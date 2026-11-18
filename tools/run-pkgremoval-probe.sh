#!/usr/bin/env bash
# NUR DIAGNOSE, NUR frisch gestarteter Wegwerf-Emulator (API 34). A/B-Test der Hypothese "verzögerte Verarbeitung einer
# Paket-Deinstallation durch den MediaProvider trifft Zeilen der Neuinstallation desselben Pakets".
#   tools/run-pkgremoval-probe.sh control|uninstall OUT   (OUT/apk aus tools/build-channel-apk.sh, mit Probe-Testklasse)
#
# Beide Varianten laufen identisch, bis auf genau einen Schritt (Paketschritt):
#   control:   "adb install -r" desselben Testpakets (Aktualisierung, KEINE Deinstallation)
#   uninstall: "adb uninstall" des Testpakets und unmittelbar danach erneute Installation (während des Boot-Scans)
# Ablauf (bewusst OHNE tools/wait-device-ready.sh, der Boot-Scan wird nicht abgewartet):
#  1. Logcat sofort, ohne Löschen (-v epoch); Frische prüfen; Liste aller Nicht-Sondendateien mit SHA-256;
#  2. drei NUR LESENDE Hilfsskripte nach /data/local/tmp (MediaStore-Abfrage, mit Gerätezeit, Dateiliste);
#  3. Installation 1; Phase pre: genau 3 Veröffentlichungen (Prüfungen +0/+250 ms);
#  4. Paketschritt (s. o.), umrahmt von Markern "BOOTSCAN PKG … event=…" im Logcat (gleiche Zeitbasis wie MediaProvider);
#     ab dem Paketschritt fragt der Host alle ~0,3 s die privilegierte Sicht mit Gerätezeit ab (Lücke ohne Testprozess);
#  5. Phase post: gedrosselt (1 Auftrag, 500 ms Abstand), bis 8 s nach dem Mount-Scan, höchstens 120 Veröffentlichungen;
#  6. Phase recheck (neuer Prozess): Abfrage über jede ursprünglich erhaltene URI (pre und post), Sicht, Dateien;
#  7. Nachher-Liste; Bewertung mit tools/pkgremoval_verdict.py variant (nie PASS).
# Es wird NICHTS gelöscht und nichts in die MediaProvider-Datenbank geschrieben; einzige verändernde Paketaktion ist die
# Deinstallation des eigenen Testpakets in der Variante uninstall. Der Emulator wird nach dem Lauf verworfen.
set -euo pipefail
VARIANT="${1:?usage: run-pkgremoval-probe.sh control|uninstall OUT}"
case "$VARIANT" in control|uninstall) ;; *) echo "refusing: variant $VARIANT"; echo "PKGAB_RESULT INVALID"; exit 2;; esac
OUT="$(cd "${2:?usage: run-pkgremoval-probe.sh control|uninstall OUT}" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=de.evp.helfer.channeltest
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
CLS=de.evp.helfer.MediaStoreBootScanProbeTest#probe
REP="$OUT/report-pkgremoval-$VARIANT"; mkdir -p "$REP"
W="$(mktemp -d)"
LOGCAT_PID=""; SAMPLER_PID=""
DUMPED=0
evidence() {   # genau einmal, auch beim Abbruch
  [ "$DUMPED" = 1 ] && return 0; DUMPED=1
  touch "$W/stop-sampler"
  [ -n "$SAMPLER_PID" ] && { wait "$SAMPLER_PID" 2>/dev/null || true; }
  [ -n "$LOGCAT_PID" ] && { kill "$LOGCAT_PID" 2>/dev/null || true; wait "$LOGCAT_PID" 2>/dev/null || true; }
  adb logcat -d -v epoch -b all > "$W/logcat-all.txt" 2>&1 || true
  cp "$W"/*.txt "$REP/" 2>/dev/null || true
  for f in instr-pre instr-post instr-recheck; do [ -f "$W/$f.txt" ] && sed "s/^/PKGAB_INSTR $VARIANT $f /" "$W/$f.txt"; done
  [ -f "$W/logcat.txt" ] && grep -E "BOOTSCAN|MediaProvider: Scanned |owner_package_changed|MediaProvider: Moving |$PKG|PACKAGE_(REMOVED|FULLY_REMOVED|ADDED|REPLACED)" "$W/logcat.txt" | sed "s/^/PKGAB_LOG $VARIANT /" || true
  [ -f "$W/host-sampler.txt" ] && sed "s/^/PKGAB_HOSTQ $VARIANT /" "$W/host-sampler.txt"
  for f in foreign-before foreign-after; do [ -f "$W/$f.txt" ] && sed "s/^/PKGAB_LIST $VARIANT $f /" "$W/$f.txt"; done
  return 0
}
trap 'evidence; rm -rf "$W"' EXIT
invalid() { echo "refusing: $*" >&2; echo "PKGAB_RESULT INVALID"; exit 2; }
marker() { adb shell log -t BOOTSCAN "'BOOTSCAN PKG run=$RUN variant=$VARIANT event=$1 ${2:-}'" > /dev/null 2>&1 || echo "marker $1 failed" >> "$W/errors.txt"; }
uid() { adb shell cmd package list packages -U "$PKG" 2>/dev/null | tr -d '\r' | sed -nE "s/^package:$PKG uid:([0-9]+).*/\1/p" | head -1; }
install_both() {
  for apk in helper-channeltest.apk helper-channeltest-androidTest.apk; do
    ok=0; for i in 1 2 3 4 5 6 7 8; do if adb install -r -t "$OUT/apk/$apk" > /dev/null 2>&1; then ok=1; break; fi; sleep 3; done
    [ "$ok" = 1 ] || invalid "install of $apk failed"
  done
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
}

adb wait-for-device
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" = 1 ] || invalid "not an emulator"
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
[ "$API" = 34 ] || invalid "API $API (this diagnosis is for API 34)"
RUN="$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"
[[ "$RUN" =~ ^[0-9a-f]{12}$ ]] || invalid "run id"
TOKEN="BootScanProbe-$RUN-"
echo "PKGAB_HOST variant=$VARIANT run=$RUN api=$API"

# 1) Logcat sofort und vollständig (kein -c)
adb logcat -G 16M > /dev/null 2>&1 || true
adb logcat -v epoch -b main,system,crash,events > "$W/logcat.txt" 2>&1 &
LOGCAT_PID=$!
adb shell pm path "$PKG" 2>/dev/null | grep -q '^package:' && invalid "test package already installed (emulator not fresh)"
foreign() {   # foreign NAME -> $W/NAME.txt, letzte Zeile LIST-END nur bei vollständiger Liste
  adb shell "cd /storage/emulated/0 || exit 3; find . -path ./Android -prune -o -type f -print | while IFS= read -r f; do case \"\$f\" in *BootScanProbe-*) continue;; esac; h=\$(sha256sum \"\$f\" 2>/dev/null | cut -d' ' -f1); s=\$(stat -c %s \"\$f\" 2>/dev/null); if [ -n \"\$h\" ] && [ -n \"\$s\" ]; then echo \"\$h \$s /storage/emulated/0/\${f#./}\"; else echo \"LIST-ERROR \$f\"; fi; done; echo LIST-END" \
    | tr -d '\r' > "$W/$1.raw" || echo "LIST-ERROR command" >> "$W/$1.raw"
  grep -v '^LIST-END$' "$W/$1.raw" | sort > "$W/$1.txt" || true
  if tail -1 "$W/$1.raw" | grep -qx LIST-END && ! grep -q '^LIST-ERROR' "$W/$1.raw"; then echo LIST-END >> "$W/$1.txt"; fi
}
foreign foreign-before
adb shell "find /storage/emulated/0 -path /storage/emulated/0/Android -prune -o -name '*BootScanProbe-*' -print" | grep -q . && invalid "probe files already present"

# 2) Nur lesende Hilfsskripte (wie tools/run-bootscan-probe.sh, dazu eine Abfrage mit Gerätezeit für den Host)
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
cat > "$W/bootscan-qt.sh" <<'QTSH'
#!/system/bin/sh
# NUR LESEN: wie bootscan-q.sh, mit Gerätezeit (Epoche in ms, gleiche Uhr wie logcat -v epoch)
case "$1" in BootScanProbe-[0-9a-f]*-) ;; *) echo "QT-BEGIN t=0 res=bad"; echo "QT-END rc=2"; exit 0;; esac
t="${EPOCHREALTIME:-}"
if [ -n "$t" ]; then t=$(echo "$t" | tr -d . | cut -c1-13); res=ms; else t="$(date +%s)000"; res=s; fi
echo "QT-BEGIN t=$t res=$res"
content query --uri 'content://media/external/file?includePending=1&includeTrashed=1' --projection _id:owner_package_name:is_pending:is_trashed:_data:_display_name:_size --where "_data LIKE '%$1%'" 2>&1
echo "QT-END rc=$?"
QTSH
for s in q f qt; do adb push "$W/bootscan-$s.sh" "/data/local/tmp/bootscan-$s.sh" > /dev/null; done
adb shell sh /data/local/tmp/bootscan-q.sh "$TOKEN" | tr -d '\r' > "$W/preflight-q.txt"
head -1 "$W/preflight-q.txt" | grep -qx Q-BEGIN && tail -1 "$W/preflight-q.txt" | grep -qx 'Q-END rc=0' || invalid "privileged query helper does not work"

