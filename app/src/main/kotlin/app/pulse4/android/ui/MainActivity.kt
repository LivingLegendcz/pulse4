package app.pulse4.android.ui

import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import app.pulse4.android.ui.theme.GlacierContainerDark
import app.pulse4.android.ui.theme.GlacierOnContainerDark
import app.pulse4.android.ui.theme.GlacierOnSecondaryDark
import app.pulse4.android.ui.theme.GlacierSecondaryDark
import app.pulse4.android.ui.theme.MineralBackgroundDark
import app.pulse4.android.ui.theme.MineralOnSurfaceDark
import app.pulse4.android.ui.theme.MineralOnSurfaceVariantDark
import app.pulse4.android.ui.theme.MineralOutlineDark
import app.pulse4.android.ui.theme.MineralSurfaceDark
import app.pulse4.android.ui.theme.MineralSurfaceVariantDark
import app.pulse4.android.ui.theme.Pulse4Typography
import app.pulse4.android.ui.theme.SageContainerDark
import app.pulse4.android.ui.theme.SageOnContainerDark
import app.pulse4.android.ui.theme.SageOnPrimaryDark
import app.pulse4.android.ui.theme.SagePrimaryDark
import app.pulse4.android.ui.theme.SandContainerDark
import app.pulse4.android.ui.theme.SandOnContainerDark
import app.pulse4.android.ui.theme.SandOnTertiaryDark
import app.pulse4.android.ui.theme.SandTertiaryDark
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.pulse4.android.Pulse4App
import app.pulse4.domain.MetricType
import app.pulse4.ui.Fmt
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val navRequestFlow = MutableStateFlow<String?>(null)
private val activeFmtFlow = MutableStateFlow(activeFmt)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sl = (application as Pulse4App).services
        CoroutineScope(Dispatchers.IO).launch {
            sl.settings.warmUp()
            val u = sl.settings.unitSystem
            val _mp = sl.settings.mapProvider
            val z = runCatching { ZoneId.of(sl.settings.zoneId) }.getOrDefault(ZoneId.systemDefault())
            val updatedFmt = Fmt(z, units = u)
            activeFmt = updatedFmt
            activeFmtFlow.value = updatedFmt
        }
        setContent { Pulse4Theme { Root(sl) } }
        handleCallback(intent)
        app.pulse4.android.work.PulseSyncService.syncState(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent)
    }

    /** Návrat z Withings OAuth přes ověřený HTTPS App Link a obsluha vývojářských / automatizačních intentů. */
    private fun handleCallback(intent: android.content.Intent?) {
        val sl = (application as Pulse4App).services
        intent?.getStringExtra("navigate_to")?.let { route ->
            navRequestFlow.value = route
        }
        intent?.getStringExtra("set_garmin_key")?.let { key ->
            sl.secrets.putString(app.pulse4.android.security.SecretStore.GARMIN_API_KEY, key.trim())
            sl.secrets.putString(app.pulse4.android.security.SecretStore.GARMIN_API_KEY_EXPIRES, (System.currentTimeMillis() + 365L * 86_400_000L).toString())
        }
        intent?.getStringExtra("set_gateway")?.let { hostRaw ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val raw = hostRaw.trim()
                    if (raw.contains(":")) {
                        val parts = raw.split(":")
                        sl.settings.gatewayHost = parts[0].trim()
                        parts[1].trim().toIntOrNull()?.let { sl.settings.gatewayPort = it }
                    } else {
                        sl.settings.gatewayHost = raw
                    }
                    sl.bridge.applyPropagationNode()
                    android.util.Log.i("Pulse4", "Gateway set to ${sl.settings.gatewayHost}:${sl.settings.gatewayPort}")
                } catch (e: Exception) {
                    android.util.Log.e("Pulse4", "Failed to set gateway", e)
                }
            }
        }
        intent?.getStringExtra("add_peer_endpoint")?.let { ep ->
            val parts = ep.split("=")
            if (parts.size == 2) {
                sl.bridge.registerLocalPeer(parts[0].trim(), parts[1].trim())
                android.util.Log.i("Pulse4", "Registered local peer: ${parts[0]} -> ${parts[1]}")
            }
        }
        if (intent?.getBooleanExtra("clear_peer_endpoints", false) == true) {
            sl.bridge.clearLocalPeers()
        }
        if (intent?.getBooleanExtra("send_test_message", false) == true) {
            val to = intent.getStringExtra("to_hash").orEmpty()
            val text = intent.getStringExtra("msg_text") ?: "P2P TEST"
            val forceGateway = intent.getBooleanExtra("force_gateway", false)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sl.transport.forceGateway = forceGateway
                    val selfHash = sl.bridge.ensureStarted().deliveryHash
                    val nowMs = System.currentTimeMillis()
                    val mb = app.pulse4.sync.MessageBuilder(selfHash)
                    val msg = mb.build(
                        recipient = to,
                        kind = app.pulse4.sync.Kinds.PROJECTION_UPSERT,
                        objectType = "daily",
                        objectId = "test-$nowMs",
                        objectVersion = 1L,
                        payload = """{"ownerId":"${sl.settings.displayName}","d":"$text"}""",
                        nowMs = nowMs
                    )
                    val wire = app.pulse4.sync.Wire.encode(msg)
                    sl.outbox.enqueue(
                        app.pulse4.sync.OutboxItem(
                            eventId = msg.envelope.eventId,
                            recipient = to,
                            kind = msg.envelope.kind,
                            objectType = msg.envelope.objectType,
                            objectId = msg.envelope.objectId,
                            objectVersion = msg.envelope.objectVersion,
                            grantId = null,
                            wire = wire,
                            createdAtMs = nowMs
                        )
                    )
                    android.util.Log.i("Pulse4Sync", "ENQUEUED test message ${msg.envelope.eventId} to $to: $text")
                    val stats = sl.transport.pump(System.currentTimeMillis(), 5000L)
                    android.util.Log.i("Pulse4Sync", "PUMP STATS: $stats")
                } catch (e: Throwable) {
                    android.util.Log.e("Pulse4Sync", "Failed to send test message", e)
                }
            }
        }
        if (intent?.getBooleanExtra("run_import", false) == true) {
            app.pulse4.android.work.Scheduler.importNow(this)
        }
        if (intent?.getBooleanExtra("run_share", false) == true) {
            app.pulse4.android.work.Scheduler.shareNow(this, manual = true)
        }
        if (intent?.getBooleanExtra("clear_test_data", false) == true) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    android.util.Log.i("Pulse4", "Vymazávám testovací data a resetuji aktivity...")
                    sl.importService.clearTestDataAndResetActivities()
                    android.util.Log.i("Pulse4", "Spouštím čistou synchronizaci aktivit z Garminu...")
                    val imported = sl.importService.syncGarminActivities(System.currentTimeMillis())
                    android.util.Log.i("Pulse4", "Synchronizace dokončena: importováno $imported aktivit.")
                } catch (e: Exception) {
                    android.util.Log.e("Pulse4", "Chyba při mazání testovacích dat a synchronizaci", e)
                }
            }
        }
        intent?.getStringExtra("import_fit_path")?.let { p ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val bytes = java.io.File(p).readBytes()
                    val res = sl.importService.importFit(bytes, System.currentTimeMillis())
                    android.util.Log.i("Pulse4", "Imported FIT from $p: $res")
                } catch (e: Exception) {
                    android.util.Log.e("Pulse4", "Failed to import FIT from $p", e)
                }
            }
        }
        intent?.getStringExtra("add_contact_b64")?.let { b64 ->
            val raw = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            when (val r = app.pulse4.sync.Pairing.parse(raw)) {
                is app.pulse4.sync.PairingResult.Ok -> {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            sl.bridge.ensureStarted()
                            val derived = sl.bridge.addContact(r.payload.pub)
                            if (!app.pulse4.sync.Pairing.addressMatches(r.payload.addr, derived)) {
                                sl.bridge.removeContact(derived)
                                android.util.Log.e("Pulse4Contact", "REJECTED: Pairing address mismatch for ${r.payload.name} (expected=${r.payload.addr}, derived=$derived)")
                            } else {
                                sl.db.contacts().upsert(
                                    app.pulse4.android.data.ContactRow(
                                        identity = derived,
                                        displayName = r.payload.name,
                                        publicKeyB64 = r.payload.pub,
                                        fingerprint = r.fingerprint,
                                        verifiedAtMs = System.currentTimeMillis()
                                    )
                                )
                                android.util.Log.i("Pulse4Contact", "SUCCESS: Added contact ${r.payload.name} (addr=$derived, fp=${r.fingerprint})")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("Pulse4Contact", "Failed to add contact", e)
                        }
                    }
                }
                is app.pulse4.sync.PairingResult.Invalid -> {
                    android.util.Log.e("Pulse4Contact", "Invalid pairing payload: ${r.reason}")
                }
            }
        }
        intent?.getStringExtra("add_contact_json")?.let { raw ->
            when (val r = app.pulse4.sync.Pairing.parse(raw)) {
                is app.pulse4.sync.PairingResult.Ok -> {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            sl.bridge.ensureStarted()
                            val derived = sl.bridge.addContact(r.payload.pub)
                            if (!app.pulse4.sync.Pairing.addressMatches(r.payload.addr, derived)) {
                                sl.bridge.removeContact(derived)
                                android.util.Log.e("Pulse4Contact", "REJECTED: Pairing address mismatch for ${r.payload.name} (expected=${r.payload.addr}, derived=$derived)")
                            } else {
                                sl.db.contacts().upsert(
                                    app.pulse4.android.data.ContactRow(
                                        identity = derived,
                                        displayName = r.payload.name,
                                        publicKeyB64 = r.payload.pub,
                                        fingerprint = r.fingerprint,
                                        verifiedAtMs = System.currentTimeMillis()
                                    )
                                )
                                android.util.Log.i("Pulse4Contact", "SUCCESS: Added contact ${r.payload.name} (addr=$derived, fp=${r.fingerprint})")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("Pulse4Contact", "Failed to add contact", e)
                        }
                    }
                }
                is app.pulse4.sync.PairingResult.Invalid -> {
                    android.util.Log.e("Pulse4Contact", "Invalid pairing payload: ${r.reason}")
                }
            }
        }
        intent?.getStringExtra("create_group")?.let { groupName ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val self = sl.bridge.ensureStarted().deliveryHash
                    val contacts = sl.db.contacts().allNow().map { it.identity }.toSet()
                    val g = app.pulse4.sharing.Group(
                        java.util.UUID.randomUUID().toString(),
                        groupName.trim(),
                        self,
                        self,
                        contacts + self,
                        1L
                    )
                    sl.shareService.saveGroup(g)
                } catch (e: Exception) {
                    android.util.Log.e("Pulse4", "Failed to create group", e)
                }
            }
        }
        intent?.getStringExtra("create_grant_all")?.let { target ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val self = sl.bridge.ensureStarted().deliveryHash
                    val recipients = if (target == "all") sl.db.contacts().allNow().map { it.identity }.toSet() else setOf(target)
                    val allFields = app.pulse4.sharing.Field.entries.toSet()
                    val g = app.pulse4.sharing.ShareGrant(
                        id = java.util.UUID.randomUUID().toString(),
                        ownerId = self,
                        recipients = recipients,
                        fields = allFields,
                        scope = app.pulse4.sharing.TimeScope.continuousFrom(java.time.LocalDate.now(java.time.ZoneId.of(sl.settings.zoneId)).minusDays(60)),
                        groupId = null,
                        createdAtMs = System.currentTimeMillis()
                    )
                    sl.shareService.saveGrant(g)
                } catch (e: Exception) {
                    android.util.Log.e("Pulse4", "Failed to create grant", e)
                }
            }
        }
        intent?.getStringExtra("seed_inbox_projection")?.let { authorId ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val now = System.currentTimeMillis()
                    val zone = java.time.ZoneId.of(sl.settings.zoneId)
                    val today = java.time.LocalDate.now(zone)
                    for (i in 6 downTo 0) {
                        val d = today.minusDays(i.toLong())
                        val dStr = d.toString()
                        val proj = app.pulse4.sharing.SharedProjection(
                            ownerId = authorId,
                            grantId = "demo-grant",
                            date = dStr,
                            fields = mapOf(
                                app.pulse4.sharing.Field.WEIGHT to kotlinx.serialization.json.JsonPrimitive(74.0 + (6 - i) * 0.2),
                                app.pulse4.sharing.Field.SLEEP_DURATION to kotlinx.serialization.json.JsonPrimitive(27000.0 + (i % 3) * 1200.0),
                                app.pulse4.sharing.Field.HRV_DAILY to kotlinx.serialization.json.JsonPrimitive(54.0 + (i % 4) * 3.0),
                                app.pulse4.sharing.Field.BB_LAST to kotlinx.serialization.json.JsonPrimitive(65.0 + (i % 5) * 6.0),
                                app.pulse4.sharing.Field.HR_DAILY to kotlinx.serialization.json.JsonPrimitive(53.0 - (i % 3) * 1.0)
                            ),
                            measuredAtUtcMs = now - i * 86_400_000L,
                            producedAtMs = now - i * 86_400_000L
                        )
                        val payload = app.pulse4.sync.Wire.json.encodeToString(app.pulse4.sharing.SharedProjection.serializer(), proj)
                        sl.db.inbox().putObject(
                            app.pulse4.android.data.ReceivedRow(
                                author = authorId,
                                objectType = "daily",
                                objectId = dStr,
                                version = 1L,
                                payload = payload
                            )
                        )
                    }
                    // Also seed a realistic shared activity projection for this author
                    val hrPoints = (0 until 60).map { s -> (s * 30_000L) to (142.0 + Math.sin(s / 5.0) * 14.0) }
                    val spdPoints = (0 until 60).map { s -> (s * 30_000L) to (3.3 + Math.cos(s / 6.0) * 0.35) }
                    val elePoints = (0 until 60).map { s -> (s * 30_000L) to (215.0 + s * 1.4) }
                    val routePoints = (0 until 60).map { s ->
                        app.pulse4.sharing.RoutePoint(45.0755 + s * 0.00045, 15.0378 + Math.sin(s / 8.0) * 0.0006)
                    }
                    val actProj = app.pulse4.sharing.ActivityProjection(
                        activityId = "p2p-run-1",
                        sport = app.pulse4.domain.Sport.RUNNING.name,
                        localDate = today.toString(),
                        startUtcMs = now - 7200_000L,
                        totalTimeS = 1800.0,
                        movingTimeS = 1765.0,
                        distanceM = 5840.0,
                        ascentM = 84.0,
                        avgHeartRate = 149,
                        charts = listOf(
                            app.pulse4.sharing.ChartSeries("heartRate", hrPoints),
                            app.pulse4.sharing.ChartSeries("speed", spdPoints),
                            app.pulse4.sharing.ChartSeries("elevation", elePoints)
                        ),
                        route = routePoints
                    )
                    val actPayload = app.pulse4.sync.Wire.json.encodeToString(app.pulse4.sharing.ActivityProjection.serializer(), actProj)
                    sl.db.inbox().putObject(
                        app.pulse4.android.data.ReceivedRow(
                            author = authorId,
                            objectType = "activity",
                            objectId = "p2p-run-1",
                            version = 1L,
                            payload = actPayload
                        )
                    )
                    android.util.Log.i("Pulse4", "Seeded demo projections and shared activity for $authorId")
                } catch (e: Exception) {
                    android.util.Log.e("Pulse4", "Failed to seed inbox", e)
                }
            }
        }
        if (intent?.getBooleanExtra("dump_pairing", false) == true) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val info = sl.bridge.ensureStarted()
                    val p = app.pulse4.sync.PairingPayload(
                        app.pulse4.sync.Pairing.FORMAT,
                        info.publicKeyB64,
                        info.deliveryHash,
                        sl.settings.displayName
                    )
                    val json = app.pulse4.sync.Pairing.encode(p)
                    android.util.Log.i("Pulse4Pairing", "PAIRING_PAYLOAD:$json, port=${info.localPort}")
                } catch (e: Throwable) {
                    android.util.Log.e("Pulse4Pairing", "Failed to dump pairing", e)
                }
            }
        }
        if (intent?.getBooleanExtra("announce", false) == true) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sl.bridge.ensureStarted()
                    sl.bridge.announce()
                } catch (e: Throwable) {
                    android.util.Log.e("Pulse4Sync", "Failed to announce", e)
                }
            }
        }
        if (intent?.getBooleanExtra("resend_all", false) == true) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sl.db.inbox().deleteAllSent()
                    sl.db.outbox().deleteAll()
                    val now = System.currentTimeMillis()
                    val enqueued = sl.shareService.planAndEnqueue(now)
                    android.util.Log.i("Pulse4Sync", "RESEND_ALL: enqueued=$enqueued items into fresh outbox")
                } catch (e: Throwable) {
                    android.util.Log.e("Pulse4Sync", "Failed to resend all", e)
                }
            }
        }
        if (intent?.getBooleanExtra("trigger_pump", false) == true) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val realNow = System.currentTimeMillis()
                    val enqueued = sl.shareService.planAndEnqueue(realNow)
                    val pumpDueTime = realNow + 86_400_000L
                    val stats = sl.transport.pump(pumpDueTime, windowMs = 12000)
                    android.util.Log.i("Pulse4Sync", "PUMP_RESULT: enqueued=$enqueued, sent=${stats.sent}, received=${stats.received}, rejected=${stats.rejected}, pending=${stats.pendingInFlight}")
                } catch (e: Throwable) {
                    android.util.Log.e("Pulse4Sync", "Failed to pump", e)
                }
            }
        }
        if (intent?.getBooleanExtra("dump_status", false) == true) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val info = sl.bridge.ensureStarted()
                    val contacts = sl.db.contacts().allNow()
                    val outboxList = sl.db.outbox().all()
                    val outboxDetails = outboxList.map { "${it.objectType}/${it.objectId}:${it.state}(att=${it.attempts},sz=${it.wire.size})" }
                    val actsCount = sl.db.activities().count()
                    val status = sl.bridge.statusJson()
                    val inDaily = sl.db.inbox().objectsNow("daily").size
                    val inActs = sl.db.inbox().objectsNow("activity").size
                    val recActs = sl.db.activities().recentSummariesNow(5).map { "${it.id}(${it.sport}): ${it.startUtcMs}" }
                    android.util.Log.i("Pulse4Status", "STATUS: hash=${info.deliveryHash}, contacts=${contacts.size}, outbox=${outboxList.size} $outboxDetails, activities=$actsCount, inDaily=$inDaily, inActs=$inActs, recActs=$recActs, rns=$status")
                } catch (e: Throwable) {
                    android.util.Log.e("Pulse4Status", "Failed to dump status", e)
                }
            }
        }

        val data = intent?.data ?: return
        if (data.path != "/withings/callback") return
        val receivedAt = System.currentTimeMillis()
        val url = data.toString()
        intent.data = null
        CoroutineScope(Dispatchers.IO).launch { (application as Pulse4App).services.withings.handleCallback(url, receivedAt) }
    }
}

