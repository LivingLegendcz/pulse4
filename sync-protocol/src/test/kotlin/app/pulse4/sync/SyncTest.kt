package app.pulse4.sync

import app.pulse4.sharing.Group
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemReceiverStore(private val contacts: Set<String>) : ReceiverStore {
    val receipts = HashMap<String, String>()
    val objects = HashMap<String, Pair<Long, String>>()
    val groups = HashMap<String, Group>()
    var transactions = 0
    override fun <T> inTransaction(block: () -> T): T { transactions++; return block() }
    override fun isVerifiedContact(identity: String) = identity in contacts
    override fun receiptHash(eventId: String) = receipts[eventId]
    override fun recordReceipt(eventId: String, contentHash: String, author: String, appliedAtMs: Long) { receipts[eventId] = contentHash }
    private fun k(a: String, t: String, i: String) = "$a|$t|$i"
    override fun objectVersion(author: String, objectType: String, objectId: String) = objects[k(author, objectType, objectId)]?.first
    override fun putObject(author: String, objectType: String, objectId: String, version: Long, payload: String) { objects[k(author, objectType, objectId)] = version to payload }
    override fun removeObject(author: String, objectType: String, objectId: String, version: Long) { objects.remove(k(author, objectType, objectId)) }
    override fun group(groupId: String) = groups[groupId]
    override fun putGroup(group: Group) { groups[group.id] = group }
}

class SyncTest {
    private val ids = generateSequence(1) { it + 1 }.iterator()
    private val josef = MessageBuilder("josef") { "e${ids.next()}" }
    private fun receiverFor(self: String, contacts: Set<String>, sink: AckSink = AckSink { _, _ -> }, handlers: Map<String, KindHandler> = BuiltInHandlers.all()) =
        MemReceiverStore(contacts) to { st: MemReceiverStore -> Receiver(self, st, handlers, sink, builder = MessageBuilder(self) { "ack${ids.next()}" }) }

    private fun upsert(version: Long, body: String = "v$version", recipient: String = "bob", key: String = "2026-10-20") =
        josef.build(recipient, Kinds.PROJECTION_UPSERT, "daily", key, version, """{"ownerId":"josef","d":"$body"}""", nowMs = 1000L + version)

    // ---------- ověřování ----------
    @Test fun `applies once, records receipt in the same transaction, acks afterwards`() {
        val (st, mk) = receiverFor("bob", setOf("josef")); val r = mk(st)
        val m = upsert(1)
        val o = r.receive("josef", Wire.encode(m), 5)
        assertIs<Outcome.Applied>(o)
        assertEquals(1, st.transactions)
        assertTrue(m.envelope.eventId in st.receipts)
        val ack = assertNotNull(o.ack)
        assertEquals(Kinds.ACK, ack.envelope.kind); assertEquals("josef", ack.envelope.recipient); assertEquals("bob", ack.envelope.author)
    }

    @Test fun `lost ack causes resend which is acknowledged again but applied once`() {
        val (st, mk) = receiverFor("bob", setOf("josef")); val r = mk(st)
        val bytes = Wire.encode(upsert(1))
        assertIs<Outcome.Applied>(r.receive("josef", bytes, 5))
        val again = r.receive("josef", bytes, 6)
        assertIs<Outcome.Duplicate>(again); assertNotNull(again.ack)
        assertEquals(1, st.objects.size)
    }

    @Test fun `same event id with different content is a conflict`() {
        val (st, mk) = receiverFor("bob", setOf("josef")); val r = mk(st)
        val a = upsert(1)
        r.receive("josef", Wire.encode(a), 5)
        val payload = """{"ownerId":"josef","d":"jiný"}"""
        val evil = WireMessage(a.envelope.copy(contentHash = Wire.hashOf(payload)), payload)
        assertEquals(RejectReason.ID_CONFLICT, assertIs<Outcome.Rejected>(r.receive("josef", Wire.encode(evil), 6)).reason)
    }

    @Test fun `author must match authenticated transport identity and gets no reply`() {
        val (st, mk) = receiverFor("bob", setOf("josef", "eva")); val r = mk(st)
        val o = assertIs<Outcome.Rejected>(r.receive("eva", Wire.encode(upsert(1)), 5)) // eva se vydává za josefa
        assertEquals(RejectReason.AUTHOR_MISMATCH, o.reason); assertNull(o.ack)
        assertTrue(st.objects.isEmpty())
    }

