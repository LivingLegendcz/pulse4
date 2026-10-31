@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ReticulumScreen(sl: ServiceLocator) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }

    val settingsTriple by io(tick) {
        Triple(sl.settings.networkMode, sl.settings.gatewayHost to sl.settings.gatewayPort, sl.settings.propagationNode)
    }
    val myInfo by io(tick) {
        sl.bridge.ensureStarted()
    }

    var mode by remember(settingsTriple) { mutableStateOf(settingsTriple?.first ?: NetworkMode.AUTO) }
    var host by remember(settingsTriple) { mutableStateOf(settingsTriple?.second?.first ?: "rns.xali.cz") }
    var port by remember(settingsTriple) { mutableStateOf(settingsTriple?.second?.second?.toString() ?: "4242") }
    var propagationNode by remember(settingsTriple) { mutableStateOf(settingsTriple?.third ?: "") }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Nastavení Reticulum (P2P sítě)", style = MaterialTheme.typography.titleLarge)
        Text(
            "Reticulum umožňuje decentralizovanou výměnu dat bez cloudu: přímo po místní Wi-Fi síti nebo přes nezávislé internetové brány.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        SectionTitle("Režim transportní sítě")
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            listOf(
                NetworkMode.AUTO to "🌐 Automaticky",
                NetworkMode.LOCAL_ONLY to "📶 Pouze Wi-Fi LAN",
                NetworkMode.MANUAL to "⚙️ Ruční"
            ).forEach { (m, label) ->
                FilterChip(
                    selected = mode == m,
                    onClick = {
                        mode = m
                        scope.launch(Dispatchers.IO) {
                            sl.settings.networkMode = m
                            tick++
                        }
                    },
                    label = { Text(label) },
                    modifier = Modifier.padding(2.dp)
                )
            }
        }

        SectionTitle("Veřejné brány v internetu")
        Text("Rychlý výběr přednastaveného uzlu:", style = MaterialTheme.typography.labelMedium)
        FlowRow(Modifier.padding(vertical = 4.dp)) {
            listOf(
                "rns.xali.cz" to 4242,
                "reticulum.betavine.net" to 4242,
                "192.168.0.184" to 4242,
                "10.0.2.2" to 4242
            ).forEach { (h, p) ->
                FilterChip(
                    selected = host == h && port == p.toString(),
                    onClick = {
                        host = h
                        port = p.toString()
                    },
                    label = { Text("$h:$p") },
                    modifier = Modifier.padding(2.dp)
                )
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
                    sl.settings.gatewayHost = host.trim()
                    port.toIntOrNull()?.let { sl.settings.gatewayPort = it }
                    sl.settings.propagationNode = propagationNode.trim().lowercase()
                    runCatching { sl.bridge.applyPropagationNode() }
                    feedback = "✅ Nastavení sítě uloženo. Připojení aktualizováno."
                    tick++
                }
            },
            modifier = Modifier.padding(vertical = 6.dp)
        ) {
            Text("Uložit nastavení sítě")
        }

        feedback?.let {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(it, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionTitle("Místní Wi-Fi (AutoInterface)")
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📶 Stav AutoInterface: ", style = MaterialTheme.typography.titleSmall)
                    Text("Aktivní", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "Telefony připojené na stejnou Wi-Fi síť se automaticky objeví bez nutnosti konfigurace a vyměňují si balíčky přímo mezi sebou.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        SectionTitle("Moje identita na síti")
        myInfo?.let { info ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Adresa příjemce (Delivery Hash):", style = MaterialTheme.typography.labelSmall)
                    Text(info.deliveryHash, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Otisk veřejného klíče:", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                    Text(app.pulse4.sync.Pairing.fingerprint(info.publicKeyB64), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
