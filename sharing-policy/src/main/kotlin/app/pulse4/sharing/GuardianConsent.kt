package app.pulse4.sharing

import app.pulse4.domain.Provider
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * Verified guardian consent for minors (pure JVM logic, no crypto dependency).
 *
 * Trust anchor: a guardian is the `parentId` of a VERIFIED [FamilyLink] with `childAgeProfile == MINOR` and
 * `childId == self`, and which is "qualified": it is the founding (earliest verified) guardian link or a
 * `GUARDIAN_ADD` approval from an existing guardian exists for it ([GuardianConsentPolicy.verifiedGuardians]).
 * A child can have several guardians; any single verified guardian can decide a request (first valid decision
 * wins), revoke any approval or remove any guardian link. Signatures are checked through an injected
 * [SignatureVerifier]; the caller resolves the signer's Ed25519 key from the contact and must only return a
 * verifier when the contact's key derives to the claimed identity id.
 */

/** Verifies a detached signature (lowercase hex) over the given bytes with an already resolved key. */
typealias SignatureVerifier = (bytes: ByteArray, signatureHex: String) -> Boolean

/** Resolves the verifier for an identity id; `null` when the contact is unknown or its key does not match the id. */
typealias SignatureVerifierResolver = (identityId: String) -> SignatureVerifier?

@Serializable
enum class ConsentStatus { PENDING, APPROVED, REJECTED, EXPIRED, REVOKED, SUPERSEDED }

@Serializable
enum class ConsentVerdict { APPROVED, REJECTED }

/** What a consent request is about. Each subject type has its own canonical shape (domain separation). */
@Serializable
enum class ConsentSubject { SHARE_GRANT, CIRCLE_MEMBERSHIP, GUARDIAN_ADD }

@Serializable
enum class MembershipAction { JOIN, CREATE }

/** The sharing terms a guardian approves; everything that widens what a recipient can see is in here. */
@Serializable
data class GrantTerms(
    val recipients: Set<String>,
    val fields: Set<Field>,
    val planRights: Map<String, PlanRight> = emptyMap(),
    val scope: TimeScope,
    /** `null` means all sources. */
    val sources: Set<Provider>? = null,
)

fun ShareGrant.terms(): GrantTerms = GrantTerms(recipients, fields, planRights, scope, sources)

/**
 * Child -> ALL verified guardians: asks for approval of one subject at [permissionVersion]. The request bytes
 * are identical for every guardian, so there is a single digest; the signer is bound in the decision.
 *
 * - [ConsentSubject.SHARE_GRANT]: [subjectId] is the grant id, [terms] (and optional [groupId], [baseline]) are set.
 * - [ConsentSubject.CIRCLE_MEMBERSHIP]: [subjectId] is the circle id; [subjectName] (circle name),
 *   [circleAdminId] and [action] are set.
 * - [ConsentSubject.GUARDIAN_ADD]: [subjectId] is the id of the proposed new guardian (derived from its public
 *   key), [subjectName] its display name and [subjectKeyDigest] the SHA-256 hex of its public key material.
 */
@Serializable
data class GuardianConsentRequest(
    val schema: Int = 1,
    val subject: ConsentSubject = ConsentSubject.SHARE_GRANT,
    val requestId: String,
    /** 16 random bytes, 32 lowercase hex characters. */
    val nonce: String,
    /** The verified guardians this request was addressed to (at issue time). */
    val guardianIds: Set<String>,
    val childId: String,
    /** Grant id, circle id or new guardian id, depending on [subject]. */
    val subjectId: String,
    val permissionVersion: Long,
    val groupId: String? = null,
    val terms: GrantTerms? = null,
    /** Currently approved terms (what the guardian already agreed to), `null` when nothing was approved yet. */
    val baseline: GrantTerms? = null,
    val subjectName: String? = null,
    val circleAdminId: String? = null,
    val action: MembershipAction? = null,
    val subjectKeyDigest: String? = null,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
) {
    /** Permission id = `subjectId#permissionVersion`. */
    val permissionId: String get() = "$subjectId#$permissionVersion"

    /** Each subject type carries exactly its own fields. */
    val wellFormed: Boolean
        get() = guardianIds.isNotEmpty() && childId !in guardianIds && when (subject) {
            ConsentSubject.SHARE_GRANT ->
                terms != null && subjectName == null && circleAdminId == null && action == null && subjectKeyDigest == null
            ConsentSubject.CIRCLE_MEMBERSHIP ->
                terms == null && baseline == null && groupId == null &&
                    subjectName != null && circleAdminId != null && action != null && subjectKeyDigest == null
            ConsentSubject.GUARDIAN_ADD ->
                terms == null && baseline == null && groupId == null && circleAdminId == null && action == null &&
                    subjectName != null && subjectKeyDigest != null && subjectId !in guardianIds && subjectId != childId
        }
}

