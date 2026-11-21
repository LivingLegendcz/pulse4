package app.pulse4.withings

import app.pulse4.domain.Activity
import app.pulse4.domain.ImportKey
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Quality
import app.pulse4.domain.Sport
import app.pulse4.domain.TimeRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Datové modely, parsery a mappery pro hodinky a spánkové senzory Withings
 * (ScanWatch, ScanWatch 2, Nova, Steel HR, Sleep Analyzer) podle oficiálního
 * Withings Public API v2:
 * - `v2/measure?action=getactivity` (denní aktivita: kroky, kalorie, vzdálenost, tep),
 * - `v2/measure?action=getworkouts` (jednotlivé tréninky / sportovní aktivity),
 * - `v2/sleep?action=getsummary` (spánkové souhrny: fáze, skóre, tep, dechová frekvence).
 *
 * POZNÁMKA: Webové `scalews.withings.com/cgi-bin/` a oficiální OAuth `wbsapi.withings.net`
 * sdílejí stejnou JSON obálku (`status`, `body.activities` / `body.series`). Chybějící nebo
 * prázdné odpovědi se vždy berou bezpečně jako prázdný seznam (uživatel má jen váhu bez hodinek).
 */
@Serializable
data class WithingsDailyActivity(
    val date: String,
    val timezone: String? = null,
    val deviceId: String? = null,
    val steps: Int? = null,
    val distanceM: Double? = null,
    val elevationM: Double? = null,
    val caloriesKcal: Double? = null,
    val totalCaloriesKcal: Double? = null,
    val softDurationS: Int? = null,
    val moderateDurationS: Int? = null,
    val intenseDurationS: Int? = null,
    val activeDurationS: Int? = null,
    val hrAverage: Int? = null,
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val hrZone0S: Int? = null,
    val hrZone1S: Int? = null,
    val hrZone2S: Int? = null,
    val hrZone3S: Int? = null,
)

@Serializable
data class WithingsSleepSummary(
    val id: Long,
    val date: String,
    val startDateSec: Long,
    val endDateSec: Long,
    val timezone: String? = null,
    val model: Int? = null,
    val deviceId: String? = null,
    val totalSleepTimeS: Int? = null,
    val totalTimeInBedS: Int? = null,
    val deepSleepDurationS: Int? = null,
    val lightSleepDurationS: Int? = null,
    val remSleepDurationS: Int? = null,
    val awakeDurationS: Int? = null,
    val wakeupCount: Int? = null,
    val sleepScore: Int? = null,
    val sleepEfficiency: Int? = null,
    val hrAverage: Int? = null,
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val rrAverage: Int? = null,
    val rrMin: Int? = null,
    val rrMax: Int? = null,
    val snoringDurationS: Int? = null,
    val apneaHypopneaIndex: Double? = null,
)

@Serializable
data class WithingsWorkout(
    val id: Long,
    val category: Int,
    val startDateSec: Long,
    val endDateSec: Long,
    val date: String? = null,
    val timezone: String? = null,
    val model: Int? = null,
    val deviceId: String? = null,
    val caloriesKcal: Double? = null,
    val manualCaloriesKcal: Double? = null,
    val effectiveDurationS: Double? = null,
    val pauseDurationS: Double? = null,
    val intensity: Int? = null,
    val steps: Int? = null,
    val distanceM: Double? = null,
    val manualDistanceM: Double? = null,
    val elevationM: Double? = null,
    val hrAverage: Int? = null,
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val hrZone0S: Double? = null,
    val hrZone1S: Double? = null,
    val hrZone2S: Double? = null,
    val hrZone3S: Double? = null,
    val spo2Average: Double? = null,
    val poolLaps: Int? = null,
    val strokes: Int? = null,
    val poolLengthM: Double? = null,
)

