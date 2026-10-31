package app.pulse4.provider

import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Adapter(override val kind: AdapterKind, override val capabilities: Set<Operation>) : ProviderAdapter

private class MemStates : AreaStateStore {
    val m = HashMap<SyncArea, AreaState>()
    override fun get(area: SyncArea) = m[area] ?: AreaState()
    override fun put(area: SyncArea, state: AreaState) { m[area] = state }
}

private class ScriptedFetcher(val script: MutableList<() -> Batch>) : AreaFetcher {
    var refreshes = 0; var fetches = 0; val sinces = mutableListOf<Long?>()
    var refreshFails = false
    override fun fetch(area: SyncArea, sinceMs: Long?): Batch { fetches++; sinces += sinceMs; return script.removeFirst()() }
    override fun refreshSession() { refreshes++; if (refreshFails) throw ProviderError.Unauthorized() }
}

class ProviderTest {
    private val official = Adapter(AdapterKind.OFFICIAL, setOf(Operation.READ_SLEEP, Operation.LIST_ACTIVITIES, Operation.DOWNLOAD_ACTIVITY))
    private val extended = Adapter(AdapterKind.EXTENDED, setOf(Operation.READ_SLEEP, Operation.UPSERT_WORKOUT, Operation.SCHEDULE_WORKOUT, Operation.REQUEST_DEVICE_SYNC))

    private fun obs(t: Long) = MetricObservation("o$t", Provider.GARMIN_PERSONAL, "a", "$t", MetricType.SLEEP_DURATION, t, null, 1.0, "UTC")
    private fun batch(next: Long, vararg t: Long) = Batch(t.map(::obs), next)

    @Test fun `official source is preferred and extended is used only for gaps`() {
        var on = true
        val r = CapabilityRouter(official, extended) { on }
        assertEquals(Route.Use(official), r.route(Operation.READ_SLEEP))
        assertEquals(Route.Use(extended), r.route(Operation.UPSERT_WORKOUT))
        assertEquals(Route.Unsupported(Operation.READ_WEIGHT), r.route(Operation.READ_WEIGHT))
        on = false
        assertEquals(Route.NeedsExtended(Operation.UPSERT_WORKOUT), r.route(Operation.UPSERT_WORKOUT))
        assertEquals(Route.Use(official), r.route(Operation.READ_SLEEP), "oficiální čtení nesmí záviset na doplňku")
        assertEquals(setOf(Operation.UPSERT_WORKOUT, Operation.SCHEDULE_WORKOUT, Operation.REQUEST_DEVICE_SYNC), r.requiresExtended())
    }

    @Test fun `error policy follows the specification`() {
        val p = RetryPolicy(Random(3), baseMs = 1000)
        assertEquals(RetryAction.RefreshTokenOnce, p.decide(ProviderError.Unauthorized(), false, 1))
        assertEquals(RetryAction.NeedNewApiKey, p.decide(ProviderError.Unauthorized(), true, 2))
        assertEquals(RetryAction.ShowForbidden, p.decide(ProviderError.Forbidden(), false, 1))
        assertEquals(RetryAction.WaitAndRetry(120_000), p.decide(ProviderError.RateLimited(120), false, 1))
        assertEquals(RetryAction.PauseForMfa, p.decide(ProviderError.MfaRequired(), false, 1))
        val w1 = (p.decide(ProviderError.RateLimited(null), false, 1) as RetryAction.WaitAndRetry).delayMs
        val w4 = (p.decide(ProviderError.RateLimited(null), false, 4) as RetryAction.WaitAndRetry).delayMs
        assertTrue(w1 in 800..1200); assertTrue(w4 in 6400..9600, "w4=$w4")
    }

    @Test fun `cursor moves only after the batch is stored`() {
        val states = MemStates()
        val f = ScriptedFetcher(mutableListOf({ batch(1000, 1, 2) }, { batch(2000, 3) }))
        var fail = true
        val saved = mutableListOf<Batch>()
        val e = SyncEngine(f, { _, b -> if (fail) error("disk full"); saved += b }, states)
        runCatching { e.runArea(SyncArea.SLEEP, 10) }
        assertNull(states.get(SyncArea.SLEEP).cursorMs, "uložení selhalo → kurzor se nesmí posunout")
        fail = false
        assertIs<AreaResult.Success>(e.runArea(SyncArea.SLEEP, 20))
        assertEquals(2000, states.get(SyncArea.SLEEP).cursorMs)
    }

