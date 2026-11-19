package app.pulse4.android.ui

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import app.pulse4.domain.MetricType
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricsFilterStateTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Test
    fun metricTypeSetSaverRoundTripKeepsOrder() {
        val sel = linkedSetOf(MetricType.WEIGHT, MetricType.MUSCLE_MASS, MetricType.BODY_FAT)
        val saved = with(MetricTypeSetSaver) { scope.save(sel) }
        assertNotNull(saved)
        val restored = MetricTypeSetSaver.restore(saved)
        assertEquals(sel.toList(), restored?.toList())
    }

    @Test
    fun metricTypeSetSaverDropsUnknownNames() {
        assertEquals(listOf(MetricType.HRV), MetricTypeSetSaver.restore("HRV,NOT_A_METRIC")?.toList())
        assertNull(MetricTypeSetSaver.restore("NOT_A_METRIC"))
    }

    @Test
    fun filterStateEncodeDecodeRoundTrip() {
        val st = MetricsFilterState(
            types = listOf(MetricType.BODY_FAT, MetricType.MUSCLE_MASS),
            daysCount = 90,
            normalize = true,
            bodyUnitMode = BodyUnitMode.PERCENT,
            showSeparateCards = false,
        )
        assertEquals(st, MetricsFilterState.decode(st.encode()))
    }

    @Test
    fun filterStateDecodeIsLenient() {
        assertNull(MetricsFilterState.decode(null))
        assertNull(MetricsFilterState.decode(""))
        val d = MetricsFilterState.decode("STEPS;999;x;BOGUS")
        assertEquals(MetricsFilterState(types = listOf(MetricType.STEPS)), d)
    }

    @Test
    fun muscleAndFatColorsArePerceptuallyDistinct() {
        val m = MetricPalette.Muscle
        val f = MetricPalette.Fat
        assertTrue(hueDistance(m, f) >= 60.0, "hue distance ${hueDistance(m, f)}")
        assertTrue(deltaE76(m, f) >= 40.0, "ΔE ${deltaE76(m, f)}")
        // Muscle must also stand apart from the total-weight reference line.
        assertTrue(deltaE76(m, MetricPalette.Weight) >= 25.0)
    }

    private fun assertGroupSeparated(group: List<MetricType>, minDeltaE: Double) {
        for (i in group.indices) for (j in i + 1 until group.size) {
            val a = group[i]; val b = group[j]
            val d = deltaE76(MetricPalette.of(a), MetricPalette.of(b))
            assertTrue(d >= minDeltaE, "$a vs $b: ΔE $d < $minDeltaE")
        }
    }

    @Test
    fun bodyCompositionPaletteHasNoNearDuplicates() = assertGroupSeparated(MetricPalette.BODY_COMPOSITION, 25.0)

    @Test
    fun cardioPaletteHasNoNearDuplicates() = assertGroupSeparated(MetricPalette.CARDIO, 25.0)

    @Test
    fun activityPaletteHasNoNearDuplicates() = assertGroupSeparated(MetricPalette.ACTIVITY, 25.0)

    @Test
    fun noTwoMetricsShareIdenticalColor() {
        val byColor = MetricType.entries.groupBy { MetricPalette.of(it) }
        val dups = byColor.filterValues { it.size > 1 }
        assertTrue(dups.isEmpty(), "duplicate colors: $dups")
    }

    @Test
    fun metricConfigUsesCentralPalette() {
        MetricType.entries.forEach { t ->
            assertEquals(MetricPalette.of(t), getMetricUiConfig(t).color, "config color of $t")
        }
    }

    private fun hueDistance(a: Color, b: Color): Double {
        val d = abs(hue(a) - hue(b))
        return min(d, 360.0 - d)
    }

    private fun hue(c: Color): Double {
        val r = c.red.toDouble(); val g = c.green.toDouble(); val b = c.blue.toDouble()
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d == 0.0) return 0.0
        val h = when (mx) {
            r -> 60.0 * (((g - b) / d) % 6.0)
            g -> 60.0 * ((b - r) / d + 2.0)
            else -> 60.0 * ((r - g) / d + 4.0)
        }
        return (h + 360.0) % 360.0
    }

    private fun lab(c: Color): DoubleArray {
        fun lin(v: Float): Double = v.toDouble().let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        val r = lin(c.red); val g = lin(c.green); val b = lin(c.blue)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun t(v: Double) = if (v > 0.008856) cbrt(v) else 7.787 * v + 16.0 / 116.0
        return doubleArrayOf(116 * t(y) - 16, 500 * (t(x) - t(y)), 200 * (t(y) - t(z)))
    }

    private fun deltaE76(a: Color, b: Color): Double {
        val la = lab(a); val lb = lab(b)
        return sqrt((la[0] - lb[0]).pow(2) + (la[1] - lb[1]).pow(2) + (la[2] - lb[2]).pow(2))
    }
}
