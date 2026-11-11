@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import app.pulse4.android.di.HcResult
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.di.WithingsUiState
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Quality
import app.pulse4.provider.AreaResult
import app.pulse4.provider.AreaState
import app.pulse4.provider.AreaStatus
import app.pulse4.provider.Freshness
import app.pulse4.provider.SyncArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Provider.label() = app.pulse4.ui.Labels.provider(this)

/**
 * Tělo: trend váhy a složení těla z několika zdrojů. Stejné vážení přes víc cest je jeden bod trendu (zdroje zůstávají
 * dohledatelné); nejednoznačné měření (např. váhu použila jiná osoba) do trendu nevstupuje, dokud je neprovedete jako své.
 * Sdílení váhy a složení těla je ve výchozím stavu vypnuté.
 */
@Composable
fun BodyScreen(sl: ServiceLocator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var type by remember { mutableStateOf(MetricType.WEIGHT) }
    var showSuspect by remember { mutableStateOf(true) }
    var showWebDialog by remember { mutableStateOf(false) }

    val points by io(Triple(tick, type, showSuspect)) { sl.withings.trend(type, 180, includeSuspect = showSuspect) }
    val sourceStates by sl.db.areaStates().all().collectAsState(emptyList())
    val wState by sl.withings.state.collectAsState()
    val cfg by io(tick) { Triple(sl.settings.withingsDirectEnabled, sl.settings.healthConnectEnabled, sl.settings.withingsClientId) }
    var clientId by remember(cfg) { mutableStateOf(cfg?.third ?: "") }
    var secret by remember { mutableStateOf("") }

    if (showWebDialog) {
        WithingsWebLoginDialog(
            sl = sl,
            mode = if (wState is WithingsUiState.Connected) WithingsDialogMode.SelectProfilesOnly else WithingsDialogMode.FullLogin,
            onDismiss = { showWebDialog = false },
            onSuccess = { profile ->
                showWebDialog = false
                msg = "Withings připojen (${profile.displayName})"
                tick++
            }
        )
    }

    val hcPerm = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        msg = if (granted.containsAll(sl.healthConnect.permissions)) "Oprávnění uděleno" else "Bez oprávnění nelze Health Connect číst"
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            msg = withContext(Dispatchers.IO) {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext "Soubor nelze přečíst"
                val r = sl.withings.importCsv(bytes)
                "CSV: přijato ${r.items.size}, odmítnuto ${r.errors.size}" + (r.errors.firstOrNull()?.let { " (řádek ${it.line}: ${it.reason})" } ?: "")
            }
            tick++
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Tělo", style = MaterialTheme.typography.titleLarge)
        FlowRow {
            listOf(
                MetricType.WEIGHT to "Váha",
                MetricType.BODY_FAT to "Tělesný tuk",
                MetricType.MUSCLE_MASS to "Svaly",
                MetricType.BODY_WATER to "Voda",
                MetricType.BONE_MASS to "Kosti",
                MetricType.FAT_FREE_MASS to "FFM",
                MetricType.VISCERAL_FAT to "Viscerální tuk",
                MetricType.VASCULAR_AGE to "Cévní věk",
                MetricType.PULSE_WAVE_VELOCITY to "PWV"
            ).forEach { (t, l) ->
                val mCfg = getMetricUiConfig(t)
                FilterChip(
                    selected = type == t,
                    onClick = { type = t },
                    leadingIcon = {
                        androidx.compose.material3.Icon(
                            imageVector = mCfg.icon,
                            contentDescription = null,
                            tint = mCfg.color,
                            modifier = Modifier.padding(end = 2.dp)
                        )
                    },
                    label = { Text(l) },
                    modifier = Modifier.padding(2.dp)
                )
            }
        }
        val pts = points
        if (pts == null) Text("Načítání…")
        else if (pts.isEmpty()) Text("Zatím žádná měření. Připojte zdroj níže nebo importujte CSV.")
        else {
            LineChart(pts.map { it.value })
            Text("Poslední: ${fmt.number(pts.last().value, 1)} ${type.unit} · ${pts.last().localDate}")
        }
        Row { Text("Zahrnout nejednoznačná měření"); Switch(showSuspect, { showSuspect = it }, Modifier.padding(start = 8.dp)) }

        SectionTitle("Měření a jejich původ")
        pts?.takeLast(15)?.reversed()?.forEach { o -> MeasurementRow(o, onWrong = { scope.launch(Dispatchers.IO) { sl.withings.markWrong(o.id, System.currentTimeMillis()); tick++ } },
            onConfirm = { scope.launch(Dispatchers.IO) { sl.withings.confirm(o.id); tick++ } }) }

        SectionTitle("Zdroj: Přímý webový konektor Withings (doporučeno)")
        Text("Přihlášení přímo k účtu Withings bez nutnosti registrace vývojářského účtu či serveru. Stahuje kompletní složení těla a ECG souhrny.", style = MaterialTheme.typography.bodySmall)
        Row {
            Button({ showWebDialog = true }) {
                Text(if (wState is WithingsUiState.Connected) "Změnit profil / Přihlásit" else "Přihlásit k Withings")
            }
            Button(
                enabled = sl.withings.isConnected(),
                onClick = {
                    scope.launch {
                        msg = withContext(Dispatchers.IO) {
                            when (val r = sl.withings.runImport(System.currentTimeMillis())) {
                                null -> "Withings není připojen"
                                is AreaResult.Success -> "Withings: uloženo ${r.saved} záznamů"
                                is AreaResult.NeedsUser -> "Vyžaduje akci: ${r.action}"
                                is AreaResult.Deferred -> "Odloženo (rate limit)"
                                else -> "Import selhal"
                            }
                        }
                        tick++
                    }
                },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Synchronizovat") }
        }
        SourceStatus("withings.", sourceStates)

        SectionTitle("Zdroj: Health Connect")
        Text("Aplikace Withings exportuje váhu a složení těla do Health Connect. Ne všechny metriky Withings se tam dostanou; čtení na pozadí a historie nad 30 dní vyžadují zvláštní oprávnění.", style = MaterialTheme.typography.bodySmall)
        Row { Text("Používat Health Connect"); Switch(cfg?.second == true, { v -> scope.launch(Dispatchers.IO) { sl.settings.healthConnectEnabled = v; tick++ } }, Modifier.padding(start = 8.dp)) }
        Row {
            Button({ hcPerm.launch(sl.healthConnect.permissions + sl.healthConnect.optionalPermissions) }) { Text("Udělit oprávnění") }
            Button({ scope.launch { msg = withContext(Dispatchers.IO) { when (val r = sl.healthConnect.sync(System.currentTimeMillis(), background = false)) {
                is HcResult.Done -> "Health Connect: ${r.upserts} záznamů, ${r.deletions} smazání (${r.mode.name.lowercase()})"
                HcResult.NoPermission -> "Chybí oprávnění"; HcResult.Unavailable -> "Health Connect není dostupný"
            } }; tick++ } }, Modifier.padding(start = 8.dp)) { Text("Importovat teď") }
        }
        SourceStatus("hc.", sourceStates)

        SectionTitle("Zdroj: přímé Withings API (pokročilé, neověřeno)")
        Text("Vlastní vývojářské přístupy ze Withings se zadávají do tohoto telefonu a zůstávají zašifrované. Před zapnutím projděte bezpečnostní bránu v docs/withings.md. Přímý režim není standardní veřejný mobilní login (nemá doložené PKCE).", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(clientId, { clientId = it }, label = { Text("client_id") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(secret, { secret = it }, label = { Text("client_secret") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = clientId.isNotBlank() && secret.isNotBlank(), onClick = { scope.launch(Dispatchers.IO) { sl.withings.saveCredentials(clientId, secret); secret = ""; tick++ } }) { Text("Uložit přístupy") }
        Row { Text("Zapnout přímý režim"); Switch(cfg?.first == true, { v -> scope.launch(Dispatchers.IO) { sl.settings.withingsDirectEnabled = v; tick++ } }, Modifier.padding(start = 8.dp)) }
        Row {
            Button(enabled = cfg?.first == true && sl.withings.hasCredentials(), onClick = {
                scope.launch {
                    val url = withContext(Dispatchers.IO) { sl.withings.beginLogin(System.currentTimeMillis()) }
                    // Systémový prohlížeč (ne WebView); návrat přes ověřený HTTPS App Link.
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }) { Text("Připojit Withings") }
            TextButton({ sl.withings.disconnect() }) { Text("Odpojit") }
        }
        Text(when (val s = wState) {
            WithingsUiState.Idle -> "Nepřipojeno"; WithingsUiState.WaitingForBrowser -> "Čeká se na dokončení v prohlížeči…"
            is WithingsUiState.Connected -> "Připojeno (uživatel ${s.userId})"
            is WithingsUiState.Failed -> "Přihlášení selhalo: ${s.message.lowercase().replace('_', ' ')} – začněte znovu (kód platí jen 30 s)"
        })
        Button(enabled = cfg?.first == true, onClick = { scope.launch { msg = withContext(Dispatchers.IO) { when (val r = sl.withings.runImport(System.currentTimeMillis())) {
            null -> "Přímý režim není připojen"; is AreaResult.Success -> "Withings: ${r.saved} záznamů"
            is AreaResult.NeedsUser -> "Vyžaduje vaši akci: ${r.action}"; is AreaResult.Deferred -> "Limit služby, zkusí se později"
            else -> "Import se nezdařil"
        } }; tick++ } }) { Text("Importovat teď") }
        SourceStatus("withings.", sourceStates)

        SectionTitle("Zdroj: CSV export z Withings")
        Text("Nouzová a referenční cesta. Jednotky se převedou na kg a %, nečitelné řádky se vypíšou s číslem řádku.", style = MaterialTheme.typography.bodySmall)
        Button({ csv.launch(arrayOf("text/*", "application/*")) }) { Text("Importovat CSV") }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun SourceStatus(prefix: String, states: List<app.pulse4.android.data.AreaStateRow>) {
    val row = states.firstOrNull { it.area == prefix + SyncArea.WEIGHT.name }
    val st = row?.let { AreaState(it.cursorMs, it.lastSuccessMs, it.lastAttemptMs, it.lastError, it.retryNotBeforeMs) } ?: AreaState()
    val status: AreaStatus = Freshness.statusOf(SyncArea.WEIGHT, st, System.currentTimeMillis())
    Text("${status.label()} · poslední úspěch ${fmt.time(st.lastSuccessMs)}" + (st.lastError?.let { " · chyba $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun MeasurementRow(o: MetricObservation, onWrong: () -> Unit, onConfirm: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.padding(8.dp)) {
            Text("${fmt.number(o.value, 1)} ${o.type.unit} · ${fmt.time(o.startUtcMs)}", style = MaterialTheme.typography.titleSmall)
            Text("Zdroj: ${o.provider.label()}" + (o.origin?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            if (o.quality == Quality.SUSPECT) Text("Nejednoznačné přiřazení osoby – v trendu jen když je zahrnete.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row {
                if (o.quality == Quality.SUSPECT) TextButton(onConfirm) { Text("Je moje") }
                TextButton(onWrong) { Text("Označit jako chybné") }
            }
        }
    }
}
