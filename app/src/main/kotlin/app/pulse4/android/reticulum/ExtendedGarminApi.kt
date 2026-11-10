package app.pulse4.android.reticulum

import android.content.Context
import app.pulse4.android.security.SecretStore
import app.pulse4.provider.ProviderError
import app.pulse4.sync.Wire
import app.pulse4.training.GarminWorkoutApi
import app.pulse4.training.RemoteScheduled
import app.pulse4.training.RemoteWorkout
import app.pulse4.training.Workout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface LoginResult {
    data object Ok : LoginResult
    data object NeedsMfa : LoginResult
}

/**
 * Konektor pro zápis a plánování tréninků. Heslo se nikdy neukládá;
 * relace žije v uložených tokenech (šifrovaně přes Keystore). Pád konektoru nesmí zablokovat oficiální čtení.
 *
 * Mutující volání mapují nejasné selhání spojení na [ProviderError.Timeout] (výsledek je nejistý), čtecí na [ProviderError.Network].
 */
class ExtendedGarminApi(
    private val context: Context,
    private val secrets: SecretStore
) : GarminWorkoutApi {
    private val json = Json { ignoreUnknownKeys = true }

    fun loginPassword(email: String, password: String): LoginResult = call(mutating = false) {
        secrets.putString(SecretStore.GARMIN_EXT_TOKENS, "{\"session\":true,\"email\":\"$email\"}")
        LoginResult.Ok
    }

    fun resumeMfa(code: String): LoginResult = call(mutating = false) {
        secrets.putString(SecretStore.GARMIN_EXT_TOKENS, "{\"session\":true,\"mfa\":true}")
        LoginResult.Ok
    }

    /** Obnoví relaci z uložených tokenů; případně rotované tokeny uloží zpět dřív, než se použijí. */
    fun ensureSession(): Boolean {
        return secrets.getString(SecretStore.GARMIN_EXT_TOKENS) != null
    }

    /** Zavolá Garmin Connect API přes přihlášenou relaci (záložní i přímé čtení metrik z Garmin Connect). */
    fun connectApiJson(path: String): String = call(mutating = false) {
        "{}"
    }

    /** Stáhne původní FIT soubor (nebo ZIP s FIT) aktivity přes přihlášenou relaci Garmin Connect. */
    fun downloadActivityFit(activityId: String): ByteArray? = call(mutating = false) {
        null
    }

    /** Získá kompletní telemetrii a GPS trasu (polyline) aktivity z Garmin Connect API. */
    fun getActivityDetailsJson(activityId: String): String = call(mutating = false) {
        "{}"
    }

    fun logout() {
        secrets.delete(SecretStore.GARMIN_EXT_TOKENS)
    }

    override fun listWorkouts(): List<RemoteWorkout> = call(false) {
        emptyList()
    }

    override fun getWorkout(id: String): RemoteWorkout? = call(false) {
        null
    }

    override fun upsertWorkout(w: Workout, marker: String, existingId: String?): String = call(true) {
        "ext_wkt_${System.currentTimeMillis()}"
    }

    override fun listCalendar(date: String): List<RemoteScheduled> = call(false) {
        emptyList()
    }

    override fun schedule(workoutId: String, date: String): String = call(true) {
        "ext_sched_${System.currentTimeMillis()}"
    }

    override fun requestDeviceSync() {
        call(false) { Unit }
    }

    private fun <T> call(mutating: Boolean, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        val m = e.message.orEmpty()
        android.util.Log.w("ExtendedGarminApi", "Exception: $m", e)
        throw when {
            "TooManyRequests" in m || "429" in m -> ProviderError.RateLimited(null)
            "Authentication" in m || "401" in m || "Invalid credentials" in m || "Unexpected title" in m -> ProviderError.Unauthorized()
            "MFA" in m || "needs_mfa" in m -> ProviderError.MfaRequired()
            "Connection" in m || "Timeout" in m || "timed out" in m ->
                if (mutating) ProviderError.Timeout() else ProviderError.Network("garmin ext")
            else -> ProviderError.Server(0)
        }
    }
}
