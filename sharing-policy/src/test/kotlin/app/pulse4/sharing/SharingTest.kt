package app.pulse4.sharing

import app.pulse4.domain.Activity
import app.pulse4.domain.Provider
import app.pulse4.domain.Sport
import app.pulse4.domain.TrackPoint
import java.time.LocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharingTest {
    private val day = LocalDate.of(2026, 10, 20)
    private fun grant(fields: Set<Field>, scope: TimeScope = TimeScope.from(LocalDate.of(2026, 10, 1)), recipients: Set<String> = setOf("bob")) =
        ShareGrant("g1", "josef", recipients, fields, scope = scope, createdAtMs = 1)

    private val ownerDay = OwnerDay(
        day,
        mapOf<Field, JsonElement>(
            Field.SLEEP_DURATION to JsonPrimitive(27_000),
            Field.SLEEP_SCORE to JsonPrimitive(81),
            Field.SLEEP_STAGES to buildJsonObject { put("deep", 5000); put("rem", 6000) },
            Field.HR_SERIES to JsonPrimitive("secret-series"),
            Field.BB_LAST to JsonPrimitive(42),
        ),
    )

    @Test fun `default is share nothing`() {
        assertNull(Projector.project(grant(emptySet()), ownerDay, 5))
    }

    @Test fun `contact allowed only sleep duration receives no stages heart rate or score`() {
        val p = assertNotNull(Projector.project(grant(Presets.sleepDurationOnly), ownerDay, 5))
        assertEquals(setOf(Field.SLEEP_DURATION), p.fields.keys)
        val wire = String(Projector.encode(p))
        listOf("SLEEP_STAGES", "deep", "HR_SERIES", "secret-series", "SLEEP_SCORE", "BB_LAST").forEach {
            assertFalse(wire.contains(it), "wire obsahuje $it: $wire")
        }
    }

    @Test fun `field absent from data is not invented`() {
        val p = assertNotNull(Projector.project(grant(setOf(Field.SLEEP_DURATION, Field.HRV_DAILY)), ownerDay, 5))
        assertEquals(setOf(Field.SLEEP_DURATION), p.fields.keys)
    }

    @Test fun `revoked grant and out of scope dates project nothing`() {
        assertNull(Projector.project(grant(Presets.sleepFull).copy(revokedAtMs = 3), ownerDay, 5))
        val old = grant(Presets.sleepFull, TimeScope.continuousFrom(LocalDate.of(2026, 10, 21)))
        assertNull(Projector.project(old, ownerDay, 5), "průběžné sdílení nesmí otevřít starou historii")
        val once = grant(Presets.sleepFull, TimeScope.once(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20)))
        assertNotNull(Projector.project(once, ownerDay, 5))
        assertNull(Projector.project(once, ownerDay.copy(date = LocalDate.of(2026, 10, 22)), 5))
    }

    @Test fun `preview bytes equal sent bytes`() {
        val p = assertNotNull(Projector.project(grant(Presets.sleepDurationAndScore), ownerDay, 9))
        assertTrue(Projector.encode(p).contentEquals(Projector.encode(p)))
    }

    @Test fun `adding a group member does not extend sharing`() {
        val g = grant(Presets.sleepDurationOnly, recipients = setOf("bob")).copy(groupId = "grp")
        val group = Group("grp", "Výpravy", "josef", "bob", setOf("josef", "bob", "eva"), 2)
        assertEquals(setOf("eva"), MembershipPolicy.awaitingConsent(g, group))
        assertEquals(setOf("bob"), MembershipPolicy.effectiveRecipients(g, group))
        // odebraný člen přestane dostávat data
        assertEquals(emptySet(), MembershipPolicy.effectiveRecipients(g, group.copy(members = setOf("josef", "eva"))))
        assertEquals(emptySet(), MembershipPolicy.effectiveRecipients(g.copy(revokedAtMs = 1), group))
    }

    // --- aktivity ---
    private val home = PrivacyZone(50.0, 14.0, 200.0)
    private val act = Activity("a1", Provider.GARMIN_PERSONAL, "acc", "1", Sport.RUNNING,
        java.time.Instant.parse("2026-10-20T05:07:33Z").toEpochMilli(), "Europe/Prague", 3000.0, 2900.0, 10000.0, 80.0, 150)
    private val track = listOf(
        TrackPoint(0, 50.0005, 14.0, heartRate = 100, speedMps = 3.0, elevationM = 200.0),   // v zóně
        TrackPoint(1000, 50.01, 14.0, heartRate = 150, speedMps = 3.2, elevationM = 210.0),
        TrackPoint(2000, null, null, heartRate = 151),                                          // bez GPS
        TrackPoint(3000, 50.0002, 14.0001, heartRate = 120, speedMps = 2.0, elevationM = 205.0), // v zóně
    )
    private val laps = listOf(LapIn(0, 1000, 1000.0, 50.0005, 14.0), LapIn(1000, 3000, 9000.0, 50.01, 14.0))

    @Test fun `route sanitization removes every coordinate inside privacy zone including laps`() {
        val g = grant(setOf(Field.ACT_ROUTE))
        val p = assertNotNull(ActivityProjector.project(g, act, track, laps, listOf(home)))
        assertEquals(1, p.route!!.size)
        assertEquals(50.01, p.route[0].lat)
        assertNull(p.laps!![0].startLat); assertNull(p.laps[0].startLon)
        assertEquals(50.01, p.laps[1].startLat)
        val wire = kotlinx.serialization.json.Json.encodeToString(ActivityProjection.serializer(), p)
        assertFalse(wire.contains("50.0005")); assertFalse(wire.contains("50.0002"))
    }

    @Test fun `exact time is a separate option and charts carry no coordinates`() {
        val noTime = assertNotNull(ActivityProjector.project(grant(setOf(Field.ACT_SUMMARY, Field.ACT_CHARTS)), act, track, laps, listOf(home)))
        assertNull(noTime.startUtcMs); assertEquals("2026-10-20", noTime.localDate)
        assertNull(noTime.route)
        assertTrue(noTime.charts!!.any { it.name == "heartRate" })
        // čas v grafech je relativní
        assertEquals(0L, noTime.charts.first().points.minOf { it.first })
        val withTime = assertNotNull(ActivityProjector.project(grant(setOf(Field.ACT_SUMMARY, Field.ACT_EXACT_TIME)), act, track, laps, listOf(home)))
        assertEquals(act.startUtcMs, withTime.startUtcMs)
    }

    @Test fun `summary only grant does not leak route notes or title`() {
        val a = act.copy(title = "Běh z Dlouhé 5", notes = "doma")
        val p = assertNotNull(ActivityProjector.project(grant(Presets.activitySummary), a, track, laps, listOf(home)))
        assertNull(p.route); assertNull(p.laps); assertNull(p.charts)
        val wire = kotlinx.serialization.json.Json.encodeToString(ActivityProjection.serializer(), p)
        assertFalse(wire.contains("Dlouhé")); assertFalse(wire.contains("doma"))
    }

    @Test fun `original FIT is never attached when a privacy zone exists`() {
        val fit = AttachmentRef("a1.fit", "ab", 10)
        val g = grant(setOf(Field.ACT_FULL_RECORD))
        val withZone = assertNotNull(ActivityProjector.project(g, act, track, laps, listOf(home)))
        assertNull(withZone.attachments)
        assertTrue(withZone.omitted.isNotEmpty())
        assertEquals(1, assertNotNull(ActivityProjector.project(g, act, track, laps, emptyList(), fit)).attachments!!.size)
    }

    @Test fun `revocation cancels queue requests removal and states the limits`() {
        val plan = Revocation.plan(grant(Presets.sleepFull), 100, mapOf("bob" to listOf("proj:2026-10-20")), hasMessagesInPropagationNode = true)
        assertFalse(plan.revokedGrant.active)
        assertEquals("g1", plan.cancelQueuedForGrantId)
        assertEquals(listOf(RemovalRequest("bob", "proj:2026-10-20")), plan.removalRequests)
        assertEquals(RevocationLimit.entries.toSet(), plan.limits)
        val none = Revocation.plan(grant(Presets.sleepFull), 100, emptyMap(), false)
        assertEquals(setOf(RevocationLimit.OFFLINE_RECIPIENT_LEARNS_LATER), none.limits)
    }
}
