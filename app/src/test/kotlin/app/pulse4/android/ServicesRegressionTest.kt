package app.pulse4.android

import app.pulse4.android.work.PulseSyncService
import app.pulse4.sharing.AgeProfile
import app.pulse4.sharing.FamilyLinkPolicy
import app.pulse4.sharing.FamilyLinkStatus
import app.pulse4.sharing.Field
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.TimeScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
}
