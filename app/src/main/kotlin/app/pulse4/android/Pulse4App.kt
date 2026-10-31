package app.pulse4.android

import android.app.Application
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.work.Scheduler

class Pulse4App : Application() {
    val services: ServiceLocator by lazy { ServiceLocator(this) }

    override fun onCreate() {
        super.onCreate()
        // Dvě jedinečné periodické úlohy (import a sdílení); opakované volání je idempotentní.
        Scheduler.schedulePeriodic(this)
    }
}
