#!/usr/bin/env bash
# NUR TESTS. Baut die Testvariante de.evp.helfer.channeltest (App + Instrumentierungs-APK) mit einem
# Wegwerf-Signierer, der hier erzeugt und danach gelöscht wird. Nie das Produktionspaket de.evp.helfer, nie ein
# produktiver oder früherer Signierschlüssel. Nichts wird veröffentlicht; die APKs bleiben in OUT/apk.
# Gradle 8.9 kommt aus dem eingecheckten Wrapper des PoC (Distributions-SHA-256 dort festgelegt).
# Aufruf: tools/build-channel-apk.sh OUT   (OUT/jniLibs + OUT/jniLibs-test aus tools/build-channel-native.sh;
#                                          ANDROID_HOME mit build-tools 34.0.0, JDK 17)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(cd "${1:?usage: build-channel-apk.sh OUT}" && pwd)"
. "$ROOT/poc/android-capability/tools/lib-checks.sh"
[ -d "$OUT/jniLibs" ] && [ -d "$OUT/jniLibs-test" ] || { echo "run tools/build-channel-native.sh first" >&2; exit 1; }
BT="${ANDROID_HOME:?set ANDROID_HOME}/build-tools/34.0.0"
[ -x "$BT/apksigner" ] && [ -x "$BT/aapt2" ] || { echo "build-tools 34.0.0 required" >&2; exit 1; }
KEYDIR="$(mktemp -d)"
trap 'rm -rf "$KEYDIR"' EXIT
# Das Passwort schützt nur einen Schlüssel, der nach diesem Build gelöscht wird; Übergabe über die Umgebung.
ORG_GRADLE_PROJECT_channelKeystorePass="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
export ORG_GRADLE_PROJECT_channelKeystorePass
keytool -genkeypair -storetype PKCS12 -keystore "$KEYDIR/throwaway.p12" -alias helper-channel-throwaway \
  -keyalg RSA -keysize 3072 -validity 3 -dname "CN=Helper channel TEST ONLY - throwaway" \
  -storepass:env ORG_GRADLE_PROJECT_channelKeystorePass -keypass:env ORG_GRADLE_PROJECT_channelKeystorePass >/dev/null 2>&1
"$ROOT/poc/android-capability/app-project/gradlew" --no-daemon -q -p "$ROOT" \
  :app:assembleChanneltest :app:assembleChanneltestAndroidTest -PchannelTests -Pandroid.useAndroidX=true \
  -PchannelJniLibs="$OUT/jniLibs" -PchannelJniLibsTest="$OUT/jniLibs-test" -PchannelKeystore="$KEYDIR/throwaway.p12" \
  ${CHANNEL_SIZE_ARGS:-}
mkdir -p "$OUT/apk"
APP="$OUT/apk/helper-channeltest.apk"
TEST="$OUT/apk/helper-channeltest-androidTest.apk"
cp "$ROOT/app/build/outputs/apk/channeltest/app-channeltest.apk" "$APP"
cp "$ROOT/app/build/outputs/apk/androidTest/channeltest/app-channeltest-androidTest.apk" "$TEST"

# Installierbare APK: eigenes Testpaket, Sicherungsregeln, Kanalbibliothek für beide ABIs, keine Testbibliothek.
manifest="$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APP")"
grep -q 'package="de.evp.helfer.channeltest"' <<<"$manifest" || { echo "unexpected package" >&2; exit 1; }
grep -q 'dataExtractionRules' <<<"$manifest" || { echo "dataExtractionRules missing" >&2; exit 1; }
grep -q 'fullBackupContent' <<<"$manifest" || { echo "fullBackupContent missing" >&2; exit 1; }
grep -q 'allowBackup.*=false' <<<"$manifest" || { echo "allowBackup not false" >&2; exit 1; }
if grep -q 'debuggable.*=true' <<<"$manifest"; then echo "test APK is debuggable" >&2; exit 1; fi
for abi in arm64-v8a x86_64; do
  unzip -l "$APP" | grep -q "lib/$abi/libkadri_pairing_poc.so" || { echo "channel library missing for $abi" >&2; exit 1; }
done
if unzip -l "$APP" | grep -q 'testinit'; then echo "test library inside the installable APK" >&2; exit 1; fi
unzip -l "$TEST" | grep -q 'libkadri_pairing_poc_testinit.so' || { echo "test library missing in instrumentation APK" >&2; exit 1; }
for a in "$APP" "$TEST"; do
  echo "== $(basename "$a")  (Hash ändert sich mit jedem Build: Wegwerf-Signierer)"
  (cd "$OUT" && sha256 "apk/$(basename "$a")")
  certs="$("$BT/apksigner" verify --print-certs "$a")"
  grep -q 'Signer #1 certificate DN: CN=Helper channel TEST ONLY - throwaway' <<<"$certs" || { echo "unexpected signer" >&2; exit 1; }
  grep -E 'Signer #1 certificate (DN|SHA-256)' <<<"$certs"
done
echo "== Nativbibliotheken: Build-Ausgabe = Inhalt der APK"
for abi in $(ls "$OUT/jniLibs"); do
  for pair in "jniLibs/$abi/libkadri_pairing_poc.so:$APP:lib/$abi/libkadri_pairing_poc.so" \
              "jniLibs-test/$abi/libkadri_pairing_poc_testinit.so:$TEST:lib/$abi/libkadri_pairing_poc_testinit.so"; do
    built="${pair%%:*}"; rest="${pair#*:}"; apk="${rest%%:*}"; entry="${rest#*:}"
    h_built="$(sha256 "$OUT/$built" | cut -d' ' -f1)"
    h_apk="$(unzip -p "$apk" "$entry" | sha256 | cut -d' ' -f1)"
    [ "$h_built" = "$h_apk" ] || { echo "MISMATCH $entry" >&2; exit 1; }
    echo "$h_apk  $(basename "$apk"):$entry"
  done
done
echo "APK-Größe (Bytes): $(wc -c < "$APP" | tr -d ' ') App, $(wc -c < "$TEST" | tr -d ' ') Instrumentierung"
