#!/usr/bin/env bash
# Negativ- und Funktionstests der sicheren Netzwerkschicht (de.evp.helfer.net) auf der JVM.
# Übersetzt den Android-freien Kern (alles außer SafeHttp.java) gegen OkHttp 5.5.1 (JVM-Artefakt, gleiche Version wie
# die App) und führt die Tests mit begrenztem Heap aus. Abhängigkeiten von Maven Central mit festen SHA-256.
# Aufruf: tests/java/net/run.sh [CACHE_DIR]
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
NET="$ROOT/app/src/main/java/de/evp/helfer/net"
# Android-freier Kern: SafeHttp.java (Context, ConnectivityManager) gehört nicht dazu.
javac -d "$WORK/classes" -cp "$CP" $(ls "$NET"/*.java | grep -v '/SafeHttp\.java$') "$HERE"/de/evp/helfer/net/*.java
# Wegwerf-TLS-Identität nur für diesen Lauf (CN/SAN 127.0.0.1), wird mit WORK gelöscht.
keytool -genkeypair -storetype PKCS12 -keystore "$WORK/server.p12" -storepass throwaway -keypass throwaway -alias server \
  -keyalg EC -groupname secp256r1 -validity 1 -dname "CN=127.0.0.1" -ext "SAN=ip:127.0.0.1" > /dev/null 2>&1
java -Xmx48m -cp "$WORK/classes:$CP" de.evp.helfer.net.TestSafeHttp "$WORK"
