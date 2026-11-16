package app.pulse4.android.reticulum

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Reticulum (RNS 1.0–1.5+) kryptografické a paketové primitivy:
 * - HKDF-SHA256
 * - RNS Fernet (AES-256-CBC + PKCS5/7 + HMAC-SHA256)
 * - RNS Link MTU signalling (3 bajty, RNS 0.9.3+)
 * - RNS Announce parser & Ed25519 ověření (včetně RNS 0.9+ ratchet flagu)
 * - RNS Link handshake (LinkRequest -> LinkProof -> LinkRTT)
 */
object RnsCrypto {
    const val PATH_REQUEST_DEST = "6b9f66014d9853faab220fba47d02761"
    const val DEFAULT_LINK_MTU = 32768 // TCP/Wi-Fi Link MTU (RNS 0.9.3+ discovery)

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray = ByteArray(0), length: Int = 64): ByteArray {
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(32) else salt
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, copyLen)
            offset += copyLen
            counter++
        }
        return out
    }

    /** Reticulum kompaktní Fernet: IV (16B) + AES-256-CBC(plaintext) + HMAC-SHA256 (32B). */
    class RnsFernet(key64: ByteArray) {
        private val signingKey = key64.copyOfRange(0, 32)
        private val encryptionKey = key64.copyOfRange(32, 64)

        fun encrypt(plaintext: ByteArray): ByteArray {
            val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
            val ct = cipher.doFinal(plaintext)
            val signedPart = iv + ct
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(signingKey, "HmacSHA256"))
            val hmac = mac.doFinal(signedPart)
            return signedPart + hmac
        }

        fun decrypt(token: ByteArray): ByteArray? = runCatching {
            if (token.size < 48) return null
            val signedPart = token.copyOfRange(0, token.size - 32)
            val actualHmac = token.copyOfRange(token.size - 32, token.size)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(signingKey, "HmacSHA256"))
            val expectedHmac = mac.doFinal(signedPart)
            if (!MessageDigest.isEqual(expectedHmac, actualHmac)) return null

            val iv = signedPart.copyOfRange(0, 16)
            val ct = signedPart.copyOfRange(16, signedPart.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
            cipher.doFinal(ct)
        }.getOrNull()
    }

    /** Zašifruje data pro veřejný X25519 klíč cílové identity (Reticulum Identity.encrypt). */
    fun encryptForIdentity(plaintext: ByteArray, recipientX25519Pub: ByteArray, recipientIdentityHash: ByteArray): ByteArray {
        val ephSeed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ephPriv = X25519PrivateKeyParameters(ephSeed, 0)
        val ephPub = ephPriv.generatePublicKey().encoded
        val recPub = X25519PublicKeyParameters(recipientX25519Pub, 0)
        val agreement = X25519Agreement().apply { init(ephPriv) }
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(recPub, shared, 0)
        val derived = hkdfSha256(shared, salt = recipientIdentityHash, length = 64)
        return ephPub + RnsFernet(derived).encrypt(plaintext)
    }

    /** Dešifruje data šifrovaná pro naši identitu (Reticulum Identity.decrypt). */
    fun decryptForIdentity(ciphertext: ByteArray, selfX25519Priv: X25519PrivateKeyParameters, selfIdentityHash: ByteArray): ByteArray? = runCatching {
        if (ciphertext.size < 32 + 48) return null
        val ephPub = X25519PublicKeyParameters(ciphertext, 0)
        val agreement = X25519Agreement().apply { init(selfX25519Priv) }
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(ephPub, shared, 0)
        val derived = hkdfSha256(shared, salt = selfIdentityHash, length = 64)
        RnsFernet(derived).decrypt(ciphertext.copyOfRange(32, ciphertext.size))
    }.getOrNull()

    /** RNS 0.9.3+ Link signalling bytes (3B): 21 bitů MTU + 3 bity mode. */
    fun encodeSignalling(mtu: Int = DEFAULT_LINK_MTU, mode: Int = 1): ByteArray {
        val value = ((mtu and 0x1FFFFF) shl 3) or (mode and 0x07)
        return byteArrayOf(
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
    }

    fun decodeSignalling(bytes: ByteArray, offset: Int = 0): Pair<Int, Int> {
        val value = ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
        val mtu = (value ushr 3) and 0x1FFFFF
        val mode = value and 0x07
        return mtu to mode
    }

    fun buildPathRequestPacket(targetDest16: ByteArray, transportId16: ByteArray? = null): ByteArray {
        val destBytes = ReticulumBridge.hexToBytes(PATH_REQUEST_DEST)
        val tag = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val payload = if (transportId16 != null) targetDest16 + transportId16 + tag else targetDest16 + tag
        return byteArrayOf(0x00, 0x00) + destBytes + byteArrayOf(0x00) + payload
    }
}

