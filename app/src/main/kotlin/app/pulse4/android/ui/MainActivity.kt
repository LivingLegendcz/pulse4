package app.pulse4.android.ui

import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.rememberCoroutineScope
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
import app.pulse4.domain.PLog
import app.pulse4.ui.Fmt
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val navRequestFlow = MutableStateFlow<String?>(null)
private val activeFmtFlow = MutableStateFlow(activeFmt)
private val playUpdateDownloadedFlow = MutableStateFlow(false)

class MainActivity : ComponentActivity() {
    private val appUpdateManager: AppUpdateManager by lazy {
        AppUpdateManagerFactory.create(applicationContext)
    }
    private val updateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { /* Uživatel potvrdil nebo odložil aktualizaci v Google Play dialogu. */ }
    private val installStateListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            playUpdateDownloadedFlow.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sl = (application as Pulse4App).services
        CoroutineScope(Dispatchers.IO).launch {
            // R4-1: start (i po otočení obrazovky) nikdy nespadne; poškozený klíč vede na SecretRecoveryOverlay.
            runActivityStartup(sl)
        }
        setContent {
            Pulse4Theme {
                Root(
                    sl = sl,
                    onCompletePlayUpdate = {
                        runCatching { appUpdateManager.completeUpdate() }
                    },
                )
            }
        }
        handleCallback(intent)
        app.pulse4.android.work.PulseSyncService.syncState(this)
        checkPlayInAppUpdate()
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    playUpdateDownloadedFlow.value = true
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { appUpdateManager.unregisterListener(installStateListener) }
        super.onDestroy()
    }

    private fun checkPlayInAppUpdate() {
        runCatching {
            appUpdateManager.registerListener(installStateListener)
            appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    playUpdateDownloadedFlow.value = true
                    return@addOnSuccessListener
                }
                if (
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    runCatching {
                        appUpdateManager.startUpdateFlowForResult(
                            info,
                            updateLauncher,
                            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent)
    }

    /** Návrat z Withings OAuth přes ověřený HTTPS App Link a povolená navigace. */
    private fun handleCallback(intent: android.content.Intent?) {
        intent?.getStringExtra("navigate_to")?.let { route ->
            if (isAllowedNavigationRoute(route)) {
                navRequestFlow.value = route
            }
        }

        val data = intent?.data ?: return
        if (data.path != "/withings/callback") return
        val receivedAt = System.currentTimeMillis()
        val url = data.toString()
        intent.data = null
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                (application as Pulse4App).services.withings.handleCallback(url, receivedAt)
            }.onFailure { e ->
                PLog.e("MainActivity", e) { "Withings callback handling failed" }
            }
        }
    }
}

/**
 * Startovní práce activity mimo hlavní vlákno (R4-1): počká na migraci a nahřeje formátování.
 * Nikdy nevyhazuje výjimku; při poškozeném / nedostupném klíči jen nechá [ServiceLocator.recoveryIssue] nastavené.
 */
internal suspend fun runActivityStartup(sl: app.pulse4.android.di.ServiceLocator) {
    if (!sl.awaitStartupReady()) return
    runCatching {
        val u = sl.settings.unitSystem
        val z = runCatching { ZoneId.of(sl.settings.zoneId) }.getOrDefault(ZoneId.systemDefault())
        val updatedFmt = Fmt(z, units = u)
        activeFmt = updatedFmt
        activeFmtFlow.value = updatedFmt
    }.onFailure { e ->
        if (e is kotlinx.coroutines.CancellationException) throw e
        PLog.e("MainActivity", e) { "Startup warmup failed" }
    }
}

/** Co kořen UI smí ukázat (R4-1, R4-3). */
internal enum class RootContent { RECOVERY, SPLASH, MAIN }

/**
 * Obnovovací obrazovka má přednost; dokud nedoběhne startovní migrace, ukazuje se jen lehký splash,
 * který nesahá na `sl.db` ani `sl.settings` (jinak by hlavní vlákno čekalo na lazy init v IO workeru).
 */
internal fun resolveRootContent(hasRecoveryIssue: Boolean, migrationsCompleted: Boolean): RootContent = when {
    hasRecoveryIssue -> RootContent.RECOVERY
    !migrationsCompleted -> RootContent.SPLASH
    else -> RootContent.MAIN
}

private val ALLOWED_STATIC_ROUTES = setOf(
    "today", "metrics", "activities", "training", "groups", "sharing_overview", "settings"
)

internal fun isAllowedNavigationRoute(route: String): Boolean {
    if (route in ALLOWED_STATIC_ROUTES) return true
    if (route.startsWith("today_metric/")) {
        val typeName = route.removePrefix("today_metric/")
        return MetricType.entries.any { it.name == typeName }
    }
    if (route.startsWith("metrics/")) {
        val typeName = route.removePrefix("metrics/")
        return MetricType.entries.any { it.name == typeName }
    }
    return false
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

/** Lehký splash během startovní migrace: žádný přístup k DB, nastavení ani Keystoru (R4-3). */
@Composable
private fun StartupSplash() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pulse4", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
        Text("Připravuji data…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class Dest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(sl: app.pulse4.android.di.ServiceLocator, onCompletePlayUpdate: () -> Unit = {}) {
    val recoveryIssue by sl.recoveryIssue.collectAsState()
    val migrationsCompleted by sl.migrationsCompleted.collectAsState()
    val recoveryScope = rememberCoroutineScope()
    val issue = recoveryIssue
    if (resolveRootContent(issue != null, migrationsCompleted) == RootContent.SPLASH) {
        StartupSplash()
        return
    }
    if (issue != null) {
        SecretRecoveryOverlay(
            issue = issue,
            sl = sl,
            onRecovered = {
                recoveryScope.launch(Dispatchers.IO) {
                    runCatching {
                        sl.runMigrationsAfterRecovery()
                        val u = sl.settings.unitSystem
                        val z = runCatching { ZoneId.of(sl.settings.zoneId) }.getOrDefault(ZoneId.systemDefault())
                        val updatedFmt = Fmt(z, units = u)
                        activeFmt = updatedFmt
                        activeFmtFlow.value = updatedFmt
                        sl.transport
                        sl.bridge.ensureStarted()
                    }.onFailure { e ->
                        PLog.e("MainActivity", e) { "Post-recovery startup failed" }
                    }
                }
            },
        )
        return
    }

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
        val gwHealth by sl.gatewayHealth.collectAsState()
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

        val playUpdateDownloaded by playUpdateDownloadedFlow.collectAsState()

        Column(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (playUpdateDownloaded) {
                Surface(
                    color = Color(0xFF0F2942),
                    contentColor = Color(0xFFE0F2FE),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Nová verze Pulse4 je stažená a připravená.",
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE0F2FE)
                        )
                        TextButton(onClick = onCompletePlayUpdate) {
                            Text("Restartovat", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
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
            // The default NavHost transition is a 700 ms cross-fade during which both destinations are composed;
            // tab switches should feel instant, so every transition is disabled.
            NavHost(
                nav, "today", Modifier.weight(1f).fillMaxSize(),
                enterTransition = { androidx.compose.animation.EnterTransition.None },
                exitTransition = { androidx.compose.animation.ExitTransition.None },
                popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                popExitTransition = { androidx.compose.animation.ExitTransition.None },
            ) {
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
