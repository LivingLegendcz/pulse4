@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.ui.theme.JetBrainsMonoFamily
import app.pulse4.sync.DeliveryState
import app.pulse4.ui.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReticulumScreen(sl: ServiceLocator) {
    val scope = rememberCoroutineScope()
    val outbox by sl.db.outbox().recent().collectAsState(emptyList())
    var tick by remember { mutableStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var showOnboarding by remember { mutableStateOf(false) }
    var showPublicNodes by remember { mutableStateOf(false) }
    var showQrShare by remember { mutableStateOf(false) }
    var showQrImport by remember { mutableStateOf(false) }

    val settingsState by io(tick) {
        listOf(
            sl.settings.networkMode.name,
            sl.settings.gatewayHost,
            sl.settings.gatewayPort.toString(),
            sl.settings.propagationNode,
            sl.settings.rnsOnboardingCompleted.toString(),
            sl.settings.rnsAdvancedMode.toString(),
        )
    }
    val myInfo by io(tick) {
        sl.bridge.ensureStarted()
    }

    var mode by remember(settingsState) {
        mutableStateOf(
            settingsState?.getOrNull(0)?.let { runCatching { NetworkMode.valueOf(it) }.getOrNull() }
                ?: NetworkMode.AUTO
        )
    }
    var host by remember(settingsState) { mutableStateOf(settingsState?.getOrNull(1) ?: "") }
    var port by remember(settingsState) { mutableStateOf(settingsState?.getOrNull(2) ?: "4242") }
    var propagationNode by remember(settingsState) { mutableStateOf(settingsState?.getOrNull(3) ?: "") }
    val onboardingCompleted = settingsState?.getOrNull(4)?.toBooleanStrictOrNull() ?: true
    var advancedMode by remember(settingsState) {
        mutableStateOf(settingsState?.getOrNull(5)?.toBooleanStrictOrNull() ?: false)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Nastavení sítě Reticulum", style = MaterialTheme.typography.titleLarge)
        Text(
            "Decentralizovaná výměna dat bez cloudu: po místní Wi-Fi síti nebo přes vámi vybraný připojovací uzel.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // Přepínač úrovně rozhraní: Běžný režim vs Pokročilý režim
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
            Spacer(Modifier.height(6.dp))
            RnsOnboardingCard(
                sl = sl,
                onCompleted = {
                    showOnboarding = false
                    tick++
                },
                onFeedback = { feedback = it }
            )
            Spacer(Modifier.height(8.dp))
        }

        feedback?.let {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(it, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (!advancedMode) {
            // ─── BĚŽNÝ REŽIM ───
            val activeNodeText = if (mode == NetworkMode.LOCAL_ONLY || host.isBlank()) {
                "Pouze místní Wi-Fi (bez vzdáleného uzlu)"
            } else {
                "$host:$port"
            }
            val statusBadge = when {
                mode == NetworkMode.LOCAL_ONLY || host.isBlank() -> "Lokální Wi-Fi"
                sl.bridge.isGatewayOnline -> "Připojeno"
                else -> "Nepřipojeno"
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Připojovací uzel", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            statusBadge,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(activeNodeText, style = MaterialTheme.typography.bodyMedium, fontFamily = JetBrainsMonoFamily)
                    Text(
                        "Uzel pro doručování zpráv: ${if (propagationNode.isBlank()) "Nenastaveno" else "Aktivní"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
            }

            val pendingCount = outbox.count {
                val st = runCatching { DeliveryState.valueOf(it.state) }.getOrNull()
                st == DeliveryState.QUEUED || st == DeliveryState.HANDED_TO_TRANSPORT || st == DeliveryState.STORED_IN_PROPAGATION
            }
            val deliveredCount = outbox.count {
                val st = runCatching { DeliveryState.valueOf(it.state) }.getOrNull()
                st == DeliveryState.ACKED
            }

            SectionTitle("Čekající zprávy")
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Čeká na odeslání: $pendingCount", style = MaterialTheme.typography.bodyMedium)
                    Text("Doručeno: $deliveredCount", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            SectionTitle("Moje síťová identita")
            myInfo?.let { info ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Ověřovací otisk:", style = MaterialTheme.typography.labelSmall)
                        Text(
                            app.pulse4.sync.Pairing.fingerprint(info.publicKeyB64),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            // ─── POKROČILÝ REŽIM ───
            SectionTitle("Režim transportní sítě")
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                listOf(
                    NetworkMode.AUTO to "AUTO",
                    NetworkMode.LOCAL_ONLY to "LOCAL_ONLY",
                    NetworkMode.MANUAL to "MANUAL"
                ).forEach { (m, label) ->
                    FilterChip(
                        selected = mode == m,
                        onClick = {
                            mode = m
                            scope.launch(Dispatchers.IO) {
                                sl.settings.networkMode = m
                                runCatching { sl.bridge.applyPropagationNode() }
                                tick++
                            }
                        },
                        label = { Text(label) },
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }

            SectionTitle("Propojovací uzel (TCPClientInterface)")
            FlowRow(
                modifier = Modifier.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { showPublicNodes = true }) {
                    Text("Veřejné komunitní uzly")
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
            }

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Gateway Host (IP nebo doména)") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("Port (výchozí 4242)") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            )
            OutlinedTextField(
                value = propagationNode,
                onValueChange = { propagationNode = it },
                label = { Text("LXMF propagation uzel (volitelná schránka)") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            )

            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val cleanHost = host.trim()
                        val cleanPort = port.toIntOrNull() ?: 4242
                        sl.settings.gatewayHost = cleanHost
                        sl.settings.gatewayPort = cleanPort
                        if (cleanHost.isNotBlank()) {
                            sl.settings.savedGatewayNodes = (sl.settings.savedGatewayNodes + "$cleanHost:$cleanPort").distinct()
                        }
                        sl.settings.propagationNode = propagationNode.trim().lowercase()
                        sl.settings.rnsOnboardingCompleted = true
                        runCatching { sl.bridge.applyPropagationNode() }
                        withContext(Dispatchers.Main) {
                            feedback = "Nastavení sítě uloženo. Připojení aktualizováno."
                            tick++
                        }
                    }
                },
                modifier = Modifier.padding(vertical = 6.dp)
            ) {
                Text("Uložit nastavení sítě")
            }

            SectionTitle("Diagnostika & Místní Wi-Fi (AutoInterface)")
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Stav AutoInterface: ", style = MaterialTheme.typography.titleSmall)
                        Text("Aktivní", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        "TCP Gateway: ${if (sl.bridge.isGatewayOnline) "ONLINE ($host:$port)" else "OFFLINE"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFamily
                    )
                }
            }

            SectionTitle("Moje identita na síti (deliveryHash)")
            myInfo?.let { info ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Adresa příjemce (Delivery Hash):", style = MaterialTheme.typography.labelSmall)
                        Text(info.deliveryHash, style = MaterialTheme.typography.bodyMedium, fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold)
                        Text("Otisk veřejného klíče:", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                        Text(app.pulse4.sync.Pairing.fingerprint(info.publicKeyB64), style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMonoFamily)
                    }
                }
            }

            SectionTitle("Odchozí fronta (${outbox.size})")
            if (outbox.isEmpty()) {
                Text("Fronta je prázdná.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                outbox.take(15).forEach { o ->
                    val line = Status.outboxLine(o.kind, "${o.objectType}/${o.objectId}", o.recipient, DeliveryState.valueOf(o.state), o.attempts, o.lastError)
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(line.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text(line.state + (line.detail?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        feedback = "Vybrán uzel $combined."
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
