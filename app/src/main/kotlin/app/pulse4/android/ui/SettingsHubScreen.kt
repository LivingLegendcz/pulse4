@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse4.android.R
import app.pulse4.android.ui.theme.JetBrainsMonoFamily
import app.pulse4.android.data.MapProvider
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.data.toRow
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.reticulum.LoginResult
import app.pulse4.android.security.SecretStore
import app.pulse4.android.work.Scheduler
import app.pulse4.backup.BackupException
import app.pulse4.provider.ProviderError
import app.pulse4.provider.SyncArea
import app.pulse4.sharing.Category
import app.pulse4.sharing.Field
import app.pulse4.sharing.Group
import app.pulse4.sharing.PrivacyZone
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.TimeScope
import app.pulse4.sync.DeliveryState
import app.pulse4.sync.Wire
import app.pulse4.ui.Fmt
import app.pulse4.ui.GrantEditor
import app.pulse4.ui.Labels
import app.pulse4.ui.Status
import app.pulse4.ui.SyncProvider
import app.pulse4.ui.SyncSchedule
import app.pulse4.ui.UnitSystem
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sjednocené Centrum Nastavení na jednom místě:
 * 0 -> Obecné & Jednotky (Metrické vs Imperiální, jméno profilu)
 * 1 -> Globální Sdílení (přepínače sdílení po sekcích pro kontakty a kruhy)
 * 2 -> Zdroje dat & Integrace (Garmin, Withings, senzory, Health Connect, rodinné profily)
 * 3 -> Reticulum P2P Síť (režim, brány, propagation uzel, identita, fronta)
 * 4 -> Soukromí & Záloha (zóny soukromí, mapy, šifrovaný export/obnova)
 */
@Composable
fun SettingsHubScreen(
    sl: ServiceLocator,
    initialTab: Int = 0,
    onOpenSharingOverview: (() -> Unit)? = null,
) {
    var selectedTab by remember { mutableStateOf(initialTab) }
    val tabs = listOf(
        Triple("Jednotky & Mapy", "KALIBRACE", 0),
        Triple("Globální Sdílení", "P2P MATICE", 1),
        Triple("Zdroje dat", "INTEGRACE", 2),
        Triple("Reticulum P2P", "MESH UZEL", 3),
        Triple("Soukromí & Záloha", "TREZOR", 4),
        Triple("Rodina", "SOUHLASY", 5)
    )

    val heroMeta = when (selectedTab) {
        0 -> Triple(
            R.drawable.img_alpine_trail_hero,
            Color(0xFF38BDF8),
            "Kalibrace Telemetrie & Map" to "Soustava jednotek, kartografické vrstvy a lokální profil uzlu."
        )
        1 -> Triple(
            R.drawable.img_nordic_wellness_hero,
            Color(0xFF2DD4BF),
            "Globální P2P Matice Sdílení" to "Určete, které biometrické domény mohou opustit vaše zařízení."
        )
        2 -> Triple(
            R.drawable.img_motif_heart_real,
            Color(0xFF10B981),
            "Datové zdroje & Integrace" to "Připojení k nositelným senzorům, biometrickým platformám a rodinným zařízením."
        )
        3 -> Triple(
            R.drawable.img_motif_hrv_autonomic,
            Color(0xFFA855F7),
            "Suverénní Uzel Reticulum P2P" to "Topologie šifrované sítě, propojovací brány a odchozí fronta."
        )
        4 -> Triple(
            R.drawable.img_sport_tactical_hero,
            Color(0xFFF59E0B),
            "Geofencing Soukromí & Trezor" to "Automatické ořezávání GPS u bydliště a šifrovaná záloha .p4bk."
        )
        else -> Triple(
            R.drawable.img_motif_body_silhouette,
            Color(0xFFA855F7),
            "Rodina & souhlasy" to "Věkový profil, moji rodiče, žádosti dětí a schválená sdílení."
        )
    }

    val (heroBgRes, heroAccent, heroTexts) = heroMeta

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        // ─── AMBITIOUS DYNAMIC OBSERVATORY HEADER & GLASS TAB CONSOLE ───
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF070C14)),
            border = BorderStroke(1.dp, heroAccent.copy(alpha = 0.42f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Box(Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = heroBgRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(0.45f)
                )
                Canvas(Modifier.matchParentSize()) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color(0x99050911), Color(0xD9070C14), Color(0xF5070C14))
                        )
                    )
                    val cx = size.width * 0.88f
                    val cy = size.height * 0.28f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(heroAccent.copy(alpha = 0.30f), Color.Transparent),
                            center = Offset(cx, cy),
                            radius = size.width * 0.5f
                        ),
                        center = Offset(cx, cy),
                        radius = size.width * 0.5f
                    )
                    listOf(28.dp.toPx(), 54.dp.toPx(), 82.dp.toPx()).forEachIndexed { idx, r ->
                        drawCircle(
                            color = heroAccent.copy(alpha = 0.16f - idx * 0.04f),
                            radius = r,
                            center = Offset(cx, cy),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                }

                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF0A1624).copy(alpha = 0.85f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Box(Modifier.size(7.dp).background(heroAccent, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "SOVEREIGN BIOMETRIC NODE · NASTAVENÍ",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = heroAccent,
                                letterSpacing = 1.2.sp,
                                fontSize = 9.sp
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "v${app.pulse4.android.BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                color = heroAccent.copy(alpha = 0.85f),
                                fontSize = 9.5.sp
                            )
                            Text(
                                "·",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF64748B),
                                fontSize = 10.sp
                            )
                            Text(
                                "SEKCE 0${selectedTab + 1}/06",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFCBD5E1),
                                fontSize = 10.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        heroTexts.first,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        heroTexts.second,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )

                    Spacer(Modifier.height(12.dp))

                    // Horizontální skleněná konzole záložek
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tabs.forEach { (label, badge, index) ->
                            val isSelected = selectedTab == index
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) heroAccent else Color(0xFF1E293B)
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) heroAccent.copy(alpha = 0.20f) else Color(0xFF0B121E).copy(alpha = 0.82f)
                                ),
                                modifier = Modifier.clickable { selectedTab = index }
                            ) {
                                Column(
                                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) heroAccent else Color(0xFF64748B),
                                        fontSize = 8.5.sp
                                    )
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        when (selectedTab) {
            0 -> GeneralAndUnitsSection(sl)
            1 -> GlobalSharingSection(sl, onOpenSharingOverview)
            2 -> SourcesSettingsSection(sl)
            3 -> ReticulumSettingsSection(sl)
            4 -> PrivacyAndBackupSection(sl)
            5 -> FamilySettingsSection(sl)
        }
    }
}

