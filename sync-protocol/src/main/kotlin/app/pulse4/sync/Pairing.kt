package app.pulse4.sync

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Obsah QR kódu: veřejná identita, adresa aplikace a verze formátu – nikdy soukromý klíč. */
@Serializable
data class PairingPayload(val v: Int, val pub: String, val addr: String, val name: String)

sealed interface PairingResult {
    data class Ok(val payload: PairingPayload, val fingerprint: String) : PairingResult
    data class Invalid(val reason: String) : PairingResult
}

object Pairing {
    const val FORMAT = 1
    const val MAX_CHARS = 1024

    fun encode(p: PairingPayload): String = Wire.json.encodeToString(p)

    fun parse(text: String): PairingResult {
        if (text.length > MAX_CHARS) return PairingResult.Invalid("Příliš dlouhý kód")
        val p = runCatching { Wire.json.decodeFromString<PairingPayload>(text) }.getOrNull()
            ?: return PairingResult.Invalid("Nečitelný párovací kód")
        if (p.v != FORMAT) return PairingResult.Invalid("Nepodporovaná verze párovacího formátu: ${p.v}")
        val key = runCatching { Base64.getDecoder().decode(p.pub) }.getOrNull()
        // Reticulum veřejný klíč identity má 64 bajtů (X25519 + Ed25519).
        if (key == null || key.size != 64) return PairingResult.Invalid("Neplatný veřejný klíč")
        if (!Regex("[0-9a-f]{32}").matches(p.addr)) return PairingResult.Invalid("Neplatná adresa")
        if (p.name.isBlank() || p.name.length > 40) return PairingResult.Invalid("Neplatné jméno")
        return PairingResult.Ok(p, fingerprint(p.pub))
    }

    /** Otisk k nezávislému porovnání osobně nebo jiným kanálem (např. telefonem): `ab12 cd34 ef56 7890 …`. */
    fun fingerprint(pubB64: String): String =
        Wire.sha256Hex(Base64.getDecoder().decode(pubB64)).take(24).chunked(4).joinToString(" ")

    /** Adresu tvrzenou v kódu musí potvrdit transportní vrstva z veřejného klíče; jinak se kontakt nepřijme. */
    fun addressMatches(claimed: String, derivedByTransport: String) = claimed.equals(derivedByTransport, ignoreCase = true)
}
