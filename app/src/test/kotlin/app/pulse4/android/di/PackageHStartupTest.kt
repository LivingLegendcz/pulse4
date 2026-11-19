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
import app.pulse4.android.data.FamilyLinkRow
import app.pulse4.android.data.GroupRow
import app.pulse4.android.data.OutboxRow
import app.pulse4.android.data.RoomOutboxStore
import app.pulse4.android.data.RoomReceiverStore
import app.pulse4.android.data.RoomSentLedger
import app.pulse4.android.data.SettingRow
import app.pulse4.android.data.Settings
import app.pulse4.android.reticulum.GatewayHealthState
import app.pulse4.android.security.KeyVault
import app.pulse4.android.security.OperationalStateStore
import app.pulse4.android.security.SecretRecoveryState
import app.pulse4.android.security.SecretSealer
import app.pulse4.android.security.SecretStore
import app.pulse4.sharing.AdminHandover
import app.pulse4.sharing.AgeProfile
import app.pulse4.sharing.FamilyLink
import app.pulse4.sharing.FamilyLinkStatus
import app.pulse4.sharing.Group
import app.pulse4.sync.BuiltInHandlers
import app.pulse4.sync.DeliveryState
import app.pulse4.sync.Kinds
import app.pulse4.sync.MessageBuilder
import app.pulse4.sync.Outcome
import app.pulse4.sync.OutboxManager
import app.pulse4.sync.Receiver
import app.pulse4.sync.Wire
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

class PackageHStartupTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        SecretRecoveryState.clear()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun newTempDir(): File = Files.createTempDirectory("pulse4-pkg-h-").toFile().also { tempDirs += it }

    private fun testKeyPair(seedByte: Byte): Pair<String, String> {
        val privBytes = ByteArray(32) { (it + seedByte.toInt()).toByte() }
        val privB64 = Base64.getEncoder().encodeToString(privBytes)
        val edPub = Ed25519PrivateKeyParameters(privBytes, 0).generatePublicKey()
        val xSeed = MessageDigest.getInstance("SHA-256").digest(privBytes)
        val xPub = X25519PrivateKeyParameters(xSeed, 0).generatePublicKey()
        val pubB64 = Base64.getEncoder().encodeToString(xPub.encoded + edPub.encoded)
        return privB64 to pubB64
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

    private fun withTempDb(block: (db: AppDatabase) -> Unit) {
        val dbFile = File.createTempFile("pulse4-pkgh-db-", ".db")
        dbFile.delete()
        val db = openTestDb(dbFile)
        try {
            block(db)
        } finally {
            runCatching { db.close() }
            dbFile.delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
        }
    }

    @Test
    fun `H6a gatewayHealth and bridge return immediately without blocking on in-flight awaitMigrations`() {
        val dir = newTempDir()
        withTempDb { db ->
            val sl = ServiceLocator(testContext(dir), dbOverride = db)
            val migrationStarted = CountDownLatch(1)
            val releaseMigration = CountDownLatch(1)
            val migrationCalls = AtomicInteger(0)

            sl.migrationRunner = {
                migrationCalls.incrementAndGet()
                migrationStarted.countDown()
                assertTrue(releaseMigration.await(5, TimeUnit.SECONDS))
            }

            // 1. Observing gatewayHealth before migrations start does not trigger bridge or migration.
            assertEquals(GatewayHealthState.UNCONFIGURED, sl.gatewayHealth.value.state)
            assertEquals(0, migrationCalls.get())
            assertFalse(sl.isMigrationsCompleted())

            runBlocking {
                // 2. Start awaitMigrations() in background (simulating Pulse4App.onCreate) and pause it mid-flight.
                val worker1 = async(Dispatchers.IO) { sl.awaitMigrations() }
                val worker2 = async(Dispatchers.IO) { sl.awaitMigrations() }
                assertTrue(migrationStarted.await(5, TimeUnit.SECONDS))

                // 3. Simulated main-thread access to gatewayHealth & bridge must return immediately without blocking on migration!
                val startNs = System.nanoTime()
                val healthFlow = sl.gatewayHealth
                val bridgeInstance = sl.bridge
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
                assertTrue(elapsedMs < 500, "Main-thread access took ${elapsedMs}ms")
                assertEquals(GatewayHealthState.UNCONFIGURED, healthFlow.value.state)

                // 4. Bridge health updates flow into ServiceLocator.gatewayHealth once bridge exists.
                bridgeInstance.reportGatewayUnreachableForTest("gw.example.invalid", 4242)
                assertEquals(GatewayHealthState.UNREACHABLE, sl.gatewayHealth.value.state)
                assertEquals("gw.example.invalid", sl.gatewayHealth.value.host)

                // 5. Release migration and verify all concurrent awaitMigrations() callers finish with exactly 1 migration run.
                releaseMigration.countDown()
                awaitAll(worker1, worker2)
                assertTrue(sl.isMigrationsCompleted())
                assertEquals(1, migrationCalls.get())

                // 6. Subsequent awaitMigrations() calls return without re-running migration.
                sl.awaitMigrations()
                assertEquals(1, migrationCalls.get())
            }
        }
    }

    @Test
    fun `R3-14 F6 and F7 check flags before Keystore decrypt and defer when selfIdentity is null`() {
        val dir = newTempDir()
        val (_, peerPubB64) = testKeyPair(42)
        val (peerLxmf, peerLegacy) = ServiceMigrations.deriveDeliveryHashes(peerPubB64)

        val sealerCalls = AtomicInteger(0)
        val countingSealer = object : SecretSealer {
            override fun seal(plain: ByteArray): ByteArray = ByteArray(12) { 1 } + plain + ByteArray(16) { 2 }
            override fun open(sealed: ByteArray): ByteArray {
                sealerCalls.incrementAndGet()
                return sealed.copyOfRange(12, sealed.size - 16)
            }
            override fun hasMasterKey(): Boolean = true
        }

        withTempDb { db ->
            val sl = ServiceLocator(testContext(dir), dbOverride = db, sealerOverride = countingSealer)
            db.contacts().upsert(ContactRow(peerLegacy, "Peer", peerPubB64, "fp", verifiedAtMs = 1_000L))

            // F6: When selfIdentity is null, runOnce defers migration and flag MUST NOT be set.
            ServiceMigrations.runOnce(sl)
            assertNull(sl.settings.getRaw("migration.lxmf_identity_v1"))
            assertEquals(1, db.contacts().count(peerLegacy))

            // Once selfIdentity becomes available, runOnce migrates contact & sets flags.
            val (selfPrivB64, _) = testKeyPair(10)
            sl.secrets.putString(SecretStore.RETICULUM_IDENTITY, selfPrivB64)
            ServiceMigrations.runOnce(sl)
            assertEquals("1", sl.settings.getRaw("migration.lxmf_identity_v1"))
            assertEquals("1", sl.settings.getRaw("migration.accept_weight_default_v1"))
            assertEquals("1", sl.settings.getRaw("migration.r3_5_reemit_v1"))
            assertEquals(1, db.contacts().count(peerLxmf))
            assertEquals(0, db.contacts().count(peerLegacy))

            // F7: When all migration flags are set, runOnce avoids touching SecretStore/Keystore completely.
            val callsBefore = sealerCalls.get()
            ServiceMigrations.runOnce(sl)
            assertEquals(callsBefore, sealerCalls.get())
        }
    }

    @Test
    fun `R3-13 Settings warmUp and reloadFromDb stay consistent with migrated DB values`() {
        val (_, selfPubB64) = testKeyPair(10)
        val (_, peerPubB64) = testKeyPair(42)
        val (peerLxmf, peerLegacy) = ServiceMigrations.deriveDeliveryHashes(peerPubB64)

        withTempDb { db ->
            val settings = Settings(db)
            db.contacts().upsert(ContactRow(peerLegacy, "Child", peerPubB64, "fp", verifiedAtMs = 1_000L))
            db.settings().put(SettingRow("childRecipientIdentity", peerLegacy))
            runBlocking { settings.warmUp() }
            assertEquals(peerLegacy, settings.childRecipientIdentity)

            ServiceMigrations.migrateLegacyPulseIdentities(db, settings, selfPubB64)
            runBlocking { settings.warmUp() }
            assertEquals(peerLxmf, settings.childRecipientIdentity)
        }
    }

    @Test
    fun `H6c R3-5 pending FAMILY_LINK_REQUEST and ADMIN_HANDOVER_REQUEST and cancelled MEMBERSHIP_UPDATE are rewritten or re-emitted and accepted by peer Receiver`() {
        val (_, parentPubB64) = testKeyPair(10)
        val (_, childPubB64) = testKeyPair(42)
        val (parentLxmf, parentLegacy) = ServiceMigrations.deriveDeliveryHashes(parentPubB64)
        val (childLxmf, childLegacy) = ServiceMigrations.deriveDeliveryHashes(childPubB64)
        assertNotEquals(parentLxmf, parentLegacy)
        assertNotEquals(childLxmf, childLegacy)

        withTempDb { parentDb ->
            withTempDb { childDb ->
                val parentSettings = Settings(parentDb)
                val childSettings = Settings(childDb)
                val parentOutbox = OutboxManager(RoomOutboxStore(parentDb))
                val parentShare = ShareService(
                    data = RoomPulseDataAccess(parentDb),
                    settings = RealShareSettingsAccess(parentSettings),
                    outbox = parentOutbox,
                    sentLedger = RoomSentLedger(parentDb),
                    recordSent = { r, k, v, h -> parentDb.inbox().putSent(app.pulse4.android.data.SentRow(r, k, v, h)) },
                    selfIdProvider = { parentLxmf },
                )

                // Parent DB upgraded from 0.1.3 (and also simulates prior buggy migration having cancelled outbox items!)
                parentDb.contacts().upsert(ContactRow(childLegacy, "Child", childPubB64, "fp-child", verifiedAtMs = 1_000L))
                val link = FamilyLink(
                    id = "fl-pending-1",
                    parentId = parentLegacy,
                    childId = childLegacy,
                    childAgeProfile = AgeProfile.MINOR,
                    status = FamilyLinkStatus.PENDING,
                    version = 1L,
                    createdAtMs = 1_000L,
                )
                parentDb.familyLinks().upsert(
                    FamilyLinkRow(link.id, parentLegacy, childLegacy, FamilyLinkStatus.PENDING.name, 1L, Wire.json.encodeToString(FamilyLink.serializer(), link))
                )
                val cancelledLinkMsg = MessageBuilder(parentLegacy) { "ev-cancelled-link" }.build(
                    recipient = childLegacy,
                    kind = Kinds.FAMILY_LINK_REQUEST,
                    objectType = "family_link",
                    objectId = link.id,
                    objectVersion = link.version,
                    payload = Wire.json.encodeToString(FamilyLink.serializer(), link),
                    nowMs = 1_000L,
                )
                parentDb.outbox().insert(
                    OutboxRow(
                        eventId = cancelledLinkMsg.envelope.eventId,
                        recipient = childLegacy,
                        kind = Kinds.FAMILY_LINK_REQUEST,
                        objectType = "family_link",
                        objectId = link.id,
                        objectVersion = link.version,
                        grantId = null,
                        wire = Wire.encode(cancelledLinkMsg),
                        createdAtMs = 1_000L,
                        state = DeliveryState.CANCELLED.name,
                        attempts = 0,
                        nextAttemptAtMs = 0L,
                        lastError = null,
                    )
                )

                val group = Group(
                    id = "grp-handover-1",
                    name = "Rodina",
                    ownerId = parentLegacy,
                    adminId = parentLegacy,
                    members = setOf(parentLegacy, childLegacy),
                    version = 2L,
                    admins = setOf(parentLegacy),
                    pendingAdminHandover = AdminHandover(
                        targetMemberId = childLegacy,
                        initiatedBy = parentLegacy,
                        initiatedAtMs = 1_000L,
                        expiresAtMs = 999_999_999_999L,
                    ),
                )
                parentDb.grants().upsertGroup(
                    GroupRow(group.id, group.version, Wire.json.encodeToString(Group.serializer(), group))
                )
                val cancelledGrpMsg = MessageBuilder(parentLegacy) { "ev-cancelled-grp" }.build(
                    recipient = childLegacy,
                    kind = Kinds.MEMBERSHIP_UPDATE,
                    objectType = "group",
                    objectId = group.id,
                    objectVersion = group.version,
                    payload = Wire.json.encodeToString(Group.serializer(), group),
                    nowMs = 1_100L,
                )
                parentDb.outbox().insert(
                    OutboxRow(
                        eventId = cancelledGrpMsg.envelope.eventId,
                        recipient = childLegacy,
                        kind = Kinds.MEMBERSHIP_UPDATE,
                        objectType = "group",
                        objectId = group.id,
                        objectVersion = group.version,
                        grantId = null,
                        wire = Wire.encode(cancelledGrpMsg),
                        createdAtMs = 1_100L,
                        state = DeliveryState.CANCELLED.name,
                        attempts = 0,
                        nextAttemptAtMs = 0L,
                        lastError = null,
                    )
                )

                // Run identity migration + R3-5 re-emit
                ServiceMigrations.migrateLegacyPulseIdentities(parentDb, parentSettings, parentPubB64)
                val reemitted = ServiceMigrations.reemitPendingControlMessages(
                    db = parentDb,
                    selfId = parentLxmf,
                    nowMs = 2_000L,
                    emitFamilyLinkRequest = { l, ts -> parentShare.emitFamilyLinkRequest(l, ts, selfOverride = parentLxmf) },
                    emitAdminHandoverRequest = { g, target, ts -> parentShare.emitAdminHandoverRequest(g, target, ts, selfOverride = parentLxmf) },
                    broadcastMembershipUpdate = { g, missing, ts ->
                        parentShare.broadcastMembershipUpdate(g.copy(members = missing + parentLxmf), ts, selfOverride = parentLxmf)
                    },
                )
                assertTrue(reemitted >= 3)

                // Verify outbox now contains QUEUED items with new lxmf.delivery addresses & valid hashes
                val due = parentOutbox.due(2_000L)
                assertEquals(
                    setOf(Kinds.FAMILY_LINK_REQUEST, Kinds.MEMBERSHIP_UPDATE, Kinds.ADMIN_HANDOVER_REQUEST),
                    due.map { it.kind }.toSet(),
                )
                assertTrue(due.all { it.recipient == childLxmf })

                // Deliver over real Receiver on child device (already migrated to lxmf.delivery)
                childDb.contacts().upsert(ContactRow(parentLxmf, "Parent", parentPubB64, "fp-parent", verifiedAtMs = 1_000L))
                val childReceiver = Receiver(
                    selfId = childLxmf,
                    store = RoomReceiverStore(childDb),
                    handlers = BuiltInHandlers.all() + buildFamilyAndWeightHandlers(
                        RoomPulseDataAccess(childDb),
                        RealShareSettingsAccess(childSettings),
                    ) + mapOf(
                        Kinds.ADMIN_HANDOVER_REQUEST to app.pulse4.sync.KindHandler { env, payload, store, nowMs ->
                            val g = Wire.json.decodeFromString(Group.serializer(), payload)
                            store.putGroup(g)
                            app.pulse4.sync.HandlerResult.Applied
                        }
                    ),
                    ackSink = { _, _ -> },
                    builder = MessageBuilder(childLxmf),
                )

                for (item in due) {
                    val outcome = childReceiver.receive(parentLxmf, item.wire, 2_500L)
                    assertTrue(outcome is Outcome.Applied, "Expected Applied for ${item.kind}, got $outcome")
                }

                // Child DB now has the pending family link and migrated group with new lxmf.delivery addresses
                val rxLink = assertNotNull(childDb.familyLinks().get(link.id))
                assertEquals(parentLxmf, rxLink.parentId)
                assertEquals(childLxmf, rxLink.childId)
                assertEquals(FamilyLinkStatus.PENDING.name, rxLink.status)
                val rxGroup = Wire.json.decodeFromString(Group.serializer(), childDb.grants().group(group.id)!!.json)
                assertEquals(parentLxmf, rxGroup.ownerId)
                assertEquals(childLxmf, rxGroup.pendingAdminHandover?.targetMemberId)
            }
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
