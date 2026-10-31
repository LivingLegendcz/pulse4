package app.pulse4.training

import app.pulse4.sharing.PlanRight
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ScheduledWorkout(val id: String, val workout: Workout, val athleteId: String, val date: String)

/** Neměnná verze plánu. Každá změna vytváří novou; stará se nikdy nepřepisuje. */
@Serializable
data class PlanRevision(
    val planId: String,
    val ownerId: String,
    val revision: Long,
    val parent: Long?,
    val authorId: String,
    val createdAtMs: Long,
    val workouts: List<ScheduledWorkout>,
    /** ID jednotek, kterých se tato revize dotkla (pro odhalení překryvu při konfliktu). */
    val touchedIds: Set<String> = emptySet(),
) {
    val contentHash: String get() = MessageDigest.getInstance("SHA-256")
        .digest(workouts.sortedBy { it.id }.joinToString("|") { "${it.id}:${it.date}:${it.workout.signature()}" }.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

@Serializable
sealed interface PlanOp {
    val targetId: String
    @Serializable @SerialName("add") data class Add(val workout: ScheduledWorkout) : PlanOp { override val targetId get() = workout.id }
    @Serializable @SerialName("remove") data class Remove(override val targetId: String) : PlanOp
    @Serializable @SerialName("replace") data class Replace(val workout: ScheduledWorkout) : PlanOp { override val targetId get() = workout.id }
}

@Serializable
data class ChangeProposal(
    val id: String,
    val planId: String,
    val baseRevision: Long,
    val authorId: String,
    val ops: List<PlanOp>,
    val createdAtMs: Long,
    val note: String? = null,
)

@Serializable
enum class ProposalStatus { PENDING, ACCEPTED, REJECTED, CONFLICT }

@Serializable
data class ConflictDiff(
    val headRevision: Long,
    val touchedByProposal: Set<String>,
    val touchedSinceBase: Set<String>,
) {
    val overlap: Set<String> get() = touchedByProposal intersect touchedSinceBase
}

@Serializable
data class ProposalDecision(
    val proposalId: String,
    val planId: String,
    val status: ProposalStatus,
    val newRevision: Long? = null,
    val diff: ConflictDiff? = null,
    val reason: String? = null,
)

sealed interface SubmitResult {
    data class Accepted(val revision: PlanRevision) : SubmitResult
    data class Pending(val proposalId: String) : SubmitResult
    data class Conflict(val proposalId: String, val diff: ConflictDiff) : SubmitResult
    data class Rejected(val reason: String) : SubmitResult
}

/** Stav plánu u vlastníka (autority). */
interface PlanStore {
    fun head(planId: String): PlanRevision?
    fun revisions(planId: String): List<PlanRevision>
    fun saveRevision(r: PlanRevision)
    fun saveProposal(p: ChangeProposal, status: ProposalStatus)
    fun proposal(id: String): Pair<ChangeProposal, ProposalStatus>?
    /** Aktuální oprávnění autora u vlastníka (z neodvolaných grantů); `null` = žádné. */
    fun rightOf(planId: String, authorId: String): PlanRight?
}

/**
 * Serializace změn u vlastníka plánu, nikoli složitý distribuovaný editor: vlastník potvrzuje výslednou verzi.
 * Oprávnění se posuzuje v okamžiku zpracování – odvolané právo se starou offline zprávou neobnoví.
 */
class PlanService(private val store: PlanStore, private val caps: DeviceCapabilities = DeviceCapabilities.default) {

    fun submit(p: ChangeProposal, nowMs: Long): SubmitResult {
        val head = store.head(p.planId) ?: return SubmitResult.Rejected("Neznámý plán")
        val right = if (p.authorId == head.ownerId) PlanRight.EDIT else store.rightOf(p.planId, p.authorId)
        if (right == null || right == PlanRight.VIEW || right == PlanRight.COPY) return SubmitResult.Rejected("Autor nemá právo měnit plán")
        if (p.ops.isEmpty()) return SubmitResult.Rejected("Návrh neobsahuje žádnou změnu")
        // Struktura návrhu se kontroluje vůči jeho výchozí revizi; validita nových jednotek vždy.
        val problems = p.ops.filterIsInstance<PlanOp.Add>().map { it.workout } .plus(p.ops.filterIsInstance<PlanOp.Replace>().map { it.workout })
            .flatMap { WorkoutValidator.validate(it.workout, caps) }
        if (problems.isNotEmpty()) return SubmitResult.Rejected("Neplatný trénink: ${problems.first().message}")

        if (right == PlanRight.PROPOSE) { store.saveProposal(p, ProposalStatus.PENDING); return SubmitResult.Pending(p.id) }
        // EDIT: automaticky jen z aktuální verze; jinak viditelný konflikt, nikdy tiché přepsání.
        if (p.baseRevision != head.revision) return conflict(p, head)
        return when (val r = apply(head, p, nowMs)) {
            is Applied.Ok -> { store.saveRevision(r.revision); store.saveProposal(p, ProposalStatus.ACCEPTED); SubmitResult.Accepted(r.revision) }
            is Applied.Fail -> SubmitResult.Rejected(r.why)
        }
    }

    /** Vlastník rozhoduje o čekajícím návrhu. Návrh z jiné než aktuální verze se nikdy neaplikuje tiše. */
    fun decide(proposalId: String, accept: Boolean, nowMs: Long, ownerId: String): SubmitResult {
        val (p, status) = store.proposal(proposalId) ?: return SubmitResult.Rejected("Neznámý návrh")
        if (status != ProposalStatus.PENDING && status != ProposalStatus.CONFLICT) return SubmitResult.Rejected("Návrh už byl vyřízen")
        val head = store.head(p.planId) ?: return SubmitResult.Rejected("Neznámý plán")
        if (head.ownerId != ownerId) return SubmitResult.Rejected("Rozhodovat smí jen vlastník plánu")
        if (!accept) { store.saveProposal(p, ProposalStatus.REJECTED); return SubmitResult.Rejected("Zamítnuto vlastníkem") }
        if (p.baseRevision != head.revision) {
            val diff = diffOf(p, head)
            // Bez překryvu lze návrh bezpečně přenést na aktuální verzi, ale jen na výslovný pokyn vlastníka.
            if (diff.overlap.isNotEmpty()) { store.saveProposal(p, ProposalStatus.CONFLICT); return SubmitResult.Conflict(p.id, diff) }
        }
        return when (val r = apply(head, p, nowMs, ownerId)) {
            is Applied.Ok -> { store.saveRevision(r.revision); store.saveProposal(p, ProposalStatus.ACCEPTED); SubmitResult.Accepted(r.revision) }
            is Applied.Fail -> SubmitResult.Rejected(r.why)
        }
    }

    fun diffOf(p: ChangeProposal, head: PlanRevision): ConflictDiff {
        val since = store.revisions(p.planId).filter { it.revision > p.baseRevision }.flatMap { it.touchedIds }.toSet()
        return ConflictDiff(head.revision, p.ops.map { it.targetId }.toSet(), since)
    }

    private fun conflict(p: ChangeProposal, head: PlanRevision): SubmitResult.Conflict {
        store.saveProposal(p, ProposalStatus.CONFLICT)
        return SubmitResult.Conflict(p.id, diffOf(p, head))
    }

    private sealed interface Applied {
        data class Ok(val revision: PlanRevision) : Applied
        data class Fail(val why: String) : Applied
    }

    private fun apply(head: PlanRevision, p: ChangeProposal, nowMs: Long, authorOverride: String? = null): Applied {
        val byId = LinkedHashMap(head.workouts.associateBy { it.id })
        for (op in p.ops) when (op) {
            is PlanOp.Add -> { if (op.workout.id in byId) return Applied.Fail("Jednotka ${op.workout.id} už existuje"); byId[op.workout.id] = op.workout }
            is PlanOp.Replace -> { if (op.workout.id !in byId) return Applied.Fail("Jednotka ${op.workout.id} neexistuje"); byId[op.workout.id] = op.workout }
            is PlanOp.Remove -> if (byId.remove(op.targetId) == null) return Applied.Fail("Jednotka ${op.targetId} neexistuje")
        }
        return Applied.Ok(PlanRevision(
            planId = head.planId, ownerId = head.ownerId, revision = head.revision + 1, parent = head.revision,
            authorId = authorOverride ?: p.authorId, createdAtMs = nowMs, workouts = byId.values.toList(),
            touchedIds = p.ops.map { it.targetId }.toSet(),
        ))
    }
}

class InMemoryPlanStore(initial: PlanRevision, private val rights: MutableMap<Pair<String, String>, PlanRight> = mutableMapOf()) : PlanStore {
    private val revs = mutableListOf(initial)
    private val proposals = LinkedHashMap<String, Pair<ChangeProposal, ProposalStatus>>()
    override fun head(planId: String) = revs.lastOrNull { it.planId == planId }
    override fun revisions(planId: String) = revs.filter { it.planId == planId }
    override fun saveRevision(r: PlanRevision) { revs += r }
    override fun saveProposal(p: ChangeProposal, status: ProposalStatus) { proposals[p.id] = p to status }
    override fun proposal(id: String) = proposals[id]
    override fun rightOf(planId: String, authorId: String) = rights[planId to authorId]
    fun grant(planId: String, who: String, r: PlanRight) { rights[planId to who] = r }
    fun revoke(planId: String, who: String) { rights.remove(planId to who) }
}
