package app.pulse4.android.di

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import app.pulse4.android.data.AppDatabase
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.security.CorruptedSecretException
import app.pulse4.android.security.CriticalSecretKind
import app.pulse4.android.security.KeyVault
import app.pulse4.android.security.SecretRecoveryState
import app.pulse4.android.security.SecretSealer
import app.pulse4.android.security.SecretStore
import app.pulse4.android.ui.RootContent
import app.pulse4.android.ui.resolveRootContent
import app.pulse4.android.ui.runActivityStartup
import java.io.File
import java.nio.file.Files
import java.security.KeyStoreException
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Balíček L (R4-1, R4-3, R4-10): start a stabilita – testy volají produkční ServiceLocator / MainActivity cestu. */
class PackageLStartupTest {

    private val tempDirs = mutableListOf<File>()
    private val openDbs = mutableListOf<AppDatabase>()
    private val locators = mutableListOf<ServiceLocator>()

    @AfterTest
    fun tearDown() {
        locators.forEach { sl -> runCatching { sl.bridge.info?.let { sl.bridge.stop() } } }
        openDbs.forEach { runCatching { it.close() } }
        SecretRecoveryState.clear()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun newTempDir(): File = Files.createTempDirectory("pulse4-pkg-l-").toFile().also { tempDirs += it }

    /** Zapečetění jako v Keystore; `customError` simuluje poškozený `db.key` nebo zamčený Keystore. */
    private class FakeSealer(var customError: (() -> Throwable)? = null) : SecretSealer {
        override fun seal(plain: ByteArray): ByteArray = ByteArray(12) { 1 } + plain + ByteArray(16) { 2 }
        override fun open(sealed: ByteArray): ByteArray {
            customError?.invoke()?.let { throw it }
            require(sealed.size > 28)
            return sealed.copyOfRange(12, sealed.size - 16)
        }
        override fun hasMasterKey(): Boolean = true
    }

    private fun testContext(dir: File): Context {
        val prefs = InMemorySharedPrefs()
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = dir
            override fun getFilesDir(): File = dir
            override fun getCacheDir(): File = dir
            override fun getDatabasePath(name: String): File = File(dir, name)
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getSystemService(name: String): Any? = null
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
            .also { openDbs += it }
    }

    /**
     * ServiceLocator bez `dbOverride`: lazy `db` jde přes produkční [KeyVault.databasePassphrase] jako `AppDatabase.open`
     * (jen SQLCipher je v JVM nahrazen JDBC SQLite).
     */
    private fun locatorWithVaultDb(dir: File, sealer: SecretSealer): Pair<ServiceLocator, AtomicInteger> {
        val dbOpens = AtomicInteger(0)
        val dbFile = File(dir, "pulse4-test.db")
        val sl = ServiceLocator(
            testContext(dir),
            dbOverride = null,
            sealerOverride = sealer,
            dbOpener = { vault ->
                vault.databasePassphrase()
                dbOpens.incrementAndGet()
                openTestDb(dbFile)
            },
        )
        locators += sl
        return sl to dbOpens
    }

    private fun locatorWithDb(dir: File): ServiceLocator {
        val db = openTestDb(File(dir, "pulse4-test.db"))
        return ServiceLocator(testContext(dir), dbOverride = db, sealerOverride = FakeSealer()).also { locators += it }
    }

    private fun seedSelfIdentity(sl: ServiceLocator) {
        sl.secrets.putString(SecretStore.RETICULUM_IDENTITY, Base64.getEncoder().encodeToString(ByteArray(32) { (it + 7).toByte() }))
    }

    private fun rootOf(sl: ServiceLocator): RootContent =
        resolveRootContent(sl.recoveryIssue.value != null, sl.migrationsCompleted.value)

    @Test
    fun `R4-1 corrupted db key on first start and activity recreation shows recovery instead of crashing`() {
        val dir = newTempDir()
        val sealer = FakeSealer()
        val (sl, dbOpens) = locatorWithVaultDb(dir, sealer)
        // Existující instalace: zapečetěný db.key a identita.
        sl.vault.databasePassphrase()
        seedSelfIdentity(sl)
        // db.key se poškodí (AEAD tag nesedí).
        File(dir, KeyVault.DB_KEY_NAME).writeBytes(ByteArray(48) { 9 })
        sealer.customError = { AEADBadTagException("Corrupted db.key") }

        runBlocking {
            // 1. MainActivity.onCreate (první start) – nesmí vyhodit.
            runActivityStartup(sl)
            val issue = assertNotNull(sl.recoveryIssue.value)
            assertEquals(CriticalSecretKind.DB_KEY, issue.kind)
            assertFalse(issue.recoverable)
            assertTrue(sl.isMigrationsCompleted())
            assertFalse(sl.migrationsCompleted.value, "UI must not be released to MAIN with a corrupted key")
            assertEquals(RootContent.RECOVERY, rootOf(sl))

            // 2. Otočení obrazovky = nová activity, znovu onCreate → rychlá cesta awaitMigrations; dříve zde pád.
            runActivityStartup(sl)
            sl.awaitMigrations()
            assertFalse(sl.awaitStartupReady(), "Workers / service must see 'not ready' and retry later")
            assertEquals(RootContent.RECOVERY, rootOf(sl))
        }
        // Kontrola, že scénář je skutečný: otevření DB s tímto klíčem opravdu vyhazuje.
        assertFailsWith<CorruptedSecretException> { sl.db }
        assertEquals(0, dbOpens.get())
    }

    @Test
    fun `R4-1 fast path exception from lazy db is caught and turned into recovery issue`() {
        val dir = newTempDir()
        val sealer = FakeSealer()
        val (sl, _) = locatorWithVaultDb(dir, sealer)
        sl.vault.databasePassphrase()
        seedSelfIdentity(sl)

        // Keystore je při startu zamčený → první průchod nahlásí přechodnou chybu, settings se nenahřejí.
        sealer.customError = { KeyStoreException("Keystore locked") }
        runBlocking { sl.awaitMigrations() }
        assertTrue(assertNotNull(sl.recoveryIssue.value).recoverable)

        // Jiná komponenta stav obnovy vyčistí (např. úspěšný probe), Keystore je ale pořád zamčený.
        SecretRecoveryState.clear()
        runBlocking {
            // Rychlá cesta: needsDeferredMigration → getRaw → lazy db → databasePassphrase → TransientKeystoreException.
            sl.awaitMigrations()
            runActivityStartup(sl)
            assertFalse(sl.awaitStartupReady())
        }
        val issue = assertNotNull(sl.recoveryIssue.value, "Exception must be converted into a recovery issue")
        assertTrue(issue.recoverable)
        assertEquals(KeyVault.DB_KEY_NAME, issue.secretName)

        // Po odemčení a „Zkusit znovu“ v overlay se UI uvolní a migrace doběhne.
        sealer.customError = null
        assertNull(sl.vault.retryCriticalSecrets(sl.secrets))
        runBlocking { sl.runMigrationsAfterRecovery() }
        assertTrue(sl.migrationsCompleted.value)
        assertEquals(RootContent.MAIN, rootOf(sl))
        assertTrue(runBlocking { sl.awaitStartupReady() })
    }

    @Test
    fun `R4-3 healthy start opens db inside awaitMigrations and releases UI only afterwards`() {
        val dir = newTempDir()
        val (sl, dbOpens) = locatorWithVaultDb(dir, FakeSealer())
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        sl.migrationRunner = { started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }

        assertEquals(RootContent.SPLASH, rootOf(sl))
        val worker = Thread { runBlocking { sl.awaitMigrations() } }.apply { start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // DB otevřel worker (IO) ještě před migrací; UI je stále ve splash.
        assertEquals(1, dbOpens.get())
        assertFalse(sl.migrationsCompleted.value)
        assertEquals(RootContent.SPLASH, rootOf(sl))
        release.countDown()
        worker.join(5_000)
        assertTrue(sl.migrationsCompleted.value)
        assertEquals(RootContent.MAIN, rootOf(sl))
        // Nahřátá nastavení se čtou z cache – žádné další otevírání DB.
        sl.settings.unitSystem
        assertEquals(1, dbOpens.get())
    }

    @Test
    fun `R4-10 failing deferred migration is retried at most three times per process`() {
        val dir = newTempDir()
        val sl = locatorWithDb(dir)
        seedSelfIdentity(sl)
        val runs = AtomicInteger(0)
        // Migrace nikdy nenastaví příznaky (a někdy vyhodí) → needsDeferredMigration zůstává true.
        sl.migrationRunner = {
            val n = runs.incrementAndGet()
            if (n % 2 == 0) error("migration boom #$n")
        }
        runBlocking {
            sl.awaitMigrations() // počáteční běh
            assertEquals(1, runs.get())
            repeat(10) { sl.awaitMigrations() } // nesmí vyhodit ani běžet donekonečna
            sl.runDeferredMigrationsIfNeeded()
            runActivityStartup(sl)
        }
        assertEquals(1 + ServiceLocator.MAX_MIGRATION_FAILURES, runs.get())
        assertEquals(ServiceLocator.MAX_MIGRATION_FAILURES, sl.migrationFailureCount)

        // Explicitní obnova uživatelem začíná nové kolo pokusů.
        runBlocking {
            runCatching { sl.runMigrationsAfterRecovery() }
            repeat(5) { sl.awaitMigrations() }
        }
        assertEquals(2 * (1 + ServiceLocator.MAX_MIGRATION_FAILURES), runs.get())
    }

    @Test
    fun `R4-10 successful deferred migration does not count as failure`() {
        val dir = newTempDir()
        val sl = locatorWithDb(dir)
        val runs = AtomicInteger(0)
        sl.migrationRunner = { runs.incrementAndGet(); ServiceMigrations.runOnce(it) }
        runBlocking {
            sl.awaitMigrations() // bez identity: migrace se odloží
            seedSelfIdentity(sl)
            sl.awaitMigrations() // identita už existuje → doběhne
            repeat(3) { sl.awaitMigrations() }
        }
        assertEquals(2, runs.get())
        assertEquals(0, sl.migrationFailureCount)
        assertFalse(ServiceMigrations.needsDeferredMigration(sl))
    }

    @Test
    fun `L3 bridge ensureStarted waits for startup migration and never starts before it`() {
        val dir = newTempDir()
        val sl = locatorWithDb(dir)
        sl.settings.networkMode = NetworkMode.MANUAL
        val release = CountDownLatch(1)
        val migrationRunning = CountDownLatch(1)
        val bridgeStartedDuringMigration = AtomicReference<Boolean?>(null)
        sl.migrationRunner = {
            migrationRunning.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            bridgeStartedDuringMigration.set(it.bridge.info != null)
        }
        val migration = Thread { runBlocking { sl.awaitMigrations() } }.apply { start() }
        assertTrue(migrationRunning.await(5, TimeUnit.SECONDS))

        val startedInfo = AtomicReference<Any?>(null)
        val caller = Thread { startedInfo.set(sl.bridge.ensureStarted()) }.apply { start() }
        caller.join(400)
        assertTrue(caller.isAlive, "ensureStarted must block until migrations finish")
        assertNull(sl.bridge.info)

        release.countDown()
        migration.join(5_000)
        caller.join(5_000)
        assertFalse(caller.isAlive)
        assertEquals(false, bridgeStartedDuringMigration.get())
        assertNotNull(startedInfo.get())
        assertNotNull(sl.bridge.info)
    }

    @Test
    fun `L3 ensureStarted called from inside the migration does not deadlock`() {
        val dir = newTempDir()
        val sl = locatorWithDb(dir)
        sl.settings.networkMode = NetworkMode.MANUAL
        val inner = AtomicReference<Any?>(null)
        sl.migrationRunner = { inner.set(it.bridge.ensureStarted()) }
        val t = Thread { runBlocking { sl.awaitMigrations() } }.apply { start() }
        t.join(5_000)
        assertFalse(t.isAlive, "Migration calling bridge.ensureStarted() deadlocked")
        assertNotNull(inner.get())
    }

    @Test
    fun `R4-3 root content precedence recovery over splash over main`() {
        assertEquals(RootContent.RECOVERY, resolveRootContent(hasRecoveryIssue = true, migrationsCompleted = false))
        assertEquals(RootContent.RECOVERY, resolveRootContent(hasRecoveryIssue = true, migrationsCompleted = true))
        assertEquals(RootContent.SPLASH, resolveRootContent(hasRecoveryIssue = false, migrationsCompleted = false))
        assertEquals(RootContent.MAIN, resolveRootContent(hasRecoveryIssue = false, migrationsCompleted = true))
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
