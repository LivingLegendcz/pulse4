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

    @Test
    fun `N-2 enqueue supersedes pending PROJECTION_REMOVE when upsert follows and vice versa`() {
        val store = InMemoryOutboxStore()
        val ob = OutboxManager(store)

        val rm1 = josef.build("bob", Kinds.PROJECTION_REMOVE, "daily", "2026-11-18", objectVersion = 1_000_000L, payload = "{}", nowMs = 1_000L)
        ob.enqueue(item(rm1, at = 1_000L))
        val up1 = josef.build("bob", Kinds.PROJECTION_UPSERT, "daily", "2026-11-18", objectVersion = 1L, payload = "{}", nowMs = 1_100L)
        val res1 = ob.enqueue(item(up1, at = 1_100L)) as EnqueueResult.Queued
        assertEquals(listOf(rm1.envelope.eventId), res1.superseded)
        assertEquals(DeliveryState.SUPERSEDED, store.find(rm1.envelope.eventId)!!.state)
        assertEquals(listOf(up1.envelope.eventId), ob.due(1_100L).map { it.eventId })

        val rm2 = josef.build("bob", Kinds.PROJECTION_REMOVE, "daily", "2026-11-18", objectVersion = 1_200L, payload = "{}", nowMs = 1_200L)
        val res2 = ob.enqueue(item(rm2, at = 1_200L)) as EnqueueResult.Queued
        assertEquals(listOf(up1.envelope.eventId), res2.superseded)
        assertEquals(DeliveryState.SUPERSEDED, store.find(up1.envelope.eventId)!!.state)
        assertEquals(listOf(rm2.envelope.eventId), ob.due(1_200L).map { it.eventId })
    }

    @Test
    fun `N-2 due masks older in-flight upsert when newer PROJECTION_REMOVE is queued`() {
        val store = InMemoryOutboxStore()
        val ob = OutboxManager(store)

        val oldUpsert = josef.build("bob", Kinds.PROJECTION_UPSERT, "daily", "2026-11-18", objectVersion = 1L, payload = "{}", nowMs = 1_000L)
        ob.enqueue(item(oldUpsert, at = 1_000L))
        ob.onTransportResult(oldUpsert.envelope.eventId, TransportResult.Handed, nowMs = 1_000L)

        val newerRemove = josef.build("bob", Kinds.PROJECTION_REMOVE, "daily", "2026-11-18", objectVersion = 2_000L, payload = "{}", nowMs = 2_000L)
        ob.enqueue(item(newerRemove, at = 2_000L))

        val dueIds = ob.due(100_000L).map { it.eventId }
        assertEquals(listOf(newerRemove.envelope.eventId), dueIds)
    }

    @Test
    fun `N-6 cancelForGrant cancels queued items referencing any contributing grantRef`() {
        val store = InMemoryOutboxStore()
        val ob = OutboxManager(store)
        val m = josef.build("bob", Kinds.PROJECTION_UPSERT, "daily", "2026-11-18", 1L, "{}", 100L, grantRef = "g1")
        ob.enqueue(
            OutboxItem(
                m.envelope.eventId, "bob", m.envelope.kind, m.envelope.objectType, m.envelope.objectId,
                m.envelope.objectVersion, "g1", Wire.encode(m), 100L, grantRefs = listOf("g1", "g2"),
            )
        )

        val cancel = ob.cancelForGrant("g2")
        assertEquals(1, cancel.cancelled)
        assertEquals(DeliveryState.CANCELLED, store.find(m.envelope.eventId)!!.state)
    }

    @Test
    fun `RE-5 NOT_PERMITTED invokes onPermissionDenied callback without scheduling snapshot reset`() {
        val store = InMemoryOutboxStore()
        val denied = mutableListOf<Pair<String, String>>()
        val ob = OutboxManager(store, onPermissionDenied = { r, k -> denied += r to k })
        val m = josef.build("child", Kinds.PROJECTION_UPSERT, "own_weight", "w-1", 1_000L, "{}", 100L)
        ob.enqueue(item(m, at = 100L))

        assertTrue(ob.onAck("child", Ack(m.envelope.eventId, Ack.Status.REJECTED, RejectReason.NOT_PERMITTED.name)))
        assertEquals(DeliveryState.CANCELLED, store.find(m.envelope.eventId)!!.state)
        assertEquals(listOf("child" to "own_weight/w-1"), denied)
        assertTrue(ob.expireStale(200L).isEmpty())
    }

    @Test
    fun `R3-4 NOT_PERMITTED on family weight push keeps CANCELLED without invoking onPermissionDenied`() {
        val store = InMemoryOutboxStore()
        val denied = mutableListOf<Pair<String, String>>()
        val ob = OutboxManager(store, onPermissionDenied = { r, k -> denied += r to k })
        val m = josef.build("child", "family.weight.push", "own_weight", "w-1", 1L, "{}", 100L)
        ob.enqueue(item(m, at = 100L))

        assertTrue(ob.onAck("child", Ack(m.envelope.eventId, Ack.Status.REJECTED, RejectReason.NOT_PERMITTED.name)))
        assertEquals(DeliveryState.CANCELLED, store.find(m.envelope.eventId)!!.state)
        assertTrue(denied.isEmpty())
        assertTrue(ob.expireStale(200L).isEmpty())
    }
}
