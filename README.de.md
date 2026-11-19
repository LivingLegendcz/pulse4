<div align="center">

# Pulse4

**Datenschutzorientierter, serverloser persönlicher Fitness-, Biometrie- und Aktivitäts-Hub für Android**

[![English](https://img.shields.io/badge/English-232A3B?style=for-the-badge)](README.md)
[![Čeština](https://img.shields.io/badge/Čeština-232A3B?style=for-the-badge)](README.cs.md)
[![Deutsch](https://img.shields.io/badge/Deutsch-0284C7?style=for-the-badge)](README.de.md)
[![Español](https://img.shields.io/badge/Español-232A3B?style=for-the-badge)](README.es.md)
[![Français](https://img.shields.io/badge/Français-232A3B?style=for-the-badge)](README.fr.md)
[![简体中文](https://img.shields.io/badge/简体中文-232A3B?style=for-the-badge)](README.zh.md)

---

[![Android](https://img.shields.io/badge/Plattform-Android%2014%2B-3DDC84.svg?style=flat&logo=android&logoColor=white)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF.svg?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Tests](https://img.shields.io/badge/Unit--Tests-349%2B%20Bestanden-10B981.svg?style=flat)](app/src/test)
[![Lizenz](https://img.shields.io/badge/Lizenz-Apache%202.0-blue.svg?style=flat)](LICENSE)

</div>

Pulse4 ist eine private Android-Anwendung zur Zusammenführung von Körpermetriken, sportlichen Aktivitäten und Regenerationsdaten aus den Ökosystemen von **Garmin** und **Withings**. Die App basiert auf dem Grundsatz der **Zero Cloud Dependency** – es existieren weder ein zentraler Server noch Abonnements. Sämtliche sensiblen Daten, verschlüsselte Datenbanken (SQLCipher) und Authentifizierungstoken verbleiben ausschließlich auf den Endgeräten der Benutzer.

Die freiwillige Weitergabe ausgewählter Werte zwischen Familienmitgliedern oder Sportgruppen erfolgt dezentral über das kryptografische Mesh-Netzwerk **Reticulum / LXMF**.

---

## Hauptfunktionen

### 1. Withings-Ökosystem (Waagen & Gesundheitssensoren)
- **Direkte Web-Anbindung ohne Entwicklerkonto:** Vollständige Synchronisation über offizielle Web-Sitzungsendpunkte ohne monatliche API-Gebühren oder externe Callback-Server.
- **Umfassende Körperzusammensetzung:** Gewicht, Muskelmasse, Körperfettanteil, Hydratation (Körperwasser), Knochenmineralmasse und viszerales Fett.
- **Segmentale Körperanalyse:** Detaillierte Aufschlüsselung von Muskeln und Fett in Extremitäten und Rumpf (rechter/linker Arm, Rumpf, rechtes/linkes Bein), prozentuale Anteile und bilaterale Symmetrieanalyse.
- **Elektrokardiogramm (EKG / ECG):** Aufzeichnungen der Withings Body Scan und ScanWatch, Sinusrhythmus-Klassifikation vs. Vorhofflimmern (AFib), Herzfrequenz und QTc-Intervallmessung in Millisekunden.
- **Automatischer Session-Keepalive:** Hintergrundverlängerung von Sitzungen und 1-Klick-Rezertifizierung.

### 2. Garmin-Ökosystem (Aktivitäten & Erholung)
- **Erweiterte Biometrie:** Nächtliche Herzfrequenzvariabilität (HRV / rMSSD), Ruhepuls im Schlaf, Body Battery (0–100), Ganztags-Stresslevel, SpO2 und Atemfrequenz.
- **Lauf- & Ausdauer-Telemetrie:** Echtzeit- und potenzielle Ausdauer (Stamina), Laufeffizienzwerte (Schrittfrequenz, vertikale Bewegung, vertikales Verhältnis, Bodenkontaktzeit GCT, Bodenkontaktzeit-Balance L/R), Herzfrequenzzonen und Leistungszustand.
- **Trainings-Editor:** Strukturierte Intervalltrainings planen und direkt an Garmin-Uhren übertragen.

### 3. Moderne Benutzeroberfläche & Hohe Auflösung (Jetpack Compose M3)
- **Hohe Kurven-Granularität (±100 g bis 500 g):** Unabhängige dynamische Skalierung in `MultiSeriesLineChart` mit 0,80 kg Mindestfenster – Schwankungen von 200 g füllen 25 % der Diagrammhöhe und verschwinden nicht in flachen Linien.
- **Präzise Delta-Auslesung in Gramm:** Tagesveränderungen direkt in Gramm (`+200 g`, `-500 g`, `±0 g`) mit interaktivem HUD-Scrubbing.
- **Gestapeltes Körperzusammensetzungsdiagramm:** Gestapelte Flächenansicht (Muskeln $\rightarrow$ Fett $\rightarrow$ Gesamtes Körpergewicht) mit Schnellzugriff auf separate Einzelkarten.
- **Kompakte Dropdown-Filter:** Übersichtliche Leiste mit Mehrfachauswahl-Checkboxen, vordefinierten Sets und Zeitfenstern (7–365 Tage).
- **Aufgeräumte Diagramme ohne Textüberfrachtung:** Erklärungen und Interpretationen erscheinen gebündelt im Dialog über die Info-Schaltfläche `ⓘ`.
- **Atmosphärisches Kartendesign:** Hochwertige Fotomotive für Dashboard-Widgets und Sportarten (Laufen, Gehen, Wandern, Schwimmen, Krafttraining).

### 4. Dezentrales Teilen über Reticulum Mesh
- **Keine Cloud:** Direkte Peer-to-Peer-Übertragung ohne zwischengeschaltete Firmenserver.
- **Ende-zu-Ende-Verschlüsselung (E2EE v3):** Asymmetrische Curve25519/Ed25519-Identitätsschlüssel mit einem ephemeren X25519-Schlüssel des Absenders pro Nachricht (ephemeral-static ECIES, Schutz vergangener Nachrichten bei Kompromittierung des Absenderschlüssels) und minimierten Metadaten im inneren Umschlag.
- **Granulare Freigaben:** Präzise Steuerung, welche Metriken für wen sichtbar sind.

---

## Architektur

```
pulse4/
├── domain/                      # Reine Kotlin-Logik: Einheiten, Zeitzonen, Deduplizierung
├── analytics/                   # Laufeffizienz, Stamina, Höhenprofile, Perzentilbänder
├── connector-garmin-personal/   # Garmin Connect Synchronisation & FIT-Download
├── connector-withings/          # Withings Web Synchronisation, Segmentanalyse, ECG
├── sync-protocol/               # Reticulum / LXMF Protokollschicht, Outbox, ACK
├── sharing-policy/              # Freigaberegeln, Privatsphärezonen, Projektionen
├── ui-logic/                    # Plattformunabhängige Präsentationslogik (JVM-testbar)
├── app/                         # Android-App (Compose M3, Room SQLCipher, Keystore, native RNS)
└── pulse4-vault/                # Projektdokumentation & Obsidian-Wissensbasis
```

---

## Bauanleitung

- **Android Studio:** Ladybug / Meerkat oder neuer
- **JDK:** OpenJDK 17
- **Android SDK:** minSdk 28 (Android 9+) (Target Android 14/15/16)

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# Unit-Tests ausführen
./gradlew testDebugUnitTest

# Debug APK kompilieren
./gradlew assembleDebug

# Auf Gerät installieren
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Lizenz

Lizenziert unter der [Apache 2.0 Lizenz](LICENSE).
