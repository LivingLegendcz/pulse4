package app.pulse4.ui

import app.pulse4.domain.MetricType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChartInfoTest {

    private val multiKeys = setOf(
        ChartKey.METRICS_CORRELATION,
        ChartKey.METRICS_CORRELATION_NORMALIZED,
        ChartKey.SEGMENTAL_COMPARE,
        ChartKey.GROUP_COMPARE,
        ChartKey.ACTIVITY_COMPARE_CHANNEL,
        ChartKey.ACTIVITY_COMPARE_ROUTES,
        ChartKey.ACTIVITY_TELEMETRY_CURVES,
    )

    private val sample = listOf(
        ChartInfoSeries("a", "Alfa", "kg"),
        ChartInfoSeries("b", "Beta", "bpm", "detail"),
        ChartInfoSeries("c", "Gama"),
    )

    @Test
    fun everyChartKeyHasNonEmptyExplanation() {
        for (key in ChartKey.entries) {
            val info = ChartInfoTexts.build(key, if (key in multiKeys) sample else emptyList())
            assertEquals(key, info.key)
            assertTrue(info.title.isNotBlank(), "title of $key")
            assertTrue(info.intro.isNotBlank(), "intro of $key")
            assertTrue(info.bullets.isNotEmpty() || info.series.isNotEmpty(), "body of $key")
            assertTrue(info.bullets.all { it.isNotBlank() }, "blank bullet in $key")
            assertTrue(info.plainText().length > 80, "text of $key is too short")
        }
    }

    @Test
    fun dynamicLegendListsAllSelectedSeries() {
        for (key in multiKeys) {
            val text = ChartInfoTexts.build(key, sample).plainText()
            assertTrue("Alfa (kg)" in text, "label+unit missing in $key")
            assertTrue("Beta (bpm) – detail" in text, "detail missing in $key")
            assertTrue("Gama" in text, "series without unit missing in $key")
        }
    }

    @Test
    fun legendFollowsTheSelectedSeriesNotAFixedList() {
        val two = ChartInfoTexts.build(ChartKey.METRICS_CORRELATION, sample.take(2))
        val three = ChartInfoTexts.build(ChartKey.METRICS_CORRELATION, sample)
        assertEquals(2, two.series.size)
        assertEquals(3, three.series.size)
        assertFalse("Gama" in two.plainText())
        assertTrue("Zobrazené řady (2)" in two.plainText())
        assertTrue("Zobrazené řady (3)" in three.plainText())
    }

    @Test
    fun metricSeriesCarriesAggregationOfThatMetric() {
        val steps = ChartInfoTexts.metricSeries(MetricType.STEPS.name, "Kroky", "")
        val weight = ChartInfoTexts.metricSeries(MetricType.WEIGHT.name, "Váha", "kg")
        val hrv = ChartInfoTexts.metricSeries(MetricType.HRV.name, "HRV", "ms")
        assertEquals("denní hodnota: součet za den", steps.detail)
        assertEquals("denní hodnota: poslední měření dne", weight.detail)
        assertEquals("denní hodnota: průměr dne", hrv.detail)
        assertEquals(null, ChartInfoTexts.metricSeries("not-a-metric", "X", "").detail)
    }

    @Test
    fun dailyValueRuleMatchesAggregation() {
        // The generic sentence in ChartInfoTexts.DAILY_VALUE_RULE makes these claims; keep them honest.
        assertEquals(Agg.SUM, Aggregation.of(MetricType.STEPS))
        assertEquals(Agg.SUM, Aggregation.of(MetricType.SLEEP_DURATION))
        for (t in listOf(MetricType.WEIGHT, MetricType.BODY_FAT, MetricType.MUSCLE_MASS, MetricType.BODY_BATTERY,
            MetricType.RESTING_HEART_RATE, MetricType.SLEEP_SCORE)) {
            assertEquals(Agg.LAST, Aggregation.of(t), "$t")
        }
        assertEquals(Agg.AVG, Aggregation.of(MetricType.HRV))
        assertTrue("součet" in ChartInfoTexts.DAILY_VALUE_RULE && "poslední měření" in ChartInfoTexts.DAILY_VALUE_RULE)
    }

    @Test
    fun normalizedChartExplainsRelativeScale() {
        val text = ChartInfoTexts.build(ChartKey.METRICS_CORRELATION_NORMALIZED, sample).plainText()
        assertTrue("0–100 %" in text)
        assertTrue("minimum" in text && "maximum" in text)
        val physical = ChartInfoTexts.build(ChartKey.METRICS_CORRELATION, sample).plainText()
        assertFalse("0–100 %" in physical)
    }

    @Test
    fun lineChartTextFollowsItsOptions() {
        val plain = ChartInfoTexts.build(ChartKey.METRIC_HISTORY_LINE).plainText()
        assertFalse("P25–P75" in plain)
        assertFalse("Druhá křivka" in plain)

        val banded = ChartInfoTexts.build(
            ChartKey.METRIC_HISTORY_LINE,
            options = ChartInfoOptions(hasBand = true, hasSecondary = true, secondaryLabel = "Potenciální", unit = "ms", metric = MetricType.STEPS),
        ).plainText()
        assertTrue("P25–P75" in banded)
        assertTrue("alespoň 7 hodnot" in banded)
        assertTrue("Druhá křivka (Potenciální)" in banded)
        assertTrue("součet za den" in banded)
        assertTrue("jednotce ms" in banded)
    }

    @Test
    fun singlestatMentionsWindow() {
        val text = ChartInfoTexts.build(ChartKey.SINGLESTAT_SPARKLINE, options = ChartInfoOptions(windowLabel = "30d")).plainText()
        assertTrue("posledních 30d" in text)
    }

    @Test
    fun telemetryStaminaBulletOnlyWhenChannelPresent() {
        val without = ChartInfoTexts.build(ChartKey.ACTIVITY_TELEMETRY_CURVES, listOf(ChartInfoSeries("HEART_RATE", "Srdeční tep", "bpm")))
        val with = ChartInfoTexts.build(
            ChartKey.ACTIVITY_TELEMETRY_CURVES,
            listOf(ChartInfoSeries("HEART_RATE", "Srdeční tep", "bpm"), ChartInfoSeries(Channel.STAMINA.name, "Výdrž", "%")),
        )
        assertFalse("Potenciální výdrž" in without.plainText())
        assertTrue("Potenciální výdrž" in with.plainText())
        assertTrue("Dostupné kanály (1)" in without.plainText() && "Dostupné kanály (2)" in with.plainText())
    }

    @Test
    fun staticSchemaChartsSayTheyAreNotMeasuredData() {
        assertTrue("ilustrativní" in ChartInfoTexts.build(ChartKey.ECG_STRIP).intro)
        assertTrue("nejsou vypočteny z vašich měření" in ChartInfoTexts.build(ChartKey.OURA_24H_HORIZON).intro)
        assertEquals(4, ChartInfoTexts.build(ChartKey.OURA_24H_HORIZON).series.size)
    }

    @Test
    fun buttonDescriptionIsTheAgreedAccessibilityLabel() {
        assertEquals("Vysvětlení grafu", ChartInfoTexts.BUTTON_DESCRIPTION)
    }
}
