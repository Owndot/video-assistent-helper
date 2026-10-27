#!/usr/bin/env bash
# Runs every PoC check and writes the result file. Needs OUT (build dir),
# ADVISORY_DB, and for Firefox FIREFOX_BIN, GECKODRIVER, SELENIUM_DIR.
# MUTATION_LOG may point to an existing mutation_check.py log.
set -uo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$1"; RES="$2"
LIB="$HERE/core/target/release/libkadri_pairing_poc.so"
cd "$HERE"
{
  echo "# PoC revision 2 – Laufergebnisse $(date -u +%Y-%m-%dT%H:%MZ)"
  echo
  echo "Wegwerfmaterial, Ports 47899/47900/47902, kein Android. HEAD-Basis: $(git rev-parse HEAD)."
  echo
  echo '## Umgebung'
  echo '```'
  rustc -V; cargo -V; wasm-bindgen --version; node -v
  java -version 2>&1 | grep -v JAVA_TOOL | head -1
  echo "Playwright $(node -e "console.log(require('playwright/package.json').version)")"
  echo "Firefox: $("$FIREFOX_BIN" --version 2>/dev/null)"; "$GECKODRIVER" --version | head -1
  uname -sm
  echo '```'
  echo
  echo '## Build (--locked)'
  echo '```'
  tools/build.sh "$OUT" 2>&1 | grep -E 'sha256|kadri_pairing_poc_bg.wasm|OUT=|error'
  stat -c '%n %s B' "$OUT"/ext-chrome/pkg/kadri_pairing_poc_bg.wasm "$LIB" | sed "s#$OUT/##; s#$HERE/##"
  echo "wasm gzip -9: $(gzip -9c "$OUT"/ext-chrome/pkg/kadri_pairing_poc_bg.wasm | wc -c) B"
  echo '```'
  echo
  echo '## RFC 9382 unabhängige Gegenrechnung'
  echo '```'; python3 -I vectors/independent_check.py vectors/rfc9382-appendix-b.json | tail -1; echo '```'
  echo
  echo '## Argon2-KAT unabhängig neu erzeugt und verglichen'
  echo '```'
  python3 -I vectors/argon2_kat.py "$OUT/kat.json" && python3 -I -c "import json,sys; a=json.load(open(sys.argv[1]));b=json.load(open(sys.argv[2])); print('KAT identical' if a['vectors']==b['vectors'] else 'KAT MISMATCH')" "$OUT/kat.json" vectors/password-scalar-kat.json
  echo '```'
  echo
  echo '## cargo test --locked'
  echo '```'; (cd core && cargo test --locked 2>&1 | grep -E '^test |test result: ok. [1-9]|FAILED|panicked'); echo '```'
  echo
  echo '## Krypto-Benchmark (nativ, ohne Sockets)'
  echo '```'; (cd core && cargo run --locked --release -q --example bench 2>&1); echo '```'
  echo
  echo '## RustSec-Abgleich'
  echo '```'; echo "advisory-db $(git -C "$ADVISORY_DB" log -1 --format='%h %cs')"; python3 -I tools/advisory_check.py core/Cargo.lock "$ADVISORY_DB"; echo '```'
  echo
  echo '## Mutationsprobe'
  echo '```'; if [ -n "${MUTATION_LOG:-}" ]; then cat "$MUTATION_LOG"; else python3 -I tools/mutation_check.py; fi; echo '```'
  for browser in chromium firefox; do
    for mode in off on; do
      flag=""; [ "$mode" = on ] && flag=--nodelay
      echo
      echo "## E2E $browser, Helper TCP_NODELAY $mode"
      echo '```'
      timeout 900 node browser/run.mjs "$browser" "$OUT" "$LIB" $flag > "$OUT/e2e-$browser-$mode.log" 2>&1
      grep -E '^(PASS|FAIL|RESULT|browser)' "$OUT/e2e-$browser-$mode.log"
      echo '```'
    done
  done
} > "$RES"
grep -E '^RESULT|RESULT (PASS|FAIL)|KAT ' "$RES"
