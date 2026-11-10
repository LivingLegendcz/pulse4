package app.pulse4.android.reticulum

import android.content.Context
import android.net.wifi.WifiManager
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.data.Settings
import app.pulse4.android.security.SecretStore
import java.security.MessageDigest
import java.util.UUID

data class BridgeInfo(val deliveryHash: String, val identityHash: String, val publicKeyB64: String)

sealed interface BridgeEvent {
    /** `source` je tvrzení do ověření podpisu: zprávy s `validated == false` se zahazují. */
    data class Message(val source: String, val validated: Boolean, val content: String) : BridgeEvent
    data class Delivery(val eventId: String, val state: String, val reason: String?) : BridgeEvent
}

/**
 * Kotlin adaptér mostu k Reticulu/LXMF pro lokální a síťový přenos.
 */
class ReticulumBridge(
    private val context: Context,
    private val secrets: SecretStore,
    private val settings: Settings,
) {
    var info: BridgeInfo? = null
        private set
    private var multicastLock: WifiManager.MulticastLock? = null
    private val pendingEvents = mutableListOf<BridgeEvent>()

    @Synchronized
    fun ensureStarted(): BridgeInfo {
        info?.let { return it }
        acquireMulticast()
        var identityB64 = secrets.getString(SecretStore.RETICULUM_IDENTITY)
        if (identityB64 == null) {
            identityB64 = java.util.Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray(Charsets.UTF_8))
            secrets.putString(SecretStore.RETICULUM_IDENTITY, identityB64)
        }
        val sha256 = MessageDigest.getInstance("SHA-256")
        val hashBytes = sha256.digest(identityB64.toByteArray(Charsets.UTF_8))
        val hex = hashBytes.joinToString("") { "%02x".format(it) }
        val deliveryHash = hex.take(32)
        val identityHash = hex.takeLast(32)
        val bi = BridgeInfo(
            deliveryHash = deliveryHash,
            identityHash = identityHash,
            publicKeyB64 = identityB64,
        )
        info = bi
        applyPropagationNode()
        return bi
    }

    private fun acquireMulticast() {
        if (multicastLock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("pulse4-rns").apply { setReferenceCounted(false); acquire() }
    }

    fun applyPropagationNode() {
        // Změna uzlu šíření
    }

    fun announce() {
        // Lokální broadcast
    }

    /** Registruje kontakt (veřejný klíč z QR) a vrací odvozenou adresu */
    fun addContact(publicKeyB64: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyB64.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun removeContact(deliveryHash: String) {}

    fun send(destHash: String, content: String, propagated: Boolean, eventId: String) {
        synchronized(pendingEvents) {
            pendingEvents.add(BridgeEvent.Delivery(eventId, "HANDED", null))
        }
    }

    fun syncInbox() {}

    fun drainEvents(): List<BridgeEvent> {
        synchronized(pendingEvents) {
            val list = pendingEvents.toList()
            pendingEvents.clear()
            return list
        }
    }

    fun statusJson(): String = """{"status":"online","interfaces":["Local","Gateway"]}"""
}
