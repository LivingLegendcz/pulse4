package app.pulse4.training

import app.pulse4.provider.ProviderError
import kotlinx.serialization.Serializable

data class RemoteWorkout(val id: String, val name: String, val signature: String)
data class RemoteScheduled(val calendarId: String, val workoutId: String, val date: String)

/** Zápis do Garminu; implementuje neoficiální konektor (nebo budoucí oficiální Training API). */
interface GarminWorkoutApi {
    fun listWorkouts(): List<RemoteWorkout>
    fun getWorkout(id: String): RemoteWorkout?
    /** Vytvoří (existingId == null) nebo aktualizuje. [marker] je součást názvu pro dohledání po nejasném timeoutu. */
    fun upsertWorkout(w: Workout, marker: String, existingId: String?): String
    fun listCalendar(date: String): List<RemoteScheduled>
    fun schedule(workoutId: String, date: String): String
    fun requestDeviceSync()
}

@Serializable
enum class ExportStep { VALIDATE, UPSERT, VERIFY_STORED, SCHEDULE, REQUEST_DEVICE }
@Serializable
enum class StepState { PENDING, DONE, FAILED, UNCERTAIN }

/** Co lze o stavu skutečně doložit; „přijetí hodinkami ověřeno“ jen s důkazem. */
enum class ExportLevel { LOCAL_ONLY, IN_GARMIN_CONNECT, SCHEDULED, DEVICE_REQUESTED, DEVICE_VERIFIED }

@Serializable
data class ExportRecord(
    val exportId: String,
    val planId: String,
    val revision: Long,
    val scheduledWorkoutId: String,
    val date: String,
    val garminWorkoutId: String? = null,
    val garminCalendarId: String? = null,
    /** Podpis, který jsme do Garminu zapsali; liší-li se podpis na straně Garminu, jde o externí změnu. */
    val exportedSignature: String? = null,
    val steps: Map<ExportStep, StepState> = ExportStep.entries.associateWith { StepState.PENDING },
    val message: String? = null,
    val deviceVerifiedEvidence: String? = null,
) {
    val blocked: Boolean get() = steps.values.any { it == StepState.UNCERTAIN }
    val level: ExportLevel get() = when {
        deviceVerifiedEvidence != null -> ExportLevel.DEVICE_VERIFIED
        steps[ExportStep.REQUEST_DEVICE] == StepState.DONE -> ExportLevel.DEVICE_REQUESTED
        steps[ExportStep.SCHEDULE] == StepState.DONE -> ExportLevel.SCHEDULED
        steps[ExportStep.UPSERT] == StepState.DONE && steps[ExportStep.VERIFY_STORED] == StepState.DONE -> ExportLevel.IN_GARMIN_CONNECT
        else -> ExportLevel.LOCAL_ONLY
    }
    val marker: String get() = "[p4-${exportId.take(6)}]"
    fun with(step: ExportStep, s: StepState, msg: String? = message) = copy(steps = steps + (step to s), message = msg)
}

sealed interface ExportOutcome {
    val record: ExportRecord
    data class Finished(override val record: ExportRecord) : ExportOutcome
    /** Zastaveno: nejednoznačný výsledek. Žádná další mutace, dokud se výsledek neporovná. */
    data class NeedsVerification(override val record: ExportRecord) : ExportOutcome
    /** Trénink v Garminu byl změněn mimo aplikaci: porovnat, nepřepisovat automaticky. */
    data class ExternalChange(override val record: ExportRecord, val remote: RemoteWorkout) : ExportOutcome
    data class Failed(override val record: ExportRecord) : ExportOutcome
}

/**
 * Export je fronta kroků: validace → vytvoření/aktualizace → kontrola uloženého objektu → kalendář → požadavek na zařízení.
 * Bez idempotency klíče poskytovatele nelze předstírat exactly-once zápis: po timeoutu se nejdřív dohledá výsledek.
 */
class ExportRunner(private val api: GarminWorkoutApi, private val caps: DeviceCapabilities = DeviceCapabilities.default) {

