package app.pulse4.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.pulse4.android.data.NetworkMode
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.ui.theme.JetBrainsMonoFamily
import app.pulse4.sync.RnsNodeConfig
import app.pulse4.sync.RnsNodeQr
import app.pulse4.sync.RnsTransportType
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CuratedPublicNode(
    val name: String,
    val host: String,
    val port: Int,
    val transportLabel: String = "TCPClientInterface",
    val connectionType: String = "Veřejný TCP uzel",
)

enum class NodeReachability {
    UNVERIFIED,
    CHECKING,
    REACHABLE,
    UNREACHABLE,
}

val CURATED_PUBLIC_NODES: List<CuratedPublicNode> = listOf(
    CuratedPublicNode(
        name = "Dublin Connect (Reticulum)",
        host = "dublin.connect.reticulum.network",
        port = 4965,
    ),
    CuratedPublicNode(
        name = "BetweenTheBorders",
        host = "betweentheborders.com",
        port = 4242,
    ),
)

val COMMUNITY_NODE_DIRECTORIES: List<Pair<String, String>> = listOf(
    "Reticulum Map (rmap.world)" to "https://rmap.world",
    "RNS Community Directory" to "https://directory.rns.recipes",
)

@Composable
fun RnsOnboardingCard(
    sl: ServiceLocator,
    onCompleted: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showCustomForm by remember { mutableStateOf(false) }
    var customHost by remember { mutableStateOf("") }
    var customPort by remember { mutableStateOf("4242") }
    var customError by remember { mutableStateOf<String?>(null) }
    var showPublicNodes by remember { mutableStateOf(false) }
    var showQrImport by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Průvodce připojením k síti",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Pulse4 neobsahuje žádný pevně daný centrální server. Vyberte si, jak se chcete připojit ke svým kontaktům:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 1. Přidat vlastní uzel
            OutlinedButton(
                onClick = { showCustomForm = !showCustomForm },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("1. Přidat vlastní uzel")
            }

            if (showCustomForm) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customHost,
                            onValueChange = {
                                customHost = it
                                customError = null
                            },
                            label = { Text("Adresa / IP uzlu") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = customPort,
                            onValueChange = {
                                customPort = it.filter(Char::isDigit)
                                customError = null
                            },
                            label = { Text("Port (výchozí 4242)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        customError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Button(
                            onClick = {
                                val h = customHost.trim()
                                val p = customPort.toIntOrNull()
                                if (h.isBlank() || h.any { it.isWhitespace() } || "://" in h) {
                                    customError = "Zadejte platnou adresu nebo IP bez protokolu (např. uzel.example.cz)."
                                    return@Button
                                }
                                if (p == null || p !in 1..65535) {
                                    customError = "Port musí být v rozsahu 1–65535."
                                    return@Button
                                }
                                scope.launch(Dispatchers.IO) {
                                    val combined = "$h:$p"
                                    sl.settings.savedGatewayNodes = (sl.settings.savedGatewayNodes + combined).distinct()
                                    sl.settings.gatewayHost = h
                                    sl.settings.gatewayPort = p
                                    if (sl.settings.networkMode == NetworkMode.LOCAL_ONLY) {
                                        sl.settings.networkMode = NetworkMode.AUTO
                                    }
                                    sl.settings.rnsOnboardingCompleted = true
                                    runCatching { sl.bridge.applyPropagationNode() }
                                    withContext(Dispatchers.Main) {
                                        onFeedback("Vlastní uzel $combined byl uložen a aktivován.")
                                        onCompleted()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Uložit a použít uzel")
                        }
                    }
                }
            }

            // 2. Vybrat veřejný uzel
            OutlinedButton(
                onClick = { showPublicNodes = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("2. Vybrat veřejný uzel")
            }

            // 3. Načíst konfiguraci z QR kódu
            OutlinedButton(
                onClick = { showQrImport = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("3. Načíst konfiguraci z QR kódu")
            }

            // 4. Pokračovat bez vzdáleného uzlu
            TextButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        sl.settings.networkMode = NetworkMode.LOCAL_ONLY
                        sl.settings.gatewayHost = ""
                        sl.settings.rnsOnboardingCompleted = true
                        runCatching { sl.bridge.applyPropagationNode() }
                        withContext(Dispatchers.Main) {
                            onFeedback("Pokračujete v režimu Pouze místní Wi-Fi (bez vzdáleného uzlu).")
                            onCompleted()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("4. Pokračovat bez vzdáleného uzlu (jen místní Wi-Fi)")
            }
        }
    }

    if (showPublicNodes) {
        RnsPublicNodePickerDialog(
            sl = sl,
            onSelectNode = { host, port ->
                scope.launch(Dispatchers.IO) {
                    val combined = "$host:$port"
                    sl.settings.savedGatewayNodes = (sl.settings.savedGatewayNodes + combined).distinct()
                    sl.settings.gatewayHost = host
                    sl.settings.gatewayPort = port
                    if (sl.settings.networkMode == NetworkMode.LOCAL_ONLY) {
                        sl.settings.networkMode = NetworkMode.AUTO
                    }
                    sl.settings.rnsOnboardingCompleted = true
                    runCatching { sl.bridge.applyPropagationNode() }
                    withContext(Dispatchers.Main) {
                        showPublicNodes = false
                        onFeedback("Veřejný uzel $combined byl uložen a aktivován.")
                        onCompleted()
                    }
                }
            },
            onDismiss = { showPublicNodes = false }
        )
    }

    if (showQrImport) {
        RnsQrImportDialog(
            sl = sl,
            onImported = { cfg ->
                showQrImport = false
                onFeedback("Konfigurace uzlu ${cfg.name} (${cfg.host}:${cfg.port}) byla uložena.")
                onCompleted()
            },
            onDismiss = { showQrImport = false }
        )
    }
}

@Composable
fun RnsPublicNodePickerDialog(
    sl: ServiceLocator,
    onSelectNode: (host: String, port: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val reachabilityMap = remember { mutableStateMapOf<String, NodeReachability>() }
    var copiedUrl by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Veřejné komunitní uzly (infrastruktura třetích stran – bez záruky dostupnosti)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Tyto uzly provozuje komunita Reticulum. Pulse4 za jejich dostupnost neručí a žádný uzel nevybírá automaticky.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "Udržované komunitní katalogy:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                COMMUNITY_NODE_DIRECTORIES.forEach { (label, url) ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    runCatching {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    url,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFamily,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row {
                                IconButton(onClick = {
                                    clipboard.setText(AnnotatedString(url))
                                    copiedUrl = url
                                }) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Kopírovat odkaz", modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = {
                                    runCatching {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                }) {
                                    Icon(Icons.Outlined.OpenInBrowser, contentDescription = "Otevřít v prohlížeči", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                copiedUrl?.let {
                    Text("Odkaz $it zkopírován do schránky.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }

                HorizontalDivider()

                Text(
                    "Ukázkové komunitní uzly:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                CURATED_PUBLIC_NODES.forEach { node ->
                    val key = "${node.host}:${node.port}"
                    val status = reachabilityMap[key] ?: NodeReachability.UNVERIFIED
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(node.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                key,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetBrainsMonoFamily,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Transport: ${node.transportLabel} · ${node.connectionType}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            val (statusText, statusColor) = when (status) {
                                NodeReachability.UNVERIFIED -> "Stav: Neověřeno" to MaterialTheme.colorScheme.onSurfaceVariant
                                NodeReachability.CHECKING -> "Stav: Ověřuji dostupnost…" to MaterialTheme.colorScheme.primary
                                NodeReachability.REACHABLE -> "Stav: Dostupný" to MaterialTheme.colorScheme.primary
                                NodeReachability.UNREACHABLE -> "Stav: Nedostupný" to MaterialTheme.colorScheme.error
                            }
                            Text(statusText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = statusColor)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    enabled = status != NodeReachability.CHECKING,
                                    onClick = {
                                        reachabilityMap[key] = NodeReachability.CHECKING
                                        scope.launch(Dispatchers.IO) {
                                            val ok = sl.bridge.probeNodeReachability(node.host, node.port)
                                            withContext(Dispatchers.Main) {
                                                reachabilityMap[key] = if (ok) NodeReachability.REACHABLE else NodeReachability.UNREACHABLE
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (status == NodeReachability.CHECKING) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text("Ověřit dostupnost", maxLines = 1)
                                }
                                Button(
                                    onClick = { onSelectNode(node.host, node.port) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Vybrat", maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Zavřít")
            }
        }
    )
}

@Composable
fun RnsQrShareDialog(
    nodeName: String,
    host: String,
    port: Int,
    propagationNode: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    val cleanHost = host.trim()
    val cleanProp = propagationNode.trim().lowercase().ifBlank { null }
    val encodedResult = remember(nodeName, cleanHost, port, cleanProp) {
        runCatching {
            val cfg = RnsNodeConfig(
                name = nodeName.trim().ifBlank { cleanHost },
                host = cleanHost,
                port = port,
                transport = RnsTransportType.TCP_CLIENT,
                propagationNode = cleanProp,
            )
            RnsNodeQr.encode(cfg)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.QrCode2, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sdílet připojení", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                encodedResult.fold(
                    onSuccess = { payload ->
                        val bmp = remember(payload) { qrBitmap(payload, 560) }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "QR kód konfigurace uzlu",
                                modifier = Modifier
                                    .size(220.dp)
                                    .padding(8.dp)
                            )
                        }
                        Text(
                            "Uzel: $cleanHost:$port (TCPClientInterface)",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "QR kód obsahuje pouze veřejnou adresu uzlu pro připojení. Neobsahuje žádné soukromé klíče ani přístupové tokeny.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(payload))
                                copied = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (copied) "Konfigurace zkopírována" else "Kopírovat text konfigurace")
                        }
                    },
                    onFailure = {
                        Text(
                            "Nelze vygenerovat QR kód: nejprve nastavte platnou adresu a port připojovacího uzlu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Zavřít")
            }
        }
    )
}

@Composable
fun RnsQrImportDialog(
    sl: ServiceLocator,
    onImported: (RnsNodeConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rawText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var previewConfig by remember { mutableStateOf<RnsNodeConfig?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun validateRaw(input: String) {
        val result = RnsNodeQr.decodeAndValidate(input.trim())
        result.fold(
            onSuccess = { cfg ->
                previewConfig = cfg
                errorMsg = null
            },
            onFailure = { err ->
                previewConfig = null
                errorMsg = "Neplatná nebo nepovolená konfigurace QR kódu: ${err.message ?: "neznámá chyba"}"
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Načíst QR kód uzlu", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        GmsBarcodeScanning.getClient(ctx).startScan()
                            .addOnSuccessListener { barcode ->
                                val scanned = barcode.rawValue.orEmpty()
                                rawText = scanned
                                validateRaw(scanned)
                            }
                            .addOnFailureListener {
                                errorMsg = "Kamera není dostupná nebo skenování bylo zrušeno. Vložte prosím text konfigurace níže."
                            }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Naskenovat QR kód kamerou")
                }

                OutlinedTextField(
                    value = rawText,
                    onValueChange = {
                        rawText = it
                        previewConfig = null
                        errorMsg = null
                    },
                    label = { Text("Vložit text konfigurace") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    enabled = rawText.isNotBlank(),
                    onClick = { validateRaw(rawText) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Ověřit konfiguraci")
                }

                errorMsg?.let { err ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(8.dp))
                            Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }

                previewConfig?.let { cfg ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Náhled konfigurace uzlu", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Název: ${cfg.name}", style = MaterialTheme.typography.bodySmall)
                            Text("Adresa: ${cfg.host}", style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMonoFamily)
                            Text("Port: ${cfg.port}", style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMonoFamily)
                            Text(
                                "Typ připojení: ${if (cfg.transport == RnsTransportType.TCP_CLIENT) "TCPClientInterface" else cfg.transport.name}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Uzel pro doručování: ${cfg.propagationNode ?: "Nenastaveno"}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetBrainsMonoFamily
                            )
                            Spacer(Modifier.height(6.dp))
                            Button(
                                enabled = !saving,
                                onClick = {
                                    saving = true
                                    scope.launch(Dispatchers.IO) {
                                        val combined = "${cfg.host}:${cfg.port}"
                                        sl.settings.savedGatewayNodes = (sl.settings.savedGatewayNodes + combined).distinct()
                                        sl.settings.gatewayHost = cfg.host
                                        sl.settings.gatewayPort = cfg.port
                                        sl.settings.propagationNode = cfg.propagationNode.orEmpty()
                                        sl.settings.rnsOnboardingCompleted = true
                                        if (sl.settings.networkMode == NetworkMode.LOCAL_ONLY) {
                                            sl.settings.networkMode = NetworkMode.AUTO
                                        }
                                        runCatching { sl.bridge.applyPropagationNode() }
                                        withContext(Dispatchers.Main) {
                                            saving = false
                                            onImported(cfg)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Potvrdit a uložit")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Zavřít")
            }
        }
    )
}
