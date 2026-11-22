package app.pulse4.withings

import app.pulse4.domain.Activity
import app.pulse4.domain.EcgAfibClassification
import app.pulse4.domain.ImportFilter
import app.pulse4.domain.ImportKey
import app.pulse4.domain.MeasurementExclusions
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Quality
import app.pulse4.domain.Reconcile
import app.pulse4.domain.TimeRules
import app.pulse4.provider.AreaFetcher
import app.pulse4.provider.Batch
import app.pulse4.provider.HttpGateway
import app.pulse4.provider.ProviderError
import app.pulse4.provider.SyncArea
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

sealed interface WithingsExtendResult {
    data class Success(val session: WithingsExtendedSession) : WithingsExtendResult
    data object Unauthorized : WithingsExtendResult
    data class TransientError(val reason: String) : WithingsExtendResult
}

const val WITHINGS_WEB_APPLIVER = "bb4050e3"

@Serializable
data class WithingsWebConfig(
    val baseUrl: String = "https://scalews.withings.com/cgi-bin/",
    val loginUrl: String = "https://account.withings.com/connection/login?r=https%3A%2F%2Fapp.withings.com%2F",
    // Stejná identifikace klienta jako webová aplikace app.withings.com (WS_APP_NAME / appliver = 8 znaků build hashe)
    val appName: String = "hmw",
    val appPfm: String = "web",
    val appLiver: String = WITHINGS_WEB_APPLIVER,
    val attribQuality: Map<Int, Quality> = mapOf(
        0 to Quality.OK,
        2 to Quality.OK,
        4 to Quality.OK,
        5 to Quality.OK,
        7 to Quality.OK,
        8 to Quality.OK,
        1 to Quality.SUSPECT
    )
)

@Serializable
data class WithingsUserProfile(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val shortName: String = "",
    val gender: Int = 0,
    val birthdate: Long = 0,
    val isPrimary: Boolean = false,
    val timezone: String? = null,
    val parentAccountId: Long? = null,
    val email: String? = null,
) {
    val displayName: String get() {
        val full = "$firstName $lastName".trim()
        return if (full.isNotBlank()) full else "Profil $id"
    }

    /** Age in years derived from [birthdate] (epoch seconds), or `null` when the API did not provide it. */
    fun ageYears(nowSec: Long = System.currentTimeMillis() / 1000L): Double? =
        if (birthdate > 0L) (nowSec - birthdate) / (365.25 * 86400.0) else null
}

@Serializable
data class WithingsExtendedSession(
    val sessionToken: String?,
    val cookies: String,
)

@Serializable
data class WithingsWebSession(
    val cookies: String,
    val sessionToken: String? = null,
    val primaryUserId: Long,
    val primaryUserName: String = "",
    val primaryUserTimezone: String? = null,
    val childUserId: Long? = null,
    val childUserName: String? = null,
    val lastSyncMs: Long? = null,
    val lastExtendedMs: Long? = null,
    val allProfiles: List<WithingsUserProfile> = emptyList(),
    val email: String? = null,
) {
    fun isConnected(): Boolean = (cookies.isNotBlank() || !sessionToken.isNullOrBlank()) && primaryUserId > 0L

    override fun toString(): String =
        "WithingsWebSession(primaryUserId=$primaryUserId, primaryUserName=$primaryUserName, childUserId=$childUserId, cookies=***)"
}

@Serializable
data class WithingsEcgSummary(
    val signalId: Long,
    val timestampSec: Long,
    val heartRate: Int?,
    val afibClassification: Int?,
    val qrsMs: Double? = null,
    val prMs: Double? = null,
    val qtMs: Double? = null,
    val qtcMs: Double? = null,
    val samplingFrequencyHz: Int = 500,
    val deviceId: String? = null,
)

@Serializable
data class WithingsEcgSignal(
    val signalId: Long,
    val timestampSec: Long,
    val samplingFrequencyHz: Int,
    val samples: List<Int>,
)

object WithingsWebTypes {
    const val WEIGHT = 1
    const val HEIGHT = 4
    const val FAT_FREE_MASS = 5
    const val FAT_RATIO = 6
    const val FAT_MASS_WEIGHT = 8
    const val HEART_RATE = 11
    const val SPO2 = 54
    const val MUSCLE_MASS = 76
    const val HYDRATION = 77
    const val BONE_MASS = 88
    const val PWV = 91
    const val VASCULAR_AGE = 155
    const val EXTRACELLULAR_WATER = 168
    const val INTRACELLULAR_WATER = 169
    const val VISCERAL_FAT = 170
    const val SEGMENT_FAT_FREE = 173
    const val SEGMENT_FAT = 174
    const val SEGMENT_MUSCLE = 175
    const val BMR = 226
    const val METABOLIC_AGE = 227

    val allRequested = listOf(
        WEIGHT,
        FAT_FREE_MASS,
        FAT_RATIO,
        FAT_MASS_WEIGHT,
        HEART_RATE,
        SPO2,
        MUSCLE_MASS,
        HYDRATION,
        BONE_MASS,
        PWV,
        VASCULAR_AGE,
        VISCERAL_FAT,
        BMR,
        METABOLIC_AGE
    )
}

