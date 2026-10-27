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
  if timeout 15 java -Djava.security.manager=allow -Xmx32m -cp "$cp" "$@" > "$work/proof.log" 2>&1; then
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

# Reviewed PR tree counterexamples use the corrected provider model and real service callbacks.
review=6a86fdd7e6cdd184fc5c72a830572febed6cd488
mkdir -p "$work/review"
for name in Downloads HelferService; do
  gh api "repos/Owndot/video-assistent-helper/contents/app/src/main/java/de/evp/helfer/$name.java?ref=$review" --jq .content | base64 --decode > "$work/review/$name.java"
done
javac -cp tests/java/lifecycle-classes:tests/java/classes -d "$work/review-classes" "$work/review/Downloads.java" "$work/review/HelferService.java"
cp="$work/review-classes:tests/java/lifecycle-classes:tests/java/classes"
reject 'owned pending row survived recovery' de.evp.helfer.TestLifecycleReview pending
reject 'main-thread cancel blocked behind provider' de.evp.helfer.TestLifecycleReview blocking
reject 'concurrent first legacy save failed' de.evp.helfer.TestLifecycleReview legacy
echo 'PASS: all three reviewed P2 findings reproduced against exact 6a86fdd production classes'
