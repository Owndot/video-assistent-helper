#!/usr/bin/env bash
# Builds the isolated test app and its instrumentation APK, signed with a
# throwaway key that is generated here and deleted afterwards. Never uses a
# production or previous helper signer. Nothing is published.
#
# Gradle 8.9 comes from the checked-in wrapper (distribution SHA-256 pinned in
# app-project/gradle/wrapper/gradle-wrapper.properties). Runs on Linux and macOS.
# Usage: build-apk.sh OUT   (OUT/jniLibs + OUT/jniLibs-test from build-native.sh;
#                            needs ANDROID_HOME with build-tools 34.0.0 and JDK 17)
#
# APK hashes differ on every build because every build has its own signing
# key. What stays comparable is printed last: the native libraries inside the
# APK, byte-compared with the build-native.sh output.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(cd "${1:?usage: build-apk.sh OUT}" && pwd)"
. "$HERE/tools/lib-checks.sh"
check_jdk
[ -d "$OUT/jniLibs" ] && [ -d "$OUT/jniLibs-test" ] || { echo "run build-native.sh first" >&2; exit 1; }
BT="${ANDROID_HOME:?set ANDROID_HOME}/build-tools/34.0.0"
[ -x "$BT/apksigner" ] && [ -x "$BT/aapt2" ] || { echo "build-tools 34.0.0 required" >&2; exit 1; }
KEYDIR="$(mktemp -d)"
trap 'rm -rf "$KEYDIR"' EXIT
# The password only protects a key that is deleted after this build; it is
# passed through the environment, not the command line.
ORG_GRADLE_PROJECT_pocKeystorePass="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
export ORG_GRADLE_PROJECT_pocKeystorePass
keytool -genkeypair -storetype PKCS12 -keystore "$KEYDIR/throwaway.p12" -alias pake-poc-throwaway \
  -keyalg RSA -keysize 3072 -validity 3 -dname "CN=PAKE PoC TEST ONLY - throwaway" \
  -storepass:env ORG_GRADLE_PROJECT_pocKeystorePass -keypass:env ORG_GRADLE_PROJECT_pocKeystorePass >/dev/null 2>&1
"$HERE/app-project/gradlew" --no-daemon -q -p "$HERE/app-project" :testapp:assembleDebug :testapp:assembleDebugAndroidTest \
  -PpocJniLibs="$OUT/jniLibs" -PpocJniLibsTest="$OUT/jniLibs-test" -PpocKeystore="$KEYDIR/throwaway.p12"
mkdir -p "$OUT/apk"
APP="$OUT/apk/pake-poc-testapp.apk"
TEST="$OUT/apk/pake-poc-testapp-androidTest.apk"
cp "$HERE/app-project/testapp/build/outputs/apk/debug/testapp-debug.apk" "$APP"
cp "$HERE/app-project/testapp/build/outputs/apk/androidTest/debug/testapp-debug-androidTest.apk" "$TEST"

# Contents of the installable APK: own package, backup/transfer rules, no test library.
manifest="$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APP")"
grep -q 'package="de.evp.pakepoc.test"' <<<"$manifest" || { echo "unexpected package" >&2; exit 1; }
grep -q 'dataExtractionRules' <<<"$manifest" || { echo "dataExtractionRules missing" >&2; exit 1; }
grep -q 'allowBackup.*=false' <<<"$manifest" || { echo "allowBackup not false" >&2; exit 1; }
if unzip -l "$APP" | grep -q 'testinit'; then echo "test library inside the installable APK" >&2; exit 1; fi
unzip -l "$TEST" | grep -q 'libkadri_pairing_poc_testinit.so' || { echo "test library missing in instrumentation APK" >&2; exit 1; }

for a in "$APP" "$TEST"; do
  echo "== $(basename "$a")  (hash differs per build: throwaway signer)"
  (cd "$OUT" && sha256 "apk/$(basename "$a")")
  "$BT/apksigner" verify --print-certs "$a" | grep -E 'Signer #1 certificate (DN|SHA-256)'
done
# Native libraries inside the APKs must be byte-identical to the reproducible build.
echo "== native libraries: build output vs. inside the APK"
for abi in $(ls "$OUT/jniLibs"); do
  for pair in "jniLibs/$abi/libkadri_pairing_poc.so:$APP:lib/$abi/libkadri_pairing_poc.so" \
              "jniLibs-test/$abi/libkadri_pairing_poc_testinit.so:$TEST:lib/$abi/libkadri_pairing_poc_testinit.so"; do
    built="${pair%%:*}"; rest="${pair#*:}"; apk="${rest%%:*}"; entry="${rest#*:}"
    h_built="$(sha256 "$OUT/$built" | cut -d' ' -f1)"
    h_apk="$(unzip -p "$apk" "$entry" | sha256 | cut -d' ' -f1)"
    [ "$h_built" = "$h_apk" ] || { echo "MISMATCH $entry" >&2; exit 1; }
    echo "$h_apk  $(basename "$apk"):$entry  (= $built)"
  done
done
