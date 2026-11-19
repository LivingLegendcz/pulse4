package app.pulse4.sync

import kotlin.math.min
import kotlin.random.Random

/**
 * Stav doručení. „Potvrzeno příjemcem“ ([ACKED]) je jediný stav, který znamená, že druhá aplikace
 * změnu ověřila a uložila. Spojení s gateway ani uložení do schránky potvrzení není.
 */
enum class DeliveryState {
    QUEUED,                  // Čeká na spojení: změna je jen ve vlastní frontě
    HANDED_TO_TRANSPORT,     // Předáno Reticulu; zatím bez potvrzení
    STORED_IN_PROPAGATION,   // Uloženo v LXMF schránce, čeká na vyzvednutí
    ACKED,                   // Potvrzeno příjemcem
    CANCELLED,               // Zrušeno před odesláním (např. odvolání sdílení)
    SUPERSEDED,              // Nahrazeno novější verzí téhož objektu
    EXPIRED_NEEDS_SNAPSHOT,  // Nepotvrzeno déle než TTL; nahradí aktuální snapshot
}

data class OutboxItem(
    val eventId: String,
    val recipient: String,
    val kind: String,
    val objectType: String,
    val objectId: String,
    val objectVersion: Long,
    val grantId: String?,
    val wire: ByteArray,
    val createdAtMs: Long,
    val state: DeliveryState = DeliveryState.QUEUED,
    val attempts: Int = 0,
    val nextAttemptAtMs: Long = 0,
    val lastError: String? = null,
    val grantRefs: List<String> = listOfNotNull(grantId),
) {
    val sizeBytes: Int get() = wire.size
    val objectKey: String get() = "$objectType/$objectId"
    val effectiveGrantRefs: Set<String> get() = (grantRefs + listOfNotNull(grantId)).toSet()
    override fun equals(other: Any?) = other is OutboxItem && other.eventId == eventId && other.state == state && other.attempts == attempts
    override fun hashCode() = eventId.hashCode()
}

/** Autoritativní outbox žije v šifrované databázi telefonu; v testech stačí paměťová implementace. */
interface OutboxStore {
    fun insert(item: OutboxItem)
    fun update(item: OutboxItem)
    fun find(eventId: String): OutboxItem?
    fun all(): List<OutboxItem>
}

sealed interface EnqueueResult {
    data class Queued(val superseded: List<String>) : EnqueueResult
    /** Fronta je plná; vlastní archiv se nikdy nemaže, uživatel uvidí stav. */
    data object OverQuota : EnqueueResult
}

sealed interface TransportResult {
    data object Handed : TransportResult
    data object StoredInPropagation : TransportResult
    data class Failed(val reason: String) : TransportResult
}

