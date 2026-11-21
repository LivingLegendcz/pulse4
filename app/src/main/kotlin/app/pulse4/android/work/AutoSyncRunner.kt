package app.pulse4.android.work

import app.pulse4.android.di.ImportScope
import app.pulse4.android.di.ServiceLocator
import app.pulse4.ui.SyncProvider

/**
 * Executes the AUTOMATIC background work that [AutoSyncPlanner] says is due. Shared by the periodic
 * [ImportWorker] and the foreground [PulseSyncService] so both follow exactly the same rules.
 *
 * Design rules (each covered by `AutoSyncRunnerTest`):
 * - The Withings session keepalive runs on its own cadence and is never blocked by import intervals, the startup
 *   grace period or the single-flight import guard.
 * - A skipped import (another import is already running) does not consume the provider's interval: it is retried
 *   shortly instead of waiting a full interval.
 * - A failing task never prevents the others from running.
 */
class AutoSyncRunner(private val env: Env) {

    interface Env {
        fun nowMs(): Long
        fun intervalMin(p: SyncProvider): Int
        fun lastSyncMs(p: SyncProvider): Long?
        fun markSynced(p: SyncProvider, atMs: Long)
        fun keepaliveLastMs(): Long?
        fun markKeepalive(atMs: Long)
        /** False when Withings was never connected (nothing to keep alive). */
        fun keepaliveEnabled(): Boolean
        /** False when Garmin was never configured (neither API key nor Garmin Connect session). */
        fun garminEnabled(): Boolean = true
        /** False when Withings was never connected (neither web nor OAuth session). */
        fun withingsEnabled(): Boolean = true
        fun withingsKeepalive()
        fun garminKeepalive()
        /** Runs the import for [scope]; returns false when it was skipped because another import is running. */
        fun runImport(nowMs: Long, scope: ImportScope): Boolean
        fun healthConnectEnabled(): Boolean
        fun healthConnectSync(nowMs: Long)
    }

    data class TickResult(
        val ran: Set<SyncTask>,
        val skippedBusy: Boolean,
        /** Delay until the next task becomes due; `null` = nothing scheduled. */
        val nextWakeMs: Long?,
    )

    fun tick(processStartMs: Long?, toleranceMs: Long): TickResult {
        val now = env.nowMs()
        val plan = AutoSyncPlanner.plan(inputs(now, processStartMs, toleranceMs))
        val ran = linkedSetOf<SyncTask>()
        var skippedBusy = false

        if (SyncTask.WITHINGS_KEEPALIVE in plan.due) {
            // Attempt time is recorded first: the coordinator has its own backoff, we only pace the pings.
            runCatching { env.markKeepalive(now) }
            runCatching { env.withingsKeepalive() }
            ran += SyncTask.WITHINGS_KEEPALIVE
        }

        val garmin = SyncTask.GARMIN_IMPORT in plan.due
        val withings = SyncTask.WITHINGS_IMPORT in plan.due
        if (garmin || withings) {
            val scope = when {
                garmin && withings -> ImportScope.ALL
                garmin -> ImportScope.GARMIN
                else -> ImportScope.WITHINGS
            }
            val previous = mapOf(
                SyncProvider.GARMIN to env.lastSyncMs(SyncProvider.GARMIN),
                SyncProvider.WITHINGS to env.lastSyncMs(SyncProvider.WITHINGS),
            )
            // Marked up front so a crash mid-import cannot cause an immediate retry loop; reverted when skipped.
            if (garmin) runCatching { env.markSynced(SyncProvider.GARMIN, now) }
            if (withings) runCatching { env.markSynced(SyncProvider.WITHINGS, now) }
            if (garmin) runCatching { env.garminKeepalive() }
            // An exception means the import did run (and failed): the interval is consumed, errors show in area state.
            val executed = runCatching { env.runImport(now, scope) }.getOrDefault(true)
            if (executed) {
                if (garmin) ran += SyncTask.GARMIN_IMPORT
                if (withings) ran += SyncTask.WITHINGS_IMPORT
                if (env.healthConnectEnabled()) runCatching { env.healthConnectSync(now) }
            } else {
                skippedBusy = true
                // Give the interval back so the busy import's completion does not delay us by a whole interval.
                if (garmin) runCatching { env.markSynced(SyncProvider.GARMIN, previous.getValue(SyncProvider.GARMIN) ?: 0L) }
                if (withings) runCatching { env.markSynced(SyncProvider.WITHINGS, previous.getValue(SyncProvider.WITHINGS) ?: 0L) }
            }
        }

        val after = AutoSyncPlanner.plan(inputs(env.nowMs(), processStartMs, toleranceMs))
        val next = if (after.due.isNotEmpty()) {
            // Something is still due (a skipped import): retry soon, never in a tight loop.
            AutoSyncPlanner.MIN_RETRY_MS * 2
        } else {
            after.nextDueInMs?.coerceAtLeast(AutoSyncPlanner.MIN_RETRY_MS)
        }
        return TickResult(ran, skippedBusy, next)
    }

