package app.pulse4.android.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.pulse4.domain.czechName

// Složité objekty se ukládají jako JSON (kotlinx-serialization) vedle několika indexovaných sloupců.

@Entity(tableName = "observation", indices = [Index("type", "startUtcMs"), Index("localDate")])
data class ObservationRow(
    @PrimaryKey val id: String,
    val provider: String, val accountId: String, val externalId: String?,
    val type: String, val startUtcMs: Long, val endUtcMs: Long?, val value: Double,
    val zoneId: String, val localDate: String?, val quality: String, val sourceVersion: Int, val deleted: Boolean,
    val origin: String? = null,
)

/** Původní odpověď poskytovatele – originál se nepřepisuje, nový import vytvoří novou verzi. */
@Entity(tableName = "source_record", indices = [Index("hash")])
data class SourceRecordRow(
    @PrimaryKey val id: String, val provider: String, val accountId: String,
    val body: String, val hash: String, val importedAtMs: Long,
)

@Entity(tableName = "activity", indices = [Index("startUtcMs")])
data class ActivityRow(
    @PrimaryKey val id: String,
    val startUtcMs: Long,
    val sport: String,
    val json: String,
    val title: String? = null,
    val distanceM: Double? = null,
    val movingTimeS: Double? = null,
    val ascentM: Double? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val caloriesKcal: Double? = null,
    val aerobicTe: Double? = null,
    val anaerobicTe: Double? = null,
    val trainingLoad: Double? = null,
    val avgPowerW: Double? = null,
    val avgCadence: Double? = null,
    val zoneId: String? = null
)

data class ActivitySummaryRow(
    val id: String,
    val startUtcMs: Long,
    val sport: String,
    val title: String?,
    val distanceM: Double?,
    val movingTimeS: Double?,
    val ascentM: Double?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val caloriesKcal: Double?,
    val aerobicTe: Double?,
    val anaerobicTe: Double?,
    val trainingLoad: Double?,
    val avgPowerW: Double?,
    val avgCadence: Double?,
    val zoneId: String?,
    val json: String? = null
)

data class IdAndJson(val id: String, val json: String)

fun app.pulse4.domain.Activity.toRow(jsonString: String): ActivityRow = ActivityRow(
    id = this.id,
    startUtcMs = this.startUtcMs,
    sport = this.sport.name,
    json = jsonString,
    title = this.title?.takeIf { it.isNotBlank() } ?: this.sport.czechName(),
    distanceM = this.distanceM,
    movingTimeS = this.movingTimeS?.takeIf { it > 0.0 } ?: this.totalTimeS,
    ascentM = this.ascentM,
    avgHeartRate = this.avgHeartRate,
    maxHeartRate = this.maxHeartRate,
    caloriesKcal = this.caloriesKcal,
    aerobicTe = this.aerobicTrainingEffect,
    anaerobicTe = this.anaerobicTrainingEffect,
    trainingLoad = this.trainingLoad,
    avgPowerW = this.avgPowerW,
    avgCadence = this.avgCadence,
    zoneId = this.zoneId
)

/**
 * Vrátí reálný čas pohybu aktivity. U dříve naimportovaných indoor aktivit (kardio, silový trénink, HIIT),
 * kde Garmin poslal `movingDuration = 0.0`, doplní celkový čas tréninku z uloženého JSONu.
 */
fun ActivitySummaryRow.effectiveMovingTimeS(): Double? {
    val direct = movingTimeS
    if (direct != null && direct > 0.0) return direct
    val raw = json ?: return direct
    val dom = runCatching {
        app.pulse4.sync.Wire.json.decodeFromString(app.pulse4.domain.Activity.serializer(), raw)
    }.getOrNull() ?: return direct
    return dom.movingTimeS?.takeIf { it > 0.0 } ?: dom.totalTimeS.takeIf { it > 0.0 } ?: direct
}

@Entity(tableName = "track")
data class TrackRow(@PrimaryKey val activityId: String, val json: String)

@Entity(tableName = "area_state")
data class AreaStateRow(
    @PrimaryKey val area: String, val cursorMs: Long?, val lastSuccessMs: Long?, val lastAttemptMs: Long?,
    val lastError: String?, val retryNotBeforeMs: Long?,
)

