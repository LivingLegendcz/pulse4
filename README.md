<div align="center">

# Pulse4

**Privacy-first, serverless personal fitness, biometric & activity hub for Android**

[![English](https://img.shields.io/badge/English-0284C7?style=for-the-badge)](README.md)
[![Čeština](https://img.shields.io/badge/Čeština-232A3B?style=for-the-badge)](README.cs.md)
[![Deutsch](https://img.shields.io/badge/Deutsch-232A3B?style=for-the-badge)](README.de.md)
[![Español](https://img.shields.io/badge/Español-232A3B?style=for-the-badge)](README.es.md)
[![Français](https://img.shields.io/badge/Français-232A3B?style=for-the-badge)](README.fr.md)
[![简体中文](https://img.shields.io/badge/简体中文-232A3B?style=for-the-badge)](README.zh.md)

---

[![Android](https://img.shields.io/badge/Platform-Android%209%2B%20(minSdk%2028)-3DDC84.svg?style=flat&logo=android&logoColor=white)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF.svg?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Tests](https://img.shields.io/badge/Unit%20Tests-283%20JVM%20%2B%20110%20App-10B981.svg?style=flat)](app/src/test)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat)](LICENSE)

</div>

Pulse4 is an open-source, private Android application designed to unify body metrics, workouts, and autonomic recovery signals from the **Garmin** and **Withings** ecosystems. Built around the core principle of **Zero Cloud Dependency**, it eliminates central servers, accounts, tracking, and subscription fees. All sensitive health observations, encrypted databases (Room with SQLCipher), and credentials reside strictly on the user's phone.

Voluntary sharing of selected biometrics and workouts between family members or training groups is handled in a decentralized fashion using the cryptographic **Reticulum Mesh / LXMF** network.

---

## Core Highlights

### 1. Withings Ecosystem (Smart Scales & Health Sensors)
- **Direct Web Connectivity:** Direct sync through Withings web session endpoints without requiring paid corporate developer accounts or hosting public OAuth callbacks.
- **Full Body Composition Suite:** Total weight, muscle mass, body fat, total body water (hydration), bone mineral mass, and visceral fat index.
- **Segmental Body Analysis:** Segmental muscle and fat distribution across extremities and trunk (right/left arm, torso, right/left leg), percentage breakdown, and bilateral limb symmetry analysis (% discrepancy).
- **Electrocardiogram (ECG):** 1-lead and 6-lead ECG recordings from Withings Body Scan and ScanWatch devices, sinus rhythm classification vs. Atrial Fibrillation (AFib), recorded heart rate, and corrected QTc interval measurement in milliseconds.
- **Automated Silent Session Keepalive:** Background session extension and one-click rapid session renewal without multi-profile picker confusion.

### 2. Garmin Ecosystem (Activities & Recovery)
- **Deep Biometric Telemetry:** Overnight Heart Rate Variability (HRV / rMSSD), nocturnal resting heart rate (RHR), Garmin Body Battery (0–100), all-day stress scores, SpO2, and respiration rate.
- **Advanced Running & Endurance Analytics:** Real-time & potential Stamina metrics, complete Running Dynamics (cadence, vertical oscillation, vertical ratio, ground contact time GCT, ground contact time balance L/R), heart rate zones, and Performance Condition baseline tracking.
- **Structured Workout Engine (Experimental):** Visual step & interval workout planner with experimental export to Garmin Connect.

### 3. High-Resolution Modern UI (Jetpack Compose Material 3)
- **High-Granularity Curve Scaling (±100g to 500g):** Multi-series line charts (`MultiSeriesLineChart`) feature independent dynamic scaling with a minimum 0.80 kg window, ensuring small ±200 g fluctuations occupy 25% of the chart canvas rather than flattening into invisible single-pixel ribbons.
- **Physical Gram Delta Readout:** Precise day-to-day changes formatted in grams (`+200 g`, `-500 g`, `±0 g`) with touch-scrubbing interactive HUD tooltips.
- **Stacked Weight Composition Area Chart:** Layered area chart showing total body mass stacked with muscle and fat strata under the curve, with quick toggles to expand individual metric cards.
- **Compact Multi-Select Dropdowns:** Unified filter bar featuring multi-checkbox metric selection, preset combinations (*Recovery*, *Heart & ECG*, *Body Comp*), and time window selection (7 to 365 days).
- **Clean Clutter-Free Visuals:** Technical metric explanations and physiological interpretations are placed inside dedicated dialogs triggered via `ⓘ` info buttons.
- **Atmospheric Visual Motifs:** Photorealistic thematic background cards for Today dashboard singlestats and activity types (running, walking, hiking, swimming, strength, cycling).

### 4. Decentralized Mesh Sharing (Reticulum Mesh & LXMF)
- **Serverless P2P Architecture:** Zero external application servers or third-party cloud aggregators.
- **End-to-End Cryptography (E2EE v3):** Curve25519/Ed25519 identity keys with a per-message ephemeral sender X25519 key (ephemeral-static ECIES, protecting past messages if sender's key is compromised) and metadata-minimized inner envelopes ensure message authenticity and confidentiality.
- **Granular Field Permissions:** Users selectively grant specific metric visibility on a per-contact basis (e.g. sharing only recovery metrics with a coach, while sharing full telemetry with family).

---

## Modular Architecture

Pulse4 is designed as a clean modular monorepo isolating business rules from Android-specific SDKs:

```
pulse4/
├── domain/                      # Pure Kotlin domain entities, units, timezones, import deduplication
├── analytics/                   # Running dynamics, stamina algorithms, elevation hysteresis, P25/P75 bands
├── connector-garmin-personal/   # Garmin Connect sync engine, session management, FIT download
├── connector-withings/          # Withings Web sync engine, body composition, segmental values, ECG
├── sync-protocol/               # Reticulum / LXMF encrypted envelope, idempotence, outbox queue, ACK
├── sharing-policy/              # Granular permissions engine, privacy zones, sharing projections
├── ui-logic/                    # Framework-independent screen logic and viewmodels (JVM testable)
├── app/                         # Android application (Compose M3, Room SQLCipher, Keystore, native RNS)
└── pulse4-vault/                # Developer logs, Obsidian knowledge base, architectural records
```

---

## Prerequisites & Building

- **Android Studio:** Ladybug / Meerkat or later
- **JDK:** OpenJDK 17
- **Android SDK:** minSdk 28 (Android 9+), targetSdk 36, compileSdk 37
- **Target Device:** 64-bit ARM (`arm64-v8a`) required for native cryptography libraries.

### Command Line Build:

```bash
# Configure paths for JDK 17 and Android SDK
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# Execute comprehensive test suite
./gradlew test :app:testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug

# Deploy to connected physical device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Security & Privacy Architecture

1. **At-Rest Encryption:** Local SQLite database is encrypted via **SQLCipher** (AES-256). Cryptographic keys are generated with secure random entropy and sealed inside the hardware-backed **Android Keystore** (TEE / StrongBox).
2. **Zero Tracking:** No telemetry, Firebase, Crashlytics, or analytics SDKs are incorporated into the codebase.
3. **Decentralized Networking:** Reticulum peer-to-peer communications use no phone numbers, emails, or static IP addresses.

See the full [Privacy Policy](docs/PRIVACY.md) ([Czech](docs/PRIVACY.cs.md)) and the [open legal and distribution items](docs/PRIVACY_OPEN_ITEMS.md).

---

## License

This project is licensed under the [Apache 2.0 License](LICENSE).
