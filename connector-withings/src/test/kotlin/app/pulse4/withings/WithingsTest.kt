package app.pulse4.withings

import app.pulse4.domain.MeasurementExclusions
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Quality
import app.pulse4.domain.TrendSelector
import app.pulse4.provider.AreaResult
import app.pulse4.provider.AreaState
import app.pulse4.provider.AreaStateStore
import app.pulse4.provider.HttpGateway
import app.pulse4.provider.HttpResponse
import app.pulse4.provider.ProviderError
import app.pulse4.provider.RetryAction
import app.pulse4.provider.SyncArea
import app.pulse4.provider.SyncEngine
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val REDIRECT = "https://pulse4.example.org/withings/callback"
private val CREDS = WithingsCredentials("cid", "SECRET")

private class MemVault : TokenVault {
    var tokens: WithingsTokens? = null
    var failNextSave = false
    var saves = 0
    override fun load() = tokens
    override fun save(t: WithingsTokens) { if (failNextSave) { failNextSave = false; throw IOException("pád před zápisem") }; saves++; tokens = t }
    override fun clear() { tokens = null }
}

private class MemPending : PendingAuthStore {
    var p: PendingAuth? = null
    override fun put(p: PendingAuth) { this.p = p }
    override fun get() = p
    override fun clear() { p = null }
}

/** Falešný Withings: jednorázové kódy, rotace refresh tokenu (starý platí do prvního použití nového access tokenu). */
private class FakeWithings(val clock: () -> Long) : HttpGateway {
    var validCodes = mutableSetOf("code-1")
    private val validRefresh = mutableSetOf<String>()
    private val staleRefresh = mutableSetOf<String>()
    private val validAccess = mutableSetOf<String>()
    private var seq = 0
    val refreshCalls = AtomicInteger()
    val requests = mutableListOf<Pair<String, Map<String, String>>>()
    var groups: List<String> = emptyList()
    var pages: List<String>? = null
    var rateLimited = false
    var rejectNextMeasureAuth = false
    var rejectAllMeasureAuth = false
    var lastupdateGroups: List<String> = emptyList()
    var invalidateRefreshStatus: Int? = null

    private fun tokenBody(user: String = "u1"): String {
        seq++
        val at = "at$seq"; val rt = "rt$seq"
        validAccess += at; validRefresh += rt
        return """{"status":0,"body":{"userid":"$user","access_token":"$at","refresh_token":"$rt","scope":"user.metrics","expires_in":10800,"token_type":"Bearer"}}"""
    }

    @Synchronized override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
        requests += url to form
        return when {
            url.endsWith("/v2/oauth2") -> when (form["action"]) {
                "getnonce" -> HttpResponse(200, emptyMap(), """{"status":0,"body":{"nonce":"n0nce"}}""")
                "requesttoken" -> when (form["grant_type"]) {
                    "authorization_code" -> if (form["code"] in validCodes) { validCodes -= form["code"]!!; HttpResponse(200, emptyMap(), tokenBody()) }
                        else HttpResponse(200, emptyMap(), """{"status":293,"error":"invalid_grant"}""")
                    "refresh_token" -> { refreshCalls.incrementAndGet()
                        invalidateRefreshStatus?.let { return HttpResponse(200, emptyMap(), """{"status":$it,"error":"invalid_grant"}""") }
                        val rt = form["refresh_token"]
                        if (rt in validRefresh) { staleRefresh += rt!!; HttpResponse(200, emptyMap(), tokenBody()) }
                        else HttpResponse(200, emptyMap(), """{"status":293,"error":"invalid_grant"}""") }
                    else -> HttpResponse(400, emptyMap(), "")
                }
                else -> HttpResponse(400, emptyMap(), "")
            }
            url.endsWith("/measure") -> {
                if (rateLimited) return HttpResponse(200, emptyMap(), """{"status":601,"error":"too many requests"}""")
                val token = headers["Authorization"]?.removePrefix("Bearer ")
                if (rejectAllMeasureAuth) return HttpResponse(200, emptyMap(), """{"status":401}""")
                if (rejectNextMeasureAuth) { rejectNextMeasureAuth = false; return HttpResponse(200, emptyMap(), """{"status":401}""") }
                if (token !in validAccess) return HttpResponse(200, emptyMap(), """{"status":401}""")
                // použití nového access tokenu zneplatní starý refresh token
                validRefresh.removeAll(staleRefresh); staleRefresh.clear()
                val body = if (form.containsKey("lastupdate")) lastupdateGroups else if (pages != null) listOf(pages!![(form["offset"]?.toInt() ?: 0)]) else groups
                val more = pages != null && (form["offset"]?.toInt() ?: 0) < pages!!.size - 1
                val offset = (form["offset"]?.toInt() ?: 0) + 1
                HttpResponse(200, emptyMap(), """{"status":0,"body":{"updatetime":1,"timezone":"Europe/Prague","measuregrps":[${body.joinToString(",")}],"more":${if (more) 1 else 0},"offset":${if (more) offset else 0}}}""")
            }
            else -> HttpResponse(404, emptyMap(), "")
        }
    }

    override fun get(url: String, headers: Map<String, String>) = HttpResponse(405, emptyMap(), "")
    fun expireAll() { validAccess.clear() }
}

