#!/usr/bin/env bash
set -euo pipefail
result_root="$PWD/android-e2e-results"
mkdir -p "$result_root"
export ANDROID_SERIAL
ANDROID_SERIAL=$(adb devices | awk 'NR>1 && $1 ~ /^emulator-/ && $2=="device" {print $1; exit}')
test -n "$ANDROID_SERIAL"
if test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1; then
  echo "Refusing a physical device: this suite clears disposable Firefox/test-app data." >&2
  exit 2
fi
export KADRI_DIST="$PWD/e2e-bundle/kadri-firefox"
export HELPER_COMMIT KADRI_COMMIT
HELPER_COMMIT=$(cat e2e-bundle/helper-commit.txt)
KADRI_COMMIT=$(cat e2e-bundle/kadri-commit.txt)
adb install -r e2e-bundle/helper-debug.apk
adb install -r e2e-bundle/helper-competing.apk
adb install -r e2e-bundle/firefox.apk
cp e2e-bundle/*provenance* e2e-bundle/firefox.json "$result_root/"
python3 tests/android_e2e/server.py --generate-only
python3 tests/android_e2e/server.py > "$result_root/fixture-server.log" 2>&1 &
fixture_pid=$!
cleanup() {
  kill "$fixture_pid" 2>/dev/null || true
  adb shell dumpsys meminfo --package de.evp.helfer.debug > "$result_root/final-helper-meminfo.txt" || true
  adb shell dumpsys meminfo --package org.mozilla.firefox > "$result_root/final-firefox-meminfo.txt" || true
  adb shell dumpsys activity exit-info de.evp.helfer.debug > "$result_root/helper-exit-info.txt" || true
  adb shell dumpsys activity exit-info org.mozilla.firefox > "$result_root/firefox-exit-info.txt" || true
}
trap cleanup EXIT
status=0
for route in ${E2E_ROUTES:-kadri}; do
  case "$route" in kadri|userscript) ;; *) echo 'Unsupported test route'; exit 2;; esac
  export E2E_ROUTE="$route"
  export E2E_OUT="$result_root/$route"
  mkdir -p "$E2E_OUT"
  # Independent empty profiles: never run the script and Kadri in the same page.
  adb shell am force-stop de.evp.helfer.e2ecompeting
  adb shell pm clear de.evp.helfer.debug
  adb shell pm clear org.mozilla.firefox
  python3 tests/android_e2e/run.py || status=1
done
python3 - <<'PY'
from pathlib import Path
root=Path("android-e2e-results")
text="# Real Android E2E — routes\n\n"
for report in sorted(root.glob("*/REPORT.md")):
    text+="## "+report.parent.name+"\n\n"+report.read_text()+"\n"
(root/"REPORT.md").write_text(text)
print(text)
PY
exit "$status"