@Entity(tableName = "contact")
data class ContactRow(
    @PrimaryKey val identity: String, val displayName: String, val publicKeyB64: String,
    val fingerprint: String, val verifiedAtMs: Long,
)

@Entity(tableName = "share_grant")
data class GrantRow(@PrimaryKey val id: String, val revoked: Boolean, val json: String)

@Entity(tableName = "grp")
data class GroupRow(@PrimaryKey val id: String, val version: Long, val json: String)

@Entity(tableName = "outbox", indices = [Index("state", "nextAttemptAtMs")])
data class OutboxRow(
    @PrimaryKey val eventId: String, val recipient: String, val kind: String,
    val objectType: String, val objectId: String, val objectVersion: Long, val grantId: String?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val wire: ByteArray,
    val createdAtMs: Long, val state: String, val attempts: Int, val nextAttemptAtMs: Long, val lastError: String?,
)

@Entity(tableName = "receipt")
data class ReceiptRow(@PrimaryKey val eventId: String, val contentHash: String, val author: String, val appliedAtMs: Long)

@Entity(tableName = "received_object", primaryKeys = ["author", "objectType", "objectId"])
data class ReceivedRow(val author: String, val objectType: String, val objectId: String, val version: Long, val payload: String)

/** Co a které verzi jsme komu odeslali (neposílají se nezměněné projekce). */
@Entity(tableName = "sent_projection", primaryKeys = ["recipient", "objectKey"])
data class SentRow(val recipient: String, val objectKey: String, val version: Long, val contentHash: String)

@Entity(tableName = "plan_revision", primaryKeys = ["planId", "revision"])
data class PlanRevisionRow(val planId: String, val revision: Long, val json: String)

@Entity(tableName = "proposal", indices = [Index("planId")])
data class ProposalRow(@PrimaryKey val id: String, val planId: String, val status: String, val json: String)

@Entity(tableName = "export_record", indices = [Index("planId")])
data class ExportRow(@PrimaryKey val exportId: String, val planId: String, val scheduledWorkoutId: String, val json: String)

@Entity(tableName = "workout_template")
data class TemplateRow(@PrimaryKey val id: String, val name: String, val json: String)

@Entity(tableName = "setting")
data class SettingRow(@PrimaryKey val key: String, val value: String)

/** Měření, která uživatel označil za chybná; přežije opakovaný import. */
@Entity(tableName = "excluded_measurement")
data class ExcludedRow(@PrimaryKey val observationId: String, val excludedAtMs: Long)

@Entity(tableName = "family_link")
data class FamilyLinkRow(
    @PrimaryKey val id: String,
    val parentId: String,
    val childId: String,
    val status: String,
    val version: Long,
    val json: String,
)

@Entity(tableName = "pending_request")
data class PendingRequestRow(
    @PrimaryKey val id: String,
    val kind: String,
    val fromId: String,
    val toId: String,
    val status: String,
    val createdAtMs: Long,
    val json: String,
)

/**
 * Guardian consent record. `CHILD_OUT` rows are the child's own outgoing requests (authoritative for what is
 * effective); `GUARDIAN_IN` rows are requests a guardian received. [requestJson] / [decisionJson] hold the
 * signed canonical inputs ([app.pulse4.sharing.GuardianConsentRequest] / [app.pulse4.sharing.GuardianDecision]),
 * [proposedGrantJson] the grant a SHARE_GRANT request was made for (activated only when approved).
 * Deliberately NOT part of backup/restore and never rewritten by the identity remap.
 */
@Entity(tableName = "guardian_consent", indices = [Index("role", "status"), Index("subjectId")])
data class GuardianConsentRow(
    @PrimaryKey val requestId: String,
    val role: String,
    val peerId: String,
    val subject: String,
    val subjectId: String,
    val linkId: String?,
    val permissionVersion: Long,
    val status: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val decidedAtMs: Long?,
    val requestJson: String,
    val proposedGrantJson: String?,
    val decisionJson: String?,
)
