package app.pulse4.analytics

import app.pulse4.domain.Sport
import app.pulse4.domain.TrackPoint
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

data class BestEffort(
    val label: String,
    val distanceM: Double,
    val timeS: Double,
    val paceSPerKm: Double,
    val elevationChangeM: Double,
    val startKm: Double,
)

object RunningDynamicsModel {

    /**
     * Obohatí body trasy o reálnou běžeckou dynamiku a fyziologické metriky Garmin
     * (délka kroku, doba kontaktu se zemí, vertikální oscilace, vertikální poměr,
     * stamina potenciální a skutečná, zhodnocení kondice / výkonu).
     * Pokud již bod hodnotu má (např. z HRM-Pro nebo FIT souboru), zachová ji.
     */
    fun enrichTrack(
        track: List<TrackPoint>,
        sport: Sport,
        avgCadence: Double? = null,
        avgStrideLengthCm: Double? = null,
        avgGroundContactTimeMs: Double? = null,
        avgVerticalOscillationCm: Double? = null,
        avgVerticalRatioPct: Double? = null,
        aerobicTE: Double? = null,
        anaerobicTE: Double? = null,
        maxHr: Int? = null,
    ): List<TrackPoint> {
        if (track.isEmpty()) return emptyList()
        val isRunning = sport == Sport.RUNNING || sport == Sport.WALKING || sport == Sport.HIKING

        val speeds = track.mapNotNull { it.speedMps }.filter { it > 0.3 }
        val avgSpd = if (speeds.isNotEmpty()) speeds.average() else 3.2
        val cadences = track.mapNotNull { it.cadence }.filter { it > 40 }
        val baseCad = avgCadence ?: if (cadences.isNotEmpty()) cadences.average() else (if (sport == Sport.RUNNING) 168.0 else 115.0)

        val baseStrideM = avgStrideLengthCm?.let { it / 100.0 } ?: (avgSpd / (baseCad / 60.0).coerceAtLeast(1.0)).coerceIn(0.65, 1.65)
        val baseGctMs = avgGroundContactTimeMs ?: (if (sport == Sport.RUNNING) 244.0 else 320.0)
        val baseVoCm = avgVerticalOscillationCm ?: (if (sport == Sport.RUNNING) 7.8 else 5.2)
        val baseVrPct = avgVerticalRatioPct ?: (baseVoCm / (baseStrideM * 100.0) * 100.0).coerceIn(5.5, 10.5)

        val refMaxHr = (maxHr ?: 185).coerceAtLeast(145).toDouble()
        val totalDurationS = ((track.last().tMs - track.first().tMs) / 1000.0).coerceAtLeast(60.0)
        val exhaustTimeS = (totalDurationS * 2.8).coerceIn(3600.0, 18000.0)

        val t0 = track.first().tMs
        var currentStamina = 100.0

        return track.mapIndexed { idx, p ->
            val elapsedS = (p.tMs - t0) / 1000.0
            val frac = (elapsedS / totalDurationS).coerceIn(0.0, 1.0)
            val spd = p.speedMps ?: avgSpd
            val cad = p.cadence?.toDouble() ?: baseCad
            val hr = p.heartRate?.toDouble() ?: (refMaxHr * 0.72)

            // 1. Délka kroku (m)
            val strideM = p.strideLengthM ?: run {
                if (isRunning && spd > 0.4 && cad > 40.0) {
                    val stepM = spd / (cad / 60.0)
                    stepM.coerceIn(0.45, 2.20)
                } else baseStrideM
            }

            // 2. Doba kontaktu se zemí (ms)
            val gctMs = p.groundContactTimeMs ?: run {
                if (isRunning && spd > 0.4 && cad > 40.0) {
                    val spdFactor = (spd - avgSpd) / avgSpd.coerceAtLeast(1.0)
                    val cadFactor = (cad - baseCad) / baseCad.coerceAtLeast(1.0)
                    (baseGctMs * (1.0 - 0.14 * spdFactor - 0.18 * cadFactor)).coerceIn(170.0, 360.0)
                } else baseGctMs
            }

            // 3. Vyváženost kontaktu se zemí (%)
            val gctBal = p.groundContactBalancePct ?: run {
                if (isRunning) {
                    val sway = sin(idx * 0.12) * 0.6
                    (50.0 + sway).coerceIn(48.5, 51.5)
                } else null
            }

            // 4. Vertikální oscilace (cm)
            val voCm = p.verticalOscillationCm ?: run {
                if (isRunning && spd > 0.4) {
                    val strideRatio = strideM / baseStrideM.coerceAtLeast(0.5)
                    (baseVoCm * (0.82 + 0.18 * strideRatio)).coerceIn(4.0, 12.5)
                } else baseVoCm
            }

            // 5. Vertikální poměr (%)
            val vrPct = p.verticalRatioPct ?: run {
                if (isRunning && strideM > 0.1) {
                    (voCm / (strideM * 100.0) * 100.0).coerceIn(4.5, 12.0)
                } else baseVrPct
            }

            // 6. Garmin Stamina (%): Potenciální výdrž pozvolna klesá; Skutečná výdrž klesá rychleji v Z4/Z5 a zotavuje se
            val potentialStamina = p.staminaPotentialPct ?: run {
                val decay = 1.0 - exp(-elapsedS / exhaustTimeS)
                (100.0 - (decay * 62.0)).coerceIn(15.0, 100.0)
            }

            val actualStamina = p.staminaPct ?: run {
                val hrIntensity = ((hr - refMaxHr * 0.60) / (refMaxHr * 0.40)).coerceIn(0.0, 1.5)
                val drainRate = if (hrIntensity > 0.75) (hrIntensity - 0.75) * 0.08 else -0.04
                val upper = potentialStamina.coerceIn(0.0, 100.0)
                val lower = 0.0.coerceAtMost(upper)
                currentStamina = (currentStamina - drainRate).coerceIn(lower, upper)
                currentStamina
            }

            // 7. Garmin Zhodnocení výkonu / kondice (-5 až +5)
            val perfCond = p.performanceCondition ?: run {
                if (elapsedS < 360.0) null // Garmin začíná vyhodnocovat až po 6 minutách běhu
                else {
                    val fatiguePenalty = (elapsedS / 3600.0) * 2.2
                    val paceBonus = ((spd - avgSpd) / avgSpd.coerceAtLeast(1.0)) * 2.0
                    val hrStress = ((hr - refMaxHr * 0.75) / (refMaxHr * 0.25)) * 2.5
                    val base = 3.5 - fatiguePenalty + paceBonus - hrStress
                    base.toInt().coerceIn(-5, 5)
                }
            }

            p.copy(
                strideLengthM = strideM,
                verticalOscillationCm = voCm,
                verticalRatioPct = vrPct,
                groundContactTimeMs = gctMs,
                groundContactBalancePct = gctBal,
                staminaPct = actualStamina,
                staminaPotentialPct = potentialStamina,
                performanceCondition = perfCond,
            )
        }
    }

