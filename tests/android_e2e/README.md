# Controlled real Android E2E

This suite installs real test APKs and official Firefox on Android API 33/35,
operates actual controls with Android UI Automator (uiautomator2), captures
ADB screen recordings/screenshots/hierarchies/logcat, and loads **unmodified**
Kadri dist/firefox with Mozilla web-ext. Screenshot matching/OCR handles controls omitted by Firefox's Android accessibility tree.
No GM shim, simulated browser API,
Playwright mobile emulation, open-shadow test build or direct invocation of
player internals is used.

## Execute deliberately

The workflow is .github/workflows/android-e2e.yml. It has contents:read,
no production secrets, no release/tag operations, no pull_request_target and no
automatic runs on ordinary development/security branches. It builds once and
runs at most two emulator jobs. The special codex/android-e2e-run ref is reserved
for an explicitly requested execution; advance it deliberately only. Normal
work belongs on codex/android-e2e. A manual workflow_dispatch is also provided
once GitHub makes the workflow available for dispatch (normally after it exists
on the default branch). Do not merge anything solely to obtain that button.

First full matrix: [33,35]. During harness calibration execution.json restricts a
controlled push to [33]; select [33,35] deliberately for the final comparison. Exact Firefox version is recorded. Default latest is
resolved through Mozilla product details; for a repeated baseline use the
recorded exact version. Mozilla HTTPS APK origin and downloaded digest are
recorded; this does not independently pin Mozilla's signing certificate.

Local prerequisites: Linux/macOS shell, Android SDK/adb and running disposable
API33/35 emulator, JDK17, Gradle8.9, Node22, Python3.12, ffmpeg, Tesseract OCR, zip, and the Cairo shared library.
Python image dependencies are pinned in requirements.txt (OpenCV/CairoSVG/Pillow).
The SVG paths in icons.json come from the player's existing Material Icons
(Apache2.0); they are screenshot targets, not player instrumentation.
Install requirements.txt and web-ext@10.8.0. Reproduce the build job to create
e2e-bundle (two debug APKs, official Firefox APK+JSON, unmodified Kadri Firefox
directory and source-commit records), then:

    python3 -m unittest discover -s tests/android_e2e -p test_server.py -v
    E2E_ROUTES='kadri userscript' bash tests/android_e2e/emulator.sh

Use ONLY an empty emulator. Setup grants notification/overlay permissions and
battery exemption with adb and stops/installs test apps. These are infrastructure
steps, not proof that every Settings permission screen has been navigated.
Firefox is provisioned as the emulator's default browser with Android's role
command, to exclude the preinstalled WebView Browser Tester. This is setup,
not proof of default-browser Settings navigation. The pairing step itself uses
the actual Helper Connect button, Android resolver/Firefox, extension or manager
storage and the Helper HTTP service. Known Android first-use notification and
immersive-fullscreen prompts are acknowledged through their actual native UI.

The debug key is generated with two-day validity, never taken from history.
Both APKs have test IDs (.debug / .e2ecompeting). The second uses identical Java
sources to exercise a real socket collision. No production-ID APK is installed.
Do not reuse this environment with personal profiles/accounts or real pairing
credentials. Temporary pairing credentials may appear in video/UI hierarchies;
only disposable test state is present. Text logs mask the pairing key.
The test identity and APK bundle expire after two days; evidence after seven.

## Owned media server

server.py generates two 60-second MP4s, an MPEG-TS HLS VOD and a WebVTT subtitle
with ffmpeg. It serves exact, suffix and open-ended Range responses (416 for
invalid/multipart ranges), HEAD, same/cross-host redirects, slow body delivery
and delayed headers. Ten host fixture-contract tests run before allocating an
emulator. That host check is **not** Android coverage.

adb reverse exposes the host server to device loopback:8765, adb forward
14781 to the actual Helper 47811 socket. This is a local media server integration,
not evidence of Wi-Fi discovery, LAN throughput or Samsung networking behavior.
Request observations retain only credential-presence booleans, never values.

