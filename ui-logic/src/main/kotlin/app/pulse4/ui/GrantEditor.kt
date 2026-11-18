package app.pulse4.ui

import app.pulse4.domain.Provider
import app.pulse4.sharing.Category
import app.pulse4.sharing.Field
import app.pulse4.sharing.ShareGrant
import app.pulse4.sharing.TimeScope
import java.time.LocalDate

/**
 * Čistá JVM logika pro vytváření a úpravy [ShareGrant] na všech 3 místech aplikace
 * (GroupsScreen, SettingsHubScreen, SharingOverviewScreen).
 *
 * Zaručuje princip nejmenšího oprávnění: nikdy tiše nerozšíří pole ani neodvolá cizí grant.
 */
object GrantEditor {
    val SENSITIVE_ACTIVITY_FIELDS: Set<Field> = setOf(
        Field.ACT_ROUTE,
        Field.ACT_FULL_RECORD,
        Field.ACT_EXACT_TIME,
    )

    enum class SourceChoice(val label: String, val providers: Set<Provider>?) {
        ALL("Všechny zdroje", null),
        WITHINGS_ONLY("Pouze Withings", setOf(Provider.WITHINGS_API)),
        GARMIN_ONLY("Pouze Garmin", setOf(Provider.GARMIN_PERSONAL, Provider.GARMIN_EXTENDED)),
        WITHINGS_AND_GARMIN(
            "Withings + Garmin",
            setOf(Provider.WITHINGS_API, Provider.GARMIN_PERSONAL, Provider.GARMIN_EXTENDED),
        ),
    }

    fun fieldsOf(category: Category): Set<Field> =
        Field.entries.filter { it.category == category }.toSet()

    /** UX-6: Nové sdílení aktivit začíná pouze souhrnem; citlivá pole vyžadují výslovnou volbu. */
    fun defaultFieldsFor(category: Category): Set<Field> = when (category) {
        Category.ACTIVITY -> setOf(Field.ACT_SUMMARY)
        else -> fieldsOf(category)
    }

    fun sourceChoiceFrom(sources: Set<Provider>?): SourceChoice? {
        if (sources == null) return SourceChoice.ALL
        val hasWithings = Provider.WITHINGS_API in sources
        val hasGarmin = Provider.GARMIN_PERSONAL in sources || Provider.GARMIN_EXTENDED in sources
        return when {
            hasWithings && hasGarmin -> SourceChoice.WITHINGS_AND_GARMIN
            hasWithings && !hasGarmin -> SourceChoice.WITHINGS_ONLY
            !hasWithings && hasGarmin -> SourceChoice.GARMIN_ONLY
            else -> null
        }
    }

    fun resolveSources(
        choice: SourceChoice?,
        originalSources: Set<Provider>?,
        sourceModified: Boolean,
    ): Set<Provider>? = if (!sourceModified) originalSources else choice?.providers

    // --- S1: Přepínač kategorie v kruhu (GroupsScreen) ---

    sealed interface GroupToggleResult {
        data class Save(val grant: ShareGrant) : GroupToggleResult
        data class Revoke(val grantId: String) : GroupToggleResult
        data object Noop : GroupToggleResult
    }

    /** S1: Páruje výhradně granty patřící tomuto kruhu (`g.groupId == groupId`). */
    fun findGroupCategoryGrant(
        activeGrants: List<ShareGrant>,
        groupId: String?,
        category: Category,
    ): ShareGrant? {
        if (groupId == null) return null
        val catFields = fieldsOf(category)
        return activeGrants.firstOrNull { g ->
            g.active && g.groupId == groupId && g.fields.intersect(catFields).isNotEmpty()
        }
    }

    /** S1: Informativní počet členů kruhu, kteří danou kategorii už dostávají jinou cestou. */
    fun otherCoverageRecipients(
        activeGrants: List<ShareGrant>,
        groupId: String?,
        targetRecipients: Set<String>,
        category: Category,
    ): Set<String> {
        val catFields = fieldsOf(category)
        return activeGrants
            .filter { g -> g.active && g.groupId != groupId && g.fields.intersect(catFields).isNotEmpty() }
            .flatMap { it.recipients }
            .toSet()
            .intersect(targetRecipients)
    }

    fun toggleGroupCategory(
        activeGrants: List<ShareGrant>,
        groupId: String?,
        category: Category,
        enable: Boolean,
        targetRecipients: Set<String>,
        ownerId: String,
        today: LocalDate,
        nowMs: Long,
        sources: Set<Provider>?,
        newGrantId: String,
        extraActivityFields: Set<Field> = emptySet(),
    ): GroupToggleResult {
        if (groupId == null) return GroupToggleResult.Noop
        if (!enable) {
            val existing = findGroupCategoryGrant(activeGrants, groupId, category) ?: return GroupToggleResult.Noop
            val remaining = existing.fields - fieldsOf(category)
            return if (remaining.isEmpty()) {
                GroupToggleResult.Revoke(existing.id)
            } else {
                GroupToggleResult.Save(existing.copy(fields = remaining))
            }
        }
        if (targetRecipients.isEmpty()) return GroupToggleResult.Noop
        val fields = if (category == Category.ACTIVITY) {
            defaultFieldsFor(category) + extraActivityFields.intersect(fieldsOf(Category.ACTIVITY))
        } else {
            defaultFieldsFor(category)
        }
        return GroupToggleResult.Save(
            ShareGrant(
                id = newGrantId,
                ownerId = ownerId,
                recipients = targetRecipients,
                fields = fields,
                scope = TimeScope.continuousFrom(today.minusDays(60)),
                groupId = groupId,
                createdAtMs = nowMs,
                sources = sources,
            )
        )
    }

