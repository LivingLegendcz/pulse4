@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.pulse4.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse4.android.R
import app.pulse4.android.data.RoomPlanStore
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.ui.theme.JetBrainsMonoFamily
import app.pulse4.domain.Sport
import app.pulse4.provider.ProviderError
import app.pulse4.sync.Wire
import app.pulse4.training.ChangeProposal
import app.pulse4.training.ExportLevel
import app.pulse4.training.ExportOutcome
import app.pulse4.training.PlanOp
import app.pulse4.training.PlanRevision
import app.pulse4.training.ScheduledWorkout
import app.pulse4.training.StepType
import app.pulse4.training.SubmitResult
import app.pulse4.training.WorkoutNode
import app.pulse4.ui.DateInput
import app.pulse4.ui.IntervalForm
import app.pulse4.ui.Labels
import app.pulse4.ui.StepForm
import app.pulse4.ui.WorkoutDraft
import app.pulse4.ui.WorkoutText
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class WorkoutTemplatePreset(
    val title: String,
    val subtitle: String,
    val tag: String,
    val sport: Sport,
    val accent: Color,
    val artworkRes: Int,
    val builder: () -> WorkoutDraft
)

/**
 * Tréninková Architektura & Garmin Export:
 * Ambiciózní Nordic-Cybernetic Observatory editor strukturovaných tréninků (běh & kolo),
 * fotografické 1-tap šablony, týmový P2P plán a přímý binární FIT export do Garmin Connect.
 */