# 3) Installation 1 und Phase pre
marker install1-begin
install_both
marker install1-end "uid=$(uid)"
adb shell am instrument -w -r -e bootscanRun "$RUN" -e bootscanPhase pre -e bootscanMax 3 -e bootscanSeconds 30 -e class "$CLS" "$RUNNER" > "$W/instr-pre.txt" 2>&1 || true

# 4) Host-Abfragen der privilegierten Sicht mit Gerätezeit (überbrückt die Zeit ohne Testprozess)
( while [ ! -f "$W/stop-sampler" ]; do adb shell sh /data/local/tmp/bootscan-qt.sh "$TOKEN" | tr -d '\r'; sleep 0.3; done ) > "$W/host-sampler.txt" 2>&1 &
SAMPLER_PID=$!
if [ "$VARIANT" = uninstall ]; then
  marker uninstall-begin
  adb uninstall "$PKG.test" > /dev/null 2>&1 || echo "uninstall $PKG.test failed" >> "$W/errors.txt"
  adb uninstall "$PKG" > /dev/null 2>&1 || echo "uninstall $PKG failed" >> "$W/errors.txt"
  marker uninstall-end
  marker install2-begin
  install_both
  marker install2-end "uid=$(uid)"
else
  marker update-begin
  install_both
  marker update-end "uid=$(uid)"
