package app.pulse4.android.data

import app.pulse4.sharing.PrivacyZone
import app.pulse4.sync.Wire
import app.pulse4.ui.UnitSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.ConcurrentHashMap

enum class NetworkMode { AUTO, LOCAL_ONLY, MANUAL }

enum class MapProvider(val title: String, val shortLabel: String, val subtitle: String, val attribution: String) {
    CARTO_DARK(
        title = "Dark Canvas (Tmavá sportovní)",
        shortLabel = "Tmavá",
        subtitle = "Kontrastní tmavý mapový podklad ideální pro čitelnost GPS trasy",
        attribution = "Tiles © Esri, HERE, Garmin, OpenStreetMap contributors"
    ),
    OSM_STANDARD(
        title = "OpenStreetMap (Klasická)",
        shortLabel = "OSM",
        subtitle = "Podrobná celosvětová mapa ulic, stezek a bodů zájmu",
        attribution = "© OpenStreetMap contributors"
    ),
    OPENTOPOMAP(
        title = "OpenTopoMap (Turistická & vrstevnice)",
        shortLabel = "Topo",
        subtitle = "Topografická mapa s vrstevnicemi, výškopisem a turistickými cestami",
        attribution = "© OpenStreetMap contributors, SRTM | © OpenTopoMap"
    ),
    CARTO_VOYAGER(
        title = "CyclOSM (Sportovní & Cyklo/Běh)",
        shortLabel = "CyclOSM",
        subtitle = "Sportovní mapa se zvýrazněním stezek, cyklotras a terénu",
        attribution = "© OpenStreetMap contributors, CyclOSM"
    ),
    ESRI_SATELLITE(
        title = "Esri World Imagery (Satelitní)",
        shortLabel = "Satelit",
        subtitle = "Letecké a satelitní snímky povrchu",
        attribution = "Tiles © Esri — Source: Esri, Maxar, Earthstar Geographics"
    ),
    CUSTOM_URL(
        title = "Vlastní MapLibre URL",
        shortLabel = "Vlastní",
        subtitle = "Vlastní JSON styl mapy zadaný v poli URL níže",
        attribution = "Vlastní zdroj dlaždic"
    ),
    OFFLINE_CANVAS(
        title = "Bez mapového podkladu (Offline plátno)",
        shortLabel = "Offline",
        subtitle = "Pouze čistá vektorová křivka trasy bez stahování dlaždic ze sítě",
        attribution = "Offline vektorové vykreslení"
    )
}

/**
 * Nastavení v databázi (bez tajemství). Výchozí gateway je přednastavena, ale host i port jsou změnitelné;
 * aplikace nesmí záviset na jediném uzlu. Propagation uzel je samostatná služba se svou destination – transport ID
 * ani discovery hash gateway se jako adresa schránky nikdy nepoužijí.
 */
class Settings(private val db: AppDatabase) {
    private val cache = ConcurrentHashMap<String, String>()
    private val missingKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var warmedUp = false

    private fun getRaw(k: String): String? {
        cache[k]?.let { return it }
        if (warmedUp || missingKeys.contains(k)) return null
        val v = db.settings().get(k)
        if (v != null) {
            cache[k] = v
        } else {
            missingKeys.add(k)
        }
        return v
    }

    private fun get(k: String, d: String) = getRaw(k) ?: d
    private fun put(k: String, v: String) {
        cache[k] = v
        missingKeys.remove(k)
        db.settings().put(SettingRow(k, v))
    }

    suspend fun warmUp() {
        withContext(Dispatchers.IO) {
            val rows = db.settings().all().first()
            rows.forEach { row ->
                cache[row.key] = row.value
                missingKeys.remove(row.key)
            }
            warmedUp = true
            refreshFlowsFromCache()
        }
    }

    private fun refreshFlowsFromCache() {
        _unitSystemFlow.value = runCatching { UnitSystem.valueOf(get("unitSystem", UnitSystem.METRIC.name)) }.getOrDefault(UnitSystem.METRIC)
        _mapProviderFlow.value = runCatching { MapProvider.valueOf(get("mapProvider", MapProvider.CARTO_DARK.name)) }.getOrDefault(MapProvider.CARTO_DARK)
        _networkModeFlow.value = runCatching { NetworkMode.valueOf(get("networkMode", NetworkMode.AUTO.name)) }.getOrDefault(NetworkMode.AUTO)
        _backgroundServiceEnabledFlow.value = get("backgroundServiceEnabled", "true").toBoolean()
        val ageProfile = get("ownAgeProfile", "ADULT").let { if (it == "MINOR") "MINOR" else "ADULT" }
        _ownAgeProfileFlow.value = ageProfile
        _isMinorFlow.value = (ageProfile == "MINOR")
        _zoneIdFlow.value = get("zoneId", java.time.ZoneId.systemDefault().id)
    }

