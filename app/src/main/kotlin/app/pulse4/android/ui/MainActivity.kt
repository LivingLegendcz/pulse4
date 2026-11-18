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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import app.pulse4.android.security.CorruptedSecretIssue
import app.pulse4.android.security.CriticalSecretKind
import app.pulse4.backup.BackupCodec
import app.pulse4.backup.BackupException
import app.pulse4.domain.MetricType
import app.pulse4.domain.PLog
import app.pulse4.ui.Fmt
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            if (sl.vault.probeCriticalSecrets(sl.secrets) != null) {
                return@launch
            }
            runCatching {
                sl.settings.warmUp()
                val u = sl.settings.unitSystem
                val _mp = sl.settings.mapProvider
                val z = runCatching { ZoneId.of(sl.settings.zoneId) }.getOrDefault(ZoneId.systemDefault())
                val updatedFmt = Fmt(z, units = u)
                activeFmt = updatedFmt
                activeFmtFlow.value = updatedFmt
            }.onFailure { e ->
                PLog.e("MainActivity", e) { "Startup warmup failed" }
            }
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

private data class Dest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(sl: app.pulse4.android.di.ServiceLocator, onCompletePlayUpdate: () -> Unit = {}) {
    val recoveryIssue by sl.vault.recoveryIssue.collectAsState()
    val recoveryScope = rememberCoroutineScope()
    val issue = recoveryIssue
    if (issue != null) {
        CriticalSecretRecoveryScreen(
            issue = issue,
            sl = sl,
            onRecovered = {
                recoveryScope.launch(Dispatchers.IO) {
                    runCatching {
                        sl.settings.warmUp()
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

@Composable
private fun CriticalSecretRecoveryScreen(
    issue: CorruptedSecretIssue,
    sl: app.pulse4.android.di.ServiceLocator,
    onRecovered: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmNewIdentity by remember { mutableStateOf(false) }

    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        statusMsg = null
        scope.launch {
            val resultMsg = withContext(Dispatchers.IO) {
                try {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Nelze přečíst soubor zálohy")
                    // Nejprve ověříme a dešifrujeme zálohu, než smažeme poškozený klíč / databázi.
                    val entries = BackupCodec.open(bytes, pass.toCharArray())
                    sl.vault.resetCorruptedForRecovery(
                        secrets = sl.secrets,
                        issue = issue,
                        resetDatabaseToo = (issue.kind == CriticalSecretKind.DB_KEY),
                    )
                    sl.backupService.restore(entries)
                    sl.bridge.ensureStarted()
                    onRecovered()
                    null
                } catch (e: BackupException) {
                    "Chyba hesla nebo poškozená záloha: ${e.message}"
                } catch (e: Exception) {
                    PLog.e("Recovery", e) { "Backup recovery failed" }
                    "Obnova ze zálohy selhala: ${e.message ?: e::class.simpleName}"
                }
            }
            statusMsg = resultMsg
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.55f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Obnova poškozeného šifrovacího klíče",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFEE2E2),
                )
                Text(
                    if (issue.kind == CriticalSecretKind.DB_KEY) {
                        "Nepodařilo se dešifrovat klíč databáze (${issue.secretName}: ${issue.error}). " +
                            "Bez obnovy ze zálohy nelze původní šifrovaná data odemknout."
                    } else {
                        "Nepodařilo se dešifrovat soukromou identitu Reticulum (${issue.secretName}: ${issue.error}). " +
                            "Můžete obnovit data ze zálohy nebo vytvořit novou identitu."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MineralOnSurfaceVariantDark,
                )

                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Heslo k záloze (.p4bk)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = { openBackup.launch(arrayOf("*/*")) },
                    enabled = !busy && pass.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Obnovit ze zálohy")
                }

                OutlinedButton(
                    onClick = { confirmNewIdentity = true },
                    enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFCA5A5)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Vytvořit novou identitu (ztráta párování)")
                }

                statusMsg?.let { msg ->
                    Spacer(Modifier.height(4.dp))
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFCA5A5))
                }
            }
        }
    }

    if (confirmNewIdentity) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmNewIdentity = false },
            title = { Text("Vytvořit novou identitu?") },
            text = {
                Text(
                    if (issue.kind == CriticalSecretKind.DB_KEY) {
                        "Vytvoření nového klíče a identity vymaže nepřístupnou databázi a zruší všechna stávající párování i sdílení. Kontakty bude nutné znovu spárovat."
                    } else {
                        "Vytvoření nové identity zruší stávající párování s kontakty a sdílení; bude nutné zařízení znovu spárovat."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        statusMsg = null
                        scope.launch {
                            val errMsg = withContext(Dispatchers.IO) {
                                runCatching {
                                    sl.vault.resetCorruptedForRecovery(
                                        secrets = sl.secrets,
                                        issue = issue,
                                        resetDatabaseToo = (issue.kind == CriticalSecretKind.DB_KEY),
                                    )
                                    sl.bridge.ensureStarted()
                                    onRecovered()
                                    null
                                }.getOrElse { e ->
                                    PLog.e("Recovery", e) { "New identity creation failed" }
                                    "Vytvoření nové identity selhalo: ${e.message ?: e::class.simpleName}"
                                }
                            }
                            if (errMsg != null) statusMsg = errMsg
                            confirmNewIdentity = false
                            busy = false
                        }
                    },
                ) {
                    Text("Vytvořit novou identitu", color = Color(0xFFFCA5A5), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { confirmNewIdentity = false }) {
                    Text("Zrušit")
                }
            },
        )
    }
}
