#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for n in 1 2 3 4; do
  java -Xmx32m -cp classes de.evp.helfer.TestJobIdentity --reserve "$work/ids" > "$work/id$n" &
done
wait
java -Xmx32m -cp classes de.evp.helfer.TestJobIdentity --reserve "$work/ids" > "$work/after"
python3 - "$work" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1]); ids={int((root/f'id{i}').read_text()) for i in range(1,5)}
assert ids=={-2,-3,-4,-5},ids
assert int((root/'after').read_text())==-6
print('PASS: four separate JVM reservations are unique; immediate restart consumes the next durable ID')
PY
