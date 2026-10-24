#!/usr/bin/env bash
set -euo pipefail
for label in stable current current stable stable current; do
  python3 tools/profile-helper.py --apk "validation-results/$label.apk" --label "$label" --output validation-results/helper-raw.jsonl
done
adb push validation-results/network-probe.zip /data/local/tmp/network-probe.zip
for label in stable current; do
  adb push "validation-results/$label.apk" "/data/local/tmp/$label.apk"
  adb shell "CLASSPATH=/data/local/tmp/network-probe.zip:/data/local/tmp/$label.apk app_process /system/bin de.evp.helfer.NetworkCookieProbe" > "validation-results/$label-native-cookie.json"
  cat "validation-results/$label-native-cookie.json"
done