    @Test fun `unknown contact is dropped silently`() {
        val (st, mk) = receiverFor("bob", setOf("eva")); val r = mk(st)
        val o = assertIs<Outcome.Rejected>(r.receive("josef", Wire.encode(upsert(1)), 5))
        assertEquals(RejectReason.UNKNOWN_CONTACT, o.reason); assertNull(o.ack)
    }

    @Test fun `wrong recipient, tampered payload, expired, oversized and unknown schema never change state`() {
        val (st, mk) = receiverFor("bob", setOf("josef")); val r = mk(st)
        fun reason(b: ByteArray, now: Long = 5) = assertIs<Outcome.Rejected>(r.receive("josef", b, now)).reason
        assertEquals(RejectReason.WRONG_RECIPIENT, reason(Wire.encode(upsert(1, recipient = "eva"))))
        val m = upsert(2)
        assertEquals(RejectReason.HASH_MISMATCH, reason(Wire.encode(m.copy(payload = """{"ownerId":"josef","d":"zmeněno"}"""))))
        val exp = josef.build("bob", Kinds.PROJECTION_UPSERT, "daily", "x", 1, "{}", 1, expiresAtMs = 10)
        assertEquals(RejectReason.EXPIRED, reason(Wire.encode(exp), now = 11))
        val big = josef.build("bob", Kinds.PROJECTION_UPSERT, "daily", "x", 1, "{\"a\":\"" + "x".repeat(ProtocolLimits().maxEventBytes + 500) + "\"}", 1)
        assertEquals(RejectReason.TOO_LARGE, reason(Wire.encode(big)))
        val unk = josef.build("bob", "future.thing", "daily", "x", 1, "{}", 1)
        assertEquals(RejectReason.UNKNOWN_SCHEMA, reason(Wire.encode(unk)))
        val newer = WireMessage(upsert(3).envelope.copy(v = 99), upsert(3).payload)
        assertEquals(RejectReason.UNKNOWN_SCHEMA, reason(Wire.encode(newer)))
        assertEquals(RejectReason.MALFORMED, reason("not json".toByteArray()))
        assertTrue(st.objects.isEmpty() && st.receipts.isEmpty())
    }

    @Test fun `older revision never rolls state back`() {
        val (st, mk) = receiverFor("bob", setOf("josef")); val r = mk(st)
        r.receive("josef", Wire.encode(upsert(5)), 1)
        assertIs<Outcome.Stale>(r.receive("josef", Wire.encode(upsert(3)), 2))
        assertEquals(5L, st.objects.values.single().first)
    }

    @Test fun `a contact cannot overwrite someone elses objects because the key contains the verified author`() {
        val (st, mk) = receiverFor("bob", setOf("josef", "eva")); val r = mk(st)
        r.receive("josef", Wire.encode(upsert(1, "josefova")), 1)
        val evaB = MessageBuilder("eva") { "ev${ids.next()}" }
        r.receive("eva", Wire.encode(evaB.build("bob", Kinds.PROJECTION_UPSERT, "daily", "2026-10-20", 9, """{"d":"evina"}""", 2)), 2)
        assertEquals(2, st.objects.size)
        assertTrue(st.objects.getValue("josef|daily|2026-10-20").second.contains("josefova"))
    }

    @Test fun `continuity gap requests snapshot without recording receipt, later redelivery still applies`() {
        val continuityHandler = KindHandler { env, p, store, _ ->
            val cur = store.objectVersion(env.author, env.objectType, env.objectId) ?: 0
            when {
                env.objectVersion <= cur -> HandlerResult.Stale
                env.objectVersion > cur + 1 -> HandlerResult.NeedSnapshot
                else -> { store.putObject(env.author, env.objectType, env.objectId, env.objectVersion, p); HandlerResult.Applied }
            }
        }
        val (st, mk) = receiverFor("bob", setOf("josef"), handlers = BuiltInHandlers.all() + (Kinds.PLAN_REVISION to continuityHandler)); val r = mk(st)
        fun rev(v: Long) = josef.build("bob", Kinds.PLAN_REVISION, "plan", "p1", v, "{}", v)
        val r3 = rev(3); val r1 = rev(1); val r2 = rev(2)
        assertIs<Outcome.NeedSnapshot>(r.receive("josef", Wire.encode(r3), 1))
        assertFalse(r3.envelope.eventId in st.receipts)
        assertIs<Outcome.Applied>(r.receive("josef", Wire.encode(r1), 2))
        assertIs<Outcome.Applied>(r.receive("josef", Wire.encode(r2), 3))
        assertIs<Outcome.Applied>(r.receive("josef", Wire.encode(r3), 4))
        assertEquals(3L, st.objects.values.single().first)
    }

