package app.pulse4.android.security

import java.io.File
import java.nio.file.Files
import javax.crypto.AEADBadTagException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SecretStoreCacheTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun newTempDir(): File = Files.createTempDirectory("pulse4-secret-cache-").toFile().also { tempDirs += it }

    private class MapOperationalStore : OperationalStateStore {
        private val data = LinkedHashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String) { data[key] = value }
        override fun delete(key: String) { data.remove(key) }
    }

    /** Fake sealer that counts `open` calls, i.e. the expensive Android Keystore decrypts. */
    private class CountingSealer : SecretSealer {
        var opens = 0
        var failOpen = false
        override fun seal(plain: ByteArray): ByteArray = ByteArray(12) { 1 } + plain + ByteArray(16) { 2 }
        override fun open(sealed: ByteArray): ByteArray {
            opens++
            if (failOpen) throw AEADBadTagException("Tag mismatch")
            return sealed.copyOfRange(12, sealed.size - 16)
        }
        override fun hasMasterKey(): Boolean = true
    }

    private fun store(sealer: CountingSealer): SecretStore {
        val dir = newTempDir()
        return SecretStore(dir, KeyVault(dir, { null }, sealer), MapOperationalStore())
    }

    @Test
    fun repeatedReadsDecryptOnlyOnce() {
        val sealer = CountingSealer()
        val secrets = store(sealer)
        secrets.putString("withings.web.session", "cookie-blob")
        repeat(10) { assertEquals("cookie-blob", secrets.getString("withings.web.session")) }
        assertEquals(1, sealer.opens, "10 reads of the same secret must hit the Keystore once")
    }

    @Test
    fun metricsBundleLikeReadPatternKeystoreCalls() {
        // One Metrics bundle used to do ~6-10 reads (loadWebSession, isConnected -> loadWebSession + vault.load,
        // trend() x N). Simulate that pattern: 3 distinct secrets, each read 8 times.
        val sealer = CountingSealer()
        val secrets = store(sealer)
        listOf("withings.web.session", "withings.tokens", "withings.client.secret").forEach { secrets.putString(it, "v-$it") }
        repeat(8) { listOf("withings.web.session", "withings.tokens", "withings.client.secret").forEach { secrets.getString(it) } }
        assertEquals(3, sealer.opens, "24 reads collapse to one decrypt per distinct secret (was 24)")
    }

    @Test
    fun putInvalidatesSoNewValueIsReadBack() {
        val sealer = CountingSealer()
        val secrets = store(sealer)
        secrets.putString("garmin.apikey", "old")
        assertEquals("old", secrets.getString("garmin.apikey"))
        secrets.putString("garmin.apikey", "new")
        assertEquals("new", secrets.getString("garmin.apikey"))
        assertEquals(2, sealer.opens)
    }

    @Test
    fun deleteInvalidates() {
        val sealer = CountingSealer()
        val secrets = store(sealer)
        secrets.putString("garmin.apikey", "v")
        assertEquals("v", secrets.getString("garmin.apikey"))
        secrets.delete("garmin.apikey")
        assertNull(secrets.getString("garmin.apikey"))
    }

    @Test
    fun evictAllForcesDecryptAgain() {
        val sealer = CountingSealer()
        val secrets = store(sealer)
        secrets.putString("garmin.apikey", "v")
        secrets.getString("garmin.apikey")
        secrets.evictAll()
        secrets.getString("garmin.apikey")
        assertEquals(2, sealer.opens)
    }

    @Test
    fun outOfBandFileChangeIsNotServedFromCache() {
        val dir = newTempDir()
        val sealer = CountingSealer()
        val secrets = SecretStore(dir, KeyVault(dir, { null }, sealer), MapOperationalStore())
        secrets.putString("garmin.apikey", "first")
        assertEquals("first", secrets.getString("garmin.apikey"))
        File(dir, "secrets/garmin.apikey").delete()
        assertNull(secrets.getString("garmin.apikey"))
    }

    @Test
    fun returnedArrayIsACopyAndCannotCorruptTheCache() {
        val sealer = CountingSealer()
        val secrets = store(sealer)
        secrets.put("garmin.apikey", "abc".toByteArray())
        val first = secrets.get("garmin.apikey")!!
        first.fill(0)
        assertContentEquals("abc".toByteArray(), secrets.get("garmin.apikey"))
    }

    @Test
    fun criticalSecretsAreNeverCachedSoCorruptionIsStillDetected() {
        val sealer = CountingSealer()
        val secrets = store(sealer)
        secrets.putString(SecretStore.RETICULUM_IDENTITY, "identity")
        assertEquals("identity", secrets.getString(SecretStore.RETICULUM_IDENTITY))
        sealer.failOpen = true
        assertFailsWith<CorruptedSecretException> { secrets.getString(SecretStore.RETICULUM_IDENTITY) }
    }

    @Test
    fun failedDecryptIsNotCached() {
        val sealer = CountingSealer()
        val secrets = store(sealer)
        secrets.putString("garmin.apikey", "v")
        sealer.failOpen = true
        assertNull(secrets.getString("garmin.apikey"))
        sealer.failOpen = false
        assertEquals("v", secrets.getString("garmin.apikey"))
    }
}
