package app.pulse4.android

import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import app.pulse4.android.data.ActivityRow
import app.pulse4.android.data.AppDatabase
import app.pulse4.android.data.ContactRow
import app.pulse4.android.data.FamilyLinkRow
import app.pulse4.android.data.GrantRow
import app.pulse4.android.data.GroupRow
import app.pulse4.android.data.OutboxRow
import app.pulse4.android.data.PendingRequestRow
import app.pulse4.android.data.PlanRevisionRow
import app.pulse4.android.data.ProposalRow
import app.pulse4.android.data.ReceiptRow
import app.pulse4.android.data.ReceivedRow
import app.pulse4.android.data.RoomPlanStore
import app.pulse4.android.data.SentRow
import app.pulse4.android.data.SettingRow
import app.pulse4.android.data.Settings
import app.pulse4.android.data.TrackRow
import app.pulse4.android.data.toRow
import app.pulse4.android.di.ServiceMigrations
import app.pulse4.domain.Activity
import app.pulse4.domain.ActivityLap
import app.pulse4.domain.Provider
import app.pulse4.domain.Sport
import app.pulse4.domain.TrackPoint
import app.pulse4.sharing.AdminHandover
import app.pulse4.sharing.AgeProfile
import app.pulse4.sharing.FamilyLink
import app.pulse4.sharing.FamilyLinkStatus
import app.pulse4.sharing.Field
import app.pulse4.sharing.Group
import app.pulse4.sharing.PlanRight
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.TimeScope
import app.pulse4.sync.DeliveryState
import app.pulse4.sync.Kinds
import app.pulse4.sync.MessageBuilder
import app.pulse4.sync.Wire
import app.pulse4.training.ChangeProposal
import app.pulse4.training.DurationSpec
import app.pulse4.training.PlanOp
import app.pulse4.training.PlanRevision
import app.pulse4.training.ScheduledWorkout
import app.pulse4.training.StepType
import app.pulse4.training.TargetSpec
import app.pulse4.training.Workout
import app.pulse4.training.WorkoutNode
import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDate
import java.util.Base64
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

class ServiceMigrationsRoomTest {

    private fun testPublicKey(seedByte: Byte): Pair<String, String> {
        val privBytes = ByteArray(32) { (it + seedByte.toInt()).toByte() }
        val privB64 = Base64.getEncoder().encodeToString(privBytes)
        val edPub = Ed25519PrivateKeyParameters(privBytes, 0).generatePublicKey()
        val xSeed = MessageDigest.getInstance("SHA-256").digest(privBytes)
        val xPub = X25519PrivateKeyParameters(xSeed, 0).generatePublicKey()
        val pubB64 = Base64.getEncoder().encodeToString(xPub.encoded + edPub.encoded)
        return privB64 to pubB64
    }

    private fun openTestDb(dbFile: File): AppDatabase {
        val ctx = object : ContextWrapper(null) {
            override fun getApplicationContext() = this
            override fun getDatabasePath(name: String): File = File(name)
        }
        return Room.databaseBuilder(ctx, AppDatabase::class.java, dbFile.absolutePath)
            .setDriver(JdbcSqliteDriver())
            .allowMainThreadQueries()
            .build()
    }

    private fun withTempDb(block: (dbFile: File, db: AppDatabase) -> Unit) {
        val dbFile = File.createTempFile("pulse4-mig-test-", ".db")
        dbFile.delete()
        val db = openTestDb(dbFile)
        try {
            block(dbFile, db)
        } finally {
            runCatching { db.close() }
            dbFile.delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
        }
    }

