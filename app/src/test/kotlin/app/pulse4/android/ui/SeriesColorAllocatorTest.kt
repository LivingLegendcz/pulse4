package app.pulse4.android.ui

import androidx.compose.ui.graphics.Color
import app.pulse4.domain.MetricType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class SeriesColorAllocatorTest {
    private val t = SeriesColorAllocator.MIN_DELTA_E
    private val all = MetricType.entries
    private fun d(a: Color, b: Color) = ColorDistance.deltaE00(a, b)

    // --- CIEDE2000 implementation -----------------------------------------------------------

    @Test
    fun ciede2000MatchesSharmaReferenceData() {
        // Sharma, Wu, Dalal (2005), Table 1: (L, a, b) pairs and expected ΔE00.
        val cases = listOf(
            doubleArrayOf(50.0, 2.6772, -79.7751, 50.0, 0.0, -82.7485, 2.0425),
            doubleArrayOf(50.0, 3.1571, -77.2803, 50.0, 0.0, -82.7485, 2.8615),
            doubleArrayOf(50.0, -1.3802, -84.2814, 50.0, 0.0, -82.7485, 1.0000),
            doubleArrayOf(50.0, 2.5, 0.0, 50.0, 0.0, -2.5, 4.3065),
            doubleArrayOf(60.2574, -34.0099, 36.2677, 60.4626, -34.1751, 39.4387, 1.2644),
            doubleArrayOf(63.0109, -31.0961, -5.8663, 62.8187, -29.7946, -4.0864, 1.2630),
            doubleArrayOf(22.7233, 20.0904, -46.6940, 23.0331, 14.9730, -42.5619, 2.0373),
            doubleArrayOf(90.8027, -2.0831, 1.4410, 91.1528, -1.6435, 0.0447, 1.4441),
        )
        for (c in cases) {
            val got = ColorDistance.deltaE00(doubleArrayOf(c[0], c[1], c[2]), doubleArrayOf(c[3], c[4], c[5]))
            assertEquals(c[6], got, 1e-4, "case ${c.toList()}")
            val sym = ColorDistance.deltaE00(doubleArrayOf(c[3], c[4], c[5]), doubleArrayOf(c[0], c[1], c[2]))
            assertEquals(got, sym, 1e-9)
        }
    }

    // --- palette sanity -----------------------------------------------------------------------

    @Test
    fun fallbackPaletteIsDistinctAndVisibleOnEveryChartBackground() {
        val p = SeriesColorAllocator.FALLBACK_PALETTE
        assertTrue(p.size in 12..16, "palette size ${p.size}")
        assertEquals(p.size, p.toSet().size)
        for (c in p) for (bg in SeriesColorAllocator.CHART_BACKGROUNDS) {
            assertTrue(d(c, bg) >= t, "$c vs background $bg: ${d(c, bg)}")
        }
    }

    /**
     * Proof of the pure-color guarantee independent of selection order: for every set of already
     * assigned colors that the allocator can produce (pairwise >= T, drawn from home colors and the
     * fallback palette) of size < PURE_COLOR_GUARANTEE, there is still a palette color >= T from
     * all of them, so the next series never has to rely on line style.
     */
    @Test
    fun paletteClosureGuaranteesPureColorUpToGuaranteeSize() {
        val bgOk = { c: Color -> SeriesColorAllocator.CHART_BACKGROUNDS.all { d(c, it) >= t } }
        val universe = (all.map { MetricPalette.of(it) }.filter(bgOk) + SeriesColorAllocator.FALLBACK_PALETTE).distinct()
        val n = universe.size
        val dist = Array(n) { i -> DoubleArray(n) { j -> d(universe[i], universe[j]) } }
        val pal = SeriesColorAllocator.FALLBACK_PALETTE.map { universe.indexOf(it) }
        var checked = 0
        fun rec(chosen: IntArray, size: Int, from: Int) {
            if (size > 0) {
                checked++
                val ok = pal.any { p -> (0 until size).all { dist[p][chosen[it]] >= t } }
                if (!ok) fail("no palette color left for assigned ${(0 until size).map { universe[chosen[it]] }}")
            }
            if (size == SeriesColorAllocator.PURE_COLOR_GUARANTEE - 1) return
            for (k in from until n) {
                if ((0 until size).all { dist[k][chosen[it]] >= t }) {
                    chosen[size] = k
                    rec(chosen, size + 1, k + 1)
                }
            }
        }
        rec(IntArray(SeriesColorAllocator.PURE_COLOR_GUARANTEE), 0, 0)
        assertTrue(checked > 1000, "checked $checked sets")
    }

    // --- allocation properties ---------------------------------------------------------------

    private fun assertPureColor(sel: List<MetricType>) {
        val st = assignSeriesColors(sel).values.toList()
        for (i in st.indices) {
            for (bg in SeriesColorAllocator.CHART_BACKGROUNDS) {
                assertTrue(d(st[i].color, bg) >= t, "$sel: ${st[i].color} too close to background")
            }
            for (j in i + 1 until st.size) {
                val de = d(st[i].color, st[j].color)
                if (de < t) fail("$sel: series $i/$j ΔE00 $de < $t")
            }
        }
    }

    /**
     * Series whose colors are closer than T must differ in line style ([strictLineStyle]) or at
     * least in the (line style, marker) combination; no two series may look identical.
     */
    private fun assertDistinguishable(sel: List<MetricType>, strictLineStyle: Boolean) {
        val st = assignSeriesColors(sel).values.toList()
        assertEquals(st.size, st.map { Triple(it.color, it.lineStyle, it.marker) }.toSet().size, "$sel: duplicate look")
        for (i in st.indices) for (j in i + 1 until st.size) {
            if (d(st[i].color, st[j].color) < t) {
                val same = if (strictLineStyle) st[i].lineStyle == st[j].lineStyle
                else st[i].lineStyle == st[j].lineStyle && st[i].marker == st[j].marker
                assertTrue(!same, "$sel: close colors $i/$j share style ${st[i].lineStyle}/${st[i].marker}")
            }
        }
    }

    @Test
    fun everySingleMetricIsVisibleAndAllPairsAndTriplesArePureColor() {
        for (a in all) assertPureColor(listOf(a))
        for (a in all) for (b in all) if (a != b) {
            assertPureColor(listOf(a, b))
            for (c in all) if (c != a && c != b) assertPureColor(listOf(a, b, c))
        }
    }

    /**
     * Every 4-metric combination in enum order and reversed (all orders are covered by the closure
     * proof above; enumerating all 863k orderings takes minutes).
     */
    @Test
    fun allQuadrupleCombinationsArePureColor() {
        val n = all.size
        for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (e in c + 1 until n) {
            val sel = listOf(all[a], all[b], all[c], all[e])
            assertPureColor(sel)
            assertPureColor(sel.asReversed())
        }
    }

    @Test
    fun randomSelectionsOfFourToSixSeries() {
        val rnd = Random(20261119)
        for (size in 4..6) repeat(20_000) {
            val sel = all.shuffled(rnd).take(size)
            if (size <= SeriesColorAllocator.PURE_COLOR_GUARANTEE) assertPureColor(sel)
            assertDistinguishable(sel, strictLineStyle = true)
        }
    }

    @Test
    fun largeSelectionsStayDistinguishable() {
        val rnd = Random(42)
        // The Metrics screen offers 24 metrics; up to 16 mutually close series stay unique.
        for (size in 7..24) repeat(500) { assertDistinguishable(all.shuffled(rnd).take(size), strictLineStyle = false) }
    }

    @Test
    fun allocationIsDeterministic() {
        val rnd = Random(7)
        repeat(2_000) {
            val sel = all.shuffled(rnd).take(rnd.nextInt(1, 9))
            assertEquals(assignSeriesColors(sel), assignSeriesColors(sel.toList()))
        }
        // Duplicates in the selection are ignored, order of first occurrence wins.
        val sel = listOf(MetricType.HRV, MetricType.STEPS, MetricType.HRV)
        assertEquals(listOf(MetricType.HRV, MetricType.STEPS), assignSeriesColors(sel).keys.toList())
    }

    @Test
    fun homeColorsKeptWhenThereIsNoConflict() {
        val mf = assignSeriesColors(listOf(MetricType.MUSCLE_MASS, MetricType.BODY_FAT))
        assertEquals(MetricPalette.Muscle, mf.getValue(MetricType.MUSCLE_MASS).color)
        assertEquals(MetricPalette.Fat, mf.getValue(MetricType.BODY_FAT).color)
        assertTrue(mf.values.all { it.isHomeColor && it.lineStyle == SeriesLineStyle.SOLID && it.marker == SeriesMarker.CIRCLE })

        val body = assignSeriesColors(listOf(MetricType.WEIGHT, MetricType.MUSCLE_MASS, MetricType.BODY_FAT))
        assertTrue(body.all { (k, v) -> v.isHomeColor && v.color == MetricPalette.of(k) }, "$body")

        // Every metric drawn alone keeps its home color unless it vanishes on the chart background.
        for (m in all) {
            val s = assignSeriesColors(listOf(m)).getValue(m)
            val bgOk = SeriesColorAllocator.CHART_BACKGROUNDS.all { d(MetricPalette.of(m), it) >= t }
            assertEquals(bgOk, s.isHomeColor, "$m")
            if (bgOk) assertEquals(MetricPalette.of(m), s.color)
        }
    }

    @Test
    fun conflictingHomeColorIsSubstitutedOnlyForTheLaterSeries() {
        // Muscle (blue-500) vs sleep duration (indigo-400) are ~10 ΔE00 apart.
        assertTrue(d(MetricPalette.Muscle, MetricPalette.SleepDuration) < t)
        val r = assignSeriesColors(listOf(MetricType.MUSCLE_MASS, MetricType.SLEEP_DURATION))
        assertEquals(MetricPalette.Muscle, r.getValue(MetricType.MUSCLE_MASS).color)
        val sleep = r.getValue(MetricType.SLEEP_DURATION)
        assertTrue(!sleep.isHomeColor && sleep.color in SeriesColorAllocator.FALLBACK_PALETTE)
        assertTrue(d(sleep.color, MetricPalette.Muscle) >= t)
        // Selection order decides who keeps the home color.
        val rev = assignSeriesColors(listOf(MetricType.SLEEP_DURATION, MetricType.MUSCLE_MASS))
        assertEquals(MetricPalette.SleepDuration, rev.getValue(MetricType.SLEEP_DURATION).color)
    }

    @Test
    fun knownCrossGroupConflictsAreResolved() {
        val pairs = listOf(
            MetricType.MUSCLE_MASS to MetricType.SLEEP_DURATION,
            MetricType.BODY_FAT to MetricType.BODY_BATTERY,
            MetricType.VISCERAL_FAT to MetricType.ECG,
            MetricType.ECG_QTC to MetricType.STRESS,
            MetricType.BODY_WATER to MetricType.SPO2,
            MetricType.WEIGHT to MetricType.SLEEP_AWAKE,
        )
        for ((a, b) in pairs) {
            assertPureColor(listOf(a, b))
            assertPureColor(listOf(b, a))
        }
    }

    @Test
    fun stylesOnlyKeepsColorsAndSeparatesNearDuplicates() {
        val c = Color(0xFF38BDF8)
        val st = SeriesColorAllocator.assignStylesOnly(listOf(c, c, Color(0xFFF97316), c, c, c))
        assertTrue(st.all { it.color == c || it.color == Color(0xFFF97316) })
        assertEquals(SeriesLineStyle.SOLID, st[0].lineStyle)
        assertEquals(SeriesLineStyle.DASHED, st[1].lineStyle)
        assertEquals(SeriesLineStyle.SOLID, st[2].lineStyle)
        assertEquals(SeriesLineStyle.DOTTED, st[3].lineStyle)
        assertEquals(SeriesLineStyle.DASH_DOT, st[4].lineStyle)
        // Fifth identical color: line styles exhausted, the marker keeps the look unique.
        val sameColor = listOf(st[0], st[1], st[3], st[4], st[5])
        assertEquals(5, sameColor.map { it.lineStyle to it.marker }.toSet().size)
    }

    // --- fixed multi-series charts (not allocated at runtime) -------------------------------

    private fun assertFixedSetSeparated(name: String, colors: List<Color>) {
        for (i in colors.indices) {
            for (bg in SeriesColorAllocator.CHART_BACKGROUNDS) assertTrue(d(colors[i], bg) >= t, "$name: ${colors[i]} vs bg")
            for (j in i + 1 until colors.size) {
                assertTrue(d(colors[i], colors[j]) >= t, "$name: ${colors[i]} vs ${colors[j]} ΔE00 ${d(colors[i], colors[j])}")
            }
        }
    }

    @Test
    fun fixedMultiSeriesChartsAreSeparated() {
        assertFixedSetSeparated("weight/muscle/fat area chart", listOf(MetricPalette.Weight, MetricPalette.Muscle, MetricPalette.Fat))
        assertFixedSetSeparated("muscle/fat/bone stacked bar", listOf(MetricPalette.Muscle, MetricPalette.Fat, MetricPalette.Bone))
        assertFixedSetSeparated("body segments", BODY_SEGMENT_DEFS.map { it.third })
    }
}
