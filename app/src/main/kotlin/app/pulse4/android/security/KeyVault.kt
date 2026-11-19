package app.pulse4.android.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import app.pulse4.domain.PLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Vyvoláno při selhání AEAD dešifrování kritického tajemství (`db.key` nebo `reticulum.identity`). */
class CorruptedSecretException(
    val secretName: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Vyvoláno při přechodné nedostupnosti Android Keystore (uzamčení, dočasné selhání provideru). */
class TransientKeystoreException(
    val secretName: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

enum class CriticalSecretKind {
    DB_KEY,
    RETICULUM_IDENTITY,
}

data class CorruptedSecretIssue(
    val kind: CriticalSecretKind,
    val secretName: String,
    val error: String,
    val recoverable: Boolean = false,
)

internal fun isRecoverableKeystoreFailure(error: Throwable): Boolean {
    var cur: Throwable? = error
    var depth = 0
    while (cur != null && depth < 12) {
        if (cur is UserNotAuthenticatedException ||
            cur is ProviderException ||
            cur is KeyStoreException ||
            cur is IOException
        ) {
            return true
        }
        if (cur is AEADBadTagException) return false
        cur = cur.cause
        depth++
    }
    return false
}

/** Procesní stav obnovy při selhání kritického tajemství nebo dočasném výpadku Keystore (NEW-11, R3-8). */
object SecretRecoveryState {
    private val _issue = MutableStateFlow<CorruptedSecretIssue?>(null)
    val issue: StateFlow<CorruptedSecretIssue?> = _issue.asStateFlow()

    fun report(
        kind: CriticalSecretKind,
        secretName: String,
        error: Throwable,
        recoverable: Boolean = isRecoverableKeystoreFailure(error),
    ) {
        val prev = _issue.value
        val shouldReplace = prev == null ||
            (!recoverable && prev.recoverable) ||
            (recoverable == prev.recoverable && kind == CriticalSecretKind.DB_KEY && prev.kind != CriticalSecretKind.DB_KEY)
        if (shouldReplace) {
            _issue.value = CorruptedSecretIssue(
                kind = kind,
                secretName = secretName,
                error = error::class.simpleName ?: if (recoverable) "TransientKeystoreFailure" else "CorruptedSecret",
                recoverable = recoverable,
            )
        }
    }

    fun clear() {
        _issue.value = null
    }
}

interface SecretSealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
    fun hasMasterKey(): Boolean
}

interface OperationalStateStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun delete(key: String)
}

/**
 * Obalový klíč v Android Keystore. Chrání klíč databáze a uložené přístupy (osobní API klíč, tokeny, Reticulum identita).
 *
 * Výchozí režim dovoluje práci na pozadí po prvním odemčení telefonu (žádné `setUserAuthenticationRequired`);
 * režim „odemknout trezor pro každý přístup“ by zastavil import a sdílení na pozadí, proto není výchozí.
 * StrongBox se použije jen tehdy, je-li skutečně dostupný.
 */
