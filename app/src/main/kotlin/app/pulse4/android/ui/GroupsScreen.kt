@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import app.pulse4.domain.czechName

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.FamilyRestroom
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
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse4.android.R
import app.pulse4.android.ui.theme.JetBrainsMonoFamily
import app.pulse4.android.data.ContactRow
import app.pulse4.android.data.ReceivedRow
import app.pulse4.android.data.effectiveMovingTimeS
import app.pulse4.android.data.toDomain
import app.pulse4.android.di.ConsentUiState
import app.pulse4.android.di.ServiceLocator
import app.pulse4.domain.Activity
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Sport
import app.pulse4.sharing.ActivityProjection
import app.pulse4.sharing.AgeProfile
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
import app.pulse4.ui.ChartKey
import app.pulse4.ui.GrantEditor
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

internal fun filterTopLevelGroupActivities(
    rows: List<app.pulse4.android.data.ActivitySummaryRow>,
): List<app.pulse4.android.data.ActivitySummaryRow> = rows.filterNot { row ->
    if (app.pulse4.android.di.ImportService.isMultisportLegId(row.id)) return@filterNot true
    val act = row.json?.let { js -> runCatching { Wire.json.decodeFromString(Activity.serializer(), js) }.getOrNull() }
    act != null && ("multisport-leg" in act.tags || act.tags.any { it.startsWith("parent:") })
}

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
    onOpenSharingOverview: () -> Unit = {},
) {
    val contacts by sl.db.contacts().all().collectAsState(emptyList())
    val groups by sl.db.grants().groups().collectAsState(emptyList())
    val grants by sl.db.grants().all().collectAsState(emptyList())
    val receivedDaily by sl.db.inbox().objects("daily").collectAsState(emptyList())
    val receivedActsRaw by sl.db.inbox().objects("activity").collectAsState(emptyList())
    val receivedActs = remember(receivedActsRaw) {
        receivedActsRaw.filterNot { app.pulse4.android.di.ImportService.isMultisportLegId(it.objectId) }
    }
    val myActsRaw by sl.db.activities().recentSummaries(60).collectAsState(emptyList())
    val myActs = remember(myActsRaw) {
        filterTopLevelGroupActivities(myActsRaw).take(20)
    }

    val decodedGroups = remember(groups) {
        groups.mapNotNull { runCatching { Wire.json.decodeFromString(Group.serializer(), it.json) }.getOrNull() }
            .filter { !it.dissolved }
    }
    val activeGrants = remember(grants) {
        grants.mapNotNull { runCatching { Wire.json.decodeFromString(ShareGrant.serializer(), it.json) }.getOrNull() }
            .filter { it.active }
    }

    val selfHash by io(Unit) { sl.bridge.ensureStarted().deliveryHash }
    val meId = selfHash ?: sl.bridge.info?.deliveryHash ?: "me"

    // null = Jsme v kořeni stromu (Seznam kruhů); jinak ID rozkliknutého kruhu (nebo ALL_FRIENDS_GROUP_ID)
    var openedGroupId by remember { mutableStateOf<String?>(null) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var showAddFamilyDialog by remember { mutableStateOf(false) }

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

    if (showAddFamilyDialog) {
        AddFamilyLinkModalDialog(
            sl = sl,
            contacts = contacts,
            onOpenPairing = {
                showAddFamilyDialog = false
                showPairingDialog = true
            },
            onDismiss = { showAddFamilyDialog = false }
        )
    }

    if (openedGroupId == null) {
        // ÚROVEŇ 1: Kořen stromu — Seznam kruhů + Přidat kruh + Kontakty
        GroupsTreeRootView(
            sl = sl,
            contacts = contacts,
            decodedGroups = decodedGroups,
            activeGrants = activeGrants,
            receivedDaily = receivedDaily,
            receivedActs = receivedActs,
            meId = meId,
            onOpenGroup = { gId -> openedGroupId = gId },
            onOpenNewGroup = { showNewGroupDialog = true },
            onOpenPairing = { showPairingDialog = true },
            onOpenSharingOverview = onOpenSharingOverview,
            onOpenAddFamily = { showAddFamilyDialog = true }
        )
    } else {
        // ÚROVEŇ 2: Detail rozkliknutého kruhu (Metriky | Aktivity | Členové & Sdílení)
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
            onOpenAddFamily = { showAddFamilyDialog = true },
            onOpenSharingOverview = onOpenSharingOverview,
            onOpenActivity = onOpenActivity,
            onCompareActivities = onCompareActivities
        )
    }
}

/**
 * ÚROVEŇ 1 (Kořen stromu):
 * Přehledný seznam všech kruhů s možností přidat nový kruh, spárovat kontakt a rozkliknout libovolný kruh.
 */
