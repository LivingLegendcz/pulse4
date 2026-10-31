package app.pulse4.withings

import app.pulse4.provider.HttpGateway
import app.pulse4.provider.ProviderError
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class WithingsTokens(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val scope: String,
    val expiresAtMs: Long,
    val issuedAtMs: Long,
) {
    override fun toString() = "WithingsTokens(userId=$userId, scope=$scope, expiresAtMs=$expiresAtMs, tokens=***)"
}

/** Úložiště tokenů; v aplikaci šifrované Keystorem. `save` musí být atomické (starý stav nesmí zmizet při pádu). */
interface TokenVault {
    fun load(): WithingsTokens?
    fun save(t: WithingsTokens)
    fun clear()
}

object WithingsSigner {
    /** HMAC-SHA256 z `action,client_id,hodnota` s klíčem client_secret, hex. */
    fun sign(secret: String, action: String, clientId: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return mac.doFinal("$action,$clientId,$value".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Odpověď Withings je HTTP 200 s polem `status`; 0 = úspěch. */
internal object Wire {
    val json = Json { ignoreUnknownKeys = true }

    class Envelope(val status: Int, val body: JsonObject?, val error: String?)

    fun parse(text: String): Envelope {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw ProviderError.Server(0)
        val status = root["status"]?.jsonPrimitive?.intOrNull ?: throw ProviderError.Server(0)
        return Envelope(status, root["body"] as? JsonObject, root["error"]?.jsonPrimitive?.contentOrNull)
    }

    /** Mapování stavu Withings na chyby poskytovatele; nikdy se nelogují těla ani tokeny. */
    fun check(e: Envelope): JsonObject {
        when (e.status) {
            0 -> return e.body ?: JsonObject(emptyMap())
            401, 403 -> throw ProviderError.Unauthorized()
            601 -> throw ProviderError.RateLimited(null)
            else -> throw ProviderError.Server(e.status)
        }
    }
}

/**
 * Relace přímého Withings API. Refresh je serializovaný (jedna obnova naráz) a nová dvojice tokenů se zapíše atomicky
 * dřív, než se použije. Withings po obnově rotuje refresh token, starý přestane platit po osmi hodinách nebo po použití
 * nového access tokenu: pád po přijetí odpovědi, ale před uložením, proto nezničí relaci – starý token ještě platí.
 */
class WithingsSession(
    private val config: WithingsConfig,
    private val creds: () -> WithingsCredentials?,
    private val http: HttpGateway,
    private val vault: TokenVault,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    fun isConnected() = vault.load() != null

    fun accessToken(): String = synchronized(lock) {
        val t = vault.load() ?: throw ProviderError.Unauthorized()
        if (t.expiresAtMs - now() > config.refreshSkewMs) t.accessToken else refresh(t).accessToken
    }

    /** Obnoví uložený token bez ohledu na jeho stáří (po 401 od API). */
    fun refreshStored(): WithingsTokens = synchronized(lock) { refresh(vault.load() ?: throw ProviderError.Unauthorized()) }

    /** Po odmítnutí tokenu: jiné vlákno ho mohlo už obnovit – pak se druhá obnova neprovádí. */
    fun refreshAfterRejection(rejectedAccessToken: String) = synchronized(lock) {
        val t = vault.load() ?: throw ProviderError.Unauthorized()
        if (t.accessToken != rejectedAccessToken) t else refresh(t)
    }

    private fun refresh(old: WithingsTokens): WithingsTokens {
        val c = creds() ?: throw ProviderError.NotConfigured("Withings: chybí vývojářské přístupy v telefonu")
        val res = try {
            http.postForm(config.tokenUrl, tokenForm(c, "refresh_token", mapOf("refresh_token" to old.refreshToken)), emptyMap())
        } catch (e: IOException) { throw ProviderError.Network(e.javaClass.simpleName) }
        if (res.code == 429) throw ProviderError.RateLimited(null)
        val env = Wire.parse(res.body)
        // Neplatný/odvolaný refresh token = nutná nová autorizace (uživatel), ne smyčka.
        val body = try { Wire.check(env) } catch (e: ProviderError.Server) { throw ProviderError.Unauthorized() }
        val fresh = parseTokens(body, now(), fallbackUser = old.userId, fallbackScope = old.scope)
        vault.save(fresh) // atomicky, před prvním použitím
        return fresh
    }

    /** Společné pole požadavku na token; s podpisem se místo client_secret posílá nonce a HMAC. */
    internal fun tokenForm(c: WithingsCredentials, grant: String, extra: Map<String, String>): Map<String, String> {
        val base = linkedMapOf("action" to "requesttoken", "client_id" to c.clientId, "grant_type" to grant) + extra
        if (!config.useSignature) return base + ("client_secret" to c.clientSecret)
        val ts = (now() / 1000).toString()
        val nonceRes = try {
            http.postForm(config.tokenUrl, mapOf("action" to "getnonce", "client_id" to c.clientId, "timestamp" to ts,
                "signature" to WithingsSigner.sign(c.clientSecret, "getnonce", c.clientId, ts)), emptyMap())
        } catch (e: IOException) { throw ProviderError.Network(e.javaClass.simpleName) }
        val nonce = Wire.check(Wire.parse(nonceRes.body))["nonce"]?.jsonPrimitive?.contentOrNull ?: throw ProviderError.Server(0)
        return base + mapOf("nonce" to nonce, "signature" to WithingsSigner.sign(c.clientSecret, "requesttoken", c.clientId, nonce))
    }

    companion object {
        fun parseTokens(body: JsonObject, nowMs: Long, fallbackUser: String? = null, fallbackScope: String? = null): WithingsTokens {
            val expires = body["expires_in"]?.jsonPrimitive?.longOrNull ?: 10_800
            return WithingsTokens(
                userId = body["userid"]?.jsonPrimitive?.contentOrNull ?: fallbackUser ?: throw ProviderError.Server(0),
                accessToken = body["access_token"]?.jsonPrimitive?.contentOrNull ?: throw ProviderError.Server(0),
                refreshToken = body["refresh_token"]?.jsonPrimitive?.contentOrNull ?: throw ProviderError.Server(0),
                scope = body["scope"]?.jsonPrimitive?.contentOrNull ?: fallbackScope ?: "",
                expiresAtMs = nowMs + expires * 1000, issuedAtMs = nowMs,
            )
        }
    }
}

internal fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
