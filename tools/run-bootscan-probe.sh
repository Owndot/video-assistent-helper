#!/usr/bin/env bash
# NUR DIAGNOSE, NUR frisch gestarteter Wegwerf-Emulator (API 34). Option B nach dem API-34-Befund auf 0f989ca:
# Was geschieht mit gerade veröffentlichten MediaStore-Zeilen während des ersten Volume-Scans nach dem Booten
# ("Scanned external_primary due to REASON_MOUNTED"), verglichen mit Veröffentlichungen nach dessen Abschluss?
#   tools/run-bootscan-probe.sh OUT   (OUT/apk aus tools/build-channel-apk.sh, mit MediaStoreBootScanProbeTest)
#
# Ablauf (bewusst OHNE tools/wait-device-ready.sh, damit der Boot-Scan nicht abgewartet wird):
#  1. Logcat-Aufnahme sofort, ohne Löschen (-v epoch, auch die Boot-Scan-Zeilen), Puffer vergrößert;
#  2. Frische prüfen: Testpaket nicht installiert, keine Sondendateien; Liste ALLER Nicht-Sondendateien unter
#     /storage/emulated/0 (ohne Android/) mit SHA-256 als Ausgangsbeleg;
#  3. zwei NUR LESENDE Hilfsskripte nach /data/local/tmp (MediaStore-Abfrage per "content query", Dateiliste per
#     find/stat/sha256sum) – die privilegierte Gegenprüfung des Tests;
#  4. Phase during: Veröffentlichungen ab sofort bis 5 s nach der ersten REASON_MOUNTED-Zeile (höchstens 180 s);
#  5. Ende des Scans abwarten (Logcat), 15 s Abstand, Phase after: 40 s kontrollierte Veröffentlichungen;
#  6. Phase recheck (neuer Prozess): Abfrage über jede ursprünglich erhaltene URI, privilegierte Sicht, Dateien;
#  7. Nachher-Liste der Nicht-Sondendateien; Bewertung mit tools/bootscan_verdict.py (nie PASS):
#     BOOTSCAN_RESULT DIAGNOSED (0) | INVALID (2) | DATA_LOSS (3) | INCONCLUSIVE (4).
# Alle Belege (vollständige Logcat-Aufnahme, Instrumentierung, Listen, Bewertung) gehen VOR jedem Abbruch ins Job-Log
# und nach OUT/report-bootscan. Es wird NICHTS gelöscht, deinstalliert oder in die MediaProvider-Datenbank geschrieben;
# der Emulator wird nach dem Lauf verworfen.
set -euo pipefail
OUT="$(cd "${1:?usage: run-bootscan-probe.sh OUT}" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=de.evp.helfer.channeltest
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
CLS=de.evp.helfer.MediaStoreBootScanProbeTest#probe
REP="$OUT/report-bootscan"; mkdir -p "$REP"
W="$(mktemp -d)"
LOGCAT_PID=""
DUMPED=0
evidence() {   # genau einmal, auch beim Abbruch
  [ "$DUMPED" = 1 ] && return 0; DUMPED=1
  [ -n "$LOGCAT_PID" ] && { kill "$LOGCAT_PID" 2>/dev/null || true; wait "$LOGCAT_PID" 2>/dev/null || true; }
  adb logcat -d -v epoch -b all > "$W/logcat-all.txt" 2>&1 || true
  cp "$W"/*.txt "$REP/" 2>/dev/null || true
  for f in instr-during instr-after instr-recheck; do [ -f "$W/$f.txt" ] && sed "s/^/BOOTSCAN_INSTR $f /" "$W/$f.txt"; done
  [ -f "$W/logcat.txt" ] && grep -E 'BOOTSCAN|MediaProvider: Scanned |owner_package_changed|MediaProvider: Moving ' "$W/logcat.txt" | sed 's/^/BOOTSCAN_LOG /' || true
  for f in foreign-before foreign-after; do [ -f "$W/$f.txt" ] && sed "s/^/BOOTSCAN_LIST $f /" "$W/$f.txt"; done
  return 0
}
trap 'evidence; rm -rf "$W"' EXIT
invalid() { echo "refusing: $*" >&2; echo "BOOTSCAN_RESULT INVALID"; exit 2; }

adb wait-for-device
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" = 1 ] || invalid "not an emulator"
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
[ "$API" = 34 ] || invalid "API $API (this diagnosis is for API 34)"
RUN="$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"
[[ "$RUN" =~ ^[0-9a-f]{12}$ ]] || invalid "run id"
TOKEN="BootScanProbe-$RUN-"
echo "BOOTSCAN_HOST run=$RUN api=$API"

# 1) Logcat sofort und vollständig (kein -c: die Boot-Scan-Zeilen müssen erhalten bleiben)
adb logcat -G 16M > /dev/null 2>&1 || true
adb logcat -v epoch -b main,system,crash > "$W/logcat.txt" 2>&1 &
LOGCAT_PID=$!

# 2) Frische und Ausgangsbeleg (alle Nicht-Sondendateien mit SHA-256; Android/ ausgenommen)
adb shell pm path "$PKG" 2>/dev/null | grep -q '^package:' && invalid "test package already installed (emulator not fresh)"
foreign() {   # foreign NAME -> $W/NAME.txt, letzte Zeile LIST-END nur bei vollständiger Liste
  adb shell "cd /storage/emulated/0 || exit 3; find . -path ./Android -prune -o -type f -print | while IFS= read -r f; do case \"\$f\" in *BootScanProbe-*) continue;; esac; h=\$(sha256sum \"\$f\" 2>/dev/null | cut -d' ' -f1); s=\$(stat -c %s \"\$f\" 2>/dev/null); if [ -n \"\$h\" ] && [ -n \"\$s\" ]; then echo \"\$h \$s /storage/emulated/0/\${f#./}\"; else echo \"LIST-ERROR \$f\"; fi; done; echo LIST-END" \
    | tr -d '\r' > "$W/$1.raw" || echo "LIST-ERROR command" >> "$W/$1.raw"
  grep -v '^LIST-END$' "$W/$1.raw" | sort > "$W/$1.txt" || true
  if tail -1 "$W/$1.raw" | grep -qx LIST-END && ! grep -q '^LIST-ERROR' "$W/$1.raw"; then echo LIST-END >> "$W/$1.txt"; fi
}
foreign foreign-before
adb shell "find /storage/emulated/0 -path /storage/emulated/0/Android -prune -o -name '*BootScanProbe-*' -print" | grep -q . && invalid "probe files already present"

# 3) Nur lesende Hilfsskripte für die privilegierte Gegenprüfung
cat > "$W/bootscan-q.sh" <<'QSH'
#!/system/bin/sh
# NUR LESEN: alle MediaStore-Zeilen (alle Besitzer, auch ausstehend/Papierkorb), deren Pfad das Sondenkennzeichen enthält
case "$1" in BootScanProbe-[0-9a-f]*-) ;; *) echo Q-BEGIN; echo "bad token"; echo "Q-END rc=2"; exit 0;; esac
echo Q-BEGIN
content query --uri 'content://media/external/file?includePending=1&includeTrashed=1' --projection _id:owner_package_name:is_pending:is_trashed:_data:_display_name:_size --where "_data LIKE '%$1%'" 2>&1
echo "Q-END rc=$?"
QSH
cat > "$W/bootscan-f.sh" <<'FSH'
#!/system/bin/sh
# NUR LESEN: Sondendateien unter Download mit Größe und SHA-256
case "$1" in BootScanProbe-[0-9a-f]*-) ;; *) echo F-BEGIN; echo "bad token"; echo "F-END rc=2"; exit 0;; esac
echo F-BEGIN
list=$(find /storage/emulated/0/Download -type f -name "*$1*" 2>&1) || { echo "F-ERROR find"; echo "F-END rc=1"; exit 0; }
echo "$list" | while IFS= read -r f; do
  [ -z "$f" ] && continue
  case "$f" in /storage/*) ;; *) echo "F-ERROR $f"; continue;; esac
  h=$(sha256sum "$f" 2>/dev/null | cut -d' ' -f1); s=$(stat -c %s "$f" 2>/dev/null)
  if [ -n "$h" ] && [ -n "$s" ]; then echo "$h $s $f"; else echo "F-ERROR $f"; fi
done
echo "F-END rc=0"
FSH
adb push "$W/bootscan-q.sh" /data/local/tmp/bootscan-q.sh > /dev/null
adb push "$W/bootscan-f.sh" /data/local/tmp/bootscan-f.sh > /dev/null
adb shell sh /data/local/tmp/bootscan-q.sh "$TOKEN" | tr -d '\r' > "$W/preflight-q.txt"
head -1 "$W/preflight-q.txt" | grep -qx Q-BEGIN && tail -1 "$W/preflight-q.txt" | grep -qx 'Q-END rc=0' || invalid "privileged query helper does not work: $(tail -2 "$W/preflight-q.txt" | tr '\n' ' ')"

# 4) Installation (nur Verfügbarkeit des Paketdienstes kurz nach dem Booten; der Scan wird nicht abgewartet)
for apk in helper-channeltest.apk helper-channeltest-androidTest.apk; do
  ok=0; for i in 1 2 3 4 5 6 7 8; do if adb install -r -t "$OUT/apk/$apk" > /dev/null 2>&1; then ok=1; break; fi; sleep 3; done
  [ "$ok" = 1 ] || invalid "install of $apk failed"
done
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
SCANS_BEFORE="$(grep -c 'Scanned external_primary due to REASON_MOUNTED' "$W/logcat.txt" || true)"
echo "BOOTSCAN_HOST mount scans before window 1: $SCANS_BEFORE"

# 5) Phase during
adb shell am instrument -w -r -e bootscanRun "$RUN" -e bootscanPhase during -e bootscanSeconds 180 -e class "$CLS" "$RUNNER" > "$W/instr-during.txt" 2>&1 || true
# 6) Ende des Scans abwarten (höchstens 300 s), dann 15 s Abstand, Phase after
for i in $(seq 1 150); do grep -q 'Scanned external_primary due to REASON_MOUNTED' "$W/logcat.txt" && break; sleep 2; done
sleep 15
adb shell am instrument -w -r -e bootscanRun "$RUN" -e bootscanPhase after -e bootscanSeconds 40 -e class "$CLS" "$RUNNER" > "$W/instr-after.txt" 2>&1 || true
# 7) Nachprüfung über jede ursprünglich erhaltene URI (Basis + IDs aus den DL-Zeilen)
sleep 2
tr -d '\r' < "$W/logcat.txt" | grep -o 'BOOTSCAN DL .*' | grep "run=$RUN " > "$W/dl-lines.txt" || true
BASES="$(grep -o ' uri=content://[^ ]*' "$W/dl-lines.txt" | sed -E 's# uri=(.*/)[0-9]+$#\1#' | sort -u || true)"
[ "$(printf '%s\n' "$BASES" | grep -c .)" -le 1 ] || invalid "downloads returned URIs with different bases: $BASES"
BASE="${BASES:-content://media/external/downloads/}"
IDS="$(sed -nE 's/.* seq=([0-9]+) .* uri=content:[^ ]*\/([0-9]+) id=[0-9]+$/\1:\2/p' "$W/dl-lines.txt" | paste -sd, -)"
adb shell am instrument -w -r -e bootscanRun "$RUN" -e bootscanPhase recheck -e bootscanBase "$BASE" -e bootscanIds "${IDS:--}" -e class "$CLS" "$RUNNER" > "$W/instr-recheck.txt" 2>&1 || true
sleep 2
foreign foreign-after
kill "$LOGCAT_PID" 2>/dev/null || true; wait "$LOGCAT_PID" 2>/dev/null || true; LOGCAT_PID=""
evidence
set +e
python3 -I "$HERE/bootscan_verdict.py" --logcat "$W/logcat.txt" --run "$RUN" --instr-during "$W/instr-during.txt" --instr-after "$W/instr-after.txt" \
  --instr-recheck "$W/instr-recheck.txt" --foreign-before "$W/foreign-before.txt" --foreign-after "$W/foreign-after.txt" | tee "$REP/verdict.txt"
rc=${PIPESTATUS[0]}
set -e
echo "BOOTSCAN_HOST verdict exit $rc"
exit $rc
