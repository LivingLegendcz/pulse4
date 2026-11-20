package app.pulse4.android.data

import app.pulse4.android.withJdbcRoomDb
import app.pulse4.ui.SyncProvider
import app.pulse4.ui.SyncSchedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsSyncScheduleTest {
    @Test
    fun missingKeysYieldProviderDefaultsSoNoMigrationIsNeeded() = withJdbcRoomDb { db ->
        val s = Settings(db)
        assertEquals(15, s.syncIntervalMinutes(SyncProvider.GARMIN))
        assertEquals(60, s.syncIntervalMinutes(SyncProvider.WITHINGS))
        assertNull(s.lastSyncAtMs(SyncProvider.GARMIN))
        assertNull(s.withingsKeepaliveAtMs)
    }

    @Test
    fun intervalsPersistPerProviderAndSurviveANewSettingsInstance() = withJdbcRoomDb { db ->
        val s = Settings(db)
        s.setSyncIntervalMinutes(SyncProvider.WITHINGS, 180)
        s.setSyncIntervalMinutes(SyncProvider.GARMIN, SyncSchedule.OFF)
        val reopened = Settings(db)
        assertEquals(180, reopened.syncIntervalMinutes(SyncProvider.WITHINGS))
        assertEquals(SyncSchedule.OFF, reopened.syncIntervalMinutes(SyncProvider.GARMIN))
    }

    @Test
    fun invalidStoredValuesAreNormalized() = withJdbcRoomDb { db ->
        val s = Settings(db)
        s.setSyncIntervalMinutes(SyncProvider.GARMIN, 3) // below the WorkManager floor
        assertEquals(15, s.syncIntervalMinutes(SyncProvider.GARMIN))
        s.put("sync.withings.intervalMin", "not-a-number")
        assertEquals(60, s.syncIntervalMinutes(SyncProvider.WITHINGS))
    }

    @Test
    fun lastSyncFallsBackToTheLegacyGlobalTimestampAfterAnUpgrade() = withJdbcRoomDb { db ->
        val s = Settings(db)
        s.lastImportAtMs = 1_234L // written by the previous version
        assertEquals(1_234L, s.lastSyncAtMs(SyncProvider.GARMIN))
        assertEquals(1_234L, s.lastSyncAtMs(SyncProvider.WITHINGS))
        s.markSynced(SyncProvider.WITHINGS, 9_999L)
        assertEquals(9_999L, s.lastSyncAtMs(SyncProvider.WITHINGS))
        assertEquals(1_234L, s.lastSyncAtMs(SyncProvider.GARMIN))
    }
}
