package app.pulse4.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object TimeRules {
    /** Místní den ve vlastním časovém pásmu měření (správné i přes změnu času a při cestování). */
    fun localDate(utcMs: Long, zoneId: String): LocalDate =
        Instant.ofEpochMilli(utcMs).atZone(ZoneId.of(zoneId)).toLocalDate()

    /**
     * Den spánku: přiřazení od poskytovatele má přednost; jinak den probuzení v pásmu měření.
     */
    fun sleepDay(providerDay: String?, endUtcMs: Long, zoneId: String): LocalDate =
        providerDay?.let(LocalDate::parse) ?: localDate(endUtcMs, zoneId)

    /** Skutečná délka dne (23/24/25 h) kvůli plánu a přepočtům při změně času. */
    fun dayLengthHours(date: LocalDate, zoneId: String): Long {
        val z = ZoneId.of(zoneId)
        val s = date.atStartOfDay(z).toInstant()
        val e = date.plusDays(1).atStartOfDay(z).toInstant()
        return java.time.Duration.between(s, e).toHours()
    }
}
