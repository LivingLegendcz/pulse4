package app.pulse4.android.di

import app.pulse4.withings.WithingsExtendResult
import app.pulse4.withings.WithingsExtendedSession
import app.pulse4.withings.WithingsUserProfile
import app.pulse4.withings.WithingsWebSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Silent re-authentication decision logic: when the app renews the Withings session by itself, when it prompts the
 * user, and the rate limiting that keeps it from hammering (or locking) the account.
 */
class WithingsSilentReauthTest {

    private class Store : WithingsAuthStateStore {
        private val map = LinkedHashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun delete(key: String) { map.remove(key) }
    }

    private fun ok() = WithingsLoginResult.Ok(
        session = WithingsWebSession(cookies = "session_key=new", sessionToken = "t", primaryUserId = 1L, primaryUserName = "T"),
        profiles = listOf(WithingsUserProfile(id = 1L, firstName = "T", lastName = "", shortName = "T", gender = 1, birthdate = 0L, isPrimary = true)),
    )

    private fun expiredSession() = WithingsWebSession(
        cookies = "session_key=old; w_uuid=x", sessionToken = "t", primaryUserId = 1L, primaryUserName = "T", lastExtendedMs = 1L,
    )

    private class Harness(
        val store: Store = Store(),
        var now: Long = 100_000_000L,
        var extend: () -> WithingsExtendResult = { WithingsExtendResult.Unauthorized },
        var sso: () -> WithingsLoginResult = { WithingsLoginResult.Error("SSO expired", WithingsAuthFailureKind.OTHER) },
        var login: () -> WithingsLoginResult = { WithingsLoginResult.Error("boom", WithingsAuthFailureKind.OTHER) },
        var session: WithingsWebSession? = null,
        var cookies: Boolean = true,
    ) {
        val promptReasons = mutableListOf<String>()
        val diagEvents = mutableListOf<String>()
        var extendCalls = 0
        var ssoCalls = 0
        var loginCalls = 0
        var silentOk = 0
        val coord = WithingsSessionCoordinator(
            store = store,
            nowMs = { now },
            isDirectEnabled = { true },
            loadSession = { session },
            saveSession = { session = it },
            extendSession = { _, _ -> extendCalls++; extend() },
            hasUsableCookies = { cookies },
            trySilentSsoRefresh = { ssoCalls++; sso() },
            loginPassword = { _, _ -> loginCalls++; login() },
            onSilentRefreshOk = { _, _ -> silentOk++ },
            onAuthPaused = { reason, _ -> promptReasons += reason },
            onDiag = { diagEvents += it },
        )

        fun withPassword(): Harness {
            store.putString(WithingsSessionCoordinator.KEY_EMAIL, "u@example.com")
            store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "pw")
            return this
        }
    }

    // ---------------- renews silently, never asks ----------------

    @Test
    fun `expired session with stored password is renewed silently and the user is never prompted`() {
        val h = Harness(session = expiredSession(), login = { ok() }).withPassword()
        assertTrue(h.coord.renewSession())
        assertEquals(1, h.silentOk)
        assertEquals(1, h.loginCalls)
        assertFalse(h.coord.needsHumanInput())
        assertTrue(h.promptReasons.isEmpty())
    }

    @Test
    fun `silent SSO alone renews without touching the stored password`() {
        val h = Harness(session = expiredSession(), sso = { ok() }).withPassword()
        assertTrue(h.coord.renewSession())
        assertEquals(0, h.loginCalls)
        assertEquals(1, h.silentOk)
    }

    @Test
    fun `regression - many background ticks after a failed password login never latch a permanent pause`() {
        // The old coordinator counted every throttled call as a failure and paused for good after three, which is
        // exactly "every time I open the app it asks me to log in with the saved password".
        val h = Harness(session = expiredSession()).withPassword()
        assertFalse(h.coord.renewSession()) // real attempt #1: password login fails with an unclassified error
        assertEquals(1, h.loginCalls)
        repeat(10) {                         // app start keepalive, worker ticks, service ticks, imports...
            h.now += 60_000L
            assertFalse(h.coord.renewSession())
        }
        assertEquals("only skipped calls, no extra attempts", 1, h.loginCalls)
        assertEquals(1, h.coord.reauthFailures())
        assertFalse(h.coord.isAutoReloginPaused())
        assertFalse(h.coord.needsHumanInput())

        // Withings recovers: the next allowed attempt (after the 15 min cooldown) logs in silently.
        h.now += 15 * 60_000L
        h.login = { ok() }
        assertTrue(h.coord.renewSession())
        assertEquals(0, h.coord.reauthFailures())
        assertEquals(0L, h.coord.reauthCooldownUntilMs())
        assertTrue(h.promptReasons.isEmpty())
    }

    // ---------------- prompts only when a human is needed ----------------

    @Test
    fun `mfa demanded by the password login needs the human and stops all further attempts`() {
        val h = Harness(session = expiredSession(), login = { WithingsLoginResult.NeedsMfa("u@example.com", "code") }).withPassword()
        assertFalse(h.coord.renewSession())
        assertEquals(WithingsSessionCoordinator.REASON_MFA_REQUIRED, h.coord.pausedReason())
        assertTrue(h.coord.needsHumanInput())
        assertEquals(listOf(WithingsSessionCoordinator.REASON_MFA_REQUIRED), h.promptReasons)
        h.now += 48 * 60 * 60_000L
        assertFalse(h.coord.renewSession())
        assertEquals(1, h.loginCalls)
    }

    @Test
    fun `rejected password needs the human and the stored password is dropped`() {
        val h = Harness(session = expiredSession(), login = { WithingsLoginResult.Error("bad", WithingsAuthFailureKind.INVALID_CREDENTIALS) }).withPassword()
        assertFalse(h.coord.renewSession())
        assertEquals(WithingsSessionCoordinator.REASON_INVALID_CREDENTIALS, h.coord.pausedReason())
        assertNull(h.store.getString(WithingsSessionCoordinator.KEY_PASSWORD))
        assertTrue(h.coord.needsHumanInput())
    }

    @Test
    fun `no stored password and no sso cookies is an immediate human prompt with a clear reason`() {
        val h = Harness(session = expiredSession(), cookies = false)
        h.store.putString(WithingsSessionCoordinator.KEY_EMAIL, "u@example.com")
        assertFalse(h.coord.renewSession())
        assertEquals(WithingsSessionCoordinator.REASON_AUTH_ERROR, h.coord.pausedReason())
        assertEquals(WithingsSessionCoordinator.MSG_NO_PASSWORD, h.coord.pausedMessage())
        assertEquals(0, h.loginCalls)
    }

    @Test
    fun `never connected means nothing to renew and no prompt`() {
        val h = Harness(session = null, cookies = false)
        assertFalse(h.coord.renewSession())
        assertFalse(h.coord.isAutoReloginPaused())
        assertFalse(h.coord.needsHumanInput())
        assertEquals(0, h.ssoCalls + h.loginCalls)
    }

    @Test
    fun `six real failures tell the user but retries continue and a later success clears the prompt`() {
        val h = Harness(session = expiredSession()).withPassword()
        val gaps = listOf(15, 60, 180, 360, 360, 360).map { it * 60_000L }
        for ((i, gap) in gaps.withIndex()) {
            assertFalse(h.coord.renewSession())
            assertEquals(i + 1, h.loginCalls)
            h.now += gap + 1
        }
        assertEquals(WithingsReauthPolicy.ATTENTION_AFTER_FAILURES, h.coord.reauthFailures())
        assertTrue(h.coord.needsHumanInput())
        assertFalse("a prompt, not a latch", h.coord.isAutoReloginPaused())
        assertEquals(listOf(WithingsSessionCoordinator.REASON_AUTH_ERROR), h.promptReasons)

        h.login = { ok() }
        assertTrue(h.coord.renewSession())
        assertFalse(h.coord.needsHumanInput())
    }

    // ---------------- extend / keepalive classification ----------------

    @Test
    fun `successful keepalive extend lifts earlier soft failures`() {
        val h = Harness(session = expiredSession()).withPassword()
        assertFalse(h.coord.renewSession())
        assertEquals(1, h.coord.reauthFailures())
        h.now += 30 * 60_000L
        h.extend = { WithingsExtendResult.Success(WithingsExtendedSession("t2", "session_key=ext")) }
        assertTrue(h.coord.renewSession(forceExtend = true))
        assertEquals(0, h.coord.reauthFailures())
        assertEquals(0L, h.coord.reauthCooldownUntilMs())
    }

    @Test
    fun `transient extend failures climb the re-auth ladder after three in a row instead of backing off forever`() {
        val h = Harness(session = expiredSession(), extend = { WithingsExtendResult.TransientError("Invalid JSON response") }, login = { ok() })
            .withPassword()
        assertFalse(h.coord.renewSession()); assertEquals(0, h.loginCalls)
        h.now += 61_000L
        assertFalse(h.coord.renewSession()); assertEquals(0, h.loginCalls)
        h.now += 121_000L
        // Third consecutive unreadable answer: treat the session as dead, even though the network backoff is running.
        assertTrue(h.coord.renewSession())
        assertEquals(1, h.loginCalls)
    }

    @Test
    fun `a real network outage is not escalated into password logins`() {
        val h = Harness(
            session = expiredSession(),
            extend = { WithingsExtendResult.TransientError("timeout") },
            sso = { WithingsLoginResult.Error("offline", WithingsAuthFailureKind.NETWORK) },
            login = { ok() },
        ).withPassword()
        repeat(3) {
            assertFalse(h.coord.renewSession())
            h.now += 10 * 60_000L
        }
        assertEquals(0, h.loginCalls)
        assertFalse(h.coord.isAutoReloginPaused())
    }

    // ---------------- rate limiting ----------------

    @Test
    fun `password logins are spaced even after a success so a dying session cannot cause a login loop`() {
        val h = Harness(session = expiredSession(), login = { ok() }).withPassword()
        assertTrue(h.coord.renewSession(forceExtend = true))
        repeat(5) {
            h.now += 60_000L
            assertFalse(h.coord.renewSession(forceExtend = true))
        }
        assertEquals(1, h.loginCalls)
    }

    @Test
    fun `cooldown schedule is 15 min 1 h 3 h then 6 h and rate limits 2 h 6 h 24 h`() {
        assertEquals(listOf(15L, 60L, 180L, 360L, 360L), (1..5).map { WithingsReauthPolicy.failureCooldownMs(it) / 60_000L })
        assertEquals(listOf(120L, 360L, 1440L, 1440L), (1..4).map { WithingsReauthPolicy.rateLimitCooldownMs(it) / 60_000L })
        assertEquals(15L * 60_000L, WithingsReauthPolicy.failureCooldownMs(0))
    }

    @Test
    fun `manual login success clears every pause cooldown and counter`() {
        val h = Harness(session = expiredSession(), login = { WithingsLoginResult.Error("429", WithingsAuthFailureKind.RATE_LIMITED) }).withPassword()
        assertFalse(h.coord.renewSession())
        assertTrue(h.coord.reauthCooldownUntilMs() > 0L)
        h.coord.recordManualAuthResult(ok())
        assertEquals(0L, h.coord.reauthCooldownUntilMs())
        assertEquals(0, h.coord.reauthFailures())
        assertNull(h.store.getString(WithingsSessionCoordinator.KEY_PASSWORD_COOLDOWN_UNTIL_MS))
        assertNull(h.store.getString(WithingsSessionCoordinator.KEY_RATE_LIMIT_HITS))
        assertNull(h.store.getString(WithingsSessionCoordinator.KEY_LAST_RATE_LIMIT_MS))
    }

    @Test
    fun `regression - alternating rate limit and background ok still escalates and enforces 6h spacing`() {
        // Reproduces the real phone log: 14:07 RATE_LIMITED -> 16:07 Ok -> 17:38 RATE_LIMITED.
        // Previously PASSWORD_MIN_SPACING_MS was 10 min and background Ok reset KEY_RATE_LIMIT_HITS to 0,
        // causing an infinite loop every 1-2h that locked out the user's account on app.withings.com.
        val h = Harness(
            session = expiredSession(),
            login = { WithingsLoginResult.Error("Too many attempts", WithingsAuthFailureKind.RATE_LIMITED) },
        ).withPassword()

        // 14:07: hit #1 -> 2h cooldown
        assertFalse(h.coord.renewSession())
        assertEquals(1, h.loginCalls)
        assertEquals(h.now + 2 * 60 * 60_000L, h.coord.reauthCooldownUntilMs())

        // 16:07 (+2h): background password login succeeds, but rate-limit hit memory is kept for 24h
        // and next background password login is spaced by 6h.
        h.now += 2 * 60 * 60_000L + 1_000L
        h.login = { ok() }
        assertTrue(h.coord.renewSession())
        assertEquals(2, h.loginCalls)
        assertEquals("1", h.store.getString(WithingsSessionCoordinator.KEY_RATE_LIMIT_HITS))

        // 17:37 (+1.5h): session dies again -> password login MUST be skipped because of 6h spacing!
        h.now += 90 * 60_000L
        h.login = { WithingsLoginResult.Error("Too many attempts", WithingsAuthFailureKind.RATE_LIMITED) }
        assertFalse(h.coord.renewSession())
        assertEquals("spaced by 6h, must not hammer password_check", 2, h.loginCalls)

        // 22:08 (+6h after 16:07): password login allowed, hits RATE_LIMITED -> counted as hit #2 (6h cooldown, not #1!).
        h.now += 5 * 60 * 60_000L
        assertFalse(h.coord.renewSession())
        assertEquals(3, h.loginCalls)
        assertEquals("2", h.store.getString(WithingsSessionCoordinator.KEY_RATE_LIMIT_HITS))
        assertEquals(h.now + 6 * 60 * 60_000L, h.coord.reauthCooldownUntilMs())
    }

    @Test
    fun `manual rate limit with existing session sets timed cooldown without hard pausing`() {
        val h = Harness(session = expiredSession(), login = { ok() }).withPassword()
        h.coord.recordManualAuthResult(WithingsLoginResult.Error("429", WithingsAuthFailureKind.RATE_LIMITED))
        assertFalse(h.coord.isAutoReloginPaused())
        assertTrue(h.coord.reauthCooldownUntilMs() > h.now)
        // Background renewal respects the cooldown instead of piling onto the manual rate limit.
        assertFalse(h.coord.renewSession())
        assertEquals(0, h.loginCalls)
    }

    @Test
    fun `failed silent SSO aligns cooldown with password spacing so keepalive ticks do not spam SSO every 30 min`() {
        val h = Harness(
            session = expiredSession(),
            sso = { WithingsLoginResult.Error("login page", WithingsAuthFailureKind.OTHER) },
            login = { ok() },
        ).withPassword()

        // First renewal succeeds via password -> sets 6h password spacing.
        assertTrue(h.coord.renewSession())
        assertEquals(1, h.ssoCalls)
        assertEquals(1, h.loginCalls)

        // 30 min later, session dies again: SSO runs once, fails (OTHER), aligns SSO cooldown to password spacing.
        h.now += 30 * 60_000L
        assertFalse(h.coord.renewSession())
        assertEquals(2, h.ssoCalls)
        assertEquals(1, h.loginCalls)
        assertTrue(h.diagEvents.any { it.startsWith("skip(pw_spaced=") })

        // Subsequent 30-min keepalive ticks across the remaining 5.5 hours MUST NOT spam account.withings.com!
        repeat(10) {
            h.now += 30 * 60_000L
            assertFalse(h.coord.renewSession())
        }
        assertEquals("SSO must stay quiet until password spacing ends", 2, h.ssoCalls)
        assertEquals(1, h.loginCalls)

        // Once the 6h password spacing ends, both SSO and password login become eligible again.
        h.now += 31 * 60_000L
        assertTrue(h.coord.renewSession())
        assertEquals(3, h.ssoCalls)
        assertEquals(2, h.loginCalls)
    }

    @Test
    fun `manual sync trigger can recover expired session with stored password after 10m floor, still obeying rate limit`() {
        val h = Harness(
            session = expiredSession(),
            sso = { WithingsLoginResult.Error("login page", WithingsAuthFailureKind.OTHER) },
            login = { ok() },
        ).withPassword()

        // Background password login succeeds -> sets 6h background spacing.
        assertTrue(h.coord.renewSession())
        assertEquals(1, h.loginCalls)

        // 3 min later: even manual sync respects the 10-min safety floor.
        h.now += 3 * 60_000L
        assertFalse(h.coord.renewSession(manualTriggered = true))
        assertEquals(1, h.loginCalls)

        // 15 min later (still inside 6h background spacing): manual sync recovers immediately!
        h.now += 12 * 60_000L
        assertTrue(h.coord.renewSession(manualTriggered = true))
        assertEquals(2, h.loginCalls)
        assertTrue(h.diagEvents.contains("manual_pw_reauth"))

        // If rate-limited, even manual sync MUST respect the 2h rate-limit cooldown.
        h.now += 15 * 60_000L
        h.login = { WithingsLoginResult.Error("429", WithingsAuthFailureKind.RATE_LIMITED) }
        assertFalse(h.coord.renewSession(manualTriggered = true))
        assertEquals(3, h.loginCalls)
        h.now += 30 * 60_000L
        assertFalse(h.coord.renewSession(manualTriggered = true))
        assertEquals("manual sync must obey rate-limit cooldown", 3, h.loginCalls)

        val summary = h.coord.diagnosticSummary(hasSession = true, hasKeyCookie = true)
        assertTrue(summary.contains("heslo=ano"))
        assertTrue(summary.contains("rlHits=1"))
    }

    @Test
    fun `diagnostics preserves readable SYNC and EXTEND parameters and filterEligibleChildContacts hides adults`() {
        val diag = WithingsAuthDiagnostics(nowMs = { 1_700_000_000_000L })
        diag.mark(WithingsAuthDiagnostics.Flow.SYNC, "web_start")
        diag.mark(WithingsAuthDiagnostics.Flow.EXTEND, "ok(sidSent=1,scKey=1,sid=0)")
        diag.mark(WithingsAuthDiagnostics.Flow.SYNC, "web_ok(saved=4,tomb=0)")
        val text = diag.export()
        assertTrue(text.contains("SYNC web_start"))
        assertTrue(text.contains("EXTEND ok(sidSent=1,scKey=1,sid=0)"))
        assertTrue(text.contains("SYNC web_ok(saved=4,tomb=0)"))

        val adult = app.pulse4.android.data.ContactRow("id-kajaska", "Kajaska", "pub1", "fp1", 100L)
        val child = app.pulse4.android.data.ContactRow("id-child", "Matyas", "pub2", "fp2", 100L)
        val filtered = app.pulse4.android.ui.filterEligibleChildContacts(
            contacts = listOf(adult, child),
            verifiedChildIds = setOf("id-child"),
            boundChildIds = emptySet(),
        )
        assertEquals(listOf("id-child"), filtered.map { it.identity })

        // No children at all -> empty list (adult sharing contact is never shown as a child)
        val noChildren = app.pulse4.android.ui.filterEligibleChildContacts(
            contacts = listOf(adult),
            verifiedChildIds = emptySet(),
            boundChildIds = emptySet(),
        )
        assertTrue(noChildren.isEmpty())
    }
}
