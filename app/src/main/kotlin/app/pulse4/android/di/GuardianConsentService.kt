package app.pulse4.android.di

import app.pulse4.android.data.AgeProfileManager
import app.pulse4.android.data.ContactRow
import app.pulse4.android.data.FamilyLinkRow
import app.pulse4.android.data.GroupRow
import app.pulse4.android.data.GuardianConsentRow
import app.pulse4.android.data.PendingRequestRow
import app.pulse4.sharing.AgeProfile
import app.pulse4.sharing.AgeState
import app.pulse4.sharing.CircleAdminPolicy
import app.pulse4.sharing.ConsentCanonical
import app.pulse4.sharing.ConsentCheck
import app.pulse4.sharing.ConsentRecord
import app.pulse4.sharing.ConsentStatus
import app.pulse4.sharing.ConsentSubject
import app.pulse4.sharing.ConsentVerdict
import app.pulse4.sharing.FamilyLink
import app.pulse4.sharing.FamilyLinkPolicy
import app.pulse4.sharing.FamilyLinkStatus
import app.pulse4.sharing.Group
import app.pulse4.sharing.GuardianAgeRelease
import app.pulse4.sharing.GuardianConsentPolicy
import app.pulse4.sharing.GuardianConsentRequest
import app.pulse4.sharing.GuardianDecision
import app.pulse4.sharing.GuardianRevocation
import app.pulse4.sharing.MembershipAction
import app.pulse4.sharing.RevocationReason
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.SignatureVerifierResolver
import app.pulse4.sharing.terms
import app.pulse4.sync.DeliveryState
import app.pulse4.sync.E2eeCrypto
import app.pulse4.sync.Kinds
import app.pulse4.sync.MessageBuilder
import app.pulse4.sync.OutboxItem
import app.pulse4.sync.OutboxManager
import app.pulse4.sync.Wire
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

