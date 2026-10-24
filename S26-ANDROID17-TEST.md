# Samsung S26 Ultra — Android 17 — Vivaldi / Firefox

Device supplied by owner. Every physical row is currently NOT TESTED. Record Settings > About phone > Software information Android/One UI versions and the browser About version. If using ADB, record `adb shell getprop ro.build.version.sdk`; do not infer platform behavior solely from the marketed version.

## Preparation
1. Use a test browser profile/account. Note current helper language/theme/autostart and stop downloads before changing installations.
2. Download only helper-baseline-APKs-TEST-ONLY / helper-debug-NOT-production workflow artifacts from the reviewed PR. Verify their file hashes and keep stable/current labels.
3. These APKs use de.evp.helfer.debug and temporary test signing. They cannot update de.evp.helfer. A failed update of the production app is not permission to uninstall it without saving your settings.
4. Stop the installed production helper while testing debug. Keep it only for the explicit two-package conflict case.
5. Record battery mode, screen brightness/refresh, Wi-Fi, temperature and whether Samsung's background-app restrictions/optimization are enabled. Do not disable them silently to make a test pass.
6. Use the same direct video, HLS, iframe fixture, subtitle files and LAN server for both versions. Record their hashes/URLs without credentials.

## Run three separate configurations
A. Vivaldi + Kadri; disable the Violentmonkey player script.
B. Vivaldi + Violentmonkey player script; disable Kadri.
C. Firefox + the intended supported Kadri or Violentmonkey route; keep only one player active.
Keep a separate result row for each route. An extension unsupported by a particular browser build is NOT TESTED for that route, not a functional pass.

## Functional checklist
Run F01-F15 exactly as described in FINAL-DEVICE-VALIDATION.md.
Pay particular attention to:
- Notification and overlay permission prompts on Android 17; helper foreground-service start from app and browser handoff.
- Samsung battery restrictions: background for five minutes, screen lock/unlock, then return. Record whether permissions or background limits caused a stop.
- PiP via Home, PiP close and fullscreen return in each browser. Firefox and Vivaldi can use different system behavior.
- One UI gesture navigation: Back, rotation, app switch and return; keep the video position and audio continuity visible in a timecode recording.
- Brightness reset after normal close, browser force-stop and screen lock; confirm the display is not left dim.
- Volume follows the media stream; no stuck overlay or second audio source.
- System file picker exports/imports only language/theme/autostart. Confirm no paired flag or key in the exported file.
- Conflict: start production helper then debug. Debug must show a port conflict and must not open a pairing page. Stop production; start debug and pair it explicitly. Reverse order. Existing browser pairing to a different installation is not automatically migrated.

## Counterbalanced performance session
Do stable/current/current/stable at least three times; separate Vivaldi and Firefox results. Let the phone cool between runs.
For each version record:
1. Direct-video first visible frame.
2. Open and close handoff duration at the same position.
3. Resolution/opening with two ongoing downloads and a slow-header server.
4. Browser/helper PSS before, during and after 20/50 player cycles.
5. CPU over equal playback windows.
6. Matched unplugged battery sessions (30 minutes or longer), same screen/network conditions.
Do not compare desktop or emulator absolute times with S26 timings. Screen recording/profiling overhead must be identical in both runs. Keep raw samples before deciding thresholds.

## What not to do
Do not generate a production key, rotate the production signer, install a fixture APK over production, or publish a build.
A successful emulator API37 result would cover installation mechanics only; it would not prove Samsung/One UI background behavior or battery performance.
Return the filled DEVICE-RESULTS.csv with PASS/FAIL/NOT TESTED and observations; never include pairing keys or real cookies/tokens.

## Optional ADB snapshots
On a computer with Android platform-tools, authorize only your own device. Find the installed browser package (usual names: com.vivaldi.browser and org.mozilla.firefox; verify yours).
Before the matched test session:
`tools/collect-device-snapshot.sh results/vivaldi-before com.vivaldi.browser`
After the same fixed workload:
`tools/collect-device-snapshot.sh results/vivaldi-after com.vivaldi.browser`
Repeat with the verified Firefox package and distinct output folders.
The collector is read-only: it does not reset battery stats, clear apps, alter brightness, install packages or read pairing preferences. Review files locally before sharing; battery stats may include broader device usage.
If no ADB computer is available, use the manual functional/video procedure and mark CPU/PSS as NOT TESTED. Do not substitute a Samsung aggregate memory tile for process PSS.
