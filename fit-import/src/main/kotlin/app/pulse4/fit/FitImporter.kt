package app.pulse4.fit

import app.pulse4.analytics.ActivityMath
import app.pulse4.domain.Sport
import app.pulse4.domain.StrengthSet
import app.pulse4.domain.TrackPoint
import com.garmin.fit.Decode
import com.garmin.fit.FitRuntimeException
import com.garmin.fit.LapMesg
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.SetMesg
import java.io.ByteArrayInputStream

data class ParsedLap(
    val startMs: Long,
    val endMs: Long,
    val distanceM: Double?,
    val startLat: Double?,
    val startLon: Double?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int? = null,
    val avgSpeedMps: Double? = null,
    val maxSpeedMps: Double? = null,
    val ascentM: Double? = null,
    val descentM: Double? = null,
    val avgCadence: Double? = null,
    val avgPowerW: Int? = null,
    val caloriesKcal: Double? = null,
)

data class ParsedFit(
    val sport: Sport,
    val startMs: Long,
    val totalTimeS: Double,
    val movingTimeS: Double?,
    val distanceM: Double?,
    val ascentM: Double?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val points: List<TrackPoint>,
    val laps: List<ParsedLap>,
    val descentM: Double? = null,
    val caloriesKcal: Double? = null,
    val avgSpeedMps: Double? = null,
    val maxSpeedMps: Double? = null,
    val avgCadence: Double? = null,
    val maxCadence: Double? = null,
    val avgPowerW: Double? = null,
    val maxPowerW: Double? = null,
    val normPowerW: Double? = null,
    val aerobicTrainingEffect: Double? = null,
    val anaerobicTrainingEffect: Double? = null,
    val avgStrideLengthCm: Double? = null,
    val avgVerticalOscillationCm: Double? = null,
    val avgGroundContactTimeMs: Double? = null,
    val avgVerticalRatioPct: Double? = null,
    val steps: Int? = null,
    val strengthSets: List<StrengthSet> = emptyList(),
    val totalReps: Int? = null,
    val totalStrokes: Int? = null,
    val avgSwolf: Int? = null,
    val poolLengthM: Double? = null,
)

sealed interface FitResult {
    data class Ok(val parsed: ParsedFit) : FitResult
    /** Soubor je nedůvěryhodný vstup: při chybě se zachová pro diagnostiku, ale nerozbije historii. */
    data class Invalid(val reason: String) : FitResult
}

/**
 * Dekódování FIT. Neplatné hodnoty (sentinel hodnoty SDK vrací jako `null`) zůstávají `null`;
 * chybějící GPS se nikdy nepřevádí na 0°/0°.
 */
object FitImporter {
    const val MAX_BYTES = 50 * 1024 * 1024
    const val MAX_RECORDS = 500_000
    private const val SEMICIRCLE_TO_DEG = 180.0 / 2147483648.0

