#!/usr/bin/env python3
"""Read-only compatibility preflight. Requires SDK apksigner and aapt in PATH.
Does not install, uninstall, publish, or access application settings.
"""
import argparse
import re
import subprocess

def certificate(text):
    values = sorted(set(re.findall(r"^(?:Signer #\d+|V\d(?:\.\d+)? Signer(?: #\d+)?):? certificate SHA-256 digest: ([0-9a-fA-F]+)", text, re.MULTILINE)))
    if len(values) != 1:
        raise ValueError("Expected exactly one APK signer")
    return values[0].lower()

def package(text):
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)'", text)
    if not m:
        raise ValueError("Cannot read APK package identity")
    return m[1], int(m[2])

def compatible(old_cert, new_cert, old_package, new_package):
    if old_cert != new_cert:
        raise ValueError("Signing identity changed")
    if old_package[0] != "de.evp.helfer" or new_package[0] != old_package[0]:
        raise ValueError("Production application ID changed")
    if new_package[1] <= old_package[1]:
        raise ValueError("New versionCode must increase")

def inspect(apk):
    cert = certificate(subprocess.check_output(
        ["apksigner", "verify", "--print-certs", apk], text=True))
    pkg = package(subprocess.check_output(["aapt", "dump", "badging", apk], text=True))
    return cert, pkg

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("old_apk")
    parser.add_argument("new_apk")
    parser.add_argument("--expected-cert", required=True)
    args = parser.parse_args()
    old_cert, old_pkg = inspect(args.old_apk)
    new_cert, new_pkg = inspect(args.new_apk)
    expected = re.sub(r"[:\s]", "", args.expected_cert).lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected) or old_cert != expected:
        raise SystemExit("Trusted production APK does not match expected certificate")
    compatible(old_cert, new_cert, old_pkg, new_pkg)
    print("PASS: package, increasing versionCode and verified production signatures")
