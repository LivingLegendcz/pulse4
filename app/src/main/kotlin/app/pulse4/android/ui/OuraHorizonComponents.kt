package app.pulse4.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse4.android.R
import app.pulse4.android.data.ActivitySummaryRow
import app.pulse4.android.data.ContactRow
import app.pulse4.android.di.ServiceLocator
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Sport
import app.pulse4.ui.Fmt
import app.pulse4.ui.MetricSeries
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 4 klíčové pilíře holistického monitoringu s bohatými fotografickými a procedurálními světy.
 */
enum class OuraPillar(
    val title: String,
    val shortTitle: String,
    val subtitle: String,
    val primaryColor: Color,
    val secondaryColor: Color,
    val glowColor: Color,
    val icon: ImageVector,
    @DrawableRes val artworkRes: Int,
    @DrawableRes val secondaryArtworkRes: Int,
    val widgetKeys: List<String>
) {
    READINESS(
        title = "Připravenost",
        shortTitle = "Připravenost",
        subtitle = "Polární záře · Autonomní nervový systém & obnova",
        primaryColor = Color(0xFF2DD4BF),    // Electric Opal / Teal
        secondaryColor = Color(0xFF38BDF8),  // Glacier Cyan
        glowColor = Color(0xFF0D9488),
        icon = PulseIcons.Hrv,
        artworkRes = R.drawable.img_nordic_wellness_hero,
        secondaryArtworkRes = R.drawable.img_motif_hrv_autonomic,
        widgetKeys = listOf("hrv", "rhr", "bb", "stress")
    ),
    SLEEP(
        title = "Spánek",
        shortTitle = "Spánek",
        subtitle = "Noční kosmos · Cirkadiánní architektura & hypnogram",
        primaryColor = Color(0xFF818CF8),    // Indigo
        secondaryColor = Color(0xFFC084FC),  // Cosmic Violet
        glowColor = Color(0xFF4F46E5),
        icon = PulseIcons.Sleep,
        artworkRes = R.drawable.img_motif_sleep_night,
        secondaryArtworkRes = R.drawable.img_nordic_wellness_hero,
        widgetKeys = listOf("sleep", "hrv", "rhr")
    ),
    ACTIVITY(
        title = "Aktivita",
        shortTitle = "Aktivita",
        subtitle = "Alpský hřeben · Pohyb, zátěž & tréninkový impuls",
        primaryColor = Color(0xFFF59E0B),    // Solar Amber
        secondaryColor = Color(0xFFFB7185),  // Coral Ember
        glowColor = Color(0xFFD97706),
        icon = PulseIcons.Steps,
        artworkRes = R.drawable.img_alpine_trail_hero,
        secondaryArtworkRes = R.drawable.img_motif_running_stride,
        widgetKeys = listOf("steps", "bb", "stress")
    ),
    BODY(
        title = "Tělo & Cévy",
        shortTitle = "Tělo",
        subtitle = "Bioluminiscence · Bioimpedance, tepny & EKG",
        primaryColor = Color(0xFF38BDF8),    // Glacier Blue
        secondaryColor = Color(0xFF2DD4BF),  // Jade
        glowColor = Color(0xFF0284C7),
        icon = PulseIcons.BodyComposition,
        artworkRes = R.drawable.img_motif_body_water,
        secondaryArtworkRes = R.drawable.img_motif_body_silhouette,
        widgetKeys = listOf("weight", "ecg")
    )
}

enum class OuraRating(val label: String, val color: Color) {
    OPTIMAL("Optimální", Color(0xFF10B981)),
    GOOD("Dobré", Color(0xFF2DD4BF)),
    FAIR("Přijatelné", Color(0xFFF59E0B)),
    ATTENTION("Odpočinek", Color(0xFFEF4444)),
    NO_DATA("Bez dat", Color(0xFF64748B))
}

data class OuraContributor(
    val name: String,
    val value: String,
    val rating: OuraRating,
    val progress: Float,
    val metricType: MetricType? = null,
    val note: String? = null
)

data class OuraPillarState(
    val pillar: OuraPillar,
    val score: Int?,
    val rating: OuraRating,
    val deltaLabel: String,
    val narrative: String,
    val contributors: List<OuraContributor>
)

/**
 * Výpočet 4 pilířů z reálných Garmin & Withings dat.
 */
object OuraScoreCalculator {

