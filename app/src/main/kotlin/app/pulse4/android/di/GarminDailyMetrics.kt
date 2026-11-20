package app.pulse4.android.di

import app.pulse4.android.data.AppDatabase
import app.pulse4.android.data.ExcludedRow
import app.pulse4.android.data.ObservationRow
import app.pulse4.domain.ImportKey
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.provider.AreaState
import app.pulse4.provider.AreaStateStore
import app.pulse4.provider.SyncArea

/**
 * Denní souhrny Garmin Personal (jedna hodnota za den a metriku). Od 0.2.1 mají stabilní [canonicalId] odvozené z
 * `(metrika, localDate)`; starší řádky (0.1.x klíčované časem začátku/konce) s ním koexistují, dokud se nesloučí.
 * Tady je sloučení (migrace i průběžně při ukládání dávky) a jednorázová obnova historie po opravě endpointů.
 */
internal object GarminDailyMetrics {
    /** Příznak jednorázové obnovy RHR/kroků/stresu (deduplikace + reset kurzoru). */
    const val RESTORE_FLAG = "migration.daily_metrics_restore_v1"

    /** Metriky s jedním kanonickým řádkem za den. Kroky by se jinak v denním součtu započítaly dvakrát. */
    val DAILY_TYPES: List<MetricType> = listOf(
        MetricType.RESTING_HEART_RATE,
        MetricType.STEPS,
        MetricType.STRESS,
    )

    /** Oblasti, jejichž historie se po opravě znovu stáhne (Body Battery se nemění, proto v seznamu není). */
    val RESTORE_AREAS: List<SyncArea> = listOf(SyncArea.HEART_RATE, SyncArea.STEPS_STRESS)

    private val allDailyTypes: Set<MetricType> = DAILY_TYPES.toSet() + MetricType.BODY_BATTERY

    fun canonicalId(accountId: String, type: MetricType, localDate: String): String =
        ImportKey.of(Provider.GARMIN_PERSONAL, accountId, null, listOf(type.name, localDate))

    /**
     * Sloučí řádky téže metriky, účtu a dne pod kanonické ID. Smazaný nebo uživatelem vyloučený den se nikdy nevzkřísí
     * a vyloučení se přenese na kanonické ID (stejná pravidla jako dříve u Body Battery).
     */
    fun deduplicate(db: AppDatabase, types: List<MetricType>) {
        val rows = db.observations().ofTypesIncludingDeleted(types.map { it.name }, "1970-01-01", "2999-12-31")
        if (rows.isEmpty()) return
        val excludedIds = db.exclusions().allIds().toHashSet()
        val nowMs = System.currentTimeMillis()
        val toDelete = ArrayList<String>()
        val toUpsert = ArrayList<ObservationRow>()
        val exclusionsToPut = ArrayList<ExcludedRow>()
        val exclusionsToRemove = ArrayList<String>()
        val grouped = rows.filter { !it.localDate.isNullOrBlank() && it.provider == Provider.GARMIN_PERSONAL.name }
            .groupBy { listOf(it.type, it.accountId, it.localDate!!) }
        for ((key, group) in grouped) {
            val type = MetricType.valueOf(key[0])
            val accountId = key[1]
            val localDate = key[2]
            val canonicalId = canonicalId(accountId, type, localDate)
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

    /**
     * Volá se při ukládání dávky, těsně před upsertem: aktivní řádky Garmin Personal téže metriky a dne s jiným ID než
     * ukládaný řádek se označí jako smazané. Díky tomu se denní hodnota (zejména kroky) nemůže po opravě endpointů znovu
     * zdvojit, ani když stará data dosud nebyla sloučena migrací.
     */
    fun supersedeNonCanonical(db: AppDatabase, items: List<MetricObservation>) {
        val daily = items.filter { it.type in allDailyTypes && it.provider == Provider.GARMIN_PERSONAL && !it.localDate.isNullOrBlank() }
        if (daily.isEmpty()) return
        val stale = ArrayList<String>()
        for ((type, perType) in daily.groupBy { it.type }) {
            val dates = perType.mapNotNull { it.localDate }
            val existing = db.observations().forTypeInRange(type.name, dates.min(), dates.max())
            val keep = perType.associate { (it.accountId to it.localDate!!) to it.id }
            for (row in existing) {
                if (row.provider != Provider.GARMIN_PERSONAL.name) continue
                val newId = keep[row.accountId to (row.localDate ?: continue)] ?: continue
                if (row.id != newId) stale += row.id
            }
        }
        if (stale.isNotEmpty()) stale.distinct().chunked(500).forEach { db.observations().markDeleted(it) }
    }

    /**
     * Jednorázová obnova po opravě endpointů: sloučí duplicity RHR/kroků/stresu a vynuluje kurzory oblastí, aby se
     * historie (okno `initialDays`) stáhla znovu. Příznak se nastaví až po úspěchu obou kroků; po chybě se zkusí znovu.
     */
    fun restoreOnce(
        db: AppDatabase,
        states: AreaStateStore,
        getFlag: (String) -> String?,
        setFlag: (String, String) -> Unit,
    ): Boolean {
        if (getFlag(RESTORE_FLAG) == "true") return false
        return runCatching {
            deduplicate(db, DAILY_TYPES)
            for (area in RESTORE_AREAS) {
                val prev: AreaState = states.get(area)
                states.put(area, prev.copy(cursorMs = null, lastError = null, retryNotBeforeMs = null))
            }
            setFlag(RESTORE_FLAG, "true")
            true
        }.getOrDefault(false)
    }
}
