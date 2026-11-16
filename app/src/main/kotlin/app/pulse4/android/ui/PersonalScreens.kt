@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Pool
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import app.pulse4.android.R
import app.pulse4.android.ui.theme.MetricMotifKind
import app.pulse4.android.ui.theme.MetricAtmosphereCatalog
import app.pulse4.android.ui.theme.drawMetricMotifIllustration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import app.pulse4.android.data.MapProvider
import app.pulse4.android.data.toRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse4.android.data.toDomain
import app.pulse4.android.di.FitImportResult
import app.pulse4.android.di.ServiceLocator
import app.pulse4.android.di.WithingsService
import app.pulse4.domain.Activity
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.Reading
import app.pulse4.domain.Sport
import app.pulse4.domain.TrackPoint
import app.pulse4.provider.SyncArea
import app.pulse4.sharing.ActivityProjection
import app.pulse4.sync.Wire
import app.pulse4.ui.ActivityView
import app.pulse4.ui.Axis
import app.pulse4.ui.Channel
import app.pulse4.ui.MetricSeries
import app.pulse4.ui.TodayCards
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

data class EcgParsed(
    val signalId: Long?,
    val afibClassification: Int?,
    val heartRate: Int?,
    val qtcMs: Double?,
)

fun parseEcgOrigin(origin: String?, value: Double?): EcgParsed {
    if (origin == null || !origin.startsWith("withings:ecg:")) {
        return EcgParsed(null, null, null, value)
    }
    val parts = origin.split(":")
    var sig: Long? = null
    var afib: Int? = null
    var hr: Int? = null
    for (p in parts) {
        if (p.startsWith("sig=")) sig = p.removePrefix("sig=").toLongOrNull()
        if (p.startsWith("afib=")) afib = p.removePrefix("afib=").toIntOrNull()
        if (p.startsWith("hr=")) hr = p.removePrefix("hr=").toIntOrNull()
    }
    return EcgParsed(sig, afib, hr, value)
}

data class MetricUiConfig(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val shortLabel: String,
    val unit: String,
    val color: Color,
    val explanation: String
)

fun getMetricUiConfig(type: MetricType): MetricUiConfig = when (type) {
    MetricType.HRV -> MetricUiConfig(
        icon = PulseIcons.Hrv,
        title = "Variabilita tepu (HRV)",
        shortLabel = "HRV",
        unit = "ms",
        color = Color(0xFF2DD4BF),
        explanation = "Noční HRV (rMSSD) odráží rovnováhu autonomního nervového systému. Vyšší hodnoty v rámci vašeho pásma značí vysokou připravenost na zátěž a efektivní regeneraci."
    )
    MetricType.RESTING_HEART_RATE, MetricType.HEART_RATE -> MetricUiConfig(
        icon = PulseIcons.HeartRate,
        title = if (type == MetricType.HEART_RATE) "Srdeční frekvence" else "Klidový tep (RHR)",
        shortLabel = if (type == MetricType.HEART_RATE) "Tep" else "Klidový tep",
        unit = "bpm",
        color = Color(0xFFFB7185),
        explanation = "Klidová srdeční frekvence měřená ve spánku. Trvalé snížení značí růst aerobní kondice; vzestup o více než 5 bpm může signalizovat začínající nemoc, únavu či alkohol."
    )
    MetricType.SLEEP_DURATION, MetricType.SLEEP_SCORE -> MetricUiConfig(
        icon = PulseIcons.Sleep,
        title = if (type == MetricType.SLEEP_SCORE) "Skóre spánku" else "Doba spánku",
        shortLabel = if (type == MetricType.SLEEP_SCORE) "Skóre spánku" else "Spánek",
        unit = if (type == MetricType.SLEEP_SCORE) "/ 100" else "hod",
        color = Color(0xFF818CF8),
        explanation = "Celková doba nočního spánku. Dlouhodobý spánek 7–9 hodin je klíčový pro buněčnou a svalovou obnovu, syntézu růstového hormonu a konsolidaci paměti."
    )
    MetricType.BODY_BATTERY -> MetricUiConfig(
        icon = PulseIcons.BodyBattery,
        title = "Body Battery",
        shortLabel = "B.Battery",
        unit = "/ 100",
        color = Color(0xFFF59E0B),
        explanation = "Komplexní energetické skóre Garminu (0–100) kalkulované z HRV, spánku, stresu a denních aktivit. Ranní hodnota 75+ značí plně dobité baterie."
    )
    MetricType.STRESS -> MetricUiConfig(
        icon = PulseIcons.Stress,
        title = "Hladina stresu",
        shortLabel = "Stres",
        unit = "/ 100",
        color = Color(0xFFA855F7),
        explanation = "Fyziologický stres měřený z odchylek intervalů tepu (0–25 odpočinek, 26–50 mírný, 51–75 střední, 76–100 vysoký stres). Zobrazuje aktivaci sympatiku."
    )
    MetricType.STEPS -> MetricUiConfig(
        icon = PulseIcons.Steps,
        title = "Denní kroky",
        shortLabel = "Kroky",
        unit = "kroků",
        color = Color(0xFF38BDF8),
        explanation = "Počet kroků za den jako ukazatel spontánní neúmyslné fyzické aktivity (NEAT). Pravidelný pohyb podporuje kardiovaskulární zdraví a inzulinovou senzitivitu."
    )
    MetricType.WEIGHT -> MetricUiConfig(
        icon = PulseIcons.Weight,
        title = "Tělesná hmotnost",
        shortLabel = "Váha",
        unit = fmt.weightUnit(),
        color = Color(0xFF2DD4BF),
        explanation = "Dlouhodobý trend tělesné hmotnosti sloučený ze všech připojených vah (Withings, Health Connect, Garmin), očištěný od krátkodobých výkyvů vody."
    )
    MetricType.BODY_FAT -> MetricUiConfig(
        icon = PulseIcons.BodyFat,
        title = "Tělesný tuk",
        shortLabel = "Tuk",
        unit = "%",
        color = Color(0xFFF59E0B),
        explanation = "Procentuální podíl tělesné tukové tkáně. Pro sledování sportovní formy a složení těla je spolehlivějším ukazatelem než samotné BMI."
    )
    MetricType.MUSCLE_MASS -> MetricUiConfig(
        icon = PulseIcons.Muscle,
        title = "Svalová hmota",
        shortLabel = "Svaly",
        unit = fmt.weightUnit(),
        color = Color(0xFF10B981),
        explanation = "Hmotnost kosterního a hladkého svalstva v těle. Zásadní ukazatel silové kondice, regenerace a bazální rychlosti metabolismu."
    )
    MetricType.BODY_WATER -> MetricUiConfig(
        icon = PulseIcons.BodyWater,
        title = "Tělesná voda",
        shortLabel = "Voda",
        unit = "%",
        color = Color(0xFF06B6D4),
        explanation = "Procentuální podíl vody v těle odrážející celkovou hydrataci organismu. Zdravé rozmezí u dospělých je obvykle 50–65 %."
    )
    MetricType.BONE_MASS -> MetricUiConfig(
        icon = PulseIcons.BoneMass,
        title = "Kostní hmota",
        shortLabel = "Kosti",
        unit = fmt.weightUnit(),
        color = Color(0xFF38BDF8),
        explanation = "Minerální hmotnost kosterní soustavy. Pomáhá sledovat stabilitu a denzitu kostní tkáně v průběhu času."
    )
    MetricType.FAT_FREE_MASS -> MetricUiConfig(
        icon = PulseIcons.FatFreeMass,
        title = "Beztuková hmota (FFM)",
        shortLabel = "FFM",
        unit = fmt.weightUnit(),
        color = Color(0xFF34D399),
        explanation = "Celková beztuková tělesná hmota (Lean Body Mass) zahrnující svalstvo, vnitřní orgány, kosti a tělesnou tekutinu."
    )
    MetricType.VISCERAL_FAT -> MetricUiConfig(
        icon = PulseIcons.VisceralFat,
        title = "Viscerální tuk",
        shortLabel = "Viscerální tuk",
        unit = "index",
        color = Color(0xFFF97316),
        explanation = "Tuk obklopující vnitřní orgány v dutině břišní. Hodnoty 1–9 značí zdravou úroveň, 10–14 zvýšenou a 15+ vysoké kardiovaskulární riziko."
    )
    MetricType.VASCULAR_AGE -> MetricUiConfig(
        icon = PulseIcons.VascularAge,
        title = "Cévní věk",
        shortLabel = "Cévní věk",
        unit = "let",
        color = Color(0xFFEC4899),
        explanation = "Orientační biologické stáří tepen vypočtené z rychlosti pulzní vlny v porovnání s vaší věkovou skupinou."
    )
    MetricType.PULSE_WAVE_VELOCITY -> MetricUiConfig(
        icon = PulseIcons.PulseWaveVelocity,
        title = "Rychlost pulzní vlny (PWV)",
        shortLabel = "PWV",
        unit = "m/s",
        color = Color(0xFF8B5CF6),
        explanation = "Rychlost šíření pulzní vlny v cévách (m/s). Nižší hodnoty ukazují na pružné, zdravé cévy."
    )
    MetricType.BMR -> MetricUiConfig(
        icon = PulseIcons.Bmr,
        title = "Bazální metabolismus (BMR)",
        shortLabel = "BMR",
        unit = "kcal",
        color = Color(0xFFF97316),
        explanation = "Denní energetický výdej organismu v úplném klidovém stavu."
    )
    MetricType.METABOLIC_AGE -> MetricUiConfig(
        icon = PulseIcons.MetabolicAge,
        title = "Metabolický věk",
        shortLabel = "Metab. věk",
        unit = "let",
        color = Color(0xFFEAB308),
        explanation = "Srovnání vašeho bazálního metabolismu s průměrnými hodnotami populace stejného věku."
    )
    MetricType.SPO2 -> MetricUiConfig(
        icon = PulseIcons.SpO2,
        title = "Okysličení krve (SpO2)",
        shortLabel = "SpO2",
        unit = "%",
        color = Color(0xFF0EA5E9),
        explanation = "Procento nasycení periferní krve kyslíkem. Běžné noční hodnoty u zdravého člověka se pohybují v rozmezí 95–100 %."
    )
    MetricType.RESPIRATION -> MetricUiConfig(
        icon = PulseIcons.Respiration,
        title = "Dechová frekvence",
        shortLabel = "Dech",
        unit = "d/min",
        color = Color(0xFF22D3EE),
        explanation = "Průměrná dechová frekvence za minutu během spánku a klidového režimu."
    )
    MetricType.ECG -> MetricUiConfig(
        icon = PulseIcons.Ecg,
        title = "Elektrokardiogram (ECG)",
        shortLabel = "ECG",
        unit = "ms",
        color = Color(0xFFF43F5E),
        explanation = "Záznam elektrické aktivity srdce z elektrod váhy Withings Body Scan a hodinek ScanWatch. Sleduje sinusový rytmus, vylučuje fibrilaci síní (AFib) a měří korigovaný interval QTc (norma obvykle 350–450 ms)."
    )
    else -> MetricUiConfig(
        icon = Icons.Outlined.Timeline,
        title = type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
        shortLabel = type.name.lowercase().replace('_', ' '),
        unit = type.unit,
        color = Color(0xFF6366F1),
        explanation = "Biometrická metrika zaznamenávaná vaším nositelným zařízením."
    )
}

object ScreenMemoryCache {
    @Volatile var todayDashboard: TodayDashboardBundle? = null
    @Volatile var activitySummaries: List<app.pulse4.android.data.ActivitySummaryRow>? = null
}

data class TodayDashboardBundle(
    val today: LocalDate,
    val cards: List<app.pulse4.ui.Card>,
    val seriesByType: Map<MetricType, MetricSeries>,
    val todaySleepScore: Double?,
    val latestWeightObs: app.pulse4.domain.MetricObservation?,
    val latestEcgObs: app.pulse4.domain.MetricObservation? = null,
    val garminLastSyncMs: Long?,
    val withingsLastSyncMs: Long?,
)

@Composable
private fun WidgetOrderReorderDialog(
    initialOrder: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val defaultOrder = listOf("sleep", "bb", "hrv", "rhr", "stress", "steps", "weight", "ecg")
    val widgetMeta = mapOf(
        "sleep" to ("Spánek" to "Doba spánku a noční skóre regenerace"),
        "bb" to ("Body Battery" to "Aktuální tělesná energie a průběh nabití"),
        "hrv" to ("HRV Status" to "Noční variabilita tepu rMSSD"),
        "rhr" to ("Klidový tep" to "Klidová tepová frekvence (RHR)"),
        "stress" to ("Stresová zátěž" to "Reakce autonomního nervového systému"),
        "steps" to ("Denní kroky" to "Kroky a celková denní aktivita"),
        "weight" to ("Hmotnost & Tělo" to "Váha a složení tělesných tkání"),
        "ecg" to ("6svodové EKG" to "Srdeční rytmus a záznamy z váhy")
    )
    var currentOrder by remember {
        val complete = initialOrder.filter { it in widgetMeta }.toMutableList()
        defaultOrder.forEach { if (!complete.contains(it)) complete.add(it) }
        mutableStateOf(complete.toList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A101B),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFCBD5E1),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pořadí widgetů na Dnes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Měňte pořadí karet pomocí šipek nahoru a dolů. Změny se okamžitě projeví na obrazovce Dnes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                currentOrder.forEachIndexed { idx, key ->
                    val meta = widgetMeta[key] ?: (key to "")
                    val widgetType = when (key) {
                        "sleep" -> MetricType.SLEEP_DURATION
                        "bb" -> MetricType.BODY_BATTERY
                        "hrv" -> MetricType.HRV
                        "rhr" -> MetricType.RESTING_HEART_RATE
                        "stress" -> MetricType.STRESS
                        "steps" -> MetricType.STEPS
                        "weight" -> MetricType.WEIGHT
                        "ecg" -> MetricType.ECG
                        else -> MetricType.HRV
                    }
                    val wCfg = getMetricUiConfig(widgetType)
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF232A3B)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Surface(
                                shape = CircleShape,
                                color = wCfg.color.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, wCfg.color.copy(alpha = 0.45f)),
                                modifier = Modifier.size(30.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        wCfg.icon,
                                        contentDescription = null,
                                        tint = wCfg.color,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(meta.first, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text(meta.second, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        if (idx > 0) {
                                            val list = currentOrder.toMutableList()
                                            val item = list.removeAt(idx)
                                            list.add(idx - 1, item)
                                            currentOrder = list
                                        }
                                    },
                                    enabled = idx > 0,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.ArrowUpward,
                                        contentDescription = "Posunout nahoru",
                                        tint = if (idx > 0) Color(0xFF38BDF8) else Color(0xFF475569),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (idx < currentOrder.size - 1) {
                                            val list = currentOrder.toMutableList()
                                            val item = list.removeAt(idx)
                                            list.add(idx + 1, item)
                                            currentOrder = list
                                        }
                                    },
                                    enabled = idx < currentOrder.size - 1,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.ArrowDownward,
                                        contentDescription = "Posunout dolů",
                                        tint = if (idx < currentOrder.size - 1) Color(0xFF38BDF8) else Color(0xFF475569),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(currentOrder); onDismiss() }) {
                Text("Uložit pořadí")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { currentOrder = defaultOrder }) {
                    Text("Obnovit výchozí", color = Color(0xFF94A3B8))
                }
                TextButton(onClick = onDismiss) {
                    Text("Zrušit")
                }
            }
        }
    )
}

/**
 * Obrazovka Dnes:
 * Moderní Oura Ring / Garmin Connect / Grafana Singlestat přehled, kde každá karta má na pozadí
 * bohaté atmosférické motivy (noční obloha s měsícem pro spánek, stopy pro kroky atp.)
 * s volitelným historickým oknem křivky (7d / 14d / 30d / 90d).
 * Uživatel má možnost upravit si pořadí widgetů podle vlastních preferencí.
 */
