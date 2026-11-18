#!/usr/bin/env bash
# Compiles and runs the JVM checks for the plain-Java parts of the test app
# (SnapshotWriter: F1/F4, HttpRequestReader: F3). Needs only a JDK 17+.
# Usage: run-jvm-checks.sh [SRC_MAIN_DIR]   (default: the real sources;
# tools/java_mutation_check.py passes a directory with a mutated copy)
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
PKG="$HERE/app-project/testapp/src"
MAIN="${1:-$PKG/main/java/de/evp/pakepoc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -Xlint:all -Werror --release 17 -d "$OUT" \
  "$MAIN/SnapshotWriter.java" "$MAIN/HttpRequestReader.java" "$PKG/jvmTest/java/de/evp/pakepoc/JvmChecks.java"
java -cp "$OUT" de.evp.pakepoc.JvmChecks
