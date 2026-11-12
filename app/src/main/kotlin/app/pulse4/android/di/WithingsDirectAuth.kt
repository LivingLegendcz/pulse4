package app.pulse4.android.di

import app.pulse4.withings.WithingsUserProfile
import app.pulse4.withings.WithingsWebClient
import app.pulse4.withings.WithingsWebSession
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface WithingsLoginResult {
    data class Ok(val session: WithingsWebSession, val profiles: List<WithingsUserProfile>) : WithingsLoginResult
    data class NeedsMfa(val email: String, val message: String) : WithingsLoginResult
    data class Error(val message: String) : WithingsLoginResult
}

private class InMemoryCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (c in cookies) {
            store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.value == "deleted" || c.expiresAt <= now) {
                store.removeAll { it.name == c.name }
            } else if (c.value.isNotEmpty()) {
                store.add(c)
            }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.removeAll { it.expiresAt < now || it.value == "deleted" }
        return store.filter { it.matches(url) }
    }

    @Synchronized
    fun getCookieString(): String {
        val byName = LinkedHashMap<String, String>()
        for (c in store) {
            if (c.value.isNotBlank() && c.value != "deleted") {
                byName[c.name] = c.value
            }
        }
        return byName.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    @Synchronized
    fun getSessionToken(): String? {
        // Pouze skutečný `session_token`. Hodnota `session_key` / `sessionid` je identifikátor relace,
        // který server čte z cookie – nesmí se vydávat za session_token.
        return store.lastOrNull { it.name == "session_token" && it.value.isNotBlank() && it.value != "deleted" }?.value
    }

    @Synchronized
    fun describe(): String = store.joinToString(", ") { "${it.name}@${it.domain}" }

    @Synchronized
    fun hasSession(): Boolean {
        return store.any {
            (it.name == "sessionid" || it.name == "session_token" || it.name == "session_key") &&
                it.value.isNotBlank() && it.value != "deleted"
        }
    }

    @Synchronized
    fun hasWorkflowCookie(): Boolean {
        return store.any { it.name == "w_uuid" && it.value.isNotBlank() }
    }

    @Synchronized
    fun setEmailCookie(email: String) {
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

    @Synchronized
    fun clear() {
        store.clear()
    }
}

/**
 * Přímé přihlášení k Withings v pozadí bez nutnosti otevírat WebView.
 * Uživateli se zobrazují pouze nativní pole v tmavém motivu aplikace Pulse4.
 * Extrahuje skrytá CSRF/stavová pole z HTML formulářů a zachytává přesměrování na 2FA.
 */
class WithingsDirectAuth(
    private val webClient: WithingsWebClient
) {
    private val cookieJar = InMemoryCookieJar()
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
    @Volatile var pendingPassword: String? = null
        private set
    @Volatile var isAwaitingCode: Boolean = false
        private set

    private var lastMfaUrl: String = "https://account.withings.com/new_workflow/email_check?r=https%3A%2F%2Fapp.withings.com%2F"
    private var lastHiddenFields: Map<String, String> = emptyMap()
    private val ua = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

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

    /**
     * Vyžádá zaslání 8místného přihlašovacího kódu na e-mail (funguje pro účty Google, Apple i účty bez hesla).
     * Vše probíhá na pozadí bez WebView.
     */
    fun requestEmailCode(email: String): WithingsLoginResult {
        cookieJar.clear()
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
            val html1 = client.newCall(req1).execute().use { it.body?.string().orEmpty() }
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

            val res2 = client.newCall(req2).execute()
            val html2 = res2.body?.string().orEmpty()
            val finalHttpUrl2 = res2.request.url
            val finalUrl2 = finalHttpUrl2.toString()
            res2.close()

            lastMfaUrl = if (finalUrl2.contains("check") || finalUrl2.contains("totp")) {
                finalUrl2
            } else {
                "https://account.withings.com/new_workflow/email_check?r=https%3A%2F%2Fapp.withings.com%2F"
            }
            lastHiddenFields = parseHiddenInputs(html2)

            if (isLoggedInUrl(finalHttpUrl2)) {
                return finishLogin()
            }

            isAwaitingCode = true
            WithingsLoginResult.NeedsMfa(
                email = em,
                message = "Na e-mail $em byl odeslán 8místný přihlašovací kód Withings. Zadejte ho do pole níže."
            )
        } catch (e: Exception) {
            WithingsLoginResult.Error(e.message ?: "Chyba sítě při vyžádání přihlašovacího kódu.")
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
                .add("appliver", "d2e7b8e1")
                .build()
            val req = Request.Builder()
                .url("https://scalews.withings.com/cgi-bin/v2/account")
                .header("User-Agent", ua)
                .header("Origin", "https://account.withings.com")
                .header("Referer", "https://account.withings.com/")
                .post(form)
                .build()
            client.newCall(req).execute().close()
            isAwaitingCode = true
            WithingsLoginResult.NeedsMfa(em, "Nový 8místný kód byl odeslán na $em. Zadejte ho do pole níže.")
        } catch (e: Exception) {
            WithingsLoginResult.Error("Nepodařilo se znovu odeslat kód: ${e.message}")
        }
    }

    fun loginPassword(email: String, password: String): WithingsLoginResult {
        if (password.isBlank()) {
            return WithingsLoginResult.Error("Zadejte heslo k účtu Withings.")
        }
        cookieJar.clear()
        val em = email.trim()
        pendingEmail = em
        pendingPassword = password
        android.util.Log.i("WithingsDirectAuth", "loginPassword starting for $em")

        return try {
            val loginUrl = "https://account.withings.com/new_workflow/login?r=https%3A%2F%2Fapp.withings.com%2F"
            // 1. Zahájení relace – načte výchozí w_uuid, signin_authorize_state a workflow cookies
            val req1 = Request.Builder()
                .url(loginUrl)
                .header("User-Agent", ua)
                .get()
                .build()
            client.newCall(req1).execute().close()

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
            val passGetHtml = client.newCall(passGetReq).execute().use { it.body?.string().orEmpty() }
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
            val bodyText = res3.body?.string().orEmpty()
            val finalHttpUrl = res3.request.url
            val finalUrl = finalHttpUrl.toString()
            res3.close()

            android.util.Log.i("WithingsDirectAuth", "loginPassword POST response: finalUrl=$finalUrl, hasSession=${cookieJar.hasSession()}")

            if (isLoggedInUrl(finalHttpUrl)) {
                return finishLogin()
            }

            if (bodyText.contains("Wrong username or password", ignoreCase = true) ||
                bodyText.contains("Invalid email or password", ignoreCase = true) ||
                (finalUrl.contains("password_check") && hasVisibleAlertError(bodyText))
            ) {
                return WithingsLoginResult.Error("Withings heslo odmítl. Zkontrolujte heslo; pokud k účtu žádné nemáte (přihlášení přes Google / Apple), založte si ho na account.withings.com přes „Zapomenuté heslo“.")
            }

            // Kontrola, zda Withings vyžaduje 2FA kód (e-mail OTP, SMS nebo TOTP)
            if (finalUrl.contains("email_check") || finalUrl.contains("phone_check") || finalUrl.contains("totp") ||
                bodyText.contains("Identification code", ignoreCase = true) ||
                bodyText.contains("code-wrapper") || bodyText.contains("name=\"code\"")
            ) {
                lastMfaUrl = if (finalUrl.contains("check") || finalUrl.contains("totp")) {
                    finalUrl
                } else {
                    "https://account.withings.com/new_workflow/email_check?r=https%3A%2F%2Fapp.withings.com%2F"
                }
                lastHiddenFields = parseHiddenInputs(bodyText)
                isAwaitingCode = true
                android.util.Log.i("WithingsDirectAuth", "loginPassword requires 2FA: lastMfaUrl=$lastMfaUrl")
                return WithingsLoginResult.NeedsMfa(em, "Withings zaslal ověřovací kód na váš e-mail nebo v SMS. Zadejte ho do pole níže.")
            }

            WithingsLoginResult.Error("Přihlášení heslem se nezdařilo. Zkontrolujte e-mail a heslo k účtu Withings.")
        } catch (e: Exception) {
            android.util.Log.e("WithingsDirectAuth", "loginPassword exception: ${e.message}", e)
            WithingsLoginResult.Error(e.message ?: "Chyba sítě při přihlašování.")
        }
    }

    fun resumeMfa(code: String, emailFallback: String? = null): WithingsLoginResult {
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
                val initHtml = client.newCall(checkInitReq).execute().use { it.body?.string().orEmpty() }
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
            val bodyText = res.body?.string().orEmpty()
            val finalHttpUrl = res.request.url
            val finalUrl = finalHttpUrl.toString()
            res.close()

            if (isLoggedInUrl(finalHttpUrl)) {
                return finishLogin()
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

            finishLogin()
        } catch (e: Exception) {
            WithingsLoginResult.Error(e.message ?: "Chyba sítě při ověření kódu.")
        }
    }

    private fun finishLogin(): WithingsLoginResult {
        // Načteme ještě hlavní stránku app.withings.com, aby se případně nastavily finální cookies pro scalews.withings.com
        runCatching {
            val touchReq = Request.Builder()
                .url("https://app.withings.com/")
                .header("User-Agent", ua)
                .get()
                .build()
            client.newCall(touchReq).execute().close()
        }

        val cookies = cookieJar.getCookieString()
        val sessionToken = cookieJar.getSessionToken()
        android.util.Log.i("WithingsDirectAuth", "finishLogin: cookies=[${cookieJar.describe()}], hasSessionToken=${sessionToken != null}")
        return try {
            val profiles = webClient.getUsersList(cookies, sessionToken)
            android.util.Log.i("WithingsDirectAuth", "finishLogin: fetched ${profiles.size} profiles: ${profiles.map { "${it.displayName} (${it.id})" }}")
            if (profiles.isEmpty()) {
                WithingsLoginResult.Error("Přihlášeno k Withings, ale nebyl nalezen žádný profil.")
            } else {
                isAwaitingCode = false
                val primary = profiles.firstOrNull { it.isPrimary } ?: profiles.first()
                val childCandidate = profiles.firstOrNull { it.id != primary.id && !it.isPrimary }
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
        } catch (e: Exception) {
            android.util.Log.e("WithingsDirectAuth", "finishLogin exception: ${e.message}", e)
            val err = e.message ?: "Chyba při načtení profilů účtu Withings."
            WithingsLoginResult.Error(err)
        }
    }
}
