#!/usr/bin/env bash
# P4a: auf einem laufenden Wegwerf-Emulator installieren, instrumentieren, Ergebnisse per run-as abholen, deinstallieren.
#   bash run-device.sh APK_DIR OUT_DIR
set -uo pipefail
APKS="$1"; OUT="$2"; mkdir -p "$OUT"
adb wait-for-device
adb shell getprop ro.build.version.sdk > "$OUT/api.txt"
adb install -r "$APKS/app-debug.apk" > "$OUT/install.txt" 2>&1
adb install -r "$APKS/app-debug-androidTest.apk" >> "$OUT/install.txt" 2>&1
adb logcat -c || true
timeout 1500 adb shell am instrument -w -r de.evp.p4a.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/instrumentation.txt" 2>&1
echo "instrument_exit=$?" >> "$OUT/instrumentation.txt"
adb logcat -d -s P4A:I AndroidRuntime:E > "$OUT/logcat.txt" 2>&1 || true
adb exec-out run-as de.evp.p4a tar -cf - files/p4a > "$OUT/p4a.tar" 2>"$OUT/pull.err"
mkdir -p "$OUT/files" && tar -xf "$OUT/p4a.tar" -C "$OUT/files" 2>>"$OUT/pull.err"; rm -f "$OUT/p4a.tar"
adb uninstall de.evp.p4a.test > /dev/null 2>&1 || true
adb uninstall de.evp.p4a > /dev/null 2>&1 || true
ls -la "$OUT/files/files/p4a" "$OUT/files/files/p4a/out" 2>&1 | head -60
grep -E "OK \(|FAILURES|Tests run|instrument_exit" "$OUT/instrumentation.txt" || true