    /** Manual "sync everything now": ignores intervals, but still refreshes the schedule timestamps and skips unconfigured providers. */
    fun runManual() {
        val now = env.nowMs()
        val garmin = env.garminEnabled()
        val withings = env.withingsEnabled()
        if (garmin) runCatching { env.garminKeepalive() }
        val scope = when {
            garmin && withings -> ImportScope.ALL
            garmin -> ImportScope.GARMIN
            withings -> ImportScope.WITHINGS
            else -> null
        }
        if (scope != null) {
            runCatching { env.runImport(now, scope) }
        }
        if (env.keepaliveEnabled()) {
            runCatching { env.markKeepalive(now) }
            runCatching { env.withingsKeepalive() }
        }
        if (env.healthConnectEnabled()) runCatching { env.healthConnectSync(now) }
    }

    private fun inputs(now: Long, processStartMs: Long?, toleranceMs: Long) = AutoSyncPlanner.Inputs(
        nowMs = now,
        garminIntervalMin = env.intervalMin(SyncProvider.GARMIN),
        withingsIntervalMin = env.intervalMin(SyncProvider.WITHINGS),
        garminLastMs = env.lastSyncMs(SyncProvider.GARMIN),
        withingsLastMs = env.lastSyncMs(SyncProvider.WITHINGS),
        keepaliveLastMs = env.keepaliveLastMs(),
        processStartMs = processStartMs,
        toleranceMs = toleranceMs,
        keepaliveEnabled = env.keepaliveEnabled(),
        garminEnabled = env.garminEnabled(),
        withingsEnabled = env.withingsEnabled(),
    )

    companion object {
        /** Production wiring on top of the service locator (all calls are blocking; run on an IO thread). */
        fun forServices(sl: ServiceLocator): AutoSyncRunner = AutoSyncRunner(object : Env {
            override fun nowMs() = System.currentTimeMillis()
            override fun intervalMin(p: SyncProvider) = sl.settings.syncIntervalMinutes(p)
            override fun lastSyncMs(p: SyncProvider) = sl.settings.lastSyncAtMs(p)
            override fun markSynced(p: SyncProvider, atMs: Long) = sl.settings.markSynced(p, atMs)
            override fun keepaliveLastMs() = sl.settings.withingsKeepaliveAtMs
            override fun markKeepalive(atMs: Long) { sl.settings.withingsKeepaliveAtMs = atMs }
            override fun keepaliveEnabled() = sl.withings.hasStoredWebSession()
            override fun garminEnabled() = sl.importService.hasGarminCredentials()
            override fun withingsEnabled() = sl.withings.isConnected()
            override fun withingsKeepalive() { sl.withings.renewSession() }
            override fun garminKeepalive() { sl.garminExt.ensureSession() }
            override fun runImport(nowMs: Long, scope: ImportScope) = sl.importService.runAll(nowMs, scope) != null
            override fun healthConnectEnabled() = sl.settings.healthConnectEnabled
            override fun healthConnectSync(nowMs: Long) {
                // Callers run on an IO thread (worker / service loop), so blocking here is fine.
                kotlinx.coroutines.runBlocking { sl.healthConnect.sync(nowMs, background = true) }
            }
        })
    }
}