@Composable
fun TodayScreen(
    sl: ServiceLocator,
    onOpenGroups: () -> Unit = {},
    onOpenMetric: (MetricType) -> Unit = {},
    onOpenActivity: (String) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var bgWindowDays by remember { mutableStateOf(14) }
    var showReorderDialog by remember { mutableStateOf(false) }
    var currentWidgetOrder by remember { mutableStateOf(sl.settings.todayWidgetOrder) }
    var selectedPillar by remember { mutableStateOf(OuraPillar.READINESS) }
    var showAllWidgets by remember { mutableStateOf(false) }

    val states by sl.db.areaStates().all().collectAsState(initial = emptyList())
    val units by sl.settings.unitSystemFlow.collectAsState()
    val recentActs by sl.db.activities().recentSummaries(1).collectAsState(initial = emptyList())

    val dashboardData by produceState<TodayDashboardBundle?>(
        initialValue = ScreenMemoryCache.todayDashboard,
        key1 = Triple(states.map { it.area to it.lastSuccessMs }, bgWindowDays, units)
    ) {
        val result = withContext(Dispatchers.IO) {
            val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
            val obsToday = sl.db.observations().forDate(today.toString()).map { it.toDomain() }
            val maxDays = maxOf(bgWindowDays, 30)
            val rangeObs = sl.db.observations().forRange(today.minusDays((maxDays - 1).toLong()).toString(), today.toString()).map { it.toDomain() }
            val weightObs = runCatching { sl.withings.trend(MetricType.WEIGHT, maxOf(bgWindowDays, 90)) }.getOrDefault(emptyList())
            val success = SyncArea.entries.associateWith { sl.db.areaStates().get(it.name)?.lastSuccessMs }

            val garminLastSyncMs = SyncArea.entries.filter { it != SyncArea.WEIGHT }
                .mapNotNull { sl.db.areaStates().get(it.name)?.lastSuccessMs }
                .maxOrNull()

            val withingsConfigured = sl.settings.withingsDirectEnabled || sl.settings.healthConnectEnabled
            val withingsLastSyncMs = if (withingsConfigured) {
                sl.db.areaStates().get("withings.WEIGHT")?.lastSuccessMs
                    ?: sl.db.areaStates().get(SyncArea.WEIGHT.name)?.lastSuccessMs
            } else {
                sl.db.areaStates().get("withings.WEIGHT")?.lastSuccessMs
            }

            val metricsOrder = listOf(
                MetricType.SLEEP_DURATION,
                MetricType.BODY_BATTERY,
                MetricType.HRV,
                MetricType.RESTING_HEART_RATE,
                MetricType.STRESS,
                MetricType.STEPS,
                MetricType.WEIGHT
            )
            val seriesByType = metricsOrder.associateWith { mType ->
                val src = if (mType == MetricType.WEIGHT) weightObs else rangeObs
                MetricSeries.build(mType, src, today, bgWindowDays)
            }
            val todaySleepScore = obsToday.filter { it.type == MetricType.SLEEP_SCORE && !it.deleted }.maxByOrNull { it.startUtcMs }?.value
            val latestWeightObs = weightObs.filter { !it.deleted }.maxByOrNull { it.startUtcMs }
                ?: sl.db.observations().latest(MetricType.WEIGHT.name)?.toDomain()
            val latestEcgObs = runCatching { sl.withings.trend(MetricType.ECG, 30).maxByOrNull { it.startUtcMs } }.getOrNull()
                ?: sl.db.observations().latest(MetricType.ECG.name)?.toDomain()

            TodayDashboardBundle(
                today = today,
                cards = TodayCards.build(obsToday, success, fmt),
                seriesByType = seriesByType,
                todaySleepScore = todaySleepScore,
                latestWeightObs = latestWeightObs,
                latestEcgObs = latestEcgObs,
                garminLastSyncMs = garminLastSyncMs,
                withingsLastSyncMs = withingsLastSyncMs
            )
        }
        ScreenMemoryCache.todayDashboard = result
        value = result
    }

    val loaded = dashboardData
    if (loaded == null) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Načítání dnešního přehledu…", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val today = loaded.today
    val cards = loaded.cards
    val seriesByType = loaded.seriesByType
    val sleepScore = loaded.todaySleepScore

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Vzdušná minimalistická hlavička s kulatými skleněnými akcemi (sjednocená napříč záložkami)
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
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(Color(0xFF2DD4BF), CircleShape)
                    )
                    Text(
                        "SOVEREIGN BIOMETRICS · HORIZONT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF2DD4BF),
                        letterSpacing = 1.4.sp,
                        fontSize = 9.5.sp
                    )
                }
                Text(
                    "Dnešní Horizont",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Kulaté skleněné tlačítko pro nahrání Demo dat (jen vývojářská verze – nikdy nemíchat do reálných dat)
                if (app.pulse4.android.BuildConfig.DEBUG) Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0C1523))
                        .border(BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.35f)), CircleShape)
                        .clickable {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    sl.importService.seedDemoData(System.currentTimeMillis())
                                }
                                syncMessage = "Ukázková data Garmin & Withings nahrána."
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = "Nahrát demo data",
                        tint = Color(0xFF2DD4BF),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Kulaté skleněné tlačítko pro úpravu pořadí karet
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0C1523))
                        .border(BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f)), CircleShape)
                        .clickable { showReorderDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "Pořadí widgetů",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(17.dp)
                    )
                }

                // Kompaktní skleněné pilulkové tlačítko Sync
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF0E1B2C))
                        .border(BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.45f)), RoundedCornerShape(18.dp))
                        .clickable(enabled = !syncing) {
                            syncing = true
                            syncMessage = null
                            app.pulse4.android.work.Scheduler.importNow(ctx)
                            scope.launch {
                                kotlinx.coroutines.delay(2500)
                                syncing = false
                                syncMessage = "Synchronizace dokončena."
                            }
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = "Synchronizovat",
                            tint = Color(0xFF2DD4BF),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (syncing) "…" else "Sync",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                            ),
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        val wState by sl.withings.state.collectAsState()
        val webSession = remember(wState) { sl.withings.loadWebSession() }
        val wNeedsLogin = wState is app.pulse4.android.di.WithingsUiState.Failed ||
            (sl.settings.withingsDirectEnabled && webSession != null && !webSession.isConnected())
        var showWithingsLoginDialogOnToday by remember { mutableStateOf(false) }

        if (showWithingsLoginDialogOnToday) {
            WithingsWebLoginDialog(
                sl = sl,
                mode = WithingsDialogMode.Reconnect,
                onDismiss = { showWithingsLoginDialogOnToday = false },
                onSuccess = { profile ->
                    showWithingsLoginDialogOnToday = false
                    syncMessage = "Withings spojení obnoveno (${profile.displayName})."
                    app.pulse4.android.work.Scheduler.importNow(ctx)
                }
            )
        }

        if (wNeedsLogin) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF450A0A)),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.8f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clickable { showWithingsLoginDialogOnToday = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Withings vyžaduje přihlášení",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "Klepněte pro přihlášení heslem / obnovení relace",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFCA5A5)
                        )
                    }
                }
            }
        }

        if (showReorderDialog) {
            WidgetOrderReorderDialog(
                initialOrder = currentWidgetOrder,
                onSave = { newOrder ->
                    sl.settings.todayWidgetOrder = newOrder
                    currentWidgetOrder = newOrder
                },
                onDismiss = { showReorderDialog = false }
            )
        }

        syncMessage?.let {
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF10B981))
            }
        }

        val ouraPillarStates = remember(seriesByType, sleepScore, loaded.latestWeightObs, loaded.latestEcgObs) {
            OuraScoreCalculator.calculate(
                seriesByType = seriesByType,
                todaySleepScore = sleepScore,
                latestWeightObs = loaded.latestWeightObs,
                latestEcgObs = loaded.latestEcgObs,
                fmt = fmt
            )
        }

        Spacer(Modifier.height(10.dp))

        // 1. Hlavní atmosférická karta (4 pilíře + Living Halo prstenec)
        OuraHorizonHeroCard(
            sl = sl,
            pillarStates = ouraPillarStates,
            selectedPillar = selectedPillar,
            onSelectPillar = { selectedPillar = it },
            onOpenMetric = onOpenMetric
        )

        Spacer(Modifier.height(12.dp))

        // 2. Kompaktní P2P Kruh Důvěry (Reticulum Mesh)
        OuraCirclesMeshSection(
            sl = sl,
            onOpenGroups = onOpenGroups,
            onInvite = onOpenGroups
        )

        // 3. Poslední sportovní aktivita (Garmin FIT), pokud je k dispozici
        val latestAct = recentActs.firstOrNull()
        if (latestAct != null) {
            Spacer(Modifier.height(12.dp))
            OuraFeaturedActivityBanner(
                activity = latestAct,
                fmt = fmt,
                onClick = { onOpenActivity(latestAct.id) }
            )
        }

        Spacer(Modifier.height(16.dp))

        // 4. Chytré filtrování karet podle aktivního pilíře (žádné zahlcení 8 kartami najednou!)
        val displayedKeys = remember(selectedPillar, showAllWidgets, currentWidgetOrder) {
            if (showAllWidgets) {
                currentWidgetOrder
            } else {
                val pillarSet = selectedPillar.widgetKeys.toSet()
                currentWidgetOrder.filter { it in pillarSet }.ifEmpty { selectedPillar.widgetKeys }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    if (showAllWidgets) "VŠECHNY BIOMETRICKÉ KARTY"
                    else "FAKTORY PILÍŘE: ${selectedPillar.title.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = selectedPillar.primaryColor,
                    letterSpacing = 1.1.sp
                )
                Text(
                    "Klepnutím na kartu otevřete detailní analýzu",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B),
                    fontSize = 10.sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Rychlý přepínač časového okna křivek (7d / 14d / 30d / 90d)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF101A28))
                        .clickable {
                            bgWindowDays = when (bgWindowDays) {
                                7 -> 14
                                14 -> 30
                                30 -> 90
                                else -> 7
                            }
                        }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(
                        "Graf ${bgWindowDays}d ↻",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF94A3B8)
                    )
                }

                // Přepínač: Jen aktivní pilíř vs Všech 8 karet
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (showAllWidgets) selectedPillar.primaryColor.copy(alpha = 0.20f) else Color(0xFF101A28))
                        .clickable { showAllWidgets = !showAllWidgets }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        if (showAllWidgets) "Dle pilíře (${selectedPillar.widgetKeys.size})" else "Vše (${currentWidgetOrder.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (showAllWidgets) selectedPillar.primaryColor else Color(0xFF38BDF8)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Vykreslení vyfiltrovaných atmosférických karet
        displayedKeys.forEach { key ->
            when (key) {
                "sleep" -> {
                    val card = cards.firstOrNull { it.key == "sleep" }
                    val s = seriesByType[MetricType.SLEEP_DURATION]
                    val rawVals = s?.values ?: emptyList()
                    val chartVals = rawVals.map { it?.div(3600.0) }
                    val usual = s?.usual
                    val todayVal = rawVals.lastOrNull()
                    val deltaLabel = if (usual != null && todayVal != null) {
                        val diff = todayVal - usual.median
                        val sign = if (diff >= 0) "+" else ""
                        "$sign${(diff / 60).toLong()} min vs medián"
                    } else null
                    val customStatus = if (sleepScore != null) "Skóre ${sleepScore.toInt()}/100" else null
                    val cfg = getMetricUiConfig(MetricType.SLEEP_DURATION)
                    val atmo = MetricAtmosphereCatalog.Sleep

                    SinglestatCard(
                        title = cfg.title,
                        icon = cfg.icon,
                        reading = card?.reading ?: Reading.NotSyncedYet,
                        historyValues = chartVals,
                        accentColor = cfg.color,
                        artworkRes = atmo.artworkRes,
                        motifKind = atmo.motifKind,
                        heroTopColor = atmo.heroTopColor,
                        windowLabel = "${bgWindowDays}d",
                        deltaLabel = deltaLabel,
                        statusLabel = customStatus,
                        onClick = { onOpenMetric(MetricType.SLEEP_DURATION) }
                    )
                }
                "bb" -> {
                    val card = cards.firstOrNull { it.key == "bb" }
                    val s = seriesByType[MetricType.BODY_BATTERY]
                    val rawVals = s?.values ?: emptyList()
                    val usual = s?.usual
                    val todayVal = rawVals.lastOrNull()
                    val deltaLabel = if (usual != null && todayVal != null) {
                        val diff = todayVal - usual.median
                        val sign = if (diff >= 0) "+" else ""
                        "$sign${fmt.number(diff, 0)} ${getMetricUiConfig(MetricType.BODY_BATTERY).unit} vs medián"
                    } else null
                    val cfg = getMetricUiConfig(MetricType.BODY_BATTERY)
                    val atmo = MetricAtmosphereCatalog.BodyBattery

                    SinglestatCard(
                        title = cfg.title,
                        icon = cfg.icon,
                        reading = card?.reading ?: Reading.NotSyncedYet,
                        historyValues = rawVals,
                        accentColor = cfg.color,
                        artworkRes = atmo.artworkRes,
                        motifKind = atmo.motifKind,
                        heroTopColor = atmo.heroTopColor,
                        windowLabel = "${bgWindowDays}d",
                        deltaLabel = deltaLabel,
                        statusLabel = null,
                        onClick = { onOpenMetric(MetricType.BODY_BATTERY) }
                    )
                }
                "hrv" -> {
                    val card = cards.firstOrNull { it.key == "hrv" }
                    val s = seriesByType[MetricType.HRV]
                    val rawVals = s?.values ?: emptyList()
                    val usual = s?.usual
                    val todayVal = rawVals.lastOrNull()
                    val deltaLabel = if (usual != null && todayVal != null) {
                        val diff = todayVal - usual.median
                        val sign = if (diff >= 0) "+" else ""
                        "$sign${fmt.number(diff, 0)} ${getMetricUiConfig(MetricType.HRV).unit} vs medián"
                    } else null
                    val cfg = getMetricUiConfig(MetricType.HRV)
                    val atmo = MetricAtmosphereCatalog.Hrv

                    SinglestatCard(
                        title = cfg.title,
                        icon = cfg.icon,
                        reading = card?.reading ?: Reading.NotSyncedYet,
                        historyValues = rawVals,
                        accentColor = cfg.color,
                        artworkRes = atmo.artworkRes,
                        motifKind = atmo.motifKind,
                        heroTopColor = atmo.heroTopColor,
                        windowLabel = "${bgWindowDays}d",
                        deltaLabel = deltaLabel,
                        statusLabel = null,
                        onClick = { onOpenMetric(MetricType.HRV) }
                    )
                }
                "rhr" -> {
                    val card = cards.firstOrNull { it.key == "rhr" }
                    val s = seriesByType[MetricType.RESTING_HEART_RATE]
                    val rawVals = s?.values ?: emptyList()
                    val usual = s?.usual
                    val todayVal = rawVals.lastOrNull()
                    val deltaLabel = if (usual != null && todayVal != null) {
                        val diff = todayVal - usual.median
                        val sign = if (diff >= 0) "+" else ""
                        "$sign${fmt.number(diff, 0)} ${getMetricUiConfig(MetricType.RESTING_HEART_RATE).unit} vs medián"
                    } else null
                    val cfg = getMetricUiConfig(MetricType.RESTING_HEART_RATE)
                    val atmo = MetricAtmosphereCatalog.RestingHr

                    SinglestatCard(
                        title = cfg.title,
                        icon = cfg.icon,
                        reading = card?.reading ?: Reading.NotSyncedYet,
                        historyValues = rawVals,
                        accentColor = cfg.color,
                        artworkRes = atmo.artworkRes,
                        motifKind = atmo.motifKind,
                        heroTopColor = atmo.heroTopColor,
                        windowLabel = "${bgWindowDays}d",
                        deltaLabel = deltaLabel,
                        statusLabel = null,
                        onClick = { onOpenMetric(MetricType.RESTING_HEART_RATE) }
                    )
                }
                "stress" -> {
                    val card = cards.firstOrNull { it.key == "stress" }
                    val s = seriesByType[MetricType.STRESS]
                    val rawVals = s?.values ?: emptyList()
                    val usual = s?.usual
                    val todayVal = rawVals.lastOrNull()
                    val deltaLabel = if (usual != null && todayVal != null) {
                        val diff = todayVal - usual.median
                        val sign = if (diff >= 0) "+" else ""
                        "$sign${fmt.number(diff, 0)} ${getMetricUiConfig(MetricType.STRESS).unit} vs medián"
                    } else null
                    val cfg = getMetricUiConfig(MetricType.STRESS)
                    val atmo = MetricAtmosphereCatalog.Stress

                    SinglestatCard(
                        title = cfg.title,
                        icon = cfg.icon,
                        reading = card?.reading ?: Reading.NotSyncedYet,
                        historyValues = rawVals,
                        accentColor = cfg.color,
                        artworkRes = atmo.artworkRes,
                        motifKind = atmo.motifKind,
                        heroTopColor = atmo.heroTopColor,
                        windowLabel = "${bgWindowDays}d",
                        deltaLabel = deltaLabel,
                        statusLabel = null,
                        onClick = { onOpenMetric(MetricType.STRESS) }
                    )
                }
                "steps" -> {
                    val card = cards.firstOrNull { it.key == "steps" }
                    val s = seriesByType[MetricType.STEPS]
                    val rawVals = s?.values ?: emptyList()
                    val usual = s?.usual
                    val todayVal = rawVals.lastOrNull()
                    val deltaLabel = if (usual != null && todayVal != null) {
                        val diff = todayVal - usual.median
                        val sign = if (diff >= 0) "+" else ""
                        "$sign${fmt.number(diff, 0)} ${getMetricUiConfig(MetricType.STEPS).unit} vs medián"
                    } else null
                    val cfg = getMetricUiConfig(MetricType.STEPS)
                    val atmo = MetricAtmosphereCatalog.Steps

                    SinglestatCard(
                        title = cfg.title,
                        icon = cfg.icon,
                        reading = card?.reading ?: Reading.NotSyncedYet,
                        historyValues = rawVals,
                        accentColor = cfg.color,
                        artworkRes = atmo.artworkRes,
                        motifKind = atmo.motifKind,
                        heroTopColor = atmo.heroTopColor,
                        windowLabel = "${bgWindowDays}d",
                        deltaLabel = deltaLabel,
                        statusLabel = null,
                        onClick = { onOpenMetric(MetricType.STEPS) }
                    )
                }
                "weight" -> {
                    val weightSeries = seriesByType[MetricType.WEIGHT]
                    val wCfg = getMetricUiConfig(MetricType.WEIGHT)
                    val latestW = loaded.latestWeightObs
                    val isWeightToday = latestW?.localDate == today.toString()
                    val wReading: Reading<String> = when {
                        latestW != null -> Reading.Value(
                            fmt.weight(latestW.value),
                            latestW.startUtcMs,
                            loaded.withingsLastSyncMs ?: latestW.startUtcMs
                        )
                        loaded.withingsLastSyncMs != null -> Reading.DeviceDoesNotMeasure
                        else -> Reading.NotSyncedYet
                    }
                    val weightValueColor = if (latestW != null && !isWeightToday) Color(0xFFF59E0B) else Color.White
                    val weightStatusBadge = when {
                        latestW == null -> null
                        isWeightToday -> "Dnes"
                        else -> "Starší (${latestW.localDate?.takeLast(5) ?: fmt.time(latestW.startUtcMs)})"
                    }
                    val weightUsual = weightSeries?.usual
                    val weightDelta = if (weightUsual != null && latestW != null) {
                        val diff = fmt.convertWeight(latestW.value - weightUsual.median)
                        val sign = if (diff >= 0) "+" else ""
                        "$sign${fmt.number(diff, 1)} ${fmt.weightUnit()} vs medián"
                    } else null
                    val atmo = MetricAtmosphereCatalog.Weight

                    SinglestatCard(
                        title = wCfg.title,
                        icon = wCfg.icon,
                        reading = wReading,
                        historyValues = weightSeries?.values?.map { it?.let(fmt::convertWeight) }.orEmpty(),
                        accentColor = wCfg.color,
                        artworkRes = atmo.artworkRes,
                        motifKind = atmo.motifKind,
                        heroTopColor = atmo.heroTopColor,
                        windowLabel = "${bgWindowDays}d",
                        deltaLabel = weightDelta,
                        statusLabel = weightStatusBadge,
                        valueColor = weightValueColor,
                        onClick = { onOpenMetric(MetricType.WEIGHT) }
                    )
                }
                "ecg" -> {
                    val latestEcg = loaded.latestEcgObs
                    if (latestEcg != null) {
                        val ecgCfg = getMetricUiConfig(MetricType.ECG)
                        val isEcgToday = latestEcg.localDate == today.toString()
                        val ecgDet = parseEcgOrigin(latestEcg.origin, latestEcg.value)
                        val ecgStatusBadge = when (ecgDet.afibClassification) {
                            0 -> "Sinusový rytmus"
                            1 -> "AFib detekována"
                            2 -> "Neprůkazný záznam"
                            else -> if (isEcgToday) "Dnes" else "Starší (${latestEcg.localDate?.takeLast(5) ?: fmt.time(latestEcg.startUtcMs)})"
                        }
                        val ecgValFormatted = if (latestEcg.value >= 200.0) {
                            "${fmt.number(latestEcg.value, 0)} ms QTc" + (ecgDet.heartRate?.let { " · $it bpm" } ?: "")
                        } else {
                            "${fmt.number(latestEcg.value, 0)} bpm"
                        }
                        val ecgReading: Reading<String> = Reading.Value(
                            ecgValFormatted,
                            latestEcg.startUtcMs,
                            loaded.withingsLastSyncMs ?: latestEcg.startUtcMs
                        )
                        val atmo = MetricAtmosphereCatalog.Ecg

                        SinglestatCard(
                            title = ecgCfg.title,
                            icon = ecgCfg.icon,
                            reading = ecgReading,
                            historyValues = emptyList(),
                            accentColor = ecgCfg.color,
                            artworkRes = atmo.artworkRes,
                            motifKind = atmo.motifKind,
                            heroTopColor = atmo.heroTopColor,
                            windowLabel = "${bgWindowDays}d",
                            deltaLabel = null,
                            statusLabel = ecgStatusBadge,
                            valueColor = if (ecgDet.afibClassification == 1) Color(0xFFEF4444) else Color.White,
                            onClick = { onOpenMetric(MetricType.ECG) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Pulse4 v${app.pulse4.android.BuildConfig.VERSION_NAME} (${app.pulse4.android.BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64748B).copy(alpha = 0.6f),
                fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun MetricInfoIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF38BDF8)
) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(0xFF1E293B))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "Informace o metrice",
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
fun MetricInfoPopupDialog(
    type: MetricType,
    cfg: MetricUiConfig,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Rozumím", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(cfg.color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(cfg.icon, contentDescription = null, tint = cfg.color, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(cfg.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    cfg.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFCBD5E1),
                    lineHeight = 20.sp
                )
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Assessment, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Jednotka & interpretace", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when (type) {
                                MetricType.ECG -> "Normální nález: Pravidelný sinusový rytmus bez arytmie. Hraniční QTc: >450 ms (muži) / >460 ms (ženy)."
                                MetricType.VISCERAL_FAT -> "Withings Body Scan desetinná bioimpedance: 0,0–2,9 (normální zdravá úroveň), 3,0–6,9 (mírně zvýšená), 7,0+ (vysoké kardiovaskulární riziko)."
                                MetricType.HRV -> "rMSSD v milisekundách. Hodnoceno vůči vašemu osobnímu 7dennímu a 28dennímu pásmu (vyvážený / nevyvážený stav)."
                                MetricType.RESTING_HEART_RATE -> "Klidový tep v bpm zaznamenaný během nejhlubší fáze spánku. Stabilní či klesající trend značí dobrou kardio kondici."
                                MetricType.SLEEP_DURATION -> "Doporučené rozmezí pro dospělého sportovce je 7 až 9 hodin kvalitního spánku za noc."
                                MetricType.BODY_BATTERY -> "Skóre 0–100. Ranní hodnota 70–100 = plná regenerace, 25–50 = lehká únava, <25 = vyčerpání organismu."
                                MetricType.STRESS -> "Skóre 0–100 z variability tepu. 0–25 klid/regenerace, 26–50 nízký stres, 51–75 střední, 76–100 vysoký stres."
                                MetricType.BODY_FAT -> "Podíl tuku v těle (%). U sportovců muži obvykle 10–18 %, ženy 18–25 %."
                                MetricType.MUSCLE_MASS -> "Kosterní a hladká svalová hmota. Zásadní je udržovat či zvyšovat poměr svalů vůči celkové hmotnosti."
                                MetricType.BODY_WATER -> "Celková tělesná voda. Zdravé referenční rozmezí je přibližně 50–65 % tělesné hmotnosti."
                                MetricType.BONE_MASS -> "Minerální kostní hmota. Obvykle 2,5–4,0 kg u dospělého člověka."
                                MetricType.VASCULAR_AGE -> "Cévní věk odvozený z rychlosti pulzní vlny (PWV). Ideální stav je cévní věk nižší nebo rovný chronologickému věku."
                                MetricType.PULSE_WAVE_VELOCITY -> "Rychlost šíření pulzní vlny v m/s. Hodnoty pod 7–8 m/s značí vysokou elasticitu a zdraví tepen."
                                MetricType.BMR -> "Klidový energetický výdej v kcal bez započtení fyzické aktivity. Závisí přímo na množství svalové tkáně."
                                MetricType.SPO2 -> "Nasycení krve kyslíkem v %. Normální hodnoty jsou 95–100 %."
                                else -> "Jednotka: ${cfg.unit.ifBlank { "bezrozměrná" }}. Sledováno v kontextu dlouhodobých trendů a osobního optima."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }
    )
}

@Composable
fun EcgLeadStripCanvas(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFF43F5E),
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0 || height <= 0) return@Canvas

        val gridStep = 16f
        var xGrid = 0f
        while (xGrid < width) {
            drawLine(
                color = Color(0xFF1E293B).copy(alpha = 0.5f),
                start = Offset(xGrid, 0f),
                end = Offset(xGrid, height),
                strokeWidth = 1f
            )
            xGrid += gridStep
        }
        var yGrid = 0f
        while (yGrid < height) {
            drawLine(
                color = Color(0xFF1E293B).copy(alpha = 0.5f),
                start = Offset(0f, yGrid),
                end = Offset(width, yGrid),
                strokeWidth = 1f
            )
            yGrid += gridStep
        }

        val midY = height * 0.60f
        drawLine(
            color = Color(0xFF334155),
            start = Offset(0f, midY),
            end = Offset(width, midY),
            strokeWidth = 1f
        )

        val cycleWidth = 110f
        val numCycles = (width / cycleWidth).toInt() + 2
        val path = Path()

        var started = false
        for (i in 0 until numCycles) {
            val base = i * cycleWidth
            if (!started) {
                path.moveTo(base, midY)
                started = true
            } else {
                path.lineTo(base, midY)
            }
            path.lineTo(base + cycleWidth * 0.12f, midY)
            path.cubicTo(
                base + cycleWidth * 0.16f, midY - height * 0.14f,
                base + cycleWidth * 0.22f, midY - height * 0.14f,
                base + cycleWidth * 0.26f, midY
            )
            path.lineTo(base + cycleWidth * 0.35f, midY)
            path.lineTo(base + cycleWidth * 0.38f, midY + height * 0.08f)
            path.lineTo(base + cycleWidth * 0.42f, midY - height * 0.55f)
            path.lineTo(base + cycleWidth * 0.46f, midY + height * 0.18f)
            path.lineTo(base + cycleWidth * 0.50f, midY)
            path.lineTo(base + cycleWidth * 0.62f, midY)
            path.cubicTo(
                base + cycleWidth * 0.68f, midY - height * 0.22f,
                base + cycleWidth * 0.78f, midY - height * 0.22f,
                base + cycleWidth * 0.84f, midY
            )
            path.lineTo(base + cycleWidth, midY)
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 2.5f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

private fun formatMetricValue(type: MetricType, v: Double): String {
    val cfg = getMetricUiConfig(type)
    return when (type) {
        MetricType.SLEEP_DURATION -> fmt.sleep(v)
        MetricType.HRV -> "${fmt.number(v, 0)} ms"
        MetricType.RESTING_HEART_RATE -> "${fmt.number(v, 0)} bpm"
        MetricType.BODY_BATTERY -> fmt.number(v, 0)
        MetricType.STRESS -> fmt.number(v, 0)
        MetricType.STEPS -> "${fmt.number(v, 0)} kroků"
        MetricType.WEIGHT, MetricType.MUSCLE_MASS, MetricType.FAT_FREE_MASS -> fmt.weight(v, 1)
        MetricType.BONE_MASS -> fmt.weight(v, 2)
        MetricType.BODY_FAT, MetricType.BODY_WATER -> "${fmt.number(v, 1)} %"
        MetricType.SPO2 -> "${fmt.number(v, 0)} %"
        MetricType.VISCERAL_FAT -> fmt.number(v, 1)
        MetricType.VASCULAR_AGE, MetricType.METABOLIC_AGE -> "${fmt.number(v, 0)} let"
        MetricType.PULSE_WAVE_VELOCITY -> "${fmt.number(v, 1)} m/s"
        MetricType.BMR -> "${fmt.number(v, 0)} kcal"
        MetricType.RESPIRATION -> "${fmt.number(v, 1)} brpm"
        MetricType.ECG -> if (v >= 200.0) "${fmt.number(v, 0)} ms (QTc)" else "${fmt.number(v, 0)} bpm"
        else -> "${fmt.number(v, 1)} ${cfg.unit}"
    }
}

enum class BodyUnitMode { KG, PERCENT }

fun isConvertibleBodyCompMetric(type: MetricType): Boolean =
    type in setOf(
        MetricType.MUSCLE_MASS,
        MetricType.FAT_FREE_MASS,
        MetricType.BONE_MASS,
        MetricType.BODY_FAT,
        MetricType.BODY_WATER
    )

fun convertBodyCompValue(
    type: MetricType,
    rawVal: Double,
    refWeightKg: Double?,
    mode: BodyUnitMode,
): Double {
    val w = (refWeightKg ?: 90.0).takeIf { it in 25.0..250.0 } ?: 90.0
    return when (mode) {
        BodyUnitMode.KG -> when (type) {
            MetricType.WEIGHT, MetricType.MUSCLE_MASS, MetricType.FAT_FREE_MASS, MetricType.BONE_MASS -> fmt.convertWeight(rawVal)
            MetricType.BODY_FAT, MetricType.BODY_WATER -> fmt.convertWeight(w * (rawVal / 100.0))
            else -> rawVal
        }
        BodyUnitMode.PERCENT -> when (type) {
            MetricType.MUSCLE_MASS, MetricType.FAT_FREE_MASS, MetricType.BONE_MASS -> (rawVal / w) * 100.0
            MetricType.BODY_FAT, MetricType.BODY_WATER -> rawVal
            MetricType.WEIGHT -> fmt.convertWeight(rawVal)
            else -> rawVal
        }
    }
}

fun bodyCompUnitLabel(type: MetricType, mode: BodyUnitMode): String {
    if (type == MetricType.WEIGHT) return fmt.weightUnit()
    if (isConvertibleBodyCompMetric(type)) {
        return if (mode == BodyUnitMode.KG) fmt.weightUnit() else "%"
    }
    return getMetricUiConfig(type).unit
}

fun formatBodyCompValue(
    type: MetricType,
    rawVal: Double,
    refWeightKg: Double?,
    mode: BodyUnitMode,
    includeSecondary: Boolean = false,
): String {
    if (type == MetricType.WEIGHT) return fmt.weight(rawVal)
    if (!isConvertibleBodyCompMetric(type)) return formatMetricValue(type, rawVal)
    val w = (refWeightKg ?: 90.0).takeIf { it in 25.0..250.0 } ?: 90.0
    val kgVal = if (type in setOf(MetricType.BODY_FAT, MetricType.BODY_WATER)) w * (rawVal / 100.0) else rawVal
    val pctVal = if (type in setOf(MetricType.BODY_FAT, MetricType.BODY_WATER)) rawVal else (rawVal / w) * 100.0
    val kgStr = fmt.weight(kgVal)
    val pctStr = "${fmt.number(pctVal, 1)} %"
    return if (includeSecondary) {
        if (mode == BodyUnitMode.KG) "$kgStr ($pctStr)" else "$pctStr ($kgStr)"
    } else {
        if (mode == BodyUnitMode.KG) kgStr else pctStr
    }
}

data class SegmentalBodySnapshot(
    val dateLabel: String,
    val timestampMs: Long,
    val muscleByPos: Map<Int, Double>,
    val fatByPos: Map<Int, Double>,
    val ffmByPos: Map<Int, Double>,
)

private val BODY_SEGMENT_DEFS = listOf(
    Triple(2, "Pravá ruka", Color(0xFF38BDF8)),
    Triple(3, "Levá ruka", Color(0xFF06B6D4)),
    Triple(12, "Trup (hrudník a břicho)", Color(0xFF10B981)),
    Triple(10, "Pravá noha", Color(0xFFA855F7)),
    Triple(11, "Levá noha", Color(0xFFEC4899)),
)

private val HealthGreen = Color(0xFF10B981)
private val HealthAmber = Color(0xFFF59E0B)
private val HealthRed = Color(0xFFEF4444)

private fun parseSegmentalSnapshots(observations: List<app.pulse4.domain.MetricObservation>): List<SegmentalBodySnapshot> {
    fun parseMap(tag: String, origin: String): Map<Int, Double> {
        val part = origin.split('|').firstOrNull { it.startsWith("$tag:") }?.removePrefix("$tag:") ?: return emptyMap()
        return part.split(',').mapNotNull { kv ->
            val sp = kv.split('=')
            if (sp.size == 2) {
                val k = sp[0].toIntOrNull()
                val v = sp[1].toDoubleOrNull()
                if (k != null && v != null) k to v else null
            } else null
        }.toMap()
    }

    return observations
        .filter { !it.deleted && it.origin?.contains("|seg") == true }
        .sortedBy { it.startUtcMs }
        .mapNotNull { obs ->
            val orig = obs.origin ?: return@mapNotNull null
            val m = parseMap("segm", orig)
            val f = parseMap("segf", orig)
            val ffm = parseMap("segffm", orig)
            if (m.isEmpty() && f.isEmpty()) null
            else SegmentalBodySnapshot(
                dateLabel = obs.localDate ?: fmt.time(obs.startUtcMs),
                timestampMs = obs.startUtcMs,
                muscleByPos = m,
                fatByPos = f,
                ffmByPos = ffm
            )
        }
        .distinctBy { it.dateLabel }
}

@Composable
fun BodyCompositionStackedBar(
    weightKg: Double?,
    muscleKg: Double?,
    fatPct: Double?,
    boneKg: Double?,
    unitMode: BodyUnitMode,
    onUnitModeChange: (BodyUnitMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val w = (weightKg ?: 93.10).takeIf { it in 25.0..250.0 } ?: 93.10
    val mKg = muscleKg ?: (w * 0.75)
    val fPct = fatPct ?: 20.9
    val fKg = w * (fPct / 100.0)
    val bKg = boneKg ?: (w * 0.038)
    val sumKg = (mKg + fKg + bKg).coerceAtLeast(1.0)
    val mShare = (mKg / sumKg).toFloat().coerceIn(0.10f, 0.85f)
    val fShare = (fKg / sumKg).toFloat().coerceIn(0.08f, 0.60f)
    val bShare = (1f - mShare - fShare).coerceAtLeast(0.04f)

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                listOf(
                    Color(0xFF2DD4BF).copy(alpha = 0.55f),
                    Color(0xFFF59E0B).copy(alpha = 0.35f),
                    Color(0xFF38BDF8).copy(alpha = 0.45f)
                )
            )
        ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF070D17))
    ) {
        Box(Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.img_motif_body_silhouette),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .alpha(0.74f)
            )
            Canvas(Modifier.matchParentSize()) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color(0xD9050912),
                            Color(0xAA070E1A),
                            Color(0x55070E1A)
                        )
                    )
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "BIOIMPEDANČNÍ SLOŽENÍ TĚLA · WITHINGS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2DD4BF),
                            letterSpacing = 1.1.sp
                        )
                        Text(
                            if (unitMode == BodyUnitMode.KG) "Všechny složky těla přepočteny na kilogramy (${fmt.weightUnit()})"
                            else "Všechny složky těla přepočteny na procenta celkové váhy (%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }

                // Přepínač jednotek: Kilogramy (kg) vs Procenta (%)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = unitMode == BodyUnitMode.KG,
                        onClick = { onUnitModeChange(BodyUnitMode.KG) },
                        leadingIcon = { Icon(PulseIcons.UnitPhysical, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("V kilogramech (${fmt.weightUnit()})", fontWeight = FontWeight.Bold, maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = unitMode == BodyUnitMode.PERCENT,
                        onClick = { onUnitModeChange(BodyUnitMode.PERCENT) },
                        leadingIcon = { Icon(PulseIcons.UnitPercent, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("V procentech (%)", fontWeight = FontWeight.Bold, maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Skládaný pruh složení těla (Svaly + Tuk + Kosti = Celková váha)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    Box(Modifier.weight(mShare).fillMaxSize().background(Color(0xFF10B981)))
                    Spacer(Modifier.width(2.dp))
                    Box(Modifier.weight(fShare).fillMaxSize().background(Color(0xFFF59E0B)))
                    Spacer(Modifier.width(2.dp))
                    Box(Modifier.weight(bShare).fillMaxSize().background(Color(0xFF38BDF8)))
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val mText = if (unitMode == BodyUnitMode.KG) fmt.weight(mKg) else "${fmt.number((mKg / w) * 100.0, 1)} %"
                    val fText = if (unitMode == BodyUnitMode.KG) fmt.weight(fKg) else "${fmt.number(fPct, 1)} %"
                    val bText = if (unitMode == BodyUnitMode.KG) fmt.weight(bKg) else "${fmt.number((bKg / w) * 100.0, 1)} %"
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(PulseIcons.Muscle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(13.dp))
                            Text("Svaly", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                        }
                        Text(mText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(PulseIcons.BodyFat, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(13.dp))
                            Text("Tuk", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                        }
                        Text(fText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(PulseIcons.BoneMass, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(13.dp))
                            Text("Kosti", style = MaterialTheme.typography.labelSmall, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                        }
                        Text(bText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun SegmentalBodyCompositionSection(
    sl: ServiceLocator,
    initialModeFat: Boolean = false,
    daysCount: Int = 365,
    externalUnitMode: BodyUnitMode? = null,
    onUnitModeChange: ((BodyUnitMode) -> Unit)? = null,
) {
    var showFatMode by remember(initialModeFat) { mutableStateOf(initialModeFat) }
    var internalUnitMode by remember { mutableStateOf(BodyUnitMode.KG) }
    val activeUnitMode = externalUnitMode ?: internalUnitMode
    val setUnitMode: (BodyUnitMode) -> Unit = { m ->
        internalUnitMode = m
        onUnitModeChange?.invoke(m)
    }
    var selectedSegmentPos by remember { mutableStateOf<Int?>(null) }

    val snapshots by io(daysCount) {
        val rows = runCatching { sl.withings.trend(MetricType.MUSCLE_MASS, maxOf(daysCount, 730)) }.getOrDefault(emptyList()) +
            runCatching { sl.withings.trend(MetricType.BODY_FAT, maxOf(daysCount, 730)) }.getOrDefault(emptyList()) +
            runCatching { sl.withings.trend(MetricType.WEIGHT, maxOf(daysCount, 730)) }.getOrDefault(emptyList())
        parseSegmentalSnapshots(rows)
    }

    val list = snapshots.orEmpty()
    if (list.isEmpty()) return

    val latest = list.last()
    val prev = if (list.size >= 2) list[list.size - 2] else null

    val currentMap = if (showFatMode) latest.fatByPos else latest.muscleByPos
    val prevMap = if (showFatMode) prev?.fatByPos else prev?.muscleByPos
    val totalVal = currentMap.values.sum().coerceAtLeast(0.01)

    // Symetrie rukou (2 vs 3) a nohou (10 vs 11)
    val rightArm = currentMap[2] ?: 0.0
    val leftArm = currentMap[3] ?: 0.0
    val armDiffPct = if (rightArm + leftArm > 0.1) ((rightArm - leftArm) / ((rightArm + leftArm) / 2.0)) * 100.0 else 0.0

    val rightLeg = currentMap[10] ?: 0.0
    val leftLeg = currentMap[11] ?: 0.0
    val legDiffPct = if (rightLeg + leftLeg > 0.1) ((rightLeg - leftLeg) / ((rightLeg + leftLeg) / 2.0)) * 100.0 else 0.0

    fun segmentPercentValue(snap: SegmentalBodySnapshot, pos: Int, fatMode: Boolean): Double? {
        val map = if (fatMode) snap.fatByPos else snap.muscleByPos
        val kg = map[pos]?.takeIf { it > 0.05 } ?: return null
        val ffm = snap.ffmByPos[pos] ?: snap.muscleByPos[pos] ?: 0.0
        val fat = snap.fatByPos[pos] ?: 0.0
        val segTot = (ffm + fat).coerceAtLeast(0.05)
        return (kg / segTot) * 100.0
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, if (showFatMode) Color(0xFFF59E0B).copy(alpha = 0.55f) else Color(0xFF10B981).copy(alpha = 0.55f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF070C15))
    ) {
        Box(Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(
                    id = if (showFatMode) R.drawable.img_motif_body_fat else R.drawable.img_motif_body_silhouette
                ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .alpha(0.68f)
            )
            Canvas(Modifier.matchParentSize()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color(0xCC060B14),
                            Color(0xB3060B14),
                            Color(0xE6050911)
                        )
                    )
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (showFatMode) "SEGMENTÁLNÍ ROZLOŽENÍ TUKU (RUCE, TRUP, NOHY)"
                            else "SEGMENTÁLNÍ ROZLOŽENÍ SVALŮ (RUCE, TRUP, NOHY)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (showFatMode) Color(0xFFF59E0B) else Color(0xFF10B981),
                            letterSpacing = 1.0.sp
                        )
                        Text(
                            "Withings Body Scan (${latest.dateLabel}) · " +
                                if (activeUnitMode == BodyUnitMode.KG) "zobrazeno v kilogramech (${fmt.weightUnit()})"
                                else "zobrazeno v procentech dané části těla (%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }

            // Přepínač Svaly vs Tuk
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !showFatMode,
                    onClick = { showFatMode = false },
                    leadingIcon = { Icon(PulseIcons.Muscle, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Svalová hmota", maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = showFatMode,
                    onClick = { showFatMode = true },
                    leadingIcon = { Icon(PulseIcons.BodyFat, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Tuková tkáň", maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Přepínač jednotky: Kilogramy (kg) vs Procenta (%)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = activeUnitMode == BodyUnitMode.KG,
                    onClick = { setUnitMode(BodyUnitMode.KG) },
                    leadingIcon = { Icon(PulseIcons.UnitPhysical, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("V kilogramech (${fmt.weightUnit()})", maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = activeUnitMode == BodyUnitMode.PERCENT,
                    onClick = { setUnitMode(BodyUnitMode.PERCENT) },
                    leadingIcon = { Icon(PulseIcons.UnitPercent, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("V procentech (%)", maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Hlavička tabulky
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Část těla", Modifier.weight(1.35f), style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                Text(
                    if (activeUnitMode == BodyUnitMode.KG) "Hmotnost (${fmt.weightUnit()})" else "Zastoupení (%)",
                    Modifier.weight(1.0f),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
                Text(
                    "Změna / Podíl",
                    Modifier.weight(1.1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
            }

            BODY_SEGMENT_DEFS.forEach { (pos, label, segColor) ->
                val kg = currentMap[pos] ?: 0.0
                val prevKg = prevMap?.get(pos)
                val diffKg = if (prevKg != null) kg - prevKg else null
                val segFfm = latest.ffmByPos[pos] ?: latest.muscleByPos[pos] ?: 0.0
                val segFat = latest.fatByPos[pos] ?: 0.0
                val segTotal = (segFfm + segFat).coerceAtLeast(0.01)
                val inSegmentPct = (kg / segTotal) * 100.0
                val prevInSegmentPct = prev?.let { segmentPercentValue(it, pos, showFatMode) }
                val diffPct = if (prevInSegmentPct != null) inSegmentPct - prevInSegmentPct else null
                val shareOfTotalPct = (kg / totalVal) * 100.0
                val isSelected = selectedSegmentPos == pos

                val rowHealthColor = if (showFatMode) {
                    when {
                        inSegmentPct <= 18.0 -> HealthGreen
                        inSegmentPct <= 26.0 -> HealthAmber
                        else -> HealthRed
                    }
                } else {
                    when {
                        inSegmentPct >= 75.0 -> HealthGreen
                        inSegmentPct >= 65.0 -> HealthAmber
                        else -> HealthRed
                    }
                }

                val diffVal = if (activeUnitMode == BodyUnitMode.KG) diffKg else diffPct
                val diffColor = if (diffVal == null || kotlin.math.abs(diffVal) < 0.01) {
                    Color(0xFF94A3B8)
                } else if (showFatMode) {
                    if (diffVal <= 0.0) HealthGreen else HealthRed
                } else {
                    if (diffVal >= 0.0) HealthGreen else HealthRed
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) segColor.copy(alpha = 0.16f) else Color(0xFF0F172A))
                        .clickable {
                            selectedSegmentPos = if (isSelected) null else pos
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(Modifier.weight(1.35f), verticalAlignment = Alignment.CenterVertically) {
                            Text("● ", color = segColor, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            if (activeUnitMode == BodyUnitMode.KG) fmt.weight(kg)
                            else "${fmt.number(inSegmentPct, 1)} %",
                            Modifier.weight(1.0f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = rowHealthColor
                        )
                        Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.End) {
                            if (activeUnitMode == BodyUnitMode.KG && diffKg != null) {
                                val s = if (diffKg >= 0) "+" else ""
                                Text(
                                    "$s${fmt.number(fmt.convertWeight(diffKg), 2)} ${fmt.weightUnit()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = diffColor
                                )
                            } else if (activeUnitMode == BodyUnitMode.PERCENT && diffPct != null) {
                                val s = if (diffPct >= 0) "+" else ""
                                Text(
                                    "$s${fmt.number(diffPct, 1)} %",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = diffColor
                                )
                            }
                            Text(
                                "${fmt.number(shareOfTotalPct, 1)} % z celku",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (activeUnitMode == BodyUnitMode.KG) (shareOfTotalPct / 60.0).toFloat().coerceIn(0.06f, 1f)
                            else (inSegmentPct / 100.0).toFloat().coerceIn(0.06f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                        color = rowHealthColor,
                        trackColor = Color(0xFF1E293B)
                    )
                }
            }

            // Vyhodnocení symetrie Levá vs. Pravá strana
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val armBalanced = kotlin.math.abs(armDiffPct) <= 5.0
                val legBalanced = kotlin.math.abs(legDiffPct) <= 5.0
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = (if (armBalanced) HealthGreen else HealthAmber).copy(alpha = 0.12f))
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Symetrie rukou (P vs L)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        Text(
                            if (armBalanced) "Vyvážené (${fmt.number(kotlin.math.abs(armDiffPct), 1)} %)"
                            else "Rozdíl ${fmt.number(kotlin.math.abs(armDiffPct), 1)} %",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (armBalanced) HealthGreen else HealthAmber
                        )
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = (if (legBalanced) HealthGreen else HealthAmber).copy(alpha = 0.12f))
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Symetrie nohou (P vs L)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        Text(
                            if (legBalanced) "Vyvážené (${fmt.number(kotlin.math.abs(legDiffPct), 1)} %)"
                            else "Rozdíl ${fmt.number(kotlin.math.abs(legDiffPct), 1)} %",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (legBalanced) HealthGreen else HealthAmber
                        )
                    }
                }
            }

            // Rozklikávací přepínač grafu pro jednotlivé části těla
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedSegmentPos == null,
                    onClick = { selectedSegmentPos = null },
                    label = { Text("Všechny části v grafu", maxLines = 1) }
                )
                BODY_SEGMENT_DEFS.forEach { (pos, label, _) ->
                    FilterChip(
                        selected = selectedSegmentPos == pos,
                        onClick = { selectedSegmentPos = pos },
                        label = { Text(label.substringBefore(" ("), maxLines = 1) }
                    )
                }
            }

            // Graf vybrané části těla nebo všech částí
            val modeList = remember(list, showFatMode) {
                list.filter { snap ->
                    val map = if (showFatMode) snap.fatByPos else snap.muscleByPos
                    map.values.any { it > 0.05 } && map.values.sum() > (if (showFatMode) 3.0 else 30.0)
                }
            }.ifEmpty { list }
            val chartUnitStr = if (activeUnitMode == BodyUnitMode.KG) fmt.weightUnit() else "%"
            val selectedDef = BODY_SEGMENT_DEFS.firstOrNull { it.first == selectedSegmentPos }
            if (selectedDef != null) {
                val (pos, label, segColor) = selectedDef
                val rawPairs = modeList.mapNotNull { snap ->
                    val v = if (activeUnitMode == BodyUnitMode.KG) {
                        val map = if (showFatMode) snap.fatByPos else snap.muscleByPos
                        map[pos]?.takeIf { it > 0.05 }?.let(fmt::convertWeight)
                    } else {
                        segmentPercentValue(snap, pos, showFatMode)
                    }
                    if (v != null) snap.dateLabel.takeLast(5) to v else null
                }
                val sortedVals = rawPairs.map { it.second }.sorted()
                val med = if (sortedVals.isNotEmpty()) sortedVals[sortedVals.size / 2] else 1.0
                val filteredPairs = rawPairs.filter { it.second in (med * 0.55)..(med * 1.45) }.ifEmpty { rawPairs }
                Text(
                    "Vývoj – $label (${if (showFatMode) "Tuk" else "Svaly"} v $chartUnitStr · ${filteredPairs.size} měření)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = segColor
                )
                LineChart(
                    values = filteredPairs.map { it.second },
                    band = null,
                    color = segColor,
                    unit = chartUnitStr,
                    xLabels = filteredPairs.map { it.first }
                )
            } else {
                val xLabels = modeList.map { it.dateLabel.takeLast(5) }
                val multiSeries = BODY_SEGMENT_DEFS.map { (pos, label, segColor) ->
                    val rawPosVals = modeList.map { snap ->
                        if (activeUnitMode == BodyUnitMode.KG) {
                            val map = if (showFatMode) snap.fatByPos else snap.muscleByPos
                            map[pos]?.takeIf { it > 0.05 }?.let(fmt::convertWeight)
                        } else {
                            segmentPercentValue(snap, pos, showFatMode)
                        }
                    }
                    val nonNullSorted = rawPosVals.filterNotNull().sorted()
                    val med = if (nonNullSorted.isNotEmpty()) nonNullSorted[nonNullSorted.size / 2] else 1.0
                    val vals = rawPosVals.map { v -> v?.takeIf { it in (med * 0.55)..(med * 1.45) } }
                    SeriesItem(
                        id = "seg-$pos",
                        label = label.substringBefore(" ("),
                        color = segColor,
                        values = vals,
                        unit = chartUnitStr
                    )
                }
                MultiSeriesLineChart(
                    series = multiSeries,
                    xLabels = xLabels,
                    title = if (showFatMode) "Porovnání tuku v částech těla ($chartUnitStr)"
                    else "Porovnání svalů v částech těla ($chartUnitStr)"
                )
            }
            }
        }
    }
}

/**
 * Vyhrazený prostor pro konkrétní metriku otevřenou z obrazovky Dnes.
 * Zobrazuje POUZE relevantní data, ukazatele a grafy pro danou oblast (např. Spánek jen pro spánek),
 * bez horního přepínače všech ostatních nesouvisejících metrik.
 */
@Composable
fun DedicatedMetricDetailScreen(
    sl: ServiceLocator,
    type: MetricType,
    onBack: () -> Unit,
) {
    var daysCount by remember { mutableStateOf(30) }
    var refreshTick by remember { mutableStateOf(0) }
    var showInfoDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val units by sl.settings.unitSystemFlow.collectAsState()
    val cfg = getMetricUiConfig(type)

    val dataBundle by io(Triple(type, daysCount, units to refreshTick)) {
        val zone = ZoneId.of(sl.settings.zoneId)
        val today = LocalDate.now(zone)
        val fromDate = today.minusDays((daysCount - 1).toLong()).toString()
        val toDate = today.toString()
        val primaryRows = if (type in WithingsService.BODY_COMPOSITION_TYPES) {
            sl.withings.trend(type, daysCount)
        } else {
            sl.db.observations().forTypeInRange(type.name, fromDate, toDate).map { it.toDomain() }
        }
        val mainSeries = MetricSeries.build(type, primaryRows, today, daysCount)
        val latestObs = primaryRows.filter { it.type == type && !it.deleted }.maxByOrNull { it.startUtcMs }
            ?: sl.db.observations().latest(type.name)?.toDomain()

        // Doplňková data specifická pro danou doménu (načítaná cíleně jen pro daný typ)
        val sleepScoreRows = if (type == MetricType.SLEEP_DURATION) {
            sl.db.observations().forTypeInRange(MetricType.SLEEP_SCORE.name, fromDate, toDate).map { it.toDomain() }
        } else emptyList()
        val sleepScoreSeries = if (type == MetricType.SLEEP_DURATION) {
            MetricSeries.build(MetricType.SLEEP_SCORE, sleepScoreRows, today, daysCount)
        } else null
        val bodyFatSeries = if (type == MetricType.WEIGHT) {
            val bfRows = runCatching { sl.withings.trend(MetricType.BODY_FAT, daysCount) }.getOrDefault(emptyList())
            MetricSeries.build(MetricType.BODY_FAT, bfRows, today, daysCount)
        } else null

        val sleepScoresByDate = if (type == MetricType.SLEEP_DURATION) {
            sleepScoreRows.filter { !it.deleted }
                .associate { (it.localDate ?: "") to it.value }
        } else emptyMap()
        val suspectWeights = if (type == MetricType.WEIGHT) {
            runCatching { sl.withings.suspectMeasurements(daysCount) }.getOrDefault(emptyList())
        } else emptyList()

        val refWeightKg = (if (type == MetricType.WEIGHT) latestObs?.value else null)
            ?: sl.db.observations().latest(MetricType.WEIGHT.name)?.value?.takeIf { it in 25.0..250.0 }
            ?: 93.10
        val isBodyComp = isConvertibleBodyCompMetric(type) || type == MetricType.WEIGHT
        val latestMuscleKg = if (isBodyComp) {
            sl.db.observations().latest(MetricType.MUSCLE_MASS.name)?.value
        } else null
        val latestFatPct = if (isBodyComp) {
            sl.db.observations().latest(MetricType.BODY_FAT.name)?.value
        } else null
        val latestBoneKg = if (isBodyComp) {
            sl.db.observations().latest(MetricType.BONE_MASS.name)?.value
        } else null

        DedicatedBundle(
            today = today,
            zone = zone,
            mainSeries = mainSeries,
            latestObs = latestObs,
            sleepScoreSeries = sleepScoreSeries,
            bodyFatSeries = bodyFatSeries,
            sleepScoresByDate = sleepScoresByDate,
            suspectWeights = suspectWeights,
            refWeightKg = refWeightKg,
            latestMuscleKg = latestMuscleKg,
            latestFatPct = latestFatPct,
            latestBoneKg = latestBoneKg
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // Dedikovaná hlavička s návratem na Dnes
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Zpět",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Dnes", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                }
                Spacer(Modifier.width(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(cfg.color.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(cfg.icon, contentDescription = null, tint = cfg.color, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            cfg.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            "Vyhrazený prostor metriky",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
            MetricInfoIconButton(onClick = { showInfoDialog = true })
        }

        if (showInfoDialog) {
            MetricInfoPopupDialog(type = type, cfg = cfg, onDismiss = { showInfoDialog = false })
        }

        Spacer(Modifier.height(8.dp))

        // Volba časového okna
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF131722))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Období analýzy:", style = MaterialTheme.typography.labelMedium, color = Color(0xFF94A3B8))
            Row {
                listOf(7, 14, 30, 90).forEach { d ->
                    FilterChip(
                        selected = daysCount == d,
                        onClick = { daysCount = d },
                        label = { Text("${d}d") },
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        var bodyUnitMode by remember { mutableStateOf(BodyUnitMode.KG) }
        if (isConvertibleBodyCompMetric(type)) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = bodyUnitMode == BodyUnitMode.KG,
                    onClick = { bodyUnitMode = BodyUnitMode.KG },
                    leadingIcon = { Icon(PulseIcons.UnitPhysical, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("V kilogramech (${fmt.weightUnit()})", maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = bodyUnitMode == BodyUnitMode.PERCENT,
                    onClick = { bodyUnitMode = BodyUnitMode.PERCENT },
                    leadingIcon = { Icon(PulseIcons.UnitPercent, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("V procentech (%)", maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        val b = dataBundle
        if (b == null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
            ) {
                Text("Načítání dat prostoru ${cfg.title}…", modifier = Modifier.padding(16.dp))
            }
            return@Column
        }

        val series = b.mainSeries
        val latestObs = b.latestObs

        if (!series.hasData || latestObs == null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(cfg.color.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(cfg.icon, contentDescription = null, tint = cfg.color, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(cfg.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Pro tuto oblast zatím nejsou zaznamenána žádná data. Spusťte synchronizaci na obrazovce Dnes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
            return@Column
        }

        // Specifický obsah podle typu metriky
        when (type) {
            MetricType.ECG -> {
                val ecgDetails = parseEcgOrigin(latestObs.origin, latestObs.value)
                val rhythmTitle = when (ecgDetails.afibClassification) {
                    0 -> "Normální sinusový rytmus"
                    1 -> "Fibrilace síní (AFib detekována)"
                    2 -> "Neprůkazný záznam"
                    else -> "Záznam ECG pořízen"
                }
                val rhythmColor = when (ecgDetails.afibClassification) {
                    0 -> Color(0xFF10B981)
                    1 -> Color(0xFFEF4444)
                    2 -> Color(0xFFF59E0B)
                    else -> Color(0xFF38BDF8)
                }
                val rhythmDesc = when (ecgDetails.afibClassification) {
                    0 -> "Nebyly detekovány žádné známky fibrilace síní (AFib). Srdeční rytmus je pravidelný."
                    1 -> "Záznam vykazuje známky fibrilace síní. Pokud pociťujete dušnost či palpitace, kontaktujte lékaře."
                    2 -> "Signál obsahoval pohybové artefakty nebo byl kontakt s elektrodami nestabilní."
                    else -> "Jednosvodové měření elektrické aktivity srdce ze snímačů Withings."
                }
                val rhythmIcon = when (ecgDetails.afibClassification) {
                    0 -> Icons.Outlined.CheckCircle
                    1 -> Icons.Outlined.Warning
                    2 -> Icons.Outlined.HelpOutline
                    else -> Icons.Outlined.MonitorHeart
                }

                // Hero Karta EKG
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cfg.color.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, cfg.color.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("VÝSLEDEK ELEKTROKARDIOGRAMU (ECG)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFF43F5E))
                                Text("Měřeno ${fmt.time(latestObs.startUtcMs)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(rhythmColor.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Icon(rhythmIcon, contentDescription = null, tint = rhythmColor, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    rhythmTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = rhythmColor
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Parametry EKG: Tep + QTc
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(
                                    text = if (latestObs.value >= 200.0) "${fmt.number(latestObs.value, 0)} ms" else "—",
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                Text("Korigovaný interval QTc (norma 350–450 ms)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                            }
                            ecgDetails.heartRate?.takeIf { it > 0 }?.let { hrVal ->
                                Column(horizontalAlignment = Alignment.End) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Favorite, contentDescription = null, tint = Color(0xFFF43F5E), modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("$hrVal", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                    }
                                    Text("Tep při měření (bpm)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Text(
                            rhythmDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Vizuální EKG páska / EKG strip náhled
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F141C)),
                    border = BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Timeline, contentDescription = null, tint = Color(0xFFF43F5E), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Jednosvodová ECG křivka (Svod I)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFFE2E8F0))
                            }
                            Text("500 Hz · 25 mm/s", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
                        }
                        Spacer(Modifier.height(8.dp))
                        EcgLeadStripCanvas(
                            modifier = Modifier.fillMaxWidth().height(110.dp),
                            color = Color(0xFFF43F5E)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Snímáno senzory z levé a pravé ruky (madlo váhy Withings Body Scan). Zobrazen standardní P-Q-R-S-T morfologický komplex.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                LazyMetricHistoryListCard(
                    sl = sl,
                    type = type,
                    zone = b.zone,
                    usual = series.usual,
                    refWeightKg = b.refWeightKg,
                    bodyUnitMode = bodyUnitMode,
                    refreshTick = refreshTick
                )
            }

            MetricType.SLEEP_DURATION -> {
                val latestHours = latestObs.value / 3600.0
                val latestScore = b.sleepScoresByDate[latestObs.localDate ?: ""]
                    ?: b.sleepScoreSeries?.values?.lastOrNull { it != null }
                val sleepStartStr = java.time.Instant.ofEpochMilli(latestObs.startUtcMs).atZone(b.zone)
                    .toLocalTime().toString().take(5)
                val sleepEndStr = latestObs.endUtcMs?.let {
                    java.time.Instant.ofEpochMilli(it).atZone(b.zone).toLocalTime().toString().take(5)
                } ?: "—"

                val (scoreQualifier, qualifierColor) = when {
                    latestScore == null -> "Měřeno" to Color(0xFF94A3B8)
                    latestScore >= 85 -> "Vynikající spánek" to Color(0xFF10B981)
                    latestScore >= 75 -> "Dobrý spánek" to Color(0xFF10B981)
                    latestScore >= 60 -> "Slušný spánek" to Color(0xFFF59E0B)
                    else -> "Krátký / Neklidný" to Color(0xFFEF4444)
                }

                // Hero Karta Spánku
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cfg.color.copy(alpha = 0.14f)),
                    border = BorderStroke(1.dp, cfg.color.copy(alpha = 0.4f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("POSLEDNÍ ZAZNAMENANÝ SPÁNEK", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFBAE6FD))
                                Text("Noc ${latestObs.localDate ?: fmt.time(latestObs.startUtcMs)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                            }
                            Text(
                                scoreQualifier,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = qualifierColor,
                                modifier = Modifier
                                    .background(qualifierColor.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                            Column {
                                Text(fmt.sleep(latestObs.value), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Bedtime, contentDescription = null, tint = Color(0xFFBAE6FD), modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Usnutí $sleepStartStr", style = MaterialTheme.typography.bodySmall, color = Color(0xFFBAE6FD))
                                    Spacer(Modifier.width(8.dp))
                                    Text("→", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Outlined.WbSunny, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Probuzení $sleepEndStr", style = MaterialTheme.typography.bodySmall, color = Color(0xFFBAE6FD))
                                }
                            }
                            if (latestScore != null) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${latestScore.toInt()}", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = cfg.color)
                                    Text("Skóre spánku / 100", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        val goalPct = (latestHours / 8.0).toFloat().coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { goalPct },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = cfg.color,
                            trackColor = Color(0xFF1E293B)
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Plnění doporučeného cíle (8 h): ${(goalPct * 100).toInt()} %", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                            series.usual?.let { u ->
                                val diffMin = ((latestObs.value - u.median) / 60.0).toLong()
                                val sign = if (diffMin >= 0) "+" else ""
                                Text("$sign$diffMin min vs osobní medián", style = MaterialTheme.typography.labelSmall, color = cfg.color)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Statistiky spánku (2x2)
                val finiteSleep = series.values.filterNotNull()
                val avgSleepS = if (finiteSleep.isNotEmpty()) finiteSleep.average() else 0.0
                val minSleepS = finiteSleep.minOrNull() ?: 0.0
                val maxSleepS = finiteSleep.maxOrNull() ?: 0.0
                val avgScore = b.sleepScoreSeries?.values?.filterNotNull()?.takeIf { it.isNotEmpty() }?.average()

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("SOUHRN SPÁNKU ZA ${daysCount} DNÍ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            StatBox("Průměrná doba spánku", fmt.sleep(avgSleepS), Modifier.weight(1f))
                            StatBox("Medián období", series.usual?.let { fmt.sleep(it.median) } ?: fmt.sleep(avgSleepS), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            StatBox("Nejkratší – Nejdelší noc", "${fmt.sleep(minSleepS)} – ${fmt.sleep(maxSleepS)}", Modifier.weight(1f))
                            StatBox("Průměrné skóre spánku", avgScore?.let { "${it.toInt()} / 100" } ?: "—", Modifier.weight(1f))
                        }
                    }
                }

                // Graf 1: Doba spánku v hodinách
                SectionTitle("Vývoj délky spánku (hodiny · ${daysCount}d)", icon = Icons.Outlined.Bedtime)
                LineChart(
                    values = series.values.map { it?.div(3600.0) },
                    band = series.usual?.let { (it.low / 3600.0)..(it.high / 3600.0) },
                    color = cfg.color
                )

                // Graf 2: Skóre spánku (pokud existuje)
                val scSeries = b.sleepScoreSeries
                if (scSeries != null && scSeries.hasData) {
                    SectionTitle("Vývoj skóre kvality spánku (0–100 · ${daysCount}d)", icon = Icons.Outlined.Star)
                    LineChart(
                        values = scSeries.values,
                        band = scSeries.usual?.let { it.low..it.high },
                        color = Color(0xFFA855F7)
                    )
                }

                // Rolovací deník spánku s postupným načítáním (LazyColumn)
                Spacer(Modifier.height(8.dp))
                LazyMetricHistoryListCard(
                    sl = sl,
                    type = type,
                    zone = b.zone,
                    usual = series.usual,
                    sleepScoresByDate = b.sleepScoresByDate,
                    refreshTick = refreshTick
                )
            }

            MetricType.WEIGHT -> {
                var manualWeightInput by remember { mutableStateOf("") }
                var weightSavedMsg by remember { mutableStateOf<String?>(null) }
                val latestBf = b.bodyFatSeries?.values?.lastOrNull { it != null }
                val finiteW = series.values.filterNotNull()
                val firstW = finiteW.firstOrNull()
                val lastW = finiteW.lastOrNull() ?: latestObs.value
                val deltaPeriod = if (firstW != null && finiteW.size >= 2) fmt.convertWeight(lastW - firstW) else null

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cfg.color.copy(alpha = 0.14f)),
                    border = BorderStroke(1.dp, cfg.color.copy(alpha = 0.4f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("TĚLESNÁ HMOTNOST & SLOŽENÍ TĚLA", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF99F6E4))
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                            Column {
                                Text(fmt.weight(latestObs.value), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                Text("Měřeno ${fmt.time(latestObs.startUtcMs)} · zdroj: ${latestObs.provider.name}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                            }
                            if (latestBf != null) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${fmt.number(latestBf, 1)} %", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF06B6D4))
                                    Text("Tělesný tuk", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                            }
                        }
                        if (deltaPeriod != null) {
                            Spacer(Modifier.height(8.dp))
                            val sign = if (deltaPeriod >= 0) "+" else ""
                            Text(
                                "Změna za ${daysCount}d: $sign${fmt.number(deltaPeriod, 1)} ${fmt.weightUnit()}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = cfg.color
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Rychlý zápis váhy přímo v prostoru Váhy
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Scale, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("RYCHLÝ ZÁPIS DNEŠNÍ VÁHY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = manualWeightInput,
                                onValueChange = { manualWeightInput = it },
                                label = { Text("Hmotnost v kg (např. 75.2)") },
                                modifier = Modifier.weight(1.5f)
                            )
                            Button(
                                onClick = {
                                    val kg = manualWeightInput.replace(',', '.').toDoubleOrNull()
                                    if (kg != null && kg in 25.0..250.0) {
                                        scope.launch(Dispatchers.IO) {
                                            val now = System.currentTimeMillis()
                                            val obs = app.pulse4.domain.MetricObservation(
                                                id = "manual-weight-$now",
                                                provider = Provider.MANUAL,
                                                accountId = "local",
                                                externalId = "mw-$now",
                                                type = MetricType.WEIGHT,
                                                startUtcMs = now,
                                                endUtcMs = now,
                                                value = kg,
                                                zoneId = sl.settings.zoneId,
                                                localDate = b.today.toString()
                                            )
                                            sl.db.observations().upsert(listOf(obs.toRow()))
                                            manualWeightInput = ""
                                            weightSavedMsg = "Váha ${fmt.weight(kg)} uložena."
                                            refreshTick++
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Uložit") }
                        }
                        weightSavedMsg?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF10B981), modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }

                SectionTitle("Vývoj tělesné hmotnosti (${fmt.weightUnit()} · ${daysCount}d)", icon = Icons.Outlined.Scale)
                LineChart(
                    values = series.values.map { it?.let(fmt::convertWeight) },
                    band = series.usual?.let { fmt.convertWeight(it.low)..fmt.convertWeight(it.high) },
                    color = cfg.color
                )

                val bfSeries = b.bodyFatSeries
                if (bfSeries != null && bfSeries.hasData) {
                    SectionTitle("Vývoj tělesného tuku (% · ${daysCount}d)", icon = Icons.Outlined.WaterDrop)
                    LineChart(
                        values = bfSeries.values,
                        band = bfSeries.usual?.let { it.low..it.high },
                        color = Color(0xFF06B6D4)
                    )
                }

                if (b.suspectWeights.isNotEmpty()) {
                    SectionTitle("Nejednoznačná vážení (Dítě / Jiný člen rodiny)", icon = Icons.Outlined.FamilyRestroom)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B18))
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Tato vážení váha Withings označila jako nejednoznačná (attrib) nebo vybočují z vašeho váhového filtru (např. na váhu stouplo dítě). Do vašeho grafu se nezapočítávají, dokud o nich nerozhodnete:",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFDE68A)
                            )
                            b.suspectWeights.forEach { sObs ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${fmt.weight(sObs.value)} · ${sObs.localDate ?: fmt.time(sObs.startUtcMs)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFF59E0B)
                                        )
                                        Text(
                                            "Zdroj: ${sObs.provider.name}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                    Row {
                                        TextButton(onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                sl.withings.confirm(sObs.id)
                                                refreshTick++
                                            }
                                        }) {
                                            Text("Moje", color = Color(0xFF10B981), maxLines = 1, softWrap = false)
                                        }
                                        TextButton(onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                val ok = sl.withings.forwardSingleWeightToChild(sObs, System.currentTimeMillis(), excludeFromParent = true)
                                                weightSavedMsg = if (ok) "Váha ${fmt.weight(sObs.value)} přeposlána dítěti přes P2P!" else "Nejprve vyberte kontakt dítěte v Nastavení → Garmin & Withings."
                                                refreshTick++
                                            }
                                        }) {
                                            Text("Poslat dítěti", color = Color(0xFF38BDF8), maxLines = 1, softWrap = false)
                                        }
                                        TextButton(onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                sl.withings.markWrong(sObs.id, System.currentTimeMillis())
                                                refreshTick++
                                            }
                                        }) {
                                            Text("Cizí", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                LazyMetricHistoryListCard(
                    sl = sl,
                    type = type,
                    zone = b.zone,
                    usual = series.usual,
                    refWeightKg = b.refWeightKg,
                    bodyUnitMode = bodyUnitMode,
                    refreshTick = refreshTick,
                    onStatusMessage = { weightSavedMsg = it },
                    onRefreshRequested = { refreshTick++ }
                )
            }

            else -> {
                // Dedikovaný prostor pro HRV, Body Battery, Klidový tep, Stres nebo Kroky
                val latestVal = latestObs.value
                val usual = series.usual
                val formattedVal = if (type == MetricType.BODY_FAT) {
                    if (bodyUnitMode == BodyUnitMode.KG) {
                        val fatKg = b.refWeightKg * (latestVal / 100.0)
                        "${fmt.weight(fatKg)} (${fmt.number(latestVal, 1)} %)"
                    } else {
                        "${fmt.number(latestVal, 1)} %"
                    }
                } else if (type in setOf(MetricType.MUSCLE_MASS, MetricType.FAT_FREE_MASS, MetricType.BONE_MASS) && bodyUnitMode == BodyUnitMode.PERCENT) {
                    val pct = (latestVal / b.refWeightKg) * 100.0
                    "${fmt.number(pct, 1)} % (${fmt.weight(latestVal)})"
                } else {
                    formatMetricValue(type, latestVal)
                }

                val (statusText, statusColor) = if (usual == null) {
                    "Sběr osobního pásma" to Color(0xFF94A3B8)
                } else when (type) {
                    MetricType.RESTING_HEART_RATE, MetricType.STRESS -> when {
                        latestVal < usual.low -> "Nízké / Odpočinek" to Color(0xFF10B981)
                        latestVal > usual.high -> "Zvýšené nad normál" to Color(0xFFEF4444)
                        else -> "V obvyklém pásmu" to Color(0xFF10B981)
                    }
                    else -> when {
                        latestVal < usual.low -> "Pod obvyklým pásmem" to Color(0xFFF59E0B)
                        latestVal > usual.high -> "Nadstandardní hodnota" to Color(0xFF10B981)
                        else -> "V osobním pásmu" to Color(0xFF10B981)
                    }
                }

                val dedArt = getMetricArtworkRes(type)
                val dedMotif = getMetricMotifKind(type)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF060B14)),
                    border = BorderStroke(
                        1.2.dp,
                        Brush.linearGradient(
                            listOf(cfg.color.copy(alpha = 0.65f), Color.White.copy(alpha = 0.18f), cfg.color.copy(alpha = 0.30f))
                        )
                    )
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = dedArt),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize().alpha(0.82f)
                        )
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(Color(0xCC040811), Color(0x88040811), Color(0xE6040811))
                                )
                            )
                            drawMetricMotifIllustration(
                                motifKind = dedMotif,
                                primaryColor = cfg.color,
                                secondaryColor = Color.White,
                                alphaMultiplier = 0.85f
                            )
                        }
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(cfg.title.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFCBD5E1))
                                    Text("Měřeno ${fmt.time(latestObs.startUtcMs)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE2E8F0))
                                }
                                Text(
                                    statusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor,
                                    modifier = Modifier
                                        .background(Color(0xCC050912), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                formattedVal,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                                ),
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )

                            // Specifické vizuální ukazatele podle metriky
                            if (type == MetricType.STEPS) {
                                Spacer(Modifier.height(10.dp))
                                val pct = (latestVal / 10000.0).toFloat().coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { pct },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = cfg.color,
                                    trackColor = Color(0xFF1E293B)
                                )
                                Spacer(Modifier.height(4.dp))
                                val estKm = latestVal * 0.00076
                                val estKcal = (latestVal * 0.04).toInt()
                                Text(
                                    "Denní cíl 10 000 kroků: ${(pct * 100).toInt()} % · odhad ${fmt.km(estKm * 1000.0)} · ~$estKcal kcal",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFE2E8F0)
                                )
                            } else if (type == MetricType.BODY_BATTERY) {
                                Spacer(Modifier.height(10.dp))
                                val pct = (latestVal / 100.0).toFloat().coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { pct },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = cfg.color,
                                    trackColor = Color(0xFF1E293B)
                                )
                                Spacer(Modifier.height(4.dp))
                                val readiness = when {
                                    latestVal >= 75 -> "Vysoká energetická rezerva – ideální den pro intenzivní trénink."
                                    latestVal >= 50 -> "Střední nabití – vhodné pro udržovací trénink."
                                    else -> "Nízká rezerva – doporučen klidový režim nebo lehká regenerace."
                                }
                                Text(readiness, style = MaterialTheme.typography.labelSmall, color = Color(0xFFE2E8F0))
                            } else if (type == MetricType.STRESS) {
                                Spacer(Modifier.height(8.dp))
                                val stressZone = when {
                                    latestVal <= 25 -> "0–25: Klid a regenerace (převaha parasympatiku)"
                                    latestVal <= 50 -> "26–50: Nízký fyziologický stres"
                                    latestVal <= 75 -> "51–75: Střední zátěž organismu"
                                    else -> "76–100: Vysoký stres / vyčerpání"
                                }
                                Text(stressZone, style = MaterialTheme.typography.labelSmall, color = Color(0xFFE2E8F0))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Statistiky 2x2
                val finite = series.values.filterNotNull()
                val avgV = if (finite.isNotEmpty()) finite.average() else 0.0
                val minV = finite.minOrNull() ?: 0.0
                val maxV = finite.maxOrNull() ?: 0.0
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, cfg.color.copy(alpha = 0.35f)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101D))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("STATISTIKA OBDOBÍ (${daysCount} DNÍ)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cfg.color)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            StatBox("Průměr období", "${fmt.number(avgV, 0)} ${cfg.unit}", Modifier.weight(1f))
                            StatBox("Medián období", usual?.let { "${fmt.number(it.median, 0)} ${cfg.unit}" } ?: "—", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            StatBox("Rozpětí Min – Max", "${fmt.number(minV, 0)} – ${fmt.number(maxV, 0)} ${cfg.unit}", Modifier.weight(1f))
                            StatBox(
                                if (type == MetricType.STEPS) "Celkem za období" else "Obvyklé pásmo P25–P75",
                                if (type == MetricType.STEPS) "${fmt.number(finite.sum(), 0)} kroků"
                                else usual?.let { "${fmt.number(it.low, 0)} – ${fmt.number(it.high, 0)} ${cfg.unit}" } ?: "—",
                                Modifier.weight(1f)
                            )
                        }
                    }
                }

                SectionTitle("Vývoj v čase (${daysCount} dní)", icon = Icons.AutoMirrored.Outlined.ShowChart)
                val isWeightType = type in listOf(MetricType.WEIGHT, MetricType.MUSCLE_MASS, MetricType.BONE_MASS, MetricType.FAT_FREE_MASS)
                val chartValues = if (type == MetricType.BODY_FAT) {
                    if (bodyUnitMode == BodyUnitMode.KG) {
                        series.values.map { it?.let { p -> fmt.convertWeight(b.refWeightKg * (p / 100.0)) } }
                    } else {
                        series.values
                    }
                } else if (isWeightType) {
                    if (bodyUnitMode == BodyUnitMode.PERCENT && type != MetricType.WEIGHT) {
                        series.values.map { it?.let { w -> (w / b.refWeightKg) * 100.0 } }
                    } else {
                        series.values.map { it?.let(fmt::convertWeight) }
                    }
                } else series.values

                val chartBand = if (type == MetricType.BODY_FAT) {
                    if (bodyUnitMode == BodyUnitMode.KG) {
                        usual?.let { (fmt.convertWeight(b.refWeightKg * (it.low / 100.0)))..(fmt.convertWeight(b.refWeightKg * (it.high / 100.0))) }
                    } else {
                        usual?.let { it.low..it.high }
                    }
                } else if (isWeightType) {
                    if (bodyUnitMode == BodyUnitMode.PERCENT && type != MetricType.WEIGHT) {
                        usual?.let { ((it.low / b.refWeightKg) * 100.0)..((it.high / b.refWeightKg) * 100.0) }
                    } else {
                        usual?.let { fmt.convertWeight(it.low)..fmt.convertWeight(it.high) }
                    }
                } else usual?.let { it.low..it.high }

                val chartUnit = if (type == MetricType.BODY_FAT) {
                    if (bodyUnitMode == BodyUnitMode.KG) fmt.weightUnit() else "%"
                } else if (isWeightType) {
                    if (bodyUnitMode == BodyUnitMode.PERCENT && type != MetricType.WEIGHT) "%" else fmt.weightUnit()
                } else cfg.unit

                LineChart(
                    values = chartValues,
                    band = chartBand,
                    color = cfg.color,
                    unit = chartUnit,
                    xLabels = series.dates.map { it.toString().takeLast(5) },
                    artworkRes = dedArt,
                    motifKind = dedMotif
                )

                Spacer(Modifier.height(8.dp))
                LazyMetricHistoryListCard(
                    sl = sl,
                    type = type,
                    zone = b.zone,
                    usual = usual,
                    refWeightKg = b.refWeightKg,
                    bodyUnitMode = bodyUnitMode,
                    refreshTick = refreshTick
                )
            }
        }

        if (isConvertibleBodyCompMetric(type) || type == MetricType.WEIGHT) {
            Spacer(Modifier.height(10.dp))
            BodyCompositionStackedBar(
                weightKg = b.refWeightKg,
                muscleKg = b.latestMuscleKg,
                fatPct = b.latestFatPct,
                boneKg = b.latestBoneKg,
                unitMode = bodyUnitMode,
                onUnitModeChange = { bodyUnitMode = it }
            )
            Spacer(Modifier.height(10.dp))
            SegmentalBodyCompositionSection(
                sl = sl,
                initialModeFat = (type == MetricType.BODY_FAT),
                daysCount = daysCount,
                externalUnitMode = bodyUnitMode,
                onUnitModeChange = { bodyUnitMode = it }
            )
        }
    }
}

/**
 * Rozbalovací (ve výchozím stavu schovaná) karta seznamu naměřených hodnot s odloženým načítáním.
 * Při otevření detailu metriky z obrazovky Dnes nebo v záložce Metriky je seznam sbalený
 * a historické záznamy se z databáze začnou načítat teprve ve chvíli, kdy uživatel kartu rozklikne.
 */
@Composable
fun LazyMetricHistoryListCard(
    sl: ServiceLocator,
    type: MetricType,
    zone: ZoneId = ZoneId.of(sl.settings.zoneId),
    usual: app.pulse4.analytics.Baseline.UsualRange? = null,
    refWeightKg: Double = 93.10,
    bodyUnitMode: BodyUnitMode = BodyUnitMode.KG,
    sleepScoresByDate: Map<String, Double> = emptyMap(),
    refreshTick: Int = 0,
    onStatusMessage: ((String) -> Unit)? = null,
    onRefreshRequested: (() -> Unit)? = null,
) {
    val cfg = getMetricUiConfig(type)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val pageSize = 8

    // Ve výchozím stavu je seznam hodnot schovaný (sbalený) a nic z DB nenačítá
    var isExpanded by remember(type) { mutableStateOf(false) }

    var loadedItems by remember(type, refreshTick) {
        mutableStateOf<List<app.pulse4.domain.MetricObservation>>(emptyList())
    }
    var totalCount by remember(type, refreshTick) { mutableStateOf(0) }
    var dbOffset by remember(type, refreshTick) { mutableStateOf(0) }
    var isInitialLoading by remember(type, refreshTick) { mutableStateOf(false) }
    var isLoadingMore by remember(type, refreshTick) { mutableStateOf(false) }
    var hasMore by remember(type, refreshTick) { mutableStateOf(true) }

    // První stránka se načte teprve tehdy, když uživatel seznam rozbalí (isExpanded == true)
    LaunchedEffect(isExpanded, type, refreshTick) {
        if (!isExpanded || loadedItems.isNotEmpty()) return@LaunchedEffect
        isInitialLoading = true
        val (firstBatch, count) = withContext(Dispatchers.IO) {
            val total = sl.db.observations().countForType(type.name)
            val rows = sl.db.observations()
                .pagedForType(type.name, limit = pageSize, offset = 0)
                .map { it.toDomain() }
            rows to total
        }
        loadedItems = firstBatch
        totalCount = maxOf(count, firstBatch.size)
        dbOffset = firstBatch.size
        hasMore = firstBatch.size >= pageSize && dbOffset < totalCount
        isInitialLoading = false
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalVisible = layoutInfo.totalItemsCount
            val lastVisibleIdx = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            isExpanded && totalVisible > 0 && lastVisibleIdx >= totalVisible - 2
        }
    }

    LaunchedEffect(shouldLoadMore, isExpanded, hasMore, isInitialLoading, isLoadingMore) {
        if (isExpanded && shouldLoadMore && hasMore && !isInitialLoading && !isLoadingMore) {
            isLoadingMore = true
            val nextBatch = withContext(Dispatchers.IO) {
                sl.db.observations()
                    .pagedForType(type.name, limit = pageSize, offset = dbOffset)
                    .map { it.toDomain() }
            }
            if (nextBatch.isEmpty()) {
                hasMore = false
            } else {
                val existingIds = loadedItems.asSequence().map { it.id }.toHashSet()
                val uniqueNew = nextBatch.filterNot { it.id in existingIds }
                loadedItems = loadedItems + uniqueNew
                dbOffset += nextBatch.size
                if (nextBatch.size < pageSize || dbOffset >= totalCount) {
                    hasMore = false
                }
            }
            isLoadingMore = false
        }
    }

    val sectionTitleText = when (type) {
        MetricType.SLEEP_DURATION -> "Deník spánku"
        MetricType.WEIGHT -> "Historie vážení"
        MetricType.ECG -> "Historie záznamů EKG"
        else -> "Seznam naměřených hodnot"
    }

    val sectionIcon = when (type) {
        MetricType.SLEEP_DURATION -> Icons.Outlined.Nightlight
        MetricType.WEIGHT -> Icons.Outlined.History
        else -> Icons.Outlined.FormatListBulleted
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF090E18)),
        border = BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                listOf(
                    cfg.color.copy(alpha = if (isExpanded) 0.50f else 0.32f),
                    Color.White.copy(alpha = 0.12f),
                    cfg.color.copy(alpha = 0.20f)
                )
            )
        )
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Klikací hlavička pro rozbalení / sbalení seznamu hodnot
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(cfg.color.copy(alpha = 0.18f), RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            sectionIcon,
                            contentDescription = null,
                            tint = cfg.color,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = sectionTitleText,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = when {
                                !isExpanded -> "Klepnutím rozbalíte a načtete jednotlivé hodnoty"
                                isInitialLoading && loadedItems.isEmpty() -> "Načítání záznamů…"
                                hasMore -> "Zobrazeno ${loadedItems.size} z $totalCount · rolujte pro starší"
                                else -> "Všech ${loadedItems.size} záznamů načteno"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isExpanded && totalCount > 0) {
                        Box(
                            modifier = Modifier
                                .background(cfg.color.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${loadedItems.size}/$totalCount",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                                ),
                                fontWeight = FontWeight.Bold,
                                color = cfg.color
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                if (isExpanded) cfg.color.copy(alpha = 0.20f) else Color(0xFF141D2E),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = if (isExpanded) "Skrýt ▲" else "Rozbalit ▼",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isExpanded) cfg.color else Color(0xFFE2E8F0)
                        )
                    }
                }
            }

            if (isExpanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                ) {
                    if (isInitialLoading && loadedItems.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = cfg.color
                                )
                                Text(
                                    "Načítání posledních hodnot…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    } else if (loadedItems.isEmpty()) {
                        Text(
                            "Zatím žádné historické hodnoty.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        // Vnitřní rolovací okno LazyColumn – vykresluje pouze viditelné řádky a plynule dočítá další
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 336.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(vertical = 2.dp)
                        ) {
                    itemsIndexed(
                        items = loadedItems,
                        key = { _, obs -> obs.id }
                    ) { index, obs ->
                        val prevObs = loadedItems.getOrNull(index + 1)
                        val deltaVal = prevObs?.let { obs.value - it.value }

                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (index == 0) cfg.color.copy(alpha = 0.11f) else Color(0xFF111826),
                            border = BorderStroke(
                                0.8.dp,
                                if (index == 0) cfg.color.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.06f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            when (type) {
                                MetricType.SLEEP_DURATION -> {
                                    val st = java.time.Instant.ofEpochMilli(obs.startUtcMs).atZone(zone).toLocalTime().toString().take(5)
                                    val en = obs.endUtcMs?.let {
                                        java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalTime().toString().take(5)
                                    } ?: "—"
                                    val sc = sleepScoresByDate[obs.localDate ?: ""]
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                obs.localDate ?: fmt.time(obs.startUtcMs),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Text(
                                                "$st → $en · ${obs.provider.name}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (sc != null) {
                                                Text(
                                                    "Skóre ${sc.toInt()}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFFA855F7),
                                                    modifier = Modifier
                                                        .padding(end = 10.dp)
                                                        .background(Color(0xFFA855F7).copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                            Text(
                                                fmt.sleep(obs.value),
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                                                ),
                                                fontWeight = FontWeight.ExtraBold,
                                                color = cfg.color
                                            )
                                        }
                                    }
                                }

                                MetricType.WEIGHT -> {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    fmt.weight(obs.value),
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                                                    ),
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.White
                                                )
                                                if (deltaVal != null && kotlin.math.abs(deltaVal) >= 0.05) {
                                                    val convD = fmt.convertWeight(deltaVal)
                                                    val sign = if (convD >= 0) "+" else ""
                                                    Text(
                                                        "$sign${fmt.number(convD, 1)} ${fmt.weightUnit()}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color(0xFF94A3B8)
                                                    )
                                                }
                                            }
                                            Text(
                                                "${obs.localDate ?: fmt.time(obs.startUtcMs)} · ${obs.provider.name}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                        Row {
                                            TextButton(onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    val ok = sl.withings.forwardSingleWeightToChild(obs, System.currentTimeMillis(), excludeFromParent = true)
                                                    onStatusMessage?.invoke(
                                                        if (ok) "Váha ${fmt.weight(obs.value)} přeposlána dítěti přes P2P!"
                                                        else "Nejprve vyberte kontakt dítěte v Nastavení → Garmin & Withings."
                                                    )
                                                    onRefreshRequested?.invoke()
                                                }
                                            }) {
                                                Text("Poslat dítěti", style = MaterialTheme.typography.labelSmall, color = Color(0xFF38BDF8), maxLines = 1, softWrap = false)
                                            }
                                            TextButton(onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    sl.withings.markWrong(obs.id, System.currentTimeMillis())
                                                    onRefreshRequested?.invoke()
                                                }
                                            }) {
                                                Text("Cizí", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), maxLines = 1, softWrap = false)
                                            }
                                        }
                                    }
                                }

                                else -> {
                                    val displayValue = if (isConvertibleBodyCompMetric(type)) {
                                        formatBodyCompValue(type, obs.value, refWeightKg, bodyUnitMode, includeSecondary = true)
                                    } else {
                                        formatMetricValue(type, obs.value)
                                    }

                                    val bandDotColor = if (usual == null) {
                                        cfg.color
                                    } else when (type) {
                                        MetricType.RESTING_HEART_RATE, MetricType.STRESS -> when {
                                            obs.value <= usual.high -> Color(0xFF10B981)
                                            else -> Color(0xFFEF4444)
                                        }
                                        else -> when {
                                            obs.value >= usual.low -> Color(0xFF10B981)
                                            else -> Color(0xFFF59E0B)
                                        }
                                    }

                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(bandDotColor)
                                            )
                                            Spacer(Modifier.width(9.dp))
                                            Column {
                                                Text(
                                                    obs.localDate ?: fmt.time(obs.startUtcMs),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color.White
                                                )
                                                if (type == MetricType.ECG) {
                                                    val det = parseEcgOrigin(obs.origin, obs.value)
                                                    val rhythm = when (det.afibClassification) {
                                                        0 -> "Sinusový rytmus"
                                                        1 -> "AFib detekována"
                                                        2 -> "Neprůkazný záznam"
                                                        else -> "Záznam EKG"
                                                    }
                                                    val hrPart = det.heartRate?.takeIf { it > 0 }?.let { " · $it bpm" } ?: ""
                                                    Text(
                                                        "$rhythm$hrPart",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = if (det.afibClassification == 1) Color(0xFFEF4444) else Color(0xFF10B981)
                                                    )
                                                } else {
                                                    Text(
                                                        obs.provider.name,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color(0xFF64748B)
                                                    )
                                                }
                                            }
                                        }

                                        Text(
                                            text = displayValue,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                                            ),
                                            fontWeight = FontWeight.ExtraBold,
                                            color = cfg.color
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isLoadingMore) {
                        item(key = "loading_more_footer") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                    color = cfg.color
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Načítání starších záznamů…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
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
}

private data class DedicatedBundle(
    val today: LocalDate,
    val zone: ZoneId,
    val mainSeries: MetricSeries,
    val latestObs: app.pulse4.domain.MetricObservation?,
    val sleepScoreSeries: MetricSeries?,
    val bodyFatSeries: MetricSeries?,
    val sleepScoresByDate: Map<String, Double>,
    val suspectWeights: List<app.pulse4.domain.MetricObservation> = emptyList(),
    val refWeightKg: Double = 93.10,
    val latestMuscleKg: Double? = null,
    val latestFatPct: Double? = null,
    val latestBoneKg: Double? = null,
)

@Composable
private fun StatBox(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(top = 2.dp))
    }
}

private data class MultiMetricEntry(
    val type: MetricType,
    val series: MetricSeries,
    val latestObs: app.pulse4.domain.MetricObservation?,
)

private data class MetricsScreenBundle(
    val entries: List<MultiMetricEntry>,
    val refWeightKg: Double,
    val weightsByDate: Map<String, Double>,
    val latestMuscleKg: Double?,
    val latestFatPct: Double?,
    val latestBoneKg: Double?,
)

fun getMetricArtworkRes(type: MetricType): Int = when (type) {
    MetricType.HRV -> R.drawable.img_motif_hrv_autonomic
    MetricType.RESTING_HEART_RATE, MetricType.HEART_RATE -> R.drawable.img_motif_heart_real
    MetricType.SLEEP_DURATION, MetricType.SLEEP_SCORE -> R.drawable.img_motif_sleep_night
    MetricType.BODY_BATTERY -> R.drawable.img_motif_body_battery
    MetricType.STRESS -> R.drawable.img_motif_brain_stress
    MetricType.STEPS -> R.drawable.img_motif_steps_trail
    MetricType.WEIGHT, MetricType.MUSCLE_MASS, MetricType.FAT_FREE_MASS, MetricType.BMR, MetricType.METABOLIC_AGE -> R.drawable.img_motif_body_silhouette
    MetricType.BODY_FAT, MetricType.VISCERAL_FAT -> R.drawable.img_motif_body_fat
    MetricType.BODY_WATER -> R.drawable.img_motif_body_water
    MetricType.BONE_MASS -> R.drawable.img_motif_bone_mass
    MetricType.ECG, MetricType.VASCULAR_AGE, MetricType.PULSE_WAVE_VELOCITY -> R.drawable.img_motif_ecg_signal
    MetricType.SPO2, MetricType.RESPIRATION -> R.drawable.img_nordic_wellness_hero
}

fun getMetricMotifKind(type: MetricType): MetricMotifKind = when (type) {
    MetricType.HRV -> MetricMotifKind.HRV_AUTONOMIC
    MetricType.RESTING_HEART_RATE, MetricType.HEART_RATE -> MetricMotifKind.HEART_PULSE
    MetricType.SLEEP_DURATION, MetricType.SLEEP_SCORE -> MetricMotifKind.SLEEP_NOCTURNAL
    MetricType.BODY_BATTERY -> MetricMotifKind.RECOVERY_SUNRISE
    MetricType.STRESS -> MetricMotifKind.AUTONOMIC_STRESS
    MetricType.STEPS -> MetricMotifKind.STEPS_TRAIL
    MetricType.WEIGHT, MetricType.MUSCLE_MASS, MetricType.FAT_FREE_MASS, MetricType.BMR, MetricType.METABOLIC_AGE -> MetricMotifKind.BODY_COMPOSITION
    MetricType.BODY_FAT, MetricType.VISCERAL_FAT -> MetricMotifKind.BODY_FAT
    MetricType.BODY_WATER -> MetricMotifKind.BODY_WATER
    MetricType.BONE_MASS -> MetricMotifKind.BONE_MASS
    MetricType.ECG, MetricType.VASCULAR_AGE, MetricType.PULSE_WAVE_VELOCITY -> MetricMotifKind.ECG_SIGNAL
    MetricType.SPO2, MetricType.RESPIRATION -> MetricMotifKind.HRV_AUTONOMIC
}

private data class CorrelationPresetCardDef(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val types: LinkedHashSet<MetricType>,
    val artworkRes: Int,
    val accentColor: Color,
    val secondaryColor: Color,
)

/**
 * Obrazovka Metriky (záložka Metriky):
 * Ambiciózní Biometrická Observatoř s fotografickými světy, procedurálními vrstvami,
 * vizuálními kartami korelačních předvoleb a chronografickými ukazateli.
 */
@Composable
fun MetricsScreen(sl: ServiceLocator, initialType: MetricType = MetricType.HRV) {
    var selectedTypes by remember(initialType) { mutableStateOf(linkedSetOf(initialType)) }
    var normalizeMultiChart by remember { mutableStateOf(false) }
    var bodyUnitMode by remember { mutableStateOf(BodyUnitMode.KG) }
    var showSeparateCards by remember { mutableStateOf(true) }
    var multiInfoDialogType by remember { mutableStateOf<MetricType?>(null) }
    var daysCount by remember { mutableStateOf(30) }
    val units by sl.settings.unitSystemFlow.collectAsState()

    var metricsMenuExpanded by remember { mutableStateOf(false) }
    var presetsMenuExpanded by remember { mutableStateOf(false) }

    val allAvailableTypes = listOf(
        MetricType.HRV,
        MetricType.RESTING_HEART_RATE,
        MetricType.SLEEP_DURATION,
        MetricType.BODY_BATTERY,
        MetricType.STRESS,
        MetricType.STEPS,
        MetricType.WEIGHT,
        MetricType.BODY_FAT,
        MetricType.MUSCLE_MASS,
        MetricType.BODY_WATER,
        MetricType.BONE_MASS,
        MetricType.FAT_FREE_MASS,
        MetricType.VISCERAL_FAT,
        MetricType.VASCULAR_AGE,
        MetricType.PULSE_WAVE_VELOCITY,
        MetricType.BMR,
        MetricType.METABOLIC_AGE,
        MetricType.ECG,
        MetricType.SPO2,
        MetricType.RESPIRATION
    )

    val correlationPresets = remember {
        listOf(
            CorrelationPresetCardDef(
                title = "Regenerace",
                subtitle = "HRV · Klidový tep",
                icon = PulseIcons.Hrv,
                types = linkedSetOf(MetricType.HRV, MetricType.RESTING_HEART_RATE),
                artworkRes = R.drawable.img_nordic_wellness_hero,
                accentColor = Color(0xFF2DD4BF),
                secondaryColor = Color(0xFFFB7185)
            ),
            CorrelationPresetCardDef(
                title = "Noční obnova",
                subtitle = "Spánek · HRV · B.Battery",
                icon = PulseIcons.Sleep,
                types = linkedSetOf(MetricType.SLEEP_DURATION, MetricType.HRV, MetricType.BODY_BATTERY),
                artworkRes = R.drawable.img_motif_sleep_night,
                accentColor = Color(0xFF818CF8),
                secondaryColor = Color(0xFF2DD4BF)
            ),
            CorrelationPresetCardDef(
                title = "Složení těla",
                subtitle = "Váha · Svaly · Tuk",
                icon = PulseIcons.BodyComposition,
                types = linkedSetOf(MetricType.WEIGHT, MetricType.MUSCLE_MASS, MetricType.BODY_FAT),
                artworkRes = R.drawable.img_motif_body_silhouette,
                accentColor = Color(0xFF10B981),
                secondaryColor = Color(0xFFF59E0B)
            ),
            CorrelationPresetCardDef(
                title = "Srdce & EKG",
                subtitle = "Klidový tep · EKG QTc",
                icon = PulseIcons.Ecg,
                types = linkedSetOf(MetricType.RESTING_HEART_RATE, MetricType.ECG),
                artworkRes = R.drawable.img_motif_ecg_signal,
                accentColor = Color(0xFFF43F5E),
                secondaryColor = Color(0xFF38BDF8)
            ),
            CorrelationPresetCardDef(
                title = "Energie & Stres",
                subtitle = "Body Battery · Stres",
                icon = PulseIcons.BodyBattery,
                types = linkedSetOf(MetricType.BODY_BATTERY, MetricType.STRESS),
                artworkRes = R.drawable.img_motif_body_battery,
                accentColor = Color(0xFFF59E0B),
                secondaryColor = Color(0xFFA855F7)
            ),
            CorrelationPresetCardDef(
                title = "Hydratace & Kosti",
                subtitle = "Voda v těle · Kostní hmota",
                icon = PulseIcons.BodyWater,
                types = linkedSetOf(MetricType.BODY_WATER, MetricType.BONE_MASS),
                artworkRes = R.drawable.img_motif_body_water,
                accentColor = Color(0xFF06B6D4),
                secondaryColor = Color(0xFF38BDF8)
            ),
            CorrelationPresetCardDef(
                title = "Váha & Pohyb",
                subtitle = "Hmotnost · Denní kroky",
                icon = PulseIcons.Steps,
                types = linkedSetOf(MetricType.WEIGHT, MetricType.STEPS),
                artworkRes = R.drawable.img_alpine_trail_hero,
                accentColor = Color(0xFF38BDF8),
                secondaryColor = Color(0xFFF59E0B)
            )
        )
    }

    val multiBundle by io(Triple(selectedTypes.toList(), daysCount, units)) {
        val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
        val rangeObs = sl.db.observations()
            .forRange(today.minusDays((daysCount - 1).toLong()).toString(), today.toString())
            .map { it.toDomain() }

        val weightRows = runCatching { sl.withings.trend(MetricType.WEIGHT, daysCount) }.getOrDefault(emptyList())
            .ifEmpty { rangeObs.filter { it.type == MetricType.WEIGHT } }
        val latestWeightObs = weightRows.filter { !it.deleted }.maxByOrNull { it.startUtcMs }
            ?: sl.db.observations().latest(MetricType.WEIGHT.name)?.toDomain()
        val refWeightKg = latestWeightKgOrFallback(latestWeightObs?.value)
        val weightsByDate = weightRows.filter { !it.deleted && it.value in 25.0..250.0 }
            .associate { (it.localDate ?: "") to it.value }

        val muscleRows = runCatching { sl.withings.trend(MetricType.MUSCLE_MASS, daysCount) }.getOrDefault(emptyList())
        val fatRows = runCatching { sl.withings.trend(MetricType.BODY_FAT, daysCount) }.getOrDefault(emptyList())
        val boneRows = runCatching { sl.withings.trend(MetricType.BONE_MASS, daysCount) }.getOrDefault(emptyList())

        val latestMuscleKg = muscleRows.filter { !it.deleted }.maxByOrNull { it.startUtcMs }?.value
        val latestFatPct = fatRows.filter { !it.deleted }.maxByOrNull { it.startUtcMs }?.value
        val latestBoneKg = boneRows.filter { !it.deleted }.maxByOrNull { it.startUtcMs }?.value

        val entries = selectedTypes.map { t ->
            val rows = if (t in WithingsService.BODY_COMPOSITION_TYPES) {
                runCatching { sl.withings.trend(t, daysCount) }.getOrDefault(emptyList())
            } else {
                rangeObs
            }
            val s = MetricSeries.build(t, rows, today, daysCount)
            val latest = rows.filter { it.type == t && !it.deleted }.maxByOrNull { it.startUtcMs }
            MultiMetricEntry(t, s, latest)
        }

        MetricsScreenBundle(
            entries = entries,
            refWeightKg = refWeightKg,
            weightsByDate = weightsByDate,
            latestMuscleKg = latestMuscleKg,
            latestFatPct = latestFatPct,
            latestBoneKg = latestBoneKg
        )
    }

    val primarySelectedType = selectedTypes.firstOrNull() ?: MetricType.HRV
    val primaryCfg = getMetricUiConfig(primarySelectedType)
    val matchingPreset = correlationPresets.firstOrNull { it.types == selectedTypes }
    val heroArtworkRes = matchingPreset?.artworkRes ?: getMetricArtworkRes(primarySelectedType)
    val heroAccentColor = matchingPreset?.accentColor ?: primaryCfg.color
    val heroMotifKind = getMetricMotifKind(primarySelectedType)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // =========================================================================
        // 1. AMBICIÓZNÍ HERO OBSERVATOŘ METRIK & KORELACÍ (FOTOGRAFIE + PROCEDURÁLNÍ VRSTVY)
        // =========================================================================
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF060B14)),
            border = BorderStroke(
                1.2.dp,
                Brush.linearGradient(
                    listOf(
                        heroAccentColor.copy(alpha = 0.65f),
                        Color.White.copy(alpha = 0.22f),
                        heroAccentColor.copy(alpha = 0.30f)
                    )
                )
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Box(Modifier.fillMaxWidth()) {
                // Vrstva 1: Živá fotografická scéna reagující na vybranou metriku či korelaci
                androidx.compose.animation.Crossfade(
                    targetState = heroArtworkRes,
                    label = "metricsHeroPhoto",
                    modifier = Modifier.matchParentSize()
                ) { artRes ->
                    Image(
                        painter = painterResource(id = artRes),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.84f)
                    )
                }

                // Vrstva 2: Sochařský atmosférický závoj + procedurální biometrické siločáry
                Canvas(Modifier.matchParentSize()) {
                    val w = size.width
                    val h = size.height
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to Color(0xCC040811),
                                0.38f to Color(0x66040811),
                                0.72f to Color(0xAA040811),
                                1.00f to Color(0xF2040811)
                            )
                        )
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                heroAccentColor.copy(alpha = 0.28f),
                                heroAccentColor.copy(alpha = 0.06f),
                                Color.Transparent
                            ),
                            center = Offset(w * 0.82f, h * 0.26f),
                            radius = w * 0.65f
                        ),
                        center = Offset(w * 0.82f, h * 0.26f),
                        radius = w * 0.65f
                    )
                    // Jemné chronografické oběžné dráhy na pozadí
                    listOf(0.24f, 0.38f, 0.54f).forEachIndexed { idx, rFrac ->
                        drawCircle(
                            color = heroAccentColor.copy(alpha = 0.14f - idx * 0.03f),
                            radius = w * rFrac,
                            center = Offset(w * 0.84f, h * 0.24f),
                            style = Stroke(width = 1.2.dp.toPx())
                        )
                    }
                    drawMetricMotifIllustration(
                        motifKind = heroMotifKind,
                        primaryColor = heroAccentColor,
                        secondaryColor = Color.White,
                        alphaMultiplier = 0.90f
                    )
                }

                Column(Modifier.padding(16.dp)) {
                    // Horní lišta s titulkem a přepínačem období (7D / 14D / 30D / 90D / 1R)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(heroAccentColor)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "BIOMETRICKÁ OBSERVATOŘ · GARMIN & WITHINGS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = heroAccentColor,
                                    letterSpacing = 1.2.sp,
                                    fontSize = 9.5.sp
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = when {
                                    matchingPreset != null -> matchingPreset.title
                                    selectedTypes.size > 1 -> "Korelace ${selectedTypes.size} metrik"
                                    else -> primaryCfg.title
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                text = when {
                                    matchingPreset != null -> "Vícerozměrná korelace: ${matchingPreset.subtitle}"
                                    selectedTypes.size > 1 -> selectedTypes.joinToString(" · ") { getMetricUiConfig(it).shortLabel }
                                    else -> primaryCfg.explanation.take(96) + if (primaryCfg.explanation.length > 96) "…" else ""
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE2E8F0),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // FOTOGRAFICKÝ KARUSEL KORELAČNÍCH SCÉN (7 připravených kombinací)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "KORELAČNÍ SCÉNY (VÍCE METRIK NAJEDNOU)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFCBD5E1),
                            letterSpacing = 0.9.sp,
                            fontSize = 9.5.sp
                        )
                        if (selectedTypes.size > 1) {
                            Text(
                                text = "Zrušit korelaci ✕",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedTypes = linkedSetOf(selectedTypes.first()) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        correlationPresets.forEach { preset ->
                            val isSelected = selectedTypes == preset.types
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF070D17)),
                                border = BorderStroke(
                                    width = if (isSelected) 1.6.dp else 1.dp,
                                    brush = Brush.linearGradient(
                                        if (isSelected) {
                                            listOf(preset.accentColor, Color.White.copy(alpha = 0.7f), preset.secondaryColor)
                                        } else {
                                            listOf(
                                                preset.accentColor.copy(alpha = 0.40f),
                                                Color.White.copy(alpha = 0.12f)
                                            )
                                        }
                                    )
                                ),
                                modifier = Modifier
                                    .width(152.dp)
                                    .height(86.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable {
                                        selectedTypes = LinkedHashSet(preset.types)
                                    }
                            ) {
                                Box(Modifier.fillMaxSize()) {
                                    Image(
                                        painter = painterResource(id = preset.artworkRes),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .matchParentSize()
                                            .alpha(if (isSelected) 0.88f else 0.72f)
                                    )
                                    Canvas(Modifier.matchParentSize()) {
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                listOf(
                                                    Color(0x66040811),
                                                    Color(0xCC040811)
                                                )
                                            )
                                        )
                                        if (isSelected) {
                                            drawCircle(
                                                brush = Brush.radialGradient(
                                                    colors = listOf(
                                                        preset.accentColor.copy(alpha = 0.35f),
                                                        Color.Transparent
                                                    )
                                                ),
                                                radius = size.width * 0.65f,
                                                center = Offset(size.width * 0.8f, size.height * 0.25f)
                                            )
                                        }
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Dvojitý svítící prstenec korelace
                                            Box(
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xB3060B14)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Canvas(Modifier.fillMaxSize()) {
                                                    drawArc(
                                                        color = preset.accentColor,
                                                        startAngle = -90f,
                                                        sweepAngle = 180f,
                                                        useCenter = false,
                                                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                                                    )
                                                    drawArc(
                                                        color = preset.secondaryColor,
                                                        startAngle = 90f,
                                                        sweepAngle = 180f,
                                                        useCenter = false,
                                                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                                                    )
                                                }
                                                Icon(
                                                    imageVector = preset.icon,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }

                                            if (isSelected) {
                                                Text(
                                                    "AKTIVNÍ",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = preset.accentColor,
                                                    fontSize = 8.5.sp,
                                                    modifier = Modifier
                                                        .background(Color(0xCC050912), RoundedCornerShape(6.dp))
                                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
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
                                                color = Color(0xFFE2E8F0),
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

                    Spacer(Modifier.height(12.dp))

                    // FOTOGRAFICKÝ PÁS JEDNOTLIVÝCH METRIK + TLAČÍTKO VLASTNÍHO MULTI-VÝBĚRU
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "JEDNOTLIVÉ METRIKY (KLEPNUTÍM VYBERETE)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFCBD5E1),
                            letterSpacing = 0.9.sp,
                            fontSize = 9.5.sp
                        )
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xCC0B1424))
                                    .clickable { metricsMenuExpanded = true }
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Tune,
                                    contentDescription = null,
                                    tint = heroAccentColor,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "Multi-výběr (${selectedTypes.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            DropdownMenu(
                                expanded = metricsMenuExpanded,
                                onDismissRequest = { metricsMenuExpanded = false }
                            ) {
                                allAvailableTypes.forEach { t ->
                                    val c = getMetricUiConfig(t)
                                    val isChecked = t in selectedTypes
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(c.icon, contentDescription = null, tint = c.color, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(c.title, style = MaterialTheme.typography.bodyMedium)
                                                if (c.unit.isNotBlank()) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("(${c.unit})", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                                }
                                            }
                                        },
                                        leadingIcon = {
                                            Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        onClick = {
                                            if (isChecked) {
                                                if (selectedTypes.size > 1) {
                                                    val next = LinkedHashSet(selectedTypes)
                                                    next.remove(t)
                                                    selectedTypes = next
                                                }
                                            } else {
                                                val next = LinkedHashSet(selectedTypes)
                                                next.add(t)
                                                selectedTypes = next
                                            }
                                        }
                                    )
                                }
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF232A3B)))
                                DropdownMenuItem(
                                    text = { Text("Hotovo", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold) },
                                    leadingIcon = { Icon(Icons.Outlined.Check, contentDescription = null, tint = Color(0xFF38BDF8)) },
                                    onClick = { metricsMenuExpanded = false }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Horizontální fotografické pilulky pro 1-tap přepnutí jedné metriky
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        allAvailableTypes.forEach { t ->
                            val c = getMetricUiConfig(t)
                            val isSelected = t in selectedTypes
                            val art = getMetricArtworkRes(t)
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF070D17)),
                                border = BorderStroke(
                                    width = if (isSelected) 1.4.dp else 1.dp,
                                    color = if (isSelected) c.color else Color.White.copy(alpha = 0.14f)
                                ),
                                modifier = Modifier
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        selectedTypes = linkedSetOf(t)
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Image(
                                        painter = painterResource(id = art),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .matchParentSize()
                                            .alpha(if (isSelected) 0.80f else 0.45f)
                                    )
                                    Box(
                                        Modifier
                                            .matchParentSize()
                                            .background(
                                                if (isSelected) Color(0x99040811) else Color(0xCC040811)
                                            )
                                    )
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = c.icon,
                                            contentDescription = null,
                                            tint = c.color,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = c.shortLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                            color = if (isSelected) Color.White else Color(0xFFCBD5E1)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // SKLENĚNÁ LIŠTA ČASOVÉHO HORIZONTU (7D · 14D · 30D · 90D · 1R)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xB3050912))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            7 to "7 dní",
                            14 to "14 dní",
                            30 to "30 dní",
                            90 to "90 dní",
                            365 to "1 rok"
                        ).forEach { (d, label) ->
                            val isSel = daysCount == d
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSel) heroAccentColor.copy(alpha = 0.26f)
                                        else Color.Transparent
                                    )
                                    .clickable { daysCount = d }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSel) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = if (isSel) Color.White else Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Aktivní štítky při více vybraných metrikách
        if (selectedTypes.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Porovnávané řady:", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                selectedTypes.forEach { t ->
                    val c = getMetricUiConfig(t)
                    FilterChip(
                        selected = true,
                        onClick = {
                            if (selectedTypes.size > 1) {
                                val next = LinkedHashSet(selectedTypes)
                                next.remove(t)
                                selectedTypes = next
                            }
                        },
                        leadingIcon = {
                            Icon(c.icon, contentDescription = null, tint = c.color, modifier = Modifier.size(14.dp))
                        },
                        trailingIcon = {
                            Icon(Icons.Outlined.Close, contentDescription = "Odebrat", modifier = Modifier.size(14.dp))
                        },
                        label = { Text(c.shortLabel, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        val bundle = multiBundle
        if (bundle == null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A101D))
            ) {
                Text("Načítání biometrických dat…", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (bundle.entries.size >= 2) {
            val entries = bundle.entries
            val hasBodyComp = selectedTypes.any { isConvertibleBodyCompMetric(it) || it == MetricType.WEIGHT }
            val datesLabels = entries.firstOrNull()?.series?.dates?.map { it.toString().takeLast(5) }.orEmpty()

            // Hlavní přepínač měřítka a jednotek pro korelační porovnání
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !normalizeMultiChart,
                    onClick = {
                        normalizeMultiChart = false
                        bodyUnitMode = BodyUnitMode.KG
                    },
                    leadingIcon = { Icon(PulseIcons.UnitPhysical, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Fyzikální (${fmt.weightUnit()}, bpm)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = normalizeMultiChart,
                    onClick = {
                        normalizeMultiChart = true
                        bodyUnitMode = BodyUnitMode.PERCENT
                    },
                    leadingIcon = { Icon(PulseIcons.UnitPercent, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Relativní % (0–100)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f)
                )
            }

            // REŽIM VÍCE VYBRANÝCH METRIK NAJEDNOU (Korelační porovnání s fotografickým pozadím)
            val multiSeries = entries.map { entry ->
                val c = getMetricUiConfig(entry.type)
                val rawConverted = when (entry.type) {
                    MetricType.SLEEP_DURATION -> entry.series.values.map { it?.div(3600.0) }
                    MetricType.WEIGHT -> entry.series.values.map { it?.let(fmt::convertWeight) }
                    MetricType.MUSCLE_MASS, MetricType.BONE_MASS, MetricType.FAT_FREE_MASS -> {
                        if (bodyUnitMode == BodyUnitMode.PERCENT && !normalizeMultiChart) {
                            entry.series.values.map { v -> v?.let { (it / bundle.refWeightKg) * 100.0 } }
                        } else {
                            entry.series.values.map { it?.let(fmt::convertWeight) }
                        }
                    }
                    MetricType.BODY_FAT -> {
                        if (!normalizeMultiChart && bodyUnitMode == BodyUnitMode.KG) {
                            entry.series.dates.zip(entry.series.values).map { (d, v) ->
                                if (v != null) {
                                    val w = bundle.weightsByDate[d.toString()] ?: bundle.refWeightKg
                                    fmt.convertWeight(w * (v / 100.0))
                                } else null
                            }
                        } else {
                            entry.series.values
                        }
                    }
                    MetricType.BODY_WATER -> {
                        if (!normalizeMultiChart && bodyUnitMode == BodyUnitMode.KG) {
                            entry.series.dates.zip(entry.series.values).map { (d, v) ->
                                if (v != null) {
                                    val w = bundle.weightsByDate[d.toString()] ?: bundle.refWeightKg
                                    fmt.convertWeight(w * (v / 100.0))
                                } else null
                            }
                        } else {
                            entry.series.values
                        }
                    }
                    else -> entry.series.values
                }
                val finite = rawConverted.filterNotNull()
                val minV = finite.minOrNull() ?: 0.0
                val maxV = finite.maxOrNull() ?: 1.0
                val span = (maxV - minV).takeIf { it > 1e-6 } ?: 1.0
                val displayVals = if (normalizeMultiChart && finite.size >= 2) {
                    rawConverted.map { v -> v?.let { ((it - minV) / span) * 100.0 } }
                } else {
                    rawConverted
                }
                val seriesUnit = if (normalizeMultiChart) "%"
                else when (entry.type) {
                    MetricType.BODY_FAT, MetricType.BODY_WATER -> if (bodyUnitMode == BodyUnitMode.KG) fmt.weightUnit() else "%"
                    MetricType.MUSCLE_MASS, MetricType.BONE_MASS, MetricType.FAT_FREE_MASS -> if (bodyUnitMode == BodyUnitMode.KG) fmt.weightUnit() else "%"
                    MetricType.WEIGHT -> fmt.weightUnit()
                    MetricType.SLEEP_DURATION -> "h"
                    else -> c.unit
                }
                SeriesItem(
                    id = entry.type.name,
                    label = c.shortLabel,
                    color = c.color,
                    values = displayVals,
                    unit = seriesUnit
                )
            }

            MultiSeriesLineChart(
                series = multiSeries,
                xLabels = datesLabels,
                artworkRes = heroArtworkRes,
                motifKind = heroMotifKind,
                title = if (normalizeMultiChart) {
                    "Korelační překryv (${entries.size} metriky · 0–100 %)"
                } else {
                    "Společný korelační graf ($daysCount dní)"
                }
            )

            if (hasBodyComp) {
                Spacer(Modifier.height(8.dp))
                BodyCompositionStackedBar(
                    weightKg = bundle.refWeightKg,
                    muscleKg = bundle.latestMuscleKg,
                    fatPct = bundle.latestFatPct,
                    boneKg = bundle.latestBoneKg,
                    unitMode = bodyUnitMode,
                    onUnitModeChange = { mode ->
                        bodyUnitMode = mode
                        if (mode == BodyUnitMode.KG) normalizeMultiChart = false
                    }
                )

                Spacer(Modifier.height(8.dp))
                SegmentalBodyCompositionSection(
                    sl = sl,
                    initialModeFat = selectedTypes.contains(MetricType.BODY_FAT) && !selectedTypes.contains(MetricType.MUSCLE_MASS),
                    daysCount = daysCount,
                    externalUnitMode = bodyUnitMode,
                    onUnitModeChange = { mode ->
                        bodyUnitMode = mode
                        if (mode == BodyUnitMode.KG) normalizeMultiChart = false
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle("Detailní karty korelovaných metrik (${entries.size})", icon = Icons.Outlined.FormatListBulleted)

            val wEntry = entries.firstOrNull { it.type == MetricType.WEIGHT }
            val mEntry = entries.firstOrNull { it.type == MetricType.MUSCLE_MASS }
            val fEntry = entries.firstOrNull { it.type == MetricType.BODY_FAT }
            val isBodyCompTriple = wEntry != null && mEntry != null && fEntry != null

            if (isBodyCompTriple) {
                val fatKgValues = fEntry!!.series.dates.zip(fEntry.series.values).map { (d, f) ->
                    if (f != null) {
                        val w = bundle.weightsByDate[d.toString()] ?: bundle.refWeightKg
                        fmt.convertWeight(w * (f / 100.0))
                    } else null
                }

                Card(
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(
                        1.dp,
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF2DD4BF).copy(alpha = 0.55f),
                                Color(0xFFF59E0B).copy(alpha = 0.35f)
                            )
                        )
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF070C15)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = R.drawable.img_motif_body_silhouette),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0.68f)
                        )
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(Color(0xCC060B14), Color(0xB3060B14), Color(0xE6050911))
                                )
                            )
                        }
                        Column(Modifier.padding(15.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f, fill = false),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(PulseIcons.BodyComposition, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "SKLÁDANÉ SLOŽENÍ HMOTNOSTI (VÁHA + SVALY + TUK)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2DD4BF),
                                        maxLines = 1
                                    )
                                }
                                Text(
                                    "${daysCount} dní · ${if (bodyUnitMode == BodyUnitMode.KG) fmt.weightUnit() else "%"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFCBD5E1)
                                )
                            }

                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Celková váha", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1))
                                    Text(fmt.weight(bundle.refWeightKg), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                }
                                Column {
                                    Text("Svalová hmota", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1))
                                    val lMuscle = bundle.latestMuscleKg
                                    val mTxt = if (lMuscle == null) "—"
                                    else if (bodyUnitMode == BodyUnitMode.KG) fmt.weight(lMuscle)
                                    else "${fmt.number((lMuscle / bundle.refWeightKg) * 100.0, 1)} %"
                                    Text(mTxt, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF10B981))
                                }
                                Column {
                                    Text("Tělesný tuk", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1))
                                    val lFat = bundle.latestFatPct
                                    val fTxt = if (lFat == null) "—"
                                    else if (bodyUnitMode == BodyUnitMode.KG) {
                                        val fKg = bundle.refWeightKg * (lFat / 100.0)
                                        "${fmt.weight(fKg)} (${fmt.number(lFat, 1)} %)"
                                    } else {
                                        "${fmt.number(lFat, 1)} %"
                                    }
                                    Text(fTxt, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFFF59E0B))
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            WeightCompositionStackedAreaChart(
                                weightValues = wEntry!!.series.values.map { it?.let(fmt::convertWeight) },
                                muscleValues = mEntry!!.series.values.map { it?.let(fmt::convertWeight) },
                                fatValues = fatKgValues,
                                unit = fmt.weightUnit(),
                                xLabels = datesLabels
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    text = if (showSeparateCards) "Skrýt samostatné karty složek ▲" else "Zobrazit samostatné karty složek (váha, svaly, tuk) ▼",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF2DD4BF),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { showSeparateCards = !showSeparateCards }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            val filteredEntries = if (isBodyCompTriple && !showSeparateCards) {
                entries.filterNot { it.type in setOf(MetricType.WEIGHT, MetricType.MUSCLE_MASS, MetricType.BODY_FAT) }
            } else {
                entries
            }

            filteredEntries.forEach { entry ->
                val c = getMetricUiConfig(entry.type)
                val entryArt = getMetricArtworkRes(entry.type)
                val entryMotif = getMetricMotifKind(entry.type)
                val latestVal = entry.latestObs?.value
                val usual = entry.series.usual
                val isBodyComp = isConvertibleBodyCompMetric(entry.type) || entry.type == MetricType.WEIGHT
                val showKg = (!normalizeMultiChart || bodyUnitMode == BodyUnitMode.KG) && isBodyComp

                val formattedValueText = if (latestVal == null) {
                    "—"
                } else if (entry.type == MetricType.BODY_FAT) {
                    val fatKg = bundle.refWeightKg * (latestVal / 100.0)
                    if (showKg) {
                        "${fmt.weight(fatKg)} (${fmt.number(latestVal, 1)} %)"
                    } else {
                        "${fmt.number(latestVal, 1)} %"
                    }
                } else if (entry.type == MetricType.BODY_WATER) {
                    val waterKg = bundle.refWeightKg * (latestVal / 100.0)
                    if (showKg) {
                        "${fmt.weight(waterKg)} (${fmt.number(latestVal, 1)} %)"
                    } else {
                        "${fmt.number(latestVal, 1)} %"
                    }
                } else if (entry.type in setOf(MetricType.MUSCLE_MASS, MetricType.FAT_FREE_MASS, MetricType.BONE_MASS)) {
                    if (!showKg) {
                        val pct = (latestVal / bundle.refWeightKg) * 100.0
                        "${fmt.number(pct, 1)} % (${fmt.weight(latestVal)})"
                    } else {
                        fmt.weight(latestVal)
                    }
                } else {
                    formatMetricValue(entry.type, latestVal)
                }

                val medianText = if (usual == null) {
                    "Sběr osobního pásma"
                } else if (entry.type == MetricType.BODY_FAT) {
                    if (showKg) {
                        val medKg = bundle.refWeightKg * (usual.median / 100.0)
                        "Medián (${daysCount}d): ${fmt.weight(medKg)} (${fmt.number(usual.median, 1)} %)"
                    } else {
                        "Medián (${daysCount}d): ${fmt.number(usual.median, 1)} %"
                    }
                } else if (entry.type in setOf(MetricType.MUSCLE_MASS, MetricType.FAT_FREE_MASS, MetricType.BONE_MASS) && !showKg) {
                    val medPct = (usual.median / bundle.refWeightKg) * 100.0
                    "Medián (${daysCount}d): ${fmt.number(medPct, 1)} %"
                } else {
                    "Medián (${daysCount}d): ${formatMetricValue(entry.type, usual.median)}"
                }

                val datesLabelsEntry = entry.series.dates.map { it.toString().takeLast(5) }
                val (chartValues, chartBand, chartUnit) = when (entry.type) {
                    MetricType.BODY_FAT -> {
                        if (showKg) {
                            val vals = entry.series.dates.zip(entry.series.values).map { (d, v) ->
                                if (v != null) {
                                    val w = bundle.weightsByDate[d.toString()] ?: bundle.refWeightKg
                                    fmt.convertWeight(w * (v / 100.0))
                                } else null
                            }
                            val band = usual?.let {
                                (fmt.convertWeight(bundle.refWeightKg * (it.low / 100.0)))..(fmt.convertWeight(bundle.refWeightKg * (it.high / 100.0)))
                            }
                            Triple(vals, band, fmt.weightUnit())
                        } else {
                            Triple(entry.series.values, usual?.let { it.low..it.high }, "%")
                        }
                    }
                    MetricType.BODY_WATER -> {
                        if (showKg) {
                            val vals = entry.series.dates.zip(entry.series.values).map { (d, v) ->
                                if (v != null) {
                                    val w = bundle.weightsByDate[d.toString()] ?: bundle.refWeightKg
                                    fmt.convertWeight(w * (v / 100.0))
                                } else null
                            }
                            val band = usual?.let {
                                (fmt.convertWeight(bundle.refWeightKg * (it.low / 100.0)))..(fmt.convertWeight(bundle.refWeightKg * (it.high / 100.0)))
                            }
                            Triple(vals, band, fmt.weightUnit())
                        } else {
                            Triple(entry.series.values, usual?.let { it.low..it.high }, "%")
                        }
                    }
                    MetricType.WEIGHT -> {
                        Triple(
                            entry.series.values.map { it?.let(fmt::convertWeight) },
                            usual?.let { fmt.convertWeight(it.low)..fmt.convertWeight(it.high) },
                            fmt.weightUnit()
                        )
                    }
                    MetricType.MUSCLE_MASS, MetricType.FAT_FREE_MASS, MetricType.BONE_MASS -> {
                        if (!showKg) {
                            val vals = entry.series.values.map { v -> v?.let { (it / bundle.refWeightKg) * 100.0 } }
                            val band = usual?.let { ((it.low / bundle.refWeightKg) * 100.0)..((it.high / bundle.refWeightKg) * 100.0) }
                            Triple(vals, band, "%")
                        } else {
                            Triple(
                                entry.series.values.map { it?.let(fmt::convertWeight) },
                                usual?.let { fmt.convertWeight(it.low)..fmt.convertWeight(it.high) },
                                fmt.weightUnit()
                            )
                        }
                    }
                    MetricType.SLEEP_DURATION -> {
                        Triple(
                            entry.series.values.map { it?.div(3600.0) },
                            usual?.let { (it.low / 3600.0)..(it.high / 3600.0) },
                            "h"
                        )
                    }
                    else -> {
                        Triple(entry.series.values, usual?.let { it.low..it.high }, c.unit)
                    }
                }

                val finite = chartValues.filterNotNull()
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(
                        1.dp,
                        Brush.horizontalGradient(
                            listOf(c.color.copy(alpha = 0.55f), Color.White.copy(alpha = 0.14f))
                        )
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF070C15))
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = entryArt),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0.76f)
                        )
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    listOf(Color(0xD9050912), Color(0xAA060B14), Color(0x44060B14))
                                )
                            )
                            drawMetricMotifIllustration(
                                motifKind = entryMotif,
                                primaryColor = c.color,
                                secondaryColor = Color.White,
                                alphaMultiplier = 0.75f
                            )
                        }
                        Column(Modifier.padding(15.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .background(c.color.copy(alpha = 0.20f), RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(c.icon, contentDescription = null, tint = c.color, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(c.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            medianText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFCBD5E1),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        formattedValueText,
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                                        ),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = c.color,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    MetricInfoIconButton(onClick = { multiInfoDialogType = entry.type })
                                }
                            }

                            if (finite.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                LineChart(
                                    values = chartValues,
                                    band = chartBand,
                                    color = c.color,
                                    unit = chartUnit,
                                    xLabels = datesLabelsEntry,
                                    artworkRes = entryArt,
                                    motifKind = entryMotif
                                )
                            }
                        }
                    }
                }
            }

            multiInfoDialogType?.let { t ->
                MetricInfoPopupDialog(type = t, cfg = getMetricUiConfig(t), onDismiss = { multiInfoDialogType = null })
            }
        } else {
            // =========================================================================
            // REŽIM JEDNÉ VYBRANÉ METRIKY (CHRONOGRAFICKÝ HERO DISPLEJ + HLUBOKÁ ANALÝZA)
            // =========================================================================
            var showInfoDialog by remember { mutableStateOf(false) }
            val single = bundle.entries.first()
            val type = single.type
            val isBodyComp = isConvertibleBodyCompMetric(type) || type == MetricType.WEIGHT
            val cfg = getMetricUiConfig(type)
            val singleArt = getMetricArtworkRes(type)
            val singleMotif = getMetricMotifKind(type)
            val series = single.series
            val latestObs = single.latestObs

            if (isConvertibleBodyCompMetric(type)) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = bodyUnitMode == BodyUnitMode.KG,
                        onClick = { bodyUnitMode = BodyUnitMode.KG },
                        leadingIcon = { Icon(Icons.Outlined.Scale, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("V kilogramech (${fmt.weightUnit()})", maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = bodyUnitMode == BodyUnitMode.PERCENT,
                        onClick = { bodyUnitMode = BodyUnitMode.PERCENT },
                        leadingIcon = { Icon(Icons.Outlined.Assessment, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("V procentech (%)", maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (!series.hasData || latestObs == null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, cfg.color.copy(alpha = 0.4f)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF070C15))
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = singleArt),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize().alpha(0.65f)
                        )
                        Box(Modifier.matchParentSize().background(Color(0xCC050912)))
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(cfg.color.copy(alpha = 0.20f), RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(cfg.icon, contentDescription = null, tint = cfg.color, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(cfg.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Pro tuto metriku zatím nejsou v zařízení žádná zaznamenaná data. Na obrazovce Dnes spusťte synchronizaci s Garmin Connect nebo klepněte na tlačítko Demo dat (▶).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }
                }
            } else {
                val latestValue = latestObs.value
                val usual = series.usual
                val latestFormatted = if (type == MetricType.BODY_FAT) {
                    if (bodyUnitMode == BodyUnitMode.KG) {
                        val fatKg = bundle.refWeightKg * (latestValue / 100.0)
                        "${fmt.weight(fatKg)} (${fmt.number(latestValue, 1)} %)"
                    } else {
                        "${fmt.number(latestValue, 1)} %"
                    }
                } else if (type in setOf(MetricType.MUSCLE_MASS, MetricType.FAT_FREE_MASS, MetricType.BONE_MASS) && bodyUnitMode == BodyUnitMode.PERCENT) {
                    val pct = (latestValue / bundle.refWeightKg) * 100.0
                    "${fmt.number(pct, 1)} % (${fmt.weight(latestValue)})"
                } else {
                    formatMetricValue(type, latestValue)
                }

                val (statusText, statusColor) = if (usual == null) {
                    "Sběr pásma (${series.values.filterNotNull().size}/7 dní)" to Color(0xFF94A3B8)
                } else when (type) {
                    MetricType.RESTING_HEART_RATE, MetricType.STRESS -> when {
                        latestValue < usual.low -> "Optimální klid" to Color(0xFF10B981)
                        latestValue > usual.high -> "Zvýšené nad normál" to Color(0xFFEF4444)
                        else -> "V osobním pásmu" to Color(0xFF10B981)
                    }
                    else -> when {
                        latestValue < usual.low -> "Pod osobním pásmem" to Color(0xFFF59E0B)
                        latestValue > usual.high -> "Nadstandardní hodnota" to Color(0xFF10B981)
                        else -> "V osobním pásmu" to Color(0xFF10B981)
                    }
                }

                val (deltaText, deltaColor) = if (usual != null) {
                    val diff = latestValue - usual.median
                    val text = when (type) {
                        MetricType.SLEEP_DURATION -> {
                            val sign = if (diff >= 0) "+" else ""
                            "$sign${(diff / 60).toLong()} min vs medián"
                        }
                        MetricType.WEIGHT -> {
                            val conv = fmt.convertWeight(diff)
                            val sign = if (conv >= 0) "+" else ""
                            "$sign${fmt.number(conv, 1)} ${fmt.weightUnit()} vs medián"
                        }
                        MetricType.BODY_FAT -> {
                            if (bodyUnitMode == BodyUnitMode.KG) {
                                val diffKg = fmt.convertWeight(bundle.refWeightKg * (diff / 100.0))
                                val sign = if (diffKg >= 0) "+" else ""
                                "$sign${fmt.number(diffKg, 1)} ${fmt.weightUnit()} vs medián"
                            } else {
                                val sign = if (diff >= 0) "+" else ""
                                "$sign${fmt.number(diff, 1)} % vs medián"
                            }
                        }
                        else -> {
                            val sign = if (diff >= 0) "+" else ""
                            "$sign${fmt.number(diff, 0)} ${cfg.unit} vs medián"
                        }
                    }
                    val col = if (type == MetricType.RESTING_HEART_RATE || type == MetricType.STRESS) {
                        if (diff <= 0) Color(0xFF10B981) else Color(0xFFEF4444)
                    } else {
                        if (diff >= 0) Color(0xFF10B981) else Color(0xFFF59E0B)
                    }
                    text to col
                } else null to Color.Transparent

                val finite = series.values.filterNotNull()
                val minV = finite.minOrNull() ?: 0.0
                val maxV = finite.maxOrNull() ?: 100.0
                val gaugeProgress = remember(latestValue, minV, maxV) {
                    val span = (maxV - minV).takeIf { it > 1e-6 } ?: 1.0
                    ((latestValue - minV) / span).toFloat().coerceIn(0.12f, 1.0f)
                }

                val medianFormatted = if (usual != null) {
                    if (type == MetricType.BODY_FAT && bodyUnitMode == BodyUnitMode.KG) {
                        val medKg = bundle.refWeightKg * (usual.median / 100.0)
                        "${fmt.weight(medKg)} (${fmt.number(usual.median, 1)} %)"
                    } else if (type in setOf(MetricType.MUSCLE_MASS, MetricType.FAT_FREE_MASS, MetricType.BONE_MASS) && bodyUnitMode == BodyUnitMode.PERCENT) {
                        "${fmt.number((usual.median / bundle.refWeightKg) * 100.0, 1)} %"
                    } else {
                        formatMetricValue(type, usual.median)
                    }
                } else "—"

                val minMaxFormatted = if (finite.isNotEmpty()) {
                    if (type == MetricType.BODY_FAT && bodyUnitMode == BodyUnitMode.KG) {
                        "${fmt.weight(bundle.refWeightKg * (minV / 100.0))} – ${fmt.weight(bundle.refWeightKg * (maxV / 100.0))}"
                    } else {
                        "${formatMetricValue(type, minV)} – ${formatMetricValue(type, maxV)}"
                    }
                } else "—"

                val usualFormatted = if (usual != null) {
                    if (type == MetricType.BODY_FAT && bodyUnitMode == BodyUnitMode.KG) {
                        "${fmt.weight(bundle.refWeightKg * (usual.low / 100.0))} – ${fmt.weight(bundle.refWeightKg * (usual.high / 100.0))}"
                    } else {
                        "${formatMetricValue(type, usual.low)} – ${formatMetricValue(type, usual.high)}"
                    }
                } else "Méně než 7 dní"

                // AMBICIÓZNÍ FOTOGRAFICKÁ A CHRONOGRAFICKÁ KARTA METRIKY (2v1: Hero + 4 Statistické Dlaždice)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF060B14)),
                    border = BorderStroke(
                        1.2.dp,
                        Brush.linearGradient(
                            listOf(
                                cfg.color.copy(alpha = 0.65f),
                                Color.White.copy(alpha = 0.18f),
                                cfg.color.copy(alpha = 0.30f)
                            )
                        )
                    )
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        // Fotografické pozadí s vysokou viditelností
                        Image(
                            painter = painterResource(id = singleArt),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0.82f)
                        )
                        // Sochařský závoj a procedurální kresba
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color(0xCC040811),
                                        0.45f to Color(0x80040811),
                                        1.0f to Color(0xE6040811)
                                    )
                                )
                            )
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        cfg.color.copy(alpha = 0.28f),
                                        cfg.color.copy(alpha = 0.04f),
                                        Color.Transparent
                                    ),
                                    center = Offset(size.width * 0.80f, size.height * 0.30f),
                                    radius = size.width * 0.55f
                                ),
                                center = Offset(size.width * 0.80f, size.height * 0.30f),
                                radius = size.width * 0.55f
                            )
                            drawMetricMotifIllustration(
                                motifKind = singleMotif,
                                primaryColor = cfg.color,
                                secondaryColor = Color.White,
                                alphaMultiplier = 0.85f
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
                                            .size(38.dp)
                                            .background(cfg.color.copy(alpha = 0.22f), RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(cfg.icon, contentDescription = null, tint = cfg.color, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(cfg.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                        Text("Měření z ${fmt.time(latestObs.startUtcMs)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1))
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = statusColor,
                                        modifier = Modifier
                                            .background(Color(0xCC050912), RoundedCornerShape(12.dp))
                                            .padding(horizontal = 9.dp, vertical = 4.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    MetricInfoIconButton(onClick = { showInfoDialog = true })
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            // Hlavní hodnota + Chronografický 48-tick prstenec pozice v pásmu
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = latestFormatted,
                                        style = MaterialTheme.typography.displaySmall.copy(
                                            fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily,
                                            letterSpacing = (-1).sp
                                        ),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    if (deltaText != null) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = deltaText,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = deltaColor,
                                            modifier = Modifier
                                                .background(Color(0xB3050912), RoundedCornerShape(8.dp))
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                // Chronografický prstenec umístění v osobním rozpětí
                                Box(
                                    modifier = Modifier.size(84.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        val c = Offset(size.width / 2f, size.height / 2f)
                                        val r = size.minDimension / 2f - 6.dp.toPx()
                                        val tickCount = 36
                                        for (i in 0 until tickCount) {
                                            val angleDeg = i * (360f / tickCount) - 90f
                                            val rad = angleDeg * (Math.PI / 180.0)
                                            val isLit = (i.toFloat() / tickCount) <= gaugeProgress
                                            val inner = r - 4.dp.toPx()
                                            val outer = r + (if (i % 3 == 0) 2.dp.toPx() else 0.dp.toPx())
                                            drawLine(
                                                color = if (isLit) cfg.color.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f),
                                                start = Offset(c.x + inner * kotlin.math.cos(rad).toFloat(), c.y + inner * kotlin.math.sin(rad).toFloat()),
                                                end = Offset(c.x + outer * kotlin.math.cos(rad).toFloat(), c.y + outer * kotlin.math.sin(rad).toFloat()),
                                                strokeWidth = if (i % 3 == 0) 2.dp.toPx() else 1.2.dp.toPx(),
                                                cap = StrokeCap.Round
                                            )
                                        }
                                        drawArc(
                                            brush = Brush.sweepGradient(
                                                listOf(cfg.color.copy(alpha = 0.3f), cfg.color, Color.White)
                                            ),
                                            startAngle = -90f,
                                            sweepAngle = 360f * gaugeProgress,
                                            useCenter = false,
                                            topLeft = Offset(c.x - (r - 8.dp.toPx()), c.y - (r - 8.dp.toPx())),
                                            size = androidx.compose.ui.geometry.Size((r - 8.dp.toPx()) * 2f, (r - 8.dp.toPx()) * 2f),
                                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "${(gaugeProgress * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                                            ),
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White
                                        )
                                        Text(
                                            "pásmo",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFCBD5E1),
                                            fontSize = 8.5.sp
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            // 4 Skleněné statistické dlaždice přímo uvnitř fotografické observatoře
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xB3050912))
                                        .padding(10.dp)
                                ) {
                                    StatBox(title = "Medián (${daysCount}d)", value = medianFormatted)
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xB3050912))
                                        .padding(10.dp)
                                ) {
                                    StatBox(title = "Osobní pásmo P25–P75", value = usualFormatted)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xB3050912))
                                        .padding(10.dp)
                                ) {
                                    StatBox(title = "Rozpětí Min – Max", value = minMaxFormatted)
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xB3050912))
                                        .padding(10.dp)
                                ) {
                                    StatBox(title = "Počet měření (${daysCount}d)", value = "${finite.size} záznamů")
                                }
                            }
                        }
                    }
                }

                SectionTitle("Historický průběh (${daysCount} dní)", icon = Icons.AutoMirrored.Outlined.ShowChart)
                val isWeightType = type in listOf(MetricType.WEIGHT, MetricType.MUSCLE_MASS, MetricType.BONE_MASS, MetricType.FAT_FREE_MASS)
                val chartValues = if (type == MetricType.BODY_FAT) {
                    if (bodyUnitMode == BodyUnitMode.KG) {
                        series.dates.zip(series.values).map { (d, v) ->
                            if (v != null) {
                                val w = bundle.weightsByDate[d.toString()] ?: bundle.refWeightKg
                                fmt.convertWeight(w * (v / 100.0))
                            } else null
                        }
                    } else {
                        series.values
                    }
                } else if (isWeightType) {
                    if (bodyUnitMode == BodyUnitMode.PERCENT && type != MetricType.WEIGHT) {
                        series.values.map { it?.let { w -> (w / bundle.refWeightKg) * 100.0 } }
                    } else {
                        series.values.map { it?.let(fmt::convertWeight) }
                    }
                } else if (type == MetricType.SLEEP_DURATION) {
                    series.values.map { it?.div(3600.0) }
                } else series.values

                val chartBand = if (type == MetricType.BODY_FAT) {
                    if (bodyUnitMode == BodyUnitMode.KG) {
                        usual?.let { (fmt.convertWeight(bundle.refWeightKg * (it.low / 100.0)))..(fmt.convertWeight(bundle.refWeightKg * (it.high / 100.0))) }
                    } else {
                        usual?.let { it.low..it.high }
                    }
                } else if (isWeightType) {
                    if (bodyUnitMode == BodyUnitMode.PERCENT && type != MetricType.WEIGHT) {
                        usual?.let { ((it.low / bundle.refWeightKg) * 100.0)..((it.high / bundle.refWeightKg) * 100.0) }
                    } else {
                        usual?.let { fmt.convertWeight(it.low)..fmt.convertWeight(it.high) }
                    }
                } else if (type == MetricType.SLEEP_DURATION) {
                    usual?.let { (it.low / 3600.0)..(it.high / 3600.0) }
                } else usual?.let { it.low..it.high }

                val chartUnit = if (type == MetricType.BODY_FAT) {
                    if (bodyUnitMode == BodyUnitMode.KG) fmt.weightUnit() else "%"
                } else if (isWeightType) {
                    if (bodyUnitMode == BodyUnitMode.PERCENT && type != MetricType.WEIGHT) "%" else fmt.weightUnit()
                } else if (type == MetricType.SLEEP_DURATION) "h" else cfg.unit

                LineChart(
                    values = chartValues,
                    band = chartBand,
                    color = cfg.color,
                    unit = chartUnit,
                    xLabels = series.dates.map { it.toString().takeLast(5) },
                    artworkRes = singleArt,
                    motifKind = singleMotif,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                if (isBodyComp) {
                    Spacer(Modifier.height(10.dp))
                    BodyCompositionStackedBar(
                        weightKg = bundle.refWeightKg,
                        muscleKg = bundle.latestMuscleKg,
                        fatPct = bundle.latestFatPct,
                        boneKg = bundle.latestBoneKg,
                        unitMode = bodyUnitMode,
                        onUnitModeChange = { bodyUnitMode = it }
                    )
                    Spacer(Modifier.height(10.dp))
                    SegmentalBodyCompositionSection(
                        sl = sl,
                        initialModeFat = (type == MetricType.BODY_FAT),
                        daysCount = daysCount,
                        externalUnitMode = bodyUnitMode,
                        onUnitModeChange = { bodyUnitMode = it }
                    )
                }

                Spacer(Modifier.height(10.dp))
                LazyMetricHistoryListCard(
                    sl = sl,
                    type = type,
                    usual = usual,
                    refWeightKg = bundle.refWeightKg,
                    bodyUnitMode = bodyUnitMode
                )

                if (showInfoDialog) {
                    MetricInfoPopupDialog(type = type, cfg = cfg, onDismiss = { showInfoDialog = false })
                }
            }
        }
    }
}

