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
import app.pulse4.domain.PLog
import app.pulse4.sync.E2eeEnvelope
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
import java.util.concurrent.atomic.AtomicBoolean
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
import kotlinx.coroutines.withContext
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

enum class PropagationAccessState { UNKNOWN, OK, NO_IDENTITY, NO_ACCESS, THROTTLED, UNUSABLE_STAMP_COST }

data class PropagationNodeStatus(
    val state: PropagationAccessState = PropagationAccessState.UNKNOWN,
    val detail: String? = null,
    val stampCost: Int? = null,
    val throttledUntilMs: Long = 0L,
)

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

internal typealias EncryptedEnvelope = E2eeEnvelope

/**
 * Nativní Kotlin transportní most (PulseSync):
 * 1. Plné end-to-end šifrování (E2EE v3: ephemeral X25519 + HKDF-SHA256 + Ed25519 podpis + AES-256-GCM + SEC-3 metadata protection; zpětně kompatibilní s v2).
 * 2. Tier 1: Lokální Wi-Fi přenos s nulovou konfigurací přes Android NSD (mDNS) a vestavěný mikro-server.
 * 3. Tier 2: Směrování přes Reticulum (RNS) TCP uzel v internetu s HDLC paketovým rámováním a nativní LXMF Propagation schránkou.
 */