    fun calculate(
        seriesByType: Map<MetricType, MetricSeries>,
        todaySleepScore: Double?,
        latestWeightObs: MetricObservation?,
        latestEcgObs: MetricObservation?,
        fmt: Fmt
    ): Map<OuraPillar, OuraPillarState> {
        val hrvSeries = seriesByType[MetricType.HRV]
        val sleepSeries = seriesByType[MetricType.SLEEP_DURATION]
        val bbSeries = seriesByType[MetricType.BODY_BATTERY]
        val rhrSeries = seriesByType[MetricType.RESTING_HEART_RATE]
        val stepsSeries = seriesByType[MetricType.STEPS]
        val stressSeries = seriesByType[MetricType.STRESS]

        val todayHrv = hrvSeries?.values?.lastOrNull()
        val usualHrv = hrvSeries?.usual?.median
        val todaySleepS = sleepSeries?.values?.lastOrNull()
        val todayBb = bbSeries?.values?.lastOrNull()
        val todayRhr = rhrSeries?.values?.lastOrNull()
        val usualRhr = rhrSeries?.usual?.median
        val todaySteps = stepsSeries?.values?.lastOrNull()

        // 1. PŘIPRAVENOST (UX-1: žádné vymyšlené defaulty; bez dat zobrazit "—" + CTA)
        val hasReadinessData = todayHrv != null || todayRhr != null || todayBb != null || todaySleepScore != null
        val hrvDelta = if (todayHrv != null && usualHrv != null) todayHrv - usualHrv else null
        val rhrDelta = if (todayRhr != null && usualRhr != null) usualRhr - todayRhr else null

        val readinessScore: Int? = if (!hasReadinessData) null else {
            var pts = 78.0
            if (hrvDelta != null) pts += (hrvDelta * 1.5).coerceIn(-18.0, 15.0)
            if (rhrDelta != null) pts += (rhrDelta * 1.2).coerceIn(-10.0, 10.0)
            if (todayBb != null) pts += ((todayBb - 60.0) * 0.15).coerceIn(-10.0, 10.0)
            if (todaySleepScore != null) pts += ((todaySleepScore - 75.0) * 0.15).coerceIn(-10.0, 10.0)
            pts.toInt().coerceIn(40, 99)
        }
        val readinessRating = when {
            readinessScore == null -> OuraRating.NO_DATA
            readinessScore >= 85 -> OuraRating.OPTIMAL
            readinessScore >= 70 -> OuraRating.GOOD
            readinessScore >= 55 -> OuraRating.FAIR
            else -> OuraRating.ATTENTION
        }

        val readinessContributors = buildList {
            if (todayHrv != null) {
                add(
                    OuraContributor(
                        name = "HRV rovnováha",
                        value = "${todayHrv.toInt()} ms",
                        rating = when {
                            hrvDelta == null -> OuraRating.GOOD
                            hrvDelta >= 2.0 -> OuraRating.OPTIMAL
                            hrvDelta >= -3.0 -> OuraRating.GOOD
                            else -> OuraRating.FAIR
                        },
                        progress = (todayHrv / 90.0).toFloat().coerceIn(0.1f, 1.0f),
                        metricType = MetricType.HRV,
                        note = hrvDelta?.let { if (it >= 0) "+${it.toInt()} ms vs medián" else "${it.toInt()} ms vs medián" } ?: "Poslední měření"
                    )
                )
            }
            if (todayRhr != null) {
                add(
                    OuraContributor(
                        name = "Klidový tep",
                        value = "${todayRhr.toInt()} bpm",
                        rating = if (rhrDelta != null && rhrDelta >= 0) OuraRating.OPTIMAL else OuraRating.GOOD,
                        progress = (1.0f - ((todayRhr - 40.0) / 45.0).toFloat()).coerceIn(0.2f, 1.0f),
                        metricType = MetricType.RESTING_HEART_RATE,
                        note = usualRhr?.let { "Základna ${it.toInt()} bpm" } ?: "Poslední měření"
                    )
                )
            }
            if (todayBb != null) {
                add(
                    OuraContributor(
                        name = "Body Battery",
                        value = "${todayBb.toInt()}/100",
                        rating = if (todayBb >= 75) OuraRating.OPTIMAL else OuraRating.GOOD,
                        progress = (todayBb / 100.0).toFloat().coerceIn(0.1f, 1.0f),
                        metricType = MetricType.BODY_BATTERY,
                        note = "Garmin energetická rezerva"
                    )
                )
            }
        }

        val readinessDeltaLabel = when {
            !hasReadinessData -> "Připojte Garmin nebo Withings"
            hrvDelta != null -> "${if (hrvDelta >= 0) "+${hrvDelta.toInt()}" else "${hrvDelta.toInt()}"} ms HRV vs medián · Odhad Pulse4"
            todayHrv != null -> "HRV ${todayHrv.toInt()} ms · Odhad Pulse4"
            todayRhr != null -> "Klidový tep ${todayRhr.toInt()} bpm · Odhad Pulse4"
            else -> "Odhad Pulse4"
        }

        val readinessNarrative = when (readinessRating) {
            OuraRating.NO_DATA -> "Zatím nemáte naměřená data HRV, klidového tepu ani spánku. Připojte Garmin nebo Withings."
            OuraRating.OPTIMAL -> if (hrvDelta != null) "Noční HRV je ${if (hrvDelta >= 0) "+${hrvDelta.toInt()}" else "${hrvDelta.toInt()}"} ms oproti vašemu mediánu." else "Naměřené hodnoty ukazují vysokou připravenost."
            OuraRating.GOOD -> "Autonomní nervový systém je v běžném pásmu podle posledních měření."
            OuraRating.FAIR -> "Zotavení je podle naměřených hodnot částečné. Zvažte lehčí zátěž."
            OuraRating.ATTENTION -> "Naměřené hodnoty ukazují zvýšenou únavu. Doporučujeme regeneraci."
        }

        // 2. SPÁNEK
        val sleepScoreVal: Int? = when {
            todaySleepScore != null -> todaySleepScore.toInt()
            todaySleepS != null -> ((todaySleepS / 3600.0) / 8.0 * 88.0).coerceIn(40.0, 96.0).toInt()
            else -> null
        }
        val sleepRating = when {
            sleepScoreVal == null -> OuraRating.NO_DATA
            sleepScoreVal >= 85 -> OuraRating.OPTIMAL
            sleepScoreVal >= 70 -> OuraRating.GOOD
            sleepScoreVal >= 55 -> OuraRating.FAIR
            else -> OuraRating.ATTENTION
        }
        val sleepDeltaLabel = when {
            todaySleepS != null && todaySleepScore != null -> fmt.sleep(todaySleepS)
            todaySleepS != null -> "${fmt.sleep(todaySleepS)} · Odhad Pulse4"
            todaySleepScore != null -> "Skóre ${todaySleepScore.toInt()}"
            else -> "Připojte Garmin"
        }
        val sleepNarrative = if (sleepScoreVal == null) {
            "Zatím není k dispozici žádný záznam spánku."
        } else if (todaySleepS != null) {
            "Zaznamenaná doba spánku: ${fmt.sleep(todaySleepS)}."
        } else {
            "Spánkové skóre ze synchronizovaného zařízení."
        }

        // 3. AKTIVITA
        val activityScore: Int? = todaySteps?.let { steps ->
            val stepsRatio = (steps / 10000.0).coerceIn(0.0, 1.5)
            (64.0 + stepsRatio * 28.0).toInt().coerceIn(40, 98)
        }
        val activityRating = when {
            activityScore == null -> OuraRating.NO_DATA
            activityScore >= 85 -> OuraRating.OPTIMAL
            activityScore >= 70 -> OuraRating.GOOD
            else -> OuraRating.FAIR
        }
        val activityDeltaLabel = if (todaySteps != null) {
            "${fmt.number(todaySteps, 0)} kroků · Odhad Pulse4"
        } else {
            "Připojte Garmin"
        }
        val activityNarrative = if (todaySteps != null) {
            "Zaznamenáno ${fmt.number(todaySteps, 0)} kroků."
        } else {
            "Zatím nejsou naměřená data o denních krocích."
        }

        // 4. TĚLO & CÉVY
        val wKg = latestWeightObs?.value
        val bodyFatVal = seriesByType[MetricType.BODY_FAT]?.values?.lastOrNull()
        val pwvVal = seriesByType[MetricType.PULSE_WAVE_VELOCITY]?.values?.lastOrNull()
        val hasBodyData = wKg != null || bodyFatVal != null || pwvVal != null
        val bodyScore: Int? = if (hasBodyData && wKg != null) 84 else null
        val bodyRating = if (hasBodyData) OuraRating.GOOD else OuraRating.NO_DATA
        val bodyParts = buildList {
            if (wKg != null) add(fmt.weight(wKg))
            if (bodyFatVal != null) add("tuk ${fmt.number(bodyFatVal, 1)} %")
            if (pwvVal != null) add("PWV ${fmt.number(pwvVal, 1)} m/s")
        }
        val bodyDeltaLabel = if (bodyParts.isNotEmpty()) bodyParts.joinToString(" · ") else "Připojte Withings"
        val bodyNarrative = if (bodyParts.isNotEmpty()) {
            "Poslední naměřené hodnoty: ${bodyParts.joinToString(", ")}."
        } else {
            "Zatím nejsou naměřená data hmotnosti, tuku ani cév."
        }

        return mapOf(
            OuraPillar.READINESS to OuraPillarState(
                pillar = OuraPillar.READINESS,
                score = readinessScore,
                rating = readinessRating,
                deltaLabel = readinessDeltaLabel,
                narrative = readinessNarrative,
                contributors = readinessContributors
            ),
            OuraPillar.SLEEP to OuraPillarState(
                pillar = OuraPillar.SLEEP,
                score = sleepScoreVal,
                rating = sleepRating,
                deltaLabel = sleepDeltaLabel,
                narrative = sleepNarrative,
                contributors = emptyList()
            ),
            OuraPillar.ACTIVITY to OuraPillarState(
                pillar = OuraPillar.ACTIVITY,
                score = activityScore,
                rating = activityRating,
                deltaLabel = activityDeltaLabel,
                narrative = activityNarrative,
                contributors = emptyList()
            ),
            OuraPillar.BODY to OuraPillarState(
                pillar = OuraPillar.BODY,
                score = bodyScore,
                rating = bodyRating,
                deltaLabel = bodyDeltaLabel,
                narrative = bodyNarrative,
                contributors = emptyList()
            )
        )
    }
}

