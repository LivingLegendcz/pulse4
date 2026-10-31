package app.pulse4.android.di

import android.content.Context
import app.pulse4.android.BuildConfig
import app.pulse4.android.data.ExcludedRow
import app.pulse4.android.data.RoomAreaStateStore
import app.pulse4.android.data.toDomain
import app.pulse4.android.data.toRow
import app.pulse4.android.net.OkHttpGateway
import app.pulse4.android.security.SecretStore
import app.pulse4.domain.MeasurementExclusions
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.TrendSelector
import app.pulse4.provider.AreaResult
import app.pulse4.provider.ProviderError
import app.pulse4.provider.SyncArea
import app.pulse4.provider.SyncEngine
import app.pulse4.sync.Wire
import app.pulse4.withings.AuthResult
import app.pulse4.withings.CsvImportResult
import app.pulse4.withings.HcChange
import app.pulse4.withings.HcRecord
import app.pulse4.withings.HealthConnectMapper
import app.pulse4.withings.PendingAuth
import app.pulse4.withings.PendingAuthStore
import app.pulse4.withings.TokenVault
import app.pulse4.withings.WithingsApi
import app.pulse4.withings.WithingsConfig
import app.pulse4.withings.WithingsCredentials
import app.pulse4.withings.WithingsCsv
import app.pulse4.withings.WithingsFetcher
import app.pulse4.withings.WithingsOAuth
import app.pulse4.withings.WithingsSession
import app.pulse4.withings.WithingsTokens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Tokeny Withings: zapečetěné Keystorem, zápis atomicky (viz [SecretStore.put]); do zálohy a diagnostiky se nikdy nedostanou. */
class SecretTokenVault(private val secrets: SecretStore, private val keyName: String = "withings.tokens") : TokenVault {
    override fun load(): WithingsTokens? = secrets.getString(keyName)?.let { runCatching { Wire.json.decodeFromString(WithingsTokens.serializer(), it) }.getOrNull() }
    override fun save(t: WithingsTokens) = secrets.putString(keyName, Wire.json.encodeToString(WithingsTokens.serializer(), t))
    override fun clear() = secrets.delete(keyName)
}

class SecretPendingStore(private val secrets: SecretStore, private val keyName: String = "withings.pending") : PendingAuthStore {
    override fun put(p: PendingAuth) = secrets.putString(keyName, "${p.state}|${p.createdAtMs}|${p.redirectUri}")
    override fun get(): PendingAuth? = secrets.getString(keyName)?.split('|', limit = 3)?.takeIf { it.size == 3 }?.let { PendingAuth(it[0], it[1].toLongOrNull() ?: 0, it[2]) }
    override fun clear() = secrets.delete(keyName)
}

sealed interface WithingsUiState {
    data object Idle : WithingsUiState
    data object WaitingForBrowser : WithingsUiState
    data class Connected(val userId: String) : WithingsUiState
    data class Failed(val message: String) : WithingsUiState
}

/**
 * Přímé Withings API bez našeho serveru: OAuth ve vlastním telefonu s individuálními vývojářskými přístupy.
 * Podporuje jak hlavní profil rodiče, tak souběžné připojení dětského profilu (`childVault`) a automatické
 * přeposílání dětské váhy přes šifrovaný P2P kanál Reticulum přímo do telefonu dítěte.
 */
class WithingsService(private val context: Context, private val sl: ServiceLocator) {
    private val vault = SecretTokenVault(sl.secrets, "withings.tokens")
    private val childVault = SecretTokenVault(sl.secrets, "withings.child.tokens")
    private val http = OkHttpGateway()
    private val _state = MutableStateFlow<WithingsUiState>(if (vault.load() != null) WithingsUiState.Connected(vault.load()!!.userId) else WithingsUiState.Idle)
    val state: StateFlow<WithingsUiState> = _state
    @Volatile private var pendingForChild: Boolean = false