    @Test
    fun `R2-1 to R2-6 migrates 0_1_x Room DB preserves plan and admin rights updates Settings cache and is idempotent`() {
        val (selfPrivB64, selfPubB64) = testPublicKey(10)
        val (_, peerPubB64) = testPublicKey(42)
        val (selfLxmf, selfLegacy) = ServiceMigrations.deriveDeliveryHashes(selfPubB64)
        val (peerLxmf, peerLegacy) = ServiceMigrations.deriveDeliveryHashes(peerPubB64)
        assertNotEquals(selfLxmf, selfLegacy)
        assertNotEquals(peerLxmf, peerLegacy)

        val legacyPlanId = "plan-${selfLegacy.take(8)}"

        withTempDb { dbFile, db ->
            val workout = Workout(
                id = "w-1",
                name = "Ranní klus",
                sport = Sport.RUNNING,
                ownerId = selfLegacy,
                nodes = listOf(WorkoutNode.Step(StepType.WORK, DurationSpec.Time(1800), TargetSpec.None)),
            )
            val sw = ScheduledWorkout("sw-1", workout, selfLegacy, "2026-11-18")
            val rev1 = PlanRevision(
                planId = legacyPlanId,
                ownerId = selfLegacy,
                revision = 1L,
                parent = null,
                authorId = selfLegacy,
                createdAtMs = 1_000L,
                workouts = listOf(sw),
                touchedIds = setOf("sw-1"),
            )
            val proposal = ChangeProposal(
                id = "prop-1",
                planId = legacyPlanId,
                baseRevision = 1L,
                authorId = peerLegacy,
                ops = listOf(PlanOp.Remove("sw-1")),
                createdAtMs = 2_000L,
            )
            val grant = ShareGrant(
                id = "grant-1",
                ownerId = selfLegacy,
                recipients = setOf(peerLegacy),
                fields = setOf(Field.SLEEP_DURATION, Field.ACT_SUMMARY),
                planRights = mapOf(legacyPlanId to PlanRight.PROPOSE),
                scope = TimeScope.continuousFrom(LocalDate.of(2026, 11, 1)),
                groupId = "grp-1",
                createdAtMs = 1_000L,
            )
            val legacyGroup = Group(
                id = "grp-1",
                name = "Rodina",
                ownerId = selfLegacy,
                adminId = selfLegacy,
                members = setOf(selfLegacy, peerLegacy),
                version = 3L,
                admins = emptySet(),
                pendingAdminHandover = AdminHandover(
                    targetMemberId = peerLegacy,
                    initiatedBy = selfLegacy,
                    initiatedAtMs = 1_000L,
                    expiresAtMs = 99_000L,
                ),
            )
            val familyLink = FamilyLink(
                id = "fl-1",
                parentId = selfLegacy,
                childId = peerLegacy,
                childAgeProfile = AgeProfile.MINOR,
                status = FamilyLinkStatus.VERIFIED,
                version = 2L,
                createdAtMs = 1_000L,
                verifiedAtMs = 1_500L,
            )

            db.contacts().upsert(ContactRow(peerLegacy, "Máma", peerPubB64, "fp-1", verifiedAtMs = 1_000L))
            db.grants().upsert(GrantRow(grant.id, false, Wire.json.encodeToString(ShareGrant.serializer(), grant)))
            db.grants().upsertGroup(GroupRow(legacyGroup.id, legacyGroup.version, Wire.json.encodeToString(Group.serializer(), legacyGroup)))
            db.plans().insertRevision(PlanRevisionRow(legacyPlanId, 1L, Wire.json.encodeToString(PlanRevision.serializer(), rev1)))
            db.plans().putProposal(ProposalRow(proposal.id, legacyPlanId, "PENDING", Wire.json.encodeToString(ChangeProposal.serializer(), proposal)))
            db.familyLinks().upsert(FamilyLinkRow(familyLink.id, selfLegacy, peerLegacy, "VERIFIED", 2L, Wire.json.encodeToString(FamilyLink.serializer(), familyLink)))
            db.pendingRequests().upsert(PendingRequestRow("pr-1", "FAMILY_LINK_REQUEST", peerLegacy, selfLegacy, "PENDING", 1_000L, """{"from":"$peerLegacy","to":"$selfLegacy"}"""))

            // Outbox: QUEUED membership.update & family.link.request with legacy IDs in payload -> CANCELLED
            val queuedGroupMsg = MessageBuilder(selfLegacy) { "ev-queued-grp" }.build(
                recipient = peerLegacy,
                kind = Kinds.MEMBERSHIP_UPDATE,
                objectType = "group",
                objectId = legacyGroup.id,
                objectVersion = legacyGroup.version,
                payload = Wire.json.encodeToString(Group.serializer(), legacyGroup),
                nowMs = 3_000L,
            )
            db.outbox().insert(
                OutboxRow(
                    eventId = queuedGroupMsg.envelope.eventId,
                    recipient = peerLegacy,
                    kind = Kinds.MEMBERSHIP_UPDATE,
                    objectType = "group",
                    objectId = legacyGroup.id,
                    objectVersion = legacyGroup.version,
                    grantId = null,
                    wire = Wire.encode(queuedGroupMsg),
                    createdAtMs = 3_000L,
                    state = DeliveryState.QUEUED.name,
                    attempts = 0,
                    nextAttemptAtMs = 0L,
                    lastError = null,
                )
            )
            val queuedLinkMsg = MessageBuilder(selfLegacy) { "ev-queued-link" }.build(
                recipient = peerLegacy,
                kind = Kinds.FAMILY_LINK_REQUEST,
                objectType = "family_link",
                objectId = familyLink.id,
                objectVersion = familyLink.version,
                payload = Wire.json.encodeToString(FamilyLink.serializer(), familyLink),
                nowMs = 3_100L,
            )
            db.outbox().insert(
                OutboxRow(
                    eventId = queuedLinkMsg.envelope.eventId,
                    recipient = peerLegacy,
                    kind = Kinds.FAMILY_LINK_REQUEST,
                    objectType = "family_link",
                    objectId = familyLink.id,
                    objectVersion = familyLink.version,
                    grantId = null,
                    wire = Wire.encode(queuedLinkMsg),
                    createdAtMs = 3_100L,
                    state = DeliveryState.QUEUED.name,
                    attempts = 0,
                    nextAttemptAtMs = 0L,
                    lastError = null,
                )
            )
            // Non-QUEUED item: envelope & recipient rewritten
            val storedMsg = MessageBuilder(selfLegacy) { "ev-stored" }.build(
                recipient = peerLegacy,
                kind = Kinds.PROJECTION_UPSERT,
                objectType = "activity",
                objectId = "act-1",
                objectVersion = 1L,
                payload = """{"activityId":"act-1"}""",
                nowMs = 3_200L,
            )
            db.outbox().insert(
                OutboxRow(
                    eventId = storedMsg.envelope.eventId,
                    recipient = peerLegacy,
                    kind = Kinds.PROJECTION_UPSERT,
                    objectType = "activity",
                    objectId = "act-1",
                    objectVersion = 1L,
                    grantId = grant.id,
                    wire = Wire.encode(storedMsg),
                    createdAtMs = 3_200L,
                    state = DeliveryState.STORED_IN_PROPAGATION.name,
                    attempts = 1,
                    nextAttemptAtMs = 10_000L,
                    lastError = null,
                )
            )

            db.inbox().putSent(SentRow(peerLegacy, "daily/2026-11-17", 4L, "hash-4"))
            db.inbox().putObject(
                ReceivedRow(
                    author = peerLegacy,
                    objectType = "group",
                    objectId = "grp-remote",
                    version = 7L,
                    payload = """{"id":"grp-remote","ownerId":"$peerLegacy","adminId":"$peerLegacy","members":["$peerLegacy","$selfLegacy"]}""",
                )
            )
            db.inbox().insertReceipt(ReceiptRow("rcpt-1", "ch-1", peerLegacy, 2_000L))

            db.settings().put(SettingRow("delivered_keys.grant-1", """{"$peerLegacy":["daily/2026-11-17"]}"""))
            db.settings().put(SettingRow("childRecipientIdentity", peerLegacy))

            // Warm up Settings cache BEFORE migration (R2-4)
            val settings = Settings(db)
            runBlocking { settings.warmUp() }
            assertTrue(settings.getRaw("delivered_keys.grant-1")!!.contains(peerLegacy))

            // Run production migration
            val migrated = ServiceMigrations.migrateLegacyPulseIdentities(db, settings, selfPubB64)
            assertTrue(migrated > 0)

            // R2-1: Plan revision & proposal updated in place (replaceRevision)
            val migratedRev = assertNotNull(RoomPlanStore(db).head(legacyPlanId))
            assertEquals(selfLxmf, migratedRev.ownerId)
            assertEquals(selfLxmf, migratedRev.authorId)
            assertEquals(selfLxmf, migratedRev.workouts.single().athleteId)
            assertEquals(selfLxmf, migratedRev.workouts.single().workout.ownerId)
            val migratedProp = assertNotNull(RoomPlanStore(db).proposal("prop-1"))
            assertEquals(peerLxmf, migratedProp.first.authorId)

            // R2-2: ownPlanId preserved as plan-<legacy8>
            assertEquals(legacyPlanId, settings.getRaw(Settings.KEY_OWN_PLAN_ID))
            assertEquals(legacyPlanId, settings.ownPlanId(selfLxmf, selfLegacy))

            // R2-3: Circle ownerId, adminId, pendingAdminHandover migrated -> effectiveAdmins contains selfLxmf
            val migratedGroup = Wire.json.decodeFromString(Group.serializer(), db.grants().group("grp-1")!!.json)
            assertEquals(selfLxmf, migratedGroup.ownerId)
            assertEquals(selfLxmf, migratedGroup.adminId)
            assertEquals(setOf(selfLxmf, peerLxmf), migratedGroup.members)
            assertEquals(peerLxmf, migratedGroup.pendingAdminHandover?.targetMemberId)
            assertEquals(selfLxmf, migratedGroup.pendingAdminHandover?.initiatedBy)
            assertTrue(selfLxmf in migratedGroup.effectiveAdmins)

            // R2-4: In-memory Settings cache immediately reflects migrated values without restart
            val cachedDelivered = settings.getRaw("delivered_keys.grant-1")!!
            assertTrue(peerLxmf in cachedDelivered)
            assertFalse(peerLegacy in cachedDelivered)
            assertEquals(peerLxmf, settings.getRaw("childRecipientIdentity"))
            assertEquals("1", settings.getRaw("migration.lxmf_identity_v1"))

            // R2-6: Outbox QUEUED items with legacy payload cancelled; non-QUEUED rewritten
            assertEquals(DeliveryState.CANCELLED.name, db.outbox().find("ev-queued-grp")?.state)
            assertEquals(DeliveryState.CANCELLED.name, db.outbox().find("ev-queued-link")?.state)
            val migratedStored = assertNotNull(db.outbox().find("ev-stored"))
            assertEquals(DeliveryState.STORED_IN_PROPAGATION.name, migratedStored.state)
            assertEquals(peerLxmf, migratedStored.recipient)
            val decodedStored = assertNotNull(Wire.decode(migratedStored.wire))
            assertEquals(selfLxmf, decodedStored.envelope.author)
            assertEquals(peerLxmf, decodedStored.envelope.recipient)

            // R2-6: received_object payload rewritten without changing version
            val rxRow = assertNotNull(db.inbox().received(peerLxmf, "group", "grp-remote"))
            assertEquals(7L, rxRow.version)
            assertTrue(peerLxmf in rxRow.payload)
            assertTrue(selfLxmf in rxRow.payload)
            assertFalse(peerLegacy in rxRow.payload)
            assertFalse(selfLegacy in rxRow.payload)
            assertNull(db.inbox().received(peerLegacy, "group", "grp-remote"))

            // Contact, grant, family link, receipt, sent_projection migrated
            assertEquals(1, db.contacts().count(peerLxmf))
            assertEquals(0, db.contacts().count(peerLegacy))
            val migratedGrant = Wire.json.decodeFromString(ShareGrant.serializer(), db.grants().get("grant-1")!!.json)
            assertEquals(selfLxmf, migratedGrant.ownerId)
            assertEquals(setOf(peerLxmf), migratedGrant.recipients)
            assertEquals(PlanRight.PROPOSE, migratedGrant.planRight(legacyPlanId))
            assertEquals(4L, db.inbox().sent(peerLxmf, "daily/2026-11-17")?.version)
            assertNull(db.inbox().sent(peerLegacy, "daily/2026-11-17"))

            // Restart simulation: reopen DB & Settings from disk
            db.close()
            val dbRestart = openTestDb(dbFile)
            try {
                val settingsRestart = Settings(dbRestart)
                runBlocking { settingsRestart.warmUp() }
                assertTrue(peerLxmf in settingsRestart.getRaw("delivered_keys.grant-1")!!)
                assertEquals(legacyPlanId, settingsRestart.ownPlanId(selfLxmf, selfLegacy))
                assertEquals(selfLxmf, RoomPlanStore(dbRestart).head(legacyPlanId)?.ownerId)

                // Idempotence: 2nd run makes 0 changes
                val secondMigrated = ServiceMigrations.migrateLegacyPulseIdentities(dbRestart, settingsRestart, selfPubB64)
                assertEquals(0, secondMigrated)
            } finally {
                dbRestart.close()
            }
        }
    }

