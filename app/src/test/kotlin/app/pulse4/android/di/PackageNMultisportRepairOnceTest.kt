package app.pulse4.android.di

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K leftover (R4-10): the network-backed legacy multisport repair (R3-7) runs once, not on every sync. */
class PackageNMultisportRepairOnceTest {

    @Test
    fun `repair runs once and sets its flag, a failed run is retried`() {
        val flags = HashMap<String, String>()
        var runs = 0
        val flag = ImportService.MULTISPORT_NET_REPAIR_FLAG

        val failed = runCatching {
            ImportService.runOnceWithFlag(flags::get, { k, v -> flags[k] = v }, flag) {
                runs++
                error("network down")
            }
        }
        assertTrue(failed.isFailure)
        assertNull(flags[flag])

        assertTrue(ImportService.runOnceWithFlag(flags::get, { k, v -> flags[k] = v }, flag) { runs++ })
        assertEquals("true", flags[flag])
        repeat(3) {
            assertFalse(ImportService.runOnceWithFlag(flags::get, { k, v -> flags[k] = v }, flag) { runs++ })
        }
        assertEquals(2, runs)
    }

    @Test
    fun `syncGarminActivities wraps the network repair in the once-flag`() {
        val src = File("src/main/kotlin/app/pulse4/android/di/Services.kt").readText()
        val idx = src.indexOf("fetchGarminSummary = { actId ->")
        assertTrue(idx > 0)
        val guard = src.lastIndexOf("runOnceWithFlag(sl.settings::getRaw, sl.settings::put, MULTISPORT_NET_REPAIR_FLAG)", idx)
        assertTrue("network repair must be guarded by MULTISPORT_NET_REPAIR_FLAG", guard in 0 until idx && idx - guard < 400)
    }
}
