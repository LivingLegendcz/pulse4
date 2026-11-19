package interop

import app.pulse4.android.reticulum.LxmfCodec
import app.pulse4.android.reticulum.LxmfStamper
import app.pulse4.android.reticulum.MsgPack
import app.pulse4.android.reticulum.RnsAnnounceParser
import app.pulse4.android.reticulum.RnsContext
import app.pulse4.android.reticulum.RnsCrypto
import app.pulse4.android.reticulum.RnsHeaderType
import app.pulse4.android.reticulum.RnsLinkSession
import app.pulse4.android.reticulum.RnsPacketType
import app.pulse4.android.reticulum.RnsResourceConstants
import app.pulse4.android.reticulum.RnsResourceReceiver
import app.pulse4.android.reticulum.RnsResourceSender
import app.pulse4.android.reticulum.RnsResourceStatus
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

fun main(args: Array<String>) {
    val port = args[0].toInt()
    val seedHex = args[1]
    val propDestHex = args[2]
    val alicePubHex = args[3]
    val aliceDestHex = args[4]
    val pyToKtTid = args[5]
    val stampCost = args[6].toDouble().toInt()
    val stampFlex = args[7].toDouble().toInt()
    val keepaliveSecs = args.getOrNull(8)?.toIntOrNull() ?: 50
    val bobPubHex = args[9]
    val bobDestHex = args[10]
    val restrictedPropDestHex = args[11]
    val pyLargeKtTid = args[12]

    val seed = RnsCrypto.hexToBytes(seedHex)
    val edPriv = Ed25519PrivateKeyParameters(seed, 0)
    val edPub = edPriv.generatePublicKey().encoded
    val xSeed = MessageDigest.getInstance("SHA-256").digest(seed)
    val xPriv = X25519PrivateKeyParameters(xSeed, 0)
    val xPub = xPriv.generatePublicKey().encoded
    val pub64 = xPub + edPub
    val sha = MessageDigest.getInstance("SHA-256")
    val idHash = sha.digest(pub64).copyOf(16)
    val lxmfNameHash = sha.digest(LxmfCodec.DELIVERY_APP_NAME.toByteArray(Charsets.UTF_8)).copyOf(10)
    val selfDest = sha.digest(lxmfNameHash + idHash).copyOf(16)
    val selfDestHex = RnsCrypto.bytesToHex(selfDest)

    val alicePub = RnsCrypto.hexToBytes(alicePubHex)
    val aliceXPub = alicePub.copyOfRange(0, 32)
    val aliceEdPub = alicePub.copyOfRange(32, 64)
    val aliceIdHash = sha.digest(alicePub).copyOf(16)

    val sock = Socket()
    sock.connect(InetSocketAddress("127.0.0.1", port), 5000)
    sock.soTimeout = 500
    val out = sock.getOutputStream()
    val inp = sock.getInputStream()

    fun sendPkt(raw: ByteArray) {
        val framed = byteArrayOf(RnsCrypto.HDLC_FLAG) + RnsCrypto.hdlcEscape(raw) + byteArrayOf(RnsCrypto.HDLC_FLAG)
        out.write(framed)
        out.flush()
    }

    val frameBuf = ByteArrayOutputStream()
    val readBuf = ByteArray(4096)
    var readPos = 0
    var readLen = 0
    var inFrame = false
    fun pollPacket(timeoutMs: Long): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            while (readPos < readLen) {
                val b = readBuf[readPos++]
                if (b == RnsCrypto.HDLC_FLAG) {
                    if (inFrame && frameBuf.size() > 0) {
                        val pkt = RnsCrypto.hdlcUnescape(frameBuf.toByteArray())
                        frameBuf.reset()
                        return pkt
                    }
                    inFrame = true
                    frameBuf.reset()
                } else if (inFrame) {
                    frameBuf.write(b.toInt())
                }
            }
            if (System.currentTimeMillis() >= deadline) break
            try {
                readPos = 0
                readLen = inp.read(readBuf)
                if (readLen <= 0) {
                    readLen = 0
                }
            } catch (_: java.net.SocketTimeoutException) {
            }
        }
        return null
    }

    fun awaitAnnounce(targetDestHex: String, timeoutMs: Long = 8000): Pair<ByteArray, ByteArray?> {
        sendPkt(RnsCrypto.buildPathRequestPacket(RnsCrypto.hexToBytes(targetDestHex)))
        var pub64Found: ByteArray? = null
        var transportId: ByteArray? = null
        val dl = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < dl && pub64Found == null) {
            val pkt = pollPacket(300) ?: continue
            val parsed = RnsAnnounceParser.parse(pkt)
            if (parsed != null && parsed.verified && parsed.destinationHash == targetDestHex) {
                pub64Found = parsed.publicKey64
                if (((pkt[0].toInt() and 0xFF) and 0x40) != 0) {
                    transportId = pkt.copyOfRange(2, 18)
                }
            }
        }
        checkNotNull(pub64Found) { "Did not receive announce for $targetDestHex" }
        return pub64Found to transportId
    }

    fun establishLink(destHex: String, pubKey64: ByteArray, transportId: ByteArray?): RnsLinkSession {
        val link = RnsLinkSession(destHex, pubKey64)
        sendPkt(link.buildLinkRequestPacket(transportId))
        val dl = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < dl && link.state != RnsLinkSession.State.ACTIVE) {
            val pkt = pollPacket(200) ?: continue
            val flags = pkt[0].toInt() and 0xFF
            val pt = flags and 0x03
            val h2 = ((flags and 0x40) shr 6) == RnsHeaderType.HEADER_2
            val destOff = if (h2) 18 else 2
            val ctxOff = if (h2) 34 else 18
            val payOff = if (h2) 35 else 19
            if (pt == RnsPacketType.PROOF && pkt.size > payOff && pkt[ctxOff] == RnsContext.LRPROOF) {
                if (RnsCrypto.bytesToHex(pkt.copyOfRange(destOff, destOff + 16)) == link.linkIdHex) {
                    link.handleLinkProof(pkt.copyOfRange(payOff, pkt.size))?.let { sendPkt(it) }
                }
            }
        }
        check(link.state == RnsLinkSession.State.ACTIVE) { "Link to $destHex did not become ACTIVE" }
        return link
    }

    fun sendGetRaw(link: RnsLinkSession, reqData: List<Any?>, timeoutMs: Long = 15000): Any? {
        val (reqId, reqPkt) = link.buildRequestPacket(LxmfCodec.MESSAGE_GET_PATH, reqData)!!
        sendPkt(reqPkt)
        val dl = System.currentTimeMillis() + timeoutMs
        var respReceiver: RnsResourceReceiver? = null
        while (System.currentTimeMillis() < dl) {
            val pkt = pollPacket(200) ?: continue
            val flags = pkt[0].toInt() and 0xFF
            val pt = flags and 0x03
            val h2 = ((flags and 0x40) shr 6) == RnsHeaderType.HEADER_2
            val destOff = if (h2) 18 else 2
            val ctxOff = if (h2) 34 else 18
            val payOff = if (h2) 35 else 19
            if (pkt.size <= payOff) continue
            if (RnsCrypto.bytesToHex(pkt.copyOfRange(destOff, destOff + 16)) != link.linkIdHex) continue

            val ctx = pkt[ctxOff]
            val body = pkt.copyOfRange(payOff, pkt.size)
            when {
                pt == RnsPacketType.DATA && ctx == RnsContext.RESPONSE -> {
                    val dec = link.decryptLinkPayload(body) ?: continue
                    val list = MsgPack.decode(dec).first as? List<*> ?: continue
                    val respId = list.getOrNull(0) as? ByteArray
                    if (respId != null && MessageDigest.isEqual(reqId, respId)) {
                        return list.getOrNull(1)
                    }
                }
                pt == RnsPacketType.DATA && ctx == RnsContext.RESOURCE_ADV -> {
                    val dec = link.decryptLinkPayload(body) ?: continue
                    val accepted = RnsResourceReceiver.accept(link, dec)
                    if (accepted != null && accepted.first.adv.isResponse) {
                        val advReqId = accepted.first.adv.requestId
                        if (advReqId != null && MessageDigest.isEqual(reqId, advReqId)) {
                            respReceiver = accepted.first
                            sendPkt(accepted.second)
                        }
                    }
                }
                pt == RnsPacketType.DATA && ctx == RnsContext.RESOURCE_HMU -> {
                    val dec = link.decryptLinkPayload(body) ?: continue
                    respReceiver?.handleHashmapUpdate(dec)?.forEach { sendPkt(it) }
                }
                pt == RnsPacketType.DATA && ctx == RnsContext.RESOURCE -> {
                    val r = respReceiver ?: continue
                    r.handlePart(body).forEach { sendPkt(it) }
                    if (r.status == RnsResourceStatus.COMPLETE) {
                        val packed = r.assembledData!!
                        val decoded = MsgPack.decode(packed).first as List<*>
                        val respId = decoded[0] as ByteArray
                        check(MessageDigest.isEqual(reqId, respId)) { "Resource response requestId mismatch" }
                        return decoded[1]
                    }
                }
            }
        }
        error("Timeout waiting for /get response")
    }

    // Wait for PN announce
    val (propPub64, transportId) = awaitAnnounce(propDestHex)
    println("[1] PN announce verified: dest=$propDestHex")

    // 1. Link request -> LRPROOF -> ACTIVE
    val link = establishLink(propDestHex, propPub64, transportId)
    println("[1] PASS: Link ACTIVE id=${link.linkIdHex} mtu=${link.negotiatedMtu} kaIntervalMs=${link.keepaliveIntervalMs}")

    // 1b. N-4: /get before LINKIDENTIFY -> ERROR_NO_IDENTITY (0xF0)
    val noIdResp = sendGetRaw(link, LxmfCodec.buildGetMessagesRequest(null, null))
    check((noIdResp as? Number)?.toInt() == LxmfCodec.ERROR_NO_IDENTITY) {
        "Expected ERROR_NO_IDENTITY (0xF0), got: $noIdResp"
    }
    println("[1b] PASS: /get before LINKIDENTIFY returned ERROR_NO_IDENTITY (0xF0)")

    // Identify on link
    sendPkt(link.buildIdentifyPacket(pub64, edPriv)!!)

    // 1c. N-4: Restricted PN -> ERROR_NO_ACCESS (0xF1)
    val (restPub64, restTid) = awaitAnnounce(restrictedPropDestHex)
    val restLink = establishLink(restrictedPropDestHex, restPub64, restTid)
    sendPkt(restLink.buildIdentifyPacket(pub64, edPriv)!!)
    val noAccessResp = sendGetRaw(restLink, LxmfCodec.buildGetMessagesRequest(null, null))
    check((noAccessResp as? Number)?.toInt() == LxmfCodec.ERROR_NO_ACCESS) {
        "Expected ERROR_NO_ACCESS (0xF1), got: $noAccessResp"
    }
    sendPkt(restLink.buildLinkClosePacket()!!)
    println("[1c] PASS: Restricted PN returned ERROR_NO_ACCESS (0xF1)")

    // 3a. Deposit Kotlin -> Python LXMF with PN stamp and await RNS PROOF
    val ktWire = LxmfCodec.pack(
        destinationHash = aliceDestHex,
        sourceHash = selfDestHex,
        edPriv = edPriv,
        content = "hello from kotlin rns-core".toByteArray(Charsets.UTF_8),
        title = "kt-title",
    )
    val enc = LxmfCodec.encryptPropagationPayload(ktWire, aliceXPub, aliceIdHash)
    val tid = LxmfStamper.transientId(enc)
    val targetCost = maxOf(0, stampCost - stampFlex)
    val stamp = LxmfStamper.generateStamp(tid, targetCost, LxmfStamper.PN_WORKBLOCK_ROUNDS)!!
    val deposit = LxmfCodec.packPropagationDepositRaw(enc + stamp)
    val depPkt = link.buildEncryptedLinkPacket(RnsContext.NONE, deposit)!!
    val depHash = RnsCrypto.packetHash(depPkt)
    sendPkt(depPkt)

    var proofVerified = false
    val proofDeadline = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < proofDeadline && !proofVerified) {
        val pkt = pollPacket(200) ?: continue
        val flags = pkt[0].toInt() and 0xFF
        val pt = flags and 0x03
        val h2 = ((flags and 0x40) shr 6) == RnsHeaderType.HEADER_2
        val ctxOff = if (h2) 34 else 18
        val payOff = if (h2) 35 else 19
        if (pt == RnsPacketType.PROOF && pkt.size >= payOff + 96 && pkt[ctxOff] == RnsContext.NONE) {
            proofVerified = link.verifyPacketProof(pkt.copyOfRange(payOff, pkt.size), depHash)
        }
    }
    check(proofVerified) { "Did not receive valid PROOF for stamped deposit" }
    println("[3a] PASS: Deposit accepted with valid PROOF (cost=$targetCost, kt->py tid=${RnsCrypto.bytesToHex(tid)})")

    // 3b. Two-phase /get: Phase 1 [null, null] -> transient IDs (includes small + large)
    val avail = (sendGetRaw(link, LxmfCodec.buildGetMessagesRequest(null, null)) as List<*>).mapNotNull { it as? ByteArray }
    check(avail.any { RnsCrypto.bytesToHex(it) == pyToKtTid }) { "Expected py_to_kt_tid $pyToKtTid in /get phase 1" }
    check(avail.any { RnsCrypto.bytesToHex(it) == pyLargeKtTid }) { "Expected py_large_kt_tid $pyLargeKtTid in /get phase 1" }
    println("[3b] PASS: /get phase 1 returned ${avail.size} transient IDs including small & 100KB large")

    // Phase 2 (small): download pyToKtTid
    val smallMsgs = (sendGetRaw(link, LxmfCodec.buildGetMessagesRequest(listOf(RnsCrypto.hexToBytes(pyToKtTid)), emptyList(), 256)) as List<*>).mapNotNull { it as? ByteArray }
    check(smallMsgs.isNotEmpty()) { "Expected small message in /get phase 2" }
    var unpackedPyMsg = false
    for (stored in smallMsgs) {
        val wire = LxmfCodec.unpackPropagationMessage(stored, xPriv, idHash) ?: continue
        val msg = LxmfCodec.unpack(wire, aliceEdPub) ?: continue
        if (msg.signatureValid && String(msg.content, Charsets.UTF_8) == "hello from python lxmf 1.3.0") {
            unpackedPyMsg = true
        }
    }
    check(unpackedPyMsg) { "Failed to decrypt/verify Python LXMF message" }
    println("[2] PASS: Python -> Kotlin LXMF message decrypted and Ed25519 signature verified")

    // Phase 2 (large > MDU): download 100KB pyLargeKtTid delivered as RNS.Resource response (N-3 KB limit = 256)
    val largeMsgs = (sendGetRaw(link, LxmfCodec.buildGetMessagesRequest(listOf(RnsCrypto.hexToBytes(pyLargeKtTid)), emptyList(), 256)) as List<*>).mapNotNull { it as? ByteArray }
    check(largeMsgs.size == 1) { "Expected 100KB message via Resource /get response, got ${largeMsgs.size}" }
    val largeWire = LxmfCodec.unpackPropagationMessage(largeMsgs.first(), xPriv, idHash)!!
    val largeMsg = LxmfCodec.unpack(largeWire, aliceEdPub)!!
    check(largeMsg.signatureValid && largeMsg.title == "py-large-get" && largeMsg.content.size == 100_000) {
        "Invalid 100KB /get Resource message: sig=${largeMsg.signatureValid} len=${largeMsg.content.size}"
    }
    println("[3b-res] PASS: /get 100KB response delivered via RNS.Resource with KB limit=256 and verified")

    // Phase 2b: purge haves
    sendGetRaw(link, LxmfCodec.buildGetMessagesRequest(emptyList(), avail, 256))
    val availAfterPurge = (sendGetRaw(link, LxmfCodec.buildGetMessagesRequest(null, null)) as List<*>).mapNotNull { it as? ByteArray }
    check(availAfterPurge.none { RnsCrypto.bytesToHex(it) == pyToKtTid || RnsCrypto.bytesToHex(it) == pyLargeKtTid }) {
        "Messages were not purged!"
    }
    println("[3c] PASS: /get purge removed both messages from PN")

    // 4. Keepalive test
    val kaDeadline = System.currentTimeMillis() + keepaliveSecs * 1000L
    var lastKa = System.currentTimeMillis()
    var closedByPeer = false
    while (System.currentTimeMillis() < kaDeadline) {
        val pkt = pollPacket(500)
        if (pkt != null) {
            val flags = pkt[0].toInt() and 0xFF
            val pt = flags and 0x03
            val h2 = ((flags and 0x40) shr 6) == RnsHeaderType.HEADER_2
            val ctxOff = if (h2) 34 else 18
            val payOff = if (h2) 35 else 19
            if (pt == RnsPacketType.DATA && pkt.size > payOff && pkt[ctxOff] == RnsContext.LINKCLOSE) {
                if (link.verifyAndHandleLinkClose(pkt.copyOfRange(payOff, pkt.size))) {
                    closedByPeer = true
                    break
                }
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastKa >= 5000L) {
            sendPkt(link.buildKeepalivePacket()!!)
            lastKa = now
        }
    }
    check(!closedByPeer) { "Peer sent LINKCLOSE during keepalive window!" }
    println("[4] PASS: Link survived ${keepaliveSecs}s idle with keepalive")

    // 4b. N-12: Resource deposit (> MDU) with invalid zero stamp -> lxmd sends RESOURCE_PRF then tears down link!
    val badStampPayload = kotlin.random.Random(9876).nextBytes(40_000)
    val badWire = LxmfCodec.pack(
        destinationHash = aliceDestHex,
        sourceHash = selfDestHex,
        edPriv = edPriv,
        content = badStampPayload,
        title = "bad-stamp-resource",
    )
    val badEnc = LxmfCodec.encryptPropagationPayload(badWire, aliceXPub, aliceIdHash)
    val badDeposit = LxmfCodec.packPropagationDepositRaw(badEnc + ByteArray(32)) // invalid all-zero stamp
    val badSender = RnsResourceSender(link, badDeposit, autoCompress = false)
    sendPkt(badSender.buildAdvertisementPacket()!!)

    var badLinkClosedAfterProof = false
    val badDeadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < badDeadline && !badLinkClosedAfterProof) {
        val pkt = pollPacket(200) ?: continue
        val flags = pkt[0].toInt() and 0xFF
        val pt = flags and 0x03
        val h2 = ((flags and 0x40) shr 6) == RnsHeaderType.HEADER_2
        val destOff = if (h2) 18 else 2
        val ctxOff = if (h2) 34 else 18
        val payOff = if (h2) 35 else 19
        if (pkt.size <= payOff) continue
        if (RnsCrypto.bytesToHex(pkt.copyOfRange(destOff, destOff + 16)) != link.linkIdHex) continue
        val ctx = pkt[ctxOff]
        val body = pkt.copyOfRange(payOff, pkt.size)
        when {
            ctx == RnsContext.RESOURCE_REQ -> {
                val dec = link.decryptLinkPayload(body) ?: continue
                badSender.handleRequest(dec).forEach { sendPkt(it) }
            }
            pt == RnsPacketType.PROOF && ctx == RnsContext.RESOURCE_PRF -> {
                badSender.handleProof(body)
            }
            ctx == RnsContext.LINKCLOSE -> {
                if (link.verifyAndHandleLinkClose(body)) {
                    badLinkClosedAfterProof = true
                }
            }
        }
    }
    check(badSender.status == RnsResourceStatus.COMPLETE && badLinkClosedAfterProof) {
        "Expected RESOURCE_PRF followed by LINKCLOSE for invalid stamp Resource deposit (status=${badSender.status}, closed=$badLinkClosedAfterProof)"
    }
    println("[4b] PASS: Invalid-stamp Resource deposit triggered RESOURCE_PRF followed by LINKCLOSE from lxmd (N-12)")

    // 5. >MDU 16KB + >1MB (1.2MB) multi-segment LXMF message end-to-end via RNS Resource on lxmf.delivery
    val bobPub = RnsCrypto.hexToBytes(bobPubHex)
    val bobEdPub = bobPub.copyOfRange(32, 64)
    val (_, bobTid) = awaitAnnounce(bobDestHex, timeoutMs = 5000)
    val bobLink = establishLink(bobDestHex, bobPub, bobTid)
    sendPkt(bobLink.buildIdentifyPacket(pub64, edPriv)!!)

    fun driveBidirectionalResource(senderInit: RnsResourceSender, expectedPyTitle: String, expectedPyLen: Int): ByteArray {
        var curSender = senderInit
        sendPkt(curSender.buildAdvertisementPacket()!!)
        var curReceiver: RnsResourceReceiver? = null
        var priorData: ByteArray? = null
        var priorMeta: Any? = null
        var finalBytes: ByteArray? = null
        val dl = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < dl &&
            !(curSender.status == RnsResourceStatus.COMPLETE && curSender.segmentIndex == curSender.totalSegments && finalBytes != null)
        ) {
            val pkt = pollPacket(200) ?: continue
            val flags = pkt[0].toInt() and 0xFF
            val pt = flags and 0x03
            val h2 = ((flags and 0x40) shr 6) == RnsHeaderType.HEADER_2
            val destOff = if (h2) 18 else 2
            val ctxOff = if (h2) 34 else 18
            val payOff = if (h2) 35 else 19
            if (pkt.size <= payOff) continue
            if (RnsCrypto.bytesToHex(pkt.copyOfRange(destOff, destOff + 16)) != bobLink.linkIdHex) continue
            val ctx = pkt[ctxOff]
            val body = pkt.copyOfRange(payOff, pkt.size)
            when (ctx) {
                RnsContext.RESOURCE_REQ -> {
                    val dec = bobLink.decryptLinkPayload(body) ?: continue
                    curSender.handleRequest(dec, RnsCrypto.packetHash(pkt)).forEach { sendPkt(it) }
                }
                RnsContext.RESOURCE_PRF -> {
                    if (pt == RnsPacketType.PROOF && curSender.handleProof(body)) {
                        if (curSender.segmentIndex < curSender.totalSegments) {
                            curSender = curSender.nextSegment()!!
                            sendPkt(curSender.buildAdvertisementPacket()!!)
                        }
                    }
                }
                RnsContext.RESOURCE_ADV -> {
                    val dec = bobLink.decryptLinkPayload(body) ?: continue
                    val accepted = RnsResourceReceiver.accept(
                        link = bobLink,
                        advPlaintext = dec,
                        maxDecompressedSize = RnsResourceConstants.DEFAULT_MAX_DECOMPRESSED_SIZE,
                        priorSegmentsData = priorData,
                        priorMetadata = priorMeta,
                    )
                    if (accepted != null) {
                        curReceiver = accepted.first
                        sendPkt(accepted.second)
                    }
                }
                RnsContext.RESOURCE_HMU -> {
                    val dec = bobLink.decryptLinkPayload(body) ?: continue
                    curReceiver?.handleHashmapUpdate(dec)?.forEach { sendPkt(it) }
                }
                RnsContext.RESOURCE -> {
                    val r = curReceiver ?: continue
                    r.handlePart(body).forEach { sendPkt(it) }
                    if (r.status == RnsResourceStatus.COMPLETE) {
                        if (r.isFinalSegment) {
                            finalBytes = r.assembledData
                        } else {
                            priorData = r.assembledData
                            priorMeta = r.assembledMetadata
                            curReceiver = null
                        }
                    }
                }
            }
        }
        check(curSender.status == RnsResourceStatus.COMPLETE && curSender.segmentIndex == curSender.totalSegments) {
            "Kotlin -> Python Resource ($expectedPyTitle) did not complete (seg=${curSender.segmentIndex}/${curSender.totalSegments}, status=${curSender.status})"
        }
        val bytes = checkNotNull(finalBytes) { "Python -> Kotlin Resource ($expectedPyTitle) did not complete" }
        val pyMsg = checkNotNull(LxmfCodec.unpack(bytes, bobEdPub)) { "Failed to unpack $expectedPyTitle" }
        check(pyMsg.signatureValid && pyMsg.title == expectedPyTitle && pyMsg.content.size == expectedPyLen) {
            "Unexpected $expectedPyTitle: title=${pyMsg.title} len=${pyMsg.content.size} sig=${pyMsg.signatureValid}"
        }
        return bytes
    }

    // 5a/5b: 16KB bidirectional Resource
    val kt16kWire = LxmfCodec.pack(
        destinationHash = bobDestHex,
        sourceHash = selfDestHex,
        edPriv = edPriv,
        content = kotlin.random.Random(12345).nextBytes(16384),
        title = "kt-16k-resource",
    )
    driveBidirectionalResource(RnsResourceSender(bobLink, kt16kWire, autoCompress = true), "py-16k-resource", 16384)
    println("[5b] PASS: Python <-> Kotlin 16KB LXMF via RNS.Resource verified")

    // 5c: 1.25 MB multi-segment (> 1 MB) bidirectional Resource
    val kt1m2Wire = LxmfCodec.pack(
        destinationHash = bobDestHex,
        sourceHash = selfDestHex,
        edPriv = edPriv,
        content = kotlin.random.Random(54321).nextBytes(1_250_000),
        title = "kt-1m2-resource",
    )
    val sender1m2 = RnsResourceSender(bobLink, kt1m2Wire, autoCompress = false)
    check(sender1m2.split && sender1m2.totalSegments == 2) { "Expected 2 segments for 1.25MB payload" }
    driveBidirectionalResource(sender1m2, "py-1m2-resource", 1_250_000)
    println("[5c] PASS: Python <-> Kotlin 1.25MB multi-segment RNS.Resource verified both ways")

    // 5d: Malformed ADV (n = 0x7fffffff, n = -1) survived without crash
    val fakeHash = ByteArray(32) { 0x11 }
    val fakeRand = ByteArray(4) { 0x22 }
    for (badN in listOf(0x7fffffffL, -1L)) {
        val badAdv = MsgPack.encode(
            linkedMapOf(
                "t" to 1024L,
                "d" to 1024L,
                "n" to badN,
                "h" to fakeHash,
                "r" to fakeRand,
                "o" to fakeHash,
                "i" to 1,
                "l" to 1,
                "q" to null,
                "f" to 0x01,
                "m" to ByteArray(4),
            ),
        )
        check(RnsResourceReceiver.accept(bobLink, badAdv) == null) { "Malformed ADV n=$badN must be rejected" }
    }
    println("[5d] PASS: Malformed ADV (n=0x7fffffff, n=-1) rejected without crash")

    sendPkt(bobLink.buildLinkClosePacket()!!)
    sock.close()
}