## What the first runner asserts

- Actual installed Helper foreground service; real auth rejection and Host guard.
- Real Android Storage Access Framework settings export/change/import round trip.
- Eight force-stop/restart cycles, preserved random key, recovered listening port.
- Official Firefox onboarding/settings and supported remote debugging toggle.
- Real temporary Kadri install, accessible player entry, actual browser pairing.
- Player clock advance, pause/resume, forward seek, original-position restoration.
- Actual volume/brightness changes after browser swipes.
- MP4 redirect, HLS and iframe playback by accessible advancing clock.
- Two UI-triggered file downloads with exact saved-file SHA256 verification.
- Android PiP pinned-task observation and return to player.
- Actual competing installed app detects occupied socket instead of reporting running.
- Target Java fatal exception checks plus raw native/ANR/exit diagnostics.

Each case is PASS, FAIL, BLOCKED (prerequisite failed), or NOT_TESTED
(unsupported/unimplemented). A failed or blocked required assertion makes the
emulator job red. NOT_TESTED rows remain prominently in the report. The suite
does not claim complete coverage merely because the job is green.

Timings are initial observations. "Entry to controls" is not decoded first-frame
latency. PSS dumps are retained per restart; eight successful restarts alone do
not demonstrate absence of a memory leak. Physical power, display, Samsung OEM
background behavior and API37 require remaining real-device validation.

## Explicit gaps / next supported increments

The workflow offers kadri, userscript and both routes; both runs sequentially
inside each emulator, not as an additional matrix. Violentmonkey 2.49.0 is fetched
from its upstream release with a pinned SHA256, temporarily loaded through the
supported web-ext route, and the unchanged userscript installed through its real
Install UI from the local server. It does not use Kadri's GM implementation.
Profiles and Helper test data are cleared between routes. Each route gets its own
report, evidence and export filename. A requested but failing install is FAIL;
selecting only Kadri leaves the userscript route untested. Vivaldi is NOT_TESTED.
Kadri coverage does not imply userscript-manager coverage. Firefox Android
supports web-ext temporary loading; each route requires actual visible UI.
Unknown onboarding/settings/installation screens fail visibly; there is no
fallback to fake content injection. Temporary extensions disappear on browser
restart; helper-only restarts do not restart Firefox. Persistent/signed add-on
installation and Firefox process-death recovery are separate scenarios.

Fixtures and assertions for subtitles, alternate source, cross-host redirects,
slow downloads, notification cancellation and concurrent-download handoff are
implemented. Their presence is not a passing test: consult the executed run's
per-case status. Own-player quality selection and delayed-header contention on
Android still need separate assertions. Native credentialed redirect checks
have passed on API33; this does not imply the browser download UI has passed. Chrome DNR/worker lifecycle stays in the existing actual
desktop Chromium suite; Android Firefox cannot validate Chrome DNR.

Production signature migration is not exercised here; use the existing
disposable APK migration workflow for API26/28/31/32/33/35. No real production
successor key or lineage is generated. Expand older Android UI tests only after
API33/35 infrastructure is reliable; do not start a broad legacy matrix by default.

## Evidence and triage

Each case records .png, .xml, .mp4 where Android screenrecord succeeds. The report
contains exception type/trace and prerequisites. logcat, app exit-info, meminfo,
web-ext logs, package/version/signature hashes and commit IDs accompany results.
The final report job aggregates both device reports in one artifact and summary.

Inspect screenshot + hierarchy + logcat before classifying a failure. A missing
Firefox menu label is an automation compatibility failure, not a proven player
bug. An app crash or mismatched saved media is a product failure only once
reproduced outside a broken setup. Never relax playback/download assertions or
change product behavior just to make this suite green.

