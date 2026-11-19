package app.pulse4.android.ui

import androidx.compose.ui.graphics.Color
import app.pulse4.domain.MetricType

/**
 * Single source of truth for metric colors (cards, multi-metric chart, legends, body-composition
 * stacked/segment charts). Tuned for the dark theme:
 *
 * - Body composition (most often combined): pairwise CIELAB ΔE76 >= 30; muscle (blue) and fat
 *   (orange) sit on opposite sides of the hue wheel, safe for deuteranopia/protanopia.
 *   Weight is a neutral light reference line.
 * - Cardio (HRV, heart rate, ECG, PWV, vascular age, SpO2, respiration) and activity
 *   (steps, Body Battery, stress, sleep, HRV, resting HR) groups: pairwise ΔE76 >= 25.
 * - Every metric type has its own color (no exact duplicates).
 *
 * These are "home" colors (cards, detail screens, icons). Wherever several metrics are drawn
 * together, [assignSeriesColors] / [SeriesColorAllocator] substitute colors (and line styles) so
 * that no two series are closer than CIEDE2000 [SeriesColorAllocator.MIN_DELTA_E].
 *
 * See MetricsFilterStateTest for the enforced thresholds.
 */
object MetricPalette {
    // Body composition
    val Weight = Color(0xFFE2E8F0)
    val Muscle = Color(0xFF3B82F6)
    val Fat = Color(0xFFF97316)
    val Water = Color(0xFF22D3EE)
    val Bone = Color(0xFFD8B4FE)
    val FatFreeMass = Color(0xFF22C55E)
    val VisceralFat = Color(0xFFEF4444)
    val Bmr = Color(0xFFFACC15)
    val MetabolicAge = Color(0xFFE879F9)

    // Cardio
    val Hrv = Color(0xFF2DD4BF)
    val RestingHeartRate = Color(0xFFFB7185)
    val HeartRate = Color(0xFFFDA4AF)
    val Ecg = Color(0xFFB91C1C)
    val EcgHr = Color(0xFFFDBA74)
    val EcgQtc = Color(0xFFD946EF)
    val EcgQrs = Color(0xFFA3E635)
    val EcgAfib = Color(0xFFF0ABFC)
    val PulseWaveVelocity = Color(0xFF6366F1)
    val VascularAge = Color(0xFFBE185D)
    val SpO2 = Color(0xFF38BDF8)
    val Respiration = Color(0xFF94A3B8)

    // Activity, energy & sleep
    val Steps = Color(0xFF86EFAC)
    val BodyBattery = Color(0xFFD97706)
    val Stress = Color(0xFFA855F7)
    val SleepDuration = Color(0xFF818CF8)
    val SleepScore = Color(0xFF4F46E5)
    val SleepDeep = Color(0xFF3730A3)
    val SleepLight = Color(0xFFA5B4FC)
    val SleepRem = Color(0xFF7C3AED)
    val SleepAwake = Color(0xFFCBD5E1)
    val SleepAwakeCount = Color(0xFF64748B)
    val SleepNap = Color(0xFFC7D2FE)

    fun of(type: MetricType): Color = when (type) {
        MetricType.WEIGHT -> Weight
        MetricType.MUSCLE_MASS -> Muscle
        MetricType.BODY_FAT -> Fat
        MetricType.BODY_WATER -> Water
        MetricType.BONE_MASS -> Bone
        MetricType.FAT_FREE_MASS -> FatFreeMass
        MetricType.VISCERAL_FAT -> VisceralFat
        MetricType.BMR -> Bmr
        MetricType.METABOLIC_AGE -> MetabolicAge
        MetricType.HRV -> Hrv
        MetricType.RESTING_HEART_RATE -> RestingHeartRate
        MetricType.HEART_RATE -> HeartRate
        MetricType.ECG -> Ecg
        MetricType.ECG_HR -> EcgHr
        MetricType.ECG_QTC -> EcgQtc
        MetricType.ECG_QRS -> EcgQrs
        MetricType.ECG_AFIB -> EcgAfib
        MetricType.PULSE_WAVE_VELOCITY -> PulseWaveVelocity
        MetricType.VASCULAR_AGE -> VascularAge
        MetricType.SPO2 -> SpO2
        MetricType.RESPIRATION -> Respiration
        MetricType.STEPS -> Steps
        MetricType.BODY_BATTERY -> BodyBattery
        MetricType.STRESS -> Stress
        MetricType.SLEEP_DURATION -> SleepDuration
        MetricType.SLEEP_SCORE -> SleepScore
        MetricType.SLEEP_DEEP -> SleepDeep
        MetricType.SLEEP_LIGHT -> SleepLight
        MetricType.SLEEP_REM -> SleepRem
        MetricType.SLEEP_AWAKE -> SleepAwake
        MetricType.SLEEP_AWAKE_COUNT -> SleepAwakeCount
        MetricType.SLEEP_NAP -> SleepNap
    }

    val BODY_COMPOSITION: List<MetricType> = listOf(
        MetricType.WEIGHT, MetricType.MUSCLE_MASS, MetricType.BODY_FAT, MetricType.BODY_WATER,
        MetricType.BONE_MASS, MetricType.FAT_FREE_MASS, MetricType.VISCERAL_FAT, MetricType.BMR,
        MetricType.METABOLIC_AGE,
    )
    val CARDIO: List<MetricType> = listOf(
        MetricType.HRV, MetricType.RESTING_HEART_RATE, MetricType.ECG, MetricType.ECG_HR,
        MetricType.ECG_QTC, MetricType.ECG_QRS, MetricType.ECG_AFIB, MetricType.PULSE_WAVE_VELOCITY,
        MetricType.VASCULAR_AGE, MetricType.SPO2, MetricType.RESPIRATION,
    )
    val ACTIVITY: List<MetricType> = listOf(
        MetricType.STEPS, MetricType.BODY_BATTERY, MetricType.STRESS, MetricType.SLEEP_DURATION,
        MetricType.HRV, MetricType.RESTING_HEART_RATE,
    )
}
