package app.pulse4.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ObservationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(rows: List<ObservationRow>)
    @Query("SELECT * FROM observation WHERE deleted = 0 AND localDate = :date ORDER BY startUtcMs") fun forDate(date: String): List<ObservationRow>
    @Query("SELECT * FROM observation WHERE deleted = 0 AND localDate BETWEEN :from AND :to ORDER BY startUtcMs") fun forRange(from: String, to: String): List<ObservationRow>
    @Query("SELECT * FROM observation WHERE deleted = 0 AND type = :type ORDER BY startUtcMs DESC LIMIT 1") fun latest(type: String): ObservationRow?
    @Query("SELECT * FROM observation WHERE deleted = 0 AND type = :type AND localDate BETWEEN :from AND :to ORDER BY startUtcMs") fun series(type: String, from: String, to: String): Flow<List<ObservationRow>>
    @Query("UPDATE observation SET deleted = 1 WHERE id IN (:ids)") fun markDeleted(ids: List<String>)
    @Query("SELECT id FROM observation WHERE provider = :provider AND deleted = 0 AND startUtcMs BETWEEN :from AND :to") fun idsIn(provider: String, from: Long, to: Long): List<String>
    @Query("SELECT * FROM observation WHERE type IN (:types) AND localDate BETWEEN :from AND :to ORDER BY startUtcMs DESC") fun ofTypesIncludingDeleted(types: List<String>, from: String, to: String): List<ObservationRow>
    @Query("UPDATE observation SET quality = 'OK' WHERE id = :id") fun confirm(id: String)
    @Query("SELECT DISTINCT localDate FROM observation WHERE deleted = 0 AND localDate IS NOT NULL AND localDate BETWEEN :from AND :to") fun datesWithData(from: String, to: String): List<String>
}

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(row: SourceRecordRow)
}

@Dao
interface ActivityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(row: ActivityRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsertTrack(row: TrackRow)
    @Query("SELECT * FROM activity ORDER BY startUtcMs DESC") fun all(): Flow<List<ActivityRow>>
    @Query("SELECT * FROM activity ORDER BY startUtcMs DESC") fun allNow(): List<ActivityRow>
    @Query("SELECT * FROM activity WHERE startUtcMs >= :from ORDER BY startUtcMs DESC") fun since(from: Long): List<ActivityRow>
    @Query("SELECT * FROM activity WHERE id = :id") fun get(id: String): ActivityRow?
    @Query("SELECT * FROM track WHERE activityId = :id") fun track(id: String): TrackRow?
    @Query("SELECT COUNT(*) FROM activity") fun count(): Int
}

@Dao
interface AreaStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: AreaStateRow)
    @Query("SELECT * FROM area_state WHERE area = :area") fun get(area: String): AreaStateRow?
    @Query("SELECT * FROM area_state") fun all(): Flow<List<AreaStateRow>>
}

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(row: ContactRow)
    @Query("SELECT * FROM contact ORDER BY displayName") fun all(): Flow<List<ContactRow>>
    @Query("SELECT * FROM contact") fun allNow(): List<ContactRow>
    @Query("SELECT COUNT(*) FROM contact WHERE identity = :id") fun count(id: String): Int
    @Query("DELETE FROM contact WHERE identity = :id") fun delete(id: String)
}

@Dao
interface GrantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(row: GrantRow)
    @Query("SELECT * FROM share_grant") fun all(): Flow<List<GrantRow>>
    @Query("SELECT * FROM share_grant") fun allNow(): List<GrantRow>
    @Query("SELECT * FROM share_grant WHERE revoked = 0") fun activeNow(): List<GrantRow>
    @Query("SELECT * FROM share_grant WHERE id = :id") fun get(id: String): GrantRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsertGroup(row: GroupRow)
    @Query("SELECT * FROM grp") fun groups(): Flow<List<GroupRow>>
    @Query("SELECT * FROM grp") fun groupsNow(): List<GroupRow>
    @Query("SELECT * FROM grp WHERE id = :id") fun group(id: String): GroupRow?
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insert(row: OutboxRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun update(row: OutboxRow)
    @Query("SELECT * FROM outbox WHERE eventId = :id") fun find(id: String): OutboxRow?
    @Query("SELECT * FROM outbox") fun all(): List<OutboxRow>
    @Query("SELECT * FROM outbox ORDER BY createdAtMs DESC LIMIT 200") fun recent(): Flow<List<OutboxRow>>
    @Query("DELETE FROM outbox WHERE state IN ('ACKED','SUPERSEDED','CANCELLED') AND createdAtMs < :before") fun purge(before: Long)
}

