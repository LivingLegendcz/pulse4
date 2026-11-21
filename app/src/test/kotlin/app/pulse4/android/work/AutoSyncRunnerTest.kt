package app.pulse4.android.work

import app.pulse4.android.di.ImportScope
import app.pulse4.ui.SyncProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoSyncRunnerTest {
    private val min = 60_000L

    private class FakeEnv(var now: Long = 1_000_000_000L) : AutoSyncRunner.Env {
        val intervals = mutableMapOf(SyncProvider.GARMIN to 15, SyncProvider.WITHINGS to 60)
        val last = mutableMapOf<SyncProvider, Long?>(SyncProvider.GARMIN to null, SyncProvider.WITHINGS to null)
        var keepaliveLast: Long? = null
        var hasSession = true
        var hasGarmin = true
        var hasWithings = true
        var hcEnabled = false
        var importResult: () -> Boolean = { true }
        val calls = mutableListOf<String>()
        val scopes = mutableListOf<ImportScope>()
        var withingsKeepaliveThrows = false

        override fun nowMs() = now
        override fun intervalMin(p: SyncProvider) = intervals.getValue(p)
        override fun lastSyncMs(p: SyncProvider) = last[p]
        override fun markSynced(p: SyncProvider, atMs: Long) { last[p] = atMs }
        override fun keepaliveLastMs() = keepaliveLast
        override fun markKeepalive(atMs: Long) { keepaliveLast = atMs }
        override fun keepaliveEnabled() = hasSession
        override fun garminEnabled() = hasGarmin
        override fun withingsEnabled() = hasWithings
        override fun withingsKeepalive() { calls += "withingsKeepalive"; if (withingsKeepaliveThrows) error("boom") }
        override fun garminKeepalive() { calls += "garminKeepalive" }
        override fun runImport(nowMs: Long, scope: ImportScope): Boolean {
            calls += "import:$scope"; scopes += scope
            return importResult()
        }
        override fun healthConnectEnabled() = hcEnabled
        override fun healthConnectSync(nowMs: Long) { calls += "hc" }
    }

    @Test
    fun `fresh install runs keepalive first then one combined import for both providers`() {
        val env = FakeEnv()
        val r = AutoSyncRunner(env).tick(processStartMs = null, toleranceMs = 0L)
        assertEquals(listOf("withingsKeepalive", "garminKeepalive", "import:ALL"), env.calls)
        assertEquals(setOf(SyncTask.WITHINGS_KEEPALIVE, SyncTask.GARMIN_IMPORT, SyncTask.WITHINGS_IMPORT), r.ran)
        assertEquals(env.now, env.last[SyncProvider.GARMIN])
        assertEquals(env.now, env.last[SyncProvider.WITHINGS])
        assertEquals(env.now, env.keepaliveLast)
        // Next wake: Garmin in 15 minutes.
        assertEquals(15 * min, r.nextWakeMs)
    }

    @Test
    fun `only garmin due runs a garmin scoped import and does not touch withings`() {
        val env = FakeEnv().apply {
            last[SyncProvider.GARMIN] = now - 15 * min
            last[SyncProvider.WITHINGS] = now - 20 * min
            keepaliveLast = now - 5 * min
        }
        val r = AutoSyncRunner(env).tick(null, 0L)
        assertEquals(listOf("garminKeepalive", "import:GARMIN"), env.calls)
        assertEquals(setOf(SyncTask.GARMIN_IMPORT), r.ran)
        assertEquals(env.now - 20 * min, env.last[SyncProvider.WITHINGS])
    }

    @Test
    fun `only withings due runs a withings scoped import`() {
        val env = FakeEnv().apply {
            last[SyncProvider.GARMIN] = now - 5 * min
            last[SyncProvider.WITHINGS] = now - 60 * min
            keepaliveLast = now - 5 * min
        }
        AutoSyncRunner(env).tick(null, 0L)
        assertEquals(listOf(ImportScope.WITHINGS), env.scopes)
        assertFalse(env.calls.contains("garminKeepalive"), "garmin keepalive belongs to the garmin task")
    }

    @Test
    fun `keepalive runs even when the single-flight guard skips the import`() {
        val env = FakeEnv().apply {
            last[SyncProvider.GARMIN] = null
            last[SyncProvider.WITHINGS] = null
            importResult = { false } // another import (e.g. a manual sync) is already running
        }
        val r = AutoSyncRunner(env).tick(null, 0L)
        assertTrue(SyncTask.WITHINGS_KEEPALIVE in r.ran)
        assertTrue(env.calls.contains("withingsKeepalive"))
        assertTrue(r.skippedBusy)
        assertTrue(SyncTask.GARMIN_IMPORT !in r.ran && SyncTask.WITHINGS_IMPORT !in r.ran)
    }

    @Test
    fun `a skipped import gives the interval back and is retried soon instead of after a full interval`() {
        val env = FakeEnv().apply {
            last[SyncProvider.GARMIN] = now - 40 * min
            last[SyncProvider.WITHINGS] = now - 90 * min
            keepaliveLast = now
            importResult = { false }
        }
        val before = env.last.toMap()
        val r = AutoSyncRunner(env).tick(null, 0L)
        assertEquals(before, env.last.toMap())
        val wake = r.nextWakeMs
        assertTrue(wake != null && wake in 30_000L..5 * min, "retry soon, not after an interval: $wake")
    }

    @Test
    fun `startup grace blocks the imports of the service path but not the keepalive`() {
        val env = FakeEnv().apply { keepaliveLast = now - 31 * min }
        val r = AutoSyncRunner(env).tick(processStartMs = env.now - 1_000L, toleranceMs = 0L)
        assertEquals(setOf(SyncTask.WITHINGS_KEEPALIVE), r.ran)
        assertEquals(listOf("withingsKeepalive"), env.calls)
        // Imports are due right after the grace period.
        assertEquals(AutoImportPolicy.STARTUP_GRACE_MS - 1_000L, r.nextWakeMs)
    }

    @Test
    fun `disabled providers never import but the keepalive still protects the session`() {
        val env = FakeEnv().apply {
            intervals[SyncProvider.GARMIN] = 0
            intervals[SyncProvider.WITHINGS] = 0
            keepaliveLast = now - 45 * min
        }
        val r = AutoSyncRunner(env).tick(null, 0L)
        assertEquals(listOf("withingsKeepalive"), env.calls)
        assertEquals(setOf(SyncTask.WITHINGS_KEEPALIVE), r.ran)
        assertEquals(30 * min, r.nextWakeMs)
    }

    @Test
    fun `nothing scheduled at all means no wake time`() {
        val env = FakeEnv().apply {
            intervals[SyncProvider.GARMIN] = 0
            intervals[SyncProvider.WITHINGS] = 0
            hasSession = false
        }
        val r = AutoSyncRunner(env).tick(null, 0L)
        assertTrue(env.calls.isEmpty())
        assertNull(r.nextWakeMs)
    }

    @Test
    fun `a failing keepalive does not stop the imports`() {
        val env = FakeEnv().apply { withingsKeepaliveThrows = true }
        val r = AutoSyncRunner(env).tick(null, 0L)
        assertTrue(SyncTask.GARMIN_IMPORT in r.ran)
        assertTrue(env.calls.contains("import:ALL"))
    }

    @Test
    fun `an import that throws still consumes the interval so a crash cannot cause a retry loop`() {
        val env = FakeEnv().apply { importResult = { error("boom") } }
        val r = AutoSyncRunner(env).tick(null, 0L)
        assertEquals(env.now, env.last[SyncProvider.GARMIN])
        assertTrue(SyncTask.GARMIN_IMPORT in r.ran)
    }

    @Test
    fun `health connect piggybacks on whichever import ran`() {
        val env = FakeEngineWithHc()
        AutoSyncRunner(env).tick(null, 0L)
        assertTrue(env.calls.contains("hc"))

        val idle = FakeEngineWithHc().apply {
            last[SyncProvider.GARMIN] = now
            last[SyncProvider.WITHINGS] = now
            keepaliveLast = now
        }
        AutoSyncRunner(idle).tick(null, 0L)
        assertFalse(idle.calls.contains("hc"))
    }

    private fun FakeEngineWithHc() = FakeEnv().apply { hcEnabled = true }

    @Test
    fun `manual run ignores intervals and still does keepalive`() {
        val env = FakeEnv().apply {
            last[SyncProvider.GARMIN] = now
            last[SyncProvider.WITHINGS] = now
            intervals[SyncProvider.GARMIN] = 0
            intervals[SyncProvider.WITHINGS] = 0
        }
        AutoSyncRunner(env).runManual()
        assertEquals(listOf("garminKeepalive", "import:ALL", "withingsKeepalive"), env.calls)
    }

    @Test
    fun `unconfigured garmin is never scheduled or called on fresh install`() {
        val env = FakeEnv().apply {
            hasGarmin = false
            hasWithings = false
            hasSession = false
        }
        val tick = AutoSyncRunner(env).tick(processStartMs = null, toleranceMs = 0L)
        assertTrue(env.calls.isEmpty(), "unconfigured providers must never run: ${env.calls}")
        assertTrue(tick.ran.isEmpty())
        assertNull(tick.nextWakeMs)

        AutoSyncRunner(env).runManual()
        assertTrue(env.calls.isEmpty(), "manual sync must also skip unconfigured Garmin/Withings: ${env.calls}")
    }

    @Test
    fun `withings-only user never triggers garmin import or keepalive`() {
        val env = FakeEnv().apply {
            hasGarmin = false
            hasWithings = true
            hasSession = true
        }
        val tick = AutoSyncRunner(env).tick(processStartMs = null, toleranceMs = 0L)
        assertEquals(listOf("withingsKeepalive", "import:WITHINGS"), env.calls)
        assertEquals(setOf(SyncTask.WITHINGS_KEEPALIVE, SyncTask.WITHINGS_IMPORT), tick.ran)
        assertNull(env.last[SyncProvider.GARMIN])

        env.calls.clear()
        AutoSyncRunner(env).runManual()
        assertEquals(listOf("import:WITHINGS", "withingsKeepalive"), env.calls)
    }
}
