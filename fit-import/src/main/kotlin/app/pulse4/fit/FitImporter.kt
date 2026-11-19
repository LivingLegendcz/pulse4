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

data class FitSessionIndex(
    val sport: Sport,
    val startMs: Long,
)

sealed interface FitResult {
    data class Ok(
        val parsed: ParsedFit,
        val sessions: List<ParsedFit> = listOf(parsed),
    ) : FitResult
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

            val rawPoints = ArrayList<Pair<TrackPoint, Double?>>() // point + raw fractional cadence
            val rawLaps = ArrayList<Pair<LapMesg, ParsedLap>>()
            val strengthSets = ArrayList<StrengthSet>()
            val rawSessions = ArrayList<SessionMesg>()
            var overflow = false

            val decode = Decode()
            val broadcaster = MesgBroadcaster(decode)
            broadcaster.addListener(com.garmin.fit.RecordMesgListener { m: RecordMesg ->
                if (rawPoints.size >= MAX_RECORDS) { overflow = true; return@RecordMesgListener }
                val ts = m.timestamp ?: return@RecordMesgListener
                // RecordMesg.StepLengthFieldNum (85) je délka kroku v mm (nikoli Performance Condition).
                val stepLen = m.stepLength?.toDouble()?.div(1000.0)
                // RecordMesg.VerticalOscillationFieldNum je vždy v mm -> převod na cm (/ 10).
                val vertOsc = m.verticalOscillation?.toDouble()?.div(10.0)
                val vertRatio = m.verticalRatio?.toDouble()
                val stanceTime = m.stanceTime?.toDouble()
                val stanceBal = m.stanceTimeBalance?.toDouble()
                val rawCad = m.cadence?.let { it.toDouble() + (m.fractionalCadence?.toDouble() ?: 0.0) }
                // Nedokumentovaná pole 140/141 (stamina) a 85 (chybně mapované jako perfCond) záměrně nemapujeme.

                rawPoints += TrackPoint(
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
                    staminaPct = null,
                    staminaPotentialPct = null,
                    performanceCondition = null,
                ) to rawCad
            })
            broadcaster.addListener(com.garmin.fit.LapMesgListener { m: LapMesg ->
                val start = m.startTime?.date?.time ?: return@LapMesgListener
                val end = m.timestamp?.date?.time
                    ?: (start + ((m.totalElapsedTime ?: m.totalTimerTime)?.let { (it * 1000).toLong() } ?: 0L))
                rawLaps += m to ParsedLap(
                    startMs = start,
                    endMs = end.coerceAtLeast(start),
                    distanceM = m.totalDistance?.toDouble(),
                    startLat = m.startPositionLat?.let { it * SEMICIRCLE_TO_DEG },
                    startLon = m.startPositionLong?.let { it * SEMICIRCLE_TO_DEG },
                    avgHeartRate = m.avgHeartRate?.toInt(),
                    maxHeartRate = m.maxHeartRate?.toInt(),
                    avgSpeedMps = (m.enhancedAvgSpeed ?: m.avgSpeed)?.toDouble(),
                    maxSpeedMps = (m.enhancedMaxSpeed ?: m.maxSpeed)?.toDouble(),
                    ascentM = m.totalAscent?.toDouble(),
                    descentM = m.totalDescent?.toDouble(),
                    avgCadence = m.avgCadence?.toDouble(),
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
            broadcaster.addListener(com.garmin.fit.SessionMesgListener { rawSessions += it })
            broadcaster.run(ByteArrayInputStream(bytes))
            if (overflow) return FitResult.Invalid("Příliš mnoho záznamů")
            if (rawPoints.isEmpty() && rawSessions.isEmpty() && strengthSets.isEmpty()) return FitResult.Invalid("Soubor neobsahuje aktivitu")

            rawPoints.sortBy { it.first.tMs }

            fun isFootSport(sp: Sport): Boolean =
                sp == Sport.RUNNING || sp == Sport.WALKING || sp == Sport.HIKING

            fun buildParsedFit(
                s: SessionMesg?,
                sessPoints: List<Pair<TrackPoint, Double?>>,
                sessLaps: List<Pair<LapMesg, ParsedLap>>,
                sessStrength: List<StrengthSet>,
            ): ParsedFit? {
                val start = s?.startTime?.date?.time
                    ?: sessPoints.firstOrNull()?.first?.tMs
                    ?: sessStrength.firstOrNull()?.startUtcMs
                    ?: return null
                val baseSport = s?.sport?.let(::mapSport) ?: Sport.OTHER
                val mappedSport = if (baseSport == Sport.OTHER && sessStrength.isNotEmpty()) Sport.STRENGTH else baseSport
                val cadMult = if (isFootSport(mappedSport)) 2.0 else 1.0

                val scaledPoints = sessPoints.map { (pt, rawCad) ->
                    val scaledCad = rawCad?.let { kotlin.math.round(it * cadMult).toInt() }
                    if (scaledCad != pt.cadence) pt.copy(cadence = scaledCad) else pt
                }
                val scaledLaps = sessLaps.map { (lapMesg, lap) ->
                    val lapSport = lapMesg.sport?.let(::mapSport) ?: mappedSport
                    val lapCadMult = if (isFootSport(lapSport)) 2.0 else 1.0
                    val scaledAvgCad = lapMesg.avgCadence?.let { it.toDouble() * lapCadMult }
                    if (scaledAvgCad != lap.avgCadence) lap.copy(avgCadence = scaledAvgCad) else lap
                }

                val computed = ActivityMath.summarize(scaledPoints)
                return ParsedFit(
                    sport = mappedSport,
                    startMs = start,
                    totalTimeS = s?.totalElapsedTime?.toDouble() ?: computed.totalTimeS,
                    movingTimeS = s?.totalTimerTime?.toDouble() ?: computed.movingTimeS.takeIf { scaledPoints.size > 1 },
                    distanceM = s?.totalDistance?.toDouble() ?: computed.distanceM.takeIf { it > 0 },
                    ascentM = s?.totalAscent?.toDouble() ?: computed.ascentM.takeIf { scaledPoints.any { p -> p.elevationM != null } },
                    avgHeartRate = s?.avgHeartRate?.toInt() ?: scaledPoints.mapNotNull { it.heartRate }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                    maxHeartRate = s?.maxHeartRate?.toInt() ?: scaledPoints.mapNotNull { it.heartRate }.maxOrNull(),
                    points = scaledPoints,
                    laps = scaledLaps,
                    descentM = s?.totalDescent?.toDouble() ?: computed.descentM.takeIf { scaledPoints.any { p -> p.elevationM != null } },
                    caloriesKcal = s?.totalCalories?.toDouble(),
                    avgSpeedMps = (s?.enhancedAvgSpeed ?: s?.avgSpeed)?.toDouble() ?: scaledPoints.mapNotNull { it.speedMps }.takeIf { it.isNotEmpty() }?.average(),
                    maxSpeedMps = (s?.enhancedMaxSpeed ?: s?.maxSpeed)?.toDouble() ?: scaledPoints.mapNotNull { it.speedMps }.maxOrNull(),
                    avgCadence = s?.avgCadence?.let { it.toDouble() * cadMult } ?: scaledPoints.mapNotNull { it.cadence }.takeIf { it.isNotEmpty() }?.average(),
                    maxCadence = s?.maxCadence?.let { it.toDouble() * cadMult } ?: scaledPoints.mapNotNull { it.cadence }.maxOrNull()?.toDouble(),
                    avgPowerW = s?.avgPower?.toDouble() ?: scaledPoints.mapNotNull { it.powerW }.takeIf { it.isNotEmpty() }?.average(),
                    maxPowerW = s?.maxPower?.toDouble() ?: scaledPoints.mapNotNull { it.powerW }.maxOrNull()?.toDouble(),
                    normPowerW = s?.normalizedPower?.toDouble(),
                    aerobicTrainingEffect = s?.totalTrainingEffect?.toDouble(),
                    anaerobicTrainingEffect = s?.totalAnaerobicTrainingEffect?.toDouble(),
                    avgStrideLengthCm = s?.avgStepLength?.let { it.toDouble() / 10.0 },
                    avgVerticalOscillationCm = s?.avgVerticalOscillation?.let { it.toDouble() / 10.0 },
                    avgGroundContactTimeMs = s?.avgStanceTime?.toDouble(),
                    avgVerticalRatioPct = s?.avgVerticalRatio?.toDouble(),
                    steps = if (isFootSport(mappedSport)) s?.totalStrides?.let { it.toInt() * 2 } else null,
                    strengthSets = sessStrength,
                    totalReps = s?.totalReps?.toInt() ?: sessStrength.filter { it.isActive }.sumOf { it.reps ?: 0 }.takeIf { it > 0 },
                    totalStrokes = s?.totalStrokes?.toInt(),
                    avgSwolf = s?.avgSwolf,
                    poolLengthM = s?.poolLength?.toDouble(),
                )
            }

            val parsedSessions = if (rawSessions.size <= 1) {
                listOfNotNull(buildParsedFit(rawSessions.firstOrNull(), rawPoints, rawLaps, strengthSets))
            } else {
                val sorted = rawSessions.sortedBy { it.startTime?.date?.time ?: 0L }
                sorted.mapIndexedNotNull { idx, s ->
                    val sStart = s.startTime?.date?.time ?: return@mapIndexedNotNull null
                    val nextStart = sorted.getOrNull(idx + 1)?.startTime?.date?.time
                    val sEnd = s.timestamp?.date?.time
                        ?: (sStart + ((s.totalElapsedTime ?: s.totalTimerTime)?.let { (it * 1000).toLong() } ?: 0L))
                    val upperBound = if (nextStart != null && nextStart > sStart) minOf(sEnd, nextStart - 1) else sEnd
                    val pts = rawPoints.filter { it.first.tMs in sStart..upperBound }
                    val lps = rawLaps.filter { it.second.startMs in (sStart - 1000)..upperBound }
                    val strs = strengthSets.filter { st -> val t = st.startUtcMs; t != null && t in sStart..upperBound }
                    buildParsedFit(s, pts, lps, strs)
                }
            }
            val primary = parsedSessions.firstOrNull() ?: return FitResult.Invalid("Chybí čas začátku")
            FitResult.Ok(parsed = primary, sessions = parsedSessions)
        } catch (e: FitRuntimeException) {
            FitResult.Invalid("Chyba dekódování FIT: ${e.message}")
        } catch (e: RuntimeException) {
            FitResult.Invalid("Neočekávaná chyba parseru: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Lehký průchod hlavičkou a `SessionMesg` bez načítání bodů trasy, kol ani sérií (R3-2).
     */
    fun parseSessionIndex(bytes: ByteArray): List<FitSessionIndex> {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return emptyList()
        return try {
            val probe = Decode()
            if (!probe.isFileFit(ByteArrayInputStream(bytes))) return emptyList()
            val sessions = ArrayList<FitSessionIndex>(2)
            val decode = Decode()
            val broadcaster = MesgBroadcaster(decode)
            broadcaster.addListener(com.garmin.fit.SessionMesgListener { m: SessionMesg ->
                val startMs = m.startTime?.date?.time ?: return@SessionMesgListener
                val sport = m.sport?.let(::mapSport) ?: Sport.OTHER
                sessions += FitSessionIndex(sport = sport, startMs = startMs)
            })
            broadcaster.run(ByteArrayInputStream(bytes))
            sessions.sortBy { it.startMs }
            sessions
        } catch (_: FitRuntimeException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
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
