package app.pulse4.android.reticulum

import app.pulse4.android.data.AppDatabase
import app.pulse4.android.data.RoomReceiverStore
import app.pulse4.android.data.Settings
import app.pulse4.domain.PLog
import app.pulse4.sync.AckSink
import app.pulse4.sync.BuiltInHandlers
import app.pulse4.sync.DeliveryState
import app.pulse4.sync.KindHandler
import app.pulse4.sync.MessageBuilder
import app.pulse4.sync.Outcome
import app.pulse4.sync.OutboxManager
import app.pulse4.sync.Receiver
import app.pulse4.sync.TransportResult
import app.pulse4.sync.Wire
import app.pulse4.sync.WireMessage

data class PumpStats(val sent: Int, val received: Int, val rejected: Int, val pendingInFlight: Int)

/**
 * Spojení outboxu, příjmu a Reticula. Běží v okně, které přidělí systém (WorkManager) nebo uživatel (popředí);
 * gateway sama telefon neprobudí. Stavy doručení hlásí jen to, co lze doložit:
 *  - HANDED: předáno transportu (Wi-Fi / TCP zápis / deposit před PROOF; stále ne potvrzení),
 *  - STORED: uloženo v propagation uzlu (potvrzeno RNS PROOF paketem),
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
    private val pumpLock = Any()
    private val syncInboxLock = Any()
    private val inFlightWaitMs = 15_000L
    @Volatile
    private var pumpActiveCallback: ((Outcome) -> Unit)? = null
    @Volatile
    var forceGateway: Boolean = false

    @Synchronized
    private fun receiver(selfId: String): Receiver = receiver ?: Receiver(
        selfId = selfId,
        store = RoomReceiverStore(db),
        handlers = BuiltInHandlers.all() + extraHandlers(),
        ackSink = AckSink { from, ack ->
            outbox.onAck(from, ack).also { handled ->
                if (handled) {
                    bridge.purgeDepositCache(ack.refEventId, from)
                }
            }
        },
        builder = MessageBuilder(selfId),
    ).also { receiver = it }

    internal fun ensureContactsRegistered() {
        val knownContacts = runCatching { db.contacts().allNow() }.getOrDefault(emptyList())
        for (c in knownContacts) {
            runCatching { bridge.addContact(c.publicKeyB64) }
        }
    }

    /** Okamžité zpracování příchozí události (z lokální Wi-Fi nebo Reticulum TCP brány). */
    fun processEvent(ev: BridgeEvent): Outcome? = processEventInternal(ev, deferredAcks = null)

    private fun processEventInternal(ev: BridgeEvent, deferredAcks: MutableList<WireMessage>?): Outcome? {
        when (ev) {
            is BridgeEvent.Delivery -> {
                // R4-8: REJECTED_BY_NODE / TOO_LARGE = trvalé selhání (FAILED_PERMANENT), ne nekonečné opakování.
                outbox.onTransportResult(ev.eventId, TransportResult.fromBridge(ev.state, ev.reason), System.currentTimeMillis())
                return null
            }

            is BridgeEvent.Message -> {
                ensureContactsRegistered()
                val info = bridge.ensureStarted()
                val rx = receiver(info.deliveryHash)
                val usePropagation = settings.propagationNode.isNotBlank()
                PLog.i("Pulse4Sync") { "INCOMING MESSAGE from ${ev.source}, validated=${ev.validated}, len=${ev.content.length}" }
                if (!ev.validated) {
                    PLog.w("Pulse4Sync") { "REJECTED: ev.validated is false!" }
                    return null
                }
                val outcome = rx.receive(ev.source, ev.content.toByteArray(Charsets.UTF_8), System.currentTimeMillis())
                PLog.i("Pulse4Sync") { "OUTCOME: $outcome" }
                pumpActiveCallback?.invoke(outcome)
                // ACK až po commitu transakce; ztracené ACK vyvolá opakování, které se znovu potvrdí.
                outcome.ack?.let { ack ->
                    if (deferredAcks != null) {
                        deferredAcks.add(ack)
                    } else {
                        runCatching {
                            bridge.send(ack.envelope.recipient, Wire.encode(ack).toString(Charsets.UTF_8), usePropagation, ack.envelope.eventId)
                        }
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
        val usePropagation = settings.propagationNode.isNotBlank()
        ensureContactsRegistered()
        val preLocalPeers = if (forceGateway) emptySet() else bridge.localPeerHashes()
        if (usePropagation) {
            // NEW-5: Run stamp PoW outside pumpLock so incoming message processing is not blocked
            for (item in outbox.due(nowMs, localPeers = preLocalPeers)) {
                if (item.recipient !in preLocalPeers) {
                    runCatching {
                        bridge.preparePropagationDeposit(item.recipient, item.wire.toString(Charsets.UTF_8), item.eventId)
                    }
                }
            }
        }
        val deferredAcks = mutableListOf<WireMessage>()
        val stats = synchronized(pumpLock) {
            val rx = receiver(info.deliveryHash)
            var sent = 0; var received = 0; var rejected = 0

            // NEW-7: Vyčisti .lxd cache pro zrušené / nahrazené / potvrzené položky ještě před purge
            runCatching {
                val terminalStates = setOf(
                    DeliveryState.CANCELLED.name,
                    DeliveryState.SUPERSEDED.name,
                    DeliveryState.ACKED.name,
                    DeliveryState.FAILED_PERMANENT.name,
                )
                for (row in db.outbox().all()) {
                    if (row.state in terminalStates) {
                        bridge.purgeDepositCache(row.eventId, row.recipient)
                    }
                }
            }

            // Průběžně vyčisti staré již doručené / nahrazené / zrušené položky z outboxu (starší než 24 hodin)
            runCatching { db.outbox().purge(nowMs - 24L * 3600L * 1000L) }

            // Ujisti se, že všechny známé kontakty z databáze jsou zaregistrovány v RNS/LXMF směrovací tabulce
            ensureContactsRegistered()

            val localPeers = if (forceGateway) emptySet() else bridge.localPeerHashes()
            val unreachableRecipients = HashSet<String>()
            val probedRecipients = HashMap<String, Boolean>()
            for (item in outbox.due(nowMs, localPeers = localPeers)) {
                if (item.recipient in unreachableRecipients) continue
                val isReachable = probedRecipients.getOrPut(item.recipient) {
                    bridge.probeRecipient(item.recipient, usePropagation)
                }
                if (!isReachable) {
                    unreachableRecipients += item.recipient
                    outbox.deferRecipient(item.recipient, nowMs, 5 * 60_000L)
                    continue
                }
                try {
                    // Označit pokus před odesláním, aby asynchronní Delivery event nepřepsal backoff
                    outbox.markAttempt(item.eventId, nowMs, inFlightWaitMs)
                    bridge.send(item.recipient, item.wire.toString(Charsets.UTF_8), propagated = usePropagation, eventId = item.eventId, forceGateway = forceGateway)
                    sent++
                } catch (e: Exception) {
                    unreachableRecipients += item.recipient
                    outbox.onTransportResult(item.eventId, TransportResult.Failed(e.javaClass.simpleName), nowMs)
                    outbox.deferRecipient(item.recipient, nowMs, 5 * 60_000L)
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
                val idleCapMs = if (sent > 0) windowMs else minOf(windowMs, if (bridge.isGatewayOnline || usePropagation) 1_500L else 500L)
                val deadline = System.currentTimeMillis() + idleCapMs
                while (true) {
                    for (ev in bridge.drainEvents()) {
                        processEventInternal(ev, deferredAcks)
                    }
                    if (System.currentTimeMillis() >= deadline) break
                    Thread.sleep(500)
                }
            } finally {
                pumpActiveCallback = null
            }
            val inFlight = outbox.due(Long.MAX_VALUE / 2, localPeers = localPeers).size
            PumpStats(sent, received, rejected, inFlight)
        }
        // R3-14: Run syncInbox outside pumpLock so /get and Resource downloads do not block sending
        var syncReceived = 0
        var syncRejected = 0
        if (usePropagation) {
            synchronized(syncInboxLock) {
                runCatching {
                    bridge.syncInbox { msg ->
                        val outcome = processEventInternal(msg, deferredAcks)
                        when (outcome) {
                            is Outcome.Rejected -> syncRejected++
                            is Outcome.AckProcessed, is Outcome.Duplicate, is Outcome.Stale, null -> Unit
                            else -> {
                                syncReceived++
                                onApplied()
                            }
                        }
                        outcome is Outcome.Applied || outcome is Outcome.Duplicate || outcome is Outcome.Stale || outcome is Outcome.AckProcessed
                    }
                }
            }
        }
        // NEW-5: Run ACK stamp PoW & dispatch outside pumpLock
        if (deferredAcks.isNotEmpty()) {
            val postLocalPeers = if (forceGateway) emptySet() else bridge.localPeerHashes()
            for (ack in deferredAcks) {
                val ackJson = Wire.encode(ack).toString(Charsets.UTF_8)
                if (usePropagation && ack.envelope.recipient !in postLocalPeers) {
                    runCatching {
                        bridge.preparePropagationDeposit(ack.envelope.recipient, ackJson, ack.envelope.eventId)
                    }
                }
                runCatching {
                    bridge.send(ack.envelope.recipient, ackJson, usePropagation, ack.envelope.eventId, forceGateway = forceGateway)
                }
            }
        }
        return PumpStats(
            sent = stats.sent,
            received = stats.received + syncReceived,
            rejected = stats.rejected + syncRejected,
            pendingInFlight = stats.pendingInFlight,
        )
    }
}
