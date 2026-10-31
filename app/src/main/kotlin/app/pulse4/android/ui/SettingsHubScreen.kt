@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
import app.pulse4.ui.Status
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
 * 1 -> Globální Sdílení (přepínače sdílení po sekcích pro kontakty a skupiny)
 * 2 -> Garmin & Withings (API klíče, přihlášení e-mail/heslo/MFA, Health Connect)
 * 3 -> Reticulum P2P Síť (režim, brány, propagation uzel, identita, fronta)
 * 4 -> Soukromí & Záloha (zóny soukromí, mapy, šifrovaný export/obnova)
 */
@Composable
fun SettingsHubScreen(sl: ServiceLocator, initialTab: Int = 0) {
    var selectedTab by remember { mutableStateOf(initialTab) }
    val tabs = listOf(
        "🎛️ Jednotky & Mapy" to 0,
        "📤 Globální Sdílení" to 1,
        "🔗 Garmin & Withings" to 2,
        "📡 Reticulum P2P" to 3,
        "🛡️ Soukromí & Záloha" to 4
    )

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 12.dp,
            containerColor = Color(0xFF0E121B),
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEach { (label, index) ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            label,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == index) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> GeneralAndUnitsSection(sl)
            1 -> GlobalSharingSection(sl)
            2 -> SourcesSettingsSection(sl)
            3 -> ReticulumSettingsSection(sl)
            4 -> PrivacyAndBackupSection(sl)
        }
    }
}

@Composable
private fun GeneralAndUnitsSection(sl: ServiceLocator) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val currentUnits by sl.settings.unitSystemFlow.collectAsState()
    val currentMapProvider by sl.settings.mapProviderFlow.collectAsState()
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
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "SOUSTAVA JEDNOTEK (METRICKÉ VS. IMPERIÁLNÍ)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Zvolená soustava okamžitě přepočítá vzdálenosti, tempo, převýšení i tělesnou hmotnost napříč celou aplikací.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = currentUnits == UnitSystem.METRIC,
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                sl.settings.unitSystem = UnitSystem.METRIC
                                activeFmt = Fmt(ZoneId.of(sl.settings.zoneId), units = UnitSystem.METRIC)
                                feedback = "✅ Nastaveny metrické jednotky (km, m, kg, min/km)."
                                tick++
                            }
                        },
                        label = { Text("📏 Metrické (km, kg)", maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = currentUnits == UnitSystem.IMPERIAL,
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                sl.settings.unitSystem = UnitSystem.IMPERIAL
                                activeFmt = Fmt(ZoneId.of(sl.settings.zoneId), units = UnitSystem.IMPERIAL)
                                feedback = "✅ Nastaveny imperiální jednotky (mi, ft, lb, min/mi)."
                                tick++
                            }
                        },
                        label = { Text("🗽 Imperiální (mi, lb)", maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))
                LabeledRow("Vzdálenost & tempo", if (currentUnits == UnitSystem.IMPERIAL) "Míle (mi) · min/mi" else "Kilometry (km) · min/km")
                LabeledRow("Nadmořská výška", if (currentUnits == UnitSystem.IMPERIAL) "Stopy (ft)" else "Metry (m)")
                LabeledRow("Tělesná hmotnost", if (currentUnits == UnitSystem.IMPERIAL) "Libry (lb)" else "Kilogramy (kg)")
            }
        }

        Spacer(Modifier.height(14.dp))

        // Výběr poskytovatele mapového podkladu (Map Provider)
        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "🗺️ POSKYTOVATEL MAPOVÉHO PODKLADU AKTIVIT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Zvolte mapový podklad pro vykreslování GPS tras v detailu a porovnání aktivit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.height(10.dp))

                FlowRow(Modifier.fillMaxWidth()) {
                    MapProvider.entries.forEach { mp ->
                        FilterChip(
                            selected = currentMapProvider == mp,
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    sl.settings.mapProvider = mp
                                    feedback = "✅ Mapový podklad změněn na: ${mp.title}"
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
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8)
                )
                Text(
                    currentMapProvider.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
                Text(
                    "Atribuce: ${currentMapProvider.attribution}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B),
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
                                feedback = "✅ Vlastní URL mapového stylu uložena."
                                tick++
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Uložit vlastní URL mapy") }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Profil uživatele a roleta s časovými pásmy
        var zoneMenuExpanded by remember { mutableStateOf(false) }
        var zoneSearch by remember { mutableStateOf("") }
        val pinnedZones = remember {
            listOf(
                "Europe/Prague" to "🇨🇿 Praha / Střední Evropa (Europe/Prague)",
                "Europe/Bratislava" to "🇸🇰 Bratislava (Europe/Bratislava)",
                "Europe/Vienna" to "🇦🇹 Vídeň (Europe/Vienna)",
                "Europe/Berlin" to "🇩🇪 Berlín (Europe/Berlin)",
                "Europe/Warsaw" to "🇵🇱 Varšava (Europe/Warsaw)",
                "Europe/London" to "🇬🇧 Londýn (Europe/London)",
                "UTC" to "🌐 Světový čas (UTC)",
                "America/New_York" to "🇺🇸 New York (America/New_York)",
                "America/Chicago" to "🇺🇸 Chicago (America/Chicago)",
                "America/Los_Angeles" to "🇺🇸 Los Angeles (America/Los_Angeles)",
                "Asia/Tokyo" to "🇯🇵 Tokio (Asia/Tokyo)",
                "Australia/Sydney" to "🇦🇺 Sydney (Australia/Sydney)"
            )
        }
        val allZones = remember {
            val pinnedIds = pinnedZones.map { it.first }.toSet()
            pinnedZones + ZoneId.getAvailableZoneIds()
                .filter { it !in pinnedIds && !it.startsWith("SystemV") && !it.startsWith("Etc/") }
                .sorted()
                .map { it to "🌍 $it" }
        }
        val filteredZones = remember(zoneSearch, allZones) {
            val q = zoneSearch.trim().lowercase()
            if (q.isEmpty()) allZones.take(35)
            else allZones.filter { it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }.take(40)
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "PROFIL A ČASOVÉ PÁSMO",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Moje jméno (zobrazí se v QR kódu a skupině)") },
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
                                feedback = "✅ Nastaveno pásmo podle telefonu: $sysZone"
                                tick++
                            }
                        }
                    ) {
                        Text("📍 Podle telefonu", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                    }
                }

                // Roleta (Dropdown) pro výběr časového pásma
                Box(Modifier.fillMaxWidth()) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (zoneMenuExpanded) Color(0xFF38BDF8) else Color(0xFF334155)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { zoneMenuExpanded = !zoneMenuExpanded }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val currentLabel = allZones.firstOrNull { it.first == zoneId }?.second ?: "🌍 $zoneId"
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
                                label = { Text("🔍 Hledat město nebo zónu…") },
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
                                            feedback = "✅ Časové pásmo změněno na: ${validZone.id}"
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
                            feedback = "✅ Jméno profilu ('${sl.settings.displayName}') a časové pásmo (${validZone.id}) uloženo."
                            tick++
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Uložit profil")
                }
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
 * a zvolí, zda sdílet všem kontaktům nebo vybrané skupině.
 */
