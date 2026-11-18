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
                PLog.Level.WARN -> if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
                PLog.Level.ERROR -> if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
            }
        }
        // Dvě jedinečné periodické úlohy (import a sdílení); opakované volání je idempotentní.
        Scheduler.schedulePeriodic(this)

        appScope.launch {
            // P0-4: bezpodmínečný purge případného starého Withings hesla ze SecretStore
            runCatching { services.secrets.delete("withings.web.password") }
            // Inicializace nastavení, P2P transportu a mostu (nejprve transport zaregistruje kontakty pro dešifrování příchozích zpráv)
            runCatching {
                services.settings.warmUp()
                services.transport
                services.bridge.ensureStarted()
            }
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
