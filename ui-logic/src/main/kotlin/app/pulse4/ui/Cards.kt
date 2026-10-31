package app.pulse4.ui

import app.pulse4.analytics.Baseline
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Reading
import app.pulse4.provider.SyncArea
import java.time.LocalDate

/** Jak se z více vzorků dne udělá jedna hodnota. Kroky a spánek se sčítají; průměr by byl chyba. */
enum class Agg { SUM, LAST, AVG }

object Aggregation {
    fun of(t: MetricType): Agg = when (t) {
        MetricType.STEPS, MetricType.SLEEP_DURATION -> Agg.SUM
        MetricType.BODY_BATTERY, MetricType.RESTING_HEART_RATE, MetricType.WEIGHT, MetricType.BODY_FAT, MetricType.SLEEP_SCORE -> Agg.LAST
        else -> Agg.AVG
    }

    fun value(t: MetricType, samples: List<MetricObservation>): Double? {
        val s = samples.filter { it.type == t && !it.deleted && it.value.isFinite() }.sortedBy { it.startUtcMs }
        if (s.isEmpty()) return null
        return when (of(t)) { Agg.SUM -> s.sumOf { it.value }; Agg.LAST -> s.last().value; Agg.AVG -> s.map { it.value }.average() }
    }
}

data class Card(val key: String, val title: String, val reading: Reading<String>)

/** Karty obrazovky „Dnes“. „Není sdíleno“, „zařízení neměří“, „zatím nesynchronizováno“ a skutečná nula jsou různé stavy. */
object TodayCards {
    private class Spec(val key: String, val title: String, val type: MetricType, val area: SyncArea, val fmt: (Fmt, Double) -> String)

    private val specs = listOf(
        Spec("sleep", "Spánek", MetricType.SLEEP_DURATION, SyncArea.SLEEP) { f, v -> f.sleep(v) },
        Spec("bb", "Body Battery (poslední vzorek)", MetricType.BODY_BATTERY, SyncArea.BODY_BATTERY) { f, v -> f.number(v) },
        Spec("rhr", "Klidový tep", MetricType.RESTING_HEART_RATE, SyncArea.HEART_RATE) { f, v -> f.number(v) + " bpm" },
        Spec("hrv", "HRV", MetricType.HRV, SyncArea.HRV) { f, v -> f.number(v) + " ms" },
        Spec("stress", "Stres", MetricType.STRESS, SyncArea.STEPS_STRESS) { f, v -> f.number(v) },
        Spec("steps", "Kroky", MetricType.STEPS, SyncArea.STEPS_STRESS) { f, v -> f.number(v) },
    )

    fun build(day: List<MetricObservation>, lastSuccessMs: Map<SyncArea, Long?>, fmt: Fmt = Fmt.default): List<Card> =
        specs.map { sp ->
            val samples = day.filter { it.type == sp.type && !it.deleted }
            val value = Aggregation.value(sp.type, samples)
            val fetched = lastSuccessMs[sp.area]
            val reading: Reading<String> = when {
                value == null && fetched == null -> Reading.NotSyncedYet
                value == null -> Reading.DeviceDoesNotMeasure // synchronizováno, ale pro dnešek žádné měření
                else -> Reading.Value(sp.fmt(fmt, value), samples.maxOf { it.endUtcMs ?: it.startUtcMs }, fetched ?: 0)
            }
            Card(sp.key, sp.title, reading)
        }
}

class MetricSeries(val dates: List<LocalDate>, val values: List<Double?>, val usual: Baseline.UsualRange?) {
    val hasData get() = values.any { it != null }

    companion object {
        /** Denní hodnoty za posledních [days] dní; den bez měření je `null` (mezera v čáře), nikdy nula. */
        fun build(type: MetricType, rows: List<MetricObservation>, today: LocalDate, days: Int = 30): MetricSeries {
            val byDate = rows.filter { it.type == type && !it.deleted }.groupBy { it.localDate }
            val dates = (0 until days).map { today.minusDays((days - 1 - it).toLong()) }
            val values = dates.map { Aggregation.value(type, byDate[it.toString()].orEmpty()) }
            return MetricSeries(dates, values, Baseline.usualRange(values.filterNotNull()))
        }
    }
}
