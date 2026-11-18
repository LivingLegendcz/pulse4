package app.pulse4.android.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.pulse4.android.Pulse4App
import app.pulse4.android.R
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.reticulum.GatewayHealthState
import app.pulse4.android.ui.MainActivity
import app.pulse4.domain.PLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Úsporná trvalá služba na pozadí (styl Threema / Meshtastic):
 * - Po startu telefonu udržuje Reticulum TCP/Wi-Fi spojení (push-driven přes onEventReceived, bez zbytečného pollingu).
 * - Každých 15 minut (jen při dostupné síti) tiše dotáhne data z Garminu, Withings a Health Connect.
 * - Používá tichou notifikaci s nejnižší prioritou (IMPORTANCE_MIN), aby uživatele nerušila.
 */
class PulseSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
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
        val promoted = try {
            promoteToForeground("Reticulum připraveno na pozadí")
            true
        } catch (e: Exception) {
            PLog.e(TAG, e) { "startForeground failed; falling back to WorkManager" }
            stopSelf()
            Scheduler.schedulePeriodic(applicationContext)
            false
        }
        if (!promoted) return START_NOT_STICKY

        lastHeartbeatAtMs.set(System.currentTimeMillis())

        if (intent?.action == ACTION_SYNC_NOW) {
            wakeSignal.trySend(Unit)
        }

        if (loopJob?.isActive == true) {
            return START_STICKY
        }

        loopJob = scope.launch {
            val bgEnabled = runCatching { sl.settings.backgroundServiceEnabled }.getOrElse { e ->
                PLog.w(TAG, e) { "Cannot read settings in PulseSyncService; stopping service" }
                false
            }
            if (!bgEnabled) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            runCatching {
                sl.transport
                sl.bridge.ensureStarted()
            }.onFailure {
                PLog.w(TAG, it) { "Bridge start failed in PulseSyncService" }
            }

            while (isActive) {
                val stillEnabled = runCatching { sl.settings.backgroundServiceEnabled }.getOrDefault(false)
                if (!stillEnabled) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }

                val now = System.currentTimeMillis()
                lastHeartbeatAtMs.set(now)
                val online = hasActiveNetwork()

                // 1. Periodické stahování zdravotních dat ze zdrojů (každých 15 min, jen když je síť).
                if (online && now - lastImportAtMs >= IMPORT_INTERVAL_MS) {
                    lastImportAtMs = now
                    runCatching { sl.garminExt.ensureSession() }
                    runCatching { sl.importService.runAll(now) }
                    runCatching {
                        sl.withings.renewSession()
                    }
                    if (sl.settings.healthConnectEnabled) {
                        runCatching { sl.healthConnect.sync(now, background = true) }
                    }
                }

                // 2. RNS / LXMF výměna: naplánovat případné nové projekce a odeslat čekající položky.
                if (sl.settings.networkMode != NetworkMode.MANUAL) {
                    runCatching {
                        sl.bridge.ensureStarted()
                        val enqueued = sl.shareService.planAndEnqueue(now)
                        val hasDue = enqueued > 0 || sl.outbox.due(now, localPeers = sl.bridge.localPeerHashes()).isNotEmpty()
                        val hasPropagation = sl.settings.propagationNode.isNotBlank()
                        if (hasDue || hasPropagation) {
                            sl.transport.pump(now, windowMs = if (hasDue) 10_000L else 3_000L)
                            sl.shareService.flushPlanNotifications(System.currentTimeMillis())
                        }
                    }.onFailure {
                        PLog.w(TAG, it) { "Background sync tick failed" }
                    }
                }

                updateNotification(statusText(sl))

                // Úsporné čekání: probudí se buď okamžitě na požadavek (ACTION_SYNC_NOW), nebo za 10 minut.
                withTimeoutOrNull(IDLE_TICK_DELAY_MS) {
                    wakeSignal.receive()
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        lastHeartbeatAtMs.set(0L)
        wakeSignal.close()
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun hasActiveNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun statusText(sl: app.pulse4.android.di.ServiceLocator): String {
        if (sl.settings.networkMode == NetworkMode.MANUAL) {
            return "Ruční režim sítě"
        }
        val gw = sl.bridge.gatewayHealth.value
        return when {
            sl.settings.networkMode == NetworkMode.LOCAL_ONLY -> "Lokální Wi-Fi P2P připraveno"
            gw.state == GatewayHealthState.ONLINE -> "Připojeno (${gw.host}) • zprávy aktivní"
            gw.state == GatewayHealthState.UNREACHABLE -> "Uzel nedostupný • čeká na síť"
            else -> "Reticulum aktivní na pozadí"
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
            .setContentTitle("Pulse4")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
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
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Tichá služba pro příjem zpráv přes Reticulum a průběžnou synchronizaci dat."
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "PulseSyncService"
        private const val CHANNEL_ID = "pulse4_background_sync_min"
        private const val NOTIFICATION_ID = 4109
        private const val IMPORT_INTERVAL_MS = 15L * 60_000L
        private const val IDLE_TICK_DELAY_MS = 10L * 60_000L
        private const val HEARTBEAT_FRESH_MS = 20L * 60_000L
        private val lastHeartbeatAtMs = java.util.concurrent.atomic.AtomicLong(0L)
        const val ACTION_SYNC_NOW = "app.pulse4.android.action.SYNC_NOW"

        fun hasFreshHeartbeat(nowMs: Long): Boolean {
            val ts = lastHeartbeatAtMs.get()
            return ts > 0L && nowMs - ts in 0L..HEARTBEAT_FRESH_MS
        }

        /**
         * Bezpečně spustí nebo zastaví službu podle nastavení uživatele (`backgroundServiceEnabled`).
         * Čtení nastavení probíhá na IO vlákně, aby neblokovalo UI.
         */
        fun syncState(context: Context, triggerSyncNow: Boolean = false) {
            val appCtx = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                val enabled = runCatching {
                    (appCtx as Pulse4App).services.settings.backgroundServiceEnabled
                }.getOrDefault(true)
                val intent = Intent(appCtx, PulseSyncService::class.java).apply {
                    if (triggerSyncNow) action = ACTION_SYNC_NOW
                }
                if (enabled) {
                    runCatching {
                        ContextCompat.startForegroundService(appCtx, intent)
                    }.onFailure {
                        PLog.w(TAG, it) { "Nelze spustit ForegroundService z aktuálního kontextu" }
                    }
                } else {
                    runCatching { appCtx.stopService(intent) }
                }
            }
        }
    }
}