    private fun config() = WithingsConfig(redirectUri = BuildConfig.WITHINGS_REDIRECT)
    private fun creds(): WithingsCredentials? {
        val id = sl.settings.withingsClientId; val secret = sl.secrets.getString(CLIENT_SECRET)
        return if (id.isNotBlank() && !secret.isNullOrBlank()) WithingsCredentials(id, secret) else null
    }
    private val session by lazy { WithingsSession(config(), ::creds, http, vault) }
    private val oauth by lazy { WithingsOAuth(config(), ::creds, http, vault, SecretPendingStore(sl.secrets, "withings.pending")) }

    private val childSession by lazy { WithingsSession(config(), ::creds, http, childVault) }
    private val childOAuth by lazy { WithingsOAuth(config(), ::creds, http, childVault, SecretPendingStore(sl.secrets, "withings.child.pending")) }

    fun saveCredentials(clientId: String, secret: String) { sl.settings.withingsClientId = clientId; sl.secrets.putString(CLIENT_SECRET, secret.trim()) }
    fun hasCredentials() = creds() != null
    fun childUserId(): String? = childVault.load()?.userId

    /** URL pro systémový prohlížeč (ne WebView) – hlavní profil rodiče. */
    fun beginLogin(nowMs: Long): String {
        pendingForChild = false
        val u = oauth.begin(nowMs)
        _state.value = WithingsUiState.WaitingForBrowser
        return u
    }

    /** URL pro připojení dětského profilu z rodinného účtu Withings (na webu Withingsu rodič vybere profil dítěte). */
    fun beginChildLogin(nowMs: Long): String {
        pendingForChild = true
        return childOAuth.begin(nowMs)
    }

    /** Voláno z `MainActivity` při návratu přes App Link. Kód se vyměňuje ihned (platí jen 30 s); URL se nikdy nelogují. */
    fun handleCallback(url: String, receivedAtMs: Long) {
        if (pendingForChild) {
            pendingForChild = false
            childOAuth.complete(url, receivedAtMs, System.currentTimeMillis(), childSession)
            return
        }
        _state.value = when (val r = oauth.complete(url, receivedAtMs, System.currentTimeMillis(), session)) {
            is AuthResult.Connected -> WithingsUiState.Connected(r.userId)
            is AuthResult.Failed -> WithingsUiState.Failed(r.reason.name)
        }
    }

    fun disconnect() { vault.clear(); _state.value = WithingsUiState.Idle }
    fun disconnectChild() { childVault.clear() }

    /** Import váhy (okno + lastupdate + tombstony). Stav po zdroji: oblast `withings.WEIGHT`. */
    fun runImport(nowMs: Long): AreaResult? {
        if (!sl.settings.withingsDirectEnabled || !session.isConnected()) return null
        val db = sl.db
        val fetcher = WithingsFetcher(
            config(), WithingsApi(config(), session, http), session, { vault.load()!!.userId }, sl.settings.zoneId,
            existingIds = { f, t -> db.observations().idsIn(Provider.WITHINGS_API.name, f, t).toSet() },
            exclusions = MeasurementExclusions { db.exclusions().count(it) > 0 }, nowMs = { nowMs },
        )
        val engine = SyncEngine(fetcher, { _, b ->
            db.runInTransaction {
                db.observations().upsert(b.items.map { it.toRow() })
                if (b.tombstones.isNotEmpty()) db.observations().markDeleted(b.tombstones)
            }
        }, RoomAreaStateStore(db, "withings."))
        return runCatching { engine.runArea(SyncArea.WEIGHT, nowMs) }.getOrElse { AreaResult.Failed(it as? ProviderError ?: ProviderError.Server(0)) }
    }

    /** CSV export z aplikace Withings – nouzová a referenční cesta. Vrací počet přijatých záznamů a chyby po řádcích. */
    fun importCsv(bytes: ByteArray): CsvImportResult {
        val r = WithingsCsv.parse(bytes, sl.settings.zoneId)
        val ex = MeasurementExclusions { sl.db.exclusions().count(it) > 0 }
        sl.db.runInTransaction { sl.db.observations().upsert(app.pulse4.domain.ImportFilter.apply(r.items, ex).map { it.toRow() }) }
        return r
    }

