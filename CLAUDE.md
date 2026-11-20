# Pulse4 – agent instructions

## Knowledge vault (mandatory)
Project documentation lives in the Obsidian vault `pulse4-vault/` (symlink to `../pulse4-vault`, a separate git repo on branch `master`, ignored by this repo). Notes are in Czech.

- **Before non-trivial work:** read `pulse4-vault/00 - Přehled & Rozcestník/01 - Stav Projektu a Přejímací Brány.md`, the latest log in `pulse4-vault/03 - Historie & Evoluce Změn/`, and check `pulse4-vault/04 - Slepé Uličky & Zamítnutá Řešení/` so rejected approaches are not repeated. Open audit findings: `pulse4-vault/06 - Analýzy & Audity/` (§17 Audit #5).
- **After each release (`vX.Y.Z`) or larger fix round:** add a new log `03 - Historie & Evoluce Změn/NN - Vývojový Deník - … (Verze X.Y.Z).md` (date, commits, why, what, verification), link it from `00 - Přehled & Rozcestník/00 - INDEX (MOC).md`, and refresh the "Aktuální stav" table in the status note. Commit vault changes in the vault repo.
- Cite commits/sources for facts; mark anything not verified as unverified.

## Build & test
- `JAVA_HOME=.tools/jdk-17` (system JDK 21 lacks the javac toolchain), `ANDROID_HOME=.tools/android-sdk`, adb at `.tools/android-sdk/platform-tools/adb`.
- Run Gradle serially: `flock /tmp/pulse4-gradle.lock ./gradlew --offline --max-workers=2 …` (host has 4 cores).
- Release gate: `test :app:testDebugUnitTest :app:assembleRelease`.
- RNS/LXMF changes: verify against real `rnsd`/`lxmd` via `flock /tmp/pulse4-interop.lock tools/rns-interop/run_interop.sh`, not only `tools/rns_hub.py`.
- More commands: `pulse4-vault/00 - Přehled & Rozcestník/02 - Rychlá Příručka (Quickstart & Příkazy).md`.
