#!/usr/bin/env bash
# Reproducible Rust -> JNI build of the shared core for the Android test app.
# Inputs are pinned: rust-toolchain.toml (rustc 1.97.0), Cargo.lock (--locked),
# NDK 27.2.12479018, API level 29 linker. Paths are remapped so the output
# does not depend on checkout or cargo-home locations.
# Usage: ANDROID_NDK_HOME=... build-native.sh OUT   (ABIS="arm64-v8a x86_64")
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$(cd "$HERE/../pake-channel/core" && pwd)"
OUT="${1:?usage: build-native.sh OUT}"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
ABIS="${ABIS:-arm64-v8a x86_64}"
API=29
EXPECTED_NDK="27.2.12479018"
REV="$(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties")"
[ "$REV" = "$EXPECTED_NDK" ] || { echo "NDK $REV found, $EXPECTED_NDK required" >&2; exit 1; }
case "$(uname -s)" in Linux) HOST=linux-x86_64 ;; Darwin) HOST=darwin-x86_64 ;; *) echo unsupported host >&2; exit 1 ;; esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
CARGO_HOME_DIR="${CARGO_HOME:-$HOME/.cargo}"
mkdir -p "$OUT/jniLibs"
cd "$CORE"
for abi in $ABIS; do
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android ;;
    x86_64) triple=x86_64-linux-android ;;
    *) echo "unsupported ABI $abi" >&2; exit 1 ;;
  esac
  var="CARGO_TARGET_$(echo "$triple" | tr 'a-z-' 'A-Z_')_LINKER"
  export "$var=$TC/${triple}${API}-clang"
  RUSTFLAGS="--remap-path-prefix=$CORE=/kadri-core --remap-path-prefix=$CARGO_HOME_DIR=/cargo-home -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,--build-id=none" \
    cargo build --locked --release --target "$triple" --features jni-test-initiator --target-dir "$OUT/target"
  mkdir -p "$OUT/jniLibs/$abi"
  cp "$OUT/target/$triple/release/libkadri_pairing_poc.so" "$OUT/jniLibs/$abi/"
done
echo "rustc: $(rustc -V)  NDK: $REV  API: $API"
(cd "$OUT" && sha256sum jniLibs/*/libkadri_pairing_poc.so && ls -l jniLibs/*/libkadri_pairing_poc.so | awk '{print $5, $9}')