    /** Označí měření za chybné (cizí osoba): zdroj zůstane, ale do trendu nevstoupí ani po opakovaném importu. */
    fun markWrong(observationId: String, nowMs: Long) = sl.db.runInTransaction {
        sl.db.exclusions().put(ExcludedRow(observationId, nowMs)); sl.db.observations().markDeleted(listOf(observationId))
    }

    /** Potvrdí podezřelé měření (nejednoznačná osoba), čímž vstoupí do trendu. */
    fun confirm(observationId: String) = sl.db.observations().confirm(observationId)

    /** Body trendu váhy/tuku: jedno měření na shluk duplicit podle zvolené priority zdrojů a ochranného filtru rodinných/dětských vážení. */
    fun trend(type: MetricType, days: Int = 180, includeSuspect: Boolean = false): List<app.pulse4.domain.MetricObservation> {
        val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
        val rawRows = sl.db.observations().ofTypesIncludingDeleted(listOf(type.name), today.minusDays(days.toLong()).toString(), today.toString()).map { it.toDomain() }
        val filterEnabled = sl.settings.weightFilterEnabled
        val minKg = sl.settings.weightFilterMinKg
        val maxKg = sl.settings.weightFilterMaxKg
        val rows = if (type == MetricType.WEIGHT && filterEnabled) {
            rawRows.map { obs ->
                if (obs.value !in minKg..maxKg) obs.copy(quality = app.pulse4.domain.Quality.SUSPECT) else obs
            }
        } else {
            rawRows
        }
        val pref = sl.settings.weightPreference.mapNotNull { runCatching { Provider.valueOf(it) }.getOrNull() }
        return TrendSelector.points(rows, pref, includeSuspect).sortedBy { it.startUtcMs }
    }

    /** Vrací nejednoznačná měření (attrib z váhy Withings nebo měření mimo váhový filtr profilu, např. dítě/člen rodiny) čekající na rozhodnutí. */
    fun suspectMeasurements(days: Int = 180): List<app.pulse4.domain.MetricObservation> {
        val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
        val rawRows = sl.db.observations()
            .ofTypesIncludingDeleted(listOf(MetricType.WEIGHT.name), today.minusDays(days.toLong()).toString(), today.toString())
            .map { it.toDomain() }
            .filter { !it.deleted }
        val filterEnabled = sl.settings.weightFilterEnabled
        val minKg = sl.settings.weightFilterMinKg
        val maxKg = sl.settings.weightFilterMaxKg
        return rawRows.mapNotNull { obs ->
            val outOfBounds = filterEnabled && obs.value !in minKg..maxKg
            if (obs.quality == app.pulse4.domain.Quality.SUSPECT || outOfBounds) {
                obs.copy(quality = app.pulse4.domain.Quality.SUSPECT)
            } else null
        }.sortedByDescending { it.startUtcMs }
    }

    /**
     * Přepošle jedno konkrétní měření váhy (např. ze seznamu nejednoznačných měření na váze rodiče nebo zadanou váhu dítěte)
     * přes šifrovaný P2P kanál Reticulum přímo do telefonu dítěte (`sl.settings.childRecipientIdentity`), kde se uloží
     * do jeho vlastních osobních metrik (`observation`). Zároveň měření vyřadí z vlastního grafu rodiče.
     */
    fun forwardSingleWeightToChild(obs: app.pulse4.domain.MetricObservation, nowMs: Long, excludeFromParent: Boolean = true): Boolean {
        val childId = sl.settings.childRecipientIdentity
        if (childId.isBlank()) return false
        val count = forwardToChild(listOf(obs), childId, nowMs)
        if (excludeFromParent) {
            markWrong(obs.id, nowMs)
        }
        runCatching { sl.transport.pump(nowMs, 2500L) }
        return count > 0
    }

