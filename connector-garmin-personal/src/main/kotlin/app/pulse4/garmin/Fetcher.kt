package app.pulse4.garmin

import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.provider.AreaFetcher
import app.pulse4.provider.Batch
import app.pulse4.provider.HttpGateway
import app.pulse4.provider.HttpResponse
import app.pulse4.provider.ProviderError
import app.pulse4.provider.SyncArea
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Osobní API klíč patří uživateli a nikdy neopouští jeho telefon. */
fun interface ApiKeyProvider { fun apiKey(): String? }

class GarminPersonalFetcher(
    private val config: GarminEndpointsConfig,
    private val http: HttpGateway,
    private val keys: ApiKeyProvider,
    private val accountId: String,
    private val zoneId: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Při prvním importu: kolik dní zpět (volba aplikace, nikoli záruka historie služby). */
    private val initialDays: Int = 28,
    /** Původní odpověď poskytovatele se uchovává před mapováním; originál se nepřepisuje. */
    private val onRawResponse: (EndpointSpec, String) -> Unit = { _, _ -> },
    /** Čas posledního uloženého pozorování metriky (pro rozlišení „nikdy nebylo“ a „dříve bylo, teď přichází prázdné“). */
    private val latestObservationMs: (MetricType) -> Long? = { null },
) : AreaFetcher {
    private var accessToken: String? = null
    var skippedItems = 0
        private set

    override fun fetch(area: SyncArea, sinceMs: Long?): Batch {
        val specs = config.endpoints.filter { it.area == area }
        if (specs.isEmpty()) return Batch(emptyList(), sinceMs ?: nowMs())
        val now = nowMs()
        val from = sinceMs ?: (now - initialDays * DAY)
        val all = ArrayList<MetricObservation>()
        val responseCache = HashMap<String, HttpResponse>()
        val warnings = ArrayList<String>()
        var firstError: ProviderError? = null
        var healthyMetrics = 0
        var holdCursor = false
        val zone = runCatching { java.time.ZoneId.of(zoneId) }.getOrDefault(ZoneOffset.UTC)
        val todayDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        // Každá metrika se stahuje izolovaně: její chyba nesmí zahodit ostatní metriky téže oblasti (např. SpO2 vedle klidového tepu).
        for ((metric, group) in specs.groupBy { it.metric }) {
            val chain = group.filter { !it.fallback } + group.filter { it.fallback }
            var items: List<MetricObservation> = emptyList()
            var metricError: ProviderError? = null
            for (spec in chain) {
                try {
                    val r = fetchSpec(spec, from, now, responseCache)
                    if (r.isNotEmpty()) {
                        items = if (items.isEmpty()) {
                            r
                        } else {
                            // Primární vrátil starší dny bez dneška: ze zálohy doplníme jen chybějící dny.
                            val existingDates = items.mapNotNull { it.localDate }.toHashSet()
                            items + r.filter { it.localDate == null || it.localDate !in existingDates }
                        }
                        if (items.any { it.localDate == todayDate }) break
                    }
                } catch (e: ProviderError) {
                    if (e.isAreaWide()) throw e
                    if (metricError == null) metricError = e
                }
            }
            when {
                items.isNotEmpty() -> { all += items; healthyMetrics++ }
                metricError != null -> {
                    warnings += "${metric.name}=${metricError.errorCode}"
                    if (firstError == null) firstError = metricError
                    // 5xx je přechodné: kurzor se nechá stát, aby se okno příště stáhlo znovu; 403/NotConfigured se nezacyklí.
                    if (metricError is ProviderError.Server && metricError.code >= 500) holdCursor = true
                }
                else -> {
                    healthyMetrics++
                    // Prázdné okno je zdravé, pokud metrika dříve data neměla nebo jsou čerstvá; jinak je to diagnostika.
                    val latest = latestObservationMs(metric)
                    if (latest != null && now - latest > STALE_EMPTY_MS) warnings += "${metric.name}=$EMPTY_RESPONSE"
                }
            }
        }
        // Selhaly všechny metriky oblasti: žádný úspěch, chyba se předá enginu (stav, opakování).
        if (healthyMetrics == 0 && firstError != null) throw firstError
        // Kurzor se posouvá až po uložení dávky (SyncEngine); zde jen nabízí nový bod.
        val deduped = LinkedHashMap<String, MetricObservation>(all.size)
        for (obs in all) {
            val prev = deduped[obs.id]
            if (prev == null || (obs.endUtcMs ?: obs.startUtcMs) >= (prev.endUtcMs ?: prev.startUtcMs)) {
                deduped[obs.id] = obs
            }
        }
        val nextCursor = if (holdCursor) maxOf(from, now - initialDays * DAY) else now
        return Batch(deduped.values.toList(), nextCursor, warnings = warnings)
    }

    /** Stáhne celé okno jedné specifikace po kalendářních dnech v zóně uživatele; chyby se předávají jako [ProviderError]. */
    private fun fetchSpec(spec: EndpointSpec, from: Long, now: Long, cache: MutableMap<String, HttpResponse>): List<MetricObservation> {
        if (!spec.verified) throw ProviderError.NotConfigured("endpoint ${spec.area}/${spec.metric} není ověřen proti oficiální specifikaci")
        val zone = runCatching { java.time.ZoneId.of(zoneId) }.getOrDefault(ZoneOffset.UTC)
        val fromDate = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val toDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val spanDays = spec.maxRangeDays.coerceAtLeast(1).toLong()
        val out = ArrayList<MetricObservation>()
        var day = fromDate
        while (!day.isAfter(toDate)) {
            val chunkEnd = minOf(day.plusDays(spanDays - 1L), toDate)
            val res = call(spec, day, chunkEnd, cache)
            onRawResponse(spec, res.body)
            val mapped = ResponseMapper.map(spec, res.body, accountId, zoneId)
            skippedItems += mapped.skipped
            out += mapped.items
            day = chunkEnd.plusDays(1L)
        }
        return out
    }

    /** Chyby, které se týkají celého přístupu (ne jedné metriky): předávají se enginu beze změny. */
    private fun ProviderError.isAreaWide(): Boolean = when (this) {
        is ProviderError.Unauthorized, is ProviderError.RateLimited, is ProviderError.MfaRequired,
        is ProviderError.Network, is ProviderError.Timeout -> true
        is ProviderError.Forbidden, is ProviderError.NotConfigured, is ProviderError.Server -> false
    }

    override fun refreshSession() {
        val key = keys.apiKey() ?: throw ProviderError.Unauthorized()
        val t = config.token
        if (!t.verified) throw ProviderError.NotConfigured("výměna osobního klíče za token není ověřena")
        val form = t.form.mapValues { it.value.replace("{apiKey}", key) }
        val headers = t.headers.mapValues { it.value.replace("{apiKey}", key) }
        val res = send { http.postForm(t.url, form, headers) }
        check(res)
        val body = res.body.trim()
        accessToken = if (body.startsWith("ey") || t.accessTokenPath == "$" || t.accessTokenPath.isEmpty()) {
            body
        } else {
            runCatching {
                val root = Json.parseToJsonElement(body)
                JsonPath.string(root, t.accessTokenPath)
            }.getOrNull() ?: body
        }
        if (accessToken.isNullOrBlank()) throw ProviderError.Unauthorized()
        // expires_in jen informativní: access token platí nejvýše 24 h a obnova ze stejného klíče je automatická.
        runCatching {
            val root = Json.parseToJsonElement(body)
            t.expiresInPath?.let { (JsonPath.get(root, it) as? JsonPrimitive)?.longOrNull }
        }
    }

    private fun call(spec: EndpointSpec, fromDate: java.time.LocalDate, toDate: java.time.LocalDate, cache: MutableMap<String, HttpResponse>): HttpResponse {
        val fmt = DateTimeFormatter.ofPattern(spec.dateFormat)
        val url = config.baseUrl.trimEnd('/') + spec.path
            .replace("{from}", fmt.format(fromDate))
            .replace("{to}", fmt.format(toDate))
        cache[url]?.let { return it }
        if (accessToken == null) refreshSession()
        val headers = mapOf(config.authHeader to config.authValue.replace("{accessToken}", accessToken!!))
        return send { http.get(url, headers) }.also { check(it); cache[url] = it }
    }

    private fun send(block: () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: SocketTimeoutException) {
        throw ProviderError.Network("timeout čtení")
    } catch (e: IOException) {
        throw ProviderError.Network(e.javaClass.simpleName)
    }

    private fun check(r: HttpResponse) {
        when {
            r.code in 200..299 -> Unit
            r.code == 401 -> { accessToken = null; throw ProviderError.Unauthorized() }
            r.code == 403 -> throw ProviderError.Forbidden()
            r.code == 429 -> throw ProviderError.RateLimited(retryAfterSeconds(r))
            r.code >= 500 -> throw ProviderError.Server(r.code)
            else -> throw ProviderError.Server(r.code)
        }
    }

    /** `Retry-After` jako počet sekund; HTTP-datum se nepodporuje (vrátí `null` → exponenciální prodleva). */
    private fun retryAfterSeconds(r: HttpResponse): Long? =
        r.headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    companion object {
        /** Kód diagnostiky: celé stažené okno metriky vyšlo prázdné (404/204/prázdné tělo/nic mapovatelného). */
        const val EMPTY_RESPONSE = "EmptyResponse"
        /**
         * Pravidlo pro [EMPTY_RESPONSE]: jen když okno nevrátilo nic, metrika má uložená dřívější data a poslední z nich je
         * starší než tento práh. Prázdný den ani prázdné okno u metriky, která nikdy neměla data, chybou není.
         */
        const val STALE_EMPTY_MS = 7L * 24 * 3600 * 1000
        private const val DAY = 24L * 3600 * 1000
    }
}
