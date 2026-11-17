#!/usr/bin/env bash
# NUR Wegwerf-Emulator (Codex Release-Readiness 2, Punkt 7; Codex NO-GO 3). Prüft, ob eine verwaiste Enddatei
# (vollständige Datei ohne MediaStore-Zeile, wie nach "publish rows=0" beobachtet) beim erneuten Download unter demselben
# Titel erhalten bleibt.
#   tools/run-orphan-collision-probe.sh OUT   (OUT/apk aus tools/build-channel-apk.sh)
# 1. legt mit root DIREKT im Speicher (ohne MediaProvider) eine Datei "C2 OrphanProbe <zufällig>.mp4" mit bekanntem
#    SHA-256 an – genau der beobachtete Zustand: Datei vorhanden, keine Zeile;
# 2. startet OrphanNameCollisionProbeTest: echter Download (Engine + App-Ablage) unter demselben Titel mit ANDEREM
#    bekanntem Inhalt; der Test protokolliert Abfragestatus, Zeilen, Namen, Größen, Hashes und Abschlussstatus;
# 3. nimmt vorher/nachher ALLE Dateien des Testordners auf – auch versteckte Dateien und Unterordner (find -type f) –
#    mit Namen, Größen und SHA-256; eine Aufnahme gilt nur mit Abschlusszeile SNAPSHOT-END und ohne Fehlerzeile;
# 4. sichert VOR jedem Aufräumen alle Belege im Job-Log: beide Aufnahmen vollständig, die vollständige
#    Instrumentierungsausgabe, die Sondenzeile, Crash-Puffer und Absturz-/OOM-Zeilen des Systemprotokolls, Aufnahmefehler
#    (Titel maskiert) – auch wenn das Skript vorher abbricht (trap);
# 5. bewertet mit tools/delivery/orphan_verdict.py: ORPHAN_RESULT PASS (Exit 0) | DATA_LOSS (3) | INVALID (2);
# 6. entfernt in jedem Fall (auch nach einem unvollständigen Test) NUR die eigenen synthetischen Dateien, deren Name den
#    zufälligen Sondentitel enthält (auch versteckte, z. B. ".pending-…"); fremde Dateien werden nie gelöscht.
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
case "$TITLE" in "C2 OrphanProbe "[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;;
  *) echo "refusing: probe title not random" >&2; echo "ORPHAN_RESULT INVALID"; exit 2;; esac
NAME="$TITLE.mp4"
LOWER=/data/media/0/Download/Video-Assistent
W="$(mktemp -d)"
: > "$W/instr.txt"; : > "$W/probe.txt"; : > "$W/crash.txt"; : > "$W/errors.txt"
DUMPED=0
mask() { sed "s/$TITLE/<title>/g"; }
evidence() {   # alle Belege ins Job-Log (genau einmal, auch beim Abbruch vor dem Aufräumen)
  [ "$DUMPED" = 1 ] && return 0; DUMPED=1
  for f in before after; do [ -f "$W/$f.txt" ] && mask < "$W/$f.txt" | sed "s/^/ORPHAN_HOST $f /"; done
  mask < "$W/instr.txt" | sed 's/^/ORPHAN_INSTR /'
  sed 's/^/ORPHAN_PROBE_LINE /' "$W/probe.txt"
  mask < "$W/crash.txt" | sed 's/^/ORPHAN_CRASH /'
  mask < "$W/errors.txt" | sed 's/^/ORPHAN_SNAPSHOT_ERROR /'
  return 0
}
cleanup() {   # Belege zuerst; danach nur synthetische Sondendateien (eindeutiger Zufallsname) und das Arbeitsverzeichnis
  evidence || true
  adb root > /dev/null 2>&1 || true; sleep 2; adb wait-for-device || true
  adb shell "cd '$LOWER' 2>/dev/null && find . -type f | while IFS= read -r f; do case \"\$f\" in *'$TITLE'*) rm -f \"\$f\";; esac; done" || true
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
# Aufnahme: jede Datei (auch versteckt, auch in Unterordnern); ein Fehler je Datei wird als SNAPSHOT-ERROR ausgegeben, die
# Abschlusszeile SNAPSHOT-END nur, wenn der Ordner lesbar war. orphan_verdict.py verwirft jede unvollständige Aufnahme.
snapshot() {   # snapshot NAME
  local raw="$W/$1.raw"
  if ! adb shell "cd '$LOWER' || exit 3; find . -type f | while IFS= read -r f; do h=\$(sha256sum \"\$f\" 2>/dev/null | cut -d' ' -f1); s=\$(stat -c %s \"\$f\" 2>/dev/null); if [ -n \"\$h\" ] && [ -n \"\$s\" ]; then echo \"\$h \$s \${f#./}\"; else echo \"SNAPSHOT-ERROR \${f#./}\"; fi; done; echo SNAPSHOT-END" > "$raw" 2>> "$W/errors.txt"; then
    echo "$1: snapshot command failed" >> "$W/errors.txt"
  fi
  tr -d '\r' < "$raw" | grep -v '^SNAPSHOT-ERROR ' | grep -v '^SNAPSHOT-END$' | sort > "$W/$1.txt" || true
  tr -d '\r' < "$raw" | grep '^SNAPSHOT-ERROR ' | sed "s/^/$1: /" >> "$W/errors.txt" || true
  # Abschluss nur übernehmen, wenn die Aufnahme vollständig und fehlerfrei war
  if tr -d '\r' < "$raw" | tail -1 | grep -qx 'SNAPSHOT-END' && ! grep -q "^$1: " "$W/errors.txt"; then echo SNAPSHOT-END >> "$W/$1.txt"; fi
}
snapshot before
echo "ORPHAN_HOST api=$API orphan <title>.mp4 sha256=$ORPHAN_SHA size=98304 (written below MediaProvider: no row)"
adb unroot > /dev/null 2>&1 || true; sleep 3; adb wait-for-device
adb install -r -t "$OUT/apk/helper-channeltest.apk" > /dev/null
adb install -r -t "$OUT/apk/helper-channeltest-androidTest.apk" > /dev/null
adb logcat -c
adb shell am instrument -w -r -e orphanTitle "'$TITLE'" -e class de.evp.helfer.OrphanNameCollisionProbeTest "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$W/instr.txt" 2>&1 || true
adb logcat -d -s MP4_TEST:I | grep 'ORPHAN_PROBE' | sed -E 's/^.*ORPHAN_PROBE/ORPHAN_PROBE/' | mask > "$W/probe.txt" || true
# Abstürze NACH der scheinbaren Erfolgsmeldung: Crash-Puffer und Absturz-/OOM-/ANR-Zeilen des Systemprotokolls
{ adb logcat -d -b crash 2>&1 || echo "crash buffer unreadable"; adb logcat -d -b main,system 2>&1 | grep -E 'FATAL EXCEPTION|OutOfMemoryError|ANR in |Fatal signal' || true; } > "$W/crash.txt"
adb root > /dev/null; sleep 3; adb wait-for-device
snapshot after
evidence
set +e
python3 -I "$HERE/delivery/orphan_verdict.py" --before "$W/before.txt" --after "$W/after.txt" --instr "$W/instr.txt" --probe "$W/probe.txt" \
  --crash "$W/crash.txt" --title "$TITLE" --orphan-sha "$ORPHAN_SHA" --orphan-size 98304
rc=$?
set -e
echo "ORPHAN_HOST api=$API verdict exit $rc"
exit $rc
