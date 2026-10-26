#!/usr/bin/env bash
# Disposable test-only.jks identities. Never uploads any APK or reads production secrets.
set -euo pipefail
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export PATH="$ANDROID_HOME/build-tools/34.0.0:$PATH"
export FIXTURE_PASS=disposable-test-only-password
for id in test-only other; do
  keytool -genkeypair -keystore "$work/$id.jks" -storetype JKS -storepass:env FIXTURE_PASS -keypass:env FIXTURE_PASS -alias fixture -keyalg RSA -keysize 2048 -validity 2 -dname "CN=HELPER-DISPOSABLE-$id" >/dev/null 2>&1
done
keytool -exportcert -keystore "$work/test-only.jks" -storepass:env FIXTURE_PASS -alias fixture -file "$work/public.der" >/dev/null 2>&1
python3 - "$work" <<'PY'
import hashlib,json,sys
from pathlib import Path
p=Path(sys.argv[1]);d={'status':'OWNER_CONFIRMED','certificate_sha256':hashlib.sha256((p/'public.der').read_bytes()).hexdigest(),'owner_confirmation':'DISPOSABLE FIXTURE ONLY; not production trust'}
(p/'fixture-policy.json').write_text(json.dumps(d))
PY
# Confirm default policy remains unapproved and all existing host compatibility tests pass.
python3 -m unittest discover -s tests/security -p test_local_release.py -v
python3 -m unittest discover -s tests/security -p test_release.py -v
# Fail closed without explicit release versions; CI number is not a release version.
if gradle :app:verifyReleaseInputs --no-daemon > "$work/no-version.log" 2>&1; then echo 'Missing version accepted'; exit 1; fi
grep -q 'explicit releaseVersionCode' "$work/no-version.log"
if gradle :app:verifyReleaseInputs -PreleaseVersionCode=200 -PreleaseVersionName=2.0.0 -PmigrationFixtureVersion=999 --no-daemon > "$work/fixture.log" 2>&1; then echo 'Fixture release override accepted'; exit 1; fi
grep -q 'Migration fixture properties are forbidden' "$work/fixture.log"
if gradle :app:verifyReleaseInputs -PreleaseVersionCode=0 -PreleaseVersionName=2.0.0 --no-daemon > "$work/zero.log" 2>&1; then echo 'Zero version accepted'; exit 1; fi
grep -q 'releaseVersionCode must be in' "$work/zero.log"
source_sha=$(git rev-parse HEAD)
# Exercise the exact source-commit worktree builder and ensure CI env cannot override version.
GITHUB_RUN_NUMBER=999999 python3 tools/prepare-release.py --commit "$source_sha" --version-code 200 --version-name 2.0.0 --out "$work/v200"
GITHUB_RUN_NUMBER=999998 python3 tools/prepare-release.py --commit "$source_sha" --version-code 201 --version-name 2.0.1 --out "$work/v201"
# No production key or policy is inferred; only this temporary fixture uses a computed test digest.
sign_fixture() {
  apksigner sign --ks "$work/$1.jks" --ks-key-alias fixture --ks-pass env:FIXTURE_PASS --key-pass env:FIXTURE_PASS --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false --v4-signing-enabled false --out "$work/$3.apk" "$work/$2/helper-unsigned-aligned.apk"
}
sign_fixture test-only v200 previous
sign_fixture test-only v201 next
sign_fixture other v201 wrong-signer
python3 tools/verify-release.py "$work/previous.apk" --policy "$work/fixture-policy.json" --version-code 200 --version-name 2.0.0
python3 tools/verify-release.py "$work/next.apk" --policy "$work/fixture-policy.json" --version-code 201 --version-name 2.0.1 --previous-apk "$work/previous.apk"
# Official SDK creates multiple signers and a genuine test lineage; neither is accepted.
apksigner sign --ks "$work/test-only.jks" --ks-pass env:FIXTURE_PASS --key-pass env:FIXTURE_PASS --next-signer --ks "$work/other.jks" --ks-pass env:FIXTURE_PASS --key-pass env:FIXTURE_PASS --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false --v4-signing-enabled false --out "$work/multiple.apk" "$work/v201/helper-unsigned-aligned.apk"
apksigner rotate --out "$work/test.lineage" --old-signer --ks "$work/test-only.jks" --ks-pass env:FIXTURE_PASS --key-pass env:FIXTURE_PASS --new-signer --ks "$work/other.jks" --ks-pass env:FIXTURE_PASS --key-pass env:FIXTURE_PASS
apksigner sign --ks "$work/test-only.jks" --ks-pass env:FIXTURE_PASS --key-pass env:FIXTURE_PASS --next-signer --ks "$work/other.jks" --ks-pass env:FIXTURE_PASS --key-pass env:FIXTURE_PASS --lineage "$work/test.lineage" --rotation-min-sdk-version 33 --v1-signing-enabled false --v4-signing-enabled false --out "$work/lineage.apk" "$work/v201/helper-unsigned-aligned.apk"
python3 - "$work" <<'PY'
from pathlib import Path
import hashlib, subprocess,sys
w=Path(sys.argv[1]); verify=[sys.executable,'tools/verify-release.py']
# Tamper with signed content; never use a production artifact.
data=bytearray((w/'next.apk').read_bytes());data[80]^=1;(w/'tampered.apk').write_bytes(data)
base=['--policy',str(w/'fixture-policy.json'),'--version-code','201','--version-name','2.0.1']
cases=[('wrong signer',[str(w/'wrong-signer.apk')]+base),('multiple signers',[str(w/'multiple.apk')]+base),('lineage',[str(w/'lineage.apk')]+base),('tamper',[str(w/'tampered.apk')]+base),('unsigned',[str(w/'v201/helper-unsigned-aligned.apk')]+base),('equal version',[str(w/'next.apk')]+base+['--previous-apk',str(w/'next.apk')]),('downgrade',[str(w/'previous.apk'),'--policy',str(w/'fixture-policy.json'),'--version-code','200','--version-name','2.0.0','--previous-apk',str(w/'next.apk')]),('wrong code',[str(w/'next.apk')]+[x if x!='201' else '202' for x in base]),('unapproved policy',[str(w/'next.apk'),'--version-code','201','--version-name','2.0.1'])]
for label,args in cases:
    result=subprocess.run(verify+args,capture_output=True,text=True)
    assert result.returncode != 0 and 'REJECT:' in result.stderr, (label,result.stdout,result.stderr)
    print('PASS rejection:',label)
for apk in ('previous.apk','next.apk'):
    expected=hashlib.sha256((w/apk).read_bytes()).hexdigest()+'  '+apk+'\n'
    assert (w/(apk+'.sha256')).read_text()==expected
print('PASS: final signed APK checksums independently recomputed; production policy untouched')
PY
# Preserve debug/fixture separation without any emulator or UI tests.
gradle :app:assembleDebug -PmigrationFixtureVersion=123 --no-daemon
aapt dump badging app/build/outputs/apk/debug/app-debug.apk > "$work/debug.txt"
grep -q "package: name='de.evp.helfer.debug' versionCode='123'" "$work/debug.txt"
grep -q 'application-debuggable' "$work/debug.txt"
echo 'PASS: unsigned source builds, local v2 signing, independent policy, rejection cases and debug separation'
