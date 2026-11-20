package app.pulse4.android.work

/**
 * Pure decision logic for AUTOMATIC (background) imports. Manual "Sync now" buttons never consult it.
 *
 * Two independent limits apply, the later one wins:
 * - a minimum interval since the last import attempt (persisted, so it survives process restarts), and
 * - a grace period after the process start, so launching the app never fires a sync while the UI is loading.
 */
object AutoImportPolicy {
    const val MIN_INTERVAL_MS: Long = 15L * 60_000L

    /** Background worker runs are scheduled "about every 15 min" by the OS, so allow some jitter. */
    const val WORKER_MIN_INTERVAL_MS: Long = 12L * 60_000L

    /** No automatic import earlier than this after the process started (service path only). */
    const val STARTUP_GRACE_MS: Long = 5L * 60_000L

    data class Decision(val runNow: Boolean, val waitMs: Long)

    fun decide(
        nowMs: Long,
        lastImportAtMs: Long?,
        processStartMs: Long?,
        minIntervalMs: Long = MIN_INTERVAL_MS,
        graceMs: Long = STARTUP_GRACE_MS,
    ): Decision {
        var notBefore = Long.MIN_VALUE
        // A timestamp in the future (clock moved back) must not block imports for longer than one interval.
        if (lastImportAtMs != null && lastImportAtMs <= nowMs + minIntervalMs) {
            notBefore = maxOf(notBefore, lastImportAtMs + minIntervalMs)
        }
        if (processStartMs != null) {
            notBefore = maxOf(notBefore, processStartMs + graceMs)
        }
        val wait = if (notBefore == Long.MIN_VALUE) 0L else (notBefore - nowMs).coerceAtLeast(0L)
        return Decision(runNow = wait == 0L, waitMs = wait)
    }
}

/** Wall-clock time at which this process started; initialised from `Pulse4App.onCreate`. */
object AppProcess {
    @Volatile var startedAtMs: Long = System.currentTimeMillis()
        private set

    fun markStarted(nowMs: Long = System.currentTimeMillis()) {
        startedAtMs = nowMs
    }
}
