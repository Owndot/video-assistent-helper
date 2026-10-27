#!/usr/bin/env bash
# Builds the PoC core (native JNI library + one WASM module), assembles the
# Chromium and Firefox test extensions around that identical WASM file and
# compiles the JVM stand-in into OUT (default: a temp dir). Produces no APK.
# --locked: the committed Cargo.lock is authoritative; nothing is upgraded.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$(mktemp -d)}"
cd "$HERE/core"
cargo build --locked --release --features jni
RUSTFLAGS='--cfg getrandom_backend="wasm_js"' cargo build --locked --release --target wasm32-unknown-unknown --features wasm
wasm-bindgen --version | grep -qx 'wasm-bindgen 0.2.104'
rm -rf "$HERE/ext/pkg"
wasm-bindgen --target web --out-dir "$HERE/ext/pkg" target/wasm32-unknown-unknown/release/kadri_pairing_poc.wasm
for b in chrome firefox; do
  rm -rf "$OUT/ext-$b"; mkdir -p "$OUT/ext-$b/pkg"
  cp "$HERE"/ext/shared/* "$OUT/ext-$b/"
  cp "$HERE"/ext/$b/* "$OUT/ext-$b/"   # browser adapter files override shared ones
  cp "$HERE"/ext/pkg/kadri_pairing_poc.js "$HERE"/ext/pkg/kadri_pairing_poc_bg.wasm "$OUT/ext-$b/pkg/"
done
rm -f "$OUT/ext-firefox.xpi"
(cd "$OUT/ext-firefox" && zip -qr -X "$OUT/ext-firefox.xpi" .)
mkdir -p "$OUT/jvm"
javac -d "$OUT/jvm" "$HERE"/jvm/src/poc/*.java
sha256sum "$OUT"/ext-*/pkg/kadri_pairing_poc_bg.wasm
echo "OUT=$OUT"
echo "LIB=$HERE/core/target/release/libkadri_pairing_poc.so"
