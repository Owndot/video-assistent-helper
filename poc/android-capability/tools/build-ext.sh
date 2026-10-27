#!/usr/bin/env bash
# Assembles the Vivaldi Android test extension around the core's WASM build.
# Output: OUT/ext-vivaldi (folder for "Load unpacked") and OUT/ext-vivaldi.zip.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$HERE/../pake-channel/core"
OUT="${1:?usage: build-ext.sh OUT}"
cd "$CORE"
RUSTFLAGS='--cfg getrandom_backend="wasm_js"' cargo build --locked --release --target wasm32-unknown-unknown --features wasm
wasm-bindgen --version | grep -qx 'wasm-bindgen 0.2.104'
rm -rf "$OUT/ext-vivaldi" "$OUT/ext-vivaldi.zip"
mkdir -p "$OUT/ext-vivaldi/pkg"
wasm-bindgen --target web --out-dir "$OUT/wasm-bindgen" target/wasm32-unknown-unknown/release/kadri_pairing_poc.wasm
cp "$OUT"/wasm-bindgen/kadri_pairing_poc.js "$OUT"/wasm-bindgen/kadri_pairing_poc_bg.wasm "$OUT/ext-vivaldi/pkg/"
cp "$HERE"/ext-vivaldi/* "$OUT/ext-vivaldi/"
(cd "$OUT/ext-vivaldi" && zip -qr -X "$OUT/ext-vivaldi.zip" .)
sha256sum "$OUT/ext-vivaldi/pkg/kadri_pairing_poc_bg.wasm" "$OUT/ext-vivaldi.zip" | sed "s#$OUT/##"