@Composable
fun TrainingScreen(sl: ServiceLocator) {
    val scope = rememberCoroutineScope()
    var draft by remember {
        mutableStateOf(
            WorkoutDraft.empty()
                .rename("Prahové intervaly 4×1 km")
                .sport(Sport.RUNNING)
                .add(StepForm.build(StepType.WARMUP, "time", "600"))
                .add(IntervalForm.build(Sport.RUNNING, "4", "distance", "1000", "120", "230", "245"))
                .add(StepForm.build(StepType.COOLDOWN, "time", "600"))
        )
    }
    var dialog by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var dateText by remember { mutableStateOf(LocalDate.now().plusDays(1).toString()) }

    val planId by io(Unit) { "plan-" + sl.bridge.ensureStarted().deliveryHash.take(8) }
    val head by io(planId to refresh) { planId?.let { id -> RoomPlanStore(sl.db).head(id) } }
    val proposals by sl.db.plans().openProposals().collectAsState(emptyList())
    val date = DateInput.parse(dateText, LocalDate.now())
    val scheduledList = head?.workouts?.sortedBy { it.date }.orEmpty()

    val templates = remember {
        listOf(
            WorkoutTemplatePreset(
                title = "Prahové 4×1 km",
                subtitle = "Běh · Tempo 3:50–4:05/km",
                tag = "4×1000m · Z4",
                sport = Sport.RUNNING,
                accent = Color(0xFF2DD4BF),
                artworkRes = R.drawable.img_motif_running_stride,
                builder = {
                    WorkoutDraft.empty()
                        .rename("Prahové intervaly 4×1 km")
                        .sport(Sport.RUNNING)
                        .add(StepForm.build(StepType.WARMUP, "time", "600"))
                        .add(IntervalForm.build(Sport.RUNNING, "4", "distance", "1000", "120", "230", "245"))
                        .add(StepForm.build(StepType.COOLDOWN, "time", "600"))
                }
            ),
            WorkoutTemplatePreset(
                title = "VO2max 6×400 m",
                subtitle = "Běh · Rychlé úseky na dráze",
                tag = "6×400m · Z5",
                sport = Sport.RUNNING,
                accent = Color(0xFFF59E0B),
                artworkRes = R.drawable.img_alpine_trail_hero,
                builder = {
                    WorkoutDraft.empty()
                        .rename("VO2max 6×400 m")
                        .sport(Sport.RUNNING)
                        .add(StepForm.build(StepType.WARMUP, "time", "900"))
                        .add(IntervalForm.build(Sport.RUNNING, "6", "distance", "400", "90", "195", "210"))
                        .add(StepForm.build(StepType.COOLDOWN, "time", "600"))
                }
            ),
            WorkoutTemplatePreset(
                title = "Regenerační Z2",
                subtitle = "Běh · 45 min souvisle",
                tag = "45 MIN · Z2",
                sport = Sport.RUNNING,
                accent = Color(0xFF38BDF8),
                artworkRes = R.drawable.img_nordic_wellness_hero,
                builder = {
                    WorkoutDraft.empty()
                        .rename("Aerobní výklus Z2")
                        .sport(Sport.RUNNING)
                        .add(StepForm.build(StepType.WARMUP, "time", "300"))
                        .add(StepForm.build(StepType.WORK, "time", "2100"))
                        .add(StepForm.build(StepType.COOLDOWN, "time", "300"))
                }
            ),
            WorkoutTemplatePreset(
                title = "Sweetspot 3×10 min",
                subtitle = "Kolo · 240–275 W",
                tag = "3×10m · FTP",
                sport = Sport.CYCLING,
                accent = Color(0xFFA855F7),
                artworkRes = R.drawable.img_sport_cycling_hero,
                builder = {
                    WorkoutDraft.empty()
                        .rename("Cyklistický Sweetspot 3×10m")
                        .sport(Sport.CYCLING)
                        .add(StepForm.build(StepType.WARMUP, "time", "600"))
                        .add(IntervalForm.build(Sport.CYCLING, "3", "time", "600", "180", "240", "275"))
                        .add(StepForm.build(StepType.COOLDOWN, "time", "600"))
                }
            )
        )
    }

    val activeHeroArt = if (draft.workout.sport == Sport.CYCLING) {
        R.drawable.img_sport_cycling_hero
    } else {
        R.drawable.img_motif_running_stride
    }
    val activeHeroAccent = if (draft.workout.sport == Sport.CYCLING) {
        Color(0xFF38BDF8)
    } else {
        Color(0xFF2DD4BF)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // 1. Ambiciózní Observatory Hero Banner (sjednocený se záložkami Metriky, Aktivity, Skupiny a Nastavení)
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF070C14)),
            border = BorderStroke(
                1.2.dp,
                Brush.linearGradient(
                    listOf(
                        activeHeroAccent.copy(alpha = 0.60f),
                        Color(0xFFA855F7).copy(alpha = 0.35f),
                        Color(0xFF1E293B)
                    )
                )
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = activeHeroArt),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(0.76f)
                )
                Canvas(Modifier.matchParentSize()) {
                    val w = size.width
                    val h = size.height
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color(0x99050911),
                            0.45f to Color(0xB3060B14),
                            1.0f to Color(0xF2050911)
                        )
                    )
                    // Procedurální radarové prstence a telemetrická křivka na pozadí
                    val center = Offset(w * 0.84f, h * 0.28f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(activeHeroAccent.copy(alpha = 0.25f), Color.Transparent),
                            center = center,
                            radius = w * 0.45f
                        ),
                        center = center,
                        radius = w * 0.45f
                    )
                    listOf(0.14f, 0.24f, 0.35f).forEachIndexed { idx, rFrac ->
                        drawCircle(
                            color = activeHeroAccent.copy(alpha = 0.16f - idx * 0.04f),
                            radius = w * rFrac,
                            center = center,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                    val wave = Path()
                    val pts = 24
                    for (i in 0..pts) {
                        val t = i.toFloat() / pts
                        val x = w * (0.38f + t * 0.62f)
                        val y = h * 0.32f - kotlin.math.sin(t * Math.PI * 3.0).toFloat() * (h * 0.09f)
                        if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
                    }
                    drawPath(
                        path = wave,
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, activeHeroAccent.copy(alpha = 0.45f), Color(0xFFA855F7).copy(alpha = 0.55f))
                        ),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                Column(Modifier.padding(18.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .background(activeHeroAccent, CircleShape)
                            )
                            Text(
                                "GARMIN WORKOUT CODEC · P2P PLÁN",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = activeHeroAccent,
                                letterSpacing = 1.3.sp,
                                fontSize = 9.5.sp
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0xCC0A1320),
                            border = BorderStroke(1.dp, activeHeroAccent.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = if (draft.workout.sport == Sport.CYCLING) "FIT · CYKLISTIKA" else "FIT · BĚH",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                fontWeight = FontWeight.Bold,
                                color = activeHeroAccent,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tréninková Architektura",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Sestavte strukturovaný trénink s intervaly a odešlete jej přímo do hodinek Garmin nebo do sdíleného týmového P2P plánu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCBD5E1)
                    )

                    Spacer(Modifier.height(14.dp))

                    // 3-sloupcový skleněný KPI Bento pás (sjednocený se Skupinami a Aktivitami)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xB3060B13),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${draft.workout.nodes.size}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    fontWeight = FontWeight.ExtraBold,
                                    color = activeHeroAccent
                                )
                                Text(
                                    "KROKY V EDITORU",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 9.sp
                                )
                            }
                            Box(
                                Modifier
                                    .width(1.dp)
                                    .height(26.dp)
                                    .background(Color.White.copy(alpha = 0.08f))
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp)
                            ) {
                                Text(
                                    "${scheduledList.size}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                Text(
                                    "V KALENDÁŘI",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 9.sp
                                )
                            }
                            Box(
                                Modifier
                                    .width(1.dp)
                                    .height(26.dp)
                                    .background(Color.White.copy(alpha = 0.08f))
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp)
                            ) {
                                Text(
                                    head?.let { "REV #${it.revision}" } ?: "P2P MESH",
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (proposals.isNotEmpty()) Color(0xFFF59E0B) else Color(0xFFA855F7)
                                )
                                Text(
                                    if (proposals.isNotEmpty()) "${proposals.size} NÁVRHŮ ČEKÁ" else "STAV PLÁNU",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 2. Fotografické rychlé šablony (1-Tap Presets)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "RYCHLÉ ŠABLONY JEDNOTEK (1-TAP)",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8),
                letterSpacing = 1.1.sp
            )
            Text(
                "${templates.size} PŘEDVOLBY",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64748B),
                fontSize = 9.5.sp
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            templates.forEach { preset ->
                val isCurrentPreset = draft.workout.name == preset.builder().workout.name
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF070D17)),
                    border = BorderStroke(
                        if (isCurrentPreset) 1.5.dp else 1.dp,
                        Brush.horizontalGradient(
                            if (isCurrentPreset) {
                                listOf(preset.accent, Color.White.copy(alpha = 0.55f))
                            } else {
                                listOf(preset.accent.copy(alpha = 0.42f), Color(0xFF1E293B))
                            }
                        )
                    ),
                    modifier = Modifier
                        .width(176.dp)
                        .height(92.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { draft = preset.builder() }
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Image(
                            painter = painterResource(id = preset.artworkRes),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(if (isCurrentPreset) 0.84f else 0.58f)
                        )
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color(0x66040811),
                                        Color(0xD9050912)
                                    )
                                )
                            )
                        }
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(11.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xCC060C16),
                                    border = BorderStroke(1.dp, preset.accent.copy(alpha = 0.5f)),
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (preset.sport == Sport.RUNNING) Icons.AutoMirrored.Outlined.DirectionsRun else Icons.AutoMirrored.Outlined.DirectionsBike,
                                            contentDescription = null,
                                            tint = preset.accent,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = preset.tag,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    fontWeight = FontWeight.Bold,
                                    color = preset.accent,
                                    fontSize = 8.5.sp,
                                    modifier = Modifier
                                        .background(Color(0xCC050912), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Column {
                                Text(
                                    preset.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    preset.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 3. Editor struktury tréninku (Obsidian Glass)
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF090F1A)),
            border = BorderStroke(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        activeHeroAccent.copy(alpha = 0.45f),
                        Color.White.copy(alpha = 0.12f),
                        Color(0xFF1B2B3D)
                    )
                )
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "EDITOR STRUKTURY JEDNOTKY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = activeHeroAccent,
                            letterSpacing = 1.1.sp
                        )
                        Text(
                            "Kroky se kompilují do nativního Garmin FIT formátu",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp
                        )
                    }
                    Row(
                        modifier = Modifier
                            .background(Color(0xFF060B13), RoundedCornerShape(11.dp))
                            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), RoundedCornerShape(11.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(Sport.RUNNING to "Běh", Sport.CYCLING to "Kolo").forEach { (s, l) ->
                            val selected = draft.workout.sport == s
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) activeHeroAccent else Color.Transparent)
                                    .clickable { draft = draft.sport(s) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    l,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (selected) Color(0xFF041E1E) else Color(0xFFCBD5E1)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = draft.workout.name,
                    onValueChange = { draft = draft.rename(it) },
                    label = { Text("Název tréninku v hodinkách Garmin") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(Modifier.height(12.dp))

                if (draft.workout.nodes.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF060B13))
                            .padding(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Zatím žádné kroky. Vyberte šablonu výše nebo přidejte krok níže.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B)
                        )
                    }
                } else {
                    draft.workout.nodes.forEachIndexed { i, n ->
                        val stepAccent = when (n) {
                            is WorkoutNode.Repeat -> Color(0xFFA855F7)
                            is WorkoutNode.Step -> when (n.stepType) {
                                StepType.WARMUP -> Color(0xFFF59E0B)
                                StepType.WORK -> Color(0xFF2DD4BF)
                                StepType.REST, StepType.RECOVERY -> Color(0xFF38BDF8)
                                StepType.COOLDOWN -> Color(0xFF94A3B8)
                            }
                        }
                        val stepLabel = when (n) {
                            is WorkoutNode.Repeat -> "INTERVALOVÝ BLOK · ${n.count}× OPAKOVÁNÍ"
                            is WorkoutNode.Step -> WorkoutText.stepType(n.stepType).uppercase()
                        }

                        Surface(
                            shape = RoundedCornerShape(15.dp),
                            color = Color(0xFF0D1524),
                            border = BorderStroke(1.dp, stepAccent.copy(alpha = 0.38f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = stepAccent.copy(alpha = 0.16f),
                                    border = BorderStroke(1.dp, stepAccent.copy(alpha = 0.45f)),
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = String.format("%02d", i + 1),
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                            fontWeight = FontWeight.ExtraBold,
                                            color = stepAccent,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stepLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = stepAccent,
                                        fontSize = 9.5.sp,
                                        letterSpacing = 0.6.sp
                                    )
                                    Text(
                                        WorkoutText.describe(n),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        fontSize = 12.5.sp
                                    )
                                }
                                IconButton(onClick = { draft = draft.move(i, -1) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Outlined.ArrowUpward, contentDescription = "Nahoru", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = { draft = draft.move(i, 1) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Outlined.ArrowDownward, contentDescription = "Dolů", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = { draft = draft.removeAt(i) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Smazat", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { dialog = "step" },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.6f))
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Přidat krok", color = Color(0xFF2DD4BF), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { dialog = "interval" },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.6f))
                    ) {
                        Icon(Icons.Outlined.Repeat, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Intervalový blok", color = Color(0xFFA855F7), fontWeight = FontWeight.Bold)
                    }
                }

                draft.issues.forEach {
                    Spacer(Modifier.height(6.dp))
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text("Datum v kalendáři (RRRR-MM-DD)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        enabled = draft.valid && planId != null && date is DateInput.Parsed.Ok,
                        onClick = {
                            val picked = (date as DateInput.Parsed.Ok).date.toString()
                            scope.launch {
                                status = withContext(Dispatchers.IO) {
                                    val id = planId!!
                                    val self = sl.bridge.ensureStarted().deliveryHash
                                    val now = System.currentTimeMillis()
                                    val store = RoomPlanStore(sl.db)
                                    if (store.head(id) == null) store.saveRevision(PlanRevision(id, self, 1, null, self, now, emptyList()))
                                    val h = store.head(id)!!
                                    val sw = ScheduledWorkout(UUID.randomUUID().toString(), draft.workout.copy(ownerId = self), self, picked)
                                    when (val r = sl.planService.submit(ChangeProposal(UUID.randomUUID().toString(), id, h.revision, self, listOf(PlanOp.Add(sw)), now), now)) {
                                        is SubmitResult.Accepted -> "Jednotka uložena do tréninkového plánu (revize ${r.revision.revision})"
                                        is SubmitResult.Rejected -> "Nelze přidat: ${r.reason}"
                                        else -> "Návrh čeká na schválení"
                                    }
                                }
                                refresh++
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text("Uložit do plánu", fontWeight = FontWeight.Bold)
                    }
                }

                when (val d = date) {
                    DateInput.Parsed.Invalid -> Text("Neplatné datum (použijte formát RRRR-MM-DD).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    is DateInput.Parsed.Ok -> if (d.inPast) Text("Poznámka: Zvolené datum je v minulosti.", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF59E0B))
                }

                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF10B981))
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // 4. Naplánované tréninky & Garmin Export
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "NAPLÁNOVANÉ JEDNOTKY V KALENDÁŘI",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8),
                letterSpacing = 1.1.sp
            )
            head?.let {
                Text(
                    "REVIZE #${it.revision}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2DD4BF),
                    fontSize = 9.5.sp
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (scheduledList.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF090F1A),
                border = BorderStroke(1.dp, Color(0xFF1A2838)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Outlined.CalendarToday, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("Plán je zatím prázdný", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Uložte připravenou šablonu výše do kalendáře pro přímý export do hodinek Garmin.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                }
            }
        } else {
            scheduledList.forEach { sw ->
                val rec by io(sw.id to refresh) { planId?.let { sl.exportService.records(it).firstOrNull { r -> r.scheduledWorkoutId == sw.id } } }
                val swAccent = if (sw.workout.sport == Sport.CYCLING) Color(0xFF38BDF8) else Color(0xFF2DD4BF)
                val swArt = if (sw.workout.sport == Sport.CYCLING) R.drawable.img_sport_cycling_hero else R.drawable.img_motif_running_stride

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF080E18)),
                    border = BorderStroke(1.dp, swAccent.copy(alpha = 0.40f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = swArt),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0.42f)
                        )
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    listOf(Color(0xE6060B14), Color(0xB3070D17), Color(0x88070D17))
                                )
                            )
                        }
                        Column(Modifier.padding(15.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (sw.workout.sport == Sport.CYCLING) Icons.AutoMirrored.Outlined.DirectionsBike else Icons.AutoMirrored.Outlined.DirectionsRun,
                                            contentDescription = null,
                                            tint = swAccent,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            sw.date,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                            fontWeight = FontWeight.ExtraBold,
                                            color = swAccent
                                        )
                                        Text(
                                            "· ${sw.workout.nodes.size} kroků",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                            color = Color(0xFFCBD5E1)
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        sw.workout.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                }
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xCC0D1826))
                                        .border(BorderStroke(1.dp, swAccent.copy(alpha = 0.35f)), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        rec?.let { Labels.export(it.level) } ?: Labels.export(ExportLevel.LOCAL_ONLY),
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                        color = swAccent,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.5.sp
                                    )
                                }
                            }

                            rec?.let {
                                if (it.blocked) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Výsledek nejasný: nutno ověřit v Garmin Connect (další zápis je zastaven).",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                it.message?.let { m ->
                                    Spacer(Modifier.height(2.dp))
                                    Text(m, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCBD5E1))
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            OutlinedButton(
                                enabled = rec?.blocked != true,
                                onClick = {
                                    scope.launch {
                                        val revision = head?.revision ?: return@launch
                                        status = withContext(Dispatchers.IO) {
                                            try {
                                                when (val o = sl.exportService.run(planId!!, revision, sw.id, sw.date, sw.workout)) {
                                                    is ExportOutcome.Finished -> "Export do Garmin Connect dokončen"
                                                    is ExportOutcome.NeedsVerification -> "Nutno ověřit v Garmin Connect"
                                                    is ExportOutcome.ExternalChange -> "Trénink byl v Garminu změněn mimo aplikaci – nepřepsáno"
                                                    is ExportOutcome.Failed -> "Export selhal: ${o.record.message}"
                                                }
                                            } catch (e: ProviderError) {
                                                Labels.error(e)
                                            }
                                        }
                                        refresh++
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, swAccent.copy(alpha = 0.60f))
                            ) {
                                Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = swAccent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Exportovat do Garmin Connect", color = swAccent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (proposals.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "ČEKAJÍCÍ P2P NÁVRHY ZMĚN PLÁNU",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF59E0B),
                letterSpacing = 1.1.sp
            )
            Spacer(Modifier.height(6.dp))
            proposals.forEach { p ->
                val prop = Wire.json.decodeFromString(ChangeProposal.serializer(), p.json)
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1724)),
                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Od ${prop.authorId.take(8)} · ${prop.ops.size} změn · ${Labels.proposal(app.pulse4.training.ProposalStatus.valueOf(p.status))}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        sl.planService.decide(p.id, true, System.currentTimeMillis(), sl.bridge.ensureStarted().deliveryHash)
                                        sl.shareService.flushPlanNotifications(System.currentTimeMillis())
                                    }
                                    refresh++
                                }
                            }) { Text("Přijmout", color = Color(0xFF2DD4BF), fontWeight = FontWeight.Bold) }
                            TextButton({
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        sl.planService.decide(p.id, false, System.currentTimeMillis(), sl.bridge.ensureStarted().deliveryHash)
                                    }
                                    refresh++
                                }
                            }) { Text("Zamítnout", color = Color(0xFFEF4444)) }
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        "step" -> StepDialog({ dialog = null }) { s -> draft = draft.add(s); dialog = null }
        "interval" -> IntervalDialog(draft.workout.sport, { dialog = null }) { n -> draft = draft.add(n); dialog = null }
    }
}