class KeyVault internal constructor(
    internal val noBackupDir: File,
    private val dbPathProvider: () -> File?,
    private val sealerOverride: SecretSealer? = null,
    private val recoveryState: SecretRecoveryState = SecretRecoveryState,
) {
    constructor(context: Context) : this(
        noBackupDir = context.noBackupFilesDir,
        dbPathProvider = { context.getDatabasePath("pulse4.db") },
        sealerOverride = null,
        recoveryState = SecretRecoveryState,
    )

    private val alias = "pulse4.master.v1"

    val recoveryIssue: StateFlow<CorruptedSecretIssue?> get() = recoveryState.issue

    internal fun reportCriticalFailure(
        kind: CriticalSecretKind,
        secretName: String,
        error: Throwable,
        recoverable: Boolean = isRecoverableKeystoreFailure(error),
    ) {
        recoveryState.report(kind, secretName, error, recoverable = recoverable)
    }

    private fun hasSealedFiles(): Boolean {
        if (File(noBackupDir, DB_KEY_NAME).exists()) return true
        val secretsDir = File(noBackupDir, "secrets")
        return secretsDir.listFiles()?.any { it.isFile && !it.name.endsWith(".tmp") } == true
    }

    private val androidSealer: SecretSealer by lazy {
        object : SecretSealer {
            private fun masterKey(): SecretKey {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                (ks.getKey(alias, null) as? SecretKey)?.let { return it }
                check(!hasSealedFiles()) { "Hlavní klíč v Keystore chybí, ale existují zapečetěné soubory" }
                fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
                    .build()
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                return try {
                    gen.init(spec(strongBox = true)); gen.generateKey()
                } catch (_: StrongBoxUnavailableException) {
                    gen.init(spec(strongBox = false)); gen.generateKey()
                }
            }

            override fun seal(plain: ByteArray): ByteArray {
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, masterKey()) }
                return c.iv + c.doFinal(plain)
            }

            override fun open(sealed: ByteArray): ByteArray {
                require(sealed.size > 12 + 16) { "Poškozený zapečetěný blok" }
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, sealed, 0, 12))
                return c.doFinal(sealed, 12, sealed.size - 12)
            }

            override fun hasMasterKey(): Boolean = runCatching {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                ks.getKey(alias, null) is SecretKey
            }.getOrDefault(false)
        }
    }

    private val sealer: SecretSealer get() = sealerOverride ?: androidSealer

    /** Formát: `[12 B IV][ciphertext+tag]`. */
    fun seal(plain: ByteArray): ByteArray = sealer.seal(plain)

    fun open(sealed: ByteArray): ByteArray = sealer.open(sealed)

    /** Heslo SQLCipher databáze: 32 náhodných bajtů, uložených zapečetěně mimo zálohy. */
    fun databasePassphrase(): ByteArray {
        val f = File(noBackupDir, DB_KEY_NAME)
        if (f.exists()) {
            return try {
                open(f.readBytes())
            } catch (e: Throwable) {
                if (e is Error) throw e
                val recoverable = isRecoverableKeystoreFailure(e)
                recoveryState.report(CriticalSecretKind.DB_KEY, DB_KEY_NAME, e, recoverable = recoverable)
                if (recoverable) {
                    PLog.w("KeyVault", e) { "Přechodná chyba Keystore pro $DB_KEY_NAME (lze zkusit znovu)" }
                    throw TransientKeystoreException(DB_KEY_NAME, "Úložiště klíčů je dočasně nedostupné ($DB_KEY_NAME).", e)
                }
                PLog.e("KeyVault", e) { "Kritické tajemství $DB_KEY_NAME selhalo při dešifrování" }
                throw CorruptedSecretException(DB_KEY_NAME, "Klíč databáze ($DB_KEY_NAME) nelze dešifrovat.", e)
            }
        }
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sealed = seal(pass)
        val tmp = File(noBackupDir, "$DB_KEY_NAME.tmp")
        FileOutputStream(tmp).use {
            it.write(sealed)
            it.flush()
            it.fd.sync()
        }
        check(tmp.renameTo(f)) { "Atomický zápis db.key selhal" }
        return pass
    }

    /**
     * Ověří čitelnost existujících kritických tajemství (`db.key`, `reticulum.identity`) bez vytváření nových.
     * Při poškození nebo dočasném výpadku Keystore zaznamená stav obnovy do [recoveryIssue] a vrátí jej.
     */
    fun probeCriticalSecrets(secrets: SecretStore): CorruptedSecretIssue? {
        var sawFailure = false
        val dbKeyFile = File(noBackupDir, DB_KEY_NAME)
        if (dbKeyFile.exists()) {
            runCatching { databasePassphrase() }.onFailure { sawFailure = true }
        }
        if (secrets.has(SecretStore.RETICULUM_IDENTITY)) {
            runCatching { secrets.get(SecretStore.RETICULUM_IDENTITY) }.onFailure { sawFailure = true }
        }
        if (secrets.has(SecretStore.RETICULUM_IDENTITY_ED25519)) {
            runCatching { secrets.get(SecretStore.RETICULUM_IDENTITY_ED25519) }.onFailure { sawFailure = true }
        }
        if (!sawFailure && recoveryState.issue.value?.recoverable == true) {
            recoveryState.clear()
        }
        return recoveryState.issue.value
    }

    /** Vyčistí předchozí stav chyby a znovu ověří čitelnost kritických tajemství („Zkusit znovu“). */
    fun retryCriticalSecrets(secrets: SecretStore): CorruptedSecretIssue? {
        recoveryState.clear()
        return probeCriticalSecrets(secrets)
    }

    /**
     * Bezpečně vymaže výhradně poškozené kritické tajemství před obnovou ze zálohy nebo vytvořením nového.
     * Přechodná chyba Keystore ani poškození ne-identitního tajemství nikdy nemažou identitu ani ostatní secrety (R3-8).
     */
    fun resetCorruptedForRecovery(
        secrets: SecretStore,
        issue: CorruptedSecretIssue? = recoveryState.issue.value,
        resetDatabaseToo: Boolean = issue?.kind == CriticalSecretKind.DB_KEY,
        resetIdentityToo: Boolean = issue?.kind == CriticalSecretKind.RETICULUM_IDENTITY,
    ) {
        if (issue?.recoverable == true) {
            PLog.w("KeyVault") { "Odmítnuto mazání při přechodné chybě Keystore (${issue.secretName})" }
            return
        }
        val shouldResetIdentity = resetIdentityToo || issue?.kind == CriticalSecretKind.RETICULUM_IDENTITY
        if (shouldResetIdentity) {
            val targetName = issue?.secretName
            if (targetName == null || targetName == SecretStore.RETICULUM_IDENTITY) {
                secrets.delete(SecretStore.RETICULUM_IDENTITY)
                File(noBackupDir, "secrets/${SecretStore.RETICULUM_IDENTITY}.tmp").delete()
            }
            if (targetName == null || targetName == SecretStore.RETICULUM_IDENTITY_ED25519) {
                secrets.delete(SecretStore.RETICULUM_IDENTITY_ED25519)
                File(noBackupDir, "secrets/${SecretStore.RETICULUM_IDENTITY_ED25519}.tmp").delete()
            }
        }

        val shouldResetDb = resetDatabaseToo || issue?.kind == CriticalSecretKind.DB_KEY
        if (shouldResetDb) {
            File(noBackupDir, DB_KEY_NAME).delete()
            File(noBackupDir, "$DB_KEY_NAME.tmp").delete()
            dbPathProvider()?.let { dbFile ->
                dbFile.delete()
                File("${dbFile.absolutePath}-wal").delete()
                File("${dbFile.absolutePath}-shm").delete()
                File("${dbFile.absolutePath}-journal").delete()
            }
        }
        recoveryState.clear()
    }

    companion object {
        const val DB_KEY_NAME = "db.key"
    }
}

