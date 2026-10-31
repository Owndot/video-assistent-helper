#!/usr/bin/env bash
# Tests der MP4-Download-Engine (de.evp.helfer.dl) auf der JVM gegen echte lokale HTTP-Server.
# Übersetzt den Android-freien Kern (net ohne SafeHttp.java, dl, ControlCommands) gegen OkHttp 5.5.1 (JVM-Artefakt)
# und läuft mit 48 MiB Heap; die 512-MiB-Datei muss also gestreamt werden. Abhängigkeiten wie tests/java/net/run.sh.
# Aufruf: tests/java/dl/run.sh [CACHE_DIR]   (DL_JAVA_OPTS="-Ddl.large=BYTES" ändert die Größe der großen Datei)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
CACHE="${1:-${RUNNER_TEMP:-/tmp}/safe-http-deps}"
mkdir -p "$CACHE"
fetch() {  # $1 Maven-Pfad, $2 SHA-256
  local f="$CACHE/$(basename "$1")"
  if [ ! -f "$f" ] || [ "$(sha256sum < "$f" | cut -d' ' -f1)" != "$2" ]; then
    curl -fsSL --retry 3 -o "$f.part" "https://repo1.maven.org/maven2/$1"
    mv "$f.part" "$f"
  fi
  echo "$2  $f" | sha256sum -c --quiet -
}
fetch com/squareup/okhttp3/okhttp-jvm/5.5.1/okhttp-jvm-5.5.1.jar 4039527bd1f2e40b66e20b0e52c689cf56074b938ea02678141495f5160f1047
fetch com/squareup/okio/okio-jvm/3.18.1/okio-jvm-3.18.1.jar b97b640557a650d15411f2be29aef558c4e422a83653cb18ba494311dbed18be
fetch org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.jar 6558a3d233da56a20934b32159f9db5f86ed5816ef098f78a2c223dc6abb79dd
CP="$CACHE/okhttp-jvm-5.5.1.jar:$CACHE/okio-jvm-3.18.1.jar:$CACHE/kotlin-stdlib-2.2.21.jar"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SRC="${APP_SRC:-$ROOT/app/src/main/java}/de/evp/helfer"   # mutation.py setzt eine veränderte Kopie
javac -d "$WORK/classes" -cp "$CP" \
  $(ls "$SRC"/net/*.java | grep -v '/SafeHttp\.java$') "$SRC"/dl/*.java "$SRC"/ControlCommands.java \
  "$HERE"/de/evp/helfer/net/*.java "$HERE"/de/evp/helfer/dl/*.java
mkdir -p "$WORK/files"
java -Xmx48m ${DL_JAVA_OPTS:-} -cp "$WORK/classes:$CP" de.evp.helfer.dl.TestDirectDownloads "$WORK/files"
