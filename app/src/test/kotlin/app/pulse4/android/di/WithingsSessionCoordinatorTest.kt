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

class WithingsSessionCoordinatorTest {

    private class InMemoryAuthStore : WithingsAuthStateStore {
        private val map = LinkedHashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun delete(key: String) { map.remove(key) }
    }

    private fun sampleSession(lastExtendedMs: Long? = 1_000L) = WithingsWebSession(
        cookies = "session_key=abc; w_uuid=xyz",
        sessionToken = "tok-1",
        primaryUserId = 42L,
        primaryUserName = "Test",
        lastExtendedMs = lastExtendedMs,
    )

    private fun sampleOk(cookies: String = "session_key=new") = WithingsLoginResult.Ok(
        session = WithingsWebSession(
            cookies = cookies,
            sessionToken = "tok-new",
            primaryUserId = 42L,
            primaryUserName = "Test",
        ),
        profiles = listOf(WithingsUserProfile(id = 42L, firstName = "Test", lastName = "", shortName = "T", gender = 1, birthdate = 0L, isPrimary = true)),
    )

    private fun coordinator(
        store: InMemoryAuthStore,
        now: () -> Long,
        sso: () -> WithingsLoginResult,
        login: (String, String) -> WithingsLoginResult,
        hasCookies: Boolean = true,
        session: () -> WithingsWebSession? = { null },
        onOk: (WithingsLoginResult.Ok, String?) -> Unit = { _, _ -> },
        onPaused: (String, String) -> Unit = { _, _ -> },
        shouldAbort: () -> Boolean = { false },
    ) = WithingsSessionCoordinator(
        store = store,
        nowMs = now,
        isDirectEnabled = { true },
        loadSession = session,
        saveSession = {},
        extendSession = { _, _ -> WithingsExtendResult.Unauthorized },
        hasUsableCookies = { hasCookies },
        trySilentSsoRefresh = sso,
        loginPassword = login,
        onSilentRefreshOk = onOk,
        onAuthPaused = onPaused,
        shouldAbort = shouldAbort,
    )

