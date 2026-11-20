package app.pulse4.sharing

/** Omezení, která musí aplikace při odvolání popsat přesně a bez slibů navíc. */
enum class RevocationLimit {
    ALREADY_DELIVERED_CANNOT_BE_RECALLED,
    MESSAGES_ON_FOREIGN_NODE_MAY_REMAIN,
    OFFLINE_RECIPIENT_LEARNS_LATER,
}

/** Why a grant was revoked; optional and informational (absent == legacy/owner revocation). */
enum class RevocationReason { OWNER, GUARDIAN_REVOKED, GUARDIAN_LINK_REMOVED, AGE_LOCK }

data class RemovalRequest(val recipient: String, val objectKey: String)

data class RevocationPlan(
    val revokedGrant: ShareGrant,
    /** Čekající položky odchozí fronty tohoto grantu se zruší, než odejdou. */
    val cancelQueuedForGrantId: String,
    /** Požadavek na odstranění už doručených projekcí; příjemcova aplikace jej provede, nelze jej vynutit. */
    val removalRequests: List<RemovalRequest>,
    val limits: Set<RevocationLimit>,
    val reason: RevocationReason? = null,
)

object Revocation {
    fun plan(
        grant: ShareGrant,
        nowMs: Long,
        deliveredObjects: Map<String, List<String>>,
        hasMessagesInPropagationNode: Boolean,
        reason: RevocationReason? = null,
    ): RevocationPlan {
        val revoked = if (grant.active) grant.copy(revokedAtMs = nowMs) else grant
        val removals = deliveredObjects.flatMap { (recipient, keys) -> keys.map { RemovalRequest(recipient, it) } }
        val limits = buildSet {
            if (removals.isNotEmpty()) add(RevocationLimit.ALREADY_DELIVERED_CANNOT_BE_RECALLED)
            if (hasMessagesInPropagationNode) add(RevocationLimit.MESSAGES_ON_FOREIGN_NODE_MAY_REMAIN)
            // After a guardian link removal data may already be out; the UI must say it cannot be recalled.
            if (reason == RevocationReason.GUARDIAN_LINK_REMOVED) add(RevocationLimit.ALREADY_DELIVERED_CANNOT_BE_RECALLED)
            add(RevocationLimit.OFFLINE_RECIPIENT_LEARNS_LATER)
        }
        return RevocationPlan(revoked, grant.id, removals, limits, reason)
    }
}
