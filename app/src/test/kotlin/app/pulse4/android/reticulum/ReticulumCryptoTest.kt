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

    @Test
    fun testX25519EcdhSharedSecretAgreement() {
        val aliceSeed = ByteArray(32) { (it + 1).toByte() }
        val bobSeed = ByteArray(32) { (it + 101).toByte() }

        val aliceXPriv = X25519PrivateKeyParameters(MessageDigest.getInstance("SHA-256").digest(aliceSeed), 0)
        val aliceXPub = aliceXPriv.generatePublicKey()
        val bobXPriv = X25519PrivateKeyParameters(MessageDigest.getInstance("SHA-256").digest(bobSeed), 0)
        val bobXPub = bobXPriv.generatePublicKey()

        val a1 = org.bouncycastle.crypto.agreement.X25519Agreement().apply { init(aliceXPriv) }
        val secretA = ByteArray(a1.agreementSize)
        a1.calculateAgreement(bobXPub, secretA, 0)

        val a2 = org.bouncycastle.crypto.agreement.X25519Agreement().apply { init(bobXPriv) }
        val secretB = ByteArray(a2.agreementSize)
        a2.calculateAgreement(aliceXPub, secretB, 0)

        assertEquals(secretA.toList(), secretB.toList())
    }

    @Test
    fun testMsgPackCanonicalRoundtrip() {
        val sample = listOf(
            false,
            1731700000L,
            true,
            128L,
            -42L,
            3.14159,
            "Pulse4-ČŘŽ",
            byteArrayOf(1, 2, 3, 4),
            mapOf("cost" to 16L, "mode" to "sync"),
        )
        val encoded = MsgPack.encode(sample)
        val (decoded, consumed) = MsgPack.decode(encoded)
        assertEquals(encoded.size, consumed)
        val list = decoded as List<*>
        assertEquals(false, list[0])
        assertEquals(1731700000L, list[1])
        assertEquals(true, list[2])
        assertEquals(128L, list[3])
        assertEquals(-42L, list[4])
        assertEquals("Pulse4-ČŘŽ", list[6])
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte()), (list[7] as ByteArray).toList())
        assertEquals(16L, (list[8] as Map<*, *>)["cost"])
    }

    @Test
    fun testRnsSignallingAndFernetIdentityRoundtrip() {
        val sig3 = RnsCrypto.encodeSignalling(mtu = 32768, mode = 1)
        val (mtu, mode) = RnsCrypto.decodeSignalling(sig3)
        assertEquals(32768, mtu)
        assertEquals(1, mode)

        val seed = ByteArray(32) { (it + 9).toByte() }
        val xPriv = X25519PrivateKeyParameters(MessageDigest.getInstance("SHA-256").digest(seed), 0)
        val xPub = xPriv.generatePublicKey().encoded
        val identHash = ByteArray(16) { 0x42 }

        val pt = "Tajná zpráva pro LXMF".toByteArray(Charsets.UTF_8)
        val ct = RnsCrypto.encryptForIdentity(pt, xPub, identHash)
        val decrypted = RnsCrypto.decryptForIdentity(ct, xPriv, identHash)
        assertNotNull(decrypted)
        assertEquals("Tajná zpráva pro LXMF", String(decrypted, Charsets.UTF_8))

        // Tamper HMAC -> must reject
        val tampered = ct.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        assertEquals(null, RnsCrypto.decryptForIdentity(tampered, xPriv, identHash))
    }

    @Test
    fun testRnsAnnounceParserWithRatchetAndLxmfPropagationAppData() {
        val seed = ByteArray(32) { (it + 3).toByte() }
        val edPriv = Ed25519PrivateKeyParameters(seed, 0)
        val edPub = edPriv.generatePublicKey()
        val xPriv = X25519PrivateKeyParameters(MessageDigest.getInstance("SHA-256").digest(seed), 0)
        val pubKey64 = xPriv.generatePublicKey().encoded + edPub.encoded

        val sha = MessageDigest.getInstance("SHA-256")
        val identHash = sha.digest(pubKey64).copyOf(16)
        val nameHash = sha.digest("lxmf.propagation".toByteArray(Charsets.UTF_8)).copyOf(10)
        val dest16 = sha.digest(nameHash + identHash).copyOf(16)
        val randomHash = ByteArray(10) { 0x11 }
        val ratchet32 = ByteArray(32) { 0x22 }
        val appData = MsgPack.encode(
            listOf(false, 1731700000L, true, 512L, 2048L, listOf(12L, 2L, 18L), mapOf("name" to "xali"))
        )

        val signed = dest16 + pubKey64 + nameHash + randomHash + ratchet32 + appData
        val signer = Ed25519Signer().apply { init(true, edPriv) }
        signer.update(signed, 0, signed.size)
        val sig64 = signer.generateSignature()

        // HEADER_1 (0), contextFlag=1 (0x20), ANNOUNCE (0x01) -> flags = 0x21
        val pkt = byteArrayOf(0x21, 0x00) + dest16 + byteArrayOf(0x00) +
            pubKey64 + nameHash + randomHash + ratchet32 + sig64 + appData

        val parsed = RnsAnnounceParser.parse(pkt)
        assertNotNull(parsed)
        assertTrue(parsed.verified)
        assertEquals(ReticulumBridge.bytesToHex(dest16), parsed.destinationHash)
        assertNotNull(parsed.ratchetPub32)

        val propInfo = LxmfCodec.parsePropagationAnnounceAppData(parsed.appData)
        assertNotNull(propInfo)
        assertTrue(propInfo.acceptingMessages)
        assertEquals(512L, propInfo.perTransferLimitKb)
        assertEquals(12, propInfo.stampCost)
        assertEquals("xali", propInfo.metadata["name"])
    }

    @Test
    fun testRnsLinkHandshakeAndLxmfPropagationEnvelopeRoundtrip() {
        val aliceSeed = ByteArray(32) { (it + 11).toByte() }
        val bobSeed = ByteArray(32) { (it + 55).toByte() }

        val aliceEdPriv = Ed25519PrivateKeyParameters(aliceSeed, 0)
        val aliceEdPub = aliceEdPriv.generatePublicKey()
        val aliceXPriv = X25519PrivateKeyParameters(MessageDigest.getInstance("SHA-256").digest(aliceSeed), 0)
        val alicePub64 = aliceXPriv.generatePublicKey().encoded + aliceEdPub.encoded

        val bobEdPriv = Ed25519PrivateKeyParameters(bobSeed, 0)
        val bobEdPub = bobEdPriv.generatePublicKey()
        val bobXPriv = X25519PrivateKeyParameters(MessageDigest.getInstance("SHA-256").digest(bobSeed), 0)
        val bobPub64 = bobXPriv.generatePublicKey().encoded + bobEdPub.encoded

        val sha = MessageDigest.getInstance("SHA-256")
        val aliceIdent16 = sha.digest(alicePub64).copyOf(16)
        val bobIdent16 = sha.digest(bobPub64).copyOf(16)
        val aliceDestHex = ReticulumBridge.bytesToHex(aliceIdent16)
        val bobDestHex = ReticulumBridge.bytesToHex(bobIdent16)

        // 1. RNS Link handshake
        val link = RnsLinkSession(bobDestHex, bobPub64)
        val reqPkt = link.buildLinkRequestPacket()
        val initEphXPub = reqPkt.copyOfRange(19, 51)
        val bobLinkXPriv = X25519PrivateKeyParameters(ByteArray(32) { 0x77 }, 0)
        val bobLinkXPub = bobLinkXPriv.generatePublicKey().encoded
        val bobSig3 = RnsCrypto.encodeSignalling(32768, 1)
        val proofSigned = link.linkIdBytes + bobLinkXPub + bobEdPub.encoded + bobSig3
        val proofSig = Ed25519Signer().run {
            init(true, bobEdPriv)
            update(proofSigned, 0, proofSigned.size)
            generateSignature()
        }
        val rttPkt = link.handleLinkProof(proofSig + bobLinkXPub + bobSig3)
        assertNotNull(rttPkt)
        assertEquals(RnsLinkSession.State.ACTIVE, link.state)
        assertEquals(32768, link.negotiatedMtu)

        // 2. LXMF message pack -> propagation deposit -> unpack -> verify
        val lxmfWire = LxmfCodec.pack(
            destinationHash = bobDestHex,
            sourceHash = aliceDestHex,
            edPriv = aliceEdPriv,
            content = """{"hello":"xali"}""".toByteArray(Charsets.UTF_8),
        )
        val deposit = LxmfCodec.packPropagationDeposit(
            lxmfWire = lxmfWire,
            recipientX25519Pub32 = bobXPriv.generatePublicKey().encoded,
            recipientIdentityHash16 = bobIdent16,
        )
        val decodedDeposit = MsgPack.decode(deposit).first as List<*>
        val storedList = decodedDeposit[1] as List<*>
        val storedEncrypted = storedList[0] as ByteArray

        val recoveredWire = LxmfCodec.unpackPropagationMessage(storedEncrypted, bobXPriv, bobIdent16)
        assertNotNull(recoveredWire)
        val recoveredMsg = LxmfCodec.unpack(recoveredWire, aliceEdPub.encoded)
        assertNotNull(recoveredMsg)
        assertTrue(recoveredMsg.signatureValid)
        assertEquals("""{"hello":"xali"}""", String(recoveredMsg.content, Charsets.UTF_8))

        // 3. LXMF PoW Stamp
        val material = sha.digest(lxmfWire)
        val stamp = LxmfStamper.generateStamp(material, targetCost = 4, expandRounds = 8)
        assertNotNull(stamp)
        assertTrue(LxmfStamper.verifyStamp(stamp, material, targetCost = 4, expandRounds = 8))
    }
}
