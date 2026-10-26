# Firefox Issue #9: targeted test triage

Source baseline: Helper d97404bb (approved Browser Proxy merge); Kadri
62f7c6ea. Historical comparison: run 40130166285, stable b05fd2de,
Playwright 1.64.1 / Firefox 157. Current reproduction: all eight unchanged Firefox files fail in run
40415049845 (job 121895131088), while all eight Chromium files pass
(job 121895131536). The earlier correction run 40416140742 passed all
eight Chromium and six Firefox files; the remaining failures were the
fractional-target comparison (`0:41`) and the fixed-lifetime poster
expectation (`[False, False]`).

This change corrects browser test contracts. Player code, generated userscript,
Firefox extension adapters and Helper Android sources are unchanged.

| Test | Cause and corrected assertion |
| --- | --- |
| dvr | A hard-coded 90px downward mouse move can leave the viewport. Constrain fixture input to actual viewport coordinates; retain the same live/seek/zoom assertions. |
| framekey | The observer counted keyless bootstrap `hello` as an authentication key. Inspect nonempty keys separately, assert keyless messages are only bootstrap, reject a wrong key and cross-origin replay before allowing authenticated fullscreen. Raw manager base key must never be sent; absent ancestorOrigins still fails closed. |
| mirrorprev | Exact string equality at 70% ignores native pointer coordinate rounding and integer-second flooring (41 versus 42). Use the pixel-derived interval `[floor(target - duration/trackWidth), floor(target + duration/trackWidth)]`, not a tolerance around the fractional target. Time-only/no-fetch/no-flicker assertions remain; an explicit check rejects ±2s errors. |
| prevretry | Firefox can reuse a complete media response, so the planned next origin request never happens (`hit=0`). A unique HTTP-500 URL on the actual preview video guarantees a real media error; assert exactly one error, one reload of the known source, and recovered preview. No production handler is replaced. |
| scrubbar | Lost-capture injection used pointerId=1 rather than the actual browser pointer ID. Record the trusted pointerdown; an unrelated ID must not cancel, the real ID must cancel and restore position. Near-zero negative subpixel deltas may display −0s. Magnifier labels include integer-second and one-CSS-pixel rounding. SVG backdrop refraction is Chromium-only; Firefox must retain the existing CSS blur fallback. |
| scrubfuzz | Random coordinates can leave the automation viewport; hard-coded lost pointer IDs are invalid there too. Keep gestures within physical viewport bounds (still outside the bar and at range endpoints), inject explicit loss with the observed ID, retain the seeded 90-gesture cleanup/position checks. Log native out-of-viewport event delivery separately. |
| seamless | Firefox deliberately excludes the capture bridge because capture can divert source audio. Verify poster/own playback, position restoration, settled playback, mute ownership and close recovery. The forced mobile path uses a video for ambient, only when the safe capture API can feed it; no immediate canvas/bridge promise. A poster has no 150ms minimum lifetime: accept a visible poster or a visible, decoded, non-seeking video after native `playing`, at the saved position plus elapsed playback. Reject empty/hidden output and wrong positions; verify poster/spinner cleanup after readiness. Chromium keeps its original seamless bridge assertions. |
| swipeseek | The mobile Firefox path deliberately omits the canvas overlay. Assert actual video seek, pause/resume, displayed deltas and absence of overlay there; keep the original canvas geometry assertion for Chromium. |

## Evidence and limits

The existing browser runner remains strict: failed assertions, crashes and
missing ERGEBNIS lines fail CI. PRs first run only mirrorprev/seamless in Firefox; only a successful
precheck enables the existing two-browser job. Eight affected files then
run on PRs with Chromium
1.56.0 and Firefox 1.64.1; main retains the existing browser suite. Browser
binaries are cached; PRs skip the unrelated performance measurement. The
known hanging Ubuntu mirror is replaced by the official Ubuntu mirror, with
bounded installer network waits. The source validation workflow excludes APK
and unrelated native-media jobs for this test-only branch.

Firefox here is the desktop Playwright engine with the existing test shim:
mobile path forced, selected autoplay/fullscreen preferences and synthetic
manager storage. It is not a real Firefox Android or installed userscript
manager verification. Native OS gestures, mobile media/canvas limits, Android
fullscreen and OEM behavior remain unverified on physical hardware. Tests do
not establish that every browser/manager exposes ancestorOrigins; where it is
missing the existing secure iframe-local fallback remains unchanged.

The final two corrections require the staged CI confirmation before review.
No additional browser downloads or external log archives are needed locally;
the CI prints the exact precheck logs using the Python standard library.
A ready video is not accepted merely because the poster disappeared: frame
dimensions, visibility, native playing, non-seeking state and saved position
are all checked. The existing 150ms observation is retained without a longer
sleep; subsequent cleanup is synchronized to native media readiness.
No new product bug has yet been established by these eight discrepancies;
no player architecture or authentication change is justified by current evidence.
Issue #5 remains open and out of scope; production policy remains UNAPPROVED.
