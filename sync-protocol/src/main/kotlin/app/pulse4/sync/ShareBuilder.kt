package app.pulse4.sync

import app.pulse4.sharing.Group
import app.pulse4.sharing.MembershipPolicy
import app.pulse4.sharing.OwnerDay
import app.pulse4.sharing.Projector
import app.pulse4.sharing.ShareGrant

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
        val out = mutableListOf<PlannedSend>()
        for (g in grants.filter { it.active && it.ownerId == selfId }) {
            val recipients = MembershipPolicy.effectiveRecipients(g, g.groupId?.let(groups::get)).sorted()
            for (day in days) {
                val projection = Projector.project(g, day, nowMs) ?: continue
                // Hash bez času vzniku: stejná data = stejný obsah, nic se neposílá znovu.
                val hash = Wire.sha256Hex(Projector.encode(projection.copy(producedAtMs = 0)))
                val key = "daily/${day.date}"
                val payload = String(Projector.encode(projection), Charsets.UTF_8)
                for (r in recipients) {
                    val last = ledger.last(r, key)
                    if (last != null && last.contentHash == hash) continue
                    out += PlannedSend(
                        builder.build(
                            recipient = r, kind = Kinds.PROJECTION_UPSERT, objectType = "daily", objectId = day.date.toString(),
                            objectVersion = (last?.version ?: 0) + 1, payload = payload, nowMs = nowMs, grantRef = g.id,
                        ),
                        g.id, key, hash,
                    )
                }
            }
        }
        return out
    }
}
