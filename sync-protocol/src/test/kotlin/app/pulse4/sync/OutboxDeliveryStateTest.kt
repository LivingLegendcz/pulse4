package app.pulse4.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutboxDeliveryStateTest {
    private val josef = MessageBuilder("josef")

    private fun item(m: WireMessage, at: Long = 0) =
        OutboxItem(m.envelope.eventId, m.envelope.recipient, m.envelope.kind, m.envelope.objectType, m.envelope.objectId, m.envelope.objectVersion, m.envelope.grantRef, Wire.encode(m), at)

    @Test
    fun `STORED_IN_PROPAGATION retries after retry window and expires after TTL`() {
        val store = InMemoryOutboxStore()
        val limits = ProtocolLimits(
            unackedSummaryTtlMs = 7L * 24 * 3600 * 1000,
            storedPropagationRetryMs = 6L * 3600 * 1000,
            storedPropagationTtlMs = 14L * 24 * 3600 * 1000,
        )
        val ob = OutboxManager(store, limits)
        val m = josef.build("bob", Kinds.PROJECTION_UPSERT, "daily", "2026-11-18", 1, "{}", 0)
        ob.enqueue(item(m, at = 0))

        ob.onTransportResult(m.envelope.eventId, TransportResult.StoredInPropagation, nowMs = 1_000L)
        assertEquals(DeliveryState.STORED_IN_PROPAGATION, store.find(m.envelope.eventId)!!.state)
        assertTrue(ob.due(1_000L + 3600_000L).isEmpty())

        // After 6h retry window, item becomes due again so it never hangs forever
        assertEquals(1, ob.due(1_000L + limits.storedPropagationRetryMs).size)

        // At 8 days (existing test expectation), still STORED_IN_PROPAGATION
        assertTrue(ob.expireStale(8L * 24 * 3600 * 1000).isEmpty())
        assertEquals(DeliveryState.STORED_IN_PROPAGATION, store.find(m.envelope.eventId)!!.state)

        // After 14 days TTL, expires for snapshot replacement
        assertEquals(listOf("bob" to "daily/2026-11-18"), ob.expireStale(15L * 24 * 3600 * 1000))
        assertEquals(DeliveryState.EXPIRED_NEEDS_SNAPSHOT, store.find(m.envelope.eventId)!!.state)
    }

    @Test
    fun `REJECTED ACK triggers snapshot reset in expireStale`() {
        val store = InMemoryOutboxStore()
        val ob = OutboxManager(store)
        val m = josef.build("bob", Kinds.PROJECTION_UPSERT, "daily", "2026-11-18", 1, "{}", 0)
        ob.enqueue(item(m, at = 0))

        assertTrue(ob.onAck("bob", Ack(m.envelope.eventId, Ack.Status.REJECTED, "GAP")))
        assertEquals(DeliveryState.EXPIRED_NEEDS_SNAPSHOT, store.find(m.envelope.eventId)!!.state)

        // Next expireStale drains the reset so Services.kt resets sentLedger
        assertEquals(listOf("bob" to "daily/2026-11-18"), ob.expireStale(100L))
        assertTrue(ob.expireStale(200L).isEmpty())
    }
}
