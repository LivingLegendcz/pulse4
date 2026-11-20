package app.pulse4.android.di

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import app.pulse4.android.data.AppDatabase
import app.pulse4.android.data.ContactRow
import app.pulse4.android.data.ExcludedRow
import app.pulse4.android.data.FamilyLinkRow
import app.pulse4.android.data.GroupRow
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.data.OutboxRow
import app.pulse4.android.data.RoomOutboxStore
import app.pulse4.android.data.RoomReceiverStore
import app.pulse4.android.data.RoomSentLedger
import app.pulse4.android.data.Settings
import app.pulse4.android.data.toRow
import app.pulse4.android.security.SecretRecoveryState
import app.pulse4.android.security.SecretSealer
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Quality
import app.pulse4.provider.HttpGateway
import app.pulse4.provider.HttpResponse
import app.pulse4.sharing.AgeProfile
import app.pulse4.sharing.FamilyLink
import app.pulse4.sharing.FamilyLinkStatus
import app.pulse4.sharing.Field
import app.pulse4.sharing.Group
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.TimeScope
import app.pulse4.sync.Ack
import app.pulse4.sync.BuiltInHandlers
import app.pulse4.sync.DeliveryState
import app.pulse4.sync.Kinds
import app.pulse4.sync.MessageBuilder
import app.pulse4.sync.OutboxManager
import app.pulse4.sync.Outcome
import app.pulse4.sync.Receiver
import app.pulse4.sync.RejectReason
import app.pulse4.sync.Wire
import app.pulse4.withings.WithingsUserProfile
import app.pulse4.withings.WithingsWebSession
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Balíček M (§16.3): R4-2 re-emit celého kruhu, R4-6 SNAPSHOT_REQUEST jen pro nastavené dítě, R4-7 revoke pod planLock. */
class PackageMSharingFamilyTest {

    private val tempDirs = mutableListOf<File>()
    private val openDbs = mutableListOf<Pair<AppDatabase, File>>()

