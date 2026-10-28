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
  "$ROOT/app/src/androidTestChanneltest/java/poc/TestInitiator.java" \
  "$HERE/de/evp/helfer/ChannelHarness.java" \
  "$HERE/de/evp/helfer/ChannelChecks.java"
(cd "$OUT" && sha256 lib/*)
