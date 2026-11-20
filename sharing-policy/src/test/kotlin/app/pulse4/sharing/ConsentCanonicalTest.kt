package app.pulse4.sharing

import app.pulse4.domain.Provider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConsentCanonicalTest {
    private fun text(b: ByteArray) = String(b, Charsets.UTF_8)
    private val base = Fx.request(Fx.grant(planRights = mapOf("p2" to PlanRight.VIEW, "p1" to PlanRight.EDIT)))

    @Test
    fun `format is header then subject then fixed-order key value lines`() {
        val lines = text(ConsentCanonical.request(Fx.request(Fx.grant(sources = null)))).trimEnd('\n').split('\n')
        assertEquals("P4-GC-v1", lines[0])
        assertEquals("subject=SHARE_GRANT", lines[1])
        assertEquals(
            listOf(
                "subject", "schema", "requestId", "nonce", "guardianIds", "childId", "grantId",
                "permissionVersion", "groupId", "recipients", "fields", "planRights", "scope", "sources", "baseline",
                "issuedAtMs", "expiresAtMs",
            ),
            lines.drop(1).map { it.substringBefore('=') },
        )
        assertTrue("sources=*" in lines)
        assertTrue("groupId=*" in lines)
        assertTrue("baseline=none" in lines)
        assertTrue("fields=SLEEP_DURATION,SLEEP_SCORE" in lines)
        assertTrue("guardianIds=guardian" in lines)
    }

    @Test
    fun `deterministic and independent of set and map order`() {
        val a = Fx.grant(
            recipients = linkedSetOf("b", "a", "c"),
            fields = linkedSetOf(Field.SLEEP_SCORE, Field.SLEEP_DURATION),
            planRights = linkedMapOf("p2" to PlanRight.VIEW, "p1" to PlanRight.EDIT),
            sources = linkedSetOf(Provider.MANUAL, Provider.GARMIN_PERSONAL),
        )
        val b = Fx.grant(
            recipients = linkedSetOf("c", "a", "b"),
            fields = linkedSetOf(Field.SLEEP_DURATION, Field.SLEEP_SCORE),
            planRights = linkedMapOf("p1" to PlanRight.EDIT, "p2" to PlanRight.VIEW),
            sources = linkedSetOf(Provider.GARMIN_PERSONAL, Provider.MANUAL),
        )
        val g1 = linkedSetOf("guardian", "guardian2")
        val g2 = linkedSetOf("guardian2", "guardian")
        assertContentEquals(
            ConsentCanonical.request(Fx.request(a, guardians = g1)),
            ConsentCanonical.request(Fx.request(b, guardians = g2)),
        )
        assertContentEquals(ConsentCanonical.request(base), ConsentCanonical.request(base))
        assertTrue("planRights=p1:EDIT,p2:VIEW" in text(ConsentCanonical.request(base)).lines())
    }

    @Test
    fun `every request field change changes the bytes`() {
        val r = Fx.request(Fx.grant())
        val variants = mapOf(
            "requestId" to r.copy(requestId = "r2"),
            "nonce" to r.copy(nonce = "ff".repeat(16)),
            "guardianIds" to r.copy(guardianIds = setOf("guardian", "guardian2")),
            "guardianIdsOther" to r.copy(guardianIds = setOf("guardian2")),
            "childId" to r.copy(childId = "other"),
            "grantId" to r.copy(subjectId = "g2"),
            "permissionVersion" to r.copy(permissionVersion = 2),
            "groupId" to r.copy(groupId = "grp"),
            "recipients" to r.copy(terms = r.t.copy(recipients = setOf("coach", "x"))),
            "fields" to r.copy(terms = r.t.copy(fields = setOf(Field.SLEEP_DURATION))),
            "planRights" to r.copy(terms = r.t.copy(planRights = mapOf("p" to PlanRight.VIEW))),
            "planRightsLevel" to r.copy(terms = r.t.copy(planRights = mapOf("p" to PlanRight.EDIT))),
            "scopeKind" to r.copy(terms = r.t.copy(scope = TimeScope(TimeScope.Kind.CONTINUOUS, Fx.day.toString()))),
            "scopeFrom" to r.copy(terms = r.t.copy(scope = TimeScope.from(Fx.day.minusDays(1)))),
            "scopeTo" to r.copy(terms = r.t.copy(scope = TimeScope.once(Fx.day, Fx.day.plusDays(1)))),
            "sources" to r.copy(terms = r.t.copy(sources = setOf(Provider.MANUAL))),
            "baseline" to r.copy(baseline = r.terms),
            "baselineTerms" to r.copy(baseline = r.t.copy(fields = setOf(Field.SLEEP_DURATION))),
            "issuedAtMs" to r.copy(issuedAtMs = r.issuedAtMs + 1),
            "expiresAtMs" to r.copy(expiresAtMs = r.expiresAtMs + 1),
            "schema" to r.copy(schema = 2),
        )
        val ref = text(ConsentCanonical.request(r))
        for ((name, v) in variants) {
            assertNotEquals(ref, text(ConsentCanonical.request(v)), "field $name must be bound")
        }
        assertEquals(variants.size, variants.values.map { text(ConsentCanonical.request(it)) }.toSet().size)
    }

    @Test
    fun `escaping prevents injection of lines or separators`() {
        val evil = Fx.request(Fx.grant(recipients = setOf("a,b")))
        val two = Fx.request(Fx.grant(recipients = setOf("a", "b")))
        assertNotEquals(text(ConsentCanonical.request(evil)), text(ConsentCanonical.request(two)))
        val nl = Fx.request().copy(requestId = "r1\ngrantId=evil")
        assertEquals(1, text(ConsentCanonical.request(nl)).lines().count { it.startsWith("grantId=") })
        val emptyRecipient = Fx.request(Fx.grant(recipients = setOf("")))
        val noRecipient = Fx.request(Fx.grant(recipients = emptySet()))
        assertNotEquals(text(ConsentCanonical.request(emptyRecipient)), text(ConsentCanonical.request(noRecipient)))
    }

    @Test
    fun `decision fields are all bound`() {
        val d = Fx.decide(base)
        val variants = listOf(
            d.copy(verdict = ConsentVerdict.REJECTED), d.copy(requestDigest = "00"), d.copy(requestId = "x"),
            d.copy(guardianId = "x"), d.copy(childId = "x"), d.copy(decidedAtMs = d.decidedAtMs + 1),
        )
        val ref = text(ConsentCanonical.decision(d))
        assertTrue(variants.all { text(ConsentCanonical.decision(it)) != ref })
        // The signature itself is never part of the signed bytes.
        assertContentEquals(ConsentCanonical.decision(d), ConsentCanonical.decision(d.copy(signatureHex = "other")))
        assertEquals(ConsentCanonical.requestDigestHex(base), d.requestDigest)
        // the signer id is bound: the same decision signed "as" the second guardian differs
        assertNotEquals(text(ConsentCanonical.decision(d)), text(ConsentCanonical.decision(Fx.decide(base, signer = Fx.GUARDIAN2))))
    }

    @Test
    fun `revocation and release fields are all bound`() {
        val rev = GuardianRevocation(requestId = "r1", guardianId = "g", childId = "c", permissionId = "g1#1", revokedAtMs = 5, signatureHex = "")
        val revVariants = listOf(
            rev.copy(requestId = "x"), rev.copy(guardianId = "x"), rev.copy(childId = "x"),
            rev.copy(permissionId = "g1#2"), rev.copy(revokedAtMs = 6),
        )
        assertTrue(revVariants.all { !ConsentCanonical.revocation(it).contentEquals(ConsentCanonical.revocation(rev)) })
        val rel = GuardianAgeRelease(guardianId = "g", childId = "c", linkId = "l", nonce = Fx.NONCE, issuedAtMs = 1, expiresAtMs = 2, signatureHex = "")
        val relVariants = listOf(
            rel.copy(guardianId = "x"), rel.copy(childId = "x"), rel.copy(linkId = "x"), rel.copy(nonce = "ff".repeat(16)),
            rel.copy(issuedAtMs = 2), rel.copy(expiresAtMs = 3),
        )
        assertTrue(relVariants.all { !ConsentCanonical.ageRelease(it).contentEquals(ConsentCanonical.ageRelease(rel)) })
    }

    @Test
    fun `domain separation between request decision revocation and release`() {
        val headers = listOf(
            text(ConsentCanonical.request(base)),
            text(ConsentCanonical.decision(Fx.decide(base))),
            text(ConsentCanonical.revocation(GuardianRevocation(requestId = "r", guardianId = "g", childId = "c", permissionId = "p", revokedAtMs = 1, signatureHex = ""))),
            text(ConsentCanonical.ageRelease(GuardianAgeRelease(guardianId = "g", childId = "c", linkId = "l", nonce = Fx.NONCE, issuedAtMs = 1, expiresAtMs = 2, signatureHex = ""))),
        ).map { it.substringBefore('\n') }
        assertEquals(listOf("P4-GC-v1", "P4-GD-v1", "P4-GR-v1", "P4-GA-v1"), headers)

        // A signature over a revocation or release must not verify as a decision with identical ids.
        val rev = Fx.revocation(base)
        val asDecision = Fx.decide(base).copy(signatureHex = rev.signatureHex)
        assertFalse(Fx.keys.resolver(Fx.GUARDIAN)!!(ConsentCanonical.decision(asDecision), rev.signatureHex))
    }

    @Test
    fun `subject types are domain separated and every guardian add field is bound`() {
        val add = Fx.guardianAdd()
        val lines = text(ConsentCanonical.request(add)).trimEnd('\n').split('\n')
        assertEquals("subject=GUARDIAN_ADD", lines[1])
        assertEquals(
            listOf(
                "subject", "schema", "requestId", "nonce", "guardianIds", "childId", "newGuardianId",
                "permissionVersion", "newGuardianName", "newGuardianKeyDigest", "issuedAtMs", "expiresAtMs",
            ),
            lines.drop(1).map { it.substringBefore('=') },
        )
        val variants = listOf(
            add.copy(subjectId = "other"), add.copy(subjectName = "Other"), add.copy(subjectKeyDigest = "cd".repeat(32)),
            add.copy(guardianIds = setOf("guardian", "guardian3")), add.copy(permissionVersion = 2),
            add.copy(childId = "x"), add.copy(requestId = "x"), add.copy(nonce = "ff".repeat(16)),
        )
        assertEquals(variants.size, variants.map { text(ConsentCanonical.request(it)) }.toSet().size)
        assertTrue(variants.all { text(ConsentCanonical.request(it)) != text(ConsentCanonical.request(add)) })
        // same id as a grant / circle never yields the same digest
        val ids = setOf(
            ConsentCanonical.requestDigestHex(Fx.guardianAdd(newGuardian = "x")),
            ConsentCanonical.requestDigestHex(Fx.membership(circleId = "x")),
            ConsentCanonical.requestDigestHex(Fx.request(Fx.grant(id = "x"))),
        )
        assertEquals(3, ids.size)
    }
}
