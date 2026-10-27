#!/usr/bin/env bash
# Branch-only counterproof of the exact pre-fix code. Requires read-only gh API.
set -euo pipefail
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
base=044784068b4b74621f0b45dbbf203780d17ebefe
for name in Fetcher Downloads; do
  gh api "repos/Owndot/video-assistent-helper/contents/app/src/main/java/de/evp/helfer/$name.java?ref=$base" --jq .content | base64 --decode > "$work/$name.java"
done
javac -cp tests/java/lifecycle-classes:tests/java/classes -d "$work/classes" "$work/Fetcher.java" "$work/Downloads.java"
cp="$work/classes:tests/java/lifecycle-classes:tests/java/classes"
reject() {
  local expected=$1
  shift
  if java -Xmx32m -cp "$cp" "$@" > "$work/proof.log" 2>&1; then
    cat "$work/proof.log"; echo 'Baseline unexpectedly passed'; exit 1
  fi
  cat "$work/proof.log"
  grep -Fq "$expected" "$work/proof.log"
}
reject 'Failed save retained pending row: write' de.evp.helfer.TestDownloadPublication
reject 'Cancelled publication reported success: fertig' de.evp.helfer.TestDownloadPublication --cancel
reject 'existing legacy target overwritten' de.evp.helfer.TestDownloadPublication --legacy
reject 'Unvalidated EOF reported success' de.evp.helfer.TestNativeLifecycle
echo 'PASS: actual baseline pending leak, cancelled success, overwrite and failed EOF independently reproduced'
