# Final technical validation — Helper / Userscript — 2026-10-24

**Ready for the owner's real-device validation; NOT approved for final merge, production signing or publication.**
Both main security PRs remain drafts. The owner device is Samsung S26 Ultra, reported Android 17.0, with Vivaldi and Firefox. Exact API/One UI/browser versions and all physical results remain unverified.

## Final post-fix evidence — 21:58 UTC
**There is no all-browser merge approval.** Distinguish completed full runs, focused corrections and automatic reruns.

- Kadri's corrected native suite: **19 files / 201 checks pass**, [40134238016](https://github.com/Owndot/Kadri/actions/runs/40134238016).
- Completed helper browser run [40134239672](https://github.com/Owndot/video-assistent-helper/actions/runs/40134239672), source eab229c1: **Chromium 57/58 files pass**, one DVR fixture assertion fails; **Firefox 50/58 pass**, the same eight baseline failures remain.
- The Chromium DVR fixture directly injected the visible CSS class, bypassing showControls(), which normally immediately paints the timeline. src/160-player-anzeige.js deliberately avoids updating hidden time labels to save CPU. The fixture now waits for a real native timeupdate after revealing controls; all label/progress assertions remain unchanged. A trusted surface-click attempt changed native fullscreen geometry and was superseded; its failed run is not passing evidence.
- Focused corrected DVR retest [40136368630](https://github.com/Owndot/video-assistent-helper/actions/runs/40136368630), source 1c0ae377: **Chromium 6/6 pass**. Firefox still fails its pre-existing live-zoom gesture assertion (5/6); do not call that workflow green.
- Deterministic old/current player regression plus handoff/quality controls: **PASS**, [40134814582](https://github.com/Owndot/video-assistent-helper/actions/runs/40134814582). Current metadata test 3/3, seamless handoff 13/13, player/quality controls 40/40, live/DVR 6/6. The explicit new-source position assertion fails on stable source and passes corrected source.
- The older composite scrub test can pass even with the defect if the initial bridge already ended. It is retained unchanged; regression discrimination now uses a deliberately fixed opening position and native metadata event, not that timing coincidence.
- Runtime correction: helper/userscript 7af974c0 and Kadri 3145e864, src/170-player-laden.js. The opening seek is consumed once per intentional tryNext load; quality/fallback loads re-arm it. Native traces proved the old code explicitly set an unrelated source to the stale position (10 seconds).
- Post-fix performance repeat [40133859838](https://github.com/Owndot/Kadri/actions/runs/40133859838): own-player transition median 1038.1 / 1038.9 ms stable/current; playing CPU 0.475 / 0.470 seconds per approximately 3 seconds. No clear deterioration in this small desktop repeat; the roughly 756 ms wait behind deliberately 750 ms active headers remains.
- A full automatic browser rerun after the final **test-only** DVR synchronization change is still running (40136368628). It is NOT counted as passed. Runtime source is unchanged from the completed full run; the only subsequent functional check change is the separately retested fixture.
- Physical Samsung, energy, multi-GB bounded memory and approved production migration remain NOT TESTED.

## Executed checklist
PASS is evidence of actual execution, never merely a prepared procedure. Host tests ran in GitHub Actions because the shared execution workspace was unavailable.

| Status | Check | Evidence / limits |
|---|---|---|
| PASS | Main containment | PR #2 merged at a7d031e; current main has no keystore/passwords; current build workflow is debug-only, contents:read and cannot publish |
| PASS | Public historical-key / published-APK certificate match | [40117549391](https://github.com/Owndot/video-assistent-helper/actions/runs/40117549391); certificate export + apksigner, no signing |
| PASS | Host/JVM/Gradle/security and debug provenance | [40131433569](https://github.com/Owndot/video-assistent-helper/actions/runs/40131433569), source 8561f7e7d2c5606d9593cae1ea67926d4e4fa2f1; 10 Python cases, JS scope regression, port/cookie/redirect/settings tests, media/ffmpeg core checks, debug APK, disposable-key release verification |
| PASS | Compromised signer rejection | same run: verifier rejects the public compromised fingerprint using only a disposable keystore; no exposed key used for signing |
| PASS | Android-native Cookie and HTTP300 checks | [40131293057](https://github.com/Owndot/video-assistent-helper/actions/runs/40131293057), source acbfc5c8; actual API33 HttpURLConnection through app_process, test APK classes, shell UID |
| PASS | Disposable real-APK update mechanics | [40130766838](https://github.com/Owndot/video-assistent-helper/actions/runs/40130766838): emulator APIs 26,28,31,32,33,35; all six pass |
| FAIL (completed full run) | Chromium browser suite: 57/58 files pass | 40134239672, eab229c1; remaining DVR fixture corrected/retested separately as described above |
| FAIL | Firefox browser suite: 50/58 files pass | 40134239672; same eight baseline files; actual engine with synthetic manager/mobile/fullscreen harness |
| FAIL / baseline evidence | Same eight failures on stable player source | [40130166285](https://github.com/Owndot/video-assistent-helper/actions/runs/40130166285); stable b05fd2de vs pre-cookie-fix 6111df1a script hashes are identical; eight selected tests remain red |
| PASS | Source/performance observations | helper run 40130072486; userscript run 40130307638; raw data committed under validation-results |
| NOT TESTED | Android17/API37 emulator | SDK/platform or required x86_64 image unavailable; platform-discovery job records explicit warning |
| NOT TESTED | Physical S26 / Vivaldi / Firefox functionality, battery and OEM behavior | FINAL-DEVICE-VALIDATION.md, S26-ANDROID17-TEST.md, DEVICE-RESULTS.csv |
| NOT TESTED | Actual approved production-successor migration | No successor key or production lineage exists; fixture APK results do not authorize rotation |
| NOT TESTED | Independently retained installed/offline production APK | Current public release asset is verified; owner copy is a separate provenance gate |

The completed 58-file run includes the HTTP300 and player-position runtime corrections. The latest test-only DVR synchronization has separate native-engine results; its broad automatic rerun remains unfinished. Earlier 57/57 Chromium runs were not reliable evidence for the stale-position case; the added deterministic test now discriminates stable and corrected source.

## Proven defects corrected during validation

### High: Android cookies crossed tenant and redirect boundaries
app/src/main/java/de/evp/helfer/Fetcher.java previously grouped alice.github.io and bob.github.io as one site. Source and native probes reproduced the leak. PublicSuffix.java now uses the same pinned official 10,397-rule PSL as Kadri; packaged ASCII data hash:
`0c203a51804ede904082d7f106322de9ec635431d3c0d982b3dea3c842106436`.
Lookup initialization is lazy. Wildcards/exceptions are tested; missing/corrupt data falls back to exact-host behavior. Unnormalized Unicode does not become a credential-sharing alias.

The old Android API33 automatic redirect implementation forwarded the manually assigned Cookie from 127.0.0.1 to localhost. The host JDK did not reproduce that behavior. Explicit HTTP(S) hop handling now re-evaluates cookie scope, suppresses credentials after HTTPS downgrade, preserves Range and bounds redirect loops. Native old/current results:
- foreign-tenant Cookie allowed: true -> false;
- Cookie present after cross-host redirect: true -> false;
- same-site Cookie and Range preserved: true -> true.

Native probes use disposable APK classes in app_process, not the real app lifecycle or Samsung network-policy environment. TLS downgrade/end-to-end unusual redirect chains still need their own device/server cases.

### High: Userscript allowed unrelated tenants to use browser-session permissions
src/070-extern-ytdlnis.js grouped alice.eu.org and bob.eu.org as eu.org. This affected proxy allow decisions, direct download/probe anonymity, subtitle requests and explicit page-cookie handoff.
src/065-public-suffix.js now embeds the pinned official data and builds the matcher only on first lookup. No network request or dependency was added.
tests/cookie_sites.js executes actual source and proxy-loop contracts; the old source fails the tenant assertion and current source passes. This unit/contract test is not a real Violentmonkey/Tampermonkey isolation test.

### Medium: Rapid port reuse raced shutdown
MiniServer.java now closes and joins the listener accept thread (bounded 250 ms) before restart can claim readiness. The test performs 100 immediate restarts.
[40119550495](https://github.com/Owndot/video-assistent-helper/actions/runs/40119550495) reproduced the old failure at cycle 28 and passed current code. The old timing race need not reproduce on every runner. Native Samsung two-app ownership/pairing behavior remains a physical test.

### Medium: HTTP300 compatibility gap caught in the new manual redirect handler
The first cookie-safe handler omitted status 300 with Location. It now preserves the Android/JDK-supported behavior.
The expanded JVM test fails that predecessor with “HTTP 300 redirect failed” and passes the correction. Android-native baseline/current both follow 300 successfully. Current tests cover 300/301/302/303/307/308, same-site authentication, Range, loops and non-HTTP target rejection.
This was a compatibility omission during this validation round, not evidence that the stable app lacked HTTP300 support.

### Medium: Later source metadata reapplied an old opening position
Both stable and development player source could seek a new source to a stale position when loadedmetadata fired again. Native traces recorded the setter and stack, not just a delayed assertion. src/170-player-laden.js now tracks whether an intentional load still owns an initial seek; the flag is consumed after metadata handling and re-armed by tryNext for legitimate quality/fallback loads.
The native event test in both repositories checks the first position and then inspects the next source directly at metadata delivery. The old source fails the controlled case, current source passes. Handoff and quality/control regressions also pass. This is a targeted correctness change, not a new player architecture.

### Build/test safeguards
app/build.gradle blocks the independently verified exposed certificate for release validation. Local/CI tests use disposable keys; no production successor was generated.
The test APK artifact includes file hash, source commit and public signing-verification output; aapt confirms de.evp.helfer.debug.
The Android emulator action executes multi-line loops through one shell script. The Firefox baseline pipeline now preserves a failed runner exit code through tee; the earlier green baseline workflow did contain failed tests and is not passing test evidence.

## Firefox failures: do not hide or waive them
Failing files: test_dvr.py, test_framekey.py, test_mirrorprev.py, test_prevretry.py, test_scrubbar.py, test_scrubfuzz.py, test_seamless.py, test_swipeseek.py.

All eight also fail with the unchanged stable player. There is no demonstrated new player regression from the security boundary change. That does not establish correct Firefox mobile behavior.
pwshim.py forces the Firefox-mobile mode and sometimes simulates fullscreen; several Chromium-oriented assertions expect canvas/bridge preview behavior that differs in that mode. Other gesture/timing failures still need classification against real Firefox on the device. No tests were disabled or weakened to manufacture a green suite. Native Firefox-extension permissions are a separate gate from these injected-userscript tests.

## Performance baseline and limits

### Helper source comparison
Run 40130072486, API33 emulator, 3 samples/version, counterbalanced order. The stable APK is rebuilt from pinned stable app sources with the current safe debug configuration; historical signing configuration/keystore is not fetched.
Median stable/current:
- authenticated ready: 664.7 / 622.3 ms;
- process PSS after idle + RPC: 32.0625 / 32.1055 MiB;
- 100 authenticated /ping calls: 229.2 / 208.8 ms;
- 10-second idle CPU ticks: 6 / 6;
- RPC burst CPU ticks: 12 / 12.
These are host/emulator observations with noise, not an improvement guarantee, video benchmark or energy measurement. Idle /ping does not initialize the lazy download PSL, so it does not measure that initialization's Android memory cost.

### Userscript PSL cost
Run 40130307638, Chromium 141.0.7390.37, six fresh samples/version, stable/current/current/stable blocks.
Median stable/current:
- bootstrap body execution: 1.3 / 1.4 ms;
- first compositor frame from navigation: 127.0 / 126.6 ms;
- first site lookup: 0.25 / 2.25 ms;
- retained JS heap after lookup: 6,344 / 388,340 B (about 373 KiB additional).
This uses synthetic manager/shadow hooks and a direct fixture. Bootstrap timing excludes parsing; the compositor metric includes page startup but is not a physical-display measurement. The small run shows no apparent first-frame delay; it cannot certify phone battery or HLS/many-frame workloads.

### Kadri under load
See the Kadri FINAL-VALIDATION-REPORT.md. Six samples/version show approximately unchanged own-player transition but a clear wait behind deliberately slow active response headers: 2.4 -> 755.6 ms in the 750 ms fixture. Priority affects queued work and never bypasses an active owner or unfinished cleanup. Longer waits can reach the existing four-second fallback. That visible behavior must be checked on the real phone.

### Known download-memory limitation
Fetcher.readAll() still reads into an unbounded ByteArrayOutputStream; its hint is not an upper bound. Unknown-size transfers and servers ignoring Range can buffer full files. Browser Blob/ArrayBuffer paths also retain full bodies. Two modest parallel transfers do not prove multi-GB bounded memory or correct arbitrary Range recovery.
No speculative streaming architecture was added in this final validation. Large-file hash, PSS trajectory, resume/cancellation and slow-server checks remain open; do not claim the original bounded-memory download objective is complete.

## Signing / provenance status
Current main containment is complete; the repository is still PUBLIC and historical copies remain compromised.
Verified public certificate SHA-256:
`ec17fab1a92079911ea50e6d308f8c70ad79efc3bb48e0231f2559b26b7e42e5`.
Pinned published APK file SHA-256:
`bdbb2dd853ffcba80afb27492a31baf474ae1e3fd36418e3f7a5206abb760086`.
Do not confuse these hashes or infer independent distribution trust from a repository asset alone.

The owner chose security over seamless legacy updates. Preferred proposal: approved v3.1/minSdk33 modern path; fresh-key clean installation with settings migration for API26-32. Android9-12 rotation is not assumed safe or promised. Export/import preserves only three allowlisted helper preferences; it excludes pairing secrets, which must be renewed. The already published old app has no exporter, so those settings must be noted manually. Production signer-triggered pairing reset, final lineage/signature verification and real approved-artifact updates are future work.

The fixture migration tests prove mechanics with unrelated test keys only, including rejection of higher-version predecessor-key APKs after modern rotation/fresh-key install. They do not prove a future production lineage or OEM/permission/foreground-service behavior.

## Files and reviewable commits
- f5e40a4: MiniServer.java and TestPortConflict rapid-restart correction.
- 6bc170c: bring main containment into the PR branch, without merging the PR into main.
- b647432: Fetcher.java, PublicSuffix.java, pinned resources/license and real HTTP/PSL tests.
- f5a618d: prior/current credential regression and APK resource verification.
- f788641 / ae1ab75: safe selective source baseline and native Android probe.
- 24169b9: one-shell emulator test runner and Firefox pipeline failure preservation.
- 516379b: userscript PSL source/build, license and actual-source proxy credential contract.
- 4792850: counterbalanced userscript startup/heap measurement.
- aa94da5: compromised-signature deny check and disposable-key negative test.
- 9496866 / e978e4f: HTTP300 compatibility and independent old-client regression.
- acbfc5c: native Android HTTP300 proof.
- 8561f7e: verified debug APK provenance artifact.
- 7af974c / matching Kadri 3145e86: per-load metadata seek ownership.
- 26de811 / 0442866 / eab229c: native event regression, lazy-discovery preconditions and live readiness observations.
- 7311016: deterministic stable/current seek proof independent of bridge timing.
- 1c0ae37: DVR fixture synchronization on actual media events; preserves production UI laziness and all assertions.
- Documents/result data: FINAL-DEVICE-VALIDATION.md, S26-ANDROID17-TEST.md, DEVICE-RESULTS.csv, VALIDATION-SAFETY.md, SIGNING-ROTATION-DECISION.md, SECURITY-CORRECTIONS.md, validation-results/*.

Earlier failed harness/build iterations are superseded and not counted as successful tests.

## Device handoff / merge gate
Start with S26-ANDROID17-TEST.md and fill DEVICE-RESULTS.csv. It has 84 physical rows across three routes, including functional, performance, network and migration/provenance cases; all remain NOT TESTED until actual execution. Unsupported browser routes must be marked unavailable, never inferred to pass.

Before a final merge recommendation: resolve/classify the Firefox failures, run available physical routes, evaluate slow-header fallback/handoff, inspect large-download memory and actual energy data, and independently compare an installed/offline production APK. Before any production distribution: separately approve and test the actual signer/lineage, permissions/settings migration and new pairing. No main security-PR merge, key generation, rotation or publication occurred.
