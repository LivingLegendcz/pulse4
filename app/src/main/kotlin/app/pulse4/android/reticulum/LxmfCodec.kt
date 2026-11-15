package app.pulse4.android.reticulum

import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

data class LxmfMessage(
    val destinationHash: String,
    val sourceHash: String,
    val messageId: String,
    val timestampSec: Double,
    val title: String,
    val content: ByteArray,
    val fields: Map<Any?, Any?>,
    val stamp: ByteArray? = null,
    val signatureValid: Boolean,
)

data class LxmfPropagationNodeInfo(
    val timebaseSec: Long,
    val acceptingMessages: Boolean,
    val perTransferLimitKb: Long,
    val perSyncLimitKb: Long,
    val stampCost: Int,
    val stampCostFlexibility: Int,
    val peeringCost: Int,
    val metadata: Map<Any?, Any?>,
)

object LxmfCodec {
    const val PROPAGATION_APP_NAME = "lxmf.propagation"
    const val DELIVERY_APP_NAME = "lxmf.delivery"
    const val OFFER_REQUEST_PATH = "/offer"
    const val MESSAGE_GET_PATH = "/get"

    /**
     * Zabalí a podepíše LXMF zprávu podle standardního wire formátu:
     * [dest 16B][source 16B][Ed25519 sig 64B][MsgPack payload]
     */
    fun pack(
        destinationHash: String,
        sourceHash: String,
        edPriv: Ed25519PrivateKeyParameters,
        content: ByteArray,
        title: String = "",
        fields: Map<Any?, Any?> = emptyMap(),
        timestampSec: Double = System.currentTimeMillis() / 1000.0,
        stamp: ByteArray? = null,
    ): ByteArray {
        val dest16 = ReticulumBridge.hexToBytes(destinationHash)
        val source16 = ReticulumBridge.hexToBytes(sourceHash)
        require(dest16.size == 16 && source16.size == 16) { "Invalid LXMF hash length" }

        val baseList = listOf(timestampSec, title.toByteArray(Charsets.UTF_8), content, fields)
        val basePayload = MsgPack.encode(baseList)
        val hashedPart = dest16 + source16 + basePayload
        val messageId = MessageDigest.getInstance("SHA-256").digest(hashedPart)
        val signedPart = hashedPart + messageId

        val signer = Ed25519Signer().apply { init(true, edPriv) }
        signer.update(signedPart, 0, signedPart.size)
        val sig64 = signer.generateSignature()

        val finalPayload = if (stamp != null) {
            MsgPack.encode(baseList + listOf(stamp))
        } else {
            basePayload
        }
        return dest16 + source16 + sig64 + finalPayload
    }

