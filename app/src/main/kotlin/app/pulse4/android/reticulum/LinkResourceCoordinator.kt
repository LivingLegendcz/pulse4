package app.pulse4.android.reticulum

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates active RNS 1.6.0 [RnsResourceSender] and [RnsResourceReceiver] transfers
 * over an [RnsLinkSession] for payloads exceeding `link.mdu` (outbound LXMF propagation
 * deposits and inbound `/get` responses or direct Resource deliveries).
 *
 * Supports multi-segment (> 1 MB) transfers, strict request-ID matching for responses,
 * concurrency limits for direct transfers, duplicate ADV/REQ suppression, and watchdog ticks.
 */
internal class LinkResourceCoordinator {

    companion object {
        const val MAX_CONCURRENT_DIRECT_INBOUND = 2
        const val DEFAULT_MAX_RESPONSE_SIZE = RnsResourceConstants.DEFAULT_MAX_DECOMPRESSED_SIZE
    }

    data class ActiveOutbound(
        val eventId: String,
        val sender: RnsResourceSender,
    )

    data class ResourceProofResult(
        val eventId: String,
        val resourceHashHex: String,
    )

    data class ResourceInboundResult(
        val resourceHashHex: String,
        val isResponse: Boolean,
        val requestId: ByteArray?,
        val data: ByteArray,
    )

    data class ResourceFailedResult(
        val eventId: String?,
        val resourceHashHex: String,
        val isResponse: Boolean,
        val requestId: ByteArray?,
        val reason: String,
    )

    private data class PendingMultiSegmentInbound(
        val originalHashHex: String,
        val totalSegments: Int,
        val nextSegmentIndex: Int,
        val isResponse: Boolean,
        val requestId: ByteArray?,
        val data: ByteArray,
        val metadata: Any?,
        val updatedAtMs: Long,
    )

    private data class PendingResponseExpectation(
        val requestId: ByteArray,
        val maxResponseSize: Int,
    )

    private val outbound = ConcurrentHashMap<String, ActiveOutbound>()
    private val inbound = ConcurrentHashMap<String, RnsResourceReceiver>()
    private val partialMultiSegmentInbound = ConcurrentHashMap<String, PendingMultiSegmentInbound>()
    private val expectedResponseLock = Any()
    private var expectedResponse: PendingResponseExpectation? = null

    fun activeOutboundCount(): Int = outbound.size
    fun activeInboundCount(): Int = inbound.size

    fun registerPendingResponse(
        requestId: ByteArray,
        maxResponseSize: Int = DEFAULT_MAX_RESPONSE_SIZE,
    ) {
        synchronized(expectedResponseLock) {
            expectedResponse = PendingResponseExpectation(
                requestId = requestId.copyOf(),
                maxResponseSize = maxResponseSize,
            )
        }
    }

    fun clearPendingResponse(requestId: ByteArray? = null) {
        synchronized(expectedResponseLock) {
            val current = expectedResponse ?: return
            if (requestId == null || MessageDigest.isEqual(current.requestId, requestId)) {
                expectedResponse = null
            }
        }
    }

    /**
     * Returns the latest activity timestamp (ms) if an inbound response Resource matching [requestId]
     * is currently transferring (or awaiting its next multi-segment advertisement), or null otherwise.
     */
    fun activeResponseLastActivityMs(requestId: ByteArray): Long? {
        var latest: Long? = null
        for (receiver in inbound.values) {
            val reqId = receiver.adv.requestId
            if (receiver.adv.isResponse && reqId != null && MessageDigest.isEqual(reqId, requestId)) {
                latest = maxOf(latest ?: 0L, receiver.lastActivityMs)
            }
        }
        for (partial in partialMultiSegmentInbound.values) {
            val reqId = partial.requestId
            if (partial.isResponse && reqId != null && MessageDigest.isEqual(reqId, requestId)) {
                latest = maxOf(latest ?: 0L, partial.updatedAtMs)
            }
        }
        return latest
    }

    fun clear() {
        outbound.values.forEach { it.sender.handleCancel() }
        outbound.clear()
        inbound.values.forEach { it.handleCancel() }
        inbound.clear()
        partialMultiSegmentInbound.clear()
        clearPendingResponse(null)
    }

