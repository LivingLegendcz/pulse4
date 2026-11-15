package app.pulse4.sharing

import app.pulse4.domain.Activity
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Sport
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyExtensionsTest {
    private val day = LocalDate.of(2026, 11, 15)

    // --- Family Links & Minor/Guardian Safeguards ---

    @Test
    fun `family link request reject then confirm never creates a ShareGrant or exposes data`() {
        val req1 = FamilyLinkPolicy.createRequest("fl-1", "parent", "child", AgeProfile.ADULT, nowMs = 100)
        assertEquals(FamilyLinkStatus.PENDING, req1.status)
        assertEquals(1L, req1.version)

        // Only child can respond
        assertFailsWith<IllegalArgumentException> {
            FamilyLinkPolicy.respond(req1, responderId = "parent", accept = true, nowMs = 110)
        }

        // Rejecting produces REJECTED and cannot be responded to again
        val rejected = FamilyLinkPolicy.respond(req1, responderId = "child", accept = false, nowMs = 120)
        assertEquals(FamilyLinkStatus.REJECTED, rejected.status)
        assertNull(rejected.verifiedAtMs)
        assertEquals(2L, rejected.version)
        assertFailsWith<IllegalArgumentException> {
            FamilyLinkPolicy.respond(rejected, responderId = "child", accept = true, nowMs = 130)
        }

        // Confirming a new request sets VERIFIED; without a ShareGrant, no data is shared
        val req2 = FamilyLinkPolicy.createRequest("fl-2", "parent", "child", AgeProfile.ADULT, nowMs = 200)
        val verified = FamilyLinkPolicy.respond(req2, responderId = "child", accept = true, nowMs = 250)
        assertEquals(FamilyLinkStatus.VERIFIED, verified.status)
        assertEquals(250L, verified.verifiedAtMs)
        assertEquals(2L, verified.version)

        val emptyGrants = emptyList<ShareGrant>()
        assertTrue(emptyGrants.none { it.ownerId == "child" && "parent" in it.recipients })
    }

    @Test
    fun `minor account cannot activate ShareGrant without verified family link and guardian co-consent`() {
        val minorGrant = ShareGrant(
            id = "g-minor",
            ownerId = "minor1",
            recipients = setOf("coach"),
            fields = setOf(Field.SLEEP_DURATION),
            scope = TimeScope.from(day),
            createdAtMs = 1000,
        )
        val pendingLink = FamilyLinkPolicy.createRequest("fl-m", "guardian1", "minor1", AgeProfile.MINOR, nowMs = 500)
        val verifiedLink = FamilyLinkPolicy.respond(pendingLink, "minor1", accept = true, nowMs = 600)

        // Solo minor consent (no guardian co-consent) -> blocked
        assertFalse(
            FamilyLinkPolicy.canActivateShareGrant(
                ownerId = "minor1",
                ownerAgeProfile = AgeProfile.MINOR,
                grant = minorGrant,
                verifiedLinks = listOf(verifiedLink),
                guardianCoConsentAtMs = null,
            ),
        )

        // Guardian co-consent without a VERIFIED link -> blocked
        assertFalse(
            FamilyLinkPolicy.canActivateShareGrant(
                ownerId = "minor1",
                ownerAgeProfile = AgeProfile.MINOR,
                grant = minorGrant,
                verifiedLinks = listOf(pendingLink),
                guardianCoConsentAtMs = 900,
            ),
        )

        // Both VERIFIED link and guardian co-consent -> allowed
        assertTrue(
            FamilyLinkPolicy.canActivateShareGrant(
                ownerId = "minor1",
                ownerAgeProfile = AgeProfile.MINOR,
                grant = minorGrant,
                verifiedLinks = listOf(verifiedLink),
                guardianCoConsentAtMs = 900,
            ),
        )

        // Adult owner -> allowed without family link or guardian co-consent
        assertTrue(
            FamilyLinkPolicy.canActivateShareGrant(
                ownerId = "minor1",
                ownerAgeProfile = AgeProfile.ADULT,
                grant = minorGrant,
                verifiedLinks = emptyList(),
                guardianCoConsentAtMs = null,
            ),
        )
    }

    // --- Source-scoped Sharing ---

    @Test
    fun `source-scoped ShareGrant allows Withings WEIGHT and blocks Garmin WEIGHT and other Garmin metrics`() {
        val observations = listOf(
            MetricObservation("w1", Provider.WITHINGS_API, "acc-w", "ext-1", MetricType.WEIGHT, 1000L, null, 74.2, "Europe/Prague", day.toString()),
            MetricObservation("g1", Provider.GARMIN_PERSONAL, "acc-g", "ext-2", MetricType.WEIGHT, 2000L, null, 75.5, "Europe/Prague", day.toString()),
            MetricObservation("g2", Provider.GARMIN_PERSONAL, "acc-g", "ext-3", MetricType.SLEEP_DURATION, 3000L, null, 28_000.0, "Europe/Prague", day.toString()),
        )

        val withingsOnlyGrant = ShareGrant(
            id = "g-src",
            ownerId = "josef",
            recipients = setOf("bob"),
            fields = setOf(Field.WEIGHT, Field.SLEEP_DURATION),
            scope = TimeScope.from(day),
            createdAtMs = 1,
            sources = setOf(Provider.WITHINGS_API),
        )

        assertTrue(withingsOnlyGrant.allowsProvider(Provider.WITHINGS_API))
        assertFalse(withingsOnlyGrant.allowsProvider(Provider.GARMIN_PERSONAL))

        val assembled = OwnerDayAssembler.assembleForGrant(day, observations, withingsOnlyGrant)
        assertEquals("74.2", assembled.values.getValue(Field.WEIGHT).toString())
        assertFalse(Field.SLEEP_DURATION in assembled.values)

        val projection = assertNotNull(Projector.project(withingsOnlyGrant, day, observations, nowMs = 5000L))
        assertEquals(setOf(Field.WEIGHT), projection.fields.keys)
        assertEquals("74.2", projection.fields.getValue(Field.WEIGHT).toString())

        // Legacy grant (sources == null) allows all providers
        val legacyGrant = withingsOnlyGrant.copy(sources = null)
        assertTrue(legacyGrant.allowsProvider(Provider.GARMIN_PERSONAL))
        val legacyProjection = assertNotNull(Projector.project(legacyGrant, day, observations, nowMs = 5000L))
        assertEquals(setOf(Field.WEIGHT, Field.SLEEP_DURATION), legacyProjection.fields.keys)
    }

    @Test
    fun `ActivityProjector respects source-scoped ShareGrant`() {
        val act = Activity(
            id = "a1",
            provider = Provider.GARMIN_PERSONAL,
            accountId = "acc",
            externalId = "1",
            sport = Sport.RUNNING,
            startUtcMs = java.time.Instant.parse("2026-11-15T07:00:00Z").toEpochMilli(),
            zoneId = "Europe/Prague",
            totalTimeS = 1800.0,
            distanceM = 5000.0,
        )
        val withingsGrant = ShareGrant(
            id = "g-act",
            ownerId = "josef",
            recipients = setOf("bob"),
            fields = setOf(Field.ACT_SUMMARY),
            scope = TimeScope.from(day),
            createdAtMs = 1,
            sources = setOf(Provider.WITHINGS_API),
        )
        assertNull(ActivityProjector.project(withingsGrant, act, emptyList(), emptyList(), emptyList()))
        assertNotNull(ActivityProjector.project(withingsGrant.copy(sources = setOf(Provider.GARMIN_PERSONAL)), act, emptyList(), emptyList(), emptyList()))
    }

    // --- Multi-Admin Circle Management ---

    @Test
    fun `multi-admin promote demote and last-admin leave handover`() {
        val initial = Group(
            id = "grp",
            name = "Běžci",
            ownerId = "alice",
            adminId = "alice",
            members = setOf("alice", "bob", "eva"),
            version = 1L,
        )
        assertEquals(setOf("alice"), initial.effectiveAdmins)

        // Non-admin cannot promote
        assertNull(CircleAdminPolicy.promoteAdmin(initial, actorId = "bob", targetMemberId = "bob"))

        // Admin promotes bob
        val twoAdmins = assertNotNull(CircleAdminPolicy.promoteAdmin(initial, actorId = "alice", targetMemberId = "bob"))
        assertEquals(setOf("alice", "bob"), twoAdmins.effectiveAdmins)
        assertEquals(2L, twoAdmins.version)

        // With two admins, alice can leave directly
        val afterAliceLeaves = assertNotNull(CircleAdminPolicy.leaveCircle(twoAdmins, actorId = "alice"))
        assertEquals(setOf("bob", "eva"), afterAliceLeaves.members)
        assertEquals(setOf("bob"), afterAliceLeaves.effectiveAdmins)
        assertEquals("bob", afterAliceLeaves.adminId)
        assertFalse(afterAliceLeaves.dissolved)

        // Now bob is the sole admin with eva still in the circle: cannot demote or leave directly
        assertNull(CircleAdminPolicy.demoteAdmin(afterAliceLeaves, actorId = "bob", targetMemberId = "bob"))
        assertFalse(CircleAdminPolicy.canLeaveDirectly(afterAliceLeaves, actorId = "bob"))
        assertNull(CircleAdminPolicy.leaveCircle(afterAliceLeaves, actorId = "bob"))

        // Handover to eva: initiate and accept
        val handedOver = assertNotNull(CircleAdminPolicy.initiateHandover(afterAliceLeaves, actorId = "bob", targetMemberId = "eva", nowMs = 1000L))
        assertNotNull(handedOver.pendingAdminHandover)
        // Expired handover fails
        assertNull(CircleAdminPolicy.acceptHandover(handedOver, actorId = "eva", nowMs = 1000L + 7L * 24 * 3600 * 1000 + 1))
        // Wrong actor fails
        assertNull(CircleAdminPolicy.acceptHandover(handedOver, actorId = "bob", nowMs = 2000L))

        val accepted = assertNotNull(CircleAdminPolicy.acceptHandover(handedOver, actorId = "eva", nowMs = 2000L, removeInitiator = true))
        assertNull(accepted.pendingAdminHandover)
        assertEquals(setOf("eva"), accepted.effectiveAdmins)
        assertEquals("eva", accepted.adminId)
        assertEquals(setOf("eva"), accepted.members)

        // Last member leaving dissolves the circle
        val dissolvedByLeave = assertNotNull(CircleAdminPolicy.leaveCircle(accepted, actorId = "eva"))
        assertTrue(dissolvedByLeave.dissolved)
        assertTrue(dissolvedByLeave.members.isEmpty())
        assertTrue(dissolvedByLeave.effectiveAdmins.isEmpty())
    }
}
