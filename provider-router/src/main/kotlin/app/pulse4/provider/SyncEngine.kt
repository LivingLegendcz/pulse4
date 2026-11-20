package app.pulse4.provider

import app.pulse4.domain.MetricObservation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Každá datová oblast má vlastní kurzor a čas úspěchu; chyba spánku nesmí vytvořit zelený stav „vše synchronizováno“. */
enum class SyncArea { SLEEP, HRV, BODY_BATTERY, HEART_RATE, STEPS_STRESS, ACTIVITIES, WEIGHT }

data class AreaConfig(
    /** Překryv při opakovaném stahování kvůli zpětným opravám poskytovatele (výchozí návrh: 3 dny pro spánek a HRV). */
    val overlapMs: Long,
    /** Po jaké době bez úspěchu je oblast „zastaralá“. */
    val staleAfterMs: Long,
) {
    companion object {
        private const val DAY = 24L * 3600 * 1000
        val defaults: Map<SyncArea, AreaConfig> = mapOf(
            SyncArea.SLEEP to AreaConfig(3 * DAY, 36 * 3600_000L),
            SyncArea.HRV to AreaConfig(3 * DAY, 36 * 3600_000L),
            SyncArea.BODY_BATTERY to AreaConfig(DAY, 6 * 3600_000L),
            SyncArea.HEART_RATE to AreaConfig(DAY, 6 * 3600_000L),
            SyncArea.STEPS_STRESS to AreaConfig(DAY, 6 * 3600_000L),
            SyncArea.ACTIVITIES to AreaConfig(DAY, 6 * 3600_000L),
            // Delší překryv u váhy: smazání/oprava měření se u poskytovatele pozná jen opětovným čtením okna.
            SyncArea.WEIGHT to AreaConfig(14 * DAY, 7 * DAY),
        )
    }
}

data class AreaState(
    val cursorMs: Long? = null,
    val lastSuccessMs: Long? = null,
    val lastAttemptMs: Long? = null,
    val lastError: String? = null,
    val retryNotBeforeMs: Long? = null,
)

/** [tombstones]: ID pozorování, která zdroj už nevrací (smazaná/opravená u poskytovatele); sink je označí jako smazaná. */
data class Batch(
    val items: List<MetricObservation>,
    val nextCursorMs: Long,
    val tombstones: List<String> = emptyList(),
    /**
     * Diagnostika po metrikách ve tvaru `METRIKA=Kód` (např. `SPO2=Forbidden`, `STEPS=EmptyResponse`). Dávka se přesto
     * uloží; engine kódy zapíše do [AreaState.lastError] jako [PARTIAL_PREFIX]-hodnotu, aby chyba jedné metriky byla vidět.
     */
    val warnings: List<String> = emptyList(),
)

/** Předpona `AreaState.lastError` pro oblast, která se uložila, ale některá metrika se nestáhla nebo přišla prázdná. */
const val PARTIAL_PREFIX = "Partial:"

interface AreaFetcher {
    /** Stáhne data od [sinceMs] (včetně překryvu). Při chybě vyhazuje [ProviderError]. */
    fun fetch(area: SyncArea, sinceMs: Long?): Batch
    /** Získá nový access token z téhož osobního klíče. Vyhodí [ProviderError.Unauthorized], je-li klíč neplatný. */
    fun refreshSession()
}

fun interface BatchSink {
    /** Uloží celou dávku atomicky (v jedné DB transakci); selhání nesmí nechat polovinu dávky. */
    fun save(area: SyncArea, batch: Batch)
}

interface AreaStateStore {
    fun get(area: SyncArea): AreaState
    fun put(area: SyncArea, state: AreaState)
}

sealed interface AreaResult {
    data class Success(val saved: Int) : AreaResult
    data class Deferred(val untilMs: Long) : AreaResult
    data class NeedsUser(val action: RetryAction) : AreaResult
    data class Failed(val error: ProviderError) : AreaResult
    data object Skipped : AreaResult
}

