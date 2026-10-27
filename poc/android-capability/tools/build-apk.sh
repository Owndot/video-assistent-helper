#!/usr/bin/env bash
# Builds the isolated test app and its instrumentation APK, signed with a
# throwaway key that is generated here and deleted afterwards. Never uses a
# production or previous helper signer. Nothing is published.
# Usage: build-apk.sh OUT   (OUT/jniLibs from build-native.sh; needs ANDROID_HOME, gradle 8.9, JDK 17)
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(cd "${1:?usage: build-apk.sh OUT}" && pwd)"
[ -d "$OUT/jniLibs" ] || { echo "run build-native.sh first" >&2; exit 1; }
KEYDIR="$(mktemp -d)"
trap 'rm -rf "$KEYDIR"' EXIT
# The password only protects a key that is deleted after this build; it is
# passed through the environment, not the command line.
export ORG_GRADLE_PROJECT_pocKeystorePass="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
keytool -genkeypair -storetype PKCS12 -keystore "$KEYDIR/throwaway.p12" -alias pake-poc-throwaway \
  -keyalg RSA -keysize 3072 -validity 3 -dname "CN=PAKE PoC TEST ONLY - throwaway" \
  -storepass:env ORG_GRADLE_PROJECT_pocKeystorePass -keypass:env ORG_GRADLE_PROJECT_pocKeystorePass >/dev/null 2>&1
gradle --no-daemon -q -p "$HERE/app-project" :testapp:assembleDebug :testapp:assembleDebugAndroidTest \
  -PpocJniLibs="$OUT/jniLibs" -PpocKeystore="$KEYDIR/throwaway.p12"
mkdir -p "$OUT/apk"
cp "$HERE/app-project/testapp/build/outputs/apk/debug/testapp-debug.apk" "$OUT/apk/pake-poc-testapp.apk"
cp "$HERE/app-project/testapp/build/outputs/apk/androidTest/debug/testapp-debug-androidTest.apk" "$OUT/apk/pake-poc-testapp-androidTest.apk"
APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/34.0.0/apksigner)"
for a in "$OUT"/apk/*.apk; do
  echo "== $(basename "$a")"
  sha256sum "$a" | sed "s#$OUT/##"
  "$APKSIGNER" verify --print-certs "$a" | grep -E 'Signer #1 certificate (DN|SHA-256)'
done
# Native libraries inside the APK must be byte-identical to the reproducible build.
for abi in $(ls "$OUT/jniLibs"); do
  unzip -p "$OUT/apk/pake-poc-testapp.apk" "lib/$abi/libkadri_pairing_poc.so" | sha256sum | sed "s#-#apk:lib/$abi/libkadri_pairing_poc.so#"
done
