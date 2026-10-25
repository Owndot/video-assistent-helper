# Installed-APK findings

## HTTP media was rejected by Android policy

Confirmed on API33 in controlled run 40140618467, source 503cfe0, twice
independently with fresh Helper/Firefox test data:

    native_cookie_redirect FAIL
    Fehler: Cleartext HTTP traffic to 127.0.0.1 not permitted

This is the actual installed Helper executing its download handler and HTTP
client, not the earlier app_process probe or desktop JVM. The HTTP fixture
server was reachable from the actual Firefox APK at the same loopback address.
The native client failed before receiving the fixture redirect.

HelferService /download deliberately accepts HTTP and HTTPS; native HTTP
media servers are part of the existing advertised functionality. The target-SDK
default in the application manifest prevented that HTTP implementation from
working in the installed APK.

Correction: explicitly enable cleartext transport in the application manifest
for the existing HTTP feature. HTTPS certificate verification remains enabled.
This applies to user-selected HTTP media, including LAN servers with arbitrary
hostnames/IPs; the application does not transparently turn HTTPS URLs into HTTP.
HTTP itself is unencrypted. Cookie/redirect site restrictions must still be
verified for BOTH Fetcher.Http and Android DownloadManager.
No test-only network policy or TLS trust bypass is introduced.

Validated in real API33 run 40141678616 at 5f830f1: the HLS-client Cookie control reached both servers and stripped the Cookie at the cross-site destination.
The same-site Cookie control and cross-site destination observation are required
to pass; receiving no cookie because the whole request was blocked is not a pass.

## Harness issues already distinguished from product issues

- Key generation is lazy and the legacy evp-1 key is intentionally rejected.
  The test now uses the rejected request to initialize/read a disposable key,
  without marking the browser paired.
- Reopening a scrolled Helper activity does not guarantee its title is visible.
  The automator checks the actual package and ScrollView rather than requiring
  the offscreen title.
- Firefox 158 has a More Collapsed menu; the exact accessible label must be used,
  since More options refers to the top-level menu button.
- The player's default picture-only mode needs a genuine tap to reveal controls.
  Tests must not wait for initially hidden controls or change that preference.


## HIGH: Android DownloadManager forwarded an explicit Cookie

Confirmed twice with independent empty test data in real API33 run
40141678616 at 5f830f1. The initial 127.0.0.1 server received the intended
disposable Cookie; after redirecting to the distinct localhost site the
destination received that Cookie too:

    native_downloadmanager_cookie_redirect FAIL
    Credentials crossed Android HTTP redirect

The native Fetcher.Http path passed the same site-boundary control in that run.
This is platform DownloadManager behavior with manually supplied headers, not
a simulation or a JVM-only finding.

Correction (confirmed for the installed APK on API33 and API35):
- Ordinary files without a permitted manual Cookie still use DownloadManager.
- Cookie-bearing native files stream through Fetcher.Http's existing per-hop
  credential policy. DownloadManager never receives manual Cookie headers.
- A 64KiB copy buffer writes directly to a cache file; save/notification/cancel
  use the existing native file-download completion path.
- Retries resume only with a stable strong ETag/Last-Modified and matching
  Content-Range. A server ignoring Range restarts the file instead of appending
  the whole response to the prefix. Unexpected content encoding is rejected.
- Two file workers are separate from long-running DownloadManager watchers.
  This prevents a new up-to-25-second queue delay behind those watchers.
  File workers time out after 30 seconds idle.

The new JVM HTTP regression streams 64MiB files with a 32MiB heap, tests unknown
length, truncated/resumed replies, ignored/wrong Range, and cancellation. It is
host transport coverage, not an Android test. The real APK regression additionally
requires a completed saved file with exact SHA256 after the Cookie-safe redirect.

