package app.pulse4.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import app.pulse4.android.Pulse4App
import app.pulse4.android.data.ContactRow
import app.pulse4.android.data.ReceivedRow
import app.pulse4.android.security.SecretStore
import app.pulse4.android.work.Scheduler
import app.pulse4.domain.Sport
import app.pulse4.sharing.ActivityProjection
import app.pulse4.sharing.ChartSeries
import app.pulse4.sharing.Field
import app.pulse4.sharing.Group
import app.pulse4.sharing.RoutePoint
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
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Debug-only BroadcastReceiver guarded by android.permission.DUMP (P0-1, SEC-5).
 * Replaces all debug intent extras previously handled by exported MainActivity.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sl = (context.applicationContext as Pulse4App).services
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.action == "app.pulse4.debug.SEED_DEMO") {
                    DemoSeeder.seed(context.applicationContext, sl)
                    return@launch
                }

                intent.getStringExtra("set_garmin_key")?.let { key ->
                    sl.secrets.putString(SecretStore.GARMIN_API_KEY, key.trim())
                    sl.secrets.putString(
                        SecretStore.GARMIN_API_KEY_EXPIRES,
                        (System.currentTimeMillis() + 365L * 86_400_000L).toString()
                    )
                }

                intent.getStringExtra("set_gateway")?.let { hostRaw ->
                    val raw = hostRaw.trim()
                    if (raw.contains(":")) {
                        val parts = raw.split(":")
                        sl.settings.gatewayHost = parts[0].trim()
                        parts[1].trim().toIntOrNull()?.let { sl.settings.gatewayPort = it }
                    } else {
                        sl.settings.gatewayHost = raw
                    }
                    sl.bridge.applyPropagationNode()
                    Log.i("Pulse4", "Gateway set to ${sl.settings.gatewayHost}:${sl.settings.gatewayPort}")
                }

                intent.getStringExtra("add_peer_endpoint")?.let { ep ->
                    val parts = ep.split("=")
                    if (parts.size == 2) {
                        sl.bridge.registerLocalPeer(parts[0].trim(), parts[1].trim())
                        Log.i("Pulse4", "Registered local peer: ${parts[0]} -> ${parts[1]}")
                    }
                }

                if (intent.getBooleanExtra("clear_peer_endpoints", false)) {
                    sl.bridge.clearLocalPeers()
                }

                if (intent.getBooleanExtra("send_test_message", false)) {
                    val to = intent.getStringExtra("to_hash").orEmpty()
                    val text = intent.getStringExtra("msg_text") ?: "P2P TEST"
                    val forceGateway = intent.getBooleanExtra("force_gateway", false)
                    sl.transport.forceGateway = forceGateway
                    val selfHash = sl.bridge.ensureStarted().deliveryHash
                    val nowMs = System.currentTimeMillis()
                    val mb = MessageBuilder(selfHash)
                    val msg = mb.build(
                        recipient = to,
                        kind = Kinds.PROJECTION_UPSERT,
                        objectType = "daily",
                        objectId = "test-$nowMs",
                        objectVersion = 1L,
                        payload = """{"ownerId":"${sl.settings.displayName}","d":"$text"}""",
                        nowMs = nowMs
                    )
                    val wire = Wire.encode(msg)
                    sl.outbox.enqueue(
                        OutboxItem(
                            eventId = msg.envelope.eventId,
                            recipient = to,
                            kind = msg.envelope.kind,
                            objectType = msg.envelope.objectType,
                            objectId = msg.envelope.objectId,
                            objectVersion = msg.envelope.objectVersion,
                            grantId = null,
                            wire = wire,
                            createdAtMs = nowMs
                        )
                    )
                    Log.i("Pulse4Sync", "ENQUEUED test message ${msg.envelope.eventId} to $to: $text")
                    val stats = sl.transport.pump(System.currentTimeMillis(), 5000L)
                    Log.i("Pulse4Sync", "PUMP STATS: $stats")
                }

                if (intent.getBooleanExtra("run_import", false)) {
                    Scheduler.importNow(context)
                }

                if (intent.getBooleanExtra("run_share", false)) {
                    Scheduler.shareNow(context, manual = true)
                }

                if (intent.getBooleanExtra("clear_test_data", false)) {
                    sl.importService.clearTestDataAndResetActivities()
                    val imported = sl.importService.syncGarminActivities(System.currentTimeMillis())
                    Log.i("Pulse4", "Synchronizace dokončena: importováno $imported aktivit.")
                }

                intent.getStringExtra("import_fit_path")?.let { p ->
                    val bytes = File(p).readBytes()
                    val res = sl.importService.importFit(bytes, System.currentTimeMillis())
                    Log.i("Pulse4", "Imported FIT from $p: $res")
                }

                intent.getStringExtra("add_contact_b64")?.let { b64 ->
                    val raw = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                    handleAddContact(sl, raw)
                }

                intent.getStringExtra("add_contact_json")?.let { raw ->
                    handleAddContact(sl, raw)
                }

                intent.getStringExtra("create_group")?.let { groupName ->
                    val self = sl.bridge.ensureStarted().deliveryHash
                    val contacts = sl.db.contacts().allNow().map { it.identity }.toSet()
                    val g = Group(
                        UUID.randomUUID().toString(),
                        groupName.trim(),
                        self,
                        self,
                        contacts + self,
                        1L
                    )
                    sl.shareService.saveGroup(g)
                }

                intent.getStringExtra("create_grant_all")?.let { target ->
                    val self = sl.bridge.ensureStarted().deliveryHash
                    val recipients = if (target == "all") {
                        sl.db.contacts().allNow().map { it.identity }.toSet()
                    } else {
                        setOf(target)
                    }
                    val allFields = Field.entries.toSet()
                    val g = ShareGrant(
                        id = UUID.randomUUID().toString(),
                        ownerId = self,
                        recipients = recipients,
                        fields = allFields,
                        scope = TimeScope.continuousFrom(LocalDate.now(ZoneId.of(sl.settings.zoneId)).minusDays(60)),
                        groupId = null,
                        createdAtMs = System.currentTimeMillis()
                    )
                    sl.shareService.saveGrantWithPolicy(g)
                }

                intent.getStringExtra("seed_inbox_projection")?.let { authorId ->
                    seedInboxProjection(sl, authorId)
                }

                if (intent.getBooleanExtra("dump_pairing", false)) {
                    val info = sl.bridge.ensureStarted()
                    val p = PairingPayload(
                        Pairing.FORMAT,
                        info.publicKeyB64,
                        info.deliveryHash,
                        sl.settings.displayName
                    )
                    val json = Pairing.encode(p)
                    Log.i("Pulse4Pairing", "PAIRING_PAYLOAD:$json, port=${info.localPort}")
                }

                if (intent.getBooleanExtra("announce", false)) {
                    sl.bridge.ensureStarted()
                    sl.bridge.announce()
                }

                if (intent.getBooleanExtra("resend_all", false)) {
                    sl.db.inbox().deleteAllSent()
                    sl.db.outbox().deleteAll()
                    val enqueued = sl.shareService.planAndEnqueue(System.currentTimeMillis())
                    Log.i("Pulse4Sync", "RESEND_ALL: enqueued=$enqueued items into fresh outbox")
                }

                if (intent.getBooleanExtra("trigger_pump", false)) {
                    val realNow = System.currentTimeMillis()
                    val enqueued = sl.shareService.planAndEnqueue(realNow)
                    val pumpDueTime = realNow + 86_400_000L
                    val stats = sl.transport.pump(pumpDueTime, windowMs = 12000)
                    Log.i(
                        "Pulse4Sync",
                        "PUMP_RESULT: enqueued=$enqueued, sent=${stats.sent}, received=${stats.received}, rejected=${stats.rejected}, pending=${stats.pendingInFlight}"
                    )
                }

                if (intent.getBooleanExtra("dump_status", false)) {
                    val info = sl.bridge.ensureStarted()
                    val contacts = sl.db.contacts().allNow()
                    val outboxList = sl.db.outbox().all()
                    val outboxDetails = outboxList.map { "${it.objectType}/${it.objectId}:${it.state}(att=${it.attempts},sz=${it.wire.size})" }
                    val actsCount = sl.db.activities().count()
                    val status = sl.bridge.statusJson()
                    val inDaily = sl.db.inbox().objectsNow("daily").size
                    val inActs = sl.db.inbox().objectsNow("activity").size
                    val recActs = sl.db.activities().recentSummariesNow(5).map { "${it.id}(${it.sport}): ${it.startUtcMs}" }
                    Log.i(
                        "Pulse4Status",
                        "STATUS: hash=${info.deliveryHash}, contacts=${contacts.size}, outbox=${outboxList.size} $outboxDetails, activities=$actsCount, inDaily=$inDaily, inActs=$inActs, recActs=$recActs, rns=$status"
                    )
                }
            } catch (e: Throwable) {
                Log.e("Pulse4Debug", "Debug command failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleAddContact(sl: app.pulse4.android.di.ServiceLocator, raw: String) {
        when (val r = Pairing.parse(raw)) {
            is PairingResult.Ok -> {
                sl.bridge.ensureStarted()
                val derived = sl.bridge.addContact(r.payload.pub)
                if (!Pairing.addressMatches(r.payload.addr, derived)) {
                    sl.bridge.removeContact(derived)
                    Log.e("Pulse4Contact", "REJECTED: Pairing address mismatch")
                } else {
                    sl.db.contacts().upsert(
                        ContactRow(
                            identity = derived,
                            displayName = r.payload.name,
                            publicKeyB64 = r.payload.pub,
                            fingerprint = r.fingerprint,
                            verifiedAtMs = System.currentTimeMillis()
                        )
                    )
                    Log.i("Pulse4Contact", "SUCCESS: Added contact (addr=$derived, fp=${r.fingerprint})")
                }
            }
            is PairingResult.Invalid -> {
                Log.e("Pulse4Contact", "Invalid pairing payload: ${r.reason}")
            }
        }
    }

    private fun seedInboxProjection(sl: app.pulse4.android.di.ServiceLocator, authorId: String) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.of(sl.settings.zoneId)
        val today = LocalDate.now(zone)
        for (i in 6 downTo 0) {
            val d = today.minusDays(i.toLong())
            val dStr = d.toString()
            val proj = SharedProjection(
                ownerId = authorId,
                grantId = "demo-grant",
                date = dStr,
                fields = mapOf(
                    Field.WEIGHT to JsonPrimitive(74.0 + (6 - i) * 0.2),
                    Field.SLEEP_DURATION to JsonPrimitive(27000.0 + (i % 3) * 1200.0),
                    Field.HRV_DAILY to JsonPrimitive(54.0 + (i % 4) * 3.0),
                    Field.BB_LAST to JsonPrimitive(65.0 + (i % 5) * 6.0),
                    Field.HR_DAILY to JsonPrimitive(53.0 - (i % 3) * 1.0)
                ),
                measuredAtUtcMs = now - i * 86_400_000L,
                producedAtMs = now - i * 86_400_000L
            )
            val payload = Wire.json.encodeToString(SharedProjection.serializer(), proj)
            sl.db.inbox().putObject(
                ReceivedRow(
                    author = authorId,
                    objectType = "daily",
                    objectId = dStr,
                    version = 1L,
                    payload = payload
                )
            )
        }
        val hrPoints = (0 until 60).map { s -> (s * 30_000L) to (142.0 + sin(s / 5.0) * 14.0) }
        val spdPoints = (0 until 60).map { s -> (s * 30_000L) to (3.3 + cos(s / 6.0) * 0.35) }
        val elePoints = (0 until 60).map { s -> (s * 30_000L) to (215.0 + s * 1.4) }
        val routePoints = (0 until 60).map { s ->
            RoutePoint(45.0200 + s * 0.00045, 15.0200 + sin(s / 8.0) * 0.0006)
        }
        val actProj = ActivityProjection(
            activityId = "p2p-run-1",
            sport = Sport.RUNNING.name,
            localDate = today.toString(),
            startUtcMs = now - 7200_000L,
            totalTimeS = 1800.0,
            movingTimeS = 1765.0,
            distanceM = 5840.0,
            ascentM = 84.0,
            avgHeartRate = 149,
            charts = listOf(
                ChartSeries("heartRate", hrPoints),
                ChartSeries("speed", spdPoints),
                ChartSeries("elevation", elePoints)
            ),
            route = routePoints
        )
        val actPayload = Wire.json.encodeToString(ActivityProjection.serializer(), actProj)
        sl.db.inbox().putObject(
            ReceivedRow(
                author = authorId,
                objectType = "activity",
                objectId = "p2p-run-1",
                version = 1L,
                payload = actPayload
            )
        )
        Log.i("Pulse4", "Seeded demo projections and shared activity for $authorId")
    }
}
