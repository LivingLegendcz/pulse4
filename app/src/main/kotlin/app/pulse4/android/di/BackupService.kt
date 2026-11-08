package app.pulse4.android.di

import app.pulse4.android.data.ActivityRow
import app.pulse4.android.data.ObservationRow
import app.pulse4.android.data.toRow
import app.pulse4.backup.BackupCodec
import app.pulse4.sync.Wire
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Záloha = vlastní data, plány a nastavení. Tokeny, osobní API klíč, vývojářská tajemství a soukromá Reticulum
 * identita se NIKDY nepřenášejí. Obnova vytvoří novou identitu a kontakty se potvrzují znovu.
 */
class BackupService(private val sl: ServiceLocator) {
    private val json = Wire.json

    fun export(passphrase: CharArray, sinceDate: String = "0000-01-01", untilDate: String = "9999-12-31"): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        val obs = sl.db.observations().forRange(sinceDate, untilDate)
        entries["observations.json"] = json.encodeToString(ListSerializer(Row.serializer()), obs.map { it.toRowDto() }).toByteArray()
        val acts = sl.db.activities().since(0)
        entries["activities.json"] = json.encodeToString(ListSerializer(String.serializer()), acts.map { it.json }).toByteArray()
        acts.forEach { a -> sl.db.activities().track(a.id)?.let { entries["tracks/${a.id}.json"] = it.json.toByteArray() } }
        entries["plans.json"] = json.encodeToString(ListSerializer(String.serializer()), sl.db.plans().allRevisions().map { it.json }).toByteArray()
        // Sdílení (granty) se nezálohují jako aktivní: po obnově vznikne nová identita a souhlasy se potvrzují znovu.
        entries["manifest.json"] = """{"app":"pulse4","format":${BackupCodec.VERSION},"observations":${obs.size},"activities":${acts.size}}""".toByteArray()
        return BackupCodec.seal(entries, passphrase)
    }

    /** Ověří a rozbalí archiv; vyvolá `BackupException` při špatném hesle nebo poškození. Samotné zapsání řeší volající po potvrzení uživatele. */
    fun open(blob: ByteArray, passphrase: CharArray): Map<String, ByteArray> = BackupCodec.open(blob, passphrase)

    /** Obnoví pozorování, aktivity, trasy a plány. Sdílení ani kontakty ne: nová identita nedědí starou důvěru. */
    fun restore(entries: Map<String, ByteArray>): Int {
        var n = 0
        sl.db.runInTransaction {
            entries["observations.json"]?.let { b ->
                val rows = json.decodeFromString(ListSerializer(Row.serializer()), b.toString(Charsets.UTF_8))
                sl.db.observations().upsert(rows.map { ObservationRow(it.id, it.provider, it.accountId, it.externalId, it.type, it.startUtcMs, it.endUtcMs, it.value, it.zoneId, it.localDate, it.quality, it.sourceVersion, it.deleted) })
                n += rows.size
            }
            entries["activities.json"]?.let { b ->
                json.decodeFromString(ListSerializer(String.serializer()), b.toString(Charsets.UTF_8)).forEach { js ->
                    val a = json.decodeFromString(app.pulse4.domain.Activity.serializer(), js)
                    sl.db.activities().upsert(a.toRow(js)); n++
                }
            }
            entries.filterKeys { it.startsWith("tracks/") }.forEach { (k, v) ->
                sl.db.activities().upsertTrack(app.pulse4.android.data.TrackRow(k.removePrefix("tracks/").removeSuffix(".json"), v.toString(Charsets.UTF_8)))
            }
            entries["plans.json"]?.let { b ->
                json.decodeFromString(ListSerializer(String.serializer()), b.toString(Charsets.UTF_8)).forEach { js ->
                    val r = json.decodeFromString(app.pulse4.training.PlanRevision.serializer(), js)
                    sl.db.plans().restoreRevision(app.pulse4.android.data.PlanRevisionRow(r.planId, r.revision, js)); n++
                }
            }
        }
        return n
    }

    @kotlinx.serialization.Serializable
    data class Row(
        val id: String, val provider: String, val accountId: String, val externalId: String?, val type: String,
        val startUtcMs: Long, val endUtcMs: Long?, val value: Double, val zoneId: String, val localDate: String?,
        val quality: String, val sourceVersion: Int, val deleted: Boolean,
    )

    private fun ObservationRow.toRowDto() = Row(id, provider, accountId, externalId, type, startUtcMs, endUtcMs, value, zoneId, localDate, quality, sourceVersion, deleted)
}
