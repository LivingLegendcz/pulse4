package app.pulse4.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse4.android.data.AppDatabase
import app.pulse4.android.data.ContactRow
import app.pulse4.android.data.GrantRow
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.data.RoomOutboxStore
import app.pulse4.android.data.Settings
import app.pulse4.sharing.Field
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.TimeScope
import java.time.LocalDate
import app.pulse4.android.reticulum.BridgeEvent
import app.pulse4.android.reticulum.EncryptedEnvelope
import app.pulse4.android.reticulum.LxmfCodec
import app.pulse4.android.reticulum.MsgPack
import app.pulse4.android.reticulum.ReticulumBridge
import app.pulse4.android.reticulum.ReticulumTransport
import app.pulse4.android.reticulum.RnsCrypto
import app.pulse4.android.reticulum.RnsLinkSession
import app.pulse4.android.data.RoomReceiverStore
import app.pulse4.sync.AckSink
import app.pulse4.sync.BuiltInHandlers
import app.pulse4.sync.Outcome
import app.pulse4.sync.Receiver
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.sync.DeliveryState
import app.pulse4.sync.Kinds
import app.pulse4.sync.MessageBuilder
import app.pulse4.sync.OutboxItem
import app.pulse4.sync.OutboxManager
import app.pulse4.sync.TransportResult
import app.pulse4.sync.Wire
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReticulumLiveIntegrationTest {

    @Test
    fun liveLocalSocketE2eeV2WithCzechUtf8AndFastTrack() {
        val app = ApplicationProvider.getApplicationContext<Pulse4App>()
        // Ensure ServiceLocator.transport lazy init has finished before wrapping onEventReceived
        app.services.transport
        val bridge = app.services.bridge
        val aliceInfo = bridge.ensureStarted()
        val alicePort = aliceInfo.localPort
        assertNotNull("Local server port should be active", alicePort)

        // Create Bob identity
        val bobSeed = ByteArray(32) { (it + 77).toByte() }
        val bobEdPriv = Ed25519PrivateKeyParameters(bobSeed, 0)
        val bobEdPub = bobEdPriv.generatePublicKey()
        val bobXPriv = X25519PrivateKeyParameters(MessageDigest.getInstance("SHA-256").digest(bobSeed), 0)
        val bobPub64 = Base64.getEncoder().encodeToString(bobXPriv.generatePublicKey().encoded + bobEdPub.encoded)
        val bobHash = bridge.addContact(bobPub64)

        // 1. Test real local HTTP ServerSocket with E2EE v2 + Czech UTF-8 multi-byte chars
        val receivedLatch = CountDownLatch(1)
        var receivedContent: String? = null
        val prevHandler = bridge.onEventReceived
        bridge.onEventReceived = { ev ->
            if (ev is BridgeEvent.Message && ev.source == bobHash) {
                receivedContent = ev.content
                receivedLatch.countDown()
            } else {
                prevHandler?.invoke(ev)
            }
        }

        try {
            val alicePubBytes = Base64.getDecoder().decode(aliceInfo.publicKeyB64)
            val aliceXPub = X25519PublicKeyParameters(alicePubBytes, 0)
            val agreement = X25519Agreement().apply { init(bobXPriv) }
            val shared = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(aliceXPub, shared, 0)

            val (a, b) = if (bobHash <= aliceInfo.deliveryHash) bobHash to aliceInfo.deliveryHash else aliceInfo.deliveryHash to bobHash
            val aesKey = MessageDigest.getInstance("SHA-256").run {
                update(shared)
                update("pulse4-e2ee-v2".toByteArray(Charsets.UTF_8))
                update(a.toByteArray(Charsets.UTF_8))
                update(b.toByteArray(Charsets.UTF_8))
                digest()
            }
            val utf8Text = "Příliš žluťoučký kůň úpěl ďábelské ódy – Pulse4 OK"
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
            }
            val ct = cipher.doFinal(utf8Text.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.getEncoder().encodeToString(iv)
            val ctB64 = Base64.getEncoder().encodeToString(ct)
            val eventId = "live-utf8-${System.currentTimeMillis()}"
            val sigInput = "pulse4-v2:$bobHash:${aliceInfo.deliveryHash}:$ivB64:$ctB64:$eventId".toByteArray(Charsets.UTF_8)
            val sig = Ed25519Signer().run {
                init(true, bobEdPriv)
                update(sigInput, 0, sigInput.size)
                generateSignature()
            }
            val env = EncryptedEnvelope(
                v = 2,
                sender = bobHash,
                recipient = aliceInfo.deliveryHash,
                iv = ivB64,
                ciphertext = ctB64,
                mac = ReticulumBridge.bytesToHex(sig),
                eventId = eventId,
            )
            val jsonBody = Json.encodeToString(env)
            val res = OkHttpClient().newCall(
                Request.Builder()
                    .url("http://127.0.0.1:$alicePort/pulse4/sync")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            assertTrue("Local HTTP POST must succeed", res.isSuccessful)
            res.close()

            assertTrue("Message must arrive and decrypt via E2EE v2", receivedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(utf8Text, receivedContent)
        } finally {
            bridge.onEventReceived = prevHandler
        }

        // 2. Test STORED_IN_PROPAGATION fast-track over local Wi-Fi endpoint via ReticulumTransport.pump()
        val memDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val settings = Settings(memDb).apply { networkMode = NetworkMode.LOCAL_ONLY }
        val outbox = OutboxManager(RoomOutboxStore(memDb))
        val transport = ReticulumTransport(bridge, settings, memDb, outbox) { emptyMap() }

        val bobServer = ServerSocket(0)
        val bobReceived = CountDownLatch(1)
        thread {
            runCatching {
                val sock = bobServer.accept()
                sock.soTimeout = 3000
                val buf = ByteArray(4096)
                val baos = ByteArrayOutputStream()
                val n = sock.getInputStream().read(buf)
                if (n > 0) baos.write(buf, 0, n)
                sock.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                sock.getOutputStream().flush()
                sock.close()
                if (baos.size() > 0) bobReceived.countDown()
            }
        }

        try {
            val msg = MessageBuilder(aliceInfo.deliveryHash).build(
                recipient = bobHash,
                kind = Kinds.PROJECTION_UPSERT,
                objectType = "daily",
                objectId = "2026-11-15",
                objectVersion = 1L,
                payload = """{"weightKg":75.2}""",
                nowMs = 1_000L,
            )
            outbox.enqueue(
                OutboxItem(
                    eventId = msg.envelope.eventId,
                    recipient = bobHash,
                    kind = msg.envelope.kind,
                    objectType = msg.envelope.objectType,
                    objectId = msg.envelope.objectId,
                    objectVersion = msg.envelope.objectVersion,
                    grantId = null,
                    wire = Wire.encode(msg),
                    createdAtMs = 1_000L,
                )
            )
            outbox.onTransportResult(msg.envelope.eventId, TransportResult.StoredInPropagation, 1_000L)
            assertEquals(DeliveryState.STORED_IN_PROPAGATION.name, memDb.outbox().find(msg.envelope.eventId)?.state)

            bridge.registerLocalPeer(bobHash, "http://127.0.0.1:${bobServer.localPort}")
            val stats = transport.pump(nowMs = 2_000L, windowMs = 1_500L)
            assertTrue("pump() must fast-track STORED_IN_PROPAGATION item over Wi-Fi", stats.sent >= 1)
            assertTrue("Bob local HTTP server must receive fast-tracked message", bobReceived.await(5, TimeUnit.SECONDS))
        } finally {
            bridge.clearLocalPeers()
            runCatching { bobServer.close() }
            memDb.close()
        }
    }

    @Test
    fun liveGatewayTcpConnectionTestWorksFromEmulator() {
        val app = ApplicationProvider.getApplicationContext<Pulse4App>()
        val result = app.services.bridge.testNodeConnection("reticulum.hispagatos.org", 4242, 5000)
        assertTrue("TCP test to reticulum.hispagatos.org:4242 failed: ${result.detail}", result.reachable)
    }

    @Test
    fun delayedPickupViaPureRnsLxmfPropagationOverTcp() {
        val app = ApplicationProvider.getApplicationContext<Pulse4App>()
        val memDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()

        // Propagation node identity (simulates xali RNS + LXMF node on TCP)
        val nodeSeed = ByteArray(32) { (it + 31).toByte() }
        val nodeEdPriv = Ed25519PrivateKeyParameters(nodeSeed, 0)
        val nodeEdPub = nodeEdPriv.generatePublicKey()
        val nodeXPriv = X25519PrivateKeyParameters(MessageDigest.getInstance("SHA-256").digest(nodeSeed), 0)
        val nodePub64 = nodeXPriv.generatePublicKey().encoded + nodeEdPub.encoded
        val sha = MessageDigest.getInstance("SHA-256")
        val nodeIdent16 = sha.digest(nodePub64).copyOf(16)
        val nodeNameHash = sha.digest("lxmf.propagation".toByteArray(Charsets.UTF_8)).copyOf(10)
        val nodeDest16 = sha.digest(nodeNameHash + nodeIdent16).copyOf(16)
        val nodeDestHex = ReticulumBridge.bytesToHex(nodeDest16)

        // Bob identity (recipient who is offline during Alice's send, picks up later)
        val bobSeed = ByteArray(32) { (it + 91).toByte() }
        val bobEdPriv = Ed25519PrivateKeyParameters(bobSeed, 0)
        val bobEdPub = bobEdPriv.generatePublicKey()
        val bobXPriv = X25519PrivateKeyParameters(MessageDigest.getInstance("SHA-256").digest(bobSeed), 0)
        val bobPub64Bytes = bobXPriv.generatePublicKey().encoded + bobEdPub.encoded
        val bobPub64 = Base64.getEncoder().encodeToString(bobPub64Bytes)
        val bobIdent16 = sha.digest(bobPub64Bytes).copyOf(16)
        val bobDeliveryNameHash = sha.digest("pulse.delivery".toByteArray(Charsets.UTF_8)).copyOf(10)
        val bobDest16 = sha.digest(bobDeliveryNameHash + bobIdent16).copyOf(16)
        val bobDestHex = ReticulumBridge.bytesToHex(bobDest16)

        val mailbox = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val depositedLatch = CountDownLatch(1)
        val nodeServer = ServerSocket(0)

        fun framePacket(raw: ByteArray): ByteArray =
            byteArrayOf(ReticulumBridge.HDLC_FLAG) + ReticulumBridge.hdlcEscape(raw) + byteArrayOf(ReticulumBridge.HDLC_FLAG)

        // Minimal RNS + LXMF Propagation Node TCP server loop
        val serverThread = thread {
            while (!nodeServer.isClosed) {
                val sock = runCatching { nodeServer.accept() }.getOrNull() ?: break
                thread {
                    runCatching {
                        val out = sock.getOutputStream()
                        val input = sock.getInputStream()

                        // Send Propagation Node ANNOUNCE with 7-element MsgPack appData
                        val randomHash = ByteArray(10) { 0x05 }
                        val appData = MsgPack.encode(listOf(false, 1731700000L, true, 512L, 2048L, listOf(0L, 0L, 0L), mapOf("name" to "xali-test")))
                        val signedAnn = nodeDest16 + nodePub64 + nodeNameHash + randomHash + appData
                        val annSig = Ed25519Signer().run { init(true, nodeEdPriv); update(signedAnn, 0, signedAnn.size); generateSignature() }
                        val annPkt = byteArrayOf(0x01, 0x00) + nodeDest16 + byteArrayOf(0x00) + nodePub64 + nodeNameHash + randomHash + annSig + appData
                        out.write(framePacket(annPkt))
                        out.flush()

                        var linkFernet: RnsCrypto.RnsFernet? = null
                        var currentLinkId: ByteArray? = null
                        val frameBuf = ByteArrayOutputStream()
                        val buf = ByteArray(4096)
                        while (!sock.isClosed) {
                            val r = input.read(buf)
                            if (r == -1) break
                            for (i in 0 until r) {
                                val b = buf[i]
                                if (b == ReticulumBridge.HDLC_FLAG) {
                                    if (frameBuf.size() > 0) {
                                        val pkt = ReticulumBridge.hdlcUnescape(frameBuf.toByteArray())
                                        frameBuf.reset()
                                        if (pkt.size < 19) continue
                                        val flags = pkt[0].toInt() and 0xFF
                                        val pktType = flags and 0x03
                                        val headerType = (flags and 0x40) shr 6
                                        val payloadOffset = if (headerType == 1) 35 else 19
                                        val destOffset = if (headerType == 1) 18 else 2
                                        val pktDest = pkt.copyOfRange(destOffset, destOffset + 16)
                                        val ctx = pkt[payloadOffset - 1]
                                        val payload = pkt.copyOfRange(payloadOffset, pkt.size)

                                        if (pktType == 0x03 && pktDest.contentEquals(nodeDest16)) {
                                            // LINKREQUEST -> compute LinkId, derive Fernet, reply LRPROOF
                                            val ephXPub32 = payload.copyOfRange(0, 32)
                                            val ephEdPub32 = payload.copyOfRange(32, 64)
                                            val linkId = sha.digest(byteArrayOf(0x03) + nodeDest16 + byteArrayOf(0x00) + ephXPub32 + ephEdPub32).copyOf(16)
                                            currentLinkId = linkId
                                            val nodeEphXPriv = X25519PrivateKeyParameters(ByteArray(32) { 0x5A }, 0)
                                            val nodeEphXPub = nodeEphXPriv.generatePublicKey().encoded
                                            val sig3 = RnsCrypto.encodeSignalling(32768, 1)
                                            val proofSigned = linkId + nodeEphXPub + nodeEdPub.encoded + sig3
                                            val proofSig = Ed25519Signer().run { init(true, nodeEdPriv); update(proofSigned, 0, proofSigned.size); generateSignature() }
                                            val proofPkt = byteArrayOf(0x0E, 0x00) + linkId + byteArrayOf(0xFF.toByte()) + proofSig + nodeEphXPub + sig3
                                            out.write(framePacket(proofPkt))
                                            out.flush()

                                            val agr = X25519Agreement().apply { init(nodeEphXPriv) }
                                            val shared = ByteArray(agr.agreementSize)
                                            agr.calculateAgreement(X25519PublicKeyParameters(ephXPub32, 0), shared, 0)
                                            linkFernet = RnsCrypto.RnsFernet(RnsCrypto.hkdfSha256(shared, salt = linkId, length = 64))
                                        } else if (pktType == 0x00 && currentLinkId != null && pktDest.contentEquals(currentLinkId)) {
                                            val pt = linkFernet?.decrypt(payload) ?: continue
                                            if (ctx == 0x00.toByte()) {
                                                // LXMF propagation deposit: [timestamp, [storedBytes]]
                                                val list = MsgPack.decode(pt).first as? List<*> ?: continue
                                                val msgs = list.getOrNull(1) as? List<*> ?: continue
                                                for (m in msgs) {
                                                    (m as? ByteArray)?.let {
                                                        mailbox.add(it)
                                                        depositedLatch.countDown()
                                                    }
                                                }
                                            } else if (ctx == 0x09.toByte()) {
                                                // RNS REQUEST [reqTime, pathHash, data] -> respond with stored messages
                                                val reqList = MsgPack.decode(pt).first as? List<*> ?: continue
                                                val reqId = sha.digest(pt).copyOf(16)
                                                val respPayload = MsgPack.encode(listOf(reqId, mailbox.toList()))
                                                val ct = linkFernet.encrypt(respPayload)
                                                val respPkt = byteArrayOf(0x0C, 0x00) + currentLinkId + byteArrayOf(0x0A) + ct
                                                out.write(framePacket(respPkt))
                                                out.flush()
                                            }
                                        }
                                    }
                                } else {
                                    frameBuf.write(b.toInt())
                                }
                            }
                        }
                    }
                }
            }
        }

        val settings = Settings(memDb).apply {
            networkMode = NetworkMode.AUTO
            gatewayHost = "127.0.0.1"
            gatewayPort = nodeServer.localPort
            propagationNode = nodeDestHex
        }
        val aliceBridge = ReticulumBridge(app, app.services.secrets, settings)

        try {
            val aliceInfo = aliceBridge.ensureStarted()
            aliceBridge.addContact(bobPub64)

            // Wait for gateway connect + node announce
            val deadline = System.currentTimeMillis() + 5000
            while ((!aliceBridge.isGatewayOnline || aliceBridge.propagationNodeInfo == null) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue("Alice must connect to RNS+LXMF node", aliceBridge.isGatewayOnline)
            assertNotNull("Alice must receive LXMF propagation node info", aliceBridge.propagationNodeInfo)

            // 1. Alice sends projection while Bob is offline -> stored in LXMF propagation node
            val syncMsg = MessageBuilder(aliceInfo.deliveryHash).build(
                recipient = bobDestHex,
                kind = Kinds.PROJECTION_UPSERT,
                objectType = "daily",
                objectId = "2026-11-15",
                objectVersion = 1L,
                payload = """{"weightKg":76.4}""",
                nowMs = 1_000L,
            )
            aliceBridge.send(
                destHash = bobDestHex,
                content = Wire.encode(syncMsg).toString(Charsets.UTF_8),
                propagated = true,
                eventId = syncMsg.envelope.eventId,
                forceGateway = true,
            )
            assertTrue("LXMF propagation node must receive Alice's deposit", depositedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(1, mailbox.size)

            // 2. Later, Bob connects to the same RNS+LXMF node and picks up via /get
            val bobSock = java.net.Socket("127.0.0.1", nodeServer.localPort)
            val bobOut = bobSock.getOutputStream()
            val bobIn = bobSock.getInputStream()
            val bobLink = RnsLinkSession(nodeDestHex, nodePub64)
            bobOut.write(framePacket(bobLink.buildLinkRequestPacket()))
            bobOut.flush()

            var pickedUpResponse: List<*>? = null
            val frameBuf = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            val readDeadline = System.currentTimeMillis() + 5000
            while (pickedUpResponse == null && System.currentTimeMillis() < readDeadline) {
                val r = bobIn.read(buf)
                if (r == -1) break
                for (i in 0 until r) {
                    val b = buf[i]
                    if (b == ReticulumBridge.HDLC_FLAG) {
                        if (frameBuf.size() > 0) {
                            val pkt = ReticulumBridge.hdlcUnescape(frameBuf.toByteArray())
                            frameBuf.reset()
                            if (pkt.size < 19) continue
                            val pktType = pkt[0].toInt() and 0x03
                            val ctx = pkt[18]
                            val payload = pkt.copyOfRange(19, pkt.size)
                            if (pktType == 0x02 && ctx == 0xFF.toByte()) {
                                val rttPkt = bobLink.handleLinkProof(payload)!!
                                bobOut.write(framePacket(rttPkt))
                                val (_, getPkt) = bobLink.buildRequestPacket(LxmfCodec.MESSAGE_GET_PATH, LxmfCodec.buildGetMessagesRequest())!!
                                bobOut.write(framePacket(getPkt))
                                bobOut.flush()
                            } else if (pktType == 0x00 && ctx == 0x0A.toByte()) {
                                val pt = bobLink.decryptLinkPayload(payload)!!
                                val resp = MsgPack.decode(pt).first as List<*>
                                pickedUpResponse = resp[1] as List<*>
                            }
                        }
                    } else {
                        frameBuf.write(b.toInt())
                    }
                }
            }
            bobSock.close()

            assertNotNull("Bob must receive LXMF /get response", pickedUpResponse)
            val storedBytes = pickedUpResponse!!.single() as ByteArray
            val lxmfWire = LxmfCodec.unpackPropagationMessage(storedBytes, bobXPriv, bobIdent16)
            assertNotNull("Bob must decrypt LXMF propagation envelope", lxmfWire)
            val alicePubBytes = Base64.getDecoder().decode(aliceInfo.publicKeyB64)
            val lxmfMsg = LxmfCodec.unpack(lxmfWire!!, alicePubBytes.copyOfRange(32, 64))
            assertNotNull(lxmfMsg)
            assertTrue("LXMF Ed25519 signature from Alice must verify", lxmfMsg!!.signatureValid)

            // Feed decrypted Pulse4 E2EE v2 envelope into Bob's Receiver -> RoomReceiverStore
            val env = Wire.json.decodeFromString<EncryptedEnvelope>(String(lxmfMsg.content, Charsets.UTF_8))
            val aliceXPub = X25519PublicKeyParameters(alicePubBytes, 0)
            val agr = X25519Agreement().apply { init(bobXPriv) }
            val shared = ByteArray(agr.agreementSize)
            agr.calculateAgreement(aliceXPub, shared, 0)
            val (a, b) = if (aliceInfo.deliveryHash <= bobDestHex) aliceInfo.deliveryHash to bobDestHex else bobDestHex to aliceInfo.deliveryHash
            val aesKey = sha.run {
                update(shared)
                update("pulse4-e2ee-v2".toByteArray(Charsets.UTF_8))
                update(a.toByteArray(Charsets.UTF_8))
                update(b.toByteArray(Charsets.UTF_8))
                digest()
            }
            val decCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, Base64.getDecoder().decode(env.iv)))
            }
            val innerWire = decCipher.doFinal(Base64.getDecoder().decode(env.ciphertext))

            val bobDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
            try {
                bobDb.contacts().upsert(
                    ContactRow(
                        identity = aliceInfo.deliveryHash,
                        displayName = "Alice",
                        publicKeyB64 = aliceInfo.publicKeyB64,
                        fingerprint = aliceInfo.identityHash.take(12),
                        verifiedAtMs = 1L,
                    )
                )
                val grant = ShareGrant(
                    id = "g-lxmf-test",
                    ownerId = aliceInfo.deliveryHash,
                    recipients = setOf(bobDestHex),
                    fields = setOf(Field.WEIGHT),
                    scope = TimeScope.from(LocalDate.of(2026, 11, 15)),
                    createdAtMs = 1L,
                )
                bobDb.grants().upsert(
                    GrantRow(
                        id = grant.id,
                        revoked = false,
                        json = Wire.json.encodeToString(ShareGrant.serializer(), grant),
                    )
                )
                val receiver = Receiver(bobDestHex, RoomReceiverStore(bobDb), BuiltInHandlers.all(), AckSink { _, _ -> })
                val outcome = receiver.receive(aliceInfo.deliveryHash, innerWire, 5_000L)
                assertTrue("Bob Receiver must apply delayed LXMF message (was $outcome)", outcome is Outcome.Applied)
                // If later picked up again / duplicated, must cleanly return Duplicate
                val dupOutcome = receiver.receive(aliceInfo.deliveryHash, innerWire, 6_000L)
                assertTrue("Duplicate delayed copy must be discarded cleanly", dupOutcome is Outcome.Duplicate)
            } finally {
                bobDb.close()
            }
        } finally {
            aliceBridge.clearLocalPeers()
            runCatching { nodeServer.close() }
            memDb.close()
        }
    }
}
