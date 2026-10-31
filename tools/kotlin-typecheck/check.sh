#!/bin/bash
# Typová kontrola zdrojáků `app/` BEZ Android SDK: Kotlin kompilátor + ručně psané stuby Android/Compose API (viz *-stubs).
# Ověřuje jen správnost Kotlinu a volání do vlastních modulů. NENAHRAZUJE sestavení aplikace: skutečné signatury Compose,
# Room, WorkManager atd. neověřuje (stuby jsou psané podle paměti a shovívavé).
# Použití: ./gradlew assemble && tools/kotlin-typecheck/check.sh
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; G="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"; K=2.4.21
j(){ find "$G/$1" -name "$2" 2>/dev/null | grep -v sources | head -1; }
CE=$(j org.jetbrains.kotlin/kotlin-compiler-embeddable "kotlin-compiler-embeddable-$K.jar")
STD=$(j org.jetbrains.kotlin/kotlin-stdlib "kotlin-stdlib-$K.jar"); SCR=$(j org.jetbrains.kotlin/kotlin-script-runtime "kotlin-script-runtime-$K.jar")
REF=$(find "$G" -name "kotlin-reflect-*.jar" | grep -v sources | head -1); DAE=$(j org.jetbrains.kotlin/kotlin-daemon-embeddable "kotlin-daemon-embeddable-$K.jar")
TR=$(find "$G" -name "trove4j*.jar" | grep -v sources | head -1); AN=$(find "$G" -name "annotations-13.0.jar" | head -1)
COR=$(find "$G" -name "kotlinx-coroutines-core-jvm-*.jar" | grep -v sources | head -1)
SERC=$(find "$G" -name "kotlinx-serialization-core-jvm-1.11.0.jar" | head -1); SERJ=$(find "$G" -name "kotlinx-serialization-json-jvm-1.11.0.jar" | head -1)
SERP=$(find "$G" -name "kotlin-serialization-compiler-plugin-embeddable-$K.jar" | head -1); GARMIN=$(find "$G/com.garmin" -name "fit-*.jar" | grep -v sources | head -1)
MODS=$(ls "$ROOT"/*/build/libs/*.jar | tr '\n' ':'); APP="$ROOT/app/src/main/kotlin/app/pulse4/android"; HERE="$ROOT/tools/kotlin-typecheck"
OUT="$(mktemp -d)"
FILES="$(find "$APP" -name '*.kt' ! -name MapRoute.kt ! -name HealthConnectSource.kt) $(ls "$HERE"/android-stubs/*.kt "$HERE"/compose-stubs/*.kt)"
java -cp "$CE:$STD:$SCR:$REF:$DAE:$TR:$AN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -d "$OUT" -Xplugin="$SERP" \
  -cp "$STD:$COR:$SERC:$SERJ:$GARMIN:$MODS" $FILES 2>&1 | grep -v "Picked up" | tee "$OUT/report.txt" | grep "error:" | head -50 || true
echo "chyb: $(grep -c 'error:' "$OUT/report.txt" || true)"
