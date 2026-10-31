package app.pulse4.withings

import app.pulse4.domain.ImportKey
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Reconcile
import app.pulse4.domain.TimeRules
import java.time.Instant
import java.time.ZoneOffset

/** Záznam z Health Connect bez závislosti na Androidu (adaptér v aplikaci jej naplní ze `WeightRecord`/`BodyFatRecord`). */
data class HcRecord(
    val id: String,
    val type: MetricType,
    val timeMs: Long,
    val zoneOffsetSeconds: Int?,
    /** kg pro váhu, % pro tělesný tuk */
    val value: Double,
    /** Balíček aplikace, která záznam zapsala (data origin). Neznamená automaticky člena domácnosti. */
    val originPackage: String,
    val lastModifiedMs: Long,
)

sealed interface HcChange {
    data class Upsert(val record: HcRecord) : HcChange
    data class Delete(val recordId: String) : HcChange
}

class HcApplied(val upserts: List<MetricObservation>, val deletedObservationIds: List<String>, val skipped: Int)

/**
 * Health Connect jako nezávislá cesta pro váhu a složení těla. Zachovává ID záznamu a data origin; přijímají se jen
 * záznamy z povolených aplikací (výchozí: aplikace Withings), aby se do trendu nedostala cizí měření.
 */
object HealthConnectMapper {
    const val WITHINGS_PACKAGE = "com.withings.wiscale2"
    const val ACCOUNT = "healthconnect"

    fun observationId(recordId: String) = ImportKey.of(Provider.HEALTH_CONNECT, ACCOUNT, recordId, emptyList())

    fun map(r: HcRecord, defaultZone: String, allowedOrigins: Set<String> = setOf(WITHINGS_PACKAGE)): MetricObservation? {
        if (r.type != MetricType.WEIGHT && r.type != MetricType.BODY_FAT) return null
        if (allowedOrigins.isNotEmpty() && r.originPackage !in allowedOrigins) return null
        val ok = if (r.type == MetricType.WEIGHT) r.value in 10.0..500.0 else r.value in 1.0..80.0
        if (!ok) return null
        // Posun zápisu určuje místní den měření (cestování, DST); jinak platí pásmo uživatele.
        val local = r.zoneOffsetSeconds
            ?.let { Instant.ofEpochMilli(r.timeMs).atOffset(ZoneOffset.ofTotalSeconds(it)).toLocalDate() }
            ?: TimeRules.localDate(r.timeMs, defaultZone)
        return MetricObservation(
            id = observationId(r.id), provider = Provider.HEALTH_CONNECT, accountId = ACCOUNT, externalId = r.id, type = r.type,
            startUtcMs = r.timeMs, value = r.value, zoneId = defaultZone, localDate = local.toString(), origin = r.originPackage,
        )
    }

    /** Changes API: aktualizace i smazání. Smazání se vztahuje na obě podoby (váha, tuk) – ID záznamů jsou jedinečná. */
    fun apply(changes: List<HcChange>, defaultZone: String, allowedOrigins: Set<String> = setOf(WITHINGS_PACKAGE)): HcApplied {
        val ups = LinkedHashMap<String, MetricObservation>()
        val del = LinkedHashSet<String>()
        var skipped = 0
        for (c in changes) when (c) {
            is HcChange.Upsert -> map(c.record, defaultZone, allowedOrigins)?.let { ups[it.id] = it; del.remove(it.id) } ?: run { skipped++ }
            is HcChange.Delete -> { val id = observationId(c.recordId); del += id; ups.remove(id) }
        }
        return HcApplied(ups.values.toList(), del.toList(), skipped)
    }

    /** Stav synchronizace podle tokenu změn. */
    enum class Plan { FULL_READ, USE_CHANGES, FULL_REREAD_AFTER_TOKEN_EXPIRY }

    fun plan(hasToken: Boolean, tokenExpired: Boolean) = when {
        !hasToken -> Plan.FULL_READ
        tokenExpired -> Plan.FULL_REREAD_AFTER_TOKEN_EXPIRY
        else -> Plan.USE_CHANGES
    }

    /** Po vypršení tokenu se okno načte znovu a deduplikuje; co v okně chybí, dostane tombstone. */
    fun reread(fetched: List<HcRecord>, existingIds: Set<String>, defaultZone: String, allowedOrigins: Set<String> = setOf(WITHINGS_PACKAGE)): HcApplied {
        val mapped = fetched.mapNotNull { map(it, defaultZone, allowedOrigins) }.distinctBy { it.id }
        return HcApplied(mapped, Reconcile.tombstones(existingIds, mapped.map { it.id }.toSet()), fetched.size - mapped.size)
    }
}
