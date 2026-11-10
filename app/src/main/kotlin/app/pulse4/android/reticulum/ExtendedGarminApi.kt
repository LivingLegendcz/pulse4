package app.pulse4.android.reticulum

import android.content.Context
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
    val oauth1Token: String,
    val oauth1Secret: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val refreshExpiresAtMs: Long? = null,
    val email: String? = null
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
        val signature = android.util.Base64.encodeToString(signatureBytes, android.util.Base64.NO_WRAP)
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
 * Čistě Kotlinový konektor pro Garmin Connect API (zápis a plánování tréninků, stahování FIT a telemetrie).
 * Heslo se nikdy neukládá; relace žije v uložených tokenech (šifrovaně přes Android Keystore).
 * Dlouhodobý OAuth 1.0 token umožňuje automatickou tichou obnovu OAuth 2.0 relace po celý rok.
 */
class ExtendedGarminApi(
    private val context: Context,
    private val secrets: SecretStore
) : GarminWorkoutApi {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(cookieStore) {
                val list = cookieStore.getOrPut(url.host) { mutableListOf() }
                for (c in cookies) {
                    list.removeAll { it.name == c.name }
                    list.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            synchronized(cookieStore) {
                return cookieStore[url.host]?.filter { it.expiresAt > System.currentTimeMillis() } ?: emptyList()
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

    fun loginPassword(email: String, password: String): LoginResult = call(mutating = false) {
        val ssoUrl = "https://sso.garmin.com/sso/signin?id=gauth-widget&embedWidget=true&gauthHost=https%3A%2F%2Fsso.garmin.com%2Fsso&service=https%3A%2F%2Fconnect.garmin.com%2Fmodern%2F&source=https%3A%2F%2Fconnect.garmin.com%2Fsignin&redirectAfterAccountLoginUrl=https%3A%2F%2Fconnect.garmin.com%2Fmodern%2F&redirectAfterAccountCreationUrl=https%3A%2F%2Fconnect.garmin.com%2Fmodern%2F"

        val ssoReq = Request.Builder()
            .url(ssoUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; M2007J20CG) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            .build()

        val ssoRes = http.newCall(ssoReq).execute()
        val ssoHtml = ssoRes.body?.string().orEmpty()

        val csrfMatch = Regex("""name="_csrf"\s+value="([^"]+)"""").find(ssoHtml)
            ?: Regex("""value="([^"]+)"\s+name="_csrf"""").find(ssoHtml)
        val csrf = csrfMatch?.groupValues?.get(1).orEmpty()

        val formBody = FormBody.Builder()
            .add("username", email)
            .add("password", password)
            .add("embed", "true")
            .add("_csrf", csrf)
            .build()

        val loginReq = Request.Builder()
            .url("https://sso.garmin.com/sso/signin")
            .post(formBody)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; M2007J20CG) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            .header("origin", "https://sso.garmin.com")
            .build()

        val loginRes = http.newCall(loginReq).execute()
        val loginBody = loginRes.body?.string().orEmpty()

        if (loginBody.contains("loginEnterMfaCode") || loginBody.contains("needs_mfa")) {
            val mfaCsrfMatch = Regex("""name="_csrf"\s+value="([^"]+)"""").find(loginBody)
            pendingMfaCsrf = mfaCsrfMatch?.groupValues?.get(1) ?: csrf
            return@call LoginResult.NeedsMfa
        }

        val ticket = extractTicket(loginBody, loginRes.request.url.toString())
            ?: throw ProviderError.Unauthorized()

        finishLoginWithTicket(ticket, email)
        LoginResult.Ok
    }

    fun resumeMfa(code: String): LoginResult = call(mutating = false) {
        val csrf = pendingMfaCsrf ?: throw ProviderError.Unauthorized()
        val mfaForm = FormBody.Builder()
            .add("mfa-code", code.trim())
            .add("embed", "true")
            .add("_csrf", csrf)
            .build()

        val mfaReq = Request.Builder()
            .url("https://sso.garmin.com/sso/verifyMFA/loginEnterMfaCode")
            .post(mfaForm)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; M2007J20CG) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            .header("origin", "https://sso.garmin.com")
            .build()

        val mfaRes = http.newCall(mfaReq).execute()
        val mfaBody = mfaRes.body?.string().orEmpty()

        val ticket = extractTicket(mfaBody, mfaRes.request.url.toString())
            ?: throw ProviderError.Unauthorized()

        finishLoginWithTicket(ticket, null)
        pendingMfaCsrf = null
        LoginResult.Ok
    }

    private fun extractTicket(body: String, requestUrl: String): String? {
        val match = Regex("""ticket=([A-Za-z0-9\-_]+)""").find(body)
            ?: Regex("""ticket=([A-Za-z0-9\-_]+)""").find(requestUrl)
        return match?.groupValues?.get(1)
    }

    private fun finishLoginWithTicket(ticket: String, email: String?) {
        val preAuthUrl = "https://connectapi.garmin.com/oauth-service/oauth/preauthorized?ticket=$ticket&login-url=https%3A%2F%2Fsso.garmin.com%2Fsso%2Fembed&accepts-mfa-tokens=true"
        val oauth1Header = OAuth1Signer.generateHeader("GET", preAuthUrl)

        val preAuthReq = Request.Builder()
            .url(preAuthUrl)
            .header("Authorization", oauth1Header)
            .header("User-Agent", "com.garmin.android.apps.connectmobile")
            .build()

        val preAuthRes = http.newCall(preAuthReq).execute()
        if (!preAuthRes.isSuccessful) throw ProviderError.Unauthorized()
        val preAuthBody = preAuthRes.body?.string().orEmpty()

        val oauth1Token = Regex("""oauth_token=([^&]+)""").find(preAuthBody)?.groupValues?.get(1)
            ?: throw ProviderError.Unauthorized()
        val oauth1Secret = Regex("""oauth_token_secret=([^&]+)""").find(preAuthBody)?.groupValues?.get(1)
            ?: throw ProviderError.Unauthorized()

        val tokens = exchangeOAuth1ForOAuth2(oauth1Token, oauth1Secret, email)
        saveTokens(tokens)
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
            .header("User-Agent", "com.garmin.android.apps.connectmobile")
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

    /**
     * Proaktivní keepalive: zkontroluje expiraci přístupového tokenu a v případě potřeby
     * provede tichou obnovu přes dlouhodobý OAuth 1.0 token (platný 365 dní).
     */
    @Synchronized
    fun ensureSession(): Boolean {
        val raw = secrets.getString(SecretStore.GARMIN_EXT_TOKENS) ?: return false
        val tokens = runCatching { json.decodeFromString<GarminExtTokens>(raw) }.getOrNull() ?: return false
        val now = System.currentTimeMillis()

        // Pokud tokenu zbývá více než 10 minut platnosti, je relace v pořádku
        if (tokens.expiresAtMs - now > 10 * 60 * 1000L) {
            return true
        }

        return runCatching {
            val fresh = exchangeOAuth1ForOAuth2(tokens.oauth1Token, tokens.oauth1Secret, tokens.email)
            saveTokens(fresh)
            true
        }.getOrElse { false }
    }

    private fun getValidAccessToken(): String {
        ensureSession()
        val raw = secrets.getString(SecretStore.GARMIN_EXT_TOKENS) ?: throw ProviderError.Unauthorized()
        val tokens = json.decodeFromString<GarminExtTokens>(raw)
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
            .header("User-Agent", "com.garmin.android.apps.connectmobile")
            .build()

        val res = http.newCall(req).execute()
        if (res.code == 401) {
            // Token mohl vypršet na straně serveru: zkusíme obnovit a opakovat jednou
            val renewed = ensureSession()
            if (renewed) {
                val newToken = getValidAccessToken()
                val retryReq = req.newBuilder().header("Authorization", "Bearer $newToken").build()
                val retryRes = http.newCall(retryReq).execute()
                if (retryRes.isSuccessful) return@call retryRes.body?.string().orEmpty()
            }
            throw ProviderError.Unauthorized()
        }
        if (res.code == 429) throw ProviderError.RateLimited(null)
        if (!res.isSuccessful) throw ProviderError.Server(res.code)
        res.body?.string().orEmpty()
    }

    /** Stáhne původní FIT soubor aktivity přes přihlášenou relaci Garmin Connect. */
    fun downloadActivityFit(activityId: String): ByteArray? = call(mutating = false) {
        val token = getValidAccessToken()
        val url = "https://connectapi.garmin.com/download-service/files/activity/$activityId"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "com.garmin.android.apps.connectmobile")
            .build()

        val res = http.newCall(req).execute()
        if (!res.isSuccessful) return@call null
        res.body?.bytes()
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
            .header("User-Agent", "com.garmin.android.apps.connectmobile")
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
            .header("User-Agent", "com.garmin.android.apps.connectmobile")
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
        android.util.Log.w("ExtendedGarminApi", "Exception: $m", e)
        throw when {
            "TooManyRequests" in m || "429" in m -> ProviderError.RateLimited(null)
            "Authentication" in m || "401" in m || "Invalid credentials" in m || "Unexpected title" in m -> ProviderError.Unauthorized()
            "MFA" in m || "needs_mfa" in m -> ProviderError.MfaRequired()
            mutating -> ProviderError.Timeout()
            else -> ProviderError.Network(m)
        }
    }
}