object WithingsWatchFields {
    const val ACTIVITY_DATA_FIELDS =
        "steps,distance,elevation,soft,moderate,intense,active,calories,totalcalories,hr_average,hr_min,hr_max,hr_zone_0,hr_zone_1,hr_zone_2,hr_zone_3"
    const val WORKOUT_DATA_FIELDS =
        "calories,effduration,intensity,manual_distance,manual_calories,hr_average,hr_min,hr_max,hr_zone_0,hr_zone_1,hr_zone_2,hr_zone_3,pause_duration,algo_pause_duration,spo2_average,steps,distance,elevation,pool_laps,strokes,pool_length"
    const val SLEEP_DATA_FIELDS =
        "nb_rem_episodes,sleep_efficiency,sleep_latency,total_sleep_time,total_timeinbed,wakeup_latency,waso,apnea_hypopnea_index,asleepduration,deepsleepduration,durationtosleep,durationtowakeup,hr_average,hr_max,hr_min,lightsleepduration,remsleepduration,rr_average,rr_max,rr_min,sleep_score,snoring,snoringepisodecount,wakeupcount,wakeupduration"
}

/**
 * Převod oficiálních číselných kategorií Withings (`v2/measure?action=getworkouts` -> `category`)
 * na doménový [Sport] a český název aktivity.
 */
object WithingsWorkoutCategory {
    fun toSport(category: Int): Sport = when (category) {
        1, 307 -> Sport.WALKING
        2, 308 -> Sport.RUNNING
        3 -> Sport.HIKING
        5, 6, 272 -> Sport.CYCLING
        7 -> Sport.SWIMMING
        16, 17, 19 -> Sport.STRENGTH
        else -> Sport.OTHER
    }

    fun czechTitle(category: Int): String = when (category) {
        1 -> "Chůze"
        2 -> "Běh"
        3 -> "Turistika"
        4 -> "Bruslení"
        5 -> "BMX"
        6 -> "Cyklistika"
        7 -> "Plavání"
        8 -> "Surfování"
        9 -> "Kitesurfing"
        10 -> "Windsurfing"
        11 -> "Bodyboarding"
        12 -> "Tenis"
        13 -> "Stolní tenis"
        14 -> "Squash"
        15 -> "Badminton"
        16 -> "Posilování"
        17 -> "Kalistenika"
        18 -> "Eliptický trenažér"
        19 -> "Pilates"
        20 -> "Basketbal"
        21 -> "Fotbal"
        22 -> "Americký fotbal"
        23 -> "Ragby"
        24 -> "Volejbal"
        25 -> "Vodní pólo"
        26 -> "Jízda na koni"
        27 -> "Golf"
        28 -> "Jóga"
        29 -> "Tanec"
        30 -> "Box"
        31 -> "Šerm"
        32 -> "Zápas"
        33 -> "Bojová umění"
        34 -> "Lyžování"
        35 -> "Snowboarding"
        36 -> "Ostatní aktivita"
        187 -> "Veslování"
        188 -> "Zumba"
        191 -> "Baseball"
        192 -> "Házená"
        193 -> "Pozemní hokej"
        194 -> "Lední hokej"
        195 -> "Lezení"
        196 -> "Lední bruslení"
        272 -> "Indoor cyklistika"
        307 -> "Chůze uvnitř"
        308 -> "Běžecký pás"
        else -> "Withings aktivita (#$category)"
    }
}