class ReticulumBridge internal constructor(
    private val context: Context?,
    private val secrets: SecretStore?,
    private val settings: Settings?,
    private val filesDirOverride: java.io.File?,
    private val identitySeedOverride: ByteArray?,
    private val propagationNodeOverride: (() -> String)?,
    private val networkModeOverride: (() -> NetworkMode)?,
    /** L3 (R4-3): brána před prvním startem – ServiceLocator zde čeká na dokončení startovní migrace. */
    private val beforeStart: () -> Unit = {},
) {
    constructor(
        context: Context,
        secrets: SecretStore,
        settings: Settings,
        beforeStart: () -> Unit = {},
    ) : this(
        context = context,
        secrets = secrets,
        settings = settings,
        filesDirOverride = null,
        identitySeedOverride = null,
        propagationNodeOverride = null,
        networkModeOverride = null,
        beforeStart = beforeStart,
    )

    private fun currentNetworkMode(): NetworkMode = networkModeOverride?.invoke() ?: settings?.networkMode ?: NetworkMode.MANUAL
    private fun currentPropagationNode(): String = propagationNodeOverride?.invoke() ?: settings?.propagationNode ?: ""
    private fun currentGatewayHost(): String = settings?.gatewayHost ?: ""
    private fun currentGatewayPort(): Int = settings?.gatewayPort ?: 4242
    private fun systemService(name: String): Any? = context?.applicationContext?.getSystemService(name)
    private fun resolveFilesDir(): java.io.File = filesDirOverride ?: context!!.filesDir
    private fun readIdentitySecret(): String? =
        identitySeedOverride?.let { Base64.getEncoder().encodeToString(it) } ?: secrets?.getString(SecretStore.RETICULUM_IDENTITY)
    private fun writeIdentitySecret(b64: String) {
        secrets?.putString(SecretStore.RETICULUM_IDENTITY, b64)
    }
    @Volatile
    var info: BridgeInfo? = null
        private set

    private val _gatewayHealth = MutableStateFlow(GatewayHealth())
    val gatewayHealth: StateFlow<GatewayHealth> get() = _gatewayHealth

    private val _propagationStatus = MutableStateFlow(PropagationNodeStatus())
    val propagationStatus: StateFlow<PropagationNodeStatus> get() = _propagationStatus

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

    internal sealed interface GetResponse {
        data class Messages(val items: List<Any?>) : GetResponse
        data class Error(val code: Int) : GetResponse
        data object NullOrInvalid : GetResponse
    }

    private data class PendingDeposit(
        val eventId: String,
        val packetHash32: ByteArray,
        val sentAtMs: Long,
        val linkIdHex: String,
        val cacheKey: String,
    )

    private data class PostProofDeposit(
        val eventId: String,
        val linkIdHex: String,
        val cacheKey: String,
        val provedAtMs: Long,
    )

    private val pendingLinkProofs = ConcurrentHashMap<String, PendingDeposit>()
    private val pendingPostProofDeposits = ConcurrentHashMap<String, PostProofDeposit>()
    private val activeLinkPacketDeposits = ConcurrentHashMap<String, MutableList<Pair<String, String>>>() // linkIdHex -> [(eventId, cacheKey)]
    private val cachedPropagationDeposits = ConcurrentHashMap<String, ByteArray>() // cacheKey -> lxd bytes
    private val eventToCacheKeys = ConcurrentHashMap<String, MutableSet<String>>() // eventId -> set of cacheKeys
    private val depositBuildLocks = ConcurrentHashMap<String, Any>()
    @Volatile
    private var postProofGraceOverrideMs: Long? = null
    internal var postProofGraceMs: Long
        get() = postProofGraceOverrideMs ?: computePostProofGraceMs(propagationLink)
        set(value) {
            postProofGraceOverrideMs = value
        }

    internal fun computePostProofGraceMs(link: RnsLinkSession?): Long {
        val linkRttMs = link?.linkRttMs ?: 0L
        return maxOf(2_000L, 4L * linkRttMs)
    }

    @Volatile
    private var lastCachePruneMs: Long = 0L
    private val downloadedTransientIds = LinkedHashSet<String>()
    private val pendingPurgeIds = LinkedHashSet<String>()
    private val getRequestLock = Any()
    private var pendingGetResponse: CompletableFuture<GetResponse>? = null
    private var pendingGetRequestId: ByteArray? = null
    private val resourceCoordinator = LinkResourceCoordinator().apply {
        accessGuard = {
            check(onResourceThread.get() == true) {
                "LinkResourceCoordinator accessed off resourceDispatcher on thread ${Thread.currentThread().name}"
            }
        }
    }
    private val resourceDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val onResourceThread = ThreadLocal<Boolean>()

    private inline fun <T> runOnResourceDispatcher(crossinline block: () -> T): T {
        if (onResourceThread.get() == true) {
            return block()
        }
        return runBlocking(resourceDispatcher) {
            onResourceThread.set(true)
            try {
                block()
            } finally {
                onResourceThread.remove()
            }
        }
    }

    private fun launchOnResourceDispatcher(block: () -> Unit) {
        scope.launch(resourceDispatcher) {
            onResourceThread.set(true)
            try {
                block()
            } finally {
                onResourceThread.remove()
            }
        }
    }

    private suspend inline fun <T> withResourceDispatcher(crossinline block: () -> T): T {
        if (onResourceThread.get() == true) {
            return block()
        }
        return withContext(resourceDispatcher) {
            onResourceThread.set(true)
            try {
                block()
            } finally {
                onResourceThread.remove()
            }
        }
    }
    private var propagationKeepaliveJob: Job? = null
    @Volatile
    private var lastPropagationInboundMs: Long = 0L
    @Volatile
    private var propagationFractionalStampCost: Int? = null
    @Volatile
    private var propagationThrottledUntilMs: Long = 0L
    @Volatile
    private var throttleBackoffMs: Long = 0L
    private val bridgeStopped = AtomicBoolean(false)
    private val localServerSemaphore = Semaphore(4)
    @Volatile
    private var legacyDeliveryHash: String? = null

    /** Info z announce propagation uzlu + hash uzlu, ke kterému patří (R4-8: při změně PN neplatí). */
    @Volatile
    private var propagationNodeInfoEntry: Pair<String, LxmfPropagationNodeInfo>? = null

    val propagationNodeInfo: LxmfPropagationNodeInfo?
        get() {
            val entry = propagationNodeInfoEntry ?: return null
            if (entry.first == currentPropagationNode().trim().lowercase()) return entry.second
            // Uživatel přepnul na jiný uzel: limity / cena stampu starého uzlu už neplatí.
            clearPropagationNodeInfo()
            return null
        }

    private fun clearPropagationNodeInfo() {
        propagationNodeInfoEntry = null
        propagationFractionalStampCost = null
    }

    /** Sekvenční fronta událostí (R4-8): HANDED / FAILED / zprávy se doručí v pořadí vzniku. */
    private val eventDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** eventId → FAILED emitovaný během aktuálního pokusu o odeslání (pozdní HANDED se pak neemituje). */
    private val failedDuringSend = ConcurrentHashMap<String, Boolean>()
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

    internal fun emitEvent(ev: BridgeEvent) {
        if (ev is BridgeEvent.Delivery && ev.state == "FAILED") {
            if (failedDuringSend.size > 4096) failedDuringSend.clear()
            failedDuringSend[ev.eventId] = true
        }
        val handler = onEventReceived
        if (handler != null) {
            // Jedna sekvenční fronta: pořadí emitování = pořadí zpracování (FAILED nepředběhne HANDED).
            scope.launch(eventDispatcher) {
                try {
                    handler(ev)
                } catch (e: Throwable) {
                    PLog.e("PulseSync", e) { "Chyba obsluhy onEventReceived" }
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

        /**
         * Derives `(lxmf.delivery, pulse.delivery)` destination hashes from a 64-byte Base64 public key (RE-1).
         */
        fun deriveDeliveryHashes(publicKeyB64: String): Pair<String, String> {
            val pubBytes = runCatching { Base64.getDecoder().decode(publicKeyB64) }.getOrNull()
                ?: publicKeyB64.toByteArray(Charsets.UTF_8)
            val sha256 = MessageDigest.getInstance("SHA-256")
            val pub64 = if (pubBytes.size >= 64) pubBytes.copyOf(64) else {
                val padded = ByteArray(64)
                System.arraycopy(pubBytes, 0, padded, 0, minOf(pubBytes.size, 64))
                padded
            }
            val identHash = sha256.digest(pub64).copyOf(16)
            val lxmfNameHash = sha256.digest(LxmfCodec.DELIVERY_APP_NAME.toByteArray(Charsets.UTF_8)).copyOf(10)
            val lxmfDestHex = bytesToHex(sha256.digest(lxmfNameHash + identHash).copyOf(16))
            val legacyNameHash = sha256.digest(LxmfCodec.LEGACY_DELIVERY_APP_NAME.toByteArray(Charsets.UTF_8)).copyOf(10)
            val legacyDestHex = bytesToHex(sha256.digest(legacyNameHash + identHash).copyOf(16))
            return lxmfDestHex to legacyDestHex
        }

        fun derivePrimaryDeliveryHash(publicKeyB64: String): String = deriveDeliveryHashes(publicKeyB64).first
    }

    /**
     * Spustí most (idempotentně). Před prvním startem projde branou [beforeStart] mimo zámek mostu,
     * takže se most nikdy nespustí před dokončením startovní migrace (L3).
     */
    fun ensureStarted(): BridgeInfo {
        info?.let { return it }
        beforeStart()
        return ensureStartedLocked()
    }

    @Synchronized
    private fun ensureStartedLocked(): BridgeInfo {
        info?.let { return it }
        bridgeStopped.set(false)
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        pruneDepositCacheIfDue()
        if (currentNetworkMode() != NetworkMode.MANUAL) {
            acquireMulticast()
        }

        var privateKeyB64 = readIdentitySecret()
        if (privateKeyB64 == null) {
            val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            privateKeyB64 = Base64.getEncoder().encodeToString(seed)
            writeIdentitySecret(privateKeyB64)
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

        if (currentNetworkMode() != NetworkMode.MANUAL) {
            startNsd(bi.deliveryHash)
        }
        registerWifiCallback(bi.deliveryHash)
        registerDefaultNetworkCallback()

        if (currentNetworkMode() != NetworkMode.LOCAL_ONLY && currentNetworkMode() != NetworkMode.MANUAL) {
            startGatewayClient()
        }

        return bi
    }

    fun shutdown() = stop()

    @Synchronized
    fun stop() {
        bridgeStopped.set(true)
        releaseMulticast()
        stopNsdDiscovery()
        registrationListener?.let { old -> runCatching { nsdManager?.unregisterService(old) } }
        registrationListener = null
        synchronized(nsdResolveLock) {
            nsdResolveQueue.clear()
            nsdResolving = false
        }

        val cm = systemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
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
        clearLinkTracking(emitFailures = false)
        clearResourceCoordinator(emitFailures = false)
        synchronized(getRequestLock) {
            pendingGetResponse?.cancel(false)
            pendingGetResponse = null
            pendingGetRequestId = null
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
        val cm = systemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (currentNetworkMode() != NetworkMode.MANUAL) {
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
        val cm = systemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
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
        val wifi = systemService(Context.WIFI_SERVICE) as? WifiManager ?: return
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
            PLog.i("PulseSync") { "Lokální server spuštěn na portu ${server.localPort}" }
        } catch (e: Exception) {
            PLog.w("PulseSync") { "Nelze spustit lokální server: ${e.message}" }
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
            PLog.w("PulseSync") { "Chyba obsluhy lokálního spojení: ${e.message}" }
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Publikuje a vyhledává _pulse4._tcp služby v lokální Wi-Fi přes Android NSD (mDNS). */
    @Synchronized
    private fun startNsd(selfHash: String) {
        val server = localServerSocket ?: return
        val port = server.localPort
        val nsd = systemService(Context.NSD_SERVICE) as? NsdManager ?: return
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
        PLog.i("PulseSync") { "Manuálně zaregistrován peer endpoint: $destHash -> $endpointUrl" }
    }

    fun clearLocalPeers() {
        localPeerMap.clear()
        PLog.i("PulseSync") { "Vyčištěna tabulka lokálních peerů" }
    }

    /** Spustí klientské spojení k Reticulum TCP uzlu v internetu */
    @Synchronized
    fun startGatewayClient() {
        val host = currentGatewayHost().trim()
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
        val port = currentGatewayPort().takeIf { it > 0 } ?: 4242

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
                    PLog.i("PulseSync") { "Připojování k Reticulum gateway $host:$port..." }
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
                    PLog.i("PulseSync") { "Úspěšně připojeno k Reticulum gateway $host:$port" }

                    // Odešli úvodní Announce paket a Path Request pro propagation uzel i registrované kontakty
                    sendRnsAnnounce()
                    val propHash = currentPropagationNode().trim()
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
                                    try {
                                        handleIncomingRnsPacket(unescaped, dispatchResourceAsync = true)
                                    } catch (t: Throwable) {
                                        PLog.e("PulseSync", t) { "Chyba zpracování příchozího RNS paketu" }
                                    }
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
                    PLog.w("PulseSync") { "Chyba Reticulum gateway spojení (${e.javaClass.simpleName})" }
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
                    clearLinkTracking(emitFailures = true, failureReason = "LINK_CLOSED")
                    clearResourceCoordinator(emitFailures = true, failureReason = "LINK_CLOSED")
                    synchronized(getRequestLock) {
                        pendingGetResponse?.cancel(false)
                        pendingGetResponse = null
                        pendingGetRequestId = null
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
        val cm = systemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    internal fun handleIncomingRnsPacketForTest(packet: ByteArray) {
        try {
            handleIncomingRnsPacket(packet, dispatchResourceAsync = false)
        } catch (t: Throwable) {
            PLog.e("PulseSync", t) { "Chyba testovacího zpracování RNS paketu" }
        }
    }

    private fun isResourceLinkContext(contextByte: Byte): Boolean = when (contextByte) {
        RnsContext.RESOURCE,
        RnsContext.RESOURCE_ADV,
        RnsContext.RESOURCE_REQ,
        RnsContext.RESOURCE_HMU,
        RnsContext.RESOURCE_ICL,
        RnsContext.RESOURCE_RCL -> true
        else -> false
    }

    private fun handleIncomingRnsPacket(packet: ByteArray, dispatchResourceAsync: Boolean = false) {
        if (packet.size < 19) return
        val flags = packet[0].toInt() and 0xFF
        val headerType = (flags and 0x40) shr 6

        // Dynamicky zachytíme transport ID internetové brány z jakéhokoliv transport paketu
        if (headerType == 1 && packet.size >= 18) {
            val tId = packet.copyOfRange(2, 18)
            if (routingState.updateGatewayTransportId(tId)) {
                PLog.i("PulseSync") { "Zjištěno Transport ID internetové brány: ${bytesToHex(tId)}" }
            }
        } else if (headerType == 0 && packet.size >= 51) {
            val destBytes = packet.copyOfRange(2, 18)
            val destHex = bytesToHex(destBytes)
            // Standardní rnstransport.path.request cíl (6b9f66014d9853faab220fba47d02761)
            // Payload začíná na indexu 19, transport ID brány je na offsetu 16..32 v payloadu (bajty 35..51 paketu)
            if (destHex.equals("6b9f66014d9853faab220fba47d02761", ignoreCase = true)) {
                val tId = packet.copyOfRange(35, 51)
                if (routingState.updateGatewayTransportId(tId)) {
                    PLog.i("PulseSync") { "Zjištěno Transport ID internetové brány z path request: ${bytesToHex(tId)}" }
                }
            }
        }

        val packetType = flags and 0x03
        if (packetType == RnsPacketType.ANNOUNCE) {
            val parsed = RnsAnnounceParser.parse(packet)
            if (parsed != null && parsed.verified) {
                val destLower = parsed.destinationHash.lowercase()
                routingState.recordDiscoveredDestination(destLower, parsed.publicKey64)
                if (destLower == currentPropagationNode().trim().lowercase()) {
                    LxmfCodec.parsePropagationAnnounceAppData(parsed.appData)?.let { info ->
                        propagationNodeInfoEntry = destLower to info
                        val rawList = runCatching { MsgPack.decode(parsed.appData).first as? List<*> }.getOrNull()
                        val fracList = rawList?.getOrNull(7) as? List<*>
                        val fracCost = (fracList?.getOrNull(0) as? Number)?.toDouble()
                        propagationFractionalStampCost = fracCost?.let { kotlin.math.ceil(it).toInt() }
                        val effectiveCost = effectiveRequiredStampCost(info)
                        if (effectiveCost > LxmfStamper.MAX_ACCEPTABLE_STAMP_COST) {
                            _propagationStatus.value = PropagationNodeStatus(
                                state = PropagationAccessState.UNUSABLE_STAMP_COST,
                                detail = "Požadovaná cena stampu ($effectiveCost) překračuje limit ${LxmfStamper.MAX_ACCEPTABLE_STAMP_COST}",
                                stampCost = effectiveCost,
                            )
                        } else if (_propagationStatus.value.state == PropagationAccessState.UNUSABLE_STAMP_COST) {
                            _propagationStatus.value = PropagationNodeStatus(
                                state = PropagationAccessState.UNKNOWN,
                                stampCost = effectiveCost,
                            )
                        }
                        PLog.i("PulseSync") {
                            "Aktualizováno info LXMF propagation uzlu: accepting=${info.acceptingMessages}, stampCost=${info.stampCost}, transferLimit=${info.perTransferLimitKb}KB, syncLimit=${info.perSyncLimitKb}KB"
                        }
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
                lastPropagationInboundMs = link.lastInboundMs
                if (contextByte == RnsContext.LRPROOF) {
                    val rttPkt = link.handleLinkProof(payload)
                    if (rttPkt != null) {
                        sendRawRnsPacket(rttPkt)
                        startPropagationKeepalive(link)
                        PLog.i("PulseSync") { "RNS Link k LXMF uzlu aktivní (id=${link.linkIdHex}, mtu=${link.negotiatedMtu})" }
                    }
                } else if (contextByte == RnsContext.NONE && payload.size >= 96) {
                    val pktHashHex = bytesToHex(payload.copyOfRange(0, 32))
                    val pending = pendingLinkProofs.remove(pktHashHex)
                    if (pending != null && pending.linkIdHex == link.linkIdHex && link.verifyPacketProof(payload, pending.packetHash32)) {
                        removeActivePacketDeposit(link.linkIdHex, pending.eventId)
                        removeCachedDepositByKey(pending.cacheKey, pending.eventId)
                        emitEvent(BridgeEvent.Delivery(pending.eventId, "STORED", null))
                    }
                } else if (contextByte == RnsContext.RESOURCE_PRF) {
                    val handlePrf = {
                        try {
                            handleInboundResourceProof(link, contextByte, payload)
                        } catch (t: Throwable) {
                            PLog.e("PulseSync", t) { "Chyba zpracování RESOURCE_PRF" }
                        }
                    }
                    if (dispatchResourceAsync) {
                        launchOnResourceDispatcher { handlePrf() }
                    } else {
                        runOnResourceDispatcher { handlePrf() }
                    }
                }
            }
            return
        }

        if (packetType != RnsPacketType.DATA) return // LINKREQUEST / jiné řídicí pakety

        val activeLink = propagationLink
        if (activeLink != null && destHex.equals(activeLink.linkIdHex, ignoreCase = true)) {
            activeLink.touchInbound()
            lastPropagationInboundMs = activeLink.lastInboundMs
            if (contextByte == RnsContext.KEEPALIVE) { // KEEPALIVE (0xFA) - nešifrovaný
                if (payload.size == 1 && payload[0] == 0xFF.toByte()) {
                    activeLink.buildKeepaliveResponsePacket()?.let { sendRawRnsPacket(it) }
                }
                return
            }
            if (contextByte == RnsContext.LINKCLOSE) { // LINKCLOSE (0xFC) - jen po dešifrování a ověření link_id
                val handleClose = {
                    try {
                        if (activeLink.verifyAndHandleLinkClose(payload)) {
                            tearDownPropagationLink(activeLink, remoteClosed = true)
                        }
                    } catch (t: Throwable) {
                        PLog.e("PulseSync", t) { "Chyba zpracování LINKCLOSE" }
                    }
                }
                if (dispatchResourceAsync) {
                    launchOnResourceDispatcher { handleClose() }
                } else {
                    runOnResourceDispatcher { handleClose() }
                }
                return
            }
            if (isResourceLinkContext(contextByte)) {
                val pktHash = runCatching { RnsCrypto.packetHash(packet) }.getOrNull()
                val handleRes = {
                    try {
                        val (consumedResource, completedResource) = resourceCoordinator.handleLinkData(
                            link = activeLink,
                            contextByte = contextByte,
                            rawPayload = payload,
                            packetHash = pktHash,
                            onFailure = ::handleResourceFailure,
                            sendPacket = ::sendRawRnsPacket,
                        )
                        if (consumedResource) {
                            completedResource?.let { handleCompletedInboundResource(it) }
                        }
                    } catch (t: Throwable) {
                        PLog.e("PulseSync", t) { "Chyba zpracování RNS Resource paketu" }
                    }
                }
                if (dispatchResourceAsync) {
                    launchOnResourceDispatcher { handleRes() }
                } else {
                    runOnResourceDispatcher { handleRes() }
                }
                return
            }
            val decrypted = activeLink.decryptLinkPayload(payload) ?: return
            handlePropagationLinkPayload(activeLink, contextByte, decrypted)
            return
        }

        val selfHash = info?.deliveryHash ?: return
        val legacyHash = legacyDeliveryHash
        if (destHex.equals(selfHash, ignoreCase = true) ||
            (legacyHash != null && destHex.equals(legacyHash, ignoreCase = true))
        ) {
            val body = String(payload, Charsets.UTF_8)
            PLog.i("PulseSync") { "Příchozí RNS DATA paket určen pro naši adresu ($destHex, len=${payload.size}), předávám k dešifrování..." }
            processIncomingEncryptedEnvelope(body, selfHash)
        } else {
            PLog.d("PulseSync") { "Přeskočen RNS paket pro jinou adresu: $destHex (naše=$selfHash)" }
        }
    }

    private fun handleInboundResourceProof(link: RnsLinkSession, contextByte: Byte, payload: ByteArray) {
        val resProof = resourceCoordinator.handleProof(
            contextByte = contextByte,
            payload = payload,
            onFailure = ::handleResourceFailure,
            sendPacket = ::sendRawRnsPacket,
        ) ?: return
        val cacheKey = eventToCacheKeys[resProof.eventId]?.firstOrNull() ?: ""
        val postProof = PostProofDeposit(
            eventId = resProof.eventId,
            linkIdHex = link.linkIdHex,
            cacheKey = cacheKey,
            provedAtMs = System.currentTimeMillis(),
        )
        pendingPostProofDeposits[resProof.resourceHashHex] = postProof
        val graceMs = postProofGraceOverrideMs ?: computePostProofGraceMs(link)
        if (graceMs <= 0L) {
            pendingPostProofDeposits.remove(resProof.resourceHashHex)
            if (link.state == RnsLinkSession.State.ACTIVE && propagationLink === link) {
                if (cacheKey.isNotEmpty()) removeCachedDepositByKey(cacheKey, resProof.eventId)
                emitEvent(BridgeEvent.Delivery(resProof.eventId, "STORED", null))
            }
        } else {
            scope.launch {
                delay(graceMs)
                val stillPending = pendingPostProofDeposits.remove(resProof.resourceHashHex) ?: return@launch
                val currentLink = propagationLink
                if (currentLink != null &&
                    currentLink === link &&
                    currentLink.linkIdHex == stillPending.linkIdHex &&
                    currentLink.state == RnsLinkSession.State.ACTIVE
                ) {
                    if (stillPending.cacheKey.isNotEmpty()) {
                        removeCachedDepositByKey(stillPending.cacheKey, stillPending.eventId)
                    }
                    emitEvent(BridgeEvent.Delivery(stillPending.eventId, "STORED", null))
                }
            }
        }
    }

    private fun parseGetResponseValue(raw: Any?): GetResponse {
        return when (raw) {
            is Number -> GetResponse.Error(raw.toInt())
            is List<*> -> GetResponse.Messages(raw)
            else -> GetResponse.NullOrInvalid
        }
    }

    private fun handleCompletedInboundResource(result: LinkResourceCoordinator.ResourceInboundResult) {
        if (result.isResponse) {
            val decoded = runCatching { MsgPack.decode(result.data).first }.getOrNull()
            val decodedList = decoded as? List<*>
            val embeddedReqId = (decodedList?.takeIf { it.size == 2 && it[0] is ByteArray }?.get(0)) as? ByteArray
            val actualId = result.requestId ?: embeddedReqId
            val payloadValue: Any? = if (decodedList != null && decodedList.size == 2 && decodedList[0] is ByteArray) {
                decodedList[1]
            } else {
                decoded
            }
            val parsedResponse = parseGetResponseValue(payloadValue)
            var matched = false
            synchronized(getRequestLock) {
                val expectedId = pendingGetRequestId
                if (expectedId != null && actualId != null && MessageDigest.isEqual(expectedId, actualId)) {
                    pendingGetRequestId = null
                    pendingGetResponse?.complete(parsedResponse)
                    pendingGetResponse = null
                    matched = true
                }
            }
            if (matched) {
                runOnResourceDispatcher { resourceCoordinator.clearPendingResponse(actualId) }
            }
            return
        }

        val selfHash = info?.deliveryHash ?: return
        val lxmfMsg = LxmfCodec.unpack(result.data)
        if (lxmfMsg != null) {
            val senderPub = registeredContacts[lxmfMsg.sourceHash]?.let { decodeContactPubKey64(it) }
            val verified = senderPub?.let { LxmfCodec.unpack(result.data, it.second.encoded)?.signatureValid } ?: false
            if (senderPub != null && verified) {
                processIncomingEncryptedEnvelope(String(lxmfMsg.content, Charsets.UTF_8), selfHash)
            }
            return
        }
        processIncomingEncryptedEnvelope(String(result.data, Charsets.UTF_8), selfHash)
    }

    private fun handlePropagationLinkPayload(link: RnsLinkSession, contextByte: Byte, plaintext: ByteArray) {
        if (contextByte == RnsContext.NONE) {
            val list = runCatching { MsgPack.decode(plaintext).first as? List<*> }.getOrNull()
            val code = (list?.firstOrNull() as? Number)?.toInt()
            if (code == LxmfCodec.ERROR_INVALID_STAMP) {
                val affected = mutableListOf<Pair<String, String>>()
                activeLinkPacketDeposits.remove(link.linkIdHex)?.let { affected.addAll(it) }
                pendingLinkProofs.entries.removeIf { entry ->
                    if (entry.value.linkIdHex == link.linkIdHex) {
                        affected.add(entry.value.eventId to entry.value.cacheKey)
                        true
                    } else false
                }
                pendingPostProofDeposits.entries.removeIf { entry ->
                    if (entry.value.linkIdHex == link.linkIdHex) {
                        affected.add(entry.value.eventId to entry.value.cacheKey)
                        true
                    } else false
                }
                for ((evId, cacheKey) in affected.distinctBy { it.first }) {
                    removeCachedDepositByKey(cacheKey, evId)
                    emitEvent(BridgeEvent.Delivery(evId, "FAILED", "ERROR_INVALID_STAMP"))
                }
                PLog.w("PulseSync") { "LXMF propagation node odmítl stamp (ERROR_INVALID_STAMP)" }
            }
            return
        }
        if (contextByte == RnsContext.RESPONSE) { // RNS Link RESPONSE [requestId, responseData]
            val decoded = runCatching { MsgPack.decode(plaintext).first }.getOrNull() as? List<*> ?: return
            val respReqId = decoded.getOrNull(0) as? ByteArray
            val parsedResponse = parseGetResponseValue(decoded.getOrNull(1))
            var matched = false
            synchronized(getRequestLock) {
                val expectedId = pendingGetRequestId
                if (expectedId != null && respReqId != null && MessageDigest.isEqual(expectedId, respReqId)) {
                    pendingGetRequestId = null
                    pendingGetResponse?.complete(parsedResponse)
                    pendingGetResponse = null
                    matched = true
                }
            }
            if (matched) {
                runOnResourceDispatcher { resourceCoordinator.clearPendingResponse(respReqId) }
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

    private fun clearLinkTracking(
        linkIdHex: String? = null,
        emitFailures: Boolean = false,
        failureReason: String = "LINK_CLOSED",
        deleteCacheOnPostProofFailure: Boolean = false,
    ) {
        val failedProofs = mutableListOf<PendingDeposit>()
        pendingLinkProofs.entries.removeIf { entry ->
            if (linkIdHex == null || entry.value.linkIdHex == linkIdHex) {
                failedProofs.add(entry.value)
                true
            } else {
                false
            }
        }
        val failedPostProofs = mutableListOf<PostProofDeposit>()
        pendingPostProofDeposits.entries.removeIf { entry ->
            if (linkIdHex == null || entry.value.linkIdHex == linkIdHex) {
                failedPostProofs.add(entry.value)
                true
            } else {
                false
            }
        }
        if (linkIdHex == null) {
            activeLinkPacketDeposits.clear()
        } else {
            activeLinkPacketDeposits.remove(linkIdHex)
        }
        if (emitFailures) {
            for (p in failedProofs) {
                emitEvent(BridgeEvent.Delivery(p.eventId, "FAILED", failureReason))
            }
            for (p in failedPostProofs) {
                if (deleteCacheOnPostProofFailure && p.cacheKey.isNotEmpty()) {
                    removeCachedDepositByKey(p.cacheKey, p.eventId)
                }
                emitEvent(BridgeEvent.Delivery(p.eventId, "FAILED", failureReason))
            }
        }
    }

    private fun removeActivePacketDeposit(linkIdHex: String, eventId: String) {
        activeLinkPacketDeposits[linkIdHex]?.let { list ->
            synchronized(list) {
                list.removeIf { it.first == eventId }
            }
        }
    }

    private fun handleResourceFailure(f: LinkResourceCoordinator.ResourceFailedResult) {
        if (f.eventId != null) {
            pendingPostProofDeposits.entries.removeIf { it.value.eventId == f.eventId }
            emitEvent(BridgeEvent.Delivery(f.eventId, "FAILED", f.reason))
        }
        if (f.isResponse) {
            synchronized(getRequestLock) {
                val expectedId = pendingGetRequestId
                if (expectedId != null && f.requestId != null && MessageDigest.isEqual(expectedId, f.requestId)) {
                    pendingGetResponse?.cancel(false)
                    pendingGetResponse = null
                    pendingGetRequestId = null
                }
            }
        }
    }

    private fun clearResourceCoordinator(emitFailures: Boolean, failureReason: String = "LINK_TEARDOWN") {
        val cancelled = runOnResourceDispatcher { resourceCoordinator.clear(failureReason) }
        if (emitFailures) {
            for (f in cancelled) {
                handleResourceFailure(f)
            }
        }
    }

    private fun tearDownPropagationLink(link: RnsLinkSession? = null, remoteClosed: Boolean = false) {
        val closedLinkId: String?
        synchronized(propagationLinkLock) {
            val current = propagationLink ?: return
            if (link != null && current !== link) return
            closedLinkId = current.linkIdHex
            if (!remoteClosed && current.state == RnsLinkSession.State.ACTIVE) {
                current.buildLinkClosePacket()?.let { sendRawRnsPacket(it) }
            }
            current.close()
            propagationLink = null
            propagationLinkConnecting = false
        }
        val reason = if (remoteClosed) "LINK_CLOSED_AFTER_PROOF" else "LINK_TEARDOWN"
        clearLinkTracking(
            linkIdHex = closedLinkId,
            emitFailures = true,
            failureReason = reason,
            deleteCacheOnPostProofFailure = remoteClosed,
        )
        clearResourceCoordinator(emitFailures = true, failureReason = reason)
        synchronized(getRequestLock) {
            pendingGetResponse?.cancel(false)
            pendingGetResponse = null
            pendingGetRequestId = null
        }
        propagationKeepaliveJob?.cancel()
        propagationKeepaliveJob = null
    }

    private fun startPropagationKeepalive(link: RnsLinkSession) {
        lastPropagationInboundMs = link.lastInboundMs
        propagationKeepaliveJob?.cancel()
        propagationKeepaliveJob = scope.launch {
            while (isActive && link.state == RnsLinkSession.State.ACTIVE && propagationLink === link) {
                val now = System.currentTimeMillis()
                val intervalMs = link.keepaliveIntervalMs.coerceAtLeast(5_000L)
                // NEW-4 / N-13: rely on RnsLinkSession.lastInboundMs for stale detection
                val inboundIdleMs = now - link.lastInboundMs
                if (inboundIdleMs > intervalMs * 2 + 10_000L) {
                    tearDownPropagationLink(link)
                    break
                }
                // NEW-4: tear down link if an outbound deposit PROOF is missing beyond 30s on THIS link
                val staleProof = pendingLinkProofs.entries.firstOrNull {
                    it.value.linkIdHex == link.linkIdHex && now - it.value.sentAtMs > 30_000L
                }
                if (staleProof != null) {
                    pendingLinkProofs.remove(staleProof.key)
                    emitEvent(BridgeEvent.Delivery(staleProof.value.eventId, "FAILED", "PROOF_TIMEOUT"))
                    tearDownPropagationLink(link)
                    break
                }
                // R3-10 / N-10: Resource watchdog tick exclusively on single-threaded resourceDispatcher
                val (resourceFailures, hasActiveResources) = withResourceDispatcher {
                    val failures = resourceCoordinator.tickWatchdog(now, ::sendRawRnsPacket)
                    val active = resourceCoordinator.activeOutboundCount() > 0 || resourceCoordinator.activeInboundCount() > 0
                    failures to active
                }
                for (f in resourceFailures) {
                    handleResourceFailure(f)
                }
                if (now - link.lastActivityMs >= intervalMs) {
                    link.buildKeepalivePacket()?.let { sendRawRnsPacket(it) }
                }
                val sleepMs = if (hasActiveResources) {
                    500L
                } else {
                    minOf(intervalMs / 2, 10_000L).coerceAtLeast(1_000L)
                }
                delay(sleepMs)
            }
        }
    }

    private fun ensurePropagationLink(timeoutMs: Long = 2500L): RnsLinkSession? {
        val propHash = currentPropagationNode().trim().lowercase()
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

        var session: RnsLinkSession? = null
        var isInitiator = false
        while (true) {
            var oldLinkToTearDown: RnsLinkSession? = null
            synchronized(propagationLinkLock) {
                val existing = propagationLink
                if (existing != null && existing.destinationHash.equals(propHash, ignoreCase = true)) {
                    if (existing.state == RnsLinkSession.State.ACTIVE) {
                        return existing
                    }
                    if (existing.state == RnsLinkSession.State.PENDING && propagationLinkConnecting) {
                        session = existing
                    } else {
                        oldLinkToTearDown = existing
                    }
                } else if (existing != null) {
                    oldLinkToTearDown = existing
                } else {
                    val newSession = RnsLinkSession(propHash, propPubKey)
                    propagationLink = newSession
                    propagationLinkConnecting = true
                    session = newSession
                    isInitiator = true
                }
                if (isInitiator) {
                    val reqPkt = session!!.buildLinkRequestPacket(gatewayTransportId)
                    if (!sendRawRnsPacket(reqPkt)) {
                        propagationLinkConnecting = false
                        propagationLink = null
                        return null
                    }
                }
            }
            if (oldLinkToTearDown != null) {
                tearDownPropagationLink(oldLinkToTearDown)
                continue
            }
            break
        }
        val activeSession = session ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (activeSession.state == RnsLinkSession.State.ACTIVE) {
                if (isInitiator) {
                    synchronized(propagationLinkLock) {
                        if (propagationLink === activeSession) propagationLinkConnecting = false
                    }
                }
                return activeSession
            }
            Thread.sleep(50)
        }
        if (isInitiator) {
            synchronized(propagationLinkLock) {
                if (propagationLink === activeSession) {
                    propagationLinkConnecting = false
                    if (activeSession.state != RnsLinkSession.State.ACTIVE) {
                        activeSession.close()
                        propagationLink = null
                    }
                }
            }
        }
        return activeSession.takeIf { it.state == RnsLinkSession.State.ACTIVE }
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
            PLog.i("PulseSync") { "Odeslán RNS paket ${rawPacket.size}B pro $destHashHex přes internet uzel (routed=${tId != null})" }
            true
        } catch (e: Exception) {
            PLog.w("PulseSync") { "Selhalo odeslání RNS paketu přes uzel: ${e.message}" }
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
            PLog.i("PulseSync") { "Odeslán RNS Announce pro ${selfInfo.deliveryHash} (${rawPacket.size}B)" }
        } catch (e: Exception) {
            PLog.w("PulseSync") { "Chyba při odesílání RNS Announce: ${e.message}" }
        }
    }

    /**
     * Detached Ed25519 signature over [bytes] with the own identity key (guardian-consent decisions, revocations
     * and age releases carry their own domain header inside [bytes]). Starts the bridge first so the key is loaded.
     */
    fun signDetached(bytes: ByteArray): ByteArray {
        ensureStarted()
        val priv = synchronized(this) { ed25519PrivKey } ?: error("Own identity key is not available")
        return app.pulse4.sync.E2eeCrypto.signDetached(priv.encoded, bytes)
    }

    private fun signAnnounceData(data: ByteArray): ByteArray {
        val priv = ed25519PrivKey ?: run {
            val privB64 = readIdentitySecret() ?: ""
            val privBytes = runCatching { Base64.getDecoder().decode(privB64) }.getOrDefault(ByteArray(32))
            Ed25519PrivateKeyParameters(privBytes, 0).also { ed25519PrivKey = it }
        }
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun applyPropagationNode() {
        if (currentNetworkMode() == NetworkMode.MANUAL) {
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
        if (currentNetworkMode() != NetworkMode.LOCAL_ONLY) {
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
        val (destHex, legacyDestHex) = deriveDeliveryHashes(publicKeyB64)
        registeredContacts[destHex] = publicKeyB64
        registeredContacts[legacyDestHex] = publicKeyB64
        legacyToPrimaryContact[legacyDestHex] = destHex
        if (isGatewayOnline && currentNetworkMode() != NetworkMode.LOCAL_ONLY) {
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
        if (currentNetworkMode() == NetworkMode.LOCAL_ONLY) return false
        if (isGatewayOnline) {
            requestPathIfNeeded(destHash)
        }
        val propReady = usePropagation && currentPropagationNode().trim().length == 32
        return isGatewayOnline && (propReady || registeredContacts.containsKey(destHash))
    }

    /**
     * Odešle zprávu příjemci.
     * 1. Pokusí se o bleskový přímý přenos po lokální Wi-Fi (pokud je peer online v místní síti).
     * 2. Pokusí se o směrování přes Reticulum (RNS) TCP uzel a volitelně uložení do LXMF Propagation uzlu.
     */
    fun send(destHash: String, content: String, propagated: Boolean, eventId: String, forceGateway: Boolean = false) {
        val selfInfo = ensureStarted()
        failedDuringSend.remove(eventId)
        val encryptedEnvelope = encryptPayload(selfInfo.deliveryHash, destHash, content, eventId)
        val jsonPayload = BridgeEnvelopeCrypto.encode(encryptedEnvelope)

        // 1. Zkouška přímého přenosu po lokální Wi-Fi (raw Socket, nepodléhá Android cleartext HTTP omezení)
        val localEndpoint = if (forceGateway) null else localPeerMap[destHash]
        if (localEndpoint != null) {
            val directDelivered = sendLocalRawHttp(localEndpoint, jsonPayload)
            if (directDelivered) {
                PLog.i("PulseSync") { "Zpráva pro $destHash úspěšně doručena přímo přes lokální Wi-Fi: $localEndpoint" }
                purgeDepositCache(eventId, destHash)
                emitEvent(BridgeEvent.Delivery(eventId, "HANDED", null))
                return
            } else {
                // Odstranit mrtvý endpoint, aby další zprávy ve frontě nečekaly na opakované 5s timeouty
                localPeerMap.remove(destHash, localEndpoint)
            }
        }

        // 2. Přenos přes Reticulum (RNS) TCP uzel v internetu (přímý RNS paket + volitelně LXMF Propagation)
        if (currentNetworkMode() != NetworkMode.LOCAL_ONLY) {
            val payloadBytes = jsonPayload.toByteArray(Charsets.UTF_8)
            val propHash = currentPropagationNode().trim()
            val propResult = if (propagated && propHash.length == 32 && isGatewayOnline) {
                sendViaLxmfPropagationDetailed(destHash, payloadBytes, eventId)
            } else {
                DepositAttemptResult.SKIPPED
            }
            if (propResult == DepositAttemptResult.FAILED_EMITTED) {
                return
            }
            val rnsDelivered = sendOverGateway(destHash, payloadBytes)
            // STORED se emituje až po obdržení RNS PROOF paketu od uzlu; zápis na TCP je vždy jen HANDED.
            if (propResult == DepositAttemptResult.STARTED || rnsDelivered) {
                // R4-8: uzel mohl deposit odmítnout (RCL) dřív, než jsme se sem dostali – pak HANDED neemitovat.
                if (failedDuringSend.remove(eventId) != null && !rnsDelivered) {
                    PLog.i("PulseSync") { "LXMF deposit $eventId selhal před HANDED, HANDED se neemituje" }
                    return
                }
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
            PLog.w("PulseSync") { "Přímé Wi-Fi odeslání selhalo (${e.javaClass.simpleName})" }
        }.getOrDefault(false)
    }

    private fun depositCacheDir(): java.io.File =
        java.io.File(resolveFilesDir(), "rns_deposit_cache").apply { mkdirs() }

    private fun depositCacheKey(eventId: String, recipientHash: String, pnHash: String): String {
        val raw = "$eventId:${recipientHash.lowercase()}:${pnHash.lowercase()}"
        return bytesToHex(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)))
    }

    private fun depositCacheFileByKey(cacheKey: String): java.io.File =
        java.io.File(depositCacheDir(), "$cacheKey.lxd")

    private fun getCachedDepositByKey(cacheKey: String, eventId: String): ByteArray? {
        cachedPropagationDeposits[cacheKey]?.let { return it }
        val f = runCatching { depositCacheFileByKey(cacheKey) }.getOrNull() ?: return null
        if (!f.exists()) return null
        return runCatching { f.readBytes() }.getOrNull()?.also {
            cachedPropagationDeposits[cacheKey] = it
            eventToCacheKeys.computeIfAbsent(eventId) { ConcurrentHashMap.newKeySet() }.add(cacheKey)
        }
    }

    private fun putCachedDepositByKey(cacheKey: String, eventId: String, bytes: ByteArray) {
        if (cachedPropagationDeposits.size > 256) {
            cachedPropagationDeposits.clear()
        }
        cachedPropagationDeposits[cacheKey] = bytes
        eventToCacheKeys.computeIfAbsent(eventId) { ConcurrentHashMap.newKeySet() }.add(cacheKey)
        runCatching { depositCacheFileByKey(cacheKey).writeBytes(bytes) }
    }

    private fun removeCachedDepositByKey(cacheKey: String, eventId: String? = null) {
        if (cacheKey.isNotEmpty()) {
            cachedPropagationDeposits.remove(cacheKey)
            runCatching { depositCacheFileByKey(cacheKey).delete() }
        }
        if (eventId != null && cacheKey.isNotEmpty()) {
            eventToCacheKeys[eventId]?.remove(cacheKey)
        }
    }

    fun purgeDepositCache(eventId: String, recipientHash: String? = null) {
        eventToCacheKeys.remove(eventId)?.forEach { key ->
            removeCachedDepositByKey(key, eventId)
        }
        if (recipientHash != null) {
            val primaryDest = legacyToPrimaryContact[recipientHash] ?: recipientHash
            val pnHash = currentPropagationNode().trim().lowercase()
            if (pnHash.isNotEmpty()) {
                val key = depositCacheKey(eventId, primaryDest, pnHash)
                removeCachedDepositByKey(key, eventId)
            }
        }
    }

    internal fun pruneDepositCache(
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = 30L * 24L * 3600L * 1000L,
    ): Int {
        val dir = runCatching { java.io.File(resolveFilesDir(), "rns_deposit_cache") }.getOrNull() ?: return 0
        if (!dir.exists()) return 0
        val cutoff = nowMs - maxAgeMs
        var deleted = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".lxd") && file.lastModified() in 1L until cutoff) {
                val key = file.name.removeSuffix(".lxd")
                cachedPropagationDeposits.remove(key)
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    deleted++
                }
            }
        }
        return deleted
    }

    private fun pruneDepositCacheIfDue(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastCachePruneMs < 3_600_000L) return
        lastCachePruneMs = nowMs
        runCatching { pruneDepositCache(nowMs) }
    }

    private fun effectiveRequiredStampCost(info: LxmfPropagationNodeInfo? = propagationNodeInfo): Int {
        if (info != null) return maxOf(0, info.stampCost - info.stampCostFlexibility)
        return propagationFractionalStampCost ?: 13
    }

    /**
     * Pre-computes encrypted LXMF + PoW stamp for [eventId] outside `pumpLock` (NEW-5, NEW-7).
     */
    fun preparePropagationDeposit(destHash: String, content: String, eventId: String) {
        val selfInfo = info ?: return
        val primaryDestHash = legacyToPrimaryContact[destHash] ?: destHash
        val pnHash = currentPropagationNode().trim().lowercase()
        val cacheKey = depositCacheKey(eventId, primaryDestHash, pnHash)
        if (getCachedDepositByKey(cacheKey, eventId) != null) return
        val encryptedEnvelope = runCatching { encryptPayload(selfInfo.deliveryHash, primaryDestHash, content, eventId) }.getOrNull()
            ?: return
        val payloadBytes = BridgeEnvelopeCrypto.encode(encryptedEnvelope).toByteArray(Charsets.UTF_8)
        buildOrLoadPropagationLxd(primaryDestHash, payloadBytes, eventId)
    }

    private fun buildOrLoadPropagationLxd(destHash: String, payloadBytes: ByteArray, eventId: String): ByteArray? {
        val primaryDestHash = legacyToPrimaryContact[destHash] ?: destHash
        val pnHash = currentPropagationNode().trim().lowercase()
        val cacheKey = depositCacheKey(eventId, primaryDestHash, pnHash)
        val lock = depositBuildLocks.computeIfAbsent(eventId) { Any() }
        return synchronized(lock) {
            getCachedDepositByKey(cacheKey, eventId)?.let { return@synchronized it }
            val selfInfo = info ?: return@synchronized null
            val edPriv = ed25519PrivKey ?: return@synchronized null
            val recPub64B64 = registeredContacts[primaryDestHash] ?: return@synchronized null
            val recPubBytes = Base64.getDecoder().decode(recPub64B64)
            if (recPubBytes.size < 64) return@synchronized null
            val recXPub32 = recPubBytes.copyOfRange(0, 32)
            val recIdent16 = MessageDigest.getInstance("SHA-256").digest(recPubBytes.copyOf(64)).copyOf(16)

            val targetCost = effectiveRequiredStampCost()
            if (targetCost > LxmfStamper.MAX_ACCEPTABLE_STAMP_COST) {
                _propagationStatus.value = PropagationNodeStatus(
                    state = PropagationAccessState.UNUSABLE_STAMP_COST,
                    detail = "Požadovaná cena stampu ($targetCost) překračuje limit ${LxmfStamper.MAX_ACCEPTABLE_STAMP_COST}",
                    stampCost = targetCost,
                )
                return@synchronized null
            }

            val lxmfWire = LxmfCodec.pack(
                destinationHash = primaryDestHash,
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
            val stamp = runBlocking(Dispatchers.Default) {
                LxmfStamper.generateStamp(
                    material = transientId,
                    targetCost = targetCost,
                    expandRounds = LxmfStamper.PN_WORKBLOCK_ROUNDS,
                    isCancelled = {
                        bridgeStopped.get() ||
                            !scope.isActive ||
                            currentPropagationNode().trim().lowercase() != pnHash
                    },
                )
            } ?: return@synchronized null
            (encrypted + stamp).also { putCachedDepositByKey(cacheKey, eventId, it) }
        }
    }

    private enum class DepositAttemptResult { STARTED, SKIPPED, FAILED_EMITTED }

    /** Největší deposit, který uzel přijme: per-sync limit `[4]` (LXMRouter.py:2555-2559), viz [LxmfPropagationNodeInfo.maxDepositBytes]. */
    internal fun maxPropagationEnvelopeBytes(pnInfo: LxmfPropagationNodeInfo? = propagationNodeInfo): Long? =
        pnInfo?.maxDepositBytes()

    private fun failDepositOverPnLimit(eventId: String, actualBytes: Int, maxBytes: Long): DepositAttemptResult {
        val sizeKb = (actualBytes.toLong() + 999L) / 1000L
        val limitKb = maxBytes / 1000L
        val detail = "Zpráva překračuje limit propagation uzlu (${sizeKb} KB > ${limitKb} KB)"
        PLog.w("PulseSync") { "LXMF deposit $eventId překročil limit uzlu: ${actualBytes}B > ${maxBytes}B" }
        _propagationStatus.value = _propagationStatus.value.copy(detail = detail)
        // TOO_LARGE = trvalé selhání: outbox položku neopakuje (FAILED_PERMANENT) a UI ukáže důvod.
        emitEvent(BridgeEvent.Delivery(eventId, "FAILED", "${app.pulse4.sync.TransportResult.TOO_LARGE}: ${sizeKb} KB > ${limitKb} KB"))
        return DepositAttemptResult.FAILED_EMITTED
    }

    private fun sendViaLxmfPropagation(destHash: String, payloadBytes: ByteArray, eventId: String): Boolean =
        sendViaLxmfPropagationDetailed(destHash, payloadBytes, eventId) == DepositAttemptResult.STARTED

    private fun sendViaLxmfPropagationDetailed(
        destHash: String,
        payloadBytes: ByteArray,
        eventId: String,
    ): DepositAttemptResult = runCatching {
        val primaryDestHash = legacyToPrimaryContact[destHash] ?: destHash
        val maxEnvelopeBytes = maxPropagationEnvelopeBytes()
        if (maxEnvelopeBytes != null && payloadBytes.size.toLong() > maxEnvelopeBytes) {
            return@runCatching failDepositOverPnLimit(eventId, payloadBytes.size, maxEnvelopeBytes)
        }
        val pnHash = currentPropagationNode().trim().lowercase()
        val cacheKey = depositCacheKey(eventId, primaryDestHash, pnHash)
        val lxd = buildOrLoadPropagationLxd(primaryDestHash, payloadBytes, eventId)
            ?: return@runCatching DepositAttemptResult.SKIPPED

        val depositEnvelope = LxmfCodec.packPropagationDepositRaw(lxd)
        if (maxEnvelopeBytes != null && depositEnvelope.size.toLong() > maxEnvelopeBytes) {
            return@runCatching failDepositOverPnLimit(eventId, depositEnvelope.size, maxEnvelopeBytes)
        }
        val link = ensurePropagationLink() ?: return@runCatching DepositAttemptResult.SKIPPED
        if (depositEnvelope.size > link.mdu) {
            val resHashHex = runOnResourceDispatcher {
                resourceCoordinator.startOutbound(
                    link = link,
                    data = depositEnvelope,
                    eventId = eventId,
                    sendPacket = ::sendRawRnsPacket,
                )
            } ?: return@runCatching DepositAttemptResult.SKIPPED
            PLog.i("PulseSync") {
                "Zahájen RNS Resource deposit pro $primaryDestHash (${depositEnvelope.size}B > MDU ${link.mdu}B, res=${resHashHex.take(8)})"
            }
            return@runCatching DepositAttemptResult.STARTED
        }
        val pkt = link.buildEncryptedLinkPacket(context = RnsContext.NONE, plaintext = depositEnvelope)
            ?: return@runCatching DepositAttemptResult.SKIPPED
        val pktHash = RnsCrypto.packetHash(pkt)
        val pktHashHex = bytesToHex(pktHash)
        pendingLinkProofs[pktHashHex] = PendingDeposit(
            eventId = eventId,
            packetHash32 = pktHash,
            sentAtMs = System.currentTimeMillis(),
            linkIdHex = link.linkIdHex,
            cacheKey = cacheKey,
        )
        val linkList = activeLinkPacketDeposits.computeIfAbsent(link.linkIdHex) { mutableListOf() }
        synchronized(linkList) {
            linkList.add(eventId to cacheKey)
        }
        if (sendRawRnsPacket(pkt)) {
            PLog.i("PulseSync") { "Odeslán LXMF deposit pro $primaryDestHash (${depositEnvelope.size}B, čeká na PROOF)" }
            DepositAttemptResult.STARTED
        } else {
            pendingLinkProofs.remove(pktHashHex)
            removeActivePacketDeposit(link.linkIdHex, eventId)
            DepositAttemptResult.SKIPPED
        }
    }.getOrDefault(DepositAttemptResult.SKIPPED)

    private fun sendIdentifyOnLink(link: RnsLinkSession, force: Boolean = false): Boolean {
        if (!force && link.identified) return true
        val xPriv = x25519PrivKey ?: return false
        val xPub = xPriv.generatePublicKey().encoded
        val edPriv = ed25519PrivKey ?: return false
        val edPub = edPriv.generatePublicKey().encoded
        val idPkt = link.buildIdentifyPacket(xPub + edPub, edPriv) ?: return false
        return sendRawRnsPacket(idPkt)
    }

    private fun awaitGetResponse(link: RnsLinkSession, reqData: List<Any?>, timeoutMs: Long = 15_000L): GetResponse? {
        val future = CompletableFuture<GetResponse>()
        val (reqId, reqPkt) = link.buildRequestPacket(LxmfCodec.MESSAGE_GET_PATH, reqData) ?: return null
        synchronized(getRequestLock) {
            pendingGetResponse?.cancel(false)
            pendingGetResponse = future
            pendingGetRequestId = reqId
        }
        runOnResourceDispatcher {
            resourceCoordinator.registerPendingResponse(reqId, maxResponseSize = RnsResourceConstants.DEFAULT_MAX_DECOMPRESSED_SIZE)
        }
        if (!sendRawRnsPacket(reqPkt)) {
            synchronized(getRequestLock) {
                if (pendingGetResponse === future) {
                    pendingGetResponse = null
                    pendingGetRequestId = null
                }
            }
            runOnResourceDispatcher { resourceCoordinator.clearPendingResponse(reqId) }
            tearDownPropagationLink(link)
            return null
        }

        // R4-8: místo pevného 60s stropu idle timeout – postupující stahování (až 2 MB) se neutne.
        val result: GetResponse? = IdleTimeoutWaiter.await(
            future = future,
            lastActivityMs = { runOnResourceDispatcher { resourceCoordinator.activeResponseLastActivityMs(reqId) } },
            responseTimeoutMs = timeoutMs,
        )
        synchronized(getRequestLock) {
            if (pendingGetResponse === future) {
                pendingGetResponse = null
                pendingGetRequestId = null
            }
        }
        runOnResourceDispatcher { resourceCoordinator.clearPendingResponse(reqId) }
        if (result == null) {
            tearDownPropagationLink(link)
        }
        return result
    }

    private fun executeGetWithErrorHandling(
        link: RnsLinkSession,
        reqData: List<Any?>,
        allowRetryOnNoIdentity: Boolean = true,
    ): GetResponse.Messages? {
        val resp = awaitGetResponse(link, reqData) ?: return null
        return when (resp) {
            is GetResponse.Messages -> {
                throttleBackoffMs = 0L
                propagationThrottledUntilMs = 0L
                if (_propagationStatus.value.state != PropagationAccessState.UNUSABLE_STAMP_COST) {
                    _propagationStatus.value = PropagationNodeStatus(
                        state = PropagationAccessState.OK,
                        stampCost = effectiveRequiredStampCost(),
                    )
                }
                resp
            }
            is GetResponse.NullOrInvalid -> {
                PLog.w("PulseSync") { "LXMF propagation node vrátil prázdnou (null) odpověď na /get" }
                null
            }
            is GetResponse.Error -> when (resp.code) {
                LxmfCodec.ERROR_NO_IDENTITY -> {
                    _propagationStatus.value = PropagationNodeStatus(
                        state = PropagationAccessState.NO_IDENTITY,
                        detail = "Uzel požaduje identifikaci linku (NO_IDENTITY)",
                    )
                    if (allowRetryOnNoIdentity && sendIdentifyOnLink(link, force = true)) {
                        executeGetWithErrorHandling(link, reqData, allowRetryOnNoIdentity = false)
                    } else {
                        tearDownPropagationLink(link)
                        null
                    }
                }
                LxmfCodec.ERROR_NO_ACCESS -> {
                    _propagationStatus.value = PropagationNodeStatus(
                        state = PropagationAccessState.NO_ACCESS,
                        detail = "Uzel odepřel přístup ke schránce (NO_ACCESS)",
                    )
                    PLog.w("PulseSync") { "LXMF propagation node vrátil ERROR_NO_ACCESS (0xF1)" }
                    tearDownPropagationLink(link)
                    null
                }
                LxmfCodec.ERROR_THROTTLED -> {
                    val nextBackoff = if (throttleBackoffMs <= 0L) 30_000L else minOf(throttleBackoffMs * 2L, 300_000L)
                    throttleBackoffMs = nextBackoff
                    val untilMs = System.currentTimeMillis() + nextBackoff
                    propagationThrottledUntilMs = untilMs
                    _propagationStatus.value = PropagationNodeStatus(
                        state = PropagationAccessState.THROTTLED,
                        detail = "Uzel omezil rychlost požadavků (THROTTLED)",
                        throttledUntilMs = untilMs,
                    )
                    PLog.w("PulseSync") { "LXMF propagation node vrátil ERROR_THROTTLED (0xF6), backoff ${nextBackoff}ms" }
                    null
                }
                else -> {
                    PLog.w("PulseSync") { "LXMF propagation node vrátil chybu /get: 0x${resp.code.toString(16)}" }
                    null
                }
            }
        }
    }

    /** Vyzvedne čekající zašifrované zprávy z LXMF propagation uzlu přes aktivní RNS Link (dvoufázový /get + purge). */
    fun syncInbox(onEnvelope: ((BridgeEvent.Message) -> Boolean)? = null) {
        if (currentNetworkMode() == NetworkMode.LOCAL_ONLY) return
        val propHash = currentPropagationNode().trim()
        if (propHash.length != 32 || !isGatewayOnline) return
        val now = System.currentTimeMillis()
        if (now < propagationThrottledUntilMs) return
        val selfInfo = info ?: return
        val xPriv = x25519PrivKey ?: return
        val selfIdBytes = hexToBytes(selfInfo.identityHash)
        val link = ensurePropagationLink() ?: return
        sendIdentifyOnLink(link, force = false)

        // Fáze 1: [null, null] -> seznam dostupných transient ID
        val phase1 = executeGetWithErrorHandling(link, LxmfCodec.buildGetMessagesRequest(null, null)) ?: return
        val availableIds = phase1.items.mapNotNull { it as? ByteArray }
        val wants = synchronized(downloadedTransientIds) {
            availableIds.filter { bytesToHex(it) !in downloadedTransientIds }.take(32)
        }
        val haves = synchronized(pendingPurgeIds) {
            pendingPurgeIds.map { hexToBytes(it) }
        }
        if (wants.isEmpty() && haves.isEmpty()) return

        // Fáze 2: [wants, haves, limit] -> stažení nových zpráv (limit v KB dle LXMRouter.py:1746) a purge již zpracovaných
        val nodeLimitKb = propagationNodeInfo?.perTransferLimitKb?.takeIf { it > 0.0 } ?: 256.0
        val transferLimitKb = kotlin.math.floor(minOf(nodeLimitKb, 2048.0).coerceAtLeast(32.0)).toInt()
        val phase2 = executeGetWithErrorHandling(
            link = link,
            reqData = LxmfCodec.buildGetMessagesRequest(wants, haves, transferLimitKb),
        ) ?: return
        // N-4: purge IDs only after Phase 2 succeeds without error
        if (haves.isNotEmpty()) {
            synchronized(pendingPurgeIds) {
                haves.forEach { pendingPurgeIds.remove(bytesToHex(it)) }
            }
        }
        for (item in phase2.items) {
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
        val xPriv = x25519PrivKey ?: return null
        val expectedRecipients = buildSet {
            add(selfHash)
            legacyDeliveryHash?.let(::add)
        }
        val result = BridgeEnvelopeCrypto.decryptToBridgeMessage(
            rawJson = rawJson,
            xPriv = xPriv,
            expectedRecipients = expectedRecipients,
            lookupContactPubKeyB64 = { sender -> registeredContacts[sender] },
            normalizeSource = { sender -> legacyToPrimaryContact[sender] ?: sender },
        )
        return if (result != null) {
            val (dec, msg) = result
            PLog.i("PulseSync") { "Úspěšně dešifrována zpráva od ${msg.source} (v=${dec.version}, len=${msg.content.length})" }
            msg
        } else {
            PLog.w("PulseSync") { "Dešifrování nebo ověření podpisu příchozí obálky selhalo" }
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

    internal fun encryptPayload(sender: String, recipient: String, content: String, eventId: String): EncryptedEnvelope {
        val recPubKeyB64 = registeredContacts[recipient]
            ?: throw IllegalStateException("Unknown recipient public key for E2EE v3")
        val edPriv = ed25519PrivKey
            ?: throw IllegalStateException("Missing Ed25519 identity key for E2EE v3")
        return BridgeEnvelopeCrypto.encrypt(
            sender = sender,
            recipient = recipient,
            content = content,
            eventId = eventId,
            edPriv = edPriv,
            recipientPubKeyB64 = recPubKeyB64,
        )
    }

    internal fun decryptPayload(env: EncryptedEnvelope): String? = runCatching {
        val xPriv = x25519PrivKey ?: return null
        val expectedRecipients = info?.deliveryHash?.let { selfHash ->
            buildSet {
                add(selfHash)
                legacyDeliveryHash?.let(::add)
            }
        }
        BridgeEnvelopeCrypto.decrypt(
            envelope = env,
            xPriv = xPriv,
            expectedRecipients = expectedRecipients,
            lookupContactPubKeyB64 = { sender -> registeredContacts[sender] },
        )?.content
    }.getOrNull()

    fun drainEvents(): List<BridgeEvent> {
        synchronized(pendingEvents) {
            val list = pendingEvents.toList()
            pendingEvents.clear()
            return list
        }
    }

    internal fun setGatewayOnlineForTest(online: Boolean) {
        isGatewayOnline = online
    }

    internal fun setPropagationLinkForTest(link: RnsLinkSession?) {
        synchronized(propagationLinkLock) {
            propagationLink = link
        }
    }

    internal fun addPendingPurgeIdForTest(tidHex: String) {
        synchronized(pendingPurgeIds) {
            pendingPurgeIds.add(tidHex)
        }
    }

    internal fun pendingPurgeIdsSnapshotForTest(): Set<String> = synchronized(pendingPurgeIds) {
        pendingPurgeIds.toSet()
    }

    internal fun pendingLinkProofsCountForTest(): Int = pendingLinkProofs.size
    internal fun pendingPostProofsCountForTest(): Int = pendingPostProofDeposits.size
    internal fun activeOutboundResourceCountForTest(): Int = runOnResourceDispatcher {
        resourceCoordinator.activeOutboundCount()
    }

    internal fun sendViaLxmfPropagationForTest(destHash: String, payloadBytes: ByteArray, eventId: String): Boolean =
        sendViaLxmfPropagation(destHash, payloadBytes, eventId)

    internal fun resetThrottleForTest() {
        propagationThrottledUntilMs = 0L
        throttleBackoffMs = 0L
    }

    fun statusJson(): String {
        val hasLocal = localServerSocket != null && !localServerSocket!!.isClosed
        val peerCount = localPeerMap.size
        val gwOnline = isGatewayOnline
        val lxmfReady = propagationLink?.state == RnsLinkSession.State.ACTIVE
        val propStatus = _propagationStatus.value
        return """{"status":"online","propagationAccess":"${propStatus.state.name}","interfaces":[{"name":"Local Wi-Fi (mDNS)","online":$hasLocal,"peers":$peerCount},{"name":"Reticulum TCP Gateway","online":$gwOnline},{"name":"LXMF Propagation","online":$lxmfReady,"accessState":"${propStatus.state.name}"}]}"""
    }
}
