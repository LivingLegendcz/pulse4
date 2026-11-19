package app.pulse4.android.ui

import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.ui.Fmt
import app.pulse4.ui.MetricSeries
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EcgUiRegressionTest {
    private fun obs(
        id: String,
        type: MetricType,
        startUtcMs: Long,
        value: Double,
        deleted: Boolean = false,
    ) = MetricObservation(
        id = id,
        provider = Provider.WITHINGS_API,
        accountId = "u1",
        externalId = id,
        type = type,
        startUtcMs = startUtcMs,
        endUtcMs = null,
        value = value,
        zoneId = "Europe/Prague",
        localDate = "2026-11-18",
        deleted = deleted,
        origin = "withings:ecg:sig=1:afib=0:hr=68",
    )

    @Test
    fun `RE-2 pickLatestEcgObservation prefers typed ECG_QTC over deleted legacy ECG and ties by priority`() {
        val rows = listOf(
            obs("legacy", MetricType.ECG, startUtcMs = 2_000L, value = 410.0, deleted = true),
            obs("hr", MetricType.ECG_HR, startUtcMs = 1_500L, value = 68.0),
            obs("qtc", MetricType.ECG_QTC, startUtcMs = 1_500L, value = 412.0),
            obs("afib", MetricType.ECG_AFIB, startUtcMs = 1_500L, value = 0.0),
            obs("qrs", MetricType.ECG_QRS, startUtcMs = 1_500L, value = 88.0),
        )
        val picked = assertNotNull(pickLatestEcgObservation(rows))
        assertEquals(MetricType.ECG_QTC, picked.type)
        assertEquals(412.0, picked.value)
    }

    @Test
    fun `RE-2 pickLatestEcgObservation selects newer typed ECG_HR over older ECG_QTC`() {
        val rows = listOf(
            obs("qtc-old", MetricType.ECG_QTC, startUtcMs = 1_000L, value = 405.0),
            obs("hr-new", MetricType.ECG_HR, startUtcMs = 3_000L, value = 72.0),
        )
        val picked = assertNotNull(pickLatestEcgObservation(rows))
        assertEquals(MetricType.ECG_HR, picked.type)
        assertEquals(72.0, picked.value)
    }

    @Test
    fun `RE-2 pickLatestEcgObservation ignores non-ECG and deleted rows`() {
        val rows = listOf(
            obs("w", MetricType.WEIGHT, startUtcMs = 5_000L, value = 75.0),
            obs("del", MetricType.ECG_QTC, startUtcMs = 4_000L, value = 410.0, deleted = true),
        )
        assertNull(pickLatestEcgObservation(rows))
    }

    @Test
    fun `RE-2 mergeEcgHistoryObservations includes typed ECG_star rows when legacy ECG is empty and groups by signal`() {
        val rows = listOf(
            obs("sig1-hr", MetricType.ECG_HR, startUtcMs = 1_000L, value = 64.0).copy(origin = "withings:ecg:sig=101:afib=0:hr=64"),
            obs("sig1-qtc", MetricType.ECG_QTC, startUtcMs = 1_000L, value = 410.0).copy(origin = "withings:ecg:sig=101:afib=0:hr=64"),
            obs("sig1-afib", MetricType.ECG_AFIB, startUtcMs = 1_000L, value = 0.0).copy(origin = "withings:ecg:sig=101:afib=0:hr=64"),
            obs("sig2-afib", MetricType.ECG_AFIB, startUtcMs = 2_000L, value = 1.0).copy(origin = "withings:ecg:sig=202:afib=1:hr=92"),
            obs("sig3-legacy", MetricType.ECG, startUtcMs = 500L, value = 405.0).copy(origin = "withings:ecg:sig=55:afib=0:hr=60"),
            obs("sig4-del", MetricType.ECG_QTC, startUtcMs = 3_000L, value = 430.0, deleted = true).copy(origin = "withings:ecg:sig=303:afib=0:hr=70"),
        )
        val merged = mergeEcgHistoryObservations(rows)
        assertEquals(3, merged.size)
        assertEquals(listOf("sig2-afib", "sig1-qtc", "sig3-legacy"), merged.map { it.id })
    }

    @Test
    fun `NEW-13 resolveHrZonesForUi returns empty without Garmin zones and never falls back to 185`() {
        assertTrue(resolveHrZonesForUi(emptyList()).isEmpty())
        assertTrue(resolveHrZonesForUi(listOf(0.0, 0.0, 0.0, 0.0, 0.0)).isEmpty())
        assertEquals(listOf(10.0, 20.0, 30.0, 40.0, 50.0), resolveHrZonesForUi(listOf(10.0, 20.0, 30.0, 40.0, 50.0)))
    }

    @Test
    fun `NEW-13 OuraScoreCalculator never hardcodes GOOD bodyRating without basis`() {
        val fmt = Fmt(ZoneId.of("Europe/Prague"))
        val empty = OuraScoreCalculator.calculate(emptyMap(), null, null, null, fmt)
        assertEquals(OuraRating.NO_DATA, empty.getValue(OuraPillar.BODY).rating)

        val singleWeight = obs("w1", MetricType.WEIGHT, 1_000L, 115.0)
        val withWeightOnly = OuraScoreCalculator.calculate(emptyMap(), null, singleWeight, null, fmt)
        assertNull(withWeightOnly.getValue(OuraPillar.BODY).rating)

        val afibObs = obs("afib1", MetricType.ECG_AFIB, 2_000L, 1.0)
        val withAfib = OuraScoreCalculator.calculate(emptyMap(), null, singleWeight, afibObs, fmt)
        assertEquals(OuraRating.ATTENTION, withAfib.getValue(OuraPillar.BODY).rating)

        val today = LocalDate.of(2026, 11, 18)
        val highPwvSeries = MetricSeries.build(
            MetricType.PULSE_WAVE_VELOCITY,
            listOf(obs("pwv1", MetricType.PULSE_WAVE_VELOCITY, 2_000L, 11.2).copy(localDate = today.toString())),
            today,
            7,
        )
        val withHighPwv = OuraScoreCalculator.calculate(
            mapOf(MetricType.PULSE_WAVE_VELOCITY to highPwvSeries),
            null,
            singleWeight,
            null,
            fmt,
        )
        assertEquals(OuraRating.ATTENTION, withHighPwv.getValue(OuraPillar.BODY).rating)
    }
}
