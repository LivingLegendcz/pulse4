# Pulse4

Osobní Android aplikace pro čtyři lidi: data z Garminu v telefonu, dobrovolné sdílení vybraných údajů přes Reticulum/LXMF
a plánování tréninků. **Žádný vlastní aplikační server** – úložiště, přihlášení, fronty i oprávnění žijí v telefonech.
Implementace podle dokumentu *Architektonický návrh a technický posudek* (stav k 28. 10. 2026).

> **Stav: jádro, dosavadní konektor Withings a logika UI jsou hotové a otestované (173 testů). Android aplikace se sestavuje a běží na fyzickém telefonu (Xiaomi POCO X3 NFC, Android 16). Pro Withings nově ověřujeme webové připojení bez developer účtu: čtení dat je doloženo HAR, přihlášení a trvalá synchronizace ještě ne.**
> Prostředí, ve kterém vznikla, nemělo Android SDK ani přístup na `dl.google.com` a `chaquo.com`. Přesný rozpis je v
> [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md). Garmin Personal API endpointy zde **nejsou vymyšlené** – viz
> [`docs/garmin-endpoints.md`](docs/garmin-endpoints.md).

Repozitář je jen lokální (`git init`, žádný remote, žádný commit ani push).

## Struktura

| Modul | Obsah | Testy |
|---|---|---|
| `domain` | entity, jednotky, časová pásma a DST, deterministické klíče importu, propojení duplicit (váha Withings↔Garmin) | 7 |
| `analytics` | pohybový čas, převýšení s hysterezí, čas v zónách, nejlepší úseky, downsampling se zachováním extrémů, osobní rozmezí | 7 |
| `sharing-policy` | pozitivní seznam polí, projekce sestavená nově (ne „schování polí“), zóny soukromí, odvolání s popisem omezení, členství | 11 |
| `sync-protocol` | obálka, příjem (autor = ověřený odesílatel, hash, schéma, oprávnění), ACK po commitu, odolný outbox, přílohy, párování, plánovač projekcí | 29 |
| `provider-router` | mapa schopností bez skrytého přepínání, politika 401/403/429, kurzor až po uložení dávky, stav čerstvosti po oblastech | 8 |
| `connector-garmin-personal` | konektor řízený konfigurací; neověřený endpoint se nevolá | 13 |
| `connector-withings` | OAuth bez serveru (state, 30s kód, App Link), rotace tokenů, `getmeas`, tombstony, CSV, Health Connect mapování | 32 |
| `training` | model a validace tréninků, revize plánu, oprávnění, konflikty, export do Garminu jako fronta kroků | 25 |
| `fit-import` | oficiální Garmin FIT SDK, odolné vůči poškozeným souborům | 3 |
| `backup` | šifrovaná záloha (PBKDF2 + AES-GCM, kontrolní součty) | 6 |
| `ui-logic` | veškerá rozhodovací logika obrazovek: karty, agregace, graf↔trasa, editor, koncept sdílení, dashboard, stavy | 32 |
| `app` | tenké Compose obrazovky nad `ui-logic`, Room + SQLCipher, Keystore, WorkManager, Health Connect, mosty do Pythonu (Chaquopy) | — |
| `app/src/main/python` | `pulse4_rns` (Reticulum/LXMF), `pulse4_garmin` (python-garminconnect) | 8 + scénář |

JVM moduly se sestaví a otestují bez Android SDK (`app` se do buildu zahrne jen s `ANDROID_HOME` nebo `local.properties`).

## Klíčová rozhodnutí

- **Filtrace před odesláním.** `Projector` skládá odesílaný objekt jen z polí, která jsou v grantu *a* v datech; náhled ukazuje přesně tytéž bajty, které půjdou do fronty. Nová metrika se sama nezveřejní.
- **Stavy doručení říkají jen to, co lze doložit.** „Potvrzeno příjemcem“ je pouze aplikační ACK; předání síti a uložení do schránky jsou zvláštní stavy.
- **ACK po commitu.** Změna a záznam přijetí jsou v jedné transakci; ztracené ACK vyvolá opakování, které se znovu potvrdí, ale neaplikuje dvakrát.
- **Export do Garminu nikdy neopakuje naslepo.** Po timeoutu se výsledek dohledá (značka v názvu); nejasný stav zastaví další zápis. Cizí tréninky se nemažou. Úprava přímo v Garmin Connect se porovná, nepřepíše.
- **Oprávnění se posuzují při zpracování u vlastníka**, takže stará offline změna po odvolání práva neprojde.
- **Tajemství.** Heslo Garminu se neukládá; tokeny, osobní API klíč a soukromá Reticulum identita jsou zapečetěné klíčem v Android Keystore, mimo zálohy. Python nezapisuje identitu ani tokeny na disk.
- **Mosty jsou pull.** Kotlin čte události z Pythonu přes `drain_events()`; předává se jen malé verzované JSON DTO.

## Withings a UI

- **Withings** (`docs/withings.md`): nový směr je přihlášený web bez developer účtu, secretu a vlastního serveru. HAR potvrzuje čtení obou profilů, celého složení těla a EKG;
  získání a životnost webové relace zbývá ověřit. Současný konektor umí jen váhu a tuk; Health Connect a CSV zůstávají alternativami s omezeným rozsahem. Stejné vážení přes víc cest
  je jeden bod trendu; nejednoznačné měření (jiná osoba) do trendu nevstupuje, dokud je uživatel nepotvrdí; chybné lze označit a přežije re-import.
  Statická callback stránka a `assetlinks.json` v `docs/withings-applink/` patří k původnímu OAuth návrhu.
- **UI**: obrazovky Dnes, Metriky, Aktivity (+ detail s propojeným grafem/trasou a osou čas/vzdálenost), Tréninky, Skupiny (kontakty, sdílení s náhledem, dashboard),
  Tělo, Připojení, Soukromí. Rozhodování je v `ui-logic` (testováno), Compose kód je jen tenká vrstva. Compose artefakty nebyly dostupné, takže **UI nebylo
  zkompilováno proti skutečnému Compose ani vykresleno**; `tools/kotlin-typecheck` ověřuje aspoň Kotlin a volání do vlastních modulů proti stubům.

## Co v této verzi není

Aplikační import aktivit z oficiálního API (kvůli chybějícím endpointům jde zatím ručně přes FIT), offline mapové balíčky, Bluetooth transport,
silové tréninky, více zařízení na osobu, iOS. Editor tréninků umí běžný krok a intervalový blok (N× práce → odpočinek); obecné vnořené opakování
v UI není. Dashboard čte denní projekce (`daily`); sdílené aktivity se zobrazují surově.

## Než poprvé sestavíte `app`

1. Doplňte endpointy podle `docs/garmin-endpoints.md`.
2. Pro Withings projděte aktuální stav v `docs/withings.md`. Nový webový konektor ještě není implementován; `-Ppulse4.withingsHost` se týká pouze původního OAuth režimu.
3. Ověřte a zvyšte verze v `app/build.gradle.kts` (AGP, KSP – musí sedět s Kotlinem, Compose BOM, Room, WorkManager, Chaquopy). Verze jsem volil podle znalostí, ne podle dostupných repozitářů.
4. Uzamkněte Python závislosti i s hashi (`pip install` v `chaquopy {}` je pro vývoj) a ověřte, že `curl_cffi` wheel funguje v Chaquopy a na 16KB stránkách.
5. Spusťte přejímací brány z `docs/ACCEPTANCE.md` na skutečných telefonech a hodinkách.
