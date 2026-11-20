package app.pulse4.sync

import app.pulse4.sharing.ConsentCanonical
import app.pulse4.sharing.ConsentCheck
import app.pulse4.sharing.ConsentStatus
import app.pulse4.sharing.ConsentVerdict
import app.pulse4.sharing.Field
import app.pulse4.sharing.FamilyLink
import app.pulse4.sharing.FamilyLinkStatus
import app.pulse4.sharing.AgeProfile
import app.pulse4.sharing.ConsentRecord
import app.pulse4.sharing.GuardianConsentPolicy
import app.pulse4.sharing.GuardianDecision
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.TimeScope
import java.security.SecureRandom
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

class ConsentCryptoTest {
    private class Key(val priv: ByteArray, val pub: ByteArray)

    private fun newKey(): Key {
        val p = Ed25519PrivateKeyParameters(SecureRandom())
        return Key(p.encoded, p.generatePublicKey().encoded)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val guardian = newKey()
    private val child = newKey()
    private val msg = ConsentCanonical.ageRelease(
        app.pulse4.sharing.GuardianAgeRelease(
            guardianId = "g", childId = "c", linkId = "l", nonce = "00".repeat(16), issuedAtMs = 1, expiresAtMs = 2, signatureHex = "",
        ),
    )

    @Test
    fun `sign verify roundtrip`() {
        val sig = E2eeCrypto.signDetached(guardian.priv, msg)
        assertEquals(64, sig.size)
        assertTrue(E2eeCrypto.verifyDetached(guardian.pub, msg, sig))
    }

    @Test
    fun `tampered message or signature fails`() {
        val sig = E2eeCrypto.signDetached(guardian.priv, msg)
        val flipped = msg.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        assertFalse(E2eeCrypto.verifyDetached(guardian.pub, flipped, sig))
        val badSig = sig.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(E2eeCrypto.verifyDetached(guardian.pub, msg, badSig))
        assertFalse(E2eeCrypto.verifyDetached(guardian.pub, msg, sig.copyOf(63)))
        assertFalse(E2eeCrypto.verifyDetached(guardian.pub, msg, ByteArray(0)))
    }

    @Test
    fun `wrong key or malformed key fails without throwing`() {
        val sig = E2eeCrypto.signDetached(guardian.priv, msg)
        assertFalse(E2eeCrypto.verifyDetached(child.pub, msg, sig))
        assertFalse(E2eeCrypto.verifyDetached(ByteArray(31), msg, sig))
        assertFalse(E2eeCrypto.verifyDetached(ByteArray(32) { 0x7f }, msg, sig))
    }

    @Test
    fun `invalid private key length is rejected`() {
        assertTrue(runCatching { E2eeCrypto.signDetached(ByteArray(31), msg) }.isFailure)
    }

    @Test
    fun `domain headers keep consent signatures apart from other messages`() {
        // A consent signature never validates as an E2EE v3 style signature input and vice versa.
        val sig = E2eeCrypto.signDetached(guardian.priv, msg)
        val other = "pulse4-e2ee-v3-sig:g:c:e:epk:hash".toByteArray()
        assertFalse(E2eeCrypto.verifyDetached(guardian.pub, other, sig))
    }

    @Test
    fun `end to end guardian decision with real ed25519 through the policy`() {
        val day = LocalDate.of(2026, 1, 10)
        val link = FamilyLink("fl", "guardian-id", "child-id", FamilyLinkStatus.VERIFIED, AgeProfile.MINOR, createdAtMs = 1, verifiedAtMs = 2, version = 2)
        val grant = ShareGrant("g1", "child-id", setOf("coach"), setOf(Field.SLEEP_DURATION), scope = TimeScope.from(day), createdAtMs = 1)
        val req = GuardianConsentPolicy.newRequest("r1", "00112233445566778899aabbccddeeff", setOf("guardian-id"), grant, 1, null, 1_000)
        val unsigned = GuardianDecision(
            verdict = ConsentVerdict.APPROVED, requestId = "r1", guardianId = "guardian-id", childId = "child-id",
            requestDigest = ConsentCanonical.requestDigestHex(req), decidedAtMs = 2_000, signatureHex = "",
        )
        val signed = unsigned.copy(signatureHex = hex(E2eeCrypto.signDetached(guardian.priv, ConsentCanonical.decision(unsigned))))
        fun resolver(pub: ByteArray): (String) -> ((ByteArray, String) -> Boolean)? = { id ->
            if (id == "guardian-id") { bytes, sig -> E2eeCrypto.verifyDetached(pub, bytes, unhex(sig)) } else null
        }
        val records = listOf(ConsentRecord(req, ConsentStatus.PENDING))
        val ok = GuardianConsentPolicy.verifyDecision("child-id", "guardian-id", signed, grant, records, listOf(link), 3_000, resolver(guardian.pub))
        assertEquals(ConsentCheck.OK, ok)
        val wrong = GuardianConsentPolicy.verifyDecision("child-id", "guardian-id", signed, grant, records, listOf(link), 3_000, resolver(child.pub))
        assertEquals(ConsentCheck.BAD_SIGNATURE, wrong)
    }
}