    @Test
    fun `R2-2 fresh install without legacy plan revisions persists plan-lxmf8`() {
        val (_, selfPubB64) = testPublicKey(7)
        val (selfLxmf, selfLegacy) = ServiceMigrations.deriveDeliveryHashes(selfPubB64)
        withTempDb { _, db ->
            val settings = Settings(db)
            ServiceMigrations.migrateLegacyPulseIdentities(db, settings, selfPubB64)
            assertEquals("plan-${selfLxmf.take(8)}", settings.getRaw(Settings.KEY_OWN_PLAN_ID))
            assertEquals("plan-${selfLxmf.take(8)}", settings.ownPlanId(selfLxmf, selfLegacy))
        }
    }

    @Test
    fun `R2-6 PK collisions keep higher version or verifiedAtMs across contact sent_projection and received_object`() {
        val (_, selfPubB64) = testPublicKey(11)
        val (_, peerPubB64) = testPublicKey(22)
        val (_, peer2PubB64) = testPublicKey(33)
        val (peerLxmf, peerLegacy) = ServiceMigrations.deriveDeliveryHashes(peerPubB64)
        val (peer2Lxmf, peer2Legacy) = ServiceMigrations.deriveDeliveryHashes(peer2PubB64)

        withTempDb { _, db ->
            // Contact 1: lxmf row is newer (verifiedAtMs = 5000 > 1000) -> keep lxmf displayName
            db.contacts().upsert(ContactRow(peerLegacy, "Old Name", peerPubB64, "fp-old", verifiedAtMs = 1_000L))
            db.contacts().upsert(ContactRow(peerLxmf, "New Name", peerPubB64, "fp-new", verifiedAtMs = 5_000L))

            // Contact 2: legacy row is newer (verifiedAtMs = 9000 > 2000) -> overwrite with legacy displayName
            db.contacts().upsert(ContactRow(peer2Legacy, "Newer Legacy Name", peer2PubB64, "fp-2-new", verifiedAtMs = 9_000L))
            db.contacts().upsert(ContactRow(peer2Lxmf, "Older Lxmf Name", peer2PubB64, "fp-2-old", verifiedAtMs = 2_000L))

            // SentProjection: k1 has higher version on lxmf (10 > 3); k2 has higher version on legacy (12 > 4)
            db.inbox().putSent(SentRow(peerLegacy, "daily/2026-11-10", 3L, "old-h"))
            db.inbox().putSent(SentRow(peerLxmf, "daily/2026-11-10", 10L, "new-h"))
            db.inbox().putSent(SentRow(peerLegacy, "daily/2026-11-11", 12L, "legacy-newer-h"))
            db.inbox().putSent(SentRow(peerLxmf, "daily/2026-11-11", 4L, "lxmf-older-h"))

            // ReceivedObject: o1 has higher version on lxmf (8 > 2); o2 has higher version on legacy (9 > 1)
            db.inbox().putObject(ReceivedRow(peerLegacy, "daily", "2026-11-10", 2L, """{"ownerId":"$peerLegacy","old":true}"""))
            db.inbox().putObject(ReceivedRow(peerLxmf, "daily", "2026-11-10", 8L, """{"ownerId":"$peerLegacy","fresh":true}"""))
            db.inbox().putObject(ReceivedRow(peerLegacy, "daily", "2026-11-11", 9L, """{"ownerId":"$peerLegacy","winner":"legacy"}"""))
            db.inbox().putObject(ReceivedRow(peerLxmf, "daily", "2026-11-11", 1L, """{"ownerId":"$peerLxmf","winner":"lxmf"}"""))

            val settings = Settings(db)
            ServiceMigrations.migrateLegacyPulseIdentities(db, settings, selfPubB64)

            assertEquals("New Name", db.contacts().allNow().single { it.identity == peerLxmf }.displayName)
            assertEquals("Newer Legacy Name", db.contacts().allNow().single { it.identity == peer2Lxmf }.displayName)
            assertEquals(0, db.contacts().count(peerLegacy))
            assertEquals(0, db.contacts().count(peer2Legacy))

            assertEquals(10L, db.inbox().sent(peerLxmf, "daily/2026-11-10")?.version)
            assertEquals("new-h", db.inbox().sent(peerLxmf, "daily/2026-11-10")?.contentHash)
            assertEquals(12L, db.inbox().sent(peerLxmf, "daily/2026-11-11")?.version)
            assertEquals("legacy-newer-h", db.inbox().sent(peerLxmf, "daily/2026-11-11")?.contentHash)

            val rx1 = assertNotNull(db.inbox().received(peerLxmf, "daily", "2026-11-10"))
            assertEquals(8L, rx1.version)
            assertTrue(""""fresh":true""" in rx1.payload)
            assertTrue(peerLxmf in rx1.payload)

            val rx2 = assertNotNull(db.inbox().received(peerLxmf, "daily", "2026-11-11"))
            assertEquals(9L, rx2.version)
            assertTrue(""""winner":"legacy"""" in rx2.payload)
            assertTrue(peerLxmf in rx2.payload)
            assertFalse(peerLegacy in rx2.payload)
        }
    }

    @Test
    fun `R2-4 Settings put updates cache only after DB write succeeds`() {
        withTempDb { _, db ->
            val settings = Settings(db)
            settings.put("k1", "v1")
            assertEquals("v1", settings.getRaw("k1"))

            // Force DB failure by closing database before put
            db.close()
            var threw = false
            try {
                settings.put("k1", "v2-uncommitted")
            } catch (_: Throwable) {
                threw = true
            }
            assertTrue(threw)
            assertEquals("v1", settings.getRaw("k1"))
        }
    }

    @Test
    fun `R2-6 deriveSelfIdentityFromSecrets matches deriveDeliveryHashes without starting bridge`() {
        val (privB64, pubB64) = testPublicKey(55)
        val (expectedLxmf, _) = ServiceMigrations.deriveDeliveryHashes(pubB64)
        // Verify deterministic key derivation using the same algorithm as ReticulumBridge
        val privBytes = Base64.getDecoder().decode(privB64)
        val edPub = Ed25519PrivateKeyParameters(privBytes, 0).generatePublicKey()
        val xSeed = MessageDigest.getInstance("SHA-256").digest(privBytes)
        val xPub = X25519PrivateKeyParameters(xSeed, 0).generatePublicKey()
        val derivedPubB64 = Base64.getEncoder().encodeToString(xPub.encoded + edPub.encoded)
        assertEquals(pubB64, derivedPubB64)
        assertEquals(expectedLxmf, ServiceMigrations.deriveDeliveryHashes(derivedPubB64).first)
    }

    @Test
    fun `NEW-12 strips fabricated 1km laps and doubles raw stride cadence on garmin activities while preserving real laps`() {
        val dist = 5200.0
        val moving = 1560.0
        val basePace = moving / (dist / 1000.0)
        val startMs = 1_700_000_000_000L
        var tAcc = startMs
        val fakeLaps = (1..5).map { k ->
            val dur = basePace * (1.0 + sin(k * 1.3) * 0.035)
            val s = tAcc
            val e = tAcc + (dur * 1000).toLong()
            tAcc = e
            ActivityLap(
                index = k,
                startUtcMs = s,
                endUtcMs = e,
                durationS = dur,
                distanceM = 1000.0,
                avgHeartRate = 150,
                maxHeartRate = 175,
                avgSpeedMps = 1000.0 / dur,
                ascentM = 10.0,
            )
        }
        val garminFakeAct = Activity(
            id = "garmin-999",
            provider = Provider.GARMIN_PERSONAL,
            accountId = "garmin-main",
            externalId = "999",
            sport = Sport.RUNNING,
            startUtcMs = startMs,
            zoneId = "Europe/Prague",
            totalTimeS = moving,
            movingTimeS = moving,
            distanceM = dist,
            avgCadence = 172.0,
            avgPowerW = null,
            powerZoneTimesS = listOf(10.0, 20.0),
            laps = fakeLaps,
        )
        val rawStrideTrack = listOf(
            TrackPoint(tMs = startMs, lat = 45.0, lon = 15.0, cadence = 86, staminaPct = 90.0, performanceCondition = 2),
            TrackPoint(tMs = startMs + 1000, lat = 45.001, lon = 15.001, cadence = 85, staminaPotentialPct = 95.0),
        )

        val (cleanedAct, cleanedTrack) = ServiceMigrations.cleanLegacyFabricatedActivity(garminFakeAct, rawStrideTrack)
        assertTrue(cleanedAct.laps.isEmpty(), "Fabricated 1km laps must be stripped for garmin-*")
        assertTrue(cleanedAct.powerZoneTimesS.isEmpty())
        assertEquals(listOf(172, 170), cleanedTrack.map { it.cadence })
        assertTrue(cleanedTrack.all { it.staminaPct == null && it.staminaPotentialPct == null && it.performanceCondition == null })

        // Real FIT lap (with variable distance / descentM) must NOT be stripped
        val realLap = ActivityLap(
            index = 1,
            startUtcMs = startMs,
            endUtcMs = startMs + 1_560_000L,
            durationS = 1560.0,
            distanceM = 5200.0,
            descentM = 45.0,
        )
        val realAct = garminFakeAct.copy(id = "garmin-1000", laps = listOf(realLap))
        val (keptAct, _) = ServiceMigrations.cleanLegacyFabricatedActivity(realAct, emptyList())
        assertEquals(1, keptAct.laps.size)
    }

    @Test
    fun `N-14 batched migrateLegacyFabricatedActivities processes 60 activities in 25-row batches and is idempotent`() {
        withTempDb { _, db ->
            val trackSerializer = kotlinx.serialization.builtins.ListSerializer(TrackPoint.serializer())
            val startMs = 1_700_000_000_000L
            val dist = 3_100.0
            val moving = 930.0
            val basePace = moving / (dist / 1000.0)
            var tAcc = startMs
            val fakeLaps = (1..3).map { k ->
                val dur = basePace * (1.0 + sin(k * 1.3) * 0.035)
                val s = tAcc
                val e = tAcc + (dur * 1000).toLong()
                tAcc = e
                ActivityLap(k, s, e, dur, distanceM = 1000.0)
            }
            for (i in 1..60) {
                val act = Activity(
                    id = "garmin-$i",
                    provider = Provider.GARMIN_PERSONAL,
                    accountId = "garmin-main",
                    externalId = "$i",
                    sport = Sport.RUNNING,
                    startUtcMs = startMs + i * 100_000L,
                    zoneId = "Europe/Prague",
                    totalTimeS = moving,
                    movingTimeS = moving,
                    distanceM = dist,
                    avgCadence = 170.0,
                    avgPowerW = null,
                    powerZoneTimesS = listOf(5.0, 10.0),
                    laps = fakeLaps,
                )
                db.activities().upsert(act.toRow(Wire.json.encodeToString(Activity.serializer(), act)))
                val track = listOf(TrackPoint(tMs = act.startUtcMs, lat = 45.0, lon = 15.0, cadence = 85, staminaPct = 80.0))
                db.activities().upsertTrack(TrackRow(act.id, Wire.json.encodeToString(trackSerializer, track)))
            }

            val settings = Settings(db)
            val progressSteps = mutableListOf<Pair<Int, Int>>()
            val fitDir = File.createTempFile("fit-dir-", "").apply { delete(); mkdirs() }
            try {
                val cleaned = ServiceMigrations.migrateLegacyFabricatedActivities(
                    db = db,
                    settings = settings,
                    fitDir = fitDir,
                    decryptFit = { it },
                    batchSize = 25,
                    onProgress = { processed, total -> progressSteps += processed to total },
                )
                assertEquals(120, cleaned) // 60 activities + 60 tracks
                assertEquals(listOf(25 to 60, 50 to 60, 60 to 60), progressSteps)
                assertEquals("1", settings.getRaw("migration.fabricated_activity_cleanup_v2"))

                val sample = Wire.json.decodeFromString(Activity.serializer(), db.activities().get("garmin-1")!!.json)
                assertTrue(sample.laps.isEmpty())
                assertTrue(sample.powerZoneTimesS.isEmpty())
                val sampleTrack = Wire.json.decodeFromString(trackSerializer, db.activities().track("garmin-1")!!.json)
                assertEquals(170, sampleTrack.single().cadence)
                assertNull(sampleTrack.single().staminaPct)

                // Second run skips immediately
                assertEquals(
                    0,
                    ServiceMigrations.migrateLegacyFabricatedActivities(
                        db = db,
                        settings = settings,
                        fitDir = fitDir,
                        decryptFit = { it },
                        batchSize = 25,
                    )
                )
            } finally {
                fitDir.deleteRecursively()
            }
        }
    }

    /** Lightweight SQLiteDriver backed by org.xerial:sqlite-jdbc so Room runs real SQLite in JVM unit tests. */
    private class JdbcSqliteDriver : SQLiteDriver {
        override fun open(fileName: String): SQLiteConnection {
            val url = if (fileName == ":memory:") "jdbc:sqlite::memory:" else "jdbc:sqlite:$fileName"
            val conn = DriverManager.getConnection(url)
            return JdbcSqliteConnection(conn)
        }
    }

    private class JdbcSqliteConnection(private val conn: java.sql.Connection) : SQLiteConnection {
        private var inTx = false

        override fun inTransaction(): Boolean = inTx

        override fun prepare(sql: String): SQLiteStatement {
            val upper = sql.trimStart().uppercase()
            return JdbcSqliteStatement(conn.prepareStatement(sql), onStep = {
                when {
                    upper.startsWith("BEGIN") -> inTx = true
                    upper.startsWith("COMMIT") || upper.startsWith("END") || upper.startsWith("ROLLBACK") -> inTx = false
                }
            })
        }

        override fun close() {
            conn.close()
        }
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

        override fun getColumnCount(): Int {
            if (cachedColumnNames == null) executeAndCacheColumns()
            return cachedColumnNames?.size ?: 0
        }

        override fun getColumnName(index: Int): String {
            if (cachedColumnNames == null) executeAndCacheColumns()
            return cachedColumnNames?.getOrNull(index) ?: ""
        }

        override fun getColumnType(index: Int): Int {
            val v = rs?.getObject(index + 1) ?: return 5 // SQLITE_NULL
            return when (v) {
                is Int, is Long -> 1 // SQLITE_INTEGER
                is Float, is Double -> 2 // SQLITE_FLOAT
                is ByteArray -> 4 // SQLITE_BLOB
                else -> 3 // SQLITE_TEXT
            }
        }

        override fun step(): Boolean {
            if (!executed) {
                executeAndCacheColumns()
                firstStepPending = false
                return hasPendingFirstRow
            }
            if (firstStepPending) {
                firstStepPending = false
                return hasPendingFirstRow
            }
            if (eof) return false
            val next = rs?.next() == true
            if (!next) eof = true
            return next
        }

        override fun reset() {
            rs?.close()
            rs = null
            cachedColumnNames = null
            executed = false
            firstStepPending = false
            hasPendingFirstRow = false
            eof = false
        }

        override fun clearBindings() {
            ps.clearParameters()
        }

        override fun close() {
            rs?.close()
            rs = null
            ps.close()
        }
    }
}