private val ObsidianDarkScheme = darkColorScheme(
    primary = SagePrimaryDark,
    onPrimary = SageOnPrimaryDark,
    primaryContainer = SageContainerDark,
    onPrimaryContainer = SageOnContainerDark,
    secondary = GlacierSecondaryDark,
    onSecondary = GlacierOnSecondaryDark,
    secondaryContainer = GlacierContainerDark,
    onSecondaryContainer = GlacierOnContainerDark,
    tertiary = SandTertiaryDark,
    onTertiary = SandOnTertiaryDark,
    tertiaryContainer = SandContainerDark,
    onTertiaryContainer = SandOnContainerDark,
    background = MineralBackgroundDark,
    onBackground = MineralOnSurfaceDark,
    surface = MineralSurfaceDark,
    onSurface = MineralOnSurfaceDark,
    surfaceVariant = MineralSurfaceVariantDark,
    onSurfaceVariant = MineralOnSurfaceVariantDark,
    outline = MineralOutlineDark
)

@Composable
fun Pulse4Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ObsidianDarkScheme,
        typography = Pulse4Typography
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content
        )
    }
}

private data class Dest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(sl: app.pulse4.android.di.ServiceLocator) {
    val nav = rememberNavController()
    val main = listOf(
        Dest("today", "Dnes", Icons.Outlined.CalendarToday),
        Dest("metrics", "Metriky", Icons.Outlined.QueryStats),
        Dest("activities", "Aktivity", PulseIcons.Steps),
        Dest("training", "Tréninky", Icons.Outlined.Timer),
        Dest("groups", "Kruhy", Icons.Outlined.Group),
        Dest("settings", "Nastavení", Icons.Outlined.Settings)
    )
    val entry by nav.currentBackStackEntryAsState()
    val units by sl.settings.unitSystemFlow.collectAsState()
    val mapProv by sl.settings.mapProviderFlow.collectAsState()
    val networkMode by sl.settings.networkModeFlow.collectAsState()
    val currentFmt by activeFmtFlow.collectAsState()
    val navReq by navRequestFlow.collectAsState()