    @AfterTest
    fun tearDown() {
        SecretRecoveryState.clear()
        openDbs.forEach { (db, f) ->
            runCatching { db.close() }
            f.delete()
            File("${f.absolutePath}-wal").delete()
            File("${f.absolutePath}-shm").delete()
        }
        openDbs.clear()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    // ---------------------------------------------------------------- R4-2

    @Test
    fun `M1 R4-2 re-emit sends the FULL circle only to the missing member and the member ends up with all 4`() {
        val owner = "aa11aa11aa11aa11aa11aa11aa11aa11"
        val m1 = "bb22bb22bb22bb22bb22bb22bb22bb22"
        val m2 = "cc33cc33cc33cc33cc33cc33cc33cc33"
        val missing = "dd44dd44dd44dd44dd44dd44dd44dd44"
        val ownerDb = newDb()
        val missingDb = newDb()

        val group = Group(
            id = "grp-4", name = "Rodina", ownerId = owner, adminId = owner,
            members = setOf(owner, m1, m2, missing), version = 5L, admins = setOf(owner),
        )
        ownerDb.grants().upsertGroup(GroupRow(group.id, group.version, Wire.json.encodeToString(Group.serializer(), group)))
        // m1, m2 already have an active MEMBERSHIP_UPDATE for v5; the one for `missing` was cancelled by an older migration.
        insertMembershipRow(ownerDb, owner, m1, group, "ev-m1", DeliveryState.ACKED)
        insertMembershipRow(ownerDb, owner, m2, group, "ev-m2", DeliveryState.HANDED_TO_TRANSPORT)
        insertMembershipRow(ownerDb, owner, missing, group, "ev-missing", DeliveryState.CANCELLED)

        val ownerOutbox = OutboxManager(RoomOutboxStore(ownerDb))
        val ownerShare = ShareService(
            data = RoomPulseDataAccess(ownerDb),
            settings = RealShareSettingsAccess(Settings(ownerDb)),
            outbox = ownerOutbox,
            sentLedger = RoomSentLedger(ownerDb),
            recordSent = { r, k, v, h -> RoomSentLedger(ownerDb).record(r, k, v, h) },
            selfIdProvider = { owner },
        )

        // Production wiring used by ServiceMigrations.runOnce.
        val reemitted = ServiceMigrations.reemitPendingControlMessages(
            db = ownerDb,
            selfId = owner,
            shareService = ownerShare,
            nowMs = 10_000L,
        )
        assertEquals(1, reemitted)

        val due = ownerDb.outbox().all().filter { it.kind == Kinds.MEMBERSHIP_UPDATE && it.createdAtMs == 10_000L }
        assertEquals(listOf(missing), due.map { it.recipient }, "only the member without an active update is re-sent")
        val sentGroup = Wire.json.decodeFromString(Group.serializer(), Wire.decode(due.single().wire)!!.payload)
        assertEquals(group.members, sentGroup.members, "payload must carry the whole circle, not missing + self")
        assertEquals(group.version, sentGroup.version)

        // The missing member knows an older (v4) circle and applies the update via the real Receiver.
        val older = group.copy(members = setOf(owner, missing), version = 4L)
        missingDb.grants().upsertGroup(GroupRow(older.id, older.version, Wire.json.encodeToString(Group.serializer(), older)))
        missingDb.contacts().upsert(ContactRow(owner, "Owner", "pub", "fp", verifiedAtMs = 1_000L))
        val receiver = Receiver(
            selfId = missing,
            store = RoomReceiverStore(missingDb),
            handlers = BuiltInHandlers.all(),
            ackSink = { _, _ -> },
            builder = MessageBuilder(missing),
        )
        val outcome = receiver.receive(owner, due.single().wire, 10_500L)
        assertTrue(outcome is Outcome.Applied, "got $outcome")
        val stored = Wire.json.decodeFromString(Group.serializer(), missingDb.grants().group(group.id)!!.json)
        assertEquals(setOf(owner, m1, m2, missing), stored.members)
        assertEquals(5L, stored.version)
    }

    // ---------------------------------------------------------------- R4-6

    @Test
    fun `M2 M4 R4-6 SNAPSHOT_REQUEST is served only to the configured child with its bound profile and DB deletion beats the cache`() {
        val parent = "pp00pp00pp00pp00pp00pp00pp00pp00"
        val childA = "ca11ca11ca11ca11ca11ca11ca11ca11"
        val childB = "cb22cb22cb22cb22cb22cb22cb22cb22"
        val env = newParentEnv(parent, selectedChildUid = 3003L)
        val db = env.db
        listOf(childA, childB).forEach { c ->
            db.contacts().upsert(ContactRow(c, "Child $c", "pub", "fp", verifiedAtMs = 1_000L))
            insertVerifiedLink(db, parent, c)
        }
        // Pairing: B <-> 3003, A <-> 2002 (A added last = configured recipient).
        assertTrue(env.withings.addChild(childB, "3003"))
        assertTrue(env.withings.addChild(childA, "2002"))
        assertEquals(childA, env.sl.settings.childRecipientIdentity)

        val today = LocalDate.now(ZoneId.of(env.sl.settings.zoneId))
        db.observations().upsert(
            listOf(
                childWeight("w-keep", 30.5, today.minusDays(1), accountId = "2002"),
                childWeight("w-tomb", 31.0, today.minusDays(2), accountId = "2002"),
                childWeight("w-excl", 29.9, today.minusDays(3), accountId = "2002"),
                // Sibling's profile (currently SELECTED in Withings settings): never goes to child A.
                childWeight("w-sibling", 22.0, today.minusDays(1), accountId = "3003"),
            ),
        )
        // "Not mine" exclusion on the parent's graph = still a valid child measurement.
        db.exclusions().put(ExcludedRow("w-excl", 1_000L))
        db.observations().markDeleted(listOf("w-excl"))

        val receiver = parentReceiver(env, parent)
        val t1 = System.currentTimeMillis()

        // Sibling B is VERIFIED and even bound, but not the configured child -> NOT_PERMITTED, nothing queued for B.
        val fromB = receiver.receive(childB, snapshotRequest(childB, parent, t1), t1)
        assertEquals(RejectReason.NOT_PERMITTED, (fromB as? Outcome.Rejected)?.reason, "got $fromB")
        assertTrue(familyPushes(db).none { it.recipient == childB }, "sibling must not receive any weights")

        // Configured child A gets exactly the data of ITS bound profile 2002, although 3003 is selected in Withings.
        val fromA = receiver.receive(childA, snapshotRequest(childA, parent, t1 + 1), t1 + 1)
        assertTrue(fromA is Outcome.Applied, "got $fromA")
        assertEquals(setOf("w-keep", "w-tomb", "w-excl"), pushedIds(db, childA, sinceMs = t1))

        // Withings later tombstones w-tomb (deleted, no exclusion). The in-memory cache still has it.
        db.observations().markDeleted(listOf("w-tomb"))
        val t2 = t1 + 60_000L
        val again = receiver.receive(childA, snapshotRequest(childA, parent, t2), t2)
        assertTrue(again is Outcome.Applied, "got $again")
        val resent = pushedIds(db, childA, sinceMs = t2)
        assertFalse("w-tomb" in resent, "cache/outbox must not override DB deleted: $resent")
        assertEquals(setOf("w-keep", "w-excl"), resent)
        assertTrue(familyPushes(db).none { it.recipient == childB })
    }

    @Test
    fun `M2 R4-6 fixed binding survives a Withings profile switch and changes only by remove plus re-add`() {
        val parent = "pp00pp00pp00pp00pp00pp00pp00pp00"
        val childA = "ca11ca11ca11ca11ca11ca11ca11ca11"
        val env = newParentEnv(parent, selectedChildUid = 2002L)
        env.sl.settings.childWeightAutoForward = false
        val now = System.currentTimeMillis()

        // Not added yet -> nothing is bound, fetched or forwarded (no implicit binding on use).
        env.sl.settings.put("migration.child_withings_uid_v1", "1")
        env.sl.settings.childRecipientIdentity = childA
        assertEquals(0, env.withings.syncAndForwardChildWeights(now))
        assertNull(env.withings.boundChildWebUid(childA))
        assertTrue(env.http.measureUserIds.isEmpty())
        assertFalse(env.withings.selectChildRecipient(childA), "unbound child cannot be selected without adding")

        assertTrue(env.withings.addChild(childA, "2002"))
        assertEquals(1, env.withings.syncAndForwardChildWeights(now))
        assertEquals(listOf("2002"), env.http.measureUserIds.toList())

        // Parent switches the selected Withings child profile to the sibling: A still gets ONLY 2002.
        env.saveSession(selectedChildUid = 3003L)
        env.http.measureUserIds.clear()
        env.withings.syncAndForwardChildWeights(now + 1_000L)
        assertEquals(listOf("2002"), env.http.measureUserIds.toList(), "fetch follows the binding, not the selection")
        assertEquals("2002", env.withings.boundChildWebUid(childA))
        assertFalse(env.withings.addChild(childA, "3003"), "an existing binding is never overwritten")
        assertEquals("2002", env.withings.boundChildWebUid(childA))

        // Remove: binding gone, recipient cleared, nothing forwarded any more.
        env.withings.removeChild(childA)
        assertNull(env.withings.boundChildWebUid(childA))
        assertEquals("", env.sl.settings.childRecipientIdentity)
        assertEquals(
            app.pulse4.android.di.WithingsService.ChildBindingStatus.UNBOUND,
            env.withings.childBinding(childA).status,
        )
        env.sl.settings.childRecipientIdentity = childA
        env.http.measureUserIds.clear()
        assertEquals(0, env.withings.syncAndForwardChildWeights(now + 2_000L))
        assertTrue(env.http.measureUserIds.isEmpty())

        // Re-add with a different profile.
        assertTrue(env.withings.addChild(childA, "3003"))
        assertEquals("3003", env.withings.boundChildWebUid(childA))
        assertEquals(1, env.withings.syncAndForwardChildWeights(now + 3_000L))
        assertEquals(listOf("3003"), env.http.measureUserIds.toList())
    }

    @Test
    fun `M2 R4-6 bound profile missing from the Withings account forwards nothing and reports a warning`() {
        val parent = "pp00pp00pp00pp00pp00pp00pp00pp00"
        val childA = "ca11ca11ca11ca11ca11ca11ca11ca11"
        val env = newParentEnv(parent, selectedChildUid = 2002L)
        assertTrue(env.withings.addChild(childA, "2002"))
        val binding = env.withings.childBinding(childA)
        assertEquals(app.pulse4.android.di.WithingsService.ChildBindingStatus.OK, binding.status)
        assertEquals("Child 2002", binding.profileName)

        val today = LocalDate.now(ZoneId.of(env.sl.settings.zoneId))
        env.db.observations().upsert(listOf(childWeight("w-a", 30.0, today.minusDays(1), accountId = "2002")))
        // Profile 2002 is removed from the Withings account.
        env.saveSession(selectedChildUid = 3003L, profileIds = listOf(3003L))
        val missing = env.withings.childBinding(childA)
        assertEquals(app.pulse4.android.di.WithingsService.ChildBindingStatus.PROFILE_MISSING, missing.status)
        assertEquals("2002", missing.withingsUid)

        val now = System.currentTimeMillis()
        assertEquals(0, env.withings.syncAndForwardChildWeights(now))
        assertTrue(env.http.measureUserIds.isEmpty())
        assertEquals(0, env.withings.onFamilyWeightSnapshotRequest(childA, now))
        assertTrue(familyPushes(env.db).isEmpty())
        // Binding is kept (no silent re-binding to another profile).
        assertEquals("2002", env.withings.boundChildWebUid(childA))
    }

    @Test
    fun `M2 R4-6 one-time migration binds the single existing child and never re-binds afterwards`() {
        val parent = "pp00pp00pp00pp00pp00pp00pp00pp00"
        val childA = "ca11ca11ca11ca11ca11ca11ca11ca11"
        val childB = "cb22cb22cb22cb22cb22cb22cb22cb22"
        val env = newParentEnv(parent, selectedChildUid = 2002L)
        // Existing install: child configured + child profile selected, no binding yet.
        env.sl.settings.childRecipientIdentity = childA
        assertEquals("2002", env.withings.boundChildWebUid(childA))

        // Later changes never bind implicitly.
        env.saveSession(selectedChildUid = 3003L)
        env.sl.settings.childRecipientIdentity = childB
        assertNull(env.withings.boundChildWebUid(childB))
        assertEquals("2002", env.withings.boundChildWebUid(childA))
        assertEquals(listOf(3003L, 2002L).map { "Child $it" }.toSet(), env.withings.childProfileCandidates().map { it.displayName }.toSet())
    }


    // ---------------------------------------------------------------- R4-7

    @Test
    fun `M3 R4-7 revoke waits for an in-flight planAndEnqueue and leaves the ledger cleared`() {
        val db = newDb()
        val settings = Settings(db)
        val zone = ZoneId.of(settings.zoneId)
        val today = LocalDate.now(zone)
        val day = today.minusDays(1)
        val ledger = RoomSentLedger(db)
        val pauseArmed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outbox = OutboxManager(RoomOutboxStore(db))
        val service = ShareService(
            data = RoomPulseDataAccess(db),
            settings = RealShareSettingsAccess(settings),
            outbox = outbox,
            sentLedger = ledger,
            recordSent = { r, k, v, h ->
                if (k == "daily/$day" && pauseArmed.compareAndSet(true, false)) {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                ledger.record(r, k, v, h)
            },
            selfIdProvider = { "alice" },
        )
        db.contacts().upsert(ContactRow("bob", "Bob", "pub", "fp", verifiedAtMs = 1_000L))
        db.observations().upsert(listOf(weight("w1", 72.5, day, zone)))
        val nowMs = System.currentTimeMillis()
        val grant = ShareGrant(
            id = "g1", ownerId = "alice", recipients = setOf("bob"), fields = setOf(Field.WEIGHT),
            scope = TimeScope.continuousFrom(today.minusDays(10)), createdAtMs = nowMs,
        )
        service.saveGrantWithPolicy(grant).getOrThrow()
        assertTrue(service.planAndEnqueue(nowMs) > 0)
        outbox.due(nowMs).forEach { outbox.onAck(it.recipient, Ack(it.eventId, Ack.Status.ACCEPTED)) }

        // New value for the day -> the next plan records a new hash for daily/day.
        db.observations().upsert(listOf(weight("w1", 73.1, day, zone)))
        pauseArmed.set(true)
        val planner = thread(name = "planner") { service.planAndEnqueue(nowMs + 1_000L) }
        assertTrue(entered.await(10, TimeUnit.SECONDS), "planAndEnqueue must reach recordSent")

        val revokeDone = CountDownLatch(1)
        val revokeError = AtomicReference<Throwable?>(null)
        val revoker = thread(name = "revoker") {
            try {
                service.revoke("g1", nowMs + 2_000L)
            } catch (t: Throwable) {
                revokeError.set(t)
            } finally {
                revokeDone.countDown()
            }
        }
        assertFalse(revokeDone.await(500, TimeUnit.MILLISECONDS), "revoke must wait for planLock")
        release.countDown()
        planner.join(10_000L)
        revoker.join(10_000L)
        assertNull(revokeError.get())

        val sent = assertNotNull(db.inbox().sent("bob", "daily/$day"))
        assertEquals("", sent.contentHash, "revoke must win over the concurrent plan (ledger cleared)")
        val removeVersion = sent.version
        assertTrue(outbox.due(nowMs + 2_000L).any { it.kind == Kinds.PROJECTION_REMOVE && it.objectKey == "daily/$day" })

        // Re-grant: the day is sent again with a version above the REMOVE.
        service.saveGrantWithPolicy(grant.copy(id = "g2", createdAtMs = nowMs + 3_000L)).getOrThrow()
        service.planAndEnqueue(nowMs + 3_000L)
        val upsert = outbox.due(nowMs + 3_000L).single { it.kind == Kinds.PROJECTION_UPSERT && it.objectKey == "daily/$day" }
        assertTrue(upsert.objectVersion > removeVersion)
    }

    // ---------------------------------------------------------------- Withings second adult never a child/source

    private fun saveAdultsAndChildSession(env: ParentEnv, storedChildUid: Long?) {
        val childBirth = System.currentTimeMillis() / 1000L - 9L * 365L * 86_400L
        val s = WithingsWebSession(
            cookies = "session_key=abc",
            primaryUserId = 1001L,
            primaryUserName = "Parent",
            childUserId = storedChildUid,
            childUserName = "stale",
            allProfiles = listOf(
                WithingsUserProfile(id = 1001L, firstName = "Parent", lastName = "", isPrimary = true, email = "p@x.cz"),
                // second adult: the API omitted email, birthdate and parent link
                WithingsUserProfile(id = 4004L, firstName = "Second", lastName = "Adult"),
                WithingsUserProfile(id = 2002L, firstName = "Kid", lastName = "", birthdate = childBirth, parentAccountId = 1001L),
                WithingsUserProfile(id = 3003L, firstName = "Kid2", lastName = "", birthdate = childBirth, parentAccountId = 1001L),
            ),
        )
        env.sl.secrets.putString(WithingsService.WEB_SESSION_KEY, Wire.json.encodeToString(WithingsWebSession.serializer(), s))
    }

    @Test
    fun `selectProfiles with no child preference keeps the stored child instead of wiping it`() {
        val env = newParentEnv("pp00pp00pp00pp00pp00pp00pp00pp00", selectedChildUid = 3003L)
        saveAdultsAndChildSession(env, storedChildUid = 3003L)
        assertTrue(env.withings.selectProfiles(1001L, null))
        assertEquals(3003L, env.withings.loadWebSession()?.childUserId)
        assertEquals(3003L, env.withings.displayedChild()?.id)
    }

    @Test
    fun `second adult stored as child by an older version is dropped and replaced by the genuine child`() {
        val env = newParentEnv("pp00pp00pp00pp00pp00pp00pp00pp00", selectedChildUid = 3003L)
        saveAdultsAndChildSession(env, storedChildUid = 4004L)
        // Display is already safe before any re-sync: the adult is never the displayed child.
        assertNull(env.withings.displayedChild())
        assertNull(env.withings.childUserId())
        assertTrue(env.withings.selectProfiles(1001L, null))
        val after = env.withings.loadWebSession()
        assertEquals(2002L, after?.childUserId) // auto-bound genuine child
        assertTrue(env.withings.childProfileCandidates().none { it.id == 4004L || it.id == 1001L })
        // an explicit request for the second adult is ignored too
        assertTrue(env.withings.selectProfiles(1001L, 4004L))
        assertTrue(env.withings.loadWebSession()?.childUserId != 4004L)
    }

    @Test
    fun `a binding that points at an adult profile is not forwarded and not displayed`() {
        val parent = "pp00pp00pp00pp00pp00pp00pp00pp00"
        val child = "ca11ca11ca11ca11ca11ca11ca11ca11"
        val env = newParentEnv(parent, selectedChildUid = 3003L)
        saveAdultsAndChildSession(env, storedChildUid = 2002L)
        assertTrue(env.withings.addChild(child, "4004")) // stale / migrated binding to the second adult
        assertEquals(WithingsService.ChildBindingStatus.PROFILE_MISSING, env.withings.childBinding(child).status)
        assertNull(env.withings.childWebUidFor(child))
        assertNull(env.withings.displayedChild())
        // a binding to the genuine child works and is what the card displays
        env.withings.removeChild(child)
        env.sl.settings.childRecipientIdentity = child
        assertTrue(env.withings.addChild(child, "3003"))
        assertEquals("3003", env.withings.childWebUidFor(child))
        assertEquals(3003L, env.withings.displayedChild()?.id)
    }

    // ---------------------------------------------------------------- helpers

    private class FakeWithingsHttp : HttpGateway {
        val measureUserIds: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun get(url: String, headers: Map<String, String>): HttpResponse = HttpResponse(404, emptyMap(), "")
        override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
            if (form["action"] != "getmeas") return HttpResponse(200, emptyMap(), """{"status":0,"body":{}}""")
            val uid = form["userid"].orEmpty()
            measureUserIds += uid
            val date = System.currentTimeMillis() / 1000L - 86_400L
            val kg = if (uid == "2002") 30400 else 21800
            return HttpResponse(
                200, emptyMap(),
                """{"status":0,"body":{"updatetime":$date,"more":0,"offset":0,"timezone":"Europe/Prague","measuregrps":[
                  {"grpid":${uid}01,"attrib":0,"date":$date,"created":$date,"modified":$date,"category":1,"deviceid":"d",
                   "timezone":"Europe/Prague","measures":[{"value":$kg,"unit":-3,"type":1}]}]}}""",
            )
        }
    }

    private class ParentEnv(
        val sl: ServiceLocator,
        val db: AppDatabase,
        val http: FakeWithingsHttp,
        val withings: WithingsService,
    ) {
        fun saveSession(selectedChildUid: Long?, profileIds: List<Long> = listOf(2002L, 3003L)) {
            val s = WithingsWebSession(
                cookies = "session_key=abc",
                primaryUserId = 1001L,
                primaryUserName = "Parent",
                childUserId = selectedChildUid,
                allProfiles = listOf(WithingsUserProfile(id = 1001L, firstName = "Parent", lastName = "", isPrimary = true)) +
                    profileIds.map { WithingsUserProfile(id = it, firstName = "Child", lastName = it.toString(), birthdate = System.currentTimeMillis() / 1000L - 10L * 365L * 86_400L) },
            )
            sl.secrets.putString(WithingsService.WEB_SESSION_KEY, Wire.json.encodeToString(WithingsWebSession.serializer(), s))
        }
    }

    private fun newParentEnv(parent: String, selectedChildUid: Long): ParentEnv {
        val dir = Files.createTempDirectory("pulse4-pkg-m-").toFile().also { tempDirs += it }
        val db = newDb()
        val sl = ServiceLocator(testContext(dir), dbOverride = db, sealerOverride = PlainSealer)
        sl.settings.networkMode = NetworkMode.MANUAL
        sl.settings.childWeightMinKg = 12.0
        sl.settings.childWeightMaxKg = 45.0
        val http = FakeWithingsHttp()
        val withings = WithingsService(sl.context, sl, http) { parent }
        return ParentEnv(sl, db, http, withings).also { it.saveSession(selectedChildUid) }
    }

    private fun parentReceiver(env: ParentEnv, parent: String) = Receiver(
        selfId = parent,
        store = RoomReceiverStore(env.db),
        handlers = buildFamilyAndWeightHandlers(
            data = RoomPulseDataAccess(env.db),
            settings = RealShareSettingsAccess(env.sl.settings),
            onSnapshotRequest = { childId, nowMs -> env.withings.onFamilyWeightSnapshotRequest(childId, nowMs) },
        ),
        ackSink = { _, _ -> },
        builder = MessageBuilder(parent),
    )

    private fun snapshotRequest(child: String, parent: String, nowMs: Long): ByteArray =
        Wire.encode(
            MessageBuilder(child).build(
                recipient = parent, kind = Kinds.SNAPSHOT_REQUEST, objectType = "own_weight",
                objectId = "family_weight", objectVersion = nowMs, payload = "{}", nowMs = nowMs,
            ),
        )

    private fun familyPushes(db: AppDatabase): List<OutboxRow> =
        db.outbox().all().filter { it.kind == WithingsService.FAMILY_WEIGHT_PUSH_KIND }

    private fun pushedIds(db: AppDatabase, recipient: String, sinceMs: Long): Set<String> =
        familyPushes(db).filter { it.recipient == recipient && it.createdAtMs >= sinceMs }.map { it.objectId }.toSet()

    private fun insertVerifiedLink(db: AppDatabase, parent: String, child: String) {
        val link = FamilyLink(
            id = "fl-$child", parentId = parent, childId = child, status = FamilyLinkStatus.VERIFIED,
            childAgeProfile = AgeProfile.MINOR, createdAtMs = 1_000L, verifiedAtMs = 2_000L, version = 2L,
        )
        db.familyLinks().upsert(
            FamilyLinkRow(link.id, parent, child, link.status.name, link.version, Wire.json.encodeToString(FamilyLink.serializer(), link)),
        )
    }

    private fun insertMembershipRow(db: AppDatabase, owner: String, recipient: String, group: Group, eventId: String, state: DeliveryState) {
        val msg = MessageBuilder(owner) { eventId }.build(
            recipient = recipient, kind = Kinds.MEMBERSHIP_UPDATE, objectType = "group", objectId = group.id,
            objectVersion = group.version, payload = Wire.json.encodeToString(Group.serializer(), group), nowMs = 1_000L,
        )
        db.outbox().insert(
            OutboxRow(
                eventId = eventId, recipient = recipient, kind = Kinds.MEMBERSHIP_UPDATE, objectType = "group",
                objectId = group.id, objectVersion = group.version, grantId = null, wire = Wire.encode(msg),
                createdAtMs = 1_000L, state = state.name, attempts = 0, nextAttemptAtMs = 0L, lastError = null,
            ),
        )
    }

    private fun childWeight(id: String, kg: Double, date: LocalDate, accountId: String) = MetricObservation(
        id = id, provider = Provider.WITHINGS_API, accountId = accountId, externalId = id, type = MetricType.WEIGHT,
        startUtcMs = date.atTime(8, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(), endUtcMs = null,
        value = kg, zoneId = "UTC", localDate = date.toString(), quality = Quality.OK,
    ).toRow()

    private fun weight(id: String, kg: Double, date: LocalDate, zone: ZoneId) = MetricObservation(
        id = id, provider = Provider.WITHINGS_API, accountId = "acc", externalId = id, type = MetricType.WEIGHT,
        startUtcMs = date.atTime(8, 0).atZone(zone).toInstant().toEpochMilli(), endUtcMs = null,
        value = kg, zoneId = zone.id, localDate = date.toString(), quality = Quality.OK,
    ).toRow()

    private object PlainSealer : SecretSealer {
        override fun seal(plain: ByteArray): ByteArray = ByteArray(12) { 1 } + plain + ByteArray(16) { 2 }
        override fun open(sealed: ByteArray): ByteArray = sealed.copyOfRange(12, sealed.size - 16)
        override fun hasMasterKey(): Boolean = true
    }

    private fun newDb(): AppDatabase {
        val dbFile = File.createTempFile("pulse4-pkgm-db-", ".db")
        dbFile.delete()
        val ctx = object : ContextWrapper(null) {
            override fun getApplicationContext() = this
            override fun getDatabasePath(name: String): File = File(name)
        }
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbFile.absolutePath)
            .setDriver(JdbcSqliteDriver())
            .allowMainThreadQueries()
            .build()
        openDbs += db to dbFile
        return db
    }

    private fun testContext(dir: File): Context {
        val prefs = InMemorySharedPrefs()
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = dir
            override fun getFilesDir(): File = dir
            override fun getDatabasePath(name: String): File = File(dir, name)
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
    }

    private class InMemorySharedPrefs : SharedPreferences {
        private val data = LinkedHashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
            private var clearAll = false
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) removals.add(key) }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                data.putAll(pending)
            }
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class JdbcSqliteDriver : SQLiteDriver {
        override fun open(fileName: String): SQLiteConnection {
            val url = if (fileName == ":memory:") "jdbc:sqlite::memory:" else "jdbc:sqlite:$fileName"
            return JdbcSqliteConnection(DriverManager.getConnection(url))
        }
    }

    private class JdbcSqliteConnection(private val conn: java.sql.Connection) : SQLiteConnection {
        private var inTx = false
        override fun inTransaction(): Boolean = inTx
        override fun prepare(sql: String): SQLiteStatement {
            val upper = sql.trimStart().uppercase()
            return JdbcSqliteStatement(conn.prepareStatement(sql)) {
                when {
                    upper.startsWith("BEGIN") -> inTx = true
                    upper.startsWith("COMMIT") || upper.startsWith("END") || upper.startsWith("ROLLBACK") -> inTx = false
                }
            }
        }
        override fun close() = conn.close()
    }

    private class JdbcSqliteStatement(
        private val ps: PreparedStatement,
        private val onStep: () -> Unit,
    ) : SQLiteStatement {
        private var executed = false
        private var rs: ResultSet? = null
        private var cachedColumnNames: List<String>? = null
        private var firstStepPending = false
        private var hasPendingFirstRow = false
        private var eof = false

        private fun executeAndCacheColumns() {
            if (executed) return
            executed = true
            onStep()
            if (ps.execute()) {
                val r = ps.resultSet
                rs = r
                val md = r.metaData
                cachedColumnNames = (1..md.columnCount).map { md.getColumnName(it) }
                hasPendingFirstRow = r.next()
                eof = !hasPendingFirstRow
                firstStepPending = true
            } else {
                cachedColumnNames = emptyList()
                eof = true
            }
        }

        override fun bindBlob(index: Int, value: ByteArray) = ps.setBytes(index, value)
        override fun bindDouble(index: Int, value: Double) = ps.setDouble(index, value)
        override fun bindLong(index: Int, value: Long) = ps.setLong(index, value)
        override fun bindText(index: Int, value: String) = ps.setString(index, value)
        override fun bindNull(index: Int) = ps.setNull(index, Types.NULL)
        override fun getBlob(index: Int): ByteArray = rs?.getBytes(index + 1) ?: ByteArray(0)
        override fun getDouble(index: Int): Double = rs?.getDouble(index + 1) ?: 0.0
        override fun getLong(index: Int): Long = rs?.getLong(index + 1) ?: 0L
        override fun getText(index: Int): String = rs?.getString(index + 1) ?: ""
        override fun isNull(index: Int): Boolean = rs?.getObject(index + 1) == null
        override fun getColumnCount(): Int { if (cachedColumnNames == null) executeAndCacheColumns(); return cachedColumnNames?.size ?: 0 }
        override fun getColumnName(index: Int): String { if (cachedColumnNames == null) executeAndCacheColumns(); return cachedColumnNames?.getOrNull(index) ?: "" }
        override fun getColumnType(index: Int): Int {
            val v = rs?.getObject(index + 1) ?: return 5
            return when (v) {
                is Int, is Long -> 1
                is Float, is Double -> 2
                is ByteArray -> 4
                else -> 3
            }
        }
        override fun step(): Boolean {
            if (!executed) { executeAndCacheColumns(); firstStepPending = false; return hasPendingFirstRow }
            if (firstStepPending) { firstStepPending = false; return hasPendingFirstRow }
            if (eof) return false
            val next = rs?.next() == true
            if (!next) eof = true
            return next
        }
        override fun reset() { rs?.close(); rs = null; cachedColumnNames = null; executed = false; firstStepPending = false; hasPendingFirstRow = false; eof = false }
        override fun clearBindings() = ps.clearParameters()
        override fun close() { rs?.close(); rs = null; ps.close() }
    }
}
