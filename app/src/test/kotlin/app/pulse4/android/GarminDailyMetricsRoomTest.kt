package app.pulse4.android

import app.pulse4.android.data.ExcludedRow
import app.pulse4.android.data.ObservationRow
import app.pulse4.android.data.RoomAreaStateStore
import app.pulse4.android.data.toRow
import app.pulse4.android.di.GarminDailyMetrics
import app.pulse4.domain.ImportKey
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.provider.AreaState
import app.pulse4.provider.AreaStateStore
import app.pulse4.provider.SyncArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Obnova RHR/kroků/stresu po 0.2.x: deduplikace klíčů 0.1.x vs. 0.2.x, reset kurzorů a ochrana proti dvojímu započtení kroků. */
class GarminDailyMetricsRoomTest {
    private val acc = "acc-1"
    private val prov = Provider.GARMIN_PERSONAL.name

    private fun oldId(type: MetricType, date: String, tag: String) =
        ImportKey.of(Provider.GARMIN_PERSONAL, acc, null, listOf(type.name, date, tag))

    private fun row(id: String, type: MetricType, date: String, value: Double, start: Long, end: Long? = null, deleted: Boolean = false, provider: String = prov) =
        ObservationRow(id, provider, acc, null, type.name, start, end, value, "UTC", date, "OK", 1, deleted)

    private fun active(db: app.pulse4.android.data.AppDatabase, type: MetricType, date: String) =
        db.observations().forTypeInRange(type.name, date, date).filter { it.provider == prov }

    @Test
    fun `restoreOnce merges old-key and canonical rows, respects tombstones and exclusions, resets cursors once`() = withJdbcRoomDb { db ->
        val steps = MetricType.STEPS
        val rhr = MetricType.RESTING_HEART_RATE
        val d1 = "2026-11-10"; val d2 = "2026-11-11"; val d3 = "2026-11-12"

        // d1: old 0.1.x row (3000 steps) + newer canonical row from 0.2.1 (8000) -> one active row, newest value
        val d1Old = oldId(steps, d1, "old")
        val d1Canon = GarminDailyMetrics.canonicalId(acc, steps, d1)
        // d2: user deleted the canonical day, an old row is still active -> must NOT be resurrected
        val d2Old = oldId(steps, d2, "old")
        val d2Canon = GarminDailyMetrics.canonicalId(acc, steps, d2)
        // d3: user excluded the old row -> exclusion moves to canonical id, nothing active remains
        val d3Old = oldId(steps, d3, "old")
        val d3Canon = GarminDailyMetrics.canonicalId(acc, steps, d3)
        // RHR: only an old row -> converted to canonical in place (history kept)
        val rhrOld = oldId(rhr, d1, "old")
        val rhrCanon = GarminDailyMetrics.canonicalId(acc, rhr, d1)
        // other provider on the same day is never touched
        val hc = row("hc-steps", steps, d1, 1234.0, 1_000L, 2_000L, provider = Provider.HEALTH_CONNECT.name)

        db.observations().upsert(listOf(
            row(d1Old, steps, d1, 3000.0, 100L, null),
            row(d1Canon, steps, d1, 8000.0, 100L, 900L),
            row(d2Canon, steps, d2, 1.0, 200L, 250L, deleted = true),
            row(d2Old, steps, d2, 5000.0, 200L, null),
            row(d3Old, steps, d3, 4000.0, 300L, null, deleted = true),
            row(rhrOld, rhr, d1, 57.0, 100L, 500L),
            hc,
        ))
        db.exclusions().put(ExcludedRow(d3Old, 9_000L))

        val states = RoomAreaStateStore(db)
        for (a in SyncArea.entries) states.put(a, AreaState(cursorMs = 777L, lastSuccessMs = 5L, lastAttemptMs = 5L, lastError = "Partial:STEPS=EmptyResponse", retryNotBeforeMs = 99L))
        val flags = HashMap<String, String>()

        assertTrue(GarminDailyMetrics.restoreOnce(db, states, flags::get, flags::put))
        assertEquals("true", flags[GarminDailyMetrics.RESTORE_FLAG])

        // d1 steps: exactly one active Garmin row = canonical, newest value (no double count)
        val d1Rows = active(db, steps, d1)
        assertEquals(listOf(d1Canon), d1Rows.map { it.id })
        assertEquals(8000.0, d1Rows.single().value)
        // d2: deleted day stays deleted
        assertTrue(active(db, steps, d2).isEmpty())
        // d3: exclusion remapped, no active rows
        assertTrue(active(db, steps, d3).isEmpty())
        val ex = db.exclusions().allIds().toSet()
        assertTrue(d3Canon in ex && d3Old !in ex)
        // RHR history preserved under the canonical id
        val rhrRows = active(db, rhr, d1)
        assertEquals(listOf(rhrCanon), rhrRows.map { it.id })
        assertEquals(57.0, rhrRows.single().value)
        // other provider untouched
        assertEquals(1, db.observations().forTypeInRange(steps.name, d1, d1).count { it.id == "hc-steps" })

        // cursors reset only for the affected areas; error and retry state cleared; others untouched
        for (a in listOf(SyncArea.HEART_RATE, SyncArea.STEPS_STRESS)) {
            val st = states.get(a)
            assertNull(st.cursorMs); assertNull(st.lastError); assertNull(st.retryNotBeforeMs)
            assertEquals(5L, st.lastSuccessMs)
        }
        for (a in listOf(SyncArea.SLEEP, SyncArea.HRV, SyncArea.BODY_BATTERY, SyncArea.WEIGHT)) {
            assertEquals(777L, states.get(a).cursorMs, "$a se nemá resetovat")
        }

        // second call is a no-op (flag): cursors set by later syncs survive
        states.put(SyncArea.STEPS_STRESS, AreaState(cursorMs = 1234L))
        assertFalse(GarminDailyMetrics.restoreOnce(db, states, flags::get, flags::put))
        assertEquals(1234L, states.get(SyncArea.STEPS_STRESS).cursorMs)
    }

