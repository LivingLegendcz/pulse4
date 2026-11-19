package app.pulse4.sync

import app.pulse4.domain.MetricObservation
import app.pulse4.domain.Provider
import app.pulse4.sharing.Field
import app.pulse4.sharing.Group
import app.pulse4.sharing.MembershipPolicy
import app.pulse4.sharing.OwnerDay
import app.pulse4.sharing.OwnerDayAssembler
import app.pulse4.sharing.Projector
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.SharedProjection
import java.time.LocalDate
import kotlinx.serialization.json.JsonElement

data class SentRecord(val version: Long, val contentHash: String)

/** Co jsme kterému příjemci naposledy odeslali (objekt = `daily/<datum>`). */
interface SentLedger {
    fun last(recipient: String, objectKey: String): SentRecord?
}

data class PlannedSend(
    val message: WireMessage,
    val grantId: String,
    val objectKey: String,
    val contentHash: String,
    val grantIds: List<String> = listOf(grantId),
)

/**
 * Plánovač projekcí: pro každý aktivní grant a skutečného příjemce vyrobí omezenou projekci a zařadí ji,
 * jen když se od posledního odeslání změnila. Zrušené granty a odebraní členové nedostanou nic.
 */
class ShareBuilder(private val selfId: String, private val builder: MessageBuilder = MessageBuilder(selfId)) {

    fun plan(
        grants: List<ShareGrant>,
        groups: Map<String, Group>,
        days: List<OwnerDay>,
        ledger: SentLedger,
        nowMs: Long,
    ): List<PlannedSend> {
        val activeGrants = grants.filter { it.active && it.ownerId == selfId }
        val recipientsByGrant = activeGrants.associateWith {
            MembershipPolicy.effectiveRecipients(it, it.groupId?.let(groups::get))
        }
        val allRecipients = recipientsByGrant.values.flatten().toSortedSet()
        val out = mutableListOf<PlannedSend>()
        for (r in allRecipients) {
            for (day in days) {
                val matching = activeGrants
                    .filter { r in recipientsByGrant.getValue(it) && it.scope.covers(day.date) }
                    .sortedBy { it.id }
                if (matching.isEmpty()) continue
                val merged = mergeGrantsForRecipient(matching)
                val contributingIds = matching
                    .filter { g -> Projector.project(g, day, nowMs) != null }
                    .map { it.id }
                    .ifEmpty { listOf(merged.id) }
                planDayForRecipient(merged, contributingIds, r, day, ledger, nowMs, out)
            }
        }
        return out
    }

    /** Plánovač respektující zdrojový filtr `ShareGrant.sources` tím, že den sestaví přímo z pozorování pro každého příjemce. */
    fun plan(
        grants: List<ShareGrant>,
        groups: Map<String, Group>,
        dates: List<LocalDate>,
        observations: List<MetricObservation>,
        ledger: SentLedger,
        nowMs: Long,
    ): List<PlannedSend> {
        val activeGrants = grants.filter { it.active && it.ownerId == selfId }
        val recipientsByGrant = activeGrants.associateWith {
            MembershipPolicy.effectiveRecipients(it, it.groupId?.let(groups::get))
        }
        val allRecipients = recipientsByGrant.values.flatten().toSortedSet()
        val out = mutableListOf<PlannedSend>()
        for (r in allRecipients) {
            for (date in dates) {
                val matching = activeGrants
                    .filter { r in recipientsByGrant.getValue(it) && it.scope.covers(date) }
                    .sortedBy { it.id }
                if (matching.isEmpty()) continue
                val primaryGrant = matching.first()
                val contributingIds = matching.filter { g ->
                    val gDay = OwnerDayAssembler.assembleForGrant(date, observations, g)
                    Projector.project(g, gDay, nowMs) != null
                }.map { it.id }.ifEmpty { listOf(primaryGrant.id) }
                val fieldsBySources = groupFieldsByEffectiveSources(matching)
                if (fieldsBySources.size == 1) {
                    val (sourcesGroup, fieldsGroup) = fieldsBySources.entries.single()
                    val merged = primaryGrant.copy(fields = fieldsGroup, sources = sourcesGroup)
                    val day = OwnerDayAssembler.assembleForGrant(date, observations, merged)
                    planDayForRecipient(merged, contributingIds, r, day, ledger, nowMs, out)
                } else {
                    val combinedFields = LinkedHashMap<Field, JsonElement>()
                    var maxMeasuredAt: Long? = null
                    for ((sourcesGroup, fieldsGroup) in fieldsBySources) {
                        val subGrant = primaryGrant.copy(fields = fieldsGroup, sources = sourcesGroup)
                        val subDay = OwnerDayAssembler.assembleForGrant(date, observations, subGrant)
                        val subProj = Projector.project(subGrant, subDay, nowMs) ?: continue
                        combinedFields.putAll(subProj.fields)
                        val ts = subProj.measuredAtUtcMs
                        if (ts != null && (maxMeasuredAt == null || ts > maxMeasuredAt)) {
                            maxMeasuredAt = ts
                        }
                    }
                    if (combinedFields.isEmpty()) continue
                    val orderedFields = LinkedHashMap<Field, JsonElement>()
                    for (f in Field.entries) {
                        combinedFields[f]?.let { orderedFields[f] = it }
                    }
                    val projection = SharedProjection(
                        ownerId = primaryGrant.ownerId,
                        grantId = primaryGrant.id,
                        date = date.toString(),
                        fields = orderedFields,
                        measuredAtUtcMs = maxMeasuredAt,
                        producedAtMs = nowMs,
                    )
                    enqueueProjectionIfChanged(projection, primaryGrant.id, contributingIds, r, date, ledger, nowMs, out)
                }
            }
        }
        return out
    }

