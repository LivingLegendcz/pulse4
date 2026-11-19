package app.pulse4.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.security.CorruptedSecretIssue
import app.pulse4.android.security.CriticalSecretKind
import app.pulse4.android.ui.theme.MineralOnSurfaceVariantDark
import app.pulse4.backup.BackupCodec
import app.pulse4.backup.BackupException
import app.pulse4.domain.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Akce dostupné na obrazovce obnovy podle povahy chyby (R3-8). */
internal data class SecretRecoveryActions(
    val showRetryOnly: Boolean,
    val allowBackupRestore: Boolean,
    val allowIdentityReset: Boolean,
    val allowDatabaseReset: Boolean,
)

internal fun resolveRecoveryActions(issue: CorruptedSecretIssue): SecretRecoveryActions =
    if (issue.recoverable) {
        SecretRecoveryActions(
            showRetryOnly = true,
            allowBackupRestore = false,
            allowIdentityReset = false,
            allowDatabaseReset = false,
        )
    } else {
        SecretRecoveryActions(
            showRetryOnly = false,
            allowBackupRestore = true,
            allowIdentityReset = issue.kind == CriticalSecretKind.RETICULUM_IDENTITY,
            allowDatabaseReset = issue.kind == CriticalSecretKind.DB_KEY,
        )
    }

@Composable
fun SecretRecoveryOverlay(
    issue: CorruptedSecretIssue,
    sl: ServiceLocator,
    onRecovered: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = remember(issue) { resolveRecoveryActions(issue) }
    var pass by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDestructiveReset by remember { mutableStateOf(false) }

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
                        resetIdentityToo = (issue.kind == CriticalSecretKind.RETICULUM_IDENTITY),
                    )
                    sl.backupService.restore(entries)
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
        val borderColor = if (actions.showRetryOnly) Color(0xFFF59E0B) else Color(0xFFEF4444)
        Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, borderColor.copy(alpha = 0.55f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (actions.showRetryOnly) {
                    Text(
                        "Úložiště klíčů je dočasně nedostupné",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFEF3C7),
                    )
                    Text(
                        "Android Keystore dočasně neodpověděl nebo vyžaduje odemčení zařízení " +
                            "(${issue.secretName}: ${issue.error}). Žádná data nebyla smazána — " +
                            "odemkněte telefon a zkuste to znovu.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MineralOnSurfaceVariantDark,
                    )
                    Button(
                        onClick = {
                            busy = true
                            statusMsg = null
                            scope.launch {
                                val nextIssue = withContext(Dispatchers.IO) {
                                    sl.vault.retryCriticalSecrets(sl.secrets)
                                }
                                if (nextIssue == null) {
                                    onRecovered()
                                } else {
                                    statusMsg = "Stále nedostupné (${nextIssue.error}). Zkuste to za chvíli znovu."
                                }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (busy) "Ověřuji…" else "Zkusit znovu")
                    }
                } else {
                    Text(
                        "Obnova poškozeného šifrovacího klíče",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFEE2E2),
                    )
                    Text(
                        if (issue.kind == CriticalSecretKind.DB_KEY) {
                            "Nepodařilo se dešifrovat klíč databáze (${issue.secretName}: ${issue.error}). " +
                                "Bez obnovy ze zálohy nelze původní šifrovaná data odemknout; vaše identita zůstane zachována."
                        } else {
                            "Nepodařilo se dešifrovat soukromou identitu Reticulum (${issue.secretName}: ${issue.error}). " +
                                "Můžete obnovit data ze zálohy nebo vytvořit novou identitu."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MineralOnSurfaceVariantDark,
                    )

                    if (actions.allowBackupRestore) {
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
                    }

                    if (actions.allowIdentityReset) {
                        OutlinedButton(
                            onClick = { confirmDestructiveReset = true },
                            enabled = !busy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFCA5A5)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Vytvořit novou identitu (ztráta párování)")
                        }
                    } else if (actions.allowDatabaseReset) {
                        OutlinedButton(
                            onClick = { confirmDestructiveReset = true },
                            enabled = !busy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFCA5A5)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Vytvořit novou databázi (zachová identitu)")
                        }
                    }
                }

                statusMsg?.let { msg ->
                    Spacer(Modifier.height(4.dp))
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFCA5A5))
                }
            }
        }
    }

    if (confirmDestructiveReset && !actions.showRetryOnly) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmDestructiveReset = false },
            title = {
                Text(
                    if (actions.allowIdentityReset) "Vytvořit novou identitu?"
                    else "Vytvořit novou databázi?"
                )
            },
            text = {
                Text(
                    if (actions.allowIdentityReset) {
                        "Vytvoření nové identity zruší stávající párování s kontakty a sdílení; bude nutné zařízení znovu spárovat."
                    } else {
                        "Vytvoření nového klíče databáze vymaže nepřístupnou databázi, ale zachová vaši síťovou identitu a ostatní nepoškozená tajemství."
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
                                        resetIdentityToo = (issue.kind == CriticalSecretKind.RETICULUM_IDENTITY),
                                    )
                                    onRecovered()
                                    null
                                }.getOrElse { e ->
                                    PLog.e("Recovery", e) { "Selective recovery reset failed" }
                                    "Obnova selhala: ${e.message ?: e::class.simpleName}"
                                }
                            }
                            if (errMsg != null) statusMsg = errMsg
                            confirmDestructiveReset = false
                            busy = false
                        }
                    },
                ) {
                    Text(
                        if (actions.allowIdentityReset) "Vytvořit novou identitu" else "Vytvořit novou databázi",
                        color = Color(0xFFFCA5A5),
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { confirmDestructiveReset = false }) {
                    Text("Zrušit")
                }
            },
        )
    }
}
