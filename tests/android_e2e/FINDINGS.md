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

Validation of the corrected APK is pending the next controlled emulator run.
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
