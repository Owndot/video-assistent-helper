#!/usr/bin/env bash
# NUR MESSUNG: installierte Größe (Paketverzeichnis) der Testvariante A und B, nach Installation und nach speed.
set -uo pipefail
T="$1"; PKG=de.evp.helfer.channeltest
adb wait-for-device
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" = 1 ] || exit 2
adb root > /dev/null 2>&1; adb wait-for-device
for i in $(seq 1 30); do [ "$(adb shell id -u | tr -d '\r')" = 0 ] && break; sleep 2; adb wait-for-device; done
measure() {
  local dir; dir="$(adb shell pm path $PKG | sed -n 's/^package://p' | tr -d '\r' | head -1)"; dir="${dir%/*}"
  local files; files="$(adb shell "find $dir -type f -exec stat -c '%s %n' {} +" | tr -d '\r' | sed "s|$dir|<pkgdir>|" | sort -k2)"
  echo "INSTALLED $1 $2 files_bytes=$(echo "$files" | awk '{s+=$1} END{print s}')"; echo "$files" | sed 's/^/    /'
}
for v in a b; do
  adb uninstall $PKG > /dev/null 2>&1 || true
  adb install -t "$T/out-$v/apk/helper-channeltest.apk" > /dev/null || { echo "install failed $v"; continue; }
  measure "$v" install
  adb shell cmd package compile -m speed -f $PKG > /dev/null
  measure "$v" speed
  adb uninstall $PKG > /dev/null
done
