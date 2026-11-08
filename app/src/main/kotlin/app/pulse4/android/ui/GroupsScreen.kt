@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import app.pulse4.domain.czechName

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pulse4.android.data.ContactRow
import app.pulse4.android.data.ReceivedRow
import app.pulse4.android.data.toDomain
import app.pulse4.android.di.ServiceLocator
import app.pulse4.domain.Activity
import app.pulse4.domain.MetricType
import app.pulse4.domain.Sport
import app.pulse4.sharing.ActivityProjection
import app.pulse4.sharing.Category
import app.pulse4.sharing.Field
import app.pulse4.sharing.Group
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.SharedProjection
import app.pulse4.sharing.TimeScope
import app.pulse4.sync.Kinds
import app.pulse4.sync.MessageBuilder
import app.pulse4.sync.OutboxItem
import app.pulse4.sync.Pairing
import app.pulse4.sync.PairingPayload
import app.pulse4.sync.PairingResult
import app.pulse4.sync.Wire
import app.pulse4.ui.Aggregation
import app.pulse4.ui.Labels
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

private const val ALL_FRIENDS_GROUP_ID = "__ALL_FRIENDS__"

fun qrBitmap(text: String, size: Int = 600): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
        for (x in 0 until size) for (y in 0 until size) b.setPixel(x, y, if (m[x, y]) AColor.BLACK else AColor.WHITE)
    }
}

/**
 * Stromová architektura obrazovky Skupiny (`GroupsScreen`):
 *
 * ÚROVEŇ 1 (Kořen stromu — Seznam skupin & kontaktů, když `openedGroupId == null`):
 * - Přehledný seznam skupin (karty jednotlivých skupin s výčtem členů, sdílených kategorií a počtem aktivit)
 * - Tlačítka "Nová skupina" a "QR párování"
 * - Sekce ověřených P2P kontaktů
 *
 * ÚROVEŇ 2 (Detail rozkliknuté skupiny, když `openedGroupId != null`):
 * - Drobečková navigace "Skupiny · Název skupiny"
 * - Rozdělení dat rozkliknuté skupiny do 3 samostatných tabů:
 *   0 -> Sdílené metriky (společný Grafana graf členů skupiny + žebříček a statistiky)
 *   1 -> Sdílené aktivity (feed aktivit členů skupiny + Compare porovnávání)
 *   2 -> Členové & Co sdílím (správa členů této skupiny a přepínače kategorií, které do skupiny sdílím)
 */
