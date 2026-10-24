#!/usr/bin/env bash
# Disposable test identities only. Never load a production keystore or repository secret.
set -euo pipefail
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p migration-fixtures
signer=$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -1)
for identity in old new; do
  keytool -genkeypair -keystore "$work/$identity.jks" -storetype JKS -storepass fixture-password -keypass fixture-password -alias fixture -keyalg RSA -keysize 2048 -validity 2 -dname "CN=MIGRATION-TEST-$identity"
done
gradle :app:assembleDebug -PmigrationFixtureVersion=100 --no-daemon
"$signer" sign --ks "$work/old.jks" --ks-pass pass:fixture-password --out migration-fixtures/old.apk app/build/outputs/apk/debug/app-debug.apk
gradle :app:assembleDebug -PmigrationFixtureVersion=101 --no-daemon
"$signer" sign --ks "$work/new.jks" --ks-pass pass:fixture-password --out migration-fixtures/fresh.apk app/build/outputs/apk/debug/app-debug.apk
"$signer" rotate --out "$work/lineage" --old-signer --ks "$work/old.jks" --ks-pass pass:fixture-password --new-signer --ks "$work/new.jks" --ks-pass pass:fixture-password
gradle :app:assembleDebug -PmigrationFixtureVersion=101 -PmigrationFixtureMinSdk=33 --no-daemon
"$signer" sign --ks "$work/old.jks" --ks-pass pass:fixture-password --next-signer --ks "$work/new.jks" --ks-pass pass:fixture-password --lineage "$work/lineage" --rotation-min-sdk-version 33 --out migration-fixtures/modern.apk app/build/outputs/apk/debug/app-debug.apk
for apk in migration-fixtures/*.apk; do
  "$signer" verify --verbose --print-certs "$apk"
done
sha256sum migration-fixtures/*.apk > migration-fixtures/SHA256SUMS
