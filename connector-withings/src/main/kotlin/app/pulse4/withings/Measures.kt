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
import kotlin.math.pow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class Measure(val type: Int, val value: Double)

/** Skupina měření jednoho vážení. `grpId` + typ dává stabilní externí ID. */
data class MeasureGroup(
    val grpId: Long,
    val date: Long,
    val modified: Long,
    val attrib: Int,
    val category: Int,
    val deviceId: String?,
    val timezone: String?,
    val measures: List<Measure>,
)

object WithingsTypes {
    const val WEIGHT = 1
    const val FAT_RATIO = 6
    val requested = listOf(WEIGHT, FAT_RATIO)
}

/** Čtení `getmeas` včetně stránkování. Při odmítnutí tokenu se jednou obnoví a zopakuje. */
class WithingsApi(
    private val config: WithingsConfig,
    private val session: WithingsSession,
    private val http: HttpGateway,
) {
    enum class Filter { DATE_WINDOW, LAST_UPDATE }

    fun getMeasures(filter: Filter, fromMs: Long, toMs: Long): List<MeasureGroup> {
        val out = ArrayList<MeasureGroup>()
        var offset = 0L
        var guard = 0
        do {
            val form = linkedMapOf("action" to "getmeas", "meastypes" to WithingsTypes.requested.joinToString(","), "category" to "1")
            when (filter) {
                Filter.DATE_WINDOW -> { form["startdate"] = (fromMs / 1000).toString(); form["enddate"] = (toMs / 1000).toString() }
                Filter.LAST_UPDATE -> form["lastupdate"] = (fromMs / 1000).toString()
            }
            if (offset > 0) form["offset"] = offset.toString()
            val body = call(form)
            out += parse(body)
            val more = (body["more"]?.jsonPrimitive?.intOrNull ?: 0) != 0
            offset = body["offset"]?.jsonPrimitive?.longOrNull ?: 0
            // Pojistka proti nekonečnému stránkování při chybné odpovědi.
            if (++guard > 200) throw ProviderError.Server(0)
        } while (more && offset > 0)
        return out
    }

    private fun call(form: Map<String, String>): JsonObject {
        var token = session.accessToken()
        repeat(2) { attempt ->
            val res = try {
                http.postForm(config.measureUrl, form, mapOf("Authorization" to "Bearer $token"))
            } catch (e: IOException) { throw ProviderError.Network(e.javaClass.simpleName) }
            if (res.code == 429) throw ProviderError.RateLimited(res.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.toLongOrNull())
            try { return Wire.check(Wire.parse(res.body)) } catch (e: ProviderError.Unauthorized) {
                if (attempt == 1) throw e
                token = session.refreshAfterRejection(token).accessToken
            }
        }
        throw ProviderError.Unauthorized()
    }

    companion object {
        fun parse(body: JsonObject): List<MeasureGroup> =
            (body["measuregrps"] as? JsonArray).orEmpty().mapNotNull { g ->
                val o = g.jsonObject
                val grp = o["grpid"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val date = o["date"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                MeasureGroup(
                    grp, date, o["modified"]?.jsonPrimitive?.longOrNull ?: date, o["attrib"]?.jsonPrimitive?.intOrNull ?: -1,
                    o["category"]?.jsonPrimitive?.intOrNull ?: 1, o["deviceid"]?.jsonPrimitive?.contentOrNull, o["timezone"]?.jsonPrimitive?.contentOrNull,
                    (o["measures"] as? JsonArray).orEmpty().mapNotNull { m ->
                        val mo = m.jsonObject
                        val v = mo["value"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                        val u = mo["unit"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        val t = mo["type"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        Measure(t, v * 10.0.pow(u))
                    },
                )
            }
    }
}

object WithingsMapper {
    class MapResult(val items: List<MetricObservation>, val skipped: Int)

    /**
     * Skupina → pozorování. Jednotky se normalizují při importu (kg, %). Mimo reálný rozsah se hodnota přeskočí
     * (ne nula), nejednoznačné přiřazení osoby (`attrib`) označí záznam jako podezřelý.
     */
    fun map(groups: List<MeasureGroup>, userId: String, defaultZone: String, config: WithingsConfig): MapResult {
        var skipped = 0
        val out = ArrayList<MetricObservation>()
        for (g in groups) {
            if (g.category != 1) continue // cíle uživatele nejsou měření
            val zone = g.timezone?.takeIf { runCatching { java.time.ZoneId.of(it) }.isSuccess } ?: defaultZone
            val quality = config.attribQuality[g.attrib] ?: Quality.SUSPECT
            for (m in g.measures) {
                val (type, ok) = when (m.type) {
                    WithingsTypes.WEIGHT -> MetricType.WEIGHT to (m.value in 10.0..500.0)
                    WithingsTypes.FAT_RATIO -> MetricType.BODY_FAT to (m.value in 1.0..80.0)
                    else -> continue
                }
                if (!ok) { skipped++; continue }
                val ext = "${g.grpId}:${m.type}"
                val start = g.date * 1000
                out += MetricObservation(
                    id = ImportKey.of(Provider.WITHINGS_API, userId, ext, listOf(type.name, start.toString())),
                    provider = Provider.WITHINGS_API, accountId = userId, externalId = ext, type = type,
                    startUtcMs = start, endUtcMs = null, value = m.value, zoneId = zone,
                    localDate = TimeRules.localDate(start, zone).toString(), quality = quality,
                    sourceVersion = 1, origin = g.deviceId?.let { "withings:$it" } ?: "withings",
                )
            }
        }
        return MapResult(out, skipped)
    }
}

/**
 * Importér váhy pro `SyncEngine` (oblast WEIGHT). Čte okno podle data měření (kvůli tombstonům) a navíc změny podle
 * `lastupdate` (opravy starších měření). Smazání se pozná jen tím, že měření z okna už chybí – proto při prázdné odpovědi
 * nebo při jakékoli chybě žádný tombstone nevzniká (výpadek nesmí smazat historii).
 */
class WithingsFetcher(
    private val config: WithingsConfig,
    private val api: WithingsApi,
    private val session: WithingsSession,
    private val userId: () -> String,
    private val zoneId: String,
    /** ID dosud uložených (nesmazaných) pozorování Withings v okně `[fromMs, toMs]` podle data měření. */
    private val existingIds: (Long, Long) -> Set<String>,
    private val exclusions: MeasurementExclusions = MeasurementExclusions { false },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val initialDays: Int = 365,
) : AreaFetcher {
    var skippedItems = 0; private set

    override fun fetch(area: SyncArea, sinceMs: Long?): Batch {
        if (area != SyncArea.WEIGHT) return Batch(emptyList(), sinceMs ?: nowMs())
        val now = nowMs()
        val from = sinceMs ?: (now - initialDays * 86_400_000L)
        val uid = userId()
        val window = WithingsMapper.map(api.getMeasures(WithingsApi.Filter.DATE_WINDOW, from, now), uid, zoneId, config)
        val modified = if (sinceMs != null) WithingsMapper.map(api.getMeasures(WithingsApi.Filter.LAST_UPDATE, from, now), uid, zoneId, config) else null
        skippedItems += window.skipped + (modified?.skipped ?: 0)

        val items = (window.items + modified?.items.orEmpty()).distinctBy { it.id }
        val tombstones = Reconcile.tombstones(existingIds(from, now), window.items.map { it.id }.toSet())
        return Batch(ImportFilter.apply(items, exclusions), now, tombstones)
    }

    /** Engine ho volá po 401: obnoví se uložený token; neplatný refresh token vyvolá Unauthorized (nutná nová autorizace). */
    override fun refreshSession() { session.refreshStored() }
}
