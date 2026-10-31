package app.pulse4.withings

import app.pulse4.provider.HttpGateway
import app.pulse4.provider.ProviderError
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

data class PendingAuth(val state: String, val createdAtMs: Long, val redirectUri: String)

interface PendingAuthStore {
    fun put(p: PendingAuth)
    fun get(): PendingAuth?
    fun clear()
}

enum class AuthFailure {
    NOT_CONFIGURED, NO_PENDING, WRONG_REDIRECT, DENIED_BY_USER, STATE_MISMATCH, PENDING_EXPIRED, MISSING_CODE,
    CODE_TOO_OLD, EXCHANGE_REJECTED, NETWORK,
}

sealed interface AuthResult {
    data class Connected(val userId: String, val scope: String) : AuthResult
    data class Failed(val reason: AuthFailure) : AuthResult
}

/**
 * Přihlášení přes systémový prohlížeč bez našeho serveru. Návrat je ověřený HTTPS App Link. Kontroluje se `state`
 * (náhodný, jednorázový, s omezenou platností) a kód se vyměňuje hned – Withings uvádí jen 30 s platnosti.
 *
 * PKCE: v dostupné referenci Withings nedoložen, proto se tu nepředstírá. Přímý režim je z toho důvodu volitelný,
 * ve výchozím stavu vypnutý a před zapnutím musí projít bezpečnostním testem (docs/withings.md).
 */
class WithingsOAuth(
    private val config: WithingsConfig,
    private val creds: () -> WithingsCredentials?,
    private val http: HttpGateway,
    private val vault: TokenVault,
    private val pending: PendingAuthStore,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Vrací URL k otevření v systémovém prohlížeči; předchozí rozpracované přihlášení se zahodí. */
    fun begin(nowMs: Long): String {
        val c = creds() ?: throw ProviderError.NotConfigured("Withings: chybí client_id/client_secret")
        val state = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        pending.put(PendingAuth(state, nowMs, config.redirectUri))
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "${config.authorizeUrl}?response_type=code&client_id=${enc(c.clientId)}&redirect_uri=${enc(config.redirectUri)}" +
            "&scope=${enc(config.scope)}&state=${enc(state)}"
    }

    /**
     * @param receivedAtMs okamžik, kdy aplikace callback přijala (měří se od něj 30 s platnosti kódu)
     * @param nowMs aktuální čas
     */
    fun complete(callbackUrl: String, receivedAtMs: Long, nowMs: Long, session: WithingsSession): AuthResult {
        val c = creds() ?: return AuthResult.Failed(AuthFailure.NOT_CONFIGURED)
        val p = pending.get() ?: return AuthResult.Failed(AuthFailure.NO_PENDING)
        val uri = runCatching { URI(callbackUrl) }.getOrNull() ?: return AuthResult.Failed(AuthFailure.WRONG_REDIRECT)
        val expected = URI(config.redirectUri)
        if (uri.scheme != expected.scheme || uri.host != expected.host || uri.path != expected.path) return AuthResult.Failed(AuthFailure.WRONG_REDIRECT)

        val q = query(uri.rawQuery)
        // Cizí/podvržený callback se špatným state nesmí zrušit legitimní rozpracované přihlášení.
        val state = q["state"]
        if (state == null || !constantTimeEquals(state, p.state)) return AuthResult.Failed(AuthFailure.STATE_MISMATCH)

        // Od této chvíle je state spotřebován (jednorázový), ať výměna dopadne jakkoli.
        pending.clear()
        if (nowMs - p.createdAtMs > config.pendingTtlMs) return AuthResult.Failed(AuthFailure.PENDING_EXPIRED)
        if (q.containsKey("error")) return AuthResult.Failed(AuthFailure.DENIED_BY_USER)
        val code = q["code"]?.takeIf { it.isNotBlank() } ?: return AuthResult.Failed(AuthFailure.MISSING_CODE)
        if (nowMs - receivedAtMs >= config.codeValidityMs) return AuthResult.Failed(AuthFailure.CODE_TOO_OLD)

        val res = try {
            http.postForm(config.tokenUrl, session.tokenForm(c, "authorization_code", mapOf("code" to code, "redirect_uri" to config.redirectUri)), emptyMap())
        } catch (e: IOException) { return AuthResult.Failed(AuthFailure.NETWORK) }
        catch (e: ProviderError.Network) { return AuthResult.Failed(AuthFailure.NETWORK) }

        val tokens = try {
            WithingsSession.parseTokens(Wire.check(Wire.parse(res.body)), nowMs)
        } catch (e: ProviderError) { return AuthResult.Failed(AuthFailure.EXCHANGE_REJECTED) }
        vault.save(tokens)
        return AuthResult.Connected(tokens.userId, tokens.scope)
    }

    /** Zruší rozpracované přihlášení (uživatel se vrátil bez dokončení). */
    fun cancel() = pending.clear()

    companion object {
        private fun query(raw: String?): Map<String, String> =
            raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                val i = it.indexOf('=')
                if (i < 0) URLDecoder.decode(it, "UTF-8") to "" else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }

        /** Podoba URL bezpečná pro logy a diagnostiku: kód a state se nikdy nevypisují. */
        fun redact(url: String): String = url.replace(Regex("(?i)([?&](code|state|access_token|refresh_token|client_secret)=)[^&#]*"), "$1***")
    }
}
