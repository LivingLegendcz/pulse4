#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK_DIR="${1:-/tmp/pulse4-rns-interop}"
PORT="${INTEROP_PORT:-42429}"
KEEPALIVE_SECS="${KEEPALIVE_TEST_SECS:-50}"
VENV="${RNS_VENV:-/tmp/rnsvenv}"
SEED_HEX="0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/out"

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "Missing Python venv at $VENV (expected rns==1.6.0, lxmf==1.3.0)" >&2
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-/home/kocourkovic/projects/pulse4/.tools/jdk-17}"
export ANDROID_HOME="${ANDROID_HOME:-/home/kocourkovic/projects/pulse4/.tools/android-sdk}"
flock /tmp/pulse4-gradle.lock "$ROOT_DIR/gradlew" --offline :rns-core:jar >/dev/null

RNS_CORE_JAR="$ROOT_DIR/rns-core/build/libs/rns-core.jar"
BCPROV_JAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.bouncycastle/bcprov-jdk18on" -name "bcprov-jdk18on-*.jar" | head -n1)"
COMMONS_COMPRESS_JAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.apache.commons/commons-compress/1.21" -name "commons-compress-1.21.jar" | head -n1)"
KOTLIN_STDLIB="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.21" -name "kotlin-stdlib-2.4.21.jar" | head -n1)"
if [[ -z "$KOTLIN_STDLIB" ]]; then
  KOTLIN_STDLIB="$(find "$HOME/.gradle" -name "kotlin-stdlib-2.*.jar" | head -n1)"
fi

if command -v kotlinc >/dev/null 2>&1; then
  kotlinc "$ROOT_DIR/tools/rns-interop/InteropHarness.kt" \
    -cp "$RNS_CORE_JAR:$BCPROV_JAR:$COMMONS_COMPRESS_JAR:$KOTLIN_STDLIB" \
    -d "$WORK_DIR/out"
else
  KOTLIN_COMPILER_CP="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.21" -name "*.jar" | tr '\n' ':'):"
  KOTLIN_COMPILER_CP+="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-reflect" -name "*.jar" | head -n1):"
  KOTLIN_COMPILER_CP+="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-script-runtime" -name "*.jar" | head -n1):"
  KOTLIN_COMPILER_CP+="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.intellij.deps/trove4j" -name "*.jar" | head -n1):"
  KOTLIN_COMPILER_CP+="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name "kotlinx-coroutines-core-jvm-1.11.0.jar" -o -name "kotlinx-coroutines-core-jvm-1.8.*.jar" -o -name "kotlinx-coroutines-core-jvm-1.7.*.jar" | head -n1):"
  KOTLIN_COMPILER_CP+="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations" -name "*.jar" | head -n1):"
  KOTLIN_COMPILER_CP+="$KOTLIN_STDLIB"
  "$JAVA_HOME/bin/java" -cp "$KOTLIN_COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    "$ROOT_DIR/tools/rns-interop/InteropHarness.kt" \
    -cp "$RNS_CORE_JAR:$BCPROV_JAR:$COMMONS_COMPRESS_JAR:$KOTLIN_STDLIB" \
    -d "$WORK_DIR/out" \
    -no-stdlib
fi

"$VENV/bin/python" "$ROOT_DIR/tools/rns-interop/node.py" "$WORK_DIR/node" "$PORT" "$SEED_HEX" > "$WORK_DIR/node.stdout" 2>&1 &
NODE_PID=$!
trap 'kill $NODE_PID 2>/dev/null || true' EXIT

for _ in $(seq 1 40); do
  if [[ -f "$WORK_DIR/node/info.json" ]]; then break; fi
  sleep 0.25
done

if [[ ! -f "$WORK_DIR/node/info.json" ]]; then
  echo "node.py failed to start:" >&2
  cat "$WORK_DIR/node.stdout" >&2
  exit 1
fi

read -r PROP_DEST ALICE_PUB ALICE_DEST BOB_PUB BOB_DEST PY_TID STAMP_COST STAMP_FLEX < <("$VENV/bin/python" -c '
import json, sys
d = json.load(open(sys.argv[1]))
print(d["prop_dest"], d["alice_pub"], d["alice_lxmf_dest"], d["bob_pub"], d["bob_lxmf_dest"], d["py_to_kt_tid"], d["stamp_cost"], d["stamp_flex"])
' "$WORK_DIR/node/info.json")

"$JAVA_HOME/bin/java" -cp "$WORK_DIR/out:$RNS_CORE_JAR:$BCPROV_JAR:$COMMONS_COMPRESS_JAR:$KOTLIN_STDLIB" \
  interop.InteropHarnessKt \
  "$PORT" "$SEED_HEX" "$PROP_DEST" "$ALICE_PUB" "$ALICE_DEST" "$PY_TID" "$STAMP_COST" "$STAMP_FLEX" "$KEEPALIVE_SECS" "$BOB_PUB" "$BOB_DEST"

sleep 1
"$VENV/bin/python" -c '
import json, sys
d = json.load(open(sys.argv[1]))
msgs = d.get("kt_to_py", [])
assert any(m.get("sig_valid") and m.get("content") == "hello from kotlin rns-core" for m in msgs), f"Missing verified kt->py msg: {msgs}"
print("[2b] PASS: Kotlin -> Python LXMF message verified on PN:", msgs)
direct_msgs = d.get("direct_kt_to_py", [])
assert any(m.get("sig_valid") and m.get("content_len") == 16384 for m in direct_msgs), f"Missing verified 16KB kt->py Resource LXMF msg: {direct_msgs}"
print("[5a] PASS: Kotlin -> Python 16KB LXMF via RNS.Resource verified:", direct_msgs)
' "$WORK_DIR/node/entries.json"

echo "ALL INTEROP CHECKS PASSED"
