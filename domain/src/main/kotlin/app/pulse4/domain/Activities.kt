package app.pulse4.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Sport { RUNNING, CYCLING, WALKING, HIKING, SWIMMING, STRENGTH, OTHER }

/** Bod trasy. Chybějící GPS je `null`, nikdy 0°/0°. */
@Serializable
data class TrackPoint(
    val tMs: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val elevationM: Double? = null,
    val heartRate: Int? = null,
    val speedMps: Double? = null,
    val cadence: Int? = null,
    val powerW: Int? = null,
    val distanceM: Double? = null,
) {
    val hasPosition: Boolean get() = lat != null && lon != null
}

@Serializable
data class ActivityLap(
    val index: Int,
    val startUtcMs: Long,
    val endUtcMs: Long,
    val durationS: Double,
    val distanceM: Double? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val avgSpeedMps: Double? = null,
    val maxSpeedMps: Double? = null,
    val ascentM: Double? = null,
    val descentM: Double? = null,
    val avgCadence: Double? = null,
    val avgPowerW: Int? = null,
    val caloriesKcal: Double? = null,
)

@Serializable
data class ActivitySplitSummary(
    val splitType: String,
    val noOfSplits: Int = 1,
    val durationS: Double,
    val distanceM: Double? = null,
    val avgSpeedMps: Double? = null,
    val maxSpeedMps: Double? = null,
    val ascentM: Double? = null,
    val descentM: Double? = null,
    val avgCadence: Double? = null,
    val avgStrideLengthCm: Double? = null,
    val avgVerticalOscillationCm: Double? = null,
    val avgGroundContactTimeMs: Double? = null,
    val avgVerticalRatioPct: Double? = null,
)

@Serializable
data class Activity(
    val id: String,
    val provider: Provider,
    val accountId: String,
    val externalId: String?,
    val sport: Sport,
    val startUtcMs: Long,
    val zoneId: String,
    val totalTimeS: Double,
    val movingTimeS: Double? = null,
    val distanceM: Double? = null,
    val ascentM: Double? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val title: String? = null,
    val notes: String? = null,
    val tags: Set<String> = emptySet(),
    val equipmentId: String? = null,
    /** Subjektivní náročnost 1–10, ruční zápis. */
    val perceivedEffort: Int? = null,
    // --- Rozšířená telemetrie Garmin Connect & FIT ---
    val locationName: String? = null,
    val descentM: Double? = null,
    val minElevationM: Double? = null,
    val maxElevationM: Double? = null,
    val avgElevationM: Double? = null,
    val avgSpeedMps: Double? = null,
    val maxSpeedMps: Double? = null,
    val avgGradeAdjustedSpeedMps: Double? = null,
    val caloriesKcal: Double? = null,
    val bmrCaloriesKcal: Double? = null,
    val waterEstimatedMl: Double? = null,
    val steps: Int? = null,
    val avgCadence: Double? = null,
    val maxCadence: Double? = null,
    val avgPowerW: Double? = null,
    val maxPowerW: Double? = null,
    val normPowerW: Double? = null,
    val avgStrideLengthCm: Double? = null,
    val avgVerticalOscillationCm: Double? = null,
    val avgGroundContactTimeMs: Double? = null,
    val avgVerticalRatioPct: Double? = null,
    val aerobicTrainingEffect: Double? = null,
    val anaerobicTrainingEffect: Double? = null,
    val trainingEffectLabel: String? = null,
    val aerobicTrainingEffectMessage: String? = null,
    val anaerobicTrainingEffectMessage: String? = null,
    val trainingLoad: Double? = null,
    val moderateIntensityMinutes: Int? = null,
    val vigorousIntensityMinutes: Int? = null,
    val bodyBatteryImpact: Int? = null,
    val fastestSplit1kS: Double? = null,
    val fastestSplit1miS: Double? = null,
    val fastestSplit5kS: Double? = null,
    val hrZoneTimesS: List<Double> = emptyList(),
    val powerZoneTimesS: List<Double> = emptyList(),
    val laps: List<ActivityLap> = emptyList(),
    val splits: List<ActivitySplitSummary> = emptyList(),
)
