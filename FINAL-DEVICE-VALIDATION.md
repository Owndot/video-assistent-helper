# Final Android device validation — no production signing or publication

Status vocabulary: PASS = executed with evidence; FAIL = reproducible mismatch; NOT TESTED = no actual execution. A prepared procedure is never a pass.
Record model, Android/API, browser/version, script/extension commit, APK SHA-256, signer SHA-256, display refresh rate, Wi-Fi/network and test date. Do not record pairing keys, cookies or account tokens.

## Safe setup
1. Use a spare device or Android test profile with test accounts. Do not uninstall the real helper before noting its settings and stopping downloads.
2. Use only test APK artifacts with de.evp.helfer.debug. They cannot update de.evp.helfer. Never replace the production signer locally just to install a test.
3. For an A/B source comparison use the stable/current debug APK artifacts produced by helper-performance.yml. Both use temporary SDK debug signing. Uninstall only de.evp.helfer.debug between clean-install trials. Copy exported non-secret settings elsewhere first.
4. Keep the existing production helper stopped during normal debug tests; otherwise both claim port 47811.
5. Test browser configurations separately: (A) Chromium/Vivaldi + Kadri, userscript disabled; (B) Chromium/Vivaldi + Violentmonkey/userscript, Kadri disabled; (C) Firefox + the supported Kadri or userscript route. Never activate both player implementations in one profile.
6. Prepare fixed authorized non-DRM fixtures: direct MP4/WebM, VOD HLS, subtitles SRT/VTT, an iframe player, a LAN media server, and a multi-GB downloadable file with a known SHA-256. Use test-only Cookie and Authorization values for network inspection.
7. Run the same fixture and network conditions for stable/current. Record unavailable browser/OS capabilities as NOT TESTED.

## Functional matrix
Run F01-F15 for every supported device/browser configuration. Record one row per configuration in DEVICE-RESULTS.csv.

| ID | Exact procedure | Expected behavior / evidence |
|---|---|---|
| F01 | Install debug APK; open app; grant required notification/overlay permissions; stop/start helper twice; return from another app. | Clear readiness state, no crash; authenticated helper works. Record launch-to-ready time and permission state. |
| F02 | Choose Connect, select the intended browser, return to video page, change volume by a swipe; restart browser and repeat. | Pairing succeeds only with the intended running helper; survives browser restart. No key in screenshots/logs. |
| F03 | Play fixed direct video; wait until about 20 s; open Kadri player; close it; repeat five times. | Correct source, sound and position; no restart at 0, visible black gap, doubled audio or orphan overlay. Record actual position and handoff video timestamps. |
| F04 | Repeat F03 for fixed HLS VOD; change quality/audio track if available; seek forward/back; wait for buffering to finish. | HLS continues, position remains consistent, segments succeed, no unexpected authentication on foreign hosts. |
| F05 | Repeat direct playback in same-origin and cross-origin iframe; reload iframe once and reopen player. | Correct source and fullscreen behavior. Record browser-specific iframe limitations, including older Firefox restrictions. |
| F06 | Load SRT and VTT; select page-provided subtitles; change delay/size; seek; close/reopen. | Correct text/timing and retained settings; no orphan subtitle nodes or duplicate tracks. |
| F07 | Enter PiP; go Home; lock/unlock; return to fullscreen; close PiP; test Back separately. | Expected play/pause policy, current position retained, no second audible player or stuck controls. Browser unsupported PiP is NOT TESTED, not a pass. |
| F08 | Play from LAN media server; perform two downloads while resolving/opening another video; cancel one download. | Local authorized source works, unrelated redirects remain blocked, other download completes with expected hash, player remains usable. |
| F09 | Record system volume/brightness; swipe both controls, switch Auto/manual, close player, then force-stop browser. | Correct system controls; brightness restored on normal close and watchdog path; no stuck dim overlay. Record actual restoration time. |
| F10 | Rotate portrait/landscape, toggle rotation lock, switch apps and return while playing. | Correct aspect ratio/orientation and position; no unintended player close or extra audio. |
| F11 | Set distinctive language/theme/autostart; export via system file picker. Change settings; import; restart app. Try invalid and oversized files. | Three preferences round-trip; invalid files fail without partial changes. File contains no pairing key/paired flag. Android permissions and downloads are unaffected. |
| F12 | With two package IDs installed (existing production and debug), start A then B; reverse order; retry after stopping the owner; explicitly re-pair intended helper. | Only endpoint owner is ready; other app explains conflict and does not open pairing URL. After switching, old browser key cannot control the new installation. |
| F13 | Stop downloads; export supported settings; uninstall debug helper only; install test new-key variant; import settings and pair again. | No inherited private key; settings import works; pairing and OS permissions require deliberate renewal. Existing old APK has no exporter: note its three settings manually; browser preferences remain separate. |
| F14 | Use an authorized source that requires mirrored playback; open/close five times, seek if supported, switch apps and return. | Correct mirrored image and one audio source; original player position/state preserved. If no suitable source is available, NOT TESTED. |
| F15 | If available, send playback to the supported external player/cast receiver; return to browser and reopen at the recorded position. | Correct URL/context and position; no unwanted second stream. Record receiver/app; unavailable hardware is NOT TESTED. |

