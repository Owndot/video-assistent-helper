#!/usr/bin/env bash
# Baut (build.sh) und führt die JVM-Prüfungen des Steuerkanals aus. Aufruf: tests/java/channel/run.sh [OUT]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/build}"
bash "$HERE/build.sh" "$OUT"
java -Djava.library.path="$OUT/lib" -cp "$OUT/classes" de.evp.helfer.ChannelChecks