private fun grp(id: Long, date: Long, kg: Double, attrib: Int = 0, fat: Double? = null, category: Int = 1, tz: String = "Europe/Prague"): String {
    val w = Math.round(kg * 1000)
    val ms = buildString { append("""{"value":$w,"type":1,"unit":-3}"""); if (fat != null) append(""",{"value":${Math.round(fat * 1000)},"type":6,"unit":-3}""") }
    return """{"grpid":$id,"attrib":$attrib,"date":$date,"created":$date,"modified":$date,"category":$category,"deviceid":"dev1","timezone":"$tz","measures":[$ms]}"""
}

private const val DAY_MS = 24L * 3600 * 1000

class WithingsTest {
    private var now = 1_800_000_000_000L
    private val clock = { now }
    private val cfg = WithingsConfig(redirectUri = REDIRECT)

    private fun rig(config: WithingsConfig = cfg) = object {
        val server = FakeWithings(clock)
        val vault = MemVault()
        val pending = MemPending()
        val session = WithingsSession(config, { CREDS }, server, vault, clock)
        val oauth = WithingsOAuth(config, { CREDS }, server, vault, pending, SecureRandom())
        val api = WithingsApi(config, session, server)
        fun connect() {
            val url = oauth.begin(now)
            val state = Regex("state=([^&]+)").find(url)!!.groupValues[1]
            assertIs<AuthResult.Connected>(oauth.complete("$REDIRECT?code=code-1&state=$state", now, now, session))
        }
    }

    // ---------------- OAuth ----------------
    @Test fun `begin builds the authorize url with random single use state and encoded redirect`() {
        val r = rig()
        val u1 = r.oauth.begin(now); val s1 = r.pending.get()!!.state
        val u2 = r.oauth.begin(now); val s2 = r.pending.get()!!.state
        assertNotEquals(s1, s2); assertTrue(s1.length >= 40)
        assertTrue(u1.startsWith("https://account.withings.com/oauth2_user/authorize2?response_type=code&client_id=cid"))
        assertTrue(u2.contains("redirect_uri=https%3A%2F%2Fpulse4.example.org%2Fwithings%2Fcallback") && u2.contains("scope=user.metrics") && u2.contains("state=$s2"))
        assertFalse(u2.contains("SECRET"))
    }

    @Test fun `successful callback exchanges the code immediately and stores tokens, state is consumed`() {
        val r = rig(); r.connect()
        val t = assertNotNull(r.vault.tokens)
        assertEquals("u1", t.userId); assertEquals(now + 10_800_000, t.expiresAtMs)
        assertNull(r.pending.get())
        assertEquals("authorization_code", r.server.requests.single { it.second["grant_type"] != null }.second["grant_type"])
    }

    @Test fun `forged state is rejected without cancelling the legitimate pending login`() {
        val r = rig(); val url = r.oauth.begin(now)
        val good = Regex("state=([^&]+)").find(url)!!.groupValues[1]
        assertEquals(AuthResult.Failed(AuthFailure.STATE_MISMATCH), r.oauth.complete("$REDIRECT?code=code-1&state=forged", now, now, r.session))
        assertEquals(AuthResult.Failed(AuthFailure.STATE_MISMATCH), r.oauth.complete("$REDIRECT?code=code-1", now, now, r.session))
        assertNull(r.vault.tokens); assertTrue(r.server.requests.isEmpty(), "kód se při špatném state nikam neposílá")
        assertIs<AuthResult.Connected>(r.oauth.complete("$REDIRECT?code=code-1&state=$good", now, now, r.session))
    }