class OutboxManager(
    private val store: OutboxStore,
    private val limits: ProtocolLimits = ProtocolLimits(),
    private val random: Random = Random.Default,
    private val baseBackoffMs: Long = 30_000,
    private val maxBackoffMs: Long = 6L * 3600 * 1000,
    private val onPermissionDenied: ((recipient: String, objectKey: String) -> Unit)? = null,
) {
    private val open = setOf(DeliveryState.QUEUED, DeliveryState.HANDED_TO_TRANSPORT, DeliveryState.STORED_IN_PROPAGATION)
    private val pendingSnapshotResets = LinkedHashSet<Pair<String, String>>()
    private val inFlightLocalFastTrackUntil = HashMap<String, Long>()

    fun queuedBytes(): Long = synchronized(this) {
        store.all().filter { it.state in open }.sumOf { it.sizeBytes.toLong() }
    }

    fun hasPendingFor(recipient: String): Boolean = synchronized(this) {
        store.all().any { it.recipient == recipient && it.state in open }
    }

    /** Počet položek čekajících na odeslání z telefonu (mimo již uložené v LXMF propagaci). */
    fun pendingCountFor(recipient: String): Int = synchronized(this) {
        store.all().count {
            it.recipient == recipient && (it.state == DeliveryState.QUEUED || it.state == DeliveryState.HANDED_TO_TRANSPORT)
        }
    }

    /** Odloží všechny čekající položky pro nedostupného příjemce, aby se fronta nezkoušela posílat po jedné položce. */
    fun deferRecipient(recipient: String, nowMs: Long, waitMs: Long): Int = synchronized(this) {
        val until = nowMs + waitMs
        var count = 0
        store.all().filter {
            it.recipient == recipient &&
                (it.state == DeliveryState.QUEUED || it.state == DeliveryState.HANDED_TO_TRANSPORT) &&
                it.nextAttemptAtMs < until
        }.forEach {
            store.update(it.copy(nextAttemptAtMs = until))
            count++
        }
        count
    }

    fun enqueue(item: OutboxItem): EnqueueResult = synchronized(this) {
        if (item.sizeBytes > limits.maxEventBytes) return@synchronized EnqueueResult.OverQuota
        if (queuedBytes() + item.sizeBytes > limits.maxQueueBytes) return@synchronized EnqueueResult.OverQuota
        val superseded = mutableListOf<String>()
        val isProjectionMutation = item.kind == Kinds.PROJECTION_UPSERT || item.kind == Kinds.PROJECTION_REMOVE
        if (item.kind in Kinds.supersedable || isProjectionMutation) {
            // Nahrazuje se jen to, co ještě neopustilo telefon; co už leží na cizím uzlu, vyřeší pořadí verzí u příjemce.
            // Zároveň nový upsert ruší neodeslaný PROJECTION_REMOVE stejného klíče a naopak (N-2).
            store.all().filter { existing ->
                existing.state == DeliveryState.QUEUED &&
                    existing.recipient == item.recipient &&
                    existing.objectKey == item.objectKey &&
                    (
                        (existing.kind == item.kind && existing.objectVersion < item.objectVersion) ||
                            (isProjectionMutation &&
                                existing.kind != item.kind &&
                                (existing.kind == Kinds.PROJECTION_UPSERT || existing.kind == Kinds.PROJECTION_REMOVE) &&
                                existing.createdAtMs <= item.createdAtMs)
                        )
            }.forEach {
                store.update(it.copy(state = DeliveryState.SUPERSEDED))
                superseded += it.eventId
            }
        }
        store.insert(item)
        EnqueueResult.Queued(superseded)
    }

    /** Položky připravené k pokusu, v pořadí vzniku (stabilní pro jednoho příjemce). */
    fun due(nowMs: Long, recipient: String? = null, localPeers: Set<String> = emptySet()): List<OutboxItem> = synchronized(this) {
        val allItems = store.all()
        val newestOpenRemoveAt = HashMap<Pair<String, String>, Long>()
        for (it in allItems) {
            if (it.kind == Kinds.PROJECTION_REMOVE && it.state in open && (recipient == null || it.recipient == recipient)) {
                val key = it.recipient to it.objectKey
                val prev = newestOpenRemoveAt[key]
                if (prev == null || it.createdAtMs > prev) newestOpenRemoveAt[key] = it.createdAtMs
            }
        }
        allItems.filter {
            val isLocalFastTrack = it.state == DeliveryState.STORED_IN_PROPAGATION &&
                it.recipient in localPeers &&
                (inFlightLocalFastTrackUntil[it.eventId] ?: 0L) <= nowMs
            val isStoredRetry = it.state == DeliveryState.STORED_IN_PROPAGATION && it.nextAttemptAtMs <= nowMs
            val maskedByNewerRemove = it.kind == Kinds.PROJECTION_UPSERT &&
                newestOpenRemoveAt[it.recipient to it.objectKey]?.let { rmAt -> it.createdAtMs <= rmAt } == true
            !maskedByNewerRemove &&
                (it.state == DeliveryState.QUEUED || it.state == DeliveryState.HANDED_TO_TRANSPORT || isLocalFastTrack || isStoredRetry) &&
                (isLocalFastTrack || it.nextAttemptAtMs <= nowMs) &&
                (recipient == null || it.recipient == recipient)
        }.sortedWith(
            compareBy<OutboxItem>({ it.createdAtMs }, { if (it.kind == Kinds.PROJECTION_REMOVE) 0 else 1 }, { it.objectVersion })
        )
    }

    fun onTransportResult(eventId: String, result: TransportResult, nowMs: Long) = synchronized(this) {
        inFlightLocalFastTrackUntil.remove(eventId)
        val it = store.find(eventId) ?: return@synchronized
        if (it.state == DeliveryState.ACKED) return@synchronized
        when (result) {
            TransportResult.Handed -> {
                val attempts = it.attempts + 1
                // Pokud aplikační ACK nedorazí do timeoutu, umožníme bezpečné opakování (idempotentní příjemce duplikát zahodí).
                val retryAt = nowMs + maxOf(60_000L, backoff(attempts))
                store.update(it.copy(state = DeliveryState.HANDED_TO_TRANSPORT, attempts = attempts, nextAttemptAtMs = retryAt))
            }
            TransportResult.StoredInPropagation -> {
                val attempts = it.attempts + 1
                val retryAt = nowMs + limits.storedPropagationRetryMs
                store.update(it.copy(state = DeliveryState.STORED_IN_PROPAGATION, attempts = attempts, nextAttemptAtMs = retryAt))
            }
            is TransportResult.Failed -> {
                val attempts = it.attempts + 1
                store.update(it.copy(state = DeliveryState.QUEUED, attempts = attempts, lastError = result.reason,
                    nextAttemptAtMs = nowMs + backoff(attempts)))
            }
        }
    }

    /**
     * Položka byla předána transportu a čeká se na výsledek: do [waitMs] se neodesílá znovu (bez duplicit v letu),
     * pak ji `due` vrátí k dalšímu pokusu. Stav zůstává „čeká na spojení“ – nic se netvrdí o doručení.
     */
    fun markAttempt(eventId: String, nowMs: Long, waitMs: Long) = synchronized(this) {
        val it = store.find(eventId) ?: return@synchronized
        if (it.state != DeliveryState.QUEUED && it.state != DeliveryState.HANDED_TO_TRANSPORT && it.state != DeliveryState.STORED_IN_PROPAGATION) return@synchronized
        inFlightLocalFastTrackUntil[eventId] = nowMs + waitMs
        store.update(it.copy(nextAttemptAtMs = nowMs + waitMs))
    }

    /** Přijetí ACK platí jen od příjemce dané události; cizí identita cizí událost nepotvrdí. */
    fun onAck(from: String, ack: Ack): Boolean = synchronized(this) {
        val it = store.find(ack.refEventId) ?: return@synchronized false
        if (it.recipient != from) return@synchronized false
        if (it.state == DeliveryState.ACKED) return@synchronized true
        return@synchronized when (ack.status) {
            Ack.Status.ACCEPTED, Ack.Status.DUPLICATE, Ack.Status.STALE -> {
                store.update(it.copy(state = DeliveryState.ACKED))
                if (it.kind == Kinds.PROJECTION_REMOVE) {
                    store.all().filter { old ->
                        old.state in open &&
                            old.kind == Kinds.PROJECTION_UPSERT &&
                            old.recipient == it.recipient &&
                            old.objectKey == it.objectKey &&
                            old.createdAtMs <= it.createdAtMs
                    }.forEach { old ->
                        store.update(old.copy(state = DeliveryState.SUPERSEDED))
                    }
                }
                true
            }
            Ack.Status.REJECTED if ack.reason == RejectReason.NOT_PERMITTED.name -> {
                // Oprávnění bylo odepřeno (např. příjemce vypnul příjem): neresetovat snapshot, jinak vznikne nekonečná smyčka.
                // Zároveň smažeme contentHash v sent ledgeru, aby pozdější zapnutí příjmu znovu poslalo celé okno (RE-5).
                store.update(it.copy(state = DeliveryState.CANCELLED, lastError = ack.reason))
                onPermissionDenied?.invoke(it.recipient, it.objectKey)
                true
            }
            // Odmítnutí (jiný důvod) a mezera nejsou doručení: položka se zastaví s chybou, aplikace vygeneruje snapshot.
            Ack.Status.REJECTED, Ack.Status.NEED_SNAPSHOT -> {
                store.update(it.copy(state = DeliveryState.EXPIRED_NEEDS_SNAPSHOT, lastError = ack.reason ?: ack.status.name))
                pendingSnapshotResets += (it.recipient to it.objectKey)
                true
            }
        }
    }

    /** Odvolání před odesláním: zruší se čekající položky grantu. Vrací počet těch, které už opustily telefon. */
    data class CancelResult(val cancelled: Int, val alreadyLeft: Int)

    fun cancelForGrant(grantId: String): CancelResult = synchronized(this) {
        var c = 0; var left = 0
        store.all().filter { grantId in it.effectiveGrantRefs }.forEach {
            when (it.state) {
                DeliveryState.QUEUED -> { store.update(it.copy(state = DeliveryState.CANCELLED)); c++ }
                DeliveryState.HANDED_TO_TRANSPORT, DeliveryState.STORED_IN_PROPAGATION -> left++
                else -> Unit
            }
        }
        CancelResult(c, left)
    }

    /**
     * Nepotvrzený souhrn starší než TTL se nahradí aktuálním snapshotem. Revize plánů a odvolání zůstávají
     * čekat. Vrací klíče objektů, pro které má aplikace vygenerovat nový snapshot.
     */
    fun expireStale(nowMs: Long): List<Pair<String, String>> = synchronized(this) {
        val out = mutableListOf<Pair<String, String>>()
        if (pendingSnapshotResets.isNotEmpty()) {
            out += pendingSnapshotResets
            pendingSnapshotResets.clear()
        }
        store.all().filter {
            val ttl = when (it.state) {
                DeliveryState.QUEUED, DeliveryState.HANDED_TO_TRANSPORT -> limits.unackedSummaryTtlMs
                DeliveryState.STORED_IN_PROPAGATION -> limits.storedPropagationTtlMs
                else -> return@filter false
            }
            it.kind in Kinds.supersedable && nowMs - it.createdAtMs > ttl
        }
            .forEach {
                store.update(it.copy(state = DeliveryState.EXPIRED_NEEDS_SNAPSHOT))
                out += it.recipient to it.objectKey
            }
        out.distinct()
    }

    /** Exponenciální prodleva s náhodným rozptylem ±20 %. */
    internal fun backoff(attempts: Int): Long {
        val exp = baseBackoffMs * (1L shl min(attempts - 1, 20))
        val capped = min(exp, maxBackoffMs)
        val jitter = 0.8 + random.nextDouble() * 0.4
        return (capped * jitter).toLong()
    }
}

class InMemoryOutboxStore : OutboxStore {
    private val items = LinkedHashMap<String, OutboxItem>()
    override fun insert(item: OutboxItem) { items[item.eventId] = item }
    override fun update(item: OutboxItem) { items[item.eventId] = item }
    override fun find(eventId: String) = items[eventId]
    override fun all() = items.values.toList()
}
