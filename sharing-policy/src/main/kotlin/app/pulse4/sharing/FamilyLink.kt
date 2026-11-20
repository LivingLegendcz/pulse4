package app.pulse4.sharing

import kotlinx.serialization.Serializable

@Serializable
enum class FamilyLinkStatus { PENDING, VERIFIED, REJECTED, REVOKED }

@Serializable
enum class AgeProfile { ADULT, MINOR }

@Serializable
data class FamilyLink(
    val id: String,
    val parentId: String,
    val childId: String,
    val status: FamilyLinkStatus,
    val childAgeProfile: AgeProfile = AgeProfile.ADULT,
    val guardianConsentAtMs: Long? = null,
    val createdAtMs: Long,
    val verifiedAtMs: Long? = null,
    val version: Long = 1L,
    /**
     * Who sent the link request (parent or child). `null` on legacy rows == parent-initiated. A link becomes
     * VERIFIED only when the counterparty (the party that did NOT initiate) accepts.
     */
    val initiatorId: String? = null,
) {
    val initiator: String get() = initiatorId ?: parentId
    val counterparty: String get() = if (initiator == parentId) childId else parentId
}

/**
 * Politika rodinných vazeb: potvrzení rodinné vazby nikdy nevytváří `ShareGrant` a u nezletilých
 * vyžaduje aktivace sdílení ověřený podepsaný souhlas zákonného zástupce ([GuardianConsentPolicy]).
 * Zákonný zástupce je jen `parentId` VERIFIED vazby s `childAgeProfile == MINOR`.
 */
object FamilyLinkPolicy {
    fun createRequest(
        id: String,
        parentId: String,
        childId: String,
        childAgeProfile: AgeProfile = AgeProfile.ADULT,
        nowMs: Long,
    ): FamilyLink {
        require(parentId != childId) { "parentId and childId must differ" }
        return FamilyLink(
            id = id,
            parentId = parentId,
            childId = childId,
            status = FamilyLinkStatus.PENDING,
            childAgeProfile = childAgeProfile,
            createdAtMs = nowMs,
        )
    }

    /**
     * Child-initiated request (child -> parent). The child declares itself MINOR (never ADULT) and can only
     * name itself as the child; the parent's device must accept before the link is VERIFIED.
     */
    fun createChildRequest(id: String, parentId: String, childId: String, nowMs: Long): FamilyLink {
        require(parentId != childId) { "parentId and childId must differ" }
        return FamilyLink(
            id = id,
            parentId = parentId,
            childId = childId,
            status = FamilyLinkStatus.PENDING,
            childAgeProfile = AgeProfile.MINOR,
            createdAtMs = nowMs,
            initiatorId = childId,
        )
    }

    /**
     * Receiver side of a family link request: normalizes the wire [payload] using the authenticated
     * [envelopeAuthor] as initiator (never a payload claim). Returns `null` when invalid: self must be the other
     * party, the author must be parent or child, and a child-initiated link must declare the child MINOR.
     */
    fun acceptIncomingRequest(payload: FamilyLink, selfId: String, envelopeAuthor: String): FamilyLink? {
        if (payload.parentId == payload.childId) return null
        val parties = setOf(payload.parentId, payload.childId)
        if (selfId !in parties || envelopeAuthor !in parties || selfId == envelopeAuthor) return null
        if (envelopeAuthor == payload.childId && payload.childAgeProfile != AgeProfile.MINOR) return null
        return payload.copy(
            status = FamilyLinkStatus.PENDING,
            verifiedAtMs = null,
            guardianConsentAtMs = null,
            initiatorId = envelopeAuthor,
        )
    }

    /** An inbound response is valid only from the counterparty of the initiator. */
    fun isValidRemoteResponse(link: FamilyLink, envelopeAuthor: String): Boolean =
        link.status == FamilyLinkStatus.PENDING && envelopeAuthor == link.counterparty

    /**
     * "Onboarding complete": an ADULT always; a MINOR as soon as ONE verified guardian exists (further guardians
     * are optional); UNKNOWN never (the age choice is still outstanding). [records] are the child's consent
     * records (needed to qualify guardians beyond the first one).
     */
    fun isOnboardingComplete(
        ageState: AgeState,
        selfId: String,
        links: List<FamilyLink>,
        records: List<ConsentRecord> = emptyList(),
    ): Boolean = when (ageState) {
        AgeState.ADULT -> true
        AgeState.UNKNOWN -> false
        AgeState.MINOR -> GuardianConsentPolicy.verifiedGuardians(selfId, links, records).isNotEmpty()
    }

    fun respond(
        link: FamilyLink,
        responderId: String,
        accept: Boolean,
        nowMs: Long,
    ): FamilyLink {
        require(responderId == link.counterparty) { "Only the counterparty of the initiator can respond to a family link request" }
        require(link.status == FamilyLinkStatus.PENDING) { "Family link must be PENDING" }
        return link.copy(
            status = if (accept) FamilyLinkStatus.VERIFIED else FamilyLinkStatus.REJECTED,
            verifiedAtMs = if (accept) nowMs else null,
            version = link.version + 1,
        )
    }

