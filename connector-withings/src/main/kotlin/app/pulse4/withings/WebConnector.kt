package app.pulse4.withings

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

@Serializable
data class WithingsWebConfig(
    val baseUrl: String = "https://scalews.withings.com/cgi-bin/",
    val loginUrl: String = "https://account.withings.com/connection/login?r=https%3A%2F%2Fapp.withings.com%2F",
    // Stejná identifikace klienta jako webová aplikace app.withings.com (WS_APP_NAME / appliver = 8 znaků build hashe)
    val appName: String = "hmw",
    val appPfm: String = "web",
    val appLiver: String = "bb4050e3",
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

    /** Zda jde o dospělý účet (má vlastní e-mail nebo věk >= 18 let). */
    val isAdult: Boolean get() {
        if (!email.isNullOrBlank()) return true
        if (birthdate > 0L) {
            val nowSec = System.currentTimeMillis() / 1000L
            val ageYears = (nowSec - birthdate) / (365.25 * 86400.0)
            return ageYears >= 18.0
        }
        return isPrimary
    }

    /** Zda jde o dětský profil (nemá vlastní e-mail a věk < 18 let, nebo je vedený pod rodičem). */
    val isChild: Boolean get() = !isAdult

    /** Nalezne dospělý rodičovský profil v seznamu profilů, pod kterým je toto dítě vedeno. */
    fun findParent(allProfiles: List<WithingsUserProfile>): WithingsUserProfile? {
        if (!isChild) return null
        return allProfiles.firstOrNull { it.isAdult && ((parentAccountId != null && it.parentAccountId == parentAccountId) || it.id == parentAccountId) }
    }

    /** Nalezne všechny dětské profily vedené pod tímto dospělým účtem. */
    fun childrenUnderThis(allProfiles: List<WithingsUserProfile>): List<WithingsUserProfile> {
        if (!isAdult) return emptyList()
        return allProfiles.filter { it.isChild && ((parentAccountId != null && it.parentAccountId == parentAccountId) || it.parentAccountId == id) }
    }
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
        onDebugLog?.invoke("fetchUsersFromEndpoint $endpoint res.body: ${res.body}")
        val body = Wire.check(Wire.parse(res.body))
        return parseUsersFromBody(body)
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
     * Prodloužení přihlášené webové relace (`/cgi-bin/session`, `action=extend`).
     * Vrací novou strukturu [WithingsExtendedSession] se sloučenými cookies a obnoveným tokenem.
     */
    fun extendSession(cookies: String, sessionToken: String? = null): WithingsExtendedSession? {
        val form = baseForm("extend", cookies, sessionToken)
        val res = runCatching { post("session", form, cookies, sessionToken) }.getOrNull() ?: return null
        val env = runCatching { Wire.parse(res.body) }.getOrNull() ?: return null
        if (env.status != 0) return null
        val body = env.body ?: return null
        val newToken = body["session_token"]?.jsonPrimitive?.contentOrNull
            ?: extractCookieValue(res.headers["Set-Cookie"].orEmpty(), "session_token")
            ?: sessionToken
        var updatedCookies = mergeCookies(cookies, res.headers["Set-Cookie"], newToken)
        // Nové sessionid z prodloužení = nová hodnota cookie session_key
        body["sessionid"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { sid ->
            updatedCookies = mergeCookies(updatedCookies, "session_key=$sid", null)
        }
        return WithingsExtendedSession(newToken, updatedCookies)
    }

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
    ): List<WithingsEcgSummary> {
        val form = baseForm("list", cookies, sessionToken).apply {
            put("userid", userId.toString())
            if (startDateSec != null) put("startdate", startDateSec.toString())
            if (endDateSec != null) put("enddate", endDateSec.toString())
        }
        val res = runCatching { post("v2/heart", form, cookies, sessionToken) }.getOrNull() ?: return emptyList()
        val env = runCatching { Wire.parse(res.body) }.getOrNull() ?: return emptyList()
        if (env.status != 0) return emptyList()
        val series = (env.body?.get("series") as? JsonArray).orEmpty()
        return series.mapNotNull { elem ->
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
                    if (lower !in setOf("path", "domain", "expires", "max-age", "samesite", "secure", "httponly") &&
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
                        if (weightInGroup != null && weightInGroup > 18.0 && m.value in 5.0..(weightInGroup * 0.85)) {
                            // Pokud je hodnota menší než 72 % váhy v kg (např. 49.9 kg vody u 93 kg člověka), převedeme na %
                            if (m.value <= weightInGroup * 0.72) {
                                val pct = (m.value / weightInGroup) * 100.0
                                Triple(MetricType.BODY_WATER, pct, pct in 25.0..85.0)
                            } else {
                                Triple(MetricType.BODY_WATER, m.value, m.value in 25.0..85.0)
                            }
                        } else {
                            Triple(MetricType.BODY_WATER, m.value, m.value in 25.0..85.0)
                        }
                    }
                    WithingsWebTypes.HEART_RATE -> Triple(MetricType.HEART_RATE, m.value, m.value in 30.0..240.0)
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
                    quality = quality,
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
            val ext = "ecg:${s.signalId}"
            val matchedHr = heartRatesBySec.entries
                .firstOrNull { kotlin.math.abs(it.key - s.timestampSec) <= 300L }
                ?.value
            val valueToStore = s.qtcMs
                ?: s.qrsMs
                ?: s.heartRate?.toDouble()
                ?: matchedHr
                ?: 72.0
            val hrInfo = s.heartRate ?: matchedHr?.toInt() ?: 0
            val afibInfo = s.afibClassification ?: -1
            out += MetricObservation(
                id = ImportKey.of(Provider.WITHINGS_API, uidStr, ext, listOf(MetricType.ECG.name, start.toString())),
                provider = Provider.WITHINGS_API,
                accountId = uidStr,
                externalId = ext,
                type = MetricType.ECG,
                startUtcMs = start,
                endUtcMs = null,
                value = valueToStore,
                zoneId = defaultZone,
                localDate = TimeRules.localDate(start, defaultZone).toString(),
                quality = Quality.OK,
                sourceVersion = 1,
                origin = "withings:ecg:sig=${s.signalId}:afib=$afibInfo:hr=$hrInfo",
            )
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
    private val config: WithingsWebConfig = WithingsWebConfig(),
    private val existingHeartRates: () -> Map<Long, Double> = { emptyMap() },
) : AreaFetcher {
    var skippedItems = 0
        private set

    override fun fetch(area: SyncArea, sinceMs: Long?): Batch {
        if (area != SyncArea.WEIGHT) return Batch(emptyList(), sinceMs ?: nowMs())
        var s = sessionProvider() ?: throw ProviderError.NotConfigured("Withings webová relace není připojena")
        if (!s.isConnected()) throw ProviderError.Unauthorized()

        val now = nowMs()
        // Proaktivní udržování relace: pokud od posledního prodloužení uplynula více než 1 hodina,
        // bezpečně relaci prodloužíme přes scalews.withings.com ještě před vypršením tokenu.
        val lastExt = s.lastExtendedMs ?: 0L
        if (now - lastExt > 1 * 3600 * 1000L) {
            val extended = runCatching { client.extendSession(s.cookies, s.sessionToken) }.getOrNull()
            if (extended != null) {
                val updated = s.copy(
                    sessionToken = extended.sessionToken ?: s.sessionToken,
                    cookies = extended.cookies,
                    lastExtendedMs = now
                )
                onSessionExtended(updated)
                s = updated
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

        val ecgItems = runCatching {
            // Voláme v2/heart?action=list bez omezujícího startdate (stejně jako webová aplikace Withings v HARu),
            // aby se vždy načetla kompletní historie EKG záznamů.
            val ecgs = client.getHeartList(
                cookies = s.cookies,
                userId = s.primaryUserId,
                sessionToken = s.sessionToken
            )
            WithingsWebMapper.mapEcgSummaries(ecgs, s.primaryUserId, zone, hrBySec)
        }.getOrDefault(emptyList())

        skippedItems += windowMapped.skipped + (modifiedMapped?.skipped ?: 0)

        // Sloučíme položky (při kolizi vyhrává novější z modifiedMapped)
        val mergedById = LinkedHashMap<String, MetricObservation>()
        windowMapped.items.forEach { mergedById[it.id] = it }
        modifiedMapped?.items?.forEach { mergedById[it.id] = it }
        ecgItems.forEach { mergedById[it.id] = it }

        val allDeletedGrpIds = windowMapped.deletedGroupIds + (modifiedMapped?.deletedGroupIds ?: emptySet())
        val existingInWindow = existingIds(fromMs, now)

        // 1. Tombstony z porovnání okna (včetně EKG a modified položek, aby se EKG záznamy nesmazaly!)
        val fetchedIdsInWindow = (windowMapped.items.map { it.id } + modifiedMapped?.items.orEmpty().map { it.id } + ecgItems.map { it.id }).toSet()
        val windowTombstones = if (windowMapped.items.isEmpty()) {
            emptyList()
        } else {
            Reconcile.tombstones(existingInWindow, fetchedIdsInWindow)
        }

        // 2. Explicitní tombstony ze skupin označených serverem jako `deleted`
        val explicitDeletedTombstones = if (allDeletedGrpIds.isNotEmpty()) {
            existingInWindow.filter { existingId ->
                allDeletedGrpIds.any { grpId -> existingId.contains("|id:$grpId:") }
            }
        } else emptyList()

        val allTombstones = (windowTombstones + explicitDeletedTombstones).distinct()
        val finalItems = ImportFilter.apply(mergedById.values.toList(), exclusions)

        return Batch(finalItems, now, allTombstones)
    }

    override fun refreshSession() {
        val s = sessionProvider() ?: throw ProviderError.Unauthorized()
        val extended = client.extendSession(s.cookies, s.sessionToken) ?: throw ProviderError.Unauthorized()
        val updated = s.copy(
            sessionToken = extended.sessionToken ?: s.sessionToken,
            cookies = extended.cookies,
            lastExtendedMs = nowMs()
        )
        onSessionExtended(updated)
    }
}
