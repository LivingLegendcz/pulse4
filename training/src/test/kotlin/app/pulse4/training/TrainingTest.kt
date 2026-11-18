package app.pulse4.training

import app.pulse4.domain.Sport
import app.pulse4.provider.ProviderError
import app.pulse4.sharing.PlanRight
import app.pulse4.sync.AckSink
import app.pulse4.sync.Kinds
import app.pulse4.sync.MessageBuilder
import app.pulse4.sync.Outcome
import app.pulse4.sync.Receiver
import app.pulse4.sync.RejectReason
import app.pulse4.sync.BuiltInHandlers
import app.pulse4.sync.Wire
import app.pulse4.sync.Envelope
import app.pulse4.sync.ReceiverStore
import app.pulse4.sharing.Group
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun run1(name: String = "Tempový běh") = Workout(
    "w1", name, Sport.RUNNING,
    listOf(
        WorkoutNode.Step(StepType.WARMUP, DurationSpec.Time(600)),
        WorkoutNode.Repeat(4, listOf(
            WorkoutNode.Step(StepType.WORK, DurationSpec.Distance(1000), TargetSpec.Pace(270, 285)),
            WorkoutNode.Step(StepType.RECOVERY, DurationSpec.Time(120)),
        )),
        WorkoutNode.Step(StepType.COOLDOWN, DurationSpec.LapButton),
    ),
    "josef",
)

private fun sw(id: String, w: Workout = run1().copy(id = id), date: String = "2026-11-02") = ScheduledWorkout(id, w, "josef", date)

class WorkoutTest {
    @Test fun `valid running and cycling workouts pass, flat steps unroll repeats`() {
        assertEquals(emptyList(), WorkoutValidator.validate(run1()))
        assertEquals(1 + 8 + 1, run1().flatSteps().size)
        val bike = Workout("b", "Prahy", Sport.CYCLING, listOf(WorkoutNode.Step(StepType.WORK, DurationSpec.Time(1200), TargetSpec.Power(220, 250))), "josef")
        assertEquals(emptyList(), WorkoutValidator.validate(bike))
    }

    @Test fun `unsupported targets sports and bad ranges are explained before export`() {
        val power = run1().copy(nodes = listOf(WorkoutNode.Step(StepType.WORK, DurationSpec.Time(60), TargetSpec.Power(200, 250))))
        assertTrue(WorkoutValidator.validate(power).any { it.path.endsWith("target") })
        val swim = run1().copy(sport = Sport.SWIMMING)
        assertTrue(WorkoutValidator.validate(swim).any { it.path == "sport" })
        val inverted = run1().copy(nodes = listOf(WorkoutNode.Step(StepType.WORK, DurationSpec.Time(60), TargetSpec.HeartRate(170, 150))))
        assertTrue(WorkoutValidator.validate(inverted).isNotEmpty())
        val rep = run1().copy(nodes = listOf(WorkoutNode.Repeat(1, listOf(WorkoutNode.Step(StepType.WORK, DurationSpec.Time(5))))))
        assertTrue(WorkoutValidator.validate(rep).any { it.path.endsWith("count") })
        val many = run1().copy(nodes = listOf(WorkoutNode.Repeat(99, listOf(WorkoutNode.Step(StepType.WORK, DurationSpec.Time(5))))))
        assertTrue(WorkoutValidator.validate(many).any { it.message.contains("kroků") })
    }

    @Test fun `R1 negative or huge repeat count does not crash or OOM validator`() {
        val neg = run1().copy(nodes = listOf(WorkoutNode.Repeat(-1, listOf(WorkoutNode.Step(StepType.WORK, DurationSpec.Time(5))))))
        assertTrue(WorkoutValidator.validate(neg).any { it.path.endsWith("count") })
        val huge = run1().copy(nodes = listOf(WorkoutNode.Repeat(Int.MAX_VALUE, listOf(WorkoutNode.Step(StepType.WORK, DurationSpec.Time(5))))))
        assertTrue(WorkoutValidator.validate(huge).any { it.path.endsWith("count") })
    }

