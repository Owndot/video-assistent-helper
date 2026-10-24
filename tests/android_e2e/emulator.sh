#!/usr/bin/env bash
set -euo pipefail
export E2E_OUT="$PWD/android-e2e-results"
mkdir -p "$E2E_OUT"
export ANDROID_SERIAL
ANDROID_SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
test -n "$ANDROID_SERIAL"
export KADRI_DIST="$PWD/e2e-bundle/kadri-firefox"
export HELPER_COMMIT KADRI_COMMIT
HELPER_COMMIT=$(cat e2e-bundle/helper-commit.txt)
KADRI_COMMIT=$(cat e2e-bundle/kadri-commit.txt)
adb install -r e2e-bundle/helper-debug.apk
adb install -r e2e-bundle/helper-competing.apk
adb install -r e2e-bundle/firefox.apk
cp e2e-bundle/*provenance* e2e-bundle/firefox.json "$E2E_OUT/"
python3 tests/android_e2e/server.py --generate-only
python3 tests/android_e2e/server.py > "$E2E_OUT/fixture-server.log" 2>&1 &
fixture_pid=$!
cleanup() {
  kill "$fixture_pid" 2>/dev/null || true
  adb shell dumpsys meminfo de.evp.helfer.debug > "$E2E_OUT/final-helper-meminfo.txt" || true
  adb shell dumpsys meminfo org.mozilla.firefox > "$E2E_OUT/final-firefox-meminfo.txt" || true
  adb shell dumpsys activity exit-info de.evp.helfer.debug > "$E2E_OUT/helper-exit-info.txt" || true
  adb shell dumpsys activity exit-info org.mozilla.firefox > "$E2E_OUT/firefox-exit-info.txt" || true
}
trap cleanup EXIT
python3 tests/android_e2e/run.py
