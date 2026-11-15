package app.pulse4.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class RnsTransportType { TCP_CLIENT, TCP_SERVER, UDP }

@Serializable
data class RnsNodeConfig(
    val v: Int = RnsNodeQr.CURRENT_VERSION,
    val type: String = RnsNodeQr.CONFIG_TYPE,
    val name: String,
    val host: String,
    val port: Int,
    val transport: RnsTransportType = RnsTransportType.TCP_CLIENT,
    val propagationNode: String? = null,
)

object RnsNodeQr {
    const val CURRENT_VERSION = 1
    const val CONFIG_TYPE = "rns_node"
    const val MAX_QR_LENGTH = 2048

    private val compactJson = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private val forbiddenKeys = setOf(
        "privatekey",
        "private_key",
        "seed",
        "token",
        "password",
        "secret",
        "identitykey",
        "identity_key",
    )

    private val hex32Regex = Regex("^[0-9a-fA-F]{32}$")

    fun encode(config: RnsNodeConfig): String {
        validateConfig(config)
        return compactJson.encodeToString(config)
    }

    fun decodeAndValidate(raw: String): Result<RnsNodeConfig> = runCatching {
        require(raw.isNotBlank()) { "QR payload must not be blank" }
        require(raw.length <= MAX_QR_LENGTH) { "QR payload exceeds $MAX_QR_LENGTH chars" }

        val element = compactJson.parseToJsonElement(raw)
        require(element is JsonObject) { "QR payload must be a JSON object" }
        require(!containsForbiddenKey(element)) { "QR payload contains forbidden sensitive key" }

        val config = compactJson.decodeFromJsonElement(RnsNodeConfig.serializer(), element)
        validateConfig(config)
        config
    }

    private fun validateConfig(config: RnsNodeConfig) {
        require(config.v == CURRENT_VERSION) { "Unsupported RNS QR version: ${config.v}" }
        require(config.type == CONFIG_TYPE) { "Invalid RNS QR type: ${config.type}" }
        require(config.name.isNotBlank()) { "Node name must not be blank" }
        require(config.host.isNotBlank()) { "Host must not be blank" }
        require(!config.host.any { it.isWhitespace() || it.isISOControl() }) { "Host contains invalid characters" }
        require("://" !in config.host) { "Host must not include URI scheme" }
        require(config.port in 1..65535) { "Port out of range: ${config.port}" }
        config.propagationNode?.let { node ->
            require(hex32Regex.matches(node)) { "Invalid propagationNode hash" }
        }
    }

    private fun containsForbiddenKey(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.entries.any { (k, v) ->
            k.lowercase() in forbiddenKeys || containsForbiddenKey(v)
        }
        is JsonArray -> element.any { containsForbiddenKey(it) }
        else -> false
    }
}
