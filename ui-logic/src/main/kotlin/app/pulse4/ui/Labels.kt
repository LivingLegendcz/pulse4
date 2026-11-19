package app.pulse4.ui

import app.pulse4.domain.Provider
import app.pulse4.provider.AreaResult
import app.pulse4.provider.AreaStatus
import app.pulse4.provider.ProviderError
import app.pulse4.provider.RetryAction
import app.pulse4.sharing.Field
import app.pulse4.sharing.RevocationLimit
import app.pulse4.sync.DeliveryState
import app.pulse4.training.ExportLevel
import app.pulse4.training.ProposalStatus

/** Všechny texty, které popisují stav. Stavy říkají jen to, co lze doložit. */
object Labels {
    fun delivery(s: DeliveryState) = when (s) {
        DeliveryState.QUEUED -> "Čeká na spojení"
        DeliveryState.HANDED_TO_TRANSPORT -> "Předáno síti (nepotvrzeno aplikací příjemce)"
        DeliveryState.STORED_IN_PROPAGATION -> "Uloženo v LXMF schránce"
        DeliveryState.ACKED -> "Potvrzeno příjemcem"
        DeliveryState.CANCELLED -> "Zrušeno"
        DeliveryState.SUPERSEDED -> "Nahrazeno novější verzí"
        DeliveryState.EXPIRED_NEEDS_SNAPSHOT -> "Vypršelo / odmítnuto – pošle se nový snapshot"
        DeliveryState.FAILED_PERMANENT -> "Neodesláno – odmítnuto propagation uzlem (neopakuje se)"
    }

