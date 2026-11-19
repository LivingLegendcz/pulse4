package app.pulse4.android.ui

import android.accounts.AccountManager
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.di.WithingsLoginResult
import app.pulse4.withings.WithingsUserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Druh inline zpětné vazby pod tlačítkem přihlášení. */
internal enum class WithingsFeedbackKind { Progress, Info, Success, Error }

/** Inline výsledek / průběh přihlášení zobrazený přímo pod akčním tlačítkem. */
internal data class WithingsFeedback(val text: String, val kind: WithingsFeedbackKind)

internal const val WITHINGS_PASSWORD_FEEDBACK_TAG = "withings_password_feedback"
internal const val WITHINGS_CODE_FEEDBACK_TAG = "withings_code_feedback"
internal const val WITHINGS_CODE_FIELD_TAG = "withings_code_field"

internal const val WITHINGS_WAITING_TEXT = "Čekám na dokončení synchronizace…"

internal fun WithingsLoginResult.toFeedback(): WithingsFeedback = when (this) {
    is WithingsLoginResult.Ok -> WithingsFeedback("Přihlášeno k Withings. Stahuji data…", WithingsFeedbackKind.Success)
    is WithingsLoginResult.NeedsMfa -> WithingsFeedback(message, WithingsFeedbackKind.Info)
    is WithingsLoginResult.Error -> WithingsFeedback(message, WithingsFeedbackKind.Error)
}

internal const val WITHINGS_PASSWORD_INFO_TEXT =
    "Pulse4 se přihlašuje jako webový prohlížeč. Heslo se ukládá šifrovaně v Android Keystore jen v telefonu a slouží k automatickému obnovení spojení, když vyprší relace; lze vypnout. Pokud se k Withings přihlašujete přes Google nebo Apple, založte si nejdřív heslo přes „Zapomenuté heslo“ na account.withings.com."

/** „Očičko“ pro zobrazení / skrytí hesla v poli hesla Withings. */
@Composable
internal fun WithingsPasswordVisibilityToggle(visible: Boolean, onChange: (Boolean) -> Unit) {
    IconButton(onClick = { onChange(!visible) }) {
        Icon(
            imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = if (visible) "Skrýt heslo" else "Zobrazit heslo",
            tint = Color(0xFF94A3B8)
        )
    }
}

/** Přepínač „Pamatovat heslo pro automatické obnovení“ (výchozí zapnuto). */
@Composable
internal fun WithingsRememberPasswordRow(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF0284C7))
        )
        Text(
            "Pamatovat heslo pro automatické obnovení",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFCBD5E1)
        )
    }
}

internal fun isPlausibleEmail(email: String): Boolean {
    val e = email.trim()
    return e.length >= 3 && e.contains('@') && !e.startsWith('@') && !e.endsWith('@')
}

/**
 * Výsledek / průběh přihlášení přímo pod tlačítkem. Při každé změně se karta posune do viditelné
 * oblasti dialogu (i když je otevřená klávesnice a uživatel je odscrollovaný dolů).
 */