@Composable
private fun StepDialog(onDismiss: () -> Unit, onOk: (app.pulse4.training.WorkoutNode.Step) -> Unit) {
    var type by remember { mutableStateOf(StepType.WARMUP) }
    var kind by remember { mutableStateOf("time") }
    var value by remember { mutableStateOf("600") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A101B),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFCBD5E1),
        title = { Text("Přidat krok tréninku", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column {
                FlowRow {
                    StepType.entries.forEach { st ->
                        FilterChip(type == st, { type = st }, { Text(WorkoutText.stepType(st)) }, Modifier.padding(2.dp))
                    }
                }
                Row {
                    listOf("time" to "Čas (s)", "distance" to "Vzdálenost (m)", "lap" to "Tlačítko").forEach { (k, l) ->
                        FilterChip(kind == k, { kind = k }, { Text(l) }, Modifier.padding(2.dp))
                    }
                }
                if (kind != "lap") {
                    OutlinedTextField(value, { value = it.filter(Char::isDigit) }, label = { Text("Hodnota") })
                }
            }
        },
        confirmButton = {
            Button(onClick = { onOk(StepForm.build(type, kind, value)) }) {
                Text("Přidat krok", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Zrušit", color = Color(0xFF94A3B8)) } }
    )
}

@Composable
private fun IntervalDialog(sport: Sport, onDismiss: () -> Unit, onOk: (app.pulse4.training.WorkoutNode.Repeat) -> Unit) {
    var count by remember { mutableStateOf("4") }
    var workKind by remember { mutableStateOf("distance") }
    var work by remember { mutableStateOf("1000") }
    var rest by remember { mutableStateOf("120") }
    var lo by remember { mutableStateOf("") }
    var hi by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A101B),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFCBD5E1),
        title = { Text("Přidat intervalový blok", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column {
                OutlinedTextField(count, { count = it.filter(Char::isDigit) }, label = { Text("Počet opakování") })
                Row {
                    listOf("distance" to "Práce (m)", "time" to "Práce (s)").forEach { (k, l) ->
                        FilterChip(workKind == k, { workKind = k }, { Text(l) }, Modifier.padding(2.dp))
                    }
                }
                OutlinedTextField(work, { work = it.filter(Char::isDigit) }, label = { Text("Délka pracovního úseku") })
                OutlinedTextField(rest, { rest = it.filter(Char::isDigit) }, label = { Text("Odpočinek (s)") })
                val unit = if (sport == Sport.RUNNING) "tempo s/km (rychlejší / pomalejší)" else "výkon W (od / do)"
                Text("Cíl práce – $unit, volitelné", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(lo, { lo = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Od") })
                    OutlinedTextField(hi, { hi = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Do") })
                }
            }
        },
        confirmButton = {
            Button(onClick = { onOk(IntervalForm.build(sport, count, workKind, work, rest, lo, hi)) }) {
                Text("Přidat intervaly", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Zrušit", color = Color(0xFF94A3B8)) } }
    )
}
