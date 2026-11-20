package app.pulse4.withings

import app.pulse4.provider.HttpGateway
import app.pulse4.provider.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WithingsProfilesTest {
    private val nowSec = 1_800_000_000L
    private fun yearsAgo(y: Int) = nowSec - y * 365L * 86_400L

    // Josef: API-flagged primary. Klarka: second adult for which the API omits email AND birthdate.
    private val josef = WithingsUserProfile(id = 1, firstName = "Josef", lastName = "A", isPrimary = true, email = "j@x.cz", birthdate = yearsAgo(40))
    private val klarkaBare = WithingsUserProfile(id = 2, firstName = "Klarka", lastName = "A")
    private val kid = WithingsUserProfile(id = 3, firstName = "Tomasek", lastName = "A", birthdate = yearsAgo(9), parentAccountId = 2)
    private val kidUnderPrimary = kid.copy(parentAccountId = 1)

    private fun session(primary: Long, child: Long?, all: List<WithingsUserProfile>) = WithingsWebSession(
        cookies = "c", primaryUserId = primary, childUserId = child, allProfiles = all,
    )

    @Test
    fun secondAdultWithoutEmailAndBirthdateIsNotAChild() {
        val all = listOf(josef, klarkaBare)
        assertFalse(WithingsProfiles.isChild(klarkaBare, all, nowSec))
        assertTrue(WithingsProfiles.children(all, josef.id, nowSec).isEmpty())
        assertNull(WithingsProfiles.autoChild(all, josef.id, nowSec))
        assertNull(WithingsProfiles.resolveChild(all, josef.id, nowSec = nowSec))
    }

    @Test
    fun sharedAccountLevelParentIdWithoutDirectLinkIsNotEnough() {
        // Both profiles carry the same account-level id (999) that is not a profile in the list.
        val j = josef.copy(isPrimary = false, parentAccountId = 999)
        val k = klarkaBare.copy(parentAccountId = 999)
        val all = listOf(j, k)
        assertTrue(WithingsProfiles.children(all, j.id, nowSec).isEmpty())
        assertTrue(WithingsProfiles.children(all, k.id, nowSec).isEmpty())
    }

    @Test
    fun adultWithBirthdateOrEmailIsNeverAChildEvenWithParentLink() {
        val adultWithParent = WithingsUserProfile(id = 5, firstName = "B", lastName = "", birthdate = yearsAgo(30), parentAccountId = 1)
        val emailWithParent = WithingsUserProfile(id = 6, firstName = "C", lastName = "", email = "c@x.cz", parentAccountId = 1)
        val all = listOf(josef, adultWithParent, emailWithParent)
        assertTrue(WithingsProfiles.children(all, josef.id, nowSec).isEmpty())
    }

    @Test
    fun genuineChildByAgeIsAutoBoundUnderPrimary() {
        val all = listOf(josef, klarkaBare, kidUnderPrimary)
        assertEquals(listOf(kidUnderPrimary), WithingsProfiles.children(all, josef.id, nowSec))
        assertEquals(kidUnderPrimary.id, WithingsProfiles.autoChild(all, josef.id, nowSec)?.id)
        assertEquals(kidUnderPrimary.id, WithingsProfiles.resolveChild(all, josef.id, nowSec = nowSec)?.id)
        assertEquals(josef.id, WithingsProfiles.findParent(kidUnderPrimary, all, nowSec)?.id)
    }

    @Test
    fun childWithoutBirthdateIsDetectedByDirectParentLink() {
        val k = WithingsUserProfile(id = 7, firstName = "Kid", lastName = "", parentAccountId = 1)
        assertEquals(listOf(7L), WithingsProfiles.children(listOf(josef, klarkaBare, k), josef.id, nowSec).map { it.id })
    }

    @Test
    fun childUnderTheOtherAdultIsStillBoundAndAdultsNeverAre() {
        val all = listOf(josef, klarkaBare, kid)
        val chosen = WithingsProfiles.autoChild(all, josef.id, nowSec)
        assertEquals(kid.id, chosen?.id)
        // switching the primary to the other adult: children list still excludes both adults
        assertEquals(listOf(kid.id), WithingsProfiles.children(all, klarkaBare.id, nowSec).map { it.id })
        assertEquals(listOf(kid.id), WithingsProfiles.childrenUnderThis(all, klarkaBare.id, nowSec).map { it.id })
    }

    @Test
    fun noPreferenceKeepsCurrentChildAndDoesNotWipeIt() {
        val kid2 = kidUnderPrimary.copy(id = 8, firstName = "Second")
        val all = listOf(josef, klarkaBare, kidUnderPrimary, kid2)
        // selectProfiles(primary, null) with a stored choice of the second child must keep it (not fall back to the first child).
        val kept = WithingsProfiles.resolveChild(all, josef.id, requestedChildId = null, currentChildId = kid2.id, nowSec = nowSec)
        assertEquals(kid2.id, kept?.id)
        // explicit request wins
        assertEquals(kidUnderPrimary.id, WithingsProfiles.resolveChild(all, josef.id, kidUnderPrimary.id, kid2.id, nowSec)?.id)
    }

    @Test
    fun adultIsRejectedAsRequestedOrStoredChild() {
        val all = listOf(josef, klarkaBare, kidUnderPrimary)
        // explicit request for the second adult is ignored
        assertEquals(kidUnderPrimary.id, WithingsProfiles.resolveChild(all, josef.id, requestedChildId = klarkaBare.id, nowSec = nowSec)?.id)
        // a stale persisted child (second adult saved by an older version) is dropped
        assertEquals(kidUnderPrimary.id, WithingsProfiles.resolveChild(all, josef.id, currentChildId = klarkaBare.id, nowSec = nowSec)?.id)
        // ... and without any genuine child nothing is bound
        assertNull(WithingsProfiles.resolveChild(listOf(josef, klarkaBare), josef.id, currentChildId = klarkaBare.id, nowSec = nowSec))
        // the primary can never be its own child
        assertNull(WithingsProfiles.resolveChild(listOf(josef, klarkaBare), josef.id, requestedChildId = josef.id, nowSec = nowSec))
    }

    @Test
    fun sessionChildIsTheOnlyProfileShownForTheCardAndNeverAnAdult() {
        val all = listOf(josef, klarkaBare, kidUnderPrimary)
        // bound child -> exactly the child, nothing else is derivable for display
        assertEquals(kidUnderPrimary.id, WithingsProfiles.sessionChild(session(josef.id, kidUnderPrimary.id, all), nowSec)?.id)
        // session stale-pointing at the second adult -> nothing is shown
        assertNull(WithingsProfiles.sessionChild(session(josef.id, klarkaBare.id, all), nowSec))
        assertNull(WithingsProfiles.sessionChild(session(josef.id, null, all), nowSec))
        assertNull(WithingsProfiles.sessionChild(null, nowSec))
        // primary switched to the other adult: Josef (adult) is not a child either
        assertTrue(WithingsProfiles.children(all, klarkaBare.id, nowSec).none { it.id == josef.id || it.id == klarkaBare.id })
    }

    @Test
    fun parsedUsersListKeepsSecondAdultOutOfChildren() {
        // Real-shape response where the second adult has neither email nor birthdate nor parent id.
        val body = """{"status":0,"body":{"users":[
            {"id":11,"firstname":"Josef","lastname":"K","is_primary":1,"email":"j@x.cz","birthdate":${yearsAgo(40)}},
            {"id":12,"firstname":"Klarka","lastname":"K","is_primary":0},
            {"id":13,"firstname":"Tom","lastname":"K","is_primary":0,"birthdate":${yearsAgo(8)},"idparentaccount":11}
        ]}}"""
        val http = object : HttpGateway {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("unused")
            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>) =
                HttpResponse(200, emptyMap(), body)
        }
        val all = WithingsWebClient(http).getUsersList("sessionid=abc", null)
        assertEquals(3, all.size)
        assertEquals(listOf(13L), WithingsProfiles.children(all, 11L).map { it.id })
        assertEquals(13L, WithingsProfiles.autoChild(all, 11L)?.id)
        assertEquals(listOf(13L), WithingsProfiles.children(all, 12L).map { it.id })
    }
}
