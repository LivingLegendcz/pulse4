package app.pulse4.garmin

import app.pulse4.domain.MetricObservation
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
        for (spec in specs) {
            if (!spec.verified) throw ProviderError.NotConfigured("endpoint ${spec.area}/${spec.metric} není ověřen proti oficiální specifikaci")
            var cursor = from
            while (cursor <= now) {
                val rangeEnd = minOf(cursor + spec.maxRangeDays * DAY - 1, now)
                val res = call(spec, cursor, rangeEnd)
                onRawResponse(spec, res.body)
                val mapped = ResponseMapper.map(spec, res.body, accountId, zoneId)
                skippedItems += mapped.skipped
                all += mapped.items
                cursor = rangeEnd + 1
            }
        }
        // Kurzor se posouvá až po uložení dávky (SyncEngine); zde jen nabízí nový bod.
        return Batch(all.distinctBy { it.id }, now)
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

    private fun call(spec: EndpointSpec, fromMs: Long, toMs: Long): HttpResponse {
        if (accessToken == null) refreshSession()
        val fmt = DateTimeFormatter.ofPattern(spec.dateFormat).withZone(ZoneOffset.UTC)
        val url = config.baseUrl.trimEnd('/') + spec.path
            .replace("{from}", fmt.format(Instant.ofEpochMilli(fromMs)))
            .replace("{to}", fmt.format(Instant.ofEpochMilli(toMs)))
        val headers = mapOf(config.authHeader to config.authValue.replace("{accessToken}", accessToken!!))
        return send { http.get(url, headers) }.also { check(it) }
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

    private companion object { const val DAY = 24L * 3600 * 1000 }
}
