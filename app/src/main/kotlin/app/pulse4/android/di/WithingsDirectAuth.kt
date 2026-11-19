package app.pulse4.android.di

import app.pulse4.domain.PLog
import app.pulse4.sync.Wire
import app.pulse4.withings.WITHINGS_WEB_APPLIVER
import app.pulse4.withings.WithingsUserProfile
import app.pulse4.withings.WithingsWebClient
import app.pulse4.withings.WithingsWebSession
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

enum class WithingsAuthFailureKind {
    INVALID_CREDENTIALS,
    RATE_LIMITED,
    NETWORK,
    OTHER,
}

sealed interface WithingsLoginResult {
    data class Ok(val session: WithingsWebSession, val profiles: List<WithingsUserProfile>) : WithingsLoginResult
    data class NeedsMfa(val email: String, val message: String) : WithingsLoginResult
    data class Error(
        val message: String,
        val kind: WithingsAuthFailureKind = WithingsAuthFailureKind.OTHER,
    ) : WithingsLoginResult
}

@Serializable
private data class PersistedCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAt: Long = 253402300799999L,
    val secure: Boolean = true,
    val httpOnly: Boolean = false,
    val hostOnly: Boolean = false,
) {
    fun toOkHttpCookie(): Cookie? = runCatching {
        Cookie.Builder()
            .name(name)
            .value(value)
            .apply {
                if (hostOnly) hostOnlyDomain(domain) else domain(domain)
                path(path)
                expiresAt(expiresAt)
                if (secure) secure()
                if (httpOnly) httpOnly()
            }
            .build()
    }.getOrNull()

    companion object {
        fun from(c: Cookie): PersistedCookie = PersistedCookie(
            name = c.name,
            value = c.value,
            domain = c.domain,
            path = c.path,
            expiresAt = c.expiresAt,
            secure = c.secure,
            httpOnly = c.httpOnly,
            hostOnly = c.hostOnly
        )
    }
}

private class PersistentCookieJar(
    private val loadPersisted: () -> String? = { null },
    private val savePersisted: (String) -> Unit = {},
) : CookieJar {
    private val store = mutableListOf<Cookie>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = loadPersisted()?.takeIf { it.isNotBlank() } ?: return
        val now = System.currentTimeMillis()
        val parsed = runCatching {
            Wire.json.decodeFromString(ListSerializer(PersistedCookie.serializer()), raw)
        }.getOrNull() ?: return
        for (p in parsed) {
            if (p.expiresAt > now && p.value.isNotBlank() && p.value != "deleted") {
                p.toOkHttpCookie()?.let { store.add(it) }
            }
        }
    }

    @Synchronized
    fun persistNow() {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val toSave = store
            .filter { it.expiresAt > now && it.value.isNotBlank() && it.value != "deleted" }
            .map { PersistedCookie.from(it) }
        val encoded = runCatching {
            Wire.json.encodeToString(ListSerializer(PersistedCookie.serializer()), toSave)
        }.getOrNull() ?: return
        savePersisted(encoded)
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        ensureLoaded()
        val now = System.currentTimeMillis()
        for (c in cookies) {
            store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.value == "deleted" || c.expiresAt <= now) {
                store.removeAll { it.name == c.name && it.domain == c.domain }
            } else if (c.value.isNotEmpty()) {
                store.add(c)
            }
        }
        persistNow()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        ensureLoaded()
        val now = System.currentTimeMillis()
        store.removeAll { it.expiresAt < now || it.value == "deleted" }
        return store.filter { it.matches(url) }
    }

    @Synchronized
    fun getCookieString(): String {
        ensureLoaded()
        val byKey = LinkedHashMap<Triple<String, String, String>, String>()
        for (c in store) {
            if (c.value.isNotBlank() && c.value != "deleted") {
                byKey[Triple(c.domain, c.path, c.name)] = c.value
            }
        }
        return byKey.entries.joinToString("; ") { "${it.key.third}=${it.value}" }
    }

    @Synchronized
    fun getSessionToken(): String? {
        ensureLoaded()
        // Pouze skutečný `session_token`. Hodnota `session_key` / `sessionid` je identifikátor relace,
        // který server čte z cookie – nesmí se vydávat za session_token.
        return store.lastOrNull { it.name == "session_token" && it.value.isNotBlank() && it.value != "deleted" }?.value
    }

    @Synchronized
    fun describe(): String {
        ensureLoaded()
        return store.joinToString(", ") { "${it.name}@${it.domain}" }
    }

    @Synchronized
    fun hasSession(): Boolean {
        ensureLoaded()
        return store.any {
            (it.name == "sessionid" || it.name == "session_token" || it.name == "session_key") &&
                it.value.isNotBlank() && it.value != "deleted"
        }
    }

    @Synchronized
    fun hasAnyCookies(): Boolean {
        ensureLoaded()
        val now = System.currentTimeMillis()
        return store.any { it.expiresAt > now && it.value.isNotBlank() && it.value != "deleted" }
    }

    @Synchronized
    fun hasWorkflowCookie(): Boolean {
        ensureLoaded()
        return store.any { it.name == "w_uuid" && it.value.isNotBlank() }
    }

    @Synchronized
    fun setEmailCookie(email: String) {
        ensureLoaded()
        val encoded = java.net.URLEncoder.encode(email, "UTF-8")
        val cookie = Cookie.Builder()
            .name("email")
            .value(encoded)
            .domain("withings.com")
            .path("/")
            .secure()
            .build()
        store.removeAll { it.name == "email" }
        store.add(cookie)
    }

    /**
     * Odstraní pouze expirovaný aplikační `session_key`/`sessionid`/`session_token` a dočasné přihlašovací
     * workflow cookies (`signin_authorize_state`, `email`), ale ZACHOVÁ dlouhodobé důvěryhodné cookies
     * zařízení a SSO (`w_uuid`, `remember*`, `account*`, apod.), aby server nevyžadoval znovu 2FA.
     */
    @Synchronized
    fun clearSessionAndWorkflowCookies() {
        ensureLoaded()
        val transientNames = setOf(
            "session_key", "sessionid", "session_token",
            "signin_authorize_state", "email"
        )
        store.removeAll { it.name in transientNames }
    }

    @Synchronized
    fun clear() {
        loaded = true
        store.clear()
        savePersisted("[]")
    }
}

