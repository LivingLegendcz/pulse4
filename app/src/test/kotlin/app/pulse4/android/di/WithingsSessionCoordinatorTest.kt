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

    @Test
    fun `invalid password pauses auto-relogin, deletes stored password, and blocks future background loginPassword`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "wrong-pass")

        var now = 10_000_000L
        var loginCalls = 0
        var session: WithingsWebSession? = sampleSession(lastExtendedMs = 1_000L)
        var pausedCallbackReason: String? = null

        val coord = WithingsSessionCoordinator(
            store = store,
            nowMs = { now },
            isDirectEnabled = { true },
            loadSession = { session },
            saveSession = { session = it },
            extendSession = { _, _ -> WithingsExtendResult.Unauthorized },
            hasUsableCookies = { false },
            trySilentSsoRefresh = { WithingsLoginResult.Error("no cookies") },
            loginPassword = { _, _ ->
                loginCalls++
                WithingsLoginResult.Error("Wrong password", WithingsAuthFailureKind.INVALID_CREDENTIALS)
            },
            onSilentRefreshOk = { _, _ -> },
            onAuthPaused = { reason, _ -> pausedCallbackReason = reason },
        )

        assertFalse(coord.renewSession())
        assertEquals(1, loginCalls)
        assertNull(store.getString(WithingsSessionCoordinator.KEY_PASSWORD))
        assertTrue(coord.isAutoReloginPaused())
        assertEquals(WithingsSessionCoordinator.REASON_INVALID_CREDENTIALS, coord.pausedReason())
        assertEquals(WithingsSessionCoordinator.REASON_INVALID_CREDENTIALS, pausedCallbackReason)

        // Subsequent calls (even much later) must NEVER invoke loginPassword again
        now += 60 * 60_000L
        assertFalse(coord.renewSession(forceExtend = true))
        assertFalse(coord.tryAutoReLogin())
        assertEquals(1, loginCalls)
    }

    @Test
    fun `rate limit pauses auto-relogin and blocks future background loginPassword`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "some-pass")

        var loginCalls = 0
        val coord = WithingsSessionCoordinator(
            store = store,
            nowMs = { 10_000_000L },
            isDirectEnabled = { true },
            loadSession = { null },
            saveSession = {},
            extendSession = { _, _ -> WithingsExtendResult.Unauthorized },
            hasUsableCookies = { false },
            trySilentSsoRefresh = { WithingsLoginResult.Error("no") },
            loginPassword = { _, _ ->
                loginCalls++
                WithingsLoginResult.Error("Too many attempts", WithingsAuthFailureKind.RATE_LIMITED)
            },
            onSilentRefreshOk = { _, _ -> },
        )

        assertFalse(coord.tryAutoReLogin())
        assertEquals(1, loginCalls)
        assertEquals(WithingsSessionCoordinator.REASON_RATE_LIMITED, coord.pausedReason())

        assertFalse(coord.renewSession())
        assertFalse(coord.tryAutoReLogin())
        assertEquals(1, loginCalls)
    }

    @Test
    fun `mfa required pauses auto-relogin and blocks future background loginPassword`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "valid-pass")

        var loginCalls = 0
        val coord = WithingsSessionCoordinator(
            store = store,
            nowMs = { 10_000_000L },
            isDirectEnabled = { true },
            loadSession = { null },
            saveSession = {},
            extendSession = { _, _ -> WithingsExtendResult.Unauthorized },
            hasUsableCookies = { false },
            trySilentSsoRefresh = { WithingsLoginResult.Error("no") },
            loginPassword = { _, _ ->
                loginCalls++
                WithingsLoginResult.NeedsMfa("user@example.com", "Enter code")
            },
            onSilentRefreshOk = { _, _ -> },
        )

        assertFalse(coord.tryAutoReLogin())
        assertEquals(1, loginCalls)
        assertEquals(WithingsSessionCoordinator.REASON_MFA_REQUIRED, coord.pausedReason())

        assertFalse(coord.renewSession())
        assertFalse(coord.tryAutoReLogin())
        assertEquals(1, loginCalls)
    }

    @Test
    fun `manual login success resets circuit breaker and allows renewal again`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "user@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "bad-pass")

        var loginCalls = 0
        var session: WithingsWebSession? = null
        val coord = WithingsSessionCoordinator(
            store = store,
            nowMs = { 10_000_000L },
            isDirectEnabled = { true },
            loadSession = { session },
            saveSession = { session = it },
            extendSession = { c, t -> WithingsExtendResult.Success(WithingsExtendedSession(t, c)) },
            hasUsableCookies = { false },
            trySilentSsoRefresh = { WithingsLoginResult.Error("no") },
            loginPassword = { _, _ ->
                loginCalls++
                WithingsLoginResult.Error("Wrong", WithingsAuthFailureKind.INVALID_CREDENTIALS)
            },
            onSilentRefreshOk = { _, _ -> },
        )

        assertFalse(coord.tryAutoReLogin())
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
    fun `transient network error uses exponential backoff and does not fall back to loginPassword`() {
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
        assertEquals("pass", store.getString(WithingsSessionCoordinator.KEY_PASSWORD))

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
}
