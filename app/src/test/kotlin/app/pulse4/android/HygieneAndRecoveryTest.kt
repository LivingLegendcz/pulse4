package app.pulse4.android

import app.pulse4.android.security.CorruptedSecretException
import app.pulse4.android.security.CriticalSecretKind
import app.pulse4.android.security.KeyVault
import app.pulse4.android.security.OperationalStateStore
import app.pulse4.android.security.SecretRecoveryState
import app.pulse4.android.security.SecretSealer
import app.pulse4.android.security.SecretStore
import java.io.File
import java.nio.file.Files
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

    private class FakeSealer(var failOpen: Boolean = false, var masterPresent: Boolean = true) : SecretSealer {
        override fun seal(plain: ByteArray): ByteArray = ByteArray(12) { 1 } + plain + ByteArray(16) { 2 }
        override fun open(sealed: ByteArray): ByteArray {
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
}