class SyncEngine(
    private val fetcher: AreaFetcher,
    private val sink: BatchSink,
    private val states: AreaStateStore,
    private val policy: RetryPolicy = RetryPolicy(),
    private val config: Map<SyncArea, AreaConfig> = AreaConfig.defaults,
) {
    private val running = ConcurrentHashMap<String, AtomicBoolean>()

    /** Ochrana proti souběžnému importu téhož účtu (ruční spuštění se spojí s běžící úlohou). */
    fun <T> singleFlight(accountId: String, block: () -> T): T? {
        val flag = running.computeIfAbsent(accountId) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) return null
        try { return block() } finally { flag.set(false) }
    }

    fun runArea(area: SyncArea, nowMs: Long): AreaResult {
        val st = states.get(area)
        if (st.retryNotBeforeMs != null && nowMs < st.retryNotBeforeMs) return AreaResult.Skipped
        var refreshed = false
        var attempt = 0
        while (true) {
            attempt++
            try {
                val since = st.cursorMs?.let { it - (config[area]?.overlapMs ?: 0L) }
                val batch = fetcher.fetch(area, since)
                sink.save(area, batch) // 1) uložit
                // 2) teprve potom posunout kurzor; kurzor se nikdy nevrací zpět kvůli překryvu
                states.put(area, AreaState(
                    cursorMs = maxOf(batch.nextCursorMs, st.cursorMs ?: Long.MIN_VALUE),
                    lastSuccessMs = nowMs, lastAttemptMs = nowMs,
                    lastError = if (batch.warnings.isEmpty()) null else PARTIAL_PREFIX + batch.warnings.joinToString(","),
                    retryNotBeforeMs = null,
                ))
                return AreaResult.Success(batch.items.size)
            } catch (e: ProviderError) {
                when (val a = policy.decide(e, refreshed, attempt)) {
                    RetryAction.RefreshTokenOnce -> {
                        refreshed = true
                        try { fetcher.refreshSession() } catch (r: ProviderError) {
                            return fail(area, st, nowMs, r, RetryAction.NeedNewApiKey)
                        }
                        // smyčka pokračuje: jeden opakovaný pokus s novým tokenem
                    }
                    is RetryAction.WaitAndRetry -> return fail(area, st, nowMs, e, a, nowMs + a.delayMs)
                    else -> return fail(area, st, nowMs, e, a)
                }
            }
            // Úložiště (SQLite) mohlo selhat po stažení: kurzor zůstává, příště se stáhne totéž.
        }
    }

    private fun fail(area: SyncArea, prev: AreaState, nowMs: Long, e: ProviderError, a: RetryAction, retryAt: Long? = null): AreaResult {
        states.put(area, prev.copy(lastAttemptMs = nowMs, lastError = e.errorCode, retryNotBeforeMs = retryAt))
        return when (a) {
            is RetryAction.WaitAndRetry -> AreaResult.Deferred(retryAt!!)
            RetryAction.NeedNewApiKey, RetryAction.PauseForMfa, RetryAction.ShowForbidden, RetryAction.ShowNetworkUnavailable, RetryAction.ShowNotConfigured -> AreaResult.NeedsUser(a)
            RetryAction.RefreshTokenOnce -> AreaResult.Failed(e)
        }
    }
}

enum class AreaStatus { NEVER, OK, STALE, ERROR }

data class Overview(val perArea: Map<SyncArea, AreaStatus>, val allFresh: Boolean, val lastSuccessMs: Long?)

object Freshness {
    fun statusOf(area: SyncArea, s: AreaState, nowMs: Long, config: Map<SyncArea, AreaConfig> = AreaConfig.defaults): AreaStatus {
        if (s.lastSuccessMs == null) return if (s.lastError != null) AreaStatus.ERROR else AreaStatus.NEVER
        if (s.lastError != null) return AreaStatus.ERROR
        val limit = config[area]?.staleAfterMs ?: Long.MAX_VALUE
        return if (nowMs - s.lastSuccessMs > limit) AreaStatus.STALE else AreaStatus.OK
    }

    /** „Vše aktuální“ jen tehdy, když je každá oblast v pořádku. */
    fun overview(states: Map<SyncArea, AreaState>, nowMs: Long): Overview {
        val per = SyncArea.entries.associateWith { statusOf(it, states[it] ?: AreaState(), nowMs) }
        return Overview(per, per.values.all { it == AreaStatus.OK }, states.values.mapNotNull { it.lastSuccessMs }.maxOrNull())
    }
}
