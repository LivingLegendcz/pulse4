package app.pulse4.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AgeProfilePolicyTest {
    private val unknown = AgeProfileRecord()
    private val adult = AgeProfileRecord(AgeState.ADULT, AgeSource.DECLARED, 5)
    private val minor = AgeProfileRecord(AgeState.MINOR, AgeSource.LINK_ACCEPTED, 5, Fx.GUARDIAN)
    private val link = Fx.link()
    private val now = Fx.NOW

    @Test
    fun `declaration transitions`() {
        assertEquals(AgeState.ADULT, AgeProfilePolicy.declare(unknown, AgeState.ADULT, 1)?.state)
        assertEquals(AgeState.MINOR, AgeProfilePolicy.declare(unknown, AgeState.MINOR, 1)?.state)
        assertEquals(AgeState.MINOR, AgeProfilePolicy.declare(adult, AgeState.MINOR, 2)?.state)
        assertEquals(minor, AgeProfilePolicy.declare(minor, AgeState.MINOR, 2))
        assertEquals(adult, AgeProfilePolicy.declare(adult, AgeState.ADULT, 2))
        assertNull(AgeProfilePolicy.declare(minor, AgeState.ADULT, 2))
        assertNull(AgeProfilePolicy.declare(unknown, AgeState.UNKNOWN, 2))
        assertNull(AgeProfilePolicy.declare(adult, AgeState.UNKNOWN, 2))
        assertNull(AgeProfilePolicy.declare(minor, AgeState.UNKNOWN, 2))
    }

    @Test
    fun `accepted minor link ratchets to minor from any state`() {
        for (cur in listOf(unknown, adult)) {
            val r = AgeProfilePolicy.onLinkAccepted(cur, link, Fx.CHILD, 9)
            assertEquals(AgeState.MINOR, r.state)
            assertEquals(Fx.GUARDIAN, r.lockedByGuardianId)
            assertEquals(AgeSource.LINK_ACCEPTED, r.source)
        }
        assertEquals(minor, AgeProfilePolicy.onLinkAccepted(minor, link, Fx.CHILD, 9))
        assertEquals(Fx.GUARDIAN, AgeProfilePolicy.onLinkAccepted(AgeProfileRecord(AgeState.MINOR, AgeSource.DECLARED, 1), link, Fx.CHILD, 9).lockedByGuardianId)
        // links that do not make this user a minor leave the profile unchanged
        assertEquals(adult, AgeProfilePolicy.onLinkAccepted(adult, Fx.link(profile = AgeProfile.ADULT), Fx.CHILD, 9))
        assertEquals(adult, AgeProfilePolicy.onLinkAccepted(adult, Fx.link(status = FamilyLinkStatus.PENDING), Fx.CHILD, 9))
        assertEquals(adult, AgeProfilePolicy.onLinkAccepted(adult, link, "somebody-else", 9))
    }

    private fun release(
        guardian: String = Fx.GUARDIAN,
        child: String = Fx.CHILD,
        linkId: String = link.id,
        nonce: String = Fx.NONCE,
        issued: Long = now - 1000,
        expires: Long = now + 60_000,
        signer: String = Fx.GUARDIAN,
    ): GuardianAgeRelease {
        val u = GuardianAgeRelease(guardianId = guardian, childId = child, linkId = linkId, nonce = nonce, issuedAtMs = issued, expiresAtMs = expires, signatureHex = "")
        return u.copy(signatureHex = Fx.keys.sign(signer, ConsentCanonical.ageRelease(u)))
    }

    private fun apply(
        rel: GuardianAgeRelease = release(),
        current: AgeProfileRecord = minor,
        author: String = rel.guardianId,
        links: List<FamilyLink> = listOf(link),
        records: List<ConsentRecord> = emptyList(),
        used: Set<String> = emptySet(),
        at: Long = now,
        resolver: SignatureVerifierResolver = Fx.keys.resolver,
    ) = AgeProfilePolicy.applyRelease(current, Fx.CHILD, author, rel, links, records, used, at, resolver)

    private fun rejected(o: ReleaseOutcome): ReleaseRejection =
        (o as? ReleaseOutcome.Rejected)?.reason ?: error("expected rejection but got $o")

    @Test
    fun `verified release from locking guardian unlocks`() {
        val o = apply()
        assertTrue(o is ReleaseOutcome.Released)
        assertEquals(AgeState.ADULT, o.record.state)
        assertEquals(AgeSource.GUARDIAN_RELEASE, o.record.source)
        assertEquals(now, o.record.atMs)
        // once the guardian link is gone nobody can release any more
        assertEquals(
            ReleaseRejection.NOT_A_GUARDIAN,
            rejected(apply(links = listOf(Fx.link(status = FamilyLinkStatus.REVOKED)))),
        )
    }

    @Test
    fun `forged release is rejected`() {
        assertEquals(ReleaseRejection.BAD_SIGNATURE, rejected(apply(release(signer = Fx.CHILD))))
        assertEquals(ReleaseRejection.BAD_SIGNATURE, rejected(apply(release().copy(signatureHex = ""))))
        assertEquals(ReleaseRejection.BAD_SIGNATURE, rejected(apply(release().copy(expiresAtMs = now + 120_000))))
        assertEquals(ReleaseRejection.NO_GUARDIAN_KEY, rejected(apply(resolver = { null })))
    }

    @Test
    fun `release from a wrong guardian or with wrong ids is rejected`() {
        val strangerRel = release(guardian = Fx.STRANGER, signer = Fx.STRANGER)
        assertEquals(ReleaseRejection.NOT_A_GUARDIAN, rejected(apply(strangerRel)))
        assertEquals(ReleaseRejection.ID_MISMATCH, rejected(apply(release(child = "other"))))
        assertEquals(ReleaseRejection.AUTHOR_MISMATCH, rejected(apply(release(), author = Fx.STRANGER)))
        assertEquals(ReleaseRejection.LINK_INVALID, rejected(apply(release(linkId = "nope"))))
        assertEquals(ReleaseRejection.NOT_A_GUARDIAN, rejected(apply(links = listOf(Fx.link(parent = Fx.STRANGER)))))
        assertEquals(ReleaseRejection.NOT_A_GUARDIAN, rejected(apply(links = listOf(Fx.link(status = FamilyLinkStatus.PENDING)))))
        assertEquals(ReleaseRejection.UNSUPPORTED_SCHEMA, rejected(apply(release().copy(schema = 7))))
    }

    @Test
    fun `expired invalid and replayed release is rejected`() {
        assertEquals(ReleaseRejection.EXPIRED, rejected(apply(at = now + 60_001)))
        assertEquals(ReleaseRejection.INVALID_VALIDITY, rejected(apply(release(issued = now, expires = now - 1))))
        assertEquals(ReleaseRejection.INVALID_VALIDITY, rejected(apply(release(expires = now + AgeProfilePolicy.MAX_RELEASE_TTL_MS + 5_000))))
        assertEquals(ReleaseRejection.NONCE_REUSED, rejected(apply(used = setOf(Fx.NONCE))))
        assertEquals(ReleaseRejection.BAD_NONCE, rejected(apply(release(nonce = "short"))))
    }

    @Test
    fun `release only applies to a minor profile with a verified guardian`() {
        assertEquals(ReleaseRejection.NOT_MINOR, rejected(apply(current = adult)))
        assertEquals(ReleaseRejection.NOT_MINOR, rejected(apply(current = unknown)))
        assertEquals(ReleaseRejection.NOT_A_GUARDIAN, rejected(apply(links = emptyList())))
        // a declared minor without recorded locking guardian can still be released by a verified guardian
        val declaredMinor = AgeProfileRecord(AgeState.MINOR, AgeSource.DECLARED, 1)
        assertTrue(apply(current = declaredMinor) is ReleaseOutcome.Released)
    }

    @Test
    fun `record serializes with defaults`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        assertEquals(minor, json.decodeFromString<AgeProfileRecord>(json.encodeToString(minor)))
        assertEquals(unknown, json.decodeFromString<AgeProfileRecord>("{}"))
    }
}
