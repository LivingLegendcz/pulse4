package app.pulse4.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import app.pulse4.analytics.Geo
import app.pulse4.android.data.MapProvider
import app.pulse4.domain.TrackPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

enum class MapMetricMode(val title: String, val shortLabel: String) {
    PACE("Tempo / Rychlost", "Tempo"),
    HEART_RATE("Tepové zóny", "Tep"),
    RUN_WALK("Chůze / Běh", "Chůze/Běh"),
    ELEVATION("Převýšení", "Výška"),
    UNIFORM("Jednotná", "Jednotná")
}

data class ActivityPause(
    val index: Int,
    val lat: Double,
    val lon: Double,
    val durationS: Long,
    val distanceM: Double,
    val startUtcMs: Long,
    val resumeLat: Double? = null,
    val resumeLon: Double? = null,
    val resumeUtcMs: Long? = null,
    val displacementM: Double = 0.0,
)

data class ActivityKmSplit(
    val km: Int,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val splitTimeS: Double,
    val splitPaceSPerKm: Double,
    val avgHr: Int?,
    val elevationM: Double?,
)

data class RunWalkBreakdown(
    val runSeconds: Long,
    val runMeters: Double,
    val runPct: Int,
    val walkSeconds: Long,
    val walkMeters: Double,
    val walkPct: Int,
    val standSeconds: Long,
    val standPct: Int,
)

object MapTrackAnalytics {
    /**
     * Detekce skutečných pauz aktivity (kdy uživatel zapauzoval stopky hodinek a poté je znovu spustil).
     *
     * @param track Seznam bodů trasy.
     * @param totalTimeS Celkový uplynulý čas aktivity (ze souhrnu aktivity, pokud je k dispozici).
     * @param movingTimeS Čistý čas pohybu/běhu stopek (pokud je k dispozici).
     */
    fun detectPauses(
        track: List<TrackPoint>,
        totalTimeS: Double? = null,
        movingTimeS: Double? = null,
    ): List<ActivityPause> {
        if (track.size < 2) return emptyList()

        // 1. Ověření podle oficiálních stopek Garminu:
        // Pokud máme celkový čas i čas pohybu a rozdíl je menší než 20 s,
        // aktivita nebyla uživatelem zapauzována (běžela kontinuálně od startu do konce).
        if (totalTimeS != null && movingTimeS != null) {
            val pauseDiff = totalTimeS - movingTimeS
            if (pauseDiff < 20.0) {
                return emptyList()
            }
        }

        // 2. Detekce skutečných mezer v záznamu (časovač byl zastaven):
        // Garmin v aktivním stavu zapisuje body po 1 s (1s recording) nebo max 4–7 s (smart recording).
        // Časová díra >= 25 s bez zaznamenaných bodů indikuje zapauzování stopek (manuální nebo autopause).
        val rawPauses = mutableListOf<ActivityPause>()
        var cumDist = 0.0

        for (i in 1 until track.size) {
            val prev = track[i - 1]
            val curr = track[i]

            if (curr.distanceM != null) {
                cumDist = curr.distanceM ?: cumDist
            } else if (prev.hasPosition && curr.hasPosition) {
                cumDist += Geo.haversineM(prev.lat!!, prev.lon!!, curr.lat!!, curr.lon!!)
            }

            val dt = (curr.tMs - prev.tMs) / 1000L
            if (dt >= 25L) {
                val dDist = if (prev.hasPosition && curr.hasPosition) {
                    Geo.haversineM(prev.lat!!, prev.lon!!, curr.lat!!, curr.lon!!)
                } else 0.0

                // Filtrování výpadku GPS při vysoké rychlosti (např. jízda tunelem při rychlosti > 5 m/s)
                val speedDuringGap = if (dt > 0) dDist / dt else 0.0
                if (speedDuringGap < 5.0 && (prev.hasPosition || curr.hasPosition)) {
                    val pLat = prev.lat ?: curr.lat!!
                    val pLon = prev.lon ?: curr.lon!!
                    rawPauses.add(
                        ActivityPause(
                            index = 0,
                            lat = pLat,
                            lon = pLon,
                            durationS = dt,
                            distanceM = cumDist,
                            startUtcMs = prev.tMs,
                            resumeLat = curr.lat,
                            resumeLon = curr.lon,
                            resumeUtcMs = curr.tMs,
                            displacementM = dDist
                        )
                    )
                }
            }
        }

        if (rawPauses.isEmpty()) return emptyList()

        // Sloučení vícenásobných přerušení bezprostředně za sebou (< 25 m a časově blízko sebe)
        val merged = mutableListOf<ActivityPause>()
        var cur = rawPauses.first()
        for (next in rawPauses.drop(1)) {
            val dist = Geo.haversineM(cur.lat, cur.lon, next.lat, next.lon)
            val timeGap = (next.startUtcMs - (cur.resumeUtcMs ?: (cur.startUtcMs + cur.durationS * 1000L))) / 1000L
            if (dist < 25.0 && timeGap < 30L) {
                cur = cur.copy(
                    durationS = cur.durationS + next.durationS,
                    resumeLat = next.resumeLat ?: cur.resumeLat,
                    resumeLon = next.resumeLon ?: cur.resumeLon,
                    resumeUtcMs = next.resumeUtcMs ?: cur.resumeUtcMs
                )
            } else {
                merged.add(cur)
                cur = next
            }
        }
        merged.add(cur)

        return merged.mapIndexed { idx, p -> p.copy(index = idx + 1) }
    }