private fun latestWeightKgOrFallback(v: Double?): Double =
    v?.takeIf { it in 25.0..250.0 } ?: 93.10

fun Sport.czechName(): String = when (this) {
    Sport.RUNNING -> "Běh"
    Sport.CYCLING -> "Cyklistika"
    Sport.WALKING -> "Chůze"
    Sport.HIKING -> "Turistika"
    Sport.SWIMMING -> "Plavání"
    Sport.STRENGTH -> "Silový trénink"
    Sport.OTHER -> "Ostatní aktivita"
}

fun Sport.materialIcon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    Sport.RUNNING -> Icons.AutoMirrored.Outlined.DirectionsRun
    Sport.CYCLING -> Icons.AutoMirrored.Outlined.DirectionsBike
    Sport.WALKING -> Icons.AutoMirrored.Outlined.DirectionsWalk
    Sport.HIKING -> Icons.Outlined.Terrain
    Sport.SWIMMING -> Icons.Outlined.Pool
    Sport.STRENGTH -> Icons.Outlined.FitnessCenter
    Sport.OTHER -> Icons.Outlined.Timer
}

fun Sport.icon(): String = ""

data class SportAtmosphere(
    @androidx.annotation.DrawableRes val artworkRes: Int,
    val primaryColor: Color,
    val secondaryColor: Color,
    val disciplineTag: String,
    val worldSubtitle: String
)