/**
 * Hlavní atmosférická karta s živou fotografickou scénou, procedurální polární září / souhvězdími / vrstevnicemi
 * a 4 fotografickými kartami pod mini-kruhy pilířů.
 */
@Composable
fun OuraHorizonHeroCard(
    sl: ServiceLocator,
    pillarStates: Map<OuraPillar, OuraPillarState>,
    selectedPillar: OuraPillar,
    onSelectPillar: (OuraPillar) -> Unit,
    onOpenMetric: (MetricType) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentPillarState = pillarStates[selectedPillar] ?: return
    var showCircadianBar by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "atmosphere_phase")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )
    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseGlow"
    )

    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF050A12)),
        border = BorderStroke(
            1.4.dp,
            Brush.verticalGradient(
                listOf(
                    currentPillarState.pillar.primaryColor.copy(alpha = 0.75f),
                    currentPillarState.pillar.secondaryColor.copy(alpha = 0.35f),
                    Color(0xFF1A283B).copy(alpha = 0.5f)
                )
            )
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(Modifier.fillMaxWidth()) {
            // 1. ŽIVÁ FOTOGRAFICKÁ SCÉNA (Crossfade s vysokou viditelností 88 %, žádné zadusení černou plochou!)
            Crossfade(
                targetState = currentPillarState.pillar,
                animationSpec = tween(600),
                modifier = Modifier.matchParentSize(),
                label = "pillar_bg_crossfade"
            ) { pillar ->
                Box(Modifier.fillMaxSize()) {
                    Image(
                        painter = painterResource(id = pillar.artworkRes),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.88f)
                    )
                    // Jemné prolnutí sekundární textury v dolní části pro extra hloubku
                    Image(
                        painter = painterResource(id = pillar.secondaryArtworkRes),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.24f)
                    )
                }
            }

            // 2. AMBICIÓZNÍ PROCEDURÁLNÍ SCÉNA POD KRUHY (Polární záře, souhvězdí, alpské vrstevnice, bioluminiscence)
            Canvas(Modifier.matchParentSize()) {
                // Jemná vinětace pouze u horního a spodního okraje, střed pod kruhy zůstává jasný a průzračný!
                drawRect(
                    brush = Brush.verticalGradient(
                        0.0f to Color(0x7704080E),
                        0.22f to Color(0x2604080E),
                        0.55f to Color(0x38050A12),
                        0.82f to Color(0xCC050911),
                        1.0f to Color(0xF2050911)
                    )
                )

                // Procedurální umělecká vrstva podle zvoleného pilíře
                drawAmbitiousPillarBackdrop(
                    pillar = selectedPillar,
                    primary = currentPillarState.pillar.primaryColor,
                    secondary = currentPillarState.pillar.secondaryColor,
                    glow = currentPillarState.pillar.glowColor,
                    phase = wavePhase,
                    pulse = pulseGlow
                )
            }

            // 3. OBSAH KARTY NAD SCÉNOU
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 4 FOTOGRAFICKÉ KARTY S MINI-KRUHY PILÍŘŮ (každý kruh má vlastní živou fototexturu a auru!)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OuraPillar.entries.forEach { pillar ->
                        val state = pillarStates[pillar]
                        val isSelected = pillar == selectedPillar
                        PillarScoreOrb(
                            pillar = pillar,
                            score = state?.score,
                            isSelected = isSelected,
                            onClick = { onSelectPillar(pillar) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Centrální Living Halo prstenec s astronomickým/horologickým ciferníkem na průhledném pozadí
                OuraHaloGauge(
                    score = currentPillarState.score,
                    primaryColor = currentPillarState.pillar.primaryColor,
                    secondaryColor = currentPillarState.pillar.secondaryColor,
                    glowColor = currentPillarState.pillar.glowColor,
                    rating = currentPillarState.rating,
                    deltaLabel = currentPillarState.deltaLabel,
                    pillarTitle = currentPillarState.pillar.title,
                    wavePhase = wavePhase
                )

                Spacer(Modifier.height(8.dp))

                // Skleněná kapsle s verdiktem dne (frosted glass podklad pro dokonalou čitelnost nad fotkou)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0x99060C15),
                    border = BorderStroke(
                        1.dp,
                        Brush.horizontalGradient(
                            listOf(
                                currentPillarState.pillar.primaryColor.copy(alpha = 0.40f),
                                Color(0xFF1E2D40).copy(alpha = 0.50f)
                            )
                        )
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = currentPillarState.pillar.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = currentPillarState.pillar.primaryColor,
                            letterSpacing = 0.6.sp
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = currentPillarState.narrative,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFF1F5F9),
                            textAlign = TextAlign.Center,
                            lineHeight = 18.5.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Rozbalovací lišta 24h biometrického horizontu + odznaky zdrojů
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xAA09121E))
                        .border(BorderStroke(1.dp, Color(0xFF1B2B3E)), RoundedCornerShape(14.dp))
                        .clickable { showCircadianBar = !showCircadianBar }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Timeline,
                            contentDescription = null,
                            tint = currentPillarState.pillar.primaryColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (showCircadianBar) "Skrýt 24h cirkadiánní rytmus" else "24h biometrický horizont dne",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE2E8F0)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderBadgeMini(label = "Garmin", color = Color(0xFF38BDF8))
                        Spacer(Modifier.width(5.dp))
                        ProviderBadgeMini(label = "Withings", color = Color(0xFF2DD4BF))
                    }
                }

                if (showCircadianBar) {
                    Spacer(Modifier.height(10.dp))
                    Oura24hBiometricHorizon(
                        selectedPillar = selectedPillar,
                        accentColor = currentPillarState.pillar.primaryColor
                    )
                }
            }
        }
    }
}