internal fun hexToBytesOrNull(hex: String): ByteArray? {
    if (hex.length % 2 != 0 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** The Ed25519 half of a contact's 64-byte public key (X25519 || Ed25519), only when it derives to the contact id. */
internal fun contactEd25519Key(contact: ContactRow?): ByteArray? {
    contact ?: return null
    val pub = runCatching { Base64.getDecoder().decode(contact.publicKeyB64) }.getOrNull() ?: return null
    if (pub.size != 64) return null
    if (ServiceMigrations.deriveDeliveryHashes(contact.publicKeyB64).first != contact.identity) return null
    return pub.copyOfRange(32, 64)
}

/** Resolver that returns a verifier only for contacts whose stored key derives to the claimed identity id. */
internal fun contactVerifierResolver(lookup: (String) -> ContactRow?): SignatureVerifierResolver = { id ->
    contactEd25519Key(lookup(id))?.let { ed ->
        { bytes: ByteArray, sigHex: String ->
            hexToBytesOrNull(sigHex)?.let { E2eeCrypto.verifyDetached(ed, bytes, it) } ?: false
        }
    }
}

/**
 * Engine behind the guardian-consent features of [ShareService]: persistence in `guardian_consent`, verification
 * of every inbound decision / revocation / release, signed outbound messages and the effectiveness rule used at
 * send time. Handler paths pass the receiving identity explicitly (`self`); API paths use [selfIdProvider].
 */
internal class GuardianConsentService(
    private val data: PulseDataAccess,
    val age: AgeProfileManager,
    private val outbox: OutboxManager,
    private val selfIdProvider: () -> String,
    private val sign: (ByteArray) -> ByteArray,
    private val revokeGrantHook: (grantId: String, nowMs: Long, reason: RevocationReason) -> Unit,
    private val broadcastMembership: (group: Group, nowMs: Long, extraRecipients: Set<String>, self: String) -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val newNonce: () -> String = { ByteArray(16).also { SecureRandom().nextBytes(it) }.let(::bytesToHex) },
) {
    private val json = Wire.json
    private val activeOutbox = setOf(
        DeliveryState.QUEUED.name, DeliveryState.HANDED_TO_TRANSPORT.name,
        DeliveryState.STORED_IN_PROPAGATION.name, DeliveryState.ACKED.name,
    )
    private val approvalCache = ConcurrentHashMap<String, Boolean>()

    val verifierFor: SignatureVerifierResolver = contactVerifierResolver { data.contacts().get(it) }

    // ------------------------------------------------------------------ loading

    fun links(): List<FamilyLink> =
        data.familyLinks().allNow().mapNotNull { runCatching { json.decodeFromString(FamilyLink.serializer(), it.json) }.getOrNull() }

    private fun GuardianConsentRow.toItem(): ConsentItem? = runCatching {
        ConsentItem(
            requestId = requestId,
            role = ConsentRole.valueOf(role),
            subject = ConsentSubject.valueOf(subject),
            subjectId = subjectId,
            permissionVersion = permissionVersion,
            status = ConsentStatus.valueOf(status),
            request = json.decodeFromString(GuardianConsentRequest.serializer(), requestJson),
            proposedGrant = proposedGrantJson?.let { json.decodeFromString(ShareGrant.serializer(), it) },
            decision = decisionJson?.let { json.decodeFromString(GuardianDecision.serializer(), it) },
            linkId = linkId,
            peerId = peerId,
            createdAtMs = createdAtMs,
            expiresAtMs = expiresAtMs,
            decidedAtMs = decidedAtMs,
        )
    }.getOrNull()

    fun decode(row: GuardianConsentRow): ConsentItem? = row.toItem()

    fun items(role: ConsentRole): List<ConsentItem> =
        data.guardianConsents().byRole(role.name).mapNotNull { it.toItem() }

    fun allItems(): List<ConsentItem> = data.guardianConsents().allNow().mapNotNull { it.toItem() }

    fun item(requestId: String): ConsentItem? = data.guardianConsents().get(requestId)?.toItem()

    private fun ConsentItem.record() = ConsentRecord(request, status, decision)

    /**
     * The child's own records with every APPROVED one cryptographically re-verified (signature, digest, signer key
     * bound to its id); an APPROVED row that fails (tampered DB, replaced key) is dropped, i.e. never effective.
     * The result of each verification is cached per (row content, signer key) and re-done when either changes.
     */
    fun verifiedChildRecords(self: String): List<ConsentRecord> =
        items(ConsentRole.CHILD_OUT).mapNotNull { item ->
            val rec = item.record()
            if (rec.status != ConsentStatus.APPROVED) return@mapNotNull rec
            val signer = rec.decision?.guardianId
            val keyFp = signer?.let { data.contacts().get(it)?.publicKeyB64 }.orEmpty()
            val cacheKey = "${item.requestId}|${item.decision.hashCode()}|${item.request.hashCode()}|${keyFp.hashCode()}|$self"
            val ok = approvalCache.getOrPut(cacheKey) {
                GuardianConsentPolicy.verifyStoredApproval(self, rec, verifierFor).ok
            }
            if (ok) rec else null
        }

    private fun newestFirst(items: List<ConsentItem>) =
        items.sortedWith(compareByDescending<ConsentItem> { it.permissionVersion }.thenByDescending { it.createdAtMs })

    // ------------------------------------------------------------------ effectiveness

    fun ageState(): AgeState = age.state

    fun verifiedGuardianIds(self: String): Set<String> =
        GuardianConsentPolicy.verifiedGuardians(self, links(), verifiedChildRecords(self))

    fun isMembershipAllowed(self: String, circleId: String, nowMs: Long): Boolean {
        val state = age.state
        if (state == AgeState.ADULT) return true
        if (state == AgeState.UNKNOWN && circleId in age.grandfatheredCircleIds()) return true
        return GuardianConsentPolicy.isMembershipAllowed(self, circleId, state, links(), verifiedChildRecords(self), nowMs)
    }

    /** Own, active grants that may flow right now (age rule + signature-verified guardian approvals + circle approval). */
    fun effectiveGrants(all: List<ShareGrant>, self: String, nowMs: Long): List<ShareGrant> {
        val own = all.filter { it.active && it.ownerId == self }
        val state = age.state
        if (state == AgeState.ADULT) return own
        val links = links()
        val records = verifiedChildRecords(self)
        val gf = age.grandfatheredGrantIds()
        val gfCircles = age.grandfatheredCircleIds()
        return own.filter { g ->
            GuardianConsentPolicy.isEffective(g, state, links, records, gf, nowMs) &&
                (
                    g.groupId == null ||
                        (state == AgeState.UNKNOWN && g.groupId in gfCircles) ||
                        GuardianConsentPolicy.isMembershipAllowed(self, g.groupId!!, state, links, records, nowMs)
                    )
        }
    }

    fun canActivate(self: String, grant: ShareGrant, nowMs: Long): Boolean {
        val state = age.state
        if (state == AgeState.ADULT) return grant.active && grant.ownerId == self
        return FamilyLinkPolicy.canActivateGrant(
            self, state, grant, links(), verifiedChildRecords(self), age.grandfatheredGrantIds(), nowMs,
        )
    }

    /** May membership messages about [group] leave this device? (A child that is not an approved member sends none.) */
    fun mayBroadcastGroup(self: String, group: Group, nowMs: Long): Boolean {
        if (age.state == AgeState.ADULT) return true
        if (group.dissolved || self !in group.members) return true
        return isMembershipAllowed(self, group.id, nowMs)
    }

    fun onboardingState(self: String): OnboardingState = when (val s = age.state) {
        AgeState.UNKNOWN -> OnboardingState.UNKNOWN
        AgeState.ADULT -> OnboardingState.ADULT
        AgeState.MINOR ->
            if (FamilyLinkPolicy.isOnboardingComplete(s, self, links(), verifiedChildRecords(self))) {
                OnboardingState.MINOR_READY
            } else {
                OnboardingState.MINOR_WAITING_FOR_GUARDIAN
            }
    }

    fun grantStates(grants: List<ShareGrant>, self: String, nowMs: Long): Map<String, ConsentUiState> {
        val own = grants.filter { it.active && it.ownerId == self }
        val state = age.state
        if (state == AgeState.ADULT) return own.associate { it.id to ConsentUiState.NOT_REQUIRED }
        val effective = effectiveGrants(own, self, nowMs).map { it.id }.toSet()
        val gf = age.grandfatheredGrantIds()
        val bySubject = items(ConsentRole.CHILD_OUT).filter { it.subject == ConsentSubject.SHARE_GRANT }.groupBy { it.subjectId }
        return own.associate { g ->
            g.id to when {
                g.id in effective -> if (state == AgeState.UNKNOWN && g.id in gf) ConsentUiState.GRANDFATHERED else ConsentUiState.APPROVED
                else -> uiStateOf(bySubject[g.id].orEmpty(), nowMs)
            }
        }
    }

    fun circleStates(circleIds: Collection<String>, self: String, nowMs: Long): Map<String, ConsentUiState> {
        val state = age.state
        if (state == AgeState.ADULT) return circleIds.associateWith { ConsentUiState.NOT_REQUIRED }
        val bySubject = items(ConsentRole.CHILD_OUT).filter { it.subject == ConsentSubject.CIRCLE_MEMBERSHIP }.groupBy { it.subjectId }
        return circleIds.associateWith { id ->
            when {
                isMembershipAllowed(self, id, nowMs) ->
                    if (state == AgeState.UNKNOWN && id in age.grandfatheredCircleIds()) ConsentUiState.GRANDFATHERED else ConsentUiState.APPROVED
                else -> uiStateOf(bySubject[id].orEmpty(), nowMs)
            }
        }
    }

    private fun uiStateOf(rows: List<ConsentItem>, nowMs: Long): ConsentUiState {
        val latest = newestFirst(rows).firstOrNull() ?: return ConsentUiState.NEEDS_APPROVAL
        return when (latest.status) {
            ConsentStatus.PENDING -> if (nowMs > latest.expiresAtMs) ConsentUiState.EXPIRED else ConsentUiState.WAITING
            ConsentStatus.REJECTED -> ConsentUiState.REJECTED
            ConsentStatus.EXPIRED -> ConsentUiState.EXPIRED
            ConsentStatus.REVOKED -> ConsentUiState.REVOKED
            ConsentStatus.APPROVED, ConsentStatus.SUPERSEDED -> ConsentUiState.NEEDS_APPROVAL
        }
    }

    // ------------------------------------------------------------------ persistence helpers

    private fun putRow(
        req: GuardianConsentRequest,
        role: ConsentRole,
        status: ConsentStatus,
        nowMs: Long,
        peerId: String,
        linkId: String? = null,
        proposed: ShareGrant? = null,
        decision: GuardianDecision? = null,
        decidedAtMs: Long? = null,
    ) {
        data.guardianConsents().upsert(
            GuardianConsentRow(
                requestId = req.requestId,
                role = role.name,
                peerId = peerId,
                subject = req.subject.name,
                subjectId = req.subjectId,
                linkId = linkId,
                permissionVersion = req.permissionVersion,
                status = status.name,
                createdAtMs = nowMs,
                expiresAtMs = req.expiresAtMs,
                decidedAtMs = decidedAtMs,
                requestJson = json.encodeToString(GuardianConsentRequest.serializer(), req),
                proposedGrantJson = proposed?.let { json.encodeToString(ShareGrant.serializer(), it) },
                decisionJson = decision?.let { json.encodeToString(GuardianDecision.serializer(), it) },
            ),
        )
    }

    private fun setStatus(requestId: String, status: ConsentStatus, decision: GuardianDecision? = null, decidedAtMs: Long? = null) {
        val row = data.guardianConsents().get(requestId) ?: return
        data.guardianConsents().upsert(
            row.copy(
                status = status.name,
                decisionJson = decision?.let { json.encodeToString(GuardianDecision.serializer(), it) } ?: row.decisionJson,
                decidedAtMs = decidedAtMs ?: row.decidedAtMs,
            ),
        )
    }

    private fun emit(
        self: String,
        recipient: String,
        kind: String,
        objectType: String,
        objectId: String,
        objectVersion: Long,
        payload: String,
        nowMs: Long,
        expiresAtMs: Long? = null,
    ) {
        val msg = MessageBuilder(self).build(
            recipient, kind, objectType, objectId, objectVersion, payload, nowMs, expiresAtMs = expiresAtMs,
        )
        val e = msg.envelope
        // grantId stays null on purpose: revoke() would otherwise treat these as delivered projections.
        outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
    }

    private fun emitRequest(self: String, req: GuardianConsentRequest, recipient: String, nowMs: Long) =
        emit(
            self, recipient, Kinds.GUARDIAN_CONSENT_REQUEST, "consent_request", req.requestId, req.permissionVersion,
            json.encodeToString(GuardianConsentRequest.serializer(), req), nowMs, req.expiresAtMs,
        )

    internal fun emitLinkRevoke(self: String, recipient: String, link: FamilyLink, nowMs: Long) =
        emit(
            self, recipient, Kinds.FAMILY_LINK_REVOKE, "family_link", link.id, link.version,
            json.encodeToString(FamilyLink.serializer(), link), nowMs,
        )

    fun emitFamilyLinkRequest(self: String, link: FamilyLink, nowMs: Long) =
        emit(
            self, link.counterpartyOf(self), Kinds.FAMILY_LINK_REQUEST, "family_link", link.id, link.version,
            json.encodeToString(FamilyLink.serializer(), link), nowMs,
        )

    private fun FamilyLink.counterpartyOf(self: String) = if (self == parentId) childId else parentId

    private fun cancelQueuedRequestMessages(requestIds: Set<String>, recipient: String? = null) {
        if (requestIds.isEmpty() && recipient == null) return
        synchronized(outbox) {
            data.outbox().all()
                .filter {
                    it.kind == Kinds.GUARDIAN_CONSENT_REQUEST && it.state == DeliveryState.QUEUED.name &&
                        (requestIds.isEmpty() || it.objectId in requestIds) && (recipient == null || it.recipient == recipient)
                }
                .forEach { data.outbox().update(it.copy(state = DeliveryState.CANCELLED.name)) }
        }
    }

    // ------------------------------------------------------------------ child: issuing requests

    /** Stores and sends a request; the caller made sure the guardian set is valid. */
    private fun issue(self: String, req: GuardianConsentRequest, proposed: ShareGrant?, nowMs: Long) {
        putRow(req, ConsentRole.CHILD_OUT, ConsentStatus.PENDING, nowMs, req.guardianIds.sorted().joinToString(","), proposed = proposed)
        for (g in req.guardianIds) emitRequest(self, req, g, nowMs)
    }

    private fun nextVersion(items: List<ConsentItem>) = (items.maxOfOrNull { it.permissionVersion } ?: 0L) + 1L

    private fun childSubjectItems(subject: ConsentSubject, subjectId: String) =
        items(ConsentRole.CHILD_OUT).filter { it.subject == subject && it.subjectId == subjectId }

    private fun supersedePending(rows: List<ConsentItem>) {
        rows.forEach { setStatus(it.requestId, ConsentStatus.SUPERSEDED) }
        cancelQueuedRequestMessages(rows.map { it.requestId }.toSet())
    }

    fun requestGuardianConsent(grant: ShareGrant, nowMs: Long): Result<GuardianConsentRequest> {
        val self = selfIdProvider()
        if (grant.ownerId != self || !grant.active) return Result.failure(IllegalArgumentException("Neplatné sdílení."))
        when (age.state) {
            AgeState.ADULT -> return Result.failure(IllegalStateException("Souhlas zákonného zástupce se nevyžaduje."))
            AgeState.UNKNOWN -> return Result.failure(AgeNotConfirmedException())
            AgeState.MINOR -> Unit
        }
        return data.inTransaction {
            expireStale(nowMs)
            val records = verifiedChildRecords(self)
            val guardians = GuardianConsentPolicy.verifiedGuardians(self, links(), records)
            if (guardians.isEmpty()) return@inTransaction Result.failure(NoGuardianException())
            val same = childSubjectItems(ConsentSubject.SHARE_GRANT, grant.id)
            val pending = same.filter { it.status == ConsentStatus.PENDING && it.expiresAtMs >= nowMs }
            pending.firstOrNull { it.request.terms == grant.terms() && it.request.groupId == grant.groupId }
                ?.let { return@inTransaction Result.success(it.request) }
            supersedePending(pending)
            val baseline = same.filter { it.status == ConsentStatus.APPROVED && it.record() in records }
                .maxByOrNull { it.permissionVersion }?.request?.terms
            val req = GuardianConsentPolicy.newRequest(newId(), newNonce(), guardians, grant, nextVersion(same), baseline, nowMs)
            issue(self, req, grant, nowMs)
            Result.success(req)
        }
    }

    fun requestCircleMembership(circle: Group, action: MembershipAction, nowMs: Long): Result<GuardianConsentRequest> {
        val self = selfIdProvider()
        when (age.state) {
            AgeState.ADULT -> return Result.failure(IllegalStateException("Souhlas zákonného zástupce se nevyžaduje."))
            AgeState.UNKNOWN -> return Result.failure(AgeNotConfirmedException())
            AgeState.MINOR -> Unit
        }
        val admin = circle.adminId.ifBlank { circle.effectiveAdmins.firstOrNull().orEmpty() }
        if (admin.isBlank()) return Result.failure(IllegalArgumentException("Kruh nemá správce."))
        return data.inTransaction {
            expireStale(nowMs)
            val records = verifiedChildRecords(self)
            val guardians = GuardianConsentPolicy.verifiedGuardians(self, links(), records)
            if (guardians.isEmpty()) return@inTransaction Result.failure(NoGuardianException())
            val same = childSubjectItems(ConsentSubject.CIRCLE_MEMBERSHIP, circle.id)
            val approved = same.filter { it.status == ConsentStatus.APPROVED && it.record() in records }.maxByOrNull { it.permissionVersion }
            if (approved != null && newestFirst(same).first().requestId == approved.requestId) {
                return@inTransaction Result.success(approved.request)
            }
            val pending = same.filter { it.status == ConsentStatus.PENDING && it.expiresAtMs >= nowMs }
            pending.firstOrNull { it.request.action == action }?.let { return@inTransaction Result.success(it.request) }
            supersedePending(pending)
            val req = GuardianConsentPolicy.newMembershipRequest(
                newId(), newNonce(), guardians, self, circle.id, circle.name, admin, action, nextVersion(same), nowMs,
            )
            issue(self, req, null, nowMs)
            Result.success(req)
        }
    }

    fun requestGuardianAdd(newParent: ContactRow, nowMs: Long): Result<GuardianConsentRequest> {
        val self = selfIdProvider()
        if (age.state != AgeState.MINOR) return Result.failure(IllegalStateException("Přidat zástupce lze jen na účtu nezletilého."))
        if (contactEd25519Key(newParent) == null) {
            return Result.failure(IllegalArgumentException("Klíč kontaktu neodpovídá jeho identitě."))
        }
        val keyDigest = ConsentCanonical.sha256Hex(Base64.getDecoder().decode(newParent.publicKeyB64))
        return data.inTransaction {
            expireStale(nowMs)
            val records = verifiedChildRecords(self)
            val guardians = GuardianConsentPolicy.verifiedGuardians(self, links(), records)
            if (guardians.isEmpty()) return@inTransaction Result.failure(NoGuardianException())
            if (newParent.identity in guardians || newParent.identity == self) {
                return@inTransaction Result.failure(IllegalArgumentException("Tento zástupce už je přidán."))
            }
            if (guardians.size >= GuardianConsentPolicy.MAX_GUARDIANS) {
                return@inTransaction Result.failure(IllegalStateException("Dosažen maximální počet zástupců."))
            }
            val same = childSubjectItems(ConsentSubject.GUARDIAN_ADD, newParent.identity)
            val pending = same.filter { it.status == ConsentStatus.PENDING && it.expiresAtMs >= nowMs }
            pending.firstOrNull { it.request.guardianIds == guardians }?.let { return@inTransaction Result.success(it.request) }
            supersedePending(pending)
            val req = GuardianConsentPolicy.newGuardianAddRequest(
                newId(), newNonce(), guardians, self, newParent.identity, newParent.displayName, keyDigest, nextVersion(same), nowMs,
            )
            issue(self, req, null, nowMs)
            Result.success(req)
        }
    }

    /** Withdraws a still pending outgoing request (nothing was approved yet). */
    fun withdrawRequest(requestId: String): Boolean {
        val it = item(requestId) ?: return false
        if (it.role != ConsentRole.CHILD_OUT || it.status != ConsentStatus.PENDING) return false
        supersedePending(listOf(it))
        return true
    }

    // ------------------------------------------------------------------ expiry / re-emit

    /** PENDING rows past their expiry become EXPIRED (both roles); queued request messages are cancelled. */
    fun expireStale(nowMs: Long): Int {
        val stale = allItems().filter { it.status == ConsentStatus.PENDING && nowMs > it.expiresAtMs }
        if (stale.isEmpty()) return 0
        data.inTransaction { stale.forEach { setStatus(it.requestId, ConsentStatus.EXPIRED) } }
        cancelQueuedRequestMessages(stale.filter { it.role == ConsentRole.CHILD_OUT }.map { it.requestId }.toSet())
        return stale.size
    }

    /** Re-sends PENDING requests to every addressed guardian that has no active outbox item for it. */
    fun reemitPendingRequests(self: String, nowMs: Long): Int {
        val pending = items(ConsentRole.CHILD_OUT).filter { it.status == ConsentStatus.PENDING && nowMs <= it.expiresAtMs }
        if (pending.isEmpty()) return 0
        val rows = data.outbox().all()
        var n = 0
        for (p in pending) {
            for (g in p.request.guardianIds) {
                val has = rows.any {
                    it.kind == Kinds.GUARDIAN_CONSENT_REQUEST && it.objectId == p.requestId && it.recipient == g && it.state in activeOutbox
                }
                if (!has) { emitRequest(self, p.request, g, nowMs); n++ }
            }
        }
        return n
    }

    fun maintenance(self: String, nowMs: Long) {
        if (age.state == AgeState.ADULT) return
        expireStale(nowMs)
        reemitPendingRequests(self, nowMs)
    }

    // ------------------------------------------------------------------ guardian: handler + decisions

    private fun failure(c: ConsentCheck): app.pulse4.sync.HandlerResult = when (c) {
        ConsentCheck.UNSUPPORTED_SCHEMA, ConsentCheck.MALFORMED, ConsentCheck.INVALID_VALIDITY, ConsentCheck.BAD_NONCE ->
            app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.INVALID_PAYLOAD)
        ConsentCheck.EXPIRED -> app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.EXPIRED)
        else -> app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.NOT_PERMITTED)
    }

    private val applied get() = app.pulse4.sync.HandlerResult.Applied
    private val stale get() = app.pulse4.sync.HandlerResult.Stale

    fun onIncomingRequest(self: String, author: String, payload: String, nowMs: Long): app.pulse4.sync.HandlerResult {
        val req = runCatching { json.decodeFromString(GuardianConsentRequest.serializer(), payload) }.getOrNull()
            ?: return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.INVALID_PAYLOAD)
        val existing = items(ConsentRole.GUARDIAN_IN).map { it.record() }
        val check = GuardianConsentPolicy.verifyIncomingRequest(self, author, req, links(), existing, nowMs)
        when (check) {
            ConsentCheck.OK -> Unit
            ConsentCheck.DUPLICATE_REQUEST, ConsentCheck.OLDER_PERMISSION_VERSION -> return stale
            else -> return failure(check)
        }
        val link = links().first { it.parentId == self && GuardianConsentPolicy.isGuardianLink(it, author) }
        // a newer version of the same subject supersedes older still-pending rows
        items(ConsentRole.GUARDIAN_IN)
            .filter { it.status == ConsentStatus.PENDING && it.subject == req.subject && it.subjectId == req.subjectId }
            .forEach { setStatus(it.requestId, ConsentStatus.SUPERSEDED) }
        putRow(req, ConsentRole.GUARDIAN_IN, ConsentStatus.PENDING, nowMs, peerId = author, linkId = link.id)
        return applied
    }

    fun decideConsent(requestId: String, approve: Boolean, nowMs: Long): Result<GuardianDecision> {
        val self = selfIdProvider()
        val item = item(requestId)?.takeIf { it.role == ConsentRole.GUARDIAN_IN }
            ?: return Result.failure(IllegalArgumentException("Žádost nenalezena."))
        if (item.status != ConsentStatus.PENDING) return Result.failure(IllegalStateException("Žádost už byla vyřízena."))
        if (nowMs > item.expiresAtMs) {
            setStatus(requestId, ConsentStatus.EXPIRED)
            return Result.failure(IllegalStateException("Platnost žádosti vypršela."))
        }
        val req = item.request
        val childId = req.childId
        if (self !in req.guardianIds || links().none { it.parentId == self && GuardianConsentPolicy.isGuardianLink(it, childId) }) {
            return Result.failure(IllegalStateException("Nejste ověřený zástupce tohoto dítěte."))
        }
        val unsigned = GuardianDecision(
            verdict = if (approve) ConsentVerdict.APPROVED else ConsentVerdict.REJECTED,
            requestId = requestId, guardianId = self, childId = childId,
            requestDigest = ConsentCanonical.requestDigestHex(req), decidedAtMs = nowMs, signatureHex = "",
        )
        val decision = unsigned.copy(signatureHex = bytesToHex(sign(ConsentCanonical.decision(unsigned))))
        data.inTransaction {
            setStatus(requestId, if (approve) ConsentStatus.APPROVED else ConsentStatus.REJECTED, decision, nowMs)
            emit(
                self, childId, if (approve) Kinds.GUARDIAN_CONSENT_APPROVED else Kinds.GUARDIAN_CONSENT_REJECTED,
                "consent_decision", requestId, req.permissionVersion,
                json.encodeToString(GuardianDecision.serializer(), decision), nowMs, req.expiresAtMs,
            )
        }
        return Result.success(decision)
    }

    /** Guardian revokes an approval (any request row it holds, also one another guardian approved). */
    fun revokeConsent(permissionId: String, nowMs: Long): Result<GuardianRevocation> {
        val self = selfIdProvider()
        val item = items(ConsentRole.GUARDIAN_IN).filter { it.permissionId == permissionId }
            .firstOrNull { it.status in setOf(ConsentStatus.APPROVED, ConsentStatus.PENDING, ConsentStatus.REJECTED) }
            ?: return Result.failure(IllegalArgumentException("Souhlas nenalezen."))
        if (item.subject == ConsentSubject.GUARDIAN_ADD) {
            // Guardians are equal: nobody removes another guardian; a guardian leaves only by removing their own link.
            return Result.failure(IllegalStateException("Přidání zástupce nelze odvolat. Zástupce odchází odebráním vlastní vazby."))
        }
        val childId = item.request.childId
        if (links().none { it.parentId == self && GuardianConsentPolicy.isGuardianLink(it, childId) }) {
            return Result.failure(IllegalStateException("Nejste ověřený zástupce tohoto dítěte."))
        }
        val unsigned = GuardianRevocation(
            requestId = item.requestId, guardianId = self, childId = childId, permissionId = permissionId,
            revokedAtMs = nowMs, signatureHex = "",
        )
        val rev = unsigned.copy(signatureHex = bytesToHex(sign(ConsentCanonical.revocation(unsigned))))
        data.inTransaction {
            setStatus(item.requestId, ConsentStatus.REVOKED)
            emit(
                self, childId, Kinds.GUARDIAN_CONSENT_REVOKED, "consent_revocation", item.requestId, nowMs,
                json.encodeToString(GuardianRevocation.serializer(), rev), nowMs,
            )
        }
        return Result.success(rev)
    }

    fun releaseAge(childId: String, nowMs: Long): Result<GuardianAgeRelease> {
        val self = selfIdProvider()
        val link = links().firstOrNull { it.parentId == self && GuardianConsentPolicy.isGuardianLink(it, childId) }
            ?: return Result.failure(IllegalStateException("Nejste ověřený zástupce tohoto dítěte."))
        val unsigned = GuardianAgeRelease(
            guardianId = self, childId = childId, linkId = link.id, nonce = newNonce(), issuedAtMs = nowMs,
            expiresAtMs = nowMs + app.pulse4.sharing.AgeProfilePolicy.MAX_RELEASE_TTL_MS, signatureHex = "",
        )
        val rel = unsigned.copy(signatureHex = bytesToHex(sign(ConsentCanonical.ageRelease(unsigned))))
        emit(
            self, childId, Kinds.GUARDIAN_AGE_RELEASE, "age_release", rel.nonce, nowMs,
            json.encodeToString(GuardianAgeRelease.serializer(), rel), nowMs, rel.expiresAtMs,
        )
        return Result.success(rel)
    }

    // ------------------------------------------------------------------ child: handlers

    private fun markPendingLinkInbox(linkId: String, status: String) {
        data.pendingRequests().get(linkId)?.let { data.pendingRequests().upsert(it.copy(status = status)) }
    }

    fun onDecision(self: String, author: String, payload: String, kindVerdict: ConsentVerdict, nowMs: Long): app.pulse4.sync.HandlerResult {
        val dec = runCatching { json.decodeFromString(GuardianDecision.serializer(), payload) }.getOrNull()
            ?: return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.INVALID_PAYLOAD)
        if (dec.verdict != kindVerdict) return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.INVALID_PAYLOAD)
        val row = data.guardianConsents().get(dec.requestId)
        val rowItem = row?.toItem()?.takeIf { it.role == ConsentRole.CHILD_OUT }
        val records = verifiedChildRecords(self)
        val check = GuardianConsentPolicy.verifyDecision(
            self, author, dec, rowItem?.proposedGrant, records, links(), nowMs, verifierFor,
        )
        when (check) {
            ConsentCheck.OK -> Unit
            ConsentCheck.NOT_PENDING -> return stale
            ConsentCheck.EXPIRED -> {
                if (rowItem != null && rowItem.status == ConsentStatus.PENDING) setStatus(rowItem.requestId, ConsentStatus.EXPIRED)
                return failure(check)
            }
            else -> return failure(check)
        }
        val item = checkNotNull(rowItem)
        if (dec.verdict == ConsentVerdict.REJECTED) {
            setStatus(item.requestId, ConsentStatus.REJECTED, dec, nowMs)
            return applied
        }
        // APPROVED: state transition, older versions superseded, subject effects: one transaction (caller's receive tx).
        setStatus(item.requestId, ConsentStatus.APPROVED, dec, nowMs)
        childSubjectItems(item.subject, item.subjectId)
            .filter { it.requestId != item.requestId && it.permissionVersion < item.permissionVersion &&
                (it.status == ConsentStatus.PENDING || it.status == ConsentStatus.APPROVED) }
            .forEach { setStatus(it.requestId, ConsentStatus.SUPERSEDED) }
        when (item.subject) {
            ConsentSubject.SHARE_GRANT -> {
                val proposed = checkNotNull(item.proposedGrant)
                val existing = data.grants().get(proposed.id)?.let { runCatching { json.decodeFromString(ShareGrant.serializer(), it.json) }.getOrNull() }
                if (existing != null && !existing.active) {
                    // a revoked grant is never revived by a late approval
                    setStatus(item.requestId, ConsentStatus.SUPERSEDED)
                    return stale
                }
                data.grants().upsert(app.pulse4.android.data.GrantRow(proposed.id, !proposed.active, json.encodeToString(ShareGrant.serializer(), proposed)))
            }
            ConsentSubject.CIRCLE_MEMBERSHIP -> applyApprovedMembership(self, item, nowMs)
            ConsentSubject.GUARDIAN_ADD -> finalizeAwaitingLinks(self, nowMs)
        }
        return applied
    }

    private fun applyApprovedMembership(self: String, item: ConsentItem, nowMs: Long) {
        val circleId = item.subjectId
        val inviteRow = data.pendingRequests().get(inviteId(circleId))
        if (inviteRow != null && inviteRow.status == "PENDING") {
            val g = runCatching { json.decodeFromString(Group.serializer(), inviteRow.json) }.getOrNull()
            val local = data.grants().group(circleId)?.let { runCatching { json.decodeFromString(Group.serializer(), it.json) }.getOrNull() }
            if (g != null && self in g.members && (local == null || g.version > local.version)) {
                data.grants().upsertGroup(GroupRow(g.id, g.version, json.encodeToString(Group.serializer(), g)))
            }
            data.pendingRequests().upsert(inviteRow.copy(status = "ACCEPTED"))
        } else {
            // CREATE (or an already stored circle): now that it is approved, tell the members.
            data.grants().group(circleId)?.let { row ->
                runCatching { json.decodeFromString(Group.serializer(), row.json) }.getOrNull()?.let { g ->
                    if (self in g.members) broadcastMembership(g, nowMs, emptySet(), self)
                }
            }
        }
    }

    fun inviteId(circleId: String) = "circle-invite-$circleId"

    fun circleInvites(): List<CircleInvite> =
        data.pendingRequests().pendingNow().filter { it.kind == "CIRCLE_INVITE" }.mapNotNull { r ->
            runCatching { CircleInvite(json.decodeFromString(Group.serializer(), r.json), r.fromId) }.getOrNull()
        }

    /** Parks a membership update that would make a non-approved child a member (instead of storing the circle). */
    fun parkCircleInvite(self: String, author: String, group: Group, payload: String, nowMs: Long) {
        val id = inviteId(group.id)
        val prev = data.pendingRequests().get(id)
        val prevVersion = prev?.let { runCatching { json.decodeFromString(Group.serializer(), it.json).version }.getOrNull() } ?: -1L
        if (group.version <= prevVersion) return
        data.pendingRequests().upsert(
            PendingRequestRow(id = id, kind = "CIRCLE_INVITE", fromId = author, toId = self, status = "PENDING", createdAtMs = nowMs, json = payload),
        )
    }

    fun onRevocation(self: String, author: String, payload: String, nowMs: Long): app.pulse4.sync.HandlerResult {
        val rev = runCatching { json.decodeFromString(GuardianRevocation.serializer(), payload) }.getOrNull()
            ?: return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.INVALID_PAYLOAD)
        val records = verifiedChildRecords(self)
        val check = GuardianConsentPolicy.verifyRevocation(self, author, rev, records, links(), verifierFor)
        when (check) {
            ConsentCheck.OK -> Unit
            ConsentCheck.NOT_APPROVED -> return stale
            else -> return failure(check)
        }
        val item = checkNotNull(item(rev.requestId))
        setStatus(item.requestId, ConsentStatus.REVOKED)
        when (item.subject) {
            ConsentSubject.SHARE_GRANT -> revokeGrantHook(item.subjectId, nowMs, RevocationReason.GUARDIAN_REVOKED)
            ConsentSubject.CIRCLE_MEMBERSHIP -> leaveCircleForced(self, item.subjectId, nowMs, RevocationReason.GUARDIAN_REVOKED)
            // Unreachable: verifyRevocation rejects GUARDIAN_ADD (a guardian is never removed by another guardian).
            ConsentSubject.GUARDIAN_ADD -> Unit
        }
        return applied
    }

    fun onAgeRelease(self: String, author: String, payload: String, nowMs: Long): app.pulse4.sync.HandlerResult {
        val rel = runCatching { json.decodeFromString(GuardianAgeRelease.serializer(), payload) }.getOrNull()
            ?: return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.INVALID_PAYLOAD)
        return when (val out = age.applyRelease(self, author, rel, links(), verifiedChildRecords(self), nowMs, verifierFor)) {
            is app.pulse4.sharing.ReleaseOutcome.Released -> applied
            is app.pulse4.sharing.ReleaseOutcome.Rejected -> when (out.reason) {
                app.pulse4.sharing.ReleaseRejection.NOT_MINOR, app.pulse4.sharing.ReleaseRejection.NONCE_REUSED -> stale
                app.pulse4.sharing.ReleaseRejection.EXPIRED ->
                    app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.EXPIRED)
                app.pulse4.sharing.ReleaseRejection.UNSUPPORTED_SCHEMA, app.pulse4.sharing.ReleaseRejection.INVALID_VALIDITY,
                app.pulse4.sharing.ReleaseRejection.BAD_NONCE ->
                    app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.INVALID_PAYLOAD)
                else -> app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.NOT_PERMITTED)
            }
        }
    }

    // ------------------------------------------------------------------ circles

    /** The child leaves (or dissolves) the circle and revokes every grant to it; used for guardian-driven removal. */
    fun leaveCircleForced(self: String, circleId: String, nowMs: Long, reason: RevocationReason) {
        data.grants().allNow()
            .mapNotNull { runCatching { json.decodeFromString(ShareGrant.serializer(), it.json) }.getOrNull() }
            .filter { it.ownerId == self && it.active && it.groupId == circleId }
            .forEach { revokeGrantHook(it.id, nowMs, reason) }
        data.pendingRequests().get(inviteId(circleId))?.let { data.pendingRequests().upsert(it.copy(status = "REJECTED")) }
        val group = data.grants().group(circleId)?.let { runCatching { json.decodeFromString(Group.serializer(), it.json) }.getOrNull() } ?: return
        if (self !in group.members) return
        val updated = CircleAdminPolicy.leaveCircle(group, self)
            ?: CircleAdminPolicy.dissolveCircle(group, self)
            ?: group.copy(members = group.members - self, admins = group.admins - self, version = group.version + 1)
        data.grants().upsertGroup(GroupRow(updated.id, updated.version, json.encodeToString(Group.serializer(), updated)))
        broadcastMembership(updated, nowMs, group.members - self - updated.members, self)
    }

    // ------------------------------------------------------------------ family links

    private fun putLink(link: FamilyLink) =
        data.familyLinks().upsert(
            FamilyLinkRow(link.id, link.parentId, link.childId, link.status.name, link.version, json.encodeToString(FamilyLink.serializer(), link)),
        )

    private fun markerId(linkId: String) = "gadd-await-$linkId"

    private fun parkAwaitingGuardianAdd(accepted: FamilyLink, nowMs: Long) {
        data.pendingRequests().upsert(
            PendingRequestRow(
                id = markerId(accepted.id), kind = "GUARDIAN_LINK_AWAITING_ADD", fromId = accepted.parentId, toId = accepted.childId,
                status = "AWAITING", createdAtMs = nowMs, json = json.encodeToString(FamilyLink.serializer(), accepted),
            ),
        )
    }

    /** Child side: the link becomes VERIFIED (ever-verified flag, age ratchet). */
    private fun markChildLinkVerified(self: String, link: FamilyLink, nowMs: Long) {
        age.markGuardianLinkVerified()
        age.onLinkAccepted(link, self, nowMs)
    }

    /** Child: a link request was accepted by the other side; may it become VERIFIED right now? */
    fun childAcceptCheck(self: String, accepted: FamilyLink): ConsentCheck =
        GuardianConsentPolicy.canVerifyGuardianLink(accepted, self, links(), verifiedChildRecords(self))

    /**
     * Re-checks links waiting for a GUARDIAN_ADD approval and verifies those that can now be. Child-initiated links
     * need nothing more; for parent-initiated ones the (so far withheld) acceptance is sent to the parent.
     */
    fun finalizeAwaitingLinks(self: String, nowMs: Long): Int {
        var n = 0
        for (marker in data.pendingRequests().allNow().filter { it.kind == "GUARDIAN_LINK_AWAITING_ADD" && it.status == "AWAITING" }) {
            val accepted = runCatching { json.decodeFromString(FamilyLink.serializer(), marker.json) }.getOrNull()
            val current = accepted?.let { data.familyLinks().get(it.id) }?.let { runCatching { json.decodeFromString(FamilyLink.serializer(), it.json) }.getOrNull() }
            if (accepted == null || current == null || current.status != FamilyLinkStatus.PENDING || current.childId != self) {
                data.pendingRequests().upsert(marker.copy(status = "CANCELLED"))
                continue
            }
            if (childAcceptCheck(self, accepted) != ConsentCheck.OK) continue
            val verified = current.copy(status = FamilyLinkStatus.VERIFIED, verifiedAtMs = nowMs, version = maxOf(accepted.version, current.version + 1))
            putLink(verified)
            markChildLinkVerified(self, verified, nowMs)
            data.pendingRequests().upsert(marker.copy(status = "DONE"))
            if (current.initiator != self) {
                emit(
                    self, current.parentId, Kinds.FAMILY_LINK_RESPONSE, "family_link", verified.id, verified.version,
                    json.encodeToString(FamilyLink.serializer(), verified), nowMs,
                )
            }
            n++
        }
        return n
    }

    fun sendChildFamilyLinkRequest(parent: ContactRow, nowMs: Long): Result<FamilyLink> {
        val self = selfIdProvider()
        if (age.state != AgeState.MINOR) return Result.failure(IllegalStateException("Žádost o zástupce může poslat jen účet nezletilého."))
        if (contactEd25519Key(parent) == null) return Result.failure(IllegalArgumentException("Klíč kontaktu neodpovídá jeho identitě."))
        if (parent.identity == self) return Result.failure(IllegalArgumentException("Nelze požádat sám sebe."))
        return data.inTransaction {
            val existing = links().firstOrNull {
                it.childId == self && it.parentId == parent.identity &&
                    (it.status == FamilyLinkStatus.PENDING || it.status == FamilyLinkStatus.VERIFIED)
            }
            if (existing != null) {
                if (existing.status == FamilyLinkStatus.VERIFIED) {
                    return@inTransaction Result.failure(IllegalStateException("Tento zástupce už je propojen."))
                }
                if (existing.initiator == self) return@inTransaction Result.success(existing)
            }
            val guardians = verifiedGuardianIds(self)
            if (guardians.size >= GuardianConsentPolicy.MAX_GUARDIANS) {
                return@inTransaction Result.failure(IllegalStateException("Dosažen maximální počet zástupců."))
            }
            val link = FamilyLinkPolicy.createChildRequest(newId(), parent.identity, self, nowMs)
            putLink(link)
            emitFamilyLinkRequest(self, link, nowMs)
            if (guardians.isNotEmpty()) requestGuardianAdd(parent, nowMs)
            Result.success(link)
        }
    }

    /** Accept / reject a pending incoming link request (the counterparty of the initiator). */
    fun respondFamilyLink(linkId: String, accept: Boolean, nowMs: Long): FamilyLink? {
        val row = data.familyLinks().get(linkId) ?: return null
        val link = runCatching { json.decodeFromString(FamilyLink.serializer(), row.json) }.getOrNull() ?: return null
        val self = selfIdProvider()
        val updated = runCatching { FamilyLinkPolicy.respond(link, self, accept, nowMs) }.getOrNull() ?: return null
        return data.inTransaction {
            if (accept && self == link.childId && link.childAgeProfile == AgeProfile.MINOR) {
                // The child accepts a guardian link: the first guardian needs nothing, a further one needs GUARDIAN_ADD.
                when (childAcceptCheck(self, updated)) {
                    ConsentCheck.OK -> Unit
                    ConsentCheck.GUARDIAN_ADD_MISSING -> {
                        parkAwaitingGuardianAdd(updated, nowMs)
                        data.contacts().get(link.parentId)?.let { requestGuardianAdd(it, nowMs) }
                        markPendingLinkInbox(linkId, "ACCEPTED")
                        return@inTransaction link
                    }
                    else -> return@inTransaction null
                }
                markChildLinkVerified(self, updated, nowMs)
            }
            putLink(updated)
            markPendingLinkInbox(linkId, if (accept) "ACCEPTED" else "REJECTED")
            emit(
                self, link.initiator, Kinds.FAMILY_LINK_RESPONSE, "family_link", updated.id, updated.version,
                json.encodeToString(FamilyLink.serializer(), updated), nowMs,
            )
            updated
        }
    }

    /** Inbound FAMILY_LINK_REQUEST (either direction). */
    fun onFamilyLinkRequest(self: String, author: String, payload: String, nowMs: Long): app.pulse4.sync.HandlerResult {
        val link = runCatching { json.decodeFromString(FamilyLink.serializer(), payload) }.getOrNull()
            ?: return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.INVALID_PAYLOAD)
        val normalized = FamilyLinkPolicy.acceptIncomingRequest(link, self, author)
            ?: return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.NOT_PERMITTED)
        val existingRow = data.familyLinks().get(link.id)
        if (existingRow != null) {
            val ex = runCatching { json.decodeFromString(FamilyLink.serializer(), existingRow.json) }.getOrNull()
            // SEC-2: nobody takes over an existing link by UUID; a VERIFIED link is never downgraded to PENDING.
            if (ex == null || ex.parentId != normalized.parentId || ex.childId != normalized.childId || ex.initiator != author) {
                return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.NOT_PERMITTED)
            }
            if (normalized.version <= existingRow.version) return stale
            if (ex.status == FamilyLinkStatus.VERIFIED) return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.NOT_PERMITTED)
        }
        val json0 = json.encodeToString(FamilyLink.serializer(), normalized)
        putLink(normalized)
        data.pendingRequests().upsert(
            PendingRequestRow(
                id = normalized.id, kind = "FAMILY_LINK_REQUEST", fromId = author, toId = self,
                status = "PENDING", createdAtMs = nowMs, json = json0,
            ),
        )
        return applied
    }

    /** Inbound FAMILY_LINK_RESPONSE (to the initiator, from its counterparty). */
    fun onFamilyLinkResponse(self: String, author: String, payload: String, nowMs: Long): app.pulse4.sync.HandlerResult {
        val link = runCatching { json.decodeFromString(FamilyLink.serializer(), payload) }.getOrNull()
            ?: return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.INVALID_PAYLOAD)
        if (link.status != FamilyLinkStatus.VERIFIED && link.status != FamilyLinkStatus.REJECTED) {
            return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.INVALID_PAYLOAD)
        }
        val row = data.familyLinks().get(link.id)
            ?: return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.NOT_PERMITTED)
        val ex = runCatching { json.decodeFromString(FamilyLink.serializer(), row.json) }.getOrNull()
            ?: return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.NOT_PERMITTED)
        if (ex.parentId != link.parentId || ex.childId != link.childId || ex.initiator != self ||
            !FamilyLinkPolicy.isValidRemoteResponse(ex, author)
        ) {
            return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.NOT_PERMITTED)
        }
        if (link.version <= ex.version) return stale
        if (link.status == FamilyLinkStatus.REJECTED) {
            putLink(ex.copy(status = FamilyLinkStatus.REJECTED, version = link.version))
            data.pendingRequests().get(markerId(ex.id))?.let { data.pendingRequests().upsert(it.copy(status = "CANCELLED")) }
            return applied
        }
        val verified = ex.copy(status = FamilyLinkStatus.VERIFIED, verifiedAtMs = nowMs, version = link.version)
        if (self == ex.childId) {
            // Child-initiated link accepted by the parent: the child verifies it only when policy allows.
            when (childAcceptCheck(self, verified)) {
                ConsentCheck.OK -> Unit
                ConsentCheck.GUARDIAN_ADD_MISSING -> {
                    parkAwaitingGuardianAdd(verified, nowMs)
                    return applied
                }
                else -> return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.NOT_PERMITTED)
            }
            markChildLinkVerified(self, verified, nowMs)
        }
        putLink(verified)
        return applied
    }

    /**
     * Removes a link. A VERIFIED link can be removed only by its parent (a child cannot drop a guardian); a PENDING
     * one can be cancelled by its initiator or rejected by the counterparty. Sends `family.link.revoke` to the
     * other party and applies the consent fallout.
     */
    fun revokeFamilyLink(linkId: String, nowMs: Long): Result<FamilyLink> {
        val self = selfIdProvider()
        val link = links().firstOrNull { it.id == linkId } ?: return Result.failure(IllegalArgumentException("Vazba nenalezena."))
        if (self != link.parentId && self != link.childId) return Result.failure(IllegalStateException("Vazbu může odebrat jen jedna ze stran."))
        if (link.status == FamilyLinkStatus.REVOKED) return Result.success(link)
        if (link.status == FamilyLinkStatus.REJECTED) return Result.failure(IllegalStateException("Odmítnutou vazbu nelze odebrat."))
        if (!FamilyLinkPolicy.canRemove(link, self)) {
            return Result.failure(IllegalStateException("Potvrzenou vazbu na rodiče může odebrat jen rodič. Dítě může zrušit jen žádost, kterou rodič ještě nepřijal."))
        }
        return Result.success(data.inTransaction { removeLinkLocally(self, link, nowMs, notifyCounterparty = true) })
    }

    /** Inbound `family.link.revoke`: only the other party may remove it; a child cannot remove a VERIFIED link. */
    fun onFamilyLinkRevoke(self: String, author: String, payload: String, nowMs: Long): app.pulse4.sync.HandlerResult {
        val msg = runCatching { json.decodeFromString(FamilyLink.serializer(), payload) }.getOrNull()
            ?: return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.INVALID_PAYLOAD)
        if (msg.status != FamilyLinkStatus.REVOKED) return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.INVALID_PAYLOAD)
        val all = links()
        val ex = all.firstOrNull { it.id == msg.id }
            ?: return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.NOT_PERMITTED)
        if (!FamilyLinkPolicy.isValidRemoteRevoke(ex, author, self) || !FamilyLinkPolicy.isValidLinkRemoval(ex, author, all, emptyList())) {
            return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.NOT_PERMITTED)
        }
        if (ex.status == FamilyLinkStatus.REVOKED) return stale
        if (ex.status == FamilyLinkStatus.REJECTED) return app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.NOT_PERMITTED)
        removeLinkLocally(self, ex, nowMs, notifyCounterparty = false)
        return applied
    }

    private fun removeLinkLocally(self: String, link: FamilyLink, nowMs: Long, notifyCounterparty: Boolean): FamilyLink {
        val wasVerified = link.status == FamilyLinkStatus.VERIFIED
        val removed = link.copy(status = FamilyLinkStatus.REVOKED, version = link.version + 1)
        putLink(removed)
        markPendingLinkInbox(link.id, "REVOKED")
        data.pendingRequests().get(markerId(link.id))?.let { data.pendingRequests().upsert(it.copy(status = "CANCELLED")) }
        if (notifyCounterparty) emitLinkRevoke(self, removed.counterpartyOf(self), removed, nowMs)
        if (self == removed.childId) {
            if (wasVerified) applyChildLinkRemovalFallout(self, removed, nowMs)
            cancelQueuedRequestMessages(emptySet(), recipient = removed.parentId)
        } else {
            // guardian side: its own undecided rows for this child are void
            items(ConsentRole.GUARDIAN_IN)
                .filter { it.request.childId == removed.childId && it.status == ConsentStatus.PENDING }
                .forEach { setStatus(it.requestId, ConsentStatus.REVOKED) }
        }
        return removed
    }

    private fun applyChildLinkRemovalFallout(self: String, removed: FamilyLink, nowMs: Long) {
        val all = links().map { if (it.id == removed.id) removed else it }
        val records = verifiedChildRecords(self)
        val affected = FamilyLinkPolicy.consentsAffectedByLinkRemoval(removed, all, records)
        val raw = items(ConsentRole.CHILD_OUT).associateBy { it.requestId }
        for (rec in affected) {
            val item = raw[rec.request.requestId] ?: continue
            val wasApproved = item.status == ConsentStatus.APPROVED
            setStatus(item.requestId, ConsentStatus.REVOKED)
            if (!wasApproved) continue
            when (item.subject) {
                ConsentSubject.SHARE_GRANT -> revokeGrantHook(item.subjectId, nowMs, RevocationReason.GUARDIAN_LINK_REMOVED)
                ConsentSubject.CIRCLE_MEMBERSHIP -> leaveCircleForced(self, item.subjectId, nowMs, RevocationReason.GUARDIAN_LINK_REMOVED)
                ConsentSubject.GUARDIAN_ADD -> Unit
            }
        }
        cancelQueuedRequestMessages(affected.filter { it.status == ConsentStatus.PENDING }.map { it.request.requestId }.toSet())
    }
}