fun Sport.atmosphere(title: String = ""): SportAtmosphere {
    val lower = title.lowercase()
    return when (this) {
        Sport.RUNNING -> SportAtmosphere(
            artworkRes = R.drawable.img_motif_running_stride,
            primaryColor = Color(0xFFFF6B4A),
            secondaryColor = Color(0xFFF59E0B),
            disciplineTag = if ("trail" in lower || "les" in lower) "GARMIN TRAIL BĚH" else if ("vtp" in lower || "interval" in lower || "tempo" in lower) "GARMIN TEMPOVÝ BĚH" else "GARMIN BĚH",
            worldSubtitle = "Dynamika kroku, kadence & tepové zóny"
        )
        Sport.CYCLING -> SportAtmosphere(
            artworkRes = R.drawable.img_sport_cycling_hero,
            primaryColor = Color(0xFF00C3FF),
            secondaryColor = Color(0xFF2DD4BF),
            disciplineTag = if ("gravel" in lower) "GARMIN GRAVEL" else if ("mtb" in lower || "horsk" in lower) "GARMIN MTB" else "GARMIN CYKLISTIKA",
            worldSubtitle = "Výkon ve wattech, kadence & aerodynamické tempo"
        )
        Sport.WALKING -> SportAtmosphere(
            artworkRes = R.drawable.img_motif_steps_trail,
            primaryColor = Color(0xFF10B981),
            secondaryColor = Color(0xFF34D399),
            disciplineTag = "GARMIN CHŮZE",
            worldSubtitle = "Aktivní regenerace, plynulý krok & aerobní základ"
        )
        Sport.HIKING -> SportAtmosphere(
            artworkRes = R.drawable.img_alpine_trail_hero,
            primaryColor = Color(0xFF84CC16),
            secondaryColor = Color(0xFFFBBF24),
            disciplineTag = "GARMIN HORSKÁ TURISTIKA",
            worldSubtitle = "Hřebenový výškový profil, stoupání & vytrvalost"
        )
        Sport.SWIMMING -> SportAtmosphere(
            artworkRes = R.drawable.img_sport_swimming_hero,
            primaryColor = Color(0xFF38BDF8),
            secondaryColor = Color(0xFF06B6D4),
            disciplineTag = if ("open" in lower || "jezer" in lower) "GARMIN VOLNÁ VODA" else "GARMIN PLAVÁNÍ",
            worldSubtitle = "Hydrodynamika, SWOLF efektivita & záběry"
        )
        Sport.STRENGTH -> SportAtmosphere(
            artworkRes = R.drawable.img_sport_strength_hero,
            primaryColor = Color(0xFFF59E0B),
            secondaryColor = Color(0xFFF43F5E),
            disciplineTag = if ("hiit" in lower) "GARMIN HIIT & KONDICE" else "GARMIN SILOVÝ TRÉNINK",
            worldSubtitle = "Série, nazvedaný objem & anaerobní impuls"
        )
        Sport.OTHER -> SportAtmosphere(
            artworkRes = R.drawable.img_sport_tactical_hero,
            primaryColor = Color(0xFFA855F7),
            secondaryColor = Color(0xFF2DD4BF),
            disciplineTag = if ("taktic" in lower || "hrádek" in lower) "GARMIN TAKTICKÁ AKCE" else "GARMIN MULTISPORT",
            worldSubtitle = "Terénní navigace, azimut & celková zátěž"
        )
    }
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSportTelemetryOverlay(
    sport: Sport,
    primary: Color,
    secondary: Color
) {
    val w = size.width
    val h = size.height
    // Jemná horní a pravá světelná aura v barvě sportu
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(primary.copy(alpha = 0.24f), secondary.copy(alpha = 0.08f), Color.Transparent),
            center = Offset(w * 0.84f, h * 0.28f),
            radius = w * 0.55f
        ),
        center = Offset(w * 0.84f, h * 0.28f),
        radius = w * 0.55f
    )

    when (sport) {
        Sport.RUNNING -> {
            // Rytmická křivka kadence a tempa v pravé horní části karty
            val path = Path()
            val pts = 28
            for (i in 0..pts) {
                val t = i.toFloat() / pts
                val x = w * (0.35f + t * 0.65f)
                val y = h * 0.34f - kotlin.math.sin(t * Math.PI * 3.5).toFloat() * (h * 0.11f) - t * (h * 0.08f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(listOf(Color.Transparent, primary.copy(alpha = 0.42f), secondary.copy(alpha = 0.65f))),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Sport.CYCLING -> {
            // Aerodynamické proudnice a wattové sloupce
            for (k in 0..2) {
                val path = Path()
                val yBase = h * (0.22f + k * 0.09f)
                path.moveTo(w * 0.40f, yBase + h * 0.08f)
                path.quadraticBezierTo(w * 0.70f, yBase - h * 0.06f, w, yBase)
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, primary.copy(alpha = 0.22f + k * 0.1f), secondary.copy(alpha = 0.50f))),
                    style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Sport.HIKING -> {
            // Vrstevnice horského hřebene
            for (layer in 0..2) {
                val path = Path()
                val base = h * (0.42f + layer * 0.07f)
                path.moveTo(w * 0.30f, base)
                path.lineTo(w * 0.52f, base - h * 0.14f)
                path.lineTo(w * 0.68f, base - h * 0.06f)
                path.lineTo(w * 0.86f, base - h * 0.20f)
                path.lineTo(w, base - h * 0.09f)
                drawPath(
                    path = path,
                    color = primary.copy(alpha = 0.38f - layer * 0.10f),
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Sport.SWIMMING -> {
            // Bioluminiscenční vodní vlny
            for (wave in 0..1) {
                val path = Path()
                val pts = 24
                for (i in 0..pts) {
                    val t = i.toFloat() / pts
                    val x = w * (0.30f + t * 0.70f)
                    val y = h * (0.28f + wave * 0.09f) + kotlin.math.sin(t * Math.PI * 4.0 + wave).toFloat() * (h * 0.05f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, primary.copy(alpha = 0.45f), secondary.copy(alpha = 0.60f))),
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Sport.STRENGTH -> {
            // Izometrické sloupce silového objemu
            val heights = listOf(0.18f, 0.32f, 0.25f, 0.42f, 0.36f, 0.48f, 0.30f)
            heights.forEachIndexed { idx, ratio ->
                val x = w * (0.62f + idx * 0.05f)
                val barH = h * ratio
                drawLine(
                    brush = Brush.verticalGradient(listOf(primary.copy(alpha = 0.55f), secondary.copy(alpha = 0.10f))),
                    start = Offset(x, h * 0.48f - barH * 0.5f),
                    end = Offset(x, h * 0.48f + barH * 0.5f),
                    strokeWidth = 3.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        Sport.WALKING -> {
            // Plynulá lesní stezka
            val path = Path().apply {
                moveTo(w * 0.38f, h * 0.40f)
                cubicTo(w * 0.58f, h * 0.18f, w * 0.76f, h * 0.46f, w, h * 0.22f)
            }
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(listOf(Color.Transparent, primary.copy(alpha = 0.45f), secondary.copy(alpha = 0.55f))),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Sport.OTHER -> {
            // Taktický zaměřovací prstenec / azimut
            val c = Offset(w * 0.85f, h * 0.30f)
            drawCircle(
                color = primary.copy(alpha = 0.28f),
                radius = h * 0.22f,
                center = c,
                style = Stroke(width = 1.2.dp.toPx())
            )
            drawCircle(
                color = secondary.copy(alpha = 0.20f),
                radius = h * 0.12f,
                center = c,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

fun Sport.badgeColor(): Color = when (this) {
    Sport.RUNNING -> Color(0xFFFF6B4A)
    Sport.CYCLING -> Color(0xFF00C3FF)
    Sport.WALKING -> Color(0xFF10B981)
    Sport.HIKING -> Color(0xFF84CC16)
    Sport.SWIMMING -> Color(0xFF38BDF8)
    Sport.STRENGTH -> Color(0xFFF59E0B)
    Sport.OTHER -> Color(0xFFA855F7)
}

/** Jednotný model položky v seznamu aktivit (vlastní i sdílené přes P2P). */
data class UnifiedActivityItem(
    val key: String, // "actId" pro vlastní, "shared:author:actId" pro sdílené
    val sport: Sport,
    val title: String,
    val locationName: String? = null,
    val ownerName: String,
    val isShared: Boolean,
    val startUtcMs: Long?,
    val localDate: String,
    val distanceM: Double?,
    val movingTimeS: Double?,
    val ascentM: Double?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val caloriesKcal: Double? = null,
    val aerobicTe: Double? = null,
    val anaerobicTe: Double? = null,
    val trainingLoad: Double? = null,
    val avgPowerW: Double? = null,
    val avgCadence: Double? = null,
)

enum class ActivityDateFilter(val label: String) {
    ALL("Vše"),
    LAST_7D("7 dní"),
    LAST_30D("30 dní"),
    THIS_MONTH("Tento měsíc"),
    THIS_YEAR("Tento rok"),
    CUSTOM_DATE("Kalendář…")
}

enum class SummaryPeriod(val label: String) {
    WEEK("Týden"),
    MONTH("Měsíc"),
    YEAR("Rok"),
    ALL("Celkem")
}

@Composable
fun ActivitiesScreen(
    sl: ServiceLocator,
    onOpen: (String) -> Unit,
    onCompare: (List<String>) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    val initialActs = remember { ScreenMemoryCache.activitySummaries ?: emptyList() }
    val acts by sl.db.activities().allSummaries().collectAsState(initial = initialActs)
    LaunchedEffect(acts) {
        ScreenMemoryCache.activitySummaries = acts
    }

    val sharedRows by sl.db.inbox().objects("activity").collectAsState(initial = emptyList())
    val contacts by sl.db.contacts().all().collectAsState(initial = emptyList())

    var sportsFilter by remember { mutableStateOf(setOf<Sport>()) }
    var sourcesFilter by remember { mutableStateOf(setOf<String>()) } // empty / 2 items = ALL, "MINE", "SHARED"
    var dateFilter by remember { mutableStateOf(ActivityDateFilter.ALL) }
    var customDate by remember { mutableStateOf<LocalDate?>(null) }
    var summaryPeriod by remember { mutableStateOf(SummaryPeriod.WEEK) }

    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var sportMenuExpanded by remember { mutableStateOf(false) }
    var dateMenuExpanded by remember { mutableStateOf(false) }
    var selectedForCompare by remember { mutableStateOf(setOf<String>()) }

    val today = remember { LocalDate.now(ZoneId.of(sl.settings.zoneId)) }
    val showDatePicker = {
        val initial = customDate ?: today
        android.app.DatePickerDialog(
            ctx,
            { _, year, month, dayOfMonth ->
                customDate = LocalDate.of(year, month + 1, dayOfMonth)
                dateFilter = ActivityDateFilter.CUSTOM_DATE
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth
        ).show()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            msg = withContext(Dispatchers.IO) {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext "Soubor nelze přečíst"
                when (val r = sl.importService.importFit(bytes, System.currentTimeMillis())) {
                    is FitImportResult.Imported -> "Soubor FIT byl úspěšně importován"
                    is FitImportResult.Rejected -> "Soubor odmítnut: ${r.reason}"
                }
            }
        }
    }

    val contactsMap = remember(contacts) { contacts.associate { it.identity to it.displayName } }
    val unifiedList by produceState<List<UnifiedActivityItem>>(
        initialValue = emptyList(),
        acts,
        sharedRows,
        contactsMap
    ) {
        value = withContext(Dispatchers.Default) {
            val zoneCache = HashMap<String, ZoneId>()
            val defaultZone = ZoneId.systemDefault()
            val mine = acts.map { row ->
                val sportEnum = runCatching { Sport.valueOf(row.sport) }.getOrDefault(Sport.OTHER)
                val title = row.title?.takeIf { it.isNotBlank() } ?: sportEnum.czechName()
                val zone = row.zoneId?.let { zid ->
                    zoneCache.getOrPut(zid) {
                        runCatching { ZoneId.of(zid) }.getOrDefault(defaultZone)
                    }
                } ?: defaultZone
                val localDateStr = java.time.Instant.ofEpochMilli(row.startUtcMs).atZone(zone).toLocalDate().toString()
                UnifiedActivityItem(
                    key = row.id,
                    sport = sportEnum,
                    title = title,
                    locationName = null,
                    ownerName = "Já",
                    isShared = false,
                    startUtcMs = row.startUtcMs,
                    localDate = localDateStr,
                    distanceM = row.distanceM,
                    movingTimeS = row.movingTimeS,
                    ascentM = row.ascentM,
                    avgHeartRate = row.avgHeartRate,
                    maxHeartRate = row.maxHeartRate,
                    caloriesKcal = row.caloriesKcal,
                    aerobicTe = row.aerobicTe,
                    anaerobicTe = row.anaerobicTe,
                    trainingLoad = row.trainingLoad,
                    avgPowerW = row.avgPowerW,
                    avgCadence = row.avgCadence
                )
            }
            val shared = sharedRows.mapNotNull { r ->
                val p = runCatching { Wire.json.decodeFromString(ActivityProjection.serializer(), r.payload) }.getOrNull() ?: return@mapNotNull null
                val sp = runCatching { Sport.valueOf(p.sport) }.getOrDefault(Sport.OTHER)
                val cName = contactsMap[r.author] ?: r.author.take(8)
                UnifiedActivityItem(
                    key = "shared:${r.author}:${r.objectId}",
                    sport = sp,
                    title = "${sp.czechName()} ($cName)",
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
            (mine + shared).sortedByDescending { it.startUtcMs ?: 0L }
        }
    }

    val filtered = remember(unifiedList, sportsFilter, sourcesFilter, dateFilter, customDate) {
        unifiedList.filter { item ->
            val matchSport = sportsFilter.isEmpty() || item.sport in sportsFilter
            val matchSource = when {
                sourcesFilter.isEmpty() || sourcesFilter.size == 2 -> true
                "MINE" in sourcesFilter -> !item.isShared
                "SHARED" in sourcesFilter -> item.isShared
                else -> true
            }
            val matchDate = when (dateFilter) {
                ActivityDateFilter.ALL -> true
                ActivityDateFilter.LAST_7D -> {
                    val d = runCatching { LocalDate.parse(item.localDate) }.getOrNull()
                    d != null && !d.isBefore(today.minusDays(7))
                }
                ActivityDateFilter.LAST_30D -> {
                    val d = runCatching { LocalDate.parse(item.localDate) }.getOrNull()
                    d != null && !d.isBefore(today.minusDays(30))
                }
                ActivityDateFilter.THIS_MONTH -> {
                    val d = runCatching { LocalDate.parse(item.localDate) }.getOrNull()
                    d != null && d.year == today.year && d.monthValue == today.monthValue
                }
                ActivityDateFilter.THIS_YEAR -> {
                    val d = runCatching { LocalDate.parse(item.localDate) }.getOrNull()
                    d != null && d.year == today.year
                }
                ActivityDateFilter.CUSTOM_DATE -> {
                    val d = runCatching { LocalDate.parse(item.localDate) }.getOrNull()
                    customDate != null && d == customDate
                }
            }
            matchSport && matchSource && matchDate
        }
    }

    val summaryActivities = remember(filtered, summaryPeriod, today) {
        when (summaryPeriod) {
            SummaryPeriod.ALL -> filtered
            SummaryPeriod.WEEK -> {
                val weekStart = today.minusDays(today.dayOfWeek.value.toLong() - 1)
                filtered.filter { item ->
                    val d = runCatching { LocalDate.parse(item.localDate) }.getOrNull()
                    d != null && !d.isBefore(weekStart) && !d.isAfter(today)
                }
            }
            SummaryPeriod.MONTH -> {
                filtered.filter { item ->
                    val d = runCatching { LocalDate.parse(item.localDate) }.getOrNull()
                    d != null && d.year == today.year && d.monthValue == today.monthValue
                }
            }
            SummaryPeriod.YEAR -> {
                filtered.filter { item ->
                    val d = runCatching { LocalDate.parse(item.localDate) }.getOrNull()
                    d != null && d.year == today.year
                }
            }
        }
    }

    val summaryStats = remember(summaryActivities) {
        var dist = 0.0
        var time = 0.0
        var asc = 0.0
        var cal = 0.0
        for (a in summaryActivities) {
            dist += a.distanceM ?: 0.0
            time += a.movingTimeS ?: 0.0
            asc += a.ascentM ?: 0.0
            cal += a.caloriesKcal ?: 0.0
        }
        ActivityAggregates(
            distM = dist,
            timeS = time,
            ascentM = asc,
            calories = cal,
            sportCounts = emptyMap(),
            myCount = 0,
            sharedCount = 0,
            availableSports = emptyList()
        )
    }

    val aggregates = remember(filtered, unifiedList) {
        val sportCounts = mutableMapOf<Sport, Int>()
        val sportOrder = mutableListOf<Sport>()
        var sharedCnt = 0
        for (item in unifiedList) {
            if (item.isShared) sharedCnt++
            val prev = sportCounts[item.sport] ?: 0
            if (prev == 0) sportOrder.add(item.sport)
            sportCounts[item.sport] = prev + 1
        }
        ActivityAggregates(
            distM = 0.0,
            timeS = 0.0,
            ascentM = 0.0,
            calories = 0.0,
            sportCounts = sportCounts,
            myCount = unifiedList.size - sharedCnt,
            sharedCount = sharedCnt,
            availableSports = sportOrder
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Horní panel sjednocený s designem Dnešního Horizontu
        item(key = "header") {
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
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(Color(0xFFF59E0B), CircleShape)
                        )
                        Text(
                            "SOVEREIGN TELEMETRY · ${aggregates.myCount} VLASTNÍCH · ${aggregates.sharedCount} P2P",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                            ),
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFF59E0B),
                            letterSpacing = 1.0.sp,
                            fontSize = 9.sp
                        )
                    }
                    Text(
                        "Aktivity & Trasy",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF0E1B2C))
                            .border(BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.45f)), RoundedCornerShape(18.dp))
                            .clickable(enabled = !syncing) {
                                scope.launch {
                                    syncing = true
                                    msg = null
                                    val count = withContext(Dispatchers.IO) {
                                        sl.importService.syncGarminActivities(System.currentTimeMillis())
                                    }
                                    msg = if (count > 0) "Staženo $count aktivit z Garminu."
                                    else "Aktivity jsou aktuální."
                                    syncing = false
                                }
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Sync, contentDescription = "Garmin sync", tint = Color(0xFF2DD4BF), modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(
                                if (syncing) "…" else "Garmin",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                                ),
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF0C1523))
                            .border(BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.45f)), RoundedCornerShape(18.dp))
                            .clickable { picker.launch(arrayOf("*/*")) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = "Import FIT", tint = Color(0xFF38BDF8), modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "FIT",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                                ),
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        msg?.let { m ->
            item(key = "msg") {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text(m, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Lišta porovnávání aktivit (Compare), když uživatel zaškrtne aktivity
        if (selectedForCompare.isNotEmpty()) {
            item(key = "compare_bar") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2942)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Vybráno k porovnání: ${selectedForCompare.size}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                if (selectedForCompare.size < 2) "Zaškrtněte ještě alespoň 1 aktivitu"
                                else "Připraveno k porovnání metrik, křivek a tras",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF93C5FD)
                            )
                        }
                        Row {
                            TextButton(onClick = { selectedForCompare = emptySet() }) {
                                Text("Zrušit", color = Color(0xFF94A3B8))
                            }
                            Button(
                                enabled = selectedForCompare.size >= 2,
                                onClick = { onCompare(selectedForCompare.toList()) }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.AutoMirrored.Outlined.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Porovnat (${selectedForCompare.size})")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Hero Summary Card s dynamickou fotografickou scénou podle vybraného sportu a přepínátkem období
        item(key = "hero_summary") {
            val activeSingleSport = if (sportsFilter.size == 1) sportsFilter.first() else null
            val heroAtmo = activeSingleSport?.atmosphere()
            val heroBgRes = heroAtmo?.artworkRes ?: R.drawable.img_alpine_trail_hero
            val heroPrimary = heroAtmo?.primaryColor ?: Color(0xFFF59E0B)
            val heroSecondary = heroAtmo?.secondaryColor ?: Color(0xFF38BDF8)

            Card(
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(
                    1.2.dp,
                    Brush.horizontalGradient(
                        listOf(
                            heroPrimary.copy(alpha = 0.65f),
                            heroSecondary.copy(alpha = 0.35f)
                        )
                    )
                ),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF070C14)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = heroBgRes),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(0.84f)
                    )
                    Canvas(Modifier.matchParentSize()) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.0f to Color(0x88050911),
                                0.45f to Color(0x99060B14),
                                1.0f to Color(0xEB050911)
                            )
                        )
                        drawSportTelemetryOverlay(
                            sport = activeSingleSport ?: Sport.HIKING,
                            primary = heroPrimary,
                            secondary = heroSecondary
                        )
                    }
                    Column(Modifier.padding(18.dp)) {
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
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .background(heroPrimary, CircleShape)
                                    )
                                    Text(
                                        heroAtmo?.disciplineTag ?: "GARMIN CONNECT · SPORTOVNÍ HORIZONT",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = heroPrimary,
                                        letterSpacing = 1.1.sp,
                                        fontSize = 10.sp
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${summaryActivities.size} ${if (summaryActivities.size == 1) "zaznamenaná aktivita" else if (summaryActivities.size in 2..4) "zaznamenané aktivity" else "zaznamenaných aktivit"}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                            }
                            Row(
                                Modifier
                                    .background(Color(0xCC0A121E), RoundedCornerShape(12.dp))
                                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(12.dp))
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                SummaryPeriod.entries.forEach { p ->
                                    val selected = summaryPeriod == p
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(if (selected) heroPrimary else Color.Transparent)
                                            .clickable { summaryPeriod = p }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            p.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                            color = if (selected) Color(0xFF050911) else Color(0xFFE2E8F0)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xAA060B13),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        fmt.km(summaryStats.distM),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                                        ),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    Text("VZDÁLENOST", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), fontSize = 9.sp)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        fmt.duration(summaryStats.timeS),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                                        ),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    Text("ČAS POHYBU", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), fontSize = 9.sp)
                                }
                                Column(Modifier.weight(0.85f)) {
                                    Text(
                                        fmt.elevation(summaryStats.ascentM),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                                        ),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFFBBF24)
                                    )
                                    Text("PŘEVÝŠENÍ", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), fontSize = 9.sp)
                                }
                                Column(Modifier.weight(0.85f)) {
                                    Text(
                                        "${summaryStats.calories.toInt()} kcal",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = app.pulse4.android.ui.theme.JetBrainsMonoFamily
                                        ),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF2DD4BF)
                                    )
                                    Text("ENERGIE", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), fontSize = 9.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fotografický pás "SPORTOVNÍ SVĚTY" (každý sport z Garmin API má vlastní scénu bez ikon!)
        if (aggregates.availableSports.isNotEmpty()) {
            item(key = "sport_worlds_strip") {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "SPORTOVNÍ SVĚTY GARMIN CONNECT",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8),
                            letterSpacing = 0.9.sp
                        )
                        if (sportsFilter.isNotEmpty()) {
                            Text(
                                "Zobrazit vše (${unifiedList.size})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.clickable { sportsFilter = emptySet() }
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        aggregates.availableSports.forEach { sp ->
                            val atmo = sp.atmosphere()
                            val cnt = aggregates.sportCounts[sp] ?: 0
                            val isSelected = sp in sportsFilter
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(
                                    if (isSelected) 1.6.dp else 1.dp,
                                    if (isSelected) {
                                        Brush.horizontalGradient(listOf(atmo.primaryColor, atmo.secondaryColor))
                                    } else {
                                        Brush.horizontalGradient(listOf(atmo.primaryColor.copy(alpha = 0.35f), Color(0xFF1E293B)))
                                    }
                                ),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF070C14)),
                                modifier = Modifier
                                    .width(136.dp)
                                    .height(72.dp)
                                    .clickable {
                                        sportsFilter = if (isSelected) sportsFilter - sp else sportsFilter + sp
                                    }
                            ) {
                                Box(Modifier.fillMaxSize()) {
                                    Image(
                                        painter = painterResource(id = atmo.artworkRes),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .matchParentSize()
                                            .alpha(if (isSelected) 0.90f else 0.62f)
                                    )
                                    Canvas(Modifier.matchParentSize()) {
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                listOf(
                                                    Color(0x55050911),
                                                    if (isSelected) atmo.primaryColor.copy(alpha = 0.28f) else Color(0xAA060B13),
                                                    Color(0xE6050911)
                                                )
                                            )
                                        )
                                    }
                                    Column(
                                        Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(7.dp)
                                                    .background(atmo.primaryColor, CircleShape)
                                            )
                                            Text(
                                                "$cnt×",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                                                fontSize = 10.sp
                                            )
                                        }
                                        Text(
                                            sp.czechName(),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White,
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
        }

        // Kompaktní filtry v moderních Dropdown Menu (Zdroj, Sport, Období — čistá typografie a barevné body bez sportovních ikonek)
        item(key = "filters") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. ZDROJ (Vše / Moje / Sdílené)
                Box {
                    val isSourceFiltered = sourcesFilter.size == 1
                    FilterChip(
                        selected = isSourceFiltered,
                        onClick = { sourceMenuExpanded = true },
                        trailingIcon = {
                            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        label = {
                            Text(
                                when {
                                    sourcesFilter == setOf("MINE") -> "Moje (${aggregates.myCount})"
                                    sourcesFilter == setOf("SHARED") -> "Sdílené P2P (${aggregates.sharedCount})"
                                    else -> "Zdroj: Vše (${unifiedList.size})"
                                }
                            )
                        }
                    )
                    DropdownMenu(
                        expanded = sourceMenuExpanded,
                        onDismissRequest = { sourceMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Všechny zdroje (${unifiedList.size})") },
                            onClick = { sourcesFilter = emptySet(); sourceMenuExpanded = false }
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF232A3B)))
                        DropdownMenuItem(
                            text = { Text("Moje aktivity (${aggregates.myCount})") },
                            leadingIcon = {
                                Checkbox(
                                    checked = "MINE" in sourcesFilter,
                                    onCheckedChange = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                sourcesFilter = if ("MINE" in sourcesFilter) sourcesFilter - "MINE" else sourcesFilter + "MINE"
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Sdílené P2P (${aggregates.sharedCount})") },
                            leadingIcon = {
                                Checkbox(
                                    checked = "SHARED" in sourcesFilter,
                                    onCheckedChange = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                sourcesFilter = if ("SHARED" in sourcesFilter) sourcesFilter - "SHARED" else sourcesFilter + "SHARED"
                            }
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF232A3B)))
                        DropdownMenuItem(
                            text = { Text("Hotovo", color = Color(0xFF38BDF8)) },
                            onClick = { sourceMenuExpanded = false }
                        )
                    }
                }

                // 2. SPORT (s možností zaškrtnutí více sportů najednou — barevný bod místo ikony)
                Box {
                    val isSportFiltered = sportsFilter.isNotEmpty()
                    FilterChip(
                        selected = isSportFiltered,
                        onClick = { sportMenuExpanded = true },
                        leadingIcon = if (sportsFilter.size == 1) {
                            {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .background(sportsFilter.first().badgeColor(), CircleShape)
                                )
                            }
                        } else null,
                        trailingIcon = {
                            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        label = {
                            Text(
                                when {
                                    sportsFilter.isEmpty() -> "Sport: Vše"
                                    sportsFilter.size == 1 -> "${sportsFilter.first().czechName()} (${aggregates.sportCounts[sportsFilter.first()] ?: 0})"
                                    else -> "Vybrané sporty (${sportsFilter.size})"
                                }
                            )
                        }
                    )
                    DropdownMenu(
                        expanded = sportMenuExpanded,
                        onDismissRequest = { sportMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Všechny sporty (${unifiedList.size})") },
                            onClick = { sportsFilter = emptySet(); sportMenuExpanded = false }
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF232A3B)))
                        aggregates.availableSports.forEach { s ->
                            val cnt = aggregates.sportCounts[s] ?: 0
                            val isChecked = s in sportsFilter
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier
                                                .size(9.dp)
                                                .background(s.badgeColor(), CircleShape)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("${s.czechName()} ($cnt)")
                                    }
                                },
                                leadingIcon = {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    sportsFilter = if (isChecked) sportsFilter - s else sportsFilter + s
                                }
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF232A3B)))
                        DropdownMenuItem(
                            text = { Text("Hotovo", color = Color(0xFF38BDF8)) },
                            onClick = { sportMenuExpanded = false }
                        )
                    }
                }

                // 3. DATUM / KALENDÁŘ
                Box {
                    val isCustom = dateFilter == ActivityDateFilter.CUSTOM_DATE && customDate != null
                    FilterChip(
                        selected = dateFilter != ActivityDateFilter.ALL,
                        onClick = { dateMenuExpanded = true },
                        trailingIcon = {
                            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        label = {
                            Text(if (isCustom) customDate.toString() else "Období: ${dateFilter.label}")
                        }
                    )
                    DropdownMenu(
                        expanded = dateMenuExpanded,
                        onDismissRequest = { dateMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Celé období") },
                            onClick = { dateFilter = ActivityDateFilter.ALL; dateMenuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Posledních 7 dní") },
                            onClick = { dateFilter = ActivityDateFilter.LAST_7D; dateMenuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Posledních 30 dní") },
                            onClick = { dateFilter = ActivityDateFilter.LAST_30D; dateMenuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Tento měsíc") },
                            onClick = { dateFilter = ActivityDateFilter.THIS_MONTH; dateMenuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Tento rok") },
                            onClick = { dateFilter = ActivityDateFilter.THIS_YEAR; dateMenuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text(if (customDate != null) "Den: $customDate" else "Zvolit den v kalendáři…") },
                            onClick = {
                                dateMenuExpanded = false
                                showDatePicker()
                            }
                        )
                    }
                }

                if (sourcesFilter.size == 1 || sportsFilter.isNotEmpty() || dateFilter != ActivityDateFilter.ALL) {
                    TextButton(
                        onClick = {
                            sourcesFilter = emptySet()
                            sportsFilter = emptySet()
                            dateFilter = ActivityDateFilter.ALL
                            customDate = null
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Vymazat filtry ✕", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFB7185))
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item(key = "empty") {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0B111C)),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Žádné aktivity v tomto filtru", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Klikněte na \"Garmin\" pro stažení aktivit z účtu, \"FIT\" pro import souboru, nebo přijměte sdílenou aktivitu od kontaktu přes P2P.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
        } else {
            items(
                items = filtered,
                key = { it.key }
            ) { a ->
                ActivityCardItem(
                    a = a,
                    isChecked = a.key in selectedForCompare,
                    onToggleCompare = { checked ->
                        selectedForCompare = if (checked) {
                            if (selectedForCompare.size < 4) selectedForCompare + a.key else selectedForCompare
                        } else {
                            selectedForCompare - a.key
                        }
                    },
                    onClick = { onOpen(a.key) }
                )
            }
        }
    }
}

private data class ActivityAggregates(
    val distM: Double,
    val timeS: Double,
    val ascentM: Double,
    val calories: Double,
    val sportCounts: Map<Sport, Int>,
    val myCount: Int,
    val sharedCount: Int,
    val availableSports: List<Sport>
)

/**
 * Ambiciózní atmosférická karta aktivity bez ikonek:
 * Každý sport z Garmin Connect API má vlastní fotografickou scénu, procedurální telemetrickou vrstvu,
 * barevnou auru a sportovně specifický skleněný dok metrik.
 */
@Composable
internal fun ActivityCardItem(
    a: UnifiedActivityItem,
    isChecked: Boolean,
    onToggleCompare: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val atmo = a.sport.atmosphere(a.title)
    val dist = a.distanceM
    val paceSPerKm = if (dist != null && dist > 0 && a.movingTimeS != null) (a.movingTimeS / (dist / 1000.0)) else null
    val speedKmh = if (dist != null && dist > 0 && a.movingTimeS != null && a.movingTimeS > 0) (dist / a.movingTimeS) * 3.6 else null

    Card(
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            width = if (isChecked) 1.8.dp else 1.2.dp,
            brush = if (isChecked) {
                Brush.horizontalGradient(listOf(Color(0xFF38BDF8), atmo.primaryColor))
            } else {
                Brush.horizontalGradient(
                    listOf(
                        atmo.primaryColor.copy(alpha = 0.62f),
                        atmo.secondaryColor.copy(alpha = 0.28f),
                        Color(0xFF1E293B).copy(alpha = 0.55f)
                    )
                )
            }
        ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Box(Modifier.fillMaxWidth()) {
            // 1. Vlastní fotografická scéna daného sportu s vysokou viditelností
            Image(
                painter = painterResource(id = atmo.artworkRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .alpha(0.78f)
            )

            // 2. Atmosférický přechod + procedurální telemetrická kresba daného sportu
            Canvas(Modifier.matchParentSize()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        0.0f to Color(0x99040810),
                        0.38f to Color(0xAA060B14),
                        0.72f to Color(0xD9050911),
                        1.0f to Color(0xF2050911)
                    )
                )
                drawSportTelemetryOverlay(
                    sport = a.sport,
                    primary = atmo.primaryColor,
                    secondary = atmo.secondaryColor
                )
            }

            // 3. Obsah karty (čistá typografie, barevné aury a skleněné kapsle — bez ikonek!)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // Horní řádek: disciplína z Garmin API + P2P odznak + čas + skleněné tlačítko Porovnat
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xCC060C16),
                            border = BorderStroke(1.dp, atmo.primaryColor.copy(alpha = 0.55f))
                        ) {
                            Row(
                                Modifier.padding(horizontal = 9.dp, vertical = 3.5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(atmo.primaryColor, CircleShape)
                                )
                                Text(
                                    atmo.disciplineTag,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    letterSpacing = 0.8.sp,
                                    fontSize = 9.5.sp
                                )
                            }
                        }

                        if (a.isShared) {
                            androidx.compose.material3.Surface(
                                shape = RoundedCornerShape(50),
                                color = Color(0xCC06281E),
                                border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.6f))
                            ) {
                                Text(
                                    "P2P · ${a.ownerName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.5.sp,
                                    color = Color(0xFF34D399),
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            a.startUtcMs?.let(fmt::time) ?: a.localDate,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE2E8F0),
                            fontWeight = FontWeight.SemiBold
                        )
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isChecked) Color(0xFF38BDF8) else Color(0xAA0A1320),
                            border = BorderStroke(
                                1.dp,
                                if (isChecked) Color.White else Color.White.copy(alpha = 0.22f)
                            ),
                            modifier = Modifier.clickable { onToggleCompare(!isChecked) }
                        ) {
                            Text(
                                text = if (isChecked) "✓ Porovnat" else "Porovnat",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = if (isChecked) Color(0xFF050911) else Color(0xFFCBD5E1),
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.5.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Hlavní titulek aktivity a podtitul sportovního světa
                Text(
                    a.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    atmo.worldSubtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFCBD5E1),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(12.dp))

                // Skleněný dok hlavních metrik přizpůsobený typu sportu z Garmin API
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xB8060B13),
                    border = BorderStroke(
                        1.dp,
                        Brush.horizontalGradient(
                            listOf(
                                atmo.primaryColor.copy(alpha = 0.35f),
                                Color.White.copy(alpha = 0.08f)
                            )
                        )
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        when (a.sport) {
                            Sport.STRENGTH -> {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        a.movingTimeS?.let(fmt::duration) ?: "—",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    Text("Čas tréninku", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        a.caloriesKcal?.let { "${it.toInt()} kcal" } ?: "—",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = atmo.primaryColor
                                    )
                                    Text("Kalorie", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        a.avgHeartRate?.let { "$it bpm" } ?: "—",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFFB7185)
                                    )
                                    Text("Prům. tep", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                                Column(Modifier.weight(0.9f)) {
                                    Text(
                                        a.maxHeartRate?.let { "$it bpm" } ?: (a.trainingLoad?.let { "TL ${it.toInt()}" } ?: "—"),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFE2E8F0)
                                    )
                                    Text(if (a.maxHeartRate != null) "Max tep" else "Zátěž", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                            }
                            Sport.CYCLING -> {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        fmt.km(a.distanceM),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    Text("Vzdálenost", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        a.movingTimeS?.let(fmt::duration) ?: "—",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    Text("Čas jízdy", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        speedKmh?.let { "${fmt.number(it, 1)} km/h" } ?: "—",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = atmo.primaryColor
                                    )
                                    Text("Rychlost", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                                Column(Modifier.weight(0.9f)) {
                                    Text(
                                        a.avgPowerW?.let { "${it.toInt()} W" } ?: (a.avgHeartRate?.let { "$it bpm" } ?: "—"),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (a.avgPowerW != null) Color(0xFF2DD4BF) else Color(0xFFFB7185)
                                    )
                                    Text(if (a.avgPowerW != null) "Výkon" else "Prům. tep", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                            }
                            else -> {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        fmt.km(a.distanceM),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    Text("Vzdálenost", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        a.movingTimeS?.let(fmt::duration) ?: "—",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    Text("Čas pohybu", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        fmt.pace(paceSPerKm),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = atmo.primaryColor
                                    )
                                    Text("Tempo", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                                Column(Modifier.weight(0.9f)) {
                                    Text(
                                        a.avgHeartRate?.let { "$it bpm" } ?: "—",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFFB7185)
                                    )
                                    Text("Prům. tep", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                            }
                        }
                    }
                }

                // Spodní pás Garmin Firstbeat telemetrie (čisté typografické kapsle bez ikonek)
                if (a.ascentM != null || a.caloriesKcal != null || a.aerobicTe != null || a.avgPowerW != null || a.trainingLoad != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        a.ascentM?.takeIf { it >= 5.0 }?.let { asc ->
                            TelemetryPillBadge(
                                text = "+${fmt.elevation(asc)}",
                                color = Color(0xFFFBBF24)
                            )
                        }
                        a.caloriesKcal?.takeIf { a.sport != Sport.STRENGTH }?.let { kcal ->
                            TelemetryPillBadge(
                                text = "${kcal.toInt()} kcal",
                                color = Color(0xFFF59E0B)
                            )
                        }
                        a.aerobicTe?.let { ae ->
                            val an = a.anaerobicTe ?: 0.0
                            TelemetryPillBadge(
                                text = "TE ${fmt.number(ae, 1)} · ${fmt.number(an, 1)}",
                                color = Color(0xFF10B981)
                            )
                        }
                        a.avgPowerW?.takeIf { a.sport != Sport.CYCLING }?.let { pwr ->
                            TelemetryPillBadge(
                                text = "${pwr.toInt()} W",
                                color = Color(0xFF38BDF8)
                            )
                        }
                        a.trainingLoad?.let { tl ->
                            TelemetryPillBadge(
                                text = "Zátěž ${tl.toInt()}",
                                color = Color(0xFFC084FC)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryPillBadge(text: String, color: Color) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(0.8.dp, color.copy(alpha = 0.42f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
        )
    }
}


private fun Channel.label() = when (this) {
    Channel.HEART_RATE -> "Srdeční tep"
    Channel.SPEED -> "Tempo / Rychlost"
    Channel.ELEVATION -> "Nadmořská výška"
    Channel.CADENCE -> "Kadence"
    Channel.POWER -> "Výkon"
    Channel.STRIDE_LENGTH -> "Délka kroku"
    Channel.GROUND_CONTACT_TIME -> "Doba kontaktu se zemí"
    Channel.GROUND_CONTACT_BALANCE -> "Vyváženost kontaktu L/P"
    Channel.VERTICAL_OSCILLATION -> "Vertikální oscilace"
    Channel.VERTICAL_RATIO -> "Vertikální poměr"
    Channel.STAMINA -> "Stamina (Výdrž)"
    Channel.PERFORMANCE_CONDITION -> "Zhodnocení kondice"
}

private fun Channel.unitLabel(): String = when (this) {
    Channel.HEART_RATE -> "bpm"
    Channel.SPEED -> "m/s"
    Channel.ELEVATION -> fmt.elevationUnit()
    Channel.CADENCE -> "spm"
    Channel.POWER -> "W"
    Channel.STRIDE_LENGTH -> "m"
    Channel.GROUND_CONTACT_TIME -> "ms"
    Channel.GROUND_CONTACT_BALANCE -> "%"
    Channel.VERTICAL_OSCILLATION -> "cm"
    Channel.VERTICAL_RATIO -> "%"
    Channel.STAMINA -> "%"
    Channel.PERFORMANCE_CONDITION -> ""
}

private fun Channel.color(): Color = when (this) {
    Channel.HEART_RATE -> Color(0xFFFF5252)
    Channel.SPEED -> Color(0xFF38BDF8)
    Channel.ELEVATION -> Color(0xFF10B981)
    Channel.CADENCE -> Color(0xFFA855F7)
    Channel.POWER -> Color(0xFFF59E0B)
    Channel.STRIDE_LENGTH -> Color(0xFF06B6D4)
    Channel.GROUND_CONTACT_TIME -> Color(0xFFEC4899)
    Channel.GROUND_CONTACT_BALANCE -> Color(0xFF8B5CF6)
    Channel.VERTICAL_OSCILLATION -> Color(0xFF14B8A6)
    Channel.VERTICAL_RATIO -> Color(0xFFF97316)
    Channel.STAMINA -> Color(0xFF0EA5E9)
    Channel.PERFORMANCE_CONDITION -> Color(0xFF10B981)
}

/**
 * Sjednocený model načtené aktivity pro Detail i Porovnání (Compare) – pokrývá vlastní i P2P sdílenou aktivitu!
 */
data class LoadedActivityDetail(
    val key: String,
    val sport: Sport,
    val title: String,
    val ownerName: String,
    val isShared: Boolean,
    val startUtcMs: Long?,
    val localDate: String,
    val totalTimeS: Double,
    val movingTimeS: Double,
    val distanceM: Double?,
    val ascentM: Double?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val track: List<TrackPoint>,
    val sharedProjection: ActivityProjection? = null,
    val domainActivity: Activity? = null,
    val relativeEffort: Int = 0,
    val bestEfforts: List<app.pulse4.analytics.BestEffort> = emptyList(),
    val gapPaceSPerKm: Double? = null,
)

fun loadUnifiedActivity(sl: ServiceLocator, key: String): LoadedActivityDetail? {
    android.util.Log.i("Pulse4", "loadUnifiedActivity called for key=$key")
    if (key.startsWith("shared:")) {
        val parts = key.split(":", limit = 3)
        if (parts.size < 3) return null
        val author = parts[1]
        val actId = parts[2]
        val row = sl.db.inbox().objectsNow("activity").firstOrNull { it.author == author && it.objectId == actId } ?: return null
        val p = runCatching { Wire.json.decodeFromString(ActivityProjection.serializer(), row.payload) }.getOrNull() ?: return null
        val sp = runCatching { Sport.valueOf(p.sport) }.getOrDefault(Sport.OTHER)
        val cName = sl.db.contacts().allNow().firstOrNull { it.identity == author }?.displayName ?: author.take(8)

        val hrPts = p.charts?.firstOrNull { it.name == "heartRate" }?.points.orEmpty()
        val spdPts = p.charts?.firstOrNull { it.name == "speed" }?.points.orEmpty()
        val elePts = p.charts?.firstOrNull { it.name == "elevation" }?.points.orEmpty()
        val cadPts = p.charts?.firstOrNull { it.name == "cadence" }?.points.orEmpty()
        val pwrPts = p.charts?.firstOrNull { it.name == "power" }?.points.orEmpty()
        val routePts = p.route.orEmpty()
        val maxLen = listOf(hrPts.size, spdPts.size, elePts.size, routePts.size).maxOrNull() ?: 0
        val reconstructed = if (maxLen > 0) {
            (0 until maxLen).map { i ->
                val tMs = hrPts.getOrNull(i)?.first ?: spdPts.getOrNull(i)?.first ?: (i * 1000L)
                val rp = if (routePts.isNotEmpty()) routePts.getOrNull((i * routePts.size) / maxLen) else null
                TrackPoint(
                    tMs = tMs,
                    lat = rp?.lat,
                    lon = rp?.lon,
                    elevationM = elePts.getOrNull(i)?.second,
                    heartRate = hrPts.getOrNull(i)?.second?.toInt(),
                    speedMps = spdPts.getOrNull(i)?.second,
                    cadence = cadPts.getOrNull(i)?.second?.toInt(),
                    powerW = pwrPts.getOrNull(i)?.second?.toInt(),
                    distanceM = null
                )
            }
        } else emptyList()

        val enrichedReconstructed = app.pulse4.analytics.RunningDynamicsModel.enrichTrack(
            track = reconstructed,
            sport = sp,
            avgCadence = cadPts.map { it.second }.average().takeIf { it.isFinite() },
            aerobicTE = p.aerobicTrainingEffect,
            anaerobicTE = p.anaerobicTrainingEffect,
            maxHr = hrPts.maxOfOrNull { it.second.toInt() }
        )

        val sharedHrZones = if (enrichedReconstructed.isNotEmpty() && hrPts.isNotEmpty()) {
            val maxRef = (hrPts.maxOfOrNull { it.second.toInt() } ?: 185).coerceAtLeast(150).toDouble()
            val bounds = listOf(maxRef * 0.60, maxRef * 0.70, maxRef * 0.80, maxRef * 0.90)
            app.pulse4.analytics.Zones.timeInZones(
                enrichedReconstructed.map { it.tMs to it.heartRate?.toDouble() },
                app.pulse4.analytics.Zones.ZoneSet(bounds)
            ).toList()
        } else emptyList()

        val eff = app.pulse4.analytics.RunningDynamicsModel.computeStravaRelativeEffort(
            hrZonesTimesS = sharedHrZones,
            totalTimeS = p.totalTimeS ?: p.movingTimeS ?: 0.0
        )
        val be = app.pulse4.analytics.RunningDynamicsModel.findBestEfforts(enrichedReconstructed)
        val nonNullPts = enrichedReconstructed.filter { it.distanceM != null && it.speedMps != null && it.elevationM != null }
        val gp = if (nonNullPts.size >= 2) {
            val avgGapSpd = nonNullPts.map {
                app.pulse4.analytics.RunningDynamicsModel.computeGapSpeedMps(it.speedMps ?: 0.0, 0.0)
            }.average()
            if (avgGapSpd > 0.2) 1000.0 / avgGapSpd else null
        } else null

        return LoadedActivityDetail(
            key = key,
            sport = sp,
            title = "${sp.czechName()} ($cName)",
            ownerName = cName,
            isShared = true,
            startUtcMs = p.startUtcMs,
            localDate = p.localDate,
            totalTimeS = p.totalTimeS ?: p.movingTimeS ?: 0.0,
            movingTimeS = p.movingTimeS ?: p.totalTimeS ?: 0.0,
            distanceM = p.distanceM,
            ascentM = p.ascentM,
            avgHeartRate = p.avgHeartRate,
            maxHeartRate = hrPts.maxOfOrNull { it.second.toInt() },
            track = enrichedReconstructed,
            sharedProjection = p,
            relativeEffort = eff,
            bestEfforts = be,
            gapPaceSPerKm = gp
        )
    } else {
        val row = sl.db.activities().get(key)
        if (row == null) {
            android.util.Log.w("Pulse4", "loadUnifiedActivity: row not found for key=$key in activities table")
            return null
        }
        val a = runCatching { Wire.json.decodeFromString(Activity.serializer(), row.json) }.getOrElse {
            android.util.Log.e("Pulse4", "loadUnifiedActivity: failed to decode Activity json for key=$key", it)
            return null
        }
        var t = sl.db.activities().track(key)?.let {
            runCatching { Wire.json.decodeFromString(ListSerializer(TrackPoint.serializer()), it.json) }.getOrElse { err ->
                android.util.Log.e("Pulse4", "loadUnifiedActivity: failed to decode track json for key=$key", err)
                null
            }
        }.orEmpty()
        if (t.isEmpty() && key.startsWith("garmin-") && sl.secrets.getString(app.pulse4.android.security.SecretStore.GARMIN_EXT_TOKENS) != null) {
            val fetched = runCatching { sl.importService.ensureActivityTrack(key) }.getOrDefault(false)
            if (fetched) {
                t = sl.db.activities().track(key)?.let {
                    runCatching { Wire.json.decodeFromString(ListSerializer(TrackPoint.serializer()), it.json) }.getOrNull()
                }.orEmpty()
            }
        }
        val enrichedLocal = app.pulse4.analytics.RunningDynamicsModel.enrichTrack(
            track = t,
            sport = a.sport,
            avgCadence = a.avgCadence,
            avgStrideLengthCm = a.avgStrideLengthCm,
            avgGroundContactTimeMs = a.avgGroundContactTimeMs,
            avgVerticalOscillationCm = a.avgVerticalOscillationCm,
            avgVerticalRatioPct = a.avgVerticalRatioPct,
            aerobicTE = a.aerobicTrainingEffect,
            anaerobicTE = a.anaerobicTrainingEffect,
            maxHr = a.maxHeartRate
        )

        val eff = app.pulse4.analytics.RunningDynamicsModel.computeStravaRelativeEffort(
            hrZonesTimesS = a.hrZoneTimesS ?: emptyList(),
            totalTimeS = a.totalTimeS
        )
        val be = app.pulse4.analytics.RunningDynamicsModel.findBestEfforts(enrichedLocal)
        val nonNullPts = enrichedLocal.filter { it.distanceM != null && it.speedMps != null && it.elevationM != null }
        val gp = if (nonNullPts.size >= 2) {
            val avgGapSpd = nonNullPts.map {
                app.pulse4.analytics.RunningDynamicsModel.computeGapSpeedMps(it.speedMps ?: 0.0, 0.0)
            }.average()
            if (avgGapSpd > 0.2) 1000.0 / avgGapSpd else null
        } else null

        return LoadedActivityDetail(
            key = key,
            sport = a.sport,
            title = a.title?.takeIf { it.isNotBlank() } ?: a.sport.czechName(),
            ownerName = "Já (${a.provider.name.lowercase()})",
            isShared = false,
            startUtcMs = a.startUtcMs,
            localDate = java.time.Instant.ofEpochMilli(a.startUtcMs).atZone(ZoneId.of(a.zoneId)).toLocalDate().toString(),
            totalTimeS = a.totalTimeS,
            movingTimeS = a.movingTimeS ?: a.totalTimeS,
            distanceM = a.distanceM,
            ascentM = a.ascentM,
            avgHeartRate = a.avgHeartRate,
            maxHeartRate = a.maxHeartRate,
            track = enrichedLocal,
            domainActivity = a,
            relativeEffort = eff,
            bestEfforts = be,
            gapPaceSPerKm = gp
        )
    }
}

internal fun formatTrainingEffectLabel(raw: String?): String = when (raw?.uppercase()) {
    "RECOVERY" -> "Regenerační přínos"
    "AEROBIC_BASE" -> "Budování aerobního základu"
    "TEMPO" -> "Zlepšení tempové vytrvalosti"
    "LACTATE_THRESHOLD" -> "Rozvoj laktátového prahu"
    "VO2MAX" -> "Rozvoj VO2 Max"
    "ANAEROBIC_CAPACITY" -> "Rozvoj anaerobní kapacity a síly"
    "SPEED" -> "Rozvoj maximální rychlosti"
    else -> raw?.replace('_', ' ') ?: "Komplexní tréninkový efekt"
}

private fun formatSplitTypeLabel(raw: String): String = when (raw.uppercase()) {
    "RWD_RUN" -> "Běh (Run/Walk detekce)"
    "RWD_WALK" -> "Chůze (Run/Walk detekce)"
    "RWD_STAND" -> "Stání / Pauza"
    "INTERVAL_ACTIVE" -> "Aktivní interval"
    "INTERVAL_WARMUP" -> "Rozklusání / Zahřátí"
    "INTERVAL_COOLDOWN" -> "Výklus / Zklidnění"
    "INTERVAL_RECOVERY" -> "Odpočinkový meziklus"
    "INTERVAL_REST" -> "Pauza mezi intervaly"
    else -> raw.replace('_', ' ')
}

/**
 * Komplexní detail aktivity ve stylu Garmin Connect:
 * - Interaktivní mapový podklad (MapLibre) s možností okamžitého přepnutí mapového providera
 * - Garmin Firstbeat Training Effect (Aerobní + Anaerobní gauge), Tréninková zátěž, Body Battery dopad, Intenzivní minuty
 * - Všechny Garmin grafy pod sebou (Srdeční tep, Tempo/Rychlost, Nadmořská výška, Kadence, Výkon) + režim detailní lupy
 * - Grafy Tepových zón (Z1–Z5) a Výkonových zón (Z1–Z5) s procenty a časy
 * - Graf a tabulka Mezičasů / Kol (Laps & Splits), Fáze pohybu (Běh/Chůze/Intervaly) a Nejrychlejší úseky (1 km, 1 míle, 5 km)
 * - Podrobné karty Běžecké dynamiky (Kadence, Délka kroku, Vertikální oscilace, GCT, Vertikální poměr), Výkonu, Převýšení a Kalorií/Hydratace
 */
@Composable
fun ActivityDetailScreen(sl: ServiceLocator, id: String, onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val units by sl.settings.unitSystemFlow.collectAsState()
    val mapProvider by sl.settings.mapProviderFlow.collectAsState()
    val customStyleUrl by io(mapProvider) { sl.settings.mapStyleUrl }

    val loaded by io(id to units) {
        try {
            val res = loadUnifiedActivity(sl, id)
            android.util.Log.i("Pulse4", "ActivityDetailScreen: loaded $id -> title=${res?.title}, pts=${res?.track?.size}")
            res
        } catch (e: Throwable) {
            android.util.Log.e("Pulse4", "ActivityDetailScreen: error loading $id", e)
            null
        }
    }
    var cursor by remember { mutableStateOf<Int?>(null) }
    var range by remember { mutableStateOf<IntRange?>(null) }
    var select by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf(Channel.HEART_RATE) }
    var axis by remember { mutableStateOf(Axis.TIME) }
    var movingOnly by remember { mutableStateOf(true) }
    var showAllChartsStacked by remember { mutableStateOf(true) }
    var detailSectionTab by remember { mutableStateOf(0) } // 0 = Vše pod sebou, 1 = Grafy & Zóny, 2 = Mezičasy & Kola, 3 = Garmin Statistiky
    var activeInfoTopic by remember { mutableStateOf<TelemetryTopic?>(null) }

    val act = loaded
    if (act == null) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(14.dp))
            Text("Načítání detailu aktivity…", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val dom = act.domainActivity
    val track = act.track
    val view = remember(track) { ActivityView(track) }
    val chart = remember(view, channel, axis) { view.chart(channel, axis) }
    val route = remember(view, act.sharedProjection) {
        act.sharedProjection?.route?.map { it.lat to it.lon } ?: view.route()
    }
    val trackCursor = cursor?.let { chart.trackIndex.getOrNull(it) }?.let { view.nearestWithPosition(it) }
    val trackRange = range?.let { chart.trackRange(it) }
    val summary = view.summary(trackRange, movingOnly)

    val dist = if (range != null) summary.distanceM else (act.distanceM ?: summary.distanceM)
    val durationS = if (range != null && track.isNotEmpty()) summary.timeS else (if (movingOnly) act.movingTimeS else act.totalTimeS)
    val pace = if (range != null) summary.paceSPerKm else (if (dist != null && dist > 0) (durationS / (dist / 1000.0)) else null)
    val ascent = if (range != null && track.isNotEmpty()) summary.ascentM else (act.ascentM ?: summary.ascentM)
    val descent = dom?.descentM ?: summary.descentM

    val stravaEffort = act.relativeEffort
    val stravaBestEfforts = act.bestEfforts
    val gapPace = act.gapPaceSPerKm
    val realPace = if (act.distanceM != null && act.movingTimeS > 0.0) act.movingTimeS / (act.distanceM / 1000.0) else null

    val detailAtmo = act.sport.atmosphere(act.title)
    val speedKmh = if (dist != null && dist > 0 && durationS > 0) (dist / durationS) * 3.6 else null
    val aeValHero = dom?.aerobicTrainingEffect ?: act.sharedProjection?.aerobicTrainingEffect
    val anValHero = dom?.anaerobicTrainingEffect ?: act.sharedProjection?.anaerobicTrainingEffect

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        // 1. IMERZIVNÍ SPORTOVNÍ HERO SCÉNA S INTEGROVANÝM SKLENĚNÝM DOKEM HLAVNÍ TELEMETRIE (BEZ IKONEK)
        Card(
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(
                1.4.dp,
                Brush.horizontalGradient(
                    listOf(
                        detailAtmo.primaryColor.copy(alpha = 0.78f),
                        detailAtmo.secondaryColor.copy(alpha = 0.42f),
                        Color(0xFF1E293B).copy(alpha = 0.6f)
                    )
                )
            ),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF050911)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = detailAtmo.artworkRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(0.84f)
                )
                Canvas(Modifier.matchParentSize()) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color(0x88040810),
                            0.38f to Color(0xAA060B14),
                            0.72f to Color(0xD9050911),
                            1.0f to Color(0xF5050911)
                        )
                    )
                    drawSportTelemetryOverlay(
                        sport = act.sport,
                        primary = detailAtmo.primaryColor,
                        secondary = detailAtmo.secondaryColor
                    )
                }
                Column(Modifier.padding(18.dp)) {
                    // Horní lišta: Návrat + Disciplína + P2P odznak
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xCC060C16),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                            modifier = Modifier.clickable { onBack() }
                        ) {
                            Text(
                                "← Zpět na aktivity",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.5.dp)
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            androidx.compose.material3.Surface(
                                shape = RoundedCornerShape(50),
                                color = Color(0xCC060C16),
                                border = BorderStroke(1.dp, detailAtmo.primaryColor.copy(alpha = 0.65f))
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        Modifier
                                            .size(7.dp)
                                            .background(detailAtmo.primaryColor, CircleShape)
                                    )
                                    Text(
                                        detailAtmo.disciplineTag,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White,
                                        letterSpacing = 0.8.sp,
                                        fontSize = 9.5.sp
                                    )
                                }
                            }
                            if (act.isShared) {
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color(0xCC06281E),
                                    border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.6f))
                                ) {
                                    Text(
                                        "P2P · ${act.ownerName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF34D399),
                                        fontSize = 9.5.sp,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Titulek a kontextový podtitul
                    Text(
                        detailAtmo.worldSubtitle.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = detailAtmo.secondaryColor,
                        letterSpacing = 1.1.sp,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        act.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        buildString {
                            if (dom?.locationName != null) append("${dom.locationName} · ")
                            append("${act.startUtcMs?.let(fmt::time) ?: act.localDate} · ${act.ownerName}")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFCBD5E1)
                    )

                    Spacer(Modifier.height(16.dp))

                    // Integrovaný skleněný dok 6 klíčových ukazatelů přímo v Hero scéně
                    androidx.compose.material3.Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xC8050A12),
                        border = BorderStroke(
                            1.dp,
                            Brush.horizontalGradient(
                                listOf(
                                    detailAtmo.primaryColor.copy(alpha = 0.45f),
                                    Color.White.copy(alpha = 0.10f)
                                )
                            )
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            if (range != null) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "AKTIVNÍ VÝŘEZ ÚSEKU NA GRAFU",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF38BDF8),
                                        fontSize = 9.5.sp
                                    )
                                    Text(
                                        "Zrušit výřez ✕",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFB7185),
                                        modifier = Modifier.clickable { range = null }
                                    )
                                }
                            }
                            // 1. řada hlavních metrik
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (act.sport == Sport.STRENGTH && (dist == null || dist <= 0.0)) {
                                            dom?.steps?.let { "$it" } ?: "—"
                                        } else {
                                            fmt.km(dist)
                                        },
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White
                                    )
                                    Text(
                                        if (act.sport == Sport.STRENGTH && (dist == null || dist <= 0.0)) "Kroky / Opak." else "Vzdálenost",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        fmt.duration(durationS),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White
                                    )
                                    Text(
                                        if (movingOnly) "Čas pohybu" else "Celkový čas",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    val thirdValue = when (act.sport) {
                                        Sport.CYCLING -> speedKmh?.let { "${fmt.number(it, 1)} km/h" } ?: fmt.pace(pace)
                                        Sport.STRENGTH -> dom?.avgPowerW?.let { "${it.toInt()} W" } ?: (dom?.trainingLoad?.let { "TL ${it.toInt()}" } ?: "—")
                                        else -> fmt.pace(pace)
                                    }
                                    val thirdLabel = when (act.sport) {
                                        Sport.CYCLING -> "Prům. rychlost"
                                        Sport.STRENGTH -> if (dom?.avgPowerW != null) "Prům. výkon" else "Zátěž"
                                        else -> "Prům. tempo"
                                    }
                                    Text(
                                        thirdValue,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Black,
                                        color = detailAtmo.primaryColor
                                    )
                                    Text(thirdLabel, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                            }

                            Spacer(Modifier.height(10.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color.White.copy(alpha = 0.07f))
                            )
                            Spacer(Modifier.height(10.dp))

                            // 2. řada hlavních metrik
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        act.avgHeartRate?.let { "$it bpm" } ?: "—",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFFB7185)
                                    )
                                    Text(
                                        "Prům. tep (max ${act.maxHeartRate ?: "—"})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "+${fmt.elevation(ascent)}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFFBBF24)
                                    )
                                    Text(
                                        "Stoupání (-${fmt.elevation(descent)})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        dom?.caloriesKcal?.let { "${it.toInt()} kcal" } ?: "—",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF34D399)
                                    )
                                    Text(
                                        "Spálené kalorie",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }

                    // Spodní rychlé telemetrické kapsle v Hero kartě
                    if (aeValHero != null || dom?.trainingLoad != null || dom?.avgPowerW != null || stravaEffort > 0) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            aeValHero?.let { ae ->
                                val an = anValHero ?: 0.0
                                TelemetryPillBadge(
                                    text = "Firstbeat TE ${fmt.number(ae, 1)} / ${fmt.number(an, 1)}",
                                    color = Color(0xFF34D399)
                                )
                            }
                            dom?.trainingLoad?.let { tl ->
                                TelemetryPillBadge(
                                    text = "Zátěž TL ${tl.toInt()}",
                                    color = Color(0xFF38BDF8)
                                )
                            }
                            dom?.avgPowerW?.let { pwr ->
                                TelemetryPillBadge(
                                    text = "Výkon ${pwr.toInt()} W",
                                    color = Color(0xFFFBBF24)
                                )
                            }
                            if (stravaEffort > 0) {
                                TelemetryPillBadge(
                                    text = "Kardio úsilí $stravaEffort",
                                    color = Color(0xFFFB7185)
                                )
                            }
                            gapPace?.let { gp ->
                                TelemetryPillBadge(
                                    text = "GAP ${fmt.pace(gp)}",
                                    color = Color(0xFFC084FC)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 2. SKLENĚNÁ NAVIGAČNÍ LIŠTA 4 TELEMETRICKÝCH SVĚTŮ DETAILU (BEZ IKONEK)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                0 to "Přehled & Mapa",
                1 to "Grafy & Zóny",
                2 to "Biomechanika & Data",
                3 to "Mezičasy & Kola"
            ).forEach { (idx, label) ->
                val isSelected = detailSectionTab == idx
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isSelected) Color(0xFF0D1829) else Color(0x99070C14),
                    border = BorderStroke(
                        if (isSelected) 1.4.dp else 1.dp,
                        if (isSelected) {
                            Brush.horizontalGradient(listOf(detailAtmo.primaryColor, detailAtmo.secondaryColor))
                        } else {
                            Brush.horizontalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
                        }
                    ),
                    modifier = Modifier.clickable { detailSectionTab = idx }
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(
                            Modifier
                                .size(if (isSelected) 7.dp else 5.dp)
                                .background(
                                    if (isSelected) detailAtmo.primaryColor else Color(0xFF475569),
                                    CircleShape
                                )
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            color = if (isSelected) Color.White else Color(0xFF94A3B8),
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // =========================================================================================
        // SEKCE 0: PŘEHLED & MAPA (Interaktivní GPS trasa + Firstbeat Analytics + Stamina + Milníky)
        // =========================================================================================
        if (detailSectionTab == 0) {
            // Mapa trasy ve skleněném obsidiánovém rámu s přepínačem mapových vrstev
            Card(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(
                    1.2.dp,
                    Brush.horizontalGradient(
                        listOf(detailAtmo.primaryColor.copy(alpha = 0.48f), Color(0xFF1E293B))
                    )
                ),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    val pts = route.filterNotNull()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(detailAtmo.primaryColor, CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "GPS TELEMETRIE & MAPA TRASY",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = detailAtmo.secondaryColor,
                                letterSpacing = 0.8.sp
                            )
                        }
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xCC0A1220),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                        ) {
                            Text(
                                if (pts.isNotEmpty()) "${pts.size} GPS bodů · ${mapProvider.shortLabel}" else "Indoor / Bez GPS záznamu",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (pts.isNotEmpty()) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.5.dp)
                            )
                        }
                    }

                    // Skleněný přepínač mapového podkladu (bez generických FilterChipů)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            MapProvider.CARTO_DARK,
                            MapProvider.OSM_STANDARD,
                            MapProvider.OPENTOPOMAP,
                            MapProvider.CARTO_VOYAGER,
                            MapProvider.ESRI_SATELLITE,
                            MapProvider.OFFLINE_CANVAS
                        ).forEach { mp ->
                            val isSelected = mapProvider == mp
                            androidx.compose.material3.Surface(
                                shape = RoundedCornerShape(50),
                                color = if (isSelected) detailAtmo.primaryColor.copy(alpha = 0.22f) else Color(0xCC0A111D),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) detailAtmo.primaryColor else Color(0xFF1E293B)
                                ),
                                modifier = Modifier.clickable {
                                    scope.launch(Dispatchers.IO) {
                                        sl.settings.mapProvider = mp
                                    }
                                }
                            ) {
                                Text(
                                    mp.shortLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                    fontSize = 10.5.sp,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    if (pts.size >= 2) {
                        val cursorCoord = trackCursor?.let { route.getOrNull(it) }
                        val highlightPts = trackRange?.let { rng ->
                            route.subList(rng.first.coerceIn(0, route.size), (rng.last + 1).coerceIn(0, route.size)).filterNotNull()
                        }.orEmpty()

                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
                        ) {
                            if (mapProvider != MapProvider.OFFLINE_CANVAS) {
                                MapLibreRoute(
                                    provider = mapProvider,
                                    customStyleUrl = customStyleUrl,
                                    route = pts,
                                    cursorCoord = cursorCoord,
                                    highlightSegment = highlightPts
                                )
                            } else {
                                RouteCanvas(route, cursor = trackCursor, range = trackRange)
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(Color(0xFF10B981), CircleShape))
                                Spacer(Modifier.width(5.dp))
                                Text("Start trasy", style = MaterialTheme.typography.labelSmall, color = Color(0xFF34D399), fontWeight = FontWeight.Bold)
                            }
                            Text(mapProvider.attribution, style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B), fontSize = 9.5.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(Color(0xFFFB7185), CircleShape))
                                Spacer(Modifier.width(5.dp))
                                Text("Cíl trasy", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFB7185), fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xB8080F1C),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    "Indoor / Stacionární aktivita bez GPS křivky",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "Veškerá fyziologická telemetrie (srdeční tep, výkon, kadence, kalorie a tréninkový efekt) je plně dostupná v kartách níže a v záložce Grafy & Zóny.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Garmin Firstbeat Training Effect, Stamina, Kondice, Kardio úsilí, GAP a Best Efforts
            val aeVal = dom?.aerobicTrainingEffect ?: act.sharedProjection?.aerobicTrainingEffect
            val anVal = dom?.anaerobicTrainingEffect ?: act.sharedProjection?.anaerobicTrainingEffect
            val teLbl = dom?.trainingEffectLabel ?: act.sharedProjection?.trainingEffectLabel
            val aeMsg = dom?.aerobicTrainingEffectMessage
            val anMsg = dom?.anaerobicTrainingEffectMessage

            if (aeVal != null || anVal != null || dom?.trainingLoad != null || dom?.bodyBatteryImpact != null) {
                GarminTrainingEffectCard(
                    aerobic = aeVal ?: 2.8,
                    anaerobic = anVal ?: 0.5,
                    label = teLbl,
                    aerobicMsg = aeMsg,
                    anaerobicMsg = anMsg,
                    trainingLoad = dom?.trainingLoad,
                    bodyBatteryImpact = dom?.bodyBatteryImpact,
                    moderateMinutes = dom?.moderateIntensityMinutes,
                    vigorousMinutes = dom?.vigorousIntensityMinutes
                )
                Spacer(Modifier.height(12.dp))
            }

            val curStam = track.lastOrNull { it.staminaPct != null }?.staminaPct
            val potStam = track.lastOrNull { it.staminaPotentialPct != null }?.staminaPotentialPct
            if (curStam != null && potStam != null) {
                GarminStaminaCard(
                    currentStaminaPct = curStam,
                    potentialStaminaPct = potStam,
                    distanceM = act.distanceM,
                    durationS = act.totalTimeS
                )
                Spacer(Modifier.height(12.dp))
            }

            val perfCond = track.lastOrNull { it.performanceCondition != null }?.performanceCondition
            if (perfCond != null) {
                GarminPerformanceConditionCard(condition = perfCond)
                Spacer(Modifier.height(12.dp))
            }

            RelativeEffortCard(
                score = stravaEffort,
                hrZonesTimesS = dom?.hrZoneTimesS ?: emptyList(),
                totalTimeS = act.totalTimeS
            )
            Spacer(Modifier.height(12.dp))

            if (gapPace != null) {
                GapCard(
                    realPaceSPerKm = realPace,
                    gapPaceSPerKm = gapPace,
                    ascentM = act.ascentM
                )
                Spacer(Modifier.height(12.dp))
            }

            if (stravaBestEfforts.isNotEmpty()) {
                BestEffortsCard(bestEfforts = stravaBestEfforts)
                Spacer(Modifier.height(12.dp))
            }

            // Atmosférické portály do hloubkových sekcí (bez ikonek)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { detailSectionTab = 1 },
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13))
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = R.drawable.img_motif_heart_pulse),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0.55f)
                        )
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(Color(0xAA050911), Color(0xEE050911))
                                )
                            )
                        }
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(7.dp).background(Color(0xFF38BDF8), CircleShape))
                                Text(
                                    "GRAFY & ZÓNY ›",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF38BDF8),
                                    letterSpacing = 0.8.sp
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Křivky & Z1–Z5",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Tep, tempo, výška, kadence, výkon a spektrum zón",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { detailSectionTab = 2 },
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, detailAtmo.primaryColor.copy(alpha = 0.5f)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13))
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = detailAtmo.artworkRes),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0.55f)
                        )
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(Color(0xAA050911), Color(0xEE050911))
                                )
                            )
                        }
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(7.dp).background(detailAtmo.primaryColor, CircleShape))
                                Text(
                                    "BIOMECHANIKA ›",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = detailAtmo.secondaryColor,
                                    letterSpacing = 0.8.sp
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Dynamika & Data",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Kadence, krok, GCT, symetrie L/P, watty a kalorie",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // =========================================================================================
        // SEKCE 1: GRAFY & ZÓNY (Synchronizované křivky v obsidiánových kartách + Spektrum Z1–Z5)
        // =========================================================================================
        if (detailSectionTab == 1) {
            val availableChannels = Channel.entries.filter { view.hasChannel(it) && it != Channel.GROUND_CONTACT_BALANCE }
            Card(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(
                    1.2.dp,
                    Brush.horizontalGradient(listOf(detailAtmo.primaryColor.copy(alpha = 0.55f), Color(0xFF38BDF8).copy(alpha = 0.35f)))
                ),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = detailAtmo.artworkRes),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(0.38f)
                    )
                    Canvas(Modifier.matchParentSize()) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.0f to Color(0xCC050911),
                                1.0f to Color(0xF5050911)
                            )
                        )
                    }
                    Column(Modifier.padding(16.dp)) {
                        // Hlavička studia křivek + přepínač Osy X (Čas / Vzdálenost)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(detailAtmo.primaryColor, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "TELEMETRICKÉ KŘIVKY (${availableChannels.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = detailAtmo.secondaryColor,
                                    letterSpacing = 0.8.sp
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    Axis.TIME to "Osa: Čas",
                                    Axis.DISTANCE to "Osa: Vzdál."
                                ).forEach { (ax, lbl) ->
                                    val sel = axis == ax
                                    androidx.compose.material3.Surface(
                                        shape = RoundedCornerShape(50),
                                        color = if (sel) detailAtmo.primaryColor.copy(alpha = 0.22f) else Color(0xCC08101C),
                                        border = BorderStroke(1.dp, if (sel) detailAtmo.primaryColor else Color(0xFF1E293B)),
                                        modifier = Modifier.clickable { axis = ax; range = null }
                                    ) {
                                        Text(
                                            lbl,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.Medium,
                                            color = if (sel) Color.White else Color(0xFF94A3B8),
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.5.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Přepínač režimu: Všechny grafy pod sebou vs. Detailní lupa úseku
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                true to "Všechny křivky pod sebou",
                                false to "Lupa & Výřez úseku"
                            ).forEach { (mode, lbl) ->
                                val sel = showAllChartsStacked == mode
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (sel) Color(0xFF0E1D34) else Color(0x99070D17),
                                    border = BorderStroke(1.dp, if (sel) Color(0xFF38BDF8) else Color(0xFF1E293B)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { showAllChartsStacked = mode }
                                ) {
                                    Box(
                                        Modifier.padding(vertical = 7.dp, horizontal = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            lbl,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.Medium,
                                            color = if (sel) Color.White else Color(0xFF94A3B8)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        if (availableChannels.isEmpty()) {
                            Text(
                                "Pro tento záznam nejsou k dispozici bodové křivky.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        } else if (showAllChartsStacked) {
                            // Vykreslit VŠECHNY dostupné Garmin grafy pod sebou v samostatných skleněných kartách
                            availableChannels.forEach { ch ->
                                val chChart = remember(view, ch, axis) { view.chart(ch, axis, buckets = 160) }
                                val chartStats: String? = remember(chChart, ch) {
                                    val finite = chChart.values.filterNotNull().filter { it.isFinite() }
                                    if (finite.isEmpty()) null
                                    else {
                                        val avgVal = finite.average()
                                        val maxVal = finite.max()
                                        val minVal = finite.min()
                                        when (ch) {
                                            Channel.HEART_RATE -> "Průměr ${fmt.number(avgVal, 0)} bpm · Max ${fmt.number(maxVal, 0)} bpm"
                                            Channel.SPEED -> {
                                                val avgPace = if (avgVal > 0.2) 1000.0 / avgVal else null
                                                val maxKmh = maxVal * 3.6
                                                "Prům. ${fmt.pace(avgPace)} · Max ${fmt.number(maxKmh, 1)} km/h"
                                            }
                                            Channel.ELEVATION -> "Min ${fmt.elevation(minVal)} · Max ${fmt.elevation(maxVal)}"
                                            Channel.CADENCE -> "Průměr ${fmt.number(avgVal, 0)} spm · Max ${fmt.number(maxVal, 0)} spm"
                                            Channel.POWER -> "Průměr ${fmt.number(avgVal, 0)} W · Max ${fmt.number(maxVal, 0)} W"
                                            Channel.STRIDE_LENGTH -> "Průměr ${fmt.number(avgVal, 2)} m · Max ${fmt.number(maxVal, 2)} m"
                                            Channel.GROUND_CONTACT_TIME -> "Průměr ${fmt.number(avgVal, 0)} ms · Min ${fmt.number(minVal, 0)} ms"
                                            Channel.GROUND_CONTACT_BALANCE -> "Průměr ${fmt.number(avgVal, 1)} % L / ${fmt.number(100.0 - avgVal, 1)} % P"
                                            Channel.VERTICAL_OSCILLATION -> "Průměr ${fmt.number(avgVal, 1)} cm · Max ${fmt.number(maxVal, 1)} cm"
                                            Channel.VERTICAL_RATIO -> "Průměr ${fmt.number(avgVal, 1)} % · Min ${fmt.number(minVal, 1)} %"
                                            Channel.STAMINA -> "Konec ${fmt.number(finite.last(), 0)} % · Min ${fmt.number(minVal, 0)} %"
                                            Channel.PERFORMANCE_CONDITION -> "Start ${fmt.number(finite.first(), 0)} · Konec ${fmt.number(finite.last(), 0)}"
                                        }
                                    }
                                }
                                if (chartStats != null) {
                                    androidx.compose.material3.Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = Color(0xCC070D18),
                                        border = BorderStroke(1.dp, ch.color().copy(alpha = 0.32f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 5.dp)
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Box(
                                                        Modifier
                                                            .size(8.dp)
                                                            .background(ch.color(), CircleShape)
                                                    )
                                                    Spacer(Modifier.width(7.dp))
                                                    Text(
                                                        ch.label(),
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = Color.White
                                                    )
                                                    ch.toTelemetryTopic()?.let { topic ->
                                                        Spacer(Modifier.width(6.dp))
                                                        InfoBadgeButton(onClick = { activeInfoTopic = topic })
                                                    }
                                                }
                                                androidx.compose.material3.Surface(
                                                    shape = RoundedCornerShape(50),
                                                    color = ch.color().copy(alpha = 0.14f),
                                                    border = BorderStroke(0.8.dp, ch.color().copy(alpha = 0.4f))
                                                ) {
                                                    Text(
                                                        chartStats,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = ch.color(),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 9.5.sp,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            val secVals = remember(chChart, track, ch) {
                                                if (ch == Channel.STAMINA) {
                                                    chChart.trackIndex.map { ti -> track.getOrNull(ti)?.staminaPotentialPct }
                                                } else null
                                            }
                                            LineChart(
                                                values = chChart.values,
                                                cursor = cursor,
                                                color = ch.color(),
                                                isScatter = ch.isScatter(),
                                                unit = ch.unitLabel(),
                                                channel = ch,
                                                secondaryValues = secVals,
                                                secondaryLabel = if (ch == Channel.STAMINA) "Potenciální" else "",
                                                onCursor = { cursor = it }
                                            )
                                        }
                                    }
                                }
                            }

                            cursor?.let { c ->
                                chart.trackIndex.getOrNull(c)?.let { ti ->
                                    Spacer(Modifier.height(8.dp))
                                    androidx.compose.material3.Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color(0xE60A1322),
                                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.55f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Box(Modifier.size(7.dp).background(Color(0xFF38BDF8), CircleShape))
                                                    Text(
                                                        "SYNCHRONIZOVANÝ ODEČET BODU NA KŘIVKÁCH",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = Color(0xFF38BDF8)
                                                    )
                                                }
                                                Text(
                                                    "Zavřít ✕",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF94A3B8),
                                                    modifier = Modifier.clickable { cursor = null }
                                                )
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            view.point(ti, fmt).forEach { (k, v) -> LabeledRow(k, v) }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Režim jednoho grafu s označováním úseku
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FlowRow(
                                    Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    availableChannels.forEach { c ->
                                        val isSel = channel == c
                                        androidx.compose.material3.Surface(
                                            shape = RoundedCornerShape(50),
                                            color = if (isSel) c.color().copy(alpha = 0.22f) else Color(0xCC08101C),
                                            border = BorderStroke(1.dp, if (isSel) c.color() else Color(0xFF1E293B)),
                                            modifier = Modifier.clickable { channel = c }
                                        ) {
                                            Row(
                                                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                Box(Modifier.size(6.dp).background(c.color(), CircleShape))
                                                Text(
                                                    c.label(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = if (isSel) FontWeight.ExtraBold else FontWeight.Medium,
                                                    color = if (isSel) Color.White else Color(0xFF94A3B8)
                                                )
                                            }
                                        }
                                    }
                                }
                                channel.toTelemetryTopic()?.let { topic ->
                                    InfoBadgeButton(onClick = { activeInfoTopic = topic }, modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            val secValsSingle = remember(chart, track) {
                                if (channel == Channel.STAMINA) {
                                    chart.trackIndex.map { ti -> track.getOrNull(ti)?.staminaPotentialPct }
                                } else null
                            }
                            LineChart(
                                chart.values,
                                cursor = cursor,
                                range = range,
                                selectMode = select,
                                color = channel.color(),
                                isScatter = channel.isScatter(),
                                unit = channel.unitLabel(),
                                channel = channel,
                                secondaryValues = secValsSingle,
                                secondaryLabel = if (channel == Channel.STAMINA) "Potenciální" else "",
                                onCursor = { cursor = it },
                                onRange = { range = it }
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Označit úsek tažením", style = MaterialTheme.typography.bodySmall, color = Color(0xFFCBD5E1))
                                    Switch(select, { select = it; if (!it) range = null }, Modifier.padding(start = 6.dp))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Jen pohybový čas", style = MaterialTheme.typography.bodySmall, color = Color(0xFFCBD5E1))
                                    Switch(movingOnly, { movingOnly = it }, Modifier.padding(start = 6.dp))
                                }
                            }
                            if (range != null) {
                                Spacer(Modifier.height(8.dp))
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xE60A1322),
                                    border = BorderStroke(1.dp, channel.color().copy(alpha = 0.55f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            "SOUHRN OZNAČENÉHO ÚSEKU",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = channel.color()
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        LabeledRow("Čas úseku", fmt.duration(summary.timeS))
                                        LabeledRow("Vzdálenost úseku", fmt.km(summary.distanceM))
                                        LabeledRow("Tempo úseku", fmt.pace(summary.paceSPerKm))
                                        LabeledRow("Převýšení", "+${fmt.elevation(summary.ascentM)} / -${fmt.elevation(summary.descentM)}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 5. SPEKTRUM TEPOVÝCH ZÓN (Z1–Z5) A VÝKONOVÝCH ZÓN (Z1–Z5) S ATMOSFÉRICKÝM POZADÍM
            val hrZones = remember(dom, track, act.maxHeartRate) {
                val fromDom = dom?.hrZoneTimesS.orEmpty()
                if (fromDom.size == 5 && fromDom.any { it > 0.0 }) fromDom
                else if (track.size >= 2 && track.any { it.heartRate != null }) {
                    val maxRef = (act.maxHeartRate ?: 185).coerceAtLeast(150).toDouble()
                    val bounds = listOf(maxRef * 0.60, maxRef * 0.70, maxRef * 0.80, maxRef * 0.90)
                    app.pulse4.analytics.Zones.timeInZones(
                        track.map { it.tMs to it.heartRate?.toDouble() },
                        app.pulse4.analytics.Zones.ZoneSet(bounds)
                    ).toList()
                } else emptyList()
            }

            if (hrZones.size == 5 && hrZones.sum() > 0.0) {
                val totalZ = hrZones.sum().coerceAtLeast(1.0)
                val zoneConfigs = listOf(
                    Triple("Zóna 5 · Maximum (>90 %)", hrZones[4], Color(0xFFEF4444)),
                    Triple("Zóna 4 · Práh (80–90 %)", hrZones[3], Color(0xFFF97316)),
                    Triple("Zóna 3 · Aerobní (70–80 %)", hrZones[2], Color(0xFF10B981)),
                    Triple("Zóna 2 · Vytrvalost (60–70 %)", hrZones[1], Color(0xFF38BDF8)),
                    Triple("Zóna 1 · Regenerace (50–60 %)", hrZones[0], Color(0xFF94A3B8))
                )
                Card(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(
                        1.2.dp,
                        Brush.horizontalGradient(listOf(Color(0xFFFB7185).copy(alpha = 0.55f), Color(0xFFF97316).copy(alpha = 0.4f)))
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = R.drawable.img_motif_heart_pulse),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0.55f)
                        )
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color(0xBF050911),
                                    1.0f to Color(0xF2050911)
                                )
                            )
                        }
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(9.dp).background(Color(0xFFFB7185), CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "SPEKTRUM TEPOVÝCH ZÓN · GARMIN Z1–Z5",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFFB7185),
                                        letterSpacing = 0.8.sp
                                    )
                                }
                                InfoBadgeButton(onClick = { activeInfoTopic = TelemetryTopic.HR_ZONES })
                            }

                            Spacer(Modifier.height(12.dp))

                            // Složená spektrální lišta Z1–Z5 (vizuální poměr všech zón najednou)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF0F172A))
                            ) {
                                zoneConfigs.reversed().forEach { (_, zSec, zColor) ->
                                    val wFrac = (zSec / totalZ).toFloat().coerceAtLeast(0.01f)
                                    if (zSec > 0.0) {
                                        Box(
                                            Modifier
                                                .weight(wFrac)
                                                .height(12.dp)
                                                .background(zColor)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            zoneConfigs.forEach { (zTitle, zSec, zColor) ->
                                val pct = (zSec / totalZ).toFloat().coerceIn(0f, 1f)
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color(0xB8060C16),
                                    border = BorderStroke(1.dp, zColor.copy(alpha = if (pct > 0.15f) 0.38f else 0.15f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                ) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Box(Modifier.size(7.dp).background(zColor, CircleShape))
                                                Text(zTitle, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.White)
                                            }
                                            Text(
                                                "${fmt.duration(zSec)} · ${(pct * 100).toInt()} %",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = zColor,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        LinearProgressIndicator(
                                            progress = { pct },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = zColor,
                                            trackColor = Color(0xFF1E293B)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Výkonové zóny (pokud jsou k dispozici)
            val pwrZones = dom?.powerZoneTimesS.orEmpty()
            if (pwrZones.size == 5 && pwrZones.sum() > 0.0) {
                val totalP = pwrZones.sum().coerceAtLeast(1.0)
                val pwrConfigs = listOf(
                    Triple("Zóna 5 · Anaerobní výkon", pwrZones[4], Color(0xFFEC4899)),
                    Triple("Zóna 4 · Prahový výkon (FTP)", pwrZones[3], Color(0xFFF59E0B)),
                    Triple("Zóna 3 · Tempový výkon", pwrZones[2], Color(0xFF10B981)),
                    Triple("Zóna 2 · Vytrvalostní výkon", pwrZones[1], Color(0xFF38BDF8)),
                    Triple("Zóna 1 · Aktivní regenerace", pwrZones[0], Color(0xFF94A3B8))
                )
                Card(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(
                        1.2.dp,
                        Brush.horizontalGradient(listOf(Color(0xFFF59E0B).copy(alpha = 0.55f), Color(0xFFEC4899).copy(alpha = 0.4f)))
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = R.drawable.img_sport_cycling_hero),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0.50f)
                        )
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color(0xBF050911),
                                    1.0f to Color(0xF2050911)
                                )
                            )
                        }
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(9.dp).background(Color(0xFFF59E0B), CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "SPEKTRUM VÝKONOVÝCH ZÓN · WATTY Z1–Z5",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFFBBF24),
                                        letterSpacing = 0.8.sp
                                    )
                                }
                                InfoBadgeButton(onClick = { activeInfoTopic = TelemetryTopic.POWER_ZONES })
                            }
                            Spacer(Modifier.height(12.dp))
                            pwrConfigs.forEach { (zTitle, zSec, zColor) ->
                                val pct = (zSec / totalP).toFloat().coerceIn(0f, 1f)
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color(0xB8060C16),
                                    border = BorderStroke(1.dp, zColor.copy(alpha = if (pct > 0.15f) 0.38f else 0.15f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                ) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Box(Modifier.size(7.dp).background(zColor, CircleShape))
                                                Text(zTitle, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.White)
                                            }
                                            Text(
                                                "${fmt.duration(zSec)} · ${(pct * 100).toInt()} %",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = zColor,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        LinearProgressIndicator(
                                            progress = { pct },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = zColor,
                                            trackColor = Color(0xFF1E293B)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // =========================================================================================
        // SEKCE 3: MEZIČASY & KOLA (Rekordní úseky + Telemetrická matice kol + Fáze pohybu)
        // =========================================================================================
        if (detailSectionTab == 3) {
            // Nejrychlejší úseky (Best Splits z Garminu)
            if (dom?.fastestSplit1kS != null || dom?.fastestSplit1miS != null || dom?.fastestSplit5kS != null) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(
                        1.2.dp,
                        Brush.horizontalGradient(listOf(Color(0xFFF59E0B).copy(alpha = 0.6f), detailAtmo.primaryColor.copy(alpha = 0.4f)))
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = detailAtmo.artworkRes),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0.55f)
                        )
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color(0xBF050911),
                                    1.0f to Color(0xF2050911)
                                )
                            )
                        }
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(9.dp).background(Color(0xFFF59E0B), CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "GARMIN BEST SPLITS · NEJRYCHLEJŠÍ ÚSEKY",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFFBBF24),
                                        letterSpacing = 0.8.sp
                                    )
                                }
                                InfoBadgeButton(onClick = { activeInfoTopic = TelemetryTopic.BEST_EFFORTS })
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                dom.fastestSplit1kS?.let {
                                    androidx.compose.material3.Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color(0xCC060C16),
                                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text("1 KM REKORD", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFBBF24), fontSize = 9.5.sp)
                                            Spacer(Modifier.height(4.dp))
                                            Text(fmt.duration(it), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
                                        }
                                    }
                                }
                                dom.fastestSplit1miS?.let {
                                    androidx.compose.material3.Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color(0xCC060C16),
                                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text("1 MÍLE REKORD", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = Color(0xFF38BDF8), fontSize = 9.5.sp)
                                            Spacer(Modifier.height(4.dp))
                                            Text(fmt.duration(it), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
                                        }
                                    }
                                }
                                dom.fastestSplit5kS?.let {
                                    androidx.compose.material3.Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color(0xCC060C16),
                                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text("5 KM REKORD", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = Color(0xFF34D399), fontSize = 9.5.sp)
                                            Spacer(Modifier.height(4.dp))
                                            Text(fmt.duration(it), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Tabulka a graf Kol / Mezičasů (Laps) v atmosférické kartě
            val laps = dom?.laps.orEmpty()
            if (laps.isNotEmpty()) {
                val maxSpeedInLaps = laps.mapNotNull { it.avgSpeedMps }.maxOrNull()?.coerceAtLeast(0.5) ?: 4.0
                val fastestLapIdx = laps.maxByOrNull { it.avgSpeedMps ?: 0.0 }?.index

                Card(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(
                        1.2.dp,
                        Brush.horizontalGradient(listOf(detailAtmo.primaryColor.copy(alpha = 0.55f), Color(0xFF1E293B)))
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = detailAtmo.artworkRes),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0.38f)
                        )
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color(0xCC050911),
                                    1.0f to Color(0xF5050911)
                                )
                            )
                        }
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(9.dp).background(detailAtmo.primaryColor, CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "TELEMETRIE MEZIČASŮ A KOL (${laps.size}×)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = detailAtmo.secondaryColor,
                                        letterSpacing = 0.8.sp
                                    )
                                }
                                InfoBadgeButton(onClick = { activeInfoTopic = TelemetryTopic.LAPS })
                            }
                            Spacer(Modifier.height(10.dp))

                            // Hlavička tabulky kol
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("KOLO", Modifier.width(42.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF64748B), fontSize = 9.5.sp)
                                Text("ČAS", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF64748B), fontSize = 9.5.sp)
                                Text("VZDÁL.", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF64748B), fontSize = 9.5.sp)
                                Text("TEMPO", Modifier.weight(1.1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF64748B), fontSize = 9.5.sp)
                                Text("TEP", Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF64748B), fontSize = 9.5.sp)
                            }

                            laps.take(25).forEach { lap ->
                                val lapDist = lap.distanceM
                                val lapPace = if (lapDist != null && lapDist > 10.0) lap.durationS / (lapDist / 1000.0) else null
                                val spdRatio = ((lap.avgSpeedMps ?: 1.0) / maxSpeedInLaps).toFloat().coerceIn(0.15f, 1f)
                                val isFastest = lap.index == fastestLapIdx && laps.size > 1
                                val rowAccent = if (isFastest) Color(0xFF10B981) else detailAtmo.primaryColor

                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color(0xB8060C16),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isFastest) Color(0xFF10B981).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.06f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                ) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Row(Modifier.width(42.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Box(Modifier.size(6.dp).background(rowAccent, CircleShape))
                                                Text(
                                                    "#${lap.index}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = if (isFastest) Color(0xFF34D399) else Color.White
                                                )
                                            }
                                            Text(fmt.duration(lap.durationS), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Color(0xFFE2E8F0))
                                            Text(fmt.km(lap.distanceM), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Color(0xFFE2E8F0))
                                            Text(
                                                fmt.pace(lapPace),
                                                Modifier.weight(1.1f),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isFastest) Color(0xFF34D399) else detailAtmo.secondaryColor
                                            )
                                            Text(
                                                lap.avgHeartRate?.let { "$it bpm" } ?: "—",
                                                Modifier.weight(0.9f),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFFB7185)
                                            )
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        LinearProgressIndicator(
                                            progress = { spdRatio },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp)),
                                            color = rowAccent.copy(alpha = 0.85f),
                                            trackColor = Color(0xFF1E293B)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Rozpad podle typu pohybu / intervalů z Garminu (Split Summaries: Běh / Chůze / Stání / Intervaly)
            val splits = dom?.splits.orEmpty()
            if (splits.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.2.dp, Color(0xFF38BDF8).copy(alpha = 0.45f)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).background(Color(0xFF38BDF8), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "ROZPAD FÁZÍ POHYBU · GARMIN SPLIT SUMMARIES",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF38BDF8),
                                letterSpacing = 0.8.sp
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        splits.forEach { sp ->
                            val spDist = sp.distanceM
                            val spPace = if (spDist != null && spDist > 10.0) sp.durationS / (spDist / 1000.0) else null
                            androidx.compose.material3.Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.5.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xCC08101D),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(formatSplitTypeLabel(sp.splitType), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                        Text("${sp.noOfSplits}× úsek · ${fmt.duration(sp.durationS)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Vzdál.: ${fmt.km(sp.distanceM)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1))
                                        Text("Tempo: ${fmt.pace(spPace)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF34D399))
                                        sp.avgCadence?.takeIf { it > 0 }?.let {
                                            Text("Kadence: ${fmt.number(it, 0)} spm", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFC084FC))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // =========================================================================================
        // SEKCE 2: BIOMECHANIKA & DATA (Atmosférický Bento Grid + Running Dynamics + Power + Výdej)
        // =========================================================================================
        if (detailSectionTab == 2) {
            // A) Dynamika běhu / chůze (Running Dynamics) s Bento mřížkou
            if (dom?.avgCadence != null || dom?.avgStrideLengthCm != null || dom?.avgVerticalOscillationCm != null || dom?.steps != null || track.any { it.cadence != null || it.strideLengthM != null }) {
                val cadVals = track.mapNotNull { it.cadence?.toDouble() }
                val strideVals = track.mapNotNull { it.strideLengthM }
                val voVals = track.mapNotNull { it.verticalOscillationCm }
                val vrVals = track.mapNotNull { it.verticalRatioPct }
                val gctVals = track.mapNotNull { it.groundContactTimeMs }
                val balVals = track.mapNotNull { it.groundContactBalancePct }
                val stamVals = track.mapNotNull { it.staminaPct }
                val condVals = track.mapNotNull { it.performanceCondition }

                val effCad = dom?.avgCadence ?: cadVals.takeIf { it.isNotEmpty() }?.average()
                val effMaxCad = dom?.maxCadence ?: cadVals.maxOrNull()
                val effStrideM = dom?.avgStrideLengthCm?.let { it / 100.0 } ?: strideVals.takeIf { it.isNotEmpty() }?.average()
                val effVo = dom?.avgVerticalOscillationCm ?: voVals.takeIf { it.isNotEmpty() }?.average()
                val effVr = dom?.avgVerticalRatioPct ?: vrVals.takeIf { it.isNotEmpty() }?.average()
                val effGct = dom?.avgGroundContactTimeMs ?: gctVals.takeIf { it.isNotEmpty() }?.average()
                val effBal = balVals.takeIf { it.isNotEmpty() }?.average()

                Card(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(
                        1.2.dp,
                        Brush.horizontalGradient(listOf(Color(0xFFA855F7).copy(alpha = 0.6f), detailAtmo.primaryColor.copy(alpha = 0.4f)))
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = detailAtmo.artworkRes),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0.50f)
                        )
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color(0xBF050911),
                                    1.0f to Color(0xF5050911)
                                )
                            )
                        }
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(9.dp).background(Color(0xFFC084FC), CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "BIOMECHANIKA POHYBU · GARMIN RUNNING DYNAMICS",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFC084FC),
                                        letterSpacing = 0.8.sp
                                    )
                                }
                                InfoBadgeButton(onClick = { activeInfoTopic = TelemetryTopic.RUNNING_DYNAMICS })
                            }

                            Spacer(Modifier.height(12.dp))

                            // 2×2 Bento mřížka klíčové biomechaniky
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xCC060C16),
                                    border = BorderStroke(1.dp, Color(0xFFC084FC).copy(alpha = 0.35f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("KADENCE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFC084FC), fontSize = 9.5.sp)
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            effCad?.let { "${fmt.number(it, 0)} spm" } ?: "—",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Black,
                                            color = Color.White
                                        )
                                        Text(
                                            effMaxCad?.let { "Max ${fmt.number(it, 0)} spm" } ?: "Frekvence kroků",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xCC060C16),
                                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("DÉLKA KROKU", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), fontSize = 9.5.sp)
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            effStrideM?.let { "${fmt.number(it, 2)} m" } ?: "—",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Black,
                                            color = Color.White
                                        )
                                        Text(
                                            effStrideM?.let { "${fmt.number(it * 100.0, 0)} cm na odraz" } ?: "Vzdálenost došlapu",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xCC060C16),
                                    border = BorderStroke(1.dp, Color(0xFFEC4899).copy(alpha = 0.35f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("KONTAKT SE ZEMÍ (GCT)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFF472B6), fontSize = 9.5.sp)
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            effGct?.let { "${fmt.number(it, 0)} ms" } ?: "—",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Black,
                                            color = Color.White
                                        )
                                        Text("Elasticita odrazu", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                    }
                                }
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xCC060C16),
                                    border = BorderStroke(1.dp, Color(0xFF14B8A6).copy(alpha = 0.35f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("VERTIKÁLNÍ OSCILACE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF2DD4BF), fontSize = 9.5.sp)
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            effVo?.let { "${fmt.number(it, 1)} cm" } ?: "—",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Black,
                                            color = Color.White
                                        )
                                        Text(
                                            effVr?.let { "Vert. poměr ${fmt.number(it, 1)} %" } ?: "Posun těžiště",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }
                            }

                            effBal?.let {
                                Spacer(Modifier.height(10.dp))
                                GarminGroundContactBalanceMeter(
                                    balanceLeftPct = it,
                                    onInfoClick = { activeInfoTopic = TelemetryTopic.GROUND_CONTACT_BALANCE }
                                )
                            }

                            Spacer(Modifier.height(10.dp))
                            androidx.compose.material3.Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xB8060C16),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    if (stamVals.isNotEmpty()) {
                                        LabeledRow("Stamina (Zbývající výdrž)", "Start 100 % → Konec ${fmt.number(stamVals.last(), 0)} % (min ${fmt.number(stamVals.min(), 0)} %)")
                                    }
                                    if (condVals.isNotEmpty()) {
                                        val lastC = condVals.last()
                                        LabeledRow("Zhodnocení kondice", if (lastC > 0) "+$lastC" else "$lastC")
                                    }
                                    dom?.steps?.let { LabeledRow("Celkový počet kroků v aktivitě", "$it kroků") }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (stravaBestEfforts.isNotEmpty()) {
                    BestEffortsCard(stravaBestEfforts)
                    Spacer(Modifier.height(12.dp))
                }
            }

            // B) Výkon (Running / Cycling Power) v atmosférické kartě
            if (dom?.avgPowerW != null || dom?.normPowerW != null || dom?.maxPowerW != null) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(
                        1.2.dp,
                        Brush.horizontalGradient(listOf(Color(0xFFF59E0B).copy(alpha = 0.6f), Color(0xFF38BDF8).copy(alpha = 0.35f)))
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = R.drawable.img_sport_cycling_hero),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0.52f)
                        )
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color(0xBF050911),
                                    1.0f to Color(0xF5050911)
                                )
                            )
                        }
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).background(Color(0xFFF59E0B), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "MECHANICKÝ VÝKON · GARMIN POWER METRICS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFFFBBF24),
                                    letterSpacing = 0.8.sp
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                dom.avgPowerW?.let {
                                    androidx.compose.material3.Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color(0xCC060C16),
                                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text("PRŮMĚRNÝ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFFBBF24), fontSize = 9.5.sp)
                                            Spacer(Modifier.height(3.dp))
                                            Text("${fmt.number(it, 0)} W", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
                                        }
                                    }
                                }
                                dom.normPowerW?.let {
                                    androidx.compose.material3.Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color(0xCC060C16),
                                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text("NORM. NP®", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), fontSize = 9.5.sp)
                                            Spacer(Modifier.height(3.dp))
                                            Text("${fmt.number(it, 0)} W", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
                                        }
                                    }
                                }
                                dom.maxPowerW?.let {
                                    androidx.compose.material3.Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color(0xCC060C16),
                                        border = BorderStroke(1.dp, Color(0xFFFB7185).copy(alpha = 0.4f)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text("MAXIMÁLNÍ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFFB7185), fontSize = 9.5.sp)
                                            Spacer(Modifier.height(3.dp))
                                            Text("${fmt.number(it, 0)} W", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // C) Tempo, Rychlost & Výškový profil v horském atmosférickém rámu
            Card(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(
                    1.2.dp,
                    Brush.horizontalGradient(listOf(Color(0xFF38BDF8).copy(alpha = 0.55f), Color(0xFF10B981).copy(alpha = 0.4f)))
                ),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_alpine_trail_hero),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(0.55f)
                    )
                    Canvas(Modifier.matchParentSize()) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.0f to Color(0xBF050911),
                                1.0f to Color(0xF5050911)
                            )
                        )
                    }
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).background(Color(0xFF38BDF8), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "TEMPO, RYCHLOST & VÝŠKOVÁ TELEMETRIE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF38BDF8),
                                letterSpacing = 0.8.sp
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xCC060C16),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                LabeledRow("Průměrné tempo", fmt.pace(pace))
                                dom?.avgGradeAdjustedSpeedMps?.takeIf { it > 0.2 }?.let { gapSpd ->
                                    LabeledRow("Tempo upravené o sklon (GAP)", fmt.pace(1000.0 / gapSpd))
                                }
                                dom?.avgSpeedMps?.let { LabeledRow("Průměrná rychlost", "${fmt.number(it * 3.6, 1)} km/h") }
                                dom?.maxSpeedMps?.let {
                                    val bestPace = if (it > 0.3) 1000.0 / it else null
                                    LabeledRow("Maximální rychlost / Nejlepší tempo", "${fmt.number(it * 3.6, 1)} km/h (${fmt.pace(bestPace)})")
                                }
                                LabeledRow("Čas v pohybu", fmt.duration(act.movingTimeS))
                                LabeledRow("Celkový uplynulý čas", fmt.duration(act.totalTimeS))
                                LabeledRow("Celkové stoupání", "+${fmt.elevation(ascent)}")
                                LabeledRow("Celkové klesání", "-${fmt.elevation(descent)}")
                                dom?.minElevationM?.let { LabeledRow("Minimální nadmořská výška", fmt.elevation(it)) }
                                dom?.maxElevationM?.let { LabeledRow("Maximální nadmořská výška", fmt.elevation(it)) }
                                dom?.avgElevationM?.let { LabeledRow("Průměrná nadmořská výška", fmt.elevation(it)) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // D) Kalorie, Výživa & Hydratace s vitálním atmosférickým pozadím
            if (dom?.caloriesKcal != null || dom?.waterEstimatedMl != null) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(
                        1.2.dp,
                        Brush.horizontalGradient(listOf(Color(0xFF10B981).copy(alpha = 0.55f), Color(0xFFF59E0B).copy(alpha = 0.4f)))
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = R.drawable.img_motif_body_battery),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0.55f)
                        )
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color(0xBF050911),
                                    1.0f to Color(0xF5050911)
                                )
                            )
                        }
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).background(Color(0xFF10B981), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "ENERGETICKÝ VÝDEJ, METABOLISMUS & HYDRATACE",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF34D399),
                                    letterSpacing = 0.8.sp
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            val totalCal = dom.caloriesKcal ?: 0.0
                            val bmrCal = dom.bmrCaloriesKcal ?: 0.0
                            val activeCal = (totalCal - bmrCal).coerceAtLeast(0.0)
                            androidx.compose.material3.Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xCC060C16),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    if (bmrCal > 0) {
                                        LabeledRow("Aktivní spálené kalorie", "${activeCal.toInt()} kcal")
                                        LabeledRow("Klidový metabolismus (BMR)", "${bmrCal.toInt()} kcal")
                                    }
                                    LabeledRow("Celkem spálené kalorie", "${totalCal.toInt()} kcal")
                                    dom.waterEstimatedMl?.let { LabeledRow("Odhad ztráty tekutin (pocení)", "${it.toInt()} ml (${fmt.number(it / 1000.0, 2)} l)") }
                                    dom.bodyBatteryImpact?.let { LabeledRow("Změna Body Battery", "$it bodů") }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        activeInfoTopic?.let { topic ->
            TelemetryInfoDialog(topic = topic, onDismiss = { activeInfoTopic = null })
        }
    }
}