    @Test fun `membership update only from the admin and never downgrades`() {
        val (st, mk) = receiverFor("bob", setOf("josef", "eva")); val r = mk(st)
        fun g(v: Long, admin: String = "josef", members: Set<String> = setOf("josef", "bob", "eva")) =
            Wire.json.encodeToString(Group.serializer(), Group("grp", "Výpravy", "josef", admin, members, v))
        assertIs<Outcome.Applied>(r.receive("josef", Wire.encode(josef.build("bob", Kinds.MEMBERSHIP_UPDATE, "group", "grp", 1, g(1), 1)), 1))
        // eva se pokusí o převzetí
        val evaB = MessageBuilder("eva") { "ev${ids.next()}" }
        val steal = evaB.build("bob", Kinds.MEMBERSHIP_UPDATE, "group", "grp", 2, g(2, admin = "eva"), 2)
        assertEquals(RejectReason.NOT_PERMITTED, assertIs<Outcome.Rejected>(r.receive("eva", Wire.encode(steal), 2)).reason)
        assertIs<Outcome.Stale>(r.receive("josef", Wire.encode(josef.build("bob", Kinds.MEMBERSHIP_UPDATE, "group", "grp", 1, g(1), 3)), 3))
        assertEquals("josef", st.groups.getValue("grp").adminId)
    }

    @Test fun `acks only count from the recipient of that event`() {
        val store = InMemoryOutboxStore(); val ob = OutboxManager(store)
        val m = upsert(1)
        ob.enqueue(item(m))
        assertFalse(ob.onAck("eva", Ack(m.envelope.eventId, Ack.Status.ACCEPTED)))
        assertEquals(DeliveryState.QUEUED, store.find(m.envelope.eventId)!!.state)
        assertTrue(ob.onAck("bob", Ack(m.envelope.eventId, Ack.Status.ACCEPTED)))
        assertEquals(DeliveryState.ACKED, store.find(m.envelope.eventId)!!.state)
    }

    // ---------- fronta ----------
    private fun item(m: WireMessage, grant: String? = "g1", at: Long = m.envelope.createdAtMs) = OutboxItem(
        m.envelope.eventId, m.envelope.recipient, m.envelope.kind, m.envelope.objectType, m.envelope.objectId,
        m.envelope.objectVersion, grant, Wire.encode(m), at,
    )

    @Test fun `newer summary supersedes only queued older ones, plan revisions are never merged`() {
        val store = InMemoryOutboxStore(); val ob = OutboxManager(store)
        val a = upsert(1); val b = upsert(2); val c = upsert(3)
        ob.enqueue(item(a))
        ob.onTransportResult(a.envelope.eventId, TransportResult.StoredInPropagation, 10) // a už leží na cizím uzlu
        ob.enqueue(item(b))
        val res = assertIs<EnqueueResult.Queued>(ob.enqueue(item(c)))
        assertEquals(listOf(b.envelope.eventId), res.superseded)
        assertEquals(DeliveryState.SUPERSEDED, store.find(b.envelope.eventId)!!.state)
        assertEquals(DeliveryState.STORED_IN_PROPAGATION, store.find(a.envelope.eventId)!!.state)
        val p1 = josef.build("bob", Kinds.PLAN_REVISION, "plan", "p", 1, "{}", 1); val p2 = josef.build("bob", Kinds.PLAN_REVISION, "plan", "p", 2, "{}", 2)
        ob.enqueue(item(p1)); assertEquals(emptyList(), (ob.enqueue(item(p2)) as EnqueueResult.Queued).superseded)
    }