@Composable
private fun GlobalSharingSection(sl: ServiceLocator) {
    val scope = rememberCoroutineScope()
    val grants by sl.db.grants().all().collectAsState(emptyList())
    val contacts by sl.db.contacts().all().collectAsState(emptyList())
    val groups by sl.db.grants().groups().collectAsState(emptyList())

    val activeGrants = remember(grants) {
        grants.mapNotNull { runCatching { Wire.json.decodeFromString(ShareGrant.serializer(), it.json) }.getOrNull() }
            .filter { it.active }
    }

    val initialFields = remember(activeGrants) {
        activeGrants.firstOrNull()?.fields ?: emptySet()
    }
    var enabledCategories by remember(initialFields) {
        mutableStateOf(
            Category.entries.filter { cat ->
                val cf = Field.entries.filter { it.category == cat }
                cf.isNotEmpty() && cf.any { it in initialFields }
            }.toSet()
        )
    }
    var selectedRecipients by remember(activeGrants, contacts) {
        mutableStateOf(
            activeGrants.firstOrNull()?.recipients ?: contacts.map { it.identity }.toSet()
        )
    }
    var notice by remember { mutableStateOf<String?>(null) }

    val sectionDefs = listOf(
        Triple(Category.SLEEP, "🛌 Spánek", "Délka spánku, skóre kvality a fáze spánku"),
        Triple(Category.BODY_BATTERY, "⚡ Body Battery & Regenerace", "Aktuální stav energie, denní min/max a křivka"),
        Triple(Category.HEART_RATE, "❤️ Kardio & Klidový tep", "Klidová tepová frekvence a denní průběh"),
        Triple(Category.HRV, "💓 Variabilita tepu (HRV)", "Noční rMSSD průměr a průběh regenerace"),
        Triple(Category.ACTIVITY, "🏃 Aktivity & Trasy", "Sportovní záznamy, souhrny, křivky a sanitizované GPS trasy"),
        Triple(Category.BODY_COMPOSITION, "⚖️ Tělesné složení & Váha", "Tělesná hmotnost a procento tělesného tuku")
    )

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "GLOBÁLNÍ NASTAVENÍ SDÍLENÍ PO SEKCÍCH",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Zapněte sekce, které chcete automaticky sdílet přes P2P. Nezapnuté sekce nikdy neopustí váš telefon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.height(10.dp))

                // Rychlá tlačítka
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { enabledCategories = Category.entries.toSet() },
                        modifier = Modifier.weight(1f)
                    ) { Text("✨ Zapnout vše") }
                    Button(
                        onClick = { enabledCategories = emptySet() },
                        modifier = Modifier.weight(1f)
                    ) { Text("🧹 Vypnout vše") }
                }

                Spacer(Modifier.height(10.dp))

                sectionDefs.forEach { (cat, title, subtitle) ->
                    val isOn = cat in enabledCategories
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                        Switch(
                            checked = isOn,
                            onCheckedChange = { checked ->
                                enabledCategories = if (checked) enabledCategories + cat else enabledCategories - cat
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Příjemci globálního sdílení:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                if (contacts.isEmpty()) {
                    Text(
                        "Zatím nemáte spárované žádné kontakty (přidejte je v záložce Skupiny → Kontakty).",
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
                            },
                            label = { Text("👥 Všechny kontakty (${contacts.size})") },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        contacts.forEach { c ->
                            FilterChip(
                                selected = c.identity in selectedRecipients,
                                onClick = {
                                    selectedRecipients = if (c.identity in selectedRecipients) selectedRecipients - c.identity else selectedRecipients + c.identity
                                },
                                label = { Text("👤 ${c.displayName}") },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = contacts.isNotEmpty() && selectedRecipients.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val self = sl.bridge.ensureStarted().deliveryHash
                                val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
                                // Odvolat předchozí aktivní granty při přepsání globálního profilu
                                activeGrants.forEach { old -> sl.shareService.revoke(old.id, System.currentTimeMillis()) }
                                val chosenFields = Field.entries.filter { it.category in enabledCategories }.toSet()
                                if (chosenFields.isNotEmpty()) {
                                    val newGrant = ShareGrant(
                                        id = UUID.randomUUID().toString(),
                                        ownerId = self,
                                        recipients = selectedRecipients,
                                        fields = chosenFields,
                                        scope = TimeScope.continuousFrom(today.minusDays(7)),
                                        groupId = null,
                                        createdAtMs = System.currentTimeMillis()
                                    )
                                    sl.shareService.saveGrant(newGrant)
                                    sl.shareService.planAndEnqueue(System.currentTimeMillis())
                                    notice = "✅ Globální sdílení uloženo (${enabledCategories.size} sekcí, ${chosenFields.size} polí) a zařazeno k odeslání."
                                } else {
                                    notice = "🔒 Všechna sdílení byla vypnuta. Žádná data se nesdílejí."
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Uložit globální profil sdílení")
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
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF232A3B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("🟢 Aktivní grant · ${g.recipients.size} příjemců · ${g.fields.size} polí", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                sl.shareService.revoke(g.id, System.currentTimeMillis())
                                notice = "🔒 Sdílení odvoláno."
                            }
                        }) {
                            Text("Odvolat", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(g.fields.joinToString { app.pulse4.ui.Labels.field(it) }, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                }
            }
        }
    }
}

@Composable
private fun SourcesSettingsSection(sl: ServiceLocator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by sl.db.areaStates().all().collectAsState(emptyList())
    val wState by sl.withings.state.collectAsState()
    var apiKey by remember { mutableStateOf("") }
    var keyDays by remember { mutableStateOf(90) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mfa by remember { mutableStateOf("") }
    var needMfa by remember { mutableStateOf(false) }
    var withingsClientId by remember { mutableStateOf("") }
    var withingsSecret by remember { mutableStateOf("") }
    var profileLabelInput by remember { mutableStateOf("") }
    var minKgInput by remember { mutableStateOf("") }
    var maxKgInput by remember { mutableStateOf("") }
    var childWeightSendKg by remember { mutableStateOf("") }
    var manualWeightKg by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var showAndroidAccountDetails by remember { mutableStateOf(false) }
    var showFamilyGuide by remember { mutableStateOf(true) }

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
    val keyExpires by io(tick + states.size) { sl.secrets.getString(SecretStore.GARMIN_API_KEY_EXPIRES)?.toLongOrNull() }

    // CSV import z aplikace Withings
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            notice = withContext(Dispatchers.IO) {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext "❌ Soubor CSV nelze přečíst."
                val r = sl.withings.importCsv(bytes)
                "✅ Withings CSV: naimportováno ${r.items.size} měření" +
                    if (r.errors.isNotEmpty()) " (přeskočeno ${r.errors.size} řádků)" else ""
            }
            tick++
        }
    }

    // Detekce nainstalovaných oficiálních aplikací v Androidu
    val garminAppInstalled = remember(tick) {
        runCatching { ctx.packageManager.getPackageInfo("com.garmin.android.apps.connectmobile", 0); true }.getOrDefault(false)
    }
    val withingsAppInstalled = remember(tick) {
        runCatching { ctx.packageManager.getPackageInfo("com.withings.wiscale2", 0); true }.getOrDefault(false)
    }

    // Android systémový výběr účtu z Nastavení -> Účty (bez nutnosti nebezpečných oprávnění)
    val accountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pickedName = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
        val pickedType = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_TYPE)
        if (!pickedName.isNullOrBlank()) {
            email = pickedName
            notice = "✅ Převzat účet ze systému Android: $pickedName (${pickedType ?: "systémový účet"}). Heslo doplní správce hesel (Bitwarden / Autofill)."
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // 0. Integrace se systémovými účty Androidu a správci hesel (Bitwarden, 1Password, KeePass, Google)
        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF0EA5E9).copy(alpha = 0.45f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "📱 ÚČTY V ANDROIDU & SPRÁVCI HESEL (BITWARDEN…)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showAndroidAccountDetails = !showAndroidAccountDetails }) {
                        Text(
                            if (showAndroidAccountDetails) "Skrýt ▲" else "Info ▼",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF38BDF8),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                Text(
                    "Využijte účty z Nastavení → Účty a automatické vyplňování ze správce hesel (Bitwarden, 1Password, KeePass, Google):",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCBD5E1)
                )
                Spacer(Modifier.height(8.dp))

                // Stav aplikací v telefonu
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text("⌚ Garmin Connect", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(
                                if (garminAppInstalled) "✅ Aplikace v telefonu" else "⚪ Přímé API připojení",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (garminAppInstalled) Color(0xFF10B981) else Color(0xFF38BDF8)
                            )
                        }
                    }
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text("⚖️ Withings", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(
                                if (withingsAppInstalled) "✅ Aplikace v telefonu" else "⚪ Přímé Withings API",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (withingsAppInstalled) Color(0xFF10B981) else Color(0xFF38BDF8)
                            )
                        }
                    }
                }

                if (showAndroidAccountDetails) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                        border = BorderStroke(1.dp, Color(0xFF232A3B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "🔑 Spolupráce s Bitwardenem, 1Password, KeePassDX i Googlem",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF8FAFC)
                            )
                            Text(
                                "Přihlašovací pole v aplikaci i přihlášení k Withingsu v systémovém prohlížeči plně podporují Android Autofill. Váš správce hesel (např. Bitwarden) tak bezpečně doplní přihlašovací údaje bez ručního opisování.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            runCatching {
                                val intent = android.accounts.AccountManager.newChooseAccountIntent(
                                    null, null, null, null, null, null, null
                                )
                                accountPickerLauncher.launch(intent)
                            }.onFailure {
                                notice = "⚠️ Systémový dialog účtů se nepodařilo otevřít: ${it.message}"
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("👤 Vybrat účet", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                                        data = Uri.parse("package:${ctx.packageName}")
                                    }
                                )
                            }.onFailure {
                                runCatching {
                                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔑 Správce hesel", maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 1. Garmin Connect Personal API
        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("GARMIN CONNECT – OSOBNÍ API KLÍČ (ČTENÍ METRIK & AKTIVIT)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                Spacer(Modifier.height(6.dp))
                Text(
                    "Oficiální Personal API klíč se šifruje v Android Keystore. Slouží ke čtení spánku, HRV, Body Battery, tepu, stresu, kroků a aktivit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
                Status.keyExpiry(keyExpires, System.currentTimeMillis())?.let { exp ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        exp.text,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (exp.warn) MaterialTheme.colorScheme.error else Color(0xFF10B981)
                    )
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Osobní API klíč (GCDC_...)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Platnost: ", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    listOf(30, 90, 180, 365).forEach { d ->
                        FilterChip(
                            selected = keyDays == d,
                            onClick = { keyDays = d },
                            label = { Text("${d}d") },
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = apiKey.isNotBlank(),
                        onClick = {
                            val trimmed = apiKey.trim()
                            scope.launch(Dispatchers.IO) {
                                sl.secrets.putString(SecretStore.GARMIN_API_KEY, trimmed)
                                sl.secrets.putString(SecretStore.GARMIN_API_KEY_EXPIRES, (System.currentTimeMillis() + keyDays.toLong() * 86_400_000L).toString())
                                apiKey = ""
                                notice = "✅ Garmin API klíč uložen do Android Keystore."
                                tick++
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Uložit klíč") }

                    Button(
                        onClick = {
                            scope.launch {
                                syncing = true
                                notice = null
                                val res = withContext(Dispatchers.IO) {
                                    sl.importService.runAll(System.currentTimeMillis())
                                }
                                notice = "✅ Synchronizace Garmin dokončena (${res?.size ?: 0} oblastí)."
                                syncing = false
                                tick++
                            }
                        },
                        enabled = !syncing,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (syncing) "Stahuji…" else "🔄 Sync Garmin") }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 2. Garmin Connect Login (pro zápis tréninků do hodinek)
        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("GARMIN CONNECT – PŘIHLÁŠENÍ PRO ZÁPIS TRÉNINKŮ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                Spacer(Modifier.height(6.dp))
                Text(
                    "Slouží k nahrávání naplánovaných tréninků do kalendáře Garmin Connect. Podporuje automatické vyplnění přes Bitwarden, 1Password, KeePass i Google Správce hesel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Garmin E-mail (podpora Bitwarden / Autofill)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Heslo") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (needMfa) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = mfa,
                        onValueChange = { mfa = it },
                        label = { Text("MFA ověřovací kód") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            notice = withContext(Dispatchers.IO) {
                                try {
                                    val r = if (needMfa) sl.garminExt.resumeMfa(mfa) else sl.garminExt.loginPassword(email, password)
                                    when (r) {
                                        LoginResult.Ok -> { password = ""; mfa = ""; needMfa = false; "✅ Přihlášení do Garmin Connect pro export tréninků bylo úspěšné." }
                                        LoginResult.NeedsMfa -> { needMfa = true; "🔐 Zadejte jednorázový MFA kód." }
                                    }
                                } catch (e: ProviderError) {
                                    "❌ Přihlášení selhalo: ${e.message}"
                                } catch (e: Exception) {
                                    "❌ Přihlášení selhalo: ${e.message}"
                                }
                            }
                            tick++
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (needMfa) "Potvrdit MFA kód" else "Přihlásit účet Garmin")
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 3. Přímá integrace Withings (Chytrá váha & Dětské / Rodinné profily)
        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF14B8A6).copy(alpha = 0.45f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "⚖️ PŘÍMÁ INTEGRACE WITHINGS (VÁHA, TUK & RODINNÉ PROFILY)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF14B8A6)
                )
                Spacer(Modifier.height(6.dp))

                // Aktuální stav připojení účtu Withings
                val activeProfileName = profileLabelInput.ifBlank { wCfg?.profileLabel ?: "Hlavní profil (Já)" }
                val connText = when (val s = wState) {
                    is app.pulse4.android.di.WithingsUiState.Connected -> "🟢 Připojeno k Withings (userid: ${s.userId} · $activeProfileName)"
                    app.pulse4.android.di.WithingsUiState.WaitingForBrowser -> "🟡 Čeká se na výběr profilu a potvrzení v prohlížeči…"
                    is app.pulse4.android.di.WithingsUiState.Failed -> "🔴 Přihlášení selhalo (${s.message.lowercase().replace('_', ' ')})"
                    app.pulse4.android.di.WithingsUiState.Idle -> if (wCfg?.hasCreds == true) "⚪ Přístupy uloženy – připraveno k připojení účtu" else "⚪ Zatím nepřipojeno"
                }
                Text(
                    connText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (wState is app.pulse4.android.di.WithingsUiState.Connected) Color(0xFF10B981) else Color(0xFFCBD5E1)
                )

                Spacer(Modifier.height(10.dp))

                // Přístupové údaje přímého Withings API
                OutlinedTextField(
                    value = withingsClientId.ifEmpty { wCfg?.clientId.orEmpty() },
                    onValueChange = { withingsClientId = it },
                    label = { Text("Withings OAuth Client ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = withingsSecret,
                    onValueChange = { withingsSecret = it },
                    label = { Text(if (wCfg?.hasCreds == true) "Withings Client Secret (uložen v Keystore – vyplňte pro změnu)" else "Withings Client Secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = profileLabelInput.ifEmpty { wCfg?.profileLabel.orEmpty() },
                    onValueChange = { profileLabelInput = it },
                    label = { Text("Označení vybraného profilu (např. Hlavní profil / Dítě)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                if (profileLabelInput.isNotBlank()) {
                                    sl.settings.withingsProfileLabel = profileLabelInput.trim()
                                }
                                sl.settings.withingsDirectEnabled = true
                                tick++
                                try {
                                    val authUrl = sl.withings.beginLogin(System.currentTimeMillis())
                                    withContext(Dispatchers.Main) {
                                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(authUrl)))
                                    }
                                } catch (e: Exception) {
                                    notice = "✅ Nastavení uloženo. Pro OAuth přihlášení doplňte Client ID i Client Secret."
                                }
                            }
                        },
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text("🔗 Připojit / Přepnout profil", maxLines = 1, softWrap = false)
                    }

                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val r = runCatching { sl.withings.runImport(System.currentTimeMillis()) }.getOrNull()
                                notice = when (r) {
                                    is app.pulse4.provider.AreaResult.Success -> "✅ Synchronizace Withings dokončena (${r.saved} měření)."
                                    null -> "⚠️ Účet Withings zatím není připojen přes OAuth (můžete také naimportovat CSV níže)."
                                    else -> "⚠️ Synchronizace Withings vyžaduje přihlášení."
                                }
                                tick++
                            }
                        },
                        modifier = Modifier.weight(0.9f)
                    ) {
                        Text("🔄 Sync Withings", maxLines = 1, softWrap = false)
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { csvLauncher.launch(arrayOf("text/*", "application/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📄 Importovat CSV z Withings", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                    }
                    if (wState is app.pulse4.android.di.WithingsUiState.Connected) {
                        TextButton(
                            onClick = {
                                sl.withings.disconnect()
                                notice = "🔌 Účet Withings byl odpojen."
                                tick++
                            }
                        ) {
                            Text("Odpojit", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Sekce: Jak fungují dětské účty a rodinné profily u Withingsu + Ochranný filtr váhy
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1420)),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "👨‍👩‍👧‍👦 DĚTSKÉ ÚČTY A RODINNÉ PROFILY U WITHINGSU",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { showFamilyGuide = !showFamilyGuide }) {
                                Text(
                                    if (showFamilyGuide) "Skrýt ▲" else "Zobrazit ▼",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF38BDF8),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        if (showFamilyGuide) {
                            Text(
                                "• 1. Dětský profil pod účtem rodiče (výběr při přihlášení): Děti ve Withingsu nemají vlastní přihlašovací e-mail/heslo, ale jsou vedeny jako vedlejší profily (Family members) pod hlavním účtem rodiče. Když kliknete na „Připojit / Přepnout profil“, Withings po zadání e-mailu rodiče zobrazí seznam všech členů rodiny (včetně dětí). Každý profil (i dětský) má ve Withingsu své vlastní unikátní userid — do aplikace se pak stahují pouze vážení vybraného člena rodiny.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1)
                            )
                            Text(
                                "• 2. Rozpoznání osoby samotnou váhou (attrib): Pokud má starší dítě podobnou váhu jako rodič (rozdíl pod ~3 kg), váha Withings označí měření jako nejednoznačné (attrib = 1 nebo 5). Pulse4 taková vážení automaticky podrží mimo graf, dokud je v detailu váhy nepotvrdíte.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1)
                            )
                            Text(
                                "• 3. Ochrana před zvážením dítěte nebo vážením s dítětem v náručí: Zapněte níže váhové rozmezí svého profilu. Pokud na váhu stoupne dítě (např. 22 kg) nebo se zvážíte s dítětem v náručí, měření mimo toto rozmezí se automaticky označí jako cizí a nikdy nerozbije váš denní přehled ani graf.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        // Ochranný váhový filtr profilu
                        val filterOn = wCfg?.filterEnabled ?: true
                        val curMin = wCfg?.minKg ?: 45.0
                        val curMax = wCfg?.maxKg ?: 130.0
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "🛡️ Ochranný filtr váhy profilu (${curMin.toInt()}–${curMax.toInt()} kg)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    "Měření mimo rozmezí (např. dítě na váze) se automaticky vyřadí z trendu",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Switch(
                                checked = filterOn,
                                onCheckedChange = { on ->
                                    scope.launch(Dispatchers.IO) {
                                        sl.settings.weightFilterEnabled = on
                                        tick++
                                    }
                                }
                            )
                        }

                        if (filterOn) {
                            // Rychlé předvolby pro Dospělý vs. Dětský profil
                            FlowRow(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                FilterChip(
                                    selected = curMin >= 45.0,
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            sl.settings.weightFilterMinKg = 45.0
                                            sl.settings.weightFilterMaxKg = 130.0
                                            sl.settings.withingsProfileLabel = "Hlavní profil (Dospělý)"
                                            minKgInput = "45"
                                            maxKgInput = "130"
                                            profileLabelInput = "Hlavní profil (Dospělý)"
                                            notice = "✅ Nastaven filtr pro dospělý profil (45–130 kg)."
                                            tick++
                                        }
                                    },
                                    label = { Text("🧑 Dospělý (45–130 kg)", maxLines = 1, softWrap = false) }
                                )
                                FilterChip(
                                    selected = curMax <= 50.0,
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            sl.settings.weightFilterMinKg = 10.0
                                            sl.settings.weightFilterMaxKg = 45.0
                                            sl.settings.withingsProfileLabel = "Dětský profil"
                                            minKgInput = "10"
                                            maxKgInput = "45"
                                            profileLabelInput = "Dětský profil"
                                            notice = "✅ Nastaven filtr pro dětský profil (10–45 kg)."
                                            tick++
                                        }
                                    },
                                    label = { Text("🧒 Dítě (10–45 kg)", maxLines = 1, softWrap = false) }
                                )
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = minKgInput.ifEmpty { curMin.toInt().toString() },
                                    onValueChange = { minKgInput = it },
                                    label = { Text("Min (kg)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = maxKgInput.ifEmpty { curMax.toInt().toString() },
                                    onValueChange = { maxKgInput = it },
                                    label = { Text("Max (kg)") },
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
                                                notice = "✅ Váhový filtr profilu uložen (${mn.toInt()}–${mx.toInt()} kg)."
                                                tick++
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(0.9f)
                                ) {
                                    Text("Uložit", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Sekce: Přeposílání dětské váhy dítěti přes P2P (Dítě má Garmin, ale nemůže mít vlastní Withings účet)
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1420)),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "🧒 JAK POSÍLAT VÁHU DÍTĚTE DÍTĚTI (DÍTĚ MÁ GARMIN, ALE NEMÁ WITHINGS)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                        Text(
                            "Protože dítě má svůj Garmin účet (hodinky), ale ve Withingsu nemůže mít vlastní účet (je vedeno jako dětský profil pod účtem rodiče), váš telefon umí váhu dítěte z Withingsu automaticky posílat přes šifrované P2P přímo do telefonu dítěte. Dítěti se pak jeho váha zobrazí v jeho vlastním Dnešním přehledu a Metrikách!",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )

                        // 1. Výběr kontaktu dítěte ze spárovaných P2P kontaktů
                        Text(
                            "1. Vyberte P2P kontakt dítěte (komu váhu posílat):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (contacts.isEmpty()) {
                            Text(
                                "Zatím nemáte spárovaný telefon dítěte. Spárujte jej přes QR kód v záložce Skupiny.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFF59E0B)
                            )
                        } else {
                            FlowRow(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                contacts.forEach { c ->
                                    val isSelected = wCfg?.childRecipientIdentity == c.identity
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                sl.settings.childRecipientIdentity = if (isSelected) "" else c.identity
                                                notice = if (isSelected) "Přeposílání dětské váhy vypnuto." else "✅ Dětská váha z Withingsu se bude posílat kontaktu: ${c.displayName}."
                                                tick++
                                            }
                                        },
                                        label = {
                                            Text(
                                                if (isSelected) "🧒 ${c.displayName} (Vybráno)" else "👤 ${c.displayName}",
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        // 2. Propojení dětského profilu ve Withingsu (druhý OAuth token pro userid dítěte) + automatické pásmo
                        val childUid = wCfg?.childWithingsUserId
                        Text(
                            if (childUid != null) "2. Dětský profil ve Withingsu: 🟢 Připojen (userid: $childUid)"
                            else "2. Zdroj dětské váhy z vašeho Withingsu:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (childUid != null) Color(0xFF10B981) else Color.White
                        )
                        Text(
                            "Můžete buď připojit přímo dětský pod-profil z vašeho účtu Withings (při přihlášení na webu Withings vyberete profil dítěte), a/nebo nechat automaticky přeposílat všechna vážení na vaší váze v dětském pásmu (${(wCfg?.childMinKg ?: 12.0).toInt()}–${(wCfg?.childMaxKg ?: 45.0).toInt()} kg):",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val authUrl = sl.withings.beginChildLogin(System.currentTimeMillis())
                                            withContext(Dispatchers.Main) {
                                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(authUrl)))
                                            }
                                        } catch (e: Exception) {
                                            notice = "⚠️ Nejprve výše uložte Withings Client ID a Client Secret."
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1.2f)
                            ) {
                                Text("🔗 Připojit dětský profil (OAuth)", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                            }
                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val sent = sl.withings.syncAndForwardChildWeights(System.currentTimeMillis())
                                        notice = if (sent > 0) "✅ Odesláno $sent dětských vážení do telefonu dítěte!"
                                        else if ((wCfg?.childRecipientIdentity ?: "").isBlank()) "⚠️ Nejprve výše vyberte kontakt dítěte."
                                        else "ℹ️ Žádné nové dětské vážení k odeslání."
                                        tick++
                                    }
                                },
                                modifier = Modifier.weight(0.9f)
                            ) {
                                Text("📤 Poslat váhu dítěti", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                            }
                        }

                        // Rychlé ruční odeslání váhy dítěti přes P2P
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = childWeightSendKg,
                                onValueChange = { childWeightSendKg = it },
                                label = { Text("Poslat váhu dítěti (např. 32.5 kg)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1.4f)
                            )
                            Button(
                                onClick = {
                                    val kg = childWeightSendKg.replace(',', '.').toDoubleOrNull()
                                    val childId = wCfg?.childRecipientIdentity.orEmpty()
                                    if (childId.isBlank()) {
                                        notice = "⚠️ Nejprve v bodě 1 vyberte P2P kontakt dítěte."
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
                                            notice = if (ok) "✅ Váha ${fmt.weight(kg)} odeslána přes P2P přímo do Dnešního přehledu dítěte!" else "❌ Odeslání se nezdařilo."
                                            tick++
                                        }
                                    } else {
                                        notice = "❌ Zadejte platnou váhu dítěte v kg."
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🧒 Poslat", maxLines = 1, softWrap = false)
                            }
                        }

                        // 3. Přepínač na telefonu dítěte (přijímat vlastní váhu od rodiče)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "📥 Na telefonu dítěte: Přijímat vlastní váhu od rodiče",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    "Uloží váhu přeposlanou z Withingsu rodiče přímo do mých osobních metrik (Dnes & Váha)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Switch(
                                checked = wCfg?.acceptFromParent ?: true,
                                onCheckedChange = { on ->
                                    scope.launch(Dispatchers.IO) {
                                        sl.settings.acceptWeightFromParent = on
                                        tick++
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualWeightKg,
                        onValueChange = { manualWeightKg = it },
                        label = { Text("Ruční zápis váhy (kg)") },
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
                                    notice = "✅ Váha ${fmt.weight(kg)} uložena."
                                }
                            } else {
                                notice = "❌ Zadejte platnou hmotnost v kg (např. 75.4)."
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Zapsat", maxLines = 1, softWrap = false) }
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

        // 4. Přehled čerstvosti oblastí
        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("STAV SYNCHRONIZACE OBLASTÍ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                Spacer(Modifier.height(8.dp))
                SyncArea.entries.forEach { area ->
                    val st = states.firstOrNull { it.area == area.name }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(area.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (st?.lastSuccessMs != null) "✅ ${fmt.time(st.lastSuccessMs)}"
                            else "Zatím nespuštěno",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (st?.lastSuccessMs != null) Color(0xFF10B981) else Color(0xFF94A3B8)
                        )
                    }
                }
            }
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

    val netData by io(tick) {
        listOf(
            sl.settings.networkMode.name,
            sl.settings.gatewayHost,
            sl.settings.gatewayPort.toString(),
            sl.settings.propagationNode,
            sl.settings.savedGatewayNodes.joinToString(",")
        )
    }
    val myInfo by io(tick) {
        sl.bridge.ensureStarted()
    }

    var mode by remember(netData) {
        mutableStateOf(netData?.getOrNull(0)?.let { runCatching { NetworkMode.valueOf(it) }.getOrNull() } ?: NetworkMode.AUTO)
    }
    var host by remember(netData) { mutableStateOf(netData?.getOrNull(1) ?: "rns.xali.cz") }
    var port by remember(netData) { mutableStateOf(netData?.getOrNull(2) ?: "4242") }
    var propagationNode by remember(netData) { mutableStateOf(netData?.getOrNull(3) ?: "") }
    val savedNodes = remember(netData) {
        netData?.getOrNull(4)?.split(',')?.filter { it.isNotBlank() } ?: listOf("rns.xali.cz:4242", "reticulum.betavine.net:4242")
    }
    var newNodeHost by remember { mutableStateOf("") }
    var newNodePort by remember { mutableStateOf("4242") }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // 1. Srozumitelná volba spojení pro běžného uživatele (BFU)
        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "📡 JAK SE SPOJIT S PŘÁTELI VE SKUPINĚ",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Vyberte, jak si aplikace vyměňuje šifrovaná data s vašimi přáteli:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCBD5E1)
                )
                Spacer(Modifier.height(10.dp))

                val modesInfo = listOf(
                    Triple(
                        NetworkMode.AUTO,
                        "🌐 Automaticky (Doporučeno)",
                        "Spojí vás s přáteli doma na stejné Wi-Fi i přes internet, když jste každý jinde."
                    ),
                    Triple(
                        NetworkMode.LOCAL_ONLY,
                        "🏠 Pouze doma na stejné Wi-Fi",
                        "Data nikdy neopustí vaši domácí Wi-Fi síť. Funguje zcela bez internetu."
                    ),
                    Triple(
                        NetworkMode.MANUAL,
                        "🔧 Vlastní vybraný uzel",
                        "Připojení pouze k serveru (uzlu), který si ručně vyberete nebo přidáte níže."
                    )
                )

                modesInfo.forEach { (m, title, desc) ->
                    val selected = mode == m
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (selected) Color(0xFF38BDF8) else Color(0xFF232A3B)),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) Color(0xFF0F2942) else Color(0xFF1A202E)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                mode = m
                                scope.launch(Dispatchers.IO) {
                                    sl.settings.networkMode = m
                                    feedback = "✅ Nastaven režim spojení: $title"
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
                                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(desc, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                            }
                            Text(
                                if (selected) "🟢 Aktivní" else "Vybrat",
                                style = MaterialTheme.typography.labelSmall,
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
                        feedback = "📡 Spuštěno okamžité odeslání a stažení sdílených dat."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔄 Synchronizovat se skupinou hned teď")
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

        Spacer(Modifier.height(14.dp))

        // 2. Správa P2P uzlů (seznam uzlů s možností přepnout, přidat nový nebo ODEBRAT)
        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
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
                            "🌐 ULOŽENÉ PROPOJOVACÍ UZLY (${savedNodes.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            "Aktivní uzel: $host:$port",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                sl.settings.savedGatewayNodes = listOf("rns.xali.cz:4242", "reticulum.betavine.net:4242")
                                sl.settings.gatewayHost = "rns.xali.cz"
                                sl.settings.gatewayPort = 4242
                                feedback = "↺ Obnoveny výchozí propojovací uzly."
                                tick++
                            }
                        }
                    ) {
                        Text("↺ Výchozí", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
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
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isActive) Color(0xFF10B981).copy(alpha = 0.6f) else Color(0xFF232A3B)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A202E)),
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
                                        fontWeight = FontWeight.Bold,
                                        color = if (isActive) Color(0xFF10B981) else Color.White
                                    )
                                    Text(
                                        if (isActive) "🟢 Právě používaný uzel" else "Uložený uzel",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isActive) Color(0xFF10B981) else Color(0xFF94A3B8)
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!isActive) {
                                        TextButton(
                                            onClick = {
                                                host = nHost
                                                port = nPort.toString()
                                                scope.launch(Dispatchers.IO) {
                                                    sl.settings.gatewayHost = nHost
                                                    sl.settings.gatewayPort = nPort
                                                    runCatching { sl.bridge.announce() }
                                                    feedback = "✅ Přepnuto na uzel $nHost:$nPort"
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
                                                }
                                                feedback = "🗑️ Uzel $entry byl odebrán."
                                                tick++
                                            }
                                        }
                                    ) {
                                        Text("🗑️ Odebrat", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
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
                                newNodeHost = ""
                                feedback = "✅ Uzel $combined přidán a aktivován."
                                tick++
                            }
                        }
                    ) {
                        Text("➕ Přidat", maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 3. Pokročilé detaily (Moje P2P adresa, Offline schránka s možností odebrat, Fronta zpráv)
        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🔧 POKROČILÉ SÍŤOVÉ DETAILY A FRONTA (${outbox.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showAdvancedP2p = !showAdvancedP2p }) {
                        Text(if (showAdvancedP2p) "Skrýt ▲" else "Zobrazit ▼", maxLines = 1, softWrap = false)
                    }
                }

                if (showAdvancedP2p) {
                    Spacer(Modifier.height(8.dp))
                    Text("Moje P2P doručovací adresa:", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            myInfo?.deliveryHash ?: "Načítání…",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                myInfo?.deliveryHash?.let {
                                    clipboard.setText(AnnotatedString(it))
                                    feedback = "✅ Adresa zkopírována do schránky"
                                }
                            }
                        ) {
                            Text("📋 Kopírovat", maxLines = 1, softWrap = false)
                        }
                    }

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
                                        feedback = "🗑️ Offline schránka odebrána."
                                        tick++
                                    }
                                }
                            ) {
                                Text("🗑️ Odebrat", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                            }
                        }
                        Button(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    sl.settings.propagationNode = propagationNode
                                    runCatching { sl.bridge.applyPropagationNode() }
                                    feedback = "✅ Nastavení offline schránky uloženo."
                                    tick++
                                }
                            }
                        ) {
                            Text("Uložit", maxLines = 1, softWrap = false)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("Odchozí fronta zpráv (${outbox.size}):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                    if (outbox.isEmpty()) {
                        Text("Všechny zprávy byly odeslány, fronta je prázdná.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                    }
                    outbox.take(10).forEach { o ->
                        val line = Status.outboxLine(o.kind, "${o.objectType}/${o.objectId}", o.recipient, DeliveryState.valueOf(o.state), o.attempts, o.lastError)
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(line.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text(line.state + (line.detail?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                            }
                        }
                    }
                }
            }
        }
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
                msg = "✅ Šifrovaná záloha byla úspěšně uložena do souboru."
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
                "✅ Obnoveno $n záznamů ze zálohy."
            } catch (e: BackupException) {
                "❌ Chyba hesla nebo poškozená záloha: ${e.message}"
            } catch (e: Exception) {
                "❌ Obnova selhala: ${e.message}"
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
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "💾 ŠIFROVANÁ ZÁLOHA VŠECH DAT DO SOUBORU",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Uloží všechna vaše naměřená zdravotní data, aktivity, tréninkové plány a skupiny do jednoho zašifrovaného souboru v telefonu (např. před přechodem na nový telefon).",
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
                    ) { Text("📤 Uložit zálohu", maxLines = 1, softWrap = false) }
                    Button(
                        enabled = pass.length >= 8,
                        onClick = { open.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("📥 Obnovit ze souboru", maxLines = 1, softWrap = false) }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 2. Skrytí bydliště u sdílených aktivit (srozumitelné vysvětlení pro BFU + automatické nastavení)
        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "🏠 SKRYTÍ BYDLIŠTĚ PŘI SDÍLENÍ AKTIVIT DO SKUPINY",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Stejně jako na Stravě nebo Garminu: když nasdílíte svůj běh nebo jízdu kamarádům do skupiny, aplikace automaticky ořízne začátek a konec GPS trasy v okruhu např. 200 m kolem vašeho domu, aby nikdo neviděl, kde přesně bydlíte.",
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
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A202E)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "🏠 Skrytý okruh #${idx + 1} (${fmt.elevation(z.radiusM)})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        "GPS: ${fmt.number(z.lat, 4)}, ${fmt.number(z.lon, 4)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        sl.settings.privacyZones = (zones ?: emptyList()) - z
                                        msg = "🗑️ Skrytý okruh byl odebrán."
                                        tick++
                                    }
                                }) {
                                    Text("🗑️ Odebrat", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val latestAct = sl.db.activities().allNow().firstOrNull()
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
                                msg = "✅ Nastaven skrytý okruh (${r.toInt()} m) podle startu vaší poslední aktivity."
                                tick++
                            } else {
                                msg = "⚠️ Nebyla nalezena žádná aktivita s GPS trasou. Zadejte polohu ručně níže."
                                showManualZoneCoords = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📍 Nastavit automaticky podle startu poslední aktivity")
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
                                msg = "❌ Zadejte platné GPS souřadnice a okruh alespoň 50 m."
                            } else {
                                scope.launch(Dispatchers.IO) {
                                    sl.settings.privacyZones = (zones ?: emptyList()) + PrivacyZone(la, lo, r)
                                    lat = ""; lon = ""; tick++
                                }
                                msg = "✅ Skrytý okruh soukromí přidán."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("➕ Přidat zadané místo") }
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
