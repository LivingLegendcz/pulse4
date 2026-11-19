package app.pulse4.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R4-8: FAILED(REJECTED_BY_NODE / TOO_LARGE) is terminal in the outbox, not an endless retry loop. */
class OutboxPermanentFailureTest {
    private val josef = MessageBuilder("josef")

    private fun item(m: WireMessage, at: Long = 0) =
        OutboxItem(m.envelope.eventId, m.envelope.recipient, m.envelope.kind, m.envelope.objectType, m.envelope.objectId, m.envelope.objectVersion, m.envelope.grantRef, Wire.encode(m), at)

    @Test
    fun `bridge failure reasons map to permanent or transient results`() {
        assertEquals(TransportResult.Handed, TransportResult.fromBridge("HANDED", null))
        assertEquals(TransportResult.StoredInPropagation, TransportResult.fromBridge("STORED", null))
        assertEquals(TransportResult.Rejected("REJECTED_BY_NODE"), TransportResult.fromBridge("FAILED", "REJECTED_BY_NODE"))
        assertEquals(TransportResult.Rejected("TOO_LARGE: 40 KB > 32 KB"), TransportResult.fromBridge("FAILED", "TOO_LARGE: 40 KB > 32 KB"))
        assertEquals(TransportResult.Failed("LINK_TEARDOWN"), TransportResult.fromBridge("FAILED", "LINK_TEARDOWN"))
        assertEquals(TransportResult.Failed("RESOURCE_TIMEOUT"), TransportResult.fromBridge("FAILED", "RESOURCE_TIMEOUT"))
        assertEquals(TransportResult.Failed("failed"), TransportResult.fromBridge("FAILED", null))
    }

    @Test
    fun `rejected by node is terminal, never due again and a late HANDED does not resurrect it`() {
        val store = InMemoryOutboxStore()
        val ob = OutboxManager(store)
        val m = josef.build("bob", Kinds.PROJECTION_UPSERT, "activity", "garmin-1", 1, "{}", 0)
        ob.enqueue(item(m))

        ob.onTransportResult(m.envelope.eventId, TransportResult.fromBridge("FAILED", "REJECTED_BY_NODE"), nowMs = 1_000L)
        val failed = store.find(m.envelope.eventId)!!
        assertEquals(DeliveryState.FAILED_PERMANENT, failed.state)
        assertEquals("REJECTED_BY_NODE", failed.lastError)

        // Late HANDED (emitted by the send thread after the RCL) and transient FAILED are ignored.
        ob.onTransportResult(m.envelope.eventId, TransportResult.Handed, nowMs = 2_000L)
        ob.onTransportResult(m.envelope.eventId, TransportResult.Failed("LINK_TEARDOWN"), nowMs = 3_000L)
        assertEquals(DeliveryState.FAILED_PERMANENT, store.find(m.envelope.eventId)!!.state)

        // Never retried, not counted as queued bytes, no pending item for the recipient.
        for (t in listOf(10_000L, 7L * 24 * 3600 * 1000, 30L * 24 * 3600 * 1000)) {
            assertTrue(ob.due(t).isEmpty())
        }
        assertEquals(0L, ob.queuedBytes())
        assertFalse(ob.hasPendingFor("bob"))

        // An ACK from the recipient (e.g. direct delivery succeeded meanwhile) still wins.
        assertTrue(ob.onAck("bob", Ack(m.envelope.eventId, Ack.Status.ACCEPTED, null)))
        assertEquals(DeliveryState.ACKED, store.find(m.envelope.eventId)!!.state)
    }

    @Test
    fun `transient failure keeps retrying with backoff`() {
        val store = InMemoryOutboxStore()
        val ob = OutboxManager(store)
        val m = josef.build("bob", Kinds.PROJECTION_UPSERT, "daily", "2026-11-18", 1, "{}", 0)
        ob.enqueue(item(m))
        ob.onTransportResult(m.envelope.eventId, TransportResult.fromBridge("FAILED", "PROOF_TIMEOUT"), nowMs = 1_000L)
        assertEquals(DeliveryState.QUEUED, store.find(m.envelope.eventId)!!.state)
        assertEquals(1, ob.due(1_000L + 7L * 3600 * 1000).size)
    }
}