data class ParsedRnsAnnounce(
    val destinationHash: String,
    val identityHash: String,
    val publicKey64: ByteArray,
    val nameHash10: ByteArray,
    val ratchetPub32: ByteArray?,
    val appData: ByteArray,
    val verified: Boolean,
)

object RnsAnnounceParser {
    /**
     * Parsuje a ověří Ed25519 podpis RNS ANNOUNCE paketu (HEADER_1 i HEADER_2, s podporou RNS 0.9+ ratchet flagu).
     */
    fun parse(packet: ByteArray): ParsedRnsAnnounce? = runCatching {
        if (packet.size < 19) return null
        val flags = packet[0].toInt() and 0xFF
        val packetType = flags and 0x03
        if (packetType != 0x01) return null

        val headerType = (flags and 0x40) shr 6
        val contextFlag = (flags and 0x20) shr 5
        val destOffset = if (headerType == 1) 18 else 2
        val payloadOffset = if (headerType == 1) 35 else 19
        if (packet.size < payloadOffset + 64 + 10 + 10 + 64) return null

        val dest16 = packet.copyOfRange(destOffset, destOffset + 16)
        val payload = packet.copyOfRange(payloadOffset, packet.size)

        val pubKey64 = payload.copyOfRange(0, 64)
        val nameHash10 = payload.copyOfRange(64, 74)
        val randomHash10 = payload.copyOfRange(74, 84)
        val ratchetSize = if (contextFlag == 1) 32 else 0
        if (payload.size < 84 + ratchetSize + 64) return null

        val ratchetPub32 = if (ratchetSize > 0) payload.copyOfRange(84, 116) else null
        val sigOffset = 84 + ratchetSize
        val sig64 = payload.copyOfRange(sigOffset, sigOffset + 64)
        val appData = payload.copyOfRange(sigOffset + 64, payload.size)

        val sha = MessageDigest.getInstance("SHA-256")
        val identHash = sha.digest(pubKey64).copyOf(16)
        val expectedDest = sha.digest(nameHash10 + identHash).copyOf(16)
        if (!expectedDest.contentEquals(dest16)) return null

        val signedData = dest16 + pubKey64 + nameHash10 + randomHash10 + (ratchetPub32 ?: ByteArray(0)) + appData
        val edPub = Ed25519PublicKeyParameters(pubKey64, 32)
        val verifier = Ed25519Signer()
        verifier.init(false, edPub)
        verifier.update(signedData, 0, signedData.size)
        val verified = verifier.verifySignature(sig64)

        ParsedRnsAnnounce(
            destinationHash = ReticulumBridge.bytesToHex(dest16),
            identityHash = ReticulumBridge.bytesToHex(identHash),
            publicKey64 = pubKey64,
            nameHash10 = nameHash10,
            ratchetPub32 = ratchetPub32,
            appData = appData,
            verified = verified,
        )
    }.getOrNull()
}

/**
 * Stavová relace Reticulum Link (Initiator):
 * 1. Vytvoří LinkRequest + spočítá LinkId.
 * 2. Ověří LinkProof z cílového uzlu a odvodí RNS Fernet klíč.
 * 3. Odešle LinkRTT a přejde do stavu ACTIVE pro šifrované DATA / REQUEST pakety.
 */
