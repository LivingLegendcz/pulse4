package app.pulse4.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncScheduleTest {
    @Test
    fun defaultsAreFifteenMinutesForGarminAndOneHourForWithings() {
        assertEquals(15, SyncProvider.GARMIN.defaultMinutes)
        assertEquals(60, SyncProvider.WITHINGS.defaultMinutes)
        // Missing stored value (fresh install or upgrade from a version without the setting) -> default.
        assertEquals(15, SyncSchedule.normalize(SyncProvider.GARMIN, null))
        assertEquals(60, SyncSchedule.normalize(SyncProvider.WITHINGS, null))
    }

    @Test
    fun offIsPreservedAndSubMinimumValuesAreClampedToFifteen() {
        assertEquals(SyncSchedule.OFF, SyncSchedule.normalize(SyncProvider.WITHINGS, 0))
        assertEquals(15, SyncSchedule.normalize(SyncProvider.GARMIN, 1))
        assertEquals(15, SyncSchedule.normalize(SyncProvider.GARMIN, 14))
        assertEquals(24 * 60, SyncSchedule.normalize(SyncProvider.GARMIN, 100_000))
    }

    @Test
    fun negativeGarbageFallsBackToProviderDefault() {
        assertEquals(15, SyncSchedule.normalize(SyncProvider.GARMIN, -5))
        assertEquals(60, SyncSchedule.normalize(SyncProvider.WITHINGS, -1))
    }

    @Test
    fun everyOfferedOptionIsStableUnderNormalization() {
        for (p in SyncProvider.entries) for (o in SyncSchedule.OPTIONS) {
            assertEquals(o, SyncSchedule.normalize(p, o), "option $o must round-trip for $p")
        }
        assertTrue(SyncSchedule.OPTIONS.contains(SyncSchedule.OFF))
    }

    @Test
    fun labelsAreHumanReadable() {
        assertEquals("Vypnuto", SyncSchedule.label(0))
        assertEquals("15 min", SyncSchedule.label(15))
        assertEquals("30 min", SyncSchedule.label(30))
        assertEquals("1 h", SyncSchedule.label(60))
        assertEquals("3 h", SyncSchedule.label(180))
        assertEquals("6 h", SyncSchedule.label(360))
        assertEquals("1 h 30 min", SyncSchedule.label(90))
    }
}
