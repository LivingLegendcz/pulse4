package app.pulse4.ui

import app.pulse4.sharing.FamilyLinkStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParentLinkStatusTest {
    private fun phase(
        status: FamilyLinkStatus,
        child: Boolean = true,
        guardians: Boolean = false,
        awaiting: Boolean = false,
        addPending: Boolean = false,
    ) = ParentLinkStatus.phase(status, child, guardians, awaiting, addPending)

    @Test
    fun `first parent request waits for the parent`() {
        assertEquals(ParentLinkPhase.WAITING_FOR_PARENT, phase(FamilyLinkStatus.PENDING))
        assertEquals("Odesláno – čeká na potvrzení rodiče", ParentLinkStatus.label(ParentLinkPhase.WAITING_FOR_PARENT))
    }

    @Test
    fun `further parent waits for parent and existing guardian, then for the existing guardian only`() {
        assertEquals(ParentLinkPhase.WAITING_FOR_PARENT_AND_GUARDIAN, phase(FamilyLinkStatus.PENDING, guardians = true, addPending = true))
        assertEquals(ParentLinkPhase.WAITING_FOR_EXISTING_GUARDIAN, phase(FamilyLinkStatus.PENDING, guardians = true, awaiting = true, addPending = true))
    }

    @Test
    fun `parent initiated pending link is an invitation and terminal states map directly`() {
        assertEquals(ParentLinkPhase.INVITED_BY_PARENT, phase(FamilyLinkStatus.PENDING, child = false))
        assertEquals(ParentLinkPhase.VERIFIED, phase(FamilyLinkStatus.VERIFIED))
        assertEquals(ParentLinkPhase.REJECTED, phase(FamilyLinkStatus.REJECTED))
        assertEquals(ParentLinkPhase.REMOVED, phase(FamilyLinkStatus.REVOKED))
    }

    @Test
    fun `removal warning mentions the lock only for the last parent and never promises recall`() {
        val last = ParentLinkStatus.removeWarning(true)
        assertTrue(last.contains("znovu zamkne"))
        assertTrue(last.contains("nemusí jít vzít zpět"))
        val other = ParentLinkStatus.removeWarning(false)
        assertTrue(other.contains("jiný rodič"))
    }
}
