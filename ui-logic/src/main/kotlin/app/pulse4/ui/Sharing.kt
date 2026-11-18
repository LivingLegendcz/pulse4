package app.pulse4.ui

import app.pulse4.sharing.Field
import app.pulse4.sharing.SharedProjection
import app.pulse4.sync.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Přijatá projekce pro dashboard: čitelné hodnoty místo surového JSON. */
class ProjectionView(val date: String, val measuredAtUtcMs: Long?, val rows: List<Pair<String, String>>) {
    companion object {
        fun parse(payload: String, fmt: Fmt = Fmt.default): ProjectionView? {
            val p = runCatching { Wire.json.decodeFromString(SharedProjection.serializer(), payload) }.getOrNull() ?: return null
            // Pořadí podle výčtu polí, aby se karta nepřeskupovala podle pořadí v JSON.
            val rows = Field.entries.mapNotNull { f -> p.fields[f]?.let { Labels.field(f) to value(f, it, fmt) } }
            return ProjectionView(p.date, p.measuredAtUtcMs, rows)
        }

        private fun num(e: JsonElement) = (e as? JsonPrimitive)?.doubleOrNull

        fun value(f: Field, e: JsonElement, fmt: Fmt): String = when (f) {
            Field.SLEEP_DURATION -> num(e)?.let(fmt::sleep)
            Field.SLEEP_SCORE, Field.BB_LAST -> num(e)?.let { fmt.number(it) }
            Field.BB_MIN_MAX -> (e as? JsonObject)?.let { o -> val lo = num(o["min"] ?: JsonPrimitive("")); val hi = num(o["max"] ?: JsonPrimitive("")); if (lo != null && hi != null) "${fmt.number(lo)}–${fmt.number(hi)}" else null }
            Field.HR_DAILY -> num(e)?.let { fmt.number(it) + " bpm" }
            Field.HRV_DAILY -> num(e)?.let { fmt.number(it) + " ms" }
            Field.WEIGHT -> num(e)?.let(fmt::weight)
            Field.BODY_FAT -> num(e)?.let { fmt.number(it, 1) + " %" }
            Field.BB_SERIES, Field.HR_SERIES, Field.HRV_SERIES -> (e as? JsonArray)?.let { "${it.size} bodů" }
            else -> null
        } ?: "—"
    }
}