    @Test fun `signature ignores name but not structure`() {
        assertEquals(run1().signature(), run1("Jiný název").signature())
        val changed = run1().copy(nodes = run1().nodes.dropLast(1))
        assertNotEquals(run1().signature(), changed.signature())
    }

    @Test fun `workout serialization roundtrips the sealed hierarchy`() {
        val s = Wire.json.encodeToString(Workout.serializer(), run1())
        assertEquals(run1(), Wire.json.decodeFromString(Workout.serializer(), s))
    }
}

class PlanTest {
    private val head = PlanRevision("p1", "josef", 1, null, "josef", 0, listOf(sw("a"), sw("b")))
    private fun store() = InMemoryPlanStore(head)
    private fun prop(id: String, who: String, base: Long, vararg ops: PlanOp) = ChangeProposal(id, "p1", base, who, ops.toList(), 1)

    @Test fun `viewer and copy right cannot change, no right is rejected`() {
        val st = store(); val svc = PlanService(st)
        st.grant("p1", "eva", PlanRight.VIEW); st.grant("p1", "tom", PlanRight.COPY)
        for (who in listOf("eva", "tom", "nobody"))
            assertIs<SubmitResult.Rejected>(svc.submit(prop("x$who", who, 1, PlanOp.Remove("a")), 5))
        assertEquals(1, st.head("p1")!!.revision)
    }

    @Test fun `propose creates pending, owner accept makes an immutable new revision`() {
        val st = store(); val svc = PlanService(st); st.grant("p1", "eva", PlanRight.PROPOSE)
        val r = svc.submit(prop("c1", "eva", 1, PlanOp.Replace(sw("a", run1().copy(id = "a", name = "Lehčí"), "2026-11-03"))), 5)
        assertIs<SubmitResult.Pending>(r)
        assertEquals(1, st.head("p1")!!.revision, "návrh nic nemění do přijetí")
        val acc = assertIs<SubmitResult.Accepted>(svc.decide("c1", true, 6, "josef"))
        assertEquals(2, acc.revision.revision); assertEquals(1L, acc.revision.parent)
        assertEquals(1, st.revisions("p1").first().revision); assertEquals("2026-11-02", st.revisions("p1").first().workouts[0].date) // stará revize beze změny
        assertIs<SubmitResult.Rejected>(svc.decide("c1", true, 7, "josef")) // už vyřízeno
    }

    @Test fun `only the owner decides`() {
        val st = store(); val svc = PlanService(st); st.grant("p1", "eva", PlanRight.PROPOSE)
        svc.submit(prop("c1", "eva", 1, PlanOp.Remove("a")), 5)
        assertIs<SubmitResult.Rejected>(svc.decide("c1", true, 6, "eva"))
    }

    @Test fun `edit right applies directly from current head but same base twice is a visible conflict`() {
        val st = store(); val svc = PlanService(st)
        st.grant("p1", "eva", PlanRight.EDIT); st.grant("p1", "tom", PlanRight.EDIT)
        assertIs<SubmitResult.Accepted>(svc.submit(prop("c1", "eva", 1, PlanOp.Replace(sw("a", date = "2026-11-05"))), 5))
        val c = assertIs<SubmitResult.Conflict>(svc.submit(prop("c2", "tom", 1, PlanOp.Replace(sw("a", date = "2026-11-06"))), 6))
        assertEquals(setOf("a"), c.diff.overlap)
        assertEquals(2, st.head("p1")!!.revision, "nic se tiše nepřepsalo")
        // owner nemůže konflikt s překryvem přijmout naslepo
        assertIs<SubmitResult.Conflict>(svc.decide("c2", true, 7, "josef"))
        assertEquals("2026-11-05", st.head("p1")!!.workouts.first { it.id == "a" }.date)
    }

