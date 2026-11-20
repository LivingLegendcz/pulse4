package app.pulse4.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CircleConsentTest {
    private val link = Fx.link()
    private val mreq = Fx.membership()
    private val now = Fx.NOW

    private fun allowed(
        circle: String = "circle-1",
        age: AgeState = AgeState.MINOR,
        links: List<FamilyLink> = listOf(link),
        approvals: List<ConsentRecord> = emptyList(),
    ) = GuardianConsentPolicy.isMembershipAllowed(Fx.CHILD, circle, age, links, approvals, now)

    private fun text(b: ByteArray) = String(b, Charsets.UTF_8)

    @Test
    fun `membership allowed for adult always and for minor or unknown only with approval`() {
        assertTrue(allowed(age = AgeState.ADULT, links = emptyList()))
        for (age in listOf(AgeState.MINOR, AgeState.UNKNOWN)) {
            assertFalse(allowed(age = age), "no approval $age")
            assertTrue(allowed(age = age, approvals = listOf(Fx.approved(mreq))), "approved $age")
        }
    }

    @Test
    fun `approval is bound to its circle and to a verified link`() {
        val approvals = listOf(Fx.approved(mreq))
        assertFalse(allowed(circle = "circle-2", approvals = approvals))
        assertFalse(allowed(approvals = approvals, links = emptyList()))
        assertFalse(allowed(approvals = approvals, links = listOf(Fx.link(status = FamilyLinkStatus.REVOKED))))
        assertFalse(allowed(approvals = approvals, links = listOf(Fx.link(profile = AgeProfile.ADULT))))
    }

    @Test
    fun `revoked rejected pending and tampered membership approvals do not allow`() {
        for (st in listOf(ConsentStatus.REVOKED, ConsentStatus.PENDING, ConsentStatus.REJECTED, ConsentStatus.EXPIRED, ConsentStatus.SUPERSEDED)) {
            assertFalse(allowed(approvals = listOf(Fx.approved(mreq).copy(status = st))), "status $st")
        }
        assertFalse(allowed(approvals = listOf(ConsentRecord(mreq, ConsentStatus.APPROVED, null))))
        assertFalse(allowed(approvals = listOf(Fx.approved(mreq, Fx.decide(mreq, digest = "00")))))
        // an approval whose request was changed after the decision (e.g. other admin / action) is not intact
        assertFalse(allowed(approvals = listOf(Fx.approved(mreq.copy(circleAdminId = "evil"), Fx.decide(mreq)))))
        assertFalse(allowed(approvals = listOf(Fx.approved(mreq.copy(action = MembershipAction.CREATE), Fx.decide(mreq)))))
    }

    @Test
    fun `newer revoked version beats older approval`() {
        val v1 = Fx.approved(mreq)
        val v2 = Fx.approved(Fx.membership(requestId = "m2", nonce = "aaaabbbbccccddddeeeeffff00001111", version = 2))
        assertTrue(allowed(approvals = listOf(v1, v2)))
        assertFalse(allowed(approvals = listOf(v1, v2.copy(status = ConsentStatus.REVOKED))))
    }

    @Test
    fun `subjects never cross over between grants and circles`() {
        // circle id equal to a grant id
        val shareReq = Fx.request(Fx.grant(id = "same-id"))
        val circleReq = Fx.membership(circleId = "same-id")
        assertFalse(allowed(circle = "same-id", approvals = listOf(Fx.approved(shareReq))))
        assertFalse(
            GuardianConsentPolicy.isEffective(
                Fx.grant(id = "same-id"), AgeState.MINOR, listOf(link), listOf(Fx.approved(circleReq)), emptySet(), now,
            ),
        )
        assertTrue(allowed(circle = "same-id", approvals = listOf(Fx.approved(circleReq))))
        assertTrue(
            GuardianConsentPolicy.isEffective(
                Fx.grant(id = "same-id"), AgeState.MINOR, listOf(link), listOf(Fx.approved(shareReq)), emptySet(), now,
            ),
        )
    }

    @Test
    fun `removing the link invalidates share and circle approvals together`() {
        val share = Fx.approved(Fx.request(Fx.grant()))
        val circle = Fx.approved(mreq)
        val removed = FamilyLinkPolicy.removeLink(link, Fx.GUARDIAN)
        for (links in listOf(listOf(link), listOf(removed))) {
            val expected = links.single().status == FamilyLinkStatus.VERIFIED
            assertEquals(expected, allowed(approvals = listOf(circle), links = links))
            assertEquals(
                expected,
                GuardianConsentPolicy.isEffective(Fx.grant(), AgeState.MINOR, links, listOf(share), emptySet(), now),
            )
        }
        val affected = FamilyLinkPolicy.consentsAffectedByLinkRemoval(
            removed, listOf(removed),
            listOf(share, circle, Fx.pending(Fx.membership(requestId = "m3", nonce = "99998888777766665555444433332222"))),
        )
        assertEquals(3, affected.size)
    }

    @Test
    fun `canonical bytes carry the subject and are domain separated per type`() {
        val circleLines = text(ConsentCanonical.request(mreq)).trimEnd('\n').split('\n')
        assertEquals("P4-GC-v1", circleLines[0])
        assertEquals("subject=CIRCLE_MEMBERSHIP", circleLines[1])
        assertEquals(
            listOf(
                "subject", "schema", "requestId", "nonce", "guardianIds", "childId", "circleId",
                "permissionVersion", "circleName", "circleAdminId", "action", "issuedAtMs", "expiresAtMs",
            ),
            circleLines.drop(1).map { it.substringBefore('=') },
        )
        val shareLines = text(ConsentCanonical.request(Fx.request())).lines()
        assertEquals("subject=SHARE_GRANT", shareLines[1])
        // same id used for grant and circle -> different bytes and different digests
        val s = Fx.request(Fx.grant(id = "x"))
        val c = Fx.membership(circleId = "x")
        assertNotEquals(ConsentCanonical.requestDigestHex(s), ConsentCanonical.requestDigestHex(c))
    }

    @Test
    fun `every membership field is bound`() {
        val ref = text(ConsentCanonical.request(mreq))
        val variants = mapOf(
            "subjectId" to mreq.copy(subjectId = "c2"),
            "name" to mreq.copy(subjectName = "Other"),
            "admin" to mreq.copy(circleAdminId = "someone"),
            "action" to mreq.copy(action = MembershipAction.CREATE),
            "version" to mreq.copy(permissionVersion = 2),
            "requestId" to mreq.copy(requestId = "m9"),
            "nonce" to mreq.copy(nonce = "ff".repeat(16)),
            "guardians" to mreq.copy(guardianIds = setOf("guardian", "guardian2")),
            "child" to mreq.copy(childId = "x"),
            "issued" to mreq.copy(issuedAtMs = 1),
            "expires" to mreq.copy(expiresAtMs = 1),
        )
        for ((n, v) in variants) assertNotEquals(ref, text(ConsentCanonical.request(v)), n)
        assertEquals(variants.size, variants.values.map { text(ConsentCanonical.request(it)) }.toSet().size)
        // escaping of the free text circle name cannot inject lines
        val inj = text(ConsentCanonical.request(mreq.copy(subjectName = "a\ncircleAdminId=evil")))
        assertEquals(1, inj.lines().count { it.startsWith("circleAdminId=") })
    }

    @Test
    fun `malformed requests are rejected`() {
        assertTrue(mreq.wellFormed)
        assertTrue(Fx.request().wellFormed)
        val shareWithCircle = Fx.request().copy(subjectName = "x", circleAdminId = "a", action = MembershipAction.JOIN)
        val membershipWithTerms = mreq.copy(terms = Fx.grant().terms())
        val membershipNoName = mreq.copy(subjectName = null)
        val shareNoTerms = Fx.request().copy(terms = null)
        for (bad in listOf(shareWithCircle, membershipWithTerms, membershipNoName, shareNoTerms)) {
            assertFalse(bad.wellFormed)
            assertEquals(
                ConsentCheck.MALFORMED,
                GuardianConsentPolicy.verifyIncomingRequest(Fx.GUARDIAN, Fx.CHILD, bad, listOf(link), emptyList(), now),
            )
        }
    }

    @Test
    fun `guardian validates incoming membership request and child verifies its decision`() {
        assertEquals(
            ConsentCheck.OK,
            GuardianConsentPolicy.verifyIncomingRequest(Fx.GUARDIAN, Fx.CHILD, mreq, listOf(link), emptyList(), now),
        )
        // an older permission version of the same circle is refused, a different circle is independent
        val newer = Fx.pending(Fx.membership(requestId = "m2", nonce = "aaaabbbbccccddddeeeeffff00001111", version = 3))
        assertEquals(
            ConsentCheck.OLDER_PERMISSION_VERSION,
            GuardianConsentPolicy.verifyIncomingRequest(Fx.GUARDIAN, Fx.CHILD, mreq, listOf(link), listOf(newer), now),
        )
        val other = Fx.membership(circleId = "circle-2", requestId = "m5", nonce = "12121212343434345656565678787878")
        assertEquals(
            ConsentCheck.OK,
            GuardianConsentPolicy.verifyIncomingRequest(Fx.GUARDIAN, Fx.CHILD, other, listOf(link), listOf(newer), now),
        )

        fun v(d: GuardianDecision, records: List<ConsentRecord> = listOf(Fx.pending(mreq))) =
            GuardianConsentPolicy.verifyDecision(Fx.CHILD, d.guardianId, d, null, records, listOf(link), now, Fx.keys.resolver)
        assertEquals(ConsentCheck.OK, v(Fx.decide(mreq)))
        assertEquals(ConsentCheck.OK, v(Fx.decide(mreq, ConsentVerdict.REJECTED)))
        assertEquals(ConsentCheck.BAD_SIGNATURE, v(Fx.decide(mreq, signer = Fx.CHILD, guardianId = Fx.GUARDIAN)))
        assertEquals(ConsentCheck.NOT_PENDING, v(Fx.decide(mreq), listOf(Fx.approved(mreq))))
        assertEquals(ConsentCheck.DIGEST_MISMATCH, v(Fx.decide(mreq, digest = ConsentCanonical.requestDigestHex(Fx.membership(circleId = "other")))))
        // a share request still needs the proposed grant
        val shareReq = Fx.request()
        assertEquals(
            ConsentCheck.PROPOSAL_MISMATCH,
            GuardianConsentPolicy.verifyDecision(
                Fx.CHILD, Fx.GUARDIAN, Fx.decide(shareReq), null, listOf(Fx.pending(shareReq)), listOf(link), now, Fx.keys.resolver,
            ),
        )
        // a membership request is never a valid proposal for a grant
        assertFalse(GuardianConsentPolicy.matches(Fx.grant(id = mreq.subjectId), mreq))
    }

    @Test
    fun `membership revocation verifies like a share revocation`() {
        val good = Fx.revocation(mreq)
        fun v(r: GuardianRevocation) =
            GuardianConsentPolicy.verifyRevocation(Fx.CHILD, r.guardianId, r, listOf(Fx.approved(mreq)), listOf(link), Fx.keys.resolver)
        assertEquals(ConsentCheck.OK, v(good))
        assertEquals(ConsentCheck.ID_MISMATCH, v(good.copy(permissionId = "circle-1#9")))
        assertEquals(ConsentCheck.BAD_SIGNATURE, v(good.copy(signatureHex = "00")))
    }
}
