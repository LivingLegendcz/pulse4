package app.pulse4.android.di

import app.pulse4.android.data.ExportRow
import app.pulse4.sync.Wire
import app.pulse4.training.ExportOutcome
import app.pulse4.training.ExportRecord
import app.pulse4.training.ExportRunner
import app.pulse4.training.Workout
import java.util.UUID

/**
 * Export tréninku do Garminu: fronta kroků s vlastním stavem. Zapisuje do účtu vlastníka tohoto telefonu –
 * právo upravovat plán neznamená právo přihlásit se do cizího Garminu. Nejednoznačný timeout zastaví další mutace.
 */
class ExportService(private val sl: ServiceLocator) {
    private val json = Wire.json

    fun records(planId: String): List<ExportRecord> =
        sl.db.plans().exports(planId).map { json.decodeFromString(ExportRecord.serializer(), it.json) }

    fun run(planId: String, revision: Long, scheduledWorkoutId: String, date: String, w: Workout, forceOverwrite: Boolean = false): ExportOutcome {
        val existing = records(planId).firstOrNull { it.scheduledWorkoutId == scheduledWorkoutId }
        val rec = existing ?: ExportRecord(UUID.randomUUID().toString(), planId, revision, scheduledWorkoutId, date)
        if (!sl.garminExt.ensureSession()) throw app.pulse4.provider.ProviderError.Unauthorized()
        val out = ExportRunner(sl.garminExt).run(rec.copy(revision = revision, date = date), w, forceOverwrite)
        val r = out.record
        sl.db.plans().putExport(ExportRow(r.exportId, r.planId, r.scheduledWorkoutId, json.encodeToString(ExportRecord.serializer(), r)))
        return out
    }
}
