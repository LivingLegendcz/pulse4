package app.pulse4.analytics

import app.pulse4.domain.Sport
import app.pulse4.domain.TrackPoint
import kotlin.math.max
import kotlin.math.pow

data class BestEffort(
    val label: String,
    val distanceM: Double,
    val timeS: Double,
    val paceSPerKm: Double,
    val elevationChangeM: Double?,
    val startKm: Double,
)

object RunningDynamicsModel {

    /**
     * Zachovává pouze reálné naměřené hodnoty z FIT/hodinek (D1).
     * Nevymýšlí vyvážení L/P (sin), dobu kontaktu, oscilaci, staminu ani cyklistickou dynamiku.
     */
    fun enrichTrack(
        track: List<TrackPoint>,
        @Suppress("UNUSED_PARAMETER") sport: Sport,
        @Suppress("UNUSED_PARAMETER") avgCadence: Double? = null,
        @Suppress("UNUSED_PARAMETER") avgStrideLengthCm: Double? = null,
        @Suppress("UNUSED_PARAMETER") avgGroundContactTimeMs: Double? = null,
        @Suppress("UNUSED_PARAMETER") avgVerticalOscillationCm: Double? = null,
        @Suppress("UNUSED_PARAMETER") avgVerticalRatioPct: Double? = null,
        @Suppress("UNUSED_PARAMETER") aerobicTE: Double? = null,
        @Suppress("UNUSED_PARAMETER") anaerobicTE: Double? = null,
        @Suppress("UNUSED_PARAMETER") maxHr: Int? = null,
    ): List<TrackPoint> = track

    /**
     * Odhad tréninkového úsilí z času v tepových zónách Z1–Z5 (HLT-5).
     * Bez dat o tepových zónách vrací `null` (žádný vymyšlený odhad z času).
     */
    fun computeStravaRelativeEffort(hrZonesTimesS: List<Double>, @Suppress("UNUSED_PARAMETER") totalTimeS: Double): Int? {
        if (hrZonesTimesS.size < 5 || hrZonesTimesS.all { it <= 0.0 }) {
            return null
        }
        val weights = listOf(0.5, 1.0, 2.2, 4.8, 8.5)
        var sum = 0.0
        for (i in 0 until 5) {
            val minInZone = (hrZonesTimesS.getOrElse(i) { 0.0 }) / 60.0
            sum += minInZone * weights[i]
        }
        return sum.toInt().coerceAtLeast(1)
    }

    /**
     * Slovní popis a doporučení k odhadu tréninkového úsilí.
     */
    fun stravaRelativeEffortDescription(score: Int): Pair<String, String> = when {
        score <= 30 -> "Mírné úsilí (odhad)" to "Lehká zátěž pro tělo, skvělé pro aktivní regeneraci."
        score <= 75 -> "Střední úsilí (odhad)" to "Vytrvalostní trénink udržující kardiovaskulární kapacitu."
        score <= 150 -> "Těžké úsilí (odhad)" to "Náročný trénink posouvající laktátový práh."
        else -> "Velmi vysoké úsilí (odhad)" to "Maximální nebo závodní nasazení vyžadující delší regeneraci."
    }

    /**
     * Minettiho rovnice energetického výdeje pro GAP (Grade Adjusted Pace).
     * Srovnává tempo běhu do/z kopce na ekvivalentní rychlost po rovině.
     */
    fun computeGapSpeedMps(speedMps: Double, gradeFraction: Double): Double {
        if (speedMps <= 0.1) return speedMps
        val g = gradeFraction.coerceIn(-0.35, 0.35)
        val cost = 155.4 * g.pow(5) - 30.4 * g.pow(4) - 32.8 * g.pow(3) + 43.7 * g.pow(2) + 19.9 * g + 3.6
        val flatCost = 3.6
        val factor = (cost / flatCost).coerceIn(0.65, 2.8)
        return speedMps * factor
    }

