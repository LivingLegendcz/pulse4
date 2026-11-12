package app.pulse4.android.di

import android.content.Context
import app.pulse4.android.BuildConfig
import app.pulse4.android.data.AreaStateRow
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
import app.pulse4.withings.WithingsWebClient
import app.pulse4.withings.WithingsWebConfig
import app.pulse4.withings.WithingsWebFetcher
import app.pulse4.withings.WithingsWebMapper
import app.pulse4.withings.WithingsWebSession
import app.pulse4.withings.WithingsUserProfile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    data class Connected(
        val userId: String,
        val userName: String? = null,
        val isWeb: Boolean = false,
        val childName: String? = null,
        val lastSyncMs: Long? = null,
    ) : WithingsUiState
    data class Failed(val message: String) : WithingsUiState
}

/**
 * Konektor Withings:
 * 1. Webový konektor (doporučený, bez nutnosti developer účtu, API klíčů či serveru):
 *    Uživatel se přihlásí ve WebView, konektor převezme session cookies a komunikuje přímo
 *    se scalews.withings.com API.
 * 2. OAuth API (záložní cesta pro vývojářské účty s Client ID a Secretem).
 * 3. CSV import a manuální zápis váhy.
 * Podporuje jak hlavní profil rodiče, tak volbu dětského profilu a automatické přeposílání
 * dětských měření přes šifrovaný P2P kanál Reticulum přímo do telefonu dítěte.
 */
class WithingsService(private val context: Context, private val sl: ServiceLocator) {
    private val vault = SecretTokenVault(sl.secrets, "withings.tokens")
    private val childVault = SecretTokenVault(sl.secrets, "withings.child.tokens")
    private val http = OkHttpGateway()
    private val webClient by lazy { WithingsWebClient(http) }

    fun loadWebSession(): WithingsWebSession? =
        sl.secrets.getString(WEB_SESSION_KEY)?.let {
            runCatching { Wire.json.decodeFromString(WithingsWebSession.serializer(), it) }.getOrNull()
        }

    fun saveWebSession(s: WithingsWebSession) {
        sl.secrets.putString(WEB_SESSION_KEY, Wire.json.encodeToString(WithingsWebSession.serializer(), s))
        sl.settings.withingsDirectEnabled = true
        _state.value = WithingsUiState.Connected(
            userId = s.primaryUserId.toString(),
            userName = s.primaryUserName,
            isWeb = true,
            childName = s.childUserName,
            lastSyncMs = s.lastSyncMs
        )
    }

    fun clearWebSession() {
        sl.secrets.delete(WEB_SESSION_KEY)
        sl.secrets.delete("withings.web.password")
        val oauth = vault.load()
        if (oauth != null) {
            _state.value = WithingsUiState.Connected(oauth.userId)
        } else {
            _state.value = WithingsUiState.Idle
        }
    }

    private val _state = MutableStateFlow<WithingsUiState>(
        loadWebSession()?.let {
            WithingsUiState.Connected(
                userId = it.primaryUserId.toString(),
                userName = it.primaryUserName,
                isWeb = true,
                childName = it.childUserName,
                lastSyncMs = it.lastSyncMs
            )
        }
            ?: vault.load()?.let { WithingsUiState.Connected(it.userId) }
            ?: WithingsUiState.Idle
    )
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
    fun isConnected(): Boolean = loadWebSession()?.isConnected() == true || session.isConnected()
    fun childUserId(): String? = loadWebSession()?.childUserId?.toString() ?: childVault.load()?.userId

    /** Ověří cookies a načte seznam profilů účtu pro dialog výběru profilu */
    fun verifyWebCookies(cookies: String, sessionToken: String? = null): List<WithingsUserProfile> {
        return webClient.getUsersList(cookies, sessionToken)
    }

    private val directAuth by lazy { WithingsDirectAuth(webClient) }

    fun pendingEmail(): String? = directAuth.pendingEmail
    fun isAwaitingCode(): Boolean = directAuth.isAwaitingCode

