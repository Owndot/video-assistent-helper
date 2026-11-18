#!/usr/bin/env bash
# Prüft .github/scripts/debug-apk-upload-gate.sh gegen verschiedene Ereignisse, Zweignamen und die Markierungsdatei.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
GATE="$HERE/.github/scripts/debug-apk-upload-gate.sh"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mkdir -p "$W/marked/.github" "$W/plain/.github"
touch "$W/marked/.github/DEBUG-APK-UPLOAD-BLOCKED"
fail=0; n=0
# tree event head_ref ref_name expected
while read -r tree event head ref want; do
  [ -z "$tree" ] && continue
  [ "$head" = "-" ] && head=""
  out="$(env -i PATH="$PATH" GITHUB_EVENT_NAME="$event" GITHUB_HEAD_REF="$head" GITHUB_REF_NAME="$ref" GITHUB_OUTPUT="$W/out" bash "$GATE" "$W/$tree")"
  got="$(sed -n 's/^allow=//p' "$W/out" | tail -1)"; rm -f "$W/out"
  n=$((n+1))
  if [ "$got" = "$want" ]; then echo "PASS $tree $event head_ref=${head:-∅} ref_name=$ref → $got"
  else echo "FAIL $tree $event head_ref=${head:-∅} ref_name=$ref → $got (expected $want): $out"; fail=1; fi
done <<'CASES'
marked pull_request feature/helper-channel-control 22/merge no
marked workflow_dispatch - feature/helper-channel-control no
marked push - feature/helper-channel-control no
marked push - main no
marked workflow_dispatch - main no
marked schedule - main no
marked pull_request codex/other 23/merge no
plain pull_request feature/helper-channel-control 22/merge no
plain workflow_dispatch - feature/helper-channel-control no
plain push - feature/helper-channel-control no
plain schedule - feature/helper-channel-control no
plain pull_request codex/other 23/merge yes
plain push - main yes
plain workflow_dispatch - main yes
CASES
# Ohne Ausgabe (Gate nicht gelaufen) darf build.yml nichts hochladen: die Bedingung verlangt allow == 'yes'.
grep -q "steps.debug_upload_gate.outputs.allow == 'yes'" "$HERE/.github/workflows/build.yml" || { echo "FAIL build.yml upload step is not bound to the gate"; fail=1; }
[ -e "$HERE/.github/DEBUG-APK-UPLOAD-BLOCKED" ] || { echo "FAIL marker file missing in this tree"; fail=1; }
[ "$fail" = 0 ] && echo "RESULT debug-upload-gate PASS $n/$n cases" || { echo "RESULT debug-upload-gate FAIL"; exit 1; }
