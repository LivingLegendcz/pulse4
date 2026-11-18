package app.pulse4.android.di

import app.pulse4.withings.WithingsExtendResult
import app.pulse4.withings.WithingsWebSession

interface WithingsAuthStateStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun delete(key: String)
}

/**
 * Řídí obnovování webové relace Withings, circuit-breaker při chybách autentizace
 * a exponenciální backoff při síťových výpadcích.
 *
 * Zajišťuje, že při chybném heslu, rate-limitu / lockoutu nebo vyžádání 2FA kódu
 * se na pozadí už nikdy opakovaně nevolá `loginPassword(...)`, dokud se uživatel
 * znovu úspěšně nepřihlásí ručně.
 */
class WithingsSessionCoordinator(
    private val store: WithingsAuthStateStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val isDirectEnabled: () -> Boolean,
    private val loadSession: () -> WithingsWebSession?,
    private val saveSession: (WithingsWebSession) -> Unit,
    private val extendSession: (cookies: String, sessionToken: String?) -> WithingsExtendResult,
    private val hasUsableCookies: () -> Boolean,
    private val trySilentSsoRefresh: () -> WithingsLoginResult,
    private val loginPassword: (email: String, password: String) -> WithingsLoginResult,
    private val onSilentRefreshOk: (WithingsLoginResult.Ok, emailFallback: String?) -> Unit,
    private val onAuthPaused: (reason: String, message: String) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {},
) {
    fun pausedReason(): String? = store.getString(KEY_PAUSED_REASON)?.takeIf { it.isNotBlank() }

    fun pausedMessage(): String? = store.getString(KEY_PAUSED_MESSAGE)?.takeIf { it.isNotBlank() }

    fun isAutoReloginPaused(): Boolean = pausedReason() != null

    fun netBackoffUntilMs(): Long = store.getString(KEY_NET_BACKOFF_UNTIL_MS)?.toLongOrNull() ?: 0L

    fun clearAuthPauseAndBackoff() {
        store.delete(KEY_PAUSED_REASON)
        store.delete(KEY_PAUSED_MESSAGE)
        resetNetworkBackoff()
    }

    private fun resetNetworkBackoff() {
        store.delete(KEY_NET_FAILURES)
        store.delete(KEY_NET_BACKOFF_UNTIL_MS)
    }

    private fun pauseAutoRelogin(reason: String, message: String) {
        store.putString(KEY_PAUSED_REASON, reason)
        store.putString(KEY_PAUSED_MESSAGE, message)
        onLog("Auto-relogin paused: reason=$reason")
        onAuthPaused(reason, message)
    }

    private fun recordNetworkFailure(now: Long) {
        val failures = (store.getString(KEY_NET_FAILURES)?.toIntOrNull() ?: 0) + 1
        val shift = (failures - 1).coerceIn(0, 5)
        val delayMs = (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
        store.putString(KEY_NET_FAILURES, failures.toString())
        store.putString(KEY_NET_BACKOFF_UNTIL_MS, (now + delayMs).toString())
        onLog("Network failure #$failures, backoff for ${delayMs / 1000L}s")
    }

    /**
     * Zaznamená výsledek ručního přihlášení v UI.
     * Úspěch odblokuje circuit-breaker; chybné heslo smaže uložené heslo a zablokuje auto-relogin.
     */
    fun recordManualAuthResult(res: WithingsLoginResult) {
        when (res) {
            is WithingsLoginResult.Ok -> clearAuthPauseAndBackoff()
            is WithingsLoginResult.NeedsMfa -> pauseAutoRelogin(REASON_MFA_REQUIRED, res.message)
            is WithingsLoginResult.Error -> when (res.kind) {
                WithingsAuthFailureKind.INVALID_CREDENTIALS -> {
                    store.delete(KEY_PASSWORD)
                    pauseAutoRelogin(REASON_INVALID_CREDENTIALS, res.message)
                }
                WithingsAuthFailureKind.RATE_LIMITED -> {
                    pauseAutoRelogin(REASON_RATE_LIMITED, res.message)
                }
                WithingsAuthFailureKind.OTHER -> {
                    pauseAutoRelogin(REASON_AUTH_ERROR, res.message)
                }
                WithingsAuthFailureKind.NETWORK -> Unit
            }
        }
    }

    fun renewSession(forceExtend: Boolean = false): Boolean {
        if (!isDirectEnabled()) return false
        if (isAutoReloginPaused()) {
            onLog("renewSession skipped: paused (${pausedReason()})")
            return false
        }
        val now = nowMs()
        val backoffUntil = netBackoffUntilMs()
        if (now < backoffUntil) {
            onLog("renewSession skipped: network backoff until $backoffUntil")
            return false
        }

        val s = loadSession()
        onLog("renewSession: hasSession=${s != null}, isConnected=${s?.isConnected() == true}, forceExtend=$forceExtend")
        if (s != null && s.isConnected()) {
            val lastExt = s.lastExtendedMs ?: 0L
            if (!forceExtend && lastExt > 0L && (now - lastExt) in 0 until FRESH_EXTEND_WINDOW_MS) {
                onLog("renewSession: session recently extended, skipping")
                return true
            }
            when (val ext = extendSession(s.cookies, s.sessionToken)) {
                is WithingsExtendResult.Success -> {
                    onLog("renewSession: extendSession SUCCESS")
                    resetNetworkBackoff()
                    saveSession(
                        s.copy(
                            sessionToken = ext.session.sessionToken ?: s.sessionToken,
                            cookies = ext.session.cookies,
                            lastExtendedMs = now,
                        )
                    )
                    return true
                }
                is WithingsExtendResult.TransientError -> {
                    onLog("renewSession: extendSession transient error (${ext.reason}), backing off")
                    recordNetworkFailure(now)
                    return false
                }
                is WithingsExtendResult.Unauthorized -> {
                    onLog("renewSession: extendSession unauthorized, falling back to tryAutoReLogin")
                }
            }
        }

        if (store.getString(KEY_PASSWORD) != null) {
            store.delete(KEY_PASSWORD)
        }
        if (hasUsableCookies()) {
            return tryAutoReLogin()
        }
        return false
    }

    fun tryAutoReLogin(): Boolean {
        if (store.getString(KEY_PASSWORD) != null) {
            store.delete(KEY_PASSWORD)
        }
        if (isAutoReloginPaused()) {
            onLog("tryAutoReLogin skipped: paused (${pausedReason()})")
            return false
        }
        val now = nowMs()
        val backoffUntil = netBackoffUntilMs()
        if (now < backoffUntil) {
            onLog("tryAutoReLogin skipped: network backoff until $backoffUntil")
            return false
        }

        val email = store.getString(KEY_EMAIL)

        if (!hasUsableCookies()) {
            return false
        }

        val ssoRes = try {
            trySilentSsoRefresh()
        } catch (e: Exception) {
            WithingsLoginResult.Error(e.message ?: "Network error", WithingsAuthFailureKind.NETWORK)
        }
        onLog("tryAutoReLogin SSO result: ${ssoRes::class.simpleName}")
        return when (ssoRes) {
            is WithingsLoginResult.Ok -> {
                clearAuthPauseAndBackoff()
                onSilentRefreshOk(ssoRes, email)
                true
            }
            is WithingsLoginResult.NeedsMfa -> {
                pauseAutoRelogin(REASON_MFA_REQUIRED, ssoRes.message)
                false
            }
            is WithingsLoginResult.Error -> when (ssoRes.kind) {
                WithingsAuthFailureKind.NETWORK -> {
                    recordNetworkFailure(now)
                    false
                }
                WithingsAuthFailureKind.RATE_LIMITED -> {
                    pauseAutoRelogin(REASON_RATE_LIMITED, ssoRes.message)
                    false
                }
                WithingsAuthFailureKind.INVALID_CREDENTIALS -> {
                    pauseAutoRelogin(REASON_INVALID_CREDENTIALS, ssoRes.message)
                    false
                }
                WithingsAuthFailureKind.OTHER -> {
                    pauseAutoRelogin(REASON_AUTH_ERROR, ssoRes.message)
                    false
                }
            }
        }
    }

    companion object {
        const val KEY_EMAIL = "withings.web.email"
        const val KEY_PASSWORD = "withings.web.password"
        const val KEY_PAUSED_REASON = "withings.auth.paused_reason"
        const val KEY_PAUSED_MESSAGE = "withings.auth.paused_message"
        const val KEY_NET_FAILURES = "withings.auth.net_failures"
        const val KEY_NET_BACKOFF_UNTIL_MS = "withings.auth.net_backoff_until_ms"

        const val REASON_INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
        const val REASON_RATE_LIMITED = "RATE_LIMITED"
        const val REASON_MFA_REQUIRED = "MFA_REQUIRED"
        const val REASON_AUTH_ERROR = "AUTH_ERROR"

        const val FRESH_EXTEND_WINDOW_MS = 5 * 60 * 1000L
        const val BASE_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_MS = 30 * 60_000L
    }
}
