package app.pulse4.ui

import app.pulse4.domain.MetricObservation
import app.pulse4.domain.MetricType
import app.pulse4.domain.Provider
import app.pulse4.provider.ProviderError
import app.pulse4.sharing.Category
import app.pulse4.sharing.Field
import app.pulse4.sharing.PlanRight
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.TimeScope
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrantEditorTest {
    private val today = LocalDate.of(2026, 11, 18)

    private fun grant(
        id: String,
        recipients: Set<String> = setOf("bob"),
        fields: Set<Field> = setOf(Field.SLEEP_DURATION),
        groupId: String? = null,
        sources: Set<Provider>? = null,
    ) = ShareGrant(
        id = id,
        ownerId = "alice",
        recipients = recipients,
        fields = fields,
        scope = TimeScope.continuousFrom(today),
        groupId = groupId,
        createdAtMs = 1000L,
        sources = sources,
    )

    @Test
    fun `S1 group toggle matches only own groupId and narrows instead of revoking foreign grant`() {
        val directBob = grant("direct-bob", recipients = setOf("bob"), fields = setOf(Field.SLEEP_DURATION, Field.HR_DAILY), groupId = null)
        val otherGroup = grant("other-grp", recipients = setOf("bob", "eva"), fields = setOf(Field.SLEEP_DURATION), groupId = "grp-2")
        val ownGroup = grant("own-grp", recipients = setOf("bob", "eva"), fields = setOf(Field.SLEEP_DURATION, Field.SLEEP_SCORE, Field.HR_DAILY), groupId = "grp-1")

        // Direct / other-group grants must never match grp-1
        assertNull(GrantEditor.findGroupCategoryGrant(listOf(directBob, otherGroup), "grp-1", Category.SLEEP))
        assertEquals(setOf("bob"), GrantEditor.otherCoverageRecipients(listOf(directBob), "grp-1", setOf("bob", "eva"), Category.SLEEP))

        // Turning off SLEEP on ownGroup narrows fields (keeps HR_DAILY), does not revoke
        val narrowed = GrantEditor.toggleGroupCategory(
            activeGrants = listOf(directBob, ownGroup),
            groupId = "grp-1",
            category = Category.SLEEP,
            enable = false,
            targetRecipients = setOf("bob", "eva"),
            ownerId = "alice",
            today = today,
            nowMs = 2000L,
            sources = null,
            newGrantId = "new-1",
        )
        assertEquals(
            GrantEditor.GroupToggleResult.Save(ownGroup.copy(fields = setOf(Field.HR_DAILY))),
            narrowed,
        )

        // Turning off the last remaining category revokes ownGroup only
        val revoked = GrantEditor.toggleGroupCategory(
            activeGrants = listOf(directBob, ownGroup.copy(fields = setOf(Field.HR_DAILY))),
            groupId = "grp-1",
            category = Category.HEART_RATE,
            enable = false,
            targetRecipients = setOf("bob", "eva"),
            ownerId = "alice",
            today = today,
            nowMs = 2000L,
            sources = null,
            newGrantId = "new-2",
        )
        assertEquals(GrantEditor.GroupToggleResult.Revoke("own-grp"), revoked)
    }

    @Test
    fun `S2 global profile touches only direct grants and defaults to empty recipients`() {
        val groupGrant = grant("grp-1", recipients = setOf("bob"), fields = setOf(Field.SLEEP_DURATION), groupId = "grp-1")
        // When only group grants exist, directGrants is empty and default recipients = emptySet()
        assertTrue(GrantEditor.directGrants(listOf(groupGrant)).isEmpty())
        val form = GrantEditor.initialGlobalForm(listOf(groupGrant))
        assertTrue(form.selectedRecipients.isEmpty())
        assertTrue(form.enabledCategories.isEmpty())

        // buildGlobalGrant refuses empty recipients
        assertNull(
            GrantEditor.buildGlobalGrant(
                id = "g-new",
                ownerId = "alice",
                existingDirectGrants = emptyList(),
                selectedCategories = setOf(Category.SLEEP),
                selectedRecipients = emptySet(),
                today = today,
                nowMs = 2000L,
            )
        )

        // Diff calculation
        val existingDirect = grant("d1", recipients = setOf("bob"), fields = setOf(Field.SLEEP_DURATION), groupId = null)
        val diff = GrantEditor.computeGlobalDiff(
            existingDirectGrants = listOf(existingDirect),
            targetCategories = setOf(Category.SLEEP, Category.HRV),
            targetRecipients = setOf("eva"),
        )
        assertEquals(setOf("eva"), diff.newlySharedRecipients)
        assertEquals(setOf("bob"), diff.removedRecipients)
        assertEquals(setOf(Category.HRV), diff.newlyAddedCategories)
        assertTrue(diff.removedCategories.isEmpty())
        assertEquals(setOf(Category.SLEEP, Category.HRV), diff.addedByRecipient["eva"])
        assertEquals(setOf(Category.SLEEP), diff.removedByRecipient["bob"])
        assertTrue(diff.hasChanges)
    }

    @Test
    fun `NEW-3 global profile preserves sources, refuses incompatible direct grants, and detects no-op saves`() {
        val withingsOnly = grant(
            id = "d1",
            recipients = setOf("bob"),
            fields = setOf(Field.WEIGHT),
            sources = setOf(Provider.WITHINGS_API),
        )
        val form = GrantEditor.initialGlobalForm(listOf(withingsOnly))
        assertFalse(form.incompatibleDirectGrants)
        assertEquals(setOf(Provider.WITHINGS_API), form.sources)
        assertEquals(GrantEditor.SourceChoice.WITHINGS_ONLY, form.sourceChoice)

        // No-op diff has hasChanges == false
        val noopDiff = GrantEditor.computeGlobalDiff(
            existingDirectGrants = listOf(withingsOnly),
            targetCategories = setOf(Category.BODY_COMPOSITION),
            targetRecipients = setOf("bob"),
            targetSources = setOf(Provider.WITHINGS_API),
        )
        assertFalse(noopDiff.hasChanges)
        assertTrue(noopDiff.addedByRecipient.isEmpty())
        assertTrue(noopDiff.removedByRecipient.isEmpty())

        // Changing sources is detected as a change and preserved in buildGlobalGrant
        val sourceChangedDiff = GrantEditor.computeGlobalDiff(
            existingDirectGrants = listOf(withingsOnly),
            targetCategories = setOf(Category.BODY_COMPOSITION),
            targetRecipients = setOf("bob"),
            targetSources = null,
        )
        assertTrue(sourceChangedDiff.hasChanges)
        assertTrue(sourceChangedDiff.sourcesChanged)

        val rebuilt = assertNotNull(
            GrantEditor.buildGlobalGrant(
                id = "d2",
                ownerId = "alice",
                existingDirectGrants = listOf(withingsOnly),
                selectedCategories = setOf(Category.BODY_COMPOSITION),
                selectedRecipients = setOf("bob"),
                today = today,
                nowMs = 2000L,
            )
        )
        assertEquals(setOf(Provider.WITHINGS_API), rebuilt.sources)

        // Incompatible direct grants (sleep->{A}, weight->{A,B}) must NOT be silently merged
        val gSleepA = grant("d-sleep", recipients = setOf("A"), fields = setOf(Field.SLEEP_DURATION))
        val gWeightAB = grant("d-weight", recipients = setOf("A", "B"), fields = setOf(Field.WEIGHT))
        assertTrue(GrantEditor.hasIncompatibleDirectGrants(listOf(gSleepA, gWeightAB)))
        assertTrue(GrantEditor.initialGlobalForm(listOf(gSleepA, gWeightAB)).incompatibleDirectGrants)
        assertNull(
            GrantEditor.buildGlobalGrant(
                id = "d-merged",
                ownerId = "alice",
                existingDirectGrants = listOf(gSleepA, gWeightAB),
                selectedCategories = setOf(Category.SLEEP, Category.BODY_COMPOSITION),
                selectedRecipients = setOf("A", "B"),
                today = today,
                nowMs = 3000L,
            )
        )
    }

    @Test
    fun `S3 editGrant preserves kept fields, never auto-adds sensitive activity fields, and preserves Withings+Garmin`() {
        val original = grant(
            id = "g1",
            fields = setOf(Field.SLEEP_DURATION),
            sources = setOf(Provider.WITHINGS_API, Provider.GARMIN_PERSONAL, Provider.GARMIN_EXTENDED),
        )
        assertEquals(GrantEditor.SourceChoice.WITHINGS_AND_GARMIN, GrantEditor.sourceChoiceFrom(original.sources))

        // Saving without changes returns identical grant
        val unchanged = GrantEditor.editGrant(
            existing = original,
            selectedCategories = setOf(Category.SLEEP),
            selectedSourceChoice = GrantEditor.sourceChoiceFrom(original.sources),
            sourceModified = false,
        )
        assertEquals(original, unchanged)

        // Checking ACTIVITY adds ONLY ACT_SUMMARY, never ACT_EXACT_TIME/ACT_ROUTE/ACT_FULL_RECORD,
        // and keeps SLEEP_DURATION only (does not expand to SLEEP_SCORE/SLEEP_STAGES).
        val withActivity = GrantEditor.editGrant(
            existing = original,
            selectedCategories = setOf(Category.SLEEP, Category.ACTIVITY),
            selectedSourceChoice = GrantEditor.SourceChoice.WITHINGS_AND_GARMIN,
            sourceModified = true,
        )
        assertEquals(setOf(Field.SLEEP_DURATION, Field.ACT_SUMMARY), withActivity.fields)
        GrantEditor.SENSITIVE_ACTIVITY_FIELDS.forEach { assertFalse(it in withActivity.fields) }
    }

    @Test
    fun `UX6 defaultFieldsFor ACTIVITY is summary only`() {
        assertEquals(setOf(Field.ACT_SUMMARY), GrantEditor.defaultFieldsFor(Category.ACTIVITY))
    }

    @Test
    fun `UX5 loginError maps errors to human sentences without class simpleName`() {
        val rateMsg = Labels.loginError(ProviderError.RateLimited(null))
        assertFalse(rateMsg.contains("RateLimited"))
        assertFalse(rateMsg.contains("429"))
        val netMsg = Labels.loginError(ProviderError.Network("ConnectException"))
        assertFalse(netMsg.contains("Network"))
        assertFalse(netMsg.contains("ConnectException"))
        val genericMsg = Labels.loginError(IllegalStateException("secret token xyz"))
        assertFalse(genericMsg.contains("IllegalStateException"))
        assertFalse(genericMsg.contains("secret token xyz"))
    }

    @Test
    fun `D6 Aggregation deduplicates STEPS and SLEEP_DURATION across providers per day`() {
        val garmin1 = MetricObservation("g1", Provider.GARMIN_PERSONAL, "a", "g1", MetricType.STEPS, 100L, null, 4000.0, "Europe/Prague", today.toString())
        val garmin2 = MetricObservation("g2", Provider.GARMIN_PERSONAL, "a", "g2", MetricType.STEPS, 200L, null, 3500.0, "Europe/Prague", today.toString())
        val withings = MetricObservation("w1", Provider.WITHINGS_API, "a", "w1", MetricType.STEPS, 150L, null, 7200.0, "Europe/Prague", today.toString())

        // Same provider sums (4000 + 3500), different provider does not double-count (+7200)
        val total = assertNotNull(Aggregation.value(MetricType.STEPS, listOf(garmin1, garmin2, withings)))
        assertEquals(7500.0, total)
    }

    @Test
    fun `Fmt pace rounds to nearest second and Chart trackRange handles empty chart`() {
        assertEquals("5:00 /km", Fmt.default.pace(299.6))
        val emptyChart = Chart(emptyList(), IntArray(0), 0.0, 0.0)
        assertEquals(IntRange.EMPTY, emptyChart.trackRange(0..10))
    }

    @Test
    fun `NEW-3 buildGlobalGrant preserves widest scope, planRights, and reports scopeChanged and empty revoke`() {
        val g1 = grant("d1", recipients = setOf("bob"), fields = setOf(Field.SLEEP_DURATION)).copy(
            scope = TimeScope.continuousFrom(today.minusDays(45)),
            planRights = mapOf("plan-1" to PlanRight.VIEW, "plan-2" to PlanRight.PROPOSE),
        )
        val g2 = grant("d2", recipients = setOf("bob"), fields = setOf(Field.SLEEP_DURATION)).copy(
            scope = TimeScope.from(today.minusDays(14)),
            planRights = mapOf("plan-1" to PlanRight.EDIT),
        )
        val rebuilt = assertNotNull(
            GrantEditor.buildGlobalGrant(
                id = "d3",
                ownerId = "alice",
                existingDirectGrants = listOf(g1, g2),
                selectedCategories = setOf(Category.SLEEP, Category.HRV),
                selectedRecipients = setOf("bob"),
                today = today,
                nowMs = 5_000L,
            )
        )
        assertEquals(today.minusDays(45).toString(), rebuilt.scope.from)
        assertEquals(mapOf("plan-1" to PlanRight.EDIT, "plan-2" to PlanRight.PROPOSE), rebuilt.planRights)

        val scopeDiff = GrantEditor.computeGlobalDiff(
            existingDirectGrants = listOf(g1),
            targetCategories = setOf(Category.SLEEP),
            targetRecipients = setOf("bob"),
            today = today,
            targetScope = TimeScope.continuousFrom(today.minusDays(90)),
        )
        assertTrue(scopeDiff.scopeChanged)
        assertTrue(scopeDiff.hasChanges)

        val revokeAllDiff = GrantEditor.computeGlobalDiff(
            existingDirectGrants = listOf(g1),
            targetCategories = setOf(Category.SLEEP),
            targetRecipients = emptySet(),
            today = today,
        )
        assertEquals(setOf("bob"), revokeAllDiff.removedRecipients)
        assertTrue(revokeAllDiff.hasChanges)
    }

    @Test
    fun `R3-9 computeGlobalDiff detects scope and planRights widening against all merged direct grants`() {
        val gOnce = grant("d1", recipients = setOf("bob"), fields = setOf(Field.SLEEP_DURATION)).copy(
            scope = TimeScope.once(today.minusDays(10), today.minusDays(5)),
            planRights = mapOf("plan-1" to PlanRight.VIEW),
        )
        val gCont = grant("d2", recipients = setOf("bob"), fields = setOf(Field.SLEEP_DURATION)).copy(
            scope = TimeScope.continuousFrom(today.minusDays(2)),
            planRights = mapOf("plan-1" to PlanRight.EDIT, "plan-2" to PlanRight.PROPOSE),
        )
        val mergedDiff = GrantEditor.computeGlobalDiff(
            existingDirectGrants = listOf(gOnce, gCont),
            targetCategories = setOf(Category.SLEEP),
            targetRecipients = setOf("bob"),
            today = today,
        )
        assertTrue(mergedDiff.scopeChanged)
        assertTrue(mergedDiff.scopeWidened)
        assertEquals(TimeScope.continuousFrom(today.minusDays(10)), mergedDiff.newScope)
        assertEquals(listOf(gOnce.scope, gCont.scope), mergedDiff.widenedFromScopes)
        assertTrue(mergedDiff.planRightsWidened)
        assertEquals(setOf("plan-1", "plan-2"), mergedDiff.widenedPlanIds)
        assertTrue(mergedDiff.hasChanges)

        // FROM + CONTINUOUS with same start date widens FROM -> CONTINUOUS
        val gFrom = grant("d3", recipients = setOf("bob"), fields = setOf(Field.SLEEP_DURATION)).copy(
            scope = TimeScope.from(today.minusDays(5)),
        )
        val gSameStartCont = grant("d4", recipients = setOf("bob"), fields = setOf(Field.SLEEP_DURATION)).copy(
            scope = TimeScope.continuousFrom(today.minusDays(5)),
        )
        val fromToContDiff = GrantEditor.computeGlobalDiff(
            existingDirectGrants = listOf(gFrom, gSameStartCont),
            targetCategories = setOf(Category.SLEEP),
            targetRecipients = setOf("bob"),
            today = today,
        )
        assertTrue(fromToContDiff.scopeWidened)
        assertTrue(fromToContDiff.scopeChanged)
        assertEquals(listOf(gFrom.scope), fromToContDiff.widenedFromScopes)
        assertFalse(fromToContDiff.planRightsWidened)
        assertTrue(fromToContDiff.hasChanges)

        // Identical scopes and planRights across merged grants report no widening
        val gIdentical1 = grant("d5", recipients = setOf("bob"), fields = setOf(Field.SLEEP_DURATION)).copy(
            scope = TimeScope.continuousFrom(today.minusDays(5)),
            planRights = mapOf("plan-1" to PlanRight.VIEW),
        )
        val gIdentical2 = gIdentical1.copy(id = "d6")
        val noWidenDiff = GrantEditor.computeGlobalDiff(
            existingDirectGrants = listOf(gIdentical1, gIdentical2),
            targetCategories = setOf(Category.SLEEP),
            targetRecipients = setOf("bob"),
            today = today,
        )
        assertFalse(noWidenDiff.scopeWidened)
        assertFalse(noWidenDiff.scopeChanged)
        assertFalse(noWidenDiff.planRightsWidened)
        assertFalse(noWidenDiff.hasChanges)
    }
}
