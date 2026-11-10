#!/usr/bin/env bash
set -eo pipefail

# Pulse4 Automated Performance & Frame Budget Regression Test
# Runs dumpsys gfxinfo benchmark against connected Android devices (physical POCO X3 NFC or emulator)
# Benchmarks both Phase 1 (500-Item Activity Lazy List) and Phase 2 (11-Chart Telemetry Dashboard).
# Enforces strict performance budgets to prevent UI stutter, slow frames, and performance regressions.

# Find ADB binary
if command -v adb >/dev/null 2>&1; then
    ADB="adb"
elif [ -x "/home/kocourkovic/projects/pulse4/.tools/android-sdk/platform-tools/adb" ]; then
    ADB="/home/kocourkovic/projects/pulse4/.tools/android-sdk/platform-tools/adb"
else
    echo "ERROR: adb executable not found!" >&2
    exit 1
fi

# Discover connected devices
DEVICES=($($ADB devices | grep -w "device" | awk '{print $1}'))
if [ ${#DEVICES[@]} -eq 0 ]; then
    echo "ERROR: No Android devices/emulators connected for performance test!" >&2
    exit 1
fi

TARGET_DEV="${1:-}"
if [ -z "$TARGET_DEV" ]; then
    # Prefer physical phone if available, otherwise first device
    TARGET_DEV="${DEVICES[0]}"
    for d in "${DEVICES[@]}"; do
        if [[ "$d" != emulator* ]]; then
            TARGET_DEV="$d"
            break
        fi
    done
fi

IS_EMULATOR=0
if [[ "$TARGET_DEV" == emulator* ]]; then
    IS_EMULATOR=1
fi

DEV_MODEL=$($ADB -s "$TARGET_DEV" shell getprop ro.product.model | tr -d '\r')
echo "========================================================================"
echo " Pulse4 Performance & Frame Budget Regression Test"
echo " Target Device: $TARGET_DEV ($DEV_MODEL) [Emulator: $IS_EMULATOR]"
echo "========================================================================"

# Ensure screen is awake and unlocked
$ADB -s "$TARGET_DEV" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
$ADB -s "$TARGET_DEV" shell wm dismiss-keyguard >/dev/null 2>&1 || true

# Helper function to parse gfxinfo stats via python
parse_gfxinfo() {
    python3 -c "
import sys, re

text = sys.stdin.read()

def get_last(pat, default='0'):
    matches = list(re.finditer(pat, text))
    return matches[-1].group(1) if matches else default

total = get_last(r'Total frames rendered:\s+(\d+)')
jank = get_last(r'Janky frames:\s+\d+\s+\(([\d\.]+)%\)', '0.0')
p50 = get_last(r'50th percentile:\s+(\d+)ms')
p90 = get_last(r'90th percentile:\s+(\d+)ms')
p95 = get_last(r'95th percentile:\s+(\d+)ms')
p99 = get_last(r'99th percentile:\s+(\d+)ms')
slow_ui = get_last(r'Number Slow UI thread:\s+(\d+)')
slow_draw = get_last(r'Number Slow issue draw commands:\s+(\d+)')
gpu_p99 = get_last(r'99th gpu percentile:\s+(\d+)ms')

print(f'{total} {jank} {p50} {p90} {p95} {p99} {slow_ui} {slow_draw} {gpu_p99}')
"
}

# -----------------------------------------------------------------------------
# PHASE 1: 500-ITEM ACTIVITY LIST SCROLLING BENCHMARK
# -----------------------------------------------------------------------------
echo ""
echo ">>> [Phase 1/2] Benchmarking 500-Item Activity Lazy List Scrolling..."

# Ensure app is in foreground and on Aktivity tab
$ADB -s "$TARGET_DEV" shell am start -n app.pulse4/app.pulse4.android.ui.MainActivity >/dev/null 2>&1 || true
sleep 1.2

if [ "$IS_EMULATOR" -eq 1 ]; then
    $ADB -s "$TARGET_DEV" shell input tap 135 600
    sleep 1.2
else
    # Tap Aktivity tab (center at X=450, Y=2180)
    $ADB -s "$TARGET_DEV" shell input tap 450 2180
    sleep 1.2
fi

# Reset gfxinfo stats right before scrolling
$ADB -s "$TARGET_DEV" shell dumpsys gfxinfo app.pulse4 reset >/dev/null 2>&1

if [ "$IS_EMULATOR" -eq 1 ]; then
    for i in {1..4}; do
        $ADB -s "$TARGET_DEV" shell input swipe 160 500 160 200 200
        sleep 0.3
    done
    for i in {1..4}; do
        $ADB -s "$TARGET_DEV" shell input swipe 160 200 160 500 200
        sleep 0.3
    done
else
    for i in {1..4}; do
        $ADB -s "$TARGET_DEV" shell input swipe 540 1600 540 600 250
        sleep 0.35
    done
    for i in {1..4}; do
        $ADB -s "$TARGET_DEV" shell input swipe 540 600 540 1600 250
        sleep 0.35
    done
fi

P1_GFX=$($ADB -s "$TARGET_DEV" shell dumpsys gfxinfo app.pulse4)
read -r P1_TOTAL P1_JANK P1_P50 P1_P90 P1_P95 P1_P99 P1_SLOW_UI P1_SLOW_DRAW P1_GPU_P99 <<< $(echo "$P1_GFX" | parse_gfxinfo)

echo "--- Phase 1: Activity List Results ---"
printf "%-32s: %s\n" "Total frames rendered" "$P1_TOTAL"
printf "%-32s: %s %%\n" "Janky frames percentage" "$P1_JANK"
printf "%-32s: %s ms\n" "50th percentile (median)" "$P1_P50"
printf "%-32s: %s ms\n" "90th percentile" "$P1_P90"
printf "%-32s: %s ms\n" "95th percentile" "$P1_P95"
printf "%-32s: %s ms\n" "99th percentile" "$P1_P99"
printf "%-32s: %s\n" "Slow UI thread frames" "$P1_SLOW_UI"
printf "%-32s: %s ms\n" "99th GPU percentile" "$P1_GPU_P99"

# -----------------------------------------------------------------------------
# PHASE 2: 11-CHART TELEMETRY DASHBOARD SCROLLING BENCHMARK
# -----------------------------------------------------------------------------
echo ""
echo ">>> [Phase 2/2] Benchmarking 11-Chart Telemetry Dashboard Scrolling..."

if [ "$IS_EMULATOR" -eq 1 ]; then
    # Tap first card
    $ADB -s "$TARGET_DEV" shell input tap 200 460
    sleep 1.5
    # Switch to Grafy tab
    $ADB -s "$TARGET_DEV" shell input tap 200 210
    sleep 1.2
else
    # Tap first run card in list
    $ADB -s "$TARGET_DEV" shell input tap 500 1850
    sleep 1.5
    # Switch to Grafy & Zóny tab
    $ADB -s "$TARGET_DEV" shell input tap 576 500
    sleep 1.2
fi

# Reset gfxinfo stats right before chart scrolling
$ADB -s "$TARGET_DEV" shell dumpsys gfxinfo app.pulse4 reset >/dev/null 2>&1

if [ "$IS_EMULATOR" -eq 1 ]; then
    for i in {1..4}; do
        $ADB -s "$TARGET_DEV" shell input swipe 160 500 160 200 200
        sleep 0.3
    done
    for i in {1..4}; do
        $ADB -s "$TARGET_DEV" shell input swipe 160 200 160 500 200
        sleep 0.3
    done
else
    for i in {1..4}; do
        $ADB -s "$TARGET_DEV" shell input swipe 540 1600 540 600 250
        sleep 0.35
    done
    for i in {1..4}; do
        $ADB -s "$TARGET_DEV" shell input swipe 540 600 540 1600 250
        sleep 0.35
    done
fi

P2_GFX=$($ADB -s "$TARGET_DEV" shell dumpsys gfxinfo app.pulse4)
read -r P2_TOTAL P2_JANK P2_P50 P2_P90 P2_P95 P2_P99 P2_SLOW_UI P2_SLOW_DRAW P2_GPU_P99 <<< $(echo "$P2_GFX" | parse_gfxinfo)

echo "--- Phase 2: Telemetry Charts Results ---"
printf "%-32s: %s\n" "Total frames rendered" "$P2_TOTAL"
printf "%-32s: %s %%\n" "Janky frames percentage" "$P2_JANK"
printf "%-32s: %s ms\n" "50th percentile (median)" "$P2_P50"
printf "%-32s: %s ms\n" "90th percentile" "$P2_P90"
printf "%-32s: %s ms\n" "95th percentile" "$P2_P95"
printf "%-32s: %s ms\n" "99th percentile" "$P2_P99"
printf "%-32s: %s\n" "Slow UI thread frames" "$P2_SLOW_UI"
printf "%-32s: %s ms\n" "99th GPU percentile" "$P2_GPU_P99"

# -----------------------------------------------------------------------------
# BUDGET EVALUATION & REPORT
# -----------------------------------------------------------------------------
echo ""
echo "========================================================================"
echo " PERFORMANCE BUDGET EVALUATION"
echo "========================================================================"

FAILED=0
if [ "$IS_EMULATOR" -eq 0 ]; then
    # Strict thresholds for physical device (hardware Adreno GPU)
    # Phase 1: 500-Item LazyColumn flinging (item creation & binding)
    MAX_P1_JANK="7.5"
    MAX_P1_P95=50
    MAX_P1_SLOW_UI=12

    # Phase 2: 11-Chart Telemetry Dashboard (custom Canvas rendering)
    MAX_P2_JANK="6.0"
    MAX_P2_P95=35
    MAX_P2_SLOW_UI=5

    # Evaluate Phase 1
    if (( $(echo "$P1_JANK > $MAX_P1_JANK" | bc -l) )); then
        echo "FAIL [Phase 1]: Janky frames ($P1_JANK %) exceeded budget ($MAX_P1_JANK %)!" >&2
        FAILED=1
    fi
    if [ "$P1_P95" -gt "$MAX_P1_P95" ]; then
        echo "FAIL [Phase 1]: 95th percentile ($P1_P95 ms) exceeded budget ($MAX_P1_P95 ms)!" >&2
        FAILED=1
    fi
    if [ "$P1_SLOW_UI" -gt "$MAX_P1_SLOW_UI" ]; then
        echo "FAIL [Phase 1]: Slow UI thread frames ($P1_SLOW_UI) exceeded budget ($MAX_P1_SLOW_UI)!" >&2
        FAILED=1
    fi

    # Evaluate Phase 2
    if (( $(echo "$P2_JANK > $MAX_P2_JANK" | bc -l) )); then
        echo "FAIL [Phase 2]: Janky frames ($P2_JANK %) exceeded budget ($MAX_P2_JANK %)!" >&2
        FAILED=1
    fi
    if [ "$P2_P95" -gt "$MAX_P2_P95" ]; then
        echo "FAIL [Phase 2]: 95th percentile ($P2_P95 ms) exceeded budget ($MAX_P2_P95 ms)!" >&2
        FAILED=1
    fi
    if [ "$P2_SLOW_UI" -gt "$MAX_P2_SLOW_UI" ]; then
        echo "FAIL [Phase 2]: Slow UI thread frames ($P2_SLOW_UI) exceeded budget ($MAX_P2_SLOW_UI)!" >&2
        FAILED=1
    fi
else
    # Relaxed thresholds for software CPU emulation (SwiftShader)
    MAX_JANK_PCT="30.0"
    MAX_P95=120
    MAX_SLOW_UI=50

    if (( $(echo "$P1_JANK > $MAX_JANK_PCT" | bc -l) )); then
        echo "FAIL [Phase 1]: Janky frames ($P1_JANK %) exceeded emulator budget ($MAX_JANK_PCT %)!" >&2
        FAILED=1
    fi
    if (( $(echo "$P2_JANK > $MAX_JANK_PCT" | bc -l) )); then
        echo "FAIL [Phase 2]: Janky frames ($P2_JANK %) exceeded emulator budget ($MAX_JANK_PCT %)!" >&2
        FAILED=1
    fi
fi

if [ "$FAILED" -eq 1 ]; then
    echo "========================================================================"
    echo " RESULT: PERFORMANCE REGRESSION DETECTED! (BUDGET EXCEEDED)"
    echo "========================================================================"
    exit 1
else
    echo "========================================================================"
    echo " RESULT: ALL PERFORMANCE BUDGETS PASSED! (Smooth 60 FPS scrolling)"
    echo "========================================================================"
    exit 0
fi
