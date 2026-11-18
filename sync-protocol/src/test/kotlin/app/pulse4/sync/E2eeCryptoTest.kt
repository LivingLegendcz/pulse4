package app.pulse4.sync

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.SecureRandom

class E2eeCryptoTest {

    private data class TestIdentity(
        val id: String,
        val xPriv: ByteArray,
        val xPub: ByteArray,
        val edPriv: ByteArray,
        val edPub: ByteArray,
    ) {
        val peerKeys: PeerKeys get() = PeerKeys(xPub, edPub)
    }

    private fun newIdentity(id: String): TestIdentity {
        val (xPriv, xPub) = E2eeCrypto.generateX25519KeyPair()
        val edParams = Ed25519PrivateKeyParameters(SecureRandom())
        val edPub = edParams.generatePublicKey()
        return TestIdentity(
            id = id,
            xPriv = xPriv,
            xPub = xPub,
            edPriv = edParams.encoded,
            edPub = edPub.encoded,
        )
    }

    @Test
    fun `v3 round-trip encrypts by default and decrypts cleanly`() {
        val alice = newIdentity("alice-hash")
        val bob = newIdentity("bob-hash")

        val env = E2eeCrypto.encrypt(
            senderId = alice.id,
            recipientId = bob.id,
            content = """{"hello":"pulse4"}""",
            eventId = "evt-001",
            senderEd25519Priv32 = alice.edPriv,
            recipientX25519Pub32 = bob.xPub,
        )

        assertEquals(E2eeCrypto.VERSION_V3, env.v)
        assertNotNull(env.epk)
        assertEquals(32, Base64.getDecoder().decode(env.epk).size)

        val json = E2eeCrypto.encodeEnvelope(env)
        val decrypted = E2eeCrypto.decryptJson(
            rawJson = json,
            recipientX25519Priv32 = bob.xPriv,
            lookupPeerKeys = { sender -> if (sender == alice.id) alice.peerKeys else null },
            expectedRecipients = setOf(bob.id),
        )

        assertNotNull(decrypted)
        assertEquals(E2eeCrypto.VERSION_V3, decrypted.version)
        assertEquals(alice.id, decrypted.sender)
        assertEquals(bob.id, decrypted.recipient)
        assertEquals("evt-001", decrypted.eventId)
        assertEquals("""{"hello":"pulse4"}""", decrypted.content)
    }

    @Test
    fun `v3 generates fresh ephemeral X25519 keypair per message`() {
        val alice = newIdentity("alice")
        val bob = newIdentity("bob")

        val env1 = E2eeCrypto.encryptV3(alice.id, bob.id, "same-payload", "evt-1", alice.edPriv, bob.xPub)
        val env2 = E2eeCrypto.encryptV3(alice.id, bob.id, "same-payload", "evt-1", alice.edPriv, bob.xPub)

        assertNotEquals(env1.epk, env2.epk)
        assertNotEquals(env1.iv, env2.iv)
        assertNotEquals(env1.ciphertext, env2.ciphertext)
    }

    @Test
    fun `compromise of sender static keys cannot decrypt captured v3 ciphertext without ephemeral private key`() {
        val alice = newIdentity("alice")
        val bob = newIdentity("bob")
        val attackerWithRotatedOrWrongKey = newIdentity("eve")

        val capturedV3 = E2eeCrypto.encryptV3(
            senderId = alice.id,
            recipientId = bob.id,
            content = "sensitive-health-data",
            eventId = "evt-fs-1",
            senderEd25519Priv32 = alice.edPriv,
            recipientX25519Pub32 = bob.xPub,
        )

        // Attacker compromises Alice's static X25519 + Ed25519 private keys,
        // and tries to decrypt using Alice's static X25519 key or another static key:
        val bySenderStatic = E2eeCrypto.decrypt(
            envelope = capturedV3,
            recipientX25519Priv32 = alice.xPriv,
            lookupPeerKeys = { if (it == alice.id) alice.peerKeys else null },
        )
        assertNull(bySenderStatic, "Sender static key must NOT decrypt v3 ciphertext")

        val byOtherStatic = E2eeCrypto.decrypt(
            envelope = capturedV3,
            recipientX25519Priv32 = attackerWithRotatedOrWrongKey.xPriv,
            lookupPeerKeys = { if (it == alice.id) alice.peerKeys else null },
        )
        assertNull(byOtherStatic, "Unrelated static key must NOT decrypt v3 ciphertext")

        // Contrast with v2 where sender's static X25519 private key + recipient's public key
        // derives the exact same ECDH shared secret as recipient's static private key:
        @Suppress("DEPRECATION")
        val capturedV2 = E2eeCrypto.encryptV2Legacy(
            senderId = alice.id,
            recipientId = bob.id,
            content = "legacy-data",
            eventId = "evt-v2",
            senderX25519Priv32 = alice.xPriv,
            senderEd25519Priv32 = alice.edPriv,
            recipientX25519Pub32 = bob.xPub,
        )
        val v2SharedBySender = E2eeCrypto.ecdhX25519(alice.xPriv, bob.xPub)
        val v2SharedByRecipient = E2eeCrypto.ecdhX25519(bob.xPriv, alice.xPub)
        assertTrue(v2SharedBySender.contentEquals(v2SharedByRecipient))
        assertNotNull(
            E2eeCrypto.decrypt(capturedV2, bob.xPriv, { if (it == alice.id) alice.peerKeys else null }),
        )
    }

