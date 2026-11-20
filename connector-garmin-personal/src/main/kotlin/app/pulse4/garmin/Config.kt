package app.pulse4.garmin

import app.pulse4.domain.MetricType
import app.pulse4.provider.SyncArea
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Specifikace jedné metriky: endpoint, cesta v JSON, jednotka a rozlišení. Podle zadání ji musí každá
 * metrika mít spolu s anonymizovanou vzorovou odpovědí; položka s `verified = false` se nikdy nevolá.
 */
@Serializable
data class EndpointSpec(
    val area: SyncArea,
    val metric: MetricType,
    /** Cesta za `baseUrl`; `{from}` a `{to}` se nahradí daty ve formátu [dateFormat]. */
    val path: String,
    val dateFormat: String = "yyyy-MM-dd",
    /** Nejdelší povolený rozsah jednoho požadavku ve dnech (rozsahy se dělí podle specifikace endpointu). */
    val maxRangeDays: Int = 1,
    /** Cesta k poli položek v odpovědi; prázdná = kořen je pole, `"$"` = kořen je jediná položka. */
    val itemsPath: String = "",
    val timePath: String,
    val endTimePath: String? = null,
    val valuePath: String,
    val timeFormat: TimeFormat = TimeFormat.EPOCH_S,
    /** Násobek pro převod do vnitřní jednotky ([MetricType.unit]). */
    val scale: Double = 1.0,
    val zonePath: String? = null,
    val localDatePath: String? = null,
    val externalIdPath: String? = null,
    val verified: Boolean = false,
    /** Odkaz na anonymizovaný vzor odpovědi v dokumentaci konektoru. */
    val sampleRef: String? = null,
    /**
     * Záložní specifikace téže metriky: volá se jen tehdy, když primární (nezáložní) položky metriky selžou
     * nebo nevrátí za celé okno žádné mapovatelné záznamy (např. 404/204/prázdné tělo).
     */
    val fallback: Boolean = false,
) {
    @Serializable enum class TimeFormat { EPOCH_S, EPOCH_MS, ISO_INSTANT }
}

@Serializable
data class TokenSpec(
    val url: String,
    val method: String = "POST",
    /** Hodnoty formuláře; `{apiKey}` se nahradí osobním klíčem uživatele. */
    val form: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val accessTokenPath: String = "access_token",
    val expiresInPath: String? = "expires_in",
    val verified: Boolean = false,
)

@Serializable
data class GarminEndpointsConfig(
    val baseUrl: String,
    val token: TokenSpec,
    val endpoints: List<EndpointSpec>,
    /** Hlavička přiložená k datovým požadavkům; `{accessToken}` se nahradí aktuálním tokenem. */
    val authHeader: String = "Authorization",
    val authValue: String = "Bearer {accessToken}",
) {
    companion object {
        val json = Json { ignoreUnknownKeys = false }
        fun parse(text: String): GarminEndpointsConfig = json.decodeFromString(serializer(), text)
    }
}