    fun detectKmSplits(track: List<TrackPoint>, distanceArray: DoubleArray): List<ActivityKmSplit> {
        val splits = mutableListOf<ActivityKmSplit>()
        var targetKm = 1
        var lastSplitTimeMs = track.firstOrNull()?.tMs ?: 0L
        var lastSplitDistM = 0.0

        for (i in track.indices) {
            val p = track[i]
            val dist = distanceArray.getOrNull(i) ?: continue
            if (dist >= targetKm * 1000.0) {
                val stepDist = dist - lastSplitDistM
                val stepTimeS = (p.tMs - lastSplitTimeMs) / 1000.0
                val pace = if (stepDist > 100) (stepTimeS / (stepDist / 1000.0)) else 0.0
                splits.add(
                    ActivityKmSplit(
                        km = targetKm,
                        lat = p.lat ?: 0.0,
                        lon = p.lon ?: 0.0,
                        splitTimeS = stepTimeS,
                        splitPaceSPerKm = pace,
                        avgHr = p.heartRate,
                        elevationM = p.elevationM
                    )
                )
                lastSplitDistM = dist
                lastSplitTimeMs = p.tMs
                targetKm++
            }
        }
        return splits
    }

    fun computeRunWalkBreakdown(track: List<TrackPoint>): RunWalkBreakdown {
        var runS = 0L
        var runM = 0.0
        var walkS = 0L
        var walkM = 0.0
        var standS = 0L

        for (i in 1 until track.size) {
            val p = track[i]
            val prev = track[i - 1]
            val rawDt = (p.tMs - prev.tMs) / 1000L
            // Pokud mezera >= 25 s, odpovídá to zapauzovaným stopkám, nikoliv stání při aktivním záznamu
            if (rawDt >= 25L) {
                continue
            }
            val dt = rawDt.coerceIn(0L, 10L)
            val dM = if (p.hasPosition && prev.hasPosition) {
                Geo.haversineM(prev.lat!!, prev.lon!!, p.lat!!, p.lon!!)
            } else {
                val d1 = p.distanceM ?: 0.0
                val d0 = prev.distanceM ?: 0.0
                (d1 - d0).coerceAtLeast(0.0)
            }

            val spd = p.speedMps ?: (if (dt > 0) dM / dt else 0.0)
            val cad = p.cadence ?: 0

            if (spd < 0.5 || (p.cadence != null && p.cadence == 0 && spd < 0.8)) {
                standS += dt
            } else if (cad >= 145 || spd >= 2.2) {
                runS += dt
                runM += dM
            } else {
                walkS += dt
                walkM += dM
            }
        }

        val totalS = (runS + walkS + standS).coerceAtLeast(1L)
        val runPct = ((runS * 100) / totalS).toInt()
        val walkPct = ((walkS * 100) / totalS).toInt()
        val standPct = (100 - runPct - walkPct).coerceAtLeast(0)

        return RunWalkBreakdown(
            runSeconds = runS,
            runMeters = runM,
            runPct = runPct,
            walkSeconds = walkS,
            walkMeters = walkM,
            walkPct = walkPct,
            standSeconds = standS,
            standPct = standPct
        )
    }

