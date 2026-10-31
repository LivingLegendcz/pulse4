package app.pulse4.ui

import app.pulse4.analytics.ActivityMath
import app.pulse4.analytics.Geo
import app.pulse4.domain.TrackPoint

enum class Channel { HEART_RATE, SPEED, ELEVATION, CADENCE, POWER }
enum class Axis { TIME, DISTANCE }

/** Graf převzorkovaný na pravidelnou osu X. [trackIndex] mapuje bod grafu zpět na bod trasy (propojení graf ↔ trasa). */
class Chart(val values: List<Double?>, val trackIndex: IntArray, val xStart: Double, val xEnd: Double) {
    val size get() = values.size

    /** Interval označený v grafu → odpovídající úsek bodů trasy. */
    fun trackRange(chartRange: IntRange): IntRange {
        val a = trackIndex[chartRange.first.coerceIn(0, size - 1)]
        val b = trackIndex[chartRange.last.coerceIn(0, size - 1)]
        return minOf(a, b)..maxOf(a, b)
    }
}

class SummaryView(val timeS: Double, val distanceM: Double?, val paceSPerKm: Double?, val ascentM: Double, val descentM: Double)

/**
 * Logika detailu aktivity: kanály, osa čas/vzdálenost, propojený výběr, souhrn označeného úseku, hodnoty vybraného bodu.
 * Chybějící hodnoty a pauzy zůstávají mezerami (`null`), nepočítají se jako nuly.
 */
class ActivityView(val track: List<TrackPoint>) {
    /** Kumulativní vzdálenost bodu; bez udané vzdálenosti se skládá z GPS, bod bez polohy drží poslední hodnotu. */
    val distance: DoubleArray = DoubleArray(track.size).also { out ->
        var acc = 0.0
        var prev: TrackPoint? = null
        track.forEachIndexed { i, p ->
            val d = p.distanceM
            if (d != null) acc = maxOf(acc, d)
            else {
                val pl = prev?.lat; val po = prev?.lon; val la = p.lat; val lo = p.lon
                if (pl != null && po != null && la != null && lo != null) acc += Geo.haversineM(pl, po, la, lo)
            }
            out[i] = acc
            if (p.hasPosition || d != null) prev = p
        }
    }

    private fun value(p: TrackPoint, c: Channel): Double? = when (c) {
        Channel.HEART_RATE -> p.heartRate?.toDouble()
        Channel.SPEED -> p.speedMps
        Channel.ELEVATION -> p.elevationM
        Channel.CADENCE -> p.cadence?.toDouble()
        Channel.POWER -> p.powerW?.toDouble()
    }

    fun hasChannel(c: Channel) = track.any { value(it, c) != null }

    fun chart(channel: Channel, axis: Axis, buckets: Int = 240): Chart {
        require(buckets >= 2)
        if (track.size < 2) return Chart(emptyList(), IntArray(0), 0.0, 0.0)
        val t0 = track.first().tMs
        val xs = DoubleArray(track.size) { if (axis == Axis.TIME) (track[it].tMs - t0) / 1000.0 else distance[it] }
        val xMin = xs.first(); val xMax = xs.last().coerceAtLeast(xMin + 1e-9)
        val sum = DoubleArray(buckets); val cnt = IntArray(buckets); val first = IntArray(buckets) { -1 }
        for (i in track.indices) {
            val b = (((xs[i] - xMin) / (xMax - xMin)) * (buckets - 1)).toInt().coerceIn(0, buckets - 1)
            if (first[b] < 0) first[b] = i
            value(track[i], channel)?.let { sum[b] += it; cnt[b]++ }
        }
        // Prázdný koš (pauza, mezera) = null; index trasy se doplní z nejbližšího předchozího bodu.
        var last = 0
        val idx = IntArray(buckets) { b -> if (first[b] >= 0) { last = first[b] }; last }
        return Chart(List(buckets) { b -> if (cnt[b] > 0) sum[b] / cnt[b] else null }, idx, xMin, xMax)
    }

    /** Body trasy zarovnané s trasou; chybějící GPS je `null` (nikdy 0°/0°). */
    fun route(): List<Pair<Double, Double>?> = track.map { p -> p.lat?.let { la -> p.lon?.let { lo -> la to lo } } }

    /** Pro kurzor na bodu bez GPS najde nejbližší bod s polohou (jinak `null`). */
    fun nearestWithPosition(i: Int): Int? {
        if (track.isEmpty()) return null
        val c = i.coerceIn(0, track.size - 1)
        for (d in 0 until track.size) {
            if (track.getOrNull(c - d)?.hasPosition == true) return c - d
            if (track.getOrNull(c + d)?.hasPosition == true) return c + d
        }
        return null
    }

    fun summary(range: IntRange?, movingOnly: Boolean): SummaryView {
        val slice = range?.let { track.subList(it.first.coerceIn(0, track.size), (it.last + 1).coerceIn(0, track.size)) } ?: track
        val s = ActivityMath.summarize(slice)
        return SummaryView(if (movingOnly) s.movingTimeS else s.totalTimeS, s.distanceM.takeIf { it > 0 }, s.avgPaceSPerKm, s.ascentM, s.descentM)
    }

    /** Hodnoty vybraného bodu; chybějící údaj je pomlčka, nikdy „0“. */
    fun point(i: Int, f: Fmt = Fmt.default): List<Pair<String, String>> {
        val p = track.getOrNull(i) ?: return emptyList()
        return listOf(
            "Tep" to (p.heartRate?.toString() ?: "—"),
            "Rychlost" to (p.speedMps?.let { f.number(it, 1) + " m/s" } ?: "—"),
            "Výška" to (p.elevationM?.let { f.number(it) + " m" } ?: "—"),
            "Kadence" to (p.cadence?.toString() ?: "—"),
            "Výkon" to (p.powerW?.let { "$it W" } ?: "—"),
            "Poloha" to if (p.hasPosition) "%.5f, %.5f".format(java.util.Locale.ROOT, p.lat, p.lon) else "bez GPS",
        )
    }
}
