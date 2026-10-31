package app.pulse4.training

import app.pulse4.domain.Sport
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface DurationSpec {
    @Serializable @SerialName("time") data class Time(val seconds: Int) : DurationSpec
    @Serializable @SerialName("distance") data class Distance(val meters: Int) : DurationSpec
    /** Ruční ukončení tlačítkem kola. */
    @Serializable @SerialName("lap") data object LapButton : DurationSpec
}

@Serializable
sealed interface TargetSpec {
    @Serializable @SerialName("none") data object None : TargetSpec
    @Serializable @SerialName("pace") data class Pace(val minSecPerKm: Int, val maxSecPerKm: Int) : TargetSpec
    @Serializable @SerialName("hr") data class HeartRate(val minBpm: Int, val maxBpm: Int) : TargetSpec
    @Serializable @SerialName("power") data class Power(val minW: Int, val maxW: Int) : TargetSpec
    @Serializable @SerialName("cadence") data class Cadence(val min: Int, val max: Int) : TargetSpec
}

@Serializable
enum class StepType { WARMUP, WORK, RECOVERY, REST, COOLDOWN }

@Serializable
sealed interface WorkoutNode {
    @Serializable @SerialName("step")
    data class Step(val stepType: StepType, val duration: DurationSpec, val target: TargetSpec = TargetSpec.None) : WorkoutNode

    @Serializable @SerialName("repeat")
    data class Repeat(val count: Int, val steps: List<Step>) : WorkoutNode
}

@Serializable
data class Workout(
    val id: String,
    val name: String,
    val sport: Sport,
    val nodes: List<WorkoutNode>,
    val ownerId: String,
) {
    /** Ploché kroky tak, jak je uvidí hodinky (opakování rozbalené). */
    fun flatSteps(): List<WorkoutNode.Step> = nodes.flatMap {
        when (it) {
            is WorkoutNode.Step -> listOf(it)
            is WorkoutNode.Repeat -> List(it.count) { _ -> it.steps }.flatten()
        }
    }

    /** Podpis struktury bez názvu; slouží k odhalení změny provedené mimo aplikaci. */
    fun signature(): String {
        val canon = buildString {
            append(sport.name)
            for (n in nodes) when (n) {
                is WorkoutNode.Step -> append("|S").append(stepCanon(n))
                is WorkoutNode.Repeat -> { append("|R").append(n.count).append('['); n.steps.forEach { append(stepCanon(it)).append(';') }; append(']') }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(canon.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun stepCanon(s: WorkoutNode.Step) = "${s.stepType}:${s.duration}:${s.target}"
}

/** Co které zařízení a sport podporují; matice se plní z ověřených schopností konkrétních hodinek. */
data class DeviceCapabilities(val targets: Map<Sport, Set<Class<out TargetSpec>>>, val maxFlatSteps: Int = 50) {
    companion object {
        /** Výchozí konzervativní matice pro běh a kolo; konkrétní model hodinek ji může zúžit. */
        val default = DeviceCapabilities(
            mapOf(
                Sport.RUNNING to setOf(TargetSpec.None::class.java, TargetSpec.Pace::class.java, TargetSpec.HeartRate::class.java, TargetSpec.Cadence::class.java),
                Sport.CYCLING to setOf(TargetSpec.None::class.java, TargetSpec.HeartRate::class.java, TargetSpec.Power::class.java, TargetSpec.Cadence::class.java),
            ),
        )
    }
}

data class Issue(val path: String, val message: String)

object WorkoutValidator {
    fun validate(w: Workout, caps: DeviceCapabilities = DeviceCapabilities.default): List<Issue> {
        val out = mutableListOf<Issue>()
        if (w.name.isBlank() || w.name.length > 60) out += Issue("name", "Název musí mít 1–60 znaků")
        val allowed = caps.targets[w.sport]
        if (allowed == null) { out += Issue("sport", "Sport ${w.sport} editor nepodporuje (v1: běh a kolo)"); return out }
        if (w.nodes.isEmpty()) out += Issue("nodes", "Trénink nemá žádný krok")
        w.nodes.forEachIndexed { i, n ->
            when (n) {
                is WorkoutNode.Step -> step("nodes[$i]", n, allowed, out)
                is WorkoutNode.Repeat -> {
                    if (n.count !in 2..99) out += Issue("nodes[$i].count", "Počet opakování musí být 2–99")
                    if (n.steps.isEmpty()) out += Issue("nodes[$i].steps", "Opakování je prázdné")
                    n.steps.forEachIndexed { j, s -> step("nodes[$i].steps[$j]", s, allowed, out) }
                }
            }
        }
        if (w.flatSteps().size > caps.maxFlatSteps) out += Issue("nodes", "Po rozbalení opakování je kroků více než ${caps.maxFlatSteps}")
        return out
    }

    private fun step(path: String, s: WorkoutNode.Step, allowed: Set<Class<out TargetSpec>>, out: MutableList<Issue>) {
        when (val d = s.duration) {
            is DurationSpec.Time -> if (d.seconds !in 1..24 * 3600) out += Issue("$path.duration", "Čas musí být 1 s až 24 h")
            is DurationSpec.Distance -> if (d.meters !in 1..500_000) out += Issue("$path.duration", "Vzdálenost musí být 1 m až 500 km")
            DurationSpec.LapButton -> Unit
        }
        if (s.target.javaClass !in allowed) out += Issue("$path.target", "Tento cíl vybraný sport nebo zařízení nepodporuje")
        when (val t = s.target) {
            is TargetSpec.Pace -> range(path, t.minSecPerKm, t.maxSecPerKm, 60..1800, "tempo", out)
            is TargetSpec.HeartRate -> range(path, t.minBpm, t.maxBpm, 30..250, "tep", out)
            is TargetSpec.Power -> range(path, t.minW, t.maxW, 0..3000, "výkon", out)
            is TargetSpec.Cadence -> range(path, t.min, t.max, 20..250, "kadence", out)
            TargetSpec.None -> Unit
        }
    }

    private fun range(path: String, lo: Int, hi: Int, bounds: IntRange, what: String, out: MutableList<Issue>) {
        if (lo !in bounds || hi !in bounds) out += Issue("$path.target", "Hodnota ($what) je mimo rozsah $bounds")
        if (lo >= hi) out += Issue("$path.target", "Dolní mez ($what) musí být menší než horní")
    }
}
