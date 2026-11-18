package app.pulse4.android.reticulum

import java.util.concurrent.ConcurrentHashMap

/**
 * Pure-JVM coordinator for RNS gateway transport ID lifecycle and destination path requests (§8.4 #12).
 *
 * Tracks the active gateway endpoint, discovered destination public keys (LRU capped at 512),
 * and per-destination path-request cooldowns so both propagation nodes and recipient destinations
 * receive path requests without spamming the gateway.
 */
internal class GatewayRoutingState(
    private val pathRequestCooldownMs: Long = 60_000L,
    maxDiscoveredDestinations: Int = 512,
) {
    @Volatile
    var gatewayTransportId: ByteArray? = null
        private set

    @Volatile
    var activeGatewayHost: String? = null
        private set

    @Volatile
    var activeGatewayPort: Int? = null
        private set

    private val pathRequestTimestamps = ConcurrentHashMap<String, Long>()

    private val discoveredDestinations = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > maxDiscoveredDestinations
    }

    fun updateGatewayTransportId(transportId: ByteArray?): Boolean {
        if (transportId == null) {
            val changed = gatewayTransportId != null
            gatewayTransportId = null
            return changed
        }
        val current = gatewayTransportId
        if (current != null && current.contentEquals(transportId)) return false
        gatewayTransportId = transportId.copyOf()
        return true
    }

    /**
     * Resets `gatewayTransportId` and path-request cooldowns when the underlying connection drops
     * or network changes.
     */
    fun onConnectionChanged(clearEndpoint: Boolean = false) {
        gatewayTransportId = null
        pathRequestTimestamps.clear()
        if (clearEndpoint) {
            activeGatewayHost = null
            activeGatewayPort = null
        }
    }

    /**
     * Checks whether [host]:[port] matches the currently active gateway endpoint.
     * If it differs, resets `gatewayTransportId` and updates the active endpoint.
     * @return true if the endpoint changed (or was unset).
     */
    fun onGatewayEndpointSelected(host: String, port: Int): Boolean {
        val changed = activeGatewayHost != host || activeGatewayPort != port
        if (changed) {
            gatewayTransportId = null
            pathRequestTimestamps.clear()
            activeGatewayHost = host
            activeGatewayPort = port
        }
        return changed
    }

    fun isSameGatewayEndpoint(host: String, port: Int): Boolean =
        activeGatewayHost == host && activeGatewayPort == port

    fun recordDiscoveredDestination(destHashHex: String, publicKey64: ByteArray) {
        val normalized = destHashHex.trim().lowercase()
        synchronized(discoveredDestinations) {
            discoveredDestinations[normalized] = publicKey64
        }
    }

    fun getDiscoveredPublicKey(destHashHex: String): ByteArray? {
        val normalized = destHashHex.trim().lowercase()
        return synchronized(discoveredDestinations) { discoveredDestinations[normalized] }
    }

    fun hasDiscoveredDestination(destHashHex: String): Boolean = getDiscoveredPublicKey(destHashHex) != null

    /**
     * Builds a path request packet for [destHashHex] if [isGatewayOnline] is true,
     * the destination is not yet discovered (unless [force] is true), and the cooldown has elapsed.
     */
    fun buildPathRequestIfNeeded(
        destHashHex: String,
        isGatewayOnline: Boolean,
        force: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): ByteArray? {
        val normalized = destHashHex.trim().lowercase()
        if (normalized.length != 32 || !isGatewayOnline) return null
        val destBytes = runCatching { RnsCrypto.hexToBytes(normalized) }.getOrNull()?.takeIf { it.size == 16 }
            ?: return null
        if (!force && hasDiscoveredDestination(normalized)) return null
        if (!force) {
            val last = pathRequestTimestamps[normalized]
            if (last != null && nowMs - last < pathRequestCooldownMs) return null
        }
        return RnsCrypto.buildPathRequestPacket(destBytes, gatewayTransportId)
    }

    fun markPathRequested(destHashHex: String, nowMs: Long = System.currentTimeMillis()) {
        pathRequestTimestamps[destHashHex.trim().lowercase()] = nowMs
    }
}
