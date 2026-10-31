package app.pulse4.domain

import java.security.MessageDigest
import kotlin.math.abs

private fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Jedinečnost importu: účet + poskytovatel + externí ID; bez ID deterministický klíč ze stabilních polí. */
object ImportKey {
    fun of(provider: Provider, accountId: String, externalId: String?, stableFields: List<String>): String {
        val tail = externalId?.let { "id:$it" } ?: ("h:" + sha256Hex(stableFields.joinToString("\u001f")))
        return "${provider.name}|$accountId|$tail"
    }
}

/**
 * Propojení možných duplikátů (např. stejné vážení z Withings a Garminu). Nic se nemaže –
 * oba zdroje se uchovají, do trendu vstupuje preferovaný zdroj. Nejasná shoda se nespojuje.
 */
object DuplicateLinker {
    data class Rule(val maxTimeDiffMs: Long, val maxValueDiff: Double)

    val defaultRules: Map<MetricType, Rule> = mapOf(
        MetricType.WEIGHT to Rule(10 * 60_000L, 0.15),
        MetricType.BODY_FAT to Rule(10 * 60_000L, 0.5),
    )

    /** Klastr = skupina pozorování, která jsou s vysokou jistotou totéž měření. */
    data class Cluster(val members: List<MetricObservation>)

    fun cluster(
        observations: List<MetricObservation>,
        rules: Map<MetricType, Rule> = defaultRules,
    ): List<Cluster> {
        val live = observations.filter { !it.deleted }
        val byType = live.groupBy { it.type }
        val out = mutableListOf<Cluster>()
        for ((type, list) in byType) {
            val rule = rules[type]
            if (rule == null) { list.forEach { out += Cluster(listOf(it)) }; continue }
            val sorted = list.sortedBy { it.startUtcMs }
            val used = BooleanArray(sorted.size)
            for (i in sorted.indices) {
                if (used[i]) continue
                used[i] = true
                val members = mutableListOf(sorted[i])
                for (j in i + 1 until sorted.size) {
                    if (used[j]) continue
                    val c = sorted[j]
                    if (c.startUtcMs - sorted[i].startUtcMs > rule.maxTimeDiffMs) break
                    // Stejný poskytovatel+účet nikdy neslučujeme: dvě vážení za sebou jsou dvě vážení.
                    val sameSource = members.any { it.provider == c.provider && it.accountId == c.accountId }
                    if (!sameSource && abs(c.value - sorted[i].value) <= rule.maxValueDiff) {
                        members += c; used[j] = true
                    }
                }
                out += Cluster(members)
            }
        }
        return out.sortedBy { it.members.minOf { m -> m.startUtcMs } }
    }

    /** Jedna hodnota na klastr podle priority zdrojů; ostatní zůstávají dohledatelné v `Cluster.members`. */
    fun selectForTrend(clusters: List<Cluster>, preference: List<Provider>): List<MetricObservation> =
        clusters.map { c ->
            c.members.minByOrNull { m ->
                preference.indexOf(m.provider).let { if (it < 0) Int.MAX_VALUE else it }
            }!!
        }
}

/**
 * Měření, která uživatel označil za chybná (např. vážení cizí osoby). Přežije opakovaný import:
 * vyřazené záznamy se při importu odfiltrují, takže je re-import „neoživí“. Zdrojová data se přitom nemažou.
 */
fun interface MeasurementExclusions { fun isExcluded(observationId: String): Boolean }

object ImportFilter {
    fun apply(items: List<MetricObservation>, ex: MeasurementExclusions): List<MetricObservation> =
        items.map { if (ex.isExcluded(it.id)) it.copy(deleted = true) else it }
}

object TrendSelector {
    /**
     * Body trendu: jedno měření na shluk duplicit podle priority zdrojů. Podezřelá měření (např. nejednoznačná osoba)
     * se do trendu nezahrnují, dokud je uživatel nepotvrdí; zdrojové záznamy zůstávají dohledatelné.
     */
    fun points(
        observations: List<MetricObservation>,
        preference: List<Provider>,
        includeSuspect: Boolean = false,
    ): List<MetricObservation> {
        val usable = observations.filter { !it.deleted && (includeSuspect || it.quality != Quality.SUSPECT) }
        return DuplicateLinker.selectForTrend(DuplicateLinker.cluster(usable), preference)
    }
}

object Reconcile {
    /**
     * Tombstony po opětovném přečtení okna: dosud uložená ID, která zdroj už nevrací. Prázdná odpověď (výpadek,
     * chybný dotaz) nikdy nic nemaže – nejednoznačný stav je bezpečnější než ztráta historie.
     */
    fun tombstones(existingIds: Set<String>, fetchedIds: Set<String>): List<String> =
        if (fetchedIds.isEmpty()) emptyList() else (existingIds - fetchedIds).sorted()
}
