#!/usr/bin/env bash
# Baut die P4-Testapp de.evp.helfer.p4test UNSIGNIERT (nicht installierbar). Signieren nur lokal mit sign.sh.
# Aufruf: tools/p4test/build-unsigned.sh OUT VERSION_CODE   (OUT/jniLibs aus tools/build-channel-native.sh)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
. "$ROOT/tools/p4test/lib.sh"
OUT="$(cd "${1:?usage: build-unsigned.sh OUT VERSION_CODE}" && pwd)"; CODE="${2:?version code}"
[ -d "$OUT/jniLibs" ] || die "run tools/build-channel-native.sh first"
BT="${ANDROID_HOME:?set ANDROID_HOME}/build-tools/34.0.0"
"$ROOT/gradlew" --no-daemon -q -p "$ROOT" :app:assembleP4test -Pandroid.useAndroidX=true \
  -PchannelJniLibs="$OUT/jniLibs" -Pp4VersionCode="$CODE"
test -s "$ROOT/app/build/outputs/mapping/p4test/mapping.txt" || die "p4test APK was not processed by R8"
mkdir -p "$OUT/p4"
APK="$OUT/p4/helper-p4test-unsigned.apk"
cp "$ROOT/app/build/outputs/apk/p4test/app-p4test-unsigned.apk" "$APK"
badging="$("$BT/aapt2" dump badging "$APK")"
grep -q "^package: name='$P4_PACKAGE' versionCode='$CODE'" <<<"$badging" || die "unexpected package or version code"
grep -q "application-label:'$P4_LABEL'" <<<"$badging" || die "unexpected label"
if grep -q 'application-debuggable' <<<"$badging"; then die "debuggable"; fi
if "$BT/apksigner" verify "$APK" >/dev/null 2>&1; then die "Gradle signed the P4 test app"; fi
if unzip -l "$APK" | grep -q 'testinit'; then die "test library inside the P4 test app"; fi
echo "UNSIGNED $P4_PACKAGE versionCode=$CODE (not installable)"
