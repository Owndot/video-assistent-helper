#!/usr/bin/env bash
# NUR Wegwerf-Emulator (Codex Release-Readiness 2, Punkt 7). Prüft, ob eine verwaiste Enddatei (vollständige Datei ohne
# MediaStore-Zeile, wie nach "publish rows=0" beobachtet) beim erneuten Download unter demselben Titel erhalten bleibt.
#   tools/run-orphan-collision-probe.sh OUT   (OUT/apk aus tools/build-channel-apk.sh)
# 1. legt mit root DIREKT im Speicher (ohne MediaProvider) eine Datei "C2 OrphanProbe <zufällig>.mp4" mit bekanntem
#    SHA-256 an – genau der beobachtete Zustand: Datei vorhanden, keine Zeile;
# 2. startet OrphanNameCollisionProbeTest: echter Download (Engine + App-Ablage) unter demselben Titel mit ANDEREM
#    bekanntem Inhalt; der Test protokolliert Zeilen, Namen, Größen, Hashes und Abschlussstatus;
# 3. vergleicht vorher/nachher ALLE Dateien des Testordners (Namen, Größen, SHA-256) und die verwaiste Datei;
# 4. entfernt nur die eigenen synthetischen Dateien. Andere Ordner werden nie gelesen oder verändert.
# Ergebniszeilen: ORPHAN_HOST ... und ORPHAN_VERDICT preserved|overwritten|missing; Exit 0 = Test lief vollständig.
set -euo pipefail
OUT="$(cd "${1:?usage: run-orphan-collision-probe.sh OUT}" && pwd)"
PKG=de.evp.helfer.channeltest
adb wait-for-device
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ] && [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" != "1" ]; then
  echo "refusing: the connected device is not an emulator" >&2; exit 2
fi
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
adb root > /dev/null; sleep 3; adb wait-for-device
[ "$(adb shell id -u | tr -d '\r')" = 0 ] || { echo "refusing: no root on the disposable emulator" >&2; exit 2; }
TITLE="C2 OrphanProbe $(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"
NAME="$TITLE.mp4"
LOWER=/data/media/0/Download/Video-Assistent
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
python3 -c 'import sys; sys.stdout.buffer.write((b"ORPHAN-PROBE-KNOWN-CONTENT-" * 4096)[:98304])' > "$W/orphan.bin"
ORPHAN_SHA="$(sha256sum < "$W/orphan.bin" | cut -d' ' -f1)"
adb shell "mkdir -p '$LOWER'"
adb push "$W/orphan.bin" /data/local/tmp/orphan-probe.bin > /dev/null
adb shell "cp /data/local/tmp/orphan-probe.bin '$LOWER/$NAME' && chown media_rw:media_rw '$LOWER/$NAME' && chmod 660 '$LOWER/$NAME' && rm /data/local/tmp/orphan-probe.bin"
snapshot() { adb shell "cd '$LOWER' && for f in *; do [ -f \"\$f\" ] && echo \"\$(sha256sum \"\$f\" | cut -d' ' -f1) \$(stat -c %s \"\$f\") \$f\"; done" | tr -d '\r' | sort; }
snapshot > "$W/before.txt"
grep -qx "$ORPHAN_SHA 98304 $NAME" "$W/before.txt" || { echo "orphan not created as expected" >&2; cat "$W/before.txt" >&2; exit 1; }
echo "ORPHAN_HOST api=$API before: $(wc -l < "$W/before.txt") files; orphan $NAME sha256=$ORPHAN_SHA size=98304 (no MediaStore row)"
adb unroot > /dev/null 2>&1 || true; sleep 3; adb wait-for-device
adb install -r -t "$OUT/apk/helper-channeltest.apk" > /dev/null
adb install -r -t "$OUT/apk/helper-channeltest-androidTest.apk" > /dev/null
adb logcat -c
set +e
adb shell am instrument -w -r -e orphanTitle "'$TITLE'" -e class de.evp.helfer.OrphanNameCollisionProbeTest "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$W/instr.txt" 2>&1
set -e
sed "s/$TITLE/<title>/g" "$W/instr.txt" | grep -E 'INSTRUMENTATION_STATUS: (test|stack)=|INSTRUMENTATION_STATUS_CODE|^OK \(|^Tests run|^FAILURES' || true
adb logcat -d -s MP4_TEST:I | grep 'ORPHAN_PROBE' | sed -E 's/^.*ORPHAN_PROBE/ORPHAN_PROBE/' | sed "s/$TITLE/<title>/g"
adb root > /dev/null; sleep 3; adb wait-for-device
snapshot > "$W/after.txt"
echo "ORPHAN_HOST api=$API after: $(wc -l < "$W/after.txt") files"
diff <(sed "s/$TITLE/<title>/g" "$W/before.txt") <(sed "s/$TITLE/<title>/g" "$W/after.txt") | sed 's/^/ORPHAN_HOST diff /' || true
# Dateien außerhalb des Probe-Namens dürfen sich nicht verändert haben
OTHER_BEFORE="$(grep -v -F "$TITLE" "$W/before.txt" || true)"; OTHER_AFTER="$(grep -v -F "$TITLE" "$W/after.txt" || true)"
if [ "$OTHER_BEFORE" != "$OTHER_AFTER" ]; then echo "ORPHAN_HOST other files changed"; OTHERS=changed; else OTHERS=unchanged; fi
if grep -qx "$ORPHAN_SHA 98304 $NAME" "$W/after.txt"; then VERDICT=preserved
elif grep -q " $NAME\$" "$W/after.txt"; then VERDICT=overwritten
else VERDICT=missing; fi
echo "ORPHAN_VERDICT api=$API orphan=$VERDICT other_files=$OTHERS"
# nur die eigenen synthetischen Dateien entfernen (Probe-Name; eigene neue Zeilen hat der Test bereits gelöscht)
adb shell "cd '$LOWER' && for f in *; do case \"\$f\" in *'$TITLE'*) rm -f \"\$f\";; esac; done"
adb unroot > /dev/null 2>&1 || true
adb uninstall "$PKG.test" > /dev/null || true
adb uninstall "$PKG" > /dev/null || true
grep -q '^OK (1 test)' "$W/instr.txt"