@Composable
fun GroupsScreen(
    sl: ServiceLocator,
    onOpenActivity: (String) -> Unit = {},
    onCompareActivities: (List<String>) -> Unit = {},
) {
    val contacts by sl.db.contacts().all().collectAsState(emptyList())
    val groups by sl.db.grants().groups().collectAsState(emptyList())
    val grants by sl.db.grants().all().collectAsState(emptyList())
    val receivedDaily by sl.db.inbox().objects("daily").collectAsState(emptyList())
    val receivedActs by sl.db.inbox().objects("activity").collectAsState(emptyList())
    val myActs by sl.db.activities().recentSummaries(20).collectAsState(emptyList())

    val decodedGroups = remember(groups) {
        groups.mapNotNull { runCatching { Wire.json.decodeFromString(Group.serializer(), it.json) }.getOrNull() }
    }
    val activeGrants = remember(grants) {
        grants.mapNotNull { runCatching { Wire.json.decodeFromString(ShareGrant.serializer(), it.json) }.getOrNull() }
            .filter { it.active }
    }

    val selfHash by io(Unit) { sl.bridge.ensureStarted().deliveryHash }
    val meId = selfHash ?: sl.bridge.info?.deliveryHash ?: "me"

    // null = Jsme v kořeni stromu (Seznam skupin); jinak ID rozkliknuté skupiny (nebo ALL_FRIENDS_GROUP_ID)
    var openedGroupId by remember { mutableStateOf<String?>(null) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var showNewGroupDialog by remember { mutableStateOf(false) }

    if (showPairingDialog) {
        PairingModalDialog(sl = sl, onDismiss = { showPairingDialog = false })
    }

    if (showNewGroupDialog) {
        CreateGroupModalDialog(
            sl = sl,
            contacts = contacts,
            onCreated = { newGroupId ->
                showNewGroupDialog = false
                openedGroupId = newGroupId
            },
            onDismiss = { showNewGroupDialog = false }
        )
    }

    if (openedGroupId == null) {
        // ÚROVEŇ 1: Kořen stromu — Seznam skupin + Přidat skupinu + Kontakty
        GroupsTreeRootView(
            contacts = contacts,
            decodedGroups = decodedGroups,
            activeGrants = activeGrants,
            receivedDaily = receivedDaily,
            receivedActs = receivedActs,
            meId = meId,
            onOpenGroup = { gId -> openedGroupId = gId },
            onOpenNewGroup = { showNewGroupDialog = true },
            onOpenPairing = { showPairingDialog = true }
        )
    } else {
        // ÚROVEŇ 2: Detail rozkliknuté skupiny (Metriky | Aktivity | Členové & Sdílení)
        val currentGroup = decodedGroups.firstOrNull { it.id == openedGroupId }
        GroupDetailBranchView(
            sl = sl,
            groupId = openedGroupId!!,
            currentGroup = currentGroup,
            meId = meId,
            contacts = contacts,
            activeGrants = activeGrants,
            receivedDaily = receivedDaily,
            receivedActs = receivedActs,
            myActs = myActs,
            onBackToTree = { openedGroupId = null },
            onOpenPairing = { showPairingDialog = true },
            onOpenActivity = onOpenActivity,
            onCompareActivities = onCompareActivities
        )
    }
}

/**
 * ÚROVEŇ 1 (Kořen stromu):
 * Přehledný seznam všech skupin s možností přidat novou skupinu, spárovat kontakt a rozkliknout libovolnou skupinu.
 */
@Composable
private fun GroupsTreeRootView(
    contacts: List<ContactRow>,
    decodedGroups: List<Group>,
    activeGrants: List<ShareGrant>,
    receivedDaily: List<ReceivedRow>,
    receivedActs: List<ReceivedRow>,
    meId: String,
    onOpenGroup: (String) -> Unit,
    onOpenNewGroup: () -> Unit,
    onOpenPairing: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Hlavička stránky Skupiny
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Skupiny & Sdílení",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Text(
                    "Vyberte skupinu pro zobrazení sdílených metrik a aktivit",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onOpenPairing) {
                    Icon(Icons.Outlined.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("QR párování", style = MaterialTheme.typography.labelMedium)
                }
                Button(onClick = onOpenNewGroup) {
                    Icon(Icons.Outlined.GroupAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nová skupina", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Sekce 1: Strom skupin
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "MOJE SKUPINY (${decodedGroups.size + 1})",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8)
            )
        }
        Spacer(Modifier.height(6.dp))

        // Speciální souhrnná větev: Všichni spárovaní přátelé
        val allParticipantIds = remember(contacts, receivedDaily, meId) {
            (listOf(meId) + contacts.map { it.identity } + receivedDaily.map { it.author }).distinct()
        }
        GroupTreeFolderCard(
            icon = Icons.Outlined.Public,
            title = "Všichni spárovaní přátelé",
            subtitle = "Souhrnný přehled všech vašich P2P kontaktů",
            memberNames = allParticipantIds.map { id ->
                if (id == meId) "Já" else contacts.firstOrNull { it.identity == id }?.displayName ?: id.take(8)
            },
            sharedDailyCount = receivedDaily.map { it.author }.distinct().size,
            sharedActivitiesCount = receivedActs.size,
            activeCategoriesCount = activeGrants.flatMap { it.fields }.map { it.category }.distinct().size,
            accentColor = Color(0xFF38BDF8),
            onClick = { onOpenGroup(ALL_FRIENDS_GROUP_ID) }
        )

        // Jednotlivé uživatelské skupiny
        decodedGroups.forEachIndexed { idx, g ->
            val palette = listOf(Color(0xFF10B981), Color(0xFFA855F7), Color(0xFFF59E0B), Color(0xFFEC4899))
            val color = palette[idx % palette.size]
            val memberNames = g.members.map { id ->
                if (id == meId) "Já" else contacts.firstOrNull { it.identity == id }?.displayName ?: id.take(8)
            }
            val groupDailyAuthors = receivedDaily.filter { it.author in g.members }.map { it.author }.distinct().size
            val groupActsCount = receivedActs.count { it.author in g.members }
            val groupGrants = activeGrants.filter { it.groupId == g.id || it.recipients.intersect(g.members).isNotEmpty() }
            val groupCatsCount = groupGrants.flatMap { it.fields }.map { it.category }.distinct().size

            GroupTreeFolderCard(
                icon = Icons.Outlined.Group,
                title = g.name,
                subtitle = "Skupina · verze ${g.version}",
                memberNames = memberNames,
                sharedDailyCount = groupDailyAuthors,
                sharedActivitiesCount = groupActsCount,
                activeCategoriesCount = groupCatsCount,
                accentColor = color,
                onClick = { onOpenGroup(g.id) }
            )
        }

        // Karta pro rychlé vytvoření nové skupiny
        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0E131F)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clickable { onOpenNewGroup() }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFF38BDF8).copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Vytvořit novou skupinu", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                        Text("Např. Rodina, Běžecký oddíl, Trenér…", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                }
                Text("›", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF64748B))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Sekce 2: Ověřené P2P kontakty v kořeni stromu
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.People, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "OVĚŘENÉ P2P KONTAKTY (${contacts.size})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
                )
            }
            TextButton(onClick = onOpenPairing) {
                Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Spárovat nový kontakt")
            }
        }

        if (contacts.isEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Zatím nemáte žádné spárované kontakty", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Klepněte na 'QR párování' pro zobrazení vašeho párovacího QR kódu nebo načtení kódu přítele.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        } else {
            contacts.forEach { c ->
                val hasSentData = receivedDaily.any { it.author == c.identity } || receivedActs.any { it.author == c.identity }
                Card(
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFF10B981).copy(alpha = 0.16f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(c.displayName.take(1).uppercase(), fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(c.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Otisk: ${c.fingerprint}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                            }
                        }
                        val badgeColor = if (hasSentData) Color(0xFF38BDF8) else Color(0xFF10B981)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                if (hasSentData) Icons.Outlined.Share else Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = badgeColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (hasSentData) "Aktivní P2P" else "Ověřeno",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun GroupTreeFolderCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    memberNames: List<String>,
    sharedDailyCount: Int,
    sharedActivitiesCount: Int,
    activeCategoriesCount: Int,
    accentColor: Color,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(accentColor.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
                    Text(
                        "Otevřít",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        " ›",
                        style = MaterialTheme.typography.headlineSmall,
                        color = accentColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Výčet členů skupiny
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Group, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Členové (${memberNames.size}): ${memberNames.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE2E8F0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(6.dp))

            // Souhrnné štítky sdílených dat ve skupině (FlowRow zabrání zalomení textu uvnitř štítku)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Equalizer, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Metriky od $sharedDailyCount přátel",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF38BDF8),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.DirectionsRun, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "$sharedActivitiesCount sdílených aktivit",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF10B981),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                if (activeCategoriesCount > 0) {
                    Box(
                        modifier = Modifier
                            .background(accentColor.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Share, contentDescription = null, tint = accentColor, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Sdílím $activeCategoriesCount sekcí",
                                style = MaterialTheme.typography.labelSmall,
                                color = accentColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * ÚROVEŇ 2 (Detail rozkliknuté skupiny):
 * Po rozkliknutí skupiny ze stromu zobrazí drobečkovou navigaci a rozdělí data skupiny na:
 * 0 -> Sdílené metriky (společný graf + žebříček členů skupiny)
 * 1 -> Sdílené aktivity (aktivity členů této skupiny + Compare)
 * 2 -> Členové & Co sdílím (správa členů a kategorií sdílených do této skupiny)
 */
@Composable
private fun GroupDetailBranchView(
    sl: ServiceLocator,
    groupId: String,
    currentGroup: Group?,
    meId: String,
    contacts: List<ContactRow>,
    activeGrants: List<ShareGrant>,
    receivedDaily: List<ReceivedRow>,
    receivedActs: List<ReceivedRow>,
    myActs: List<app.pulse4.android.data.ActivitySummaryRow>,
    onBackToTree: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenActivity: (String) -> Unit,
    onCompareActivities: (List<String>) -> Unit,
) {
    var activeTab by remember { mutableStateOf(0) } // 0 = Metriky, 1 = Aktivity, 2 = Členové & Sdílení

    val participantIds = remember(currentGroup, contacts, meId, receivedDaily) {
        if (currentGroup != null) {
            currentGroup.members.toList()
        } else {
            (listOf(meId) + contacts.map { it.identity } + receivedDaily.map { it.author }).distinct()
        }
    }

    val groupTitle = currentGroup?.name ?: "Všichni spárovaní přátelé"
    val memberNames = remember(participantIds, contacts, meId) {
        participantIds.map { id ->
            if (id == meId) "Já" else contacts.firstOrNull { it.identity == id }?.displayName ?: id.take(8)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Drobečková hlavička rozkliknuté skupiny
        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBackToTree) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Zpět na skupiny",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Skupiny",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Group, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                groupTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            "${participantIds.size} členů: ${memberNames.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 3 samostatné záložky uvnitř rozkliknuté skupiny
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val branchTabs = listOf(
                        Triple(0, "Metriky", Icons.Outlined.Equalizer),
                        Triple(1, "Aktivity", Icons.AutoMirrored.Outlined.DirectionsRun),
                        Triple(2, "Členové", Icons.Outlined.People)
                    )
                    branchTabs.forEach { (idx, label, icon) ->
                        FilterChip(
                            selected = activeTab == idx,
                            onClick = { activeTab = idx },
                            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = {
                                Text(
                                    label,
                                    fontWeight = if (activeTab == idx) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        when (activeTab) {
            0 -> GroupMetricsComparisonSection(
                sl = sl,
                groupTitle = groupTitle,
                participantIds = participantIds,
                meId = meId,
                contacts = contacts,
                receivedDaily = receivedDaily
            )
            1 -> GroupSharedActivitiesSection(
                contacts = contacts,
                participantIds = participantIds,
                receivedActs = receivedActs,
                myActs = myActs,
                onOpenActivity = onOpenActivity,
                onCompareActivities = onCompareActivities
            )
            else -> GroupBranchMembersAndSharingSection(
                sl = sl,
                groupId = groupId,
                currentGroup = currentGroup,
                meId = meId,
                participantIds = participantIds,
                contacts = contacts,
                activeGrants = activeGrants,
                onOpenPairing = onOpenPairing,
                onBackToTree = onBackToTree
            )
        }
    }
}

/**
 * Sekce 0 uvnitř rozkliknuté skupiny: Sdílené metriky (Grafana Multi-Series graf + žebříček členů).
 */
@Composable
private fun GroupMetricsComparisonSection(
    sl: ServiceLocator,
    groupTitle: String,
    participantIds: List<String>,
    meId: String,
    contacts: List<ContactRow>,
    receivedDaily: List<ReceivedRow>,
) {
    val units by sl.settings.unitSystemFlow.collectAsState()
    var activeField by remember { mutableStateOf(Field.HRV_DAILY) }
    var windowDays by remember { mutableStateOf(7) }

    val palette = listOf(
        Color(0xFF10B981), // Emerald (Já)
        Color(0xFF38BDF8), // Cyan
        Color(0xFFF59E0B), // Amber
        Color(0xFFEC4899), // Pink
        Color(0xFFA855F7), // Violet
        Color(0xFF22D3EE)  // Light Cyan
    )

    val metricTypeForField = when (activeField) {
        Field.WEIGHT -> MetricType.WEIGHT
        Field.SLEEP_DURATION -> MetricType.SLEEP_DURATION
        Field.HRV_DAILY -> MetricType.HRV
        Field.BB_LAST -> MetricType.BODY_BATTERY
        Field.HR_DAILY -> MetricType.RESTING_HEART_RATE
        else -> MetricType.STEPS
    }

    val unitSuffix = when (activeField) {
        Field.WEIGHT -> fmt.weightUnit()
        Field.SLEEP_DURATION -> "h"
        Field.HRV_DAILY -> "ms"
        Field.BB_LAST -> "b."
        Field.HR_DAILY -> "bpm"
        else -> ""
    }

    val myDataByDate by io(Triple(activeField, windowDays, units)) {
        val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
        val obs = if (metricTypeForField == MetricType.WEIGHT) {
            runCatching { sl.withings.trend(MetricType.WEIGHT, windowDays) }.getOrDefault(emptyList())
        } else {
            sl.db.observations().forRange(today.minusDays((windowDays - 1).toLong()).toString(), today.toString()).map { it.toDomain() }
        }
        val dates = (0 until windowDays).map { today.minusDays((windowDays - 1 - it).toLong()).toString() }
        val byDate = obs.filter { it.type == metricTypeForField && !it.deleted }.groupBy { it.localDate }
        dates to dates.associateWith { d -> Aggregation.value(metricTypeForField, byDate[d].orEmpty()) }
    }

    val datesAxis = myDataByDate?.first ?: (0 until windowDays).map {
        LocalDate.now().minusDays((windowDays - 1 - it).toLong()).toString()
    }
    val myMap = myDataByDate?.second.orEmpty()

    val multiSeries = remember(participantIds, receivedDaily, myMap, datesAxis, activeField, contacts, meId, units) {
        participantIds.mapIndexed { idx, memberId ->
            val isMe = memberId == meId
            val name = if (isMe) "Já" else contacts.firstOrNull { it.identity == memberId }?.displayName ?: memberId.take(8)
            val color = palette[idx % palette.size]

            val valuesForDates: List<Double?> = if (isMe) {
                datesAxis.map { d ->
                    val raw = myMap[d]
                    when (activeField) {
                        Field.SLEEP_DURATION -> raw?.div(3600.0)
                        Field.WEIGHT -> raw?.let(fmt::convertWeight)
                        else -> raw
                    }
                }
            } else {
                val memberRows = receivedDaily.filter { it.author == memberId }
                val byDate = memberRows.mapNotNull { r ->
                    runCatching { Wire.json.decodeFromString(SharedProjection.serializer(), r.payload) }.getOrNull()
                }.associateBy { it.date }

                datesAxis.map { d ->
                    val sp = byDate[d]
                    val raw = (sp?.fields?.get(activeField) as? JsonPrimitive)?.doubleOrNull
                    when (activeField) {
                        Field.SLEEP_DURATION -> raw?.div(3600.0)
                        Field.WEIGHT -> raw?.let(fmt::convertWeight)
                        else -> raw
                    }
                }
            }

            SeriesItem(
                id = memberId,
                label = name,
                color = color,
                values = valuesForDates,
                unit = unitSuffix
            )
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        // Výběr metriky a časového okna
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                Triple(Field.HRV_DAILY, "HRV", Icons.Outlined.MonitorHeart),
                Triple(Field.SLEEP_DURATION, "Spánek", Icons.Outlined.Bedtime),
                Triple(Field.BB_LAST, "Body Battery", Icons.Outlined.Bolt),
                Triple(Field.HR_DAILY, "Klidový tep", Icons.Outlined.Favorite),
                Triple(Field.WEIGHT, "Váha", Icons.Outlined.Scale)
            ).forEach { (f, label, icon) ->
                FilterChip(
                    selected = activeField == f,
                    onClick = { activeField = f },
                    leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text(label) }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Časové okno porovnání",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(7, 14, 30).forEach { d ->
                    FilterChip(
                        selected = windowDays == d,
                        onClick = { windowDays = d },
                        label = { Text("${d}d") }
                    )
                }
            }
        }

        // Společný Grafana MultiSeries graf členů skupiny
        MultiSeriesLineChart(
            series = multiSeries,
            xLabels = datesAxis.map { it.takeLast(5) },
            title = "$groupTitle · ${windowDays} dní"
        )

        Spacer(Modifier.height(8.dp))
        SectionTitle("Žebříček a statistiky členů skupiny (${participantIds.size})", icon = Icons.Outlined.EmojiEvents)

        val rankedParticipants = remember(participantIds, multiSeries, activeField) {
            participantIds.mapIndexed { idx, memberId ->
                val seriesItem = multiSeries.firstOrNull { it.id == memberId }
                val finiteVals = seriesItem?.values?.filterNotNull().orEmpty()
                val latestVal = finiteVals.lastOrNull()
                val avgVal = if (finiteVals.isNotEmpty()) finiteVals.average() else null
                Triple(idx, memberId, Pair(latestVal, avgVal))
            }.sortedWith(
                compareByDescending<Triple<Int, String, Pair<Double?, Double?>>> { it.third.first != null }
                    .thenBy {
                        val v = it.third.first ?: 0.0
                        if (activeField == Field.HR_DAILY) v else -v
                    }
            )
        }

        rankedParticipants.forEachIndexed { rankIdx, (origIdx, memberId, stats) ->
            val isMe = memberId == meId
            val contact = contacts.firstOrNull { it.identity == memberId }
            val memberName = if (isMe) "Já (vlastník)" else contact?.displayName ?: (memberId.take(8) + "…")
            val memberColor = palette[origIdx % palette.size]
            val seriesItem = multiSeries.firstOrNull { it.id == memberId }
            val finiteVals = seriesItem?.values?.filterNotNull().orEmpty()
            val latestVal = stats.first
            val avgVal = stats.second

            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, memberColor.copy(alpha = 0.4f)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(memberColor.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "#${rankIdx + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = memberColor
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(memberColor, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    memberName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Text(
                                if (isMe) "Moje lokální měření"
                                else if (finiteVals.isNotEmpty()) "P2P synchronizováno (${finiteVals.size}/${windowDays} dní)"
                                else "Čeká na sdílená P2P data",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        val formattedLatest = if (latestVal != null) {
                            formatGroupFieldValue(activeField, latestVal)
                        } else "—"
                        Text(
                            formattedLatest,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (latestVal != null) memberColor else Color(0xFF64748B)
                        )
                        if (avgVal != null && finiteVals.size >= 2) {
                            Text(
                                "Ø ${formatGroupFieldValue(activeField, avgVal)} · Min ${fmt.number(finiteVals.min(), 1)} / Max ${fmt.number(finiteVals.max(), 1)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun formatGroupFieldValue(field: Field, value: Double): String = when (field) {
    Field.SLEEP_DURATION -> fmt.sleep(value * 3600.0)
    Field.WEIGHT -> "${fmt.number(value, 1)} ${fmt.weightUnit()}"
    Field.HRV_DAILY -> "${fmt.number(value, 0)} ms"
    Field.HR_DAILY -> "${fmt.number(value, 0)} bpm"
    Field.BB_LAST -> "${fmt.number(value, 0)} b."
    else -> fmt.number(value, 0)
}

/**
 * Sekce 1 uvnitř rozkliknuté skupiny: Dedikovaný feed společných aktivit členů skupiny + Compare.
 */
@Composable
private fun GroupSharedActivitiesSection(
    contacts: List<ContactRow>,
    participantIds: List<String>,
    receivedActs: List<ReceivedRow>,
    myActs: List<app.pulse4.android.data.ActivitySummaryRow>,
    onOpenActivity: (String) -> Unit,
    onCompareActivities: (List<String>) -> Unit,
) {
    var filterMode by remember { mutableStateOf(0) } // 0 = Vše ve skupině, 1 = Od členů skupiny, 2 = Moje
    var selectedActsForCompare by remember { mutableStateOf(setOf<String>()) }

    val allActivities = remember(myActs, receivedActs, contacts, participantIds) {
        val shared = receivedActs.filter { it.author in participantIds }.mapNotNull { r ->
            val p = runCatching { Wire.json.decodeFromString(ActivityProjection.serializer(), r.payload) }.getOrNull() ?: return@mapNotNull null
            val sp = runCatching { Sport.valueOf(p.sport) }.getOrDefault(Sport.OTHER)
            val cName = contacts.firstOrNull { it.identity == r.author }?.displayName ?: r.author.take(8)
            UnifiedActivityItem(
                key = "shared:${r.author}:${r.objectId}",
                sport = sp,
                title = "${sp.czechName()} · $cName",
                ownerName = cName,
                isShared = true,
                startUtcMs = p.startUtcMs,
                localDate = p.localDate,
                distanceM = p.distanceM,
                movingTimeS = p.movingTimeS ?: p.totalTimeS,
                ascentM = p.ascentM,
                avgHeartRate = p.avgHeartRate,
                maxHeartRate = null
            )
        }
        val mine = myActs.take(15).mapNotNull { row ->
            if (row.title != null) {
                val sp = runCatching { Sport.valueOf(row.sport) }.getOrDefault(Sport.OTHER)
                UnifiedActivityItem(
                    key = row.id,
                    sport = sp,
                    title = "${row.title} · Já",
                    ownerName = "Já",
                    isShared = false,
                    startUtcMs = row.startUtcMs,
                    localDate = Instant.ofEpochMilli(row.startUtcMs).atZone(ZoneId.of(row.zoneId ?: ZoneId.systemDefault().id)).toLocalDate().toString(),
                    distanceM = row.distanceM,
                    movingTimeS = row.movingTimeS,
                    ascentM = row.ascentM,
                    avgHeartRate = row.avgHeartRate,
                    maxHeartRate = row.maxHeartRate,
                    caloriesKcal = row.caloriesKcal,
                    aerobicTe = row.aerobicTe,
                    trainingLoad = row.trainingLoad
                )
            } else {
                val a = row.json?.let { js -> runCatching { Wire.json.decodeFromString(Activity.serializer(), js) }.getOrNull() } ?: return@mapNotNull null
                UnifiedActivityItem(
                    key = a.id,
                    sport = a.sport,
                    title = "${a.title ?: a.sport.czechName()} · Já",
                    ownerName = "Já",
                    isShared = false,
                    startUtcMs = a.startUtcMs,
                    localDate = Instant.ofEpochMilli(a.startUtcMs).atZone(ZoneId.of(a.zoneId)).toLocalDate().toString(),
                    distanceM = a.distanceM,
                    movingTimeS = a.movingTimeS ?: a.totalTimeS,
                    ascentM = a.ascentM,
                    avgHeartRate = a.avgHeartRate,
                    maxHeartRate = a.maxHeartRate,
                    caloriesKcal = a.caloriesKcal,
                    aerobicTe = a.aerobicTrainingEffect,
                    trainingLoad = a.trainingLoad
                )
            }
        }
        (shared + mine).sortedByDescending { it.startUtcMs ?: 0L }
    }

    val filtered = remember(allActivities, filterMode) {
        when (filterMode) {
            1 -> allActivities.filter { it.isShared }
            2 -> allActivities.filter { !it.isShared }
            else -> allActivities
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = filterMode == 0,
                onClick = { filterMode = 0 },
                label = { Text("Vše (${allActivities.size})") }
            )
            FilterChip(
                selected = filterMode == 1,
                onClick = { filterMode = 1 },
                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Od členů (${allActivities.count { it.isShared }})") }
            )
            FilterChip(
                selected = filterMode == 2,
                onClick = { filterMode = 2 },
                leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Moje (${allActivities.count { !it.isShared }})") }
            )
        }

        if (selectedActsForCompare.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2942)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Vybráno ${selectedActsForCompare.size}/4",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selectedActsForCompare = emptySet() }) {
                            Text("Zrušit", maxLines = 1, softWrap = false)
                        }
                        Button(
                            enabled = selectedActsForCompare.size >= 2,
                            onClick = { onCompareActivities(selectedActsForCompare.toList()) }
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Porovnat (${selectedActsForCompare.size})", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        } else {
            Text(
                "Zaškrtněte 2 až 4 aktivity členů skupiny pro překryvné porovnání tras, tempa a tepu.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8),
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        if (filtered.isEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Zatím žádné aktivity v tomto filtru", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Jakmile členové této skupiny nasdílí své aktivity přes Reticulum P2P, objeví se zde.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        } else {
            filtered.forEach { act ->
                val checked = act.key in selectedActsForCompare
                val ownerBadgeColor = if (act.isShared) Color(0xFF38BDF8) else Color(0xFF10B981)
                Card(
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (checked) Color(0xFF38BDF8) else Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = if (checked) Color(0xFF132338) else Color(0xFF131722)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onOpenActivity(act.key) }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { c ->
                                selectedActsForCompare = if (c) {
                                    if (selectedActsForCompare.size < 4) selectedActsForCompare + act.key else selectedActsForCompare
                                } else {
                                    selectedActsForCompare - act.key
                                }
                            }
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .size(38.dp)
                                .background(act.sport.badgeColor().copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = act.sport.materialIcon(),
                                contentDescription = act.sport.czechName(),
                                tint = act.sport.badgeColor(),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    act.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Box(
                                    modifier = Modifier
                                        .background(ownerBadgeColor.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (act.isShared) Icons.Outlined.Share else Icons.Outlined.Person,
                                            contentDescription = null,
                                            tint = ownerBadgeColor,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            if (act.isShared) act.ownerName else "Moje",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ownerBadgeColor
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            val actDist = act.distanceM
                            val actMove = act.movingTimeS
                            val actPace = if (actDist != null && actDist > 10.0 && actMove != null) actMove / (actDist / 1000.0) else null
                            Text(
                                "${fmt.km(actDist)} · ${actMove?.let(fmt::duration) ?: "—"} · Tempo ${fmt.pace(actPace)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                buildString {
                                    append(act.localDate)
                                    act.avgHeartRate?.let { append(" · $it bpm") }
                                    act.ascentM?.let { append(" · +${it.toInt()} m") }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Text("Detail ›", style = MaterialTheme.typography.labelMedium, color = Color(0xFF38BDF8))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Sekce 2 uvnitř rozkliknuté skupiny:
 * Správa členů této skupiny a přepínače kategorií dat, které JÁ sdílím s touto skupinou.
 */
@Composable
private fun GroupBranchMembersAndSharingSection(
    sl: ServiceLocator,
    groupId: String,
    currentGroup: Group?,
    meId: String,
    participantIds: List<String>,
    contacts: List<ContactRow>,
    activeGrants: List<ShareGrant>,
    onOpenPairing: () -> Unit,
    onBackToTree: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }

    val categories = listOf(
        Triple(Category.SLEEP, "Spánek" to "Doba spánku, skóre a fáze", Icons.Outlined.Bedtime),
        Triple(Category.HRV, "HRV & Regenerace" to "Noční variabilita tepu rMSSD", Icons.Outlined.MonitorHeart),
        Triple(Category.BODY_BATTERY, "Body Battery" to "Nabití energie a denní průběh", Icons.Outlined.Bolt),
        Triple(Category.HEART_RATE, "Klidový tep" to "Klidová tepová frekvence", Icons.Outlined.Favorite),
        Triple(Category.ACTIVITY, "Aktivity & GPS trasy" to "Souhrny aktivit, tempo a trasy", Icons.AutoMirrored.Outlined.DirectionsRun),
        Triple(Category.BODY_COMPOSITION, "Váha & Tělo" to "Hmotnost a procento tuku", Icons.Outlined.Scale)
    )

    val targetRecipients = remember(currentGroup, contacts, meId) {
        if (currentGroup != null) {
            (currentGroup.members - meId).toSet()
        } else {
            contacts.map { it.identity }.toSet()
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        notice?.let { msg ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2942)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(msg, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
        }

        // 1. Přepínače kategorií sdílených do této skupiny
        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Co sdílím do skupiny '${currentGroup?.name ?: "Všichni přátelé"}'",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Zapněte kategorie dat, které chcete automaticky šifrovaně posílat členům této skupiny.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )

                Spacer(Modifier.height(8.dp))

                if (targetRecipients.isEmpty()) {
                    Text(
                        "V této skupině zatím není žádný další spárovaný příjemce. Přidejte člena níže nebo spárujte kontakt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF59E0B)
                    )
                } else {
                    categories.forEach { (cat, info, icon) ->
                        val (title, subtitle) = info
                        val catFields = Field.entries.filter { it.category == cat }.toSet()
                        val matchingGrant = activeGrants.firstOrNull { g ->
                            g.fields.intersect(catFields).isNotEmpty() &&
                                (g.groupId == currentGroup?.id || g.recipients.intersect(targetRecipients).isNotEmpty())
                        }
                        val isEnabled = matchingGrant != null

                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color(0xFF38BDF8).copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(icon, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                            }
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { enable ->
                                    scope.launch {
                                        notice = withContext(Dispatchers.IO) {
                                            val now = System.currentTimeMillis()
                                            if (enable) {
                                                val self = sl.bridge.ensureStarted().deliveryHash
                                                val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
                                                val newGrant = ShareGrant(
                                                    id = UUID.randomUUID().toString(),
                                                    ownerId = self,
                                                    recipients = targetRecipients,
                                                    fields = catFields,
                                                    scope = TimeScope.continuousFrom(today.minusDays(60)),
                                                    groupId = currentGroup?.id,
                                                    createdAtMs = now
                                                )
                                                sl.shareService.saveGrant(newGrant)
                                                sl.shareService.planAndEnqueue(now)
                                                "Sdílení sekce '$title' do skupiny zapnuto."
                                            } else {
                                                matchingGrant?.let { sl.shareService.revoke(it.id, now) }
                                                "Sdílení sekce '$title' bylo odvoláno."
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 2. Členové této skupiny + možnost přidat/odebrat člena z kontaktů
        Card(
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.People, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Členové skupiny (${participantIds.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    TextButton(onClick = onOpenPairing) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Nový kontakt")
                    }
                }

                participantIds.forEach { memberId ->
                    val isMe = memberId == meId
                    val contact = contacts.firstOrNull { it.identity == memberId }
                    val name = if (isMe) "Já (vlastník zařízení)" else contact?.displayName ?: memberId.take(10)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isMe) Icons.Outlined.Star else Icons.Outlined.Person,
                                    contentDescription = null,
                                    tint = if (isMe) Color(0xFFFFB300) else Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                            Text(
                                if (isMe) "Lokální účet" else "Otisk: ${contact?.fingerprint ?: memberId.take(12)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        if (!isMe && currentGroup != null) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        notice = withContext(Dispatchers.IO) {
                                            val updated = currentGroup.copy(
                                                members = currentGroup.members - memberId,
                                                version = currentGroup.version + 1
                                            )
                                            sl.shareService.saveGroup(updated)
                                            "Člen '$name' odebrán ze skupiny."
                                        }
                                    }
                                }
                            ) {
                                Text("Odebrat", color = Color(0xFFF43F5E))
                            }
                        }
                    }
                }

                // Pokud jde o uživatelskou skupinu a máme kontakty, které ještě nejsou ve skupině, nabídneme jejich přidání
                if (currentGroup != null) {
                    val nonMembers = contacts.filter { it.identity !in currentGroup.members }
                    if (nonMembers.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Přidat do skupiny z vašich kontaktů:",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                        FlowRow(Modifier.padding(top = 4.dp)) {
                            nonMembers.forEach { c ->
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            notice = withContext(Dispatchers.IO) {
                                                val self = sl.bridge.ensureStarted().deliveryHash
                                                val updated = currentGroup.copy(
                                                    members = currentGroup.members + c.identity,
                                                    version = currentGroup.version + 1
                                                )
                                                sl.shareService.saveGroup(updated)
                                                val b = MessageBuilder(self)
                                                val now = System.currentTimeMillis()
                                                val msg = b.build(
                                                    c.identity,
                                                    Kinds.MEMBERSHIP_UPDATE,
                                                    "group",
                                                    updated.id,
                                                    updated.version,
                                                    Wire.json.encodeToString(Group.serializer(), updated),
                                                    now
                                                )
                                                val e = msg.envelope
                                                sl.outbox.enqueue(
                                                    OutboxItem(e.eventId, c.identity, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), now)
                                                )
                                                "Kontakt '${c.displayName}' byl přidán do skupiny '${updated.name}'."
                                            }
                                        }
                                    },
                                    modifier = Modifier.padding(end = 6.dp, bottom = 4.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(c.displayName)
                                    }
                                }
                            }
                        }
                    }
                }

                if (currentGroup != null) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    sl.db.grants().deleteGroup(currentGroup.id)
                                }
                                onBackToTree()
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF43F5E)),
                        border = BorderStroke(1.dp, Color(0xFFF43F5E).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Smazat skupinu '${currentGroup.name}'", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Čistý modální dialog pro QR párování kontaktů (Můj QR kód / Přidat kontakt).
 */
@Composable
private fun PairingModalDialog(
    sl: ServiceLocator,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var mode by remember { mutableStateOf(0) } // 0 = Můj QR kód, 1 = Přidat kontakt
    var paste by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PairingResult.Ok?>(null) }
    var verified by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    val mine by io(Unit) {
        val info = sl.bridge.ensureStarted()
        val text = Pairing.encode(PairingPayload(Pairing.FORMAT, info.publicKeyB64, info.deliveryHash, sl.settings.displayName))
        Triple(text, Pairing.fingerprint(info.publicKeyB64), qrBitmap(text))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.QrCode, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("P2P Párování kontaktů", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = mode == 0,
                        onClick = { mode = 0 },
                        label = { Text("Můj QR kód") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = mode == 1,
                        onClick = { mode = 1 },
                        label = { Text("Přidat kontakt") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (mode == 0) {
                    mine?.let { (text, fp, bmp) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Image(bmp.asImageBitmap(), "QR kód", Modifier.size(190.dp))
                            Spacer(Modifier.height(6.dp))
                            Text("Otisk: $fp", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Nechte přítele naskenovat tento QR kód nebo mu zkopírujte párovací řetězec.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    clipboard.setText(AnnotatedString(text))
                                    msg = "Párovací kód zkopírován do schránky."
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Kopírovat můj párovací kód")
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(ctx).startScan()
                                .addOnSuccessListener { b ->
                                    paste = b.rawValue.orEmpty()
                                    when (val r = Pairing.parse(paste)) {
                                        is PairingResult.Invalid -> msg = "Neplatný kód: ${r.reason}"
                                        is PairingResult.Ok -> { pending = r; verified = false; msg = null }
                                    }
                                }
                                .addOnFailureListener { msg = "Kamera není dostupná, vložte prosím text kódu níže." }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Naskenovat QR kód kamerou")
                    }

                    OutlinedTextField(
                        value = paste,
                        onValueChange = { paste = it; pending = null },
                        label = { Text("Nebo vložte text kódu kontaktu") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    )

                    OutlinedButton(
                        onClick = {
                            when (val r = Pairing.parse(paste)) {
                                is PairingResult.Invalid -> msg = "Neplatný kód: ${r.reason}"
                                is PairingResult.Ok -> { pending = r; verified = false; msg = null }
                            }
                        },
                        enabled = paste.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ověřit vložený kód")
                    }

                    pending?.let { p ->
                        Card(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Person, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(p.payload.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                }
                                Text("Otisk: ${p.fingerprint}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(verified, { verified = it })
                                    Text("Otisk souhlasí s protistranou", style = MaterialTheme.typography.labelSmall)
                                }
                                Button(
                                    enabled = verified,
                                    onClick = {
                                        scope.launch {
                                            msg = withContext(Dispatchers.IO) {
                                                sl.bridge.ensureStarted()
                                                val derived = sl.bridge.addContact(p.payload.pub)
                                                if (!Pairing.addressMatches(p.payload.addr, derived)) {
                                                    sl.bridge.removeContact(derived)
                                                    "Adresa v kódu neodpovídá klíči."
                                                } else {
                                                    sl.db.contacts().upsert(
                                                        ContactRow(derived, p.payload.name, p.payload.pub, p.fingerprint, System.currentTimeMillis())
                                                    )
                                                    "Kontakt '${p.payload.name}' byl uložen."
                                                }
                                            }
                                            pending = null
                                            paste = ""
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Uložit kontakt")
                                }
                            }
                        }
                    }
                }

                msg?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF38BDF8))
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

/**
 * Čistý modální dialog pro vytvoření nové skupiny a okamžité otevření jejího detailu.
 */
@Composable
private fun CreateGroupModalDialog(
    sl: ServiceLocator,
    contacts: List<ContactRow>,
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var members by remember { mutableStateOf(setOf<String>()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.GroupAdd, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Vytvořit novou skupinu", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název skupiny (např. Běžci, Rodina)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (contacts.isEmpty()) {
                    Text(
                        "Zatím nemáte spárované kontakty — skupinu vytvoříte pro sebe a členy můžete přidat později.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                } else {
                    Text("Vyberte členy skupiny:", style = MaterialTheme.typography.labelMedium)
                    FlowRow(Modifier.padding(top = 4.dp)) {
                        contacts.forEach { c ->
                            FilterChip(
                                selected = c.identity in members,
                                onClick = {
                                    members = if (c.identity in members) members - c.identity else members + c.identity
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text(c.displayName) },
                                modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                            )
                        }
                    }
                }
                errorMsg?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    val groupName = name.trim()
                    scope.launch {
                        try {
                            val newId = withContext(Dispatchers.IO) {
                                val self = sl.bridge.ensureStarted().deliveryHash
                                val g = Group(UUID.randomUUID().toString(), groupName, self, self, members + self, 1)
                                sl.shareService.saveGroup(g)
                                val b = MessageBuilder(self)
                                val now = System.currentTimeMillis()
                                (g.members - self).forEach { m ->
                                    val msg = b.build(m, Kinds.MEMBERSHIP_UPDATE, "group", g.id, g.version, Wire.json.encodeToString(Group.serializer(), g), now)
                                    val e = msg.envelope
                                    sl.outbox.enqueue(OutboxItem(e.eventId, m, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), now))
                                }
                                g.id
                            }
                            onCreated(newId)
                        } catch (e: Exception) {
                            errorMsg = "Chyba: ${e.message}"
                        }
                    }
                }
            ) {
                Text("Vytvořit a otevřít")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Zrušit")
            }
        }
    )
}
