package app.pulse4.android.work

import app.pulse4.ui.SyncSchedule

/** Independent units of automatic background work. Each has its own cadence. */
enum class SyncTask { GARMIN_IMPORT, WITHINGS_IMPORT, WITHINGS_KEEPALIVE }

/**
 * Pure planner for AUTOMATIC background work (manual "Sync now" buttons never consult it).
 *
 * Data imports are due per provider according to the configured interval ([SyncSchedule]); the Withings session
 * keepalive has its own, shorter cadence ([KEEPALIVE_INTERVAL_MS]) and is deliberately NOT subject to
 * [AutoImportPolicy] limits: neither the import interval (an hourly or disabled import must not stretch the keepalive)
 * nor the startup grace period (a keepalive is a tiny session ping, not a sync that competes with the UI).
 */
object AutoSyncPlanner {
    /**
     * Keepalive cadence for the Withings web session. The server-side session lifetime is not verifiable offline
     * (see docs/withings.md), so the ping is frequent but very cheap (one small POST). 30 minutes is two ticks of
     * the 15-minute WorkManager floor and keeps the session "active" far more often than any plausible idle timeout.
     */
    const val KEEPALIVE_INTERVAL_MS: Long = 30L * 60_000L

    /** WorkManager fires "about every 15 min" with jitter, so worker-driven ticks accept slightly early runs. */
    const val WORKER_TOLERANCE_MS: Long = 3L * 60_000L

    /** Never plan a wake-up sooner than this, so a skipped (busy) task cannot create a tight loop. */
    const val MIN_RETRY_MS: Long = 30_000L

    data class Inputs(
        val nowMs: Long,
        val garminIntervalMin: Int,
        val withingsIntervalMin: Int,
        val garminLastMs: Long?,
        val withingsLastMs: Long?,
        val keepaliveLastMs: Long?,
        /** Process start for the service path (startup grace applies to imports only); `null` for worker ticks. */
        val processStartMs: Long?,
        val toleranceMs: Long = 0L,
        /** False when Withings was never connected: nothing to keep alive. */
        val keepaliveEnabled: Boolean,
        /** False when Garmin has neither API key nor Garmin Connect session: never schedule Garmin import. */
        val garminEnabled: Boolean = true,
        /** False when Withings has neither Web session nor OAuth session: never schedule Withings import. */
        val withingsEnabled: Boolean = true,
    )

    data class Plan(
        val due: Set<SyncTask>,
        /** Milliseconds until the earliest not-yet-due task, `null` when nothing is scheduled (all off). */
        val nextDueInMs: Long?,
    )

    fun plan(i: Inputs): Plan {
        val due = linkedSetOf<SyncTask>()
        var nextIn: Long? = null
        fun consider(task: SyncTask, decisionRunNow: Boolean, waitMs: Long) {
            if (decisionRunNow) due += task else nextIn = minOf(nextIn ?: Long.MAX_VALUE, waitMs)
        }

        if (i.garminEnabled) {
            importDecision(i, i.garminIntervalMin, i.garminLastMs)?.let { consider(SyncTask.GARMIN_IMPORT, it.runNow, it.waitMs) }
        }
        if (i.withingsEnabled) {
            importDecision(i, i.withingsIntervalMin, i.withingsLastMs)?.let { consider(SyncTask.WITHINGS_IMPORT, it.runNow, it.waitMs) }
        }

        if (i.keepaliveEnabled) {
            val wait = keepaliveWaitMs(i.nowMs, i.keepaliveLastMs, i.toleranceMs)
            consider(SyncTask.WITHINGS_KEEPALIVE, wait == 0L, wait)
        }
        return Plan(due, nextIn)
    }

    private fun importDecision(i: Inputs, intervalMin: Int, lastMs: Long?): AutoImportPolicy.Decision? {
        if (intervalMin <= SyncSchedule.OFF) return null
        val interval = SyncSchedule.intervalMs(intervalMin)
        return AutoImportPolicy.decide(
            nowMs = i.nowMs,
            lastImportAtMs = lastMs,
            processStartMs = i.processStartMs,
            minIntervalMs = (interval - i.toleranceMs).coerceAtLeast(0L),
        )
    }

    private fun keepaliveWaitMs(nowMs: Long, lastMs: Long?, toleranceMs: Long): Long {
        val interval = (KEEPALIVE_INTERVAL_MS - toleranceMs).coerceAtLeast(0L)
        // Never attempted, or a timestamp from the future (clock moved back): run now.
        if (lastMs == null || lastMs > nowMs + KEEPALIVE_INTERVAL_MS) return 0L
        return (lastMs + interval - nowMs).coerceAtLeast(0L)
    }
}
