package app.pulse4.sync

import app.pulse4.sharing.Group
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class RejectReason {
    TOO_LARGE, MALFORMED, UNKNOWN_SCHEMA, AUTHOR_MISMATCH, WRONG_RECIPIENT, HASH_MISMATCH, EXPIRED,
    UNKNOWN_CONTACT, ID_CONFLICT, NOT_PERMITTED, INVALID_PAYLOAD,
}

/** Lokální stav příjemce. Implementace musí `inTransaction` provést atomicky (Room: `withTransaction`). */
interface ReceiverStore {
    fun <T> inTransaction(block: () -> T): T
    fun isVerifiedContact(identity: String): Boolean
    fun receiptHash(eventId: String): String?
    fun recordReceipt(eventId: String, contentHash: String, author: String, appliedAtMs: Long)
    fun objectVersion(author: String, objectType: String, objectId: String): Long?
    fun putObject(author: String, objectType: String, objectId: String, version: Long, payload: String)
    fun removeObject(author: String, objectType: String, objectId: String, version: Long)
    fun group(groupId: String): Group?
    fun putGroup(group: Group)
}

sealed interface HandlerResult {
    data object Applied : HandlerResult
    data object Stale : HandlerResult
    data object NeedSnapshot : HandlerResult
    data class Rejected(val reason: RejectReason) : HandlerResult
}

fun interface KindHandler {
    /** Voláno uvnitř transakce; změny stavu provádí jen zde. Nesmí vyvolat síťové operace. */
    fun handle(env: Envelope, payload: String, store: ReceiverStore, nowMs: Long): HandlerResult
}

fun interface AckSink {
    fun onAck(from: String, ack: Ack)
}

sealed interface Outcome {
    /** Aplikační ACK k odeslání *po* commitu transakce; `null` = neodpovídat (neověřený odesílatel). */
    val ack: WireMessage?

    data class Applied(override val ack: WireMessage?) : Outcome
    data class Duplicate(override val ack: WireMessage?) : Outcome
    data class Stale(override val ack: WireMessage?) : Outcome
    data class NeedSnapshot(override val ack: WireMessage?) : Outcome
    data class Rejected(val reason: RejectReason, override val ack: WireMessage?) : Outcome
    data object AckProcessed : Outcome { override val ack: WireMessage? = null }
}

/**
 * Příjem zprávy: podpis (autor) ověřil transport, zde se kontroluje shoda autora s obálkou, příjemce,
 * velikost, schéma, hash a oprávnění. Změna a záznam přijetí se uloží v jedné transakci; ACK odejde až po ní.
 */
