package app.pulse4.android.ui

import app.pulse4.android.data.ActivitySummaryRow
import app.pulse4.android.data.effectiveMovingTimeS
import app.pulse4.domain.Activity
import app.pulse4.domain.Provider
import app.pulse4.domain.Sport
import app.pulse4.sync.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OuraScoreCalculatorTest {

    private fun act(
        sport: Sport,
        title: String,
        movingTimeS: Double? = 2400.0,
        trainingLoad: Double? = null,
        aerobicTe: Double? = null,
        anaerobicTe: Double? = null,
        caloriesKcal: Double? = null,
        avgHr: Int? = null,
    ) = ActivitySummaryRow(
        id = "a1",
        startUtcMs = 1_700_000_000_000L,
        sport = sport.name,
        title = title,
        distanceM = null,
        movingTimeS = movingTimeS,
        ascentM = null,
        avgHeartRate = avgHr,
        maxHeartRate = null,
        caloriesKcal = caloriesKcal,
        aerobicTe = aerobicTe,
        anaerobicTe = anaerobicTe,
        trainingLoad = trainingLoad,
        avgPowerW = null,
        avgCadence = null,
        zoneId = null,
    )

    @Test
    fun noStepsAndNoActivitiesReturnsNull() {
        assertNull(OuraScoreCalculator.calculateActivityBreakdown(null, emptyList()))
    }

    @Test
    fun zeroStepsAndNoActivitiesIsZeroNotArtificial64() {
        val b = OuraScoreCalculator.calculateActivityBreakdown(0.0, emptyList())
        assertNotNull(b)
        assertEquals(0, b!!.stepsPoints)
        assertEquals(0, b.activitiesPoints)
        assertEquals(0, b.totalScore)
    }

    @Test
    fun tenThousandStepsGivesFixed60Points() {
        val b = OuraScoreCalculator.calculateActivityBreakdown(10_000.0, emptyList())!!
        assertEquals(60, b.stepsPoints)
        assertEquals(0, b.activitiesPoints)
        assertEquals(60, b.totalScore)
    }

    @Test
    fun breathworkBarelyMovesActivityBar() {
        val breath = act(
            sport = Sport.OTHER,
            title = "Dechové cvičení",
            movingTimeS = 600.0,
            trainingLoad = 1.0,
            aerobicTe = 0.1,
            caloriesKcal = 12.0,
            avgHr = 64,
        )
        val b = OuraScoreCalculator.calculateActivityBreakdown(0.0, listOf(breath))!!
        assertTrue("Breathwork should add at most 1 point, got ${b.activitiesPoints}", b.activitiesPoints <= 1)
    }

    @Test
    fun hardWorkoutAddsMeaningfulPointsAndCapsAt100WithSteps() {
        val run = act(
            sport = Sport.RUNNING,
            title = "Běh intervaly",
            movingTimeS = 3000.0,
            trainingLoad = 105.0,
            aerobicTe = 3.6,
            anaerobicTe = 1.8,
            caloriesKcal = 620.0,
            avgHr = 162,
        )
        val rideOnly = OuraScoreCalculator.calculateActivityBreakdown(0.0, listOf(run))!!
        assertTrue("Hard workout alone should add >= 35 pts, got ${rideOnly.activitiesPoints}", rideOnly.activitiesPoints >= 35)

        val stepsAndRun = OuraScoreCalculator.calculateActivityBreakdown(10_000.0, listOf(run))!!
        assertEquals(60, stepsAndRun.stepsPoints)
        assertEquals(100, stepsAndRun.totalScore)
    }

    @Test
    fun legacyIndoorCardioWithZeroMovingDurationRecoversTotalTimeFromJson() {
        val domainCardio = Activity(
            id = "garmin-cardio-1",
            provider = Provider.GARMIN_PERSONAL,
            accountId = "acc",
            externalId = "cardio-1",
            sport = Sport.STRENGTH,
            startUtcMs = 1_700_000_000_000L,
            zoneId = "Europe/Prague",
            totalTimeS = 2700.0,
            movingTimeS = 0.0,
            avgHeartRate = 146,
            title = "Kardio",
        )
        val row = ActivitySummaryRow(
            id = domainCardio.id,
            startUtcMs = domainCardio.startUtcMs,
            sport = domainCardio.sport.name,
            title = domainCardio.title,
            distanceM = null,
            movingTimeS = 0.0,
            ascentM = null,
            avgHeartRate = 146,
            maxHeartRate = null,
            caloriesKcal = null,
            aerobicTe = null,
            anaerobicTe = null,
            trainingLoad = null,
            avgPowerW = null,
            avgCadence = null,
            zoneId = domainCardio.zoneId,
            json = Wire.json.encodeToString(Activity.serializer(), domainCardio),
        )
        assertEquals(2700.0, row.effectiveMovingTimeS()!!, 1e-6)
        val pts = OuraScoreCalculator.scoreActivityEffort(row)
        assertTrue("Indoor cardio with 45 min HR 146 should score >= 15 pts, got $pts", pts >= 15.0)
    }
}
