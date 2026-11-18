package app.pulse4.sync

import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.sharing.Field
import app.pulse4.sharing.Group
import app.pulse4.sharing.OwnerDayAssembler
import app.pulse4.sharing.Presets
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.TimeScope
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareBuilderTest {
    private val d = LocalDate.of(2026, 10, 20)
    private fun obs(t: MetricType, v: Double, ts: Long, id: String = "$t$ts", date: String = d.toString()) =
        MetricObservation(id, Provider.GARMIN_PERSONAL, "a", id, t, ts, null, v, "Europe/Prague", date)

    private val observations = listOf(
        obs(MetricType.SLEEP_DURATION, 20_000.0, 1), obs(MetricType.SLEEP_DURATION, 3_000.0, 2), obs(MetricType.SLEEP_SCORE, 81.0, 3),
        obs(MetricType.BODY_BATTERY, 30.0, 10), obs(MetricType.BODY_BATTERY, 90.0, 20), obs(MetricType.BODY_BATTERY, 55.0, 30),
        obs(MetricType.HRV, 40.0, 5), obs(MetricType.HRV, 60.0, 6), obs(MetricType.RESTING_HEART_RATE, 48.0, 7),
        obs(MetricType.WEIGHT, 80.0, 8), obs(MetricType.SLEEP_DURATION, 999.0, 9, date = "2026-10-19"),
    ).let { it + obs(MetricType.HEART_RATE, 70.0, 4).copy(deleted = true) }

    private val ledger = object : SentLedger {
        val m = HashMap<String, SentRecord>()
        override fun last(recipient: String, objectKey: String) = m["$recipient|$objectKey"]
    }
    private fun grant(fields: Set<Field>, recipients: Set<String> = setOf("bob", "eva")) =
        ShareGrant("g1", "josef", recipients, fields, scope = TimeScope.from(LocalDate.of(2026, 10, 1)), createdAtMs = 1)

    @Test fun `assembler aggregates per local day and never invents missing fields`() {
        val day = OwnerDayAssembler.assemble(d, observations)
        assertEquals("23000.0", day.values.getValue(Field.SLEEP_DURATION).toString())
        assertEquals("55.0", day.values.getValue(Field.BB_LAST).toString())
        assertEquals("""{"min":30.0,"max":90.0}""", day.values.getValue(Field.BB_MIN_MAX).toString())
        assertEquals("50.0", day.values.getValue(Field.HRV_DAILY).toString())
        assertFalse(Field.SLEEP_STAGES in day.values); assertFalse(Field.HR_SERIES in day.values, "smazané měření se nepoužije")
        assertTrue(OwnerDayAssembler.assemble(LocalDate.of(2026, 1, 1), observations).values.isEmpty())
    }

    @Test fun `shared weight is the deduplicated trend value and never an ambiguous measurement`() {
        val t = 1_790_000_000_000L
        fun w(p: Provider, v: Double, ts: Long, q: app.pulse4.domain.Quality = app.pulse4.domain.Quality.OK) =
            MetricObservation("$p$ts", p, "a", "$p$ts", MetricType.WEIGHT, ts, null, v, "Europe/Prague", d.toString(), q)
        val list = listOf(w(Provider.GARMIN_PERSONAL, 80.1, t + 60_000), w(Provider.WITHINGS_API, 80.1, t), w(Provider.WITHINGS_API, 112.0, t + 7_200_000, app.pulse4.domain.Quality.SUSPECT))
        val day = OwnerDayAssembler.assemble(d, list)
        assertEquals("80.1", day.values.getValue(Field.WEIGHT).toString()) // jedno vážení, ne dvě; cizí 112 kg se nesdílí
        val only = OwnerDayAssembler.assemble(d, listOf(w(Provider.WITHINGS_API, 112.0, t, app.pulse4.domain.Quality.SUSPECT)))
        assertFalse(Field.WEIGHT in only.values)
    }

    @Test fun `one projection per recipient with only granted fields`() {
        val plan = ShareBuilder("josef", MessageBuilder("josef") { java.util.UUID.randomUUID().toString() })
            .plan(listOf(grant(Presets.sleepDurationOnly)), emptyMap(), listOf(OwnerDayAssembler.assemble(d, observations)), ledger, 100)
        assertEquals(listOf("bob", "eva"), plan.map { it.message.envelope.recipient })
        plan.forEach {
            assertEquals(1L, it.message.envelope.objectVersion); assertEquals("daily/2026-10-20", it.objectKey)
            assertTrue(it.message.payload.contains("SLEEP_DURATION"))
            listOf("BB_", "HRV", "HR_", "SLEEP_SCORE", "WEIGHT").forEach { f -> assertFalse(it.message.payload.contains(f), f) }
        }
    }

    @Test fun `unchanged data is not resent, changed data gets the next version`() {
        val sb = ShareBuilder("josef", MessageBuilder("josef") { java.util.UUID.randomUUID().toString() })
        val g = listOf(grant(Presets.sleepDurationAndScore, setOf("bob")))
        val first = sb.plan(g, emptyMap(), listOf(OwnerDayAssembler.assemble(d, observations)), ledger, 100).single()
        ledger.m["bob|daily/2026-10-20"] = SentRecord(first.message.envelope.objectVersion, first.contentHash)
        assertEquals(emptyList(), sb.plan(g, emptyMap(), listOf(OwnerDayAssembler.assemble(d, observations)), ledger, 9999), "jiný čas vzniku nesmí vyvolat znovuodeslání")
        val changed = observations + obs(MetricType.SLEEP_DURATION, 100.0, 50)
        val again = sb.plan(g, emptyMap(), listOf(OwnerDayAssembler.assemble(d, changed)), ledger, 200).single()
        assertEquals(2L, again.message.envelope.objectVersion)
    }

    @Test fun `revoked grants, foreign grants and removed group members produce nothing`() {
        val sb = ShareBuilder("josef", MessageBuilder("josef") { java.util.UUID.randomUUID().toString() })
        val days = listOf(OwnerDayAssembler.assemble(d, observations))
        assertEquals(emptyList(), sb.plan(listOf(grant(Presets.sleepFull).copy(revokedAtMs = 5)), emptyMap(), days, ledger, 1))
        assertEquals(emptyList(), sb.plan(listOf(grant(Presets.sleepFull).copy(ownerId = "bob")), emptyMap(), days, ledger, 1))
        val g = grant(Presets.sleepDurationOnly, setOf("bob", "eva")).copy(groupId = "grp")
        val group = Group("grp", "V", "josef", "josef", setOf("josef", "bob"), 3)
        assertEquals(listOf("bob"), sb.plan(listOf(g), mapOf("grp" to group), days, ledger, 1).map { it.message.envelope.recipient })
        assertNull(sb.plan(listOf(g.copy(scope = TimeScope.continuousFrom(LocalDate.of(2026, 10, 21)))), mapOf("grp" to group), days, ledger, 1).firstOrNull())
    }

    @Test fun `S4 two grants for same recipient merge into one projection and do not resend`() {
        val sb = ShareBuilder("josef", MessageBuilder("josef") { java.util.UUID.randomUUID().toString() })
        val g1 = grant(setOf(Field.SLEEP_DURATION), setOf("bob")).copy(id = "g1")
        val g2 = grant(setOf(Field.HR_DAILY), setOf("bob")).copy(id = "g2")
        val first = sb.plan(listOf(g1, g2), emptyMap(), listOf(d), observations, ledger, 100)
        assertEquals(1, first.size)
        val sent = first.single()
        assertTrue(sent.message.payload.contains("SLEEP_DURATION"))
        assertTrue(sent.message.payload.contains("HR_DAILY"))

        ledger.m["bob|${sent.objectKey}"] = SentRecord(sent.message.envelope.objectVersion, sent.contentHash)
        val second = sb.plan(listOf(g1, g2), emptyMap(), listOf(d), observations, ledger, 200)
        assertEquals(emptyList(), second)
    }
}