Official supported route:
https://extensionworkshop.com/documentation/develop/developing-extensions-for-firefox-for-android/
Android UI Automator:
https://developer.android.com/training/testing/other-components/ui-automator

Violentmonkey Android support is listed by Mozilla:
https://addons.mozilla.org/en-US/android/addon/violentmonkey/
The unchanged userscript still declares a mutable hls.js@1 CDN @require; the
manager fetches it normally. This route therefore needs CDN access during
installation and is not a completely offline dependency baseline. Owned video
fixtures never use external streaming sites. No metadata URL is silently rewritten.

For test-only changes a previous bundle may be reused (reuse_bundle_run). The
workflow compares the complete app subtree and root Gradle build inputs by Git
object IDs before reusing its APKs, then verifies their saved SHA256 checksums.
The original APK commit stays in provenance; automation_commit records the new
tests. Changing Android build input refuses reuse. Kadri is rebuilt at the
selected exact kadri_sha; Firefox version is freshly resolved/downloaded. No
historical repository keystore or full-history checkout is needed. Set
execution.json previous_run to 0 after diagnosis; expired optional evidence
never blocks the functional suite. Ordinary development refs do not trigger CI.
Screen recordings use 720x1280/1Mbps to limit artifact size; screenshots retain
full device resolution for visual assertions.

The installed-APK tests found a product issue on API33 at 503cfe0: accepted
plain-HTTP media URLs were blocked by Android's target-SDK cleartext policy
(Fehler: Cleartext HTTP traffic to 127.0.0.1 not permitted). Native restart,
unauthorized-key/Host rejection and real port conflict passed on both isolated
route preparations. Browser installation had not yet been reached because of a
Firefox menu-selector failure. These are not passing browser integrations.

The runner refuses physical devices (ro.kernel.qemu must be 1) before setup.
The shell entry also selects only emulator-* ADB serials. This prevents the
profile-reset steps from touching the owner's Samsung/Firefox data. Local tests
run on one disposable emulator at a time; Actions uses isolated workers per API.
A 128MiB valid MP4 fixture (sparse ISO-BMFF free box) supports a full saved-file
SHA256 check with repeated Helper/Firefox/DownloadProvider PSS snapshots. It is
not a multi-GB memory guarantee; recordings and reports exclude the media copy.

Real API33 run 40141678616 verified the HTTP manifest correction and exposed a
separate Cookie leak in platform DownloadManager. Credentialed file streaming
and bounded retry/resume checks are described in FINDINGS.md. Do not reuse an
older APK bundle across this app-source change; the workflow equality guard
must reject that. Before browser setup succeeds, native APK API checks remain
native-only evidence, not evidence of browser pairing or playback.

## Inspect evidence without rebuilding or running an emulator

.github/workflows/android-e2e-inspect.yml reads an existing completed run's
artifacts with contents:read/actions:read. It has no Android job, APK build,
repository write permission or release operation. Set execution.json previous_run
and deliberately advance only codex/android-e2e-inspect to run the inspector.
Its workflow_dispatch becomes available under GitHub's usual default-branch
constraint. Ordinary commits never start it. This separates screenshot/log
diagnosis from new device executions.

The main workflow supports an explicit smoke scope for driver calibration.
A green smoke run would cover only setup/install/pair/play-pause/crash checks;
all omitted functional cases remain NOT_TESTED. Use scope full with APIs33/35
for functional validation. The lightweight screenshot calibration runs before
emulator allocation and must pass its positive and negative controls.

Visual/OCR polling contributes observer overhead. Entry-to-controls timings
must be compared only with the same instrumentation, source, browser version
and emulator configuration. They are not decoded first-frame times. Read the
raw PSS/CPU samples before drawing performance conclusions; emulator power
readings cannot establish Samsung battery consumption.

Current findings, confirmed native checks and lifecycle tradeoffs are recorded
in FINDINGS.md. Until full browser scenarios pass on both APIs, this work is
not a merge-readiness verdict for either security PR.