    @Test
    fun `restoreOnce does not set the flag when a step fails so it is retried on the next sync`() = withJdbcRoomDb { db ->
        val failing = object : AreaStateStore {
            override fun get(area: SyncArea) = AreaState(cursorMs = 1L)
            override fun put(area: SyncArea, state: AreaState) { throw IllegalStateException("db busy") }
        }
        val flags = HashMap<String, String>()
        assertFalse(GarminDailyMetrics.restoreOnce(db, failing, flags::get, flags::put))
        assertNull(flags[GarminDailyMetrics.RESTORE_FLAG])
    }

    @Test
    fun `supersedeNonCanonical retires old-key Garmin rows of the same day so steps cannot be summed twice`() = withJdbcRoomDb { db ->
        val steps = MetricType.STEPS
        val date = "2026-11-15"
        val old = oldId(steps, date, "old")
        val canon = GarminDailyMetrics.canonicalId(acc, steps, date)
        val otherDay = oldId(steps, "2026-11-14", "old")
        db.observations().upsert(listOf(
            row(old, steps, date, 3000.0, 100L),
            row(otherDay, steps, "2026-11-14", 6000.0, 50L),
            row("hc", steps, date, 999.0, 100L, 200L, provider = Provider.HEALTH_CONNECT.name),
        ))
        val incoming = MetricObservation(
            id = canon, provider = Provider.GARMIN_PERSONAL, accountId = acc, externalId = null, type = steps,
            startUtcMs = 100L, endUtcMs = 900L, value = 8200.0, zoneId = "UTC", localDate = date,
        )
        db.runInTransaction {
            GarminDailyMetrics.supersedeNonCanonical(db, listOf(incoming))
            db.observations().upsert(listOf(incoming.toRow()))
        }
        val rows = active(db, steps, date)
        assertEquals(listOf(canon), rows.map { it.id })
        assertEquals(8200.0, rows.sumOf { it.value }, "denní součet Garminu je právě jedna hodnota")
        assertEquals(listOf(otherDay), active(db, steps, "2026-11-14").map { it.id }, "jiný den se nemění")
        assertEquals(1, db.observations().forTypeInRange(steps.name, date, date).count { it.provider == Provider.HEALTH_CONNECT.name })
    }
}
