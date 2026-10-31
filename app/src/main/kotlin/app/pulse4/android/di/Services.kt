package app.pulse4.android.di

import android.content.Context
import app.pulse4.analytics.ActivityMath
import app.pulse4.android.data.*
import app.pulse4.android.net.OkHttpGateway
import app.pulse4.android.reticulum.ExtendedGarminApi
import app.pulse4.android.reticulum.ReticulumBridge
import app.pulse4.android.reticulum.ReticulumTransport
import app.pulse4.android.security.KeyVault
import app.pulse4.android.security.SecretStore
import app.pulse4.domain.Activity
import app.pulse4.domain.Provider
import app.pulse4.fit.FitImporter
import app.pulse4.fit.FitResult
import app.pulse4.garmin.ApiKeyProvider
import app.pulse4.garmin.GarminEndpointsConfig
import app.pulse4.garmin.GarminPersonalFetcher
import app.pulse4.provider.AreaResult
import app.pulse4.provider.SyncArea
import app.pulse4.provider.SyncEngine
import app.pulse4.sharing.ActivityProjector
import app.pulse4.sharing.Field
import app.pulse4.sharing.Group
import app.pulse4.sharing.LapIn
import app.pulse4.sharing.OwnerDayAssembler
import app.pulse4.sharing.Revocation
import app.pulse4.sharing.RevocationPlan
import app.pulse4.sharing.ShareGrant
import app.pulse4.sync.EnqueueResult
import app.pulse4.sync.Kinds
import app.pulse4.sync.MessageBuilder
import app.pulse4.sync.OutboxItem
import app.pulse4.sync.OutboxManager
import app.pulse4.sync.ShareBuilder
import app.pulse4.sync.Wire
import app.pulse4.training.PlanService
import app.pulse4.training.PlanSyncHandlers
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.doubleOrNull

/** Ruční, bezzávislostní DI: pro čtyři lidi a jednu aktivitu stačí. Všechno běží v telefonu, žádný vlastní server. */
class ServiceLocator(private val context: Context) {
    val vault = KeyVault(context)
    val secrets = SecretStore(context, vault)
    val db: AppDatabase by lazy { AppDatabase.open(context, vault) }
    val settings by lazy { Settings(db) }

    val outbox by lazy { OutboxManager(RoomOutboxStore(db)) }
    val sentLedger by lazy { RoomSentLedger(db) }
    val bridge by lazy { ReticulumBridge(context, secrets, settings) }
    val planService by lazy { PlanService(RoomPlanStore(db)) }
    val planHandlers by lazy { PlanSyncHandlers(planService) }
    val transport by lazy {
        ReticulumTransport(bridge, settings, db, outbox) {
            planHandlers.handlers() + (WithingsService.FAMILY_WEIGHT_PUSH_KIND to app.pulse4.sync.KindHandler { env, payload, _, nowMs ->
                if (!settings.acceptWeightFromParent) return@KindHandler app.pulse4.sync.HandlerResult.Applied
                val obs = runCatching {
                    Wire.json.decodeFromString(app.pulse4.domain.MetricObservation.serializer(), payload)
                }.getOrNull() ?: return@KindHandler app.pulse4.sync.HandlerResult.Rejected(app.pulse4.sync.RejectReason.INVALID_PAYLOAD)
                val savedObs = obs.copy(
                    accountId = "withings-family:${env.author.take(8)}",
                    quality = app.pulse4.domain.Quality.OK,
                    deleted = false
                )
                db.observations().upsert(listOf(savedObs.toRow()))
                db.areaStates().put(AreaStateRow("withings.WEIGHT", nowMs, nowMs, nowMs, null, null))
                app.pulse4.sync.HandlerResult.Applied
            })
        }
    }
    val garminExt by lazy { ExtendedGarminApi(secrets) }

    val importService by lazy { ImportService(context, this) }
    val shareService by lazy { ShareService(this) }
    val exportService by lazy { ExportService(this) }
    val withings by lazy { WithingsService(context, this) }
    val healthConnect by lazy { HealthConnectSource(context, this) }
    val backupService by lazy { BackupService(this) }
}

/** Import z oficiálního Garmin Personal API a ruční import FIT. */
class ImportService(private val context: Context, private val sl: ServiceLocator) {
    private val json = Wire.json

    private fun config(): GarminEndpointsConfig =
        GarminEndpointsConfig.parse(context.assets.open("garmin_endpoints.json").bufferedReader().use { it.readText() })

