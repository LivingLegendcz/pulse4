@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse4.android.data.ContactRow
import app.pulse4.android.di.CircleInvite
import app.pulse4.android.di.ConsentItem
import app.pulse4.android.di.ConsentRole
import app.pulse4.android.di.ConsentUiState
import app.pulse4.android.di.ServiceLocator
import app.pulse4.sharing.AgeProfile
import app.pulse4.sharing.AgeState
import app.pulse4.sharing.ConsentStatus
import app.pulse4.sharing.ConsentSubject
import app.pulse4.sharing.FamilyLink
import app.pulse4.sharing.FamilyLinkStatus
import app.pulse4.sharing.Group
import app.pulse4.sharing.MembershipAction
import app.pulse4.sync.Wire
import app.pulse4.ui.ConsentDiff
import app.pulse4.ui.Labels
import app.pulse4.ui.ParentLinkPhase
import app.pulse4.ui.ParentLinkStatus
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------------------------------------------------

private val CardBg = Color(0xFF0A101B)
private val Muted = Color(0xFF94A3B8)
private val Soft = Color(0xFFCBD5E1)
private val Teal = Color(0xFF2DD4BF)
private val Sky = Color(0xFF38BDF8)
private val Amber = Color(0xFFF59E0B)
private val Green = Color(0xFF10B981)
private val Rose = Color(0xFFF43F5E)

private fun nameResolver(contacts: List<ContactRow>): (String) -> String {
    val byId = contacts.associateBy { it.identity }
    return { id -> byId[id]?.displayName ?: "Neznámý kontakt …${id.takeLast(4)}" }
}

private fun formatDateTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

@Composable
private fun rememberSelfId(sl: ServiceLocator): String? {
    val self by io(Unit) { sl.bridge.ensureStarted().deliveryHash }
    return self
}

@Composable
private fun rememberFamilyLinks(sl: ServiceLocator): List<FamilyLink> {
    val rows by sl.db.familyLinks().all().collectAsState(emptyList())
    return remember(rows) {
        rows.mapNotNull { runCatching { Wire.json.decodeFromString(FamilyLink.serializer(), it.json) }.getOrNull() }
    }
}

@Composable
private fun rememberConsentItems(sl: ServiceLocator): List<ConsentItem> {
    val flow = remember(sl) { sl.shareService.consentItems() }
    val items by flow.collectAsState(emptyList())
    return items
}

@Composable
private fun rememberInbox(sl: ServiceLocator): List<ConsentItem> {
    val flow = remember(sl) { sl.shareService.pendingGuardianInbox() }
    val items by flow.collectAsState(emptyList())
    return items
}

@Composable
private fun rememberContacts(sl: ServiceLocator): List<ContactRow> {
    val contacts by sl.db.contacts().all().collectAsState(emptyList())
    return contacts
}

/** The device-local onboarding gate; `null` until the first value is known (a short splash is shown meanwhile). */
@Composable
internal fun rememberOnboardingState(sl: ServiceLocator): State<app.pulse4.android.di.OnboardingState?> =
    produceState<app.pulse4.android.di.OnboardingState?>(initialValue = null) {
        sl.shareService.onboardingState
            .map<app.pulse4.android.di.OnboardingState, app.pulse4.android.di.OnboardingState?> { it }
            // UI-only gate: when the state cannot be read the app stays usable; sending is enforced separately.
            .catch { emit(app.pulse4.android.di.OnboardingState.ADULT) }
            .collect { value = it }
    }

internal fun consentUiLabel(s: ConsentUiState): String? = when (s) {
    ConsentUiState.NOT_REQUIRED -> null
    ConsentUiState.GRANDFATHERED -> "Dosavadní sdílení"
    ConsentUiState.NEEDS_APPROVAL -> "Vyžaduje schválení"
    ConsentUiState.WAITING -> "Čeká na rodiče"
    ConsentUiState.APPROVED -> "Schváleno rodičem"
    ConsentUiState.REJECTED -> "Zamítnuto"
    ConsentUiState.EXPIRED -> "Vypršelo"
    ConsentUiState.REVOKED -> "Odvoláno"
}

