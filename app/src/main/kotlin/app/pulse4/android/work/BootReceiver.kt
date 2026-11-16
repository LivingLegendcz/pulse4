package app.pulse4.android.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Po startu telefonu nebo aktualizaci aplikace obnoví periodický WorkManager
 * a spustí trvalou službu na pozadí (pokud ji uživatel nevypnul v Nastavení).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Scheduler.schedulePeriodic(context.applicationContext)
            PulseSyncService.syncState(context.applicationContext)
        }
    }
}
