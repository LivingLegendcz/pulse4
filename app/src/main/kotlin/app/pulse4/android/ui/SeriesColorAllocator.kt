package app.pulse4.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.pulse4.domain.MetricType
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Perceptual color difference: CIEDE2000 (ΔE00) on CIELAB, sRGB / D65.
 * Reference: Sharma, Wu, Dalal (2005), "The CIEDE2000 color-difference formula".
 */
internal object ColorDistance {
    private val labCache = ConcurrentHashMap<ULong, DoubleArray>()

    fun lab(c: Color): DoubleArray = labCache.getOrPut(c.value) { srgbToLab(c.red, c.green, c.blue) }

    private fun srgbToLab(red: Float, green: Float, blue: Float): DoubleArray {
        fun lin(v: Float): Double = v.toDouble().let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        val r = lin(red); val g = lin(green); val b = lin(blue)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
        val fx = f(x); val fy = f(y); val fz = f(z)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    fun deltaE00(a: Color, b: Color): Double = deltaE00(lab(a), lab(b))

    fun deltaE00(lab1: DoubleArray, lab2: DoubleArray): Double {
        val (l1, a1, b1) = Triple(lab1[0], lab1[1], lab1[2])
        val (l2, a2, b2) = Triple(lab2[0], lab2[1], lab2[2])
        val c1 = sqrt(a1 * a1 + b1 * b1)
        val c2 = sqrt(a2 * a2 + b2 * b2)
        val cBar7 = ((c1 + c2) / 2.0).pow(7)
        val g = 0.5 * (1.0 - sqrt(cBar7 / (cBar7 + POW25_7)))
        val a1p = (1.0 + g) * a1
        val a2p = (1.0 + g) * a2
        val c1p = sqrt(a1p * a1p + b1 * b1)
        val c2p = sqrt(a2p * a2p + b2 * b2)
        val h1p = hueDeg(b1, a1p)
        val h2p = hueDeg(b2, a2p)

        val dLp = l2 - l1
        val dCp = c2p - c1p
        val dhp = when {
            c1p * c2p == 0.0 -> 0.0
            abs(h2p - h1p) <= 180.0 -> h2p - h1p
            h2p - h1p > 180.0 -> h2p - h1p - 360.0
            else -> h2p - h1p + 360.0
        }
        val dHp = 2.0 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2.0))

        val lBarP = (l1 + l2) / 2.0
        val cBarP = (c1p + c2p) / 2.0
        val hBarP = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180.0 -> (h1p + h2p) / 2.0
            h1p + h2p < 360.0 -> (h1p + h2p + 360.0) / 2.0
            else -> (h1p + h2p - 360.0) / 2.0
        }
        val t = 1.0 -
            0.17 * cos(Math.toRadians(hBarP - 30.0)) +
            0.24 * cos(Math.toRadians(2.0 * hBarP)) +
            0.32 * cos(Math.toRadians(3.0 * hBarP + 6.0)) -
            0.20 * cos(Math.toRadians(4.0 * hBarP - 63.0))
        val dTheta = 30.0 * exp(-((hBarP - 275.0) / 25.0).pow(2))
        val cBarP7 = cBarP.pow(7)
        val rc = 2.0 * sqrt(cBarP7 / (cBarP7 + POW25_7))
        val lm50 = (lBarP - 50.0).pow(2)
        val sl = 1.0 + 0.015 * lm50 / sqrt(20.0 + lm50)
        val sc = 1.0 + 0.045 * cBarP
        val sh = 1.0 + 0.015 * cBarP * t
        val rt = -sin(Math.toRadians(2.0 * dTheta)) * rc
        val tl = dLp / sl; val tc = dCp / sc; val th = dHp / sh
        return sqrt(tl * tl + tc * tc + th * th + rt * tc * th)
    }

    private fun hueDeg(b: Double, aP: Double): Double {
        if (b == 0.0 && aP == 0.0) return 0.0
        val h = Math.toDegrees(atan2(b, aP))
        return if (h < 0) h + 360.0 else h
    }

    private val POW25_7 = 25.0.pow(7)
}

/** Point marker of a chart series; secondary cue next to [SeriesLineStyle]. */
internal enum class SeriesMarker { CIRCLE, SQUARE, TRIANGLE, DIAMOND }

/** Resolved look of one series in a multi-series chart and its legend. */
internal data class SeriesStyle(
    val color: Color,
    val lineStyle: SeriesLineStyle,
    val marker: SeriesMarker,
    /** True when [color] is the metric's own [MetricPalette] color (no substitution). */
    val isHomeColor: Boolean,
)

