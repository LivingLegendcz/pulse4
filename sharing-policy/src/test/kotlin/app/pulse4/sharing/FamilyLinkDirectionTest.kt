package app.pulse4.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FamilyLinkDirectionTest {
    private val child = "child"
    private val parent = "parent"

    @Test
    fun `child initiated link is MINOR with the child as initiator`() {
        val l = FamilyLinkPolicy.createChildRequest("fl", parent, child, 10)
        assertEquals(AgeProfile.MINOR, l.childAgeProfile)
        assertEquals(child, l.initiator)
        assertEquals(parent, l.counterparty)
        assertEquals(FamilyLinkStatus.PENDING, l.status)
        assertFailsWith<IllegalArgumentException> { FamilyLinkPolicy.createChildRequest("fl", child, child, 10) }
    }

    @Test
    fun `only the counterparty can verify`() {
        val l = FamilyLinkPolicy.createChildRequest("fl", parent, child, 10)
        assertFailsWith<IllegalArgumentException> { FamilyLinkPolicy.respond(l, child, accept = true, nowMs = 11) }
        assertFailsWith<IllegalArgumentException> { FamilyLinkPolicy.respond(l, "stranger", accept = true, nowMs = 11) }
        val v = FamilyLinkPolicy.respond(l, parent, accept = true, nowMs = 11)
        assertEquals(FamilyLinkStatus.VERIFIED, v.status)
        assertEquals(11L, v.verifiedAtMs)
        assertEquals(AgeProfile.MINOR, v.childAgeProfile)
        assertEquals(FamilyLinkStatus.REJECTED, FamilyLinkPolicy.respond(l, parent, accept = false, nowMs = 11).status)
        assertTrue(GuardianConsentPolicy.isGuardianLink(v, child))
    }

    @Test
    fun `parent initiated link keeps working and legacy rows stay parent initiated`() {
        val l = FamilyLinkPolicy.createRequest("fl", parent, child, AgeProfile.MINOR, 10)
        assertEquals(parent, l.initiator)
        assertEquals(child, l.counterparty)
        assertFailsWith<IllegalArgumentException> { FamilyLinkPolicy.respond(l, parent, accept = true, nowMs = 11) }
        assertEquals(FamilyLinkStatus.VERIFIED, FamilyLinkPolicy.respond(l, child, accept = true, nowMs = 11).status)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacy = json.decodeFromString<FamilyLink>(
            """{"id":"a","parentId":"p","childId":"c","status":"PENDING","createdAtMs":1}""",
        )
        assertNull(legacy.initiatorId)
        assertEquals("p", legacy.initiator)
        assertEquals(l, json.decodeFromString<FamilyLink>(json.encodeToString(l)))
    }

    @Test
    fun `incoming request initiator comes from the authenticated author`() {
        val fromChild = FamilyLinkPolicy.createChildRequest("fl", parent, child, 10)
        // parent's device receives the child's request
        val stored = assertNotNull(FamilyLinkPolicy.acceptIncomingRequest(fromChild, parent, child))
        assertEquals(child, stored.initiator)
        assertEquals(FamilyLinkStatus.PENDING, stored.status)
        // a payload that lies about initiator / status is normalized
        val lying = fromChild.copy(initiatorId = parent, status = FamilyLinkStatus.VERIFIED, verifiedAtMs = 5, guardianConsentAtMs = 7)
        val norm = assertNotNull(FamilyLinkPolicy.acceptIncomingRequest(lying, parent, child))
        assertEquals(child, norm.initiator)
        assertEquals(FamilyLinkStatus.PENDING, norm.status)
        assertNull(norm.verifiedAtMs)
        assertNull(norm.guardianConsentAtMs)
        // parent-initiated arriving at the child
        val fromParent = FamilyLinkPolicy.createRequest("fl2", parent, child, AgeProfile.MINOR, 10)
        assertEquals(parent, FamilyLinkPolicy.acceptIncomingRequest(fromParent, child, parent)?.initiator)
    }

    @Test
    fun `child can neither claim adult nor name someone else as the child`() {
        val adultClaim = FamilyLinkPolicy.createChildRequest("fl", parent, child, 10).copy(childAgeProfile = AgeProfile.ADULT)
        assertNull(FamilyLinkPolicy.acceptIncomingRequest(adultClaim, parent, child))
        // author is not a party of the link
        val fromChild = FamilyLinkPolicy.createChildRequest("fl", parent, child, 10)
        assertNull(FamilyLinkPolicy.acceptIncomingRequest(fromChild, parent, "stranger"))
        // receiver is not a party
        assertNull(FamilyLinkPolicy.acceptIncomingRequest(fromChild, "stranger", child))
        // sending a request to oneself, or parent == child
        assertNull(FamilyLinkPolicy.acceptIncomingRequest(fromChild, child, child))
        assertNull(FamilyLinkPolicy.acceptIncomingRequest(fromChild.copy(parentId = child), child, child))
    }

    @Test
    fun `remote response is valid only from the counterparty of a pending link`() {
        val l = FamilyLinkPolicy.createChildRequest("fl", parent, child, 10)
        assertTrue(FamilyLinkPolicy.isValidRemoteResponse(l, parent))
        assertFalse(FamilyLinkPolicy.isValidRemoteResponse(l, child))
        assertFalse(FamilyLinkPolicy.isValidRemoteResponse(l, "stranger"))
        assertFalse(FamilyLinkPolicy.isValidRemoteResponse(l.copy(status = FamilyLinkStatus.VERIFIED), parent))
        val p = FamilyLinkPolicy.createRequest("fl2", parent, child, AgeProfile.MINOR, 10)
        assertTrue(FamilyLinkPolicy.isValidRemoteResponse(p, child))
        assertFalse(FamilyLinkPolicy.isValidRemoteResponse(p, parent))
    }

    @Test
    fun `onboarding is complete for adult or for a minor with a verified guardian link`() {
        val pending = FamilyLinkPolicy.createChildRequest("fl", parent, child, 10)
        val verified = FamilyLinkPolicy.respond(pending, parent, accept = true, nowMs = 11)
        val revoked = FamilyLinkPolicy.removeLink(verified, parent)
        assertTrue(FamilyLinkPolicy.isOnboardingComplete(AgeState.ADULT, child, emptyList()))
        assertFalse(FamilyLinkPolicy.isOnboardingComplete(AgeState.UNKNOWN, child, listOf(verified)))
        assertFalse(FamilyLinkPolicy.isOnboardingComplete(AgeState.MINOR, child, emptyList()))
        assertFalse(FamilyLinkPolicy.isOnboardingComplete(AgeState.MINOR, child, listOf(pending)))
        assertFalse(FamilyLinkPolicy.isOnboardingComplete(AgeState.MINOR, child, listOf(revoked)))
        assertFalse(FamilyLinkPolicy.isOnboardingComplete(AgeState.MINOR, child, listOf(verified.copy(childAgeProfile = AgeProfile.ADULT))))
        assertFalse(FamilyLinkPolicy.isOnboardingComplete(AgeState.MINOR, "someone-else", listOf(verified)))
        assertTrue(FamilyLinkPolicy.isOnboardingComplete(AgeState.MINOR, child, listOf(verified)))
    }

    @Test
    fun `declared minor gets its guardian recorded when the child initiated link is verified`() {
        val declared = AgeProfilePolicy.declare(AgeProfileRecord(), AgeState.MINOR, 5)!!
        assertNull(declared.lockedByGuardianId)
        val pending = FamilyLinkPolicy.createChildRequest("fl", parent, child, 10)
        // pending link does not lock yet
        assertNull(AgeProfilePolicy.onLinkAccepted(declared, pending, child, 12).lockedByGuardianId)
        val verified = FamilyLinkPolicy.respond(pending, parent, accept = true, nowMs = 11)
        val locked = AgeProfilePolicy.onLinkAccepted(declared, verified, child, 12)
        assertEquals(AgeState.MINOR, locked.state)
        assertEquals(parent, locked.lockedByGuardianId)
    }
}
