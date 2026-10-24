# Required production update validation (not yet executed)

Host preflight (Android SDK tools on PATH):
`python3 tools/check-update.py trusted-old.apk app/build/outputs/apk/release/app-release.apk --expected-cert PUBLIC_CERT_SHA256`

This verifies both APK signatures with apksigner, the production package ID and an increasing versionCode. It cannot prove preservation of runtime state.

On each supported Android version / representative device:
1. Install the previously distributed production APK and pair the intended browser. Select distinctive language/theme/autostart settings. Record them without recording keys.
2. Exercise playback, HLS, subtitles, PiP, casting, media-server playback, brightness/volume and downloads.
3. Install the newly verified production APK with `adb install -r new.apk`. Do not uninstall, clear data or use downgrade flags.
4. Confirm the same package was updated, settings persist and the already-paired browser still authenticates without re-pairing.
5. Repeat player handoff and functionality checks, including interrupted and parallel downloads.
6. Start debug and production helpers in both orders. Only the app that owns the endpoint may report itself ready; the other must show a port conflict and must not open a pairing URL.
7. Stop the endpoint owner, retry the other app and explicitly pair it. Browser endpoint remains port 47811; switching installations requires pairing the intended helper.

Record device, Android version, old/new APK SHA-256, public signing-certificate SHA-256 and results. Current status: **UNTESTED**. No device-test pass is claimed.
