package app.pulse4.android.debug

import android.content.Context
import app.pulse4.android.data.ContactRow
import app.pulse4.android.data.ReceivedRow
import app.pulse4.android.data.TrackRow
import app.pulse4.android.data.toRow
import app.pulse4.android.di.ServiceLocator
import app.pulse4.domain.Activity
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Sport
import app.pulse4.domain.TrackPoint
import app.pulse4.sharing.ActivityProjection
import app.pulse4.sharing.ChartSeries
import app.pulse4.sharing.Field
import app.pulse4.sharing.Group
import app.pulse4.sharing.RoutePoint
import app.pulse4.sharing.SharedProjection
import app.pulse4.sync.Wire
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Debug-only demo data seeder (moved out of release Services.kt for P0-1).
 */
object DemoSeeder {
    @JvmStatic
    fun seed(context: Context, sl: ServiceLocator, nowMs: Long = System.currentTimeMillis()) {
        val json = Wire.json
        val zone = ZoneId.of(sl.settings.zoneId)
        val today = LocalDate.now(zone)

        val obsList = mutableListOf<app.pulse4.android.data.ObservationRow>()
        val hrvBase = 55.0
        val rhrBase = 49.0
        val weightBase = 75.8

        for (i in 13 downTo 0) {
            val d = today.minusDays(i.toLong())
            val dStr = d.toString()
            val dayStartMs = d.atStartOfDay(zone).toInstant().toEpochMilli()

            val hrvVal = (hrvBase + sin(i * 0.9) * 6.5 + (if (i == 0) 4.0 else 0.0)).coerceIn(42.0, 75.0)
            obsList.add(
                MetricObservation(
                    id = "demo-hrv-$dStr",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "demo-hrv-$dStr",
                    type = MetricType.HRV,
                    startUtcMs = dayStartMs + 6 * 3600_000L,
                    endUtcMs = dayStartMs + 6 * 3600_000L,
                    value = hrvVal,
                    zoneId = sl.settings.zoneId,
                    localDate = dStr
                ).toRow()
            )

            val rhrVal = (rhrBase - cos(i * 0.8) * 2.8 + (if (i % 4 == 0) 1.5 else -1.0)).coerceIn(44.0, 58.0)
            obsList.add(
                MetricObservation(
                    id = "demo-rhr-$dStr",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "demo-rhr-$dStr",
                    type = MetricType.RESTING_HEART_RATE,
                    startUtcMs = dayStartMs + 5 * 3600_000L,
                    endUtcMs = dayStartMs + 5 * 3600_000L,
                    value = rhrVal,
                    zoneId = sl.settings.zoneId,
                    localDate = dStr
                ).toRow()
            )

            val sleepHours = 7.5 + sin(i * 1.1) * 0.7 + (if (i == 0) 0.3 else 0.0)
            obsList.add(
                MetricObservation(
                    id = "demo-sleep-$dStr",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "demo-sleep-$dStr",
                    type = MetricType.SLEEP_DURATION,
                    startUtcMs = dayStartMs + 6 * 3600_000L,
                    endUtcMs = dayStartMs + 6 * 3600_000L,
                    value = sleepHours * 3600.0,
                    zoneId = sl.settings.zoneId,
                    localDate = dStr
                ).toRow()
            )

            val sleepScore = (sleepHours / 8.0 * 88.0 + sin(i * 1.4) * 4.0).coerceIn(68.0, 95.0)
            obsList.add(
                MetricObservation(
                    id = "demo-sleepscore-$dStr",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "demo-sleepscore-$dStr",
                    type = MetricType.SLEEP_SCORE,
                    startUtcMs = dayStartMs + 6 * 3600_000L,
                    endUtcMs = dayStartMs + 6 * 3600_000L,
                    value = sleepScore,
                    zoneId = sl.settings.zoneId,
                    localDate = dStr
                ).toRow()
            )

            val bbVal = (84.0 + sin(i * 0.7) * 12.0 + (if (i == 0) 6.0 else 0.0)).coerceIn(55.0, 98.0)
            obsList.add(
                MetricObservation(
                    id = "demo-bb-$dStr",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "demo-bb-$dStr",
                    type = MetricType.BODY_BATTERY,
                    startUtcMs = dayStartMs + 7 * 3600_000L,
                    endUtcMs = dayStartMs + 7 * 3600_000L,
                    value = bbVal,
                    zoneId = sl.settings.zoneId,
                    localDate = dStr
                ).toRow()
            )

            val stepsVal = (8800.0 + cos(i * 1.2) * 2600.0 + (if (i % 3 == 0) 2000.0 else -400.0)).coerceIn(4800.0, 16000.0)
            obsList.add(
                MetricObservation(
                    id = "demo-steps-$dStr",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "demo-steps-$dStr",
                    type = MetricType.STEPS,
                    startUtcMs = dayStartMs + 21 * 3600_000L,
                    endUtcMs = dayStartMs + 21 * 3600_000L,
                    value = stepsVal,
                    zoneId = sl.settings.zoneId,
                    localDate = dStr
                ).toRow()
            )

            val stressVal = (25.0 - sin(i * 0.8) * 8.0).coerceIn(16.0, 45.0)
            obsList.add(
                MetricObservation(
                    id = "demo-stress-$dStr",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "demo-stress-$dStr",
                    type = MetricType.STRESS,
                    startUtcMs = dayStartMs + 20 * 3600_000L,
                    endUtcMs = dayStartMs + 20 * 3600_000L,
                    value = stressVal,
                    zoneId = sl.settings.zoneId,
                    localDate = dStr
                ).toRow()
            )

            if (i % 2 == 0 || i == 0) {
                val wVal = weightBase - (13 - i) * 0.06 + sin(i.toDouble()) * 0.15
                val wRound = round(wVal * 10.0) / 10.0
                obsList.add(
                    MetricObservation(
                        id = "demo-weight-$dStr",
                        provider = Provider.WITHINGS_API,
                        accountId = "withings-demo",
                        externalId = "demo-w-$dStr",
                        type = MetricType.WEIGHT,
                        startUtcMs = dayStartMs + 7 * 3600_000L + 15 * 60_000L,
                        endUtcMs = dayStartMs + 7 * 3600_000L + 15 * 60_000L,
                        value = wRound,
                        zoneId = sl.settings.zoneId,
                        localDate = dStr
                    ).toRow()
                )

                val bfVal = 14.3 - (13 - i) * 0.03
                obsList.add(
                    MetricObservation(
                        id = "demo-bf-$dStr",
                        provider = Provider.WITHINGS_API,
                        accountId = "withings-demo",
                        externalId = "demo-bf-$dStr",
                        type = MetricType.BODY_FAT,
                        startUtcMs = dayStartMs + 7 * 3600_000L + 15 * 60_000L,
                        endUtcMs = dayStartMs + 7 * 3600_000L + 15 * 60_000L,
                        value = round(bfVal * 10.0) / 10.0,
                        zoneId = sl.settings.zoneId,
                        localDate = dStr
                    ).toRow()
                )

                obsList.add(
                    MetricObservation(
                        id = "demo-pwv-$dStr",
                        provider = Provider.WITHINGS_API,
                        accountId = "withings-demo",
                        externalId = "demo-pwv-$dStr",
                        type = MetricType.PULSE_WAVE_VELOCITY,
                        startUtcMs = dayStartMs + 7 * 3600_000L + 15 * 60_000L,
                        endUtcMs = dayStartMs + 7 * 3600_000L + 15 * 60_000L,
                        value = 6.3,
                        zoneId = sl.settings.zoneId,
                        localDate = dStr
                    ).toRow()
                )
            }
        }

        obsList.add(
            MetricObservation(
                id = "demo-ecg-$today",
                provider = Provider.WITHINGS_API,
                accountId = "withings-demo",
                externalId = "demo-ecg-$today",
                type = MetricType.ECG,
                startUtcMs = nowMs - 3600_000L,
                endUtcMs = nowMs - 3600_000L,
                value = 1.0,
                zoneId = sl.settings.zoneId,
                localDate = today.toString()
            ).toRow()
        )

        sl.db.observations().upsert(obsList)

        if (sl.db.activities().count() == 0) {
            runCatching {
                val fitBytes = context.assets.open("sample_activity.fit").use { it.readBytes() }
                if (fitBytes.isNotEmpty()) {
                    sl.importService.importFit(fitBytes, nowMs)
                }
            }

            runCatching {
                val jsonStr = context.assets.open("sample_activities.json").bufferedReader().use { it.readText() }
                val rootArr = Json.parseToJsonElement(jsonStr) as? JsonArray
                rootArr?.take(6)?.forEach { elem ->
                    val obj = elem as? JsonObject ?: return@forEach
                    val actId = obj["activityId"]?.let { (it as? JsonPrimitive)?.content } ?: return@forEach
                    val actKey = "garmin-$actId"
                    if (sl.db.activities().get(actKey) == null) {
                        val act = buildActivityFromSampleJson(
                            id = actKey,
                            accountId = sl.settings.accountId,
                            zoneId = sl.settings.zoneId,
                            extId = actId,
                            obj = obj,
                            nowMs = nowMs
                        )
                        sl.db.activities().upsert(act.toRow(json.encodeToString(Activity.serializer(), act)))
                        val synTrack = synthesizeTrackFromSummary(act)
                        sl.db.activities().upsertTrack(
                            TrackRow(
                                actKey,
                                json.encodeToString(ListSerializer(TrackPoint.serializer()), synTrack)
                            )
                        )
                    }
                }
            }
        }

        runCatching {
            val extraSpecs = listOf(
                Activity(
                    id = "garmin-demo-cycling-gravel",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "2491020301",
                    sport = Sport.CYCLING,
                    startUtcMs = nowMs - 1 * 86_400_000L - 3 * 3600_000L,
                    zoneId = sl.settings.zoneId,
                    totalTimeS = 5420.0,
                    movingTimeS = 5280.0,
                    distanceM = 42650.0,
                    ascentM = 645.0,
                    descentM = 640.0,
                    avgHeartRate = 148,
                    maxHeartRate = 179,
                    title = "Karlštejn Gravel Švih",
                    locationName = "Český kras",
                    tags = setOf("gravel_cycling"),
                    avgSpeedMps = 8.07,
                    maxSpeedMps = 15.8,
                    caloriesKcal = 1140.0,
                    avgCadence = 86.0,
                    avgPowerW = 238.0,
                    maxPowerW = 640.0,
                    normPowerW = 254.0,
                    aerobicTrainingEffect = 3.8,
                    anaerobicTrainingEffect = 2.1,
                    trainingEffectLabel = "TEMPO",
                    trainingLoad = 148.0,
                    bodyBatteryImpact = -34
                ),
                Activity(
                    id = "garmin-demo-hiking-sumava",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "2491020302",
                    sport = Sport.HIKING,
                    startUtcMs = nowMs - 3 * 86_400_000L - 5 * 3600_000L,
                    zoneId = sl.settings.zoneId,
                    totalTimeS = 14800.0,
                    movingTimeS = 13200.0,
                    distanceM = 18400.0,
                    ascentM = 890.0,
                    descentM = 875.0,
                    avgHeartRate = 122,
                    maxHeartRate = 156,
                    title = "Šumava · Hřebenovka Poledník",
                    locationName = "Šumava",
                    tags = setOf("hiking"),
                    avgSpeedMps = 1.39,
                    caloriesKcal = 1320.0,
                    steps = 24600,
                    aerobicTrainingEffect = 3.2,
                    anaerobicTrainingEffect = 0.4,
                    trainingEffectLabel = "AEROBIC_BASE",
                    trainingLoad = 96.0,
                    bodyBatteryImpact = -38
                ),
                Activity(
                    id = "garmin-demo-strength-upper",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "2491020303",
                    sport = Sport.STRENGTH,
                    startUtcMs = nowMs - 2 * 86_400_000L - 2 * 3600_000L,
                    zoneId = sl.settings.zoneId,
                    totalTimeS = 3480.0,
                    movingTimeS = 2420.0,
                    distanceM = null,
                    ascentM = null,
                    avgHeartRate = 128,
                    maxHeartRate = 166,
                    title = "Silový trénink · Fullbody & Core",
                    locationName = "Praha",
                    tags = setOf("strength_training"),
                    caloriesKcal = 465.0,
                    aerobicTrainingEffect = 2.1,
                    anaerobicTrainingEffect = 2.8,
                    trainingEffectLabel = "ANAEROBIC_CAPACITY",
                    trainingLoad = 78.0,
                    totalVolumeKg = 6840.0,
                    totalReps = 142,
                    activeSetsCount = 16,
                    bodyBatteryImpact = -21
                ),
                Activity(
                    id = "garmin-demo-swimming-podoli",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "2491020304",
                    sport = Sport.SWIMMING,
                    startUtcMs = nowMs - 4 * 86_400_000L - 4 * 3600_000L,
                    zoneId = sl.settings.zoneId,
                    totalTimeS = 2160.0,
                    movingTimeS = 1980.0,
                    distanceM = 1600.0,
                    ascentM = null,
                    avgHeartRate = 136,
                    maxHeartRate = 161,
                    title = "Podolí · Kraul vytrvalost 1600 m",
                    locationName = "Praha Podolí",
                    tags = setOf("lap_swimming"),
                    avgSpeedMps = 0.81,
                    caloriesKcal = 510.0,
                    aerobicTrainingEffect = 3.1,
                    anaerobicTrainingEffect = 1.2,
                    trainingEffectLabel = "AEROBIC_BASE",
                    trainingLoad = 84.0,
                    totalStrokes = 1180,
                    avgSwolf = 37,
                    poolLengthM = 50.0,
                    swimStroke = "FREESTYLE"
                )
            )
            var addedAny = false
            for (extra in extraSpecs) {
                if (sl.db.activities().get(extra.id) == null) {
                    sl.db.activities().upsert(extra.toRow(json.encodeToString(Activity.serializer(), extra)))
                    val synTrack = synthesizeTrackFromSummary(extra)
                    sl.db.activities().upsertTrack(
                        TrackRow(
                            extra.id,
                            json.encodeToString(ListSerializer(TrackPoint.serializer()), synTrack)
                        )
                    )
                    addedAny = true
                }
            }
            if (addedAny) {
                app.pulse4.android.ui.ScreenMemoryCache.activitySummaries = null
            }
        }

        if (sl.db.contacts().allNow().isEmpty()) {
            val contactTereza = ContactRow(
                identity = "contact_tereza_garmin",
                displayName = "Tereza (Garmin 265)",
                publicKeyB64 = "dGVyZXphX2dhcm1pbl9wdWJrZXk=",
                fingerprint = "TEREZA-RUN",
                verifiedAtMs = nowMs
            )
            val contactTomas = ContactRow(
                identity = "contact_tomas_withings",
                displayName = "Tomáš (Withings Scan)",
                publicKeyB64 = "dG9tYXNfd2l0aGluZ3NfcHVia2V5",
                fingerprint = "TOMAS-SCAN",
                verifiedAtMs = nowMs
            )
            sl.db.contacts().upsert(contactTereza)
            sl.db.contacts().upsert(contactTomas)

            for (i in 6 downTo 0) {
                val d = today.minusDays(i.toLong())
                val dStr = d.toString()
                val proj = SharedProjection(
                    ownerId = contactTereza.identity,
                    grantId = "grant-tereza",
                    date = dStr,
                    fields = mapOf(
                        Field.WEIGHT to JsonPrimitive(58.4 - (6 - i) * 0.1),
                        Field.SLEEP_DURATION to JsonPrimitive(28800.0 + (i % 3) * 900.0),
                        Field.HRV_DAILY to JsonPrimitive(62.0 + (i % 4) * 2.5),
                        Field.BB_LAST to JsonPrimitive(88.0 + (i % 3) * 4.0),
                        Field.HR_DAILY to JsonPrimitive(48.0 - (i % 2) * 1.0)
                    ),
                    measuredAtUtcMs = nowMs - i * 86_400_000L,
                    producedAtMs = nowMs - i * 86_400_000L
                )
                val payload = json.encodeToString(SharedProjection.serializer(), proj)
                sl.db.inbox().putObject(
                    ReceivedRow(
                        author = contactTereza.identity,
                        objectType = "daily",
                        objectId = dStr,
                        version = 1L,
                        payload = payload
                    )
                )
            }

            val hrPoints = (0 until 40).map { s -> (s * 30_000L) to (148.0 + sin(s / 4.0) * 12.0) }
            val spdPoints = (0 until 40).map { s -> (s * 30_000L) to (3.45 + cos(s / 5.0) * 0.3) }
            val elePoints = (0 until 40).map { s -> (s * 30_000L) to (210.0 + s * 1.1) }
            val routePoints = (0 until 40).map { s ->
                RoutePoint(45.0100 + s * 0.00035, 15.0100 + sin(s / 6.0) * 0.0005)
            }
            val actProj = ActivityProjection(
                activityId = "tereza-trail-run",
                sport = Sport.RUNNING.name,
                localDate = today.toString(),
                startUtcMs = nowMs - 14400_000L,
                totalTimeS = 2160.0,
                movingTimeS = 2110.0,
                distanceM = 7420.0,
                ascentM = 92.0,
                avgHeartRate = 152,
                charts = listOf(
                    ChartSeries("heartRate", hrPoints),
                    ChartSeries("speed", spdPoints),
                    ChartSeries("elevation", elePoints)
                ),
                route = routePoints
            )
            val actPayload = json.encodeToString(ActivityProjection.serializer(), actProj)
            sl.db.inbox().putObject(
                ReceivedRow(
                    author = contactTereza.identity,
                    objectType = "activity",
                    objectId = "tereza-trail-run",
                    version = 1L,
                    payload = actPayload
                )
            )

            val selfHash = sl.bridge.ensureStarted().deliveryHash
            val demoGroup = Group(
                id = "group-sumava",
                name = "Tým Šumava · Garmin & Withings",
                ownerId = selfHash,
                adminId = selfHash,
                members = setOf(contactTereza.identity, contactTomas.identity, selfHash),
                version = 1L
            )
            sl.shareService.saveGroup(demoGroup)
        }
    }

