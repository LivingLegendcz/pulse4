package app.pulse4.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChartInfoTest {

    @Test
    fun everyChartKeyHasShortPlainLanguageExplanation() {
        for (key in ChartKey.entries) {
            val info = ChartInfoTexts.build(key)
            assertEquals(key, info.key)
            assertTrue(info.title.isNotBlank(), "title of $key")
            assertTrue(info.intro.isNotBlank(), "intro of $key")
            assertTrue(info.bullets.all { it.isNotBlank() }, "blank bullet in $key")
            assertTrue(info.plainText().length in 40..360, "text length of $key was ${info.plainText().length}")
            assertTrue(info.bullets.size <= 1, "too many default bullets for $key")
        }
    }

    @Test
    fun correlationChartsExplainThatCurvesShowChanges() {
        val physical = ChartInfoTexts.build(ChartKey.METRICS_CORRELATION).plainText()
        assertTrue("roste nebo klesá" in physical)
        assertFalse("0–100 %" in physical)

        val normalized = ChartInfoTexts.build(ChartKey.METRICS_CORRELATION_NORMALIZED).plainText()
        assertTrue("0–100 %" in normalized)
        assertTrue("směr změn" in normalized)
    }

    @Test
    fun lineChartTextFollowsItsOptions() {
        val plain = ChartInfoTexts.build(ChartKey.METRIC_HISTORY_LINE)
        assertTrue(plain.bullets.isEmpty())
        assertFalse("obvyklé rozmezí" in plain.plainText())
        assertFalse("Druhá čára" in plain.plainText())

        val detailed = ChartInfoTexts.build(
            ChartKey.METRIC_HISTORY_LINE,
            options = ChartInfoOptions(hasBand = true, hasSecondary = true, secondaryLabel = "Potenciální", isScatter = true),
        ).plainText()
        assertTrue("obvyklé rozmezí" in detailed)
        assertTrue("Druhá čára" in detailed)
        assertTrue("Potenciální" in detailed)
        assertTrue("Jednotlivé body" in detailed)
    }

    @Test
    fun weightCompositionExplainsWholeChartInPlainCzech() {
        val info = ChartInfoTexts.build(ChartKey.WEIGHT_COMPOSITION_STACKED_AREA)
        val text = info.plainText()
        assertTrue("celkovou váhu" in text)
        assertTrue("svalů" in text && "tuku" in text)
        assertTrue(text.length < 360)
    }

    @Test
    fun ecgStripSaysItIsIllustrative() {
        val intro = ChartInfoTexts.build(ChartKey.ECG_STRIP).intro
        assertTrue("ukázkový obrázek" in intro)
        assertTrue("ne o váš skutečný záznam" in intro)
    }

    @Test
    fun buttonDescriptionIsTheAgreedAccessibilityLabel() {
        assertEquals("Vysvětlení grafu", ChartInfoTexts.BUTTON_DESCRIPTION)
    }
}
