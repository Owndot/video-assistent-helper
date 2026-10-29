#!/usr/bin/env bash
# Entscheidet, ob build.yml die Debug-APK als (öffentliches) Artefakt hochladen darf. Ausgabe: allow=yes|no.
# Gesperrt, sobald EINES zutrifft – unabhängig vom Ereignis (pull_request, push, workflow_dispatch, schedule …):
#   - Markierungsdatei .github/DEBUG-APK-UPLOAD-BLOCKED im ausgecheckten Stand (gilt auch nach einem Merge),
#   - GITHUB_HEAD_REF (nur bei pull_request gesetzt) oder GITHUB_REF_NAME ist der Kanal-Integrationszweig.
# Fehlt diese Ausgabe (Schritt nicht gelaufen), lädt build.yml nichts hoch.
set -euo pipefail
ROOT="${1:-.}"
BLOCKED_BRANCH='feature/helper-channel-control'
reasons=()
[ -e "$ROOT/.github/DEBUG-APK-UPLOAD-BLOCKED" ] && reasons+=("marker .github/DEBUG-APK-UPLOAD-BLOCKED")
[ "${GITHUB_HEAD_REF:-}" = "$BLOCKED_BRANCH" ] && reasons+=("head_ref=$BLOCKED_BRANCH")
[ "${GITHUB_REF_NAME:-}" = "$BLOCKED_BRANCH" ] && reasons+=("ref_name=$BLOCKED_BRANCH")
if [ "${#reasons[@]}" -gt 0 ]; then allow=no; else allow=yes; fi
echo "debug APK upload: allow=$allow (event=${GITHUB_EVENT_NAME:-?} head_ref=${GITHUB_HEAD_REF:-} ref_name=${GITHUB_REF_NAME:-})${reasons[*]:+ – blocked by: ${reasons[*]}}"
[ -n "${GITHUB_OUTPUT:-}" ] && echo "allow=$allow" >> "$GITHUB_OUTPUT"
exit 0
