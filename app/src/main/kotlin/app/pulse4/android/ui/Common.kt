@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pulse4.domain.Reading
import app.pulse4.provider.AreaStatus
import app.pulse4.sync.DeliveryState
import app.pulse4.ui.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Načtení z databáze mimo hlavní vlákno; `key` vyvolá nové načtení. */
@Composable
fun <T> io(key: Any?, block: () -> T): State<T?> = produceState<T?>(null, key) { value = withContext(Dispatchers.IO) { block() } }

/** Formátování reagující na zvolenou soustavu jednotek (Metrické / Imperiální). */
@Volatile
var activeFmt: Fmt = Fmt.default

val fmt: Fmt get() = activeFmt

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
    icon: String,
    reading: Reading<String>,
    historyValues: List<Double?>,
    accentColor: Color,
    windowLabel: String,
    deltaLabel: String? = null,
    statusLabel: String? = null,
    valueColor: Color = Color.White,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val finite = remember(historyValues) { historyValues.filterNotNull() }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.28f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121620))
    ) {
        Box(Modifier.fillMaxWidth()) {
            // Graf na pozadí (Grafana Singlestat sparkline + area fill)
            if (finite.size >= 2) {
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                ) {
                    val lo = finite.minOrNull() ?: 0.0
                    val hi = finite.maxOrNull() ?: 100.0
                    val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
                    val topPad = size.height * 0.32f
                    val botPad = 6f
                    val usableH = (size.height - topPad - botPad).coerceAtLeast(10f)

                    fun x(i: Int) = size.width * i / (historyValues.size - 1).coerceAtLeast(1)
                    fun y(v: Double) = topPad + (usableH * (1.0 - (v - lo) / span)).toFloat()

                    val pts = mutableListOf<Pair<Int, Double>>()
                    historyValues.forEachIndexed { idx, v -> if (v != null) pts.add(idx to v) }
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
                        fillPath.lineTo(lastX, size.height)
                        fillPath.close()

                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(accentColor.copy(alpha = 0.24f), accentColor.copy(alpha = 0.02f)),
                                startY = topPad,
                                endY = size.height
                            )
                        )
                        drawPath(
                            path = strokePath,
                            color = accentColor.copy(alpha = 0.55f),
                            style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
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
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(accentColor.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(icon, style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE2E8F0),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Pozadí: $windowLabel historie",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF64748B),
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
                            Text(
                                badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (valueColor != Color.White) valueColor else accentColor,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .background(
                                        (if (valueColor != Color.White) valueColor else accentColor).copy(alpha = 0.14f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Text(
                            " ›",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color(0xFF64748B),
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
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = valueColor,
                                maxLines = 1,
                                softWrap = false
                            )
                            if (deltaLabel != null) {
                                Text(
                                    deltaLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = accentColor,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(bottom = 3.dp, start = 6.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Měřeno ${fmt.time(reading.measuredAtUtcMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                            if (finite.size >= 2) {
                                Text(
                                    "Min ${fmt.number(finite.min(), 0)} · Max ${fmt.number(finite.max(), 0)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
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
        icon = "📊",
        reading = reading,
        historyValues = emptyList(),
        accentColor = MaterialTheme.colorScheme.primary,
        windowLabel = "dnes",
        modifier = modifier
    )
}

/**
 * Položka datové řady pro společný graf (Grafana styl).
 */
data class SeriesItem(
    val id: String,
    val label: String,
    val color: Color,
    val values: List<Double?>,
    val unit: String = "",
)

/**
 * Vícenásobný srovnávací graf (Grafana styl) pro Skupinový Dashboard i Porovnání aktivit (Compare).
 * Podporuje zapínání/vypínání jednotlivých křivek v legendě a interaktivní odečet všech křivek najednou.
 */
@Composable
fun MultiSeriesLineChart(
    series: List<SeriesItem>,
    modifier: Modifier = Modifier,
    xLabels: List<String> = emptyList(),
    title: String = "Srovnávací graf",
) {
    var hiddenIds by remember { mutableStateOf(setOf<String>()) }
    var selectedIdx by remember { mutableStateOf<Int?>(null) }

    val visibleSeries = remember(series, hiddenIds) {
        series.filter { it.id !in hiddenIds && it.values.any { v -> v != null } }
    }
    val maxPoints = remember(series) { series.maxOfOrNull { it.values.size } ?: 0 }
    val allFinite = remember(visibleSeries) { visibleSeries.flatMap { it.values.filterNotNull() } }
    val lo = remember(allFinite) { allFinite.minOrNull() ?: 0.0 }
    val hi = remember(allFinite) { allFinite.maxOrNull() ?: 100.0 }

    val gridColor = Color.White.copy(alpha = 0.07f)
    val cursorColor = Color(0xFFE2E8F0)

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFF232A3B)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11151F))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFCBD5E1),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                val xText = selectedIdx?.let { xLabels.getOrNull(it) ?: "Bod #${it + 1}" }
                    ?: if (allFinite.isNotEmpty()) "${fmt.number(lo, 0)} – ${fmt.number(hi, 0)}" else "Bez dat"
                Text(
                    xText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Interaktivní Grafana legenda (kliknutím lze skrýt/zobrazit účastníka nebo aktivitu)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                series.forEach { s ->
                    val enabled = s.id !in hiddenIds
                    val curVal = selectedIdx?.let { s.values.getOrNull(it) } ?: s.values.lastOrNull { it != null }
                    val decimals = if (s.unit == "%" || s.unit == "bpm" || s.unit == "ms" || s.unit == "W") 0 else 1
                    val valStr = curVal?.let { "${fmt.number(it, decimals)}${if (s.unit.isNotBlank()) " ${s.unit}" else ""}" } ?: "—"
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (enabled) s.color.copy(alpha = 0.16f)
                                else Color(0xFF1E2433).copy(alpha = 0.5f)
                            )
                            .clickable {
                                hiddenIds = if (enabled) hiddenIds + s.id else hiddenIds - s.id
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(if (enabled) s.color else Color.Gray, CircleShape)
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
                        color = Color(0xFF64748B)
                    )
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(195.dp)
                        .padding(vertical = 6.dp)
                        .pointerInput(maxPoints) {
                            fun idx(x: Float) = ((x / size.width) * (maxPoints - 1)).toInt().coerceIn(0, maxPoints - 1)
                            detectTapGestures { offset ->
                                selectedIdx = idx(offset.x)
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
                    val padY = 16f
                    val chartH = (size.height - padY * 2).coerceAtLeast(10f)
                    val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0

                    fun x(i: Int, count: Int) = size.width * i / (count - 1).coerceAtLeast(1)
                    fun y(v: Double) = padY + (chartH * (1.0 - (v - lo) / span)).toFloat()

                    // Mřížka
                    for (step in 0..4) {
                        val gy = padY + chartH * (step / 4f)
                        drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.2f)
                    }

                    // Vykreslení každé viditelné řady
                    for (s in visibleSeries) {
                        val pts = mutableListOf<Pair<Int, Double>>()
                        s.values.forEachIndexed { idx, v -> if (v != null) pts.add(idx to v) }
                        if (pts.isEmpty()) continue
                        val count = s.values.size.coerceAtLeast(maxPoints)
                        if (pts.size == 1) {
                            drawCircle(s.color, radius = 6f, center = Offset(x(pts[0].first, count), y(pts[0].second)))
                            continue
                        }

                        val strokePath = Path()
                        val fillPath = Path()
                        val fx = x(pts[0].first, count)
                        val fy = y(pts[0].second)
                        strokePath.moveTo(fx, fy)
                        fillPath.moveTo(fx, size.height)
                        fillPath.lineTo(fx, fy)

                        for (i in 1 until pts.size) {
                            val px = x(pts[i - 1].first, count)
                            val py = y(pts[i - 1].second)
                            val cx = x(pts[i].first, count)
                            val cy = y(pts[i].second)
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
                                colors = listOf(s.color.copy(alpha = if (visibleSeries.size == 1) 0.28f else 0.10f), Color.Transparent),
                                startY = padY,
                                endY = size.height
                            )
                        )
                        drawPath(
                            strokePath,
                            color = s.color,
                            style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                        if (pts.size <= 35) {
                            for (pt in pts) {
                                drawCircle(s.color, radius = 4f, center = Offset(x(pt.first, count), y(pt.second)))
                            }
                        }
                    }

                    // Kurzor
                    selectedIdx?.let { idx ->
                        val cx = x(idx, maxPoints)
                        drawLine(cursorColor.copy(alpha = 0.65f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2f)
                        for (s in visibleSeries) {
                            s.values.getOrNull(idx)?.let { v ->
                                drawCircle(s.color, radius = 7f, center = Offset(cx, y(v)))
                                drawCircle(Color.White, radius = 3.5f, center = Offset(cx, y(v)))
                            }
                        }
                    }
                }
                if (xLabels.size >= 2) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(xLabels.first(), style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B), maxLines = 1, softWrap = false)
                        if (xLabels.size >= 3) {
                            Text(xLabels[xLabels.size / 2], style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B), maxLines = 1, softWrap = false)
                        }
                        Text(xLabels.last(), style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/**
 * Moderní plynulý graf zdravotních metrik s kubickými Bézier křivkami, přechodem a zónou obvyklých hodnot.
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
    onCursor: (Int) -> Unit = {},
    onRange: (IntRange?) -> Unit = {},
) {
    val bandColor = color.copy(alpha = 0.12f)
    val selColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
    val cursorColor = Color(0xFFFF5252)
    val gridColor = Color.White.copy(alpha = 0.07f)
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val finite = remember(values) { values.filterNotNull() }
    val lo = if (finite.isNotEmpty()) minOf(finite.min(), band?.start ?: Double.MAX_VALUE) else 0.0
    val hi = if (finite.isNotEmpty()) maxOf(finite.max(), band?.endInclusive ?: -Double.MAX_VALUE) else 100.0

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFF232A3B)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11151F))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val activeIdx = selectedIndex ?: cursor
                val activeVal = activeIdx?.let { values.getOrNull(it) }
                if (activeVal != null) {
                    Text(
                        "Vybraná hodnota: ${fmt.number(activeVal, 1)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                } else {
                    Text(
                        "Rozpětí: ${fmt.number(lo, 1)} – ${fmt.number(hi, 1)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF94A3B8)
                    )
                }
                Text(
                    "Vzorků: ${values.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B)
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(185.dp)
                    .padding(vertical = 8.dp)
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
                        detectDragGestures(
                            onDragStart = { o ->
                                anchor = idx(o.x)
                                selectedIndex = anchor
                                onCursor(anchor)
                                if (selectMode) onRange(anchor..anchor)
                            },
                            onDrag = { change, _ ->
                                val i = idx(change.position.x)
                                selectedIndex = i
                                onCursor(i)
                                if (selectMode) onRange(minOf(anchor, i)..maxOf(anchor, i))
                            }
                        )
                    }
            ) {
                if (finite.size < 2) return@Canvas
                val paddingY = 18f
                val chartHeight = size.height - (paddingY * 2)
                val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0

                fun x(i: Int) = size.width * i / (values.size - 1).coerceAtLeast(1)
                fun y(v: Double) = paddingY + (chartHeight * (1 - (v - lo) / span)).toFloat()

                for (step in 1..3) {
                    val gridY = paddingY + chartHeight * (step / 4f)
                    drawLine(gridColor, Offset(0f, gridY), Offset(size.width, gridY), strokeWidth = 1.3f)
                }

                band?.let {
                    val top = y(it.endInclusive).coerceIn(0f, size.height)
                    val bottom = y(it.start).coerceIn(0f, size.height)
                    drawRect(bandColor, Offset(0f, top), Size(size.width, (bottom - top).coerceAtLeast(4f)))
                }

                range?.let {
                    drawRect(selColor, Offset(x(it.first), 0f), Size(x(it.last) - x(it.first), size.height))
                }

                val segments = mutableListOf<MutableList<Pair<Int, Double>>>()
                var curSegment = mutableListOf<Pair<Int, Double>>()
                values.forEachIndexed { i, v ->
                    if (v != null) {
                        curSegment.add(i to v)
                    } else if (curSegment.isNotEmpty()) {
                        segments.add(curSegment)
                        curSegment = mutableListOf()
                    }
                }
                if (curSegment.isNotEmpty()) segments.add(curSegment)

                for (segment in segments) {
                    if (segment.size < 2) {
                        val p = segment[0]
                        drawCircle(color, radius = 6f, center = Offset(x(p.first), y(p.second)))
                        continue
                    }

                    val strokePath = Path()
                    val fillPath = Path()

                    val firstX = x(segment[0].first)
                    val firstY = y(segment[0].second)
                    strokePath.moveTo(firstX, firstY)
                    fillPath.moveTo(firstX, size.height)
                    fillPath.lineTo(firstX, firstY)

                    for (i in 1 until segment.size) {
                        val prevX = x(segment[i - 1].first)
                        val prevY = y(segment[i - 1].second)
                        val currX = x(segment[i].first)
                        val currY = y(segment[i].second)
                        val midX = prevX + (currX - prevX) * 0.5f

                        strokePath.cubicTo(midX, prevY, midX, currY, currX, currY)
                        fillPath.cubicTo(midX, prevY, midX, currY, currX, currY)
                    }

                    val lastX = x(segment.last().first)
                    fillPath.lineTo(lastX, size.height)
                    fillPath.close()

                    val gradient = Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f)),
                        startY = paddingY,
                        endY = size.height
                    )
                    drawPath(fillPath, brush = gradient)

                    drawPath(
                        strokePath,
                        color = color,
                        style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    if (segment.size <= 40) {
                        for (pt in segment) {
                            drawCircle(color, radius = 4f, center = Offset(x(pt.first), y(pt.second)))
                        }
                    }
                }

                val active = selectedIndex ?: cursor
                active?.let { i ->
                    val cx = x(i)
                    drawLine(cursorColor, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2.2f)
                    values.getOrNull(i)?.let { valY ->
                        drawCircle(cursorColor, radius = 7f, center = Offset(cx, y(valY)))
                        drawCircle(Color.White, radius = 3.5f, center = Offset(cx, y(valY)))
                    }
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
            .background(Color(0xFF0D111A))
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
    modifier: Modifier = Modifier,
) {
    val allPts = remember(routes) { routes.flatMap { it.second.filterNotNull() } }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0D111A))
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
        routes.forEach { (color, pts) ->
            var prev: Offset? = null
            pts.forEach { c ->
                if (c == null) { prev = null; return@forEach }
                val o = p(c)
                prev?.let { drawLine(color, it, o, 4.5f, cap = StrokeCap.Round) }
                prev = o
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.Bold,
    color = Color(0xFFE2E8F0),
    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
)

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
