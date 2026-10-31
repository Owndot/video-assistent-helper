#!/usr/bin/env bash
# Internal disposable emulator only. The test must detect the previous post-publication cleanup failure.
set -euo pipefail
OUT="$(cd "${1:?usage: run-channel-publication-regression.sh OUT}" && pwd)"
PKG=de.evp.helfer.channeltest
adb wait-for-device
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ] && [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" != 1 ]; then
  echo 'refusing: not a disposable emulator' >&2
  exit 2
fi
# Each build has its own throwaway signer. Neither package may survive this probe.
cleanup() { adb uninstall "$PKG.test" >/dev/null 2>&1 || true; adb uninstall "$PKG" >/dev/null 2>&1 || true; }
trap cleanup EXIT
mkdir -p "$OUT/report"
adb install -t "$OUT/apk/helper-channeltest.apk" >/dev/null
adb install -t "$OUT/apk/helper-channeltest-androidTest.apk" >/dev/null
adb shell am instrument -w -r -e class de.evp.helfer.Mp4DownloadsInstrumentedTest#publishedFileSurvivesMarkerFailureAndRestart "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$OUT/report/publication-baseline.txt" 2>&1
cat "$OUT/report/publication-baseline.txt"
grep -q 'published file is successful despite marker failure' "$OUT/report/publication-baseline.txt"
grep -q 'failed:storage' "$OUT/report/publication-baseline.txt"
grep -q 'INSTRUMENTATION_STATUS_CODE: -2' "$OUT/report/publication-baseline.txt"
if grep -q '^OK (' "$OUT/report/publication-baseline.txt"; then echo 'baseline unexpectedly passed'; exit 1; fi
echo 'PASS: previous cleanup fails the publication regression (failed:storage instead of done)'
