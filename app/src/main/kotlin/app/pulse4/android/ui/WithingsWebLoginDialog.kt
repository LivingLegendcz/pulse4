package app.pulse4.android.ui

import android.accounts.AccountManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.di.WithingsLoginResult
import app.pulse4.withings.WithingsUserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nativní přihlašovací formulář Withings běžící 100% na pozadí (bez WebView).
 * Podporuje:
 * 1. Účty vedené přes Google / Apple nebo bez hesla (přihlášení 8místným kódem zaslaným na e-mail).
 * 2. Klasické přihlášení e-mailem a heslem Withings (včetně případného 2FA kódu).
 */
@Composable
fun WithingsNativeLoginForm(
    sl: ServiceLocator,
    onLoggedIn: (WithingsLoginResult.Ok) -> Unit,
    onNotice: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val savedEmail = sl.secrets.getString("withings.web.email") ?: sl.withings.loadWebSession()?.email ?: sl.withings.pendingEmail()
    val savedPass = sl.secrets.getString("withings.web.password")
    var authMode by rememberSaveable { mutableStateOf(if (!savedPass.isNullOrBlank()) 1 else 0) }
    var email by rememberSaveable { mutableStateOf(savedEmail ?: "pepe.kocourek@gmail.com") }
    var password by remember { mutableStateOf(savedPass ?: "") }
    var code by rememberSaveable { mutableStateOf("") }
    var awaitingCode by rememberSaveable { mutableStateOf(sl.withings.isAwaitingCode()) }
    var busy by remember { mutableStateOf(false) }

    val accountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pickedName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (!pickedName.isNullOrBlank()) {
            email = pickedName
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Přepínač způsobu přihlášení: Google/Apple/Kód vs Heslo
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = authMode == 0,
                onClick = { authMode = 0 },
                label = {
                    Text(
                        "Google / Apple / Kód",
                        maxLines = 1,
                        softWrap = false,
                        fontWeight = if (authMode == 0) FontWeight.Bold else FontWeight.Normal
                    )
                },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = authMode == 1,
                onClick = { authMode = 1 },
                label = {
                    Text(
                        "E-mail + Heslo",
                        maxLines = 1,
                        softWrap = false,
                        fontWeight = if (authMode == 1) FontWeight.Bold else FontWeight.Normal
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }

        if (authMode == 0) {
            Text(
                "Pro účty propojené s Google nebo Apple (nebo bez hesla): klikněte na „1. Poslat 8místný kód na e-mail“ a doručený kód vložte do pole „8místný ověřovací kód“ níže.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCBD5E1)
            )
        } else {
            Text(
                "Přihlášení přímo na pozadí pomocí e-mailu a hesla vašeho účtu Withings.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCBD5E1)
            )
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(if (authMode == 0) "E-mail účtu (Google / Apple / Withings)" else "E-mail") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )

        TextButton(
            enabled = !busy,
            onClick = {
                runCatching {
                    accountPickerLauncher.launch(
                        AccountManager.newChooseAccountIntent(null, null, null, null, null, null, null)
                    )
                }.onFailure { onNotice("Výběr účtu z telefonu nelze otevřít.") }
            }
        ) {
            Text("Vybrat Google / e-mailový účet z telefonu", maxLines = 1, softWrap = false)
        }

        if (authMode == 1) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Heslo Withings") },
                supportingText = { Text("Může doplnit Bitwarden / správce hesel") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
        }

        val canSendInitial = !busy && if (authMode == 0) email.isNotBlank() else (email.isNotBlank() && password.isNotBlank())

        OutlinedButton(
            enabled = canSendInitial,
            onClick = {
                busy = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        if (authMode == 0) {
                            sl.withings.requestDirectEmailCode(email)
                        } else {
                            sl.withings.loginDirectPassword(email, password)
                        }
                    }
                    busy = false
                    when (res) {
                        is WithingsLoginResult.Ok -> {
                            password = ""
                            code = ""
                            awaitingCode = false
                            onLoggedIn(res)
                        }
                        is WithingsLoginResult.NeedsMfa -> {
                            awaitingCode = true
                            onNotice(res.message)
                        }
                        is WithingsLoginResult.Error -> {
                            onNotice(res.message)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFF38BDF8))
        ) {
            val sendLabel = when {
                authMode == 1 -> "Přihlásit heslem (nebo vyžádat 2FA kód)"
                awaitingCode -> "Poslat nový 8místný kód na e-mail"
                else -> "1. Poslat 8místný kód na e-mail"
            }
            Text(sendLabel, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
        }

        // Pole pro 8místný ověřovací kód je VŽDY viditelné, aby uživatel mohl kód kdykoli vložit ze schránky
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (awaitingCode || code.isNotBlank()) Color(0xFF0F2E3E) else Color(0xFF0B1522)
            ),
            border = BorderStroke(
                1.dp,
                if (awaitingCode || code.isNotBlank()) Color(0xFF06B6D4) else app.pulse4.android.ui.theme.MineralOutlineDark
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "2. Zadání 8místného ověřovacího kódu z e-mailu",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8)
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("8místný ověřovací kód z e-mailu / 2FA") },
                    placeholder = { Text("Vložte 8místný kód z e-mailu") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    enabled = !busy && code.isNotBlank() && email.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                sl.withings.resumeDirectMfa(code, emailFallback = email)
                            }
                            busy = false
                            when (res) {
                                is WithingsLoginResult.Ok -> {
                                    password = ""
                                    code = ""
                                    awaitingCode = false
                                    onLoggedIn(res)
                                }
                                is WithingsLoginResult.NeedsMfa -> {
                                    awaitingCode = true
                                    onNotice(res.message)
                                }
                                is WithingsLoginResult.Error -> {
                                    onNotice(res.message)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Ověřuji kód u Withings…", fontWeight = FontWeight.Bold)
                    } else {
                        Text("Potvrdit kód a přihlásit", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Režimy zobrazení dialogu Withings:
 * - [Reconnect]: Přímé rychlé obnovení relace bez jakéhokoli výběru účtů / profilů (zachovává stávající profil Pepe a dětský profil).
 * - [SelectProfilesOnly]: Pouze přepnutí profilů (hlavní / dětský) v existující platné relaci (z Nastavení -> Změnit profil).
 * - [FullLogin]: Kompletní nové přihlášení s možností přepnutí mezi Google/Apple a heslem.
 */
enum class WithingsDialogMode {
    Reconnect,
    SelectProfilesOnly,
    FullLogin,
}

/**
 * Přímý formulář obnovení relace pro Google SSO účet (nebo existující Withings účet).
 * Nezobrazuje výběr profilů ani systémový account picker – řeší čistě znovupřipojení.
 */
@Composable
fun WithingsReconnectForm(
    sl: ServiceLocator,
    onLoggedIn: (WithingsLoginResult.Ok) -> Unit,
    onNotice: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val savedEmail = sl.secrets.getString("withings.web.email")
        ?: sl.withings.loadWebSession()?.email
        ?: sl.withings.pendingEmail()
        ?: "pepe.kocourek@gmail.com"
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(sl.withings.isAwaitingCode()) }
    var sendingCode by remember { mutableStateOf(false) }
    var verifyingCode by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Karta účtu Google SSO
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2E3E)),
            border = BorderStroke(1.dp, Color(0xFF06B6D4)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Withings (Google SSO)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        savedEmail,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "Původní nastavení profilů zůstane zachováno",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }

        // Tlačítko pro vyžádání 8místného kódu na e-mail
        Button(
            enabled = !sendingCode && !verifyingCode,
            onClick = {
                sendingCode = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        sl.withings.requestDirectEmailCode(savedEmail)
                    }
                    sendingCode = false
                    when (res) {
                        is WithingsLoginResult.NeedsMfa -> {
                            codeSent = true
                            onNotice("8místný ověřovací kód byl odeslán na $savedEmail.")
                        }
                        is WithingsLoginResult.Ok -> {
                            onLoggedIn(res)
                        }
                        is WithingsLoginResult.Error -> {
                            onNotice(res.message)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (codeSent) Color(0xFF1E293B) else Color(0xFF0284C7)
            ),
            shape = RoundedCornerShape(12.dp),
            border = if (codeSent) BorderStroke(1.dp, Color(0xFF38BDF8)) else null
        ) {
            if (sendingCode) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Odesílám kód…", fontWeight = FontWeight.Bold)
            } else {
                Text(
                    if (codeSent) "Poslat nový kód na e-mail" else "1. Poslat 8místný kód na e-mail",
                    fontWeight = FontWeight.Bold,
                    color = if (codeSent) Color(0xFF38BDF8) else Color.White
                )
            }
        }

        // Zadání 8místného kódu
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (codeSent || code.isNotBlank()) Color(0xFF0B1928) else Color(0xFF0B1522)
            ),
            border = BorderStroke(
                1.dp,
                if (codeSent || code.isNotBlank()) Color(0xFF0284C7) else app.pulse4.android.ui.theme.MineralOutlineDark
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "2. Zadejte 8místný kód z e-mailu",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8)
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(8)
                        code = digits
                    },
                    label = { Text("8místný kód z e-mailu") },
                    placeholder = { Text("Vložte kód z e-mailu") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !verifyingCode,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    enabled = !verifyingCode && code.length >= 6,
                    onClick = {
                        verifyingCode = true
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                sl.withings.resumeDirectMfa(code, emailFallback = savedEmail)
                            }
                            verifyingCode = false
                            when (res) {
                                is WithingsLoginResult.Ok -> {
                                    onLoggedIn(res)
                                }
                                is WithingsLoginResult.NeedsMfa -> {
                                    onNotice(res.message)
                                }
                                is WithingsLoginResult.Error -> {
                                    onNotice(res.message)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    if (verifyingCode) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Ověřuji kód u Withings…", fontWeight = FontWeight.Bold)
                    } else {
                        Text("Obnovit spojení", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

private sealed interface NativeDialogStage {
    data object ReconnectForm : NativeDialogStage
    data object LoginForm : NativeDialogStage
    data object LoadingProfiles : NativeDialogStage
    data class SelectProfiles(
        val cookies: String,
        val sessionToken: String?,
        val profiles: List<WithingsUserProfile>,
    ) : NativeDialogStage
    data class Error(val message: String) : NativeDialogStage
}

/**
 * Nativní dialog pro přihlášení k Withings a výběr hlavního i dětského profilu.
 * Nepoužívá žádný WebView – veškerá komunikace probíhá na pozadí přes OkHttp.
 */
@Composable
fun WithingsWebLoginDialog(
    sl: ServiceLocator,
    mode: WithingsDialogMode = WithingsDialogMode.FullLogin,
    onDismiss: () -> Unit,
    onSuccess: (WithingsUserProfile) -> Unit
) {
    val scope = rememberCoroutineScope()
    val existingSession = remember { sl.withings.loadWebSession() }
    var notice by remember { mutableStateOf<String?>(null) }
    var stage by remember {
        mutableStateOf<NativeDialogStage>(
            when (mode) {
                WithingsDialogMode.Reconnect -> NativeDialogStage.ReconnectForm
                WithingsDialogMode.SelectProfilesOnly -> {
                    if (existingSession != null && existingSession.allProfiles.isNotEmpty()) {
                        NativeDialogStage.SelectProfiles(
                            cookies = existingSession.cookies,
                            sessionToken = existingSession.sessionToken,
                            profiles = existingSession.allProfiles
                        )
                    } else if (existingSession != null && existingSession.isConnected()) {
                        NativeDialogStage.LoadingProfiles
                    } else {
                        NativeDialogStage.LoginForm
                    }
                }
                WithingsDialogMode.FullLogin -> NativeDialogStage.LoginForm
            }
        )
    }

    LaunchedEffect(mode) {
        if (stage is NativeDialogStage.LoadingProfiles && existingSession != null) {
            try {
                val profiles = withContext(Dispatchers.IO) {
                    sl.withings.verifyWebCookies(existingSession.cookies, existingSession.sessionToken)
                }
                stage = if (profiles.isNotEmpty()) {
                    NativeDialogStage.SelectProfiles(existingSession.cookies, existingSession.sessionToken, profiles)
                } else {
                    NativeDialogStage.LoginForm
                }
            } catch (e: Exception) {
                stage = NativeDialogStage.Error(e.message ?: "Nepodařilo se načíst profily z Withings.")
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(20.dp)),
            color = app.pulse4.android.ui.theme.MineralSurfaceDark,
            border = BorderStroke(1.dp, app.pulse4.android.ui.theme.MineralOutlineDark)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Horní lišta dialogu
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(app.pulse4.android.ui.theme.MineralBackgroundDark)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (stage) {
                                is NativeDialogStage.SelectProfiles -> "Výběr profilu Withings"
                                is NativeDialogStage.ReconnectForm -> "Obnovení Withings (Google SSO)"
                                else -> "Přihlášení k Withings"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Zabezpečeno",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Přímé spojení na pozadí · Keystore šifrování",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF38BDF8),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Zavřít",
                            tint = Color(0xFF94A3B8)
                        )
                    }
                }

                Column(Modifier.padding(16.dp)) {
                    notice?.let { msg ->
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .clickable { notice = null },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Text(msg, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    when (val cur = stage) {
                        is NativeDialogStage.ReconnectForm -> {
                            WithingsReconnectForm(
                                sl = sl,
                                onLoggedIn = { ok ->
                                    notice = null
                                    // Automaticky zachovat stávající primární i dětský profil bez otravného dotazování
                                    val primary = existingSession?.primaryUserId?.let { pid -> ok.profiles.firstOrNull { it.id == pid } }
                                        ?: ok.profiles.firstOrNull { it.isPrimary }
                                        ?: ok.profiles.first()
                                    val child = existingSession?.childUserId?.let { cid -> ok.profiles.firstOrNull { it.id == cid } }
                                    val em = existingSession?.email ?: sl.secrets.getString("withings.web.email")
                                    scope.launch(Dispatchers.IO) {
                                        sl.withings.completeWebLogin(
                                            cookies = ok.session.cookies,
                                            primary = primary,
                                            child = child,
                                            sessionToken = ok.session.sessionToken,
                                            allProfiles = ok.profiles,
                                            email = em
                                        )
                                        withContext(Dispatchers.Main) {
                                            onSuccess(primary)
                                            onDismiss()
                                        }
                                    }
                                },
                                onNotice = { notice = it }
                            )
                        }

                        is NativeDialogStage.LoginForm -> {
                            WithingsNativeLoginForm(
                                sl = sl,
                                onLoggedIn = { ok ->
                                    notice = null
                                    if (ok.profiles.size > 1) {
                                        stage = NativeDialogStage.SelectProfiles(
                                            cookies = ok.session.cookies,
                                            sessionToken = ok.session.sessionToken,
                                            profiles = ok.profiles
                                        )
                                    } else {
                                        val primary = ok.profiles.first()
                                        onSuccess(primary)
                                        onDismiss()
                                    }
                                },
                                onNotice = { notice = it }
                            )
                        }

                        is NativeDialogStage.LoadingProfiles -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 3.dp,
                                    color = Color(0xFF38BDF8),
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "Načítám profily z účtu Withings…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        is NativeDialogStage.SelectProfiles -> {
                            var selectedPrimaryId by remember {
                                mutableStateOf(
                                    existingSession?.primaryUserId?.takeIf { id -> cur.profiles.any { it.id == id } }
                                        ?: cur.profiles.firstOrNull { it.isPrimary }?.id
                                        ?: cur.profiles.first().id
                                )
                            }
                            var selectedChildId by remember {
                                mutableStateOf<Long?>(
                                    existingSession?.childUserId?.takeIf { id -> id != selectedPrimaryId && cur.profiles.any { it.id == id } }
                                        ?: cur.profiles.firstOrNull { it.id != selectedPrimaryId && !it.isPrimary }?.id
                                )
                            }
                            var saving by remember { mutableStateOf(false) }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Účet Withings připojen",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF10B981)
                                    )
                                    Text(
                                        "Nalezeno ${cur.profiles.size} profilů na účtu.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        notice = null
                                        stage = NativeDialogStage.LoginForm
                                    }
                                ) {
                                    Text("Jiný účet", style = MaterialTheme.typography.labelSmall, color = Color(0xFF38BDF8))
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            Text(
                                "1. Váš hlavní profil (měření pro tento telefon):",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(Modifier.height(6.dp))

                            cur.profiles.forEach { p ->
                                val isSelected = p.id == selectedPrimaryId
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            selectedPrimaryId = p.id
                                            if (selectedChildId == p.id) selectedChildId = null
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) Color(0xFF0F2E3E) else app.pulse4.android.ui.theme.MineralBackgroundDark
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) Color(0xFF06B6D4) else app.pulse4.android.ui.theme.MineralOutlineDark
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                selectedPrimaryId = p.id
                                                if (selectedChildId == p.id) selectedChildId = null
                                            },
                                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF06B6D4))
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                p.displayName + if (p.isPrimary) " (Hlavní na účtu)" else "",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Text(
                                                "ID profilu: ${p.id}" + (p.shortName.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                }
                            }

                            if (cur.profiles.size > 1) {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "2. Dětský profil pro automatické P2P přeposílání do telefonu dítěte (volitelné):",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(Modifier.height(6.dp))

                                val candidateChildren = cur.profiles.filter { it.id != selectedPrimaryId }
                                val isNone = selectedChildId == null
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clickable { selectedChildId = null },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isNone) Color(0xFF1E293B) else app.pulse4.android.ui.theme.MineralBackgroundDark
                                    ),
                                    border = BorderStroke(1.dp, if (isNone) Color(0xFF38BDF8) else app.pulse4.android.ui.theme.MineralOutlineDark)
                                ) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = isNone,
                                            onClick = { selectedChildId = null },
                                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF38BDF8))
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Žádný (pouze můj profil)", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                                    }
                                }

                                candidateChildren.forEach { cp ->
                                    val isChild = selectedChildId == cp.id
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .clickable { selectedChildId = cp.id },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isChild) Color(0xFF1E293B) else app.pulse4.android.ui.theme.MineralBackgroundDark
                                        ),
                                        border = BorderStroke(1.dp, if (isChild) Color(0xFF38BDF8) else app.pulse4.android.ui.theme.MineralOutlineDark)
                                    ) {
                                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(
                                                selected = isChild,
                                                onClick = { selectedChildId = cp.id },
                                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF38BDF8))
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text(cp.displayName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Color.White)
                                                Text("ID profilu: ${cp.id}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            Button(
                                enabled = !saving,
                                onClick = {
                                    saving = true
                                    val primary = cur.profiles.first { it.id == selectedPrimaryId }
                                    val child = cur.profiles.firstOrNull { it.id == selectedChildId }
                                    scope.launch(Dispatchers.IO) {
                                        sl.withings.completeWebLogin(
                                            cookies = cur.cookies,
                                            primary = primary,
                                            child = child,
                                            sessionToken = cur.sessionToken,
                                            allProfiles = cur.profiles
                                        )
                                        withContext(Dispatchers.Main) {
                                            saving = false
                                            onSuccess(primary)
                                            onDismiss()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                if (saving) {
                                    CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Stahuji historii měření…", fontWeight = FontWeight.Bold)
                                } else {
                                    Text("Uložit profily a synchronizovat data", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        is NativeDialogStage.Error -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "Chyba při komunikaci s Withings",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    cur.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF94A3B8)
                                )
                                Spacer(Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = onDismiss) {
                                        Text("Zavřít")
                                    }
                                    Button(onClick = { stage = NativeDialogStage.LoginForm }) {
                                        Text("Přihlásit znovu")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