    @Test fun `non overlapping stale proposal can be carried over only on explicit owner decision`() {
        val st = store(); val svc = PlanService(st)
        st.grant("p1", "eva", PlanRight.EDIT); st.grant("p1", "tom", PlanRight.EDIT)
        svc.submit(prop("c1", "eva", 1, PlanOp.Replace(sw("a", date = "2026-11-05"))), 5)
        val c = assertIs<SubmitResult.Conflict>(svc.submit(prop("c2", "tom", 1, PlanOp.Remove("b")), 6))
        assertTrue(c.diff.overlap.isEmpty())
        val acc = assertIs<SubmitResult.Accepted>(svc.decide("c2", true, 7, "josef"))
        assertEquals(setOf("a"), acc.revision.workouts.map { it.id }.toSet())
    }

    @Test fun `revoked edit right means an old offline change is not executed`() {
        val st = store(); val svc = PlanService(st); st.grant("p1", "eva", PlanRight.EDIT)
        st.revoke("p1", "eva")
        assertIs<SubmitResult.Rejected>(svc.submit(prop("late", "eva", 1, PlanOp.Remove("a")), 99))
        assertEquals(1, st.head("p1")!!.revision)
    }

    @Test fun `invalid workouts and impossible ops are rejected`() {
        val st = store(); val svc = PlanService(st)
        val bad = sw("z", run1().copy(id = "z", nodes = emptyList()))
        assertIs<SubmitResult.Rejected>(svc.submit(prop("c1", "josef", 1, PlanOp.Add(bad)), 5))
        assertIs<SubmitResult.Rejected>(svc.submit(prop("c2", "josef", 1, PlanOp.Remove("nope")), 5))
        assertIs<SubmitResult.Rejected>(svc.submit(prop("c3", "josef", 1, PlanOp.Add(sw("a"))), 5)) // duplicitní ID
    }

    // --- přes příjem zpráv ---
    private class Store(val contacts: Set<String>) : ReceiverStore {
        val receipts = HashMap<String, String>(); val objs = HashMap<String, Pair<Long, String>>()
        override fun <T> inTransaction(block: () -> T) = block()
        override fun isVerifiedContact(identity: String) = identity in contacts
        override fun receiptHash(eventId: String) = receipts[eventId]
        override fun recordReceipt(eventId: String, contentHash: String, author: String, appliedAtMs: Long) { receipts[eventId] = contentHash }
        override fun objectVersion(author: String, objectType: String, objectId: String) = objs["$author|$objectType|$objectId"]?.first
        override fun putObject(author: String, objectType: String, objectId: String, version: Long, payload: String) { objs["$author|$objectType|$objectId"] = version to payload }
        override fun removeObject(author: String, objectType: String, objectId: String, version: Long) { objs.remove("$author|$objectType|$objectId") }
        override fun group(groupId: String): Group? = null
        override fun putGroup(group: Group) = Unit
    }

