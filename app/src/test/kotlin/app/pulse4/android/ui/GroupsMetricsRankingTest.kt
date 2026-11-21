package app.pulse4.android.ui

import app.pulse4.sharing.Field
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupsMetricsRankingTest {

    @Test
    fun bodyCompositionFieldsAreNeverRankedAsLeaderboard() {
        assertFalse(isRankedGroupField(Field.WEIGHT))
        assertFalse(isRankedGroupField(Field.BODY_FAT))
    }

    @Test
    fun standardTelemetryFieldsKeepLeaderboard() {
        assertTrue(isRankedGroupField(Field.HRV_DAILY))
        assertTrue(isRankedGroupField(Field.SLEEP_DURATION))
        assertTrue(isRankedGroupField(Field.BB_LAST))
        assertTrue(isRankedGroupField(Field.HR_DAILY))
    }
}
