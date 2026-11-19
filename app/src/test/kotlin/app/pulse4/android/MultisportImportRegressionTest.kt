package app.pulse4.android

import app.pulse4.android.data.ActivityRow
import app.pulse4.android.data.ActivitySummaryRow
import app.pulse4.android.data.TrackRow
import app.pulse4.android.di.ActivityImportStore
import app.pulse4.android.di.FitImportResult
import app.pulse4.android.di.ImportService
import app.pulse4.android.ui.filterTopLevelActivities
import app.pulse4.domain.Activity
import app.pulse4.domain.Sport
import app.pulse4.domain.TrackPoint
import app.pulse4.fit.FitImporter
import app.pulse4.fit.FitResult
import app.pulse4.fit.ParsedFit
import app.pulse4.sync.Wire
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MultisportImportRegressionTest {
    private class InMemoryActivityStore : ActivityImportStore {
        val activities = LinkedHashMap<String, ActivityRow>()
        val tracks = LinkedHashMap<String, TrackRow>()
        override fun get(id: String): ActivityRow? = activities[id]
        override fun track(id: String): TrackRow? = tracks[id]
        override fun upsert(row: ActivityRow) { activities[row.id] = row }
        override fun upsertTrack(row: TrackRow) { tracks[row.activityId] = row }
        override fun inTransaction(block: () -> Unit) = block()
    }

    private fun garminMultisportSummaryJson(activityId: String = "999888"): JsonObject {
        val raw = """
            {
              "activityId": $activityId,
              "activityName": "Praha Triatlon",
              "startTimeGMT": "2026-08-15 07:00:00",
              "activityType": { "typeKey": "multi_sport", "parentTypeId": 89 },
              "distance": 51500.0,
              "duration": 7800.0,
              "movingDuration": 7650.0,
              "elevationGain": 420.0,
              "averageHR": 152.0,
              "maxHR": 181.0,
              "calories": 1850.0,
              "aerobicTrainingEffect": 4.2,
              "anaerobicTrainingEffect": 2.1,
              "activityTrainingLoad": 215.0
            }
        """.trimIndent()
        return Json.parseToJsonElement(raw).jsonObject
    }

    /** Builds a valid binary FIT file containing 2 sessions (cycling + running) with valid FIT CRC-16. */
    private fun buildTwoSessionMultisportFitBytes(): ByteArray {
        fun crc16(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Int {
            val table = intArrayOf(
                0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
                0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
            )
            var crc = 0
            for (i in start until end) {
                val b = bytes[i].toInt() and 0xFF
                var tmp = table[crc and 0xF]
                crc = (crc ushr 4) and 0x0FFF
                crc = crc xor tmp xor table[b and 0xF]
                tmp = table[crc and 0xF]
                crc = (crc ushr 4) and 0x0FFF
                crc = crc xor tmp xor table[(b ushr 4) and 0xF]
            }
            return crc
        }

        fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)
        fun ByteArrayOutputStream.u16(v: Int) { u8(v); u8(v ushr 8) }
        fun ByteArrayOutputStream.u32(v: Long) { u8(v.toInt()); u8((v ushr 8).toInt()); u8((v ushr 16).toInt()); u8((v ushr 24).toInt()) }

        val data = ByteArrayOutputStream()
        // Local 0 = FileId (mesg 0): type(0, enum, 1B), manufacturer(1, uint16, 2B)
        data.u8(0x40); data.u8(0); data.u8(0); data.u16(0); data.u8(2)
        data.u8(0); data.u8(1); data.u8(0x00)
        data.u8(1); data.u8(2); data.u8(0x84)
        data.u8(0x00); data.u8(4); data.u16(1)

        // Local 1 = Record (mesg 20): timestamp(253, uint32, 4B), position_lat(0, sint32, 4B), position_long(1, sint32, 4B), heart_rate(3, uint8, 1B)
        data.u8(0x41); data.u8(0); data.u8(0); data.u16(20); data.u8(4)
        data.u8(253); data.u8(4); data.u8(0x86)
        data.u8(0); data.u8(4); data.u8(0x85)
        data.u8(1); data.u8(4); data.u8(0x85)
        data.u8(3); data.u8(1); data.u8(0x02)

        val t0 = 1_000_000_000L
        fun sc(deg: Double) = (deg / 180.0 * 2147483648.0).toLong()
        // Record 1 (in session 1)
        data.u8(0x01); data.u32(t0 + 10); data.u32(sc(45.001)); data.u32(sc(15.001)); data.u8(145)
        // Record 2 (in session 2)
        data.u8(0x01); data.u32(t0 + 4000); data.u32(sc(45.010)); data.u32(sc(15.010)); data.u8(165)

        // Local 2 = Session (mesg 18): timestamp(253, uint32), start_time(2, uint32), sport(5, enum), total_elapsed_time(7, uint32), total_timer_time(8, uint32), total_distance(9, uint32)
        data.u8(0x42); data.u8(0); data.u8(0); data.u16(18); data.u8(6)
        data.u8(253); data.u8(4); data.u8(0x86)
        data.u8(2); data.u8(4); data.u8(0x86)
        data.u8(5); data.u8(1); data.u8(0x00)
        data.u8(7); data.u8(4); data.u8(0x86)
        data.u8(8); data.u8(4); data.u8(0x86)
        data.u8(9); data.u8(4); data.u8(0x86)

        // Session 1: Cycling (sport=2), elapsed=3600s, timer=3550s, dist=40000m
        data.u8(0x02); data.u32(t0 + 3600); data.u32(t0); data.u8(2); data.u32(3600_000L); data.u32(3550_000L); data.u32(40000_00L)
        // Session 2: Running (sport=1), elapsed=2400s, timer=2350s, dist=10000m
        data.u8(0x02); data.u32(t0 + 6000); data.u32(t0 + 3600); data.u8(1); data.u32(2400_000L); data.u32(2350_000L); data.u32(10000_00L)

        val dataBytes = data.toByteArray()
        val header = ByteArrayOutputStream().apply {
            u8(14); u8(0x20); u16(2124); u32(dataBytes.size.toLong())
            write(".FIT".toByteArray(Charsets.US_ASCII))
        }.toByteArray()
        val headerCrc = crc16(header)
        val body = ByteArrayOutputStream().apply {
            write(header); u16(headerCrc); write(dataBytes)
        }.toByteArray()
        val fileCrc = crc16(body)
        return ByteArrayOutputStream().apply {
            write(body); u16(fileCrc)
        }.toByteArray()
    }

    private fun toSummaryRow(r: ActivityRow): ActivitySummaryRow = ActivitySummaryRow(
        id = r.id,
        startUtcMs = r.startUtcMs,
        sport = r.sport,
        title = r.title,
        distanceM = r.distanceM,
        movingTimeS = r.movingTimeS,
        ascentM = r.ascentM,
        avgHeartRate = r.avgHeartRate,
        maxHeartRate = r.maxHeartRate,
        caloriesKcal = r.caloriesKcal,
        aerobicTe = r.aerobicTe,
        anaerobicTe = r.anaerobicTe,
        trainingLoad = r.trainingLoad,
        avgPowerW = r.avgPowerW,
        avgCadence = r.avgCadence,
        zoneId = r.zoneId,
    )

    @Test
    fun `RE-4 syncGarminActivityElements and lazy ensureActivityTrackWith produce identical multisport parent and child legs without double counting`() {
        val fitBytes = buildTwoSessionMultisportFitBytes()
        val parsedCheck = assertIs<FitResult.Ok>(FitImporter.parse(fitBytes))
        assertEquals(2, parsedCheck.sessions.size)

        val garminObj = garminMultisportSummaryJson("999888")
        val nowMs = 1_786_780_000_000L

        // Path A: Sync path downloads FIT immediately during sync
        val syncStore = InMemoryActivityStore()
        ImportService.syncGarminActivityElements(
            allActivityElements = listOf(garminObj),
            isInitialSync = true,
            nowMs = nowMs,
            store = syncStore,
            accountId = "acc-1",
            zoneId = "Europe/Prague",
            downloadFit = { fitBytes },
            fetchDetailsJson = { "" },
        )

        // Path B: Initial sync without FIT followed by lazy ensureActivityTrackWith
        val lazyStore = InMemoryActivityStore()
        ImportService.syncGarminActivityElements(
            allActivityElements = listOf(garminObj),
            isInitialSync = true,
            nowMs = nowMs,
            store = lazyStore,
            accountId = "acc-1",
            zoneId = "Europe/Prague",
            downloadFit = { null },
            fetchDetailsJson = { "" },
        )
        assertTrue(
            ImportService.ensureActivityTrackWith(
                actKey = "garmin-999888",
                store = lazyStore,
                hasSession = true,
                downloadFit = { fitBytes },
                fetchDetailsJson = { "" },
                nowMs = nowMs,
                accountId = "acc-1",
                zoneId = "Europe/Prague",
            )
        )
        // Calling ensureActivityTrackWith on a leg must be a safe no-op
        assertFalse(
            ImportService.ensureActivityTrackWith(
                actKey = "garmin-999888-s1",
                store = lazyStore,
                hasSession = true,
                downloadFit = { fitBytes },
                fetchDetailsJson = { "" },
                nowMs = nowMs,
                accountId = "acc-1",
                zoneId = "Europe/Prague",
            )
        )

        assertEquals(syncStore.activities.keys, lazyStore.activities.keys)
        assertEquals(setOf("garmin-999888", "garmin-999888-s1", "garmin-999888-s2"), syncStore.activities.keys)

        for (key in syncStore.activities.keys) {
            val sRow = syncStore.activities.getValue(key)
            val lRow = lazyStore.activities.getValue(key)
            assertEquals(sRow.title, lRow.title)
            assertEquals(sRow.distanceM, lRow.distanceM)
            assertEquals(sRow.movingTimeS, lRow.movingTimeS)
            assertEquals(sRow.aerobicTe, lRow.aerobicTe)
            assertEquals(sRow.trainingLoad, lRow.trainingLoad)
            assertEquals(syncStore.tracks[key]?.json, lazyStore.tracks[key]?.json)
        }

        // Parent retains Garmin's overall summary (name, TE, load, total distance/duration)
        val parentRow = syncStore.activities.getValue("garmin-999888")
        assertEquals("Praha Triatlon", parentRow.title)
        assertEquals(51500.0, parentRow.distanceM)
        assertEquals(7650.0, parentRow.movingTimeS)
        assertEquals(4.2, parentRow.aerobicTe)
        assertEquals(215.0, parentRow.trainingLoad)
        assertNotNull(syncStore.tracks["garmin-999888"])

        // Legs carry child tags and parent reference
        val leg1 = Wire.json.decodeFromString(Activity.serializer(), syncStore.activities.getValue("garmin-999888-s1").json)
        val leg2 = Wire.json.decodeFromString(Activity.serializer(), syncStore.activities.getValue("garmin-999888-s2").json)
        assertEquals(Sport.CYCLING, leg1.sport)
        assertEquals(Sport.RUNNING, leg2.sport)
        assertTrue("multisport-leg" in leg1.tags)
        assertTrue("parent:garmin-999888" in leg1.tags)
        assertTrue("multisport-leg" in leg2.tags)

        // Top-level filter excludes child legs so totals equal Garmin summary without double counting
        val topLevel = filterTopLevelActivities(syncStore.activities.values.map(::toSummaryRow))
        assertEquals(1, topLevel.size)
        assertEquals(51500.0, topLevel.sumOf { it.distanceM ?: 0.0 })
        assertEquals(7650.0, topLevel.sumOf { it.movingTimeS ?: 0.0 })
    }

    @Test
    fun `RE-4 manual 3-leg multisport FIT creates parent summary and excludes legs from totals`() {
        val swim = ParsedFit(
            sport = Sport.SWIMMING, startMs = 1_000L, totalTimeS = 1800.0, movingTimeS = 1750.0,
            distanceM = 1500.0, ascentM = null, avgHeartRate = 135, maxHeartRate = 155,
            points = listOf(TrackPoint(tMs = 1_000L)), laps = emptyList(), caloriesKcal = 350.0,
        )
        val bike = ParsedFit(
            sport = Sport.CYCLING, startMs = 2_000_000L, totalTimeS = 3600.0, movingTimeS = 3500.0,
            distanceM = 40000.0, ascentM = 320.0, avgHeartRate = 150, maxHeartRate = 172,
            points = listOf(TrackPoint(tMs = 2_000_000L, lat = 45.0, lon = 15.0)), laps = emptyList(), caloriesKcal = 900.0,
        )
        val run = ParsedFit(
            sport = Sport.RUNNING, startMs = 5_700_000L, totalTimeS = 2400.0, movingTimeS = 2400.0,
            distanceM = 10000.0, ascentM = 100.0, avgHeartRate = 165, maxHeartRate = 181,
            points = listOf(TrackPoint(tMs = 5_700_000L, lat = 45.01, lon = 15.01)), laps = emptyList(), caloriesKcal = 600.0,
        )
        val store = InMemoryActivityStore()
        val res = ImportService.importParsedFitSessions(
            parsed = FitResult.Ok(parsed = swim, sessions = listOf(swim, bike, run)),
            hash = "abc123",
            nowMs = 9_000_000L,
            garminObj = null,
            targetId = null,
            store = store,
            accountId = "acc-1",
            zoneId = "Europe/Prague",
        )
        assertEquals(FitImportResult.Imported("fit-abc123"), res)
        assertEquals(setOf("fit-abc123", "fit-abc123-s1", "fit-abc123-s2", "fit-abc123-s3"), store.activities.keys)
        val topLevel = filterTopLevelActivities(store.activities.values.map(::toSummaryRow))
        assertEquals(1, topLevel.size)
        assertEquals(51500.0, topLevel.sumOf { it.distanceM ?: 0.0 })
        assertEquals(7650.0, topLevel.sumOf { it.movingTimeS ?: 0.0 })
        assertEquals(420.0, topLevel.sumOf { it.ascentM ?: 0.0 })
        assertEquals(1850.0, topLevel.sumOf { it.caloriesKcal ?: 0.0 })
    }
}
