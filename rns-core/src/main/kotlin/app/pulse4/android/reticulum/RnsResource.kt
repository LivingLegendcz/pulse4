package app.pulse4.android.reticulum

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.ceil
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream

object RnsResourceConstants {
    const val WINDOW = 4
    const val WINDOW_MIN = 2
    const val WINDOW_MAX_SLOW = 10
    const val WINDOW_MAX_VERY_SLOW = 4
    const val WINDOW_MAX_FAST = 75
    const val WINDOW_MAX = WINDOW_MAX_FAST
    const val WINDOW_FLEXIBILITY = 4

    const val MAPHASH_LEN = 4
    const val RANDOM_HASH_SIZE = 4
    const val DEFAULT_LINK_MDU = 431
    const val MAX_EFFICIENT_SIZE = 1 * 1024 * 1024 - 1
    const val METADATA_MAX_SIZE = 16 * 1024 * 1024 - 1
    const val AUTO_COMPRESS_MAX_SIZE = 64 * 1024 * 1024
    const val DEFAULT_MAX_DECOMPRESSED_SIZE = 4 * 1024 * 1024

    const val PART_TIMEOUT_FACTOR = 4
    const val PART_TIMEOUT_FACTOR_AFTER_RTT = 2
    const val PROOF_TIMEOUT_FACTOR = 3
    const val MAX_RETRIES = 16
    const val MAX_ADV_RETRIES = 4
    const val MAX_PROOF_RETRIES = 3
    const val SENDER_GRACE_TIME_MS = 10_000L
    const val PROCESSING_GRACE_MS = 1_000L
    const val RETRY_GRACE_TIME_MS = 250L
    const val PER_RETRY_DELAY_MS = 500L

    const val HASHMAP_IS_NOT_EXHAUSTED = 0x00
    const val HASHMAP_IS_EXHAUSTED = 0xFF
    const val HASHMAP_IS_EMPTY = 0xFE

    const val ADV_OVERHEAD = 134
    const val HASHMAP_MAX_LEN = (DEFAULT_LINK_MDU - ADV_OVERHEAD) / MAPHASH_LEN // 74
    const val COLLISION_GUARD_SIZE = 2 * WINDOW_MAX + HASHMAP_MAX_LEN // 224
}

enum class RnsResourceStatus(val code: Int) {
    NONE(0x00),
    QUEUED(0x01),
    ADVERTISED(0x02),
    TRANSFERRING(0x03),
    AWAITING_PROOF(0x04),
    ASSEMBLING(0x05),
    COMPLETE(0x06),
    FAILED(0x07),
    CORRUPT(0x08),
    REJECTED(0x09),
}

object RnsResourceBz2 {
    fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        BZip2CompressorOutputStream(out).use { bz2 ->
            bz2.write(data)
        }
        return out.toByteArray()
    }

    fun decompress(data: ByteArray, maxLength: Int = RnsResourceConstants.AUTO_COMPRESS_MAX_SIZE): ByteArray? = runCatching {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        BZip2CompressorInputStream(ByteArrayInputStream(data)).use { bz2 ->
            while (true) {
                val n = bz2.read(buf)
                if (n < 0) break
                total += n
                if (total > maxLength) return null
                out.write(buf, 0, n)
            }
        }
        out.toByteArray()
    }.getOrNull()
}

