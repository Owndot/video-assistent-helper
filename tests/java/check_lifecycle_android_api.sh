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
# P4b: HlsDownloads nutzt den App-Remuxer (Media3 Extractor + Muxer 1.11.1, Guava 33.7.2-android) – nur zum Übersetzen,
# dieselben SHA-256 wie gradle/verification-metadata.xml.
gfetch() {  # $1 Google-Maven-Pfad, $2 SHA-256
  local f="$deps/$(basename "$1")"
  if [ ! -f "$f" ] || [ "$(sha256sum < "$f" | cut -d' ' -f1)" != "$2" ]; then
    curl -fsSL --retry 3 -o "$f.part" "https://dl.google.com/dl/android/maven2/$1" && mv "$f.part" "$f"
  fi
  echo "$2  $f" | sha256sum -c --quiet -
}
gfetch androidx/media3/media3-common/1.11.1/media3-common-1.11.1.aar 50813cf219c7c93ed8a32a750891ca6d1280729ecf7a90c49567adacd67ef31b
gfetch androidx/media3/media3-container/1.11.1/media3-container-1.11.1.aar 930fca2f018f06f1d08c12599f8c97f31eddbd728edee5de06eb53a882f8fc54
gfetch androidx/media3/media3-decoder/1.11.1/media3-decoder-1.11.1.aar c3bcd704959026e0d644b0260f9e682a2e26e7357c2cf82d990bcb323f800d49
gfetch androidx/media3/media3-extractor/1.11.1/media3-extractor-1.11.1.aar 6b2d58d23a0c53425249d8b7c945600019d61a8b404c8fb8cd9ca860c5738508
gfetch androidx/media3/media3-muxer/1.11.1/media3-muxer-1.11.1.aar f68fa5996882a5f08ea0925463bc56e1c2b20ead3f556dadba24686fc4c979f1
fetch com/google/guava/guava/33.7.2-android/guava-33.7.2-android.jar b949fe975a32421c1a2633c07a4f1578b462f2f467668aa0a4023c5ff7c4a808
for m in common container decoder extractor muxer; do
  mkdir -p "$work/m3/$m"; (cd "$work/m3/$m" && unzip -q -o "$deps/media3-$m-1.11.1.aar" classes.jar)
  cp_net="$cp_net:$work/m3/$m/classes.jar"
done
cp_net="$cp_net:$deps/guava-33.7.2-android.jar"
src=app/src/main/java/de/evp/helfer
javac -cp "$sdk:tests/java/classes:$cp_net" -d "$work/classes" \
  "$src/Downloads.java" "$src/HelferService.java" "$src/Keys.java" "$work/R.java" "$work/MainActivity.java" \
  app/src/main/java/poc/Core.java "$src/ChannelServer.java" "$src/ControlCommands.java" "$src/HttpRequestReader.java" \
  "$src/SnapshotWriter.java" "$src/IdentityStore.java" "$src/RegistryStore.java" \
  "$src/Mp4Downloads.java" "$src/Tr.java" "$src"/dl/*.java "$src"/net/*.java \
  "$src/HlsDownloads.java" "$src/KeystoreSealer.java" "$src/NetWatch.java" "$src"/hls/*.java "$src"/hlsmux/*.java
echo "PASS: actual Downloads/HelferService/Keys, Mp4Downloads, HlsDownloads, KeystoreSealer, NetWatch, dl/, net/, hls/, hlsmux/ and channel classes compile against $sdk; no APK built"