/** Guardian -> child: signed decision over the digest of the child's canonical request. */
@Serializable
data class GuardianDecision(
    val schema: Int = 1,
    val verdict: ConsentVerdict,
    val requestId: String,
    /** The signer (any verified guardian the request was addressed to). */
    val guardianId: String,
    val childId: String,
    /** Lowercase hex SHA-256 of [ConsentCanonical.request]. */
    val requestDigest: String,
    val decidedAtMs: Long,
    val signatureHex: String,
)

/** Guardian -> child: withdraws a previously approved permission (any verified guardian may do so). */
@Serializable
data class GuardianRevocation(
    val schema: Int = 1,
    val requestId: String,
    /** The signer. */
    val guardianId: String,
    val childId: String,
    val permissionId: String,
    val revokedAtMs: Long,
    val signatureHex: String,
)

/** Guardian -> child: one-time release of the age lock (MINOR -> ADULT); any verified guardian may sign. */
@Serializable
data class GuardianAgeRelease(
    val schema: Int = 1,
    /** The signer. */
    val guardianId: String,
    val childId: String,
    /** The signer's own family link. */
    val linkId: String,
    val nonce: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val signatureHex: String,
)

/** A stored consent row as seen by the policy (child-side outgoing request or guardian-side incoming one). */
@Serializable
data class ConsentRecord(
    val request: GuardianConsentRequest,
    val status: ConsentStatus,
    val decision: GuardianDecision? = null,
)

/** Result of a verification; `OK` is the only success value. */
enum class ConsentCheck {
    OK,
    UNSUPPORTED_SCHEMA,
    MALFORMED,
    AUTHOR_MISMATCH,
    UNKNOWN_REQUEST,
    ID_MISMATCH,
    NOT_A_GUARDIAN,
    NOT_PENDING,
    NOT_APPROVED,
    EXPIRED,
    INVALID_VALIDITY,
    LINK_INVALID,
    DIGEST_MISMATCH,
    PROPOSAL_MISMATCH,
    OLDER_PERMISSION_VERSION,
    DUPLICATE_REQUEST,
    BAD_NONCE,
    GUARDIAN_ADD_MISSING,
    TOO_MANY_GUARDIANS,
    NO_GUARDIAN_KEY,
    BAD_SIGNATURE,
    ;

    val ok: Boolean get() = this == OK
}

/**
 * Deterministic canonical bytes that are signed / hashed. NOT JSON: a domain header line followed by
 * `key=value\n` lines in a fixed order, `subject=` first. Sets are sorted, plan rights are sorted by plan id,
 * `*` stands for "all/none" (`null` sources, `null` group, `null` scope bound). String values are
 * percent-escaped (`% \n \r , | : * =`) so values can never inject lines or separators.
 */
object ConsentCanonical {
    const val REQUEST_HEADER = "P4-GC-v1"
    const val DECISION_HEADER = "P4-GD-v1"
    const val REVOCATION_HEADER = "P4-GR-v1"
    const val AGE_RELEASE_HEADER = "P4-GA-v1"