    fun buildColoredSegments(
        track: List<TrackPoint>,
        mode: MapMetricMode,
        maxHr: Int?,
        defaultColor: String
    ): FeatureCollection {
        val pts = track.filter { it.hasPosition }
        if (pts.size < 2) return FeatureCollection.fromFeatures(emptyList())

        if (mode == MapMetricMode.UNIFORM) {
            val line = LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon!!, it.lat!!) })
            val feat = Feature.fromGeometry(line).also { it.addStringProperty("color", defaultColor) }
            return FeatureCollection.fromFeatures(listOf(feat))
        }

        // Statistiky pro adaptivní barevné škály
        val validSpeeds = pts.mapNotNull { it.speedMps }.filter { it > 0.4 }.sorted()
        val spdQ = if (validSpeeds.isNotEmpty()) {
            doubleArrayOf(
                validSpeeds[(validSpeeds.size * 0.20).toInt().coerceIn(0, validSpeeds.size - 1)],
                validSpeeds[(validSpeeds.size * 0.40).toInt().coerceIn(0, validSpeeds.size - 1)],
                validSpeeds[(validSpeeds.size * 0.60).toInt().coerceIn(0, validSpeeds.size - 1)],
                validSpeeds[(validSpeeds.size * 0.80).toInt().coerceIn(0, validSpeeds.size - 1)]
            )
        } else doubleArrayOf(1.8, 2.4, 3.0, 3.6)

        val targetMaxHr = maxHr ?: 190
        val elevations = pts.mapNotNull { it.elevationM }
        val minAlt = elevations.minOrNull() ?: 0.0
        val maxAlt = elevations.maxOrNull() ?: 100.0

        fun getColor(p: TrackPoint): String = when (mode) {
            MapMetricMode.PACE -> {
                val s = p.speedMps ?: 0.0
                when {
                    s <= 0.35 -> "#64748B"
                    s >= spdQ[3] -> "#10B981" // Nejrychlejší (Smaragdová)
                    s >= spdQ[2] -> "#84CC16" // Rychlé (Limetková)
                    s >= spdQ[1] -> "#EAB308" // Průměrné (Žlutá)
                    s >= spdQ[0] -> "#F97316" // Pozvolné (Oranžová)
                    else -> "#EF4444"         // Pomalé / stoupání (Červená)
                }
            }
            MapMetricMode.HEART_RATE -> {
                val hr = p.heartRate ?: return "#94A3B8"
                when {
                    hr >= (targetMaxHr * 0.90) -> "#EF4444" // Z5 Max (Červená)
                    hr >= (targetMaxHr * 0.80) -> "#F97316" // Z4 Práh (Oranžová)
                    hr >= (targetMaxHr * 0.70) -> "#EAB308" // Z3 Aerobní (Žlutá)
                    hr >= (targetMaxHr * 0.60) -> "#22C55E" // Z2 Vytrvalost (Zelená)
                    else -> "#38BDF8"                       // Z1 Regenerace (Světle modrá)
                }
            }
            MapMetricMode.RUN_WALK -> {
                val spd = p.speedMps ?: 0.0
                val cad = p.cadence ?: 0
                when {
                    spd < 0.5 || (p.cadence != null && cad == 0 && spd < 0.8) -> "#64748B" // Stání (Šedá)
                    cad >= 145 || spd >= 2.2 -> "#F97316"                                   // Běh (Oranžová)
                    else -> "#38BDF8"                                                       // Chůze (Azurová)
                }
            }
            MapMetricMode.ELEVATION -> {
                val alt = p.elevationM ?: minAlt
                val span = (maxAlt - minAlt).coerceAtLeast(1.0)
                val ratio = ((alt - minAlt) / span).coerceIn(0.0, 1.0)
                when {
                    ratio >= 0.75 -> "#EC4899" // Vrchol (Růžová)
                    ratio >= 0.50 -> "#8B5CF6" // Stoupání (Fialová)
                    ratio >= 0.25 -> "#3B82F6" // Střední výška (Modrá)
                    else -> "#06B6D4"          // Údolí (Azurová)
                }
            }
            MapMetricMode.UNIFORM -> defaultColor
        }

        val features = mutableListOf<Feature>()
        var curColor = getColor(pts[0])
        var curSegment = mutableListOf(Point.fromLngLat(pts[0].lon!!, pts[0].lat!!))

        for (i in 1 until pts.size) {
            val p = pts[i]
            val ptColor = getColor(p)
            curSegment.add(Point.fromLngLat(p.lon!!, p.lat!!))
            if (ptColor != curColor) {
                if (curSegment.size >= 2) {
                    val line = LineString.fromLngLats(curSegment)
                    val feat = Feature.fromGeometry(line).also { it.addStringProperty("color", curColor) }
                    features.add(feat)
                }
                curColor = ptColor
                curSegment = mutableListOf(Point.fromLngLat(p.lon!!, p.lat!!))
            }
        }
        if (curSegment.size >= 2) {
            val line = LineString.fromLngLats(curSegment)
            val feat = Feature.fromGeometry(line).also { it.addStringProperty("color", curColor) }
            features.add(feat)
        }

        return FeatureCollection.fromFeatures(features)
    }
}

// Generátory Bitmap odznaků pro MapLibre SymbolLayer
object MapBadgeGenerator {
    fun createKmBadge(km: Int): Bitmap {
        val size = 56
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = android.graphics.Color.parseColor("#0A0D14")
        paint.style = Paint.Style.FILL
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.color = android.graphics.Color.parseColor("#0284C7")
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 3.5f, paint)

        paint.color = android.graphics.Color.WHITE
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = if (km >= 100) 20f else if (km >= 10) 24f else 28f
        paint.textAlign = Paint.Align.CENTER
        val textY = (size / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(km.toString(), size / 2f, textY, paint)
        return bitmap
    }

    fun createPauseBadge(): Bitmap {
        val size = 56
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = android.graphics.Color.parseColor("#0A0D14")
        paint.style = Paint.Style.FILL
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.color = android.graphics.Color.parseColor("#F59E0B")
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 3.5f, paint)

        paint.color = android.graphics.Color.parseColor("#0A0D14")
        paint.style = Paint.Style.FILL
        val barW = 5.5f
        val barH = 19f
        val top = (size - barH) / 2f
        val bot = top + barH
        val mid = size / 2f
        canvas.drawRoundRect(mid - 7.5f, top, mid - 7.5f + barW, bot, 2.5f, 2.5f, paint)
        canvas.drawRoundRect(mid + 2f, top, mid + 2f + barW, bot, 2.5f, 2.5f, paint)
        return bitmap
    }

    fun createPinBadge(letter: String, bgColorHex: String): Bitmap {
        val size = 56
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = android.graphics.Color.parseColor("#0A0D14")
        paint.style = Paint.Style.FILL
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.color = android.graphics.Color.parseColor(bgColorHex)
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 3.5f, paint)

        paint.color = android.graphics.Color.WHITE
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 26f
        paint.textAlign = Paint.Align.CENTER
        val textY = (size / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(letter, size / 2f, textY, paint)
        return bitmap
    }
}