    private fun groupFieldsByEffectiveSources(matching: List<ShareGrant>): Map<Set<Provider>?, Set<Field>> {
        val sourcesByField = LinkedHashMap<Field, Set<Provider>?>()
        for (g in matching) {
            for (f in g.fields) {
                if (f !in sourcesByField) {
                    sourcesByField[f] = g.sources
                } else {
                    val existing = sourcesByField[f]
                    sourcesByField[f] = if (existing == null || g.sources == null) null else existing + g.sources!!
                }
            }
        }
        val fieldsBySources = LinkedHashMap<Set<Provider>?, MutableSet<Field>>()
        for ((field, sources) in sourcesByField) {
            fieldsBySources.getOrPut(sources) { LinkedHashSet() }.add(field)
        }
        return fieldsBySources
    }

    private fun mergeGrantsForRecipient(matching: List<ShareGrant>): ShareGrant {
        if (matching.size == 1) return matching.first()
        val mergedFields = matching.flatMap { it.fields }.toSet()
        val mergedSources = if (matching.any { it.sources == null }) null else {
            matching.flatMap { it.sources.orEmpty() }.toSet()
        }
        return matching.first().copy(fields = mergedFields, sources = mergedSources)
    }

    private fun planDayForRecipient(
        g: ShareGrant,
        grantIds: List<String>,
        recipient: String,
        day: OwnerDay,
        ledger: SentLedger,
        nowMs: Long,
        out: MutableList<PlannedSend>,
    ) {
        val projection = Projector.project(g, day, nowMs) ?: return
        enqueueProjectionIfChanged(projection, g.id, grantIds, recipient, day.date, ledger, nowMs, out)
    }

    private fun enqueueProjectionIfChanged(
        projection: SharedProjection,
        grantId: String,
        grantIds: List<String>,
        recipient: String,
        date: LocalDate,
        ledger: SentLedger,
        nowMs: Long,
        out: MutableList<PlannedSend>,
    ) {
        // Hash bez času vzniku: stejná data = stejný obsah, nic se neposílá znovu.
        val hash = Wire.sha256Hex(Projector.encode(projection.copy(producedAtMs = 0)))
        val key = "daily/$date"
        val payload = String(Projector.encode(projection), Charsets.UTF_8)
        val last = ledger.last(recipient, key)
        if (last != null && last.contentHash == hash) return
        out += PlannedSend(
            builder.build(
                recipient = recipient, kind = Kinds.PROJECTION_UPSERT, objectType = "daily", objectId = date.toString(),
                objectVersion = (last?.version ?: 0) + 1, payload = payload, nowMs = nowMs, grantRef = grantId,
            ),
            grantId, key, hash, grantIds,
        )
    }
}
