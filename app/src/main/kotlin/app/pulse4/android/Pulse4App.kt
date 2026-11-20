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
        app.pulse4.android.work.AppProcess.markStarted()
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
            // L5 (R4-3): v debug buildu se každé čtení / zápis disku na hlavním vlákně loguje (tag StrictMode).
            // Záměrně BEZ penaltyDeath: debug APK používají i reální uživatelé, pád by je potrestal za
            // případné zbývající porušení. Porušení hlídá upgrade test (grep StrictMode v logcatu).
            runCatching {
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .penaltyLog()
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
            // Withings heslo (volitelně zapamatované uživatelem) zůstává šifrované v SecretStore (Keystore);
            // maže se pouze při odpojení účtu nebo při odmítnutí heslem.
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
            // Tichý proaktivní keepalive Garmin & Withings relací: odložen, aby nesoutěžil se startem UI o síť a zámky.
            kotlinx.coroutines.delay(KEEPALIVE_DELAY_MS)
            runCatching { services.garminExt.ensureSession() }
            runCatching { services.withings.renewSession() }
        }

        // Trvalá služba na pozadí (pokud je zapnutá v Nastavení)
        PulseSyncService.syncState(this)
    }

    private val startedElapsedMs = android.os.SystemClock.elapsedRealtime()
    private val lastForegroundRenewElapsedMs = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Called whenever the UI comes to the foreground. Silently renews the Withings session (extend, SSO, stored
     * password) so the user never sees a login prompt for something the app can fix itself. Debounced; the very first
     * launch is covered by the delayed startup keepalive, and the coordinator applies its own cooldowns.
     */
    fun onAppForeground() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - startedElapsedMs < KEEPALIVE_DELAY_MS) return
        val last = lastForegroundRenewElapsedMs.get()
        if (last != 0L && now - last < FOREGROUND_RENEW_DEBOUNCE_MS) return
        if (!lastForegroundRenewElapsedMs.compareAndSet(last, now)) return
        appScope.launch {
            if (!services.awaitStartupReady()) return@launch
            runCatching { services.withings.renewSession() }
        }
    }

    private companion object {
        const val KEEPALIVE_DELAY_MS = 45_000L
        const val FOREGROUND_RENEW_DEBOUNCE_MS = 5L * 60_000L
    }
}
