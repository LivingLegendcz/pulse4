package app.pulse4.withings

/**
 * Pure profile-role logic for a Withings account that holds several profiles.
 *
 * Withings shares the data of all adult profiles on one account, so a second adult must never be shown,
 * selectable or bound as a data source. A profile is treated as a child only when that is *provable*:
 * everything else (including adults whose email/birthdate the API omits) is "not a child".
 */
object WithingsProfiles {
    const val ADULT_AGE_YEARS = 18.0

    /**
     * Provable child:
     *  - never the account's primary profile and never a profile with its own e-mail (own account => adult);
     *  - known birthdate => child iff younger than [ADULT_AGE_YEARS];
     *  - unknown birthdate => child only if its parent-account link points to ANOTHER profile in [all]
     *    (a bare shared account id is not enough).
     */
    fun isChild(
        p: WithingsUserProfile,
        all: List<WithingsUserProfile>,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): Boolean {
        if (p.isPrimary) return false
        if (!p.email.isNullOrBlank()) return false
        p.ageYears(nowSec)?.let { return it < ADULT_AGE_YEARS }
        val parentId = p.parentAccountId ?: return false
        return parentId != p.id && all.any { it.id == parentId }
    }

    /** Child profiles that may ever be shown or bound (excludes [primaryId]). */
    fun children(
        all: List<WithingsUserProfile>,
        primaryId: Long,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): List<WithingsUserProfile> = all.filter { it.id != primaryId && isChild(it, all, nowSec) }

    /** Children whose parent-account link points directly at [primaryId]. */
    fun childrenUnderThis(
        all: List<WithingsUserProfile>,
        primaryId: Long,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): List<WithingsUserProfile> = children(all, primaryId, nowSec).filter { it.parentAccountId == primaryId }

    /** Parent (non-child) profile of [child], or `null` when unknown. */
    fun findParent(
        child: WithingsUserProfile,
        all: List<WithingsUserProfile>,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): WithingsUserProfile? {
        val pid = child.parentAccountId ?: return null
        return all.firstOrNull { it.id == pid && !isChild(it, all, nowSec) }
    }

    /**
     * Automatic child binding: prefers a child listed under [primaryId], otherwise any provable child of the
     * account (adult data is shared, so a child under the other adult is still this account's child).
     */
    fun autoChild(
        all: List<WithingsUserProfile>,
        primaryId: Long,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): WithingsUserProfile? =
        childrenUnderThis(all, primaryId, nowSec).firstOrNull() ?: children(all, primaryId, nowSec).firstOrNull()

    /**
     * Child to store in the session. Order: explicit [requestedChildId] (only if it is a provable child),
     * then the [currentChildId] (kept, never wiped by a "no preference" call), then [autoChild].
     * A non-child id (e.g. the second adult) is silently dropped, including a stale persisted [currentChildId].
     */
    fun resolveChild(
        all: List<WithingsUserProfile>,
        primaryId: Long,
        requestedChildId: Long? = null,
        currentChildId: Long? = null,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): WithingsUserProfile? {
        val candidates = children(all, primaryId, nowSec)
        requestedChildId?.let { id -> candidates.firstOrNull { it.id == id }?.let { return it } }
        currentChildId?.let { id -> candidates.firstOrNull { it.id == id }?.let { return it } }
        return autoChild(all, primaryId, nowSec)
    }

    /** The child profile the session really points at (validated against its own profile list), else `null`. */
    fun sessionChild(
        s: WithingsWebSession?,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): WithingsUserProfile? {
        if (s == null) return null
        val id = s.childUserId ?: return null
        return children(s.allProfiles, s.primaryUserId, nowSec).firstOrNull { it.id == id }
    }
}
