package app.pulse4.analytics

import app.pulse4.domain.TrackPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val R = 6_371_008.8

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(a)))
    }
}

/** Explicitní definice: pauza = mezera mezi vzorky delší než [maxGapS] nebo rychlost pod [movingSpeedMps]. */
data class ActivityDefinitions(
    val movingSpeedMps: Double = 0.5,
    val maxGapS: Double = 15.0,
    val ascentHysteresisM: Double = 3.0,
)

data class ActivitySummary(
    val totalTimeS: Double,
    val movingTimeS: Double,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    /** Průměrné tempo v s/km z pohybového času; `null`, pokud není dost dat. */
    val avgPaceSPerKm: Double?,
)

object ActivityMath {
    fun summarize(points: List<TrackPoint>, def: ActivityDefinitions = ActivityDefinitions()): ActivitySummary {
        if (points.size < 2) return ActivitySummary(0.0, 0.0, 0.0, 0.0, 0.0, null)
        val total = (points.last().tMs - points.first().tMs) / 1000.0
        var moving = 0.0
        var movingWithDistance = 0.0
        var dist = 0.0
        var prev = points.first()
        for (i in 1 until points.size) {
            val p = points[i]
            val dt = (p.tMs - prev.tMs) / 1000.0
            if (dt <= 0.0) { prev = p; continue }
            val d = segmentDistance(prev, p)
            // Mezera nad limitem je pauza (zastavení záznamu), nepočítá se ani do pohybu, ani do vzdálenosti.
            if (dt <= def.maxGapS) {
                val speed = p.speedMps ?: (d?.let { it / dt })
                if (speed != null && speed >= def.movingSpeedMps) {
                    moving += dt
                    if (d != null) { dist += d; movingWithDistance += dt }
                }
            }
            prev = p
        }
        val (asc, desc) = elevationChange(points.mapNotNull { it.elevationM }, def.ascentHysteresisM)
        // Tempo jen z úseků, které mají známou vzdálenost; pohyb bez polohy ho nezkresluje.
        val pace = if (movingWithDistance > 0 && dist > 0) movingWithDistance / (dist / 1000.0) else null
        return ActivitySummary(total, moving, dist, asc, desc, pace)
    }

    private fun segmentDistance(a: TrackPoint, b: TrackPoint): Double? {
        val da = a.distanceM
        val db = b.distanceM
        if (da != null && db != null) return max(0.0, db - da)
        val la = a.lat; val oa = a.lon; val lb = b.lat; val ob = b.lon
        if (la != null && oa != null && lb != null && ob != null) return Geo.haversineM(la, oa, lb, ob)
        return null // chybějící poloha není vzdálenost 0
    }

    /** Převýšení s hysterezí, aby šum výškoměru nenabíjel stoupání. */
    fun elevationChange(ele: List<Double>, hysteresisM: Double): Pair<Double, Double> {
        if (ele.isEmpty()) return 0.0 to 0.0
        var ref = ele.first()
        var up = 0.0
        var down = 0.0
        for (e in ele) {
            val d = e - ref
            if (d >= hysteresisM) { up += d; ref = e }
            else if (d <= -hysteresisM) { down += -d; ref = e }
        }
        return up to down
    }
}

object Downsample {
    /**
     * Zmenšení řady pro graf: z každého koše zůstane minimum i maximum (v časovém pořadí),
     * takže špičky nezmizí. Hodnoty `null` (chybějící údaj) se přeskakují.
     */
    fun minMax(series: List<Pair<Long, Double?>>, buckets: Int): List<Pair<Long, Double>> {
        require(buckets > 0)
        val valid = series.mapNotNull { (t, v) -> v?.let { t to it } }
        if (valid.size <= buckets * 2) return valid
        val per = valid.size.toDouble() / buckets
        val out = ArrayList<Pair<Long, Double>>(buckets * 2)
        for (b in 0 until buckets) {
            val from = (b * per).toInt()
            val to = if (b == buckets - 1) valid.size else ((b + 1) * per).toInt()
            if (from >= to) continue
            val slice = valid.subList(from, to)
            val lo = slice.minBy { it.second }
            val hi = slice.maxBy { it.second }
            if (lo === hi) out += lo else out += listOf(lo, hi).sortedBy { it.first }
        }
        return out
    }
}

object Zones {
    /** Horní hranice zón v rostoucím pořadí; zóna i = (hranice[i-1], hranice[i]], poslední je otevřená. */
    data class ZoneSet(val upperBounds: List<Double>) {
        fun zoneOf(v: Double): Int {
            val i = upperBounds.indexOfFirst { v <= it }
            return if (i < 0) upperBounds.size else i
        }
        val count get() = upperBounds.size + 1
    }

    /**
     * Čas v zónách podle zón platných v době aktivity. Chybějící vzorky ani pauzy se nepočítají jako nuly:
     * čas mezi dvěma vzorky se přičte jen tehdy, když oba existují a mezera není větší než [maxGapS].
     */
    fun timeInZones(samples: List<Pair<Long, Double?>>, zones: ZoneSet, maxGapS: Double = 15.0): DoubleArray {
        val out = DoubleArray(zones.count)
        for (i in 1 until samples.size) {
            val (t0, v0) = samples[i - 1]
            val (t1, v1) = samples[i]
            if (v0 == null || v1 == null) continue
            val dt = (t1 - t0) / 1000.0
            if (dt <= 0 || dt > maxGapS) continue
            out[zones.zoneOf(v0)] += dt
        }
        return out
    }
}

object BestEfforts {
    data class Segment(val startMs: Long, val endMs: Long, val average: Double)

    /**
     * Nejlepší souvislý úsek dané délky podle průměru. Okno nesmí obsahovat chybějící hodnotu
     * ani mezeru delší než [maxGapS]; průměr je vážený časem.
     */
    fun bestWindow(samples: List<Pair<Long, Double?>>, windowS: Long, maxGapS: Double = 15.0): Segment? {
        val windowMs = windowS * 1000
        var best: Segment? = null
        var i = 0
        while (i < samples.size) {
            val (t0, v0) = samples[i]
            if (v0 == null) { i++; continue }
            var sum = 0.0
            var covered = 0L
            var j = i
            var ok = true
            while (j + 1 < samples.size && covered < windowMs) {
                val (ta, va) = samples[j]
                val (tb, vb) = samples[j + 1]
                if (va == null || vb == null || (tb - ta) / 1000.0 > maxGapS || tb <= ta) { ok = false; break }
                val use = min(tb - ta, windowMs - covered)
                sum += va * use
                covered += use
                j++
            }
            if (ok && covered >= windowMs) {
                val avg = sum / covered
                if (best == null || avg > best.average) best = Segment(t0, t0 + windowMs, avg)
            }
            i++
        }
        return best
    }
}

object Baseline {
    data class UsualRange(val low: Double, val median: Double, val high: Double, val n: Int)

    /** Vlastní obvyklé rozmezí (P25–P75). Bez dostatku historie vrací `null`, nevymýšlí si hodnotu. */
    fun usualRange(values: List<Double>, minSamples: Int = 7): UsualRange? {
        val v = values.filter { it.isFinite() }.sorted()
        if (v.size < minSamples) return null
        return UsualRange(percentile(v, 0.25), percentile(v, 0.5), percentile(v, 0.75), v.size)
    }

    fun percentile(sorted: List<Double>, p: Double): Double {
        require(sorted.isNotEmpty())
        val pos = p * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = min(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}