    fun parse(bytes: ByteArray): FitResult {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return FitResult.Invalid("Velikost souboru mimo limit")
        return try {
            val probe = Decode()
            if (!probe.isFileFit(ByteArrayInputStream(bytes))) return FitResult.Invalid("Není to FIT soubor")
            if (!Decode().checkFileIntegrity(ByteArrayInputStream(bytes))) return FitResult.Invalid("Selhala kontrola integrity (CRC)")

            val points = ArrayList<TrackPoint>()
            val laps = ArrayList<ParsedLap>()
            val strengthSets = ArrayList<StrengthSet>()
            var session: SessionMesg? = null
            var overflow = false

            val decode = Decode()
            val broadcaster = MesgBroadcaster(decode)
            broadcaster.addListener(com.garmin.fit.RecordMesgListener { m: RecordMesg ->
                if (points.size >= MAX_RECORDS) { overflow = true; return@RecordMesgListener }
                val ts = m.timestamp ?: return@RecordMesgListener
                val stepLen = m.stepLength?.toDouble()?.let { if (it > 10.0) it / 1000.0 else it }
                val vertOsc = m.verticalOscillation?.toDouble()?.let { if (it > 25.0) it / 10.0 else it }
                val vertRatio = m.verticalRatio?.toDouble()
                val stanceTime = m.stanceTime?.toDouble()
                val stanceBal = m.stanceTimeBalance?.toDouble()
                val stam = (m.getFieldValue(140) as? Number)?.toDouble()
                val stamPot = (m.getFieldValue(141) as? Number)?.toDouble()
                val perfCond = (m.getFieldValue(85) as? Number)?.toInt()

                points += TrackPoint(
                    tMs = ts.date.time,
                    lat = m.positionLat?.let { it * SEMICIRCLE_TO_DEG },
                    lon = m.positionLong?.let { it * SEMICIRCLE_TO_DEG },
                    elevationM = (m.enhancedAltitude ?: m.altitude)?.toDouble(),
                    heartRate = m.heartRate?.toInt(),
                    speedMps = (m.enhancedSpeed ?: m.speed)?.toDouble(),
                    cadence = m.cadence?.toInt(),
                    powerW = m.power,
                    distanceM = m.distance?.toDouble(),
                    strideLengthM = stepLen,
                    verticalOscillationCm = vertOsc,
                    verticalRatioPct = vertRatio,
                    groundContactTimeMs = stanceTime,
                    groundContactBalancePct = stanceBal,
                    staminaPct = stam,
                    staminaPotentialPct = stamPot,
                    performanceCondition = perfCond,
                )
            })
            broadcaster.addListener(com.garmin.fit.LapMesgListener { m: LapMesg ->
                val start = m.startTime?.date?.time ?: return@LapMesgListener
                val dur = (m.totalTimerTime ?: m.totalElapsedTime)?.let { (it * 1000).toLong() } ?: 0L
                val sp = m.sport
                val cadScale = if (sp == com.garmin.fit.Sport.RUNNING || sp == com.garmin.fit.Sport.WALKING || sp == com.garmin.fit.Sport.HIKING) 2.0 else 1.0
                laps += ParsedLap(
                    startMs = start,
                    endMs = start + dur,
                    distanceM = m.totalDistance?.toDouble(),
                    startLat = m.startPositionLat?.let { it * SEMICIRCLE_TO_DEG },
                    startLon = m.startPositionLong?.let { it * SEMICIRCLE_TO_DEG },
                    avgHeartRate = m.avgHeartRate?.toInt(),
                    maxHeartRate = m.maxHeartRate?.toInt(),
                    avgSpeedMps = (m.enhancedAvgSpeed ?: m.avgSpeed)?.toDouble(),
                    maxSpeedMps = (m.enhancedMaxSpeed ?: m.maxSpeed)?.toDouble(),
                    ascentM = m.totalAscent?.toDouble(),
                    descentM = m.totalDescent?.toDouble(),
                    avgCadence = m.avgCadence?.let { it.toDouble() * cadScale },
                    avgPowerW = m.avgPower,
                    caloriesKcal = m.totalCalories?.toDouble(),
                )
            })
            broadcaster.addListener(com.garmin.fit.SetMesgListener { m: SetMesg ->
                val setTypeStr = when (m.setType) {
                    com.garmin.fit.SetType.ACTIVE -> "ACTIVE"
                    com.garmin.fit.SetType.REST -> "REST"
                    else -> if ((m.repetitions ?: 0) > 0) "ACTIVE" else "REST"
                }
                val catStr = if (m.numCategory > 0) {
                    m.getCategory(0)?.let { com.garmin.fit.ExerciseCategory.getStringFromValue(it) }?.takeIf { it.isNotBlank() }
                } else null

                strengthSets += StrengthSet(
                    index = strengthSets.size + 1,
                    setType = setTypeStr,
                    reps = m.repetitions?.takeIf { it > 0 },
                    weightKg = m.weight?.toDouble()?.takeIf { it > 0.0 },
                    durationS = m.duration?.toDouble()?.takeIf { it > 0.0 },
                    exerciseCategory = catStr,
                    exerciseName = catStr,
                    startUtcMs = m.startTime?.date?.time ?: m.timestamp?.date?.time,
                    wktStepIndex = m.wktStepIndex,
                )
            })
            broadcaster.addListener(com.garmin.fit.SessionMesgListener { session = it })
            broadcaster.run(ByteArrayInputStream(bytes))
            if (overflow) return FitResult.Invalid("Příliš mnoho záznamů")
            if (points.isEmpty() && session == null && strengthSets.isEmpty()) return FitResult.Invalid("Soubor neobsahuje aktivitu")

            points.sortBy { it.tMs }
            val s = session
            val computed = ActivityMath.summarize(points)
            val start = s?.startTime?.date?.time ?: points.firstOrNull()?.tMs ?: strengthSets.firstOrNull()?.startUtcMs ?: return FitResult.Invalid("Chybí čas začátku")
            val baseSport = s?.sport?.let(::mapSport) ?: Sport.OTHER
            val mappedSport = if (baseSport == Sport.OTHER && strengthSets.isNotEmpty()) Sport.STRENGTH else baseSport
            val cadMult = if (mappedSport == Sport.RUNNING || mappedSport == Sport.WALKING || mappedSport == Sport.HIKING) 2.0 else 1.0
            FitResult.Ok(
                ParsedFit(
                    sport = mappedSport,
                    startMs = start,
                    totalTimeS = s?.totalElapsedTime?.toDouble() ?: computed.totalTimeS,
                    movingTimeS = s?.totalTimerTime?.toDouble() ?: computed.movingTimeS.takeIf { points.size > 1 },
                    distanceM = s?.totalDistance?.toDouble() ?: computed.distanceM.takeIf { it > 0 },
                    ascentM = s?.totalAscent?.toDouble() ?: computed.ascentM.takeIf { points.any { p -> p.elevationM != null } },
                    avgHeartRate = s?.avgHeartRate?.toInt() ?: points.mapNotNull { it.heartRate }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                    maxHeartRate = s?.maxHeartRate?.toInt() ?: points.mapNotNull { it.heartRate }.maxOrNull(),
                    points = points,
                    laps = laps,
                    descentM = s?.totalDescent?.toDouble() ?: computed.descentM.takeIf { points.any { p -> p.elevationM != null } },
                    caloriesKcal = s?.totalCalories?.toDouble(),
                    avgSpeedMps = (s?.enhancedAvgSpeed ?: s?.avgSpeed)?.toDouble() ?: points.mapNotNull { it.speedMps }.takeIf { it.isNotEmpty() }?.average(),
                    maxSpeedMps = (s?.enhancedMaxSpeed ?: s?.maxSpeed)?.toDouble() ?: points.mapNotNull { it.speedMps }.maxOrNull(),
                    avgCadence = s?.avgCadence?.let { it.toDouble() * cadMult } ?: points.mapNotNull { it.cadence }.takeIf { it.isNotEmpty() }?.average()?.times(cadMult),
                    maxCadence = s?.maxCadence?.let { it.toDouble() * cadMult } ?: points.mapNotNull { it.cadence }.maxOrNull()?.let { it * cadMult },
                    avgPowerW = s?.avgPower?.toDouble() ?: points.mapNotNull { it.powerW }.takeIf { it.isNotEmpty() }?.average(),
                    maxPowerW = s?.maxPower?.toDouble() ?: points.mapNotNull { it.powerW }.maxOrNull()?.toDouble(),
                    normPowerW = s?.normalizedPower?.toDouble(),
                    aerobicTrainingEffect = s?.totalTrainingEffect?.toDouble(),
                    anaerobicTrainingEffect = s?.totalAnaerobicTrainingEffect?.toDouble(),
                    avgStrideLengthCm = s?.avgStepLength?.let { it.toDouble() / 10.0 },
                    avgVerticalOscillationCm = s?.avgVerticalOscillation?.let { it.toDouble() / 10.0 },
                    avgGroundContactTimeMs = s?.avgStanceTime?.toDouble(),
                    avgVerticalRatioPct = s?.avgVerticalRatio?.toDouble(),
                    steps = s?.totalStrides?.let { it.toInt() * 2 },
                    strengthSets = strengthSets,
                    totalReps = s?.totalReps?.toInt() ?: strengthSets.filter { it.isActive }.sumOf { it.reps ?: 0 }.takeIf { it > 0 },
                    totalStrokes = s?.totalStrokes?.toInt(),
                    avgSwolf = s?.avgSwolf,
                    poolLengthM = s?.poolLength?.toDouble(),
                ),
            )
        } catch (e: FitRuntimeException) {
            FitResult.Invalid("Chyba dekódování FIT: ${e.message}")
        } catch (e: RuntimeException) {
            FitResult.Invalid("Neočekávaná chyba parseru: ${e.javaClass.simpleName}")
        }
    }

    private fun mapSport(s: com.garmin.fit.Sport): Sport = when (s) {
        com.garmin.fit.Sport.RUNNING -> Sport.RUNNING
        com.garmin.fit.Sport.CYCLING, com.garmin.fit.Sport.E_BIKING -> Sport.CYCLING
        com.garmin.fit.Sport.WALKING -> Sport.WALKING
        com.garmin.fit.Sport.HIKING, com.garmin.fit.Sport.MOUNTAINEERING -> Sport.HIKING
        com.garmin.fit.Sport.SWIMMING -> Sport.SWIMMING
        com.garmin.fit.Sport.TRAINING, com.garmin.fit.Sport.FITNESS_EQUIPMENT, com.garmin.fit.Sport.HIIT -> Sport.STRENGTH
        else -> Sport.OTHER
    }
}
