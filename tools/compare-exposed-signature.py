#!/usr/bin/env python3
"""Run locally on a trusted workstation. Never run with production credentials in PR CI.
Only exports the public certificate; never signs, rotates, installs or publishes.
Use a trusted old APK copied from an existing installation/offline archive.
"""
import argparse
import getpass
import hashlib
import os
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("keystore")
parser.add_argument("trusted_old_apk")
parser.add_argument("--alias", required=True)
args = parser.parse_args()
env = os.environ.copy()
env["KEYSTORE_AUDIT_PASSWORD"] = getpass.getpass("Exposed keystore password (not logged): ")
try:
    cert = subprocess.check_output(["keytool", "-exportcert", "-keystore", args.keystore,
        "-alias", args.alias, "-storepass:env", "KEYSTORE_AUDIT_PASSWORD"], env=env)
finally:
    env.pop("KEYSTORE_AUDIT_PASSWORD", None)
key_fingerprint = hashlib.sha256(cert).hexdigest()
output = subprocess.check_output(["apksigner", "verify", "--verbose", "--print-certs",
                                  args.trusted_old_apk], text=True)
fingerprints = sorted(set(re.findall(r"^(?:Signer #\d+|V\d(?:\.\d+)? Signer(?: #\d+)?):? certificate SHA-256 digest: ([0-9a-fA-F]+)", output, re.MULTILINE)))
if len(fingerprints) != 1:
    raise SystemExit("Expected one unrotated APK signer; inspect signing lineage manually")
apk_fingerprint = fingerprints[0].lower()
print("Keystore public certificate SHA-256:", key_fingerprint)
print("Trusted APK signer SHA-256:", apk_fingerprint)
print("Trusted APK file SHA-256:", hashlib.sha256(open(args.trusted_old_apk, "rb").read()).hexdigest())
if key_fingerprint != apk_fingerprint:
    raise SystemExit("MISMATCH: do not choose a production signing identity")
print("MATCH: the exposed identity signs this APK. Treat it as compromised.")
