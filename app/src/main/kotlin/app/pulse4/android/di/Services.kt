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
import app.pulse4.domain.MetricType
import app.pulse4.domain.PLog
import app.pulse4.domain.Provider
import app.pulse4.domain.Quality
import app.pulse4.domain.Sport
import app.pulse4.domain.TrackPoint
import app.pulse4.domain.czechName
import app.pulse4.fit.FitImporter
import app.pulse4.fit.FitResult
import app.pulse4.fit.ParsedFit
import app.pulse4.garmin.ApiKeyProvider
import app.pulse4.garmin.GarminEndpointsConfig
import app.pulse4.garmin.GarminPersonalFetcher
import app.pulse4.provider.AreaResult
import app.pulse4.provider.SyncArea
import app.pulse4.provider.SyncEngine
import app.pulse4.sharing.ActivityProjector
import app.pulse4.sharing.AgeProfile
import app.pulse4.sharing.CircleAdminPolicy
import app.pulse4.sharing.FamilyLink
import app.pulse4.sharing.FamilyLinkPolicy
import app.pulse4.sharing.FamilyLinkStatus
import app.pulse4.sharing.Field
import app.pulse4.sharing.Group
import app.pulse4.sharing.LapIn
import app.pulse4.sharing.OwnerDayAssembler
import app.pulse4.sharing.Revocation
import app.pulse4.sharing.RevocationPlan
import app.pulse4.sharing.ShareGrant
import app.pulse4.sync.EnqueueResult
import app.pulse4.sync.HandlerResult
import app.pulse4.sync.KindHandler
import app.pulse4.sync.Kinds
import app.pulse4.sync.MessageBuilder
import app.pulse4.sync.OutboxItem
import app.pulse4.sync.OutboxManager
import app.pulse4.sync.RejectReason
import app.pulse4.sync.ShareBuilder
import app.pulse4.sync.Wire
import app.pulse4.training.PlanService
import app.pulse4.training.PlanSyncHandlers
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.doubleOrNull

/** Ruční, bezzávislostní DI: pro čtyři lidi a jednu aktivitu stačí. Všechno běží v telefonu, žádný vlastní server. */
class ServiceLocator(internal val context: Context) {
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
        ServiceMigrations.runOnce(this)
        ReticulumTransport(bridge, settings, db, outbox) {
            planHandlers.handlers() + mapOf(
                WithingsService.FAMILY_WEIGHT_PUSH_KIND to KindHandler { env, payload, _, nowMs ->
                    val isVerifiedParent = db.familyLinks().allNow().any { row ->
                        row.status == FamilyLinkStatus.VERIFIED.name &&
                            row.parentId == env.author &&
                            row.childId == env.recipient
                    }
                    if (!isVerifiedParent) {
                        return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
                    }
                    // Migrace výchozí hodnoty pro existující ověřené dětské účty (RE-5).
                    if (settings.getRaw("acceptWeightFromParent") == null) {
                        settings.acceptWeightFromParent = true
                    }
                    if (!settings.acceptWeightFromParent) {
                        return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
                    }
                    val obs = runCatching {
                        Wire.json.decodeFromString(app.pulse4.domain.MetricObservation.serializer(), payload)
                    }.getOrNull() ?: return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
                    if (!isValidChildBodyComposition(obs.type, obs.value)) {
                        return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
                    }
                    val idPrefix = if (obs.type == MetricType.WEIGHT) "parent-weight" else "parent-${obs.type.name.lowercase()}"
                    val savedObs = obs.copy(
                        id = "$idPrefix:${env.author.take(16)}:${obs.startUtcMs}",
                        provider = Provider.WITHINGS_API,
                        accountId = "withings-family:${env.author.take(8)}",
                        quality = Quality.OK,
                        deleted = false
                    )
                    db.observations().upsert(listOf(savedObs.toRow()))
                    val areaKey = "withings.${obs.type.name}"
                    val prevW = db.areaStates().get(areaKey)
                    db.areaStates().put(AreaStateRow(areaKey, prevW?.cursorMs, nowMs, nowMs, null, null))
                    HandlerResult.Applied
                },
                Kinds.FAMILY_LINK_REQUEST to KindHandler { env, payload, _, nowMs ->
                    val link = runCatching {
                        Wire.json.decodeFromString(FamilyLink.serializer(), payload)
                    }.getOrNull() ?: return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
                    if (link.childId != env.recipient || link.parentId != env.author) {
                        return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
                    }
                    val existing = db.familyLinks().get(link.id)
                    if (existing != null && link.version <= existing.version) {
                        return@KindHandler HandlerResult.Stale
                    }
                    val sanitized = link.copy(status = FamilyLinkStatus.PENDING, verifiedAtMs = null)
                    val sanitizedJson = Wire.json.encodeToString(FamilyLink.serializer(), sanitized)
                    db.familyLinks().upsert(FamilyLinkRow(sanitized.id, sanitized.parentId, sanitized.childId, FamilyLinkStatus.PENDING.name, sanitized.version, sanitizedJson))
                    db.pendingRequests().upsert(
                        PendingRequestRow(
                            id = sanitized.id,
                            kind = "FAMILY_LINK_REQUEST",
                            fromId = sanitized.parentId,
                            toId = sanitized.childId,
                            status = "PENDING",
                            createdAtMs = nowMs,
                            json = sanitizedJson,
                        )
                    )
                    HandlerResult.Applied
                },
                Kinds.FAMILY_LINK_RESPONSE to KindHandler { env, payload, _, _ ->
                    val link = runCatching {
                        Wire.json.decodeFromString(FamilyLink.serializer(), payload)
                    }.getOrNull() ?: return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
                    if (link.parentId != env.recipient || link.childId != env.author) {
                        return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
                    }
                    if (link.status != FamilyLinkStatus.VERIFIED && link.status != FamilyLinkStatus.REJECTED) {
                        return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
                    }
                    val existing = db.familyLinks().get(link.id)
                        ?: return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
                    if (existing.parentId != env.recipient || existing.childId != env.author) {
                        return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
                    }
                    if (link.version <= existing.version) {
                        return@KindHandler HandlerResult.Stale
                    }
                    val sanitizedJson = Wire.json.encodeToString(FamilyLink.serializer(), link)
                    db.familyLinks().upsert(FamilyLinkRow(link.id, link.parentId, link.childId, link.status.name, link.version, sanitizedJson))
                    HandlerResult.Applied
                },
                Kinds.ADMIN_HANDOVER_REQUEST to KindHandler { env, payload, store, nowMs ->
                    val g = runCatching {
                        Wire.json.decodeFromString(Group.serializer(), payload)
                    }.getOrNull() ?: return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
                    val handover = g.pendingAdminHandover
                        ?: return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
                    val existing = store.group(g.id)
                    if (existing != null) {
                        if (env.author !in existing.effectiveAdmins) {
                            return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
                        }
                        if (g.version <= existing.version) return@KindHandler HandlerResult.Stale
                    } else if (env.author !in g.effectiveAdmins) {
                        return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
                    }
                    store.putGroup(g)
                    if (handover.targetMemberId == env.recipient) {
                        db.pendingRequests().upsert(
                            PendingRequestRow(
                                id = "handover-${g.id}",
                                kind = "ADMIN_HANDOVER_REQUEST",
                                fromId = handover.initiatedBy,
                                toId = handover.targetMemberId,
                                status = "PENDING",
                                createdAtMs = nowMs,
                                json = payload,
                            )
                        )
                    }
                    HandlerResult.Applied
                },
                Kinds.ADMIN_HANDOVER_ACCEPT to KindHandler { env, payload, store, nowMs ->
                    val g = runCatching {
                        Wire.json.decodeFromString(Group.serializer(), payload)
                    }.getOrNull() ?: return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
                    if (!g.dissolved && g.effectiveAdmins.isEmpty()) {
                        return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
                    }
                    val existing = store.group(g.id)
                    if (existing != null) {
                        if (env.author in existing.effectiveAdmins) {
                            if (g.version <= existing.version) return@KindHandler HandlerResult.Stale
                        } else {
                            val handover = existing.pendingAdminHandover
                            val validHandover = handover != null &&
                                handover.targetMemberId == env.author &&
                                nowMs <= handover.expiresAtMs &&
                                handover.initiatedBy in existing.effectiveAdmins &&
                                env.author in existing.members
                            if (!validHandover) return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
                            if (g.version <= existing.version) return@KindHandler HandlerResult.Stale
                            val expectedKeep = CircleAdminPolicy.acceptHandover(existing, env.author, nowMs, removeInitiator = false)
                            val expectedRemove = CircleAdminPolicy.acceptHandover(existing, env.author, nowMs, removeInitiator = true)
                            val matchesKeep = expectedKeep != null && g == expectedKeep.copy(version = g.version)
                            val matchesRemove = expectedRemove != null && g == expectedRemove.copy(version = g.version)
                            if (!matchesKeep && !matchesRemove) {
                                return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
                            }
                        }
                    } else if (env.author !in g.effectiveAdmins) {
                        return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
                    }
                    store.putGroup(g)
                    db.pendingRequests().get("handover-${g.id}")?.let { req ->
                        db.pendingRequests().upsert(req.copy(status = "ACCEPTED", json = payload))
                    }
                    HandlerResult.Applied
                },
            )
        }.also { t ->
            t.ensureContactsRegistered()
            bridge.onEventReceived = { ev -> t.processEvent(ev) }
            bridge.onLocalPeerDiscovered = { peerHash ->
                if (settings.networkMode != app.pulse4.android.data.NetworkMode.MANUAL && outbox.hasPendingFor(peerHash)) {
                    app.pulse4.android.work.Scheduler.shareNow(context)
                }
            }
        }
    }
    val garminExt by lazy { ExtendedGarminApi(context, secrets) }

    val importService by lazy { ImportService(context, this) }
    val shareService by lazy { ShareService(this) }
    val exportService by lazy { ExportService(this) }
    val withings by lazy { WithingsService(context, this) }
    val healthConnect by lazy { HealthConnectSource(context, this) }
    val backupService by lazy { BackupService(this) }
}

/** Import z oficiálního Garmin Personal API (a záložně z přihlášené relace Garmin Connect) a ruční import FIT. */
internal interface ActivityImportStore {
    fun get(id: String): ActivityRow?
    fun track(id: String): TrackRow?
    fun upsert(row: ActivityRow)
    fun upsertTrack(row: TrackRow)
    fun inTransaction(block: () -> Unit)
}

class ImportService(private val context: Context, private val sl: ServiceLocator) {
    private val json = Wire.json
    private val syncLock = Any()