/**
 * Přímé přihlášení k Withings v pozadí bez nutnosti otevírat WebView.
 * Uživateli se zobrazují pouze nativní pole v tmavém motivu aplikace Pulse4.
 * Extrahuje skrytá CSRF/stavová pole z HTML formulářů, uchovává dlouhodobé cookies zařízení
 * v šifrovaném úložišti a zachytává přesměrování na 2FA.
 */
class WithingsDirectAuth(
    private val webClient: WithingsWebClient,
    loadPersistedCookies: () -> String? = { null },
    savePersistedCookies: (String) -> Unit = {},
    private val diagnostics: WithingsAuthDiagnostics? = null,
) {
    private val cookieJar = PersistentCookieJar(loadPersistedCookies, savePersistedCookies)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile var pendingEmail: String? = null
        private set
    @Volatile var isAwaitingCode: Boolean = false
        private set

    private var lastMfaUrl: String = "https://account.withings.com/new_workflow/email_check?r=https%3A%2F%2Fapp.withings.com%2F"
    private var lastHiddenFields: Map<String, String> = emptyMap()
    private val ua = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    private fun trace(flow: WithingsAuthDiagnostics.Flow, step: String, res: okhttp3.Response) {
        val finalUrl = res.request.url
        diagnostics?.step(
            flow = flow,
            step = step,
            url = finalUrl.toString(),
            status = res.code,
            toApp = finalUrl.host.equals("app.withings.com", ignoreCase = true),
        )
    }

    private inline fun traced(flow: WithingsAuthDiagnostics.Flow, block: () -> WithingsLoginResult): WithingsLoginResult {
        diagnostics?.mark(flow, "start")
        val r = block()
        diagnostics?.result(flow, r)
        return r
    }

    private fun traceException(flow: WithingsAuthDiagnostics.Flow, e: Throwable) {
        diagnostics?.mark(flow, "exception:${e::class.java.simpleName}")
    }

    private fun isLoggedInUrl(url: HttpUrl): Boolean {
        return cookieJar.hasSession() || url.host.equals("app.withings.com", ignoreCase = true)
    }

    private fun parseHiddenInputs(html: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val inputRegex = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)
        val typeHidden = Regex("""\btype\s*=\s*["']hidden["']""", RegexOption.IGNORE_CASE)
        val nameAttr = Regex("""\bname\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val valAttr = Regex("""\bvalue\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

        for (match in inputRegex.findAll(html)) {
            val tag = match.value
            if (!typeHidden.containsMatchIn(tag)) continue
            val name = nameAttr.find(tag)?.groupValues?.getOrNull(1)?.trim() ?: continue
            if (name.isEmpty()) continue
            val value = valAttr.find(tag)?.groupValues?.getOrNull(1) ?: ""
            out[name] = value
        }
        return out
    }

    /**
     * Zjistí, zda HTML obsahuje skutečnou viditelnou chybovou hlášku (nikoli prázdnou šablonu `<div class="alert alert-danger hide"></div>`).
     */
    private fun hasVisibleAlertError(html: String): Boolean {
        val visibleAlertRegex = Regex("""<div\s+class=["']alert\s+alert-danger["']\s*>\s*<li>""", RegexOption.IGNORE_CASE)
        return visibleAlertRegex.containsMatchIn(html)
    }

    fun clearPersistedCookies() {
        cookieJar.clear()
        isAwaitingCode = false
    }

    fun hasUsableCookies(): Boolean = cookieJar.hasAnyCookies()

    fun clearPendingPassword() = Unit

    /**
     * Tiché obnovení webové relace Withings pomocí uložených dlouhodobých SSO / důvěryhodných cookies
     * na `account.withings.com`. Funguje i bez hesla (např. po přihlášení přes 8místný kód nebo Google/Apple),
     * dokud platí SSO relace na account.withings.com.
     */
    fun trySilentSsoRefresh(shouldAbort: () -> Boolean = { false }): WithingsLoginResult =
        traced(WithingsAuthDiagnostics.Flow.SILENT_SSO) { trySilentSsoRefreshInner(shouldAbort) }

    private fun trySilentSsoRefreshInner(shouldAbort: () -> Boolean): WithingsLoginResult {
        val flow = WithingsAuthDiagnostics.Flow.SILENT_SSO
        if (!cookieJar.hasAnyCookies()) {
            return WithingsLoginResult.Error("Žádné uložené cookies pro tiché obnovení.", WithingsAuthFailureKind.OTHER)
        }
        cookieJar.clearSessionAndWorkflowCookies()
        return try {
            val urls = listOf(
                "https://account.withings.com/new_workflow/login?r=https%3A%2F%2Fapp.withings.com%2F",
                "https://account.withings.com/connection/login?r=https%3A%2F%2Fapp.withings.com%2F"
            )
            var lastStatus = 200
            for (u in urls) {
                if (shouldAbort()) {
                    diagnostics?.mark(flow, "aborted:manual_login")
                    return WithingsLoginResult.Error(ABORTED_MESSAGE, WithingsAuthFailureKind.OTHER)
                }
                val req = Request.Builder()
                    .url(u)
                    .header("User-Agent", ua)
                    .get()
                    .build()
                client.newCall(req).execute().use { res ->
                    lastStatus = res.code
                    trace(flow, "GET sso", res)
                    val finalHttpUrl = res.request.url
                    if (finalHttpUrl.host.equals("app.withings.com", ignoreCase = true) || cookieJar.hasSession()) {
                        PLog.i("WithingsDirectAuth") { "trySilentSsoRefresh: SSO redirect succeeded via ${finalHttpUrl.encodedPath}" }
                        val fin = finishLogin(flow)
                        if (fin is WithingsLoginResult.Ok) return fin
                    }
                }
            }
            when {
                lastStatus == 429 -> WithingsLoginResult.Error("Withings dočasně omezil požadavky (HTTP 429).", WithingsAuthFailureKind.RATE_LIMITED)
                lastStatus >= 500 -> WithingsLoginResult.Error("Server Withings je dočasně nedostupný (HTTP $lastStatus).", WithingsAuthFailureKind.NETWORK)
                else -> WithingsLoginResult.Error("Tiché SSO obnovení nevyústilo v aktivní relaci.", WithingsAuthFailureKind.OTHER)
            }
        } catch (e: IOException) {
            traceException(flow, e)
            PLog.w("WithingsDirectAuth", e) { "trySilentSsoRefresh network exception: ${e.message}" }
            WithingsLoginResult.Error(e.message ?: "Chyba sítě při tichém obnovení relace.", WithingsAuthFailureKind.NETWORK)
        } catch (e: Exception) {
            traceException(flow, e)
            PLog.w("WithingsDirectAuth", e) { "trySilentSsoRefresh exception: ${e.message}" }
            WithingsLoginResult.Error(e.message ?: "Chyba při tichém obnovení relace.", WithingsAuthFailureKind.OTHER)
        }
    }

    /**
     * Vyžádá zaslání 8místného přihlašovacího kódu na e-mail (funguje pro účty Google, Apple i účty bez hesla).
     * Vše probíhá na pozadí bez WebView.
     */
    fun requestEmailCode(email: String): WithingsLoginResult =
        traced(WithingsAuthDiagnostics.Flow.EMAIL_CODE) { requestEmailCodeInner(email) }

    private fun requestEmailCodeInner(email: String): WithingsLoginResult {
        val flow = WithingsAuthDiagnostics.Flow.EMAIL_CODE
        cookieJar.clearSessionAndWorkflowCookies()
        val em = email.trim()
        if (em.isBlank() || !em.contains("@")) {
            return WithingsLoginResult.Error("Zadejte platnou e-mailovou adresu vašeho účtu Withings (nebo Google / Apple účtu).")
        }
        pendingEmail = em

        return try {
            val loginUrl = "https://account.withings.com/new_workflow/login?r=https%3A%2F%2Fapp.withings.com%2F"
            val req1 = Request.Builder()
                .url(loginUrl)
                .header("User-Agent", ua)
                .get()
                .build()
            val html1 = client.newCall(req1).execute().use { trace(flow, "GET login", it); it.body?.string().orEmpty() }
            val hidden1 = parseHiddenInputs(html1)

            val bodyEmailBuilder = FormBody.Builder()
            hidden1.forEach { (k, v) -> if (k != "email" && k.isNotBlank()) bodyEmailBuilder.add(k, v) }
            bodyEmailBuilder.add("email", em)

            val req2 = Request.Builder()
                .url(loginUrl)
                .header("User-Agent", ua)
                .header("Origin", "https://account.withings.com")
                .header("Referer", loginUrl)
                .post(bodyEmailBuilder.build())
                .build()

            val (status2, html2, finalHttpUrl2) = client.newCall(req2).execute().use { res2 ->
                trace(flow, "POST login", res2)
                Triple(res2.code, res2.body?.string().orEmpty(), res2.request.url)
            }
            val finalUrl2 = finalHttpUrl2.toString()

            if (isLoggedInUrl(finalHttpUrl2)) {
                return finishLogin(flow)
            }
            if (status2 == 429 || RATE_LIMIT_PATTERNS.any { html2.lowercase().contains(it) }) {
                return WithingsLoginResult.Error(
                    "Withings dočasně omezil požadavky (příliš mnoho pokusů). Počkejte prosím několik minut.",
                    WithingsAuthFailureKind.RATE_LIMITED
                )
            }
            if (status2 >= 500) {
                return WithingsLoginResult.Error(
                    "Server Withings je dočasně nedostupný (HTTP $status2).",
                    WithingsAuthFailureKind.NETWORK
                )
            }
            if (hasVisibleAlertError(html2) && !finalUrl2.contains("check") && !finalUrl2.contains("totp")) {
                return WithingsLoginResult.Error(
                    "Withings odmítl odeslání přihlašovacího kódu. Zkontrolujte e-mail nebo zkuste později.",
                    WithingsAuthFailureKind.OTHER
                )
            }

            lastMfaUrl = if (finalUrl2.contains("check") || finalUrl2.contains("totp")) {
                finalUrl2
            } else {
                "https://account.withings.com/new_workflow/email_check?r=https%3A%2F%2Fapp.withings.com%2F"
            }
            lastHiddenFields = parseHiddenInputs(html2)

            isAwaitingCode = true
            WithingsLoginResult.NeedsMfa(
                email = em,
                message = "Na e-mail $em byl odeslán 8místný přihlašovací kód Withings. Zadejte ho do pole níže."
            )
        } catch (e: IOException) {
            traceException(flow, e)
            WithingsLoginResult.Error("Nepodařilo se spojit se serverem Withings. Zkontrolujte připojení a zkuste to znovu.", WithingsAuthFailureKind.NETWORK)
        } catch (e: Exception) {
            traceException(flow, e)
            WithingsLoginResult.Error("Nepodařilo se vyžádat přihlašovací kód.", WithingsAuthFailureKind.OTHER)
        }
    }

    /**
     * Znovu odešle 8místný přihlašovací kód na e-mail přes `scalews.withings.com/cgi-bin/v2/account` (`createshortcode`).
     */
    fun resendEmailCode(emailFallback: String? = null): WithingsLoginResult {
        val em = pendingEmail?.takeIf { it.isNotBlank() }
            ?: emailFallback?.trim()?.takeIf { it.isNotBlank() }
            ?: return WithingsLoginResult.Error("Nejprve zadejte e-mail.")
        pendingEmail = em
        return try {
            if (!cookieJar.hasWorkflowCookie()) {
                return requestEmailCode(em)
            }
            val form = FormBody.Builder()
                .add("action", "createshortcode")
                .add("email", em)
                .add("appfsm", "web")
                .add("redirect_uri", lastMfaUrl)
                .add("no_jwt", "false")
                .add("appname", "hmw")
                .add("apppfm", "web")
                .add("appliver", WITHINGS_WEB_APPLIVER)
                .build()
            val req = Request.Builder()
                .url("https://scalews.withings.com/cgi-bin/v2/account")
                .header("User-Agent", ua)
                .header("Origin", "https://account.withings.com")
                .header("Referer", "https://account.withings.com/")
                .post(form)
                .build()
            val (code, body) = client.newCall(req).execute().use { res ->
                res.code to res.body?.string().orEmpty()
            }
            if (code == 429) {
                return WithingsLoginResult.Error("Withings dočasně omezil odesílání kódů. Počkejte prosím několik minut.", WithingsAuthFailureKind.RATE_LIMITED)
            }
            if (code !in 200..299 || body.contains("\"status\":") && !body.contains("\"status\":0")) {
                return WithingsLoginResult.Error("Nepodařilo se znovu odeslat kód. Zkuste to za chvíli znovu.", WithingsAuthFailureKind.OTHER)
            }
            isAwaitingCode = true
            WithingsLoginResult.NeedsMfa(em, "Nový 8místný kód byl odeslán na $em. Zadejte ho do pole níže.")
        } catch (e: IOException) {
            WithingsLoginResult.Error("Nepodařilo se spojit se serverem Withings. Zkontrolujte připojení a zkuste to znovu.", WithingsAuthFailureKind.NETWORK)
        } catch (e: Exception) {
            WithingsLoginResult.Error("Nepodařilo se znovu odeslat kód.", WithingsAuthFailureKind.OTHER)
        }
    }

    /** [background] = přihlášení zapamatovaným heslem z obnovy na pozadí (jen pro diagnostiku). */
    fun loginPassword(email: String, password: String, background: Boolean = false): WithingsLoginResult {
        val flow = if (background) WithingsAuthDiagnostics.Flow.BACKGROUND_PASSWORD else WithingsAuthDiagnostics.Flow.MANUAL_PASSWORD
        return traced(flow) { loginPasswordInner(email, password, flow) }
    }

    private fun loginPasswordInner(email: String, password: String, flow: WithingsAuthDiagnostics.Flow): WithingsLoginResult {
        if (password.isBlank()) {
            return WithingsLoginResult.Error("Zadejte heslo k účtu Withings.")
        }
        cookieJar.clearSessionAndWorkflowCookies()
        val em = email.trim()
        pendingEmail = em
        PLog.i("WithingsDirectAuth") { "loginPassword starting" }

        return try {
            val loginUrl = "https://account.withings.com/new_workflow/login?r=https%3A%2F%2Fapp.withings.com%2F"
            // 1. Zahájení relace – načte výchozí w_uuid, signin_authorize_state a workflow cookies
            val req1 = Request.Builder()
                .url(loginUrl)
                .header("User-Agent", ua)
                .get()
                .build()
            client.newCall(req1).execute().use { res1 ->
                trace(flow, "GET login", res1)
                if (isLoggedInUrl(res1.request.url)) {
                    PLog.i("WithingsDirectAuth") { "loginPassword: req1 SSO already succeeded" }
                    val fin = finishLogin(flow)
                    if (fin is WithingsLoginResult.Ok) return fin
                }
            }

            // 2. Nastavíme cookie `email` přímo v cookieJar bez odeslání POST /login
            // Tím Withings nepošle zbytečný 8místný OTP kód do schránky, pokud je heslo platné!
            cookieJar.setEmailCookie(em)

            // 3. Načtení stránky password_check a odeslání hesla
            val passCheckUrl = "https://account.withings.com/new_workflow/password_check?r=https%3A%2F%2Fapp.withings.com%2F"
            val passGetReq = Request.Builder()
                .url(passCheckUrl)
                .header("User-Agent", ua)
                .header("Referer", loginUrl)
                .get()
                .build()
            val passGetHtml = client.newCall(passGetReq).execute().use { trace(flow, "GET password_check", it); it.body?.string().orEmpty() }
            val hiddenPass = parseHiddenInputs(passGetHtml)

            val bodyPassBuilder = FormBody.Builder()
            hiddenPass.forEach { (k, v) -> if (k != "password" && k != "email" && k.isNotBlank()) bodyPassBuilder.add(k, v) }
            bodyPassBuilder.add("password", password)

            val req3 = Request.Builder()
                .url(passCheckUrl)
                .header("User-Agent", ua)
                .header("Origin", "https://account.withings.com")
                .header("Referer", passCheckUrl)
                .post(bodyPassBuilder.build())
                .build()

            val res3 = client.newCall(req3).execute()
            trace(flow, "POST password_check", res3)
            val statusCode = res3.code
            val bodyText = res3.body?.string().orEmpty()
            val finalHttpUrl = res3.request.url
            val finalUrl = finalHttpUrl.toString()
            res3.close()

            PLog.i("WithingsDirectAuth") { "loginPassword POST response: status=$statusCode, finalPath=${finalHttpUrl.encodedPath}, hasSession=${cookieJar.hasSession()}" }

            if (isLoggedInUrl(finalHttpUrl)) {
                return finishLogin(flow)
            }

            val classified = classifyPasswordLoginResponse(
                statusCode = statusCode,
                finalUrl = finalUrl,
                bodyText = bodyText,
                email = em
            )
            if (classified is WithingsLoginResult.NeedsMfa) {
                lastMfaUrl = if (finalUrl.contains("check") || finalUrl.contains("totp")) {
                    finalUrl
                } else {
                    "https://account.withings.com/new_workflow/email_check?r=https%3A%2F%2Fapp.withings.com%2F"
                }
                lastHiddenFields = parseHiddenInputs(bodyText)
                isAwaitingCode = true
                PLog.i("WithingsDirectAuth") { "loginPassword requires 2FA" }
                return classified
            }
            classified
        } catch (e: IOException) {
            traceException(flow, e)
            PLog.w("WithingsDirectAuth", e) { "loginPassword network exception" }
            WithingsLoginResult.Error("Nepodařilo se spojit se serverem Withings. Zkontrolujte připojení a zkuste to znovu.", WithingsAuthFailureKind.NETWORK)
        } catch (e: Exception) {
            traceException(flow, e)
            PLog.e("WithingsDirectAuth", e) { "loginPassword exception" }
            WithingsLoginResult.Error("Přihlášení se nezdařilo. Zkuste to prosím znovu.", WithingsAuthFailureKind.OTHER)
        }
    }

    companion object {
        const val ABORTED_MESSAGE = "Tiché obnovení přerušeno kvůli ručnímu přihlášení."

        private val RATE_LIMIT_PATTERNS = listOf(
            "too many",
            "too_many_attempts",
            "rate limit",
            "try again later",
            "temporarily locked",
            "account locked",
            "account is locked",
            "temporarily blocked",
            "trop de tentatives",
            "příliš mnoho pokusů",
        )

        private val INVALID_CREDENTIALS_PATTERNS = listOf(
            "wrong username or password",
            "invalid email or password",
            "incorrect password",
            "wrong password",
            "mot de passe incorrect",
            "identifiants invalides",
        )

        private fun hasVisibleAlert(html: String): Boolean {
            val visibleAlertRegex = Regex("""<div\s+class=["']alert\s+alert-danger["']\s*>\s*<li>""", RegexOption.IGNORE_CASE)
            return visibleAlertRegex.containsMatchIn(html)
        }

        internal fun classifyPasswordLoginResponse(
            statusCode: Int,
            finalUrl: String,
            bodyText: String,
            email: String,
        ): WithingsLoginResult {
            val lower = bodyText.lowercase()
            if (statusCode == 429 || RATE_LIMIT_PATTERNS.any { lower.contains(it) }) {
                return WithingsLoginResult.Error(
                    "Withings dočasně zablokoval přihlášení kvůli příliš mnoha pokusům. Počkejte prosím a přihlaste se později ručně.",
                    WithingsAuthFailureKind.RATE_LIMITED,
                )
            }

            if (INVALID_CREDENTIALS_PATTERNS.any { lower.contains(it) } ||
                (finalUrl.contains("password_check", ignoreCase = true) && hasVisibleAlert(bodyText))
            ) {
                return WithingsLoginResult.Error(
                    "Withings heslo odmítl. Zkontrolujte heslo; pokud k účtu žádné nemáte (přihlášení přes Google / Apple), založte si ho na account.withings.com přes „Zapomenuté heslo“.",
                    WithingsAuthFailureKind.INVALID_CREDENTIALS,
                )
            }

            if (finalUrl.contains("email_check", ignoreCase = true) ||
                finalUrl.contains("phone_check", ignoreCase = true) ||
                finalUrl.contains("totp", ignoreCase = true) ||
                bodyText.contains("Identification code", ignoreCase = true) ||
                bodyText.contains("code-wrapper") ||
                bodyText.contains("name=\"code\"")
            ) {
                return WithingsLoginResult.NeedsMfa(
                    email,
                    "Withings zaslal ověřovací kód na váš e-mail nebo v SMS. Zadejte ho do pole níže.",
                )
            }

            if (statusCode >= 500) {
                return WithingsLoginResult.Error(
                    "Server Withings je dočasně nedostupný (HTTP $statusCode).",
                    WithingsAuthFailureKind.NETWORK,
                )
            }

            return WithingsLoginResult.Error(
                "Přihlášení heslem se nezdařilo. Zkontrolujte e-mail a heslo k účtu Withings.",
                WithingsAuthFailureKind.OTHER,
            )
        }
    }

    fun resumeMfa(code: String, emailFallback: String? = null): WithingsLoginResult =
        traced(WithingsAuthDiagnostics.Flow.MFA_CODE) { resumeMfaInner(code, emailFallback) }

    private fun resumeMfaInner(code: String, emailFallback: String?): WithingsLoginResult {
        val flow = WithingsAuthDiagnostics.Flow.MFA_CODE
        val em = emailFallback?.trim()?.takeIf { it.isNotBlank() }
            ?: pendingEmail?.takeIf { it.isNotBlank() }
            ?: return WithingsLoginResult.Error("Zadejte prosím i svůj e-mail účtu Withings.")
        pendingEmail = em
        val cleanedCode = code.trim().replace(" ", "").replace("-", "")
        if (cleanedCode.isEmpty()) {
            return WithingsLoginResult.Error("Zadejte ověřovací kód.")
        }

        return try {
            // Pokud uživatel zadává kód, který mu už přišel dříve (např. po restartu aplikace),
            // inicializujeme workflow cookies bez vygenerování nového kódu.
            if (!cookieJar.hasWorkflowCookie()) {
                cookieJar.setEmailCookie(em)
                val checkInitReq = Request.Builder()
                    .url(lastMfaUrl)
                    .header("User-Agent", ua)
                    .get()
                    .build()
                val initHtml = client.newCall(checkInitReq).execute().use { trace(flow, "GET code_check", it); it.body?.string().orEmpty() }
                lastHiddenFields = parseHiddenInputs(initHtml)
            }

            val bodyMfaBuilder = FormBody.Builder()
            lastHiddenFields.forEach { (k, v) -> if (k != "code" && k != "email" && k.isNotBlank()) bodyMfaBuilder.add(k, v) }
            if ("use_2fa" !in lastHiddenFields) {
                bodyMfaBuilder.add("use_2fa", "")
            }
            bodyMfaBuilder.add("email", em)
            bodyMfaBuilder.add("code", cleanedCode)

            val req = Request.Builder()
                .url(lastMfaUrl)
                .header("User-Agent", ua)
                .header("Origin", "https://account.withings.com")
                .header("Referer", lastMfaUrl)
                .post(bodyMfaBuilder.build())
                .build()

            val res = client.newCall(req).execute()
            trace(flow, "POST code_check", res)
            val bodyText = res.body?.string().orEmpty()
            val finalHttpUrl = res.request.url
            val finalUrl = finalHttpUrl.toString()
            res.close()

            if (isLoggedInUrl(finalHttpUrl)) {
                return finishLogin(flow)
            }

            if (bodyText.contains("Invalid code", ignoreCase = true) || hasVisibleAlertError(bodyText)) {
                isAwaitingCode = true
                return WithingsLoginResult.Error("Neplatný nebo expirovaný ověřovací kód. Přihlaste se znovu heslem a zadejte nový kód.")
            }

            // Pokud po email_check následuje ještě druhý faktor (např. SMS/TOTP)
            if ((finalUrl.contains("phone_check") || finalUrl.contains("totp")) && finalUrl != lastMfaUrl) {
                lastMfaUrl = finalUrl
                lastHiddenFields = parseHiddenInputs(bodyText)
                isAwaitingCode = true
                return WithingsLoginResult.NeedsMfa(em, "Zadejte ještě dvoufázový kód (SMS / Authenticator).")
            }

            finishLogin(flow)
        } catch (e: IOException) {
            traceException(flow, e)
            WithingsLoginResult.Error("Nepodařilo se spojit se serverem Withings. Zkontrolujte připojení a zkuste to znovu.", WithingsAuthFailureKind.NETWORK)
        } catch (e: Exception) {
            traceException(flow, e)
            WithingsLoginResult.Error("Ověření kódu se nezdařilo. Zkuste to prosím znovu.", WithingsAuthFailureKind.OTHER)
        }
    }

    private fun finishLogin(flow: WithingsAuthDiagnostics.Flow): WithingsLoginResult {
        // Načteme ještě hlavní stránku app.withings.com, aby se případně nastavily finální cookies pro scalews.withings.com
        runCatching {
            val touchReq = Request.Builder()
                .url("https://app.withings.com/")
                .header("User-Agent", ua)
                .get()
                .build()
            client.newCall(touchReq).execute().use { trace(flow, "GET app", it) }
        }

        val cookies = cookieJar.getCookieString()
        val sessionToken = cookieJar.getSessionToken()
        PLog.i("WithingsDirectAuth") { "finishLogin: cookies=[${cookieJar.describe()}], hasSessionToken=${sessionToken != null}" }
        return try {
            val profiles = webClient.getUsersList(cookies, sessionToken)
            diagnostics?.mark(flow, "getUsersList:${profiles.size}")
            PLog.i("WithingsDirectAuth") { "finishLogin: fetched ${profiles.size} profiles" }
            if (profiles.isEmpty()) {
                WithingsLoginResult.Error("Přihlášeno k Withings, ale nebyl nalezen žádný profil.")
            } else {
                isAwaitingCode = false
                val primary = profiles.firstOrNull { it.isPrimary } ?: profiles.first()
                // Druhý dospělý profil na účtu (např. Klárka) je samostatný dospělý, nikoliv automaticky dítě.
                // Dětský profil se automaticky naváže pouze tehdy, pokud je na účtu skutečný dětský profil
                // (isChild) vedený přímo pod tímto dospělým účtem (parentAccountId).
                val childCandidate = primary.childrenUnderThis(profiles).firstOrNull()
                val session = WithingsWebSession(
                    cookies = cookies,
                    sessionToken = sessionToken,
                    primaryUserId = primary.id,
                    primaryUserName = primary.displayName,
                    primaryUserTimezone = primary.timezone,
                    childUserId = childCandidate?.id,
                    childUserName = childCandidate?.displayName,
                    lastSyncMs = null,
                    allProfiles = profiles
                )
                WithingsLoginResult.Ok(session, profiles)
            }
        } catch (e: IOException) {
            traceException(flow, e)
            PLog.w("WithingsDirectAuth", e) { "finishLogin network exception" }
            WithingsLoginResult.Error("Nepodařilo se spojit se serverem Withings. Zkontrolujte připojení a zkuste to znovu.", WithingsAuthFailureKind.NETWORK)
        } catch (e: Exception) {
            traceException(flow, e)
            PLog.e("WithingsDirectAuth", e) { "finishLogin exception" }
            WithingsLoginResult.Error("Chyba při načtení profilů účtu Withings.", WithingsAuthFailureKind.OTHER)
        }
    }
}
