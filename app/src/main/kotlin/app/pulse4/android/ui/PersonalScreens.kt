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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.pulse4.android.data.MapProvider
import app.pulse4.android.data.toRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import app.pulse4.android.data.toDomain
import app.pulse4.android.di.FitImportResult
import app.pulse4.android.di.ServiceLocator
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

data class MetricUiConfig(
    val icon: String,
    val title: String,
    val shortLabel: String,
    val unit: String,
    val color: Color,
    val explanation: String
)

fun getMetricUiConfig(type: MetricType): MetricUiConfig = when (type) {
    MetricType.HRV -> MetricUiConfig(
        icon = "💓",
        title = "Variabilita tepu (HRV)",
        shortLabel = "HRV",
        unit = "ms",
        color = Color(0xFF10B981),
        explanation = "Noční HRV (rMSSD) odráží rovnováhu autonomního nervového systému. Vyšší hodnoty v rámci vašeho pásma značí vysokou připravenost na zátěž a efektivní regeneraci."
    )
    MetricType.RESTING_HEART_RATE -> MetricUiConfig(
        icon = "❤️",
        title = "Klidový tep (RHR)",
        shortLabel = "Klidový tep",
        unit = "bpm",
        color = Color(0xFFEF4444),
        explanation = "Klidová srdeční frekvence měřená ve spánku. Trvalé snížení značí růst aerobní kondice; vzestup o více než 5 bpm může signalizovat začínající nemoc, únavu či alkohol."
    )
    MetricType.SLEEP_DURATION -> MetricUiConfig(
        icon = "🛌",
        title = "Doba spánku",
        shortLabel = "Spánek",
        unit = "hod",
        color = Color(0xFF38BDF8),
        explanation = "Celková doba nočního spánku. Dlouhodobý spánek 7–9 hodin je klíčový pro buněčnou a svalovou obnovu, syntézu růstového hormonu a konsolidaci paměti."
    )
    MetricType.BODY_BATTERY -> MetricUiConfig(
        icon = "⚡",
        title = "Body Battery",
        shortLabel = "B.Battery",
        unit = "/ 100",
        color = Color(0xFFF59E0B),
        explanation = "Komplexní energetické skóre Garminu (0–100) kalkulované z HRV, spánku, stresu a denních aktivit. Ranní hodnota 75+ značí plně dobité baterie."
    )
    MetricType.STRESS -> MetricUiConfig(
        icon = "🧘",
        title = "Hladina stresu",
        shortLabel = "Stres",
        unit = "/ 100",
        color = Color(0xFFA855F7),
        explanation = "Fyziologický stres měřený z odchylek intervalů tepu (0–25 odpočinek, 26–50 mírný, 51–75 střední, 76–100 vysoký stres). Zobrazuje aktivaci sympatiku."
    )
    MetricType.STEPS -> MetricUiConfig(
        icon = "👣",
        title = "Denní kroky",
        shortLabel = "Kroky",
        unit = "kroků",
        color = Color(0xFF3B82F6),
        explanation = "Počet kroků za den jako ukazatel spontánní neúmyslné fyzické aktivity (NEAT). Pravidelný pohyb podporuje kardiovaskulární zdraví a inzulinovou senzitivitu."
    )
    MetricType.WEIGHT -> MetricUiConfig(
        icon = "⚖️",
        title = "Tělesná hmotnost",
        shortLabel = "Váha",
        unit = fmt.weightUnit(),
        color = Color(0xFF14B8A6),
        explanation = "Dlouhodobý trend tělesné hmotnosti sloučený ze všech připojených vah (Withings, Health Connect, Garmin), očištěný od krátkodobých výkyvů vody."
    )
    MetricType.BODY_FAT -> MetricUiConfig(
        icon = "💧",
        title = "Tělesný tuk",
        shortLabel = "Tuk",
        unit = "%",
        color = Color(0xFF06B6D4),
        explanation = "Procentuální podíl tělesné tukové tkáně. Pro sledování sportovní formy a složení těla je spolehlivějším ukazatelem než samotné BMI."
    )
    MetricType.SPO2 -> MetricUiConfig(
        icon = "🫁",
        title = "Okysličení krve (SpO2)",
        shortLabel = "SpO2",
        unit = "%",
        color = Color(0xFF0EA5E9),
        explanation = "Procento nasycení periferní krve kyslíkem. Běžné noční hodnoty u zdravého člověka se pohybují v rozmezí 95–100 %."
    )
    else -> MetricUiConfig(
        icon = "📊",
        title = type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
        shortLabel = type.name.lowercase().replace('_', ' '),
        unit = type.unit,
        color = Color(0xFF6366F1),
        explanation = "Biometrická metrika zaznamenávaná vaším nositelným zařízením."
    )
}

data class TodayDashboardBundle(
    val today: LocalDate,
    val cards: List<app.pulse4.ui.Card>,
    val seriesByType: Map<MetricType, MetricSeries>,
    val todaySleepScore: Double?,
    val latestWeightObs: app.pulse4.domain.MetricObservation?,
    val garminLastSyncMs: Long?,
    val withingsLastSyncMs: Long?,
)

/**
 * Obrazovka Dnes:
 * Moderní Oura Ring / Garmin Connect / Grafana Singlestat přehled, kde každá karta má na pozadí
 * vykreslenou historickou křivku s volitelným časovým oknem (7d / 14d / 30d / 90d).
 * Karta Váhy zobrazuje poslední váhu a při starším datu odliší hodnotu barevně.
 * Časy posledního syncu se zobrazují pouze u zdrojů, které mají skutečně aktivní synchronizaci.
 */