fun buildRasterStyleJson(tiles: List<String>, tileSize: Int = 256): String {
    val tilesJson = tiles.joinToString(",") { "\"$it\"" }
    return """
    {
      "version": 8,
      "sources": {
        "raster-tiles": {
          "type": "raster",
          "tiles": [$tilesJson],
          "tileSize": $tileSize,
          "minzoom": 0,
          "maxzoom": 19
        }
      },
      "layers": [
        {
          "id": "background-layer",
          "type": "raster",
          "source": "raster-tiles"
        }
      ]
    }
    """.trimIndent()
}

fun buildStyleBuilder(provider: MapProvider, customStyleUrl: String?): Style.Builder? {
    return when (provider) {
        MapProvider.CARTO_DARK -> Style.Builder().fromJson(
            buildRasterStyleJson(
                listOf("https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}"),
                tileSize = 256
            )
        )
        MapProvider.OSM_STANDARD -> Style.Builder().fromJson(
            buildRasterStyleJson(
                listOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
                tileSize = 256
            )
        )
        MapProvider.OPENTOPOMAP -> Style.Builder().fromJson(
            buildRasterStyleJson(
                listOf(
                    "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
                    "https://b.tile.opentopomap.org/{z}/{x}/{y}.png",
                    "https://c.tile.opentopomap.org/{z}/{x}/{y}.png"
                ),
                tileSize = 256
            )
        )
        MapProvider.CARTO_VOYAGER -> Style.Builder().fromJson(
            buildRasterStyleJson(
                listOf(
                    "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
                    "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png"
                ),
                tileSize = 256
            )
        )
        MapProvider.ESRI_SATELLITE -> Style.Builder().fromJson(
            buildRasterStyleJson(
                listOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"),
                tileSize = 256
            )
        )
        MapProvider.CUSTOM_URL -> {
            val url = customStyleUrl?.trim().orEmpty()
            if (url.isNotBlank()) Style.Builder().fromUri(url)
            else Style.Builder().fromJson(
                buildRasterStyleJson(
                    listOf("https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png")
                )
            )
        }
        MapProvider.OFFLINE_CANVAS -> null
    }
}

/**
 * Pokročilá interaktivní mapa trasy v Garmin stylu:
 * - Dynamické barevné režimy trasy: Tempo, Tepové zóny, Chůze/Běh, Převýšení, Jednotná
 * - Detekce a vizualizace zastávek (Pauzy) s ikonou ⏸
 * - Kilometrovníky podél trasy (odznáčky 1, 2, 3...)
 * - Start (zelený S) a Cíl (červený C)
 * - Interaktivní synchronizace kurzoru při tažení v grafu
 * - Plovoucí minilegenda aktivní metriky
 */
@Composable
fun MapLibreRoute(
    provider: MapProvider,
    customStyleUrl: String?,
    track: List<TrackPoint>,
    modifier: Modifier = Modifier,
    metricMode: MapMetricMode = MapMetricMode.PACE,
    showKmMarkers: Boolean = true,
    showPauses: Boolean = true,
    maxHeartRate: Int? = null,
    cursorCoord: Pair<Double, Double>? = null,
    highlightSegment: List<Pair<Double, Double>> = emptyList(),
    selectedCoordinate: Pair<Double, Double>? = null,
    pauses: List<ActivityPause> = emptyList(),
    splits: List<ActivityKmSplit> = emptyList(),
    breakdown: RunWalkBreakdown? = null,
    onMapReady: ((MapLibreMap) -> Unit)? = null
) {
    val styleBuilder = remember(provider, customStyleUrl) { buildStyleBuilder(provider, customStyleUrl) } ?: return
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapLibre.getInstance(ctx); MapView(ctx) }
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val defaultLineColor = if (provider == MapProvider.CARTO_DARK || provider == MapProvider.ESRI_SATELLITE) "#38BDF8" else "#E11D48"

    DisposableEffect(lifecycle, mapView) {
        mapView.onCreate(null)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }

    // Centrování na vybranou souřadnici (např. klik na pauzu nebo km split)
    LaunchedEffect(selectedCoordinate) {
        val coord = selectedCoordinate ?: return@LaunchedEffect
        mapRef.value?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(coord.first, coord.second), 16.0))
    }

    val validPoints = remember(track) { track.filter { it.hasPosition } }

    Box(modifier = modifier.fillMaxWidth().height(290.dp).clip(RoundedCornerShape(16.dp))) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.matchParentSize()
        ) { mv ->
            mv.getMapAsync { map ->
                mapRef.value = map
                onMapReady?.invoke(map)
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isLogoEnabled = false

                val style = map.style
                if (style == null || !style.isFullyLoaded) {
                    map.setStyle(styleBuilder) { loadedStyle ->
                        setupMapLayers(
                            map = map,
                            style = loadedStyle,
                            validPoints = validPoints,
                            metricMode = metricMode,
                            maxHr = maxHeartRate,
                            defaultColor = defaultLineColor,
                            pauses = pauses,
                            splits = splits,
                            showKmMarkers = showKmMarkers,
                            showPauses = showPauses,
                            cursorCoord = cursorCoord,
                            highlightSegment = highlightSegment
                        )
                    }
                } else {
                    updateMapData(
                        style = style,
                        validPoints = validPoints,
                        metricMode = metricMode,
                        maxHr = maxHeartRate,
                        defaultColor = defaultLineColor,
                        pauses = pauses,
                        splits = splits,
                        showKmMarkers = showKmMarkers,
                        showPauses = showPauses,
                        cursorCoord = cursorCoord,
                        highlightSegment = highlightSegment
                    )
                }
            }
        }

        // Plovoucí minilegenda aktivní metriky
        if (validPoints.size >= 2) {
            FloatingMapLegend(
                mode = metricMode,
                breakdown = breakdown,
                elevations = validPoints.mapNotNull { it.elevationM },
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
            )
        }
    }
}