class Receiver(
    private val selfId: String,
    private val store: ReceiverStore,
    private val handlers: Map<String, KindHandler>,
    private val ackSink: AckSink,
    private val limits: ProtocolLimits = ProtocolLimits(),
    private val builder: MessageBuilder = MessageBuilder(selfId),
) {
    /** @param authenticatedSource identita odesílatele ověřená transportní vrstvou (LXMF podpis). */
    fun receive(authenticatedSource: String, bytes: ByteArray, nowMs: Long): Outcome {
        if (bytes.size > limits.maxEventBytes) return reject(RejectReason.TOO_LARGE, authenticatedSource, null, nowMs)
        val msg = Wire.decode(bytes) ?: return Outcome.Rejected(RejectReason.MALFORMED, null)
        val env = msg.envelope

        // Pořadí kontrol: nejdřív identita, aby neověřený odesílatel nezískal žádnou odpověď.
        if (env.author != authenticatedSource) return Outcome.Rejected(RejectReason.AUTHOR_MISMATCH, null)
        if (!store.isVerifiedContact(authenticatedSource)) return Outcome.Rejected(RejectReason.UNKNOWN_CONTACT, null)
        if (env.recipient != selfId) return reject(RejectReason.WRONG_RECIPIENT, authenticatedSource, env, nowMs)
        if (env.v > Wire.VERSION || env.v < 1) return reject(RejectReason.UNKNOWN_SCHEMA, authenticatedSource, env, nowMs)
        if (Wire.hashOf(msg.payload) != env.contentHash) return reject(RejectReason.HASH_MISMATCH, authenticatedSource, env, nowMs)
        env.expiresAtMs?.let { if (it < nowMs) return reject(RejectReason.EXPIRED, authenticatedSource, env, nowMs) }

        if (env.kind == Kinds.ACK) return handleAck(env, msg.payload)
        val handler = handlers[env.kind] ?: return reject(RejectReason.UNKNOWN_SCHEMA, authenticatedSource, env, nowMs)

        // Atomicky: kontrola opakování + změna + záznam přijetí.
        val result: Result = store.inTransaction {
            val seen = store.receiptHash(env.eventId)
            when {
                seen != null && seen == env.contentHash -> Result.Dup
                seen != null -> Result.Conflict
                else -> when (val r = handler.handle(env, msg.payload, store, nowMs)) {
                    HandlerResult.Applied, HandlerResult.Stale -> {
                        store.recordReceipt(env.eventId, env.contentHash, env.author, nowMs)
                        if (r == HandlerResult.Applied) Result.Ok else Result.StaleOk
                    }
                    // Bez záznamu přijetí: pozdější doručení chybějící revize může událost ještě aplikovat.
                    HandlerResult.NeedSnapshot -> Result.Gap
                    is HandlerResult.Rejected -> Result.Reject(r.reason)
                }
            }
        }
        return when (result) {
            Result.Ok -> Outcome.Applied(ack(env, Ack.Status.ACCEPTED, null, nowMs))
            Result.StaleOk -> Outcome.Stale(ack(env, Ack.Status.STALE, null, nowMs))
            Result.Dup -> Outcome.Duplicate(ack(env, Ack.Status.DUPLICATE, null, nowMs))
            Result.Gap -> Outcome.NeedSnapshot(ack(env, Ack.Status.NEED_SNAPSHOT, null, nowMs))
            Result.Conflict -> reject(RejectReason.ID_CONFLICT, authenticatedSource, env, nowMs)
            is Result.Reject -> reject(result.reason, authenticatedSource, env, nowMs)
        }
    }

    private sealed interface Result {
        data object Ok : Result
        data object StaleOk : Result
        data object Dup : Result
        data object Gap : Result
        data object Conflict : Result
        data class Reject(val reason: RejectReason) : Result
    }

    private fun handleAck(env: Envelope, payload: String): Outcome {
        val ack = runCatching { Wire.json.decodeFromString<Ack>(payload) }.getOrNull()
            ?: return Outcome.Rejected(RejectReason.INVALID_PAYLOAD, null)
        ackSink.onAck(env.author, ack)
        return Outcome.AckProcessed
    }

    private fun reject(reason: RejectReason, to: String, env: Envelope?, nowMs: Long): Outcome.Rejected {
        // Na vlastní chybné zprávy odpovídáme jen ověřenému kontaktu a jen když známe ID události.
        val canReply = env != null && store.isVerifiedContact(to) && reason != RejectReason.WRONG_RECIPIENT
        return Outcome.Rejected(reason, if (canReply) ack(env, Ack.Status.REJECTED, reason.name, nowMs) else null)
    }

    private fun ack(env: Envelope, status: Ack.Status, reason: String?, nowMs: Long): WireMessage =
        builder.build(
            recipient = env.author, kind = Kinds.ACK, objectType = "ack", objectId = env.eventId,
            objectVersion = 0, payload = Wire.json.encodeToString(Ack(env.eventId, status, reason)), nowMs = nowMs,
        )
}

/** Vestavěné obsluhy druhů událostí, které nepotřebují znalost plánů. */
object BuiltInHandlers {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Projekce o autorovi smí publikovat jen on sám: klíč úložiště vždy obsahuje autora z ověřené obálky,
     * takže cizí objekt nelze přepsat. Starší verze nikdy nevrací stav zpět.
     */
    val projectionUpsert = KindHandler { env, payload, store, _ ->
        if (runCatching { json.parseToJsonElement(payload) }.isFailure) return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
        val cur = store.objectVersion(env.author, env.objectType, env.objectId)
        if (cur != null && env.objectVersion <= cur) return@KindHandler HandlerResult.Stale
        store.putObject(env.author, env.objectType, env.objectId, env.objectVersion, payload)
        HandlerResult.Applied
    }

    val projectionRemove = KindHandler { env, _, store, _ ->
        val cur = store.objectVersion(env.author, env.objectType, env.objectId)
        if (cur != null && env.objectVersion < cur) return@KindHandler HandlerResult.Stale
        store.removeObject(env.author, env.objectType, env.objectId, env.objectVersion)
        HandlerResult.Applied
    }

    /** Změnu členství podepisuje správce nebo cíl probíhajícího předání; samo o sobě nerozšiřuje souhlas vlastníků dat. */
    val membershipUpdate = KindHandler { env, payload, store, _ ->
        val g = runCatching { json.decodeFromString<Group>(payload) }.getOrNull()
            ?: return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
        if (!g.dissolved && g.effectiveAdmins.isEmpty()) {
            return@KindHandler HandlerResult.Rejected(RejectReason.INVALID_PAYLOAD)
        }
        val existing = store.group(g.id)
        if (existing != null) {
            val permitted = env.author in existing.effectiveAdmins ||
                existing.pendingAdminHandover?.targetMemberId == env.author
            if (!permitted) return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
            if (g.version <= existing.version) return@KindHandler HandlerResult.Stale
        } else {
            if (env.author !in g.effectiveAdmins) return@KindHandler HandlerResult.Rejected(RejectReason.NOT_PERMITTED)
        }
        store.putGroup(g)
        HandlerResult.Applied
    }

    fun all(): Map<String, KindHandler> = mapOf(
        Kinds.PROJECTION_UPSERT to projectionUpsert,
        Kinds.PROJECTION_REMOVE to projectionRemove,
        Kinds.MEMBERSHIP_UPDATE to membershipUpdate,
    )
}
