package app.pulse4.backup

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupTest {
    private val pass = "správné-heslo-123".toCharArray()
    private val entries = mapOf("observations.json" to """[{"a":1}]""".toByteArray(), "plans/p1.json" to ByteArray(5000) { it.toByte() }, "empty" to ByteArray(0))
    private val fast = 10_000 // jen pro rychlost testů

    @Test fun `roundtrip restores every entry byte for byte`() {
        val blob = BackupCodec.seal(entries, pass, fast)
        val back = BackupCodec.open(blob, pass)
        assertEquals(entries.keys, back.keys)
        entries.forEach { (k, v) -> assertContentEquals(v, back.getValue(k), k) }
    }

    @Test fun `archive is not readable without the passphrase and leaks no plaintext`() {
        val blob = BackupCodec.seal(entries, pass, fast)
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("observations"))
        assertFailsWith<BackupException> { BackupCodec.open(blob, "jine-heslo-123".toCharArray()) }
    }

    @Test fun `every kind of tampering and truncation is detected`() {
        val blob = BackupCodec.seal(entries, pass, fast)
        for (i in listOf(0, 4, 6, 22, 30, blob.size / 2, blob.size - 1)) {
            val bad = blob.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<BackupException>("bit v pozici $i") { BackupCodec.open(bad, pass) }
        }
        for (cut in listOf(0, 10, 40, blob.size - 20)) assertFailsWith<BackupException>("zkráceno na $cut") { BackupCodec.open(blob.copyOf(cut), pass) }
        assertFailsWith<BackupException> { BackupCodec.open(blob + byteArrayOf(1), pass) }
    }

    @Test fun `short passphrase is refused and two seals differ`() {
        assertFailsWith<IllegalArgumentException> { BackupCodec.seal(entries, "krátké".toCharArray(), fast) }
        assertFalse(BackupCodec.seal(entries, pass, fast).contentEquals(BackupCodec.seal(entries, pass, fast)))
    }

    @Test fun `unsupported version and absurd kdf parameters are refused before any work`() {
        val blob = BackupCodec.seal(entries, pass, fast)
        assertFailsWith<BackupException> { BackupCodec.open(blob.copyOf().also { it[4] = 2 }, pass) }
        val huge = blob.copyOf().also { java.nio.ByteBuffer.wrap(it, 21, 4).putInt(Int.MAX_VALUE) }
        assertFailsWith<BackupException> { BackupCodec.open(huge, pass) }
    }

    /** Zákeřná záloha se správným heslem: nebezpečný název a lživé délky nesmí vést k zápisu mimo cíl ani k alokaci. */
    private fun forge(build: (DataOutputStream) -> Unit): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }; val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val header = ByteArrayOutputStream().also { it.write("P4BK".toByteArray()); it.write(1); it.write(salt); DataOutputStream(it).writeInt(fast) }.toByteArray()
        val keyBytes = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(javax.crypto.spec.PBEKeySpec(pass, salt, fast, 256)).encoded
        val bos = ByteArrayOutputStream(); DataOutputStream(bos).use(build)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv)); updateAAD(header) }
        return header + iv + c.doFinal(bos.toByteArray())
    }

    @Test fun `forged archives with path traversal or lying lengths are rejected`() {
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(ByteArray(0))
        val traversal = forge { it.writeInt(1); it.writeUTF("../../etc/passwd"); it.writeInt(0); it.write(sha) }
        assertTrue(assertFailsWith<BackupException> { BackupCodec.open(traversal, pass) }.message!!.contains("Nebezpečný"))
        val lying = forge { it.writeInt(1); it.writeUTF("a"); it.writeInt(Int.MAX_VALUE); it.write(sha) }
        assertFailsWith<BackupException> { BackupCodec.open(lying, pass) }
        val manyEntries = forge { it.writeInt(Int.MAX_VALUE) }
        assertFailsWith<BackupException> { BackupCodec.open(manyEntries, pass) }
        val badSum = forge { it.writeInt(1); it.writeUTF("a"); it.writeInt(1); it.write(sha); it.write(7) }
        assertTrue(assertFailsWith<BackupException> { BackupCodec.open(badSum, pass) }.message!!.contains("Kontrolní"))
    }
}
