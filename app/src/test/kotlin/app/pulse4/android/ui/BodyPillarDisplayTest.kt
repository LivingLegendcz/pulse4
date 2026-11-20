package app.pulse4.android.ui

import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.ui.Fmt
import app.pulse4.ui.UnitSystem
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BodyPillarDisplayTest {
    private fun weight(kg: Double) = MetricObservation(
        id = "w",
        provider = Provider.WITHINGS_API,
        accountId = "u1",
        externalId = "w",
        type = MetricType.WEIGHT,
        startUtcMs = 1_000L,
        endUtcMs = null,
        value = kg,
        zoneId = "Europe/Prague",
        localDate = "2026-11-20",
    )

    private fun body(w: MetricObservation?, fmt: Fmt) =
        OuraScoreCalculator.calculate(emptyMap(), null, w, null, fmt)[OuraPillar.BODY]!!

    @Test
    fun `body ring shows the real weight and never an invented score`() {
        val s = body(weight(92.38), Fmt(locale = Locale.forLanguageTag("cs-CZ")))
        assertEquals("92,4", s.displayValue)
        assertNull(s.score)
    }

    @Test
    fun `body ring follows the imperial unit setting`() {
        val s = body(weight(100.0), Fmt(locale = Locale.forLanguageTag("cs-CZ"), units = UnitSystem.IMPERIAL))
        assertEquals("220,5", s.displayValue)
    }

    @Test
    fun `body ring stays empty without weight`() {
        val s = body(null, Fmt(locale = Locale.forLanguageTag("cs-CZ")))
        assertNull(s.displayValue)
        assertNull(s.score)
    }
}
