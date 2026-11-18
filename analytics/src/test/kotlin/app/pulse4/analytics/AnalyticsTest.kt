package app.pulse4.analytics

import app.pulse4.domain.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsTest {
    @Test fun `pause does not count as moving time and missing gps is not zero`() {
        val pts = listOf(
            TrackPoint(0, 50.0, 14.0, speedMps = 3.0),
            TrackPoint(10_000, 50.0001, 14.0, speedMps = 3.0),
            TrackPoint(20_000, 50.0002, 14.0, speedMps = 3.0),
            TrackPoint(120_000, 50.0002, 14.0, speedMps = 0.0), // 100 s pauza
            TrackPoint(130_000, null, null, speedMps = 3.0, distanceM = null), // bez GPS
        )
        val s = ActivityMath.summarize(pts)
        assertEquals(130.0, s.totalTimeS)
        assertEquals(30.0, s.movingTimeS, 1e-9) // poslední úsek hlásí rychlost, jen nemá polohu
        assertTrue(s.distanceM in 20.0..25.0, "distance=${s.distanceM}") // 2 × ~11 m, žádný skok na 0°/0°
        val pace = assertNotNull(s.avgPaceSPerKm)
        assertEquals(20.0 / (s.distanceM / 1000.0), pace, 1e-6) // úsek bez vzdálenosti tempo nezkresluje
    }

    @Test fun `elevation hysteresis ignores sensor noise`() {
        val noisy = listOf(100.0, 100.8, 100.2, 101.0, 100.5, 100.9)
        assertEquals(0.0 to 0.0, ActivityMath.elevationChange(noisy, 3.0))
        val (up, down) = ActivityMath.elevationChange(listOf(100.0, 105.0, 110.0, 104.0), 3.0)
        assertEquals(10.0, up); assertEquals(6.0, down)
    }

    @Test fun `downsample keeps peaks`() {
        val s = (0 until 1000).map { it.toLong() to (if (it == 437) 999.0 else 1.0 + (it % 3)) as Double? }
        val d = Downsample.minMax(s, 20)
        assertTrue(d.size <= 40)
        assertTrue(d.any { it.second == 999.0 })
        assertEquals(d.sortedBy { it.first }, d)
    }

    @Test fun `downsample skips missing values rather than inventing zeros`() {
        val d = Downsample.minMax(listOf(0L to 5.0, 1L to null, 2L to 7.0), 10)
        assertEquals(listOf(0L to 5.0, 2L to 7.0), d)
    }

    @Test fun `time in zones skips gaps and missing samples`() {
        val zones = Zones.ZoneSet(listOf(120.0, 150.0))
        val s = listOf<Pair<Long, Double?>>(
            0L to 100.0, 10_000L to 130.0, 20_000L to null, 30_000L to 160.0, 40_000L to 160.0, 400_000L to 160.0,
        )
        val t = Zones.timeInZones(s, zones)
        assertEquals(10.0, t[0]); assertEquals(0.0, t[1]) // 10→20 s a 20→30 s mají null → vynecháno
        assertEquals(10.0, t[2]) // 30→40 s; 40→400 s je mezera → vynecháno
    }

    @Test fun `best window requires continuous data`() {
        val s = listOf<Pair<Long, Double?>>(
            0L to 100.0, 5_000L to 100.0, 10_000L to 200.0, 15_000L to 200.0, 20_000L to 200.0, 25_000L to 100.0,
        )
        val seg = assertNotNull(BestEfforts.bestWindow(s, 10))
        assertEquals(200.0, seg.average, 1e-9)
        assertEquals(10_000L, seg.startMs)
        assertNull(BestEfforts.bestWindow(listOf(0L to 1.0, 5_000L to null, 10_000L to 1.0), 10))
    }

    @Test fun `usual range needs enough history`() {
        assertNull(Baseline.usualRange(listOf(1.0, 2.0, 3.0)))
        val r = assertNotNull(Baseline.usualRange((1..9).map { it.toDouble() }))
        assertEquals(3.0, r.low); assertEquals(5.0, r.median); assertEquals(7.0, r.high)
    }

    @Test fun `D1 enrichTrack never fabricates dynamics or cycling metrics`() {
        val pts = listOf(
            TrackPoint(0L, speedMps = 3.0, cadence = 170),
            TrackPoint(10_000L, speedMps = 3.1, cadence = 172),
        )
        val out = RunningDynamicsModel.enrichTrack(pts, app.pulse4.domain.Sport.RUNNING, maxHr = 175)
        assertNull(out[0].groundContactBalancePct)
        assertNull(out[0].groundContactTimeMs)
        assertNull(out[0].verticalOscillationCm)
        assertNull(out[0].staminaPct)
        assertNull(out[0].performanceCondition)
    }

    @Test fun `HLT-5 relative effort returns null without HR zones`() {
        assertNull(RunningDynamicsModel.computeStravaRelativeEffort(emptyList(), 3600.0))
        assertNull(RunningDynamicsModel.computeStravaRelativeEffort(listOf(0.0, 0.0, 0.0, 0.0, 0.0), 3600.0))
        assertNotNull(RunningDynamicsModel.computeStravaRelativeEffort(listOf(600.0, 600.0, 0.0, 0.0, 0.0), 1200.0))
    }

    @Test fun `D2 GAP uses real grade from elevation`() {
        val uphill = listOf(
            TrackPoint(0L, speedMps = 3.0, distanceM = 0.0, elevationM = 100.0),
            TrackPoint(100_000L, speedMps = 3.0, distanceM = 300.0, elevationM = 130.0), // 10% grade
        )
        val flat = listOf(
            TrackPoint(0L, speedMps = 3.0, distanceM = 0.0, elevationM = 100.0),
            TrackPoint(100_000L, speedMps = 3.0, distanceM = 300.0, elevationM = 100.0),
        )
        val gapUphill = assertNotNull(RunningDynamicsModel.computeAverageGapPaceSPerKm(uphill))
        val gapFlat = assertNotNull(RunningDynamicsModel.computeAverageGapPaceSPerKm(flat))
        assertTrue(gapUphill < gapFlat, "Uphill GAP pace ($gapUphill) should be faster than flat ($gapFlat)")
    }

    @Test fun `D4 findBestEfforts keeps missing elevation as null`() {
        val pts = listOf(
            TrackPoint(0L, distanceM = 0.0, elevationM = null),
            TrackPoint(100_000L, distanceM = 450.0, elevationM = 250.0),
        )
        val efforts = RunningDynamicsModel.findBestEfforts(pts)
        assertEquals(1, efforts.size)
        assertNull(efforts.single().elevationChangeM)
    }
}
