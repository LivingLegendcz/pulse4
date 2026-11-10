package app.pulse4.android.reticulum

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReticulumCryptoTest {

    @Test
    fun testEd25519Support() {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()
        assertNotNull(kp.public)
        assertNotNull(kp.private)

        val sig = Signature.getInstance("Ed25519")
        sig.initSign(kp.private)
        sig.update("pulse4-test".toByteArray(Charsets.UTF_8))
        val signatureBytes = sig.sign()
        assertEquals(64, signatureBytes.size)

        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(kp.public)
        verifier.update("pulse4-test".toByteArray(Charsets.UTF_8))
        assertTrue(verifier.verify(signatureBytes))
    }

    @Test
    fun testX25519Support() {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val kp = kpg.generateKeyPair()
        assertNotNull(kp.public)
        assertNotNull(kp.private)
        val rawX509 = kp.public.encoded
        // Raw 32 bytes are at the end
        assertTrue(rawX509.size >= 32)
    }

    @Test
    fun testHdlcEscapeUnescapeRoundtrip() {
        val testBytes = byteArrayOf(
            0x00, 0x01, 0x7E.toByte(), 0x7D.toByte(), 0x5E.toByte(), 0x5D.toByte(), 0xFF.toByte()
        )
        val escaped = ReticulumBridge.hdlcEscape(testBytes)
        // Escaped should not contain unescaped 0x7E or 0x7D
        assertTrue(escaped.size > testBytes.size)
        val unescaped = ReticulumBridge.hdlcUnescape(escaped)
        assertEquals(testBytes.toList(), unescaped.toList())
    }

    @Test
    fun testHexConversion() {
        val hex = "0123456789abcdef"
        val bytes = ReticulumBridge.hexToBytes(hex)
        assertEquals(8, bytes.size)
        assertEquals(hex, ReticulumBridge.bytesToHex(bytes))
    }

    @Test
    fun testRnsDestinationHashAndAnnounceSignature() {
        val xKpg = KeyPairGenerator.getInstance("X25519")
        val xKp = xKpg.generateKeyPair()
        val edKpg = KeyPairGenerator.getInstance("Ed25519")
        val edKp = edKpg.generateKeyPair()

        val rawX = xKp.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }
        val rawEd = edKp.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }
        val pubKey64 = rawX + rawEd
        assertEquals(64, pubKey64.size)

        val sha256 = MessageDigest.getInstance("SHA-256")
        val identHash = sha256.digest(pubKey64).copyOf(16)
        val nameHash = sha256.digest("pulse.delivery".toByteArray(Charsets.UTF_8)).copyOf(10)
        val destHash = sha256.digest(nameHash + identHash).copyOf(16)

        val randomHash = ByteArray(10) { it.toByte() }
        val appData = "Test".toByteArray(Charsets.UTF_8)
        val signedData = destHash + pubKey64 + nameHash + randomHash + appData

        val sig = Signature.getInstance("Ed25519")
        sig.initSign(edKp.private)
        sig.update(signedData)
        val signature = sig.sign()
        assertEquals(64, signature.size)

        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(edKp.public)
        verifier.update(signedData)
        assertTrue(verifier.verify(signature))
    }

    @Test
    fun testBouncyCastleEd25519AndX25519Compatibility() {
        val seed = ByteArray(32) { (it * 7).toByte() }
        val edPriv = Ed25519PrivateKeyParameters(seed, 0)
        val edPub = edPriv.generatePublicKey()
        assertEquals(32, edPub.encoded.size)

        val xSeed = MessageDigest.getInstance("SHA-256").digest(seed)
        val xPriv = X25519PrivateKeyParameters(xSeed, 0)
        val xPub = xPriv.generatePublicKey()
        assertEquals(32, xPub.encoded.size)

        val pubKey64 = xPub.encoded + edPub.encoded
        assertEquals(64, pubKey64.size)

        val sha256 = MessageDigest.getInstance("SHA-256")
        val identHash = sha256.digest(pubKey64).copyOf(16)
        val nameHash = sha256.digest("pulse.delivery".toByteArray(Charsets.UTF_8)).copyOf(10)
        val destHash = sha256.digest(nameHash + identHash).copyOf(16)

        val randomHash = ByteArray(10) { it.toByte() }
        val appData = "Test".toByteArray(Charsets.UTF_8)
        val signedData = destHash + pubKey64 + nameHash + randomHash + appData

        val signer = Ed25519Signer()
        signer.init(true, edPriv)
        signer.update(signedData, 0, signedData.size)
        val signature = signer.generateSignature()
        assertEquals(64, signature.size)

        val verifier = Ed25519Signer()
        verifier.init(false, edPub)
        verifier.update(signedData, 0, signedData.size)
        assertTrue(verifier.verifySignature(signature))
    }

    @Test
    fun testRnsHeader1AndHeader2Parsing() {
        val dest16 = ByteArray(16) { (it + 1).toByte() }
        val destHex = ReticulumBridge.bytesToHex(dest16)
        val payload = "HELLO_PULSE".toByteArray(Charsets.UTF_8)

        // HEADER_1: flags (0x08), hops (0x00), dest (16B), context (0x00), payload
        val h1 = byteArrayOf(0x08, 0x00) + dest16 + byteArrayOf(0x00) + payload
        assertEquals(2 + 16 + 1 + payload.size, h1.size)
        val h1Type = ((h1[0].toInt() and 0xFF) and 0x40) shr 6
        assertEquals(0, h1Type)
        val h1Dest = ReticulumBridge.bytesToHex(h1.copyOfRange(2, 18))
        assertEquals(destHex, h1Dest)
        val h1Payload = h1.copyOfRange(19, h1.size)
        assertEquals("HELLO_PULSE", String(h1Payload, Charsets.UTF_8))

        // HEADER_2 (transport routed): flags (0x48), hops (0x01), transport_id (16B), dest (16B), context (0x00), payload
        val transportId16 = ByteArray(16) { 0xAA.toByte() }
        val h2 = byteArrayOf(0x48, 0x01) + transportId16 + dest16 + byteArrayOf(0x00) + payload
        assertEquals(2 + 16 + 16 + 1 + payload.size, h2.size)
        val h2Type = ((h2[0].toInt() and 0xFF) and 0x40) shr 6
        assertEquals(1, h2Type)
        val h2Dest = ReticulumBridge.bytesToHex(h2.copyOfRange(18, 34))
        assertEquals(destHex, h2Dest)
        val h2Payload = h2.copyOfRange(35, h2.size)
        assertEquals("HELLO_PULSE", String(h2Payload, Charsets.UTF_8))
    }
}
