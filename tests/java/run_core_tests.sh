#!/usr/bin/env bash
# Kern-Tests: HLS (verschlüsselt, fMP4, Byte-Bereiche, Live), DASH (3 Arten, DRM, Live), Cookies, Laden über den "Browser"
set -euo pipefail
cd "$(dirname "$0")"
[ -d srv ] || ./make_streams.sh
[ -d classes ] || ./build.sh
python3 srv.py & SRV=$!
trap 'kill $SRV' EXIT
sleep 1
rm -rf out
java -cp classes TestPortConflict
java -cp classes TestSettingsTransfer
java -cp classes de.evp.helfer.TestCookie
java -cp classes TestMain
echo "--- Prüfung der Ergebnisse mit ffprobe:"
for f in out/*/video.* out/*/audio.*; do
  [ -f "$f" ] || continue
  d=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$f")
  e=$(ffmpeg -v error -i "$f" -f null - 2>&1 | wc -l)
  echo "$f: ${d}s, Decodierfehler: $e"
done
