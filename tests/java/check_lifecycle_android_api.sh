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
# Phase C2: HelferService nutzt Mp4Downloads (MediaStore, StatFs, Benachrichtigungen) und damit dl/ und net/ (OkHttp).
# OkHttp nur zum Übersetzen, festgelegt per SHA-256 wie tests/java/net/run.sh; okhttp-android statt -jvm, weil SafeHttp
# OkHttp.initialize(Context) nutzt (nur im Android-Artefakt).
deps="${SAFE_HTTP_DEPS:-${RUNNER_TEMP:-/tmp}/safe-http-deps}"; mkdir -p "$deps"
fetch() {  # $1 Maven-Pfad, $2 SHA-256
  local f="$deps/$(basename "$1")"
  if [ ! -f "$f" ] || [ "$(sha256sum < "$f" | cut -d' ' -f1)" != "$2" ]; then
    curl -fsSL --retry 3 -o "$f.part" "https://repo1.maven.org/maven2/$1" && mv "$f.part" "$f"
  fi
  echo "$2  $f" | sha256sum -c --quiet -
}
fetch com/squareup/okio/okio-jvm/3.18.1/okio-jvm-3.18.1.jar b97b640557a650d15411f2be29aef558c4e422a83653cb18ba494311dbed18be
fetch org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.jar 6558a3d233da56a20934b32159f9db5f86ed5816ef098f78a2c223dc6abb79dd
okhttp_aar="$deps/okhttp-android-5.5.1.aar"
fetch com/squareup/okhttp3/okhttp-android/5.5.1/okhttp-android-5.5.1.aar ce00fd7751cf7e4f28ba73a0287b3cc0c62b8b83f038e428592eeef189513ee1
(cd "$work" && unzip -q -o "$okhttp_aar" classes.jar && mv classes.jar okhttp-android.jar)
cp_net="$work/okhttp-android.jar:$deps/okio-jvm-3.18.1.jar:$deps/kotlin-stdlib-2.2.21.jar"
src=app/src/main/java/de/evp/helfer
javac -cp "$sdk:tests/java/classes:$cp_net" -d "$work/classes" \
  "$src/Downloads.java" "$src/HelferService.java" "$src/Keys.java" "$work/R.java" "$work/MainActivity.java" \
  app/src/main/java/poc/Core.java "$src/ChannelServer.java" "$src/ControlCommands.java" "$src/HttpRequestReader.java" \
  "$src/SnapshotWriter.java" "$src/IdentityStore.java" "$src/RegistryStore.java" \
  "$src/Mp4Downloads.java" "$src/Tr.java" "$src"/dl/*.java "$src"/net/*.java
echo "PASS: actual Downloads/HelferService/Keys, Mp4Downloads, dl/, net/ and channel classes compile against $sdk; no APK built"
