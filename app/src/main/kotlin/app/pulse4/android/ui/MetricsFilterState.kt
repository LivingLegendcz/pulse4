package app.pulse4.android.ui

import androidx.compose.runtime.saveable.Saver
import app.pulse4.android.data.Settings
import app.pulse4.domain.MetricType

/** Allowed values of the "Období" (days) filter on the Metrics screen. */
internal val METRICS_DAYS_OPTIONS = setOf(7, 14, 30, 90, 365)

/**
 * Filter state of the Metrics screen, mirrored to [Settings] so the last selection is restored
 * after an app restart. Within a process the individual fields live in `rememberSaveable`
 * (see [MetricTypeSetSaver]), which survives rotation and process death.
 */
data class MetricsFilterState(
    val types: List<MetricType> = listOf(MetricType.HRV),
    val daysCount: Int = 30,
    val normalize: Boolean = false,
    val bodyUnitMode: BodyUnitMode = BodyUnitMode.KG,
    val showSeparateCards: Boolean = true,
) {
    val selectedTypes: LinkedHashSet<MetricType> get() = LinkedHashSet(types)

    /** Compact, stable text form: `HRV,WEIGHT;30;0;KG;1`. */
    fun encode(): String = listOf(
        types.joinToString(",") { it.name },
        daysCount.toString(),
        if (normalize) "1" else "0",
        bodyUnitMode.name,
        if (showSeparateCards) "1" else "0",
    ).joinToString(";")

    companion object {
        /** Lenient decoder: unknown metric names are dropped, invalid fields fall back to defaults. */
        fun decode(raw: String?): MetricsFilterState? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(';')
            val types = parts.getOrNull(0).orEmpty().split(',')
                .mapNotNull { n -> MetricType.entries.firstOrNull { it.name == n.trim() } }
                .distinct()
            if (types.isEmpty()) return null
            val d = MetricsFilterState()
            return MetricsFilterState(
                types = types,
                daysCount = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in METRICS_DAYS_OPTIONS } ?: d.daysCount,
                normalize = parts.getOrNull(2)?.let { it == "1" } ?: d.normalize,
                bodyUnitMode = parts.getOrNull(3)?.let { m -> BodyUnitMode.entries.firstOrNull { it.name == m } } ?: d.bodyUnitMode,
                showSeparateCards = parts.getOrNull(4)?.let { it == "1" } ?: d.showSeparateCards,
            )
        }
    }
}

/**
 * Bundle-friendly Saver for the ordered multi-metric selection (`rememberSaveable(stateSaver = ...)`).
 * Order matters: the first selected metric drives the hero artwork/accent.
 */
val MetricTypeSetSaver: Saver<LinkedHashSet<MetricType>, String> = Saver(
    save = { set -> set.joinToString(",") { it.name } },
    restore = { raw ->
        raw.split(',')
            .mapNotNull { n -> MetricType.entries.firstOrNull { it.name == n.trim() } }
            .takeIf { it.isNotEmpty() }
            ?.let { LinkedHashSet(it) }
    },
)

private const val KEY_METRICS_FILTER = "metricsFilterState"

/** Last Metrics screen filter, persisted as a non-critical preference. */
internal var Settings.metricsFilterState: MetricsFilterState?
    get() = runCatching { MetricsFilterState.decode(getRaw(KEY_METRICS_FILTER)) }.getOrNull()
    set(v) { put(KEY_METRICS_FILTER, v?.encode().orEmpty()) }
