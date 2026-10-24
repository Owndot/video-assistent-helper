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

## Read-only provenance check of your existing helper
Before stopping/changing the existing production installation, optionally verify its public signer on your own ADB computer:
1. Run `adb shell pm path de.evp.helfer`. Copy the reported base.apk path (without the leading `package:`).
2. Run `adb pull COPIED_BASE_APK_PATH trusted-installed-helper.apk`.
3. Run SDK `apksigner verify --verbose --print-certs trusted-installed-helper.apk`.
4. Compare the **certificate SHA-256** with `ec17fab1a92079911ea50e6d308f8c70ad79efc3bb48e0231f2559b26b7e42e5`, the exposed historical key and pinned public release identity. Also record the APK file hash separately.
This is not an installation or signing operation. A match identifies the old signer; it does not make the exposed key safe. If different, stop migration assumptions and retain the evidence. Do not share private app preferences or pairing keys.

A real production-successor update cannot be tested yet: no successor key/approved lineage exists. Test-key emulator migrations do not authorize a production rotation.

## Detailed result IDs
Use one row per version/browser route in DEVICE-RESULTS.csv. Preserve raw samples in a separate file linked from its evidence column.
- P01: first actually visible frame; P02: open/close handoff; P03: resolution with two downloads and slow headers; P04: matched browser/helper PSS and CPU; P05: matched unplugged battery session; P06: multi-GB file, hash, Range/resume, cancel and slow server; P07: 20/50 open-close cycles and retained memory. Follow the numbered baseline protocol in FINAL-DEVICE-VALIDATION.md; no fixed pass threshold is invented.
- S01: synthetic-cookie/Authorization redirect inspection on controlled servers; S02: simultaneous LAN/public request isolation; S03: actual extension-worker restart during setup/headers/redirect/body; S04: queued/header/body cancellation and timeout cleanup. Desktop CI evidence does not fill these phone rows. If you cannot inspect controlled server logs or worker tools on this browser, mark NOT TESTED with the reason.
- M01: public certificate comparison of the already installed/offline production APK (read-only instructions above).
- M02: an approved future production-successor upgrade plus settings/permission/new-pairing checks. This remains blocked/NOT TESTED: no approved successor key or production lineage exists. Do not generate one or use fixture keys for this row.

Android 8-12L legacy reinstall paths require additional representative devices; success on your Android 17 phone does not cover them. Existing emulator results are separately recorded in FINAL-VALIDATION-REPORT.md.

## Exact current test artifact and efficient run order
Verified isolated debug build: [run 40131433569](https://github.com/Owndot/video-assistent-helper/actions/runs/40131433569), source `8561f7e7d2c5606d9593cae1ea67926d4e4fa2f1`, artifact [helper-debug-NOT-production](https://github.com/Owndot/video-assistent-helper/actions/runs/40131433569/artifacts/12575590883).
The archive includes app-debug.apk, debug-apk.sha256, debug-source-commit.txt and debug-signature.txt. Match the APK SHA-256 to the manifest and verify the signer with SDK apksigner if available. The archive/ZIP digest is a different value from the APK digest. Later docs-only commits do not change these APK sources.
For matched stable/current trials use the two **debug, test-signed source builds** in [comparison run 40131293057](https://github.com/Owndot/video-assistent-helper/actions/runs/40131293057), not the old production APK. These artifacts have limited retention; if expired, rerun the read-only comparison workflow.

1. Record versions/provenance and save existing settings; leave production data intact.
2. Check whether each browser actually supports the intended extension/manager. Mark unavailable routes NOT TESTED; do not install an unrelated browser merely to make the row green.
3. On each available route run F01-F05, F08, F09 and F12 first. If playback, pairing or brightness restoration fails, record it before spending time on battery comparisons.
4. Complete F06-F15, including mirroring/cast only if the source/hardware supports them.
5. Perform matched P01-P04 and P06-P07 trials, then matched unplugged P05 battery sessions. Separate stable-source observations from current-source observations and record network/temperature.
6. Fill the relevant S/M rows only where the required tools and authorization exist. M02 remains NOT TESTED until an approved production migration is separately prepared.
