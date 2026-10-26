#!/usr/bin/env python3
"""Compatibility entrypoint; new releases use the owner-confirmed policy, never ENV trust."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from release_verify import certificate, package, compatible
if __name__ == '__main__':
    raise SystemExit('Use tools/verify-release.py NEW.apk --previous-apk PREVIOUS.apk --version-code CODE --version-name NAME (owner-confirmed policy required).')
