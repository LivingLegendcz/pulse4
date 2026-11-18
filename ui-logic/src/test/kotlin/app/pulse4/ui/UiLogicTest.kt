package app.pulse4.ui

import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Reading
import app.pulse4.domain.Sport
import app.pulse4.domain.TrackPoint
import app.pulse4.provider.AreaResult
import app.pulse4.provider.AreaState
import app.pulse4.provider.AreaStatus
import app.pulse4.provider.ProviderError
import app.pulse4.provider.RetryAction
import app.pulse4.provider.SyncArea
import app.pulse4.sharing.Field
import app.pulse4.sharing.OwnerDay
import app.pulse4.sharing.Presets
import app.pulse4.sharing.RevocationLimit
import app.pulse4.sync.DeliveryState
import app.pulse4.training.DurationSpec
import app.pulse4.training.ExportLevel
import app.pulse4.training.StepType
import app.pulse4.training.TargetSpec
import app.pulse4.training.WorkoutNode
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val F = Fmt(ZoneId.of("Europe/Prague"))
private val TODAY = LocalDate.of(2026, 10, 20)

private fun obs(t: MetricType, v: Double, ts: Long, date: String = TODAY.toString(), end: Long? = null) =
    MetricObservation("$t$ts$date", Provider.GARMIN_PERSONAL, "a", "$t$ts$date", t, ts, end, v, "Europe/Prague", date)

class FormatLabelsTest {
    @Test fun `czech formatting, safe fallbacks`() {
        assertEquals("5,23 km", F.km(5230.0)); assertEquals("—", F.km(null)); assertEquals("—", F.km(Double.NaN))
        assertEquals("1:02:05", F.duration(3725.0)); assertEquals("7 h 30 min", F.sleep(27_000.0))
        assertEquals("5:30 /km", F.pace(330.0)); assertEquals("—", F.pace(0.0)); assertEquals("—", F.pace(null))
        assertEquals("80,1", F.number(80.12, 1))
        val t = java.time.Instant.parse("2026-10-20T05:07:00Z").toEpochMilli()
        assertEquals("20. 10. 07:07", F.time(t)); assertEquals("—", F.time(null))
    }

    @Test fun `age text`() {
        val now = 1_000_000_000L
        assertEquals("právě teď", F.age(now, now - 10_000)); assertEquals("před 5 min", F.age(now, now - 300_000))
        assertEquals("před 3 h", F.age(now, now - 3 * 3_600_000L)); assertEquals("před 2 d", F.age(now, now - 2 * 86_400_000L))
        assertEquals("—", F.age(now, null)); assertTrue(F.age(now, now + 5000).contains("budoucnu"))
    }

    @Test fun `only acked means confirmed by the recipient and every state has its own text`() {
        val texts = DeliveryState.entries.associateWith(Labels::delivery)
        assertEquals(DeliveryState.entries.size, texts.values.toSet().size)
        assertEquals(listOf(DeliveryState.ACKED), texts.filterValues { it.startsWith("Potvrzeno") }.keys.toList())
        assertTrue(Labels.delivery(DeliveryState.STORED_IN_PROPAGATION).contains("schránce"))
        assertFalse(Labels.delivery(DeliveryState.HANDED_TO_TRANSPORT).startsWith("Potvrzeno"))
    }

    @Test fun `device verification is claimed only with evidence`() {
        assertFalse(Labels.export(ExportLevel.DEVICE_REQUESTED).contains("ověřeno ") && !Labels.export(ExportLevel.DEVICE_REQUESTED).contains("není ověřeno"))
        assertEquals("Přijetí hodinkami ověřeno", Labels.export(ExportLevel.DEVICE_VERIFIED))
    }

