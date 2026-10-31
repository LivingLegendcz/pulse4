package app.pulse4.withings

import app.pulse4.domain.Quality
import kotlinx.serialization.Serializable

/**
 * Adresy a parametry Withings Public API. POZOR: dokumentace Withings nebyla při vzniku dostupná, hodnoty jsou z paměti
 * a před zapnutím přímého režimu je nutné je ověřit (viz docs/withings.md). Proto jsou konfigurovatelné.
 */
@Serializable
data class WithingsConfig(
    val authorizeUrl: String = "https://account.withings.com/oauth2_user/authorize2",
    val tokenUrl: String = "https://wbsapi.withings.net/v2/oauth2",
    val measureUrl: String = "https://wbsapi.withings.net/measure",
    val scope: String = "user.metrics",
    /** Ověřený HTTPS App Link (potřebuje doménu a statický assetlinks.json, nikoli token broker). */
    val redirectUri: String,
    /** Podepsaný požadavek na token (getnonce + HMAC) místo posílání client_secret; Withings uvádí obojí, ověřit. */
    val useSignature: Boolean = false,
    /** Za kolik ms před vypršením se access token obnoví předem. */
    val refreshSkewMs: Long = 2 * 60_000,
    /** Platnost jednorázového autorizačního kódu (Withings uvádí 30 s): výměna musí proběhnout ihned. */
    val codeValidityMs: Long = 30_000,
    /** Jak dlouho čeká rozpracované přihlášení na návrat z prohlížeče. */
    val pendingTtlMs: Long = 10 * 60_000,
    /**
     * Jak Withings `attrib` mapuje na jistotu přiřazení osoby. 0 = změřeno zařízením, 2 = ručně; 1 a 5 = zařízení nemuselo
     * poznat uživatele (nejednoznačné). Neznámé hodnoty se berou jako podezřelé.
     */
    val attribQuality: Map<Int, Quality> = mapOf(0 to Quality.OK, 2 to Quality.OK, 4 to Quality.OK, 7 to Quality.OK, 1 to Quality.SUSPECT, 5 to Quality.SUSPECT, 8 to Quality.SUSPECT),
) {
    init { require(redirectUri.startsWith("https://")) { "Návratová adresa musí být HTTPS App Link" } }
}

/** Individuální vývojářské přístupy uživatele; zadávají se až do jeho telefonu. Společné tajemství v APK nebude. */
data class WithingsCredentials(val clientId: String, val clientSecret: String) {
    override fun toString() = "WithingsCredentials(clientId=$clientId, clientSecret=***)"
}
