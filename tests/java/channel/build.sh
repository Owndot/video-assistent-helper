#!/usr/bin/env bash
# NUR TESTS. Baut den Rust-Kern für den Build-Rechner (Helper-Seite: Feature jni; Kadri-Seite für die Prüfungen:
# Feature jni-test-initiator) und übersetzt die Kanal-Klassen der Helper-App für ein Desktop-JVM zusammen mit
# ChannelHarness (für Kadris Integrationstests) und ChannelChecks. Nichts davon wird ausgeliefert.
# Aufruf: tests/java/channel/build.sh OUT
#   -> OUT/lib/libkadri_pairing_poc.so, OUT/lib/libkadri_pairing_poc_testinit.so, OUT/classes
#   Harness: java -Djava.library.path=OUT/lib -cp OUT/classes de.evp.helfer.ChannelHarness STATE_DIR
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
CORE="$ROOT/poc/pake-channel/core"
OUT="$(mkdir -p "${1:?usage: build.sh OUT}" && cd "$1" && pwd)"
. "$ROOT/poc/android-capability/tools/lib-checks.sh"
(HERE="$ROOT/poc/android-capability"; check_rust)   # check_rust liest HERE
(cd "$CORE" && cargo build --locked --release --features jni --target-dir "$OUT/target")
(cd "$CORE" && RUSTFLAGS="-C link-arg=-Wl,-soname,libkadri_pairing_poc_testinit.so" \
  cargo build --locked --release --features jni-test-initiator --target-dir "$OUT/target-testinit")
mkdir -p "$OUT/lib"
cp "$OUT/target/release/libkadri_pairing_poc.so" "$OUT/lib/"
cp "$OUT/target-testinit/release/libkadri_pairing_poc.so" "$OUT/lib/libkadri_pairing_poc_testinit.so"
APP="${APP_SRC:-$ROOT/app/src/main/java}"   # mutation.py setzt eine veränderte Kopie
rm -rf "$OUT/classes"
javac -Xlint:all -Werror --release 17 -d "$OUT/classes" \
  "$APP/poc/Core.java" \
  "$APP/de/evp/helfer/ChannelServer.java" \
  "$APP/de/evp/helfer/ControlCommands.java" \
  "$APP/de/evp/helfer/HttpRequestReader.java" \
  "$APP/de/evp/helfer/SnapshotWriter.java" \
  "$ROOT/app/src/androidTestChanneltest/java/poc/TestInitiator.java"
# Phase C3: echte Download-Engine (dl/) und sichere Netzwerkschicht (net/, ohne den Android-Einstieg SafeHttp) der App
# für den Download-Modus des Testhelfers. OkHttp/Okio/Kotlin mit festen SHA-256 (dieselben wie tests/java/net/run.sh
# und gradle/verification-metadata.xml); entpackt in classes/, damit "java -cp classes" genügt.
DEPS="${SAFE_HTTP_DEPS:-$OUT/deps}"; mkdir -p "$DEPS"
fetch() {  # $1 Maven-Pfad, $2 SHA-256
  local f="$DEPS/$(basename "$1")"
  if [ ! -f "$f" ] || [ "$(sha256sum < "$f" | cut -d' ' -f1)" != "$2" ]; then
    curl -fsSL --retry 5 --retry-all-errors -o "$f.part" "https://repo1.maven.org/maven2/$1" && mv "$f.part" "$f"
  fi
  echo "$2  $f" | sha256sum -c --quiet -
}
fetch com/squareup/okhttp3/okhttp-jvm/5.5.1/okhttp-jvm-5.5.1.jar 4039527bd1f2e40b66e20b0e52c689cf56074b938ea02678141495f5160f1047
fetch com/squareup/okio/okio-jvm/3.18.1/okio-jvm-3.18.1.jar b97b640557a650d15411f2be29aef558c4e422a83653cb18ba494311dbed18be
fetch org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.jar 6558a3d233da56a20934b32159f9db5f86ed5816ef098f78a2c223dc6abb79dd
JARS="$DEPS/okhttp-jvm-5.5.1.jar:$DEPS/okio-jvm-3.18.1.jar:$DEPS/kotlin-stdlib-2.2.21.jar"
javac --release 17 -nowarn -d "$OUT/classes" -cp "$OUT/classes:$JARS" \
  $(ls "$APP"/de/evp/helfer/net/*.java | grep -v '/SafeHttp\.java$') "$APP"/de/evp/helfer/dl/*.java \
  "$ROOT/tests/java/dl/de/evp/helfer/net/LocalTestClients.java"
javac -Xlint:all -Werror --release 17 -d "$OUT/classes" -cp "$OUT/classes:$JARS" \
  "$HERE/de/evp/helfer/HarnessDownloads.java" \
  "$HERE/de/evp/helfer/ChannelHarness.java" \
  "$HERE/de/evp/helfer/ChannelChecks.java"
for j in okhttp-jvm-5.5.1 okio-jvm-3.18.1 kotlin-stdlib-2.2.21; do (cd "$OUT/classes" && unzip -q -o "$DEPS/$j.jar" -x 'META-INF/*'); done
(cd "$OUT" && sha256 lib/*)