    /**
     * Stáhne vážení z připojeného dětského profilu ve Withings (`childVault`) a také zkontroluje dětská vážení
     * zachycená v dětském váhovém pásmu (`childWeightMinKg..childWeightMaxKg`) a odešle je přes P2P do telefonu dítěte.
     */
    fun syncAndForwardChildWeights(nowMs: Long): Int {
        val childRecipient = sl.settings.childRecipientIdentity
        if (childRecipient.isBlank()) return 0
        val toForward = mutableListOf<app.pulse4.domain.MetricObservation>()

        // 1. Pokud má rodič připojený OAuth token přímo pro dětský profil ve Withingsu (`childVault`):
        val cUid = childVault.load()?.userId
        if (cUid != null && childSession.isConnected()) {
            runCatching {
                val api = WithingsApi(config(), childSession, http)
                val from = nowMs - 30L * 86_400_000L
                val mapped = app.pulse4.withings.WithingsMapper.map(
                    api.getMeasures(WithingsApi.Filter.DATE_WINDOW, from, nowMs),
                    cUid,
                    sl.settings.zoneId,
                    config()
                )
                toForward += mapped.items.map { it.copy(quality = app.pulse4.domain.Quality.OK) }
            }
        }

        // 2. Pokud je zapnuté automatické přeposílání dětského váhového pásma z váhy rodiče (např. 12–45 kg):
        if (sl.settings.childWeightAutoForward) {
            val cMin = sl.settings.childWeightMinKg
            val cMax = sl.settings.childWeightMaxKg
            val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
            val recent = sl.db.observations()
                .ofTypesIncludingDeleted(listOf(MetricType.WEIGHT.name), today.minusDays(30).toString(), today.toString())
                .map { it.toDomain() }
                .filter { !it.deleted && it.value in cMin..cMax }
            recent.forEach { obs ->
                toForward += obs.copy(quality = app.pulse4.domain.Quality.OK)
                // Pokud dětské vážení leží mimo dospělý filtr rodiče, vyřadíme ho z grafu rodiče
                if (sl.settings.weightFilterEnabled && obs.value !in sl.settings.weightFilterMinKg..sl.settings.weightFilterMaxKg) {
                    markWrong(obs.id, nowMs)
                }
            }
        }

        val sentCount = forwardToChild(toForward.distinctBy { it.id }, childRecipient, nowMs)
        if (sentCount > 0) {
            runCatching { sl.transport.pump(nowMs, 2500L) }
        }
        return sentCount
    }

    private fun forwardToChild(
        observations: List<app.pulse4.domain.MetricObservation>,
        childRecipient: String,
        nowMs: Long,
    ): Int {
        if (observations.isEmpty() || childRecipient.isBlank()) return 0
        val self = sl.bridge.ensureStarted().deliveryHash
        val builder = app.pulse4.sync.MessageBuilder(self)
        var queued = 0
        for (obs in observations) {
            val cleanObs = obs.copy(
                deleted = false,
                quality = app.pulse4.domain.Quality.OK,
                origin = "withings-family:$self"
            )
            val payload = Wire.json.encodeToString(app.pulse4.domain.MetricObservation.serializer(), cleanObs)
            val hash = Wire.sha256Hex(payload.toByteArray())
            val key = "own_weight/${cleanObs.id}"
            val last = sl.sentLedger.last(childRecipient, key)
            if (last?.contentHash == hash) continue
            val version = (last?.version ?: 0L) + 1L
            val msg = builder.build(
                recipient = childRecipient,
                kind = FAMILY_WEIGHT_PUSH_KIND,
                objectType = "own_weight",
                objectId = cleanObs.id,
                objectVersion = version,
                payload = payload,
                nowMs = nowMs
            )
            val e = msg.envelope
            val item = app.pulse4.sync.OutboxItem(
                e.eventId, e.recipient, e.kind, e.objectType, e.objectId, e.objectVersion, null, Wire.encode(msg), nowMs
            )
            if (sl.outbox.enqueue(item) is app.pulse4.sync.EnqueueResult.Queued) {
                sl.sentLedger.record(childRecipient, key, version, hash)
                queued++
            }
        }
        return queued
    }

    companion object {
        private const val CLIENT_SECRET = "withings.clientsecret"
        const val FAMILY_WEIGHT_PUSH_KIND = "family.weight.push"
    }
}
