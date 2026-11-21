package app.pulse4.android.di

import app.pulse4.withings.WithingsExtendResult
import app.pulse4.withings.WithingsWebSession

interface WithingsAuthStateStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun delete(key: String)
}

/** Pure numbers of the re-authentication rate limiting; unit tested directly. */
object WithingsReauthPolicy {
    /**
     * Minimum gap between two background password logins, even after a success.
     * Withings rate-limits `account.withings.com/new_workflow/password_check` if it is hit every 1–2 hours,
     * which can lock out the user's web login too ("Too many failed login attempts").
     */
    const val PASSWORD_MIN_SPACING_MS = 6 * 60 * 60_000L

    /**
     * How long a prior rate-limit hit stays remembered across background successes so an alternating
     * `RATE_LIMITED -> 2h -> Ok -> session dies -> RATE_LIMITED` cycle still escalates to 6h / 24h / pause.
     */
    const val RATE_LIMIT_DECAY_MS = 24 * 60 * 60_000L

    /** After this many consecutive failed re-auth attempts the UI is told; background retries continue anyway. */
    const val ATTENTION_AFTER_FAILURES = 6

    /** Without a stored password, silent SSO is the only rung: give up (human needed) after this many real failures. */
    const val NO_PASSWORD_HARD_PAUSE_AFTER = 3

    /** HTTP 429 / lockout in a row before it is treated as "account locked" and the user is asked. */
    const val RATE_LIMIT_HARD_PAUSE_AFTER = 3

    /** Consecutive transient `extend` failures after which a (possibly misclassified) expired session is re-authenticated. */
    const val EXTEND_ESCALATE_AFTER_TRANSIENT = 3

    private val FAILURE_COOLDOWNS_MS = longArrayOf(15 * 60_000L, 60 * 60_000L, 3 * 60 * 60_000L, 6 * 60 * 60_000L)
    private val RATE_LIMIT_COOLDOWNS_MS = longArrayOf(2 * 60 * 60_000L, 6 * 60 * 60_000L, 24 * 60 * 60_000L)

    /** Cooldown after the [failures]-th consecutive failed attempt: 15 min, 1 h, 3 h, then 6 h. */
    fun failureCooldownMs(failures: Int): Long =
        FAILURE_COOLDOWNS_MS[(failures - 1).coerceIn(0, FAILURE_COOLDOWNS_MS.lastIndex)]

    /** Cooldown after the [hits]-th consecutive rate limit: 2 h, 6 h, then 24 h. */
    fun rateLimitCooldownMs(hits: Int): Long =
        RATE_LIMIT_COOLDOWNS_MS[(hits - 1).coerceIn(0, RATE_LIMIT_COOLDOWNS_MS.lastIndex)]
}

