package app.pulse4.android.reticulum

import app.pulse4.android.data.NetworkMode
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

class ReticulumBridgeHardeningTest {

    private lateinit var tempDir: File
    private var propNodeHex: String = "aa".repeat(16)
    private lateinit var bridge: ReticulumBridge
    private lateinit var peerEdPriv: Ed25519PrivateKeyParameters
    private lateinit var peerEdPub: ByteArray
    private lateinit var peerXPriv: X25519PrivateKeyParameters
    private lateinit var peerXPub: ByteArray
    private lateinit var peerPub64: ByteArray

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("pulse4-bridge-test").toFile()
        peerEdPriv = Ed25519PrivateKeyParameters(ByteArray(32) { 0x31 }, 0)
        peerEdPub = peerEdPriv.generatePublicKey().encoded
        peerXPriv = X25519PrivateKeyParameters(ByteArray(32) { 0x41 }, 0)
        peerXPub = peerXPriv.generatePublicKey().encoded
        peerPub64 = peerXPub + peerEdPub

        val selfSeed = ByteArray(32) { (it + 1).toByte() }
        bridge = ReticulumBridge(
            context = null,
            secrets = null,
            settings = null,
            filesDirOverride = tempDir,
            identitySeedOverride = selfSeed,
            propagationNodeOverride = { propNodeHex },
            networkModeOverride = { NetworkMode.MANUAL },
        )
        bridge.ensureStarted()
        bridge.setGatewayOnlineForTest(true)
    }

    @AfterTest
    fun tearDown() {
        bridge.stop()
        tempDir.deleteRecursively()
    }

    private fun activateLinkWithPeer(): RnsLinkSession {
        val link = RnsLinkSession(propNodeHex, peerPub64, offeredMtu = 32768)
        val sig3 = RnsCrypto.encodeSignalling(32768, RnsLinkMode.AES256_CBC)
        val signed = link.linkIdBytes + peerXPub + peerEdPub + sig3
        val sig64 = Ed25519Signer().apply {
            init(true, peerEdPriv)
            update(signed, 0, signed.size)
        }.generateSignature()
        assertNotNull(link.handleLinkProof(sig64 + peerXPub + sig3))
        assertEquals(RnsLinkSession.State.ACTIVE, link.state)
        bridge.setPropagationLinkForTest(link)
        return link
    }

    private fun buildPnAnnouncePacket(
        transferLimitKb: Long,
        stampCost: Int,
        stampFlex: Int,
    ): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        val ident = sha.digest(peerPub64).copyOf(16)
        val nameHash = sha.digest(LxmfCodec.PROPAGATION_APP_NAME.toByteArray(Charsets.UTF_8)).copyOf(10)
        val dest16 = sha.digest(nameHash + ident).copyOf(16)
        propNodeHex = RnsCrypto.bytesToHex(dest16)
        val randHash = ByteArray(10) { 0x05 }
        val appData = MsgPack.encode(
            listOf(
                false,
                1700000000L,
                true,
                transferLimitKb,
                1024L,
                listOf(stampCost, stampFlex, 16),
                emptyMap<Any?, Any?>(),
                listOf(stampCost.toDouble(), stampFlex, 16),
            ),
        )
        val signed = dest16 + peerPub64 + nameHash + randHash + appData
        val sig = Ed25519Signer().apply {
            init(true, peerEdPriv)
            update(signed, 0, signed.size)
        }.generateSignature()
        val flags = RnsCrypto.packetFlags(RnsHeaderType.HEADER_1, RnsTransportType.BROADCAST, RnsDestType.SINGLE, RnsPacketType.ANNOUNCE)
        return byteArrayOf(flags, 0x00) + dest16 + byteArrayOf(RnsContext.NONE) + peerPub64 + nameHash + randHash + sig + appData
    }

    private fun wrapLinkDataPacket(link: RnsLinkSession, context: Byte, plaintext: ByteArray): ByteArray {
        return link.buildEncryptedLinkPacket(context, plaintext)!!
    }

    private fun wrapLinkProofPacket(link: RnsLinkSession, context: Byte, payload: ByteArray): ByteArray {
        return link.buildUnencryptedLinkPacket(RnsPacketType.PROOF, context, payload)!!
    }

    private fun extractPayload(pkt: ByteArray): Pair<Byte, ByteArray> {
        val ctx = pkt[18]
        val payload = pkt.copyOfRange(19, pkt.size)
        return ctx to payload
    }

    @Test
    fun getPhase2SendsLimitInKbAndHandlesNoIdentityRetryAndPreservesPurgeIdsOnError() {
        val announcePkt = buildPnAnnouncePacket(transferLimitKb = 256L, stampCost = 8, stampFlex = 0)
        bridge.handleIncomingRnsPacketForTest(announcePkt)
        val link = activateLinkWithPeer()

        val tidHex = "cc".repeat(32)
        val tidBytes = RnsCrypto.hexToBytes(tidHex)
        bridge.addPendingPurgeIdForTest(tidHex)

        val sentContexts = mutableListOf<Byte>()
        val capturedPhase2Args = mutableListOf<List<*>>()
        var phase1Attempts = 0

        bridge.rawPacketSenderOverride = { pkt ->
            val (ctx, body) = extractPayload(pkt)
            sentContexts.add(ctx)
            if (ctx == RnsContext.REQUEST) {
                val dec = link.decryptLinkPayload(body)!!
                val reqList = MsgPack.decode(dec).first as List<*>
                val reqData = reqList[2] as List<*>
                val reqId = RnsCrypto.packetTruncatedHash(pkt)
                if (reqData[0] == null && reqData[1] == null) {
                    phase1Attempts++
                    val respVal: Any = if (phase1Attempts == 1) LxmfCodec.ERROR_NO_IDENTITY else listOf(tidBytes)
                    val respPkt = wrapLinkDataPacket(link, RnsContext.RESPONSE, MsgPack.encode(listOf(reqId, respVal)))
                    bridge.handleIncomingRnsPacketForTest(respPkt)
                } else {
                    capturedPhase2Args.add(reqData)
                    // Return ERROR_NO_ACCESS on phase 2 -> must NOT purge pendingPurgeIds!
                    val respPkt = wrapLinkDataPacket(
                        link,
                        RnsContext.RESPONSE,
                        MsgPack.encode(listOf(reqId, LxmfCodec.ERROR_NO_ACCESS)),
                    )
                    bridge.handleIncomingRnsPacketForTest(respPkt)
                }
            }
            true
        }

        bridge.syncInbox()

        // N-4: NO_IDENTITY triggered re-identify + retry of phase 1
        assertEquals(2, phase1Attempts)
        assertTrue(sentContexts.count { it == RnsContext.LINKIDENTIFY } >= 2)
        // N-3: Phase 2 limit must be in KB (256), NOT 256*1024 bytes!
        assertEquals(1, capturedPhase2Args.size)
        assertEquals(256, (capturedPhase2Args.first()[2] as Number).toInt())
        // N-4: NO_ACCESS surfaced in propagationStatus and pendingPurgeIds NOT discarded on phase 2 error
        assertEquals(PropagationAccessState.NO_ACCESS, bridge.propagationStatus.value.state)
        assertTrue(tidHex in bridge.pendingPurgeIdsSnapshotForTest())
    }

    @Test
    fun getHandlesThrottledBackoffAndPreservesPurgeIds() {
        val announcePkt = buildPnAnnouncePacket(transferLimitKb = 256L, stampCost = 8, stampFlex = 0)
        bridge.handleIncomingRnsPacketForTest(announcePkt)
        val link = activateLinkWithPeer()

        val tidHex = "dd".repeat(32)
        bridge.addPendingPurgeIdForTest(tidHex)
        var requestCount = 0

        bridge.rawPacketSenderOverride = { pkt ->
            val (ctx, body) = extractPayload(pkt)
            if (ctx == RnsContext.REQUEST) {
                requestCount++
                val dec = link.decryptLinkPayload(body)!!
                val reqList = MsgPack.decode(dec).first as List<*>
                val reqId = RnsCrypto.packetTruncatedHash(pkt)
                val respPkt = wrapLinkDataPacket(
                    link,
                    RnsContext.RESPONSE,
                    MsgPack.encode(listOf(reqId, LxmfCodec.ERROR_THROTTLED)),
                )
                bridge.handleIncomingRnsPacketForTest(respPkt)
            }
            true
        }

        bridge.syncInbox()
        assertEquals(1, requestCount)
        assertEquals(PropagationAccessState.THROTTLED, bridge.propagationStatus.value.state)
        assertTrue(bridge.propagationStatus.value.throttledUntilMs > System.currentTimeMillis())
        assertTrue(tidHex in bridge.pendingPurgeIdsSnapshotForTest())

        // Subsequent immediate syncInbox must skip due to throttle backoff
        bridge.syncInbox()
        assertEquals(1, requestCount)
    }

    @Test
    fun malformedAdvWithHugeOrNegativePartsSurvivesWithoutCrash() {
        val link = activateLinkWithPeer()
        val fakeHash = ByteArray(32) { 0x11 }
        val fakeRand = ByteArray(4) { 0x22 }
        val replies = mutableListOf<ByteArray>()
        bridge.rawPacketSenderOverride = { pkt -> replies.add(pkt); true }

        fun makeAdvPacket(nVal: Long): ByteArray {
            val adv = MsgPack.encode(
                linkedMapOf(
                    "t" to 1024L,
                    "d" to 1024L,
                    "n" to nVal,
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
            return wrapLinkDataPacket(link, RnsContext.RESOURCE_ADV, adv)
        }

        bridge.handleIncomingRnsPacketForTest(makeAdvPacket(0x7fffffffL))
        bridge.handleIncomingRnsPacketForTest(makeAdvPacket(-1L))
        // Must reject cleanly with RESOURCE_RCL and never throw OutOfMemoryError
        assertTrue(replies.all { extractPayload(it).first == RnsContext.RESOURCE_RCL })
    }

    @Test
    fun resourceDepositWaitsForPostProofGraceWindowAndFailsIfLinkCloses() {
        val announcePkt = buildPnAnnouncePacket(transferLimitKb = 256L, stampCost = 1, stampFlex = 0)
        bridge.handleIncomingRnsPacketForTest(announcePkt)
        val link = activateLinkWithPeer()
        bridge.postProofGraceMs = 400L

        val contactPubB64 = Base64.getEncoder().encodeToString(peerPub64)
        val contactDest = bridge.addContact(contactPubB64)
        val largePayload = Random(123).nextBytes(40_000) // > 32KB link.mdu -> Resource deposit

        val peerCoord = LinkResourceCoordinator()
        val peerOutboundQueue = ArrayDeque<ByteArray>()
        bridge.rawPacketSenderOverride = { pkt ->
            val (ctx, body) = extractPayload(pkt)
            val (_, _) = peerCoord.handleLinkData(link, ctx, body, packetHash = RnsCrypto.packetHash(pkt)) { reply ->
                peerOutboundQueue.addLast(reply)
                true
            }
            true
        }

        assertTrue(bridge.sendViaLxmfPropagationForTest(contactDest, largePayload, "evt-grace-fail"))
        assertEquals(1, bridge.activeOutboundResourceCountForTest())

        // Pump Resource REQ -> DATA -> RESOURCE_PRF
        var steps = 0
        while (peerOutboundQueue.isNotEmpty() && steps < 200) {
            steps++
            val pkt = peerOutboundQueue.removeFirst()
            bridge.handleIncomingRnsPacketForTest(pkt)
        }
        // Proof arrived: must NOT be STORED yet while inside postProofGraceMs!
        assertEquals(1, bridge.pendingPostProofsCountForTest())
        assertTrue(bridge.drainEvents().none { it is BridgeEvent.Delivery && it.state == "STORED" })

        // Simulate PN tearing down link due to invalid stamp immediately after RESOURCE_PRF
        val linkClosePkt = wrapLinkDataPacket(link, RnsContext.LINKCLOSE, link.linkIdBytes)
        bridge.handleIncomingRnsPacketForTest(linkClosePkt)

        val events = bridge.drainEvents().filterIsInstance<BridgeEvent.Delivery>()
        assertTrue(events.any { it.eventId == "evt-grace-fail" && it.state == "FAILED" })
        assertTrue(events.none { it.state == "STORED" })
        assertEquals(0, bridge.pendingPostProofsCountForTest())

        // Now test healthy path where link stays ACTIVE past postProofGraceMs -> STORED!
        bridge.postProofGraceMs = 100L
        val link2 = activateLinkWithPeer()
        val peerCoord2 = LinkResourceCoordinator()
        val queue2 = ArrayDeque<ByteArray>()
        bridge.rawPacketSenderOverride = { pkt ->
            val (ctx, body) = extractPayload(pkt)
            peerCoord2.handleLinkData(link2, ctx, body, packetHash = RnsCrypto.packetHash(pkt)) { reply ->
                queue2.addLast(reply)
                true
            }
            true
        }
        assertTrue(bridge.sendViaLxmfPropagationForTest(contactDest, largePayload, "evt-grace-ok"))
        steps = 0
        while (queue2.isNotEmpty() && steps < 200) {
            steps++
            bridge.handleIncomingRnsPacketForTest(queue2.removeFirst())
        }
        Thread.sleep(250)
        val okEvents = bridge.drainEvents().filterIsInstance<BridgeEvent.Delivery>()
        assertTrue(okEvents.any { it.eventId == "evt-grace-ok" && it.state == "STORED" })
    }

    @Test
    fun linkCloseClearsPendingLinkProofsAndUnusableStampCostMarksNodeUnusable() {
        val announceNormal = buildPnAnnouncePacket(transferLimitKb = 256L, stampCost = 1, stampFlex = 0)
        bridge.handleIncomingRnsPacketForTest(announceNormal)
        val link = activateLinkWithPeer()
        val contactPubB64 = Base64.getEncoder().encodeToString(peerPub64)
        val contactDest = bridge.addContact(contactPubB64)
        bridge.rawPacketSenderOverride = { true }

        assertTrue(bridge.sendViaLxmfPropagationForTest(contactDest, "small".toByteArray(), "evt-proof-clear"))
        assertEquals(1, bridge.pendingLinkProofsCountForTest())

        // NEW-4: LINKCLOSE clears pendingLinkProofs
        bridge.handleIncomingRnsPacketForTest(wrapLinkDataPacket(link, RnsContext.LINKCLOSE, link.linkIdBytes))
        assertEquals(0, bridge.pendingLinkProofsCountForTest())

        // NEW-5: stampCost > 24 marks node unusable and skips PoW loop
        val announceHighCost = buildPnAnnouncePacket(transferLimitKb = 256L, stampCost = 26, stampFlex = 0)
        bridge.handleIncomingRnsPacketForTest(announceHighCost)
        assertEquals(PropagationAccessState.UNUSABLE_STAMP_COST, bridge.propagationStatus.value.state)
        assertFalse(bridge.sendViaLxmfPropagationForTest(contactDest, "small".toByteArray(), "evt-too-costly"))
    }

    @Test
    fun depositCacheKeysByRecipientAndPnPurgesAndPrunesOldFiles() {
        val announcePkt = buildPnAnnouncePacket(transferLimitKb = 256L, stampCost = 1, stampFlex = 0)
        bridge.handleIncomingRnsPacketForTest(announcePkt)
        val contactPubB64 = Base64.getEncoder().encodeToString(peerPub64)
        val contactDest = bridge.addContact(contactPubB64)

        bridge.preparePropagationDeposit(contactDest, "{\"e\":1}", "evt-cache-1")
        val cacheDir = File(tempDir, "rns_deposit_cache")
        val filesPn1 = cacheDir.listFiles()?.toList().orEmpty()
        assertEquals(1, filesPn1.size)

        // Changing PN produces a distinct cache key file
        propNodeHex = "bb".repeat(16)
        bridge.preparePropagationDeposit(contactDest, "{\"e\":1}", "evt-cache-1")
        val filesPn2 = cacheDir.listFiles()?.toList().orEmpty()
        assertEquals(2, filesPn2.size)

        // Purging deletes cached files for eventId
        bridge.purgeDepositCache("evt-cache-1", contactDest)
        assertEquals(0, cacheDir.listFiles()?.size ?: 0)

        // Pruning deletes files > 30 days old, keeps recent ones
        val oldFile = File(cacheDir, "old_entry.lxd").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val freshFile = File(cacheDir, "fresh_entry.lxd").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val now = System.currentTimeMillis()
        oldFile.setLastModified(now - 31L * 24L * 3600L * 1000L)
        freshFile.setLastModified(now - 2L * 24L * 3600L * 1000L)

        val pruned = bridge.pruneDepositCache(nowMs = now)
        assertEquals(1, pruned)
        assertFalse(oldFile.exists())
        assertTrue(freshFile.exists())
    }

    @Test
    fun zeroDirectAndroidUtilLogInOwnedReticulumFiles() {
        val files = listOf(
            File("src/main/kotlin/app/pulse4/android/reticulum/ReticulumBridge.kt"),
            File("src/main/kotlin/app/pulse4/android/reticulum/ReticulumTransport.kt"),
            File("src/main/kotlin/app/pulse4/android/reticulum/LinkResourceCoordinator.kt"),
            File("src/main/kotlin/app/pulse4/android/reticulum/GatewayRoutingState.kt"),
            File("src/main/kotlin/app/pulse4/android/reticulum/BridgeEnvelopeCrypto.kt"),
        ).filter { it.exists() }
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val content = f.readText()
            assertFalse(content.contains("android.util.Log"), "${f.name} still calls android.util.Log directly")
        }
    }
}