class RnsLinkSession(
    val destinationHash: String,
    val destinationPubKey64: ByteArray?,
    val offeredMtu: Int = RnsCrypto.DEFAULT_LINK_MTU,
) {
    enum class State { PENDING, ACTIVE, CLOSED }

    var state: State = State.PENDING
        private set
    var negotiatedMtu: Int = 500
        private set

    private val ephSeed = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val ephXPriv = X25519PrivateKeyParameters(ephSeed, 0)
    private val ephXPub32 = ephXPriv.generatePublicKey().encoded
    private val ephEdPriv = Ed25519PrivateKeyParameters(ByteArray(32).also { SecureRandom().nextBytes(it) }, 0)
    private val ephEdPub32 = ephEdPriv.generatePublicKey().encoded
    private val signalling3 = RnsCrypto.encodeSignalling(offeredMtu, mode = 1)
    private val createdAtMs = System.currentTimeMillis()
    private var fernet: RnsCrypto.RnsFernet? = null

    val linkIdBytes: ByteArray
    val linkIdHex: String

    init {
        val dest16 = ReticulumBridge.hexToBytes(destinationHash)
        // Reticulum LinkId = SHA-256(hashable_part_without_hops_and_signalling)[0:16]
        val hashableFlags = byteArrayOf(0x03) // HEADER_1, LINKREQUEST
        val hashable = hashableFlags + dest16 + byteArrayOf(0x00) + ephXPub32 + ephEdPub32
        linkIdBytes = MessageDigest.getInstance("SHA-256").digest(hashable).copyOf(16)
        linkIdHex = ReticulumBridge.bytesToHex(linkIdBytes)
    }

    fun buildLinkRequestPacket(transportId16: ByteArray? = null): ByteArray {
        val dest16 = ReticulumBridge.hexToBytes(destinationHash)
        val payload = ephXPub32 + ephEdPub32 + signalling3
        return if (transportId16 != null) {
            byteArrayOf(0x53.toByte(), 0x00) + transportId16 + dest16 + byteArrayOf(0x00) + payload
        } else {
            byteArrayOf(0x03, 0x00) + dest16 + byteArrayOf(0x00) + payload
        }
    }

    /** Zpracuje příchozí LRPROOF (context = 0xFF) a vrátí paket LRRTT pro dokončení handshake. */
    fun handleLinkProof(proofPayload: ByteArray): ByteArray? {
        if (state != State.PENDING) return null
        if (proofPayload.size < 64 + 32) return null
        val sig64 = proofPayload.copyOfRange(0, 64)
        val peerXPub32 = proofPayload.copyOfRange(64, 96)
        val peerSigBytes = if (proofPayload.size >= 99) proofPayload.copyOfRange(96, 99) else null
        negotiatedMtu = peerSigBytes?.let { RnsCrypto.decodeSignalling(it).first.coerceAtLeast(500) } ?: 500

        // Pokud známe Ed25519 klíč cíle, ověříme podpis nad linkId + peerXPub + peerEdPub + signalling
        destinationPubKey64?.let { pub64 ->
            val peerEdPub32 = pub64.copyOfRange(32, 64)
            val signed = linkIdBytes + peerXPub32 + peerEdPub32 + (peerSigBytes ?: ByteArray(0))
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(peerEdPub32, 0))
            verifier.update(signed, 0, signed.size)
            if (!verifier.verifySignature(sig64)) return null
        }

        val agreement = X25519Agreement().apply { init(ephXPriv) }
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerXPub32, 0), shared, 0)
        val derived = RnsCrypto.hkdfSha256(shared, salt = linkIdBytes, length = 64)
        fernet = RnsCrypto.RnsFernet(derived)
        state = State.ACTIVE

        val rttSec = ((System.currentTimeMillis() - createdAtMs).coerceAtLeast(1L)) / 1000.0
        val rttPayload = MsgPack.encode(rttSec)
        return buildEncryptedLinkPacket(context = 0xFE.toByte(), plaintext = rttPayload)
    }

    fun buildEncryptedLinkPacket(context: Byte, plaintext: ByteArray): ByteArray? {
        val f = fernet ?: return null
        val ct = f.encrypt(plaintext)
        // HEADER_1, DATA, DEST_TYPE_LINK (0x03 shl 2 = 0x0C)
        val pkt = byteArrayOf(0x0C, 0x00) + linkIdBytes + byteArrayOf(context) + ct
        if (pkt.size > negotiatedMtu) {
            return null
        }
        return pkt
    }

    fun decryptLinkPayload(ciphertext: ByteArray): ByteArray? = fernet?.decrypt(ciphertext)

    fun buildRequestPacket(path: String, data: Any?): Pair<ByteArray, ByteArray>? {
        val pathHash = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8)).copyOf(16)
        val reqTime = System.currentTimeMillis() / 1000.0
        val packed = MsgPack.encode(listOf(reqTime, pathHash, data))
        val reqId = MessageDigest.getInstance("SHA-256").digest(packed).copyOf(16)
        val pkt = buildEncryptedLinkPacket(context = 0x09, plaintext = packed) ?: return null
        return reqId to pkt
    }

    fun close() {
        state = State.CLOSED
    }
}
