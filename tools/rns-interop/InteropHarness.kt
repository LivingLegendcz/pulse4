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

    // Request path to PN so node responds with its announce
    sendPkt(RnsCrypto.buildPathRequestPacket(RnsCrypto.hexToBytes(propDestHex)))

    // Wait for PN announce
    var propPub64: ByteArray? = null
    var transportId: ByteArray? = null
    val annDeadline = System.currentTimeMillis() + 8000
    while (System.currentTimeMillis() < annDeadline && propPub64 == null) {
        val pkt = pollPacket(300) ?: continue
        val parsed = RnsAnnounceParser.parse(pkt)
        if (parsed != null && parsed.verified && parsed.destinationHash == propDestHex) {
            propPub64 = parsed.publicKey64
            if (((pkt[0].toInt() and 0xFF) and 0x40) != 0) {
                transportId = pkt.copyOfRange(2, 18)
            }
            println("[1] PN announce verified: dest=${parsed.destinationHash}")
        }
    }
    checkNotNull(propPub64) { "Did not receive PN announce" }

    // 1. Link request -> LRPROOF -> ACTIVE
    val link = RnsLinkSession(propDestHex, propPub64)
    sendPkt(link.buildLinkRequestPacket(transportId))
    val lrDeadline = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < lrDeadline && link.state != RnsLinkSession.State.ACTIVE) {
        val pkt = pollPacket(200) ?: continue
        val flags = pkt[0].toInt() and 0xFF
        val pt = flags and 0x03
        val h2 = ((flags and 0x40) shr 6) == RnsHeaderType.HEADER_2
        val destOff = if (h2) 18 else 2
        val ctxOff = if (h2) 34 else 18
        val payOff = if (h2) 35 else 19
        if (pt == RnsPacketType.PROOF && pkt.size > payOff && pkt[ctxOff] == RnsContext.LRPROOF) {
            val destHex = RnsCrypto.bytesToHex(pkt.copyOfRange(destOff, destOff + 16))
            if (destHex == link.linkIdHex) {
                val rttPkt = link.handleLinkProof(pkt.copyOfRange(payOff, pkt.size))
                if (rttPkt != null) {
                    sendPkt(rttPkt)
                }
            }
        }
    }
    check(link.state == RnsLinkSession.State.ACTIVE) { "Link did not become ACTIVE" }
    println("[1] PASS: Link ACTIVE id=${link.linkIdHex} mtu=${link.negotiatedMtu} kaIntervalMs=${link.keepaliveIntervalMs}")

    // Identify on link
    sendPkt(link.buildIdentifyPacket(pub64, edPriv)!!)

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

    // 3b. Two-phase /get: Phase 1 [null, null] -> transient IDs
    fun sendGetAndAwait(wants: List<ByteArray>?, haves: List<ByteArray>?): List<*> {
        val (_, reqPkt) = link.buildRequestPacket(LxmfCodec.MESSAGE_GET_PATH, LxmfCodec.buildGetMessagesRequest(wants, haves))!!
        sendPkt(reqPkt)
        val dl = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < dl) {
            val pkt = pollPacket(200) ?: continue
            val flags = pkt[0].toInt() and 0xFF
            val pt = flags and 0x03
            val h2 = ((flags and 0x40) shr 6) == RnsHeaderType.HEADER_2
            val ctxOff = if (h2) 34 else 18
            val payOff = if (h2) 35 else 19
            if (pt == RnsPacketType.DATA && pkt.size > payOff && pkt[ctxOff] == RnsContext.RESPONSE) {
                val dec = link.decryptLinkPayload(pkt.copyOfRange(payOff, pkt.size)) ?: continue
                val list = MsgPack.decode(dec).first as? List<*> ?: continue
                return list.getOrNull(1) as? List<*> ?: emptyList<Any?>()
            }
        }
        error("Timeout waiting for /get response")
    }

    val avail = sendGetAndAwait(null, null).mapNotNull { it as? ByteArray }
    check(avail.any { RnsCrypto.bytesToHex(it) == pyToKtTid }) { "Expected py_to_kt_tid $pyToKtTid in /get phase 1" }
    println("[3b] PASS: /get phase 1 returned ${avail.size} transient IDs including $pyToKtTid")

    // Phase 2: download wants
    val msgs = sendGetAndAwait(avail, emptyList()).mapNotNull { it as? ByteArray }
    check(msgs.isNotEmpty()) { "Expected messages in /get phase 2" }
    var unpackedPyMsg = false
    for (stored in msgs) {
        val wire = LxmfCodec.unpackPropagationMessage(stored, xPriv, idHash) ?: continue
        val msg = LxmfCodec.unpack(wire, aliceEdPub) ?: continue
        if (msg.signatureValid && String(msg.content, Charsets.UTF_8) == "hello from python lxmf 1.3.0") {
            unpackedPyMsg = true
        }
    }
    check(unpackedPyMsg) { "Failed to decrypt/verify Python LXMF message" }
    println("[2] PASS: Python -> Kotlin LXMF message decrypted and Ed25519 signature verified")

    // Phase 2b: purge haves
    sendGetAndAwait(emptyList(), avail)
    val availAfterPurge = sendGetAndAwait(null, null).mapNotNull { it as? ByteArray }
    check(availAfterPurge.none { RnsCrypto.bytesToHex(it) == pyToKtTid }) { "Message was not purged!" }
    println("[3c] PASS: /get purge removed $pyToKtTid from PN")

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
    sendPkt(link.buildLinkClosePacket()!!)

    // 5. >MDU 16KB LXMF message end-to-end via RNS Resource on lxmf.delivery
    val bobPub = RnsCrypto.hexToBytes(bobPubHex)
    val bobEdPub = bobPub.copyOfRange(32, 64)
    sendPkt(RnsCrypto.buildPathRequestPacket(RnsCrypto.hexToBytes(bobDestHex)))
    var bobTid: ByteArray? = null
    var bobFound = false
    val bobAnnDeadline = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < bobAnnDeadline && !bobFound) {
        val pkt = pollPacket(200) ?: continue
        val parsed = RnsAnnounceParser.parse(pkt)
        if (parsed != null && parsed.verified && parsed.destinationHash == bobDestHex) {
            bobFound = true
            if (((pkt[0].toInt() and 0xFF) and 0x40) != 0) {
                bobTid = pkt.copyOfRange(2, 18)
            }
        }
    }
    check(bobFound) { "Did not receive Bob lxmf.delivery announce" }

    val bobLink = RnsLinkSession(bobDestHex, bobPub)
    sendPkt(bobLink.buildLinkRequestPacket(bobTid))
    val bobLrDeadline = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < bobLrDeadline && bobLink.state != RnsLinkSession.State.ACTIVE) {
        val pkt = pollPacket(200) ?: continue
        val flags = pkt[0].toInt() and 0xFF
        val pt = flags and 0x03
        val h2 = ((flags and 0x40) shr 6) == RnsHeaderType.HEADER_2
        val destOff = if (h2) 18 else 2
        val ctxOff = if (h2) 34 else 18
        val payOff = if (h2) 35 else 19
        if (pt == RnsPacketType.PROOF && pkt.size > payOff && pkt[ctxOff] == RnsContext.LRPROOF) {
            if (RnsCrypto.bytesToHex(pkt.copyOfRange(destOff, destOff + 16)) == bobLink.linkIdHex) {
                bobLink.handleLinkProof(pkt.copyOfRange(payOff, pkt.size))?.let { sendPkt(it) }
            }
        }
    }
    check(bobLink.state == RnsLinkSession.State.ACTIVE) { "Bob lxmf.delivery link did not become ACTIVE" }
    sendPkt(bobLink.buildIdentifyPacket(pub64, edPriv)!!)

    // 5a. Kotlin -> Python 16KB incompressible LXMF message via RnsResourceSender (>16KB SDU => multi-part)
    val kt16kPayload = kotlin.random.Random(12345).nextBytes(16384)
    val kt16kWire = LxmfCodec.pack(
        destinationHash = bobDestHex,
        sourceHash = selfDestHex,
        edPriv = edPriv,
        content = kt16kPayload,
        title = "kt-16k-resource",
    )
    val resSender = RnsResourceSender(bobLink, kt16kWire, autoCompress = true)
    sendPkt(resSender.buildAdvertisementPacket()!!)

    // 5b. Drive Resource sender AND receive Python -> Kotlin 16KB LXMF via RnsResourceReceiver
    var resReceiver: RnsResourceReceiver? = null
    val resDeadline = System.currentTimeMillis() + 15000
    while (System.currentTimeMillis() < resDeadline &&
        (resSender.status != RnsResourceStatus.COMPLETE || resReceiver?.status != RnsResourceStatus.COMPLETE)
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
                resSender.handleRequest(dec).forEach { sendPkt(it) }
            }
            RnsContext.RESOURCE_PRF -> {
                if (pt == RnsPacketType.PROOF) {
                    resSender.handleProof(body)
                }
            }
            RnsContext.RESOURCE_ADV -> {
                val dec = bobLink.decryptLinkPayload(body) ?: continue
                val accepted = RnsResourceReceiver.accept(bobLink, dec)
                if (accepted != null) {
                    resReceiver = accepted.first
                    sendPkt(accepted.second)
                }
            }
            RnsContext.RESOURCE_HMU -> {
                val dec = bobLink.decryptLinkPayload(body) ?: continue
                resReceiver?.handleHashmapUpdate(dec)?.forEach { sendPkt(it) }
            }
            RnsContext.RESOURCE -> {
                resReceiver?.handlePart(body)?.forEach { sendPkt(it) }
            }
        }
    }
    check(resSender.status == RnsResourceStatus.COMPLETE) { "Kotlin -> Python 16KB Resource did not complete (status=${resSender.status})" }
    check(resReceiver?.status == RnsResourceStatus.COMPLETE) { "Python -> Kotlin 16KB Resource did not complete (status=${resReceiver?.status})" }
    val py16kWire = resReceiver.assembledData!!
    val py16kMsg = LxmfCodec.unpack(py16kWire, bobEdPub)
    checkNotNull(py16kMsg) { "Failed to unpack Python 16KB LXMF message" }
    check(py16kMsg.signatureValid) { "Invalid Ed25519 signature on Python 16KB LXMF message" }
    check(py16kMsg.content.size == 16384 && py16kMsg.title == "py-16k-resource") {
        "Unexpected Python 16KB LXMF content: title=${py16kMsg.title} len=${py16kMsg.content.size}"
    }
    println("[5b] PASS: Python -> Kotlin 16KB LXMF via RNS.Resource verified (len=${py16kMsg.content.size}, sigValid=true)")

    sendPkt(bobLink.buildLinkClosePacket()!!)
    sock.close()
}