    @Test fun `proposal over the wire is authorised by the verified author and answered after commit`() {
        val st = store(); st.grant("p1", "eva", PlanRight.EDIT)
        val handlers = PlanSyncHandlers(PlanService(st))
        val rx = Receiver("josef", Store(setOf("eva", "mallory")), BuiltInHandlers.all() + handlers.handlers(), AckSink { _, _ -> })
        val eva = MessageBuilder("eva") { "e1" }
        val p = prop("c1", "eva", 1, PlanOp.Replace(sw("a", date = "2026-11-09")))
        val msg = eva.build("josef", Kinds.PLAN_PROPOSAL, "plan", "p1", 1, Wire.json.encodeToString(ChangeProposal.serializer(), p), 10)
        assertIs<Outcome.Applied>(rx.receive("eva", Wire.encode(msg), 10))
        assertEquals(2, st.head("p1")!!.revision)
        val note = handlers.drainNotifications().single()
        assertEquals("eva", note.toIdentity); assertEquals(ProposalStatus.ACCEPTED, note.decision.status)
        assertEquals(emptyList(), handlers.drainNotifications())

        // mallory pošle návrh podepsaný jako eva v obsahu, ale sám je odesílatel
        val forged = MessageBuilder("mallory") { "e2" }.build("josef", Kinds.PLAN_PROPOSAL, "plan", "p1", 1, Wire.json.encodeToString(ChangeProposal.serializer(), prop("c9", "eva", 2, PlanOp.Remove("a"))), 11)
        assertEquals(RejectReason.AUTHOR_MISMATCH, assertIs<Outcome.Rejected>(rx.receive("mallory", Wire.encode(forged), 11)).reason)
        // mallory bez práva
        val noRight = MessageBuilder("mallory") { "e3" }.build("josef", Kinds.PLAN_PROPOSAL, "plan", "p1", 1, Wire.json.encodeToString(ChangeProposal.serializer(), prop("c8", "mallory", 2, PlanOp.Remove("a"))), 12)
        assertEquals(RejectReason.NOT_PERMITTED, assertIs<Outcome.Rejected>(rx.receive("mallory", Wire.encode(noRight), 12)).reason)
        assertEquals(2, st.head("p1")!!.revision)
    }

    @Test fun `plan replica accepts revisions only from the owner and never goes backwards`() {
        val handlers = PlanSyncHandlers(PlanService(store()))
        val replica = Store(setOf("josef", "eva"))
        val rx = Receiver("tom", replica, handlers.handlers(), AckSink { _, _ -> })
        fun rev(v: Long, owner: String = "josef") = Wire.json.encodeToString(PlanRevision.serializer(), head.copy(revision = v, ownerId = owner))
        val b = MessageBuilder("josef") { "r${System.nanoTime()}" }
        assertIs<Outcome.Applied>(rx.receive("josef", Wire.encode(b.build("tom", Kinds.PLAN_REVISION, "plan", "p1", 3, rev(3), 1)), 1))
        assertIs<Outcome.Stale>(rx.receive("josef", Wire.encode(b.build("tom", Kinds.PLAN_REVISION, "plan", "p1", 2, rev(2), 2)), 2))
        val evaB = MessageBuilder("eva") { "r${System.nanoTime()}" }
        val o = rx.receive("eva", Wire.encode(evaB.build("tom", Kinds.PLAN_REVISION, "plan", "p1", 9, rev(9, owner = "josef"), 3)), 3)
        assertEquals(RejectReason.NOT_PERMITTED, assertIs<Outcome.Rejected>(o).reason)
        assertEquals(3L, replica.objs.getValue("josef|plan|p1").first)
    }
}

// ----------------------------------------------------------------------------------------------
private class FakeGarmin : GarminWorkoutApi {
    val workouts = LinkedHashMap<String, Pair<String, Workout>>() // id -> (název s markerem, trénink)
    val calendar = mutableListOf<RemoteScheduled>()
    var syncRequests = 0
    private var seq = 0
    var timeoutAfterUpsert = false; var timeoutBeforeUpsert = false
    var timeoutAfterSchedule = false; var timeoutBeforeSchedule = false
    var listFails = false; var calendarFails = false
    var external: ((String) -> RemoteWorkout?)? = null
    var upserts = 0; var schedules = 0

    override fun listWorkouts(): List<RemoteWorkout> { if (listFails) throw ProviderError.Network("down"); return workouts.map { (id, p) -> RemoteWorkout(id, p.first, p.second.signature()) } }
    override fun getWorkout(id: String): RemoteWorkout? = external?.invoke(id) ?: workouts[id]?.let { RemoteWorkout(id, it.first, it.second.signature()) }
    override fun upsertWorkout(w: Workout, marker: String, existingId: String?): String {
        upserts++
        if (timeoutBeforeUpsert) { timeoutBeforeUpsert = false; throw ProviderError.Timeout() }
        val id = existingId ?: "gw${++seq}"
        workouts[id] = "${w.name} $marker" to w
        if (timeoutAfterUpsert) { timeoutAfterUpsert = false; throw ProviderError.Timeout() }
        return id
    }
    override fun listCalendar(date: String): List<RemoteScheduled> { if (calendarFails) throw ProviderError.Network("down"); return calendar.filter { it.date == date } }
    override fun schedule(workoutId: String, date: String): String {
        schedules++
        if (timeoutBeforeSchedule) { timeoutBeforeSchedule = false; throw ProviderError.Timeout() }
        val id = "cal${++seq}"; calendar += RemoteScheduled(id, workoutId, date)
        if (timeoutAfterSchedule) { timeoutAfterSchedule = false; throw ProviderError.Timeout() }
        return id
    }
    override fun requestDeviceSync() { syncRequests++ }
}

