package app.pulse4.android.di

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.pulse4.android.data.RoomAreaStateStore
import app.pulse4.android.data.toRow
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.provider.AreaState
import app.pulse4.provider.SyncArea
import app.pulse4.withings.HcChange
import app.pulse4.withings.HcRecord
import app.pulse4.withings.HealthConnectMapper
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface HcResult {
    data class Done(val upserts: Int, val deletions: Int, val mode: HealthConnectMapper.Plan) : HcResult
    data object Unavailable : HcResult
    data object NoPermission : HcResult
}

/**
 * Health Connect jako nezávislá cesta pro váhu a složení těla (bez Withings účtu v aplikaci). Ne všechny metriky Withings se
 * do Health Connect dostávají; přejímací test porovná hodnoty se zdrojovým měřením. Mapování, tombstony a deduplikace
 * jsou v JVM modulu `connector-withings` (testované); tato třída je tenký adaptér nad Androidem.
 */
class HealthConnectSource(private val context: Context, private val sl: ServiceLocator) {
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
    )
    /** Čtení na pozadí a historie před standardním 30denním oknem jsou zvláštní oprávnění; bez nich jen v popředí a v rozsahu. */
    // Záměrně se nežádají (vyžadují zvláštní schválení Google Play); bez nich jen popředí a 29denní okno.
    val optionalPermissions: Set<String> = emptySet()

    private val syncMutex = Mutex()

    fun available() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun recordAttemptError(nowMs: Long, error: String) {
        val store = RoomAreaStateStore(sl.db, "hc.")
        val prev = store.get(SyncArea.WEIGHT)
        store.put(
            SyncArea.WEIGHT,
            prev.copy(
                lastAttemptMs = nowMs,
                lastError = error,
            )
        )
    }

    suspend fun sync(nowMs: Long, background: Boolean): HcResult = syncMutex.withLock {
        if (!available()) {
            recordAttemptError(nowMs, "Health Connect není dostupný")
            return@withLock HcResult.Unavailable
        }
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(permissions)) {
            recordAttemptError(nowMs, "Chybí oprávnění Health Connect")
            return@withLock HcResult.NoPermission
        }
        if (background && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted) {
            recordAttemptError(nowMs, "Chybí oprávnění pro čtení Health Connect na pozadí")
            return@withLock HcResult.NoPermission
        }

        val s = sl.settings
        val zone = s.zoneId
        val origins = s.healthConnectOrigins
        val token = s.healthConnectToken?.takeIf { it.isNotBlank() }
        val db = sl.db
        val historyDays = if (HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted) 365L else 29L

        suspend fun readWindow(): List<HcRecord> {
            val range = TimeRangeFilter.between(Instant.ofEpochMilli(nowMs - historyDays * 86_400_000L), Instant.ofEpochMilli(nowMs))
            val out = ArrayList<HcRecord>()
            var pageToken: String? = null
            do {
                val r = client.readRecords(ReadRecordsRequest(WeightRecord::class, range, pageToken = pageToken))
                r.records.forEach { out += HcRecord(it.metadata.id, MetricType.WEIGHT, it.time.toEpochMilli(), it.zoneOffset?.totalSeconds, it.weight.inKilograms, it.metadata.dataOrigin.packageName, it.metadata.lastModifiedTime.toEpochMilli()) }
                pageToken = r.pageToken
            } while (pageToken != null)
            do {
                val r = client.readRecords(ReadRecordsRequest(BodyFatRecord::class, range, pageToken = pageToken))
                r.records.forEach { out += HcRecord(it.metadata.id, MetricType.BODY_FAT, it.time.toEpochMilli(), it.zoneOffset?.totalSeconds, it.percentage.value, it.metadata.dataOrigin.packageName, it.metadata.lastModifiedTime.toEpochMilli()) }
                pageToken = r.pageToken
            } while (pageToken != null)
            return out
        }

        suspend fun newToken() = client.getChangesToken(ChangesTokenRequest(setOf(WeightRecord::class, BodyFatRecord::class)))

        try {
            var mode = HealthConnectMapper.plan(token != null, tokenExpired = false)
            var applied: app.pulse4.withings.HcApplied
            var tokenToSave: String? = null
            if (token != null) {
                val changes = ArrayList<HcChange>()
                var t: String = token; var expired = false
                do {
                    val r = client.getChanges(t)
                    if (r.changesTokenExpired) { expired = true; break }
                    r.changes.forEach { c ->
                        when (c) {
                            is UpsertionChange -> when (val rec = c.record) {
                                is WeightRecord -> changes += HcChange.Upsert(HcRecord(rec.metadata.id, MetricType.WEIGHT, rec.time.toEpochMilli(), rec.zoneOffset?.totalSeconds, rec.weight.inKilograms, rec.metadata.dataOrigin.packageName, rec.metadata.lastModifiedTime.toEpochMilli()))
                                is BodyFatRecord -> changes += HcChange.Upsert(HcRecord(rec.metadata.id, MetricType.BODY_FAT, rec.time.toEpochMilli(), rec.zoneOffset?.totalSeconds, rec.percentage.value, rec.metadata.dataOrigin.packageName, rec.metadata.lastModifiedTime.toEpochMilli()))
                                else -> Unit
                            }
                            is DeletionChange -> changes += HcChange.Delete(c.recordId)
                        }
                    }
                    t = r.nextChangesToken
                    if (!r.hasMore) break
                } while (true)
                if (expired) {
                    // Token vypršel (30 dní): okno se načte znovu, deduplikuje a co chybí, dostane tombstone.
                    mode = HealthConnectMapper.plan(true, true)
                    val from = nowMs - historyDays * 86_400_000L
                    applied = HealthConnectMapper.reread(readWindow(), db.observations().idsIn(Provider.HEALTH_CONNECT.name, from, nowMs).toSet(), zone, origins)
                    tokenToSave = newToken()
                } else {
                    applied = HealthConnectMapper.apply(changes, zone, origins)
                    tokenToSave = t
                }
            } else {
                applied = HealthConnectMapper.reread(readWindow(), emptySet(), zone, origins)
                tokenToSave = newToken()
            }
            db.runInTransaction {
                db.observations().upsert(applied.upserts.map { it.toRow() })
                if (applied.deletedObservationIds.isNotEmpty()) db.observations().markDeleted(applied.deletedObservationIds)
            }
            if (tokenToSave != null) {
                s.healthConnectToken = tokenToSave
            }
            // Stav po zdroji (čas posledního úspěchu) – oddělený od Garminu.
            RoomAreaStateStore(db, "hc.").put(SyncArea.WEIGHT, AreaState(null, nowMs, nowMs, null, null))
            return@withLock HcResult.Done(applied.upserts.size, applied.deletedObservationIds.size, mode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordAttemptError(nowMs, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }
}
