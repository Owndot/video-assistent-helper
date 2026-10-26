#!/usr/bin/env python3
"""Read-only signature/metadata verification; checksum written only after all checks pass."""
import argparse
from pathlib import Path
from release_verify import ROOT, verify
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('apk', type=Path)
p.add_argument('--policy', type=Path, default=ROOT/'release/production-policy.json')
p.add_argument('--version-code', required=True, type=int)
p.add_argument('--version-name', required=True)
p.add_argument('--previous-apk', type=Path)
a = p.parse_args()
try:
    digest = verify(a.apk, a.policy, a.version_code, a.version_name, a.previous_apk)
except Exception as error:
    # SDK output is public metadata; no signing secrets are consumed here.
    p.exit(1, 'REJECT: '+str(error)+'\n')
checksum = a.apk.with_name(a.apk.name+'.sha256')
checksum.write_text(digest+'  '+a.apk.name+'\n')
print('PASS: owner-confirmed signer, v2 only, release metadata, alignment and SHA-256: '+digest)
