package app.pulse4.android.reticulum

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

class RnsResourceTest {

    private fun createActiveLink(mtu: Int = 500): RnsLinkSession {
        val peerEdPriv = Ed25519PrivateKeyParameters(ByteArray(32) { 0x11 }, 0)
        val peerEdPub = peerEdPriv.generatePublicKey().encoded
        val peerXPriv = X25519PrivateKeyParameters(ByteArray(32) { 0x22 }, 0)
        val peerXPub = peerXPriv.generatePublicKey().encoded
        val link = RnsLinkSession("0102030405060708090a0b0c0d0e0f10", peerXPub + peerEdPub, offeredMtu = mtu)
        val sig3 = RnsCrypto.encodeSignalling(mtu, RnsLinkMode.AES256_CBC)
        val signed = link.linkIdBytes + peerXPub + peerEdPub + sig3
        val sig64 = Ed25519Signer().apply { init(true, peerEdPriv); update(signed, 0, signed.size) }.generateSignature()
        assertNotNull(link.handleLinkProof(sig64 + peerXPub + sig3))
        assertEquals(RnsLinkSession.State.ACTIVE, link.state)
        return link
    }

    private fun extractPayload(pkt: ByteArray): Pair<Byte, ByteArray> {
        val context = pkt[18]
        val payload = pkt.copyOfRange(19, pkt.size)
        return context to payload
    }

    private fun runTransfer(
        link: RnsLinkSession,
        sender: RnsResourceSender,
        maxSteps: Int = 500,
    ): RnsResourceReceiver {
        val advPkt = sender.buildAdvertisementPacket()
        assertNotNull(advPkt)
        assertEquals(RnsResourceStatus.ADVERTISED, sender.status)
        val (advCtx, advEnc) = extractPayload(advPkt)
        assertEquals(RnsContext.RESOURCE_ADV, advCtx)
        val advPlain = link.decryptLinkPayload(advEnc)
        assertNotNull(advPlain)

        val accepted = RnsResourceReceiver.accept(link, advPlain)
        assertNotNull(accepted)
        val (receiver, initialReq) = accepted

        val queue = ArrayDeque<ByteArray>()
        queue.addLast(initialReq)

        var steps = 0
        while (queue.isNotEmpty() && steps < maxSteps) {
            steps++
            val pkt = queue.removeFirst()
            val pt = pkt[0].toInt() and 0x03
            val (ctx, body) = extractPayload(pkt)

            when (ctx) {
                RnsContext.RESOURCE_REQ -> {
                    val dec = link.decryptLinkPayload(body)!!
                    sender.handleRequest(dec).forEach { queue.addLast(it) }
                }
                RnsContext.RESOURCE -> {
                    assertEquals(RnsPacketType.DATA, pt)
                    receiver.handlePart(body).forEach { queue.addLast(it) }
                }
                RnsContext.RESOURCE_HMU -> {
                    val dec = link.decryptLinkPayload(body)!!
                    receiver.handleHashmapUpdate(dec).forEach { queue.addLast(it) }
                }
                RnsContext.RESOURCE_PRF -> {
                    assertEquals(RnsPacketType.PROOF, pt)
                    assertTrue(sender.handleProof(body))
                }
                else -> error("Unexpected context: $ctx")
            }
        }
        assertTrue(steps < maxSteps, "Transfer exceeded $maxSteps steps")
        return receiver
    }

    @Test
    fun `golden vector matches Python RNS 1_6_0 ResourceAdvertisement and hashes`() {
        val data = "Pulse4 RNS Resource Golden Payload ".repeat(100).toByteArray(Charsets.UTF_8)
        assertEquals(3500, data.size)

        val randHash = RnsCrypto.hexToBytes("01020304")
        val resHash = MessageDigest.getInstance("SHA-256").digest(data + randHash)
        val expProof = MessageDigest.getInstance("SHA-256").digest(data + resHash)
        val mapHash = rnsResourceMapHash("part0_payload_bytes".toByteArray(Charsets.UTF_8), randHash)

        assertEquals("cf7ac5d663a1606063e7a26e6c45bf8fc35c6f49fa3b990497976a679f92d19a", RnsCrypto.bytesToHex(resHash))
        assertEquals("f43432f8c90e30c60784f31b616b9a031d3ba7e35c2568400bd92b2ee8cb8372", RnsCrypto.bytesToHex(expProof))
        assertEquals("e77409c2", RnsCrypto.bytesToHex(mapHash))

        val pyAdvHex = "8ba174cd04d2a164cd0daca16e03a168c420cf7ac5d663a1606063e7a26e6c45bf8fc35c6f49fa3b990497976a679f92d19aa172c40401020304a16fc420cf7ac5d663a1606063e7a26e6c45bf8fc35c6f49fa3b990497976a679f92d19aa16901a16c01a171c0a16603a16dc40ce77409c2e77409c2e77409c2"
        val adv = RnsResourceAdvertisement.unpack(RnsCrypto.hexToBytes(pyAdvHex))
        assertNotNull(adv)
        assertEquals(1234L, adv.transferSize)
        assertEquals(3500L, adv.dataSize)
        assertEquals(3, adv.totalParts)
        assertTrue(adv.encrypted)
        assertTrue(adv.compressed)
        assertFalse(adv.split)
        assertNull(adv.requestId)
        assertContentEquals(resHash, adv.hash)
        assertContentEquals(randHash, adv.randomHash)
        assertContentEquals(mapHash + mapHash + mapHash, adv.hashmap)
        assertEquals(pyAdvHex, RnsCrypto.bytesToHex(adv.pack()))
    }

