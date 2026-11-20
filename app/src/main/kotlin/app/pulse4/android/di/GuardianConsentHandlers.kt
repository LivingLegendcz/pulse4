package app.pulse4.android.di

import app.pulse4.sharing.AgeState
import app.pulse4.sharing.ConsentVerdict
import app.pulse4.sharing.Group
import app.pulse4.sync.BuiltInHandlers
import app.pulse4.sync.HandlerResult
import app.pulse4.sync.KindHandler
import app.pulse4.sync.Kinds
import app.pulse4.sync.ReceiverStore

/**
 * Inbound handlers of the guardian-consent protocol plus the (generalized, either direction) family-link request /
 * response handlers and the age-aware membership handler. They run inside the receive transaction (all state
 * transitions and the grant upsert of one message commit together) and never touch the network. The receiving
 * identity is always `env.recipient` (the Receiver has already checked it equals the local id).
 */
internal fun buildGuardianConsentHandlers(shareService: ShareService): Map<String, KindHandler> {
    val c = shareService.consent
    return mapOf(
        Kinds.GUARDIAN_CONSENT_REQUEST to KindHandler { env, payload, _, nowMs ->
            c.onIncomingRequest(env.recipient, env.author, payload, nowMs)
        },
        Kinds.GUARDIAN_CONSENT_APPROVED to KindHandler { env, payload, _, nowMs ->
            c.onDecision(env.recipient, env.author, payload, ConsentVerdict.APPROVED, nowMs)
        },
        Kinds.GUARDIAN_CONSENT_REJECTED to KindHandler { env, payload, _, nowMs ->
            c.onDecision(env.recipient, env.author, payload, ConsentVerdict.REJECTED, nowMs)
        },
        Kinds.GUARDIAN_CONSENT_REVOKED to KindHandler { env, payload, _, nowMs ->
            c.onRevocation(env.recipient, env.author, payload, nowMs)
        },
        Kinds.GUARDIAN_AGE_RELEASE to KindHandler { env, payload, _, nowMs ->
            c.onAgeRelease(env.recipient, env.author, payload, nowMs)
        },
        Kinds.FAMILY_LINK_REQUEST to KindHandler { env, payload, _, nowMs ->
            c.onFamilyLinkRequest(env.recipient, env.author, payload, nowMs)
        },
        Kinds.FAMILY_LINK_RESPONSE to KindHandler { env, payload, _, nowMs ->
            c.onFamilyLinkResponse(env.recipient, env.author, payload, nowMs)
        },
        Kinds.FAMILY_LINK_REVOKE to KindHandler { env, payload, _, nowMs ->
            c.onFamilyLinkRevoke(env.recipient, env.author, payload, nowMs)
        },
        // A non-ADULT device never becomes a circle member without a guardian approval: the base handler still
        // validates authority, but a group that would include this device is parked as an invitation instead.
        Kinds.MEMBERSHIP_UPDATE to KindHandler { env, payload, store, nowMs ->
            if (c.age.state == AgeState.ADULT) {
                return@KindHandler BuiltInHandlers.membershipUpdate.handle(env, payload, store, nowMs)
            }
            var captured: Group? = null
            val capturing = object : ReceiverStore by store {
                override fun putGroup(group: Group) {
                    captured = group
                }
            }
            val result = BuiltInHandlers.membershipUpdate.handle(env, payload, capturing, nowMs)
            val g = captured
            if (result == HandlerResult.Applied && g != null) {
                if (env.recipient in g.members && !c.isMembershipAllowed(env.recipient, g.id, nowMs)) {
                    c.parkCircleInvite(env.recipient, env.author, g, payload, nowMs)
                } else {
                    store.putGroup(g)
                }
            }
            result
        },
    )
}
