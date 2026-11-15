package app.pulse4.android.reticulum

import android.content.Context
import android.util.Base64
import android.util.Log
import app.pulse4.android.security.SecretStore
import app.pulse4.provider.ProviderError
import app.pulse4.sync.Wire
import app.pulse4.training.GarminWorkoutApi
import app.pulse4.training.RemoteScheduled
import app.pulse4.training.RemoteWorkout
import app.pulse4.training.Workout
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface LoginResult {
    data object Ok : LoginResult
    data object NeedsMfa : LoginResult
}

@Serializable
data class GarminExtTokens(
    val oauth1Token: String = "",
    val oauth1Secret: String = "",
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val refreshExpiresAtMs: Long? = null,
    val email: String? = null,
    val clientId: String = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"
)

/**
 * Generátor OAuth 1.0 (RFC 5849) autorizační hlavičky pro Garmin Connect API.
 */
object OAuth1Signer {
    private const val CONSUMER_KEY = "fc3e99d2-118c-44b8-8ae3-03370dde24c0"
    private const val CONSUMER_SECRET = "E08WAR897WEy2knn7aFBrvegVAf0AFdWBBF"

    fun generateHeader(
        method: String,
        url: String,
        token: String? = null,
        tokenSecret: String? = null,
        extraParams: Map<String, String> = emptyMap(),
        consumerKey: String = CONSUMER_KEY,
        consumerSecret: String = CONSUMER_SECRET
    ): String {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val timestamp = (System.currentTimeMillis() / 1000).toString()

        val oauthParams = mutableMapOf(
            "oauth_consumer_key" to consumerKey,
            "oauth_nonce" to nonce,
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to timestamp,
            "oauth_version" to "1.0"
        )
        if (!token.isNullOrBlank()) {
            oauthParams["oauth_token"] = token
        }

        val uri = URI(url)
        val allParams = TreeMap<String, String>()
        uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.forEach { param ->
            val parts = param.split('=', limit = 2)
            val k = percentEncode(URLDecoder.decode(parts[0], "UTF-8"))
            val v = if (parts.size > 1) percentEncode(URLDecoder.decode(parts[1], "UTF-8")) else ""
            allParams[k] = v
        }
        for ((k, v) in extraParams) allParams[percentEncode(k)] = percentEncode(v)
        for ((k, v) in oauthParams) allParams[percentEncode(k)] = percentEncode(v)

        val paramString = allParams.entries.joinToString("&") { "${it.key}=${it.value}" }
        val baseUrl = "${uri.scheme}://${uri.authority}${uri.path}"
        val baseString = "${method.uppercase()}&${percentEncode(baseUrl)}&${percentEncode(paramString)}"
        val signingKey = "${percentEncode(consumerSecret)}&${percentEncode(tokenSecret.orEmpty())}"

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(signingKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signatureBytes = mac.doFinal(baseString.toByteArray(Charsets.UTF_8))
        val signature = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        oauthParams["oauth_signature"] = signature

        return "OAuth " + oauthParams.entries.joinToString(", ") {
            """${percentEncode(it.key)}="${percentEncode(it.value)}""""
        }
    }

    fun percentEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
}

/**
 * Konektor pro Garmin Connect API (zápis a plánování tréninků, stahování FIT a telemetrie).
 * Heslo se nikdy neukládá; relace žije v uložených tokenech (šifrovaně přes Android Keystore).
 * Autentizace využívá moderní DI-OAuth2 protokol (GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2)
 * se záložním mechanizmem OAuth 1.0 pro starší relace.
 */
class ExtendedGarminApi(
    private val context: Context,
    private val secrets: SecretStore
) : GarminWorkoutApi {
    companion object {
        private const val TAG = "ExtendedGarminApi"
        private const val SSO_BASE_URL = "https://sso.garmin.com/sso"
        private const val SSO_EMBED_URL = "https://sso.garmin.com/sso/embed"
        private const val SSO_SIGNIN_URL = "https://sso.garmin.com/sso/signin"
        private const val DI_TOKEN_URL = "https://diauth.garmin.com/di-oauth2-service/oauth/token"
        private const val DI_GRANT_TYPE = "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"
        private val DI_CLIENT_IDS = listOf(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI"
        )
        private const val SSO_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; M2007J20CG) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        private const val CONNECT_MOBILE_USER_AGENT = "com.garmin.android.apps.connectmobile"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cookieStore = mutableListOf<Cookie>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(cookieStore) {
                for (c in cookies) {
                    cookieStore.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                    cookieStore.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            synchronized(cookieStore) {
                cookieStore.removeAll { it.expiresAt <= now }
                return cookieStore.filter { it.matches(url) }
            }
        }
    }

    private val http = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .callTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var pendingMfaCsrf: String? = null
    private var pendingMfaEmail: String? = null

    fun hasPendingMfa(): Boolean = !pendingMfaCsrf.isNullOrBlank()

    private fun extractHiddenInputs(html: String): Map<String, String> {
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
            out[name] = valAttr.find(tag)?.groupValues?.getOrNull(1) ?: ""
        }
        return out
    }

