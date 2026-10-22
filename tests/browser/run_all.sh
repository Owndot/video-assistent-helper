#!/usr/bin/env bash
# Startet alle Browser-Tests nacheinander und zeigt je Test das Ergebnis.
# Streng: Eine fehlgeschlagene Prüfung (FAIL/FEHLER, „x von y“ mit x < y), ein Absturz oder ein Test ohne
# Ergebniszeile zählt als Fehler – dann endet das Skript mit Fehlercode (CI wird rot).
# Messungen (mess_*.py) haben kein Bestanden/Durchfallen: Sie laufen nur, wenn man sie nennt (TESTS="mess_perf.py").
# Voraussetzung: Python 3 + Playwright mit Chromium (pip install playwright && playwright install chromium).
# Für test_e2e/test_pair zusätzlich: Java + ffmpeg (../java/make_streams.sh und ../java/build.sh laufen bei Bedarf von selbst).
cd "$(dirname "$0")"
if ls ${TESTS:-test_*.py} | grep -qE "test_(e2e|pair)"; then
  [ -d ../java/srv ] || ../java/make_streams.sh
  [ -d ../java/classes ] || ../java/build.sh
fi
mkdir -p out
bad=(); n=0
for t in ${TESTS:-test_*.py}; do
  log="out/${t%.py}.log"
  t0=$SECONDS
  timeout -k 15 300 python3 -u "$t" > "$log" 2>&1; code=$?
  dur="$((SECONDS - t0))s"
  if [[ $t == mess_* ]]; then
    r=$(grep -vE '^\s*$' "$log" | tail -3 | tr '\n' ' ' | cut -c1-160)
    printf '%-22s Messung: %s\n' "$t" "${r:-(keine Ausgabe)}"
    continue
  fi
  n=$((n+1))
  res=$(grep -E '^ERGEBNIS' "$log" | tail -1)
  nfail=$(grep -cE '^\s*FAIL' "$log")
  why=''
  if [ "$code" = 124 ] || [ "$code" = 137 ]; then why='Zeit überschritten (300 s)'
  elif [ "$code" != 0 ]; then why='Absturz: '$(grep -E '^[A-Za-z_.]+(Error|Exception)' "$log" | tail -1 | cut -c1-90)
  elif [ -z "$res" ]; then why='keine Ergebniszeile'
  elif [ "$nfail" -gt 0 ]; then why="$nfail Prüfung(en) fehlgeschlagen"
  elif echo "$res" | grep -q 'FEHLER'; then why='FEHLER gemeldet'
  elif [[ $res =~ ([0-9]+)\ von\ ([0-9]+) ]] && [ "${BASH_REMATCH[1]}" != "${BASH_REMATCH[2]}" ]; then why="nur ${BASH_REMATCH[1]} von ${BASH_REMATCH[2]}"
  fi
  if [ -n "$why" ]; then
    bad+=("$t")
    printf '%-22s %5s FEHLER – %s\n' "$t" "$dur" "$why"
    grep -E '^\s*FAIL' "$log" | head -5 | cut -c1-200 | sed 's/^/      /'
  else
    printf '%-22s %5s ok   %s\n' "$t" "$dur" "${res#ERGEBNIS: }"
  fi
done
[ $n = 0 ] && exit 0   # nur Messungen
echo
if [ ${#bad[@]} -gt 0 ]; then echo "GESAMT: ${#bad[@]} Test(s) mit Fehlern: ${bad[*]}"; exit 1; fi
echo "GESAMT: alles bestanden"
