<div align="center">

# Pulse4

**Osobní biometrický a fitness hub pro Android s důrazem na soukromí a nezávislost na cloudu**

[![English](https://img.shields.io/badge/English-232A3B?style=for-the-badge)](README.md)
[![Čeština](https://img.shields.io/badge/Čeština-0284C7?style=for-the-badge)](README.cs.md)
[![Deutsch](https://img.shields.io/badge/Deutsch-232A3B?style=for-the-badge)](README.de.md)
[![Español](https://img.shields.io/badge/Español-232A3B?style=for-the-badge)](README.es.md)
[![Français](https://img.shields.io/badge/Français-232A3B?style=for-the-badge)](README.fr.md)
[![简体中文](https://img.shields.io/badge/简体中文-232A3B?style=for-the-badge)](README.zh.md)

---

[![Android](https://img.shields.io/badge/Platform-Android%2014%2B-3DDC84.svg?style=flat&logo=android&logoColor=white)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF.svg?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Tests](https://img.shields.io/badge/Unit%20Tests-173%20Passed-10B981.svg?style=flat)](app/src/test)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat)](LICENSE)

</div>

Pulse4 je osobní a rodinná Android aplikace pro sjednocení tělesných metrik, sportovních aktivit a regenerace z ekosystémů **Garmin** a **Withings**. Funguje na principu **Zero Cloud Dependency** – neexistuje žádný centrální server ani předplatné. Všechna citlivá data, šifrované databáze (SQLCipher), tokeny v Android Keystore i oprávnění žijí výhradně v zařízeních uživatelů.

Sdílení vybraných údajů mezi členy rodiny či sportovní skupiny probíhá decentralizovaně přes kryptografickou síť **Reticulum Mesh / LXMF**.

---

## Hlavní funkce

### 1. Ekosystém Withings (Váhy & Zdraví)
- **Přímé připojení bez vývojářského účtu:** Plnohodnotná synchronizace přes oficiální webové rozhraní bez nutnosti platit API poplatky nebo hostovat callback server.
- **Kompletní složení těla:** Tělesná hmotnost, svalová hmota, tělesný tuk, hydratace (voda v těle), minerální kostní hmota a viscerální tuk.
- **Segmentální analýza těla:** Detailní rozpad svalů a tuku v končetinách a trupu (pravá/levá ruka, trup, pravá/levá noha), procentuální podíly z celku a výpočet bilaterální symetrie končetin (rozdíl v %).
- **Elektrokardiogram (ECG):** Záznamy z elektrod Withings Body Scan a hodinek ScanWatch, klasifikace srdečního rytmu (sinusový rytmus vs. fibrilace síní AFib), srdeční tep a délka intervalu QTc v milisekundách.
- **Spolehlivý tichý keepalive:** Automatické prodlužování webové relace na pozadí a 1-klikové rychlé obnovení bez nutnosti procházet výběry profilů.

### 2. Ekosystém Garmin (Aktivity & Regenerace)
- **Komplexní biometrie:** Noční variabilita tepu (HRV / rMSSD), klidová srdeční frekvence ve spánku, Body Battery (0–100), celodenní fyziologický stres, SpO2 a dechová frekvence.
- **Tréninky a telemetrie:** Detailní telemetrie běžeckých i cyklistických aktivit – okamžitá a potenciální výdrž (Stamina), běžecká dynamika (kadence, vertikální oscilace, vertikální poměr, doba kontaktu se zemí GCT, vyvážení L/P nohy), zóny srdečního tepu a Performance Condition.
- **Editor a export tréninků:** Plánování strukturovaných fázových tréninků a jejich přímý export do hodinek Garmin.

### 3. Vysoké rozlišení a nová vizualizace (Jetpack Compose M3)
- **Vysoká granularita křivek (±100 g až 500 g):** Nezávislé škálování křivek (`MultiSeriesLineChart`) s minimálním oknem 0,80 kg – denní 200g změny tvoří 25 % výšky grafu a neztrácí se v ploché lince.
- **Odečet delty v gramech:** Okamžité vyčíslení změn oproti předchozímu měření přímo v gramech (`+200 g`, `-500 g`, `±0 g`) a interaktivní dotykový HUD.
- **Skládaný graf složení váhy:** Společný plošný graf celkové váhy s vrstvami svalstva a tuku pod křivkou, s možností přepnutí na detailní samostatné karty.
- **Kompaktní multi-dropdown filtry:** Pohodlný výběr metrik s checkboxy, hotové kombinace (Regenerace, Srdce & ECG, Složení těla) a volba časového okna (7d–365d).
- **Čisté grafy bez dlouhých textů:** Veškeré popisy a interpretace metrik jsou přístupné pod informačním tlačítkem `ⓘ`, plátna grafů zůstávají přehledná.
- **Atmosférický vizuální design:** Dynamická fotopozadí na kartách přehledu Dnes i v aktivitách (běh, chůze, turistika, plavání, síla).

### 4. Decentralizované sdílení (Reticulum Mesh)
- **Zero Cloud Architecture:** Žádná centrální databáze ani server třetí strany.
- **Kryptografické P2P šifrování:** Přenos vybraných metrik a souhrnů probíhá šifrovaně přes protokol LXMF přímo mezi zařízeními.
- **Granulární oprávnění:** Uživatel přesně určuje, jaké metriky s kým sdílí (např. pouze regenerace pro trenéra, kompletní souhrn pro partnera).

---

## Architektura projektu

Projekt je koncipován jako modulární monorepo s čistým oddělením doménové logiky od Android frameworku:

```
pulse4/
├── domain/                      # Ryzí Kotlin: biometrické entity, metriky, jednotky, časová pásma, dedup
├── analytics/                   # Běžecká dynamika, stamina, pohybový čas, výškové profily, P25/P75 pásma
├── connector-garmin-personal/   # Garmin Connect sync engine, správa relací, stahování FIT
├── connector-withings/          # Withings Web sync engine, měření váhy, segmentální složení, ECG
├── sync-protocol/               # Reticulum / LXMF šifrovaná obálka, idempotence, outbox fronta, ACK
├── sharing-policy/              # Granulární bezpečnostní pravidla, zóny soukromí, projekce sdílení
├── ui-logic/                    # Prezentační logika nezávislá na frameworku (testovatelná na JVM)
├── app/                         # Android aplikace (Jetpack Compose M3, Room SQLCipher, Keystore, Chaquopy)
└── pulse4-vault/                # Vývojová dokumentace, Obsidian vault, architektonické rozhodnutí
```

---

## Požadavky a sestavení

- **Android Studio:** Ladybug / Meerkat (nebo novější)
- **JDK:** OpenJDK 17
- **Android SDK:** API 34+ (Target Android 14/15/16)
- **Fyzické testovací zařízení:** Vyžadována podpora 64-bit ARM (arm64-v8a) pro kryptografické nativní knihovny.

### Sestavení z příkazové řádky:

```bash
# Nastavení cesty k JDK 17 a Android SDK
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# Spuštění unit testů (173 testů napříč všemi moduly)
./gradlew testDebugUnitTest

# Sestavení debug APK
./gradlew assembleDebug

# Instalace na připojené zařízení
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Bezpečnost & Soukromí

1. **Šifrování v klidu:** Databáze Room je šifrována pomocí **SQLCipher** (AES-256). Šifrovací klíč je generován kryptograficky bezpečným generátorem a uložen v **Android Keystore** chráněném hardwarem (TEE / StrongBox).
2. **Žádný sběr telemetrie:** Aplikace neobsahuje Firebase Analytics, Crashlytics ani žádný jiný sledovací SDK kód.
3. **P2P transport:** Síťová komunikace přes Reticulum nepoužívá IP adresy ani telefonní čísla; identity jsou odvozeny z asymetrických ed25519 klíčů.

---

## Licence

Projekt je licencován pod licencí [Apache 2.0](LICENSE).