@Dao
interface InboxDao {
    @Query("SELECT contentHash FROM receipt WHERE eventId = :id") fun receiptHash(id: String): String?
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertReceipt(row: ReceiptRow)
    @Query("SELECT version FROM received_object WHERE author = :a AND objectType = :t AND objectId = :i") fun version(a: String, t: String, i: String): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putObject(row: ReceivedRow)
    @Query("DELETE FROM received_object WHERE author = :a AND objectType = :t AND objectId = :i") fun removeObject(a: String, t: String, i: String)
    @Query("SELECT * FROM received_object WHERE objectType = :t ORDER BY objectId DESC") fun objects(t: String): Flow<List<ReceivedRow>>
    @Query("SELECT * FROM received_object WHERE objectType = :t ORDER BY objectId DESC") fun objectsNow(t: String): List<ReceivedRow>
    @Query("SELECT * FROM received_object WHERE author = :a AND objectType = :t") fun objectsOf(a: String, t: String): Flow<List<ReceivedRow>>
    @Query("SELECT * FROM sent_projection WHERE recipient = :r AND objectKey = :k") fun sent(r: String, k: String): SentRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putSent(row: SentRow)
    @Query("DELETE FROM received_object WHERE author = :a") fun removeAllFrom(a: String)
    @Query("SELECT MAX(appliedAtMs) FROM receipt WHERE author = :a") fun lastReceived(a: String): Long?
}

@Dao
interface PlanDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertRevision(row: PlanRevisionRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun restoreRevision(row: PlanRevisionRow)
    @Query("SELECT * FROM plan_revision ORDER BY planId, revision") fun allRevisions(): List<PlanRevisionRow>
    @Query("SELECT * FROM plan_revision WHERE planId = :id ORDER BY revision DESC LIMIT 1") fun head(id: String): PlanRevisionRow?
    @Query("SELECT * FROM plan_revision WHERE planId = :id ORDER BY revision") fun revisions(id: String): List<PlanRevisionRow>
    @Query("SELECT * FROM plan_revision WHERE planId = :id ORDER BY revision DESC LIMIT 1") fun headFlow(id: String): Flow<PlanRevisionRow?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putProposal(row: ProposalRow)
    @Query("SELECT * FROM proposal WHERE id = :id") fun proposal(id: String): ProposalRow?
    @Query("SELECT * FROM proposal WHERE status IN ('PENDING','CONFLICT') ORDER BY id") fun openProposals(): Flow<List<ProposalRow>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putExport(row: ExportRow)
    @Query("SELECT * FROM export_record WHERE planId = :planId") fun exports(planId: String): List<ExportRow>
    @Query("SELECT * FROM export_record") fun exportsFlow(): Flow<List<ExportRow>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putTemplate(row: TemplateRow)
    @Query("SELECT * FROM workout_template ORDER BY name") fun templates(): Flow<List<TemplateRow>>
    @Query("SELECT * FROM workout_template WHERE id = :id") fun template(id: String): TemplateRow?
}

@Dao
interface SettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: SettingRow)
    @Query("SELECT value FROM setting WHERE `key` = :key") fun get(key: String): String?
    @Query("SELECT * FROM setting") fun all(): Flow<List<SettingRow>>
}

@Dao
interface ExclusionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: ExcludedRow)
    @Query("SELECT COUNT(*) FROM excluded_measurement WHERE observationId = :id") fun count(id: String): Int
    @Query("DELETE FROM excluded_measurement WHERE observationId = :id") fun remove(id: String)
}
