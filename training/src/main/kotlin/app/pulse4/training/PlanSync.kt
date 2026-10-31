package app.pulse4.training

import app.pulse4.sync.Envelope
import app.pulse4.sync.HandlerResult
import app.pulse4.sync.KindHandler
import app.pulse4.sync.Kinds
import app.pulse4.sync.RejectReason
import app.pulse4.sync.Wire

/** Rozhodnutí, která má aplikace po commitu transakce odeslat zpět (PLAN_DECISION / PLAN_REVISION). */
data class PlanNotification(val toIdentity: String, val decision: ProposalDecision, val revision: PlanRevision?)

/**
 * Napojení plánů na příjem zpráv.
 *  - u vlastníka: PLAN_PROPOSAL → [PlanService.submit]
 *  - u spolupracovníka: PLAN_REVISION / PLAN_DECISION → místní replika
 * Handler běží uvnitř transakce a nic neodesílá; výsledky odevzdá v [drainNotifications].
 */
class PlanSyncHandlers(private val service: PlanService) {
    private val pending = mutableListOf<PlanNotification>()

    fun drainNotifications(): List<PlanNotification> = pending.toList().also { pending.clear() }

    val proposal = KindHandler { env, payload, _, nowMs ->
        val p = runCatching { Wire.json.decodeFromString<ChangeProposal>(payload) }.getOrNull()
            ?: return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
        // Autor návrhu musí být ověřený odesílatel; cizí jménem nikdo navrhovat nemůže.
        if (p.authorId != env.author) return@KindHandler HandlerResult.Rejected(RejectReason.AUTHOR_MISMATCH)
        when (val r = service.submit(p, nowMs)) {
            is SubmitResult.Rejected -> HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
            is SubmitResult.Accepted -> { notify(env, ProposalDecision(p.id, p.planId, ProposalStatus.ACCEPTED, r.revision.revision), r.revision); HandlerResult.Applied }
            is SubmitResult.Pending -> { notify(env, ProposalDecision(p.id, p.planId, ProposalStatus.PENDING), null); HandlerResult.Applied }
            is SubmitResult.Conflict -> { notify(env, ProposalDecision(p.id, p.planId, ProposalStatus.CONFLICT, diff = r.diff), null); HandlerResult.Applied }
        }
    }

    private fun notify(env: Envelope, d: ProposalDecision, rev: PlanRevision?) { pending += PlanNotification(env.author, d, rev) }

    /** Replika plánu u příjemce: revize je úplný stav, starší verze nikdy nevrací zpět. Autorem musí být vlastník plánu. */
    val revision = KindHandler { env, payload, store, _ ->
        val rev = runCatching { Wire.json.decodeFromString<PlanRevision>(payload) }.getOrNull()
            ?: return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
        if (rev.ownerId != env.author) return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
        val cur = store.objectVersion(env.author, "plan", rev.planId)
        if (cur != null && rev.revision <= cur) return@KindHandler HandlerResult.Stale
        store.putObject(env.author, "plan", rev.planId, rev.revision, payload)
        HandlerResult.Applied
    }

    /** Odpověď vlastníka na náš návrh: uložíme ji jako objekt, UI ji zobrazí jako „čeká na přijetí“ / „přijato“ / „konflikt“. */
    val decision = KindHandler { env, payload, store, _ ->
        val d = runCatching { Wire.json.decodeFromString<ProposalDecision>(payload) }.getOrNull()
            ?: return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
        val cur = store.objectVersion(env.author, "decision", d.proposalId)
        if (cur != null && env.objectVersion <= cur) return@KindHandler HandlerResult.Stale
        store.putObject(env.author, "decision", d.proposalId, env.objectVersion, payload)
        HandlerResult.Applied
    }

    fun handlers(): Map<String, KindHandler> = mapOf(
        Kinds.PLAN_PROPOSAL to proposal, Kinds.PLAN_REVISION to revision, Kinds.PLAN_DECISION to decision,
    )
}