    @Test
    fun `all-zero X25519 shared secret from low-order points is rejected`() {
        val bob = newIdentity("bob")
        val alice = newIdentity("alice")

        // Known X25519 low-order points that produce an all-zero shared secret:
        val lowOrderPoints = listOf(
            ByteArray(32), // 0
            ByteArray(32).also { it[0] = 0x01 }, // 1
            // Order-8 point: 0xe0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800
            byteArrayOf(
                0xe0.toByte(), 0xeb.toByte(), 0x7a, 0x7c, 0x3b, 0x41, 0xb8.toByte(), 0xae.toByte(),
                0x16, 0x56, 0xe3.toByte(), 0xfa.toByte(), 0xf1.toByte(), 0x9f.toByte(), 0xc4.toByte(), 0x6a,
                0xda.toByte(), 0x09, 0x8d.toByte(), 0xeb.toByte(), 0x9c.toByte(), 0x32, 0xb1.toByte(), 0xfd.toByte(),
                0x86.toByte(), 0x62, 0x05, 0x16, 0x5f, 0x49, 0xb8.toByte(), 0x00,
            ),
        )

        for (weakPoint in lowOrderPoints) {
            assertFailsWith<WeakX25519KeyException> {
                E2eeCrypto.ecdhX25519(bob.xPriv, weakPoint)
            }
            assertFailsWith<WeakX25519KeyException> {
                E2eeCrypto.encryptV3(alice.id, bob.id, "msg", "evt-weak", alice.edPriv, weakPoint)
            }
        }

        // Also reject all-zero secret in deriveV3Key:
        assertFailsWith<WeakX25519KeyException> {
            E2eeCrypto.deriveV3Key(ByteArray(32), "evt-weak")
        }

        // And decrypt() rejects an envelope whose epk is a low-order point:
        val validEnv = E2eeCrypto.encryptV3(alice.id, bob.id, "msg", "evt-1", alice.edPriv, bob.xPub)
        val weakEpkEnv = validEnv.copy(epk = Base64.getEncoder().encodeToString(lowOrderPoints[2]))
        assertNull(
            E2eeCrypto.decrypt(weakEpkEnv, bob.xPriv, { if (it == alice.id) alice.peerKeys else null }),
        )
    }

    @Test
    fun `tampered epk, msgId, iv, or ciphertext fails AEAD authentication`() {
        val alice = newIdentity("alice")
        val bob = newIdentity("bob")
        val env = E2eeCrypto.encryptV3(alice.id, bob.id, "secret-payload", "evt-auth-1", alice.edPriv, bob.xPub)
        val lookup: (String) -> PeerKeys? = { if (it == alice.id) alice.peerKeys else null }

        // 1. Tampered epk
        val epkBytes = Base64.getDecoder().decode(env.epk!!)
        epkBytes[0] = (epkBytes[0].toInt() xor 0x01).toByte()
        val tamperedEpk = env.copy(epk = Base64.getEncoder().encodeToString(epkBytes))
        assertNull(E2eeCrypto.decrypt(tamperedEpk, bob.xPriv, lookup))

        // 2. Tampered eventId / msgId
        val tamperedMsgId = env.copy(eventId = "evt-auth-2")
        assertNull(E2eeCrypto.decrypt(tamperedMsgId, bob.xPriv, lookup))

        // 3. Tampered ciphertext
        val ctBytes = Base64.getDecoder().decode(env.ciphertext)
        ctBytes[ctBytes.size / 2] = (ctBytes[ctBytes.size / 2].toInt() xor 0xFF).toByte()
        val tamperedCt = env.copy(ciphertext = Base64.getEncoder().encodeToString(ctBytes))
        assertNull(E2eeCrypto.decrypt(tamperedCt, bob.xPriv, lookup))

        // 4. Tampered IV
        val ivBytes = Base64.getDecoder().decode(env.iv)
        ivBytes[0] = (ivBytes[0].toInt() xor 0x80).toByte()
        val tamperedIv = env.copy(iv = Base64.getEncoder().encodeToString(ivBytes))
        assertNull(E2eeCrypto.decrypt(tamperedIv, bob.xPriv, lookup))
    }

