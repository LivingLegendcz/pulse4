package app.pulse4.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pulse4.android.data.AppDatabase
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.data.RoomOutboxStore
import app.pulse4.android.data.Settings
import app.pulse4.android.reticulum.BridgeEvent
import app.pulse4.android.reticulum.EncryptedEnvelope
import app.pulse4.android.reticulum.ReticulumBridge
import app.pulse4.android.reticulum.ReticulumTransport
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
}