    /** Čitelný důvod chyby doručení (kódy z transportní vrstvy přeloží, ostatní nechá). */
    fun deliveryError(reason: String): String = when {
        reason.startsWith(app.pulse4.sync.TransportResult.TOO_LARGE) ->
            "zpráva je větší, než propagation uzel přijímá" +
                (reason.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
        reason.startsWith(app.pulse4.sync.TransportResult.REJECTED_BY_NODE) ->
            "propagation uzel zprávu odmítl (nejspíš překročen limit velikosti uzlu)"
        else -> reason
    }

    fun area(s: AreaStatus) = when (s) {
        AreaStatus.NEVER -> "Zatím nesynchronizováno"
        AreaStatus.OK -> "Aktuální"
        AreaStatus.STALE -> "Zastaralé"
        AreaStatus.ERROR -> "Chyba poslední synchronizace"
    }

    fun export(l: ExportLevel) = when (l) {
        ExportLevel.LOCAL_ONLY -> "Uloženo lokálně"
        ExportLevel.IN_GARMIN_CONNECT -> "Uloženo v Garmin Connect"
        ExportLevel.SCHEDULED -> "Naplánováno"
        ExportLevel.DEVICE_REQUESTED -> "Požadavek na zařízení odeslán (přijetí hodinkami není ověřeno)"
        ExportLevel.DEVICE_VERIFIED -> "Přijetí hodinkami ověřeno"
    }

    fun proposal(s: ProposalStatus) = when (s) {
        ProposalStatus.PENDING -> "Čeká na přijetí vlastníkem"
        ProposalStatus.ACCEPTED -> "Přijato"
        ProposalStatus.REJECTED -> "Zamítnuto"
        ProposalStatus.CONFLICT -> "Konflikt: změna vychází ze starší revize"
    }

    fun provider(p: Provider) = when (p) {
        Provider.WITHINGS_API -> "Withings API"
        Provider.HEALTH_CONNECT -> "Health Connect"
        Provider.MANUAL -> "CSV / ruční"
        Provider.GARMIN_PERSONAL -> "Garmin"
        Provider.GARMIN_EXTENDED -> "Garmin (doplněk)"
    }

    val field: Map<Field, String> = mapOf(
        Field.SLEEP_DURATION to "Spánek: délka", Field.SLEEP_SCORE to "Spánek: skóre", Field.SLEEP_STAGES to "Spánek: fáze",
        Field.BB_LAST to "Body Battery: poslední hodnota", Field.BB_MIN_MAX to "Body Battery: min/max", Field.BB_SERIES to "Body Battery: průběh",
        Field.HR_DAILY to "Tep: denní souhrn", Field.HR_SERIES to "Tep: detailní řada",
        Field.HRV_DAILY to "HRV: denní souhrn", Field.HRV_SERIES to "HRV: detailní řada",
        Field.ACT_SUMMARY to "Aktivita: souhrn", Field.ACT_CHARTS to "Aktivita: vybrané grafy", Field.ACT_ROUTE to "Aktivita: trasa",
        Field.ACT_FULL_RECORD to "Aktivita: úplný záznam", Field.ACT_EXACT_TIME to "Aktivita: přesný čas",
        Field.WEIGHT to "Váha (výchozí vypnuto)", Field.BODY_FAT to "Tělesný tuk (výchozí vypnuto)",
    )

    fun field(f: Field) = field[f] ?: f.name

    /** Omezení odvolání se popisují přesně a bez slibů navíc. */
    fun revocation(l: RevocationLimit) = when (l) {
        RevocationLimit.ALREADY_DELIVERED_CANNOT_BE_RECALLED -> "Už doručená data nelze spolehlivě vzít zpět; příjemci byl odeslán požadavek na odstranění."
        RevocationLimit.MESSAGES_ON_FOREIGN_NODE_MAY_REMAIN -> "Zprávy čekající na cizím uzlu nemusí jít odvolat."
        RevocationLimit.OFFLINE_RECIPIENT_LEARNS_LATER -> "Offline zařízení se o odvolání dozví až po doručení nového stavu."
    }

    fun revocation(ls: Collection<RevocationLimit>) = RevocationLimit.entries.filter { it in ls }.map(::revocation)

    /** Chyba poskytovatele srozumitelně; žádné vymyšlené „chyba serveru“, když jde o konfiguraci. */
    fun error(e: ProviderError) = when (e) {
        is ProviderError.Unauthorized -> "Přihlášení nebo klíč už neplatí – vložte nový klíč / přihlaste se znovu."
        is ProviderError.Forbidden -> "Služba odmítla přístup (403). Nic se neopakuje."
        is ProviderError.RateLimited -> e.retryAfterS?.let { "Limit služby, další pokus za $it s." } ?: "Limit služby, zkusí se později."
        is ProviderError.Network -> "Síť není dostupná."
        is ProviderError.MfaRequired -> "Je potřeba kód MFA."
        is ProviderError.NotConfigured -> "Není nakonfigurováno: ${e.what}"
        is ProviderError.Server -> "Chyba služby (${e.code})."
        is ProviderError.Timeout -> "Odpověď nedorazila; výsledek je nejistý."
    }

    /** UX-5: Lidská zpráva pro chyby přihlášení bez technických detailů (`e::class.simpleName` / `e.message`). */
    fun loginError(e: Throwable): String = when (e) {
        is ProviderError.MfaRequired -> "Garmin vyžaduje dvoufázový kód – zadejte ho do pole Ověřovací kód a potvrďte."
        is ProviderError.Unauthorized -> "Neplatný e-mail, heslo nebo ověřovací kód. Zkontrolujte údaje a zkuste to znovu."
        is ProviderError.Forbidden -> "Přístup byl službou odepřen. Zkuste se přihlásit později."
        is ProviderError.RateLimited -> e.retryAfterS?.let {
            "Příliš mnoho pokusů o přihlášení. Zkuste to znovu za $it s."
        } ?: "Příliš mnoho pokusů o přihlášení. Počkejte prosím několik minut a zkuste to znovu."
        is ProviderError.Network -> "Nepodařilo se připojit ke službě. Zkontrolujte připojení k internetu a zkuste to znovu."
        is ProviderError.Timeout -> "Spojení se službou vypršelo. Zkuste to znovu."
        is ProviderError.NotConfigured -> "Přihlášení není správně nastaveno. Zkontrolujte údaje účtu."
        is ProviderError.Server -> "Služba je dočasně nedostupná. Zkuste to prosím za chvíli."
        is java.net.UnknownHostException, is java.net.ConnectException ->
            "Nepodařilo se připojit k internetu. Zkontrolujte síť a zkuste to znovu."
        is java.net.SocketTimeoutException ->
            "Spojení vypršelo. Zkuste to znovu."
        else -> "Přihlášení se nezdařilo. Zkontrolujte údaje a připojení a zkuste to znovu."
    }

    /** Převod stabilního kódu chyby synchronizace uloženého v `AreaState.lastError` na čitelný český text. */
    fun syncError(code: String): String = when (code) {
        "Unauthorized" -> "Přihlášení nebo klíč už neplatí – přihlaste se znovu."
        "Forbidden" -> "Služba odmítla přístup (403)."
        "RateLimited" -> "Limit požadavků služby, zkusí se později."
        "Network" -> "Síť není dostupná."
        "MfaRequired" -> "Vyžadováno dvoufázové ověření (MFA)."
        "NotConfigured" -> "Chybí ověřená konfigurace."
        "Server" -> "Chyba serveru služby."
        "Timeout" -> "Spojení vypršelo."
        else -> code
    }

    fun action(a: RetryAction) = when (a) {
        RetryAction.RefreshTokenOnce -> "Obnovuje se přístup"
        RetryAction.NeedNewApiKey -> "Je potřeba nový klíč / nové přihlášení"
        is RetryAction.WaitAndRetry -> "Zkusí se znovu za ${a.delayMs / 1000} s"
        RetryAction.ShowForbidden -> "Přístup odepřen (403)"
        RetryAction.PauseForMfa -> "Čeká na kód MFA"
        RetryAction.ShowNetworkUnavailable -> "Síť není dostupná"
        RetryAction.ShowNotConfigured -> "Chybí ověřená konfigurace"
    }

    fun result(r: AreaResult?) = when (r) {
        null -> "Nespuštěno"
        is AreaResult.Success -> "Staženo ${r.saved} záznamů"
        is AreaResult.Deferred -> "Odloženo (limit služby)"
        is AreaResult.NeedsUser -> action(r.action)
        is AreaResult.Failed -> error(r.error)
        AreaResult.Skipped -> "Přeskočeno (čeká se na odložení)"
    }
}
