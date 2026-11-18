package app.pulse4.android.reticulum

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

class LinkResourceCoordinatorTest {

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

    private fun packetHeader(pkt: ByteArray): Pair<Int, Pair<Byte, ByteArray>> {
        val pt = pkt[0].toInt() and 0x03
        val ctx = pkt[18]
        val payload = pkt.copyOfRange(19, pkt.size)
        return pt to (ctx to payload)
    }

    @Test
    fun outboundResourceOverMduCompletesAndEmitsProofResult() {
        val link = createActiveLink(mtu = 500)
        val senderCoord = LinkResourceCoordinator()
        val receiverCoord = LinkResourceCoordinator()

        val payload = Random(42).nextBytes(4096) // > 500B MDU -> multi-part Resource
        assertTrue(payload.size > link.mdu)

        val toPeer = ArrayDeque<ByteArray>()
        val toSender = ArrayDeque<ByteArray>()

        val hashHex = senderCoord.startOutbound(link, payload, "evt-res-1") { pkt ->
            toPeer.addLast(pkt)
            true
        }
        assertNotNull(hashHex)
        assertEquals(1, senderCoord.activeOutboundCount())

        var proofResult: LinkResourceCoordinator.ResourceProofResult? = null
        var inboundResult: LinkResourceCoordinator.ResourceInboundResult? = null

        var steps = 0
        while ((toPeer.isNotEmpty() || toSender.isNotEmpty()) && steps < 500) {
            steps++
            while (toPeer.isNotEmpty()) {
                val pkt = toPeer.removeFirst()
                val (pt, ctxPay) = packetHeader(pkt)
                val (ctx, body) = ctxPay
                assertEquals(RnsPacketType.DATA, pt)
                val (consumed, completed) = receiverCoord.handleLinkData(link, ctx, body) { reply ->
                    toSender.addLast(reply)
                    true
                }
                assertTrue(consumed)
                if (completed != null) inboundResult = completed
            }
            while (toSender.isNotEmpty()) {
                val pkt = toSender.removeFirst()
                val (pt, ctxPay) = packetHeader(pkt)
                val (ctx, body) = ctxPay
                if (pt == RnsPacketType.PROOF) {
                    val res = senderCoord.handleProof(ctx, body)
                    if (res != null) proofResult = res
                } else {
                    val (consumed, _) = senderCoord.handleLinkData(link, ctx, body) { reply ->
                        toPeer.addLast(reply)
                        true
                    }
                    assertTrue(consumed)
                }
            }
        }

        assertNotNull(inboundResult)
        assertContentEquals(payload, inboundResult.data)
        assertNotNull(proofResult)
        assertEquals("evt-res-1", proofResult.eventId)
        assertEquals(hashHex, proofResult.resourceHashHex)
        assertEquals(0, senderCoord.activeOutboundCount())
        assertEquals(0, receiverCoord.activeInboundCount())
    }

    @Test
    fun inboundGetResponseResourceOverMduAssemblesResponse() {
        val link = createActiveLink(mtu = 500)
        val receiverCoord = LinkResourceCoordinator()
        val requestId = ByteArray(16) { (it + 1).toByte() }
        val msg1 = Random(1).nextBytes(700)
        val msg2 = Random(2).nextBytes(700)
        val packedResponse = MsgPack.encode(listOf(requestId, listOf(msg1, msg2)))
        assertTrue(packedResponse.size > link.mdu)

        val remoteSender = RnsResourceSender(
            link = link,
            data = packedResponse,
            autoCompress = false,
            requestId = requestId,
            isResponse = true,
        )

        val toReceiver = ArrayDeque<ByteArray>()
        val toRemote = ArrayDeque<ByteArray>()
        toReceiver.addLast(remoteSender.buildAdvertisementPacket()!!)

        var completed: LinkResourceCoordinator.ResourceInboundResult? = null
        var steps = 0
        while ((toReceiver.isNotEmpty() || toRemote.isNotEmpty()) && steps < 500) {
            steps++
            while (toReceiver.isNotEmpty()) {
                val pkt = toReceiver.removeFirst()
                val (_, ctxPay) = packetHeader(pkt)
                val (ctx, body) = ctxPay
                val (consumed, res) = receiverCoord.handleLinkData(link, ctx, body) { reply ->
                    toRemote.addLast(reply)
                    true
                }
                assertTrue(consumed)
                if (res != null) completed = res
            }
            while (toRemote.isNotEmpty()) {
                val pkt = toRemote.removeFirst()
                val (pt, ctxPay) = packetHeader(pkt)
                val (ctx, body) = ctxPay
                if (pt == RnsPacketType.PROOF && ctx == RnsContext.RESOURCE_PRF) {
                    assertTrue(remoteSender.handleProof(body))
                } else if (ctx == RnsContext.RESOURCE_REQ) {
                    val dec = link.decryptLinkPayload(body)!!
                    remoteSender.handleRequest(dec).forEach { toReceiver.addLast(it) }
                }
            }
        }

        assertNotNull(completed)
        assertTrue(completed.isResponse)
        assertContentEquals(requestId, completed.requestId)
        assertContentEquals(packedResponse, completed.data)
        assertEquals(RnsResourceStatus.COMPLETE, remoteSender.status)
    }

    @Test
    fun deriveDeliveryHashesProducesDistinctLxmfAndLegacyAddresses() {
        val pub64 = ByteArray(64) { (it + 7).toByte() }
        val pubB64 = java.util.Base64.getEncoder().encodeToString(pub64)
        val (lxmfHex, legacyHex) = ReticulumBridge.deriveDeliveryHashes(pubB64)
        assertEquals(32, lxmfHex.length)
        assertEquals(32, legacyHex.length)
        kotlin.test.assertNotEquals(lxmfHex, legacyHex)
        assertEquals(lxmfHex, ReticulumBridge.derivePrimaryDeliveryHash(pubB64))
    }
}
