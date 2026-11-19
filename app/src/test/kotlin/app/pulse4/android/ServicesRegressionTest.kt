package app.pulse4.android

import app.pulse4.android.data.SentRow
import app.pulse4.android.di.ServiceMigrations
import app.pulse4.android.di.isValidChildBodyComposition
import app.pulse4.android.di.mergeActivityGrantsForRecipient
import app.pulse4.android.di.multisportSessionIds
import app.pulse4.android.di.updateSentRowOnRevoke
import app.pulse4.android.work.PulseSyncService
import app.pulse4.domain.Activity
import app.pulse4.domain.MetricType
import app.pulse4.domain.PLog
import app.pulse4.domain.Provider
import app.pulse4.domain.Sport
import app.pulse4.domain.TrackPoint
import app.pulse4.fit.ParsedFit
import app.pulse4.fit.ParsedLap
import app.pulse4.sharing.AgeProfile
import app.pulse4.sharing.FamilyLinkPolicy
import app.pulse4.sharing.FamilyLinkStatus
import app.pulse4.sharing.Field
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.TimeScope
import app.pulse4.sync.Ack
import app.pulse4.sync.DeliveryState
import app.pulse4.sync.OutboxItem
import app.pulse4.sync.OutboxManager
import app.pulse4.sync.OutboxStore
import app.pulse4.sync.RejectReason
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServicesRegressionTest {
    @Test
    fun `SEC-2 family link starts PENDING and only child can verify`() {
        val req = FamilyLinkPolicy.createRequest("link-1", "parent-1", "child-1", AgeProfile.MINOR, nowMs = 1_000L)
        assertEquals(FamilyLinkStatus.PENDING, req.status)
        assertFailsWith<IllegalArgumentException> {
            FamilyLinkPolicy.respond(req, responderId = "parent-1", accept = true, nowMs = 2_000L)
        }
        val verified = FamilyLinkPolicy.respond(req, responderId = "child-1", accept = true, nowMs = 2_000L)
        assertEquals(FamilyLinkStatus.VERIFIED, verified.status)
        assertEquals(2_000L, verified.verifiedAtMs)
    }

    @Test
    fun `ENG-2 minor cannot activate ShareGrant without verified link and guardian consent`() {
        val grant = ShareGrant(
            id = "g-1",
            ownerId = "child-1",
            recipients = setOf("parent-1"),
            fields = setOf(Field.WEIGHT),
            scope = TimeScope.continuousFrom(java.time.LocalDate.of(2026, 11, 1)),
            groupId = null,
            createdAtMs = 1_000L,
        )
        assertFalse(
            FamilyLinkPolicy.canActivateShareGrant(
                ownerId = "child-1",
                ownerAgeProfile = AgeProfile.MINOR,
                grant = grant,
                verifiedLinks = emptyList(),
                guardianCoConsentAtMs = null,
            )
        )
        val verifiedLink = FamilyLinkPolicy.respond(
            FamilyLinkPolicy.createRequest("link-1", "parent-1", "child-1", AgeProfile.MINOR, 1_000L),
            responderId = "child-1",
            accept = true,
            nowMs = 2_000L,
        )
        assertFalse(
            FamilyLinkPolicy.canActivateShareGrant(
                ownerId = "child-1",
                ownerAgeProfile = AgeProfile.MINOR,
                grant = grant,
                verifiedLinks = listOf(verifiedLink),
                guardianCoConsentAtMs = null,
            )
        )
        assertTrue(
            FamilyLinkPolicy.canActivateShareGrant(
                ownerId = "child-1",
                ownerAgeProfile = AgeProfile.MINOR,
                grant = grant,
                verifiedLinks = listOf(verifiedLink),
                guardianCoConsentAtMs = 3_000L,
            )
        )
    }

    @Test
    fun `R2 PLT-1 heartbeat is false before service ticks`() {
        assertFalse(PulseSyncService.hasFreshHeartbeat(System.currentTimeMillis()))
    }

    @Test
    fun `RE-1 deriveDeliveryHashes produces distinct deterministic lxmf and legacy pulse hashes`() {
        val pubKey = Base64.getEncoder().encodeToString(ByteArray(64) { (it + 1).toByte() })
        val (lxmf, legacy) = ServiceMigrations.deriveDeliveryHashes(pubKey)
        assertEquals(32, lxmf.length)
        assertEquals(32, legacy.length)
        assertNotEquals(lxmf, legacy)
        assertEquals(lxmf to legacy, ServiceMigrations.deriveDeliveryHashes(pubKey))
    }

    @Test
    fun `RE-5 child body composition validator accepts body comp types within range and rejects invalid`() {
        assertTrue(isValidChildBodyComposition(MetricType.WEIGHT, 34.5))
        assertTrue(isValidChildBodyComposition(MetricType.BODY_FAT, 18.2))
        assertTrue(isValidChildBodyComposition(MetricType.MUSCLE_MASS, 22.0))
        assertTrue(isValidChildBodyComposition(MetricType.BODY_WATER, 55.0))
        assertTrue(isValidChildBodyComposition(MetricType.BONE_MASS, 2.1))
        assertTrue(isValidChildBodyComposition(MetricType.VISCERAL_FAT, 4.0))
        assertTrue(isValidChildBodyComposition(MetricType.BMR, 1450.0))
        assertFalse(isValidChildBodyComposition(MetricType.WEIGHT, Double.NaN))
        assertFalse(isValidChildBodyComposition(MetricType.WEIGHT, 500.0))
        assertFalse(isValidChildBodyComposition(MetricType.STEPS, 5000.0))
    }

    @Test
    fun `RE-5 Outbox NOT_PERMITTED does not trigger snapshot reset`() {
        val store = object : OutboxStore {
            private val items = LinkedHashMap<String, OutboxItem>()
            override fun insert(item: OutboxItem) { items[item.eventId] = item }
            override fun update(item: OutboxItem) { items[item.eventId] = item }
            override fun find(eventId: String): OutboxItem? = items[eventId]
            override fun all(): List<OutboxItem> = items.values.toList()
        }
        val outbox = OutboxManager(store)
        val item = OutboxItem(
            eventId = "ev-1",
            recipient = "child-1",
            kind = "family.weight.push",
            objectType = "own_weight",
            objectId = "obs-1",
            objectVersion = 1L,
            grantId = null,
            wire = byteArrayOf(1, 2, 3),
            createdAtMs = 1_000L,
        )
        outbox.enqueue(item)
        assertTrue(outbox.onAck("child-1", Ack("ev-1", Ack.Status.REJECTED, RejectReason.NOT_PERMITTED.name)))
        assertEquals(DeliveryState.CANCELLED, store.find("ev-1")?.state)
        assertTrue(outbox.expireStale(2_000L).isEmpty())

        val item2 = item.copy(eventId = "ev-2", objectId = "obs-2")
        outbox.enqueue(item2)
        assertTrue(outbox.onAck("child-1", Ack("ev-2", Ack.Status.NEED_SNAPSHOT, null)))
        assertEquals(DeliveryState.EXPIRED_NEEDS_SNAPSHOT, store.find("ev-2")?.state)
        assertEquals(listOf("child-1" to "own_weight/obs-2"), outbox.expireStale(2_000L))
    }

    @Test
    fun `NEW-10 PLog redacts emails and bearer tokens in release mode`() {
        val prevDebug = PLog.isDebug
        try {
            PLog.isDebug = false
            val scrubbed = PLog.redact("Login failed for user@example.com with Bearer secretToken123")
            assertFalse("user@example.com" in scrubbed)
            assertFalse("secretToken123" in scrubbed)
            assertTrue("<email>" in scrubbed)
        } finally {
            PLog.isDebug = prevDebug
        }
    }

    @Test
    fun `NEW-12 cleanLegacyFabricatedActivity strips stamina and perfCond and empty power zones`() {
        val act = Activity(
            id = "fit-abc",
            provider = Provider.MANUAL,
            accountId = "local",
            externalId = "abc",
            sport = Sport.RUNNING,
            startUtcMs = 1_000L,
            zoneId = "Europe/Prague",
            totalTimeS = 1800.0,
            movingTimeS = 1800.0,
            avgPowerW = null,
            powerZoneTimesS = listOf(100.0, 200.0),
        )
        val track = listOf(
            TrackPoint(tMs = 1_000L, lat = 50.0, lon = 14.0, staminaPct = 85.0, staminaPotentialPct = 90.0, performanceCondition = 3)
        )
        val (cleanedAct, cleanedTrack) = ServiceMigrations.cleanLegacyFabricatedActivity(act, track)
        assertTrue(cleanedAct.powerZoneTimesS.isEmpty())
        assertNull(cleanedTrack.single().staminaPct)
        assertNull(cleanedTrack.single().staminaPotentialPct)
        assertNull(cleanedTrack.single().performanceCondition)

        val reparsed = ParsedFit(
            sport = Sport.RUNNING,
            startMs = 1_000L,
            totalTimeS = 1800.0,
            movingTimeS = 1800.0,
            distanceM = 5000.0,
            ascentM = null,
            descentM = null,
            avgHeartRate = 150,
            maxHeartRate = 175,
            avgCadence = 176.0,
            avgPowerW = null,
            caloriesKcal = 350.0,
            points = listOf(TrackPoint(tMs = 1_000L, lat = 50.0, lon = 14.0, cadence = 176)),
            laps = listOf(ParsedLap(startMs = 1_000L, endMs = 1_801_000L, distanceM = 5000.0, startLat = null, startLon = null, avgHeartRate = 150)),
        )
        val (reparsedAct, reparsedTrack) = ServiceMigrations.cleanLegacyFabricatedActivity(act, track, reparsed)
        assertEquals(1, reparsedAct.laps.size)
        assertEquals(176.0, reparsedAct.avgCadence)
        assertEquals(176, reparsedTrack.single().cadence)
    }

    @Test
    fun `RE-4 multisportSessionIds generates primary and suffixed leg IDs`() {
        val ids = multisportSessionIds("fit-hash123", "hash123", 3)
        assertEquals(
            listOf(
                "fit-hash123" to "hash123",
                "fit-hash123-s1" to "hash123-s1",
                "fit-hash123-s2" to "hash123-s2",
            ),
            ids,
        )
    }

    @Test
    fun `RE-6 mergeActivityGrantsForRecipient unions fields and sources deterministically`() {
        val g1 = ShareGrant(
            id = "g-2",
            ownerId = "self",
            recipients = setOf("peer-1"),
            fields = setOf(Field.ACT_SUMMARY),
            scope = TimeScope.continuousFrom(java.time.LocalDate.of(2026, 11, 1)),
            groupId = null,
            createdAtMs = 1_000L,
            sources = setOf(Provider.GARMIN_PERSONAL),
        )
        val g2 = g1.copy(
            id = "g-1",
            fields = setOf(Field.ACT_ROUTE),
            sources = setOf(Provider.MANUAL),
        )
        val merged = mergeActivityGrantsForRecipient(listOf(g1, g2))
        assertEquals("g-1", merged.id)
        assertEquals(setOf(Field.ACT_SUMMARY, Field.ACT_ROUTE), merged.fields)
        assertEquals(setOf(Provider.GARMIN_PERSONAL, Provider.MANUAL), merged.sources)
    }

    @Test
    fun `NEW-1 and R3-3 updateSentRowOnRevoke clears hash and preserves monotonic version`() {
        val existing = SentRow(recipient = "peer-1", objectKey = "daily/2026-11-18", version = 5L, contentHash = "abc123")
        val kept = updateSentRowOnRevoke(
            existing = existing,
            recipient = "peer-1",
            objectKey = "daily/2026-11-18",
            isCoveredByOtherGrant = true,
            removeVersion = 100L,
        )
        assertEquals(SentRow(recipient = "peer-1", objectKey = "daily/2026-11-18", version = 5L, contentHash = ""), kept)
        val uncovered = updateSentRowOnRevoke(
            existing = existing,
            recipient = "peer-1",
            objectKey = "daily/2026-11-18",
            isCoveredByOtherGrant = false,
            removeVersion = 100L,
        )
        assertEquals(SentRow(recipient = "peer-1", objectKey = "daily/2026-11-18", version = 100L, contentHash = ""), uncovered)
    }
}
