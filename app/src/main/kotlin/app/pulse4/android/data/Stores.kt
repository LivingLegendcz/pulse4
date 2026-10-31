package app.pulse4.android.data

import app.pulse4.provider.AreaState
import app.pulse4.provider.AreaStateStore
import app.pulse4.provider.SyncArea
import app.pulse4.sharing.Group
import app.pulse4.sharing.PlanRight
import app.pulse4.sharing.ShareGrant
import app.pulse4.sync.DeliveryState
import app.pulse4.sync.OutboxItem
import app.pulse4.sync.OutboxStore
import app.pulse4.sync.ReceiverStore
import app.pulse4.sync.SentLedger
import app.pulse4.sync.SentRecord
import app.pulse4.sync.Wire
import app.pulse4.training.ChangeProposal
import app.pulse4.training.PlanRevision
import app.pulse4.training.PlanStore
import app.pulse4.training.ProposalStatus
import java.util.concurrent.Callable

/** Přijatá data a záznam přijetí se mění v jedné databázové transakci; ACK odchází až po commitu. */
class RoomReceiverStore(private val db: AppDatabase) : ReceiverStore {
    override fun <T> inTransaction(block: () -> T): T = db.runInTransaction(Callable { block() })

    override fun isVerifiedContact(identity: String) = db.contacts().count(identity) > 0
    override fun receiptHash(eventId: String) = db.inbox().receiptHash(eventId)
    override fun recordReceipt(eventId: String, contentHash: String, author: String, appliedAtMs: Long) =
        db.inbox().insertReceipt(ReceiptRow(eventId, contentHash, author, appliedAtMs))

    override fun objectVersion(author: String, objectType: String, objectId: String) = db.inbox().version(author, objectType, objectId)
    override fun putObject(author: String, objectType: String, objectId: String, version: Long, payload: String) =
        db.inbox().putObject(ReceivedRow(author, objectType, objectId, version, payload))

    override fun removeObject(author: String, objectType: String, objectId: String, version: Long) =
        db.inbox().removeObject(author, objectType, objectId)

    override fun group(groupId: String): Group? =
        db.grants().group(groupId)?.let { Wire.json.decodeFromString(Group.serializer(), it.json) }

    override fun putGroup(group: Group) =
        db.grants().upsertGroup(GroupRow(group.id, group.version, Wire.json.encodeToString(Group.serializer(), group)))
}

class RoomOutboxStore(private val db: AppDatabase) : OutboxStore {
    override fun insert(item: OutboxItem) = db.outbox().insert(item.toRow())
    override fun update(item: OutboxItem) = db.outbox().update(item.toRow())
    override fun find(eventId: String) = db.outbox().find(eventId)?.toItem()
    override fun all() = db.outbox().all().map { it.toItem() }

    private fun OutboxItem.toRow() = OutboxRow(eventId, recipient, kind, objectType, objectId, objectVersion, grantId, wire,
        createdAtMs, state.name, attempts, nextAttemptAtMs, lastError)

    private fun OutboxRow.toItem() = OutboxItem(eventId, recipient, kind, objectType, objectId, objectVersion, grantId, wire,
        createdAtMs, DeliveryState.valueOf(state), attempts, nextAttemptAtMs, lastError)
}

/** Klíč stavu = `prefix + oblast`, aby se Garmin a Withings/Health Connect nepřepisovaly (prázdný prefix = Garmin). */
class RoomAreaStateStore(private val db: AppDatabase, private val prefix: String = "") : AreaStateStore {
    override fun get(area: SyncArea): AreaState =
        db.areaStates().get(prefix + area.name)?.let { AreaState(it.cursorMs, it.lastSuccessMs, it.lastAttemptMs, it.lastError, it.retryNotBeforeMs) } ?: AreaState()

    override fun put(area: SyncArea, state: AreaState) =
        db.areaStates().put(AreaStateRow(prefix + area.name, state.cursorMs, state.lastSuccessMs, state.lastAttemptMs, state.lastError, state.retryNotBeforeMs))
}

class RoomSentLedger(private val db: AppDatabase) : SentLedger {
    override fun last(recipient: String, objectKey: String) =
        db.inbox().sent(recipient, objectKey)?.let { SentRecord(it.version, it.contentHash) }

    fun record(recipient: String, objectKey: String, version: Long, hash: String) =
        db.inbox().putSent(SentRow(recipient, objectKey, version, hash))
}

/** Stav plánů u vlastníka (autority). Oprávnění se odvozují z aktuálních, neodvolaných grantů. */
class RoomPlanStore(private val db: AppDatabase) : PlanStore {
    private val json = Wire.json

    override fun head(planId: String) = db.plans().head(planId)?.let { json.decodeFromString(PlanRevision.serializer(), it.json) }
    override fun revisions(planId: String) = db.plans().revisions(planId).map { json.decodeFromString(PlanRevision.serializer(), it.json) }

    override fun saveRevision(r: PlanRevision) =
        db.plans().insertRevision(PlanRevisionRow(r.planId, r.revision, json.encodeToString(PlanRevision.serializer(), r)))

    override fun saveProposal(p: ChangeProposal, status: ProposalStatus) =
        db.plans().putProposal(ProposalRow(p.id, p.planId, status.name, json.encodeToString(ChangeProposal.serializer(), p)))

    override fun proposal(id: String): Pair<ChangeProposal, ProposalStatus>? =
        db.plans().proposal(id)?.let { json.decodeFromString(ChangeProposal.serializer(), it.json) to ProposalStatus.valueOf(it.status) }

    override fun rightOf(planId: String, authorId: String): PlanRight? =
        db.grants().activeNow()
            .map { json.decodeFromString(ShareGrant.serializer(), it.json) }
            .filter { authorId in it.recipients }
            .mapNotNull { it.planRight(planId) }
            .maxByOrNull { it.ordinal }
}