/**
 * Keeps the Withings web session alive and re-authenticates silently when it dies.
 *
 * Ladder when the session is invalid (each rung only when the previous one cannot help):
 * 1. `session?action=extend` (keepalive, cheap),
 * 2. silent SSO through the long-lived `account.withings.com` cookies,
 * 3. one login with the remembered e-mail + password.
 *
 * The user is prompted only when a human is really needed: a 2FA / e-mail code is demanded
 * ([REASON_MFA_REQUIRED]), Withings rejected the password ([REASON_INVALID_CREDENTIALS]), the account stays rate
 * limited / locked for days ([REASON_RATE_LIMITED]) or there is no stored password to renew with
 * ([REASON_AUTH_ERROR]). Those are persisted "hard pauses" that only a successful manual login clears.
 *
 * Everything else is TIME-BASED and self-healing, never a permanent latch (the previous design paused for good after
 * three unclassified failures and counted skipped attempts as failures, which silently killed background renewal and
 * made the user log in by hand again and again):
 * - network / server trouble: exponential backoff 1 -> 30 min ([recordNetworkFailure]);
 * - failed re-auth attempts (counted per REAL attempt only, never per skipped call): cooldown 15 min -> 1 h -> 3 h -> 6 h
 *   ([WithingsReauthPolicy.failureCooldownMs]); after [WithingsReauthPolicy.ATTENTION_AFTER_FAILURES] failures the UI is
 *   told ([needsHumanInput]) but retries continue every 6 h;
 * - HTTP 429 / lockout: cooldown 2 h -> 6 h -> 24 h, escalated to a hard pause after three in a row;
 * - password logins are spaced at least [WithingsReauthPolicy.PASSWORD_MIN_SPACING_MS] apart even after a success, so a
 *   session that keeps dying cannot turn into a login loop that hammers the account.
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
    /** Background login with the remembered password; must poll [shouldAbort] between HTTP requests. */
    private val loginPassword: (email: String, password: String) -> WithingsLoginResult,
    private val onSilentRefreshOk: (WithingsLoginResult.Ok, emailFallback: String?) -> Unit,
    /**
     * Called when the UI should ask the user (hard pause, or [WithingsReauthPolicy.ATTENTION_AFTER_FAILURES] failed
     * attempts in a row). Only a hard pause is persisted and stops background renewal.
     */
    private val onAuthPaused: (reason: String, message: String) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {},
    /** True when a manual (user) login is waiting; background renewal then stops between HTTP calls. */
    private val shouldAbort: () -> Boolean = { false },
) {
    fun pausedReason(): String? = store.getString(KEY_PAUSED_REASON)?.takeIf { it.isNotBlank() }

    fun pausedMessage(): String? = store.getString(KEY_PAUSED_MESSAGE)?.takeIf { it.isNotBlank() }

    /** True while a persisted HARD pause is active (a human is required). Timed cooldowns do not count. */
    fun isAutoReloginPaused(): Boolean = pausedReason() != null

    fun netBackoffUntilMs(): Long = store.getString(KEY_NET_BACKOFF_UNTIL_MS)?.toLongOrNull() ?: 0L

    fun reauthCooldownUntilMs(): Long = store.getString(KEY_REAUTH_COOLDOWN_UNTIL_MS)?.toLongOrNull() ?: 0L

    fun reauthFailures(): Int = store.getString(KEY_OTHER_AUTH_FAILURES)?.toIntOrNull() ?: 0

    /**
     * Whether the UI should prompt the user: a hard pause, or silent renewal failed so many times in a row that
     * something outside our control is wrong. Plain backoff / cooldown / throttling never prompts.
     */
    fun needsHumanInput(): Boolean =
        isAutoReloginPaused() || reauthFailures() >= WithingsReauthPolicy.ATTENTION_AFTER_FAILURES

    fun clearAuthPauseAndBackoff(keepPasswordSpacing: Boolean = false) {
        val now = nowMs()
        store.delete(KEY_PAUSED_REASON)
        store.delete(KEY_PAUSED_MESSAGE)
        store.delete(KEY_OTHER_AUTH_FAILURES)
        val lastRl = store.getString(KEY_LAST_RATE_LIMIT_MS)?.toLongOrNull() ?: 0L
        val keepRateLimitMemory = keepPasswordSpacing && lastRl > 0L && (now - lastRl) in 0 until WithingsReauthPolicy.RATE_LIMIT_DECAY_MS
        if (!keepRateLimitMemory) {
            store.delete(KEY_RATE_LIMIT_HITS)
            store.delete(KEY_LAST_RATE_LIMIT_MS)
        }
        store.delete(KEY_REAUTH_COOLDOWN_UNTIL_MS)
        store.delete(KEY_LAST_PASSWORD_ATTEMPT_MS)
        if (!keepPasswordSpacing) store.delete(KEY_PASSWORD_COOLDOWN_UNTIL_MS)
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

    private fun networkFailures(): Int = store.getString(KEY_NET_FAILURES)?.toIntOrNull() ?: 0

    private fun recordNetworkFailure(now: Long) {
        val failures = networkFailures() + 1
        val shift = (failures - 1).coerceIn(0, 5)
        val delayMs = (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
        store.putString(KEY_NET_FAILURES, failures.toString())
        store.putString(KEY_NET_BACKOFF_UNTIL_MS, (now + delayMs).toString())
        onLog("Network failure #$failures, backoff for ${delayMs / 1000L}s")
    }

    /** True když je uložená webová relace použitelná (má cookies / token a primární profil). */
    private fun hasValidSession(): Boolean = runCatching { loadSession()?.isConnected() == true }.getOrDefault(false)

    /**
     * Zaznamená výsledek ručního přihlášení v UI (R4-4).
     * - Úspěch odblokuje circuit-breaker.
     * - NeedsMfa / OTHER / NETWORK / ABORTED z ručního pokusu **nikdy** nepozastaví auto-relogin
     *   (zavřený dialog u kódu nebo špatný kód nesmí zastavit importy s platnou relací).
     * - INVALID_CREDENTIALS / RATE_LIMITED pozastaví auto-relogin jen tehdy, když relace není platná.
     * - Uložené heslo se smaže jen tehdy, když odmítnuté heslo [attemptedPassword] je **shodné** s uloženým
     *   (překlep v nově psaném hesle nesmaže dobré uložené heslo).
     */
    fun recordManualAuthResult(res: WithingsLoginResult, attemptedPassword: String? = null) {
        when (res) {
            is WithingsLoginResult.Ok -> clearAuthPauseAndBackoff()
            is WithingsLoginResult.NeedsMfa -> Unit
            is WithingsLoginResult.Error -> when (res.kind) {
                WithingsAuthFailureKind.INVALID_CREDENTIALS -> {
                    val stored = store.getString(KEY_PASSWORD)
                    if (!attemptedPassword.isNullOrEmpty() && stored == attemptedPassword) {
                        store.delete(KEY_PASSWORD)
                        onLog("Manual login: stored password rejected, deleted")
                    }
                    if (!hasValidSession()) pauseAutoRelogin(REASON_INVALID_CREDENTIALS, res.message)
                }
                WithingsAuthFailureKind.RATE_LIMITED -> {
                    if (!hasValidSession()) {
                        pauseAutoRelogin(REASON_RATE_LIMITED, res.message)
                    } else {
                        // Keep existing valid session running (R4-4), but cool down background re-auth so it does not pile on.
                        val now = nowMs()
                        store.putString(KEY_LAST_RATE_LIMIT_MS, now.toString())
                        store.putString(
                            KEY_REAUTH_COOLDOWN_UNTIL_MS,
                            (now + WithingsReauthPolicy.rateLimitCooldownMs(1)).toString(),
                        )
                    }
                }
                WithingsAuthFailureKind.OTHER,
                WithingsAuthFailureKind.NETWORK,
                WithingsAuthFailureKind.ABORTED -> Unit
            }
        }
    }

    /**
     * Keepalive + silent renewal entry point (app start, periodic worker, foreground service, import after a 401).
     * Returns true when a valid session exists afterwards.
     */
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
                    // A working session proves the earlier failures are history: lift the soft prompt too.
                    store.delete(KEY_OTHER_AUTH_FAILURES)
                    val lastRl = store.getString(KEY_LAST_RATE_LIMIT_MS)?.toLongOrNull() ?: 0L
                    if (lastRl <= 0L || (now - lastRl) !in 0 until WithingsReauthPolicy.RATE_LIMIT_DECAY_MS) {
                        store.delete(KEY_RATE_LIMIT_HITS)
                        store.delete(KEY_LAST_RATE_LIMIT_MS)
                    }
                    store.delete(KEY_REAUTH_COOLDOWN_UNTIL_MS)
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
                    // A server that keeps answering with something we cannot read is more likely a dead session
                    // than a long outage: after a few failures in a row climb the ladder (it is network-aware).
                    if (networkFailures() < WithingsReauthPolicy.EXTEND_ESCALATE_AFTER_TRANSIENT) return false
                    onLog("renewSession: ${networkFailures()} transient extend failures in a row, trying re-authentication")
                    return reauth(now, ignoreNetworkBackoff = true)
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
        return reauth(now, ignoreNetworkBackoff = false)
    }

    /** Silent re-authentication without prior extend: SSO cookies first, then the remembered password. */
    fun tryAutoReLogin(): Boolean = reauth(nowMs(), ignoreNetworkBackoff = false)

    private fun reauth(now: Long, ignoreNetworkBackoff: Boolean): Boolean {
        if (isAutoReloginPaused()) {
            onLog("tryAutoReLogin skipped: paused (${pausedReason()})")
            return false
        }
        if (!ignoreNetworkBackoff) {
            val backoffUntil = netBackoffUntilMs()
            if (now < backoffUntil) {
                onLog("tryAutoReLogin skipped: network backoff until $backoffUntil")
                return false
            }
        }
        val cooldownUntil = reauthCooldownUntilMs()
        if (now < cooldownUntil) {
            // A cooldown skip is NOT a failure: it must never count towards escalation.
            onLog("tryAutoReLogin skipped: cooldown for another ${(cooldownUntil - now) / 60_000L} min")
            return false
        }

        val email = store.getString(KEY_EMAIL)?.takeIf { it.isNotBlank() }
        val password = store.getString(KEY_PASSWORD)?.takeIf { it.isNotEmpty() }
        val cookies = hasUsableCookies()
        var ssoFailure: WithingsLoginResult.Error? = null

        if (cookies) {
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
                    clearAuthPauseAndBackoff(keepPasswordSpacing = true)
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
                        recordRateLimit(now, ssoRes.message)
                        return false
                    }
                    WithingsAuthFailureKind.INVALID_CREDENTIALS -> {
                        pauseAutoRelogin(REASON_INVALID_CREDENTIALS, ssoRes.message)
                        return false
                    }
                    WithingsAuthFailureKind.OTHER -> ssoFailure = ssoRes
                    WithingsAuthFailureKind.ABORTED -> {
                        onLog("tryAutoReLogin: SSO aborted")
                        return false
                    }
                }
            }
        }

        if (password != null && email != null) return tryPasswordLogin(now, email, password)

        // No stored password (or e-mail): silent SSO was the only rung and it did not help.
        val evidenceOfConnection = email != null || cookies || runCatching { loadSession() != null }.getOrDefault(false)
        if (!evidenceOfConnection) return false
        if (ssoFailure == null) {
            // Not even SSO cookies and nothing to log in with: automation is impossible, a human must log in.
            pauseAutoRelogin(REASON_AUTH_ERROR, MSG_NO_PASSWORD)
            return false
        }
        registerFailure(
            now,
            if (password == null) MSG_NO_PASSWORD else ssoFailure.message,
            hardPauseAt = WithingsReauthPolicy.NO_PASSWORD_HARD_PAUSE_AFTER,
        )
        return false
    }

    /** One login with the remembered password. Spaced by [WithingsReauthPolicy.PASSWORD_MIN_SPACING_MS]. */
    private fun tryPasswordLogin(now: Long, email: String, password: String): Boolean {
        val spacingUntil = store.getString(KEY_PASSWORD_COOLDOWN_UNTIL_MS)?.toLongOrNull() ?: 0L
        if (now < spacingUntil) {
            // Skipped, not failed: the session just kept dying right after a login, wait for the spacing to pass.
            onLog("password login skipped: spaced (${(spacingUntil - now) / 60_000L} min left)")
            return false
        }
        if (shouldAbort()) {
            onLog("password login skipped: manual login pending")
            return false
        }
        val previousSpacing = store.getString(KEY_PASSWORD_COOLDOWN_UNTIL_MS)
        val res = try {
            loginPassword(email, password)
        } catch (e: Exception) {
            WithingsLoginResult.Error(e.message ?: "Network error", WithingsAuthFailureKind.NETWORK)
        }
        onLog("password login result: ${res::class.simpleName}")
        if (res is WithingsLoginResult.Error && res.kind == WithingsAuthFailureKind.ABORTED) {
            // Přerušeno před odesláním hesla (ruční přihlášení čeká): nepočítat jako pokus ani jako selhání.
            if (previousSpacing != null) store.putString(KEY_PASSWORD_COOLDOWN_UNTIL_MS, previousSpacing)
            else store.delete(KEY_PASSWORD_COOLDOWN_UNTIL_MS)
            onLog("password login aborted: manual login pending")
            return false
        }
        return when (res) {
            is WithingsLoginResult.Ok -> {
                // Enforce 6h spacing after a successful background password login so a session that keeps dying
                // every 60-90 min cannot turn into a repeated password-login loop that locks out the account.
                store.putString(KEY_PASSWORD_COOLDOWN_UNTIL_MS, (now + WithingsReauthPolicy.PASSWORD_MIN_SPACING_MS).toString())
                clearAuthPauseAndBackoff(keepPasswordSpacing = true)
                onSilentRefreshOk(res, email)
                true
            }
            is WithingsLoginResult.NeedsMfa -> {
                // A code can only come from the human.
                pauseAutoRelogin(REASON_MFA_REQUIRED, MSG_FALLBACK_MFA)
                false
            }
            is WithingsLoginResult.Error -> {
                // Failed attempts already carry their own cooldowns (15m..6h for OTHER, 2h/6h/24h for RATE_LIMITED,
                // 1m..30m for NETWORK); keep a short 10m floor only against accidental tight loops.
                when (res.kind) {
                    WithingsAuthFailureKind.INVALID_CREDENTIALS -> {
                        store.delete(KEY_PASSWORD)
                        pauseAutoRelogin(REASON_INVALID_CREDENTIALS, MSG_FALLBACK_INVALID)
                    }
                    WithingsAuthFailureKind.RATE_LIMITED -> recordRateLimit(now, res.message)
                    WithingsAuthFailureKind.NETWORK -> recordNetworkFailure(now)
                    WithingsAuthFailureKind.OTHER -> registerFailure(now, res.message)
                    WithingsAuthFailureKind.ABORTED -> Unit
                }
                false
            }
        }
    }

    /**
     * A real, unclassified failed re-auth attempt: cooldown 15 min / 1 h / 3 h / 6 h. Never a permanent latch; after
     * [WithingsReauthPolicy.ATTENTION_AFTER_FAILURES] failures the UI is informed while retries go on.
     * [hardPauseAt] (no stored password) turns the count into a persisted hard pause.
     */
    private fun registerFailure(now: Long, message: String, hardPauseAt: Int? = null) {
        val failures = reauthFailures() + 1
        store.putString(KEY_OTHER_AUTH_FAILURES, failures.toString())
        val cooldown = WithingsReauthPolicy.failureCooldownMs(failures)
        store.putString(KEY_REAUTH_COOLDOWN_UNTIL_MS, (now + cooldown).toString())
        onLog("Re-auth failure #$failures, next attempt in ${cooldown / 60_000L} min")
        when {
            hardPauseAt != null && failures >= hardPauseAt -> pauseAutoRelogin(REASON_AUTH_ERROR, message)
            failures == WithingsReauthPolicy.ATTENTION_AFTER_FAILURES -> onAuthPaused(REASON_AUTH_ERROR, MSG_REPEATED_FAILURES)
        }
    }

    /** HTTP 429 / lockout: cooldown 2 h / 6 h / 24 h; the third in a row is treated as a locked account. */
    private fun recordRateLimit(now: Long, message: String) {
        val hits = (store.getString(KEY_RATE_LIMIT_HITS)?.toIntOrNull() ?: 0) + 1
        store.putString(KEY_RATE_LIMIT_HITS, hits.toString())
        store.putString(KEY_LAST_RATE_LIMIT_MS, now.toString())
        if (hits >= WithingsReauthPolicy.RATE_LIMIT_HARD_PAUSE_AFTER) {
            pauseAutoRelogin(REASON_RATE_LIMITED, message)
            return
        }
        val cooldown = WithingsReauthPolicy.rateLimitCooldownMs(hits)
        store.putString(KEY_REAUTH_COOLDOWN_UNTIL_MS, (now + cooldown).toString())
        onLog("Rate limited #$hits, no re-auth attempt for ${cooldown / 60_000L} min")
    }

    companion object {
        const val KEY_EMAIL = "withings.web.email"
        const val KEY_PASSWORD = "withings.web.password"
        const val KEY_PAUSED_REASON = "withings.auth.paused_reason"
        const val KEY_PAUSED_MESSAGE = "withings.auth.paused_message"
        const val KEY_NET_FAILURES = "withings.auth.net_failures"
        const val KEY_NET_BACKOFF_UNTIL_MS = "withings.auth.net_backoff_until_ms"

        /** Consecutive failed re-auth attempts (real attempts only). Key name kept for stored-state compatibility. */
        const val KEY_OTHER_AUTH_FAILURES = "withings.auth.other_failures"
        const val KEY_REAUTH_COOLDOWN_UNTIL_MS = "withings.auth.reauth_cooldown_until_ms"
        const val KEY_PASSWORD_COOLDOWN_UNTIL_MS = "withings.auth.password_cooldown_until_ms"
        const val KEY_RATE_LIMIT_HITS = "withings.auth.rate_limit_hits"
        const val KEY_LAST_RATE_LIMIT_MS = "withings.auth.last_rate_limit_ms"

        /** Legacy (6 h throttle of the old design); only deleted now. */
        const val KEY_LAST_PASSWORD_ATTEMPT_MS = "withings.auth.last_password_attempt_ms"

        const val MSG_FALLBACK_MFA =
            "Withings při automatickém přihlášení uloženým heslem vyžaduje ověřovací kód. Přihlaste se prosím ručně a zadejte kód."
        const val MSG_FALLBACK_INVALID =
            "Withings odmítl uložené heslo. Uložené heslo bylo smazáno – přihlaste se prosím ručně."
        const val MSG_NO_PASSWORD =
            "Relace Withings vypršela a není uloženo heslo, kterým by šla tiše obnovit. Přihlaste se prosím a ponechte zapnuté „Pamatovat heslo“."
        const val MSG_REPEATED_FAILURES =
            "Tiché obnovení relace Withings se opakovaně nezdařilo. Aplikace to zkouší dál sama, můžete se také přihlásit ručně."

        const val REASON_INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
        const val REASON_RATE_LIMITED = "RATE_LIMITED"
        const val REASON_MFA_REQUIRED = "MFA_REQUIRED"
        const val REASON_AUTH_ERROR = "AUTH_ERROR"

        const val FRESH_EXTEND_WINDOW_MS = 5 * 60 * 1000L
        const val BASE_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_MS = 30 * 60_000L
    }
}
