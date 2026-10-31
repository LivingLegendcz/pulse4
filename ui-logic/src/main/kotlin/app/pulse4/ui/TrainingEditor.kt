package app.pulse4.ui

import app.pulse4.domain.Sport
import app.pulse4.training.DurationSpec
import app.pulse4.training.Issue
import app.pulse4.training.StepType
import app.pulse4.training.TargetSpec
import app.pulse4.training.Workout
import app.pulse4.training.WorkoutNode
import app.pulse4.training.WorkoutValidator
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

object WorkoutText {
    fun duration(d: DurationSpec) = when (d) {
        is DurationSpec.Time -> "${d.seconds / 60}:%02d min".format(d.seconds % 60)
        is DurationSpec.Distance -> "${d.meters} m"
        DurationSpec.LapButton -> "tlačítko kola"
    }

    fun target(t: TargetSpec) = when (t) {
        TargetSpec.None -> ""
        is TargetSpec.Pace -> " @ ${t.minSecPerKm / 60}:%02d–${t.maxSecPerKm / 60}:%02d /km".format(t.minSecPerKm % 60, t.maxSecPerKm % 60)
        is TargetSpec.HeartRate -> " @ ♥ ${t.minBpm}–${t.maxBpm}"
        is TargetSpec.Power -> " @ ${t.minW}–${t.maxW} W"
        is TargetSpec.Cadence -> " @ ${t.min}–${t.max} rpm"
    }

    fun step(s: WorkoutNode.Step) = "${stepType(s.stepType)} · ${duration(s.duration)}${target(s.target)}"

    fun describe(n: WorkoutNode): String = when (n) {
        is WorkoutNode.Step -> step(n)
        is WorkoutNode.Repeat -> "${n.count}× [ " + n.steps.joinToString(" → ") { step(it) } + " ]"
    }

    fun stepType(t: StepType) = when (t) {
        StepType.WARMUP -> "rozcvičení"; StepType.WORK -> "práce"; StepType.RECOVERY -> "zotavení"; StepType.REST -> "odpočinek"; StepType.COOLDOWN -> "závěr"
    }
}

/** Formulář kroku z dialogu. Neplatné vstupy se nezakrývají, vrátí se do validace jako problém. */
object StepForm {
    fun build(type: StepType, kind: String, value: String): WorkoutNode.Step {
        val n = value.toIntOrNull() ?: 0
        val d = when (kind) { "time" -> DurationSpec.Time(n); "distance" -> DurationSpec.Distance(n); else -> DurationSpec.LapButton }
        return WorkoutNode.Step(type, d)
    }
}

/** Typický intervalový blok: N× (práce → zotavení). Cíl práce je tempo (běh) nebo výkon (kolo); meze se seřadí. */
object IntervalForm {
    fun build(sport: Sport, count: String, workKind: String, work: String, rest: String, lo: String, hi: String): WorkoutNode.Repeat {
        val w = work.toIntOrNull() ?: 0
        val a = lo.toIntOrNull(); val b = hi.toIntOrNull()
        val target: TargetSpec = when {
            a == null || b == null -> TargetSpec.None
            sport == Sport.RUNNING -> TargetSpec.Pace(minOf(a, b), maxOf(a, b))
            else -> TargetSpec.Power(minOf(a, b), maxOf(a, b))
        }
        val workStep = WorkoutNode.Step(StepType.WORK, if (workKind == "time") DurationSpec.Time(w) else DurationSpec.Distance(w), target)
        return WorkoutNode.Repeat(count.toIntOrNull() ?: 0, listOf(workStep, WorkoutNode.Step(StepType.RECOVERY, DurationSpec.Time(rest.toIntOrNull() ?: 0))))
    }
}

/** Neměnný stav editoru. Změna sportu vymaže kroky (cíle se mezi během a kolem liší). */
data class WorkoutDraft(val workout: Workout) {
    val issues: List<Issue> get() = WorkoutValidator.validate(workout)
    val valid get() = issues.isEmpty()

    fun rename(name: String) = copy(workout = workout.copy(name = name))
    fun sport(s: Sport) = if (s == workout.sport) this else copy(workout = workout.copy(sport = s, nodes = emptyList()))
    fun add(n: WorkoutNode) = copy(workout = workout.copy(nodes = workout.nodes + n))
    fun removeAt(i: Int) = if (i !in workout.nodes.indices) this else copy(workout = workout.copy(nodes = workout.nodes.filterIndexed { k, _ -> k != i }))
    fun move(i: Int, delta: Int): WorkoutDraft {
        val j = i + delta
        if (i !in workout.nodes.indices || j !in workout.nodes.indices) return this
        val l = workout.nodes.toMutableList(); l[i] = l[j].also { l[j] = l[i] }
        return copy(workout = workout.copy(nodes = l))
    }

    companion object {
        fun empty(sport: Sport = Sport.RUNNING) = WorkoutDraft(Workout(UUID.randomUUID().toString(), "Nový trénink", sport, emptyList(), ""))
    }
}

object DateInput {
    /** Datum pro plán: musí být platné; minulé datum je dovoleno (zpětné plánování), ale upozorní se. */
    sealed interface Parsed {
        data class Ok(val date: LocalDate, val inPast: Boolean) : Parsed
        data object Invalid : Parsed
    }

    fun parse(text: String, today: LocalDate): Parsed = try {
        LocalDate.parse(text.trim()).let { Parsed.Ok(it, it.isBefore(today)) }
    } catch (e: DateTimeParseException) { Parsed.Invalid }
}