    fun request(r: GuardianConsentRequest): ByteArray = build(REQUEST_HEADER) {
        line("subject", r.subject.name)
        line("schema", r.schema.toString())
        line("requestId", esc(r.requestId))
        line("nonce", esc(r.nonce))
        line("guardianIds", r.guardianIds.sorted().joinToString(",", transform = ::esc))
        line("childId", esc(r.childId))
        when (r.subject) {
            ConsentSubject.SHARE_GRANT -> {
                line("grantId", esc(r.subjectId))
                line("permissionVersion", r.permissionVersion.toString())
                line("groupId", r.groupId?.let(::esc) ?: STAR)
                terms("", requireNotNull(r.terms) { "share request without terms" })
                val b = r.baseline
                if (b == null) {
                    line("baseline", "none")
                } else {
                    line("baseline", "present")
                    terms("baseline.", b)
                }
            }
            ConsentSubject.CIRCLE_MEMBERSHIP -> {
                line("circleId", esc(r.subjectId))
                line("permissionVersion", r.permissionVersion.toString())
                line("circleName", esc(requireNotNull(r.subjectName) { "membership request without name" }))
                line("circleAdminId", esc(requireNotNull(r.circleAdminId) { "membership request without admin" }))
                line("action", requireNotNull(r.action) { "membership request without action" }.name)
            }
            ConsentSubject.GUARDIAN_ADD -> {
                line("newGuardianId", esc(r.subjectId))
                line("permissionVersion", r.permissionVersion.toString())
                line("newGuardianName", esc(requireNotNull(r.subjectName) { "guardian add without name" }))
                line("newGuardianKeyDigest", esc(requireNotNull(r.subjectKeyDigest) { "guardian add without key digest" }))
            }
        }
        line("issuedAtMs", r.issuedAtMs.toString())
        line("expiresAtMs", r.expiresAtMs.toString())
    }

    fun requestDigestHex(r: GuardianConsentRequest): String = sha256Hex(request(r))

    fun decision(d: GuardianDecision): ByteArray = build(DECISION_HEADER) {
        line("decision", d.verdict.name)
        line("requestDigest", esc(d.requestDigest))
        line("requestId", esc(d.requestId))
        line("guardianId", esc(d.guardianId))
        line("childId", esc(d.childId))
        line("decidedAtMs", d.decidedAtMs.toString())
    }

    fun revocation(r: GuardianRevocation): ByteArray = build(REVOCATION_HEADER) {
        line("requestId", esc(r.requestId))
        line("guardianId", esc(r.guardianId))
        line("childId", esc(r.childId))
        line("permissionId", esc(r.permissionId))
        line("revokedAtMs", r.revokedAtMs.toString())
    }

