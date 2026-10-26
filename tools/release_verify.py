"""Option A: SDK verifies cryptography; we enforce release metadata and v2-only policy."""
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile

BLOCKED = 'ec17fab1a92079911ea50e6d308f8c70ad79efc3bb48e0231f2559b26b7e42e5'
V2 = 0x7109871a
V3 = {0xf05368c0, 0x1b93ad61}
ROOT = Path(__file__).resolve().parents[1]

def run(*args):
    return subprocess.check_output(list(map(str, args)), text=True, stderr=subprocess.STDOUT)

def fingerprint(value):
    if not isinstance(value, str):
        raise ValueError('Owner-confirmed production certificate is missing')
    value = re.sub(r'[:\s]', '', value).lower()
    if not re.fullmatch('[0-9a-f]{64}', value) or len(set(value)) == 1:
        raise ValueError('Invalid/placeholder production fingerprint')
    if value == BLOCKED:
        raise ValueError('Compromised production certificate is forbidden')
    return value

def policy(path):
    data = json.loads(Path(path).read_text())
    if data.get('status') != 'OWNER_CONFIRMED' or not isinstance(data.get('owner_confirmation'), str) or not data['owner_confirmation'].strip():
        raise ValueError('Production policy UNAPPROVED: separate owner confirmation required')
    return fingerprint(data.get('certificate_sha256'))

def certificate(text):
    # Accept old SDK output for existing host tests; real verification also checks count.
    values = re.findall(r'^(?:Signer #\d+|V\d(?:\.\d+)? Signer(?: #\d+)?):? certificate SHA-256 digest: ([0-9a-fA-F]+)', text, re.M)
    values = set(v.lower() for v in values)
    if len(values) != 1:
        raise ValueError('Expected exactly one APK signer')
    return values.pop()

def package(text):
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)'", text)
    if not match:
        raise ValueError('Cannot read APK package identity')
    return match[1], int(match[2])

def compatible(old_cert, new_cert, old_package, new_package):
    if old_cert == BLOCKED or new_cert == BLOCKED:
        raise ValueError('Compromised production certificate is forbidden')
    if old_cert != new_cert:
        raise ValueError('Signing identity changed')
    if old_package[0] != 'de.evp.helfer' or new_package[0] != old_package[0]:
        raise ValueError('Production application ID changed')
    if new_package[1] <= old_package[1]:
        raise ValueError('New versionCode must increase')

def signing_ids(apk):
    """Parse APK block framing only, never implement signature verification.

    Reject all v3/v3.1 blocks, so no proof-of-rotation lineage is accepted.
    Official apksigner verifies the v2 signature independently afterwards.
    """
    data = Path(apk).read_bytes()
    eocd = data.rfind(b'PK\x05\x06', max(0, len(data)-65557))
    if eocd < 0 or eocd + 22 > len(data):
        raise ValueError('Missing ZIP EOCD')
    if eocd + 22 + struct.unpack_from('<H', data, eocd+20)[0] != len(data):
        raise ValueError('Invalid ZIP end')
    central = struct.unpack_from('<I', data, eocd+16)[0]
    if central > eocd or central == 0xffffffff:
        raise ValueError('Unsupported ZIP layout')
    if central < 24 or data[central-16:central] != b'APK Sig Block 42':
        return set()
    size = struct.unpack_from('<Q', data, central-24)[0]
    begin = central-size-8
    if size < 24 or begin < 0 or struct.unpack_from('<Q', data, begin)[0] != size:
        raise ValueError('Invalid APK signing block')
    pos, end, ids = begin+8, central-24, set()
    while pos < end:
        if pos+8 > end:
            raise ValueError('Truncated signing entry')
        length = struct.unpack_from('<Q', data, pos)[0]
        pos += 8
        if length < 4 or pos+length > end:
            raise ValueError('Invalid signing entry length')
        ident = struct.unpack_from('<I', data, pos)[0]
        if ident in ids:
            raise ValueError('Duplicate signing scheme')
        ids.add(ident)
        pos += length
    return ids

def metadata(apk, code=None, name=None):
    text = run('aapt', 'dump', 'badging', apk)
    pkg, actual_code = package(text)
    if pkg != 'de.evp.helfer' or not 1 <= actual_code <= 2100000000:
        raise ValueError('Invalid production package/versionCode')
    if code is not None and actual_code != code:
        raise ValueError('Explicit versionCode mismatch')
    actual_name = re.search(r"versionName='([^']*)'", text)
    if not actual_name or (name is not None and actual_name[1] != name):
        raise ValueError('Explicit versionName mismatch')
    if "sdkVersion:'26'" not in text or "targetSdkVersion:'34'" not in text:
        raise ValueError('Expected minSdk 26 and targetSdk 34')
    if 'application-debuggable' in text:
        raise ValueError('Debuggable APK is forbidden')
    xml = run('aapt', 'dump', 'xmltree', apk, 'AndroidManifest.xml')
    debug = re.findall(r'android:debuggable\([^)]*\)=\(type 0x12\)(0x[0-9a-fA-F]+)', xml)
    if 'android:debuggable(' in xml and debug != ['0x0']:
        raise ValueError('Release must have effective debuggable=false')
    return (pkg, actual_code), actual_name[1]

def inspect(apk, expected, code=None, name=None):
    ids = signing_ids(apk)
    if V2 not in ids or ids & V3:
        raise ValueError('Option A requires v2 and forbids v3/v3.1/lineage')
    # Other schemes must not silently add another platform signer.
    with zipfile.ZipFile(apk) as z:
        if any(re.match(r'META-INF/.*\.(RSA|DSA|EC|SF)$', n, re.I) for n in z.namelist()):
            raise ValueError('Option A does not accept v1 signatures')
    text = run('apksigner', 'verify', '--verbose', '--print-certs', '--min-sdk-version', '26', apk)
    if not re.search(r'^Number of signers: 1\s*$', text, re.M) or certificate(text) != expected:
        raise ValueError('APK does not match the one owner-confirmed production signer')
    fingerprint(certificate(text))
    if not re.search(r'Verified using v2 scheme .*: true', text):
        raise ValueError('SDK did not verify v2')
    run('zipalign', '-c', '-p', '4', apk)
    return metadata(apk, code, name)[0]

def verify(apk, policy_path, code, name, previous=None):
    expected = policy(policy_path)  # Never infer trust from keystore/APK/ENV.
    current = inspect(apk, expected, code, name)
    if previous:
        old = inspect(previous, expected)
        compatible(expected, expected, old, current)
    return hashlib.sha256(Path(apk).read_bytes()).hexdigest()
