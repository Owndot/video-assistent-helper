#!/usr/bin/env bash
# NUR Selbsttest des Signierwegs (z. B. auf macOS ohne App-Build): erzeugt ein unsigniertes Mini-Paket mit gegebenem
# Paketnamen und Label und den zwei erwarteten Bibliotheksnamen (Inhalt: ein Byte, kein Code). Nicht lauffähig, nie
# verteilt. Aufruf: tools/p4test/synthetic-apk.sh OUT.apk PACKAGE LABEL   (ANDROID_HOME: build-tools 34.0.0, android-34)
set -euo pipefail
OUT="${1:?out}"; PKG="${2:?package}"; LABEL="${3:?label}"
BT="${ANDROID_HOME:?set ANDROID_HOME}/build-tools/34.0.0"
JAR="$ANDROID_HOME/platforms/android-34/android.jar"
[ -x "$BT/aapt2" ] && [ -x "$BT/zipalign" ] && [ -f "$JAR" ] || { echo "build-tools 34.0.0 and platform android-34 required" >&2; exit 1; }
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
# Label als String-Ressource wie in der echten App (aapt2 meldet es dann als application-label)
mkdir -p "$W/res/values"
cat > "$W/res/values/strings.xml" <<XML
<resources><string name="app_name">$LABEL</string></resources>
XML
cat > "$W/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$PKG" android:versionCode="7" android:versionName="selftest">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34" />
    <application android:label="@string/app_name" android:allowBackup="false" />
</manifest>
XML
"$BT/aapt2" compile -o "$W/res.zip" --dir "$W/res"
"$BT/aapt2" link -o "$W/base.apk" --manifest "$W/AndroidManifest.xml" -I "$JAR" "$W/res.zip"
mkdir -p "$W/z/lib/arm64-v8a" "$W/z/lib/x86_64"
printf 'x' > "$W/z/lib/arm64-v8a/libkadri_pairing_poc.so"
printf 'x' > "$W/z/lib/x86_64/libkadri_pairing_poc.so"
(cd "$W/z" && zip -q -r -D "$W/base.apk" lib)   # -D: keine Ordnereinträge (wie in echten APKs)
"$BT/zipalign" -p -f 4 "$W/base.apk" "$OUT"
echo "SYNTHETIC $PKG (unsigned, no code, never installed)"
