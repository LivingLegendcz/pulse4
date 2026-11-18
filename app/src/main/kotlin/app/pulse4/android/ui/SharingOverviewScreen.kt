@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import app.pulse4.ui.GrantEditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse4.android.data.ContactRow
import app.pulse4.android.data.PendingRequestRow
import app.pulse4.android.data.ReceivedRow
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.ui.theme.JetBrainsMonoFamily
import app.pulse4.domain.Provider
import app.pulse4.sharing.AgeProfile
import app.pulse4.sharing.Category
import app.pulse4.sharing.FamilyLink
import app.pulse4.sharing.Field
import app.pulse4.sharing.Group
import app.pulse4.sharing.MembershipPolicy
import app.pulse4.sharing.RevocationLimit
import app.pulse4.sharing.RevocationPlan
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.SharedProjection
import app.pulse4.sync.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SharingOverviewScreen(
    sl: ServiceLocator,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val contacts by sl.db.contacts().all().collectAsState(emptyList())
    val groups by sl.db.grants().groups().collectAsState(emptyList())
    val grants by sl.db.grants().all().collectAsState(emptyList())
    val receivedDaily by sl.db.inbox().objects("daily").collectAsState(emptyList())
    val receivedActs by sl.db.inbox().objects("activity").collectAsState(emptyList())
    val pendingRows by sl.db.pendingRequests().all().collectAsState(emptyList())

    val selfHash by io(Unit) { sl.bridge.ensureStarted().deliveryHash }
    val meId = selfHash ?: sl.bridge.info?.deliveryHash ?: "me"

    val decodedGroups = remember(groups) {
        groups.mapNotNull { runCatching { Wire.json.decodeFromString(Group.serializer(), it.json) }.getOrNull() }
    }
    val groupsById = remember(decodedGroups) { decodedGroups.associateBy { it.id } }
    val decodedGrants = remember(grants) {
        grants.mapNotNull { runCatching { Wire.json.decodeFromString(ShareGrant.serializer(), it.json) }.getOrNull() }
    }
    val myActiveGrants = remember(decodedGrants, meId) {
        decodedGrants.filter { it.active && it.ownerId == meId }
    }

    val pendingFamilyRequests = remember(pendingRows) {
        pendingRows.filter { it.status == "PENDING" && it.kind == "FAMILY_LINK_REQUEST" }
    }
    val pendingHandoverGroups = remember(decodedGroups, meId) {
        decodedGroups.filter { !it.dissolved && it.pendingAdminHandover?.targetMemberId == meId }
    }
    val awaitingConsentGrants = remember(myActiveGrants, groupsById) {
        myActiveGrants.mapNotNull { g ->
            val grp = g.groupId?.let { groupsById[it] } ?: return@mapNotNull null
            if (grp.dissolved) return@mapNotNull null
            val awaiting = MembershipPolicy.awaitingConsent(g, grp)
            if (awaiting.isEmpty()) null else Triple(g, grp, awaiting)
        }
    }
    val totalPendingCount = pendingFamilyRequests.size + pendingHandoverGroups.size + awaitingConsentGrants.size

    var selectedTab by remember { mutableStateOf(0) } // 0 = Sdílím s ostatními, 1 = Ostatní sdílejí se mnou, 2 = Čekající žádosti
    var notice by remember { mutableStateOf<String?>(null) }
    var revocationPlanDialog by remember { mutableStateOf<RevocationPlan?>(null) }
    var editingGrant by remember { mutableStateOf<ShareGrant?>(null) }

    revocationPlanDialog?.let { plan ->
        RevocationPlanDialog(
            plan = plan,
            contacts = contacts,
            onDismiss = { revocationPlanDialog = null }
        )
    }

    editingGrant?.let { grant ->
        EditGrantDialog(
            sl = sl,
            grant = grant,
            groupName = grant.groupId?.let { groupsById[it]?.name },
            contacts = contacts,
            onSaved = { updated ->
                editingGrant = null
                notice = "Oprávnění aktualizováno (${formatSourceLabel(updated.sources)})."
            },
            onError = { msg -> notice = msg },
            onDismiss = { editingGrant = null }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Hlavička obrazovky
        Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0F1F32))
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Zpět",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Sdílení a oprávnění",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            "Centrální přehled aktivních grantů, příchozích dat a čekajících žádostí",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 3 záložky
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val tabs = listOf(
                        Triple(0, "Sdílím (${myActiveGrants.size})", Icons.Outlined.Share),
                        Triple(1, "Sdílejí se mnou", Icons.Outlined.Download),
                        Triple(2, "Žádosti ($totalPendingCount)", Icons.Outlined.NotificationsActive)
                    )
                    tabs.forEach { (idx, label, icon) ->
                        val selected = selectedTab == idx
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.dp,
                                if (selected) Color(0xFF38BDF8) else Color(0xFF1E293B)
                            ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) Color(0xFF0E2942) else Color(0xFF0B121E)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedTab = idx }
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = if (selected) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = if (selected) Color.White else Color(0xFF94A3B8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        notice?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2942)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(msg, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
        }

        Spacer(Modifier.height(10.dp))

        var pendingRevokeGrant by remember { mutableStateOf<ShareGrant?>(null) }

        pendingRevokeGrant?.let { grantToRevoke ->
            val recNames = grantToRevoke.recipients.joinToString(", ") { id ->
                contacts.firstOrNull { it.identity == id }?.displayName ?: id.take(10)
            }
            AlertDialog(
                onDismissRequest = { pendingRevokeGrant = null },
                containerColor = Color(0xFF0A101B),
                titleContentColor = Color.White,
                textContentColor = Color(0xFFCBD5E1),
                title = { Text("Odvolat oprávnění?", fontWeight = FontWeight.ExtraBold) },
                text = {
                    Text(
                        "Sdílení pro $recNames bude okamžitě zastaveno a příjemci obdrží požadavek na smazání. Již doručené kopie v cizích zařízeních nelze technicky vynutit zpět.",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        pendingRevokeGrant = null
                        scope.launch {
                            val plan = withContext(Dispatchers.IO) {
                                sl.shareService.revoke(grantToRevoke.id, System.currentTimeMillis())
                            }
                            if (plan != null) {
                                revocationPlanDialog = plan
                            } else {
                                notice = "Oprávnění se nepodařilo odvolat."
                            }
                        }
                    }) { Text("Odvolat", fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRevokeGrant = null }) { Text("Zrušit", color = Color(0xFF94A3B8)) }
                }
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            when (selectedTab) {
                0 -> OutgoingGrantsTab(
                    grants = myActiveGrants,
                    groupsById = groupsById,
                    contacts = contacts,
                    onEdit = { editingGrant = it },
                    onRevoke = { grant -> pendingRevokeGrant = grant }
                )
                1 -> IncomingSharingTab(
                    contacts = contacts,
                    receivedDaily = receivedDaily,
                    receivedActs = receivedActs
                )
                else -> PendingRequestsTab(
                    sl = sl,
                    contacts = contacts,
                    pendingFamilyRequests = pendingFamilyRequests,
                    pendingHandoverGroups = pendingHandoverGroups,
                    awaitingConsentGrants = awaitingConsentGrants,
                    onNotice = { notice = it }
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OutgoingGrantsTab(
    grants: List<ShareGrant>,
    groupsById: Map<String, Group>,
    contacts: List<ContactRow>,
    onEdit: (ShareGrant) -> Unit,
    onRevoke: (ShareGrant) -> Unit,
) {
    if (grants.isEmpty()) {
        EmptyStateCard(
            title = "Zatím nesdílíte žádná data",
            subtitle = "Oprávnění můžete zapnout v detailu libovolného kruhu důvěry."
        )
        return
    }

    grants.forEach { grant ->
        val recipientNames = grant.recipients.map { id ->
            contacts.firstOrNull { it.identity == id }?.displayName ?: id.take(10)
        }
        val circleName = grant.groupId?.let { groupsById[it]?.name } ?: "Přímá vazba"
        val categories = grant.fields.map { it.category }.distinct()
        val sourceLabel = formatSourceLabel(grant.sources)

        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (recipientNames.isEmpty()) "Žádní příjemci" else recipientNames.joinToString(", "),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "$circleName · Zdroj: $sourceLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF38BDF8)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (grant.active) "Aktivní" else "Odvoláno",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (grant.active) Color(0xFF10B981) else Color(0xFFF43F5E)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { cat ->
                        val catColor = cat.unifiedColor()
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(catColor.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                categoryLabel(cat),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = catColor
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onEdit(grant) },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Upravit", color = Color(0xFF38BDF8))
                    }
                    OutlinedButton(
                        onClick = { onRevoke(grant) },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFF43F5E).copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = Color(0xFFF43F5E), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Odebrat", color = Color(0xFFF43F5E))
                    }
                }
            }
        }
    }
}

