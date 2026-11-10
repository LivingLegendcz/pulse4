#!/usr/bin/env bash
set -eo pipefail

export JAVA_HOME="${JAVA_HOME:-/home/kocourkovic/.tools/jdk-17}"

echo "========================================================================"
echo " 1/3: Running Project Unit Tests (:domain, :analytics, :sync, :app ...)"
echo "========================================================================"
./gradlew test

echo "========================================================================"
echo " 2/3: Assembling Debug APK"
echo "========================================================================"
./gradlew assembleDebug

echo "========================================================================"
echo " 3/3: Running Automated Performance & Frame Budget Regression Test"
echo "========================================================================"
./scripts/perf_test.sh

echo "========================================================================"
echo " ALL CHECKS PASSED: Unit Tests + Build + Performance Regression Budget!"
echo "========================================================================"
