package app.pulse4.android.reticulum

import app.pulse4.android.security.SecretStore
import app.pulse4.provider.ProviderError
import app.pulse4.sync.Wire
import app.pulse4.training.GarminWorkoutApi
import app.pulse4.training.RemoteScheduled
import app.pulse4.training.RemoteWorkout
import app.pulse4.training.Workout
import com.chaquo.python.PyException
import com.chaquo.python.Python
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
 * Neoficiální konektor (python-garminconnect) pro zápis a plánování tréninků. Heslo se nikdy neukládá;
 * relace žije v uložených tokenech (šifrovaně přes Keystore). Pád konektoru nesmí zablokovat oficiální čtení.
 *
 * Mutující volání mapují nejasné selhání spojení na [ProviderError.Timeout] (výsledek je nejistý), čtecí na [ProviderError.Network].
 */
class ExtendedGarminApi(private val secrets: SecretStore) : GarminWorkoutApi {
    private val json = Json { ignoreUnknownKeys = true }
    private fun module() = Python.getInstance().getModule("pulse4_garmin.session")

    fun loginPassword(email: String, password: String): LoginResult = call(mutating = false) {
        val r = json.parseToJsonElement(module().callAttr("login_password", email, password).toString()).jsonObject
        finishLogin(r)
    }

    fun resumeMfa(code: String): LoginResult = call(mutating = false) {
        finishLogin(json.parseToJsonElement(module().callAttr("resume_mfa", code).toString()).jsonObject)
    }

    private fun finishLogin(r: kotlinx.serialization.json.JsonObject): LoginResult {
        if (r.getValue("status").jsonPrimitive.content == "needs_mfa") return LoginResult.NeedsMfa
        secrets.putString(SecretStore.GARMIN_EXT_TOKENS, r.getValue("tokens").jsonPrimitive.content)
        return LoginResult.Ok
    }

    /** Obnoví relaci z uložených tokenů; případně rotované tokeny uloží zpět dřív, než se použijí. */
    fun ensureSession(): Boolean {
        val tokens = secrets.getString(SecretStore.GARMIN_EXT_TOKENS) ?: return false
        return call(mutating = false) {
            secrets.putString(SecretStore.GARMIN_EXT_TOKENS, module().callAttr("login_tokens", tokens).toString()); true
        }
    }

    fun logout() { runCatching { module().callAttr("logout") }; secrets.delete(SecretStore.GARMIN_EXT_TOKENS) }

    override fun listWorkouts(): List<RemoteWorkout> = call(false) {
        json.parseToJsonElement(module().callAttr("list_workouts").toString()).jsonArray.map {
            val o = it.jsonObject
            RemoteWorkout(o.getValue("id").jsonPrimitive.content, o.getValue("name").jsonPrimitive.content, "")
        }
    }

    override fun getWorkout(id: String): RemoteWorkout? = call(false) {
        val o = json.parseToJsonElement(module().callAttr("get_workout", id).toString()).jsonObject
        val dto = o["workout"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        // Podpis se počítá stejným kódem jako na straně aplikace (žádná ruční shoda dvou implementací v různých jazycích).
        val sig = dto?.let { Wire.json.decodeFromJsonElement(Workout.serializer(), it).signature() } ?: "unsupported"
        RemoteWorkout(id, o.getValue("name").jsonPrimitive.content, sig)
    }

    override fun upsertWorkout(w: Workout, marker: String, existingId: String?): String = call(true) {
        module().callAttr("upsert_workout", Wire.json.encodeToString(Workout.serializer(), w), marker, existingId).toString()
    }

    override fun listCalendar(date: String): List<RemoteScheduled> = call(false) {
        json.parseToJsonElement(module().callAttr("list_calendar", date).toString()).jsonArray.map {
            val o = it.jsonObject
            RemoteScheduled(o.getValue("calendarId").jsonPrimitive.content, o.getValue("workoutId").jsonPrimitive.content, o.getValue("date").jsonPrimitive.content)
        }
    }

    override fun schedule(workoutId: String, date: String): String = call(true) { module().callAttr("schedule", workoutId, date).toString() }
    override fun requestDeviceSync() { call(false) { module().callAttr("request_device_sync"); Unit } }

    private fun <T> call(mutating: Boolean, block: () -> T): T = try {
        block()
    } catch (e: PyException) {
        val m = e.message.orEmpty()
        throw when {
            "TooManyRequests" in m -> ProviderError.RateLimited(null)
            "Authentication" in m -> ProviderError.Unauthorized()
            "MFA" in m || "needs_mfa" in m -> ProviderError.MfaRequired()
            "Connection" in m || "Timeout" in m || "timed out" in m ->
                if (mutating) ProviderError.Timeout() else ProviderError.Network("garmin ext")
            else -> ProviderError.Server(0)
        }
    }
}
