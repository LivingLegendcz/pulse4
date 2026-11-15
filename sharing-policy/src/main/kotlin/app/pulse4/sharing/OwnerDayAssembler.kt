package app.pulse4.sharing

import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.TrendSelector
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Z pozorování jednoho místního dne sestaví hodnoty polí. Pole bez dat se vynechá, nikdy se nenahradí nulou. */
object OwnerDayAssembler {
    /**
     * Váha a tělesný tuk jdou přes stejný výběr jako trend: jedno měření na shluk duplicit napříč zdroji a bez nejednoznačných
     * (podezřelých) měření, takže se sdílí totéž, co vidí vlastník v trendu. [weightPreference] je priorita zdrojů.
     */
    fun assemble(
        date: LocalDate,
        observations: List<MetricObservation>,
        weightPreference: List<Provider> = listOf(Provider.WITHINGS_API, Provider.HEALTH_CONNECT, Provider.MANUAL, Provider.GARMIN_PERSONAL),
    ): OwnerDay {
        val live = observations.filter { !it.deleted && it.localDate == date.toString() }
        val body = setOf(MetricType.WEIGHT, MetricType.BODY_FAT)
        val day = live.filter { it.type !in body } +
            body.flatMap { t -> TrendSelector.points(live.filter { it.type == t }, weightPreference) }
        fun of(t: MetricType) = day.filter { it.type == t }.sortedBy { it.startUtcMs }
        val values = LinkedHashMap<Field, JsonElement>()

        of(MetricType.SLEEP_DURATION).takeIf { it.isNotEmpty() }?.let { values[Field.SLEEP_DURATION] = JsonPrimitive(it.sumOf { o -> o.value }) }
        of(MetricType.SLEEP_SCORE).lastOrNull()?.let { values[Field.SLEEP_SCORE] = JsonPrimitive(it.value) }

        val bb = of(MetricType.BODY_BATTERY)
        if (bb.isNotEmpty()) {
            values[Field.BB_LAST] = JsonPrimitive(bb.last().value)
            values[Field.BB_MIN_MAX] = buildJsonObject { put("min", bb.minOf { it.value }); put("max", bb.maxOf { it.value }) }
            values[Field.BB_SERIES] = series(bb)
        }
        of(MetricType.RESTING_HEART_RATE).lastOrNull()?.let { values[Field.HR_DAILY] = JsonPrimitive(it.value) }
        of(MetricType.HEART_RATE).takeIf { it.isNotEmpty() }?.let { values[Field.HR_SERIES] = series(it) }
        val hrv = of(MetricType.HRV)
        if (hrv.isNotEmpty()) {
            values[Field.HRV_DAILY] = JsonPrimitive(hrv.sumOf { it.value } / hrv.size)
            values[Field.HRV_SERIES] = series(hrv)
        }
        of(MetricType.WEIGHT).lastOrNull()?.let { values[Field.WEIGHT] = JsonPrimitive(it.value) }
        of(MetricType.BODY_FAT).lastOrNull()?.let { values[Field.BODY_FAT] = JsonPrimitive(it.value) }

        return OwnerDay(date, values, day.maxOfOrNull { it.endUtcMs ?: it.startUtcMs })
    }

    /** Sestaví den jen z pozorování povolených zdrojovým filtrem grantu (`ShareGrant.sources`). */
    fun assembleForGrant(
        date: LocalDate,
        observations: List<MetricObservation>,
        grant: ShareGrant,
        weightPreference: List<Provider> = listOf(Provider.WITHINGS_API, Provider.HEALTH_CONNECT, Provider.MANUAL, Provider.GARMIN_PERSONAL),
    ): OwnerDay = assemble(date, observations.filter { grant.allowsProvider(it.provider) }, weightPreference)

    private fun series(o: List<MetricObservation>) = JsonArray(o.map { JsonArray(listOf(JsonPrimitive(it.startUtcMs), JsonPrimitive(it.value))) })
}
