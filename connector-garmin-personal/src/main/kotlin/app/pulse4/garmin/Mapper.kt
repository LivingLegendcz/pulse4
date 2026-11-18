package app.pulse4.garmin

import app.pulse4.domain.MetricObservation
import app.pulse4.domain.Provider
import app.pulse4.domain.TimeRules
import app.pulse4.domain.ImportKey
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Jednoduchá tečkovaná cesta (`a.b.0.c`) nad JSON; chybějící uzel = `null`, nikdy výjimka ani nula. */
object JsonPath {
    fun get(root: JsonElement, path: String): JsonElement? {
        if (path.isEmpty() || path == "$") return root
        var cur: JsonElement = root
        for (part in path.split('.')) {
            cur = when (cur) {
                is JsonObject -> cur[part] ?: return null
                is JsonArray -> part.toIntOrNull()?.let { cur.getOrNull(it) } ?: return null
                else -> return null
            }
        }
        return if (cur is JsonNull) null else cur
    }

    fun string(root: JsonElement, path: String?): String? =
        path?.let { (get(root, it) as? JsonPrimitive)?.takeIf { p -> p.isString || p.content != "null" }?.content }

    fun double(root: JsonElement, path: String?): Double? =
        path?.let { (get(root, it) as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.isFinite() }
}

object ResponseMapper {
    private val json = Json { ignoreUnknownKeys = true }

    class MapResult(val items: List<MetricObservation>, val skipped: Int)

    /**
     * Převede odpověď na pozorování. Položka bez času nebo hodnoty se přeskočí (nepočítá se jako nula)
     * a započítá se do `skipped`, aby chyba mapování nebyla tichá.
     */
    fun map(spec: EndpointSpec, body: String, accountId: String, defaultZone: String): MapResult {
        if (body.isBlank()) return MapResult(emptyList(), 0)
        val root = json.parseToJsonElement(body)
        val itemsNode = if (spec.itemsPath == "$") null else JsonPath.get(root, spec.itemsPath)
        val items: List<JsonElement> = when {
            spec.itemsPath == "$" && root is JsonArray -> root.toList()
            spec.itemsPath == "$" -> listOf(root)
            itemsNode is JsonArray -> itemsNode.toList()
            itemsNode == null -> emptyList()
            else -> listOf(itemsNode)
        }
        var skipped = 0
        val out = ArrayList<MetricObservation>(items.size)
        for (item in items) {
            val start = parseTime(spec, JsonPath.get(item, spec.timePath))
            val value = JsonPath.double(item, spec.valuePath)
            if (start == null || value == null) { skipped++; continue }
            val end = spec.endTimePath?.let { parseTime(spec, JsonPath.get(item, it)) }?.takeIf { it >= start }
            val zone = JsonPath.string(item, spec.zonePath) ?: defaultZone
            val ext = JsonPath.string(item, spec.externalIdPath)
            val local = JsonPath.string(item, spec.localDatePath) ?: TimeRules.localDate(end ?: start, zone).toString()
            val scopedExt = ext?.let { "${spec.metric.name}:$it" }
            val key = ImportKey.of(Provider.GARMIN_PERSONAL, accountId, scopedExt, listOf(spec.metric.name, start.toString(), end?.toString() ?: ""))
            out += MetricObservation(
                id = key, provider = Provider.GARMIN_PERSONAL, accountId = accountId, externalId = ext,
                type = spec.metric, startUtcMs = start, endUtcMs = end, value = value * spec.scale,
                zoneId = zone, localDate = local,
            )
        }
        return MapResult(out, skipped)
    }

    private fun parseTime(spec: EndpointSpec, e: JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        return when (spec.timeFormat) {
            EndpointSpec.TimeFormat.EPOCH_S -> p.longOrNull?.times(1000)
            EndpointSpec.TimeFormat.EPOCH_MS -> p.longOrNull
            EndpointSpec.TimeFormat.ISO_INSTANT -> runCatching {
                val s = p.content.trim().replace(' ', 'T')
                val withTime = if (s.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) s + "T00:00:00Z" else s
                val normalized = if (!withTime.endsWith("Z") && !withTime.contains('+') && !withTime.substringAfterLast('T', "").contains('-')) withTime + "Z" else withTime
                Instant.parse(normalized).toEpochMilli()
            }.getOrNull()
        }
    }
}
