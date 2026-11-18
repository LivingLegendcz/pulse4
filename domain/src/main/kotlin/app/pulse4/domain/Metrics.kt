package app.pulse4.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Provider { GARMIN_PERSONAL, GARMIN_EXTENDED, HEALTH_CONNECT, WITHINGS_API, MANUAL }

/** Vnitřní jednotky jsou jednotné; převody se dělají při importu a zobrazení. */
@Serializable
enum class MetricType(val unit: String) {
    SLEEP_DURATION("s"),
    SLEEP_SCORE("score"),
    SLEEP_DEEP("s"),
    SLEEP_LIGHT("s"),
    SLEEP_REM("s"),
    SLEEP_AWAKE("s"),
    SLEEP_AWAKE_COUNT("count"),
    SLEEP_NAP("s"),
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
    FAT_FREE_MASS("kg"),
    MUSCLE_MASS("kg"),
    BONE_MASS("kg"),
    BODY_WATER("%"),
    VISCERAL_FAT("idx"),
    VASCULAR_AGE("yr"),
    PULSE_WAVE_VELOCITY("m/s"),
    BMR("kcal"),
    METABOLIC_AGE("yr"),
    ECG("ms"),
    ECG_HR("bpm"),
    ECG_QTC("ms"),
    ECG_QRS("ms"),
    ECG_AFIB("class"),
}

@Serializable
enum class EcgAfibClassification(val code: Double) {
    NONE_DETECTED(0.0),
    AFIB(1.0),
    INCONCLUSIVE(2.0),
    UNKNOWN(-1.0);

    companion object {
        fun fromWithingsCode(code: Int?): EcgAfibClassification = when (code) {
            0 -> NONE_DETECTED
            1 -> AFIB
            2 -> INCONCLUSIVE
            else -> UNKNOWN
        }

        fun fromValue(value: Double): EcgAfibClassification = when (value.toInt()) {
            0 -> NONE_DETECTED
            1 -> AFIB
            2 -> INCONCLUSIVE
            else -> UNKNOWN
        }
    }
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
