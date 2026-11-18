#!/usr/bin/env bash
# TEST HARNESS ONLY. Builds the desktop host for the unchanged PocServer
# (see DesktopHelperHost.java): the shared Rust core for the build host with
# the helper JNI surface (feature jni), plus the server classes of the
# Android test app compiled for a desktop JVM. Used by Kadri's channel
# integration tests as the helper counterpart. Nothing here is shipped.
# Usage: build-desktop-helper.sh OUT
#   -> OUT/lib/libkadri_pairing_poc.(so|dylib), OUT/classes
#   run: java -Djava.library.path=OUT/lib -cp OUT/classes de.evp.pakepoc.DesktopHelperHost STATE_DIR
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$(cd "$HERE/../pake-channel/core" && pwd)"
OUT="$(mkdir -p "${1:?usage: build-desktop-helper.sh OUT}" && cd "$1" && pwd)"
. "$HERE/tools/lib-checks.sh"
check_rust
(cd "$CORE" && cargo build --locked --release --features jni --target-dir "$OUT/target")
mkdir -p "$OUT/lib"
for f in libkadri_pairing_poc.so libkadri_pairing_poc.dylib; do
  [ -f "$OUT/target/release/$f" ] && cp "$OUT/target/release/$f" "$OUT/lib/"
done
SRC="$HERE/app-project/testapp/src"
rm -rf "$OUT/classes"
javac -Xlint:all -Werror --release 17 -d "$OUT/classes" \
  "$SRC/main/java/poc/Core.java" \
  "$SRC/main/java/de/evp/pakepoc/PocServer.java" \
  "$SRC/main/java/de/evp/pakepoc/HttpRequestReader.java" \
  "$SRC/main/java/de/evp/pakepoc/SnapshotWriter.java" \
  "$SRC/jvmTest/java/de/evp/pakepoc/DesktopHelperHost.java"
(cd "$OUT" && sha256 lib/*)
