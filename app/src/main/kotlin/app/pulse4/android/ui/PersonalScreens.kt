@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pulse4.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
        icon = Icons.Outlined.MonitorHeart,
        title = "Variabilita tepu (HRV)",
        shortLabel = "HRV",
        unit = "ms",
        color = Color(0xFF10B981),
        explanation = "Noční HRV (rMSSD) odráží rovnováhu autonomního nervového systému. Vyšší hodnoty v rámci vašeho pásma značí vysokou připravenost na zátěž a efektivní regeneraci."
    )
    MetricType.RESTING_HEART_RATE -> MetricUiConfig(
        icon = Icons.Outlined.Favorite,
        title = "Klidový tep (RHR)",
        shortLabel = "Klidový tep",
        unit = "bpm",
        color = Color(0xFFEF4444),
        explanation = "Klidová srdeční frekvence měřená ve spánku. Trvalé snížení značí růst aerobní kondice; vzestup o více než 5 bpm může signalizovat začínající nemoc, únavu či alkohol."
    )
    MetricType.SLEEP_DURATION -> MetricUiConfig(
        icon = Icons.Outlined.Bedtime,
        title = "Doba spánku",
        shortLabel = "Spánek",
        unit = "hod",
        color = Color(0xFF38BDF8),
        explanation = "Celková doba nočního spánku. Dlouhodobý spánek 7–9 hodin je klíčový pro buněčnou a svalovou obnovu, syntézu růstového hormonu a konsolidaci paměti."
    )
    MetricType.BODY_BATTERY -> MetricUiConfig(
        icon = Icons.Outlined.Bolt,
        title = "Body Battery",
        shortLabel = "B.Battery",
        unit = "/ 100",
        color = Color(0xFFF59E0B),
        explanation = "Komplexní energetické skóre Garminu (0–100) kalkulované z HRV, spánku, stresu a denních aktivit. Ranní hodnota 75+ značí plně dobité baterie."
    )
    MetricType.STRESS -> MetricUiConfig(
        icon = Icons.Outlined.SelfImprovement,
        title = "Hladina stresu",
        shortLabel = "Stres",
        unit = "/ 100",
        color = Color(0xFFA855F7),
        explanation = "Fyziologický stres měřený z odchylek intervalů tepu (0–25 odpočinek, 26–50 mírný, 51–75 střední, 76–100 vysoký stres). Zobrazuje aktivaci sympatiku."
    )
    MetricType.STEPS -> MetricUiConfig(
        icon = Icons.AutoMirrored.Outlined.DirectionsWalk,
        title = "Denní kroky",
        shortLabel = "Kroky",
        unit = "kroků",
        color = Color(0xFF3B82F6),
        explanation = "Počet kroků za den jako ukazatel spontánní neúmyslné fyzické aktivity (NEAT). Pravidelný pohyb podporuje kardiovaskulární zdraví a inzulinovou senzitivitu."
    )
    MetricType.WEIGHT -> MetricUiConfig(
        icon = Icons.Outlined.Scale,
        title = "Tělesná hmotnost",
        shortLabel = "Váha",
        unit = fmt.weightUnit(),
        color = Color(0xFF14B8A6),
        explanation = "Dlouhodobý trend tělesné hmotnosti sloučený ze všech připojených vah (Withings, Health Connect, Garmin), očištěný od krátkodobých výkyvů vody."
    )
    MetricType.BODY_FAT -> MetricUiConfig(
        icon = Icons.Outlined.WaterDrop,
        title = "Tělesný tuk",
        shortLabel = "Tuk",
        unit = "%",
        color = Color(0xFF06B6D4),
        explanation = "Procentuální podíl tělesné tukové tkáně. Pro sledování sportovní formy a složení těla je spolehlivějším ukazatelem než samotné BMI."
    )
    MetricType.MUSCLE_MASS -> MetricUiConfig(
        icon = Icons.Outlined.FitnessCenter,
        title = "Svalová hmota",
        shortLabel = "Svaly",
        unit = fmt.weightUnit(),
        color = Color(0xFFF43F5E),
        explanation = "Hmotnost kosterního a hladkého svalstva v těle. Zásadní ukazatel silové kondice, regenerace a bazální rychlosti metabolismu."
    )
    MetricType.BODY_WATER -> MetricUiConfig(
        icon = Icons.Outlined.WaterDrop,
        title = "Tělesná voda",
        shortLabel = "Voda",
        unit = "%",
        color = Color(0xFF0284C7),
        explanation = "Procentuální podíl vody v těle odrážející celkovou hydrataci organismu. Zdravé rozmezí u dospělých je obvykle 50–65 %."
    )
    MetricType.BONE_MASS -> MetricUiConfig(
        icon = Icons.Outlined.Crop,
        title = "Kostní hmota",
        shortLabel = "Kosti",
        unit = fmt.weightUnit(),
        color = Color(0xFFA8A29E),
        explanation = "Minerální hmotnost kosterní soustavy. Pomáhá sledovat stabilitu a denzitu kostní tkáně v průběhu času."
    )
    MetricType.FAT_FREE_MASS -> MetricUiConfig(
        icon = Icons.Outlined.FitnessCenter,
        title = "Beztuková hmota (FFM)",
        shortLabel = "FFM",
        unit = fmt.weightUnit(),
        color = Color(0xFF10B981),
        explanation = "Celková beztuková tělesná hmota (Lean Body Mass) zahrnující svalstvo, vnitřní orgány, kosti a tělesnou tekutinu."
    )
    MetricType.VISCERAL_FAT -> MetricUiConfig(
        icon = Icons.Outlined.Warning,
        title = "Viscerální tuk",
        shortLabel = "Viscerální tuk",
        unit = "index",
        color = Color(0xFFD97706),
        explanation = "Tuk obklopující vnitřní orgány v dutině břišní. Hodnoty 1–9 značí zdravou úroveň, 10–14 zvýšenou a 15+ vysoké kardiovaskulární riziko."
    )
    MetricType.VASCULAR_AGE -> MetricUiConfig(
        icon = Icons.Outlined.MonitorHeart,
        title = "Cévní věk",
        shortLabel = "Cévní věk",
        unit = "let",
        color = Color(0xFFEC4899),
        explanation = "Orientační biologické stáří tepen vypočtené z rychlosti pulzní vlny v porovnání s vaší věkovou skupinou."
    )
    MetricType.PULSE_WAVE_VELOCITY -> MetricUiConfig(
        icon = Icons.Outlined.Speed,
        title = "Rychlost pulzní vlny (PWV)",
        shortLabel = "PWV",
        unit = "m/s",
        color = Color(0xFF8B5CF6),
        explanation = "Rychlost šíření pulzní vlny v cévách (m/s). Nižší hodnoty ukazují na pružné, zdravé cévy."
    )
    MetricType.BMR -> MetricUiConfig(
        icon = Icons.Outlined.LocalFireDepartment,
        title = "Bazální metabolismus (BMR)",
        shortLabel = "BMR",
        unit = "kcal",
        color = Color(0xFFF97316),
        explanation = "Denní energetický výdej organismu v úplném klidovém stavu."
    )
    MetricType.METABOLIC_AGE -> MetricUiConfig(
        icon = Icons.Outlined.Schedule,
        title = "Metabolický věk",
        shortLabel = "Metab. věk",
        unit = "let",
        color = Color(0xFFEAB308),
        explanation = "Srovnání vašeho bazálního metabolismu s průměrnými hodnotami populace stejného věku."
    )
    MetricType.SPO2 -> MetricUiConfig(
        icon = Icons.Outlined.Air,
        title = "Okysličení krve (SpO2)",
        shortLabel = "SpO2",
        unit = "%",
        color = Color(0xFF0EA5E9),
        explanation = "Procento nasycení periferní krve kyslíkem. Běžné noční hodnoty u zdravého člověka se pohybují v rozmezí 95–100 %."
    )
    MetricType.ECG -> MetricUiConfig(
        icon = Icons.Outlined.MonitorHeart,
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
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pořadí widgetů na Dnes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
fun TodayScreen(sl: ServiceLocator, onOpenMetric: (MetricType) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var bgWindowDays by remember { mutableStateOf(14) }
    var showReorderDialog by remember { mutableStateOf(false) }
    var currentWidgetOrder by remember { mutableStateOf(sl.settings.todayWidgetOrder) }

    val states by sl.db.areaStates().all().collectAsState(initial = emptyList())
    val units by sl.settings.unitSystemFlow.collectAsState()

    val dashboardData by produceState<TodayDashboardBundle?>(
        initialValue = ScreenMemoryCache.todayDashboard,
        key1 = Triple(states.map { it.area to it.lastSuccessMs }, bgWindowDays, units)
    ) {
        val result = withContext(Dispatchers.IO) {
            runCatching { sl.importService.ensureWeightFallbackLoaded(System.currentTimeMillis()) }
            val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
            val obsToday = sl.db.observations().forDate(today.toString()).map { it.toDomain() }
            val maxDays = maxOf(bgWindowDays, 30)
            val rangeObs = sl.db.observations().forRange(today.minusDays((maxDays - 1).toLong()).toString(), today.toString()).map { it.toDomain() }
            val weightObs = runCatching { sl.withings.trend(MetricType.WEIGHT, maxOf(bgWindowDays, 90)) }.getOrDefault(emptyList())
            val success = SyncArea.entries.associateWith { sl.db.areaStates().get(it.name)?.lastSuccessMs }

            // Zobrazit čas syncu pouze pokud má daná služba skutečně proběhlý sync (nebo nastavený klíč/účet)
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
    val keyToType = mapOf(
        "sleep" to MetricType.SLEEP_DURATION,
        "bb" to MetricType.BODY_BATTERY,
        "rhr" to MetricType.RESTING_HEART_RATE,
        "hrv" to MetricType.HRV,
        "stress" to MetricType.STRESS,
        "steps" to MetricType.STEPS
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Horní lišta Dnes + Pořadí widgetů + Synchronizace
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Dnešní Přehled", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text(
                    "$today · Klikněte na kartu pro vyhrazený detail",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { showReorderDialog = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f))
                ) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Upravit widgety", style = MaterialTheme.typography.labelMedium, color = Color(0xFF38BDF8))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        syncing = true
                        syncMessage = null
                        app.pulse4.android.work.Scheduler.importNow(ctx)
                        scope.launch {
                            kotlinx.coroutines.delay(2500)
                            syncing = false
                            syncMessage = "Synchronizace na pozadí spuštěna."
                        }
                    },
                    enabled = !syncing
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (syncing) "Stahuji…" else "Sync")
                    }
                }
            }
        }

        val wState by sl.withings.state.collectAsState()
        val webSession = remember(wState) { sl.withings.loadWebSession() }
        val wNeedsLogin = wState is app.pulse4.android.di.WithingsUiState.Failed ||
            (sl.settings.withingsDirectEnabled && webSession != null && !webSession.isConnected())
        var showWithingsLoginDialogOnToday by remember { mutableStateOf(false) }
        var isSilentReconnecting by remember { mutableStateOf(false) }

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

        val onBarClick: () -> Unit = {
            if (!isSilentReconnecting) {
                scope.launch {
                    isSilentReconnecting = true
                    val ok = withContext(Dispatchers.IO) {
                        sl.withings.renewSession()
                    }
                    isSilentReconnecting = false
                    if (ok) {
                        syncMessage = "Withings relace byla úspěšně obnovena."
                        app.pulse4.android.work.Scheduler.importNow(ctx)
                    } else {
                        showWithingsLoginDialogOnToday = true
                    }
                }
            }
        }

        if (wNeedsLogin) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF450A0A)),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.8f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clickable(enabled = !isSilentReconnecting) { onBarClick() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(24.dp)
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
                            if (isSilentReconnecting) "Ověřuji a obnovuji relaci na pozadí…" else "Klepněte pro rychlé obnovení relace (Google SSO)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFCA5A5)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !isSilentReconnecting,
                        onClick = { onBarClick() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        if (isSilentReconnecting) {
                            CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Obnovuji…", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        } else {
                            Text("Obnovit", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
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
                modifier = Modifier.padding(top = 4.dp),
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

        Spacer(Modifier.height(8.dp))

        // Výběr historického okna pro grafy na pozadí karet (Grafana Singlestat window selector)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF131722))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Timeline,
                    contentDescription = null,
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Historické okno grafů:",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF94A3B8)
                )
            }
            Row {
                listOf(7, 14, 30, 90).forEach { d ->
                    FilterChip(
                        selected = bgWindowDays == d,
                        onClick = { bgWindowDays = d },
                        label = { Text("${d}d", maxLines = 1) },
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Singlestat karty v uživatelsky definovaném pořadí s bohatým atmosférickým motivem na pozadí
        currentWidgetOrder.forEach { key ->
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
                        icon = null,
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
                        icon = null,
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
                        icon = null,
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
                        icon = null,
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
                        icon = null,
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
                        icon = null,
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
                        icon = null,
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
                            icon = null,
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

        Spacer(Modifier.height(12.dp))
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
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFF2DD4BF).copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "JEDNOTNÉ ZOBRAZENÍ SLOŽENÍ TĚLA",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2DD4BF)
                    )
                    Text(
                        if (unitMode == BodyUnitMode.KG) "Všechny složky těla přepočteny na kilogramy (${fmt.weightUnit()})"
                        else "Všechny složky těla přepočteny na procenta celkové váhy (%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // Přepínač jednotek: Kilogramy (kg) vs Procenta (%)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = unitMode == BodyUnitMode.KG,
                    onClick = { onUnitModeChange(BodyUnitMode.KG) },
                    leadingIcon = { Icon(Icons.Outlined.Scale, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("V kilogramech (${fmt.weightUnit()})", fontWeight = FontWeight.Bold, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = unitMode == BodyUnitMode.PERCENT,
                    onClick = { onUnitModeChange(BodyUnitMode.PERCENT) },
                    leadingIcon = { Icon(Icons.Outlined.Assessment, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("V procentech (%)", fontWeight = FontWeight.Bold, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Skládaný pruh složení těla (Svaly + Tuk + Kosti = Celková váha)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
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
                    Text("● Svaly", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                    Text(mText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("● Tuk", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                    Text(fText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("● Kosti", style = MaterialTheme.typography.labelSmall, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                    Text(bText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
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
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (showFatMode) Color(0xFFF59E0B).copy(alpha = 0.5f) else Color(0xFF10B981).copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (showFatMode) "ROZLOŽENÍ TUKU V TĚLE (RUCE, TRUP, NOHY)"
                        else "ROZLOŽENÍ SVALŮ V TĚLE (RUCE, TRUP, NOHY)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (showFatMode) Color(0xFFF59E0B) else Color(0xFF10B981)
                    )
                    Text(
                        "Withings Body Scan (${latest.dateLabel}) · " +
                            if (activeUnitMode == BodyUnitMode.KG) "zobrazeno v kilogramech (${fmt.weightUnit()})"
                            else "zobrazeno v procentech dané části těla (%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // Přepínač Svaly vs Tuk
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !showFatMode,
                    onClick = { showFatMode = false },
                    leadingIcon = { Icon(Icons.Outlined.FitnessCenter, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Svalová hmota", maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = showFatMode,
                    onClick = { showFatMode = true },
                    leadingIcon = { Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Tuková tkáň", maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Přepínač jednotky: Kilogramy (kg) vs Procenta (%)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = activeUnitMode == BodyUnitMode.KG,
                    onClick = { setUnitMode(BodyUnitMode.KG) },
                    leadingIcon = { Icon(Icons.Outlined.Scale, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("V kilogramech (${fmt.weightUnit()})", maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = activeUnitMode == BodyUnitMode.PERCENT,
                    onClick = { setUnitMode(BodyUnitMode.PERCENT) },
                    leadingIcon = { Icon(Icons.Outlined.Assessment, contentDescription = null, modifier = Modifier.size(16.dp)) },
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
        val allRangeObs = sl.db.observations().forRange(fromDate, toDate).map { it.toDomain() }
        val primaryRows = if (type in WithingsService.BODY_COMPOSITION_TYPES) {
            sl.withings.trend(type, daysCount)
        } else {
            allRangeObs.filter { it.type == type }
        }
        val mainSeries = MetricSeries.build(type, primaryRows, today, daysCount)
        val latestObs = primaryRows.filter { it.type == type && !it.deleted }.maxByOrNull { it.startUtcMs }

        // Doplňková data specifická pro danou doménu (např. SLEEP_SCORE pro spánek, BODY_FAT pro váhu)
        val sleepScoreSeries = if (type == MetricType.SLEEP_DURATION) {
            MetricSeries.build(MetricType.SLEEP_SCORE, allRangeObs, today, daysCount)
        } else null
        val bodyFatSeries = if (type == MetricType.WEIGHT) {
            val bfRows = runCatching { sl.withings.trend(MetricType.BODY_FAT, daysCount) }.getOrDefault(emptyList())
            MetricSeries.build(MetricType.BODY_FAT, bfRows, today, daysCount)
        } else null

        val recentLogs = primaryRows.filter { it.type == type && !it.deleted }
            .sortedByDescending { it.startUtcMs }
            .take(14)
        val sleepScoresByDate = if (type == MetricType.SLEEP_DURATION) {
            allRangeObs.filter { it.type == MetricType.SLEEP_SCORE && !it.deleted }
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
            runCatching { sl.withings.trend(MetricType.MUSCLE_MASS, daysCount) }.getOrDefault(emptyList())
                .filter { !it.deleted }.maxByOrNull { it.startUtcMs }?.value
        } else null
        val latestFatPct = if (isBodyComp) {
            runCatching { sl.withings.trend(MetricType.BODY_FAT, daysCount) }.getOrDefault(emptyList())
                .filter { !it.deleted }.maxByOrNull { it.startUtcMs }?.value
        } else null
        val latestBoneKg = if (isBodyComp) {
            runCatching { sl.withings.trend(MetricType.BONE_MASS, daysCount) }.getOrDefault(emptyList())
                .filter { !it.deleted }.maxByOrNull { it.startUtcMs }?.value
        } else null

        DedicatedBundle(
            today = today,
            zone = zone,
            mainSeries = mainSeries,
            latestObs = latestObs,
            sleepScoreSeries = sleepScoreSeries,
            bodyFatSeries = bodyFatSeries,
            recentLogs = recentLogs,
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

                // Deník spánku po jednotlivých nocích
                SectionTitle("Deník spánku (poslední noci)", icon = Icons.Outlined.Nightlight)
                b.recentLogs.forEach { obs ->
                    val st = java.time.Instant.ofEpochMilli(obs.startUtcMs).atZone(b.zone).toLocalTime().toString().take(5)
                    val en = obs.endUtcMs?.let { java.time.Instant.ofEpochMilli(it).atZone(b.zone).toLocalTime().toString().take(5) } ?: "—"
                    val sc = b.sleepScoresByDate[obs.localDate ?: ""]
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(obs.localDate ?: fmt.time(obs.startUtcMs), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("$st → $en", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (sc != null) {
                                    Text(
                                        "Skóre ${sc.toInt()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFA855F7),
                                        modifier = Modifier
                                            .padding(end = 10.dp)
                                            .background(Color(0xFFA855F7).copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                Text(fmt.sleep(obs.value), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cfg.color)
                            }
                        }
                    }
                }
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

                SectionTitle("Historie vážení", icon = Icons.Outlined.History)
                b.recentLogs.forEach { obs ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    fmt.weight(obs.value),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
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
                                        weightSavedMsg = if (ok) "Váha ${fmt.weight(obs.value)} přeposlána dítěti přes P2P!" else "Nejprve vyberte kontakt dítěte v Nastavení → Garmin & Withings."
                                        refreshTick++
                                    }
                                }) {
                                    Text("Poslat dítěti", style = MaterialTheme.typography.labelSmall, color = Color(0xFF38BDF8), maxLines = 1, softWrap = false)
                                }
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        sl.withings.markWrong(obs.id, System.currentTimeMillis())
                                        refreshTick++
                                    }
                                }) {
                                    Text("Cizí", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
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

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cfg.color.copy(alpha = 0.14f)),
                    border = BorderStroke(1.dp, cfg.color.copy(alpha = 0.4f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(cfg.title.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFCBD5E1))
                                Text("Měřeno ${fmt.time(latestObs.startUtcMs)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                            }
                            Text(
                                statusText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = statusColor,
                                modifier = Modifier
                                    .background(statusColor.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(formattedVal, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = Color.White)

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
                                color = Color(0xFF94A3B8)
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
                            Text(readiness, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        } else if (type == MetricType.STRESS) {
                            Spacer(Modifier.height(8.dp))
                            val stressZone = when {
                                latestVal <= 25 -> "0–25: Klid a regenerace (převaha parasympatiku)"
                                latestVal <= 50 -> "26–50: Nízký fyziologický stres"
                                latestVal <= 75 -> "51–75: Střední zátěž organismu"
                                else -> "76–100: Vysoký stres / vyčerpání"
                            }
                            Text(stressZone, style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBD5E1))
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
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("STATISTIKA OBDOBÍ (${daysCount} DNÍ)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
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
                    xLabels = series.dates.map { it.toString().takeLast(5) }
                )

                SectionTitle("Denní záznamy (${b.recentLogs.size})", icon = Icons.Outlined.FormatListBulleted)
                b.recentLogs.forEach { obs ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(obs.localDate ?: fmt.time(obs.startUtcMs), style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                                if (type == MetricType.ECG) {
                                    val det = parseEcgOrigin(obs.origin, obs.value)
                                    val rhythm = when (det.afibClassification) {
                                        0 -> "Sinusový rytmus"
                                        1 -> "AFib detekována"
                                        2 -> "Neprůkazný záznam"
                                        else -> "Záznam"
                                    }
                                    val hrPart = det.heartRate?.takeIf { it > 0 }?.let { " · $it bpm" } ?: ""
                                    Text(
                                        "$rhythm$hrPart",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (det.afibClassification == 1) Color(0xFFEF4444) else Color(0xFF10B981)
                                    )
                                }
                            }
                            Text(formatMetricValue(type, obs.value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = cfg.color)
                        }
                    }
                }
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

private data class DedicatedBundle(
    val today: LocalDate,
    val zone: ZoneId,
    val mainSeries: MetricSeries,
    val latestObs: app.pulse4.domain.MetricObservation?,
    val sleepScoreSeries: MetricSeries?,
    val bodyFatSeries: MetricSeries?,
    val recentLogs: List<app.pulse4.domain.MetricObservation>,
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

/**
 * Obrazovka Metriky (záložka Metriky):
 * Umožňuje jak prohlížení jedné metriky, tak výběr více smysluplných metrik najednou
 * (pomocí připravených kombinací nebo přepínače Multi-výběr) pro srovnání jejich korelace v čase.
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
    var timeMenuExpanded by remember { mutableStateOf(false) }

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

    val presets = listOf(
        Triple("Regenerace (HRV + Tep)", Icons.Outlined.MonitorHeart, linkedSetOf(MetricType.HRV, MetricType.RESTING_HEART_RATE)),
        Triple("Srdce & ECG (Tep + ECG)", Icons.Outlined.MonitorHeart, linkedSetOf(MetricType.RESTING_HEART_RATE, MetricType.ECG)),
        Triple("Energie (B.Battery + Stres)", Icons.Outlined.Bolt, linkedSetOf(MetricType.BODY_BATTERY, MetricType.STRESS)),
        Triple("Spánek + HRV + B.Battery", Icons.Outlined.Bedtime, linkedSetOf(MetricType.SLEEP_DURATION, MetricType.HRV, MetricType.BODY_BATTERY)),
        Triple("Složení těla (Váha + Svaly + Tuk)", Icons.Outlined.FitnessCenter, linkedSetOf(MetricType.WEIGHT, MetricType.MUSCLE_MASS, MetricType.BODY_FAT)),
        Triple("Hydratace & Kosti (Voda + Kosti)", Icons.Outlined.WaterDrop, linkedSetOf(MetricType.BODY_WATER, MetricType.BONE_MASS)),
        Triple("Tělo (Váha + Tuk)", Icons.Outlined.Scale, linkedSetOf(MetricType.WEIGHT, MetricType.BODY_FAT))
    )

    val multiBundle by io(Triple(selectedTypes.toList(), daysCount, units)) {
        val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
        val rangeObs = sl.db.observations()
            .forRange(today.minusDays((daysCount - 1).toLong()).toString(), today.toString())
            .map { it.toDomain() }

        val weightRows = runCatching { sl.withings.trend(MetricType.WEIGHT, daysCount) }.getOrDefault(emptyList())
            .ifEmpty { rangeObs.filter { it.type == MetricType.WEIGHT } }
        val latestWeightObs = weightRows.filter { !it.deleted }.maxByOrNull { it.startUtcMs }
            ?: sl.db.observations().latest(MetricType.WEIGHT.name)?.toDomain()
        val refWeightKg = latestWeightObs?.value?.takeIf { it in 25.0..250.0 } ?: 93.10
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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // Moderní lišta Dropdown filtrů (Metriky s multi-výběrem, Kombinace předvoleb, Období)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. DROPDOWN METRIKY S MULTI-VÝBĚREM
            Box {
                val isMulti = selectedTypes.size > 1
                val firstCfg = getMetricUiConfig(selectedTypes.first())
                FilterChip(
                    selected = isMulti,
                    onClick = { metricsMenuExpanded = true },
                    leadingIcon = {
                        Icon(
                            if (isMulti) Icons.Outlined.CheckCircle else firstCfg.icon,
                            contentDescription = null,
                            tint = if (isMulti) MaterialTheme.colorScheme.primary else firstCfg.color,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    trailingIcon = {
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    label = {
                        Text(
                            if (isMulti) "Metriky (${selectedTypes.size})"
                            else firstCfg.title
                        )
                    }
                )
                DropdownMenu(
                    expanded = metricsMenuExpanded,
                    onDismissRequest = { metricsMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Základní: ${getMetricUiConfig(MetricType.RESTING_HEART_RATE).title}") },
                        leadingIcon = { Icon(Icons.Outlined.MonitorHeart, contentDescription = null, tint = Color(0xFFEF4444)) },
                        onClick = {
                            selectedTypes = linkedSetOf(MetricType.RESTING_HEART_RATE)
                            metricsMenuExpanded = false
                        }
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF232A3B)))
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
                        text = { Text("Zavřít", color = Color(0xFF38BDF8)) },
                        leadingIcon = { Icon(Icons.Outlined.Check, contentDescription = null, tint = Color(0xFF38BDF8)) },
                        onClick = { metricsMenuExpanded = false }
                    )
                }
            }

            // 2. DROPDOWN PŘEDVOLBY / KOMBINACE
            Box {
                val matchingPreset = presets.firstOrNull { it.third == selectedTypes }
                val isPresetActive = matchingPreset != null
                FilterChip(
                    selected = isPresetActive,
                    onClick = { presetsMenuExpanded = true },
                    leadingIcon = {
                        Icon(
                            matchingPreset?.second ?: Icons.Outlined.FormatListBulleted,
                            contentDescription = null,
                            tint = if (isPresetActive) MaterialTheme.colorScheme.primary else Color(0xFFF59E0B),
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    trailingIcon = {
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    label = {
                        Text(matchingPreset?.first?.substringBefore(" (") ?: "Kombinace")
                    }
                )
                DropdownMenu(
                    expanded = presetsMenuExpanded,
                    onDismissRequest = { presetsMenuExpanded = false }
                ) {
                    presets.forEach { (label, iconVector, presetSet) ->
                        val isSel = selectedTypes == presetSet
                        DropdownMenuItem(
                            text = { Text(label, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) },
                            leadingIcon = {
                                Icon(iconVector, contentDescription = null, tint = if (isSel) MaterialTheme.colorScheme.primary else Color(0xFF94A3B8))
                            },
                            onClick = {
                                selectedTypes = LinkedHashSet(presetSet)
                                presetsMenuExpanded = false
                            }
                        )
                    }
                }
            }

            // 3. DROPDOWN OBDOBÍ (7d, 14d, 30d, 90d, 365d)
            Box {
                FilterChip(
                    selected = daysCount != 30,
                    onClick = { timeMenuExpanded = true },
                    leadingIcon = {
                        Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    trailingIcon = {
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    label = { Text("Období: ${daysCount}d") }
                )
                DropdownMenu(
                    expanded = timeMenuExpanded,
                    onDismissRequest = { timeMenuExpanded = false }
                ) {
                    listOf(
                        7 to "Posledních 7 dní (1 týden)",
                        14 to "Posledních 14 dní (2 týdny)",
                        30 to "Posledních 30 dní (1 měsíc)",
                        90 to "Posledních 90 dní (3 měsíce)",
                        365 to "Posledních 365 dní (1 rok)"
                    ).forEach { (d, label) ->
                        val isSel = daysCount == d
                        DropdownMenuItem(
                            text = { Text(label, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) },
                            leadingIcon = {
                                if (isSel) Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                else Box(Modifier.size(24.dp))
                            },
                            onClick = {
                                daysCount = d
                                timeMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Zobrazení aktivních chipů s tlačítkem ✕ pro okamžité odebrání, pokud je vybráno více metrik
        if (selectedTypes.size > 1) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Zobrazeno:", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
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
                TextButton(
                    onClick = { selectedTypes = linkedSetOf(selectedTypes.first()) },
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text("Jen 1 metrika", style = MaterialTheme.typography.labelSmall, color = Color(0xFF38BDF8))
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        val bundle = multiBundle
        if (bundle == null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
            ) {
                Text("Načítání biometrických dat…", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (bundle.entries.size >= 2) {
            val entries = bundle.entries
            val hasBodyComp = selectedTypes.any { isConvertibleBodyCompMetric(it) || it == MetricType.WEIGHT }
            val datesLabels = entries.firstOrNull()?.series?.dates?.map { it.toString().takeLast(5) }.orEmpty()

            // Hlavní přepínač měřítka a jednotek pro celou obrazovku souhrnu: Původní hodnoty vs. % škála
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
                    leadingIcon = { Icon(Icons.Outlined.Scale, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Původní hodnoty (${fmt.weightUnit()}, bpm…)", maxLines = 1, softWrap = false) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = normalizeMultiChart,
                    onClick = {
                        normalizeMultiChart = true
                        bodyUnitMode = BodyUnitMode.PERCENT
                    },
                    leadingIcon = { Icon(Icons.Outlined.Assessment, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Relativní % škála (0–100)", maxLines = 1, softWrap = false) },
                    modifier = Modifier.weight(1f)
                )
            }

            // REŽIM VÍCE VYBRANÝCH METRIK NAJEDNOU (Korelační porovnání)
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFF232A3B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Assessment, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "SPOLEČNÉ POROVNÁNÍ (${entries.size} VYBRANÉ METRIKY)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (!normalizeMultiChart) "Měřítko v původních fyzikálních jednotkách (${fmt.weightUnit()}, bpm, h…)"
                        else "Normalizované měřítko pro snadné porovnání korelací (0–100 %)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(Modifier.height(6.dp))

                    val datesLabels = entries.firstOrNull()?.series?.dates?.map { it.toString().takeLast(5) }.orEmpty()
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
                        title = if (normalizeMultiChart) {
                            "Korelace (0–100 %)"
                        } else {
                            "Společný graf ($daysCount dní)"
                        }
                    )
                }
            }

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

                val wEntry = entries.firstOrNull { it.type == MetricType.WEIGHT }
                val mEntry = entries.firstOrNull { it.type == MetricType.MUSCLE_MASS }
                val fEntry = entries.firstOrNull { it.type == MetricType.BODY_FAT }

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
            SectionTitle("Souhrn vybraných metrik (${entries.size})", icon = Icons.Outlined.FormatListBulleted)

            val wEntry = entries.firstOrNull { it.type == MetricType.WEIGHT }
            val mEntry = entries.firstOrNull { it.type == MetricType.MUSCLE_MASS }
            val fEntry = entries.firstOrNull { it.type == MetricType.BODY_FAT }
            val isBodyCompTriple = wEntry != null && mEntry != null && fEntry != null

            if (isBodyCompTriple) {
                // Skládaný graf složení tělesné hmotnosti (Váha + Svaly + Tuk) v jednom společném grafu namísto tří oddělených!
                val fatKgValues = fEntry!!.series.dates.zip(fEntry.series.values).map { (d, f) ->
                    if (f != null) {
                        val w = bundle.weightsByDate[d.toString()] ?: bundle.refWeightKg
                        fmt.convertWeight(w * (f / 100.0))
                    } else null
                }

                Card(
                    shape = RoundedCornerShape(18.dp),
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
                                Icon(Icons.Outlined.Scale, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "SKLÁDANÉ SLOŽENÍ HMOTNOSTI (VÁHA + SVALY + TUK)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2DD4BF)
                                )
                            }
                            Text(
                                "${daysCount} dní · ${if (bodyUnitMode == BodyUnitMode.KG) fmt.weightUnit() else "%"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        // Souhrn hodnot všech tří složek vedle sebe
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Celková váha", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                Text(fmt.weight(bundle.refWeightKg), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            }
                            Column {
                                Text("Svalová hmota", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                val lMuscle = bundle.latestMuscleKg
                                val mTxt = if (lMuscle == null) "—"
                                else if (bodyUnitMode == BodyUnitMode.KG) fmt.weight(lMuscle)
                                else "${fmt.number((lMuscle / bundle.refWeightKg) * 100.0, 1)} %"
                                Text(mTxt, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF10B981))
                            }
                            Column {
                                Text("Tělesný tuk", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
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

            val filteredEntries = if (isBodyCompTriple && !showSeparateCards) {
                entries.filterNot { it.type in setOf(MetricType.WEIGHT, MetricType.MUSCLE_MASS, MetricType.BODY_FAT) }
            } else {
                entries
            }

            filteredEntries.forEach { entry ->
                val c = getMetricUiConfig(entry.type)
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
                    "Málo dat pro medián"
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

                val datesLabels = entry.series.dates.map { it.toString().takeLast(5) }
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
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, c.color.copy(alpha = 0.4f)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
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
                                        .size(36.dp)
                                        .background(c.color.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
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
                                        color = Color(0xFF94A3B8),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    formattedValueText,
                                    style = MaterialTheme.typography.titleLarge,
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
                                xLabels = datesLabels
                            )
                        }
                    }
                }
            }

            multiInfoDialogType?.let { t ->
                MetricInfoPopupDialog(type = t, cfg = getMetricUiConfig(t), onDismiss = { multiInfoDialogType = null })
            }
        } else {
            // REŽIM JEDNÉ VYBRANÉ METRIKY
            var showInfoDialog by remember { mutableStateOf(false) }
            val single = bundle.entries.first()
            val type = single.type
            val isBodyComp = isConvertibleBodyCompMetric(type) || type == MetricType.WEIGHT
            val cfg = getMetricUiConfig(type)
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
                            "Pro tuto metriku zatím nejsou v zařízení žádná zaznamenaná data. Na obrazovce Dnes spusťte synchronizaci s Garmin Connect nebo připojte váhu.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF94A3B8)
                        )
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
                        latestValue < usual.low -> "Nízké / Odpočinek" to Color(0xFF10B981)
                        latestValue > usual.high -> "Zvýšené nad normál" to Color(0xFFEF4444)
                        else -> "V obvyklém rozmezí" to Color(0xFF10B981)
                    }
                    else -> when {
                        latestValue < usual.low -> "Pod obvyklým rozmezím" to Color(0xFFF59E0B)
                        latestValue > usual.high -> "Nadstandardní hodnota" to Color(0xFF10B981)
                        else -> "V obvyklém rozmezí" to Color(0xFF10B981)
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

                // Hero Card
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                                    Text(cfg.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Měření z ${fmt.time(latestObs.startUtcMs)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor,
                                    modifier = Modifier
                                        .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                MetricInfoIconButton(onClick = { showInfoDialog = true })
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = latestFormatted,
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            if (deltaText != null) {
                                Text(
                                    text = deltaText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = deltaColor,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                        }
                    }
                }

                // 2x2 Statistický souhrn
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

                val finite = series.values.filterNotNull()
                val minMaxFormatted = if (finite.isNotEmpty()) {
                    val minV = finite.minOrNull() ?: 0.0
                    val maxV = finite.maxOrNull() ?: 0.0
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

                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Statistický přehled období ($daysCount dní)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            StatBox(title = "Poslední záznam", value = latestFormatted, modifier = Modifier.weight(1f))
                            StatBox(title = "Medián (${daysCount}d)", value = medianFormatted, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            StatBox(title = "Rozpětí Min – Max", value = minMaxFormatted, modifier = Modifier.weight(1f))
                            StatBox(title = "Obvyklé pásmo P25–P75", value = usualFormatted, modifier = Modifier.weight(1f))
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

                if (showInfoDialog) {
                    MetricInfoPopupDialog(type = type, cfg = cfg, onDismiss = { showInfoDialog = false })
                }
            }
        }
    }
}

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

fun Sport.badgeColor(): Color = when (this) {
    Sport.RUNNING -> Color(0xFFFF6B4A)
    Sport.CYCLING -> Color(0xFF00C3FF)
    Sport.WALKING -> Color(0xFF26E07F)
    Sport.HIKING -> Color(0xFF8BC34A)
    Sport.SWIMMING -> Color(0xFF2979FF)
    Sport.STRENGTH -> Color(0xFFFFB300)
    Sport.OTHER -> Color(0xFFAB47BC)
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

    val initialActs = remember {
        ScreenMemoryCache.activitySummaries ?: runCatching { sl.db.activities().allSummariesNow() }.getOrDefault(emptyList()).also {
            ScreenMemoryCache.activitySummaries = it
        }
    }
    val acts by sl.db.activities().allSummaries().collectAsState(initial = initialActs)
    LaunchedEffect(acts) {
        ScreenMemoryCache.activitySummaries = acts
    }

    val initialShared = remember { runCatching { sl.db.inbox().objectsNow("activity") }.getOrDefault(emptyList()) }
    val sharedRows by sl.db.inbox().objects("activity").collectAsState(initial = initialShared)
    val initialContacts = remember { runCatching { sl.db.contacts().allNow() }.getOrDefault(emptyList()) }
    val contacts by sl.db.contacts().all().collectAsState(initial = initialContacts)

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

    val unifiedList = remember(acts, sharedRows, contacts) {
        val mine = acts.map { row ->
            val sportEnum = runCatching { Sport.valueOf(row.sport) }.getOrDefault(Sport.OTHER)
            val title = row.title?.takeIf { it.isNotBlank() } ?: sportEnum.czechName()
            val zone = row.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
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
        val contactsMap = contacts.associate { it.identity to it.displayName }
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
        // Horní panel
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Aktivity & Trasy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "${aggregates.myCount} vlastních · ${aggregates.sharedCount} sdílených P2P",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
                Row {
                    Button(
                        onClick = {
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
                        },
                        enabled = !syncing,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Sync, contentDescription = "Garmin sync", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (syncing) "…" else "Garmin")
                        }
                    }
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = "Import FIT", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("FIT")
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

        // Hero Summary Card s přepínátkem časového okna (Týden / Měsíc / Rok / Celkem)
        item(key = "hero_summary") {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFF232A3B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "SOUHRN AKTIVIT",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                "${summaryActivities.size} ${if (summaryActivities.size == 1) "aktivita" else if (summaryActivities.size in 2..4) "aktivity" else "aktivit"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF38BDF8)
                            )
                        }
                        // Přepínátko: Týden / Měsíc / Rok / Celkem
                        Row(
                            Modifier
                                .background(Color(0xFF1E2433), RoundedCornerShape(10.dp))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            SummaryPeriod.entries.forEach { p ->
                                val selected = summaryPeriod == p
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) Color(0xFF38BDF8) else Color.Transparent)
                                        .clickable { summaryPeriod = p }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        p.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) Color(0xFF0F172A) else Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(fmt.km(summaryStats.distM), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Vzdálenost", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(fmt.duration(summaryStats.timeS), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Doba pohybu", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                        Column(Modifier.weight(0.8f)) {
                            Text(fmt.elevation(summaryStats.ascentM), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFFFFB300))
                            Text("Převýšení", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                        Column(Modifier.weight(0.8f)) {
                            Text("${summaryStats.calories.toInt()} kcal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                            Text("Kalorie", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                    }
                }
            }
        }

        // Kompaktní filtry v moderních Dropdown Menu (Zdroj, Sport, Období s multi-výběrem)
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
                        leadingIcon = {
                            Icon(
                                when {
                                    sourcesFilter == setOf("MINE") -> Icons.Outlined.Person
                                    sourcesFilter == setOf("SHARED") -> Icons.Outlined.Share
                                    else -> Icons.Outlined.FolderOpen
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        trailingIcon = {
                            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        label = {
                            Text(
                                when {
                                    sourcesFilter == setOf("MINE") -> "Moje (${aggregates.myCount})"
                                    sourcesFilter == setOf("SHARED") -> "Sdílené (${aggregates.sharedCount})"
                                    else -> "Zdroj: Vše"
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
                            leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
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
                            text = { Text("Zavřít", color = Color(0xFF38BDF8)) },
                            leadingIcon = { Icon(Icons.Outlined.Check, contentDescription = null, tint = Color(0xFF38BDF8)) },
                            onClick = { sourceMenuExpanded = false }
                        )
                    }
                }

                // 2. SPORT (s možností zaškrtnutí více sportů najednou)
                Box {
                    val isSportFiltered = sportsFilter.isNotEmpty()
                    FilterChip(
                        selected = isSportFiltered,
                        onClick = { sportMenuExpanded = true },
                        leadingIcon = {
                            Icon(
                                imageVector = if (sportsFilter.size == 1) sportsFilter.first().materialIcon() else Icons.AutoMirrored.Outlined.DirectionsRun,
                                contentDescription = null,
                                tint = if (sportsFilter.size == 1) sportsFilter.first().badgeColor() else if (isSportFiltered) MaterialTheme.colorScheme.primary else Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        trailingIcon = {
                            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        label = {
                            Text(
                                when {
                                    sportsFilter.isEmpty() -> "Sport: Vše"
                                    sportsFilter.size == 1 -> "${sportsFilter.first().czechName()} (${aggregates.sportCounts[sportsFilter.first()] ?: 0})"
                                    else -> "Sporty (${sportsFilter.size})"
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
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DirectionsRun, contentDescription = null) },
                            onClick = { sportsFilter = emptySet(); sportMenuExpanded = false }
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF232A3B)))
                        aggregates.availableSports.forEach { s ->
                            val cnt = aggregates.sportCounts[s] ?: 0
                            val isChecked = s in sportsFilter
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = s.materialIcon(),
                                            contentDescription = null,
                                            tint = s.badgeColor(),
                                            modifier = Modifier.size(18.dp)
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
                            text = { Text("Zavřít", color = Color(0xFF38BDF8)) },
                            leadingIcon = { Icon(Icons.Outlined.Check, contentDescription = null, tint = Color(0xFF38BDF8)) },
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
                        leadingIcon = {
                            Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
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
                            leadingIcon = { Icon(Icons.Outlined.CalendarToday, contentDescription = null) },
                            onClick = {
                                dateMenuExpanded = false
                                showDatePicker()
                            }
                        )
                    }
                }

                // Tlačítko rychlého zrušení filtrů, pokud je cokoliv aktivní
                if (sourcesFilter.size == 1 || sportsFilter.isNotEmpty() || dateFilter != ActivityDateFilter.ALL) {
                    IconButton(
                        onClick = {
                            sourcesFilter = emptySet()
                            sportsFilter = emptySet()
                            dateFilter = ActivityDateFilter.ALL
                            customDate = null
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Zrušit filtry",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Zobrazení aktivních chipů filtrů s možností okamžitého odebrání (✕)
        if (sourcesFilter.size == 1 || sportsFilter.isNotEmpty() || dateFilter != ActivityDateFilter.ALL) {
            item(key = "active_filter_chips") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Aktivní:", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    if (sourcesFilter == setOf("MINE")) {
                        FilterChip(
                            selected = true,
                            onClick = { sourcesFilter = emptySet() },
                            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Zrušit", modifier = Modifier.size(14.dp)) },
                            label = { Text("Moje aktivity", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    if (sourcesFilter == setOf("SHARED")) {
                        FilterChip(
                            selected = true,
                            onClick = { sourcesFilter = emptySet() },
                            leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Zrušit", modifier = Modifier.size(14.dp)) },
                            label = { Text("Sdílené P2P", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    sportsFilter.forEach { s ->
                        FilterChip(
                            selected = true,
                            onClick = { sportsFilter = sportsFilter - s },
                            leadingIcon = { Icon(s.materialIcon(), contentDescription = null, tint = s.badgeColor(), modifier = Modifier.size(14.dp)) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Zrušit", modifier = Modifier.size(14.dp)) },
                            label = { Text(s.czechName(), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    if (dateFilter != ActivityDateFilter.ALL) {
                        FilterChip(
                            selected = true,
                            onClick = { dateFilter = ActivityDateFilter.ALL; customDate = null },
                            leadingIcon = { Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Zrušit", modifier = Modifier.size(14.dp)) },
                            label = { Text(if (dateFilter == ActivityDateFilter.CUSTOM_DATE && customDate != null) "$customDate" else dateFilter.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    TextButton(
                        onClick = {
                            sourcesFilter = emptySet()
                            sportsFilter = emptySet()
                            dateFilter = ActivityDateFilter.ALL
                            customDate = null
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("Vymazat filtry", style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF4444))
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item(key = "empty") {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Žádné aktivity v tomto filtru", style = MaterialTheme.typography.titleMedium)
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

@Composable
private fun ActivityCardItem(
    a: UnifiedActivityItem,
    isChecked: Boolean,
    onToggleCompare: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val dist = a.distanceM
    val pace = if (dist != null && dist > 0 && a.movingTimeS != null) (a.movingTimeS / (dist / 1000.0)) else null
    val cardBg = if (isChecked) Color(0xFF132338) else Color(0xFF131722)

    val bgRes = when (a.sport) {
        Sport.RUNNING -> R.drawable.img_motif_running_stride
        Sport.WALKING -> R.drawable.img_motif_steps_trail
        Sport.HIKING -> R.drawable.img_alpine_trail_hero
        Sport.SWIMMING -> R.drawable.img_motif_body_water
        Sport.STRENGTH -> R.drawable.img_motif_bone_mass
        Sport.CYCLING -> R.drawable.img_nordic_wellness_hero
        Sport.OTHER -> R.drawable.img_nordic_wellness_hero
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (isChecked) Color(0xFF38BDF8)
            else if (a.isShared) Color(0xFF10B981).copy(alpha = 0.4f)
            else Color(0xFF232A3B)
        ),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Box(Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = bgRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .alpha(0.28f)
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                cardBg.copy(alpha = 0.95f),
                                cardBg.copy(alpha = 0.85f),
                                cardBg.copy(alpha = 0.50f)
                            )
                        )
                    )
            )

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            // Checkbox pro Compare
            Checkbox(
                checked = isChecked,
                onCheckedChange = onToggleCompare,
                modifier = Modifier.padding(end = 4.dp)
            )

            // Sportovní ikona
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = a.sport.badgeColor().copy(alpha = 0.18f)),
                border = BorderStroke(1.dp, a.sport.badgeColor().copy(alpha = 0.35f)),
                modifier = Modifier.padding(end = 10.dp)
            ) {
                Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = a.sport.materialIcon(),
                        contentDescription = a.sport.czechName(),
                        tint = a.sport.badgeColor(),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Detaily aktivity
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Text(
                            a.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (a.isShared) {
                            Spacer(Modifier.width(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(Color(0xFF10B981).copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Outlined.Share, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(10.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    "P2P: ${a.ownerName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = Color(0xFF10B981),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Text(
                        a.startUtcMs?.let(fmt::time) ?: a.localDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(fmt.km(a.distanceM), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("Vzdálenost", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                    Column {
                        Text(a.movingTimeS?.let(fmt::duration) ?: "—", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("Čas", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                    Column {
                        Text(fmt.pace(pace), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("Tempo", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                    Column {
                        Text(a.avgHeartRate?.let { "$it bpm" } ?: "—", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF5252))
                        Text("Tep", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                }

                // Druhá řada Garmin ukazatelů (Kalorie, Training Effect, Výkon, Zátěž)
                if (a.caloriesKcal != null || a.aerobicTe != null || a.avgPowerW != null || a.trainingLoad != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        a.caloriesKcal?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("${it.toInt()} kcal", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF59E0B))
                            }
                        }
                        a.aerobicTe?.let { ae ->
                            val an = a.anaerobicTe ?: 0.0
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Bolt, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("TE ${fmt.number(ae, 1)} / ${fmt.number(an, 1)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981))
                            }
                        }
                        a.avgPowerW?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Bolt, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("${it.toInt()} W", style = MaterialTheme.typography.labelSmall, color = Color(0xFF38BDF8))
                            }
                        }
                        a.trainingLoad?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Assessment, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("Zátěž ${it.toInt()}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFA855F7))
                            }
                        }
                    }
                }
            }

            Text("›", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF64748B), modifier = Modifier.padding(start = 4.dp))
        }
    }
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
        val t = sl.db.activities().track(key)?.let {
            runCatching { Wire.json.decodeFromString(ListSerializer(TrackPoint.serializer()), it.json) }.getOrElse { err ->
                android.util.Log.e("Pulse4", "loadUnifiedActivity: failed to decode track json for key=$key", err)
                null
            }
        }.orEmpty()
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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // Horní návratová lišta + Hero Header
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                TextButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Zpět",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Zpět", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                }
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = act.sport.badgeColor().copy(alpha = 0.2f)),
                    border = BorderStroke(1.dp, act.sport.badgeColor().copy(alpha = 0.4f)),
                    modifier = Modifier.padding(end = 10.dp)
                ) {
                    Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = act.sport.materialIcon(),
                            contentDescription = act.sport.czechName(),
                            tint = act.sport.badgeColor(),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Column {
                    Text(act.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (dom?.locationName != null) {
                            Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "${dom.locationName} · ",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Text(
                            "${act.startUtcMs?.let(fmt::time) ?: act.localDate} · ${act.ownerName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
            if (act.isShared) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xFF10B981).copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "P2P",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Rozdělení detailu aktivity do 4 čistých sekcí s Material 3 ikonami
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                Triple(0, "Přehled & Mapa", Icons.Outlined.Map),
                Triple(1, "Grafy & Zóny", Icons.AutoMirrored.Outlined.ShowChart),
                Triple(2, "Tabulky & Statistiky", Icons.Outlined.TableChart),
                Triple(3, "Mezičasy & Kola", Icons.Outlined.Flag)
            ).forEach { (idx, label, icon) ->
                FilterChip(
                    selected = detailSectionTab == idx,
                    onClick = { detailSectionTab = idx },
                    leadingIcon = {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (detailSectionTab == idx) MaterialTheme.colorScheme.primary else Color(0xFF94A3B8)
                        )
                    },
                    label = { Text(label, maxLines = 1) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 1. MAPA TRASY S MAPOVÝM PODKLADEM A RYCHLOU VOLBOU PROVIDERA (pouze v sekci Přehled & Mapa)
        if (detailSectionTab == 0) {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFF232A3B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    val pts = route.filterNotNull()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Map, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("MAPA A GPS TRASA", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                        }
                        Text(
                            if (pts.isNotEmpty()) "${pts.size} GPS bodů · ${mapProvider.shortLabel}" else "Bez GPS bodů",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pts.isNotEmpty()) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                        )
                    }

                    // Rychlý přepínač mapového podkladu přímo u mapy (ukládá se i do Nastavení)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            MapProvider.CARTO_DARK,
                            MapProvider.OSM_STANDARD,
                            MapProvider.OPENTOPOMAP,
                            MapProvider.CARTO_VOYAGER,
                            MapProvider.ESRI_SATELLITE,
                            MapProvider.OFFLINE_CANVAS
                        ).forEach { mp ->
                            FilterChip(
                                selected = mapProvider == mp,
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        sl.settings.mapProvider = mp
                                    }
                                },
                                label = { Text(mp.shortLabel, maxLines = 1) }
                            )
                        }
                    }

                    if (pts.size >= 2) {
                        val cursorCoord = trackCursor?.let { route.getOrNull(it) }
                        val highlightPts = trackRange?.let { rng ->
                            route.subList(rng.first.coerceIn(0, route.size), (rng.last + 1).coerceIn(0, route.size)).filterNotNull()
                        }.orEmpty()

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

                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(Color(0xFF10B981), CircleShape))
                                Spacer(Modifier.width(4.dp))
                                Text("Start", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                            }
                            Text(mapProvider.attribution, style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(Color(0xFFFF5252), CircleShape))
                                Spacer(Modifier.width(4.dp))
                                Text("Cíl", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Text(
                            "Tato aktivita neobsahuje GPS souřadnice.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 2. HLAVNÍ GARMIN PŘEHLED (6 klíčových ukazatelů — pouze v sekci Přehled & Mapa)
        if (detailSectionTab == 0) {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFF232A3B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "HLAVNÍ TELEMETRICKÉ UKAZATELE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(fmt.km(dist), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Vzdálenost", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(fmt.duration(durationS), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Čas pohybu", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(fmt.pace(pace), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                            Text("Prům. tempo", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                act.avgHeartRate?.let { "$it bpm" } ?: "—",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF5252)
                            )
                            Text("Prům. tep (max ${act.maxHeartRate ?: "—"})", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "+${fmt.elevation(ascent)}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFB300)
                            )
                            Text("Stoupání (-${fmt.elevation(descent)})", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                dom?.caloriesKcal?.let { "${it.toInt()} kcal" } ?: "—",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                            Text("Spálené kalorie", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 3. GARMIN TRAINING EFFECT, STAMINA, KONDICE & STRAVA (v sekci Přehled & Mapa)
        if (detailSectionTab == 0) {
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

            // Garmin Stamina Hero Card
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

            // Garmin Performance Condition Hero Card
            val perfCond = track.lastOrNull { it.performanceCondition != null }?.performanceCondition
            if (perfCond != null) {
                GarminPerformanceConditionCard(condition = perfCond)
                Spacer(Modifier.height(12.dp))
            }

            // Kardiovaskulární úsilí (Suffer score)
            RelativeEffortCard(
                score = stravaEffort,
                hrZonesTimesS = dom?.hrZoneTimesS ?: emptyList(),
                totalTimeS = act.totalTimeS
            )
            Spacer(Modifier.height(12.dp))

            // GAP (Tempo srovnané se sklonem)
            if (gapPace != null) {
                GapCard(
                    realPaceSPerKm = realPace,
                    gapPaceSPerKm = gapPace,
                    ascentM = act.ascentM
                )
                Spacer(Modifier.height(12.dp))
            }

            // Atletické milníky (Best Efforts)
            if (stravaBestEfforts.isNotEmpty()) {
                BestEffortsCard(bestEfforts = stravaBestEfforts)
                Spacer(Modifier.height(12.dp))
            }
        }

        if (detailSectionTab == 0) {
            // Rozcestník na samostatné sekce (aby se nemíchaly tabulky s grafy)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(
                    modifier = Modifier.weight(1f).clickable { detailSectionTab = 1 },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Outlined.ShowChart, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Grafy & Zóny", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Křivky tepu, tempa, výšky, kadence a Z1–Z5", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                }
                Card(
                    modifier = Modifier.weight(1f).clickable { detailSectionTab = 2 },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.4f)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.TableChart, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Tabulky & Data", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFFA855F7))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Dynamika běhu, výkon, převýšení a kalorie", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 4. TELEMETRICKÉ GRAFY PRŮBĚHU (POUZE V SEKCI GRAFY & ZÓNY — bez míchání s tabulkami)
        if (detailSectionTab == 1) {
            val availableChannels = Channel.entries.filter { view.hasChannel(it) && it != Channel.GROUND_CONTACT_BALANCE }
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFF232A3B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Outlined.ShowChart, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "GRAFY PRŮBĚHU AKTIVITY (${availableChannels.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Row {
                            FilterChip(
                                selected = axis == Axis.TIME,
                                onClick = { axis = Axis.TIME; range = null },
                                leadingIcon = { Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(14.dp)) },
                                label = { Text("Čas") },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            FilterChip(
                                selected = axis == Axis.DISTANCE,
                                onClick = { axis = Axis.DISTANCE; range = null },
                                leadingIcon = { Icon(Icons.Outlined.Straighten, contentDescription = null, modifier = Modifier.size(14.dp)) },
                                label = { Text("Vzdál.") }
                            )
                        }
                    }

                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = showAllChartsStacked,
                            onClick = { showAllChartsStacked = true },
                            leadingIcon = { Icon(Icons.Outlined.ViewStream, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            label = { Text("Všechny grafy pod sebou (Garmin styl)") }
                        )
                        FilterChip(
                            selected = !showAllChartsStacked,
                            onClick = { showAllChartsStacked = false },
                            leadingIcon = { Icon(Icons.Outlined.Crop, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            label = { Text("Výřez úseku") }
                        )
                    }

                    if (availableChannels.isEmpty()) {
                        Text(
                            "Pro tento záznam nejsou k dispozici bodové křivky.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    } else if (showAllChartsStacked) {
                        // Vykreslit VŠECHNY dostupné Garmin grafy pod sebou (Tep, Tempo/Rychlost, Výška, Kadence, Výkon)
                        availableChannels.forEach { ch ->
                            val chChart = remember(view, ch, axis) { view.chart(ch, axis, buckets = 160) }
                            val chartStats = remember(chChart, ch) {
                                val finite = chChart.values.filterNotNull().filter { it.isFinite() }
                                if (finite.isEmpty()) null
                                else {
                                    val avgVal = finite.average()
                                    val maxVal = finite.max()
                                    val minVal = finite.min()
                                    val header = when (ch) {
                                        Channel.HEART_RATE -> "Průměr ${fmt.number(avgVal, 0)} bpm · Max ${fmt.number(maxVal, 0)} bpm"
                                        Channel.SPEED -> {
                                            val avgPace = if (avgVal > 0.2) 1000.0 / avgVal else null
                                            val maxKmh = maxVal * 3.6
                                            "Prům. tempo ${fmt.pace(avgPace)} · Max ${fmt.number(maxKmh, 1)} km/h"
                                        }
                                        Channel.ELEVATION -> "Min ${fmt.elevation(minVal)} · Max ${fmt.elevation(maxVal)}"
                                        Channel.CADENCE -> "Průměr ${fmt.number(avgVal, 0)} spm · Max ${fmt.number(maxVal, 0)} spm (XY body)"
                                        Channel.POWER -> "Průměr ${fmt.number(avgVal, 0)} W · Max ${fmt.number(maxVal, 0)} W"
                                        Channel.STRIDE_LENGTH -> "Průměr ${fmt.number(avgVal, 2)} m · Max ${fmt.number(maxVal, 2)} m (XY body)"
                                        Channel.GROUND_CONTACT_TIME -> "Průměr ${fmt.number(avgVal, 0)} ms · Min ${fmt.number(minVal, 0)} ms (XY body)"
                                        Channel.GROUND_CONTACT_BALANCE -> "Průměr ${fmt.number(avgVal, 1)} % L / ${fmt.number(100.0 - avgVal, 1)} % P"
                                        Channel.VERTICAL_OSCILLATION -> "Průměr ${fmt.number(avgVal, 1)} cm · Max ${fmt.number(maxVal, 1)} cm (XY body)"
                                        Channel.VERTICAL_RATIO -> "Průměr ${fmt.number(avgVal, 1)} % · Min ${fmt.number(minVal, 1)} % (XY body)"
                                        Channel.STAMINA -> "Konec ${fmt.number(finite.last(), 0)} % · Min ${fmt.number(minVal, 0)} %"
                                        Channel.PERFORMANCE_CONDITION -> "Počátek ${fmt.number(finite.first(), 0)} · Konec ${fmt.number(finite.last(), 0)}"
                                    }
                                    header
                                }
                            }
                            if (chartStats != null) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            ch.label(),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = ch.color()
                                        )
                                        ch.toTelemetryTopic()?.let { topic ->
                                            Spacer(Modifier.width(6.dp))
                                            InfoBadgeButton(onClick = { activeInfoTopic = topic })
                                        }
                                    }
                                    Text(chartStats, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
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

                        cursor?.let { c ->
                            chart.trackIndex.getOrNull(c)?.let { ti ->
                                Spacer(Modifier.height(6.dp))
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("ODEČET VYBRANÉHO BODU NA VŠECH GRAFECH A MAPĚ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                                        }
                                        Spacer(Modifier.height(4.dp))
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
                            FlowRow(Modifier.weight(1f)) {
                                availableChannels.forEach { c ->
                                    FilterChip(
                                        selected = channel == c,
                                        onClick = { channel = c },
                                        label = { Text(c.label()) },
                                        modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                                    )
                                }
                            }
                            channel.toTelemetryTopic()?.let { topic ->
                                InfoBadgeButton(onClick = { activeInfoTopic = topic }, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
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
                                Text("Označit úsek tažením", style = MaterialTheme.typography.bodySmall)
                                Switch(select, { select = it; if (!it) range = null }, Modifier.padding(start = 6.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Jen pohybový čas", style = MaterialTheme.typography.bodySmall)
                                Switch(movingOnly, { movingOnly = it }, Modifier.padding(start = 6.dp))
                            }
                        }
                        if (range != null) {
                            Spacer(Modifier.height(8.dp))
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("SOUHRN OZNAČENÉHO ÚSEKU", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
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
            Spacer(Modifier.height(12.dp))
        }

        // 5. GRAFY ČASU V TEPOVÝCH ZÓNÁCH (Z1–Z5) A VÝKONOVÝCH ZÓNÁCH (Z1–Z5) (pouze v sekci Grafy & Zóny)
        if (detailSectionTab == 1) {
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
                    Triple("Zóna 2 · Snadné (60–70 %)", hrZones[1], Color(0xFF38BDF8)),
                    Triple("Zóna 1 · Zahřátí (50–60 %)", hrZones[0], Color(0xFF94A3B8))
                )
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Favorite, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "ČAS V TEPOVÝCH ZÓNÁCH (GARMIN Z1–Z5)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            InfoBadgeButton(onClick = { activeInfoTopic = TelemetryTopic.HR_ZONES })
                        }
                        Spacer(Modifier.height(10.dp))
                        zoneConfigs.forEach { (zTitle, zSec, zColor) ->
                            val pct = (zSec / totalZ).toFloat().coerceIn(0f, 1f)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(zTitle, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text("${fmt.duration(zSec)} (${(pct * 100).toInt()} %)", style = MaterialTheme.typography.labelSmall, color = zColor, fontWeight = FontWeight.Bold)
                            }
                            LinearProgressIndicator(
                                progress = { pct },
                                modifier = Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(5.dp)),
                                color = zColor,
                                trackColor = Color(0xFF1E293B)
                            )
                            Spacer(Modifier.height(6.dp))
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
                    Triple("Zóna 4 · Prahový výkon", pwrZones[3], Color(0xFFF59E0B)),
                    Triple("Zóna 3 · Tempový výkon", pwrZones[2], Color(0xFF10B981)),
                    Triple("Zóna 2 · Vytrvalostní výkon", pwrZones[1], Color(0xFF38BDF8)),
                    Triple("Zóna 1 · Aktivní regenerace", pwrZones[0], Color(0xFF94A3B8))
                )
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Bolt, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "ČAS VE VÝKONOVÝCH ZÓNÁCH (WATTY Z1–Z5)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            InfoBadgeButton(onClick = { activeInfoTopic = TelemetryTopic.POWER_ZONES })
                        }
                        Spacer(Modifier.height(10.dp))
                        pwrConfigs.forEach { (zTitle, zSec, zColor) ->
                            val pct = (zSec / totalP).toFloat().coerceIn(0f, 1f)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(zTitle, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text("${fmt.duration(zSec)} (${(pct * 100).toInt()} %)", style = MaterialTheme.typography.labelSmall, color = zColor, fontWeight = FontWeight.Bold)
                            }
                            LinearProgressIndicator(
                                progress = { pct },
                                modifier = Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(5.dp)),
                                color = zColor,
                                trackColor = Color(0xFF1E293B)
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // 6. MEZIČASY, KOLA (LAPS), FÁZE POHYBU (SPLITS) & NEJRYCHLEJŠÍ ÚSEKY (pouze v sekci Mezičasy & Kola)
        if (detailSectionTab == 3) {
            // Nejrychlejší úseky (Best Efforts z Garminu)
            if (dom?.fastestSplit1kS != null || dom?.fastestSplit1miS != null || dom?.fastestSplit5kS != null) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.EmojiEvents, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "NEJRYCHLEJŠÍ ÚSEKY V AKTIVITĚ (GARMIN BEST SPLITS)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF59E0B)
                                )
                            }
                            InfoBadgeButton(onClick = { activeInfoTopic = TelemetryTopic.BEST_EFFORTS })
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            dom.fastestSplit1kS?.let {
                                Column(Modifier.weight(1f)) {
                                    Text(fmt.duration(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                    Text("Nejrychlejší 1 km", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                            }
                            dom.fastestSplit1miS?.let {
                                Column(Modifier.weight(1f)) {
                                    Text(fmt.duration(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                    Text("Nejrychlejší 1 míle", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                            }
                            dom.fastestSplit5kS?.let {
                                Column(Modifier.weight(1f)) {
                                    Text(fmt.duration(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                    Text("Nejrychlejší 5 km", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Tabulka a graf Kol / Mezičasů (Laps)
            val laps = dom?.laps.orEmpty()
            if (laps.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Flag, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "MEZIČASY A KOLA (${laps.size} KOL)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            InfoBadgeButton(onClick = { activeInfoTopic = TelemetryTopic.LAPS })
                        }
                        Spacer(Modifier.height(8.dp))

                        val maxSpeedInLaps = laps.mapNotNull { it.avgSpeedMps }.maxOrNull()?.coerceAtLeast(0.5) ?: 4.0

                        // Hlavička tabulky kol
                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            Text("#", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
                            Text("Čas", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
                            Text("Vzdál.", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
                            Text("Tempo", Modifier.weight(1.1f), style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
                            Text("Tep", Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
                        }

                        laps.take(25).forEach { lap ->
                            val lapDist = lap.distanceM
                            val lapPace = if (lapDist != null && lapDist > 10.0) lap.durationS / (lapDist / 1000.0) else null
                            val spdRatio = ((lap.avgSpeedMps ?: 1.0) / maxSpeedInLaps).toFloat().coerceIn(0.15f, 1f)
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${lap.index}", Modifier.width(28.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                                    Text(fmt.duration(lap.durationS), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Color.White)
                                    Text(fmt.km(lap.distanceM), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Color.White)
                                    Text(fmt.pace(lapPace), Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                                    Text(lap.avgHeartRate?.let { "$it bpm" } ?: "—", Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF5252))
                                }
                                Spacer(Modifier.height(2.dp))
                                LinearProgressIndicator(
                                    progress = { spdRatio },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = Color(0xFF38BDF8).copy(alpha = 0.7f),
                                    trackColor = Color(0xFF1E293B)
                                )
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
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Sync, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "ROZPAD FÁZÍ AKTIVITY (GARMIN SPLIT SUMMARIES)",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        splits.forEach { sp ->
                            val spDist = sp.distanceM
                            val spPace = if (spDist != null && spDist > 10.0) sp.durationS / (spDist / 1000.0) else null
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2030))
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(formatSplitTypeLabel(sp.splitType), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text("${sp.noOfSplits}× úsek · ${fmt.duration(sp.durationS)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF38BDF8))
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Vzdál.: ${fmt.km(sp.distanceM)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                        Text("Tempo: ${fmt.pace(spPace)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981))
                                        sp.avgCadence?.takeIf { it > 0 }?.let {
                                            Text("Kadence: ${fmt.number(it, 0)} spm", style = MaterialTheme.typography.labelSmall, color = Color(0xFFA855F7))
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

        // 7. PODROBNÉ GARMIN TABULKY STATISTIK (pouze v sekci Tabulky & Statistiky — bez grafů)
        if (detailSectionTab == 2) {
            // A) Dynamika běhu / chůze (Running Dynamics)
            if (dom?.avgCadence != null || dom?.avgStrideLengthCm != null || dom?.avgVerticalOscillationCm != null || dom?.steps != null || track.any { it.cadence != null || it.strideLengthM != null }) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Outlined.DirectionsRun, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "DYNAMIKA BĚHU A POHYBU (GARMIN RUNNING DYNAMICS)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFA855F7)
                                )
                            }
                            InfoBadgeButton(onClick = { activeInfoTopic = TelemetryTopic.RUNNING_DYNAMICS })
                        }
                        Spacer(Modifier.height(8.dp))
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

                        effCad?.let { LabeledRow("Průměrná kadence (spm)", "${fmt.number(it, 0)} kroků/min") }
                        effMaxCad?.let { LabeledRow("Maximální kadence (spm)", "${fmt.number(it, 0)} kroků/min") }
                        effStrideM?.let { LabeledRow("Průměrná délka kroku", "${fmt.number(it, 2)} m (${fmt.number(it * 100.0, 1)} cm)") }
                        effGct?.let { LabeledRow("Doba kontaktu se zemí (GCT)", "${fmt.number(it, 0)} ms") }
                        effBal?.let {
                            Spacer(Modifier.height(6.dp))
                            GarminGroundContactBalanceMeter(
                                balanceLeftPct = it,
                                onInfoClick = { activeInfoTopic = TelemetryTopic.GROUND_CONTACT_BALANCE }
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        effVo?.let { LabeledRow("Průměrná vertikální oscilace", "${fmt.number(it, 1)} cm") }
                        effVr?.let { LabeledRow("Průměrný vertikální poměr", "${fmt.number(it, 1)} %") }
                        if (stamVals.isNotEmpty()) {
                            LabeledRow("Stamina (Zbývající výdrž)", "Výchozí 100 % → Konec ${fmt.number(stamVals.last(), 0)} % (min ${fmt.number(stamVals.min(), 0)} %)")
                        }
                        if (condVals.isNotEmpty()) {
                            val lastC = condVals.last()
                            LabeledRow("Zhodnocení kondice", if (lastC > 0) "+$lastC" else "$lastC")
                        }
                        dom?.steps?.let { LabeledRow("Celkový počet kroků v aktivitě", "$it kroků") }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Atletické milníky v tabulkách
                if (stravaBestEfforts.isNotEmpty()) {
                    BestEffortsCard(stravaBestEfforts)
                    Spacer(Modifier.height(12.dp))
                }
            }

            // B) Výkon (Running / Cycling Power)
            if (dom?.avgPowerW != null || dom?.normPowerW != null || dom?.maxPowerW != null) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Bolt, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "VÝKONOVÉ UKAZATELE (POWER METRICS)",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF59E0B)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        dom.avgPowerW?.let { LabeledRow("Průměrný výkon", "${fmt.number(it, 0)} W") }
                        dom.normPowerW?.let { LabeledRow("Normalizovaný výkon (NP®)", "${fmt.number(it, 0)} W") }
                        dom.maxPowerW?.let { LabeledRow("Maximální výkon", "${fmt.number(it, 0)} W") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // C) Tempo, Rychlost & Výškový profil
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFF232A3B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Terrain, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "TEMPO, RYCHLOST, ČAS A NADMOŘSKÁ VÝŠKA",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
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
            Spacer(Modifier.height(12.dp))

            // D) Kalorie, Výživa & Hydratace
            if (dom?.caloriesKcal != null || dom?.waterEstimatedMl != null) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "ENERGETICKÝ VÝDEJ A HYDRATACE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        val totalCal = dom.caloriesKcal ?: 0.0
                        val bmrCal = dom.bmrCaloriesKcal ?: 0.0
                        val activeCal = (totalCal - bmrCal).coerceAtLeast(0.0)
                        if (bmrCal > 0) {
                            LabeledRow("Aktivní spálené kalorie", "${activeCal.toInt()} kcal")
                            LabeledRow("Klidový metabolismus (BMR)", "${bmrCal.toInt()} kcal")
                        }
                        LabeledRow("Celkem spálené kalorie", "${totalCal.toInt()} kcal")
                        dom.waterEstimatedMl?.let { LabeledRow("Odhad ztráty tekutin (pocení)", "${it.toInt()} ml (${fmt.number(it / 1000.0, 2)} l)") }
                        dom.bodyBatteryImpact?.let { LabeledRow("Změna Body Battery", "$it bodů") }
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
 * Umožňuje porovnat 2 až 4 aktivity (vlastní i sdílené přes P2P) v tabulce vedle sebe,
 * na společném překryvném grafu křivek (Grafana styl) a na společné mapě tras!
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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.CompareArrows, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Porovnání Aktivit",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "${items.size} porovnávané aktivity · Garmin & Grafana styl",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onBack, modifier = Modifier.padding(start = 6.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Zpět",
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Zpět", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), maxLines = 1, softWrap = false)
            }
        }

        Spacer(Modifier.height(10.dp))

        // Tabulkové srovnání metrik vedle sebe
        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "SROVNÁVACÍ TABULKA UKAZATELŮ",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.height(10.dp))

                // Hlavička sloupců
                Row(Modifier.fillMaxWidth()) {
                    Text("Parametr", Modifier.weight(1.1f), style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), maxLines = 1)
                    items.forEachIndexed { idx, act ->
                        val col = palette[idx % palette.size]
                        Column(Modifier.weight(1f).padding(horizontal = 2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = act.sport.materialIcon(),
                                    contentDescription = null,
                                    tint = col,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    act.title.take(12),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
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
                                fontWeight = FontWeight.SemiBold,
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
        SectionTitle("Překryvné porovnání křivek (Grafana styl)")
        FlowRow {
            Channel.entries.forEach { c ->
                FilterChip(
                    selected = channel == c,
                    onClick = { channel = c },
                    label = { Text(c.label()) },
                    modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                )
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
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFF232A3B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Map, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "PŘEKRYVNÉ POROVNÁNÍ GPS TRAS (${mapProvider.shortLabel})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
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
