package app.pulse4.android.work

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pulse4.android.Pulse4App
import app.pulse4.android.data.NetworkMode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Context.services get() = (applicationContext as Pulse4App).services

/**
 * Import z Garminu. Potřebuje internet. Výsledek (včetně chyb po oblastech) se ukládá do `area_state`;
 * úloha sama vždy skončí úspěšně, aby periodický plán nezanikl a stav byl čitelný v UI, ne ve frontě workeru.
 */
class ImportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sl = applicationContext.services
        val isManual = inputData.getBoolean(ShareWorker.KEY_MANUAL, false)
        // Pokud běží nepřetržitá služba na pozadí s čerstvým heartbeatom, periodický WorkManager import neduplikujeme.
        if (PulseSyncService.hasFreshHeartbeat(System.currentTimeMillis()) && !isManual) {
            return@withContext Result.success()
        }
        // R4-1: poškozený nebo zamčený klíč nesmí shodit worker; zkusí se znovu podle backoffu.
        if (!sl.awaitStartupReady()) return@withContext Result.retry()
        val now = System.currentTimeMillis()
        // Automatický (periodický) běh nesmí navázat na čerstvý import (ruční nebo ze služby); přežívá restart procesu.
        if (!isManual) {
            val last = runCatching { sl.settings.lastImportAtMs }.getOrNull()
            val decision = AutoImportPolicy.decide(
                nowMs = now,
                lastImportAtMs = last,
                processStartMs = null, // worker často sám startuje proces; ochranná lhůta by ho vyhladověla
                minIntervalMs = AutoImportPolicy.WORKER_MIN_INTERVAL_MS,
            )
            if (!decision.runNow) return@withContext Result.success()
        }
        runCatching { sl.settings.lastImportAtMs = now }
        // 1. Garmin proaktivní keepalive a import
        runCatching { sl.garminExt.ensureSession() }
        runCatching { sl.importService.runAll(now) }

        // 2. Withings proaktivní keepalive (import běží v importService.runAll)
        runCatching {
            sl.withings.renewSession()
        }

        if (sl.settings.healthConnectEnabled) runCatching { sl.healthConnect.sync(now, background = true) }
        // Po úspěšném importu zkusit hned odeslat nové projekce, ať nečekají na další interval.
        if (applicationContext.services.settings.networkMode != NetworkMode.MANUAL) Scheduler.shareNow(applicationContext)
        Result.success()
    }
}

/**
 * Výměna změn mezi aplikacemi přes Reticulum. NEMÁ podmínku validovaného internetu – místní Wi-Fi bez internetu
 * je platná cesta. Pracuje v okně, které přidělí systém; Doze může úlohu odložit (15 minut není záruka).
 */
class ShareWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sl = applicationContext.services
        // R4-1: poškozený nebo zamčený klíč nesmí shodit worker; zkusí se znovu podle backoffu.
        if (!sl.awaitStartupReady()) return@withContext Result.retry()
        if (sl.settings.networkMode == NetworkMode.MANUAL && !inputData.getBoolean(KEY_MANUAL, false)) return@withContext Result.success()
        val now = System.currentTimeMillis()
        runCatching {
            sl.shareService.planAndEnqueue(now)
            sl.transport.pump(now, windowMs = 45_000)
            sl.shareService.flushPlanNotifications(System.currentTimeMillis())
        }
        // Chyba transportu není chyba úlohy: nepotvrzené položky zůstávají v outboxu a stav je vidět v UI.
        Result.success()
    }

    companion object { const val KEY_MANUAL = "manual" }
}

object Scheduler {
    private const val IMPORT = "import"
    private const val SHARE = "share"

    fun schedulePeriodic(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(
            IMPORT, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ImportWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build(),
        )
        wm.enqueueUniquePeriodicWork(
            SHARE, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ShareWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build(),
        )
    }

    /** Ruční požadavek se spojí s již běžící úlohou (KEEP), nevytvoří druhý souběžný import. */
    fun importNow(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniqueWork(
        "$IMPORT-now", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(androidx.work.workDataOf(ShareWorker.KEY_MANUAL to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
    )

    fun shareNow(ctx: Context, manual: Boolean = false): androidx.work.Operation? {
        val appCtx = ctx.applicationContext
        val bgEnabled = runCatching { appCtx.services.settings.backgroundServiceEnabled }.getOrDefault(false)
        if (bgEnabled && !manual) {
            val started = runCatching {
                val intent = Intent(appCtx, PulseSyncService::class.java).apply {
                    action = PulseSyncService.ACTION_SYNC_NOW
                }
                ContextCompat.startForegroundService(appCtx, intent)
                true
            }.getOrDefault(false)
            if (started) return null
        }
        if (manual) {
            PulseSyncService.syncState(appCtx, triggerSyncNow = true)
        }
        return WorkManager.getInstance(appCtx).enqueueUniqueWork(
            "$SHARE-now", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ShareWorker>().setInputData(androidx.work.workDataOf(ShareWorker.KEY_MANUAL to manual)).build(),
        )
    }
}
