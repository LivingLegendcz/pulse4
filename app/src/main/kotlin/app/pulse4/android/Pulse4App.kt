package app.pulse4.android

import android.app.Application
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class Pulse4App : Application() {
    val services: ServiceLocator by lazy { ServiceLocator(this) }

    override fun onCreate() {
        super.onCreate()
        // Dvě jedinečné periodické úlohy (import a sdílení); opakované volání je idempotentní.
        Scheduler.schedulePeriodic(this)

        // Asynchronní backfill normalizovaných summary sloupců pro bleskurychlé procházení a škálování 10 000+ aktivit
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                services.importService.backfillActivitySummaries()
            }
        }

        // Tichý proaktivní keepalive Garmin & Withings relací při spuštění aplikace
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { services.garminExt.ensureSession() }
            runCatching { services.withings.renewSession() }
        }
    }
}