@Composable
internal fun WithingsInlineFeedback(feedback: WithingsFeedback?, tag: String) {
    if (feedback == null) return
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(feedback) {
        // Počkat na layout karty, pak ji vysunout do viditelné oblasti scrollovaného dialogu.
        delay(60)
        runCatching { requester.bringIntoView() }
    }
    val (container, border, textColor) = when (feedback.kind) {
        WithingsFeedbackKind.Error -> Triple(Color(0xFF450A0A), Color(0xFFEF4444), Color(0xFFFCA5A5))
        WithingsFeedbackKind.Info -> Triple(Color(0xFF0B1928), Color(0xFF06B6D4), Color(0xFFBAE6FD))
        WithingsFeedbackKind.Success -> Triple(Color(0xFF064E3B), Color(0xFF10B981), Color(0xFFA7F3D0))
        WithingsFeedbackKind.Progress -> Triple(Color(0xFF0F1E2E), Color(0xFF0284C7), Color(0xFFCBD5E1))
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, border.copy(alpha = 0.8f)),
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (feedback.kind) {
                WithingsFeedbackKind.Progress -> CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = border,
                    modifier = Modifier.size(18.dp)
                )
                WithingsFeedbackKind.Error -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = border, modifier = Modifier.size(20.dp))
                WithingsFeedbackKind.Success -> Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = border, modifier = Modifier.size(20.dp))
                WithingsFeedbackKind.Info -> Icon(Icons.Outlined.Info, contentDescription = null, tint = border, modifier = Modifier.size(20.dp))
            }
            Text(feedback.text, style = MaterialTheme.typography.bodySmall, color = textColor)
        }
    }
}

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
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val savedEmail = sl.secrets.getString("withings.web.email") ?: sl.withings.loadWebSession()?.email ?: sl.withings.pendingEmail()
    var email by rememberSaveable { mutableStateOf(savedEmail ?: "") }
    var password by remember { mutableStateOf(sl.withings.rememberedPassword().orEmpty()) }
    var rememberPassword by rememberSaveable { mutableStateOf(true) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var awaitingCode by rememberSaveable { mutableStateOf(sl.withings.isAwaitingCode()) }
    var loggingIn by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var waitingForSync by remember { mutableStateOf(false) }
    var loginFeedback by remember { mutableStateOf<WithingsFeedback?>(null) }
    var codeFeedback by remember { mutableStateOf<WithingsFeedback?>(null) }
    var focusCodeTick by remember { mutableStateOf(0) }
    val codeFocus = remember { FocusRequester() }
    val busy = loggingIn || verifying
    val pausedMsg = remember { sl.withings.authPausedMessage() }

    LaunchedEffect(focusCodeTick) {
        if (focusCodeTick > 0) {
            delay(120)
            runCatching { codeFocus.requestFocus() }
        }
    }

    val accountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pickedName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (!pickedName.isNullOrBlank()) {
            email = pickedName
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!pausedMsg.isNullOrBlank()) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF450A0A)),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Automatické obnovení bylo pozastaveno: $pausedMsg",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFCA5A5),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        // E-mail účtu Withings
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("E-mail účtu Withings") },
            placeholder = { Text("např. jmeno@gmail.com") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
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

        val doLogin: () -> Unit = doLogin@{
            if (busy || password.isBlank()) return@doLogin
            if (!isPlausibleEmail(email)) {
                loginFeedback = WithingsFeedback("Zadejte platný e-mail účtu Withings.", WithingsFeedbackKind.Error)
                return@doLogin
            }
            keyboard?.hide()
            focusManager.clearFocus()
            loggingIn = true
            waitingForSync = false
            codeFeedback = null
            loginFeedback = WithingsFeedback("Přihlašuji se k Withings…", WithingsFeedbackKind.Progress)
            val em = email.trim()
            val pw = password
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    sl.withings.loginDirectPassword(em, pw, rememberPassword = rememberPassword, onWaiting = {
                        scope.launch {
                            waitingForSync = true
                            loginFeedback = WithingsFeedback(WITHINGS_WAITING_TEXT, WithingsFeedbackKind.Progress)
                        }
                    })
                }
                loggingIn = false
                waitingForSync = false
                loginFeedback = res.toFeedback()
                when (res) {
                    is WithingsLoginResult.Ok -> {
                        password = ""
                        code = ""
                        awaitingCode = false
                        onLoggedIn(res)
                    }
                    is WithingsLoginResult.NeedsMfa -> {
                        awaitingCode = true
                        focusCodeTick++
                    }
                    is WithingsLoginResult.Error -> Unit
                }
            }
        }

        // Heslo Withings
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Heslo Withings") },
            placeholder = { Text("Zadejte heslo k účtu Withings") },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { WithingsPasswordVisibilityToggle(passwordVisible) { passwordVisible = it } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { doLogin() }),
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        WithingsRememberPasswordRow(rememberPassword, enabled = !busy) { rememberPassword = it }

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
                        "K účtu Withings je nutné mít heslo",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        WITHINGS_PASSWORD_INFO_TEXT,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                }
            }
        }

        Button(
            enabled = !busy && password.isNotBlank(),
            onClick = doLogin,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
        ) {
            if (loggingIn) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (waitingForSync) WITHINGS_WAITING_TEXT else "Přihlašuji se k Withings…", fontWeight = FontWeight.Bold)
            } else {
                Text("Přihlásit se heslem", fontWeight = FontWeight.Bold)
            }
        }

        // Výsledek přihlášení přímo pod tlačítkem (chyba / výzva k zadání kódu / průběh)
        WithingsInlineFeedback(loginFeedback, WITHINGS_PASSWORD_FEEDBACK_TAG)

        val doVerifyMfa: () -> Unit = doVerify@{
            if (busy || code.isBlank()) return@doVerify
            if (!isPlausibleEmail(email)) {
                codeFeedback = WithingsFeedback("Zadejte platný e-mail účtu Withings.", WithingsFeedbackKind.Error)
                return@doVerify
            }
            keyboard?.hide()
            focusManager.clearFocus()
            verifying = true
            waitingForSync = false
            codeFeedback = WithingsFeedback("Ověřuji kód u Withings…", WithingsFeedbackKind.Progress)
            val em = email.trim()
            val c = code
            val pw = password
            val remember = rememberPassword
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    sl.withings.resumeDirectMfa(c, emailFallback = em, password = pw, rememberPassword = remember, onWaiting = {
                        scope.launch {
                            waitingForSync = true
                            codeFeedback = WithingsFeedback(WITHINGS_WAITING_TEXT, WithingsFeedbackKind.Progress)
                        }
                    })
                }
                verifying = false
                waitingForSync = false
                codeFeedback = res.toFeedback()
                when (res) {
                    is WithingsLoginResult.Ok -> {
                        password = ""
                        code = ""
                        awaitingCode = false
                        onLoggedIn(res)
                    }
                    is WithingsLoginResult.NeedsMfa -> {
                        awaitingCode = true
                        code = ""
                        focusCodeTick++
                    }
                    is WithingsLoginResult.Error -> Unit
                }
            }
        }

        // Pokud Withings vyžaduje 2FA kód (např. e-mail OTP nebo SMS) – pole hned pod výsledkem přihlášení
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
                        modifier = Modifier.fillMaxWidth().focusRequester(codeFocus).testTag(WITHINGS_CODE_FIELD_TAG)
                    )

                    Button(
                        enabled = !busy && code.isNotBlank(),
                        onClick = doVerifyMfa,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                    ) {
                        if (verifying) {
                            CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (waitingForSync) WITHINGS_WAITING_TEXT else "Ověřuji kód…", fontWeight = FontWeight.Bold)
                        } else {
                            Text("Potvrdit ověřovací kód", fontWeight = FontWeight.Bold)
                        }
                    }

                    WithingsInlineFeedback(codeFeedback, WITHINGS_CODE_FEEDBACK_TAG)
                }
            }
        }
    }
}

