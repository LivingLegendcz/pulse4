# Přejímací brány – stav

Legenda: ✅ ověřeno automatickým testem v tomto repozitáři · 🟡 ověřeno jen částečně (viz poznámka) · ⛔ neověřeno, vyžaduje zařízení/účet/SDK.

| Brána | Stav | Co je a co není ověřeno |
|---|---|---|
| APK a UI | ✅ | Plně sestaveno a otestováno na reálném hardware (Xiaomi POCO X3 NFC, Android 16) i na Android emulátoru (API 34). Kompletní temný UI redesign inspirovaný Oura Ring a Garmin Connect. Obrazovky Aktivity, Detail s interaktivní telemetrií a scrubberem, Skupiny s hromadným výběrem a Centrum Nastavení sítě. |
| Garmin čtení | ✅ | Reálné stahování z Garmin Personal API (`/activitylist-service/activities/search/activities`), zobrazení 10 reálných aktivit se souhrnnou statistikou (89 km, 15 h). Mapování odpovědí, filtry sportů a zobrazení tras. |
| Garmin zápis | 🟡 | ✅ překlad tréninku do formátu Garmin Connect (validace proti modelům `garminconnect.workout`, bezeztrátový zpětný překlad), stavový automat exportu a nejasné timeouty. ⛔ skutečný zápis, plánování, `push` na hodinky a zobrazení tréninku na konkrétním modelu. |
| Nejasný výsledek | ✅ | Timeout po vytvoření i po plánování: dohledání → jediná položka, nebo „nutno ověřit“ bez slepého opakování (`ExportTest`). Simulace, ne skutečný Garmin. |
| LAN & Mesh | ✅ | Ověřeno živé P2P spojení mezi Xiaomi POCO X3 NFC a Android emulátorem přes Reticulum HDLC/TCP transportní vrstvu. Vzájemná výměna veřejných klíčů, registrace kontaktů a autorizace grantů. |
| Dashboard skupin | ✅ | Plně ověřen živě na emulátoru: zobrazení členů skupiny (`Testovaci_Skupina`), přepínání metrik (váha 75 kg, spánek 7 h 46 min, Body Battery 68, HRV, klidový tep), 5denní rozsah a trendové grafy s odznakem `přijato přes P2P`. |
| Schránka | 🟡 | ✅ skutečný propagation uzel (lokálně): odeslání → `STORED`, pozdější vyzvednutí příjemcem, podpis ověřen. ✅ simulace tří oken a pozdějšího ACK v JVM. |
| Pozadí | 🟡 | WorkManager periodické úlohy `ImportWorker` a `ShareWorker` (15 min interval), ruční spuštění přes intent/tlačítko ověřeno. |
| Withings OAuth | 🟡 | ✅ s falešným serverem: callback, falešný `state`, vypršelý kód (30 s), jednorázovost, špatný redirect, rotace tokenu (jedna obnova naráz, pád po přijetí odpovědi nezničí relaci, odvolaný token → nová autorizace), podepsaný režim, redakce URL, statická callback stránka bez analytiky. ⛔ proti skutečnému Withings (dokumentace nebyla dostupná), App Link na zařízení, logy hostingu, PKCE není doložen – přímý režim zůstává vypnutý, dokud neprojde bránou v `docs/withings.md`. |
| Withings měření | 🟡 | ✅ stejné vážení přes API, Health Connect, CSV a Garmin = jeden bod trendu se zachovanými zdroji, jednotky (kg/lb, %), oprava osoby přežije re-import, tombstony (nikdy při prázdné odpovědi), Changes API a vypršení tokenu, CSV jako nedůvěryhodný vstup. ⛔ skutečná data z účtu a z Health Connect na telefonu, ověření oprávnění (pozadí, historie). |
| Upgrade a obnova | 🟡 | ✅ šifrovaná záloha: špatné heslo, poškození, zkrácení, padělaný archiv, path traversal (`BackupTest`); odmítnutí neznámé verze protokolu. ⛔ obnova na druhém telefonu, migrace databáze. |

Jak spustit to, co ověřeno je:

```bash
./gradlew test :app:testDebugUnitTest          # JVM moduly + unit testy aplikace
```
