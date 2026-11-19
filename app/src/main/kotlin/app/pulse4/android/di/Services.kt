package app.pulse4.android.di

import android.content.Context
import app.pulse4.analytics.ActivityMath
import app.pulse4.android.data.*
import app.pulse4.android.net.OkHttpGateway
import app.pulse4.android.reticulum.BridgeInfo
import app.pulse4.android.reticulum.ExtendedGarminApi
import app.pulse4.android.reticulum.GatewayHealth
import app.pulse4.android.reticulum.ReticulumBridge
import app.pulse4.android.reticulum.ReticulumTransport
import app.pulse4.android.security.CorruptedSecretIssue
import app.pulse4.android.security.KeyVault
import app.pulse4.android.security.SecretRecoveryState
import app.pulse4.android.security.SecretSealer
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
import app.pulse4.fit.FitSessionIndex
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.doubleOrNull

/** Ruční, bezzávislostní DI: pro čtyři lidi a jednu aktivitu stačí. Všechno běží v telefonu, žádný vlastní server. */
class ServiceLocator internal constructor(
    internal val context: Context,
    private val dbOverride: AppDatabase? = null,
    private val sealerOverride: SecretSealer? = null,
) {
    constructor(context: Context) : this(context, null, null)

    val vault by lazy {
        if (sealerOverride != null) {
            KeyVault(
                noBackupDir = context.noBackupFilesDir,
                dbPathProvider = { context.getDatabasePath("pulse4.db") },
                sealerOverride = sealerOverride,
            )
        } else {
            KeyVault(context)
        }
    }
    val secrets by lazy { SecretStore(context, vault) }
    val recoveryIssue: StateFlow<CorruptedSecretIssue?> get() = SecretRecoveryState.issue
    val db: AppDatabase by lazy { dbOverride ?: AppDatabase.open(context, vault) }
    val settings by lazy { Settings { db } }

    private val _gatewayHealth = MutableStateFlow(GatewayHealth())
    val gatewayHealth: StateFlow<GatewayHealth> = _gatewayHealth.asStateFlow()
    private val gatewayHealthScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val migrationMutex = Mutex()
    private val migrationDone = CompletableDeferred<Unit>()
    internal var migrationRunner: (ServiceLocator) -> Unit = { ServiceMigrations.runOnce(it) }

    fun isMigrationsCompleted(): Boolean = migrationDone.isCompleted

    /** Jednorázové asynchronní spuštění a čekání na startovní migrace mimo hlavní vlákno (R3-1, R3-13). */
    suspend fun awaitMigrations() {
        if (migrationDone.isCompleted) {
            if (ServiceMigrations.needsDeferredMigration(this) && ServiceMigrations.hasSelfIdentity(secrets)) {
                withContext(Dispatchers.IO) {
                    migrationMutex.withLock {
                        migrationRunner(this@ServiceLocator)
                        settings.reloadFromDb()
                    }
                }
            }
            return
        }
        withContext(Dispatchers.IO) {
            migrationMutex.withLock {
                if (!migrationDone.isCompleted) {
                    try {
                        if (vault.probeCriticalSecrets(secrets) == null) {
                            migrationRunner(this@ServiceLocator)
                            settings.warmUp()
                        }
                    } finally {
                        migrationDone.complete(Unit)
                    }
                }
            }
        }
        migrationDone.await()
    }

    suspend fun runMigrationsAfterRecovery() {
        withContext(Dispatchers.IO) {
            migrationMutex.withLock {
                if (vault.probeCriticalSecrets(secrets) == null) {
                    migrationRunner(this@ServiceLocator)
                    settings.warmUp()
                }
            }
        }
    }

    suspend fun runDeferredMigrationsIfNeeded() {
        if (!ServiceMigrations.needsDeferredMigration(this)) return
        withContext(Dispatchers.IO) {
            migrationMutex.withLock {
                if (vault.probeCriticalSecrets(secrets) == null) {
                    migrationRunner(this@ServiceLocator)
                    settings.reloadFromDb()
                }
            }
        }
    }

    suspend fun ensureBridgeStarted(): BridgeInfo {
        awaitMigrations()
        return withContext(Dispatchers.IO) { bridge.ensureStarted() }
    }

    val outbox by lazy {
        OutboxManager(
            store = RoomOutboxStore(db),
            onPermissionDenied = { recipient, objectKey ->
                // R3-4: pro own_weight/* (FAMILY_WEIGHT_PUSH) contentHash nemažeme; resync spouští výhradně SNAPSHOT_REQUEST.
                if (!objectKey.startsWith("own_weight/")) {
                    db.inbox().sent(recipient, objectKey)?.let { row ->
                        db.inbox().putSent(row.copy(contentHash = ""))
                    }
                }
            },
        )
    }
    val sentLedger by lazy { RoomSentLedger(db) }
    val bridge by lazy {
        ReticulumBridge(context, secrets, settings).also { b ->
            gatewayHealthScope.launch {
                b.gatewayHealth.collect { _gatewayHealth.value = it }
            }
        }
    }
    val planService by lazy { PlanService(RoomPlanStore(db)) }
    val planHandlers by lazy { PlanSyncHandlers(planService) }
    val transport by lazy {
        ReticulumTransport(bridge, settings, db, outbox) {
            planHandlers.handlers() + buildFamilyAndWeightHandlers(
                data = RoomPulseDataAccess(db),
                settings = RealShareSettingsAccess(settings),
                onSnapshotRequest = { childId, nowMs ->
                    withings.onFamilyWeightSnapshotRequest(childId, nowMs)
                },
            ) + mapOf(
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
    fun allIds(): List<String> = emptyList()
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

        /** R3-7 zbytek: oprava legacy multisportu se síťovým dotazem na Garmin summary běží jednou, ne při každém syncu. */
        internal const val MULTISPORT_NET_REPAIR_FLAG = "migration.multisport_repair_net_v1"

        /**
         * Spustí [block] jen tehdy, když [flag] ještě není nastaven; po úspěchu flag nastaví.
         * Výjimka z [block] flag nenastaví (zkusí se při další synchronizaci).
         */
        internal inline fun runOnceWithFlag(
            getFlag: (String) -> String?,
            setFlag: (String, String) -> Unit,
            flag: String,
            block: () -> Unit,
        ): Boolean {
            if (getFlag(flag) == "true") return false
            block()
            setFlag(flag, "true")
            return true
        }

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
            } else if (maxHr != null && pts.size >= 2 && pts.any { it.heartRate != null }) {
                val maxRef = maxHr.coerceAtLeast(150).toDouble()
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

        internal fun normalizeGarminSummaryObj(obj: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
            val summary = obj["summaryDTO"] as? kotlinx.serialization.json.JsonObject ?: return obj
            val merged = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(obj)
            summary.forEach { (k, v) -> merged.putIfAbsent(k, v) }
            if (!merged.containsKey("activityType") && obj["activityTypeDTO"] is kotlinx.serialization.json.JsonObject) {
                merged["activityType"] = obj.getValue("activityTypeDTO")
            }
            return kotlinx.serialization.json.JsonObject(merged)
        }

        private fun synthesizeOfflineMultisportParent(
            parentId: String,
            extId: String,
            legs: List<Activity>,
            zoneId: String,
        ): Activity {
            val first = legs.first()
            val totalTimeS = legs.sumOf { it.totalTimeS }
            val movingTimeS = legs.mapNotNull { it.movingTimeS }.takeIf { it.isNotEmpty() }?.sum() ?: totalTimeS
            val distanceM = legs.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum()
            val ascentM = legs.mapNotNull { it.ascentM }.takeIf { it.isNotEmpty() }?.sum()
            val descentM = legs.mapNotNull { it.descentM }.takeIf { it.isNotEmpty() }?.sum()
            val caloriesKcal = legs.mapNotNull { it.caloriesKcal }.takeIf { it.isNotEmpty() }?.sum()
            return Activity(
                id = parentId,
                provider = Provider.GARMIN_PERSONAL,
                accountId = first.accountId,
                externalId = extId,
                sport = Sport.OTHER,
                startUtcMs = first.startUtcMs,
                zoneId = zoneId,
                totalTimeS = totalTimeS,
                movingTimeS = movingTimeS,
                distanceM = distanceM,
                ascentM = ascentM,
                descentM = descentM,
                caloriesKcal = caloriesKcal,
                title = "Multisport (${legs.size} úseky)",
                tags = setOf("multisport-summary-pending"),
            )
        }

        /**
         * R3-7: Jednorázová oprava multisport aktivit `garmin-X` importovaných verzí `9c61ec9`,
         * kde rodič `garmin-X` nesl jen 1. úsek (bez Garmin souhrnu) a podřízené úseky `-s1..-s(N-1)`
         * byly posunuty o jeden index a bez značek `multisport-leg` / `parent:`.
         */
        internal fun repairLegacyMultisportActivities(
            store: ActivityImportStore,
            garminSummariesByActKey: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap(),
            findSealedFitForParent: (parentAct: Activity, legPairs: List<Pair<String, Activity>>) -> FitResult.Ok? = { _, _ -> null },
            fetchGarminSummary: (actId: String) -> kotlinx.serialization.json.JsonObject? = { null },
            nowMs: Long,
            accountId: String,
            zoneId: String,
        ): Int {
            val allIds = store.allIds()
            if (allIds.isEmpty()) return 0
            val groupedLegs = allIds
                .filter { isMultisportLegId(it) && it.startsWith("garmin-") }
                .groupBy { parentIdFromLegId(it)!! }
            if (groupedLegs.isEmpty()) return 0

            val trackSerializer = kotlinx.serialization.builtins.ListSerializer(TrackPoint.serializer())
            var repairedCount = 0

            for ((parentId, legIds) in groupedLegs) {
                val parentRow = store.get(parentId) ?: continue
                val parentAct = runCatching { json.decodeFromString(Activity.serializer(), parentRow.json) }.getOrNull() ?: continue
                val sortedLegIds = legIds.sortedBy { it.substringAfterLast("-s").toIntOrNull() ?: Int.MAX_VALUE }
                val legPairs = sortedLegIds.mapNotNull { id ->
                    val row = store.get(id) ?: return@mapNotNull null
                    val act = runCatching { json.decodeFromString(Activity.serializer(), row.json) }.getOrNull() ?: return@mapNotNull null
                    id to act
                }
                if (legPairs.isEmpty()) continue

                val anyLegUntagged = legPairs.any { (_, act) ->
                    "multisport-leg" !in act.tags || "parent:$parentId" !in act.tags
                }
                val parentSummaryPending = "multisport-summary-pending" in parentAct.tags
                val parentLacksSummary = parentAct.title.isNullOrBlank() &&
                    parentAct.aerobicTrainingEffect == null &&
                    parentAct.anaerobicTrainingEffect == null &&
                    parentAct.trainingLoad == null
                if (!anyLegUntagged && !parentSummaryPending && !parentLacksSummary) continue

                val actId = parentId.removePrefix("garmin-")
                val rawGarminObj = garminSummariesByActKey[parentId]
                    ?: if (parentLacksSummary || parentSummaryPending) fetchGarminSummary(actId) else null
                val garminObj = rawGarminObj?.let(::normalizeGarminSummaryObj)
                val sealedFit = if (anyLegUntagged) findSealedFitForParent(parentAct, legPairs) else null

                store.inTransaction {
                    when {
                        // Případ 1: `9c61ec9` sync path — rodič `garmin-X` držel 1. úsek a `-s1..-sK` další úseky bez tagů.
                        anyLegUntagged && parentLacksSummary -> {
                            if (sealedFit != null && sealedFit.sessions.size >= 2) {
                                val newParent = if (garminObj != null) {
                                    buildActivityFromGarminAndFit(
                                        id = parentId,
                                        provider = Provider.GARMIN_PERSONAL,
                                        extId = actId,
                                        obj = garminObj,
                                        parsed = null,
                                        nowMs = nowMs,
                                        accountId = accountId,
                                        zoneId = zoneId,
                                    )
                                } else {
                                    val synthLegs = sealedFit.sessions.mapIndexed { idx, session ->
                                        buildActivityFromGarminAndFit(
                                            id = "$parentId-s${idx + 1}",
                                            provider = Provider.GARMIN_PERSONAL,
                                            extId = "$actId-s${idx + 1}",
                                            obj = null,
                                            parsed = session,
                                            nowMs = nowMs,
                                            accountId = accountId,
                                            zoneId = zoneId,
                                        )
                                    }
                                    synthesizeOfflineMultisportParent(parentId, actId, synthLegs, zoneId)
                                }
                                store.upsert(newParent.toRow(json.encodeToString(Activity.serializer(), newParent)))
                                val allPoints = sealedFit.sessions.flatMap { it.points }.sortedBy { it.tMs }
                                if (allPoints.isNotEmpty()) {
                                    store.upsertTrack(TrackRow(parentId, json.encodeToString(trackSerializer, allPoints)))
                                }
                                val parentTitle = newParent.title?.takeIf { !("multisport-summary-pending" in newParent.tags) && it.isNotBlank() }
                                val newLegIds = multisportLegIds(parentId, actId, sealedFit.sessions.size)
                                for ((idx, session) in sealedFit.sessions.withIndex()) {
                                    val (legId, legExtId) = newLegIds[idx]
                                    val legTitle = if (parentTitle != null) {
                                        "$parentTitle · ${idx + 1}. ${session.sport.czechName()}"
                                    } else {
                                        "${idx + 1}. ${session.sport.czechName()}"
                                    }
                                    val legAct = buildActivityFromGarminAndFit(
                                        id = legId,
                                        provider = Provider.GARMIN_PERSONAL,
                                        extId = legExtId,
                                        obj = null,
                                        parsed = session,
                                        nowMs = nowMs,
                                        accountId = accountId,
                                        zoneId = zoneId,
                                    ).copy(
                                        title = legTitle,
                                        tags = setOf("multisport-leg", "parent:$parentId"),
                                    )
                                    store.upsert(legAct.toRow(json.encodeToString(Activity.serializer(), legAct)))
                                    if (session.points.isNotEmpty()) {
                                        store.upsertTrack(TrackRow(legId, json.encodeToString(trackSerializer, session.points)))
                                    }
                                }
                            } else {
                                // Bez sealed FIT přečíslujeme existující řádky: starý `garmin-X` -> `-s1`, `-s1` -> `-s2`, ...
                                val allLegsInOrder = listOf(parentAct) + legPairs.map { it.second }
                                val allTracksInOrder = listOf(store.track(parentId)) + legPairs.map { store.track(it.first) }
                                val newParent = if (garminObj != null) {
                                    buildActivityFromGarminAndFit(
                                        id = parentId,
                                        provider = Provider.GARMIN_PERSONAL,
                                        extId = actId,
                                        obj = garminObj,
                                        parsed = null,
                                        nowMs = nowMs,
                                        accountId = accountId,
                                        zoneId = zoneId,
                                    )
                                } else {
                                    synthesizeOfflineMultisportParent(parentId, actId, allLegsInOrder, zoneId)
                                }
                                store.upsert(newParent.toRow(json.encodeToString(Activity.serializer(), newParent)))
                                val combinedPoints = allTracksInOrder.filterNotNull()
                                    .flatMap { tr -> runCatching { json.decodeFromString(trackSerializer, tr.json) }.getOrDefault(emptyList()) }
                                    .sortedBy { it.tMs }
                                if (combinedPoints.isNotEmpty()) {
                                    store.upsertTrack(TrackRow(parentId, json.encodeToString(trackSerializer, combinedPoints)))
                                }
                                val parentTitle = newParent.title?.takeIf { !("multisport-summary-pending" in newParent.tags) && it.isNotBlank() }
                                val newLegIds = multisportLegIds(parentId, actId, allLegsInOrder.size)
                                for ((idx, legAct) in allLegsInOrder.withIndex()) {
                                    val (newLegId, newLegExtId) = newLegIds[idx]
                                    val legTitle = legAct.title?.takeIf { it.isNotBlank() }
                                        ?: if (parentTitle != null) "$parentTitle · ${idx + 1}. ${legAct.sport.czechName()}"
                                        else "${idx + 1}. ${legAct.sport.czechName()}"
                                    val updatedLeg = legAct.copy(
                                        id = newLegId,
                                        externalId = newLegExtId,
                                        title = legTitle,
                                        tags = legAct.tags + setOf("multisport-leg", "parent:$parentId"),
                                    )
                                    store.upsert(updatedLeg.toRow(json.encodeToString(Activity.serializer(), updatedLeg)))
                                    allTracksInOrder[idx]?.let { tr ->
                                        store.upsertTrack(TrackRow(newLegId, tr.json))
                                    }
                                }
                            }
                            repairedCount++
                        }

                        // Případ 2: Rodič už byl dočasně opraven offline a nyní dorazil souhrn z Garmin API.
                        parentSummaryPending && garminObj != null -> {
                            val newParent = buildActivityFromGarminAndFit(
                                id = parentId,
                                provider = Provider.GARMIN_PERSONAL,
                                extId = actId,
                                obj = garminObj,
                                parsed = null,
                                nowMs = nowMs,
                                accountId = accountId,
                                zoneId = zoneId,
                            )
                            store.upsert(newParent.toRow(json.encodeToString(Activity.serializer(), newParent)))
                            val parentTitle = newParent.title?.takeIf { it.isNotBlank() }
                            if (parentTitle != null) {
                                legPairs.forEachIndexed { idx, (legId, legAct) ->
                                    val updatedTitle = if (legAct.title.isNullOrBlank() || legAct.title!!.startsWith("${idx + 1}. ")) {
                                        "$parentTitle · ${idx + 1}. ${legAct.sport.czechName()}"
                                    } else legAct.title
                                    val updatedLeg = legAct.copy(
                                        title = updatedTitle,
                                        tags = legAct.tags + setOf("multisport-leg", "parent:$parentId"),
                                    )
                                    if (updatedLeg != legAct) {
                                        store.upsert(updatedLeg.toRow(json.encodeToString(Activity.serializer(), updatedLeg)))
                                    }
                                }
                            }
                            repairedCount++
                        }

                        // Případ 3: `9c61ec9` lazy path — rodič má Garmin souhrn, úseky `-s*` jen neměly tagy (případně chyběl `-s1`).
                        anyLegUntagged -> {
                            if (sealedFit != null && sealedFit.sessions.size >= 2 && sealedFit.sessions.size > legPairs.size) {
                                val allPoints = sealedFit.sessions.flatMap { it.points }.sortedBy { it.tMs }
                                if (allPoints.isNotEmpty()) {
                                    store.upsertTrack(TrackRow(parentId, json.encodeToString(trackSerializer, allPoints)))
                                }
                                val parentTitle = parentAct.title?.takeIf { it.isNotBlank() }
                                val newLegIds = multisportLegIds(parentId, actId, sealedFit.sessions.size)
                                for ((idx, session) in sealedFit.sessions.withIndex()) {
                                    val (legId, legExtId) = newLegIds[idx]
                                    val legTitle = if (parentTitle != null) {
                                        "$parentTitle · ${idx + 1}. ${session.sport.czechName()}"
                                    } else {
                                        "${idx + 1}. ${session.sport.czechName()}"
                                    }
                                    val legAct = buildActivityFromGarminAndFit(
                                        id = legId,
                                        provider = Provider.GARMIN_PERSONAL,
                                        extId = legExtId,
                                        obj = null,
                                        parsed = session,
                                        nowMs = nowMs,
                                        accountId = accountId,
                                        zoneId = zoneId,
                                    ).copy(
                                        title = legTitle,
                                        tags = setOf("multisport-leg", "parent:$parentId"),
                                    )
                                    store.upsert(legAct.toRow(json.encodeToString(Activity.serializer(), legAct)))
                                    if (session.points.isNotEmpty()) {
                                        store.upsertTrack(TrackRow(legId, json.encodeToString(trackSerializer, session.points)))
                                    }
                                }
                            } else {
                                for ((legId, legAct) in legPairs) {
                                    val tagged = legAct.copy(tags = legAct.tags + setOf("multisport-leg", "parent:$parentId"))
                                    store.upsert(tagged.toRow(json.encodeToString(Activity.serializer(), tagged)))
                                }
                            }
                            repairedCount++
                        }
                    }
                }
            }
            return repairedCount
        }

        internal fun deduplicateBodyBatteryRows(db: AppDatabase) {
            val rows = db.observations().ofTypesIncludingDeleted(
                listOf(MetricType.BODY_BATTERY.name),
                "1970-01-01",
                "2999-12-31",
            )
            if (rows.isEmpty()) return
            val excludedIds = db.exclusions().allIds().toHashSet()
            val nowMs = System.currentTimeMillis()
            val toDelete = ArrayList<String>()
            val toUpsert = ArrayList<ObservationRow>()
            val exclusionsToPut = ArrayList<ExcludedRow>()
            val exclusionsToRemove = ArrayList<String>()
            val grouped = rows.filter { !it.localDate.isNullOrBlank() && it.provider == Provider.GARMIN_PERSONAL.name }
                .groupBy { Triple(it.provider, it.accountId, it.localDate!!) }
            for ((key, group) in grouped) {
                val (_, accountId, localDate) = key
                val canonicalId = app.pulse4.domain.ImportKey.of(
                    Provider.GARMIN_PERSONAL,
                    accountId,
                    null,
                    listOf(MetricType.BODY_BATTERY.name, localDate),
                )
                val excludedInGroup = group.filter { it.id in excludedIds }
                val canonicalRow = group.firstOrNull { it.id == canonicalId }
                val isDayExcludedOrCanonicalDeleted = excludedInGroup.isNotEmpty() || canonicalRow?.deleted == true

                for (exRow in excludedInGroup) {
                    if (exRow.id != canonicalId) {
                        if (canonicalId !in excludedIds) {
                            exclusionsToPut += ExcludedRow(canonicalId, nowMs)
                            excludedIds += canonicalId
                        }
                        exclusionsToRemove += exRow.id
                    }
                }

                if (isDayExcludedOrCanonicalDeleted) {
                    // Nikdy nevzkřísit smazaný nebo uživatelem vyloučený den.
                    group.filter { !it.deleted }.forEach { toDelete += it.id }
                    continue
                }

                val activeRows = group.filter { !it.deleted }
                if (activeRows.isEmpty()) continue

                val newest = activeRows.maxWithOrNull(
                    compareBy<ObservationRow> { it.endUtcMs ?: it.startUtcMs }.thenBy { it.startUtcMs }
                ) ?: continue
                val nonCanonicalActiveIds = activeRows.map { it.id }.filter { it != canonicalId }
                if (nonCanonicalActiveIds.isNotEmpty() || canonicalRow == null || newest.id != canonicalId) {
                    toUpsert += newest.copy(id = canonicalId, deleted = false)
                    group.filter { it.id != canonicalId && !it.deleted }.forEach { toDelete += it.id }
                }
            }
            if (toUpsert.isNotEmpty() || toDelete.isNotEmpty() || exclusionsToPut.isNotEmpty() || exclusionsToRemove.isNotEmpty()) {
                db.runInTransaction {
                    toDelete.distinct().chunked(500).forEach { db.observations().markDeleted(it) }
                    if (toUpsert.isNotEmpty()) db.observations().upsert(toUpsert)
                    exclusionsToPut.forEach { db.exclusions().put(it) }
                    exclusionsToRemove.forEach { db.exclusions().remove(it) }
                }
            }
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

            val garminSummariesByActKey = LinkedHashMap<String, kotlinx.serialization.json.JsonObject>()
            for (elem in allActivityElements) {
                val obj = (elem as? kotlinx.serialization.json.JsonObject) ?: continue
                val actId = obj["activityId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: continue
                garminSummariesByActKey["garmin-$actId"] = obj
            }
            repairLegacyMultisportActivities(
                store = store,
                garminSummariesByActKey = garminSummariesByActKey,
                nowMs = nowMs,
                accountId = accountId,
                zoneId = zoneId,
            )

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
        if (settings.getRaw("migration.multisport_repair_v1") != "true") {
            runCatching {
                repairLegacyMultisportActivities(
                    store = roomActivityImportStore(),
                    findSealedFitForParent = { parentAct, legPairs -> findSealedFitInDir(parentAct, legPairs) },
                    nowMs = nowMs,
                    accountId = settings.accountId,
                    zoneId = settings.zoneId,
                )
                settings.put("migration.multisport_repair_v1", "true")
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

    internal fun deduplicateBodyBatteryRows(db: AppDatabase) = Companion.deduplicateBodyBatteryRows(db)

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

            runOnceWithFlag(sl.settings::getRaw, sl.settings::put, MULTISPORT_NET_REPAIR_FLAG) {
                repairLegacyMultisportActivities(
                    store = roomActivityImportStore(),
                    findSealedFitForParent = { parentAct, legPairs -> findSealedFitInDir(parentAct, legPairs) },
                    fetchGarminSummary = { actId ->
                        val summaryPath = "/activity-service/activity/$actId"
                        val body = runCatching {
                            if (authHeader != null) {
                                val req = okhttp3.Request.Builder()
                                    .url("${cfg.baseUrl.trimEnd('/')}$summaryPath")
                                    .header(cfg.authHeader, authHeader)
                                    .get()
                                    .build()
                                client.newCall(req).execute().use { resp ->
                                    if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
                                }
                            } else ""
                        }.getOrDefault("").ifBlank {
                            if (hasExt) runCatching { sl.garminExt.connectApiJson(summaryPath) }.getOrDefault("") else ""
                        }
                        body.takeIf { it.isNotBlank() }?.let { raw ->
                            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject }.getOrNull()
                        }
                    },
                    nowMs = nowMs,
                    accountId = sl.settings.accountId,
                    zoneId = sl.settings.zoneId,
                )
            }

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

    private fun findSealedFitInDir(parentAct: Activity, legPairs: List<Pair<String, Activity>>): FitResult.Ok? {
        val fitDir = File(context.noBackupFilesDir, "fit")
        if (!fitDir.isDirectory) return null
        val files = fitDir.listFiles { f -> f.isFile && f.name.endsWith(".fit.enc") }.orEmpty()
        val firstLeg = legPairs.firstOrNull()?.second
        for (f in files) {
            val ok = runCatching {
                val bytes = sl.vault.open(f.readBytes())
                FitImporter.parse(bytes) as? FitResult.Ok
            }.getOrNull() ?: continue
            if (ok.sessions.size < 2) continue
            val s0 = ok.sessions.first()
            if (kotlin.math.abs(s0.startMs - parentAct.startUtcMs) <= 2_000L &&
                (s0.sport == parentAct.sport || (firstLeg != null && ok.sessions.getOrNull(1)?.sport == firstLeg.sport))
            ) {
                return ok
            }
        }
        return null
    }

    private fun roomActivityImportStore(): ActivityImportStore = object : ActivityImportStore {
        override fun allIds(): List<String> = sl.db.activities().allIds()
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

internal interface PulseDataAccess {
    fun observations(): ObservationDao
    fun activities(): ActivityDao
    fun areaStates(): AreaStateDao
    fun grants(): GrantDao
    fun outbox(): OutboxDao
    fun inbox(): InboxDao
    fun familyLinks(): FamilyLinkDao
    fun pendingRequests(): PendingRequestDao
}

internal class RoomPulseDataAccess(private val db: AppDatabase) : PulseDataAccess {
    override fun observations() = db.observations()
    override fun activities() = db.activities()
    override fun areaStates() = db.areaStates()
    override fun grants() = db.grants()
    override fun outbox() = db.outbox()
    override fun inbox() = db.inbox()
    override fun familyLinks() = db.familyLinks()
    override fun pendingRequests() = db.pendingRequests()
}

internal interface ShareSettingsAccess {
    val zoneId: String
    val privacyZones: List<app.pulse4.sharing.PrivacyZone>
    val ownAgeProfile: String
    val acceptWeightFromParent: Boolean
    val childRecipientIdentity: String
    fun getRaw(key: String): String?
    fun put(key: String, value: String)
}

internal class RealShareSettingsAccess(private val s: Settings) : ShareSettingsAccess {
    override val zoneId: String get() = s.zoneId
    override val privacyZones: List<app.pulse4.sharing.PrivacyZone> get() = s.privacyZones
    override val ownAgeProfile: String get() = s.ownAgeProfile
    override val acceptWeightFromParent: Boolean get() = s.acceptWeightFromParent
    override val childRecipientIdentity: String get() = s.childRecipientIdentity
    override fun getRaw(key: String) = s.getRaw(key)
    override fun put(key: String, value: String) = s.put(key, value)
}

internal fun buildFamilyAndWeightHandlers(
    data: PulseDataAccess,
    settings: ShareSettingsAccess,
    onSnapshotRequest: ((childId: String, nowMs: Long) -> Unit)? = null,
): Map<String, KindHandler> = mapOf(
    WithingsService.FAMILY_WEIGHT_PUSH_KIND to KindHandler { env, payload, _, nowMs ->
        val isVerifiedParent = data.familyLinks().allNow().any { row ->
            row.status == FamilyLinkStatus.VERIFIED.name &&
                row.parentId == env.author &&
                row.childId == env.recipient
        }
        if (!isVerifiedParent) {
            return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
        }
        if (!settings.acceptWeightFromParent) {
            return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
        }
        val obs = runCatching {
            Wire.json.decodeFromString(app.pulse4.domain.MetricObservation.serializer(), payload)
        }.getOrNull() ?: return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
        if (obs.type !in WithingsService.CHILD_ACCEPTED_TYPES || !isValidChildBodyComposition(obs.type, obs.value)) {
            return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
        }
        val idPrefix = if (obs.type == MetricType.WEIGHT) "parent-weight" else "parent-${obs.type.name.lowercase()}"
        val savedObs = obs.copy(
            id = "$idPrefix:${env.author.take(16)}:${obs.startUtcMs}",
            provider = Provider.WITHINGS_API,
            accountId = "withings-family:${env.author.take(8)}",
            quality = obs.quality,
            deleted = false
        )
        data.observations().upsert(listOf(savedObs.toRow()))
        val areaKey = "withings.${obs.type.name}"
        val prevW = data.areaStates().get(areaKey)
        data.areaStates().put(AreaStateRow(areaKey, prevW?.cursorMs, nowMs, nowMs, null, null))
        HandlerResult.Applied
    },
    Kinds.FAMILY_LINK_REQUEST to KindHandler { env, payload, _, nowMs ->
        val link = runCatching {
            Wire.json.decodeFromString(FamilyLink.serializer(), payload)
        }.getOrNull() ?: return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
        if (link.childId != env.recipient || link.parentId != env.author) {
            return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
        }
        val existing = data.familyLinks().get(link.id)
        if (existing != null) {
            // SEC-2: cizí autor nesmí převzít existující vazbu podle UUID; ověřenou vazbu nelze shodit na PENDING.
            if (existing.parentId != env.author || existing.childId != env.recipient) {
                return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
            }
            if (link.version <= existing.version) {
                return@KindHandler HandlerResult.Stale
            }
            if (existing.status == FamilyLinkStatus.VERIFIED.name) {
                return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
            }
        }
        val sanitized = link.copy(status = FamilyLinkStatus.PENDING, verifiedAtMs = null)
        val sanitizedJson = Wire.json.encodeToString(FamilyLink.serializer(), sanitized)
        data.familyLinks().upsert(FamilyLinkRow(sanitized.id, sanitized.parentId, sanitized.childId, FamilyLinkStatus.PENDING.name, sanitized.version, sanitizedJson))
        data.pendingRequests().upsert(
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
        val existing = data.familyLinks().get(link.id)
            ?: return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
        if (existing.parentId != env.recipient || existing.childId != env.author) {
            return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
        }
        // SEC-2: dítě nesmí přepnout REVOKED/REJECTED/VERIFIED bez nového požadavku rodiče (stav musí být PENDING).
        if (existing.status != FamilyLinkStatus.PENDING.name) {
            return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
        }
        if (link.version <= existing.version) {
            return@KindHandler HandlerResult.Stale
        }
        val sanitizedJson = Wire.json.encodeToString(FamilyLink.serializer(), link)
        data.familyLinks().upsert(FamilyLinkRow(link.id, link.parentId, link.childId, link.status.name, link.version, sanitizedJson))
        HandlerResult.Applied
    },
    Kinds.SNAPSHOT_REQUEST to KindHandler { env, _, _, nowMs ->
        if (env.objectType != "own_weight") {
            return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
        }
        val isVerifiedChild = data.familyLinks().allNow().any { row ->
            row.status == FamilyLinkStatus.VERIFIED.name &&
                row.parentId == env.recipient &&
                row.childId == env.author
        } || settings.childRecipientIdentity == env.author
        if (!isVerifiedChild) {
            return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
        }
        for (sent in data.inbox().allSentNow()) {
            if (sent.recipient == env.author && sent.objectKey.startsWith("own_weight/") && sent.contentHash.isNotEmpty()) {
                data.inbox().putSent(sent.copy(contentHash = ""))
            }
        }
        onSnapshotRequest?.invoke(env.author, nowMs)
        HandlerResult.Applied
    },
)

internal fun parseGrantRefs(rawGrantId: String?): List<String> =
    rawGrantId?.split(',')?.filter { it.isNotBlank() }.orEmpty()

/** Tvorba projekcí, zařazení do outboxu a odvolání sdílení. Filtrace probíhá před šifrováním a odesláním. */
class ShareService internal constructor(
    private val data: PulseDataAccess,
    private val settings: ShareSettingsAccess,
    private val outbox: OutboxManager,
    private val sentLedger: app.pulse4.sync.SentLedger,
    private val recordSent: (recipient: String, objectKey: String, version: Long, hash: String) -> Unit,
    private val selfIdProvider: () -> String,
    private val runMigrations: () -> Unit = {},
    private val onScheduleShare: () -> Unit = {},
    private val drainPlanNotifications: () -> List<app.pulse4.training.PlanNotification> = { emptyList() },
) {
    constructor(sl: ServiceLocator) : this(
        data = RoomPulseDataAccess(sl.db),
        settings = RealShareSettingsAccess(sl.settings),
        outbox = sl.outbox,
        sentLedger = sl.sentLedger,
        recordSent = { r, k, v, h -> sl.sentLedger.record(r, k, v, h) },
        selfIdProvider = {
            if (!sl.isMigrationsCompleted()) {
                runBlocking { sl.awaitMigrations() }
            }
            sl.bridge.ensureStarted().deliveryHash
        },
        runMigrations = {
            if (!sl.isMigrationsCompleted()) {
                runBlocking { sl.awaitMigrations() }
            }
        },
        onScheduleShare = {
            if (sl.settings.networkMode != app.pulse4.android.data.NetworkMode.MANUAL) {
                app.pulse4.android.work.Scheduler.shareNow(sl.context)
            }
        },
        drainPlanNotifications = { sl.planHandlers.drainNotifications() },
    )

    fun scheduleShare() = onScheduleShare()

    private val json = Wire.json
    private val planLock = Any()

    fun grants(): List<ShareGrant> = data.grants().allNow().map { json.decodeFromString(ShareGrant.serializer(), it.json) }
    fun groups(): Map<String, Group> = data.grants().groupsNow().associate { it.id to json.decodeFromString(Group.serializer(), it.json) }
    fun familyLinks(): List<FamilyLink> = data.familyLinks().allNow().map { json.decodeFromString(FamilyLink.serializer(), it.json) }
    fun pendingRequests(): List<PendingRequestRow> = data.pendingRequests().pendingNow()

    private fun saveGrant(g: ShareGrant) {
        if (g.active) {
            saveGrantWithPolicy(g).getOrThrow()
        } else {
            saveGrantDirect(g)
        }
    }

    private fun saveGrantDirect(g: ShareGrant) =
        data.grants().upsert(GrantRow(g.id, !g.active, json.encodeToString(ShareGrant.serializer(), g)))

    fun saveGroup(g: Group) = data.grants().upsertGroup(GroupRow(g.id, g.version, json.encodeToString(Group.serializer(), g)))

    private fun scopeCovers(outer: app.pulse4.sharing.TimeScope, inner: app.pulse4.sharing.TimeScope): Boolean {
        val outerFrom = outer.from?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        val innerFrom = inner.from?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        if (innerFrom.isBefore(outerFrom)) return false
        if (outer.kind == app.pulse4.sharing.TimeScope.Kind.ONCE) {
            if (inner.kind != app.pulse4.sharing.TimeScope.Kind.ONCE) return false
            val outerTo = outer.to?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
            val innerTo = inner.to?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
            if (innerTo.isAfter(outerTo)) return false
        }
        return true
    }

    internal fun isGrantNarrowing(existingGrants: List<ShareGrant>, candidate: ShareGrant): Boolean {
        if (existingGrants.isEmpty() || !candidate.active) return false
        val activeExisting = existingGrants.filter { it.active && it.ownerId == candidate.ownerId }
        if (activeExisting.isEmpty()) return false
        if (candidate.recipients.isEmpty()) return true
        return candidate.recipients.all { recipient ->
            val grantsForRecipient = activeExisting.filter { recipient in it.recipients }
            if (grantsForRecipient.isEmpty()) return@all false
            val fieldsCovered = candidate.fields.all { field ->
                grantsForRecipient.any { g ->
                    field in g.fields &&
                        (g.sources == null || (candidate.sources != null && candidate.sources!!.all { it in g.sources!! })) &&
                        scopeCovers(g.scope, candidate.scope)
                }
            }
            val planRightsCovered = candidate.planRights.all { (planId, right) ->
                grantsForRecipient.any { g -> (g.planRights[planId]?.ordinal ?: -1) >= right.ordinal }
            }
            fieldsCovered && planRightsCovered
        }
    }

    fun saveGrantWithPolicy(grant: ShareGrant, guardianCoConsentAtMs: Long? = null): Result<ShareGrant> {
        if (!grant.active) {
            saveGrantDirect(grant)
            return Result.success(grant)
        }
        val self = selfIdProvider()
        val ageProfile = AgeProfile.valueOf(settings.ownAgeProfile)
        if (!FamilyLinkPolicy.canActivateShareGrant(self, ageProfile, grant, familyLinks(), guardianCoConsentAtMs)) {
            val existingSame = data.grants().get(grant.id)?.let {
                runCatching { json.decodeFromString(ShareGrant.serializer(), it.json) }.getOrNull()
            }
            if (existingSame != null && isGrantNarrowing(listOf(existingSame), grant)) {
                saveGrantDirect(grant)
                return Result.success(grant)
            }
            return Result.failure(
                IllegalStateException("Sdílení nelze aktivovat: pro nezletilý účet je nutná ověřená rodinná vazba a souhlas zákonného zástupce.")
            )
        }
        saveGrantDirect(grant)
        return Result.success(grant)
    }

    /**
     * N-2 & RE-7: Globální profil nejprve ověří a uloží nový grant, teprve poté odvolá staré přímé granty.
     * Při selhání politiky (např. nezletilý bez spolupodpisu při rozšiřování) nic neodvolá; zúžení projde.
     */
    fun saveGlobalDirectProfile(
        newGrant: ShareGrant?,
        existingDirectGrants: List<ShareGrant>,
        nowMs: Long = System.currentTimeMillis(),
        guardianCoConsentAtMs: Long? = null,
    ): Result<ShareGrant?> {
        if (newGrant == null) {
            existingDirectGrants.forEach { old -> revoke(old.id, nowMs) }
            return Result.success(null)
        }
        val self = selfIdProvider()
        val ageProfile = AgeProfile.valueOf(settings.ownAgeProfile)
        val policyOk = FamilyLinkPolicy.canActivateShareGrant(
            self, ageProfile, newGrant, familyLinks(), guardianCoConsentAtMs
        ) || isGrantNarrowing(existingDirectGrants, newGrant)
        if (!policyOk) {
            return Result.failure(
                IllegalStateException("Sdílení nelze aktivovat: pro nezletilý účet je nutná ověřená rodinná vazba a souhlas zákonného zástupce.")
            )
        }
        saveGrantDirect(newGrant)
        existingDirectGrants.forEach { old ->
            if (old.id != newGrant.id) {
                revoke(old.id, nowMs)
            }
        }
        planAndEnqueue(nowMs)
        return Result.success(newGrant)
    }

    /** Dítě po zapnutí příjmu váhy od rodiče požádá ověřené rodiče o znovuposlání 30denního okna (RE-5). */
    fun sendFamilyWeightResyncRequests(nowMs: Long = System.currentTimeMillis()): Int {
        val self = selfIdProvider()
        val verifiedParents = familyLinks()
            .filter { it.status == FamilyLinkStatus.VERIFIED && it.childId == self }
            .map { it.parentId }
            .distinct()
        if (verifiedParents.isEmpty()) return 0
        val builder = MessageBuilder(self)
        var queued = 0
        for (parentId in verifiedParents) {
            val msg = builder.build(
                recipient = parentId,
                kind = Kinds.SNAPSHOT_REQUEST,
                objectType = "own_weight",
                objectId = "family_weight",
                objectVersion = nowMs,
                payload = "{}",
                nowMs = nowMs,
            )
            val e = msg.envelope
            val item = OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs)
            if (outbox.enqueue(item) is EnqueueResult.Queued) queued++
        }
        if (queued > 0) scheduleShare()
        return queued
    }

    fun sendFamilyLinkRequest(
        childId: String,
        childAgeProfile: AgeProfile = AgeProfile.MINOR,
        nowMs: Long = System.currentTimeMillis(),
    ): FamilyLink {
        val self = selfIdProvider()
        val link = FamilyLinkPolicy.createRequest(
            id = java.util.UUID.randomUUID().toString(),
            parentId = self,
            childId = childId,
            childAgeProfile = childAgeProfile,
            nowMs = nowMs,
        )
        val linkJson = json.encodeToString(FamilyLink.serializer(), link)
        data.familyLinks().upsert(FamilyLinkRow(link.id, link.parentId, link.childId, link.status.name, link.version, linkJson))
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
        outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
        return link
    }

    fun respondFamilyLink(
        linkId: String,
        accept: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): FamilyLink? {
        val row = data.familyLinks().get(linkId) ?: return null
        val link = json.decodeFromString(FamilyLink.serializer(), row.json)
        val self = selfIdProvider()
        val updated = runCatching { FamilyLinkPolicy.respond(link, self, accept, nowMs) }.getOrNull() ?: return null
        val updatedJson = json.encodeToString(FamilyLink.serializer(), updated)
        data.familyLinks().upsert(FamilyLinkRow(updated.id, updated.parentId, updated.childId, updated.status.name, updated.version, updatedJson))
        data.pendingRequests().get(linkId)?.let { req ->
            data.pendingRequests().upsert(
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
        outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
        return updated
    }

    fun promoteCircleAdmin(groupId: String, targetMemberId: String): Result<Group> {
        val group = groups()[groupId] ?: return Result.failure(IllegalArgumentException("Kruh nenalezen."))
        val self = selfIdProvider()
        val updated = CircleAdminPolicy.promoteAdmin(group, self, targetMemberId)
            ?: return Result.failure(IllegalStateException("Nelze povýšit člena na správce."))
        saveGroup(updated)
        broadcastMembershipUpdate(updated, System.currentTimeMillis())
        return Result.success(updated)
    }

    fun demoteCircleAdmin(groupId: String, targetMemberId: String): Result<Group> {
        val group = groups()[groupId] ?: return Result.failure(IllegalArgumentException("Kruh nenalezen."))
        val self = selfIdProvider()
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
        val self = selfIdProvider()
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
        outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
        return Result.success(updated)
    }

    fun acceptAdminHandover(
        groupId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Result<Group> {
        val group = groups()[groupId] ?: return Result.failure(IllegalArgumentException("Kruh nenalezen."))
        val self = selfIdProvider()
        val initiator = group.pendingAdminHandover?.initiatedBy
        val updated = CircleAdminPolicy.acceptHandover(group, self, nowMs, removeInitiator = true)
            ?: return Result.failure(IllegalStateException("Žádost o předání správy není platná nebo vypršela."))
        saveGroup(updated)
        val payload = json.encodeToString(Group.serializer(), updated)
        data.pendingRequests().get("handover-${group.id}")?.let { req ->
            data.pendingRequests().upsert(req.copy(status = "ACCEPTED", json = payload))
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
            outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
        }
        return Result.success(updated)
    }

    fun leaveCircle(groupId: String): Result<Group> {
        val group = groups()[groupId] ?: return Result.failure(IllegalArgumentException("Kruh nenalezen."))
        val self = selfIdProvider()
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
        val self = selfIdProvider()
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
        val self = selfIdProvider()
        val updated = group.copy(
            members = group.members - memberId,
            admins = if (memberId in group.admins) group.admins - memberId else group.admins,
            version = group.version + 1,
        )
        val groupGrants = grants().filter { it.groupId == group.id }
        val groupGrantIds = groupGrants.mapTo(HashSet()) { it.id }
        synchronized(outbox) {
            data.outbox().all()
                .filter {
                    it.recipient == memberId &&
                        it.state == app.pulse4.sync.DeliveryState.QUEUED.name &&
                        parseGrantRefs(it.grantId).any { gid -> gid in groupGrantIds }
                }
                .forEach { row ->
                    data.outbox().update(row.copy(state = app.pulse4.sync.DeliveryState.CANCELLED.name))
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

    internal fun broadcastMembershipUpdate(
        group: Group,
        nowMs: Long,
        extraRecipients: Set<String> = emptySet(),
        selfOverride: String? = null,
    ) {
        val self = selfOverride ?: selfIdProvider()
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
            outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
        }
        scheduleShare()
    }

    internal fun emitFamilyLinkRequest(
        link: FamilyLink,
        nowMs: Long = System.currentTimeMillis(),
        selfOverride: String? = null,
    ) {
        val self = selfOverride ?: selfIdProvider()
        val linkJson = json.encodeToString(FamilyLink.serializer(), link)
        val msg = MessageBuilder(self).build(
            recipient = link.childId,
            kind = Kinds.FAMILY_LINK_REQUEST,
            objectType = "family_link",
            objectId = link.id,
            objectVersion = link.version,
            payload = linkJson,
            nowMs = nowMs,
        )
        val e = msg.envelope
        outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
        scheduleShare()
    }

    internal fun emitAdminHandoverRequest(
        group: Group,
        targetMemberId: String,
        nowMs: Long = System.currentTimeMillis(),
        selfOverride: String? = null,
    ) {
        val self = selfOverride ?: selfIdProvider()
        val payload = json.encodeToString(Group.serializer(), group)
        val msg = MessageBuilder(self).build(
            recipient = targetMemberId,
            kind = Kinds.ADMIN_HANDOVER_REQUEST,
            objectType = "group",
            objectId = group.id,
            objectVersion = group.version,
            payload = payload,
            nowMs = nowMs,
        )
        val e = msg.envelope
        outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
        scheduleShare()
    }

    /** Vytvoří projekce změněných dat pro každého skutečného příjemce a zařadí je do outboxu. Vrací počet zařazených. */
    fun planAndEnqueue(nowMs: Long): Int = synchronized(planLock) {
        runMigrations()
        val self = selfIdProvider()
        val zone = ZoneId.of(settings.zoneId)
        val today = LocalDate.now(zone)
        val activeGrants = grants().filter { it.active && it.ownerId == self }
        val earliestGrantFrom = activeGrants.mapNotNull { it.scope.from?.let { f -> runCatching { LocalDate.parse(f) }.getOrNull() } }.minOrNull()
        val from = earliestGrantFrom ?: today.minusDays(60)
        // Řadíme od nejnovějších dní, aby dnešek a včerejšek měly vždy přednost před starší historií.
        val dates = data.observations().datesWithData(from.toString(), today.toString())
            .map(LocalDate::parse)
            .sortedDescending()
        val allObservations = data.observations().forRange(from.toString(), today.toString()).map { it.toDomain() }
        // Nejdřív nahradit prošlé nepotvrzené souhrny čerstvým snapshotem.
        outbox.expireStale(nowMs).forEach { (recipient, key) -> recordSent(recipient, key, (sentLedger.last(recipient, key)?.version ?: 0), "expired") }

        val recentDateCutoff = today.minusDays(1).toString()
        val pendingByRecipient = HashMap<String, Int>()
        fun pendingFor(recipient: String): Int = pendingByRecipient.getOrPut(recipient) { outbox.pendingCountFor(recipient) }

        var queued = 0
        val plan = ShareBuilder(self, MessageBuilder(self)).plan(grants(), groups(), dates, allObservations, sentLedger, nowMs)
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
            val refs = p.grantIds.ifEmpty { listOf(p.grantId) }
            val item = OutboxItem(
                e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion,
                p.grantId, Wire.encode(p.message), nowMs, grantRefs = refs,
            )
            when (outbox.enqueue(item)) {
                is EnqueueResult.Queued -> {
                    recordSent(e.recipient, p.objectKey, e.objectVersion, p.contentHash)
                    refs.forEach { gid -> recordDeliveredKey(gid, e.recipient, p.objectKey) }
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
        val zone = ZoneId.of(settings.zoneId)
        val zones = settings.privacyZones
        val recentActCutoffMs = LocalDate.now(zone).minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        var queued = 0
        val acts = data.activities().since(from.atStartOfDay(zone).toInstant().toEpochMilli())
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
            if (ImportService.isMultisportLegId(row.id)) continue
            val act = json.decodeFromString(Activity.serializer(), row.json)
            if (isMultisportChildActivity(act)) continue
            val track = data.activities().track(row.id)?.let {
                json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(TrackPoint.serializer()), it.json)
            }.orEmpty()
            val actDate = app.pulse4.domain.TimeRules.localDate(act.startUtcMs, act.zoneId)
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
                val curPending = pendingByRecipient.getOrPut(r) { outbox.pendingCountFor(r) }
                if (isHistorical && curPending >= MAX_PENDING_PER_RECIPIENT) {
                    continue
                }
                val key = "activity/${act.id}"
                val last = sentLedger.last(r, key)
                if (last?.contentHash == hash) {
                    PLog.i("Pulse4Sync") { "act=${act.id} skipped, same hash as last sent" }
                    continue
                }
                val contributingIds = matching.map { it.id }
                val msg = MessageBuilder(self).build(r, Kinds.PROJECTION_UPSERT, "activity", act.id, (last?.version ?: 0) + 1, payload, nowMs, grantRef = mergedGrant.id)
                val e = msg.envelope
                val item = OutboxItem(
                    e.eventId, r, e.kind, e.objectType, e.objectId, e.objectVersion,
                    mergedGrant.id, Wire.encode(msg), nowMs, grantRefs = contributingIds,
                )
                val res = outbox.enqueue(item)
                PLog.i("Pulse4Sync") { "act=${act.id} enqueue result: $res" }
                when (res) {
                    is EnqueueResult.Queued -> {
                        recordSent(r, key, e.objectVersion, hash)
                        contributingIds.forEach { gid -> recordDeliveredKey(gid, r, key) }
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
        val notes = drainPlanNotifications()
        if (notes.isEmpty()) return 0
        val self = selfIdProvider()
        val b = MessageBuilder(self)
        var n = 0
        fun put(msg: app.pulse4.sync.WireMessage) {
            val e = msg.envelope
            if (outbox.enqueue(OutboxItem(e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs)) is EnqueueResult.Queued) n++
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
        val current = settings.getRaw(k)?.let {
            runCatching { json.decodeFromString(deliveredKeysSerializer, it) }.getOrNull()
        }.orEmpty().toMutableMap()
        val updatedSet = (current[recipient].orEmpty() + objectKey)
        if (updatedSet != current[recipient]) {
            current[recipient] = updatedSet
            settings.put(k, json.encodeToString(deliveredKeysSerializer, current))
        }
    }

    private fun deliveredKeysForGrant(grantId: String): Map<String, Set<String>> {
        val k = "delivered_keys.$grantId"
        return settings.getRaw(k)?.let {
            runCatching { json.decodeFromString(deliveredKeysSerializer, it) }.getOrNull()
        }.orEmpty()
    }

    /**
     * Odvolání: zruší čekající položky, odešle požadavek na odstranění už doručených kopií a vrátí plán s popisem omezení,
     * který UI musí uživateli ukázat přesně (doručená data nelze vzít zpět).
     */
    fun revoke(grantId: String, nowMs: Long): RevocationPlan? {
        val row = data.grants().get(grantId) ?: return null
        val g = json.decodeFromString(ShareGrant.serializer(), row.json)
        val self = selfIdProvider()
        val allOutbox = data.outbox().all()
        val outboxSentRows = allOutbox.filter {
            grantId in parseGrantRefs(it.grantId) && it.state !in setOf("CANCELLED", "SUPERSEDED")
        }
        val durableDelivered = deliveredKeysForGrant(grantId)
        val allRecipients = (g.recipients + durableDelivered.keys + outboxSentRows.map { it.recipient }).distinct()
        val delivered = allRecipients.associateWith { r ->
            val fromOutbox = outboxSentRows.filter { it.recipient == r }.map { "${it.objectType}/${it.objectId}" }
            val fromDurable = durableDelivered[r].orEmpty()
            (fromOutbox + fromDurable).distinct()
        }
        val inNode = allOutbox.any { grantId in parseGrantRefs(it.grantId) && it.state == "STORED_IN_PROPAGATION" }
        val plan = Revocation.plan(g, nowMs, delivered, inNode)
        saveGrantDirect(plan.revokedGrant)
        outbox.cancelForGrant(plan.cancelQueuedForGrantId)
        settings.put("delivered_keys.$grantId", "{}")
        val allGroups = groups()
        val otherActiveGrants = grants().filter { it.id != grantId && it.active && it.ownerId == self }
        val effectiveRecipientsByGrant = otherActiveGrants.associateWith { other ->
            app.pulse4.sharing.MembershipPolicy.effectiveRecipients(other, other.groupId?.let(allGroups::get))
        }
        val zones = settings.privacyZones
        val dayObsCache = HashMap<LocalDate, List<app.pulse4.domain.MetricObservation>>()
        val actCache = HashMap<String, Pair<Activity, List<TrackPoint>>?>()
        val coveredCache = HashMap<Pair<String, String>, Boolean>()
        fun isCoveredByOtherGrant(recipient: String, objectKey: String): Boolean =
            coveredCache.getOrPut(recipient to objectKey) {
                otherActiveGrants.any { other ->
                    if (recipient !in effectiveRecipientsByGrant.getValue(other)) return@any false
                    when {
                        objectKey.startsWith("daily/") -> {
                            val date = runCatching { LocalDate.parse(objectKey.removePrefix("daily/")) }.getOrNull()
                                ?: return@any false
                            val dayObs = dayObsCache.getOrPut(date) {
                                data.observations().forDate(date.toString()).map { it.toDomain() }
                            }
                            app.pulse4.sharing.Projector.project(other, date, dayObs, nowMs) != null
                        }
                        objectKey.startsWith("activity/") -> {
                            val actId = objectKey.removePrefix("activity/")
                            val actAndTrack = actCache.getOrPut(actId) {
                                val actRow = data.activities().get(actId) ?: return@getOrPut null
                                val act = runCatching { json.decodeFromString(Activity.serializer(), actRow.json) }.getOrNull()
                                    ?: return@getOrPut null
                                val track = data.activities().track(actId)?.let {
                                    runCatching {
                                        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(TrackPoint.serializer()), it.json)
                                    }.getOrNull()
                                }.orEmpty()
                                act to track
                            } ?: return@any false
                            ActivityProjector.project(other, actAndTrack.first, actAndTrack.second, emptyList<LapIn>(), zones) != null
                        }
                        else -> false
                    }
                }
            }
        // R3-3: sent_projection řádek nemažeme ani u nekrytého klíče; nastavíme version = removeVersion a contentHash = "",
        // aby následný re-grant/upsert dostal removeVersion + 1 (> vN příjemce) a čistě nahradil případný REMOVE ve frontě.
        val sentVersions = HashMap<Pair<String, String>, Long>()
        val removeVersions = HashMap<Pair<String, String>, Long>()
        for ((recipient, keys) in delivered) {
            for (key in keys) {
                val existingSent = data.inbox().sent(recipient, key)
                if (existingSent != null) sentVersions[recipient to key] = existingSent.version
                val covered = isCoveredByOtherGrant(recipient, key)
                val removeVersion = maxOf(nowMs, (existingSent?.version ?: 0L) + 1L)
                if (!covered) removeVersions[recipient to key] = removeVersion
                val updatedSent = updateSentRowOnRevoke(
                    existing = existingSent,
                    recipient = recipient,
                    objectKey = key,
                    isCoveredByOtherGrant = covered,
                    removeVersion = removeVersion,
                )
                if (updatedSent != null) {
                    data.inbox().putSent(updatedSent)
                }
            }
        }
        for (rq in plan.removalRequests) {
            if (isCoveredByOtherGrant(rq.recipient, rq.objectKey)) continue
            val (type, id) = rq.objectKey.split('/', limit = 2)
            val removeVersion = removeVersions[rq.recipient to rq.objectKey]
                ?: maxOf(nowMs, (sentVersions[rq.recipient to rq.objectKey] ?: 0L) + 1L)
            val msg = MessageBuilder(self).build(rq.recipient, Kinds.PROJECTION_REMOVE, type, id, removeVersion, "{}", nowMs, grantRef = grantId)
            val e = msg.envelope
            outbox.enqueue(OutboxItem(e.eventId, rq.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs))
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

internal fun isMultisportChildActivity(act: Activity): Boolean =
    ImportService.isMultisportLegId(act.id) ||
        "multisport-leg" in act.tags ||
        act.tags.any { it.startsWith("parent:") }

internal fun mergeActivityGrantsForRecipient(matching: List<ShareGrant>): ShareGrant {
    val sorted = matching.sortedBy { it.id }
    if (sorted.size == 1) return sorted.first()
    val mergedFields = sorted.flatMap { it.fields }.toSet()
    val mergedSources = if (sorted.any { it.sources == null }) null else sorted.flatMap { it.sources.orEmpty() }.toSet()
    return sorted.first().copy(fields = mergedFields, sources = mergedSources)
}

internal fun updateSentRowOnRevoke(
    existing: SentRow?,
    recipient: String,
    objectKey: String,
    isCoveredByOtherGrant: Boolean,
    removeVersion: Long,
): SentRow? = if (isCoveredByOtherGrant) {
    existing?.copy(contentHash = "")
} else {
    SentRow(recipient = recipient, objectKey = objectKey, version = removeVersion, contentHash = "")
}

/**
 * Jednorázové datové migrace při startu služeb:
 * - RE-1: přepis uložených `pulse.delivery` hashů na `lxmf.delivery` z uložených veřejných klíčů
 * - RE-5: zachování `acceptWeightFromParent = true` pro existující ověřené dětské účty
 * - NEW-12: vyčištění dříve uložených vymyšlených hodnot (`staminaPct`, `performanceCondition`, prázdný výkon apod.)
 */
object ServiceMigrations {
    private const val KEY_IDENTITY_V1 = "migration.lxmf_identity_v1"
    private const val KEY_ACCEPT_WEIGHT_V1 = "migration.accept_weight_default_v1"
    private const val KEY_R3_5_REEMIT_V1 = "migration.r3_5_reemit_v1"
    private const val KEY_FABRICATED_CLEANUP_V1 = "migration.fabricated_activity_cleanup_v1"
    private const val KEY_FABRICATED_CLEANUP_V2 = "migration.fabricated_activity_cleanup_v2"
    private const val KEY_FABRICATED_CLEANUP_FAILURES = "migration.fabricated_activity_cleanup_failures"
    private const val MAX_CLEANUP_FAILURES = 3
    private const val CLEANUP_WORK_NAME = "fabricated-activity-cleanup"
    private val FOOT_SPORTS = setOf(Sport.RUNNING, Sport.WALKING, Sport.HIKING)

    private val identityMigrationLock = Any()
    private val fabricatedCleanupLock = Any()

    internal fun hasSelfIdentity(secrets: SecretStore): Boolean =
        secrets.has(SecretStore.RETICULUM_IDENTITY)

    internal fun needsDeferredMigration(sl: ServiceLocator): Boolean =
        sl.settings.getRaw(KEY_IDENTITY_V1) == null ||
            sl.settings.getRaw(KEY_ACCEPT_WEIGHT_V1) == null ||
            sl.settings.getRaw(KEY_R3_5_REEMIT_V1) == null

    internal fun deriveSelfIdentityFromSecrets(secrets: SecretStore): Pair<String, String>? {
        val privateKeyB64 = secrets.getString(SecretStore.RETICULUM_IDENTITY) ?: return null
        val privBytes = runCatching { java.util.Base64.getDecoder().decode(privateKeyB64) }.getOrNull() ?: return null
        if (privBytes.size < 32) return null
        val edPriv = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(privBytes, 0)
        val edPub = edPriv.generatePublicKey()
        val xSeed = MessageDigest.getInstance("SHA-256").digest(privBytes)
        val xPriv = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(xSeed, 0)
        val xPub = xPriv.generatePublicKey()
        val pubKey64 = xPub.encoded + edPub.encoded
        val publicKeyB64 = java.util.Base64.getEncoder().encodeToString(pubKey64)
        val (lxmfHash, _) = deriveDeliveryHashes(publicKeyB64)
        return publicKeyB64 to lxmfHash
    }

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

    private fun rebuildQueuedPayload(
        db: AppDatabase,
        env: app.pulse4.sync.Envelope,
        fallbackPayload: String,
        mapJsonHashes: (String) -> String,
    ): String = when (env.kind) {
        Kinds.MEMBERSHIP_UPDATE, Kinds.ADMIN_HANDOVER_REQUEST, Kinds.ADMIN_HANDOVER_ACCEPT -> {
            val groupRow = db.grants().group(env.objectId)
            if (groupRow != null && groupRow.version == env.objectVersion) groupRow.json else mapJsonHashes(fallbackPayload)
        }
        Kinds.FAMILY_LINK_REQUEST, Kinds.FAMILY_LINK_RESPONSE -> {
            val linkRow = db.familyLinks().get(env.objectId)
            if (linkRow != null && linkRow.version == env.objectVersion) linkRow.json else mapJsonHashes(fallbackPayload)
        }
        Kinds.PLAN_REVISION -> {
            db.plans().revisions(env.objectId).firstOrNull { it.revision == env.objectVersion }?.json
                ?: mapJsonHashes(fallbackPayload)
        }
        Kinds.PLAN_PROPOSAL -> {
            db.plans().proposal(env.objectId)?.json ?: mapJsonHashes(fallbackPayload)
        }
        else -> mapJsonHashes(fallbackPayload)
    }

    fun migrateLegacyPulseIdentities(
        db: AppDatabase,
        settings: Settings,
        selfPublicKeyB64: String,
        json: kotlinx.serialization.json.Json = Wire.json,
    ): Int = synchronized(identityMigrationLock) {
        val hashMap = LinkedHashMap<String, String>()
        val selfHashes: Pair<String, String>? = if (selfPublicKeyB64.isNotBlank()) {
            deriveDeliveryHashes(selfPublicKeyB64).also { (lxmf, legacy) ->
                if (legacy != lxmf) hashMap[legacy] = lxmf
            }
        } else null
        val contacts = db.contacts().allNow()
        for (c in contacts) {
            if (c.publicKeyB64.isBlank()) continue
            val (lxmf, legacy) = deriveDeliveryHashes(c.publicKeyB64)
            if (legacy != lxmf && (c.identity == legacy || c.identity == lxmf)) {
                hashMap[legacy] = lxmf
            }
        }
        if (hashMap.isEmpty()) {
            if (selfPublicKeyB64.isNotBlank()) {
                settings.put(KEY_IDENTITY_V1, "1")
            }
            return@synchronized 0
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
            val contactById = HashMap<String, ContactRow>(contacts.size)
            for (c in contacts) contactById[c.identity] = c
            for (c in contacts) {
                val newId = mapId(c.identity)
                if (newId != c.identity) {
                    val existing = contactById[newId]
                    if (existing == null || c.verifiedAtMs > existing.verifiedAtMs) {
                        val updated = c.copy(identity = newId)
                        db.contacts().upsert(updated)
                        contactById[newId] = updated
                    }
                    db.contacts().delete(c.identity)
                    contactById.remove(c.identity)
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
                val newOwnerId = mapId(g.ownerId)
                val newAdminId = mapId(g.adminId)
                val newMembers = g.members.mapTo(LinkedHashSet()) { mapId(it) }
                val newAdmins = g.admins.mapTo(LinkedHashSet()) { mapId(it) }
                val newHandover = g.pendingAdminHandover?.let { h ->
                    h.copy(
                        targetMemberId = mapId(h.targetMemberId),
                        initiatedBy = mapId(h.initiatedBy),
                    )
                }
                if (
                    newOwnerId != g.ownerId ||
                    newAdminId != g.adminId ||
                    newMembers != g.members ||
                    newAdmins != g.admins ||
                    newHandover != g.pendingAdminHandover
                ) {
                    val updated = g.copy(
                        ownerId = newOwnerId,
                        adminId = newAdminId,
                        members = newMembers,
                        admins = newAdmins,
                        pendingAdminHandover = newHandover,
                    )
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
            for (row in db.plans().allRevisions()) {
                val newJson = mapJsonHashes(row.json)
                if (newJson != row.json) {
                    db.plans().replaceRevision(row.copy(json = newJson))
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
            // R3-5: Čekající (QUEUED) položky s legacy ID nerušíme, ale přepíšeme z aktuálního stavu DB s platným contentHash.
            for (row in db.outbox().all()) {
                if (
                    row.state == app.pulse4.sync.DeliveryState.CANCELLED.name ||
                    row.state == app.pulse4.sync.DeliveryState.SUPERSEDED.name ||
                    row.state == app.pulse4.sync.DeliveryState.ACKED.name
                ) {
                    continue
                }
                val newRec = mapId(row.recipient)
                val decoded = runCatching { Wire.decode(row.wire) }.getOrNull()
                val newWire = if (decoded != null) {
                    val e = decoded.envelope
                    val mappedAuthor = mapId(e.author)
                    val mappedRecipient = mapId(e.recipient)
                    val newPayload = if (row.state == app.pulse4.sync.DeliveryState.QUEUED.name) {
                        rebuildQueuedPayload(db, e, decoded.payload, ::mapJsonHashes)
                    } else {
                        decoded.payload
                    }
                    val newHash = if (newPayload != decoded.payload) Wire.hashOf(newPayload) else e.contentHash
                    if (
                        mappedAuthor != e.author ||
                        mappedRecipient != e.recipient ||
                        newPayload != decoded.payload ||
                        newHash != e.contentHash
                    ) {
                        Wire.encode(
                            decoded.copy(
                                envelope = e.copy(
                                    author = mappedAuthor,
                                    recipient = mappedRecipient,
                                    contentHash = newHash,
                                ),
                                payload = newPayload,
                            )
                        )
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
                    val existing = db.inbox().sent(newRec, row.objectKey)
                    if (existing == null || row.version > existing.version) {
                        db.inbox().putSent(row.copy(recipient = newRec))
                    }
                    db.inbox().deleteSent(row.recipient, row.objectKey)
                    migrated++
                }
            }
            for (row in db.inbox().allReceivedNow()) {
                val newAuthor = mapId(row.author)
                val newPayload = mapJsonHashes(row.payload)
                if (newAuthor != row.author) {
                    val existing = db.inbox().received(newAuthor, row.objectType, row.objectId)
                    if (existing == null || row.version > existing.version) {
                        db.inbox().putObject(row.copy(author = newAuthor, payload = newPayload))
                    } else {
                        val existingPayload = mapJsonHashes(existing.payload)
                        if (existingPayload != existing.payload) {
                            db.inbox().putObject(existing.copy(payload = existingPayload))
                        }
                    }
                    db.inbox().removeObject(row.author, row.objectType, row.objectId)
                    migrated++
                } else if (newPayload != row.payload) {
                    db.inbox().putObject(row.copy(payload = newPayload))
                    migrated++
                }
            }
            for ((oldH, newH) in hashMap) {
                db.inbox().updateReceiptAuthor(oldH, newH)
            }
            val childRec = db.settings().get("childRecipientIdentity")
            if (!childRec.isNullOrBlank()) {
                val mapped = mapId(childRec)
                if (mapped != childRec) {
                    db.settings().put(SettingRow("childRecipientIdentity", mapped))
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
            if (selfHashes != null && db.settings().get(Settings.KEY_OWN_PLAN_ID).isNullOrBlank()) {
                val (selfLxmf, selfLegacy) = selfHashes
                val legacyPlanId = "plan-${selfLegacy.take(8)}"
                val resolvedPlanId = if (db.plans().head(legacyPlanId) != null) {
                    legacyPlanId
                } else {
                    "plan-${selfLxmf.take(8)}"
                }
                db.settings().put(SettingRow(Settings.KEY_OWN_PLAN_ID, resolvedPlanId))
            }
        }
        settings.reloadFromDb()
        if (selfPublicKeyB64.isNotBlank()) {
            settings.put(KEY_IDENTITY_V1, "1")
        }
        return@synchronized migrated
    }

    /**
     * R3-5: Po migraci znovu zařadí čekající nebo dříve zrušené jednorázové řídicí zprávy
     * (`FAMILY_LINK_REQUEST`, `ADMIN_HANDOVER_REQUEST`, `MEMBERSHIP_UPDATE`) s novou adresou.
     */
    internal fun reemitPendingControlMessages(
        db: AppDatabase,
        selfId: String,
        nowMs: Long = System.currentTimeMillis(),
        emitFamilyLinkRequest: (FamilyLink, Long) -> Unit,
        emitAdminHandoverRequest: (Group, String, Long) -> Unit,
        broadcastMembershipUpdate: (Group, Set<String>, Long) -> Unit,
        json: kotlinx.serialization.json.Json = Wire.json,
    ): Int {
        if (selfId.isBlank()) return 0
        val activeStates = setOf(
            app.pulse4.sync.DeliveryState.QUEUED.name,
            app.pulse4.sync.DeliveryState.HANDED_TO_TRANSPORT.name,
            app.pulse4.sync.DeliveryState.STORED_IN_PROPAGATION.name,
            app.pulse4.sync.DeliveryState.ACKED.name,
        )
        val outboxRows = db.outbox().all()
        var reemitted = 0

        for (row in db.familyLinks().allNow()) {
            if (row.status != FamilyLinkStatus.PENDING.name || row.parentId != selfId) continue
            val link = runCatching { json.decodeFromString(FamilyLink.serializer(), row.json) }.getOrNull() ?: continue
            val hasActive = outboxRows.any {
                it.kind == Kinds.FAMILY_LINK_REQUEST &&
                    it.objectId == link.id &&
                    it.recipient == link.childId &&
                    it.state in activeStates
            }
            if (!hasActive) {
                emitFamilyLinkRequest(link, nowMs)
                reemitted++
            }
        }

        val groupsById = db.grants().groupsNow().mapNotNull { row ->
            runCatching { json.decodeFromString(Group.serializer(), row.json) }.getOrNull()
        }.associateBy { it.id }

        val groupsWithCancelledMembership = outboxRows
            .filter { it.kind == Kinds.MEMBERSHIP_UPDATE && it.state == app.pulse4.sync.DeliveryState.CANCELLED.name }
            .mapTo(LinkedHashSet()) { it.objectId }
        val emittedMembershipGroupIds = HashSet<String>()

        for ((groupId, group) in groupsById) {
            if (group.dissolved) continue
            val handover = group.pendingAdminHandover
            if (handover != null && handover.initiatedBy == selfId && nowMs <= handover.expiresAtMs && handover.targetMemberId in group.members) {
                val hasActiveHandover = outboxRows.any {
                    it.kind == Kinds.ADMIN_HANDOVER_REQUEST &&
                        it.objectId == group.id &&
                        it.objectVersion == group.version &&
                        it.recipient == handover.targetMemberId &&
                        it.state in activeStates
                }
                if (!hasActiveHandover) {
                    val missingMembershipRecipients = (group.members - selfId).filterTo(LinkedHashSet()) { r ->
                        outboxRows.none {
                            it.kind == Kinds.MEMBERSHIP_UPDATE &&
                                it.objectId == group.id &&
                                it.objectVersion == group.version &&
                                it.recipient == r &&
                                it.state in activeStates
                        }
                    }
                    if (missingMembershipRecipients.isNotEmpty()) {
                        broadcastMembershipUpdate(group, missingMembershipRecipients, nowMs)
                        emittedMembershipGroupIds.add(group.id)
                        reemitted++
                    }
                    emitAdminHandoverRequest(group, handover.targetMemberId, nowMs + 1L)
                    reemitted++
                }
            }
            if (groupId in groupsWithCancelledMembership && selfId in group.effectiveAdmins) {
                if (groupId !in emittedMembershipGroupIds) {
                    val missingRecipients = (group.members - selfId).filterTo(LinkedHashSet()) { r ->
                        outboxRows.none {
                            it.kind == Kinds.MEMBERSHIP_UPDATE &&
                                it.objectId == group.id &&
                                it.objectVersion == group.version &&
                                it.recipient == r &&
                                it.state in activeStates
                        }
                    }
                    if (missingRecipients.isNotEmpty()) {
                        broadcastMembershipUpdate(group, missingRecipients, nowMs)
                        emittedMembershipGroupIds.add(groupId)
                        reemitted++
                    }
                }
                outboxRows
                    .filter { it.kind == Kinds.MEMBERSHIP_UPDATE && it.objectId == groupId && it.state == app.pulse4.sync.DeliveryState.CANCELLED.name }
                    .forEach { oldRow ->
                        db.outbox().update(oldRow.copy(state = app.pulse4.sync.DeliveryState.SUPERSEDED.name))
                    }
            }
        }
        return reemitted
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

    internal fun isFabricated1KmLaps(act: Activity): Boolean {
        val laps = act.laps
        if (laps.isEmpty()) return false
        val dist = act.distanceM ?: return false
        val moving = act.movingTimeS ?: return false
        if (dist < 1000.0 || moving <= 0.0) return false
        val fullKm = (dist / 1000.0).toInt().coerceIn(1, 50)
        if (laps.size != fullKm) return false
        val basePace = moving / (dist / 1000.0)
        return laps.all { lap ->
            val k = lap.index
            val expectedDur = basePace * (1.0 + kotlin.math.sin(k * 1.3) * 0.035)
            lap.distanceM == 1000.0 &&
                lap.descentM == null &&
                lap.maxSpeedMps == null &&
                lap.caloriesKcal == null &&
                kotlin.math.abs(lap.durationS - expectedDur) < 0.05
        }
    }

    fun cleanLegacyFabricatedActivity(
        act: Activity,
        track: List<TrackPoint>,
        reparsedFit: ParsedFit? = null,
    ): Pair<Activity, List<TrackPoint>> {
        var cleanedTrack = if (reparsedFit != null && reparsedFit.points.isNotEmpty()) {
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
                maxCadence = reparsedFit.maxCadence ?: act.maxCadence,
                powerZoneTimesS = if (act.avgPowerW == null) emptyList() else act.powerZoneTimesS,
            )
        } else {
            val strippedLaps = if (isFabricated1KmLaps(act)) emptyList() else act.laps
            val normalizedLaps = if (
                strippedLaps.isNotEmpty() &&
                act.sport in FOOT_SPORTS &&
                act.avgCadence != null &&
                act.avgCadence!! >= 110.0
            ) {
                val lapCads = strippedLaps.mapNotNull { it.avgCadence?.takeIf { c -> c > 0.0 } }
                if (lapCads.isNotEmpty()) {
                    val avgLapCad = lapCads.average()
                    if (avgLapCad in 40.0..120.0 && kotlin.math.abs(act.avgCadence!! - avgLapCad * 2.0) <= act.avgCadence!! * 0.25) {
                        strippedLaps.map { l -> l.avgCadence?.let { c -> l.copy(avgCadence = c * 2.0) } ?: l }
                    } else strippedLaps
                } else strippedLaps
            } else strippedLaps
            if (act.sport in FOOT_SPORTS && act.avgCadence != null && act.avgCadence!! >= 110.0) {
                val trackCads = cleanedTrack.mapNotNull { it.cadence?.takeIf { c -> c > 0 } }
                if (trackCads.isNotEmpty()) {
                    val avgTrackCad = trackCads.average()
                    if (avgTrackCad in 40.0..120.0 && kotlin.math.abs(act.avgCadence!! - avgTrackCad * 2.0) <= act.avgCadence!! * 0.25) {
                        cleanedTrack = cleanedTrack.map { pt -> pt.cadence?.let { c -> pt.copy(cadence = c * 2) } ?: pt }
                    }
                }
            }
            act.copy(
                laps = normalizedLaps,
                powerZoneTimesS = if (act.avgPowerW == null) emptyList() else act.powerZoneTimesS,
            )
        }
        return cleanedAct to cleanedTrack
    }

    fun migrateLegacyFabricatedActivities(
        sl: ServiceLocator,
        batchSize: Int = 25,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = migrateLegacyFabricatedActivities(
        db = sl.db,
        settings = sl.settings,
        fitDir = File(sl.context.noBackupFilesDir, "fit"),
        decryptFit = { sl.vault.open(it) },
        batchSize = batchSize,
        onProgress = onProgress,
    )

    private data class UnindexedFitEntry(
        val file: File,
        val sessions: List<FitSessionIndex>,
    )

    internal fun migrateLegacyFabricatedActivities(
        db: AppDatabase,
        settings: Settings,
        fitDir: File,
        decryptFit: (ByteArray) -> ByteArray,
        batchSize: Int = 25,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = synchronized(fabricatedCleanupLock) {
        if (settings.getRaw(KEY_FABRICATED_CLEANUP_V2) != null) return@synchronized 0
        val allIds = db.activities().allIds()
        if (allIds.isEmpty()) {
            settings.put(KEY_FABRICATED_CLEANUP_V1, "1")
            settings.put(KEY_FABRICATED_CLEANUP_V2, "1")
            return@synchronized 0
        }
        val trackSerializer = kotlinx.serialization.builtins.ListSerializer(TrackPoint.serializer())

        val referencedFitHashes = allIds.mapNotNullTo(HashSet()) { id ->
            id.removePrefix("fit-").substringBefore("-s").takeIf { id.startsWith("fit-") }
        }
        // R3-2: Lehký index pouze (startTime + sport) ze SessionMesg bez držení TrackPointů v paměti.
        val unindexedByStartMs = HashMap<Long, MutableList<UnindexedFitEntry>>()
        if (allIds.any { it.startsWith("garmin-") } && fitDir.isDirectory) {
            val extraFitFiles = fitDir.listFiles { f ->
                f.isFile && f.name.endsWith(".fit.enc") && f.name.removeSuffix(".fit.enc") !in referencedFitHashes
            }.orEmpty()
            for (f in extraFitFiles) {
                val sessions = try {
                    val bytes = decryptFit(f.readBytes())
                    FitImporter.parseSessionIndex(bytes)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    PLog.w("ServiceMigrations", t) { "Skipping unreadable/corrupt FIT file ${f.name} during index" }
                    emptyList()
                }
                val first = sessions.firstOrNull() ?: continue
                unindexedByStartMs.getOrPut(first.startMs) { ArrayList(1) }.add(UnindexedFitEntry(f, sessions))
            }
        }

        fun findMatchingGarminFitFile(act: Activity, sessionIdx: Int): File? {
            if (unindexedByStartMs.isEmpty()) return null
            unindexedByStartMs[act.startUtcMs]?.firstOrNull { entry ->
                entry.sessions.getOrNull(sessionIdx)?.sport == act.sport
            }?.let { return it.file }
            for ((startMs, entries) in unindexedByStartMs) {
                if (kotlin.math.abs(startMs - act.startUtcMs) <= 2_000L) {
                    entries.firstOrNull { entry ->
                        entry.sessions.getOrNull(sessionIdx)?.sport == act.sport
                    }?.let { return it.file }
                }
            }
            return null
        }

        // Max. 1 plně rozparsovaný FIT soubor v paměti v jeden okamžik (R3-2).
        var currentParsedFile: File? = null
        var currentParsedSessions: List<ParsedFit>? = null
        fun parseSingleFitFile(f: File): List<ParsedFit>? {
            if (currentParsedFile == f) return currentParsedSessions
            currentParsedFile = null
            currentParsedSessions = null
            val parsed = try {
                val bytes = decryptFit(f.readBytes())
                (FitImporter.parse(bytes) as? FitResult.Ok)?.sessions
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                PLog.w("ServiceMigrations", t) { "Skipping corrupt or oversized FIT file ${f.name}" }
                null
            }
            if (parsed != null) {
                currentParsedFile = f
                currentParsedSessions = parsed
            }
            return parsed
        }

        var cleanedCount = 0
        var processed = 0
        val effectiveBatchSize = batchSize.coerceAtLeast(1)
        for (batchIds in allIds.chunked(effectiveBatchSize)) {
            val updatedActivities = ArrayList<ActivityRow>()
            val updatedTracks = ArrayList<TrackRow>()

            // Keystore decrypt + FIT parse probíhá vždy mimo DB transakci (N-14).
            for (id in batchIds) {
                val row = db.activities().get(id) ?: continue
                val act = runCatching { Wire.json.decodeFromString(Activity.serializer(), row.json) }.getOrNull() ?: continue
                val trackRow = db.activities().track(id)
                val track = trackRow?.let {
                    runCatching { Wire.json.decodeFromString(trackSerializer, it.json) }.getOrNull()
                }.orEmpty()
                val sessionIdx = id.substringAfter("-s", "").toIntOrNull() ?: 0
                val fitHash = id.removePrefix("fit-").substringBefore("-s").takeIf { id.startsWith("fit-") }
                val targetFile = fitHash?.let { h -> File(fitDir, "$h.fit.enc").takeIf { it.exists() } }
                    ?: if (id.startsWith("garmin-")) findMatchingGarminFitFile(act, sessionIdx) else null
                val reparsed = targetFile?.let { f -> parseSingleFitFile(f)?.getOrNull(sessionIdx) }

                val (newAct, newTrack) = cleanLegacyFabricatedActivity(act, track, reparsed)
                if (newAct != act) {
                    updatedActivities.add(newAct.toRow(Wire.json.encodeToString(Activity.serializer(), newAct)))
                }
                if (trackRow != null && newTrack != track) {
                    updatedTracks.add(TrackRow(id, Wire.json.encodeToString(trackSerializer, newTrack)))
                }
            }

            if (updatedActivities.isNotEmpty() || updatedTracks.isNotEmpty()) {
                db.runInTransaction {
                    updatedActivities.forEach { db.activities().upsert(it) }
                    updatedTracks.forEach { db.activities().upsertTrack(it) }
                }
                cleanedCount += updatedActivities.size + updatedTracks.size
            }
            processed += batchIds.size
            onProgress(processed, allIds.size)
        }
        currentParsedFile = null
        currentParsedSessions = null
        settings.put(KEY_FABRICATED_CLEANUP_V1, "1")
        settings.put(KEY_FABRICATED_CLEANUP_V2, "1")
        return@synchronized cleanedCount
    }

    internal fun runFabricatedCleanupWithRetryCap(
        settings: Settings,
        maxFailures: Int = MAX_CLEANUP_FAILURES,
        runCleanup: () -> Unit,
    ): Boolean {
        if (settings.getRaw(KEY_FABRICATED_CLEANUP_V2) != null) return true
        val previousFailures = settings.getRaw(KEY_FABRICATED_CLEANUP_FAILURES)?.toIntOrNull() ?: 0
        if (previousFailures >= maxFailures) {
            PLog.w("FabricatedActivityCleanupWorker") {
                "Giving up fabricated activity cleanup after $previousFailures failed runs"
            }
            settings.put(KEY_FABRICATED_CLEANUP_V1, "1")
            settings.put(KEY_FABRICATED_CLEANUP_V2, "1")
            return false
        }
        settings.put(KEY_FABRICATED_CLEANUP_FAILURES, (previousFailures + 1).toString())
        return try {
            runCleanup()
            true
        } catch (t: Throwable) {
            if (t is CancellationException) {
                runCatching { settings.put(KEY_FABRICATED_CLEANUP_FAILURES, previousFailures.toString()) }
                throw t
            }
            PLog.w("FabricatedActivityCleanupWorker", t) {
                "Batched activity cleanup failed (attempt ${previousFailures + 1}/$maxFailures)"
            }
            if (previousFailures + 1 >= maxFailures) {
                runCatching {
                    settings.put(KEY_FABRICATED_CLEANUP_V1, "1")
                    settings.put(KEY_FABRICATED_CLEANUP_V2, "1")
                }
                false
            } else {
                throw t
            }
        }
    }

    fun scheduleFabricatedActivityCleanup(context: Context) {
        runCatching {
            val req = androidx.work.OneTimeWorkRequestBuilder<FabricatedActivityCleanupWorker>().build()
            androidx.work.WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(CLEANUP_WORK_NAME, androidx.work.ExistingWorkPolicy.KEEP, req)
        }.onFailure { e ->
            PLog.w("ServiceMigrations", e) { "Could not schedule fabricated activity cleanup worker" }
        }
    }

    fun runOnce(sl: ServiceLocator) {
        synchronized(identityMigrationLock) {
            runCatching {
                val needsIdentity = sl.settings.getRaw(KEY_IDENTITY_V1) == null
                val needsWeight = sl.settings.getRaw(KEY_ACCEPT_WEIGHT_V1) == null
                val needsReemit = sl.settings.getRaw(KEY_R3_5_REEMIT_V1) == null
                // R3-14 F7: Nejprve zkontrolujeme příznaky migrací, teprve poté dešifrujeme tajemství z Keystoru.
                if (needsIdentity || needsWeight || needsReemit) {
                    val selfIdentity = deriveSelfIdentityFromSecrets(sl.secrets)
                    // R3-14 F6: Pokud selfIdentity == null (klíč ještě neexistuje nebo je nedostupný), migraci odložíme a flag nenastavíme.
                    if (selfIdentity != null) {
                        val (selfPubB64, selfLxmf) = selfIdentity
                        if (needsIdentity) {
                            migrateLegacyPulseIdentities(sl.db, sl.settings, selfPubB64)
                        }
                        if (needsWeight) {
                            migrateAcceptWeightDefaultIfVerified(sl.db, sl.settings, selfLxmf)
                        }
                        if (needsReemit) {
                            reemitPendingControlMessages(
                                db = sl.db,
                                selfId = selfLxmf,
                                emitFamilyLinkRequest = { link, ts ->
                                    sl.shareService.emitFamilyLinkRequest(link, ts, selfOverride = selfLxmf)
                                },
                                emitAdminHandoverRequest = { group, target, ts ->
                                    sl.shareService.emitAdminHandoverRequest(group, target, ts, selfOverride = selfLxmf)
                                },
                                broadcastMembershipUpdate = { group, missingRecipients, ts ->
                                    val otherMembers = (group.members - selfLxmf) - missingRecipients
                                    if (otherMembers.isEmpty()) {
                                        sl.shareService.broadcastMembershipUpdate(group, ts, selfOverride = selfLxmf)
                                    } else {
                                        // Pošleme pouze příjemcům, kteří nemají aktivní MEMBERSHIP_UPDATE.
                                        sl.shareService.broadcastMembershipUpdate(
                                            group = group.copy(members = missingRecipients + selfLxmf),
                                            nowMs = ts,
                                            selfOverride = selfLxmf,
                                        )
                                    }
                                },
                            )
                            sl.settings.put(KEY_R3_5_REEMIT_V1, "1")
                        }
                    }
                }
            }.onFailure { e ->
                PLog.w("ServiceMigrations", e) { "Identity/weight migration skipped or failed" }
            }
        }
        if (sl.settings.getRaw(KEY_FABRICATED_CLEANUP_V2) == null) {
            scheduleFabricatedActivityCleanup(sl.context)
        }
    }
}

class FabricatedActivityCleanupWorker(
    ctx: Context,
    params: androidx.work.WorkerParameters,
) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sl = (applicationContext as app.pulse4.android.Pulse4App).services
        sl.awaitMigrations()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        ensureChannel(nm)
        try {
            val completedOrSkipped = ServiceMigrations.runFabricatedCleanupWithRetryCap(sl.settings) {
                ServiceMigrations.migrateLegacyFabricatedActivities(sl, batchSize = 25) { processed, total ->
                    setProgressAsync(androidx.work.workDataOf("processed" to processed, "total" to total))
                    updateNotification(nm, processed, total)
                }
            }
            if (completedOrSkipped) Result.success() else Result.failure()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            PLog.w("FabricatedActivityCleanupWorker", t) { "Batched activity cleanup failed; scheduling retry" }
            Result.retry()
        } finally {
            runCatching { nm?.cancel(NOTIFICATION_ID) }
        }
    }

    private fun ensureChannel(nm: android.app.NotificationManager?) {
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return
        runCatching {
            val ch = android.app.NotificationChannel(
                CHANNEL_ID,
                "Optimalizace dat",
                android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Jednorázová optimalizace dříve importovaných aktivit na pozadí."
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun updateNotification(nm: android.app.NotificationManager?, processed: Int, total: Int) {
        if (nm == null || total <= 0) return
        runCatching {
            val notif = androidx.core.app.NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(app.pulse4.android.R.mipmap.ic_launcher)
                .setContentTitle("Pulse4 · optimalizace aktivit")
                .setContentText("Zpracováno $processed / $total")
                .setProgress(total, processed.coerceAtMost(total), false)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(NOTIFICATION_ID, notif)
        }
    }

    private companion object {
        const val CHANNEL_ID = "pulse4_migration_progress"
        const val NOTIFICATION_ID = 4110
    }
}