@Composable
private fun GeneralAndUnitsSection(sl: ServiceLocator) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val currentUnits by sl.settings.unitSystemFlow.collectAsState()
    val currentMapProvider by sl.settings.mapProviderFlow.collectAsState()
    val currentShowChartPoints by sl.settings.showChartPointsFlow.collectAsState()
    val currentShowAllTodayWidgets by sl.settings.showAllTodayWidgetsFlow.collectAsState()
    val profileData by io(tick) {
        Triple(sl.settings.displayName, sl.settings.zoneId, sl.settings.mapStyleUrl)
    }

    var displayName by remember(profileData) { mutableStateOf(profileData?.first ?: "Já") }
    var zoneId by remember(profileData) { mutableStateOf(profileData?.second ?: ZoneId.systemDefault().id) }
    var customMapUrl by remember(profileData) { mutableStateOf(profileData?.third ?: "") }
    var feedback by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // Výběr Metrické vs Imperiální jednotky
        Card(
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = R.drawable.img_motif_running_stride),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().alpha(0.22f)
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xD9080E18), Color(0xF20A101B))
                            )
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        "SOUSTAVA JEDNOTEK (METRICKÉ VS. IMPERIÁLNÍ)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF38BDF8),
                        letterSpacing = 0.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Zvolená soustava okamžitě přepočítá vzdálenosti, tempo, převýšení i tělesnou hmotnost napříč celou aplikací.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                    Spacer(Modifier.height(10.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = currentUnits == UnitSystem.METRIC,
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    sl.settings.unitSystem = UnitSystem.METRIC
                                    activeFmt = Fmt(ZoneId.of(sl.settings.zoneId), units = UnitSystem.METRIC)
                                    feedback = "Nastaveny metrické jednotky (km, m, kg, min/km)."
                                    tick++
                                }
                            },
                            label = { Text("Metrické (km, kg)", maxLines = 1, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = currentUnits == UnitSystem.IMPERIAL,
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    sl.settings.unitSystem = UnitSystem.IMPERIAL
                                    activeFmt = Fmt(ZoneId.of(sl.settings.zoneId), units = UnitSystem.IMPERIAL)
                                    feedback = "Nastaveny imperiální jednotky (mi, ft, lb, min/mi)."
                                    tick++
                                }
                            },
                            label = { Text("Imperiální (mi, lb)", maxLines = 1, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    LabeledRow("Vzdálenost & tempo", if (currentUnits == UnitSystem.IMPERIAL) "Míle (mi) · min/mi" else "Kilometry (km) · min/km")
                    LabeledRow("Nadmořská výška", if (currentUnits == UnitSystem.IMPERIAL) "Stopy (ft)" else "Metry (m)")
                    LabeledRow("Tělesná hmotnost", if (currentUnits == UnitSystem.IMPERIAL) "Libry (lb)" else "Kilogramy (kg)")
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Zobrazení karet na Dnes a bodů v grafech
        Card(
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.28f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "ZOBRAZENÍ DNES A GRAFŮ",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF38BDF8),
                    letterSpacing = 0.8.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "Všechny biometrické karty na Dnes",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            "Zobrazí na domovské obrazovce všechny karty najednou (vypnutím se filtrují podle vybraného pilíře).",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                    Switch(
                        checked = currentShowAllTodayWidgets,
                        onCheckedChange = { enabled ->
                            scope.launch(Dispatchers.IO) {
                                sl.settings.showAllTodayWidgets = enabled
                            }
                        }
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E293B)))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "Body měření v grafech",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            "Vykreslí na křivkách grafů malé puntíky v místech, kde byla hodnota skutečně naměřená.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                    Switch(
                        checked = currentShowChartPoints,
                        onCheckedChange = { enabled ->
                            showChartPointsState = enabled
                            scope.launch(Dispatchers.IO) {
                                sl.settings.showChartPoints = enabled
                            }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Výběr poskytovatele mapového podkladu (Map Provider)
        Card(
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.35f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = R.drawable.img_alpine_trail_hero),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().alpha(0.24f)
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xD9080E18), Color(0xF20A101B))
                            )
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        "KARTOGRAFICKÝ ENGINE AKTIVIT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF2DD4BF),
                        letterSpacing = 0.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Zvolte mapový podklad pro vykreslování GPS tras v detailu a překryvném porovnání aktivit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                    Spacer(Modifier.height(10.dp))

                    FlowRow(Modifier.fillMaxWidth()) {
                        MapProvider.entries.forEach { mp ->
                            FilterChip(
                                selected = currentMapProvider == mp,
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        sl.settings.mapProvider = mp
                                        feedback = "Mapový podklad změněn na: ${mp.title}"
                                        tick++
                                    }
                                },
                                label = { Text(mp.shortLabel, maxLines = 1) },
                                modifier = Modifier.padding(end = 6.dp, bottom = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        currentMapProvider.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        currentMapProvider.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                    Text(
                        "Atribuce: ${currentMapProvider.attribution}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    if (currentMapProvider == MapProvider.CUSTOM_URL) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customMapUrl,
                            onValueChange = { customMapUrl = it },
                            label = { Text("Vlastní URL stylu mapy (style.json)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    sl.settings.mapStyleUrl = customMapUrl.trim()
                                    feedback = "Vlastní URL mapového stylu uložena."
                                    tick++
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Uložit vlastní URL mapy") }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Profil uživatele a roleta s časovými pásmy
        var zoneMenuExpanded by remember { mutableStateOf(false) }
        var zoneSearch by remember { mutableStateOf("") }
        val pinnedZones = remember {
            listOf(
                "Europe/Prague" to "Praha / Střední Evropa (Europe/Prague)",
                "Europe/Bratislava" to "Bratislava (Europe/Bratislava)",
                "Europe/Vienna" to "Vídeň (Europe/Vienna)",
                "Europe/Berlin" to "Berlín (Europe/Berlin)",
                "Europe/Warsaw" to "Varšava (Europe/Warsaw)",
                "Europe/London" to "Londýn (Europe/London)",
                "UTC" to "Světový čas (UTC)",
                "America/New_York" to "New York (America/New_York)",
                "America/Chicago" to "Chicago (America/Chicago)",
                "America/Los_Angeles" to "Los Angeles (America/Los_Angeles)",
                "Asia/Tokyo" to "Tokio (Asia/Tokyo)",
                "Australia/Sydney" to "Sydney (Australia/Sydney)"
            )
        }
        val allZones = remember {
            val pinnedIds = pinnedZones.map { it.first }.toSet()
            pinnedZones + ZoneId.getAvailableZoneIds()
                .filter { it !in pinnedIds && !it.startsWith("SystemV") && !it.startsWith("Etc/") }
                .sorted()
                .map { it to it }
        }
        val filteredZones = remember(zoneSearch, allZones) {
            val q = zoneSearch.trim().lowercase()
            if (q.isEmpty()) allZones.take(35)
            else allZones.filter { it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }.take(40)
        }

        Card(
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.35f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "IDENTITA PROFILU A ČASOVÉ PÁSMO",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFA855F7),
                    letterSpacing = 0.8.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Moje jméno (zobrazí se v QR kódu a kruhu)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Časové pásmo (roleta):",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFCBD5E1),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val sysZone = ZoneId.systemDefault().id
                            zoneId = sysZone
                            scope.launch(Dispatchers.IO) {
                                sl.settings.zoneId = sysZone
                                activeFmt = Fmt(ZoneId.of(sysZone), units = sl.settings.unitSystem)
                                feedback = "Nastaveno pásmo podle telefonu: $sysZone"
                                tick++
                            }
                        }
                    ) {
                        Text("Podle telefonu", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                    }
                }

                // Roleta (Dropdown) pro výběr časového pásma
                Box(Modifier.fillMaxWidth()) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (zoneMenuExpanded) Color(0xFF38BDF8) else Color(0xFF334155)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141D2E)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { zoneMenuExpanded = !zoneMenuExpanded }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val currentLabel = allZones.firstOrNull { it.first == zoneId }?.second ?: zoneId
                            Text(
                                currentLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (zoneMenuExpanded) "▲" else "▼",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = zoneMenuExpanded,
                        onDismissRequest = { zoneMenuExpanded = false },
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .heightIn(max = 340.dp)
                            .background(Color(0xFF1E293B))
                    ) {
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                            OutlinedTextField(
                                value = zoneSearch,
                                onValueChange = { zoneSearch = it },
                                label = { Text("Hledat město nebo zónu…") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        filteredZones.forEach { (id, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        label,
                                        fontWeight = if (id == zoneId) FontWeight.Bold else FontWeight.Normal,
                                        color = if (id == zoneId) Color(0xFF38BDF8) else Color.White
                                    )
                                },
                                onClick = {
                                    zoneId = id
                                    zoneMenuExpanded = false
                                    scope.launch(Dispatchers.IO) {
                                        val validZone = runCatching { ZoneId.of(id) }.getOrNull()
                                        if (validZone != null) {
                                            sl.settings.zoneId = validZone.id
                                            activeFmt = Fmt(validZone, units = sl.settings.unitSystem)
                                            feedback = "Časové pásmo změněno na: ${validZone.id}"
                                            tick++
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val validZone = runCatching { ZoneId.of(zoneId.trim()) }.getOrNull() ?: ZoneId.systemDefault()
                            sl.settings.displayName = displayName.trim().ifBlank { "Já" }
                            sl.settings.zoneId = validZone.id
                            activeFmt = Fmt(validZone, units = sl.settings.unitSystem)
                            feedback = "Jméno profilu ('${sl.settings.displayName}') a časové pásmo (${validZone.id}) uloženo."
                            tick++
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Uložit profil")
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // O aplikaci & Informace o sestavení
        val clipboardManager = LocalClipboardManager.current
        Card(
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0xFF64748B).copy(alpha = 0.35f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val info = "Pulse4 v${app.pulse4.android.BuildConfig.VERSION_NAME} (build ${app.pulse4.android.BuildConfig.VERSION_CODE}) · SDK ${android.os.Build.VERSION.SDK_INT}"
                    clipboardManager.setText(AnnotatedString(info))
                    feedback = "Informace o verzi zkopírovány do schránky."
                }
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "O APLIKACI · SESTAVENÍ",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF94A3B8),
                            letterSpacing = 0.8.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Pulse4 Sovereign Biometrics",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "v${app.pulse4.android.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                LabeledRow("Kód sestavení (build)", "${app.pulse4.android.BuildConfig.VERSION_CODE}")
                LabeledRow("Cílová platforma", "Android 15+ (API ${android.os.Build.VERSION.SDK_INT})")
                LabeledRow("Typ buildu", if (app.pulse4.android.BuildConfig.DEBUG) "Debug (vývojový)" else "Release (Google Play)")

                Spacer(Modifier.height(8.dp))
                Text(
                    "Klepnutím zkopírujete verzi a podrobnosti do schránky.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B),
                    fontSize = 11.sp
                )
            }
        }

        feedback?.let {
            Spacer(Modifier.height(10.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Globální sdílení po sekcích na jednom místě:
 * Uživatel si zapne/vypne přepínače jednotlivých sekcí (Spánek, Body Battery, Tep, HRV, Aktivity, Tělo)
 * a zvolí, zda sdílet všem kontaktům nebo vybranému kruhu.
 */
@Composable
private fun GlobalSharingSection(
    sl: ServiceLocator,
    onOpenSharingOverview: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val grants by sl.db.grants().all().collectAsState(emptyList())
    val contacts by sl.db.contacts().all().collectAsState(emptyList())
    val groups by sl.db.grants().groups().collectAsState(emptyList())
    val grantStateFlow = remember(sl) { sl.shareService.consentStateByGrant() }
    val grantConsentStates by grantStateFlow.collectAsState(emptyMap())

    val activeGrants = remember(grants) {
        grants.mapNotNull { runCatching { Wire.json.decodeFromString(ShareGrant.serializer(), it.json) }.getOrNull() }
            .filter { it.active }
    }
    val directGrants = remember(activeGrants) { GrantEditor.directGrants(activeGrants) }

    var enabledCategoryNames by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var selectedRecipients by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var extraActFieldNames by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var selectedSourceChoiceName by rememberSaveable { mutableStateOf<String?>(GrantEditor.SourceChoice.ALL.name) }
    var sourceModified by rememberSaveable { mutableStateOf(false) }
    var isDirty by rememberSaveable { mutableStateOf(false) }

    val incompatibleDirectGrants = remember(directGrants) { GrantEditor.hasIncompatibleDirectGrants(directGrants) }

    LaunchedEffect(directGrants, isDirty) {
        if (!isDirty) {
            val form = GrantEditor.initialGlobalForm(directGrants)
            enabledCategoryNames = form.enabledCategories.map { it.name }.toSet()
            selectedRecipients = form.selectedRecipients
            extraActFieldNames = form.extraActivityFields.map { it.name }.toSet()
            selectedSourceChoiceName = form.sourceChoice?.name
            sourceModified = false
        }
    }

    val enabledCategories = remember(enabledCategoryNames) {
        enabledCategoryNames.mapNotNull { runCatching { Category.valueOf(it) }.getOrNull() }.toSet()
    }
    val extraActivityFields = remember(extraActFieldNames) {
        extraActFieldNames.mapNotNull { runCatching { Field.valueOf(it) }.getOrNull() }.toSet()
    }
    val selectedSourceChoice = remember(selectedSourceChoiceName) {
        selectedSourceChoiceName?.let { name -> runCatching { GrantEditor.SourceChoice.valueOf(name) }.getOrNull() }
    }
    val resolvedSources = remember(selectedSourceChoice, directGrants, sourceModified) {
        GrantEditor.resolveSources(selectedSourceChoice, directGrants.firstOrNull()?.sources, sourceModified)
    }
    val today = remember(sl.settings.zoneId) { LocalDate.now(ZoneId.of(sl.settings.zoneId)) }
    val currentDiff = remember(directGrants, enabledCategories, selectedRecipients, extraActivityFields, resolvedSources, today) {
        GrantEditor.computeGlobalDiff(directGrants, enabledCategories, selectedRecipients, extraActivityFields, resolvedSources, today = today)
    }
    var showConfirmSaveDialog by remember { mutableStateOf(false) }
    var pendingRevokeGrant by remember { mutableStateOf<ShareGrant?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val sectionDefs = listOf(
        Triple(Category.SLEEP, "Spánek" to "Délka spánku, skóre kvality a fáze spánku", R.drawable.img_motif_sleep_night),
        Triple(Category.BODY_BATTERY, "Body Battery & Regenerace" to "Aktuální stav energie, denní min/max a křivka", R.drawable.img_motif_body_battery),
        Triple(Category.HEART_RATE, "Kardio & Klidový tep" to "Klidová tepová frekvence a denní průběh", R.drawable.img_motif_heart_pulse),
        Triple(Category.HRV, "Variabilita tepu (HRV)" to "Noční rMSSD průměr a průběh regenerace", R.drawable.img_motif_hrv_autonomic),
        Triple(Category.ACTIVITY, "Aktivity & Trasy" to "Sportovní záznamy, souhrny, křivky a sanitizované GPS trasy", R.drawable.img_alpine_trail_hero),
        Triple(Category.BODY_COMPOSITION, "Tělesné složení & Váha" to "Tělesná hmotnost a procento tělesného tuku", R.drawable.img_motif_body_silhouette)
    )

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Card(
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.35f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "GLOBÁLNÍ NASTAVENÍ SDÍLENÍ PO SEKCÍCH",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF2DD4BF),
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        "${enabledCategories.size}/6 AKTIVNÍ",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Zapněte biometrické domény, které chcete automaticky šifrovaně sdílet přes P2P. Nezapnuté sekce nikdy neopustí váš telefon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCBD5E1)
                )
                Spacer(Modifier.height(10.dp))

                // Rychlá tlačítka
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            enabledCategoryNames = Category.entries.map { it.name }.toSet()
                            isDirty = true
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Zapnout vše") }
                    OutlinedButton(
                        onClick = {
                            enabledCategoryNames = emptySet()
                            isDirty = true
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Vypnout vše") }
                }

                Spacer(Modifier.height(10.dp))

                sectionDefs.forEach { (cat, texts, bgRes) ->
                    val (title, subtitle) = texts
                    val isOn = cat in enabledCategories
                    val catColor = cat.unifiedColor()
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (isOn) catColor.copy(alpha = 0.55f) else Color(0xFF1E293B)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1524)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                enabledCategoryNames = if (isOn) enabledCategoryNames - cat.name else enabledCategoryNames + cat.name
                                isDirty = true
                            }
                    ) {
                        Box(Modifier.fillMaxWidth()) {
                            Image(
                                painter = painterResource(id = bgRes),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .matchParentSize()
                                    .alpha(if (isOn) 0.28f else 0.12f)
                            )
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color(0xE60A101C), Color(0xCC0E1524))
                                        )
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.Surface(
                                    shape = CircleShape,
                                    color = catColor.copy(alpha = if (isOn) 0.22f else 0.08f),
                                    border = BorderStroke(1.dp, catColor.copy(alpha = if (isOn) 0.55f else 0.22f)),
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = cat.unifiedIcon(),
                                            contentDescription = null,
                                            tint = if (isOn) catColor else Color(0xFF64748B),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1))
                                }
                                Switch(
                                    checked = isOn,
                                    onCheckedChange = { checked ->
                                        enabledCategoryNames = if (checked) enabledCategoryNames + cat.name else enabledCategoryNames - cat.name
                                        isDirty = true
                                    }
                                )
                            }
                        }
                    }
                }

                if (Category.ACTIVITY in enabledCategories) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Citlivé detaily aktivit (výchozí je pouze souhrn):",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFDE68A)
                    )
                    listOf(
                        Field.ACT_ROUTE to "GPS trasa (může odhalit polohu)",
                        Field.ACT_EXACT_TIME to "Přesný čas startu a konce",
                        Field.ACT_FULL_RECORD to "Úplný FIT záznam (obsahuje přesný čas)",
                    ).forEach { (field, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = field in extraActivityFields,
                                onCheckedChange = { checked ->
                                    extraActFieldNames = if (checked) extraActFieldNames + field.name else extraActFieldNames - field.name
                                    isDirty = true
                                }
                            )
                            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Zdroj zdravotních dat:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    GrantEditor.SourceChoice.entries.forEach { choice ->
                        FilterChip(
                            selected = selectedSourceChoice == choice,
                            onClick = {
                                selectedSourceChoiceName = choice.name
                                sourceModified = true
                                isDirty = true
                            },
                            label = { Text(choice.label) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Příjemci globálního sdílení:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                if (contacts.isEmpty()) {
                    Text(
                        "Zatím nemáte spárované žádné kontakty (přidejte je v záložce Kruhy → Kontakty).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    FlowRow(Modifier.padding(vertical = 4.dp)) {
                        FilterChip(
                            selected = selectedRecipients.containsAll(contacts.map { it.identity }),
                            onClick = {
                                val allIds = contacts.map { it.identity }.toSet()
                                selectedRecipients = if (selectedRecipients.containsAll(allIds)) emptySet() else allIds
                                isDirty = true
                            },
                            label = { Text("Všechny kontakty (${contacts.size})") },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        contacts.forEach { c ->
                            FilterChip(
                                selected = c.identity in selectedRecipients,
                                onClick = {
                                    selectedRecipients = if (c.identity in selectedRecipients) selectedRecipients - c.identity else selectedRecipients + c.identity
                                    isDirty = true
                                },
                                label = { Text(c.displayName) },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }
                }

                if (incompatibleDirectGrants) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.55f)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1607)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Máte více samostatných přímých oprávnění s různými příjemci, poli nebo zdroji. Globální profil je nemůže sloučit, aby nedošlo k nechtěnému rozšíření sdílení. Upravte je jednotlivě v Přehledu sdílení.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFDE68A)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = (contacts.isNotEmpty() || directGrants.isNotEmpty()) &&
                        (selectedRecipients.isNotEmpty() || directGrants.isNotEmpty()) &&
                        !incompatibleDirectGrants &&
                        currentDiff.hasChanges,
                    onClick = { showConfirmSaveDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Uložit globální profil sdílení")
                }

                if (showConfirmSaveDialog) {
                    val diff = currentDiff
                    val isMinor = sl.settings.ageState != app.pulse4.sharing.AgeState.ADULT
                    val isRevokingAll = selectedRecipients.isEmpty() || enabledCategories.isEmpty()
                    val needsWideningConfirm = !isRevokingAll && (diff.scopeWidened || diff.planRightsWidened)
                    val needsGuardianConsent = isMinor && !isRevokingAll &&
                        (diff.addedByRecipient.isNotEmpty() || diff.scopeWidened || diff.planRightsWidened)
                    var wideningConfirmed by remember(showConfirmSaveDialog) { mutableStateOf(false) }
                    fun contactName(id: String) = contacts.firstOrNull { it.identity == id }?.displayName ?: id.take(10)
                    fun categoryLabel(cat: Category) = sectionDefs.firstOrNull { it.first == cat }?.second?.first ?: cat.name
                    fun formatScope(scope: TimeScope): String = when (scope.kind) {
                        TimeScope.Kind.ONCE -> "Jednorázově (${scope.from ?: "?"}–${scope.to ?: "?"})"
                        TimeScope.Kind.FROM -> "Od ${scope.from ?: "?"}"
                        TimeScope.Kind.CONTINUOUS -> "Průběžně od ${scope.from ?: "?"}"
                    }
                    AlertDialog(
                        onDismissRequest = { showConfirmSaveDialog = false },
                        containerColor = Color(0xFF0A101B),
                        titleContentColor = Color.White,
                        textContentColor = Color(0xFFCBD5E1),
                        title = {
                            Text(
                                if (isRevokingAll) "Odvolat přímé globální sdílení?" else "Potvrdit změny přímého sdílení",
                                fontWeight = FontWeight.ExtraBold
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                diff.addedByRecipient.forEach { (recipientId, cats) ->
                                    Text(
                                        "Nově uvidí ${contactName(recipientId)}: ${cats.joinToString(", ") { categoryLabel(it) }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF34D399)
                                    )
                                }
                                diff.removedByRecipient.forEach { (recipientId, cats) ->
                                    Text(
                                        "Přestane vidět ${contactName(recipientId)}: ${cats.joinToString(", ") { categoryLabel(it) }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFF59E0B)
                                    )
                                }
                                if (diff.sourcesChanged) {
                                    Text(
                                        "Zdroj dat: ${selectedSourceChoice?.label ?: "Vlastní výběr"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF38BDF8)
                                    )
                                }
                                if (diff.activityFieldsChanged) {
                                    Text(
                                        "Změna sdílených detailů aktivit.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF38BDF8)
                                    )
                                }
                                if (diff.scopeWidened && diff.newScope != null) {
                                    Text(
                                        "Rozšíření časového rozsahu: ${diff.widenedFromScopes.joinToString(", ") { formatScope(it) }} → ${formatScope(diff.newScope!!)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFF59E0B)
                                    )
                                } else if (diff.scopeChanged) {
                                    Text(
                                        "Rozsah historie: ${diff.oldScope?.let(::formatScope) ?: "?"} → ${diff.newScope?.let(::formatScope) ?: "?"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF38BDF8)
                                    )
                                }
                                if (diff.planRightsWidened) {
                                    Text(
                                        "Rozšíření oprávnění k tréninkovým plánům (${diff.widenedPlanIds.sorted().joinToString(", ")}) oproti některému slučovanému grantu.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFF59E0B)
                                    )
                                }
                                if (isRevokingAll) {
                                    Text(
                                        "Všechna přímá globální oprávnění budou odvolána; data, která nesdílí jiný grant, budou u příjemců smazána.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFF59E0B)
                                    )
                                } else {
                                    Text(
                                        "Sdílení v kruzích a rodinných vazbách zůstane beze změny. Zachovává se původní rozsah historie (u nového grantu posledních 7 dní).",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                                if (needsWideningConfirm) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = wideningConfirmed, onCheckedChange = { wideningConfirmed = it })
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "Potvrzuji rozšíření časového rozsahu nebo práv k tréninkovému plánu při sloučení grantů.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFFDE68A)
                                        )
                                    }
                                }
                                if (needsGuardianConsent) {
                                    Text(
                                        "Účet nezletilého: rozšíření se odešle rodiči ke schválení a zapne se až po jeho souhlasu. Zúžení se uloží hned. " +
                                            app.pulse4.ui.Labels.DELIVERED_DATA_NOTE,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFFDE68A)
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                enabled = !needsWideningConfirm || wideningConfirmed,
                                onClick = {
                                    showConfirmSaveDialog = false
                                    if (incompatibleDirectGrants || !diff.hasChanges) return@Button
                                    scope.launch {
                                        val resultMsg = withContext(Dispatchers.IO) {
                                            val now = System.currentTimeMillis()
                                            val self = sl.bridge.ensureStarted().deliveryHash
                                            val saveToday = LocalDate.now(ZoneId.of(sl.settings.zoneId))
                                            val newGrant = GrantEditor.buildGlobalGrant(
                                                id = UUID.randomUUID().toString(),
                                                ownerId = self,
                                                existingDirectGrants = directGrants,
                                                selectedCategories = enabledCategories,
                                                selectedRecipients = selectedRecipients,
                                                extraActivityFields = extraActivityFields,
                                                today = saveToday,
                                                nowMs = now,
                                                sources = resolvedSources,
                                            )
                                            sl.shareService.saveGlobalDirectProfile(
                                                newGrant = newGrant,
                                                existingDirectGrants = directGrants,
                                                nowMs = now,
                                            ).fold(
                                                onSuccess = { saved ->
                                                    isDirty = false
                                                    if (saved != null) {
                                                        "Globální sdílení uloženo (${enabledCategories.size} sekcí, ${saved.fields.size} polí) a zařazeno k odeslání."
                                                    } else {
                                                        "Přímé globální sdílení bylo vypnuto. Kruhy zůstaly beze změny."
                                                    }
                                                },
                                                onFailure = { err ->
                                                    err.message ?: "Uložení globálního sdílení selhalo."
                                                }
                                            )
                                        }
                                        notice = resultMsg
                                    }
                                }
                            ) {
                                Text(
                                    when {
                                        isRevokingAll -> "Potvrdit odvolání"
                                        needsGuardianConsent -> "Odeslat rodiči ke schválení"
                                        else -> "Potvrdit a uložit"
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showConfirmSaveDialog = false }) { Text("Zrušit", color = Color(0xFF94A3B8)) }
                        }
                    )
                }

                if (onOpenSharingOverview != null) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onOpenSharingOverview,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Otevřít Sdílení a oprávnění")
                    }
                }
            }
        }

        notice?.let {
            Spacer(Modifier.height(10.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("Přehled aktivních oprávnění (${activeGrants.size})")
        if (activeGrants.isEmpty()) {
            Text("Momentálně nejsou aktivní žádná oprávnění ke sdílení.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
        }
        activeGrants.forEach { g ->
            Card(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.35f)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Aktivní grant · ${g.recipients.size} příjemců · ${g.fields.size} polí",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        TextButton(onClick = { pendingRevokeGrant = g }) {
                            Text("Odvolat", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(g.fields.joinToString { app.pulse4.ui.Labels.field(it) }, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    grantConsentStates[g.id]?.let { st ->
                        Spacer(Modifier.height(6.dp))
                        ConsentStateChip(st)
                    }
                }
            }
        }

        pendingRevokeGrant?.let { g ->
            AlertDialog(
                onDismissRequest = { pendingRevokeGrant = null },
                containerColor = Color(0xFF0A101B),
                titleContentColor = Color.White,
                textContentColor = Color(0xFFCBD5E1),
                title = { Text("Odvolat oprávnění?", fontWeight = FontWeight.ExtraBold) },
                text = {
                    Text(
                        "Sdílení pro ${g.recipients.size} příjemců (${g.fields.size} polí) se okamžitě zastaví a data, která nesdílí jiný grant, budou u příjemce smazána.",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        val targetId = g.id
                        pendingRevokeGrant = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val now = System.currentTimeMillis()
                                sl.shareService.revoke(targetId, now)
                                sl.shareService.planAndEnqueue(now)
                                sl.shareService.scheduleShare()
                            }
                            notice = "Sdílení odvoláno."
                        }
                    }) { Text("Odvolat") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRevokeGrant = null }) { Text("Zrušit", color = Color(0xFF94A3B8)) }
                }
            )
        }
    }
}

