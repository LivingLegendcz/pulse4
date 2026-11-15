package app.pulse4.sharing

import app.pulse4.analytics.Downsample
import app.pulse4.analytics.Geo
import app.pulse4.domain.Activity
import app.pulse4.domain.TimeRules
import app.pulse4.domain.TrackPoint
import kotlinx.serialization.Serializable

/** Zóna soukromí (typicky domov): žádná souřadnice uvnitř poloměru neopustí telefon. */
@Serializable
data class PrivacyZone(val lat: Double, val lon: Double, val radiusM: Double) {
    fun contains(lat: Double, lon: Double) = Geo.haversineM(this.lat, this.lon, lat, lon) <= radiusM
}

data class LapIn(val startTMs: Long, val endTMs: Long, val distanceM: Double?, val startLat: Double?, val startLon: Double?)

@Serializable
data class LapOut(val startTMs: Long, val endTMs: Long, val distanceM: Double?, val startLat: Double?, val startLon: Double?)

@Serializable
data class RoutePoint(val lat: Double, val lon: Double)

@Serializable
data class ChartSeries(val name: String, val points: List<Pair<Long, Double>>)

@Serializable
data class AttachmentRef(val name: String, val sha256: String, val length: Long)

/** Nový, sanitizovaný objekt aktivity; nikdy kopie původní aktivity. */
@Serializable
data class ActivityProjection(
    val activityId: String,
    val sport: String,
    val localDate: String,
    /** Přesný čas jen s `ACT_EXACT_TIME`, jinak `null`. */
    val startUtcMs: Long? = null,
    val totalTimeS: Double? = null,
    val movingTimeS: Double? = null,
    val distanceM: Double? = null,
    val ascentM: Double? = null,
    val avgHeartRate: Int? = null,
    val charts: List<ChartSeries>? = null,
    val route: List<RoutePoint>? = null,
    val laps: List<LapOut>? = null,
    val attachments: List<AttachmentRef>? = null,
    val aerobicTrainingEffect: Double? = null,
    val anaerobicTrainingEffect: Double? = null,
    val trainingEffectLabel: String? = null,
    /** Důvod, proč se něco z požadovaného neodeslalo (zobrazí se v náhledu). */
    val omitted: List<String> = emptyList(),
)

object ActivityProjector {
    fun project(
        grant: ShareGrant,
        activity: Activity,
        track: List<TrackPoint>,
        laps: List<LapIn>,
        zones: List<PrivacyZone>,
        originalFit: AttachmentRef? = null,
        chartBuckets: Int = 80,
    ): ActivityProjection? {
        if (!grant.active || !grant.allowsProvider(activity.provider)) return null
        val date = TimeRules.localDate(activity.startUtcMs, activity.zoneId)
        if (!grant.scope.covers(date)) return null
        val omitted = mutableListOf<String>()

        val wantsSummary = grant.allows(Field.ACT_SUMMARY)
        val wantsCharts = grant.allows(Field.ACT_CHARTS)
        val wantsRoute = grant.allows(Field.ACT_ROUTE) || grant.allows(Field.ACT_FULL_RECORD)
        val wantsFull = grant.allows(Field.ACT_FULL_RECORD)
        if (!(wantsSummary || wantsCharts || wantsRoute)) return null

        var attachments: List<AttachmentRef>? = null
        if (wantsFull) {
            // Původní FIT nelze sanitizovat; při zónách soukromí se nepřikládá vůbec.
            if (zones.isNotEmpty()) omitted += "Původní FIT soubor se nepřikládá, protože je nastavena zóna soukromí."
            else if (originalFit != null) attachments = listOf(originalFit)
        }

        val route = if (wantsRoute) sanitizedRoute(track, zones) else null
        val outLaps = if (wantsRoute) laps.map { sanitizeLap(it, zones) } else null
        val charts = if (wantsCharts) charts(track, chartBuckets) else null

        return ActivityProjection(
            activityId = activity.id,
            sport = activity.sport.name,
            localDate = date.toString(),
            startUtcMs = if (grant.allows(Field.ACT_EXACT_TIME)) activity.startUtcMs else null,
            totalTimeS = activity.totalTimeS.takeIf { wantsSummary },
            movingTimeS = activity.movingTimeS.takeIf { wantsSummary },
            distanceM = activity.distanceM.takeIf { wantsSummary },
            ascentM = activity.ascentM.takeIf { wantsSummary },
            avgHeartRate = activity.avgHeartRate.takeIf { wantsSummary },
            charts = charts,
            route = route,
            laps = outLaps,
            attachments = attachments,
            aerobicTrainingEffect = activity.aerobicTrainingEffect.takeIf { wantsSummary },
            anaerobicTrainingEffect = activity.anaerobicTrainingEffect.takeIf { wantsSummary },
            trainingEffectLabel = activity.trainingEffectLabel.takeIf { wantsSummary },
            omitted = omitted,
        )
    }

    fun sanitizedRoute(track: List<TrackPoint>, zones: List<PrivacyZone>, maxPoints: Int = 120): List<RoutePoint> {
        val filtered = track.mapNotNull { p ->
            val la = p.lat; val lo = p.lon
            if (la == null || lo == null) null
            else if (zones.any { it.contains(la, lo) }) null
            else RoutePoint(la, lo)
        }
        if (filtered.size <= maxPoints) return filtered
        val step = filtered.size.toDouble() / maxPoints
        return (0 until maxPoints).map { i -> filtered[(i * step).toInt()] } + filtered.last()
    }

    private fun sanitizeLap(l: LapIn, zones: List<PrivacyZone>): LapOut {
        val inZone = l.startLat != null && l.startLon != null && zones.any { it.contains(l.startLat, l.startLon) }
        return LapOut(l.startTMs, l.endTMs, l.distanceM, if (inZone) null else l.startLat, if (inZone) null else l.startLon)
    }

    /** Grafy nenesou souřadnice; čas je relativní k začátku, takže neprozrazuje přesný okamžik. */
    private fun charts(track: List<TrackPoint>, buckets: Int): List<ChartSeries> {
        if (track.isEmpty()) return emptyList()
        val t0 = track.first().tMs
        fun series(name: String, sel: (TrackPoint) -> Double?): ChartSeries? {
            val pts = Downsample.minMax(track.map { (it.tMs - t0) to sel(it) }, buckets)
            return if (pts.isEmpty()) null else ChartSeries(name, pts)
        }
        return listOfNotNull(
            series("heartRate") { it.heartRate?.toDouble() },
            series("speed") { it.speedMps },
            series("elevation") { it.elevationM },
            series("cadence") { it.cadence?.toDouble() },
            series("power") { it.powerW?.toDouble() },
            series("strideLength") { it.strideLengthM },
            series("verticalOscillation") { it.verticalOscillationCm },
            series("groundContactTime") { it.groundContactTimeMs },
            series("verticalRatio") { it.verticalRatioPct },
            series("stamina") { it.staminaPct },
            series("performanceCondition") { it.performanceCondition?.toDouble() },
        )
    }
}