/**
 * Vykreslí ambiciózní procedurální uměleckou scénu přímo pod kruhy:
 * - Polární záře a autonomní harmonické vlny pro Připravenost
 * - Souhvězdí, hvězdná mlhovina a orbitální dráhy pro Spánek
 * - Topografické vrstevnice a sluneční paprsky pro Aktivitu
 * - Bioluminiscenční pulzní vlny a buněčná mřížka pro Tělo & Cévy
 */
private fun DrawScope.drawAmbitiousPillarBackdrop(
    pillar: OuraPillar,
    primary: Color,
    secondary: Color,
    glow: Color,
    phase: Float,
    pulse: Float
) {
    val w = size.width
    val h = size.height
    val haloCenter = Offset(w * 0.5f, h * 0.46f)

    // 1. Dýchající vícevrstvá světelná aura za hlavním prstencem
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                primary.copy(alpha = 0.34f * pulse),
                secondary.copy(alpha = 0.16f * pulse),
                Color.Transparent
            ),
            center = haloCenter,
            radius = w * 0.58f
        ),
        center = haloCenter,
        radius = w * 0.58f
    )

    // 2. Jemné orbitální elipsy / soustředné energetické prstence
    for (ringIdx in 1..3) {
        val r = (w * 0.24f) + ringIdx * (w * 0.075f)
        drawCircle(
            color = primary.copy(alpha = 0.14f / ringIdx),
            radius = r,
            center = haloCenter,
            style = Stroke(width = 1.dp.toPx())
        )
    }

    when (pillar) {
        OuraPillar.READINESS -> {
            // Proudy polární záře (Aurora Borealis ribbons)
            for (ribbon in 0..3) {
                val path = Path()
                val baseY = h * (0.24f + ribbon * 0.10f)
                val amp = h * (0.045f + ribbon * 0.012f)
                val steps = 40
                for (s in 0..steps) {
                    val t = s.toFloat() / steps
                    val x = w * t
                    val y = baseY + sin(t * 4.5f + phase + ribbon * 0.8f) * amp +
                        cos(t * 2.2f - phase * 0.6f) * (amp * 0.5f)
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            primary.copy(alpha = 0.35f - ribbon * 0.06f),
                            secondary.copy(alpha = 0.30f - ribbon * 0.05f),
                            Color.Transparent
                        )
                    ),
                    style = Stroke(width = (3.5f - ribbon * 0.6f).dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        OuraPillar.SLEEP -> {
            // Noční souhvězdí a kosmická mlhovina kolem prstence
            val stars = listOf(
                Offset(w * 0.12f, h * 0.28f),
                Offset(w * 0.22f, h * 0.36f),
                Offset(w * 0.18f, h * 0.52f),
                Offset(w * 0.84f, h * 0.26f),
                Offset(w * 0.76f, h * 0.38f),
                Offset(w * 0.88f, h * 0.48f),
                Offset(w * 0.78f, h * 0.58f)
            )
            // Spojnice souhvězdí
            val constellation = Path().apply {
                moveTo(stars[0].x, stars[0].y)
                lineTo(stars[1].x, stars[1].y)
                lineTo(stars[2].x, stars[2].y)
                moveTo(stars[3].x, stars[3].y)
                lineTo(stars[4].x, stars[4].y)
                lineTo(stars[5].x, stars[5].y)
                lineTo(stars[6].x, stars[6].y)
            }
            drawPath(
                path = constellation,
                color = secondary.copy(alpha = 0.32f),
                style = Stroke(width = 1.dp.toPx())
            )
            stars.forEachIndexed { idx, pt ->
                val twinkle = 0.5f + 0.5f * sin(phase + idx * 1.1f)
                drawCircle(
                    color = Color.White.copy(alpha = 0.45f + 0.45f * twinkle),
                    radius = (2.2f + (idx % 2) * 1.2f).dp.toPx(),
                    center = pt
                )
                drawCircle(
                    color = primary.copy(alpha = 0.25f * twinkle),
                    radius = 7.dp.toPx(),
                    center = pt
                )
            }
        }

        OuraPillar.ACTIVITY -> {
            // Topografické vrstevnice a sluneční paprsky nad alpským hřebenem
            for (iso in 0..4) {
                val path = Path()
                val yOffset = h * (0.28f + iso * 0.08f)
                val steps = 36
                for (s in 0..steps) {
                    val t = s.toFloat() / steps
                    val x = w * t
                    val ridge = sin(t * 3.8f + iso * 0.5f) * (h * 0.04f) -
                        kotlin.math.abs(t - 0.5f) * (h * 0.06f)
                    val y = yOffset + ridge
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            primary.copy(alpha = 0.32f - iso * 0.05f),
                            secondary.copy(alpha = 0.28f - iso * 0.04f),
                            Color.Transparent
                        )
                    ),
                    style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        OuraPillar.BODY -> {
            // Bioluminiscenční cévní a bioimpedanční harmonické vlny
            for (wave in 0..3) {
                val path = Path()
                val steps = 45
                val baseY = h * (0.32f + wave * 0.07f)
                for (s in 0..steps) {
                    val t = s.toFloat() / steps
                    val x = w * t
                    val env = sin(t * PI).toFloat()
                    val y = baseY + sin(t * 8f - phase + wave * 0.7f) * (h * 0.045f) * env
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            primary.copy(alpha = 0.38f - wave * 0.06f),
                            secondary.copy(alpha = 0.34f - wave * 0.06f),
                            Color.Transparent
                        )
                    ),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}

/**
 * Fotografická karta s mini-prstencem pilíře:
 * Každý ze 4 kruhů nahoře má vlastní živé fotografické pozadí (polární záře, noční obloha, alpské štíty, vodní struktura)
 * a svítící skleněný rámeček.
 */
@Composable
private fun PillarScoreOrb(
    pillar: OuraPillar,
    score: Int?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(86.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(
                BorderStroke(
                    if (isSelected) 1.5.dp else 1.dp,
                    if (isSelected) {
                        Brush.verticalGradient(listOf(pillar.primaryColor, pillar.secondaryColor))
                    } else {
                        Brush.verticalGradient(listOf(Color(0x66334155), Color(0x331E293B)))
                    }
                ),
                RoundedCornerShape(18.dp)
            )
            .clickable { onClick() }
    ) {
        // Fotografická textura přímo pod mini-kruhem!
        Image(
            painter = painterResource(id = pillar.artworkRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .alpha(if (isSelected) 0.85f else 0.48f)
        )

        // Jemný gradient a barevná aura uvnitř mini-karty
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(
                        Color(0x44050911),
                        if (isSelected) pillar.glowColor.copy(alpha = 0.42f) else Color(0xAA060B12),
                        Color(0xCC050911)
                    )
                )
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 3.5.dp.toPx()
                    drawCircle(
                        color = Color(0x66050911),
                        radius = size.minDimension / 2f - stroke / 2f
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.16f),
                        style = Stroke(width = stroke)
                    )
                    if (score != null) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                listOf(pillar.primaryColor, pillar.secondaryColor, pillar.primaryColor)
                            ),
                            startAngle = -90f,
                            sweepAngle = (score / 100f) * 360f,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                }
                Text(
                    text = score?.toString() ?: "—",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    fontSize = 12.5.sp
                )
            }
            Spacer(Modifier.height(5.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = pillar.icon,
                    contentDescription = null,
                    tint = if (isSelected) pillar.primaryColor else Color(0xFF94A3B8),
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    text = pillar.shortTitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Hlavní světelný prstenec (Living Halo Gauge) s jemným chronografickým ciferníkem
 * a průhledným středem, kterým prosvítá živá fotografická a procedurální scéna.
 */
@Composable
fun OuraHaloGauge(
    score: Int?,
    primaryColor: Color,
    secondaryColor: Color,
    glowColor: Color,
    rating: OuraRating,
    deltaLabel: String,
    pillarTitle: String = "",
    wavePhase: Float = 0f,
    modifier: Modifier = Modifier
) {
    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(score, pillarTitle) {
        animatedProgress.animateTo(
            targetValue = (score ?: 0) / 100f,
            animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(204.dp)) {
            val strokeW = 14.dp.toPx()
            val arcSize = size.width - strokeW * 1.6f
            val topLeft = Offset(strokeW * 0.8f, strokeW * 0.8f)

            // Vnější chronografická stupnice (60 jemných rysů kolem prstence)
            val outerR = size.width / 2f - 2.dp.toPx()
            for (tick in 0 until 60) {
                val angleDeg = tick * 6f
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val isMajor = tick % 5 == 0
                val tickLen = if (isMajor) 6.dp.toPx() else 3.dp.toPx()
                val p1 = Offset(
                    center.x + (outerR * cos(angleRad)).toFloat(),
                    center.y + (outerR * sin(angleRad)).toFloat()
                )
                val p2 = Offset(
                    center.x + ((outerR - tickLen) * cos(angleRad)).toFloat(),
                    center.y + ((outerR - tickLen) * sin(angleRad)).toFloat()
                )
                drawLine(
                    color = if (isMajor) primaryColor.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.16f),
                    start = p1,
                    end = p2,
                    strokeWidth = if (isMajor) 1.5.dp.toPx() else 0.8.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Jemný poloprůhledný optický závoj uprostřed (aby vynikla číslice, ale stále prosvítala fotka a polární záře!)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x66040810),
                        glowColor.copy(alpha = 0.22f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = arcSize / 2f
                ),
                radius = arcSize / 2f,
                center = center
            )

            // Vnitřní jemný kompasový kruh
            drawCircle(
                color = primaryColor.copy(alpha = 0.22f),
                radius = (arcSize / 2f) - strokeW * 0.9f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            val startAngle = 140f
            val maxSweep = 260f

            // Podkladová dráha prstence (poloprůhledné sklo)
            drawArc(
                color = Color.White.copy(alpha = 0.14f),
                startAngle = startAngle,
                sweepAngle = maxSweep,
                useCenter = false,
                topLeft = topLeft,
                size = Size(arcSize, arcSize),
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )

            val activeSweep = maxSweep * animatedProgress.value
            if (activeSweep > 0f) {
                // Měkká vnější záře aktivního oblouku
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to primaryColor.copy(alpha = 0.38f),
                        0.5f to secondaryColor.copy(alpha = 0.38f),
                        1.0f to primaryColor.copy(alpha = 0.38f)
                    ),
                    startAngle = startAngle,
                    sweepAngle = activeSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(arcSize, arcSize),
                    style = Stroke(width = strokeW * 1.55f, cap = StrokeCap.Round)
                )

                // Hlavní svítící oblouk
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to primaryColor,
                        0.5f to secondaryColor,
                        1.0f to primaryColor
                    ),
                    startAngle = startAngle,
                    sweepAngle = activeSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(arcSize, arcSize),
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )

                val endAngleDeg = startAngle + activeSweep
                val endAngleRad = Math.toRadians(endAngleDeg.toDouble())
                val rMid = arcSize / 2f
                val tipCenter = Offset(
                    center.x + (rMid * cos(endAngleRad)).toFloat(),
                    center.y + (rMid * sin(endAngleRad)).toFloat()
                )
                drawCircle(color = Color.White, radius = 4.2.dp.toPx(), center = tipCenter)
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (pillarTitle.isNotBlank()) {
                Text(
                    text = pillarTitle.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = primaryColor,
                    letterSpacing = 1.6.sp,
                    fontSize = 10.sp
                )
            }
            Text(
                text = score?.toString() ?: "—",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                fontSize = 52.sp,
                letterSpacing = (-1.5).sp
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x99060C14))
                    .border(BorderStroke(1.dp, rating.color.copy(alpha = 0.70f)), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 2.5.dp)
            ) {
                Text(
                    text = rating.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = rating.color,
                    fontSize = 9.5.sp,
                    letterSpacing = 0.8.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = deltaLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE2E8F0),
                fontSize = 11.sp
            )
        }
    }
}

