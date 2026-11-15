# Pulse4 – Release Pipeline & Konektory (Withings / Garmin)

Tento dokument shrnuje architekturu CI/CD publikace do Google Play a mechanismy trvalé synchronizace pro Withings a Garmin bez třetích stran.

---

## 1. CI/CD a Release Pipeline pro Google Play

Konfigurace je v [`.github/workflows/android-build.yml`](../.github/workflows/android-build.yml) a [`.github/dependabot.yml`](../.github/dependabot.yml).

### Pravidla vydávání nové verze
* **Tag:** `vMAJOR.MINOR.PATCH` (např. `v0.1.6`)
* **Název GitHub Release:** přesně `Pulse4 MAJOR.MINOR.PATCH` (např. `Pulse4 0.1.6`)
* **Automatický výpočet verze na runneru:**
  * `versionName` = `MAJOR.MINOR.PATCH` (např. `0.1.6`)
  * `versionCode` = `MAJOR * 1_000_000 + MINOR * 1_000 + PATCH` (např. `1006`)
  * Změna verze v `app/build.gradle.kts` se provádí pouze na runneru při buildu (necommituje se zpět do Gitu).
* **Poznámky k vydání (`whatsnew-cs-CZ`):**
  * Text z těla GitHub Release se automaticky ořezává na **max. 500 znaků včetně koncového `\n`** (limit Google Play API).

### Fronta běhů (Concurrency)
* `pulse4-ci`: pro `push` a `pull_request` (`cancel-in-progress: false` – běhy se řadí postupně za sebou, aby nevznikaly kolize).
* `pulse4-google-play`: pro `release` (`cancel-in-progress: false` – publikace do Google Play běží vždy jen jedna).
* **Dependabot:** nastaven na `monthly`, `open-pull-requests-limit: 1` a seskupený do společných PR (`groups`), aby nezahltil frontu akcí.

### Ověřovací a bezpečnostní kroky
1. **`verify` job:**
   * `./gradlew test :app:assembleDebug :app:lintDebug` (unit testy všech 12 modulů + debug APK + Android Lint).
2. **`publish` job** (pouze pokud `verify` projde a tag je `vX.Y.Z` s názvem `Pulse4 X.Y.Z`):
   * **Google Play Version Guard:** přes OIDC (Workload Identity Federation, bez statických JSON klíčů) ověří nejvyšší nahraný `versionCode` v Google Play a zamítne duplicitní/nižší číslo.
   * **Podpis a ověření AAB:** obnoví upload keystore ze GitHub Secrets do `$RUNNER_TEMP`, sestaví `:app:bundleRelease`, ověří podpis přes `jarsigner -verify` a existenci `mapping.txt`.
   * **Publikace:** nahraje podepsaný `.aab` a `mapping.txt` na track `internal`.

---

## 2. Withings – Trvalé automatické udržování relace

Veškerá komunikace probíhá přímo mezi telefonem a servery Withings (`account.withings.com`, `scalews.withings.com`) bez jakékoliv vlastní proxy nebo třetí strany.

* **HTTP/2 `Set-Cookie` oprava ([`WebConnector.kt`](../connector-withings/src/main/kotlin/app/pulse4/withings/WebConnector.kt)):**
  * Hlavička `Set-Cookie` se čte bez ohledu na velikost písmen (`ignoreCase = true`), protože HTTP/2 vrací `set-cookie` malými písmeny.
  * Proaktivní volání `session?action=extend` probíhá každých 30 minut.
* **Trvalý šifrovaný Cookie Vault ([`WithingsDirectAuth.kt`](../app/src/main/kotlin/app/pulse4/android/di/WithingsDirectAuth.kt)):**
  * Kompletní cookies (včetně dlouhodobých důvěryhodných cookies zařízení a SSO) se ukládají do `SecretStore` (`withings.web.cookiejar`, šifrováno Android Keystore).
  * Při obnově se maže pouze expirovaný `session_key` a dočasné workflow cookies; důvěryhodné cookies zůstávají zachovány.
* **3stupňové tiché obnovení v `renewSession()` ([`WeightServices.kt`](../app/src/main/kotlin/app/pulse4/android/di/WeightServices.kt)):**
  1. `webClient.extendSession(...)` (rychlé prodloužení přes `scalews.withings.com`).
  2. `directAuth.trySilentSsoRefresh()` (tiché SSO přes dlouhodobé cookies na `account.withings.com` – funguje i bez hesla pro účty přihlášené 8místným kódem / Google / Apple).
  3. `directAuth.loginPassword(...)` se zachováním důvěryhodných cookies (bez opětovného vyžadování 2FA).
  * Celý proces je chráněn zámkem `renewLock` a napojen i do `WithingsWebFetcher.refreshSession()`.

---

## 3. Garmin – SSO přihlášení a synchronizace

* **Robustní SSO & MFA ([`ExtendedGarminApi.kt`](../app/src/main/kotlin/app/pulse4/android/reticulum/ExtendedGarminApi.kt)):**
  * Parser všech `<input type="hidden">` a `<meta name="_csrf">` nezávislý na pořadí atributů.
  * Všechny HTTP odpovědi jsou bezpečně uzavírány přes `.use {}`.
  * Podpora nativních `GarminExtTokens` i Garth Base64 dumpů (`W3...`).
* **Synchronizace metrik a aktivit ([`Services.kt`](../app/src/main/kotlin/app/pulse4/android/di/Services.kt), [`garmin_endpoints.json`](../app/src/main/assets/garmin_endpoints.json)):**
  * `maxRangeDays: 27` u endpointů s 28denním limitem (`STEPS`, `STRESS`, atd.), aby rozsah `{from}..{to}` nikdy nepřekročil 28 kalendářních dní včetně.
  * `smartHttp.get` předává reálné HTTP stavy (`401`, `403`, `429`, síťové výjimky) a `404`/`204` převádí na čistou prázdnou odpověď.
  * Dvoufázový import aktivit: rychlé uložení až 1000 souhrnů v jedné Room transakci + okamžité stažení FIT tras pro posledních 15 aktivit; starší trasy se dotahují na vyžádání (`ensureActivityTrack`).

---

## 4. Zobrazení verze v aplikaci

* **Nastavení ([`SettingsHubScreen.kt`](../app/src/main/kotlin/app/pulse4/android/ui/SettingsHubScreen.kt)):** verze v hlavičce konzole (`vX.Y.Z`) a karta *O aplikaci · Sestavení* (po klepnutí zkopíruje diagnostiku do schránky).
* **Hlavní obrazovka ([`PersonalScreens.kt`](../app/src/main/kotlin/app/pulse4/android/ui/PersonalScreens.kt)):** decentní patička `Pulse4 vX.Y.Z (versionCode)` na konci obrazovky *Dnes*.
