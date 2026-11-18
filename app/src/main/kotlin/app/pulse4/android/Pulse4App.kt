package app.pulse4.android

import android.app.Application
import android.util.Log
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.work.PulseSyncService
import app.pulse4.android.work.Scheduler
import app.pulse4.domain.PLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Pulse4App : Application() {
    val services: ServiceLocator by lazy { ServiceLocator(this) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PLog.isDebug = BuildConfig.DEBUG
        PLog.sink = PLog.Sink { level, tag, message, throwable ->
            when (level) {
                PLog.Level.DEBUG -> Log.d(tag, message)
                PLog.Level.INFO -> Log.i(tag, message)
                PLog.Level.WARN -> if (!BuildConfig.DEBUG) {
                    // V release R8 odstraňuje přímé Log.w; PLog.w již zprávu redigoval a posílá ji přes Log.e (NEW-10).
                    Log.e(tag, "[WARN] $message")
                } else if (throwable != null) {
                    Log.w(tag, message, throwable)
                } else {
                    Log.w(tag, message)
                }
                PLog.Level.ERROR -> if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
            }
        }
        // Dvě jedinečné periodické úlohy (import a sdílení); opakované volání je idempotentní.
        Scheduler.schedulePeriodic(this)

        appScope.launch {
            if (services.vault.probeCriticalSecrets(services.secrets) != null) {
                PLog.e("Pulse4App") { "Critical secret corrupted; deferring background startup until recovery" }
                return@launch
            }
            // P0-4: bezpodmínečný purge případného starého Withings hesla ze SecretStore
            runCatching { services.secrets.delete("withings.web.password") }
            // Inicializace nastavení, P2P transportu a mostu (nejprve transport zaregistruje kontakty pro dešifrování příchozích zpráv)
            runCatching {
                services.settings.warmUp()
                services.transport
                services.bridge.ensureStarted()
            }.onFailure { e ->
                PLog.e("Pulse4App", e) { "Startup initialization failed" }
            }
            if (services.vault.recoveryIssue.value != null) return@launch
            // Asynchronní backfill normalizovaných summary sloupců pro bleskurychlé procházení a škálování 10 000+ aktivit
            runCatching {
                services.importService.backfillActivitySummaries()
            }
            // Tichý proaktivní keepalive Garmin & Withings relací při spuštění aplikace
            runCatching { services.garminExt.ensureSession() }
            runCatching { services.withings.renewSession() }
        }

        // Trvalá služba na pozadí (pokud je zapnutá v Nastavení)
        PulseSyncService.syncState(this)
    }
}