For F12, do not install a random production-ID APK as a second helper. Use an already trusted installation on the spare device; otherwise mark that two-package test NOT TESTED.
Emulator fixture v3.1 tests prove mechanics with test identities only. A future approved production lineage still needs its own device upgrade test before distribution.


## Baseline performance protocol
1. Fix screen refresh/brightness, volume, battery-saver state, Wi-Fi AP, fixture hashes, network shaping and ambient temperature. Let both versions cool between trials. Keep Android/browser updates unchanged.
2. Counterbalance stable/current/current/stable order; collect at least three blocks. Separate cold install/profile runs from warm repeated-playback runs. Do not mix them in one median.
3. For first image, record the display with a camera/high-frame-rate recorder if available; measure from the play action to the first actually visible video frame. Browser requestVideoFrameCallback is supplementary compositor evidence, not physical display latency.
4. For handoff, use a visible timecode clip and record tap -> own-player visible frame and close -> page-visible frame. Note position before/after and audible gaps. Screen recording adds overhead: use the same method for both versions.
5. For URL resolution, repeat idle, two streaming downloads, slow headers and bandwidth-limited server cases. Record queue wait, request/response time and fallback/timeout separately; a fallback is not a successful fast resolution.
6. Collect Android PSS with adb shell dumpsys meminfo PACKAGE before playback, at 5/15/30 minutes, during large downloads and after 20/50 open/close cycles. Measure browser and helper separately. Report native/graphics/Java heap where available. RSS/PSS/JS heap are different quantities.
7. Capture CPU time over equal idle and playback windows. adb shell dumpsys cpuinfo and Android Studio profiler may help; record method/window and profiling overhead. Do not interpret an instant percentage as energy consumption.
8. For battery, use matched unplugged 30-minute sessions (or longer if resolution is inadequate), same starting charge range and temperature, stable network and screen settings. Capture adb shell dumpsys batterystats before/after. Never use emulator battery readings as real energy data. Background OS activity and thermal throttling must be recorded.
9. For large files, test the chosen multi-GB fixture, range/resume if supported, cancellation and a slow/stalled server. Check SHA-256, free storage and observed PSS trajectory. The existing browser Blob download path still buffers data; do not claim bounded memory from chunk transport alone.
10. Plot raw samples, median and spread first. Investigate repeatable differences beyond measurement noise. Set release criteria only after reviewing those baselines; no arbitrary fixed pass latency or battery number is defined here.

## Security observations
- Use only synthetic cookies/tokens on controlled servers. Verify server logs for same-site, cross-site redirect, parallel-context and LAN redirect cases. Do not upload real browsing headers.
- Kill/restart the actual extension worker during an active granted request; verify new public requests cannot reuse the old grant and normal requests recover.
- Cancel during queueing, header wait and body transfer. Confirm no further network request for canceled queued jobs, cleanup completes, other jobs continue.
- Test workflows must have read permission, no production secret references/signing and no release or result-branch write command. Historical-identity audit is a separate read-only verification operation, not a build.

## Exit gate
Physical device rows, battery and OEM/service/PiP behavior remain NOT TESTED until executed by the device owner.
Do not merge the two main security PRs, generate a production successor key, rotate the production signer or publish from this procedure.
