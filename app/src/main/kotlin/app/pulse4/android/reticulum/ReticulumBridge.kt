package app.pulse4.android.reticulum

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.data.Settings
import app.pulse4.android.security.SecretStore
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
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

data class NodeTestResult(
    val host: String,
    val port: Int,
    val reachable: Boolean,
    val latencyMs: Long,
    val detail: String,
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
 * 1. Plné end-to-end šifrování (E2EE v2: X25519 ECDH + Ed25519 podpis + AES-256-GCM).
 * 2. Tier 1: Lokální Wi-Fi přenos s nulovou konfigurací přes Android NSD (mDNS) a vestavěný mikro-server.
 * 3. Tier 2: Směrování přes Reticulum (RNS) TCP uzel v internetu s HDLC paketovým rámováním a nativní LXMF Propagation schránkou.
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
    private val legacyToPrimaryContact = ConcurrentHashMap<String, String>() // legacy pulse.delivery -> lxmf.delivery
    private val localPeerMap = ConcurrentHashMap<String, String>() // deliveryHash -> "http://ip:port"
    internal val routingState = GatewayRoutingState()

    private data class PendingDeposit(val eventId: String, val packetHash32: ByteArray, val sentAtMs: Long)
    private val pendingLinkProofs = ConcurrentHashMap<String, PendingDeposit>()
    @Volatile
    private var lastDepositEventId: String? = null
    private val cachedPropagationDeposits = ConcurrentHashMap<String, ByteArray>()
    private val downloadedTransientIds = LinkedHashSet<String>()
    private val pendingPurgeIds = LinkedHashSet<String>()
    private val getRequestLock = Any()
    private var pendingGetResponse: CompletableFuture<List<Any?>>? = null
    private var propagationKeepaliveJob: Job? = null
    private val localServerSemaphore = Semaphore(4)
    @Volatile
    private var legacyDeliveryHash: String? = null

    @Volatile
    var propagationNodeInfo: LxmfPropagationNodeInfo? = null
        private set
    @Volatile
    private var propagationLink: RnsLinkSession? = null
    @Volatile
    private var propagationLinkConnecting = false
    private val propagationLinkLock = Any()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var localServerSocket: ServerSocket? = null
    private var localServerJob: Job? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var nsdBurstJob: Job? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkWakeSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var tcpSocket: Socket? = null
    @Volatile
    private var tcpWriter: OutputStream? = null
    private var tcpJob: Job? = null
    @Volatile
    var isGatewayOnline = false
        private set
    val gatewayTransportId: ByteArray?
        get() = routingState.gatewayTransportId

    @Volatile
    internal var rawPacketSenderOverride: ((ByteArray) -> Boolean)? = null

    private var ed25519PrivKey: Ed25519PrivateKeyParameters? = null
    private var x25519PrivKey: X25519PrivateKeyParameters? = null
    private val nsdResolveLock = Any()
    private val nsdResolveQueue = ArrayDeque<NsdServiceInfo>()
    private var nsdResolving = false
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null

    var onEventReceived: ((BridgeEvent) -> Unit)? = null
    var onLocalPeerDiscovered: ((String) -> Unit)? = null

    fun localPeerHashes(): Set<String> = localPeerMap.keys.toSet()
    fun hasRegisteredContacts(): Boolean = registeredContacts.isNotEmpty()

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

    companion object {
        const val HDLC_FLAG = RnsCrypto.HDLC_FLAG
        const val HDLC_ESC = RnsCrypto.HDLC_ESC
        const val HDLC_ESC_MASK = RnsCrypto.HDLC_ESC_MASK
        const val MAX_EVENT_BYTES = 2 * 1024 * 1024
        const val MAX_LOCAL_EVENT_BYTES = 256 * 1024
        const val MAX_LOCAL_HEADER_BYTES = 8 * 1024
        const val MAX_LOCAL_HEADER_LINES = 64
        private val GATEWAY_BACKOFF_MS = longArrayOf(5_000L, 15_000L, 45_000L, 120_000L, 300_000L)

        fun hdlcEscape(data: ByteArray): ByteArray = RnsCrypto.hdlcEscape(data)

        fun hdlcUnescape(data: ByteArray): ByteArray = RnsCrypto.hdlcUnescape(data)

        fun hexToBytes(hex: String): ByteArray = RnsCrypto.hexToBytes(hex)

        fun bytesToHex(bytes: ByteArray): String = RnsCrypto.bytesToHex(bytes)
    }

    @Synchronized
    fun ensureStarted(): BridgeInfo {
        info?.let { return it }
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        if (settings.networkMode != NetworkMode.MANUAL) {
            acquireMulticast()
        }

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
        this.x25519PrivKey = xPriv
        val xPub = xPriv.generatePublicKey()

        // 64-bajtový veřejný klíč Reticulum (X25519 32B + Ed25519 32B)
        val pubKey64 = xPub.encoded + edPub.encoded
        val publicKeyB64 = Base64.getEncoder().encodeToString(pubKey64)

        // Reticulum Identity Hash (16 bajtů = 128 bitů)
        val sha256 = MessageDigest.getInstance("SHA-256")
        val identHash = sha256.digest(pubKey64).copyOf(16)
        val identityHex = bytesToHex(identHash)

        // Reticulum Destination Hash pro "lxmf.delivery" (16 bajtů = 128 bitů) + legacy "pulse.delivery" pro přechodný příjem
        val nameHash = sha256.digest(LxmfCodec.DELIVERY_APP_NAME.toByteArray(Charsets.UTF_8)).copyOf(10)
        val deliveryHashBytes = sha256.digest(nameHash + identHash).copyOf(16)
        val deliveryHex = bytesToHex(deliveryHashBytes)

        val legacyNameHash = sha256.digest(LxmfCodec.LEGACY_DELIVERY_APP_NAME.toByteArray(Charsets.UTF_8)).copyOf(10)
        legacyDeliveryHash = bytesToHex(sha256.digest(legacyNameHash + identHash).copyOf(16))

        startLocalServer(deliveryHex)

        val bi = BridgeInfo(
            deliveryHash = deliveryHex,
            identityHash = identityHex,
            publicKeyB64 = publicKeyB64,
            localPort = localServerSocket?.localPort
        )
        info = bi

        if (settings.networkMode != NetworkMode.MANUAL) {
            startNsd(bi.deliveryHash)
        }
        registerWifiCallback(bi.deliveryHash)
        registerDefaultNetworkCallback()

        if (settings.networkMode != NetworkMode.LOCAL_ONLY && settings.networkMode != NetworkMode.MANUAL) {
            startGatewayClient()
        }

        return bi
    }

    fun shutdown() = stop()

    @Synchronized
    fun stop() {
        releaseMulticast()
        stopNsdDiscovery()
        registrationListener?.let { old -> runCatching { nsdManager?.unregisterService(old) } }
        registrationListener = null
        synchronized(nsdResolveLock) {
            nsdResolveQueue.clear()
            nsdResolving = false
        }

        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        wifiNetworkCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        wifiNetworkCallback = null
        defaultNetworkCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        defaultNetworkCallback = null

        tcpJob?.cancel()
        tcpJob = null
        runCatching { tcpSocket?.close() }
        tcpSocket = null
        tcpWriter = null
        isGatewayOnline = false
        _gatewayHealth.value = GatewayHealth(state = GatewayHealthState.UNCONFIGURED)

        propagationKeepaliveJob?.cancel()
        propagationKeepaliveJob = null
        synchronized(propagationLinkLock) {
            propagationLink?.let { link ->
                link.buildLinkClosePacket()?.let { sendRawRnsPacket(it) }
                link.close()
            }
            propagationLink = null
            propagationLinkConnecting = false
        }
        routingState.onConnectionChanged(clearEndpoint = true)

        localServerJob?.cancel()
        localServerJob = null
        runCatching { localServerSocket?.close() }
        localServerSocket = null

        localPeerMap.clear()
        info = null
        scope.cancel()
    }

    private fun registerWifiCallback(selfHash: String) {
        if (wifiNetworkCallback != null) return
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (settings.networkMode != NetworkMode.MANUAL) {
                    acquireMulticast()
                    startNsd(selfHash)
                }
                networkWakeSignal.trySend(Unit)
            }
            override fun onLost(network: Network) {
                localPeerMap.clear()
                releaseMulticast()
            }
        }
        wifiNetworkCallback = cb
        runCatching { cm.registerNetworkCallback(req, cb) }
    }

    private fun registerDefaultNetworkCallback() {
        if (defaultNetworkCallback != null) return
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                routingState.onConnectionChanged()
                networkWakeSignal.trySend(Unit)
            }
            override fun onLost(network: Network) {
                routingState.onConnectionChanged()
            }
        }
        defaultNetworkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    private fun acquireMulticast() {
        if (multicastLock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("pulse4-sync").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseMulticast() {
        runCatching {
            multicastLock?.let { lock ->
                lock.setReferenceCounted(false)
                if (lock.isHeld) lock.release()
            }
        }
        multicastLock = null
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
                        if (!localServerSemaphore.tryAcquire()) {
                            runCatching { socket.close() }
                            continue
                        }
                        scope.launch {
                            try {
                                handleLocalSocket(socket, selfHash)
                            } finally {
                                localServerSemaphore.release()
                            }
                        }
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

    private fun readHttpLine(input: InputStream, maxBytes: Int = MAX_LOCAL_HEADER_BYTES): String? {
        val baos = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) return if (baos.size() == 0) null else baos.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                if (baos.size() >= maxBytes) throw IllegalArgumentException("HTTP header line exceeds $maxBytes bytes")
                baos.write(b)
            }
        }
        return baos.toString(Charsets.UTF_8.name())
    }

    private fun handleLocalSocket(socket: Socket, selfHash: String) {
        try {
            socket.soTimeout = 10_000
            val input = socket.getInputStream()
            var totalHeaderBytes = 0
            var headerLines = 0
            var line = readHttpLine(input, MAX_LOCAL_HEADER_BYTES) ?: return
            totalHeaderBytes += line.length + 2
            var contentLength = 0
            while (line.isNotEmpty()) {
                headerLines++
                if (headerLines > MAX_LOCAL_HEADER_LINES || totalHeaderBytes > MAX_LOCAL_HEADER_BYTES) {
                    val resp = "HTTP/1.1 431 Request Header Fields Too Large\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(resp.toByteArray(Charsets.UTF_8)); flush() }
                    return
                }
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                line = readHttpLine(input, MAX_LOCAL_HEADER_BYTES - totalHeaderBytes) ?: break
                totalHeaderBytes += line.length + 2
            }
            val out: OutputStream = socket.getOutputStream()
            if (contentLength <= 0) {
                val resp = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(resp.toByteArray(Charsets.UTF_8))
                out.flush()
                return
            }
            if (contentLength > MAX_LOCAL_EVENT_BYTES) {
                val resp = "HTTP/1.1 413 Payload Too Large\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(resp.toByteArray(Charsets.UTF_8))
                out.flush()
                return
            }
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val r = input.read(buf, read, contentLength - read)
                if (r == -1) break
                read += r
            }
            if (read != contentLength) {
                val resp = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(resp.toByteArray(Charsets.UTF_8))
                out.flush()
                return
            }
            val body = String(buf, 0, read, Charsets.UTF_8)
            val accepted = processIncomingEncryptedEnvelope(body, selfHash)
            val resp = if (accepted) {
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            } else {
                "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            }
            out.write(resp.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Exception) {
            android.util.Log.w("PulseSync", "Chyba obsluhy lokálního spojení: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Publikuje a vyhledává _pulse4._tcp služby v lokální Wi-Fi přes Android NSD (mDNS). */
    @Synchronized
    private fun startNsd(selfHash: String) {
        val server = localServerSocket ?: return
        val port = server.localPort
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = nsd

        discoveryListener?.let { old -> runCatching { nsd.stopServiceDiscovery(old) } }
        registrationListener?.let { old -> runCatching { nsd.unregisterService(old) } }
        synchronized(nsdResolveLock) {
            nsdResolveQueue.clear()
            nsdResolving = false
        }

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
                if (info.serviceType.contains("_pulse4") && !info.serviceName.endsWith(selfHash)) {
                    synchronized(nsdResolveLock) {
                        nsdResolveQueue.addLast(info)
                    }
                    drainNsdResolveQueue(nsd, selfHash)
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostHash = serviceInfo.serviceName.removePrefix("Pulse4-").trim()
                if (lostHash.isNotBlank()) {
                    localPeerMap.remove(lostHash)
                }
            }
        }
        runCatching { nsd.discoverServices("_pulse4._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
        nsdBurstJob?.cancel()
        nsdBurstJob = scope.launch {
            delay(15_000L)
            stopNsdDiscovery()
        }
    }

    @Synchronized
    private fun stopNsdDiscovery() {
        nsdBurstJob?.cancel()
        nsdBurstJob = null
        val nsd = nsdManager ?: return
        discoveryListener?.let { old -> runCatching { nsd.stopServiceDiscovery(old) } }
        discoveryListener = null
    }

    private fun drainNsdResolveQueue(nsd: NsdManager, selfHash: String) {
        val next = synchronized(nsdResolveLock) {
            if (nsdResolving || nsdResolveQueue.isEmpty()) return
            nsdResolving = true
            nsdResolveQueue.removeFirst()
        }
        runCatching {
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(nsdResolveLock) { nsdResolving = false }
                    drainNsdResolveQueue(nsd, selfHash)
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    runCatching {
                        val addrBytes = serviceInfo.attributes["addr"]
                        val peerHash = addrBytes?.let { String(it, Charsets.UTF_8).trim() }
                            ?: serviceInfo.serviceName.removePrefix("Pulse4-").trim()
                        val host = serviceInfo.host?.hostAddress
                        val peerPort = serviceInfo.port
                        if (peerHash.isNotBlank() && peerHash != selfHash && !host.isNullOrBlank() && peerPort > 0) {
                            val endpoint = "http://$host:$peerPort"
                            val prev = localPeerMap.put(peerHash, endpoint)
                            if (prev != endpoint) {
                                onLocalPeerDiscovered?.invoke(peerHash)
                            }
                        }
                    }
                    synchronized(nsdResolveLock) { nsdResolving = false }
                    drainNsdResolveQueue(nsd, selfHash)
                }
            })
        }.onFailure {
            synchronized(nsdResolveLock) { nsdResolving = false }
            drainNsdResolveQueue(nsd, selfHash)
        }
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
            routingState.onConnectionChanged(clearEndpoint = true)
            _gatewayHealth.value = GatewayHealth(state = GatewayHealthState.UNCONFIGURED)
            runCatching { oldSocket?.close() }
            return
        }
        val port = settings.gatewayPort.takeIf { it > 0 } ?: 4242

        if (tcpJob?.isActive == true &&
            isGatewayOnline &&
            tcpSocket?.isConnected == true &&
            tcpSocket?.isClosed == false &&
            routingState.isSameGatewayEndpoint(host, port)
        ) {
            return
        }

        tcpJob?.cancel()
        val oldSocket = tcpSocket
        tcpSocket = null
        tcpWriter = null
        isGatewayOnline = false
        routingState.onGatewayEndpointSelected(host, port)
        routingState.onConnectionChanged()
        _gatewayHealth.value = GatewayHealth(host = host, port = port, state = GatewayHealthState.CONNECTING)
        runCatching { oldSocket?.close() }

        tcpJob = scope.launch {
            var failures = 0
            while (isActive) {
                if (!hasActiveNetwork(host)) {
                    _gatewayHealth.value = GatewayHealth(
                        host = host,
                        port = port,
                        state = GatewayHealthState.UNREACHABLE,
                        consecutiveFailures = failures,
                        lastError = "Čeká na síť",
                    )
                    networkWakeSignal.receive()
                    continue
                }
                var currentSocket: Socket? = null
                var wasConnectedThisAttempt = false
                try {
                    android.util.Log.i("PulseSync", "Připojování k Reticulum gateway $host:$port...")
                    val s = Socket()
                    currentSocket = s
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    s.soTimeout = 120_000
                    s.connect(InetSocketAddress(host, port), 6000)
                    wasConnectedThisAttempt = true
                    failures = 0
                    synchronized(this@ReticulumBridge) {
                        tcpSocket = s
                        tcpWriter = s.getOutputStream()
                        isGatewayOnline = true
                    }
                    _gatewayHealth.value = GatewayHealth(host = host, port = port, state = GatewayHealthState.ONLINE)
                    android.util.Log.i("PulseSync", "Úspěšně připojeno k Reticulum gateway $host:$port")

                    // Odešli úvodní Announce paket a Path Request pro propagation uzel i registrované kontakty
                    sendRnsAnnounce()
                    val propHash = settings.propagationNode.trim()
                    if (propHash.length == 32) {
                        requestPathIfNeeded(propHash, force = true)
                    }
                    for (contactDest in registeredContacts.keys) {
                        if (!legacyToPrimaryContact.containsKey(contactDest)) {
                            requestPathIfNeeded(contactDest, force = true)
                        }
                    }

                    val inputStream = s.getInputStream()
                    val frameBuffer = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    val maxFrameBytes = 1024 * 1024
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
                            } else if (frameBuffer.size() < maxFrameBytes) {
                                frameBuffer.write(b.toInt())
                            } else {
                                // Ochrana proti přetečení paměti při poškozeném streamu (RNS 1.5 bounded deframer)
                                frameBuffer.reset()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (wasConnectedThisAttempt) {
                        // Běžné odpojení běžící relace (např. Doze / změna Wi-Fi) – nepovažovat hned za neexistující uzel
                        failures = 0
                        _gatewayHealth.value = GatewayHealth(host = host, port = port, state = GatewayHealthState.CONNECTING)
                    } else {
                        failures++
                        if (failures >= 3) {
                            _gatewayHealth.value = GatewayHealth(
                                host = host,
                                port = port,
                                state = GatewayHealthState.UNREACHABLE,
                                consecutiveFailures = failures,
                                lastError = e.message ?: "Spojení se nezdařilo",
                            )
                        }
                    }
                    android.util.Log.w("PulseSync", "Chyba Reticulum gateway spojení ($host:$port): ${e.message}")
                } finally {
                    synchronized(this@ReticulumBridge) {
                        if (tcpSocket === currentSocket) {
                            isGatewayOnline = false
                            tcpSocket = null
                            tcpWriter = null
                        }
                    }
                    propagationKeepaliveJob?.cancel()
                    propagationKeepaliveJob = null
                    synchronized(propagationLinkLock) {
                        propagationLink?.close()
                        propagationLink = null
                        propagationLinkConnecting = false
                    }
                    routingState.onConnectionChanged(clearEndpoint = true)
                    runCatching { currentSocket?.close() }
                }
                val backoffMs = if (failures > 0) {
                    GATEWAY_BACKOFF_MS[(failures - 1).coerceAtMost(GATEWAY_BACKOFF_MS.lastIndex)]
                } else {
                    GATEWAY_BACKOFF_MS[0]
                }
                withTimeoutOrNull(backoffMs) {
                    networkWakeSignal.receive()
                }
            }
        }
    }

    private fun hasActiveNetwork(host: String): Boolean {
        if (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) return true
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun handleIncomingRnsPacket(packet: ByteArray) {
        if (packet.size < 19) return
        val flags = packet[0].toInt() and 0xFF
        val headerType = (flags and 0x40) shr 6

        // Dynamicky zachytíme transport ID internetové brány z jakéhokoliv transport paketu
        if (headerType == 1 && packet.size >= 18) {
            val tId = packet.copyOfRange(2, 18)
            if (routingState.updateGatewayTransportId(tId)) {
                android.util.Log.i("PulseSync", "Zjištěno Transport ID internetové brány: ${bytesToHex(tId)}")
            }
        } else if (headerType == 0 && packet.size >= 51) {
            val destBytes = packet.copyOfRange(2, 18)
            val destHex = bytesToHex(destBytes)
            // Standardní rnstransport.path.request cíl (6b9f66014d9853faab220fba47d02761)
            // Payload začíná na indexu 19, transport ID brány je na offsetu 16..32 v payloadu (bajty 35..51 paketu)
            if (destHex.equals("6b9f66014d9853faab220fba47d02761", ignoreCase = true)) {
                val tId = packet.copyOfRange(35, 51)
                if (routingState.updateGatewayTransportId(tId)) {
                    android.util.Log.i("PulseSync", "Zjištěno Transport ID internetové brány z path request: ${bytesToHex(tId)}")
                }
            }
        }

        val packetType = flags and 0x03
        if (packetType == RnsPacketType.ANNOUNCE) {
            val parsed = RnsAnnounceParser.parse(packet)
            if (parsed != null && parsed.verified) {
                val destLower = parsed.destinationHash.lowercase()
                routingState.recordDiscoveredDestination(destLower, parsed.publicKey64)
                if (destLower == settings.propagationNode.trim().lowercase()) {
                    LxmfCodec.parsePropagationAnnounceAppData(parsed.appData)?.let { info ->
                        propagationNodeInfo = info
                        android.util.Log.i(
                            "PulseSync",
                            "Aktualizováno info LXMF propagation uzlu: accepting=${info.acceptingMessages}, stampCost=${info.stampCost}, limit=${info.perTransferLimitKb}KB"
                        )
                    }
                }
            }
            return
        }

        val destHex: String
        val contextByte: Byte
        val payload: ByteArray

        if (headerType == RnsHeaderType.HEADER_2) { // HEADER_2 (transport-routed paket s 16B transport_id)
            if (packet.size < 35) return
            val destBytes = packet.copyOfRange(18, 34)
            destHex = bytesToHex(destBytes)
            contextByte = packet[34]
            payload = packet.copyOfRange(35, packet.size)
        } else { // HEADER_1 (přímý 1-hop paket)
            val destBytes = packet.copyOfRange(2, 18)
            destHex = bytesToHex(destBytes)
            contextByte = packet[18]
            payload = packet.copyOfRange(19, packet.size)
        }

        if (packetType == RnsPacketType.PROOF) { // PROOF (0x03: LRPROOF nebo packet proof)
            val link = propagationLink
            if (link != null && destHex.equals(link.linkIdHex, ignoreCase = true)) {
                link.touchInbound()
                if (contextByte == RnsContext.LRPROOF) {
                    val rttPkt = link.handleLinkProof(payload)
                    if (rttPkt != null) {
                        sendRawRnsPacket(rttPkt)
                        startPropagationKeepalive(link)
                        android.util.Log.i("PulseSync", "RNS Link k LXMF uzlu aktivní (id=${link.linkIdHex}, mtu=${link.negotiatedMtu})")
                    }
                } else if (contextByte == RnsContext.NONE && payload.size >= 96) {
                    val pktHashHex = bytesToHex(payload.copyOfRange(0, 32))
                    val pending = pendingLinkProofs.remove(pktHashHex)
                    if (pending != null && link.verifyPacketProof(payload, pending.packetHash32)) {
                        cachedPropagationDeposits.remove(pending.eventId)
                        emitEvent(BridgeEvent.Delivery(pending.eventId, "STORED", null))
                    }
                }
            }
            return
        }

        if (packetType != RnsPacketType.DATA) return // LINKREQUEST / jiné řídicí pakety

        val activeLink = propagationLink
        if (activeLink != null && destHex.equals(activeLink.linkIdHex, ignoreCase = true)) {
            if (contextByte == RnsContext.KEEPALIVE) { // KEEPALIVE (0xFA) - nešifrovaný
                activeLink.touchInbound()
                if (payload.size == 1 && payload[0] == 0xFF.toByte()) {
                    activeLink.buildKeepaliveResponsePacket()?.let { sendRawRnsPacket(it) }
                }
                return
            }
            if (contextByte == RnsContext.LINKCLOSE) { // LINKCLOSE (0xFC) - jen po dešifrování a ověření link_id
                if (activeLink.verifyAndHandleLinkClose(payload)) {
                    synchronized(propagationLinkLock) {
                        if (propagationLink === activeLink) {
                            propagationLink = null
                            propagationLinkConnecting = false
                        }
                    }
                    propagationKeepaliveJob?.cancel()
                    propagationKeepaliveJob = null
                }
                return
            }
            val decrypted = activeLink.decryptLinkPayload(payload) ?: return
            handlePropagationLinkPayload(contextByte, decrypted)
            return
        }

        val selfHash = info?.deliveryHash ?: return
        val legacyHash = legacyDeliveryHash
        if (destHex.equals(selfHash, ignoreCase = true) ||
            (legacyHash != null && destHex.equals(legacyHash, ignoreCase = true))
        ) {
            val body = String(payload, Charsets.UTF_8)
            android.util.Log.i("PulseSync", "Příchozí RNS DATA paket určen pro naši adresu ($destHex, len=${payload.size}), předávám k dešifrování...")
            processIncomingEncryptedEnvelope(body, selfHash)
        } else {
            android.util.Log.d("PulseSync", "Přeskočen RNS paket pro jinou adresu: $destHex (naše=$selfHash)")
        }
    }

    private fun handlePropagationLinkPayload(contextByte: Byte, plaintext: ByteArray) {
        if (contextByte == RnsContext.NONE) {
            val list = runCatching { MsgPack.decode(plaintext).first as? List<*> }.getOrNull()
            val code = (list?.firstOrNull() as? Number)?.toInt()
            if (code == LxmfCodec.ERROR_INVALID_STAMP) {
                val evId = lastDepositEventId
                if (evId != null) {
                    cachedPropagationDeposits.remove(evId)
                    emitEvent(BridgeEvent.Delivery(evId, "FAILED", "ERROR_INVALID_STAMP"))
                }
                android.util.Log.w("PulseSync", "LXMF propagation node odmítl stamp (ERROR_INVALID_STAMP)")
            }
            return
        }
        if (contextByte == RnsContext.RESPONSE) { // RNS Link RESPONSE [requestId, responseData]
            val decoded = runCatching { MsgPack.decode(plaintext).first }.getOrNull() as? List<*> ?: return
            val responseData = decoded.getOrNull(1) as? List<*>
            synchronized(getRequestLock) {
                pendingGetResponse?.complete(responseData ?: emptyList<Any?>())
            }
        }
    }

    internal fun requestPathIfNeeded(
        destHashHex: String,
        force: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val pkt = routingState.buildPathRequestIfNeeded(
            destHashHex = destHashHex,
            isGatewayOnline = isGatewayOnline,
            force = force,
            nowMs = nowMs,
        ) ?: return false
        return sendRawRnsPacket(pkt).also { sent ->
            if (sent) routingState.markPathRequested(destHashHex, nowMs)
        }
    }

    private fun sendRawRnsPacket(rawPacket: ByteArray): Boolean {
        rawPacketSenderOverride?.let { return it(rawPacket) }
        val writer = tcpWriter ?: return false
        val s = tcpSocket ?: return false
        if (!s.isConnected || s.isClosed) return false
        return try {
            val framed = byteArrayOf(HDLC_FLAG) + hdlcEscape(rawPacket) + byteArrayOf(HDLC_FLAG)
            synchronized(writer) {
                writer.write(framed)
                writer.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startPropagationKeepalive(link: RnsLinkSession) {
        propagationKeepaliveJob?.cancel()
        propagationKeepaliveJob = scope.launch {
            while (isActive && link.state == RnsLinkSession.State.ACTIVE && propagationLink === link) {
                val intervalMs = link.keepaliveIntervalMs.coerceAtLeast(5_000L)
                val idleMs = System.currentTimeMillis() - link.lastActivityMs
                if (idleMs > intervalMs * 2 + 10_000L) {
                    link.close()
                    synchronized(propagationLinkLock) {
                        if (propagationLink === link) propagationLink = null
                    }
                    break
                }
                if (idleMs >= intervalMs) {
                    link.buildKeepalivePacket()?.let { sendRawRnsPacket(it) }
                }
                delay(minOf(intervalMs / 2, 10_000L).coerceAtLeast(1_000L))
            }
        }
    }

    private fun ensurePropagationLink(timeoutMs: Long = 2500L): RnsLinkSession? {
        val propHash = settings.propagationNode.trim().lowercase()
        if (propHash.length != 32 || !isGatewayOnline) return null

        var propPubKey = routingState.getDiscoveredPublicKey(propHash)
        if (propPubKey == null) {
            requestPathIfNeeded(propHash, force = true)
            val pathDeadline = System.currentTimeMillis() + 2000L
            while (System.currentTimeMillis() < pathDeadline) {
                propPubKey = routingState.getDiscoveredPublicKey(propHash)
                if (propPubKey != null) break
                Thread.sleep(50)
            }
            if (propPubKey == null) return null
        }

        val session: RnsLinkSession
        var isInitiator = false
        synchronized(propagationLinkLock) {
            val existing = propagationLink
            if (existing != null && existing.destinationHash.equals(propHash, ignoreCase = true)) {
                if (existing.state == RnsLinkSession.State.ACTIVE) {
                    return existing
                }
                if (existing.state == RnsLinkSession.State.PENDING && propagationLinkConnecting) {
                    session = existing
                } else {
                    session = RnsLinkSession(propHash, propPubKey)
                    propagationLink = session
                    propagationLinkConnecting = true
                    isInitiator = true
                }
            } else {
                session = RnsLinkSession(propHash, propPubKey)
                propagationLink = session
                propagationLinkConnecting = true
                isInitiator = true
            }
            if (isInitiator) {
                val reqPkt = session.buildLinkRequestPacket(gatewayTransportId)
                if (!sendRawRnsPacket(reqPkt)) {
                    propagationLinkConnecting = false
                    propagationLink = null
                    return null
                }
            }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (session.state == RnsLinkSession.State.ACTIVE) {
                if (isInitiator) {
                    synchronized(propagationLinkLock) {
                        if (propagationLink === session) propagationLinkConnecting = false
                    }
                }
                return session
            }
            Thread.sleep(50)
        }
        if (isInitiator) {
            synchronized(propagationLinkLock) {
                if (propagationLink === session) {
                    propagationLinkConnecting = false
                    if (session.state != RnsLinkSession.State.ACTIVE) {
                        session.close()
                        propagationLink = null
                    }
                }
            }
        }
        return session.takeIf { it.state == RnsLinkSession.State.ACTIVE }
    }

    private fun sendOverGateway(destHashHex: String, payloadBytes: ByteArray): Boolean {
        val writer = tcpWriter ?: return false
        val s = tcpSocket ?: return false
        if (!s.isConnected || s.isClosed) return false

        return try {
            requestPathIfNeeded(destHashHex)
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
                .digest(LxmfCodec.DELIVERY_APP_NAME.toByteArray(Charsets.UTF_8))
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

            // §8.4 #10: Prázdná app-data (ne displayName) pro ochranu soukromí ve veřejné síti
            val appData = ByteArray(0)

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
        if (settings.networkMode == NetworkMode.MANUAL) {
            releaseMulticast()
            stopNsdDiscovery()
            tcpJob?.cancel()
            runCatching { tcpSocket?.close() }
            tcpSocket = null
            tcpWriter = null
            isGatewayOnline = false
            routingState.onConnectionChanged(clearEndpoint = true)
            _gatewayHealth.value = GatewayHealth(state = GatewayHealthState.UNCONFIGURED)
            return
        }
        if (settings.networkMode != NetworkMode.LOCAL_ONLY) {
            startGatewayClient()
        } else {
            tcpJob?.cancel()
            runCatching { tcpSocket?.close() }
            tcpSocket = null
            tcpWriter = null
            isGatewayOnline = false
            routingState.onConnectionChanged(clearEndpoint = true)
            _gatewayHealth.value = GatewayHealth(state = GatewayHealthState.UNCONFIGURED)
        }
    }

    fun probeNodeReachability(host: String, port: Int, timeoutMs: Int = 2500): Boolean =
        testNodeConnection(host, port, timeoutMs).reachable

    fun testNodeConnection(host: String, port: Int, timeoutMs: Int = 3000): NodeTestResult {
        val cleanHost = host.trim()
        if (cleanHost.isBlank() || port !in 1..65535) {
            return NodeTestResult(cleanHost, port, false, 0L, "Neplatná adresa nebo port")
        }
        val startedNs = System.nanoTime()
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(cleanHost, port), timeoutMs)
            val ms = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
            NodeTestResult(cleanHost, port, true, ms, "TCP spojení OK ($ms ms)")
        } catch (e: Exception) {
            val ms = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            NodeTestResult(cleanHost, port, false, ms, reason)
        } finally {
            runCatching { socket.close() }
        }
    }

    fun announce() {
        val inf = info ?: return
        startNsd(inf.deliveryHash)
        sendRnsAnnounce()
    }

    /** Registruje kontakt (veřejný klíč z QR) a vrací odvozenou adresu lxmf.delivery */
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
        val nameHash = sha256.digest(LxmfCodec.DELIVERY_APP_NAME.toByteArray(Charsets.UTF_8)).copyOf(10)
        val destHash = sha256.digest(nameHash + identHash).copyOf(16)
        val destHex = bytesToHex(destHash)
        registeredContacts[destHex] = publicKeyB64

        val legacyNameHash = sha256.digest(LxmfCodec.LEGACY_DELIVERY_APP_NAME.toByteArray(Charsets.UTF_8)).copyOf(10)
        val legacyDestHex = bytesToHex(sha256.digest(legacyNameHash + identHash).copyOf(16))
        registeredContacts[legacyDestHex] = publicKeyB64
        legacyToPrimaryContact[legacyDestHex] = destHex
        if (isGatewayOnline && settings.networkMode != NetworkMode.LOCAL_ONLY) {
            requestPathIfNeeded(destHex)
        }
        return destHex
    }

    fun removeContact(deliveryHash: String) {
        registeredContacts.remove(deliveryHash)
        localPeerMap.remove(deliveryHash)
    }

    /**
     * Rychlá zkouška dostupnosti kontaktu ("test call") před odesláním dávky z fronty.
     * Ověří buď lokální Wi-Fi peer (TCP connect), nebo dostupnost RNS brány / LXMF propagace.
     */
    fun probeRecipient(destHash: String, usePropagation: Boolean, timeoutMs: Int = 1200): Boolean {
        val localEndpoint = localPeerMap[destHash]
        if (localEndpoint != null) {
            val reachable = runCatching {
                val uri = java.net.URI(localEndpoint)
                val port = if (uri.port > 0) uri.port else 80
                java.net.Socket().use { sock ->
                    sock.connect(java.net.InetSocketAddress(uri.host, port), timeoutMs)
                }
                true
            }.getOrDefault(false)
            if (reachable) return true
            localPeerMap.remove(destHash, localEndpoint)
        }
        if (settings.networkMode == NetworkMode.LOCAL_ONLY) return false
        if (isGatewayOnline) {
            requestPathIfNeeded(destHash)
        }
        val propReady = usePropagation && settings.propagationNode.trim().length == 32
        return isGatewayOnline && (propReady || registeredContacts.containsKey(destHash))
    }

    /**
     * Odešle zprávu příjemci.
     * 1. Pokusí se o bleskový přímý přenos po lokální Wi-Fi (pokud je peer online v místní síti).
     * 2. Pokusí se o směrování přes Reticulum (RNS) TCP uzel a volitelně uložení do LXMF Propagation uzlu.
     */
    fun send(destHash: String, content: String, propagated: Boolean, eventId: String, forceGateway: Boolean = false) {
        val selfInfo = ensureStarted()
        val encryptedEnvelope = encryptPayload(selfInfo.deliveryHash, destHash, content, eventId)
        val jsonPayload = json.encodeToString(encryptedEnvelope)

        // 1. Zkouška přímého přenosu po lokální Wi-Fi (raw Socket, nepodléhá Android cleartext HTTP omezení)
        val localEndpoint = if (forceGateway) null else localPeerMap[destHash]
        if (localEndpoint != null) {
            val directDelivered = sendLocalRawHttp(localEndpoint, jsonPayload)
            if (directDelivered) {
                android.util.Log.i("PulseSync", "Zpráva pro $destHash úspěšně doručena přímo přes lokální Wi-Fi: $localEndpoint")
                emitEvent(BridgeEvent.Delivery(eventId, "HANDED", null))
                return
            } else {
                // Odstranit mrtvý endpoint, aby další zprávy ve frontě nečekaly na opakované 5s timeouty
                localPeerMap.remove(destHash, localEndpoint)
            }
        }

        // 2. Přenos přes Reticulum (RNS) TCP uzel v internetu (přímý RNS paket + volitelně LXMF Propagation)
        if (settings.networkMode != NetworkMode.LOCAL_ONLY) {
            val payloadBytes = jsonPayload.toByteArray(Charsets.UTF_8)
            val rnsDelivered = sendOverGateway(destHash, payloadBytes)
            val propHash = settings.propagationNode.trim()
            val depositedToLink = if (propagated && propHash.length == 32 && isGatewayOnline) {
                sendViaLxmfPropagation(destHash, payloadBytes, eventId)
            } else {
                false
            }
            // STORED se emituje až po obdržení RNS PROOF paketu od uzlu; zápis na TCP je vždy jen HANDED.
            if (depositedToLink || rnsDelivered) {
                emitEvent(BridgeEvent.Delivery(eventId, "HANDED", null))
                return
            }
        }

        emitEvent(BridgeEvent.Delivery(eventId, "FAILED", "Žádná dostupná transportní cesta"))
    }

    private fun sendLocalRawHttp(localEndpoint: String, jsonPayload: String): Boolean {
        return runCatching {
            val uri = java.net.URI(localEndpoint)
            val host = uri.host ?: return false
            val port = if (uri.port > 0) uri.port else 80
            val bodyBytes = jsonPayload.toByteArray(Charsets.UTF_8)
            val header = "POST /pulse4/sync HTTP/1.1\r\nHost: $host:$port\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.UTF_8)
            Socket().use { socket ->
                socket.soTimeout = 8_000
                socket.connect(InetSocketAddress(host, port), 5_000)
                val out = socket.getOutputStream()
                out.write(header)
                out.write(bodyBytes)
                out.flush()
                val statusLine = readHttpLine(socket.getInputStream(), 1024) ?: return false
                statusLine.contains(" 200 ") || statusLine.contains(" 204 ")
            }
        }.onFailure { e ->
            android.util.Log.w("PulseSync", "Přímé Wi-Fi odeslání na $localEndpoint selhalo: ${e.message}")
        }.getOrDefault(false)
    }

    private fun sendViaLxmfPropagation(destHash: String, payloadBytes: ByteArray, eventId: String): Boolean = runCatching {
        val selfInfo = info ?: return false
        val edPriv = ed25519PrivKey ?: return false
        val recPub64B64 = registeredContacts[destHash] ?: return false
        val recPubBytes = Base64.getDecoder().decode(recPub64B64)
        if (recPubBytes.size < 64) return false
        val recXPub32 = recPubBytes.copyOfRange(0, 32)
        val recIdent16 = MessageDigest.getInstance("SHA-256").digest(recPubBytes.copyOf(64)).copyOf(16)

        val lxd = cachedPropagationDeposits[eventId] ?: run {
            val lxmfWire = LxmfCodec.pack(
                destinationHash = destHash,
                sourceHash = selfInfo.deliveryHash,
                edPriv = edPriv,
                content = payloadBytes,
            )
            val encrypted = LxmfCodec.encryptPropagationPayload(
                lxmfWire = lxmfWire,
                recipientX25519Pub32 = recXPub32,
                recipientIdentityHash16 = recIdent16,
            )
            val transientId = LxmfStamper.transientId(encrypted)
            val targetCost = propagationNodeInfo?.let { maxOf(0, it.stampCost - it.stampCostFlexibility) } ?: 13
            val stamp = runBlocking(Dispatchers.Default) {
                LxmfStamper.generateStamp(
                    material = transientId,
                    targetCost = targetCost,
                    expandRounds = LxmfStamper.PN_WORKBLOCK_ROUNDS,
                )
            } ?: return false
            val built = encrypted + stamp
            if (cachedPropagationDeposits.size > 256) {
                cachedPropagationDeposits.clear()
            }
            cachedPropagationDeposits[eventId] = built
            built
        }

        val depositEnvelope = LxmfCodec.packPropagationDepositRaw(lxd)
        val link = ensurePropagationLink() ?: return false
        val pkt = link.buildEncryptedLinkPacket(context = RnsContext.NONE, plaintext = depositEnvelope) ?: return false
        val pktHash = RnsCrypto.packetHash(pkt)
        val pktHashHex = bytesToHex(pktHash)
        pendingLinkProofs[pktHashHex] = PendingDeposit(eventId, pktHash, System.currentTimeMillis())
        lastDepositEventId = eventId
        sendRawRnsPacket(pkt).also { ok ->
            if (ok) {
                android.util.Log.i("PulseSync", "Odeslán LXMF deposit pro $destHash (${depositEnvelope.size}B, čeká na PROOF)")
            } else {
                pendingLinkProofs.remove(pktHashHex)
            }
        }
    }.getOrDefault(false)

    private fun awaitGetResponse(link: RnsLinkSession, reqData: List<Any?>, timeoutMs: Long = 5_000L): List<Any?>? {
        val future = CompletableFuture<List<Any?>>()
        synchronized(getRequestLock) {
            pendingGetResponse?.cancel(false)
            pendingGetResponse = future
        }
        val (_, reqPkt) = link.buildRequestPacket(LxmfCodec.MESSAGE_GET_PATH, reqData) ?: return null
        if (!sendRawRnsPacket(reqPkt)) return null
        return runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    /** Vyzvedne čekající zašifrované zprávy z LXMF propagation uzlu přes aktivní RNS Link (dvoufázový /get + purge). */
    fun syncInbox(onEnvelope: ((BridgeEvent.Message) -> Boolean)? = null) {
        if (settings.networkMode == NetworkMode.LOCAL_ONLY) return
        val propHash = settings.propagationNode.trim()
        if (propHash.length != 32 || !isGatewayOnline) return
        val selfInfo = info ?: return
        val xPriv = x25519PrivKey ?: return
        val selfIdBytes = hexToBytes(selfInfo.identityHash)
        val link = ensurePropagationLink() ?: return
        if (!link.identified) {
            val xPub = xPriv.generatePublicKey().encoded
            val edPriv = ed25519PrivKey
            val edPub = edPriv?.generatePublicKey()?.encoded
            if (edPriv != null && edPub != null) {
                link.buildIdentifyPacket(xPub + edPub, edPriv)?.let { idPkt ->
                    sendRawRnsPacket(idPkt)
                }
            }
        }

        // Fáze 1: [null, null] -> seznam dostupných transient ID
        val availableList = awaitGetResponse(link, LxmfCodec.buildGetMessagesRequest(null, null)) ?: return
        val availableIds = availableList.mapNotNull { it as? ByteArray }
        val wants = synchronized(downloadedTransientIds) {
            availableIds.filter { bytesToHex(it) !in downloadedTransientIds }.take(32)
        }
        val haves = synchronized(pendingPurgeIds) {
            pendingPurgeIds.map { hexToBytes(it) }
        }
        if (wants.isEmpty() && haves.isEmpty()) return

        // Fáze 2: [wants, haves] -> stažení nových zpráv a purge již zpracovaných
        val responseMessages = awaitGetResponse(link, LxmfCodec.buildGetMessagesRequest(wants, haves)) ?: return
        if (haves.isNotEmpty()) {
            synchronized(pendingPurgeIds) {
                haves.forEach { pendingPurgeIds.remove(bytesToHex(it)) }
            }
        }
        for (item in responseMessages) {
            val storedBytes = item as? ByteArray ?: continue
            val tidHex = bytesToHex(LxmfStamper.transientId(storedBytes))
            val lxmfWire = LxmfCodec.unpackPropagationMessage(storedBytes, xPriv, selfIdBytes) ?: continue
            val msg = LxmfCodec.unpack(lxmfWire) ?: continue
            val senderPub = registeredContacts[msg.sourceHash]?.let { decodeContactPubKey64(it) }
            val verified = senderPub?.let { LxmfCodec.unpack(lxmfWire, it.second.encoded)?.signatureValid } ?: false
            if (senderPub != null && verified) {
                val body = String(msg.content, Charsets.UTF_8)
                val decoded = decodeIncomingEncryptedEnvelope(body, selfInfo.deliveryHash) ?: continue
                val accepted = if (onEnvelope != null) {
                    onEnvelope(decoded)
                } else {
                    emitEvent(decoded)
                    true
                }
                if (accepted) {
                    synchronized(downloadedTransientIds) {
                        downloadedTransientIds.add(tidHex)
                        if (downloadedTransientIds.size > 1024) {
                            downloadedTransientIds.iterator().let { it.next(); it.remove() }
                        }
                    }
                    synchronized(pendingPurgeIds) {
                        pendingPurgeIds.add(tidHex)
                    }
                }
            }
        }
    }

    private fun decodeIncomingEncryptedEnvelope(rawJson: String, selfHash: String): BridgeEvent.Message? {
        val env = runCatching { json.decodeFromString<EncryptedEnvelope>(rawJson) }
            .onFailure { android.util.Log.w("PulseSync", "Chyba dekódování JSON obálky: ${it.message}") }
            .getOrNull() ?: return null
        if (env.recipient != selfHash && env.recipient != legacyDeliveryHash) {
            android.util.Log.w("PulseSync", "Příjemce v obálce nesouhlasí: ${env.recipient} != $selfHash")
            return null
        }

        val decrypted = decryptPayload(env)
        val normalizedSource = legacyToPrimaryContact[env.sender] ?: env.sender
        return if (decrypted != null) {
            android.util.Log.i("PulseSync", "Úspěšně dešifrována zpráva od $normalizedSource (v=${env.v}, len=${decrypted.length})")
            BridgeEvent.Message(source = normalizedSource, validated = true, content = decrypted)
        } else {
            android.util.Log.w("PulseSync", "Dešifrování nebo ověření podpisu selhalo pro zprávu od $normalizedSource")
            null
        }
    }

    private fun processIncomingEncryptedEnvelope(rawJson: String, selfHash: String): Boolean {
        val msg = decodeIncomingEncryptedEnvelope(rawJson, selfHash) ?: return false
        emitEvent(msg)
        return true
    }

    private fun decodeContactPubKey64(pubKeyB64: String): Pair<X25519PublicKeyParameters, Ed25519PublicKeyParameters>? = runCatching {
        val raw = Base64.getDecoder().decode(pubKeyB64)
        if (raw.size < 64) return null
        X25519PublicKeyParameters(raw, 0) to Ed25519PublicKeyParameters(raw, 32)
    }.getOrNull()

    private fun deriveE2eeAesKey(sharedSecret: ByteArray, sender: String, recipient: String): ByteArray {
        val (a, b) = if (sender <= recipient) sender to recipient else recipient to sender
        val md = MessageDigest.getInstance("SHA-256")
        md.update(sharedSecret)
        md.update("pulse4-e2ee-v2".toByteArray(Charsets.UTF_8))
        md.update(a.toByteArray(Charsets.UTF_8))
        md.update(b.toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    private fun canonicalSignatureInput(sender: String, recipient: String, ivB64: String, ciphertextB64: String, eventId: String?): ByteArray =
        "pulse4-v2:$sender:$recipient:$ivB64:$ciphertextB64:${eventId ?: ""}".toByteArray(Charsets.UTF_8)

    internal fun encryptPayload(sender: String, recipient: String, content: String, eventId: String): EncryptedEnvelope {
        val recipientPub = registeredContacts[recipient]?.let { decodeContactPubKey64(it) }
        val xPriv = x25519PrivKey
        val edPriv = ed25519PrivKey
        if (recipientPub == null || xPriv == null || edPriv == null) {
            throw IllegalStateException("Unknown recipient public key for E2EE v2")
        }
        val agreement = X25519Agreement()
        agreement.init(xPriv)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(recipientPub.first, sharedSecret, 0)
        val aesBytes = deriveE2eeAesKey(sharedSecret, sender, recipient)
        val key = SecretKeySpec(aesBytes, "AES")

        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(content.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val ctB64 = Base64.getEncoder().encodeToString(ciphertext)

        val sigInput = canonicalSignatureInput(sender, recipient, ivB64, ctB64, eventId)
        val signer = Ed25519Signer()
        signer.init(true, edPriv)
        signer.update(sigInput, 0, sigInput.size)
        val sigBytes = signer.generateSignature()

        return EncryptedEnvelope(
            v = 2,
            sender = sender,
            recipient = recipient,
            iv = ivB64,
            ciphertext = ctB64,
            mac = bytesToHex(sigBytes),
            eventId = eventId
        )
    }

    internal fun decryptPayload(env: EncryptedEnvelope): String? = runCatching {
        when (env.v) {
            2 -> {
                val senderPub = registeredContacts[env.sender]?.let { decodeContactPubKey64(it) } ?: return null
                val xPriv = x25519PrivKey ?: return null
                val sigBytes = hexToBytes(env.mac)
                if (sigBytes.size != 64) return null
                val sigInput = canonicalSignatureInput(env.sender, env.recipient, env.iv, env.ciphertext, env.eventId)
                val verifier = Ed25519Signer()
                verifier.init(false, senderPub.second)
                verifier.update(sigInput, 0, sigInput.size)
                if (!verifier.verifySignature(sigBytes)) return null

                val agreement = X25519Agreement()
                agreement.init(xPriv)
                val sharedSecret = ByteArray(agreement.agreementSize)
                agreement.calculateAgreement(senderPub.first, sharedSecret, 0)
                val aesBytes = deriveE2eeAesKey(sharedSecret, env.sender, env.recipient)
                val key = SecretKeySpec(aesBytes, "AES")

                val iv = Base64.getDecoder().decode(env.iv)
                val cipherBytes = Base64.getDecoder().decode(env.ciphertext)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            }
            else -> null
        }
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
        val lxmfReady = propagationLink?.state == RnsLinkSession.State.ACTIVE
        return """{"status":"online","interfaces":[{"name":"Local Wi-Fi (mDNS)","online":$hasLocal,"peers":$peerCount},{"name":"Reticulum TCP Gateway","online":$gwOnline},{"name":"LXMF Propagation","online":$lxmfReady}]}"""
    }
}