@Composable
fun TodayScreen(sl: ServiceLocator, onOpenMetric: (MetricType) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var bgWindowDays by remember { mutableStateOf(14) }

    val states by sl.db.areaStates().all().collectAsState(initial = emptyList())
    val units by sl.settings.unitSystemFlow.collectAsState()

    val dashboardData by io(Triple(states.map { it.area to it.lastSuccessMs }, bgWindowDays, units)) {
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

        TodayDashboardBundle(
            today = today,
            cards = TodayCards.build(obsToday, success, fmt),
            seriesByType = seriesByType,
            todaySleepScore = todaySleepScore,
            latestWeightObs = latestWeightObs,
            garminLastSyncMs = garminLastSyncMs,
            withingsLastSyncMs = withingsLastSyncMs
        )
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
        // Horní lišta Dnes + Synchronizace
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
            Button(
                onClick = {
                    syncing = true
                    syncMessage = null
                    app.pulse4.android.work.Scheduler.importNow(ctx)
                    scope.launch {
                        kotlinx.coroutines.delay(2500)
                        syncing = false
                        syncMessage = "✅ Synchronizace na pozadí spuštěna."
                    }
                },
                enabled = !syncing
            ) {
                Text(if (syncing) "Stahuji…" else "🔄 Sync")
            }
        }

        // Řádek s časem posledního syncu — zobrazí POUZE ty zdroje, které skutečně mají synchronizaci
        if (loaded.garminLastSyncMs != null || loaded.withingsLastSyncMs != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF131722))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                loaded.garminLastSyncMs?.let { gMs ->
                    Text(
                        "⌚ Garmin sync: ${fmt.time(gMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                loaded.withingsLastSyncMs?.let { wMs ->
                    Text(
                        "⚖️ Withings sync: ${fmt.time(wMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF10B981),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        syncMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF10B981), modifier = Modifier.padding(top = 4.dp))
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
            Text(
                "📊 Okno na pozadí karet:",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF94A3B8)
            )
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

        // Singlestat karty s grafem na pozadí (pouze dnešní hodnoty, bez „poslední známá“)
        cards.forEach { card ->
            val mType = keyToType[card.key] ?: MetricType.HRV
            val cfg = getMetricUiConfig(mType)
            val s = seriesByType[mType]
            val rawVals = s?.values ?: emptyList()
            val chartVals = if (mType == MetricType.SLEEP_DURATION) rawVals.map { it?.div(3600.0) } else rawVals

            val usual = s?.usual
            val todayVal = rawVals.lastOrNull()
            val deltaLabel = if (usual != null && todayVal != null) {
                val diff = todayVal - usual.median
                val sign = if (diff >= 0) "+" else ""
                when (mType) {
                    MetricType.SLEEP_DURATION -> "$sign${(diff / 60).toLong()} min vs medián"
                    else -> "$sign${fmt.number(diff, 0)} ${cfg.unit} vs medián"
                }
            } else null

            val customStatus = if (mType == MetricType.SLEEP_DURATION && sleepScore != null) {
                "Skóre ${sleepScore.toInt()}/100"
            } else null

            SinglestatCard(
                title = cfg.title,
                icon = cfg.icon,
                reading = card.reading,
                historyValues = chartVals,
                accentColor = cfg.color,
                windowLabel = "${bgWindowDays}d",
                deltaLabel = deltaLabel,
                statusLabel = customStatus,
                onClick = { onOpenMetric(mType) }
            )
        }

        // Karta Váhy jako Singlestat: zobrazuje váhu vždy; pokud je měření starší než dnešní, hodnota změní barvu na jantarovou/oranžovou
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

        SinglestatCard(
            title = wCfg.title,
            icon = wCfg.icon,
            reading = wReading,
            historyValues = weightSeries?.values?.map { it?.let(fmt::convertWeight) }.orEmpty(),
            accentColor = wCfg.color,
            windowLabel = "${bgWindowDays}d",
            deltaLabel = weightDelta,
            statusLabel = weightStatusBadge,
            valueColor = weightValueColor,
            onClick = { onOpenMetric(MetricType.WEIGHT) }
        )

        Spacer(Modifier.height(12.dp))
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
    val scope = rememberCoroutineScope()
    val units by sl.settings.unitSystemFlow.collectAsState()
    val cfg = getMetricUiConfig(type)

    val dataBundle by io(Triple(type, daysCount, units to refreshTick)) {
        val zone = ZoneId.of(sl.settings.zoneId)
        val today = LocalDate.now(zone)
        val fromDate = today.minusDays((daysCount - 1).toLong()).toString()
        val toDate = today.toString()
        val allRangeObs = sl.db.observations().forRange(fromDate, toDate).map { it.toDomain() }
        val primaryRows = if (type == MetricType.WEIGHT || type == MetricType.BODY_FAT) {
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

        DedicatedBundle(
            today = today,
            zone = zone,
            mainSeries = mainSeries,
            latestObs = latestObs,
            sleepScoreSeries = sleepScoreSeries,
            bodyFatSeries = bodyFatSeries,
            recentLogs = recentLogs,
            sleepScoresByDate = sleepScoresByDate,
            suspectWeights = suspectWeights
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
                    Text("← Dnes", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        "${cfg.icon} ${cfg.title}",
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
                    Text("${cfg.icon} ${cfg.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
            MetricType.SLEEP_DURATION -> {
                val latestHours = latestObs.value / 3600.0
                val latestScore = b.sleepScoresByDate[latestObs.localDate ?: ""]
                    ?: b.sleepScoreSeries?.values?.lastOrNull { it != null }
                val sleepStartStr = java.time.Instant.ofEpochMilli(latestObs.startUtcMs).atZone(b.zone)
                    .toLocalTime().toString().take(5)
                val sleepEndStr = latestObs.endUtcMs?.let {
                    java.time.Instant.ofEpochMilli(it).atZone(b.zone).toLocalTime().toString().take(5)
                } ?: "—"

                val scoreQualifier = when {
                    latestScore == null -> "Měřeno"
                    latestScore >= 85 -> "🟢 Vynikající spánek"
                    latestScore >= 75 -> "🟢 Dobrý spánek"
                    latestScore >= 60 -> "🟡 Slušný spánek"
                    else -> "🔴 Krátký / Neklidný"
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
                                color = Color.White,
                                modifier = Modifier
                                    .background(cfg.color.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                            Column {
                                Text(fmt.sleep(latestObs.value), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                Text("🌙 Usnutí $sleepStartStr  →  ☀️ Probuzení $sleepEndStr", style = MaterialTheme.typography.bodySmall, color = Color(0xFFBAE6FD))
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
                SectionTitle("🛌 Vývoj délky spánku (hodiny · ${daysCount}d)")
                LineChart(
                    values = series.values.map { it?.div(3600.0) },
                    band = series.usual?.let { (it.low / 3600.0)..(it.high / 3600.0) },
                    color = cfg.color
                )

                // Graf 2: Skóre spánku (pokud existuje)
                val scSeries = b.sleepScoreSeries
                if (scSeries != null && scSeries.hasData) {
                    SectionTitle("⭐ Vývoj skóre kvality spánku (0–100 · ${daysCount}d)")
                    LineChart(
                        values = scSeries.values,
                        band = scSeries.usual?.let { it.low..it.high },
                        color = Color(0xFFA855F7)
                    )
                }

                // Deník spánku po jednotlivých nocích
                SectionTitle("🌙 Deník spánku (poslední noci)")
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
                                Text("🌙 $st → ☀️ $en", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
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
                        Text("⚖️ RYCHLÝ ZÁPIS DNEŠNÍ VÁHY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
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
                                            weightSavedMsg = "✅ Váha ${fmt.weight(kg)} uložena."
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

                SectionTitle("⚖️ Vývoj tělesné hmotnosti (${fmt.weightUnit()} · ${daysCount}d)")
                LineChart(
                    values = series.values.map { it?.let(fmt::convertWeight) },
                    band = series.usual?.let { fmt.convertWeight(it.low)..fmt.convertWeight(it.high) },
                    color = cfg.color
                )

                val bfSeries = b.bodyFatSeries
                if (bfSeries != null && bfSeries.hasData) {
                    SectionTitle("💧 Vývoj tělesného tuku (% · ${daysCount}d)")
                    LineChart(
                        values = bfSeries.values,
                        band = bfSeries.usual?.let { it.low..it.high },
                        color = Color(0xFF06B6D4)
                    )
                }

                if (b.suspectWeights.isNotEmpty()) {
                    SectionTitle("👨‍👩‍👧‍👦 Nejednoznačná vážení (Dítě / Jiný člen rodiny)")
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
                                            Text("✅ Moje", color = Color(0xFF10B981), maxLines = 1, softWrap = false)
                                        }
                                        TextButton(onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                val ok = sl.withings.forwardSingleWeightToChild(sObs, System.currentTimeMillis(), excludeFromParent = true)
                                                weightSavedMsg = if (ok) "🧒 Váha ${fmt.weight(sObs.value)} přeposlána dítěti přes P2P!" else "⚠️ Nejprve vyberte kontakt dítěte v Nastavení → Garmin & Withings."
                                                refreshTick++
                                            }
                                        }) {
                                            Text("🧒 Poslat dítěti", color = Color(0xFF38BDF8), maxLines = 1, softWrap = false)
                                        }
                                        TextButton(onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                sl.withings.markWrong(sObs.id, System.currentTimeMillis())
                                                refreshTick++
                                            }
                                        }) {
                                            Text("🚫 Cizí", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                SectionTitle("📋 Historie vážení")
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
                                        weightSavedMsg = if (ok) "🧒 Váha ${fmt.weight(obs.value)} přeposlána dítěti přes P2P!" else "⚠️ Nejprve vyberte kontakt dítěte v Nastavení → Garmin & Withings."
                                        refreshTick++
                                    }
                                }) {
                                    Text("🧒 Poslat dítěti", style = MaterialTheme.typography.labelSmall, color = Color(0xFF38BDF8), maxLines = 1, softWrap = false)
                                }
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        sl.withings.markWrong(obs.id, System.currentTimeMillis())
                                        refreshTick++
                                    }
                                }) {
                                    Text("🚫 Cizí", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), maxLines = 1, softWrap = false)
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
                val formattedVal = when (type) {
                    MetricType.HRV -> "${fmt.number(latestVal, 0)} ms"
                    MetricType.RESTING_HEART_RATE -> "${fmt.number(latestVal, 0)} bpm"
                    MetricType.BODY_BATTERY -> "${fmt.number(latestVal, 0)} / 100"
                    MetricType.STRESS -> "${fmt.number(latestVal, 0)} / 100"
                    MetricType.STEPS -> "${fmt.number(latestVal, 0)} kroků"
                    else -> "${fmt.number(latestVal, 1)} ${cfg.unit}"
                }

                val (statusText, statusColor) = if (usual == null) {
                    "Sběr osobního pásma" to Color(0xFF94A3B8)
                } else when (type) {
                    MetricType.RESTING_HEART_RATE, MetricType.STRESS -> when {
                        latestVal < usual.low -> "🟢 Nízké / Odpočinek" to Color(0xFF10B981)
                        latestVal > usual.high -> "🔴 Zvýšené nad normál" to Color(0xFFEF4444)
                        else -> "🟢 V obvyklém pásmu" to Color(0xFF10B981)
                    }
                    else -> when {
                        latestVal < usual.low -> "🟡 Pod obvyklým pásmem" to Color(0xFFF59E0B)
                        latestVal > usual.high -> "🟢 Nadstandardní hodnota" to Color(0xFF10B981)
                        else -> "🟢 V osobním pásmu" to Color(0xFF10B981)
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

                SectionTitle("📈 Vývoj v čase (${daysCount} dní)")
                LineChart(
                    values = series.values,
                    band = usual?.let { it.low..it.high },
                    color = cfg.color
                )

                // Fyziologické vysvětlení
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Text("💡", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 10.dp))
                        Column {
                            Text("Fyziologický kontext", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(cfg.explanation, style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                        }
                    }
                }

                SectionTitle("📋 Denní záznamy (${b.recentLogs.size})")
                b.recentLogs.forEach { obs ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(obs.localDate ?: fmt.time(obs.startUtcMs), style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                            Text("${fmt.number(obs.value, 0)} ${cfg.unit}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = cfg.color)
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
    val recentLogs: List<app.pulse4.domain.MetricObservation>,
    val sleepScoresByDate: Map<String, Double>,
    val suspectWeights: List<app.pulse4.domain.MetricObservation> = emptyList(),
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

private fun formatMetricValue(type: MetricType, v: Double): String {
    val cfg = getMetricUiConfig(type)
    return when (type) {
        MetricType.SLEEP_DURATION -> fmt.sleep(v)
        MetricType.HRV -> "${fmt.number(v, 0)} ms"
        MetricType.RESTING_HEART_RATE -> "${fmt.number(v, 0)} bpm"
        MetricType.BODY_BATTERY -> fmt.number(v, 0)
        MetricType.STRESS -> fmt.number(v, 0)
        MetricType.STEPS -> "${fmt.number(v, 0)} kroků"
        MetricType.WEIGHT -> fmt.weight(v)
        MetricType.BODY_FAT -> "${fmt.number(v, 1)} %"
        MetricType.SPO2 -> "${fmt.number(v, 0)} %"
        else -> "${fmt.number(v, 1)} ${cfg.unit}"
    }
}

/**
 * Obrazovka Metriky (záložka Metriky):
 * Umožňuje jak prohlížení jedné metriky, tak výběr více smysluplných metrik najednou
 * (pomocí připravených kombinací nebo přepínače Multi-výběr) pro srovnání jejich korelace v čase.
 */
@Composable
fun MetricsScreen(sl: ServiceLocator, initialType: MetricType = MetricType.HRV) {
    var selectedTypes by remember(initialType) { mutableStateOf(linkedSetOf(initialType)) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var normalizeMultiChart by remember { mutableStateOf(true) }
    var daysCount by remember { mutableStateOf(30) }
    val units by sl.settings.unitSystemFlow.collectAsState()

    val allAvailableTypes = listOf(
        MetricType.HRV,
        MetricType.RESTING_HEART_RATE,
        MetricType.SLEEP_DURATION,
        MetricType.BODY_BATTERY,
        MetricType.STRESS,
        MetricType.STEPS,
        MetricType.WEIGHT,
        MetricType.BODY_FAT,
        MetricType.SPO2
    )

    val presets = listOf(
        "💓 Regenerace (HRV + Tep)" to linkedSetOf(MetricType.HRV, MetricType.RESTING_HEART_RATE),
        "⚡ Energie (B.Battery + Stres)" to linkedSetOf(MetricType.BODY_BATTERY, MetricType.STRESS),
        "🛌 Spánek + HRV + B.Battery" to linkedSetOf(MetricType.SLEEP_DURATION, MetricType.HRV, MetricType.BODY_BATTERY),
        "⚖️ Tělo (Váha + Tuk)" to linkedSetOf(MetricType.WEIGHT, MetricType.BODY_FAT)
    )

    val multiBundle by io(Triple(selectedTypes.toList(), daysCount, units)) {
        val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
        val rangeObs = sl.db.observations()
            .forRange(today.minusDays((daysCount - 1).toLong()).toString(), today.toString())
            .map { it.toDomain() }

        selectedTypes.map { t ->
            val rows = if (t == MetricType.WEIGHT || t == MetricType.BODY_FAT) {
                runCatching { sl.withings.trend(t, daysCount) }.getOrDefault(emptyList())
            } else {
                rangeObs
            }
            val s = MetricSeries.build(t, rows, today, daysCount)
            val latest = rows.filter { it.type == t && !it.deleted }.maxByOrNull { it.startUtcMs }
            MultiMetricEntry(t, s, latest)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // 1. Hlavička s přepínačem Multi-výběru a rychlými kombinacemi
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Smysluplné kombinace:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = multiSelectMode || selectedTypes.size > 1,
                onClick = {
                    multiSelectMode = !multiSelectMode
                    if (!multiSelectMode && selectedTypes.size > 1) {
                        selectedTypes = linkedSetOf(selectedTypes.first())
                    }
                },
                label = {
                    Text(
                        if (multiSelectMode || selectedTypes.size > 1) "✓ Multi-výběr (${selectedTypes.size})"
                        else "➕ Vybrat více",
                        maxLines = 1,
                        softWrap = false
                    )
                }
            )
        }

        // Rychlé předvolby smysluplných kombinací
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presets.forEach { (label, presetSet) ->
                val isSelected = selectedTypes == presetSet
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (isSelected) {
                            selectedTypes = linkedSetOf(presetSet.first())
                            multiSelectMode = false
                        } else {
                            selectedTypes = LinkedHashSet(presetSet)
                            multiSelectMode = true
                        }
                    },
                    label = { Text(label, maxLines = 1) }
                )
            }
        }

        // Výběr jednotlivých metrik (v Multi-výběru umožňuje zaškrtnout více najednou)
        FlowRow(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            allAvailableTypes.forEach { t ->
                val c = getMetricUiConfig(t)
                val isSel = t in selectedTypes
                FilterChip(
                    selected = isSel,
                    onClick = {
                        if (multiSelectMode) {
                            if (isSel && selectedTypes.size > 1) {
                                val next = LinkedHashSet(selectedTypes)
                                next.remove(t)
                                selectedTypes = next
                            } else if (!isSel) {
                                val next = LinkedHashSet(selectedTypes)
                                next.add(t)
                                selectedTypes = next
                            }
                        } else {
                            selectedTypes = linkedSetOf(t)
                        }
                    },
                    label = { Text("${c.icon} ${c.shortLabel}") },
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                )
            }
        }

        // Volba časového okna (7d, 14d, 30d, 90d)
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Časové okno:", style = MaterialTheme.typography.labelMedium, color = Color(0xFF94A3B8))
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

        val entries = multiBundle
        if (entries == null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
            ) {
                Text("Načítání biometrických dat…", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (entries.size >= 2) {
            // REŽIM VÍCE VYBRANÝCH METRIK NAJEDNOU (Korelační porovnání)
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFF232A3B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "📊 SPOLEČNÉ POROVNÁNÍ (${entries.size} VYBRANÉ METRIKY)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = normalizeMultiChart,
                            onClick = { normalizeMultiChart = true },
                            label = { Text("📐 % škála (0–100)", maxLines = 1, softWrap = false) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = !normalizeMultiChart,
                            onClick = { normalizeMultiChart = false },
                            label = { Text("🔢 Původní hodnoty", maxLines = 1, softWrap = false) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    val datesLabels = entries.firstOrNull()?.series?.dates?.map { it.toString().takeLast(5) }.orEmpty()
                    val multiSeries = entries.map { entry ->
                        val c = getMetricUiConfig(entry.type)
                        val rawConverted = when (entry.type) {
                            MetricType.SLEEP_DURATION -> entry.series.values.map { it?.div(3600.0) }
                            MetricType.WEIGHT -> entry.series.values.map { it?.let(fmt::convertWeight) }
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
                        SeriesItem(
                            id = entry.type.name,
                            label = "${c.icon} ${c.shortLabel}",
                            color = c.color,
                            values = displayVals,
                            unit = if (normalizeMultiChart) "%" else c.unit
                        )
                    }

                    MultiSeriesLineChart(
                        series = multiSeries,
                        xLabels = datesLabels,
                        title = if (normalizeMultiChart) {
                            "📈 Korelace (0–100 %)"
                        } else {
                            "📈 Společný graf ($daysCount dní)"
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle("📋 Souhrn vybraných metrik (${entries.size})")

            entries.forEach { entry ->
                val c = getMetricUiConfig(entry.type)
                val latestVal = entry.latestObs?.value
                val usual = entry.series.usual
                val finite = entry.series.values.filterNotNull()
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
                                Text(c.icon, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        usual?.let { "Medián (${daysCount}d): ${formatMetricValue(entry.type, it.median)}" } ?: "Málo dat pro medián",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF94A3B8),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Text(
                                latestVal?.let { formatMetricValue(entry.type, it) } ?: "—",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = c.color,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }

                        if (finite.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            val chartValues = when (entry.type) {
                                MetricType.SLEEP_DURATION -> entry.series.values.map { it?.div(3600.0) }
                                MetricType.WEIGHT -> entry.series.values.map { it?.let(fmt::convertWeight) }
                                else -> entry.series.values
                            }
                            val chartBand = when (entry.type) {
                                MetricType.SLEEP_DURATION -> usual?.let { (it.low / 3600.0)..(it.high / 3600.0) }
                                MetricType.WEIGHT -> usual?.let { fmt.convertWeight(it.low)..fmt.convertWeight(it.high) }
                                else -> usual?.let { it.low..it.high }
                            }
                            LineChart(
                                values = chartValues,
                                band = chartBand,
                                color = c.color
                            )
                        }
                    }
                }
            }
        } else {
            // REŽIM JEDNÉ VYBRANÉ METRIKY
            val single = entries.first()
            val type = single.type
            val cfg = getMetricUiConfig(type)
            val series = single.series
            val latestObs = single.latestObs

            if (!series.hasData || latestObs == null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${cfg.icon} ${cfg.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                val latestFormatted = formatMetricValue(type, latestValue)

                val (statusText, statusColor) = if (usual == null) {
                    "Sběr pásma (${series.values.filterNotNull().size}/7 dní)" to Color(0xFF94A3B8)
                } else when (type) {
                    MetricType.RESTING_HEART_RATE, MetricType.STRESS -> when {
                        latestValue < usual.low -> "🟢 Nízké / Odpočinek" to Color(0xFF10B981)
                        latestValue > usual.high -> "🔴 Zvýšené nad normál" to Color(0xFFEF4444)
                        else -> "🟢 V obvyklém rozmezí" to Color(0xFF10B981)
                    }
                    else -> when {
                        latestValue < usual.low -> "🟡 Pod obvyklým rozmezím" to Color(0xFFF59E0B)
                        latestValue > usual.high -> "🟢 Nadstandardní hodnota" to Color(0xFF10B981)
                        else -> "🟢 V obvyklém rozmezí" to Color(0xFF10B981)
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
                            val sign = if (diff >= 0) "+" else ""
                            "$sign${fmt.number(diff, 1)} % vs medián"
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
                                Text(cfg.icon, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(end = 8.dp))
                                Column {
                                    Text(cfg.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Měření z ${fmt.time(latestObs.startUtcMs)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = statusColor,
                                modifier = Modifier
                                    .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
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
                val medianFormatted = if (usual != null) formatMetricValue(type, usual.median) else "—"
                val finite = series.values.filterNotNull()
                val minMaxFormatted = if (finite.isNotEmpty()) {
                    val minV = finite.minOrNull() ?: 0.0
                    val maxV = finite.maxOrNull() ?: 0.0
                    "${formatMetricValue(type, minV)} – ${formatMetricValue(type, maxV)}"
                } else "—"

                val usualFormatted = if (usual != null) {
                    "${formatMetricValue(type, usual.low)} – ${formatMetricValue(type, usual.high)}"
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

                SectionTitle("📈 Historický průběh (${daysCount} dní)")
                val chartValues = when (type) {
                    MetricType.SLEEP_DURATION -> series.values.map { it?.div(3600.0) }
                    MetricType.WEIGHT -> series.values.map { it?.let(fmt::convertWeight) }
                    else -> series.values
                }
                val chartBand = when (type) {
                    MetricType.SLEEP_DURATION -> usual?.let { (it.low / 3600.0)..(it.high / 3600.0) }
                    MetricType.WEIGHT -> usual?.let { fmt.convertWeight(it.low)..fmt.convertWeight(it.high) }
                    else -> usual?.let { it.low..it.high }
                }

                LineChart(
                    values = chartValues,
                    band = chartBand,
                    color = cfg.color,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Text("💡", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 10.dp, top = 2.dp))
                        Column {
                            Text("Význam metriky a regenerace", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(cfg.explanation, style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                        }
                    }
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

fun Sport.icon(): String = when (this) {
    Sport.RUNNING -> "🏃"
    Sport.CYCLING -> "🚴"
    Sport.WALKING -> "🚶"
    Sport.HIKING -> "🥾"
    Sport.SWIMMING -> "🏊"
    Sport.STRENGTH -> "🏋️"
    Sport.OTHER -> "⏱️"
}

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
    val acts by sl.db.activities().all().collectAsState(emptyList())
    val sharedRows by sl.db.inbox().objects("activity").collectAsState(emptyList())
    val contacts by sl.db.contacts().all().collectAsState(emptyList())
    val units by sl.settings.unitSystemFlow.collectAsState()

    var sportFilter by remember { mutableStateOf<Sport?>(null) }
    var sourceFilter by remember { mutableStateOf("ALL") } // ALL, MINE, SHARED
    var selectedForCompare by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(acts.size) {
        withContext(Dispatchers.IO) {
            sl.importService.ensureSampleActivityLoaded(System.currentTimeMillis())
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            msg = withContext(Dispatchers.IO) {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext "Soubor nelze přečíst"
                when (val r = sl.importService.importFit(bytes, System.currentTimeMillis())) {
                    is FitImportResult.Imported -> "✅ Soubor FIT byl úspěšně importován"
                    is FitImportResult.Rejected -> "❌ Soubor odmítnut: ${r.reason}"
                }
            }
        }
    }

    val unifiedList = remember(acts, sharedRows, contacts, units) {
        val mine = acts.mapNotNull { row ->
            val a = runCatching { Wire.json.decodeFromString(Activity.serializer(), row.json) }.getOrNull() ?: return@mapNotNull null
            UnifiedActivityItem(
                key = a.id,
                sport = a.sport,
                title = a.title?.takeIf { it.isNotBlank() } ?: a.sport.czechName(),
                locationName = a.locationName,
                ownerName = "Já",
                isShared = false,
                startUtcMs = a.startUtcMs,
                localDate = java.time.Instant.ofEpochMilli(a.startUtcMs).atZone(ZoneId.of(a.zoneId)).toLocalDate().toString(),
                distanceM = a.distanceM,
                movingTimeS = a.movingTimeS ?: a.totalTimeS,
                ascentM = a.ascentM,
                avgHeartRate = a.avgHeartRate,
                maxHeartRate = a.maxHeartRate,
                caloriesKcal = a.caloriesKcal,
                aerobicTe = a.aerobicTrainingEffect,
                anaerobicTe = a.anaerobicTrainingEffect,
                trainingLoad = a.trainingLoad,
                avgPowerW = a.avgPowerW,
                avgCadence = a.avgCadence
            )
        }
        val shared = sharedRows.mapNotNull { r ->
            val p = runCatching { Wire.json.decodeFromString(ActivityProjection.serializer(), r.payload) }.getOrNull() ?: return@mapNotNull null
            val sp = runCatching { Sport.valueOf(p.sport) }.getOrDefault(Sport.OTHER)
            val cName = contacts.firstOrNull { it.identity == r.author }?.displayName ?: r.author.take(8)
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

    val filtered = remember(unifiedList, sportFilter, sourceFilter) {
        unifiedList.filter { item ->
            val matchSport = sportFilter == null || item.sport == sportFilter
            val matchSource = when (sourceFilter) {
                "MINE" -> !item.isShared
                "SHARED" -> item.isShared
                else -> true
            }
            matchSport && matchSource
        }
    }

    val totalDistM = remember(filtered) { filtered.fold(0.0) { acc, a -> acc + (a.distanceM ?: 0.0) } }
    val totalTimeS = remember(filtered) { filtered.fold(0.0) { acc, a -> acc + (a.movingTimeS ?: 0.0) } }
    val totalAscentM = remember(filtered) { filtered.fold(0.0) { acc, a -> acc + (a.ascentM ?: 0.0) } }
    val totalCalories = remember(filtered) { filtered.fold(0.0) { acc, a -> acc + (a.caloriesKcal ?: 0.0) } }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Horní panel
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Aktivity & Trasy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(
                    "${unifiedList.count { !it.isShared }} vlastních · ${unifiedList.count { it.isShared }} sdílených P2P",
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
                            msg = if (count > 0) "✅ Staženo $count aktivit z Garminu."
                            else "✅ Aktivity jsou aktuální."
                            syncing = false
                        }
                    },
                    enabled = !syncing,
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Text(if (syncing) "…" else "🔄 Garmin")
                }
                Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text("📂 FIT")
                }
            }
        }

        msg?.let {
            Card(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Lišta porovnávání aktivit (Compare), když uživatel zaškrtne aktivity
        if (selectedForCompare.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
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
                            Text("📊 Porovnat (${selectedForCompare.size})")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Hero Summary Card
        Card(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF232A3B)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "SOUHRN ZOBRAZENÝCH AKTIVIT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        "Zaškrtněte 2+ pro Compare ☑️",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF38BDF8)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(fmt.km(totalDistM), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Vzdálenost", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(fmt.duration(totalTimeS), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Doba pohybu", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                    Column(Modifier.weight(0.8f)) {
                        Text(fmt.elevation(totalAscentM), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFFFFB300))
                        Text("Převýšení", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                    Column(Modifier.weight(0.8f)) {
                        Text("${totalCalories.toInt()} kcal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                        Text("Kalorie", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Filtr Vlastní vs Sdílené P2P
        Row(Modifier.fillMaxWidth()) {
            listOf(
                "ALL" to "Vše (${unifiedList.size})",
                "MINE" to "👤 Moje (${unifiedList.count { !it.isShared }})",
                "SHARED" to "📡 Sdílené P2P (${unifiedList.count { it.isShared }})"
            ).forEach { (k, label) ->
                FilterChip(
                    selected = sourceFilter == k,
                    onClick = { sourceFilter = k },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }

        // Filtr sportů
        FlowRow(Modifier.fillMaxWidth()) {
            unifiedList.map { it.sport }.distinct().forEach { s ->
                val cnt = unifiedList.count { it.sport == s }
                FilterChip(
                    selected = sportFilter == s,
                    onClick = { sportFilter = if (sportFilter == s) null else s },
                    label = { Text("${s.icon()} ${s.czechName()} ($cnt)") },
                    modifier = Modifier.padding(end = 4.dp, bottom = 2.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        if (filtered.isEmpty()) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Žádné aktivity v tomto filtru", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Klikněte na '🔄 Garmin' pro stažení aktivit z účtu, '📂 FIT' pro import souboru, nebo přijměte sdílenou aktivitu od kontaktu přes P2P.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        } else {
            filtered.forEach { a ->
                val dist = a.distanceM
                val pace = if (dist != null && dist > 0 && a.movingTimeS != null) (a.movingTimeS / (dist / 1000.0)) else null
                val isChecked = a.key in selectedForCompare

                Card(
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isChecked) Color(0xFF38BDF8)
                        else if (a.isShared) Color(0xFF10B981).copy(alpha = 0.4f)
                        else Color(0xFF232A3B)
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isChecked) Color(0xFF132338) else Color(0xFF131722)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clickable { onOpen(a.key) }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Checkbox pro Compare
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                selectedForCompare = if (checked) {
                                    if (selectedForCompare.size < 4) selectedForCompare + a.key else selectedForCompare
                                } else {
                                    selectedForCompare - a.key
                                }
                            },
                            modifier = Modifier.padding(end = 4.dp)
                        )

                        // Sportovní ikona
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = a.sport.badgeColor().copy(alpha = 0.18f)),
                            border = BorderStroke(1.dp, a.sport.badgeColor().copy(alpha = 0.35f)),
                            modifier = Modifier.padding(end = 10.dp)
                        ) {
                            Text(
                                a.sport.icon(),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(8.dp)
                            )
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
                                        Text(
                                            " 📡 P2P",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF10B981),
                                            fontWeight = FontWeight.Bold
                                        )
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
                                        Text("🔥 ${it.toInt()} kcal", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF59E0B))
                                    }
                                    a.aerobicTe?.let { ae ->
                                        val an = a.anaerobicTe ?: 0.0
                                        Text("⚡ TE ${fmt.number(ae, 1)} / ${fmt.number(an, 1)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981))
                                    }
                                    a.avgPowerW?.let {
                                        Text("🔋 ${it.toInt()} W", style = MaterialTheme.typography.labelSmall, color = Color(0xFF38BDF8))
                                    }
                                    a.trainingLoad?.let {
                                        Text("🏋️ Zátěž ${it.toInt()}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFA855F7))
                                    }
                                }
                            }
                        }

                        Text("›", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF64748B), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

private fun Channel.label() = when (this) {
    Channel.HEART_RATE -> "❤️ Srdeční tep"
    Channel.SPEED -> "⚡ Tempo / Rychlost"
    Channel.ELEVATION -> "⛰️ Nadmořská výška"
    Channel.CADENCE -> "🦶 Kadence"
    Channel.POWER -> "🔋 Výkon"
}

private fun Channel.unitLabel(): String = when (this) {
    Channel.HEART_RATE -> "bpm"
    Channel.SPEED -> "m/s"
    Channel.ELEVATION -> fmt.elevationUnit()
    Channel.CADENCE -> "spm"
    Channel.POWER -> "W"
}

private fun Channel.color(): Color = when (this) {
    Channel.HEART_RATE -> Color(0xFFFF5252)
    Channel.SPEED -> Color(0xFF38BDF8)
    Channel.ELEVATION -> Color(0xFF10B981)
    Channel.CADENCE -> Color(0xFFA855F7)
    Channel.POWER -> Color(0xFFF59E0B)
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
)

fun loadUnifiedActivity(sl: ServiceLocator, key: String): LoadedActivityDetail? {
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
            track = reconstructed,
            sharedProjection = p
        )
    } else {
        val row = sl.db.activities().get(key) ?: return null
        val a = runCatching { Wire.json.decodeFromString(Activity.serializer(), row.json) }.getOrNull() ?: return null
        val t = sl.db.activities().track(key)?.let {
            runCatching { Wire.json.decodeFromString(ListSerializer(TrackPoint.serializer()), it.json) }.getOrNull()
        }.orEmpty()
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
            track = t,
            domainActivity = a
        )
    }
}

private fun formatTrainingEffectLabel(raw: String?): String = when (raw?.uppercase()) {
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
    "RWD_RUN" -> "🏃 Běh (Run/Walk detekce)"
    "RWD_WALK" -> "🚶 Chůze (Run/Walk detekce)"
    "RWD_STAND" -> "🧍 Stání / Pauza"
    "INTERVAL_ACTIVE" -> "⚡ Aktivní interval"
    "INTERVAL_WARMUP" -> "🔥 Rozklusání / Zahřátí"
    "INTERVAL_COOLDOWN" -> "❄️ Výklus / Zklidnění"
    "INTERVAL_RECOVERY" -> "💚 Odpočinkový meziklus"
    "INTERVAL_REST" -> "⏸️ Pauza mezi intervaly"
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
        runCatching { loadUnifiedActivity(sl, id) }.getOrNull()
    }
    var cursor by remember { mutableStateOf<Int?>(null) }
    var range by remember { mutableStateOf<IntRange?>(null) }
    var select by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf(Channel.HEART_RATE) }
    var axis by remember { mutableStateOf(Axis.TIME) }
    var movingOnly by remember { mutableStateOf(true) }
    var showAllChartsStacked by remember { mutableStateOf(true) }
    var detailSectionTab by remember { mutableStateOf(0) } // 0 = Vše pod sebou, 1 = Grafy & Zóny, 2 = Mezičasy & Kola, 3 = Garmin Statistiky

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
                    Text("← Zpět", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                }
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = act.sport.badgeColor().copy(alpha = 0.2f)),
                    border = BorderStroke(1.dp, act.sport.badgeColor().copy(alpha = 0.4f)),
                    modifier = Modifier.padding(end = 10.dp)
                ) {
                    Text(
                        act.sport.icon(),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Column {
                    Text(act.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    val locPart = dom?.locationName?.let { " · 📍 $it" }.orEmpty()
                    Text(
                        "${act.startUtcMs?.let(fmt::time) ?: act.localDate}$locPart · ${act.ownerName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
            if (act.isShared) {
                Text(
                    "📡 P2P",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981),
                    modifier = Modifier
                        .background(Color(0xFF10B981).copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Rozdělení detailu aktivity do 4 čistých sekcí, aby se nemíchaly tabulky s grafy
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                0 to "🗺️ Přehled & Mapa",
                1 to "📈 Grafy & Zóny",
                2 to "📋 Tabulky & Statistiky",
                3 to "🏁 Mezičasy & Kola"
            ).forEach { (idx, label) ->
                FilterChip(
                    selected = detailSectionTab == idx,
                    onClick = { detailSectionTab = idx },
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
                        Text("🗺️ MAPA A GPS TRASA", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
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
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("🟢 Start", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981))
                            Text(mapProvider.attribution, style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
                            Text("🏁 Cíl", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF5252))
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

        // 3. GARMIN TRAINING EFFECT, TRÉNINKOVÁ ZÁTĚŽ & BODY BATTERY DOPAD (v sekci Přehled & Mapa)
        if (detailSectionTab == 0 &&
            (dom?.aerobicTrainingEffect != null || dom?.trainingLoad != null || dom?.bodyBatteryImpact != null)
        ) {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFF232A3B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "⚡ GARMIN TRÉNINKOVÝ EFEKT & ZÁTĚŽ",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                        dom.trainingEffectLabel?.let { lbl ->
                            Text(
                                formatTrainingEffectLabel(lbl),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981),
                                modifier = Modifier
                                    .background(Color(0xFF10B981).copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    val ae = dom.aerobicTrainingEffect ?: 0.0
                    val an = dom.anaerobicTrainingEffect ?: 0.0

                    // Aerobní TE pruh (0.0 – 5.0)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Aerobní Training Effect", style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("${fmt.number(ae, 1)} / 5.0", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (ae / 5.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFF10B981),
                        trackColor = Color(0xFF1E293B)
                    )
                    dom.aerobicTrainingEffectMessage?.let {
                        Text(it.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B), modifier = Modifier.padding(top = 2.dp))
                    }

                    Spacer(Modifier.height(10.dp))

                    // Anaerobní TE pruh (0.0 – 5.0)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Anaerobní Training Effect", style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("${fmt.number(an, 1)} / 5.0", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFFA855F7))
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (an / 5.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFFA855F7),
                        trackColor = Color(0xFF1E293B)
                    )
                    dom.anaerobicTrainingEffectMessage?.let {
                        Text(it.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B), modifier = Modifier.padding(top = 2.dp))
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                dom.trainingLoad?.let { fmt.number(it, 0) } ?: "—",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF38BDF8)
                            )
                            Text("Tréninková zátěž", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                dom.bodyBatteryImpact?.let { "${if (it > 0) "+$it" else "$it"} ⚡" } ?: "—",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFF59E0B)
                            )
                            Text("Dopad Body Battery", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                        Column(Modifier.weight(1f)) {
                            val modMin = dom.moderateIntensityMinutes ?: 0
                            val vigMin = dom.vigorousIntensityMinutes ?: 0
                            val totalIntMin = modMin + vigMin * 2
                            Text(
                                if (totalIntMin > 0) "$totalIntMin min" else "—",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF10B981)
                            )
                            Text("Intenzivní minuty", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
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
                        Text("📈 Grafy & Zóny", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
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
                        Text("📋 Tabulky & Data", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFFA855F7))
                        Text("Dynamika běhu, výkon, převýšení a kalorie", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 4. TELEMETRICKÉ GRAFY PRŮBĚHU (POUZE V SEKCI GRAFY & ZÓNY — bez míchání s tabulkami)
        if (detailSectionTab == 1) {
            val availableChannels = Channel.entries.filter { view.hasChannel(it) }
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
                        Text(
                            "📈 GRAFY PRŮBĚHU AKTIVITY (${availableChannels.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                        Row {
                            FilterChip(
                                selected = axis == Axis.TIME,
                                onClick = { axis = Axis.TIME; range = null },
                                label = { Text("⏱️ Čas") },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            FilterChip(
                                selected = axis == Axis.DISTANCE,
                                onClick = { axis = Axis.DISTANCE; range = null },
                                label = { Text("📏 Vzdál.") }
                            )
                        }
                    }

                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = showAllChartsStacked,
                            onClick = { showAllChartsStacked = true },
                            label = { Text("📊 Všechny grafy pod sebou (Garmin styl)") }
                        )
                        FilterChip(
                            selected = !showAllChartsStacked,
                            onClick = { showAllChartsStacked = false },
                            label = { Text("🔍 Výřez úseku") }
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
                            val finite = chChart.values.filterNotNull()
                            if (finite.isNotEmpty()) {
                                val avgVal = finite.average()
                                val maxVal = finite.max()
                                val minVal = finite.min()
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        ch.label(),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = ch.color()
                                    )
                                    val statsHeader = when (ch) {
                                        Channel.HEART_RATE -> "Průměr ${fmt.number(avgVal, 0)} bpm · Max ${fmt.number(maxVal, 0)} bpm"
                                        Channel.SPEED -> {
                                            val avgPace = if (avgVal > 0.2) 1000.0 / avgVal else null
                                            val maxKmh = maxVal * 3.6
                                            "Prům. tempo ${fmt.pace(avgPace)} · Max ${fmt.number(maxKmh, 1)} km/h"
                                        }
                                        Channel.ELEVATION -> "Min ${fmt.elevation(minVal)} · Max ${fmt.elevation(maxVal)}"
                                        Channel.CADENCE -> "Průměr ${fmt.number(avgVal, 0)} spm · Max ${fmt.number(maxVal, 0)} spm"
                                        Channel.POWER -> "Průměr ${fmt.number(avgVal, 0)} W · Max ${fmt.number(maxVal, 0)} W"
                                    }
                                    Text(statsHeader, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                                }
                                LineChart(
                                    values = chChart.values,
                                    cursor = cursor,
                                    color = ch.color(),
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
                                        Text("📍 ODEČET VYBRANÉHO BODU NA VŠECH GRAFECH A MAPĚ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                                        Spacer(Modifier.height(4.dp))
                                        view.point(ti, fmt).forEach { (k, v) -> LabeledRow(k, v) }
                                    }
                                }
                            }
                        }
                    } else {
                        // Režim jednoho grafu s označováním úseku
                        FlowRow {
                            availableChannels.forEach { c ->
                                FilterChip(
                                    selected = channel == c,
                                    onClick = { channel = c },
                                    label = { Text(c.label()) },
                                    modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                                )
                            }
                        }
                        LineChart(
                            chart.values,
                            cursor = cursor,
                            range = range,
                            selectMode = select,
                            color = channel.color(),
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
                        Text(
                            "❤️ ČAS V TEPOVÝCH ZÓNÁCH (GARMIN Z1–Z5)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
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
                        Text(
                            "🔋 ČAS VE VÝKONOVÝCH ZÓNÁCH (WATTY Z1–Z5)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
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
                        Text(
                            "🏆 NEJRYCHLEJŠÍ ÚSEKY V AKTIVITĚ (GARMIN BEST SPLITS)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF59E0B)
                        )
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
                        Text(
                            "🏁 MEZIČASY A KOLA (${laps.size} KOL)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
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
                        Text(
                            "🔄 ROZPAD FÁZÍ AKTIVITY (GARMIN SPLIT SUMMARIES)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
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
            if (dom?.avgCadence != null || dom?.avgStrideLengthCm != null || dom?.avgVerticalOscillationCm != null || dom?.steps != null) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFF232A3B)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "🦶 DYNAMIKA BĚHU A POHYBU (GARMIN RUNNING DYNAMICS)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFA855F7)
                        )
                        Spacer(Modifier.height(8.dp))
                        dom.avgCadence?.let { LabeledRow("Průměrná kadence", "${fmt.number(it, 0)} kroků/min (spm)") }
                        dom.maxCadence?.let { LabeledRow("Maximální kadence", "${fmt.number(it, 0)} kroků/min (spm)") }
                        dom.avgStrideLengthCm?.let { LabeledRow("Průměrná délka kroku", "${fmt.number(it, 1)} cm (${fmt.number(it / 100.0, 2)} m)") }
                        dom.avgVerticalOscillationCm?.let { LabeledRow("Průměrná vertikální oscilace", "${fmt.number(it, 1)} cm") }
                        dom.avgVerticalRatioPct?.let { LabeledRow("Průměrný vertikální poměr", "${fmt.number(it, 1)} %") }
                        dom.avgGroundContactTimeMs?.let { LabeledRow("Doba kontaktu se zemí (GCT)", "${fmt.number(it, 0)} ms") }
                        dom.steps?.let { LabeledRow("Celkový počet kroků v aktivitě", "$it kroků") }
                    }
                }
                Spacer(Modifier.height(12.dp))
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
                        Text(
                            "🔋 VÝKONOVÉ UKAZATELE (POWER METRICS)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF59E0B)
                        )
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
                    Text(
                        "⛰️ TEMPO, RYCHLOST, ČAS A NADMOŘSKÁ VÝŠKA",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
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
                        Text(
                            "🔥 ENERGETICKÝ VÝDEJ A HYDRATACE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
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
                Text(
                    "📊 Porovnání Aktivit",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${items.size} porovnávané aktivity · Garmin & Grafana styl",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onBack, modifier = Modifier.padding(start = 6.dp)) {
                Text("← Zpět", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), maxLines = 1, softWrap = false)
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
                            Text(
                                "${act.sport.icon()} ${act.title.take(12)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = col,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
        SectionTitle("📈 Překryvné porovnání křivek (Grafana styl)")
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
                    Text(
                        "🗺️ PŘEKRYVNÉ POROVNÁNÍ GPS TRAS (${mapProvider.shortLabel})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
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