class ExportTest {
    private val rec = ExportRecord("abcdef123", "p1", 2, "w1", "2026-11-02")

    @Test fun `happy path reaches device requested but never claims device verified`() {
        val g = FakeGarmin()
        val out = assertIs<ExportOutcome.Finished>(ExportRunner(g).run(rec, run1()))
        assertEquals(ExportLevel.DEVICE_REQUESTED, out.record.level)
        assertEquals(1, g.workouts.size); assertEquals(1, g.calendar.size); assertEquals(1, g.syncRequests)
        assertTrue(g.workouts.values.single().first.contains("[p4-abcdef]"))
        assertEquals(ExportLevel.DEVICE_VERIFIED, out.record.copy(deviceVerifiedEvidence = "hodinky ukázaly trénink").level)
    }

    @Test fun `export state survives serialization so it can be stored and resumed`() {
        val g = FakeGarmin().apply { timeoutAfterUpsert = true; listFails = true }
        val n = assertIs<ExportOutcome.NeedsVerification>(ExportRunner(g).run(rec, run1()))
        val back = Wire.json.decodeFromString(ExportRecord.serializer(), Wire.json.encodeToString(ExportRecord.serializer(), n.record))
        assertEquals(n.record, back); assertTrue(back.blocked)
        assertEquals(ExportStep.entries.toSet(), back.steps.keys)
    }

    @Test fun `rerunning a finished export does not create duplicates`() {
        val g = FakeGarmin(); val runner = ExportRunner(g)
        val done = runner.run(rec, run1()).record
        runner.run(done, run1()); runner.run(done, run1())
        assertEquals(1, g.workouts.size); assertEquals(1, g.calendar.size); assertEquals(1, g.upserts); assertEquals(1, g.schedules)
    }

    @Test fun `timeout after create finds the single object and continues without a second upload`() {
        val g = FakeGarmin().apply { timeoutAfterUpsert = true }
        val out = assertIs<ExportOutcome.Finished>(ExportRunner(g).run(rec, run1()))
        assertEquals(1, g.workouts.size); assertEquals(1, g.upserts)
        assertEquals("gw1", out.record.garminWorkoutId)
    }

    @Test fun `timeout before create is detected as not created and a retry is safe`() {
        val g = FakeGarmin().apply { timeoutBeforeUpsert = true }
        val runner = ExportRunner(g)
        val f = assertIs<ExportOutcome.Failed>(runner.run(rec, run1()))
        assertEquals(0, g.workouts.size)
        assertEquals(StepState.FAILED, f.record.steps[ExportStep.UPSERT])
        assertIs<ExportOutcome.Finished>(runner.run(f.record, run1()))
        assertEquals(1, g.workouts.size)
    }

    @Test fun `timeout when the lookup also fails is ambiguous, blocks every further mutation and is not retried blindly`() {
        val g = FakeGarmin().apply { timeoutAfterUpsert = true; listFails = true }
        val runner = ExportRunner(g)
        val n = assertIs<ExportOutcome.NeedsVerification>(runner.run(rec, run1()))
        assertEquals(StepState.UNCERTAIN, n.record.steps[ExportStep.UPSERT]); assertTrue(n.record.blocked)
        val again = runner.run(n.record, run1())
        assertIs<ExportOutcome.NeedsVerification>(again)
        assertEquals(1, g.upserts, "žádný slepý druhý upload")
        assertNotEquals(ExportLevel.DEVICE_REQUESTED, n.record.level)
    }