    /**
     * Vyžádá zaslání 8místného přihlašovacího kódu na e-mail v pozadí (pro účty Google, Apple i účty bez hesla).
     */
    fun requestDirectEmailCode(email: String): WithingsLoginResult {
        val em = email.trim()
        sl.secrets.putString("withings.web.email", em)
        val r = directAuth.requestEmailCode(em)
        if (r is WithingsLoginResult.Ok) {
            val existing = loadWebSession()
            val primary = existing?.primaryUserId?.let { pid -> r.profiles.firstOrNull { it.id == pid } }
                ?: r.profiles.firstOrNull { it.isPrimary }
                ?: r.profiles.first()
            val child = existing?.childUserId?.let { cid -> r.profiles.firstOrNull { it.id == cid } }
            completeWebLogin(
                cookies = r.session.cookies,
                primary = primary,
                child = child,
                sessionToken = r.session.sessionToken,
                allProfiles = r.profiles,
                email = em
            )
        }
        return r
    }

    /** Znovu odešle 8místný přihlašovací kód na e-mail v pozadí. */
    fun resendDirectEmailCode(emailFallback: String? = null): WithingsLoginResult {
        return directAuth.resendEmailCode(emailFallback)
    }

    /** Nativní přihlášení e-mailem a heslem v pozadí (stejně jako u Garminu) */
    fun loginDirectPassword(email: String, password: String): WithingsLoginResult {
        val em = email.trim()
        val r = directAuth.loginPassword(em, password)
        if (r is WithingsLoginResult.Ok) {
            val existing = loadWebSession()
            val primary = existing?.primaryUserId?.let { pid -> r.profiles.firstOrNull { it.id == pid } }
                ?: r.profiles.firstOrNull { it.isPrimary }
                ?: r.profiles.first()
            val child = existing?.childUserId?.let { cid -> r.profiles.firstOrNull { it.id == cid } }
            sl.secrets.putString("withings.web.email", em)
            sl.secrets.putString("withings.web.password", password)
            completeWebLogin(
                cookies = r.session.cookies,
                primary = primary,
                child = child,
                sessionToken = r.session.sessionToken,
                allProfiles = r.profiles,
                email = em
            )
        }
        return r
    }

    /** Dokončení dvoufázového ověření (kód z e-mailu nebo SMS) */
    fun resumeDirectMfa(code: String, emailFallback: String? = null): WithingsLoginResult {
        val r = directAuth.resumeMfa(code, emailFallback)
        if (r is WithingsLoginResult.Ok) {
            val existing = loadWebSession()
            val primary = existing?.primaryUserId?.let { pid -> r.profiles.firstOrNull { it.id == pid } }
                ?: r.profiles.firstOrNull { it.isPrimary }
                ?: r.profiles.first()
            val child = existing?.childUserId?.let { cid -> r.profiles.firstOrNull { it.id == cid } }
            val em = emailFallback?.trim() ?: directAuth.pendingEmail ?: existing?.email
            if (em != null) sl.secrets.putString("withings.web.email", em)
            val pass = directAuth.pendingPassword ?: sl.secrets.getString("withings.web.password")
            if (!pass.isNullOrBlank()) sl.secrets.putString("withings.web.password", pass)
            completeWebLogin(
                cookies = r.session.cookies,
                primary = primary,
                child = child,
                sessionToken = r.session.sessionToken,
                allProfiles = r.profiles,
                email = em
            )
        }
        return r
    }

