package app.pulse4.sync

import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.sharing.CircleAdminPolicy
import app.pulse4.sharing.Field
import app.pulse4.sharing.Group
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.TimeScope
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncExtensionsTest {
    private val d = LocalDate.of(2026, 11, 15)

    @Test
    fun `ShareBuilder respects source-scoped ShareGrant`() {
        val observations = listOf(
            MetricObservation("w1", Provider.WITHINGS_API, "aw", "1", MetricType.WEIGHT, 100L, null, 72.5, "Europe/Prague", d.toString()),
            MetricObservation("g1", Provider.GARMIN_PERSONAL, "ag", "2", MetricType.WEIGHT, 200L, null, 73.0, "Europe/Prague", d.toString()),
            MetricObservation("g2", Provider.GARMIN_PERSONAL, "ag", "3", MetricType.SLEEP_DURATION, 300L, null, 25_000.0, "Europe/Prague", d.toString()),
        )
        val grant = ShareGrant(
            id = "g-src",
            ownerId = "josef",
            recipients = setOf("bob"),
            fields = setOf(Field.WEIGHT, Field.SLEEP_DURATION),
            scope = TimeScope.from(d),
            createdAtMs = 1L,
            sources = setOf(Provider.WITHINGS_API),
        )
        val ledger = object : SentLedger {
            override fun last(recipient: String, objectKey: String): SentRecord? = null
        }
        val sb = ShareBuilder("josef", MessageBuilder("josef") { "ev-1" })
        val planned = sb.plan(listOf(grant), emptyMap(), listOf(d), observations, ledger, nowMs = 500L)
        assertEquals(1, planned.size)
        val payload = planned.single().message.payload
        assertTrue(payload.contains("WEIGHT"))
        assertTrue(payload.contains("72.5"))
        assertFalse(payload.contains("SLEEP_DURATION"))
    }

    @Test
    fun `membershipUpdate supports handover acceptance and rejects zero-admin non-dissolved updates`() {
        val store = MemReceiverStore(setOf("josef", "bob", "eva"))
        val receiver = Receiver("alice", store, BuiltInHandlers.all(), AckSink { _, _ -> })
        val josefMsg = MessageBuilder("josef") { "j-${System.nanoTime()}" }
        val bobMsg = MessageBuilder("bob") { "b-${System.nanoTime()}" }
        val evaMsg = MessageBuilder("eva") { "e-${System.nanoTime()}" }

        val g1 = Group("grp", "Circle", "josef", "josef", setOf("josef", "bob", "eva"), version = 1L)
        val m1 = josefMsg.build("alice", Kinds.MEMBERSHIP_UPDATE, "group", "grp", 1L, Wire.json.encodeToString(Group.serializer(), g1), 1L)
        assertIs<Outcome.Applied>(receiver.receive("josef", Wire.encode(m1), 1L))

        // Reject 0-admin non-dissolved group (e.g. concurrent/offline conflict)
        val zeroAdmin = Group("grp", "Circle", "josef", "", setOf("bob", "eva"), version = 2L, admins = emptySet(), dissolved = false)
        val mZero = josefMsg.build("alice", Kinds.MEMBERSHIP_UPDATE, "group", "grp", 2L, Wire.json.encodeToString(Group.serializer(), zeroAdmin), 2L)
        assertEquals(
            RejectReason.INVALID_PAYLOAD,
            assertIs<Outcome.Rejected>(receiver.receive("josef", Wire.encode(mZero), 2L)).reason,
        )

        // Initiate handover to bob
        val g2 = assertNotNull(CircleAdminPolicy.initiateHandover(g1, actorId = "josef", targetMemberId = "bob", nowMs = 10L))
        val m2 = josefMsg.build("alice", Kinds.MEMBERSHIP_UPDATE, "group", "grp", g2.version, Wire.json.encodeToString(Group.serializer(), g2), 10L)
        assertIs<Outcome.Applied>(receiver.receive("josef", Wire.encode(m2), 10L))

        // Unrelated member eva cannot accept or hijack
        val gSteal = g2.copy(adminId = "eva", admins = setOf("eva"), pendingAdminHandover = null, version = 3L)
        val mSteal = evaMsg.build("alice", Kinds.MEMBERSHIP_UPDATE, "group", "grp", 3L, Wire.json.encodeToString(Group.serializer(), gSteal), 15L)
        assertEquals(
            RejectReason.NOT_PERMITTED,
            assertIs<Outcome.Rejected>(receiver.receive("eva", Wire.encode(mSteal), 15L)).reason,
        )

        // Handover target bob can publish accepted handover
        val g3 = assertNotNull(CircleAdminPolicy.acceptHandover(g2, actorId = "bob", nowMs = 20L, removeInitiator = true))
        val m3 = bobMsg.build("alice", Kinds.MEMBERSHIP_UPDATE, "group", "grp", g3.version, Wire.json.encodeToString(Group.serializer(), g3), 20L)
        assertIs<Outcome.Applied>(receiver.receive("bob", Wire.encode(m3), 20L))
        assertEquals(setOf("bob"), store.groups.getValue("grp").effectiveAdmins)
    }

    @Test
    fun `RnsNodeQr roundtrip and validation rejects malformed, unsupported, and malicious payloads`() {
        val cfg = RnsNodeConfig(
            name = "Home Hub",
            host = "rns.example.org",
            port = 4242,
            transport = RnsTransportType.TCP_CLIENT,
            propagationNode = "0123456789abcdef0123456789abcdef",
        )
        val encoded = RnsNodeQr.encode(cfg)
        val decoded = RnsNodeQr.decodeAndValidate(encoded).getOrThrow()
        assertEquals(cfg, decoded)

        // Without propagationNode
        val minimal = RnsNodeConfig(name = "Local", host = "192.168.1.50", port = 4965, transport = RnsTransportType.UDP)
        assertEquals(minimal, RnsNodeQr.decodeAndValidate(RnsNodeQr.encode(minimal)).getOrThrow())

        // Reject blank / oversized
        assertTrue(RnsNodeQr.decodeAndValidate("   ").isFailure)
        assertTrue(RnsNodeQr.decodeAndValidate("""{"v":1,"type":"rns_node","name":"${"a".repeat(2050)}","host":"h","port":4242}""").isFailure)

        // Reject unsupported version / wrong type
        assertTrue(RnsNodeQr.decodeAndValidate("""{"v":2,"type":"rns_node","name":"n","host":"h","port":4242}""").isFailure)
        assertTrue(RnsNodeQr.decodeAndValidate("""{"v":1,"type":"other","name":"n","host":"h","port":4242}""").isFailure)

        // Reject invalid host & port & propagationNode
        assertTrue(RnsNodeQr.decodeAndValidate("""{"v":1,"type":"rns_node","name":"n","host":"http://h","port":4242}""").isFailure)
        assertTrue(RnsNodeQr.decodeAndValidate("""{"v":1,"type":"rns_node","name":"n","host":"bad host","port":4242}""").isFailure)
        assertTrue(RnsNodeQr.decodeAndValidate("""{"v":1,"type":"rns_node","name":"n","host":"h","port":0}""").isFailure)
        assertTrue(RnsNodeQr.decodeAndValidate("""{"v":1,"type":"rns_node","name":"n","host":"h","port":70000}""").isFailure)
        assertTrue(RnsNodeQr.decodeAndValidate("""{"v":1,"type":"rns_node","name":"n","host":"h","port":4242,"propagationNode":"not-hex"}""").isFailure)

        // Reject forbidden sensitive keys (top-level and nested)
        listOf("privateKey", "private_key", "seed", "token", "password", "secret", "identityKey", "identity_key").forEach { key ->
            val evil = """{"v":1,"type":"rns_node","name":"n","host":"h","port":4242,"$key":"leak"}"""
            assertTrue(RnsNodeQr.decodeAndValidate(evil).isFailure, "Expected rejection for key=$key")
            val nestedEvil = """{"v":1,"type":"rns_node","name":"n","host":"h","port":4242,"extra":{"$key":"leak"}}"""
            assertTrue(RnsNodeQr.decodeAndValidate(nestedEvil).isFailure, "Expected rejection for nested key=$key")
        }

        // Encode also validates
        assertFailsWith<IllegalArgumentException> {
            RnsNodeQr.encode(cfg.copy(port = 0))
        }
    }

    @Test
    fun wifiFirstThenDelayedXaliCopyReturnsDuplicateAndStaleWithoutOverwriting() {
        val receipts = mutableMapOf<String, String>()
        val objects = mutableMapOf<String, Pair<Long, String>>()
        val store = object : ReceiverStore {
            override fun <T> inTransaction(block: () -> T): T = block()
            override fun isVerifiedContact(identity: String): Boolean = identity == "alice"
            override fun receiptHash(eventId: String): String? = receipts[eventId]
            override fun recordReceipt(eventId: String, contentHash: String, author: String, appliedAtMs: Long) {
                receipts[eventId] = contentHash
            }
            override fun objectVersion(author: String, objectType: String, objectId: String): Long? =
                objects["$author/$objectType/$objectId"]?.first
            override fun putObject(author: String, objectType: String, objectId: String, version: Long, payload: String) {
                objects["$author/$objectType/$objectId"] = version to payload
            }
            override fun removeObject(author: String, objectType: String, objectId: String, version: Long) {
                objects.remove("$author/$objectType/$objectId")
            }
            override fun group(groupId: String): Group? = null
            override fun putGroup(group: Group) = Unit
        }
        val acks = mutableListOf<Ack>()
        val receiver = Receiver("bob", store, BuiltInHandlers.all(), AckSink { _, a -> acks += a })
        val builder = MessageBuilder("alice")

        // 1. Alice sends v1 and v2 over local Wi-Fi first -> Bob applies v2
        val msgV1 = builder.build("bob", Kinds.PROJECTION_UPSERT, "daily", "2026-11-15", 1L, """{"weightKg":75.0}""", 1_000L)
        val msgV2 = builder.build("bob", Kinds.PROJECTION_UPSERT, "daily", "2026-11-15", 2L, """{"weightKg":74.5}""", 2_000L)
        val wireV1 = Wire.encode(msgV1)
        val wireV2 = Wire.encode(msgV2)

        assertTrue(receiver.receive("alice", wireV2, 2_100L) is Outcome.Applied)
        assertEquals("""{"weightKg":74.5}""", objects["alice/daily/2026-11-15"]?.second)

        // 2. Later, Bob picks up the delayed stored copy of v2 from xali -> Duplicate, data untouched
        val dupOutcome = receiver.receive("alice", wireV2, 5_000L)
        assertTrue(dupOutcome is Outcome.Duplicate)
        assertEquals("""{"weightKg":74.5}""", objects["alice/daily/2026-11-15"]?.second)

        // 3. Later, Bob picks up an even older stored v1 from xali -> Stale, never overwrites v2
        val staleOutcome = receiver.receive("alice", wireV1, 6_000L)
        assertTrue(staleOutcome is Outcome.Stale)
        assertEquals(2L, objects["alice/daily/2026-11-15"]?.first)
        assertEquals("""{"weightKg":74.5}""", objects["alice/daily/2026-11-15"]?.second)
    }

    @Test
    fun handedToTransportRetriesAfterTimeoutAndClearsOnAck() {
        val outboxStore = InMemoryOutboxStore()
        val mgr = OutboxManager(outboxStore)
        val item = OutboxItem("ev-1", "bob", Kinds.PROJECTION_UPSERT, "daily", "2026-11-15", 1L, "g-1", byteArrayOf(1, 2), 1_000L)
        mgr.enqueue(item)

        // Sent via Wi-Fi or RNS -> HANDED_TO_TRANSPORT
        mgr.onTransportResult("ev-1", TransportResult.Handed, 1_000L)
        assertTrue(mgr.due(10_000L).isEmpty())

        // If ACK was lost, item becomes due again after >= 60s timeout
        val retried = mgr.due(1_000L + 120_000L)
        assertEquals(1, retried.size)
        assertEquals("ev-1", retried.first().eventId)

        // Once ACK arrives, item is ACKED and never due again
        assertTrue(mgr.onAck("bob", Ack("ev-1", Ack.Status.ACCEPTED)))
        assertEquals(DeliveryState.ACKED, outboxStore.find("ev-1")?.state)
        assertTrue(mgr.due(1_000L + 240_000L).isEmpty())
    }

    @Test
    fun storedInPropagationFastTracksWhenRecipientIsInLocalPeersAndMarkAttemptDoesNotDoubleCount() {
        val outboxStore = InMemoryOutboxStore()
        val mgr = OutboxManager(outboxStore)
        val item = OutboxItem("ev-prop", "bob", Kinds.PROJECTION_UPSERT, "daily", "2026-11-15", 1L, "g-1", byteArrayOf(1, 2), 1_000L)
        mgr.enqueue(item)

        mgr.onTransportResult("ev-prop", TransportResult.StoredInPropagation, 1_000L)
        assertEquals(DeliveryState.STORED_IN_PROPAGATION, outboxStore.find("ev-prop")?.state)
        assertEquals(1, outboxStore.find("ev-prop")?.attempts)

        // Not due when bob is not on local Wi-Fi
        assertTrue(mgr.due(2_000L).isEmpty())

        // Fast-tracked immediately when bob appears on local Wi-Fi
        val localDue = mgr.due(2_000L, localPeers = setOf("bob"))
        assertEquals(1, localDue.size)
        assertEquals("ev-prop", localDue.single().eventId)

        // markAttempt sets in-flight wait without double-counting attempts
        mgr.markAttempt("ev-prop", 2_000L, 15_000L)
        assertEquals(1, outboxStore.find("ev-prop")?.attempts)
        assertTrue(mgr.due(10_000L, localPeers = setOf("bob")).isEmpty())

        mgr.onTransportResult("ev-prop", TransportResult.Handed, 2_100L)
        assertEquals(DeliveryState.HANDED_TO_TRANSPORT, outboxStore.find("ev-prop")?.state)
        assertEquals(2, outboxStore.find("ev-prop")?.attempts)

        assertTrue(mgr.onAck("bob", Ack("ev-prop", Ack.Status.ACCEPTED)))
        assertEquals(DeliveryState.ACKED, outboxStore.find("ev-prop")?.state)
    }
}
