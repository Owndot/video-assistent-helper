#!/usr/bin/env bash
# NUR GRÖSSENTEST (Zweig tmp/okhttp-size-test). Misst je Variante die installierte Codegröße auf einem Wegwerf-Emulator
# (Paketverzeichnis /data/app/…: APK + oat/vdex/art) nach der Installation und nach erzwungener speed-Kompilierung,
# deinstalliert wieder und führt danach die Kanal-Instrumentierung gegen genau diese APK aus.
# Aufruf: tools/size-emulator.sh ROOT VARIANTE...   (ROOT/VARIANTE/apk aus tools/build-channel-apk.sh)
set -uo pipefail
R="$1"; shift
mkdir -p "$R/text"
# Ausgabe nur in eine Datei: ein von adb neu gestarteter Server würde sonst die Ausgabe-Pipe des Runners offen halten.
LOG="$R/text/emulator.txt"
exec < /dev/null >> "$LOG" 2>&1
PKG=de.evp.helfer.channeltest
adb wait-for-device
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ] && [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" != "1" ]; then
  echo "refusing: not an emulator" >&2; exit 2
fi
adb root < /dev/null > /dev/null 2>&1; adb wait-for-device
for i in $(seq 1 30); do [ "$(adb shell id -u | tr -d '\r')" = 0 ] && break; sleep 2; adb wait-for-device; done
[ "$(adb shell id -u | tr -d '\r')" = 0 ] || { echo "adb root unavailable" | tee -a "$LOG"; exit 3; }
status=0
measure() {  # $1 = label
  local dir; dir="$(adb shell pm path $PKG | sed -n 's/^package://p' | tr -d '\r' | head -1)"; dir="${dir%/*}"
  adb shell "find $dir -type f -exec stat -c '%s %n' {} +" | tr -d '\r' | sed "s|$dir|<pkgdir>|" | sort -k2 > "$R/$v.$1.files"
  local total; total=$(awk '{s+=$1} END{print s}' "$R/$v.$1.files")
  local blocks; blocks=$(adb shell du -sk "$dir" | tr -d '\r' | cut -f1)
  local filt; filt=$(adb shell dumpsys package $PKG | tr -d '\r' | grep -m1 -o 'compilation_filter=[a-z-]*' || true)
  echo "INSTALLED $v $1 files_bytes=$total du_kib=$blocks $filt" | tee -a "$R/installed.txt"
  sed 's/^/    /' "$R/$v.$1.files"
}
for v in "$@"; do
  [ -f "$R/$v/apk/helper-channeltest.apk" ] || { echo "SKIPPED $v (not built)" | tee -a "$R/installed.txt"; continue; }
  adb uninstall $PKG > /dev/null 2>&1 || true
  adb install -t "$R/$v/apk/helper-channeltest.apk" > /dev/null || { echo "install failed $v"; status=1; continue; }
  measure install
  adb shell cmd package compile -m speed -f $PKG > /dev/null
  measure speed
  adb uninstall $PKG > /dev/null
  echo "== instrumentation $v"
  adb logcat -c || true
  if timeout -k 15 300 bash tools/run-channel-instrumentation.sh "$R/$v"; then echo "INSTRUMENTATION $v OK $(grep -m1 '^OK (' "$R/$v/report/instrumentation.txt")" | tee -a "$R/installed.txt"
  else echo "INSTRUMENTATION $v FAILED" | tee -a "$R/installed.txt"; status=1; fi
  adb logcat -d -b crash > "$R/$v/report/crash.txt" 2>&1 || true
  adb logcat -d -s AndroidRuntime:E TestRunner:E > "$R/$v/report/runtime-errors.txt" 2>&1 || true
  grep -E 'FATAL|NoClassDef|ClassNotFound|Exception' "$R/$v/report/runtime-errors.txt" | head -20 || true
  adb shell am force-stop $PKG || true; adb uninstall "$PKG.test" > /dev/null 2>&1 || true; adb uninstall $PKG > /dev/null 2>&1 || true
  if [ -s "$R/$v/report/crash.txt" ]; then echo "CRASH LOG $v:"; head -40 "$R/$v/report/crash.txt"; status=1; fi
  adb logcat -b crash -c || true
done
exit $status