    /** Automatické tiché opětovné přihlášení e-mailem a heslem ze zapečetěného úložiště, pokud relace vypršela. */
    fun tryAutoReLogin(): Boolean {
        val email = sl.secrets.getString("withings.web.email") ?: return false
        val pass = sl.secrets.getString("withings.web.password") ?: return false
        val r = directAuth.loginPassword(email, pass)
        if (r is WithingsLoginResult.Ok) {
            val existing = loadWebSession()
            val primary = existing?.primaryUserId?.let { pid -> r.profiles.firstOrNull { it.id == pid } }
                ?: r.profiles.firstOrNull { it.isPrimary }
                ?: r.profiles.first()
            val child = existing?.childUserId?.let { cid -> r.profiles.firstOrNull { it.id == cid } }
            completeWebLogin(
                cookies = r.session.cookies,
                primary = primary,
                child = child,
                sessionToken = r.session.sessionToken,
                allProfiles = r.profiles,
                email = email
            )
            return true
        }
        return false
    }

    /** Prodlouží existující webovou relaci Withings nebo provede automatické znovupřihlášení. */
    fun renewSession(): Boolean {
        if (sl.settings.withingsDirectEnabled) {
            val s = loadWebSession()
            if (s != null && s.isConnected()) {
                val extended = runCatching { webClient.extendSession(s.cookies, s.sessionToken) }.getOrNull()
                if (extended != null) {
                    val updated = s.copy(
                        sessionToken = extended.sessionToken ?: s.sessionToken,
                        cookies = extended.cookies,
                        lastExtendedMs = System.currentTimeMillis()
                    )
                    saveWebSession(updated)
                    return true
                }
                return tryAutoReLogin()
            }
            return false
        }
        if (session.isConnected()) {
            return runCatching { session.accessToken(); true }.getOrDefault(false)
        }
        return false
    }

