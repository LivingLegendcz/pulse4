package app.pulse4.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Provider { GARMIN_PERSONAL, GARMIN_EXTENDED, HEALTH_CONNECT, WITHINGS_API, MANUAL }

/** Vnitřní jednotky jsou jednotné; převody se dělají při importu a zobrazení. */
@Serializable
enum class MetricType(val unit: String) {
    SLEEP_DURATION("s"),
    SLEEP_SCORE("score"),
    BODY_BATTERY("score"),
    HEART_RATE("bpm"),
    RESTING_HEART_RATE("bpm"),
    HRV("ms"),
    STRESS("score"),
    STEPS("count"),
    RESPIRATION("brpm"),
    SPO2("%"),
    WEIGHT("kg"),
    BODY_FAT("%"),
}

@Serializable
enum class Quality { OK, ESTIMATED, SUSPECT }

/**
 * Jedno měření. Čas je UTC, pásmo a místní den se ukládají vedle (spánek přes půlnoc
 * zůstává intervalem a zároveň nese den přiřazený poskytovatelem).
 */
@Serializable
data class MetricObservation(
    val id: String,
    val provider: Provider,
    val accountId: String,
    val externalId: String?,
    val type: MetricType,
    val startUtcMs: Long,
    val endUtcMs: Long? = null,
    val value: Double,
    val zoneId: String,
    val localDate: String? = null,
    val quality: Quality = Quality.OK,
    val sourceVersion: Int = 1,
    val deleted: Boolean = false,
    /** Původ záznamu u zprostředkovaných zdrojů (např. balíček aplikace v Health Connect, ID zařízení Withings). */
    val origin: String? = null,
) {
    init {
        require(value.isFinite()) { "Hodnota měření musí být konečné číslo" }
        endUtcMs?.let { require(it >= startUtcMs) { "Konec před začátkem" } }
    }
}

/** Stav hodnoty na kartě: „není sdíleno“, „zařízení neměří“, „zatím nesynchronizováno“ a skutečná nula jsou různé stavy. */
sealed interface Reading<out T> {
    data class Value<T>(val value: T, val measuredAtUtcMs: Long, val fetchedAtUtcMs: Long) : Reading<T>
    data object NotShared : Reading<Nothing>
    data object DeviceDoesNotMeasure : Reading<Nothing>
    data object NotSyncedYet : Reading<Nothing>
}
