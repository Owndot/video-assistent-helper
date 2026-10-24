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

Correction under test:
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