    /**
     * Starts an outbound Resource transfer for [data] over [link].
     * Sends the initial `RESOURCE_ADV` packet via [sendPacket] and registers the sender.
     * @return the 32-byte resource hash hex on success, or null if advertisement failed.
     */
    fun startOutbound(
        link: RnsLinkSession,
        data: ByteArray,
        eventId: String,
        sendPacket: (ByteArray) -> Boolean,
    ): String? {
        val sender = RnsResourceSender(link = link, data = data, autoCompress = true)
        val advPkt = sender.buildAdvertisementPacket() ?: return null
        outbound[sender.hashHex] = ActiveOutbound(eventId = eventId, sender = sender)
        if (!sendPacket(advPkt)) {
            outbound.remove(sender.hashHex)
            sender.handleCancel()
            return null
        }
        return sender.hashHex
    }

    /**
     * Handles an unencrypted `PROOF` packet with `RnsContext.RESOURCE_PRF`.
     * For multi-segment outbound Resources, advances and advertises the next segment on proof
     * and returns `ResourceProofResult` only when the final segment is proven.
     */
    fun handleProof(
        contextByte: Byte,
        payload: ByteArray,
        sendPacket: (ByteArray) -> Boolean = { false },
    ): ResourceProofResult? {
        if (contextByte != RnsContext.RESOURCE_PRF || payload.size != 64) return null
        val hashHex = RnsCrypto.bytesToHex(payload.copyOfRange(0, 32))
        val entry = outbound[hashHex] ?: return null
        if (!entry.sender.handleProof(payload)) return null
        outbound.remove(hashHex)

        if (entry.sender.segmentIndex < entry.sender.totalSegments) {
            val nextSender = entry.sender.nextSegment() ?: return null
            val advPkt = nextSender.buildAdvertisementPacket()
            if (advPkt != null) {
                outbound[nextSender.hashHex] = ActiveOutbound(eventId = entry.eventId, sender = nextSender)
                if (!sendPacket(advPkt)) {
                    outbound.remove(nextSender.hashHex)
                    nextSender.handleCancel()
                }
            } else {
                nextSender.handleCancel()
            }
            return null
        }

        return ResourceProofResult(eventId = entry.eventId, resourceHashHex = hashHex)
    }