@Composable
private fun GroupsTreeRootView(
    sl: ServiceLocator,
    contacts: List<ContactRow>,
    decodedGroups: List<Group>,
    activeGrants: List<ShareGrant>,
    receivedDaily: List<ReceivedRow>,
    receivedActs: List<ReceivedRow>,
    meId: String,
    onOpenGroup: (String) -> Unit,
    onOpenNewGroup: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenSharingOverview: () -> Unit,
    onOpenAddFamily: () -> Unit,
) {
    val activeCatsTotal = remember(activeGrants) {
        activeGrants.flatMap { it.fields }.map { it.category }.distinct().size
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // ─── AMBITIOUS NORDIC-CYBERNETIC HERO: KRUHY DŮVĚRY & RETICULUM P2P MESH ───
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF070C14)),
            border = BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.42f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = R.drawable.img_nordic_wellness_hero),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(0.55f)
                )
                // Procedurální P2P Mesh souhvězdí a radarové kružnice na pozadí
                Canvas(Modifier.matchParentSize()) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color(0x88050911), Color(0xCC070C14), Color(0xF2060A10))
                        )
                    )
                    val cx = size.width * 0.84f
                    val cy = size.height * 0.34f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF2DD4BF).copy(alpha = 0.28f), Color.Transparent),
                            center = Offset(cx, cy),
                            radius = size.width * 0.55f
                        ),
                        center = Offset(cx, cy),
                        radius = size.width * 0.55f
                    )
                    listOf(36.dp.toPx(), 68.dp.toPx(), 102.dp.toPx()).forEachIndexed { idx, r ->
                        drawCircle(
                            color = Color(0xFF2DD4BF).copy(alpha = 0.16f - idx * 0.04f),
                            radius = r,
                            center = Offset(cx, cy),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                    // Mesh uzly
                    val meshPts = listOf(
                        Offset(cx, cy),
                        Offset(cx - 48.dp.toPx(), cy - 22.dp.toPx()),
                        Offset(cx - 32.dp.toPx(), cy + 38.dp.toPx()),
                        Offset(cx + 28.dp.toPx(), cy + 30.dp.toPx()),
                        Offset(cx - 82.dp.toPx(), cy + 8.dp.toPx())
                    )
                    for (i in 1 until meshPts.size) {
                        drawLine(
                            color = Color(0xFF38BDF8).copy(alpha = 0.32f),
                            start = meshPts[0],
                            end = meshPts[i],
                            strokeWidth = 1.2.dp.toPx()
                        )
                    }
                    drawLine(
                        color = Color(0xFF2DD4BF).copy(alpha = 0.22f),
                        start = meshPts[1],
                        end = meshPts[4],
                        strokeWidth = 1.dp.toPx()
                    )
                    meshPts.forEachIndexed { idx, pt ->
                        drawCircle(
                            color = if (idx == 0) Color(0xFF2DD4BF) else Color(0xFF38BDF8),
                            radius = if (idx == 0) 4.5.dp.toPx() else 3.dp.toPx(),
                            center = pt
                        )
                    }
                }

                Column(Modifier.padding(18.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF0A1926).copy(alpha = 0.85f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Box(Modifier.size(7.dp).background(Color(0xFF2DD4BF), CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "RETICULUM P2P MESH · E2EE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF2DD4BF),
                                letterSpacing = 1.2.sp,
                                fontSize = 9.5.sp
                            )
                        }
                        Text(
                            "ZERO-CLOUD",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8),
                            fontSize = 10.sp
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Kruhy Důvěry",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Sdílejte vybrané biometrické metriky Garmin a Withings přímo mezi ověřenými zařízeními bez centrálního serveru.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )

                    Spacer(Modifier.height(14.dp))

                    // Glassmorphic Bento Strip s živými P2P statistikami
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            Triple("KRUHY", "${decodedGroups.size + 1}", Color(0xFF38BDF8)),
                            Triple("P2P KONTAKTY", "${contacts.size}", Color(0xFF2DD4BF)),
                            Triple("SDÍLENÉ SEKCE", "$activeCatsTotal/6", Color(0xFFA855F7))
                        ).forEach { (lbl, valueStr, col) ->
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1320).copy(alpha = 0.82f)),
                                border = BorderStroke(1.dp, col.copy(alpha = 0.32f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(
                                        lbl,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF94A3B8),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Text(
                                        valueStr,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = col
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenPairing,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF0A1924).copy(alpha = 0.75f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.QrCode, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("QR párování", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2DD4BF), maxLines = 1)
                        }
                        Button(
                            onClick = onOpenNewGroup,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.GroupAdd, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Nový kruh", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenSharingOverview,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF0A1924).copy(alpha = 0.75f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Security, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Sdílení a oprávnění", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onOpenAddFamily,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF150F24).copy(alpha = 0.75f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.FamilyRestroom, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Přidat dítě", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFFA855F7), maxLines = 1)
                        }
                    }
                }
            }
        }

        // Souhlas rodiče u kruhů a zaparkované pozvánky (jen účet nezletilého)
        CircleConsentSection(sl)

        Spacer(Modifier.height(18.dp))

        // Sekce 1: Strom kruhů
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "MOJE KRUHY (${decodedGroups.size + 1})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFCBD5E1),
                    letterSpacing = 1.sp
                )
            }
            Text(
                "Klepnutím otevřete detail",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64748B)
            )
        }
        Spacer(Modifier.height(8.dp))

        // Speciální souhrnná větev: Všichni spárovaní přátelé
        val allParticipantIds = remember(contacts, receivedDaily, meId) {
            (listOf(meId) + contacts.map { it.identity } + receivedDaily.map { it.author }).distinct()
        }
        GroupTreeFolderCard(
            icon = Icons.Outlined.Public,
            title = "Všichni spárovaní přátelé",
            subtitle = "Souhrnná observatoř všech vašich P2P kontaktů",
            memberNames = allParticipantIds.map { id ->
                if (id == meId) "Já" else contacts.firstOrNull { it.identity == id }?.displayName ?: id.take(8)
            },
            sharedDailyCount = receivedDaily.map { it.author }.distinct().size,
            sharedActivitiesCount = receivedActs.size,
            activeCategoriesCount = activeCatsTotal,
            accentColor = Color(0xFF38BDF8),
            bgImageRes = R.drawable.img_nordic_wellness_hero,
            onClick = { onOpenGroup(ALL_FRIENDS_GROUP_ID) }
        )

        // Jednotlivé uživatelské skupiny s unikátní atmosférou
        val groupPalettes = listOf(Color(0xFF10B981), Color(0xFFA855F7), Color(0xFFF59E0B), Color(0xFFEC4899))
        val groupBgImages = listOf(
            R.drawable.img_alpine_trail_hero,
            R.drawable.img_motif_hrv_autonomic,
            R.drawable.img_sport_cycling_hero,
            R.drawable.img_sport_tactical_hero
        )
        decodedGroups.forEachIndexed { idx, g ->
            val color = groupPalettes[idx % groupPalettes.size]
            val bgRes = groupBgImages[idx % groupBgImages.size]
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
                subtitle = "Šifrovaný P2P kruh · revize v${g.version}",
                memberNames = memberNames,
                sharedDailyCount = groupDailyAuthors,
                sharedActivitiesCount = groupActsCount,
                activeCategoriesCount = groupCatsCount,
                accentColor = color,
                bgImageRes = bgRes,
                onClick = { onOpenGroup(g.id) }
            )
        }

        // Atmosférická karta pro rychlé vytvoření nové skupiny
        Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A121E)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable { onOpenNewGroup() }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF0F243C).copy(alpha = 0.65f), Color(0xFF0A121E))
                        )
                    )
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(0xFF38BDF8).copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Založit nový kruh důvěry",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            "Např. Rodina, Běžecký oddíl, Trenér nebo Expedice…",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF38BDF8).copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        "+ Nový",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // Sekce 2: Ověřené P2P kontakty v kořeni stromu
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.People, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "OVĚŘENÉ KRYPTOGRAFICKÉ UZLY (${contacts.size})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFCBD5E1),
                    letterSpacing = 0.8.sp
                )
            }
            TextButton(onClick = onOpenPairing) {
                Icon(Icons.Outlined.PersonAdd, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Spárovat uzel", color = Color(0xFF2DD4BF), fontWeight = FontWeight.Bold)
            }
        }

        if (contacts.isEmpty()) {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B111C)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Zatím nemáte žádné spárované P2P kontakty",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Klepněte na 'QR párování' pro zobrazení vašeho kryptografického QR kódu nebo načtení kódu přítele.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        } else {
            contacts.forEachIndexed { idx, c ->
                val hasSentData = receivedDaily.any { it.author == c.identity } || receivedActs.any { it.author == c.identity }
                val nodeAccent = if (hasSentData) Color(0xFF38BDF8) else Color(0xFF10B981)
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, nodeAccent.copy(alpha = 0.32f)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0C121E)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(nodeAccent.copy(alpha = 0.10f), Color.Transparent)
                                )
                            )
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(nodeAccent.copy(alpha = 0.18f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    c.displayName.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = nodeAccent
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    c.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                Text(
                                    "KEY FP: ${c.fingerprint}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFamily,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(nodeAccent.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                if (hasSentData) Icons.Outlined.Share else Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = nodeAccent,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (hasSentData) "Aktivní P2P" else "Ověřeno",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = nodeAccent
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
    bgImageRes: Int = R.drawable.img_nordic_wellness_hero,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF090F1A)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onClick() }
    ) {
        Box(Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = bgImageRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .alpha(0.32f)
            )
            Canvas(Modifier.matchParentSize()) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xE6080D17), Color(0xCC0A111E), Color(0x990A111E))
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accentColor.copy(alpha = 0.24f), Color.Transparent),
                        center = Offset(size.width * 0.9f, size.height * 0.2f),
                        radius = size.width * 0.45f
                    ),
                    center = Offset(size.width * 0.9f, size.height * 0.2f),
                    radius = size.width * 0.45f
                )
            }

            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .background(accentColor.copy(alpha = 0.20f), RoundedCornerShape(14.dp)),
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
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accentColor.copy(alpha = 0.18f))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "Otevřít",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = accentColor,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            " ›",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Avatarové kroužky členů + výčet jmen
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                        memberNames.take(5).forEachIndexed { i, mName ->
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(
                                        if (i == 0) accentColor else Color(0xFF1E293B),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    mName.take(1).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (i == 0) Color(0xFF06101A) else Color.White,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${memberNames.size} členů: ${memberNames.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE2E8F0),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Souhrnné štítky sdílených dat ve skupině
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF0F172A).copy(alpha = 0.85f), RoundedCornerShape(9.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Equalizer, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Metriky: $sharedDailyCount přátel",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFamily,
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF0F172A).copy(alpha = 0.85f), RoundedCornerShape(9.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Outlined.DirectionsRun, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "$sharedActivitiesCount aktivit",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFamily,
                                color = Color(0xFF10B981),
                                fontSize = 10.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                    if (activeCategoriesCount > 0) {
                        Box(
                            modifier = Modifier
                                .background(accentColor.copy(alpha = 0.20f), RoundedCornerShape(9.dp))
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Share, contentDescription = null, tint = accentColor, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Sdílím $activeCategoriesCount sekcí",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor,
                                    fontSize = 10.sp,
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
    onOpenAddFamily: () -> Unit,
    onOpenSharingOverview: () -> Unit = {},
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
    val headerBgRes = if (currentGroup == null) R.drawable.img_nordic_wellness_hero else R.drawable.img_alpine_trail_hero

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Atmosférická Hero hlavička rozkliknuté skupiny se skleněným přepínačem sekcí
        Card(
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.40f)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF080E18)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = headerBgRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(0.38f)
                )
                Canvas(Modifier.matchParentSize()) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color(0x99060B13), Color(0xD9080E18), Color(0xF5080E18))
                        )
                    )
                }

                Column(Modifier.padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0F1F32).copy(alpha = 0.85f))
                                .clickable { onBackToTree() }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Zpět na kruhy",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Kruhy",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                groupTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${participantIds.size} členů · ${memberNames.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFCBD5E1),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // 3 skleněné segmentové záložky uvnitř rozkliknuté skupiny
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
                            val selected = activeTab == idx
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) Color(0xFF38BDF8) else Color(0xFF1E293B)
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) Color(0xFF0E2942) else Color(0xFF0B121E).copy(alpha = 0.78f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { activeTab = idx }
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = if (selected) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelMedium,
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
                onOpenAddFamily = onOpenAddFamily,
                onOpenSharingOverview = onOpenSharingOverview,
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
        Color(0xFFC4B5FD), // Violet (pairwise CIEDE2000 >= 25 with the rest, see SeriesColorAllocator)
        Color(0xFF2563EB)  // Royal Blue
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
                // Sloučení více projekcí téhož dne (např. z různých grantů), aby novější nepřepsala pole starší.
                val byDateFields = HashMap<String, MutableMap<Field, Double>>()
                for (r in memberRows) {
                    val sp = runCatching { Wire.json.decodeFromString(SharedProjection.serializer(), r.payload) }.getOrNull() ?: continue
                    val dayMap = byDateFields.getOrPut(sp.date) { HashMap() }
                    for ((field, elem) in sp.fields) {
                        val v = (elem as? JsonPrimitive)?.doubleOrNull ?: continue
                        if (v.isFinite()) dayMap[field] = v
                    }
                }

                datesAxis.map { d ->
                    val raw = byDateFields[d]?.get(activeField)
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

    val fieldMotifBg = when (activeField) {
        Field.HRV_DAILY -> R.drawable.img_motif_hrv_autonomic
        Field.SLEEP_DURATION -> R.drawable.img_motif_sleep_night
        Field.BB_LAST -> R.drawable.img_motif_body_battery
        Field.HR_DAILY -> R.drawable.img_motif_heart_pulse
        Field.WEIGHT -> R.drawable.img_motif_body_silhouette
        else -> R.drawable.img_nordic_wellness_hero
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        // Výběr metriky a časového okna
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                Triple(Field.HRV_DAILY, "HRV", PulseIcons.Hrv),
                Triple(Field.SLEEP_DURATION, "Spánek", PulseIcons.Sleep),
                Triple(Field.BB_LAST, "Body Battery", PulseIcons.BodyBattery),
                Triple(Field.HR_DAILY, "Klidový tep", PulseIcons.HeartRate),
                Triple(Field.WEIGHT, "Váha", PulseIcons.Weight)
            ).forEach { (f, label, icon) ->
                FilterChip(
                    selected = activeField == f,
                    onClick = { activeField = f },
                    leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text(label, fontWeight = if (activeField == f) FontWeight.Bold else FontWeight.Normal) }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Časové okno telemetrie",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(7, 14, 30).forEach { d ->
                    FilterChip(
                        selected = windowDays == d,
                        onClick = { windowDays = d },
                        label = { Text("${d}d", fontFamily = JetBrainsMonoFamily) }
                    )
                }
            }
        }

        // Společný Grafana MultiSeries graf členů skupiny – výchozí je společná osa (skutečné naměřené hodnoty)
        MultiSeriesLineChart(
            series = multiSeries,
            xLabels = datesAxis.map { it.takeLast(5) },
            title = "$groupTitle · ${windowDays} dní",
            infoKey = ChartKey.GROUP_COMPARE,
            defaultIndependentScale = false
        )

        Spacer(Modifier.height(10.dp))
        val isRanked = isRankedGroupField(activeField)
        SectionTitle(
            if (isRanked) "Žebříček a statistiky členů kruhu (${participantIds.size})"
            else "Přehled hodnot členů kruhu (${participantIds.size})",
            icon = if (isRanked) Icons.Outlined.EmojiEvents else Icons.Outlined.People
        )

        val orderedParticipants = remember(participantIds, multiSeries, activeField, contacts, meId, isRanked) {
            val items = participantIds.mapIndexed { idx, memberId ->
                val seriesItem = multiSeries.firstOrNull { it.id == memberId }
                val finiteVals = seriesItem?.values?.filterNotNull().orEmpty()
                val latestVal = finiteVals.lastOrNull()
                val avgVal = if (finiteVals.isNotEmpty()) finiteVals.average() else null
                Triple(idx, memberId, Pair(latestVal, avgVal))
            }
            if (isRanked) {
                items.sortedWith(
                    compareByDescending<Triple<Int, String, Pair<Double?, Double?>>> { it.third.first != null }
                        .thenBy {
                            val v = it.third.first ?: 0.0
                            if (activeField == Field.HR_DAILY) v else -v
                        }
                )
            } else {
                // U tělesných / individuálních metrik (např. váha) bez pořadí: nejdřív Já, pak podle jména.
                items.sortedWith(
                    compareByDescending<Triple<Int, String, Pair<Double?, Double?>>> { it.second == meId }
                        .thenByDescending { it.third.first != null }
                        .thenBy { (_, id, _) ->
                            (contacts.firstOrNull { it.identity == id }?.displayName ?: id).lowercase()
                        }
                )
            }
        }

        orderedParticipants.forEachIndexed { rankIdx, (origIdx, memberId, stats) ->
            val isMe = memberId == meId
            val contact = contacts.firstOrNull { it.identity == memberId }
            val displayShort = if (isMe) "Já" else contact?.displayName ?: memberId.take(8)
            val memberName = if (isMe) "Já (vlastník)" else contact?.displayName ?: (memberId.take(8) + "…")
            val memberColor = palette[origIdx % palette.size]
            val seriesItem = multiSeries.firstOrNull { it.id == memberId }
            val finiteVals = seriesItem?.values?.filterNotNull().orEmpty()
            val latestVal = stats.first
            val avgVal = stats.second

            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, memberColor.copy(alpha = 0.45f)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B))
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = fieldMotifBg),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(0.22f)
                    )
                    Canvas(Modifier.matchParentSize()) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(Color(0xEB090E18), Color(0xCC0A101B), memberColor.copy(alpha = 0.14f))
                            )
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            val badgeText = when {
                                isRanked -> if (latestVal != null) "#${rankIdx + 1}" else "—"
                                isMe -> "Já"
                                else -> memberInitials(displayShort)
                            }
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(memberColor.copy(alpha = 0.22f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    badgeText,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = memberColor
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).background(memberColor, CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        memberName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
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
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (latestVal != null) memberColor else Color(0xFF64748B)
                            )
                            if (avgVal != null && finiteVals.size >= 2) {
                                val rangeDecimals = if (activeField == Field.WEIGHT || activeField == Field.SLEEP_DURATION) 1 else 0
                                Text(
                                    "Ø ${formatGroupFieldValue(activeField, avgVal)} · ${fmt.number(finiteVals.min(), rangeDecimals)}–${fmt.number(finiteVals.max(), rangeDecimals)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFamily,
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Určuje, zda u dané metriky dává smysl řadit členy kruhu do soutěžního žebříčku (#1, #2, …).
 * Váha a další metriky tělesné kompozice znamenají pro každého člověka něco jiného,
 * proto se zobrazují jen jako neutrální přehled bez pořadí.
 */
internal fun isRankedGroupField(field: Field): Boolean =
    field.category != app.pulse4.sharing.Category.BODY_COMPOSITION

private fun memberInitials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> "${parts[0].first()}${parts[1].first()}".uppercase()
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
    var filterMode by remember { mutableStateOf(0) } // 0 = Vše v kruhu, 1 = Od členů kruhu, 2 = Moje
    var selectedActsForCompare by remember { mutableStateOf(setOf<String>()) }

    val contactsMap = remember(contacts) { contacts.associate { it.identity to it.displayName } }
    val participantSet = remember(participantIds) { participantIds.toSet() }
    val allActivities by produceState<List<UnifiedActivityItem>>(
        initialValue = emptyList(),
        myActs,
        receivedActs,
        contactsMap,
        participantSet
    ) {
        value = withContext(Dispatchers.Default) {
            val zoneCache = HashMap<String, ZoneId>()
            val defaultZone = ZoneId.systemDefault()
            val shared = receivedActs.filter {
                it.author in participantSet && !app.pulse4.android.di.ImportService.isMultisportLegId(it.objectId)
            }.mapNotNull { r ->
                val p = runCatching { Wire.json.decodeFromString(ActivityProjection.serializer(), r.payload) }.getOrNull() ?: return@mapNotNull null
                val sp = runCatching { Sport.valueOf(p.sport) }.getOrDefault(Sport.OTHER)
                val cName = contactsMap[r.author] ?: r.author.take(8)
                UnifiedActivityItem(
                    key = "shared:${r.author}:${r.objectId}",
                    sport = sp,
                    title = "${sp.czechName()} · $cName",
                    ownerName = cName,
                    isShared = true,
                    startUtcMs = p.startUtcMs,
                    localDate = p.localDate,
                    distanceM = p.distanceM,
                    movingTimeS = p.movingTimeS?.takeIf { it > 0.0 } ?: p.totalTimeS,
                    ascentM = p.ascentM,
                    avgHeartRate = p.avgHeartRate,
                    maxHeartRate = null
                )
            }
            val mine = filterTopLevelGroupActivities(myActs).take(15).mapNotNull { row ->
                if (row.title != null) {
                    val sp = runCatching { Sport.valueOf(row.sport) }.getOrDefault(Sport.OTHER)
                    val zone = row.zoneId?.let { zid ->
                        zoneCache.getOrPut(zid) { runCatching { ZoneId.of(zid) }.getOrDefault(defaultZone) }
                    } ?: defaultZone
                    UnifiedActivityItem(
                        key = row.id,
                        sport = sp,
                        title = "${row.title} · Já",
                        ownerName = "Já",
                        isShared = false,
                        startUtcMs = row.startUtcMs,
                        localDate = Instant.ofEpochMilli(row.startUtcMs).atZone(zone).toLocalDate().toString(),
                        distanceM = row.distanceM,
                        movingTimeS = row.effectiveMovingTimeS(),
                        ascentM = row.ascentM,
                        avgHeartRate = row.avgHeartRate,
                        maxHeartRate = row.maxHeartRate,
                        caloriesKcal = row.caloriesKcal,
                        aerobicTe = row.aerobicTe,
                        trainingLoad = row.trainingLoad
                    )
                } else {
                    val a = row.json?.let { js -> runCatching { Wire.json.decodeFromString(Activity.serializer(), js) }.getOrNull() } ?: return@mapNotNull null
                    val zone = zoneCache.getOrPut(a.zoneId) { runCatching { ZoneId.of(a.zoneId) }.getOrDefault(defaultZone) }
                    UnifiedActivityItem(
                        key = a.id,
                        sport = a.sport,
                        title = "${a.title ?: a.sport.czechName()} · Já",
                        ownerName = "Já",
                        isShared = false,
                        startUtcMs = a.startUtcMs,
                        localDate = Instant.ofEpochMilli(a.startUtcMs).atZone(zone).toLocalDate().toString(),
                        distanceM = a.distanceM,
                        movingTimeS = a.movingTimeS?.takeIf { it > 0.0 } ?: a.totalTimeS,
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
                "Zaškrtněte 2 až 4 aktivity členů kruhu pro překryvné porovnání tras, tempa a tepu.",
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
                        "Jakmile členové tohoto kruhu nasdílí své aktivity přes Reticulum P2P, objeví se zde.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        } else {
            filtered.forEach { act ->
                val checked = act.key in selectedActsForCompare
                ActivityCardItem(
                    a = act,
                    isChecked = checked,
                    onToggleCompare = { c ->
                        selectedActsForCompare = if (c) {
                            if (selectedActsForCompare.size < 4) selectedActsForCompare + act.key else selectedActsForCompare
                        } else {
                            selectedActsForCompare - act.key
                        }
                    },
                    onClick = { onOpenActivity(act.key) }
                )
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
    onOpenAddFamily: () -> Unit = {},
    onOpenSharingOverview: () -> Unit = {},
    onBackToTree: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedSourceMode by remember { mutableStateOf(0) } // 0 = Všechny zdroje, 1 = Withings, 2 = Garmin
    var showHandoverDialog by remember { mutableStateOf(false) }
    var showDissolveConfirmDialog by remember { mutableStateOf(false) }

    val isMinorAccount by sl.settings.isMinorFlow.collectAsState()
    val grantStateFlow = remember(sl) { sl.shareService.consentStateByGrant() }
    val grantStates by grantStateFlow.collectAsState(emptyMap())
    val isSelfAdmin = currentGroup != null && meId in currentGroup.effectiveAdmins

    // Dialog pro předání správy kruhu jinému členovi (poslední správce)
    if (showHandoverDialog && currentGroup != null) {
        val candidates = (currentGroup.members - meId).toList()
        var selectedTarget by remember { mutableStateOf(candidates.firstOrNull()) }
        AlertDialog(
            onDismissRequest = { showHandoverDialog = false },
            containerColor = Color(0xFF0A101B),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCBD5E1),
            title = {
                Text("Předat správu kruhu", fontWeight = FontWeight.ExtraBold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Jste poslední správce kruhu '${currentGroup.name}'. Před odchodem vyberte nástupce, kterému předáte správu. Zůstáváte správcem, dokud nástupce převzetí nepotvrdí.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        candidates.forEach { candId ->
                            val cName = contacts.firstOrNull { it.identity == candId }?.displayName ?: candId.take(10)
                            FilterChip(
                                selected = selectedTarget == candId,
                                onClick = { selectedTarget = candId },
                                label = { Text(cName) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedTarget != null,
                    onClick = {
                        val targetId = selectedTarget ?: return@Button
                        showHandoverDialog = false
                        scope.launch {
                            notice = withContext(Dispatchers.IO) {
                                sl.shareService.initiateAdminHandover(currentGroup.id, targetId).fold(
                                    onSuccess = {
                                        val cName = contacts.firstOrNull { it.identity == targetId }?.displayName ?: targetId.take(10)
                                        "Žádost o předání správy kruhu odeslána členovi '$cName'. Zůstáváte správcem, dokud ji nepotvrdí."
                                    },
                                    onFailure = { it.message ?: "Předání správy selhalo." }
                                )
                            }
                        }
                    }
                ) {
                    Text("Odeslat předání", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showHandoverDialog = false }) {
                    Text("Zrušit", color = Color(0xFF94A3B8))
                }
            }
        )
    }

    // Dialog pro zrušení kruhu
    if (showDissolveConfirmDialog && currentGroup != null) {
        AlertDialog(
            onDismissRequest = { showDissolveConfirmDialog = false },
            containerColor = Color(0xFF0A101B),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCBD5E1),
            title = { Text("Zrušit kruh '${currentGroup.name}'?", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text(
                    "Kruh bude rozpuštěn a všechna aktivní sdílení vázaná na tento kruh budou odvolána.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF43F5E)),
                    onClick = {
                        showDissolveConfirmDialog = false
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                sl.shareService.dissolveCircle(currentGroup.id)
                            }
                            if (res.isSuccess) {
                                onBackToTree()
                            } else {
                                notice = res.exceptionOrNull()?.message ?: "Kruh se nepodařilo zrušit."
                            }
                        }
                    }
                ) {
                    Text("Zrušit kruh", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDissolveConfirmDialog = false }) {
                    Text("Zpět", color = Color(0xFF94A3B8))
                }
            }
        )
    }

    val categories = listOf(
        Triple(Category.SLEEP, "Spánek" to "Doba spánku, skóre a fáze", R.drawable.img_motif_sleep_night),
        Triple(Category.HRV, "HRV & Regenerace" to "Noční variabilita tepu rMSSD", R.drawable.img_motif_hrv_autonomic),
        Triple(Category.BODY_BATTERY, "Body Battery" to "Nabití energie a denní průběh", R.drawable.img_motif_body_battery),
        Triple(Category.HEART_RATE, "Klidový tep" to "Klidová tepová frekvence", R.drawable.img_motif_heart_pulse),
        Triple(Category.ACTIVITY, "Aktivity & GPS trasy" to "Souhrny aktivit, tempo a trasy", R.drawable.img_alpine_trail_hero),
        Triple(Category.BODY_COMPOSITION, "Váha & Tělo" to "Hmotnost a procento tuku", R.drawable.img_motif_body_silhouette)
    )

    val targetRecipients = remember(currentGroup, contacts, meId) {
        if (currentGroup != null) {
            (currentGroup.members - meId).toSet()
        } else {
            contacts.map { it.identity }.toSet()
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        // Banner pro převzetí správy kruhu
        if (currentGroup?.pendingAdminHandover?.targetMemberId == meId) {
            val initiatorId = currentGroup.pendingAdminHandover!!.initiatedBy
            val initiatorName = contacts.firstOrNull { it.identity == initiatorId }?.displayName ?: initiatorId.take(10)
            Card(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.7f)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF231807)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Nabídka převzetí správy kruhu", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Správce $initiatorName vám předává správu tohoto kruhu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFDE68A)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                notice = withContext(Dispatchers.IO) {
                                    sl.shareService.acceptAdminHandover(currentGroup.id).fold(
                                        onSuccess = { "Správa kruhu '${currentGroup.name}' byla úspěšně převzata." },
                                        onFailure = { it.message ?: "Převzetí správy selhalo." }
                                    )
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B), contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Převzít správu kruhu", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }

        notice?.let { msg ->
            Card(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2942)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(msg, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
        }

        // 1. Přepínače kategorií sdílených do tohoto kruhu (pouze pro skutečné kruhy; virtuální 'Všichni přátelé' odkazuje na globální profil)
        if (currentGroup == null) {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF232A3B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Share, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Přehled všech spárovaných přátel",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                    Text(
                        "„Všichni přátelé“ je souhrnný pohled na všechny spárované kontakty. Jednotlivé přímé sdílení upravíte v Přehledu sdílení a oprávnění; společný profil nastavíte v Nastavení → Globální sdílení.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                    Button(
                        onClick = onOpenSharingOverview,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Otevřít Přehled sdílení a oprávnění")
                    }
                }
            }
        } else {
        Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Share, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Co sdílím do kruhu '${currentGroup.name}'",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Zapněte biometrické domény, které chcete automaticky šifrovaně odesílat členům tohoto kruhu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )

                if (isMinorAccount) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1607)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Účet nezletilého: každé nové nebo rozšířené sdílení se odešle rodiči ke schválení a zapne se až po jeho souhlasu. " +
                                "Zúžení a vypnutí se projeví hned. Data, která už byla doručena, nemusí jít vzít zpět.",
                            Modifier.padding(10.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFDE68A)
                        )
                    }
                    ChildRequestsSection(sl = sl, groupId = currentGroup?.id, onNotice = { notice = it })
                }

                Spacer(Modifier.height(10.dp))
                Text("Zdroj dat pro nová sdílení:", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                ) {
                    GrantEditor.SourceChoice.entries.forEachIndexed { mode, choice ->
                        FilterChip(
                            selected = selectedSourceMode == mode,
                            onClick = { selectedSourceMode = mode },
                            label = { Text(choice.label) }
                        )
                    }
                }

                var pendingGroupEnableCat by remember { mutableStateOf<Pair<Category, String>?>(null) }
                var pendingGroupDisableCat by remember { mutableStateOf<Pair<Category, String>?>(null) }
                var includeActRoute by remember { mutableStateOf(false) }
                var includeActExactTime by remember { mutableStateOf(false) }
                var includeActFullRecord by remember { mutableStateOf(false) }

                pendingGroupEnableCat?.let { (cat, catTitle) ->
                    AlertDialog(
                        onDismissRequest = { pendingGroupEnableCat = null },
                        containerColor = Color(0xFF0A101B),
                        titleContentColor = Color.White,
                        textContentColor = Color(0xFFCBD5E1),
                        title = { Text("Zapnout sdílení: $catTitle", fontWeight = FontWeight.ExtraBold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Zapnutím sdílení do kruhu se členům odešle i posledních 60 dní historie této sekce.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFDE68A)
                                )
                                if (cat == Category.ACTIVITY) {
                                    Text(
                                        "Výchozí volba sdílí pouze souhrn aktivity. Citlivé detaily vyžadují výslovné zapnutí:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = includeActRoute, onCheckedChange = { includeActRoute = it })
                                        Text("GPS trasa (může odhalit polohu)", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = includeActExactTime, onCheckedChange = { includeActExactTime = it })
                                        Text("Přesný čas startu a konce", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = includeActFullRecord, onCheckedChange = { includeActFullRecord = it })
                                        Text("Úplný FIT záznam (obsahuje přesný čas)", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                val chosenSources = GrantEditor.SourceChoice.entries.getOrNull(selectedSourceMode)?.providers
                                val extraAct = buildSet {
                                    if (includeActRoute) add(Field.ACT_ROUTE)
                                    if (includeActExactTime) add(Field.ACT_EXACT_TIME)
                                    if (includeActFullRecord) add(Field.ACT_FULL_RECORD)
                                }
                                pendingGroupEnableCat = null
                                scope.launch {
                                    val preparedGrant = withContext(Dispatchers.IO) {
                                        val now = System.currentTimeMillis()
                                        val self = sl.bridge.ensureStarted().deliveryHash
                                        val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
                                        val res = GrantEditor.toggleGroupCategory(
                                            activeGrants = activeGrants,
                                            groupId = currentGroup?.id,
                                            category = cat,
                                            enable = true,
                                            targetRecipients = targetRecipients,
                                            ownerId = self,
                                            today = today,
                                            nowMs = now,
                                            sources = chosenSources,
                                            newGrantId = UUID.randomUUID().toString(),
                                            extraActivityFields = extraAct,
                                        )
                                        (res as? GrantEditor.GroupToggleResult.Save)?.grant
                                    } ?: return@launch
                                    // Non-ADULT: the service stores a PENDING guardian request instead of the grant and fails with
                                    // ConsentPendingException (its message tells the child the request was sent).
                                    notice = withContext(Dispatchers.IO) {
                                        val now = System.currentTimeMillis()
                                        sl.shareService.saveGrantWithPolicy(preparedGrant).fold(
                                            onSuccess = {
                                                sl.shareService.planAndEnqueue(now)
                                                "Sdílení sekce '$catTitle' do kruhu zapnuto."
                                            },
                                            onFailure = { it.message ?: "Aktivace sdílení selhala." }
                                        )
                                    }
                                }
                            }) { Text(if (isMinorAccount) "Odeslat rodiči ke schválení" else "Potvrdit a zapnout") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingGroupEnableCat = null }) { Text("Zrušit") }
                        }
                    )
                }

                pendingGroupDisableCat?.let { (cat, catTitle) ->
                    AlertDialog(
                        onDismissRequest = { pendingGroupDisableCat = null },
                        containerColor = Color(0xFF0A101B),
                        titleContentColor = Color.White,
                        textContentColor = Color(0xFFCBD5E1),
                        title = { Text("Odvolat sdílení: $catTitle?", fontWeight = FontWeight.ExtraBold) },
                        text = {
                            Text(
                                "Budoucí synchronizace této sekce pro kruh se okamžitě zastaví a data, která nesdílí jiný grant, budou u příjemce smazána. Již doručené kopie v cizích zařízeních nelze technicky vynutit zpět.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                pendingGroupDisableCat = null
                                scope.launch {
                                    notice = withContext(Dispatchers.IO) {
                                        val now = System.currentTimeMillis()
                                        val self = sl.bridge.ensureStarted().deliveryHash
                                        val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
                                        when (val res = GrantEditor.toggleGroupCategory(
                                            activeGrants = activeGrants,
                                            groupId = currentGroup?.id,
                                            category = cat,
                                            enable = false,
                                            targetRecipients = targetRecipients,
                                            ownerId = self,
                                            today = today,
                                            nowMs = now,
                                            sources = null,
                                            newGrantId = "",
                                        )) {
                                            is GrantEditor.GroupToggleResult.Save -> {
                                                sl.shareService.saveGrantWithPolicy(res.grant).fold(
                                                    onSuccess = {
                                                        sl.shareService.planAndEnqueue(now)
                                                        "Sdílení sekce '$catTitle' bylo zúženo."
                                                    },
                                                    onFailure = { it.message ?: "Úprava sdílení selhala." }
                                                )
                                            }
                                            is GrantEditor.GroupToggleResult.Revoke -> {
                                                sl.shareService.revoke(res.grantId, now)
                                                sl.shareService.planAndEnqueue(now)
                                                sl.shareService.scheduleShare()
                                                "Sdílení sekce '$catTitle' bylo odvoláno."
                                            }
                                            GrantEditor.GroupToggleResult.Noop -> "Žádná změna."
                                        }
                                    }
                                }
                            }) { Text("Odvolat") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingGroupDisableCat = null }) { Text("Zrušit") }
                        }
                    )
                }

                if (targetRecipients.isEmpty()) {
                    Text(
                        "V tomto kruhu zatím není žádný další spárovaný příjemce. Přidejte člena níže nebo spárujte kontakt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF59E0B)
                    )
                } else {
                    categories.forEach { (cat, info, bgRes) ->
                        val (title, subtitle) = info
                        val catColor = cat.unifiedColor()
                        val matchingGrant = GrantEditor.findGroupCategoryGrant(activeGrants, currentGroup?.id, cat)
                        val isEnabled = matchingGrant != null
                        val otherCoverage = GrantEditor.otherCoverageRecipients(activeGrants, currentGroup?.id, targetRecipients, cat)
                        val grantSourceText = matchingGrant?.let { g ->
                            GrantEditor.sourceChoiceFrom(g.sources)?.let { " · ${it.label}" }.orEmpty() +
                                (consentUiLabel(grantStates[g.id] ?: ConsentUiState.NOT_REQUIRED)?.let { " · $it" } ?: "")
                        }.orEmpty()
                        val otherInfoText = if (!isEnabled && otherCoverage.isNotEmpty()) {
                            " · ${otherCoverage.size} členů už tato data dostává jinak"
                        } else {
                            ""
                        }

                        Card(
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, if (isEnabled) catColor.copy(alpha = 0.55f) else Color(0xFF1E293B)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1524)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Box(Modifier.fillMaxWidth()) {
                                Image(
                                    painter = painterResource(id = bgRes),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .matchParentSize()
                                        .alpha(if (isEnabled) 0.28f else 0.12f)
                                )
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(Color(0xE60B111E), Color(0xCC0E1524))
                                            )
                                        )
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .background(catColor.copy(alpha = if (isEnabled) 0.22f else 0.10f), RoundedCornerShape(11.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                cat.unifiedIcon(),
                                                contentDescription = null,
                                                tint = if (isEnabled) catColor else Color(0xFF64748B),
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                            Text("$subtitle$grantSourceText$otherInfoText", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1))
                                        }
                                    }
                                    Switch(
                                        checked = isEnabled,
                                        onCheckedChange = { enable ->
                                            if (enable) {
                                                includeActRoute = false
                                                includeActExactTime = false
                                                includeActFullRecord = false
                                                pendingGroupEnableCat = cat to title
                                            } else {
                                                pendingGroupDisableCat = cat to title
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        Spacer(Modifier.height(12.dp))

        // 2. Členové tohoto kruhu + role Správce/Člen + možnost přidat/odebrat člena
        Card(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101B)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.People, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Členové kruhu (${participantIds.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                    Row {
                        TextButton(onClick = onOpenAddFamily) {
                            Icon(Icons.Outlined.FamilyRestroom, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Přidat dítě", color = Color(0xFFA855F7))
                        }
                        TextButton(onClick = onOpenPairing) {
                            Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Nový kontakt")
                        }
                    }
                }

                val effectiveAdmins = currentGroup?.effectiveAdmins ?: emptySet()
                participantIds.forEach { memberId ->
                    val isMe = memberId == meId
                    val isAdmin = memberId in effectiveAdmins
                    val contact = contacts.firstOrNull { it.identity == memberId }
                    val name = if (isMe) "Já (vlastník zařízení)" else contact?.displayName ?: memberId.take(10)
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (isMe) Icons.Outlined.Star else Icons.Outlined.Person,
                                        contentDescription = null,
                                        tint = if (isMe) Color(0xFFFFB300) else Color(0xFF38BDF8),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    if (currentGroup != null) {
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(
                                                    if (isAdmin) Color(0xFFF59E0B).copy(alpha = 0.2f)
                                                    else Color(0xFF38BDF8).copy(alpha = 0.14f)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                if (isAdmin) "Správce" else "Člen",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp,
                                                color = if (isAdmin) Color(0xFFF59E0B) else Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    if (isMe) "Lokální suverénní uzel" else "KEY FP: ${contact?.fingerprint ?: memberId.take(12)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFamily,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp
                                )
                            }
                            if (!isMe && currentGroup != null) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            notice = withContext(Dispatchers.IO) {
                                                sl.shareService.removeMemberFromGroup(currentGroup, memberId)
                                                "Člen '$name' odebrán z kruhu."
                                            }
                                        }
                                    }
                                ) {
                                    Text("Odebrat", color = Color(0xFFF43F5E))
                                }
                            }
                        }
                        if (!isMe && currentGroup != null && isSelfAdmin) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 22.dp, top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!isAdmin) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                notice = withContext(Dispatchers.IO) {
                                                    sl.shareService.promoteCircleAdmin(currentGroup.id, memberId).fold(
                                                        onSuccess = { "Člen '$name' jmenován správcem kruhu." },
                                                        onFailure = { it.message ?: "Nepodařilo se jmenovat správce." }
                                                    )
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f))
                                    ) {
                                        Text("Jmenovat správcem", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF59E0B))
                                    }
                                } else if (effectiveAdmins.size > 1) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                notice = withContext(Dispatchers.IO) {
                                                    sl.shareService.demoteCircleAdmin(currentGroup.id, memberId).fold(
                                                        onSuccess = { "Členu '$name' bylo odebráno správcovství." },
                                                        onFailure = { it.message ?: "Nepodařilo se odebrat správcovství." }
                                                    )
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, Color(0xFF94A3B8).copy(alpha = 0.4f))
                                    ) {
                                        Text("Odebrat správcovství", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1))
                                    }
                                }
                            }
                        }
                    }
                }

                // Pokud jde o uživatelský kruh a máme kontakty, které ještě nejsou v kruhu, nabídneme jejich přidání
                if (currentGroup != null) {
                    val nonMembers = contacts.filter { it.identity !in currentGroup.members }
                    if (nonMembers.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Přidat do kruhu z vašich kontaktů:",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                        FlowRow(Modifier.padding(top = 4.dp)) {
                            nonMembers.forEach { c ->
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            notice = withContext(Dispatchers.IO) {
                                                val updated = currentGroup.copy(
                                                    members = currentGroup.members + c.identity,
                                                    version = currentGroup.version + 1
                                                )
                                                sl.shareService.saveAndBroadcastGroup(updated)
                                                "Kontakt '${c.displayName}' byl přidán do kruhu '${updated.name}'."
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
                            val isLastAdmin = isSelfAdmin && currentGroup.effectiveAdmins.size == 1
                            val otherMembers = currentGroup.members - meId
                            if (isLastAdmin && otherMembers.isNotEmpty()) {
                                showHandoverDialog = true
                            } else if (isLastAdmin && otherMembers.isEmpty()) {
                                showDissolveConfirmDialog = true
                            } else {
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        sl.shareService.leaveCircle(currentGroup.id)
                                    }
                                    if (res.isSuccess) {
                                        onBackToTree()
                                    } else {
                                        notice = res.exceptionOrNull()?.message ?: "Kruh nelze opustit."
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF59E0B)),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Opustit kruh", fontWeight = FontWeight.Bold)
                    }

                    if (isSelfAdmin) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showDissolveConfirmDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF43F5E)),
                            border = BorderStroke(1.dp, Color(0xFFF43F5E).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Zrušit kruh '${currentGroup.name}'", fontWeight = FontWeight.Bold)
                        }
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
internal fun PairingModalDialog(
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
        containerColor = Color(0xFF0A101B),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFCBD5E1),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.QrCode, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("P2P Párování kontaktů", fontWeight = FontWeight.ExtraBold)
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
                            androidx.compose.material3.Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color.White,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Image(bmp.asImageBitmap(), "QR kód", Modifier.size(190.dp).padding(8.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "KEY FP: $fp",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF2DD4BF)
                            )
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
                                Text("Kopírovat můj párovací kód", fontWeight = FontWeight.Bold)
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
                        Text("Naskenovat QR kód kamerou", fontWeight = FontWeight.Bold)
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
                        Text("Ověřit vložený kód", fontWeight = FontWeight.Bold)
                    }

                    pending?.let { p ->
                        Card(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.4f)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1624))
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Person, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(p.payload.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                }
                                Text(
                                    "KEY FP: ${p.fingerprint}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = Color(0xFF94A3B8)
                                )
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
                                    Text("Uložit kontakt", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                msg?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF2DD4BF))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Zavřít", color = Color(0xFF94A3B8))
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
    val isMinorAccount by sl.settings.isMinorFlow.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A101B),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFCBD5E1),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.GroupAdd, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Vytvořit nový kruh", fontWeight = FontWeight.ExtraBold)
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název kruhu (např. Běžci, Rodina)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (contacts.isEmpty()) {
                    Text(
                        "Zatím nemáte spárované kontakty — kruh vytvoříte pro sebe a členy můžete přidat později.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                } else {
                    Text("Vyberte členy kruhu:", style = MaterialTheme.typography.labelMedium)
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
                if (isMinorAccount) {
                    Text(
                        "Kruh se založí až po souhlasu rodiče. Dokud ho rodič neschválí, nikdo další o kruhu nedostane zprávu.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFDE68A),
                        modifier = Modifier.padding(top = 6.dp)
                    )
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
                                sl.shareService.saveAndBroadcastGroup(g)
                                // A non-ADULT sends no membership message until a guardian approves the new circle.
                                if (sl.shareService.ageState() != app.pulse4.sharing.AgeState.ADULT) {
                                    sl.shareService.requestCircleMembership(g, app.pulse4.sharing.MembershipAction.CREATE)
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
                Text(if (isMinorAccount) "Vytvořit a požádat rodiče" else "Vytvořit a otevřít", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Zrušit", color = Color(0xFF94A3B8))
            }
        }
    )
}

/**
 * Modální dialog pro přidání dítěte / rodinného člena z existujících P2P kontaktů.
 */
@Composable
private fun AddFamilyLinkModalDialog(
    sl: ServiceLocator,
    contacts: List<ContactRow>,
    onOpenPairing: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedContact by remember { mutableStateOf(contacts.firstOrNull()) }
    var selectedProfile by remember { mutableStateOf(AgeProfile.MINOR) }
    var confirmationMessage by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A101B),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFCBD5E1),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FamilyRestroom, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Přidat dítě / člena rodiny", fontWeight = FontWeight.ExtraBold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (confirmationMessage != null) {
                    Text(
                        confirmationMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF10B981)
                    )
                } else if (contacts.isEmpty()) {
                    Text(
                        "Zatím nemáte žádný spárovaný kontakt. Nejprve spárujte zařízení dítěte nebo člena rodiny přes QR kód.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )
                    OutlinedButton(
                        onClick = onOpenPairing,
                        border = BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.7f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.QrCode, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Spárovat nový kontakt", color = Color(0xFF2DD4BF))
                    }
                } else {
                    Text("1. Vyberte spárovaný kontakt:", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        contacts.forEach { c ->
                            FilterChip(
                                selected = selectedContact?.identity == c.identity,
                                onClick = { selectedContact = c },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text(c.displayName) }
                            )
                        }
                    }

                    Text("2. Věkový profil účtu:", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = selectedProfile == AgeProfile.MINOR,
                            onClick = { selectedProfile = AgeProfile.MINOR },
                            label = { Text("Dítě / nezletilý") }
                        )
                        FilterChip(
                            selected = selectedProfile == AgeProfile.ADULT,
                            onClick = { selectedProfile = AgeProfile.ADULT },
                            label = { Text("Dospělý člen") }
                        )
                    }

                    Text(
                        "Po potvrzení nevznikne automatické sdílení zdravotních dat — oprávnění se nastavují zvlášť.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
                errorMsg?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (confirmationMessage != null) {
                Button(onClick = onDismiss) {
                    Text("Hotovo", fontWeight = FontWeight.Bold)
                }
            } else if (contacts.isNotEmpty()) {
                Button(
                    enabled = selectedContact != null,
                    onClick = {
                        val contact = selectedContact ?: return@Button
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    sl.shareService.sendFamilyLinkRequest(contact.identity, selectedProfile)
                                }
                                confirmationMessage =
                                    "Žádost o rodinné propojení odeslána. Po potvrzení nevznikne automatické sdílení zdravotních dat — oprávnění se nastavují zvlášť."
                            } catch (e: Exception) {
                                errorMsg = "Chyba: ${e.message}"
                            }
                        }
                    }
                ) {
                    Text("Odeslat žádost", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (confirmationMessage == null) {
                TextButton(onClick = onDismiss) {
                    Text("Zrušit", color = Color(0xFF94A3B8))
                }
            }
        }
    )
}
