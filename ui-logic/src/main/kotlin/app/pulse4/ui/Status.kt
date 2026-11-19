package app.pulse4.ui

import app.pulse4.provider.AreaState
import app.pulse4.provider.AreaStatus
import app.pulse4.provider.Freshness
import app.pulse4.provider.SyncArea
import app.pulse4.sync.DeliveryState

class AreaRow(val label: String, val status: AreaStatus, val lastSuccessMs: Long?, val lastAttemptMs: Long?, val error: String?)

class SyncOverview(val garmin: List<AreaRow>, val allFresh: Boolean, val lastSuccessMs: Long?, val weightSources: List<AreaRow>)

object Status {
    /**
     * Stavy jsou uložené pod klíčem `[prefix.]OBLAST`: Garmin bez prefixu, `withings.` a `hc.` jsou zdroje váhy.
     * „Vše aktuální“ platí jen tehdy, když je v pořádku každá garminovská oblast; chyba spánku nesmí vytvořit zelený stav.
     */
    fun overview(rows: Map<String, AreaState>, nowMs: Long): SyncOverview {
        val garminStates = rows.filterKeys { '.' !in it }.mapNotNull { (k, v) -> runCatching { SyncArea.valueOf(k) }.getOrNull()?.let { it to v } }.toMap()
        val ov = Freshness.overview(garminStates, nowMs)
        val garmin = SyncArea.entries.map { a -> val s = garminStates[a] ?: AreaState(); AreaRow(a.name, ov.perArea.getValue(a), s.lastSuccessMs, s.lastAttemptMs, s.lastError) }
        val sources = listOf("withings." to "Withings API", "hc." to "Health Connect").map { (prefix, label) ->
            val s = rows[prefix + SyncArea.WEIGHT.name] ?: AreaState()
            AreaRow(label, Freshness.statusOf(SyncArea.WEIGHT, s, nowMs), s.lastSuccessMs, s.lastAttemptMs, s.lastError)
        }
        return SyncOverview(garmin, ov.allFresh, ov.lastSuccessMs, sources)
    }

    /** Platnost osobního API klíče (30/90/180/365 dní): uživatel musí vidět blížící se expiraci. */
    class KeyExpiry(val text: String, val warn: Boolean)

    fun keyExpiry(expiresAtMs: Long?, nowMs: Long): KeyExpiry? {
        if (expiresAtMs == null) return null
        val days = Math.floorDiv(expiresAtMs - nowMs, 86_400_000L)
        return when {
            expiresAtMs <= nowMs -> KeyExpiry("Klíč vypršel – vytvořte nový.", true)
            days < 7 -> KeyExpiry("Klíč vyprší za ${days} dní – brzy vytvořte nový.", true)
            else -> KeyExpiry("Klíč vyprší za $days dní", false)
        }
    }

    class OutboxLine(val title: String, val state: String, val detail: String?)

    fun outboxLine(kind: String, objectKey: String, recipient: String, state: DeliveryState, attempts: Int, lastError: String?) =
        OutboxLine("$kind · $objectKey → ${recipient.take(8)}", Labels.delivery(state), if (attempts > 0 || lastError != null) "pokusů $attempts" + (lastError?.let { " · ${Labels.deliveryError(it)}" } ?: "") else null)

    /** Počty ve frontě pro diagnostiku (bez obsahu zpráv). */
    fun queueSummary(states: List<DeliveryState>) =
        "${states.count { it == DeliveryState.QUEUED }} čeká, ${states.count { it == DeliveryState.ACKED }} potvrzeno"
}