    /**
     * Handles an inbound link `DATA` packet whose [contextByte] is one of the RNS Resource contexts
     * (`RESOURCE_ADV`, `RESOURCE_REQ`, `RESOURCE_HMU`, `RESOURCE`, `RESOURCE_ICL`, `RESOURCE_RCL`).
     *
     * @return `true` if the context was a Resource context (consumed), and optional [ResourceInboundResult]
     *         when an inbound Resource (including final segment) completes assembly.
     */
    fun handleLinkData(
        link: RnsLinkSession,
        contextByte: Byte,
        rawPayload: ByteArray,
        packetHash: ByteArray? = null,
        sendPacket: (ByteArray) -> Boolean,
    ): Pair<Boolean, ResourceInboundResult?> {
        return when (contextByte) {
            RnsContext.RESOURCE_ADV -> {
                val dec = link.decryptLinkPayload(rawPayload) ?: return true to null
                val adv = RnsResourceAdvertisement.unpack(dec)
                if (adv == null) {
                    RnsResourceReceiver.buildRejectPacket(link, dec)?.let(sendPacket)
                    return true to null
                }
                val advHashHex = RnsCrypto.bytesToHex(adv.hash)
                if (inbound.containsKey(advHashHex)) {
                    // N-13: ignore duplicate advertisement for an already active Resource
                    return true to null
                }
                if (adv.isRequest) {
                    RnsResourceReceiver.buildRejectPacket(link, dec)?.let(sendPacket)
                    return true to null
                }

                val maxDecompressedSize: Int
                if (adv.isResponse) {
                    val expected = synchronized(expectedResponseLock) { expectedResponse }
                    val actualId = adv.requestId
                    if (expected == null || actualId == null || !MessageDigest.isEqual(expected.requestId, actualId)) {
                        RnsResourceReceiver.buildRejectPacket(link, dec)?.let(sendPacket)
                        return true to null
                    }
                    if (adv.dataSize > expected.maxResponseSize.toLong()) {
                        RnsResourceReceiver.buildRejectPacket(link, dec)?.let(sendPacket)
                        return true to null
                    }
                    maxDecompressedSize = expected.maxResponseSize
                } else {
                    if (adv.segmentIndex == 1 &&
                        inbound.values.count { !it.adv.isResponse } >= MAX_CONCURRENT_DIRECT_INBOUND
                    ) {
                        RnsResourceReceiver.buildRejectPacket(link, dec)?.let(sendPacket)
                        return true to null
                    }
                    maxDecompressedSize = RnsResourceConstants.DEFAULT_MAX_DECOMPRESSED_SIZE
                }

                val origHex = RnsCrypto.bytesToHex(adv.originalHash)
                val priorData: ByteArray?
                val priorMeta: Any?
                if (adv.segmentIndex == 1) {
                    priorData = null
                    priorMeta = null
                } else {
                    val partial = partialMultiSegmentInbound[origHex]
                    val reqMatch = if (adv.isResponse) {
                        adv.requestId != null && partial?.requestId != null &&
                            MessageDigest.isEqual(adv.requestId, partial.requestId)
                    } else {
                        true
                    }
                    if (partial == null ||
                        adv.segmentIndex != partial.nextSegmentIndex ||
                        adv.totalSegments != partial.totalSegments ||
                        adv.isResponse != partial.isResponse ||
                        !reqMatch
                    ) {
                        RnsResourceReceiver.buildRejectPacket(link, dec)?.let(sendPacket)
                        return true to null
                    }
                    priorData = partial.data
                    priorMeta = partial.metadata
                }

                val accepted = RnsResourceReceiver.accept(
                    link = link,
                    advPlaintext = dec,
                    maxDecompressedSize = maxDecompressedSize,
                    priorSegmentsData = priorData,
                    priorMetadata = priorMeta,
                )
                if (accepted != null) {
                    val (receiver, reqPkt) = accepted
                    inbound[receiver.hashHex] = receiver
                    sendPacket(reqPkt)
                } else {
                    RnsResourceReceiver.buildRejectPacket(link, dec)?.let(sendPacket)
                }
                true to null
            }

            RnsContext.RESOURCE_REQ -> {
                val dec = link.decryptLinkPayload(rawPayload) ?: return true to null
                for (entry in outbound.values) {
                    val pkts = entry.sender.handleRequest(dec, requestPacketHash = packetHash)
                    if (pkts.isNotEmpty()) {
                        pkts.forEach { sendPacket(it) }
                        break
                    }
                }
                true to null
            }

            RnsContext.RESOURCE_HMU -> {
                val dec = link.decryptLinkPayload(rawPayload) ?: return true to null
                if (dec.size >= 32) {
                    val hashHex = RnsCrypto.bytesToHex(dec.copyOfRange(0, 32))
                    val receiver = inbound[hashHex]
                    if (receiver != null) {
                        receiver.handleHashmapUpdate(dec).forEach { sendPacket(it) }
                    }
                }
                true to null
            }

            RnsContext.RESOURCE -> {
                // DATA(RESOURCE) parts are not packet-layer link-encrypted; RnsResourceReceiver
                // verifies part map hashes and decrypts the assembled stream.
                var completed: ResourceInboundResult? = null
                for (receiver in inbound.values) {
                    val prevCount = receiver.receivedCount
                    val prevStatus = receiver.status
                    val outPkts = receiver.handlePart(rawPayload)
                    val changed = receiver.receivedCount != prevCount || receiver.status != prevStatus || outPkts.isNotEmpty()
                    if (!changed) continue

                    outPkts.forEach { sendPacket(it) }
                    when (receiver.status) {
                        RnsResourceStatus.COMPLETE -> {
                            inbound.remove(receiver.hashHex)
                            val data = receiver.assembledData
                            if (!receiver.isFinalSegment) {
                                if (data != null) {
                                    partialMultiSegmentInbound[receiver.originalHashHex] = PendingMultiSegmentInbound(
                                        originalHashHex = receiver.originalHashHex,
                                        totalSegments = receiver.totalSegments,
                                        nextSegmentIndex = receiver.segmentIndex + 1,
                                        isResponse = receiver.adv.isResponse,
                                        requestId = receiver.adv.requestId,
                                        data = data,
                                        metadata = receiver.assembledMetadata,
                                        updatedAtMs = System.currentTimeMillis(),
                                    )
                                }
                            } else {
                                partialMultiSegmentInbound.remove(receiver.originalHashHex)
                                if (data != null) {
                                    if (receiver.adv.isResponse) {
                                        clearPendingResponse(receiver.adv.requestId)
                                    }
                                    completed = ResourceInboundResult(
                                        resourceHashHex = receiver.hashHex,
                                        isResponse = receiver.adv.isResponse,
                                        requestId = receiver.adv.requestId,
                                        data = data,
                                    )
                                }
                            }
                        }
                        RnsResourceStatus.CORRUPT, RnsResourceStatus.FAILED -> {
                            inbound.remove(receiver.hashHex)
                            partialMultiSegmentInbound.remove(receiver.originalHashHex)
                        }
                        else -> Unit
                    }
                    break
                }
                true to completed
            }

            RnsContext.RESOURCE_ICL -> {
                val dec = link.decryptLinkPayload(rawPayload) ?: return true to null
                if (dec.size >= 32) {
                    val hashHex = RnsCrypto.bytesToHex(dec.copyOfRange(0, 32))
                    inbound.remove(hashHex)?.let { receiver ->
                        receiver.handleCancel()
                        partialMultiSegmentInbound.remove(receiver.originalHashHex)
                    }
                }
                true to null
            }

            RnsContext.RESOURCE_RCL -> {
                val dec = link.decryptLinkPayload(rawPayload) ?: return true to null
                if (dec.size >= 32) {
                    val hashHex = RnsCrypto.bytesToHex(dec.copyOfRange(0, 32))
                    outbound.remove(hashHex)?.sender?.handleReject()
                }
                true to null
            }

            else -> false to null
        }
    }

