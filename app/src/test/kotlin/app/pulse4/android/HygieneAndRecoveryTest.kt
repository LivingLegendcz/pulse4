package app.pulse4.android

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import app.pulse4.android.data.AppDatabase
import app.pulse4.android.data.ExcludedRow
import app.pulse4.android.data.ObservationRow
import app.pulse4.android.di.ImportService
import app.pulse4.android.security.CorruptedSecretException
import app.pulse4.android.security.CriticalSecretKind
import app.pulse4.android.security.KeyVault
import app.pulse4.android.security.OperationalStateStore
import app.pulse4.android.security.SecretRecoveryState
import app.pulse4.android.security.SecretSealer
import app.pulse4.android.security.SecretStore
import app.pulse4.android.security.TransientKeystoreException
import app.pulse4.android.ui.resolveRecoveryActions
import app.pulse4.domain.ImportKey
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import java.io.File
import java.nio.file.Files
import java.security.KeyStoreException
import java.security.ProviderException
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.crypto.AEADBadTagException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HygieneAndRecoveryTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        SecretRecoveryState.clear()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun newTempDir(): File = Files.createTempDirectory("pulse4-pkg-g-").toFile().also { tempDirs += it }

    private class MapOperationalStore : OperationalStateStore {
        private val data = LinkedHashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String) { data[key] = value }
        override fun delete(key: String) { data.remove(key) }
    }

    private class FakeSealer(
        var failOpen: Boolean = false,
        var masterPresent: Boolean = true,
        var customError: (() -> Throwable)? = null,
    ) : SecretSealer {
        override fun seal(plain: ByteArray): ByteArray = ByteArray(12) { 1 } + plain + ByteArray(16) { 2 }
        override fun open(sealed: ByteArray): ByteArray {
            customError?.invoke()?.let { throw it }
            if (failOpen) throw AEADBadTagException("Tag mismatch")
            require(sealed.size > 28)
            return sealed.copyOfRange(12, sealed.size - 16)
        }
        override fun hasMasterKey(): Boolean = masterPresent
    }

    @Test
    fun `NEW-11 corrupted db key records DB_KEY recovery issue and throws CorruptedSecretException`() {
        val dir = newTempDir()
        val dbFile = File(dir, "pulse4.db").apply { writeText("encrypted-db") }
        val sealer = FakeSealer(failOpen = false)
        val vault = KeyVault(dir, { dbFile }, sealer)
        val secrets = SecretStore(dir, vault, MapOperationalStore())

        // Seed valid db.key
        val initialPass = vault.databasePassphrase()
        assertEquals(32, initialPass.size)
        assertNull(vault.probeCriticalSecrets(secrets))

        // Corrupt / invalidate AEAD key
        sealer.failOpen = true
        assertFailsWith<CorruptedSecretException> { vault.databasePassphrase() }
        val issue = vault.recoveryIssue.value
        assertNotNull(issue)
        assertEquals(CriticalSecretKind.DB_KEY, issue.kind)
        assertEquals(KeyVault.DB_KEY_NAME, issue.secretName)

        // Probe must report without throwing unhandled
        assertEquals(issue, vault.probeCriticalSecrets(secrets))

        // Recovery reset clears issue and lets a fresh db.key be created
        sealer.failOpen = false
        vault.resetCorruptedForRecovery(secrets, issue, resetDatabaseToo = true)
        assertNull(vault.recoveryIssue.value)
        assertFalse(dbFile.exists())
        assertEquals(32, vault.databasePassphrase().size)
    }

    @Test
    fun `NEW-11 corrupted reticulum identity records RETICULUM_IDENTITY issue and non-critical secrets still return null`() {
        val dir = newTempDir()
        val sealer = FakeSealer(failOpen = false)
        val vault = KeyVault(dir, { null }, sealer)
        val secrets = SecretStore(dir, vault, MapOperationalStore())

        secrets.putString(SecretStore.RETICULUM_IDENTITY, "ed25519-priv-b64")
        secrets.putString(SecretStore.GARMIN_API_KEY, "api-key-123")

        sealer.failOpen = true
        // Non-critical secret still returns null on corruption
        assertNull(secrets.getString(SecretStore.GARMIN_API_KEY))
        assertNull(vault.recoveryIssue.value)

        // Critical identity throws CorruptedSecretException and populates recovery state
        assertFailsWith<CorruptedSecretException> { secrets.getString(SecretStore.RETICULUM_IDENTITY) }
        val issue = vault.probeCriticalSecrets(secrets)
        assertNotNull(issue)
        assertEquals(CriticalSecretKind.RETICULUM_IDENTITY, issue.kind)
        assertEquals(SecretStore.RETICULUM_IDENTITY, issue.secretName)

        // Reset allows fresh identity creation
        sealer.failOpen = false
        vault.resetCorruptedForRecovery(secrets, issue)
        assertNull(vault.recoveryIssue.value)
        assertNull(secrets.getString(SecretStore.RETICULUM_IDENTITY))
    }

    @Test
    fun `NEW-11 Garmin and Withings cooldown counters survive AEAD corruption and never bypass on legacy corruption`() {
        val dir = newTempDir()
        val sealer = FakeSealer(failOpen = false)
        val vault = KeyVault(dir, { null }, sealer)
        val opStore = MapOperationalStore()
        var now = 1_000_000L
        val secrets = SecretStore(dir, vault, opStore) { now }

        // 1. Operational cooldowns stored outside AEAD survive Keystore/AEAD failure
        secrets.putString("garmin.ext.login_failures", "2")
        secrets.putString("garmin.ext.login_cooldown_until_ms", (now + 900_000L).toString())
        secrets.putString("withings.auth.paused_reason", "RATE_LIMITED")
        sealer.failOpen = true
        assertEquals("2", secrets.getString("garmin.ext.login_failures"))
        assertEquals((now + 900_000L).toString(), secrets.getString("garmin.ext.login_cooldown_until_ms"))
        assertEquals("RATE_LIMITED", secrets.getString("withings.auth.paused_reason"))

        // 2. Legacy sealed cooldown file that is corrupted returns conservative cooldown instead of null
        val legacyDir = File(dir, "secrets").apply { mkdirs() }
        File(legacyDir, "garmin.ext.refresh_blocked_until_ms").writeBytes(ByteArray(40) { 9 })
        File(legacyDir, "withings.auth.other_failures").writeBytes(ByteArray(40) { 9 })

        val blockedUntil = secrets.getString("garmin.ext.refresh_blocked_until_ms")?.toLongOrNull() ?: 0L
        assertTrue(blockedUntil >= now + SecretStore.CONSERVATIVE_COOLDOWN_MS, "expected conservative cooldown, got $blockedUntil")
        assertEquals("3", secrets.getString("withings.auth.other_failures"))
    }

    @Test
    fun `NEW-10 owned app main files use PLog with zero direct android util Log calls`() {
        val root = File("..").takeIf { File("../app/src/main").isDirectory } ?: File(".")
        val ownedFiles = listOf(
            "app/src/main/kotlin/app/pulse4/android/ui/Common.kt",
            "app/src/main/kotlin/app/pulse4/android/work/PulseSyncService.kt",
            "app/src/main/kotlin/app/pulse4/android/ui/MainActivity.kt",
            "app/src/main/kotlin/app/pulse4/android/work/Workers.kt",
            "app/src/main/kotlin/app/pulse4/android/di/HealthConnectSource.kt",
            "app/src/main/kotlin/app/pulse4/android/security/KeyVault.kt",
        )
        val directLogRegex = Regex("""(\bandroid\.util\.Log\b|\bLog\.[vdiwe]\()""")
        for (rel in ownedFiles) {
            val f = File(root, rel)
            assertTrue(f.exists(), "Missing owned file $rel")
            val text = f.readText()
            assertFalse(directLogRegex.containsMatchIn(text), "Direct android.util.Log call found in $rel")
        }
        val appFile = File(root, "app/src/main/kotlin/app/pulse4/android/Pulse4App.kt").readText()
        assertTrue(appFile.contains("""Log.e(tag, "[WARN] """), "Pulse4App must route release PLog.w through Log.e")
    }

    @Test
    fun `NEW-8 and R2-7 hygiene checks for synthetic coords, health values, and E2EE terminology`() {
        val root = File("..").takeIf { File("../docs/samples").isDirectory } ?: File(".")
        val demoSeeder = File(root, "app/src/debug/kotlin/app/pulse4/android/debug/DemoSeeder.kt").readText()
        val debugReceiver = File(root, "app/src/debug/kotlin/app/pulse4/android/debug/DebugCommandReceiver.kt").readText()
        for (realCoord in listOf("45.0947", "15.0439", "45.0825", "15.0280", "45.0755", "15.0378")) {
            assertFalse(demoSeeder.contains(realCoord), "Real coord $realCoord still in DemoSeeder.kt")
            assertFalse(debugReceiver.contains(realCoord), "Real coord $realCoord still in DebugCommandReceiver.kt")
        }

        val fitnessAge = File(root, "docs/samples/fitnessage.json").readText()
        assertFalse(fitnessAge.contains(""""chronologicalAge": 39"""))
        assertFalse(fitnessAge.contains(""""value": 29.2"""))
        assertFalse(fitnessAge.contains(""""value": 50"""))

        val e2ee = File(root, "sync-protocol/src/main/kotlin/app/pulse4/sync/E2eeCrypto.kt").readText()
        assertFalse(e2ee.contains("forward secrecy", ignoreCase = true))
        assertFalse(e2ee.contains("forward-secret", ignoreCase = true))
        assertTrue(e2ee.contains("ephemeral-static ECIES"))

        val readmeEn = File(root, "README.md").readText()
        val readmeCs = File(root, "README.cs.md").readText()
        assertTrue(readmeEn.contains("ephemeral-static ECIES"))
        assertTrue(readmeCs.contains("ephemeral-static ECIES"))
    }

    @Test
    fun `R3-8 transient Keystore failures offer retry only and recover without deleting identity or db`() {
        val dir = newTempDir()
        val dbFile = File(dir, "pulse4.db").apply { writeText("encrypted-db") }
        val sealer = FakeSealer()
        val vault = KeyVault(dir, { dbFile }, sealer)
        val secrets = SecretStore(dir, vault, MapOperationalStore())

        val pass = vault.databasePassphrase()
        secrets.putString(SecretStore.RETICULUM_IDENTITY, "ed25519-priv-b64")
        secrets.putString(SecretStore.GARMIN_API_KEY, "api-key-123")

        // 1. KeyStoreException / ProviderException on db.key -> TransientKeystoreException + recoverable=true
        sealer.customError = { KeyStoreException("Keystore locked") }
        assertFailsWith<TransientKeystoreException> { vault.databasePassphrase() }
        val transientIssue = vault.recoveryIssue.value
        assertNotNull(transientIssue)
        assertTrue(transientIssue.recoverable)
        val actions = resolveRecoveryActions(transientIssue)
        assertTrue(actions.showRetryOnly)
        assertFalse(actions.allowIdentityReset)
        assertFalse(actions.allowDatabaseReset)
        assertFalse(actions.allowBackupRestore)

        // resetCorruptedForRecovery refuses to delete anything for a recoverable issue
        vault.resetCorruptedForRecovery(secrets, transientIssue, resetDatabaseToo = true, resetIdentityToo = true)
        assertTrue(dbFile.exists())
        assertTrue(secrets.has(SecretStore.RETICULUM_IDENTITY))

        // 2. ProviderException on identity -> recoverable=true; non-critical secret returns null without touching file
        SecretRecoveryState.clear()
        sealer.customError = { ProviderException("Keymaster busy") }
        assertNull(secrets.getString(SecretStore.GARMIN_API_KEY))
        assertFailsWith<TransientKeystoreException> { secrets.getString(SecretStore.RETICULUM_IDENTITY) }
        val idTransient = vault.recoveryIssue.value
        assertNotNull(idTransient)
        assertTrue(idTransient.recoverable)
        assertEquals(CriticalSecretKind.RETICULUM_IDENTITY, idTransient.kind)

        // 3. When Keystore becomes available again, retryCriticalSecrets clears issue and preserves everything
        sealer.customError = null
        assertNull(vault.retryCriticalSecrets(secrets))
        assertNull(vault.recoveryIssue.value)
        assertEquals(pass.toList(), vault.databasePassphrase().toList())
        assertEquals("ed25519-priv-b64", secrets.getString(SecretStore.RETICULUM_IDENTITY))
        assertEquals("api-key-123", secrets.getString(SecretStore.GARMIN_API_KEY))
    }

    @Test
    fun `R3-8 AEADBadTagException reset deletes only the corrupted secret and never wipes identity for db key corruption`() {
        val dir = newTempDir()
        val dbFile = File(dir, "pulse4.db").apply { writeText("encrypted-db") }
        val sealer = FakeSealer()
        val vault = KeyVault(dir, { dbFile }, sealer)
        val secrets = SecretStore(dir, vault, MapOperationalStore())

        vault.databasePassphrase()
        secrets.putString(SecretStore.RETICULUM_IDENTITY, "ed25519-priv-b64")
        secrets.putString(SecretStore.GARMIN_API_KEY, "api-key-123")

        // Corrupt ONLY db.key with AEADBadTagException
        File(dir, KeyVault.DB_KEY_NAME).writeBytes(ByteArray(32) { 7 })
        sealer.customError = { AEADBadTagException("Corrupted db.key") }
        assertFailsWith<CorruptedSecretException> { vault.databasePassphrase() }
        val dbIssue = vault.recoveryIssue.value!!
        assertFalse(dbIssue.recoverable)
        assertEquals(CriticalSecretKind.DB_KEY, dbIssue.kind)
        val dbActions = resolveRecoveryActions(dbIssue)
        assertFalse(dbActions.showRetryOnly)
        assertFalse(dbActions.allowIdentityReset, "Identity reset must NOT be offered when only db.key is corrupted")
        assertTrue(dbActions.allowDatabaseReset)

        // Resetting db.key MUST preserve RETICULUM_IDENTITY and GARMIN_API_KEY
        vault.resetCorruptedForRecovery(secrets, dbIssue)
        sealer.customError = null
        assertFalse(dbFile.exists())
        assertEquals("ed25519-priv-b64", secrets.getString(SecretStore.RETICULUM_IDENTITY))
        assertEquals("api-key-123", secrets.getString(SecretStore.GARMIN_API_KEY))

        // Now corrupt ONLY RETICULUM_IDENTITY -> allowIdentityReset=true, reset preserves db.key and GARMIN_API_KEY
        val freshDbPass = vault.databasePassphrase()
        sealer.customError = { AEADBadTagException("Corrupted identity") }
        assertFailsWith<CorruptedSecretException> { secrets.getString(SecretStore.RETICULUM_IDENTITY) }
        val idIssue = vault.recoveryIssue.value!!
        assertFalse(idIssue.recoverable)
        assertTrue(resolveRecoveryActions(idIssue).allowIdentityReset)

        vault.resetCorruptedForRecovery(secrets, idIssue)
        sealer.customError = null
        assertNull(secrets.getString(SecretStore.RETICULUM_IDENTITY))
        assertEquals(freshDbPass.toList(), vault.databasePassphrase().toList())
        assertEquals("api-key-123", secrets.getString(SecretStore.GARMIN_API_KEY))
    }

    @Test
    fun `Hygiene all main kotlin sources ban direct android util Log outside PLog and Pulse4App`() {
        val root = File("..").takeIf { File("../app/src/main/kotlin").isDirectory } ?: File(".")
        val allowedFileNames = setOf("Pulse4App.kt", "PLog.kt")
        val directLogRegex = Regex("""(\bandroid\.util\.Log\b|\bLog\.(?:v|d|i|w|e|wtf)\()""")
        val mainSourceDirs = root.listFiles { f -> f.isDirectory && File(f, "src/main/kotlin").isDirectory }
            .orEmpty()
            .map { File(it, "src/main/kotlin") }
        assertTrue(mainSourceDirs.isNotEmpty(), "Expected at least one src/main/kotlin dir under ${root.absolutePath}")

        val violations = mutableListOf<String>()
        for (dir in mainSourceDirs) {
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in allowedFileNames }.forEach { f ->
                val text = f.readText()
                if (directLogRegex.containsMatchIn(text)) {
                    violations += f.relativeTo(root).path
                }
            }
        }
        assertTrue(violations.isEmpty(), "Direct android.util.Log usage found in: $violations")
    }

    @Test
    fun `R3-14 deduplicateBodyBatteryRows never resurrects deleted rows and remaps exclusions in real Room DB`() {
        val dbFile = File.createTempFile("pulse4-bb-dedup-", ".db").apply { delete() }
        val ctx = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext() = this
            override fun getDatabasePath(name: String): File = File(name)
        }
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbFile.absolutePath)
            .setDriver(JdbcSqliteDriver())
            .allowMainThreadQueries()
            .build()

        try {
            val accountId = "acc-1"
            val bbMetric = MetricType.BODY_BATTERY.name
            val prov = Provider.GARMIN_PERSONAL.name

            // Day 1 (2026-11-15): two active duplicates -> newest survives under canonical key
            val d1 = "2026-11-15"
            val d1Canonical = ImportKey.of(Provider.GARMIN_PERSONAL, accountId, null, listOf(bbMetric, d1))
            val d1DupOld = ImportKey.of(Provider.GARMIN_PERSONAL, accountId, null, listOf(bbMetric, d1, "end-100"))
            val d1DupNew = ImportKey.of(Provider.GARMIN_PERSONAL, accountId, null, listOf(bbMetric, d1, "end-200"))

            // Day 2 (2026-11-16): canonical row is deleted=1 while an old duplicate is deleted=0 -> MUST NOT resurrect
            val d2 = "2026-11-16"
            val d2Canonical = ImportKey.of(Provider.GARMIN_PERSONAL, accountId, null, listOf(bbMetric, d2))
            val d2Dup = ImportKey.of(Provider.GARMIN_PERSONAL, accountId, null, listOf(bbMetric, d2, "end-150"))

            // Day 3 (2026-11-17): old duplicate was deleted & excluded, another duplicate sneaked in -> remap exclusion to canonical & stay deleted
            val d3 = "2026-11-17"
            val d3Canonical = ImportKey.of(Provider.GARMIN_PERSONAL, accountId, null, listOf(bbMetric, d3))
            val d3Excluded = ImportKey.of(Provider.GARMIN_PERSONAL, accountId, null, listOf(bbMetric, d3, "end-100"))
            val d3Sneak = ImportKey.of(Provider.GARMIN_PERSONAL, accountId, null, listOf(bbMetric, d3, "end-200"))

            db.observations().upsert(
                listOf(
                    ObservationRow(d1DupOld, prov, accountId, null, bbMetric, 1_000L, 1_100L, 55.0, "UTC", d1, "OK", 1, deleted = false),
                    ObservationRow(d1DupNew, prov, accountId, null, bbMetric, 1_000L, 1_200L, 78.0, "UTC", d1, "OK", 1, deleted = false),
                    ObservationRow(d2Canonical, prov, accountId, null, bbMetric, 2_000L, 2_100L, 60.0, "UTC", d2, "OK", 1, deleted = true),
                    ObservationRow(d2Dup, prov, accountId, null, bbMetric, 2_000L, 2_200L, 65.0, "UTC", d2, "OK", 1, deleted = false),
                    ObservationRow(d3Excluded, prov, accountId, null, bbMetric, 3_000L, 3_100L, 40.0, "UTC", d3, "OK", 1, deleted = true),
                    ObservationRow(d3Sneak, prov, accountId, null, bbMetric, 3_000L, 3_200L, 44.0, "UTC", d3, "OK", 1, deleted = false),
                )
            )
            db.exclusions().put(ExcludedRow(d3Excluded, 9_000L))

            ImportService.deduplicateBodyBatteryRows(db)

            // Day 1: canonical active with newest value 78.0, duplicates marked deleted
            val d1Active = db.observations().forTypeInRange(bbMetric, d1, d1)
            assertEquals(1, d1Active.size)
            assertEquals(d1Canonical, d1Active.single().id)
            assertEquals(78.0, d1Active.single().value)

            // Day 2: deleted canonical row must NEVER be resurrected
            assertTrue(db.observations().forTypeInRange(bbMetric, d2, d2).isEmpty())
            val d2All = db.observations().ofTypesIncludingDeleted(listOf(bbMetric), d2, d2)
            assertTrue(d2All.all { it.deleted })

            // Day 3: exclusion remapped to canonical key, zero active rows
            assertTrue(db.observations().forTypeInRange(bbMetric, d3, d3).isEmpty())
            val exIds = db.exclusions().allIds().toSet()
            assertTrue(d3Canonical in exIds, "Exclusion must be remapped to canonical key $d3Canonical")
            assertFalse(d3Excluded in exIds, "Old excluded key $d3Excluded should be removed")
        } finally {
            runCatching { db.close() }
            dbFile.delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
        }
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
            return JdbcSqliteStatement(conn.prepareStatement(sql), onStep = {
                when {
                    upper.startsWith("BEGIN") -> inTx = true
                    upper.startsWith("COMMIT") || upper.startsWith("END") || upper.startsWith("ROLLBACK") -> inTx = false
                }
            })
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
        override fun getColumnCount(): Int {
            if (cachedColumnNames == null) executeAndCacheColumns()
            return cachedColumnNames?.size ?: 0
        }
        override fun getColumnName(index: Int): String {
            if (cachedColumnNames == null) executeAndCacheColumns()
            return cachedColumnNames?.getOrNull(index) ?: ""
        }
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
            rs?.close(); rs = null; cachedColumnNames = null; executed = false
            firstStepPending = false; hasPendingFirstRow = false; eof = false
        }
        override fun clearBindings() = ps.clearParameters()
        override fun close() { rs?.close(); rs = null; ps.close() }
    }
}