/**
 * Conflict-free color allocation for charts that draw several series together.
 *
 * Every metric keeps its [MetricPalette] "home" color wherever it is shown alone (cards, detail
 * screens, icons). Only when series are drawn together does this allocator step in:
 *
 * 1. Series are processed in selection order (deterministic: same selection -> same result).
 * 2. A series keeps its home color if its CIEDE2000 distance to every already assigned color and
 *    to every chart background in [CHART_BACKGROUNDS] is >= [MIN_DELTA_E].
 * 3. Otherwise it takes a [FALLBACK_PALETTE] color: among unused palette colors that satisfy the
 *    threshold, the one perceptually nearest to its home color (keeps the hue family); if none
 *    satisfies it, the one maximizing the minimum distance to the assigned colors.
 * 4. Line style + marker: a series whose color is closer than [MIN_DELTA_E] to an earlier series
 *    gets the lowest style slot not used by any of those close series, so two series never share
 *    both a similar color and the same look. Series without conflicts stay solid + circle.
 *
 * The palette is built so that the pure-color guarantee (no reliance on line style) holds for any
 * selection of up to [PURE_COLOR_GUARANTEE] series; MetricsFilterStateTest / SeriesColorAllocatorTest
 * verify this exhaustively.
 */
internal object SeriesColorAllocator {
    /** Minimum CIEDE2000 difference between two series colors (and series vs. chart background). */
    const val MIN_DELTA_E: Double = 25.0

    /** Number of series for which distinct colors alone (ΔE00 >= [MIN_DELTA_E]) are guaranteed. */
    const val PURE_COLOR_GUARANTEE: Int = 5

    /**
     * Surfaces the series are drawn on. The app ships a single dark (Obsidian/Mineral) scheme and
     * the multi-series chart card is hard-coded dark, so there is no light-theme chart background.
     */
    val CHART_BACKGROUNDS: List<Color> = listOf(
        Color(0xFF070C15), // MultiSeriesLineChart card container
        Color(0xFF060B14), // chart veil gradient
        Color(0xFF050911), // chart veil gradient (bottom)
        Color(0xFF0B1322), // legend chip / toggle background
        Color(0xFF0D1520), // MineralSurfaceDark
        Color(0xFF162231), // MineralSurfaceVariantDark (lightest surface)
    )

    /**
     * App-design fallback colors: Tailwind 300–600 tones already used across the UI and theme
     * (Sage teal, Glacier sky, Sand amber, rose, indigo, lime, cyan, emerald, …). Ordered by
     * priority; the order is part of the deterministic tie-breaking.
     */
    val FALLBACK_PALETTE: List<Color> = listOf(
        Color(0xFF2DD4BF), // teal-400    (SagePrimaryDark)
        Color(0xFF38BDF8), // sky-400     (GlacierSecondaryDark)
        Color(0xFFFBBF24), // amber-400   (SandTertiaryDark)
        Color(0xFFFDA4AF), // rose-300
        Color(0xFF4F46E5), // indigo-600
        Color(0xFF65A30D), // lime-600
        Color(0xFF22D3EE), // cyan-400
        Color(0xFF059669), // emerald-600
        Color(0xFFC026D3), // fuchsia-600
        Color(0xFF0284C7), // sky-600     (GlacierSecondaryLight)
        Color(0xFFDB2777), // pink-600
        Color(0xFF60A5FA), // blue-400
        Color(0xFF10B981), // emerald-500 (HealthGreen)
        Color(0xFFCA8A04), // yellow-600
    )

    private val LINE_STYLES = SeriesLineStyle.entries
    private val MARKERS = SeriesMarker.entries

    /** Assign colors + styles to series with the given home colors, in order. */
    fun assign(homeColors: List<Color>): List<SeriesStyle> {
        val colors = ArrayList<Color>(homeColors.size)
        val home = ArrayList<Boolean>(homeColors.size)
        for (h in homeColors) {
            if (isFree(h, colors)) {
                colors += h; home += true
            } else {
                val c = pickFallback(h, colors)
                colors += c; home += c == h
            }
        }
        return withStyles(colors).mapIndexed { i, s -> s.copy(isHomeColor = home[i]) }
    }

    /**
     * Keep the given colors (e.g. per-member or per-activity identity colors) and only add line
     * styles / markers where two colors are closer than [MIN_DELTA_E].
     */
    fun assignStylesOnly(colors: List<Color>): List<SeriesStyle> = withStyles(colors)

