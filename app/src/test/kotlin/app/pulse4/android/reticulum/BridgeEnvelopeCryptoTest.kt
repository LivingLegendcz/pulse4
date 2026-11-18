package app.pulse4.android.reticulum

import app.pulse4.sync.E2eeCrypto
import app.pulse4.sync.E2eeEnvelope
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

class BridgeEnvelopeCryptoTest {

    private fun testIdentity(seedByte: Byte): Triple<X25519PrivateKeyParameters, Ed25519PrivateKeyParameters, String> {
        val seed = ByteArray(32) { (it + seedByte.toInt()).toByte() }
        val edPriv = Ed25519PrivateKeyParameters(seed, 0)
        val edPub = edPriv.generatePublicKey().encoded
        val xSeed = MessageDigest.getInstance("SHA-256").digest(seed)
        val xPriv = X25519PrivateKeyParameters(xSeed, 0)
        val xPub = xPriv.generatePublicKey().encoded
        val pub64B64 = Base64.getEncoder().encodeToString(xPub + edPub)
        return Triple(xPriv, edPriv, pub64B64)
    }

    @Test
    fun outboundV3OmitsOuterMetadataAndDecrypts() {
        val (aliceXPriv, aliceEdPriv, alicePubB64) = testIdentity(1)
        val (bobXPriv, _, bobPubB64) = testIdentity(2)
        val aliceHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val alicePrimaryHash = "cccccccccccccccccccccccccccccccc"
        val bobHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        val env = BridgeEnvelopeCrypto.encrypt(
            sender = aliceHash,
            recipient = bobHash,
            content = """{"kind":"projection.upsert","hr":72}""",
            eventId = "evt-v3-1",
            edPriv = aliceEdPriv,
            recipientPubKeyB64 = bobPubB64,
        )
        assertEquals(E2eeCrypto.VERSION_V3, env.v)
        assertNull(env.sender)
        assertNull(env.recipient)
        assertNull(env.mac)
        assertNotNull(env.epk)

        val wireJson = BridgeEnvelopeCrypto.encode(env)
        assertFalse(wireJson.contains("\"sender\""), "SEC-3: outer JSON must not expose sender")
        assertFalse(wireJson.contains("\"recipient\""), "SEC-3: outer JSON must not expose recipient")
        assertFalse(wireJson.contains("\"mac\""), "SEC-3: outer JSON must not expose mac")

        val result = BridgeEnvelopeCrypto.decryptToBridgeMessage(
            rawJson = wireJson,
            xPriv = bobXPriv,
            expectedRecipients = setOf(bobHash),
            lookupContactPubKeyB64 = { id -> if (id == aliceHash) alicePubB64 else null },
            normalizeSource = { id -> if (id == aliceHash) alicePrimaryHash else id },
        )
        assertNotNull(result)
        val (dec, msg) = result
        assertEquals(E2eeCrypto.VERSION_V3, dec.version)
        assertEquals(alicePrimaryHash, msg.source)
        assertEquals("""{"kind":"projection.upsert","hr":72}""", msg.content)

        // Wrong expected recipient must fail
        val wrongRecipient = BridgeEnvelopeCrypto.decryptToBridgeMessage(
            rawJson = wireJson,
            xPriv = bobXPriv,
            expectedRecipients = setOf("dddddddddddddddddddddddddddddddd"),
            lookupContactPubKeyB64 = { id -> if (id == aliceHash) alicePubB64 else null },
        )
        assertNull(wrongRecipient)

        // Ensure unused variable doesn't warn
        assertNotNull(aliceXPriv)
    }

    @Test
    fun inboundLegacyV2RemainsDecryptable() {
        val (aliceXPriv, aliceEdPriv, alicePubB64) = testIdentity(10)
        val (bobXPriv, _, bobPubB64) = testIdentity(20)
        val bobKeys = BridgeEnvelopeCrypto.decodePeerKeys64(bobPubB64)!!
        val aliceHash = "11111111111111111111111111111111"
        val bobHash = "22222222222222222222222222222222"
        val eventId = "evt-v2-legacy"
        val plaintext = "legacy-v2-payload"

        // Construct a legacy v2 envelope matching the pre-v3 format
        val agreement = X25519Agreement()
        agreement.init(aliceXPriv)
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(org.bouncycastle.crypto.params.X25519PublicKeyParameters(bobKeys.x25519Pub32, 0), shared, 0)
        val (a, b) = if (aliceHash <= bobHash) aliceHash to bobHash else bobHash to aliceHash
        val md = MessageDigest.getInstance("SHA-256")
        md.update(shared)
        md.update("pulse4-e2ee-v2".toByteArray(Charsets.UTF_8))
        md.update(a.toByteArray(Charsets.UTF_8))
        md.update(b.toByteArray(Charsets.UTF_8))
        val key = SecretKeySpec(md.digest(), "AES")

        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val ctB64 = Base64.getEncoder().encodeToString(ct)
        val sigInput = "pulse4-v2:$aliceHash:$bobHash:$ivB64:$ctB64:$eventId".toByteArray(Charsets.UTF_8)
        val signer = Ed25519Signer()
        signer.init(true, aliceEdPriv)
        signer.update(sigInput, 0, sigInput.size)
        val macHex = RnsCrypto.bytesToHex(signer.generateSignature())

        val v2Env = E2eeEnvelope(
            v = 2,
            sender = aliceHash,
            recipient = bobHash,
            iv = ivB64,
            ciphertext = ctB64,
            mac = macHex,
            eventId = eventId,
        )
        val wireJson = BridgeEnvelopeCrypto.encode(v2Env)
        val result = BridgeEnvelopeCrypto.decryptToBridgeMessage(
            rawJson = wireJson,
            xPriv = bobXPriv,
            expectedRecipients = setOf(bobHash),
            lookupContactPubKeyB64 = { id -> if (id == aliceHash) alicePubB64 else null },
        )
        assertNotNull(result)
        assertEquals(2, result.first.version)
        assertEquals(plaintext, result.second.content)
    }
}