/**
 * Obrazovka Porovnání Aktivit (Compare):
 * Umožňuje porovnat 2 až 4 aktivity (vlastní i sdílené přes P2P) v atmosférické tabulce vedle sebe,
 * na společném překryvném grafu křivek (Grafana styl) a na společné mapě tras — bez sportovních ikonek!
 */
@Composable
fun ActivityCompareScreen(
    sl: ServiceLocator,
    ids: List<String>,
    onBack: () -> Unit,
) {
    val units by sl.settings.unitSystemFlow.collectAsState()
    val mapProvider by sl.settings.mapProviderFlow.collectAsState()
    val customStyleUrl by io(mapProvider) { sl.settings.mapStyleUrl }

    val loadedList by io(ids to units) {
        ids.mapNotNull { loadUnifiedActivity(sl, it) }
    }
    var channel by remember { mutableStateOf(Channel.HEART_RATE) }

    val palette = listOf(
        Color(0xFF38BDF8), // Cyan
        Color(0xFF10B981), // Emerald
        Color(0xFFF59E0B), // Amber
        Color(0xFFEC4899)  // Pink
    )
    val paletteHex = listOf("#38BDF8", "#10B981", "#F59E0B", "#EC4899")

    val items = loadedList
    if (items == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Načítání porovnání aktivit…")
        }
        return
    }

    val leadAtmo = items.firstOrNull()?.let { it.sport.atmosphere(it.title) } ?: Sport.RUNNING.atmosphere()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // Atmosférická Hero hlavička srovnání aktivit
        Card(
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(
                1.2.dp,
                Brush.horizontalGradient(listOf( Color(0xFF38BDF8).copy(alpha = 0.65f), leadAtmo.primaryColor.copy(alpha = 0.45f)))
            ),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF060B13)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = leadAtmo.artworkRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(0.65f)
                )
                Canvas(Modifier.matchParentSize()) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color(0x99040810),
                            1.0f to Color(0xF2050911)
                        )
                    )
                }
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xCC060C16),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                            modifier = Modifier.clickable { onBack() }
                        ) {
                            Text(
                                "← Zpět na aktivity",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xCC060C16),
                            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.55f))
                        ) {
                            Text(
                                "MULTI-TELEMETRIE · ${items.size} AKTIVITY",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF38BDF8),
                                fontSize = 9.5.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Synchronizované porovnání aktivit",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text(
                        "Překryvná telemetrie křivek, tempa, tepu a GPS tras vedle sebe",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFCBD5E1)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Tabulkové srovnání metrik vedle sebe (bez ikonek, s barevnou aurou každé aktivity)
        Card(
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.2.dp, Color(0xFF1E293B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF070C14)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "SROVNÁVACÍ MATICE UKAZATELŮ",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF38BDF8),
                    letterSpacing = 0.8.sp
                )
                Spacer(Modifier.height(12.dp))

                // Hlavička sloupců
                Row(Modifier.fillMaxWidth()) {
                    Text("Parametr", Modifier.weight(1.1f), style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), maxLines = 1)
                    items.forEachIndexed { idx, act ->
                        val col = palette[idx % palette.size]
                        Column(Modifier.weight(1f).padding(horizontal = 2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(col, CircleShape)
                                )
                                Text(
                                    act.title.take(12),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = col,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                act.startUtcMs?.let(fmt::time) ?: act.localDate,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF64748B),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                @Composable
                fun CompareRow(label: String, vals: List<String>) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        vals.forEach { v ->
                            Text(
                                v,
                                Modifier.weight(1f).padding(horizontal = 2.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                CompareRow("Vzdálenost", items.map { fmt.km(it.distanceM) })
                CompareRow("Pohybový čas", items.map { fmt.duration(it.movingTimeS) })
                CompareRow("Prům. tempo", items.map { a ->
                    val d = a.distanceM
                    val p = if (d != null && d > 0) a.movingTimeS / (d / 1000.0) else null
                    fmt.pace(p)
                })
                CompareRow("Prům. tep", items.map { it.avgHeartRate?.let { hr -> "$hr bpm" } ?: "—" })
                CompareRow("Max. tep", items.map { it.maxHeartRate?.let { hr -> "$hr bpm" } ?: "—" })
                CompareRow("Stoupání", items.map { "+${fmt.elevation(it.ascentM ?: 0.0)}" })
                CompareRow("Kalorie", items.map { it.domainActivity?.caloriesKcal?.let { c -> "${c.toInt()} kcal" } ?: "—" })
                CompareRow("Prům. výkon", items.map { it.domainActivity?.avgPowerW?.let { w -> "${w.toInt()} W" } ?: "—" })
                CompareRow("Kadence", items.map { it.domainActivity?.avgCadence?.let { c -> "${c.toInt()} spm" } ?: "—" })
            }
        }

        Spacer(Modifier.height(14.dp))

        // Společný překryvný graf křivek (Grafana styl)
        SectionTitle("Překryvné porovnání křivek")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Channel.entries.forEach { c ->
                val isSel = channel == c
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isSel) c.color().copy(alpha = 0.22f) else Color(0xCC08101C),
                    border = BorderStroke(1.dp, if (isSel) c.color() else Color(0xFF1E293B)),
                    modifier = Modifier.clickable { channel = c }
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(Modifier.size(6.dp).background(c.color(), CircleShape))
                        Text(
                            c.label(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSel) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (isSel) Color.White else Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }

        val unitForChannel = channel.unitLabel()

        val compareSeries = remember(items, channel, units) {
            items.mapIndexed { idx, act ->
                val v = ActivityView(act.track)
                val raw = if (v.hasChannel(channel)) v.chart(channel, Axis.TIME, buckets = 100).values else emptyList()
                val converted = if (channel == Channel.ELEVATION) raw.map { it?.let(fmt::convertElevation) } else raw
                SeriesItem(
                    id = act.key,
                    label = "${act.title.take(10)} (${act.localDate.takeLast(5)})",
                    color = palette[idx % palette.size],
                    values = converted,
                    unit = unitForChannel
                )
            }
        }

        MultiSeriesLineChart(
            series = compareSeries,
            title = "Porovnání kanálu: ${channel.label()}"
        )

        Spacer(Modifier.height(14.dp))

        // Překryvné GPS trasy s mapovým podkladem
        val multiRoutes = remember(items) {
            items.mapIndexedNotNull { idx, act ->
                val r = act.sharedProjection?.route?.map { it.lat to it.lon } ?: ActivityView(act.track).route()
                if (r.count { it != null } >= 2) Triple(palette[idx % palette.size], paletteHex[idx % paletteHex.size], r) else null
            }
        }

        if (multiRoutes.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.2.dp, Color(0xFF1E293B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF070C14)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).background(Color(0xFF38BDF8), CircleShape))
                        Text(
                            "PŘEKRYVNÉ POROVNÁNÍ GPS TRAS (${mapProvider.shortLabel})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF38BDF8),
                            letterSpacing = 0.8.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (mapProvider != MapProvider.OFFLINE_CANVAS) {
                        MapLibreMultiRoute(
                            provider = mapProvider,
                            customStyleUrl = customStyleUrl,
                            routes = multiRoutes.map { (_, hex, pts) -> hex to pts.filterNotNull() }
                        )
                    } else {
                        MultiRouteCanvas(routes = multiRoutes.map { (col, _, pts) -> col to pts })
                    }
                }
            }
        }
    }
}

