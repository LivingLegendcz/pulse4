@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.pulse4.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pulse4.android.data.RoomPlanStore
import app.pulse4.android.di.ServiceLocator
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

/**
 * Tréninky: knihovna jednotek, editor běhu a kola, plán, čekající návrhy a export do Garminu.
 * Editor pokrývá rozcvičení, pracovní úseky, odpočinek, opakované bloky a závěr; silové jednotky jsou další fáze.
 * Stav editoru, formuláře a texty jsou v `ui-logic` (testováno v JVM).
 */
@Composable
fun TrainingScreen(sl: ServiceLocator) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(WorkoutDraft.empty()) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var dateText by remember { mutableStateOf(LocalDate.now().plusDays(1).toString()) }

    val planId by io(Unit) { "plan-" + sl.bridge.ensureStarted().deliveryHash.take(8) }
    val head by io(planId to refresh) { planId?.let { id -> RoomPlanStore(sl.db).head(id) } }
    val proposals by sl.db.plans().openProposals().collectAsState(emptyList())
    val date = DateInput.parse(dateText, LocalDate.now())

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Editor tréninku", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(draft.workout.name, { draft = draft.rename(it) }, label = { Text("Název") }, modifier = Modifier.fillMaxWidth())
        Row { listOf(Sport.RUNNING to "Běh", Sport.CYCLING to "Kolo").forEach { (s, l) ->
            FilterChip(draft.workout.sport == s, { draft = draft.sport(s) }, { Text(l) }, Modifier.padding(2.dp)) } }
        draft.workout.nodes.forEachIndexed { i, n ->
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(Modifier.padding(8.dp)) {
                    Text(WorkoutText.describe(n), Modifier.weight(1f))
                    TextButton({ draft = draft.move(i, -1) }) { Text("↑") }
                    TextButton({ draft = draft.move(i, 1) }) { Text("↓") }
                    TextButton({ draft = draft.removeAt(i) }) { Text("Smazat") }
                }
            }
        }
        Row {
            Button({ dialog = "step" }) { Text("+ Krok") }
            Button({ dialog = "interval" }, Modifier.padding(start = 8.dp)) { Text("+ Intervaly") }
        }
        // Nepodporované kombinace se vysvětlí před exportem, ne až po selhání.
        draft.issues.forEach { Text("⚠ ${it.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        OutlinedTextField(dateText, { dateText = it }, label = { Text("Datum (RRRR-MM-DD)") }, modifier = Modifier.fillMaxWidth())
        when (val d = date) {
            DateInput.Parsed.Invalid -> Text("Neplatné datum.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            is DateInput.Parsed.Ok -> if (d.inPast) Text("Datum je v minulosti.", style = MaterialTheme.typography.bodySmall)
        }
        Button(enabled = draft.valid && planId != null && date is DateInput.Parsed.Ok, onClick = {
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
                        is SubmitResult.Accepted -> "Přidáno do plánu (revize ${r.revision.revision})"
                        is SubmitResult.Rejected -> "Nelze přidat: ${r.reason}"
                        else -> "Čeká na rozhodnutí"
                    }
                }
                refresh++
            }
        }) { Text("Přidat do plánu") }
        status?.let { Text(it) }

        SectionTitle("Plán" + (head?.let { " · revize ${it.revision}" } ?: ""))
        head?.workouts?.sortedBy { it.date }?.forEach { sw ->
            val rec by io(sw.id to refresh) { planId?.let { sl.exportService.records(it).firstOrNull { r -> r.scheduledWorkoutId == sw.id } } }
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text("${sw.date} · ${sw.workout.name}", style = MaterialTheme.typography.titleSmall)
                    rec?.let {
                        Text(Labels.export(it.level))
                        if (it.blocked) Text("Výsledek nejasný: nutno ověřit v Garmin Connect (další zápis je zastaven).", color = MaterialTheme.colorScheme.error)
                        it.message?.let { m -> Text(m, style = MaterialTheme.typography.bodySmall) }
                    } ?: Text(Labels.export(ExportLevel.LOCAL_ONLY))
                    TextButton(enabled = rec?.blocked != true, onClick = {
                        scope.launch {
                            val revision = head?.revision ?: return@launch
                            status = withContext(Dispatchers.IO) {
                                try {
                                    when (val o = sl.exportService.run(planId!!, revision, sw.id, sw.date, sw.workout)) {
                                        is ExportOutcome.Finished -> "Export dokončen"
                                        is ExportOutcome.NeedsVerification -> "Nutno ověřit v Garmin Connect"
                                        is ExportOutcome.ExternalChange -> "Trénink byl v Garminu změněn mimo aplikaci – porovnejte, nepřepsáno"
                                        is ExportOutcome.Failed -> "Export selhal: ${o.record.message}"
                                    }
                                } catch (e: ProviderError) { Labels.error(e) }
                            }
                            refresh++
                        }
                    }) { Text("Exportovat do Garminu") }
                }
            }
        }

        if (proposals.isNotEmpty()) SectionTitle("Čekající návrhy změn")
        proposals.forEach { p ->
            val prop = Wire.json.decodeFromString(ChangeProposal.serializer(), p.json)
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text("Od ${prop.authorId.take(8)} · ${prop.ops.size} změn · ${Labels.proposal(app.pulse4.training.ProposalStatus.valueOf(p.status))}")
                    Row {
                        TextButton({ scope.launch { withContext(Dispatchers.IO) { sl.planService.decide(p.id, true, System.currentTimeMillis(), sl.bridge.ensureStarted().deliveryHash); sl.shareService.flushPlanNotifications(System.currentTimeMillis()) }; refresh++ } }) { Text("Přijmout") }
                        TextButton({ scope.launch { withContext(Dispatchers.IO) { sl.planService.decide(p.id, false, System.currentTimeMillis(), sl.bridge.ensureStarted().deliveryHash) }; refresh++ } }) { Text("Zamítnout") }
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Krok") },
        text = { Column {
            androidx.compose.foundation.layout.FlowRow { StepType.entries.forEach { st -> FilterChip(type == st, { type = st }, { Text(WorkoutText.stepType(st)) }, Modifier.padding(2.dp)) } }
            Row { listOf("time" to "Čas (s)", "distance" to "Vzdálenost (m)", "lap" to "Tlačítko").forEach { (k, l) -> FilterChip(kind == k, { kind = k }, { Text(l) }, Modifier.padding(2.dp)) } }
            if (kind != "lap") OutlinedTextField(value, { value = it.filter(Char::isDigit) }, label = { Text("Hodnota") })
        } },
        confirmButton = { TextButton({ onOk(StepForm.build(type, kind, value)) }) { Text("Přidat") } },
        dismissButton = { TextButton(onDismiss) { Text("Zrušit") } })
}