    @Test fun `overlap window re-reads recent days and cursor never moves backwards`() {
        val states = MemStates(); val day = 24L * 3600 * 1000
        val f = ScriptedFetcher(mutableListOf({ batch(10 * day, 1) }, { batch(9 * day, 2) }))
        val e = SyncEngine(f, { _, _ -> }, states)
        e.runArea(SyncArea.SLEEP, 1); e.runArea(SyncArea.SLEEP, 2)
        assertEquals(listOf(null, 7 * day), f.sinces) // překryv 3 dny
        assertEquals(10 * day, states.get(SyncArea.SLEEP).cursorMs)
    }

    @Test fun `401 refreshes once then succeeds, repeated 401 asks for a new key without looping`() {
        val s1 = MemStates()
        val f1 = ScriptedFetcher(mutableListOf({ throw ProviderError.Unauthorized() }, { batch(5, 1) }))
        assertIs<AreaResult.Success>(SyncEngine(f1, { _, _ -> }, s1).runArea(SyncArea.SLEEP, 1))
        assertEquals(1, f1.refreshes)

        val f2 = ScriptedFetcher(mutableListOf({ throw ProviderError.Unauthorized() }, { throw ProviderError.Unauthorized() }, { batch(5, 1) }))
        val r = SyncEngine(f2, { _, _ -> }, MemStates()).runArea(SyncArea.SLEEP, 1)
        assertEquals(AreaResult.NeedsUser(RetryAction.NeedNewApiKey), r)
        assertEquals(2, f2.fetches); assertEquals(1, f2.refreshes)

        val f3 = ScriptedFetcher(mutableListOf({ throw ProviderError.Unauthorized() })).also { it.refreshFails = true }
        assertEquals(AreaResult.NeedsUser(RetryAction.NeedNewApiKey), SyncEngine(f3, { _, _ -> }, MemStates()).runArea(SyncArea.SLEEP, 1))
    }

    @Test fun `403 shows state without login loop and 429 defers by Retry-After`() {
        val f = ScriptedFetcher(mutableListOf({ throw ProviderError.Forbidden() }))
        assertEquals(AreaResult.NeedsUser(RetryAction.ShowForbidden), SyncEngine(f, { _, _ -> }, MemStates()).runArea(SyncArea.SLEEP, 1))
        assertEquals(0, f.refreshes); assertEquals(1, f.fetches)

        val st = MemStates()
        val f2 = ScriptedFetcher(mutableListOf({ throw ProviderError.RateLimited(60) }))
        val e = SyncEngine(f2, { _, _ -> }, st)
        assertEquals(AreaResult.Deferred(61_000), e.runArea(SyncArea.SLEEP, 1000))
        assertEquals(AreaResult.Skipped, e.runArea(SyncArea.SLEEP, 30_000)) // dokud neuplyne Retry-After, nic nevolá
        assertEquals(1, f2.fetches)
    }

    @Test fun `a failing area never produces a green overall state`() {
        val now = 100_000L
        val ok = AreaState(cursorMs = 1, lastSuccessMs = now, lastAttemptMs = now)
        val all = SyncArea.entries.associateWith { ok }
        assertTrue(Freshness.overview(all, now).allFresh)
        val sleepFailed = all + (SyncArea.SLEEP to ok.copy(lastError = "Server"))
        val o = Freshness.overview(sleepFailed, now)
        assertFalse(o.allFresh); assertEquals(AreaStatus.ERROR, o.perArea[SyncArea.SLEEP]); assertEquals(AreaStatus.OK, o.perArea[SyncArea.HRV])
        assertEquals(AreaStatus.NEVER, Freshness.overview(emptyMap(), now).perArea[SyncArea.STEPS_STRESS])
        assertEquals(AreaStatus.STALE, Freshness.statusOf(SyncArea.HEART_RATE, ok, now + 7 * 3600_000L))
    }

    @Test fun `single flight prevents concurrent import of the same account`() {
        val e = SyncEngine(ScriptedFetcher(mutableListOf()), { _, _ -> }, MemStates())
        var inner: String? = "unset"
        val outer = e.singleFlight("acc") { inner = e.singleFlight("acc") { "x" }; "done" }
        assertEquals("done", outer); assertNull(inner)
        assertEquals("again", e.singleFlight("acc") { "again" })
    }
}
