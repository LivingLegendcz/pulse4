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
    private val inFlightWaitMs = 5 * 60_000L

    private fun receiver(selfId: String): Receiver = receiver ?: Receiver(
        selfId = selfId,
        store = RoomReceiverStore(db),
        handlers = BuiltInHandlers.all() + extraHandlers(),
        ackSink = AckSink { from, ack -> outbox.onAck(from, ack) },
        builder = MessageBuilder(selfId),
    ).also { receiver = it }

    /**
     * Jedno okno synchronizace: odeslat splatné, vyzvednout schránku, zpracovat události až do [windowMs].
     * Nikdy nemaže vlastní archiv; nepotvrzené položky zůstávají v outboxu.
     */
    fun pump(nowMs: Long, windowMs: Long, onApplied: () -> Unit = {}): PumpStats {
        val info = bridge.ensureStarted()
        val rx = receiver(info.deliveryHash)
        val usePropagation = settings.propagationNode.isNotBlank()
        var sent = 0; var received = 0; var rejected = 0

        for (item in outbox.due(nowMs)) {
            try {
                bridge.send(item.recipient, item.wire.toString(Charsets.UTF_8), propagated = usePropagation, eventId = item.eventId)
                outbox.markAttempt(item.eventId, nowMs, inFlightWaitMs)
                sent++
            } catch (e: Exception) {
                outbox.onTransportResult(item.eventId, TransportResult.Failed(e.javaClass.simpleName), nowMs)
            }
        }
        if (usePropagation) runCatching { bridge.syncInbox() }

        val deadline = System.currentTimeMillis() + windowMs
        while (true) {
            for (ev in bridge.drainEvents()) when (ev) {
                is BridgeEvent.Delivery -> outbox.onTransportResult(ev.eventId, when (ev.state) {
                    "HANDED" -> TransportResult.Handed
                    "STORED" -> TransportResult.StoredInPropagation
                    else -> TransportResult.Failed(ev.reason ?: "failed")
                }, System.currentTimeMillis())

                is BridgeEvent.Message -> {
                    if (!ev.validated) { rejected++; continue } // podpis neověřen → zahodit
                    val outcome = rx.receive(ev.source, ev.content.toByteArray(Charsets.UTF_8), System.currentTimeMillis())
                    when (outcome) {
                        is Outcome.Rejected -> rejected++
                        is Outcome.AckProcessed -> Unit
                        else -> { received++; onApplied() }
                    }
                    // ACK až po commitu transakce; ztracené ACK vyvolá opakování, které se znovu potvrdí.
                    outcome.ack?.let { ack ->
                        runCatching {
                            bridge.send(ack.envelope.recipient, app.pulse4.sync.Wire.encode(ack).toString(Charsets.UTF_8), usePropagation, ack.envelope.eventId)
                        }
                    }
                }
            }
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(500)
        }
        val inFlight = outbox.due(Long.MAX_VALUE / 2).size
        return PumpStats(sent, received, rejected, inFlight)
    }
}
