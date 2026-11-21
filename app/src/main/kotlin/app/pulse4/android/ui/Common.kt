@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse4.android.ui.theme.JetBrainsMonoFamily
import app.pulse4.android.ui.theme.MetricMotifKind
import app.pulse4.android.ui.theme.MineralBackgroundDark
import app.pulse4.android.ui.theme.MineralOutlineDark
import app.pulse4.android.ui.theme.MineralSurfaceDark
import app.pulse4.android.ui.theme.MineralSurfaceVariantDark
import app.pulse4.android.ui.theme.drawMetricMotifIllustration
import app.pulse4.domain.PLog
import app.pulse4.domain.Reading
import app.pulse4.provider.AreaStatus
import app.pulse4.sync.DeliveryState
import app.pulse4.ui.Channel
import app.pulse4.ui.ChartInfo
import app.pulse4.ui.ChartInfoOptions
import app.pulse4.ui.ChartInfoTexts
import app.pulse4.ui.ChartKey
import app.pulse4.ui.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Načtení z databáze mimo hlavní vlákno; `key` vyvolá nové načtení. Centrální ošetření chyb (R7). */
@Composable
fun <T> io(key: Any?, block: () -> T): State<T?> = produceState<T?>(null, key) {
    value = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            PLog.e("Pulse4", e) { "io() failed: ${e.javaClass.simpleName}" }
            null
        }
    }
}

/**
 * Like [io], but stale-while-revalidate with a visible failure state: the last result for [key] from [cache] is
 * shown immediately and refreshed in the background; a failed load yields [LoadState.Failed] (or keeps the stale
 * value) instead of staying "loading" forever. Increase [retry] to force a reload (e.g. from a retry button).
 */
@Composable
fun <K : Any, V : Any> ioCached(
    key: K,
    cache: LastResultCache<K, V>,
    retry: Int = 0,
    block: () -> V,
): State<LoadState<V>> = produceState(initialLoadState(key, cache), key, retry) {
    cache.get(key)?.let { value = LoadState.Ready(it, stale = true) }
    if (value is LoadState.Failed) value = LoadState.Loading
    value = loadWithCache(key, cache, block = block)
}

/** Formátování reagující na zvolenou soustavu jednotek (Metrické / Imperiální). */
@Volatile
var activeFmt: Fmt = Fmt.default

val fmt: Fmt get() = activeFmt

/** Zda na křivkách grafů vykreslovat malé puntíky v místech skutečně naměřených hodnot. */
var showChartPointsState by mutableStateOf(true)