object WithingsWatchParser {
    fun parseDailyActivities(body: JsonObject): List<WithingsDailyActivity> {
        val arr = (body["activities"] as? JsonArray)
            ?: (body["series"] as? JsonArray)
            ?: return emptyList()
        return arr.mapNotNull { elem ->
            val o = elem as? JsonObject ?: return@mapNotNull null
            if (o["is_missing"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val date = o["date"]?.jsonPrimitive?.contentOrNull?.take(10)
                ?.takeIf { it.matches(DATE_REGEX) }
                ?: return@mapNotNull null
            val data = (o["data"] as? JsonObject) ?: o
            WithingsDailyActivity(
                date = date,
                timezone = o["timezone"]?.jsonPrimitive?.contentOrNull,
                deviceId = o["deviceid"]?.jsonPrimitive?.contentOrNull
                    ?: o["hash_deviceid"]?.jsonPrimitive?.contentOrNull,
                steps = data["steps"]?.jsonPrimitive?.intOrNull,
                distanceM = data["distance"]?.jsonPrimitive?.doubleOrNull,
                elevationM = data["elevation"]?.jsonPrimitive?.doubleOrNull,
                caloriesKcal = data["calories"]?.jsonPrimitive?.doubleOrNull,
                totalCaloriesKcal = data["totalcalories"]?.jsonPrimitive?.doubleOrNull,
                softDurationS = data["soft"]?.jsonPrimitive?.intOrNull,
                moderateDurationS = data["moderate"]?.jsonPrimitive?.intOrNull,
                intenseDurationS = data["intense"]?.jsonPrimitive?.intOrNull,
                activeDurationS = data["active"]?.jsonPrimitive?.intOrNull,
                hrAverage = data["hr_average"]?.jsonPrimitive?.intOrNull,
                hrMin = data["hr_min"]?.jsonPrimitive?.intOrNull,
                hrMax = data["hr_max"]?.jsonPrimitive?.intOrNull,
                hrZone0S = data["hr_zone_0"]?.jsonPrimitive?.intOrNull,
                hrZone1S = data["hr_zone_1"]?.jsonPrimitive?.intOrNull,
                hrZone2S = data["hr_zone_2"]?.jsonPrimitive?.intOrNull,
                hrZone3S = data["hr_zone_3"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    fun parseSleepSummaries(body: JsonObject): List<WithingsSleepSummary> {
        val arr = (body["series"] as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { elem ->
            val o = elem as? JsonObject ?: return@mapNotNull null
            val start = o["startdate"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val end = o["enddate"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (end < start) return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.longOrNull ?: start
            val tz = o["timezone"]?.jsonPrimitive?.contentOrNull
            val date = o["date"]?.jsonPrimitive?.contentOrNull?.take(10)
                ?.takeIf { it.matches(DATE_REGEX) }
                ?: TimeRules.localDate(end * 1000L, validZone(tz, "UTC")).toString()
            val data = (o["data"] as? JsonObject) ?: o
            val deep = data["deepsleepduration"]?.jsonPrimitive?.intOrNull
            val light = data["lightsleepduration"]?.jsonPrimitive?.intOrNull
            val rem = data["remsleepduration"]?.jsonPrimitive?.intOrNull
            val stageSum = listOfNotNull(deep, light, rem).takeIf { it.isNotEmpty() }?.sum()
            val totalSleep = data["total_sleep_time"]?.jsonPrimitive?.intOrNull
                ?: data["asleepduration"]?.jsonPrimitive?.intOrNull
                ?: stageSum
            val awake = data["waso"]?.jsonPrimitive?.intOrNull
                ?: data["wakeupduration"]?.jsonPrimitive?.intOrNull
            WithingsSleepSummary(
                id = id,
                date = date,
                startDateSec = start,
                endDateSec = end,
                timezone = tz,
                model = o["model"]?.jsonPrimitive?.intOrNull,
                deviceId = o["deviceid"]?.jsonPrimitive?.contentOrNull
                    ?: o["hash_deviceid"]?.jsonPrimitive?.contentOrNull,
                totalSleepTimeS = totalSleep,
                totalTimeInBedS = data["total_timeinbed"]?.jsonPrimitive?.intOrNull,
                deepSleepDurationS = deep,
                lightSleepDurationS = light,
                remSleepDurationS = rem,
                awakeDurationS = awake,
                wakeupCount = data["wakeupcount"]?.jsonPrimitive?.intOrNull,
                sleepScore = data["sleep_score"]?.jsonPrimitive?.intOrNull,
                sleepEfficiency = data["sleep_efficiency"]?.jsonPrimitive?.intOrNull,
                hrAverage = data["hr_average"]?.jsonPrimitive?.intOrNull,
                hrMin = data["hr_min"]?.jsonPrimitive?.intOrNull,
                hrMax = data["hr_max"]?.jsonPrimitive?.intOrNull,
                rrAverage = data["rr_average"]?.jsonPrimitive?.intOrNull,
                rrMin = data["rr_min"]?.jsonPrimitive?.intOrNull,
                rrMax = data["rr_max"]?.jsonPrimitive?.intOrNull,
                snoringDurationS = data["snoring"]?.jsonPrimitive?.intOrNull,
                apneaHypopneaIndex = data["apnea_hypopnea_index"]?.jsonPrimitive?.doubleOrNull,
            )
        }
    }

    fun parseWorkouts(body: JsonObject): List<WithingsWorkout> {
        val arr = (body["series"] as? JsonArray)
            ?: (body["activities"] as? JsonArray)
            ?: return emptyList()
        return arr.mapNotNull { elem ->
            val o = elem as? JsonObject ?: return@mapNotNull null
            val start = o["startdate"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val end = o["enddate"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            val category = o["category"]?.jsonPrimitive?.intOrNull ?: 36
            val id = o["id"]?.jsonPrimitive?.longOrNull ?: start
            val data = (o["data"] as? JsonObject) ?: o
            val pause = data["pause_duration"]?.jsonPrimitive?.doubleOrNull
                ?: data["algo_pause_duration"]?.jsonPrimitive?.doubleOrNull
            WithingsWorkout(
                id = id,
                category = category,
                startDateSec = start,
                endDateSec = end,
                date = o["date"]?.jsonPrimitive?.contentOrNull?.take(10),
                timezone = o["timezone"]?.jsonPrimitive?.contentOrNull,
                model = o["model"]?.jsonPrimitive?.intOrNull,
                deviceId = o["deviceid"]?.jsonPrimitive?.contentOrNull
                    ?: o["hash_deviceid"]?.jsonPrimitive?.contentOrNull,
                caloriesKcal = data["calories"]?.jsonPrimitive?.doubleOrNull,
                manualCaloriesKcal = data["manual_calories"]?.jsonPrimitive?.doubleOrNull,
                effectiveDurationS = data["effduration"]?.jsonPrimitive?.doubleOrNull,
                pauseDurationS = pause,
                intensity = data["intensity"]?.jsonPrimitive?.intOrNull,
                steps = data["steps"]?.jsonPrimitive?.intOrNull,
                distanceM = data["distance"]?.jsonPrimitive?.doubleOrNull,
                manualDistanceM = data["manual_distance"]?.jsonPrimitive?.doubleOrNull,
                elevationM = data["elevation"]?.jsonPrimitive?.doubleOrNull,
                hrAverage = data["hr_average"]?.jsonPrimitive?.intOrNull,
                hrMin = data["hr_min"]?.jsonPrimitive?.intOrNull,
                hrMax = data["hr_max"]?.jsonPrimitive?.intOrNull,
                hrZone0S = data["hr_zone_0"]?.jsonPrimitive?.doubleOrNull,
                hrZone1S = data["hr_zone_1"]?.jsonPrimitive?.doubleOrNull,
                hrZone2S = data["hr_zone_2"]?.jsonPrimitive?.doubleOrNull,
                hrZone3S = data["hr_zone_3"]?.jsonPrimitive?.doubleOrNull,
                spo2Average = data["spo2_average"]?.jsonPrimitive?.doubleOrNull,
                poolLaps = data["pool_laps"]?.jsonPrimitive?.intOrNull,
                strokes = data["strokes"]?.jsonPrimitive?.intOrNull,
                poolLengthM = data["pool_length"]?.jsonPrimitive?.doubleOrNull,
            )
        }
    }

    internal fun validZone(tz: String?, fallback: String): String =
        tz?.takeIf { runCatching { ZoneId.of(it) }.isSuccess } ?: fallback

    private val DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
}

object WithingsWatchMapper {
    fun mapDailyActivities(
        activities: List<WithingsDailyActivity>,
        userId: String,
        defaultZone: String,
    ): List<MetricObservation> {
        val out = ArrayList<MetricObservation>()
        for (a in activities) {
            val zone = WithingsWatchParser.validZone(a.timezone, defaultZone)
            val localDate = runCatching { LocalDate.parse(a.date) }.getOrNull() ?: continue
            val startMs = localDate.atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
            val origin = a.deviceId?.let { "withings:activity:$it" } ?: "withings:activity"

            fun emit(suffix: String, type: MetricType, value: Double) {
                if (!value.isFinite()) return
                val ext = "act:${a.date}:$suffix"
                out += MetricObservation(
                    id = ImportKey.of(Provider.WITHINGS_API, userId, ext, listOf(type.name, a.date)),
                    provider = Provider.WITHINGS_API,
                    accountId = userId,
                    externalId = ext,
                    type = type,
                    startUtcMs = startMs,
                    endUtcMs = null,
                    value = value,
                    zoneId = zone,
                    localDate = a.date,
                    quality = Quality.OK,
                    sourceVersion = 1,
                    origin = origin,
                )
            }

            a.steps?.takeIf { it > 0 }?.let { emit("steps", MetricType.STEPS, it.toDouble()) }
            a.hrAverage?.takeIf { it in 30..240 }?.let { emit("hr", MetricType.HEART_RATE, it.toDouble()) }
        }
        return out.distinctBy { it.id }
    }

    fun mapSleepSummaries(
        summaries: List<WithingsSleepSummary>,
        userId: String,
        defaultZone: String,
    ): List<MetricObservation> {
        val out = ArrayList<MetricObservation>()
        for (s in summaries) {
            val zone = WithingsWatchParser.validZone(s.timezone, defaultZone)
            val startMs = s.startDateSec * 1000L
            val endMs = s.endDateSec * 1000L
            val origin = buildString {
                append("withings:sleep")
                s.model?.let { append(":model=").append(it) }
                s.deviceId?.takeIf { it.isNotBlank() }?.let { append(':').append(it) }
            }

            fun emit(suffix: String, type: MetricType, value: Double) {
                if (!value.isFinite()) return
                val ext = "sleep:${s.id}:$suffix"
                out += MetricObservation(
                    id = ImportKey.of(Provider.WITHINGS_API, userId, ext, listOf(type.name, startMs.toString())),
                    provider = Provider.WITHINGS_API,
                    accountId = userId,
                    externalId = ext,
                    type = type,
                    startUtcMs = startMs,
                    endUtcMs = endMs,
                    value = value,
                    zoneId = zone,
                    localDate = s.date,
                    quality = Quality.OK,
                    sourceVersion = 1,
                    origin = origin,
                )
            }

            s.totalSleepTimeS?.takeIf { it > 0 }?.let { emit("dur", MetricType.SLEEP_DURATION, it.toDouble()) }
            s.sleepScore?.takeIf { it in 1..100 }?.let { emit("score", MetricType.SLEEP_SCORE, it.toDouble()) }
            s.deepSleepDurationS?.takeIf { it >= 0 }?.let { emit("deep", MetricType.SLEEP_DEEP, it.toDouble()) }
            s.lightSleepDurationS?.takeIf { it >= 0 }?.let { emit("light", MetricType.SLEEP_LIGHT, it.toDouble()) }
            s.remSleepDurationS?.takeIf { it >= 0 }?.let { emit("rem", MetricType.SLEEP_REM, it.toDouble()) }
            s.awakeDurationS?.takeIf { it >= 0 }?.let { emit("awake", MetricType.SLEEP_AWAKE, it.toDouble()) }
            s.wakeupCount?.takeIf { it >= 0 }?.let { emit("wakecnt", MetricType.SLEEP_AWAKE_COUNT, it.toDouble()) }
            s.rrAverage?.takeIf { it in 5..60 }?.let { emit("rr", MetricType.RESPIRATION, it.toDouble()) }
            val rhr = s.hrMin?.takeIf { it in 30..140 } ?: s.hrAverage?.takeIf { it in 30..140 }
            rhr?.let { emit("rhr", MetricType.RESTING_HEART_RATE, it.toDouble()) }
        }
        return out.distinctBy { it.id }
    }

    fun mapWorkouts(
        workouts: List<WithingsWorkout>,
        userId: String,
        defaultZone: String,
    ): Pair<List<Activity>, List<MetricObservation>> {
        val activities = ArrayList<Activity>(workouts.size)
        val extraObs = ArrayList<MetricObservation>()
        for (w in workouts) {
            val zone = WithingsWatchParser.validZone(w.timezone, defaultZone)
            val startMs = w.startDateSec * 1000L
            val endMs = w.endDateSec * 1000L
            val totalS = (w.endDateSec - w.startDateSec).coerceAtLeast(1L).toDouble()
            val pauseS = w.pauseDurationS?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val movingS = w.effectiveDurationS?.takeIf { it.isFinite() && it > 0.0 }
                ?: (totalS - pauseS).takeIf { it > 0.0 }
                ?: totalS
            val distanceM = w.distanceM?.takeIf { it.isFinite() && it > 0.0 }
                ?: w.manualDistanceM?.takeIf { it.isFinite() && it > 0.0 }
            val caloriesKcal = w.caloriesKcal?.takeIf { it.isFinite() && it > 0.0 }
                ?: w.manualCaloriesKcal?.takeIf { it.isFinite() && it > 0.0 }
            val avgSpeed = if (distanceM != null && movingS > 0.0) distanceM / movingS else null
            val zones = listOf(
                w.hrZone0S?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                w.hrZone1S?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                w.hrZone2S?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                w.hrZone3S?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
            ).takeIf { list -> list.any { it > 0.0 } }.orEmpty()

            activities += Activity(
                id = "withings-${w.id}",
                provider = Provider.WITHINGS_API,
                accountId = userId,
                externalId = w.id.toString(),
                sport = WithingsWorkoutCategory.toSport(w.category),
                startUtcMs = startMs,
                zoneId = zone,
                totalTimeS = totalS,
                movingTimeS = movingS,
                distanceM = distanceM,
                ascentM = w.elevationM?.takeIf { it.isFinite() && it >= 0.0 },
                avgHeartRate = w.hrAverage?.takeIf { it in 30..240 },
                maxHeartRate = w.hrMax?.takeIf { it in 30..250 },
                title = WithingsWorkoutCategory.czechTitle(w.category),
                tags = setOf("withings"),
                avgSpeedMps = avgSpeed,
                caloriesKcal = caloriesKcal,
                steps = w.steps?.takeIf { it > 0 },
                hrZoneTimesS = zones,
                totalStrokes = w.strokes?.takeIf { it > 0 },
                poolLengthM = w.poolLengthM?.takeIf { it.isFinite() && it > 0.0 },
            )

            w.spo2Average?.takeIf { it.isFinite() && it in 50.0..100.0 }?.let { spo2 ->
                val ext = "wkt:${w.id}:spo2"
                val date = w.date ?: TimeRules.localDate(startMs, zone).toString()
                extraObs += MetricObservation(
                    id = ImportKey.of(Provider.WITHINGS_API, userId, ext, listOf(MetricType.SPO2.name, startMs.toString())),
                    provider = Provider.WITHINGS_API,
                    accountId = userId,
                    externalId = ext,
                    type = MetricType.SPO2,
                    startUtcMs = startMs,
                    endUtcMs = endMs,
                    value = spo2,
                    zoneId = zone,
                    localDate = date,
                    quality = Quality.OK,
                    sourceVersion = 1,
                    origin = w.deviceId?.let { "withings:workout:$it" } ?: "withings:workout",
                )
            }
        }
        return activities.distinctBy { it.id } to extraObs.distinctBy { it.id }
    }

    internal fun formatYmd(epochMs: Long, zoneId: String): String {
        val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of("UTC"))
        return Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()
    }
}