    fun run(start: ExportRecord, w: Workout, forceOverwriteExternal: Boolean = false): ExportOutcome {
        var r = start
        if (r.blocked) return ExportOutcome.NeedsVerification(r) // žádné další mutace do porovnání výsledku

        // 1) validace
        val issues = WorkoutValidator.validate(w, caps)
        if (issues.isNotEmpty()) return ExportOutcome.Failed(r.with(ExportStep.VALIDATE, StepState.FAILED, issues.first().message))
        r = r.with(ExportStep.VALIDATE, StepState.DONE, null)

        // 2) vytvoření / aktualizace
        if (r.steps[ExportStep.UPSERT] != StepState.DONE || r.exportedSignature != w.signature()) {
            if (r.garminWorkoutId != null && !forceOverwriteExternal) {
                val remote = runCatching { api.getWorkout(r.garminWorkoutId) }.getOrNull()
                if (remote != null && r.exportedSignature != null && remote.signature != r.exportedSignature) {
                    return ExportOutcome.ExternalChange(r, remote)
                }
            }
            try {
                val id = api.upsertWorkout(w, r.marker, r.garminWorkoutId)
                r = r.copy(garminWorkoutId = id, exportedSignature = w.signature()).with(ExportStep.UPSERT, StepState.DONE, null)
            } catch (e: ProviderError.Timeout) {
                return afterUpsertTimeout(r, w)
            } catch (e: ProviderError) {
                return ExportOutcome.Failed(r.with(ExportStep.UPSERT, StepState.FAILED, e.message))
            }
        }

        // 3) kontrola uloženého objektu
        val remote = try { api.getWorkout(r.garminWorkoutId!!) } catch (e: ProviderError) {
            return ExportOutcome.Failed(r.with(ExportStep.VERIFY_STORED, StepState.FAILED, e.message))
        }
        if (remote == null || remote.signature != w.signature()) {
            return ExportOutcome.Failed(r.with(ExportStep.VERIFY_STORED, StepState.FAILED, "Uložený trénink neodpovídá odeslanému"))
        }
        r = r.with(ExportStep.VERIFY_STORED, StepState.DONE, null)

        // 4) kalendář (nejdřív zjistit, zda položka už není, aby nevznikl duplikát)
        if (r.garminCalendarId == null) {
            try {
                val existing = api.listCalendar(r.date).filter { it.workoutId == r.garminWorkoutId }
                r = when (existing.size) {
                    0 -> r.copy(garminCalendarId = api.schedule(r.garminWorkoutId!!, r.date)).with(ExportStep.SCHEDULE, StepState.DONE, null)
                    1 -> r.copy(garminCalendarId = existing.single().calendarId).with(ExportStep.SCHEDULE, StepState.DONE, null)
                    else -> return ExportOutcome.NeedsVerification(r.with(ExportStep.SCHEDULE, StepState.UNCERTAIN, "V kalendáři je více položek"))
                }
            } catch (e: ProviderError.Timeout) {
                return afterScheduleTimeout(r, w)
            } catch (e: ProviderError) {
                return ExportOutcome.Failed(r.with(ExportStep.SCHEDULE, StepState.FAILED, e.message))
            }
        } else r = r.with(ExportStep.SCHEDULE, StepState.DONE, null)

        // 5) požadavek na zařízení (idempotentní; neznamená přijetí hodinkami)
        r = try {
            api.requestDeviceSync()
            r.with(ExportStep.REQUEST_DEVICE, StepState.DONE, null)
        } catch (e: ProviderError) {
            return ExportOutcome.Failed(r.with(ExportStep.REQUEST_DEVICE, StepState.FAILED, e.message))
        } catch (e: UnsupportedOperationException) {
            return ExportOutcome.Failed(r.with(ExportStep.REQUEST_DEVICE, StepState.FAILED, e.message ?: "Synchronizaci hodinek spusťte v aplikaci Garmin Connect"))
        }
        return ExportOutcome.Finished(r)
    }

    private fun afterUpsertTimeout(r: ExportRecord, w: Workout): ExportOutcome {
        val found = try { api.listWorkouts().filter { it.name.contains(r.marker) } } catch (e: ProviderError) {
            return ExportOutcome.NeedsVerification(r.with(ExportStep.UPSERT, StepState.UNCERTAIN, "Timeout a výsledek nelze dohledat"))
        }
        return when {
            // Aktualizace existujícího objektu: ID už známe, jen výsledek nelze potvrdit.
            r.garminWorkoutId != null -> {
                val remote = runCatching { api.getWorkout(r.garminWorkoutId) }.getOrNull()
                if (remote != null && remote.signature == w.signature()) run(r.copy(exportedSignature = w.signature()).with(ExportStep.UPSERT, StepState.DONE, null), w)
                else ExportOutcome.NeedsVerification(r.with(ExportStep.UPSERT, StepState.UNCERTAIN, "Aktualizace po timeoutu nepotvrzena"))
            }
            found.size == 1 -> run(r.copy(garminWorkoutId = found.single().id, exportedSignature = w.signature()).with(ExportStep.UPSERT, StepState.DONE, null), w)
            found.isEmpty() -> ExportOutcome.Failed(r.with(ExportStep.UPSERT, StepState.FAILED, "Timeout; trénink v Garminu nevznikl, opakování je bezpečné"))
            else -> ExportOutcome.NeedsVerification(r.with(ExportStep.UPSERT, StepState.UNCERTAIN, "Po timeoutu nalezeno více shodných tréninků"))
        }
    }

    private fun afterScheduleTimeout(r: ExportRecord, w: Workout): ExportOutcome {
        val found = try { api.listCalendar(r.date).filter { it.workoutId == r.garminWorkoutId } } catch (e: ProviderError) {
            return ExportOutcome.NeedsVerification(r.with(ExportStep.SCHEDULE, StepState.UNCERTAIN, "Timeout a kalendář nelze zkontrolovat"))
        }
        return when (found.size) {
            // Položka vznikla: přijmeme ji a pokračujeme posledním krokem, žádné druhé naplánování.
            1 -> run(r.copy(garminCalendarId = found.single().calendarId).with(ExportStep.SCHEDULE, StepState.DONE, null), w)
            0 -> ExportOutcome.Failed(r.with(ExportStep.SCHEDULE, StepState.FAILED, "Timeout; položka v kalendáři nevznikla, opakování je bezpečné"))
            else -> ExportOutcome.NeedsVerification(r.with(ExportStep.SCHEDULE, StepState.UNCERTAIN, "Po timeoutu nalezeno více položek"))
        }
    }
}

/** Cizí tréninky bez lokálního mapování se automaticky nemažou. */
object ExportCleanup {
    fun deletableRemote(records: List<ExportRecord>, remote: List<RemoteWorkout>): List<RemoteWorkout> {
        val mapped = records.mapNotNull { it.garminWorkoutId }.toSet()
        return remote.filter { it.id in mapped }
    }
}
