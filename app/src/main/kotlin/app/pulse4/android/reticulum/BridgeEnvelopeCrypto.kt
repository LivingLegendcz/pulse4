package app.pulse4.android.reticulum

import app.pulse4.sync.DecryptedEnvelope
import app.pulse4.sync.E2eeCrypto
import app.pulse4.sync.E2eeEnvelope
import app.pulse4.sync.PeerKeys
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/**
 * Pure-JVM E2EE v3 + SEC-3 envelope adapter used by [ReticulumBridge].
 *
 * Outbound messages always use E2EE v3 (ephemeral X25519 + HKDF-SHA256 + AES-256-GCM + 256B
 * padded inner envelope with outer `sender`/`recipient`/`mac` omitted per SEC-3).
 * Inbound decryption accepts both E2EE v3 and legacy E2EE v2 envelopes.
 */
internal object BridgeEnvelopeCrypto {

    fun decodePeerKeys64(pubKeyB64: String): PeerKeys? = runCatching {
        val raw = Base64.getDecoder().decode(pubKeyB64)
        if (raw.size < 64) return null
        PeerKeys(
            x25519Pub32 = raw.copyOfRange(0, 32),
            ed25519Pub32 = raw.copyOfRange(32, 64),
        )
    }.getOrNull()

    fun encrypt(
        sender: String,
        recipient: String,
        content: String,
        eventId: String,
        edPriv: Ed25519PrivateKeyParameters,
        recipientPubKeyB64: String,
    ): E2eeEnvelope {
        val recipientKeys = decodePeerKeys64(recipientPubKeyB64)
            ?: throw IllegalStateException("Unknown or invalid recipient public key for E2EE v3")
        return E2eeCrypto.encrypt(
            senderId = sender,
            recipientId = recipient,
            content = content,
            eventId = eventId,
            senderEd25519Priv32 = edPriv.encoded,
            recipientX25519Pub32 = recipientKeys.x25519Pub32,
            includeOuterRecipient = false,
            paddingBlockBytes = E2eeCrypto.DEFAULT_PADDING_BLOCK_BYTES,
        )
    }

    fun encode(envelope: E2eeEnvelope): String = E2eeCrypto.encodeEnvelope(envelope)

    fun decrypt(
        envelope: E2eeEnvelope,
        xPriv: X25519PrivateKeyParameters,
        expectedRecipients: Set<String>?,
        lookupContactPubKeyB64: (String) -> String?,
    ): DecryptedEnvelope? = E2eeCrypto.decrypt(
        envelope = envelope,
        recipientX25519Priv32 = xPriv.encoded,
        lookupPeerKeys = { senderId -> lookupContactPubKeyB64(senderId)?.let(::decodePeerKeys64) },
        expectedRecipients = expectedRecipients,
    )

    fun decryptToBridgeMessage(
        rawJson: String,
        xPriv: X25519PrivateKeyParameters,
        expectedRecipients: Set<String>,
        lookupContactPubKeyB64: (String) -> String?,
        normalizeSource: (String) -> String = { it },
    ): Pair<DecryptedEnvelope, BridgeEvent.Message>? {
        val dec = E2eeCrypto.decryptJson(
            rawJson = rawJson,
            recipientX25519Priv32 = xPriv.encoded,
            lookupPeerKeys = { senderId -> lookupContactPubKeyB64(senderId)?.let(::decodePeerKeys64) },
            expectedRecipients = expectedRecipients,
        ) ?: return null
        val normalizedSource = normalizeSource(dec.sender)
        return dec to BridgeEvent.Message(
            source = normalizedSource,
            validated = true,
            content = dec.content,
        )
    }
}
