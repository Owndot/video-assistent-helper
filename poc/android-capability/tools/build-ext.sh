#!/usr/bin/env bash
# Assembles the Vivaldi Android test extension around the core's WASM build.
# Output: OUT/ext-vivaldi (folder for "Load unpacked") and OUT/ext-vivaldi.zip.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
. "$HERE/tools/lib-checks.sh"
check_rust
CORE="$HERE/../pake-channel/core"
OUT="${1:?usage: build-ext.sh OUT}"
cd "$CORE"
# Paths of the build machine are remapped (as in build-native.sh); otherwise panic location strings embed the
# checkout and cargo-home paths and the .wasm differs from machine to machine.
CORE_ABS="$(pwd)"
RUSTFLAGS="--cfg getrandom_backend=\"wasm_js\" --remap-path-prefix=$CORE_ABS=/kadri-core --remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}=/cargo-home" cargo build --locked --release --target wasm32-unknown-unknown --features wasm
wasm-bindgen --version | grep -qx 'wasm-bindgen 0.2.104'
rm -rf "$OUT/ext-vivaldi" "$OUT/ext-vivaldi.zip"
mkdir -p "$OUT/ext-vivaldi/pkg"
wasm-bindgen --target web --out-dir "$OUT/wasm-bindgen" target/wasm32-unknown-unknown/release/kadri_pairing_poc.wasm
cp "$OUT"/wasm-bindgen/kadri_pairing_poc.js "$OUT"/wasm-bindgen/kadri_pairing_poc_bg.wasm "$OUT/ext-vivaldi/pkg/"
cp "$HERE"/ext-vivaldi/* "$OUT/ext-vivaldi/"
(cd "$OUT/ext-vivaldi" && zip -qr -X "$OUT/ext-vivaldi.zip" .)
(cd "$OUT" && sha256 ext-vivaldi/pkg/kadri_pairing_poc_bg.wasm)
