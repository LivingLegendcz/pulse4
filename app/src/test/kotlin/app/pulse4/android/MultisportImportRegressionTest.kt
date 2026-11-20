package app.pulse4.android

import app.pulse4.android.data.ActivityDao
import app.pulse4.android.data.ActivityRow
import app.pulse4.android.data.ActivitySummaryRow
import app.pulse4.android.data.AreaStateDao
import app.pulse4.android.data.AreaStateRow
import app.pulse4.android.data.FamilyLinkDao
import app.pulse4.android.data.FamilyLinkRow
import app.pulse4.android.data.GrantDao
import app.pulse4.android.data.GrantRow
import app.pulse4.android.data.GroupRow
import app.pulse4.android.data.IdAndJson
import app.pulse4.android.data.InboxDao
import app.pulse4.android.data.ObservationDao
import app.pulse4.android.data.ObservationRow
import app.pulse4.android.data.OutboxDao
import app.pulse4.android.data.OutboxRow
import app.pulse4.android.data.PendingRequestDao
import app.pulse4.android.data.PendingRequestRow
import app.pulse4.android.data.ReceiptRow
import app.pulse4.android.data.ReceivedRow
import app.pulse4.android.data.SentRow
import app.pulse4.android.data.TrackRow
import app.pulse4.android.data.toRow
import app.pulse4.android.di.ActivityImportStore
import app.pulse4.android.di.FitImportResult
import app.pulse4.android.di.ImportService
import app.pulse4.android.di.PulseDataAccess
import app.pulse4.android.di.ShareService
import app.pulse4.android.di.ShareSettingsAccess
import app.pulse4.android.di.parseGrantRefs
import app.pulse4.android.ui.filterTopLevelActivities
import app.pulse4.android.ui.pickLatestTopLevelActivity
import app.pulse4.domain.Activity
import app.pulse4.domain.Provider
import app.pulse4.domain.Sport
import app.pulse4.domain.TrackPoint
import app.pulse4.fit.FitImporter
import app.pulse4.fit.FitResult
import app.pulse4.fit.ParsedFit
import app.pulse4.sharing.AgeProfile
import app.pulse4.sharing.Field
import app.pulse4.sharing.PrivacyZone
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.TimeScope
import app.pulse4.sync.DeliveryState
import app.pulse4.sync.OutboxItem
import app.pulse4.sync.OutboxManager
import app.pulse4.sync.OutboxStore
import app.pulse4.sync.SentLedger
import app.pulse4.sync.SentRecord
import app.pulse4.sync.Wire
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultisportImportRegressionTest {
    private class InMemoryActivityStore : ActivityImportStore {
        val activities = LinkedHashMap<String, ActivityRow>()
        val tracks = LinkedHashMap<String, TrackRow>()
        override fun allIds(): List<String> = activities.keys.toList()
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

    @Test
    fun `R3-6 ShareService enqueueActivities shares triathlon as single parent object and excludes legs`() {
        val nowMs = 1_786_780_000_000L
        val actMap = LinkedHashMap<String, ActivityRow>()
        val trackMap = LinkedHashMap<String, TrackRow>()
        val grantMap = LinkedHashMap<String, GrantRow>()
        val outboxMap = LinkedHashMap<String, OutboxRow>()
        val sentMap = LinkedHashMap<Pair<String, String>, SentRow>()

        val parent = Activity(
            id = "garmin-tri1",
            provider = Provider.GARMIN_PERSONAL,
            accountId = "acc-1",
            externalId = "tri1",
            sport = Sport.OTHER,
            startUtcMs = nowMs - 10_000L,
            zoneId = "UTC",
            totalTimeS = 7800.0,
            movingTimeS = 7650.0,
            distanceM = 51500.0,
            title = "Praha Triatlon",
        )
        val leg1 = parent.copy(id = "garmin-tri1-s1", externalId = "tri1-s1", sport = Sport.SWIMMING, distanceM = 1500.0, tags = setOf("multisport-leg", "parent:garmin-tri1"))
        val leg2 = parent.copy(id = "garmin-tri1-s2", externalId = "tri1-s2", sport = Sport.CYCLING, distanceM = 40000.0, tags = setOf("multisport-leg", "parent:garmin-tri1"))
        val leg3 = parent.copy(id = "custom-leg-3", externalId = "tri1-s3", sport = Sport.RUNNING, distanceM = 10000.0, tags = setOf("parent:garmin-tri1"))
        listOf(parent, leg1, leg2, leg3).forEach { act ->
            actMap[act.id] = act.toRow(Wire.json.encodeToString(Activity.serializer(), act))
        }

        val grant = ShareGrant(
            id = "g-act",
            ownerId = "alice",
            recipients = setOf("bob"),
            fields = setOf(Field.ACT_SUMMARY, Field.ACT_CHARTS),
            scope = TimeScope.continuousFrom(LocalDate.of(2026, 1, 1)),
            groupId = null,
            createdAtMs = 1_000L,
        )
        grantMap[grant.id] = GrantRow(grant.id, false, Wire.json.encodeToString(ShareGrant.serializer(), grant))

        val data = object : PulseDataAccess {
            private val obsDao = object : ObservationDao {
                override fun upsert(rows: List<ObservationRow>) {}
                override fun forDate(date: String) = emptyList<ObservationRow>()
                override fun forRange(from: String, to: String) = emptyList<ObservationRow>()
                override fun forTypeInRange(type: String, from: String, to: String) = emptyList<ObservationRow>()
                override fun latest(type: String): ObservationRow? = null
                override fun latestRows(type: String, limit: Int) = emptyList<ObservationRow>()
                override fun pagedForType(type: String, limit: Int, offset: Int) = emptyList<ObservationRow>()
                override fun countForType(type: String) = 0
                override fun series(type: String, from: String, to: String): Flow<List<ObservationRow>> = emptyFlow()
                override fun markDeleted(ids: List<String>) {}
                override fun idsIn(provider: String, from: Long, to: Long) = emptyList<String>()
                override fun idsInForAccount(provider: String, accountId: String, from: Long, to: Long) = emptyList<String>()
                override fun idsForAccount(provider: String, accountId: String) = emptyList<String>()
                override fun deleteLegacyCombinedEcg() = 0
                override fun ofTypesIncludingDeleted(types: List<String>, from: String, to: String) = emptyList<ObservationRow>()
                override fun confirm(id: String) {}
                override fun datesWithData(from: String, to: String) = emptyList<String>()
                override fun deleteSyntheticHistory() {}
                override fun deleteLegacyGarminSleepForDates(dates: List<String>) {}
            }
            private val actDao = object : ActivityDao {
                override fun upsert(row: ActivityRow) { actMap[row.id] = row }
                override fun upsertTrack(row: TrackRow) { trackMap[row.activityId] = row }
                override fun all(): Flow<List<ActivityRow>> = emptyFlow()
                override fun allNow() = actMap.values.toList()
                override fun allIds() = actMap.keys.toList()
                override fun latest() = actMap.values.maxByOrNull { it.startUtcMs }
                override fun allSummaries(): Flow<List<ActivitySummaryRow>> = emptyFlow()
                override fun allSummariesNow() = emptyList<ActivitySummaryRow>()
                override fun recentSummaries(limit: Int): Flow<List<ActivitySummaryRow>> = emptyFlow()
                override fun recentSummariesNow(limit: Int) = emptyList<ActivitySummaryRow>()
                override fun unbackfilled(limit: Int) = emptyList<IdAndJson>()
                override fun markBackfillFailed(id: String) {}
                override fun updateSummary(id: String, title: String, distanceM: Double?, movingTimeS: Double?, ascentM: Double?, avgHeartRate: Int?, maxHeartRate: Int?, caloriesKcal: Double?, aerobicTe: Double?, anaerobicTe: Double?, trainingLoad: Double?, avgPowerW: Double?, avgCadence: Double?, zoneId: String?) {}
                override fun since(from: Long) = actMap.values.filter { it.startUtcMs >= from }
                override fun get(id: String) = actMap[id]
                override fun track(id: String) = trackMap[id]
                override fun count() = actMap.size
                override fun deleteAll() = actMap.clear()
                override fun deleteAllTracks() = trackMap.clear()
            }
            private val areaDao = object : AreaStateDao {
                override fun put(row: AreaStateRow) {}
                override fun get(area: String): AreaStateRow? = null
                override fun all(): Flow<List<AreaStateRow>> = emptyFlow()
            }
            private val grantDao = object : GrantDao {
                override fun upsert(row: GrantRow) { grantMap[row.id] = row }
                override fun all(): Flow<List<GrantRow>> = emptyFlow()
                override fun allNow() = grantMap.values.toList()
                override fun activeNow() = grantMap.values.filter { !it.revoked }
                override fun get(id: String) = grantMap[id]
                override fun upsertGroup(row: GroupRow) {}
                override fun groups(): Flow<List<GroupRow>> = emptyFlow()
                override fun groupsNow() = emptyList<GroupRow>()
                override fun group(id: String): GroupRow? = null
                override fun deleteGroup(id: String) {}
                override fun deleteAllGroups() {}
                override fun deleteAllGrants() { grantMap.clear() }
            }
            private val outboxDao = object : OutboxDao {
                override fun insert(row: OutboxRow) { outboxMap[row.eventId] = row }
                override fun update(row: OutboxRow) { outboxMap[row.eventId] = row }
                override fun find(id: String) = outboxMap[id]
                override fun all() = outboxMap.values.toList()
                override fun recent(): Flow<List<OutboxRow>> = emptyFlow()
                override fun purge(before: Long) {}
                override fun deleteAll() { outboxMap.clear() }
            }
            private val inboxDao = object : InboxDao {
                override fun receiptHash(id: String): String? = null
                override fun insertReceipt(row: ReceiptRow) {}
                override fun version(a: String, t: String, i: String): Long? = null
                override fun received(a: String, t: String, i: String): ReceivedRow? = null
                override fun putObject(row: ReceivedRow) {}
                override fun removeObject(a: String, t: String, i: String) {}
                override fun objects(t: String): Flow<List<ReceivedRow>> = emptyFlow()
                override fun objectsNow(t: String) = emptyList<ReceivedRow>()
                override fun objectsOf(a: String, t: String): Flow<List<ReceivedRow>> = emptyFlow()
                override fun sent(r: String, k: String) = sentMap[r to k]
                override fun allSentNow() = sentMap.values.toList()
                override fun allReceivedNow() = emptyList<ReceivedRow>()
                override fun updateReceiptAuthor(oldAuthor: String, newAuthor: String) {}
                override fun putSent(row: SentRow) { sentMap[row.recipient to row.objectKey] = row }
                override fun deleteSent(r: String, k: String) { sentMap.remove(r to k) }
                override fun removeAllFrom(a: String) {}
                override fun lastReceived(a: String): Long? = null
                override fun deleteAll() {}
                override fun deleteAllReceipts() {}
                override fun deleteAllSent() { sentMap.clear() }
            }
            private val familyDao = object : FamilyLinkDao {
                override fun all(): Flow<List<FamilyLinkRow>> = emptyFlow()
                override fun allNow() = emptyList<FamilyLinkRow>()
                override fun get(id: String): FamilyLinkRow? = null
                override fun upsert(row: FamilyLinkRow) {}
                override fun delete(id: String) {}
            }
            private val pendingDao = object : PendingRequestDao {
                override fun all(): Flow<List<PendingRequestRow>> = emptyFlow()
                override fun allNow() = emptyList<PendingRequestRow>()
                override fun pendingNow() = emptyList<PendingRequestRow>()
                override fun get(id: String): PendingRequestRow? = null
                override fun upsert(row: PendingRequestRow) {}
                override fun delete(id: String) {}
            }
            override fun observations() = obsDao
            override fun activities() = actDao
            override fun areaStates() = areaDao
            override fun grants() = grantDao
            override fun outbox() = outboxDao
            override fun inbox() = inboxDao
            override fun familyLinks() = familyDao
            override fun pendingRequests() = pendingDao
            val contactDao = app.pulse4.android.di.InMemoryContactDao()
            val consentDao = app.pulse4.android.di.InMemoryGuardianConsentDao()
            override fun contacts() = contactDao
            override fun guardianConsents() = consentDao
        }
        val settings = object : ShareSettingsAccess {
            override var zoneId = "UTC"
            override var privacyZones: List<PrivacyZone> = emptyList()
            override var acceptWeightFromParent = false
            override var childRecipientIdentity = ""
            private val raw = LinkedHashMap<String, String>().also { app.pulse4.android.di.seedAge(it, app.pulse4.sharing.AgeState.ADULT) }
            override fun getRaw(key: String): String? = raw[key]
            override fun put(key: String, value: String) { raw[key] = value }
        }
        val outbox = OutboxManager(
            store = object : OutboxStore {
                override fun insert(item: OutboxItem) = data.outbox().insert(
                    OutboxRow(item.eventId, item.recipient, item.kind, item.objectType, item.objectId, item.objectVersion, item.effectiveGrantRefs.firstOrNull(), item.wire, item.createdAtMs, item.state.name, item.attempts, item.nextAttemptAtMs, item.lastError)
                )
                override fun update(item: OutboxItem) = insert(item)
                override fun find(eventId: String): OutboxItem? = null
                override fun all() = data.outbox().all().map {
                    OutboxItem(it.eventId, it.recipient, it.kind, it.objectType, it.objectId, it.objectVersion, it.grantId, it.wire, it.createdAtMs, DeliveryState.valueOf(it.state), it.attempts, it.nextAttemptAtMs, it.lastError, parseGrantRefs(it.grantId))
                }
            }
        )
        val service = ShareService(
            data = data,
            settings = settings,
            outbox = outbox,
            sentLedger = object : SentLedger {
                override fun last(recipient: String, objectKey: String) =
                    data.inbox().sent(recipient, objectKey)?.let { SentRecord(it.version, it.contentHash) }
            },
            recordSent = { r, k, v, h -> data.inbox().putSent(SentRow(r, k, v, h)) },
            selfIdProvider = { "alice" },
        )

        val enqueued = service.planAndEnqueue(nowMs)
        assertEquals(1, enqueued)
        val activityItems = outboxMap.values.filter { it.objectType == "activity" }
        assertEquals(1, activityItems.size)
        assertEquals("garmin-tri1", activityItems.single().objectId)
    }

    @Test
    fun `R3-6 pickLatestTopLevelActivity never falls back to multisport child legs`() {
        val leg1 = ActivitySummaryRow("garmin-100-s1", 2_000L, "CYCLING", "1. Kolo", 40_000.0, 3600.0, null, null, null, null, null, null, null, null, null, "UTC", null)
        val leg2 = ActivitySummaryRow("garmin-100-s2", 1_900L, "RUNNING", "2. Běh", 10_000.0, 2400.0, null, null, null, null, null, null, null, null, null, "UTC", null)
        assertNull(pickLatestTopLevelActivity(listOf(leg1, leg2)))

        val parent = ActivitySummaryRow("garmin-100", 1_800L, "OTHER", "Triatlon", 50_000.0, 6000.0, null, null, null, null, null, null, null, null, null, "UTC", null)
        assertEquals("garmin-100", pickLatestTopLevelActivity(listOf(leg1, leg2, parent))?.id)
    }

    @Test
    fun `R3-7 repairLegacyMultisportActivities repairs 9c61ec9 shifted legs and restores Garmin parent summary`() {
        val fitBytes = buildTwoSessionMultisportFitBytes()
        val parsed = assertIs<FitResult.Ok>(FitImporter.parse(fitBytes))
        val garminObj = garminMultisportSummaryJson("777666")
        val nowMs = 1_786_780_000_000L
        val trackSer = kotlinx.serialization.builtins.ListSerializer(TrackPoint.serializer())

        // Simulate 9c61ec9 DB state: garmin-777666 got session[0] without Garmin summary; -s1 got session[1] without tags.
        val store = InMemoryActivityStore()
        val oldParentSession0 = ImportService.buildActivityFromGarminAndFit(
            id = "garmin-777666",
            provider = Provider.GARMIN_PERSONAL,
            extId = "777666",
            obj = null,
            parsed = parsed.sessions[0],
            nowMs = nowMs,
            accountId = "acc-1",
            zoneId = "Europe/Prague",
        )
        val oldLeg1Session1 = ImportService.buildActivityFromGarminAndFit(
            id = "garmin-777666-s1",
            provider = Provider.GARMIN_PERSONAL,
            extId = "777666-s1",
            obj = null,
            parsed = parsed.sessions[1],
            nowMs = nowMs,
            accountId = "acc-1",
            zoneId = "Europe/Prague",
        )
        store.upsert(oldParentSession0.toRow(Wire.json.encodeToString(Activity.serializer(), oldParentSession0)))
        store.upsertTrack(TrackRow("garmin-777666", Wire.json.encodeToString(trackSer, parsed.sessions[0].points)))
        store.upsert(oldLeg1Session1.toRow(Wire.json.encodeToString(Activity.serializer(), oldLeg1Session1)))
        store.upsertTrack(TrackRow("garmin-777666-s1", Wire.json.encodeToString(trackSer, parsed.sessions[1].points)))

        // Step 1: Offline repair (before next sync) synthesizes parent and shifts legs cleanly -s1, -s2
        val offlineRepaired = ImportService.repairLegacyMultisportActivities(
            store = store,
            nowMs = nowMs,
            accountId = "acc-1",
            zoneId = "Europe/Prague",
        )
        assertEquals(1, offlineRepaired)
        assertEquals(setOf("garmin-777666", "garmin-777666-s1", "garmin-777666-s2"), store.activities.keys)
        val pendingParent = Wire.json.decodeFromString(Activity.serializer(), store.activities.getValue("garmin-777666").json)
        assertTrue("multisport-summary-pending" in pendingParent.tags)
        assertEquals(50_000.0, pendingParent.distanceM)
        val leg1 = Wire.json.decodeFromString(Activity.serializer(), store.activities.getValue("garmin-777666-s1").json)
        val leg2 = Wire.json.decodeFromString(Activity.serializer(), store.activities.getValue("garmin-777666-s2").json)
        assertEquals(Sport.CYCLING, leg1.sport)
        assertEquals(Sport.RUNNING, leg2.sport)
        assertTrue("multisport-leg" in leg1.tags && "parent:garmin-777666" in leg1.tags)
        assertTrue("multisport-leg" in leg2.tags && "parent:garmin-777666" in leg2.tags)

        // Step 2: Next Garmin sync provides garminObj -> parent summary is restored and pending tag cleared
        ImportService.syncGarminActivityElements(
            allActivityElements = listOf(garminObj),
            isInitialSync = false,
            nowMs = nowMs + 10_000L,
            store = store,
            accountId = "acc-1",
            zoneId = "Europe/Prague",
            downloadFit = { null },
            fetchDetailsJson = { "" },
        )
        val restoredParent = Wire.json.decodeFromString(Activity.serializer(), store.activities.getValue("garmin-777666").json)
        assertEquals("Praha Triatlon", restoredParent.title)
        assertEquals(51500.0, restoredParent.distanceM)
        assertEquals(4.2, restoredParent.aerobicTrainingEffect)
        assertEquals(215.0, restoredParent.trainingLoad)
        assertFalse("multisport-summary-pending" in restoredParent.tags)
        val renamedLeg1 = Wire.json.decodeFromString(Activity.serializer(), store.activities.getValue("garmin-777666-s1").json)
        assertTrue(renamedLeg1.title?.startsWith("Praha Triatlon") == true)
    }

    @Test
    fun `R3-14 buildActivityFromGarminAndFit leaves hrZoneTimesS empty when maxHr is unknown`() {
        val parsedNoMaxHr = ParsedFit(
            sport = Sport.RUNNING,
            startMs = 1_000L,
            totalTimeS = 600.0,
            movingTimeS = 600.0,
            distanceM = 2000.0,
            ascentM = null,
            avgHeartRate = 150,
            maxHeartRate = null,
            points = listOf(
                TrackPoint(tMs = 1_000L, heartRate = 145),
                TrackPoint(tMs = 11_000L, heartRate = 160),
                TrackPoint(tMs = 21_000L, heartRate = 155),
            ),
            laps = emptyList(),
            caloriesKcal = 150.0,
        )
        val actWithoutMax = ImportService.buildActivityFromGarminAndFit(
            id = "fit-nomax",
            provider = Provider.MANUAL,
            extId = "nomax",
            obj = null,
            parsed = parsedNoMaxHr,
            nowMs = 2_000L,
            accountId = "acc-1",
            zoneId = "UTC",
        )
        assertTrue(actWithoutMax.hrZoneTimesS.isEmpty(), "Expected empty hrZoneTimesS without maxHr, got ${actWithoutMax.hrZoneTimesS}")

        val actWithMax = ImportService.buildActivityFromGarminAndFit(
            id = "fit-withmax",
            provider = Provider.MANUAL,
            extId = "withmax",
            obj = null,
            parsed = parsedNoMaxHr.copy(maxHeartRate = 180),
            nowMs = 2_000L,
            accountId = "acc-1",
            zoneId = "UTC",
        )
        assertEquals(5, actWithMax.hrZoneTimesS.size)
        assertTrue(actWithMax.hrZoneTimesS.any { it > 0.0 })
    }
}
