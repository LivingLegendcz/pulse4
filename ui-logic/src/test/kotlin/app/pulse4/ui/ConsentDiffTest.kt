package app.pulse4.ui

import app.pulse4.domain.Provider
import app.pulse4.sharing.ConsentSubject
import app.pulse4.sharing.Field
import app.pulse4.sharing.GrantTerms
import app.pulse4.sharing.GuardianConsentRequest
import app.pulse4.sharing.MembershipAction
import app.pulse4.sharing.PlanRight
import app.pulse4.sharing.TimeScope
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsentDiffTest {
    private val names = mapOf("bob-id" to "Bob", "cyril-id" to "Cyril", "admin-id" to "Trenér Adam")
    private fun nameOf(id: String) = names[id] ?: "?"
    private val scope = TimeScope.continuousFrom(LocalDate.of(2026, 3, 1))

    private fun terms(
        recipients: Set<String> = setOf("bob-id"),
        fields: Set<Field> = setOf(Field.SLEEP_DURATION),
        scope: TimeScope = this.scope,
        sources: Set<Provider>? = null,
        plans: Map<String, PlanRight> = emptyMap(),
    ) = GrantTerms(recipients, fields, plans, scope, sources)

    private fun request(terms: GrantTerms, baseline: GrantTerms? = null) = GuardianConsentRequest(
        subject = ConsentSubject.SHARE_GRANT, requestId = "r1", nonce = "0".repeat(32), guardianIds = setOf("g"), childId = "c",
        subjectId = "grant1", permissionVersion = 1, terms = terms, baseline = baseline, issuedAtMs = 1, expiresAtMs = 2,
    )

    @Test
    fun `first approval lists the full terms by name without ids`() {
        val d = ConsentDiff.describe(request(terms(fields = setOf(Field.SLEEP_DURATION, Field.SLEEP_SCORE, Field.HR_DAILY))), ::nameOf)
        assertTrue(d.firstApproval)
        assertEquals("Sdílení metrik", d.title)
        val byLabel = d.lines.associate { it.label to it.text }
        assertEquals("Bob", byLabel["Příjemci"])
        assertEquals("Spánek (délka, skóre); Tep (denní souhrn)", byLabel["Data"])
        assertEquals("průběžně od 1. 3. 2026", byLabel["Období"])
        assertEquals("všechny zdroje", byLabel["Zdroje"])
        assertFalse(d.lines.any { it.text.contains("-id") })
    }

    @Test
    fun `widening shows added recipients fields scope and sources`() {
        val base = terms()
        val now = terms(
            recipients = setOf("bob-id", "cyril-id"),
            fields = setOf(Field.SLEEP_DURATION, Field.SLEEP_STAGES, Field.WEIGHT),
            scope = TimeScope.continuousFrom(LocalDate.of(2025, 1, 15)),
            sources = setOf(Provider.WITHINGS_API),
        )
        val d = ConsentDiff.describe(request(now, base), ::nameOf)
        assertFalse(d.firstApproval)
        assertTrue(d.hasWidening)
        assertEquals("Dítě chce rozšířit už schválené sdílení.", d.headline)
        val lines = d.lines.associateBy { it.label }
        assertEquals(ConsentDiff.Kind.ADDED, lines.getValue("Nový příjemce").kind)
        assertEquals("Cyril", lines.getValue("Nový příjemce").text)
        assertEquals("Spánek (fáze); Tělesné složení (Váha)", lines.getValue("Nová data").text)
        assertEquals("průběžně od 1. 3. 2026 → průběžně od 15. 1. 2025", lines.getValue("Období").text)
        assertEquals("všechny zdroje → Withings API", lines.getValue("Zdroje").text)
    }

    @Test
    fun `narrowing shows removals only and is not a widening`() {
        val base = terms(recipients = setOf("bob-id", "cyril-id"), fields = setOf(Field.SLEEP_DURATION, Field.SLEEP_SCORE))
        val now = terms(recipients = setOf("bob-id"), fields = setOf(Field.SLEEP_DURATION))
        val d = ConsentDiff.describe(request(now, base), ::nameOf)
        assertFalse(d.hasWidening)
        assertEquals("Dítě chce sdílení zúžit.", d.headline)
        assertEquals(setOf(ConsentDiff.Kind.REMOVED), d.lines.map { it.kind }.toSet())
        assertEquals("Cyril", d.lines.first { it.label == "Odebraný příjemce" }.text)
        assertEquals("Spánek (skóre)", d.lines.first { it.label == "Odebraná data" }.text)
    }

    @Test
    fun `identical terms give an empty diff`() {
        val t = terms()
        val d = ConsentDiff.describe(request(t, t), ::nameOf)
        assertTrue(d.lines.isEmpty())
        assertEquals("Beze změny oproti schválenému sdílení.", d.headline)
    }

    @Test
    fun `plan rights widening and narrowing are reported with plan names`() {
        val base = terms(plans = mapOf("p1" to PlanRight.VIEW, "p2" to PlanRight.EDIT))
        val now = terms(plans = mapOf("p1" to PlanRight.EDIT, "p3" to PlanRight.COPY))
        val lines = ConsentDiff.diff(base.copy(), now, ::nameOf) { id -> "Plán $id" }
        val more = lines.first { it.label == "Plány: více práv" }
        assertEquals("Plán p1 (upravovat), Plán p3 (kopírovat)", more.text)
        val fewer = lines.first { it.label == "Plány: méně práv" }
        assertEquals("Plán p2 (upravovat)", fewer.text)
    }

    @Test
    fun `once scope and time range changes are readable`() {
        val a = terms(scope = TimeScope.once(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
        val lines = ConsentDiff.diff(a, terms(), ::nameOf)
        assertEquals("jednorázově 1. 1. 2026 až 31. 1. 2026 → průběžně od 1. 3. 2026", lines.single().text)
    }

    @Test
    fun `circle and guardian add requests describe the subject without ids`() {
        val join = GuardianConsentRequest(
            subject = ConsentSubject.CIRCLE_MEMBERSHIP, requestId = "r2", nonce = "0".repeat(32), guardianIds = setOf("g"), childId = "c",
            subjectId = "circle-1", permissionVersion = 1, subjectName = "Fotbal", circleAdminId = "admin-id", action = MembershipAction.JOIN,
            issuedAtMs = 1, expiresAtMs = 2,
        )
        val dj = ConsentDiff.describe(join, ::nameOf)
        assertEquals("Vstup do kruhu", dj.title)
        assertEquals("Dítě chce vstoupit do kruhu „Fotbal“.", dj.headline)
        assertEquals("Trenér Adam", dj.lines.first { it.label == "Správce kruhu" }.text)
        val dc = ConsentDiff.describe(join.copy(action = MembershipAction.CREATE), ::nameOf)
        assertEquals("Založení kruhu", dc.title)

        val add = GuardianConsentRequest(
            subject = ConsentSubject.GUARDIAN_ADD, requestId = "r3", nonce = "0".repeat(32), guardianIds = setOf("g"), childId = "c",
            subjectId = "new-guardian", permissionVersion = 1, subjectName = "Maminka", subjectKeyDigest = "ab".repeat(32),
            issuedAtMs = 1, expiresAtMs = 2,
        )
        val da = ConsentDiff.describe(add, ::nameOf)
        assertEquals("Přidání dalšího rodiče", da.title)
        assertTrue(da.lines.any { it.label == "Nový rodič" && it.text == "Maminka" })
        assertFalse(da.lines.any { it.text.contains("abab") || it.text.contains("new-guardian") })
    }
}
