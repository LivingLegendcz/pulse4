package app.pulse4.provider

import kotlin.math.min
import kotlin.random.Random

/** Operace poskytovatele; `requestDeviceSync` znamená požadavek na odeslání, nikoli důkaz přijetí hodinkami. */
enum class Operation {
    READ_SLEEP, READ_HRV, READ_BODY_BATTERY, READ_HEART_RATE, READ_STEPS, READ_STRESS, READ_WEIGHT, READ_SPO2,
    LIST_ACTIVITIES, DOWNLOAD_ACTIVITY,
    LIST_WORKOUTS, UPSERT_WORKOUT, SCHEDULE_WORKOUT, REQUEST_DEVICE_SYNC,
}

enum class AdapterKind { OFFICIAL, EXTENDED }

interface ProviderAdapter {
    val kind: AdapterKind
    val capabilities: Set<Operation>
}

sealed interface Route {
    data class Use(val adapter: ProviderAdapter) : Route
    /** Funkce vyžaduje neoficiální doplněk, který je vypnutý nebo nepřihlášený – uživatel to musí vidět. */
    data class NeedsExtended(val op: Operation) : Route
    data class Unsupported(val op: Operation) : Route
}

/**
 * Explicitní mapa schopností s preferencí oficiálního zdroje. Výběr závisí výhradně na schopnostech
 * a nastavení, nikdy na tom, že předchozí volání selhalo: chyba neznamená skryté přepnutí na jiné přihlášení.
 */
class CapabilityRouter(
    private val official: ProviderAdapter,
    private val extended: ProviderAdapter?,
    private val extendedEnabled: () -> Boolean,
) {
    fun route(op: Operation): Route = when {
        op in official.capabilities -> Route.Use(official)
        extended != null && op in extended.capabilities ->
            if (extendedEnabled()) Route.Use(extended) else Route.NeedsExtended(op)
        else -> Route.Unsupported(op)
    }

    fun requiresExtended(): Set<Operation> =
        (extended?.capabilities.orEmpty() - official.capabilities)
}

// ---------------------------------------------------------------------------------------------
sealed class ProviderError(message: String) : Exception(message) {
    class Unauthorized : ProviderError("401")
    class Forbidden : ProviderError("403")
    class RateLimited(val retryAfterS: Long?) : ProviderError("429")
    class Network(cause: String) : ProviderError(cause)
    class MfaRequired : ProviderError("MFA")
    /** Endpoint nebo přihlášení není nakonfigurováno/ověřeno podle oficiální specifikace; nic se nevolá. */
    class NotConfigured(val what: String) : ProviderError("not configured: $what")
    class Server(val code: Int) : ProviderError("HTTP $code")
    /** Odpověď po zápisu se nedoručila: výsledek je nejistý, nelze slepě opakovat. */
    class Timeout : ProviderError("timeout")
}

sealed interface RetryAction {
    /** 401: nejvýše jednou zkusit nový access token ze stejného klíče. */
    data object RefreshTokenOnce : RetryAction
    /** Opakovaný 401 po obnově: osobní klíč je neplatný/odvolaný, uživatel musí vložit nový. */
    data object NeedNewApiKey : RetryAction
    data class WaitAndRetry(val delayMs: Long) : RetryAction
    /** 403: žádná přihlašovací smyčka, jen stav. */
    data object ShowForbidden : RetryAction
    data object PauseForMfa : RetryAction
    /** Chybí ověřená konfigurace: uživatel vidí, co je třeba doplnit; žádné opakování. */
    data object ShowNotConfigured : RetryAction
    data object ShowNetworkUnavailable : RetryAction
}

class RetryPolicy(
    private val random: Random = Random.Default,
    private val baseMs: Long = 30_000,
    private val maxMs: Long = 6L * 3600 * 1000,
) {
    fun decide(error: ProviderError, refreshAlreadyTried: Boolean, attempt: Int): RetryAction = when (error) {
        is ProviderError.Unauthorized -> if (refreshAlreadyTried) RetryAction.NeedNewApiKey else RetryAction.RefreshTokenOnce
        is ProviderError.Forbidden -> RetryAction.ShowForbidden
        // Retry-After se respektuje; jinak exponenciální prodleva s náhodným rozptylem.
        is ProviderError.RateLimited -> RetryAction.WaitAndRetry(error.retryAfterS?.let { it * 1000 } ?: backoff(attempt))
        is ProviderError.MfaRequired -> RetryAction.PauseForMfa
        is ProviderError.NotConfigured -> RetryAction.ShowNotConfigured
        is ProviderError.Network -> RetryAction.ShowNetworkUnavailable
        is ProviderError.Server, is ProviderError.Timeout -> RetryAction.WaitAndRetry(backoff(attempt))
    }

    fun backoff(attempt: Int): Long {
        val exp = baseMs * (1L shl min(maxOf(attempt - 1, 0), 20))
        return (min(exp, maxMs) * (0.8 + random.nextDouble() * 0.4)).toLong()
    }
}
