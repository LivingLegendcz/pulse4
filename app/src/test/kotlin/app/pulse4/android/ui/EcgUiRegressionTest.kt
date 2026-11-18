package app.pulse4.android.ui

import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
}
