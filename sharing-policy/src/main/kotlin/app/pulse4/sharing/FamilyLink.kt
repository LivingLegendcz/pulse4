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
)

/**
 * Politika rodinných vazeb: potvrzení rodinné vazby nikdy nevytváří `ShareGrant` a u nezletilých
 * vyžaduje aktivace sdílení výslovný souhlas zákonného zástupce.
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

    fun respond(
        link: FamilyLink,
        responderId: String,
        accept: Boolean,
        nowMs: Long,
    ): FamilyLink {
        require(responderId == link.childId) { "Only the child account can respond to a family link request" }
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
