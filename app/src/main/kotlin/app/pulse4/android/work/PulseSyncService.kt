package app.pulse4.android.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.pulse4.android.Pulse4App
import app.pulse4.android.R
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.reticulum.GatewayHealthState
import app.pulse4.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Trvalá služba na pozadí (styl Threema / Meshtastic), která po startu telefonu
 * udržuje živé Reticulum spojení, přijímá zprávy přes RNS Link a periodicky
 * stahuje data z Garminu, Withings a Health Connect.
 */
class PulseSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var lastImportAtMs: Long = 0L
    private var lastNotifText: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sl = (applicationContext as Pulse4App).services
        promoteToForeground("Spouštím Reticulum a synchronizaci…")

        if (loopJob?.isActive == true) {
            return START_STICKY
        }

        loopJob = scope.launch {
            if (!sl.settings.backgroundServiceEnabled) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            runCatching {
                sl.bridge.ensureStarted()
                sl.transport
            }.onFailure {
                Log.w(TAG, "Bridge start failed in PulseSyncService", it)
            }

            while (isActive) {
                if (!sl.settings.backgroundServiceEnabled) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }

                val now = System.currentTimeMillis()

                // 1. Periodické stahování zdravotních dat (každých 15 minut).
                if (now - lastImportAtMs >= IMPORT_INTERVAL_MS) {
                    lastImportAtMs = now
                    runCatching {
                        sl.garminExt.ensureSession()
                        sl.importService.runAll(now)
                    }
                    runCatching {
                        sl.withings.renewSession()
                        sl.withings.runImport(now)
                    }
                    if (sl.settings.healthConnectEnabled) {
                        runCatching { sl.healthConnect.sync(now, background = true) }
                    }
                }

                // 2. RNS / LXMF výměna zpráv a příchozích aktualizací.
                if (sl.settings.networkMode != NetworkMode.MANUAL) {
                    runCatching {
                        sl.bridge.ensureStarted()
                        sl.shareService.planAndEnqueue(now)
                        sl.transport.pump(now, windowMs = 15_000L)
                        sl.shareService.flushPlanNotifications(System.currentTimeMillis())
                    }.onFailure {
                        Log.w(TAG, "Background sync tick failed", it)
                    }
                }

                updateNotification(statusText(sl))
                delay(SYNC_TICK_DELAY_MS)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun statusText(sl: app.pulse4.android.di.ServiceLocator): String {
        if (sl.settings.networkMode == NetworkMode.MANUAL) {
            return "Ruční režim sítě • čeká na vyžádání"
        }
        val gw = sl.bridge.gatewayHealth.value
        return when {
            sl.settings.networkMode == NetworkMode.LOCAL_ONLY -> "Lokální Wi-Fi P2P • připraveno"
            gw.state == GatewayHealthState.ONLINE -> "Připojeno (${gw.host}) • zprávy aktivní"
            gw.state == GatewayHealthState.UNREACHABLE -> "Uzel nedostupný • zkouším znovu"
            else -> "Reticulum aktivní • synchronizace na pozadí"
        }
    }

    private fun promoteToForeground(text: String) {
        val notif = buildNotification(text)
        lastNotifText = text
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        if (text == lastNotifText) return
        lastNotifText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Pulse4 běží na pozadí")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Synchronizace na pozadí",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Udržuje připojení k Reticulum a synchronizaci zpráv i po startu telefonu."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "PulseSyncService"
        private const val CHANNEL_ID = "pulse4_background_sync"
        private const val NOTIFICATION_ID = 4109
        private const val IMPORT_INTERVAL_MS = 15L * 60_000L
        private const val SYNC_TICK_DELAY_MS = 30_000L

        /**
         * Bezpečně spustí nebo zastaví službu podle nastavení uživatele (`backgroundServiceEnabled`).
         * Čtení nastavení probíhá na IO vlákně, aby neblokovalo UI.
         */
        fun syncState(context: Context) {
            val appCtx = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                val enabled = runCatching {
                    (appCtx as Pulse4App).services.settings.backgroundServiceEnabled
                }.getOrDefault(true)
                val intent = Intent(appCtx, PulseSyncService::class.java)
                if (enabled) {
                    runCatching {
                        ContextCompat.startForegroundService(appCtx, intent)
                    }.onFailure {
                        Log.w(TAG, "Nelze spustit ForegroundService z aktuálního kontextu", it)
                    }
                } else {
                    runCatching { appCtx.stopService(intent) }
                }
            }
        }
    }
}
