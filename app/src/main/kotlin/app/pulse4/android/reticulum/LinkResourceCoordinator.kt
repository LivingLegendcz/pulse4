package app.pulse4.android.reticulum

import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates active RNS 1.6.0 [RnsResourceSender] and [RnsResourceReceiver] transfers
 * over an [RnsLinkSession] for payloads exceeding `link.mdu` (outbound LXMF propagation
 * deposits and inbound `/get` responses or direct Resource deliveries).
 */
internal class LinkResourceCoordinator {

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

    private val outbound = ConcurrentHashMap<String, ActiveOutbound>()
    private val inbound = ConcurrentHashMap<String, RnsResourceReceiver>()

    fun activeOutboundCount(): Int = outbound.size
    fun activeInboundCount(): Int = inbound.size

    fun clear() {
        outbound.values.forEach { it.sender.handleCancel() }
        outbound.clear()
        inbound.values.forEach { it.handleCancel() }
        inbound.clear()
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
     * @return [ResourceProofResult] if a matching outbound Resource was verified and completed.
     */
    fun handleProof(contextByte: Byte, payload: ByteArray): ResourceProofResult? {
        if (contextByte != RnsContext.RESOURCE_PRF || payload.size != 64) return null
        val hashHex = RnsCrypto.bytesToHex(payload.copyOfRange(0, 32))
        val entry = outbound[hashHex] ?: return null
        if (!entry.sender.handleProof(payload)) return null
        outbound.remove(hashHex)
        return ResourceProofResult(eventId = entry.eventId, resourceHashHex = hashHex)
    }

    /**
     * Handles an inbound link `DATA` packet whose [contextByte] is one of the RNS Resource contexts
     * (`RESOURCE_ADV`, `RESOURCE_REQ`, `RESOURCE_HMU`, `RESOURCE`, `RESOURCE_ICL`, `RESOURCE_RCL`).
     *
     * @return `true` if the context was a Resource context (consumed), and optional [ResourceInboundResult]
     *         when an inbound Resource completes assembly.
     */
    fun handleLinkData(
        link: RnsLinkSession,
        contextByte: Byte,
        rawPayload: ByteArray,
        sendPacket: (ByteArray) -> Boolean,
    ): Pair<Boolean, ResourceInboundResult?> {
        return when (contextByte) {
            RnsContext.RESOURCE_ADV -> {
                val dec = link.decryptLinkPayload(rawPayload) ?: return true to null
                val accepted = RnsResourceReceiver.accept(link, dec)
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
                    val pkts = entry.sender.handleRequest(dec)
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
                            if (data != null) {
                                completed = ResourceInboundResult(
                                    resourceHashHex = receiver.hashHex,
                                    isResponse = receiver.adv.isResponse,
                                    requestId = receiver.adv.requestId,
                                    data = data,
                                )
                            }
                        }
                        RnsResourceStatus.CORRUPT, RnsResourceStatus.FAILED -> {
                            inbound.remove(receiver.hashHex)
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
                    inbound.remove(hashHex)?.handleCancel()
                }
                true to null
            }

            RnsContext.RESOURCE_RCL -> {
                val dec = link.decryptLinkPayload(rawPayload) ?: return true to null
                if (dec.size >= 32) {
                    val hashHex = RnsCrypto.bytesToHex(dec.copyOfRange(0, 32))
                    outbound.remove(hashHex)?.sender?.handleCancel()
                }
                true to null
            }

            else -> false to null
        }
    }
}
