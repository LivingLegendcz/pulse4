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

    @Test
    fun `S5 Projector measuredAtUtcMs uses only permitted fields`() {
        val d = LocalDate.of(2026, 11, 18)
        val sleepObs = app.pulse4.domain.MetricObservation("s1", Provider.GARMIN_PERSONAL, "a", "s1", app.pulse4.domain.MetricType.SLEEP_DURATION, 1_000L, 7_000L, 25_000.0, "Europe/Prague", d.toString())
        val weightObs = app.pulse4.domain.MetricObservation("w1", Provider.WITHINGS_API, "a", "w1", app.pulse4.domain.MetricType.WEIGHT, 99_000L, null, 78.5, "Europe/Prague", d.toString())
        val day = OwnerDayAssembler.assemble(d, listOf(sleepObs, weightObs))
        val sleepOnlyGrant = ShareGrant("g1", "alice", setOf("bob"), setOf(Field.SLEEP_DURATION), scope = TimeScope.continuousFrom(d), createdAtMs = 1L)
        val proj = assertNotNull(Projector.project(sleepOnlyGrant, day, 100_000L))
        assertEquals(7_000L, proj.measuredAtUtcMs)
    }

    @Test
    fun `S6 handover invalidated when initiator demoted or leaves`() {
        val g = Group("grp", "T", "alice", "alice", setOf("alice", "bob", "eva"), 1L, admins = setOf("alice", "bob"))
        val handed = assertNotNull(CircleAdminPolicy.initiateHandover(g, "bob", "eva", nowMs = 1000L))
        val demoted = assertNotNull(CircleAdminPolicy.demoteAdmin(handed, "alice", "bob"))
        assertNull(demoted.pendingAdminHandover)
        // Even if pendingAdminHandover were somehow still present with demoted initiator, acceptHandover rejects it
        val staleHandover = demoted.copy(pendingAdminHandover = handed.pendingAdminHandover)
        assertNull(CircleAdminPolicy.acceptHandover(staleHandover, "eva", nowMs = 2000L))
    }

    @Test
    fun `S7 effectiveRecipients is fail-closed when group is missing or dissolved`() {
        val gGrant = ShareGrant("g1", "alice", setOf("bob"), setOf(Field.SLEEP_DURATION), scope = TimeScope.continuousFrom(LocalDate.of(2026, 11, 18)), groupId = "grp-1", createdAtMs = 1L)
        assertEquals(emptySet(), MembershipPolicy.effectiveRecipients(gGrant, null))
        val dissolved = Group("grp-1", "T", "alice", "alice", setOf("alice", "bob"), 2L, dissolved = true)
        assertEquals(emptySet(), MembershipPolicy.effectiveRecipients(gGrant, dissolved))
    }

    @Test
    fun `S8 canActivateShareGrant validates positive co-consent and bound guardian`() {
        val d = LocalDate.of(2026, 11, 18)
        val g = ShareGrant("g1", "child", setOf("bob"), setOf(Field.SLEEP_DURATION), scope = TimeScope.continuousFrom(d), createdAtMs = 1L)
        val link = FamilyLink("fl1", "parent1", "child", FamilyLinkStatus.VERIFIED, AgeProfile.MINOR, createdAtMs = 1L, verifiedAtMs = 2L)
        assertFalse(FamilyLinkPolicy.canActivateShareGrant("child", AgeProfile.MINOR, g, listOf(link), 0L))
        assertFalse(FamilyLinkPolicy.canActivateShareGrant("child", AgeProfile.MINOR, g, listOf(link), 500L, coConsentGuardianId = "stranger"))
        assertTrue(FamilyLinkPolicy.canActivateShareGrant("child", AgeProfile.MINOR, g, listOf(link), 500L, coConsentGuardianId = "parent1"))
    }

    @Test
    fun `D6 OwnerDayAssembler deduplicates SLEEP_DURATION across providers per day`() {
        val d = LocalDate.of(2026, 11, 18)
        val g1 = app.pulse4.domain.MetricObservation("g1", Provider.GARMIN_PERSONAL, "a", "g1", app.pulse4.domain.MetricType.SLEEP_DURATION, 100L, null, 20_000.0, "Europe/Prague", d.toString())
        val g2 = app.pulse4.domain.MetricObservation("g2", Provider.GARMIN_PERSONAL, "a", "g2", app.pulse4.domain.MetricType.SLEEP_DURATION, 200L, null, 3_000.0, "Europe/Prague", d.toString())
        val w1 = app.pulse4.domain.MetricObservation("w1", Provider.WITHINGS_API, "a", "w1", app.pulse4.domain.MetricType.SLEEP_DURATION, 150L, null, 24_000.0, "Europe/Prague", d.toString())
        val day = OwnerDayAssembler.assemble(d, listOf(g1, g2, w1))
        assertEquals("23000.0", day.values.getValue(Field.SLEEP_DURATION).toString())
    }
}