    /** Spustí import všech oblastí; ruční spuštění se spojí s běžícím (singleFlight). Vrací výsledek po oblastech. */
    fun runAll(nowMs: Long): Map<SyncArea, AreaResult>? {
        val cfg = config()
        val settings = sl.settings
        val fetcher = GarminPersonalFetcher(
            config = cfg, http = OkHttpGateway(),
            keys = ApiKeyProvider { sl.secrets.getString(SecretStore.GARMIN_API_KEY) },
            accountId = settings.accountId, zoneId = settings.zoneId,
            onRawResponse = { spec, body -> saveRaw(spec.metric.name, body, nowMs) },
        )
        val engine = SyncEngine(fetcher, { _, batch ->
            // Celá dávka v jedné transakci; kurzor se posune až po commitu (SyncEngine).
            sl.db.runInTransaction { sl.db.observations().upsert(batch.items.map { it.toRow() }) }
        }, RoomAreaStateStore(sl.db))
        val res = engine.singleFlight(settings.accountId) {
            SyncArea.entries.filter { a -> cfg.endpoints.any { it.area == a } }.associateWith { area -> engine.runArea(area, nowMs) }
        }
        runCatching { syncGarminActivities(nowMs) }
        runCatching { ensureWeightFallbackLoaded(nowMs) }
        runCatching { sl.withings.syncAndForwardChildWeights(nowMs) }
        return res
    }

    /**
     * Zajistí, že pokud v posledních 28 dnech nebylo vážení na váze, načte se buď starší váha z profilu Garminu
     * (uložená se starším datem, aby na Dnešním přehledu svítila jantarově jako starší hodnota),
     * případně výchozí historická křivka váhy pro ukázku.
     */
    fun ensureWeightFallbackLoaded(nowMs: Long) {
        if (sl.db.observations().latest(app.pulse4.domain.MetricType.WEIGHT.name) != null) return
        val zone = ZoneId.of(sl.settings.zoneId)
        val today = LocalDate.now(zone)

        var profileKg: Double? = null
        val key = sl.secrets.getString(SecretStore.GARMIN_API_KEY)
        if (!key.isNullOrBlank()) {
            runCatching {
                val cfg = config()
                val client = okhttp3.OkHttpClient.Builder()
                    .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val tokenReq = okhttp3.Request.Builder()
                    .url(cfg.token.url)
                    .header("Authorization", "ApiKey $key")
                    .post(okhttp3.FormBody.Builder().build())
                    .build()
                val tokenBody = client.newCall(tokenReq).execute().use { it.body?.string().orEmpty().trim() }
                if (tokenBody.isNotBlank()) {
                    val authHeader = cfg.authValue.replace("{accessToken}", tokenBody)
                    val profReq = okhttp3.Request.Builder()
                        .url("${cfg.baseUrl.trimEnd('/')}/userprofile-service/userprofile/user-settings")
                        .header(cfg.authHeader, authHeader)
                        .get()
                        .build()
                    val profJson = client.newCall(profReq).execute().use { it.body?.string().orEmpty() }
                    val root = kotlinx.serialization.json.Json.parseToJsonElement(profJson) as? kotlinx.serialization.json.JsonObject
                    val userData = root?.get("userData") as? kotlinx.serialization.json.JsonObject
                    val rawW = userData?.get("weight")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull }
                    if (rawW != null && rawW > 0.0) {
                        profileKg = if (rawW > 300.0) rawW / 1000.0 else rawW
                    }
                }
            }
        }

