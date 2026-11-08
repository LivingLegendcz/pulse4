package app.pulse4.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Sport { RUNNING, CYCLING, WALKING, HIKING, SWIMMING, STRENGTH, OTHER }

fun Sport.czechName(): String = when (this) {
    Sport.RUNNING -> "Běh"
    Sport.CYCLING -> "Cyklistika"
    Sport.WALKING -> "Chůze"
    Sport.HIKING -> "Turistika"
    Sport.SWIMMING -> "Plavání"
    Sport.STRENGTH -> "Silový trénink"
    Sport.OTHER -> "Ostatní aktivita"
}

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
    // --- Rozšířená běžecká dynamika a fyziologie Garmin ---
    val strideLengthM: Double? = null,
    val verticalOscillationCm: Double? = null,
    val verticalRatioPct: Double? = null,
    val groundContactTimeMs: Double? = null,
    val groundContactBalancePct: Double? = null,
    val staminaPct: Double? = null,
    val staminaPotentialPct: Double? = null,
    val performanceCondition: Int? = null,
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
data class StrengthSet(
    val index: Int,
    val setType: String, // "ACTIVE" or "REST"
    val reps: Int? = null,
    val weightKg: Double? = null,
    val durationS: Double? = null,
    val exerciseCategory: String? = null,
    val exerciseName: String? = null,
    val startUtcMs: Long? = null,
    val wktStepIndex: Int? = null,
) {
    val isActive: Boolean get() = setType.equals("ACTIVE", ignoreCase = true)
    val isRest: Boolean get() = setType.equals("REST", ignoreCase = true)
    val volumeKg: Double get() = if (isActive && reps != null && weightKg != null && weightKg > 0.0) reps * weightKg else 0.0
}

fun formatGarminExerciseName(category: String?, name: String?): String {
    val raw = name?.takeIf { it.isNotBlank() } ?: category?.takeIf { it.isNotBlank() } ?: return "Cvik"
    val upper = raw.uppercase().replace(' ', '_')
    return when {
        "BENCH_PRESS" in upper -> "Bench press"
        "INCLINE_BENCH_PRESS" in upper -> "Šikmý bench press"
        "DECLINE_BENCH_PRESS" in upper -> "Hlavou dolů bench press"
        "FRONT_SQUAT" in upper -> "Čelní dřep"
        "SQUAT" in upper -> "Dřep"
        "ROMANIAN_DEADLIFT" in upper -> "Rumunský mrtvý tah"
        "DEADLIFT" in upper -> "Mrtvý tah"
        "OVERHEAD_PRESS" in upper || "SHOULDER_PRESS" in upper || "MILITARY_PRESS" in upper -> "Tlak na ramena"
        "CHIN_UP" in upper -> "Shyby podhmatem"
        "PULL_UP" in upper -> "Shyby"
        "LAT_PULLDOWN" in upper -> "Stahování kladky"
        "BARBELL_ROW" in upper || "BENT_OVER_ROW" in upper -> "Přítahy v předklonu"
        "ROW" in upper -> "Přítahy (veslování)"
        "PUSH_UP" in upper -> "Kliky"
        "DIP" in upper -> "Kliky na bradlech"
        "BICEP_CURL" in upper || "CURL" in upper -> "Bicepsový zdvih"
        "TRICEPS_EXTENSION" in upper || "TRICEPS_PUSHDOWN" in upper -> "Tricepsové extenze"
        "LATERAL_RAISE" in upper -> "Upažování"
        "FRONT_RAISE" in upper -> "Předpažování"
        "REAR_DELT" in upper -> "Zadní delty"
        "LEG_PRESS" in upper -> "Leg press"
        "LEG_EXTENSION" in upper -> "Předkopávání"
        "LEG_CURL" in upper -> "Zakopávání"
        "CALF_RAISE" in upper -> "Výpony na lýtka"
        "LUNGE" in upper -> "Výpady"
        "HIP_THRUST" in upper || "HIP_RAISE" in upper -> "Hip thrust"
        "PLANK" in upper -> "Plank"
        "CRUNCH" in upper -> "Zkracovačky"
        "SIT_UP" in upper -> "Sedy-lehy"
        "RUSSIAN_TWIST" in upper -> "Ruský twist"
        "HANGING_LEG_RAISE" in upper -> "Přednožování ve visu"
        "SHRUG" in upper -> "Krčení ramen (shrugs)"
        "FLYE" in upper || "PEC_DECK" in upper -> "Rozpažování (flyes)"
        "CLEAN" in upper -> "Přemístění (clean)"
        "SNATCH" in upper -> "Trh (snatch)"
        "CARDIO" in upper -> "Kardio"
        "WARM_UP" in upper -> "Rozcvička"
        else -> raw.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }
}

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
    // --- Silový trénink (Garmin Connect & FIT) ---
    val strengthSets: List<StrengthSet> = emptyList(),
    val totalVolumeKg: Double? = null,
    val totalReps: Int? = null,
    val activeSetsCount: Int? = null,
    val workDurationS: Double? = null,
    val restDurationS: Double? = null,
    // --- Plavání (Garmin Connect & FIT) ---
    val totalStrokes: Int? = null,
    val avgSwolf: Int? = null,
    val poolLengthM: Double? = null,
    val swimStroke: String? = null,
)
