package app.pulse4.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Thrown when an X25519 ECDH exchange yields an all-zero shared secret
 * (e.g. a low-order / weak public key point).
 */
class WeakX25519KeyException(message: String) : SecurityException(message)

/**
 * Outer wire envelope for Pulse4 E2EE transport.
 *
 * - **v3 (default, per-message ephemeral sender X25519 key / ephemeral-static ECIES + SEC-3 metadata protection):**
 *   Carries only `v = 3`, `epk` (32B ephemeral X25519 public key, Base64), `iv` (12B GCM IV, Base64),
 *   `ciphertext` (Base64 AES-256-GCM of padded [InnerEnvelope]), and `eventId` (used as HKDF salt + AAD).
 *   Protects past messages if the sender's long-term key is compromised (compromise of the recipient's
 *   static X25519 key still decrypts recorded v3 envelopes without ratchet/prekey rotation).
 *   `sender` and `mac` are omitted from the outer JSON so relays/observers cannot see who is
 *   communicating or verify a public signature. `recipient` is also omitted by default.
 * - **v2 (legacy):**
 *   Carries `v = 2`, `sender`, `recipient`, `iv`, `ciphertext`, `mac` (Ed25519 hex signature), and `eventId`.
 */
@Serializable
data class E2eeEnvelope(
    val v: Int,
    val iv: String,
    val ciphertext: String,
    val eventId: String? = null,
    val epk: String? = null,
    val sender: String? = null,
    val recipient: String? = null,
    val mac: String? = null,
)

/**
 * Authenticated inner payload encrypted inside a v3 [E2eeEnvelope] (SEC-3).
 * Hides `sender`, `recipient`, and the Ed25519 signature `sig` inside AES-256-GCM ciphertext.
 */
@Serializable
internal data class InnerEnvelope(
    val sender: String,
    val recipient: String,
    val eventId: String,
    val content: String,
    val sig: String,
)

/** Public key material for a verified contact (32B X25519 + 32B Ed25519). */
data class PeerKeys(
    val x25519Pub32: ByteArray,
    val ed25519Pub32: ByteArray,
) {
    init {
        require(x25519Pub32.size == 32) { "x25519Pub32 must be 32 bytes" }
        require(ed25519Pub32.size == 32) { "ed25519Pub32 must be 32 bytes" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerKeys) return false
        return x25519Pub32.contentEquals(other.x25519Pub32) &&
            ed25519Pub32.contentEquals(other.ed25519Pub32)
    }

    override fun hashCode(): Int =
        31 * x25519Pub32.contentHashCode() + ed25519Pub32.contentHashCode()
}

/** Result of decrypting and authenticating an [E2eeEnvelope] (v2 or v3). */
data class DecryptedEnvelope(
    val version: Int,
    val sender: String,
    val recipient: String,
    val eventId: String?,
    val content: String,
)

/**
 * End-to-end encryption primitives for Pulse4:
 * - **E2EE v3**: Per-message ephemeral sender X25519 key (ephemeral-static ECIES, protecting past messages
 *   if sender's key is compromised) + all-zero shared-secret rejection + HKDF-SHA256
 *   (`info = "pulse4-e2ee-v3"`, `salt = msgId`) + AES-256-GCM with AAD binding (`epk`, `msgId`) +
 *   padded [InnerEnvelope] hiding sender/recipient/signature (SEC-3).
 * - **E2EE v2**: Legacy static X25519 + Ed25519 decryption for backward compatibility during transition.
 */
object E2eeCrypto {
    const val VERSION_V2 = 2
    const val VERSION_V3 = 3
    const val DEFAULT_PADDING_BLOCK_BYTES = 256

    private const val X25519_KEY_BYTES = 32
    private const val ED25519_KEY_BYTES = 32
    private const val ED25519_SIG_BYTES = 64
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val V3_HKDF_INFO = "pulse4-e2ee-v3"
    private const val V3_AAD_PREFIX = "pulse4-e2ee-v3:"

    private val random = SecureRandom()