    @Test fun `failure backs off with growing jittered delay and keeps the item`() {
        val store = InMemoryOutboxStore(); val ob = OutboxManager(store, random = Random(1), baseBackoffMs = 1000, maxBackoffMs = 60_000)
        val m = upsert(1); ob.enqueue(item(m))
        var now = 0L; var last = 0L
        repeat(4) {
            ob.onTransportResult(m.envelope.eventId, TransportResult.Failed("síť"), now)
            val it = store.find(m.envelope.eventId)!!
            assertEquals(DeliveryState.QUEUED, it.state)
            val delay = it.nextAttemptAtMs - now
            assertTrue(delay >= last * 1 / 2, "delay=$delay last=$last")
            assertTrue(ob.due(now).isEmpty()); assertEquals(1, ob.due(it.nextAttemptAtMs).size)
            last = delay; now = it.nextAttemptAtMs
        }
        assertTrue(last <= 72_000)
    }

    @Test fun `an item handed to transport is not sent again until the wait elapses`() {
        val store = InMemoryOutboxStore(); val ob = OutboxManager(store)
        val m = upsert(1); ob.enqueue(item(m))
        assertEquals(1, ob.due(0).size)
        ob.markAttempt(m.envelope.eventId, 0, 300_000)
        assertTrue(ob.due(299_999).isEmpty()); assertEquals(1, ob.due(300_000).size)
        assertEquals(DeliveryState.QUEUED, store.find(m.envelope.eventId)!!.state)
        ob.onTransportResult(m.envelope.eventId, TransportResult.StoredInPropagation, 1)
        ob.markAttempt(m.envelope.eventId, 2, 10) // už není ve frontě: beze změny
        assertEquals(DeliveryState.STORED_IN_PROPAGATION, store.find(m.envelope.eventId)!!.state)
    }

    @Test fun `queue quota refuses new items but never deletes existing ones`() {
        val store = InMemoryOutboxStore()
        val a = upsert(1)
        val ob = OutboxManager(store, ProtocolLimits(maxQueueBytes = (Wire.encode(a).size * 2 - 1).toLong()))
        assertIs<EnqueueResult.Queued>(ob.enqueue(item(a)))
        assertIs<EnqueueResult.OverQuota>(ob.enqueue(item(upsert(1, key = "other"))).let { if (it is EnqueueResult.Queued) ob.enqueue(item(upsert(1, key = "third"))) else it })
        assertEquals(DeliveryState.QUEUED, store.find(a.envelope.eventId)!!.state)
    }

    @Test fun `revoking a grant cancels queued items and reports what already left`() {
        val store = InMemoryOutboxStore(); val ob = OutboxManager(store)
        val a = upsert(1, key = "k1"); val b = upsert(1, key = "k2")
        ob.enqueue(item(a)); ob.enqueue(item(b))
        ob.onTransportResult(b.envelope.eventId, TransportResult.Handed, 1)
        assertEquals(OutboxManager.CancelResult(1, 1), ob.cancelForGrant("g1"))
        assertEquals(DeliveryState.CANCELLED, store.find(a.envelope.eventId)!!.state)
    }

    @Test fun `unacked summaries older than seven days are replaced by a snapshot, plan changes stay pending`() {
        val store = InMemoryOutboxStore(); val ob = OutboxManager(store)
        val s = upsert(1); val p = josef.build("bob", Kinds.PLAN_PROPOSAL, "plan", "p", 1, "{}", 1)
        ob.enqueue(item(s, at = 0)); ob.enqueue(item(p, at = 0))
        assertEquals(emptyList(), ob.expireStale(6L * 24 * 3600 * 1000))
        assertEquals(listOf("bob" to "daily/2026-10-20"), ob.expireStale(8L * 24 * 3600 * 1000))
        assertEquals(DeliveryState.EXPIRED_NEEDS_SNAPSHOT, store.find(s.envelope.eventId)!!.state)
        assertEquals(DeliveryState.QUEUED, store.find(p.envelope.eventId)!!.state)
    }

    // ---------- scénáře ----------
    /** Jednoduchá síť: doručí zprávy v zadaném pořadí a případně je zduplikuje. */
    @Test fun `two days offline with shuffled and duplicated delivery loses nothing and multiplies nothing`() {
        val (st, mk) = receiverFor("bob", setOf("josef")); val r = mk(st)
        val msgs = (1L..30L).map { upsert(it, key = "d${it % 5}") } // 5 objektů, 6 verzí každý
        val rnd = Random(42)
        val delivery = (msgs + msgs.shuffled(rnd).take(15)).shuffled(rnd)
        delivery.forEach { r.receive("josef", Wire.encode(it), 100) }
        assertEquals(5, st.objects.size)
        for (k in 0 until 5) {
            val expectedMax = msgs.filter { it.envelope.objectId == "d$k" }.maxOf { it.envelope.objectVersion }
            assertEquals(expectedMax, st.objects.getValue("josef|daily|d$k").first)
        }
        assertEquals(30, st.receipts.size) // žádné násobení, každá událost zaznamenána jednou
    }

