package app.pulse4.android.reticulum

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
        receiverCoord.registerPendingResponse(requestId)

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

    @Test
    fun multiSegmentResourceOver1MbCompletesAcrossCoordinator() {
        val link = createActiveLink(mtu = 32768)
        val senderCoord = LinkResourceCoordinator()
        val receiverCoord = LinkResourceCoordinator()
        val payload = Random(31415).nextBytes(1_200_000)

        val toPeer = ArrayDeque<ByteArray>()
        val toSender = ArrayDeque<ByteArray>()

        val firstSegHash = senderCoord.startOutbound(link, payload, "evt-multi-1") { pkt ->
            toPeer.addLast(pkt)
            true
        }
        assertNotNull(firstSegHash)

        var proofResult: LinkResourceCoordinator.ResourceProofResult? = null
        var inboundResult: LinkResourceCoordinator.ResourceInboundResult? = null
        var steps = 0
        while ((toPeer.isNotEmpty() || toSender.isNotEmpty()) && steps < 2000) {
            steps++
            while (toPeer.isNotEmpty()) {
                val pkt = toPeer.removeFirst()
                val (_, ctxPay) = packetHeader(pkt)
                val (ctx, body) = ctxPay
                val (consumed, completed) = receiverCoord.handleLinkData(
                    link, ctx, body,
                    packetHash = RnsCrypto.packetHash(pkt),
                    sendPacket = { reply ->
                        toSender.addLast(reply)
                        true
                    },
                )
                assertTrue(consumed)
                if (completed != null) inboundResult = completed
            }
            while (toSender.isNotEmpty()) {
                val pkt = toSender.removeFirst()
                val (pt, ctxPay) = packetHeader(pkt)
                val (ctx, body) = ctxPay
                if (pt == RnsPacketType.PROOF) {
                    val res = senderCoord.handleProof(ctx, body) { nextPkt ->
                        toPeer.addLast(nextPkt)
                        true
                    }
                    if (res != null) proofResult = res
                } else {
                    val (consumed, _) = senderCoord.handleLinkData(
                        link, ctx, body,
                        packetHash = RnsCrypto.packetHash(pkt),
                        sendPacket = { reply ->
                            toPeer.addLast(reply)
                            true
                        },
                    )
                    assertTrue(consumed)
                }
            }
        }

        assertNotNull(inboundResult)
        assertContentEquals(payload, inboundResult.data)
        assertNotNull(proofResult)
        assertEquals("evt-multi-1", proofResult.eventId)
        assertEquals(0, senderCoord.activeOutboundCount())
        assertEquals(0, receiverCoord.activeInboundCount())
    }

    @Test
    fun coordinatorHardeningRejectsUnmatchedResponseCapsDirectInboundAndIgnoresDuplicateAdv() {
        val link = createActiveLink(mtu = 500)
        val coord = LinkResourceCoordinator()
        val reqId1 = ByteArray(16) { 0x10 }
        val reqId2 = ByteArray(16) { 0x20 }
        val sentReplies = mutableListOf<ByteArray>()

        // 1. Response ADV with NO pending request registered -> rejected (null != null)
        val respSender = RnsResourceSender(link, Random(1).nextBytes(1024), autoCompress = false, requestId = reqId1, isResponse = true)
        val advBody1 = packetHeader(respSender.buildAdvertisementPacket()!!).second.second
        coord.handleLinkData(link, RnsContext.RESOURCE_ADV, advBody1) { sentReplies.add(it); true }
        assertEquals(0, coord.activeInboundCount())
        assertEquals(RnsContext.RESOURCE_RCL, packetHeader(sentReplies.last()).second.first)

        // 2. Response ADV with mismatched requestId -> rejected
        coord.registerPendingResponse(reqId2)
        sentReplies.clear()
        coord.handleLinkData(link, RnsContext.RESOURCE_ADV, advBody1) { sentReplies.add(it); true }
        assertEquals(0, coord.activeInboundCount())
        assertEquals(RnsContext.RESOURCE_RCL, packetHeader(sentReplies.last()).second.first)

        // 3. Matching requestId -> accepted, and duplicate ADV ignored without rejecting
        coord.registerPendingResponse(reqId1)
        sentReplies.clear()
        coord.handleLinkData(link, RnsContext.RESOURCE_ADV, advBody1) { sentReplies.add(it); true }
        assertEquals(1, coord.activeInboundCount())
        assertEquals(RnsContext.RESOURCE_REQ, packetHeader(sentReplies.last()).second.first)
        sentReplies.clear()
        coord.handleLinkData(link, RnsContext.RESOURCE_ADV, advBody1) { sentReplies.add(it); true }
        assertTrue(sentReplies.isEmpty(), "Duplicate ADV must be ignored")
        assertEquals(1, coord.activeInboundCount())
        coord.clear()

        // 4. Cap concurrent direct inbound Resources at MAX_CONCURRENT_DIRECT_INBOUND (2)
        repeat(2) { idx ->
            val s = RnsResourceSender(link, Random(10 + idx).nextBytes(1024), autoCompress = false)
            val b = packetHeader(s.buildAdvertisementPacket()!!).second.second
            coord.handleLinkData(link, RnsContext.RESOURCE_ADV, b) { true }
        }
        assertEquals(LinkResourceCoordinator.MAX_CONCURRENT_DIRECT_INBOUND, coord.activeInboundCount())

        val third = RnsResourceSender(link, Random(99).nextBytes(1024), autoCompress = false)
        val thirdBody = packetHeader(third.buildAdvertisementPacket()!!).second.second
        sentReplies.clear()
        coord.handleLinkData(link, RnsContext.RESOURCE_ADV, thirdBody) { sentReplies.add(it); true }
        assertEquals(LinkResourceCoordinator.MAX_CONCURRENT_DIRECT_INBOUND, coord.activeInboundCount())
        assertEquals(RnsContext.RESOURCE_RCL, packetHeader(sentReplies.last()).second.first)
    }

    @Test
    fun coordinatorWatchdogCleansUpTimedOutOutboundResource() {
        val link = createActiveLink(mtu = 500)
        val coord = LinkResourceCoordinator()
        val pkts = mutableListOf<ByteArray>()
        assertNotNull(coord.startOutbound(link, Random(5).nextBytes(1024), "evt-wd-1") { pkts.add(it); true })
        assertEquals(1, coord.activeOutboundCount())

        var now = System.currentTimeMillis()
        var failures = emptyList<LinkResourceCoordinator.ResourceFailedResult>()
        for (step in 0 until RnsResourceConstants.MAX_ADV_RETRIES + 2) {
            now += 20_000L
            failures = coord.tickWatchdog(now) { pkts.add(it); true }
            if (failures.isNotEmpty()) break
        }
        assertEquals(1, failures.size)
        assertEquals("evt-wd-1", failures.first().eventId)
        assertEquals(0, coord.activeOutboundCount())
    }

    @Test
    fun resourceRclEmitsRejectedByNodeFailureAndClearEmitsFailedForInFlight() {
        val link = createActiveLink(mtu = 500)
        val coord = LinkResourceCoordinator()
        val pkts = mutableListOf<ByteArray>()
        val resHashHex = coord.startOutbound(link, Random(7).nextBytes(2048), "evt-rcl-1") { pkts.add(it); true }
        assertNotNull(resHashHex)
        assertEquals(1, coord.activeOutboundCount())

        val advPlain = link.decryptLinkPayload(packetHeader(pkts.first()).second.second)!!
        val rclBody = packetHeader(RnsResourceReceiver.buildRejectPacket(link, advPlain)!!).second.second
        val failures = mutableListOf<LinkResourceCoordinator.ResourceFailedResult>()
        val (consumed, _) = coord.handleLinkData(
            link = link,
            contextByte = RnsContext.RESOURCE_RCL,
            rawPayload = rclBody,
            onFailure = { failures.add(it) },
        ) { true }
        assertTrue(consumed)
        assertEquals(0, coord.activeOutboundCount())
        assertEquals(1, failures.size)
        assertEquals("evt-rcl-1", failures.first().eventId)
        assertEquals("REJECTED_BY_NODE", failures.first().reason)

        // Now verify clear() emits FAILED for in-flight outbound + inbound responses
        assertNotNull(coord.startOutbound(link, Random(8).nextBytes(2048), "evt-clear-1") { true })
        val reqId = ByteArray(16) { 0x42 }
        coord.registerPendingResponse(reqId)
        val respSender = RnsResourceSender(link, Random(9).nextBytes(2048), autoCompress = false, requestId = reqId, isResponse = true)
        val respAdvBody = packetHeader(respSender.buildAdvertisementPacket()!!).second.second
        coord.handleLinkData(link, RnsContext.RESOURCE_ADV, respAdvBody) { true }
        assertEquals(1, coord.activeOutboundCount())
        assertEquals(1, coord.activeInboundCount())

        val cancelled = coord.clear("LINK_TEARDOWN")
        assertEquals(0, coord.activeOutboundCount())
        assertEquals(0, coord.activeInboundCount())
        assertTrue(cancelled.any { it.eventId == "evt-clear-1" && it.reason == "LINK_TEARDOWN" })
        assertTrue(cancelled.any { it.isResponse && it.reason == "LINK_TEARDOWN" })
    }

    @Test
    fun partialMultiSegmentInboundCountsTowardConcurrencyCapAndExpiresAfter120sTtl() {
        val link = createActiveLink(mtu = 32768)
        val coord = LinkResourceCoordinator()
        val t0 = 1_000_000L

        fun completeFirstSegmentOnly(seed: Int) {
            val sender = RnsResourceSender(link, Random(seed).nextBytes(1_200_000), autoCompress = false)
            assertTrue(sender.split)
            val advBody = packetHeader(sender.buildAdvertisementPacket(t0)!!).second.second
            val toSender = ArrayDeque<ByteArray>()
            coord.handleLinkData(link, RnsContext.RESOURCE_ADV, advBody, nowMs = t0) { toSender.addLast(it); true }
            while (toSender.isNotEmpty()) {
                val pkt = toSender.removeFirst()
                val (ctx, body) = packetHeader(pkt).second
                if (ctx == RnsContext.RESOURCE_REQ) {
                    val dec = link.decryptLinkPayload(body)!!
                    sender.handleRequest(dec).forEach { partPkt ->
                        val (pCtx, pBody) = packetHeader(partPkt).second
                        coord.handleLinkData(link, pCtx, pBody, nowMs = t0) { reply ->
                            if (packetHeader(reply).first == RnsPacketType.DATA) toSender.addLast(reply)
                            true
                        }
                    }
                }
            }
        }

        // Complete segment 1 for two direct multi-segment transfers -> both sit in partialMultiSegmentInbound
        completeFirstSegmentOnly(101)
        completeFirstSegmentOnly(102)
        assertEquals(0, coord.activeInboundCount())
        assertEquals(2, coord.partialMultiSegmentCountForTest())

        // 3rd direct inbound at t0 + 10s must be rejected because 2 partials count toward MAX_CONCURRENT_DIRECT_INBOUND
        val third = RnsResourceSender(link, Random(103).nextBytes(1024), autoCompress = false)
        val thirdBody = packetHeader(third.buildAdvertisementPacket(t0 + 10_000L)!!).second.second
        val replies = mutableListOf<ByteArray>()
        coord.handleLinkData(link, RnsContext.RESOURCE_ADV, thirdBody, nowMs = t0 + 10_000L) {
            replies.add(it); true
        }
        assertEquals(0, coord.activeInboundCount())
        assertEquals(RnsContext.RESOURCE_RCL, packetHeader(replies.last()).second.first)

        // Advance past 120s TTL -> watchdog evicts stale partial buffers
        coord.tickWatchdog(t0 + LinkResourceCoordinator.PARTIAL_MULTI_SEGMENT_TTL_MS) { true }
        assertEquals(0, coord.partialMultiSegmentCountForTest())

        // Now a new direct inbound transfer is accepted
        replies.clear()
        coord.handleLinkData(link, RnsContext.RESOURCE_ADV, thirdBody, nowMs = t0 + LinkResourceCoordinator.PARTIAL_MULTI_SEGMENT_TTL_MS) {
            replies.add(it); true
        }
        assertEquals(1, coord.activeInboundCount())
        assertEquals(RnsContext.RESOURCE_REQ, packetHeader(replies.last()).second.first)
    }
}