/** Typický intervalový blok: N× (práce → odpočinek), práce s volitelným cílem (tempo u běhu, výkon u kola). */
@Composable
private fun IntervalDialog(sport: Sport, onDismiss: () -> Unit, onOk: (app.pulse4.training.WorkoutNode.Repeat) -> Unit) {
    var count by remember { mutableStateOf("4") }
    var workKind by remember { mutableStateOf("distance") }
    var work by remember { mutableStateOf("1000") }
    var rest by remember { mutableStateOf("120") }
    var lo by remember { mutableStateOf("") }
    var hi by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Intervaly") },
        text = { Column {
            OutlinedTextField(count, { count = it.filter(Char::isDigit) }, label = { Text("Opakování") })
            Row { listOf("distance" to "Práce m", "time" to "Práce s").forEach { (k, l) -> FilterChip(workKind == k, { workKind = k }, { Text(l) }, Modifier.padding(2.dp)) } }
            OutlinedTextField(work, { work = it.filter(Char::isDigit) }, label = { Text("Práce") })
            OutlinedTextField(rest, { rest = it.filter(Char::isDigit) }, label = { Text("Odpočinek (s)") })
            val unit = if (sport == Sport.RUNNING) "tempo s/km (rychlejší / pomalejší)" else "výkon W (od / do)"
            Text("Cíl práce – $unit, volitelné")
            Row { OutlinedTextField(lo, { lo = it.filter(Char::isDigit) }, Modifier.weight(1f)); OutlinedTextField(hi, { hi = it.filter(Char::isDigit) }, Modifier.weight(1f)) }
        } },
        confirmButton = { TextButton({ onOk(IntervalForm.build(sport, count, workKind, work, rest, lo, hi)) }) { Text("Přidat") } },
        dismissButton = { TextButton(onDismiss) { Text("Zrušit") } })
}
