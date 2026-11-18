#!/usr/bin/env bash
# Secure channel library for the helper app (de.evp.helfer and the test variant de.evp.helfer.channeltest).
# Same pinned, reproducible Rust -> JNI build as the device-tested PoC (poc/android-capability/tools/build-native.sh:
# rustc 1.97.0, Cargo.lock, NDK r27c, remapped paths), linked for the app's minSdk 26. Output is never committed:
#   OUT/jniLibs/<abi>/libkadri_pairing_poc.so              -> app (gradle -PchannelJniLibs=OUT/jniLibs)
#   OUT/jniLibs-test/<abi>/libkadri_pairing_poc_testinit.so -> channel instrumentation APK only
# Usage: ANDROID_NDK_HOME=... tools/build-channel-native.sh [OUT]   (default app/build/channel-jni)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/app/build/channel-jni}"
mkdir -p "$OUT"
API=26 bash "$ROOT/poc/android-capability/tools/build-native.sh" "$OUT"