/** Kompaktní přepínač zobrazení bodů měření na křivkách grafů. */
@Composable
fun ChartPointsTogglePill(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(0xFF38BDF8)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) accent.copy(alpha = 0.18f) else Color(0xCC0B1322))
            .border(
                1.dp,
                if (enabled) accent.copy(alpha = 0.55f) else Color(0xFF334155),
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .then(
                    if (enabled) Modifier.background(accent)
                    else Modifier.border(1.4.dp, Color(0xFF64748B), CircleShape)
                )
        )
        Text(
            "Body",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (enabled) accent else Color(0xFF94A3B8),
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Stavy doručení tak, jak je vidí uživatel; jen ACKED znamená, že příjemcova aplikace změnu uložila. */
fun DeliveryState.label(): String = app.pulse4.ui.Labels.delivery(this)

fun AreaStatus.label(): String = app.pulse4.ui.Labels.area(this)

/**
 * Grafana Singlestat / Oura Ring / Garmin Connect styl karty s historickým grafem na pozadí.
 * Nenáročná na výkon: cesty křivky se přepočítávají pouze při změně dat nebo velikosti.
 */
@Composable
fun SinglestatCard(
    title: String,
    icon: Any? = null,
    reading: Reading<String>,
    historyValues: List<Double?>,
    accentColor: Color,
    windowLabel: String,
    deltaLabel: String? = null,
    deltaColor: Color? = null,
    statusLabel: String? = null,
    statusColor: Color? = null,
    subtitle: String? = null,
    valueColor: Color = Color.White,
    artworkRes: Int? = null,
    motifKind: MetricMotifKind? = null,
    heroTopColor: Color? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val finite = remember(historyValues) { historyValues.filterNotNull().filter { it.isFinite() } }
    val cardBg = heroTopColor ?: MineralSurfaceDark

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                listOf(
                    accentColor.copy(alpha = 0.45f),
                    Color(0xFF1B2A3B).copy(alpha = 0.55f)
                )
            )
        ),
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Box(Modifier.fillMaxWidth()) {
            // 1. Fotografie / umělecký podklad (Oura / Nordic wellness styl)
            if (artworkRes != null) {
                Image(
                    painter = painterResource(id = artworkRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(0.56f)
                )
            }

            // 2. Atmosférické vrstvy a procedurální motiv
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height

                // Ztmavení pod texty na levé i pravé straně, uprostřed prosvítá ilustrace
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xE0050911),
                            cardBg.copy(alpha = 0.56f),
                            Color(0xA6050911)
                        )
                    )
                )

                // Spodní ztmavení pro čitelnost řádku Měřeno / Min · Max
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x33050911),
                            Color(0xBF050911)
                        )
                    )
                )

                // Jemná radiální záře v pravé horní části karty v barvě metriky
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.24f),
                            accentColor.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.86f, h * 0.35f),
                        radius = w * 0.52f
                    ),
                    center = Offset(w * 0.86f, h * 0.35f),
                    radius = w * 0.52f
                )

                // Vektorový procedurální motiv
                if (motifKind != null) {
                    drawMetricMotifIllustration(
                        motifKind = motifKind,
                        primaryColor = accentColor,
                        secondaryColor = Color.White,
                        alphaMultiplier = 0.85f
                    )
                }
            }

            // 3. Graf na pozadí (Sparkline + area fill + svítící koncový bod)
            if (finite.size >= 2) {
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                ) {
                    val lo = finite.minOrNull() ?: 0.0
                    val hi = finite.maxOrNull() ?: 100.0
                    val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
                    val topPad = size.height * 0.34f
                    val botPad = 6f
                    val usableH = (size.height - topPad - botPad).coerceAtLeast(10f)

                    fun x(i: Int) = size.width * i / (historyValues.size - 1).coerceAtLeast(1)
                    fun y(v: Double) = topPad + (usableH * (1.0 - (v - lo) / span)).toFloat()

                    val pts = mutableListOf<Pair<Int, Double>>()
                    historyValues.forEachIndexed { idx, v -> if (v != null && v.isFinite()) pts.add(idx to v) }
                    if (pts.size >= 2) {
                        val strokePath = Path()
                        val fillPath = Path()
                        val firstX = x(pts.first().first)
                        val firstY = y(pts.first().second)
                        strokePath.moveTo(firstX, firstY)
                        fillPath.moveTo(firstX, size.height)
                        fillPath.lineTo(firstX, firstY)

                        for (i in 1 until pts.size) {
                            val px = x(pts[i - 1].first)
                            val py = y(pts[i - 1].second)
                            val cx = x(pts[i].first)
                            val cy = y(pts[i].second)
                            val midX = px + (cx - px) * 0.5f
                            strokePath.cubicTo(midX, py, midX, cy, cx, cy)
                            fillPath.cubicTo(midX, py, midX, cy, cx, cy)
                        }
                        val lastX = x(pts.last().first)
                        val lastY = y(pts.last().second)
                        fillPath.lineTo(lastX, size.height)
                        fillPath.close()

                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(accentColor.copy(alpha = 0.26f), accentColor.copy(alpha = 0.02f)),
                                startY = topPad,
                                endY = size.height
                            )
                        )
                        drawPath(
                            path = strokePath,
                            color = accentColor.copy(alpha = 0.65f),
                            style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                        drawCircle(
                            color = accentColor.copy(alpha = 0.35f),
                            radius = 7.dp.toPx(),
                            center = Offset(lastX, lastY)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 2.8.dp.toPx(),
                            center = Offset(lastX, lastY)
                        )
                    }
                }
            }

            // Obsah karty nad grafem
            Column(Modifier.padding(15.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        if (icon != null) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(accentColor.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                when (icon) {
                                    is androidx.compose.ui.graphics.vector.ImageVector -> Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    is String -> Text(icon, style = MaterialTheme.typography.titleMedium)
                                    else -> {}
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
                        val badgeText = statusLabel ?: when (reading) {
                            is Reading.Value -> "Aktuální"
                            Reading.NotSyncedYet -> "Čeká"
                            else -> null
                        }
                        if (badgeText != null) {
                            val badgeCol = statusColor ?: if (valueColor != Color.White) valueColor else accentColor
                            Text(
                                badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = badgeCol,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .background(
                                        Color(0xCC060B14),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        1.dp,
                                        badgeCol.copy(alpha = 0.45f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Text(
                            " ›",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color(0xFFCBD5E1),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                when (reading) {
                    is Reading.Value -> {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                reading.value,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 24.sp,
                                    letterSpacing = (-0.5).sp
                                ),
                                fontWeight = FontWeight.Bold,
                                color = valueColor,
                                maxLines = 1,
                                softWrap = false
                            )
                            if (deltaLabel != null) {
                                Text(
                                    deltaLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = deltaColor ?: accentColor,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier
                                        .padding(bottom = 2.dp, start = 6.dp)
                                        .background(Color(0xC0050911), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (!subtitle.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE2E8F0),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Měřeno ${fmt.time(reading.measuredAtUtcMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFCBD5E1)
                            )
                            if (finite.size >= 2) {
                                val minMaxDecimals = if (reading.value.contains("kg") || reading.value.contains("lb") || (finite.max() - finite.min()) < 3.0) 2
                                else if (reading.value.contains("%") || reading.value.contains("m/s")) 1 else 0
                                Text(
                                    "Min ${fmt.number(finite.min(), minMaxDecimals)} · Max ${fmt.number(finite.max(), minMaxDecimals)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                        }
                    }
                    Reading.NotShared -> Text("Není sdíleno", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF64748B))
                    Reading.DeviceDoesNotMeasure -> {
                        Text(
                            "—",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            "Dnes bez měření · klikněte pro historii",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B)
                        )
                    }
                    Reading.NotSyncedYet -> {
                        Text(
                            "—",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            "Zatím nesynchronizováno · klikněte pro detail",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MetricCard(title: String, reading: Reading<String>, modifier: Modifier = Modifier) {
    SinglestatCard(
        title = title,
        icon = "",
        reading = reading,
        historyValues = emptyList(),
        accentColor = MaterialTheme.colorScheme.primary,
        windowLabel = "dnes",
        modifier = modifier
    )
}

/**
 * Položka datové řady pro společný graf (Grafana styl).
 *
 * Line style and marker are derived inside [MultiSeriesLineChart] from the colors via
 * [SeriesColorAllocator.assignStylesOnly]: series whose colors are closer than
 * [SeriesColorAllocator.MIN_DELTA_E] get distinct line styles / markers.
 */
data class SeriesItem(
    val id: String,
    val label: String,
    val color: Color,
    val values: List<Double?>,
    val unit: String = "",
)

/** Line style of a series in [MultiSeriesLineChart]. */
internal enum class SeriesLineStyle { SOLID, DASHED, DOTTED, DASH_DOT }

/**
 * Zformátuje změnu metriky:
 * U tělesné hmotnosti (kg) zobrazí změny menší než 1 kg přímo v gramech (např. +200 g, -500 g, +50 g),
 * u větších změn v kg (+1.20 kg). U procent, tepu a HRV zachová jejich přirozené jednotky.
 */
fun formatDeltaMetric(diff: Double, unit: String): String {
    val sign = if (diff > 0.0) "+" else if (diff < 0.0) "-" else "±"
    val absDiff = kotlin.math.abs(diff)
    return when (unit) {
        "kg", "lb" -> {
            if (absDiff < 0.999) {
                val g = kotlin.math.round(absDiff * 1000.0).toInt()
                "$sign$g g"
            } else {
                "$sign${String.format(java.util.Locale.getDefault(), "%.2f", absDiff)} $unit"
            }
        }
        "%" -> "$sign${String.format(java.util.Locale.getDefault(), "%.1f", absDiff)} %"
        "bpm" -> "$sign${kotlin.math.round(absDiff).toInt()} bpm"
        "ms" -> "$sign${kotlin.math.round(absDiff).toInt()} ms"
        else -> "$sign${String.format(java.util.Locale.getDefault(), "%.1f", absDiff)} $unit"
    }
}

private fun inferChartArtworkRes(unit: String, title: String = ""): Int {
    val u = unit.lowercase()
    val t = title.lowercase()
    return when {
        t.contains("spánek") || t.contains("sleep") || u == "h" || u == "hod" -> app.pulse4.android.R.drawable.img_motif_sleep_night
        t.contains("hrv") || u == "ms" -> app.pulse4.android.R.drawable.img_motif_hrv_autonomic
        t.contains("ecg") || t.contains("ekg") -> app.pulse4.android.R.drawable.img_motif_ecg_signal
        t.contains("tep") || u == "bpm" -> app.pulse4.android.R.drawable.img_motif_heart_real
        t.contains("voda") || t.contains("hydr") -> app.pulse4.android.R.drawable.img_motif_body_water
        t.contains("tuk") || t.contains("fat") -> app.pulse4.android.R.drawable.img_motif_body_fat
        t.contains("kost") || t.contains("bone") -> app.pulse4.android.R.drawable.img_motif_bone_mass
        t.contains("váh") || t.contains("sval") || u == "kg" || u == "lb" -> app.pulse4.android.R.drawable.img_motif_body_silhouette
        t.contains("krok") || t.contains("běh") || t.contains("tempo") -> app.pulse4.android.R.drawable.img_alpine_trail_hero
        t.contains("stres") -> app.pulse4.android.R.drawable.img_motif_brain_stress
        t.contains("battery") || t.contains("energ") -> app.pulse4.android.R.drawable.img_motif_body_battery
        else -> app.pulse4.android.R.drawable.img_nordic_wellness_hero
    }
}

private fun inferChartMotifKind(unit: String, title: String = ""): MetricMotifKind {
    val u = unit.lowercase()
    val t = title.lowercase()
    return when {
        t.contains("spánek") || t.contains("sleep") || u == "h" || u == "hod" -> MetricMotifKind.SLEEP_NOCTURNAL
        t.contains("hrv") || u == "ms" -> MetricMotifKind.HRV_AUTONOMIC
        t.contains("ecg") || t.contains("ekg") -> MetricMotifKind.ECG_SIGNAL
        t.contains("tep") || u == "bpm" -> MetricMotifKind.HEART_PULSE
        t.contains("voda") || t.contains("hydr") -> MetricMotifKind.BODY_WATER
        t.contains("tuk") || t.contains("fat") -> MetricMotifKind.BODY_FAT
        t.contains("kost") || t.contains("bone") -> MetricMotifKind.BONE_MASS
        t.contains("váh") || t.contains("sval") || u == "kg" || u == "lb" -> MetricMotifKind.BODY_COMPOSITION
        t.contains("krok") || t.contains("běh") -> MetricMotifKind.STEPS_TRAIL
        t.contains("stres") -> MetricMotifKind.AUTONOMIC_STRESS
        else -> MetricMotifKind.RECOVERY_SUNRISE
    }
}

@Composable
private fun ChartCardFrame(
    embedded: Boolean,
    accentColor: Color,
    artworkRes: Int,
    motifKind: MetricMotifKind,
    artworkAlpha: Float,
    glowCenterXFraction: Float,
    glowCenterYFraction: Float,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (embedded) {
        Column(modifier = modifier.fillMaxWidth().padding(top = 4.dp), content = content)
    } else {
        Card(
            modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        accentColor.copy(alpha = 0.54f),
                        Color.White.copy(alpha = 0.16f),
                        accentColor.copy(alpha = 0.26f)
                    )
                )
            ),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF070C15))
        ) {
            Box(Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = artworkRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(artworkAlpha)
                )
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xCC060B14),
                                Color(0x99060B14),
                                Color(0xE6050911)
                            )
                        )
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.22f),
                                accentColor.copy(alpha = 0.04f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * glowCenterXFraction, size.height * glowCenterYFraction),
                            radius = size.width * 0.55f
                        ),
                        center = Offset(size.width * glowCenterXFraction, size.height * glowCenterYFraction),
                        radius = size.width * 0.55f
                    )
                    drawMetricMotifIllustration(
                        motifKind = motifKind,
                        primaryColor = accentColor,
                        secondaryColor = Color.White,
                        alphaMultiplier = 0.65f
                    )
                }
                Column(Modifier.padding(15.dp), content = content)
            }
        }
    }
}

/**
 * Vícenásobný srovnávací graf (Grafana / Oura Biometric Observatory styl) pro Skupinový Dashboard,
 * Korelace metrik i Porovnání aktivit (Compare).
 * Podporuje zapínání/vypínání jednotlivých křivek v legendě, fotografické pozadí a interaktivní odečet.
 */