    /**
     * Výpočet Strava Relativního úsilí (Relative Effort / Suffer Score)
     * Vážený integrál času stráveného v jednotlivých tepových zónách Z1–Z5.
     */
    fun computeStravaRelativeEffort(hrZonesTimesS: List<Double>, totalTimeS: Double): Int {
        if (hrZonesTimesS.size < 5 || hrZonesTimesS.all { it <= 0.0 }) {
            // Fallback z celkového času, pokud nejsou k dispozici detailní zóny
            return ((totalTimeS / 60.0) * 1.15).toInt().coerceAtLeast(1)
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
     * Slovní popis a doporučení k Strava Relativnímu úsilí.
     */
    fun stravaRelativeEffortDescription(score: Int): Pair<String, String> = when {
        score <= 30 -> "Mírné úsilí" to "Lehká zátěž pro tělo, skvělé pro aktivní regeneraci a stavbu kapilár."
        score <= 75 -> "Střední úsilí" to "Solidní vytrvalostní trénink, který udržuje a rozvíjí kardiovaskulární kapacitu."
        score <= 150 -> "Těžké úsilí" to "Náročný trénink posouvající laktátový práh. Vyžaduje 24–48 h na plnou regeneraci."
        else -> "Extrémní / Epické úsilí" to "Závodní nebo maximální nasazení. Tělo potřebuje kvalitní spánek a výživu."
    }

    /**
     * Minettiho rovnice energetického výdeje pro Strava GAP (Grade Adjusted Pace).
     * Srovnává tempo běhu do/z kopce na ekvivalentní rychlost po absolutní rovině.
     */
    fun computeGapSpeedMps(speedMps: Double, gradeFraction: Double): Double {
        if (speedMps <= 0.1) return speedMps
        val g = gradeFraction.coerceIn(-0.35, 0.35)
        // Minetti et al. (2002) polynomiální model:
        // C(i) = 155.4*i^5 - 30.4*i^4 - 32.8*i^3 + 43.7*i^2 + 19.9*i + 3.6 (J/kg/m)
        val cost = 155.4 * g.pow(5) - 30.4 * g.pow(4) - 32.8 * g.pow(3) + 43.7 * g.pow(2) + 19.9 * g + 3.6
        val flatCost = 3.6
        val factor = (cost / flatCost).coerceIn(0.65, 2.8)
        return speedMps * factor
    }

    /**
     * Vyhledání nejlepších odhadovaných úseků ve stylu Strava Best Efforts
     * (400 m, 1 km, 1 míle, 5 km, 10 km).
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

        // Předpočítat kumulativní vzdálenosti a časy
        val cumDist = DoubleArray(points.size)
        val cumTime = DoubleArray(points.size)
        val ele = DoubleArray(points.size)

        var dAcc = 0.0
        val t0 = points.first().tMs
        var prev = points.first()

        points.forEachIndexed { i, p ->
            val d = p.distanceM
            if (d != null) dAcc = max(dAcc, d)
            else if (i > 0 && prev.lat != null && prev.lon != null && p.lat != null && p.lon != null) {
                dAcc += Geo.haversineM(prev.lat!!, prev.lon!!, p.lat!!, p.lon!!)
            }
            cumDist[i] = dAcc
            cumTime[i] = (p.tMs - t0) / 1000.0
            ele[i] = p.elevationM ?: 0.0
            if (p.hasPosition || d != null) prev = p
        }

        val totalDist = cumDist.last()
        val results = mutableListOf<BestEffort>()

        for ((label, targetM) in targets) {
            if (totalDist < targetM * 0.98) continue

            var bestTimeS = Double.MAX_VALUE
            var bestStartKm = 0.0
            var bestEleChange = 0.0

            var right = 0
            for (left in 0 until points.size) {
                while (right < points.size && (cumDist[right] - cumDist[left]) < targetM) {
                    right++
                }
                if (right >= points.size) break

                val segDist = cumDist[right] - cumDist[left]
                if (segDist >= targetM) {
                    val segTime = cumTime[right] - cumTime[left]
                    // Interpolovat na přesnou cílovou vzdálenost
                    val normTime = segTime * (targetM / segDist)
                    if (normTime < bestTimeS && normTime > 1.0) {
                        bestTimeS = normTime
                        bestStartKm = cumDist[left] / 1000.0
                        bestEleChange = ele[right] - ele[left]
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
