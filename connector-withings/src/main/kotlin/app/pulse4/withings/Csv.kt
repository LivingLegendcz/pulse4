package app.pulse4.withings

import app.pulse4.domain.ImportKey
import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.domain.TimeRules
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class CsvRowError(val line: Int, val reason: String)

class CsvImportResult(val items: List<MetricObservation>, val errors: List<CsvRowError>)

/**
 * Nouzová a referenční cesta: CSV export z aplikace Withings. Soubor je nedůvěryhodný vstup (limity velikosti a počtu
 * řádků), jednotky se převádějí na kg a %, nečitelné řádky se hlásí s číslem řádku, ne potichu zahazují. Záznamy mají
 * poskytovatele MANUAL (původ `withings-csv`), aby se správně sloučily s tímtéž vážením z API nebo Health Connect.
 */
object WithingsCsv {
    const val MAX_BYTES = 20 * 1024 * 1024
    const val MAX_ROWS = 200_000
    private const val LB = 0.45359237

    private val formats = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"), DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"),
        DateTimeFormatter.ofPattern("d/M/yyyy HH:mm:ss"),
    )

    fun parse(bytes: ByteArray, zoneId: String, accountId: String = "withings-csv"): CsvImportResult {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return CsvImportResult(emptyList(), listOf(CsvRowError(0, "Velikost souboru mimo limit")))
        val text = bytes.toString(Charsets.UTF_8).removePrefix("﻿")
        val rows = readRows(text)
        if (rows.size < 2) return CsvImportResult(emptyList(), listOf(CsvRowError(1, "Soubor neobsahuje data")))
        if (rows.size > MAX_ROWS) return CsvImportResult(emptyList(), listOf(CsvRowError(0, "Příliš mnoho řádků")))

        val header = rows[0].map { it.trim().lowercase() }
        val dateCol = header.indexOfFirst { it == "date" || it.startsWith("date") }
        val weightCol = header.indexOfFirst { it.startsWith("weight") }
        val fatMassCol = header.indexOfFirst { it.startsWith("fat mass") }
        val fatPctCol = header.indexOfFirst { it.startsWith("fat") && ("%" in it || "ratio" in it) }
        if (dateCol < 0 || weightCol < 0) return CsvImportResult(emptyList(), listOf(CsvRowError(1, "Chybí sloupec Date nebo Weight")))
        val weightToKg = unitFactor(header[weightCol]) ?: return CsvImportResult(emptyList(), listOf(CsvRowError(1, "Neznámá jednotka váhy")))
        val fatToKg = if (fatMassCol >= 0) unitFactor(header[fatMassCol]) else null

        val zone = ZoneId.of(zoneId)
        val items = ArrayList<MetricObservation>()
        val errors = ArrayList<CsvRowError>()
        for (i in 1 until rows.size) {
            val r = rows[i]
            if (r.all { it.isBlank() }) continue
            val line = i + 1
            val ts = parseDate(r.getOrNull(dateCol), zone)
            val w = r.getOrNull(weightCol)?.let(::num)?.times(weightToKg)
            if (ts == null) { errors += CsvRowError(line, "Nečitelné datum"); continue }
            if (w == null || w !in 10.0..500.0) { errors += CsvRowError(line, "Váha chybí nebo je mimo rozsah"); continue }
            items += obs(MetricType.WEIGHT, w, ts, zoneId, accountId)
            val pct = when {
                fatPctCol >= 0 -> r.getOrNull(fatPctCol)?.let(::num)
                fatMassCol >= 0 && fatToKg != null -> r.getOrNull(fatMassCol)?.let(::num)?.let { it * fatToKg / w * 100.0 }
                else -> null
            }
            if (pct != null && pct in 1.0..80.0) items += obs(MetricType.BODY_FAT, pct, ts, zoneId, accountId)
        }
        return CsvImportResult(items, errors)
    }

    private fun obs(type: MetricType, v: Double, ts: Long, zone: String, acc: String) = MetricObservation(
        id = ImportKey.of(Provider.MANUAL, acc, null, listOf("withings-csv", type.name, ts.toString())),
        provider = Provider.MANUAL, accountId = acc, externalId = null, type = type, startUtcMs = ts, value = v,
        zoneId = zone, localDate = TimeRules.localDate(ts, zone).toString(), origin = "withings-csv",
    )

    private fun unitFactor(h: String) = when {
        "(kg)" in h -> 1.0
        "(lb)" in h || "(lbs)" in h -> LB
        else -> null
    }

    private fun num(s: String): Double? = s.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun parseDate(s: String?, zone: ZoneId): Long? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (f in formats) try { return LocalDateTime.parse(t, f).atZone(zone).toInstant().toEpochMilli() } catch (_: DateTimeParseException) {}
        return try { OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (_: DateTimeParseException) { null }
    }

    /** RFC 4180: uvozovky, zdvojené uvozovky, čárka nebo středník jako oddělovač podle hlavičky. */
    internal fun readRows(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delim = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>(); val cell = StringBuilder(); var q = false; var i = 0
        fun endCell() { row.add(cell.toString()); cell.setLength(0) }
        fun endRow() { endCell(); rows.add(row); row = ArrayList() }
        while (i < text.length) {
            val c = text[i]
            when {
                q && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> q = !q
                !q && c == delim -> endCell()
                !q && c == '\n' -> endRow()
                !q && c == '\r' -> Unit
                else -> cell.append(c)
            }
            i++
            if (rows.size > MAX_ROWS + 1) break
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