    fun ageRelease(a: GuardianAgeRelease): ByteArray = build(AGE_RELEASE_HEADER) {
        line("guardianId", esc(a.guardianId))
        line("childId", esc(a.childId))
        line("linkId", esc(a.linkId))
        line("nonce", esc(a.nonce))
        line("issuedAtMs", a.issuedAtMs.toString())
        line("expiresAtMs", a.expiresAtMs.toString())
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private const val STAR = "*"

    private class Builder {
        val sb = StringBuilder()

        fun line(key: String, value: String) {
            sb.append(key).append('=').append(value).append('\n')
        }

        fun terms(prefix: String, t: GrantTerms) {
            line(prefix + "recipients", t.recipients.sorted().joinToString(",", transform = ::esc))
            line(prefix + "fields", t.fields.map { it.name }.sorted().joinToString(","))
            line(
                prefix + "planRights",
                t.planRights.entries.sortedBy { it.key }.joinToString(",") { esc(it.key) + ":" + it.value.name },
            )
            line(
                prefix + "scope",
                t.scope.kind.name + "|" + (t.scope.from?.let(::esc) ?: STAR) + "|" + (t.scope.to?.let(::esc) ?: STAR),
            )
            line(prefix + "sources", t.sources?.map { it.name }?.sorted()?.joinToString(",") ?: STAR)
        }
    }

    private fun build(header: String, block: Builder.() -> Unit): ByteArray {
        val b = Builder()
        b.sb.append(header).append('\n')
        b.block()
        return b.sb.toString().toByteArray(Charsets.UTF_8)
    }

    internal fun esc(s: String): String {
        if (s.isEmpty()) return "%"
        val out = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '%', '\n', '\r', ',', '|', ':', '*', '=' -> out.append('%').append("%02X".format(c.code))
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}

object GuardianConsentPolicy {
    const val SCHEMA = 1
    const val DEFAULT_REQUEST_TTL_MS: Long = 7L * 24 * 3600 * 1000
    const val MAX_REQUEST_TTL_MS: Long = 30L * 24 * 3600 * 1000
    /** Sane cap on the number of guardians of one child. */
    const val MAX_GUARDIANS = 4
    private const val NONCE_HEX_LEN = 32
    private val HEX = Regex("^[0-9a-f]+$")

    /** A VERIFIED guardian link for [childId] (child flagged MINOR); says nothing about qualification. */
    fun isGuardianLink(link: FamilyLink, childId: String): Boolean =
        link.status == FamilyLinkStatus.VERIFIED &&
            link.childAgeProfile == AgeProfile.MINOR &&
            link.childId == childId

    /**
     * The ids of the child's verified guardians: parents of VERIFIED MINOR links that are qualified, i.e. the
     * founding (earliest verified, ties by link id) link, or a link with an APPROVED, unrevoked, intact
     * `GUARDIAN_ADD` record from [records] for that parent. A self-appointed "guardian" the child added without
     * an existing guardian's approval is therefore never part of the set. [records] are the child's records.
     */
    fun verifiedGuardians(childId: String, links: List<FamilyLink>, records: List<ConsentRecord>): Set<String> {
        val candidates = links.filter { isGuardianLink(it, childId) }
        if (candidates.isEmpty()) return emptySet()
        val founder = candidates.minWithOrNull(compareBy<FamilyLink>({ it.verifiedAtMs ?: Long.MAX_VALUE }, { it.id }))
        return candidates
            .filter { it === founder || latestApprovedRequest(records, ConsentSubject.GUARDIAN_ADD, it.parentId, childId) != null }
            .map { it.parentId }
            .toSet()
    }

    /** The verified guardian link of [guardianId] for [childId], if that guardian is qualified. */
    fun guardianLink(links: List<FamilyLink>, records: List<ConsentRecord>, childId: String, guardianId: String): FamilyLink? =
        if (guardianId in verifiedGuardians(childId, links, records)) {
            links.firstOrNull { isGuardianLink(it, childId) && it.parentId == guardianId }
        } else {
            null
        }

    /**
     * May [link] (a guardian link of [childId] that the counterparty accepted) become VERIFIED on the child's
     * device? The first guardian needs nothing; every further guardian needs a `GUARDIAN_ADD` approval from an
     * existing guardian for exactly this parent id, and at most [MAX_GUARDIANS] guardians are allowed.
     */
    fun canVerifyGuardianLink(
        link: FamilyLink,
        childId: String,
        links: List<FamilyLink>,
        records: List<ConsentRecord>,
    ): ConsentCheck {
        if (link.childId != childId || link.childAgeProfile != AgeProfile.MINOR || link.parentId == childId) {
            return ConsentCheck.LINK_INVALID
        }
        val others = links.filter { it.id != link.id }
        val existing = verifiedGuardians(childId, others, records)
        if (existing.isEmpty()) return ConsentCheck.OK
        if (link.parentId in existing) return ConsentCheck.OK
        if (existing.size >= MAX_GUARDIANS) return ConsentCheck.TOO_MANY_GUARDIANS
        val approved = latestApprovedRequest(records, ConsentSubject.GUARDIAN_ADD, link.parentId, childId)
        return if (approved != null) ConsentCheck.OK else ConsentCheck.GUARDIAN_ADD_MISSING
    }

    private fun checkAddressees(guardianIds: Set<String>, childId: String, ttlMs: Long) {
        require(guardianIds.isNotEmpty() && guardianIds.size <= MAX_GUARDIANS) { "Invalid guardian set" }
        require(childId !in guardianIds) { "Child cannot be its own guardian" }
        require(ttlMs in 1..MAX_REQUEST_TTL_MS) { "Invalid request ttl" }
    }

    /** Builds a share request for [grant], addressed to [guardianIds] (all verified guardians of the child). */
    fun newRequest(
        requestId: String,
        nonce: String,
        guardianIds: Set<String>,
        grant: ShareGrant,
        permissionVersion: Long,
        baseline: GrantTerms?,
        nowMs: Long,
        ttlMs: Long = DEFAULT_REQUEST_TTL_MS,
    ): GuardianConsentRequest {
        checkAddressees(guardianIds, grant.ownerId, ttlMs)
        return GuardianConsentRequest(
            requestId = requestId,
            nonce = nonce,
            guardianIds = guardianIds,
            childId = grant.ownerId,
            subject = ConsentSubject.SHARE_GRANT,
            subjectId = grant.id,
            permissionVersion = permissionVersion,
            groupId = grant.groupId,
            terms = grant.terms(),
            baseline = baseline,
            issuedAtMs = nowMs,
            expiresAtMs = nowMs + ttlMs,
        )
    }

    /** Builds a circle join/create request; the guardians see [circleName] and [circleAdminId]. */
    fun newMembershipRequest(
        requestId: String,
        nonce: String,
        guardianIds: Set<String>,
        childId: String,
        circleId: String,
        circleName: String,
        circleAdminId: String,
        action: MembershipAction,
        permissionVersion: Long,
        nowMs: Long,
        ttlMs: Long = DEFAULT_REQUEST_TTL_MS,
    ): GuardianConsentRequest {
        checkAddressees(guardianIds, childId, ttlMs)
        return GuardianConsentRequest(
            requestId = requestId,
            nonce = nonce,
            guardianIds = guardianIds,
            childId = childId,
            subject = ConsentSubject.CIRCLE_MEMBERSHIP,
            subjectId = circleId,
            permissionVersion = permissionVersion,
            subjectName = circleName,
            circleAdminId = circleAdminId,
            action = action,
            issuedAtMs = nowMs,
            expiresAtMs = nowMs + ttlMs,
        )
    }

    /**
     * Builds a request to add [newGuardianId] as another guardian; addressed to the EXISTING guardians. The new
     * id must be the public-key-derived identity id; [newGuardianKeyDigest] is the SHA-256 hex of its key.
     */
    fun newGuardianAddRequest(
        requestId: String,
        nonce: String,
        existingGuardianIds: Set<String>,
        childId: String,
        newGuardianId: String,
        newGuardianName: String,
        newGuardianKeyDigest: String,
        permissionVersion: Long,
        nowMs: Long,
        ttlMs: Long = DEFAULT_REQUEST_TTL_MS,
    ): GuardianConsentRequest {
        checkAddressees(existingGuardianIds, childId, ttlMs)
        require(newGuardianId !in existingGuardianIds && newGuardianId != childId) { "Invalid new guardian id" }
        return GuardianConsentRequest(
            requestId = requestId,
            nonce = nonce,
            guardianIds = existingGuardianIds,
            childId = childId,
            subject = ConsentSubject.GUARDIAN_ADD,
            subjectId = newGuardianId,
            permissionVersion = permissionVersion,
            subjectName = newGuardianName,
            subjectKeyDigest = newGuardianKeyDigest,
            issuedAtMs = nowMs,
            expiresAtMs = nowMs + ttlMs,
        )
    }

    /** Does [grant] carry exactly the terms (and ids) the [request] asked approval for? */
    fun matches(grant: ShareGrant, request: GuardianConsentRequest): Boolean =
        request.subject == ConsentSubject.SHARE_GRANT &&
            grant.active &&
            grant.id == request.subjectId &&
            grant.ownerId == request.childId &&
            grant.groupId == request.groupId &&
            grant.terms() == request.terms

    /** Subset test per field: everything in [candidate] is allowed by [approved]. */
    fun covers(approved: GrantTerms, candidate: GrantTerms): Boolean {
        if (!approved.recipients.containsAll(candidate.recipients)) return false
        if (!approved.fields.containsAll(candidate.fields)) return false
        val rightsOk = candidate.planRights.all { (planId, right) ->
            (approved.planRights[planId]?.ordinal ?: -1) >= right.ordinal
        }
        if (!rightsOk) return false
        if (!sourcesCovered(approved.sources, candidate.sources)) return false
        return scopeCovers(approved.scope, candidate.scope)
    }

    private fun sourcesCovered(outer: Set<Provider>?, inner: Set<Provider>?): Boolean =
        outer == null || (inner != null && outer.containsAll(inner))

    fun scopeCovers(outer: TimeScope, inner: TimeScope): Boolean {
        val outerFrom = outer.from?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        val innerFrom = inner.from?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        if (innerFrom.isBefore(outerFrom)) return false
        if (outer.kind == TimeScope.Kind.ONCE) {
            if (inner.kind != TimeScope.Kind.ONCE) return false
            val outerTo = outer.to?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
            val innerTo = inner.to?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
            if (innerTo.isAfter(outerTo)) return false
        }
        return true
    }

    /**
     * True when [candidate] only narrows what the owner's [existingGrants] already allow, per recipient
     * (fields, sources, scope and plan rights are all covered). Narrowing never needs a guardian.
     */
    fun isGrantNarrowing(existingGrants: List<ShareGrant>, candidate: ShareGrant): Boolean {
        if (existingGrants.isEmpty() || !candidate.active) return false
        val activeExisting = existingGrants.filter { it.active && it.ownerId == candidate.ownerId }
        if (activeExisting.isEmpty()) return false
        if (candidate.recipients.isEmpty()) return true
        return candidate.recipients.all { recipient ->
            val grantsForRecipient = activeExisting.filter { recipient in it.recipients }
            if (grantsForRecipient.isEmpty()) return@all false
            val fieldsCovered = candidate.fields.all { field ->
                grantsForRecipient.any { g ->
                    field in g.fields &&
                        sourcesCovered(g.sources, candidate.sources) &&
                        scopeCovers(g.scope, candidate.scope)
                }
            }
            val planRightsCovered = candidate.planRights.all { (planId, right) ->
                grantsForRecipient.any { g -> (g.planRights[planId]?.ordinal ?: -1) >= right.ordinal }
            }
            fieldsCovered && planRightsCovered
        }
    }

    /**
     * Guardian side: validates an incoming request. [selfId] is the guardian, [envelopeAuthor] the
     * authenticated sender (must be the child, and [selfId] must be one of the addressed guardians with a
     * VERIFIED MINOR link to it). [existing] are the guardian's stored incoming records.
     */
    fun verifyIncomingRequest(
        selfId: String,
        envelopeAuthor: String,
        request: GuardianConsentRequest,
        links: List<FamilyLink>,
        existing: List<ConsentRecord>,
        now: Long,
    ): ConsentCheck {
        if (request.schema != SCHEMA) return ConsentCheck.UNSUPPORTED_SCHEMA
        if (!request.wellFormed || request.guardianIds.size > MAX_GUARDIANS) return ConsentCheck.MALFORMED
        if (selfId !in request.guardianIds || request.childId != envelopeAuthor) return ConsentCheck.ID_MISMATCH
        val hasLink = links.any { it.parentId == selfId && isGuardianLink(it, envelopeAuthor) }
        if (!hasLink) return ConsentCheck.LINK_INVALID
        if (request.nonce.length != NONCE_HEX_LEN || !HEX.matches(request.nonce)) return ConsentCheck.BAD_NONCE
        if (request.permissionVersion < 1L || request.issuedAtMs > request.expiresAtMs ||
            request.expiresAtMs - request.issuedAtMs > MAX_REQUEST_TTL_MS
        ) {
            return ConsentCheck.INVALID_VALIDITY
        }
        if (now > request.expiresAtMs) return ConsentCheck.EXPIRED
        if (existing.any { it.request.requestId == request.requestId || it.request.nonce == request.nonce }) {
            return ConsentCheck.DUPLICATE_REQUEST
        }
        val highest = existing
            .filter { sameSubject(it.request, request) }
            .maxOfOrNull { it.request.permissionVersion }
        if (highest != null && request.permissionVersion <= highest) return ConsentCheck.OLDER_PERMISSION_VERSION
        return ConsentCheck.OK
    }

    /**
     * Child side: verifies an incoming decision against the child's OWN stored request. The signer
     * (`decision.guardianId`) must be a verified guardian addressed by the request; the first valid decision
     * wins because only a PENDING request accepts one. [records] are all the child's outgoing records (the
     * stored one is looked up by requestId), [proposedGrant] is the locally stored grant a SHARE_GRANT request
     * was made for (ignored, may be `null`, for other subjects).
     */
    fun verifyDecision(
        selfId: String,
        envelopeAuthor: String,
        decision: GuardianDecision,
        proposedGrant: ShareGrant?,
        records: List<ConsentRecord>,
        links: List<FamilyLink>,
        now: Long,
        verifierFor: SignatureVerifierResolver,
    ): ConsentCheck {
        if (decision.schema != SCHEMA) return ConsentCheck.UNSUPPORTED_SCHEMA
        if (envelopeAuthor != decision.guardianId) return ConsentCheck.AUTHOR_MISMATCH
        val stored = records.firstOrNull { it.request.requestId == decision.requestId }
            ?: return ConsentCheck.UNKNOWN_REQUEST
        val req = stored.request
        if (req.childId != selfId || decision.childId != selfId) return ConsentCheck.ID_MISMATCH
        if (!req.wellFormed) return ConsentCheck.MALFORMED
        if (decision.guardianId !in req.guardianIds ||
            decision.guardianId !in verifiedGuardians(selfId, links, records)
        ) {
            return ConsentCheck.NOT_A_GUARDIAN
        }
        if (stored.status != ConsentStatus.PENDING) return ConsentCheck.NOT_PENDING
        if (now > req.expiresAtMs || decision.decidedAtMs > req.expiresAtMs) return ConsentCheck.EXPIRED
        if (decision.requestDigest != ConsentCanonical.requestDigestHex(req)) return ConsentCheck.DIGEST_MISMATCH
        if (decision.verdict == ConsentVerdict.APPROVED) {
            if (req.subject == ConsentSubject.SHARE_GRANT && (proposedGrant == null || !matches(proposedGrant, req))) {
                return ConsentCheck.PROPOSAL_MISMATCH
            }
            val newer = records.any {
                sameSubject(it.request, req) &&
                    it.request.permissionVersion > req.permissionVersion &&
                    (it.status == ConsentStatus.APPROVED || it.status == ConsentStatus.REVOKED)
            }
            if (newer) return ConsentCheck.OLDER_PERMISSION_VERSION
        }
        val verifier = verifierFor(decision.guardianId) ?: return ConsentCheck.NO_GUARDIAN_KEY
        if (!verifier(ConsentCanonical.decision(decision), decision.signatureHex)) return ConsentCheck.BAD_SIGNATURE
        return ConsentCheck.OK
    }

    /**
     * Child side: verifies a revocation of an APPROVED permission. ANY verified guardian may revoke, also an
     * approval another guardian gave. Revoking a `GUARDIAN_ADD` approval drops that guardian again.
     */
    fun verifyRevocation(
        selfId: String,
        envelopeAuthor: String,
        revocation: GuardianRevocation,
        records: List<ConsentRecord>,
        links: List<FamilyLink>,
        verifierFor: SignatureVerifierResolver,
    ): ConsentCheck {
        if (revocation.schema != SCHEMA) return ConsentCheck.UNSUPPORTED_SCHEMA
        if (envelopeAuthor != revocation.guardianId) return ConsentCheck.AUTHOR_MISMATCH
        val stored = records.firstOrNull { it.request.requestId == revocation.requestId }
            ?: return ConsentCheck.UNKNOWN_REQUEST
        val req = stored.request
        if (req.childId != selfId || revocation.childId != selfId || revocation.permissionId != req.permissionId) {
            return ConsentCheck.ID_MISMATCH
        }
        if (revocation.guardianId !in verifiedGuardians(selfId, links, records)) return ConsentCheck.NOT_A_GUARDIAN
        if (stored.status != ConsentStatus.APPROVED) return ConsentCheck.NOT_APPROVED
        val verifier = verifierFor(revocation.guardianId) ?: return ConsentCheck.NO_GUARDIAN_KEY
        if (!verifier(ConsentCanonical.revocation(revocation), revocation.signatureHex)) return ConsentCheck.BAD_SIGNATURE
        return ConsentCheck.OK
    }

    /**
     * Re-verifies the stored APPROVED decision of [record] (digest, ids, signer among the addressed guardians,
     * signature); use when loading approvals for sending. Whether guardians still exist is checked by
     * [isEffective]; approvals survive the removal of the signing guardian while another guardian remains.
     */
    fun verifyStoredApproval(
        selfId: String,
        record: ConsentRecord,
        verifierFor: SignatureVerifierResolver,
    ): ConsentCheck {
        val d = record.decision ?: return ConsentCheck.NOT_APPROVED
        val req = record.request
        if (record.status != ConsentStatus.APPROVED || d.verdict != ConsentVerdict.APPROVED) {
            return ConsentCheck.NOT_APPROVED
        }
        if (!req.wellFormed || req.childId != selfId || d.childId != selfId || d.requestId != req.requestId) {
            return ConsentCheck.ID_MISMATCH
        }
        if (d.guardianId !in req.guardianIds) return ConsentCheck.NOT_A_GUARDIAN
        if (d.requestDigest != ConsentCanonical.requestDigestHex(req)) return ConsentCheck.DIGEST_MISMATCH
        val verifier = verifierFor(d.guardianId) ?: return ConsentCheck.NO_GUARDIAN_KEY
        if (!verifier(ConsentCanonical.decision(d), d.signatureHex)) return ConsentCheck.BAD_SIGNATURE
        return ConsentCheck.OK
    }

    private fun sameSubject(a: GuardianConsentRequest, b: GuardianConsentRequest): Boolean =
        a.subject == b.subject && a.subjectId == b.subjectId && a.childId == b.childId

    /**
     * Main enforcement rule: may [grant] flow data right now?
     *
     * - ADULT: whenever the grant is active.
     * - MINOR: active AND covered by the latest APPROVED terms (all of recipients, fields, plan rights,
     *   scope and sources are subsets), not REVOKED, while the child still has at least one verified guardian.
     *   Approvals survive the removal of one guardian; with the last guardian link gone nothing is effective.
     * - UNKNOWN: like MINOR, but grants whose id is in [grandfatheredIds] keep flowing.
     *
     * Narrowing therefore works offline: a narrower grant is still covered by the old approval.
     * Cryptographic re-verification of the stored decision is done by [verifyStoredApproval] when the
     * approvals are loaded; this function only checks structural integrity. [now] is reserved for
     * time-bound approvals and is currently not used.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isEffective(
        grant: ShareGrant,
        ageState: AgeState,
        links: List<FamilyLink>,
        approvals: List<ConsentRecord>,
        grandfatheredIds: Set<String>,
        now: Long,
    ): Boolean {
        if (!grant.active) return false
        if (ageState == AgeState.ADULT) return true
        if (ageState == AgeState.UNKNOWN && grant.id in grandfatheredIds) return true
        val req = latestApprovedRequest(approvals, ConsentSubject.SHARE_GRANT, grant.id, grant.ownerId) ?: return false
        if (verifiedGuardians(grant.ownerId, links, approvals).isEmpty()) return false
        return req.terms?.let { covers(it, grant.terms()) } ?: false
    }

    /**
     * May the child [childId] take part in circle [circleId] (join / create / keep sending membership messages)?
     * ADULT: always. MINOR/UNKNOWN: only with an APPROVED, unrevoked CIRCLE_MEMBERSHIP approval while the child
     * still has a verified guardian. [now] is reserved, as in [isEffective].
     */
    @Suppress("UNUSED_PARAMETER")
    fun isMembershipAllowed(
        childId: String,
        circleId: String,
        ageState: AgeState,
        links: List<FamilyLink>,
        approvals: List<ConsentRecord>,
        now: Long,
    ): Boolean {
        if (ageState == AgeState.ADULT) return true
        latestApprovedRequest(approvals, ConsentSubject.CIRCLE_MEMBERSHIP, circleId, childId) ?: return false
        return verifiedGuardians(childId, links, approvals).isNotEmpty()
    }

    /**
     * The request of the highest-version decisive (APPROVED or REVOKED) record of the subject, provided it is
     * APPROVED and structurally intact (decision matches request digest and ids, signer was an addressed
     * guardian). Returns `null` otherwise. Does not look at links.
     */
    private fun latestApprovedRequest(
        records: List<ConsentRecord>,
        subject: ConsentSubject,
        subjectId: String,
        childId: String,
    ): GuardianConsentRequest? {
        val latest = records
            .filter {
                it.request.subject == subject && it.request.subjectId == subjectId && it.request.childId == childId &&
                    (it.status == ConsentStatus.APPROVED || it.status == ConsentStatus.REVOKED)
            }
            .maxWithOrNull(
                compareBy<ConsentRecord> { it.request.permissionVersion }
                    .thenBy { if (it.status == ConsentStatus.REVOKED) 1 else 0 },
            ) ?: return null
        if (latest.status != ConsentStatus.APPROVED) return null
        val req = latest.request
        val d = latest.decision ?: return null
        if (!req.wellFormed || d.verdict != ConsentVerdict.APPROVED || d.requestId != req.requestId ||
            d.childId != req.childId || d.guardianId !in req.guardianIds ||
            d.requestDigest != ConsentCanonical.requestDigestHex(req)
        ) {
            return null
        }
        return req
    }

    /** Approved records signed by [guardianId] (e.g. to offer "revoke everything this guardian approved"). */
    fun approvalsSignedBy(guardianId: String, records: List<ConsentRecord>): List<ConsentRecord> =
        records.filter { it.status == ConsentStatus.APPROVED && it.decision?.guardianId == guardianId }
}
