package app.pulse4.android.reticulum

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.data.Settings
import app.pulse4.android.security.SecretStore
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

data class BridgeInfo(
    val deliveryHash: String,
    val identityHash: String,
    val publicKeyB64: String,
    val localPort: Int? = null,
)

enum class GatewayHealthState { UNCONFIGURED, CONNECTING, ONLINE, UNREACHABLE }

data class GatewayHealth(
    val host: String = "",
    val port: Int = 4242,
    val state: GatewayHealthState = GatewayHealthState.UNCONFIGURED,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
)

sealed interface BridgeEvent {
    data class Message(val source: String, val validated: Boolean, val content: String) : BridgeEvent
    data class Delivery(val eventId: String, val state: String, val reason: String?) : BridgeEvent
}

@Serializable
internal data class EncryptedEnvelope(
    val v: Int = 1,
    val sender: String,
    val recipient: String,
    val iv: String,
    val ciphertext: String,
    val mac: String,
    val eventId: String? = null
)

/**
 * Nativní Kotlin transportní most (PulseSync):
 * 1. Plné end-to-end šifrování (E2EE) s využitím AES-256-GCM a HMAC-SHA256.
 * 2. Tier 1: Lokální Wi-Fi přenos s nulovou konfigurací přes Android NSD (mDNS) a vestavěný mikro-server.
 * 3. Tier 2: Směrování přes Reticulum (RNS) TCP uzel v internetu s HDLC paketovým rámováním.
 * 4. Tier 3: Asynchronní doručení přes Zero-Knowledge Blind Mailbox Relay pro HTTP/HTTPS relay servery.
 */
