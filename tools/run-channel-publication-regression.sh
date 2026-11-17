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
# Absturz des Systems (z. B. "System has crashed" auf neuen Emulator-Abbildern): Crash-Puffer und Systemprotokoll zeigen
if grep -q 'INSTRUMENTATION_ABORTED\|Process crashed' "$OUT/report/publication-baseline.txt"; then
  echo "== crash buffer"; adb logcat -d -b crash 2>/dev/null | head -150 || true
  echo "== system log (fatal)"; adb logcat -d -b system,main 2>/dev/null | grep -E 'FATAL|\*\*\* |Watchdog|SystemServer|system_server' | tail -80 || true
fi
# Codex NO-GO 3 (P1): Nachweis nur, wenn die Veröffentlichung GELANG und erst die absichtlich manipulierte nachgelagerte
# Bereinigung der Besitzmarkierung scheiterte. Diese Ursache ("Cannot remove pending download ownership", ohne Abbruch
# "last abort: ;" und ohne Ablagefehler "store: ;") entsteht im Produktcode nur nach rows == 1 bzw. nach dem Verschieben.
# Beschriftung, failed:storage oder Code -2 allein genügen nicht; jeder Veröffentlichungsfehler im Lauf ist ein Fehlschlag.
# tools/device_gate.py prüft dasselbe unabhängig am vollständigen Job-Log.
B="$OUT/report/publication-baseline.txt"
if grep -Eq 'publish rows=|publish threw=|IOException: publish|publish result \(cause|MP4_STORE_FAILURE' "$B"; then
  echo 'mutant run shows a publish failure: the publication itself failed (not the marker cleanup)'; exit 1
fi
grep -Eq '^INSTRUMENTATION_STATUS: stack=org\.junit\.ComparisonFailure: published file is successful despite marker failure \(cause: IOException: Cannot remove pending download ownership; last abort: ; store: ; downloadsInitialized: (true|false); now=[0-9]+ms; cleanups: \[\]\) expected:<\[don\]e> but was:<\[failed:storag\]e>$' "$B"
[ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: -2$' "$B")" = 1 ]
grep -qx 'Tests run: 1,  Failures: 1' "$B"
if grep -q '^OK (' "$B"; then echo 'baseline unexpectedly passed'; exit 1; fi
echo 'PASS: previous cleanup fails the publication regression (failed:storage instead of done)'