    @Test
    fun `v2 backward compatibility still works`() {
        val alice = newIdentity("alice-v2")
        val bob = newIdentity("bob-v2")

        @Suppress("DEPRECATION")
        val legacyEnv = E2eeCrypto.encryptV2Legacy(
            senderId = alice.id,
            recipientId = bob.id,
            content = """{"legacy":true}""",
            eventId = "evt-v2-1",
            senderX25519Priv32 = alice.xPriv,
            senderEd25519Priv32 = alice.edPriv,
            recipientX25519Pub32 = bob.xPub,
        )
        assertEquals(E2eeCrypto.VERSION_V2, legacyEnv.v)

        val rawJson = E2eeCrypto.encodeEnvelope(legacyEnv)
        val decrypted = E2eeCrypto.decryptJson(
            rawJson = rawJson,
            recipientX25519Priv32 = bob.xPriv,
            lookupPeerKeys = { if (it == alice.id) alice.peerKeys else null },
            expectedRecipients = setOf(bob.id),
        )

        assertNotNull(decrypted)
        assertEquals(E2eeCrypto.VERSION_V2, decrypted.version)
        assertEquals(alice.id, decrypted.sender)
        assertEquals(bob.id, decrypted.recipient)
        assertEquals("evt-v2-1", decrypted.eventId)
        assertEquals("""{"legacy":true}""", decrypted.content)
    }

    @Test
    fun `SEC-3 outer v3 JSON hides sender, recipient, mac, and pads payload length`() {
        val alice = newIdentity("alice-secret-id")
        val bob = newIdentity("bob-secret-id")

        val shortEnv = E2eeCrypto.encryptV3(alice.id, bob.id, "hi", "e1", alice.edPriv, bob.xPub)
        val mediumEnv = E2eeCrypto.encryptV3(alice.id, bob.id, "a".repeat(20), "e2", alice.edPriv, bob.xPub)

        val shortJson = E2eeCrypto.encodeEnvelope(shortEnv)
        assertFalse(shortJson.contains("alice-secret-id"), "Outer JSON must not leak sender")
        assertFalse(shortJson.contains("bob-secret-id"), "Outer JSON must not leak recipient by default")
        assertFalse(shortJson.contains("\"mac\""), "Outer JSON must not expose publicly verifiable mac")
        assertFalse(shortJson.contains("\"sender\""), "Outer JSON must omit sender field")

        // Both short and medium messages pad to the same 256B bucket (+ 16B GCM tag = 272B)
        val shortCtLen = Base64.getDecoder().decode(shortEnv.ciphertext).size
        val mediumCtLen = Base64.getDecoder().decode(mediumEnv.ciphertext).size
        assertEquals(256 + 16, shortCtLen)
        assertEquals(256 + 16, mediumCtLen)

        // Larger messages pad to the 512B bucket (+ 16B GCM tag = 528B)
        val large1 = E2eeCrypto.encryptV3(alice.id, bob.id, "a".repeat(80), "e3", alice.edPriv, bob.xPub)
        val large2 = E2eeCrypto.encryptV3(alice.id, bob.id, "a".repeat(200), "e4", alice.edPriv, bob.xPub)
        assertEquals(512 + 16, Base64.getDecoder().decode(large1.ciphertext).size)
        assertEquals(512 + 16, Base64.getDecoder().decode(large2.ciphertext).size)
    }

    @Test
    fun `HKDF-SHA256 matches RFC 5869 Appendix A_1 Test Case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c)
        val info = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())
        val okm = E2eeCrypto.hkdfSha256(ikm, salt, info, length = 42)
        val expectedHex = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        assertEquals(expectedHex, okm.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `wrong expectedRecipient rejects decrypted v3 envelope`() {
        val alice = newIdentity("alice")
        val bob = newIdentity("bob")
        val env = E2eeCrypto.encryptV3(alice.id, bob.id, "msg", "evt-r", alice.edPriv, bob.xPub)

        val rejected = E2eeCrypto.decrypt(
            envelope = env,
            recipientX25519Priv32 = bob.xPriv,
            lookupPeerKeys = { if (it == alice.id) alice.peerKeys else null },
            expectedRecipients = setOf("someone-else"),
        )
        assertNull(rejected)
    }
}
