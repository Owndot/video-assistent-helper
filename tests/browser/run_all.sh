#!/usr/bin/env bash
# Startet alle Browser-Tests nacheinander und zeigt je Test das Ergebnis.
# Voraussetzung: Python 3 + Playwright mit Chromium (pip install playwright && playwright install chromium).
# Für test_e2e/test_pair zusätzlich: Java + ffmpeg (../java/make_streams.sh und ../java/build.sh laufen bei Bedarf von selbst).
cd "$(dirname "$0")"
if ls ${TESTS:-test_*.py} | grep -qE "test_(e2e|pair)"; then
  [ -d ../java/srv ] || ../java/make_streams.sh
  [ -d ../java/classes ] || ../java/build.sh
fi
mkdir -p out
for t in ${TESTS:-test_*.py}; do
  timeout 300 python3 -u "$t" > "out/${t%.py}.log" 2>&1
  r=$(grep -E 'ERGEBNIS|FAIL|bestanden|symmetrisch|Bilder/s|pausiert' "out/${t%.py}.log" | tr '\n' ' ' | cut -c1-160)
  printf '%-22s %s\n' "$t" "${r:-(keine Ausgabe)}"
done