class WithingsWebClient(
    private val http: HttpGateway,
    private val config: WithingsWebConfig = WithingsWebConfig(),
) {
    /**
     * Načte seznam profilů z `/cgi-bin/account` (`action=getuserslist`) a pokud je prázdný nebo selže,
     * zkusí záložní `/cgi-bin/v2/account` (`action=get`).
     */
    var onDebugLog: ((String) -> Unit)? = null
    /** Strictly redacted trace of `session?action=extend` (HTTP/status codes & flags only, never tokens/cookies). */
    var onExtendTrace: ((String) -> Unit)? = null

    fun getUsersList(cookies: String, sessionToken: String? = null): List<WithingsUserProfile> {
        val list1 = runCatching { fetchUsersFromEndpoint("account", "getuserslist", cookies, sessionToken) }.getOrDefault(emptyList())
        val list2 = runCatching { fetchUsersFromEndpoint("v2/account", "get", cookies, sessionToken) }.getOrDefault(emptyList())
        val combined = (list1 + list2).distinctBy { it.id }
        if (combined.isNotEmpty()) return combined

        return fetchUsersFromEndpoint("account", "getuserslist", cookies, sessionToken)
    }

    private fun fetchUsersFromEndpoint(
        endpoint: String,
        action: String,
        cookies: String,
        sessionToken: String?,
    ): List<WithingsUserProfile> {
        val form = baseForm(action, cookies, sessionToken)
        val res = post(endpoint, form, cookies, sessionToken)
        val body = Wire.check(Wire.parse(res.body))
        val users = parseUsersFromBody(body)
        onDebugLog?.invoke("fetchUsersFromEndpoint $endpoint users=${users.size}")
        return users
    }

    private fun parseUsersFromBody(body: JsonObject): List<WithingsUserProfile> {
        val accountObj = (body["account"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: (body["account"] as? JsonObject)
        val accountTz = accountObj?.get("timezone")?.jsonPrimitive?.contentOrNull
            ?: body["timezone"]?.jsonPrimitive?.contentOrNull

        val usersArray = (body["users"] as? JsonArray)
            ?: (accountObj?.get("users") as? JsonArray)
            ?: return emptyList()

        return usersArray.mapNotNull { elem ->
            val o = elem.jsonObject
            val id = o["id"]?.jsonPrimitive?.longOrNull
                ?: o["userid"]?.jsonPrimitive?.longOrNull
                ?: return@mapNotNull null
            val status = o["status"]?.jsonPrimitive?.intOrNull ?: 0
            if (status < 0) return@mapNotNull null
            val pAccountId = o["idparentaccount"]?.jsonPrimitive?.longOrNull
                ?: o["parentid"]?.jsonPrimitive?.longOrNull
            val em = o["email"]?.jsonPrimitive?.contentOrNull
            WithingsUserProfile(
                id = id,
                firstName = o["firstname"]?.jsonPrimitive?.contentOrNull ?: "",
                lastName = o["lastname"]?.jsonPrimitive?.contentOrNull ?: "",
                shortName = o["shortname"]?.jsonPrimitive?.contentOrNull ?: "",
                gender = o["gender"]?.jsonPrimitive?.intOrNull ?: 0,
                birthdate = o["birthdate"]?.jsonPrimitive?.longOrNull ?: 0L,
                isPrimary = o["is_primary"]?.jsonPrimitive?.booleanOrNull
                    ?: (o["is_primary"]?.jsonPrimitive?.intOrNull == 1),
                timezone = o["timezone"]?.jsonPrimitive?.contentOrNull ?: accountTz,
                parentAccountId = pAccountId,
                email = em
            )
        }
    }

    /**
     * Prodloužení přihlášené webové relace (`/cgi-bin/session`, `action=extend`) s rozlišením důvodu selhání.
     *
     * Na rozdíl od čtecích endpointů (`/measure`, `/account`) vyžaduje `session?action=extend` ve formuláři
     * parametr `sessionid` (viz DevLog 14; bez něj server vrací `status=501`). Pokud server odmítne `session_key`
     * v poli `sessionid` stavem `250`, zkusí se jednou záložně bez `sessionid`.
     */
    fun extendSessionDetailed(cookies: String, sessionToken: String? = null): WithingsExtendResult {
        val form = baseForm("extend", cookies, sessionToken)
        val cookieSid = extractCookieValue(cookies, "sessionid")
        val cookieKey = extractCookieValue(cookies, "session_key")
        var usedKeyAsSid = false
        if (form["sessionid"].isNullOrBlank()) {
            val sidCandidate = cookieSid ?: cookieKey
            if (!sidCandidate.isNullOrBlank()) {
                form["sessionid"] = sidCandidate
                usedKeyAsSid = (cookieSid == null && cookieKey != null)
            }
        }
        val first = runExtendAttempt(form, cookies, sessionToken, sidSent = !form["sessionid"].isNullOrBlank())
        if (first is ExtendAttempt.Status250 && usedKeyAsSid) {
            val fallbackForm = baseForm("extend", cookies, sessionToken)
            return when (val second = runExtendAttempt(fallbackForm, cookies, sessionToken, sidSent = false)) {
                is ExtendAttempt.Done -> second.result
                is ExtendAttempt.Status250 -> {
                    onExtendTrace?.invoke("unauth(status=250,sidSent=0)")
                    WithingsExtendResult.Unauthorized
                }
            }
        }
        return when (first) {
            is ExtendAttempt.Done -> first.result
            is ExtendAttempt.Status250 -> {
                onExtendTrace?.invoke("unauth(status=250,sidSent=1)")
                WithingsExtendResult.Unauthorized
            }
        }
    }

    private sealed interface ExtendAttempt {
        data class Done(val result: WithingsExtendResult) : ExtendAttempt
        data object Status250 : ExtendAttempt
    }

    private fun runExtendAttempt(
        form: Map<String, String>,
        cookies: String,
        sessionToken: String?,
        sidSent: Boolean,
    ): ExtendAttempt {
        val sidTag = if (sidSent) "1" else "0"
        val res = try {
            post("session", form, cookies, sessionToken)
        } catch (e: ProviderError.Unauthorized) {
            // HTTP 401 on the keepalive means the session is dead. It used to be swallowed as "transient", so
            // the renewal ladder (SSO, stored password) never ran and only the network backoff kicked in.
            onExtendTrace?.invoke("unauth(http=401,sidSent=$sidTag)")
            return ExtendAttempt.Done(WithingsExtendResult.Unauthorized)
        } catch (e: ProviderError.Forbidden) {
            onExtendTrace?.invoke("unauth(http=403,sidSent=$sidTag)")
            return ExtendAttempt.Done(WithingsExtendResult.Unauthorized)
        } catch (e: ProviderError.RateLimited) {
            onExtendTrace?.invoke("transient(http=429,sidSent=$sidTag)")
            return ExtendAttempt.Done(WithingsExtendResult.TransientError("HTTP 429"))
        } catch (e: Exception) {
            onExtendTrace?.invoke("transient(${e::class.java.simpleName},sidSent=$sidTag)")
            return ExtendAttempt.Done(WithingsExtendResult.TransientError(e.message ?: "Network error"))
        }
        if (res.code == 429 || res.code >= 500) {
            onExtendTrace?.invoke("transient(http=${res.code},sidSent=$sidTag)")
            return ExtendAttempt.Done(WithingsExtendResult.TransientError("HTTP ${res.code}"))
        }
        val env = runCatching { Wire.parse(res.body) }.getOrNull()
            ?: run {
                onExtendTrace?.invoke("transient(bad_json,http=${res.code},sidSent=$sidTag)")
                return ExtendAttempt.Done(WithingsExtendResult.TransientError("Invalid JSON response"))
            }
        // 601 is Withings' own rate limit: not a sign of a dead session.
        if (env.status == 601) {
            onExtendTrace?.invoke("transient(status=601,sidSent=$sidTag)")
            return ExtendAttempt.Done(WithingsExtendResult.TransientError("status 601"))
        }
        if (env.status == 250) {
            return ExtendAttempt.Status250
        }
        if (env.status != 0) {
            onExtendTrace?.invoke("unauth(status=${env.status},sidSent=$sidTag)")
            return ExtendAttempt.Done(WithingsExtendResult.Unauthorized)
        }
        val body = env.body ?: run {
            onExtendTrace?.invoke("unauth(empty_body,sidSent=$sidTag)")
            return ExtendAttempt.Done(WithingsExtendResult.Unauthorized)
        }
        val setCookie = res.headers.entries.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }?.value
        val setCookieSessionKey = extractCookieValue(setCookie.orEmpty(), "session_key")
        val newToken = body["session_token"]?.jsonPrimitive?.contentOrNull
            ?: extractCookieValue(setCookie.orEmpty(), "session_token")
            ?: sessionToken
        var updatedCookies = mergeCookies(cookies, setCookie, newToken)
        // Nové sessionid z prodloužení použij jako session_key jen tehdy, pokud server neposlal čerstvé Set-Cookie: session_key.
        val bodySid = body["sessionid"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        if (setCookieSessionKey == null && bodySid != null) {
            updatedCookies = mergeCookies(updatedCookies, "session_key=$bodySid", null)
        }
        onExtendTrace?.invoke("ok(sidSent=$sidTag,scKey=${if (setCookieSessionKey != null) 1 else 0},sid=${if (bodySid != null) 1 else 0})")
        return ExtendAttempt.Done(WithingsExtendResult.Success(WithingsExtendedSession(newToken, updatedCookies)))
    }

    /**
     * Prodloužení přihlášené webové relace (`/cgi-bin/session`, `action=extend`).
     * Vrací novou strukturu [WithingsExtendedSession] se sloučenými cookies a obnoveným tokenem.
     */
    fun extendSession(cookies: String, sessionToken: String? = null): WithingsExtendedSession? =
        (extendSessionDetailed(cookies, sessionToken) as? WithingsExtendResult.Success)?.session

    /**
     * Načte skupiny měření z `/cgi-bin/measure` (`action=getmeas`).
     * Neposílá omezující `meastypes`, aby server vrátil kompletní skupinu (všechny složky těla a příznaky `deleted`),
     * přesně tak, jak to dělá webová aplikace Withings v HAR záznamu.
     */
    fun getMeasures(
        cookies: String,
        userId: Long,
        startDateSec: Long? = null,
        endDateSec: Long? = null,
        lastUpdateSec: Long? = null,
        sessionToken: String? = null,
    ): List<MeasureGroup> {
        val out = ArrayList<MeasureGroup>()
        var offset = 0L
        var guard = 0
        do {
            val form = baseForm("getmeas", cookies, sessionToken).apply {
                put("category", "1")
                put("userid", userId.toString())
                if (startDateSec != null && lastUpdateSec == null) put("startdate", startDateSec.toString())
                if (endDateSec != null && lastUpdateSec == null) put("enddate", endDateSec.toString())
                if (lastUpdateSec != null) put("lastupdate", lastUpdateSec.toString())
                if (offset > 0) put("offset", offset.toString())
            }

            val res = post("measure", form, cookies, sessionToken)
            val body = Wire.check(Wire.parse(res.body))
            out += WithingsApi.parse(body)
            val more = (body["more"]?.jsonPrimitive?.intOrNull ?: 0) != 0 ||
                (body["more"]?.jsonPrimitive?.booleanOrNull == true)
            offset = body["offset"]?.jsonPrimitive?.longOrNull ?: 0L
            if (++guard > 200) throw ProviderError.Server(0)
        } while (more && offset > 0L)
        return out
    }

    /**
     * Načte seznam záznamů EKG a srdečních signálů z `/cgi-bin/v2/heart` (`action=list`).
     */
    fun getHeartList(
        cookies: String,
        userId: Long,
        startDateSec: Long? = null,
        endDateSec: Long? = null,
        sessionToken: String? = null,
    ): Result<List<WithingsEcgSummary>> = runCatching {
        val form = baseForm("list", cookies, sessionToken).apply {
            put("userid", userId.toString())
            if (startDateSec != null) put("startdate", startDateSec.toString())
            if (endDateSec != null) put("enddate", endDateSec.toString())
        }
        val res = post("v2/heart", form, cookies, sessionToken)
        val env = Wire.parse(res.body)
        if (env.status != 0) throw ProviderError.Server(env.status)
        val series = (env.body?.get("series") as? JsonArray).orEmpty()
        series.mapNotNull { elem ->
            val o = elem.jsonObject
            val ecgObj = o["ecg"] as? JsonObject ?: o
            val sigId = ecgObj["signalid"]?.jsonPrimitive?.longOrNull
                ?: o["signalid"]?.jsonPrimitive?.longOrNull
                ?: return@mapNotNull null
            val ts = o["timestamp"]?.jsonPrimitive?.longOrNull
                ?: o["date"]?.jsonPrimitive?.longOrNull
                ?: return@mapNotNull null
            val hr = o["heart_rate"]?.jsonPrimitive?.intOrNull
                ?: ecgObj["heart_rate"]?.jsonPrimitive?.intOrNull
            val afib = ecgObj["afib"]?.jsonPrimitive?.intOrNull
                ?: o["afib"]?.jsonPrimitive?.intOrNull
            val qrs = ecgObj["qrs"]?.jsonPrimitive?.doubleOrNull
            val pr = ecgObj["pr"]?.jsonPrimitive?.doubleOrNull
            val qt = ecgObj["qt"]?.jsonPrimitive?.doubleOrNull
            val qtc = ecgObj["qtc"]?.jsonPrimitive?.doubleOrNull
            val dev = o["deviceid"]?.jsonPrimitive?.contentOrNull
            WithingsEcgSummary(
                signalId = sigId,
                timestampSec = ts,
                heartRate = hr,
                afibClassification = afib,
                qrsMs = qrs,
                prMs = pr,
                qtMs = qt,
                qtcMs = qtc,
                deviceId = dev
            )
        }
    }

    /**
     * Načte konkrétní křivku EKG signálu z `/cgi-bin/v2/signal` (`action=get`).
     */
    fun getSignal(
        cookies: String,
        signalId: Long,
        sessionToken: String? = null,
    ): WithingsEcgSignal? {
        val form = baseForm("get", cookies, sessionToken).apply {
            put("signalid", signalId.toString())
            put("with_filtered", "true")
        }
        val res = runCatching { post("v2/signal", form, cookies, sessionToken) }.getOrNull() ?: return null
        val env = runCatching { Wire.parse(res.body) }.getOrNull() ?: return null
        if (env.status != 0) return null
        val body = env.body ?: return null
        val sigObj = (body["signal"] as? JsonObject) ?: body
        val meta = (sigObj["meta"] as? JsonObject) ?: sigObj
        val freq = sigObj["metafreq"]?.jsonPrimitive?.intOrNull
            ?: meta["sampling_frequency"]?.jsonPrimitive?.intOrNull
            ?: 500
        val ts = sigObj["timestamp"]?.jsonPrimitive?.longOrNull
            ?: meta["timestamp"]?.jsonPrimitive?.longOrNull
            ?: 0L
        val rawArr = (sigObj["filtered_data"] as? JsonArray)
            ?: (sigObj["signal"] as? JsonArray)
            ?: return null
        val samples = rawArr.mapNotNull { it.jsonPrimitive.intOrNull }
        return WithingsEcgSignal(
            signalId = signalId,
            timestampSec = ts,
            samplingFrequencyHz = freq,
            samples = samples
        )
    }

    /**
     * Načte denní souhrny aktivity z `/cgi-bin/v2/measure` (`action=getactivity`) dle oficiálního API Withings.
     * Vrací [Result], aby nepodporovaný/prázdný endpoint u účtů bez hodinek nikdy nerozbil synchronizaci váhy.
     */
    fun getDailyActivities(
        cookies: String,
        userId: Long,
        startDateYmd: String,
        endDateYmd: String,
        sessionToken: String? = null,
    ): Result<List<WithingsDailyActivity>> = runCatching {
        val out = ArrayList<WithingsDailyActivity>()
        var offset = 0L
        var guard = 0
        do {
            val form = baseForm("getactivity", cookies, sessionToken).apply {
                put("userid", userId.toString())
                put("startdateymd", startDateYmd)
                put("enddateymd", endDateYmd)
                put("data_fields", WithingsWatchFields.ACTIVITY_DATA_FIELDS)
                if (offset > 0L) put("offset", offset.toString())
            }
            val res = post("v2/measure", form, cookies, sessionToken)
            val env = Wire.parse(res.body)
            if (env.status != 0) throw ProviderError.Server(env.status)
            val body = env.body ?: break
            out += WithingsWatchParser.parseDailyActivities(body)
            val more = (body["more"]?.jsonPrimitive?.intOrNull ?: 0) != 0 ||
                (body["more"]?.jsonPrimitive?.booleanOrNull == true)
            offset = body["offset"]?.jsonPrimitive?.longOrNull ?: 0L
            if (++guard > 100) break
        } while (more && offset > 0L)
        out
    }

    /**
     * Načte spánkové souhrny z `/cgi-bin/v2/sleep` (`action=getsummary`) dle oficiálního API Withings.
     */
    fun getSleepSummaries(
        cookies: String,
        userId: Long,
        startDateYmd: String,
        endDateYmd: String,
        sessionToken: String? = null,
    ): Result<List<WithingsSleepSummary>> = runCatching {
        val out = ArrayList<WithingsSleepSummary>()
        var offset = 0L
        var guard = 0
        do {
            val form = baseForm("getsummary", cookies, sessionToken).apply {
                put("userid", userId.toString())
                put("startdateymd", startDateYmd)
                put("enddateymd", endDateYmd)
                put("data_fields", WithingsWatchFields.SLEEP_DATA_FIELDS)
                if (offset > 0L) put("offset", offset.toString())
            }
            val res = post("v2/sleep", form, cookies, sessionToken)
            val env = Wire.parse(res.body)
            if (env.status != 0) throw ProviderError.Server(env.status)
            val body = env.body ?: break
            out += WithingsWatchParser.parseSleepSummaries(body)
            val more = (body["more"]?.jsonPrimitive?.intOrNull ?: 0) != 0 ||
                (body["more"]?.jsonPrimitive?.booleanOrNull == true)
            offset = body["offset"]?.jsonPrimitive?.longOrNull ?: 0L
            if (++guard > 100) break
        } while (more && offset > 0L)
        out
    }

    /**
     * Načte jednotlivé sportovní tréninky z `/cgi-bin/v2/measure` (`action=getworkouts`) dle oficiálního API Withings.
     */
    fun getWorkouts(
        cookies: String,
        userId: Long,
        startDateYmd: String,
        endDateYmd: String,
        sessionToken: String? = null,
    ): Result<List<WithingsWorkout>> = runCatching {
        val out = ArrayList<WithingsWorkout>()
        var offset = 0L
        var guard = 0
        do {
            val form = baseForm("getworkouts", cookies, sessionToken).apply {
                put("userid", userId.toString())
                put("startdateymd", startDateYmd)
                put("enddateymd", endDateYmd)
                put("data_fields", WithingsWatchFields.WORKOUT_DATA_FIELDS)
                if (offset > 0L) put("offset", offset.toString())
            }
            val res = post("v2/measure", form, cookies, sessionToken)
            val env = Wire.parse(res.body)
            if (env.status != 0) throw ProviderError.Server(env.status)
            val body = env.body ?: break
            out += WithingsWatchParser.parseWorkouts(body)
            val more = (body["more"]?.jsonPrimitive?.intOrNull ?: 0) != 0 ||
                (body["more"]?.jsonPrimitive?.booleanOrNull == true)
            offset = body["offset"]?.jsonPrimitive?.longOrNull ?: 0L
            if (++guard > 100) break
        } while (more && offset > 0L)
        out
    }

    private fun post(endpoint: String, form: Map<String, String>, cookies: String, sessionToken: String? = null) = try {
        val r = http.postForm("${config.baseUrl}$endpoint", form, defaultHeaders(cookies, sessionToken))
        if (r.code == 429) {
            val retryAfter = r.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.toLongOrNull()
            throw ProviderError.RateLimited(retryAfter)
        }
        if (r.code == 401 || r.code == 403) {
            throw ProviderError.Unauthorized()
        }
        r
    } catch (e: IOException) {
        throw ProviderError.Network(e.javaClass.simpleName)
    }

    private fun baseForm(action: String, cookies: String, explicitToken: String?): LinkedHashMap<String, String> {
        val map = linkedMapOf(
            "action" to action,
            "appname" to config.appName,
            "apppfm" to config.appPfm,
            "appliver" to config.appLiver,
        )
        // Server ověřuje relaci cookie `session_key` (shodně s webem, který posílá cookies přes withCredentials).
        // Parametr `sessionid` se smí poslat jen se SKUTEČNÝM sessionid – dosazení jiného tokenu
        // vede na status 250 („credentials do not match“), který se projevoval jako 401.
        val sessionKey = extractCookieValue(cookies, "session_key")
        val sessionid = extractCookieValue(cookies, "sessionid")
        val sessionToken = extractCookieValue(cookies, "session_token")
            ?: explicitToken?.takeIf { it != sessionKey && it != sessionid }

        if (sessionKey.isNullOrBlank() && !sessionid.isNullOrBlank()) {
            map["sessionid"] = sessionid
        }
        if (!sessionToken.isNullOrBlank()) {
            map["session_token"] = sessionToken
        }
        return map
    }

    fun mergeCookies(existingCookies: String, setCookieHeader: String?, newToken: String?): String {
        val map = LinkedHashMap<String, String>()
        existingCookies.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach {
                val idx = it.indexOf('=')
                if (idx > 0) {
                    val k = it.substring(0, idx).trim()
                    val v = it.substring(idx + 1).trim()
                    if (v.isNotEmpty() && v != "deleted") map[k] = v
                }
            }
        setCookieHeader?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { part ->
                val idx = part.indexOf('=')
                if (idx > 0) {
                    val k = part.substring(0, idx).trim()
                    val v = part.substring(idx + 1).trim()
                    val lower = k.lowercase()
                    if (lower !in setOf("path", "domain", "expires", "max-age", "samesite", "secure", "httponly", "priority") &&
                        v.isNotEmpty() && v != "deleted"
                    ) {
                        map[k] = v
                    }
                }
            }
        if (!newToken.isNullOrBlank() && newToken != map["session_key"] && newToken != map["sessionid"]) {
            map["session_token"] = newToken
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun extractCookieValue(cookies: String, name: String): String? {
        return cookies.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun defaultHeaders(cookies: String, sessionToken: String? = null) = buildMap {
        val effective = if (!sessionToken.isNullOrBlank()) {
            mergeCookies(cookies, null, sessionToken)
        } else cookies
        if (effective.isNotBlank()) put("Cookie", effective)
        put("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
        put("Origin", "https://app.withings.com")
        put("Referer", "https://app.withings.com/")
    }
}

object WithingsWebMapper {
    class MapResult(
        val items: List<MetricObservation>,
        val deletedGroupIds: Set<Long>,
        val skipped: Int,
    )

    fun map(
        groups: List<MeasureGroup>,
        userId: Long,
        defaultZone: String,
        config: WithingsWebConfig = WithingsWebConfig(),
    ): MapResult {
        var skipped = 0
        val out = ArrayList<MetricObservation>()
        val deletedGrpIds = LinkedHashSet<Long>()
        val uidStr = userId.toString()

        // Mapování vah podle času pro případ, že váha a bioimpedance jsou v sousedních skupinách téhož vážení
        val weightsByDateSec = groups
            .filter { it.category == 1 && !it.deleted }
            .mapNotNull { g ->
                val w = g.measures.firstOrNull { it.type == WithingsWebTypes.WEIGHT && (it.position == null || it.position == 7 || it.position == 0) }?.value
                if (w != null) g.date to w else null
            }

        for (g in groups) {
            if (g.category != 1) continue
            if (g.deleted) {
                deletedGrpIds += g.grpId
                continue
            }
            val zone = g.timezone?.takeIf { runCatching { java.time.ZoneId.of(it) }.isSuccess } ?: defaultZone
            val quality = config.attribQuality[g.attrib] ?: Quality.OK

            // Celková váha ve skupině (nebo ze stejného vážení ±300 s) pro přepočet absolutní hydratace (kg) na procenta (%)
            val weightInGroup = g.measures.firstOrNull { it.type == WithingsWebTypes.WEIGHT && (it.position == null || it.position == 7 || it.position == 0) }?.value
                ?: weightsByDateSec.firstOrNull { kotlin.math.abs(it.first - g.date) <= 300L }?.second

            // U vah Withings Body Scan / Body Comp mají celotělové bioimpedanční metriky (tuk, svaly, voda, kosti,
            // viscerální tuk, BMR, metabolický věk) position == 7 (Whole Body), zatímco končetiny/trup mají position
            // 1..5, 8..12. Pro každý typ ve skupině vybereme celotělové měření s prioritou: null -> 7 -> 0.
            val wholeBodyMeasures = g.measures
                .filter { it.position == null || it.position == 7 || it.position == 0 }
                .groupBy { it.type }
                .mapValues { (_, list) ->
                    list.minByOrNull { m ->
                        when (m.position) {
                            null -> 0
                            7 -> 1
                            0 -> 2
                            else -> 3
                        }
                    }!!
                }
                .values

            for (m in wholeBodyMeasures) {
                val (type, normValue, ok) = when (m.type) {
                    WithingsWebTypes.WEIGHT -> Triple(MetricType.WEIGHT, m.value, m.value in 10.0..500.0)
                    WithingsWebTypes.FAT_RATIO -> Triple(MetricType.BODY_FAT, m.value, m.value in 1.0..80.0)
                    WithingsWebTypes.FAT_FREE_MASS -> Triple(MetricType.FAT_FREE_MASS, m.value, m.value in 5.0..250.0)
                    WithingsWebTypes.MUSCLE_MASS -> Triple(MetricType.MUSCLE_MASS, m.value, m.value in 5.0..200.0)
                    WithingsWebTypes.BONE_MASS -> Triple(MetricType.BONE_MASS, m.value, m.value in 0.5..15.0)
                    WithingsWebTypes.HYDRATION -> {
                        if (weightInGroup == null || weightInGroup <= 18.0) {
                            skipped++
                            continue
                        }
                        val pct = (m.value / weightInGroup) * 100.0
                        Triple(MetricType.BODY_WATER, pct, pct.isFinite() && pct > 0.0)
                    }
                    WithingsWebTypes.HEART_RATE -> Triple(MetricType.HEART_RATE, m.value, m.value in 30.0..240.0)
                    WithingsWebTypes.SPO2 -> Triple(MetricType.SPO2, m.value, m.value in 50.0..100.0)
                    WithingsWebTypes.PWV -> Triple(MetricType.PULSE_WAVE_VELOCITY, m.value, m.value in 2.0..25.0)
                    WithingsWebTypes.VASCULAR_AGE -> Triple(MetricType.VASCULAR_AGE, m.value, m.value in 10.0..120.0)
                    WithingsWebTypes.VISCERAL_FAT -> Triple(MetricType.VISCERAL_FAT, m.value, m.value in 0.0..50.0)
                    WithingsWebTypes.BMR -> Triple(MetricType.BMR, m.value, m.value in 500.0..6000.0)
                    WithingsWebTypes.METABOLIC_AGE -> Triple(MetricType.METABOLIC_AGE, m.value, m.value in 10.0..120.0)
                    else -> continue
                }
                if (!ok) {
                    skipped++
                    continue
                }
                val ext = "${g.grpId}:${m.type}"
                val start = g.date * 1000L
                val segSuffix = buildSegmentalOriginSuffix(g.measures)
                val baseOrigin = g.deviceId?.let { "withings:web:$it" } ?: "withings:web"
                val effectiveQuality = if (type == MetricType.BODY_WATER && normValue !in 25.0..85.0) {
                    Quality.SUSPECT
                } else {
                    quality
                }
                out += MetricObservation(
                    id = ImportKey.of(Provider.WITHINGS_API, uidStr, ext, listOf(type.name, start.toString())),
                    provider = Provider.WITHINGS_API,
                    accountId = uidStr,
                    externalId = ext,
                    type = type,
                    startUtcMs = start,
                    endUtcMs = null,
                    value = normValue,
                    zoneId = zone,
                    localDate = TimeRules.localDate(start, zone).toString(),
                    quality = effectiveQuality,
                    sourceVersion = 1,
                    origin = if (segSuffix.isNotEmpty() && type in setOf(MetricType.WEIGHT, MetricType.MUSCLE_MASS, MetricType.BODY_FAT, MetricType.FAT_FREE_MASS)) {
                        "$baseOrigin$segSuffix"
                    } else {
                        baseOrigin
                    },
                )
            }
        }
        return MapResult(out.distinctBy { it.id }, deletedGrpIds, skipped)
    }

    private fun buildSegmentalOriginSuffix(measures: List<Measure>): String {
        val segPositions = listOf(2, 3, 12, 10, 11) // Pravá ruka (2), Levá ruka (3), Trup (12), Pravá noha (10), Levá noha (11)
        fun encodeType(t: Int): String {
            val byPos = measures.filter { it.type == t && it.position in segPositions }.associate { it.position!! to it.value }
            if (byPos.isEmpty()) return ""
            return segPositions.mapNotNull { p -> byPos[p]?.let { v -> "$p=${"%.2f".format(java.util.Locale.US, v)}" } }.joinToString(",")
        }
        val mStr = encodeType(WithingsWebTypes.SEGMENT_MUSCLE)
        val fStr = encodeType(WithingsWebTypes.SEGMENT_FAT)
        val ffmStr = encodeType(WithingsWebTypes.SEGMENT_FAT_FREE)
        return buildString {
            if (mStr.isNotEmpty()) append("|segm:").append(mStr)
            if (fStr.isNotEmpty()) append("|segf:").append(fStr)
            if (ffmStr.isNotEmpty()) append("|segffm:").append(ffmStr)
        }
    }

    fun mapEcgSummaries(
        summaries: List<WithingsEcgSummary>,
        userId: Long,
        defaultZone: String,
        heartRatesBySec: Map<Long, Double> = emptyMap(),
    ): List<MetricObservation> {
        val uidStr = userId.toString()
        val out = ArrayList<MetricObservation>()
        for (s in summaries) {
            val start = s.timestampSec * 1000L
            val matchedHr = heartRatesBySec.entries
                .firstOrNull { kotlin.math.abs(it.key - s.timestampSec) <= 300L }
                ?.value
            val hrValue = s.heartRate?.toDouble() ?: matchedHr
            val hrInfo = s.heartRate ?: matchedHr?.toInt() ?: 0
            val afibClass = EcgAfibClassification.fromWithingsCode(s.afibClassification)
            val afibInfo = s.afibClassification ?: -1
            val localDate = TimeRules.localDate(start, defaultZone).toString()
            val origin = "withings:ecg:sig=${s.signalId}:afib=$afibInfo:hr=$hrInfo"

            fun addMetric(suffix: String, type: MetricType, value: Double) {
                if (!value.isFinite()) return
                val ext = "ecg:${s.signalId}:$suffix"
                out += MetricObservation(
                    id = ImportKey.of(Provider.WITHINGS_API, uidStr, ext, listOf(type.name, start.toString())),
                    provider = Provider.WITHINGS_API,
                    accountId = uidStr,
                    externalId = ext,
                    type = type,
                    startUtcMs = start,
                    endUtcMs = null,
                    value = value,
                    zoneId = defaultZone,
                    localDate = localDate,
                    quality = Quality.OK,
                    sourceVersion = 1,
                    origin = origin,
                )
            }

            addMetric("afib", MetricType.ECG_AFIB, afibClass.code)
            hrValue?.let { addMetric("hr", MetricType.ECG_HR, it) }
            s.qtcMs?.let { addMetric("qtc", MetricType.ECG_QTC, it) }
            s.qrsMs?.let { addMetric("qrs", MetricType.ECG_QRS, it) }
        }
        return out
    }
}

/**
 * Plnohodnotný `AreaFetcher` pro `SyncEngine` nad přihlášenou webovou relací Withings (`scalews.withings.com`).
 * - Čte časové okno `[fromMs, nowMs]` (s překryvem z `SyncEngine`),
 * - Při inkrementální synchronizaci čte také `lastupdate` pro zachycení zpětných oprav a explicitně smazaných skupin (`deleted`),
 * - Generuje tombstony pro záznamy, které v okně zmizely nebo mají příznak `deleted`,
 * - Aplikuje uživatelská vyloučení (`MeasurementExclusions`),
 * - Při 401 se pokusí prodloužit webovou relaci přes `session?action=extend`.
 */
class WithingsWebFetcher(
    private val client: WithingsWebClient,
    private val sessionProvider: () -> WithingsWebSession?,
    private val onSessionExtended: (WithingsWebSession) -> Unit = {},
    private val defaultZone: String,
    private val existingIds: (Long, Long) -> Set<String>,
    private val exclusions: MeasurementExclusions = MeasurementExclusions { false },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val initialDays: Int = 730, // 2 roky historie při prvním importu
    private val initialWatchDays: Int = 90,
    private val config: WithingsWebConfig = WithingsWebConfig(),
    private val existingHeartRates: () -> Map<Long, Double> = { emptyMap() },
    private val onRefreshFallback: (() -> WithingsWebSession?)? = null,
    private val existingIdsByGrpIds: (Set<Long>) -> Set<String> = { emptySet() },
    private val onWorkouts: (List<Activity>) -> Unit = {},
) : AreaFetcher {
    var skippedItems = 0
        private set
    private var lastExtendFailedAtMs: Long = 0L

    override fun fetch(area: SyncArea, sinceMs: Long?): Batch {
        if (area != SyncArea.WEIGHT) return Batch(emptyList(), sinceMs ?: nowMs())
        var s = sessionProvider() ?: throw ProviderError.NotConfigured("Withings webová relace není připojena")
        if (!s.isConnected()) throw ProviderError.Unauthorized()

        val now = nowMs()
        // Proaktivní udržování relace: pokud od posledního prodloužení uplynulo více než 30 minut,
        // bezpečně relaci prodloužíme přes scalews.withings.com ještě před vypršením tokenu.
        val lastExt = s.lastExtendedMs ?: 0L
        if (now - lastExt > 30 * 60 * 1000L) {
            val extended = runCatching { client.extendSession(s.cookies, s.sessionToken) }.getOrNull()
            if (extended != null) {
                lastExtendFailedAtMs = 0L
                val updated = s.copy(
                    sessionToken = extended.sessionToken ?: s.sessionToken,
                    cookies = extended.cookies,
                    lastExtendedMs = now
                )
                onSessionExtended(updated)
                s = updated
            } else {
                lastExtendFailedAtMs = now
            }
        }

        val fromMs = sinceMs ?: (now - initialDays.toLong() * 86_400_000L)
        val fromSec = (fromMs / 1000L).coerceAtLeast(0L)
        val toSec = (now / 1000L).coerceAtLeast(fromSec)
        val zone = s.primaryUserTimezone?.takeIf { runCatching { java.time.ZoneId.of(it) }.isSuccess } ?: defaultZone

        val windowGroups = client.getMeasures(
            cookies = s.cookies,
            userId = s.primaryUserId,
            startDateSec = fromSec,
            endDateSec = toSec,
            sessionToken = s.sessionToken
        )
        val windowMapped = WithingsWebMapper.map(windowGroups, s.primaryUserId, zone, config)

        val modifiedMapped = if (sinceMs != null) {
            val modGroups = client.getMeasures(
                cookies = s.cookies,
                userId = s.primaryUserId,
                lastUpdateSec = fromSec,
                sessionToken = s.sessionToken
            )
            WithingsWebMapper.map(modGroups, s.primaryUserId, zone, config)
        } else null

        val hrBySec = existingHeartRates() + (windowMapped.items + modifiedMapped?.items.orEmpty())
            .filter { it.type == MetricType.HEART_RATE }
            .associate { (it.startUtcMs / 1000L) to it.value }

        val heartResult = client.getHeartList(
            cookies = s.cookies,
            userId = s.primaryUserId,
            sessionToken = s.sessionToken
        ).map { ecgs ->
            WithingsWebMapper.mapEcgSummaries(ecgs, s.primaryUserId, zone, hrBySec)
        }
        val heartOk = heartResult.isSuccess
        val ecgItems = heartResult.getOrDefault(emptyList())

        // Hodinky / spánkové senzory Withings (oficiální v2 API: getactivity, getsummary, getworkouts).
        // Volitelné – selhání či prázdná odpověď u účtů bez hodinek nesmí ovlivnit váhu ani tvořit tombstony.
        val uidStr = s.primaryUserId.toString()
        val watchFromMs = maxOf(fromMs, now - initialWatchDays.toLong() * 86_400_000L)
        val startYmd = WithingsWatchMapper.formatYmd(watchFromMs, zone)
        val endYmd = WithingsWatchMapper.formatYmd(now, zone)
        val dailyObs = client.getDailyActivities(s.cookies, s.primaryUserId, startYmd, endYmd, s.sessionToken)
            .map { WithingsWatchMapper.mapDailyActivities(it, uidStr, zone) }
            .getOrDefault(emptyList())
        val sleepObs = client.getSleepSummaries(s.cookies, s.primaryUserId, startYmd, endYmd, s.sessionToken)
            .map { WithingsWatchMapper.mapSleepSummaries(it, uidStr, zone) }
            .getOrDefault(emptyList())
        val (workouts, workoutObs) = client.getWorkouts(s.cookies, s.primaryUserId, startYmd, endYmd, s.sessionToken)
            .map { WithingsWatchMapper.mapWorkouts(it, uidStr, zone) }
            .getOrDefault(emptyList<Activity>() to emptyList())
        if (workouts.isNotEmpty()) onWorkouts(workouts)

        skippedItems += windowMapped.skipped + (modifiedMapped?.skipped ?: 0)

        // Sloučíme položky (při kolizi vyhrává novější z modifiedMapped)
        val mergedById = LinkedHashMap<String, MetricObservation>()
        windowMapped.items.forEach { mergedById[it.id] = it }
        modifiedMapped?.items?.forEach { mergedById[it.id] = it }
        ecgItems.forEach { mergedById[it.id] = it }
        dailyObs.forEach { mergedById[it.id] = it }
        sleepObs.forEach { mergedById[it.id] = it }
        workoutObs.forEach { mergedById[it.id] = it }

        val allDeletedGrpIds = windowMapped.deletedGroupIds + (modifiedMapped?.deletedGroupIds ?: emptySet())
        val existingInWindow = existingIds(fromMs, now)

        // 1. Tombstony z porovnání okna (hodinkové záznamy a při selhání v2/heart i ecg:* nikdy nerekoncilujeme jako smazané)
        val existingForReconcile = existingInWindow.filterNot { id ->
            id.contains("|id:act:") ||
                id.contains("|id:sleep:") ||
                id.contains("|id:wkt:") ||
                (!heartOk && id.contains("|id:ecg:"))
        }.toSet()
        val fetchedIdsInWindow = (windowMapped.items.map { it.id } + modifiedMapped?.items.orEmpty().map { it.id } + ecgItems.map { it.id }).toSet()
        val windowTombstones = if (windowMapped.items.isEmpty()) {
            emptyList()
        } else {
            Reconcile.tombstones(existingForReconcile, fetchedIdsInWindow)
        }

        // 2. Explicitní tombstony ze skupin označených serverem jako `deleted` (bez časového okna)
        val explicitDeletedTombstones = if (allDeletedGrpIds.isNotEmpty()) {
            val outsideWindowPool = existingIdsByGrpIds(allDeletedGrpIds)
            val pool = (existingInWindow + outsideWindowPool).distinct()
            pool.filter { existingId ->
                allDeletedGrpIds.any { grpId -> existingId.contains("|id:$grpId:") }
            }
        } else emptyList()

        val allTombstones = (windowTombstones + explicitDeletedTombstones).distinct()
        val finalItems = ImportFilter.apply(mergedById.values.toList(), exclusions)

        return Batch(finalItems, now, allTombstones)
    }

    override fun refreshSession() {
        val s = sessionProvider() ?: throw ProviderError.Unauthorized()
        val now = nowMs()
        val recentlyFailedExtend = lastExtendFailedAtMs > 0L && (now - lastExtendFailedAtMs) in 0 until 60_000L
        val extended = if (recentlyFailedExtend) {
            null
        } else {
            runCatching { client.extendSession(s.cookies, s.sessionToken) }.getOrNull()
        }
        if (extended != null) {
            lastExtendFailedAtMs = 0L
            val updated = s.copy(
                sessionToken = extended.sessionToken ?: s.sessionToken,
                cookies = extended.cookies,
                lastExtendedMs = now
            )
            onSessionExtended(updated)
            return
        }
        val fallbackSession = runCatching { onRefreshFallback?.invoke() }.getOrNull()
        if (fallbackSession != null && fallbackSession.isConnected()) {
            lastExtendFailedAtMs = 0L
            onSessionExtended(fallbackSession)
            return
        }
        throw ProviderError.Unauthorized()
    }
}