    @Test fun `errors are explained as what they are`() {
        assertTrue(Labels.error(ProviderError.NotConfigured("endpoint X")).contains("endpoint X"))
        assertFalse(Labels.error(ProviderError.NotConfigured("x")).contains("serveru"))
        assertTrue(Labels.error(ProviderError.RateLimited(90)).contains("90"))
        assertTrue(Labels.error(ProviderError.Timeout()).contains("nejist"))
        assertEquals("Nespuštěno", Labels.result(null)); assertTrue(Labels.result(AreaResult.Success(3)).contains("3"))
        assertTrue(Labels.result(AreaResult.NeedsUser(RetryAction.NeedNewApiKey)).contains("klíč"))
        listOf(RetryAction.RefreshTokenOnce, RetryAction.NeedNewApiKey, RetryAction.WaitAndRetry(5000), RetryAction.ShowForbidden, RetryAction.PauseForMfa, RetryAction.ShowNetworkUnavailable, RetryAction.ShowNotConfigured)
            .forEach { assertTrue(Labels.action(it).isNotBlank()) }
    }

    @Test fun `revocation text lists exactly the limits that apply, never promises recall`() {
        val all = Labels.revocation(RevocationLimit.entries)
        assertEquals(3, all.size); assertTrue(all[0].contains("nelze spolehlivě vzít zpět"))
        assertEquals(1, Labels.revocation(setOf(RevocationLimit.OFFLINE_RECIPIENT_LEARNS_LATER)).size)
        assertEquals(emptyList(), Labels.revocation(emptySet<RevocationLimit>()))
        assertTrue(app.pulse4.sharing.Field.entries.all { Labels.field.containsKey(it) }, "každé pole má český popis")
    }
}

class CardsTest {
    @Test fun `card states differ - not synced, device does not measure, value`() {
        val synced = mapOf<SyncArea, Long?>(SyncArea.SLEEP to 5_000L)
        val cards = TodayCards.build(listOf(obs(MetricType.SLEEP_DURATION, 27_000.0, 1000, end = 30_000)), synced, F).associateBy { it.key }
        assertIs<Reading.Value<String>>(cards.getValue("sleep").reading)
        assertEquals("7 h 30 min", (cards.getValue("sleep").reading as Reading.Value).value)
        assertEquals(30_000L, (cards.getValue("sleep").reading as Reading.Value).measuredAtUtcMs)
        assertEquals(5_000L, (cards.getValue("sleep").reading as Reading.Value).fetchedAtUtcMs)
        assertEquals(Reading.NotSyncedYet, cards.getValue("hrv").reading, "oblast nikdy nesynchronizována")
        val both = TodayCards.build(emptyList(), mapOf(SyncArea.HRV to 1L), F).associateBy { it.key }
        assertEquals(Reading.DeviceDoesNotMeasure, both.getValue("hrv").reading)
    }

    @Test fun `a real zero is a value, not a missing state`() {
        val c = TodayCards.build(listOf(obs(MetricType.STEPS, 0.0, 1)), mapOf(SyncArea.STEPS_STRESS to 2L), F).first { it.key == "steps" }
        assertEquals("0", (c.reading as Reading.Value).value)
    }

    @Test fun `steps and sleep add up, body battery is the last sample, deleted samples are ignored`() {
        val day = listOf(
            obs(MetricType.STEPS, 3000.0, 1), obs(MetricType.STEPS, 4500.0, 2),
            obs(MetricType.SLEEP_DURATION, 20_000.0, 1), obs(MetricType.SLEEP_DURATION, 3_000.0, 2),
            obs(MetricType.BODY_BATTERY, 90.0, 1), obs(MetricType.BODY_BATTERY, 40.0, 5),
            obs(MetricType.HRV, 40.0, 1), obs(MetricType.HRV, 60.0, 2), obs(MetricType.HRV, 999.0, 3).copy(deleted = true),
        )
        val m = TodayCards.build(day, SyncArea.entries.associateWith { 9L }, F)
            .mapNotNull { c -> (c.reading as? Reading.Value)?.let { c.key to it.value } }.toMap()
        assertEquals("7500", m.getValue("steps")); assertEquals("6 h 23 min", m.getValue("sleep"))
        assertEquals("40", m.getValue("bb")); assertEquals("50 ms", m.getValue("hrv"))
        assertFalse("stress" in m, "bez měření není hodnota")
    }
}

