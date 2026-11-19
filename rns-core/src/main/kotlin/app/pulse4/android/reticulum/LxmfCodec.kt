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
    const val LEGACY_DELIVERY_APP_NAME = "pulse.delivery"
    const val OFFER_REQUEST_PATH = "/offer"
    const val MESSAGE_GET_PATH = "/get"
    const val ERROR_NO_IDENTITY = 0xF0
    const val ERROR_NO_ACCESS = 0xF1
    const val ERROR_INVALID_KEY = 0xF3
    const val ERROR_INVALID_DATA = 0xF4
    const val ERROR_INVALID_STAMP = 0xF5
    const val ERROR_THROTTLED = 0xF6
    const val ERROR_NOT_FOUND = 0xFD
    const val ERROR_TIMEOUT = 0xFE

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
        val dest16 = RnsCrypto.hexToBytes(destinationHash)
        val source16 = RnsCrypto.hexToBytes(sourceHash)
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
            destinationHash = RnsCrypto.bytesToHex(dest16),
            sourceHash = RnsCrypto.bytesToHex(source16),
            messageId = RnsCrypto.bytesToHex(messageId),
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
        val extCosts = list.getOrNull(7) as? List<*> ?: emptyList<Any?>()
        val rawStampCost = (extCosts.getOrNull(0) as? Number)?.toDouble()
            ?: (costs.getOrNull(0) as? Number)?.toDouble()
            ?: 0.0
        val stampCost = kotlin.math.ceil(rawStampCost).toInt()
        val stampFlex = (extCosts.getOrNull(1) as? Number)?.toInt()
            ?: (costs.getOrNull(1) as? Number)?.toInt()
            ?: 0
        val peeringCost = (extCosts.getOrNull(2) as? Number)?.toInt()
            ?: (costs.getOrNull(2) as? Number)?.toInt()
            ?: 0
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
     * Vytvoří zašifrovaný payload pro uložení na LXMF Propagation Node (bez stampu):
     * `dest16 + RnsCrypto.encryptForIdentity(lxmf[16..end])`.
     */
    fun encryptPropagationPayload(
        lxmfWire: ByteArray,
        recipientX25519Pub32: ByteArray,
        recipientIdentityHash16: ByteArray,
    ): ByteArray {
        val dest16 = lxmfWire.copyOfRange(0, 16)
        return dest16 + RnsCrypto.encryptForIdentity(
            plaintext = lxmfWire.copyOfRange(16, lxmfWire.size),
            recipientX25519Pub = recipientX25519Pub32,
            recipientIdentityHash = recipientIdentityHash16,
        )
    }

    /**
     * Zabalí LXMF zprávu do šifrované obálky pro LXMF Propagation Node:
     * `[timestampSec, [dest16 + enc (+ optional 32B pnStamp)]]`
     */
    fun packPropagationDeposit(
        lxmfWire: ByteArray,
        recipientX25519Pub32: ByteArray,
        recipientIdentityHash16: ByteArray,
        timestampSec: Double = System.currentTimeMillis() / 1000.0,
        pnStamp: ByteArray? = null,
    ): ByteArray {
        val encrypted = encryptPropagationPayload(lxmfWire, recipientX25519Pub32, recipientIdentityHash16)
        val lxd = if (pnStamp != null) encrypted + pnStamp else encrypted
        return MsgPack.encode(listOf(timestampSec, listOf(lxd)))
    }

    /** Zabalí již zašifrovaný `lxd` (dest16 + enc + optional stamp) do deposit obálky. */
    fun packPropagationDepositRaw(
        lxd: ByteArray,
        timestampSec: Double = System.currentTimeMillis() / 1000.0,
    ): ByteArray = MsgPack.encode(listOf(timestampSec, listOf(lxd)))

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

    /**
     * Dvoufázový `/get` požadavek dle `LXMRouter.message_get_request`:
     * - Fáze 1: `[null, null]` -> vrací seznam dostupných transient ID
     * - Fáze 2: `[wantIds, haveIds, transferLimitKb]` -> stáhne `wantIds` (s limitem v KB, `LXMRouter.py:1746`) a smaže `haveIds`
     */
    fun buildGetMessagesRequest(
        wantIds: List<ByteArray>? = null,
        haveIds: List<ByteArray>? = null,
        transferLimitKb: Int? = null,
    ): List<Any?> = if (transferLimitKb != null) {
        listOf(wantIds, haveIds, transferLimitKb)
    } else {
        listOf(wantIds, haveIds)
    }
}

/**
 * LXMF Proof-of-Work Stamper (LXStamper): ochrana propagation uzlů proti spamu.
 */
object LxmfStamper {
    const val DEFAULT_WORKBLOCK_ROUNDS = 3000
    const val PN_WORKBLOCK_ROUNDS = 1000
    const val PEERING_WORKBLOCK_ROUNDS = 25
    const val STAMP_SIZE = 32
    const val MAX_ACCEPTABLE_STAMP_COST = 24

    fun defaultMaxAttemptsForCost(targetCost: Int): Long {
        if (targetCost <= 0) return 1L
        val clamped = targetCost.coerceAtMost(MAX_ACCEPTABLE_STAMP_COST)
        return (1L shl clamped) * 8L
    }

    fun transientId(destPlusEncrypted: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(destPlusEncrypted)

    fun workblock(
        material: ByteArray,
        expandRounds: Int = DEFAULT_WORKBLOCK_ROUNDS,
        isCancelled: () -> Boolean = { false },
    ): ByteArray {
        val out = ByteArray(expandRounds * 256)
        val sha = MessageDigest.getInstance("SHA-256")
        for (n in 0 until expandRounds) {
            if ((n and 0x3F) == 0 && isCancelled()) return ByteArray(0)
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
        maxAttempts: Int,
        isCancelled: () -> Boolean = { false },
    ): ByteArray? = generateStamp(
        material = material,
        targetCost = targetCost,
        expandRounds = expandRounds,
        maxAttempts = maxAttempts.toLong(),
        isCancelled = isCancelled,
    )

    fun generateStamp(
        material: ByteArray,
        targetCost: Int,
        expandRounds: Int = DEFAULT_WORKBLOCK_ROUNDS,
        maxAttempts: Long = defaultMaxAttemptsForCost(targetCost),
        isCancelled: () -> Boolean = { false },
    ): ByteArray? {
        if (isCancelled()) return null
        if (targetCost > MAX_ACCEPTABLE_STAMP_COST) return null
        if (targetCost <= 0) return ByteArray(STAMP_SIZE).also { SecureRandom().nextBytes(it) }
        val wb = workblock(material, expandRounds, isCancelled)
        if (wb.isEmpty() || isCancelled()) return null
        val rng = SecureRandom()
        val candidate = ByteArray(STAMP_SIZE)
        var attempt = 0L
        while (attempt < maxAttempts) {
            if ((attempt and 0xFFL) == 0L && isCancelled()) return null
            rng.nextBytes(candidate)
            if (stampValue(wb, candidate) >= targetCost) {
                return candidate.copyOf()
            }
            attempt++
        }
        return null
    }
}
