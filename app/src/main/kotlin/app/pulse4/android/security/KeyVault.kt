package app.pulse4.android.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Obalový klíč v Android Keystore. Chrání klíč databáze a uložené přístupy (osobní API klíč, tokeny, Reticulum identita).
 *
 * Výchozí režim dovoluje práci na pozadí po prvním odemčení telefonu (žádné `setUserAuthenticationRequired`);
 * režim „odemknout trezor pro každý přístup“ by zastavil import a sdílení na pozadí, proto není výchozí.
 * StrongBox se použije jen tehdy, je-li skutečně dostupný.
 */
class KeyVault(private val context: Context) {
    private val alias = "pulse4.master.v1"

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            gen.init(spec(strongBox = true)); gen.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            gen.init(spec(strongBox = false)); gen.generateKey()
        }
    }

    /** Formát: `[12 B IV][ciphertext+tag]`. */
    fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, masterKey()) }
        return c.iv + c.doFinal(plain)
    }

    fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > 12 + 16) { "Poškozený zapečetěný blok" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, sealed, 0, 12))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    /** Heslo SQLCipher databáze: 32 náhodných bajtů, uložených zapečetěně mimo zálohy. */
    fun databasePassphrase(): ByteArray {
        val f = File(context.noBackupFilesDir, "db.key")
        if (f.exists()) return open(f.readBytes())
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        f.writeBytes(seal(pass))
        return pass
    }
}

/**
 * Úložiště malých tajemství (osobní API klíč, Garmin tokeny, soukromá Reticulum identita).
 * Nikdy se nezapisují v čitelné podobě; diagnostický export je nezahrnuje.
 */
class SecretStore(context: Context, private val vault: KeyVault) {
    private val dir = File(context.noBackupFilesDir, "secrets").apply { mkdirs() }

    private fun file(name: String): File {
        require(name.matches(Regex("[a-z0-9._-]{1,64}"))) { "Neplatný název tajemství" }
        return File(dir, name)
    }

    fun put(name: String, value: ByteArray) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(vault.seal(value))
        // Atomicky: pád uprostřed zápisu nesmí zničit předchozí hodnotu (např. rotující refresh token).
        if (!tmp.renameTo(file(name))) { file(name).delete(); check(tmp.renameTo(file(name))) { "Zápis tajemství selhal" } }
    }

    fun get(name: String): ByteArray? = file(name).takeIf { it.exists() }?.let { runCatching { vault.open(it.readBytes()) }.getOrNull() }

    fun putString(name: String, value: String) = put(name, value.toByteArray(Charsets.UTF_8))
    fun getString(name: String): String? = get(name)?.toString(Charsets.UTF_8)
    fun delete(name: String) { file(name).delete() }
    fun has(name: String) = file(name).exists()

    companion object {
        const val GARMIN_API_KEY = "garmin.apikey"
        const val GARMIN_API_KEY_EXPIRES = "garmin.apikey.expires"
        const val GARMIN_EXT_TOKENS = "garmin.ext.tokens"
        const val RETICULUM_IDENTITY = "reticulum.identity"
    }
}
