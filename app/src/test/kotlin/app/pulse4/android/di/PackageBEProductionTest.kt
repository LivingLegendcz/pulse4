package app.pulse4.android.di

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
import app.pulse4.android.data.toDomain
import app.pulse4.android.data.toRow
import app.pulse4.domain.Activity
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Quality
import app.pulse4.domain.Sport
import app.pulse4.sharing.AgeProfile
import app.pulse4.sharing.FamilyLink
import app.pulse4.sharing.FamilyLinkPolicy
import app.pulse4.sharing.FamilyLinkStatus
import app.pulse4.sharing.Field
import app.pulse4.sharing.Group
import app.pulse4.sharing.PrivacyZone
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.TimeScope
import app.pulse4.sync.Ack
import app.pulse4.sync.BuiltInHandlers
import app.pulse4.sync.DeliveryState
import app.pulse4.sync.Envelope
import app.pulse4.sync.HandlerResult
import app.pulse4.sync.Kinds
import app.pulse4.sync.MessageBuilder
import app.pulse4.sync.OutboxItem
import app.pulse4.sync.OutboxManager
import app.pulse4.sync.OutboxStore
import app.pulse4.sync.ReceiverStore
import app.pulse4.sync.RejectReason
import app.pulse4.sync.SentLedger
import app.pulse4.sync.SentRecord
import app.pulse4.sync.Wire
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class PackageBEProductionTest {

    private class FakeSettings(
        override var zoneId: String = "UTC",
        override var privacyZones: List<PrivacyZone> = emptyList(),
        override var ownAgeProfile: String = AgeProfile.ADULT.name,
        override var acceptWeightFromParent: Boolean = false,
        override var childRecipientIdentity: String = "",
    ) : ShareSettingsAccess {
        val raw = LinkedHashMap<String, String>()
        override fun getRaw(key: String): String? = raw[key]
        override fun put(key: String, value: String) { raw[key] = value }
    }

    private class FakeDataAccess : PulseDataAccess {
        val obsList = LinkedHashMap<String, ObservationRow>()
        val actMap = LinkedHashMap<String, ActivityRow>()
        val trackMap = LinkedHashMap<String, TrackRow>()
        val areaMap = LinkedHashMap<String, AreaStateRow>()
        val grantMap = LinkedHashMap<String, GrantRow>()
        val groupMap = LinkedHashMap<String, GroupRow>()
        val outboxMap = LinkedHashMap<String, OutboxRow>()
        val sentMap = LinkedHashMap<Pair<String, String>, SentRow>()
        val receivedList = mutableListOf<ReceivedRow>()
        val receiptMap = LinkedHashMap<String, ReceiptRow>()
        val familyLinkMap = LinkedHashMap<String, FamilyLinkRow>()
        val pendingReqMap = LinkedHashMap<String, PendingRequestRow>()

        private val obsDao = object : ObservationDao {
            override fun upsert(rows: List<ObservationRow>) { rows.forEach { obsList[it.id] = it } }
            override fun forDate(date: String) = obsList.values.filter { !it.deleted && it.localDate == date }.sortedBy { it.startUtcMs }
            override fun forRange(from: String, to: String) =
                obsList.values.filter { !it.deleted && (it.localDate ?: "") in from..to }.sortedBy { it.startUtcMs }
            override fun forTypeInRange(type: String, from: String, to: String) =
                obsList.values.filter { !it.deleted && it.type == type && (it.localDate ?: "") in from..to }.sortedBy { it.startUtcMs }
            override fun latest(type: String) = obsList.values.filter { !it.deleted && it.type == type }.maxByOrNull { it.startUtcMs }
            override fun latestRows(type: String, limit: Int) =
                obsList.values.filter { !it.deleted && it.type == type }.sortedByDescending { it.startUtcMs }.take(limit)
            override fun pagedForType(type: String, limit: Int, offset: Int) = emptyList<ObservationRow>()
            override fun countForType(type: String) = obsList.values.count { !it.deleted && it.type == type }
            override fun series(type: String, from: String, to: String): Flow<List<ObservationRow>> = emptyFlow()
            override fun markDeleted(ids: List<String>) { ids.forEach { id -> obsList[id]?.let { obsList[id] = it.copy(deleted = true) } } }
            override fun idsIn(provider: String, from: Long, to: Long) = emptyList<String>()
            override fun idsInForAccount(provider: String, accountId: String, from: Long, to: Long) = emptyList<String>()
            override fun idsForAccount(provider: String, accountId: String) = emptyList<String>()
            override fun deleteLegacyCombinedEcg() = 0
            override fun ofTypesIncludingDeleted(types: List<String>, from: String, to: String) = emptyList<ObservationRow>()
            override fun confirm(id: String) {}
            override fun datesWithData(from: String, to: String) =
                obsList.values.filter { !it.deleted && it.localDate != null && it.localDate in from..to }.mapNotNull { it.localDate }.distinct()
            override fun deleteSyntheticHistory() {}
            override fun deleteLegacyGarminSleepForDates(dates: List<String>) {}
        }

        private val actDao = object : ActivityDao {
            override fun upsert(row: ActivityRow) { actMap[row.id] = row }
            override fun upsertTrack(row: TrackRow) { trackMap[row.activityId] = row }
            override fun all(): Flow<List<ActivityRow>> = emptyFlow()
            override fun allNow() = actMap.values.sortedByDescending { it.startUtcMs }
            override fun latest() = actMap.values.maxByOrNull { it.startUtcMs }
            override fun allSummaries(): Flow<List<ActivitySummaryRow>> = emptyFlow()
            override fun allSummariesNow() = emptyList<ActivitySummaryRow>()
            override fun recentSummaries(limit: Int): Flow<List<ActivitySummaryRow>> = emptyFlow()
            override fun recentSummariesNow(limit: Int) = emptyList<ActivitySummaryRow>()
            override fun unbackfilled(limit: Int) = emptyList<IdAndJson>()
            override fun markBackfillFailed(id: String) {}
            override fun updateSummary(
                id: String, title: String, distanceM: Double?, movingTimeS: Double?, ascentM: Double?,
                avgHeartRate: Int?, maxHeartRate: Int?, caloriesKcal: Double?, aerobicTe: Double?,
                anaerobicTe: Double?, trainingLoad: Double?, avgPowerW: Double?, avgCadence: Double?, zoneId: String?,
            ) {}
            override fun since(from: Long) = actMap.values.filter { it.startUtcMs >= from }.sortedByDescending { it.startUtcMs }
            override fun get(id: String) = actMap[id]
            override fun track(id: String) = trackMap[id]
            override fun count() = actMap.size
            override fun deleteAll() { actMap.clear() }
            override fun deleteAllTracks() { trackMap.clear() }
        }

        private val areaDao = object : AreaStateDao {
            override fun put(row: AreaStateRow) { areaMap[row.area] = row }
            override fun get(area: String) = areaMap[area]
            override fun all(): Flow<List<AreaStateRow>> = emptyFlow()
        }

        private val grantDao = object : GrantDao {
            override fun upsert(row: GrantRow) { grantMap[row.id] = row }
            override fun all(): Flow<List<GrantRow>> = emptyFlow()
            override fun allNow() = grantMap.values.toList()
            override fun activeNow() = grantMap.values.filter { !it.revoked }
            override fun get(id: String) = grantMap[id]
            override fun upsertGroup(row: GroupRow) { groupMap[row.id] = row }
            override fun groups(): Flow<List<GroupRow>> = emptyFlow()
            override fun groupsNow() = groupMap.values.toList()
            override fun group(id: String) = groupMap[id]
            override fun deleteGroup(id: String) { groupMap.remove(id) }
            override fun deleteAllGroups() { groupMap.clear() }
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
            override fun receiptHash(id: String) = receiptMap[id]?.contentHash
            override fun insertReceipt(row: ReceiptRow) { receiptMap[row.eventId] = row }
            override fun version(a: String, t: String, i: String) =
                receivedList.firstOrNull { it.author == a && it.objectType == t && it.objectId == i }?.version
            override fun putObject(row: ReceivedRow) {
                receivedList.removeAll { it.author == row.author && it.objectType == row.objectType && it.objectId == row.objectId }
                receivedList += row
            }
            override fun removeObject(a: String, t: String, i: String) {
                receivedList.removeAll { it.author == a && it.objectType == t && it.objectId == i }
            }
            override fun objects(t: String): Flow<List<ReceivedRow>> = emptyFlow()
            override fun objectsNow(t: String) = receivedList.filter { it.objectType == t }
            override fun objectsOf(a: String, t: String): Flow<List<ReceivedRow>> = emptyFlow()
            override fun sent(r: String, k: String) = sentMap[r to k]
            override fun allSentNow() = sentMap.values.toList()
            override fun allReceivedNow() = receivedList.toList()
            override fun updateReceiptAuthor(oldAuthor: String, newAuthor: String) {}
            override fun putSent(row: SentRow) { sentMap[row.recipient to row.objectKey] = row }
            override fun deleteSent(r: String, k: String) { sentMap.remove(r to k) }
            override fun removeAllFrom(a: String) { receivedList.removeAll { it.author == a } }
            override fun lastReceived(a: String) = null
            override fun deleteAll() { receivedList.clear() }
            override fun deleteAllReceipts() { receiptMap.clear() }
            override fun deleteAllSent() { sentMap.clear() }
        }

        private val familyDao = object : FamilyLinkDao {
            override fun all(): Flow<List<FamilyLinkRow>> = emptyFlow()
            override fun allNow() = familyLinkMap.values.toList()
            override fun get(id: String) = familyLinkMap[id]
            override fun upsert(row: FamilyLinkRow) { familyLinkMap[row.id] = row }
            override fun delete(id: String) { familyLinkMap.remove(id) }
        }

        private val pendingDao = object : PendingRequestDao {
            override fun all(): Flow<List<PendingRequestRow>> = emptyFlow()
            override fun allNow() = pendingReqMap.values.toList()
            override fun pendingNow() = pendingReqMap.values.filter { it.status == "PENDING" }
            override fun get(id: String) = pendingReqMap[id]
            override fun upsert(row: PendingRequestRow) { pendingReqMap[row.id] = row }
            override fun delete(id: String) { pendingReqMap.remove(id) }
        }

        override fun observations() = obsDao
        override fun activities() = actDao
        override fun areaStates() = areaDao
        override fun grants() = grantDao
        override fun outbox() = outboxDao
        override fun inbox() = inboxDao
        override fun familyLinks() = familyDao
        override fun pendingRequests() = pendingDao
    }

    private fun buildOutbox(data: FakeDataAccess): OutboxManager {
        val store = object : OutboxStore {
            override fun insert(item: OutboxItem) = data.outbox().insert(item.toRow())
            override fun update(item: OutboxItem) = data.outbox().update(item.toRow())
            override fun find(eventId: String) = data.outbox().find(eventId)?.toItem()
            override fun all() = data.outbox().all().map { it.toItem() }

            private fun OutboxItem.toRow(): OutboxRow {
                val refs = effectiveGrantRefs.takeIf { it.isNotEmpty() }?.joinToString(",")
                return OutboxRow(
                    eventId, recipient, kind, objectType, objectId, objectVersion, refs, wire,
                    createdAtMs, state.name, attempts, nextAttemptAtMs, lastError,
                )
            }

            private fun OutboxRow.toItem(): OutboxItem {
                val refs = parseGrantRefs(grantId)
                return OutboxItem(
                    eventId, recipient, kind, objectType, objectId, objectVersion, refs.firstOrNull(), wire,
                    createdAtMs, DeliveryState.valueOf(state), attempts, nextAttemptAtMs, lastError, refs,
                )
            }
        }
        return OutboxManager(
            store = store,
            onPermissionDenied = { recipient, objectKey ->
                data.inbox().sent(recipient, objectKey)?.let { row ->
                    data.inbox().putSent(row.copy(contentHash = ""))
                }
            },
        )
    }

    private fun buildShareService(
        data: FakeDataAccess,
        settings: FakeSettings,
        outbox: OutboxManager,
        selfId: String = "alice",
    ): ShareService {
        val ledger = object : SentLedger {
            override fun last(recipient: String, objectKey: String) =
                data.inbox().sent(recipient, objectKey)?.let { SentRecord(it.version, it.contentHash) }
        }
        return ShareService(
            data = data,
            settings = settings,
            outbox = outbox,
            sentLedger = ledger,
            recordSent = { r, k, v, h -> data.inbox().putSent(SentRow(r, k, v, h)) },
            selfIdProvider = { selfId },
        )
    }

    private val dummyReceiverStore = object : ReceiverStore {
        override fun <T> inTransaction(block: () -> T): T = block()
        override fun isVerifiedContact(identity: String) = true
        override fun receiptHash(eventId: String): String? = null
        override fun recordReceipt(eventId: String, contentHash: String, author: String, appliedAtMs: Long) {}
        override fun objectVersion(author: String, objectType: String, objectId: String): Long? = null
        override fun putObject(author: String, objectType: String, objectId: String, version: Long, payload: String) {}
        override fun removeObject(author: String, objectType: String, objectId: String, version: Long) {}
        override fun group(groupId: String): Group? = null
        override fun putGroup(group: Group) {}
    }

    private fun obsRow(
        id: String,
        type: MetricType,
        value: Double,
        date: LocalDate,
        hour: Int = 8,
        provider: Provider = Provider.GARMIN_PERSONAL,
        quality: Quality = Quality.OK,
    ): ObservationRow {
        val ms = date.atTime(hour, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        return MetricObservation(
            id = id,
            provider = provider,
            accountId = "acc",
            externalId = id,
            type = type,
            startUtcMs = ms,
            endUtcMs = ms + 3600_000L,
            value = value,
            zoneId = "UTC",
            localDate = date.toString(),
            quality = quality,
        ).toRow()
    }

    private fun ackAllDue(outbox: OutboxManager, nowMs: Long) {
        for (item in outbox.due(nowMs)) {
            outbox.onAck(item.recipient, Ack(item.eventId, Ack.Status.ACCEPTED))
        }
    }

    @Test
    fun `N-1 revoke g1 sends REMOVE for sleep-only day and ACT_EXACT_TIME-only key while resending merged day vN+1`() {
        val data = FakeDataAccess()
        val settings = FakeSettings(zoneId = "UTC")
        val outbox = buildOutbox(data)
        val service = buildShareService(data, settings, outbox, selfId = "alice")

        val today = LocalDate.now(ZoneId.of("UTC"))
        val sleepOnlyDay = today.minusDays(2)
        val bothDay = today.minusDays(1)
        val now = today.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

        data.observations().upsert(
            listOf(
                obsRow("s1", MetricType.SLEEP_DURATION, 25_200.0, sleepOnlyDay),
                obsRow("s2", MetricType.SLEEP_DURATION, 26_000.0, bothDay),
                obsRow("w2", MetricType.WEIGHT, 72.5, bothDay),
            )
        )
        val actMs = bothDay.atTime(10, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val act = Activity(
            id = "act-1", provider = Provider.GARMIN_PERSONAL, accountId = "acc", externalId = "act-1",
            sport = Sport.RUNNING, startUtcMs = actMs, zoneId = "UTC", totalTimeS = 1800.0, distanceM = 5000.0,
        )
        data.activities().upsert(act.toRow(Wire.json.encodeToString(Activity.serializer(), act)))

        val g1SleepAndAct = ShareGrant(
            id = "g1", ownerId = "alice", recipients = setOf("bob"),
            fields = setOf(Field.SLEEP_DURATION, Field.ACT_SUMMARY),
            scope = TimeScope.continuousFrom(today.minusDays(10)), createdAtMs = now,
        )
        val g2WeightAndExactTime = ShareGrant(
            id = "g2", ownerId = "alice", recipients = setOf("bob"),
            fields = setOf(Field.WEIGHT, Field.ACT_EXACT_TIME),
            scope = TimeScope.continuousFrom(today.minusDays(10)), createdAtMs = now,
        )
        service.saveGrantWithPolicy(g1SleepAndAct).getOrThrow()
        service.saveGrantWithPolicy(g2WeightAndExactTime).getOrThrow()

        service.planAndEnqueue(now)
        val initialDue = outbox.due(now)
        assertEquals(
            setOf("daily/$sleepOnlyDay", "daily/$bothDay", "activity/act-1"),
            initialDue.map { it.objectKey }.toSet(),
        )
        // N-6: merged daily/$bothDay carries BOTH contributing grantRefs
        val mergedDailyItem = initialDue.first { it.objectKey == "daily/$bothDay" }
        assertEquals(setOf("g1", "g2"), mergedDailyItem.effectiveGrantRefs)
        // Sleep-only day only had g1 contributing
        val sleepOnlyItem = initialDue.first { it.objectKey == "daily/$sleepOnlyDay" }
        assertEquals(setOf("g1"), sleepOnlyItem.effectiveGrantRefs)
        ackAllDue(outbox, now)

        // Revoke g1:
        // - daily/sleepOnlyDay has only g2 (WEIGHT) left -> WEIGHT projects null on sleep-only day -> PROJECTION_REMOVE MUST be queued!
        // - activity/act-1 has only g2 (ACT_EXACT_TIME-only, no ACT_SUMMARY) left -> ActivityProjector returns null -> PROJECTION_REMOVE MUST be queued!
        // - daily/bothDay still has WEIGHT -> NO PROJECTION_REMOVE; subsequent planAndEnqueue emits upsert version 2 without SLEEP_DURATION!
        val t2 = now + 5_000L
        service.revoke("g1", t2)

        val dueAfterRevoke = outbox.due(t2)
        val removes = dueAfterRevoke.filter { it.kind == Kinds.PROJECTION_REMOVE }
        assertEquals(setOf("daily/$sleepOnlyDay", "activity/act-1"), removes.map { it.objectKey }.toSet())

        service.planAndEnqueue(t2 + 1_000L)
        val dueAfterReplan = outbox.due(t2 + 1_000L)
        val bothUpsert = dueAfterReplan.single { it.kind == Kinds.PROJECTION_UPSERT && it.objectKey == "daily/$bothDay" }
        assertEquals(2L, bothUpsert.objectVersion)
        val payloadStr = Wire.decode(bothUpsert.wire)!!.payload
        assertTrue(payloadStr.contains("WEIGHT"))
        assertFalse(payloadStr.contains("SLEEP_DURATION"))
    }

    @Test
    fun `N-1 and N-6 revoking non-primary grant updates merged daily and activity and cancelForGrant works`() {
        val data = FakeDataAccess()
        val settings = FakeSettings(zoneId = "UTC")
        val outbox = buildOutbox(data)
        val service = buildShareService(data, settings, outbox, selfId = "alice")

        val today = LocalDate.now(ZoneId.of("UTC"))
        val day = today.minusDays(1)
        val now = today.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

        data.observations().upsert(
            listOf(
                obsRow("s1", MetricType.SLEEP_DURATION, 24_000.0, day),
                obsRow("w1", MetricType.WEIGHT, 70.0, day),
            )
        )
        val actMs = day.atTime(9, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val act = Activity(
            id = "act-route", provider = Provider.GARMIN_PERSONAL, accountId = "acc", externalId = "act-route",
            sport = Sport.CYCLING, startUtcMs = actMs, zoneId = "UTC", totalTimeS = 3600.0, distanceM = 20_000.0,
        )
        data.activities().upsert(act.toRow(Wire.json.encodeToString(Activity.serializer(), act)))

        // Alphabetically: "g1" < "g2", so "g2" is non-primary
        val g1 = ShareGrant(
            id = "g1", ownerId = "alice", recipients = setOf("bob"),
            fields = setOf(Field.SLEEP_DURATION, Field.ACT_SUMMARY),
            scope = TimeScope.continuousFrom(today.minusDays(10)), createdAtMs = now,
        )
        val g2 = ShareGrant(
            id = "g2", ownerId = "alice", recipients = setOf("bob"),
            fields = setOf(Field.WEIGHT, Field.ACT_CHARTS),
            scope = TimeScope.continuousFrom(today.minusDays(10)), createdAtMs = now,
        )
        service.saveGrantWithPolicy(g1).getOrThrow()
        service.saveGrantWithPolicy(g2).getOrThrow()

        service.planAndEnqueue(now)
        val actItem = outbox.due(now).first { it.objectKey == "activity/act-route" }
        assertEquals(setOf("g1", "g2"), actItem.effectiveGrantRefs)
        assertTrue(settings.getRaw("delivered_keys.g2")?.contains("daily/$day") == true)
        assertTrue(settings.getRaw("delivered_keys.g2")?.contains("activity/act-route") == true)

        // Revoke non-primary grant BEFORE delivery -> queued merged items referencing g2 are cancelled, then replan sends g1-only
        service.revoke("g2", now + 100L)
        assertEquals(DeliveryState.CANCELLED, data.outbox().find(actItem.eventId)?.let { DeliveryState.valueOf(it.state) })

        // Re-grant g2 and deliver, then revoke non-primary g2 AFTER ACK -> daily/day gets v2 without WEIGHT
        val g2b = g2.copy(id = "g2b")
        service.saveGrantWithPolicy(g2b).getOrThrow()
        service.planAndEnqueue(now + 200L)
        ackAllDue(outbox, now + 200L)
        val prevVer = data.inbox().sent("bob", "daily/$day")!!.version

        service.revoke("g2b", now + 500L)
        assertTrue(outbox.due(now + 500L).none { it.kind == Kinds.PROJECTION_REMOVE })
        service.planAndEnqueue(now + 600L)
        val updatedDaily = outbox.due(now + 600L).single { it.objectKey == "daily/$day" }
        assertEquals(prevVer + 1L, updatedDaily.objectVersion)
        val payload = Wire.decode(updatedDaily.wire)!!.payload
        assertTrue(payload.contains("SLEEP_DURATION"))
        assertFalse(payload.contains("WEIGHT"))
    }

    @Test
    fun `N-2 global profile edit adding category emits zero REMOVEs and supersedes pending remove`() {
        val data = FakeDataAccess()
        val settings = FakeSettings(zoneId = "UTC")
        val outbox = buildOutbox(data)
        val service = buildShareService(data, settings, outbox, selfId = "alice")

        val today = LocalDate.now(ZoneId.of("UTC"))
        val day = today.minusDays(1)
        val now = today.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        data.observations().upsert(
            listOf(
                obsRow("s1", MetricType.SLEEP_DURATION, 25_000.0, day),
                obsRow("w1", MetricType.WEIGHT, 75.0, day),
            )
        )
        val oldGrant = ShareGrant(
            id = "direct-1", ownerId = "alice", recipients = setOf("bob"),
            fields = setOf(Field.SLEEP_DURATION), scope = TimeScope.continuousFrom(today.minusDays(7)), createdAtMs = now,
        )
        service.saveGrantWithPolicy(oldGrant).getOrThrow()
        service.planAndEnqueue(now)
        ackAllDue(outbox, now)

        val expandedGrant = oldGrant.copy(
            id = "direct-2",
            fields = setOf(Field.SLEEP_DURATION, Field.WEIGHT),
            createdAtMs = now + 1000L,
        )
        val res = service.saveGlobalDirectProfile(expandedGrant, listOf(oldGrant), nowMs = now + 1000L)
        assertTrue(res.isSuccess)
        assertTrue(data.grants().get("direct-1")!!.revoked)
        assertFalse(data.grants().get("direct-2")!!.revoked)

        val allDue = outbox.due(now + 1000L)
        assertTrue(allDue.none { it.kind == Kinds.PROJECTION_REMOVE }, "Adding a category must emit zero PROJECTION_REMOVEs")
        val dailyUpsert = allDue.single { it.objectKey == "daily/$day" }
        assertEquals(2L, dailyUpsert.objectVersion)
        val payload = Wire.decode(dailyUpsert.wire)!!.payload
        assertTrue(payload.contains("SLEEP_DURATION"))
        assertTrue(payload.contains("WEIGHT"))
    }

    @Test
    fun `N-2 and RE-7 minor narrows global profile succeeds while unconsented expansion fails without revoking existing`() {
        val data = FakeDataAccess()
        val settings = FakeSettings(zoneId = "UTC", ownAgeProfile = AgeProfile.MINOR.name)
        val outbox = buildOutbox(data)
        val service = buildShareService(data, settings, outbox, selfId = "child-1")

        val link = FamilyLinkPolicy.respond(
            FamilyLinkPolicy.createRequest("link-1", "parent-1", "child-1", AgeProfile.MINOR, 100L),
            responderId = "child-1", accept = true, nowMs = 200L,
        )
        data.familyLinks().upsert(
            FamilyLinkRow(link.id, link.parentId, link.childId, link.status.name, link.version, Wire.json.encodeToString(FamilyLink.serializer(), link))
        )

        val today = LocalDate.now(ZoneId.of("UTC"))
        val now = 10_000L
        val existing = ShareGrant(
            id = "g-minor-1", ownerId = "child-1", recipients = setOf("parent-1"),
            fields = setOf(Field.SLEEP_DURATION, Field.WEIGHT),
            scope = TimeScope.continuousFrom(today.minusDays(7)), createdAtMs = now,
        )
        service.saveGrantWithPolicy(existing, guardianCoConsentAtMs = now).getOrThrow()

        // Expand without guardian consent -> fails and does NOT revoke g-minor-1
        val expanded = existing.copy(id = "g-minor-expand", fields = setOf(Field.SLEEP_DURATION, Field.WEIGHT, Field.HRV_DAILY))
        val failExpand = service.saveGlobalDirectProfile(expanded, listOf(existing), nowMs = now + 100L, guardianCoConsentAtMs = null)
        assertTrue(failExpand.isFailure)
        assertFalse(data.grants().get("g-minor-1")!!.revoked)
        assertNull(data.grants().get("g-minor-expand"))

        // Narrow without guardian consent -> succeeds and revokes g-minor-1
        val narrowed = existing.copy(id = "g-minor-narrow", fields = setOf(Field.SLEEP_DURATION))
        val okNarrow = service.saveGlobalDirectProfile(narrowed, listOf(existing), nowMs = now + 200L, guardianCoConsentAtMs = null)
        assertTrue(okNarrow.isSuccess)
        assertTrue(data.grants().get("g-minor-1")!!.revoked)
        assertFalse(data.grants().get("g-minor-narrow")!!.revoked)
    }

    @Test
    fun `RE-5 forwardChildObservations filters types and preserves Quality, NOT_PERMITTED clears hash and SNAPSHOT_REQUEST resyncs`() {
        val parentData = FakeDataAccess()
        val parentSettings = FakeSettings(zoneId = "UTC", childRecipientIdentity = "child-1")
        val parentOutbox = buildOutbox(parentData)

        val link = FamilyLinkPolicy.respond(
            FamilyLinkPolicy.createRequest("link-1", "parent-1", "child-1", AgeProfile.MINOR, 100L),
            responderId = "child-1", accept = true, nowMs = 200L,
        )
        parentData.familyLinks().upsert(
            FamilyLinkRow(link.id, link.parentId, link.childId, link.status.name, link.version, Wire.json.encodeToString(FamilyLink.serializer(), link))
        )

        val weightSuspect = MetricObservation(
            "m-weight", Provider.WITHINGS_API, "w", "m-weight", MetricType.WEIGHT,
            5_000L, null, 41.2, "UTC", "2026-11-18", Quality.SUSPECT,
        )
        val stepsDisallowed = MetricObservation(
            "m-steps", Provider.WITHINGS_API, "w", "m-steps", MetricType.STEPS,
            5_000L, null, 8000.0, "UTC", "2026-11-18", Quality.OK,
        )
        val outOfRangeWeight = MetricObservation(
            "m-bad", Provider.WITHINGS_API, "w", "m-bad", MetricType.WEIGHT,
            5_000L, null, 999.0, "UTC", "2026-11-18", Quality.OK,
        )

        val queued = WithingsService.forwardChildObservations(
            observations = listOf(weightSuspect, stepsDisallowed, outOfRangeWeight),
            childRecipient = "child-1",
            selfId = "parent-1",
            outbox = parentOutbox,
            lastSent = { r, k -> parentData.inbox().sent(r, k)?.let { SentRecord(it.version, it.contentHash) } },
            recordSent = { r, k, v, h -> parentData.inbox().putSent(SentRow(r, k, v, h)) },
            nowMs = 10_000L,
        )
        assertEquals(1, queued)
        val sentItem = parentOutbox.due(10_000L).single()
        assertEquals("own_weight/m-weight", sentItem.objectKey)

        // Child with acceptWeightFromParent=false rejects (NOT_PERMITTED) and never flips opt-in to true
        val childData = FakeDataAccess()
        childData.familyLinks().upsert(
            FamilyLinkRow(link.id, link.parentId, link.childId, link.status.name, link.version, Wire.json.encodeToString(FamilyLink.serializer(), link))
        )
        val childSettings = FakeSettings(acceptWeightFromParent = false)
        val childHandlers = buildFamilyAndWeightHandlers(childData, childSettings)
        val msg = Wire.decode(sentItem.wire)!!
        val resDenied = childHandlers.getValue(WithingsService.FAMILY_WEIGHT_PUSH_KIND)
            .handle(msg.envelope, msg.payload, dummyReceiverStore, 11_000L)
        assertEquals(HandlerResult.Rejected(RejectReason.NOT_PERMITTED), resDenied)
        assertFalse(childSettings.acceptWeightFromParent)

        // Parent receives NOT_PERMITTED ACK -> CANCELLED and sent_projection.contentHash cleared (version stays 1)
        assertTrue(parentOutbox.onAck("child-1", Ack(sentItem.eventId, Ack.Status.REJECTED, RejectReason.NOT_PERMITTED.name)))
        assertEquals(DeliveryState.CANCELLED, DeliveryState.valueOf(parentData.outbox().find(sentItem.eventId)!!.state))
        val sentAfterDeny = parentData.inbox().sent("child-1", "own_weight/m-weight")!!
        assertEquals(1L, sentAfterDeny.version)
        assertEquals("", sentAfterDeny.contentHash)
        assertTrue(parentOutbox.expireStale(12_000L).isEmpty())

        // Child turns toggle ON -> sends SNAPSHOT_REQUEST to verified parent
        childSettings.acceptWeightFromParent = true
        val childOutbox = buildOutbox(childData)
        val childService = buildShareService(childData, childSettings, childOutbox, selfId = "child-1")
        assertEquals(1, childService.sendFamilyWeightResyncRequests(13_000L))
        val reqMsg = Wire.decode(childOutbox.due(13_000L).single().wire)!!

        // Parent handles SNAPSHOT_REQUEST -> clears own_weight/* hashes (if any remain); rejects stranger
        val parentHandlers = buildFamilyAndWeightHandlers(parentData, parentSettings)
        parentData.inbox().putSent(SentRow("child-1", "own_weight/m-old", 3L, "oldhash"))
        assertEquals(
            HandlerResult.Applied,
            parentHandlers.getValue(Kinds.SNAPSHOT_REQUEST).handle(reqMsg.envelope, reqMsg.payload, dummyReceiverStore, 13_500L),
        )
        assertEquals("", parentData.inbox().sent("child-1", "own_weight/m-old")!!.contentHash)

        // Next forwardChildObservations resends m-weight at version 2; child applies and preserves Quality.SUSPECT
        val reQueued = WithingsService.forwardChildObservations(
            observations = listOf(weightSuspect),
            childRecipient = "child-1",
            selfId = "parent-1",
            outbox = parentOutbox,
            lastSent = { r, k -> parentData.inbox().sent(r, k)?.let { SentRecord(it.version, it.contentHash) } },
            recordSent = { r, k, v, h -> parentData.inbox().putSent(SentRow(r, k, v, h)) },
            nowMs = 14_000L,
        )
        assertEquals(1, reQueued)
        val resendItem = parentOutbox.due(14_000L).single()
        assertEquals(2L, resendItem.objectVersion)
        val resendMsg = Wire.decode(resendItem.wire)!!
        val resApplied = childHandlers.getValue(WithingsService.FAMILY_WEIGHT_PUSH_KIND)
            .handle(resendMsg.envelope, resendMsg.payload, dummyReceiverStore, 14_500L)
        assertEquals(HandlerResult.Applied, resApplied)
        val savedObs = childData.observations().latest(MetricType.WEIGHT.name)!!.toDomain()
        assertEquals(41.2, savedObs.value)
        assertEquals(Quality.SUSPECT, savedObs.quality)
    }

    @Test
    fun `SEC-2 FAMILY_LINK_REQUEST blocks UUID hijack and VERIFIED downgrade and RESPONSE requires PENDING`() {
        val data = FakeDataAccess()
        val settings = FakeSettings()
        val handlers = buildFamilyAndWeightHandlers(data, settings)
        val reqHandler = handlers.getValue(Kinds.FAMILY_LINK_REQUEST)
        val respHandler = handlers.getValue(Kinds.FAMILY_LINK_RESPONSE)

        // 1. Parent sends link-1 -> Applied (PENDING)
        val link1 = FamilyLinkPolicy.createRequest("link-1", "parent-1", "child-1", AgeProfile.MINOR, 1_000L)
        val mReq1 = MessageBuilder("parent-1").build(
            "child-1", Kinds.FAMILY_LINK_REQUEST, "family_link", link1.id, link1.version,
            Wire.json.encodeToString(FamilyLink.serializer(), link1), 1_000L,
        )
        assertEquals(HandlerResult.Applied, reqHandler.handle(mReq1.envelope, mReq1.payload, dummyReceiverStore, 1_000L))
        assertEquals(FamilyLinkStatus.PENDING.name, data.familyLinks().get("link-1")!!.status)

        // 2. Stranger attempts UUID hijack on link-1 -> NOT_PERMITTED
        val spoofed = link1.copy(parentId = "attacker", version = 2L)
        val mHijack = MessageBuilder("attacker").build(
            "child-1", Kinds.FAMILY_LINK_REQUEST, "family_link", spoofed.id, spoofed.version,
            Wire.json.encodeToString(FamilyLink.serializer(), spoofed), 1_500L,
        )
        assertEquals(
            HandlerResult.Rejected(RejectReason.NOT_PERMITTED),
            reqHandler.handle(mHijack.envelope, mHijack.payload, dummyReceiverStore, 1_500L),
        )
        assertEquals("parent-1", data.familyLinks().get("link-1")!!.parentId)

        // 3. Verify link-1 on child + parent side, then parent sends link-1 request again -> must NOT downgrade VERIFIED -> PENDING
        val verified1 = FamilyLinkPolicy.respond(link1, responderId = "child-1", accept = true, nowMs = 2_000L)
        data.familyLinks().upsert(
            FamilyLinkRow(verified1.id, verified1.parentId, verified1.childId, verified1.status.name, verified1.version, Wire.json.encodeToString(FamilyLink.serializer(), verified1))
        )
        val downgradeAttempt = link1.copy(version = verified1.version + 1L)
        val mDowngrade = MessageBuilder("parent-1").build(
            "child-1", Kinds.FAMILY_LINK_REQUEST, "family_link", downgradeAttempt.id, downgradeAttempt.version,
            Wire.json.encodeToString(FamilyLink.serializer(), downgradeAttempt), 2_500L,
        )
        assertEquals(
            HandlerResult.Rejected(RejectReason.NOT_PERMITTED),
            reqHandler.handle(mDowngrade.envelope, mDowngrade.payload, dummyReceiverStore, 2_500L),
        )
        assertEquals(FamilyLinkStatus.VERIFIED.name, data.familyLinks().get("link-1")!!.status)

        // 4. Child cannot transition REVOKED -> VERIFIED without a fresh parent request
        val revoked2 = link1.copy(id = "link-2", status = FamilyLinkStatus.REVOKED, version = 2L)
        data.familyLinks().upsert(
            FamilyLinkRow(revoked2.id, revoked2.parentId, revoked2.childId, revoked2.status.name, revoked2.version, Wire.json.encodeToString(FamilyLink.serializer(), revoked2))
        )
        val badVerify = revoked2.copy(status = FamilyLinkStatus.VERIFIED, version = 3L, verifiedAtMs = 3_500L)
        val mBadResp = MessageBuilder("child-1").build(
            "parent-1", Kinds.FAMILY_LINK_RESPONSE, "family_link", badVerify.id, badVerify.version,
            Wire.json.encodeToString(FamilyLink.serializer(), badVerify), 3_500L,
        )
        assertEquals(
            HandlerResult.Rejected(RejectReason.NOT_PERMITTED),
            respHandler.handle(mBadResp.envelope, mBadResp.payload, dummyReceiverStore, 3_500L),
        )
        assertEquals(FamilyLinkStatus.REVOKED.name, data.familyLinks().get("link-2")!!.status)

        // 5. After fresh parent request (version = 4), response transitions PENDING -> VERIFIED
        val freshReq = revoked2.copy(status = FamilyLinkStatus.PENDING, version = 4L)
        val mFreshReq = MessageBuilder("parent-1").build(
            "child-1", Kinds.FAMILY_LINK_REQUEST, "family_link", freshReq.id, freshReq.version,
            Wire.json.encodeToString(FamilyLink.serializer(), freshReq), 4_000L,
        )
        assertEquals(HandlerResult.Applied, reqHandler.handle(mFreshReq.envelope, mFreshReq.payload, dummyReceiverStore, 4_000L))
        assertEquals(FamilyLinkStatus.PENDING.name, data.familyLinks().get("link-2")!!.status)

        val goodResp = freshReq.copy(status = FamilyLinkStatus.VERIFIED, version = 5L, verifiedAtMs = 4_500L)
        val mGoodResp = MessageBuilder("child-1").build(
            "parent-1", Kinds.FAMILY_LINK_RESPONSE, "family_link", goodResp.id, goodResp.version,
            Wire.json.encodeToString(FamilyLink.serializer(), goodResp), 4_500L,
        )
        assertEquals(HandlerResult.Applied, respHandler.handle(mGoodResp.envelope, mGoodResp.payload, dummyReceiverStore, 4_500L))
        assertEquals(FamilyLinkStatus.VERIFIED.name, data.familyLinks().get("link-2")!!.status)
    }
}
