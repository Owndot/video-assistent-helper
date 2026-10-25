#!/usr/bin/env python3
"""Download an official Fenix APK; record resolved version, origin and digest."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("version")
ap.add_argument("output", type=Path)
args = ap.parse_args()
version = args.version
if version == "latest":
    with urllib.request.urlopen("https://product-details.mozilla.org/1.0/firefox_versions.json", timeout=30) as r:
        version = json.load(r)["LATEST_FIREFOX_VERSION"]
if not re.fullmatch(r"\d+\.\d+(?:\.\d+)?", version):
    raise SystemExit("Only exact stable Firefox versions or latest are accepted.")
base = f"https://archive.mozilla.org/pub/fenix/releases/{version}/android/fenix-{version}-android-x86_64/"
with urllib.request.urlopen(base, timeout=60) as r:
    listing = r.read().decode()
links = [urllib.parse.urljoin(base, x) for x in re.findall(r'href="([^"]+\.apk)"', listing)]
links = [x for x in links if x.startswith(base) and "android-x86_64" in x]
if len(links) != 1:
    raise SystemExit("Expected one official x86_64 APK; inspect official listing. No unofficial mirror fallback.")
args.output.parent.mkdir(exist_ok=True, parents=True)
with urllib.request.urlopen(links[0], timeout=120) as source, args.output.open("wb") as target:
    while data := source.read(1024 * 1024):
        target.write(data)
meta = {"version": version, "url": links[0], "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
        "trust": "Mozilla HTTPS archive download; digest recorded for reproduction, not an independently pinned certificate."}
args.output.with_suffix(".json").write_text(json.dumps(meta, indent=2))
print(json.dumps(meta))
