package app.pulse4.android.reticulum

import app.pulse4.android.data.AppDatabase
import app.pulse4.android.data.RoomReceiverStore
import app.pulse4.android.data.Settings
import app.pulse4.sync.AckSink
import app.pulse4.sync.BuiltInHandlers
import app.pulse4.sync.KindHandler
import app.pulse4.sync.MessageBuilder
import app.pulse4.sync.Outcome
import app.pulse4.sync.OutboxManager
import app.pulse4.sync.Receiver
import app.pulse4.sync.TransportResult

data class PumpStats(val sent: Int, val received: Int, val rejected: Int, val pendingInFlight: Int)

/**
 * Spojení outboxu, příjmu a Reticula. Běží v okně, které přidělí systém (WorkManager) nebo uživatel (popředí);
 * gateway sama telefon neprobudí. Stavy doručení hlásí jen to, co lze doložit:
 *  - HANDED: LXMF doručení příjemci prokázáno (stále ne zpracování aplikací),
 *  - STORED: uloženo v propagation uzlu,
 *  - ACKED: teprve aplikační ACK od příjemcovy aplikace (viz [OutboxManager.onAck]).
 */
class ReticulumTransport(
    private val bridge: ReticulumBridge,
    private val settings: Settings,
    private val db: AppDatabase,
    private val outbox: OutboxManager,
    private val extraHandlers: () -> Map<String, KindHandler>,
) {
    private var receiver: Receiver? = null
    private val inFlightWaitMs = 15_000L
    @Volatile
    private var pumpActiveCallback: ((Outcome) -> Unit)? = null
    @Volatile
    var forceGateway: Boolean = false

    private fun receiver(selfId: String): Receiver = receiver ?: Receiver(
        selfId = selfId,
        store = RoomReceiverStore(db),
        handlers = BuiltInHandlers.all() + extraHandlers(),
        ackSink = AckSink { from, ack -> outbox.onAck(from, ack) },
        builder = MessageBuilder(selfId),
    ).also { receiver = it }

    private fun ensureContactsRegistered() {
        if (!bridge.hasRegisteredContacts()) {
            val knownContacts = runCatching { db.contacts().allNow() }.getOrDefault(emptyList())
            for (c in knownContacts) {
                runCatching { bridge.addContact(c.publicKeyB64) }
            }
        }
    }

    /** Okamžité zpracování příchozí události (z lokální Wi-Fi nebo Reticulum TCP brány). */
    fun processEvent(ev: BridgeEvent): Outcome? {
        when (ev) {
            is BridgeEvent.Delivery -> {
                outbox.onTransportResult(ev.eventId, when (ev.state) {
                    "HANDED" -> TransportResult.Handed
                    "STORED" -> TransportResult.StoredInPropagation
                    else -> TransportResult.Failed(ev.reason ?: "failed")
                }, System.currentTimeMillis())
                return null
            }

            is BridgeEvent.Message -> {
                ensureContactsRegistered()
                val info = bridge.ensureStarted()
                val rx = receiver(info.deliveryHash)
                val usePropagation = settings.propagationNode.isNotBlank()
                android.util.Log.i("Pulse4Sync", "INCOMING MESSAGE from ${ev.source}, validated=${ev.validated}, len=${ev.content.length}")
                if (!ev.validated) {
                    android.util.Log.w("Pulse4Sync", "REJECTED: ev.validated is false!")
                    return null
                }
                val outcome = rx.receive(ev.source, ev.content.toByteArray(Charsets.UTF_8), System.currentTimeMillis())
                android.util.Log.i("Pulse4Sync", "OUTCOME: $outcome")
                pumpActiveCallback?.invoke(outcome)
                // ACK až po commitu transakce; ztracené ACK vyvolá opakování, které se znovu potvrdí.
                outcome.ack?.let { ack ->
                    runCatching {
                        bridge.send(ack.envelope.recipient, app.pulse4.sync.Wire.encode(ack).toString(Charsets.UTF_8), usePropagation, ack.envelope.eventId)
                    }
                }
                return outcome
            }
        }
    }

    /**
     * Jedno okno synchronizace: odeslat splatné, vyzvednout schránku, zpracovat události až do [windowMs].
     * Nikdy nemaže vlastní archiv; nepotvrzené položky zůstávají v outboxu.
     */
    fun pump(nowMs: Long, windowMs: Long, onApplied: () -> Unit = {}): PumpStats {
        val info = bridge.ensureStarted()
        val rx = receiver(info.deliveryHash)
        val usePropagation = settings.propagationNode.isNotBlank()
        var sent = 0; var received = 0; var rejected = 0

        // Průběžně vyčisti staré již doručené / nahrazené / zrušené položky z outboxu (starší než 24 hodin)
        runCatching { db.outbox().purge(nowMs - 24L * 3600L * 1000L) }

        // Ujisti se, že všechny známé kontakty z databáze jsou zaregistrovány v RNS/LXMF směrovací tabulce
        val knownContacts = runCatching { db.contacts().allNow() }.getOrDefault(emptyList())
        for (c in knownContacts) {
            runCatching { bridge.addContact(c.publicKeyB64) }
        }

        val localPeers = if (forceGateway) emptySet() else bridge.localPeerHashes()
        for (item in outbox.due(nowMs, localPeers = localPeers)) {
            try {
                // Označit pokus před odesláním, aby asynchronní Delivery event nepřepsal backoff
                outbox.markAttempt(item.eventId, nowMs, inFlightWaitMs)
                bridge.send(item.recipient, item.wire.toString(Charsets.UTF_8), propagated = usePropagation, eventId = item.eventId, forceGateway = forceGateway)
                sent++
            } catch (e: Exception) {
                outbox.onTransportResult(item.eventId, TransportResult.Failed(e.javaClass.simpleName), nowMs)
            }
        }

        pumpActiveCallback = { outcome ->
            when (outcome) {
                is Outcome.Rejected -> rejected++
                is Outcome.AckProcessed, is Outcome.Duplicate, is Outcome.Stale -> Unit
                else -> { received++; onApplied() }
            }
        }
        try {
            if (usePropagation) {
                runCatching {
                    bridge.syncInbox { msg ->
                        val outcome = processEvent(msg)
                        outcome is Outcome.Applied || outcome is Outcome.Duplicate || outcome is Outcome.Stale || outcome is Outcome.AckProcessed
                    }
                }
            }

            val idleCapMs = if (sent > 0) windowMs else minOf(windowMs, if (bridge.isGatewayOnline || usePropagation) 1_500L else 500L)
            val deadline = System.currentTimeMillis() + idleCapMs
            while (true) {
                for (ev in bridge.drainEvents()) {
                    processEvent(ev)
                }
                if (System.currentTimeMillis() >= deadline) break
                Thread.sleep(500)
            }
        } finally {
            pumpActiveCallback = null
        }
        val inFlight = outbox.due(Long.MAX_VALUE / 2, localPeers = localPeers).size
        return PumpStats(sent, received, rejected, inFlight)
    }
}
