package app.pulse4.android.work

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoImportPolicyTest {
    private val min = AutoImportPolicy.MIN_INTERVAL_MS
    private val grace = AutoImportPolicy.STARTUP_GRACE_MS

    @Test
    fun appLaunchDoesNotImportDuringStartupGraceEvenWhenNeverImported() {
        val start = 1_000_000L
        val d = AutoImportPolicy.decide(nowMs = start + 1_000, lastImportAtMs = null, processStartMs = start)
        assertFalse(d.runNow)
        assertEquals(grace - 1_000, d.waitMs)
    }

    @Test
    fun importAllowedAfterGraceWhenNeverImported() {
        val start = 1_000_000L
        assertTrue(AutoImportPolicy.decide(start + grace, null, start).runNow)
    }

    @Test
    fun recentImportBlocksAcrossProcessRestart() {
        // Last import 2 minutes ago, process restarted 6 minutes ago (grace over): still blocked by the interval.
        val now = 10_000_000L
        val d = AutoImportPolicy.decide(now, lastImportAtMs = now - 2 * 60_000L, processStartMs = now - 6 * 60_000L)
        assertFalse(d.runNow)
        assertEquals(min - 2 * 60_000L, d.waitMs)
    }

    @Test
    fun oldImportAndOldProcessAllowImport() {
        val now = 100_000_000L
        assertTrue(AutoImportPolicy.decide(now, now - min, now - grace - 1).runNow)
    }

    @Test
    fun laterOfTheTwoLimitsWins() {
        val now = 50_000_000L
        // Interval ended long ago but the process just started -> grace decides.
        val d = AutoImportPolicy.decide(now, lastImportAtMs = now - 10 * min, processStartMs = now - 1_000)
        assertEquals(grace - 1_000, d.waitMs)
    }

    @Test
    fun workerHasNoStartupGraceAndUsesShorterInterval() {
        val now = 70_000_000L
        val wmin = AutoImportPolicy.WORKER_MIN_INTERVAL_MS
        assertTrue(AutoImportPolicy.decide(now, now - wmin, null, minIntervalMs = wmin).runNow)
        assertFalse(AutoImportPolicy.decide(now, now - wmin + 1, null, minIntervalMs = wmin).runNow)
    }

    @Test
    fun timestampFromTheFutureDoesNotBlockForever() {
        val now = 80_000_000L
        // Clock moved back: stored value is far in the future -> ignored.
        assertTrue(AutoImportPolicy.decide(now, lastImportAtMs = now + 5 * min, processStartMs = null).runNow)
    }

    @Test
    fun noInformationMeansRunNow() {
        val d = AutoImportPolicy.decide(1L, null, null)
        assertTrue(d.runNow)
        assertEquals(0L, d.waitMs)
    }
}