    /**
     * Vypočítá průměrné GAP tempo (s/km) se skutečným sklonem z výškového profilu (D2).
     */
    fun computeAverageGapPaceSPerKm(points: List<TrackPoint>): Double? {
        if (points.size < 2) return null
        val gapSpeeds = mutableListOf<Double>()
        var prevDistPt: TrackPoint? = null
        var prevDistM = 0.0
        for (p in points) {
            val spd = p.speedMps ?: continue
            val dM = p.distanceM ?: run {
                val prev = prevDistPt
                if (prev != null && prev.lat != null && prev.lon != null && p.lat != null && p.lon != null) {
                    prevDistM + Geo.haversineM(prev.lat!!, prev.lon!!, p.lat!!, p.lon!!)
                } else null
            }
            val prev = prevDistPt
            val grade = if (prev != null && dM != null && p.elevationM != null && prev.elevationM != null) {
                val dd = dM - prevDistM
                if (dd >= 5.0) ((p.elevationM!! - prev.elevationM!!) / dd).coerceIn(-0.35, 0.35) else 0.0
            } else 0.0
            if (spd > 0.2) {
                gapSpeeds += computeGapSpeedMps(spd, grade)
            }
            if (dM != null) {
                prevDistM = dM
                prevDistPt = p
            } else if (p.hasPosition) {
                prevDistPt = p
            }
        }
        val avgGapSpd = gapSpeeds.takeIf { it.isNotEmpty() }?.average() ?: return null
        return if (avgGapSpd > 0.2) 1000.0 / avgGapSpd else null
    }

    /**
     * Vyhledání nejlepších odhadovaných úseků (400 m, 1 km, 1 míle, 5 km, 10 km).
     * D4: Chybějící nadmořská výška zůstává null (nikdy 0.0); vzdálenost se nemíchá mezi hodinkami a haversine.
     */
    fun findBestEfforts(points: List<TrackPoint>): List<BestEffort> {
        if (points.size < 2) return emptyList()
        val targets = listOf(
            "400 m" to 400.0,
            "1 km" to 1000.0,
            "1 míle" to 1609.34,
            "5 km" to 5000.0,
            "10 km" to 10000.0,
        )

        val useWatchDist = points.count { it.distanceM != null } >= points.size / 2
        val cumDist = DoubleArray(points.size)
        val cumTime = DoubleArray(points.size)
        val ele = arrayOfNulls<Double>(points.size)

        var dAcc = 0.0
        val t0 = points.first().tMs
        var prevGps: TrackPoint? = points.firstOrNull { it.hasPosition }

        points.forEachIndexed { i, p ->
            if (useWatchDist) {
                val d = p.distanceM
                if (d != null) dAcc = max(dAcc, d)
            } else if (i > 0 && p.lat != null && p.lon != null) {
                val pg = prevGps
                if (pg != null && pg.lat != null && pg.lon != null) {
                    dAcc += Geo.haversineM(pg.lat!!, pg.lon!!, p.lat!!, p.lon!!)
                }
                prevGps = p
            }
            cumDist[i] = dAcc
            cumTime[i] = (p.tMs - t0) / 1000.0
            ele[i] = p.elevationM
        }

        val totalDist = cumDist.last()
        val results = mutableListOf<BestEffort>()

        for ((label, targetM) in targets) {
            if (totalDist < targetM * 0.98) continue

            var bestTimeS = Double.MAX_VALUE
            var bestStartKm = 0.0
            var bestEleChange: Double? = null

            var right = 0
            for (left in 0 until points.size) {
                while (right < points.size && (cumDist[right] - cumDist[left]) < targetM) {
                    right++
                }
                if (right >= points.size) break

                val segDist = cumDist[right] - cumDist[left]
                if (segDist >= targetM) {
                    val segTime = cumTime[right] - cumTime[left]
                    val normTime = segTime * (targetM / segDist)
                    if (normTime < bestTimeS && normTime > 1.0) {
                        bestTimeS = normTime
                        bestStartKm = cumDist[left] / 1000.0
                        val eL = ele[left]
                        val eR = ele[right]
                        bestEleChange = if (eL != null && eR != null) eR - eL else null
                    }
                }
            }

            if (bestTimeS < Double.MAX_VALUE) {
                val pace = (bestTimeS / (targetM / 1000.0))
                results += BestEffort(
                    label = label,
                    distanceM = targetM,
                    timeS = bestTimeS,
                    paceSPerKm = pace,
                    elevationChangeM = bestEleChange,
                    startKm = bestStartKm
                )
            }
        }

        return results
    }
}
