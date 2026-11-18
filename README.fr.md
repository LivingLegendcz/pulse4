<div align="center">

# Pulse4

**Plateforme personnelle de suivi biométrique et sportif pour Android, privée et sans serveur**

[![English](https://img.shields.io/badge/English-232A3B?style=for-the-badge)](README.md)
[![Čeština](https://img.shields.io/badge/Čeština-232A3B?style=for-the-badge)](README.cs.md)
[![Deutsch](https://img.shields.io/badge/Deutsch-232A3B?style=for-the-badge)](README.de.md)
[![Español](https://img.shields.io/badge/Español-232A3B?style=for-the-badge)](README.es.md)
[![Français](https://img.shields.io/badge/Français-0284C7?style=for-the-badge)](README.fr.md)
[![简体中文](https://img.shields.io/badge/简体中文-232A3B?style=for-the-badge)](README.zh.md)

---

[![Android](https://img.shields.io/badge/Plateforme-Android%2014%2B-3DDC84.svg?style=flat&logo=android&logoColor=white)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF.svg?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Tests](https://img.shields.io/badge/Tests%20Unitaires-319%2B%20Réussis-10B981.svg?style=flat)](app/src/test)
[![Licence](https://img.shields.io/badge/Licence-Apache%202.0-blue.svg?style=flat)](LICENSE)

</div>

Pulse4 est une application Android open source conçue pour unifier les métriques corporelles, les activités sportives et les signaux de récupération issus des écosystèmes **Garmin** et **Withings**. Fondée sur le principe de **Zero Cloud Dependency**, elle élimine tout serveur central, suivi ou abonnement payant. Toutes les données sensibles, bases de données chiffrées (SQLCipher) et identifiants résident exclusivement sur l'appareil de l'utilisateur.

Le partage décentralisé d'informations choisies entre membres d'une famille ou sportifs s'effectue via le réseau maillé chiffré pair-à-pair **Reticulum Mesh / LXMF**.

---

## Fonctionnalités Principales

### 1. Écosystème Withings (Balances & Santé)
- **Connexion web directe sans compte développeur tiers :** Synchronisation complète via l'interface web officielle sans redevance d'API ni serveur de callback externe.
- **Composition corporelle intégrale :** Poids, masse musculaire, masse grasse, hydratation (eau corporelle totale), masse osseuse et graisse viscérale.
- **Analyse corporelle segmentaire :** Répartition détaillée des muscles et de la graisse par membres et tronc (bras droit/gauche, torse, jambe droite/gauche), pourcentages et calcul de symétrie bilatérale (écart en %).
- **Électrocardiogramme (ECG) :** Tracés issus des balances Withings Body Scan et montres ScanWatch, classification du rythme (rythme sinusal vs fibrillation auriculaire AFib), fréquence cardiaque et mesure de l'intervalle QTc en millisecondes.
- **Maintien de session silencieux (keepalive) :** Renouvellement automatique en arrière-plan et reconnexion simplifiée en 1 clic.

### 2. Écosystème Garmin (Activités & Récupération)
- **Biométrie approfondie :** Variabilité de fréquence cardiaque nocturne (VRC / HRV / rMSSD), fréquence cardiaque au repos durant le sommeil, Body Battery (0–100), niveau de stress, SpO2 et rythme respiratoire.
- **Télémétrie de course & endurance :** Endurance en temps réel et potentielle (Stamina), dynamiques de course avancées (cadence, oscillation verticale, ratio vertical, temps de contact au sol GCT et équilibre G/D), zones cardio et condition de performance.
- **Éditeur d'entraînements :** Planification d'entraînements par intervalles et synchronisation directe avec les montres Garmin.

### 3. Interface Moderne Haute Résolution (Jetpack Compose M3)
- **Haute granularité des courbes (±100 g à 500 g) :** Mise à l'échelle dynamique indépendante (`MultiSeriesLineChart`) avec fenêtre minimale de 0,80 kg : les variations quotidiennes de 200 g occupent 25 % de la hauteur du graphique sans être écrasées.
- **Relevé des deltas en grammes :** Changements quotidiens affichés directement en grammes (`+200 g`, `-500 g`, `±0 g`) avec HUD interactif au toucher.
- **Graphique d'aire empilée de la composition :** Graphique combiné empilé (Muscles $\rightarrow$ Graisse $\rightarrow$ Poids total) avec option de déploiement des cartes individuelles.
- **Menus déroulants compacts à sélection multiple :** Barre de filtres unifiée avec cases à cocher, combinaisons prédéfinies (Récupération, Cœur & ECG, Composition) et périodes (7 à 365 jours).
- **Graphiques épurés sans paragraphes encombrants :** Les explications médicales et physiologiques sont consultables dans un pop-up dédié via le bouton `ⓘ`.
- **Visuels atmosphériques :** Arrière-plans photographiques pour les cartes du tableau de bord Aujourd'hui et les activités (course, marche, randonnée, natation, musculation).

### 4. Partage Décentralisé (Reticulum Mesh)
- **Architecture sans cloud :** Échanges directs pair-à-pair entre téléphones.
- **Chiffrement de bout en bout (E2EE v3) :** Clés d'identité Curve25519/Ed25519 avec une clé éphémère X25519 de l'expéditeur par message (ephemeral-static ECIES, protégeant les messages passés si la clé de l'expéditeur est compromise) et enveloppes internes à métadonnées minimisées.
- **Permissions granulaires :** Contrôle précis des métriques partagées avec chaque contact.

---

## Structure du Projet

```
pulse4/
├── domain/                      # Entités métier en pur Kotlin, déduplication, unités
├── analytics/                   # Algorithmes de course, stamina, profils d'altitude
├── connector-garmin-personal/   # Moteur de synchronisation Garmin Connect et téléchargement FIT
├── connector-withings/          # Moteur de synchronisation Withings Web, composition et ECG
├── sync-protocol/               # Protocole chiffré Reticulum / LXMF, file d'attente outbox
├── sharing-policy/              # Gestion des autorisations et zones de confidentialité
├── ui-logic/                    # Logique de présentation indépendante (testable sur JVM)
├── app/                         # Application Android (Compose M3, Room SQLCipher, Keystore, native RNS)
└── pulse4-vault/                # Documentation de développement et coffre Obsidian
```

---

## Compilation

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# Lancer la suite de tests unitaires
./gradlew testDebugUnitTest

# Compiler l'APK de débogage
./gradlew assembleDebug

# Installer sur l'appareil connecté
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Licence

Distribué sous la [Licence Apache 2.0](LICENSE).
