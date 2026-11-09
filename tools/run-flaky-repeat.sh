#!/usr/bin/env bash
# NUR TESTS, nur Wegwerf-Emulator. Wiederholt die drei auf API 34 sporadisch gescheiterten Gerätetests (P4b-0) so oft,
# dass ein einzelner grüner Lauf kein Nachweis ist: N-mal die drei zusammen und zusätzlich zweimal die ganze Klasse
# (Wechselwirkung zwischen Tests). Jeder Fehlschlag mit Befund (Ursache, Abbruch-Ergebnis) im Bericht; Exit 1 bei einem.
#   tools/run-flaky-repeat.sh OUT [N]   (OUT/apk aus tools/build-channel-apk.sh)
set -uo pipefail
OUT="$(cd "${1:?usage: run-flaky-repeat.sh OUT [N]}" && pwd)"; N="${2:-20}"
REPORT="$OUT/report"; mkdir -p "$REPORT"
PKG=de.evp.helfer.channeltest
T=de.evp.helfer.Mp4DownloadsInstrumentedTest
adb wait-for-device
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ] && [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" != "1" ]; then
  echo "refusing: the connected device is not an emulator" >&2; exit 2
fi
adb install -r -t "$OUT/apk/helper-channeltest.apk" > /dev/null
adb install -r -t "$OUT/apk/helper-channeltest-androidTest.apk" > /dev/null
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
fail=0; pass=0
run() {   # run LABEL CLASSSPEC
  local log="$REPORT/repeat-$1.txt"
  adb shell am instrument -w -r -e class "$2" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$log" 2>&1
  if grep -q '^OK (' "$log"; then pass=$((pass+1)); rm -f "$log"; else fail=$((fail+1)); grep -E 'test=|AssertionError|ComparisonFailure|Exception' "$log" | head -20 >> "$REPORT/repeat-failures.txt"; fi
}
for i in $(seq 1 "$N"); do
  run "three-$i" "$T#duplicateAndLateStartsFetchOnce,$T#leftoversAfterProcessDeathAreRemovedOnNextStart,$T#cancelRemovesEverything"
done
for i in 1 2; do run "class-$i" "$T"; done
adb uninstall "$PKG.test" > /dev/null || true
adb uninstall "$PKG" > /dev/null || true
echo "REPEAT api=$(adb shell getprop ro.build.version.sdk | tr -d '\r') runs=$((pass+fail)) passed=$pass failed=$fail" | tee -a "$REPORT/summary.txt"
[ "$fail" = 0 ]