    private val _unitSystemFlow = MutableStateFlow(UnitSystem.METRIC)
    val unitSystemFlow: StateFlow<UnitSystem> get() = _unitSystemFlow

    private val _mapProviderFlow = MutableStateFlow(MapProvider.CARTO_DARK)
    val mapProviderFlow: StateFlow<MapProvider> get() = _mapProviderFlow

    private val _networkModeFlow = MutableStateFlow(NetworkMode.AUTO)
    val networkModeFlow: StateFlow<NetworkMode> get() = _networkModeFlow

    private val _backgroundServiceEnabledFlow = MutableStateFlow(true)
    val backgroundServiceEnabledFlow: StateFlow<Boolean> get() = _backgroundServiceEnabledFlow

    private val _ownAgeProfileFlow = MutableStateFlow("ADULT")
    val ownAgeProfileFlow: StateFlow<String> get() = _ownAgeProfileFlow

    private val _isMinorFlow = MutableStateFlow(false)
    val isMinorFlow: StateFlow<Boolean> get() = _isMinorFlow

    private val _zoneIdFlow = MutableStateFlow(java.time.ZoneId.systemDefault().id)
    val zoneIdFlow: StateFlow<String> get() = _zoneIdFlow

    var unitSystem: UnitSystem
        get() = runCatching { UnitSystem.valueOf(get("unitSystem", UnitSystem.METRIC.name)) }.getOrDefault(UnitSystem.METRIC).also { _unitSystemFlow.value = it }
        set(v) {
            put("unitSystem", v.name)
            _unitSystemFlow.value = v
        }

    var mapProvider: MapProvider
        get() = runCatching { MapProvider.valueOf(get("mapProvider", MapProvider.CARTO_DARK.name)) }.getOrDefault(MapProvider.CARTO_DARK).also { _mapProviderFlow.value = it }
        set(v) {
            put("mapProvider", v.name)
            _mapProviderFlow.value = v
        }

    var displayName: String get() = get("displayName", "Já"); set(v) = put("displayName", v)
    var accountId: String get() = get("accountId", "garmin-main"); set(v) = put("accountId", v)
    var zoneId: String
        get() = get("zoneId", java.time.ZoneId.systemDefault().id).also { _zoneIdFlow.value = it }
        set(v) {
            put("zoneId", v)
            _zoneIdFlow.value = v
        }

