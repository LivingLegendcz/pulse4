package app.pulse4.sharing

import app.pulse4.domain.MetricObservation
import app.pulse4.domain.Provider
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Vlastní data jednoho dne tak, jak je zná telefon vlastníka. Nikdy se neposílají přímo. */
data class OwnerDay(
    val date: LocalDate,
    val values: Map<Field, JsonElement>,
    val measuredAtUtcMs: Long? = null,
    val fieldTimestamps: Map<Field, Long> = emptyMap(),
)

/** Skutečně odeslaná omezená podoba dat. Je sestavena nově, ne zkopírována a „zakryta“. */
@Serializable
data class SharedProjection(
    val schema: Int = SCHEMA,
    val ownerId: String,
    val grantId: String,
    val date: String,
    val fields: Map<Field, JsonElement>,
    val measuredAtUtcMs: Long? = null,
    val producedAtMs: Long,
) {
    companion object { const val SCHEMA = 1 }
}

object Projector {
    val json = Json { encodeDefaults = true; explicitNulls = false }

    /**
     * Filtrace proběhne před šifrováním a odesláním. Do výsledku se dostanou jen pole, která
     * jsou zároveň v grantu (pozitivní seznam) a v datech. Bez shody vrací `null` – neodesílá se nic.
     */
    fun project(grant: ShareGrant, day: OwnerDay, nowMs: Long): SharedProjection? {
        if (!grant.active || !grant.scope.covers(day.date)) return null
        val allowed = buildMap {
            for (f in grant.fields) day.values[f]?.let { put(f, it) }
        }
        if (allowed.isEmpty()) return null
        val permittedMeasuredAt = if (day.fieldTimestamps.isNotEmpty()) {
            allowed.keys.mapNotNull { day.fieldTimestamps[it] }.maxOrNull()
        } else {
            day.measuredAtUtcMs
        }
        return SharedProjection(
            ownerId = grant.ownerId,
            grantId = grant.id,
            date = day.date.toString(),
            fields = allowed,
            measuredAtUtcMs = permittedMeasuredAt,
            producedAtMs = nowMs,
        )
    }

    fun project(
        grant: ShareGrant,
        date: LocalDate,
        observations: List<MetricObservation>,
        nowMs: Long,
        weightPreference: List<Provider> = listOf(Provider.WITHINGS_API, Provider.HEALTH_CONNECT, Provider.MANUAL, Provider.GARMIN_PERSONAL),
    ): SharedProjection? {
        if (!grant.active || !grant.scope.covers(date)) return null
        return project(grant, OwnerDayAssembler.assembleForGrant(date, observations, grant, weightPreference), nowMs)
    }

    /** Přesně tyto bajty uvidí uživatel v náhledu a přesně tyto odejdou do fronty. */
    fun encode(p: SharedProjection): ByteArray = json.encodeToString(p).toByteArray(Charsets.UTF_8)
}