    @Test
    fun `silent SSO failure falls back to stored password at most once per 6 hours`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "stored-pass")

        var now = 10_000_000L
        val loginArgs = mutableListOf<Pair<String, String>>()
        var okEmail: String? = null
        var ssoCalls = 0
        val coord = coordinator(
            store = store,
            now = { now },
            sso = { ssoCalls++; WithingsLoginResult.Error("SSO expired", WithingsAuthFailureKind.OTHER) },
            login = { e, p -> loginArgs += e to p; sampleOk() },
            onOk = { _, e -> okEmail = e },
        )

        assertTrue(coord.tryAutoReLogin())
        assertEquals(listOf("user@example.com" to "stored-pass"), loginArgs)
        assertEquals("user@example.com", okEmail)
        assertEquals("stored-pass", store.getString(WithingsSessionCoordinator.KEY_PASSWORD))

        // Session expires again 1 h later: SSO is tried, password fallback is throttled.
        now += 60 * 60_000L
        assertFalse(coord.renewSession())
        assertEquals(2, ssoCalls)
        assertEquals(1, loginArgs.size)

        // After 6 h the fallback is allowed once more.
        now += WithingsSessionCoordinator.PASSWORD_FALLBACK_INTERVAL_MS
        assertTrue(coord.renewSession())
        assertEquals(2, loginArgs.size)
    }

    @Test
    fun `stored password works without usable cookies`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "stored-pass")
        var ssoCalls = 0
        var loginCalls = 0
        val coord = coordinator(
            store = store,
            now = { 10_000_000L },
            hasCookies = false,
            sso = { ssoCalls++; WithingsLoginResult.Error("no cookies") },
            login = { _, _ -> loginCalls++; sampleOk() },
        )
        assertTrue(coord.renewSession(forceExtend = true))
        assertEquals(0, ssoCalls)
        assertEquals(1, loginCalls)
    }

    @Test
    fun `password fallback rejected deletes stored password and pauses`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "old-pass")
        var now = 10_000_000L
        var loginCalls = 0
        var pausedMsg: String? = null
        val coord = coordinator(
            store = store,
            now = { now },
            sso = { WithingsLoginResult.Error("SSO expired", WithingsAuthFailureKind.OTHER) },
            login = { _, _ -> loginCalls++; WithingsLoginResult.Error("Wrong", WithingsAuthFailureKind.INVALID_CREDENTIALS) },
            onPaused = { _, m -> pausedMsg = m },
        )

        assertFalse(coord.tryAutoReLogin())
        assertEquals(1, loginCalls)
        assertNull(store.getString(WithingsSessionCoordinator.KEY_PASSWORD))
        assertEquals(WithingsSessionCoordinator.REASON_INVALID_CREDENTIALS, coord.pausedReason())
        assertEquals(WithingsSessionCoordinator.MSG_FALLBACK_INVALID, pausedMsg)

        now += 7 * 60 * 60_000L
        assertFalse(coord.renewSession())
        assertFalse(coord.tryAutoReLogin())
        assertEquals(1, loginCalls)
    }

    @Test
    fun `password fallback needing mfa pauses keeps password and surfaces message`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "pass")
        var loginCalls = 0
        var pausedMsg: String? = null
        val coord = coordinator(
            store = store,
            now = { 10_000_000L },
            sso = { WithingsLoginResult.Error("SSO expired", WithingsAuthFailureKind.OTHER) },
            login = { e, _ -> loginCalls++; WithingsLoginResult.NeedsMfa(e, "code") },
            onPaused = { _, m -> pausedMsg = m },
        )
        assertFalse(coord.tryAutoReLogin())
        assertEquals(WithingsSessionCoordinator.REASON_MFA_REQUIRED, coord.pausedReason())
        assertEquals(WithingsSessionCoordinator.MSG_FALLBACK_MFA, pausedMsg)
        assertEquals("pass", store.getString(WithingsSessionCoordinator.KEY_PASSWORD))
        assertFalse(coord.tryAutoReLogin())
        assertEquals(1, loginCalls)
    }

    @Test
    fun `password fallback rate limited pauses and keeps password`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "pass")
        var loginCalls = 0
        val coord = coordinator(
            store = store,
            now = { 10_000_000L },
            sso = { WithingsLoginResult.Error("SSO expired", WithingsAuthFailureKind.OTHER) },
            login = { _, _ -> loginCalls++; WithingsLoginResult.Error("Too many", WithingsAuthFailureKind.RATE_LIMITED) },
        )
        assertFalse(coord.tryAutoReLogin())
        assertEquals(WithingsSessionCoordinator.REASON_RATE_LIMITED, coord.pausedReason())
        assertEquals("pass", store.getString(WithingsSessionCoordinator.KEY_PASSWORD))
        assertFalse(coord.tryAutoReLogin())
        assertEquals(1, loginCalls)
    }

    @Test
    fun `password fallback network error backs off without pausing`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "pass")
        val coord = coordinator(
            store = store,
            now = { 10_000_000L },
            sso = { WithingsLoginResult.Error("SSO expired", WithingsAuthFailureKind.OTHER) },
            login = { _, _ -> WithingsLoginResult.Error("timeout", WithingsAuthFailureKind.NETWORK) },
        )
        assertFalse(coord.tryAutoReLogin())
        assertFalse(coord.isAutoReloginPaused())
        assertTrue(coord.netBackoffUntilMs() > 10_000_000L)
        assertEquals("pass", store.getString(WithingsSessionCoordinator.KEY_PASSWORD))
    }

    @Test
    fun `rate limit on silent SSO pauses without trying the stored password`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "some-pass")

        var loginCalls = 0
        var pausedCallbackReason: String? = null
        val coord = coordinator(
            store = store,
            now = { 10_000_000L },
            sso = { WithingsLoginResult.Error("Too many attempts", WithingsAuthFailureKind.RATE_LIMITED) },
            login = { _, _ -> loginCalls++; sampleOk() },
            onPaused = { reason, _ -> pausedCallbackReason = reason },
        )

        assertFalse(coord.tryAutoReLogin())
        assertEquals(0, loginCalls)
        assertEquals("some-pass", store.getString(WithingsSessionCoordinator.KEY_PASSWORD))
        assertEquals(WithingsSessionCoordinator.REASON_RATE_LIMITED, coord.pausedReason())
        assertEquals(WithingsSessionCoordinator.REASON_RATE_LIMITED, pausedCallbackReason)

        assertFalse(coord.renewSession())
        assertFalse(coord.tryAutoReLogin())
        assertEquals(0, loginCalls)
    }

    @Test
    fun `mfa required on silent SSO pauses without trying the stored password`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "valid-pass")

        var loginCalls = 0
        val coord = coordinator(
            store = store,
            now = { 10_000_000L },
            sso = { WithingsLoginResult.NeedsMfa("user@example.com", "Enter code") },
            login = { _, _ -> loginCalls++; sampleOk() },
        )

        assertFalse(coord.tryAutoReLogin())
        assertEquals(0, loginCalls)
        assertEquals(WithingsSessionCoordinator.REASON_MFA_REQUIRED, coord.pausedReason())

        assertFalse(coord.renewSession())
        assertFalse(coord.tryAutoReLogin())
        assertEquals(0, loginCalls)
    }

    @Test
    fun `pending manual login aborts background renewal without counting a failure`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "pass")
        var abort = true
        var ssoCalls = 0
        var loginCalls = 0
        val coord = coordinator(
            store = store,
            now = { 10_000_000L },
            sso = { ssoCalls++; WithingsLoginResult.Error("x", WithingsAuthFailureKind.OTHER) },
            login = { _, _ -> loginCalls++; sampleOk() },
            shouldAbort = { abort },
        )
        assertFalse(coord.renewSession())
        assertFalse(coord.tryAutoReLogin())
        assertEquals(0, ssoCalls)
        assertEquals(0, loginCalls)
        assertNull(store.getString(WithingsSessionCoordinator.KEY_OTHER_AUTH_FAILURES))
        assertNull(store.getString(WithingsSessionCoordinator.KEY_LAST_PASSWORD_ATTEMPT_MS))
        abort = false
        assertTrue(coord.tryAutoReLogin())
    }

    @Test
    fun `manual login success resets circuit breaker and allows renewal again`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")

        var loginCalls = 0
        var session: WithingsWebSession? = null
        val coord = WithingsSessionCoordinator(
            store = store,
            nowMs = { 10_000_000L },
            isDirectEnabled = { true },
            loadSession = { session },
            saveSession = { session = it },
            extendSession = { c, t -> WithingsExtendResult.Success(WithingsExtendedSession(t, c)) },
            hasUsableCookies = { true },
            trySilentSsoRefresh = { WithingsLoginResult.Error("Expired", WithingsAuthFailureKind.OTHER) },
            loginPassword = { _, _ ->
                loginCalls++
                WithingsLoginResult.Error("Wrong", WithingsAuthFailureKind.INVALID_CREDENTIALS)
            },
            onSilentRefreshOk = { _, _ -> },
        )

        assertFalse(coord.tryAutoReLogin())
        assertFalse(coord.tryAutoReLogin())
        assertFalse(coord.tryAutoReLogin())
        assertEquals(0, loginCalls)
        assertTrue(coord.isAutoReloginPaused())

        // User logs in manually
        val ok = sampleOk()
        coord.recordManualAuthResult(ok)
        session = ok.session
        assertFalse(coord.isAutoReloginPaused())
        assertNull(coord.pausedReason())
        assertTrue(coord.renewSession(forceExtend = true))
    }

    @Test
    fun `transient network error on extend uses exponential backoff and does not fall back to loginPassword`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "pass")

        var now = 10_000_000L
        var extendCalls = 0
        var loginCalls = 0
        var session: WithingsWebSession? = sampleSession(lastExtendedMs = 1_000L)

        val coord = WithingsSessionCoordinator(
            store = store,
            nowMs = { now },
            isDirectEnabled = { true },
            loadSession = { session },
            saveSession = { session = it },
            extendSession = { _, _ ->
                extendCalls++
                WithingsExtendResult.TransientError("timeout")
            },
            hasUsableCookies = { true },
            trySilentSsoRefresh = { WithingsLoginResult.Error("net", WithingsAuthFailureKind.NETWORK) },
            loginPassword = { _, _ ->
                loginCalls++
                sampleOk()
            },
            onSilentRefreshOk = { _, _ -> },
        )

        // 1st failure -> 60s backoff
        assertFalse(coord.renewSession())
        assertEquals(1, extendCalls)
        assertEquals(0, loginCalls)
        assertFalse(coord.isAutoReloginPaused())

        // Within 60s backoff -> skipped
        now += 30_000L
        assertFalse(coord.renewSession())
        assertEquals(1, extendCalls)

        // After 60s -> retries, 2nd failure -> 120s backoff
        now += 31_000L
        assertFalse(coord.renewSession())
        assertEquals(2, extendCalls)
        assertEquals(0, loginCalls)

        // Within 120s backoff -> skipped
        now += 90_000L
        assertFalse(coord.renewSession())
        assertEquals(2, extendCalls)
        assertEquals(0, loginCalls)
        assertEquals("pass", store.getString(WithingsSessionCoordinator.KEY_PASSWORD))
    }

    @Test
    fun `fresh extend window skips redundant extendSession unless forceExtend is true`() {
        val store = InMemoryAuthStore()
        var now = 10_000_000L
        var extendCalls = 0
        var session: WithingsWebSession? = sampleSession(lastExtendedMs = now - 60_000L)

        val coord = WithingsSessionCoordinator(
            store = store,
            nowMs = { now },
            isDirectEnabled = { true },
            loadSession = { session },
            saveSession = { session = it },
            extendSession = { c, t ->
                extendCalls++
                WithingsExtendResult.Success(WithingsExtendedSession(t, c))
            },
            hasUsableCookies = { false },
            trySilentSsoRefresh = { WithingsLoginResult.Error("no") },
            loginPassword = { _, _ -> sampleOk() },
            onSilentRefreshOk = { _, _ -> },
        )

        assertTrue(coord.renewSession(forceExtend = false))
        assertEquals(0, extendCalls)

        assertTrue(coord.renewSession(forceExtend = true))
        assertEquals(1, extendCalls)
    }

    @Test
    fun `classifyPasswordLoginResponse detects rate limit, invalid credentials, mfa, and server errors`() {
        val rateLimit429 = WithingsDirectAuth.classifyPasswordLoginResponse(
            statusCode = 429,
            finalUrl = "https://account.withings.com/new_workflow/password_check",
            bodyText = "<html></html>",
            email = "u@example.com",
        )
        assertTrue(rateLimit429 is WithingsLoginResult.Error)
        assertEquals(WithingsAuthFailureKind.RATE_LIMITED, (rateLimit429 as WithingsLoginResult.Error).kind)

        val rateLimitText = WithingsDirectAuth.classifyPasswordLoginResponse(
            statusCode = 200,
            finalUrl = "https://account.withings.com/new_workflow/password_check",
            bodyText = "<div class=\"alert alert-danger\"><li>Too many login attempts, please try again later.</li></div>",
            email = "u@example.com",
        )
        assertTrue(rateLimitText is WithingsLoginResult.Error)
        assertEquals(WithingsAuthFailureKind.RATE_LIMITED, (rateLimitText as WithingsLoginResult.Error).kind)

        val badPass = WithingsDirectAuth.classifyPasswordLoginResponse(
            statusCode = 200,
            finalUrl = "https://account.withings.com/new_workflow/password_check",
            bodyText = "<div class=\"alert alert-danger\"><li>Wrong username or password</li></div>",
            email = "u@example.com",
        )
        assertTrue(badPass is WithingsLoginResult.Error)
        assertEquals(WithingsAuthFailureKind.INVALID_CREDENTIALS, (badPass as WithingsLoginResult.Error).kind)

        val mfa = WithingsDirectAuth.classifyPasswordLoginResponse(
            statusCode = 200,
            finalUrl = "https://account.withings.com/new_workflow/email_check",
            bodyText = "<input name=\"code\" />",
            email = "u@example.com",
        )
        assertTrue(mfa is WithingsLoginResult.NeedsMfa)

        val server503 = WithingsDirectAuth.classifyPasswordLoginResponse(
            statusCode = 503,
            finalUrl = "https://account.withings.com/new_workflow/password_check",
            bodyText = "Service Unavailable",
            email = "u@example.com",
        )
        assertTrue(server503 is WithingsLoginResult.Error)
        assertEquals(WithingsAuthFailureKind.NETWORK, (server503 as WithingsLoginResult.Error).kind)
    }

    @Test
    fun `other auth errors pause only after 3 consecutive failures`() {
        val store = InMemoryAuthStore()
        val coord = WithingsSessionCoordinator(
            store = store,
            nowMs = { 10_000_000L },
            isDirectEnabled = { true },
            loadSession = { null },
            saveSession = {},
            extendSession = { _, _ -> WithingsExtendResult.Unauthorized },
            hasUsableCookies = { true },
            trySilentSsoRefresh = { WithingsLoginResult.Error("Temporary parse issue", WithingsAuthFailureKind.OTHER) },
            loginPassword = { _, _ -> sampleOk() },
            onSilentRefreshOk = { _, _ -> },
        )

        assertFalse(coord.tryAutoReLogin())
        assertFalse(coord.isAutoReloginPaused())

        assertFalse(coord.tryAutoReLogin())
        assertFalse(coord.isAutoReloginPaused())

        assertFalse(coord.tryAutoReLogin())
        assertTrue(coord.isAutoReloginPaused())
        assertEquals(WithingsSessionCoordinator.REASON_AUTH_ERROR, coord.pausedReason())
    }
}