class SeriesTest {
    @Test fun `days without measurement are gaps, steps sum, weight takes the last of the day`() {
        val rows = listOf(
            obs(MetricType.STEPS, 1000.0, 1, "2026-10-18"), obs(MetricType.STEPS, 2000.0, 2, "2026-10-18"), obs(MetricType.STEPS, 500.0, 1, "2026-10-20"),
            obs(MetricType.WEIGHT, 81.0, 1, "2026-10-20"), obs(MetricType.WEIGHT, 80.0, 9, "2026-10-20"),
        )
        val steps = MetricSeries.build(MetricType.STEPS, rows, TODAY, 5)
        assertEquals(listOf(null, null, 3000.0, null, 500.0), steps.values); assertEquals(TODAY.minusDays(4), steps.dates.first())
        assertEquals(80.0, MetricSeries.build(MetricType.WEIGHT, rows, TODAY, 3).values.last())
        assertFalse(MetricSeries.build(MetricType.HRV, rows, TODAY, 5).hasData)
    }

    @Test fun `usual range needs at least seven days`() {
        fun rows(n: Int) = (0 until n).map { obs(MetricType.HRV, 40.0 + it, it.toLong(), TODAY.minusDays(it.toLong()).toString()) }
        assertNull(MetricSeries.build(MetricType.HRV, rows(6), TODAY).usual)
        assertNotNull(MetricSeries.build(MetricType.HRV, rows(8), TODAY).usual)
    }
}

class ActivityViewTest {
    /** 0–50 s běh 3 m/s, 50–150 s pauza bez vzorků, 150–200 s dál; bod 20 bez GPS. */
    private val track: List<TrackPoint> = buildList {
        var d = 0.0
        for (s in 0..50 step 5) { add(TrackPoint(s * 1000L, if (s == 20) null else 50.0 + s * 1e-5, if (s == 20) null else 14.0, 200.0 + s / 10.0, 140 + s / 5, 3.0, null, null, d)); d += 15 }
        for (s in 150..200 step 5) { add(TrackPoint(s * 1000L, 50.01 + s * 1e-5, 14.0, 210.0, 150, 3.0, null, null, d)); d += 15 }
    }
    private val v = ActivityView(track)

    @Test fun `time axis shows a pause as a gap and never as zero`() {
        val c = v.chart(Channel.HEART_RATE, Axis.TIME, 40)
        assertEquals(40, c.size)
        assertTrue(c.values.any { it == null }, "pauza je mezera")
        assertTrue(c.values.filterNotNull().all { it >= 140 }, "žádné nuly")
        assertEquals(0.0, c.xStart); assertEquals(200.0, c.xEnd)
    }

    @Test fun `distance axis has no gap because no distance was covered during the pause`() {
        val c = v.chart(Channel.HEART_RATE, Axis.DISTANCE, 20) // 22 bodů na 315 m: každý koš má aspoň jeden bod
        assertTrue(c.values.all { it != null }, "bez mezer")
        assertTrue(c.xEnd > 300)
    }

    @Test fun `chart range maps back to track points for linking with the route`() {
        val c = v.chart(Channel.SPEED, Axis.TIME, 40)
        val r = c.trackRange(0..39)
        assertEquals(0, r.first); assertTrue(r.last >= track.size - 3)
        val mid = c.trackRange(2..4)
        assertTrue(mid.first <= mid.last && mid.last < track.size)
        assertTrue(c.trackRange(39..0 + 0).first >= 0)
        assertEquals(0..0, ActivityView(track.take(2)).chart(Channel.HEART_RATE, Axis.TIME, 2).trackRange(0..0))
    }

    @Test fun `route keeps missing gps as null and the cursor snaps to the nearest positioned point`() {
        val route = v.route(); assertNull(route[4]); assertNotNull(route[3])
        assertEquals(3, v.nearestWithPosition(4)); assertEquals(0, v.nearestWithPosition(-5))
        assertNull(ActivityView(listOf(TrackPoint(0))).nearestWithPosition(0))
    }

    @Test fun `selected interval is summarised separately and moving time differs from total`() {
        val full = v.summary(null, movingOnly = false); val moving = v.summary(null, movingOnly = true)
        assertEquals(200.0, full.timeS); assertEquals(100.0, moving.timeS, 15.0) // pauza se nepočítá do pohybu
        val part = v.summary(0..5, false)
        assertTrue(part.timeS < full.timeS && (part.distanceM ?: 0.0) < (full.distanceM ?: 0.0))
        assertNull(ActivityView(emptyList()).summary(null, true).distanceM)
    }

