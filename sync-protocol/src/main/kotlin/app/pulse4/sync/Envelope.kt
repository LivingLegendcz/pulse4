package app.pulse4.sync

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Návrhové ochranné limity aplikace; nejsou to limity Reticula a cizí schránka může mít přísnější. */
data class ProtocolLimits(
    val maxEventBytes: Int = 128 * 1024,
    val summaryTargetBytes: Int = 4 * 1024,
    val maxAttachmentBytes: Long = 25L * 1024 * 1024,
    val maxQueueBytes: Long = 100L * 1024 * 1024,
    val unackedSummaryTtlMs: Long = 7L * 24 * 3600 * 1000,
    val storedPropagationRetryMs: Long = 6L * 3600 * 1000,
    val storedPropagationTtlMs: Long = 14L * 24 * 3600 * 1000,
)

object Kinds {
    const val PROJECTION_UPSERT = "projection.upsert"
    const val PROJECTION_REMOVE = "projection.remove"
    const val MEMBERSHIP_UPDATE = "membership.update"
    const val PLAN_PROPOSAL = "plan.proposal"
    const val PLAN_REVISION = "plan.revision"
    const val PLAN_DECISION = "plan.decision"
    const val SNAPSHOT_REQUEST = "snapshot.request"
    const val ACK = "ack"
    const val FAMILY_LINK_REQUEST = "family.link.request"
    const val FAMILY_LINK_RESPONSE = "family.link.response"
    const val ADMIN_HANDOVER_REQUEST = "admin.handover.request"
    const val ADMIN_HANDOVER_ACCEPT = "admin.handover.accept"
    const val FAMILY_LINK_REVOKE = "family.link.revoke"
    const val GUARDIAN_CONSENT_REQUEST = "guardian.consent.request"
    const val GUARDIAN_CONSENT_APPROVED = "guardian.consent.approved"
    const val GUARDIAN_CONSENT_REJECTED = "guardian.consent.rejected"
    const val GUARDIAN_CONSENT_REVOKED = "guardian.consent.revoked"
    const val GUARDIAN_AGE_RELEASE = "guardian.age.release"

    /** Novější souhrn může nahradit starší nedoručený; revize plánů a odvolání se nikdy neslučují. */
    val supersedable = setOf(PROJECTION_UPSERT)
}

/**
 * Obálka události. Hodiny telefonu nejsou autoritou pro pořadí – rozhoduje [objectVersion].
 * `payload` je řetězec přesně v podobě, z níž se počítá [contentHash].
 */
@Serializable
data class Envelope(
    val v: Int,
    val eventId: String,
    val author: String,
    val recipient: String,
    val kind: String,
    val objectType: String,
    val objectId: String,
    val objectVersion: Long,
    val baseVersion: Long? = null,
    val grantRef: String? = null,
    val membershipVersion: Long? = null,
    val createdAtMs: Long,
    val expiresAtMs: Long? = null,
    val contentHash: String,
)

@Serializable
data class WireMessage(val envelope: Envelope, val payload: String)

@Serializable
data class Ack(val refEventId: String, val status: Status, val reason: String? = null) {
    @Serializable enum class Status { ACCEPTED, DUPLICATE, STALE, REJECTED, NEED_SNAPSHOT }
}

object Wire {
    const val VERSION = 1
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hashOf(payload: String) = sha256Hex(payload.toByteArray(Charsets.UTF_8))

    fun encode(m: WireMessage): ByteArray = json.encodeToString(m).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): WireMessage? =
        runCatching { json.decodeFromString<WireMessage>(bytes.toString(Charsets.UTF_8)) }.getOrNull()

    fun newEventId(): String = UUID.randomUUID().toString()
}

/** Sestavení odchozí zprávy; hash se počítá z přesného textu payloadu. */
class MessageBuilder(
    private val selfId: String,
    private val newId: () -> String = Wire::newEventId,
) {
    fun build(
        recipient: String,
        kind: String,
        objectType: String,
        objectId: String,
        objectVersion: Long,
        payload: String,
        nowMs: Long,
        baseVersion: Long? = null,
        grantRef: String? = null,
        membershipVersion: Long? = null,
        expiresAtMs: Long? = null,
    ): WireMessage = WireMessage(
        Envelope(
            v = Wire.VERSION, eventId = newId(), author = selfId, recipient = recipient, kind = kind,
            objectType = objectType, objectId = objectId, objectVersion = objectVersion,
            baseVersion = baseVersion, grantRef = grantRef, membershipVersion = membershipVersion,
            createdAtMs = nowMs, expiresAtMs = expiresAtMs, contentHash = Wire.hashOf(payload),
        ),
        payload,
    )
}
