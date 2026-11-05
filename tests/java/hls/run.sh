#!/usr/bin/env bash
# P4b: JVM-Tests des HLS-Kerns der App (de.evp.helfer.hls) – strenger Parser, RFC-3986-Auflösung, Plan/Budgets und
# Abrufe über den echten SafeHttpClient gegen einen lokalen Server. Abhängigkeiten wie tests/java/dl/run.sh.
#   tests/java/hls/run.sh FIXTURES_DIR [CACHE_DIR]   (FIXTURES_DIR aus poc/p4a-hls/tools/make-fixtures.sh)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
FX="${1:?fixtures dir}"
CACHE="${2:-${RUNNER_TEMP:-/tmp}/safe-http-deps}"
mkdir -p "$CACHE"
fetch() {
  local f="$CACHE/$(basename "$1")"
  if [ ! -f "$f" ] || [ "$(sha256sum < "$f" | cut -d' ' -f1)" != "$2" ]; then
    curl -fsSL --retry 3 -o "$f.part" "https://repo1.maven.org/maven2/$1"; mv "$f.part" "$f"
  fi
  echo "$2  $f" | sha256sum -c --quiet -
}
fetch com/squareup/okhttp3/okhttp-jvm/5.5.1/okhttp-jvm-5.5.1.jar 4039527bd1f2e40b66e20b0e52c689cf56074b938ea02678141495f5160f1047
fetch com/squareup/okio/okio-jvm/3.18.1/okio-jvm-3.18.1.jar b97b640557a650d15411f2be29aef558c4e422a83653cb18ba494311dbed18be
fetch org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.jar 6558a3d233da56a20934b32159f9db5f86ed5816ef098f78a2c223dc6abb79dd
CP="$CACHE/okhttp-jvm-5.5.1.jar:$CACHE/okio-jvm-3.18.1.jar:$CACHE/kotlin-stdlib-2.2.21.jar"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
SRC="${APP_SRC:-$ROOT/app/src/main/java}/de/evp/helfer"   # mutation.py setzt eine veränderte Kopie
javac -Xlint:-options -d "$WORK/c" -cp "$CP" $(ls "$SRC"/net/*.java | grep -v '/SafeHttp\.java$') "$SRC"/hls/*.java \
  "$ROOT/tests/java/dl/de/evp/helfer/net/LocalTestClients.java" "$HERE"/de/evp/helfer/hls/*.java
java -Xmx64m -cp "$WORK/c:$CP" de.evp.helfer.hls.TestHls "$FX"
