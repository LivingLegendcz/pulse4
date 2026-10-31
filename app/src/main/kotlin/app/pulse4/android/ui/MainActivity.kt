package app.pulse4.android.ui

import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sl = (application as Pulse4App).services
        CoroutineScope(Dispatchers.IO).launch {
            val u = sl.settings.unitSystem
            val _mp = sl.settings.mapProvider
            val z = runCatching { ZoneId.of(sl.settings.zoneId) }.getOrDefault(ZoneId.systemDefault())
            activeFmt = Fmt(z, units = u)
        }
        setContent { Pulse4Theme { Root(sl) } }
        handleCallback(intent)
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
        intent?.getStringExtra("set_gateway")?.let { host ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sl.settings.gatewayHost = host.trim()
                    android.util.Log.i("Pulse4", "Gateway set to $host")
                } catch (e: Exception) {
                    android.util.Log.e("Pulse4", "Failed to set gateway", e)
                }
            }
        }
        if (intent?.getBooleanExtra("run_import", false) == true) {
            app.pulse4.android.work.Scheduler.importNow(this)
        }
        if (intent?.getBooleanExtra("run_share", false) == true) {
            app.pulse4.android.work.Scheduler.shareNow(this, manual = true)
        }
        intent?.getStringExtra("add_contact_b64")?.let { b64 ->
            val raw = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            when (val r = app.pulse4.sync.Pairing.parse(raw)) {
                is app.pulse4.sync.PairingResult.Ok -> {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            sl.bridge.ensureStarted()
                            val derived = sl.bridge.addContact(r.payload.pub)
                            sl.db.contacts().upsert(
                                app.pulse4.android.data.ContactRow(
                                    identity = derived,
                                    displayName = r.payload.name,
                                    publicKeyB64 = r.payload.pub,
                                    fingerprint = r.fingerprint,
                                    verifiedAtMs = System.currentTimeMillis()
                                )
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("Pulse4", "Failed to add contact", e)
                        }
                    }
                }
                is app.pulse4.sync.PairingResult.Invalid -> {
                    android.util.Log.e("Pulse4", "Invalid pairing payload: ${r.reason}")
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
                            sl.db.contacts().upsert(
                                app.pulse4.android.data.ContactRow(
                                    identity = derived,
                                    displayName = r.payload.name,
                                    publicKeyB64 = r.payload.pub,
                                    fingerprint = r.fingerprint,
                                    verifiedAtMs = System.currentTimeMillis()
                                )
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("Pulse4", "Failed to add contact", e)
                        }
                    }
                }
                is app.pulse4.sync.PairingResult.Invalid -> {
                    android.util.Log.e("Pulse4", "Invalid pairing payload: ${r.reason}")
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
                        scope = app.pulse4.sharing.TimeScope.continuousFrom(java.time.LocalDate.now(java.time.ZoneId.of(sl.settings.zoneId)).minusDays(7)),
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
                    android.util.Log.i("Pulse4Pairing", "PAIRING_PAYLOAD:$json")
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
        if (intent?.getBooleanExtra("trigger_pump", false) == true) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val now = System.currentTimeMillis()
                    val enqueued = sl.shareService.planAndEnqueue(now)
                    val stats = sl.transport.pump(now, windowMs = 8000)
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
                    val outboxCount = sl.db.outbox().all().size
                    val actsCount = sl.db.activities().allNow().size
                    val status = sl.bridge.statusJson()
                    android.util.Log.i("Pulse4Status", "STATUS: hash=${info.deliveryHash}, contacts=${contacts.size}, outbox=$outboxCount, activities=$actsCount, rns=$status")
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
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF061320),
    primaryContainer = Color(0xFF0F2942),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = Color(0xFF10B981),
    onSecondary = Color(0xFF052E16),
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFF0A0D14),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF11151F),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1A2030),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF475569)
)

@Composable
fun Pulse4Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ObsidianDarkScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content
        )
    }
}

private data class Dest(val route: String, val label: String, val icon: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(sl: app.pulse4.android.di.ServiceLocator) {
    val nav = rememberNavController()
    val main = listOf(
        Dest("today", "Dnes", "📅"),
        Dest("metrics", "Metriky", "📈"),
        Dest("activities", "Aktivity", "🏃"),
        Dest("training", "Tréninky", "⏱️"),
        Dest("groups", "Skupiny", "👥"),
        Dest("settings", "Nastavení", "⚙️")
    )
    val entry by nav.currentBackStackEntryAsState()
    val units by sl.settings.unitSystemFlow.collectAsState()
    val mapProv by sl.settings.mapProviderFlow.collectAsState()
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
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0E121B),
                    titleContentColor = Color.White
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Pulse4", fontWeight = FontWeight.ExtraBold)
                        Text(
                            if (units == app.pulse4.ui.UnitSystem.IMPERIAL) " · MI/LB" else " · KM/KG",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                },
                actions = {
                    Text(
                        mapProv.shortLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0E121B)) {
                val curRoute = entry?.destination?.route.orEmpty()
                main.forEach { d ->
                    val isSelected = when (d.route) {
                        "today" -> curRoute == "today" || curRoute.startsWith("today_metric/")
                        "activities" -> curRoute == "activities" || curRoute.startsWith("activity/") || curRoute.startsWith("compare/")
                        else -> curRoute == d.route || curRoute.startsWith("${d.route}/")
                    }
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { nav.navigate(d.route) { launchSingleTop = true } },
                        icon = { Text(d.icon, style = MaterialTheme.typography.titleMedium) },
                        label = {
                            Text(
                                d.label,
                                maxLines = 1,
                                softWrap = false,
                                fontSize = 10.sp
                            )
                        }
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, "today", Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            composable("today") {
                TodayScreen(sl) { mType ->
                    nav.navigate("today_metric/${mType.name}")
                }
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
                    }
                )
            }
            composable("settings") { SettingsHubScreen(sl) }
        }
    }
}
