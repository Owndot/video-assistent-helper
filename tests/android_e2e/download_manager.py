#!/usr/bin/env python3
"""Pinned upstream Violentmonkey release. Test dependency only, never a GM shim."""
import hashlib
from pathlib import Path
import urllib.request
import zipfile

version = "2.49.0"
digest = "847c86a20e214d3515a9dfabe5357875af20e305bbcc1692d6e109a4edd6a7f3"
url = f"https://github.com/violentmonkey/violentmonkey/releases/download/v{version}/Violentmonkey-webext-v{version}.zip"
bundle = Path("e2e-bundle")
target = bundle / "violentmonkey"
target.mkdir(parents=True, exist_ok=True)
archive = bundle / "violentmonkey.zip"
with urllib.request.urlopen(url, timeout=60) as response:
    archive.write_bytes(response.read(4 * 1024 * 1024))
assert hashlib.sha256(archive.read_bytes()).hexdigest() == digest, "Unexpected manager dependency"
with zipfile.ZipFile(archive) as z:
    for entry in z.infolist():
        dest = (target / entry.filename).resolve()
        assert target.resolve() in dest.parents, "Unsafe upstream ZIP path"
    z.extractall(target)
assert (target / "manifest.json").is_file()
(bundle / "manager-provenance.txt").write_text(f"Violentmonkey {version}\n{url}\nsha256 {digest}\n")
archive.unlink()
