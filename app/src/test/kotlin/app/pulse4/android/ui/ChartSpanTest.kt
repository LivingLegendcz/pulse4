package app.pulse4.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartSpanTest {

    @Test
    fun `7-day series with missing leading and trailing days stretches to full width`() {
        val values = listOf(null, 80.1, null, 79.8, 79.5, null, null)
        val span = activeFiniteSpan(listOf(values))!!

        assertEquals(ChartIndexSpan(1, 4), span)
        assertEquals(0f, span.fraction(1), 1e-6f)
        assertEquals(1f, span.fraction(4), 1e-6f)
        // Internal gap at index 2 keeps proportional spacing: (3 - 1) / (4 - 1) = 2/3
        assertEquals(2f / 3f, span.fraction(3), 1e-6f)
        assertEquals(1, span.indexForFraction(0f))
        assertEquals(3, span.indexForFraction(2f / 3f))
        assertEquals(4, span.indexForFraction(1f))
    }

    @Test
    fun `7-day series with all days present spans 0 to 6`() {
        val values = listOf(70.0, 70.1, 70.2, 70.3, 70.4, 70.5, 70.6)
        val span = activeFiniteSpan(listOf(values))!!

        assertEquals(ChartIndexSpan(0, 6), span)
        assertEquals(0f, span.fraction(0), 1e-6f)
        assertEquals(0.5f, span.fraction(3), 1e-6f)
        assertEquals(1f, span.fraction(6), 1e-6f)
    }

    @Test
    fun `single measurement centers horizontally and maps taps to that index`() {
        val values = listOf(null, null, 81.2, null, null, null, null)
        val span = activeFiniteSpan(listOf(values))!!

        assertEquals(ChartIndexSpan(2, 2), span)
        assertEquals(0.5f, span.fraction(2), 1e-6f)
        assertEquals(2, span.indexForFraction(0.1f))
        assertEquals(2, span.indexForFraction(0.9f))
    }

    @Test
    fun `multi-series span uses union of all visible series`() {
        val s1 = listOf(null, 55.0, null, 58.0, null, null, null)
        val s2 = listOf(null, null, 61.0, null, null, 62.0, null)
        val span = activeFiniteSpan(listOf(s1, s2))!!

        assertEquals(ChartIndexSpan(1, 5), span)
        assertEquals(0f, span.fraction(1), 1e-6f)
        assertEquals(1f, span.fraction(5), 1e-6f)
    }

    @Test
    fun `all null or non-finite series returns null`() {
        assertNull(activeFiniteSpan(listOf(listOf(null, Double.NaN, Double.POSITIVE_INFINITY, null))))
    }
}