/**
 * Úložiště malých tajemství (osobní API klíč, Garmin tokeny, soukromá Reticulum identita).
 * Nikdy se nezapisují v čitelné podobě; diagnostický export je nezahrnuje.
 * Ne-tajné provozní čítače cooldownů a backoffů se ukládají mimo AEAD v SharedPreferences,
 * aby poškození nebo výpadek Keystoru nikdy neobešly ochranu proti 429 / lockoutu (NEW-11).
 */
class SecretStore internal constructor(
    noBackupDir: File,
    private val vault: KeyVault,
    private val operationalStore: OperationalStateStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: Context, vault: KeyVault) : this(
        noBackupDir = context.noBackupFilesDir,
        vault = vault,
        operationalStore = SharedPrefsOperationalStore(
            context.applicationContext.getSharedPreferences(OPERATIONAL_PREFS_NAME, Context.MODE_PRIVATE),
        ),
    )

    private val dir = File(noBackupDir, "secrets").apply { mkdirs() }

    private fun file(name: String): File {
        require(name.matches(Regex("[a-z0-9._-]{1,64}"))) { "Neplatný název tajemství" }
        return File(dir, name)
    }

    fun put(name: String, value: ByteArray) {
        if (name in OPERATIONAL_STATE_KEYS) {
            operationalStore.putString(name, value.toString(Charsets.UTF_8))
            file(name).delete()
            return
        }
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(vault.seal(value))
        // Atomicky: pád uprostřed zápisu nesmí zničit předchozí hodnotu (např. rotující refresh token).
        if (!tmp.renameTo(file(name))) {
            file(name).delete()
            check(tmp.renameTo(file(name))) { "Zápis tajemství selhal" }
        }
    }

    fun get(name: String): ByteArray? {
        if (name in OPERATIONAL_STATE_KEYS) {
            operationalStore.getString(name)?.let { return it.toByteArray(Charsets.UTF_8) }
            val legacy = file(name)
            if (!legacy.exists()) return null
            return try {
                val plain = vault.open(legacy.readBytes())
                operationalStore.putString(name, plain.toString(Charsets.UTF_8))
                legacy.delete()
                plain
            } catch (e: Throwable) {
                if (e is Error) throw e
                val fallback = conservativeCooldownValue(name)
                if (fallback != null) {
                    operationalStore.putString(name, fallback)
                }
                legacy.delete()
                PLog.w("SecretStore", e) { "Poškozený stav cooldownu '$name', aktivován konzervativní cooldown" }
                fallback?.toByteArray(Charsets.UTF_8)
            }
        }

        val f = file(name)
        if (!f.exists()) return null
        if (name in CRITICAL_SECRETS) {
            return try {
                vault.open(f.readBytes())
            } catch (e: Throwable) {
                if (e is Error) throw e
                val recoverable = isRecoverableKeystoreFailure(e)
                vault.reportCriticalFailure(CriticalSecretKind.RETICULUM_IDENTITY, name, e, recoverable = recoverable)
                if (recoverable) {
                    PLog.w("SecretStore", e) { "Přechodná chyba Keystore pro '$name' (lze zkusit znovu)" }
                    throw TransientKeystoreException(name, "Úložiště klíčů je pro '$name' dočasně nedostupné.", e)
                }
                PLog.e("SecretStore", e) { "Kritické tajemství '$name' selhalo při dešifrování" }
                throw CorruptedSecretException(name, "Kritické tajemství '$name' nelze dešifrovat.", e)
            }
        }
        return try {
            vault.open(f.readBytes())
        } catch (e: GeneralSecurityException) {
            PLog.w("SecretStore", e) { "Nečitelné ne-kritické tajemství '$name', vracím null" }
            null
        } catch (e: ProviderException) {
            PLog.w("SecretStore", e) { "Přechodná chyba Keystore pro ne-kritické tajemství '$name', vracím null" }
            null
        } catch (e: IllegalArgumentException) {
            PLog.w("SecretStore", e) { "Neplatný formát tajemství '$name', vracím null" }
            null
        } catch (e: IllegalStateException) {
            PLog.w("SecretStore", e) { "Chyba klíče pro ne-kritické tajemství '$name', vracím null" }
            null
        } catch (e: IOException) {
            PLog.w("SecretStore", e) { "Chyba čtení tajemství '$name', vracím null" }
            null
        }
    }

    private fun conservativeCooldownValue(name: String): String? = when (name) {
        in OPERATIONAL_COOLDOWN_UNTIL_KEYS -> (nowMs() + CONSERVATIVE_COOLDOWN_MS).toString()
        in OPERATIONAL_FAILURE_COUNT_KEYS -> "3"
        OPERATIONAL_PAUSE_REASON_KEY -> "RATE_LIMITED"
        OPERATIONAL_PAUSE_MESSAGE_KEY ->
            "Ochrana proti opakovanému přihlášení byla aktivována po chybě úložiště — přihlaste se prosím ručně."
        else -> null
    }

    fun putString(name: String, value: String) = put(name, value.toByteArray(Charsets.UTF_8))
    fun getString(name: String): String? = get(name)?.toString(Charsets.UTF_8)
    fun delete(name: String) {
        if (name in OPERATIONAL_STATE_KEYS) {
            operationalStore.delete(name)
        }
        file(name).delete()
    }

    fun has(name: String): Boolean =
        (name in OPERATIONAL_STATE_KEYS && operationalStore.getString(name) != null) || file(name).exists()

    private class SharedPrefsOperationalStore(
        private val prefs: android.content.SharedPreferences,
    ) : OperationalStateStore {
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }
        override fun delete(key: String) {
            prefs.edit().remove(key).apply()
        }
    }

    companion object {
        const val GARMIN_API_KEY = "garmin.apikey"
        const val GARMIN_API_KEY_EXPIRES = "garmin.apikey.expires"
        const val GARMIN_EXT_TOKENS = "garmin.ext.tokens"
        const val RETICULUM_IDENTITY = "reticulum.identity"
        const val RETICULUM_IDENTITY_ED25519 = "rns.identity.ed25519"
        private val CRITICAL_SECRETS = setOf(RETICULUM_IDENTITY, RETICULUM_IDENTITY_ED25519)

        private const val OPERATIONAL_PREFS_NAME = "pulse4_operational_state"
        internal const val CONSERVATIVE_COOLDOWN_MS = 15 * 60_000L

        private val OPERATIONAL_COOLDOWN_UNTIL_KEYS = setOf(
            "garmin.ext.login_cooldown_until_ms",
            "garmin.ext.refresh_blocked_until_ms",
            "withings.auth.net_backoff_until_ms",
        )
        private val OPERATIONAL_FAILURE_COUNT_KEYS = setOf(
            "garmin.ext.login_failures",
            "garmin.ext.refresh_failures",
            "withings.auth.net_failures",
            "withings.auth.other_failures",
        )
        private const val OPERATIONAL_PAUSE_REASON_KEY = "withings.auth.paused_reason"
        private const val OPERATIONAL_PAUSE_MESSAGE_KEY = "withings.auth.paused_message"
        internal val OPERATIONAL_STATE_KEYS = OPERATIONAL_COOLDOWN_UNTIL_KEYS +
            OPERATIONAL_FAILURE_COUNT_KEYS +
            OPERATIONAL_PAUSE_REASON_KEY +
            OPERATIONAL_PAUSE_MESSAGE_KEY
    }
}