    @Test fun `state is single use`() {
        val r = rig(); val url = r.oauth.begin(now); val st = Regex("state=([^&]+)").find(url)!!.groupValues[1]
        r.oauth.complete("$REDIRECT?code=code-1&state=$st", now, now, r.session)
        r.server.validCodes += "code-2"
        assertEquals(AuthResult.Failed(AuthFailure.NO_PENDING), r.oauth.complete("$REDIRECT?code=code-2&state=$st", now, now, r.session))
    }

    @Test fun `wrong redirect, denial, missing code, expired pending and stale code are refused`() {
        fun attempt(build: (String) -> String, received: Long = now, at: Long = now, begin: Long = now): AuthResult {
            val r = rig(); val url = r.oauth.begin(begin); val st = Regex("state=([^&]+)").find(url)!!.groupValues[1]
            val res = r.oauth.complete(build(st), received, at, r.session)
            assertTrue(r.vault.tokens == null)
            return res
        }
        assertEquals(AuthResult.Failed(AuthFailure.WRONG_REDIRECT), attempt({ "https://evil.example/withings/callback?code=code-1&state=$it" }))
        assertEquals(AuthResult.Failed(AuthFailure.WRONG_REDIRECT), attempt({ "https://pulse4.example.org/other?code=code-1&state=$it" }))
        assertEquals(AuthResult.Failed(AuthFailure.DENIED_BY_USER), attempt({ "$REDIRECT?error=access_denied&state=$it" }))
        assertEquals(AuthResult.Failed(AuthFailure.MISSING_CODE), attempt({ "$REDIRECT?state=$it" }))
        assertEquals(AuthResult.Failed(AuthFailure.PENDING_EXPIRED), attempt({ "$REDIRECT?code=code-1&state=$it" }, at = now + 11 * 60_000))
        // 31 s od přijetí callbacku: kód už neplatí, výměna se ani nezkouší
        val r = rig(); val url = r.oauth.begin(now); val st = Regex("state=([^&]+)").find(url)!!.groupValues[1]
        assertEquals(AuthResult.Failed(AuthFailure.CODE_TOO_OLD), r.oauth.complete("$REDIRECT?code=code-1&state=$st", now, now + 31_000, r.session))
        assertTrue(r.server.requests.isEmpty())
    }

    @Test fun `a code the server rejects leaves no half connected state`() {
        val r = rig(); val url = r.oauth.begin(now); val st = Regex("state=([^&]+)").find(url)!!.groupValues[1]
        assertEquals(AuthResult.Failed(AuthFailure.EXCHANGE_REJECTED), r.oauth.complete("$REDIRECT?code=bad&state=$st", now, now, r.session))
        assertNull(r.vault.tokens); assertNull(r.pending.get()); assertFalse(r.session.isConnected())
    }

    @Test fun `network failure during exchange is reported and the user can start again`() {
        val r = rig()
        val flaky = object : HttpGateway by r.server { override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse = throw IOException("reset") }
        val oauth = WithingsOAuth(cfg, { CREDS }, flaky, r.vault, r.pending)
        val url = oauth.begin(now); val st = Regex("state=([^&]+)").find(url)!!.groupValues[1]
        assertEquals(AuthResult.Failed(AuthFailure.NETWORK), oauth.complete("$REDIRECT?code=code-1&state=$st", now, now, r.session))
        assertNull(r.vault.tokens)
    }

    @Test fun `redaction hides code and state and secrets never appear in toString`() {
        val u = "$REDIRECT?code=abc123&state=xyz&other=ok"
        val red = WithingsOAuth.redact(u)
        assertFalse(red.contains("abc123") || red.contains("xyz")); assertTrue(red.contains("other=ok"))
        assertFalse(CREDS.toString().contains("SECRET"))
        val t = WithingsTokens("u", "ACCESS", "REFRESH", "s", 1, 1)
        assertFalse(t.toString().contains("ACCESS") || t.toString().contains("REFRESH"))
    }

