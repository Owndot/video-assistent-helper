#!/usr/bin/env bash
# Reproducible Rust -> JNI build of the shared core for the Android test app.
# Inputs are pinned: rust-toolchain.toml (rustc 1.97.0), Cargo.lock (--locked),
# NDK 27.2.12479018 (r27c), API level 29 linker. Paths are remapped so the
# output does not depend on checkout or cargo-home locations.
#
# Two libraries per ABI:
#   OUT/jniLibs/<abi>/libkadri_pairing_poc.so               helper side (feature jni) – in the test app
#   OUT/jniLibs-test/<abi>/libkadri_pairing_poc_testinit.so Kadri side (feature jni-test-initiator) –
#                                                            only in the instrumentation APK
# Runs on Linux x86_64 and macOS (Apple Silicon or Intel).
# Usage: ANDROID_NDK_HOME=... build-native.sh OUT   (ABIS="arm64-v8a x86_64")
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$(cd "$HERE/../pake-channel/core" && pwd)"
OUT="${1:?usage: build-native.sh OUT}"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
ABIS="${ABIS:-arm64-v8a x86_64}"
API=29
. "$HERE/tools/lib-checks.sh"
check_rust
check_ndk "$NDK"
# The NDK ships host tools as darwin-x86_64 (universal binaries) on every Mac.
case "$(uname -s)" in Linux) HOST=linux-x86_64 ;; Darwin) HOST=darwin-x86_64 ;; *) echo unsupported host >&2; exit 1 ;; esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
CARGO_HOME_DIR="${CARGO_HOME:-$HOME/.cargo}"
COMMON="--remap-path-prefix=$CORE=/kadri-core --remap-path-prefix=$CARGO_HOME_DIR=/cargo-home -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,--build-id=none"
mkdir -p "$OUT/jniLibs" "$OUT/jniLibs-test"
cd "$CORE"
for abi in $ABIS; do
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android ;;
    x86_64) triple=x86_64-linux-android ;;
    *) echo "unsupported ABI $abi" >&2; exit 1 ;;
  esac
  var="CARGO_TARGET_$(echo "$triple" | tr 'a-z-' 'A-Z_')_LINKER"
  export "$var=$TC/${triple}${API}-clang"
  RUSTFLAGS="$COMMON" \
    cargo build --locked --release --target "$triple" --features jni --target-dir "$OUT/target"
  # Own soname: the Android linker must not mistake it for the helper library.
  RUSTFLAGS="$COMMON -C link-arg=-Wl,-soname,libkadri_pairing_poc_testinit.so" \
    cargo build --locked --release --target "$triple" --features jni-test-initiator --target-dir "$OUT/target-testinit"
  mkdir -p "$OUT/jniLibs/$abi" "$OUT/jniLibs-test/$abi"
  cp "$OUT/target/$triple/release/libkadri_pairing_poc.so" "$OUT/jniLibs/$abi/"
  cp "$OUT/target-testinit/$triple/release/libkadri_pairing_poc.so" "$OUT/jniLibs-test/$abi/libkadri_pairing_poc_testinit.so"
  # The installable library must not contain the Kadri-side test surface, and vice versa.
  main_syms="$("$TC/llvm-nm" -D --defined-only "$OUT/jniLibs/$abi/libkadri_pairing_poc.so")"
  test_syms="$("$TC/llvm-nm" -D --defined-only "$OUT/jniLibs-test/$abi/libkadri_pairing_poc_testinit.so")"
  grep -q 'Java_poc_Core_hostInit' <<<"$main_syms" || { echo "helper JNI symbols missing" >&2; exit 1; }
  if grep -q 'Java_poc_TestInitiator_' <<<"$main_syms"; then echo "test initiator in the app library" >&2; exit 1; fi
  grep -q 'Java_poc_TestInitiator_pairStart' <<<"$test_syms" || { echo "test initiator symbols missing" >&2; exit 1; }
  if grep -q 'Java_poc_Core_' <<<"$test_syms"; then echo "helper symbols in the test library" >&2; exit 1; fi
done
echo "rustc: $(rustc -V)  NDK: $(ndk_revision "$NDK")  API: $API  host: $(uname -sm)"
(cd "$OUT" && sha256 jniLibs/*/libkadri_pairing_poc.so jniLibs-test/*/libkadri_pairing_poc_testinit.so)
(cd "$OUT" && for f in jniLibs/*/*.so jniLibs-test/*/*.so; do echo "$(wc -c < "$f" | tr -d ' ') $f"; done)
