package app.pulse4.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DomainTest {
    private fun obs(
        id: String, p: Provider, t: Long, v: Double, type: MetricType = MetricType.WEIGHT, acc: String = "a",
    ) = MetricObservation(id, p, acc, id, type, t, null, v, "Europe/Prague")

    @Test fun `import key is deterministic and prefers external id`() {
        val a = ImportKey.of(Provider.GARMIN_PERSONAL, "acc", null, listOf("1", "2"))
        val b = ImportKey.of(Provider.GARMIN_PERSONAL, "acc", null, listOf("1", "2"))
        val c = ImportKey.of(Provider.GARMIN_PERSONAL, "acc", null, listOf("1", "3"))
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals("GARMIN_PERSONAL|acc|id:77", ImportKey.of(Provider.GARMIN_PERSONAL, "acc", "77", listOf("x")))
    }

    @Test fun `same weight from Withings and Garmin is one trend point and both sources are kept`() {
        val w = obs("w", Provider.HEALTH_CONNECT, 1_000_000, 80.1)
        val g = obs("g", Provider.GARMIN_PERSONAL, 1_000_000 + 120_000, 80.1)
        val clusters = DuplicateLinker.cluster(listOf(w, g))
        assertEquals(1, clusters.size)
        assertEquals(2, clusters[0].members.size)
        val trend = DuplicateLinker.selectForTrend(clusters, listOf(Provider.HEALTH_CONNECT, Provider.GARMIN_PERSONAL))
        assertEquals(listOf("w"), trend.map { it.id })
    }

    @Test fun `ambiguous or same-source measurements are not merged`() {
        val a = obs("a", Provider.GARMIN_PERSONAL, 0, 80.0)
        val b = obs("b", Provider.GARMIN_PERSONAL, 60_000, 80.0) // stejný zdroj = dvě vážení
        val c = obs("c", Provider.HEALTH_CONNECT, 60_000, 83.0) // jiná hodnota = jiná osoba/vážení
        assertEquals(3, DuplicateLinker.cluster(listOf(a, b, c)).size)
    }

    @Test fun `deleted observations never enter a cluster`() {
        val a = obs("a", Provider.GARMIN_PERSONAL, 0, 80.0).copy(deleted = true)
        assertEquals(0, DuplicateLinker.cluster(listOf(a)).size)
    }

    @Test fun `non finite values and inverted intervals are rejected`() {
        assertFailsWith<IllegalArgumentException> { obs("x", Provider.MANUAL, 0, Double.NaN) }
        assertFailsWith<IllegalArgumentException> {
            MetricObservation("x", Provider.MANUAL, "a", null, MetricType.HRV, 10, 5, 1.0, "UTC")
        }
    }

    @Test fun `dst days have 23 and 25 hours and local date follows zone`() {
        val z = "Europe/Prague"
        assertEquals(23, TimeRules.dayLengthHours(java.time.LocalDate.of(2026, 3, 29), z))
        assertEquals(25, TimeRules.dayLengthHours(java.time.LocalDate.of(2026, 10, 25), z))
        // 23:30 UTC 28. 3. je v Praze už 29. 3. 00:30 (SEČ)
        val utc = java.time.Instant.parse("2026-03-28T23:30:00Z").toEpochMilli()
        assertEquals(java.time.LocalDate.of(2026, 3, 29), TimeRules.localDate(utc, z))
        assertEquals(java.time.LocalDate.of(2026, 3, 28), TimeRules.localDate(utc, "America/New_York"))
    }

    @Test fun `sleep day prefers provider assignment`() {
        val end = java.time.Instant.parse("2026-10-24T22:30:00Z").toEpochMilli()
        assertEquals(java.time.LocalDate.of(2026, 10, 24), TimeRules.sleepDay("2026-10-24", end, "Europe/Prague"))
        assertEquals(java.time.LocalDate.of(2026, 10, 25), TimeRules.sleepDay(null, end, "Europe/Prague"))
    }
}