    @Test fun `point details show dashes for missing values`() {
        val m = v.point(4).toMap()
        assertEquals("bez GPS", m["Poloha"]); assertEquals("—", m["Kadence"]); assertEquals("—", m["Výkon"]); assertEquals("3,0 m/s", m["Rychlost"])
        assertEquals(emptyList(), v.point(999))
        assertTrue(v.hasChannel(Channel.HEART_RATE)); assertFalse(v.hasChannel(Channel.POWER))
    }
}

class EditorTest {
    @Test fun `texts describe steps and repeats`() {
        val r = IntervalForm.build(Sport.RUNNING, "4", "distance", "1000", "120", "270", "285")
        assertEquals("4× [ práce · 1000 m @ 4:30–4:45 /km → zotavení · 2:00 min ]", WorkoutText.describe(r))
        assertEquals("rozcvičení · 10:00 min", WorkoutText.describe(WorkoutNode.Step(StepType.WARMUP, DurationSpec.Time(600))))
        assertEquals("závěr · tlačítko kola", WorkoutText.describe(WorkoutNode.Step(StepType.COOLDOWN, DurationSpec.LapButton)))
    }

    @Test fun `interval form normalises swapped bounds and picks power for cycling`() {
        val swapped = IntervalForm.build(Sport.RUNNING, "4", "time", "60", "30", "300", "270")
        assertEquals(TargetSpec.Pace(270, 300), (swapped.steps[0]).target)
        assertEquals(TargetSpec.Power(220, 250), IntervalForm.build(Sport.CYCLING, "3", "time", "600", "120", "250", "220").steps[0].target)
        assertEquals(TargetSpec.None, IntervalForm.build(Sport.RUNNING, "3", "time", "60", "30", "", "").steps[0].target)
    }

    @Test fun `invalid form input surfaces as a validation problem instead of being hidden`() {
        val d = WorkoutDraft.empty().add(IntervalForm.build(Sport.RUNNING, "1", "time", "abc", "30", "", ""))
        assertFalse(d.valid); assertTrue(d.issues.any { it.path.endsWith("count") }); assertTrue(d.issues.any { it.message.contains("Čas") })
        assertTrue(WorkoutDraft.empty().issues.any { it.path == "nodes" }, "prázdný trénink není platný")
    }

    @Test fun `draft operations are immutable and bounds safe`() {
        val a = StepForm.build(StepType.WARMUP, "time", "600"); val b = StepForm.build(StepType.WORK, "distance", "1000")
        val d0 = WorkoutDraft.empty().rename("Tempo")
        val d = d0.add(a).add(b)
        assertEquals(0, d0.workout.nodes.size); assertEquals(listOf<WorkoutNode>(a, b), d.workout.nodes)
        assertEquals(listOf<WorkoutNode>(b, a), d.move(0, 1).workout.nodes); assertEquals(d, d.move(0, -1)); assertEquals(d, d.move(5, 1))
        assertEquals(listOf<WorkoutNode>(b), d.removeAt(0).workout.nodes); assertEquals(d, d.removeAt(9))
        assertTrue(d.valid)
        assertEquals(0, d.sport(Sport.CYCLING).workout.nodes.size, "změna sportu vymaže kroky"); assertEquals(d, d.sport(Sport.RUNNING))
        assertFalse(StepForm.build(StepType.WORK, "time", "").let { d0.add(it) }.valid)
    }

    @Test fun `date input`() {
        assertEquals(DateInput.Parsed.Ok(LocalDate.of(2026, 11, 2), false), DateInput.parse(" 2026-11-02 ", TODAY))
        assertEquals(DateInput.Parsed.Ok(LocalDate.of(2026, 10, 1), true), DateInput.parse("2026-10-01", TODAY))
        assertEquals(DateInput.Parsed.Invalid, DateInput.parse("2. 11. 2026", TODAY)); assertEquals(DateInput.Parsed.Invalid, DateInput.parse("2026-13-40", TODAY))
    }
}