    @Test
    fun `bz2 compression round-trip and maxLength guard`() {
        val data = "Pulse4 RNS Resource Golden Payload ".repeat(100).toByteArray(Charsets.UTF_8)
        val compressed = RnsResourceBz2.compress(data)
        assertTrue(compressed.size < data.size)
        val decompressed = RnsResourceBz2.decompress(compressed)
        assertNotNull(decompressed)
        assertContentEquals(data, decompressed)

        // Zip-bomb guard
        assertNull(RnsResourceBz2.decompress(compressed, maxLength = 100))
    }

    @Test
    fun `compressed resource transfer with metadata round-trips end-to-end`() {
        val link = createActiveLink(mtu = 500)
        val payload = "Pulse4 compressed resource payload! ".repeat(200).toByteArray(Charsets.UTF_8)
        val metadata = mapOf("filename" to "sync.bin", "v" to 1)
        val sender = RnsResourceSender(link, payload, metadata = metadata, autoCompress = true)
        assertTrue(sender.compressed)
        assertTrue(sender.hasMetadata)

        val receiver = runTransfer(link, sender)
        assertEquals(RnsResourceStatus.COMPLETE, sender.status)
        assertEquals(RnsResourceStatus.COMPLETE, receiver.status)
        assertContentEquals(payload, receiver.assembledData)
        val decMeta = receiver.assembledMetadata as? Map<*, *>
        assertNotNull(decMeta)
        assertEquals("sync.bin", decMeta["filename"])
    }

    @Test
    fun `uncompressed multi-HMU resource transfer over 500B MTU exercises HASHMAP_IS_EXHAUSTED`() {
        val link = createActiveLink(mtu = 500)
        // 48 KB incompressible random data -> ~104 parts (> HASHMAP_MAX_LEN=74) at sdu=464
        val payload = Random(42).nextBytes(48 * 1024)
        val sender = RnsResourceSender(link, payload, autoCompress = true)
        assertFalse(sender.compressed)
        assertTrue(sender.totalParts > RnsResourceConstants.HASHMAP_MAX_LEN)

        val receiver = runTransfer(link, sender)
        assertEquals(RnsResourceStatus.COMPLETE, sender.status)
        assertEquals(RnsResourceStatus.COMPLETE, receiver.status)
        assertContentEquals(payload, receiver.assembledData)
    }

    @Test
    fun `large resource over 32KB MTU transfers cleanly`() {
        val link = createActiveLink(mtu = 32768)
        val payload = Random(99).nextBytes(128 * 1024)
        val sender = RnsResourceSender(link, payload, autoCompress = false)
        assertEquals(32732, sender.sdu)
        assertTrue(sender.totalParts in 4..6)

        val receiver = runTransfer(link, sender)
        assertEquals(RnsResourceStatus.COMPLETE, sender.status)
        assertEquals(RnsResourceStatus.COMPLETE, receiver.status)
        assertContentEquals(payload, receiver.assembledData)
    }

    @Test
    fun `reject and cancel transitions`() {
        val link = createActiveLink(mtu = 500)
        val sender = RnsResourceSender(link, ByteArray(1024) { 0x5A }, autoCompress = false)
        val advPkt = sender.buildAdvertisementPacket()!!
        val advPlain = link.decryptLinkPayload(extractPayload(advPkt).second)!!
        val rejPkt = RnsResourceReceiver.buildRejectPacket(link, advPlain)!!
        val (rejCtx, rejEnc) = extractPayload(rejPkt)
        assertEquals(RnsContext.RESOURCE_RCL, rejCtx)
        assertContentEquals(sender.hash, link.decryptLinkPayload(rejEnc))
        sender.handleReject()
        assertEquals(RnsResourceStatus.REJECTED, sender.status)
    }

    @Test
    fun `propagation announce appData prefers ceil of list_7_0 fractional stamp cost and link tracks lastInboundMs`() {
        val appData = MsgPack.encode(
            listOf(
                false,
                1700000000L,
                true,
                256L,
                1024L,
                listOf(16, 3, 18),
                emptyMap<Any?, Any?>(),
                listOf(18.2, 2, 16),
            ),
        )
        val info = LxmfCodec.parsePropagationAnnounceAppData(appData)
        assertNotNull(info)
        assertEquals(19, info.stampCost)
        assertEquals(2, info.stampCostFlexibility)
        assertEquals(16, info.peeringCost)

        val link = createActiveLink(mtu = 500)
        val initialInbound = link.lastInboundMs
        Thread.sleep(5)
        assertNotNull(link.buildKeepalivePacket())
        assertEquals(initialInbound, link.lastInboundMs)
        Thread.sleep(5)
        link.touchInbound()
        assertTrue(link.lastInboundMs > initialInbound)
    }
}
