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
 * Pokud tiché SSO selže a uživatel si nechal zapamatovat heslo, zkusí se na pozadí jedno přihlášení
 * uloženým heslem nejvýše jednou za 6 h. Při chybném heslu (heslo se smaže), rate-limitu / lockoutu
 * nebo vyžádání 2FA kódu se auto-relogin pozastaví, dokud se uživatel znovu úspěšně nepřihlásí ručně.
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
    /** True when a manual (user) login is waiting; background renewal then stops between HTTP calls. */
    private val shouldAbort: () -> Boolean = { false },
) {
    fun pausedReason(): String? = store.getString(KEY_PAUSED_REASON)?.takeIf { it.isNotBlank() }

    fun pausedMessage(): String? = store.getString(KEY_PAUSED_MESSAGE)?.takeIf { it.isNotBlank() }

    fun isAutoReloginPaused(): Boolean = pausedReason() != null

    fun netBackoffUntilMs(): Long = store.getString(KEY_NET_BACKOFF_UNTIL_MS)?.toLongOrNull() ?: 0L

    fun clearAuthPauseAndBackoff() {
        store.delete(KEY_PAUSED_REASON)
        store.delete(KEY_PAUSED_MESSAGE)
        store.delete(KEY_OTHER_AUTH_FAILURES)
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

        if (shouldAbort()) {
            onLog("renewSession aborted: manual login pending")
            return false
        }
        return tryAutoReLogin()
    }

    /**
     * Tiché obnovení relace na pozadí:
     * 1. Tiché SSO přes dlouhodobé cookies `account.withings.com`.
     * 2. Pokud SSO selže (ne síťově) a uživatel si nechal zapamatovat heslo, jeden pokus o přihlášení
     *    uloženým heslem – nejvýše jednou za [PASSWORD_FALLBACK_INTERVAL_MS], nikdy ve smyčce.
     */
    fun tryAutoReLogin(): Boolean {
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

        val email = store.getString(KEY_EMAIL)?.takeIf { it.isNotBlank() }
        var ssoOtherFailure: WithingsLoginResult.Error? = null

        if (hasUsableCookies()) {
            if (shouldAbort()) {
                onLog("tryAutoReLogin aborted: manual login pending")
                return false
            }
            val ssoRes = try {
                trySilentSsoRefresh()
            } catch (e: Exception) {
                WithingsLoginResult.Error(e.message ?: "Network error", WithingsAuthFailureKind.NETWORK)
            }
            onLog("tryAutoReLogin SSO result: ${ssoRes::class.simpleName}")
            if (ssoRes !is WithingsLoginResult.Ok && shouldAbort()) {
                // Přerušeno kvůli ručnímu přihlášení – nepočítat jako selhání (circuit-breaker / backoff).
                onLog("tryAutoReLogin aborted after SSO attempt: manual login pending")
                return false
            }
            when (ssoRes) {
                is WithingsLoginResult.Ok -> {
                    clearAuthPauseAndBackoff()
                    onSilentRefreshOk(ssoRes, email)
                    return true
                }
                is WithingsLoginResult.NeedsMfa -> {
                    pauseAutoRelogin(REASON_MFA_REQUIRED, ssoRes.message)
                    return false
                }
                is WithingsLoginResult.Error -> when (ssoRes.kind) {
                    WithingsAuthFailureKind.NETWORK -> {
                        recordNetworkFailure(now)
                        return false
                    }
                    WithingsAuthFailureKind.RATE_LIMITED -> {
                        pauseAutoRelogin(REASON_RATE_LIMITED, ssoRes.message)
                        return false
                    }
                    WithingsAuthFailureKind.INVALID_CREDENTIALS -> {
                        pauseAutoRelogin(REASON_INVALID_CREDENTIALS, ssoRes.message)
                        return false
                    }
                    WithingsAuthFailureKind.OTHER -> ssoOtherFailure = ssoRes
                }
            }
        }

        tryPasswordFallback(now, email)?.let { return it }

        val other = ssoOtherFailure ?: return false
        countOtherFailure(other.message)
        return false
    }

    private fun countOtherFailure(message: String) {
        val failures = (store.getString(KEY_OTHER_AUTH_FAILURES)?.toIntOrNull() ?: 0) + 1
        store.putString(KEY_OTHER_AUTH_FAILURES, failures.toString())
        if (failures >= MAX_CONSECUTIVE_OTHER_FAILURES) {
            pauseAutoRelogin(REASON_AUTH_ERROR, message)
        } else {
            onLog("Non-network auth failure #$failures/$MAX_CONSECUTIVE_OTHER_FAILURES, not pausing yet")
        }
    }

    /**
     * Jeden pokus o přihlášení zapamatovaným heslem. Vrací `null`, pokud se pokus neprovedl
     * (chybí heslo / e-mail, throttle 6 h, ruční přihlášení čeká), jinak výsledek obnovy.
     */
    private fun tryPasswordFallback(now: Long, email: String?): Boolean? {
        val password = store.getString(KEY_PASSWORD)?.takeIf { it.isNotEmpty() } ?: return null
        if (email == null) {
            onLog("password fallback skipped: no stored email")
            return null
        }
        val last = store.getString(KEY_LAST_PASSWORD_ATTEMPT_MS)?.toLongOrNull() ?: 0L
        if (last > 0L && now - last in 0 until PASSWORD_FALLBACK_INTERVAL_MS) {
            onLog("password fallback skipped: throttled (last attempt ${(now - last) / 60_000L} min ago)")
            return null
        }
        if (shouldAbort()) {
            onLog("password fallback skipped: manual login pending")
            return false
        }
        store.putString(KEY_LAST_PASSWORD_ATTEMPT_MS, now.toString())
        val res = try {
            loginPassword(email, password)
        } catch (e: Exception) {
            WithingsLoginResult.Error(e.message ?: "Network error", WithingsAuthFailureKind.NETWORK)
        }
        onLog("password fallback result: ${res::class.simpleName}")
        return when (res) {
            is WithingsLoginResult.Ok -> {
                clearAuthPauseAndBackoff()
                onSilentRefreshOk(res, email)
                true
            }
            is WithingsLoginResult.NeedsMfa -> {
                pauseAutoRelogin(REASON_MFA_REQUIRED, MSG_FALLBACK_MFA)
                false
            }
            is WithingsLoginResult.Error -> {
                when (res.kind) {
                    WithingsAuthFailureKind.INVALID_CREDENTIALS -> {
                        store.delete(KEY_PASSWORD)
                        pauseAutoRelogin(REASON_INVALID_CREDENTIALS, MSG_FALLBACK_INVALID)
                    }
                    WithingsAuthFailureKind.RATE_LIMITED -> pauseAutoRelogin(REASON_RATE_LIMITED, res.message)
                    WithingsAuthFailureKind.NETWORK -> recordNetworkFailure(now)
                    WithingsAuthFailureKind.OTHER -> countOtherFailure(res.message)
                }
                false
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
        const val KEY_OTHER_AUTH_FAILURES = "withings.auth.other_failures"
        const val KEY_LAST_PASSWORD_ATTEMPT_MS = "withings.auth.last_password_attempt_ms"
        const val PASSWORD_FALLBACK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val MSG_FALLBACK_MFA =
            "Withings při automatickém přihlášení uloženým heslem vyžaduje ověřovací kód. Přihlaste se prosím ručně a zadejte kód."
        const val MSG_FALLBACK_INVALID =
            "Withings odmítl uložené heslo. Uložené heslo bylo smazáno – přihlaste se prosím ručně."
        const val MAX_CONSECUTIVE_OTHER_FAILURES = 3

        const val REASON_INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
        const val REASON_RATE_LIMITED = "RATE_LIMITED"
        const val REASON_MFA_REQUIRED = "MFA_REQUIRED"
        const val REASON_AUTH_ERROR = "AUTH_ERROR"

        const val FRESH_EXTEND_WINDOW_MS = 5 * 60 * 1000L
        const val BASE_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_MS = 30 * 60_000L
    }
}