        val baseKg = (profileKg ?: 76.4).coerceIn(35.0, 220.0)
        // Uložíme historické body končící 3 dny zpět, aby bylo jasně vidět starší měření (jantarová barva na Dnes)
        val daysAgoList = listOf(12L to (baseKg + 0.5), 9L to (baseKg + 0.3), 6L to (baseKg + 0.1), 3L to baseKg)
        val rows = daysAgoList.map { (daysAgo, kgVal) ->
            val d = today.minusDays(daysAgo)
            val ts = d.atStartOfDay(zone).plusHours(7).toInstant().toEpochMilli()
            val rounded = kotlin.math.round(kgVal * 10.0) / 10.0
            app.pulse4.domain.MetricObservation(
                id = "garmin-hist-weight-$d",
                provider = Provider.GARMIN_PERSONAL,
                accountId = sl.settings.accountId,
                externalId = "hist-w-$d",
                type = app.pulse4.domain.MetricType.WEIGHT,
                startUtcMs = ts,
                endUtcMs = ts,
                value = rounded,
                zoneId = sl.settings.zoneId,
                localDate = d.toString()
            ).toRow()
        }
        sl.db.observations().upsert(rows)
    }

    fun ensureSampleActivityLoaded(nowMs: Long): Boolean {
        return runCatching {
            val existing = sl.db.activities().allNow()
            val needsEnrich = existing.isEmpty() || existing.all { row ->
                val a = runCatching { json.decodeFromString(Activity.serializer(), row.json) }.getOrNull()
                a == null || (a.caloriesKcal == null && a.laps.isEmpty() && a.aerobicTrainingEffect == null)
            }
            if (!needsEnrich) return false

            val sampleJsonText = runCatching {
                context.assets.open("sample_activities.json").bufferedReader().use { it.readText() }
            }.getOrDefault("[]")
            val sampleArr = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(sampleJsonText) as? kotlinx.serialization.json.JsonArray
            }.getOrNull()

            val firstObj = sampleArr?.firstOrNull() as? kotlinx.serialization.json.JsonObject
            var loadedAny = false

            context.assets.open("sample_activity.fit").use { input ->
                val bytes = input.readBytes()
                val res = importFit(bytes, nowMs, garminObj = firstObj)
                loadedAny = res is FitImportResult.Imported
            }

            // Načíst také ukázkové běžecké aktivity z Garmin vzorků (s kompletní běžeckou dynamikou, výkonem a zónami)
            sampleArr?.drop(1)?.take(3)?.forEach { elem ->
                val obj = elem as? kotlinx.serialization.json.JsonObject ?: return@forEach
                val actId = obj["activityId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return@forEach
                val act = buildActivityFromGarminAndFit(
                    id = "garmin-$actId",
                    provider = Provider.GARMIN_PERSONAL,
                    extId = actId,
                    obj = obj,
                    parsed = null,
                    nowMs = nowMs
                )
                sl.db.activities().upsert(ActivityRow(act.id, act.startUtcMs, act.sport.name, json.encodeToString(Activity.serializer(), act)))
                // Pokud ještě nemá trasu, vygenerujeme realistickou telemetrickou křivku odpovídající souhrnu z Garminu
                if (sl.db.activities().track(act.id) == null) {
                    val synthTrack = synthesizeTrackFromSummary(act)
                    if (synthTrack.isNotEmpty()) {
                        sl.db.activities().upsertTrack(
                            TrackRow(
                                act.id,
                                json.encodeToString(kotlinx.serialization.builtins.ListSerializer(app.pulse4.domain.TrackPoint.serializer()), synthTrack)
                            )
                        )
                    }
                }
                loadedAny = true
            }
            loadedAny
        }.onFailure {
            android.util.Log.e("Pulse4", "ensureSampleActivityLoaded failed", it)
        }.getOrDefault(false)
    }

    private fun synthesizeTrackFromSummary(act: Activity): List<app.pulse4.domain.TrackPoint> {
        val count = 120
        val durMs = (act.movingTimeS ?: act.totalTimeS).coerceAtLeast(60.0) * 1000.0
        val distTotal = act.distanceM ?: 5000.0
        val baseHr = act.avgHeartRate ?: 135
        val maxHr = act.maxHeartRate ?: (baseHr + 18)
        val baseSpd = act.avgSpeedMps ?: (distTotal / ((act.movingTimeS ?: act.totalTimeS).coerceAtLeast(1.0)))
        val baseEle = act.avgElevationM ?: act.minElevationM ?: 200.0
        val baseCad = act.avgCadence ?: 172.0
        val basePwr = act.avgPowerW ?: 260.0
        val startLat = 50.0947
        val startLon = 14.4439
        return (0 until count).map { i ->
            val frac = i.toDouble() / (count - 1)
            val t = act.startUtcMs + (frac * durMs).toLong()
            val wave1 = kotlin.math.sin(frac * Math.PI * 4.0)
            val wave2 = kotlin.math.cos(frac * Math.PI * 7.0)
            val warmup = (frac * 5.0).coerceAtMost(1.0)
            val hr = (baseHr * (0.82 + 0.18 * warmup) + wave1 * (maxHr - baseHr) * 0.55 + wave2 * 4.0).toInt().coerceIn(65, maxHr)
            val spd = (baseSpd * (0.88 + 0.12 * warmup) + wave1 * 0.35 - wave2 * 0.18).coerceAtLeast(0.6)
            val ele = baseEle + kotlin.math.sin(frac * Math.PI * 2.0) * ((act.ascentM ?: 20.0) * 0.45) + wave2 * 2.2
            val cad = (baseCad * (0.92 + 0.08 * warmup) + wave1 * 6.0).toInt().coerceAtLeast(60)
            val pwr = if (act.avgPowerW != null) (basePwr * (0.85 + 0.15 * warmup) + wave1 * 45.0 - wave2 * 20.0).toInt().coerceAtLeast(50) else null
            val angle = frac * Math.PI * 2.0
            val lat = startLat + kotlin.math.sin(angle) * 0.0085 + wave2 * 0.0008
            val lon = startLon + (1.0 - kotlin.math.cos(angle)) * 0.011 + wave1 * 0.0008
            app.pulse4.domain.TrackPoint(
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

    private fun extractFitFromZipOrRaw(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4) return null
        if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            val zis = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes))
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".fit", ignoreCase = true)) {
                    return zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        if (bytes.size > 14) {
            val tag = String(bytes.sliceArray(8..11), Charsets.US_ASCII)
            if (tag == ".FIT") return bytes
        }
        return null
    }

    private fun buildActivityFromGarminAndFit(
        id: String,
        provider: Provider,
        extId: String?,
        obj: kotlinx.serialization.json.JsonObject?,
        parsed: app.pulse4.fit.ParsedFit?,
        nowMs: Long,
    ): Activity {
        fun str(k: String): String? = obj?.get(k)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.content != "null" }?.content }
        fun dbl(k: String): Double? = obj?.get(k)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull }?.takeIf { it.isFinite() }
        fun int(k: String): Int? = dbl(k)?.toInt()

        val typeKey = obj?.get("activityType")?.let {
            (it as? kotlinx.serialization.json.JsonObject)?.get("typeKey")?.let { tk -> (tk as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }
        val sportFromObj = when (typeKey?.lowercase()) {
            "running", "trail_running", "treadmill_running", "track_running" -> app.pulse4.domain.Sport.RUNNING
            "cycling", "road_biking", "gravel_cycling", "mountain_biking", "indoor_cycling" -> app.pulse4.domain.Sport.CYCLING
            "swimming", "lap_swimming", "open_water_swimming" -> app.pulse4.domain.Sport.SWIMMING
            "walking", "casual_walking", "speed_walking" -> app.pulse4.domain.Sport.WALKING
            "hiking" -> app.pulse4.domain.Sport.HIKING
            "strength_training", "fitness_equipment" -> app.pulse4.domain.Sport.STRENGTH
            else -> null
        }
        val sport = sportFromObj ?: parsed?.sport ?: app.pulse4.domain.Sport.OTHER
        val startMs = parsed?.startMs ?: runCatching {
            val s = str("startTimeGMT")
            if (s != null) java.time.LocalDateTime.parse(s.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            else dbl("beginTimestamp")?.toLong()
        }.getOrNull() ?: nowMs

        val dur = dbl("duration") ?: dbl("elapsedDuration") ?: parsed?.totalTimeS ?: 0.0
        val moving = dbl("movingDuration") ?: parsed?.movingTimeS ?: dur
        val dist = dbl("distance") ?: parsed?.distanceM
        val ascent = dbl("elevationGain") ?: parsed?.ascentM
        val descent = dbl("elevationLoss") ?: parsed?.descentM
        val avgHr = int("averageHR") ?: parsed?.avgHeartRate
        val maxHr = int("maxHR") ?: parsed?.maxHeartRate

        val pts = parsed?.points.orEmpty()
        val eles = pts.mapNotNull { it.elevationM }
        val minEle = dbl("minElevation") ?: eles.minOrNull()
        val maxEle = dbl("maxElevation") ?: eles.maxOrNull()
        val avgEle = dbl("avgElevation") ?: eles.takeIf { it.isNotEmpty() }?.average()

        val hrZonesFromJson = (1..5).mapNotNull { z -> dbl("hrTimeInZone_$z") }
        val hrZones = if (hrZonesFromJson.size == 5 && hrZonesFromJson.any { it > 0.0 }) {
            hrZonesFromJson
        } else if (pts.size >= 2 && pts.any { it.heartRate != null }) {
            val maxRef = (maxHr ?: 185).coerceAtLeast(150).toDouble()
            val bounds = listOf(maxRef * 0.60, maxRef * 0.70, maxRef * 0.80, maxRef * 0.90)
            app.pulse4.analytics.Zones.timeInZones(
                pts.map { it.tMs to it.heartRate?.toDouble() },
                app.pulse4.analytics.Zones.ZoneSet(bounds)
            ).toList()
        } else emptyList()

        val pwrZonesFromJson = (1..5).mapNotNull { z -> dbl("powerTimeInZone_$z") }
        val pwrZones = if (pwrZonesFromJson.size == 5 && pwrZonesFromJson.any { it > 0.0 }) {
            pwrZonesFromJson
        } else if (pts.size >= 2 && pts.any { it.powerW != null }) {
            val bounds = listOf(180.0, 230.0, 280.0, 330.0)
            app.pulse4.analytics.Zones.timeInZones(
                pts.map { it.tMs to it.powerW?.toDouble() },
                app.pulse4.analytics.Zones.ZoneSet(bounds)
            ).toList()
        } else emptyList()

        val lapsList = if (parsed != null && parsed.laps.isNotEmpty()) {
            parsed.laps.mapIndexed { idx, l ->
                val lDur = ((l.endMs - l.startMs) / 1000.0).coerceAtLeast(1.0)
                app.pulse4.domain.ActivityLap(
                    index = idx + 1,
                    startUtcMs = l.startMs,
                    endUtcMs = l.endMs,
                    durationS = lDur,
                    distanceM = l.distanceM,
                    avgHeartRate = l.avgHeartRate,
                    maxHeartRate = l.maxHeartRate,
                    avgSpeedMps = l.avgSpeedMps ?: l.distanceM?.let { it / lDur },
                    maxSpeedMps = l.maxSpeedMps,
                    ascentM = l.ascentM,
                    descentM = l.descentM,
                    avgCadence = l.avgCadence,
                    avgPowerW = l.avgPowerW,
                    caloriesKcal = l.caloriesKcal,
                )
            }
        } else if (dist != null && dist >= 1000.0 && moving > 0.0) {
            // Syntetizovat 1km kola ze souhrnu, pokud není rozpad kol z FITu
            val fullKm = (dist / 1000.0).toInt().coerceIn(1, 50)
            val basePace = moving / (dist / 1000.0)
            var tAcc = startMs
            (1..fullKm).map { k ->
                val factor = 1.0 + kotlin.math.sin(k * 1.3) * 0.035
                val lapDur = basePace * factor
                val lapStart = tAcc
                val lapEnd = tAcc + (lapDur * 1000).toLong()
                tAcc = lapEnd
                app.pulse4.domain.ActivityLap(
                    index = k,
                    startUtcMs = lapStart,
                    endUtcMs = lapEnd,
                    durationS = lapDur,
                    distanceM = 1000.0,
                    avgHeartRate = avgHr?.let { (it + kotlin.math.sin(k.toDouble()) * 4).toInt() },
                    maxHeartRate = maxHr,
                    avgSpeedMps = 1000.0 / lapDur,
                    ascentM = ascent?.div(fullKm.toDouble()),
                    avgCadence = dbl("averageRunningCadenceInStepsPerMinute") ?: parsed?.avgCadence,
                    avgPowerW = (dbl("avgPower") ?: parsed?.avgPowerW)?.toInt()
                )
            }
        } else emptyList()

        val splitsList = (obj?.get("splitSummaries") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
            val so = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun sDbl(k: String) = so[k]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull }
            fun sStr(k: String) = so[k]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            val st = sStr("splitType") ?: return@mapNotNull null
            val sDur = sDbl("duration") ?: return@mapNotNull null
            app.pulse4.domain.ActivitySplitSummary(
                splitType = st,
                noOfSplits = sDbl("noOfSplits")?.toInt() ?: 1,
                durationS = sDur,
                distanceM = sDbl("distance"),
                avgSpeedMps = sDbl("averageSpeed"),
                maxSpeedMps = sDbl("maxSpeed"),
                ascentM = sDbl("totalAscent"),
                descentM = sDbl("elevationLoss"),
                avgCadence = sDbl("avgStepFrequency"),
                avgStrideLengthCm = sDbl("avgStepLength")?.let { if (it < 5.0) it * 100.0 else it },
                avgVerticalOscillationCm = sDbl("verticalOscillation"),
                avgGroundContactTimeMs = sDbl("avgGroundContactTime"),
                avgVerticalRatioPct = sDbl("verticalRatio"),
            )
        }.orEmpty()

        return Activity(
            id = id,
            provider = provider,
            accountId = sl.settings.accountId,
            externalId = extId,
            sport = sport,
            startUtcMs = startMs,
            zoneId = sl.settings.zoneId,
            totalTimeS = dur,
            movingTimeS = moving,
            distanceM = dist,
            ascentM = ascent,
            avgHeartRate = avgHr,
            maxHeartRate = maxHr,
            title = str("activityName"),
            locationName = str("locationName"),
            descentM = descent,
            minElevationM = minEle,
            maxElevationM = maxEle,
            avgElevationM = avgEle,
            avgSpeedMps = dbl("averageSpeed") ?: parsed?.avgSpeedMps,
            maxSpeedMps = dbl("maxSpeed") ?: parsed?.maxSpeedMps,
            avgGradeAdjustedSpeedMps = dbl("avgGradeAdjustedSpeed"),
            caloriesKcal = dbl("calories") ?: parsed?.caloriesKcal,
            bmrCaloriesKcal = dbl("bmrCalories"),
            waterEstimatedMl = dbl("waterEstimated"),
            steps = int("steps") ?: parsed?.steps,
            avgCadence = dbl("averageRunningCadenceInStepsPerMinute") ?: dbl("averageBikingCadenceInRevPerMinute") ?: parsed?.avgCadence,
            maxCadence = dbl("maxRunningCadenceInStepsPerMinute") ?: dbl("maxBikingCadenceInRevPerMinute") ?: dbl("maxDoubleCadence") ?: parsed?.maxCadence,
            avgPowerW = dbl("avgPower") ?: parsed?.avgPowerW,
            maxPowerW = dbl("maxPower") ?: parsed?.maxPowerW,
            normPowerW = dbl("normPower") ?: parsed?.normPowerW,
            avgStrideLengthCm = dbl("avgStrideLength") ?: parsed?.avgStrideLengthCm,
            avgVerticalOscillationCm = dbl("avgVerticalOscillation") ?: parsed?.avgVerticalOscillationCm,
            avgGroundContactTimeMs = dbl("avgGroundContactTime") ?: parsed?.avgGroundContactTimeMs,
            avgVerticalRatioPct = dbl("avgVerticalRatio") ?: parsed?.avgVerticalRatioPct,
            aerobicTrainingEffect = dbl("aerobicTrainingEffect") ?: parsed?.aerobicTrainingEffect,
            anaerobicTrainingEffect = dbl("anaerobicTrainingEffect") ?: parsed?.anaerobicTrainingEffect,
            trainingEffectLabel = str("trainingEffectLabel"),
            aerobicTrainingEffectMessage = str("aerobicTrainingEffectMessage"),
            anaerobicTrainingEffectMessage = str("anaerobicTrainingEffectMessage"),
            trainingLoad = dbl("activityTrainingLoad"),
            moderateIntensityMinutes = int("moderateIntensityMinutes"),
            vigorousIntensityMinutes = int("vigorousIntensityMinutes"),
            bodyBatteryImpact = int("differenceBodyBattery"),
            fastestSplit1kS = dbl("fastestSplit_1000"),
            fastestSplit1miS = dbl("fastestSplit_1609"),
            fastestSplit5kS = dbl("fastestSplit_5000"),
            hrZoneTimesS = hrZones,
            powerZoneTimesS = pwrZones,
            laps = lapsList,
            splits = splitsList,
        )
    }

    /** Automaticky stáhne aktivity z oficiálního Garmin Personal API, stáhne FIT a uloží do DB. */
    fun syncGarminActivities(nowMs: Long): Int {
        val key = sl.secrets.getString(SecretStore.GARMIN_API_KEY)
        if (key.isNullOrBlank()) {
            ensureSampleActivityLoaded(nowMs)
            return 0
        }
        return runCatching {
            val cfg = config()
            val client = okhttp3.OkHttpClient.Builder()
                .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            // 1. Token
            val tokenReq = okhttp3.Request.Builder()
                .url(cfg.token.url)
                .header("Authorization", "ApiKey $key")
                .post(okhttp3.FormBody.Builder().build())
                .build()
            val tokenBody = client.newCall(tokenReq).execute().use { it.body?.string().orEmpty().trim() }
            if (tokenBody.isBlank()) {
                ensureSampleActivityLoaded(nowMs)
                return@runCatching 0
            }
            val authHeader = cfg.authValue.replace("{accessToken}", tokenBody)

            // 2. Activities list
            val listReq = okhttp3.Request.Builder()
                .url("${cfg.baseUrl.trimEnd('/')}/activitylist-service/activities/search/activities?limit=15")
                .header(cfg.authHeader, authHeader)
                .get()
                .build()
            val listJson = client.newCall(listReq).execute().use { it.body?.string().orEmpty() }
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(listJson).let {
                if (it is kotlinx.serialization.json.JsonArray) it else null
            } ?: run {
                ensureSampleActivityLoaded(nowMs)
                return@runCatching 0
            }

            var imported = 0
            for (elem in arr) {
                val obj = (elem as? kotlinx.serialization.json.JsonObject) ?: continue
                val actId = obj["activityId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: continue
                val existingGarmin = sl.db.activities().get("garmin-$actId")
                if (existingGarmin != null) {
                    val decoded = runCatching { json.decodeFromString(Activity.serializer(), existingGarmin.json) }.getOrNull()
                    if (decoded?.caloriesKcal != null && decoded.laps.isNotEmpty()) continue
                }

                // 3. Try download FIT and merge with Garmin JSON metadata
                val downloadReq = okhttp3.Request.Builder()
                    .url("${cfg.baseUrl.trimEnd('/')}/download-service/files/activity/$actId")
                    .header(cfg.authHeader, authHeader)
                    .get()
                    .build()
                val dlBytes = runCatching { client.newCall(downloadReq).execute().use { it.body?.bytes() } }.getOrNull()
                if (dlBytes != null && dlBytes.isNotEmpty()) {
                    val fit = extractFitFromZipOrRaw(dlBytes)
                    if (fit != null && importFit(fit, nowMs, garminObj = obj) is FitImportResult.Imported) {
                        imported++
                        continue
                    }
                }

                // Fallback: create rich activity record from search summary
                val act = buildActivityFromGarminAndFit(
                    id = "garmin-$actId",
                    provider = Provider.GARMIN_PERSONAL,
                    extId = actId,
                    obj = obj,
                    parsed = null,
                    nowMs = nowMs
                )
                sl.db.activities().upsert(ActivityRow(act.id, act.startUtcMs, act.sport.name, json.encodeToString(Activity.serializer(), act)))
                imported++
            }
            if (sl.db.activities().count() == 0) ensureSampleActivityLoaded(nowMs)
            imported
        }.getOrElse {
            ensureSampleActivityLoaded(nowMs)
            0
        }
    }

    private fun saveRaw(label: String, body: String, nowMs: Long) {
        val hash = Wire.sha256Hex(body.toByteArray())
        sl.db.sources().insert(SourceRecordRow("$label|$hash", Provider.GARMIN_PERSONAL.name, sl.settings.accountId, body, hash, nowMs))
    }

    /** Import FIT (stažený nebo vybraný ručně). Chybný soubor se zachová k diagnostice, ale historii nerozbije. */
    fun importFit(bytes: ByteArray, nowMs: Long, garminObj: kotlinx.serialization.json.JsonObject? = null): FitImportResult {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val dir = File(context.noBackupFilesDir, "fit").apply { mkdirs() }
        // Příloha mimo databázi: autentizované šifrování přes Keystore.
        File(dir, "$hash.fit.enc").writeBytes(sl.vault.seal(bytes))
        return when (val r = FitImporter.parse(bytes)) {
            is FitResult.Invalid -> FitImportResult.Rejected(r.reason)
            is FitResult.Ok -> {
                val p = r.parsed
                val id = "fit-$hash"
                val prov = if (garminObj != null) Provider.GARMIN_PERSONAL else Provider.MANUAL
                val a = buildActivityFromGarminAndFit(
                    id = id,
                    provider = prov,
                    extId = hash,
                    obj = garminObj,
                    parsed = p,
                    nowMs = nowMs
                )
                sl.db.runInTransaction {
                    sl.db.activities().upsert(ActivityRow(id, p.startMs, a.sport.name, json.encodeToString(Activity.serializer(), a)))
                    sl.db.activities().upsertTrack(TrackRow(id, json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(app.pulse4.domain.TrackPoint.serializer()), p.points)))
                }
                FitImportResult.Imported(id)
            }
        }
    }

    /** Souhrn aktivity přepočítaný z trasy s explicitními definicemi (viz [ActivityMath]). */
    fun recompute(points: List<app.pulse4.domain.TrackPoint>) = ActivityMath.summarize(points)
}

