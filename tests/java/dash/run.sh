#!/usr/bin/env bash
# S3a: JVM-Tests des DASH-Kerns der App (de.evp.helfer.dash) – gehärtetes XML, Adressen, Vorlagen, Vererbung,
# Zeitleisten, Grenzen, S3-Profil, vollständiger Plan, Fingerabdruck v1 und Querprüfung mit Kadris Erkennungsindex.
# Keine Netz- oder Android-Abhängigkeit: nur das JDK (kompiliert zusätzlich nur HlsUri/HlsException, unverändert).
#   tests/java/dash/run.sh                 Tests (Heap 64 MB)
#   tests/java/dash/run.sh --dump-keys OUT Adressen für tests/java/dash/kadri_index.py
#   tests/java/dash/run.sh --write-fp      Golden-Text/Testvektoren des Fingerabdrucks neu schreiben (nur bewusst)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
SRC="${APP_SRC:-$ROOT/app/src/main/java}/de/evp/helfer"   # mutation.py setzt eine veränderte Kopie
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
javac -Xlint:all,-serial -Werror -d "$WORK/c" "$SRC"/hls/HlsUri.java "$SRC"/hls/HlsException.java "$SRC"/dash/*.java "$HERE"/de/evp/helfer/dash/*.java
cd "$ROOT"
java -Xmx64m -Ddash.src="$SRC/dash" -cp "$WORK/c" de.evp.helfer.dash.TestDash "$HERE/fixtures" "$@"
