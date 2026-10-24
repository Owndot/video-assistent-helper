#!/usr/bin/env bash
# Emulator/device fixture test. Uses only de.evp.helfer.debug and disposable APK identities.
set -euo pipefail
pkg=de.evp.helfer.debug
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
trap 'adb uninstall de.evp.helfer.debug >/dev/null 2>&1 || true' EXIT
adb uninstall "$pkg" >/dev/null 2>&1 || true
adb install migration-fixtures/old.apk
# Seed settings without launching the service; run-as works because these are DEBUG fixtures.
adb shell run-as "$pkg" mkdir -p shared_prefs
printf '%s' '<?xml version="1.0" encoding="utf-8"?><map><string name="lang">de</string><string name="theme">dark</string><boolean name="boot" value="false"/><string name="key">test-pairing-marker-not-a-real-secret</string><boolean name="paired" value="true"/></map>' | adb shell run-as "$pkg" sh -c "'cat > shared_prefs/helfer.xml'"
if [ "$sdk" -ge 33 ]; then
  adb install -r migration-fixtures/modern.apk
  prefs=$(adb shell run-as "$pkg" cat shared_prefs/helfer.xml)
  for marker in 'dark' 'test-pairing-marker-not-a-real-secret' 'paired'; do
    grep -q "$marker" <<< "$prefs"
  done
  echo "PASS API $sdk: v3.1 fixture upgrade retained private settings and pairing state"
else
  # Modern variant is intentionally minSdk 33: no old-signer fallback may be delivered here.
  if adb install -r migration-fixtures/modern.apk > modern-install.log 2>&1; then
    echo "Unexpected modern APK installation on legacy Android"; exit 1
  fi
  grep -q INSTALL_FAILED_OLDER_SDK modern-install.log
  echo "PASS API $sdk: modern variant rejected"
fi
# A fresh-key APK must never update either the original or rotated installation through an unapproved path.
# Reset to original fixture first to test signature-mismatch rejection deterministically.
adb uninstall "$pkg"
adb install migration-fixtures/old.apk
if adb install -r migration-fixtures/fresh.apk > fresh-install.log 2>&1; then
  echo "Unexpected new-key upgrade without lineage"; exit 1
fi
grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE fresh-install.log
adb uninstall "$pkg"
adb install migration-fixtures/fresh.apk
if adb shell run-as "$pkg" test -f shared_prefs/helfer.xml; then
  echo "Old private preferences survived a clean reinstall unexpectedly"; exit 1
fi
echo "PASS API $sdk: incompatible signer rejected; clean new-key installation works"
