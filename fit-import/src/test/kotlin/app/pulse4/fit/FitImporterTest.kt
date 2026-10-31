package app.pulse4.fit

import app.pulse4.domain.Sport
import com.garmin.fit.DateTime
import com.garmin.fit.File
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.LapMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FitImporterTest {
    private val t0 = 1_000_000_000L // FIT sekundy od 1989-12-31
    private fun sc(deg: Double) = (deg / 180.0 * 2147483648.0).toInt()

    private fun build(withSession: Boolean = true): ByteArray {
        val f = java.io.File.createTempFile("pulse4-test", ".fit")
        try {
            val enc = FileEncoder(f, Fit.ProtocolVersion.V2_0)
            enc.write(FileIdMesg().apply { type = File.ACTIVITY; manufacturer = Manufacturer.GARMIN; product = 1; serialNumber = 123L; timeCreated = DateTime(t0) })
            for (i in 0 until 5) {
                enc.write(RecordMesg().apply {
                    timestamp = DateTime(t0 + i * 5)
                    if (i != 3) { positionLat = sc(50.0 + i * 0.0001); positionLong = sc(14.0) } // 4. záznam bez GPS
                    heartRate = (140 + i).toShort()
                    enhancedSpeed = 3.0f
                    distance = i * 15f
                    enhancedAltitude = 200f + i
                })
            }
            enc.write(LapMesg().apply { startTime = DateTime(t0); totalElapsedTime = 20f; totalDistance = 60f; startPositionLat = sc(50.0); startPositionLong = sc(14.0); avgHeartRate = 142.toShort() })
            if (withSession) enc.write(SessionMesg().apply {
                startTime = DateTime(t0); sport = com.garmin.fit.Sport.RUNNING; totalElapsedTime = 20f; totalTimerTime = 18f
                totalDistance = 60f; totalAscent = 4; avgHeartRate = 142.toShort(); maxHeartRate = 144.toShort()
            })
            enc.close()
            return f.readBytes()
        } finally { f.delete() }
    }

    @Test fun `decodes records laps and session, missing gps stays null`() {
        val r = assertIs<FitResult.Ok>(FitImporter.parse(build())).parsed
        assertEquals(Sport.RUNNING, r.sport)
        assertEquals(5, r.points.size)
        assertEquals(20.0, r.totalTimeS); assertEquals(18.0, r.movingTimeS); assertEquals(60.0, r.distanceM)
        assertEquals(142, r.avgHeartRate)
        val noGps = r.points[3]
        assertNull(noGps.lat); assertNull(noGps.lon); assertEquals(false, noGps.hasPosition)
        assertEquals(50.0, assertNotNull(r.points[0].lat), 1e-6)
        assertEquals(14.0, assertNotNull(r.points[0].lon), 1e-6)
        assertEquals(1, r.laps.size); assertEquals(60.0, r.laps[0].distanceM); assertEquals(50.0, assertNotNull(r.laps[0].startLat), 1e-6)
        assertTrue(r.points.zipWithNext().all { (a, b) -> a.tMs < b.tMs })
    }

    @Test fun `without a session message summary is computed from records`() {
        val r = assertIs<FitResult.Ok>(FitImporter.parse(build(withSession = false))).parsed
        assertEquals(Sport.OTHER, r.sport)
        assertEquals(20.0, r.totalTimeS)
        assertTrue(assertNotNull(r.distanceM) > 0)
    }

    @Test fun `corrupt oversized empty and non fit input is rejected without throwing`() {
        val good = build()
        assertIs<FitResult.Invalid>(FitImporter.parse(ByteArray(0)))
        assertIs<FitResult.Invalid>(FitImporter.parse("hello world, not fit".toByteArray()))
        val flipped = good.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() }
        assertIs<FitResult.Invalid>(FitImporter.parse(flipped))
        assertIs<FitResult.Invalid>(FitImporter.parse(good.copyOf(good.size - 7)))
        assertIs<FitResult.Invalid>(FitImporter.parse(ByteArray(FitImporter.MAX_BYTES + 1)))
    }
}
