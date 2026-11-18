#!/usr/bin/env python3
"""Build an aligned, unsigned APK from an exact reviewed commit. Never loads keys."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from release_verify import ROOT, metadata, run, signing_ids

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--commit', required=True)
p.add_argument('--version-code', required=True, type=int)
p.add_argument('--version-name', required=True)
p.add_argument('--out', required=True, type=Path)
a = p.parse_args()
if not re.fullmatch('[0-9a-f]{40}', a.commit) or not 1 <= a.version_code <= 2100000000 or not re.fullmatch('[0-9][0-9A-Za-z.+-]{0,63}', a.version_name):
    p.error('Full 40-character source commit and explicit valid versionCode/versionName required')
repo = Path(run('git', '-C', ROOT, 'rev-parse', '--show-toplevel').strip())
subprocess.run(['git', '-C', repo, 'merge-base', '--is-ancestor', 'd1119234e25273210d0807961058484058cc0f49', a.commit], check=True)
if 'Gradle 9.3.1\n' not in run('gradle', '--version'):
    p.error('Pinned Gradle 9.3.1 required')
java = subprocess.run(['java', '-version'], capture_output=True, text=True, check=True)
if not re.search(r'version "17[.\"]', java.stderr+java.stdout):
    p.error('Pinned JDK major 17 required')
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
bt = sdk/'build-tools/34.0.0'
if not (bt/'zipalign').is_file():
    p.error('Android build-tools 34.0.0 required')
# A fresh output directory prevents stale APKs being mistaken for this build.
a.out = a.out.resolve()
a.out.mkdir(parents=True, exist_ok=False)
env = {k:v for k,v in os.environ.items() if not k.startswith(('EVP_', 'ORG_GRADLE_PROJECT_')) and k not in ('GRADLE_OPTS','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS')}
env['PATH'] = str(bt)+os.pathsep+env['PATH']
with tempfile.TemporaryDirectory(prefix='helper-unsigned-') as temp:
    checkout = Path(temp)/'source'
    try:
        subprocess.run(['git','-C',repo,'worktree','add','--detach',checkout,a.commit],check=True)
        subprocess.run(['gradle',':app:assembleRelease','--no-daemon','--no-build-cache','--no-configuration-cache',
                        '-PreleaseVersionCode='+str(a.version_code),'-PreleaseVersionName='+a.version_name],cwd=checkout,env=env,check=True)
        unsigned = checkout/'app/build/outputs/apk/release/app-release-unsigned.apk'
        if signing_ids(unsigned):
            raise ValueError('Build unexpectedly contains an APK signature block')
        import zipfile
        with zipfile.ZipFile(unsigned) as z:
            if any(re.match(r'META-INF/.*\.(RSA|DSA|EC|SF)$', n, re.I) for n in z.namelist()):
                raise ValueError('Build unexpectedly contains a v1 signature')
        prepared = a.out/'helper-unsigned-aligned.apk'
        subprocess.run([bt/'zipalign','-f','-p','4',unsigned,prepared],check=True)
        subprocess.run([bt/'zipalign','-c','-p','4',prepared],check=True)
        # Use the pinned SDK tools to inspect the resulting binary manifest.
        os.environ['PATH'] = env['PATH']
        metadata(prepared,a.version_code,a.version_name)
        import hashlib
        (a.out/'build-provenance.json').write_text(json.dumps({
            'source_commit':a.commit,'version_code':a.version_code,'version_name':a.version_name,
            'unsigned_sha256':hashlib.sha256(prepared.read_bytes()).hexdigest(),
            'gradle':'9.3.1','agp':'9.1.1','jdk_major':17,'build_tools':'34.0.0',
            'state':'UNSIGNED_NOT_INSTALLABLE_NOT_APPROVED'},indent=2)+'\n')
        print('PASS: unsigned aligned APK prepared; not a production release')
    finally:
        if checkout.exists():
            subprocess.run(['git','-C',repo,'worktree','remove','--force',checkout],check=True)