private fun consentUiColor(s: ConsentUiState): Color = when (s) {
    ConsentUiState.NOT_REQUIRED -> Muted
    ConsentUiState.GRANDFATHERED, ConsentUiState.NEEDS_APPROVAL -> Amber
    ConsentUiState.WAITING -> Sky
    ConsentUiState.APPROVED -> Green
    ConsentUiState.REJECTED, ConsentUiState.REVOKED -> Rose
    ConsentUiState.EXPIRED -> Muted
}

/** Small status badge (nothing for ADULT, where no approval is involved). */
@Composable
internal fun ConsentStateChip(state: ConsentUiState?, modifier: Modifier = Modifier) {
    val label = state?.let(::consentUiLabel) ?: return
    val color = consentUiColor(state)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun PanelCard(accent: Color = Color(0xFF232A3B), content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, accent),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        titleContentColor = Color.White,
        textContentColor = Soft,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Amber, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.ExtraBold)
            }
        },
        text = { Text(text, style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = Rose) else ButtonDefaults.buttonColors(),
            ) { Text(confirmLabel, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit", color = Muted) } },
    )
}

@Composable
private fun NoticeText(text: String?) {
    if (text == null) return
    Spacer(Modifier.height(6.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = Teal)
}

// ---------------------------------------------------------------------------------------------------------------------
// First run: "Kdo bude aplikaci používat?"
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun FirstRunAgeScreen(sl: ServiceLocator) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.FamilyRestroom, contentDescription = null, tint = Teal, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            "Kdo bude aplikaci používat?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Pulse4 umí sdílet zdravotní údaje s lidmi, které si vyberete. Pro děti do 18 let proto platí zvláštní pravidla: " +
                "každé sdílení a každý vstup do kruhu musí schválit rodič nebo zákonný zástupce na svém zařízení.",
            style = MaterialTheme.typography.bodyMedium,
            color = Soft,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { sl.shareService.declareAge(AgeState.ADULT) }
                    if (!ok) error = "Volbu se nepodařilo uložit."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Dospělý (18+)", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { sl.shareService.declareAge(AgeState.MINOR) }
                    if (!ok) error = "Volbu se nepodařilo uložit."
                }
            },
            border = BorderStroke(1.dp, Teal.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Dítě (do 18 let)", fontWeight = FontWeight.Bold, color = Teal) }
        Spacer(Modifier.height(16.dp))
        Text(
            "Dospělý potvrzuje, že mu je alespoň 18 let. Dítě si hned přidá rodiče; do té doby je aplikace zamčená. " +
                "Zpět na dospělého může dítě přejít jen s podpisem rodiče (nebo hned při omylu, dokud rodič vazbu nepotvrdil).",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            textAlign = TextAlign.Center,
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Child: waiting-for-parent gate
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun ChildGateScreen(sl: ServiceLocator) {
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmAdult by remember { mutableStateOf(false) }
    val links = rememberFamilyLinks(sl)
    val everVerified by io(links) { sl.settings.ageProfileManager.guardianLinkEverVerified() }

    if (confirmAdult) {
        ConfirmDialog(
            title = "Jste dospělý?",
            text = "Potvrzujete, že vám je alespoň 18 let a volbu „Dítě“ jste zvolil(a) omylem. Aplikace se odemkne bez rodiče.",
            confirmLabel = "Ano, jsem dospělý",
            onConfirm = {
                confirmAdult = false
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { sl.shareService.declareAge(AgeState.ADULT) }
                    if (!ok) notice = "Změnu nelze provést: rodinná vazba už byla jednou potvrzena. Uvolnit vás může jen rodič."
                }
            },
            onDismiss = { confirmAdult = false },
        )
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Security, contentDescription = null, tint = Amber, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Čekáme na rodiče",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Aplikace je zamčená, protože je určena pro dítě do 18 let. Dokud rodič nebo zákonný zástupce nepotvrdí propojení, " +
                "nelze přihlásit Garmin ani Withings, zobrazit metriky ani vytvářet kruhy a sdílení. " +
                "Spárujte zařízení rodiče, pošlete mu žádost a počkejte na jeho potvrzení.",
            style = MaterialTheme.typography.bodyMedium,
            color = Soft,
        )
        Spacer(Modifier.height(12.dp))
        ParentLinksPanel(sl = sl, gated = true, onNotice = { notice = it })
        NoticeText(notice)
        if (everVerified == false) {
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = { confirmAdult = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Zvolil(a) jsem špatně, jsem dospělý", color = Muted)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Child: "Moji rodiče" (also used by the gate)
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun ParentLinksPanel(sl: ServiceLocator, gated: Boolean, onNotice: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val selfId = rememberSelfId(sl)
    val links = rememberFamilyLinks(sl)
    val contacts = rememberContacts(sl)
    val markers by sl.db.pendingRequests().all().collectAsState(emptyList())
    val consents = rememberConsentItems(sl)
    val nameOf = remember(contacts) { nameResolver(contacts) }

    var showPairing by remember { mutableStateOf(false) }
    var selectedContact by remember { mutableStateOf<String?>(null) }
    var removeTarget by remember { mutableStateOf<FamilyLink?>(null) }
    var cancelTarget by remember { mutableStateOf<FamilyLink?>(null) }
    var localNotice by remember { mutableStateOf<String?>(null) }
    fun say(msg: String) { localNotice = msg; onNotice(msg) }

    if (showPairing) PairingModalDialog(sl = sl, onDismiss = { showPairing = false })

    val me = selfId ?: return
    val mine = links.filter { it.childId == me && it.childAgeProfile == AgeProfile.MINOR }
    val verified = mine.filter { it.status == FamilyLinkStatus.VERIFIED }
    val pending = mine.filter { it.status == FamilyLinkStatus.PENDING }
    val rejected = if (gated) mine.filter { it.status == FamilyLinkStatus.REJECTED } else emptyList()
    val awaitingMarkers = markers.filter { it.kind == "GUARDIAN_LINK_AWAITING_ADD" && it.status == "AWAITING" }.map { it.id }.toSet()
    val addPendingFor = consents.filter {
        it.role == ConsentRole.CHILD_OUT && it.subject == ConsentSubject.GUARDIAN_ADD && it.status == ConsentStatus.PENDING
    }.map { it.subjectId }.toSet()

    removeTarget?.let { link ->
        ConfirmDialog(
            title = "Odebrat rodiče ${nameOf(link.parentId)}?",
            text = ParentLinkStatus.removeWarning(isLastVerifiedParent = verified.size <= 1),
            confirmLabel = "Odebrat vazbu",
            danger = true,
            onConfirm = {
                removeTarget = null
                scope.launch {
                    val res = withContext(Dispatchers.IO) { sl.shareService.revokeFamilyLink(link.id) }
                    say(res.fold({ "Vazba s rodičem ${nameOf(link.parentId)} byla odebrána." }, { it.message ?: "Vazbu se nepodařilo odebrat." }))
                }
            },
            onDismiss = { removeTarget = null },
        )
    }
    cancelTarget?.let { link ->
        ConfirmDialog(
            title = "Zrušit žádost?",
            text = "Žádost pro ${nameOf(link.parentId)} se zruší. Rodiči se pošle upozornění o zrušení.",
            confirmLabel = "Zrušit žádost",
            danger = true,
            onConfirm = {
                cancelTarget = null
                scope.launch {
                    val res = withContext(Dispatchers.IO) { sl.shareService.revokeFamilyLink(link.id) }
                    say(res.fold({ "Žádost byla zrušena." }, { it.message ?: "Žádost se nepodařilo zrušit." }))
                }
            },
            onDismiss = { cancelTarget = null },
        )
    }

    PanelCard(Teal.copy(alpha = 0.45f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FamilyRestroom, contentDescription = null, tint = Teal, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Moji rodiče", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        if (mine.none { it.status != FamilyLinkStatus.REVOKED && it.status != FamilyLinkStatus.REJECTED }) {
            Text("Zatím nemáte žádného rodiče.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        (verified + pending + rejected).forEach { link ->
            val phase = ParentLinkStatus.phase(
                status = link.status,
                initiatedByChild = link.initiator == me,
                hasVerifiedGuardians = verified.isNotEmpty(),
                awaitingGuardianAdd = "gadd-await-${link.id}" in awaitingMarkers,
                guardianAddPending = link.parentId in addPendingFor,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = Teal, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(nameOf(link.parentId), fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        ParentLinkStatus.label(phase),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (phase == ParentLinkPhase.VERIFIED) Green else Muted,
                    )
                }
            }
            when (phase) {
                ParentLinkPhase.WAITING_FOR_PARENT, ParentLinkPhase.WAITING_FOR_PARENT_AND_GUARDIAN -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { sl.shareService.resendFamilyLinkRequest(link.id) }
                                say(if (ok) "Žádost byla odeslána znovu." else "Žádost se nepodařilo odeslat.")
                            }
                        }) { Text("Odeslat znovu") }
                        TextButton(onClick = { cancelTarget = link }) { Text("Zrušit žádost", color = Rose) }
                    }
                }
                ParentLinkPhase.WAITING_FOR_EXISTING_GUARDIAN ->
                    TextButton(onClick = { cancelTarget = link }) { Text("Zrušit žádost", color = Rose) }
                ParentLinkPhase.INVITED_BY_PARENT -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) { sl.shareService.respondFamilyLink(link.id, accept = true) }
                                    say(if (res != null) "Rodič byl potvrzen." else "Žádost se nepodařilo potvrdit.")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Green),
                        ) { Text("Potvrdit", fontWeight = FontWeight.Bold) }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) { sl.shareService.respondFamilyLink(link.id, accept = false) }
                                say(if (res != null) "Žádost byla odmítnuta." else "Žádost se nepodařilo odmítnout.")
                            }
                        }) { Text("Odmítnout", color = Rose) }
                    }
                }
                ParentLinkPhase.VERIFIED ->
                    TextButton(onClick = { removeTarget = link }) { Text("Odebrat vazbu", color = Rose) }
                else -> Unit
            }
        }

        // add a (further) parent
        val takenIds = (verified + pending).map { it.parentId }.toSet()
        val candidates = contacts.filter { it.identity !in takenIds && it.identity != me }
        Spacer(Modifier.height(14.dp))
        Text(
            if (verified.isEmpty()) "Přidat rodiče" else "Přidat dalšího rodiče",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        if (verified.isNotEmpty()) {
            Text(
                "Další rodič musí žádost potvrdit na svém zařízení a zároveň ji musí schválit stávající rodič.",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (candidates.isEmpty()) {
            Text(
                "Nejdřív spárujte zařízení rodiče přes QR kód (rodič ukáže svůj QR kód v aplikaci).",
                style = MaterialTheme.typography.bodySmall,
                color = Soft,
            )
        } else {
            Text("Vyberte spárovaný kontakt:", style = MaterialTheme.typography.labelMedium, color = Soft)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                candidates.forEach { c ->
                    FilterChip(
                        selected = selectedContact == c.identity,
                        onClick = { selectedContact = c.identity },
                        leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text(c.displayName) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Button(
                enabled = selectedContact != null,
                onClick = {
                    val contact = candidates.firstOrNull { it.identity == selectedContact } ?: return@Button
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { sl.shareService.sendChildFamilyLinkRequest(contact) }
                        selectedContact = null
                        say(
                            res.fold(
                                { "Žádost odeslána (${contact.displayName}). Čeká na potvrzení rodiče." },
                                { it.message ?: "Žádost se nepodařilo odeslat." },
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Odeslat žádost rodiči", fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { showPairing = true },
            border = BorderStroke(1.dp, Teal.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.QrCode, contentDescription = null, tint = Teal, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Spárovat zařízení rodiče (QR)", color = Teal)
        }
        NoticeText(localNotice)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Child: my requests to the parents (waiting / rejected / expired / revoked)
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun ChildRequestsSection(sl: ServiceLocator, groupId: String? = null, onNotice: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val contacts = rememberContacts(sl)
    val items = rememberConsentItems(sl)
    val ageState by sl.settings.ageStateFlow.collectAsState()
    val nameOf = remember(contacts) { nameResolver(contacts) }
    if (ageState == AgeState.ADULT) return

    val now = System.currentTimeMillis()
    val mine = items.filter { it.role == ConsentRole.CHILD_OUT && it.subject != ConsentSubject.GUARDIAN_ADD }
        .filter { groupId == null || it.request.groupId == groupId || it.subjectId == groupId }
    val waiting = mine.filter { it.status == ConsentStatus.PENDING && it.expiresAtMs >= now }
    val settledSubjects = mine.filter {
        (it.status == ConsentStatus.PENDING && it.expiresAtMs >= now) || it.status == ConsentStatus.APPROVED
    }.map { it.subject to it.subjectId }.toSet()
    val failed = mine.filter {
        (it.status == ConsentStatus.REJECTED || it.status == ConsentStatus.EXPIRED || it.status == ConsentStatus.REVOKED ||
            (it.status == ConsentStatus.PENDING && it.expiresAtMs < now)) && (it.subject to it.subjectId) !in settledSubjects
    }.groupBy { it.subject to it.subjectId }.values.map { rows -> rows.maxBy { it.createdAtMs } }
        .sortedByDescending { it.createdAtMs }.take(5)
    if (waiting.isEmpty() && failed.isEmpty()) return

    PanelCard(Sky.copy(alpha = 0.4f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Security, contentDescription = null, tint = Sky, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Moje žádosti rodičům", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        (waiting + failed).forEach { item ->
            val d = remember(item.requestId, contacts) { ConsentDiff.describe(item.request, nameOf) }
            val state = when {
                item.status == ConsentStatus.PENDING && item.expiresAtMs >= now -> ConsentUiState.WAITING
                item.status == ConsentStatus.REJECTED -> ConsentUiState.REJECTED
                item.status == ConsentStatus.REVOKED -> ConsentUiState.REVOKED
                else -> ConsentUiState.EXPIRED
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.title, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(d.headline, style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                ConsentStateChip(state)
            }
            DiffLines(d.lines)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state == ConsentUiState.WAITING) {
                    TextButton(onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { sl.shareService.withdrawConsentRequest(item.requestId) }
                            onNotice(if (ok) "Žádost byla stažena." else "Žádost už nelze stáhnout.")
                        }
                    }) { Text("Stáhnout žádost", color = Rose) }
                } else if (item.subject == ConsentSubject.SHARE_GRANT && item.proposedGrant != null) {
                    TextButton(onClick = {
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { sl.shareService.requestGuardianConsent(item.proposedGrant) }
                            onNotice(res.fold({ "Žádost byla odeslána znovu." }, { it.message ?: "Žádost se nepodařilo odeslat." }))
                        }
                    }) { Text("Požádat znovu", color = Sky) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(Labels.DELIVERED_DATA_NOTE, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

@Composable
private fun DiffLines(lines: List<ConsentDiff.Line>) {
    lines.forEach { line ->
        val color = when (line.kind) {
            ConsentDiff.Kind.ADDED -> Color(0xFF34D399)
            ConsentDiff.Kind.REMOVED -> Amber
            ConsentDiff.Kind.CHANGED -> Sky
            ConsentDiff.Kind.INFO -> Muted
        }
        val prefix = when (line.kind) {
            ConsentDiff.Kind.ADDED -> "+ "
            ConsentDiff.Kind.REMOVED -> "− "
            ConsentDiff.Kind.CHANGED -> "~ "
            ConsentDiff.Kind.INFO -> ""
        }
        Text("$prefix${line.label}: ${line.text}", style = MaterialTheme.typography.bodySmall, color = color)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Child: circles - state chips and parked invitations
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun CircleConsentSection(sl: ServiceLocator, onNotice: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val ageState by sl.settings.ageStateFlow.collectAsState()
    if (ageState == AgeState.ADULT) return
    val selfId = rememberSelfId(sl)
    val groupRows by sl.db.grants().groups().collectAsState(emptyList())
    val pendingRows by sl.db.pendingRequests().all().collectAsState(emptyList())
    val contacts = rememberContacts(sl)
    val stateFlow = remember(sl) { sl.shareService.consentStateByCircle() }
    val states by stateFlow.collectAsState(emptyMap())
    val nameOf = remember(contacts) { nameResolver(contacts) }
    val groups = remember(groupRows) {
        groupRows.mapNotNull { runCatching { Wire.json.decodeFromString(Group.serializer(), it.json) }.getOrNull() }.filter { !it.dissolved }
    }
    val invites by produceState(emptyList<CircleInvite>(), pendingRows) {
        value = withContext(Dispatchers.IO) { runCatching { sl.shareService.circleInvites() }.getOrDefault(emptyList()) }
    }
    val rows = groups.map { it to null as String? } + invites.map { it.group to it.fromId as String? }
    if (rows.isEmpty()) return
    val me = selfId ?: return

    PanelCard(Amber.copy(alpha = 0.4f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Security, contentDescription = null, tint = Amber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Kruhy a souhlas rodiče", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        Text(
            "Do kruhu můžete vstoupit nebo ho založit až po souhlasu rodiče. Dokud nesouhlasí, ostatní členové vás v kruhu neuvidí a nic se tam nesdílí.",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
        rows.forEach { (group, inviteFrom) ->
            val state = states[group.id]
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(group.name, fontWeight = FontWeight.Bold, color = Color.White)
                    if (inviteFrom != null) {
                        Text("Pozvánka od ${nameOf(inviteFrom)}", style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
                ConsentStateChip(state)
            }
            val needs = state == ConsentUiState.NEEDS_APPROVAL || state == ConsentUiState.REJECTED ||
                state == ConsentUiState.EXPIRED || state == ConsentUiState.REVOKED
            if (needs) {
                val action = if (group.adminId == me || me in group.effectiveAdmins) MembershipAction.CREATE else MembershipAction.JOIN
                TextButton(onClick = {
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { sl.shareService.requestCircleMembership(group, action) }
                        onNotice(res.fold({ "Žádost o souhlas rodiče byla odeslána." }, { it.message ?: "Žádost se nepodařilo odeslat." }))
                    }
                }) { Text("Požádat rodiče o souhlas", color = Sky, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Guardian: inbox "Žádosti dětí"
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun GuardianInboxSection(sl: ServiceLocator, onNotice: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val contacts = rememberContacts(sl)
    val inbox = rememberInbox(sl)
    val nameOf = remember(contacts) { nameResolver(contacts) }
    if (inbox.isEmpty()) return

    inbox.forEach { item ->
        val d = remember(item.requestId, contacts) {
            ConsentDiff.describe(item.request, nameOf, planNameOf = { "tréninkový plán" })
        }
        var details by remember(item.requestId) { mutableStateOf(false) }
        PanelCard(Teal.copy(alpha = 0.5f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FamilyRestroom, contentDescription = null, tint = Teal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${nameOf(item.request.childId)}: ${d.title}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                    )
                    Text(d.headline, style = MaterialTheme.typography.labelSmall, color = Soft)
                }
            }
            Spacer(Modifier.height(6.dp))
            DiffLines(d.lines)
            if (!d.firstApproval && d.lines.isEmpty()) {
                Text("Žádné změny oproti schválenému sdílení.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            TextButton(onClick = { details = !details }) {
                Text(if (details) "Skrýt podrobnosti" else "Podrobnosti", color = Muted, fontSize = 12.sp)
            }
            if (details) {
                Text(
                    "Žádost platí do ${formatDateTime(item.expiresAtMs)}. Podepíšete ji svým klíčem a dítě její platnost samo ověří. " +
                        Labels.DELIVERED_DATA_NOTE,
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { sl.shareService.decideConsent(item.requestId, approve = true) }
                            onNotice(res.fold({ "Žádost byla schválena." }, { it.message ?: "Schválení se nepodařilo." }))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Schválit", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { sl.shareService.decideConsent(item.requestId, approve = false) }
                            onNotice(res.fold({ "Žádost byla zamítnuta." }, { it.message ?: "Zamítnutí se nepodařilo." }))
                        }
                    },
                    border = BorderStroke(1.dp, Rose.copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = null, tint = Rose, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Zamítnout", color = Rose)
                }
            }
        }
    }
}

/** Number of requests waiting for this guardian (drives the banner and the tab counter). */
@Composable
internal fun rememberGuardianInboxCount(sl: ServiceLocator): Int {
    return rememberInbox(sl).size
}

/** Thin banner shown on every main screen while a child's request waits for this guardian. */
@Composable
internal fun GuardianInboxBanner(sl: ServiceLocator, onOpen: () -> Unit) {
    val count = rememberGuardianInboxCount(sl)
    if (count == 0) return
    androidx.compose.material3.Surface(color = Color(0xFF0F2942), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (count == 1) "1 žádost dítěte čeká na schválení." else "$count žádostí dětí čeká na schválení.",
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE0F2FE),
            )
            TextButton(onClick = onOpen) { Text("Otevřít", color = Sky, fontWeight = FontWeight.Bold) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Guardian: "Moje děti"
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun MyChildrenSection(sl: ServiceLocator, onNotice: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val selfId = rememberSelfId(sl)
    val links = rememberFamilyLinks(sl)
    val contacts = rememberContacts(sl)
    val items = rememberConsentItems(sl)
    val nameOf = remember(contacts) { nameResolver(contacts) }
    var revokeTarget by remember { mutableStateOf<ConsentItem?>(null) }
    var releaseTarget by remember { mutableStateOf<FamilyLink?>(null) }
    var removeTarget by remember { mutableStateOf<FamilyLink?>(null) }

    val me = selfId ?: return
    val children = links.filter {
        it.parentId == me && it.status == FamilyLinkStatus.VERIFIED && it.childAgeProfile == AgeProfile.MINOR
    }
    if (children.isEmpty()) return

    revokeTarget?.let { item ->
        val d = ConsentDiff.describe(item.request.copy(baseline = null), nameOf)
        ConfirmDialog(
            title = "Odvolat souhlas?",
            text = "${d.title}: dítě přestane sdílet podle tohoto souhlasu a bude muset požádat znovu. ${Labels.DELIVERED_DATA_NOTE}",
            confirmLabel = "Odvolat",
            danger = true,
            onConfirm = {
                revokeTarget = null
                scope.launch {
                    val res = withContext(Dispatchers.IO) { sl.shareService.revokeConsent(item.permissionId) }
                    onNotice(res.fold({ "Souhlas byl odvolán." }, { it.message ?: "Odvolání se nepodařilo." }))
                }
            },
            onDismiss = { revokeTarget = null },
        )
    }
    releaseTarget?.let { link ->
        ConfirmDialog(
            title = "Uvolnit ${nameOf(link.childId)} na dospělého?",
            text = "Dítě bude mít plnou kontrolu nad svým sdílením a váš dohled skončí. Tento krok nelze vrátit zpět.",
            confirmLabel = "Uvolnit",
            danger = true,
            onConfirm = {
                releaseTarget = null
                scope.launch {
                    val res = withContext(Dispatchers.IO) { sl.shareService.releaseAge(link.childId) }
                    onNotice(res.fold({ "Uvolnění bylo odesláno dítěti." }, { it.message ?: "Uvolnění se nepodařilo." }))
                }
            },
            onDismiss = { releaseTarget = null },
        )
    }
    removeTarget?.let { link ->
        ConfirmDialog(
            title = "Zrušit vaši vazbu s ${nameOf(link.childId)}?",
            text = "Odebere se jen vaše vazba. Druhý rodič zůstává a schválená sdílení zůstanou platná, dokud je neodvolá. " +
                "Pokud jste poslední rodič, dítěti se aplikace zamkne. Jiného rodiče odebrat nelze.",
            confirmLabel = "Zrušit vazbu",
            danger = true,
            onConfirm = {
                removeTarget = null
                scope.launch {
                    val res = withContext(Dispatchers.IO) { sl.shareService.revokeFamilyLink(link.id) }
                    onNotice(res.fold({ "Vaše vazba byla zrušena." }, { it.message ?: "Vazbu se nepodařilo zrušit." }))
                }
            },
            onDismiss = { removeTarget = null },
        )
    }

    children.forEach { link ->
        // newest approved version per subject; the guardian-add approvals are shown but can never be revoked
        val approved = items.filter {
            it.role == ConsentRole.GUARDIAN_IN && it.status == ConsentStatus.APPROVED && it.request.childId == link.childId
        }.groupBy { it.subject to it.subjectId }.values.map { rows -> rows.maxBy { it.permissionVersion } }
            .sortedBy { it.createdAtMs }
        PanelCard(Teal.copy(alpha = 0.4f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = Teal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(nameOf(link.childId), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
            Spacer(Modifier.height(6.dp))
            if (approved.isEmpty()) {
                Text("Zatím nemáte schválené žádné sdílení tohoto dítěte.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            approved.forEach { item ->
                val d = ConsentDiff.describe(item.request.copy(baseline = null), nameOf, planNameOf = { "tréninkový plán" })
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(d.title, fontWeight = FontWeight.Bold, color = Color.White)
                        d.lines.filter { it.kind != ConsentDiff.Kind.INFO }.forEach {
                            Text("${it.label}: ${it.text}", style = MaterialTheme.typography.labelSmall, color = Soft)
                        }
                    }
                    if (item.subject != ConsentSubject.GUARDIAN_ADD) {
                        TextButton(onClick = { revokeTarget = item }) { Text("Odvolat", color = Rose) }
                    } else {
                        StatusChip("Rodič", Teal)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { releaseTarget = link }, modifier = Modifier.weight(1f)) {
                    Text("Uvolnit na dospělého", fontSize = 12.sp, maxLines = 2)
                }
                OutlinedButton(
                    onClick = { removeTarget = link },
                    border = BorderStroke(1.dp, Rose.copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1f),
                ) { Text("Zrušit moji vazbu", color = Rose, fontSize = 12.sp, maxLines = 2) }
            }
            Text(
                "Každý rodič může odebrat jen sám sebe; druhého rodiče odebrat nelze.",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Settings tab "Rodina"
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun FamilySettingsSection(sl: ServiceLocator) {
    val ageState by sl.settings.ageStateFlow.collectAsState()
    var notice by remember { mutableStateOf<String?>(null) }
    val inboxCount = rememberGuardianInboxCount(sl)
    val selfId = rememberSelfId(sl)
    val links = rememberFamilyLinks(sl)
    val isGuardian = selfId != null && links.any {
        it.parentId == selfId && it.status == FamilyLinkStatus.VERIFIED && it.childAgeProfile == AgeProfile.MINOR
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
    ) {
        PanelCard {
            Text("Věkový profil", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(
                when (ageState) {
                    AgeState.ADULT -> "Dospělý (18+). Sdílení nepotřebuje schválení rodiče."
                    AgeState.MINOR -> "Dítě do 18 let. Každé sdílení a každý kruh schvaluje rodič. Na dospělého vás může uvolnit jen rodič."
                    AgeState.UNKNOWN -> "Zatím nezvoleno."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Soft,
            )
        }

        if (ageState == AgeState.MINOR) {
            ParentLinksPanel(sl = sl, gated = false, onNotice = { notice = it })
            ChildRequestsSection(sl = sl, onNotice = { notice = it })
        }

        if (isGuardian || inboxCount > 0) {
            Text(
                "Žádosti dětí",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (inboxCount == 0) {
                Text("Žádné čekající žádosti.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            GuardianInboxSection(sl, onNotice = { notice = it })
            MyChildrenSection(sl, onNotice = { notice = it })
        } else if (ageState == AgeState.ADULT) {
            PanelCard {
                Text("Chcete být rodičem dítěte?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text(
                    "Dítě si vás přidá samo (Nastavení → Rodina → Moji rodiče) a vy žádost potvrdíte v Kruhy → Sdílení a oprávnění → Žádosti. " +
                        "Případně dítě přidejte v Kruhy → Přidat dítě.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Soft,
                )
            }
        }

        notice?.let {
            Spacer(Modifier.height(8.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
        }
        Spacer(Modifier.height(10.dp))
        Text(Labels.DELIVERED_DATA_NOTE, style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.height(16.dp))
    }
}
