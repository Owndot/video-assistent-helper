#!/usr/bin/env bash
# Builds the PoC core (native JNI library + WASM for the test extension) and
# the JVM stand-in into OUT (default: a temp dir). Produces no APK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$(mktemp -d)}"
cd "$HERE/core"
cargo build --release --features jni
RUSTFLAGS='--cfg getrandom_backend="wasm_js"' cargo build --release --target wasm32-unknown-unknown --features wasm
wasm-bindgen --target web --out-dir "$HERE/ext/pkg" target/wasm32-unknown-unknown/release/kadri_pairing_poc.wasm
mkdir -p "$OUT/jvm"
javac -d "$OUT/jvm" "$HERE"/jvm/src/poc/*.java
echo "JVM_OUT=$OUT/jvm"
echo "LIB=$HERE/core/target/release/libkadri_pairing_poc.so"