@Composable
private fun SourcesSettingsSection(sl: ServiceLocator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val states by sl.db.areaStates().all().collectAsState(emptyList())
    val wState by sl.withings.state.collectAsState()
    var sub by rememberSaveable { mutableStateOf(0) }
    var apiKey by remember { mutableStateOf("") }
    var editingGarminKey by rememberSaveable { mutableStateOf(false) }
    var editingGarminLogin by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mfa by rememberSaveable { mutableStateOf("") }
    var needMfa by rememberSaveable { mutableStateOf(false) }
    var withingsClientId by remember { mutableStateOf("") }
    var withingsSecret by remember { mutableStateOf("") }
    var minKgInput by remember { mutableStateOf("") }
    var maxKgInput by remember { mutableStateOf("") }
    var childWeightSendKg by remember { mutableStateOf("") }
    var manualWeightKg by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var withingsDisconnecting by remember { mutableStateOf(false) }
    // R4-5: odpojení nikdy na UI vlákně – čeká na zámek autentizace (max. 5 s) a ukazuje průběh.
    val disconnectWithings: () -> Unit = {
        if (!withingsDisconnecting) {
            withingsDisconnecting = true
            notice = WITHINGS_DISCONNECTING_TEXT
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { sl.withings.disconnect() }.getOrDefault(false) }
                withingsDisconnecting = false
                notice = if (ok) "Withings byl odpojen." else WITHINGS_DISCONNECT_BUSY_TEXT
                tick++
            }
        }
    }
    var showKeyHelp by remember { mutableStateOf(false) }
    var showWithingsHelp by remember { mutableStateOf<Boolean?>(null) }
    var showFilter by remember { mutableStateOf(false) }
    var showWebLoginDialog by remember { mutableStateOf(false) }
    var webLoginMode by remember { mutableStateOf(WithingsDialogMode.FullLogin) }

    val contacts by sl.db.contacts().all().collectAsState(emptyList())
    val wCfg by io(tick) {
        WithingsSettingsSnapshot(
            clientId = sl.settings.withingsClientId,
            hasCreds = sl.withings.hasCredentials(),
            profileLabel = sl.settings.withingsProfileLabel,
            filterEnabled = sl.settings.weightFilterEnabled,
            minKg = sl.settings.weightFilterMinKg,
            maxKg = sl.settings.weightFilterMaxKg,
            childRecipientIdentity = sl.settings.childRecipientIdentity,
            childAutoForward = sl.settings.childWeightAutoForward,
            childMinKg = sl.settings.childWeightMinKg,
            childMaxKg = sl.settings.childWeightMaxKg,
            acceptFromParent = sl.settings.acceptWeightFromParent,
            childWithingsUserId = sl.withings.childUserId()
        )
    }
    val keyExpires by io(tick + states.size) {
        val raw = sl.secrets.getString(SecretStore.GARMIN_API_KEY_EXPIRES)?.toLongOrNull()
        if (raw != null && raw <= System.currentTimeMillis() + 2L * 86_400_000L) {
            sl.secrets.delete(SecretStore.GARMIN_API_KEY_EXPIRES)
            null
        } else raw
    }
    val savedGarminKey by io(tick) { sl.secrets.getString(SecretStore.GARMIN_API_KEY) }
    val hasGarminKey = !savedGarminKey.isNullOrBlank()
    val garminLoggedInFromStore by io(tick) { sl.secrets.getString(SecretStore.GARMIN_EXT_TOKENS) != null }
    var garminLoggedInImmediate by remember { mutableStateOf<Boolean?>(null) }
    val garminLoggedIn = garminLoggedInImmediate ?: garminLoggedInFromStore
    var expandGarminSync by rememberSaveable { mutableStateOf(false) }
    var expandWithingsSync by rememberSaveable { mutableStateOf(false) }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            notice = withContext(Dispatchers.IO) {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext "Soubor nelze přečíst."
                val r = sl.withings.importCsv(bytes)
                "Naimportováno ${r.items.size} měření" +
                    if (r.errors.isNotEmpty()) " (přeskočeno ${r.errors.size})" else ""
            }
            tick++
        }
    }

    // Systémový výběr účtu (Nastavení → Účty), bez nebezpečných oprávnění
    val accountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pickedName = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
        if (!pickedName.isNullOrBlank()) email = pickedName
    }

    fun openUrl(url: String) {
        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    val webSession by io(tick) { sl.withings.loadWebSession() }
    // Single source of truth for the child label (follows the R4-6 binding, never an adult profile).
    val shownChild by io(tick) { sl.withings.displayedChild() }
    val wConnected = wState is app.pulse4.android.di.WithingsUiState.Connected || webSession?.isConnected() == true
    val garminOk = hasGarminKey == true || garminLoggedIn == true
    val latestBody by io(tick + states.size) { sl.withings.latestBodyComposition(730) }

    if (showWebLoginDialog) {
        WithingsWebLoginDialog(
            sl = sl,
            mode = webLoginMode,
            onDismiss = { showWebLoginDialog = false },
            onSuccess = { profile ->
                showWebLoginDialog = false
                notice = "Withings připojen k profilu: ${profile.displayName}"
                tick++
            }
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // Atmosférický přepínač biometrické služby (Garmin | Withings & Váha)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val withingsFailed = wState is app.pulse4.android.di.WithingsUiState.Failed
            listOf(
                Triple(R.drawable.img_motif_heart_pulse, "Garmin Connect", garminOk),
                Triple(R.drawable.img_motif_body_silhouette, "Withings & Váha", wConnected && !withingsFailed)
            ).forEachIndexed { i, (bgRes, label, ok) ->
                val selected = sub == i
                val isWithingsAlert = label.startsWith("Withings") && withingsFailed
                val cardAccent = when {
                    isWithingsAlert -> Color(0xFFEF4444)
                    selected -> Color(0xFF38BDF8)
                    ok -> Color(0xFF10B981)
                    else -> Color(0xFF334155)
                }
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(if (selected) 1.5.dp else 1.dp, cardAccent.copy(alpha = if (selected) 0.85f else 0.40f)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
                    modifier = Modifier.weight(1f).clickable { sub = i; notice = null }
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = bgRes),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(if (selected) 0.34f else 0.16f)
                        )
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0x99080E18), Color(0xEB0A101B))
                                    )
                                )
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                            Spacer(Modifier.height(2.dp))
                            val statusText = when {
                                isWithingsAlert -> "⚠️ vyžaduje login"
                                ok -> "● připojeno"
                                else -> "○ nenastaveno"
                            }
                            val statusColor = when {
                                isWithingsAlert -> Color(0xFFF87171)
                                ok -> Color(0xFF10B981)
                                else -> Color(0xFF94A3B8)
                            }
                            Text(
                                statusText,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFamily,
                                color = statusColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        notice?.let {
            Spacer(Modifier.height(10.dp))
            Card(
                Modifier.fillMaxWidth().clickable { notice = null },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(12.dp))

        SyncIntervalCard(if (sub == 0) SyncProvider.GARMIN else SyncProvider.WITHINGS, sl)
        Spacer(Modifier.height(12.dp))

        when (sub) {
            // ───────────────────────── GARMIN ─────────────────────────
            0 -> {
                fun summarizeGarminSync(res: Map<SyncArea, app.pulse4.provider.AreaResult>?): String {
                    if (res == null) return "Synchronizace již probíhá."
                    val okAreas = res.values.filterIsInstance<app.pulse4.provider.AreaResult.Success>()
                    val failedAreas = res.entries.mapNotNull { (a, r) ->
                        when (r) {
                            is app.pulse4.provider.AreaResult.Failed -> "${a.name}: ${Labels.error(r.error)}"
                            is app.pulse4.provider.AreaResult.NeedsUser -> "${a.name}: vyžaduje přihlášení/klíč"
                            else -> null
                        }
                    }
                    val totalSaved = okAreas.sumOf { it.saved }
                    return if (okAreas.isNotEmpty() && failedAreas.isEmpty()) {
                        "Garmin synchronizován (${okAreas.size} oblastí, uloženo $totalSaved záznamů)."
                    } else if (okAreas.isNotEmpty()) {
                        "Garmin částečně synchronizován (${okAreas.size}/${res.size} oblastí, $totalSaved záznamů). Chyby: ${failedAreas.joinToString(", ")}"
                    } else {
                        "Synchronizace Garmin selhala (${failedAreas.joinToString(", ")}). Zkontrolujte klíč nebo přihlášení."
                    }
                }

                AccountCard("Přihlášení Garmin Connect", if (garminLoggedIn == true) "Přihlášeno" else null) {
                    Text(
                        "Pro přímé stahování metrik, aktivit a zápis tréninků do hodinek.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                    if (garminLoggedIn == true && !editingGarminLogin) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    email.ifBlank { "Garmin Connect účet" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    "Šifrovaná relace uložena v Keystore",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF10B981)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF064E3B))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "AKTIVNÍ",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF34D399)
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !syncing,
                                onClick = {
                                    scope.launch {
                                        syncing = true
                                        notice = null
                                        val res = withContext(Dispatchers.IO) { sl.importService.runAll(System.currentTimeMillis()) }
                                        notice = summarizeGarminSync(res)
                                        syncing = false
                                        tick++
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (syncing) "Stahuji…" else "Synchronizovat", maxLines = 1, softWrap = false) }
                            OutlinedButton(
                                onClick = { editingGarminLogin = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("Přepojit účet", maxLines = 1, softWrap = false) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                garminLoggedInImmediate = false
                                editingGarminLogin = false
                                scope.launch(Dispatchers.IO) { sl.garminExt.logout(); tick++ }
                                notice = "Odhlášeno z Garmin Connect."
                            }) { Text("Odhlásit", color = MaterialTheme.colorScheme.error) }
                        }
                    } else {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("E-mail") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(
                            onClick = {
                                runCatching {
                                    accountPickerLauncher.launch(
                                        android.accounts.AccountManager.newChooseAccountIntent(null, null, null, null, null, null, null)
                                    )
                                }.onFailure { notice = "Výběr účtu nelze otevřít." }
                            }
                        ) { Text("Vyplnit e-mail z účtů v telefonu", maxLines = 1, softWrap = false) }
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Heslo") },
                            supportingText = { Text("Může doplnit Bitwarden / správce hesel") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (needMfa || mfa.isNotBlank() || sl.garminExt.hasPendingMfa()) {
                            OutlinedTextField(
                                value = mfa,
                                onValueChange = { mfa = it },
                                label = { Text("Ověřovací kód (ověřovací aplikace (TOTP) / e-mail / SMS)") },
                                placeholder = { Text("Zadejte kód, pokud jej Garmin vyžádal") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Button(
                            enabled = !syncing && ((mfa.isNotBlank() && (needMfa || email.isNotBlank())) || (email.isNotBlank() && password.isNotBlank())),
                            onClick = {
                                scope.launch {
                                    syncing = true
                                    var loginSucceeded = false
                                    val loginMsg = withContext(Dispatchers.IO) {
                                        try {
                                            val r = if (mfa.isNotBlank() && (needMfa || sl.garminExt.hasPendingMfa())) {
                                                sl.garminExt.resumeMfa(mfa)
                                            } else {
                                                sl.garminExt.loginPassword(email, password)
                                            }
                                            when (r) {
                                                LoginResult.Ok -> {
                                                    loginSucceeded = true
                                                    null
                                                }
                                                LoginResult.NeedsMfa -> {
                                                    needMfa = true
                                                    "Zadejte ověřovací kód z e-mailu, SMS nebo ověřovací aplikace (TOTP) do pole výše a klikněte na Potvrdit kód."
                                                }
                                            }
                                        } catch (e: ProviderError.MfaRequired) {
                                            needMfa = true
                                            Labels.loginError(e)
                                        } catch (e: Exception) {
                                            Labels.loginError(e)
                                        }
                                    }
                                    if (loginSucceeded) {
                                        // Okamžitě skryjeme pole pro heslo a přepneme kartu na přihlášený účet:
                                        password = ""
                                        mfa = ""
                                        needMfa = false
                                        editingGarminLogin = false
                                        garminLoggedInImmediate = true
                                        tick++
                                        notice = "Přihlášeno do Garmin Connect ($email). Probíhá synchronizace dat…"
                                        val syncRes = withContext(Dispatchers.IO) {
                                            runCatching { sl.importService.runAll(System.currentTimeMillis()) }.getOrNull()
                                        }
                                        notice = "Přihlášeno do Garmin Connect. " + summarizeGarminSync(syncRes)
                                    } else {
                                        notice = loginMsg
                                    }
                                    syncing = false
                                    tick++
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (syncing) "Přihlašuji a synchronizuji…" else if (needMfa || mfa.isNotBlank()) "Potvrdit kód a přihlásit" else "Přihlásit") }
                        if (garminLoggedIn == true && editingGarminLogin) {
                            TextButton(onClick = { editingGarminLogin = false }) {
                                Text("Zrušit úpravu")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                AccountCard(
                    "Osobní API klíč",
                    if (hasGarminKey) "Aktivní" else null,
                    onHelp = { showKeyHelp = !showKeyHelp }
                ) {
                    Text(
                        "Oficiální Personal API klíč pro čtení spánku, tepu, HRV a aktivit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                    if (showKeyHelp) {
                        HelpBox(
                            listOf(
                                "Přihlaste se na Garmin Developer Portal.",
                                "V sekci Personal API vygenerujte klíč (začíná GCDC_).",
                                "Vložte ho sem a klikněte na Uložit a synchronizovat."
                            )
                        ) {
                            OutlinedButton(
                                onClick = { openUrl("https://developer.garmin.com/gc-developer-program/") },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Otevřít developer.garmin.com", maxLines = 1, softWrap = false) }
                        }
                    }

                    if (hasGarminKey && !editingGarminKey) {
                        val maskedKey = remember(savedGarminKey) {
                            val k = savedGarminKey.orEmpty().trim()
                            if (k.length > 10) "${k.take(6)}••••••••${k.takeLast(4)}" else "Uložen v Keystore"
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    maskedKey,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Status.keyExpiry(keyExpires, System.currentTimeMillis())?.let { exp ->
                                    Text(
                                        "Platnost z API: ${exp.text}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (exp.warn) MaterialTheme.colorScheme.error else Color(0xFF10B981)
                                    )
                                } ?: Text(
                                    "Klíč bezpečně uložen v Keystore",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF10B981)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF064E3B))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "ULOŽEN",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF34D399)
                                )
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !syncing,
                                onClick = {
                                    scope.launch {
                                        syncing = true
                                        notice = null
                                        val res = withContext(Dispatchers.IO) { sl.importService.runAll(System.currentTimeMillis()) }
                                        notice = summarizeGarminSync(res)
                                        syncing = false
                                        tick++
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (syncing) "Stahuji…" else "Synchronizovat", maxLines = 1, softWrap = false) }
                            OutlinedButton(
                                onClick = {
                                    apiKey = ""
                                    editingGarminKey = true
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Změnit klíč", maxLines = 1, softWrap = false) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        sl.secrets.delete(SecretStore.GARMIN_API_KEY)
                                        sl.secrets.delete(SecretStore.GARMIN_API_KEY_EXPIRES)
                                        notice = "Osobní API klíč byl odebrán."
                                        tick++
                                    }
                                }
                            ) { Text("Odebrat klíč", color = MaterialTheme.colorScheme.error) }
                        }
                    } else {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("Osobní klíč (GCDC_…)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !syncing && apiKey.isNotBlank(),
                                onClick = {
                                    val trimmed = apiKey.trim().removePrefix("ApiKey ").trim()
                                    scope.launch {
                                        syncing = true
                                        notice = withContext(Dispatchers.IO) {
                                            if (trimmed.startsWith("W3") || (trimmed.startsWith("[") && trimmed.contains("oauth_token"))) {
                                                val parsed = sl.garminExt.parseTokens(trimmed)
                                                if (parsed != null) {
                                                    sl.secrets.delete(SecretStore.GARMIN_API_KEY)
                                                    sl.secrets.delete(SecretStore.GARMIN_API_KEY_EXPIRES)
                                                    apiKey = ""
                                                    editingGarminKey = false
                                                    val res = runCatching { sl.importService.runAll(System.currentTimeMillis()) }.getOrNull()
                                                    "Garmin relace (Garth) úspěšně rozpoznána a uložena. " + summarizeGarminSync(res)
                                                } else {
                                                    sl.secrets.putString(SecretStore.GARMIN_API_KEY, trimmed)
                                                    sl.secrets.delete(SecretStore.GARMIN_API_KEY_EXPIRES)
                                                    apiKey = ""
                                                    editingGarminKey = false
                                                    val res = runCatching { sl.importService.runAll(System.currentTimeMillis()) }.getOrNull()
                                                    "Klíč uložen. " + summarizeGarminSync(res)
                                                }
                                            } else {
                                                sl.secrets.putString(SecretStore.GARMIN_API_KEY, trimmed)
                                                // Smažeme případnou umělou expiraci – nastaví se pouze tehdy, když ji vrátí samo API
                                                sl.secrets.delete(SecretStore.GARMIN_API_KEY_EXPIRES)
                                                apiKey = ""
                                                editingGarminKey = false
                                                val res = runCatching { sl.importService.runAll(System.currentTimeMillis()) }.getOrNull()
                                                "Klíč uložen. " + summarizeGarminSync(res)
                                            }
                                        }
                                        syncing = false
                                        tick++
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (syncing) "Ověřuji a stahuji…" else "Uložit a synchronizovat", maxLines = 1, softWrap = false) }
                            if (hasGarminKey && editingGarminKey) {
                                OutlinedButton(
                                    onClick = {
                                        apiKey = ""
                                        editingGarminKey = false
                                    }
                                ) { Text("Zrušit", maxLines = 1, softWrap = false) }
                            }
                        }
                    }
                }
            }

            // ───────────────────────── WITHINGS ─────────────────────────
            else -> {
                val conn = wState as? app.pulse4.android.di.WithingsUiState.Connected
                val failed = wState as? app.pulse4.android.di.WithingsUiState.Failed
                val isWeb = conn?.isWeb == true || webSession != null
                val profName = conn?.userName ?: webSession?.primaryUserName ?: wCfg?.profileLabel?.ifBlank { null }
                val wSyncState = states.firstOrNull { it.area == "withings.WEIGHT" }
                val lastWithingsSyncMs = wSyncState?.lastSuccessMs ?: conn?.lastSyncMs ?: webSession?.lastSyncMs

                if (failed != null) {
                    Card(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF450A0A)),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.8f))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Withings vyžaduje přihlášení / obnovení",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Text(
                                failed.message.takeIf { it.isNotBlank() }
                                    ?: "Webová relace vypršela na serveru Withings. Pro pokračování stahování tělesných dat se znovu přihlaste heslem nebo účet odpojte.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFCA5A5)
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        webLoginMode = WithingsDialogMode.Reconnect
                                        showWebLoginDialog = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                    modifier = Modifier.weight(1.3f)
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Znovu přihlásit", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = disconnectWithings,
                                    enabled = !withingsDisconnecting,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFCA5A5)),
                                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Outlined.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Odpojit")
                                }
                            }
                        }
                    }
                }

                if (wConnected) {
                    AccountCard("Withings účet", if (isWeb) "Web propojen" else "API propojeno") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        profName ?: "Withings Uživatel",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        if (isWeb) "Přímý webový konektor (ID ${conn?.userId ?: webSession?.primaryUserId?.toString() ?: ""})" else "Vývojářské API OAuth 2.0",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF38BDF8)
                                    )
                                    val shownChildName = shownChild?.displayName ?: if (!isWeb) conn?.childName else null
                                    if (shownChildName != null) {
                                        Text(
                                            "Dětský profil: $shownChildName",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFA7F3D0)
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (failed != null) Color(0xFF7F1D1D) else Color(0xFF064E3B))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        if (failed != null) "VYŽADUJE LOGIN" else "AKTIVNÍ",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (failed != null) Color(0xFFFCA5A5) else Color(0xFF34D399)
                                    )
                                }
                            }

                            Text(
                                "Stahuje kompletní tělesná data: váhu, % tuku, svalovou hmotu, beztukovou hmotu, hydrataci těla, kosti, viscerální tuk, cévní věk, PWV, BMR, tep a ECG.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )

                            if (lastWithingsSyncMs != null && lastWithingsSyncMs > 0L) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Poslední synchronizace:", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                    Text(fmt.time(lastWithingsSyncMs), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Color(0xFF10B981))
                                }
                            }
                            if (!wSyncState?.lastError.isNullOrBlank()) {
                                Text(
                                    "Poslední chyba: ${Labels.syncError(wSyncState?.lastError.orEmpty())}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFF87171)
                                )
                            }

                            // Adult profiles are intentionally never listed or switchable here: Withings shares data
                            // between the adults of an account, so only the (bound) child is shown (see label above).

                            if (!latestBody.isNullOrEmpty()) {
                                val summaryParts = buildList {
                                    latestBody?.get(app.pulse4.domain.MetricType.WEIGHT)?.let { add("Váha ${"%.1f".format(java.util.Locale.US, it.value)} kg") }
                                    latestBody?.get(app.pulse4.domain.MetricType.BODY_FAT)?.let { add("Tuk ${"%.1f".format(java.util.Locale.US, it.value)} %") }
                                    latestBody?.get(app.pulse4.domain.MetricType.MUSCLE_MASS)?.let { add("Svaly ${"%.1f".format(java.util.Locale.US, it.value)} kg") }
                                    latestBody?.get(app.pulse4.domain.MetricType.FAT_FREE_MASS)?.let { add("FFM ${"%.1f".format(java.util.Locale.US, it.value)} kg") }
                                    latestBody?.get(app.pulse4.domain.MetricType.BODY_WATER)?.let { add("Voda ${"%.1f".format(java.util.Locale.US, it.value)} %") }
                                    latestBody?.get(app.pulse4.domain.MetricType.BONE_MASS)?.let { add("Kosti ${"%.2f".format(java.util.Locale.US, it.value)} kg") }
                                    latestBody?.get(app.pulse4.domain.MetricType.VISCERAL_FAT)?.let { add("Visc. tuk ${"%.1f".format(java.util.Locale.US, it.value)}") }
                                    latestBody?.get(app.pulse4.domain.MetricType.VASCULAR_AGE)?.let { add("Cévní věk ${"%.0f".format(java.util.Locale.US, it.value)} r") }
                                    latestBody?.get(app.pulse4.domain.MetricType.PULSE_WAVE_VELOCITY)?.let { add("PWV ${"%.1f".format(java.util.Locale.US, it.value)} m/s") }
                                    latestBody?.get(app.pulse4.domain.MetricType.BMR)?.let { add("BMR ${"%.0f".format(java.util.Locale.US, it.value)} kcal") }
                                    latestBody?.get(app.pulse4.domain.MetricType.METABOLIC_AGE)?.let { add("Metab. věk ${"%.0f".format(java.util.Locale.US, it.value)} r") }
                                    latestBody?.get(app.pulse4.domain.MetricType.ECG)?.let { add("ECG ${"%.0f".format(java.util.Locale.US, it.value)} ${if (it.value >= 200.0) "ms" else "bpm"}") }
                                    latestBody?.get(app.pulse4.domain.MetricType.ECG_HR)?.let { add("ECG tep ${"%.0f".format(java.util.Locale.US, it.value)} bpm") }
                                    latestBody?.get(app.pulse4.domain.MetricType.ECG_QTC)?.let { add("QTc ${"%.0f".format(java.util.Locale.US, it.value)} ms") }
                                    latestBody?.get(app.pulse4.domain.MetricType.ECG_QRS)?.let { add("QRS ${"%.0f".format(java.util.Locale.US, it.value)} ms") }
                                    latestBody?.get(app.pulse4.domain.MetricType.ECG_AFIB)?.let { add("AFib ${if (it.value >= 1.0) "detek." else "ne"}") }
                                }
                                if (summaryParts.isNotEmpty()) {
                                    Text(
                                        "Poslední měření (${summaryParts.size} metrik): ${summaryParts.joinToString(" · ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFCBD5E1)
                                    )
                                }
                            }

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = !syncing,
                                    onClick = {
                                        if (failed != null) {
                                            webLoginMode = WithingsDialogMode.Reconnect
                                            showWebLoginDialog = true
                                        } else {
                                            scope.launch {
                                                syncing = true
                                                val r = withContext(Dispatchers.IO) {
                                                    sl.withings.renewSession()
                                                    runCatching { sl.withings.runImport(System.currentTimeMillis()) }.getOrNull()
                                                }
                                                syncing = false
                                                notice = when (r) {
                                                    is app.pulse4.provider.AreaResult.Success -> "Staženo ${r.saved} měření z Withings."
                                                    is app.pulse4.provider.AreaResult.Failed -> "Chyba synchronizace: ${r.error}"
                                                    else -> "Synchronizace dokončena."
                                                }
                                                tick++
                                            }
                                        }
                                    },
                                    colors = if (failed != null) ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)) else ButtonDefaults.buttonColors(),
                                    modifier = Modifier.weight(1.3f)
                                ) {
                                    Icon(
                                        if (failed != null) Icons.AutoMirrored.Outlined.Login else Icons.Outlined.Sync,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (syncing) "Stahuji…" else if (failed != null) "Znovu přihlásit" else "Synchronizovat", maxLines = 1, softWrap = false)
                                }

                                OutlinedButton(
                                    onClick = {
                                        webLoginMode = WithingsDialogMode.SelectProfilesOnly
                                        showWebLoginDialog = true
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Změnit profil", maxLines = 1, softWrap = false) }
                            }

                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Odebrání propojení účtu:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF64748B)
                                )
                                OutlinedButton(
                                    onClick = disconnectWithings,
                                    enabled = !withingsDisconnecting,
                                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Outlined.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Odpojit Withings", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    AccountCard("Přihlášení Withings účtem", "E-mail a heslo · Trvalé šifrované spojení") {
                        WithingsNativeLoginForm(
                            sl = sl,
                            onLoggedIn = { res ->
                                val primaryName = res.profiles.firstOrNull { it.isPrimary }?.displayName
                                    ?: res.profiles.firstOrNull()?.displayName
                                    ?: "Hlavní"
                                notice = "Přihlášeno k Withings profilu $primaryName."
                                tick++
                                if (res.profiles.size > 1) {
                                    webLoginMode = WithingsDialogMode.SelectProfilesOnly
                                    showWebLoginDialog = true
                                }
                            },
                            onNotice = { notice = it }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                WithingsDiagnosticsCard(sl = sl, refreshKey = tick)

                Spacer(Modifier.height(12.dp))

                // ───────────────────────── RODINA & DĚTSKÝ PROFIL ─────────────────────────
                val childUid = wCfg?.childWithingsUserId
                // StrictMode (L5): čtení relace (soubor + Keystore) mimo hlavní vlákno.
                val childSession = io(tick) { sl.withings.loadWebSession() }.value
                val childName = shownChild?.displayName
                val childProfiles = childSession?.let {
                    app.pulse4.withings.WithingsProfiles.children(it.allProfiles, it.primaryUserId)
                }.orEmpty()
                val hasChildConfig = childUid != null || (wCfg?.childRecipientIdentity ?: "").isNotBlank()

                AccountCard(
                    "Rodina & Dětský profil",
                    if (hasChildConfig) "Aktivní přeposílání" else "Sdílení váhy dítěte"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Withings nepovoluje dětem do 16 let samostatný účet. Pokud máte v rodinném Withings profil dítěte, Pulse4 může jeho váhu automaticky přeposílat přes šifrovanou P2P síť (Reticulum) přímo do jeho vlastního telefonu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )

                        if (wConnected) {
                            Text(
                                "1. Dětský profil na Withings váze:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            if (childProfiles.isNotEmpty()) {
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    childProfiles.forEach { cp ->
                                        val isSelected = cp.id.toString() == childUid
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    val s = sl.withings.loadWebSession() ?: return@launch
                                                    // selectProfiles(null) means "keep current child", so disconnect explicitly.
                                                    if (isSelected) sl.withings.disconnectChild()
                                                    else sl.withings.selectProfiles(s.primaryUserId, cp.id)
                                                    notice = if (isSelected) "Dětský profil odpojen." else "Vybrán dětský profil ${cp.displayName}."
                                                    tick++
                                                }
                                            },
                                            label = { Text(cp.displayName, maxLines = 1, softWrap = false) }
                                        )
                                    }
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        webLoginMode = WithingsDialogMode.SelectProfilesOnly
                                        showWebLoginDialog = true
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        if (childUid != null) "Změnit profil (${childName ?: "ID $childUid"})" else "Přiřadit profil dítěte",
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                if (childUid != null) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { sl.withings.disconnectChild() }
                                            notice = "Dětský profil odpojen."
                                            tick++
                                        }
                                    }) {
                                        Text("Odpojit", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }

                            Text(
                                "2. Telefon dítěte (příjemce v P2P síti):",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (contacts.isEmpty()) {
                                Text(
                                    "Zatím nemáte spárované žádné kontakty. Spárujte telefon dítěte v záložce Kruhy → QR kód.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFF59E0B)
                                )
                            } else {
                                // R4-6: pevná vazba dítě ↔ Withings profil. Přidání se ptá na profil, změna = odebrat a přidat znovu.
                                val childBindings by androidx.compose.runtime.produceState(
                                    initialValue = emptyMap<String, app.pulse4.android.di.WithingsService.ChildWithingsBinding>(),
                                    contacts, tick,
                                ) {
                                    value = withContext(Dispatchers.IO) {
                                        contacts.mapNotNull { c ->
                                            runCatching { sl.withings.childBinding(c.identity) }.getOrNull()?.let { c.identity to it }
                                        }.toMap()
                                    }
                                }
                                var addChildFor by remember { mutableStateOf<app.pulse4.android.data.ContactRow?>(null) }
                                var addChildCandidates by remember {
                                    mutableStateOf<List<app.pulse4.withings.WithingsUserProfile>>(emptyList())
                                }
                                var addChildSelectedUid by remember { mutableStateOf<Long?>(null) }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    contacts.forEach { c ->
                                        val binding = childBindings[c.identity]
                                        val bound = binding != null &&
                                            binding.status != app.pulse4.android.di.WithingsService.ChildBindingStatus.UNBOUND
                                        val isSelected = wCfg?.childRecipientIdentity == c.identity
                                        Column {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                FilterChip(
                                                    selected = isSelected,
                                                    onClick = {
                                                        scope.launch(Dispatchers.IO) {
                                                            if (bound) {
                                                                if (!isSelected) sl.withings.selectChildRecipient(c.identity)
                                                            } else {
                                                                val candidates = runCatching { sl.withings.childProfileCandidates() }.getOrDefault(emptyList())
                                                                withContext(Dispatchers.Main) {
                                                                    addChildCandidates = candidates
                                                                    addChildSelectedUid = candidates.singleOrNull()?.id
                                                                    addChildFor = c
                                                                }
                                                            }
                                                            tick++
                                                        }
                                                    },
                                                    label = { Text(c.displayName, maxLines = 1, softWrap = false) },
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (bound) {
                                                    TextButton(onClick = {
                                                        scope.launch(Dispatchers.IO) {
                                                            sl.withings.removeChild(c.identity)
                                                            notice = "Dítě ${c.displayName} odebráno (vazba na Withings profil smazána)."
                                                            tick++
                                                        }
                                                    }) {
                                                        Text("Odebrat", color = MaterialTheme.colorScheme.error)
                                                    }
                                                } else {
                                                    Text("Přidat dítě", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                                                }
                                            }
                                            if (bound && binding != null) {
                                                Text(
                                                    "Withings profil: ${binding.profileName ?: "ID ${binding.withingsUid}"}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFF94A3B8)
                                                )
                                                if (binding.status == app.pulse4.android.di.WithingsService.ChildBindingStatus.PROFILE_MISSING) {
                                                    Text(
                                                        "Navázaný Withings profil (ID ${binding.withingsUid}) už na účtu není – váhy se tomuto dítěti nepřeposílají. Odeberte dítě a přidejte ho znovu.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color(0xFFF59E0B)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                addChildFor?.let { child ->
                                    AlertDialog(
                                        onDismissRequest = { addChildFor = null },
                                        title = { Text("Přidat dítě ${child.displayName}") },
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                if (addChildCandidates.isEmpty()) {
                                                    Text("Na účtu Withings není žádný dětský profil. Nejdřív připojte Withings a dětský profil.")
                                                } else {
                                                    Text("Který dětský profil Withings patří tomuto dítěti? Vazbu lze později změnit jen odebráním a novým přidáním dítěte.")
                                                    addChildCandidates.forEach { p ->
                                                        FilterChip(
                                                            selected = addChildSelectedUid == p.id,
                                                            onClick = { addChildSelectedUid = p.id },
                                                            label = { Text(p.displayName, maxLines = 1, softWrap = false) }
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            Button(
                                                enabled = addChildSelectedUid != null,
                                                onClick = {
                                                    val uid = addChildSelectedUid ?: return@Button
                                                    addChildFor = null
                                                    scope.launch(Dispatchers.IO) {
                                                        val ok = sl.withings.addChild(child.identity, uid.toString())
                                                        notice = if (ok) "Dítě ${child.displayName} přidáno." else "Dítě už má jinou vazbu – nejdřív ho odeberte."
                                                        tick++
                                                    }
                                                }
                                            ) { Text("Přidat") }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { addChildFor = null }) { Text("Zrušit") }
                                        }
                                    )
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            val sent = sl.withings.syncAndForwardChildWeights(System.currentTimeMillis())
                                            notice = if (sent > 0) "Odesláno $sent vážení dítěte."
                                            else if ((wCfg?.childRecipientIdentity ?: "").isBlank()) "Vyberte, komu posílat."
                                            else "Nic nového k odeslání."
                                            tick++
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Odeslat měření dítěte", maxLines = 1, softWrap = false)
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = childWeightSendKg,
                                    onValueChange = { childWeightSendKg = it },
                                    label = { Text("Nebo ruční váha dítěte (kg)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.weight(1.4f)
                                )
                                OutlinedButton(
                                    onClick = {
                                        val kg = childWeightSendKg.replace(',', '.').toDoubleOrNull()
                                        val childId = wCfg?.childRecipientIdentity.orEmpty()
                                        if (childId.isBlank()) {
                                            notice = "Vyberte, komu posílat."
                                        } else if (kg != null && kg in 5.0..150.0) {
                                            scope.launch(Dispatchers.IO) {
                                                val now = System.currentTimeMillis()
                                                val today = LocalDate.now(ZoneId.of(sl.settings.zoneId)).toString()
                                                val obs = app.pulse4.domain.MetricObservation(
                                                    id = "withings-child-$now",
                                                    provider = app.pulse4.domain.Provider.WITHINGS_API,
                                                    accountId = "withings-family",
                                                    externalId = "cw-$now",
                                                    type = app.pulse4.domain.MetricType.WEIGHT,
                                                    startUtcMs = now,
                                                    endUtcMs = now,
                                                    value = kg,
                                                    zoneId = sl.settings.zoneId,
                                                    localDate = today
                                                )
                                                val ok = sl.withings.forwardSingleWeightToChild(obs, now, excludeFromParent = false)
                                                childWeightSendKg = ""
                                                notice = if (ok) "${fmt.weight(kg)} odesláno dítěti." else "Odeslání se nezdařilo."
                                                tick++
                                            }
                                        } else notice = "Zadejte platnou váhu v kg."
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Poslat", maxLines = 1, softWrap = false) }
                            }
                        }

                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E293B)))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    "Přijímat moji váhu od rodiče",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    "Zapněte na telefonu dítěte. Váha zaslaná z rodinného účtu rodiče se automaticky zařadí do grafů.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Switch(
                                checked = wCfg?.acceptFromParent ?: false,
                                onCheckedChange = { on ->
                                    scope.launch(Dispatchers.IO) {
                                        sl.settings.acceptWeightFromParent = on
                                        if (on) sl.shareService.sendFamilyWeightResyncRequests()
                                        tick++
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Ochranný filtr – aby se do vašeho grafu nepletlo vážení dítěte
                val filterOn = wCfg?.filterEnabled ?: true
                val curMin = wCfg?.minKg ?: 45.0
                val curMax = wCfg?.maxKg ?: 130.0
                AccountCard("Filtr cizích vážení", null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).clickable { showFilter = !showFilter }) {
                            Text("Jen ${curMin.toInt()}–${curMax.toInt()} kg", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (showFilter) "Skrýt ▲" else "Upravit ▼",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF38BDF8)
                            )
                        }
                        Switch(
                            checked = filterOn,
                            onCheckedChange = { on -> scope.launch(Dispatchers.IO) { sl.settings.weightFilterEnabled = on; tick++ } }
                        )
                    }
                    if (showFilter) {
                        Text(
                            "Vážení mimo rozsah (dítě na váze, dítě v náručí) se nezapočítá do vašeho trendu.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = minKgInput.ifEmpty { curMin.toInt().toString() },
                                onValueChange = { minKgInput = it },
                                label = { Text("Od kg") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = maxKgInput.ifEmpty { curMax.toInt().toString() },
                                onValueChange = { maxKgInput = it },
                                label = { Text("Do kg") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val mn = minKgInput.replace(',', '.').toDoubleOrNull() ?: curMin
                                    val mx = maxKgInput.replace(',', '.').toDoubleOrNull() ?: curMax
                                    if (mn in 5.0..250.0 && mx > mn) {
                                        scope.launch(Dispatchers.IO) {
                                            sl.settings.weightFilterMinKg = mn
                                            sl.settings.weightFilterMaxKg = mx
                                            notice = "Filtr ${mn.toInt()}–${mx.toInt()} kg uložen."
                                            tick++
                                        }
                                    } else notice = "Neplatný rozsah."
                                },
                                modifier = Modifier.weight(0.8f)
                            ) { Text("OK", maxLines = 1, softWrap = false) }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                AccountCard("Bez připojení", null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = manualWeightKg,
                            onValueChange = { manualWeightKg = it },
                            label = { Text("Váha (kg)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1.4f)
                        )
                        Button(
                            onClick = {
                                val kg = manualWeightKg.replace(',', '.').toDoubleOrNull()
                                if (kg != null && kg in 5.0..250.0) {
                                    scope.launch(Dispatchers.IO) {
                                        val now = System.currentTimeMillis()
                                        val today = LocalDate.now(ZoneId.of(sl.settings.zoneId)).toString()
                                        val obs = app.pulse4.domain.MetricObservation(
                                            id = "manual-weight-$now",
                                            provider = app.pulse4.domain.Provider.MANUAL,
                                            accountId = "local",
                                            externalId = "mw-$now",
                                            type = app.pulse4.domain.MetricType.WEIGHT,
                                            startUtcMs = now,
                                            endUtcMs = now,
                                            value = kg,
                                            zoneId = sl.settings.zoneId,
                                            localDate = today
                                        )
                                        sl.db.observations().upsert(listOf(obs.toRow()))
                                        manualWeightKg = ""
                                        notice = "Váha ${fmt.weight(kg)} uložena."
                                    }
                                } else notice = "Zadejte platnou váhu v kg (např. 75.4)."
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Zapsat", maxLines = 1, softWrap = false) }
                    }
                    OutlinedButton(
                        onClick = { csvLauncher.launch(arrayOf("text/*", "application/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Import CSV z aplikace Withings", maxLines = 1, softWrap = false) }
                }

                Spacer(Modifier.height(12.dp))

                var showOAuthCard by remember { mutableStateOf(false) }
                AccountCard("Vývojářské API (OAuth 2.0)", if (wCfg?.hasCreds == true) "Nastaveno" else "Alternativa", onHelp = { showOAuthCard = !showOAuthCard }) {
                    Text(
                        "Alternativní připojení přes vývojářský portál Withings (pro ty, kdo mají vlastní Client ID a Secret).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                    if (showOAuthCard || wCfg?.hasCreds == true) {
                        Spacer(Modifier.height(8.dp))
                        HelpBox(
                            listOf(
                                "Přihlaste se na developer.withings.com (stejný účet jako v aplikaci Withings).",
                                "Klikněte na Create an application → Public API integration a jako Callback URL vložte adresu níže.",
                                "Zkopírujte sem Client ID a Client Secret a klikněte na Připojit."
                            )
                        ) {
                            OutlinedButton(
                                onClick = { openUrl("https://developer.withings.com/dashboard/") },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Otevřít developer.withings.com", maxLines = 1, softWrap = false) }
                            Text(
                                app.pulse4.android.BuildConfig.WITHINGS_REDIRECT,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF38BDF8)
                            )
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(app.pulse4.android.BuildConfig.WITHINGS_REDIRECT))
                                    notice = "Callback URL zkopírována."
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Kopírovat Callback URL", maxLines = 1, softWrap = false) }
                        }
                        OutlinedTextField(
                            value = withingsClientId.ifEmpty { wCfg?.clientId.orEmpty() },
                            onValueChange = { withingsClientId = it },
                            label = { Text("Client ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = withingsSecret,
                            onValueChange = { withingsSecret = it },
                            label = { Text(if (wCfg?.hasCreds == true) "Client Secret (uložen)" else "Client Secret") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val cid = withingsClientId.ifBlank { wCfg?.clientId.orEmpty() }.trim()
                                    if (cid.isNotBlank() && withingsSecret.isNotBlank()) {
                                        sl.withings.saveCredentials(cid, withingsSecret.trim())
                                        withingsSecret = ""
                                    } else if (cid.isNotBlank()) {
                                        sl.settings.withingsClientId = cid
                                    }
                                    sl.settings.withingsDirectEnabled = true
                                    tick++
                                    try {
                                        val authUrl = sl.withings.beginLogin(System.currentTimeMillis())
                                        withContext(Dispatchers.Main) { openUrl(authUrl) }
                                    } catch (e: Exception) {
                                        notice = "Vyplňte Client ID i Client Secret."
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (wConnected && !isWeb) "Přepojit OAuth" else "Připojit přes OAuth", maxLines = 1, softWrap = false) }
                    }
                }
            }
        }

        // ───────────────────────── DOLE: ROZROLOVÁVACÍ STAV SYNC GARMIN & SYNC WITHINGS ─────────────────────────
        Spacer(Modifier.height(16.dp))

        val garminAreas = remember { SyncArea.entries.filter { it != SyncArea.WEIGHT } }
        val garminAreaLabels = remember {
            mapOf(
                SyncArea.SLEEP to "Spánek (SLEEP)",
                SyncArea.HRV to "Variabilita tepu (HRV)",
                SyncArea.BODY_BATTERY to "Body Battery (BODY_BATTERY)",
                SyncArea.HEART_RATE to "Tepová frekvence (HEART_RATE)",
                SyncArea.STEPS_STRESS to "Kroky a stres (STEPS_STRESS)",
                SyncArea.ACTIVITIES to "Sportovní aktivity (ACTIVITIES)"
            )
        }
        val garminStatesList = garminAreas.map { area -> area to states.firstOrNull { it.area == area.name } }
        val garminLastSyncMs = garminStatesList.mapNotNull { it.second?.lastSuccessMs }.maxOrNull()
        val garminHasError = garminStatesList.any { !it.second?.lastError.isNullOrBlank() }
        val garminAllSynced = garminStatesList.all { it.second?.lastSuccessMs != null && it.second?.lastError.isNullOrBlank() }
        val garminBarColor = when {
            garminHasError -> Color(0xFFEF4444)
            garminAllSynced -> Color(0xFF10B981)
            garminLastSyncMs != null -> Color(0xFFF59E0B)
            else -> Color(0xFF64748B)
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, garminBarColor.copy(alpha = 0.7f)),
            colors = CardDefaults.cardColors(containerColor = garminBarColor.copy(alpha = 0.10f)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expandGarminSync = !expandGarminSync }
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Text(
                            "●",
                            color = garminBarColor,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Column {
                            Text(
                                "Sync Garmin",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                when {
                                    garminHasError -> "Chyba u některé oblasti – klikněte pro detail"
                                    garminAllSynced && garminLastSyncMs != null -> "Všech ${garminAreas.size} oblastí OK · ${fmt.time(garminLastSyncMs)}"
                                    garminLastSyncMs != null -> "Částečně synchronizováno · ${fmt.time(garminLastSyncMs)}"
                                    else -> "Zatím nesynchronizováno"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = garminBarColor
                            )
                        }
                    }
                    Text(
                        if (expandGarminSync) "Skrýt ▲" else "Detail ▼",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = garminBarColor
                    )
                }

                if (expandGarminSync) {
                    Spacer(Modifier.height(10.dp))
                    garminStatesList.forEach { (area, st) ->
                        val hasErr = !st?.lastError.isNullOrBlank()
                        val isOk = st?.lastSuccessMs != null && !hasErr
                        val rowColor = when {
                            hasErr -> Color(0xFFF87171)
                            isOk -> Color(0xFF10B981)
                            else -> Color(0xFF94A3B8)
                        }
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    garminAreaLabels[area] ?: area.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                                Text(
                                    when {
                                        st?.lastSuccessMs != null -> fmt.time(st.lastSuccessMs)
                                        hasErr -> "Selhalo"
                                        else -> "Nesynchronizováno"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = rowColor
                                )
                            }
                            if (hasErr) {
                                Text(
                                    "Chyba: ${Labels.syncError(st?.lastError.orEmpty())}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFF87171)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        enabled = !syncing,
                        onClick = {
                            scope.launch {
                                syncing = true
                                withContext(Dispatchers.IO) {
                                    runCatching { sl.importService.runAll(System.currentTimeMillis()) }
                                }
                                syncing = false
                                tick++
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (syncing) "Synchronizuji Garmin…" else "Synchronizovat Garmin nyní")
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        val wConn = wState as? app.pulse4.android.di.WithingsUiState.Connected
        val wFailed = wState as? app.pulse4.android.di.WithingsUiState.Failed
        val wAreaState = states.firstOrNull { it.area == "withings.WEIGHT" }
        val wLastSyncMs = wAreaState?.lastSuccessMs ?: wConn?.lastSyncMs ?: webSession?.lastSyncMs
        val wLastError = wAreaState?.lastError?.takeIf {
            it.isNotBlank() && (wAreaState.lastSuccessMs == null || (wAreaState.lastAttemptMs ?: 0L) > (wAreaState.lastSuccessMs ?: 0L))
        }
        val wErrorText = wFailed?.message ?: wLastError?.let { Labels.syncError(it) }
        val withingsBarColor = when {
            wFailed != null -> Color(0xFFEF4444)
            wLastError != null -> Color(0xFFEF4444)
            wLastSyncMs != null && !latestBody.isNullOrEmpty() -> Color(0xFF10B981)
            wConnected || wLastSyncMs != null -> Color(0xFFF59E0B)
            else -> Color(0xFF64748B)
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, withingsBarColor.copy(alpha = 0.7f)),
            colors = CardDefaults.cardColors(containerColor = withingsBarColor.copy(alpha = 0.10f)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expandWithingsSync = !expandWithingsSync }
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Text(
                            "●",
                            color = withingsBarColor,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Column {
                            Text(
                                "Sync Withings",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                when {
                                    wFailed != null -> "Relace vypršela – klepněte pro obnovení"
                                    wLastError != null -> "Chyba synchronizace: $wLastError"
                                    wLastSyncMs != null -> "Synchronizováno (${latestBody?.size ?: 0} metrik) · ${fmt.time(wLastSyncMs)}"
                                    wConnected -> "Připojeno – čeká na synchronizaci"
                                    else -> "Zatím nesynchronizováno"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = withingsBarColor
                            )
                        }
                    }
                    Text(
                        if (expandWithingsSync) "Skrýt ▲" else "Detail ▼",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = withingsBarColor
                    )
                }

                if (expandWithingsSync) {
                    Spacer(Modifier.height(10.dp))
                    if (wErrorText != null) {
                        Text(
                            "Poslední chyba: $wErrorText",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFF87171),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    val withingsMetricLabels = listOf(
                        app.pulse4.domain.MetricType.WEIGHT to "Tělesná hmotnost (WEIGHT)",
                        app.pulse4.domain.MetricType.BODY_FAT to "Tělesný tuk (BODY_FAT)",
                        app.pulse4.domain.MetricType.MUSCLE_MASS to "Svalová hmota (MUSCLE_MASS)",
                        app.pulse4.domain.MetricType.FAT_FREE_MASS to "Beztuková hmota (FAT_FREE_MASS)",
                        app.pulse4.domain.MetricType.BODY_WATER to "Tělesná voda (BODY_WATER)",
                        app.pulse4.domain.MetricType.BONE_MASS to "Kostní hmota (BONE_MASS)",
                        app.pulse4.domain.MetricType.VISCERAL_FAT to "Viscerální tuk (VISCERAL_FAT)",
                        app.pulse4.domain.MetricType.VASCULAR_AGE to "Cévní věk (VASCULAR_AGE)",
                        app.pulse4.domain.MetricType.PULSE_WAVE_VELOCITY to "Rychlost pulzní vlny (PWV)",
                        app.pulse4.domain.MetricType.BMR to "Bazální metabolismus (BMR)",
                        app.pulse4.domain.MetricType.METABOLIC_AGE to "Metabolický věk (METABOLIC_AGE)",
                        app.pulse4.domain.MetricType.ECG to "Elektrokardiogram (ECG)",
                        app.pulse4.domain.MetricType.ECG_HR to "ECG tepová frekvence (ECG_HR)",
                        app.pulse4.domain.MetricType.ECG_QTC to "ECG interval QTc (ECG_QTC)",
                        app.pulse4.domain.MetricType.ECG_QRS to "ECG komplex QRS (ECG_QRS)",
                        app.pulse4.domain.MetricType.ECG_AFIB to "ECG fibrilace síní (ECG_AFIB)"
                    )
                    withingsMetricLabels.forEach { (mType, label) ->
                        val obs = latestBody?.get(mType)
                        val valText = obs?.let {
                            val v = it.value
                            val formatted = when (mType) {
                                app.pulse4.domain.MetricType.WEIGHT,
                                app.pulse4.domain.MetricType.MUSCLE_MASS,
                                app.pulse4.domain.MetricType.FAT_FREE_MASS -> "${"%.1f".format(java.util.Locale.US, v)} kg"
                                app.pulse4.domain.MetricType.BONE_MASS -> "${"%.2f".format(java.util.Locale.US, v)} kg"
                                app.pulse4.domain.MetricType.BODY_FAT,
                                app.pulse4.domain.MetricType.BODY_WATER -> "${"%.1f".format(java.util.Locale.US, v)} %"
                                app.pulse4.domain.MetricType.VISCERAL_FAT -> "${"%.1f".format(java.util.Locale.US, v)} idx"
                                app.pulse4.domain.MetricType.VASCULAR_AGE,
                                app.pulse4.domain.MetricType.METABOLIC_AGE -> "${"%.0f".format(java.util.Locale.US, v)} let"
                                app.pulse4.domain.MetricType.PULSE_WAVE_VELOCITY -> "${"%.2f".format(java.util.Locale.US, v)} m/s"
                                app.pulse4.domain.MetricType.BMR -> "${"%.0f".format(java.util.Locale.US, v)} kcal"
                                app.pulse4.domain.MetricType.ECG -> "${"%.0f".format(java.util.Locale.US, v)} ${if (v >= 200.0) "ms" else "bpm"}"
                                app.pulse4.domain.MetricType.ECG_HR -> "${"%.0f".format(java.util.Locale.US, v)} bpm"
                                app.pulse4.domain.MetricType.ECG_QTC,
                                app.pulse4.domain.MetricType.ECG_QRS -> "${"%.0f".format(java.util.Locale.US, v)} ms"
                                app.pulse4.domain.MetricType.ECG_AFIB -> if (v >= 1.0) "detekováno" else "normální"
                                else -> "%.1f".format(java.util.Locale.US, v)
                            }
                            "$formatted (${it.localDate ?: fmt.time(it.startUtcMs)})"
                        } ?: "Zatím bez záznamu"
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                valText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (obs != null) Color(0xFF10B981) else Color(0xFF94A3B8)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        enabled = !syncing,
                        onClick = {
                            scope.launch {
                                syncing = true
                                withContext(Dispatchers.IO) {
                                    runCatching { sl.withings.runImport(System.currentTimeMillis()) }
                                }
                                syncing = false
                                tick++
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (syncing) "Synchronizuji Withings…" else "Synchronizovat Withings nyní")
                    }
                }
            }
        }
    }
}

/** Jednotná karta účtu: nadpis, volitelný stav vpravo a volitelné tlačítko „?“ pro nápovědu. */
/**
 * Interval of the automatic background import of one provider. Manual "Synchronizovat" buttons are not affected.
 * Stored per provider ([app.pulse4.android.data.Settings.setSyncIntervalMinutes]); defaults are Garmin 15 min and
 * Withings 1 h. Android cannot run background work more often than about every 15 minutes.
 */
@Composable
private fun SyncIntervalCard(provider: SyncProvider, sl: ServiceLocator) {
    val scope = rememberCoroutineScope()
    var minutes by remember(provider) { mutableStateOf<Int?>(null) }
    LaunchedEffect(provider) {
        minutes = withContext(Dispatchers.IO) { runCatching { sl.settings.syncIntervalMinutes(provider) }.getOrNull() }
    }
    val name = if (provider == SyncProvider.GARMIN) "Garmin" else "Withings"
    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Automatická synchronizace: $name",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            val current = minutes
            Text(
                when {
                    current == null -> "Načítám…"
                    current == SyncSchedule.OFF -> "Automatické stahování dat z $name je vypnuté. Tlačítko „Synchronizovat“ funguje dál."
                    else -> "Data z $name se na pozadí stahují zhruba každých ${SyncSchedule.label(current)} " +
                        "(výchozí: ${SyncSchedule.label(provider.defaultMinutes)}). Android nespouští práci na pozadí přesně na čas."
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SyncSchedule.OPTIONS.forEach { option ->
                    FilterChip(
                        selected = current == option,
                        onClick = {
                            minutes = option
                            scope.launch(Dispatchers.IO) {
                                runCatching { sl.settings.setSyncIntervalMinutes(provider, option) }
                            }
                        },
                        label = { Text(SyncSchedule.label(option), maxLines = 1, softWrap = false) },
                    )
                }
            }
            if (provider == SyncProvider.WITHINGS) {
                Text(
                    "Přihlášení k Withings se udržuje samostatně každých 30 minut a při otevření aplikace, bez ohledu na tuto volbu.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B)
                )
            }
        }
    }
}

@Composable
private fun AccountCard(
    title: String,
    status: String?,
    onHelp: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val activeAccent = if (status != null) Color(0xFF10B981) else Color(0xFF38BDF8)
    Card(
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, activeAccent.copy(alpha = 0.38f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(activeAccent.copy(alpha = 0.08f), Color(0xFF0A101B))
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                status?.let {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            it.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981),
                            fontSize = 10.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                onHelp?.let {
                    TextButton(onClick = it) { Text("Kde získat?", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) }
                }
            }
            content()
        }
    }
}

/** Krátký očíslovaný návod se slotem pro akční tlačítka. */
@Composable
private fun HelpBox(steps: List<String>, actions: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2A3D)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEachIndexed { i, s ->
                Row {
                    Text("${i + 1}.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), modifier = Modifier.padding(end = 6.dp))
                    Text(s, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCBD5E1))
                }
            }
            actions()
        }
    }
}

@Composable
private fun ReticulumSettingsSection(sl: ServiceLocator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val outbox by sl.db.outbox().recent().collectAsState(emptyList())
    var tick by remember { mutableStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var showAdvancedP2p by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }
    var showPublicNodes by remember { mutableStateOf(false) }
    var showQrShare by remember { mutableStateOf(false) }
    var showQrImport by remember { mutableStateOf(false) }
    val nodeTestResults = remember { mutableStateMapOf<String, app.pulse4.android.reticulum.NodeTestResult>() }
    var testingNodeKey by remember { mutableStateOf<String?>(null) }

    val netData by io(tick) {
        listOf(
            sl.settings.networkMode.name,
            sl.settings.gatewayHost,
            sl.settings.gatewayPort.toString(),
            sl.settings.propagationNode,
            sl.settings.savedGatewayNodes.joinToString(","),
            sl.settings.rnsOnboardingCompleted.toString(),
            sl.settings.rnsAdvancedMode.toString(),
            sl.settings.backgroundServiceEnabled.toString()
        )
    }
    val myInfo by io(tick) {
        sl.bridge.ensureStarted()
    }

    var mode by remember(netData) {
        mutableStateOf(netData?.getOrNull(0)?.let { runCatching { NetworkMode.valueOf(it) }.getOrNull() } ?: NetworkMode.AUTO)
    }
    var host by remember(netData) { mutableStateOf(netData?.getOrNull(1) ?: "") }
    var port by remember(netData) { mutableStateOf(netData?.getOrNull(2) ?: "4242") }
    var propagationNode by remember(netData) { mutableStateOf(netData?.getOrNull(3) ?: "") }
    val savedNodes = remember(netData) {
        netData?.getOrNull(4)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    }
    val onboardingCompleted = netData?.getOrNull(5)?.toBooleanStrictOrNull() ?: true
    var advancedMode by remember(netData) {
        mutableStateOf(netData?.getOrNull(6)?.toBooleanStrictOrNull() ?: false)
    }
    var newNodeHost by remember { mutableStateOf("") }
    var newNodePort by remember { mutableStateOf("4242") }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // Přepínač úrovně rozhraní: Běžný režim vs Pokročilý režim
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !advancedMode,
                onClick = {
                    advancedMode = false
                    scope.launch(Dispatchers.IO) {
                        sl.settings.rnsAdvancedMode = false
                        tick++
                    }
                },
                label = { Text("Běžný režim") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = advancedMode,
                onClick = {
                    advancedMode = true
                    scope.launch(Dispatchers.IO) {
                        sl.settings.rnsAdvancedMode = true
                        tick++
                    }
                },
                label = { Text("Pokročilý režim") },
                modifier = Modifier.weight(1f)
            )
        }

        if (!onboardingCompleted || showOnboarding) {
            RnsOnboardingCard(
                sl = sl,
                onCompleted = {
                    showOnboarding = false
                    tick++
                },
                onFeedback = { feedback = it }
            )
            Spacer(Modifier.height(12.dp))
        }

        // 1. Srozumitelná volba spojení
        Card(
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.40f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = R.drawable.img_motif_hrv_autonomic),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().alpha(0.24f)
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xD9080E18), Color(0xF20A101B))
                            )
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        if (advancedMode) "REŽIM KRYPTOGRAFICKÉHO SPOJENÍ RETICULUM" else "ZPŮSOB PŘIPOJENÍ KE KRUHŮM",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFA855F7),
                        letterSpacing = 0.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Vyberte, jak si váš uzel vyměňuje šifrovaná data s vašimi přáteli:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                    Spacer(Modifier.height(10.dp))

                    val modesInfo = listOf(
                        Triple(
                            NetworkMode.AUTO,
                            "Automaticky (Doporučeno)",
                            "Spojí vás s přáteli doma na stejné Wi-Fi i přes internet, když jste každý jinde."
                        ),
                        Triple(
                            NetworkMode.LOCAL_ONLY,
                            "Pouze doma na stejné Wi-Fi",
                            "Data nikdy neopustí vaši domácí Wi-Fi síť. Funguje zcela bez internetu."
                        ),
                        Triple(
                            NetworkMode.MANUAL,
                            "Vlastní vybraný uzel",
                            "Připojení pouze k uzlu, který si ručně vyberete nebo přidáte."
                        )
                    )

                    modesInfo.forEach { (m, title, desc) ->
                        val selected = mode == m
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, if (selected) Color(0xFF38BDF8) else Color(0xFF1E293B)),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) Color(0xFF0F2942) else Color(0xFF0E1524)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    mode = m
                                    scope.launch(Dispatchers.IO) {
                                        sl.settings.networkMode = m
                                        runCatching { sl.bridge.applyPropagationNode() }
                                        feedback = "Nastaven režim spojení: $title"
                                        tick++
                                    }
                                }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                    Text(desc, style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1))
                                }
                                Text(
                                    if (selected) "AKTIVNÍ" else "Vybrat",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) Color(0xFF38BDF8) else Color(0xFF64748B),
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            Scheduler.shareNow(ctx, manual = true)
                            feedback = "Spuštěno okamžité odeslání a stažení sdílených dat."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Synchronizovat s kruhem hned teď")
                    }
                }
            }
        }

        feedback?.let {
            Card(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(it, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        val bgEnabled = netData?.getOrNull(7)?.toBooleanStrictOrNull() ?: true
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        val ignoringBattery = pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "Trvalý běh na pozadí",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "Naběhne po startu telefonu (jako Threema / Meshtastic), udržuje Reticulum a průběžně synchronizuje data i zprávy.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = bgEnabled,
                        onCheckedChange = { checked ->
                            scope.launch(Dispatchers.IO) {
                                sl.settings.backgroundServiceEnabled = checked
                                app.pulse4.android.work.PulseSyncService.syncState(ctx)
                                feedback = if (checked) {
                                    "Trvalý běh na pozadí zapnut."
                                } else {
                                    "Trvalý běh na pozadí vypnut (synchronizace jen přes WorkManager)."
                                }
                                tick++
                            }
                        }
                    )
                }
                if (bgEnabled && !ignoringBattery) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:${ctx.packageName}")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(intent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Vypnout omezení baterie pro spolehlivý běh")
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (!advancedMode) {
            // ─── BĚŽNÝ REŽIM ───
            val activeNodeText = if (mode == NetworkMode.LOCAL_ONLY || host.isBlank()) {
                "Pouze místní Wi-Fi"
            } else {
                "$host:$port"
            }
            val statusBadge = when {
                mode == NetworkMode.LOCAL_ONLY || host.isBlank() -> "Lokální Wi-Fi"
                sl.bridge.isGatewayOnline -> "Připojeno"
                else -> "Nepřipojeno"
            }
            val statusColor = when (statusBadge) {
                "Připojeno" -> Color(0xFF10B981)
                "Lokální Wi-Fi" -> Color(0xFF38BDF8)
                else -> Color(0xFFF59E0B)
            }

            Card(
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "PŘIPOJOVACÍ UZEL",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF38BDF8),
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            statusBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }

                    Text(
                        activeNodeText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Text(
                        "Uzel pro doručování zpráv: ${if (propagationNode.isBlank()) "Nenastaveno" else "Aktivní"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(onClick = { showOnboarding = !showOnboarding }) {
                            Text("Nastavit připojení")
                        }
                        OutlinedButton(onClick = { showPublicNodes = true }) {
                            Text("Veřejné uzly")
                        }
                        OutlinedButton(
                            enabled = host.isNotBlank(),
                            onClick = { showQrShare = true }
                        ) {
                            Text("Sdílet připojení")
                        }
                        OutlinedButton(onClick = { showQrImport = true }) {
                            Text("Načíst QR kód")
                        }
                        if (host.isNotBlank()) {
                            val activePortInt = port.toIntOrNull() ?: 4242
                            val key = "$host:$activePortInt"
                            OutlinedButton(
                                enabled = testingNodeKey != key,
                                onClick = {
                                    testingNodeKey = key
                                    scope.launch(Dispatchers.IO) {
                                        val res = sl.bridge.testNodeConnection(host, activePortInt)
                                        nodeTestResults[key] = res
                                        testingNodeKey = null
                                    }
                                }
                            ) {
                                Text(if (testingNodeKey == key) "Testuji…" else "Test uzlu")
                            }
                        }
                    }

                    if (host.isNotBlank()) {
                        val activePortInt = port.toIntOrNull() ?: 4242
                        val res = nodeTestResults["$host:$activePortInt"]
                        if (res != null) {
                            Text(
                                if (res.reachable) "✓ ${res.detail}" else "✕ Nedostupný: ${res.detail}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (res.reachable) Color(0xFF10B981) else Color(0xFFF87171),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Veřejné RNS zdroje:",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            "rmap.world ↗",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { runCatching { uriHandler.openUri("https://rmap.world") } }
                        )
                        Text(
                            "directory.rns.recipes ↗",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { runCatching { uriHandler.openUri("https://directory.rns.recipes") } }
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            val localQueueCount = outbox.count {
                val st = runCatching { DeliveryState.valueOf(it.state) }.getOrNull()
                st == DeliveryState.QUEUED || st == DeliveryState.HANDED_TO_TRANSPORT
            }
            val storedOnNodeCount = outbox.count {
                runCatching { DeliveryState.valueOf(it.state) }.getOrNull() == DeliveryState.STORED_IN_PROPAGATION
            }
            val deliveredCount = outbox.count {
                runCatching { DeliveryState.valueOf(it.state) }.getOrNull() == DeliveryState.ACKED
            }

            Card(
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.35f)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "ČEKAJÍCÍ ZPRÁVY",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF2DD4BF),
                        letterSpacing = 0.8.sp
                    )
                    Text("V telefonu (čeká na spojení): $localQueueCount", style = MaterialTheme.typography.bodySmall, color = Color.White)
                    Text("Na nodu (čeká na vyzvednutí): $storedOnNodeCount", style = MaterialTheme.typography.bodySmall, color = Color(0xFF38BDF8))
                    Text("Vyzvednuto a potvrzeno: $deliveredCount", style = MaterialTheme.typography.bodySmall, color = Color(0xFF10B981))

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "MOJE SÍŤOVÁ IDENTITA",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF94A3B8),
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        myInfo?.let { app.pulse4.sync.Pairing.fingerprint(it.publicKeyB64) } ?: "Načítání…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = Color(0xFF38BDF8)
                    )
                }
            }
        } else {
            // ─── POKROČILÝ REŽIM ───
            // 2. Správa P2P uzlů (seznam uzlů s možností přepnout, přidat nový nebo ODEBRAT)
            Card(
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "ULOŽENÉ PROPOJOVACÍ UZLY (${savedNodes.size})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF38BDF8),
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                if (host.isBlank()) "Aktivní uzel: nenastaven (pouze místní Wi-Fi)" else "Aktivní uzel: $host:$port",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetBrainsMonoFamily,
                                color = Color(0xFF2DD4BF),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        TextButton(onClick = { showOnboarding = !showOnboarding }) {
                            Text("Průvodce", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(onClick = { showPublicNodes = true }) {
                            Text("Veřejné uzly")
                        }
                        OutlinedButton(
                            enabled = host.isNotBlank(),
                            onClick = { showQrShare = true }
                        ) {
                            Text("Sdílet připojení")
                        }
                        OutlinedButton(onClick = { showQrImport = true }) {
                            Text("Načíst QR kód")
                        }
                        if (host.isNotBlank()) {
                            val activePortInt = port.toIntOrNull() ?: 4242
                            val key = "$host:$activePortInt"
                            OutlinedButton(
                                enabled = testingNodeKey != key,
                                onClick = {
                                    testingNodeKey = key
                                    scope.launch(Dispatchers.IO) {
                                        val res = sl.bridge.testNodeConnection(host, activePortInt)
                                        nodeTestResults[key] = res
                                        testingNodeKey = null
                                    }
                                }
                            ) {
                                Text(if (testingNodeKey == key) "Testuji…" else "Test uzlu")
                            }
                        }
                    }

                    if (host.isNotBlank()) {
                        val activePortInt = port.toIntOrNull() ?: 4242
                        val res = nodeTestResults["$host:$activePortInt"]
                        if (res != null) {
                            Text(
                                if (res.reachable) "✓ ${res.detail}" else "✕ Nedostupný: ${res.detail}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (res.reachable) Color(0xFF10B981) else Color(0xFFF87171),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    if (savedNodes.isEmpty()) {
                        Text(
                            "Nemáte uložené žádné uzly. Aplikace komunikuje pouze napřímo přes lokální Wi-Fi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    } else {
                        savedNodes.forEach { entry ->
                            val parts = entry.split(':')
                            val nHost = parts.getOrNull(0) ?: entry
                            val nPort = parts.getOrNull(1)?.toIntOrNull() ?: 4242
                            val isActive = host == nHost && port == nPort.toString()

                            Card(
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, if (isActive) Color(0xFF10B981).copy(alpha = 0.65f) else Color(0xFF1E293B)),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1524)),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "$nHost:$nPort",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isActive) Color(0xFF10B981) else Color.White
                                        )
                                        Text(
                                            if (isActive) "Právě používaný uzel" else "Uložený uzel",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isActive) Color(0xFF10B981) else Color(0xFF94A3B8)
                                        )
                                        val nodeRes = nodeTestResults["$nHost:$nPort"]
                                        if (nodeRes != null) {
                                            Text(
                                                if (nodeRes.reachable) "✓ ${nodeRes.detail}" else "✕ ${nodeRes.detail}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (nodeRes.reachable) Color(0xFF10B981) else Color(0xFFF87171),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(
                                            enabled = testingNodeKey != "$nHost:$nPort",
                                            onClick = {
                                                val k = "$nHost:$nPort"
                                                testingNodeKey = k
                                                scope.launch(Dispatchers.IO) {
                                                    nodeTestResults[k] = sl.bridge.testNodeConnection(nHost, nPort)
                                                    testingNodeKey = null
                                                }
                                            }
                                        ) {
                                            Text(if (testingNodeKey == "$nHost:$nPort") "…" else "Test", maxLines = 1, softWrap = false)
                                        }
                                        if (!isActive) {
                                            TextButton(
                                                onClick = {
                                                    host = nHost
                                                    port = nPort.toString()
                                                    scope.launch(Dispatchers.IO) {
                                                        sl.settings.gatewayHost = nHost
                                                        sl.settings.gatewayPort = nPort
                                                        sl.settings.rnsOnboardingCompleted = true
                                                        runCatching { sl.bridge.applyPropagationNode() }
                                                        feedback = "Přepnuto na uzel $nHost:$nPort"
                                                        tick++
                                                    }
                                                }
                                            ) {
                                                Text("Použít", maxLines = 1, softWrap = false)
                                            }
                                        }
                                        TextButton(
                                            onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    val remaining = savedNodes.filterNot { it == entry }
                                                    sl.settings.savedGatewayNodes = remaining
                                                    if (isActive) {
                                                        val next = remaining.firstOrNull()
                                                        if (next != null) {
                                                            val np = next.split(':')
                                                            sl.settings.gatewayHost = np.getOrNull(0) ?: ""
                                                            sl.settings.gatewayPort = np.getOrNull(1)?.toIntOrNull() ?: 4242
                                                        } else {
                                                            sl.settings.gatewayHost = ""
                                                            sl.settings.networkMode = NetworkMode.LOCAL_ONLY
                                                        }
                                                        runCatching { sl.bridge.applyPropagationNode() }
                                                    }
                                                    feedback = "Uzel $entry byl odebrán."
                                                    tick++
                                                }
                                            }
                                        ) {
                                            Text("Odebrat", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("Přidat nový uzel do seznamu:", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newNodeHost,
                            onValueChange = { newNodeHost = it },
                            label = { Text("Adresa / IP uzlu") },
                            singleLine = true,
                            modifier = Modifier.weight(1.8f)
                        )
                        OutlinedTextField(
                            value = newNodePort,
                            onValueChange = { newNodePort = it.filter(Char::isDigit) },
                            label = { Text("Port") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(0.9f)
                        )
                        Button(
                            enabled = newNodeHost.isNotBlank(),
                            onClick = {
                                val h = newNodeHost.trim()
                                val p = newNodePort.toIntOrNull() ?: 4242
                                val combined = "$h:$p"
                                scope.launch(Dispatchers.IO) {
                                    sl.settings.savedGatewayNodes = (savedNodes + combined).distinct()
                                    sl.settings.gatewayHost = h
                                    sl.settings.gatewayPort = p
                                    sl.settings.rnsOnboardingCompleted = true
                                    if (sl.settings.networkMode == NetworkMode.LOCAL_ONLY) {
                                        sl.settings.networkMode = NetworkMode.AUTO
                                    }
                                    runCatching { sl.bridge.applyPropagationNode() }
                                    newNodeHost = ""
                                    feedback = "Uzel $combined přidán a aktivován."
                                    tick++
                                }
                            }
                        ) {
                            Text("Přidat", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 3. Pokročilé detaily (Moje P2P adresa, Offline schránka s možností odebrat, Fronta zpráv)
            Card(
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.35f)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val advPendingRows = outbox.filter {
                            val st = runCatching { DeliveryState.valueOf(it.state) }.getOrNull()
                            st == DeliveryState.QUEUED || st == DeliveryState.HANDED_TO_TRANSPORT || st == DeliveryState.STORED_IN_PROPAGATION
                        }
                        val advPendingCount = advPendingRows.size
                        val advDeliveredCount = outbox.count {
                            runCatching { DeliveryState.valueOf(it.state) }.getOrNull() == DeliveryState.ACKED
                        }
                        Text(
                            "KRYPTOGRAFICKÁ ADRESA & ODCHOZÍ FRONTA ($advPendingCount čekajících)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF2DD4BF),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showAdvancedP2p = !showAdvancedP2p }) {
                            Text(if (showAdvancedP2p) "Skrýt ▲" else "Zobrazit ▼", maxLines = 1, softWrap = false)
                        }
                    }

                    if (showAdvancedP2p) {
                        Spacer(Modifier.height(8.dp))
                        Text("Moje P2P doručovací adresa (deliveryHash):", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                myInfo?.deliveryHash ?: "Načítání…",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    myInfo?.deliveryHash?.let {
                                        clipboard.setText(AnnotatedString(it))
                                        feedback = "Adresa zkopírována do schránky"
                                    }
                                }
                            ) {
                                Text("Kopírovat", maxLines = 1, softWrap = false)
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Diagnostika: TCP Gateway ${if (sl.bridge.isGatewayOnline) "ONLINE" else "OFFLINE"} · AutoInterface aktivní",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = Color(0xFF94A3B8)
                        )

                        Spacer(Modifier.height(8.dp))
                        Text("Schránka pro offline zprávy (Propagation Node):", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = propagationNode,
                                onValueChange = { propagationNode = it.trim() },
                                label = { Text("Hash schránky (volitelné)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            if (propagationNode.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        propagationNode = ""
                                        scope.launch(Dispatchers.IO) {
                                            sl.settings.propagationNode = ""
                                            runCatching { sl.bridge.applyPropagationNode() }
                                            feedback = "Offline schránka odebrána."
                                            tick++
                                        }
                                    }
                                ) {
                                    Text("Odebrat", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                                }
                            }
                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        sl.settings.propagationNode = propagationNode
                                        runCatching { sl.bridge.applyPropagationNode() }
                                        feedback = "Nastavení offline schránky uloženo."
                                        tick++
                                    }
                                }
                            ) {
                                Text("Uložit", maxLines = 1, softWrap = false)
                            }
                        }

                        val localPendingRows = outbox.filter {
                            val st = runCatching { DeliveryState.valueOf(it.state) }.getOrNull()
                            st == DeliveryState.QUEUED || st == DeliveryState.HANDED_TO_TRANSPORT
                        }
                        val storedOnNodeRows = outbox.filter {
                            runCatching { DeliveryState.valueOf(it.state) }.getOrNull() == DeliveryState.STORED_IN_PROPAGATION
                        }
                        val ackedRows = outbox.filter {
                            runCatching { DeliveryState.valueOf(it.state) }.getOrNull() == DeliveryState.ACKED
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Fronta: ${localPendingRows.size} v telefonu · ${storedOnNodeRows.size} na nodu · ${ackedRows.size} vyzvednuto",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.weight(1f)
                            )
                            if (ackedRows.isNotEmpty()) {
                                TextButton(onClick = { scope.launch(Dispatchers.IO) { sl.db.outbox().purge(System.currentTimeMillis()) } }) {
                                    Text("Vyčistit vyzvednuté", fontSize = 11.sp)
                                }
                            }
                        }
                        if (localPendingRows.isEmpty() && storedOnNodeRows.isEmpty()) {
                            Text("Žádné zprávy nečekají na odeslání ani na vyzvednutí.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                        }
                        if (storedOnNodeRows.isNotEmpty()) {
                            Text("Na nodu (čeká na vyzvednutí):", style = MaterialTheme.typography.labelSmall, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                            storedOnNodeRows.take(10).forEach { o ->
                                val line = Status.outboxLine(o.kind, "${o.objectType}/${o.objectId}", o.recipient, DeliveryState.valueOf(o.state), o.attempts, o.lastError)
                                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    Text(line.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    Text(line.state + (line.detail?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = Color(0xFF38BDF8))
                                }
                            }
                        }
                        if (localPendingRows.isNotEmpty()) {
                            Text("V telefonu (čeká / předáno síti):", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold)
                            localPendingRows.take(10).forEach { o ->
                                val line = Status.outboxLine(o.kind, "${o.objectType}/${o.objectId}", o.recipient, DeliveryState.valueOf(o.state), o.attempts, o.lastError)
                                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    Text(line.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    Text(line.state + (line.detail?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                            }
                        }
                        if (ackedRows.isNotEmpty()) {
                            Text("Nedávno vyzvednuto (${ackedRows.size}):", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                            ackedRows.take(5).forEach { o ->
                                val line = Status.outboxLine(o.kind, "${o.objectType}/${o.objectId}", o.recipient, DeliveryState.valueOf(o.state), o.attempts, o.lastError)
                                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    Text(line.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    Text("Vyzvednuto a potvrzeno příjemcem", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPublicNodes) {
        RnsPublicNodePickerDialog(
            sl = sl,
            onSelectNode = { chosenHost, chosenPort ->
                scope.launch(Dispatchers.IO) {
                    val combined = "$chosenHost:$chosenPort"
                    sl.settings.savedGatewayNodes = (sl.settings.savedGatewayNodes + combined).distinct()
                    sl.settings.gatewayHost = chosenHost
                    sl.settings.gatewayPort = chosenPort
                    if (sl.settings.networkMode == NetworkMode.LOCAL_ONLY) {
                        sl.settings.networkMode = NetworkMode.AUTO
                    }
                    sl.settings.rnsOnboardingCompleted = true
                    runCatching { sl.bridge.applyPropagationNode() }
                    withContext(Dispatchers.Main) {
                        showPublicNodes = false
                        feedback = "Vybrán veřejný uzel $combined."
                        tick++
                    }
                }
            },
            onDismiss = { showPublicNodes = false }
        )
    }

    if (showQrShare) {
        RnsQrShareDialog(
            nodeName = host,
            host = host,
            port = port.toIntOrNull() ?: 4242,
            propagationNode = propagationNode,
            onDismiss = { showQrShare = false }
        )
    }

    if (showQrImport) {
        RnsQrImportDialog(
            sl = sl,
            onImported = { cfg ->
                showQrImport = false
                feedback = "Načtena konfigurace uzlu ${cfg.name} (${cfg.host}:${cfg.port})."
                tick++
            },
            onDismiss = { showQrImport = false }
        )
    }
}

@Composable
private fun PrivacyAndBackupSection(sl: ServiceLocator) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val zones by io(tick) { sl.settings.privacyZones }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("200") }
    var showManualZoneCoords by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var pendingBackup by remember { mutableStateOf<ByteArray?>(null) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val data = pendingBackup
        if (uri != null && data != null) {
            scope.launch(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                msg = "Šifrovaná záloha byla úspěšně uložena do souboru."
                pendingBackup = null
            }
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            msg = try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("nelze číst")
                val n = sl.backupService.restore(sl.backupService.open(bytes, pass.toCharArray()))
                "Obnoveno $n záznamů ze zálohy."
            } catch (e: BackupException) {
                "Chyba hesla nebo poškozená záloha: ${e.message}"
            } catch (e: Exception) {
                "Obnova selhala: ${e.message}"
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // 1. Šifrovaná záloha a obnova dat
        Card(
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.40f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = R.drawable.img_sport_tactical_hero),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().alpha(0.24f)
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xD9080E18), Color(0xF20A101B))
                            )
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        "ŠIFROVANÝ TREZOR & ZÁLOHA DAT (.P4BK)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFF59E0B),
                        letterSpacing = 0.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Uloží všechna vaše naměřená zdravotní data, aktivity, tréninkové plány a kruhy do jednoho zašifrovaného souboru v telefonu (např. před přechodem na nový telefon).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Zvolte heslo pro zašifrování / odemčení zálohy (min. 8 znaků)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = pass.length >= 8,
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    pendingBackup = sl.backupService.export(pass.toCharArray())
                                    create.launch("pulse4-zaloha.p4bk")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Uložit zálohu", maxLines = 1, softWrap = false) }
                        OutlinedButton(
                            enabled = pass.length >= 8,
                            onClick = { open.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Obnovit ze souboru", maxLines = 1, softWrap = false) }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 2. Skrytí bydliště u sdílených aktivit (srozumitelné vysvětlení pro BFU + automatické nastavení)
        Card(
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.40f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = R.drawable.img_alpine_trail_hero),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().alpha(0.22f)
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xD9080E18), Color(0xF20A101B))
                            )
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        "GEOFENCING: SKRYTÍ BYDLIŠTĚ PŘI SDÍLENÍ TRAS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF10B981),
                        letterSpacing = 0.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Stejně jako na Stravě nebo Garminu: když nasdílíte svůj běh nebo jízdu kamarádům do kruhu, aplikace automaticky ořízne začátek a konec GPS trasy v okruhu např. 200 m kolem vašeho domu, aby nikdo neviděl, kde přesně bydlíte.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                    Spacer(Modifier.height(10.dp))

                    if (zones.isNullOrEmpty()) {
                        Text(
                            "Momentálně nemáte nastavené žádné skryté místo (sdílí se celá trasa).",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    } else {
                        zones?.forEachIndexed { idx, z ->
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.35f)),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1524)),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Skrytý okruh #${idx + 1} (${fmt.elevation(z.radiusM)})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            "GPS: ${fmt.number(z.lat, 4)}, ${fmt.number(z.lon, 4)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = JetBrainsMonoFamily,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                    TextButton(onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            sl.settings.privacyZones = (zones ?: emptyList()) - z
                                            msg = "Skrytý okruh byl odebrán."
                                            tick++
                                        }
                                    }) {
                                        Text("Odebrat", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val latestAct = sl.db.activities().recentSummariesNow(50).firstOrNull { row ->
                                    if (app.pulse4.android.di.ImportService.isMultisportLegId(row.id)) return@firstOrNull false
                                    val fullJson = row.json ?: sl.db.activities().get(row.id)?.json
                                    val act = fullJson?.let { js ->
                                        runCatching { Wire.json.decodeFromString(app.pulse4.domain.Activity.serializer(), js) }.getOrNull()
                                    }
                                    act == null || ("multisport-leg" !in act.tags && act.tags.none { it.startsWith("parent:") })
                                }
                                val pts = latestAct?.let { sl.db.activities().track(it.id) }?.let { tr ->
                                    runCatching {
                                        Wire.json.decodeFromString(
                                            kotlinx.serialization.builtins.ListSerializer(app.pulse4.domain.TrackPoint.serializer()),
                                            tr.json
                                        )
                                    }.getOrNull()
                                }.orEmpty()
                                val firstGps = pts.firstOrNull { it.lat != null && it.lon != null }
                                if (firstGps?.lat != null && firstGps.lon != null) {
                                    val r = radius.toDoubleOrNull()?.coerceAtLeast(50.0) ?: 200.0
                                    sl.settings.privacyZones = (zones ?: emptyList()) + PrivacyZone(firstGps.lat!!, firstGps.lon!!, r)
                                    msg = "Nastaven skrytý okruh (${r.toInt()} m) podle startu vaší poslední aktivity."
                                    tick++
                                } else {
                                    msg = "Nebyla nalezena žádná aktivita s GPS trasou. Zadejte polohu ručně níže."
                                    showManualZoneCoords = true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Nastavit automaticky podle startu poslední aktivity")
                    }

                    TextButton(
                        onClick = { showManualZoneCoords = !showManualZoneCoords },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (showManualZoneCoords) "Skrýt ruční zadání GPS ▲" else "Zadat GPS souřadnice ručně ▼")
                    }

                    if (showManualZoneCoords) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(value = lat, onValueChange = { lat = it }, label = { Text("Šířka (lat)") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = lon, onValueChange = { lon = it }, label = { Text("Délka (lon)") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = radius, onValueChange = { radius = it.filter(Char::isDigit) }, label = { Text("Okruh (m)") }, singleLine = true, modifier = Modifier.weight(0.9f))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                val la = lat.replace(',', '.').toDoubleOrNull()
                                val lo = lon.replace(',', '.').toDoubleOrNull()
                                val r = radius.toDoubleOrNull()
                                if (la == null || lo == null || r == null || la !in -90.0..90.0 || lo !in -180.0..180.0 || r < 50) {
                                    msg = "Zadejte platné GPS souřadnice a okruh alespoň 50 m."
                                } else {
                                    scope.launch(Dispatchers.IO) {
                                        sl.settings.privacyZones = (zones ?: emptyList()) + PrivacyZone(la, lo, r)
                                        lat = ""; lon = ""; tick++
                                    }
                                    msg = "Skrytý okruh soukromí přidán."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Přidat zadané místo") }
                    }
                }
            }
        }

        msg?.let {
            Spacer(Modifier.height(10.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private data class WithingsSettingsSnapshot(
    val clientId: String,
    val hasCreds: Boolean,
    val profileLabel: String,
    val filterEnabled: Boolean,
    val minKg: Double,
    val maxKg: Double,
    val childRecipientIdentity: String,
    val childAutoForward: Boolean,
    val childMinKg: Double,
    val childMaxKg: Double,
    val acceptFromParent: Boolean,
    val childWithingsUserId: String?,
)
