package app.pulse4.garmin

import app.pulse4.domain.MetricType
import app.pulse4.provider.AreaResult
import app.pulse4.provider.HttpGateway
import app.pulse4.provider.HttpResponse
import app.pulse4.provider.AreaState
import app.pulse4.provider.AreaStateStore
import app.pulse4.provider.RetryAction
import app.pulse4.provider.ProviderError
import app.pulse4.provider.SyncArea
import app.pulse4.provider.SyncEngine
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SPEC = """
{
  "baseUrl": "https://api.example.invalid/personal/",
  "token": {"url": "https://api.example.invalid/token", "form": {"key": "{apiKey}"}, "verified": true},
  "endpoints": [
    {"area": "SLEEP", "metric": "SLEEP_DURATION", "path": "/sleep?from={from}&to={to}", "maxRangeDays": 2,
     "itemsPath": "items", "timePath": "start", "endTimePath": "end", "valuePath": "seconds", "timeFormat": "EPOCH_S",
     "localDatePath": "day", "externalIdPath": "id", "verified": true},
    {"area": "HRV", "metric": "HRV", "path": "/hrv", "itemsPath": "", "timePath": "t", "valuePath": "v", "verified": false}
  ]
}
"""

private class FakeHttp(val responses: MutableList<() -> HttpResponse>) : HttpGateway {
    val gets = mutableListOf<Pair<String, Map<String, String>>>()
    val posts = mutableListOf<Map<String, String>>()
    override fun get(url: String, headers: Map<String, String>): HttpResponse { gets += url to headers; return responses.removeFirst()() }
    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
        posts += form; return HttpResponse(200, emptyMap(), """{"access_token":"tok${posts.size}","expires_in":86400}""")
    }
}

private fun ok(body: String) = { HttpResponse(200, emptyMap(), body) }
private const val DAY = 24L * 3600 * 1000

class GarminTest {
    private val cfg = GarminEndpointsConfig.parse(SPEC)
    private fun fetcher(http: HttpGateway, key: String? = "KEY", now: Long = 10 * DAY) =
        GarminPersonalFetcher(cfg, http, { key }, "acc", "Europe/Prague", { now }, initialDays = 3)

    @Test fun `maps items, skips incomplete ones without inventing zeros`() {
        val body = """{"items":[
            {"id":"s1","start":1000,"end":30000,"seconds":27000,"day":"2026-10-20"},
            {"id":"s2","start":2000,"seconds":null},
            {"id":"s3","seconds":5},
            {"id":"s4","start":3000,"end":2000,"seconds":100}]}"""
        val r = ResponseMapper.map(cfg.endpoints[0], body, "acc", "Europe/Prague")
        assertEquals(2, r.skipped)
        assertEquals(2, r.items.size)
        val a = r.items[0]
        assertEquals(MetricType.SLEEP_DURATION, a.type); assertEquals(1_000_000L, a.startUtcMs); assertEquals(30_000_000L, a.endUtcMs)
        assertEquals("2026-10-20", a.localDate); assertEquals("s1", a.externalId); assertEquals(27000.0, a.value)
        assertNull(r.items[1].endUtcMs, "konec před začátkem se zahodí, ne přepíše")
    }

    @Test fun `scale converts to internal units and ids are deterministic`() {
        val spec = cfg.endpoints[0].copy(scale = 60.0, externalIdPath = null)
        val body = """{"items":[{"start":5,"seconds":10}]}"""
        val a = ResponseMapper.map(spec, body, "acc", "UTC").items.single()
        val b = ResponseMapper.map(spec, body, "acc", "UTC").items.single()
        assertEquals(600.0, a.value); assertEquals(a.id, b.id)
    }

    @Test fun `range is split by maxRangeDays and the first call exchanges the key for a token`() {
        val http = FakeHttp(MutableList(5) { ok("""{"items":[]}""") })
        val b = fetcher(http).fetch(SyncArea.SLEEP, null) // 3 dny zpět, rozsah max 2 dny
        assertEquals(1, http.posts.size); assertEquals("KEY", http.posts[0]["key"])
        assertTrue(http.gets.size in 2..3, "gets=${http.gets.size}")
        assertTrue(http.gets.all { it.second["Authorization"] == "Bearer tok1" })
        assertEquals(10 * DAY, b.nextCursorMs)
        assertTrue(http.gets[0].first.startsWith("https://api.example.invalid/personal/sleep?from=1970-01-0"))
    }

