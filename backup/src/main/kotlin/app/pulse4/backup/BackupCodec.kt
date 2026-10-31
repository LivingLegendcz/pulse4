package app.pulse4.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/**
 * Přenosný šifrovaný archiv s manifestem verzí a kontrolními součty. Klíč vzniká z hesla uživatele (PBKDF2),
 * ne z Keystore starého telefonu, takže obnova funguje na jiném zařízení. Archiv obsahuje vlastní data, plány a
 * nastavení – tokeny, vývojářská tajemství a soukromá Reticulum identita se do něj nikdy nevkládají (to hlídá volající).
 *
 * Formát: `"P4BK" | verze(1) | salt(16) | iterace(4) | iv(12) | AES-256-GCM( payload )`, hlavička je součástí AAD.
 * payload: `počet(4) | { název(UTF) | délka(4) | sha256(32) | data }*`.
 */
object BackupCodec {
    const val VERSION = 1
    private val MAGIC = byteArrayOf('P'.code.toByte(), '4'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val SALT = 16
    private const val IV = 12
    const val DEFAULT_ITERATIONS = 310_000
    private const val MAX_ITERATIONS = 5_000_000
    const val MAX_ENTRIES = 100_000
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    fun seal(
        entries: Map<String, ByteArray>,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(passphrase.size >= 8) { "Heslo zálohy musí mít aspoň 8 znaků" }
        require(entries.size <= MAX_ENTRIES && entries.values.sumOf { it.size.toLong() } <= MAX_TOTAL_BYTES) { "Archiv je příliš velký" }
        val salt = ByteArray(SALT).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val header = header(salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
            updateAAD(header)
        }
        return header + iv + cipher.doFinal(payload(entries))
    }

    fun open(blob: ByteArray, passphrase: CharArray): Map<String, ByteArray> {
        val headerLen = MAGIC.size + 1 + SALT + 4
        if (blob.size < headerLen + IV + 16) throw BackupException("Archiv je zkrácený nebo poškozený")
        if (!blob.copyOfRange(0, 4).contentEquals(MAGIC)) throw BackupException("Nejde o zálohu Pulse4")
        val version = blob[4].toInt()
        if (version != VERSION) throw BackupException("Nepodporovaná verze zálohy: $version")
        val salt = blob.copyOfRange(5, 5 + SALT)
        val iterations = DataInputStream(ByteArrayInputStream(blob, 5 + SALT, 4)).readInt()
        if (iterations !in 10_000..MAX_ITERATIONS) throw BackupException("Neplatné parametry odvození klíče")
        val iv = blob.copyOfRange(headerLen, headerLen + IV)
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
                updateAAD(blob.copyOfRange(0, headerLen))
            }.doFinal(blob, headerLen + IV, blob.size - headerLen - IV)
        } catch (e: AEADBadTagException) {
            throw BackupException("Špatné heslo, nebo je archiv poškozený")
        }
        return parse(plain)
    }

    private fun header(salt: ByteArray, iterations: Int): ByteArray = ByteArrayOutputStream().also {
        it.write(MAGIC); it.write(VERSION); it.write(salt); DataOutputStream(it).writeInt(iterations)
    }.toByteArray()

    private fun key(pass: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    private fun payload(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(entries.size)
            for ((name, data) in entries.toSortedMap()) {
                out.writeUTF(name); out.writeInt(data.size)
                out.write(MessageDigest.getInstance("SHA-256").digest(data)); out.write(data)
            }
        }
        return bos.toByteArray()
    }

    private fun parse(plain: ByteArray): Map<String, ByteArray> = try {
        val inp = DataInputStream(ByteArrayInputStream(plain))
        val n = inp.readInt()
        if (n < 0 || n > MAX_ENTRIES) throw BackupException("Neplatný počet položek")
        var total = 0L
        val out = LinkedHashMap<String, ByteArray>()
        repeat(n) {
            val name = inp.readUTF()
            // Zákaz cest ven z cílové složky (path traversal) při obnově souborů.
            if (name.isEmpty() || name.startsWith("/") || ".." in name.split('/', '\\')) throw BackupException("Nebezpečný název položky")
            val len = inp.readInt()
            total += len
            if (len < 0 || total > MAX_TOTAL_BYTES || len > inp.available()) throw BackupException("Neplatná velikost položky")
            val sum = ByteArray(32).also { inp.readFully(it) }
            val data = ByteArray(len).also { inp.readFully(it) }
            if (!MessageDigest.isEqual(sum, MessageDigest.getInstance("SHA-256").digest(data))) throw BackupException("Kontrolní součet nesedí: $name")
            if (out.put(name, data) != null) throw BackupException("Duplicitní položka: $name")
        }
        if (inp.available() != 0) throw BackupException("Nadbytečná data za koncem archivu")
        out
    } catch (e: EOFException) {
        throw BackupException("Archiv je zkrácený")
    }
}
