#!/usr/bin/env bash
# Focused JVM auth evidence. No Android/emulator, APK, signing key or media matrix.
set -euo pipefail
cd "$(dirname "$0")"
bash build.sh
python3 - <<'PY'
from pathlib import Path
import re
root=Path('../..')
service=(root/'app/src/main/java/de/evp/helfer/HelferService.java').read_text()
pattern=r'server = new MiniServer\(PORT, \(k\) -> \{(.*?)\}, new MiniServer.Handler\(\)'
parts=re.findall(pattern,service,re.S)
assert len(parts)==1,'Production service predicate changed shape'
body=parts[0].replace('this','context')
assert 'Keys.key(context)' in body and 'Keys.markPaired(context)' in body
out=Path('classes/beta-auth-generated/de/evp/helfer');out.mkdir(parents=True,exist_ok=True)
(out/'ProductionAuth.java').write_text('package de.evp.helfer;\nimport android.content.Context;\nfinal class ProductionAuth { static boolean accept(Context context,String k) {'+body+'} }\n')
# Source checks only for Android UI/service wiring; no device execution is claimed.
activity=(root/'app/src/main/java/de/evp/helfer/MainActivity.java').read_text()
pair=activity.split('private void pairWhenReady(int attempt) {',1)[1].split('\n    @Override',1)[0]
assert pair.index('if (!HelferService.running)') < pair.index('return;') < pair.index('Intent i =')
assert 'HelferService.startupError != null' in pair and 'attempt >= 20' in pair
startup=service.split('if (!startServer()) {',1)[1].split('running = true;',1)[0]
assert 'startupError =' in startup and 'stopSelf();' in startup and 'return;' in startup
print('PASS: production service predicate extracted; source-only readiness/pair-intent guards checked')
PY
javac -cp classes -d classes beta_auth/android/content/Context.java beta_auth/android/content/SharedPreferences.java \
  ../../app/src/main/java/de/evp/helfer/Keys.java \
  classes/beta-auth-generated/de/evp/helfer/ProductionAuth.java beta_auth/de/evp/helfer/TestBetaAuth.java
java -cp classes TestPortConflict
java -cp classes TestMiniServerLimits
java -cp classes de.evp.helfer.TestBetaAuth
