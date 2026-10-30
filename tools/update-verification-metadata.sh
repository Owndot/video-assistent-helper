#!/usr/bin/env bash
# Erzeugt gradle/verification-metadata.xml neu: SHA-256 jedes Artefakts, das die Gradle-Aufgaben von CI und
# Release-Weg auflösen (Plugins, Build-Werkzeuge, App- und Testabhängigkeiten). Danach prüft Gradle bei jedem Build
# jede heruntergeladene Datei gegen diese Liste und bricht bei Abweichung oder unbekannten Artefakten ab.
# aapt2 lädt AGP plattformabhängig; die Varianten für macOS und Windows werden ergänzt (lokaler Release-Weg auf dem Mac).
# Nach jeder Abhängigkeitsänderung ausführen und das Ergebnis prüfen (Diff), nie ungesehen übernehmen.
# Aufruf: tools/update-verification-metadata.sh JNI_OUT   (JNI_OUT aus tools/build-channel-native.sh)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(cd "${1:?usage: update-verification-metadata.sh JNI_OUT}" && pwd)"
META="$ROOT/gradle/verification-metadata.xml"
rm -f "$META"
G=("$ROOT/gradlew" --no-daemon -q -p "$ROOT" --write-verification-metadata sha256)
"${G[@]}" -PchannelTests -Pandroid.useAndroidX=true -PchannelJniLibs="$OUT/jniLibs" -PchannelJniLibsTest="$OUT/jniLibs-test" \
  :app:assembleChanneltest :app:assembleChanneltestAndroidTest :app:traceTestKeepRules
"${G[@]}" -PreleaseVersionCode=200 -PreleaseVersionName=2.0.0 \
  :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease :app:verifyReleaseInputs help
"${G[@]}" -PmigrationFixtureVersion=123 :app:assembleDebug
python3 - "$META" <<'PY'
import hashlib, re, sys, urllib.request
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
m = re.search(r'<component group="com\.android\.tools\.build" name="aapt2" version="([^"]+)">(.*?)</component>', s, re.S)
if not m:
    sys.exit('aapt2 component missing')
version, body = m.group(1), m.group(2)
extra = ''
for os_name in ('osx', 'windows'):
    name = f'aapt2-{version}-{os_name}.jar'
    if name in body:
        continue
    url = f'https://dl.google.com/android/maven2/com/android/tools/build/aapt2/{version}/{name}'
    data = urllib.request.urlopen(url, timeout=120).read()
    digest = hashlib.sha256(data).hexdigest()
    extra += f'         <artifact name="{name}">\n            <sha256 value="{digest}" origin="tools/update-verification-metadata.sh from dl.google.com"/>\n         </artifact>\n'
    print(f'{name} {digest} ({len(data)} bytes)')
s = s[:m.start(2)] + body.rstrip() + '\n' + extra + '      ' + s[m.end(2):]
open(p, 'w', encoding='utf-8').write(s)
PY
echo "components: $(grep -c '<component ' "$META")  artifacts: $(grep -c '<artifact ' "$META")"
