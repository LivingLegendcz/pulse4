package app.pulse4.ui

import app.pulse4.sharing.ConsentSubject
import app.pulse4.sharing.Field
import app.pulse4.sharing.GrantTerms
import app.pulse4.sharing.GuardianConsentRequest
import app.pulse4.sharing.MembershipAction
import app.pulse4.sharing.PlanRight

/**
 * Čistá logika pro rodiče: lidsky čitelný popis žádosti dítěte o souhlas a rozdíl oproti dříve schváleným podmínkám.
 * Žádná ID, hashe ani podpisy; příjemci a plány se překládají přes předané funkce.
 */
object ConsentDiff {
    enum class Kind { ADDED, REMOVED, CHANGED, INFO }

    /** Jeden řádek popisu ("Nový příjemce: Kuba"). */
    data class Line(val kind: Kind, val label: String, val text: String)

    data class Description(
        /** Typ žádosti ("Sdílení metrik", "Vstup do kruhu", ...). */
        val title: String,
        /** Jedna věta shrnutí. */
        val headline: String,
        /** `true` když zatím nic schváleno nebylo (celý obsah je nový). */
        val firstApproval: Boolean,
        val lines: List<Line>,
    ) {
        /** Rozšíření je to, co vyžaduje pozornost: cokoli přidaného nebo změněného. */
        val hasWidening: Boolean get() = lines.any { it.kind == Kind.ADDED || it.kind == Kind.CHANGED }
    }

    fun describe(
        request: GuardianConsentRequest,
        nameOf: (String) -> String,
        planNameOf: (String) -> String = { it },
    ): Description = when (request.subject) {
        ConsentSubject.SHARE_GRANT -> describeGrant(request, nameOf, planNameOf)
        ConsentSubject.CIRCLE_MEMBERSHIP -> describeCircle(request, nameOf)
        ConsentSubject.GUARDIAN_ADD -> describeGuardianAdd(request)
    }

    private fun describeGuardianAdd(r: GuardianConsentRequest): Description {
        val name = r.subjectName.orEmpty().ifBlank { "neznámý kontakt" }
        return Description(
            title = Labels.consentSubject(r.subject),
            headline = "Dítě chce přidat dalšího rodiče: $name.",
            firstApproval = true,
            lines = listOf(
                Line(Kind.ADDED, "Nový rodič", name),
                Line(Kind.INFO, "Důsledek", "Nový rodič bude moci stejně jako vy schvalovat žádosti dítěte. Rodiče jsou si rovni a nemohou se navzájem odebrat."),
            ),
        )
    }

    private fun describeCircle(r: GuardianConsentRequest, nameOf: (String) -> String): Description {
        val circle = r.subjectName.orEmpty().ifBlank { "kruh" }
        val admin = r.circleAdminId?.let(nameOf)
        val create = r.action == MembershipAction.CREATE
        val lines = buildList {
            add(Line(Kind.ADDED, if (create) "Nový kruh" else "Vstup do kruhu", circle))
            if (admin != null) add(Line(Kind.INFO, "Správce kruhu", admin))
            add(Line(Kind.INFO, "Důsledek", "Členové kruhu uvidí dítě mezi členy. Metriky se do kruhu nesdílejí, dokud je zvlášť neschválíte."))
        }
        return Description(
            title = Labels.consentSubject(r.subject, r.action),
            headline = if (create) "Dítě chce založit kruh „$circle“." else "Dítě chce vstoupit do kruhu „$circle“.",
            firstApproval = true,
            lines = lines,
        )
    }

