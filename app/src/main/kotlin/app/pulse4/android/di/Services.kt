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
import app.pulse4.domain.Sport
import app.pulse4.domain.czechName
import app.pulse4.fit.FitImporter
import app.pulse4.fit.FitResult
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
            planHandlers.handlers() + mapOf(
                WithingsService.FAMILY_WEIGHT_PUSH_KIND to KindHandler { env, payload, _, nowMs ->
                    if (!settings.acceptWeightFromParent) return@KindHandler HandlerResult.Applied
                    val obs = runCatching {
                        Wire.json.decodeFromString(app.pulse4.domain.MetricObservation.serializer(), payload)
                    }.getOrNull() ?: return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
                    val savedObs = obs.copy(
                        accountId = "withings-family:${env.author.take(8)}",
                        quality = app.pulse4.domain.Quality.OK,
                        deleted = false
                    )
                    db.observations().upsert(listOf(savedObs.toRow()))
                    val prevW = db.areaStates().get("withings.WEIGHT")
                    db.areaStates().put(AreaStateRow("withings.WEIGHT", prevW?.cursorMs, nowMs, nowMs, null, null))
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
                    db.familyLinks().upsert(FamilyLinkRow(link.id, link.parentId, link.childId, link.status.name, link.version, payload))
                    db.pendingRequests().upsert(
                        PendingRequestRow(
                            id = link.id,
                            kind = "FAMILY_LINK_REQUEST",
                            fromId = link.parentId,
                            toId = link.childId,
                            status = "PENDING",
                            createdAtMs = nowMs,
                            json = payload,
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
                    val existing = db.familyLinks().get(link.id)
                    if (existing != null && link.version <= existing.version) {
                        return@KindHandler HandlerResult.Stale
                    }
                    db.familyLinks().upsert(FamilyLinkRow(link.id, link.parentId, link.childId, link.status.name, link.version, payload))
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
                Kinds.ADMIN_HANDOVER_ACCEPT to KindHandler { env, payload, store, _ ->
                    val g = runCatching {
                        Wire.json.decodeFromString(Group.serializer(), payload)
                    }.getOrNull() ?: return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
                    if (!g.dissolved && g.effectiveAdmins.isEmpty()) {
                        return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
                    }
                    val existing = store.group(g.id)
                    if (existing != null) {
                        val permitted = env.author in existing.effectiveAdmins ||
                            existing.pendingAdminHandover?.targetMemberId == env.author
                        if (!permitted) return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
                        if (g.version <= existing.version) return@KindHandler HandlerResult.Stale
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
            bridge.onEventReceived = { ev -> t.processEvent(ev) }
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
class ImportService(private val context: Context, private val sl: ServiceLocator) {
    private val json = Wire.json

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
    fun runAll(nowMs: Long): Map<SyncArea, AreaResult>? {
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
                if (res.code !in 200..299 && sl.secrets.getString(SecretStore.GARMIN_EXT_TOKENS) != null) {
                    val pathAndQuery = url.removePrefix(cfg.baseUrl.trimEnd('/'))
                    val fallbackBody = runCatching { sl.garminExt.connectApiJson(pathAndQuery) }.getOrNull()
                    if (!fallbackBody.isNullOrBlank()) {
                        return app.pulse4.provider.HttpResponse(200, emptyMap(), fallbackBody)
                    }
                }
                if (res.code == 404 || res.code == 204) {
                    return app.pulse4.provider.HttpResponse(200, emptyMap(), "")
                }
                return res
            }
        }
        val garminStates = RoomAreaStateStore(sl.db)
        val areaCheckMetrics = mapOf(
            SyncArea.SLEEP to app.pulse4.domain.MetricType.SLEEP_DURATION,
            SyncArea.HRV to app.pulse4.domain.MetricType.HRV,
            SyncArea.BODY_BATTERY to app.pulse4.domain.MetricType.BODY_BATTERY,
            SyncArea.HEART_RATE to app.pulse4.domain.MetricType.RESTING_HEART_RATE,
            SyncArea.STEPS_STRESS to app.pulse4.domain.MetricType.STEPS,
        )
        for ((area, mType) in areaCheckMetrics) {
            if (sl.db.observations().latest(mType.name) == null) {
                garminStates.put(area, app.pulse4.provider.AreaState())
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
            onRawResponse = { spec, body -> saveRaw(spec.metric.name, body, nowMs) },
        )
        val engine = SyncEngine(fetcher, { _, batch ->
            // Celá dávka v jedné transakci; kurzor se posune až po commitu (SyncEngine).
            sl.db.runInTransaction { sl.db.observations().upsert(batch.items.map { it.toRow() }) }
        }, garminStates)
        val res = engine.singleFlight(settings.accountId) {
            SyncArea.entries.filter { a -> cfg.endpoints.any { it.area == a } }.associateWith { area -> engine.runArea(area, nowMs) }
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
        return res?.plus(SyncArea.ACTIVITIES to actRes)
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

    /**
     * Vygeneruje realistická ukázková data pro prezentaci aplikace:
     * 14 dní biometrických pozorování (HRV, klidový tep, spánek, Body Battery, kroky, stres, váha Withings),
     * vzorové sportovní aktivity z Garmin FIT & JSON s GPS trasami a P2P sdílené kontakty.
     */
    fun seedDemoData(nowMs: Long = System.currentTimeMillis()) {
        val zone = java.time.ZoneId.of(sl.settings.zoneId)
        val today = java.time.LocalDate.now(zone)

        val obsList = mutableListOf<app.pulse4.android.data.ObservationRow>()
        val hrvBase = 55.0
        val rhrBase = 49.0
        val weightBase = 75.8

        for (i in 13 downTo 0) {
            val d = today.minusDays(i.toLong())
            val dStr = d.toString()
            val dayStartMs = d.atStartOfDay(zone).toInstant().toEpochMilli()

            // HRV (ms)
            val hrvVal = (hrvBase + kotlin.math.sin(i * 0.9) * 6.5 + (if (i == 0) 4.0 else 0.0)).coerceIn(42.0, 75.0)
            obsList.add(
                app.pulse4.domain.MetricObservation(
                    id = "demo-hrv-$dStr",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "demo-hrv-$dStr",
                    type = app.pulse4.domain.MetricType.HRV,
                    startUtcMs = dayStartMs + 6 * 3600_000L,
                    endUtcMs = dayStartMs + 6 * 3600_000L,
                    value = hrvVal,
                    zoneId = sl.settings.zoneId,
                    localDate = dStr
                ).toRow()
            )

            // Resting Heart Rate (bpm)
            val rhrVal = (rhrBase - kotlin.math.cos(i * 0.8) * 2.8 + (if (i % 4 == 0) 1.5 else -1.0)).coerceIn(44.0, 58.0)
            obsList.add(
                app.pulse4.domain.MetricObservation(
                    id = "demo-rhr-$dStr",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "demo-rhr-$dStr",
                    type = app.pulse4.domain.MetricType.RESTING_HEART_RATE,
                    startUtcMs = dayStartMs + 5 * 3600_000L,
                    endUtcMs = dayStartMs + 5 * 3600_000L,
                    value = rhrVal,
                    zoneId = sl.settings.zoneId,
                    localDate = dStr
                ).toRow()
            )

            // Sleep Duration (s) & Sleep Score
            val sleepHours = 7.5 + kotlin.math.sin(i * 1.1) * 0.7 + (if (i == 0) 0.3 else 0.0)
            obsList.add(
                app.pulse4.domain.MetricObservation(
                    id = "demo-sleep-$dStr",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "demo-sleep-$dStr",
                    type = app.pulse4.domain.MetricType.SLEEP_DURATION,
                    startUtcMs = dayStartMs + 6 * 3600_000L,
                    endUtcMs = dayStartMs + 6 * 3600_000L,
                    value = sleepHours * 3600.0,
                    zoneId = sl.settings.zoneId,
                    localDate = dStr
                ).toRow()
            )

            val sleepScore = (sleepHours / 8.0 * 88.0 + kotlin.math.sin(i * 1.4) * 4.0).coerceIn(68.0, 95.0)
            obsList.add(
                app.pulse4.domain.MetricObservation(
                    id = "demo-sleepscore-$dStr",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "demo-sleepscore-$dStr",
                    type = app.pulse4.domain.MetricType.SLEEP_SCORE,
                    startUtcMs = dayStartMs + 6 * 3600_000L,
                    endUtcMs = dayStartMs + 6 * 3600_000L,
                    value = sleepScore,
                    zoneId = sl.settings.zoneId,
                    localDate = dStr
                ).toRow()
            )

            // Body Battery
            val bbVal = (84.0 + kotlin.math.sin(i * 0.7) * 12.0 + (if (i == 0) 6.0 else 0.0)).coerceIn(55.0, 98.0)
            obsList.add(
                app.pulse4.domain.MetricObservation(
                    id = "demo-bb-$dStr",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "demo-bb-$dStr",
                    type = app.pulse4.domain.MetricType.BODY_BATTERY,
                    startUtcMs = dayStartMs + 7 * 3600_000L,
                    endUtcMs = dayStartMs + 7 * 3600_000L,
                    value = bbVal,
                    zoneId = sl.settings.zoneId,
                    localDate = dStr
                ).toRow()
            )

            // Steps
            val stepsVal = (8800.0 + kotlin.math.cos(i * 1.2) * 2600.0 + (if (i % 3 == 0) 2000.0 else -400.0)).coerceIn(4800.0, 16000.0)
            obsList.add(
                app.pulse4.domain.MetricObservation(
                    id = "demo-steps-$dStr",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "demo-steps-$dStr",
                    type = app.pulse4.domain.MetricType.STEPS,
                    startUtcMs = dayStartMs + 21 * 3600_000L,
                    endUtcMs = dayStartMs + 21 * 3600_000L,
                    value = stepsVal,
                    zoneId = sl.settings.zoneId,
                    localDate = dStr
                ).toRow()
            )

            // Stress
            val stressVal = (25.0 - kotlin.math.sin(i * 0.8) * 8.0).coerceIn(16.0, 45.0)
            obsList.add(
                app.pulse4.domain.MetricObservation(
                    id = "demo-stress-$dStr",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "demo-stress-$dStr",
                    type = app.pulse4.domain.MetricType.STRESS,
                    startUtcMs = dayStartMs + 20 * 3600_000L,
                    endUtcMs = dayStartMs + 20 * 3600_000L,
                    value = stressVal,
                    zoneId = sl.settings.zoneId,
                    localDate = dStr
                ).toRow()
            )

            // Withings Weight & Bioimpedance (každé 2 dny a dnes)
            if (i % 2 == 0 || i == 0) {
                val wVal = weightBase - (13 - i) * 0.06 + kotlin.math.sin(i.toDouble()) * 0.15
                val wRound = kotlin.math.round(wVal * 10.0) / 10.0
                obsList.add(
                    app.pulse4.domain.MetricObservation(
                        id = "demo-weight-$dStr",
                        provider = Provider.WITHINGS_API,
                        accountId = "withings-demo",
                        externalId = "demo-w-$dStr",
                        type = app.pulse4.domain.MetricType.WEIGHT,
                        startUtcMs = dayStartMs + 7 * 3600_000L + 15 * 60_000L,
                        endUtcMs = dayStartMs + 7 * 3600_000L + 15 * 60_000L,
                        value = wRound,
                        zoneId = sl.settings.zoneId,
                        localDate = dStr
                    ).toRow()
                )

                // Body Fat %
                val bfVal = 14.3 - (13 - i) * 0.03
                obsList.add(
                    app.pulse4.domain.MetricObservation(
                        id = "demo-bf-$dStr",
                        provider = Provider.WITHINGS_API,
                        accountId = "withings-demo",
                        externalId = "demo-bf-$dStr",
                        type = app.pulse4.domain.MetricType.BODY_FAT,
                        startUtcMs = dayStartMs + 7 * 3600_000L + 15 * 60_000L,
                        endUtcMs = dayStartMs + 7 * 3600_000L + 15 * 60_000L,
                        value = kotlin.math.round(bfVal * 10.0) / 10.0,
                        zoneId = sl.settings.zoneId,
                        localDate = dStr
                    ).toRow()
                )

                // Pulse Wave Velocity (m/s)
                obsList.add(
                    app.pulse4.domain.MetricObservation(
                        id = "demo-pwv-$dStr",
                        provider = Provider.WITHINGS_API,
                        accountId = "withings-demo",
                        externalId = "demo-pwv-$dStr",
                        type = app.pulse4.domain.MetricType.PULSE_WAVE_VELOCITY,
                        startUtcMs = dayStartMs + 7 * 3600_000L + 15 * 60_000L,
                        endUtcMs = dayStartMs + 7 * 3600_000L + 15 * 60_000L,
                        value = 6.3,
                        zoneId = sl.settings.zoneId,
                        localDate = dStr
                    ).toRow()
                )
            }
        }

        // Dnešní EKG
        obsList.add(
            app.pulse4.domain.MetricObservation(
                id = "demo-ecg-$today",
                provider = Provider.WITHINGS_API,
                accountId = "withings-demo",
                externalId = "demo-ecg-$today",
                type = app.pulse4.domain.MetricType.ECG,
                startUtcMs = nowMs - 3600_000L,
                endUtcMs = nowMs - 3600_000L,
                value = 1.0,
                zoneId = sl.settings.zoneId,
                localDate = today.toString()
            ).toRow()
        )

        sl.db.observations().upsert(obsList)

        // 2. Import vzorových aktivit ze souboru FIT a JSON v assets + plné spektrum Garmin sportů
        if (sl.db.activities().count() == 0) {
            runCatching {
                val fitBytes = context.assets.open("sample_activity.fit").use { it.readBytes() }
                if (fitBytes.isNotEmpty()) {
                    importFit(fitBytes, nowMs)
                }
            }

            runCatching {
                val jsonStr = context.assets.open("sample_activities.json").bufferedReader().use { it.readText() }
                val rootArr = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr) as? kotlinx.serialization.json.JsonArray
                rootArr?.take(6)?.forEach { elem ->
                    val obj = elem as? kotlinx.serialization.json.JsonObject ?: return@forEach
                    val actId = obj["activityId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return@forEach
                    val actKey = "garmin-$actId"
                    if (sl.db.activities().get(actKey) == null) {
                        val act = buildActivityFromGarminAndFit(
                            id = actKey,
                            provider = Provider.GARMIN_PERSONAL,
                            extId = actId,
                            obj = obj,
                            parsed = null,
                            nowMs = nowMs
                        )
                        sl.db.activities().upsert(act.toRow(json.encodeToString(Activity.serializer(), act)))
                        val synTrack = synthesizeTrackFromSummary(act)
                        sl.db.activities().upsertTrack(
                            TrackRow(
                                actKey,
                                json.encodeToString(
                                    kotlinx.serialization.builtins.ListSerializer(app.pulse4.domain.TrackPoint.serializer()),
                                    synTrack
                                )
                            )
                        )
                    }
                }
            }
        }

        // Doplnění reprezentativních Garmin Connect aktivit pro všechny hlavní sporty z API (Cyklistika, Turistika, Síla, Plavání)
        runCatching {
            val extraSpecs = listOf(
                Activity(
                    id = "garmin-demo-cycling-gravel",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = sl.settings.accountId,
                    externalId = "2491020301",
                    sport = app.pulse4.domain.Sport.CYCLING,
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
                    sport = app.pulse4.domain.Sport.HIKING,
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
                    sport = app.pulse4.domain.Sport.STRENGTH,
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
                    sport = app.pulse4.domain.Sport.SWIMMING,
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
                            json.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(app.pulse4.domain.TrackPoint.serializer()),
                                synTrack
                            )
                        )
                    )
                    addedAny = true
                }
            }
            if (addedAny) {
                app.pulse4.android.ui.ScreenMemoryCache.activitySummaries = null
            }
        }

        // 3. Spárování ukázkových kontaktů a zpráv v P2P mesh síti (Garmin & Withings kruhy)
        if (sl.db.contacts().allNow().isEmpty()) {
            val contactTereza = app.pulse4.android.data.ContactRow(
                identity = "contact_tereza_garmin",
                displayName = "Tereza (Garmin 265)",
                publicKeyB64 = "dGVyZXphX2dhcm1pbl9wdWJrZXk=",
                fingerprint = "TEREZA-RUN",
                verifiedAtMs = nowMs
            )
            val contactTomas = app.pulse4.android.data.ContactRow(
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
                val proj = app.pulse4.sharing.SharedProjection(
                    ownerId = contactTereza.identity,
                    grantId = "grant-tereza",
                    date = dStr,
                    fields = mapOf(
                        app.pulse4.sharing.Field.WEIGHT to kotlinx.serialization.json.JsonPrimitive(58.4 - (6 - i) * 0.1),
                        app.pulse4.sharing.Field.SLEEP_DURATION to kotlinx.serialization.json.JsonPrimitive(28800.0 + (i % 3) * 900.0),
                        app.pulse4.sharing.Field.HRV_DAILY to kotlinx.serialization.json.JsonPrimitive(62.0 + (i % 4) * 2.5),
                        app.pulse4.sharing.Field.BB_LAST to kotlinx.serialization.json.JsonPrimitive(88.0 + (i % 3) * 4.0),
                        app.pulse4.sharing.Field.HR_DAILY to kotlinx.serialization.json.JsonPrimitive(48.0 - (i % 2) * 1.0)
                    ),
                    measuredAtUtcMs = nowMs - i * 86_400_000L,
                    producedAtMs = nowMs - i * 86_400_000L
                )
                val payload = app.pulse4.sync.Wire.json.encodeToString(app.pulse4.sharing.SharedProjection.serializer(), proj)
                sl.db.inbox().putObject(
                    app.pulse4.android.data.ReceivedRow(
                        author = contactTereza.identity,
                        objectType = "daily",
                        objectId = dStr,
                        version = 1L,
                        payload = payload
                    )
                )
            }

            val hrPoints = (0 until 40).map { s -> (s * 30_000L) to (148.0 + kotlin.math.sin(s / 4.0) * 12.0) }
            val spdPoints = (0 until 40).map { s -> (s * 30_000L) to (3.45 + kotlin.math.cos(s / 5.0) * 0.3) }
            val elePoints = (0 until 40).map { s -> (s * 30_000L) to (210.0 + s * 1.1) }
            val routePoints = (0 until 40).map { s ->
                app.pulse4.sharing.RoutePoint(45.0825 + s * 0.00035, 15.0280 + kotlin.math.sin(s / 6.0) * 0.0005)
            }
            val actProj = app.pulse4.sharing.ActivityProjection(
                activityId = "tereza-trail-run",
                sport = app.pulse4.domain.Sport.RUNNING.name,
                localDate = today.toString(),
                startUtcMs = nowMs - 14400_000L,
                totalTimeS = 2160.0,
                movingTimeS = 2110.0,
                distanceM = 7420.0,
                ascentM = 92.0,
                avgHeartRate = 152,
                charts = listOf(
                    app.pulse4.sharing.ChartSeries("heartRate", hrPoints),
                    app.pulse4.sharing.ChartSeries("speed", spdPoints),
                    app.pulse4.sharing.ChartSeries("elevation", elePoints)
                ),
                route = routePoints
            )
            val actPayload = app.pulse4.sync.Wire.json.encodeToString(app.pulse4.sharing.ActivityProjection.serializer(), actProj)
            sl.db.inbox().putObject(
                app.pulse4.android.data.ReceivedRow(
                    author = contactTereza.identity,
                    objectType = "activity",
                    objectId = "tereza-trail-run",
                    version = 1L,
                    payload = actPayload
                )
            )

            val selfHash = sl.bridge.ensureStarted().deliveryHash
            val demoGroup = app.pulse4.sharing.Group(
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
        val startLat = 45.0947
        val startLon = 15.0439
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
            accountId = sl.settings.accountId,
            externalId = extId,
            sport = finalSport,
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

    /** Automaticky stáhne aktivity z oficiálního Garmin Personal API (nebo přihlášené relace Garmin Connect), stáhne FIT a uloží do DB. */
    fun syncGarminActivities(nowMs: Long): Int {
        val key = sl.secrets.getString(SecretStore.GARMIN_API_KEY)?.trim()?.removePrefix("ApiKey ")?.trim()
        val hasExt = sl.secrets.getString(SecretStore.GARMIN_EXT_TOKENS) != null
        if (key.isNullOrBlank() && !hasExt) {
            return 0
        }
        return runCatching {
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
                val tokenBody = runCatching {
                    client.newCall(tokenReq).execute().use { if (it.isSuccessful) it.body?.string().orEmpty().trim() else "" }
                }.getOrDefault("")
                if (tokenBody.isNotBlank()) {
                    authHeader = cfg.authValue.replace("{accessToken}", tokenBody)
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
                    val r = runCatching { client.newCall(listReq).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" } }.getOrDefault("")
                    if (r.isNotBlank()) r else if (hasExt) runCatching { sl.garminExt.connectApiJson(pagePath) }.getOrDefault("") else ""
                } else if (hasExt) {
                    runCatching { sl.garminExt.connectApiJson(pagePath) }.getOrDefault("")
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
                            if (sl.db.activities().get(actKey) != null && sl.db.activities().track(actKey) != null) {
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

            // FÁZE 1: Rychlé uložení souhrnných metadat všech aktivit (až 1000 aktivit) do Room DB.
            // Běží bleskově v jedné transakci bez stahování velkých FIT souborů.
            var imported = 0
            var latestTimestampMs: Long? = null
            val activitiesToSave = mutableListOf<app.pulse4.android.data.ActivityRow>()

            for (elem in allActivityElements) {
                val obj = (elem as? kotlinx.serialization.json.JsonObject) ?: continue
                val actId = obj["activityId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: continue
                val actKey = "garmin-$actId"
                val existingGarmin = sl.db.activities().get(actKey)

                if (existingGarmin == null) {
                    val act = buildActivityFromGarminAndFit(
                        id = actKey,
                        provider = Provider.GARMIN_PERSONAL,
                        extId = actId,
                        obj = obj,
                        parsed = null,
                        nowMs = nowMs
                    )
                    activitiesToSave.add(act.toRow(json.encodeToString(Activity.serializer(), act)))
                    if (latestTimestampMs == null || act.startUtcMs > latestTimestampMs!!) {
                        latestTimestampMs = act.startUtcMs
                    }
                } else {
                    imported++
                }
            }

            if (activitiesToSave.isNotEmpty()) {
                sl.db.runInTransaction {
                    for (row in activitiesToSave) {
                        sl.db.activities().upsert(row)
                    }
                }
                imported += activitiesToSave.size
            }

            // FÁZE 2: Progresivní stažení FIT souborů / GPS křivek pouze pro nejnovější N aktivit.
            // Starší aktivity zůstávají v DB s kompletními souhrnnými daty a jejich GPS křivka se dotáhne
            // na vyžádání (lazy) při rozkliknutí nebo na pozadí, čímž se zamezí zamrzání a limitům Garminu (HTTP 429).
            val maxImmediateFitDownloads = if (isInitialSync) 15 else 30
            var fitDownloadsCount = 0

            for (elem in allActivityElements) {
                if (fitDownloadsCount >= maxImmediateFitDownloads) break
                val obj = (elem as? kotlinx.serialization.json.JsonObject) ?: continue
                val actId = obj["activityId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: continue
                val actKey = "garmin-$actId"
                if (sl.db.activities().track(actKey) != null) continue

                var fitImported = false
                // 3a. Stažení FIT z gateway (pokud je dostupný authHeader)
                if (authHeader != null) {
                    val downloadReq = okhttp3.Request.Builder()
                        .url("${cfg.baseUrl.trimEnd('/')}/download-service/files/activity/$actId")
                        .header(cfg.authHeader, authHeader)
                        .get()
                        .build()
                    val dlBytes = runCatching { client.newCall(downloadReq).execute().use { it.body?.bytes() } }.getOrNull()
                    if (dlBytes != null && dlBytes.isNotEmpty()) {
                        val fit = extractFitFromZipOrRaw(dlBytes)
                        if (fit != null && importFit(fit, nowMs, garminObj = obj) is FitImportResult.Imported) {
                            fitImported = true
                        }
                    }
                }

                // 3b. Stažení původního FIT přes přihlášenou relaci Garmin Connect
                if (!fitImported && hasExt) {
                    val rawExtFit = runCatching { sl.garminExt.downloadActivityFit(actId) }.getOrNull()
                    if (rawExtFit != null && rawExtFit.isNotEmpty()) {
                        val fit = extractFitFromZipOrRaw(rawExtFit)
                        if (fit != null && importFit(fit, nowMs, garminObj = obj) is FitImportResult.Imported) {
                            fitImported = true
                        }
                    }
                }

                // 3c. Fallback: Pokus o získání GPS křivky z detailů Garminu
                if (!fitImported && hasExt && sl.db.activities().track(actKey) == null) {
                    val detailsJson = runCatching { sl.garminExt.getActivityDetailsJson(actId) }.getOrDefault("")
                    if (detailsJson.isNotBlank()) {
                        val gpsPoints = parseGarminDetailsGpsPoints(detailsJson)
                        if (gpsPoints.isNotEmpty()) {
                            sl.db.activities().upsertTrack(
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
                try { Thread.sleep(80) } catch (_: InterruptedException) {}
            }

            val cursor = latestTimestampMs ?: prevState?.cursorMs ?: nowMs
            sl.db.areaStates().put(AreaStateRow(SyncArea.ACTIVITIES.name, cursor, nowMs, nowMs, null, null))
            imported
        }.getOrElse { e ->
            android.util.Log.e("ImportService", "syncGarminActivities failed", e)
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

    /** Na vyžádání (lazy) dotáhne FIT nebo GPS křivku pro zadanou aktivitu, pokud ji ještě nemá v lokální DB. */
    fun ensureActivityTrack(actKey: String): Boolean {
        if (sl.db.activities().track(actKey) != null) return true
        val actId = actKey.removePrefix("garmin-")
        val hasExt = sl.secrets.getString(SecretStore.GARMIN_EXT_TOKENS) != null
        if (!hasExt) return false
        return runCatching {
            val rawFit = sl.garminExt.downloadActivityFit(actId)
            if (rawFit != null && rawFit.isNotEmpty()) {
                val fit = extractFitFromZipOrRaw(rawFit)
                if (fit != null && importFit(fit, System.currentTimeMillis(), garminObj = null) is FitImportResult.Imported) {
                    return@runCatching true
                }
            }
            val detailsJson = sl.garminExt.getActivityDetailsJson(actId)
            if (detailsJson.isNotBlank()) {
                val gpsPoints = parseGarminDetailsGpsPoints(detailsJson)
                if (gpsPoints.isNotEmpty()) {
                    sl.db.activities().upsertTrack(
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

    /** Zpracuje GPS body a telemetrii z Garmin Connect activity details JSON. */
    fun parseGarminDetailsGpsPoints(detailsJson: String): List<app.pulse4.domain.TrackPoint> {
        return runCatching<List<app.pulse4.domain.TrackPoint>> {
            val root = json.parseToJsonElement(detailsJson) as? kotlinx.serialization.json.JsonObject ?: return@runCatching emptyList()
            // 1. Zkusit geoPolylineDTO
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

            // 2. Zkusit activityDetailMetrics
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
                val actId = garminObj?.get("activityId")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                val id = if (actId != null) "garmin-$actId" else "fit-$hash"
                val prov = if (garminObj != null) Provider.GARMIN_PERSONAL else Provider.MANUAL
                val a = buildActivityFromGarminAndFit(
                    id = id,
                    provider = prov,
                    extId = actId ?: hash,
                    obj = garminObj,
                    parsed = p,
                    nowMs = nowMs
                )
                sl.db.runInTransaction {
                    sl.db.activities().upsert(a.toRow(json.encodeToString(Activity.serializer(), a)))
                    if (p.points.isNotEmpty()) {
                        sl.db.activities().upsertTrack(TrackRow(id, json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(app.pulse4.domain.TrackPoint.serializer()), p.points)))
                    }
                }
                FitImportResult.Imported(id)
            }
        }
    }

    /** Doplní normalizované sloupce souhrnu (title, distance, time, calories atd.) u starších záznamů bez nutnosti číst JSON v seznamu. */
    fun backfillActivitySummaries(): Int {
        var updated = 0
        while (true) {
            val batch = sl.db.activities().unbackfilled(100)
            if (batch.isEmpty()) break
            for (item in batch) {
                val a = runCatching { Wire.json.decodeFromString(Activity.serializer(), item.json) }.getOrNull()
                if (a != null) {
                    sl.db.activities().updateSummary(
                        id = a.id,
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

    fun grants(): List<ShareGrant> = sl.db.grants().allNow().map { json.decodeFromString(ShareGrant.serializer(), it.json) }
    fun groups(): Map<String, Group> = sl.db.grants().groupsNow().associate { it.id to json.decodeFromString(Group.serializer(), it.json) }
    fun familyLinks(): List<FamilyLink> = sl.db.familyLinks().allNow().map { json.decodeFromString(FamilyLink.serializer(), it.json) }
    fun pendingRequests(): List<PendingRequestRow> = sl.db.pendingRequests().pendingNow()

    fun saveGrant(g: ShareGrant) = sl.db.grants().upsert(GrantRow(g.id, !g.active, json.encodeToString(ShareGrant.serializer(), g)))
    fun saveGroup(g: Group) = sl.db.grants().upsertGroup(GroupRow(g.id, g.version, json.encodeToString(Group.serializer(), g)))

    fun saveGrantWithPolicy(grant: ShareGrant, guardianCoConsentAtMs: Long? = null): Result<ShareGrant> {
        val self = sl.bridge.ensureStarted().deliveryHash
        val ageProfile = AgeProfile.valueOf(sl.settings.ownAgeProfile)
        if (!FamilyLinkPolicy.canActivateShareGrant(self, ageProfile, grant, familyLinks(), guardianCoConsentAtMs)) {
            return Result.failure(
                IllegalStateException("Sdílení nelze aktivovat: pro nezletilý účet je nutná ověřená rodinná vazba a souhlas zákonného zástupce.")
            )
        }
        saveGrant(grant)
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
    }

    /** Vytvoří projekce změněných dat pro každého skutečného příjemce a zařadí je do outboxu. Vrací počet zařazených. */
    fun planAndEnqueue(nowMs: Long): Int {
        val self = sl.bridge.ensureStarted().deliveryHash
        val zone = ZoneId.of(sl.settings.zoneId)
        val today = LocalDate.now(zone)
        val activeGrants = grants().filter { it.active && it.ownerId == self }
        val earliestGrantFrom = activeGrants.mapNotNull { it.scope.from?.let { f -> runCatching { LocalDate.parse(f) }.getOrNull() } }.minOrNull()
        val from = earliestGrantFrom ?: today.minusDays(60)
        val dates = sl.db.observations().datesWithData(from.toString(), today.toString()).map(LocalDate::parse)
        val allObservations = sl.db.observations().forRange(from.toString(), today.toString()).map { it.toDomain() }
        // Nejdřív nahradit prošlé nepotvrzené souhrny čerstvým snapshotem.
        sl.outbox.expireStale(nowMs).forEach { (recipient, key) -> sl.sentLedger.record(recipient, key, (sl.sentLedger.last(recipient, key)?.version ?: 0), "expired") }

        var queued = 0
        val plan = ShareBuilder(self, MessageBuilder(self)).plan(grants(), groups(), dates, allObservations, sl.sentLedger, nowMs)
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
        android.util.Log.i("Pulse4Sync", "enqueueActivities: from=$from, acts=${acts.size}, activeGrants=${grants().filter { it.active }.size}")
        for (row in acts.take(20)) {
            val act = json.decodeFromString(Activity.serializer(), row.json)
            val track = sl.db.activities().track(row.id)?.let {
                json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(app.pulse4.domain.TrackPoint.serializer()), it.json)
            }.orEmpty()
            for (g in grants().filter { it.active && it.ownerId == self && it.fields.any { f -> f.category == app.pulse4.sharing.Category.ACTIVITY } }) {
                if (!g.allowsProvider(act.provider)) continue
                val proj = ActivityProjector.project(g, act, track, emptyList<LapIn>(), zones)
                android.util.Log.i("Pulse4Sync", "act=${act.id}, grant=${g.id}, scope=${g.scope}, projIsNull=${proj == null}")
                if (proj == null) continue
                val payload = json.encodeToString(app.pulse4.sharing.ActivityProjection.serializer(), proj)
                val hash = Wire.sha256Hex(payload.toByteArray())
                val recipients = app.pulse4.sharing.MembershipPolicy.effectiveRecipients(g, g.groupId?.let(groups()::get))
                android.util.Log.i("Pulse4Sync", "act=${act.id}, payloadLen=${payload.length}, recipients=$recipients")
                for (r in recipients) {
                    val key = "activity/${act.id}"
                    val last = sl.sentLedger.last(r, key)
                    if (last?.contentHash == hash) {
                        android.util.Log.i("Pulse4Sync", "act=${act.id} skipped, same hash as last sent")
                        continue
                    }
                    val msg = MessageBuilder(self).build(r, Kinds.PROJECTION_UPSERT, "activity", act.id, (last?.version ?: 0) + 1, payload, nowMs, grantRef = g.id)
                    val e = msg.envelope
                    val res = sl.outbox.enqueue(OutboxItem(e.eventId, r, e.kind, e.objectType, e.objectId, e.objectVersion, g.id, Wire.encode(msg), nowMs))
                    android.util.Log.i("Pulse4Sync", "act=${act.id} enqueue result: $res")
                    if (res is EnqueueResult.Queued) {
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