    private fun isFree(c: Color, assigned: List<Color>): Boolean =
        CHART_BACKGROUNDS.all { ColorDistance.deltaE00(c, it) >= MIN_DELTA_E } &&
            assigned.all { ColorDistance.deltaE00(c, it) >= MIN_DELTA_E }

    private fun minDistance(c: Color, assigned: List<Color>): Double =
        assigned.minOfOrNull { ColorDistance.deltaE00(c, it) } ?: Double.MAX_VALUE

    private fun pickFallback(home: Color, assigned: List<Color>): Color {
        val unused = FALLBACK_PALETTE.filter { it !in assigned }
        val valid = unused.filter { isFree(it, assigned) }
        if (valid.isNotEmpty()) {
            // minByOrNull keeps the first minimum -> palette order breaks ties.
            return valid.minBy { ColorDistance.deltaE00(it, home) }
        }
        // Nothing reaches the threshold: the home color competes too; line style / marker then
        // carry the distinction (see withStyles).
        val homeVisible = CHART_BACKGROUNDS.all { ColorDistance.deltaE00(home, it) >= MIN_DELTA_E }
        val pool = (if (homeVisible) listOf(home) else emptyList()) + unused.ifEmpty { FALLBACK_PALETTE }
        var best = pool.first()
        var bestD = -1.0
        for (p in pool) {
            val d = minDistance(p, assigned)
            if (d > bestD) { best = p; bestD = d }
        }
        return best
    }

    private fun withStyles(colors: List<Color>): List<SeriesStyle> {
        val slots = IntArray(colors.size)
        for (i in colors.indices) {
            val taken = HashSet<Int>()
            for (j in 0 until i) {
                if (ColorDistance.deltaE00(colors[i], colors[j]) < MIN_DELTA_E) taken += slots[j]
            }
            var k = 0
            while (k in taken) k++
            slots[i] = k
        }
        return colors.mapIndexed { i, c ->
            val k = slots[i]
            SeriesStyle(
                color = c,
                lineStyle = LINE_STYLES[k % LINE_STYLES.size],
                // Shift the marker every full line-style cycle so (style, marker) stays unique for 16 slots.
                marker = MARKERS[(k + k / LINE_STYLES.size) % MARKERS.size],
                isHomeColor = true,
            )
        }
    }
}

/**
 * Colors and styles for metrics drawn together (multi-metric chart, its legend, presets).
 * Deterministic in selection order; duplicates in [selected] are ignored.
 */
internal fun assignSeriesColors(selected: List<MetricType>): Map<MetricType, SeriesStyle> {
    val distinct = selected.distinct()
    val styles = SeriesColorAllocator.assign(distinct.map { MetricPalette.of(it) })
    return LinkedHashMap<MetricType, SeriesStyle>(distinct.size).apply {
        distinct.forEachIndexed { i, t -> put(t, styles[i]) }
    }
}

/** Dash pattern of a line style; [scale] shrinks it for small legend samples. */
internal fun SeriesLineStyle.pathEffect(scale: Float = 1f): PathEffect? = when (this) {
    SeriesLineStyle.SOLID -> null
    SeriesLineStyle.DASHED -> PathEffect.dashPathEffect(floatArrayOf(16f * scale, 10f * scale))
    SeriesLineStyle.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(2f * scale, 9f * scale))
    SeriesLineStyle.DASH_DOT -> PathEffect.dashPathEffect(floatArrayOf(16f * scale, 7f * scale, 2f * scale, 7f * scale))
}

/** Draw a series point marker centered at [center]; [radius] is the circle-equivalent size. */
internal fun DrawScope.drawSeriesMarker(marker: SeriesMarker, color: Color, center: Offset, radius: Float) {
    when (marker) {
        SeriesMarker.CIRCLE -> drawCircle(color, radius = radius, center = center)
        SeriesMarker.SQUARE -> {
            val half = radius * 0.9f
            drawRect(color, topLeft = Offset(center.x - half, center.y - half), size = Size(half * 2, half * 2))
        }
        SeriesMarker.TRIANGLE -> {
            val r = radius * 1.25f
            val p = Path().apply {
                moveTo(center.x, center.y - r)
                lineTo(center.x + r * 0.866f, center.y + r * 0.5f)
                lineTo(center.x - r * 0.866f, center.y + r * 0.5f)
                close()
            }
            drawPath(p, color)
        }
        SeriesMarker.DIAMOND -> {
            val r = radius * 1.25f
            val p = Path().apply {
                moveTo(center.x, center.y - r)
                lineTo(center.x + r, center.y)
                lineTo(center.x, center.y + r)
                lineTo(center.x - r, center.y)
                close()
            }
            drawPath(p, color)
        }
    }
}