data class RnsResourceAdvertisement(
    val transferSize: Long,
    val dataSize: Long,
    val totalParts: Int,
    val hash: ByteArray,
    val randomHash: ByteArray,
    val originalHash: ByteArray,
    val segmentIndex: Int,
    val totalSegments: Int,
    val requestId: ByteArray?,
    val flags: Int,
    val hashmap: ByteArray,
) {
    val encrypted: Boolean get() = (flags and 0x01) != 0
    val compressed: Boolean get() = ((flags ushr 1) and 0x01) != 0
    val split: Boolean get() = ((flags ushr 2) and 0x01) != 0
    val isRequest: Boolean get() = ((flags ushr 3) and 0x01) != 0
    val isResponse: Boolean get() = ((flags ushr 4) and 0x01) != 0
    val hasMetadata: Boolean get() = ((flags ushr 5) and 0x01) != 0
    val appFlag: Boolean get() = ((flags ushr 6) and 0x01) != 0

    fun pack(segment: Int = 0, linkMdu: Int = RnsResourceConstants.DEFAULT_LINK_MDU): ByteArray {
        val start = segment * RnsResourceConstants.HASHMAP_MAX_LEN
        val end = minOf((segment + 1) * RnsResourceConstants.HASHMAP_MAX_LEN, totalParts)
        val hmSlice = if (start < end && end * RnsResourceConstants.MAPHASH_LEN <= hashmap.size) {
            hashmap.copyOfRange(start * RnsResourceConstants.MAPHASH_LEN, end * RnsResourceConstants.MAPHASH_LEN)
        } else {
            ByteArray(0)
        }

        val dict = linkedMapOf<String, Any?>(
            "t" to transferSize,
            "d" to dataSize,
            "n" to totalParts,
            "h" to hash,
            "r" to randomHash,
            "o" to originalHash,
            "i" to segmentIndex,
            "l" to totalSegments,
            "q" to requestId,
            "f" to flags,
            "m" to hmSlice,
        )
        val packed = MsgPack.encode(dict)
        if (packed.size > linkMdu) {
            dict["m"] = ByteArray(0)
            return MsgPack.encode(dict)
        }
        return packed
    }

    companion object {
        fun encodeFlags(
            encrypted: Boolean = true,
            compressed: Boolean = false,
            split: Boolean = false,
            isRequest: Boolean = false,
            isResponse: Boolean = false,
            hasMetadata: Boolean = false,
            appFlag: Boolean = false,
        ): Int =
            (if (appFlag) 1 shl 6 else 0) or
                (if (hasMetadata) 1 shl 5 else 0) or
                (if (isResponse) 1 shl 4 else 0) or
                (if (isRequest) 1 shl 3 else 0) or
                (if (split) 1 shl 2 else 0) or
                (if (compressed) 1 shl 1 else 0) or
                (if (encrypted) 1 else 0)

        fun unpack(data: ByteArray): RnsResourceAdvertisement? = runCatching {
            val map = MsgPack.decode(data).first as? Map<*, *> ?: return null
            val t = (map["t"] as? Number)?.toLong() ?: return null
            val d = (map["d"] as? Number)?.toLong() ?: return null
            val nLong = (map["n"] as? Number)?.toLong() ?: return null
            val h = map["h"] as? ByteArray ?: return null
            val r = map["r"] as? ByteArray ?: return null
            val o = map["o"] as? ByteArray ?: return null
            val i = (map["i"] as? Number)?.toInt() ?: return null
            val l = (map["l"] as? Number)?.toInt() ?: return null
            val q = map["q"] as? ByteArray
            val f = (map["f"] as? Number)?.toInt() ?: return null
            val m = map["m"] as? ByteArray ?: return null

            if (t <= 0L || t > RnsResourceConstants.MAX_EFFICIENT_SIZE * 3L) return null
            if (d < 0L) return null
            if (nLong <= 0L || nLong > t || nLong > Int.MAX_VALUE.toLong()) return null
            val n = nLong.toInt()
            if (i < 1 || l < 1 || i > l) return null
            if (h.size != 32 || o.size != 32 || r.size != RnsResourceConstants.RANDOM_HASH_SIZE) return null

            RnsResourceAdvertisement(
                transferSize = t,
                dataSize = d,
                totalParts = n,
                hash = h,
                randomHash = r,
                originalHash = o,
                segmentIndex = i,
                totalSegments = l,
                requestId = q,
                flags = f,
                hashmap = m,
            )
        }.getOrNull()

        internal fun extractHash(data: ByteArray): ByteArray? = runCatching {
            val map = MsgPack.decode(data).first as? Map<*, *> ?: return null
            val h = map["h"] as? ByteArray ?: return null
            h.takeIf { it.size == 32 }
        }.getOrNull()
    }
}

internal fun rnsResourceMapHash(partData: ByteArray, randomHash: ByteArray): ByteArray {
    val sha = MessageDigest.getInstance("SHA-256")
    sha.update(partData)
    sha.update(randomHash)
    return sha.digest().copyOf(RnsResourceConstants.MAPHASH_LEN)
}

/**
 * Outgoing RNS 1.6.0 Resource state machine (supports single-segment and multi-segment > MAX_EFFICIENT_SIZE).
 * Produces encrypted RESOURCE_ADV / RESOURCE_HMU / RESOURCE_ICL packets and
 * unencrypted-at-packet-layer DATA(RESOURCE) part packets for RnsLinkSession.
 */
