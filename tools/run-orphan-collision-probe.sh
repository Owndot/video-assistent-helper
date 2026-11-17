#!/usr/bin/env bash
# NUR Wegwerf-Emulator (Codex Release-Readiness 2, Punkt 7). Prüft, ob eine verwaiste Enddatei (vollständige Datei ohne
# MediaStore-Zeile, wie nach "publish rows=0" beobachtet) beim erneuten Download unter demselben Titel erhalten bleibt.
#   tools/run-orphan-collision-probe.sh OUT   (OUT/apk aus tools/build-channel-apk.sh)
# 1. legt mit root DIREKT im Speicher (ohne MediaProvider) eine Datei "C2 OrphanProbe <zufällig>.mp4" mit bekanntem
#    SHA-256 an – genau der beobachtete Zustand: Datei vorhanden, keine Zeile;
# 2. startet OrphanNameCollisionProbeTest: echter Download (Engine + App-Ablage) unter demselben Titel mit ANDEREM
#    bekanntem Inhalt; der Test protokolliert Zeilen, Namen, Größen, Hashes und Abschlussstatus;
# 3. nimmt vorher/nachher ALLE Dateien des Testordners auf (Namen, Größen, SHA-256) und gibt beide Aufnahmen vollständig
#    als Beleg aus, BEVOR aufgeräumt wird;
# 4. bewertet mit tools/delivery/orphan_verdict.py: ORPHAN_RESULT PASS (Exit 0) | DATA_LOSS (3) | INVALID (2);
# 5. entfernt in jedem Fall (auch bei Fehlern) nur die eigenen synthetischen Dateien mit dem Sondennamen.
set -euo pipefail
OUT="$(cd "${1:?usage: run-orphan-collision-probe.sh OUT}" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=de.evp.helfer.channeltest
adb wait-for-device
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ] && [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" != "1" ]; then
  echo "refusing: the connected device is not an emulator" >&2; echo "ORPHAN_RESULT INVALID"; exit 2
fi
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
adb root > /dev/null; sleep 3; adb wait-for-device
[ "$(adb shell id -u | tr -d '\r')" = 0 ] || { echo "refusing: no root on the disposable emulator" >&2; echo "ORPHAN_RESULT INVALID"; exit 2; }
TITLE="C2 OrphanProbe $(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"
NAME="$TITLE.mp4"
LOWER=/data/media/0/Download/Video-Assistent
W="$(mktemp -d)"
cleanup() {   # nur synthetische Sondendateien (eindeutiger Zufallsname) und das Arbeitsverzeichnis
  adb root > /dev/null 2>&1 || true; sleep 2; adb wait-for-device || true
  adb shell "cd '$LOWER' 2>/dev/null && for f in *; do case \"\$f\" in *'$TITLE'*) rm -f \"\$f\";; esac; done" || true
  adb shell rm -f /data/local/tmp/orphan-probe.bin || true
  adb unroot > /dev/null 2>&1 || true
  adb uninstall "$PKG.test" > /dev/null 2>&1 || true
  adb uninstall "$PKG" > /dev/null 2>&1 || true
  rm -rf "$W"
}
trap cleanup EXIT
python3 -c 'import sys; sys.stdout.buffer.write((b"ORPHAN-PROBE-KNOWN-CONTENT-" * 4096)[:98304])' > "$W/orphan.bin"
ORPHAN_SHA="$(sha256sum < "$W/orphan.bin" | cut -d' ' -f1)"
adb shell "mkdir -p '$LOWER'"
adb push "$W/orphan.bin" /data/local/tmp/orphan-probe.bin > /dev/null
adb shell "cp /data/local/tmp/orphan-probe.bin '$LOWER/$NAME' && chown media_rw:media_rw '$LOWER/$NAME' && chmod 660 '$LOWER/$NAME' && rm /data/local/tmp/orphan-probe.bin" || true
snapshot() { adb shell "cd '$LOWER' && for f in *; do [ -f \"\$f\" ] && echo \"\$(sha256sum \"\$f\" | cut -d' ' -f1) \$(stat -c %s \"\$f\") \$f\"; done" | tr -d '\r' | sort; }
evidence() { sed "s/$TITLE/<title>/g" "$1" | sed "s/^/ORPHAN_HOST $2 /"; }
snapshot > "$W/before.txt"
echo "ORPHAN_HOST api=$API orphan <title>.mp4 sha256=$ORPHAN_SHA size=98304 (written below MediaProvider: no row)"
evidence "$W/before.txt" before
adb unroot > /dev/null 2>&1 || true; sleep 3; adb wait-for-device
adb install -r -t "$OUT/apk/helper-channeltest.apk" > /dev/null
adb install -r -t "$OUT/apk/helper-channeltest-androidTest.apk" > /dev/null
adb logcat -c
adb shell am instrument -w -r -e orphanTitle "'$TITLE'" -e class de.evp.helfer.OrphanNameCollisionProbeTest "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$W/instr.txt" 2>&1 || true
sed "s/$TITLE/<title>/g" "$W/instr.txt" | grep -E 'INSTRUMENTATION_STATUS: (test|stack)=|INSTRUMENTATION_STATUS_CODE|^OK \(|^Tests run|^FAILURES' || true
adb logcat -d -s MP4_TEST:I | grep 'ORPHAN_PROBE' | sed -E 's/^.*ORPHAN_PROBE/ORPHAN_PROBE/' | sed "s/$TITLE/<title>/g" > "$W/probe.txt" || true
cat "$W/probe.txt"
adb root > /dev/null; sleep 3; adb wait-for-device
snapshot > "$W/after.txt"
evidence "$W/after.txt" after
set +e
python3 -I "$HERE/delivery/orphan_verdict.py" --before "$W/before.txt" --after "$W/after.txt" --instr "$W/instr.txt" --probe "$W/probe.txt" \
  --title "$TITLE" --orphan-sha "$ORPHAN_SHA" --orphan-size 98304
rc=$?
set -e
echo "ORPHAN_HOST api=$API verdict exit $rc"
exit $rc