    @Test fun `raw responses are handed over before mapping so a mapping bug cannot lose the original`() {
        val raws = mutableListOf<String>()
        val http = FakeHttp(MutableList(5) { ok("""{"items":[{"unexpected":"shape"}]}""") })
        val f = GarminPersonalFetcher(cfg, http, { "KEY" }, "acc", "UTC", { 10 * DAY }, initialDays = 1, onRawResponse = { _, b -> raws += b })
        val b = f.fetch(SyncArea.SLEEP, null)
        assertTrue(raws.isNotEmpty() && raws.all { it.contains("unexpected") })
        assertEquals(0, b.items.size); assertTrue(f.skippedItems > 0, "chyba mapování se nesmí ztratit potichu")
    }

    @Test fun `unverified endpoints are never called`() {
        val http = FakeHttp(mutableListOf())
        assertFailsWith<ProviderError.NotConfigured> { fetcher(http).fetch(SyncArea.HRV, null) }
        assertEquals(0, http.gets.size)
        assertEquals(0, fetcher(http).fetch(SyncArea.WEIGHT, null).items.size)
    }

    @Test fun `http statuses map to provider errors`() {
        fun err(resp: HttpResponse): ProviderError = assertFailsWith<ProviderError> {
            fetcher(FakeHttp(mutableListOf({ resp }))).fetch(SyncArea.SLEEP, null)
        }
        assertIs<ProviderError.Unauthorized>(err(HttpResponse(401, emptyMap(), "")))
        assertIs<ProviderError.Forbidden>(err(HttpResponse(403, emptyMap(), "")))
        assertEquals(120L, assertIs<ProviderError.RateLimited>(err(HttpResponse(429, mapOf("retry-after" to "120"), ""))).retryAfterS)
        assertNull(assertIs<ProviderError.RateLimited>(err(HttpResponse(429, mapOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT"), ""))).retryAfterS)
        assertEquals(503, assertIs<ProviderError.Server>(err(HttpResponse(503, emptyMap(), ""))).code)
        val net = FakeHttp(mutableListOf({ throw IOException("reset") }))
        assertIs<ProviderError.Network>(assertFailsWith<ProviderError> { fetcher(net).fetch(SyncArea.SLEEP, null) })
    }

    @Test fun `missing api key means a new key is required`() {
        assertIs<ProviderError.Unauthorized>(assertFailsWith<ProviderError> { fetcher(FakeHttp(mutableListOf()), key = null).fetch(SyncArea.SLEEP, null) })
    }

    // --- spolupráce s engine: 401 → obnova tokenu → úspěch, a posun kurzoru až po uložení ---
    private class States : AreaStateStore {
        val m = HashMap<SyncArea, AreaState>()
        override fun get(area: SyncArea) = m[area] ?: AreaState()
        override fun put(area: SyncArea, state: AreaState) { m[area] = state }
    }

    @Test fun `engine refreshes the token once on 401 and then stores the data`() {
        val body = """{"items":[{"id":"s1","start":1000,"seconds":100}]}"""
        val http = FakeHttp(mutableListOf({ HttpResponse(401, emptyMap(), "") }, ok(body), ok(body), ok(body)))
        val saved = mutableListOf<Int>()
        val st = States()
        val r = SyncEngine(fetcher(http), { _, b -> saved += b.items.size }, st).runArea(SyncArea.SLEEP, 5)
        assertIs<AreaResult.Success>(r)
        assertEquals(2, http.posts.size, "první výměna + jedna obnova po 401")
        assertTrue(saved.single() >= 1)
        assertEquals(10 * DAY, st.get(SyncArea.SLEEP).cursorMs)
    }

    @Test fun `engine defers on 429 using Retry-After and does not touch the cursor`() {
        val http = FakeHttp(mutableListOf({ HttpResponse(429, mapOf("Retry-After" to "90"), "") }))
        val st = States()
        val r = SyncEngine(fetcher(http), { _, _ -> }, st).runArea(SyncArea.SLEEP, 1000)
        assertEquals(AreaResult.Deferred(91_000), r)
        assertNull(st.get(SyncArea.SLEEP).cursorMs)
    }

    @Test fun `unconfigured endpoint is surfaced as a user-visible state not as a fake server error`() {
        val unverified = GarminEndpointsConfig.parse(SPEC.replace("\"area\": \"SLEEP\"", "\"area\": \"SLEEP\"").replace("\"verified\": true},\n    {\"area\": \"HRV\"", "\"verified\": false},\n    {\"area\": \"HRV\""))
        val http = FakeHttp(mutableListOf())
        val f = GarminPersonalFetcher(unverified, http, { "KEY" }, "acc", "UTC", { 10 * DAY })
        val st = States()
        assertEquals(AreaResult.NeedsUser(RetryAction.ShowNotConfigured), SyncEngine(f, { _, _ -> }, st).runArea(SyncArea.SLEEP, 1))
        assertEquals(0, http.gets.size); assertEquals("NotConfigured", st.get(SyncArea.SLEEP).lastError)
    }

    @Test fun `forbidden shows state without retry loop`() {
        val http = FakeHttp(mutableListOf({ HttpResponse(403, emptyMap(), "") }))
        assertEquals(AreaResult.NeedsUser(RetryAction.ShowForbidden), SyncEngine(fetcher(http), { _, _ -> }, States()).runArea(SyncArea.SLEEP, 1))
        assertEquals(1, http.gets.size)
    }

    @Test fun `blank body returns empty MapResult without throwing`() {
        val r = ResponseMapper.map(cfg.endpoints[0], "", "acc", "UTC")
        assertEquals(0, r.items.size)
        assertEquals(0, r.skipped)
    }

    @Test fun `root JsonArray with itemsPath dollar unpacks elements`() {
        val spec = cfg.endpoints[0].copy(itemsPath = "$")
        val body = """[{"id":"a1","start":1000,"end":30000,"seconds":27000,"day":"2026-10-20"}]"""
        val r = ResponseMapper.map(spec, body, "acc", "Europe/Prague")
        assertEquals(1, r.items.size)
        assertEquals(27000.0, r.items[0].value)
    }

    @Test fun `multiple sleep metrics from same dailySleep endpoint share one HTTP call and produce distinct ids`() {
        val multiSpec = """
        {
          "baseUrl": "https://api.example.invalid/personal/",
          "token": {"url": "https://api.example.invalid/token", "form": {"key": "{apiKey}"}, "verified": true},
          "endpoints": [
            {"area": "SLEEP", "metric": "SLEEP_DURATION", "path": "/sleep-service/sleep/dailySleep?date={from}", "maxRangeDays": 1,
             "itemsPath": "$", "timePath": "sleepStartTimestampGMT", "endTimePath": "sleepEndTimestampGMT", "valuePath": "sleepTimeSeconds",
             "timeFormat": "EPOCH_MS", "localDatePath": "calendarDate", "externalIdPath": "id", "verified": true},
            {"area": "SLEEP", "metric": "SLEEP_SCORE", "path": "/sleep-service/sleep/dailySleep?date={from}", "maxRangeDays": 1,
             "itemsPath": "$", "timePath": "sleepEndTimestampGMT", "valuePath": "sleepScores.overall.value",
             "timeFormat": "EPOCH_MS", "localDatePath": "calendarDate", "externalIdPath": "id", "verified": true},
            {"area": "SLEEP", "metric": "SLEEP_DEEP", "path": "/sleep-service/sleep/dailySleep?date={from}", "maxRangeDays": 1,
             "itemsPath": "$", "timePath": "sleepStartTimestampGMT", "endTimePath": "sleepEndTimestampGMT", "valuePath": "deepSleepSeconds",
             "timeFormat": "EPOCH_MS", "localDatePath": "calendarDate", "externalIdPath": "id", "verified": true},
            {"area": "SLEEP", "metric": "SLEEP_AWAKE_COUNT", "path": "/sleep-service/sleep/dailySleep?date={from}", "maxRangeDays": 1,
             "itemsPath": "$", "timePath": "sleepEndTimestampGMT", "valuePath": "awakeCount",
             "timeFormat": "EPOCH_MS", "localDatePath": "calendarDate", "externalIdPath": "id", "verified": true}
          ]
        }
        """
        val dailyBody = """{
            "id": 1792971564000,
            "calendarDate": "1970-01-10",
            "sleepTimeSeconds": 25961,
            "deepSleepSeconds": 5400,
            "awakeCount": 3,
            "sleepStartTimestampGMT": 800000000,
            "sleepEndTimestampGMT": 826000000,
            "sleepScores": {"overall": {"value": 78}}
        }"""
        val http = FakeHttp(MutableList(2) { ok(dailyBody) })
        val f = GarminPersonalFetcher(GarminEndpointsConfig.parse(multiSpec), http, { "KEY" }, "acc", "UTC", { 10 * DAY }, initialDays = 0)
        val batch = f.fetch(SyncArea.SLEEP, 10 * DAY)
        assertEquals(1, http.gets.size, "Stejná URL se má stáhnout jen jednou")
        assertEquals(4, batch.items.size, "Žádná metrika nesmí být zahozena kolizí ID")
        val byType = batch.items.associateBy { it.type }
        assertEquals(25961.0, byType[MetricType.SLEEP_DURATION]?.value)
        assertEquals(78.0, byType[MetricType.SLEEP_SCORE]?.value)
        assertEquals(5400.0, byType[MetricType.SLEEP_DEEP]?.value)
        assertEquals(3.0, byType[MetricType.SLEEP_AWAKE_COUNT]?.value)
        assertEquals(4, batch.items.map { it.id }.toSet().size)
    }
}

class ShippedConfigTest {
    private val file = java.io.File("../app/src/main/assets/garmin_endpoints.json")

    @Test fun `N-15 shipped config verified endpoints all have existing sampleRef files`() {
        val cfg = GarminEndpointsConfig.parse(file.readText())
        assertTrue(cfg.endpoints.isNotEmpty())
        assertTrue(cfg.token.verified)
        val verified = cfg.endpoints.filter { it.verified }
        assertTrue(verified.isNotEmpty())
        for (ep in verified) {
            val ref = ep.sampleRef
            assertTrue(!ref.isNullOrBlank(), "Ověřený endpoint ${ep.metric} musí mít sampleRef")
            assertTrue(java.io.File("../$ref").exists(), "Vzorek $ref pro ${ep.metric} neexistuje")
        }
        assertTrue(cfg.endpoints.all { it.verified }, "Všechny dodané endpointy musí být ověřené proti vzorkům")
    }

    @Test fun `RESTING_HEART_RATE, STEPS and STRESS map cleanly from real dailysummaries sample and skip sentinels`() {
        val cfg = GarminEndpointsConfig.parse(file.readText())
        val sampleJson = java.io.File("../docs/samples/dailysummaries.json").readText()
        val rhr = ResponseMapper.map(cfg.endpoints.first { it.metric == MetricType.RESTING_HEART_RATE }, sampleJson, "acc", "Europe/Prague")
        assertEquals(0, rhr.skipped)
        assertEquals(listOf(60.0, 60.0, 60.0, 60.0), rhr.items.map { it.value })

        val steps = ResponseMapper.map(cfg.endpoints.first { it.metric == MetricType.STEPS }, sampleJson, "acc", "Europe/Prague")
        assertEquals(0, steps.skipped)
        assertEquals(listOf(8000.0, 5000.0, 7000.0, 4500.0), steps.items.map { it.value })

        val stressSpec = cfg.endpoints.first { it.metric == MetricType.STRESS }
        val stress = ResponseMapper.map(stressSpec, sampleJson, "acc", "Europe/Prague")
        assertEquals(0, stress.skipped)
        assertEquals(listOf(25.0, 25.0, 25.0, 25.0), stress.items.map { it.value })

        val sentinelJson = """
            {
              "data": [
                {
                  "calendarDate": "2026-11-18",
                  "wellnessChronology": {
                    "startTimestampGmt": "2026-11-17T23:00:00.0",
                    "endTimestampGmt": "2026-11-18T10:15:00.0"
                  },
                  "heartRate": { "restingValue": 0 },
                  "movement": { "steps": { "value": -1 } },
                  "stress": { "avgLevel": -1 }
                }
              ]
            }
        """.trimIndent()
        assertEquals(1, ResponseMapper.map(cfg.endpoints.first { it.metric == MetricType.RESTING_HEART_RATE }, sentinelJson, "acc", "Europe/Prague").skipped)
        assertEquals(1, ResponseMapper.map(cfg.endpoints.first { it.metric == MetricType.STEPS }, sentinelJson, "acc", "Europe/Prague").skipped)
        assertEquals(1, ResponseMapper.map(stressSpec, sentinelJson, "acc", "Europe/Prague").skipped)
    }

    @Test fun `daily summary metrics keep stable ImportKey across shifting endTimestampGmt`() {
        val cfg = GarminEndpointsConfig.parse(file.readText())
        fun body(endGmt: String, rhr: Int, steps: Int, stress: Int) = """
            {
              "data": [
                {
                  "calendarDate": "2026-11-18",
                  "wellnessChronology": {
                    "startTimestampGmt": "2026-11-17T23:00:00.0",
                    "endTimestampGmt": "$endGmt"
                  },
                  "heartRate": { "restingValue": $rhr },
                  "movement": { "steps": { "value": $steps } },
                  "stress": { "avgLevel": $stress }
                }
              ]
            }
        """.trimIndent()
        for (m in listOf(MetricType.RESTING_HEART_RATE, MetricType.STEPS, MetricType.STRESS)) {
            val spec = cfg.endpoints.first { it.metric == m }
            val first = ResponseMapper.map(spec, body("2026-11-18T10:15:00.0", 58, 4200, 21), "acc", "Europe/Prague").items.single()
            val second = ResponseMapper.map(spec, body("2026-11-18T18:45:00.0", 57, 8900, 26), "acc", "Europe/Prague").items.single()
            assertEquals(first.id, second.id, "${m.name} klíč nesmí záviset na posouvajícím se endTimestampGmt")
        }
    }

    @Test fun `N-9 body battery uses stable ImportKey by metric and calendarDate across shifting endTimestampGmt`() {
        val cfg = GarminEndpointsConfig.parse(file.readText())
        val bbSpec = cfg.endpoints.first { it.metric == MetricType.BODY_BATTERY }
        fun body(endGmt: String, value: Int) = """
            {
              "data": [
                {
                  "calendarDate": "2026-11-18",
                  "wellnessChronology": {
                    "startTimestampGmt": "2026-11-17T23:00:00.0",
                    "endTimestampGmt": "$endGmt"
                  },
                  "bodyBattery": {
                    "latestValue": $value
                  }
                }
              ]
            }
        """.trimIndent()
        val first = ResponseMapper.map(bbSpec, body("2026-11-18T10:15:00.0", 72), "acc", "Europe/Prague").items.single()
        val second = ResponseMapper.map(bbSpec, body("2026-11-18T18:45:00.0", 38), "acc", "Europe/Prague").items.single()
        assertEquals(first.id, second.id, "Body Battery klíč nesmí záviset na posouvajícím se endTimestampGmt")
        assertEquals(38.0, second.value)
    }

    @Test fun `body battery maps numeric latestValue from real dailysummaries sample`() {
        val cfg = GarminEndpointsConfig.parse(file.readText())
        val bbSpec = cfg.endpoints.first { it.metric == MetricType.BODY_BATTERY }
        val sampleJson = java.io.File("../docs/samples/dailysummaries.json").readText()
        val mapped = ResponseMapper.map(bbSpec, sampleJson, "acc", "Europe/Prague")
        assertEquals(0, mapped.skipped)
        assertEquals(4, mapped.items.size)
        assertEquals(listOf(15.0, 16.0, 30.0, 47.0), mapped.items.map { it.value })
        assertEquals(4, mapped.items.mapNotNull { it.localDate }.distinct().size)
        assertTrue(mapped.items.all { it.localDate?.matches(Regex("""\d{4}-\d{2}-\d{2}""")) == true })
    }

    @Test fun `body battery skips entries with missing or non-numeric latestValue`() {
        val cfg = GarminEndpointsConfig.parse(file.readText())
        val bbSpec = cfg.endpoints.first { it.metric == MetricType.BODY_BATTERY }
        val sampleJson = """
            {
              "data": [
                {
                  "calendarDate": "2026-10-28",
                  "wellnessChronology": {
                    "startTimestampGmt": "2026-10-27T23:00:00.0",
                    "endTimestampGmt": "2026-10-28T16:23:00.0"
                  },
                  "bodyBattery": {
                    "dynamicFeedbackEvent": {
                      "bodyBatteryLevel": "MODERATE"
                    }
                  }
                }
              ]
            }
        """.trimIndent()
        val mapped = ResponseMapper.map(bbSpec, sampleJson, "acc", "Europe/Prague")
        assertEquals(0, mapped.items.size)
        assertEquals(1, mapped.skipped)
    }
}