fi

# 5) Phase post (gedrosselt, begrenzt), danach Host-Abfragen beenden
adb shell am instrument -w -r -e bootscanRun "$RUN" -e bootscanPhase post -e bootscanSeconds 120 -e bootscanMax 120 -e bootscanPaceMs 500 \
  -e bootscanAfterScanMs 8000 -e class "$CLS" "$RUNNER" > "$W/instr-post.txt" 2>&1 || true
touch "$W/stop-sampler"; wait "$SAMPLER_PID" 2>/dev/null || true; SAMPLER_PID=""

# 6) Nachprüfung über jede ursprünglich erhaltene URI (pre und post)
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
python3 -I "$HERE/pkgremoval_verdict.py" variant --variant "$VARIANT" --run "$RUN" --logcat "$W/logcat.txt" --host-sampler "$W/host-sampler.txt" \
  --instr-pre "$W/instr-pre.txt" --instr-post "$W/instr-post.txt" --instr-recheck "$W/instr-recheck.txt" \
  --foreign-before "$W/foreign-before.txt" --foreign-after "$W/foreign-after.txt" | tee "$REP/verdict.txt"
rc=${PIPESTATUS[0]}
set -e
echo "PKGAB_HOST variant=$VARIANT verdict exit $rc"
exit $rc
