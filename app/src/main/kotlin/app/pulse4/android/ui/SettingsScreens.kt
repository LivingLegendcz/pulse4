@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.reticulum.LoginResult
import app.pulse4.android.security.SecretStore
import app.pulse4.android.work.Scheduler
import app.pulse4.backup.BackupException
import app.pulse4.provider.AreaState
import app.pulse4.ui.Status
import app.pulse4.provider.ProviderError
import app.pulse4.provider.SyncArea
import app.pulse4.sharing.PrivacyZone
import app.pulse4.sync.DeliveryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConnectionsScreen(sl: ServiceLocator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by sl.db.areaStates().all().collectAsState(emptyList())
    val outbox by sl.db.outbox().recent().collectAsState(emptyList())
    var apiKey by remember { mutableStateOf("") }
    var keyDays by remember { mutableStateOf(90) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mfa by remember { mutableStateOf("") }
    var needMfa by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    val now = System.currentTimeMillis()

    val settings by io(tick) { Triple(sl.settings.networkMode, sl.settings.gatewayHost to sl.settings.gatewayPort, sl.settings.propagationNode) }
    var host by remember(settings) { mutableStateOf(settings?.second?.first ?: "") }
    var port by remember(settings) { mutableStateOf(settings?.second?.second?.toString() ?: "") }
    var node by remember(settings) { mutableStateOf(settings?.third ?: "") }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Připojení", style = MaterialTheme.typography.titleLarge)

        SectionTitle("Stav synchronizace")
        val o = Status.overview(states.associate { it.area to AreaState(it.cursorMs, it.lastSuccessMs, it.lastAttemptMs, it.lastError, it.retryNotBeforeMs) }, now)
        // „Vše aktuální“ jen tehdy, když je každá oblast v pořádku; chyba spánku nesmí vytvořit zelený stav.
        Text(if (o.allFresh) "Vše aktuální" else "Něco není aktuální", style = MaterialTheme.typography.titleMedium)
        Text("Poslední úspěšný import: ${fmt.time(o.lastSuccessMs)}")
        o.garmin.forEach { r ->
            LabeledRow(r.label, r.status.label())
            Text("poslední pokus ${fmt.time(r.lastAttemptMs)}" + (r.error?.let { e -> " · chyba: $e" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
        Text("Váha", style = MaterialTheme.typography.labelLarge)
        o.weightSources.forEach { r -> LabeledRow(r.label, r.status.label()) }
        Row {
            Button({ Scheduler.importNow(ctx); Scheduler.shareNow(ctx, manual = true); msg = "Synchronizace spuštěna (výsledek uvidíte ve stavu)" }) { Text("Synchronizovat nyní") }
            Button({ Scheduler.importNow(ctx) }, Modifier.padding(start = 8.dp)) { Text("Jen Garmin") }
            Button({ Scheduler.shareNow(ctx, true) }, Modifier.padding(start = 8.dp)) { Text("Jen kontakty") }
        }
        Text("Tlačítko nesynchronizuje hodinky do Garmin cloudu – k tomu otevřete Garmin Connect. Neobejde limity Garminu, nedostupného příjemce ani uspání druhého telefonu.", style = MaterialTheme.typography.bodySmall)
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        SectionTitle("Garmin – osobní API klíč")
        Text("Klíč patří jen vám a zůstává šifrovaně v tomto telefonu. Nesdílejte jej ani nevkládejte jinam.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("Osobní API klíč") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row { listOf(30, 90, 180, 365).forEach { d -> FilterChip(keyDays == d, { keyDays = d }, { Text("$d dní") }, Modifier.padding(2.dp)) } }
        Button(enabled = apiKey.isNotBlank(), onClick = {
            scope.launch(Dispatchers.IO) {
                val trimmed = apiKey.trim().removePrefix("ApiKey ").trim()
                if (trimmed.startsWith("W3") || (trimmed.startsWith("[") && trimmed.contains("oauth_token"))) {
                    sl.garminExt.parseTokens(trimmed)
                    sl.secrets.delete(SecretStore.GARMIN_API_KEY)
                    sl.secrets.delete(SecretStore.GARMIN_API_KEY_EXPIRES)
                } else {
                    sl.secrets.putString(SecretStore.GARMIN_API_KEY, trimmed)
                    sl.secrets.putString(SecretStore.GARMIN_API_KEY_EXPIRES, (System.currentTimeMillis() + keyDays * 86_400_000L).toString())
                }
                apiKey = ""
            }
        }) { Text("Uložit klíč") }
        val expires by io(tick + states.size) { sl.secrets.getString(SecretStore.GARMIN_API_KEY_EXPIRES)?.toLongOrNull() }
        Status.keyExpiry(expires, now)?.let {
            Text(it.text, color = if (it.warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }

        SectionTitle("Garmin – zápis tréninků (neoficiální doplněk)")
        Text("Samostatné přihlášení. Heslo se neukládá, uloží se jen šifrované tokeny. Výpadek tohoto přihlášení neblokuje oficiální čtení.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Heslo") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (needMfa) OutlinedTextField(mfa, { mfa = it }, label = { Text("MFA kód") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Button({
            scope.launch {
                msg = withContext(Dispatchers.IO) {
                    try {
                        val r = if (needMfa) sl.garminExt.resumeMfa(mfa) else sl.garminExt.loginPassword(email, password)
                        when (r) { LoginResult.Ok -> { password = ""; mfa = ""; needMfa = false; "Přihlášeno" }; LoginResult.NeedsMfa -> { needMfa = true; "Zadejte MFA kód" } }
                    } catch (e: ProviderError) { "Přihlášení selhalo: ${e.message}" } catch (e: Exception) { "Přihlášení selhalo" }
                }
                tick++
            }
        }) { Text(if (needMfa) "Dokončit MFA" else "Přihlásit") }

        SectionTitle("Síť")
        Row { listOf(NetworkMode.AUTO to "Automaticky", NetworkMode.LOCAL_ONLY to "Pouze místní síť", NetworkMode.MANUAL to "Ručně").forEach { (m, l) ->
            FilterChip(settings?.first == m, { scope.launch(Dispatchers.IO) { sl.settings.networkMode = m; tick++ } }, { Text(l) }, Modifier.padding(2.dp)) } }
        Text("Přednastavená gateway: ${app.pulse4.android.data.Settings.GATEWAY_DEFAULT_NAME}. Host i port lze změnit; aplikace nezávisí na jediném uzlu. Změna rozhraní se projeví po restartu aplikace.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(host, { host = it }, label = { Text("Gateway host") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(node, { node = it }, label = { Text("LXMF propagation uzel (hash destination)") }, modifier = Modifier.fillMaxWidth())
        Text("Adresa schránky je samostatná propagation destination; transport ID ani discovery hash gateway se sem nevkládá. Bez schránky se změny doručí jen při současné dostupnosti obou telefonů.", style = MaterialTheme.typography.bodySmall)
        Button({ scope.launch(Dispatchers.IO) { sl.settings.gatewayHost = host.trim(); port.toIntOrNull()?.let { sl.settings.gatewayPort = it }; sl.settings.propagationNode = node; runCatching { sl.bridge.applyPropagationNode() }; tick++ } }) { Text("Uložit síť") }

        SectionTitle("Odchozí fronta")
        if (outbox.isEmpty()) Text("Prázdná")
        outbox.take(30).forEach { o ->
            val line = Status.outboxLine(o.kind, "${o.objectType}/${o.objectId}", o.recipient, DeliveryState.valueOf(o.state), o.attempts, o.lastError)
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text(line.title, style = MaterialTheme.typography.bodyMedium)
                    Text(line.state + (line.detail?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun PrivacyScreen(sl: ServiceLocator) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val zones by io(tick) { sl.settings.privacyZones }
    val style by io(tick) { sl.settings.mapStyleUrl }
    var lat by remember { mutableStateOf("") }; var lon by remember { mutableStateOf("") }; var radius by remember { mutableStateOf("200") }
    var styleText by remember(style) { mutableStateOf(style ?: "") }
    var pass by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var pendingBackup by remember { mutableStateOf<ByteArray?>(null) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val data = pendingBackup
        if (uri != null && data != null) scope.launch(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }; msg = "Záloha uložena"; pendingBackup = null }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            msg = try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("nelze číst")
                val n = sl.backupService.restore(sl.backupService.open(bytes, pass.toCharArray()))
                "Obnoveno $n záznamů. Vytvořena nová identita – kontakty a sdílení potvrďte znovu."
            } catch (e: BackupException) { e.message } catch (e: Exception) { "Obnova selhala" }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Soukromí", style = MaterialTheme.typography.titleLarge)
        SectionTitle("Zóny soukromí (domov)")
        Text("Žádná souřadnice uvnitř zóny neopustí telefon ve sdílené trase, kolech ani metadatech. Původní FIT se při zóně nikdy nepřikládá.", style = MaterialTheme.typography.bodySmall)
        zones?.forEach { Text("${it.lat}, ${it.lon} · ${it.radiusM.toInt()} m") }
        Row { OutlinedTextField(lat, { lat = it }, Modifier.weight(1f), label = { Text("Šířka") }); OutlinedTextField(lon, { lon = it }, Modifier.weight(1f), label = { Text("Délka") }); OutlinedTextField(radius, { radius = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("m") }) }
        Button({
            val la = lat.toDoubleOrNull(); val lo = lon.toDoubleOrNull(); val r = radius.toDoubleOrNull()
            if (la == null || lo == null || r == null || la !in -90.0..90.0 || lo !in -180.0..180.0 || r < 50) msg = "Neplatné hodnoty (poloměr aspoň 50 m)"
            else scope.launch(Dispatchers.IO) { sl.settings.privacyZones = (zones ?: emptyList()) + PrivacyZone(la, lo, r); lat = ""; lon = ""; tick++ }
        }) { Text("Přidat zónu") }

        SectionTitle("Mapový podklad")
        Text("Prázdné = bez online map. Zadejte URL stylu poskytovatele, jehož licenci a atribuci splňujete; mapový poskytovatel může vidět, které oblasti si zobrazíte. Offline balíčky nesmí vznikat hromadným stahováním veřejných OSM dlaždic.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(styleText, { styleText = it }, label = { Text("URL stylu") }, modifier = Modifier.fillMaxWidth())
        Button({ scope.launch(Dispatchers.IO) { sl.settings.mapStyleUrl = styleText; tick++ } }) { Text("Uložit") }

        SectionTitle("Záloha a obnova")
        Text("Šifrovaný archiv s vlastními daty a plány. Tokeny, API klíč, tajemství a soukromá identita se nepřenášejí. Obnova vytvoří novou identitu.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(pass, { pass = it }, label = { Text("Heslo zálohy (min. 8 znaků)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row {
            Button(enabled = pass.length >= 8, onClick = { scope.launch(Dispatchers.IO) { pendingBackup = sl.backupService.export(pass.toCharArray()); create.launch("pulse4-zaloha.p4bk") } }) { Text("Zálohovat") }
            Button(enabled = pass.length >= 8, onClick = { open.launch(arrayOf("*/*")) }, modifier = Modifier.padding(start = 8.dp)) { Text("Obnovit") }
        }
        SectionTitle("Diagnostika")
        val diag by io(tick) {
            val o = sl.db.outbox().all()
            "Fronta: ${Status.queueSummary(o.map { DeliveryState.valueOf(it.state) })}\n" +
                SyncArea.entries.joinToString("\n") { a -> sl.db.areaStates().get(a.name).let { "${a.name}: poslední úspěch ${fmt.time(it?.lastSuccessMs)}, chyba ${it?.lastError ?: "—"}" } }
        }
        Text("Export diagnostiky vynechává zdravotní hodnoty, GPS, přihlašovací údaje i obsah zpráv.", style = MaterialTheme.typography.bodySmall)
        Text(diag ?: "…", style = MaterialTheme.typography.bodySmall)
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