/**
 * Režimy zobrazení dialogu Withings:
 * - [Reconnect]: Přímé rychlé obnovení relace bez jakéhokoli výběru účtů / profilů (zachovává stávající hlavní i dětský profil).
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
 * Umožňuje rychlé obnovení heslem bez ztráty nastavení profilů.
 */
@Composable
fun WithingsReconnectForm(
    sl: ServiceLocator,
    onLoggedIn: (WithingsLoginResult.Ok) -> Unit,
    @Suppress("UNUSED_PARAMETER") onNotice: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val savedEmail = remember {
        sl.secrets.getString("withings.web.email")?.takeIf { it.isNotBlank() }
            ?: sl.withings.loadWebSession()?.email?.takeIf { it.isNotBlank() }
            ?: sl.withings.pendingEmail()
            ?: ""
    }
    var email by rememberSaveable { mutableStateOf(savedEmail) }
    var password by remember { mutableStateOf(sl.withings.rememberedPassword().orEmpty()) }
    var rememberPassword by rememberSaveable { mutableStateOf(true) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(sl.withings.isAwaitingCode()) }
    var verifyingCode by remember { mutableStateOf(false) }
    var loggingInByPassword by remember { mutableStateOf(false) }
    var waitingForSync by remember { mutableStateOf(false) }
    var loginFeedback by remember { mutableStateOf<WithingsFeedback?>(null) }
    var codeFeedback by remember { mutableStateOf<WithingsFeedback?>(null) }
    var focusCodeTick by remember { mutableStateOf(0) }
    val codeFocus = remember { FocusRequester() }
    val busy = loggingInByPassword || verifyingCode
    val pausedMsg = remember { sl.withings.authPausedMessage() }

    LaunchedEffect(focusCodeTick) {
        if (focusCodeTick > 0) {
            delay(120)
            runCatching { codeFocus.requestFocus() }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!pausedMsg.isNullOrBlank()) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF450A0A)),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Automatické obnovení bylo pozastaveno: $pausedMsg",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFCA5A5),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
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
                        "Původní nastavení profilů zůstane zachováno",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }

        // E-mail účtu (předvyplněný, lze opravit)
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("E-mail účtu Withings") },
            placeholder = { Text("např. jmeno@gmail.com") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            singleLine = true,
            enabled = !busy,
            isError = email.isNotEmpty() && !isPlausibleEmail(email),
            modifier = Modifier.fillMaxWidth()
        )

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
                        "K účtu Withings je nutné mít heslo",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        WITHINGS_PASSWORD_INFO_TEXT,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                }
            }
        }

        val doPasswordLogin: () -> Unit = doLogin@{
            if (busy || password.isBlank()) return@doLogin
            if (!isPlausibleEmail(email)) {
                loginFeedback = WithingsFeedback("Zadejte platný e-mail účtu Withings.", WithingsFeedbackKind.Error)
                return@doLogin
            }
            keyboard?.hide()
            focusManager.clearFocus()
            loggingInByPassword = true
            waitingForSync = false
            codeFeedback = null
            loginFeedback = WithingsFeedback("Obnovuji spojení heslem…", WithingsFeedbackKind.Progress)
            val em = email.trim()
            val pw = password
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    sl.withings.loginDirectPassword(em, pw, rememberPassword = rememberPassword, onWaiting = {
                        scope.launch {
                            waitingForSync = true
                            loginFeedback = WithingsFeedback(WITHINGS_WAITING_TEXT, WithingsFeedbackKind.Progress)
                        }
                    })
                }
                loggingInByPassword = false
                waitingForSync = false
                loginFeedback = res.toFeedback()
                when (res) {
                    is WithingsLoginResult.Ok -> {
                        password = ""
                        onLoggedIn(res)
                    }
                    is WithingsLoginResult.NeedsMfa -> {
                        codeSent = true
                        focusCodeTick++
                    }
                    is WithingsLoginResult.Error -> Unit
                }
            }
        }

        // Pole pro heslo
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Heslo Withings") },
            placeholder = { Text("Zadejte heslo k účtu") },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { WithingsPasswordVisibilityToggle(passwordVisible) { passwordVisible = it } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { doPasswordLogin() }),
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        WithingsRememberPasswordRow(rememberPassword, enabled = !busy) { rememberPassword = it }

        Button(
            enabled = !busy && password.isNotBlank(),
            onClick = doPasswordLogin,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
        ) {
            if (loggingInByPassword) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (waitingForSync) WITHINGS_WAITING_TEXT else "Obnovuji spojení heslem…", fontWeight = FontWeight.Bold)
            } else {
                Text("Obnovit spojení heslem", fontWeight = FontWeight.Bold)
            }
        }

        // Výsledek přihlášení přímo pod tlačítkem (chyba / výzva k zadání kódu / průběh)
        WithingsInlineFeedback(loginFeedback, WITHINGS_PASSWORD_FEEDBACK_TAG)

        // Pokud server vyžádal 2FA kód – pole hned pod výsledkem přihlášení
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
                        "Withings vyžaduje ověřovací kód (2FA)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )

                    val doVerifyCode: () -> Unit = doVerify@{
                        if (busy || code.length < 6) return@doVerify
                        if (!isPlausibleEmail(email)) {
                            codeFeedback = WithingsFeedback("Zadejte platný e-mail účtu Withings.", WithingsFeedbackKind.Error)
                            return@doVerify
                        }
                        keyboard?.hide()
                        focusManager.clearFocus()
                        verifyingCode = true
                        waitingForSync = false
                        codeFeedback = WithingsFeedback("Ověřuji kód u Withings…", WithingsFeedbackKind.Progress)
                        val em = email.trim()
                        val c = code
                        val pw = password
                        val remember = rememberPassword
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                sl.withings.resumeDirectMfa(c, emailFallback = em, password = pw, rememberPassword = remember, onWaiting = {
                                    scope.launch {
                                        waitingForSync = true
                                        codeFeedback = WithingsFeedback(WITHINGS_WAITING_TEXT, WithingsFeedbackKind.Progress)
                                    }
                                })
                            }
                            verifyingCode = false
                            waitingForSync = false
                            codeFeedback = res.toFeedback()
                            when (res) {
                                is WithingsLoginResult.Ok -> onLoggedIn(res)
                                is WithingsLoginResult.NeedsMfa -> {
                                    code = ""
                                    focusCodeTick++
                                }
                                is WithingsLoginResult.Error -> Unit
                            }
                        }
                    }

                    OutlinedTextField(
                        value = code,
                        onValueChange = { input ->
                            val digits = input.filter { it.isDigit() }.take(8)
                            code = digits
                        },
                        label = { Text("Ověřovací kód (2FA)") },
                        placeholder = { Text("Kód, který Withings vyžádal") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { doVerifyCode() }),
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().focusRequester(codeFocus).testTag(WITHINGS_CODE_FIELD_TAG)
                    )

                    Button(
                        enabled = !busy && code.length >= 6,
                        onClick = doVerifyCode,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                    ) {
                        if (verifyingCode) {
                            CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (waitingForSync) WITHINGS_WAITING_TEXT else "Ověřuji kód u Withings…", fontWeight = FontWeight.Bold)
                        } else {
                            Text("Potvrdit kód a obnovit", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    WithingsInlineFeedback(codeFeedback, WITHINGS_CODE_FEEDBACK_TAG)
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
                stage = NativeDialogStage.Error("Nepodařilo se načíst profily z Withings. Zkuste to prosím znovu.")
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
                                        // Pokud již profil známe (hlavní profil), zachováme ho a dialog ihned zavřeme:
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
                                val parent = p.findParent(cur.profiles)
                                val roleBadge = when {
                                    p.isAdult && p.isPrimary -> " (Hlavní účet)"
                                    p.isAdult -> " (Dospělý)"
                                    parent != null -> " (Dětský · pod ${parent.firstName})"
                                    else -> " (Dětský profil)"
                                }
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            selectedPrimaryId = p.id
                                            if (selectedChildId == p.id) selectedChildId = null
                                            val childForAdult = p.childrenUnderThis(cur.profiles).firstOrNull()
                                            if (childForAdult != null && selectedChildId == null) {
                                                selectedChildId = childForAdult.id
                                            }
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
                                                val childForAdult = p.childrenUnderThis(cur.profiles).firstOrNull()
                                                if (childForAdult != null && selectedChildId == null) {
                                                    selectedChildId = childForAdult.id
                                                }
                                            },
                                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF06B6D4))
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                p.displayName + roleBadge,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            val details = buildList {
                                                add("ID: ${p.id}")
                                                p.email?.takeIf { it.isNotBlank() }?.let { add(it) }
                                                p.shortName.takeIf { it.isNotBlank() }?.let { add(it) }
                                            }.joinToString(" · ")
                                            Text(
                                                details,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                }
                            }

                            val childProfiles = cur.profiles.filter { it.id != selectedPrimaryId && it.isChild }
                            if (cur.profiles.size > 1) {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "2. Dětský profil pro automatické P2P přeposílání do telefonu dítěte (volitelné):",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                if (childProfiles.isEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(containerColor = app.pulse4.android.ui.theme.MineralBackgroundDark),
                                        border = BorderStroke(1.dp, app.pulse4.android.ui.theme.MineralOutlineDark)
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(
                                                "Na Withings účtu jsou nyní 2 dospělé profily (${cur.profiles.joinToString { it.displayName }}).",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFFE2E8F0)
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                "Pokud Klárka nebo vy vytvoříte dětský profil ve Withings aplikaci, Pulse4 jej automaticky rozpozná (vedený pod daným rodičem) a nabídne pro přeposílání. Mezitím můžete využít váhové pásmo dítěte.",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        "Vyberte profil dítěte na váze. Druhý dospělý profil zůstává samostatně.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                    Spacer(Modifier.height(6.dp))

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
                                            Text("Žádný (nepřeposílat)", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                                        }
                                    }

                                    childProfiles.forEach { cp ->
                                        val isChild = selectedChildId == cp.id
                                        val parent = cp.findParent(cur.profiles)
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
                                                    Text(
                                                        "Dětský profil${parent?.let { " · Vedený pod: ${it.displayName}" } ?: ""} · ID: ${cp.id}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color(0xFF94A3B8)
                                                    )
                                                }
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

/**
 * Karta „Diagnostika Withings“ v Nastavení: redigovaný záznam posledních pokusů o přihlášení
 * a tichého obnovení (cesty URL bez parametrů, HTTP stav, výsledek). Neobsahuje cookies, hesla,
 * tokeny ani e-mail, takže ji lze bezpečně zkopírovat a poslat.
 */
@Composable
fun WithingsDiagnosticsCard(sl: ServiceLocator, refreshKey: Any? = null) {
    val ctx = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(expanded, reload, refreshKey) {
        if (expanded) text = withContext(Dispatchers.IO) { sl.withings.authDiagnosticsText() }
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded; copied = false },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Diagnostika Withings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Průběh posledních pokusů o přihlášení a tiché obnovení (bez hesla, cookies a e-mailu).",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
                Text(if (expanded) "Skrýt" else "Zobrazit", style = MaterialTheme.typography.labelMedium, color = Color(0xFF38BDF8))
            }
            if (expanded) {
                Text(
                    text.ifBlank { "Načítám…" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFCBD5E1)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        copied = cm != null && runCatching {
                            cm.setPrimaryClip(ClipData.newPlainText("Diagnostika Withings", text.ifBlank { sl.withings.authDiagnosticsText() }))
                        }.isSuccess
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (copied) "Zkopírováno" else "Zkopírovat")
                    }
                    TextButton(onClick = { reload++; copied = false }) { Text("Obnovit") }
                }
            }
        }
    }
}