    /**
     * Runs one watchdog tick over active outbound and inbound Resources (`Resource.py:590-672`).
     * Retries advertisements/part-requests on timeout and removes/reports transfers whose retries
     * are exhausted.
     */
    fun tickWatchdog(
        nowMs: Long = System.currentTimeMillis(),
        sendPacket: (ByteArray) -> Boolean,
    ): List<ResourceFailedResult> {
        val failures = mutableListOf<ResourceFailedResult>()

        val outEntries = outbound.entries.toList()
        for ((hashHex, entry) in outEntries) {
            val pkts = entry.sender.checkTimeout(nowMs)
            pkts.forEach { sendPacket(it) }
            if (entry.sender.status == RnsResourceStatus.FAILED || entry.sender.status == RnsResourceStatus.REJECTED) {
                outbound.remove(hashHex)
                failures += ResourceFailedResult(
                    eventId = entry.eventId,
                    resourceHashHex = hashHex,
                    isResponse = entry.sender.isResponse,
                    requestId = entry.sender.requestId,
                    reason = "RESOURCE_TIMEOUT",
                )
            }
        }

        val inEntries = inbound.entries.toList()
        for ((hashHex, receiver) in inEntries) {
            val pkts = receiver.checkTimeout(nowMs)
            pkts.forEach { sendPacket(it) }
            if (receiver.status == RnsResourceStatus.FAILED || receiver.status == RnsResourceStatus.CORRUPT) {
                inbound.remove(hashHex)
                partialMultiSegmentInbound.remove(receiver.originalHashHex)
                if (receiver.adv.isResponse) {
                    clearPendingResponse(receiver.adv.requestId)
                }
                failures += ResourceFailedResult(
                    eventId = null,
                    resourceHashHex = hashHex,
                    isResponse = receiver.adv.isResponse,
                    requestId = receiver.adv.requestId,
                    reason = "RESOURCE_TIMEOUT",
                )
            }
        }

        return failures
    }
}
