#!/usr/bin/env bash
# Startet alle Browser-Tests nacheinander und zeigt je Test das Ergebnis.
# Voraussetzung: Python 3 + Playwright mit Chromium (pip install playwright && playwright install chromium).
# Für test_e2e/test_pair zusätzlich: Java + ffmpeg (../java/make_streams.sh und ../java/build.sh laufen bei Bedarf von selbst).
cd "$(dirname "$0")"
if ls test_e2e.py >/dev/null 2>&1; then
  [ -d ../java/srv ] || ../java/make_streams.sh
  [ -d ../java/classes ] || ../java/build.sh
fi
mkdir -p out
for t in test_*.py; do
  r=$(timeout 300 python3 "$t" 2>&1 | grep -E 'ERGEBNIS|FAIL|bestanden|symmetrisch|Bilder/s|pausiert' | tr '\n' ' ' | cut -c1-160)
  printf '%-22s %s\n' "$t" "${r:-(keine Ausgabe)}"
done
