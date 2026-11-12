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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.ImeAction
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
 * Nativní přihlašovací formulář Withings: Jméno (e-mail) a heslo.
 * U účtů Google / Apple je zobrazena poznámka o jednorázovém vytvoření hesla přes web Withings.
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
        // E-mail účtu Withings
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("E-mail účtu Withings") },
            placeholder = { Text("např. jmeno@gmail.com") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
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
                Text("Vybrat e-mail z telefonu", style = MaterialTheme.typography.bodySmall)
            }
        }

        val doLogin: () -> Unit = {
            if (!busy && email.isNotBlank() && password.isNotBlank()) {
                busy = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        sl.withings.loginDirectPassword(email, password)
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
            }
        }

        // Heslo Withings
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Heslo Withings") },
            placeholder = { Text("Zadejte heslo k účtu Withings") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { doLogin() }),
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )

        // Důležitá poznámka pro účty Google / Apple
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1E2E)),
            border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Účty přihlašované přes Google nebo Apple",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        "Pokud k Withings běžně používáte přihlášení přes Google nebo Apple, je potřeba si k účtu jednorázově vytvořit heslo na webu Withings (přes volbu „Zapomenuté heslo“ odeslanou na váš Google e-mail). Heslo se bezpečně uloží v šifrovaném úložišti telefonu a zajistí trvalé automatické spojení bez odhlašování.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                }
            }
        }

        val canSubmit = !busy && email.isNotBlank() && password.isNotBlank()

        Button(
            enabled = canSubmit,
            onClick = doLogin,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
        ) {
            if (busy && !awaitingCode) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Přihlašuji se k Withings…", fontWeight = FontWeight.Bold)
            } else {
                Text("Přihlásit se heslem", fontWeight = FontWeight.Bold)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(Modifier.weight(1f), color = Color(0xFF334155))
            Text("  NEBO  ", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            HorizontalDivider(Modifier.weight(1f), color = Color(0xFF334155))
        }

        OutlinedButton(
            enabled = !busy && email.isNotBlank(),
            onClick = {
                busy = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        sl.withings.requestDirectEmailCode(email)
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
                        is WithingsLoginResult.Error -> onNotice(res.message)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
            border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.6f))
        ) {
            Icon(Icons.Outlined.Email, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Poslat 8místný kód na e-mail (Google / Apple)", fontWeight = FontWeight.SemiBold)
        }

        val doVerifyMfa: () -> Unit = {
            if (!busy && code.isNotBlank() && email.isNotBlank()) {
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
            }
        }

        // Pokud Withings vyžaduje 2FA kód (např. e-mail OTP nebo SMS)
        if (awaitingCode || code.isNotBlank()) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1928)),
                border = BorderStroke(1.dp, Color(0xFF06B6D4)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Ověřovací 2FA kód (zaslaný na e-mail nebo v SMS)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )

                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Ověřovací kód") },
                        placeholder = { Text("Vložte kód z e-mailu / SMS") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { doVerifyMfa() }),
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        enabled = !busy && code.isNotBlank() && email.isNotBlank(),
                        onClick = doVerifyMfa,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                    ) {
                        if (busy) {
                            CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Ověřuji kód…", fontWeight = FontWeight.Bold)
                        } else {
                            Text("Potvrdit ověřovací kód", fontWeight = FontWeight.Bold)
                        }
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
 * - [FullLogin]: Kompletní nové přihlášení s e-mailem a heslem.
 */
enum class WithingsDialogMode {
    Reconnect,
    SelectProfilesOnly,
    FullLogin,
}

/**
 * Přímý formulář obnovení relace Withings:
 * Umožňuje rychlé obnovení heslem nebo e-mailovým kódem bez ztráty nastavení profilů.
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
    val savedPass = sl.secrets.getString("withings.web.password").orEmpty()
    var password by remember { mutableStateOf(savedPass) }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(sl.withings.isAwaitingCode()) }
    var sendingCode by remember { mutableStateOf(false) }
    var verifyingCode by remember { mutableStateOf(false) }
    var loggingInByPassword by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Karta účtu
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
                        "Účet Withings",
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

        // Informační poznámka pro účty Google / Apple
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1E2E)),
            border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Účty přes Google nebo Apple",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        "Pokud k Withings používáte Google nebo Apple, vytvořte si k účtu heslo přes volbu „Zapomenuté heslo“ odeslanou na váš Google e-mail. Zadané heslo se bezpečně uloží do telefonu a zajistí trvalé automatické spojení.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                }
            }
        }

        val doPasswordLogin: () -> Unit = {
            if (!loggingInByPassword && !verifyingCode && !sendingCode && password.isNotBlank()) {
                loggingInByPassword = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        sl.withings.loginDirectPassword(savedEmail, password)
                    }
                    loggingInByPassword = false
                    when (res) {
                        is WithingsLoginResult.Ok -> onLoggedIn(res)
                        is WithingsLoginResult.NeedsMfa -> {
                            codeSent = true
                            onNotice(res.message)
                        }
                        is WithingsLoginResult.Error -> onNotice(res.message)
                    }
                }
            }
        }

        // Pole pro heslo
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Heslo Withings") },
            placeholder = { Text("Zadejte heslo k účtu") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { doPasswordLogin() }),
            singleLine = true,
            enabled = !loggingInByPassword && !verifyingCode && !sendingCode,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            enabled = !loggingInByPassword && !verifyingCode && !sendingCode && password.isNotBlank(),
            onClick = doPasswordLogin,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
        ) {
            if (loggingInByPassword) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Obnovuji spojení heslem…", fontWeight = FontWeight.Bold)
            } else {
                Text("Obnovit spojení heslem", fontWeight = FontWeight.Bold)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(Modifier.weight(1f), color = Color(0xFF334155))
            Text("  NEBO  ", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            HorizontalDivider(Modifier.weight(1f), color = Color(0xFF334155))
        }

        OutlinedButton(
            enabled = !loggingInByPassword && !verifyingCode && !sendingCode,
            onClick = {
                sendingCode = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        sl.withings.requestDirectEmailCode(savedEmail)
                    }
                    sendingCode = false
                    when (res) {
                        is WithingsLoginResult.Ok -> onLoggedIn(res)
                        is WithingsLoginResult.NeedsMfa -> {
                            codeSent = true
                            onNotice(res.message)
                        }
                        is WithingsLoginResult.Error -> onNotice(res.message)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
            border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.6f))
        ) {
            if (sendingCode) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Odesílám kód na e-mail…", fontWeight = FontWeight.SemiBold)
            } else {
                Icon(Icons.Outlined.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Poslat 8místný kód na e-mail (Google / Apple)", fontWeight = FontWeight.SemiBold)
            }
        }

        // Pokud uživatel ještě heslo nemá nebo server vyžádal 2FA kód
        if (codeSent || code.isNotBlank()) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1928)),
                border = BorderStroke(1.dp, Color(0xFF06B6D4)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Ověřovací 2FA kód z e-mailu / SMS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )

                    val doVerifyCode: () -> Unit = {
                        if (!verifyingCode && code.length >= 6) {
                            verifyingCode = true
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    sl.withings.resumeDirectMfa(code, emailFallback = savedEmail)
                                }
                                verifyingCode = false
                                when (res) {
                                    is WithingsLoginResult.Ok -> onLoggedIn(res)
                                    is WithingsLoginResult.NeedsMfa -> onNotice(res.message)
                                    is WithingsLoginResult.Error -> onNotice(res.message)
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = code,
                        onValueChange = { input ->
                            val digits = input.filter { it.isDigit() }.take(8)
                            code = digits
                        },
                        label = { Text("8místný kód z e-mailu / 2FA") },
                        placeholder = { Text("Vložte kód z e-mailu") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { doVerifyCode() }),
                        singleLine = true,
                        enabled = !verifyingCode,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        enabled = !verifyingCode && code.length >= 6,
                        onClick = doVerifyCode,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                    ) {
                        if (verifyingCode) {
                            CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Ověřuji kód u Withings…", fontWeight = FontWeight.Bold)
                        } else {
                            Text("Potvrdit kód a obnovit", fontWeight = FontWeight.Bold, color = Color.White)
                        }
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
                                is NativeDialogStage.ReconnectForm -> "Obnovení spojení Withings"
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
                                    val primary = existingSession?.primaryUserId?.let { pid -> ok.profiles.firstOrNull { it.id == pid } }
                                        ?: ok.profiles.firstOrNull { it.isPrimary }
                                        ?: ok.profiles.first()
                                    onSuccess(primary)
                                    onDismiss()
                                },
                                onNotice = { notice = it }
                            )
                        }

                        is NativeDialogStage.LoginForm -> {
                            WithingsNativeLoginForm(
                                sl = sl,
                                onLoggedIn = { ok ->
                                    notice = null
                                    if (existingSession != null) {
                                        // Pokud již profil známe (např. Pepe), zachováme ho a dialog ihned zavřeme:
                                        val primary = existingSession.primaryUserId.let { pid -> ok.profiles.firstOrNull { it.id == pid } }
                                            ?: ok.profiles.firstOrNull { it.isPrimary }
                                            ?: ok.profiles.first()
                                        onSuccess(primary)
                                        onDismiss()
                                    } else if (ok.profiles.size > 1) {
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