Tradeoffs to validate: credentialed native files now have the same process/FGS
lifecycle as native HLS, rather than DownloadManager's independent process.
Hard process death during such a file and persistence/resume across process
recreation are not guaranteed by this small fix. Completion uses the existing
cache-to-MediaStore copy, so its extra disk I/O should be measured.
No production APK, production signer, release, or default-branch change is made.

## Confirmed correction and remaining validation

Runs [40182442969](https://github.com/Owndot/video-assistent-helper/actions/runs/40182442969)
and [40183356625](https://github.com/Owndot/video-assistent-helper/actions/runs/40183356625)
used the corrected real APK on API33. The credentialed-file check received its
Cookie on the first hop, received none on the foreign hop, and verified the
completed saved file against the fixture SHA256. Native HLS saved a playable
file with audio/video and the expected duration verified by ffprobe.
These checks use the installed application's native API; they do not establish
browser pairing, UI-triggered downloads, or Android35 coverage.

The same runs passed the real SAF export/change/import round trip (pairing key
excluded from export), eight Helper force-stop/restart cycles with the same key
and recovered port, missing/wrong-key and foreign Host rejection, and the
collision between two installed test APKs. PSS observations are retained; no
battery or leak verdict is inferred.

## Firefox Android accessibility limitation in the test driver

Real run [40185452608](https://github.com/Owndot/video-assistent-helper/actions/runs/40185452608)
installed the unchanged Kadri add-on into official Firefox158.0.1 on API33.
The captured screenshot shows the genuine Player pill and advancing source
video. The Android UI hierarchy omits the pill's closed-shadow content.
This is an automation limitation, not proof that Kadri failed to load.

The driver uses UI Automator where accessible. For omitted rendered controls,
it matches the production Material SVG glyphs against actual screenshots and
taps the matched coordinates through UI Automator. OCR reads rendered clocks
and menu text when necessary. It never opens the shadow root, patches the
extension, or invokes player functions. The entry threshold is calibrated on
the captured real frame with a negative control that removes the actual icon.
Image recognition still needs runtime validation and can fail when browser
rendering/layout changes; a low-confidence match fails instead of blind tapping.
The added host image/OCR dependencies are confined to the test runner.

An exit-info test regex was also corrected to match Android's real reason
format. Earlier raw exit-info/logcat artifacts remain evidence, but the previous
regex alone was not sufficient evidence of native-crash/ANR absence.

## Later actual API33/API35 confirmation and browser limits

Full run [40192612290](https://github.com/Owndot/video-assistent-helper/actions/runs/40192612290)
repeated the installed-APK security checks on both APIs, with separate fresh
Helper/Firefox state for each route. Native HLS saved audio/video of the
expected duration; credentialed cross-site redirect files matched the source
SHA256. SAF transfer, eight restarts, auth/Host rejection and real socket
collision passed. These native checks are independent of browser UI failures.

Real Kadri and real Violentmonkey/userscript installation and browser pairing
passed on both APIs. API33 demonstrated moving own-player video, pause/resume,
redirect/HLS playback and rotation through both routes. API35 handoff passed
for Kadri in run40194913791; its pause/resume glyph changed correctly but a
later rendered-clock OCR assertion failed. No full browser suite is green.

Captured subtitle_selection.png on API33 visibly contains ANDROID E2E SUBTITLE;
the full-frame OCR missed it. Several older Close/download touches used stale
short-lived control locations. The invalid uiautomator2 double_click interval
argument and these observation failures are harness defects, not established
player defects. Fresh-context HTTP traces exclude a stale pre-pair tab as the
explanation for the volume failures in run40192612290. Gesture integration
remains unresolved pending an Android native-volume positive control.

No claim is made for production update migration, physical battery consumption,
Wi-Fi/LAN performance, Vivaldi, process-death persistence of credentialed files,
or a stable-versus-development performance regression comparison.

See [VALIDATION-REPORT.md](VALIDATION-REPORT.md) for the completed full API33/35 comparison, exact per-route counts, actual 128MiB hash/PSS observations, and independently verified harness regressions. Full browser validation remains incomplete.
