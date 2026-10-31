package app.pulse4.android.reticulum

import android.content.Context
import android.net.wifi.WifiManager
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.data.Settings
import app.pulse4.android.security.SecretStore
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class BridgeInfo(val deliveryHash: String, val identityHash: String, val publicKeyB64: String)

sealed interface BridgeEvent {
    /** `source` je tvrzení do ověření podpisu: zprávy s `validated == false` se zahazují. */
    data class Message(val source: String, val validated: Boolean, val content: String) : BridgeEvent
    data class Delivery(val eventId: String, val state: String, val reason: String?) : BridgeEvent
}

/**
 * Kotlin strana mostu k Reticulu/LXMF (Python, Chaquopy). Předává se jen malé verzované JSON DTO.
 * Životní cyklus RNS vlastní tento adaptér; RNS je singleton procesu, takže změna rozhraní se projeví po restartu aplikace.
 * Aplikace nezávisí na ručně spuštěném Termuxu ani na instalaci Sidebandu a má vlastní identitu.
 */
class ReticulumBridge(
    private val context: Context,
    private val secrets: SecretStore,
    private val settings: Settings,
) {
    private val json = Json { ignoreUnknownKeys = true }
    var info: BridgeInfo? = null
        private set
    private var multicastLock: WifiManager.MulticastLock? = null

    private fun module(): PyObject {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        return Python.getInstance().getModule("pulse4_rns.bridge")
    }

    @Synchronized
    fun ensureStarted(): BridgeInfo {
        info?.let { return it }
        val mode = settings.networkMode
        val ifaces = buildJsonArray {
            // Místní Wi-Fi: AutoInterface vyžaduje multicast, link-local IPv6 a povolenou komunikaci mezi klienty.
            add(buildJsonObject { put("type", "auto"); put("name", "Local") })
            // Existující gateway; v režimu „Pouze místní síť“ se internetové rozhraní vypíná.
            if (mode != NetworkMode.LOCAL_ONLY) {
                val resolvedHost = runCatching {
                    java.net.InetAddress.getByName(settings.gatewayHost).hostAddress
                }.getOrNull() ?: settings.gatewayHost
                add(buildJsonObject {
                    put("type", "tcp_client"); put("name", "Gateway")
                    put("host", resolvedHost); put("port", settings.gatewayPort)
                })
            }
        }
        acquireMulticast()
        val identityB64 = secrets.getString(SecretStore.RETICULUM_IDENTITY)
        val dir = java.io.File(context.noBackupFilesDir, "reticulum").apply { mkdirs() }.absolutePath
        val out = json.parseToJsonElement(
            module().callAttr("start", dir, identityB64, settings.displayName, ifaces.toString(), false, false).toString(),
        ).jsonObject
        out["privateKey"]?.jsonPrimitive?.contentOrNull?.let { secrets.putString(SecretStore.RETICULUM_IDENTITY, it) }
        val bi = BridgeInfo(
            deliveryHash = out.getValue("deliveryHash").jsonPrimitive.content,
            identityHash = out.getValue("identityHash").jsonPrimitive.content,
            publicKeyB64 = out.getValue("publicKey").jsonPrimitive.content,
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
        if (info == null) return
        module().callAttr("set_propagation_node", settings.propagationNode.ifBlank { null })
    }

    fun announce() { module().callAttr("announce") }

    /** Registruje ověřený kontakt (veřejný klíč z QR) a vrací adresu odvozenou transportem – ta musí sedět s tvrzenou v kódu. */
    fun addContact(publicKeyB64: String): String = module().callAttr("add_contact", publicKeyB64).toString()
    fun removeContact(deliveryHash: String) { module().callAttr("remove_contact", deliveryHash) }

    fun send(destHash: String, content: String, propagated: Boolean, eventId: String) {
        module().callAttr("send", destHash, content, if (propagated) "propagated" else "direct", eventId)
    }

    fun syncInbox() { module().callAttr("sync_inbox", 0) }

    fun drainEvents(): List<BridgeEvent> {
        val arr: JsonArray = json.parseToJsonElement(module().callAttr("drain_events").toString()).jsonArray
        return arr.mapNotNull { e ->
            val o: JsonObject = e.jsonObject
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "message" -> BridgeEvent.Message(
                    o.getValue("source").jsonPrimitive.content, o.getValue("validated").jsonPrimitive.boolean,
                    o.getValue("content").jsonPrimitive.content,
                )
                "delivery" -> BridgeEvent.Delivery(
                    o.getValue("eventId").jsonPrimitive.content, o.getValue("state").jsonPrimitive.content,
                    o["reason"]?.jsonPrimitive?.contentOrNull,
                )
                else -> null // neznámý druh události z novější verze mostu se ignoruje
            }
        }
    }

    fun statusJson(): String = module().callAttr("status").toString()
}