    /** Rozbalí LXMF wire zprávu a volitelně ověří Ed25519 podpis odesílatele. */
    fun unpack(wire: ByteArray, senderEdPub32: ByteArray? = null): LxmfMessage? = runCatching {
        if (wire.size < 16 + 16 + 64 + 2) return null
        val dest16 = wire.copyOfRange(0, 16)
        val source16 = wire.copyOfRange(16, 32)
        val sig64 = wire.copyOfRange(32, 96)
        val payloadBytes = wire.copyOfRange(96, wire.size)

        val decoded = MsgPack.decode(payloadBytes).first as? List<*> ?: return null
        if (decoded.size < 4) return null

        val ts = (decoded[0] as? Number)?.toDouble() ?: return null
        val titleBytes = when (val t = decoded[1]) {
            is ByteArray -> t
            is String -> t.toByteArray(Charsets.UTF_8)
            else -> ByteArray(0)
        }
        val contentBytes = when (val c = decoded[2]) {
            is ByteArray -> c
            is String -> c.toByteArray(Charsets.UTF_8)
            else -> return null
        }
        @Suppress("UNCHECKED_CAST")
        val fields = (decoded[3] as? Map<Any?, Any?>) ?: emptyMap()
        val stamp = decoded.getOrNull(4) as? ByteArray

        val basePayload = if (decoded.size > 4) {
            MsgPack.encode(decoded.subList(0, 4))
        } else {
            payloadBytes
        }
        val hashedPart = dest16 + source16 + basePayload
        val messageId = MessageDigest.getInstance("SHA-256").digest(hashedPart)
        val signedPart = hashedPart + messageId

        val sigValid = if (senderEdPub32 != null && senderEdPub32.size == 32) {
            val verifier = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(senderEdPub32, 0)) }
            verifier.update(signedPart, 0, signedPart.size)
            verifier.verifySignature(sig64)
        } else {
            false
        }

        LxmfMessage(
            destinationHash = ReticulumBridge.bytesToHex(dest16),
            sourceHash = ReticulumBridge.bytesToHex(source16),
            messageId = ReticulumBridge.bytesToHex(messageId),
            timestampSec = ts,
            title = String(titleBytes, Charsets.UTF_8),
            content = contentBytes,
            fields = fields,
            stamp = stamp,
            signatureValid = sigValid,
        )
    }.getOrNull()

    /** Parsuje 7-prvkové MessagePack appData z Announce LXMF Propagation Uzlu. */
    fun parsePropagationAnnounceAppData(appData: ByteArray): LxmfPropagationNodeInfo? = runCatching {
        val list = MsgPack.decode(appData).first as? List<*> ?: return null
        if (list.size < 6) return null
        val timebase = (list[1] as? Number)?.toLong() ?: 0L
        val accepting = (list[2] as? Boolean) ?: false
        val transferKb = (list[3] as? Number)?.toLong() ?: 0L
        val syncKb = (list[4] as? Number)?.toLong() ?: 0L
        val costs = list[5] as? List<*> ?: emptyList<Any?>()
        val stampCost = (costs.getOrNull(0) as? Number)?.toInt() ?: 0
        val stampFlex = (costs.getOrNull(1) as? Number)?.toInt() ?: 0
        val peeringCost = (costs.getOrNull(2) as? Number)?.toInt() ?: 0
        @Suppress("UNCHECKED_CAST")
        val metadata = (list.getOrNull(6) as? Map<Any?, Any?>) ?: emptyMap()

        LxmfPropagationNodeInfo(
            timebaseSec = timebase,
            acceptingMessages = accepting,
            perTransferLimitKb = transferKb,
            perSyncLimitKb = syncKb,
            stampCost = stampCost,
            stampCostFlexibility = stampFlex,
            peeringCost = peeringCost,
            metadata = metadata,
        )
    }.getOrNull()

    /**
     * Zabalí LXMF zprávu do šifrované obálky pro LXMF Propagation Node:
     * [timestampSec, [dest16 + RnsCrypto.encryptForIdentity(lxmf[16..end])]]
     */
    fun packPropagationDeposit(
        lxmfWire: ByteArray,
        recipientX25519Pub32: ByteArray,
        recipientIdentityHash16: ByteArray,
        timestampSec: Double = System.currentTimeMillis() / 1000.0,
    ): ByteArray {
        val dest16 = lxmfWire.copyOfRange(0, 16)
        val encrypted = dest16 + RnsCrypto.encryptForIdentity(
            plaintext = lxmfWire.copyOfRange(16, lxmfWire.size),
            recipientX25519Pub = recipientX25519Pub32,
            recipientIdentityHash = recipientIdentityHash16,
        )
        return MsgPack.encode(listOf(timestampSec, listOf(encrypted)))
    }

    /** Rozbalí zprávu staženou z LXMF Propagation Node. */
    fun unpackPropagationMessage(
        storedBytes: ByteArray,
        selfX25519Priv: org.bouncycastle.crypto.params.X25519PrivateKeyParameters,
        selfIdentityHash16: ByteArray,
    ): ByteArray? = runCatching {
        if (storedBytes.size <= 16) return null
        val dest16 = storedBytes.copyOfRange(0, 16)
        val decrypted = RnsCrypto.decryptForIdentity(
            ciphertext = storedBytes.copyOfRange(16, storedBytes.size),
            selfX25519Priv = selfX25519Priv,
            selfIdentityHash = selfIdentityHash16,
        ) ?: return null
        dest16 + decrypted
    }.getOrNull()

    fun buildGetMessagesRequest(wantIds: List<ByteArray> = emptyList(), haveIds: List<ByteArray> = emptyList()): List<Any> =
        listOf(wantIds, haveIds)
}

/**
 * LXMF Proof-of-Work Stamper (LXStamper): ochrana propagation uzlů proti spamu.
 */
object LxmfStamper {
    const val DEFAULT_WORKBLOCK_ROUNDS = 256

    fun workblock(material: ByteArray, expandRounds: Int = DEFAULT_WORKBLOCK_ROUNDS): ByteArray {
        val out = ByteArray(expandRounds * 256)
        val sha = MessageDigest.getInstance("SHA-256")
        for (n in 0 until expandRounds) {
            val nPack = MsgPack.encode(n.toLong())
            val salt = sha.digest(material + nPack)
            val block = RnsCrypto.hkdfSha256(ikm = material, salt = salt, length = 256)
            System.arraycopy(block, 0, out, n * 256, 256)
        }
        return out
    }

    fun stampValue(workblock: ByteArray, stamp: ByteArray): Int {
        val hash = MessageDigest.getInstance("SHA-256").digest(workblock + stamp)
        var bits = 0
        for (b in hash) {
            val v = b.toInt() and 0xFF
            if (v == 0) {
                bits += 8
            } else {
                bits += Integer.numberOfLeadingZeros(v) - 24
                break
            }
        }
        return bits
    }

    fun verifyStamp(stamp: ByteArray, material: ByteArray, targetCost: Int, expandRounds: Int = DEFAULT_WORKBLOCK_ROUNDS): Boolean {
        if (targetCost <= 0) return true
        val wb = workblock(material, expandRounds)
        return stampValue(wb, stamp) >= targetCost
    }

    fun generateStamp(
        material: ByteArray,
        targetCost: Int,
        expandRounds: Int = DEFAULT_WORKBLOCK_ROUNDS,
        maxAttempts: Int = 500_000,
    ): ByteArray? {
        if (targetCost <= 0) return ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wb = workblock(material, expandRounds)
        val rng = SecureRandom()
        val candidate = ByteArray(32)
        repeat(maxAttempts) {
            rng.nextBytes(candidate)
            if (stampValue(wb, candidate) >= targetCost) {
                return candidate.copyOf()
            }
        }
        return null
    }
}
