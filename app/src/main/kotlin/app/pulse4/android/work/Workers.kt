package app.pulse4.android.work

import android.content.Context
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
        val now = System.currentTimeMillis()
        sl.importService.runAll(now)
        // Váha: každý zdroj má vlastní stav a vlastní chyby; výpadek jednoho nesmí zablokovat ostatní.
        runCatching { sl.withings.runImport(now) }
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
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
    )

    fun shareNow(ctx: Context, manual: Boolean = false) = WorkManager.getInstance(ctx).enqueueUniqueWork(
        "$SHARE-now", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<ShareWorker>().setInputData(androidx.work.workDataOf(ShareWorker.KEY_MANUAL to manual)).build(),
    )
}