    LaunchedEffect(navReq) {
        val target = navReq
        if (!target.isNullOrBlank()) {
            navRequestFlow.value = null
            nav.navigate(target) { launchSingleTop = true }
        }
    }

    val askLan = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 37) askLan.launch("android.permission.ACCESS_LOCAL_NETWORK") }

    Scaffold(
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    SagePrimaryDark.copy(alpha = 0.35f),
                                    GlacierSecondaryDark.copy(alpha = 0.35f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                NavigationBar(
                    containerColor = Color(0xFF070C13),
                    contentColor = MineralOnSurfaceDark,
                    tonalElevation = 0.dp
                ) {
                    val curRoute = entry?.destination?.route.orEmpty()
                    main.forEach { d ->
                        val isSelected = when (d.route) {
                            "today" -> curRoute == "today" || curRoute.startsWith("today_metric/")
                            "activities" -> curRoute == "activities" || curRoute.startsWith("activity/") || curRoute.startsWith("compare/")
                            else -> curRoute == d.route || curRoute.startsWith("${d.route}/")
                        }
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                nav.navigate(d.route) {
                                    popUpTo(nav.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = d.icon,
                                    contentDescription = d.label,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = SagePrimaryDark,
                                selectedTextColor = Color.White,
                                unselectedIconColor = Color(0xFF64748B),
                                unselectedTextColor = Color(0xFF64748B),
                                indicatorColor = SagePrimaryDark.copy(alpha = 0.16f)
                            ),
                            label = {
                                Text(
                                    d.label,
                                    maxLines = 1,
                                    softWrap = false,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        )
                    }
                }
            }
        },
    ) { pad ->
        val gwHealth by sl.bridge.gatewayHealth.collectAsState()
        var dismissedUnreachableKey by remember { mutableStateOf<String?>(null) }
        val healthKey = "${gwHealth.host}:${gwHealth.port}"
        val showUnreachableBar =
            gwHealth.state == app.pulse4.android.reticulum.GatewayHealthState.UNREACHABLE &&
                gwHealth.host.isNotBlank() &&
                networkMode != app.pulse4.android.data.NetworkMode.LOCAL_ONLY &&
                dismissedUnreachableKey != healthKey
        // Ensure Root observes currentFmt so timezone/units updates recompose active screens.
        @Suppress("UNUSED_VARIABLE")
        val observedFmt = currentFmt

        Column(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (showUnreachableBar) {
                Surface(
                    color = Color(0xFF3B1A1E),
                    contentColor = Color(0xFFFCA5A5),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Připojovací uzel ${gwHealth.host}:${gwHealth.port} je nedostupný",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = Color(0xFFFEE2E2)
                            )
                            Text(
                                "Zkontrolujte síť nebo si v Nastavení vyberte jiný uzel ze seznamu veřejných zdrojů.",
                                fontSize = 11.sp,
                                color = Color(0xFFFCA5A5)
                            )
                        }
                        TextButton(
                            onClick = {
                                nav.navigate("settings") { launchSingleTop = true }
                            }
                        ) {
                            Text("Vybrat uzel", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = { dismissedUnreachableKey = healthKey }
                        ) {
                            Text("✕", color = Color(0xFFFCA5A5))
                        }
                    }
                }
            }
            NavHost(nav, "today", Modifier.weight(1f).fillMaxSize()) {
            composable("today") {
                TodayScreen(
                    sl = sl,
                    onOpenGroups = { nav.navigate("groups") },
                    onOpenMetric = { mType ->
                        nav.navigate("today_metric/${mType.name}")
                    },
                    onOpenActivity = { id ->
                        nav.navigate("activity/${Uri.encode(id)}")
                    }
                )
            }
            composable("today_metric/{type}") { backStack ->
                val tName = backStack.arguments?.getString("type").orEmpty()
                val mType = runCatching { MetricType.valueOf(tName) }.getOrDefault(MetricType.SLEEP_DURATION)
                DedicatedMetricDetailScreen(sl, type = mType, onBack = { nav.popBackStack() })
            }
            composable("metrics") {
                MetricsScreen(sl)
            }
            composable("metrics/{type}") { backStack ->
                val tName = backStack.arguments?.getString("type").orEmpty()
                val mType = runCatching { MetricType.valueOf(tName) }.getOrDefault(MetricType.HRV)
                MetricsScreen(sl, initialType = mType)
            }
            composable("activities") {
                ActivitiesScreen(
                    sl = sl,
                    onOpen = { id -> nav.navigate("activity/${Uri.encode(id)}") },
                    onCompare = { ids ->
                        val encoded = ids.joinToString(",") { Uri.encode(it) }
                        nav.navigate("compare/$encoded")
                    }
                )
            }
            composable("activity/{id}") { backStack ->
                val rawId = Uri.decode(backStack.arguments?.getString("id").orEmpty())
                ActivityDetailScreen(sl, rawId, onBack = { nav.popBackStack() })
            }
            composable("compare/{ids}") { backStack ->
                val raw = backStack.arguments?.getString("ids").orEmpty()
                val ids = raw.split(",").filter { it.isNotBlank() }.map { Uri.decode(it) }
                ActivityCompareScreen(sl, ids, onBack = { nav.popBackStack() })
            }
            composable("training") { TrainingScreen(sl) }
            composable("groups") {
                GroupsScreen(
                    sl = sl,
                    onOpenActivity = { id -> nav.navigate("activity/${Uri.encode(id)}") },
                    onCompareActivities = { ids ->
                        val encoded = ids.joinToString(",") { Uri.encode(it) }
                        nav.navigate("compare/$encoded")
                    },
                    onOpenSharingOverview = { nav.navigate("sharing_overview") }
                )
            }
            composable("sharing_overview") {
                SharingOverviewScreen(sl = sl, onBack = { nav.popBackStack() })
            }
            composable("settings") {
                SettingsHubScreen(
                    sl = sl,
                    onOpenSharingOverview = { nav.navigate("sharing_overview") }
                )
            }
        }
        }
    }
}