    private fun extractCsrfToken(html: String): String? {
        extractHiddenInputs(html)["_csrf"]?.takeIf { it.isNotBlank() }?.let { return it }
        val metaRegex = Regex("""<meta\b[^>]*name\s*=\s*["']_csrf["'][^>]*content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        metaRegex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        val inlineRegex = Regex("""["']_csrf["']\s*[:=]\s*["']([^"']+)["']""")
        return inlineRegex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun buildEmbedUrl(): HttpUrl =
        SSO_EMBED_URL.toHttpUrl().newBuilder()
            .addQueryParameter("id", "gauth-widget")
            .addQueryParameter("embedWidget", "true")
            .addQueryParameter("gauthHost", SSO_BASE_URL)
            .build()

    private fun buildSigninUrl(): HttpUrl =
        SSO_SIGNIN_URL.toHttpUrl().newBuilder()
            .addQueryParameter("id", "gauth-widget")
            .addQueryParameter("embedWidget", "true")
            .addQueryParameter("gauthHost", SSO_EMBED_URL)
            .addQueryParameter("service", SSO_EMBED_URL)
            .addQueryParameter("source", SSO_EMBED_URL)
            .addQueryParameter("redirectAfterAccountLoginUrl", SSO_EMBED_URL)
            .addQueryParameter("redirectAfterAccountCreationUrl", SSO_EMBED_URL)
            .build()

    fun loginPassword(email: String, password: String): LoginResult = call(mutating = false) {
        val cleanEmail = email.trim()
        Log.d(TAG, "Starting Garmin SSO login for email: $cleanEmail")
        pendingMfaCsrf = null
        pendingMfaEmail = cleanEmail
        synchronized(cookieStore) { cookieStore.clear() }

        // 1. GET embed page to initialize cookies
        val embedUrl = buildEmbedUrl()
        val embedReq = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", SSO_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "cs-CZ,cs;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        http.newCall(embedReq).execute().use { embedRes ->
            Log.d(TAG, "SSO embed response code: ${embedRes.code}")
            if (embedRes.code == 429 || embedRes.code == 403) {
                throw ProviderError.RateLimited(null)
            }
        }

        // 2. GET signin page to obtain CSRF token and hidden form inputs
        val signinUrl = buildSigninUrl()
        val signinGetReq = Request.Builder()
            .url(signinUrl)
            .header("User-Agent", SSO_USER_AGENT)
            .header("Referer", embedUrl.toString())
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "cs-CZ,cs;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        val signinGetHtml = http.newCall(signinGetReq).execute().use { signinGetRes ->
            val html = signinGetRes.body?.string().orEmpty()
            Log.d(TAG, "SSO signin GET response code: ${signinGetRes.code}, body length: ${html.length}")
            if (signinGetRes.code == 429 || signinGetRes.code == 403) {
                throw ProviderError.RateLimited(null)
            }
            html
        }

        val hiddenInputs = extractHiddenInputs(signinGetHtml)
        val csrf = extractCsrfToken(signinGetHtml).orEmpty()
        if (csrf.isBlank()) {
            Log.w(TAG, "Failed to extract CSRF token from signin page")
            throw ProviderError.Network("Garmin SSO nevrátil CSRF token (zkuste znovu)")
        }

        // Anti-WAF pause
        try { Thread.sleep(700) } catch (_: InterruptedException) {}

        // 3. POST credentials (včetně případných dalších skrytých polí formuláře)
        val formBuilder = FormBody.Builder()
            .add("username", cleanEmail)
            .add("password", password)
            .add("embed", "true")
            .add("_csrf", csrf)
        hiddenInputs.forEach { (k, v) ->
            if (k !in setOf("username", "password", "embed", "_csrf") && k.isNotBlank()) {
                formBuilder.add(k, v)
            }
        }

        val loginReq = Request.Builder()
            .url(signinUrl)
            .post(formBuilder.build())
            .header("User-Agent", SSO_USER_AGENT)
            .header("Referer", signinUrl.toString())
            .header("Origin", "https://sso.garmin.com")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "cs-CZ,cs;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()

        val (loginCode, loginBody, finalUrl) = http.newCall(loginReq).execute().use { loginRes ->
            Triple(loginRes.code, loginRes.body?.string().orEmpty(), loginRes.request.url.toString())
        }
        Log.d(TAG, "SSO signin POST response code: $loginCode, finalUrl: $finalUrl")

        if (loginCode == 429 || loginCode == 403) {
            Log.w(TAG, "Garmin SSO rate limited or WAF blocked (HTTP $loginCode)")
            throw ProviderError.RateLimited(null)
        }
        if (loginCode == 401 || loginBody.contains("Invalid credentials", ignoreCase = true) ||
            loginBody.contains("incorrect", ignoreCase = true) || loginBody.contains("locked", ignoreCase = true)) {
            Log.w(TAG, "Garmin SSO credentials rejected (HTTP $loginCode)")
            throw ProviderError.Unauthorized()
        }

        // Check MFA
        if (finalUrl.contains("verifyMFA", ignoreCase = true) ||
            loginBody.contains("loginEnterMfaCode") || loginBody.contains("needs_mfa") ||
            loginBody.contains("mfaMethod") || loginBody.contains("setupEnterMfaCode") ||
            loginBody.contains("mfa-code")) {
            Log.d(TAG, "Garmin SSO requires MFA")
            pendingMfaCsrf = extractCsrfToken(loginBody) ?: csrf
            triggerMfaEmailIfNeeded(loginBody)
            return@call LoginResult.NeedsMfa
        }

        val ticket = extractTicket(loginBody, finalUrl)
        if (ticket == null) {
            Log.w(TAG, "Service ticket not found in login response. URL: $finalUrl, body length: ${loginBody.length}")
            throw ProviderError.Unauthorized()
        }

        Log.d(TAG, "Garmin SSO login succeeded, acquired ticket: ${ticket.take(8)}...")
        finishLoginWithTicket(ticket, cleanEmail)
        pendingMfaEmail = null
        LoginResult.Ok
    }

    private fun triggerMfaEmailIfNeeded(html: String) {
        if (html.contains("codeSentTo")) return
        val methodMatch = Regex("""var\s+mfaMethod\s*=\s*"([^"]*)"""").find(html)?.groupValues?.get(1) ?: return
        if (methodMatch.equals("email", ignoreCase = true) || methodMatch.equals("sms", ignoreCase = true)) {
            val guid = Regex("""var\s+customerGuid\s*=\s*"([^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()
            val locale = Regex("""var\s+locale\s*=\s*"([^"]*)"""").find(html)?.groupValues?.get(1) ?: "en-US"
            val payload = """{"customerGuid":"$guid","mfaMethod":"$methodMatch","locale":"$locale"}"""
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://sso.garmin.com/sso/verifyMFA/mfaCode?clientId=gauth-widget")
                .header("Referer", buildSigninUrl().toString())
                .header("Origin", "https://sso.garmin.com")
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", SSO_USER_AGENT)
                .post(payload)
                .build()
            runCatching { http.newCall(req).execute().close() }
        }
    }

    fun resumeMfa(code: String): LoginResult = call(mutating = false) {
        val csrf = pendingMfaCsrf ?: throw ProviderError.Unauthorized()
        val cleanedCode = code.trim().replace(" ", "").replace("-", "")
        if (cleanedCode.isBlank()) throw ProviderError.Unauthorized()
        val mfaUrl = SSO_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("verifyMFA")
            .addPathSegment("loginEnterMfaCode")
            .addQueryParameter("id", "gauth-widget")
            .addQueryParameter("embedWidget", "true")
            .addQueryParameter("gauthHost", SSO_EMBED_URL)
            .addQueryParameter("service", SSO_EMBED_URL)
            .addQueryParameter("source", SSO_EMBED_URL)
            .addQueryParameter("redirectAfterAccountLoginUrl", SSO_EMBED_URL)
            .addQueryParameter("redirectAfterAccountCreationUrl", SSO_EMBED_URL)
            .build()

        val mfaForm = FormBody.Builder()
            .add("mfa-code", cleanedCode)
            .add("embed", "true")
            .add("_csrf", csrf)
            .add("fromPage", "setupEnterMfaCode")
            .build()

        val mfaReq = Request.Builder()
            .url(mfaUrl)
            .post(mfaForm)
            .header("User-Agent", SSO_USER_AGENT)
            .header("Referer", buildSigninUrl().toString())
            .header("Origin", "https://sso.garmin.com")
            .build()

        val (mfaCode, mfaBody, finalUrl) = http.newCall(mfaReq).execute().use { mfaRes ->
            Triple(mfaRes.code, mfaRes.body?.string().orEmpty(), mfaRes.request.url.toString())
        }
        Log.d(TAG, "SSO verifyMFA response code: $mfaCode, finalUrl: $finalUrl")
        if (mfaCode == 429 || mfaCode == 403) {
            throw ProviderError.RateLimited(null)
        }

        val ticket = extractTicket(mfaBody, finalUrl)
            ?: run {
                // Aktualizujeme CSRF z odpovědi, aby šlo případně kód opravit a odeslat znovu
                extractCsrfToken(mfaBody)?.let { pendingMfaCsrf = it }
                Log.w(TAG, "Service ticket not found after MFA verification")
                throw ProviderError.Unauthorized()
            }

        Log.d(TAG, "Garmin MFA succeeded, acquired ticket: ${ticket.take(8)}...")
        finishLoginWithTicket(ticket, pendingMfaEmail)
        pendingMfaCsrf = null
        pendingMfaEmail = null
        LoginResult.Ok
    }

    private fun extractTicket(body: String, requestUrl: String): String? {
        val ticketRegex = Regex("""(?:\?|&|#)ticket=([A-Za-z0-9\-_]+)""")
        val jsonRegex = Regex("""serviceTicketId"?\s*[:=]\s*"([^"]+)"""")
        val altRegex = Regex("""ticket=([A-Za-z0-9\-_]+)""")

        return ticketRegex.find(requestUrl)?.groupValues?.get(1)
            ?: ticketRegex.find(body)?.groupValues?.get(1)
            ?: jsonRegex.find(body)?.groupValues?.get(1)
            ?: altRegex.find(body)?.groupValues?.get(1)
    }

    private fun finishLoginWithTicket(ticket: String, email: String?) {
        var lastException: Exception? = null

        // 1. Primární strategie: Moderní výměna DI-OAuth2 tokenu
        for (clientId in DI_CLIENT_IDS) {
            try {
                Log.d(TAG, "Attempting DI-OAuth2 token exchange with clientId: $clientId")
                val tokens = exchangeTicketWithDiOAuth2(ticket, clientId, email)
                saveTokens(tokens)
                Log.i(TAG, "DI-OAuth2 exchange successful with clientId: $clientId")
                return
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "DI-OAuth2 exchange failed with clientId: $clientId: ${e.message}")
            }
        }

        // 2. Sekundární záloha: Legacy OAuth 1.0 preauthorized exchange
        try {
            Log.d(TAG, "Falling back to legacy OAuth 1.0 preauthorized exchange")
            val preAuthUrl = "https://connectapi.garmin.com/oauth-service/oauth/preauthorized?ticket=$ticket&service=https%3A%2F%2Fsso.garmin.com%2Fsso%2Fembed&accepts-mfa-tokens=true"
            val oauth1Header = OAuth1Signer.generateHeader("GET", preAuthUrl)

            val preAuthReq = Request.Builder()
                .url(preAuthUrl)
                .header("Authorization", oauth1Header)
                .header("User-Agent", CONNECT_MOBILE_USER_AGENT)
                .build()

            val preAuthRes = http.newCall(preAuthReq).execute()
            if (preAuthRes.isSuccessful) {
                val preAuthBody = preAuthRes.body?.string().orEmpty()
                val oauth1Token = Regex("""oauth_token=([^&]+)""").find(preAuthBody)?.groupValues?.get(1)
                val oauth1Secret = Regex("""oauth_token_secret=([^&]+)""").find(preAuthBody)?.groupValues?.get(1)
                if (!oauth1Token.isNullOrBlank() && !oauth1Secret.isNullOrBlank()) {
                    val tokens = exchangeOAuth1ForOAuth2(oauth1Token, oauth1Secret, email)
                    saveTokens(tokens)
                    Log.i(TAG, "Legacy OAuth 1.0 exchange successful")
                    return
                }
            } else {
                Log.w(TAG, "Legacy preauthorized exchange failed with HTTP ${preAuthRes.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy OAuth 1.0 exchange failed: ${e.message}")
            if (lastException == null) lastException = e
        }

        throw lastException ?: ProviderError.Unauthorized()
    }

    private fun exchangeTicketWithDiOAuth2(ticket: String, clientId: String, email: String?): GarminExtTokens {
        val basicAuth = "Basic " + Base64.encodeToString("$clientId:".toByteArray(), Base64.NO_WRAP)
        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("service_ticket", ticket)
            .add("grant_type", DI_GRANT_TYPE)
            .add("service_url", SSO_EMBED_URL)
            .build()

        val req = Request.Builder()
            .url(DI_TOKEN_URL)
            .post(formBody)
            .header("Authorization", basicAuth)
            .header("User-Agent", CONNECT_MOBILE_USER_AGENT)
            .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
            .build()

        val res = http.newCall(req).execute()
        if (res.code == 429) throw ProviderError.RateLimited(null)
        if (!res.isSuccessful) {
            val errBody = res.body?.string().orEmpty()
            Log.w(TAG, "DI-OAuth2 exchange HTTP ${res.code}: $errBody")
            throw ProviderError.Unauthorized()
        }

        val jsonText = res.body?.string().orEmpty()
        val elem = json.parseToJsonElement(jsonText).jsonObject
        val accessToken = elem["access_token"]?.jsonPrimitive?.contentOrNull ?: throw ProviderError.Unauthorized()
        val refreshToken = elem["refresh_token"]?.jsonPrimitive?.contentOrNull ?: throw ProviderError.Unauthorized()
        val expiresInS = elem["expires_in"]?.jsonPrimitive?.longOrNull ?: 86400L
        val refreshExpiresInS = elem["refresh_token_expires_in"]?.jsonPrimitive?.longOrNull ?: 7776000L

        val now = System.currentTimeMillis()
        return GarminExtTokens(
            oauth1Token = "",
            oauth1Secret = "",
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMs = now + expiresInS * 1000L,
            refreshExpiresAtMs = now + refreshExpiresInS * 1000L,
            email = email,
            clientId = clientId
        )
    }

    private fun refreshDiOAuth2Token(refreshToken: String, clientId: String, email: String?): GarminExtTokens {
        val basicAuth = "Basic " + Base64.encodeToString("$clientId:".toByteArray(), Base64.NO_WRAP)
        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .build()

        val req = Request.Builder()
            .url(DI_TOKEN_URL)
            .post(formBody)
            .header("Authorization", basicAuth)
            .header("User-Agent", CONNECT_MOBILE_USER_AGENT)
            .header("Accept", "application/json")
            .build()

        val res = http.newCall(req).execute()
        if (!res.isSuccessful) {
            val errBody = res.body?.string().orEmpty()
            Log.w(TAG, "DI token refresh HTTP ${res.code}: $errBody")
            throw ProviderError.Unauthorized()
        }

        val jsonText = res.body?.string().orEmpty()
        val elem = json.parseToJsonElement(jsonText).jsonObject
        val newAccessToken = elem["access_token"]?.jsonPrimitive?.contentOrNull ?: throw ProviderError.Unauthorized()
        val newRefreshToken = elem["refresh_token"]?.jsonPrimitive?.contentOrNull ?: refreshToken
        val expiresInS = elem["expires_in"]?.jsonPrimitive?.longOrNull ?: 86400L
        val refreshExpiresInS = elem["refresh_token_expires_in"]?.jsonPrimitive?.longOrNull ?: 7776000L

        val now = System.currentTimeMillis()
        return GarminExtTokens(
            oauth1Token = "",
            oauth1Secret = "",
            accessToken = newAccessToken,
            refreshToken = newRefreshToken,
            expiresAtMs = now + expiresInS * 1000L,
            refreshExpiresAtMs = now + refreshExpiresInS * 1000L,
            email = email,
            clientId = clientId
        )
    }

    private fun exchangeOAuth1ForOAuth2(oauth1Token: String, oauth1Secret: String, email: String?): GarminExtTokens {
        val exchangeUrl = "https://connectapi.garmin.com/oauth-service/oauth/exchange/user/2.0"
        val oauth1Header = OAuth1Signer.generateHeader(
            method = "POST",
            url = exchangeUrl,
            token = oauth1Token,
            tokenSecret = oauth1Secret
        )

        val emptyBody = "".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val exchangeReq = Request.Builder()
            .url(exchangeUrl)
            .post(emptyBody)
            .header("Authorization", oauth1Header)
            .header("User-Agent", CONNECT_MOBILE_USER_AGENT)
            .build()

        val exchangeRes = http.newCall(exchangeReq).execute()
        if (!exchangeRes.isSuccessful) throw ProviderError.Unauthorized()
        val jsonText = exchangeRes.body?.string().orEmpty()

        val elem = json.parseToJsonElement(jsonText).jsonObject
        val accessToken = elem["access_token"]?.jsonPrimitive?.contentOrNull ?: throw ProviderError.Unauthorized()
        val refreshToken = elem["refresh_token"]?.jsonPrimitive?.contentOrNull ?: throw ProviderError.Unauthorized()
        val expiresInS = elem["expires_in"]?.jsonPrimitive?.longOrNull ?: 86400L
        val refreshExpiresInS = elem["refresh_token_expires_in"]?.jsonPrimitive?.longOrNull ?: 7776000L

        val now = System.currentTimeMillis()
        return GarminExtTokens(
            oauth1Token = oauth1Token,
            oauth1Secret = oauth1Secret,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMs = now + expiresInS * 1000L,
            refreshExpiresAtMs = now + refreshExpiresInS * 1000L,
            email = email
        )
    }

    fun parseTokens(raw: String): GarminExtTokens? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // 1. Přímá deserializace nativního formátu GarminExtTokens
        val direct = runCatching { json.decodeFromString<GarminExtTokens>(trimmed) }.getOrNull()
        if (direct != null && direct.accessToken.isNotBlank()) return direct

        // 2. Podpora pro formát Garth session dump (Base64 řetězec začínající W3... nebo JSON pole)
        val jsonText = if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed
        } else {
            runCatching {
                String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8).trim()
            }.getOrNull() ?: ""
        }

        if (jsonText.startsWith("[") && jsonText.endsWith("]")) {
            return runCatching {
                val array = json.parseToJsonElement(jsonText).jsonArray
                if (array.size >= 2) {
                    val o1 = array[0].jsonObject
                    val o2 = array[1].jsonObject
                    val oauth1Token = o1["oauth_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val oauth1Secret = o1["oauth_token_secret"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val accessToken = o2["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val refreshToken = o2["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val expiresAtSec = o2["expires_at"]?.jsonPrimitive?.longOrNull
                        ?: (o2["expires_in"]?.jsonPrimitive?.longOrNull?.let { System.currentTimeMillis() / 1000L + it })
                        ?: (System.currentTimeMillis() / 1000L + 86400L)
                    val refreshExpiresAtSec = o2["refresh_token_expires_at"]?.jsonPrimitive?.longOrNull
                        ?: (o2["refresh_token_expires_in"]?.jsonPrimitive?.longOrNull?.let { System.currentTimeMillis() / 1000L + it })

                    val expiresAtMs = if (expiresAtSec > 20000000000L) expiresAtSec else expiresAtSec * 1000L
                    val refreshExpiresAtMs = refreshExpiresAtSec?.let { if (it > 20000000000L) it else it * 1000L }

                    val parsed = GarminExtTokens(
                        oauth1Token = oauth1Token,
                        oauth1Secret = oauth1Secret,
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expiresAtMs = expiresAtMs,
                        refreshExpiresAtMs = refreshExpiresAtMs
                    )
                    // Znormalizované tokeny rovnou zapíšeme zpět do SecretStore
                    saveTokens(parsed)
                    parsed
                } else null
            }.getOrNull()
        }
        return null
    }

    /**
     * Proaktivní keepalive: zkontroluje expiraci přístupového tokenu a v případě potřeby
     * provede tichou obnovu přes DI-OAuth2 refresh token nebo dlouhodobý OAuth 1.0 token.
     */
    @Synchronized
    fun ensureSession(): Boolean {
        val raw = secrets.getString(SecretStore.GARMIN_EXT_TOKENS) ?: return false
        val tokens = parseTokens(raw) ?: return false
        val now = System.currentTimeMillis()

        // Pokud tokenu zbývá více než 10 minut platnosti, je relace v pořádku
        if (tokens.expiresAtMs - now > 10 * 60 * 1000L) {
            return true
        }

        // 1. Zkusit obnovit přes DI-OAuth2 refresh token
        if (tokens.refreshToken.isNotBlank()) {
            val refreshed = runCatching {
                val fresh = refreshDiOAuth2Token(tokens.refreshToken, tokens.clientId, tokens.email)
                saveTokens(fresh)
                true
            }.getOrElse { false }
            if (refreshed) {
                Log.d(TAG, "Session refreshed via DI-OAuth2 token")
                return true
            }
        }

        // 2. Fallback: obnovit přes dlouhodobý OAuth 1.0 token (pokud existuje)
        if (tokens.oauth1Token.isNotBlank() && tokens.oauth1Secret.isNotBlank()) {
            return runCatching {
                val fresh = exchangeOAuth1ForOAuth2(tokens.oauth1Token, tokens.oauth1Secret, tokens.email)
                saveTokens(fresh)
                Log.d(TAG, "Session refreshed via OAuth 1.0 token")
                true
            }.getOrElse { false }
        }

        return false
    }

    private fun getValidAccessToken(): String {
        ensureSession()
        val raw = secrets.getString(SecretStore.GARMIN_EXT_TOKENS) ?: throw ProviderError.Unauthorized()
        val tokens = parseTokens(raw) ?: throw ProviderError.Unauthorized()
        return tokens.accessToken
    }

    private fun saveTokens(t: GarminExtTokens) {
        secrets.putString(SecretStore.GARMIN_EXT_TOKENS, json.encodeToString(t))
    }

    /** Zavolá Garmin Connect API endpoint přes přihlášenou relaci. */
    fun connectApiJson(path: String): String = call(mutating = false) {
        val token = getValidAccessToken()
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val fullUrl = if (path.startsWith("http")) path else "https://connectapi.garmin.com$cleanPath"

        val req = Request.Builder()
            .url(fullUrl)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", CONNECT_MOBILE_USER_AGENT)
            .build()

        http.newCall(req).execute().use { res ->
            if (res.code == 401) {
                // Token mohl vypršet na straně serveru: zkusíme obnovit a opakovat jednou
                val renewed = ensureSession()
                if (renewed) {
                    val newToken = getValidAccessToken()
                    val retryReq = req.newBuilder().header("Authorization", "Bearer $newToken").build()
                    http.newCall(retryReq).execute().use { retryRes ->
                        if (retryRes.isSuccessful) return@call retryRes.body?.string().orEmpty()
                    }
                }
                throw ProviderError.Unauthorized()
            }
            if (res.code == 429) throw ProviderError.RateLimited(null)
            if (!res.isSuccessful) throw ProviderError.Server(res.code)
            res.body?.string().orEmpty()
        }
    }

    /** Stáhne původní FIT soubor aktivity přes přihlášenou relaci Garmin Connect. */
    fun downloadActivityFit(activityId: String): ByteArray? = call(mutating = false) {
        val token = getValidAccessToken()
        val url = "https://connectapi.garmin.com/download-service/files/activity/$activityId"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", CONNECT_MOBILE_USER_AGENT)
            .build()

        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@call null
            res.body?.bytes()
        }
    }

    /** Získá kompletní telemetrii a GPS trasu aktivity z Garmin Connect API. */
    fun getActivityDetailsJson(activityId: String): String = call(mutating = false) {
        connectApiJson("/activity-service/activity/$activityId/details")
    }

    fun logout() {
        secrets.delete(SecretStore.GARMIN_EXT_TOKENS)
        synchronized(cookieStore) { cookieStore.clear() }
    }

    override fun listWorkouts(): List<RemoteWorkout> = call(false) {
        val res = connectApiJson("/workout-service/workouts")
        val arr = json.parseToJsonElement(res).jsonArray
        arr.mapNotNull {
            val o = it.jsonObject
            val id = o["workoutId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = o["workoutName"]?.jsonPrimitive?.contentOrNull.orEmpty()
            RemoteWorkout(id, name, "")
        }
    }

    override fun getWorkout(id: String): RemoteWorkout? = call(false) {
        val res = runCatching { connectApiJson("/workout-service/workout/$id") }.getOrNull() ?: return@call null
        val o = json.parseToJsonElement(res).jsonObject
        val name = o["workoutName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        RemoteWorkout(id, name, "")
    }

    override fun upsertWorkout(w: Workout, marker: String, existingId: String?): String = call(true) {
        val token = getValidAccessToken()
        val url = if (existingId.isNullOrBlank()) {
            "https://connectapi.garmin.com/workout-service/workout"
        } else {
            "https://connectapi.garmin.com/workout-service/workout/$existingId"
        }

        val jsonBody = Wire.json.encodeToString(Workout.serializer(), w)
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(url)
            .post(jsonBody)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", CONNECT_MOBILE_USER_AGENT)
            .build()

        val res = http.newCall(req).execute()
        if (!res.isSuccessful) throw ProviderError.Server(res.code)
        val respText = res.body?.string().orEmpty()
        val respJson = json.parseToJsonElement(respText).jsonObject
        respJson["workoutId"]?.jsonPrimitive?.contentOrNull ?: existingId ?: "ext_wkt_${System.currentTimeMillis()}"
    }

    override fun listCalendar(date: String): List<RemoteScheduled> = call(false) {
        val res = connectApiJson("/workout-service/schedule?date=$date")
        val arr = runCatching { json.parseToJsonElement(res).jsonArray }.getOrDefault(emptyList())
        arr.mapNotNull {
            val o = it.jsonObject
            val cId = o["calendarId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val wId = o["workoutId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            RemoteScheduled(cId, wId, date)
        }
    }

    override fun schedule(workoutId: String, date: String): String = call(true) {
        val token = getValidAccessToken()
        val url = "https://connectapi.garmin.com/workout-service/schedule/$workoutId"
        val payload = """{"date":"$date"}""".toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(payload)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", CONNECT_MOBILE_USER_AGENT)
            .build()

        val res = http.newCall(req).execute()
        if (!res.isSuccessful) throw ProviderError.Server(res.code)
        "ext_sched_${System.currentTimeMillis()}"
    }

    override fun requestDeviceSync() {
        call(false) { Unit }
    }

    private fun <T> call(mutating: Boolean, block: () -> T): T = try {
        block()
    } catch (e: ProviderError) {
        throw e
    } catch (e: IOException) {
        throw if (mutating) ProviderError.Timeout() else ProviderError.Network(e.javaClass.simpleName)
    } catch (e: Exception) {
        val m = e.message.orEmpty()
        Log.w(TAG, "Exception: $m", e)
        throw when {
            "TooManyRequests" in m || "429" in m -> ProviderError.RateLimited(null)
            "Authentication" in m || "401" in m || "Invalid credentials" in m || "Unexpected title" in m -> ProviderError.Unauthorized()
            "MFA" in m || "needs_mfa" in m -> ProviderError.MfaRequired()
            mutating -> ProviderError.Timeout()
            else -> ProviderError.Network(m)
        }
    }
}
