package app.pulse4.android.reticulum

import app.pulse4.android.data.NetworkMode
import app.pulse4.sync.TransportResult
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Package N / R4-8: event ordering, per-sync PN limit, float limits, PN change, /get idle timeout. */
class PackageNReticulumTest {

    private lateinit var tempDir: File
    private var propNodeHex: String = "aa".repeat(16)
    private lateinit var bridge: ReticulumBridge
    private val peerEdPriv = Ed25519PrivateKeyParameters(ByteArray(32) { 0x31 }, 0)
    private val peerXPriv = X25519PrivateKeyParameters(ByteArray(32) { 0x41 }, 0)
    private val peerEdPub: ByteArray = peerEdPriv.generatePublicKey().encoded
    private val peerXPub: ByteArray = peerXPriv.generatePublicKey().encoded
    private val peerPub64: ByteArray = peerXPub + peerEdPub

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("pulse4-pkg-n-bridge").toFile()
        bridge = ReticulumBridge(
            context = null,
            secrets = null,
            settings = null,
            filesDirOverride = tempDir,
            identitySeedOverride = ByteArray(32) { (it + 1).toByte() },
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

    private fun pnAppData(transferKb: Any, syncKb: Any, stampCost: Int = 1): ByteArray = MsgPack.encode(
        listOf(
            false,
            1700000000L,
            true,
            transferKb,
            syncKb,
            listOf(stampCost, 0, 16),
            emptyMap<Any?, Any?>(),
            listOf(stampCost.toDouble(), 0, 16),
        ),
    )

    /** Signed PN announce from the fake peer; sets [propNodeHex] to the announced destination. */
    private fun pnAnnounce(transferKb: Any, syncKb: Any): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        val ident = sha.digest(peerPub64).copyOf(16)
        val nameHash = sha.digest(LxmfCodec.PROPAGATION_APP_NAME.toByteArray(Charsets.UTF_8)).copyOf(10)
        val dest16 = sha.digest(nameHash + ident).copyOf(16)
        propNodeHex = RnsCrypto.bytesToHex(dest16)
        val randHash = ByteArray(10) { 0x05 }
        val appData = pnAppData(transferKb, syncKb)
        val signed = dest16 + peerPub64 + nameHash + randHash + appData
        val sig = Ed25519Signer().apply { init(true, peerEdPriv); update(signed, 0, signed.size) }.generateSignature()
        val flags = RnsCrypto.packetFlags(RnsHeaderType.HEADER_1, RnsTransportType.BROADCAST, RnsDestType.SINGLE, RnsPacketType.ANNOUNCE)
        return byteArrayOf(flags, 0x00) + dest16 + byteArrayOf(RnsContext.NONE) + peerPub64 + nameHash + randHash + sig + appData
    }

    private fun activateLinkWithPeer(): RnsLinkSession {
        val link = RnsLinkSession(propNodeHex, peerPub64, offeredMtu = 32768)
        val sig3 = RnsCrypto.encodeSignalling(32768, RnsLinkMode.AES256_CBC)
        val signed = link.linkIdBytes + peerXPub + peerEdPub + sig3
        val sig64 = Ed25519Signer().apply { init(true, peerEdPriv); update(signed, 0, signed.size) }.generateSignature()
        assertNotNull(link.handleLinkProof(sig64 + peerXPub + sig3))
        bridge.setPropagationLinkForTest(link)
        return link
    }

