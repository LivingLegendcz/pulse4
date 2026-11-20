package app.pulse4.android.di

import android.content.ContextWrapper
import androidx.room.Room
import app.pulse4.android.TestJdbcSqliteDriver
import app.pulse4.android.data.AgeProfileManager
import app.pulse4.android.data.AppDatabase
import app.pulse4.android.data.ContactRow
import app.pulse4.android.data.FamilyLinkRow
import app.pulse4.android.data.GrantRow
import app.pulse4.android.data.RoomOutboxStore
import app.pulse4.android.data.RoomReceiverStore
import app.pulse4.android.data.RoomSentLedger
import app.pulse4.android.data.SentRow
import app.pulse4.android.data.Settings
import app.pulse4.android.data.toRow
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Quality
import app.pulse4.sharing.AgeProfile
import app.pulse4.sharing.AgeSource
import app.pulse4.sharing.AgeState
import app.pulse4.sharing.ConsentCanonical
import app.pulse4.sharing.ConsentStatus
import app.pulse4.sharing.ConsentSubject
import app.pulse4.sharing.ConsentVerdict
import app.pulse4.sharing.FamilyLink
import app.pulse4.sharing.FamilyLinkStatus
import app.pulse4.sharing.Field
import app.pulse4.sharing.Group
import app.pulse4.sharing.GuardianConsentPolicy
import app.pulse4.sharing.GuardianConsentRequest
import app.pulse4.sharing.GuardianDecision
import app.pulse4.sharing.GuardianRevocation
import app.pulse4.sharing.MembershipAction
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.TimeScope
import app.pulse4.sync.Ack
import app.pulse4.sync.AckSink
import app.pulse4.sync.BuiltInHandlers
import app.pulse4.sync.DeliveryState
import app.pulse4.sync.E2eeCrypto
import app.pulse4.sync.Envelope
import app.pulse4.sync.HandlerResult
import app.pulse4.sync.Kinds
import app.pulse4.sync.MessageBuilder
import app.pulse4.sync.OutboxManager
import app.pulse4.sync.Outcome
import app.pulse4.sync.Receiver
import app.pulse4.sync.RejectReason
import app.pulse4.sync.Wire
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/**
 * Verified guardian consent end to end over real Room databases, real Ed25519 identities and the real Receiver:
 * every party is a full device (DB + ShareService + Receiver); messages move by handing outbox items to the other
 * party's Receiver.
 */
class GuardianConsentHandlersTest {

    private val openDbs = mutableListOf<Pair<AppDatabase, File>>()
    private val now = System.currentTimeMillis()
    private val day7 = 7L * 24 * 3600 * 1000

    @AfterTest
    fun tearDown() {
        openDbs.forEach { (db, f) ->
            runCatching { db.close() }
            f.delete()
            File("${f.absolutePath}-wal").delete()
            File("${f.absolutePath}-shm").delete()
        }
        openDbs.clear()
    }