    // --- S2: Globální profil přímého sdílení (SettingsHubScreen) ---

    data class GlobalFormState(
        val enabledCategories: Set<Category>,
        val selectedRecipients: Set<String>,
        val extraActivityFields: Set<Field> = emptySet(),
    )

    data class GlobalSaveDiff(
        val newlySharedRecipients: Set<String>,
        val removedRecipients: Set<String>,
        val newlyAddedCategories: Set<Category>,
        val removedCategories: Set<Category>,
    )

    /** S2: Pracuje výhradně s přímými granty (`groupId == null`). */
    fun directGrants(activeGrants: List<ShareGrant>, ownerId: String? = null): List<ShareGrant> =
        activeGrants.filter { g ->
            g.active && g.groupId == null && (ownerId == null || g.ownerId == ownerId)
        }

    /** S2: Výchozí příjemci jsou prázdná množina (nikdy ne všechny kontakty). */
    fun initialGlobalForm(activeGrants: List<ShareGrant>, ownerId: String? = null): GlobalFormState {
        val direct = directGrants(activeGrants, ownerId)
        val allFields = direct.flatMap { it.fields }.toSet()
        val cats = allFields.map { it.category }.toSet()
        val recipients = if (direct.isEmpty()) emptySet() else direct.flatMap { it.recipients }.toSet()
        val extraAct = allFields.intersect(fieldsOf(Category.ACTIVITY) - Field.ACT_SUMMARY)
        return GlobalFormState(
            enabledCategories = cats,
            selectedRecipients = recipients,
            extraActivityFields = extraAct,
        )
    }

    fun computeGlobalDiff(
        existingDirectGrants: List<ShareGrant>,
        targetCategories: Set<Category>,
        targetRecipients: Set<String>,
    ): GlobalSaveDiff {
        val oldRecipients = existingDirectGrants.flatMap { it.recipients }.toSet()
        val oldCategories = existingDirectGrants.flatMap { it.fields }.map { it.category }.toSet()
        val effectiveNewRecipients = if (targetCategories.isEmpty()) emptySet() else targetRecipients
        val effectiveNewCategories = if (targetRecipients.isEmpty()) emptySet() else targetCategories
        return GlobalSaveDiff(
            newlySharedRecipients = effectiveNewRecipients - oldRecipients,
            removedRecipients = oldRecipients - effectiveNewRecipients,
            newlyAddedCategories = effectiveNewCategories - oldCategories,
            removedCategories = oldCategories - effectiveNewCategories,
        )
    }

    fun buildGlobalGrant(
        id: String,
        ownerId: String,
        existingDirectGrants: List<ShareGrant>,
        selectedCategories: Set<Category>,
        selectedRecipients: Set<String>,
        extraActivityFields: Set<Field> = emptySet(),
        today: LocalDate,
        nowMs: Long,
    ): ShareGrant? {
        if (selectedRecipients.isEmpty() || selectedCategories.isEmpty()) return null
        val existingFields = existingDirectGrants.flatMap { it.fields }.toSet()
        val existingCategories = existingFields.map { it.category }.toSet()
        val keptCategories = existingCategories.intersect(selectedCategories)
        val newlyChecked = selectedCategories - existingCategories

        val keptFields = existingFields.filter { it.category in keptCategories }.toSet()
        val addedFields = newlyChecked.flatMap { defaultFieldsFor(it) }.toSet()
        var chosenFields = keptFields + addedFields
        if (Category.ACTIVITY in selectedCategories) {
            chosenFields = (chosenFields - (fieldsOf(Category.ACTIVITY) - Field.ACT_SUMMARY)) +
                Field.ACT_SUMMARY +
                extraActivityFields.intersect(fieldsOf(Category.ACTIVITY))
        }
        if (chosenFields.isEmpty()) return null

        return ShareGrant(
            id = id,
            ownerId = ownerId,
            recipients = selectedRecipients,
            fields = chosenFields,
            scope = TimeScope.continuousFrom(today.minusDays(7)),
            groupId = null,
            createdAtMs = nowMs,
        )
    }

    // --- S3: Úprava existujícího grantu (SharingOverviewScreen) ---

    /**
     * S3: Zachová existující pole v ponechaných kategoriích a přidá jen výchozí pole nově zaškrtnutých kategorií.
     * Uložení beze změny vrátí grant rovný původnímu.
     */
    fun editGrant(
        existing: ShareGrant,
        selectedCategories: Set<Category>,
        selectedSourceChoice: SourceChoice?,
        sourceModified: Boolean,
        explicitActivityFields: Set<Field>? = null,
    ): ShareGrant {
        val existingCategories = existing.fields.map { it.category }.toSet()
        val keptCategories = existingCategories.intersect(selectedCategories)
        val newlyChecked = selectedCategories - existingCategories

        val keptFields = existing.fields.filter { it.category in keptCategories }.toSet()
        val addedFields = newlyChecked.flatMap { defaultFieldsFor(it) }.toSet()
        var newFields = keptFields + addedFields
        if (explicitActivityFields != null && Category.ACTIVITY in selectedCategories) {
            newFields = (newFields - fieldsOf(Category.ACTIVITY)) +
                Field.ACT_SUMMARY +
                explicitActivityFields.intersect(fieldsOf(Category.ACTIVITY))
        }

        val newSources = resolveSources(selectedSourceChoice, existing.sources, sourceModified)
        if (newFields == existing.fields && newSources == existing.sources) {
            return existing
        }
        return existing.copy(
            fields = newFields,
            sources = newSources,
        )
    }
}