    /** Odesílatel a příjemce jsou online v různých oknech; bez schránky se nikdy nepotkají. */
    @Test fun `non overlapping windows deliver only through a propagation node`() {
        val senderStore = InMemoryOutboxStore(); val ob = OutboxManager(senderStore)
        val (st, mk) = receiverFor("bob", setOf("josef"), sink = AckSink { from, ack -> ob.onAck(from, ack) }); val r = mk(st)
        val m = upsert(1); ob.enqueue(item(m))

        // Okno A: odesílatel online, příjemce ne. Bez schránky transport selže → zůstává ve frontě.
        ob.due(0).forEach { ob.onTransportResult(it.eventId, TransportResult.Failed("příjemce nedostupný"), 0) }
        assertEquals(DeliveryState.QUEUED, senderStore.find(m.envelope.eventId)!!.state)
        assertTrue(st.objects.isEmpty())

        // Totéž se schránkou: uloženo, ale NE potvrzeno.
        val node = ArrayDeque<ByteArray>()
        val retry = ob.due(Long.MAX_VALUE / 2).single()
        node.add(retry.wire); ob.onTransportResult(retry.eventId, TransportResult.StoredInPropagation, 1)
        assertEquals(DeliveryState.STORED_IN_PROPAGATION, senderStore.find(m.envelope.eventId)!!.state)

        // Okno B: příjemce online, odesílatel ne. Příjemce vyzvedne, aplikuje a ACK uloží do schránky pro odesílatele.
        val acks = ArrayDeque<Pair<String, ByteArray>>()
        while (node.isNotEmpty()) {
            val o = r.receive("josef", node.removeFirst(), 2); acks.add("bob" to Wire.encode(assertNotNull(o.ack)))
        }
        assertEquals(1, st.objects.size)
        assertEquals(DeliveryState.STORED_IN_PROPAGATION, senderStore.find(m.envelope.eventId)!!.state) // odesílatel ještě neví

        // Okno C: odesílatel se vrátí a vyzvedne ACK → teprve teď „Potvrzeno příjemcem“.
        val senderRx = Receiver("josef", MemReceiverStore(setOf("bob")), BuiltInHandlers.all(), AckSink { f, a -> ob.onAck(f, a) })
        acks.forEach { (from, bytes) -> senderRx.receive(from, bytes, 3) }
        assertEquals(DeliveryState.ACKED, senderStore.find(m.envelope.eventId)!!.state)
    }

    @Test fun `revoked grant does not resurrect through an old queued message`() {
        val store = InMemoryOutboxStore(); val ob = OutboxManager(store)
        val m = upsert(1); ob.enqueue(item(m, grant = "g1"))
        ob.cancelForGrant("g1")
        assertTrue(ob.due(Long.MAX_VALUE / 2).isEmpty())
    }

    // ---------- přílohy ----------
    @Test fun `attachment access requires verified contact offer and size not just the hash`() {
        val idx = AttachmentIndex { rec, h -> rec == "bob" && h == "abc" }
        val known = { id: String -> id == "bob" || id == "eva" }
        assertEquals(AttachmentDecision.Allow, AttachmentGate.authorize("bob", "abc", 100, known, idx))
        assertIs<AttachmentDecision.Deny>(AttachmentGate.authorize("eva", "abc", 100, known, idx)) // zná hash, ale nebylo nabídnuto
        assertIs<AttachmentDecision.Deny>(AttachmentGate.authorize("mallory", "abc", 100, known, idx))
        assertIs<AttachmentDecision.Deny>(AttachmentGate.authorize("bob", "abc", 26L * 1024 * 1024, known, idx))
        val data = "hello".toByteArray()
        assertTrue(AttachmentGate.verifyReceived(data, Wire.sha256Hex(data), 5))
        assertFalse(AttachmentGate.verifyReceived(data, Wire.sha256Hex(data), 6))
        assertFalse(AttachmentGate.verifyReceived("hellp".toByteArray(), Wire.sha256Hex(data), 5))
    }
}