    private fun newDb(): AppDatabase {
        val dbFile = File.createTempFile("pulse4-gc-", ".db").also { it.delete() }
        val ctx = object : ContextWrapper(null) {
            override fun getApplicationContext() = this
            override fun getDatabasePath(name: String): File = File(name)
        }
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbFile.absolutePath)
            .setDriver(TestJdbcSqliteDriver())
            .allowMainThreadQueries()
            .build()
        openDbs += db to dbFile
        return db
    }

    /** A full device. */
    private inner class Party(val name: String, seed: Int) {
        val priv = ByteArray(32) { (it + seed).toByte() }
        val pubB64: String
        val id: String
        init {
            val edPub = Ed25519PrivateKeyParameters(priv, 0).generatePublicKey()
            val xPub = X25519PrivateKeyParameters(MessageDigest.getInstance("SHA-256").digest(priv), 0).generatePublicKey()
            pubB64 = Base64.getEncoder().encodeToString(xPub.encoded + edPub.encoded)
            id = ServiceMigrations.deriveDeliveryHashes(pubB64).first
        }
        val db = newDb()
        val settings = Settings(db)
        val outbox = OutboxManager(RoomOutboxStore(db))
        val ledger = RoomSentLedger(db)
        val share = ShareService(
            data = RoomPulseDataAccess(db),
            settings = RealShareSettingsAccess(settings),
            outbox = outbox,
            sentLedger = ledger,
            recordSent = { r, k, v, h -> db.inbox().putSent(SentRow(r, k, v, h)) },
            selfIdProvider = { id },
            signDetached = { E2eeCrypto.signDetached(priv, it) },
        )
        val handlers = BuiltInHandlers.all() + buildGuardianConsentHandlers(share)
        val receiver = Receiver(
            selfId = id,
            store = RoomReceiverStore(db),
            handlers = handlers,
            ackSink = AckSink { from, ack -> outbox.onAck(from, ack) },
            builder = MessageBuilder(id),
        )

        fun contact() = ContactRow(id, name, pubB64, "fp-$name", 1L)
        fun knows(vararg others: Party) = others.forEach { db.contacts().upsert(it.contact()) }
        fun consentRows() = db.guardianConsents().allNow()
        fun sign(bytes: ByteArray) = bytesToHex(E2eeCrypto.signDetached(priv, bytes))
    }

    private fun deliver(from: Party, to: Party, at: Long): List<Outcome> =
        from.outbox.due(at).filter { it.recipient == to.id }.map { item ->
            val out = to.receiver.receive(from.id, item.wire, at)
            out.ack?.let { ack ->
                from.outbox.onAck(to.id, Wire.json.decodeFromString(Ack.serializer(), ack.payload))
            }
            out
        }

    private fun handle(p: Party, author: String, kind: String, payload: String, at: Long = now): HandlerResult {
        val msg = MessageBuilder(author).build(p.id, kind, "x", "x", 1, payload, at)
        return p.handlers.getValue(kind).handle(msg.envelope, msg.payload, RoomReceiverStore(p.db), at)
    }

    private fun sendRaw(from: Party, to: Party, kind: String, payload: String, objectType: String = "x", objectId: String = "x", at: Long = now): Outcome {
        val msg = MessageBuilder(from.id).build(to.id, kind, objectType, objectId, 1, payload, at)
        return to.receiver.receive(from.id, Wire.encode(msg), at)
    }

    private val today get() = LocalDate.now(ZoneId.systemDefault())

    private fun grant(owner: Party, id: String, recipient: String = "coach", fields: Set<Field> = setOf(Field.SLEEP_DURATION), groupId: String? = null) = ShareGrant(
        id = id, ownerId = owner.id, recipients = setOf(recipient), fields = fields,
        scope = TimeScope.continuousFrom(today.minusDays(5)), groupId = groupId, createdAtMs = now,
    )

    private fun pendingReq(r: Result<*>): GuardianConsentRequest = (r.exceptionOrNull() as ConsentPendingException).request

    /** child declares MINOR, pairs with [guardian] (child-initiated) and the guardian accepts. */
    private fun link(child: Party, guardian: Party, at: Long = now): FamilyLink {
        child.knows(guardian); guardian.knows(child)
        if (child.share.ageState() != AgeState.MINOR) assertTrue(child.share.declareAge(AgeState.MINOR, at))
        val l = child.share.sendChildFamilyLinkRequest(guardian.contact(), at).getOrThrow()
        deliver(child, guardian, at)
        assertNotNull(guardian.share.respondFamilyLink(l.id, true, at + 1))
        deliver(guardian, child, at + 1)
        return l
    }

    private fun approveAll(child: Party, guardian: Party, at: Long) {
        deliver(child, guardian, at)
        guardian.share.pendingGuardianInboxNow().forEach { assertTrue(guardian.share.decideConsent(it.requestId, true, at + 1).isSuccess) }
        deliver(guardian, child, at + 2)
    }

    private fun decision(signer: ByteArray, guardianId: String, req: GuardianConsentRequest, verdict: ConsentVerdict = ConsentVerdict.APPROVED, digest: String = ConsentCanonical.requestDigestHex(req)): GuardianDecision {
        val u = GuardianDecision(verdict = verdict, requestId = req.requestId, guardianId = guardianId, childId = req.childId, requestDigest = digest, decidedAtMs = now + 5, signatureHex = "")
        return u.copy(signatureHex = bytesToHex(E2eeCrypto.signDetached(signer, ConsentCanonical.decision(u))))
    }

    private fun enc(d: GuardianDecision) = Wire.json.encodeToString(GuardianDecision.serializer(), d)

    // ---------------------------------------------------------------- 1. minor without link

    @Test
    fun `1 minor without a verified guardian cannot activate any sharing`() {
        val child = Party("child", 1)
        assertTrue(child.share.declareAge(AgeState.MINOR, now))
        val r = child.share.saveGrantWithPolicy(grant(child, "g1"))
        assertTrue(r.exceptionOrNull() is NoGuardianException)
        assertTrue(child.share.grants().isEmpty())
        assertTrue(child.share.effectiveGrants(now).isEmpty())
        assertEquals(OnboardingState.MINOR_WAITING_FOR_GUARDIAN, child.share.onboardingStateNow())
        assertTrue(child.consentRows().isEmpty())
        // UNKNOWN age: blocked until confirmed
        val fresh = Party("fresh", 2)
        assertTrue(fresh.share.saveGrantWithPolicy(grant(fresh, "g1")).exceptionOrNull() is AgeNotConfirmedException)
        assertEquals(OnboardingState.UNKNOWN, fresh.share.onboardingStateNow())
        // ADULT: immediate
        assertTrue(fresh.share.declareAge(AgeState.ADULT, now))
        assertTrue(fresh.share.saveGrantWithPolicy(grant(fresh, "g1")).isSuccess)
        assertEquals(listOf("g1"), fresh.share.effectiveGrants(now).map { it.id })
    }

    @Test
    fun `onboarding gate follows age and the first verified guardian link`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2)
        assertEquals(OnboardingState.UNKNOWN, child.share.onboardingStateNow())
        child.knows(guardian); guardian.knows(child)
        // sending a child link request needs the MINOR state
        assertTrue(child.share.sendChildFamilyLinkRequest(guardian.contact(), now).isFailure)
        assertTrue(child.share.declareAge(AgeState.MINOR, now))
        assertEquals(OnboardingState.MINOR_WAITING_FOR_GUARDIAN, child.share.onboardingStateNow())
        val l = child.share.sendChildFamilyLinkRequest(guardian.contact(), now).getOrThrow()
        assertEquals(child.id, l.initiator)
        deliver(child, guardian, now)
        // still blocked until the parent accepted
        assertEquals(OnboardingState.MINOR_WAITING_FOR_GUARDIAN, child.share.onboardingStateNow())
        assertEquals(1, guardian.share.pendingRequests().size)
        guardian.share.respondFamilyLink(l.id, true, now + 1)
        deliver(guardian, child, now + 1)
        assertEquals(OnboardingState.MINOR_READY, child.share.onboardingStateNow())
        assertEquals(setOf(guardian.id), child.share.verifiedGuardianIds())
        // after the first verified guardian link the self-declared MINOR can no longer undo
        assertFalse(child.share.declareAge(AgeState.ADULT, now + 2))
        assertEquals(AgeState.MINOR, child.share.ageState())
    }

    @Test
    fun `self declared minor can undo only before any guardian link was ever verified`() {
        val a = Party("a", 1)
        assertTrue(a.share.declareAge(AgeState.MINOR, now))
        assertTrue(a.share.declareAge(AgeState.ADULT, now + 1))
        assertEquals(AgeState.ADULT, a.share.ageState())
        val b = Party("b", 2); val g = Party("g", 3)
        link(b, g)
        // removing the link later does not reopen the door: only a signed release does
        assertTrue(g.share.revokeFamilyLink(g.share.familyLinks().single().id, now + 5).isSuccess)
        deliver(g, b, now + 5)
        assertFalse(b.share.declareAge(AgeState.ADULT, now + 6))
        assertEquals(OnboardingState.MINOR_WAITING_FOR_GUARDIAN, b.share.onboardingStateNow())
    }

    // ---------------------------------------------------------------- 2. self approval / strangers

    @Test
    fun `2 child self approval and a stranger decision are rejected`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2); val mallory = Party("mallory", 3)
        link(child, guardian); child.knows(mallory); mallory.knows(child)
        val req = pendingReq(child.share.saveGrantWithPolicy(grant(child, "g1")))
        assertEquals(setOf(guardian.id), req.guardianIds)

        val forgedByChild = decision(child.priv, child.id, req)
        assertEquals(HandlerResult.Rejected(RejectReason.NOT_PERMITTED), handle(child, child.id, Kinds.GUARDIAN_CONSENT_APPROVED, enc(forgedByChild)))
        val byMallory = decision(mallory.priv, mallory.id, req)
        assertEquals(HandlerResult.Rejected(RejectReason.NOT_PERMITTED), handle(child, mallory.id, Kinds.GUARDIAN_CONSENT_APPROVED, enc(byMallory)))
        val out = sendRaw(mallory, child, Kinds.GUARDIAN_CONSENT_APPROVED, enc(byMallory))
        assertTrue(out is Outcome.Rejected && out.reason == RejectReason.NOT_PERMITTED, "$out")
        assertEquals(listOf(ConsentStatus.PENDING.name), child.consentRows().map { it.status })
        assertTrue(child.share.grants().isEmpty())
        assertTrue(child.share.effectiveGrants(now).isEmpty())
    }

    @Test
    fun `a request from someone who is not a linked child is rejected by the guardian`() {
        val guardian = Party("guardian", 2); val mallory = Party("mallory", 3)
        guardian.knows(mallory); mallory.knows(guardian)
        val g = grant(mallory, "g1")
        val req = GuardianConsentPolicy.newRequest("r-x", "00112233445566778899aabbccddeeff", setOf(guardian.id), g, 1, null, now)
        val out = sendRaw(mallory, guardian, Kinds.GUARDIAN_CONSENT_REQUEST, Wire.json.encodeToString(GuardianConsentRequest.serializer(), req))
        assertTrue(out is Outcome.Rejected && out.reason == RejectReason.NOT_PERMITTED, "$out")
        assertTrue(guardian.share.pendingGuardianInboxNow().isEmpty())
    }

    // ---------------------------------------------------------------- 3. valid approval

    @Test
    fun `3 a valid approval activates exactly the approved grant`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2)
        link(child, guardian)
        val r1 = pendingReq(child.share.saveGrantWithPolicy(grant(child, "g1")))
        val r2 = pendingReq(child.share.saveGrantWithPolicy(grant(child, "g2", fields = setOf(Field.WEIGHT))))
        // nothing is stored or effective while pending
        assertTrue(child.share.grants().isEmpty())
        deliver(child, guardian, now)
        assertEquals(setOf(r1.requestId, r2.requestId), guardian.share.pendingGuardianInboxNow().map { it.requestId }.toSet())
        assertTrue(guardian.share.decideConsent(r1.requestId, true, now + 10).isSuccess)
        val outs = deliver(guardian, child, now + 11)
        assertTrue(outs.all { it is Outcome.Applied }, "$outs")
        assertEquals(listOf("g1"), child.share.grants().map { it.id })
        assertEquals(listOf("g1"), child.share.effectiveGrants(now + 12).map { it.id })
        assertEquals(ConsentUiState.APPROVED, child.share.consent.grantStates(child.share.grants(), child.id, now + 12)["g1"])
        // the second request is still waiting; a guardian reject changes nothing
        assertTrue(guardian.share.decideConsent(r2.requestId, false, now + 13).isSuccess)
        deliver(guardian, child, now + 14)
        assertEquals(listOf("g1"), child.share.grants().map { it.id })
        assertEquals(ConsentStatus.REJECTED.name, child.consentRows().single { it.requestId == r2.requestId }.status)
        // the guardian cannot decide the same request twice
        assertTrue(guardian.share.decideConsent(r1.requestId, true, now + 15).isFailure)
    }

    // ---------------------------------------------------------------- 4/5. forged / tampered

    @Test
    fun `4 a decision signed by another identity or over another digest is rejected`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2); val mallory = Party("mallory", 3)
        link(child, guardian); child.knows(mallory)
        val req = pendingReq(child.share.saveGrantWithPolicy(grant(child, "g1")))
        // claims to be the guardian but is signed with mallory's key
        val forged = decision(mallory.priv, guardian.id, req)
        assertEquals(HandlerResult.Rejected(RejectReason.NOT_PERMITTED), handle(child, guardian.id, Kinds.GUARDIAN_CONSENT_APPROVED, enc(forged)))
        // right signer, but over a different request digest
        val wrongDigest = decision(guardian.priv, guardian.id, req, digest = "00".repeat(32))
        assertEquals(HandlerResult.Rejected(RejectReason.NOT_PERMITTED), handle(child, guardian.id, Kinds.GUARDIAN_CONSENT_APPROVED, enc(wrongDigest)))
        // a verdict that does not match the message kind
        assertEquals(HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD), handle(child, guardian.id, Kinds.GUARDIAN_CONSENT_REJECTED, enc(decision(guardian.priv, guardian.id, req))))
        assertTrue(child.share.effectiveGrants(now).isEmpty())
        assertEquals(ConsentStatus.PENDING.name, child.consentRows().single().status)
        // the genuine decision still works afterwards
        assertEquals(HandlerResult.Applied, handle(child, guardian.id, Kinds.GUARDIAN_CONSENT_APPROVED, enc(decision(guardian.priv, guardian.id, req))))
        assertEquals(listOf("g1"), child.share.effectiveGrants(now + 1).map { it.id })
    }

    @Test
    fun `5 tampered request, wrong recipient and wider scope never activate anything`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2); val other = Party("other", 3)
        link(child, guardian)

        // d) the locally stored proposal was widened before the decision arrived
        val req = pendingReq(child.share.saveGrantWithPolicy(grant(child, "g1")))
        deliver(child, guardian, now)
        guardian.share.decideConsent(req.requestId, true, now + 1)
        val row = child.db.guardianConsents().get(req.requestId)!!
        val wider = Wire.json.decodeFromString(ShareGrant.serializer(), row.proposedGrantJson!!).copy(fields = setOf(Field.SLEEP_DURATION, Field.HRV_DAILY))
        child.db.guardianConsents().upsert(row.copy(proposedGrantJson = Wire.json.encodeToString(ShareGrant.serializer(), wider)))
        val wire = guardian.outbox.due(now + 2).single { it.recipient == child.id }.wire
        val out = child.receiver.receive(guardian.id, wire, now + 2)
        assertTrue(out is Outcome.Rejected && out.reason == RejectReason.NOT_PERMITTED, "$out")
        assertTrue(child.share.grants().isEmpty())
        assertEquals(ConsentStatus.PENDING.name, child.db.guardianConsents().get(req.requestId)!!.status)
        child.db.guardianConsents().upsert(row) // restore the proposal

        // c) wrong recipient on the envelope
        val decoded = Wire.decode(wire)!!
        val misaddressed = Wire.encode(decoded.copy(envelope = decoded.envelope.copy(recipient = other.id, eventId = "ev-wrong")))
        val out2 = child.receiver.receive(guardian.id, misaddressed, now + 2)
        assertTrue(out2 is Outcome.Rejected && out2.reason == RejectReason.WRONG_RECIPIENT, "$out2")

        // genuine delivery works
        assertTrue(child.receiver.receive(guardian.id, wire, now + 3) is Outcome.Applied)
        assertEquals(listOf("g1"), child.share.effectiveGrants(now + 4).map { it.id })

        // a) request JSON tampered afterwards (terms widened): the digest no longer matches -> not effective
        val approved = child.db.guardianConsents().get(req.requestId)!!
        val parsed = Wire.json.decodeFromString(GuardianConsentRequest.serializer(), approved.requestJson)
        val tampered = parsed.copy(terms = parsed.terms!!.copy(fields = parsed.terms!!.fields + Field.HRV_DAILY))
        child.db.guardianConsents().upsert(approved.copy(requestJson = Wire.json.encodeToString(GuardianConsentRequest.serializer(), tampered)))
        assertTrue(child.share.effectiveGrants(now + 5).isEmpty())
        child.db.guardianConsents().upsert(approved)
        assertEquals(1, child.share.effectiveGrants(now + 6).size)

        // b) signature of the stored decision tampered
        val d = Wire.json.decodeFromString(GuardianDecision.serializer(), approved.decisionJson!!)
        val flipped = d.copy(signatureHex = (if (d.signatureHex.first() == 'a') "b" else "a") + d.signatureHex.drop(1))
        child.db.guardianConsents().upsert(approved.copy(decisionJson = Wire.json.encodeToString(GuardianDecision.serializer(), flipped)))
        assertTrue(child.share.effectiveGrants(now + 7).isEmpty())
        child.db.guardianConsents().upsert(approved)
        assertEquals(1, child.share.effectiveGrants(now + 8).size)
    }

    // ---------------------------------------------------------------- 6. replay / expired / revoked

    @Test
    fun `6 replay and revoked approvals cannot be revived`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2)
        link(child, guardian)
        val req = pendingReq(child.share.saveGrantWithPolicy(grant(child, "g1")))
        deliver(child, guardian, now)
        guardian.share.decideConsent(req.requestId, true, now + 1)
        val item = guardian.outbox.due(now + 2).single { it.recipient == child.id }
        assertTrue(child.receiver.receive(guardian.id, item.wire, now + 2) is Outcome.Applied)
        // the same bytes again: idempotent receipt
        assertTrue(child.receiver.receive(guardian.id, item.wire, now + 3) is Outcome.Duplicate)
        // the same decision under a new event id: single use, ignored
        val wire = Wire.decode(item.wire)!!
        val replay = Wire.encode(wire.copy(envelope = wire.envelope.copy(eventId = "ev-replay")))
        assertTrue(child.receiver.receive(guardian.id, replay, now + 4) is Outcome.Stale)

        // guardian revokes: grant is revoked, nothing effective, replaying the old approval does not revive it
        assertTrue(guardian.share.revokeConsent(req.permissionId, now + 10).isSuccess)
        val revOuts = deliver(guardian, child, now + 11)
        assertTrue(revOuts.any { it is Outcome.Applied } && revOuts.none { it is Outcome.Rejected }, "$revOuts") // the first one is the (manually delivered) approval again
        child.share.drainPendingRevokes(now + 12)
        assertFalse(child.share.grants().single().active)
        assertTrue(child.share.effectiveGrants(now + 13).isEmpty())
        assertEquals(ConsentStatus.REVOKED.name, child.db.guardianConsents().get(req.requestId)!!.status)
        val replay2 = Wire.encode(wire.copy(envelope = wire.envelope.copy(eventId = "ev-replay-2")))
        assertTrue(child.receiver.receive(guardian.id, replay2, now + 14) is Outcome.Stale)
        assertFalse(child.share.grants().single().active)
    }

    @Test
    fun `6 an expired request cannot be approved late`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2)
        link(child, guardian)
        val req = pendingReq(child.share.saveGrantWithPolicy(grant(child, "g1")))
        deliver(child, guardian, now)
        // the guardian approved in time but the answer only travels after the expiry
        assertTrue(guardian.share.decideConsent(req.requestId, true, now + 10).isSuccess)
        val late = now + day7 + 60_000
        assertEquals(1, child.share.expireStaleConsents(late))
        assertEquals(ConsentStatus.EXPIRED.name, child.db.guardianConsents().get(req.requestId)!!.status)
        val outs = deliver(guardian, child, late)
        assertTrue(outs.single() is Outcome.Rejected, "$outs")
        assertTrue(child.share.grants().isEmpty())
        assertTrue(child.share.effectiveGrants(late).isEmpty())
        // and the guardian can no longer decide an expired request locally
        val child2 = Party("child2", 7)
        link(child2, guardian)
        val r2 = pendingReq(child2.share.saveGrantWithPolicy(grant(child2, "g9")))
        deliver(child2, guardian, now)
        assertTrue(guardian.share.decideConsent(r2.requestId, true, now + day7 + 60_000).isFailure)
    }

    // ---------------------------------------------------------------- 7. offline guardian

    @Test
    fun `7 offline guardian leaves the request queued and nothing effective until it expires, narrowing still works`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2)
        link(child, guardian)
        val req = pendingReq(child.share.saveGrantWithPolicy(grant(child, "g1", fields = setOf(Field.SLEEP_DURATION, Field.WEIGHT))))
        val queued = child.outbox.due(now + 5).filter { it.kind == Kinds.GUARDIAN_CONSENT_REQUEST }
        assertEquals(listOf(guardian.id), queued.map { it.recipient })
        assertEquals(DeliveryState.QUEUED, queued.single().state)
        assertTrue(child.share.effectiveGrants(now + 5).isEmpty())
        assertEquals(1, child.share.expireStaleConsents(now + day7 + 60_000))
        assertEquals(ConsentStatus.EXPIRED.name, child.db.guardianConsents().get(req.requestId)!!.status)
        // the queued request message is cancelled, not sent forever
        assertTrue(child.outbox.due(now + day7 + 2).none { it.kind == Kinds.GUARDIAN_CONSENT_REQUEST })

        // a new request is approved; afterwards the guardian is offline: narrowing and disabling work immediately
        val r2 = pendingReq(child.share.saveGrantWithPolicy(grant(child, "g1", fields = setOf(Field.SLEEP_DURATION, Field.WEIGHT))))
        assertTrue(r2.permissionVersion > req.permissionVersion)
        approveAll(child, guardian, now + day7 + 10)
        assertEquals(listOf("g1"), child.share.effectiveGrants(now + day7 + 20).map { it.id })
        val narrowed = child.share.grants().single().copy(fields = setOf(Field.SLEEP_DURATION))
        assertTrue(child.share.saveGrantWithPolicy(narrowed).isSuccess)
        assertEquals(setOf(Field.SLEEP_DURATION), child.share.effectiveGrants(now + day7 + 21).single().fields)
        // widening again is NOT immediate (and creates a new pending request)
        val widened = narrowed.copy(fields = setOf(Field.SLEEP_DURATION, Field.WEIGHT, Field.HRV_DAILY))
        assertTrue(child.share.saveGrantWithPolicy(widened).exceptionOrNull() is ConsentPendingException)
        assertEquals(setOf(Field.SLEEP_DURATION), child.share.grants().single().fields)
        // disable
        assertTrue(child.share.saveGrantWithPolicy(narrowed.copy(revokedAtMs = now + day7 + 30)).isSuccess)
        assertTrue(child.share.effectiveGrants(now + day7 + 31).isEmpty())
    }

    // ---------------------------------------------------------------- 8. migration / backup

    private fun legacyLink(child: String, parent: String, consentTs: Long? = 5_000L) = FamilyLink(
        id = "legacy-fl", parentId = parent, childId = child, status = FamilyLinkStatus.VERIFIED,
        childAgeProfile = AgeProfile.MINOR, guardianConsentAtMs = consentTs, createdAtMs = 1_000L, verifiedAtMs = 2_000L, version = 2L,
    )

    private fun putLegacyGrant(p: Party, id: String) =
        p.db.grants().upsert(GrantRow(id, false, Wire.json.encodeToString(ShareGrant.serializer(), grant(p, id))))

    @Test
    fun `8 migration locks a linked minor and legacy checkbox grants are not effective`() {
        val child = Party("child", 1); val parent = Party("parent", 2)
        putLegacyGrant(child, "legacy-g")
        child.db.familyLinks().upsert(legacyLink(child.id, parent.id).let { FamilyLinkRow(it.id, it.parentId, it.childId, it.status.name, it.version, Wire.json.encodeToString(FamilyLink.serializer(), it)) })
        child.settings.put("ownAgeProfile", "ADULT") // the legacy default was never evidence of anything
        ServiceMigrations.migrateGuardianConsent(child.db, child.settings, child.id, now)
        val rec = child.settings.ageProfileManager.current()
        assertEquals(AgeState.MINOR, rec.state)
        assertEquals(AgeSource.MIGRATION, rec.source)
        assertEquals(parent.id, rec.lockedByGuardianId)
        assertTrue(child.settings.ageProfileManager.guardianLinkEverVerified())
        assertEquals(1, child.share.grants().size)
        assertTrue(child.share.effectiveGrants(now).isEmpty())
        assertEquals(OnboardingState.MINOR_READY, child.share.onboardingStateNow())
        assertEquals(ConsentUiState.NEEDS_APPROVAL, child.share.consent.grantStates(child.share.grants(), child.id, now)["legacy-g"])
        // and a MINOR cannot undo
        assertFalse(child.share.declareAge(AgeState.ADULT, now))
    }

    @Test
    fun `8 upgraded install without a child link becomes ADULT with no first-run gate and everything effective`() {
        val u = Party("user", 1) // no fresh-install marker: the database was upgraded, not created
        putLegacyGrant(u, "g-old")
        val circle = Group("c1", "Fam", u.id, u.id, setOf(u.id, "friend"), 1L)
        u.share.saveGroup(circle)
        u.settings.put("ownAgeProfile", "ADULT") // legacy default
        ServiceMigrations.migrateGuardianConsent(u.db, u.settings, u.id, now)
        val rec = u.settings.ageProfileManager.current()
        assertEquals(AgeState.ADULT, rec.state)
        assertEquals(AgeSource.MIGRATION, rec.source)
        assertEquals(now, rec.atMs)
        assertEquals(OnboardingState.ADULT, u.share.onboardingStateNow())
        // nothing is grandfathered, everything is simply effective
        assertTrue(u.settings.ageProfileManager.grandfatheredGrantIds().isEmpty())
        assertTrue(u.settings.ageProfileManager.grandfatheredCircleIds().isEmpty())
        assertEquals(listOf("g-old"), u.share.effectiveGrants(now).map { it.id })
        assertEquals(ConsentUiState.NOT_REQUIRED, u.share.consent.grantStates(u.share.grants(), u.id, now)["g-old"])
        assertTrue(u.share.consent.isMembershipAllowed(u.id, "c1", now))
        // new and widened sharing needs no approval
        assertTrue(u.share.saveGrantWithPolicy(grant(u, "g-new")).isSuccess)
        val old = u.share.grants().first { it.id == "g-old" }
        assertTrue(u.share.saveGrantWithPolicy(old.copy(fields = setOf(Field.SLEEP_DURATION, Field.WEIGHT))).isSuccess)
        assertEquals(setOf("g-old", "g-new"), u.share.effectiveGrants(now + 1).map { it.id }.toSet())
        // idempotent
        ServiceMigrations.migrateGuardianConsent(u.db, u.settings, u.id, now + 1)
        assertEquals(now, u.settings.ageProfileManager.current().atMs)
        assertEquals(AgeState.ADULT, u.share.ageState())
    }

    @Test
    fun `8 fresh install stays UNKNOWN and shows the first-run question`() {
        val u = Party("fresh", 1)
        u.settings.put(AppDatabase.KEY_FRESH_INSTALL, "1") // what AppDatabase.FRESH_INSTALL_CALLBACK writes on creation
        ServiceMigrations.migrateGuardianConsent(u.db, u.settings, u.id, now)
        assertEquals(AgeState.UNKNOWN, u.share.ageState())
        assertEquals(OnboardingState.UNKNOWN, u.share.onboardingStateNow())
        assertTrue(u.settings.ageProfileManager.grandfatheredGrantIds().isEmpty())
        // new sharing is blocked until the age is confirmed
        assertTrue(u.share.saveGrantWithPolicy(grant(u, "g-new")).exceptionOrNull() is AgeNotConfirmedException)
        // idempotent, then the first-run choice works both ways
        ServiceMigrations.migrateGuardianConsent(u.db, u.settings, u.id, now + 1)
        assertEquals(AgeState.UNKNOWN, u.share.ageState())
        assertTrue(u.share.declareAge(AgeState.ADULT, now + 2))
        assertTrue(u.share.saveGrantWithPolicy(grant(u, "g-new")).isSuccess)

        val minor = Party("fresh-minor", 3)
        minor.settings.put(AppDatabase.KEY_FRESH_INSTALL, "1")
        ServiceMigrations.migrateGuardianConsent(minor.db, minor.settings, minor.id, now)
        assertTrue(minor.share.declareAge(AgeState.MINOR, now))
        assertEquals(AgeState.MINOR, minor.share.ageState())
    }

    @Test
    fun `8 fresh answer given before the deferred migration is kept`() {
        val u = Party("early", 1)
        u.settings.put(AppDatabase.KEY_FRESH_INSTALL, "1")
        assertTrue(u.share.declareAge(AgeState.MINOR, now)) // user answered while the migration was still deferred
        ServiceMigrations.migrateGuardianConsent(u.db, u.settings, u.id, now + 1)
        assertEquals(AgeState.MINOR, u.share.ageState())
    }

    @Test
    fun `8 upgraded v2 record is never overwritten by the ADULT migration`() {
        val u = Party("upgraded-minor-declared", 1)
        assertTrue(u.share.declareAge(AgeState.MINOR, now))
        ServiceMigrations.migrateGuardianConsent(u.db, u.settings, u.id, now + 1)
        assertEquals(AgeState.MINOR, u.share.ageState())
    }

    @Test
    fun `8 stored minor stays minor and an existing v2 record is not overwritten by the migration`() {
        val m = Party("m", 1)
        m.settings.put("ownAgeProfile", "MINOR")
        ServiceMigrations.migrateGuardianConsent(m.db, m.settings, m.id, now)
        assertEquals(AgeState.MINOR, m.share.ageState())
        assertEquals(AgeSource.MIGRATION, m.settings.ageProfileManager.current().source)
        assertEquals(OnboardingState.MINOR_WAITING_FOR_GUARDIAN, m.share.onboardingStateNow())

        val a = Party("a", 2)
        assertTrue(a.share.declareAge(AgeState.ADULT, now))
        a.settings.put("ownAgeProfile", "MINOR")
        ServiceMigrations.migrateGuardianConsent(a.db, a.settings, a.id, now + 1)
        assertEquals(AgeState.ADULT, a.share.ageState())
    }

    @Test
    fun `8 identity remap does not rewrite guardian_consent rows and backup carries neither age nor consent`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2)
        link(child, guardian)
        pendingReq(child.share.saveGrantWithPolicy(grant(child, "g1")))
        val before = child.db.guardianConsents().allNow()
        // a legacy-addressed contact makes the remap actually run
        val peerPub = Party("legacy", 9)
        val legacyId = ServiceMigrations.deriveDeliveryHashes(peerPub.pubB64).second
        child.db.contacts().upsert(ContactRow(legacyId, "Legacy", peerPub.pubB64, "fp", 1L))
        assertTrue(ServiceMigrations.migrateLegacyPulseIdentities(child.db, child.settings, child.pubB64) > 0)
        assertEquals(before, child.db.guardianConsents().allNow())

        val svc = BackupService(child.db)
        val entries = svc.open(svc.export("password-123".toCharArray()), "password-123".toCharArray())
        assertTrue(entries.keys.none { it.contains("age", true) || it.contains("consent", true) || it.contains("setting", true) || it.contains("family", true) }, "${entries.keys}")
        assertTrue(entries.values.none { String(it).contains("ageProfile") || String(it).contains("guardian") })
        // restoring never touches age or consent
        val ageBefore = child.settings.ageProfileManager.current()
        svc.restore(entries)
        assertEquals(ageBefore, child.settings.ageProfileManager.current())
        assertEquals(before, child.db.guardianConsents().allNow())
    }

    // ---------------------------------------------------------------- 9. multi guardian

    private fun addSecond(child: Party, g1: Party, g2: Party, at: Long): FamilyLink {
        child.knows(g2); g2.knows(child)
        val l = child.share.sendChildFamilyLinkRequest(g2.contact(), at).getOrThrow()
        deliver(child, g2, at); deliver(child, g1, at)
        assertNotNull(g2.share.respondFamilyLink(l.id, true, at + 1))
        deliver(g2, child, at + 1)
        return l
    }

    @Test
    fun `9 a second guardian needs a guardian add approval and the fake one stays a non guardian`() {
        val child = Party("child", 1); val g1 = Party("g1", 2); val g2 = Party("g2", 3)
        link(child, g1)
        val l2 = addSecond(child, g1, g2, now + 100)
        // accepted by the parent, but not approved by an existing guardian: still not a guardian
        assertEquals(setOf(g1.id), child.share.verifiedGuardianIds())
        assertEquals(FamilyLinkStatus.PENDING, child.share.familyLinks().single { it.id == l2.id }.status)
        val req = pendingReq(child.share.saveGrantWithPolicy(grant(child, "g1")))
        assertEquals(setOf(g1.id), req.guardianIds)
        // g2 tries to decide and to revoke: rejected
        assertEquals(HandlerResult.Rejected(RejectReason.NOT_PERMITTED), handle(child, g2.id, Kinds.GUARDIAN_CONSENT_APPROVED, enc(decision(g2.priv, g2.id, req))))
        assertTrue(child.share.effectiveGrants(now + 200).isEmpty())
        // g1 approves the GUARDIAN_ADD -> the link becomes VERIFIED
        val add = g1.share.pendingGuardianInboxNow().single { it.subject == ConsentSubject.GUARDIAN_ADD }
        assertEquals(g2.id, add.subjectId)
        assertTrue(g1.share.decideConsent(add.requestId, true, now + 300).isSuccess)
        deliver(g1, child, now + 301)
        assertEquals(setOf(g1.id, g2.id), child.share.verifiedGuardianIds())
        assertEquals(FamilyLinkStatus.VERIFIED, child.share.familyLinks().single { it.id == l2.id }.status)
    }

    private fun twoGuardians(): Triple<Party, Party, Party> {
        val child = Party("child", 1); val g1 = Party("g1", 2); val g2 = Party("g2", 3)
        link(child, g1)
        addSecond(child, g1, g2, now + 100)
        deliver(child, g1, now + 150)
        val add = g1.share.pendingGuardianInboxNow().single { it.subject == ConsentSubject.GUARDIAN_ADD }
        g1.share.decideConsent(add.requestId, true, now + 300)
        deliver(g1, child, now + 301)
        assertEquals(2, child.share.verifiedGuardianIds().size)
        return Triple(child, g1, g2)
    }

    @Test
    fun `9 either guardian can approve, the first valid decision wins and the other can revoke it`() {
        val (child, g1, g2) = twoGuardians()
        val req = pendingReq(child.share.saveGrantWithPolicy(grant(child, "g1")))
        assertEquals(setOf(g1.id, g2.id), req.guardianIds)
        deliver(child, g1, now + 400); deliver(child, g2, now + 400)
        assertTrue(g2.share.decideConsent(req.requestId, true, now + 401).isSuccess)
        deliver(g2, child, now + 402)
        assertEquals(listOf("g1"), child.share.effectiveGrants(now + 403).map { it.id })
        // g1 decides later (rejects): ignored, the first valid decision won
        assertTrue(g1.share.decideConsent(req.requestId, false, now + 410).isSuccess)
        val late = deliver(g1, child, now + 411)
        assertTrue(late.single() is Outcome.Stale, "$late")
        assertEquals(ConsentStatus.APPROVED.name, child.db.guardianConsents().get(req.requestId)!!.status)
        assertEquals(1, child.share.effectiveGrants(now + 412).size)
        // g1 revokes the approval g2 gave
        assertTrue(g1.share.revokeConsent(req.permissionId, now + 420).isSuccess)
        deliver(g1, child, now + 421)
        child.share.drainPendingRevokes(now + 422)
        assertTrue(child.share.effectiveGrants(now + 423).isEmpty())
        assertFalse(child.share.grants().single().active)
    }

    @Test
    fun `9 a guardian cannot remove another guardians link, but may remove its own without voiding approvals`() {
        val (child, g1, g2) = twoGuardians()
        val req = pendingReq(child.share.saveGrantWithPolicy(grant(child, "g1")))
        deliver(child, g1, now + 400); deliver(child, g2, now + 400)
        g1.share.decideConsent(req.requestId, true, now + 401)
        deliver(g1, child, now + 402)
        assertEquals(1, child.share.effectiveGrants(now + 403).size)

        // g2 forges a removal of g1's link
        val l1 = child.share.familyLinks().single { it.parentId == g1.id }
        val forged = Wire.json.encodeToString(FamilyLink.serializer(), l1.copy(status = FamilyLinkStatus.REVOKED, version = l1.version + 1))
        val out = sendRaw(g2, child, Kinds.FAMILY_LINK_REVOKE, forged, "family_link", l1.id)
        assertTrue(out is Outcome.Rejected && out.reason == RejectReason.NOT_PERMITTED, "$out")
        assertEquals(FamilyLinkStatus.VERIFIED, child.share.familyLinks().single { it.id == l1.id }.status)
        // locally g2 does not even know that link
        assertTrue(g2.share.revokeFamilyLink(l1.id, now + 410).isFailure)

        // g1 removes its own link: approval g1 gave stays valid because g2 remains
        assertTrue(g1.share.revokeFamilyLink(g1.share.familyLinks().single().id, now + 420).isSuccess)
        assertTrue(deliver(g1, child, now + 421).all { it is Outcome.Applied })
        assertEquals(setOf(g2.id), child.share.verifiedGuardianIds())
        assertEquals(1, child.share.effectiveGrants(now + 422).size)
        assertEquals(OnboardingState.MINOR_READY, child.share.onboardingStateNow())
    }

    @Test
    fun `9 a child cannot remove a verified guardian link, locally or by a message, but may cancel a pending request`() {
        val (child, g1, g2) = twoGuardians()
        val l1 = child.share.familyLinks().single { it.parentId == g1.id }
        // local refusal on the child device: failure, nothing changed, nothing queued
        val res = child.share.revokeFamilyLink(l1.id, now + 601)
        assertTrue(res.isFailure)
        assertEquals(FamilyLinkStatus.VERIFIED, child.share.familyLinks().single { it.id == l1.id }.status)
        assertTrue(child.outbox.due(now + 602).none { it.kind == Kinds.FAMILY_LINK_REVOKE })
        assertEquals(setOf(g1.id, g2.id), child.share.verifiedGuardianIds())
        // forged inbound family.link.revoke authored by the child is rejected on the parent device
        val forged = Wire.json.encodeToString(FamilyLink.serializer(), l1.copy(status = FamilyLinkStatus.REVOKED, version = l1.version + 1))
        val out = sendRaw(child, g1, Kinds.FAMILY_LINK_REVOKE, forged, "family_link", l1.id)
        assertTrue(out is Outcome.Rejected && out.reason == RejectReason.NOT_PERMITTED, "$out")
        assertEquals(FamilyLinkStatus.VERIFIED, g1.share.familyLinks().single { it.id == l1.id }.status)
        // the parent may still remove its own link
        assertTrue(g1.share.revokeFamilyLink(l1.id, now + 610).isSuccess)
        assertTrue(deliver(g1, child, now + 611).all { it is Outcome.Applied })
        assertEquals(setOf(g2.id), child.share.verifiedGuardianIds())
    }

    @Test
    fun `9 a child may cancel its own pending request and the parent sees it withdrawn`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2)
        child.knows(guardian); guardian.knows(child)
        assertTrue(child.share.declareAge(AgeState.MINOR, now))
        val l = child.share.sendChildFamilyLinkRequest(guardian.contact(), now).getOrThrow()
        deliver(child, guardian, now)
        assertTrue(child.share.revokeFamilyLink(l.id, now + 1).isSuccess)
        assertTrue(deliver(child, guardian, now + 2).all { it is Outcome.Applied })
        assertEquals(FamilyLinkStatus.REVOKED, guardian.share.familyLinks().single().status)
    }

    @Test
    fun `9 a guardian cannot revoke a guardian add approval, neither through the service nor by a forged message`() {
        val (child, g1, g2) = twoGuardians()
        val add = g1.share.consent.items(ConsentRole.GUARDIAN_IN).single { it.subject == ConsentSubject.GUARDIAN_ADD }
        // service side: refused for both guardians, nothing is queued
        assertTrue(g1.share.revokeConsent(add.permissionId, now + 500).isFailure)
        assertTrue(g2.share.revokeConsent(add.permissionId, now + 501).isFailure)
        assertEquals(ConsentStatus.APPROVED.name, g1.db.guardianConsents().get(add.requestId)!!.status)
        assertTrue(g1.outbox.due(now + 502).none { it.kind == Kinds.GUARDIAN_CONSENT_REVOKED })
        // child side: even a correctly signed revocation by the founder is rejected and g2 stays a guardian
        val u = GuardianRevocation(
            requestId = add.requestId, guardianId = g1.id, childId = child.id, permissionId = add.permissionId,
            revokedAtMs = now + 510, signatureHex = "",
        )
        val rev = u.copy(signatureHex = bytesToHex(E2eeCrypto.signDetached(g1.priv, ConsentCanonical.revocation(u))))
        val out = handle(child, g1.id, Kinds.GUARDIAN_CONSENT_REVOKED, Wire.json.encodeToString(GuardianRevocation.serializer(), rev), now + 511)
        assertEquals(HandlerResult.Rejected(RejectReason.NOT_PERMITTED), out)
        assertEquals(setOf(g1.id, g2.id), child.share.verifiedGuardianIds())
    }

    @Test
    fun `9 removing the last guardian revokes everything and sends the child back to waiting`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2)
        link(child, guardian)
        pendingReq(child.share.saveGrantWithPolicy(grant(child, "g1")))
        approveAll(child, guardian, now + 10)
        assertEquals(1, child.share.effectiveGrants(now + 20).size)
        pendingReq(child.share.saveGrantWithPolicy(grant(child, "g2", fields = setOf(Field.WEIGHT)))) // a second one stays pending

        assertTrue(guardian.share.revokeFamilyLink(guardian.share.familyLinks().single().id, now + 30).isSuccess)
        assertTrue(deliver(guardian, child, now + 31).all { it is Outcome.Applied })
        child.share.drainPendingRevokes(now + 32)
        assertTrue(child.share.effectiveGrants(now + 33).isEmpty())
        assertFalse(child.share.grants().single().active)
        assertEquals(OnboardingState.MINOR_WAITING_FOR_GUARDIAN, child.share.onboardingStateNow())
        assertTrue(child.consentRows().all { it.status == ConsentStatus.REVOKED.name })
        // the pending request message is not sent any more
        assertTrue(child.outbox.due(now + 40).none { it.kind == Kinds.GUARDIAN_CONSENT_REQUEST })
        // a decision for the (now void) pending request is ignored
        assertTrue(child.share.verifiedGuardianIds().isEmpty())
    }

    @Test
    fun `a child waiting for its first guardian is gated until the guardian accepts and can resend its request`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2)
        child.knows(guardian); guardian.knows(child)
        assertTrue(child.share.declareAge(AgeState.MINOR, now))
        assertTrue(child.share.isAppGatedForGuardian())
        val l = child.share.sendChildFamilyLinkRequest(guardian.contact(), now).getOrThrow()
        assertTrue(child.share.isAppGatedForGuardian())
        assertTrue(child.share.resendFamilyLinkRequest(l.id, now + 5))
        assertTrue(child.outbox.due(now + 6).any { it.kind == Kinds.FAMILY_LINK_REQUEST })
        // only the initiator can resend
        assertFalse(guardian.share.resendFamilyLinkRequest(l.id, now + 6))
        deliver(child, guardian, now + 7)
        assertNotNull(guardian.share.respondFamilyLink(l.id, true, now + 8))
        deliver(guardian, child, now + 9)
        assertFalse(child.share.isAppGatedForGuardian())
        assertEquals(OnboardingState.MINOR_READY, child.share.onboardingStateNow())
        // once a link was verified the self-declared age can no longer be undone by the child
        assertFalse(child.share.declareAge(AgeState.ADULT, now + 10))
    }

    @Test
    fun `undoing the child choice cancels the pending parent request so it cannot re-lock the adult later`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2)
        child.knows(guardian); guardian.knows(child)
        assertTrue(child.share.declareAge(AgeState.MINOR, now))
        val l = child.share.sendChildFamilyLinkRequest(guardian.contact(), now).getOrThrow()
        assertTrue(child.share.declareAge(AgeState.ADULT, now + 1))
        assertEquals(FamilyLinkStatus.REVOKED, child.share.familyLinks().single { it.id == l.id }.status)
        assertEquals(AgeState.ADULT, child.share.ageState())
        // a late acceptance by the parent no longer verifies anything on the child
        deliver(child, guardian, now + 2)
        guardian.share.respondFamilyLink(l.id, true, now + 3)
        deliver(guardian, child, now + 4)
        assertEquals(AgeState.ADULT, child.share.ageState())
        assertTrue(child.share.verifiedGuardianIds().isEmpty())
    }

    @Test
    fun `age release by a verified guardian lifts the lock once`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2); val stranger = Party("stranger", 3)
        link(child, guardian); child.knows(stranger)
        // a stranger cannot release
        val fake = stranger.share.releaseAge(child.id, now)
        assertTrue(fake.isFailure)
        assertTrue(guardian.share.releaseAge(child.id, now + 10).isSuccess)
        val outs = deliver(guardian, child, now + 11)
        assertTrue(outs.single() is Outcome.Applied, "$outs")
        assertEquals(AgeState.ADULT, child.share.ageState())
        assertEquals(OnboardingState.ADULT, child.share.onboardingStateNow())
        assertTrue(child.share.saveGrantWithPolicy(grant(child, "g1")).isSuccess)
    }

    // ---------------------------------------------------------------- 10. circles

    @Test
    fun `10 circle membership is blocked until a guardian approves it and revocation makes the child leave`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2); val admin = Party("admin", 3)
        link(child, guardian); child.knows(admin); admin.knows(child)
        val group = Group("c1", "Fotbal", admin.id, admin.id, setOf(admin.id, child.id), 1L, admins = setOf(admin.id))
        val msg = MessageBuilder(admin.id).build(child.id, Kinds.MEMBERSHIP_UPDATE, "group", "c1", 1, Wire.json.encodeToString(Group.serializer(), group), now)
        assertTrue(child.receiver.receive(admin.id, Wire.encode(msg), now) is Outcome.Applied)
        // not a member: nothing stored, the invitation is parked
        assertTrue(child.share.groups().isEmpty())
        val invite = child.share.circleInvites().single()
        assertEquals("c1", invite.group.id)
        assertEquals(admin.id, invite.fromId)
        assertEquals(ConsentUiState.NEEDS_APPROVAL, child.share.consent.circleStates(listOf("c1"), child.id, now)["c1"])

        val req = child.share.requestCircleMembership(invite.group, MembershipAction.JOIN, now + 1).getOrThrow()
        assertEquals(ConsentSubject.CIRCLE_MEMBERSHIP, req.subject)
        assertEquals("Fotbal", req.subjectName)
        assertEquals(admin.id, req.circleAdminId)
        // asking twice keeps the same request
        assertEquals(req.requestId, child.share.requestCircleMembership(invite.group, MembershipAction.JOIN, now + 2).getOrThrow().requestId)
        assertEquals(ConsentUiState.WAITING, child.share.consent.circleStates(listOf("c1"), child.id, now + 3)["c1"])
        assertTrue(child.share.groups().isEmpty())

        deliver(child, guardian, now + 4)
        assertTrue(guardian.share.decideConsent(req.requestId, true, now + 5).isSuccess)
        deliver(guardian, child, now + 6)
        assertEquals(setOf("c1"), child.share.groups().keys)
        assertEquals(ConsentUiState.APPROVED, child.share.consent.circleStates(listOf("c1"), child.id, now + 7)["c1"])

        // a grant to the circle: needs its own approval, then flows
        val gg = grant(child, "gc", groupId = "c1")
        pendingReq(child.share.saveGrantWithPolicy(gg))
        approveAll(child, guardian, now + 10)
        assertEquals(listOf("gc"), child.share.effectiveGrants(now + 20).map { it.id })

        // the guardian revokes the circle: the child leaves, its grants to the circle are revoked
        assertTrue(guardian.share.revokeConsent(req.permissionId, now + 30).isSuccess)
        assertTrue(deliver(guardian, child, now + 31).all { it is Outcome.Applied })
        child.share.drainPendingRevokes(now + 32)
        assertFalse(child.share.groups().getValue("c1").members.contains(child.id))
        assertTrue(child.share.effectiveGrants(now + 33).isEmpty())
        assertFalse(child.share.grants().single { it.id == "gc" }.active)
        // the circle's members learn that the child left
        assertTrue(child.outbox.due(now + 40).any { it.kind == Kinds.MEMBERSHIP_UPDATE && it.recipient == admin.id })
    }

    @Test
    fun `10 a child creating a circle sends no membership message until it is approved`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2)
        link(child, guardian)
        val group = Group("mine", "Moje parta", child.id, child.id, setOf(child.id, "friend-1"), 1L, admins = setOf(child.id))
        child.share.saveAndBroadcastGroup(group)
        assertTrue(child.outbox.due(now + 1).none { it.kind == Kinds.MEMBERSHIP_UPDATE })
        val req = child.share.requestCircleMembership(group, MembershipAction.CREATE, now + 2).getOrThrow()
        assertTrue(child.outbox.due(now + 2).none { it.kind == Kinds.MEMBERSHIP_UPDATE })
        deliver(child, guardian, now + 3)
        guardian.share.decideConsent(req.requestId, true, now + 4)
        deliver(guardian, child, now + 5)
        assertEquals(listOf("friend-1"), child.outbox.due(now + 6).filter { it.kind == Kinds.MEMBERSHIP_UPDATE }.map { it.recipient })
    }

    // ---------------------------------------------------------------- 11. send time enforcement

    private fun seedSleep(p: Party) {
        val zone = ZoneId.systemDefault()
        val d = LocalDate.now(zone).minusDays(1)
        val ms = d.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        p.db.observations().upsert(
            listOf(
                MetricObservation(
                    id = "s1", provider = Provider.GARMIN_PERSONAL, accountId = "a", externalId = "s1", type = MetricType.SLEEP_DURATION,
                    startUtcMs = ms, endUtcMs = ms + 3_600_000L, value = 25_000.0, zoneId = zone.id, localDate = d.toString(), quality = Quality.OK,
                ).toRow(),
            ),
        )
    }

    @Test
    fun `11 send time enforcement a tampered active grant without a valid approval is never enqueued`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2)
        link(child, guardian); seedSleep(child)
        // 1. a grant written straight into the DB as active, no approval at all
        putLegacyGrant(child, "evil")
        assertEquals(0, child.share.planAndEnqueue(now + 1))
        assertTrue(child.outbox.due(now + 2).none { it.kind == Kinds.PROJECTION_UPSERT })

        // 2. a genuinely approved grant flows (positive control)
        val req = pendingReq(child.share.saveGrantWithPolicy(grant(child, "good")))
        approveAll(child, guardian, now + 10)
        assertTrue(child.share.planAndEnqueue(now + 20) > 0)
        child.outbox.due(now + 21).forEach { child.outbox.onAck(it.recipient, Ack(it.eventId, Ack.Status.ACCEPTED)) }

        // 3. forged approval row: flip a REJECTED/other row to APPROVED with a fake decision -> not enqueued
        val row = child.db.guardianConsents().get(req.requestId)!!
        val forgedDecision = Wire.json.decodeFromString(GuardianDecision.serializer(), row.decisionJson!!).let { it.copy(signatureHex = "00".repeat(64)) }
        child.db.guardianConsents().upsert(row.copy(decisionJson = Wire.json.encodeToString(GuardianDecision.serializer(), forgedDecision)))
        child.db.inbox().deleteAllSent()
        assertEquals(0, child.share.planAndEnqueue(now + 30))
        assertTrue(child.outbox.due(now + 31).none { it.kind == Kinds.PROJECTION_UPSERT })
    }

    @Test
    fun `reemit sends pending child link requests and consent requests that lack an active outbox item`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2)
        child.knows(guardian); guardian.knows(child)
        assertTrue(child.share.declareAge(AgeState.MINOR, now))
        val l = child.share.sendChildFamilyLinkRequest(guardian.contact(), now).getOrThrow()
        fun cancelAll(kind: String) = child.db.outbox().all().filter { it.kind == kind }
            .forEach { child.db.outbox().update(it.copy(state = DeliveryState.CANCELLED.name)) }
        // an active item exists: nothing to re-emit
        assertEquals(0, ServiceMigrations.reemitPendingControlMessages(child.db, child.id, child.share, now + 1))
        cancelAll(Kinds.FAMILY_LINK_REQUEST)
        assertEquals(1, ServiceMigrations.reemitPendingControlMessages(child.db, child.id, child.share, now + 2))
        val resent = child.outbox.due(now + 3).single { it.kind == Kinds.FAMILY_LINK_REQUEST }
        assertEquals(guardian.id, resent.recipient) // goes to the counterparty of the initiator (the parent)
        assertEquals(l.id, resent.objectId)
        // the re-sent request is accepted by the parent device
        assertTrue(deliver(child, guardian, now + 3).all { it is Outcome.Applied })
        guardian.share.respondFamilyLink(l.id, true, now + 4)
        deliver(guardian, child, now + 5)

        pendingReq(child.share.saveGrantWithPolicy(grant(child, "g1")))
        assertEquals(0, ServiceMigrations.reemitPendingControlMessages(child.db, child.id, child.share, now + 6))
        cancelAll(Kinds.GUARDIAN_CONSENT_REQUEST)
        assertEquals(1, ServiceMigrations.reemitPendingControlMessages(child.db, child.id, child.share, now + 7))
        assertEquals(listOf(guardian.id), child.outbox.due(now + 8).filter { it.kind == Kinds.GUARDIAN_CONSENT_REQUEST }.map { it.recipient })
        // the guardian takes the re-sent request; a second copy of the same request is an idempotent duplicate
        assertTrue(deliver(child, guardian, now + 8).all { it is Outcome.Applied })
        assertEquals(1, guardian.share.pendingGuardianInboxNow().size)
    }

    @Test
    fun `accepting a parent initiated minor link ratchets the child to MINOR`() {
        val parent = Party("parent", 1); val child = Party("child", 2)
        parent.knows(child); child.knows(parent)
        assertTrue(child.share.declareAge(AgeState.ADULT, now)) // e.g. a wrong first-run choice
        val l = parent.share.sendFamilyLinkRequest(child.id, AgeProfile.MINOR, now)
        assertTrue(deliver(parent, child, now).all { it is Outcome.Applied })
        assertEquals(1, child.share.pendingRequests().size)
        val accepted = child.share.respondFamilyLink(l.id, true, now + 1)
        assertEquals(FamilyLinkStatus.VERIFIED, accepted?.status)
        assertEquals(AgeState.MINOR, child.share.ageState())
        assertEquals(parent.id, child.settings.ageProfileManager.current().lockedByGuardianId)
        assertEquals(OnboardingState.MINOR_READY, child.share.onboardingStateNow())
        deliver(child, parent, now + 2)
        assertEquals(FamilyLinkStatus.VERIFIED, parent.share.familyLinks().single().status)
        // an ADULT-flagged link never locks anybody
        val other = Party("other", 3); other.knows(parent); parent.knows(other)
        assertTrue(other.share.declareAge(AgeState.ADULT, now))
        val l2 = parent.share.sendFamilyLinkRequest(other.id, AgeProfile.ADULT, now)
        deliver(parent, other, now)
        other.share.respondFamilyLink(l2.id, true, now + 1)
        assertEquals(AgeState.ADULT, other.share.ageState())
    }

    @Test
    fun `state flows reflect onboarding and the per grant consent state`() {
        val child = Party("child", 1); val guardian = Party("guardian", 2)
        fun <T> first(f: kotlinx.coroutines.flow.Flow<T>): T = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(10_000) { f.first() }
        }
        assertEquals(OnboardingState.UNKNOWN, first(child.share.onboardingState))
        link(child, guardian)
        assertEquals(OnboardingState.MINOR_READY, first(child.share.onboardingState))
        val g = grant(child, "g1")
        pendingReq(child.share.saveGrantWithPolicy(g))
        assertEquals(1, first(child.share.consentItems()).size)
        // a stored (e.g. legacy) active grant that was never approved
        putLegacyGrant(child, "legacy")
        val states = first(child.share.consentStateByGrant())
        assertEquals(ConsentUiState.NEEDS_APPROVAL, states["legacy"])
        deliver(child, guardian, now + 1)
        assertEquals(1, first(guardian.share.pendingGuardianInbox()).size)
    }

    @Test
    fun `the age lock keys are the only age state and the legacy key is never written`() {
        val p = Party("p", 1)
        p.share.declareAge(AgeState.ADULT, now)
        assertNull(p.settings.getRaw(AgeProfileManager.KEY_LEGACY_OWN_AGE))
        assertNotNull(p.settings.getRaw(AgeProfileManager.KEY_AGE_V2))
    }
}