class RnsResourceSender private constructor(
    private val link: RnsLinkSession,
    private val fullUncompressedData: ByteArray,
    private val rawDataSize: Int,
    val hasMetadata: Boolean,
    private val autoCompress: Boolean,
    private val autoCompressLimit: Int,
    val requestId: ByteArray?,
    val isResponse: Boolean,
    val appFlag: Boolean,
    val segmentIndex: Int,
    val totalSegments: Int,
    initialOriginalHash: ByteArray?,
) {
    constructor(
        link: RnsLinkSession,
        data: ByteArray,
        metadata: Any? = null,
        autoCompress: Boolean = true,
        autoCompressLimit: Int = RnsResourceConstants.AUTO_COMPRESS_MAX_SIZE,
        requestId: ByteArray? = null,
        isResponse: Boolean = false,
        appFlag: Boolean = false,
    ) : this(
        link = link,
        fullUncompressedData = buildFullUncompressed(data, metadata),
        rawDataSize = data.size,
        hasMetadata = metadata != null,
        autoCompress = autoCompress,
        autoCompressLimit = autoCompressLimit,
        requestId = requestId,
        isResponse = isResponse,
        appFlag = appFlag,
        segmentIndex = 1,
        totalSegments = computeTotalSegments(buildFullUncompressed(data, metadata).size),
        initialOriginalHash = null,
    )

    companion object {
        private fun buildFullUncompressed(data: ByteArray, metadata: Any?): ByteArray {
            if (metadata == null) return data
            val packedMeta = MsgPack.encode(metadata)
            require(packedMeta.size <= RnsResourceConstants.METADATA_MAX_SIZE) { "Resource metadata size exceeded" }
            val sz = packedMeta.size
            val prefix = byteArrayOf(
                ((sz ushr 16) and 0xFF).toByte(),
                ((sz ushr 8) and 0xFF).toByte(),
                (sz and 0xFF).toByte(),
            )
            return prefix + packedMeta + data
        }

        private fun computeTotalSegments(totalBytes: Int): Int {
            if (totalBytes <= RnsResourceConstants.MAX_EFFICIENT_SIZE) return 1
            return ((totalBytes - 1) / RnsResourceConstants.MAX_EFFICIENT_SIZE) + 1
        }
    }

    val sdu: Int = link.sdu
    val compressed: Boolean
    val encrypted: Boolean = true
    val totalSize: Long = fullUncompressedData.size.toLong()
    val transferSize: Int
    val totalParts: Int
    val split: Boolean = totalSegments > 1

    lateinit var randomHash: ByteArray
        private set
    lateinit var hash: ByteArray
        private set
    lateinit var originalHash: ByteArray
        private set
    lateinit var expectedProof: ByteArray
        private set

    val hashHex: String get() = RnsCrypto.bytesToHex(hash)
    val originalHashHex: String get() = RnsCrypto.bytesToHex(originalHash)

    var status: RnsResourceStatus = RnsResourceStatus.NONE
        private set
    var sentParts: Int = 0
        private set
    var retriesLeft: Int = RnsResourceConstants.MAX_ADV_RETRIES
        private set
    var advSentMs: Long = 0L
        private set
    var lastActivityMs: Long = 0L
        private set
    var lastPartSentMs: Long = 0L
        private set

    val progress: Float
        get() {
            if (status == RnsResourceStatus.COMPLETE && segmentIndex == totalSegments) return 1.0f
            val segFrac = sentParts.toFloat() / totalParts.coerceAtLeast(1)
            return if (!split) {
                minOf(1.0f, segFrac)
            } else {
                minOf(1.0f, ((segmentIndex - 1).toFloat() + segFrac) / totalSegments.toFloat())
            }
        }

    private val parts: MutableList<ByteArray> = mutableListOf()
    private val partHashes: MutableList<ByteArray> = mutableListOf()
    private val partSent: BooleanArray
    private var hashmapBytes: ByteArray = ByteArray(0)
    private var receiverMinConsecutiveHeight: Int = 0
    private val seenRequestHashes = HashSet<String>()

    init {
        val segStart = ((segmentIndex - 1) * RnsResourceConstants.MAX_EFFICIENT_SIZE).coerceAtMost(fullUncompressedData.size)
        val segEnd = minOf(segmentIndex * RnsResourceConstants.MAX_EFFICIENT_SIZE, fullUncompressedData.size)
        val uncompressedData = fullUncompressedData.copyOfRange(segStart, segEnd)

        val innerPayload = if (autoCompress && rawDataSize <= autoCompressLimit) {
            val comp = runCatching { RnsResourceBz2.compress(uncompressedData) }.getOrNull()
            if (comp != null && comp.size < uncompressedData.size) {
                compressed = true
                comp
            } else {
                compressed = false
                uncompressedData
            }
        } else {
            compressed = false
            uncompressedData
        }

        val randomPrefix = ByteArray(RnsResourceConstants.RANDOM_HASH_SIZE).also { SecureRandom().nextBytes(it) }
        val encryptedStream = link.encryptLinkPayload(randomPrefix + innerPayload)
            ?: error("Link must be ACTIVE to create RnsResourceSender")

        transferSize = encryptedStream.size
        totalParts = ceil(transferSize.toDouble() / sdu.toDouble()).toInt().coerceAtLeast(1)

        var hashmapOk = false
        while (!hashmapOk) {
            randomHash = ByteArray(RnsResourceConstants.RANDOM_HASH_SIZE).also { SecureRandom().nextBytes(it) }
            val sha = MessageDigest.getInstance("SHA-256")
            sha.update(uncompressedData)
            sha.update(randomHash)
            hash = sha.digest()
            originalHash = initialOriginalHash ?: hash

            val proofSha = MessageDigest.getInstance("SHA-256")
            proofSha.update(uncompressedData)
            proofSha.update(hash)
            expectedProof = proofSha.digest()

            parts.clear()
            partHashes.clear()
            val hmOut = ByteArrayOutputStream(totalParts * RnsResourceConstants.MAPHASH_LEN)
            val guard = ArrayDeque<ByteArray>(RnsResourceConstants.COLLISION_GUARD_SIZE + 1)

            hashmapOk = true
            for (i in 0 until totalParts) {
                val start = i * sdu
                val end = minOf((i + 1) * sdu, transferSize)
                val partData = encryptedStream.copyOfRange(start, end)
                val mapHash = rnsResourceMapHash(partData, randomHash)
                if (guard.any { it.contentEquals(mapHash) }) {
                    hashmapOk = false
                    break
                }
                guard.addLast(mapHash)
                if (guard.size > RnsResourceConstants.COLLISION_GUARD_SIZE) {
                    guard.removeFirst()
                }
                parts.add(partData)
                partHashes.add(mapHash)
                hmOut.write(mapHash)
            }
            if (hashmapOk) {
                hashmapBytes = hmOut.toByteArray()
            }
        }
        partSent = BooleanArray(totalParts)
    }

    /**
     * Creates the next segment sender (`segmentIndex + 1` of `totalSegments`) sharing `originalHash`,
     * or returns `null` when this segment is already the final segment (`segmentIndex >= totalSegments`).
     */
    fun nextSegment(): RnsResourceSender? {
        if (segmentIndex >= totalSegments) return null
        return RnsResourceSender(
            link = link,
            fullUncompressedData = fullUncompressedData,
            rawDataSize = rawDataSize,
            hasMetadata = hasMetadata,
            autoCompress = autoCompress,
            autoCompressLimit = autoCompressLimit,
            requestId = requestId,
            isResponse = isResponse,
            appFlag = appFlag,
            segmentIndex = segmentIndex + 1,
            totalSegments = totalSegments,
            initialOriginalHash = originalHash,
        )
    }

    fun buildAdvertisement(): RnsResourceAdvertisement {
        val flags = RnsResourceAdvertisement.encodeFlags(
            encrypted = encrypted,
            compressed = compressed,
            split = split,
            isRequest = requestId != null && !isResponse,
            isResponse = requestId != null && isResponse,
            hasMetadata = hasMetadata,
            appFlag = appFlag,
        )
        return RnsResourceAdvertisement(
            transferSize = transferSize.toLong(),
            dataSize = totalSize,
            totalParts = totalParts,
            hash = hash,
            randomHash = randomHash,
            originalHash = originalHash,
            segmentIndex = segmentIndex,
            totalSegments = totalSegments,
            requestId = requestId,
            flags = flags,
            hashmap = hashmapBytes,
        )
    }

    fun buildAdvertisementPacket(nowMs: Long = System.currentTimeMillis()): ByteArray? {
        val advBytes = buildAdvertisement().pack(segment = 0, linkMdu = link.mdu)
        val pkt = link.buildEncryptedLinkPacket(RnsContext.RESOURCE_ADV, advBytes) ?: return null
        if (status == RnsResourceStatus.NONE || status == RnsResourceStatus.QUEUED) {
            retriesLeft = RnsResourceConstants.MAX_ADV_RETRIES
        }
        status = RnsResourceStatus.ADVERTISED
        advSentMs = nowMs
        lastActivityMs = nowMs
        return pkt
    }

    /**
     * Processes a decrypted RESOURCE_REQ payload and returns outbound packets
     * (DATA(RESOURCE) parts + optional DATA(RESOURCE_HMU)).
     * Updates state only after validating resource hash and checking for duplicate requests (N-13).
     */
    fun handleRequest(
        decryptedReqPayload: ByteArray,
        requestPacketHash: ByteArray? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): List<ByteArray> {
        if (status == RnsResourceStatus.FAILED || status == RnsResourceStatus.REJECTED || status == RnsResourceStatus.COMPLETE) {
            return emptyList()
        }
        if (decryptedReqPayload.size < 1 + 32) return emptyList()

        val flag = decryptedReqPayload[0].toInt() and 0xFF
        val hashmapIsEmpty = flag == RnsResourceConstants.HASHMAP_IS_EMPTY
        val wantsMoreHashmap = hashmapIsEmpty || flag == RnsResourceConstants.HASHMAP_IS_EXHAUSTED
        val pad = 1 + if (flag == RnsResourceConstants.HASHMAP_IS_EXHAUSTED) RnsResourceConstants.MAPHASH_LEN else 0
        if (decryptedReqPayload.size < pad + 32) return emptyList()

        val reqHash = decryptedReqPayload.copyOfRange(pad, pad + 32)
        if (!reqHash.contentEquals(hash)) return emptyList()

        // N-13 / Link.py:1181: ignore duplicate RESOURCE_REQ packets to prevent sequencing errors
        val dedupHash = requestPacketHash ?: MessageDigest.getInstance("SHA-256").digest(decryptedReqPayload)
        if (!seenRequestHashes.add(RnsCrypto.bytesToHex(dedupHash))) {
            return emptyList()
        }

        status = RnsResourceStatus.TRANSFERRING
        lastActivityMs = nowMs
        retriesLeft = RnsResourceConstants.MAX_RETRIES

        val requestedBytes = decryptedReqPayload.copyOfRange(pad + 32, decryptedReqPayload.size)
        val requestedHashes = ArrayList<ByteArray>(requestedBytes.size / RnsResourceConstants.MAPHASH_LEN)
        var off = 0
        while (off + RnsResourceConstants.MAPHASH_LEN <= requestedBytes.size) {
            requestedHashes.add(requestedBytes.copyOfRange(off, off + RnsResourceConstants.MAPHASH_LEN))
            off += RnsResourceConstants.MAPHASH_LEN
        }

        val outPackets = mutableListOf<ByteArray>()
        val searchStart = receiverMinConsecutiveHeight
        val searchEnd = minOf(totalParts, receiverMinConsecutiveHeight + RnsResourceConstants.COLLISION_GUARD_SIZE)

        for (idx in searchStart until searchEnd) {
            val mh = partHashes[idx]
            if (requestedHashes.any { it.contentEquals(mh) }) {
                val pkt = link.buildUnencryptedLinkPacket(RnsPacketType.DATA, RnsContext.RESOURCE, parts[idx]) ?: continue
                outPackets.add(pkt)
                if (!partSent[idx]) {
                    partSent[idx] = true
                    sentParts++
                }
                lastActivityMs = nowMs
                lastPartSentMs = nowMs
            }
        }

        if (wantsMoreHashmap) {
            val segment = if (hashmapIsEmpty) {
                0
            } else {
                val lastMapHash = decryptedReqPayload.copyOfRange(1, 1 + RnsResourceConstants.MAPHASH_LEN)
                var partIndex = receiverMinConsecutiveHeight
                val hmuSearchEnd = minOf(totalParts, receiverMinConsecutiveHeight + RnsResourceConstants.COLLISION_GUARD_SIZE)
                for (idx in receiverMinConsecutiveHeight until hmuSearchEnd) {
                    partIndex++
                    if (partHashes[idx].contentEquals(lastMapHash)) break
                }
                receiverMinConsecutiveHeight = maxOf(partIndex - 1 - RnsResourceConstants.WINDOW_MAX, 0)
                if (partIndex % RnsResourceConstants.HASHMAP_MAX_LEN != 0) {
                    status = RnsResourceStatus.FAILED
                    return outPackets
                }
                partIndex / RnsResourceConstants.HASHMAP_MAX_LEN
            }

            val hmStart = segment * RnsResourceConstants.HASHMAP_MAX_LEN
            val hmEnd = minOf((segment + 1) * RnsResourceConstants.HASHMAP_MAX_LEN, totalParts)
            if (hmStart >= hmEnd) {
                status = RnsResourceStatus.FAILED
                return outPackets
            }
            val hmSlice = hashmapBytes.copyOfRange(
                hmStart * RnsResourceConstants.MAPHASH_LEN,
                hmEnd * RnsResourceConstants.MAPHASH_LEN,
            )
            val hmuPayload = hash + MsgPack.encode(listOf(segment, hmSlice))
            link.buildEncryptedLinkPacket(RnsContext.RESOURCE_HMU, hmuPayload)?.let { outPackets.add(it) }
            lastActivityMs = nowMs
        }

        if (sentParts == totalParts) {
            status = RnsResourceStatus.AWAITING_PROOF
            retriesLeft = RnsResourceConstants.MAX_PROOF_RETRIES
            if (lastPartSentMs == 0L) lastPartSentMs = nowMs
        }
        return outPackets
    }

    /**
     * Watchdog step for outgoing Resource (`Resource.py:590-676`):
     * - `ADVERTISED`: retries `RESOURCE_ADV` up to `MAX_ADV_RETRIES` (4), then cancels (`FAILED`).
     * - `TRANSFERRING`: cancels if receiver stops requesting parts beyond max wait window.
     * - `AWAITING_PROOF`: waits for `RESOURCE_PRF` across `MAX_PROOF_RETRIES` rounds, then cancels (`FAILED`).
     */
    fun checkTimeout(nowMs: Long = System.currentTimeMillis()): List<ByteArray> {
        val baseTimeoutMs = (link.rttSec * 6.0 * 1000.0).toLong().coerceIn(1_500L, 10_000L)
        return when (status) {
            RnsResourceStatus.ADVERTISED -> {
                val deadline = advSentMs + baseTimeoutMs + RnsResourceConstants.PROCESSING_GRACE_MS
                if (nowMs >= deadline) {
                    if (retriesLeft > 0) {
                        retriesLeft--
                        val pkt = buildAdvertisementPacket(nowMs)
                        if (pkt != null) listOf(pkt) else listOfNotNull(buildCancelPacket())
                    } else {
                        listOfNotNull(buildCancelPacket())
                    }
                } else {
                    emptyList()
                }
            }
            RnsResourceStatus.TRANSFERRING -> {
                val maxWaitMs = baseTimeoutMs * RnsResourceConstants.MAX_RETRIES + RnsResourceConstants.SENDER_GRACE_TIME_MS
                if (nowMs >= lastActivityMs + maxWaitMs) {
                    listOfNotNull(buildCancelPacket())
                } else {
                    emptyList()
                }
            }
            RnsResourceStatus.AWAITING_PROOF -> {
                val proofWaitMs = (link.rttSec * RnsResourceConstants.PROOF_TIMEOUT_FACTOR * 1000.0).toLong().coerceAtLeast(1_500L) +
                    RnsResourceConstants.SENDER_GRACE_TIME_MS / 2
                val refTime = if (lastPartSentMs > 0L) lastPartSentMs else lastActivityMs
                if (nowMs >= refTime + proofWaitMs) {
                    if (retriesLeft > 0) {
                        retriesLeft--
                        lastPartSentMs = nowMs
                        emptyList()
                    } else {
                        listOfNotNull(buildCancelPacket())
                    }
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    fun handleProof(proofPayload: ByteArray): Boolean {
        if (status == RnsResourceStatus.FAILED || status == RnsResourceStatus.REJECTED) return false
        if (proofPayload.size != 64) return false
        val resHash = proofPayload.copyOfRange(0, 32)
        val proof = proofPayload.copyOfRange(32, 64)
        if (!resHash.contentEquals(hash)) return false
        if (!MessageDigest.isEqual(proof, expectedProof)) return false
        status = RnsResourceStatus.COMPLETE
        return true
    }

    fun handleReject() {
        if (status.code < RnsResourceStatus.COMPLETE.code) {
            status = RnsResourceStatus.REJECTED
        }
    }

    fun handleCancel() {
        if (status.code < RnsResourceStatus.COMPLETE.code) {
            status = RnsResourceStatus.FAILED
        }
    }

    fun buildCancelPacket(): ByteArray? {
        if (status.code < RnsResourceStatus.COMPLETE.code) {
            status = RnsResourceStatus.FAILED
            return link.buildEncryptedLinkPacket(RnsContext.RESOURCE_ICL, hash)
        }
        return null
    }
}

/**
 * Incoming RNS 1.6.0 Resource state machine (supports single-segment and multi-segment > MAX_EFFICIENT_SIZE).
 * Consumes RESOURCE_ADV / RESOURCE_HMU / DATA(RESOURCE) parts and produces
 * RESOURCE_REQ / RESOURCE_PRF / RESOURCE_RCL packets for RnsLinkSession.
 */
class RnsResourceReceiver private constructor(
    private val link: RnsLinkSession,
    val adv: RnsResourceAdvertisement,
    private val maxDecompressedSize: Int,
    private val priorSegmentsData: ByteArray?,
    private val priorMetadata: Any?,
) {
    val hash: ByteArray = adv.hash
    val hashHex: String = RnsCrypto.bytesToHex(hash)
    val originalHash: ByteArray = adv.originalHash
    val originalHashHex: String = RnsCrypto.bytesToHex(originalHash)
    val randomHash: ByteArray = adv.randomHash
    val totalParts: Int = adv.totalParts
    val transferSize: Long = adv.transferSize
    val totalSize: Long = adv.dataSize
    val segmentIndex: Int = adv.segmentIndex
    val totalSegments: Int = adv.totalSegments
    val isFinalSegment: Boolean = adv.segmentIndex == adv.totalSegments

    var status: RnsResourceStatus = RnsResourceStatus.TRANSFERRING
        private set
    var receivedCount: Int = 0
        private set
    var retriesLeft: Int = RnsResourceConstants.MAX_RETRIES
        private set
    var lastActivityMs: Long = System.currentTimeMillis()
        private set
    private var partTimeoutFactor: Int = RnsResourceConstants.PART_TIMEOUT_FACTOR

    val progress: Float
        get() {
            if (status == RnsResourceStatus.COMPLETE && isFinalSegment) return 1.0f
            val segFrac = receivedCount.toFloat() / totalParts.coerceAtLeast(1)
            return if (totalSegments <= 1) {
                minOf(1.0f, segFrac)
            } else {
                minOf(1.0f, ((segmentIndex - 1).toFloat() + segFrac) / totalSegments.toFloat())
            }
        }

    var assembledData: ByteArray? = null
        private set
    var assembledMetadata: Any? = priorMetadata
        private set

    private var window: Int = RnsResourceConstants.WINDOW
    private var windowMax: Int = RnsResourceConstants.WINDOW_MAX_SLOW
    private var windowMin: Int = RnsResourceConstants.WINDOW_MIN
    private val windowFlexibility: Int = RnsResourceConstants.WINDOW_FLEXIBILITY

    private val parts: Array<ByteArray?> = arrayOfNulls(totalParts)
    private val hashmap: Array<ByteArray?> = arrayOfNulls(totalParts)
    private var hashmapHeight: Int = 0
    private var consecutiveCompletedHeight: Int = -1
    private var outstandingParts: Int = 0
    private var waitingForHmu: Boolean = false

    private fun applyHashmapUpdate(
        segment: Int,
        hmBytes: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): ByteArray? {
        if (status == RnsResourceStatus.FAILED || status == RnsResourceStatus.CORRUPT) return null
        if (segment < 0 || hmBytes.size % RnsResourceConstants.MAPHASH_LEN != 0) {
            status = RnsResourceStatus.FAILED
            return null
        }
        val segLen = RnsResourceConstants.HASHMAP_MAX_LEN
        val baseIdx = segment.toLong() * segLen.toLong()
        if (baseIdx >= totalParts.toLong() && totalParts > 0) {
            status = RnsResourceStatus.FAILED
            return null
        }
        val hashes = hmBytes.size / RnsResourceConstants.MAPHASH_LEN
        if (hashes < 1 && segment != 0) {
            status = RnsResourceStatus.FAILED
            return null
        }
        status = RnsResourceStatus.TRANSFERRING
        lastActivityMs = nowMs
        retriesLeft = RnsResourceConstants.MAX_RETRIES
        for (i in 0 until hashes) {
            val idx = (baseIdx + i).toInt()
            if (idx in 0 until totalParts) {
                if (hashmap[idx] == null) hashmapHeight++
                hashmap[idx] = hmBytes.copyOfRange(i * RnsResourceConstants.MAPHASH_LEN, (i + 1) * RnsResourceConstants.MAPHASH_LEN)
            }
        }
        waitingForHmu = false
        return buildRequestNextPacket(nowMs)
    }

    private fun buildRequestNextPacket(nowMs: Long = System.currentTimeMillis()): ByteArray? {
        if (status == RnsResourceStatus.FAILED || status == RnsResourceStatus.CORRUPT || waitingForHmu) return null

        outstandingParts = 0
        var hashmapExhausted = RnsResourceConstants.HASHMAP_IS_NOT_EXHAUSTED
        val reqHashes = ByteArrayOutputStream()

        if (totalParts > 0 && hashmap[0] == null) {
            hashmapExhausted = RnsResourceConstants.HASHMAP_IS_EMPTY
            waitingForHmu = true
        } else {
            var count = 0
            var pn = consecutiveCompletedHeight + 1
            val searchEnd = minOf(totalParts, pn + window)
            while (pn < searchEnd && count < window) {
                if (parts[pn] == null) {
                    val ph = hashmap[pn]
                    if (ph != null) {
                        reqHashes.write(ph)
                        outstandingParts++
                        count++
                    } else {
                        hashmapExhausted = RnsResourceConstants.HASHMAP_IS_EXHAUSTED
                        break
                    }
                }
                pn++
            }
        }

        val hmuPart = ByteArrayOutputStream()
        hmuPart.write(hashmapExhausted)
        if (hashmapExhausted == RnsResourceConstants.HASHMAP_IS_EXHAUSTED) {
            if (hashmapHeight <= 0 || hashmapHeight - 1 >= hashmap.size) return null
            val lastHash = hashmap[hashmapHeight - 1] ?: return null
            hmuPart.write(lastHash)
            waitingForHmu = true
        }
        val reqData = hmuPart.toByteArray() + hash + reqHashes.toByteArray()
        lastActivityMs = nowMs
        return link.buildEncryptedLinkPacket(RnsContext.RESOURCE_REQ, reqData)
    }

    fun handleHashmapUpdate(
        decryptedHmuPayload: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): List<ByteArray> {
        if (status == RnsResourceStatus.FAILED || status == RnsResourceStatus.CORRUPT || !waitingForHmu) return emptyList()
        if (decryptedHmuPayload.size < 33) return emptyList()
        val resHash = decryptedHmuPayload.copyOfRange(0, 32)
        if (!resHash.contentEquals(hash)) return emptyList()

        val update = runCatching {
            MsgPack.decode(decryptedHmuPayload, offset = 32).first as? List<*>
        }.getOrNull() ?: return emptyList()
        if (update.size < 2) return emptyList()
        val segment = (update[0] as? Number)?.toInt() ?: return emptyList()
        val hmBytes = update[1] as? ByteArray ?: return emptyList()

        val req = applyHashmapUpdate(segment, hmBytes, nowMs)
        return if (req != null) listOf(req) else emptyList()
    }

    fun handlePart(
        partData: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): List<ByteArray> {
        if (status == RnsResourceStatus.FAILED || status == RnsResourceStatus.CORRUPT || status == RnsResourceStatus.COMPLETE) {
            return emptyList()
        }
        val partHash = rnsResourceMapHash(partData, randomHash)
        val searchStart = consecutiveCompletedHeight + 1
        val searchEnd = minOf(totalParts, searchStart + window)
        var matched = false

        for (i in searchStart until searchEnd) {
            val expected = hashmap[i]
            if (expected != null && expected.contentEquals(partHash) && parts[i] == null) {
                parts[i] = partData
                receivedCount++
                outstandingParts = maxOf(0, outstandingParts - 1)
                matched = true

                var cp = consecutiveCompletedHeight + 1
                while (cp < totalParts && parts[cp] != null) {
                    consecutiveCompletedHeight = cp
                    cp++
                }
                break
            }
        }

        if (!matched) return emptyList()

        lastActivityMs = nowMs
        retriesLeft = RnsResourceConstants.MAX_RETRIES
        partTimeoutFactor = RnsResourceConstants.PART_TIMEOUT_FACTOR_AFTER_RTT

        if (receivedCount == totalParts) {
            return listOfNotNull(assembleAndProve())
        }

        if (outstandingParts == 0) {
            if (window < windowMax) {
                window++
                if ((window - windowMin) > (windowFlexibility - 1)) {
                    windowMin++
                }
            }
            return listOfNotNull(buildRequestNextPacket(nowMs))
        }

        return emptyList()
    }

    private fun assembleAndProve(): ByteArray? {
        status = RnsResourceStatus.ASSEMBLING
        val streamOut = ByteArrayOutputStream(transferSize.toInt().coerceAtLeast(0))
        for (p in parts) {
            if (p == null) {
                status = RnsResourceStatus.CORRUPT
                return buildCancelPacket()
            }
            streamOut.write(p)
        }
        val stream = streamOut.toByteArray()

        val decrypted = if (adv.encrypted) {
            link.decryptLinkPayload(stream) ?: run {
                status = RnsResourceStatus.CORRUPT
                return buildCancelPacket()
            }
        } else {
            stream
        }

        if (decrypted.size < RnsResourceConstants.RANDOM_HASH_SIZE) {
            status = RnsResourceStatus.CORRUPT
            return buildCancelPacket()
        }
        val payloadAfterRand = decrypted.copyOfRange(RnsResourceConstants.RANDOM_HASH_SIZE, decrypted.size)

        val maxSegmentSize = minOf(maxDecompressedSize, RnsResourceConstants.MAX_EFFICIENT_SIZE)
        val uncompressed = if (!adv.compressed) {
            if (payloadAfterRand.size > maxSegmentSize) {
                status = RnsResourceStatus.CORRUPT
                return buildCancelPacket()
            }
            payloadAfterRand
        } else {
            RnsResourceBz2.decompress(payloadAfterRand, maxSegmentSize) ?: run {
                status = RnsResourceStatus.CORRUPT
                return buildCancelPacket()
            }
        }

        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(uncompressed)
        sha.update(randomHash)
        val calculatedHash = sha.digest()
        if (!calculatedHash.contentEquals(hash)) {
            status = RnsResourceStatus.CORRUPT
            return buildCancelPacket()
        }

        val segmentPayload = if (adv.hasMetadata && adv.segmentIndex == 1) {
            if (uncompressed.size < 3) {
                status = RnsResourceStatus.CORRUPT
                return buildCancelPacket()
            }
            val metaSize = ((uncompressed[0].toInt() and 0xFF) shl 16) or
                ((uncompressed[1].toInt() and 0xFF) shl 8) or
                (uncompressed[2].toInt() and 0xFF)
            if (metaSize < 0 || 3 + metaSize > uncompressed.size) {
                status = RnsResourceStatus.CORRUPT
                return buildCancelPacket()
            }
            assembledMetadata = runCatching {
                MsgPack.decode(uncompressed.copyOfRange(3, 3 + metaSize)).first
            }.getOrNull()
            uncompressed.copyOfRange(3 + metaSize, uncompressed.size)
        } else {
            uncompressed
        }

        val priorLen = priorSegmentsData?.size ?: 0
        if (priorLen.toLong() + segmentPayload.size.toLong() > maxDecompressedSize.toLong()) {
            status = RnsResourceStatus.CORRUPT
            return buildCancelPacket()
        }

        assembledData = if (priorSegmentsData != null && priorSegmentsData.isNotEmpty()) {
            priorSegmentsData + segmentPayload
        } else {
            segmentPayload
        }
        status = RnsResourceStatus.COMPLETE

        val proofSha = MessageDigest.getInstance("SHA-256")
        proofSha.update(uncompressed)
        proofSha.update(hash)
        val proof = proofSha.digest()
        return link.buildUnencryptedLinkPacket(RnsPacketType.PROOF, RnsContext.RESOURCE_PRF, hash + proof)
    }

    fun buildRetryRequest(nowMs: Long = System.currentTimeMillis()): ByteArray? {
        if (status != RnsResourceStatus.TRANSFERRING) return null
        if (window > windowMin) {
            window--
            if (windowMax > windowMin) {
                windowMax--
                if ((windowMax - window) > (windowFlexibility - 1)) {
                    windowMax--
                }
            }
        }
        waitingForHmu = false
        return buildRequestNextPacket(nowMs)
    }

    /**
     * Watchdog step for incoming Resource (`Resource.py:611-646`):
     * When waiting for parts or HMU exceeds the part timeout, shrinks window and sends a retry
     * `RESOURCE_REQ` up to `MAX_RETRIES` (16), then cancels with `RESOURCE_RCL` (`FAILED`).
     */
    fun checkTimeout(nowMs: Long = System.currentTimeMillis()): List<ByteArray> {
        if (status != RnsResourceStatus.TRANSFERRING) return emptyList()
        val retriesUsed = (RnsResourceConstants.MAX_RETRIES - retriesLeft).coerceAtLeast(0)
        val extraWaitMs = retriesUsed * RnsResourceConstants.PER_RETRY_DELAY_MS
        val baseRttWaitMs = (link.rttSec * 6.0 * 1000.0).toLong().coerceIn(1_000L, 10_000L)
        val partTimeoutMs = (baseRttWaitMs * partTimeoutFactor) / 2 + RnsResourceConstants.RETRY_GRACE_TIME_MS + extraWaitMs
        if (nowMs < lastActivityMs + partTimeoutMs) return emptyList()

        return if (retriesLeft > 0) {
            retriesLeft--
            lastActivityMs = nowMs
            listOfNotNull(buildRetryRequest(nowMs))
        } else {
            listOfNotNull(buildCancelPacket())
        }
    }

    fun handleCancel() {
        if (status.code < RnsResourceStatus.COMPLETE.code) {
            status = RnsResourceStatus.FAILED
        }
    }

    fun buildCancelPacket(): ByteArray? {
        if (status.code < RnsResourceStatus.COMPLETE.code) {
            status = RnsResourceStatus.FAILED
        }
        return link.buildEncryptedLinkPacket(RnsContext.RESOURCE_RCL, hash)
    }

    companion object {
        fun accept(
            link: RnsLinkSession,
            advPlaintext: ByteArray,
            maxDecompressedSize: Int = RnsResourceConstants.DEFAULT_MAX_DECOMPRESSED_SIZE,
            priorSegmentsData: ByteArray? = null,
            priorMetadata: Any? = null,
            nowMs: Long = System.currentTimeMillis(),
        ): Pair<RnsResourceReceiver, ByteArray>? {
            val adv = RnsResourceAdvertisement.unpack(advPlaintext) ?: return null
            if (adv.transferSize <= 0L || adv.transferSize > RnsResourceConstants.MAX_EFFICIENT_SIZE * 3L) return null
            if (adv.dataSize < 0L || adv.dataSize > maxDecompressedSize.toLong()) return null
            val expectedParts = ceil(adv.transferSize.toDouble() / link.sdu.toDouble()).toInt()
            if (expectedParts <= 0 || adv.totalParts != expectedParts) return null
            if (adv.segmentIndex < 1 || adv.totalSegments < 1 || adv.segmentIndex > adv.totalSegments) return null
            if (adv.segmentIndex == 1 && !adv.originalHash.contentEquals(adv.hash)) return null
            if (adv.segmentIndex > 1 && priorSegmentsData == null) return null
            if (priorSegmentsData != null && priorSegmentsData.size > maxDecompressedSize) return null

            val receiver = RnsResourceReceiver(link, adv, maxDecompressedSize, priorSegmentsData, priorMetadata)
            val initialReq = receiver.applyHashmapUpdate(0, adv.hashmap, nowMs) ?: return null
            return receiver to initialReq
        }

        fun buildRejectPacket(link: RnsLinkSession, advPlaintext: ByteArray): ByteArray? {
            val resHash = RnsResourceAdvertisement.extractHash(advPlaintext) ?: return null
            return link.buildEncryptedLinkPacket(RnsContext.RESOURCE_RCL, resHash)
        }
    }
}
