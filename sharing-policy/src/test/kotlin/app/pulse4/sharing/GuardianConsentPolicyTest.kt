package app.pulse4.sharing

import app.pulse4.domain.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuardianConsentPolicyTest {
    private val link = Fx.link()
    private val grant = Fx.grant()
    private val req = Fx.request(grant)
    private val now = Fx.NOW

    private fun effective(
        g: ShareGrant = grant,
        age: AgeState = AgeState.MINOR,
        links: List<FamilyLink> = listOf(link),
        approvals: List<ConsentRecord> = emptyList(),
        grandfathered: Set<String> = emptySet(),
    ) = GuardianConsentPolicy.isEffective(g, age, links, approvals, grandfathered, now)

    private fun verify(
        decision: GuardianDecision = Fx.decide(req),
        author: String = decision.guardianId,
        proposed: ShareGrant = grant,
        records: List<ConsentRecord> = listOf(Fx.pending(req)),
        links: List<FamilyLink> = listOf(link),
        at: Long = now,
        resolver: SignatureVerifierResolver = Fx.keys.resolver,
    ) = GuardianConsentPolicy.verifyDecision(Fx.CHILD, author, decision, proposed, records, links, at, resolver)

    // ---- effectiveness ----

    @Test
    fun `minor without link or approval cannot activate`() {
        assertFalse(effective(links = emptyList()))
        assertFalse(effective())
        assertFalse(FamilyLinkPolicy.canActivateGrant(Fx.CHILD, AgeState.MINOR, grant, emptyList(), emptyList(), emptySet(), now))
        // an approval alone is useless without any guardian link
        assertFalse(effective(links = emptyList(), approvals = listOf(Fx.approved(req))))
    }

    @Test
    fun `adult is unaffected and revoked grant is never effective`() {
        assertTrue(effective(age = AgeState.ADULT, links = emptyList()))
        assertFalse(effective(g = Fx.grant(revokedAtMs = 5), age = AgeState.ADULT))
        assertFalse(effective(g = Fx.grant(revokedAtMs = 5), approvals = listOf(Fx.approved(req))))
    }

    @Test
    fun `valid approval is effective only for covered grant`() {
        val approvals = listOf(Fx.approved(req))
        assertTrue(effective(approvals = approvals))
        assertTrue(FamilyLinkPolicy.canActivateGrant(Fx.CHILD, AgeState.MINOR, grant, listOf(link), approvals, emptySet(), now))
        assertFalse(effective(g = Fx.grant(recipients = setOf("coach", "other")), approvals = approvals))
        assertFalse(effective(g = Fx.grant(fields = setOf(Field.SLEEP_DURATION, Field.HR_DAILY)), approvals = approvals))
        assertFalse(effective(g = Fx.grant(planRights = mapOf("p" to PlanRight.VIEW)), approvals = approvals))
        assertFalse(effective(g = Fx.grant(scope = TimeScope.from(Fx.day.minusDays(1))), approvals = approvals))
        assertFalse(effective(g = Fx.grant(sources = setOf(Provider.MANUAL)), approvals = listOf(approvedWithSources(setOf(Provider.GARMIN_PERSONAL)))))
        // other owner / wrong ownerId never matches
        assertFalse(FamilyLinkPolicy.canActivateGrant("someone", AgeState.MINOR, grant, listOf(link), approvals, emptySet(), now))
    }

    private fun approvedWithSources(s: Set<Provider>?): ConsentRecord = Fx.approved(Fx.request(Fx.grant(sources = s)))

    @Test
    fun `sources coverage null approved covers everything but not vice versa`() {
        val any = approvedWithSources(null)
        assertTrue(effective(g = Fx.grant(sources = setOf(Provider.MANUAL)), approvals = listOf(any)))
        val limited = approvedWithSources(setOf(Provider.MANUAL))
        assertFalse(effective(g = Fx.grant(sources = null), approvals = listOf(limited)))
        assertTrue(effective(g = Fx.grant(sources = setOf(Provider.MANUAL)), approvals = listOf(limited)))
    }

    @Test
    fun `narrowing is covered offline by the previous approval`() {
        val approvals = listOf(Fx.approved(req))
        val narrower = Fx.grant(fields = setOf(Field.SLEEP_DURATION), scope = TimeScope.from(Fx.day.plusDays(3)))
        assertTrue(effective(g = narrower, approvals = approvals))
        assertTrue(GuardianConsentPolicy.isGrantNarrowing(listOf(grant), narrower))
        assertFalse(GuardianConsentPolicy.isGrantNarrowing(listOf(narrower), grant))
    }

    @Test
    fun `plan right levels and once scopes are compared by subset`() {
        val g = Fx.grant(planRights = mapOf("p" to PlanRight.COPY), scope = TimeScope.once(Fx.day, Fx.day.plusDays(10)))
        val approvals = listOf(Fx.approved(Fx.request(g)))
        assertTrue(effective(g = Fx.grant(planRights = mapOf("p" to PlanRight.VIEW), scope = TimeScope.once(Fx.day.plusDays(1), Fx.day.plusDays(5))), approvals = approvals))
        assertFalse(effective(g = Fx.grant(planRights = mapOf("p" to PlanRight.EDIT), scope = g.scope), approvals = approvals))
        assertFalse(effective(g = Fx.grant(planRights = g.planRights, scope = TimeScope.once(Fx.day, Fx.day.plusDays(11))), approvals = approvals))
        assertFalse(effective(g = Fx.grant(planRights = g.planRights, scope = TimeScope.from(Fx.day)), approvals = approvals))
    }

    @Test
    fun `highest permission version wins and older approval cannot resurrect`() {
        val v1 = Fx.approved(Fx.request(grant, requestId = "r1", version = 1))
        val widerGrant = Fx.grant(fields = setOf(Field.SLEEP_DURATION, Field.SLEEP_SCORE, Field.HR_DAILY))
        val v2 = Fx.approved(Fx.request(widerGrant, requestId = "r2", nonce = "ff".repeat(16), version = 2))
        assertTrue(effective(g = widerGrant, approvals = listOf(v1, v2)))
        assertFalse(effective(g = widerGrant, approvals = listOf(v1)))
        // guardian revoked v2: the older v1 must not take over
        val v2Revoked = v2.copy(status = ConsentStatus.REVOKED)
        assertFalse(effective(approvals = listOf(v1, v2Revoked)))
        assertFalse(effective(g = widerGrant, approvals = listOf(v1, v2Revoked)))
    }

    @Test
    fun `revoked pending rejected and superseded records do not grant`() {
        for (st in listOf(ConsentStatus.REVOKED, ConsentStatus.PENDING, ConsentStatus.REJECTED, ConsentStatus.EXPIRED, ConsentStatus.SUPERSEDED)) {
            assertFalse(effective(approvals = listOf(Fx.approved(req).copy(status = st))), "status $st")
        }
    }

    @Test
    fun `approval without or with tampered decision is not effective`() {
        assertFalse(effective(approvals = listOf(ConsentRecord(req, ConsentStatus.APPROVED, null))))
        assertFalse(effective(approvals = listOf(Fx.approved(req, Fx.decide(req, digest = "00")))))
        assertFalse(effective(approvals = listOf(Fx.approved(req, Fx.decide(req, verdict = ConsentVerdict.REJECTED)))))
        // decision signed "by" somebody the request was not addressed to
        assertFalse(effective(approvals = listOf(Fx.approved(req, Fx.decide(req, signer = Fx.STRANGER)))))
    }

    @Test
    fun `link removed or downgraded makes approval not effective`() {
        val approvals = listOf(Fx.approved(req))
        assertFalse(effective(approvals = approvals, links = listOf(Fx.link(status = FamilyLinkStatus.REVOKED))))
        assertFalse(effective(approvals = approvals, links = emptyList()))
        assertFalse(effective(approvals = approvals, links = listOf(Fx.link(profile = AgeProfile.ADULT))))
        val removed = FamilyLinkPolicy.removeLink(link, Fx.GUARDIAN)
        assertEquals(FamilyLinkStatus.REVOKED, removed.status)
        assertEquals(link.version + 1, removed.version)
        assertFalse(effective(approvals = approvals, links = listOf(removed)))
    }

    @Test
    fun `unknown age uses grandfathered ids and otherwise behaves like minor`() {
        assertTrue(effective(age = AgeState.UNKNOWN, links = emptyList(), grandfathered = setOf("g1")))
        assertFalse(effective(age = AgeState.UNKNOWN, links = emptyList(), grandfathered = setOf("other")))
        assertFalse(effective(g = Fx.grant(revokedAtMs = 1), age = AgeState.UNKNOWN, grandfathered = setOf("g1")))
        assertTrue(effective(age = AgeState.UNKNOWN, approvals = listOf(Fx.approved(req))))
        // MINOR suspends grandfathered grants until approved
        assertFalse(effective(age = AgeState.MINOR, grandfathered = setOf("g1")))
    }

    // ---- decision verification ----

    @Test
    fun `valid approved decision verifies`() {
        assertEquals(ConsentCheck.OK, verify())
        assertEquals(ConsentCheck.OK, verify(Fx.decide(req, ConsentVerdict.REJECTED)))
    }

    @Test
    fun `decision signed by child key or unsigned is rejected`() {
        assertEquals(ConsentCheck.BAD_SIGNATURE, verify(Fx.decide(req, signer = Fx.CHILD, guardianId = Fx.GUARDIAN)))
        assertEquals(ConsentCheck.BAD_SIGNATURE, verify(Fx.decide(req).copy(signatureHex = "")))
        assertEquals(ConsentCheck.BAD_SIGNATURE, verify(Fx.decide(req, signer = Fx.STRANGER, guardianId = Fx.GUARDIAN)))
        // the child itself is never a guardian
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, verify(Fx.decide(req, signer = Fx.CHILD)))
    }

    @Test
    fun `decision from another identity is rejected`() {
        // stranger signs as itself, author matches stranger -> not an addressed guardian of this request
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, verify(Fx.decide(req, signer = Fx.STRANGER)))
        // envelope author differs from the signer id in the payload
        assertEquals(ConsentCheck.AUTHOR_MISMATCH, verify(Fx.decide(req), author = Fx.STRANGER))
        // stranger has its own verified guardian link elsewhere but was not addressed
        val strangerLink = Fx.link(id = "fl-2", parent = Fx.STRANGER)
        assertEquals(
            ConsentCheck.NOT_A_GUARDIAN,
            verify(Fx.decide(req, signer = Fx.STRANGER), links = listOf(link, strangerLink)),
        )
    }

    @Test
    fun `contact whose key does not match the id is rejected`() {
        val noKey: SignatureVerifierResolver = { null }
        assertEquals(ConsentCheck.NO_GUARDIAN_KEY, verify(resolver = noKey))
    }

    @Test
    fun `modified request wrong recipient or wider scope are rejected`() {
        // digest of a different (modified) request
        val modified = req.copy(terms = req.t.copy(fields = req.t.fields + Field.HR_DAILY))
        assertEquals(ConsentCheck.DIGEST_MISMATCH, verify(Fx.decide(req, digest = ConsentCanonical.requestDigestHex(modified))))
        // child's stored request was changed after signing
        val storedModified = Fx.pending(modified)
        assertEquals(ConsentCheck.DIGEST_MISMATCH, verify(Fx.decide(req), records = listOf(storedModified)))
        // approval is right, but the grant being activated differs from the request
        assertEquals(ConsentCheck.PROPOSAL_MISMATCH, verify(proposed = Fx.grant(recipients = setOf("stranger-coach"))))
        assertEquals(ConsentCheck.PROPOSAL_MISMATCH, verify(proposed = Fx.grant(scope = TimeScope.from(Fx.day.minusDays(30)))))
        assertEquals(ConsentCheck.PROPOSAL_MISMATCH, verify(proposed = Fx.grant(fields = grant.fields + Field.WEIGHT)))
        assertEquals(ConsentCheck.PROPOSAL_MISMATCH, verify(proposed = Fx.grant(revokedAtMs = 1)))
    }

    @Test
    fun `replay expired and non pending are rejected`() {
        for (st in listOf(ConsentStatus.APPROVED, ConsentStatus.REJECTED, ConsentStatus.REVOKED, ConsentStatus.EXPIRED, ConsentStatus.SUPERSEDED)) {
            assertEquals(ConsentCheck.NOT_PENDING, verify(records = listOf(ConsentRecord(req, st))), "status $st")
        }
        assertEquals(ConsentCheck.EXPIRED, verify(at = req.expiresAtMs + 1))
        assertEquals(ConsentCheck.EXPIRED, verify(Fx.decide(req, decidedAtMs = req.expiresAtMs + 1)))
        assertEquals(ConsentCheck.UNKNOWN_REQUEST, verify(records = emptyList()))
        assertEquals(ConsentCheck.UNKNOWN_REQUEST, verify(Fx.decide(req.copy(requestId = "nope")), records = listOf(Fx.pending(req))))
    }

    @Test
    fun `revoked or downgraded link rejects decision`() {
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, verify(links = listOf(Fx.link(status = FamilyLinkStatus.REVOKED))))
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, verify(links = emptyList()))
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, verify(links = listOf(Fx.link(profile = AgeProfile.ADULT))))
        // link version changes no longer matter: the guardian only has to be verified
        assertEquals(ConsentCheck.OK, verify(links = listOf(Fx.link(version = 7))))
    }

    @Test
    fun `older permission version is rejected once a newer one is decided`() {
        val newerReq = Fx.request(grant, requestId = "r2", nonce = "ff".repeat(16), version = 2)
        for (st in listOf(ConsentStatus.APPROVED, ConsentStatus.REVOKED)) {
            val records = listOf(Fx.pending(req), ConsentRecord(newerReq, st, Fx.decide(newerReq)))
            assertEquals(ConsentCheck.OLDER_PERMISSION_VERSION, verify(records = records), "newer $st")
        }
        // a still pending newer request does not block
        assertEquals(ConsentCheck.OK, verify(records = listOf(Fx.pending(req), Fx.pending(newerReq))))
    }

    @Test
    fun `ids of decision must match child and request`() {
        assertEquals(ConsentCheck.ID_MISMATCH, verify(Fx.decide(req).copy(childId = "other")))
        assertEquals(ConsentCheck.UNSUPPORTED_SCHEMA, verify(Fx.decide(req).copy(schema = 9)))
        // a request of somebody else's child is not mine
        val foreign = req.copy(childId = "other")
        assertEquals(ConsentCheck.ID_MISMATCH, verify(records = listOf(Fx.pending(foreign))))
    }

    // ---- revocation, stored approval, incoming request ----

    @Test
    fun `revocation verifies only for approved permission from a verified guardian`() {
        val good = Fx.revocation(req)
        val records = listOf(Fx.approved(req))
        fun v(r: GuardianRevocation = good, author: String = r.guardianId, recs: List<ConsentRecord> = records, links: List<FamilyLink> = listOf(link)) =
            GuardianConsentPolicy.verifyRevocation(Fx.CHILD, author, r, recs, links, Fx.keys.resolver)
        assertEquals(ConsentCheck.OK, v())
        assertEquals(ConsentCheck.NOT_APPROVED, v(recs = listOf(Fx.pending(req))))
        assertEquals(ConsentCheck.NOT_APPROVED, v(recs = listOf(Fx.approved(req).copy(status = ConsentStatus.REVOKED))))
        assertEquals(ConsentCheck.ID_MISMATCH, v(good.copy(permissionId = "g1#9")))
        assertEquals(ConsentCheck.AUTHOR_MISMATCH, v(author = Fx.STRANGER))
        assertEquals(ConsentCheck.BAD_SIGNATURE, v(Fx.revocation(req, signer = Fx.CHILD, guardianId = Fx.GUARDIAN)))
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, v(Fx.revocation(req, signer = Fx.STRANGER)))
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, v(links = emptyList()))
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, v(links = listOf(Fx.link(status = FamilyLinkStatus.REVOKED))))
    }

    @Test
    fun `stored approval re-verification catches tampering`() {
        fun v(rec: ConsentRecord, r: SignatureVerifierResolver = Fx.keys.resolver) =
            GuardianConsentPolicy.verifyStoredApproval(Fx.CHILD, rec, r)
        assertEquals(ConsentCheck.OK, v(Fx.approved(req)))
        assertEquals(ConsentCheck.BAD_SIGNATURE, v(Fx.approved(req, Fx.decide(req, signer = Fx.CHILD, guardianId = Fx.GUARDIAN))))
        assertEquals(ConsentCheck.DIGEST_MISMATCH, v(Fx.approved(req.copy(terms = req.t.copy(fields = Field.entries.toSet())), Fx.decide(req))))
        assertEquals(ConsentCheck.NO_GUARDIAN_KEY, v(Fx.approved(req), r = { null }))
        assertEquals(ConsentCheck.NOT_APPROVED, v(ConsentRecord(req, ConsentStatus.APPROVED, null)))
        assertEquals(ConsentCheck.NOT_A_GUARDIAN, v(Fx.approved(req, Fx.decide(req, signer = Fx.STRANGER))))
    }

    @Test
    fun `guardian side validates incoming request`() {
        fun v(r: GuardianConsentRequest = req, author: String = Fx.CHILD, links: List<FamilyLink> = listOf(link), existing: List<ConsentRecord> = emptyList(), at: Long = now) =
            GuardianConsentPolicy.verifyIncomingRequest(Fx.GUARDIAN, author, r, links, existing, at)
        assertEquals(ConsentCheck.OK, v())
        assertEquals(ConsentCheck.ID_MISMATCH, v(author = Fx.STRANGER))
        assertEquals(ConsentCheck.ID_MISMATCH, v(req.copy(guardianIds = setOf(Fx.STRANGER))))
        assertEquals(ConsentCheck.MALFORMED, v(req.copy(guardianIds = emptySet())))
        assertEquals(ConsentCheck.MALFORMED, v(req.copy(guardianIds = setOf(Fx.GUARDIAN, Fx.CHILD))))
        assertEquals(ConsentCheck.LINK_INVALID, v(links = emptyList()))
        assertEquals(ConsentCheck.LINK_INVALID, v(links = listOf(Fx.link(profile = AgeProfile.ADULT))))
        assertEquals(ConsentCheck.LINK_INVALID, v(links = listOf(Fx.link(status = FamilyLinkStatus.PENDING))))
        assertEquals(ConsentCheck.EXPIRED, v(at = req.expiresAtMs + 1))
        assertEquals(ConsentCheck.BAD_NONCE, v(req.copy(nonce = "zz")))
        assertEquals(ConsentCheck.INVALID_VALIDITY, v(req.copy(expiresAtMs = req.issuedAtMs + GuardianConsentPolicy.MAX_REQUEST_TTL_MS + 1)))
        assertEquals(ConsentCheck.DUPLICATE_REQUEST, v(existing = listOf(Fx.pending(req))))
        assertEquals(ConsentCheck.DUPLICATE_REQUEST, v(req.copy(requestId = "r2"), existing = listOf(Fx.pending(req))))
        val old = req.copy(requestId = "r0", nonce = "ee".repeat(16), permissionVersion = 5)
        assertEquals(ConsentCheck.OLDER_PERMISSION_VERSION, v(existing = listOf(Fx.pending(old))))
    }

    @Test
    fun `link helpers`() {
        assertEquals(link, GuardianConsentPolicy.guardianLink(listOf(link), emptyList(), Fx.CHILD, Fx.GUARDIAN))
        assertEquals(null, GuardianConsentPolicy.guardianLink(listOf(Fx.link(profile = AgeProfile.ADULT)), emptyList(), Fx.CHILD, Fx.GUARDIAN))
        assertEquals(null, GuardianConsentPolicy.guardianLink(listOf(link), emptyList(), Fx.CHILD, Fx.STRANGER))
        assertTrue(FamilyLinkPolicy.isValidRemoteRevoke(link, Fx.CHILD, Fx.GUARDIAN))
        assertTrue(FamilyLinkPolicy.isValidRemoteRevoke(link, Fx.GUARDIAN, Fx.CHILD))
        assertFalse(FamilyLinkPolicy.isValidRemoteRevoke(link, Fx.STRANGER, Fx.GUARDIAN))
        assertFalse(FamilyLinkPolicy.isValidRemoteRevoke(link, Fx.GUARDIAN, Fx.GUARDIAN))
        val already = Fx.link(status = FamilyLinkStatus.REVOKED)
        assertEquals(already, FamilyLinkPolicy.removeLink(already, Fx.CHILD))
        assertTrue(runCatching { FamilyLinkPolicy.removeLink(link, Fx.STRANGER) }.isFailure)
        assertTrue(runCatching { FamilyLinkPolicy.removeLink(Fx.link(status = FamilyLinkStatus.REJECTED), Fx.CHILD) }.isFailure)
    }

    @Test
    fun `revocation plan carries reason and recall limit on link removal`() {
        val plain = Revocation.plan(grant, 1, emptyMap(), false)
        assertEquals(null, plain.reason)
        assertFalse(RevocationLimit.ALREADY_DELIVERED_CANNOT_BE_RECALLED in plain.limits)
        val removed = Revocation.plan(grant, 1, emptyMap(), false, RevocationReason.GUARDIAN_LINK_REMOVED)
        assertEquals(RevocationReason.GUARDIAN_LINK_REMOVED, removed.reason)
        assertTrue(RevocationLimit.ALREADY_DELIVERED_CANNOT_BE_RECALLED in removed.limits)
    }
}