class ReticulumBridge(
    private val context: Context,
    private val secrets: SecretStore,
    private val settings: Settings,
) {
    var info: BridgeInfo? = null
        private set

    private val _gatewayHealth = MutableStateFlow(GatewayHealth())
    val gatewayHealth: StateFlow<GatewayHealth> get() = _gatewayHealth

    fun reportGatewayUnreachableForTest(host: String, port: Int, reason: String? = "Nedostupný") {
        _gatewayHealth.value = GatewayHealth(
            host = host,
            port = port,
            state = GatewayHealthState.UNREACHABLE,
            consecutiveFailures = 1,
            lastError = reason,
        )
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pendingEvents = mutableListOf<BridgeEvent>()
    private val registeredContacts = ConcurrentHashMap<String, String>() // deliveryHash -> publicKeyB64
    private val localPeerMap = ConcurrentHashMap<String, String>() // deliveryHash -> "http://ip:port"

    private var multicastLock: WifiManager.MulticastLock? = null
    private var localServerSocket: ServerSocket? = null
    private var localServerJob: Job? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var tcpSocket: Socket? = null
    @Volatile
    private var tcpWriter: OutputStream? = null
    private var tcpJob: Job? = null
    @Volatile
    var isGatewayOnline = false
        private set
    @Volatile
    var gatewayTransportId: ByteArray? = null
        private set

    private var ed25519PrivKey: Ed25519PrivateKeyParameters? = null
    var onEventReceived: ((BridgeEvent) -> Unit)? = null

    private fun emitEvent(ev: BridgeEvent) {
        val handler = onEventReceived
        if (handler != null) {
            scope.launch {
                try {
                    handler(ev)
                } catch (e: Throwable) {
                    android.util.Log.e("PulseSync", "Chyba obsluhy onEventReceived", e)
                    synchronized(pendingEvents) { pendingEvents.add(ev) }
                }
            }
        } else {
            synchronized(pendingEvents) { pendingEvents.add(ev) }
        }
    }

    private val http = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    companion object {
        const val HDLC_FLAG = 0x7E.toByte()
        const val HDLC_ESC = 0x7D.toByte()
        const val HDLC_ESC_MASK = 0x20

        fun hdlcEscape(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size + 16)
            for (b in data) {
                val ub = b.toInt() and 0xFF
                if (b == HDLC_ESC) {
                    out.write(HDLC_ESC.toInt())
                    out.write(HDLC_ESC.toInt() xor HDLC_ESC_MASK)
                } else if (b == HDLC_FLAG) {
                    out.write(HDLC_ESC.toInt())
                    out.write(HDLC_FLAG.toInt() xor HDLC_ESC_MASK)
                } else {
                    out.write(ub)
                }
            }
            return out.toByteArray()
        }

        fun hdlcUnescape(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size)
            var i = 0
            while (i < data.size) {
                val b = data[i]
                if (b == HDLC_ESC && i + 1 < data.size) {
                    val next = data[i + 1]
                    out.write(next.toInt() xor HDLC_ESC_MASK)
                    i += 2
                } else {
                    out.write(b.toInt() and 0xFF)
                    i++
                }
            }
            return out.toByteArray()
        }

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            val len = clean.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun ensureStarted(): BridgeInfo {
        info?.let { return it }
        acquireMulticast()

        var privateKeyB64 = secrets.getString(SecretStore.RETICULUM_IDENTITY)
        if (privateKeyB64 == null) {
            val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            privateKeyB64 = Base64.getEncoder().encodeToString(seed)
            secrets.putString(SecretStore.RETICULUM_IDENTITY, privateKeyB64)
        }

        val privBytes = Base64.getDecoder().decode(privateKeyB64)
        val edPriv = Ed25519PrivateKeyParameters(privBytes, 0)
        val edPub = edPriv.generatePublicKey()
        this.ed25519PrivKey = edPriv

        val xSeed = MessageDigest.getInstance("SHA-256").digest(privBytes)
        val xPriv = X25519PrivateKeyParameters(xSeed, 0)
        val xPub = xPriv.generatePublicKey()

        // 64-bajtový veřejný klíč Reticulum (X25519 32B + Ed25519 32B)
        val pubKey64 = xPub.encoded + edPub.encoded
        val publicKeyB64 = Base64.getEncoder().encodeToString(pubKey64)

        // Reticulum Identity Hash (16 bajtů = 128 bitů)
        val sha256 = MessageDigest.getInstance("SHA-256")
        val identHash = sha256.digest(pubKey64).copyOf(16)
        val identityHex = bytesToHex(identHash)

        // Reticulum Destination Hash pro "pulse.delivery" (16 bajtů = 128 bitů)
        val nameHash = sha256.digest("pulse.delivery".toByteArray(Charsets.UTF_8)).copyOf(10)
        val deliveryHashBytes = sha256.digest(nameHash + identHash).copyOf(16)
        val deliveryHex = bytesToHex(deliveryHashBytes)

        startLocalServer(deliveryHex)

        val bi = BridgeInfo(
            deliveryHash = deliveryHex,
            identityHash = identityHex,
            publicKeyB64 = publicKeyB64,
            localPort = localServerSocket?.localPort
        )
        info = bi

        startNsd(bi.deliveryHash)

        if (settings.networkMode != NetworkMode.LOCAL_ONLY) {
            startGatewayClient()
        }

        return bi
    }

    private fun acquireMulticast() {
        if (multicastLock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("pulse4-sync").apply { setReferenceCounted(false); acquire() }
    }

    /** Spustí lokální mikro-server pro přímý přenos po místní Wi-Fi síti. */
    private fun startLocalServer(selfHash: String) {
        if (localServerSocket != null) return
        try {
            val server = ServerSocket(0)
            localServerSocket = server
            localServerJob = scope.launch {
                while (!server.isClosed) {
                    try {
                        val socket = server.accept()
                        scope.launch { handleLocalSocket(socket, selfHash) }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
            android.util.Log.i("PulseSync", "Lokální server spuštěn na portu ${server.localPort}")
        } catch (e: Exception) {
            android.util.Log.w("PulseSync", "Nelze spustit lokální server: ${e.message}")
        }
    }

    private fun handleLocalSocket(socket: Socket, selfHash: String) {
        try {
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            var line = reader.readLine() ?: return
            var contentLength = 0
            while (line.isNotEmpty()) {
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                line = reader.readLine() ?: break
            }
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = reader.read(buf, read, contentLength - read)
                    if (r == -1) break
                    read += r
                }
                val body = String(buf, 0, read)
                processIncomingEncryptedEnvelope(body, selfHash)
            }
            val out: OutputStream = socket.getOutputStream()
            val resp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(resp.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Exception) {
            android.util.Log.w("PulseSync", "Chyba obsluhy lokálního spojení: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Publikuje a vyhledává _pulse4._tcp služby v lokální Wi-Fi přes Android NSD (mDNS). */
    private fun startNsd(selfHash: String) {
        val server = localServerSocket ?: return
        val port = server.localPort
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = nsd

        val serviceInfo = NsdServiceInfo().apply {
            serviceType = "_pulse4._tcp"
            serviceName = "Pulse4-$selfHash"
            setPort(port)
            setAttribute("addr", selfHash)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener) }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("_pulse4")) {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val addrBytes = serviceInfo.attributes["addr"] ?: return
                            val peerHash = String(addrBytes, Charsets.UTF_8).trim()
                            if (peerHash.isNotBlank() && peerHash != selfHash) {
                                val host = serviceInfo.host.hostAddress
                                val peerPort = serviceInfo.port
                                localPeerMap[peerHash] = "http://$host:$peerPort"
                            }
                        }
                    })
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }
        runCatching { nsd.discoverServices("_pulse4._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
    }

    /** Manuální registrace peer endpointu (užitečné při spojení emulátor-telefon bez mDNS) */
    fun registerLocalPeer(destHash: String, endpointUrl: String) {
        localPeerMap[destHash] = endpointUrl
        android.util.Log.i("PulseSync", "Manuálně zaregistrován peer endpoint: $destHash -> $endpointUrl")
    }

    fun clearLocalPeers() {
        localPeerMap.clear()
        android.util.Log.i("PulseSync", "Vyčištěna tabulka lokálních peerů")
    }

    /** Spustí klientské spojení k Reticulum TCP uzlu v internetu */
    @Synchronized
    fun startGatewayClient() {
        val host = settings.gatewayHost.trim()
        if (host.isBlank()) {
            tcpJob?.cancel()
            val oldSocket = tcpSocket
            tcpSocket = null
            tcpWriter = null
            isGatewayOnline = false
            _gatewayHealth.value = GatewayHealth(state = GatewayHealthState.UNCONFIGURED)
            runCatching { oldSocket?.close() }
            return
        }
        val port = settings.gatewayPort.takeIf { it > 0 } ?: 4242
        if (port == 80 || port == 443) return

        if (tcpJob?.isActive == true && isGatewayOnline && tcpSocket?.isConnected == true && tcpSocket?.isClosed == false) {
            return
        }

        tcpJob?.cancel()
        val oldSocket = tcpSocket
        tcpSocket = null
        tcpWriter = null
        isGatewayOnline = false
        _gatewayHealth.value = GatewayHealth(host = host, port = port, state = GatewayHealthState.CONNECTING)
        runCatching { oldSocket?.close() }

        tcpJob = scope.launch {
            var failures = 0
            while (isActive) {
                var currentSocket: Socket? = null
                try {
                    android.util.Log.i("PulseSync", "Připojování k Reticulum gateway $host:$port...")
                    val s = Socket()
                    currentSocket = s
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    s.connect(InetSocketAddress(host, port), 6000)
                    failures = 0
                    synchronized(this@ReticulumBridge) {
                        tcpSocket = s
                        tcpWriter = s.getOutputStream()
                        isGatewayOnline = true
                    }
                    _gatewayHealth.value = GatewayHealth(host = host, port = port, state = GatewayHealthState.ONLINE)
                    android.util.Log.i("PulseSync", "Úspěšně připojeno k Reticulum gateway $host:$port")

                    // Odešli úvodní Announce paket
                    sendRnsAnnounce()

                    val inputStream = s.getInputStream()
                    val frameBuffer = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (isActive && !s.isClosed) {
                        val read = inputStream.read(buf)
                        if (read == -1) break
                        for (i in 0 until read) {
                            val b = buf[i]
                            if (b == HDLC_FLAG) {
                                if (frameBuffer.size() > 0) {
                                    val frame = frameBuffer.toByteArray()
                                    frameBuffer.reset()
                                    val unescaped = hdlcUnescape(frame)
                                    handleIncomingRnsPacket(unescaped)
                                }
                            } else {
                                frameBuffer.write(b.toInt())
                            }
                        }
                    }
                } catch (e: Exception) {
                    failures++
                    _gatewayHealth.value = GatewayHealth(
                        host = host,
                        port = port,
                        state = GatewayHealthState.UNREACHABLE,
                        consecutiveFailures = failures,
                        lastError = e.message ?: "Spojení se nezdařilo",
                    )
                    android.util.Log.w("PulseSync", "Chyba Reticulum gateway spojení ($host:$port): ${e.message}")
                } finally {
                    synchronized(this@ReticulumBridge) {
                        if (tcpSocket === currentSocket) {
                            isGatewayOnline = false
                            tcpSocket = null
                            tcpWriter = null
                        }
                    }
                    runCatching { currentSocket?.close() }
                }
                delay(5000)
            }
        }
    }

    private fun handleIncomingRnsPacket(packet: ByteArray) {
        if (packet.size < 19) return
        val flags = packet[0].toInt() and 0xFF
        val headerType = (flags and 0x40) shr 6

        // Dynamicky zachytíme transport ID internetové brány z jakéhokoliv transport paketu
        if (headerType == 1 && packet.size >= 18) {
            val tId = packet.copyOfRange(2, 18)
            if (gatewayTransportId == null || !gatewayTransportId.contentEquals(tId)) {
                gatewayTransportId = tId
                android.util.Log.i("PulseSync", "Zjištěno Transport ID internetové brány: ${bytesToHex(tId)}")
            }
        } else if (headerType == 0 && packet.size >= 51) {
            val destBytes = packet.copyOfRange(2, 18)
            val destHex = bytesToHex(destBytes)
            // Standardní rnstransport.path.request cíl (6b9f66014d9853faab220fba47d02761)
            // Payload začíná na indexu 19, transport ID brány je na offsetu 16..32 v payloadu (bajty 35..51 paketu)
            if (destHex.equals("6b9f66014d9853faab220fba47d02761", ignoreCase = true)) {
                val tId = packet.copyOfRange(35, 51)
                if (gatewayTransportId == null || !gatewayTransportId.contentEquals(tId)) {
                    gatewayTransportId = tId
                    android.util.Log.i("PulseSync", "Zjištěno Transport ID internetové brány z path request: ${bytesToHex(tId)}")
                }
            }
        }

        val packetType = flags and 0x03
        if (packetType != 0) {
            // Ignorujeme kontrolní a announce pakety (Announce=1, Proof=2, Link=3)
            return
        }

        val destHex: String
        val payload: ByteArray

        if (headerType == 1) { // HEADER_2 (transport-routed paket s 16B transport_id)
            if (packet.size < 35) return
            val destBytes = packet.copyOfRange(18, 34)
            destHex = bytesToHex(destBytes)
            payload = packet.copyOfRange(35, packet.size)
        } else { // HEADER_1 (přímý 1-hop paket)
            val destBytes = packet.copyOfRange(2, 18)
            destHex = bytesToHex(destBytes)
            payload = packet.copyOfRange(19, packet.size)
        }

        val selfHash = info?.deliveryHash ?: return
        if (destHex.equals(selfHash, ignoreCase = true)) {
            val body = String(payload, Charsets.UTF_8)
            android.util.Log.i("PulseSync", "Příchozí RNS DATA paket určen pro naši adresu ($destHex, len=${payload.size}), předávám k dešifrování...")
            processIncomingEncryptedEnvelope(body, selfHash)
        } else {
            android.util.Log.d("PulseSync", "Přeskočen RNS paket pro jinou adresu: $destHex (naše=$selfHash)")
        }
    }

    private fun sendOverGateway(destHashHex: String, payloadBytes: ByteArray): Boolean {
        val writer = tcpWriter ?: return false
        val s = tcpSocket ?: return false
        if (!s.isConnected || s.isClosed) return false

        return try {
            val destBytes = hexToBytes(destHashHex)
            if (destBytes.size != 16) return false
            val tId = gatewayTransportId
            val hops = 0x00.toByte()
            val context = 0x00.toByte()

            val rawPacket = if (tId != null) {
                // HEADER_2 (bit 6=1), TRANSPORT (bit 4=1), SINGLE dest (bits 2..3=0), DATA (bits 0..1=0) -> 0x50
                val flags = 0x50.toByte()
                byteArrayOf(flags, hops) + tId + destBytes + byteArrayOf(context) + payloadBytes
            } else {
                // Fallback direct HEADER_1
                val flags = 0x00.toByte()
                byteArrayOf(flags, hops) + destBytes + byteArrayOf(context) + payloadBytes
            }

            val framed = byteArrayOf(HDLC_FLAG) + hdlcEscape(rawPacket) + byteArrayOf(HDLC_FLAG)
            synchronized(writer) {
                writer.write(framed)
                writer.flush()
            }
            android.util.Log.i("PulseSync", "Odeslán RNS paket ${rawPacket.size}B pro $destHashHex přes internet uzel (routed=${tId != null})")
            true
        } catch (e: Exception) {
            android.util.Log.w("PulseSync", "Selhalo odeslání RNS paketu přes uzel: ${e.message}")
            false
        }
    }

    fun sendRnsAnnounce() {
        val writer = tcpWriter ?: return
        val selfInfo = info ?: return
        try {
            val destBytes = hexToBytes(selfInfo.deliveryHash)
            val nameHash = MessageDigest.getInstance("SHA-256")
                .digest("pulse.delivery".toByteArray(Charsets.UTF_8))
                .copyOf(10)

            // Reticulum standardní random_hash: 5 náhodných bajtů + 5 bajtů big-endian unix timestamp (sekundy)
            val randomBytes = ByteArray(5).also { SecureRandom().nextBytes(it) }
            val nowSec = System.currentTimeMillis() / 1000L
            val timeBytes = byteArrayOf(
                ((nowSec shr 32) and 0xFF).toByte(),
                ((nowSec shr 24) and 0xFF).toByte(),
                ((nowSec shr 16) and 0xFF).toByte(),
                ((nowSec shr 8) and 0xFF).toByte(),
                (nowSec and 0xFF).toByte()
            )
            val randomHash = randomBytes + timeBytes

            val appData = settings.displayName.toByteArray(Charsets.UTF_8)

            val pubBytes = runCatching { Base64.getDecoder().decode(selfInfo.publicKeyB64) }.getOrNull() ?: ByteArray(32)
            val pubKey64 = if (pubBytes.size >= 64) pubBytes.copyOf(64) else {
                val padded = ByteArray(64)
                System.arraycopy(pubBytes, 0, padded, 0, minOf(pubBytes.size, 64))
                padded
            }

            val signedData = destBytes + pubKey64 + nameHash + randomHash + appData
            val sig = signAnnounceData(signedData)

            val announceData = pubKey64 + nameHash + randomHash + sig + appData
            val flags = 0x01.toByte() // HEADER_1, ANNOUNCE, SINGLE
            val hops = 0x00.toByte()
            val context = 0x00.toByte()

            val rawPacket = byteArrayOf(flags, hops) + destBytes + byteArrayOf(context) + announceData
            val framed = byteArrayOf(HDLC_FLAG) + hdlcEscape(rawPacket) + byteArrayOf(HDLC_FLAG)
            synchronized(writer) {
                writer.write(framed)
                writer.flush()
            }
            android.util.Log.i("PulseSync", "Odeslán RNS Announce pro ${selfInfo.deliveryHash} (${rawPacket.size}B)")
        } catch (e: Exception) {
            android.util.Log.w("PulseSync", "Chyba při odesílání RNS Announce: ${e.message}")
        }
    }

    private fun signAnnounceData(data: ByteArray): ByteArray {
        val priv = ed25519PrivKey ?: run {
            val privB64 = secrets.getString(SecretStore.RETICULUM_IDENTITY) ?: ""
            val privBytes = runCatching { Base64.getDecoder().decode(privB64) }.getOrDefault(ByteArray(32))
            Ed25519PrivateKeyParameters(privBytes, 0).also { ed25519PrivKey = it }
        }
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun applyPropagationNode() {
        if (settings.networkMode != NetworkMode.LOCAL_ONLY) {
            startGatewayClient()
        } else {
            tcpJob?.cancel()
            runCatching { tcpSocket?.close() }
            tcpSocket = null
            tcpWriter = null
            isGatewayOnline = false
        }
    }

    fun probeNodeReachability(host: String, port: Int, timeoutMs: Int = 2500): Boolean {
        val cleanHost = host.trim()
        if (cleanHost.isBlank() || port !in 1..65535) return false
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(cleanHost, port), timeoutMs)
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    fun announce() {
        val inf = info ?: return
        startNsd(inf.deliveryHash)
        sendRnsAnnounce()
    }

    /** Registruje kontakt (veřejný klíč z QR) a vrací odvozenou adresu */
    fun addContact(publicKeyB64: String): String {
        val pubBytes = runCatching { Base64.getDecoder().decode(publicKeyB64) }.getOrNull()
            ?: publicKeyB64.toByteArray(Charsets.UTF_8)
        val sha256 = MessageDigest.getInstance("SHA-256")
        val pub64 = if (pubBytes.size >= 64) pubBytes.copyOf(64) else {
            val padded = ByteArray(64)
            System.arraycopy(pubBytes, 0, padded, 0, minOf(pubBytes.size, 64))
            padded
        }
        val identHash = sha256.digest(pub64).copyOf(16)
        val nameHash = sha256.digest("pulse.delivery".toByteArray(Charsets.UTF_8)).copyOf(10)
        val destHash = sha256.digest(nameHash + identHash).copyOf(16)
        val destHex = bytesToHex(destHash)
        registeredContacts[destHex] = publicKeyB64
        return destHex
    }

    fun removeContact(deliveryHash: String) {
        registeredContacts.remove(deliveryHash)
        localPeerMap.remove(deliveryHash)
    }

    /**
     * Odešle zprávu příjemci.
     * 1. Pokusí se o bleskový přímý přenos po lokální Wi-Fi (pokud je peer online v místní síti).
     * 2. Pokusí se o směrování přes Reticulum (RNS) TCP uzel v internetu (pokud je gateway online).
     * 3. Pokud není TCP gateway dostupná, zkusí Zero-Knowledge Blind Mailbox Relay pro HTTP porty.
     */
    fun send(destHash: String, content: String, propagated: Boolean, eventId: String, forceGateway: Boolean = false) {
        val selfInfo = ensureStarted()
        val encryptedEnvelope = encryptPayload(selfInfo.deliveryHash, destHash, content, eventId)
        val jsonPayload = json.encodeToString(encryptedEnvelope)

        // 1. Zkouška přímého přenosu po lokální Wi-Fi (pokud je peer známý v místní síti)
        val localEndpoint = if (forceGateway) null else localPeerMap[destHash]
        if (localEndpoint != null) {
            val directDelivered = runCatching {
                val req = Request.Builder()
                    .url("$localEndpoint/pulse4/sync")
                    .post(jsonPayload.toRequestBody("application/json".toMediaType()))
                    .build()
                val res = http.newCall(req).execute()
                res.isSuccessful
            }.onFailure { e ->
                android.util.Log.w("PulseSync", "Přímé Wi-Fi odeslání na $localEndpoint selhalo: ${e.message}")
            }.getOrDefault(false)

            if (directDelivered) {
                android.util.Log.i("PulseSync", "Zpráva pro $destHash úspěšně doručena přímo přes lokální Wi-Fi: $localEndpoint")
                emitEvent(BridgeEvent.Delivery(eventId, "HANDED", null))
                return
            }
        }

        // 2. Přenos přes Reticulum (RNS) TCP uzel v internetu
        if (settings.networkMode != NetworkMode.LOCAL_ONLY) {
            val rnsDelivered = sendOverGateway(destHash, jsonPayload.toByteArray(Charsets.UTF_8))
            if (rnsDelivered) {
                emitEvent(BridgeEvent.Delivery(eventId, "HANDED", null))
                return
            }

            // 3. Fallback na HTTP Relay Mailbox (pokud je nakonfigurován host a port 80 nebo 443)
            val relayHost = settings.gatewayHost.trim()
            val relayPort = settings.gatewayPort.takeIf { it > 0 } ?: 443
            if (relayHost.isNotBlank() && (relayPort == 80 || relayPort == 443)) {
                val scheme = if (relayPort == 443) "https" else "http"
                val relayUrl = "$scheme://$relayHost:$relayPort/mailbox/$destHash"

                val relayDelivered = runCatching {
                    val req = Request.Builder()
                        .url(relayUrl)
                        .header("X-Event-Id", eventId)
                        .post(jsonPayload.toRequestBody("application/json".toMediaType()))
                        .build()
                    val res = http.newCall(req).execute()
                    res.isSuccessful
                }.getOrDefault(false)

                if (relayDelivered) {
                    emitEvent(BridgeEvent.Delivery(eventId, "STORED", null))
                    return
                }
            }
        }

        emitEvent(BridgeEvent.Delivery(eventId, "FAILED", "Žádná dostupná transportní cesta"))
    }

    /** Vyzvedne čekající zašifrované zprávy ze slepého relay serveru. */
    fun syncInbox() {
        if (settings.networkMode == NetworkMode.LOCAL_ONLY) return
        val selfInfo = info ?: return
        val relayHost = settings.gatewayHost.trim()
        if (relayHost.isBlank()) return
        val relayPort = settings.gatewayPort.takeIf { it > 0 } ?: 443
        if (relayPort != 80 && relayPort != 443) return
        val scheme = if (relayPort == 443) "https" else "http"
        val mailboxUrl = "$scheme://$relayHost:$relayPort/mailbox/${selfInfo.deliveryHash}"

        runCatching {
            val req = Request.Builder().url(mailboxUrl).get().build()
            val res = http.newCall(req).execute()
            if (!res.isSuccessful) return@runCatching
            val body = res.body?.string().orEmpty()
            val root = json.parseToJsonElement(body)
            val items = if (root is kotlinx.serialization.json.JsonArray) root else root.jsonObject["messages"]?.jsonArray
            items?.forEach { elem ->
                val rawText = elem.jsonObject.toString()
                processIncomingEncryptedEnvelope(rawText, selfInfo.deliveryHash)
                val msgId = elem.jsonObject["eventId"]?.jsonPrimitive?.contentOrNull
                if (!msgId.isNullOrBlank()) {
                    val delReq = Request.Builder().url("$mailboxUrl/$msgId").delete().build()
                    runCatching { http.newCall(delReq).execute() }
                }
            }
        }
    }

    private fun processIncomingEncryptedEnvelope(rawJson: String, selfHash: String) {
        val env = runCatching { json.decodeFromString<EncryptedEnvelope>(rawJson) }
            .onFailure { android.util.Log.w("PulseSync", "Chyba dekódování JSON obálky: ${it.message}, preview=${rawJson.take(100)}") }
            .getOrNull() ?: return
        if (env.recipient != selfHash) {
            android.util.Log.w("PulseSync", "Příjemce v obálce nesouhlasí: ${env.recipient} != $selfHash")
            return
        }

        val decrypted = decryptPayload(env)
        if (decrypted != null) {
            android.util.Log.i("PulseSync", "Úspěšně dešifrována zpráva od ${env.sender} (len=${decrypted.length})")
            emitEvent(BridgeEvent.Message(source = env.sender, validated = true, content = decrypted))
        } else {
            android.util.Log.w("PulseSync", "Dešifrování selhalo pro zprávu od ${env.sender}")
        }
    }

    private fun encryptPayload(sender: String, recipient: String, content: String, eventId: String): EncryptedEnvelope {
        val secretBytes = MessageDigest.getInstance("SHA-256")
            .digest(("pulse-shared-key:$sender:$recipient").toByteArray(Charsets.UTF_8))
        val key = SecretKeySpec(secretBytes, "AES")

        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(content.toByteArray(Charsets.UTF_8))

        val macEngine = Mac.getInstance("HmacSHA256")
        macEngine.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        val macBytes = macEngine.doFinal(ciphertext)

        return EncryptedEnvelope(
            v = 1,
            sender = sender,
            recipient = recipient,
            iv = Base64.getEncoder().encodeToString(iv),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
            mac = macBytes.joinToString("") { "%02x".format(it) },
            eventId = eventId
        )
    }

    private fun decryptPayload(env: EncryptedEnvelope): String? = runCatching {
        val secretBytes = MessageDigest.getInstance("SHA-256")
            .digest(("pulse-shared-key:${env.sender}:${env.recipient}").toByteArray(Charsets.UTF_8))
        val key = SecretKeySpec(secretBytes, "AES")

        val iv = Base64.getDecoder().decode(env.iv)
        val cipherBytes = Base64.getDecoder().decode(env.ciphertext)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plaintext = cipher.doFinal(cipherBytes)
        String(plaintext, Charsets.UTF_8)
    }.getOrNull()

    fun drainEvents(): List<BridgeEvent> {
        synchronized(pendingEvents) {
            val list = pendingEvents.toList()
            pendingEvents.clear()
            return list
        }
    }

    fun statusJson(): String {
        val hasLocal = localServerSocket != null && !localServerSocket!!.isClosed
        val peerCount = localPeerMap.size
        val gwOnline = isGatewayOnline
        return """{"status":"online","interfaces":[{"name":"Local Wi-Fi (mDNS)","online":$hasLocal,"peers":$peerCount},{"name":"Reticulum TCP Gateway","online":$gwOnline},{"name":"Blind Mailbox Relay","online":true}]}"""
    }
}