    @Test fun `signed mode gets a nonce and sends an HMAC instead of the client secret`() {
        val c = cfg.copy(useSignature = true); val r = rig(c)
        r.connect()
        val token = r.server.requests.last { it.second["action"] == "requesttoken" }.second
        assertFalse("client_secret" in token); assertEquals("n0nce", token["nonce"])
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("SECRET".toByteArray(), "HmacSHA256")) }
        assertEquals(mac.doFinal("requesttoken,cid,n0nce".toByteArray()).joinToString("") { "%02x".format(it) }, token["signature"])
        val nonceReq = r.server.requests.first { it.second["action"] == "getnonce" }.second
        assertEquals(mac.doFinal("getnonce,cid,${nonceReq["timestamp"]}".toByteArray()).joinToString("") { "%02x".format(it) }, nonceReq["signature"])
    }

    // ---------------- rotace tokenů ----------------
    @Test fun `fresh token is reused, expiring token is refreshed once and saved before use`() {
        val r = rig(); r.connect()
        val first = r.session.accessToken(); assertEquals(first, r.session.accessToken()); assertEquals(0, r.server.refreshCalls.get())
        now += 10_800_000 - 60_000 // zbývá minuta < odstup 2 min
        val second = r.session.accessToken()
        assertNotEquals(first, second); assertEquals(1, r.server.refreshCalls.get())
        assertEquals(second, r.vault.tokens!!.accessToken)
        assertEquals("u1", r.vault.tokens!!.userId, "userid se při obnově zachová")
    }

    @Test fun `concurrent refresh jobs perform exactly one refresh`() {
        val r = rig(); r.connect(); now += 10_800_000
        val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        val results = (1..8).map { pool.submit<String> { start.await(); r.session.accessToken() } }
        start.countDown()
        val tokens = results.map { it.get() }.toSet()
        pool.shutdown()
        assertEquals(1, r.server.refreshCalls.get()); assertEquals(1, tokens.size)
    }

    @Test fun `crash after the server rotated but before saving does not lose the session`() {
        val r = rig(); r.connect(); now += 10_800_000
        r.vault.failNextSave = true
        assertFailsWith<IOException> { r.session.accessToken() } // odpověď došla, uložení spadlo
        assertNotNull(r.vault.tokens); assertEquals("rt1", r.vault.tokens!!.refreshToken, "starý stav zůstal nedotčen")
        // starý refresh token na serveru ještě platí (nebyl použit nový access token) → další pokus uspěje
        val at = r.session.accessToken()
        assertEquals(2, r.server.refreshCalls.get()); assertEquals(at, r.vault.tokens!!.accessToken)
        r.server.groups = emptyList(); assertEquals(emptyList(), r.api.getMeasures(WithingsApi.Filter.DATE_WINDOW, 0, now))
    }

    @Test fun `revoked refresh token asks for a new authorization and keeps the stored state`() {
        val r = rig(); r.connect(); now += 10_800_000
        r.server.invalidateRefreshStatus = 293
        assertFailsWith<ProviderError.Unauthorized> { r.session.accessToken() }
        assertNotNull(r.vault.tokens)
    }

    @Test fun `missing developer credentials is a configuration state not a crash`() {
        val r = rig(); r.connect(); now += 10_800_000
        val s = WithingsSession(cfg, { null }, r.server, r.vault, clock)
        assertFailsWith<ProviderError.NotConfigured> { s.accessToken() }
        assertFailsWith<ProviderError.NotConfigured> { WithingsOAuth(cfg, { null }, r.server, r.vault, r.pending).begin(now) }
    }

    // ---------------- měření ----------------
    @Test fun `rejected token is refreshed once and the request retried, a second rejection surfaces`() {
        val r = rig(); r.connect(); r.server.groups = listOf(grp(1, now / 1000 - 100, 80.0))
        r.server.rejectNextMeasureAuth = true
        assertEquals(1, r.api.getMeasures(WithingsApi.Filter.DATE_WINDOW, 0, now).size)
        assertEquals(1, r.server.refreshCalls.get())
        // Server odmítá i čerstvý token: jedna obnova, jedno opakování, pak chyba – žádná smyčka.
        val before = r.server.refreshCalls.get()
        r.server.rejectAllMeasureAuth = true
        assertFailsWith<ProviderError.Unauthorized> { r.api.getMeasures(WithingsApi.Filter.DATE_WINDOW, 0, now) }
        assertEquals(before + 1, r.server.refreshCalls.get())
    }

    @Test fun `units attrib timezone and categories are mapped, implausible values are skipped not zeroed`() {
        val d = 1_790_000_000L
        val groups = WithingsApi.parse(Wire.json.parseToJsonElement("""{"measuregrps":[
            ${grp(10, d, 80.123, attrib = 0, fat = 21.4)}, ${grp(11, d + 60, 79.0, attrib = 1)}, ${grp(12, d + 120, 5.0)}, ${grp(13, d + 180, 70.0, category = 2)},
            ${grp(14, d + 240, 81.0, attrib = 99)}]}""").let { it as kotlinx.serialization.json.JsonObject })
        val m = WithingsMapper.map(groups, "u1", "UTC", cfg)
        assertEquals(1, m.skipped)
        val w = m.items.filter { it.type == MetricType.WEIGHT }
        assertEquals(listOf(80.123, 79.0, 81.0), w.map { it.value })
        assertEquals(Quality.OK, w[0].quality); assertEquals(Quality.SUSPECT, w[1].quality); assertEquals(Quality.SUSPECT, w[2].quality, "neznámý attrib je podezřelý")
        assertEquals(21.4, m.items.single { it.type == MetricType.BODY_FAT }.value, 1e-9)
        assertEquals("Europe/Prague", w[0].zoneId); assertEquals("withings:dev1", w[0].origin)
        assertEquals(m.items.map { it.id }, WithingsMapper.map(groups, "u1", "UTC", cfg).items.map { it.id }, "ID jsou deterministická")
        assertFalse(m.items.any { it.value == 0.0 })
    }

    @Test fun `paging follows more and offset`() {
        val r = rig(); r.connect()
        r.server.pages = listOf(grp(1, 1_790_000_000, 80.0), grp(2, 1_790_000_100, 81.0), grp(3, 1_790_000_200, 82.0))
        assertEquals(listOf(1L, 2L, 3L), r.api.getMeasures(WithingsApi.Filter.DATE_WINDOW, 0, now).map { it.grpId })
    }

    private class States : AreaStateStore {
        val m = HashMap<SyncArea, AreaState>()
        override fun get(area: SyncArea) = m[area] ?: AreaState()
        override fun put(area: SyncArea, state: AreaState) { m[area] = state }
    }

    private fun fetcher(r: Any, rig: Rig, existing: Set<String> = emptySet(), ex: MeasurementExclusions = MeasurementExclusions { false }) =
        WithingsFetcher(cfg, rig.api, rig.session, { rig.vault.tokens!!.userId }, "Europe/Prague", { _, _ -> existing }, ex, clock)
    private class Rig(val server: FakeWithings, val vault: MemVault, val session: WithingsSession, val api: WithingsApi)
    private fun rig2(): Rig { val r = rig(); r.connect(); return Rig(r.server, r.vault, r.session, r.api) }

    @Test fun `removed measurement becomes a tombstone, but an empty answer never deletes history`() {
        val rg = rig2(); val t = now / 1000
        rg.server.groups = listOf(grp(1, t - 3600, 80.0), grp(2, t - 7200, 81.0))
        val ids = WithingsMapper.map(WithingsApi.parse(Wire.json.parseToJsonElement("""{"measuregrps":[${rg.server.groups.joinToString(",")}]}""") as kotlinx.serialization.json.JsonObject), "u1", "Europe/Prague", cfg).items.map { it.id }
        val gone = "WITHINGS_API|u1|id:99:1"
        val b = fetcher(Unit, rg, existing = ids.toSet() + gone).fetch(SyncArea.WEIGHT, now - DAY_MS)
        assertEquals(listOf(gone), b.tombstones); assertEquals(2, b.items.size)
        rg.server.groups = emptyList()
        assertEquals(emptyList(), fetcher(Unit, rg, existing = setOf(gone)).fetch(SyncArea.WEIGHT, now - DAY_MS).tombstones)
    }

    @Test fun `lastupdate pass brings corrections of older measurements`() {
        val rg = rig2(); val t = now / 1000
        rg.server.groups = listOf(grp(1, t - 100, 80.0))
        rg.server.lastupdateGroups = listOf(grp(7, t - 20 * 86_400, 79.5))
        val b = fetcher(Unit, rg).fetch(SyncArea.WEIGHT, now - 14 * DAY_MS)
        assertEquals(setOf("1:1", "7:1"), b.items.map { it.externalId }.toSet())
        assertEquals(emptyList(), b.tombstones.filter { it.contains("7:1") })
    }

    @Test fun `first import has no lastupdate pass and reads the configured history`() {
        val rg = rig2(); rg.server.groups = listOf(grp(1, now / 1000 - 100, 80.0))
        fetcher(Unit, rg).fetch(SyncArea.WEIGHT, null)
        assertTrue(rg.server.requests.filter { it.first.endsWith("/measure") }.none { it.second.containsKey("lastupdate") })
        assertEquals(((now - 365 * DAY_MS) / 1000).toString(), rg.server.requests.last { it.first.endsWith("/measure") }.second["startdate"])
    }

    @Test fun `user exclusions survive re-import so a wrongly attributed weighing stays out`() {
        val rg = rig2(); val t = now / 1000
        rg.server.groups = listOf(grp(1, t - 100, 80.0), grp(2, t - 200, 112.0))
        val badId = WithingsMapper.map(WithingsApi.parse(Wire.json.parseToJsonElement("""{"measuregrps":[${grp(2, t - 200, 112.0)}]}""") as kotlinx.serialization.json.JsonObject), "u1", "Europe/Prague", cfg).items.single().id
        val b = fetcher(Unit, rg, ex = { it == badId }).fetch(SyncArea.WEIGHT, now - DAY_MS)
        assertEquals(setOf(badId), b.items.filter { it.deleted }.map { it.id }.toSet())
        assertEquals(1, TrendSelector.points(b.items, listOf(Provider.WITHINGS_API)).size)
    }

    @Test fun `engine defers on rate limit and asks for reauthorization when the refresh token is revoked`() {
        val rg = rig2(); rg.server.rateLimited = true
        val st = States()
        val eng = SyncEngine(fetcher(Unit, rg), { _, _ -> }, st)
        assertIs<AreaResult.Deferred>(eng.runArea(SyncArea.WEIGHT, now))
        assertNull(st.get(SyncArea.WEIGHT).cursorMs)

        val rg2 = rig2(); rg2.server.expireAll(); rg2.server.invalidateRefreshStatus = 293
        val r = SyncEngine(fetcher(Unit, rg2), { _, _ -> }, States()).runArea(SyncArea.WEIGHT, now)
        assertEquals(AreaResult.NeedsUser(RetryAction.NeedNewApiKey), r)
    }

    // ---------------- stejné vážení přes všechny cesty ----------------
    private fun garminWeight(kg: Double, t: Long) = MetricObservation("g$t", Provider.GARMIN_PERSONAL, "a", "g$t", MetricType.WEIGHT, t, null, kg, "Europe/Prague", "2026-10-20")

    @Test fun `the same weighing via api, health connect, csv and garmin is one trend point with all sources kept`() {
        val t = 1_790_000_000_000L
        val api = WithingsMapper.map(WithingsApi.parse(Wire.json.parseToJsonElement("""{"measuregrps":[${grp(5, t / 1000, 80.1)}]}""") as kotlinx.serialization.json.JsonObject), "u1", "Europe/Prague", cfg).items.single()
        val hc = HealthConnectMapper.map(HcRecord("hc-1", MetricType.WEIGHT, t + 30_000, null, 80.1, HealthConnectMapper.WITHINGS_PACKAGE, t), "Europe/Prague")!!
        val csvText = "Date,Weight (kg),Fat mass (kg)\n" + java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.of("Europe/Prague")).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + ",80.1,\n"
        val csv = WithingsCsv.parse(csvText.toByteArray(), "Europe/Prague").items.single()
        val garmin = garminWeight(80.1, t + 90_000)
        val all = listOf(garmin, csv, hc, api)
        val pref = listOf(Provider.WITHINGS_API, Provider.HEALTH_CONNECT, Provider.MANUAL, Provider.GARMIN_PERSONAL)
        val trend = TrendSelector.points(all, pref)
        assertEquals(1, trend.size); assertEquals(Provider.WITHINGS_API, trend.single().provider)
        assertEquals(4, app.pulse4.domain.DuplicateLinker.cluster(all).single().members.size, "všechny zdroje zůstávají dohledatelné")
        assertEquals(80.1, trend.single().value, 1e-9)
        // jiná osoba/jiné vážení se nesloučí
        assertEquals(2, TrendSelector.points(all + garminWeight(95.0, t + 60_000), pref).size)
    }

    @Test fun `suspect measurements stay out of the trend until confirmed`() {
        val t = 1_790_000_000_000L
        val api = WithingsMapper.map(WithingsApi.parse(Wire.json.parseToJsonElement("""{"measuregrps":[${grp(5, t / 1000, 80.1, attrib = 1)}]}""") as kotlinx.serialization.json.JsonObject), "u1", "Europe/Prague", cfg).items.single()
        assertEquals(0, TrendSelector.points(listOf(api), listOf(Provider.WITHINGS_API)).size)
        assertEquals(1, TrendSelector.points(listOf(api), listOf(Provider.WITHINGS_API), includeSuspect = true).size)
        assertEquals(1, TrendSelector.points(listOf(api.copy(quality = Quality.OK)), listOf(Provider.WITHINGS_API)).size)
    }

    // ---------------- CSV ----------------
    @Test fun `csv handles kg lb bom quotes semicolon and decimal comma, reports bad rows with line numbers`() {
        val kg = "﻿\"Date\",\"Weight (kg)\",\"Fat mass (kg)\",\"Comments\"\n\"2026-10-20 07:30:00\",\"80.5\",\"16.1\",\"po \"\"běhu\"\"\"\nnonsense,81,,\n2026-10-21 07:30,5,,\n2026-10-22 07:31:00,79.9,,\n"
        val r = WithingsCsv.parse(kg.toByteArray(), "Europe/Prague")
        assertEquals(listOf(80.5, 79.9), r.items.filter { it.type == MetricType.WEIGHT }.map { it.value })
        assertEquals(20.0, r.items.single { it.type == MetricType.BODY_FAT }.value, 1e-9) // 16.1 / 80.5
        assertEquals(listOf(3, 4), r.errors.map { it.line }); assertTrue(r.items.all { it.provider == Provider.MANUAL && it.origin == "withings-csv" })

        val lb = WithingsCsv.parse("Date,Weight (lb)\n2026-10-20 07:30:00,176.37\n".toByteArray(), "UTC").items.single()
        assertEquals(80.0, lb.value, 0.01)
        val semi = WithingsCsv.parse("Date;Weight (kg)\n20.10.2026 07:30:00;80,4\n".toByteArray(), "UTC").items.single()
        assertEquals(80.4, semi.value, 1e-9)
    }

    @Test fun `csv is untrusted input - missing columns, empty and huge files are rejected and ids are stable`() {
        assertTrue(WithingsCsv.parse("Foo,Bar\n1,2\n".toByteArray(), "UTC").errors.isNotEmpty())
        assertTrue(WithingsCsv.parse(ByteArray(0), "UTC").errors.isNotEmpty())
        assertTrue(WithingsCsv.parse(ByteArray(WithingsCsv.MAX_BYTES + 1), "UTC").items.isEmpty())
        assertTrue(WithingsCsv.parse("Date,Weight (stone)\n2026-10-20 07:30:00,12\n".toByteArray(), "UTC").items.isEmpty())
        val text = "Date,Weight (kg)\n2026-10-20 07:30:00,80\n".toByteArray()
        assertEquals(WithingsCsv.parse(text, "UTC").items.map { it.id }, WithingsCsv.parse(text, "UTC").items.map { it.id })
    }

    // ---------------- Health Connect ----------------
    private fun hc(id: String, kg: Double, pkg: String = HealthConnectMapper.WITHINGS_PACKAGE, t: Long = 1_790_000_000_000L, off: Int? = null) =
        HcRecord(id, MetricType.WEIGHT, t, off, kg, pkg, t)

    @Test fun `health connect keeps record id and data origin, accepts only allowed origins`() {
        val ok = HealthConnectMapper.map(hc("a", 80.0), "Europe/Prague")!!
        assertEquals("a", ok.externalId); assertEquals(HealthConnectMapper.WITHINGS_PACKAGE, ok.origin); assertEquals(Provider.HEALTH_CONNECT, ok.provider)
        assertNull(HealthConnectMapper.map(hc("b", 80.0, pkg = "com.someone.else"), "UTC"))
        assertNotNull(HealthConnectMapper.map(hc("b", 80.0, pkg = "com.someone.else"), "UTC", allowedOrigins = emptySet()))
        assertNull(HealthConnectMapper.map(hc("c", 3.0), "UTC")); assertNull(HealthConnectMapper.map(HcRecord("d", MetricType.HRV, 1, null, 50.0, "x", 1), "UTC", emptySet()))
    }

    @Test fun `zone offset decides the local day`() {
        val t = java.time.Instant.parse("2026-10-20T23:30:00Z").toEpochMilli()
        assertEquals("2026-10-20", HealthConnectMapper.map(hc("a", 80.0, t = t, off = 0), "Europe/Prague")!!.localDate)
        assertEquals("2026-10-21", HealthConnectMapper.map(hc("a", 80.0, t = t, off = 2 * 3600), "UTC")!!.localDate)
    }

    @Test fun `changes api applies updates and deletions and the last change for a record wins`() {
        val a = hc("a", 80.0); val a2 = hc("a", 79.0)
        val r = HealthConnectMapper.apply(listOf(HcChange.Upsert(a), HcChange.Upsert(a2), HcChange.Upsert(hc("b", 81.0)), HcChange.Delete("b"), HcChange.Upsert(hc("x", 70.0, pkg = "other"))), "UTC")
        assertEquals(listOf(79.0), r.upserts.map { it.value }); assertEquals(listOf(HealthConnectMapper.observationId("b")), r.deletedObservationIds); assertEquals(1, r.skipped)
        val back = HealthConnectMapper.apply(listOf(HcChange.Delete("a"), HcChange.Upsert(hc("a", 78.0))), "UTC")
        assertEquals(1, back.upserts.size); assertTrue(back.deletedObservationIds.isEmpty(), "pozdější úprava přebíjí dřívější smazání")
    }

    @Test fun `token expiry triggers a full reread that deduplicates and tombstones what disappeared`() {
        assertEquals(HealthConnectMapper.Plan.FULL_READ, HealthConnectMapper.plan(false, false))
        assertEquals(HealthConnectMapper.Plan.USE_CHANGES, HealthConnectMapper.plan(true, false))
        assertEquals(HealthConnectMapper.Plan.FULL_REREAD_AFTER_TOKEN_EXPIRY, HealthConnectMapper.plan(true, true))
        val existing = setOf(HealthConnectMapper.observationId("a"), HealthConnectMapper.observationId("gone"))
        val r = HealthConnectMapper.reread(listOf(hc("a", 80.0), hc("a", 80.0), hc("n", 81.0)), existing, "UTC")
        assertEquals(setOf("a", "n"), r.upserts.map { it.externalId }.toSet()); assertEquals(listOf(HealthConnectMapper.observationId("gone")), r.deletedObservationIds)
        assertEquals(emptyList(), HealthConnectMapper.reread(emptyList(), existing, "UTC").deletedObservationIds)
    }
}

class CallbackPageTest {
    private val page = java.io.File("../docs/withings-applink/withings/callback/index.html").readText()

    @Test fun `callback page has no analytics, no third party resources and strips the code from the address`() {
        assertFalse(Regex("(?i)(src|href)\\s*=\\s*[\"']?(https?:)?//").containsMatchIn(page), "žádné externí zdroje")
        assertFalse(Regex("(?i)(google-analytics|gtag|matomo|plausible|facebook|segment)").containsMatchIn(page))
        assertTrue(page.contains("history.replaceState"), "kód se odstraní z adresy")
        assertTrue(page.contains("""name="referrer" content="no-referrer""""))
        assertEquals(0, Regex("<script[^>]+src=").findAll(page).count())
        assertFalse(Regex("(?i)(fetch\\(|XMLHttpRequest|sendBeacon|localStorage|sessionStorage|document\\.cookie)").containsMatchIn(page), "nic neodesílá ani neukládá")
    }

    @Test fun `assetlinks template is valid json and names the app package`() {
        val j = Wire.json.parseToJsonElement(java.io.File("../docs/withings-applink/.well-known/assetlinks.json").readText())
        assertTrue(j.toString().contains("app.pulse4"))
    }
}