@Composable
private fun IncomingSharingTab(
    contacts: List<ContactRow>,
    receivedDaily: List<ReceivedRow>,
    receivedActs: List<ReceivedRow>,
) {
    val contactsById = remember(contacts) { contacts.associateBy { it.identity } }
    val (dailyByAuthor, actsByAuthor, authors) = remember(receivedDaily, receivedActs) {
        val dailyMap = receivedDaily.groupBy { it.author }
        val actsMap = receivedActs.groupBy { it.author }
        val allAuthors = (dailyMap.keys + actsMap.keys).toList()
        Triple(dailyMap, actsMap, allAuthors)
    }

    if (authors.isEmpty()) {
        EmptyStateCard(
            title = "Zatím nikdo nesdílí data s vámi",
            subtitle = "Jakmile vám ověřený kontakt nasdílí své metriky nebo aktivity, zobrazí se přehled zde."
        )
        return
    }

    authors.forEach { authorId ->
        key(authorId) {
            val contact = contactsById[authorId]
            val authorName = contact?.displayName ?: authorId.take(12)
            val authorDaily = dailyByAuthor[authorId].orEmpty()
            val authorActs = actsByAuthor[authorId].orEmpty()
            val decodedCategories by produceState<List<Category>>(
                initialValue = emptyList(),
                authorDaily,
                authorActs
            ) {
                value = withContext(Dispatchers.Default) {
                    val dailyCats = authorDaily.flatMap { row ->
                        runCatching {
                            Wire.json.decodeFromString(SharedProjection.serializer(), row.payload).fields.keys.map { it.category }
                        }.getOrDefault(emptyList())
                    }
                    val actCats = if (authorActs.isNotEmpty()) listOf(Category.ACTIVITY) else emptyList()
                    (dailyCats + actCats).distinct()
                }
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFF232A3B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Person, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    authorName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                Text(
                                    "${authorDaily.size} denních záznamů · ${authorActs.size} aktivit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF38BDF8).copy(alpha = 0.16f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "Aktivní příjem",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                        }
                    }

                    if (decodedCategories.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            decodedCategories.forEach { cat ->
                                val catColor = cat.unifiedColor()
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(catColor.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        categoryLabel(cat),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = catColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingRequestsTab(
    sl: ServiceLocator,
    contacts: List<ContactRow>,
    pendingFamilyRequests: List<PendingRequestRow>,
    pendingHandoverGroups: List<Group>,
    awaitingConsentGrants: List<Triple<ShareGrant, Group, Set<String>>>,
    onNotice: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isMinor by sl.settings.isMinorFlow.collectAsState()
    var pendingMinorAwaitingGrant by remember { mutableStateOf<Triple<ShareGrant, Group, Set<String>>?>(null) }

    if (pendingFamilyRequests.isEmpty() && pendingHandoverGroups.isEmpty() && awaitingConsentGrants.isEmpty()) {
        EmptyStateCard(
            title = "Žádné čekající žádosti",
            subtitle = "Všechny rodinné vazby, předání správy kruhů i potvrzení nových členů jsou vyřízené."
        )
        return
    }

    // 1. Čekající žádosti o rodinné propojení
    pendingFamilyRequests.forEach { req ->
        val link = remember(req) {
            runCatching { Wire.json.decodeFromString(FamilyLink.serializer(), req.json) }.getOrNull()
        }
        val senderName = contacts.firstOrNull { it.identity == req.fromId }?.displayName ?: req.fromId.take(12)
        val profileLabel = if (link?.childAgeProfile == AgeProfile.MINOR) "Dítě / nezletilý" else "Dospělý člen"

        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.45f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FamilyRestroom, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Žádost o rodinné propojení",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Od: $senderName · Profil: $profileLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCBD5E1)
                )
                Text(
                    "Potvrzením nevznikne automatické sdílení zdravotních dat — oprávnění se nastavují zvlášť.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    sl.shareService.respondFamilyLink(req.id, accept = true)
                                }
                                onNotice(
                                    if (res != null) "Rodinné propojení s '$senderName' potvrzeno."
                                    else "Žádost se nepodařilo potvrdit."
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Potvrdit", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    sl.shareService.respondFamilyLink(req.id, accept = false)
                                }
                                onNotice(
                                    if (res != null) "Žádost o rodinné propojení odmítnuta."
                                    else "Žádost se nepodařilo odmítnout."
                                )
                            }
                        },
                        border = BorderStroke(1.dp, Color(0xFFF43F5E).copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = null, tint = Color(0xFFF43F5E), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Odmítnout", color = Color(0xFFF43F5E))
                    }
                }
            }
        }
    }

    // 2. Čekající předání správy kruhu
    pendingHandoverGroups.forEach { grp ->
        val handover = grp.pendingAdminHandover ?: return@forEach
        val initiatorName = contacts.firstOrNull { it.identity == handover.initiatedBy }?.displayName
            ?: handover.initiatedBy.take(12)

        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.55f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Předání správy kruhu '${grp.name}'",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Dosavadní správce $initiatorName vám nabízí převzetí správy kruhu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCBD5E1)
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                sl.shareService.acceptAdminHandover(grp.id)
                            }
                            onNotice(
                                res.fold(
                                    onSuccess = { "Správa kruhu '${grp.name}' byla převzata." },
                                    onFailure = { it.message ?: "Převzetí správy selhalo." }
                                )
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B), contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Převzít správu", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }

    // 3. Noví členové kruhu čekající na potvrzení rozšíření sdílení (MembershipPolicy.awaitingConsent)
    awaitingConsentGrants.forEach { (grant, grp, awaitingIds) ->
        val awaitingNames = awaitingIds.map { id ->
            contacts.firstOrNull { it.identity == id }?.displayName ?: id.take(10)
        }
        val cats = grant.fields.map { categoryLabel(it.category) }.distinct().joinToString(", ")

        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.45f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Security, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Čekající souhlas v kruhu '${grp.name}'",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Noví členové bez přístupu k '$cats': ${awaitingNames.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCBD5E1)
                )
                Text(
                    "Přidání člena do kruhu automaticky nerozšiřuje vaše sdílení. Potvrďte, pokud jim chcete tato data zpřístupnit.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (isMinor) {
                            pendingMinorAwaitingGrant = Triple(grant, grp, awaitingIds)
                        } else {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    val updated = grant.copy(recipients = grant.recipients + awaitingIds)
                                    sl.shareService.saveGrantWithPolicy(updated, null).also {
                                        if (it.isSuccess) sl.shareService.planAndEnqueue(System.currentTimeMillis())
                                    }
                                }
                                onNotice(
                                    res.fold(
                                        onSuccess = { "Sdílení v kruhu '${grp.name}' rozšířeno pro nové členy." },
                                        onFailure = { it.message ?: "Nepodařilo se potvrdit rozšíření sdílení." }
                                    )
                                )
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Povolit sdílení novým členům", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    pendingMinorAwaitingGrant?.let { (grant, grp, awaitingIds) ->
        var guardianChecked by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingMinorAwaitingGrant = null },
            containerColor = Color(0xFF0A101B),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCBD5E1),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Security, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Souhlas zákonného zástupce", fontWeight = FontWeight.ExtraBold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Tento účet má profil nezletilého (MINOR). Rozšíření sdílení v kruhu '${grp.name}' pro nové členy vyžaduje výslovný souhlas zákonného zástupce.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = guardianChecked, onCheckedChange = { guardianChecked = it })
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Potvrzuji souhlas zákonného zástupce s rozšířením tohoto sdílení.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFDE68A)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = guardianChecked,
                    onClick = {
                        if (!guardianChecked) return@Button
                        pendingMinorAwaitingGrant = null
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                val updated = grant.copy(recipients = grant.recipients + awaitingIds)
                                val now = System.currentTimeMillis()
                                sl.shareService.saveGrantWithPolicy(updated, now).also {
                                    if (it.isSuccess) sl.shareService.planAndEnqueue(now)
                                }
                            }
                            onNotice(
                                res.fold(
                                    onSuccess = { "Sdílení v kruhu '${grp.name}' rozšířeno pro nové členy (se souhlasem zákonného zástupce)." },
                                    onFailure = { it.message ?: "Nepodařilo se potvrdit rozšíření sdílení." }
                                )
                            )
                        }
                    }
                ) {
                    Text("Potvrdit a povolit", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMinorAwaitingGrant = null }) {
                    Text("Zrušit", color = Color(0xFF94A3B8))
                }
            }
        )
    }
}

