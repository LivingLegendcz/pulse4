package app.pulse4.android

import android.app.Application
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.work.PulseSyncService
import app.pulse4.android.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Pulse4App : Application() {
    val services: ServiceLocator by lazy { ServiceLocator(this) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Dvě jedinečné periodické úlohy (import a sdílení); opakované volání je idempotentní.
        Scheduler.schedulePeriodic(this)

        appScope.launch {
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