/** Plovoucí minilegenda v rohu mapy podle vybraného režimu metriky. */
@Composable
fun FloatingMapLegend(
    mode: MapMetricMode,
    breakdown: RunWalkBreakdown?,
    elevations: List<Double>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF0F172A).copy(alpha = 0.88f), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        when (mode) {
            MapMetricMode.PACE -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(0xFF10B981), CircleShape))
                        Spacer(Modifier.width(3.dp))
                        Text("Svižné", fontSize = 11.sp, color = Color.White)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(0xFFEAB308), CircleShape))
                        Spacer(Modifier.width(3.dp))
                        Text("Průměr", fontSize = 11.sp, color = Color.White)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(0xFFEF4444), CircleShape))
                        Spacer(Modifier.width(3.dp))
                        Text("Volné", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
            MapMetricMode.HEART_RATE -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        "Z1" to Color(0xFF38BDF8),
                        "Z2" to Color(0xFF22C55E),
                        "Z3" to Color(0xFFEAB308),
                        "Z4" to Color(0xFFF97316),
                        "Z5" to Color(0xFFEF4444)
                    ).forEach { (label, col) ->
                        Box(
                            Modifier
                                .background(col.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                                .border(0.5.dp, col, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = col)
                        }
                    }
                }
            }
            MapMetricMode.RUN_WALK -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(0xFFF97316), CircleShape))
                        Spacer(Modifier.width(3.dp))
                        Text("Běh ${breakdown?.runPct ?: 0}%", fontSize = 11.sp, color = Color.White)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(0xFF38BDF8), CircleShape))
                        Spacer(Modifier.width(3.dp))
                        Text("Chůze ${breakdown?.walkPct ?: 0}%", fontSize = 11.sp, color = Color.White)
                    }
                    if ((breakdown?.standPct ?: 0) > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(Color(0xFF64748B), CircleShape))
                            Spacer(Modifier.width(3.dp))
                            Text("Stání ${breakdown?.standPct}%", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                    }
                }
            }
            MapMetricMode.ELEVATION -> {
                val minM = elevations.minOrNull()?.toInt() ?: 0
                val maxM = elevations.maxOrNull()?.toInt() ?: 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(Color(0xFF06B6D4), CircleShape))
                    Spacer(Modifier.width(3.dp))
                    Text("$minM m", fontSize = 11.sp, color = Color.White)
                    Text(" ➔ ", fontSize = 10.sp, color = Color(0xFF64748B))
                    Box(Modifier.size(8.dp).background(Color(0xFFEC4899), CircleShape))
                    Spacer(Modifier.width(3.dp))
                    Text("$maxM m (+${maxM - minM} m)", fontSize = 11.sp, color = Color.White)
                }
            }
            MapMetricMode.UNIFORM -> {
                Text("Jednotná trasa", fontSize = 11.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun setupMapLayers(
    map: MapLibreMap,
    style: Style,
    validPoints: List<TrackPoint>,
    metricMode: MapMetricMode,
    maxHr: Int?,
    defaultColor: String,
    pauses: List<ActivityPause>,
    splits: List<ActivityKmSplit>,
    showKmMarkers: Boolean,
    showPauses: Boolean,
    cursorCoord: Pair<Double, Double>?,
    highlightSegment: List<Pair<Double, Double>>
) {
    if (validPoints.size < 2) return

    // 1. Zaregistrovat ikony a odznáčky do stylu
    style.addImage("marker-start", MapBadgeGenerator.createPinBadge("S", "#10B981"))
    style.addImage("marker-end", MapBadgeGenerator.createPinBadge("C", "#EF4444"))
    style.addImage("marker-pause", MapBadgeGenerator.createPauseBadge())
    splits.forEach { sp ->
        style.addImage("split-${sp.km}", MapBadgeGenerator.createKmBadge(sp.km))
    }

    // 2. Zdroj pro křivku trasy (segmentovaný GeoJSON)
    val routeGeoJson = MapTrackAnalytics.buildColoredSegments(validPoints, metricMode, maxHr, defaultColor)
    style.addSource(GeoJsonSource("route", routeGeoJson))

    // Tmavé kontrastní lemování
    style.addLayer(
        LineLayer("route-casing", "route").withProperties(
            PropertyFactory.lineColor("#0A0D14"),
            PropertyFactory.lineWidth(7.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )

    // Hlavní datově zbarvená křivka
    style.addLayer(
        LineLayer("route-line", "route").withProperties(
            PropertyFactory.lineColor(Expression.coalesce(Expression.get("color"), Expression.literal(defaultColor))),
            PropertyFactory.lineWidth(4.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )

    // Zvýrazněný interval (při tažení v grafu)
    val segLine = if (highlightSegment.size >= 2) {
        LineString.fromLngLats(highlightSegment.map { Point.fromLngLat(it.second, it.first) })
    } else {
        LineString.fromLngLats(listOf(Point.fromLngLat(validPoints[0].lon!!, validPoints[0].lat!!), Point.fromLngLat(validPoints[0].lon!!, validPoints[0].lat!!)))
    }
    style.addSource(GeoJsonSource("route-seg", Feature.fromGeometry(segLine)))
    style.addLayer(
        LineLayer("route-seg-line", "route-seg").withProperties(
            PropertyFactory.lineColor("#F59E0B"),
            PropertyFactory.lineWidth(6.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.visibility(if (highlightSegment.size >= 2) Property.VISIBLE else Property.NONE)
        )
    )

    // Start a Cíl
    val startPt = validPoints.first()
    val endPt = validPoints.last()
    style.addSource(GeoJsonSource("start-pt", Feature.fromGeometry(Point.fromLngLat(startPt.lon!!, startPt.lat!!))))
    style.addLayer(
        SymbolLayer("start-layer", "start-pt").withProperties(
            PropertyFactory.iconImage("marker-start"),
            PropertyFactory.iconSize(0.60f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        )
    )

    style.addSource(GeoJsonSource("end-pt", Feature.fromGeometry(Point.fromLngLat(endPt.lon!!, endPt.lat!!))))
    style.addLayer(
        SymbolLayer("end-layer", "end-pt").withProperties(
            PropertyFactory.iconImage("marker-end"),
            PropertyFactory.iconSize(0.60f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        )
    )

    // Kilometrovníky
    val splitFeatures = if (showKmMarkers) {
        splits.filter { it.lat != 0.0 && it.lon != 0.0 }.map { sp ->
            Feature.fromGeometry(Point.fromLngLat(sp.lon, sp.lat)).also { it.addStringProperty("iconId", "split-${sp.km}") }
        }
    } else emptyList()
    style.addSource(GeoJsonSource("splits-source", FeatureCollection.fromFeatures(splitFeatures)))
    style.addLayer(
        SymbolLayer("splits-layer", "splits-source").withProperties(
            PropertyFactory.iconImage(Expression.get("iconId")),
            PropertyFactory.iconSize(0.60f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.visibility(if (showKmMarkers) Property.VISIBLE else Property.NONE)
        )
    )

    // Pauzy a zastávky
    val pauseFeatures = if (showPauses) {
        pauses.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).also {
                it.addNumberProperty("index", p.index)
                it.addNumberProperty("durationS", p.durationS)
            }
        }
    } else emptyList()
    style.addSource(GeoJsonSource("pauses-source", FeatureCollection.fromFeatures(pauseFeatures)))
    style.addLayer(
        SymbolLayer("pauses-layer", "pauses-source").withProperties(
            PropertyFactory.iconImage("marker-pause"),
            PropertyFactory.iconSize(0.60f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.visibility(if (showPauses) Property.VISIBLE else Property.NONE)
        )
    )

    // Kurzor z grafu
    val curPt = cursorCoord ?: (validPoints[0].lat!! to validPoints[0].lon!!)
    style.addSource(GeoJsonSource("cursor-pt", Feature.fromGeometry(Point.fromLngLat(curPt.second, curPt.first))))
    style.addLayer(
        CircleLayer("cursor-circle", "cursor-pt").withProperties(
            PropertyFactory.circleRadius(8.5f),
            PropertyFactory.circleColor("#F59E0B"),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.visibility(if (cursorCoord != null) Property.VISIBLE else Property.NONE)
        )
    )

    // Přizpůsobení kamery hranicím trasy
    val lats = validPoints.map { it.lat!! }
    val lons = validPoints.map { it.lon!! }
    val latSpan = (lats.max() - lats.min())
    val lonSpan = (lons.max() - lons.min())
    if (latSpan > 1e-5 && lonSpan > 1e-5) {
        val bounds = LatLngBounds.Builder().includes(validPoints.map { LatLng(it.lat!!, it.lon!!) }).build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 50))
    } else {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(startPt.lat!!, startPt.lon!!), 15.0))
    }
}

private fun updateMapData(
    style: Style,
    validPoints: List<TrackPoint>,
    metricMode: MapMetricMode,
    maxHr: Int?,
    defaultColor: String,
    pauses: List<ActivityPause>,
    splits: List<ActivityKmSplit>,
    showKmMarkers: Boolean,
    showPauses: Boolean,
    cursorCoord: Pair<Double, Double>?,
    highlightSegment: List<Pair<Double, Double>>
) {
    if (validPoints.size < 2) return

    // Registrace nových splits bitmap do stylu, pokud ještě nejsou
    splits.forEach { sp ->
        if (style.getImage("split-${sp.km}") == null) {
            style.addImage("split-${sp.km}", MapBadgeGenerator.createKmBadge(sp.km))
        }
    }

    // Aktualizace trasy
    val routeGeoJson = MapTrackAnalytics.buildColoredSegments(validPoints, metricMode, maxHr, defaultColor)
    style.getSourceAs<GeoJsonSource>("route")?.setGeoJson(routeGeoJson)

    // Aktualizace kilometrovníků
    val splitFeatures = if (showKmMarkers) {
        splits.filter { it.lat != 0.0 && it.lon != 0.0 }.map { sp ->
            Feature.fromGeometry(Point.fromLngLat(sp.lon, sp.lat)).also { it.addStringProperty("iconId", "split-${sp.km}") }
        }
    } else emptyList()
    style.getSourceAs<GeoJsonSource>("splits-source")?.setGeoJson(FeatureCollection.fromFeatures(splitFeatures))
    style.getLayerAs<SymbolLayer>("splits-layer")?.setProperties(
        PropertyFactory.visibility(if (showKmMarkers && splits.isNotEmpty()) Property.VISIBLE else Property.NONE)
    )

    // Aktualizace pauz
    val pauseFeatures = if (showPauses) {
        pauses.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).also {
                it.addNumberProperty("index", p.index)
                it.addNumberProperty("durationS", p.durationS)
            }
        }
    } else emptyList()
    style.getSourceAs<GeoJsonSource>("pauses-source")?.setGeoJson(FeatureCollection.fromFeatures(pauseFeatures))
    style.getLayerAs<SymbolLayer>("pauses-layer")?.setProperties(
        PropertyFactory.visibility(if (showPauses && pauses.isNotEmpty()) Property.VISIBLE else Property.NONE)
    )

    // Aktualizace kurzoru
    if (cursorCoord != null) {
        style.getSourceAs<GeoJsonSource>("cursor-pt")?.setGeoJson(Point.fromLngLat(cursorCoord.second, cursorCoord.first))
        style.getLayerAs<CircleLayer>("cursor-circle")?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
    } else {
        style.getLayerAs<CircleLayer>("cursor-circle")?.setProperties(PropertyFactory.visibility(Property.NONE))
    }

    // Aktualizace zvýrazněného úseku
    if (highlightSegment.size >= 2) {
        val segLine = LineString.fromLngLats(highlightSegment.map { Point.fromLngLat(it.second, it.first) })
        style.getSourceAs<GeoJsonSource>("route-seg")?.setGeoJson(segLine)
        style.getLayerAs<LineLayer>("route-seg-line")?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
    } else {
        style.getLayerAs<LineLayer>("route-seg-line")?.setProperties(PropertyFactory.visibility(Property.NONE))
    }
}

/**
 * Zpětná kompatibilita pro jednoduché volání s List<Pair<Double, Double>>.
 */
@Composable
fun MapLibreRoute(
    provider: MapProvider,
    customStyleUrl: String?,
    route: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    cursorCoord: Pair<Double, Double>? = null,
    highlightSegment: List<Pair<Double, Double>> = emptyList(),
) {
    val syntheticTrack = remember(route) {
        route.mapIndexed { idx, (lat, lon) ->
            TrackPoint(
                tMs = idx * 1000L,
                lat = lat,
                lon = lon
            )
        }
    }
    MapLibreRoute(
        provider = provider,
        customStyleUrl = customStyleUrl,
        track = syntheticTrack,
        modifier = modifier,
        metricMode = MapMetricMode.UNIFORM,
        showKmMarkers = false,
        showPauses = false,
        cursorCoord = cursorCoord,
        highlightSegment = highlightSegment
    )
}

data class MultiRouteItem(
    val title: String,
    val hexColor: String,
    val points: List<Pair<Double, Double>>
)

/**
 * Vykreslení více tras najednou na mapovém podkladu (pro porovnání aktivit Compare).
 * Obsahuje tmavé halo pod trasou pro maximální kontrast, start [S] a cíl [C] markery,
 * a plynulé centrování kamery na vybranou trasu nebo všechny trasy.
 */
@Composable
fun MapLibreMultiRoute(
    provider: MapProvider,
    customStyleUrl: String?,
    routes: List<MultiRouteItem>,
    focusedRouteIndex: Int? = null,
    modifier: Modifier = Modifier,
) {
    val styleBuilder = remember(provider, customStyleUrl) { buildStyleBuilder(provider, customStyleUrl) } ?: return
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapLibre.getInstance(ctx); MapView(ctx) }
    val mapInstance = remember { mutableStateOf<MapLibreMap?>(null) }

    DisposableEffect(lifecycle, mapView) {
        mapView.onCreate(null)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }

    fun renderMultiRoutes(style: Style, items: List<MultiRouteItem>, focusedIdx: Int?) {
        for (i in 0 until 16) {
            style.removeLayer("multi-start-marker-$i")
            style.removeLayer("multi-end-marker-$i")
            style.removeLayer("multi-line-$i")
            style.removeLayer("multi-line-halo-$i")
            style.removeSource("multi-start-$i")
            style.removeSource("multi-end-$i")
            style.removeSource("multi-route-$i")
        }

        items.forEachIndexed { idx, item ->
            val pts = item.points
            if (pts.size >= 2) {
                val line = LineString.fromLngLats(pts.map { Point.fromLngLat(it.second, it.first) })
                style.addSource(GeoJsonSource("multi-route-$idx", Feature.fromGeometry(line)))

                // Tmavé halo (casing) pod trasou pro zřetelnou viditelnost na jakékoliv mapě
                style.addLayer(
                    LineLayer("multi-line-halo-$idx", "multi-route-$idx").withProperties(
                        PropertyFactory.lineColor("#0F172A"),
                        PropertyFactory.lineWidth(7.5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineOpacity(0.92f)
                    )
                )

                // Hlavní barevná křivka trasy
                val isFocused = (focusedIdx == null || focusedIdx == idx)
                style.addLayer(
                    LineLayer("multi-line-$idx", "multi-route-$idx").withProperties(
                        PropertyFactory.lineColor(item.hexColor),
                        PropertyFactory.lineWidth(if (isFocused) 4.5f else 2.6f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineOpacity(if (isFocused) 1.0f else 0.40f)
                    )
                )

                // Start pin (barevný bod s bílým lemováním)
                val startPt = pts.first()
                style.addSource(GeoJsonSource("multi-start-$idx", Feature.fromGeometry(Point.fromLngLat(startPt.second, startPt.first))))
                style.addLayer(
                    CircleLayer("multi-start-marker-$idx", "multi-start-$idx").withProperties(
                        PropertyFactory.circleRadius(6.5f),
                        PropertyFactory.circleColor(item.hexColor),
                        PropertyFactory.circleStrokeWidth(2.5f),
                        PropertyFactory.circleStrokeColor("#FFFFFF")
                    )
                )

                // Cíl pin (tmavý střed s lemem v barvě trasy)
                val endPt = pts.last()
                style.addSource(GeoJsonSource("multi-end-$idx", Feature.fromGeometry(Point.fromLngLat(endPt.second, endPt.first))))
                style.addLayer(
                    CircleLayer("multi-end-marker-$idx", "multi-end-$idx").withProperties(
                        PropertyFactory.circleRadius(6.5f),
                        PropertyFactory.circleColor("#0F172A"),
                        PropertyFactory.circleStrokeWidth(2.5f),
                        PropertyFactory.circleStrokeColor(item.hexColor)
                    )
                )
            }
        }
    }

    fun adjustCamera(map: MapLibreMap, mv: MapView, items: List<MultiRouteItem>, focusedIdx: Int?, animate: Boolean) {
        val targetPts = if (focusedIdx != null && focusedIdx in items.indices) {
            items[focusedIdx].points
        } else {
            items.flatMap { it.points }
        }
        if (targetPts.size < 2) return

        val lats = targetPts.map { it.first }
        val lons = targetPts.map { it.second }
        val latSpan = lats.max() - lats.min()
        val lonSpan = lons.max() - lons.min()

        val action = {
            if (latSpan > 1e-5 && lonSpan > 1e-5) {
                val bounds = LatLngBounds.Builder().includes(targetPts.map { LatLng(it.first, it.second) }).build()
                val update = CameraUpdateFactory.newLatLngBounds(bounds, 55)
                if (animate) map.animateCamera(update, 600) else map.moveCamera(update)
            } else {
                val update = CameraUpdateFactory.newLatLngZoom(LatLng(targetPts.first().first, targetPts.first().second), 14.5)
                if (animate) map.animateCamera(update, 600) else map.moveCamera(update)
            }
        }

        if (mv.width > 0 && mv.height > 0) {
            action()
        } else {
            mv.post { action() }
        }
    }

    LaunchedEffect(styleBuilder) {
        val map = mapInstance.value ?: return@LaunchedEffect
        map.setStyle(styleBuilder) { style ->
            renderMultiRoutes(style, routes, focusedRouteIndex)
            adjustCamera(map, mapView, routes, focusedRouteIndex, animate = false)
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
    ) { mv ->
        val map = mapInstance.value
        if (map == null) {
            mv.getMapAsync { loadedMap ->
                mapInstance.value = loadedMap
                loadedMap.uiSettings.isAttributionEnabled = false
                loadedMap.uiSettings.isLogoEnabled = false
                loadedMap.setStyle(styleBuilder) { style ->
                    renderMultiRoutes(style, routes, focusedRouteIndex)
                    adjustCamera(loadedMap, mv, routes, focusedRouteIndex, animate = false)
                }
            }
        } else {
            val style = map.style
            if (style != null && style.isFullyLoaded) {
                renderMultiRoutes(style, routes, focusedRouteIndex)
                adjustCamera(map, mv, routes, focusedRouteIndex, animate = true)
            }
        }
    }
}

@Composable
fun MapLibreMultiRoute(
    provider: MapProvider,
    customStyleUrl: String?,
    routes: List<Pair<String, List<Pair<Double, Double>>>>,
    modifier: Modifier = Modifier,
) {
    MapLibreMultiRoute(
        provider = provider,
        customStyleUrl = customStyleUrl,
        routes = routes.mapIndexed { idx, (hex, pts) -> MultiRouteItem("Trasa ${idx + 1}", hex, pts) },
        focusedRouteIndex = null,
        modifier = modifier
    )
}