@Composable
fun MultiSeriesLineChart(
    series: List<SeriesItem>,
    modifier: Modifier = Modifier,
    xLabels: List<String> = emptyList(),
    title: String = "Srovnávací graf",
    artworkRes: Int? = null,
    motifKind: MetricMotifKind? = null,
    infoKey: ChartKey = ChartKey.METRICS_CORRELATION,
    infoOptions: ChartInfoOptions = ChartInfoOptions(),
    info: ChartInfo? = null,
    showInfo: Boolean = true,
    embedded: Boolean = false,
) {
    var hiddenIds by remember { mutableStateOf(setOf<String>()) }
    var selectedIdx by remember { mutableStateOf<Int?>(null) }
    var independentScale by remember { mutableStateOf(true) }

    val visibleSeries = remember(series, hiddenIds) {
        series.filter { it.id !in hiddenIds && it.values.any { v -> v != null } }
    }
    val maxPoints = remember(series) { series.maxOfOrNull { it.values.size } ?: 0 }
    // Styles depend on all series (not only visible ones) so toggling the legend never restyles lines.
    val seriesStyles = remember(series) {
        val styles = SeriesColorAllocator.assignStylesOnly(series.map { it.color })
        series.mapIndexed { i, s -> s.id to styles[i] }.toMap()
    }
    val allFinite = remember(visibleSeries) { visibleSeries.flatMap { it.values.filterNotNull() }.filter { it.isFinite() } }
    val rawLo = remember(allFinite) { allFinite.minOrNull() ?: 0.0 }
    val rawHi = remember(allFinite) { allFinite.maxOrNull() ?: 100.0 }
    val primaryUnit = remember(visibleSeries) {
        val units = visibleSeries.map { it.unit }.filter { it.isNotBlank() }.distinct()
        if (units.size == 1) units.first() else units.firstOrNull() ?: ""
    }
    val leadColor = remember(visibleSeries, series) {
        visibleSeries.firstOrNull()?.color ?: series.firstOrNull()?.color ?: Color(0xFF2DD4BF)
    }
    val effectiveArtwork = remember(artworkRes, primaryUnit, title, visibleSeries) {
        artworkRes ?: inferChartArtworkRes(primaryUnit, visibleSeries.firstOrNull()?.label ?: title)
    }
    val effectiveMotif = remember(motifKind, primaryUnit, title, visibleSeries) {
        motifKind ?: inferChartMotifKind(primaryUnit, visibleSeries.firstOrNull()?.label ?: title)
    }

    // Výpočet rozsahů pro každou řadu zvlášť pro vysoké rozlišení změn (±100g až 500g)
    data class SeriesScale(val lo: Double, val hi: Double, val span: Double)
    val seriesScales = remember(visibleSeries, independentScale, rawLo, rawHi) {
        visibleSeries.associate { s ->
            if (!independentScale) {
                val span = (rawHi - rawLo).takeIf { it > 1e-9 } ?: 1.0
                s.id to SeriesScale(rawLo, rawHi, span)
            } else {
                val f = s.values.filterNotNull().filter { it.isFinite() }
                val sMin = f.minOrNull() ?: 0.0
                val sMax = f.maxOrNull() ?: 1.0
                val sRawSpan = sMax - sMin
                val minSpanForUnit = when (s.unit) {
                    "kg", "lb" -> 0.80
                    "%" -> 2.0
                    "bpm" -> 6.0
                    "ms" -> 6.0
                    else -> 1.0
                }
                val pad = maxOf(sRawSpan * 0.22, minSpanForUnit / 2.0)
                val sLo = (sMin - pad).coerceAtLeast(if (s.unit == "%") 0.0 else -10000.0)
                val sHi = sMax + pad
                val sSpan = (sHi - sLo).takeIf { it > 1e-9 } ?: 1.0
                s.id to SeriesScale(sLo, sHi, sSpan)
            }
        }
    }

    val primaryScale = remember(visibleSeries, seriesScales, rawLo, rawHi) {
        visibleSeries.firstOrNull { it.unit == "kg" }?.let { seriesScales[it.id] }
            ?: seriesScales.values.firstOrNull()
            ?: SeriesScale(rawLo, rawHi, (rawHi - rawLo).takeIf { it > 1e-9 } ?: 1.0)
    }

    val yTicksLo = if (independentScale) primaryScale.lo else rawLo
    val yTicksHi = if (independentScale) primaryScale.hi else rawHi
    val yTicksSpan = (yTicksHi - yTicksLo).takeIf { it > 1e-9 } ?: 1.0
    val yDecimals = if (primaryUnit == "kg" || primaryUnit == "lb" || yTicksSpan <= 4.0) 2 else 1

    val gridColor = Color.White.copy(alpha = 0.09f)
    val cursorColor = Color(0xFFE2E8F0)
    val showMeasuredPoints = showChartPointsState

    ChartCardFrame(
        embedded = embedded,
        accentColor = leadColor,
        artworkRes = effectiveArtwork,
        motifKind = effectiveMotif,
        artworkAlpha = 0.64f,
        glowCenterXFraction = 0.82f,
        glowCenterYFraction = 0.25f,
        modifier = modifier,
    ) {
                // FlowRow: on narrow screens (320 dp) the scale toggle wraps under the title instead of squeezing it.
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            selectedIdx?.let { idx ->
                                val dateText = xLabels.getOrNull(idx) ?: "Bod #${idx + 1}"
                                Text(
                                    "Odečet: $dateText (klepnutím zrušíte)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF38BDF8),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        if (showInfo) {
                            val effectiveInfo = remember(info, infoKey, infoOptions) {
                                info ?: ChartInfoTexts.build(infoKey, infoOptions)
                            }
                            ChartInfoAction(effectiveInfo)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xCC0B1322))
                            .clickable { independentScale = !independentScale }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (independentScale) "Jen tvar změn" else "Společná osa",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (independentScale) Color(0xFF2DD4BF) else Color(0xFF94A3B8)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Interaktivní legenda s okamžitým odečtem hodnot a přírůstků vs předchozí měření
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    series.forEach { s ->
                        val enabled = s.id !in hiddenIds
                        val activeIdx = selectedIdx ?: s.values.indexOfLast { it != null && it.isFinite() }.takeIf { it >= 0 }
                        val curVal = activeIdx?.let { s.values.getOrNull(it) }
                        val prevVal = activeIdx?.let { if (it > 0) s.values.getOrNull(it - 1) else null }
                        val delta = if (curVal != null && prevVal != null) curVal - prevVal else null
                        val deltaStr = if (delta != null) " (${formatDeltaMetric(delta, s.unit)})" else ""

                        val decimals = when (s.unit) {
                            "kg", "lb" -> 2
                            "%", "m/s", "h" -> 1
                            "bpm", "ms", "W", "kcal" -> 0
                            else -> 1
                        }
                        val valStr = curVal?.let { "${fmt.number(it, decimals)}${if (s.unit.isNotBlank()) " ${s.unit}" else ""}$deltaStr" } ?: "—"
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (enabled) s.color.copy(alpha = 0.22f)
                                    else Color(0x990F172A)
                                )
                                .clickable {
                                    hiddenIds = if (enabled) hiddenIds + s.id else hiddenIds - s.id
                                }
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SeriesLegendSample(
                                style = seriesStyles[s.id],
                                color = if (enabled) s.color else Color.Gray
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${s.label}: $valStr",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (enabled) Color.White else Color.Gray,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                if (allFinite.size < 2 || maxPoints < 2) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Pro zobrazení křivek jsou potřeba alespoň 2 datové body.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Osa Y (5 úrovní s vysokým rozlišením pro odečet 200g změn)
                        Column(
                            modifier = Modifier
                                .height(195.dp)
                                .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.End
                        ) {
                            for (step in 0..4) {
                                val tickVal = yTicksHi - yTicksSpan * (step / 4.0)
                                val suffix = if (primaryUnit.isNotBlank()) " $primaryUnit" else ""
                                Text(
                                    "${fmt.number(tickVal, yDecimals)}$suffix",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFCBD5E1),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        Canvas(
                            modifier = Modifier
                                .weight(1f)
                                .height(195.dp)
                                .padding(vertical = 6.dp)
                                .pointerInput(maxPoints) {
                                    fun idx(x: Float) = ((x / size.width) * (maxPoints - 1)).toInt().coerceIn(0, maxPoints - 1)
                                    detectTapGestures { offset ->
                                        val tapped = idx(offset.x)
                                        selectedIdx = if (selectedIdx == tapped) null else tapped
                                    }
                                }
                                .pointerInput(maxPoints) {
                                    fun idx(x: Float) = ((x / size.width) * (maxPoints - 1)).toInt().coerceIn(0, maxPoints - 1)
                                    detectDragGestures(
                                        onDragStart = { o -> selectedIdx = idx(o.x) },
                                        onDrag = { change, _ -> selectedIdx = idx(change.position.x) }
                                    )
                                }
                        ) {
                            val padY = 10f
                            val chartH = (size.height - padY * 2).coerceAtLeast(10f)

                            fun x(i: Int, count: Int) = size.width * i / (count - 1).coerceAtLeast(1)

                            // Mřížka (5 úrovní zarovnaných s osou Y)
                            for (step in 0..4) {
                                val gy = padY + chartH * (step / 4f)
                                drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.2f)
                            }

                            // Vykreslení každé viditelné řady se zářícím halo efektem
                            for (s in visibleSeries) {
                                val scale = seriesScales[s.id] ?: SeriesScale(yTicksLo, yTicksHi, yTicksSpan)
                                fun yVal(v: Double) = padY + (chartH * (1.0 - (v - scale.lo) / scale.span)).toFloat()

                                val pts = mutableListOf<Pair<Int, Double>>()
                                s.values.forEachIndexed { idx, v -> if (v != null && v.isFinite()) pts.add(idx to v) }
                                if (pts.isEmpty()) continue
                                val count = s.values.size.coerceAtLeast(maxPoints)
                                val style = seriesStyles[s.id]
                                val marker = style?.marker ?: SeriesMarker.CIRCLE
                                if (pts.size == 1) {
                                    drawSeriesMarker(marker, s.color, Offset(x(pts[0].first, count), yVal(pts[0].second)), 6f)
                                    continue
                                }

                                val strokePath = Path()
                                val fillPath = Path()
                                val fx = x(pts[0].first, count)
                                val fy = yVal(pts[0].second)
                                strokePath.moveTo(fx, fy)
                                fillPath.moveTo(fx, size.height)
                                fillPath.lineTo(fx, fy)

                                for (i in 1 until pts.size) {
                                    val px = x(pts[i - 1].first, count)
                                    val py = yVal(pts[i - 1].second)
                                    val cx = x(pts[i].first, count)
                                    val cy = yVal(pts[i].second)
                                    val midX = px + (cx - px) * 0.5f
                                    strokePath.cubicTo(midX, py, midX, cy, cx, cy)
                                    fillPath.cubicTo(midX, py, midX, cy, cx, cy)
                                }
                                val lx = x(pts.last().first, count)
                                fillPath.lineTo(lx, size.height)
                                fillPath.close()

                                drawPath(
                                    fillPath,
                                    brush = Brush.verticalGradient(
                                        colors = listOf(s.color.copy(alpha = if (visibleSeries.size == 1) 0.34f else 0.18f), Color.Transparent),
                                        startY = padY,
                                        endY = size.height
                                    )
                                )
                                // Vnější jemná záře křivky
                                drawPath(
                                    strokePath,
                                    color = s.color.copy(alpha = 0.24f),
                                    style = Stroke(width = 11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                                // Ostré jádro křivky
                                drawPath(
                                    strokePath,
                                    color = s.color,
                                    style = Stroke(
                                        width = 4.5f,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                        pathEffect = style?.lineStyle?.pathEffect()
                                    )
                                )
                                if (showMeasuredPoints && pts.size <= 100) {
                                    val dense = pts.size > 45
                                    val ringR = if (dense) 5.0f else 6.6f
                                    val dotR = if (dense) 3.6f else 4.8f
                                    val coreR = if (dense) 1.3f else 1.9f
                                    for (pt in pts) {
                                        val ptCenter = Offset(x(pt.first, count), yVal(pt.second))
                                        drawCircle(Color(0xFF070C15), radius = ringR, center = ptCenter)
                                        drawSeriesMarker(marker, s.color, ptCenter, dotR)
                                        drawCircle(Color.White.copy(alpha = 0.92f), radius = coreR, center = ptCenter)
                                    }
                                }
                            }

                            // Kurzor s body pro každou křivku
                            selectedIdx?.let { idx ->
                                val cx = x(idx, maxPoints)
                                drawLine(cursorColor.copy(alpha = 0.75f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2f)
                                for (s in visibleSeries) {
                                    val scale = seriesScales[s.id] ?: SeriesScale(yTicksLo, yTicksHi, yTicksSpan)
                                    s.values.getOrNull(idx)?.let { v ->
                                        val cy = padY + (chartH * (1.0 - (v - scale.lo) / scale.span)).toFloat()
                                        drawCircle(s.color.copy(alpha = 0.38f), radius = 12f, center = Offset(cx, cy))
                                        drawCircle(s.color, radius = 7f, center = Offset(cx, cy))
                                        drawCircle(Color.White, radius = 3.5f, center = Offset(cx, cy))
                                    }
                                }
                            }
                        }
                    }
                    if (xLabels.size >= 2) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 52.dp, top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(xLabels.first(), style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1), maxLines = 1, softWrap = false)
                            if (xLabels.size >= 3) {
                                Text(xLabels[xLabels.size / 2], style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1), maxLines = 1, softWrap = false)
                            }
                            Text(xLabels.last(), style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1), maxLines = 1, softWrap = false)
                        }
                    }
                }
    }
}

