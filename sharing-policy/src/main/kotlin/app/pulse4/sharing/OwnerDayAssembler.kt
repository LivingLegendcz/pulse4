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
        fun tsOf(o: MetricObservation): Long = o.endUtcMs ?: o.startUtcMs
        val values = LinkedHashMap<Field, JsonElement>()
        val fieldTimestamps = LinkedHashMap<Field, Long>()

        val sleepProviderOrder = listOf(
            Provider.GARMIN_PERSONAL,
            Provider.GARMIN_EXTENDED,
            Provider.WITHINGS_API,
            Provider.HEALTH_CONNECT,
            Provider.MANUAL,
        )
        val sleepDur = selectPreferredProvider(of(MetricType.SLEEP_DURATION), sleepProviderOrder)
        if (sleepDur.isNotEmpty()) {
            values[Field.SLEEP_DURATION] = JsonPrimitive(sleepDur.sumOf { it.value })
            fieldTimestamps[Field.SLEEP_DURATION] = sleepDur.maxOf(::tsOf)
        }
        of(MetricType.SLEEP_SCORE).lastOrNull()?.let {
            values[Field.SLEEP_SCORE] = JsonPrimitive(it.value)
            fieldTimestamps[Field.SLEEP_SCORE] = tsOf(it)
        }

        val bb = of(MetricType.BODY_BATTERY)
        if (bb.isNotEmpty()) {
            val bbMaxTs = bb.maxOf(::tsOf)
            values[Field.BB_LAST] = JsonPrimitive(bb.last().value)
            fieldTimestamps[Field.BB_LAST] = tsOf(bb.last())
            values[Field.BB_MIN_MAX] = buildJsonObject { put("min", bb.minOf { it.value }); put("max", bb.maxOf { it.value }) }
            fieldTimestamps[Field.BB_MIN_MAX] = bbMaxTs
            values[Field.BB_SERIES] = series(bb)
            fieldTimestamps[Field.BB_SERIES] = bbMaxTs
        }
        of(MetricType.RESTING_HEART_RATE).lastOrNull()?.let {
            values[Field.HR_DAILY] = JsonPrimitive(it.value)
            fieldTimestamps[Field.HR_DAILY] = tsOf(it)
        }
        val hrSeries = of(MetricType.HEART_RATE)
        if (hrSeries.isNotEmpty()) {
            values[Field.HR_SERIES] = series(hrSeries)
            fieldTimestamps[Field.HR_SERIES] = hrSeries.maxOf(::tsOf)
        }
        val hrv = of(MetricType.HRV)
        if (hrv.isNotEmpty()) {
            val hrvMaxTs = hrv.maxOf(::tsOf)
            values[Field.HRV_DAILY] = JsonPrimitive(hrv.sumOf { it.value } / hrv.size)
            fieldTimestamps[Field.HRV_DAILY] = hrvMaxTs
            values[Field.HRV_SERIES] = series(hrv)
            fieldTimestamps[Field.HRV_SERIES] = hrvMaxTs
        }
        of(MetricType.WEIGHT).lastOrNull()?.let {
            values[Field.WEIGHT] = JsonPrimitive(it.value)
            fieldTimestamps[Field.WEIGHT] = tsOf(it)
        }
        of(MetricType.BODY_FAT).lastOrNull()?.let {
            values[Field.BODY_FAT] = JsonPrimitive(it.value)
            fieldTimestamps[Field.BODY_FAT] = tsOf(it)
        }

        return OwnerDay(date, values, day.maxOfOrNull(::tsOf), fieldTimestamps)
    }

    private fun selectPreferredProvider(
        samples: List<MetricObservation>,
        preference: List<Provider>,
    ): List<MetricObservation> {
        if (samples.isEmpty()) return emptyList()
        val byProvider = samples.groupBy { it.provider }
        if (byProvider.size <= 1) return samples
        val chosen = preference.firstOrNull { it in byProvider } ?: samples.first().provider
        return byProvider[chosen].orEmpty()
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