    companion object {
        private val MULTISPORT_LEG_REGEX = Regex("""^(garmin|fit)-.+-s\d+$""")
        private val json = Wire.json

        fun isMultisportLegId(id: String): Boolean = MULTISPORT_LEG_REGEX.matches(id)

        fun parentIdFromLegId(id: String): String? =
            if (isMultisportLegId(id)) id.substringBeforeLast("-s") else null

        internal fun multisportLegIds(baseId: String, baseExtId: String, sessionCount: Int): List<Pair<String, String>> =
            if (sessionCount <= 1) listOf(baseId to baseExtId)
            else (1..sessionCount).map { legNum -> "$baseId-s$legNum" to "$baseExtId-s$legNum" }

        internal fun extractFitFromZipOrRaw(bytes: ByteArray): ByteArray? {
            val maxFitBytes = 25 * 1024 * 1024
            val maxZipEntries = 32
            if (bytes.size < 4 || bytes.size > maxFitBytes) return null
            if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
                    var entries = 0
                    var entry = zis.nextEntry
                    while (entry != null && entries < maxZipEntries) {
                        entries++
                        if (!entry.isDirectory && entry.name.endsWith(".fit", ignoreCase = true)) {
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val n = zis.read(buf)
                                if (n <= 0) break
                                total += n
                                if (total > maxFitBytes) return null
                                out.write(buf, 0, n)
                            }
                            return out.toByteArray()
                        }
                        entry = zis.nextEntry
                    }
                }
                return null
            }
            if (bytes.size > 14) {
                val tag = String(bytes.sliceArray(8..11), Charsets.US_ASCII)
                if (tag == ".FIT") return bytes
            }
            return null
        }

        internal fun parseGarminDetailsGpsPoints(detailsJson: String): List<app.pulse4.domain.TrackPoint> {
            return runCatching<List<app.pulse4.domain.TrackPoint>> {
                val root = json.parseToJsonElement(detailsJson) as? kotlinx.serialization.json.JsonObject ?: return@runCatching emptyList()
                val polyline = root["geoPolylineDTO"]?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("polyline") as? kotlinx.serialization.json.JsonArray }
                if (polyline != null && polyline.isNotEmpty()) {
                    val pts = polyline.mapNotNull { elem ->
                        val obj = elem as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        val lat = obj["lat"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() } ?: return@mapNotNull null
                        val lon = obj["lon"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() } ?: return@mapNotNull null
                        val alt = obj["altitude"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }
                        val time = obj["time"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() } ?: 0L
                        app.pulse4.domain.TrackPoint(
                            tMs = time,
                            lat = lat,
                            lon = lon,
                            elevationM = alt,
                            heartRate = null,
                            speedMps = null,
                            cadence = null,
                            powerW = null,
                            distanceM = null
                        )
                    }
                    if (pts.size >= 2) return@runCatching pts
                }

                val descArr = root["metricDescriptors"] as? kotlinx.serialization.json.JsonArray
                val metricsArr = root["activityDetailMetrics"] as? kotlinx.serialization.json.JsonArray
                if (descArr != null && metricsArr != null && metricsArr.isNotEmpty()) {
                    var latIdx = -1
                    var lonIdx = -1
                    var altIdx = -1
                    var timeIdx = -1
                    var hrIdx = -1
                    var spdIdx = -1
                    descArr.forEach { dElem ->
                        val dObj = dElem as? kotlinx.serialization.json.JsonObject ?: return@forEach
                        val idx = dObj["metricsIndex"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: return@forEach
                        val key = dObj["key"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }?.lowercase() ?: ""
                        when {
                            "latitude" in key -> latIdx = idx
                            "longitude" in key -> lonIdx = idx
                            "elevation" in key || "altitude" in key -> altIdx = idx
                            "timestamp" in key || "time" in key -> timeIdx = idx
                            "heartrate" in key || "hr" in key -> hrIdx = idx
                            "speed" in key -> spdIdx = idx
                        }
                    }
                    if (latIdx >= 0 && lonIdx >= 0) {
                        val pts = metricsArr.mapNotNull { mElem ->
                            val mObj = mElem as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                            val vals = mObj["metrics"] as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                            val lat = vals.getOrNull(latIdx)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() } ?: return@mapNotNull null
                            val lon = vals.getOrNull(lonIdx)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() } ?: return@mapNotNull null
                            val alt = if (altIdx >= 0) vals.getOrNull(altIdx)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() } else null
                            val t = if (timeIdx >= 0) vals.getOrNull(timeIdx)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() } ?: 0L else 0L
                            val hr = if (hrIdx >= 0) vals.getOrNull(hrIdx)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } else null
                            val spd = if (spdIdx >= 0) vals.getOrNull(spdIdx)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() } else null
                            app.pulse4.domain.TrackPoint(
                                tMs = t,
                                lat = lat,
                                lon = lon,
                                elevationM = alt,
                                heartRate = hr,
                                speedMps = spd,
                                cadence = null,
                                powerW = null,
                                distanceM = null
                            )
                        }
                        if (pts.size >= 2) return@runCatching pts
                    }
                }
                emptyList<app.pulse4.domain.TrackPoint>()
            }.getOrDefault(emptyList())
        }

        internal fun buildActivityFromGarminAndFit(
            id: String,
            provider: Provider,
            extId: String?,
            obj: kotlinx.serialization.json.JsonObject?,
            parsed: app.pulse4.fit.ParsedFit?,
            nowMs: Long,
            accountId: String,
            zoneId: String,
        ): Activity {
            fun str(k: String): String? = obj?.get(k)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.content != "null" }?.content }
            fun dbl(k: String): Double? = obj?.get(k)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull }?.takeIf { it.isFinite() }
            fun int(k: String): Int? = dbl(k)?.toInt()

            val activityTypeObj = obj?.get("activityType") as? kotlinx.serialization.json.JsonObject
            val typeKey = activityTypeObj?.get("typeKey")?.let { tk -> (tk as? kotlinx.serialization.json.JsonPrimitive)?.content }
            val parentTypeId = activityTypeObj?.get("parentTypeId")?.let { pt -> (pt as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull?.toInt() }
            val sportFromObj = when (typeKey?.lowercase()) {
                "running", "trail_running", "treadmill_running", "track_running",
                "street_running", "ultra_run", "virtual_run", "indoor_running", "obstacle_run" -> app.pulse4.domain.Sport.RUNNING
                "cycling", "road_biking", "gravel_cycling", "mountain_biking", "indoor_cycling",
                "virtual_ride", "cyclocross", "e_bike_mountain", "e_bike_fitness", "bmx",
                "recumbent_cycling", "track_cycling" -> app.pulse4.domain.Sport.CYCLING
                "swimming", "lap_swimming", "open_water_swimming", "pool_swimming",
                "stand_up_paddleboarding_v2", "kayaking_v2", "rowing_v2", "indoor_rowing" -> app.pulse4.domain.Sport.SWIMMING
                "walking", "casual_walking", "speed_walking", "indoor_walking" -> app.pulse4.domain.Sport.WALKING
                "hiking", "mountaineering", "rock_climbing", "bouldering",
                "snowshoe_ws", "backcountry_skiing_snowboarding_ws" -> app.pulse4.domain.Sport.HIKING
                "strength_training", "fitness_equipment", "hiit", "indoor_cardio",
                "cardio_training", "elliptical", "stair_climbing", "yoga", "pilates",
                "breathwork", "mobility" -> app.pulse4.domain.Sport.STRENGTH
                else -> when (parentTypeId) {
                    1 -> app.pulse4.domain.Sport.RUNNING
                    2 -> app.pulse4.domain.Sport.CYCLING
                    3 -> app.pulse4.domain.Sport.HIKING
                    26 -> app.pulse4.domain.Sport.SWIMMING
                    29 -> app.pulse4.domain.Sport.STRENGTH
                    else -> null
                }
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

            val setsFromJson = (obj?.get("exerciseSets") as? kotlinx.serialization.json.JsonArray)?.mapIndexedNotNull { idx, el ->
                val so = el as? kotlinx.serialization.json.JsonObject ?: return@mapIndexedNotNull null
                fun sDbl(k: String) = so[k]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull }
                fun sInt(k: String) = sDbl(k)?.toInt()
                fun sStr(k: String) = so[k]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

                val setTypeRaw = sStr("setType") ?: sStr("type") ?: if ((sInt("repetitionCount") ?: 0) > 0) "ACTIVE" else "REST"
                val setType = if (setTypeRaw.equals("REST", ignoreCase = true)) "REST" else "ACTIVE"
                val reps = sInt("repetitionCount") ?: sInt("reps")
                val rawWeight = sDbl("weight")
                val weightKg = rawWeight?.let { if (it > 500.0) it / 1000.0 else it }
                val durationS = sDbl("duration") ?: sDbl("durationInSeconds")
                val cat = sStr("exerciseCategory") ?: sStr("category")
                val name = sStr("exerciseName") ?: sStr("name")
                val startMsSet = sDbl("startTime")?.toLong() ?: sDbl("startTimeGMT")?.toLong()
                val stepIdx = sInt("wktStepIndex")

                app.pulse4.domain.StrengthSet(
                    index = idx + 1,
                    setType = setType,
                    reps = reps,
                    weightKg = weightKg,
                    durationS = durationS,
                    exerciseCategory = cat,
                    exerciseName = name,
                    startUtcMs = startMsSet,
                    wktStepIndex = stepIdx
                )
            }.orEmpty()

            val finalStrengthSets = if (parsed?.strengthSets?.isNotEmpty() == true) {
                if (setsFromJson.isNotEmpty() && setsFromJson.size == parsed.strengthSets.size) {
                    parsed.strengthSets.mapIndexed { i, ps ->
                        val js = setsFromJson[i]
                        ps.copy(
                            exerciseName = js.exerciseName ?: ps.exerciseName,
                            exerciseCategory = js.exerciseCategory ?: ps.exerciseCategory
                        )
                    }
                } else parsed.strengthSets
            } else setsFromJson

            val activeSets = finalStrengthSets.filter { it.isActive }
            val computedTotalVolumeKg = activeSets.sumOf { it.volumeKg }.takeIf { it > 0.0 }
            val computedTotalReps = activeSets.sumOf { it.reps ?: 0 }.takeIf { it > 0 }
            val computedActiveSetsCount = activeSets.size.takeIf { it > 0 }
            val computedWorkDurationS = activeSets.sumOf { it.durationS ?: 0.0 }.takeIf { it > 0.0 }
            val computedRestDurationS = finalStrengthSets.filter { it.isRest }.sumOf { it.durationS ?: 0.0 }.takeIf { it > 0.0 }

            val totalVolumeKg = dbl("totalVolume")?.let { if (it > 5000.0) it / 1000.0 else it } ?: computedTotalVolumeKg
            val totalReps = int("totalReps") ?: int("repetitionCount") ?: parsed?.totalReps ?: computedTotalReps
            val activeSetsCount = int("activeSets") ?: int("activeSetsCount") ?: computedActiveSetsCount
            val workDurationS = dbl("totalWorkDuration") ?: dbl("totalExerciseDuration") ?: computedWorkDurationS
            val restDurationS = dbl("totalRestDuration") ?: dbl("totalRestTime") ?: computedRestDurationS

            val finalSport = if (sport == app.pulse4.domain.Sport.OTHER && finalStrengthSets.isNotEmpty()) {
                app.pulse4.domain.Sport.STRENGTH
            } else sport

            val totalStrokes = int("totalStrokes") ?: int("strokes") ?: parsed?.totalStrokes
            val avgSwolf = int("avgSwolf") ?: int("averageSwolf") ?: parsed?.avgSwolf
            val poolLengthM = dbl("poolLength") ?: parsed?.poolLengthM
            val swimStroke = str("swimStroke")

            return Activity(
                id = id,
                provider = provider,
                accountId = accountId,
                externalId = extId,
                sport = finalSport,
                startUtcMs = startMs,
                zoneId = zoneId,
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
                strengthSets = finalStrengthSets,
                totalVolumeKg = totalVolumeKg,
                totalReps = totalReps,
                activeSetsCount = activeSetsCount,
                workDurationS = workDurationS,
                restDurationS = restDurationS,
                totalStrokes = totalStrokes,
                avgSwolf = avgSwolf,
                poolLengthM = poolLengthM,
                swimStroke = swimStroke,
            )
        }

        internal fun importParsedFitSessions(
            parsed: FitResult.Ok,
            hash: String,
            nowMs: Long,
            garminObj: kotlinx.serialization.json.JsonObject? = null,
            targetId: String? = null,
            store: ActivityImportStore,
            accountId: String,
            zoneId: String,
        ): FitImportResult {
            val actId = garminObj?.get("activityId")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            val baseId = targetId ?: if (actId != null) "garmin-$actId" else "fit-$hash"
            val baseExtId = actId ?: baseId.removePrefix("garmin-").takeIf { baseId.startsWith("garmin-") } ?: hash
            val prov = if (garminObj != null || baseId.startsWith("garmin-")) Provider.GARMIN_PERSONAL else Provider.MANUAL
            val trackSerializer = kotlinx.serialization.builtins.ListSerializer(TrackPoint.serializer())

            store.inTransaction {
                if (parsed.sessions.size <= 1) {
                    val session = parsed.sessions.first()
                    val existing = store.get(baseId)
                    if (existing != null && targetId != null) {
                        if (session.points.isNotEmpty()) {
                            store.upsertTrack(TrackRow(baseId, json.encodeToString(trackSerializer, session.points)))
                        }
                        return@inTransaction
                    }
                    val a = buildActivityFromGarminAndFit(
                        id = baseId,
                        provider = prov,
                        extId = baseExtId,
                        obj = garminObj,
                        parsed = session,
                        nowMs = nowMs,
                        accountId = accountId,
                        zoneId = zoneId,
                    )
                    store.upsert(a.toRow(json.encodeToString(Activity.serializer(), a)))
                    if (session.points.isNotEmpty()) {
                        store.upsertTrack(TrackRow(baseId, json.encodeToString(trackSerializer, session.points)))
                    }
                } else {
                    // Multisport: zachováme rodiče `baseId` se souhrnem Garminu (nebo součtem ručního FIT)
                    // a jednotlivé úseky uložíme jako podřízené záznamy `-s1..-sN` (RE-4).
                    val existingParent = store.get(baseId)
                    if (existingParent == null) {
                        val parentAct = if (garminObj != null) {
                            buildActivityFromGarminAndFit(
                                id = baseId,
                                provider = prov,
                                extId = baseExtId,
                                obj = garminObj,
                                parsed = null,
                                nowMs = nowMs,
                                accountId = accountId,
                                zoneId = zoneId,
                            )
                        } else {
                            val firstSession = parsed.sessions.first()
                            val totalTimeS = parsed.sessions.sumOf { it.totalTimeS }
                            val movingTimeS = parsed.sessions.mapNotNull { it.movingTimeS }.takeIf { it.isNotEmpty() }?.sum()
                            val distanceM = parsed.sessions.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum()
                            val ascentM = parsed.sessions.mapNotNull { it.ascentM }.takeIf { it.isNotEmpty() }?.sum()
                            val descentM = parsed.sessions.mapNotNull { it.descentM }.takeIf { it.isNotEmpty() }?.sum()
                            val caloriesKcal = parsed.sessions.mapNotNull { it.caloriesKcal }.takeIf { it.isNotEmpty() }?.sum()
                            Activity(
                                id = baseId,
                                provider = prov,
                                accountId = accountId,
                                externalId = baseExtId,
                                sport = Sport.OTHER,
                                startUtcMs = firstSession.startMs,
                                zoneId = zoneId,
                                totalTimeS = totalTimeS,
                                movingTimeS = movingTimeS ?: totalTimeS,
                                distanceM = distanceM,
                                ascentM = ascentM,
                                descentM = descentM,
                                caloriesKcal = caloriesKcal,
                                title = "Multisport (${parsed.sessions.size} úseky)",
                            )
                        }
                        store.upsert(parentAct.toRow(json.encodeToString(Activity.serializer(), parentAct)))
                    }

                    val allPoints = parsed.sessions.flatMap { it.points }.sortedBy { it.tMs }
                    if (allPoints.isNotEmpty()) {
                        store.upsertTrack(TrackRow(baseId, json.encodeToString(trackSerializer, allPoints)))
                    }

                    val parentTitle = existingParent?.title?.takeIf { it.isNotBlank() }
                        ?: garminObj?.get("activityName")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.content != "null" }?.content }
                    val legIds = multisportLegIds(baseId, baseExtId, parsed.sessions.size)
                    for ((idx, session) in parsed.sessions.withIndex()) {
                        val (legId, legExtId) = legIds[idx]
                        val legTitle = if (!parentTitle.isNullOrBlank()) {
                            "$parentTitle · ${idx + 1}. ${session.sport.czechName()}"
                        } else {
                            "${idx + 1}. ${session.sport.czechName()}"
                        }
                        val legAct = buildActivityFromGarminAndFit(
                            id = legId,
                            provider = prov,
                            extId = legExtId,
                            obj = null,
                            parsed = session,
                            nowMs = nowMs,
                            accountId = accountId,
                            zoneId = zoneId,
                        ).copy(
                            title = legTitle,
                            tags = setOf("multisport-leg", "parent:$baseId"),
                        )
                        store.upsert(legAct.toRow(json.encodeToString(Activity.serializer(), legAct)))
                        if (session.points.isNotEmpty()) {
                            store.upsertTrack(TrackRow(legId, json.encodeToString(trackSerializer, session.points)))
                        }
                    }
                }
            }
            return FitImportResult.Imported(baseId)
        }

        internal fun ensureActivityTrackWith(
            actKey: String,
            store: ActivityImportStore,
            hasSession: Boolean,
            downloadFit: (String) -> ByteArray?,
            fetchDetailsJson: (String) -> String,
            nowMs: Long,
            accountId: String,
            zoneId: String,
            sealFit: (String, ByteArray) -> Unit = { _, _ -> },
        ): Boolean {
            if (isMultisportLegId(actKey)) return false
            if (store.track(actKey) != null) return true
            val actId = actKey.removePrefix("garmin-")
            if (!hasSession) return false
            return runCatching {
                val rawFit = downloadFit(actId)
                if (rawFit != null && rawFit.isNotEmpty()) {
                    val fit = extractFitFromZipOrRaw(rawFit)
                    if (fit != null) {
                        val hash = MessageDigest.getInstance("SHA-256").digest(fit).joinToString("") { "%02x".format(it) }
                        sealFit(hash, fit)
                        val parsed = FitImporter.parse(fit)
                        if (parsed is FitResult.Ok &&
                            importParsedFitSessions(
                                parsed = parsed,
                                hash = hash,
                                nowMs = nowMs,
                                garminObj = null,
                                targetId = actKey,
                                store = store,
                                accountId = accountId,
                                zoneId = zoneId,
                            ) is FitImportResult.Imported
                        ) {
                            return@runCatching true
                        }
                    }
                }
                val detailsJson = fetchDetailsJson(actId)
                if (detailsJson.isNotBlank()) {
                    val gpsPoints = parseGarminDetailsGpsPoints(detailsJson)
                    if (gpsPoints.isNotEmpty()) {
                        store.upsertTrack(
                            TrackRow(
                                actKey,
                                json.encodeToString(
                                    kotlinx.serialization.builtins.ListSerializer(app.pulse4.domain.TrackPoint.serializer()),
                                    gpsPoints
                                )
                            )
                        )
                        return@runCatching true
                    }
                }
                false
            }.getOrDefault(false)
        }

        internal fun syncGarminActivityElements(
            allActivityElements: List<kotlinx.serialization.json.JsonElement>,
            isInitialSync: Boolean,
            nowMs: Long,
            store: ActivityImportStore,
            accountId: String,
            zoneId: String,
            downloadFit: (String) -> ByteArray?,
            fetchDetailsJson: (String) -> String,
            sealFit: (String, ByteArray) -> Unit = { _, _ -> },
            throttleMs: Long = 0L,
        ): Pair<Int, Long?> {
            var imported = 0
            var latestTimestampMs: Long? = null
            val activitiesToSave = mutableListOf<ActivityRow>()

            for (elem in allActivityElements) {
                val obj = (elem as? kotlinx.serialization.json.JsonObject) ?: continue
                val actId = obj["activityId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: continue
                val actKey = "garmin-$actId"
                val existingGarmin = store.get(actKey)

                if (existingGarmin == null) {
                    val act = buildActivityFromGarminAndFit(
                        id = actKey,
                        provider = Provider.GARMIN_PERSONAL,
                        extId = actId,
                        obj = obj,
                        parsed = null,
                        nowMs = nowMs,
                        accountId = accountId,
                        zoneId = zoneId,
                    )
                    activitiesToSave.add(act.toRow(json.encodeToString(Activity.serializer(), act)))
                    if (latestTimestampMs == null || act.startUtcMs > latestTimestampMs) {
                        latestTimestampMs = act.startUtcMs
                    }
                } else {
                    imported++
                }
            }

            val alreadyExistedIds = allActivityElements.mapNotNull { elem ->
                val actId = (elem as? kotlinx.serialization.json.JsonObject)?.get("activityId")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                actId?.let { "garmin-$it" }?.takeIf { store.get(it) != null }
            }.toHashSet()

            if (activitiesToSave.isNotEmpty()) {
                store.inTransaction {
                    for (row in activitiesToSave) {
                        store.upsert(row)
                    }
                }
                imported += activitiesToSave.size
            }

            val maxImmediateFitDownloads = if (isInitialSync) 15 else 30
            var fitDownloadsCount = 0

            for (elem in allActivityElements) {
                if (fitDownloadsCount >= maxImmediateFitDownloads) break
                val obj = (elem as? kotlinx.serialization.json.JsonObject) ?: continue
                val actId = obj["activityId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: continue
                val actKey = "garmin-$actId"
                if (store.track(actKey) != null || (!isInitialSync && actKey in alreadyExistedIds)) continue

                var fitImported = false
                val dlBytes = downloadFit(actId)
                if (dlBytes != null && dlBytes.isNotEmpty()) {
                    val fit = extractFitFromZipOrRaw(dlBytes)
                    if (fit != null) {
                        val hash = MessageDigest.getInstance("SHA-256").digest(fit).joinToString("") { "%02x".format(it) }
                        sealFit(hash, fit)
                        val parsed = FitImporter.parse(fit)
                        if (parsed is FitResult.Ok &&
                            importParsedFitSessions(
                                parsed = parsed,
                                hash = hash,
                                nowMs = nowMs,
                                garminObj = obj,
                                targetId = null,
                                store = store,
                                accountId = accountId,
                                zoneId = zoneId,
                            ) is FitImportResult.Imported
                        ) {
                            fitImported = true
                        }
                    }
                }

                if (!fitImported && store.track(actKey) == null) {
                    val detailsJson = fetchDetailsJson(actId)
                    if (detailsJson.isNotBlank()) {
                        val gpsPoints = parseGarminDetailsGpsPoints(detailsJson)
                        if (gpsPoints.isNotEmpty()) {
                            store.upsertTrack(
                                TrackRow(
                                    actKey,
                                    json.encodeToString(
                                        kotlinx.serialization.builtins.ListSerializer(app.pulse4.domain.TrackPoint.serializer()),
                                        gpsPoints
                                    )
                                )
                            )
                        }
                    }
                }

                fitDownloadsCount++
                if (throttleMs > 0L) {
                    try { Thread.sleep(throttleMs) } catch (_: InterruptedException) {}
                }
            }
            return imported to latestTimestampMs
        }
    }

    private fun config(): GarminEndpointsConfig =
        GarminEndpointsConfig.parse(context.assets.open("garmin_endpoints.json").bufferedReader().use { it.readText() })

    /**
     * Pokusí se vyčíst skutečnou dobu platnosti osobního API klíče přímo z odpovědi API.
     * Krátkodobá expirace Bearer access tokenu (<= 48 h, vyměňovaného automaticky z 1letého klíče) se ignoruje,
     * aby se neukazovalo "Klíč vyprší za 0 dní".
     */
    fun extractApiReportedExpiryMs(tokenOrJson: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val trimmed = tokenOrJson.trim().removeSurrounding("\"")
        if (trimmed.isBlank()) return null
        val minKeyHorizonMs = nowMs + 2L * 86_400_000L

        // 1. JSON objekt s expires_at / expires_in
        if (trimmed.startsWith("{")) {
            runCatching {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(trimmed) as? kotlinx.serialization.json.JsonObject
                val expAt = (obj?.get("expires_at") as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
                if (expAt != null && expAt > 1_000_000_000.0) {
                    val ms = (expAt * 1000.0).toLong()
                    if (ms > minKeyHorizonMs) return ms
                }
                val expIn = (obj?.get("expires_in") as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
                if (expIn != null && expIn > 0.0) {
                    val ms = nowMs + (expIn * 1000.0).toLong()
                    if (ms > minKeyHorizonMs) return ms
                }
            }
        }

        // 2. JWT token (header.payload.signature) -> pole `exp`
        val parts = trimmed.split('.')
        if (parts.size == 3) {
            runCatching {
                val payloadBytes = java.util.Base64.getUrlDecoder().decode(parts[1])
                val payloadObj = kotlinx.serialization.json.Json.parseToJsonElement(String(payloadBytes, Charsets.UTF_8)) as? kotlinx.serialization.json.JsonObject
                val expSec = (payloadObj?.get("exp") as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
                if (expSec != null && expSec > 1_000_000_000.0) {
                    val ms = (expSec * 1000.0).toLong()
                    if (ms > minKeyHorizonMs) return ms
                }
            }
        }
        return null
    }

    /** Spustí import všech oblastí; ruční spuštění se spojí s běžícím (singleFlight). Vrací výsledek po oblastech. */
    fun runAll(nowMs: Long): Map<SyncArea, AreaResult>? = synchronized(syncLock) {
        val cfg = config()
        val settings = sl.settings
        // Pokud je v GARMIN_API_KEY_EXPIRES uložena krátkodobá expirace access tokenu (<= 48 h), vyčistíme ji:
        sl.secrets.getString(SecretStore.GARMIN_API_KEY_EXPIRES)?.toLongOrNull()?.let { expMs ->
            if (expMs <= nowMs + 2L * 86_400_000L) {
                sl.secrets.delete(SecretStore.GARMIN_API_KEY_EXPIRES)
            }
        }
        val baseHttp = OkHttpGateway()
        val smartHttp = object : app.pulse4.provider.HttpGateway {
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): app.pulse4.provider.HttpResponse {
                val rawKey = sl.secrets.getString(SecretStore.GARMIN_API_KEY)?.trim()?.removePrefix("ApiKey ")?.trim()
                if (!rawKey.isNullOrBlank()) {
                    val res = runCatching { baseHttp.postForm(url, form, headers) }.getOrNull()
                    if (res != null && res.code in 200..299 && res.body.isNotBlank()) {
                        extractApiReportedExpiryMs(res.body, nowMs)?.let { expMs ->
                            sl.secrets.putString(SecretStore.GARMIN_API_KEY_EXPIRES, expMs.toString())
                        }
                        return res
                    }
                    // Pokud není přihlášený Garmin Connect účet, vrátíme skutečnou chybu API klíče
                    if (sl.secrets.getString(SecretStore.GARMIN_EXT_TOKENS) == null && res != null) {
                        return res
                    }
                }
                if (sl.secrets.getString(SecretStore.GARMIN_EXT_TOKENS) != null) {
                    return app.pulse4.provider.HttpResponse(200, emptyMap(), "GARTH_SESSION_TOKEN")
                }
                return baseHttp.postForm(url, form, headers)
            }

            override fun get(url: String, headers: Map<String, String>): app.pulse4.provider.HttpResponse {
                val auth = headers["Authorization"].orEmpty()
                if (auth.contains("GARTH_SESSION_TOKEN")) {
                    val pathAndQuery = url.removePrefix(cfg.baseUrl.trimEnd('/'))
                    return try {
                        val body = sl.garminExt.connectApiJson(pathAndQuery)
                        app.pulse4.provider.HttpResponse(200, emptyMap(), body)
                    } catch (e: app.pulse4.provider.ProviderError.Unauthorized) {
                        app.pulse4.provider.HttpResponse(401, emptyMap(), "")
                    } catch (e: app.pulse4.provider.ProviderError.Forbidden) {
                        app.pulse4.provider.HttpResponse(403, emptyMap(), "")
                    } catch (e: app.pulse4.provider.ProviderError.RateLimited) {
                        app.pulse4.provider.HttpResponse(429, emptyMap(), "")
                    } catch (e: app.pulse4.provider.ProviderError.Network) {
                        throw java.io.IOException(e.message ?: "Network error")
                    } catch (e: app.pulse4.provider.ProviderError.Server) {
                        if (e.code == 204 || e.code == 404) {
                            app.pulse4.provider.HttpResponse(200, emptyMap(), "")
                        } else {
                            app.pulse4.provider.HttpResponse(e.code, emptyMap(), "")
                        }
                    } catch (e: Exception) {
                        app.pulse4.provider.HttpResponse(500, emptyMap(), e.message.orEmpty())
                    }
                }
                val res = baseHttp.get(url, headers)
                if (res.code == 404 || res.code == 204) {
                    return app.pulse4.provider.HttpResponse(200, emptyMap(), "")
                }
                if (res.code != 429 && res.code !in 200..299 && sl.secrets.getString(SecretStore.GARMIN_EXT_TOKENS) != null) {
                    val pathAndQuery = url.removePrefix(cfg.baseUrl.trimEnd('/'))
                    val fallbackBody = runCatching { sl.garminExt.connectApiJson(pathAndQuery) }.getOrNull()
                    if (!fallbackBody.isNullOrBlank()) {
                        return app.pulse4.provider.HttpResponse(200, emptyMap(), fallbackBody)
                    }
                }
                return res
            }
        }
        val garminStates = RoomAreaStateStore(sl.db)
        if (settings.getRaw("migration.bb_dedup_v1") != "true") {
            runCatching {
                deduplicateBodyBatteryRows(sl.db)
                settings.put("migration.bb_dedup_v1", "true")
            }
        }
        val areaCheckMetrics = mapOf(
            SyncArea.SLEEP to app.pulse4.domain.MetricType.SLEEP_DURATION,
            SyncArea.HRV to app.pulse4.domain.MetricType.HRV,
            SyncArea.BODY_BATTERY to app.pulse4.domain.MetricType.BODY_BATTERY,
            SyncArea.HEART_RATE to app.pulse4.domain.MetricType.RESTING_HEART_RATE,
            SyncArea.STEPS_STRESS to app.pulse4.domain.MetricType.STEPS,
        )
        for ((area, mType) in areaCheckMetrics) {
            val backfillDone = settings.getRaw("garmin.backfillDone.${area.name}") == "true"
            if (!backfillDone && sl.db.observations().latest(mType.name) == null) {
                val prev = garminStates.get(area)
                garminStates.put(area, prev.copy(cursorMs = null))
            }
        }
        val fetcher = GarminPersonalFetcher(
            config = cfg, http = smartHttp,
            keys = ApiKeyProvider {
                sl.secrets.getString(SecretStore.GARMIN_API_KEY)?.trim()?.removePrefix("ApiKey ")?.trim()?.takeIf { it.isNotBlank() }
                    ?: sl.secrets.getString(SecretStore.GARMIN_EXT_TOKENS)?.let { "GARTH_EXT" }
            },
            accountId = settings.accountId, zoneId = settings.zoneId,
            initialDays = 28,
        )
        val engine = SyncEngine(fetcher, { area, batch ->
            // Celá dávka v jedné transakci; kurzor se posune až po commitu (SyncEngine).
            val excluded = sl.db.exclusions().allIds().toHashSet()
            val allowed = batch.items.filter { it.id !in excluded }
            sl.db.runInTransaction {
                if (area == SyncArea.SLEEP) {
                    val dates = allowed.mapNotNull { it.localDate }.distinct()
                    if (dates.isNotEmpty()) {
                        sl.db.observations().deleteLegacyGarminSleepForDates(dates)
                    }
                }
                if (allowed.isNotEmpty()) {
                    sl.db.observations().upsert(allowed.map { it.toRow() })
                }
            }
        }, garminStates)
        val res = engine.singleFlight(settings.accountId) {
            SyncArea.entries.filter { a -> cfg.endpoints.any { it.area == a } }.associateWith { area ->
                val r = engine.runArea(area, nowMs)
                if (r is AreaResult.Success) {
                    settings.put("garmin.backfillDone.${area.name}", "true")
                }
                r
            }
        }
        val actRes = runCatching {
            val count = syncGarminActivities(nowMs)
            val st = sl.db.areaStates().get(SyncArea.ACTIVITIES.name)
            if (st?.lastSuccessMs != null && st.lastError.isNullOrBlank()) {
                AreaResult.Success(count)
            } else {
                AreaResult.Failed(app.pulse4.provider.ProviderError.Server(500))
            }
        }.getOrElse { AreaResult.Failed(app.pulse4.provider.ProviderError.Server(500)) }
        runCatching { sl.withings.runImport(nowMs) }
        runCatching { sl.withings.syncAndForwardChildWeights(nowMs) }
        res?.plus(SyncArea.ACTIVITIES to actRes)
    }

    internal fun deduplicateBodyBatteryRows(db: AppDatabase) {
        val rows = db.observations().forTypeInRange(MetricType.BODY_BATTERY.name, "1970-01-01", "2999-12-31")
        if (rows.isEmpty()) return
        val toDelete = ArrayList<String>()
        val toUpsert = ArrayList<ObservationRow>()
        val grouped = rows.filter { !it.localDate.isNullOrBlank() }
            .groupBy { Triple(it.provider, it.accountId, it.localDate!!) }
        for ((key, group) in grouped) {
            val (providerName, accountId, localDate) = key
            if (providerName != Provider.GARMIN_PERSONAL.name) continue
            val canonicalId = app.pulse4.domain.ImportKey.of(
                Provider.GARMIN_PERSONAL,
                accountId,
                null,
                listOf(MetricType.BODY_BATTERY.name, localDate),
            )
            val newest = group.maxWithOrNull(
                compareBy<ObservationRow> { it.endUtcMs ?: it.startUtcMs }.thenBy { it.startUtcMs }
            ) ?: continue
            val dupIds = group.map { it.id }.filter { it != canonicalId }
            if (dupIds.isNotEmpty()) {
                toUpsert += newest.copy(id = canonicalId)
                toDelete += dupIds
            }
        }
        if (toUpsert.isNotEmpty() || toDelete.isNotEmpty()) {
            db.runInTransaction {
                toDelete.chunked(500).forEach { db.observations().markDeleted(it) }
                if (toUpsert.isNotEmpty()) db.observations().upsert(toUpsert)
            }
        }
    }

    /** Kompletní vymazání všech testovacích dat a reset aktivit k čistému opětovnému stažení. */
    fun clearTestDataAndResetActivities() {
        sl.db.runInTransaction {
            sl.db.activities().deleteAll()
            sl.db.activities().deleteAllTracks()
            sl.db.grants().deleteAllGroups()
            sl.db.grants().deleteAllGrants()
            sl.db.contacts().deleteAll()
            sl.db.inbox().deleteAll()
            sl.db.inbox().deleteAllReceipts()
            sl.db.inbox().deleteAllSent()
            sl.db.outbox().deleteAll()
            sl.db.observations().deleteSyntheticHistory()
            sl.db.areaStates().put(AreaStateRow(SyncArea.ACTIVITIES.name, null, null, null, null, null))
        }
        runCatching {
            File(context.noBackupFilesDir, "fit").listFiles()?.forEach { it.delete() }
        }
    }

    /** Automaticky stáhne aktivity z oficiálního Garmin Personal API (nebo přihlášené relace Garmin Connect), stáhne FIT a uloží do DB. */
    fun syncGarminActivities(nowMs: Long): Int = synchronized(syncLock) {
        val key = sl.secrets.getString(SecretStore.GARMIN_API_KEY)?.trim()?.removePrefix("ApiKey ")?.trim()
        val hasExt = sl.secrets.getString(SecretStore.GARMIN_EXT_TOKENS) != null
        if (key.isNullOrBlank() && !hasExt) {
            return@synchronized 0
        }
        runCatching {
            val cfg = config()
            val client = okhttp3.OkHttpClient.Builder()
                .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            var authHeader: String? = null
            if (!key.isNullOrBlank()) {
                val tokenReq = okhttp3.Request.Builder()
                    .url(cfg.token.url)
                    .header("Authorization", "ApiKey $key")
                    .post(okhttp3.FormBody.Builder().build())
                    .build()
                val tokenRes = runCatching {
                    client.newCall(tokenReq).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string().orEmpty().trim() else ""
                    }
                }
                val tokenBody = tokenRes.getOrDefault("")
                if (tokenBody.isNotBlank()) {
                    authHeader = cfg.authValue.replace("{accessToken}", tokenBody)
                } else if (!hasExt) {
                    throw (tokenRes.exceptionOrNull() ?: app.pulse4.provider.ProviderError.Unauthorized())
                }
            }

            // 2. Activities list: První sync načte kompletní historii (až 500 aktivit po 100),
            // následné synchronizace stahují pouze inkrementální přírůstky a změny (page size 30, zastaví při dosažení známých aktivit)
            val prevState = sl.db.areaStates().get(SyncArea.ACTIVITIES.name)
            val totalInDb = sl.db.activities().count()
            val isInitialSync = (prevState?.cursorMs == null || totalInDb == 0)

            val allActivityElements = mutableListOf<kotlinx.serialization.json.JsonElement>()
            val pageSize = if (isInitialSync) 100 else 30
            val maxActivitiesToFetch = if (isInitialSync) 1000 else 60
            var pageStart = 0
            var stopIncremental = false

            while (!stopIncremental) {
                val pagePath = "/activitylist-service/activities/search/activities?limit=$pageSize&start=$pageStart"
                val rawJson = if (authHeader != null) {
                    val listReq = okhttp3.Request.Builder()
                        .url("${cfg.baseUrl.trimEnd('/')}$pagePath")
                        .header(cfg.authHeader, authHeader)
                        .get()
                        .build()
                    val r = runCatching {
                        client.newCall(listReq).execute().use { resp ->
                            if (resp.isSuccessful) {
                                resp.body?.string().orEmpty()
                            } else {
                                when (resp.code) {
                                    401, 403 -> throw app.pulse4.provider.ProviderError.Unauthorized()
                                    429 -> throw app.pulse4.provider.ProviderError.RateLimited(null)
                                    else -> throw app.pulse4.provider.ProviderError.Server(resp.code)
                                }
                            }
                        }
                    }
                    when {
                        r.isSuccess && r.getOrThrow().isNotBlank() -> r.getOrThrow()
                        hasExt -> sl.garminExt.connectApiJson(pagePath)
                        r.isFailure -> throw r.exceptionOrNull()!!
                        else -> ""
                    }
                } else if (hasExt) {
                    sl.garminExt.connectApiJson(pagePath)
                } else ""

                if (rawJson.isBlank()) break
                val pageArr = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(rawJson).let { if (it is kotlinx.serialization.json.JsonArray) it else null }
                }.getOrNull() ?: break

                if (pageArr.isEmpty()) break
                allActivityElements.addAll(pageArr)

                // Pokud jde o inkrementální synchronizaci, zkontrolujeme, zda jsme již nedorazili k dříve synchronizovaným aktivitám
                if (!isInitialSync) {
                    var consecutiveExisting = 0
                    for (elem in pageArr) {
                        val actId = (elem as? kotlinx.serialization.json.JsonObject)?.get("activityId")
                            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        if (actId != null) {
                            val actKey = "garmin-$actId"
                            if (sl.db.activities().get(actKey) != null) {
                                consecutiveExisting++
                            } else {
                                consecutiveExisting = 0
                            }
                        }
                    }
                    if (consecutiveExisting >= 3 || allActivityElements.size >= maxActivitiesToFetch) {
                        stopIncremental = true
                    }
                }

                if (pageArr.size < pageSize || allActivityElements.size >= maxActivitiesToFetch) break
                pageStart += pageArr.size
            }

            if (allActivityElements.isEmpty()) {
                val prev = sl.db.areaStates().get(SyncArea.ACTIVITIES.name)
                sl.db.areaStates().put(
                    AreaStateRow(
                        SyncArea.ACTIVITIES.name,
                        prev?.cursorMs ?: nowMs,
                        nowMs,
                        nowMs,
                        if (key.isNullOrBlank() && !hasExt) "Garmin není připojen" else null,
                        null
                    )
                )
                return@runCatching 0
            }

            val (imported, latestTimestampMs) = syncGarminActivityElements(
                allActivityElements = allActivityElements,
                isInitialSync = isInitialSync,
                nowMs = nowMs,
                store = roomActivityImportStore(),
                accountId = sl.settings.accountId,
                zoneId = sl.settings.zoneId,
                downloadFit = { actId ->
                    var dl: ByteArray? = null
                    if (authHeader != null) {
                        val downloadReq = okhttp3.Request.Builder()
                            .url("${cfg.baseUrl.trimEnd('/')}/download-service/files/activity/$actId")
                            .header(cfg.authHeader, authHeader)
                            .get()
                            .build()
                        dl = runCatching { client.newCall(downloadReq).execute().use { it.body?.bytes() } }.getOrNull()
                    }
                    if ((dl == null || dl.isEmpty()) && hasExt) {
                        dl = runCatching { sl.garminExt.downloadActivityFit(actId) }.getOrNull()
                    }
                    dl
                },
                fetchDetailsJson = { actId ->
                    if (hasExt) runCatching { sl.garminExt.getActivityDetailsJson(actId) }.getOrDefault("") else ""
                },
                sealFit = { hash, fit ->
                    val dir = File(context.noBackupFilesDir, "fit").apply { mkdirs() }
                    File(dir, "$hash.fit.enc").writeBytes(sl.vault.seal(fit))
                },
                throttleMs = 80L,
            )

            val cursor = latestTimestampMs ?: prevState?.cursorMs ?: nowMs
            sl.db.areaStates().put(AreaStateRow(SyncArea.ACTIVITIES.name, cursor, nowMs, nowMs, null, null))
            imported
        }.getOrElse { e ->
            PLog.e("ImportService", e) { "syncGarminActivities failed" }
            val prev = sl.db.areaStates().get(SyncArea.ACTIVITIES.name)
            sl.db.areaStates().put(
                AreaStateRow(
                    SyncArea.ACTIVITIES.name,
                    prev?.cursorMs,
                    prev?.lastSuccessMs,
                    nowMs,
                    e.message ?: "Chyba synchronizace aktivit",
                    null
                )
            )
            0
        }
    }

    private fun roomActivityImportStore(): ActivityImportStore = object : ActivityImportStore {
        override fun get(id: String): ActivityRow? = sl.db.activities().get(id)
        override fun track(id: String): TrackRow? = sl.db.activities().track(id)
        override fun upsert(row: ActivityRow) = sl.db.activities().upsert(row)
        override fun upsertTrack(row: TrackRow) = sl.db.activities().upsertTrack(row)
        override fun inTransaction(block: () -> Unit) = sl.db.runInTransaction(block)
    }

    /** Na vyžádání (lazy) dotáhne FIT nebo GPS křivku pro zadanou aktivitu, pokud ji ještě nemá v lokální DB. */
    fun ensureActivityTrack(actKey: String): Boolean {
        val hasExt = sl.secrets.getString(SecretStore.GARMIN_EXT_TOKENS) != null
        return ensureActivityTrackWith(
            actKey = actKey,
            store = roomActivityImportStore(),
            hasSession = hasExt,
            downloadFit = { actId -> sl.garminExt.downloadActivityFit(actId) },
            fetchDetailsJson = { actId -> sl.garminExt.getActivityDetailsJson(actId) },
            nowMs = System.currentTimeMillis(),
            accountId = sl.settings.accountId,
            zoneId = sl.settings.zoneId,
            sealFit = { hash, rawFit ->
                val dir = File(context.noBackupFilesDir, "fit").apply { mkdirs() }
                File(dir, "$hash.fit.enc").writeBytes(sl.vault.seal(rawFit))
            },
        )
    }

    private fun saveRaw(label: String, body: String, nowMs: Long) {
        val hash = Wire.sha256Hex(body.toByteArray())
        sl.db.sources().insert(SourceRecordRow("$label|$hash", Provider.GARMIN_PERSONAL.name, sl.settings.accountId, body, hash, nowMs))
    }

    /** Import FIT (stažený nebo vybraný ručně). Chybný soubor se zachová k diagnostice, ale historii nerozbije. */
    fun importFit(
        bytes: ByteArray,
        nowMs: Long,
        garminObj: kotlinx.serialization.json.JsonObject? = null,
        targetId: String? = null,
    ): FitImportResult {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val dir = File(context.noBackupFilesDir, "fit").apply { mkdirs() }
        // Příloha mimo databázi: autentizované šifrování přes Keystore.
        File(dir, "$hash.fit.enc").writeBytes(sl.vault.seal(bytes))
        return when (val r = FitImporter.parse(bytes)) {
            is FitResult.Invalid -> FitImportResult.Rejected(r.reason)
            is FitResult.Ok -> importParsedFitSessions(
                parsed = r,
                hash = hash,
                nowMs = nowMs,
                garminObj = garminObj,
                targetId = targetId,
                store = roomActivityImportStore(),
                accountId = sl.settings.accountId,
                zoneId = sl.settings.zoneId,
            )
        }
    }

    /** Doplní normalizované sloupce souhrnu (title, distance, time, calories atd.) u starších záznamů bez nutnosti číst JSON v seznamu. */
    fun backfillActivitySummaries(): Int {
        var updated = 0
        var iterations = 0
        while (iterations < 100) {
            iterations++
            val batch = sl.db.activities().unbackfilled(100)
            if (batch.isEmpty()) break
            sl.db.runInTransaction {
                for (item in batch) {
                    val a = runCatching { Wire.json.decodeFromString(Activity.serializer(), item.json) }.getOrNull()
                    if (a != null) {
                        sl.db.activities().updateSummary(
                            id = item.id,
                            title = a.title?.takeIf { it.isNotBlank() } ?: a.sport.czechName(),
                            distanceM = a.distanceM,
                            movingTimeS = a.movingTimeS ?: a.totalTimeS,
                            ascentM = a.ascentM,
                            avgHeartRate = a.avgHeartRate,
                            maxHeartRate = a.maxHeartRate,
                            caloriesKcal = a.caloriesKcal,
                            aerobicTe = a.aerobicTrainingEffect,
                            anaerobicTe = a.anaerobicTrainingEffect,
                            trainingLoad = a.trainingLoad,
                            avgPowerW = a.avgPowerW,
                            avgCadence = a.avgCadence,
                            zoneId = a.zoneId
                        )
                        updated++
                    } else {
                        sl.db.activities().markBackfillFailed(item.id)
                    }
                }
            }
        }
        return updated
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
    private val planLock = Any()

    fun grants(): List<ShareGrant> = sl.db.grants().allNow().map { json.decodeFromString(ShareGrant.serializer(), it.json) }
    fun groups(): Map<String, Group> = sl.db.grants().groupsNow().associate { it.id to json.decodeFromString(Group.serializer(), it.json) }
    fun familyLinks(): List<FamilyLink> = sl.db.familyLinks().allNow().map { json.decodeFromString(FamilyLink.serializer(), it.json) }
    fun pendingRequests(): List<PendingRequestRow> = sl.db.pendingRequests().pendingNow()

    private fun saveGrant(g: ShareGrant) {
        if (g.active) {
            saveGrantWithPolicy(g).getOrThrow()
        } else {
            saveGrantDirect(g)
        }
    }

    private fun saveGrantDirect(g: ShareGrant) =
        sl.db.grants().upsert(GrantRow(g.id, !g.active, json.encodeToString(ShareGrant.serializer(), g)))

    fun saveGroup(g: Group) = sl.db.grants().upsertGroup(GroupRow(g.id, g.version, json.encodeToString(Group.serializer(), g)))

    fun saveGrantWithPolicy(grant: ShareGrant, guardianCoConsentAtMs: Long? = null): Result<ShareGrant> {
        if (!grant.active) {
            saveGrantDirect(grant)
            return Result.success(grant)
        }
        val self = sl.bridge.ensureStarted().deliveryHash
        val ageProfile = AgeProfile.valueOf(sl.settings.ownAgeProfile)
        if (!FamilyLinkPolicy.canActivateShareGrant(self, ageProfile, grant, familyLinks(), guardianCoConsentAtMs)) {
            return Result.failure(
                IllegalStateException("Sdílení nelze aktivovat: pro nezletilý účet je nutná ověřená rodinná vazba a souhlas zákonného zástupce.")
            )
        }
        saveGrantDirect(grant)
        return Result.success(grant)
    }

    fun sendFamilyLinkRequest(
        childId: String,
        childAgeProfile: AgeProfile = AgeProfile.MINOR,
        nowMs: Long = System.currentTimeMillis(),
    ): FamilyLink {
        val self = sl.bridge.ensureStarted().deliveryHash
        val link = FamilyLinkPolicy.createRequest(
            id = java.util.UUID.randomUUID().toString(),
            parentId = self,
            childId = childId,
            childAgeProfile = childAgeProfile,
            nowMs = nowMs,
        )
        val linkJson = json.encodeToString(FamilyLink.serializer(), link)
        sl.db.familyLinks().upsert(FamilyLinkRow(link.id, link.parentId, link.childId, link.status.name, link.version, linkJson))
        val msg = MessageBuilder(self).build(
            recipient = childId,
            kind = Kinds.FAMILY_LINK_REQUEST,
            objectType = "family_link",
            objectId = link.id,
            objectVersion = link.version,
            payload = linkJson,
            nowMs = nowMs,
        )
        val e = msg.envelope
        sl.outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
        return link
    }

    fun respondFamilyLink(
        linkId: String,
        accept: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): FamilyLink? {
        val row = sl.db.familyLinks().get(linkId) ?: return null
        val link = json.decodeFromString(FamilyLink.serializer(), row.json)
        val self = sl.bridge.ensureStarted().deliveryHash
        val updated = runCatching { FamilyLinkPolicy.respond(link, self, accept, nowMs) }.getOrNull() ?: return null
        val updatedJson = json.encodeToString(FamilyLink.serializer(), updated)
        sl.db.familyLinks().upsert(FamilyLinkRow(updated.id, updated.parentId, updated.childId, updated.status.name, updated.version, updatedJson))
        sl.db.pendingRequests().get(linkId)?.let { req ->
            sl.db.pendingRequests().upsert(
                req.copy(
                    status = if (accept) "ACCEPTED" else "REJECTED",
                    json = updatedJson,
                )
            )
        }
        val msg = MessageBuilder(self).build(
            recipient = updated.parentId,
            kind = Kinds.FAMILY_LINK_RESPONSE,
            objectType = "family_link",
            objectId = updated.id,
            objectVersion = updated.version,
            payload = updatedJson,
            nowMs = nowMs,
        )
        val e = msg.envelope
        sl.outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
        return updated
    }

    fun promoteCircleAdmin(groupId: String, targetMemberId: String): Result<Group> {
        val group = groups()[groupId] ?: return Result.failure(IllegalArgumentException("Kruh nenalezen."))
        val self = sl.bridge.ensureStarted().deliveryHash
        val updated = CircleAdminPolicy.promoteAdmin(group, self, targetMemberId)
            ?: return Result.failure(IllegalStateException("Nelze povýšit člena na správce."))
        saveGroup(updated)
        broadcastMembershipUpdate(updated, System.currentTimeMillis())
        return Result.success(updated)
    }

    fun demoteCircleAdmin(groupId: String, targetMemberId: String): Result<Group> {
        val group = groups()[groupId] ?: return Result.failure(IllegalArgumentException("Kruh nenalezen."))
        val self = sl.bridge.ensureStarted().deliveryHash
        val updated = CircleAdminPolicy.demoteAdmin(group, self, targetMemberId)
            ?: return Result.failure(IllegalStateException("Nelze odebrat roli správce (kruh musí mít alespoň jednoho správce)."))
        saveGroup(updated)
        broadcastMembershipUpdate(updated, System.currentTimeMillis())
        return Result.success(updated)
    }

    fun initiateAdminHandover(
        groupId: String,
        targetMemberId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Result<Group> {
        val group = groups()[groupId] ?: return Result.failure(IllegalArgumentException("Kruh nenalezen."))
        val self = sl.bridge.ensureStarted().deliveryHash
        val updated = CircleAdminPolicy.initiateHandover(group, self, targetMemberId, nowMs)
            ?: return Result.failure(IllegalStateException("Nelze zahájit předání správy kruhu."))
        saveGroup(updated)
        broadcastMembershipUpdate(updated, nowMs)
        val payload = json.encodeToString(Group.serializer(), updated)
        val msg = MessageBuilder(self).build(
            recipient = targetMemberId,
            kind = Kinds.ADMIN_HANDOVER_REQUEST,
            objectType = "group",
            objectId = updated.id,
            objectVersion = updated.version,
            payload = payload,
            nowMs = nowMs,
        )
        val e = msg.envelope
        sl.outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
        return Result.success(updated)
    }

    fun acceptAdminHandover(
        groupId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Result<Group> {
        val group = groups()[groupId] ?: return Result.failure(IllegalArgumentException("Kruh nenalezen."))
        val self = sl.bridge.ensureStarted().deliveryHash
        val initiator = group.pendingAdminHandover?.initiatedBy
        val updated = CircleAdminPolicy.acceptHandover(group, self, nowMs, removeInitiator = true)
            ?: return Result.failure(IllegalStateException("Žádost o předání správy není platná nebo vypršela."))
        saveGroup(updated)
        val payload = json.encodeToString(Group.serializer(), updated)
        sl.db.pendingRequests().get("handover-${group.id}")?.let { req ->
            sl.db.pendingRequests().upsert(req.copy(status = "ACCEPTED", json = payload))
        }
        val extra = setOfNotNull(initiator)
        broadcastMembershipUpdate(updated, nowMs, extraRecipients = extra)
        val builder = MessageBuilder(self)
        for (r in (updated.members + extra) - self) {
            val msg = builder.build(
                recipient = r,
                kind = Kinds.ADMIN_HANDOVER_ACCEPT,
                objectType = "group",
                objectId = updated.id,
                objectVersion = updated.version,
                payload = payload,
                nowMs = nowMs,
            )
            val e = msg.envelope
            sl.outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
        }
        return Result.success(updated)
    }

    fun leaveCircle(groupId: String): Result<Group> {
        val group = groups()[groupId] ?: return Result.failure(IllegalArgumentException("Kruh nenalezen."))
        val self = sl.bridge.ensureStarted().deliveryHash
        if (!CircleAdminPolicy.canLeaveDirectly(group, self)) {
            return Result.failure(
                IllegalStateException("Jste poslední správce kruhu. Před odchodem musíte nejprve předat roli správce jinému členovi.")
            )
        }
        val prevMembers = group.members
        val updated = CircleAdminPolicy.leaveCircle(group, self)
            ?: return Result.failure(IllegalStateException("Kruh nelze opustit."))
        saveGroup(updated)
        broadcastMembershipUpdate(updated, System.currentTimeMillis(), extraRecipients = prevMembers - updated.members - self)
        return Result.success(updated)
    }

    fun dissolveCircle(groupId: String): Result<Group> {
        val group = groups()[groupId] ?: return Result.failure(IllegalArgumentException("Kruh nenalezen."))
        val self = sl.bridge.ensureStarted().deliveryHash
        val prevMembers = group.members
        val updated = CircleAdminPolicy.dissolveCircle(group, self)
            ?: return Result.failure(IllegalStateException("Kruh může rozpustit pouze správce."))
        saveGroup(updated)
        val nowMs = System.currentTimeMillis()
        for (g in grants().filter { it.active && it.groupId == groupId }) {
            revoke(g.id, nowMs)
        }
        broadcastMembershipUpdate(updated, nowMs, extraRecipients = prevMembers - self)
        return Result.success(updated)
    }

    /** Odebere člena ze skupiny, zruší jeho čekající položky v outboxu, vyřadí ho z vlastních grantů a rozešle aktualizaci členství. */
    fun removeMemberFromGroup(group: Group, memberId: String): Group {
        val self = sl.bridge.ensureStarted().deliveryHash
        val updated = group.copy(
            members = group.members - memberId,
            admins = if (memberId in group.admins) group.admins - memberId else group.admins,
            version = group.version + 1,
        )
        val groupGrants = grants().filter { it.groupId == group.id }
        val groupGrantIds = groupGrants.mapTo(HashSet()) { it.id }
        synchronized(sl.outbox) {
            sl.db.outbox().all()
                .filter {
                    it.recipient == memberId &&
                        it.state == app.pulse4.sync.DeliveryState.QUEUED.name &&
                        it.grantId in groupGrantIds
                }
                .forEach { row ->
                    sl.db.outbox().update(row.copy(state = app.pulse4.sync.DeliveryState.CANCELLED.name))
                }
        }
        for (g in groupGrants) {
            if (g.ownerId == self && memberId in g.recipients) {
                // Zúžení příjemců (odebrání člena) nesmí selhat na politice spolupodpisu nezletilého (RE-7).
                saveGrantDirect(g.copy(recipients = g.recipients - memberId))
            }
        }
        saveGroup(updated)
        broadcastMembershipUpdate(updated, System.currentTimeMillis(), extraRecipients = setOf(memberId))
        return updated
    }

    /** Uloží kruh a ihned rozešle aktualizaci členství všem členům (a případným odebraným) + spustí synchronizaci. */
    fun saveAndBroadcastGroup(
        group: Group,
        extraRecipients: Set<String> = emptySet(),
    ) {
        saveGroup(group)
        broadcastMembershipUpdate(group, System.currentTimeMillis(), extraRecipients = extraRecipients)
    }

    private fun broadcastMembershipUpdate(
        group: Group,
        nowMs: Long,
        extraRecipients: Set<String> = emptySet(),
    ) {
        val self = sl.bridge.ensureStarted().deliveryHash
        val builder = MessageBuilder(self)
        val payload = json.encodeToString(Group.serializer(), group)
        for (r in (group.members + extraRecipients) - self) {
            val msg = builder.build(
                recipient = r,
                kind = Kinds.MEMBERSHIP_UPDATE,
                objectType = "group",
                objectId = group.id,
                objectVersion = group.version,
                payload = payload,
                nowMs = nowMs,
            )
            val e = msg.envelope
            sl.outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
        }
        if (sl.settings.networkMode != app.pulse4.android.data.NetworkMode.MANUAL) {
            app.pulse4.android.work.Scheduler.shareNow(sl.context)
        }
    }

    /** Vytvoří projekce změněných dat pro každého skutečného příjemce a zařadí je do outboxu. Vrací počet zařazených. */
    fun planAndEnqueue(nowMs: Long): Int = synchronized(planLock) {
        ServiceMigrations.runOnce(sl)
        val self = sl.bridge.ensureStarted().deliveryHash
        val zone = ZoneId.of(sl.settings.zoneId)
        val today = LocalDate.now(zone)
        val activeGrants = grants().filter { it.active && it.ownerId == self }
        val earliestGrantFrom = activeGrants.mapNotNull { it.scope.from?.let { f -> runCatching { LocalDate.parse(f) }.getOrNull() } }.minOrNull()
        val from = earliestGrantFrom ?: today.minusDays(60)
        // Řadíme od nejnovějších dní, aby dnešek a včerejšek měly vždy přednost před starší historií.
        val dates = sl.db.observations().datesWithData(from.toString(), today.toString())
            .map(LocalDate::parse)
            .sortedDescending()
        val allObservations = sl.db.observations().forRange(from.toString(), today.toString()).map { it.toDomain() }
        // Nejdřív nahradit prošlé nepotvrzené souhrny čerstvým snapshotem.
        sl.outbox.expireStale(nowMs).forEach { (recipient, key) -> sl.sentLedger.record(recipient, key, (sl.sentLedger.last(recipient, key)?.version ?: 0), "expired") }

        val recentDateCutoff = today.minusDays(1).toString()
        val pendingByRecipient = HashMap<String, Int>()
        fun pendingFor(recipient: String): Int = pendingByRecipient.getOrPut(recipient) { sl.outbox.pendingCountFor(recipient) }

        var queued = 0
        val plan = ShareBuilder(self, MessageBuilder(self)).plan(grants(), groups(), dates, allObservations, sl.sentLedger, nowMs)
            .associateBy { it.message.envelope.recipient to it.objectKey }
            .values
        val maxEventBytes = app.pulse4.sync.ProtocolLimits().maxEventBytes
        for (p in plan) {
            val e = p.message.envelope
            val datePart = p.objectKey.removePrefix("daily/")
            val isHistorical = datePart < recentDateCutoff
            if (isHistorical && pendingFor(e.recipient) >= MAX_PENDING_PER_RECIPIENT) {
                continue
            }
            val item = OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, p.grantId, Wire.encode(p.message), nowMs)
            when (sl.outbox.enqueue(item)) {
                is EnqueueResult.Queued -> {
                    sl.sentLedger.record(e.recipient, p.objectKey, e.objectVersion, p.contentHash)
                    recordDeliveredKey(p.grantId, e.recipient, p.objectKey)
                    pendingByRecipient[e.recipient] = pendingFor(e.recipient) + 1
                    queued++
                }
                EnqueueResult.OverQuota -> {
                    if (item.sizeBytes > maxEventBytes) continue
                    return@synchronized queued // fronta plná: vlastní archiv zůstává, uživatel uvidí stav
                }
            }
        }
        queued + enqueueActivities(self, nowMs, from, pendingByRecipient)
    }

    private fun enqueueActivities(
        self: String,
        nowMs: Long,
        from: LocalDate,
        pendingByRecipient: MutableMap<String, Int> = HashMap(),
    ): Int {
        val zone = ZoneId.of(sl.settings.zoneId)
        val zones = sl.settings.privacyZones
        val recentActCutoffMs = LocalDate.now(zone).minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        var queued = 0
        val acts = sl.db.activities().since(from.atStartOfDay(zone).toInstant().toEpochMilli())
            .sortedByDescending { it.startUtcMs }
        val allGroups = groups()
        val activeActivityGrants = grants().filter {
            it.active && it.ownerId == self && it.fields.any { f -> f.category == app.pulse4.sharing.Category.ACTIVITY }
        }
        val recipientsByGrant = activeActivityGrants.associateWith {
            app.pulse4.sharing.MembershipPolicy.effectiveRecipients(it, it.groupId?.let(allGroups::get))
        }
        val allRecipients = recipientsByGrant.values.flatten().toSortedSet()
        PLog.i("Pulse4Sync") { "enqueueActivities: from=$from, acts=${acts.size}, activeGrants=${activeActivityGrants.size}" }
        for (row in acts) {
            val act = json.decodeFromString(Activity.serializer(), row.json)
            val track = sl.db.activities().track(row.id)?.let {
                json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(TrackPoint.serializer()), it.json)
            }.orEmpty()
            val actDate = java.time.Instant.ofEpochMilli(act.startUtcMs).atZone(zone).toLocalDate()
            val isHistorical = act.startUtcMs < recentActCutoffMs
            // Slučování grantů aktivit per příjemce stejně jako S4 v ShareBuilderu (RE-6).
            for (r in allRecipients) {
                val matching = activeActivityGrants
                    .filter { r in recipientsByGrant.getValue(it) && it.allowsProvider(act.provider) && it.scope.covers(actDate) }
                    .sortedBy { it.id }
                if (matching.isEmpty()) continue
                val mergedGrant = mergeActivityGrantsForRecipient(matching)
                val proj = ActivityProjector.project(mergedGrant, act, track, emptyList<LapIn>(), zones)
                if (proj == null) continue
                val payload = json.encodeToString(app.pulse4.sharing.ActivityProjection.serializer(), proj)
                val hash = Wire.sha256Hex(payload.toByteArray())
                val curPending = pendingByRecipient.getOrPut(r) { sl.outbox.pendingCountFor(r) }
                if (isHistorical && curPending >= MAX_PENDING_PER_RECIPIENT) {
                    continue
                }
                val key = "activity/${act.id}"
                val last = sl.sentLedger.last(r, key)
                if (last?.contentHash == hash) {
                    PLog.i("Pulse4Sync") { "act=${act.id} skipped, same hash as last sent" }
                    continue
                }
                val msg = MessageBuilder(self).build(r, Kinds.PROJECTION_UPSERT, "activity", act.id, (last?.version ?: 0) + 1, payload, nowMs, grantRef = mergedGrant.id)
                val e = msg.envelope
                val item = OutboxItem(e.eventId, r, e.kind, e.objectType, e.objectId, e.objectVersion, mergedGrant.id, Wire.encode(msg), nowMs)
                val res = sl.outbox.enqueue(item)
                PLog.i("Pulse4Sync") { "act=${act.id} enqueue result: $res" }
                when (res) {
                    is EnqueueResult.Queued -> {
                        sl.sentLedger.record(r, key, e.objectVersion, hash)
                        matching.forEach { recordDeliveredKey(it.id, r, key) }
                        pendingByRecipient[r] = curPending + 1
                        queued++
                    }
                    EnqueueResult.OverQuota -> {
                        if (item.sizeBytes > app.pulse4.sync.ProtocolLimits().maxEventBytes) continue
                        return queued
                    }
                }
            }
        }
        return queued
    }

    private companion object {
        const val MAX_PENDING_PER_RECIPIENT = 25
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
                val allGroups = groups()
                val viewers = grants()
                    .filter { it.active && it.planRight(rev.planId) != null }
                    .flatMap { app.pulse4.sharing.MembershipPolicy.effectiveRecipients(it, it.groupId?.let(allGroups::get)) }
                    .toSet() + note.toIdentity
                for (v in viewers) put(b.build(v, Kinds.PLAN_REVISION, "plan", rev.planId, rev.revision,
                    json.encodeToString(app.pulse4.training.PlanRevision.serializer(), rev), nowMs))
            }
        }
        return n
    }

    private val deliveredKeysSerializer = kotlinx.serialization.builtins.MapSerializer(
        String.serializer(),
        kotlinx.serialization.builtins.SetSerializer(String.serializer())
    )

    private fun recordDeliveredKey(grantId: String, recipient: String, objectKey: String) {
        val k = "delivered_keys.$grantId"
        val current = sl.settings.getRaw(k)?.let {
            runCatching { json.decodeFromString(deliveredKeysSerializer, it) }.getOrNull()
        }.orEmpty().toMutableMap()
        val updatedSet = (current[recipient].orEmpty() + objectKey)
        if (updatedSet != current[recipient]) {
            current[recipient] = updatedSet
            sl.settings.put(k, json.encodeToString(deliveredKeysSerializer, current))
        }
    }

    private fun deliveredKeysForGrant(grantId: String): Map<String, Set<String>> {
        val k = "delivered_keys.$grantId"
        return sl.settings.getRaw(k)?.let {
            runCatching { json.decodeFromString(deliveredKeysSerializer, it) }.getOrNull()
        }.orEmpty()
    }

    /**
     * Odvolání: zruší čekající položky, odešle požadavek na odstranění už doručených kopií a vrátí plán s popisem omezení,
     * který UI musí uživateli ukázat přesně (doručená data nelze vzít zpět).
     */
    fun revoke(grantId: String, nowMs: Long): RevocationPlan? {
        val row = sl.db.grants().get(grantId) ?: return null
        val g = json.decodeFromString(ShareGrant.serializer(), row.json)
        val self = sl.bridge.ensureStarted().deliveryHash
        val allOutbox = sl.db.outbox().all()
        val outboxSentRows = allOutbox.filter {
            it.grantId == grantId && it.state !in setOf("CANCELLED", "SUPERSEDED")
        }
        val durableDelivered = deliveredKeysForGrant(grantId)
        val allRecipients = (g.recipients + durableDelivered.keys + outboxSentRows.map { it.recipient }).distinct()
        val delivered = allRecipients.associateWith { r ->
            val fromOutbox = outboxSentRows.filter { it.recipient == r }.map { "${it.objectType}/${it.objectId}" }
            val fromDurable = durableDelivered[r].orEmpty()
            (fromOutbox + fromDurable).distinct()
        }
        val inNode = allOutbox.any { it.grantId == grantId && it.state == "STORED_IN_PROPAGATION" }
        val plan = Revocation.plan(g, nowMs, delivered, inNode)
        saveGrantDirect(plan.revokedGrant)
        sl.outbox.cancelForGrant(plan.cancelQueuedForGrantId)
        sl.settings.put("delivered_keys.$grantId", "{}")
        val allGroups = groups()
        val otherActiveGrants = grants().filter { it.id != grantId && it.active && it.ownerId == self }
        val zone = ZoneId.of(sl.settings.zoneId)
        fun isCoveredByOtherGrant(recipient: String, objectKey: String): Boolean {
            return otherActiveGrants.any { other ->
                val recipients = app.pulse4.sharing.MembershipPolicy.effectiveRecipients(other, other.groupId?.let(allGroups::get))
                if (recipient !in recipients) return@any false
                when {
                    objectKey.startsWith("daily/") -> {
                        val date = runCatching { LocalDate.parse(objectKey.removePrefix("daily/")) }.getOrNull() ?: return@any false
                        other.fields.any { it.category != app.pulse4.sharing.Category.ACTIVITY } && other.scope.covers(date)
                    }
                    objectKey.startsWith("activity/") -> {
                        if (other.fields.none { it.category == app.pulse4.sharing.Category.ACTIVITY }) return@any false
                        val actRow = sl.db.activities().get(objectKey.removePrefix("activity/"))
                        val actDate = actRow?.let {
                            java.time.Instant.ofEpochMilli(it.startUtcMs).atZone(zone).toLocalDate()
                        }
                        val provOk = actRow?.let {
                            runCatching { json.decodeFromString(Activity.serializer(), it.json).provider }.getOrNull()?.let(other::allowsProvider) ?: true
                        } ?: true
                        provOk && (actDate == null || other.scope.covers(actDate))
                    }
                    else -> false
                }
            }
        }
        // NEW-1: ledger maž pouze pro klíče, kam se skutečně posílá PROJECTION_REMOVE;
        // u klíčů krytých jiným grantem smaž jen contentHash a zachovej monotonní verzi N+1.
        for ((recipient, keys) in delivered) {
            for (key in keys) {
                val updatedSent = updateSentRowOnRevoke(
                    existing = sl.db.inbox().sent(recipient, key),
                    isCoveredByOtherGrant = isCoveredByOtherGrant(recipient, key),
                )
                if (updatedSent != null) {
                    sl.db.inbox().putSent(updatedSent)
                } else if (!isCoveredByOtherGrant(recipient, key)) {
                    sl.db.inbox().deleteSent(recipient, key)
                }
            }
        }
        for (rq in plan.removalRequests) {
            if (isCoveredByOtherGrant(rq.recipient, rq.objectKey)) continue
            val (type, id) = rq.objectKey.split('/', limit = 2)
            val msg = MessageBuilder(self).build(rq.recipient, Kinds.PROJECTION_REMOVE, type, id, nowMs, "{}", nowMs, grantRef = grantId)
            val e = msg.envelope
            sl.outbox.enqueue(OutboxItem(e.eventId, rq.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
        }
        return plan
    }
}

internal fun isValidChildBodyComposition(type: MetricType, value: Double): Boolean {
    if (!value.isFinite()) return false
    return when (type) {
        MetricType.WEIGHT -> value in 1.0..400.0
        MetricType.BODY_FAT -> value in 0.5..90.0
        MetricType.MUSCLE_MASS, MetricType.FAT_FREE_MASS, MetricType.BONE_MASS -> value in 0.1..250.0
        MetricType.BODY_WATER -> value in 0.5..90.0
        MetricType.VISCERAL_FAT -> value in 0.0..60.0
        MetricType.VASCULAR_AGE, MetricType.METABOLIC_AGE -> value in 1.0..120.0
        MetricType.PULSE_WAVE_VELOCITY -> value in 1.0..30.0
        MetricType.BMR -> value in 200.0..10000.0
        MetricType.ECG_HR -> value in 20.0..300.0
        MetricType.ECG_QTC, MetricType.ECG_QRS -> value in 10.0..1000.0
        MetricType.ECG_AFIB, MetricType.ECG -> value in 0.0..1000.0
        else -> false
    }
}

internal fun multisportSessionIds(baseId: String, baseExtId: String, sessionCount: Int): List<Pair<String, String>> =
    (0 until sessionCount).map { idx ->
        if (idx == 0) baseId to baseExtId else "$baseId-s$idx" to "$baseExtId-s$idx"
    }

internal fun mergeActivityGrantsForRecipient(matching: List<ShareGrant>): ShareGrant {
    val sorted = matching.sortedBy { it.id }
    if (sorted.size == 1) return sorted.first()
    val mergedFields = sorted.flatMap { it.fields }.toSet()
    val mergedSources = if (sorted.any { it.sources == null }) null else sorted.flatMap { it.sources.orEmpty() }.toSet()
    return sorted.first().copy(fields = mergedFields, sources = mergedSources)
}

internal fun updateSentRowOnRevoke(existing: SentRow?, isCoveredByOtherGrant: Boolean): SentRow? =
    if (isCoveredByOtherGrant) existing?.copy(contentHash = "") else null

/**
 * Jednorázové datové migrace při startu služeb:
 * - RE-1: přepis uložených `pulse.delivery` hashů na `lxmf.delivery` z uložených veřejných klíčů
 * - RE-5: zachování `acceptWeightFromParent = true` pro existující ověřené dětské účty
 * - NEW-12: vyčištění dříve uložených vymyšlených hodnot (`staminaPct`, `performanceCondition`, prázdný výkon apod.)
 */
object ServiceMigrations {
    private const val KEY_IDENTITY_V1 = "migration.lxmf_identity_v1"
    private const val KEY_ACCEPT_WEIGHT_V1 = "migration.accept_weight_default_v1"
    private const val KEY_FABRICATED_CLEANUP_V1 = "migration.fabricated_activity_cleanup_v1"

    fun deriveDeliveryHashes(publicKeyB64: String): Pair<String, String> {
        val pubBytes = runCatching { java.util.Base64.getDecoder().decode(publicKeyB64) }.getOrNull()
            ?: publicKeyB64.toByteArray(Charsets.UTF_8)
        val pub64 = if (pubBytes.size >= 64) pubBytes.copyOf(64) else ByteArray(64).also {
            System.arraycopy(pubBytes, 0, it, 0, minOf(pubBytes.size, 64))
        }
        val sha256 = MessageDigest.getInstance("SHA-256")
        val identHash = sha256.digest(pub64).copyOf(16)
        val lxmfNameHash = sha256.digest("lxmf.delivery".toByteArray(Charsets.UTF_8)).copyOf(10)
        val legacyNameHash = sha256.digest("pulse.delivery".toByteArray(Charsets.UTF_8)).copyOf(10)
        val lxmfHex = sha256.digest(lxmfNameHash + identHash).copyOf(16).joinToString("") { "%02x".format(it) }
        val legacyHex = sha256.digest(legacyNameHash + identHash).copyOf(16).joinToString("") { "%02x".format(it) }
        return lxmfHex to legacyHex
    }

    fun migrateLegacyPulseIdentities(
        db: AppDatabase,
        settings: Settings,
        selfPublicKeyB64: String,
        json: kotlinx.serialization.json.Json = Wire.json,
    ): Int {
        val hashMap = LinkedHashMap<String, String>()
        if (selfPublicKeyB64.isNotBlank()) {
            val (lxmf, legacy) = deriveDeliveryHashes(selfPublicKeyB64)
            if (legacy != lxmf) hashMap[legacy] = lxmf
        }
        val contacts = db.contacts().allNow()
        for (c in contacts) {
            if (c.publicKeyB64.isBlank()) continue
            val (lxmf, legacy) = deriveDeliveryHashes(c.publicKeyB64)
            if (legacy != lxmf && (c.identity == legacy || c.identity == lxmf)) {
                hashMap[legacy] = lxmf
            }
        }
        if (hashMap.isEmpty()) {
            settings.put(KEY_IDENTITY_V1, "1")
            return 0
        }
        fun mapId(id: String): String = hashMap[id] ?: id
        fun mapJsonHashes(raw: String): String {
            var out = raw
            for ((oldH, newH) in hashMap) {
                if (oldH in out) out = out.replace("\"$oldH\"", "\"$newH\"")
            }
            return out
        }

        var migrated = 0
        db.runInTransaction {
            for (c in contacts) {
                val newId = mapId(c.identity)
                if (newId != c.identity) {
                    db.contacts().delete(c.identity)
                    db.contacts().upsert(c.copy(identity = newId))
                    migrated++
                }
            }
            for (row in db.grants().allNow()) {
                val g = runCatching { json.decodeFromString(ShareGrant.serializer(), row.json) }.getOrNull() ?: continue
                val newOwner = mapId(g.ownerId)
                val newRecipients = g.recipients.mapTo(LinkedHashSet()) { mapId(it) }
                if (newOwner != g.ownerId || newRecipients != g.recipients) {
                    val updated = g.copy(ownerId = newOwner, recipients = newRecipients)
                    db.grants().upsert(row.copy(json = json.encodeToString(ShareGrant.serializer(), updated)))
                    migrated++
                }
            }
            for (row in db.grants().groupsNow()) {
                val g = runCatching { json.decodeFromString(Group.serializer(), row.json) }.getOrNull() ?: continue
                val newMembers = g.members.mapTo(LinkedHashSet()) { mapId(it) }
                val newAdmins = g.admins.mapTo(LinkedHashSet()) { mapId(it) }
                if (newMembers != g.members || newAdmins != g.admins) {
                    val updated = g.copy(members = newMembers, admins = newAdmins)
                    db.grants().upsertGroup(row.copy(json = json.encodeToString(Group.serializer(), updated)))
                    migrated++
                }
            }
            for (row in db.familyLinks().allNow()) {
                val newP = mapId(row.parentId)
                val newC = mapId(row.childId)
                val newJson = mapJsonHashes(row.json)
                if (newP != row.parentId || newC != row.childId || newJson != row.json) {
                    db.familyLinks().upsert(row.copy(parentId = newP, childId = newC, json = newJson))
                    migrated++
                }
            }
            for (row in db.pendingRequests().allNow()) {
                val newFrom = mapId(row.fromId)
                val newTo = mapId(row.toId)
                val newJson = mapJsonHashes(row.json)
                if (newFrom != row.fromId || newTo != row.toId || newJson != row.json) {
                    db.pendingRequests().upsert(row.copy(fromId = newFrom, toId = newTo, json = newJson))
                    migrated++
                }
            }
            for (row in db.outbox().all()) {
                val newRec = mapId(row.recipient)
                val decoded = runCatching { Wire.decode(row.wire) }.getOrNull()
                val newWire = if (decoded != null) {
                    val e = decoded.envelope
                    val mappedAuthor = mapId(e.author)
                    val mappedRecipient = mapId(e.recipient)
                    if (mappedAuthor != e.author || mappedRecipient != e.recipient) {
                        Wire.encode(decoded.copy(envelope = e.copy(author = mappedAuthor, recipient = mappedRecipient)))
                    } else row.wire
                } else row.wire
                if (newRec != row.recipient || !newWire.contentEquals(row.wire)) {
                    db.outbox().update(row.copy(recipient = newRec, wire = newWire))
                    migrated++
                }
            }
            for (row in db.inbox().allSentNow()) {
                val newRec = mapId(row.recipient)
                if (newRec != row.recipient) {
                    db.inbox().deleteSent(row.recipient, row.objectKey)
                    db.inbox().putSent(row.copy(recipient = newRec))
                    migrated++
                }
            }
            for (row in db.inbox().allReceivedNow()) {
                val newAuthor = mapId(row.author)
                if (newAuthor != row.author) {
                    db.inbox().removeObject(row.author, row.objectType, row.objectId)
                    db.inbox().putObject(row.copy(author = newAuthor))
                    migrated++
                }
            }
            for ((oldH, newH) in hashMap) {
                db.inbox().updateReceiptAuthor(oldH, newH)
            }
            for (row in db.plans().allRevisions()) {
                val newJson = mapJsonHashes(row.json)
                if (newJson != row.json) {
                    db.plans().restoreRevision(row.copy(json = newJson))
                    migrated++
                }
            }
            for (row in db.plans().allProposalsNow()) {
                val newJson = mapJsonHashes(row.json)
                if (newJson != row.json) {
                    db.plans().putProposal(row.copy(json = newJson))
                    migrated++
                }
            }
            val childRec = settings.getRaw("childRecipientIdentity")
            if (!childRec.isNullOrBlank()) {
                val mapped = mapId(childRec)
                if (mapped != childRec) {
                    settings.put("childRecipientIdentity", mapped)
                    migrated++
                }
            }
            for (s in db.settings().allNow()) {
                if (s.key.startsWith("delivered_keys.")) {
                    val newVal = mapJsonHashes(s.value)
                    if (newVal != s.value) {
                        db.settings().put(s.copy(value = newVal))
                        migrated++
                    }
                }
            }
            settings.put(KEY_IDENTITY_V1, "1")
        }
        return migrated
    }

    fun migrateAcceptWeightDefaultIfVerified(db: AppDatabase, settings: Settings, selfId: String) {
        if (settings.getRaw(KEY_ACCEPT_WEIGHT_V1) != null) return
        if (settings.getRaw("acceptWeightFromParent") == null) {
            val hasVerifiedChildLink = db.familyLinks().allNow().any {
                it.status == FamilyLinkStatus.VERIFIED.name && it.childId == selfId
            }
            if (hasVerifiedChildLink) {
                settings.acceptWeightFromParent = true
            }
        }
        settings.put(KEY_ACCEPT_WEIGHT_V1, "1")
    }

    fun cleanLegacyFabricatedActivity(
        act: Activity,
        track: List<TrackPoint>,
        reparsedFit: ParsedFit? = null,
    ): Pair<Activity, List<TrackPoint>> {
        val cleanedTrack = if (reparsedFit != null && reparsedFit.points.isNotEmpty()) {
            reparsedFit.points
        } else {
            track.map { pt ->
                if (pt.staminaPct != null || pt.staminaPotentialPct != null || pt.performanceCondition != null) {
                    pt.copy(staminaPct = null, staminaPotentialPct = null, performanceCondition = null)
                } else pt
            }
        }
        val cleanedAct = if (reparsedFit != null) {
            val mappedLaps = reparsedFit.laps.mapIndexed { idx, l ->
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
            act.copy(
                laps = mappedLaps,
                avgCadence = reparsedFit.avgCadence ?: act.avgCadence,
                powerZoneTimesS = if (act.avgPowerW == null) emptyList() else act.powerZoneTimesS,
            )
        } else {
            act.copy(
                powerZoneTimesS = if (act.avgPowerW == null) emptyList() else act.powerZoneTimesS,
            )
        }
        return cleanedAct to cleanedTrack
    }

    fun migrateLegacyFabricatedActivities(sl: ServiceLocator): Int {
        if (sl.settings.getRaw(KEY_FABRICATED_CLEANUP_V1) != null) return 0
        val trackSerializer = kotlinx.serialization.builtins.ListSerializer(TrackPoint.serializer())
        val fitDir = File(sl.context.noBackupFilesDir, "fit")
        var cleanedCount = 0
        sl.db.runInTransaction {
            for (row in sl.db.activities().allNow()) {
                val act = runCatching { Wire.json.decodeFromString(Activity.serializer(), row.json) }.getOrNull() ?: continue
                val trackRow = sl.db.activities().track(row.id)
                val track = trackRow?.let {
                    runCatching { Wire.json.decodeFromString(trackSerializer, it.json) }.getOrNull()
                }.orEmpty()
                val fitHash = row.id.removePrefix("fit-").substringBefore("-s").takeIf { row.id.startsWith("fit-") }
                val reparsed = fitHash?.let { h ->
                    val f = File(fitDir, "$h.fit.enc")
                    if (f.exists()) {
                        val sessionIdx = row.id.substringAfter("-s", "").toIntOrNull() ?: 0
                        runCatching {
                            val bytes = sl.vault.open(f.readBytes())
                            (FitImporter.parse(bytes) as? FitResult.Ok)?.sessions?.getOrNull(sessionIdx)
                        }.getOrNull()
                    } else null
                }
                val (newAct, newTrack) = cleanLegacyFabricatedActivity(act, track, reparsed)
                if (newAct != act) {
                    sl.db.activities().upsert(newAct.toRow(Wire.json.encodeToString(Activity.serializer(), newAct)))
                    cleanedCount++
                }
                if (trackRow != null && newTrack != track) {
                    sl.db.activities().upsertTrack(TrackRow(row.id, Wire.json.encodeToString(trackSerializer, newTrack)))
                    cleanedCount++
                }
            }
            sl.settings.put(KEY_FABRICATED_CLEANUP_V1, "1")
        }
        return cleanedCount
    }

    fun runOnce(sl: ServiceLocator) {
        runCatching {
            val info = sl.bridge.ensureStarted()
            if (sl.settings.getRaw(KEY_IDENTITY_V1) == null) {
                migrateLegacyPulseIdentities(sl.db, sl.settings, info.publicKeyB64)
            }
            migrateAcceptWeightDefaultIfVerified(sl.db, sl.settings, info.deliveryHash)
        }.onFailure { e ->
            PLog.w("ServiceMigrations", e) { "Identity/weight migration skipped or failed" }
        }
        runCatching {
            migrateLegacyFabricatedActivities(sl)
        }.onFailure { e ->
            PLog.w("ServiceMigrations", e) { "Fabricated activity cleanup failed" }
        }
    }
}
