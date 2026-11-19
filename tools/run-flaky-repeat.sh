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
# shellcheck source=tools/run-channel-isolation.sh
. "$(cd "$(dirname "$0")" && pwd)/run-channel-isolation.sh"
adb wait-for-device
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ] && [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" != "1" ]; then
  echo "refusing: the connected device is not an emulator" >&2; exit 2
fi
# Runde 5 (Testisolation): eigene Gerätephase "repeat" auf einem frischen Emulator (helper-channel.yml). Wie im Hauptlauf
# die Puffer vor der Installation leeren, damit der Crash-Puffer unten genau diese Phase umfasst. Runde 7: das Leeren steht
# VOR dem Start des Isolationsstroms, weil "logcat -c" auf Android 8 einen laufenden Strom stört (Lauf 44537491920, API 26).
adb logcat -c
iso_begin repeat "$REPORT"
trap iso_stop EXIT
iso_mark install
adb install -r -t "$OUT/apk/helper-channeltest.apk" > /dev/null
adb install -r -t "$OUT/apk/helper-channeltest-androidTest.apk" > /dev/null
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
fail=0; pass=0
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
# Codex Release-Readiness P2: JEDER der Läufe (auch erfolgreiche) wird vollständig mit eindeutiger Kennung und API
# ins Job-Log geschrieben; tools/device_gate.py prüft Anzahl, Kennungen, Testmengen und Ergebnis jedes Laufs.
# Format 3 (Codex NO-GO 3): danach wird der Crash-Puffer erneut ausgelesen (Abschnitt repeat-crash). Seit Runde 5 läuft
# diese Phase auf einem eigenen Emulator: Der Puffer wurde oben vor der Installation geleert und umfasst alle 22
# Wiederholungen; Hauptlauf, Sonden und Neustarts deckt der Abschnitt crash der Phase main ab. Ein Absturz außerhalb der
# Instrumentierungsausgabe (z. B. Hintergrundfaden) bleibt so nicht unsichtbar.
echo "REPEAT-FORMAT 3"
run() {   # run LABEL CLASSSPEC [NOTCLASS]
  local log="$REPORT/repeat-$1.txt"
  adb shell am instrument -w -r -e class "$2" ${3:+-e notClass "$3"} "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$log" 2>&1
  if grep -q '^OK (' "$log"; then pass=$((pass+1))
  else fail=$((fail+1)); grep -E 'test=|AssertionError|ComparisonFailure|Exception' "$log" | head -20 >> "$REPORT/repeat-failures.txt"
  fi
  echo "GATE-BEGIN repeat-$1"; echo "REPEAT-RUN label=$1 api=$API"; cat "$log"; echo "GATE-END repeat-$1"
}
for i in $(seq 1 "$N"); do
  run "three-$i" "$T#duplicateAndLateStartsFetchOnce,$T#leftoversAfterProcessDeathAreRemovedOnNextStart,$T#cancelRemovesEverything"
done
# Review P4: publicFileForUninstallCheck lässt absichtlich eine Datei mit festem Namen für die Deinstallationsprüfung
# zurück und ist deshalb nicht wiederholbar (Android 10: SQLiteConstraintException beim Veröffentlichen auf den belegten
# Namen, CI 2157122). Er läuft im Hauptlauf; nur aus den Klassen-Wiederholungen ausgenommen.
for i in 1 2; do run "class-$i" "$T" "$T#publicFileForUninstallCheck"; done
if adb logcat -d -b crash > "$REPORT/repeat-crash.txt" 2>&1; then
  echo "GATE-BEGIN repeat-crash"; cat "$REPORT/repeat-crash.txt"; echo "GATE-END repeat-crash"
else
  echo "GATE-BEGIN repeat-crash"; echo "GATE-MISSING repeat-crash"; echo "GATE-END repeat-crash"; fail=$((fail+1))
fi
iso_mark uninstall
adb uninstall "$PKG.test" > /dev/null || true
adb uninstall "$PKG" > /dev/null || true
iso_ok=1
iso_end || iso_ok=0
echo "REPEAT api=$API runs=$((pass+fail)) passed=$pass failed=$fail" | tee -a "$REPORT/summary.txt"
[ "$fail" = 0 ] && [ "$iso_ok" = 1 ]