    @Test
    fun `R4-8 events are delivered sequentially in emission order`() {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(4)
        bridge.onEventReceived = { ev ->
            val d = ev as BridgeEvent.Delivery
            // The first handler is slow: with one coroutine per event the FAILED would overtake it.
            if (d.state == "HANDED") Thread.sleep(300)
            seen += "${d.eventId}:${d.state}"
            done.countDown()
        }
        bridge.emitEvent(BridgeEvent.Delivery("e1", "HANDED", null))
        bridge.emitEvent(BridgeEvent.Delivery("e1", "FAILED", TransportResult.REJECTED_BY_NODE))
        bridge.emitEvent(BridgeEvent.Delivery("e2", "STORED", null))
        bridge.emitEvent(BridgeEvent.Delivery("e3", "FAILED", "LINK_TEARDOWN"))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("e1:HANDED", "e1:FAILED", "e2:STORED", "e3:FAILED"), seen.toList())
    }

    @Test
    fun `R4-8 float PN limits are parsed exactly and the deposit limit is the per-sync limit`() {
        val info = checkNotNull(LxmfCodec.parsePropagationAnnounceAppData(pnAppData(16.0, 32.5)))
        assertEquals(16.0, info.perTransferLimitKb)
        assertEquals(32.5, info.perSyncLimitKb)
        assertEquals(32_500L, info.maxDepositBytes())
        assertEquals(16_000L, info.perTransferLimitBytes())

        // Sub-kilobyte float limit: `.toLong()` would have turned this into 0 (= no limit).
        val tiny = checkNotNull(LxmfCodec.parsePropagationAnnounceAppData(pnAppData(0.5, 0.5)))
        assertEquals(500L, tiny.maxDepositBytes())

        // Integer limits (Python int) still work; missing sync limit falls back to the transfer limit.
        assertEquals(256_000L, LxmfCodec.parsePropagationAnnounceAppData(pnAppData(256L, 0L))!!.maxDepositBytes())
        assertNull(LxmfCodec.parsePropagationAnnounceAppData(pnAppData(0L, 0L))!!.maxDepositBytes())
    }

    @Test
    fun `R4-8 deposit between per-transfer and per-sync limit is sent, above per-sync fails permanently`() {
        bridge.handleIncomingRnsPacketForTest(pnAnnounce(16L, 64L))
        activateLinkWithPeer()
        val contactDest = bridge.addContact(Base64.getEncoder().encodeToString(peerPub64))
        bridge.rawPacketSenderOverride = { true }
        val content = "x".repeat(35_000)

        bridge.send(contactDest, content, propagated = true, eventId = "evt-mid")
        val ev1 = bridge.drainEvents().filterIsInstance<BridgeEvent.Delivery>().filter { it.eventId == "evt-mid" }
        assertTrue(ev1.none { it.state == "FAILED" }, "deposit under the per-sync limit must not be pre-rejected: $ev1")
        assertTrue(ev1.any { it.state == "HANDED" })

        bridge.handleIncomingRnsPacketForTest(pnAnnounce(16L, 32L))
        bridge.send(contactDest, content, propagated = true, eventId = "evt-big")
        val ev2 = bridge.drainEvents().filterIsInstance<BridgeEvent.Delivery>().filter { it.eventId == "evt-big" }
        val failed = ev2.single { it.state == "FAILED" }
        assertTrue(failed.reason!!.startsWith(TransportResult.TOO_LARGE))
        assertTrue(ev2.none { it.state == "HANDED" })
        assertTrue(TransportResult.fromBridge(failed.state, failed.reason) is TransportResult.Rejected)
    }

    @Test
    fun `R4-8 propagationNodeInfo of the previous node is dropped when the PN changes`() {
        bridge.handleIncomingRnsPacketForTest(pnAnnounce(16L, 32L))
        assertEquals(32_000L, bridge.maxPropagationEnvelopeBytes())
        assertNotNull(bridge.propagationNodeInfo)

        propNodeHex = "bb".repeat(16)
        assertNull(bridge.propagationNodeInfo)
        assertNull(bridge.maxPropagationEnvelopeBytes())
    }

    @Test
    fun `R4-8 get waits while the resource keeps progressing and gives up only after idle time`() {
        // Progressing transfer that takes longer than a "hard cap" of 600 ms but never idles for 300 ms.
        val progressing = CompletableFuture<String>()
        val activity = AtomicLong(System.currentTimeMillis())
        val ticker = Thread {
            repeat(12) { Thread.sleep(100); activity.set(System.currentTimeMillis()) }
            progressing.complete("done")
        }
        ticker.start()
        val r = IdleTimeoutWaiter.await(
            future = progressing,
            lastActivityMs = { activity.get() },
            responseTimeoutMs = 200L,
            idleTimeoutMs = 300L,
            absoluteCapMs = 10_000L,
            pollMs = 20L,
        )
        assertEquals("done", r)
        ticker.join()

        // Stalled transfer: no progress -> null after ~idle timeout.
        val stalled = CompletableFuture<String>()
        val stalledAt = System.currentTimeMillis()
        val t0 = System.nanoTime()
        assertNull(
            IdleTimeoutWaiter.await(stalled, { stalledAt }, responseTimeoutMs = 100L, idleTimeoutMs = 300L, absoluteCapMs = 10_000L, pollMs = 20L),
        )
        val waited = (System.nanoTime() - t0) / 1_000_000L
        assertTrue(waited in 250L..2_000L, "waited $waited ms")

        // No resource at all: response timeout applies.
        val t1 = System.nanoTime()
        assertNull(IdleTimeoutWaiter.await(CompletableFuture<String>(), { null }, responseTimeoutMs = 150L, pollMs = 20L))
        val waited2 = (System.nanoTime() - t1) / 1_000_000L
        assertTrue(waited2 in 100L..2_000L, "waited $waited2 ms")
    }
}