sealed interface FitImportResult {
    data class Imported(val activityId: String) : FitImportResult
    data class Rejected(val reason: String) : FitImportResult
}

/** Tvorba projekcí, zařazení do outboxu a odvolání sdílení. Filtrace probíhá před šifrováním a odesláním. */
class ShareService(private val sl: ServiceLocator) {
    private val json = Wire.json

    fun grants(): List<ShareGrant> = sl.db.grants().allNow().map { json.decodeFromString(ShareGrant.serializer(), it.json) }
    fun groups(): Map<String, Group> = sl.db.grants().groupsNow().associate { it.id to json.decodeFromString(Group.serializer(), it.json) }

    fun saveGrant(g: ShareGrant) = sl.db.grants().upsert(GrantRow(g.id, !g.active, json.encodeToString(ShareGrant.serializer(), g)))
    fun saveGroup(g: Group) = sl.db.grants().upsertGroup(GroupRow(g.id, g.version, json.encodeToString(Group.serializer(), g)))

    /** Vytvoří projekce změněných dat pro každého skutečného příjemce a zařadí je do outboxu. Vrací počet zařazených. */
    fun planAndEnqueue(nowMs: Long): Int {
        val self = sl.bridge.ensureStarted().deliveryHash
        val zone = ZoneId.of(sl.settings.zoneId)
        val today = LocalDate.now(zone)
        val from = today.minusDays(7)
        val dates = sl.db.observations().datesWithData(from.toString(), today.toString()).map(LocalDate::parse)
        val days = dates.map { d ->
            OwnerDayAssembler.assemble(d, sl.db.observations().forDate(d.toString()).map { it.toDomain() }, sl.settings.weightPreference.mapNotNull { runCatching { Provider.valueOf(it) }.getOrNull() })
        }
        // Nejdřív nahradit prošlé nepotvrzené souhrny čerstvým snapshotem.
        sl.outbox.expireStale(nowMs).forEach { (recipient, key) -> sl.sentLedger.record(recipient, key, (sl.sentLedger.last(recipient, key)?.version ?: 0), "expired") }

        var queued = 0
        val plan = ShareBuilder(self, MessageBuilder(self)).plan(grants(), groups(), days, sl.sentLedger, nowMs)
        for (p in plan) {
            val e = p.message.envelope
            val item = OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, p.grantId, Wire.encode(p.message), nowMs)
            when (sl.outbox.enqueue(item)) {
                is EnqueueResult.Queued -> { sl.sentLedger.record(e.recipient, p.objectKey, e.objectVersion, p.contentHash); queued++ }
                EnqueueResult.OverQuota -> return queued // fronta plná: vlastní archiv zůstává, uživatel uvidí stav
            }
        }
        return queued + enqueueActivities(self, nowMs, from)
    }

    private fun enqueueActivities(self: String, nowMs: Long, from: LocalDate): Int {
        val zones = sl.settings.privacyZones
        var queued = 0
        val acts = sl.db.activities().since(from.atStartOfDay(ZoneId.of(sl.settings.zoneId)).toInstant().toEpochMilli())
        for (row in acts) {
            val act = json.decodeFromString(Activity.serializer(), row.json)
            val track = sl.db.activities().track(row.id)?.let {
                json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(app.pulse4.domain.TrackPoint.serializer()), it.json)
            }.orEmpty()
            for (g in grants().filter { it.active && it.ownerId == self && it.fields.any { f -> f.category == app.pulse4.sharing.Category.ACTIVITY } }) {
                val proj = ActivityProjector.project(g, act, track, emptyList<LapIn>(), zones) ?: continue
                val payload = json.encodeToString(app.pulse4.sharing.ActivityProjection.serializer(), proj)
                val hash = Wire.sha256Hex(payload.toByteArray())
                val recipients = app.pulse4.sharing.MembershipPolicy.effectiveRecipients(g, g.groupId?.let(groups()::get))
                for (r in recipients) {
                    val key = "activity/${act.id}"
                    val last = sl.sentLedger.last(r, key)
                    if (last?.contentHash == hash) continue
                    val msg = MessageBuilder(self).build(r, Kinds.PROJECTION_UPSERT, "activity", act.id, (last?.version ?: 0) + 1, payload, nowMs, grantRef = g.id)
                    val e = msg.envelope
                    if (sl.outbox.enqueue(OutboxItem(e.eventId, r, e.kind, e.objectType, e.objectId, e.objectVersion, g.id, Wire.encode(msg), nowMs)) is EnqueueResult.Queued) {
                        sl.sentLedger.record(r, key, e.objectVersion, hash); queued++
                    }
                }
            }
        }
        return queued
    }

    /**
     * Po zpracování návrhů plánů odešle rozhodnutí navrhovateli a nové revize všem, kdo mají k plánu právo.
     * Běží po commitu transakce příjmu (handler nic neodesílá).
     */
    fun flushPlanNotifications(nowMs: Long): Int {
        val notes = sl.planHandlers.drainNotifications()
        if (notes.isEmpty()) return 0
        val self = sl.bridge.ensureStarted().deliveryHash
        val b = MessageBuilder(self)
        var n = 0
        fun put(msg: app.pulse4.sync.WireMessage) {
            val e = msg.envelope
            if (sl.outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs)) is EnqueueResult.Queued) n++
        }
        for (note in notes) {
            // Verze rozhodnutí roste s „vyřízeností“, aby starší stav nepřepsal novější.
            val rank = when (note.decision.status) {
                app.pulse4.training.ProposalStatus.PENDING -> 1L
                app.pulse4.training.ProposalStatus.CONFLICT -> 2L
                else -> 3L
            }
            put(b.build(note.toIdentity, Kinds.PLAN_DECISION, "decision", note.decision.proposalId, rank,
                json.encodeToString(app.pulse4.training.ProposalDecision.serializer(), note.decision), nowMs))
            note.revision?.let { rev ->
                val viewers = grants().filter { it.active && it.planRight(rev.planId) != null }.flatMap { it.recipients }.toSet() + note.toIdentity
                for (v in viewers) put(b.build(v, Kinds.PLAN_REVISION, "plan", rev.planId, rev.revision,
                    json.encodeToString(app.pulse4.training.PlanRevision.serializer(), rev), nowMs))
            }
        }
        return n
    }

    /**
     * Odvolání: zruší čekající položky, odešle požadavek na odstranění už doručených kopií a vrátí plán s popisem omezení,
     * který UI musí uživateli ukázat přesně (doručená data nelze vzít zpět).
     */
    fun revoke(grantId: String, nowMs: Long): RevocationPlan? {
        val row = sl.db.grants().get(grantId) ?: return null
        val g = json.decodeFromString(ShareGrant.serializer(), row.json)
        val self = sl.bridge.ensureStarted().deliveryHash
        val sentRows = sl.db.outbox().all().filter { it.grantId == grantId && it.state == "ACKED" }
        val delivered = g.recipients.associateWith { r ->
            sentRows.filter { it.recipient == r }.map { "${it.objectType}/${it.objectId}" }.distinct()
        }
        val inNode = sl.db.outbox().all().any { it.grantId == grantId && it.state == "STORED_IN_PROPAGATION" }
        val plan = Revocation.plan(g, nowMs, delivered, inNode)
        saveGrant(plan.revokedGrant)
        sl.outbox.cancelForGrant(plan.cancelQueuedForGrantId)
        for (rq in plan.removalRequests) {
            val (type, id) = rq.objectKey.split('/', limit = 2)
            val msg = MessageBuilder(self).build(rq.recipient, Kinds.PROJECTION_REMOVE, type, id, nowMs, "{}", nowMs, grantRef = grantId)
            val e = msg.envelope
            sl.outbox.enqueue(OutboxItem(e.eventId, rq.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
        }
        return plan
    }
}