    /** Dokončí přihlášení vybraných profilů a spustí počáteční import */
    fun completeWebLogin(
        cookies: String,
        primary: WithingsUserProfile,
        child: WithingsUserProfile? = null,
        sessionToken: String? = null,
        allProfiles: List<WithingsUserProfile> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        email: String? = null,
    ) {
        val existing = loadWebSession()
        val profiles = allProfiles.ifEmpty { existing?.allProfiles.orEmpty() }
        val effectiveEmail = email ?: existing?.email ?: sl.secrets.getString("withings.web.email")
        val s = WithingsWebSession(
            cookies = cookies,
            sessionToken = sessionToken ?: existing?.sessionToken,
            primaryUserId = primary.id,
            primaryUserName = primary.displayName,
            primaryUserTimezone = primary.timezone,
            childUserId = child?.id,
            childUserName = child?.displayName,
            allProfiles = profiles,
            lastSyncMs = existing?.lastSyncMs,
            lastExtendedMs = nowMs,
            email = effectiveEmail,
        )
        saveWebSession(s)
        sl.settings.withingsProfileLabel = primary.displayName
        runCatching { RoomAreaStateStore(sl.db, "withings.").put(SyncArea.WEIGHT, app.pulse4.provider.AreaState()) }
        // Spustit počáteční import na pozadí, aby neblokoval okamžité zavření UI dialogu:
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(1000L)
            runCatching { runWebImport(nowMs) }
        }
    }

    /** Přepne aktivní hlavní nebo dětský profil v rámci existující relace a spustí synchronizaci. */
    fun selectProfiles(primaryId: Long, childId: Long?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val s = loadWebSession() ?: return false
        val profiles = s.allProfiles.ifEmpty {
            runCatching { webClient.getUsersList(s.cookies, s.sessionToken) }.getOrDefault(emptyList())
        }
        val primary = profiles.firstOrNull { it.id == primaryId } ?: return false
        val child = childId?.let { cid -> profiles.firstOrNull { it.id == cid } }
        val updated = s.copy(
            primaryUserId = primary.id,
            primaryUserName = primary.displayName,
            primaryUserTimezone = primary.timezone,
            childUserId = child?.id,
            childUserName = child?.displayName,
            allProfiles = profiles
        )
        saveWebSession(updated)
        sl.settings.withingsProfileLabel = primary.displayName
        runCatching { RoomAreaStateStore(sl.db, "withings.").put(SyncArea.WEIGHT, app.pulse4.provider.AreaState()) }
        runCatching { runWebImport(nowMs) }
        return true
    }

    /** URL pro systémový prohlížeč – záložní OAuth přihlášení. */
    fun beginLogin(nowMs: Long): String {
        pendingForChild = false
        val u = oauth.begin(nowMs)
        _state.value = WithingsUiState.WaitingForBrowser
        return u
    }

    /** URL pro připojení dětského profilu z rodinného účtu Withings přes OAuth. */
    fun beginChildLogin(nowMs: Long): String {
        pendingForChild = true
        return childOAuth.begin(nowMs)
    }

    /** Voláno z `MainActivity` při návratu přes App Link. */
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

    fun disconnect() {
        clearWebSession()
        vault.clear()
        runCatching { RoomAreaStateStore(sl.db, "withings.").put(SyncArea.WEIGHT, app.pulse4.provider.AreaState()) }
        _state.value = WithingsUiState.Idle
    }

    fun disconnectChild() {
        val s = loadWebSession()
        if (s != null) {
            saveWebSession(s.copy(childUserId = null, childUserName = null))
        }
        childVault.clear()
    }

    /** Import dat přes webový konektor (váha, tuk, svaly, kosti, voda, beztuková hmota, viscerální tuk, cévní věk, PWV, BMR, tep, ECG). */
    fun runWebImport(nowMs: Long): AreaResult? {
        val s = loadWebSession() ?: return null
        if (!sl.settings.withingsDirectEnabled || !s.isConnected()) return null

        val db = sl.db
        val stateStore = RoomAreaStateStore(db, "withings.")
        // Pokud v DB chybí složení těla (viscerální tuk), ECG nebo segmentální rozložení svalů/tuku (|segm:), vynulujeme kurzor pro plný 730denní import:
        val latestEcg = db.observations().latest(MetricType.ECG.name)
        val latestMuscle = db.observations().latest(MetricType.MUSCLE_MASS.name)
        if (db.observations().latest(MetricType.VISCERAL_FAT.name) == null ||
            latestEcg == null ||
            latestEcg.value < 30.0 ||
            latestMuscle?.origin?.contains("|segm:") != true
        ) {
            stateStore.put(SyncArea.WEIGHT, app.pulse4.provider.AreaState())
        }
        val fetcher = WithingsWebFetcher(
            client = webClient,
            sessionProvider = ::loadWebSession,
            onSessionExtended = ::saveWebSession,
            defaultZone = sl.settings.zoneId,
            existingIds = { f, t -> db.observations().idsIn(Provider.WITHINGS_API.name, f, t).toSet() },
            exclusions = MeasurementExclusions { db.exclusions().count(it) > 0 },
            nowMs = { nowMs },
            config = WithingsWebConfig(),
            existingHeartRates = {
                val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
                db.observations()
                    .ofTypesIncludingDeleted(listOf(MetricType.HEART_RATE.name), today.minusDays(730).toString(), today.plusDays(1).toString())
                    .filter { !it.deleted && it.provider == Provider.WITHINGS_API.name }
                    .associate { (it.startUtcMs / 1000L) to it.value }
            }
        )
        val engine = SyncEngine(
            fetcher = fetcher,
            sink = { _, batch ->
                db.runInTransaction {
                    db.observations().upsert(batch.items.map { it.toRow() })
                    if (batch.tombstones.isNotEmpty()) {
                        db.observations().markDeleted(batch.tombstones)
                    }
                }
            },
            states = stateStore
        )

        // Odstranit případné syntetické záložní váhy (garmin-hist-weight-*), jakmile je Withings připojen:
        runCatching {
            val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
            val fakeIds = db.observations()
                .ofTypesIncludingDeleted(listOf(MetricType.WEIGHT.name), today.minusDays(730).toString(), today.plusDays(1).toString())
                .filter { !it.deleted && (it.id.startsWith("garmin-hist-weight-") || it.externalId?.startsWith("hist-w-") == true) }
                .map { it.id }
            if (fakeIds.isNotEmpty()) {
                db.observations().markDeleted(fakeIds)
            }
        }

        val result = engine.runArea(SyncArea.WEIGHT, nowMs)
        when (result) {
            is AreaResult.Success -> {
                val latestSession = loadWebSession() ?: s
                saveWebSession(latestSession.copy(lastSyncMs = nowMs))
                // Pokud je v session nastavený dětský profil, stáhneme a odešleme dětská měření složení těla:
                val childUid = latestSession.childUserId
                if (childUid != null && sl.settings.childRecipientIdentity.isNotBlank()) {
                    runCatching {
                        val fromSec = (nowMs / 1000L) - 60L * 86_400L
                        val childGroups = webClient.getMeasures(
                            cookies = latestSession.cookies,
                            userId = childUid,
                            startDateSec = fromSec,
                            endDateSec = nowMs / 1000L,
                            sessionToken = latestSession.sessionToken
                        )
                        val childMapped = WithingsWebMapper.map(childGroups, childUid, sl.settings.zoneId)
                        val childBodyObs = childMapped.items.filter { it.type in BODY_COMPOSITION_TYPES }
                        val sent = forwardToChild(childBodyObs, sl.settings.childRecipientIdentity, nowMs)
                        if (sent > 0) {
                            runCatching { sl.transport.pump(nowMs, 2500L) }
                        }
                    }
                }
            }
            is AreaResult.Failed -> {
                if (result.error is ProviderError.Unauthorized) {
                    if (renewSession()) {
                        return runWebImport(nowMs)
                    }
                    _state.value = WithingsUiState.Failed("Relace Withings vyžaduje obnovení")
                }
            }
            is AreaResult.NeedsUser -> {
                if (renewSession()) {
                    return runWebImport(nowMs)
                }
                _state.value = WithingsUiState.Failed("Relace Withings vyžaduje obnovení")
            }
            else -> Unit
        }
        return result
    }

    /** Hlavní import váhy a složení těla: upřednostňuje přímé webové přihlášení, jako zálohu používá OAuth. */
    fun runImport(nowMs: Long): AreaResult? {
        if (!sl.settings.withingsDirectEnabled) return null
        if (loadWebSession()?.isConnected() == true) {
            return runWebImport(nowMs)
        }
        if (!session.isConnected()) return null
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
    fun trend(type: MetricType, days: Int = 365, includeSuspect: Boolean = false): List<app.pulse4.domain.MetricObservation> {
        val today = LocalDate.now(ZoneId.of(sl.settings.zoneId))
        val activeWithingsUid = loadWebSession()?.primaryUserId?.takeIf { it > 0 }?.toString()
        val allRows = sl.db.observations()
            .ofTypesIncludingDeleted(listOf(type.name), today.minusDays(days.toLong()).toString(), today.toString())
            .map { it.toDomain() }
            .filter { obs ->
                obs.provider != Provider.WITHINGS_API ||
                    activeWithingsUid == null ||
                    obs.accountId == activeWithingsUid ||
                    obs.accountId == "withings-csv"
            }
        val hasRealWeights = type != MetricType.WEIGHT || isConnected() || allRows.any { !it.deleted && !it.id.startsWith("garmin-hist-weight-") }
        val rawRows = if (type == MetricType.WEIGHT && hasRealWeights) {
            allRows.filterNot { it.id.startsWith("garmin-hist-weight-") }
        } else {
            allRows
        }
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
        val effectiveIncludeSuspect = includeSuspect || type != MetricType.WEIGHT
        return TrendSelector.points(rows, pref, effectiveIncludeSuspect).sortedBy { it.startUtcMs }
    }

    /** Vrací poslední známou hodnotu pro každou metriku složení těla a ECG z Withings (za zadaný počet dní). */
    fun latestBodyComposition(days: Int = 730): Map<MetricType, app.pulse4.domain.MetricObservation> {
        val result = linkedMapOf<MetricType, app.pulse4.domain.MetricObservation>()
        val activeWithingsUid = loadWebSession()?.primaryUserId?.takeIf { it > 0 }?.toString()
        val filterEnabled = sl.settings.weightFilterEnabled
        val minKg = sl.settings.weightFilterMinKg
        val maxKg = sl.settings.weightFilterMaxKg
        val pref = sl.settings.weightPreference.mapNotNull { runCatching { Provider.valueOf(it) }.getOrNull() }

        for (type in BODY_COMPOSITION_TYPES) {
            val candidates = sl.db.observations().latestRows(type.name, 10).map { it.toDomain() }
                .filter { !it.deleted && (it.provider != Provider.WITHINGS_API || activeWithingsUid == null || it.accountId == activeWithingsUid) }
            val valid = candidates.filter { obs ->
                if (type == MetricType.WEIGHT && filterEnabled && (minKg != null || maxKg != null)) {
                    val inRange = obs.value in (minKg ?: Double.MIN_VALUE)..(maxKg ?: Double.MAX_VALUE)
                    inRange && obs.quality != app.pulse4.domain.Quality.SUSPECT
                } else {
                    type != MetricType.WEIGHT || obs.quality != app.pulse4.domain.Quality.SUSPECT
                }
            }
            if (valid.isNotEmpty()) {
                val best = if (pref.isNotEmpty()) {
                    valid.sortedWith(compareByDescending<app.pulse4.domain.MetricObservation> { pref.indexOf(it.provider).let { idx -> if (idx >= 0) 100 - idx else 0 } }
                        .thenByDescending { it.startUtcMs }).first()
                } else {
                    valid.maxByOrNull { it.startUtcMs }
                }
                if (best != null) {
                    result[type] = best
                    continue
                }
            }

            val pts = trend(type, days = days, includeSuspect = type != MetricType.WEIGHT)
            if (pts.isNotEmpty()) {
                result[type] = pts.last()
            } else {
                sl.db.observations().latest(type.name)?.toDomain()?.takeIf { obs ->
                    !obs.deleted && (obs.provider != Provider.WITHINGS_API || activeWithingsUid == null || obs.accountId == activeWithingsUid)
                }?.let {
                    result[type] = it
                }
            }
        }
        return result
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

        // 1. Pokud má rodič připojený Web Connector s vybraným dětským profilem:
        val webSession = loadWebSession()
        val webChildId = webSession?.childUserId
        if (webChildId != null && webSession.isConnected()) {
            runCatching {
                val fromSec = (nowMs - 30L * 86_400_000L) / 1000L
                val childGroups = webClient.getMeasures(
                    cookies = webSession.cookies,
                    userId = webChildId,
                    startDateSec = fromSec,
                    sessionToken = webSession.sessionToken
                )
                val mapped = WithingsWebMapper.map(
                    groups = childGroups,
                    userId = webChildId,
                    defaultZone = sl.settings.zoneId
                )
                val childBodyObs = mapped.items.filter { it.type in BODY_COMPOSITION_TYPES }
                toForward += childBodyObs.map { it.copy(quality = app.pulse4.domain.Quality.OK) }
            }
        }

        // 2. Pokud má rodič připojený OAuth token přímo pro dětský profil ve Withingsu (`childVault`):
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

        // 3. Pokud je zapnuté automatické přeposílání dětského váhového pásma z váhy rodiče (např. 12–45 kg):
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
        const val WEB_SESSION_KEY = "withings.web.session"
        const val FAMILY_WEIGHT_PUSH_KIND = "family.weight.push"

        val BODY_COMPOSITION_TYPES = listOf(
            MetricType.WEIGHT,
            MetricType.BODY_FAT,
            MetricType.MUSCLE_MASS,
            MetricType.FAT_FREE_MASS,
            MetricType.BODY_WATER,
            MetricType.BONE_MASS,
            MetricType.VISCERAL_FAT,
            MetricType.VASCULAR_AGE,
            MetricType.PULSE_WAVE_VELOCITY,
            MetricType.BMR,
            MetricType.METABOLIC_AGE,
            MetricType.ECG,
        )
    }
}
