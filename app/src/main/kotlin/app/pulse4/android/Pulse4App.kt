package app.pulse4.android

import android.app.Application
import android.os.StrictMode
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
        if (BuildConfig.DEBUG) {
            // L5 (R4-3): v debug buildu je jakékoli čtení / zápis disku na hlavním vlákně fatální, ne jen
            // v migračních rámcích. ThreadPolicy platí jen pro vlákno, které ji nastaví (= hlavní vlákno).
            runCatching {
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .penaltyLog()
                        .penaltyDeath()
                        .build()
                )
            }
        }

        appScope.launch {
            // Dvě jedinečné periodické úlohy (import a sdílení); opakované volání je idempotentní.
            runCatching { Scheduler.schedulePeriodic(this@Pulse4App) }
            // Jednorázová asynchronní migrace + warmUp nastavení pod jedním zámkem před startem transportu (R3-1, R3-13).
            // R4-1: awaitStartupReady nikdy nevyhazuje; false = poškozený / nedostupný klíč → čeká se na obnovu v UI.
            if (!services.awaitStartupReady()) {
                PLog.e("Pulse4App") { "Critical secret corrupted; deferring background startup until recovery" }
                return@launch
            }
            // P0-4: bezpodmínečný purge případného starého Withings hesla ze SecretStore
            runCatching { services.secrets.delete("withings.web.password") }
            // Inicializace P2P transportu a mostu (nejprve transport zaregistruje kontakty pro dešifrování příchozích zpráv)
            runCatching {
                services.transport
                services.bridge.ensureStarted()
                services.runDeferredMigrationsIfNeeded()
            }.onFailure { e ->
                PLog.e("Pulse4App", e) { "Startup initialization failed" }
            }
            if (services.recoveryIssue.value != null) return@launch
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
