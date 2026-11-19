package app.pulse4.android.di

/**
 * Zapamatované heslo Withings (šifrovaně v SecretStore / Android Keystore) a uložená volba
 * „Pamatovat heslo“ (R4-9).
 *
 * UI heslo nikdy nedostane zpět do viditelného pole: zná jen [hasStoredPassword]. Když uživatel
 * nenapíše nové heslo, posílá `null` a služba si uložené heslo vyzvedne sama přes [resolve].
 */
class WithingsPasswordVault(private val store: WithingsAuthStateStore) {

    fun hasStoredPassword(): Boolean = !store.getString(KEY_PASSWORD).isNullOrEmpty()

    /** Uložená volba „Pamatovat heslo“; výchozí zapnuto (heslo slouží k obnově relace na pozadí). */
    fun rememberPreference(): Boolean = when (store.getString(KEY_REMEMBER)) {
        VALUE_OFF -> false
        else -> true
    }

    /**
     * Uloží volbu „Pamatovat heslo“. Vypnutí okamžitě smaže uložené heslo, aby ho obnova na pozadí
     * dál nepoužívala; zapnutí heslo uloží až při dalším úspěšném přihlášení.
     */
    fun setRememberPreference(remember: Boolean) {
        store.putString(KEY_REMEMBER, if (remember) VALUE_ON else VALUE_OFF)
        if (!remember) store.delete(KEY_PASSWORD)
    }

    /**
     * Heslo pro přihlášení: nově napsané heslo, nebo (`typed == null`) uložené heslo.
     * Prázdné nově napsané heslo vrací `null` (UI ho nemá odeslat).
     */
    fun resolve(typed: String?): String? =
        if (typed == null) store.getString(KEY_PASSWORD)?.takeIf { it.isNotEmpty() }
        else typed.takeIf { it.isNotEmpty() }

    /**
     * Po úspěšném přihlášení: uloží volbu a podle ní heslo uloží / smaže.
     * [password] `null` = přihlášení bez hesla (jen kód) – uložené heslo zůstává, pokud se má pamatovat.
     */
    fun onLoginSucceeded(password: String?, remember: Boolean) {
        store.putString(KEY_REMEMBER, if (remember) VALUE_ON else VALUE_OFF)
        when {
            !remember -> store.delete(KEY_PASSWORD)
            !password.isNullOrEmpty() -> store.putString(KEY_PASSWORD, password)
        }
    }

    companion object {
        const val KEY_PASSWORD = WithingsSessionCoordinator.KEY_PASSWORD
        const val KEY_REMEMBER = "withings.web.remember_password"
        private const val VALUE_ON = "1"
        private const val VALUE_OFF = "0"
    }
}
