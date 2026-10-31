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
}

class ShippedConfigTest {
    private val file = java.io.File("../app/src/main/assets/garmin_endpoints.json")

    @Test fun `shipped config matches the schema and all endpoints are verified`() {
        val cfg = GarminEndpointsConfig.parse(file.readText())
        assertTrue(cfg.endpoints.isNotEmpty())
        assertTrue(cfg.token.verified)
        assertTrue(cfg.endpoints.all { it.verified }, "Všechny endpointy jsou ověřeny proti oficiálnímu API")
    }
}
