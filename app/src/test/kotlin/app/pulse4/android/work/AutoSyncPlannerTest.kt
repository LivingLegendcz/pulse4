package app.pulse4.android.work

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoSyncPlannerTest {
    private val min = 60_000L
    private val now = 1_000_000_000L

    private fun inputs(
        garminMin: Int = 15,
        withingsMin: Int = 60,
        garminLast: Long? = null,
        withingsLast: Long? = null,
        keepaliveLast: Long? = null,
        processStart: Long? = null,
        tolerance: Long = 0L,
        keepalive: Boolean = true,
        at: Long = now,
    ) = AutoSyncPlanner.Inputs(
        nowMs = at,
        garminIntervalMin = garminMin,
        withingsIntervalMin = withingsMin,
        garminLastMs = garminLast,
        withingsLastMs = withingsLast,
        keepaliveLastMs = keepaliveLast,
        processStartMs = processStart,
        toleranceMs = tolerance,
        keepaliveEnabled = keepalive,
    )

    @Test
    fun `garmin every 15 minutes and withings hourly by default`() {
        val p = AutoSyncPlanner.plan(
            inputs(garminLast = now - 15 * min, withingsLast = now - 15 * min, keepaliveLast = now - min),
        )
        assertEquals(setOf(SyncTask.GARMIN_IMPORT), p.due)
        // Withings is next in 45 minutes, keepalive in 29.
        assertEquals(29 * min, p.nextDueInMs)

        val later = AutoSyncPlanner.plan(
            inputs(garminLast = now - min, withingsLast = now - 60 * min, keepaliveLast = now - min),
        )
        assertEquals(setOf(SyncTask.WITHINGS_IMPORT), later.due)
    }

    @Test
    fun `both imports due together when never synced`() {
        val p = AutoSyncPlanner.plan(inputs(keepalive = false))
        assertEquals(setOf(SyncTask.GARMIN_IMPORT, SyncTask.WITHINGS_IMPORT), p.due)
        assertNull(p.nextDueInMs)
    }

    @Test
    fun `provider set to off is never due and never wakes the planner`() {
        val p = AutoSyncPlanner.plan(
            inputs(garminMin = 0, withingsMin = 0, garminLast = 0L, withingsLast = 0L, keepalive = false),
        )
        assertTrue(p.due.isEmpty())
        assertNull(p.nextDueInMs)
    }

    @Test
    fun `intervals are configurable per provider`() {
        val p = AutoSyncPlanner.plan(
            inputs(garminMin = 360, withingsMin = 30, garminLast = now - 3 * 60 * min, withingsLast = now - 30 * min, keepalive = false),
        )
        assertEquals(setOf(SyncTask.WITHINGS_IMPORT), p.due)
        assertEquals(3 * 60 * min, p.nextDueInMs)
    }

    @Test
    fun `keepalive is not blocked by the startup grace period that blocks imports`() {
        val p = AutoSyncPlanner.plan(inputs(processStart = now - 1_000L, keepaliveLast = now - 31 * min, garminLast = 0L, withingsLast = 0L))
        assertEquals(setOf(SyncTask.WITHINGS_KEEPALIVE), p.due)
        assertEquals(AutoImportPolicy.STARTUP_GRACE_MS - 1_000L, p.nextDueInMs)
    }

    @Test
    fun `keepalive cadence is independent of the withings import interval`() {
        // Hourly, 6 hourly or even disabled Withings imports must not stretch the 30 minute session keepalive.
        for (withings in listOf(60, 360, 0)) {
            val p = AutoSyncPlanner.plan(
                inputs(withingsMin = withings, garminMin = 0, withingsLast = now - min, keepaliveLast = now - 30 * min),
            )
            assertTrue(SyncTask.WITHINGS_KEEPALIVE in p.due, "keepalive due with withings=$withings")
            assertFalse(SyncTask.WITHINGS_IMPORT in p.due)
        }
    }

    @Test
    fun `keepalive waits for its own interval and is skipped when withings was never connected`() {
        val soon = AutoSyncPlanner.plan(inputs(garminMin = 0, withingsMin = 0, keepaliveLast = now - 10 * min))
        assertTrue(soon.due.isEmpty())
        assertEquals(20 * min, soon.nextDueInMs)

        val disabled = AutoSyncPlanner.plan(inputs(garminMin = 0, withingsMin = 0, keepalive = false, keepaliveLast = null))
        assertTrue(disabled.due.isEmpty())
        assertNull(disabled.nextDueInMs)
    }

    @Test
    fun `worker tick accepts runs slightly before the interval because WorkManager jitters`() {
        val tol = AutoSyncPlanner.WORKER_TOLERANCE_MS
        val early = AutoSyncPlanner.plan(inputs(garminLast = now - 13 * min, withingsLast = now, keepaliveLast = now, tolerance = tol, keepalive = false))
        assertEquals(setOf(SyncTask.GARMIN_IMPORT), early.due)
        val tooEarly = AutoSyncPlanner.plan(inputs(garminLast = now - 11 * min, withingsLast = now, tolerance = tol, keepalive = false))
        assertTrue(tooEarly.due.isEmpty())
        val ka = AutoSyncPlanner.plan(inputs(garminMin = 0, withingsMin = 0, keepaliveLast = now - 27 * min, tolerance = tol))
        assertEquals(setOf(SyncTask.WITHINGS_KEEPALIVE), ka.due)
    }

    @Test
    fun `timestamps from the future do not block anything`() {
        val p = AutoSyncPlanner.plan(
            inputs(garminLast = now + 10 * 60 * min, withingsLast = now + 10 * 60 * min, keepaliveLast = now + 10 * 60 * min),
        )
        assertEquals(setOf(SyncTask.GARMIN_IMPORT, SyncTask.WITHINGS_IMPORT, SyncTask.WITHINGS_KEEPALIVE), p.due)
    }
}
