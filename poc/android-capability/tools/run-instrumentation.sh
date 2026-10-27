#!/usr/bin/env bash
# Installs the test app on a disposable emulator (or, only with the owner's
# separate approval, a test device), runs the instrumentation tests, collects
# a secret-free report and uninstalls both APKs again.
# Usage: run-instrumentation.sh OUT
set -uo pipefail
OUT="$(cd "${1:?usage: run-instrumentation.sh OUT}" && pwd)"
REPORT="$OUT/report"; mkdir -p "$REPORT"
PKG=de.evp.pakepoc.test
adb wait-for-device
adb shell getprop ro.build.version.release > "$REPORT/device.txt"
adb shell getprop ro.build.version.sdk >> "$REPORT/device.txt"
adb shell getprop ro.product.cpu.abi >> "$REPORT/device.txt"
adb logcat -c
adb install -r -t "$OUT/apk/pake-poc-testapp.apk" > /dev/null
adb install -r -t "$OUT/apk/pake-poc-testapp-androidTest.apk" > /dev/null
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell am instrument -w -r "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$REPORT/instrumentation.txt" 2>&1
status=0
grep -q '^OK (' "$REPORT/instrumentation.txt" || status=1
grep -E 'INSTRUMENTATION_STATUS: (test|class)=|INSTRUMENTATION_STATUS_CODE|^OK \(|FAILURES|Tests run' "$REPORT/instrumentation.txt" > "$REPORT/summary.txt"
adb shell dumpsys meminfo $PKG > "$REPORT/meminfo.txt" 2>&1 || true
adb logcat -d -s PAKEPOC_TEST:I > "$REPORT/measurements.txt"
adb uninstall "$PKG.test" > /dev/null || true
adb uninstall "$PKG" > /dev/null || true
cat "$REPORT/summary.txt" | tail -5
grep -E 'POC_' "$REPORT/measurements.txt" || true
exit $status