/** Legend swatch: short line in the series' color + line style with its point marker. */
@Composable
private fun SeriesLegendSample(style: SeriesStyle?, color: Color) {
    Canvas(Modifier.size(width = 20.dp, height = 10.dp)) {
        val y = size.height / 2f
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 2.5.dp.toPx(),
            cap = if (style?.lineStyle == SeriesLineStyle.DOTTED) StrokeCap.Round else StrokeCap.Butt,
            pathEffect = style?.lineStyle?.pathEffect(scale = 0.45f)
        )
        drawSeriesMarker(style?.marker ?: SeriesMarker.CIRCLE, color, Offset(size.width / 2f, y), 3.dp.toPx())
    }
}

private class ChartSeries(val indices: IntArray, val values: DoubleArray) {
    val size: Int get() = indices.size
}

/**
 * Moderní plynulý graf zdravotních metrik s kubickými Bézier křivkami, fotografickým a procedurálním
 * pozadím, osou Y s jednotkami, osou X s časem a automatickým přiblížením na změnu hodnot.
 */
@Composable
fun LineChart(
    values: List<Double?>,
    modifier: Modifier = Modifier,
    cursor: Int? = null,
    range: IntRange? = null,
    selectMode: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary,
    band: ClosedFloatingPointRange<Double>? = null,
    unit: String = "",
    xLabels: List<String> = emptyList(),
    decimals: Int? = null,
    isScatter: Boolean = false,
    scatterColorForValue: ((Double) -> Color)? = null,
    secondaryValues: List<Double?>? = null,
    secondaryColor: Color = Color(0xFFF59E0B),
    secondaryLabel: String = "",
    channel: Channel? = null,
    artworkRes: Int? = null,
    motifKind: MetricMotifKind? = null,
    /** Custom explanation; when null one is derived from this chart's own parameters. */
    info: ChartInfo? = null,
    /** False where the surrounding card already carries its own (i) for this chart. */
    showInfo: Boolean = true,
    /** True when already nested inside a parent Card so no double border/backdrop is drawn. */
    embedded: Boolean = false,
    onCursor: (Int) -> Unit = {},
    onRange: (IntRange?) -> Unit = {},
) {
    val bandColor = color.copy(alpha = 0.16f)
    val selColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
    val cursorColor = Color(0xFFFF5252)
    val gridColor = Color.White.copy(alpha = 0.09f)
    val showMeasuredPoints = showChartPointsState
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val effectiveArtwork = remember(artworkRes, unit, secondaryLabel) {
        artworkRes ?: inferChartArtworkRes(unit, secondaryLabel)
    }
    val effectiveMotif = remember(motifKind, unit, secondaryLabel) {
        motifKind ?: inferChartMotifKind(unit, secondaryLabel)
    }

    val strokePath = remember { Path() }
    val fillPath = remember { Path() }
    val secPath = remember { Path() }

    val series = remember(values) {
        var count = 0
        for (v in values) { if (v != null && v.isFinite()) count++ }
        val idxs = IntArray(count)
        val vals = DoubleArray(count)
        var pos = 0
        values.forEachIndexed { i, v ->
            if (v != null && v.isFinite()) {
                idxs[pos] = i
                vals[pos] = v
                pos++
            }
        }
        ChartSeries(idxs, vals)
    }

    val secSeries = remember(secondaryValues) {
        if (secondaryValues == null) null
        else {
            var count = 0
            for (v in secondaryValues) { if (v != null && v.isFinite()) count++ }
            val idxs = IntArray(count)
            val vals = DoubleArray(count)
            var pos = 0
            secondaryValues.forEachIndexed { i, v ->
                if (v != null && v.isFinite()) {
                    idxs[pos] = i
                    vals[pos] = v
                    pos++
                }
            }
            ChartSeries(idxs, vals)
        }
    }

    val finite = remember(values, secondaryValues) {
        val all = if (secondaryValues != null) values + secondaryValues else values
        all.mapNotNull { it?.takeIf { d -> d.isFinite() } }
    }
    val dataLo = if (finite.isNotEmpty()) finite.min() else 0.0
    val dataHi = if (finite.isNotEmpty()) finite.max() else 100.0
    val effectiveDecimals = decimals ?: when (unit) {
        "kg", "lb" -> 2
        "%", "m/s", "h" -> 1
        "bpm", "ms", "W", "kcal", "let", "spm" -> 0
        else -> if ((dataHi - dataLo) in 0.001..3.0) 2 else 1
    }

    val minSpan = when (unit) {
        "kg", "lb" -> 0.20
        "%" -> 0.40
        else -> 0.50
    }
    val lo = if ((dataHi - dataLo) < minSpan) ((dataHi + dataLo) / 2.0 - minSpan / 2.0) else dataLo
    val hi = if ((dataHi - dataLo) < minSpan) ((dataHi + dataLo) / 2.0 + minSpan / 2.0) else dataHi
    val unitSuffix = if (unit.isNotBlank()) " $unit" else ""

    ChartCardFrame(
        embedded = embedded,
        accentColor = color,
        artworkRes = effectiveArtwork,
        motifKind = effectiveMotif,
        artworkAlpha = 0.62f,
        glowCenterXFraction = 0.85f,
        glowCenterYFraction = 0.28f,
        modifier = modifier,
    ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val activeIdx = selectedIndex ?: cursor
                    val activeVal = activeIdx?.let { values.getOrNull(it) }
                    val activeSecVal = activeIdx?.let { secondaryValues?.getOrNull(it) }
                    val activeDate = activeIdx?.let { xLabels.getOrNull(it) }
                    val firstVal = values.firstOrNull { it != null && it.isFinite() }
                    if (activeVal != null) {
                        val deltaVsStart = if (firstVal != null) activeVal - firstVal else null
                        val deltaStr = deltaVsStart?.let { d ->
                            val sign = if (d >= 0) "+" else ""
                            " ($sign${fmt.number(d, effectiveDecimals)}$unitSuffix)"
                        } ?: ""
                        Column(Modifier.weight(1f)) {
                            FlowRow(
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "${fmt.number(activeVal, effectiveDecimals)}$unitSuffix$deltaStr",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = color,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                if (activeSecVal != null && secondaryLabel.isNotBlank()) {
                                    Text(
                                        "$secondaryLabel: ${fmt.number(activeSecVal, effectiveDecimals)}$unitSuffix",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = secondaryColor,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                            if (!activeDate.isNullOrBlank()) {
                                Text(
                                    "Datum / Poloha: $activeDate",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                        }
                    } else {
                        val netDelta = if (finite.size >= 2) finite.last() - finite.first() else null
                        val netStr = netDelta?.let { d ->
                            val sign = if (d >= 0) "+" else ""
                            " · Δ $sign${fmt.number(d, effectiveDecimals)}$unitSuffix"
                        } ?: ""
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Rozpětí: ${fmt.number(dataLo, effectiveDecimals)} – ${fmt.number(dataHi, effectiveDecimals)}$unitSuffix$netStr",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                "Počet měření v grafu: ${finite.size}" + (if (isScatter) " (bodový rozptyl XY bez spojnic)" else " · klepnutím nebo tažením odečtete bod"),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                    if (showInfo) {
                        val hasBand = band != null
                        val hasSecondary = secondaryValues != null
                        val lineInfo = remember(info, hasBand, hasSecondary, secondaryLabel, isScatter) {
                            info ?: ChartInfoTexts.build(
                                ChartKey.METRIC_HISTORY_LINE,
                                options = ChartInfoOptions(
                                    hasBand = hasBand,
                                    hasSecondary = hasSecondary,
                                    secondaryLabel = secondaryLabel,
                                    isScatter = isScatter,
                                )
                            )
                        }
                        ChartInfoAction(lineInfo)
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Osa Y s jednotkou (5 horizontálních hladin)
                    if (finite.size >= 2) {
                        Column(
                            modifier = Modifier
                                .height(185.dp)
                                .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.End
                        ) {
                            val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
                            for (step in 0..4) {
                                val tickVal = hi - span * (step / 4.0)
                                Text(
                                    "${fmt.number(tickVal, effectiveDecimals)}$unitSuffix",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFCBD5E1),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .height(185.dp)
                            .padding(vertical = 6.dp)
                            .pointerInput(values.size, selectMode) {
                                if (values.size < 2) return@pointerInput
                                fun idx(x: Float) = ((x / size.width) * (values.size - 1)).toInt().coerceIn(0, values.size - 1)
                                detectTapGestures { o ->
                                    val i = idx(o.x)
                                    selectedIndex = i
                                    onCursor(i)
                                }
                            }
                            .pointerInput(values.size, selectMode) {
                                if (values.size < 2) return@pointerInput
                                var anchor = 0
                                fun idx(x: Float) = ((x / size.width) * (values.size - 1)).toInt().coerceIn(0, values.size - 1)
                                detectHorizontalDragGestures(
                                    onDragStart = { o ->
                                        anchor = idx(o.x)
                                        selectedIndex = anchor
                                        onCursor(anchor)
                                        if (selectMode) onRange(anchor..anchor)
                                    },
                                    onHorizontalDrag = { _, dragAmount ->
                                        val curIdx = selectedIndex ?: anchor
                                        val nextIdx = (curIdx + if (dragAmount > 0) 1 else if (dragAmount < 0) -1 else 0).coerceIn(0, values.size - 1)
                                        selectedIndex = nextIdx
                                        onCursor(nextIdx)
                                        if (selectMode) onRange(minOf(anchor, nextIdx)..maxOf(anchor, nextIdx))
                                    }
                                )
                            }
                    ) {
                        if (finite.size < 2) return@Canvas
                        val curLo = lo
                        val curHi = hi
                        val paddingY = 10f
                        val chartHeight = (size.height - (paddingY * 2)).coerceAtLeast(10f)
                        val span = (curHi - curLo).takeIf { it > 1e-9 } ?: 1.0

                        fun x(i: Int) = size.width * i / (values.size - 1).coerceAtLeast(1)
                        fun y(v: Double) = paddingY + (chartHeight * (1 - (v - curLo) / span)).toFloat()

                        for (step in 0..4) {
                            val gridY = paddingY + chartHeight * (step / 4f)
                            drawLine(gridColor, Offset(0f, gridY), Offset(size.width, gridY), strokeWidth = 1.2f)
                        }

                        band?.let {
                            val top = y(it.endInclusive).coerceIn(0f, size.height)
                            val bottom = y(it.start).coerceIn(0f, size.height)
                            if (bottom > top) {
                                drawRect(bandColor, Offset(0f, top), Size(size.width, (bottom - top).coerceAtLeast(4f)))
                            }
                        }

                        range?.let {
                            drawRect(selColor, Offset(x(it.first), 0f), Size(x(it.last) - x(it.first), size.height))
                        }

                        // Baseline pro Performance Condition na Y = 0.0
                        if (channel == Channel.PERFORMANCE_CONDITION && 0.0 in lo..hi) {
                            val zeroY = y(0.0)
                            drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1.8f)
                        }

                        val ptsCount = series.size
                        if (isScatter) {
                            val cRed = Color(0xFFEF4444)
                            val cOrange = Color(0xFFF59E0B)
                            val cGreen = Color(0xFF10B981)
                            val cBlue = Color(0xFF38BDF8)
                            val cPurple = Color(0xFFA855F7)
                            var lastDrawnX = -100f
                            val minDeltaX = if (ptsCount > 600) 2.5f else if (ptsCount > 250) 1.5f else 0.8f
                            for (p in 0 until ptsCount) {
                                val idx = series.indices[p]
                                val cx = x(idx)
                                if (p > 0 && p < ptsCount - 1 && kotlin.math.abs(cx - lastDrawnX) < minDeltaX) continue
                                lastDrawnX = cx

                                val v = series.values[p]
                                val ptColor = scatterColorForValue?.invoke(v) ?: when (channel) {
                                    Channel.CADENCE -> when {
                                        v < 153.0 -> cRed
                                        v < 164.0 -> cOrange
                                        v < 174.0 -> cGreen
                                        v < 184.0 -> cBlue
                                        else -> cPurple
                                    }
                                    Channel.GROUND_CONTACT_TIME -> when {
                                        v < 208.0 -> cPurple
                                        v < 241.0 -> cBlue
                                        v < 273.0 -> cGreen
                                        v < 305.0 -> cOrange
                                        else -> cRed
                                    }
                                    Channel.VERTICAL_OSCILLATION, Channel.VERTICAL_RATIO -> when {
                                        v < 6.1 -> cPurple
                                        v < 7.5 -> cBlue
                                        v < 8.7 -> cGreen
                                        v < 10.1 -> cOrange
                                        else -> cRed
                                    }
                                    Channel.PERFORMANCE_CONDITION -> when {
                                        v > 0.0 -> cGreen
                                        v == 0.0 -> cBlue
                                        else -> cRed
                                    }
                                    else -> color
                                }
                                val cy = y(v)
                                drawCircle(ptColor, radius = 3.5f, center = Offset(cx, cy))
                            }
                        } else if (ptsCount == 1) {
                            val cx = x(series.indices[0])
                            val cy = y(series.values[0])
                            drawCircle(color, radius = 6f, center = Offset(cx, cy))
                        } else if (ptsCount >= 2) {
                            strokePath.reset()
                            fillPath.reset()

                            val step = if (ptsCount > 200) maxOf(1, ptsCount / 160) else 1
                            val firstX = x(series.indices[0])
                            val firstY = y(series.values[0])
                            strokePath.moveTo(firstX, firstY)
                            fillPath.moveTo(firstX, size.height)
                            fillPath.lineTo(firstX, firstY)

                            var prevIdx = 0
                            var i = step
                            while (i < ptsCount) {
                                val prevX = x(series.indices[prevIdx])
                                val prevY = y(series.values[prevIdx])
                                val currX = x(series.indices[i])
                                val currY = y(series.values[i])
                                val midX = prevX + (currX - prevX) * 0.5f

                                strokePath.cubicTo(midX, prevY, midX, currY, currX, currY)
                                fillPath.cubicTo(midX, prevY, midX, currY, currX, currY)
                                prevIdx = i
                                i += step
                            }
                            if (prevIdx != ptsCount - 1) {
                                val prevX = x(series.indices[prevIdx])
                                val prevY = y(series.values[prevIdx])
                                val currX = x(series.indices[ptsCount - 1])
                                val currY = y(series.values[ptsCount - 1])
                                val midX = prevX + (currX - prevX) * 0.5f

                                strokePath.cubicTo(midX, prevY, midX, currY, currX, currY)
                                fillPath.cubicTo(midX, prevY, midX, currY, currX, currY)
                            }

                            val lastX = x(series.indices[ptsCount - 1])
                            fillPath.lineTo(lastX, size.height)
                            fillPath.close()

                            val gradient = Brush.verticalGradient(
                                colors = listOf(color.copy(alpha = 0.38f), color.copy(alpha = 0.03f)),
                                startY = paddingY,
                                endY = size.height
                            )
                            drawPath(fillPath, brush = gradient)

                            // Vnější záře křivky
                            drawPath(
                                strokePath,
                                color = color.copy(alpha = 0.25f),
                                style = Stroke(width = 11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                            // Ostrá hlavní křivka
                            drawPath(
                                strokePath,
                                color = color,
                                style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )

                            if (showMeasuredPoints && channel == null && ptsCount <= 120) {
                                val dense = ptsCount > 45
                                val ringR = if (dense) 5.2f else 6.8f
                                val dotR = if (dense) 3.7f else 4.8f
                                val coreR = if (dense) 1.4f else 2.0f
                                for (p in 0 until ptsCount) {
                                    val ptCenter = Offset(x(series.indices[p]), y(series.values[p]))
                                    drawCircle(Color(0xFF070C15), radius = ringR, center = ptCenter)
                                    drawCircle(color, radius = dotR, center = ptCenter)
                                    drawCircle(Color.White.copy(alpha = 0.92f), radius = coreR, center = ptCenter)
                                }
                            }
                        }

                        // Vykreslení sekundární křivky (např. Potenciální výdrž u Staminy)
                        secSeries?.let { sec ->
                            val secCount = sec.size
                            if (secCount >= 2) {
                                secPath.reset()
                                val step = if (secCount > 200) maxOf(1, secCount / 160) else 1
                                secPath.moveTo(x(sec.indices[0]), y(sec.values[0]))
                                var prevIdx = 0
                                var i = step
                                while (i < secCount) {
                                    val prevX = x(sec.indices[prevIdx])
                                    val prevY = y(sec.values[prevIdx])
                                    val currX = x(sec.indices[i])
                                    val currY = y(sec.values[i])
                                    val midX = prevX + (currX - prevX) * 0.5f
                                    secPath.cubicTo(midX, prevY, midX, currY, currX, currY)
                                    prevIdx = i
                                    i += step
                                }
                                if (prevIdx != secCount - 1) {
                                    val prevX = x(sec.indices[prevIdx])
                                    val prevY = y(sec.values[prevIdx])
                                    val currX = x(sec.indices[secCount - 1])
                                    val currY = y(sec.values[secCount - 1])
                                    val midX = prevX + (currX - prevX) * 0.5f
                                    secPath.cubicTo(midX, prevY, midX, currY, currX, currY)
                                }
                                drawPath(
                                    secPath,
                                    color = secondaryColor,
                                    style = Stroke(width = 2.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                            }
                        }

                        val active = selectedIndex ?: cursor
                        active?.let { i ->
                            val cx = x(i)
                            drawLine(cursorColor, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2.2f)
                            values.getOrNull(i)?.let { valY ->
                                drawCircle(cursorColor.copy(alpha = 0.35f), radius = 12f, center = Offset(cx, y(valY)))
                                drawCircle(cursorColor, radius = 7f, center = Offset(cx, y(valY)))
                                drawCircle(Color.White, radius = 3.5f, center = Offset(cx, y(valY)))
                            }
                            secondaryValues?.getOrNull(i)?.let { valSecY ->
                                drawCircle(secondaryColor, radius = 6f, center = Offset(cx, y(valSecY)))
                                drawCircle(Color.White, radius = 2.8f, center = Offset(cx, y(valSecY)))
                            }
                        }
                    }
                }

                if (xLabels.size >= 2) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 52.dp, top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(xLabels.first(), style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1), maxLines = 1, softWrap = false)
                        if (xLabels.size >= 3) {
                            Text(xLabels[xLabels.size / 2], style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1), maxLines = 1, softWrap = false)
                        }
                        Text(xLabels.last(), style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1), maxLines = 1, softWrap = false)
                    }
                }

                // Garmin barevná stupnice / legenda pro běžeckou dynamiku a Staminu
                channel?.let { ch ->
                    if (isScatter) {
                        GarminZoneLegend(ch, Modifier.padding(start = 52.dp, top = 4.dp))
                    } else if (ch == Channel.STAMINA && secondaryValues != null) {
                        FlowRow(
                            Modifier.padding(start = 52.dp, top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF0EA5E9)))
                                Spacer(Modifier.width(6.dp))
                                Text("Okamžitá výdrž (Aktuální)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0EA5E9), fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, softWrap = false)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFF59E0B)))
                                Spacer(Modifier.width(6.dp))
                                Text("Potenciální výdrž (Strop)", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
    }
}

/**
 * Barevná zónová legenda ve stylu Garmin Connect pro metriky běžecké dynamiky.
 */
@Composable
fun GarminZoneLegend(channel: Channel, modifier: Modifier = Modifier) {
    val items = when (channel) {
        Channel.CADENCE -> listOf(
            "< 153" to Color(0xFFEF4444),
            "153–163" to Color(0xFFF59E0B),
            "164–173" to Color(0xFF10B981),
            "174–183" to Color(0xFF38BDF8),
            "> 183 spm" to Color(0xFFA855F7),
        )
        Channel.GROUND_CONTACT_TIME -> listOf(
            "< 208 ms" to Color(0xFFA855F7),
            "208–240" to Color(0xFF38BDF8),
            "241–272" to Color(0xFF10B981),
            "273–305" to Color(0xFFF59E0B),
            "> 305 ms" to Color(0xFFEF4444),
        )
        Channel.VERTICAL_OSCILLATION -> listOf(
            "< 6,1 cm" to Color(0xFFA855F7),
            "6,1–7,4" to Color(0xFF38BDF8),
            "7,5–8,6" to Color(0xFF10B981),
            "8,7–10,1" to Color(0xFFF59E0B),
            "> 10,1 cm" to Color(0xFFEF4444),
        )
        Channel.VERTICAL_RATIO -> listOf(
            "< 6,1 %" to Color(0xFFA855F7),
            "6,1–7,4" to Color(0xFF38BDF8),
            "7,5–8,6" to Color(0xFF10B981),
            "8,7–10,0" to Color(0xFFF59E0B),
            "> 10,0 %" to Color(0xFFEF4444),
        )
        Channel.PERFORMANCE_CONDITION -> listOf(
            "Únava (-5..-1)" to Color(0xFFEF4444),
            "Výchozí (0)" to Color(0xFF38BDF8),
            "Dobrý (+1..+3)" to Color(0xFF10B981),
            "Výborný (+4..+5)" to Color(0xFFA855F7),
        )
        else -> return
    }

    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { (_, col) ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(col)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            items.forEach { (lbl, _) ->
                Text(lbl, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), fontSize = 9.sp)
            }
        }
    }
}

/**
 * Speciální skládaný plošný graf (Stacked Area Chart) pro Váhu v kg (nebo %) se složením Svalů a Tuku uvnitř plochy pod křivkou váhy.
 */
@Composable
fun WeightCompositionStackedAreaChart(
    weightValues: List<Double?>,
    muscleValues: List<Double?>,
    fatValues: List<Double?>,
    unit: String = "kg",
    band: ClosedFloatingPointRange<Double>? = null,
    xLabels: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val weightColor = MetricPalette.Weight
    val muscleColor = MetricPalette.Muscle
    val fatColor = MetricPalette.Fat
    val bandColor = weightColor.copy(alpha = 0.14f)
    val cursorColor = Color(0xFFFFFFFF)

    var selectedIndex by remember(weightValues) { mutableStateOf<Int?>(null) }
    var absoluteZeroScale by remember { mutableStateOf(false) }
    val showMeasuredPoints = showChartPointsState

    val finiteWeights = weightValues.filterNotNull().filter { it.isFinite() }
    val rawMinW = finiteWeights.minOrNull() ?: 0.0
    val rawMaxW = finiteWeights.maxOrNull() ?: 100.0
    val rawSpanW = (rawMaxW - rawMinW).coerceAtLeast(0.0)
    val minSensibleSpan = if (unit == "kg" || unit == "lb") 0.80 else 2.0
    val halfPad = maxOf(rawSpanW * 0.20, minSensibleSpan / 2.0)

    val minY = if (absoluteZeroScale) {
        0.0
    } else {
        rawMinW - halfPad
    }
    val maxY = if (absoluteZeroScale) {
        (rawMaxW * 1.06).coerceAtLeast(10.0)
    } else {
        rawMaxW + halfPad
    }
    val span = (maxY - minY).takeIf { it > 1e-6 } ?: 1.0

    val decimals = when {
        absoluteZeroScale -> 0
        unit == "kg" || unit == "lb" -> if (span < 4.0) 2 else 1
        else -> 1
    }
    fun fmtVal(v: Double): String = String.format(java.util.Locale.getDefault(), "%.${decimals}f", v)
    fun fmtDetail(v: Double): String = String.format(java.util.Locale.getDefault(), "%.2f", v)

    val latestIdx = weightValues.indexOfLast { it != null && it.isFinite() }.takeIf { it >= 0 }
    val activeIdx = selectedIndex ?: latestIdx
    val curW = activeIdx?.let { weightValues.getOrNull(it) }
    val curM = activeIdx?.let { muscleValues.getOrNull(it) } ?: muscleValues.lastOrNull { it != null && it.isFinite() }
    val curF = activeIdx?.let { fatValues.getOrNull(it) } ?: fatValues.lastOrNull { it != null && it.isFinite() }
    val curDate = activeIdx?.let { xLabels.getOrNull(it) }

    val prevIdx = activeIdx?.let { cur ->
        (cur - 1 downTo 0).firstOrNull { weightValues.getOrNull(it)?.isFinite() == true }
    }
    val prevW = prevIdx?.let { weightValues.getOrNull(it) }
    val prevM = prevIdx?.let { muscleValues.getOrNull(it) }
    val prevF = prevIdx?.let { fatValues.getOrNull(it) }
    val diffW = if (curW != null && prevW != null) curW - prevW else null
    val diffM = if (curM != null && prevM != null) curM - prevM else null
    val diffF = if (curF != null && prevF != null) curF - prevF else null

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MineralOutlineDark),
        colors = CardDefaults.cardColors(containerColor = MineralSurfaceDark)
    ) {
        Column(Modifier.padding(12.dp)) {
            // 1. Horní lišta: Titulek nalevo, přepínač měřítka napravo (FlowRow: na úzkém displeji se přepínač zalomí)
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (curDate != null) "Z ČEHO SE SKLÁDÁ VÁHA ($curDate)" else "Z ČEHO SE SKLÁDÁ VÁHA",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1
                    )
                    val compositionInfo = remember {
                        ChartInfoTexts.build(ChartKey.WEIGHT_COMPOSITION_STACKED_AREA)
                    }
                    ChartInfoAction(compositionInfo)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MineralBackgroundDark)
                        .clickable { absoluteZeroScale = !absoluteZeroScale }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (absoluteZeroScale) "Od nuly (0–${fmtVal(maxY)} $unit)" else "Detail změn",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = weightColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // 2. Legenda s hodnotami a přírůstky na plnou šířku karty pomocí FlowRow
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (curW != null) {
                    val dStr = diffW?.let { " (${formatDeltaMetric(it, unit)})" } ?: ""
                    Text(
                        "● Váha ${fmtDetail(curW)} $unit$dStr",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = weightColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                if (curM != null) {
                    val dStr = diffM?.let { " (${formatDeltaMetric(it, unit)})" } ?: ""
                    Text(
                        "■ Svaly ${fmtDetail(curM)} $unit$dStr",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = muscleColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                if (curF != null) {
                    val dStr = diffF?.let { " (${formatDeltaMetric(it, unit)})" } ?: ""
                    Text(
                        "■ Tuk ${fmtDetail(curF)} $unit$dStr",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = fatColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Levá osa Y (5 hladin s rozlišením 200g/250g)
                Column(
                    modifier = Modifier
                        .width(58.dp)
                        .height(210.dp)
                        .padding(end = 6.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    for (step in 0..4) {
                        val tickVal = maxY - span * (step / 4.0)
                        val isKey = step == 0 || step == 4
                        Text(
                            "${fmtVal(tickVal)} $unit",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isKey) Color(0xFF94A3B8) else Color(0xFF64748B),
                            fontSize = 9.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // Plátno se skládanou plochou (Stacked Area: Svaly dole -> Tuk uprostřed -> Celková váha nahoře)
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(210.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MineralBackgroundDark)
                        .pointerInput(weightValues) {
                            fun nearestMeasuredIdx(rawIdx: Int): Int =
                                weightValues.indices
                                    .filter { weightValues[it]?.isFinite() == true }
                                    .minByOrNull { kotlin.math.abs(it - rawIdx) }
                                    ?: rawIdx
                            detectTapGestures { offset ->
                                if (weightValues.isNotEmpty() && size.width > 0) {
                                    val raw = ((offset.x / size.width) * (weightValues.size - 1)).toInt()
                                        .coerceIn(0, weightValues.lastIndex)
                                    val idx = nearestMeasuredIdx(raw)
                                    selectedIndex = if (selectedIndex == idx) null else idx
                                }
                            }
                        }
                        .pointerInput(weightValues) {
                            fun nearestMeasuredIdx(rawIdx: Int): Int =
                                weightValues.indices
                                    .filter { weightValues[it]?.isFinite() == true }
                                    .minByOrNull { kotlin.math.abs(it - rawIdx) }
                                    ?: rawIdx
                            detectDragGestures(
                                onDragEnd = { },
                                onDrag = { change, _ ->
                                    if (weightValues.isNotEmpty() && size.width > 0) {
                                        val raw = ((change.position.x / size.width) * (weightValues.size - 1)).toInt()
                                            .coerceIn(0, weightValues.lastIndex)
                                        selectedIndex = nearestMeasuredIdx(raw)
                                    }
                                }
                            )
                        }
                ) {
                    if (weightValues.isEmpty() || finiteWeights.isEmpty()) return@Canvas

                    val paddingY = 16f
                    val usableH = (size.height - paddingY * 2f).coerceAtLeast(1f)
                    val bottomY = size.height - 4f

                    fun x(i: Int): Float =
                        if (weightValues.size <= 1) size.width / 2f
                        else i.toFloat() / (weightValues.size - 1).coerceAtLeast(1) * size.width

                    fun y(v: Double): Float =
                        paddingY + (1f - ((v - minY) / span).toFloat().coerceIn(0f, 1f)) * usableH

                    // Vodorovná mřížka (5 úrovní odpovídajících popiskům osy Y)
                    for (step in 0..4) {
                        val gy = paddingY + usableH * (step / 4f)
                        drawLine(
                            color = MineralOutlineDark.copy(alpha = 0.55f),
                            start = Offset(0f, gy),
                            end = Offset(size.width, gy),
                            strokeWidth = 1f
                        )
                    }

                    if (!absoluteZeroScale && band != null && band.start.isFinite() && band.endInclusive.isFinite()) {
                        val top = y(band.endInclusive)
                        val bottom = y(band.start)
                        drawRect(bandColor, Offset(0f, top), Size(size.width, (bottom - top).coerceAtLeast(2f)))
                    }

                    val fallbackM = muscleValues.lastOrNull { it != null && it.isFinite() } ?: (rawMaxW * 0.72)
                    val fallbackF = fatValues.lastOrNull { it != null && it.isFinite() } ?: (rawMaxW * 0.23)

                    data class StackedPoint(
                        val idx: Int,
                        val xPos: Float,
                        val weightY: Float,
                        val fatTopY: Float,
                        val muscleTopY: Float,
                    )

                    val pts = mutableListOf<StackedPoint>()
                    weightValues.forEachIndexed { i, wVal ->
                        if (wVal != null && wVal.isFinite() && wVal > 0.0) {
                            val mVal = (muscleValues.getOrNull(i)?.takeIf { it.isFinite() && it > 0.0 } ?: fallbackM)
                                .coerceAtMost(wVal * 0.92)
                            val fVal = (fatValues.getOrNull(i)?.takeIf { it.isFinite() && it > 0.0 } ?: fallbackF)
                                .coerceAtMost((wVal - mVal).coerceAtLeast(0.0))

                            val wy = y(wVal)
                            val (mTopY, fTopY) = if (absoluteZeroScale) {
                                // Absolutní skládaná plocha na ose Y (0 -> Svaly -> Svaly+Tuk -> Váha)
                                y(mVal) to y(mVal + fVal)
                            } else {
                                // V detailním režimu rozdělíme plochu pod křivkou váhy (od wy po bottomY)
                                // přesně v poměru Svaly : Tuk : Ostatní, aby byl vidět jak detailní pohyb váhy,
                                // tak skládané vrstvy svalů a tuku pod ní.
                                val areaH = (bottomY - wy).coerceAtLeast(18f)
                                val mFrac = (mVal / wVal).toFloat().coerceIn(0.15f, 0.88f)
                                val fFrac = (fVal / wVal).toFloat().coerceIn(0.05f, 0.95f - mFrac)
                                val muscleY = bottomY - areaH * mFrac
                                val fatY = muscleY - areaH * fFrac
                                muscleY to fatY
                            }
                            pts.add(StackedPoint(i, x(i), wy, fTopY, mTopY))
                        }
                    }

                    if (pts.size == 1) {
                        val p = pts[0]
                        val barW = (size.width * 0.35f).coerceAtLeast(40f)
                        val leftX = (p.xPos - barW / 2f).coerceAtLeast(0f)
                        // Svaly (modrá)
                        drawRect(
                            color = muscleColor.copy(alpha = 0.42f),
                            topLeft = Offset(leftX, p.muscleTopY),
                            size = Size(barW, (bottomY - p.muscleTopY).coerceAtLeast(2f))
                        )
                        // Tuk (oranžová)
                        drawRect(
                            color = fatColor.copy(alpha = 0.42f),
                            topLeft = Offset(leftX, p.fatTopY),
                            size = Size(barW, (p.muscleTopY - p.fatTopY).coerceAtLeast(2f))
                        )
                        drawCircle(weightColor, radius = 6f, center = Offset(p.xPos, p.weightY))
                    } else if (pts.size >= 2) {
                        // 1. Skládaná vrstva: SVALY (od spodu grafu po muscleTopY)
                        val muscleFill = Path().apply {
                            moveTo(pts.first().xPos, bottomY)
                            lineTo(pts.first().xPos, pts.first().muscleTopY)
                            for (i in 1 until pts.size) {
                                val prev = pts[i - 1]
                                val curr = pts[i]
                                val midX = (prev.xPos + curr.xPos) * 0.5f
                                cubicTo(midX, prev.muscleTopY, midX, curr.muscleTopY, curr.xPos, curr.muscleTopY)
                            }
                            lineTo(pts.last().xPos, bottomY)
                            close()
                        }
                        drawPath(
                            muscleFill,
                            brush = Brush.verticalGradient(
                                colors = listOf(muscleColor.copy(alpha = 0.48f), muscleColor.copy(alpha = 0.18f)),
                                startY = pts.minOf { it.muscleTopY },
                                endY = bottomY
                            )
                        )

                        // 2. Skládaná vrstva: TUK (od muscleTopY nahoru po fatTopY)
                        val fatFill = Path().apply {
                            moveTo(pts.first().xPos, pts.first().muscleTopY)
                            lineTo(pts.first().xPos, pts.first().fatTopY)
                            for (i in 1 until pts.size) {
                                val prev = pts[i - 1]
                                val curr = pts[i]
                                val midX = (prev.xPos + curr.xPos) * 0.5f
                                cubicTo(midX, prev.fatTopY, midX, curr.fatTopY, curr.xPos, curr.fatTopY)
                            }
                            lineTo(pts.last().xPos, pts.last().muscleTopY)
                            for (i in pts.lastIndex downTo 1) {
                                val curr = pts[i]
                                val prev = pts[i - 1]
                                val midX = (prev.xPos + curr.xPos) * 0.5f
                                cubicTo(midX, curr.muscleTopY, midX, prev.muscleTopY, prev.xPos, prev.muscleTopY)
                            }
                            close()
                        }
                        drawPath(
                            fatFill,
                            brush = Brush.verticalGradient(
                                colors = listOf(fatColor.copy(alpha = 0.50f), fatColor.copy(alpha = 0.24f)),
                                startY = pts.minOf { it.fatTopY },
                                endY = pts.maxOf { it.muscleTopY }
                            )
                        )

                        // 3. Zbývající pás pod celkovou váhou (Kosti + Ostatní)
                        val weightCapFill = Path().apply {
                            moveTo(pts.first().xPos, pts.first().fatTopY)
                            lineTo(pts.first().xPos, pts.first().weightY)
                            for (i in 1 until pts.size) {
                                val prev = pts[i - 1]
                                val curr = pts[i]
                                val midX = (prev.xPos + curr.xPos) * 0.5f
                                cubicTo(midX, prev.weightY, midX, curr.weightY, curr.xPos, curr.weightY)
                            }
                            lineTo(pts.last().xPos, pts.last().fatTopY)
                            for (i in pts.lastIndex downTo 1) {
                                val curr = pts[i]
                                val prev = pts[i - 1]
                                val midX = (prev.xPos + curr.xPos) * 0.5f
                                cubicTo(midX, curr.fatTopY, midX, prev.fatTopY, prev.xPos, prev.fatTopY)
                            }
                            close()
                        }
                        drawPath(weightCapFill, color = weightColor.copy(alpha = 0.22f))

                        // Hraniční křivky pro Svaly, Tuk a Celkovou váhu
                        val muscleStroke = Path().apply {
                            moveTo(pts.first().xPos, pts.first().muscleTopY)
                            for (i in 1 until pts.size) {
                                val prev = pts[i - 1]
                                val curr = pts[i]
                                val midX = (prev.xPos + curr.xPos) * 0.5f
                                cubicTo(midX, prev.muscleTopY, midX, curr.muscleTopY, curr.xPos, curr.muscleTopY)
                            }
                        }
                        drawPath(
                            muscleStroke,
                            color = muscleColor,
                            style = Stroke(width = 3.0f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        val fatStroke = Path().apply {
                            moveTo(pts.first().xPos, pts.first().fatTopY)
                            for (i in 1 until pts.size) {
                                val prev = pts[i - 1]
                                val curr = pts[i]
                                val midX = (prev.xPos + curr.xPos) * 0.5f
                                cubicTo(midX, prev.fatTopY, midX, curr.fatTopY, curr.xPos, curr.fatTopY)
                            }
                        }
                        // Fat boundary is dashed so it stays distinguishable from muscle even without color.
                        drawPath(
                            fatStroke,
                            color = fatColor,
                            style = Stroke(
                                width = 3.0f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f))
                            )
                        )

                        val weightStroke = Path().apply {
                            moveTo(pts.first().xPos, pts.first().weightY)
                            for (i in 1 until pts.size) {
                                val prev = pts[i - 1]
                                val curr = pts[i]
                                val midX = (prev.xPos + curr.xPos) * 0.5f
                                cubicTo(midX, prev.weightY, midX, curr.weightY, curr.xPos, curr.weightY)
                            }
                        }
                        drawPath(
                            weightStroke,
                            color = weightColor,
                            style = Stroke(width = 4.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        if (showMeasuredPoints && pts.size <= 100) {
                            val dense = pts.size > 45
                            val wRing = if (dense) 5.0f else 6.5f
                            val wDot = if (dense) 3.6f else 4.7f
                            val wCore = if (dense) 1.4f else 2.0f
                            val sRing = if (dense) 4.4f else 5.5f
                            val sDot = if (dense) 3.1f else 3.9f
                            val sCore = if (dense) 1.2f else 1.5f
                            for (pt in pts) {
                                val wCenter = Offset(pt.xPos, pt.weightY)
                                drawCircle(Color(0xFF070C15), radius = wRing, center = wCenter)
                                drawCircle(weightColor, radius = wDot, center = wCenter)
                                drawCircle(Color.White.copy(alpha = 0.95f), radius = wCore, center = wCenter)
                                if (fatValues.getOrNull(pt.idx)?.isFinite() == true) {
                                    val fCenter = Offset(pt.xPos, pt.fatTopY)
                                    drawCircle(Color(0xFF070C15), radius = sRing, center = fCenter)
                                    drawCircle(fatColor, radius = sDot, center = fCenter)
                                    drawCircle(Color.White.copy(alpha = 0.90f), radius = sCore, center = fCenter)
                                }
                                if (muscleValues.getOrNull(pt.idx)?.isFinite() == true) {
                                    val mCenter = Offset(pt.xPos, pt.muscleTopY)
                                    drawCircle(Color(0xFF070C15), radius = sRing, center = mCenter)
                                    drawCircle(muscleColor, radius = sDot, center = mCenter)
                                    drawCircle(Color.White.copy(alpha = 0.90f), radius = sCore, center = mCenter)
                                }
                            }
                        }
                    }

                    activeIdx?.let { selI ->
                        pts.find { it.idx == selI }?.let { pt ->
                            drawLine(cursorColor.copy(alpha = 0.75f), Offset(pt.xPos, 0f), Offset(pt.xPos, size.height), strokeWidth = 2f)
                            drawCircle(weightColor, radius = 6.5f, center = Offset(pt.xPos, pt.weightY))
                            drawCircle(Color.White, radius = 3.0f, center = Offset(pt.xPos, pt.weightY))
                        }
                    }
                }
            }

            if (xLabels.size >= 2) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 54.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(xLabels.first(), style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), maxLines = 1, softWrap = false)
                    if (xLabels.size >= 3) {
                        Text(xLabels[xLabels.size / 2], style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), maxLines = 1, softWrap = false)
                    }
                    Text(xLabels.last(), style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Trasa bez mapového podkladu (funguje offline a bez licence dlaždic). Chybějící GPS body se přeskakují. */
@Composable
fun RouteCanvas(
    points: List<Pair<Double, Double>?>,
    modifier: Modifier = Modifier,
    cursor: Int? = null,
    range: IntRange? = null,
) {
    val base = Color(0xFF38BDF8)
    val sel = Color(0xFFF59E0B)
    val cur = Color(0xFFFF5252)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MineralBackgroundDark)
    ) {
        val pts = points.filterNotNull()
        if (pts.size < 2) return@Canvas
        val minLat = pts.minOf { it.first }; val maxLat = pts.maxOf { it.first }
        val minLon = pts.minOf { it.second }; val maxLon = pts.maxOf { it.second }
        val cosLat = Math.cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(0.01)
        val w = ((maxLon - minLon) * cosLat).coerceAtLeast(1e-9); val h = (maxLat - minLat).coerceAtLeast(1e-9)
        val scale = minOf(size.width / w, size.height / h) * 0.86
        fun p(c: Pair<Double, Double>) = Offset(
            ((size.width - w * scale) / 2 + (c.second - minLon) * cosLat * scale).toFloat(),
            ((size.height + h * scale) / 2 - (c.first - minLat) * scale).toFloat(),
        )
        var prev: Offset? = null
        points.forEachIndexed { i, c ->
            if (c == null) { prev = null; return@forEachIndexed }
            val o = p(c)
            prev?.let { drawLine(if (range != null && i in range) sel else base, it, o, if (range != null && i in range) 7f else 4.5f, cap = StrokeCap.Round) }
            prev = o
        }
        // Start & End markers
        drawCircle(Color(0xFF10B981), 7f, p(pts.first()))
        drawCircle(Color(0xFFFF5252), 7f, p(pts.last()))
        cursor?.let { i -> points.getOrNull(i)?.let { drawCircle(cur, 9f, p(it)) } }
    }
}

/** Překryvné zobrazení více GPS tras pro srovnání aktivit (Compare). */
@Composable
fun MultiRouteCanvas(
    routes: List<Pair<Color, List<Pair<Double, Double>?>>>,
    focusedRouteIndex: Int? = null,
    modifier: Modifier = Modifier,
) {
    val allPts = remember(routes, focusedRouteIndex) {
        if (focusedRouteIndex != null && focusedRouteIndex in routes.indices) {
            routes[focusedRouteIndex].second.filterNotNull()
        } else {
            routes.flatMap { it.second.filterNotNull() }
        }
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MineralBackgroundDark)
    ) {
        if (allPts.size < 2) return@Canvas
        val minLat = allPts.minOf { it.first }; val maxLat = allPts.maxOf { it.first }
        val minLon = allPts.minOf { it.second }; val maxLon = allPts.maxOf { it.second }
        val cosLat = Math.cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(0.01)
        val w = ((maxLon - minLon) * cosLat).coerceAtLeast(1e-9); val h = (maxLat - minLat).coerceAtLeast(1e-9)
        val scale = minOf(size.width / w, size.height / h) * 0.86
        fun p(c: Pair<Double, Double>) = Offset(
            ((size.width - w * scale) / 2 + (c.second - minLon) * cosLat * scale).toFloat(),
            ((size.height + h * scale) / 2 - (c.first - minLat) * scale).toFloat(),
        )
        routes.forEachIndexed { idx, (color, pts) ->
            val isFocused = (focusedRouteIndex == null || focusedRouteIndex == idx)
            val strokeWidth = if (isFocused) 5.0f else 2.6f
            val lineColor = if (isFocused) color else color.copy(alpha = 0.35f)

            // 1. Průchod: Tmavé kontrastní halo
            var prev: Offset? = null
            pts.forEach { c ->
                if (c == null) { prev = null; return@forEach }
                val o = p(c)
                prev?.let { drawLine(Color(0xFF0F172A), it, o, strokeWidth + 3.2f, cap = StrokeCap.Round) }
                prev = o
            }

            // 2. Průchod: Barevná linie trasy
            prev = null
            pts.forEach { c ->
                if (c == null) { prev = null; return@forEach }
                val o = p(c)
                prev?.let { drawLine(lineColor, it, o, strokeWidth, cap = StrokeCap.Round) }
                prev = o
            }

            // 3. Start a cíl piny
            val valid = pts.filterNotNull()
            if (valid.size >= 2) {
                val startOffset = p(valid.first())
                val endOffset = p(valid.last())
                drawCircle(Color.White, radius = 7f, center = startOffset)
                drawCircle(lineColor, radius = 5.2f, center = startOffset)
                drawCircle(lineColor, radius = 7f, center = endOffset)
                drawCircle(Color(0xFF0F172A), radius = 5.2f, center = endOffset)
            }
        }
    }
}

@Composable
fun SectionTitle(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    modifier: Modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE2E8F0)
        )
    }
}

@Composable
fun LabeledRow(label: String, value: String) = Row(
    Modifier.fillMaxWidth().padding(vertical = 3.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFF94A3B8),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
    )
    Text(
        value,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(start = 8.dp)
    )
}