@Composable
private fun RevocationPlanDialog(
    plan: RevocationPlan,
    contacts: List<ContactRow>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A101B),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFCBD5E1),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sdílení odvoláno", fontWeight = FontWeight.ExtraBold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "• Budoucí synchronizace i neodeslané zprávy ve frontě pro toto oprávnění byly okamžitě zablokovány.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
                Text(
                    "• Odesláno požadavků na odstranění doručených kopií: ${plan.removalRequests.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF38BDF8),
                    fontFamily = JetBrainsMonoFamily
                )
                if (plan.removalRequests.isNotEmpty()) {
                    val recipients = plan.removalRequests.map { rq ->
                        contacts.firstOrNull { it.identity == rq.recipient }?.displayName ?: rq.recipient.take(10)
                    }.distinct()
                    Text(
                        "Příjemci požadavku: ${recipients.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
                plan.limits.forEach { limit ->
                    val text = when (limit) {
                        RevocationLimit.ALREADY_DELIVERED_CANNOT_BE_RECALLED ->
                            "Již doručené kopie v zařízeních příjemců nelze technicky vynutit zpět — aplikace příjemce obdrží požadavek na jejich smazání."
                        RevocationLimit.MESSAGES_ON_FOREIGN_NODE_MAY_REMAIN ->
                            "Zprávy již uložené na cizím propagačním uzlu tam mohou dočasně zůstat."
                        RevocationLimit.OFFLINE_RECIPIENT_LEARNS_LATER ->
                            "Offline příjemce se o odvolání dozví při příštím spojení."
                    }
                    Text("• $text", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF59E0B))
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Rozumím", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun EditGrantDialog(
    sl: ServiceLocator,
    grant: ShareGrant,
    groupName: String?,
    contacts: List<ContactRow>,
    onSaved: (ShareGrant) -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isMinor by sl.settings.isMinorFlow.collectAsState()
    var guardianConfirmed by remember(isMinor) { mutableStateOf(!isMinor) }
    var selectedSourceChoice by remember(grant.id) {
        mutableStateOf(GrantEditor.sourceChoiceFrom(grant.sources))
    }
    var sourceModified by remember(grant.id) { mutableStateOf(false) }
    var selectedCategories by remember(grant.id) {
        mutableStateOf(grant.fields.map { it.category }.toSet())
    }
    var activityDetailsModified by remember(grant.id) { mutableStateOf(false) }
    var includeActRoute by remember(grant.id) { mutableStateOf(Field.ACT_ROUTE in grant.fields) }
    var includeActExactTime by remember(grant.id) { mutableStateOf(Field.ACT_EXACT_TIME in grant.fields) }
    var includeActFullRecord by remember(grant.id) { mutableStateOf(Field.ACT_FULL_RECORD in grant.fields) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A101B),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFCBD5E1),
        title = {
            Text(
                "Upravit oprávnění (${groupName ?: "Přímá vazba"})",
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Zdroj zdravotních dat:", style = MaterialTheme.typography.labelMedium, color = Color.White)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GrantEditor.SourceChoice.entries.forEach { choice ->
                        FilterChip(
                            selected = selectedSourceChoice == choice,
                            onClick = {
                                selectedSourceChoice = choice
                                sourceModified = true
                            },
                            label = { Text(choice.label) }
                        )
                    }
                }

                Text("Sdílené kategorie:", style = MaterialTheme.typography.labelMedium, color = Color.White)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Category.entries.forEach { cat ->
                        FilterChip(
                            selected = cat in selectedCategories,
                            onClick = {
                                selectedCategories = if (cat in selectedCategories) selectedCategories - cat else selectedCategories + cat
                            },
                            label = { Text(categoryLabel(cat)) }
                        )
                    }
                }

                if (Category.ACTIVITY in selectedCategories) {
                    Text(
                        "Citlivé detaily aktivit (výchozí je pouze souhrn):",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFDE68A)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeActRoute, onCheckedChange = { includeActRoute = it; activityDetailsModified = true })
                        Text("GPS trasa (může odhalit polohu)", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeActExactTime, onCheckedChange = { includeActExactTime = it; activityDetailsModified = true })
                        Text("Přesný čas startu a konce", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeActFullRecord, onCheckedChange = { includeActFullRecord = it; activityDetailsModified = true })
                        Text("Úplný FIT záznam (obsahuje přesný čas)", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (isMinor) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1607))
                    ) {
                        Row(
                            Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = guardianConfirmed,
                                onCheckedChange = { guardianConfirmed = it }
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Účet nezletilého: potvrzuji souhlas zákonného zástupce s úpravou tohoto sdílení.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFDE68A)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedCategories.isNotEmpty() && guardianConfirmed,
                onClick = {
                    if (isMinor && !guardianConfirmed) return@Button
                    val explicitAct = if (activityDetailsModified) {
                        buildSet<Field> {
                            if (Field.ACT_CHARTS in grant.fields) add(Field.ACT_CHARTS)
                            if (includeActRoute) add(Field.ACT_ROUTE)
                            if (includeActExactTime) add(Field.ACT_EXACT_TIME)
                            if (includeActFullRecord) add(Field.ACT_FULL_RECORD)
                        }
                    } else {
                        null
                    }
                    val updated = GrantEditor.editGrant(
                        existing = grant,
                        selectedCategories = selectedCategories,
                        selectedSourceChoice = selectedSourceChoice,
                        sourceModified = sourceModified,
                        explicitActivityFields = explicitAct,
                    )
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            val consentTs = if (isMinor && guardianConfirmed) System.currentTimeMillis() else null
                            sl.shareService.saveGrantWithPolicy(updated, consentTs).also {
                                if (it.isSuccess) sl.shareService.planAndEnqueue(System.currentTimeMillis())
                            }
                        }
                        res.fold(
                            onSuccess = { onSaved(it) },
                            onFailure = { onError(it.message ?: "Uložení oprávnění selhalo.") }
                        )
                    }
                }
            ) {
                Text("Uložit změny", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Zrušit", color = Color(0xFF94A3B8))
            }
        }
    )
}

@Composable
private fun EmptyStateCard(title: String, subtitle: String) {
    Card(
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFF232A3B)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
        }
    }
}

private fun formatSourceLabel(sources: Set<Provider>?): String = when {
    sources == null -> "Všechny zdroje"
    Provider.WITHINGS_API in sources && Provider.GARMIN_PERSONAL !in sources -> "Withings"
    Provider.GARMIN_PERSONAL in sources && Provider.WITHINGS_API !in sources -> "Garmin"
    else -> sources.joinToString(", ") { it.name }
}

private fun categoryLabel(cat: Category): String = when (cat) {
    Category.SLEEP -> "Spánek"
    Category.HRV -> "HRV"
    Category.BODY_BATTERY -> "Body Battery"
    Category.HEART_RATE -> "Klidový tep"
    Category.ACTIVITY -> "Aktivity"
    Category.BODY_COMPOSITION -> "Váha & Tělo"
}
