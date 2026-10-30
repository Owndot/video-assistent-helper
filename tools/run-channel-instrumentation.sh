#!/usr/bin/env bash
# NUR TESTS. Installiert die Testvariante de.evp.helfer.channeltest auf einem Wegwerf-Emulator, führt die
# Kanal-Instrumentierungstests aus, sammelt einen geheimnisfreien Bericht und deinstalliert beide APKs wieder.
# Ein echtes Gerät wird abgelehnt. Das Produktionspaket de.evp.helfer wird weder installiert noch berührt.
# Aufruf: tools/run-channel-instrumentation.sh OUT   (OUT/apk aus tools/build-channel-apk.sh)
set -uo pipefail
OUT="$(cd "${1:?usage: run-channel-instrumentation.sh OUT}" && pwd)"
REPORT="$OUT/report"; mkdir -p "$REPORT"
PKG=de.evp.helfer.channeltest
adb wait-for-device
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ] && [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" != "1" ]; then
  echo "refusing: the connected device is not an emulator" >&2
  exit 2
fi
adb shell getprop ro.build.version.release > "$REPORT/device.txt"
adb shell getprop ro.build.version.sdk >> "$REPORT/device.txt"
adb shell getprop ro.product.cpu.abi >> "$REPORT/device.txt"
adb shell pm list packages de.evp.helfer > "$REPORT/packages-before.txt"
adb logcat -c
adb install -r -t "$OUT/apk/helper-channeltest.apk" > /dev/null
adb install -r -t "$OUT/apk/helper-channeltest-androidTest.apk" > /dev/null
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell am instrument -w -r -e class de.evp.helfer.ChannelInstrumentedTest,de.evp.helfer.net.SafeHttpInstrumentedTest,de.evp.helfer.Mp4DownloadsInstrumentedTest "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$REPORT/instrumentation.txt" 2>&1
status=0
grep -q '^OK (' "$REPORT/instrumentation.txt" || status=1
grep -E 'INSTRUMENTATION_STATUS: (test|class)=|INSTRUMENTATION_STATUS_CODE|^OK \(|FAILURES|Tests run|AssertionError|Exception' "$REPORT/instrumentation.txt" > "$REPORT/summary.txt"
adb shell dumpsys meminfo $PKG > "$REPORT/meminfo.txt" 2>&1 || true
adb shell top -b -n 1 -p "$(adb shell pidof $PKG | tr -d '\r')" > "$REPORT/top.txt" 2>&1 || true
adb logcat -d -s CHANNEL_TEST:I OKHTTP_TEST:I MP4_TEST:I > "$REPORT/measurements.txt"
# Nichts lauscht mehr auf dem alten Port, solange der Dienst läuft.
adb shell 'cat /proc/net/tcp /proc/net/tcp6 2>/dev/null' | awk '$4=="0A"{print $2}' > "$REPORT/listeners.txt"
if grep -qi ':BA43$' "$REPORT/listeners.txt"; then echo "LISTENER ON 47811" >> "$REPORT/summary.txt"; status=1; fi
adb shell pm list packages de.evp.helfer > "$REPORT/packages-during.txt"
adb uninstall "$PKG.test" > /dev/null || true
adb uninstall "$PKG" > /dev/null || true
adb shell pm list packages de.evp.helfer > "$REPORT/packages-after.txt"
diff -q "$REPORT/packages-before.txt" "$REPORT/packages-after.txt" > /dev/null || { echo "package list changed" >> "$REPORT/summary.txt"; status=1; }
tail -5 "$REPORT/summary.txt"
grep -E 'CHANNEL_|OKHTTP_|MP4_' "$REPORT/measurements.txt" || true
grep -E 'TOTAL PSS|TOTAL:|Native Heap|Java Heap' "$REPORT/meminfo.txt" | head -5 || true
exit $status
