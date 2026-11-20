package app.pulse4.android.di

import app.pulse4.sharing.ConsentStatus
import app.pulse4.sharing.ConsentSubject
import app.pulse4.sharing.GuardianConsentRequest
import app.pulse4.sharing.GuardianDecision
import app.pulse4.sharing.ShareGrant

/** Which side of a consent exchange a stored row belongs to. */
enum class ConsentRole { CHILD_OUT, GUARDIAN_IN }

/** A stored consent row decoded for the UI layer. */
data class ConsentItem(
    val requestId: String,
    val role: ConsentRole,
    val subject: ConsentSubject,
    /** Grant id, circle id or the id of the proposed new guardian, depending on [subject]. */
    val subjectId: String,
    val permissionVersion: Long,
    val status: ConsentStatus,
    val request: GuardianConsentRequest,
    /** The grant a SHARE_GRANT request asks to activate (it is only stored as a grant once approved). */
    val proposedGrant: ShareGrant?,
    val decision: GuardianDecision?,
    /** GUARDIAN_IN only: the guardian's own link to the child. */
    val linkId: String?,
    /** GUARDIAN_IN: the child; CHILD_OUT: comma separated addressed guardians. */
    val peerId: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val decidedAtMs: Long?,
) {
    val permissionId: String get() = request.permissionId
}

/** Consent state of one grant / circle as the child's UI should present it. */
enum class ConsentUiState {
    /** ADULT: no guardian approval involved. */
    NOT_REQUIRED,
    /** UNKNOWN age + migrated grant that keeps flowing until the age is confirmed. */
    GRANDFATHERED,
    /** Not effective and no live request: offer "send for guardian approval". */
    NEEDS_APPROVAL,
    WAITING,
    APPROVED,
    REJECTED,
    EXPIRED,
    REVOKED,
}

/** Gate state of the whole app for the local user. */
enum class OnboardingState {
    /** Age not declared yet: ask "child or adult". */
    UNKNOWN,
    ADULT,
    /** MINOR without any verified guardian: the app is blocked. */
    MINOR_WAITING_FOR_GUARDIAN,
    MINOR_READY,
}

/** A circle invitation parked on a non-ADULT device until a guardian approves the membership. */
data class CircleInvite(val group: app.pulse4.sharing.Group, val fromId: String)

/** The grant change was NOT applied; a guardian request is (still) pending for it. */
class ConsentPendingException(val request: GuardianConsentRequest) :
    IllegalStateException("Žádost odeslána rodiči ke schválení. Sdílení se změní až po jeho souhlasu.")

/** UNKNOWN age: new or widened sharing is blocked until the age is confirmed. */
class AgeNotConfirmedException :
    IllegalStateException("Nejdřív potvrďte svůj věk. Do té doby nelze sdílení rozšiřovat.")

/** A guardian approval is needed but the child has no verified guardian. */
class NoGuardianException :
    IllegalStateException("Pro nezletilý účet je nutný ověřený zákonný zástupce.")
