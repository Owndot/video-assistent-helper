#!/usr/bin/env bash
# Emulator/device fixture test. Uses only de.evp.helfer.debug and disposable APK identities.
set -euo pipefail
trap 'for f in *-install.log; do [ ! -f "$f" ] || cat "$f"; done' ERR
pkg=de.evp.helfer.debug
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
trap 'adb uninstall de.evp.helfer.debug >/dev/null 2>&1 || true' EXIT
adb uninstall "$pkg" >/dev/null 2>&1 || true
adb install --no-incremental migration-fixtures/old.apk
# Seed settings without launching the service; run-as works because these are DEBUG fixtures.
adb shell run-as "$pkg" mkdir -p shared_prefs
printf '%s' '<?xml version="1.0" encoding="utf-8"?><map><string name="lang">de</string><string name="theme">dark</string><boolean name="boot" value="false"/><string name="key">test-pairing-marker-not-a-real-secret</string><boolean name="paired" value="true"/></map>' | adb shell run-as "$pkg" sh -c "'cat > shared_prefs/helfer.xml'"
if [ "$sdk" -ge 33 ]; then
  adb install --no-incremental -r migration-fixtures/modern.apk
  prefs=$(adb shell run-as "$pkg" cat shared_prefs/helfer.xml)
  for marker in 'dark' 'test-pairing-marker-not-a-real-secret' 'paired'; do
    grep -q "$marker" <<< "$prefs"
  done
  if adb install --no-incremental -r migration-fixtures/old-key-higher-version.apk > old-key-install.log 2>&1; then
    echo "Compromised old identity can still update migrated app"; exit 1
  fi
  grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE old-key-install.log
  echo "PASS API $sdk: v3.1 fixture upgrade retained private state and rejected higher-version old-key update"
else
  # Modern variant is intentionally minSdk 33: no old-signer fallback may be delivered here.
  if adb install --no-incremental -r migration-fixtures/modern.apk > modern-install.log 2>&1; then
    echo "Unexpected modern APK installation on legacy Android"; exit 1
  fi
  cat modern-install.log
  # Package parsing/signature validation can precede the minSdk check on legacy systems.
  # The build job independently checks manifest minSdk=33.
  grep -Eq 'INSTALL_FAILED_OLDER_SDK|INSTALL_PARSE_FAILED_NO_CERTIFICATES|INSTALL_FAILED_UPDATE_INCOMPATIBLE' modern-install.log
  adb shell run-as "$pkg" cat shared_prefs/helfer.xml | grep -q 'dark'
  echo "PASS API $sdk: modern variant rejected"
fi
# A fresh-key APK must never update either the original or rotated installation through an unapproved path.
# Reset to original fixture first to test signature-mismatch rejection deterministically.
adb uninstall "$pkg"
adb install --no-incremental migration-fixtures/old.apk
if adb install --no-incremental -r migration-fixtures/fresh.apk > fresh-install.log 2>&1; then
  echo "Unexpected new-key upgrade without lineage"; exit 1
fi
grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE fresh-install.log
adb uninstall "$pkg"
adb install --no-incremental migration-fixtures/fresh.apk
if adb shell run-as "$pkg" test -f shared_prefs/helfer.xml; then
  echo "Old private preferences survived a clean reinstall unexpectedly"; exit 1
fi
if adb install --no-incremental -r migration-fixtures/old-key-higher-version.apk > old-key-install.log 2>&1; then
  echo "Old identity updated clean new-key installation"; exit 1
fi
grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE old-key-install.log
echo "PASS API $sdk: incompatible signer rejected; clean new-key installation rejects old-key updates"
