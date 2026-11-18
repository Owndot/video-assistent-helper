#!/usr/bin/env bash
# P4b-4: der App-Remuxer (de.evp.helfer.hlsmux, Media3 1.11.1 Extractor + Mp4Muxer) auf dem JVM gegen alle synthetischen
# Fixtures, mit denselben Media3-Binärartefakten wie die App (SHA-256 wie in gradle/verification-metadata.xml).
# Android-Klassen kommen aus Robolectrics android-all (nur zum Übersetzen/Laden); wenige JVM-Shims (shim/) ersetzen
# native Teile. Jede Ausgabe wird mit poc/p4a-hls/tools/analyze.py gegen die Quelle geprüft (Bilder, Dauer, A/V-Versatz).
# Nur Test-Infrastruktur: nichts davon gelangt in die App.
#   tests/java/hlsmux/run.sh FIXTURES_DIR [CACHE_DIR]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
FX="${1:?fixtures dir}"
CACHE="${2:-${RUNNER_TEMP:-/tmp}/hlsmux-deps}"
mkdir -p "$CACHE"
fetch() {   # fetch BASE PATH SHA256
  local f="$CACHE/$(basename "$2")"
  if [ ! -f "$f" ] || [ "$(sha256sum < "$f" | cut -d' ' -f1)" != "$3" ]; then
    curl -fsSL --retry 3 -o "$f.part" "$1/$2"; mv "$f.part" "$f"
  fi
  echo "$3  $f" | sha256sum -c --quiet -
}
C=https://repo1.maven.org/maven2
G=https://dl.google.com/dl/android/maven2
fetch $C org/robolectric/android-all/16-robolectric-13921718-2/android-all-16-robolectric-13921718-2.jar ead18e465e8a4c2b93a59d7d7a68c1830f3cd8f1b6bcdfd96f45d658ba979ce3
fetch $C com/google/guava/guava/33.7.2-android/guava-33.7.2-android.jar b949fe975a32421c1a2633c07a4f1578b462f2f467668aa0a4023c5ff7c4a808
fetch $C com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar 8a8f81cf9b359e3f6dfa691a1e776985c061ef2f223c9b2c80753e1b458e8064
fetch $C org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar 1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
fetch $C org/checkerframework/checker-qual/3.43.0/checker-qual-3.43.0.jar 3fbc2e98f05854c3df16df9abaa955b91b15b3ecac33623208ed6424640ef0f6
fetch $C com/google/errorprone/error_prone_annotations/2.28.0/error_prone_annotations-2.28.0.jar f3fc8a3a0a4020706a373b00e7f57c2512dd26d1f83d28c7d38768f8682b231e
fetch $C org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.jar 6558a3d233da56a20934b32159f9db5f86ed5816ef098f78a2c223dc6abb79dd
# HLSMUX_MEDIA3_CP: nur lokal, wenn Google Maven nicht erreichbar ist (aus den 1.11.1-Quellen übersetzte Klassen)
if [ -z "${HLSMUX_MEDIA3_CP:-}" ]; then
  fetch $G androidx/media3/media3-common/1.11.1/media3-common-1.11.1.aar 50813cf219c7c93ed8a32a750891ca6d1280729ecf7a90c49567adacd67ef31b
  fetch $G androidx/media3/media3-container/1.11.1/media3-container-1.11.1.aar 930fca2f018f06f1d08c12599f8c97f31eddbd728edee5de06eb53a882f8fc54
  fetch $G androidx/media3/media3-decoder/1.11.1/media3-decoder-1.11.1.aar c3bcd704959026e0d644b0260f9e682a2e26e7357c2cf82d990bcb323f800d49
  fetch $G androidx/media3/media3-extractor/1.11.1/media3-extractor-1.11.1.aar 6b2d58d23a0c53425249d8b7c945600019d61a8b404c8fb8cd9ca860c5738508
  fetch $G androidx/media3/media3-muxer/1.11.1/media3-muxer-1.11.1.aar f68fa5996882a5f08ea0925463bc56e1c2b20ead3f556dadba24686fc4c979f1
fi
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
CP="${HLSMUX_MEDIA3_CP:+:$HLSMUX_MEDIA3_CP}"
if [ -z "${HLSMUX_MEDIA3_CP:-}" ]; then
  for m in common container decoder extractor muxer; do
    mkdir -p "$WORK/aar/$m"
    unzip -q -o "$CACHE/media3-$m-1.11.1.aar" classes.jar -d "$WORK/aar/$m"
    CP="$CP:$WORK/aar/$m/classes.jar"
  done
fi
for j in guava-33.7.2-android failureaccess-1.0.2 jspecify-1.0.0 checker-qual-3.43.0 error_prone_annotations-2.28.0 kotlin-stdlib-2.2.21; do CP="$CP:$CACHE/$j.jar"; done
ANDROID="$CACHE/android-all-16-robolectric-13921718-2.jar"
javac -nowarn -d "$WORK/shim" -cp "$ANDROID" $(find "$HERE/shim" -name '*.java')
SRC="${APP_SRC:-$ROOT/app/src/main/java}/de/evp/helfer"   # Mutationsproben setzen eine veränderte Kopie
javac -nowarn -proc:none -d "$WORK/c" -cp "$ANDROID$CP" "$SRC"/hlsmux/*.java "$SRC"/hls/HlsException.java "$SRC"/hls/HlsRemux.java \
  "$HERE"/de/evp/helfer/hlsmux/*.java
# Shims vor android-all (ersetzen native Teile); Heap bewusst klein: der Remux muss mit festen Puffern auskommen
if [ "${HLSMUX_CAPTURE_ONLY:-0}" != 1 ]; then
  java -Xmx${HLSMUX_XMX:-192m} -cp "$WORK/shim:$WORK/c$CP:$ANDROID" de.evp.helfer.hlsmux.TestMedia3Remux "$FX" "$WORK/out" "$ROOT/poc/p4a-hls/tools/analyze.py"
fi
java -Xmx${HLSMUX_XMX:-192m} -cp "$WORK/shim:$WORK/c$CP:$ANDROID" de.evp.helfer.hlsmux.TestCapturedMedia "$FX" "$WORK/capture" "${HLSMUX_CAPTURE_FIXTURE:-}"
