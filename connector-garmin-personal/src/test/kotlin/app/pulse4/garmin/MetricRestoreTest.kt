package app.pulse4.garmin

import app.pulse4.domain.MetricType
import app.pulse4.provider.AreaResult
import app.pulse4.provider.AreaState
import app.pulse4.provider.AreaStateStore
import app.pulse4.provider.HttpGateway
import app.pulse4.provider.HttpResponse
import app.pulse4.provider.PARTIAL_PREFIX
import app.pulse4.provider.ProviderError
import app.pulse4.provider.SyncArea
import app.pulse4.provider.SyncEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Obnova metrik 0.1.x: izolace chyb po metrikách, záloha mezi endpointy, pravidlo prázdného okna a kurzor.
 * Odpovědi jsou syntetické (viz docs/samples); skutečná odpověď Garminu v tomto prostředí k dispozici není.
 */
class MetricRestoreTest {
    private val cfg = GarminEndpointsConfig.parse(java.io.File("../app/src/main/assets/garmin_endpoints.json").readText())

    /** 2025-01-16T12:00:00Z */
    private val now = 1_737_028_800_000L
    private val day = 24L * 3600 * 1000

    private class Routing(val handler: (String) -> HttpResponse) : HttpGateway {
        val gets = mutableListOf<String>()
        override fun get(url: String, headers: Map<String, String>): HttpResponse { gets += url; return handler(url) }
        override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>) =
            HttpResponse(200, emptyMap(), "tok")
    }

    private class States : AreaStateStore {
        val m = HashMap<SyncArea, AreaState>()
        override fun get(area: SyncArea) = m[area] ?: AreaState()
        override fun put(area: SyncArea, state: AreaState) { m[area] = state }
    }

    private fun ok(body: String) = HttpResponse(200, emptyMap(), body)
    private fun empty() = HttpResponse(200, emptyMap(), "")
    private fun http(code: Int) = HttpResponse(code, emptyMap(), "")

    private val spo2Body = """{"calendarDate":"2025-01-16","startTimestampGMT":"2025-01-15T23:00:00.0","averageSpO2":96.0}"""
    private val rhrBody = """{"calendarDate":"2025-01-16","startTimestampGMT":"2025-01-15T23:00:00.0","endTimestampGMT":"2025-01-16T11:00:00.0","restingHeartRate":54}"""
    private fun dailySummaries(rhr: Int, steps: Int, stress: Int) = """
        {"data":[{"calendarDate":"2025-01-16",
          "wellnessChronology":{"startTimestampGmt":"2025-01-15T23:00:00.0","endTimestampGmt":"2025-01-16T11:00:00.0"},
          "heartRate":{"restingValue":$rhr},"movement":{"steps":{"value":$steps}},"stress":{"avgLevel":$stress},
          "bodyBattery":{"latestValue":40}}]}
    """.trimIndent()

    private fun fetcher(h: HttpGateway, latest: (MetricType) -> Long? = { null }, initialDays: Int = 2) =
        GarminPersonalFetcher(cfg, h, { "KEY" }, "acc", "Europe/Prague", { now }, initialDays = initialDays, latestObservationMs = latest)

    // ---- izolace po metrikách ----

    @Test fun `failing resting heart rate does not drop SPO2 and is reported as a per-metric warning`() {
        val h = Routing { url ->
            when {
                url.contains("dailyHeartRate") -> http(403)
                url.contains("dailysummaries") -> http(403)
                url.contains("spo2acclimation") -> ok(spo2Body)
                else -> empty()
            }
        }
        val batch = fetcher(h).fetch(SyncArea.HEART_RATE, null)
        assertEquals(listOf(MetricType.SPO2), batch.items.map { it.type }.distinct())
        assertEquals(listOf("RESTING_HEART_RATE=Forbidden"), batch.warnings)
        assertEquals(now, batch.nextCursorMs, "403 se nezacyklí: kurzor se posune")
    }

    @Test fun `transient 5xx on one metric holds the cursor so the window is fetched again, other metrics are still saved`() {
        val h = Routing { url ->
            when {
                url.contains("dailyHeartRate") -> http(503)
                url.contains("dailysummaries") -> http(503)
                url.contains("spo2acclimation") -> ok(spo2Body)
                else -> empty()
            }
        }
        val batch = fetcher(h, initialDays = 2).fetch(SyncArea.HEART_RATE, null)
        assertEquals(MetricType.SPO2, batch.items.single().type)
        assertEquals(listOf("RESTING_HEART_RATE=Server"), batch.warnings)
        assertEquals(now - 2 * day, batch.nextCursorMs, "kurzor stojí na začátku okna")
    }

    @Test fun `engine saves the partial batch and records the per-metric error in lastError`() {
        val h = Routing { url ->
            when {
                url.contains("dailyHeartRate") || url.contains("dailysummaries") -> http(403)
                url.contains("spo2acclimation") -> ok(spo2Body)
                else -> empty()
            }
        }
        val states = States()
        val saved = mutableListOf<Int>()
        val r = SyncEngine(fetcher(h), { _, b -> saved += b.items.size }, states).runArea(SyncArea.HEART_RATE, now)
        assertIs<AreaResult.Success>(r)
        assertTrue(saved.single() > 0)
        assertEquals(PARTIAL_PREFIX + "RESTING_HEART_RATE=Forbidden", states.get(SyncArea.HEART_RATE).lastError)
        assertEquals(now, states.get(SyncArea.HEART_RATE).lastSuccessMs)
    }

    @Test fun `when every metric of the area fails the first error is thrown`() {
        val h = Routing { http(403) }
        assertIs<ProviderError.Forbidden>(assertFailsWith<ProviderError> { fetcher(h).fetch(SyncArea.HEART_RATE, null) })
    }

    @Test fun `area-wide errors such as 401 or 429 are not isolated`() {
        assertIs<ProviderError.Unauthorized>(assertFailsWith<ProviderError> { fetcher(Routing { http(401) }).fetch(SyncArea.HEART_RATE, null) })
        assertIs<ProviderError.RateLimited>(assertFailsWith<ProviderError> { fetcher(Routing { http(429) }).fetch(SyncArea.STEPS_STRESS, null) })
    }

    // ---- záloha ----

    @Test fun `primary result is used and the fallback endpoint is never called`() {
        val h = Routing { url ->
            when {
                url.contains("dailyHeartRate") -> ok(rhrBody)
                url.contains("spo2acclimation") -> ok(spo2Body)
                else -> empty()
            }
        }
        val batch = fetcher(h, initialDays = 0).fetch(SyncArea.HEART_RATE, null)
        assertEquals(54.0, batch.items.first { it.type == MetricType.RESTING_HEART_RATE }.value)
        assertTrue(h.gets.none { it.contains("dailysummaries") })
        assertTrue(batch.warnings.isEmpty())
    }

    @Test fun `empty or 404 primary falls back to dailysummaries for steps and stress with one shared call`() {
        val h = Routing { url ->
            when {
                url.contains("dailysummaries") -> ok(dailySummaries(rhr = 57, steps = 9100, stress = 28))
                else -> empty() // smartHttp mění 404/204 na 200 s prázdným tělem
            }
        }
        val batch = fetcher(h, initialDays = 0).fetch(SyncArea.STEPS_STRESS, null)
        assertEquals(9100.0, batch.items.single { it.type == MetricType.STEPS }.value)
        assertEquals(28.0, batch.items.single { it.type == MetricType.STRESS }.value)
        assertEquals(1, h.gets.count { it.contains("dailysummaries") }, "stejná URL se stáhne jednou")
    }

    @Test fun `primary error also triggers the fallback and the metric is then healthy`() {
        val h = Routing { url ->
            when {
                url.contains("dailyHeartRate") -> http(403)
                url.contains("dailysummaries") -> ok(dailySummaries(rhr = 57, steps = 1, stress = 1))
                url.contains("spo2acclimation") -> ok(spo2Body)
                else -> empty()
            }
        }
        val batch = fetcher(h, initialDays = 0).fetch(SyncArea.HEART_RATE, null)
        assertEquals(57.0, batch.items.first { it.type == MetricType.RESTING_HEART_RATE }.value)
        assertTrue(batch.warnings.isEmpty())
    }

    @Test fun `old path and fallback give the same id so a later fallback run overwrites instead of duplicating`() {
        val viaOld = fetcher(Routing { url -> if (url.contains("stats/steps")) ok("""[{"calendarDate":"2025-01-16","totalSteps":4000}]""") else empty() })
            .fetch(SyncArea.STEPS_STRESS, null).items.single { it.type == MetricType.STEPS }
        val viaFallback = fetcher(Routing { url -> if (url.contains("dailysummaries")) ok(dailySummaries(60, 6500, 20)) else empty() })
            .fetch(SyncArea.STEPS_STRESS, null).items.single { it.type == MetricType.STEPS }
        assertEquals(viaOld.id, viaFallback.id)
    }

    // ---- pravidlo prázdného okna ----

    private fun allEmpty() = Routing { empty() }

    @Test fun `empty window for a metric that never had data is not flagged`() {
        val batch = fetcher(allEmpty(), latest = { null }).fetch(SyncArea.STEPS_STRESS, null)
        assertTrue(batch.warnings.isEmpty())
        assertTrue(batch.items.isEmpty())
        assertEquals(now, batch.nextCursorMs)
    }

    @Test fun `empty window with fresh history (within 7 days) is a normal empty day, not an error`() {
        val batch = fetcher(allEmpty(), latest = { now - 3 * day }).fetch(SyncArea.STEPS_STRESS, null)
        assertTrue(batch.warnings.isEmpty())
    }

    @Test fun `empty window although the metric had data more than 7 days ago is flagged as EmptyResponse`() {
        val batch = fetcher(allEmpty(), latest = { m -> if (m == MetricType.STEPS) now - 10 * day else null }).fetch(SyncArea.STEPS_STRESS, null)
        assertEquals(listOf("STEPS=${GarminPersonalFetcher.EMPTY_RESPONSE}"), batch.warnings)
    }

    @Test fun `EmptyResponse becomes a visible lastError but the area is still a success and the cursor advances`() {
        val states = States()
        val f = fetcher(allEmpty(), latest = { now - 30 * day })
        val r = SyncEngine(f, { _, _ -> }, states).runArea(SyncArea.STEPS_STRESS, now)
        assertIs<AreaResult.Success>(r)
        val st = states.get(SyncArea.STEPS_STRESS)
        assertEquals("${PARTIAL_PREFIX}STEPS=EmptyResponse,STRESS=EmptyResponse", st.lastError)
        assertEquals(now, st.cursorMs)
        // další běh s daty chybu smaže
        val ok = fetcher(Routing { url -> if (url.contains("dailysummaries")) ok(dailySummaries(60, 5000, 20)) else empty() }, latest = { now - 30 * day })
        SyncEngine(ok, { _, _ -> }, states).runArea(SyncArea.STEPS_STRESS, now + 1)
        assertNull(states.get(SyncArea.STEPS_STRESS).lastError)
    }

    @Test fun `maxRangeDays=1 endpoints always include today even when previous cursor was later in the day`() {
        // Předchozí sync proběhl 2025-01-15 ve 22:00 CET; nyní je 2025-01-16 v 08:00 CET.
        // S překryvem 1 den je `since` 2025-01-14 22:00 CET – denní endpointy (maxRangeDays=1) musí přesto stáhnout i 2025-01-16.
        val zone = java.time.ZoneId.of("Europe/Prague")
        val morningNow = java.time.ZonedDateTime.of(2025, 1, 16, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val prevEveningSince = java.time.ZonedDateTime.of(2025, 1, 14, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        val h = Routing { url ->
            when {
                url.contains("dailyHeartRate?date=2025-01-16") -> ok(rhrBody)
                url.contains("spo2acclimation/2025-01-16") -> ok(spo2Body)
                else -> empty()
            }
        }
        val f = GarminPersonalFetcher(cfg, h, { "KEY" }, "acc", "Europe/Prague", { morningNow }, initialDays = 2)
        val batch = f.fetch(SyncArea.HEART_RATE, prevEveningSince)
        assertTrue(h.gets.any { it.contains("dailyHeartRate?date=2025-01-16") }, "musí dotázat dnešek: ${h.gets}")
        assertTrue(h.gets.any { it.contains("spo2acclimation/2025-01-16") }, "SpO2 musí dotázat dnešek: ${h.gets}")
        assertEquals(54.0, batch.items.single { it.type == MetricType.RESTING_HEART_RATE }.value)
    }

    @Test fun `primary returning only yesterday falls back to dailysummaries to fill missing today`() {
        val h = Routing { url ->
            when {
                url.contains("stats/steps") -> ok("""[{"calendarDate":"2025-01-15","totalSteps":7200}]""")
                url.contains("stats/stress") -> ok("""[{"calendarDate":"2025-01-15","values":{"overallStressLevel":30}}]""")
                url.contains("dailysummaries") -> ok(dailySummaries(rhr = 55, steps = 4321, stress = 22))
                else -> empty()
            }
        }
        val batch = fetcher(h, initialDays = 1).fetch(SyncArea.STEPS_STRESS, null)
        val stepsByDate = batch.items.filter { it.type == MetricType.STEPS }.associate { it.localDate to it.value }
        assertEquals(7200.0, stepsByDate["2025-01-15"])
        assertEquals(4321.0, stepsByDate["2025-01-16"])
    }
}
