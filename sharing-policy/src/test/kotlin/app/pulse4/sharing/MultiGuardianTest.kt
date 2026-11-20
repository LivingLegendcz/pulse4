package app.pulse4.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiGuardianTest {
    private val g1 = Fx.GUARDIAN
    private val g2 = Fx.GUARDIAN2
    private val l1 = Fx.founderLink
    private val l2 = Fx.secondLink
    private val bothLinks = listOf(l1, l2)
    private val addApproved get() = Fx.addApproved
    private val grant = Fx.grant()
    private val both = setOf(g1, g2)
    private val now = Fx.NOW

    private fun effective(
        links: List<FamilyLink>,
        records: List<ConsentRecord>,
        g: ShareGrant = grant,
        age: AgeState = AgeState.MINOR,
    ) = GuardianConsentPolicy.isEffective(g, age, links, records, emptySet(), now)

    private fun guardians(links: List<FamilyLink>, records: List<ConsentRecord>) =
        GuardianConsentPolicy.verifiedGuardians(Fx.CHILD, links, records)

    private fun decide(
        @Suppress("UNUSED_PARAMETER") req: GuardianConsentRequest,
        d: GuardianDecision,
        records: List<ConsentRecord>,
        links: List<FamilyLink> = bothLinks,
        proposed: ShareGrant? = grant,
    ) = GuardianConsentPolicy.verifyDecision(Fx.CHILD, d.guardianId, d, proposed, records, links, now, Fx.keys.resolver)

    // ---- who counts as a verified guardian ----

    @Test
    fun `second guardian only counts with an approved guardian add`() {
        assertEquals(setOf(g1), guardians(listOf(l1), emptyList()))
        // fake second guardian: verified link but no GUARDIAN_ADD approval
        assertEquals(setOf(g1), guardians(bothLinks, emptyList()))
        assertEquals(both, guardians(bothLinks, listOf(addApproved)))
        // pending / rejected / revoked / wrong id / tampered add approvals do not qualify
        val add = Fx.guardianAdd()
        for (st in listOf(ConsentStatus.PENDING, ConsentStatus.REJECTED, ConsentStatus.REVOKED, ConsentStatus.EXPIRED)) {
            assertEquals(setOf(g1), guardians(bothLinks, listOf(Fx.approved(add).copy(status = st))), "status $st")
        }
        val otherAdd = Fx.approved(Fx.guardianAdd(newGuardian = "guardian3"))
        assertEquals(setOf(g1), guardians(bothLinks, listOf(otherAdd)))
        assertEquals(setOf(g1), guardians(bothLinks, listOf(Fx.approved(add, Fx.decide(add, digest = "00")))))
        assertEquals(setOf(g1), guardians(bothLinks, listOf(Fx.approved(add, Fx.decide(add, signer = Fx.STRANGER)))))
        // links that are not VERIFIED MINOR links of this child never count
        assertEquals(emptySet(), guardians(listOf(Fx.link(status = FamilyLinkStatus.PENDING)), emptyList()))
        assertEquals(emptySet(), guardians(listOf(Fx.link(profile = AgeProfile.ADULT)), emptyList()))
    }

    @Test
    fun `canVerifyGuardianLink requires guardian add for every further guardian`() {
        val check = { link: FamilyLink, links: List<FamilyLink>, records: List<ConsentRecord> ->
            GuardianConsentPolicy.canVerifyGuardianLink(link, Fx.CHILD, links, records)
        }
        val pending2 = l2.copy(status = FamilyLinkStatus.PENDING, verifiedAtMs = null)
        // first guardian needs nothing
        val pending1 = l1.copy(status = FamilyLinkStatus.PENDING, verifiedAtMs = null)
        assertEquals(ConsentCheck.OK, check(pending1, listOf(pending1), emptyList()))
        // second guardian without approval
        assertEquals(ConsentCheck.GUARDIAN_ADD_MISSING, check(pending2, listOf(l1, pending2), emptyList()))
        // with approval for exactly this parent id
        assertEquals(ConsentCheck.OK, check(pending2, listOf(l1, pending2), listOf(addApproved)))
        // approval for somebody else / revoked / pending approval
        assertEquals(ConsentCheck.GUARDIAN_ADD_MISSING, check(pending2, listOf(l1, pending2), listOf(Fx.approved(Fx.guardianAdd(newGuardian = "guardian3")))))
        assertEquals(ConsentCheck.GUARDIAN_ADD_MISSING, check(pending2, listOf(l1, pending2), listOf(addApproved.copy(status = ConsentStatus.REVOKED))))
        assertEquals(ConsentCheck.GUARDIAN_ADD_MISSING, check(pending2, listOf(l1, pending2), listOf(Fx.pending(Fx.guardianAdd()))))
        // a link of an ADULT can never become a guardian link
        assertEquals(ConsentCheck.LINK_INVALID, check(pending2.copy(childAgeProfile = AgeProfile.ADULT), listOf(l1), emptyList()))
        // cap on the number of guardians
        val many = (1..GuardianConsentPolicy.MAX_GUARDIANS).map { Fx.link(id = "x$it", parent = "p$it", verifiedAtMs = it.toLong()) }
        val addRecords = many.drop(1).map { Fx.approved(Fx.guardianAdd(newGuardian = it.parentId, requestId = "a-${it.id}", nonce = Fx.nonce(it.id.hashCode()))) }
        assertEquals(many.map { it.parentId }.toSet(), guardians(many, addRecords))
        val extra = Fx.link(id = "extra", parent = "p-extra", status = FamilyLinkStatus.PENDING)
        val extraAdd = Fx.approved(Fx.guardianAdd(newGuardian = "p-extra", requestId = "a-extra", nonce = Fx.nonce(777)))
        assertEquals(ConsentCheck.TOO_MANY_GUARDIANS, check(extra, many + extra, addRecords + extraAdd))
    }

    @Test
    fun `guardian add decisions are verified like any other and cannot be self approved`() {
        val add = Fx.guardianAdd()
        fun v(d: GuardianDecision, links: List<FamilyLink> = listOf(l1), records: List<ConsentRecord> = listOf(Fx.pending(add))) =
            GuardianConsentPolicy.verifyDecision(Fx.CHILD, d.guardianId, d, null, records, links, now, Fx.keys.resolver)
        assertEquals(ConsentCheck.OK, v(Fx.decide(add)))
        assertEquals(ConsentCheck.OK, v(Fx.decide(add, ConsentVerdict.REJECTED)))
        // the proposed guardian can not approve its own addition, nor can a fake one
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, v(Fx.decide(add, signer = g2), links = bothLinks))
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, v(Fx.decide(add, signer = Fx.CHILD)))
        // a request that lists the new guardian among the approvers is malformed
        val selfApproved = add.copy(guardianIds = setOf(g1, g2))
        assertFalse(selfApproved.wellFormed)
        assertEquals(ConsentCheck.MALFORMED, v(Fx.decide(selfApproved), records = listOf(Fx.pending(selfApproved)), links = bothLinks))
        // a request to add the child itself or an empty guardian set is malformed
        assertFalse(add.copy(subjectId = Fx.CHILD).wellFormed)
        assertFalse(add.copy(subjectKeyDigest = null).wellFormed)
        // guardian side accepts a GUARDIAN_ADD request addressed to it
        assertEquals(
            ConsentCheck.OK,
            GuardianConsentPolicy.verifyIncomingRequest(g1, Fx.CHILD, add, listOf(l1), emptyList(), now),
        )
        assertEquals(
            ConsentCheck.ID_MISMATCH,
            GuardianConsentPolicy.verifyIncomingRequest(g2, Fx.CHILD, add, listOf(l2), emptyList(), now),
        )
        assertFailsWith<IllegalArgumentException> {
            GuardianConsentPolicy.newGuardianAddRequest("a", Fx.NONCE, setOf(g1), Fx.CHILD, g1, "x", "k", 1, now)
        }
    }

    // ---- two guardians in action ----

    @Test
    fun `either guardian can approve and the first valid decision wins`() {
        val req = Fx.request(grant, guardians = both)
        val records = listOf(addApproved, Fx.pending(req))
        for (signer in listOf(g1, g2)) {
            assertEquals(ConsentCheck.OK, decide(req, Fx.decide(req, signer = signer), records), "signer $signer")
        }
        // g1 decided first -> g2's later decision hits a non-PENDING request
        val decided = listOf(addApproved, Fx.approved(req, Fx.decide(req, signer = g1)))
        assertEquals(ConsentCheck.NOT_PENDING, decide(req, Fx.decide(req, signer = g2), decided))
        // a request addressed only to g1 can not be decided by g2
        val onlyG1 = Fx.request(grant, guardians = setOf(g1), requestId = "r9", nonce = Fx.nonce(9))
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, decide(onlyG1, Fx.decide(onlyG1, signer = g2), listOf(addApproved, Fx.pending(onlyG1))))
        // both guardians receive and accept the very same request bytes
        for (self in listOf(g1, g2)) {
            val links = listOf(if (self == g1) l1 else l2)
            assertEquals(ConsentCheck.OK, GuardianConsentPolicy.verifyIncomingRequest(self, Fx.CHILD, req, links, emptyList(), now))
        }
    }

    @Test
    fun `approval by either guardian is effective`() {
        val req = Fx.request(grant, guardians = both)
        for (signer in listOf(g1, g2)) {
            val approved = Fx.approved(req, Fx.decide(req, signer = signer))
            assertTrue(effective(bothLinks, listOf(addApproved, approved)), "signer $signer")
            assertEquals(ConsentCheck.OK, GuardianConsentPolicy.verifyStoredApproval(Fx.CHILD, approved, Fx.keys.resolver))
        }
    }

    @Test
    fun `fake second guardian without guardian add is rejected everywhere`() {
        val req = Fx.request(grant, guardians = both)
        val records = listOf(Fx.pending(req))
        // decision
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, decide(req, Fx.decide(req, signer = g2), records))
        // revocation of someone else's approval
        val approvedByG1 = Fx.approved(req, Fx.decide(req, signer = g1))
        val rev = GuardianConsentPolicy.verifyRevocation(Fx.CHILD, g2, Fx.revocation(req, signer = g2), listOf(approvedByG1), bothLinks, Fx.keys.resolver)
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, rev)
        // link removal of the real guardian
        assertFalse(FamilyLinkPolicy.isValidLinkRemoval(l1, g2, bothLinks, emptyList()))
        assertTrue(runCatching { FamilyLinkPolicy.removeLink(l1, g2, bothLinks, emptyList()) }.isFailure)
        // age release
        val release = release(g2, l2.id)
        val outcome = AgeProfilePolicy.applyRelease(
            AgeProfileRecord(AgeState.MINOR, AgeSource.LINK_ACCEPTED, 1, g1), Fx.CHILD, g2, release, bothLinks, emptyList(),
            emptySet(), now, Fx.keys.resolver,
        )
        assertEquals(ReleaseOutcome.Rejected(ReleaseRejection.NOT_A_GUARDIAN), outcome)
    }

    @Test
    fun `any verified guardian can revoke an approval another guardian gave`() {
        val req = Fx.request(grant, guardians = both)
        val approvedByG1 = Fx.approved(req, Fx.decide(req, signer = g1))
        val records = listOf(addApproved, approvedByG1)
        val revByG2 = GuardianConsentPolicy.verifyRevocation(Fx.CHILD, g2, Fx.revocation(req, signer = g2), records, bothLinks, Fx.keys.resolver)
        assertEquals(ConsentCheck.OK, revByG2)
        val revByG1 = GuardianConsentPolicy.verifyRevocation(Fx.CHILD, g1, Fx.revocation(req, signer = g1), records, bothLinks, Fx.keys.resolver)
        assertEquals(ConsentCheck.OK, revByG1)
        // once applied the grant is no longer effective
        assertTrue(effective(bothLinks, records))
        assertFalse(effective(bothLinks, listOf(addApproved, approvedByG1.copy(status = ConsentStatus.REVOKED))))
        // a stranger cannot
        assertEquals(
            ConsentCheck.NOT_A_GUARDIAN,
            GuardianConsentPolicy.verifyRevocation(Fx.CHILD, Fx.STRANGER, Fx.revocation(req, signer = Fx.STRANGER), records, bothLinks, Fx.keys.resolver),
        )
    }

    @Test
    fun `revoking the guardian add approval drops that guardian`() {
        assertEquals(both, guardians(bothLinks, listOf(addApproved)))
        val revokedAdd = addApproved.copy(status = ConsentStatus.REVOKED)
        assertEquals(setOf(g1), guardians(bothLinks, listOf(revokedAdd)))
        // and its approvals are then no longer usable for decisions
        val req = Fx.request(grant, guardians = both)
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, decide(req, Fx.decide(req, signer = g2), listOf(revokedAdd, Fx.pending(req))))
    }

    @Test
    fun `a guardian cannot revoke the guardian add approval of another guardian`() {
        // guardians are equal: neither the founder nor the added guardian may remove the other through a revocation
        for (signer in listOf(g1, g2)) {
            val revoke = GuardianConsentPolicy.verifyRevocation(
                Fx.CHILD, signer, Fx.revocation(Fx.guardianAdd(), signer = signer), listOf(addApproved), bothLinks, Fx.keys.resolver,
            )
            assertEquals(ConsentCheck.REVOCATION_NOT_ALLOWED, revoke)
        }
    }

    // ---- removal of guardian links ----

    @Test
    fun `approvals survive the removal of one guardian while another remains`() {
        val req = Fx.request(grant, guardians = both)
        val byG1 = Fx.approved(req, Fx.decide(req, signer = g1))
        val byG2 = Fx.approved(req, Fx.decide(req, signer = g2))
        val records = listOf(addApproved, byG1)
        // g1 removes its own link: the approval it gave stays valid because g2 remains
        val l1Removed = FamilyLinkPolicy.removeLink(l1, g1)
        assertEquals(setOf(g2), guardians(listOf(l1Removed, l2), records))
        assertTrue(effective(listOf(l1Removed, l2), records))
        // the other way round: approval signed by g2, g2 removes its own link, g1 remains
        val l2Removed = FamilyLinkPolicy.removeLink(l2, g2)
        assertTrue(effective(listOf(l1, l2Removed), listOf(addApproved, byG2)))
        // the new sole guardian can still decide
        val req2 = Fx.request(grant, guardians = setOf(g2), requestId = "r2", nonce = Fx.nonce(2), version = 2)
        assertEquals(
            ConsentCheck.OK,
            GuardianConsentPolicy.verifyDecision(
                Fx.CHILD, g2, Fx.decide(req2, signer = g2), grant, records + Fx.pending(req2), listOf(l1Removed, l2), now, Fx.keys.resolver,
            ),
        )
    }

    @Test
    fun `last guardian removed means nothing is effective`() {
        val shareReq = Fx.request(grant, guardians = both)
        val circleReq = Fx.membership(guardians = both)
        val records = listOf(
            addApproved,
            Fx.approved(shareReq, Fx.decide(shareReq, signer = g1)),
            Fx.approved(circleReq, Fx.decide(circleReq, signer = g2)),
        )
        assertTrue(effective(bothLinks, records))
        assertTrue(GuardianConsentPolicy.isMembershipAllowed(Fx.CHILD, "circle-1", AgeState.MINOR, bothLinks, records, now))
        val l1Removed = FamilyLinkPolicy.removeLink(l1, g1)
        val l2Removed = FamilyLinkPolicy.removeLink(l2, g2)
        val none = listOf(l1Removed, l2Removed)
        assertEquals(emptySet(), guardians(none, records))
        assertFalse(effective(none, records))
        assertFalse(GuardianConsentPolicy.isMembershipAllowed(Fx.CHILD, "circle-1", AgeState.MINOR, none, records, now))
        assertFalse(FamilyLinkPolicy.isOnboardingComplete(AgeState.MINOR, Fx.CHILD, none, records))
        // an ADULT stays unaffected
        assertTrue(effective(none, records, age = AgeState.ADULT))
        // bookkeeping: everything pending/approved of the child is withdrawn
        val pendingOne = Fx.pending(Fx.request(grant, guardians = both, requestId = "p1", nonce = Fx.nonce(3)))
        val affected = FamilyLinkPolicy.consentsAffectedByLinkRemoval(l2Removed, none, records + pendingOne)
        assertEquals(records.size + 1, affected.size)
    }

    @Test
    fun `removing one guardian only withdraws that guardians add rows`() {
        val shareReq = Fx.request(grant, guardians = both)
        val share = Fx.approved(shareReq, Fx.decide(shareReq, signer = g1))
        val records = listOf(addApproved, share, Fx.pending(Fx.membership(guardians = both)))
        val l2Removed = FamilyLinkPolicy.removeLink(l2, g2)
        val affected = FamilyLinkPolicy.consentsAffectedByLinkRemoval(l2Removed, listOf(l1, l2Removed), records)
        assertEquals(listOf(addApproved), affected)
        // removing the founder instead: the add row of the founder does not exist, nothing is withdrawn
        val l1Removed = FamilyLinkPolicy.removeLink(l1, g1)
        assertEquals(emptyList(), FamilyLinkPolicy.consentsAffectedByLinkRemoval(l1Removed, listOf(l1Removed, l2), records))
    }

    @Test
    fun `a guardian can remove only its own link, never another guardians`() {
        val records = listOf(addApproved)
        // guardian B cannot remove guardian A's link (and vice versa), neither locally nor as an inbound revoke
        assertFalse(FamilyLinkPolicy.isValidLinkRemoval(l1, g2, bothLinks, records))
        assertFalse(FamilyLinkPolicy.isValidLinkRemoval(l2, g1, bothLinks, records))
        assertTrue(runCatching { FamilyLinkPolicy.removeLink(l1, g2, bothLinks, records) }.isFailure)
        assertTrue(runCatching { FamilyLinkPolicy.removeLink(l2, g1, bothLinks, records) }.isFailure)
        // the two parties of a link can
        assertTrue(FamilyLinkPolicy.isValidLinkRemoval(l1, Fx.CHILD, bothLinks, records))
        assertTrue(FamilyLinkPolicy.isValidLinkRemoval(l1, g1, bothLinks, records))
        assertEquals(FamilyLinkStatus.REVOKED, FamilyLinkPolicy.removeLink(l2, g2, bothLinks, records).status)
        assertEquals(FamilyLinkStatus.REVOKED, FamilyLinkPolicy.removeLink(l2, Fx.CHILD, bothLinks, records).status)
        // strangers cannot
        assertFalse(FamilyLinkPolicy.isValidLinkRemoval(l1, Fx.STRANGER, bothLinks, records))
        assertTrue(runCatching { FamilyLinkPolicy.removeLink(l2, Fx.STRANGER, bothLinks, records) }.isFailure)
    }

    @Test
    fun `age release can be signed by any verified guardian through its own link`() {
        val locked = AgeProfileRecord(AgeState.MINOR, AgeSource.LINK_ACCEPTED, 1, g1)
        fun apply(rel: GuardianAgeRelease, links: List<FamilyLink> = bothLinks, records: List<ConsentRecord> = listOf(addApproved)) =
            AgeProfilePolicy.applyRelease(locked, Fx.CHILD, rel.guardianId, rel, links, records, emptySet(), now, Fx.keys.resolver)
        assertTrue(apply(release(g1, l1.id)) is ReleaseOutcome.Released)
        assertTrue(apply(release(g2, l2.id)) is ReleaseOutcome.Released)
        // a guardian cannot borrow the other guardian's link
        assertEquals(ReleaseOutcome.Rejected(ReleaseRejection.LINK_INVALID), apply(release(g2, l1.id)))
        // forged signature
        assertEquals(ReleaseOutcome.Rejected(ReleaseRejection.BAD_SIGNATURE), apply(release(g2, l2.id, signer = g1)))
        // g2 removed or never approved
        assertEquals(ReleaseOutcome.Rejected(ReleaseRejection.NOT_A_GUARDIAN), apply(release(g2, l2.id), records = emptyList()))
        assertEquals(
            ReleaseOutcome.Rejected(ReleaseRejection.NOT_A_GUARDIAN),
            apply(release(g2, l2.id), links = listOf(l1, FamilyLinkPolicy.removeLink(l2, g2))),
        )
    }

    @Test
    fun `onboarding completes with one guardian and further guardians are optional`() {
        assertFalse(FamilyLinkPolicy.isOnboardingComplete(AgeState.MINOR, Fx.CHILD, emptyList()))
        assertTrue(FamilyLinkPolicy.isOnboardingComplete(AgeState.MINOR, Fx.CHILD, listOf(l1)))
        // a second verified link without approval does not matter, nor does it break anything
        assertTrue(FamilyLinkPolicy.isOnboardingComplete(AgeState.MINOR, Fx.CHILD, bothLinks))
        // only an unapproved fake link left after the founder is gone: it becomes the (sole) founder
        val l1Removed = FamilyLinkPolicy.removeLink(l1, g1)
        assertTrue(FamilyLinkPolicy.isOnboardingComplete(AgeState.MINOR, Fx.CHILD, listOf(l1Removed, l2), listOf(addApproved)))
    }

    @Test
    fun `approvals signed by a guardian can be listed for bulk revocation`() {
        val req = Fx.request(grant, guardians = both)
        val byG1 = Fx.approved(req, Fx.decide(req, signer = g1))
        val req2 = Fx.request(Fx.grant(id = "g2"), guardians = both, requestId = "r2", nonce = Fx.nonce(2))
        val byG2 = Fx.approved(req2, Fx.decide(req2, signer = g2))
        assertEquals(listOf(byG1), GuardianConsentPolicy.approvalsSignedBy(g1, listOf(byG1, byG2, Fx.pending(req2))))
        assertEquals(listOf(byG2), GuardianConsentPolicy.approvalsSignedBy(g2, listOf(byG1, byG2)))
    }

    @Test
    fun `request addressing is capped and excludes the child`() {
        assertFailsWith<IllegalArgumentException> { Fx.request(grant, guardians = emptySet()) }
        assertFailsWith<IllegalArgumentException> { Fx.request(grant, guardians = setOf(Fx.CHILD, g1)) }
        assertFailsWith<IllegalArgumentException> {
            Fx.request(grant, guardians = (1..GuardianConsentPolicy.MAX_GUARDIANS + 1).map { "p$it" }.toSet())
        }
    }

    private fun release(guardian: String, linkId: String, signer: String = guardian): GuardianAgeRelease {
        val u = GuardianAgeRelease(
            guardianId = guardian, childId = Fx.CHILD, linkId = linkId, nonce = Fx.NONCE,
            issuedAtMs = now - 1000, expiresAtMs = now + 60_000, signatureHex = "",
        )
        return u.copy(signatureHex = Fx.keys.sign(signer, ConsentCanonical.ageRelease(u)))
    }
}
