# Withings

Dvě nezávislé cesty, obě sdílejí normalizovaný datový model (váha v kg, tělesný tuk v %). Žádná nepotřebuje náš server.

| Cesta | Stav | Pro koho |
|---|---|---|
| **Health Connect** | doporučená, výchozí | stačí aplikace Withings s exportem do Health Connect |
| **Přímé Withings API** | volitelná, **vypnutá**, neověřená | bohatší metriky a historie; vlastní vývojářské přístupy každého člověka |
| **CSV export** | nouzová/referenční | jednorázový import historie |

Dokumentace Withings nebyla při psaní dostupná: adresy, názvy parametrů a hodnoty `attrib` v `WithingsConfig` jsou z paměti a
**je nutné je před zapnutím přímého režimu ověřit**.

## Co je ověřeno testy (`connector-withings`, 30 testů, falešný server)

Platnost kódu, `state` (náhodný, jednorázový, s TTL, špatný state nezruší legitimní přihlášení), výměna kódu do 30 s, špatný
redirect, odmítnutí uživatelem, rotace refresh tokenu (jedna obnova naráz, uložení před použitím, pád po přijetí odpovědi
nezničí relaci, odvolaný token → nová autorizace), podepsaný režim, `getmeas` se stránkováním, tombstony, jednotky, deduplikace
vůči Garminu/Health Connect/CSV, oprava osoby (vyřazení přežije re-import), CSV (kg/lb, BOM, středník, chybné řádky), Health Connect
(origin, Changes API, vypršení tokenu).

## Bezpečnostní brána přímého režimu (musí projít, jinak zůstává vypnuto)

1. Ověřit proti dokumentaci: URL, `requesttoken`/`getnonce`, podpis, `getmeas`, `attrib`, chybové stavy (601, 401, 293).
2. Ověřit, že Withings akceptuje HTTPS App Link jako redirect URI při registraci i autorizaci (vlastní URI schéma/loopback jen po potvrzení).
3. Na zařízení: callback, falešný `state`, vypršelý kód, rotace, pád během obnovy → bez úniku přístupů a bez ztráty nové relace.
4. Statická callback stránka (`docs/withings-applink/`) bez analytiky a třetích stran; ověřit logy hostingu při selhání App Linku
   (URL s kódem se do nich nesmí dostat/uchovat).
5. PKCE není v prověřené referenci Withings doložen: přímý režim proto **není** standardní veřejný mobilní login (RFC 8252).
   `client_secret` v telefonu uživatele omezuje dosah úniku jen na jeho registraci, nedělá z aplikace neextrahovatelného klienta.

## Nastavení

1. Vlastní doména se dvěma statickými soubory z `docs/withings-applink/` (`/.well-known/assetlinks.json` s otiskem podpisového klíče, `/withings/callback/`).
2. Build s `-Ppulse4.withingsHost=<vaše-doména>`; stejnou adresu zaregistrujte ve Withings jako redirect URI.
3. V aplikaci: Tělo → Withings API → vložit `client_id` a `client_secret` → zapnout → Připojit.

## Cena a účty

Bezplatný plán Withings uvádí 10 připojených uživatelů před přezkoumáním aplikace; čtyři lidé jsou v limitu. U nových účtů je vyžadováno
Withings+, u starších platí výjimka (účty dalších členů se posuzují jednotlivě; hranice 12./13. 10. 2026 se v podkladech liší).
Stáří účtu nenahrazuje registraci a OAuth oprávnění.
