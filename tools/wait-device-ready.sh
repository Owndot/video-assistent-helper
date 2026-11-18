#!/usr/bin/env bash
# Wartet, bis das Gerät wirklich bereit ist (nicht nur sys.boot_completed): Paket-, Aktivitäts- und Einstellungsdienst
# antworten fünfmal hintereinander im Abstand von 2 s. Höchstens 300 s, sonst Abbruch mit Befund. Neuere
# Emulator-Abbilder (API 37) melden den Boot, bevor system_server stabil läuft ("Broken pipe" beim Installieren).
set -uo pipefail
end=$(( $(date +%s) + 300 )); ok=0
while [ "$(date +%s)" -lt "$end" ]; do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] \
     && adb shell pm path android 2>/dev/null | grep -q '^package:' \
     && adb shell cmd activity get-current-user >/dev/null 2>&1 \
     && adb shell settings get global device_provisioned >/dev/null 2>&1; then
    ok=$((ok + 1))
    if [ "$ok" -ge 5 ]; then echo "DEVICE_READY after $(( 300 - (end - $(date +%s)) )) s"; exit 0; fi
  else
    ok=0
  fi
  sleep 2
done
echo "DEVICE_NOT_READY: system services did not answer stably within 300 s" >&2
adb shell getprop | grep -E 'sys.boot_completed|init.svc.bootanim|ro.build.version.sdk' >&2 || true
exit 1
