package app.pulse4.sync

import app.pulse4.domain.MetricObservation
import app.pulse4.sharing.Group
import app.pulse4.sharing.MembershipPolicy
import app.pulse4.sharing.OwnerDay
import app.pulse4.sharing.OwnerDayAssembler
import app.pulse4.sharing.Projector
import app.pulse4.sharing.ShareGrant
import java.time.LocalDate

data class SentRecord(val version: Long, val contentHash: String)

/** Co jsme kterému příjemci naposledy odeslali (objekt = `daily/<datum>`). */
interface SentLedger {
    fun last(recipient: String, objectKey: String): SentRecord?
}

data class PlannedSend(val message: WireMessage, val grantId: String, val objectKey: String, val contentHash: String)

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
                planDayForRecipient(merged, r, day, ledger, nowMs, out)
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
                val merged = mergeGrantsForRecipient(matching)
                val day = OwnerDayAssembler.assembleForGrant(date, observations, merged)
                planDayForRecipient(merged, r, day, ledger, nowMs, out)
            }
        }
        return out
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
        recipient: String,
        day: OwnerDay,
        ledger: SentLedger,
        nowMs: Long,
        out: MutableList<PlannedSend>,
    ) {
        val projection = Projector.project(g, day, nowMs) ?: return
        // Hash bez času vzniku: stejná data = stejný obsah, nic se neposílá znovu.
        val hash = Wire.sha256Hex(Projector.encode(projection.copy(producedAtMs = 0)))
        val key = "daily/${day.date}"
        val payload = String(Projector.encode(projection), Charsets.UTF_8)
        val last = ledger.last(recipient, key)
        if (last != null && last.contentHash == hash) return
        out += PlannedSend(
            builder.build(
                recipient = recipient, kind = Kinds.PROJECTION_UPSERT, objectType = "daily", objectId = day.date.toString(),
                objectVersion = (last?.version ?: 0) + 1, payload = payload, nowMs = nowMs, grantRef = g.id,
            ),
            g.id, key, hash,
        )
    }
}
