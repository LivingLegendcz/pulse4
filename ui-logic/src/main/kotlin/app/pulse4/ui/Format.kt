package app.pulse4.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class UnitSystem { METRIC, IMPERIAL }

/** Formátování pro české UI. Pásmo, jazyk a soustava jednotek jsou parametry, aby bylo chování deterministické a testovatelné. */
class Fmt(
    zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.forLanguageTag("cs-CZ"),
    val units: UnitSystem = UnitSystem.METRIC,
) {
    private val dt = DateTimeFormatter.ofPattern("d. M. HH:mm").withZone(zone)

    val isImperial: Boolean get() = units == UnitSystem.IMPERIAL

    fun time(ms: Long?): String = ms?.let { dt.format(Instant.ofEpochMilli(it)) } ?: "—"

    fun duration(s: Double): String { val t = s.toLong().coerceAtLeast(0); return String.format(locale, "%d:%02d:%02d", t / 3600, t % 3600 / 60, t % 60) }

    fun sleep(s: Double): String { val m = (s / 60).toLong().coerceAtLeast(0); return "${m / 60} h ${m % 60} min" }

    fun pace(secPerKm: Double?): String =
        secPerKm?.takeIf { it.isFinite() && it > 0 }?.let {
            if (isImperial) {
                val secPerMi = kotlin.math.round(it * 1.609344).toInt()
                String.format(locale, "%d:%02d /mi", secPerMi / 60, secPerMi % 60)
            } else {
                val sec = kotlin.math.round(it).toInt()
                String.format(locale, "%d:%02d /km", sec / 60, sec % 60)
            }
        } ?: "—"

    fun km(m: Double?): String = m?.takeIf { it.isFinite() && it >= 0 }?.let {
        if (isImperial) String.format(locale, "%.2f mi", it / 1609.344)
        else String.format(locale, "%.2f km", it / 1000)
    } ?: "—"

    fun elevation(m: Double?): String = m?.takeIf { it.isFinite() }?.let {
        if (isImperial) "${number(it * 3.28084, 0)} ft"
        else "${number(it, 0)} m"
    } ?: "—"

    fun weight(kg: Double?, decimals: Int = 2): String = kg?.takeIf { it.isFinite() && it > 0 }?.let {
        if (isImperial) "${number(it * 2.20462, decimals)} lb"
        else "${number(it, decimals)} kg"
    } ?: "—"

    fun weightUnit(): String = if (isImperial) "lb" else "kg"
    fun distanceUnit(): String = if (isImperial) "mi" else "km"
    fun elevationUnit(): String = if (isImperial) "ft" else "m"

    fun convertWeight(kg: Double): Double = if (isImperial) kg * 2.20462 else kg
    fun convertElevation(m: Double): Double = if (isImperial) m * 3.28084 else m
    fun convertDistanceKm(m: Double): Double = if (isImperial) m / 1609.344 else m / 1000.0

    fun number(v: Double, decimals: Int = 0): String = String.format(locale, "%.${decimals}f", v)

    /** „Před 5 min“ – stáří dat bez hrubého zaokrouhlování na „dnes“. */
    fun age(nowMs: Long, thenMs: Long?): String {
        if (thenMs == null) return "—"
        val d = (nowMs - thenMs) / 1000
        return when {
            d < 0 -> "v budoucnu (špatné hodiny?)"
            d < 60 -> "právě teď"
            d < 3600 -> "před ${d / 60} min"
            d < 86_400 -> "před ${d / 3600} h"
            else -> "před ${d / 86_400} d"
        }
    }

    companion object { val default = Fmt() }
}