    var networkMode: NetworkMode
        get() = runCatching { NetworkMode.valueOf(get("networkMode", NetworkMode.AUTO.name)) }.getOrDefault(NetworkMode.AUTO).also { _networkModeFlow.value = it }
        set(v) {
            put("networkMode", v.name)
            _networkModeFlow.value = v
        }
    /** Trvalý běh služby na pozadí (po startu telefonu) pro okamžitý příjem zpráv a synchronizaci dat. */
    var backgroundServiceEnabled: Boolean
        get() = get("backgroundServiceEnabled", "true").toBoolean().also { _backgroundServiceEnabledFlow.value = it }
        set(v) {
            put("backgroundServiceEnabled", v.toString())
            _backgroundServiceEnabledFlow.value = v
        }
    var gatewayHost: String get() = get("gatewayHost", ""); set(v) = put("gatewayHost", v)
    var gatewayPort: Int get() = get("gatewayPort", "4242").toInt(); set(v) = put("gatewayPort", v.toString())
    /** Uložený seznam P2P uzlů (ve tvaru host:port), které lze v nastavení přepínat, přidávat i odebírat. */
    var savedGatewayNodes: List<String>
        get() = get("savedGatewayNodes", "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        set(v) = put("savedGatewayNodes", v.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(","))
    var rnsOnboardingCompleted: Boolean
        get() {
            val saved = getRaw("rnsOnboardingCompleted")
            if (saved != null) return saved.toBoolean()
            val existingHost = getRaw("gatewayHost")
            return !existingHost.isNullOrBlank()
        }
        set(v) = put("rnsOnboardingCompleted", v.toString())
    var rnsAdvancedMode: Boolean get() = get("rnsAdvancedMode", "false").toBoolean(); set(v) = put("rnsAdvancedMode", v.toString())
    var ownAgeProfile: String
        get() = get("ownAgeProfile", "ADULT").let { if (it == "MINOR") "MINOR" else "ADULT" }.also {
            _ownAgeProfileFlow.value = it
            _isMinorFlow.value = (it == "MINOR")
        }
        set(v) {
            val normalized = if (v == "MINOR") "MINOR" else "ADULT"
            put("ownAgeProfile", normalized)
            _ownAgeProfileFlow.value = normalized
            _isMinorFlow.value = (normalized == "MINOR")
        }
    /** Hash LXMF propagation destination; prázdné = bez schránky (jen přímá výměna). */
    var propagationNode: String get() = get("propagationNode", ""); set(v) = put("propagationNode", v.trim().lowercase())

    /** Prázdné = bez online map (jen trasa na plátně). Zdroj dlaždic a jeho licence/atribuce je volba uživatele. */
    var mapStyleUrl: String get() = get("mapStyleUrl", ""); set(v) = put("mapStyleUrl", v.trim())

    var privacyZones: List<PrivacyZone>
        get() = runCatching { Wire.json.decodeFromString(ListSerializer(PrivacyZone.serializer()), get("privacyZones", "[]")) }.getOrDefault(emptyList())
        set(v) = put("privacyZones", Wire.json.encodeToString(ListSerializer(PrivacyZone.serializer()), v))

    // --- Withings ---
    var withingsDirectEnabled: Boolean get() = get("withingsDirect", "true").toBoolean(); set(v) = put("withingsDirect", v.toString())
    var withingsClientId: String get() = get("withingsClientId", ""); set(v) = put("withingsClientId", v.trim())
    /** Pojmenování aktivního profilu ve Withings (např. "Hlavní profil (Já)" nebo "Dětský profil – Honzík"). */
    var withingsProfileLabel: String get() = get("withingsProfileLabel", "Hlavní profil (Já)"); set(v) = put("withingsProfileLabel", v.trim())
    /** Ochranný filtr váhy proti nechtěnému započítání vážení dítěte nebo jiného člena domácnosti na společné váze. */
    var weightFilterEnabled: Boolean get() = get("weightFilterEnabled", "true").toBoolean(); set(v) = put("weightFilterEnabled", v.toString())
    var weightFilterMinKg: Double get() = get("weightFilterMinKg", "45.0").toDoubleOrNull() ?: 45.0; set(v) = put("weightFilterMinKg", v.toString())
    var weightFilterMaxKg: Double get() = get("weightFilterMaxKg", "130.0").toDoubleOrNull() ?: 130.0; set(v) = put("weightFilterMaxKg", v.toString())

    // --- Přeposílání dětské váhy z Withingsu rodiče přímo do telefonu dítěte přes P2P ---
    /** Identita (deliveryHash) spárovaného kontaktu dítěte, kterému rodič přeposílá dětskou váhu z Withingsu. */
    var childRecipientIdentity: String get() = get("childRecipientIdentity", ""); set(v) = put("childRecipientIdentity", v.trim())
    /** Automaticky přeposílat vážení z připojeného dětského profilu ve Withings (nebo z dětského váhového pásma) dítěti přes P2P. */
    var childWeightAutoForward: Boolean get() = get("childWeightAutoForward", "true").toBoolean(); set(v) = put("childWeightAutoForward", v.toString())
    var childWeightMinKg: Double get() = get("childWeightMinKg", "12.0").toDoubleOrNull() ?: 12.0; set(v) = put("childWeightMinKg", v.toString())
    var childWeightMaxKg: Double get() = get("childWeightMaxKg", "45.0").toDoubleOrNull() ?: 45.0; set(v) = put("childWeightMaxKg", v.toString())
    /** Na telefonu dítěte: přijímat vlastní váhu přeposlanou z účtu Withings rodiče přímo do mých osobních metrik. */
    var acceptWeightFromParent: Boolean get() = get("acceptWeightFromParent", "true").toBoolean(); set(v) = put("acceptWeightFromParent", v.toString())

    var healthConnectEnabled: Boolean get() = get("healthConnect", "false").toBoolean(); set(v) = put("healthConnect", v.toString())
    /** Balíčky aplikací, jejichž záznamy z Health Connect přijímáme (výchozí: aplikace Withings). Prázdné = všechny. */
    var healthConnectOrigins: Set<String> get() = get("hcOrigins", "com.withings.wiscale2").split(',').filter { it.isNotBlank() }.toSet(); set(v) = put("hcOrigins", v.joinToString(","))
    var healthConnectToken: String? get() = getRaw("hcToken"); set(v) = put("hcToken", v ?: "")
    /** Pořadí zdrojů pro trend váhy (první vyhrává u duplicit). */
    var weightPreference: List<String> get() = get("weightPref", "WITHINGS_API,MANUAL,GARMIN_PERSONAL").split(','); set(v) = put("weightPref", v.joinToString(","))

    /** Uživatelem definované pořadí widgetů na obrazovce Dnes. */
    var todayWidgetOrder: List<String>
        get() = get("todayWidgetOrder", "sleep,bb,hrv,rhr,stress,steps,weight,ecg")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        set(v) = put("todayWidgetOrder", v.joinToString(","))

    /** Nastavení běží na pozadí v pracovních vláknech; volejte mimo hlavní vlákno. */
    companion object { const val GATEWAY_DEFAULT_NAME = "Jihlava WoD Gateway – CZE" }
}