    private fun describeGrant(r: GuardianConsentRequest, nameOf: (String) -> String, planNameOf: (String) -> String): Description {
        val terms = r.terms
            ?: return Description(Labels.consentSubject(r.subject), "Žádost o sdílení metrik.", true, emptyList())
        val base = r.baseline
        val lines = if (base == null) fullTerms(terms, nameOf, planNameOf) else diff(base, terms, nameOf, planNameOf)
        val headline = when {
            base == null -> "Dítě chce začít sdílet metriky."
            lines.isEmpty() -> "Beze změny oproti schválenému sdílení."
            lines.any { it.kind == Kind.ADDED || it.kind == Kind.CHANGED } -> "Dítě chce rozšířit už schválené sdílení."
            else -> "Dítě chce sdílení zúžit."
        }
        return Description(Labels.consentSubject(r.subject), headline, base == null, lines)
    }

    private fun names(ids: Collection<String>, nameOf: (String) -> String) = ids.map(nameOf).sorted().joinToString(", ")

    private fun fieldsText(fields: Set<Field>): String =
        fields.groupBy { it.category }.entries.sortedBy { it.key.ordinal }.joinToString("; ") { (cat, fs) ->
            "${Labels.category(cat)} (${fs.sortedBy { it.ordinal }.joinToString(", ") { Labels.fieldShort(it) }})"
        }

    private fun fullTerms(t: GrantTerms, nameOf: (String) -> String, planNameOf: (String) -> String): List<Line> = buildList {
        add(Line(Kind.ADDED, "Příjemci", names(t.recipients, nameOf).ifEmpty { "nikdo" }))
        add(Line(Kind.ADDED, "Data", fieldsText(t.fields).ifEmpty { "žádná" }))
        add(Line(Kind.ADDED, "Období", Labels.scope(t.scope)))
        add(Line(Kind.ADDED, "Zdroje", Labels.sources(t.sources)))
        if (t.planRights.isNotEmpty()) {
            add(Line(Kind.ADDED, "Tréninkové plány", planRightsText(t.planRights, planNameOf)))
        }
    }

    private fun planRightsText(m: Map<String, PlanRight>, planNameOf: (String) -> String) =
        m.entries.sortedBy { planNameOf(it.key) }.joinToString(", ") { "${planNameOf(it.key)} (${Labels.planRight(it.value)})" }

    /** Rozdíl [base] (už schváleno) -> [now] (žádáno). Prázdný seznam = beze změny. */
    fun diff(base: GrantTerms, now: GrantTerms, nameOf: (String) -> String, planNameOf: (String) -> String = { it }): List<Line> = buildList {
        val addedRecipients = now.recipients - base.recipients
        val removedRecipients = base.recipients - now.recipients
        if (addedRecipients.isNotEmpty()) add(Line(Kind.ADDED, "Nový příjemce", names(addedRecipients, nameOf)))
        if (removedRecipients.isNotEmpty()) add(Line(Kind.REMOVED, "Odebraný příjemce", names(removedRecipients, nameOf)))

        val addedFields = now.fields - base.fields
        val removedFields = base.fields - now.fields
        if (addedFields.isNotEmpty()) add(Line(Kind.ADDED, "Nová data", fieldsText(addedFields)))
        if (removedFields.isNotEmpty()) add(Line(Kind.REMOVED, "Odebraná data", fieldsText(removedFields)))

        if (now.scope != base.scope) {
            add(Line(Kind.CHANGED, "Období", "${Labels.scope(base.scope)} → ${Labels.scope(now.scope)}"))
        }
        if (now.sources != base.sources) {
            add(Line(Kind.CHANGED, "Zdroje", "${Labels.sources(base.sources)} → ${Labels.sources(now.sources)}"))
        }

        val addedPlans = now.planRights.filter { (id, right) -> base.planRights[id].let { it == null || right.ordinal > it.ordinal } }
        val removedPlans = base.planRights.filter { (id, right) -> now.planRights[id].let { it == null || it.ordinal < right.ordinal } }
        if (addedPlans.isNotEmpty()) add(Line(Kind.ADDED, "Plány: více práv", planRightsText(addedPlans, planNameOf)))
        if (removedPlans.isNotEmpty()) add(Line(Kind.REMOVED, "Plány: méně práv", planRightsText(removedPlans, planNameOf)))
    }
}