    /** Generates a fresh 32-byte X25519 keypair: `(privateKey32, publicKey32)`. */
    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val priv = X25519PrivateKeyParameters(random)
        val pub = priv.generatePublicKey()
        return priv.encoded to pub.encoded
    }

    /**
     * Computes X25519 ECDH shared secret and rejects all-zero outputs (low-order / weak points)
     * in constant time.
     */
    fun ecdhX25519(privKey32: ByteArray, peerPubKey32: ByteArray): ByteArray {
        require(privKey32.size == X25519_KEY_BYTES) { "X25519 private key must be 32 bytes" }
        require(peerPubKey32.size == X25519_KEY_BYTES) { "X25519 public key must be 32 bytes" }

        val priv = X25519PrivateKeyParameters(privKey32, 0)
        val pub = X25519PublicKeyParameters(peerPubKey32, 0)
        val agreement = X25519Agreement()
        agreement.init(priv)
        val secret = ByteArray(agreement.agreementSize)
        try {
            agreement.calculateAgreement(pub, secret, 0)
        } catch (e: IllegalStateException) {
            secret.fill(0)
            throw WeakX25519KeyException("X25519 ECDH rejected weak/low-order point")
        }

        if (isAllZeroConstantTime(secret)) {
            secret.fill(0)
            throw WeakX25519KeyException("X25519 ECDH produced an all-zero shared secret")
        }
        return secret
    }

    /** RFC 5869 HKDF-SHA256. */
    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = 32,
    ): ByteArray {
        require(length in 1..(255 * 32)) { "Invalid HKDF output length: $length" }
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt

        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = extractMac.doFinal(ikm)

        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))

        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            expandMac.reset()
            expandMac.update(t)
            expandMac.update(info)
            expandMac.update(counter.toByte())
            t = expandMac.doFinal()
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
            counter++
        }
        prk.fill(0)
        return okm
    }

    /**
     * Derives the 32-byte AES-256-GCM key for E2EE v3 using HKDF-SHA256:
     * `HKDF-SHA256(ikm = ecdhSecret, salt = msgId, info = "pulse4-e2ee-v3")`.
     */
    fun deriveV3Key(ecdhSecret: ByteArray, msgId: String): ByteArray {
        require(ecdhSecret.size == X25519_KEY_BYTES) { "ecdhSecret must be 32 bytes" }
        if (isAllZeroConstantTime(ecdhSecret)) {
            throw WeakX25519KeyException("ecdhSecret must not be all-zero")
        }
        require(msgId.isNotBlank()) { "msgId must not be blank for E2EE v3" }
        return hkdfSha256(
            ikm = ecdhSecret,
            salt = msgId.toByteArray(Charsets.UTF_8),
            info = V3_HKDF_INFO.toByteArray(Charsets.UTF_8),
            length = 32,
        )
    }

    /**
     * Encrypts an outbound message using E2EE v3 (per-message ephemeral sender X25519 key —
     * ephemeral-static ECIES, protecting past messages if sender's key is compromised — + SEC-3 metadata minimization).
     * Defaults to v3 for all new outbound messages.
     */
    fun encrypt(
        senderId: String,
        recipientId: String,
        content: String,
        eventId: String,
        senderEd25519Priv32: ByteArray,
        recipientX25519Pub32: ByteArray,
        includeOuterRecipient: Boolean = false,
        paddingBlockBytes: Int = DEFAULT_PADDING_BLOCK_BYTES,
    ): E2eeEnvelope = encryptV3(
        senderId = senderId,
        recipientId = recipientId,
        content = content,
        eventId = eventId,
        senderEd25519Priv32 = senderEd25519Priv32,
        recipientX25519Pub32 = recipientX25519Pub32,
        includeOuterRecipient = includeOuterRecipient,
        paddingBlockBytes = paddingBlockBytes,
    )

    /**
     * Encrypts an outbound message using E2EE v3:
     * 1. Generates a fresh ephemeral X25519 keypair (`epkPriv`, `epkPub`).
     * 2. Computes `ecdhSecret = ECDH(epkPriv, recipientX25519Pub32)` (rejects all-zero).
     * 3. Derives 32B AES key via `HKDF-SHA256(ikm = ecdhSecret, salt = eventId, info = "pulse4-e2ee-v3")`.
     * 4. Signs the inner payload with `senderEd25519Priv32` (bound to `epkPub`, `senderId`, `recipientId`, `eventId`).
     * 5. Pads the serialized [InnerEnvelope] and encrypts with AES-256-GCM + AAD(`epkPub`, `eventId`).
     */
    fun encryptV3(
        senderId: String,
        recipientId: String,
        content: String,
        eventId: String,
        senderEd25519Priv32: ByteArray,
        recipientX25519Pub32: ByteArray,
        includeOuterRecipient: Boolean = false,
        paddingBlockBytes: Int = DEFAULT_PADDING_BLOCK_BYTES,
    ): E2eeEnvelope {
        require(senderId.isNotBlank()) { "senderId must not be blank" }
        require(recipientId.isNotBlank()) { "recipientId must not be blank" }
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(senderEd25519Priv32.size == ED25519_KEY_BYTES) { "Ed25519 private key must be 32 bytes" }
        require(recipientX25519Pub32.size == X25519_KEY_BYTES) { "X25519 public key must be 32 bytes" }

        val (epkPriv, epkPub) = generateX25519KeyPair()
        val ecdhSecret = try {
            ecdhX25519(epkPriv, recipientX25519Pub32)
        } finally {
            epkPriv.fill(0)
        }
        val aesKey = try {
            deriveV3Key(ecdhSecret, eventId)
        } finally {
            ecdhSecret.fill(0)
        }

        return try {
            val contentBytes = content.toByteArray(Charsets.UTF_8)
            val sigInput = v3SignatureInput(senderId, recipientId, eventId, epkPub, contentBytes)
            val sigBytes = signEd25519(senderEd25519Priv32, sigInput)

            val inner = InnerEnvelope(
                sender = senderId,
                recipient = recipientId,
                eventId = eventId,
                content = content,
                sig = bytesToHex(sigBytes),
            )
            val innerJsonBytes = Wire.json.encodeToString(inner).toByteArray(Charsets.UTF_8)
            val padded = pad(innerJsonBytes, paddingBlockBytes)

            val iv = ByteArray(GCM_IV_BYTES).also { random.nextBytes(it) }
            val aad = v3Aad(epkPub, eventId)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(padded)

            val b64 = Base64.getEncoder()
            E2eeEnvelope(
                v = VERSION_V3,
                iv = b64.encodeToString(iv),
                ciphertext = b64.encodeToString(ciphertext),
                eventId = eventId,
                epk = b64.encodeToString(epkPub),
                sender = null,
                recipient = if (includeOuterRecipient) recipientId else null,
                mac = null,
            )
        } finally {
            aesKey.fill(0)
        }
    }

    /**
     * Legacy E2EE v2 encryption (static X25519 + Ed25519), retained for backward-compatibility tests.
     * New outbound messages should use [encrypt] / [encryptV3].
     */
    @Deprecated("Use encrypt() / encryptV3() for per-message ephemeral sender X25519 key (ephemeral-static ECIES)")
    fun encryptV2Legacy(
        senderId: String,
        recipientId: String,
        content: String,
        eventId: String,
        senderX25519Priv32: ByteArray,
        senderEd25519Priv32: ByteArray,
        recipientX25519Pub32: ByteArray,
    ): E2eeEnvelope {
        val sharedSecret = ecdhX25519(senderX25519Priv32, recipientX25519Pub32)
        val aesKey = deriveV2Key(sharedSecret, senderId, recipientId)
        sharedSecret.fill(0)

        val iv = ByteArray(GCM_IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(content.toByteArray(Charsets.UTF_8))
        aesKey.fill(0)

        val b64 = Base64.getEncoder()
        val ivB64 = b64.encodeToString(iv)
        val ctB64 = b64.encodeToString(ciphertext)
        val sigInput = v2SignatureInput(senderId, recipientId, ivB64, ctB64, eventId)
        val sigBytes = signEd25519(senderEd25519Priv32, sigInput)

        return E2eeEnvelope(
            v = VERSION_V2,
            sender = senderId,
            recipient = recipientId,
            iv = ivB64,
            ciphertext = ctB64,
            mac = bytesToHex(sigBytes),
            eventId = eventId,
        )
    }

    /**
     * Decrypts and authenticates an [E2eeEnvelope] (supports both v3 ephemeral-static ECIES and v2 legacy).
     *
     * @param expectedRecipients If non-null, verifies the authenticated recipient is in this set
     *   (e.g. `setOfNotNull(selfHash, legacyDeliveryHash)`).
     * @return [DecryptedEnvelope] on success, or `null` if decryption, authentication, or validation fails.
     */
    fun decrypt(
        envelope: E2eeEnvelope,
        recipientX25519Priv32: ByteArray,
        lookupPeerKeys: (senderId: String) -> PeerKeys?,
        expectedRecipients: Set<String>? = null,
    ): DecryptedEnvelope? = runCatching {
        when (envelope.v) {
            VERSION_V3 -> decryptV3(envelope, recipientX25519Priv32, lookupPeerKeys, expectedRecipients)
            VERSION_V2 -> decryptV2(envelope, recipientX25519Priv32, lookupPeerKeys, expectedRecipients)
            else -> null
        }
    }.getOrNull()

    /** Convenience wrapper that decodes JSON and decrypts [E2eeEnvelope] (v2 or v3). */
    fun decryptJson(
        rawJson: String,
        recipientX25519Priv32: ByteArray,
        lookupPeerKeys: (senderId: String) -> PeerKeys?,
        expectedRecipients: Set<String>? = null,
    ): DecryptedEnvelope? {
        val env = decodeEnvelope(rawJson) ?: return null
        return decrypt(env, recipientX25519Priv32, lookupPeerKeys, expectedRecipients)
    }

    fun encodeEnvelope(envelope: E2eeEnvelope): String = Wire.json.encodeToString(envelope)

    fun decodeEnvelope(rawJson: String): E2eeEnvelope? =
        runCatching { Wire.json.decodeFromString<E2eeEnvelope>(rawJson) }.getOrNull()

    private fun decryptV3(
        envelope: E2eeEnvelope,
        recipientX25519Priv32: ByteArray,
        lookupPeerKeys: (senderId: String) -> PeerKeys?,
        expectedRecipients: Set<String>?,
    ): DecryptedEnvelope? {
        val eventId = envelope.eventId?.takeIf { it.isNotBlank() } ?: return null
        val epkB64 = envelope.epk?.takeIf { it.isNotBlank() } ?: return null
        val decoder = Base64.getDecoder()
        val epkBytes = decoder.decode(epkB64)
        if (epkBytes.size != X25519_KEY_BYTES) return null

        val iv = decoder.decode(envelope.iv)
        if (iv.size != GCM_IV_BYTES) return null
        val ct = decoder.decode(envelope.ciphertext)

        val ecdhSecret = ecdhX25519(recipientX25519Priv32, epkBytes)
        val aesKey = try {
            deriveV3Key(ecdhSecret, eventId)
        } finally {
            ecdhSecret.fill(0)
        }

        val paddedPlaintext = try {
            val aad = v3Aad(epkBytes, eventId)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(ct)
        } finally {
            aesKey.fill(0)
        }

        val innerBytes = unpad(paddedPlaintext) ?: return null
        val inner = runCatching {
            Wire.json.decodeFromString<InnerEnvelope>(innerBytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return null

        if (inner.eventId != eventId) return null
        if (envelope.recipient != null && envelope.recipient != inner.recipient) return null
        if (expectedRecipients != null && inner.recipient !in expectedRecipients) return null

        val peerKeys = lookupPeerKeys(inner.sender) ?: return null
        val sigBytes = hexToBytesOrNull(inner.sig) ?: return null
        if (sigBytes.size != ED25519_SIG_BYTES) return null

        val sigInput = v3SignatureInput(
            inner.sender,
            inner.recipient,
            inner.eventId,
            epkBytes,
            inner.content.toByteArray(Charsets.UTF_8),
        )
        if (!verifyEd25519(peerKeys.ed25519Pub32, sigInput, sigBytes)) return null

        return DecryptedEnvelope(
            version = VERSION_V3,
            sender = inner.sender,
            recipient = inner.recipient,
            eventId = inner.eventId,
            content = inner.content,
        )
    }

    private fun decryptV2(
        envelope: E2eeEnvelope,
        recipientX25519Priv32: ByteArray,
        lookupPeerKeys: (senderId: String) -> PeerKeys?,
        expectedRecipients: Set<String>?,
    ): DecryptedEnvelope? {
        val sender = envelope.sender?.takeIf { it.isNotBlank() } ?: return null
        val recipient = envelope.recipient?.takeIf { it.isNotBlank() } ?: return null
        val mac = envelope.mac?.takeIf { it.isNotBlank() } ?: return null
        if (expectedRecipients != null && recipient !in expectedRecipients) return null

        val peerKeys = lookupPeerKeys(sender) ?: return null
        val sigBytes = hexToBytesOrNull(mac) ?: return null
        if (sigBytes.size != ED25519_SIG_BYTES) return null

        val sigInput = v2SignatureInput(sender, recipient, envelope.iv, envelope.ciphertext, envelope.eventId)
        if (!verifyEd25519(peerKeys.ed25519Pub32, sigInput, sigBytes)) return null

        val sharedSecret = ecdhX25519(recipientX25519Priv32, peerKeys.x25519Pub32)
        val aesKey = deriveV2Key(sharedSecret, sender, recipient)
        sharedSecret.fill(0)

        return try {
            val decoder = Base64.getDecoder()
            val iv = decoder.decode(envelope.iv)
            val cipherBytes = decoder.decode(envelope.ciphertext)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            DecryptedEnvelope(
                version = VERSION_V2,
                sender = sender,
                recipient = recipient,
                eventId = envelope.eventId,
                content = plaintext,
            )
        } finally {
            aesKey.fill(0)
        }
    }

    internal fun v3Aad(epk32: ByteArray, msgId: String): ByteArray {
        val prefix = V3_AAD_PREFIX.toByteArray(Charsets.UTF_8)
        val sep = ":".toByteArray(Charsets.UTF_8)
        val idBytes = msgId.toByteArray(Charsets.UTF_8)
        return ByteArray(prefix.size + epk32.size + sep.size + idBytes.size).apply {
            System.arraycopy(prefix, 0, this, 0, prefix.size)
            System.arraycopy(epk32, 0, this, prefix.size, epk32.size)
            System.arraycopy(sep, 0, this, prefix.size + epk32.size, sep.size)
            System.arraycopy(idBytes, 0, this, prefix.size + epk32.size + sep.size, idBytes.size)
        }
    }

    internal fun pad(payload: ByteArray, blockSize: Int = DEFAULT_PADDING_BLOCK_BYTES): ByteArray {
        val effectiveBlock = blockSize.coerceAtLeast(1)
        val rawLen = 4 + payload.size
        val paddedLen = ((rawLen + effectiveBlock - 1) / effectiveBlock) * effectiveBlock
        val out = ByteArray(paddedLen)
        out[0] = ((payload.size ushr 24) and 0xFF).toByte()
        out[1] = ((payload.size ushr 16) and 0xFF).toByte()
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        out[3] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    internal fun unpad(padded: ByteArray): ByteArray? {
        if (padded.size < 4) return null
        val len = ((padded[0].toInt() and 0xFF) shl 24) or
            ((padded[1].toInt() and 0xFF) shl 16) or
            ((padded[2].toInt() and 0xFF) shl 8) or
            (padded[3].toInt() and 0xFF)
        if (len < 0 || 4 + len > padded.size) return null
        return padded.copyOfRange(4, 4 + len)
    }

    private fun v3SignatureInput(
        sender: String,
        recipient: String,
        eventId: String,
        epk32: ByteArray,
        contentBytes: ByteArray,
    ): ByteArray {
        val epkB64 = Base64.getEncoder().encodeToString(epk32)
        val contentHash = Wire.sha256Hex(contentBytes)
        return "pulse4-e2ee-v3-sig:$sender:$recipient:$eventId:$epkB64:$contentHash".toByteArray(Charsets.UTF_8)
    }

    private fun v2SignatureInput(
        sender: String,
        recipient: String,
        ivB64: String,
        ciphertextB64: String,
        eventId: String?,
    ): ByteArray = "pulse4-v2:$sender:$recipient:$ivB64:$ciphertextB64:${eventId ?: ""}".toByteArray(Charsets.UTF_8)

    private fun deriveV2Key(sharedSecret: ByteArray, sender: String, recipient: String): ByteArray {
        val (a, b) = if (sender <= recipient) sender to recipient else recipient to sender
        val md = MessageDigest.getInstance("SHA-256")
        md.update(sharedSecret)
        md.update("pulse4-e2ee-v2".toByteArray(Charsets.UTF_8))
        md.update(a.toByteArray(Charsets.UTF_8))
        md.update(b.toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    /**
     * Detached Ed25519 signature over caller-provided bytes (e.g. guardian-consent canonical bytes, which carry
     * their own domain header). Independent from the E2EE v3 envelope signature.
     */
    fun signDetached(priv32: ByteArray, bytes: ByteArray): ByteArray {
        require(priv32.size == ED25519_KEY_BYTES) { "Ed25519 private key must be 32 bytes" }
        return signEd25519(priv32, bytes)
    }

    /** Verifies a [signDetached] signature; returns `false` (never throws) for malformed keys or signatures. */
    fun verifyDetached(pub32: ByteArray, bytes: ByteArray, sig: ByteArray): Boolean {
        if (pub32.size != ED25519_KEY_BYTES || sig.size != ED25519_SIG_BYTES) return false
        return try {
            verifyEd25519(pub32, bytes, sig)
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun signEd25519(priv32: ByteArray, data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(priv32, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    private fun verifyEd25519(pub32: ByteArray, data: ByteArray, sig64: ByteArray): Boolean {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(pub32, 0))
        verifier.update(data, 0, data.size)
        return verifier.verifySignature(sig64)
    }

    private fun isAllZeroConstantTime(bytes: ByteArray): Boolean {
        var acc = 0
        for (b in bytes) {
            acc = acc or (b.toInt() and 0xFF)
        }
        return acc == 0
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytesOrNull(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