/**
 * 24hodinový biometrický horizont (rozbalovací na vyžádání).
 */
@Composable
fun Oura24hBiometricHorizon(
    selectedPillar: OuraPillar,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val curHour = LocalTime.now().hour

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC090F17))
            .border(BorderStroke(1.dp, Color(0xFF1E293B)), RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0xFF141C27))
        ) {
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(7f)
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(Color(0xFF4338CA), Color(0xFF818CF8)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("SPÁNEK", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Box(
                    Modifier
                        .weight(5f)
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(Color(0xFF0F766E), Color(0xFF2DD4BF)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("HRV VRCHOL", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF042F2E))
                }
                Box(
                    Modifier
                        .weight(6f)
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(Color(0xFFB45309), Color(0xFFF59E0B)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("AKTIVITA", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3B1D00))
                }
                Box(
                    Modifier
                        .weight(6f)
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(Color(0xFF1E293B), Color(0xFF334155)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("ZKLIDNĚNÍ", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCBD5E1))
                }
            }

            val currentProgress = (curHour / 24f).coerceIn(0f, 1f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val markerX = size.width * currentProgress
                drawLine(
                    color = Color.White,
                    start = Offset(markerX, 0f),
                    end = Offset(markerX, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("00:00", fontSize = 8.5.sp, color = Color(0xFF94A3B8))
            Text("06:00", fontSize = 8.5.sp, color = Color(0xFF94A3B8))
            Text("12:00", fontSize = 8.5.sp, color = Color(0xFF94A3B8))
            Text("18:00", fontSize = 8.5.sp, color = Color(0xFF94A3B8))
            Text("24:00", fontSize = 8.5.sp, color = Color(0xFF94A3B8))
        }
    }
}

/**
 * Horizontální lišta „Kruh Důvěry · P2P Mesh“ s fotografickým pozadím pod kartami přátel.
 */
@Composable
fun OuraCirclesMeshSection(
    sl: ServiceLocator,
    onOpenGroups: () -> Unit,
    onInvite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contactsList: List<ContactRow> by sl.db.contacts().all().collectAsState(initial = emptyList())

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF080F18),
        border = BorderStroke(1.dp, Color(0xFF1D2E42)),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.img_nordic_wellness_hero),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .alpha(0.32f)
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = Color(0xFF2DD4BF),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "KRUH DŮVĚRY · RETICULUM P2P",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFCBD5E1),
                            letterSpacing = 1.1.sp
                        )
                    }
                    Text(
                        "Kruhy & QR ›",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onOpenGroups() }
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (contactsList.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xCC111B29),
                            border = BorderStroke(1.dp, Color(0xFF1F3044)),
                            modifier = Modifier.clickable { onOpenGroups() }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.People, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Spárovat prvního člena přes QR", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            }
                        }
                    } else {
                        for ((idx, contact) in contactsList.withIndex()) {
                            val haloColors = listOf(
                                Color(0xFF2DD4BF) to Color(0xFF38BDF8),
                                Color(0xFF818CF8) to Color(0xFFA855F7),
                                Color(0xFFF59E0B) to Color(0xFFFB7185)
                            )
                            val (c1, c2) = haloColors[idx % haloColors.size]
                            val lastReceivedMs by io(contact.identity) { sl.db.inbox().lastReceived(contact.identity) }
                            val deviceTag = lastReceivedMs?.let { ts ->
                                val mins = ((System.currentTimeMillis() - ts) / 60_000L).coerceAtLeast(0L)
                                when {
                                    mins < 60L -> "Sdíleno před $mins min"
                                    mins < 1440L -> "Sdíleno před ${mins / 60L} h"
                                    else -> "Sdíleno před ${mins / 1440L} d"
                                }
                            } ?: "Zatím nic nesdílí"
                            val bgArt = if (idx % 2 == 0) R.drawable.img_alpine_trail_hero else R.drawable.img_motif_body_water

                            CompactMemberAuraPill(
                                name = contact.displayName.ifBlank { "Člen" },
                                scoreText = if (lastReceivedMs != null) "OK" else "—",
                                deviceTag = deviceTag,
                                artworkRes = bgArt,
                                haloColor1 = c1,
                                haloColor2 = c2,
                                onClick = onOpenGroups
                            )
                        }

                        // Kompaktní tlačítko + QR
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xCC0E1724),
                            border = BorderStroke(1.dp, Color(0xFF263B54)),
                            modifier = Modifier
                                .height(56.dp)
                                .clickable { onInvite() }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.QrCode, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Párovat", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactMemberAuraPill(
    name: String,
    scoreText: String,
    deviceTag: String,
    @DrawableRes artworkRes: Int,
    haloColor1: Color,
    haloColor2: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, haloColor1.copy(alpha = 0.45f)), RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Image(
            painter = painterResource(id = artworkRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .alpha(0.55f)
        )
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xD908101A), Color(0x990A1422))
                    )
                )
        )
        Row(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color(0x88050911)
                    )
                    drawCircle(
                        brush = Brush.sweepGradient(listOf(haloColor1, haloColor2, haloColor1)),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                Text(
                    scoreText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(9.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1
                )
                Text(
                    deviceTag,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE2E8F0),
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Atmosférická karta poslední sportovní aktivity (Garmin FIT) přímo na obrazovce Dnes.
 */
@Composable
fun OuraFeaturedActivityBanner(
    activity: ActivitySummaryRow,
    fmt: Fmt,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sportEnum = runCatching { Sport.valueOf(activity.sport) }.getOrDefault(Sport.RUNNING)
    val title = activity.title?.takeIf { it.isNotBlank() } ?: sportEnum.czechName()
    val atmo = sportEnum.atmosphere(title)

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B131E)),
        border = BorderStroke(1.dp, atmo.primaryColor.copy(alpha = 0.55f)),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Box(Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = atmo.artworkRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .alpha(0.75f)
            )
            Canvas(Modifier.matchParentSize()) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color(0xE6050911),
                            Color(0xB3070D17),
                            Color(0x4D070D17)
                        )
                    )
                )
                drawSportTelemetryOverlay(
                    sport = sportEnum,
                    primary = atmo.primaryColor,
                    secondary = atmo.secondaryColor
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 15.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(atmo.primaryColor)
                        )
                        Text(
                            "POSLEDNÍ AKTIVITA · ${atmo.disciplineTag}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = atmo.secondaryColor,
                            fontSize = 9.5.sp,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    val distStr = activity.distanceM?.let { fmt.km(it) } ?: ""
                    val durStr = activity.movingTimeS?.let { fmt.duration(it) } ?: ""
                    val hrStr = activity.avgHeartRate?.let { "$it bpm" } ?: ""
                    val summaryLine = listOf(distStr, durStr, hrStr).filter { it.isNotBlank() }.joinToString(" · ")
                    Text(
                        summaryLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFF1F5F9)
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = "Detail aktivity",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun ProviderBadgeMini(label: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .border(BorderStroke(0.8.dp, color.copy(alpha = 0.55f)), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            fontSize = 9.sp
        )
    }
}