    @Test fun `timeout after scheduling adopts the calendar entry instead of scheduling twice`() {
        val g = FakeGarmin().apply { timeoutAfterSchedule = true }
        val out = assertIs<ExportOutcome.Finished>(ExportRunner(g).run(rec, run1()))
        assertEquals(1, g.calendar.size); assertEquals(1, g.schedules)
        assertEquals(ExportLevel.DEVICE_REQUESTED, out.record.level)
    }

    @Test fun `timeout before scheduling and unreadable calendar is ambiguous`() {
        val g = FakeGarmin().apply { timeoutBeforeSchedule = true }
        val runner = ExportRunner(g)
        val f = assertIs<ExportOutcome.Failed>(runner.run(rec, run1()))
        assertEquals(0, g.calendar.size)
        assertIs<ExportOutcome.Finished>(runner.run(f.record, run1()))
        assertEquals(1, g.calendar.size)

        val g2 = FakeGarmin().apply { timeoutAfterSchedule = true; calendarFails = false }
        // timeout po zápisu a následné čtení kalendáře selže:
        val flaky = object : GarminWorkoutApi by g2 {
            var calls = 0
            override fun listCalendar(date: String): List<RemoteScheduled> { if (++calls >= 2) throw ProviderError.Network("down"); return g2.listCalendar(date) }
        }
        assertIs<ExportOutcome.NeedsVerification>(ExportRunner(flaky).run(rec, run1()))
        assertEquals(1, g2.schedules)
    }

    @Test fun `external edit in Garmin Connect is compared not overwritten`() {
        val g = FakeGarmin(); val runner = ExportRunner(g)
        val done = runner.run(rec, run1()).record
        g.external = { id -> RemoteWorkout(id, "Upraveno ručně", "jiny-podpis") }
        val changed = run1().copy(nodes = run1().nodes.dropLast(1))
        val o = assertIs<ExportOutcome.ExternalChange>(runner.run(done.copy(steps = done.steps + (ExportStep.UPSERT to StepState.PENDING)), changed))
        assertEquals("jiny-podpis", o.remote.signature)
        assertEquals(1, g.upserts)
        g.external = null
        assertIs<ExportOutcome.Finished>(runner.run(done.copy(steps = done.steps + (ExportStep.UPSERT to StepState.PENDING)), changed, forceOverwriteExternal = true))
    }

    @Test fun `invalid workout never reaches the provider`() {
        val g = FakeGarmin()
        val o = assertIs<ExportOutcome.Failed>(ExportRunner(g).run(rec, run1().copy(nodes = emptyList())))
        assertEquals(StepState.FAILED, o.record.steps[ExportStep.VALIDATE]); assertEquals(0, g.upserts)
    }

    @Test fun `verify step catches a stored workout that does not match`() {
        val g = FakeGarmin().apply { external = { id -> RemoteWorkout(id, "x", "zkomolene") } }
        val o = assertIs<ExportOutcome.Failed>(ExportRunner(g).run(rec, run1()))
        assertEquals(StepState.FAILED, o.record.steps[ExportStep.VERIFY_STORED]); assertEquals(0, g.calendar.size)
    }

    @Test fun `cleanup only touches workouts with a local mapping`() {
        val remote = listOf(RemoteWorkout("gw1", "moje", "s"), RemoteWorkout("foreign", "cizí plán", "s"))
        val mapped = listOf(rec.copy(garminWorkoutId = "gw1"), rec.copy(exportId = "zzzzzz1"))
        assertEquals(listOf("gw1"), ExportCleanup.deletableRemote(mapped, remote).map { it.id })
        assertFalse(ExportCleanup.deletableRemote(emptyList(), remote).isNotEmpty())
    }
}
