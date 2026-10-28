#!/usr/bin/env bash
# Compile API usage only: no dex/APK, signing, Android execution or generated app artifacts.
set -euo pipefail
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
sdk=$(find "${ANDROID_HOME:?}/platforms" -name android.jar -type f | sort -V | tail -1)
test -n "$sdk"
cat > "$work/R.java" <<'JAVA'
package de.evp.helfer;
final class R { static final class drawable { static final int ic_stat=0; } }
JAVA
cat > "$work/MainActivity.java" <<'JAVA'
package de.evp.helfer;
final class MainActivity {} // Only the notification's Intent class literal; never executed.
JAVA
src=app/src/main/java/de/evp/helfer
javac -cp "$sdk:tests/java/classes" -d "$work/classes" \
  "$src/Downloads.java" "$src/HelferService.java" "$src/Keys.java" "$work/R.java" "$work/MainActivity.java" \
  app/src/main/java/poc/Core.java "$src/ChannelServer.java" "$src/ControlCommands.java" "$src/HttpRequestReader.java" \
  "$src/SnapshotWriter.java" "$src/IdentityStore.java" "$src/RegistryStore.java"
echo "PASS: actual Downloads/HelferService/Keys and channel classes compile against $sdk; no APK built"
