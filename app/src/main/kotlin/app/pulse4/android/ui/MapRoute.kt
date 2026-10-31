package app.pulse4.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import app.pulse4.android.data.MapProvider
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

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
                listOf(
                    "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}"
                ),
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
 * Interaktivní MapLibre mapa trasy s volitelným poskytovatelem dlaždic (Carto Dark, OpenStreetMap, OpenTopoMap,
 * Carto Voyager, Esri Satelitní, nebo vlastní MapLibre style URL).
 * Zobrazuje trasu s контраstním lemováním, start (zelený bod), cíl (červený bod), označený úsek a kurzor.
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
    val styleBuilder = remember(provider, customStyleUrl) { buildStyleBuilder(provider, customStyleUrl) } ?: return
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapLibre.getInstance(ctx); MapView(ctx) }

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

    AndroidView(
        factory = { mapView },
        modifier = modifier
            .fillMaxWidth()
            .height(265.dp)
            .clip(RoundedCornerShape(14.dp))
    ) { mv ->
        mv.getMapAsync { map ->
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.setStyle(styleBuilder) { style ->
                if (route.size < 2) return@setStyle
                val line = LineString.fromLngLats(route.map { Point.fromLngLat(it.second, it.first) })
                style.addSource(GeoJsonSource("route", Feature.fromGeometry(line)))
                // Tmavé lemování čáry pro čitelnost na světlé i satelitní mapě
                style.addLayer(
                    LineLayer("route-casing", "route").withProperties(
                        PropertyFactory.lineColor("#0A0D14"),
                        PropertyFactory.lineWidth(7.5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )
                )
                // Hlavní čára trasy
                val mainColor = if (provider == MapProvider.CARTO_DARK || provider == MapProvider.ESRI_SATELLITE) "#38BDF8" else "#E11D48"
                style.addLayer(
                    LineLayer("route-line", "route").withProperties(
                        PropertyFactory.lineColor(mainColor),
                        PropertyFactory.lineWidth(4.5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )
                )

                // Zvýrazněný úsek (pokud uživatel vybral interval v grafu)
                if (highlightSegment.size >= 2) {
                    val segLine = LineString.fromLngLats(highlightSegment.map { Point.fromLngLat(it.second, it.first) })
                    style.addSource(GeoJsonSource("route-seg", Feature.fromGeometry(segLine)))
                    style.addLayer(
                        LineLayer("route-seg-line", "route-seg").withProperties(
                            PropertyFactory.lineColor("#F59E0B"),
                            PropertyFactory.lineWidth(6.5f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
                        )
                    )
                }

                // Start bod (zelený)
                val startPt = route.first()
                style.addSource(GeoJsonSource("start-pt", Feature.fromGeometry(Point.fromLngLat(startPt.second, startPt.first))))
                style.addLayer(
                    CircleLayer("start-circle", "start-pt").withProperties(
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleColor("#10B981"),
                        PropertyFactory.circleStrokeWidth(2.5f),
                        PropertyFactory.circleStrokeColor("#FFFFFF")
                    )
                )

                // Cíl bod (červený)
                val endPt = route.last()
                style.addSource(GeoJsonSource("end-pt", Feature.fromGeometry(Point.fromLngLat(endPt.second, endPt.first))))
                style.addLayer(
                    CircleLayer("end-circle", "end-pt").withProperties(
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleColor("#FF5252"),
                        PropertyFactory.circleStrokeWidth(2.5f),
                        PropertyFactory.circleStrokeColor("#FFFFFF")
                    )
                )

                // Kurzor bod (při posunu prstem v grafu)
                cursorCoord?.let { cur ->
                    style.addSource(GeoJsonSource("cursor-pt", Feature.fromGeometry(Point.fromLngLat(cur.second, cur.first))))
                    style.addLayer(
                        CircleLayer("cursor-circle", "cursor-pt").withProperties(
                            PropertyFactory.circleRadius(8.5f),
                            PropertyFactory.circleColor("#F59E0B"),
                            PropertyFactory.circleStrokeWidth(3f),
                            PropertyFactory.circleStrokeColor("#FFFFFF")
                        )
                    )
                }

                val lats = route.map { it.first }
                val lons = route.map { it.second }
                val latSpan = (lats.max() - lats.min())
                val lonSpan = (lons.max() - lons.min())
                if (latSpan > 1e-5 && lonSpan > 1e-5) {
                    val bounds = LatLngBounds.Builder().includes(route.map { LatLng(it.first, it.second) }).build()
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 55))
                } else {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(startPt.first, startPt.second), 15.0))
                }
            }
        }
    }
}

/**
 * Vykreslení více tras najednou na mapovém podkladu (pro porovnání aktivit Compare).
 */
@Composable
fun MapLibreMultiRoute(
    provider: MapProvider,
    customStyleUrl: String?,
    routes: List<Pair<String, List<Pair<Double, Double>>>>,
    modifier: Modifier = Modifier,
) {
    val styleBuilder = remember(provider, customStyleUrl) { buildStyleBuilder(provider, customStyleUrl) } ?: return
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapLibre.getInstance(ctx); MapView(ctx) }

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

    AndroidView(
        factory = { mapView },
        modifier = modifier
            .fillMaxWidth()
            .height(250.dp)
            .clip(RoundedCornerShape(14.dp))
    ) { mv ->
        mv.getMapAsync { map ->
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.setStyle(styleBuilder) { style ->
                val allPts = routes.flatMap { it.second }
                if (allPts.size < 2) return@setStyle
                routes.forEachIndexed { idx, (hexColor, pts) ->
                    if (pts.size >= 2) {
                        val line = LineString.fromLngLats(pts.map { Point.fromLngLat(it.second, it.first) })
                        style.addSource(GeoJsonSource("multi-route-$idx", Feature.fromGeometry(line)))
                        style.addLayer(
                            LineLayer("multi-line-$idx", "multi-route-$idx").withProperties(
                                PropertyFactory.lineColor(hexColor),
                                PropertyFactory.lineWidth(4.5f),
                                PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
                            )
                        )
                    }
                }
                val lats = allPts.map { it.first }
                val lons = allPts.map { it.second }
                if ((lats.max() - lats.min()) > 1e-5 && (lons.max() - lons.min()) > 1e-5) {
                    val bounds = LatLngBounds.Builder().includes(allPts.map { LatLng(it.first, it.second) }).build()
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 55))
                } else {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(allPts.first().first, allPts.first().second), 14.5))
                }
            }
        }
    }
}
