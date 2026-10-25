#!/usr/bin/env bash
# Read-only snapshots on the owner's authorized Android device. Review local files before sharing.
set -euo pipefail
if [ "$#" -ne 2 ]; then echo "Usage: $0 OUTPUT_DIRECTORY BROWSER_PACKAGE"; exit 2; fi
out=$1
browser_pkg=$2
if [[ ! "$browser_pkg" =~ ^[A-Za-z0-9_.]+$ ]]; then echo "Invalid package name"; exit 2; fi
mkdir -p "$out"
{
  date -u +%FT%TZ
  adb shell getprop ro.product.model
  adb shell getprop ro.build.version.release
  adb shell getprop ro.build.version.sdk
  adb shell getprop ro.build.version.oneui
  adb shell uptime
} > "$out/environment.txt"
adb shell dumpsys battery > "$out/battery-state.txt"
adb shell dumpsys thermalservice > "$out/thermal.txt"
for pkg in de.evp.helfer.debug "$browser_pkg"; do
  adb shell dumpsys meminfo "$pkg" > "$out/$pkg-memory.txt"
  adb shell dumpsys cpuinfo | grep -F "$pkg" > "$out/$pkg-cpu.txt" || true
  adb shell dumpsys package "$pkg" | grep -E 'versionName=|versionCode=' > "$out/$pkg-version.txt" || true
  adb shell dumpsys batterystats "$pkg" > "$out/$pkg-battery-stats.txt"
done
echo "Saved observations to $out. Missing CPU rows are not zero CPU. These are snapshots, not a benchmark verdict."