    fun revoke(link: FamilyLink, actorId: String): FamilyLink {
        require(actorId == link.parentId || actorId == link.childId) { "Only parent or child can revoke" }
        return link.copy(
            status = FamilyLinkStatus.REVOKED,
            version = link.version + 1,
        )
    }

    /**
     * Approvals-aware activation check: may [grant] flow data for [ownerId] in the given [ageState]?
     * Delegates to [GuardianConsentPolicy.isEffective]; a legacy checkbox timestamp is never evidence.
     */
    fun canActivateGrant(
        ownerId: String,
        ageState: AgeState,
        grant: ShareGrant,
        links: List<FamilyLink>,
        approvals: List<ConsentRecord>,
        grandfatheredIds: Set<String>,
        now: Long,
    ): Boolean =
        grant.ownerId == ownerId &&
            GuardianConsentPolicy.isEffective(grant, ageState, links, approvals, grandfatheredIds, now)

    /**
     * Link removal. Allowed for either party of the link and, for a guardian link, also for any OTHER verified
     * guardian of the same child ([links] / [records] are needed to establish that). Idempotent for an already
     * REVOKED link; REJECTED links cannot be removed.
     */
    fun removeLink(
        link: FamilyLink,
        actorId: String,
        links: List<FamilyLink> = emptyList(),
        records: List<ConsentRecord> = emptyList(),
    ): FamilyLink {
        require(mayRemove(link, actorId, links, records)) { "Actor may not remove this link" }
        if (link.status == FamilyLinkStatus.REVOKED) return link
        require(link.status == FamilyLinkStatus.VERIFIED || link.status == FamilyLinkStatus.PENDING) {
            "Only a PENDING or VERIFIED link can be removed"
        }
        return link.copy(status = FamilyLinkStatus.REVOKED, version = link.version + 1)
    }

    private fun mayRemove(link: FamilyLink, actorId: String, links: List<FamilyLink>, records: List<ConsentRecord>) =
        actorId == link.parentId || actorId == link.childId ||
            actorId in GuardianConsentPolicy.verifiedGuardians(link.childId, links, records)

    /** An inbound `family.link.revoke` is valid only from the other party of the link; [selfId] is a party. */
    fun isValidRemoteRevoke(link: FamilyLink, envelopeAuthor: String, selfId: String): Boolean =
        (link.parentId == selfId && link.childId == envelopeAuthor) ||
            (link.childId == selfId && link.parentId == envelopeAuthor)

    /**
     * Child side of an inbound `family.link.revoke`: valid from either party of [link] or from any other verified
     * guardian of the child (a guardian may remove another guardian, e.g. a compromised or fake one).
     */
    fun isValidLinkRemoval(
        link: FamilyLink,
        envelopeAuthor: String,
        links: List<FamilyLink>,
        records: List<ConsentRecord>,
    ): Boolean = mayRemove(link, envelopeAuthor, links, records)

    /**
     * Consent rows to mark withdrawn after [removed] was removed ([linksAfter] already contains it as REVOKED):
     * the removed guardian's `GUARDIAN_ADD` rows (so it cannot come back qualified), and, only when no verified
     * guardian is left, every PENDING/APPROVED row of the child. While another guardian remains, other approvals
     * stay valid.
     */
    fun consentsAffectedByLinkRemoval(
        removed: FamilyLink,
        linksAfter: List<FamilyLink>,
        records: List<ConsentRecord>,
    ): List<ConsentRecord> {
        val child = removed.childId
        val noGuardianLeft = GuardianConsentPolicy.verifiedGuardians(child, linksAfter, records).isEmpty()
        return records.filter {
            it.request.childId == child &&
                (it.status == ConsentStatus.PENDING || it.status == ConsentStatus.APPROVED) &&
                (
                    noGuardianLeft ||
                        (it.request.subject == ConsentSubject.GUARDIAN_ADD && it.request.subjectId == removed.parentId)
                    )
        }
    }

    /**
     * Legacy checkbox-based check, kept only so existing callers compile until they are rewired to
     * [canActivateGrant]. A co-consent timestamp is NOT evidence of guardian approval.
     */
    fun canActivateShareGrant(
        ownerId: String,
        ownerAgeProfile: AgeProfile,
        grant: ShareGrant,
        verifiedLinks: List<FamilyLink>,
        guardianCoConsentAtMs: Long?,
        coConsentGuardianId: String? = null,
    ): Boolean {
        if (!grant.active || grant.ownerId != ownerId) return false
        if (ownerAgeProfile == AgeProfile.ADULT) return true
        val consentTs = guardianCoConsentAtMs ?: return false
        if (consentTs <= 0L) return false
        val hasVerifiedLink = verifiedLinks.any {
            it.status == FamilyLinkStatus.VERIFIED &&
                it.childId == ownerId &&
                (coConsentGuardianId == null || it.parentId == coConsentGuardianId)
        }
        return hasVerifiedLink
    }
}
