#!/usr/bin/env bash
# Builds the shared core's Kadri-side WASM for Kadri's classic (non-module)
# MV3 service worker: same cargo build as build-ext.sh, but wasm-bindgen
# --target no-modules, which defines a global `wasm_bindgen` loadable with
# importScripts(). Kadri vendors the two output files and records their
# SHA-256 and the source commit; its CI rebuilds them with this script and
# compares. Runs on Linux and macOS.
# Usage: build-wasm-kadri.sh OUT  -> OUT/kadri-wasm/{kadri_pairing_poc.js,kadri_pairing_poc_bg.wasm}
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$HERE/../pake-channel/core"
OUT="$(mkdir -p "${1:?usage: build-wasm-kadri.sh OUT}" && cd "$1" && pwd)"
. "$HERE/tools/lib-checks.sh"
check_rust
wasm-bindgen --version | grep -qx 'wasm-bindgen 0.2.104' || { echo "wasm-bindgen 0.2.104 required" >&2; exit 1; }
cd "$CORE"
RUSTFLAGS='--cfg getrandom_backend="wasm_js"' cargo build --locked --release --target wasm32-unknown-unknown --features wasm --target-dir "$OUT/target-wasm"
rm -rf "$OUT/kadri-wasm"
wasm-bindgen --target no-modules --out-dir "$OUT/kadri-wasm" "$OUT/target-wasm/wasm32-unknown-unknown/release/kadri_pairing_poc.wasm"
rm -f "$OUT"/kadri-wasm/*.d.ts
(cd "$OUT/kadri-wasm" && sha256 kadri_pairing_poc.js kadri_pairing_poc_bg.wasm)
