package app.pulse4.android.ui

import app.pulse4.domain.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapTrackAnalyticsTest {

    @Test
    fun `continuous activity with red lights is NOT detected as pause`() {
        val t0 = 1_000_000L
        val points = mutableListOf<TrackPoint>()
        // 100 metrů chůze (1 bod za sekundu)
        for (i in 0 until 50) {
            points.add(TrackPoint(tMs = t0 + i * 1000L, lat = 50.0 + i * 0.00001, lon = 14.4, speedMps = 1.3))
        }
        // 35 sekund stání na semaforu při běžících stopkách (body se stále zapisují po 1 s, rychlost 0)
        for (i in 50 until 85) {
            points.add(TrackPoint(tMs = t0 + i * 1000L, lat = 50.0005, lon = 14.4, speedMps = 0.0))
        }
        // Dalších 50 sekund chůze
        for (i in 85 until 135) {
            points.add(TrackPoint(tMs = t0 + i * 1000L, lat = 50.0005 + (i - 85) * 0.00001, lon = 14.4, speedMps = 1.3))
        }

        // Časovač Garminu běžel kontinuálně (celkový čas = čas pohybu)
        val pauses = MapTrackAnalytics.detectPauses(points, totalTimeS = 135.0, movingTimeS = 135.0)
        assertTrue(pauses.isEmpty(), "Stání na semaforu s běžícími stopkami nesmí být detekováno jako pauza!")
    }

    @Test
    fun `actual paused watch creates real pause with resume coordinate and duration`() {
        val t0 = 1_000_000L
        val points = mutableListOf<TrackPoint>()
        // 50 sekund chůze
        for (i in 0 until 50) {
            points.add(TrackPoint(tMs = t0 + i * 1000L, lat = 50.0 + i * 0.00001, lon = 14.4, speedMps = 1.3))
        }
        // Uživatel zmáčkl PAUSE na 10 minut (600 s) a sedl si do cukrárny 30 m vedle
        // Během pauzy se nezapisují žádné trackpointy!
        val tResume = points.last().tMs + 600 * 1000L
        points.add(TrackPoint(tMs = tResume, lat = 50.0007, lon = 14.4002, speedMps = 1.2))
        // Další chůze po obnovení
        for (i in 1 until 50) {
            points.add(TrackPoint(tMs = tResume + i * 1000L, lat = 50.0007 + i * 0.00001, lon = 14.4002, speedMps = 1.3))
        }

        val totalTime = 50.0 + 600.0 + 50.0 // 700 s
        val movingTime = 100.0 // 100 s čistého času

        val pauses = MapTrackAnalytics.detectPauses(points, totalTimeS = totalTime, movingTimeS = movingTime)
        assertEquals(1, pauses.size)
        val p = pauses[0]
        assertEquals(1, p.index)
        assertEquals(600L, p.durationS)
        assertEquals(50.00049, p.lat, 1e-5)
        assertEquals(50.0007, p.resumeLat!!, 1e-5)
        assertTrue(p.displacementM > 20.0, "Měl by být detekován mírný posun během pauzy")
    }

    @Test
    fun `high speed transit through tunnel without points is NOT treated as pause`() {
        val t0 = 1_000_000L
        val points = mutableListOf<TrackPoint>()
        points.add(TrackPoint(tMs = t0, lat = 50.0, lon = 14.4, speedMps = 15.0))
        // Výpadek GPS v tunelu na 40 sekund při rychlosti vlaku/auta 20 m/s (ujeto 800 m)
        points.add(TrackPoint(tMs = t0 + 40_000L, lat = 50.007, lon = 14.4, speedMps = 18.0))

        val pauses = MapTrackAnalytics.detectPauses(points, totalTimeS = 40.0, movingTimeS = 40.0)
        assertTrue(pauses.isEmpty(), "Průjezd tunelem bez GPS nesmí být detekován jako pauza!")
    }
}
