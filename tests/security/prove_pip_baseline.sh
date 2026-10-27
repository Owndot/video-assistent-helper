#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
old=$(mktemp -d)
trap 'rm -rf "$old"' EXIT
mkdir -p "$old/src"
for name in 140-player-ton-licht.js 200-player-mediasession.js 250-player-zurueck.js; do
  gh api "repos/Owndot/video-assistent-helper/contents/src/$name?ref=b05fd2de60d27d368eda60ff32be8b361a695f45" --jq .content | base64 --decode > "$old/src/$name"
done
for case in brightness session; do
  if node tests/security/pip_lifecycle.js "$old" --case="$case" > "$old/$case.log" 2>&1; then cat "$old/$case.log"; echo 'Stable baseline unexpectedly passed'; exit 1; fi
  cat "$old/$case.log"
done
grep -q 'background return changed linear device brightness' "$old/brightness.log"
grep -q 'old cleanup cleared successor handlers: media=true, pip=true' "$old/session.log"
echo 'PASS: stable production brightness-return and successor-handler defects independently reproduced'
