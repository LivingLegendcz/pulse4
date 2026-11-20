package app.pulse4.ui

import app.pulse4.sharing.FamilyLinkStatus

/** Co se děje s jednou vazbou dítěte na rodiče; čistá logika pro obrazovku "Moji rodiče" / čekací obrazovku. */
enum class ParentLinkPhase {
    /** Dítě poslalo žádost a rodič ji zatím nepotvrdil. */
    WAITING_FOR_PARENT,
    /** Rodič potvrdil, ale další rodič musí ještě schválit stávající rodič (GUARDIAN_ADD). */
    WAITING_FOR_EXISTING_GUARDIAN,
    /** Dítě poslalo žádost dalšímu rodiči: čeká se na rodiče i na stávajícího rodiče. */
    WAITING_FOR_PARENT_AND_GUARDIAN,
    /** Rodič dítě sám vyzval a dítě má žádost potvrdit nebo odmítnout. */
    INVITED_BY_PARENT,
    VERIFIED,
    REJECTED,
    REMOVED,
}

object ParentLinkStatus {
    fun phase(
        status: FamilyLinkStatus,
        initiatedByChild: Boolean,
        hasVerifiedGuardians: Boolean,
        awaitingGuardianAdd: Boolean,
        guardianAddPending: Boolean,
    ): ParentLinkPhase = when (status) {
        FamilyLinkStatus.VERIFIED -> ParentLinkPhase.VERIFIED
        FamilyLinkStatus.REJECTED -> ParentLinkPhase.REJECTED
        FamilyLinkStatus.REVOKED -> ParentLinkPhase.REMOVED
        FamilyLinkStatus.PENDING -> when {
            !initiatedByChild -> ParentLinkPhase.INVITED_BY_PARENT
            awaitingGuardianAdd -> ParentLinkPhase.WAITING_FOR_EXISTING_GUARDIAN
            hasVerifiedGuardians && guardianAddPending -> ParentLinkPhase.WAITING_FOR_PARENT_AND_GUARDIAN
            else -> ParentLinkPhase.WAITING_FOR_PARENT
        }
    }

    fun label(p: ParentLinkPhase) = when (p) {
        ParentLinkPhase.WAITING_FOR_PARENT -> "Odesláno – čeká na potvrzení rodiče"
        ParentLinkPhase.WAITING_FOR_EXISTING_GUARDIAN -> "Rodič potvrdil – čeká na schválení stávajícím rodičem"
        ParentLinkPhase.WAITING_FOR_PARENT_AND_GUARDIAN -> "Čeká na potvrzení rodiče a na schválení stávajícím rodičem"
        ParentLinkPhase.INVITED_BY_PARENT -> "Rodič vás vyzval k propojení – potvrďte nebo odmítněte"
        ParentLinkPhase.VERIFIED -> "Propojeno"
        ParentLinkPhase.REJECTED -> "Odmítnuto"
        ParentLinkPhase.REMOVED -> "Odebráno"
    }

    /** Varování před odebráním vlastní vazby s rodičem. */
    fun removeWarning(isLastVerifiedParent: Boolean): String =
        if (isLastVerifiedParent) {
            "Je to váš poslední rodič. Po odebrání se aplikace znovu zamkne a všechna sdílení i kruhy schválené rodičem se odvolají. " +
                Labels.DELIVERED_DATA_NOTE
        } else {
            "Schválená sdílení zůstanou platná, dokud je neodvolá jiný rodič. Odebrat můžete jen svou vlastní vazbu."
        }
}
