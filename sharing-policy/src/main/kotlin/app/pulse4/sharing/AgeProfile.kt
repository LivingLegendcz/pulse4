package app.pulse4.sharing

import kotlinx.serialization.Serializable

/** Age declaration of the local user. UNKNOWN is the state before the first declaration/migration. */
@Serializable
enum class AgeState { UNKNOWN, ADULT, MINOR }

@Serializable
enum class AgeSource { DECLARED, LINK_ACCEPTED, GUARDIAN_RELEASE, MIGRATION }

/** Persisted age state (settings key `ageProfile.v2`). */
@Serializable
data class AgeProfileRecord(
    val state: AgeState = AgeState.UNKNOWN,
    val source: AgeSource? = null,
    val atMs: Long = 0L,
    /** Guardian that locked the profile to MINOR (via a verified link); only that guardian can release it. */
    val lockedByGuardianId: String? = null,
)

sealed interface ReleaseOutcome {
    data class Released(val record: AgeProfileRecord) : ReleaseOutcome
    data class Rejected(val reason: ReleaseRejection) : ReleaseOutcome
}

enum class ReleaseRejection {
    NOT_MINOR,
    NOT_A_GUARDIAN,
    UNSUPPORTED_SCHEMA,
    AUTHOR_MISMATCH,
    ID_MISMATCH,
    LINK_INVALID,
    EXPIRED,
    INVALID_VALIDITY,
    NONCE_REUSED,
    BAD_NONCE,
    NO_GUARDIAN_KEY,
    BAD_SIGNATURE,
}

/**
 * Age lock transitions. UNKNOWN -> ADULT/MINOR and ADULT -> MINOR are free; MINOR -> ADULT is only possible
 * with a verified [GuardianAgeRelease] signed by ANY currently verified guardian of the child. Nothing ever
 * transitions back to UNKNOWN.
 */
object AgeProfilePolicy {
    const val MAX_RELEASE_TTL_MS: Long = 7L * 24 * 3600 * 1000
    private const val NONCE_HEX_LEN = 32
    private val HEX = Regex("^[0-9a-f]+$")

    /** User declaration. Returns the new record, or `null` when the transition is not allowed (MINOR -> ADULT, -> UNKNOWN). */
    fun declare(current: AgeProfileRecord, target: AgeState, nowMs: Long): AgeProfileRecord? = when {
        target == AgeState.UNKNOWN -> null
        current.state == target -> current
        current.state == AgeState.UNKNOWN -> AgeProfileRecord(target, AgeSource.DECLARED, nowMs)
        current.state == AgeState.ADULT && target == AgeState.MINOR -> AgeProfileRecord(AgeState.MINOR, AgeSource.DECLARED, nowMs)
        else -> null // MINOR -> ADULT
    }

    /**
     * Accepting a family link whose child is flagged MINOR ratchets the local profile to MINOR and records
     * the guardian. Returns [current] unchanged when the link does not apply.
     */
    fun onLinkAccepted(current: AgeProfileRecord, link: FamilyLink, selfId: String, nowMs: Long): AgeProfileRecord {
        if (!GuardianConsentPolicy.isGuardianLink(link, selfId)) return current
        if (current.state == AgeState.MINOR) {
            return if (current.lockedByGuardianId == null) current.copy(lockedByGuardianId = link.parentId) else current
        }
        return AgeProfileRecord(AgeState.MINOR, AgeSource.LINK_ACCEPTED, nowMs, link.parentId)
    }

    /**
     * Applies a guardian age release. [usedNonces] are the release nonces already consumed, [records] the
     * child's consent records (to qualify guardians). Succeeds only for a MINOR profile and a release signed by
     * a currently verified guardian, through that guardian's own link.
     */
    fun applyRelease(
        current: AgeProfileRecord,
        selfId: String,
        envelopeAuthor: String,
        release: GuardianAgeRelease,
        links: List<FamilyLink>,
        records: List<ConsentRecord>,
        usedNonces: Set<String>,
        nowMs: Long,
        verifierFor: SignatureVerifierResolver,
    ): ReleaseOutcome {
        fun reject(r: ReleaseRejection) = ReleaseOutcome.Rejected(r)
        if (current.state != AgeState.MINOR) return reject(ReleaseRejection.NOT_MINOR)
        if (release.schema != GuardianConsentPolicy.SCHEMA) return reject(ReleaseRejection.UNSUPPORTED_SCHEMA)
        if (envelopeAuthor != release.guardianId) return reject(ReleaseRejection.AUTHOR_MISMATCH)
        if (release.childId != selfId) return reject(ReleaseRejection.ID_MISMATCH)
        if (release.guardianId !in GuardianConsentPolicy.verifiedGuardians(selfId, links, records)) {
            return reject(ReleaseRejection.NOT_A_GUARDIAN)
        }
        // The release must reference the signer's own verified guardian link.
        val link = links.firstOrNull { it.id == release.linkId }
        if (link == null || link.parentId != release.guardianId || !GuardianConsentPolicy.isGuardianLink(link, selfId)) {
            return reject(ReleaseRejection.LINK_INVALID)
        }
        if (release.nonce.length != NONCE_HEX_LEN || !HEX.matches(release.nonce)) return reject(ReleaseRejection.BAD_NONCE)
        if (release.issuedAtMs > release.expiresAtMs || release.expiresAtMs - release.issuedAtMs > MAX_RELEASE_TTL_MS) {
            return reject(ReleaseRejection.INVALID_VALIDITY)
        }
        if (nowMs > release.expiresAtMs) return reject(ReleaseRejection.EXPIRED)
        if (release.nonce in usedNonces) return reject(ReleaseRejection.NONCE_REUSED)
        val verifier = verifierFor(release.guardianId) ?: return reject(ReleaseRejection.NO_GUARDIAN_KEY)
        if (!verifier(ConsentCanonical.ageRelease(release), release.signatureHex)) return reject(ReleaseRejection.BAD_SIGNATURE)
        return ReleaseOutcome.Released(AgeProfileRecord(AgeState.ADULT, AgeSource.GUARDIAN_RELEASE, nowMs))
    }
}
