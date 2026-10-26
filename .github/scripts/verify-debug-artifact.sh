#!/usr/bin/env bash
# Upload gate for the final disposable CI debug APK, never a production release.
set -euo pipefail
apk=app/build/outputs/apk/debug/app-debug.apk
python3 - <<'PY'
from pathlib import Path
import re
text=Path('validation-results/debug-apk.sha256').read_text()
assert re.fullmatch(r'[0-9a-f]{64}  app/build/outputs/apk/debug/app-debug\.apk\n',text), 'Unexpected APK checksum record'
PY
sha256sum --check --strict validation-results/debug-apk.sha256
test "$(cat validation-results/debug-source-commit.txt)" = "$(git rev-parse HEAD)"
signer=$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -1)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
"$signer" verify --verbose --print-certs "$apk" > "$work/signature.txt"
cmp validation-results/debug-signature.txt "$work/signature.txt"
echo 'PASS: uploaded debug APK matches recorded SHA-256, SDK signature verification and source commit'
