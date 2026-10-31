package app.pulse4.ui

import app.pulse4.sharing.Field
import app.pulse4.sharing.OwnerDay
import app.pulse4.sharing.PlanRight
import app.pulse4.sharing.Projector
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.SharedProjection
import app.pulse4.sharing.TimeScope
import app.pulse4.sync.Wire
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Koncept nového sdílení. Výchozí stav je nesdílet nic; uložit lze až s aspoň jedním příjemcem a polem. */
data class GrantDraft(
    val recipients: Set<String> = emptySet(),
    val fields: Set<Field> = emptySet(),
    val once: Boolean = false,
    val planRights: Map<String, PlanRight> = emptyMap(),
    val groupId: String? = null,
) {
    fun toggleRecipient(id: String) = copy(recipients = if (id in recipients) recipients - id else recipients + id)
    fun toggleField(f: Field) = copy(fields = if (f in fields) fields - f else fields + f)

    /** Rychlá volba je přepínač celé sady: je-li už celá zapnutá, vypne se. */
    fun togglePreset(p: Set<Field>) = copy(fields = if (fields.containsAll(p)) fields - p else fields + p)

    val canSave get() = recipients.isNotEmpty() && fields.isNotEmpty()

    fun problems(): List<String> = buildList {
        if (recipients.isEmpty()) add("Vyberte aspoň jednoho příjemce nebo skupinu.")
        if (fields.isEmpty()) add("Vyberte aspoň jedno pole – výchozí stav je nesdílet nic.")
        if (Field.ACT_FULL_RECORD in fields) add("Úplný záznam: při nastavené zóně soukromí se původní FIT nepřikládá.")
        if (Field.WEIGHT in fields || Field.BODY_FAT in fields) add("Váha a složení těla jsou citlivé; sdílejí se jen jednoznačná měření.")
    }

    /** Průběžné sdílení začíná dnem vytvoření: nikdy neotevírá starou historii. */
    fun toGrant(id: String, ownerId: String, today: LocalDate, nowMs: Long): ShareGrant {
        require(canSave) { "Sdílení nemá příjemce nebo pole" }
        return ShareGrant(
            id = id,
            ownerId = ownerId,
            recipients = recipients,
            fields = fields,
            planRights = planRights,
            scope = if (once) TimeScope.once(today, today) else TimeScope.continuousFrom(today),
            groupId = groupId,
            createdAtMs = nowMs,
        )
    }

    /** Náhled přesně toho, co odejde: tytéž bajty jako ve frontě. */
    fun preview(ownerId: String, day: OwnerDay, nowMs: Long): String {
        val g = ShareGrant("preview", ownerId, recipients.ifEmpty { setOf("?") }, fields, planRights, TimeScope.once(day.date, day.date), createdAtMs = 0)
        return Projector.project(g, day, nowMs)?.let { String(Projector.encode(it)) }
            ?: "Pro dnešek nejsou žádná data odpovídající výběru – nic by neodešlo."
    }
}

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