    private fun buildActivityFromSampleJson(
        id: String,
        accountId: String,
        zoneId: String,
        extId: String,
        obj: JsonObject,
        nowMs: Long
    ): Activity {
        fun str(k: String): String? = (obj[k] as? JsonPrimitive)?.content
        fun dbl(k: String): Double? = (obj[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
        fun lng(k: String): Long? = (obj[k] as? JsonPrimitive)?.content?.toLongOrNull()
        val typeKey = ((obj["activityType"] as? JsonObject)?.get("typeKey") as? JsonPrimitive)?.content.orEmpty()
        val sport = when {
            "run" in typeKey -> Sport.RUNNING
            "cycl" in typeKey || "bik" in typeKey -> Sport.CYCLING
            "hik" in typeKey -> Sport.HIKING
            "swim" in typeKey -> Sport.SWIMMING
            "strength" in typeKey -> Sport.STRENGTH
            else -> Sport.WALKING
        }
        return Activity(
            id = id,
            provider = Provider.GARMIN_PERSONAL,
            accountId = accountId,
            externalId = extId,
            sport = sport,
            startUtcMs = lng("beginTimestamp") ?: nowMs,
            zoneId = zoneId,
            totalTimeS = dbl("duration") ?: 1800.0,
            movingTimeS = dbl("movingDuration"),
            distanceM = dbl("distance"),
            ascentM = dbl("elevationGain"),
            descentM = dbl("elevationLoss"),
            avgHeartRate = dbl("averageHR")?.toInt(),
            maxHeartRate = dbl("maxHR")?.toInt(),
            title = str("activityName"),
            locationName = str("locationName"),
            avgSpeedMps = dbl("averageSpeed"),
            maxSpeedMps = dbl("maxSpeed"),
            caloriesKcal = dbl("calories")
        )
    }

    private fun synthesizeTrackFromSummary(act: Activity): List<TrackPoint> {
        val count = 120
        val durMs = (act.movingTimeS ?: act.totalTimeS).coerceAtLeast(60.0) * 1000.0
        val distTotal = act.distanceM ?: 5000.0
        val baseHr = act.avgHeartRate ?: 135
        val maxHr = act.maxHeartRate ?: (baseHr + 18)
        val baseSpd = act.avgSpeedMps ?: (distTotal / ((act.movingTimeS ?: act.totalTimeS).coerceAtLeast(1.0)))
        val baseEle = act.avgElevationM ?: act.minElevationM ?: 200.0
        val baseCad = act.avgCadence ?: 172.0
        val basePwr = act.avgPowerW ?: 260.0
        val startLat = 45.0000
        val startLon = 15.0000
        return (0 until count).map { i ->
            val frac = i.toDouble() / (count - 1)
            val t = act.startUtcMs + (frac * durMs).toLong()
            val wave1 = sin(frac * Math.PI * 4.0)
            val wave2 = cos(frac * Math.PI * 7.0)
            val warmup = (frac * 5.0).coerceAtMost(1.0)
            val hr = (baseHr * (0.82 + 0.18 * warmup) + wave1 * (maxHr - baseHr) * 0.55 + wave2 * 4.0).toInt().coerceIn(65, maxHr)
            val spd = (baseSpd * (0.88 + 0.12 * warmup) + wave1 * 0.35 - wave2 * 0.18).coerceAtLeast(0.6)
            val ele = baseEle + sin(frac * Math.PI * 2.0) * ((act.ascentM ?: 20.0) * 0.45) + wave2 * 2.2
            val cad = (baseCad * (0.92 + 0.08 * warmup) + wave1 * 6.0).toInt().coerceAtLeast(60)
            val pwr = if (act.avgPowerW != null) (basePwr * (0.85 + 0.15 * warmup) + wave1 * 45.0 - wave2 * 20.0).toInt().coerceAtLeast(50) else null
            val angle = frac * Math.PI * 2.0
            val lat = startLat + sin(angle) * 0.0085 + wave2 * 0.0008
            val lon = startLon + (1.0 - cos(angle)) * 0.011 + wave1 * 0.0008
            TrackPoint(
                tMs = t,
                lat = lat,
                lon = lon,
                elevationM = ele,
                heartRate = hr,
                speedMps = spd,
                cadence = cad,
                powerW = pwr,
                distanceM = distTotal * frac
            )
        }
    }
}