class SharingViewTest {
    private val day = OwnerDay(TODAY, mapOf(
        Field.SLEEP_DURATION to JsonPrimitive(27_000.0), Field.SLEEP_SCORE to JsonPrimitive(81.0),
        Field.BB_MIN_MAX to buildJsonObject { put("min", 30.0); put("max", 90.0) }, Field.WEIGHT to JsonPrimitive(80.14),
    ))

    @Test fun `received projection is shown with readable values in a stable order`() {
        val payload = String(app.pulse4.sharing.Projector.encode(app.pulse4.sharing.Projector.project(
            app.pulse4.sharing.ShareGrant("g", "o", setOf("r"), setOf(Field.WEIGHT, Field.SLEEP_DURATION, Field.BB_MIN_MAX), scope = app.pulse4.sharing.TimeScope.from(TODAY), createdAtMs = 0), day, 7)!!))
        val v = assertNotNull(ProjectionView.parse(payload, F))
        assertEquals("2026-10-20", v.date)
        assertEquals(listOf("Spánek: délka", "Body Battery: min/max", "Váha (výchozí vypnuto)"), v.rows.map { it.first })
        assertEquals(listOf("7 h 30 min", "30–90", "80,14 kg"), v.rows.map { it.second })
        assertNull(ProjectionView.parse("{nonsense", F))
        assertEquals("—", ProjectionView.value(Field.SLEEP_DURATION, JsonPrimitive("x"), F))
        assertEquals("3 bodů", ProjectionView.value(Field.HR_SERIES, kotlinx.serialization.json.JsonArray(List(3) { JsonPrimitive(it) }), F))
    }
}

class StatusTest {
    private val now = 10 * 86_400_000L
    private fun ok() = AreaState(1, now, now)

    @Test fun `prefixed weight sources do not disturb the garmin overview`() {
        val rows = SyncArea.entries.associate { it.name to ok() } + mapOf("withings.WEIGHT" to ok(), "hc.WEIGHT" to AreaState(null, now - 1000, now, "Network", null))
        val o = Status.overview(rows, now)
        assertTrue(o.allFresh); assertEquals(SyncArea.entries.size, o.garmin.size)
        assertEquals(AreaStatus.OK, o.weightSources[0].status); assertEquals(AreaStatus.ERROR, o.weightSources[1].status); assertEquals("Network", o.weightSources[1].error)
    }

    @Test fun `one failing garmin area breaks the green state and unknown keys are ignored`() {
        val rows = SyncArea.entries.associate { it.name to ok() } + ("SLEEP" to ok().copy(lastError = "Server")) + ("BOGUS" to ok())
        val o = Status.overview(rows, now)
        assertFalse(o.allFresh); assertEquals(AreaStatus.ERROR, o.garmin.first { it.label == "SLEEP" }.status)
        assertEquals(AreaStatus.NEVER, Status.overview(emptyMap(), now).garmin.first().status)
        assertEquals(AreaStatus.NEVER, Status.overview(emptyMap(), now).weightSources[0].status)
    }

    @Test fun `api key expiry warns in the last week and after expiry`() {
        val day = 86_400_000L
        assertNull(Status.keyExpiry(null, now))
        assertEquals(false, Status.keyExpiry(now + 30 * day, now)!!.warn); assertTrue(Status.keyExpiry(now + 30 * day, now)!!.text.contains("30"))
        assertTrue(Status.keyExpiry(now + 3 * day, now)!!.warn); assertTrue(Status.keyExpiry(now - 1, now)!!.text.contains("vypršel"))
    }

    @Test fun `outbox line and queue summary`() {
        val l = Status.outboxLine("projection.upsert", "daily/2026-10-20", "abcdef0123456789", DeliveryState.QUEUED, 2, "Failed")
        assertEquals("projection.upsert · daily/2026-10-20 → abcdef01", l.title); assertEquals("pokusů 2 · Failed", l.detail); assertEquals("Čeká na spojení", l.state)
        assertNull(Status.outboxLine("k", "o", "r", DeliveryState.ACKED, 0, null).detail)
        assertEquals("1 čeká, 2 potvrzeno", Status.queueSummary(listOf(DeliveryState.QUEUED, DeliveryState.ACKED, DeliveryState.ACKED, DeliveryState.CANCELLED)))
    }
}
