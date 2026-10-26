# Final two P2 corrections — Helper #16 / Kadri #5

## Full legacy HTTP 200 representation with unknown reported total

The reviewed Helper (5d5100c) accepted a size probe of 8 followed by a
legacy `/push` with `st=200`, `total=-1`, and ten bytes `0123456789`.
`toFile()` successfully saved only `01234567`. The new production-class
regression fails with that exact prefix on the reviewed source.

After spooling, `Proxy.pushStream()` now compares the actual file length of
a full HTTP-200 representation against the pinned transfer size as well as
any reported total. A mismatch publishes permanent HTTP 422 to the waiting
consumer; both spool and partial destination are removed, with no retry.
The existing size probe, pinned expectation, exact-window and final-file
checks remain intact. No new body array or protocol field is introduced.

This check excludes bodyless size probes. In the existing protocol `st=200`
carries the full representation; browser-normalized partial windows carry
`st=206`. Unpinned window fetches and unknown-size transfers remain valid.
Tests cover exact legacy 200, 206, bodyless 200 probes, unknown size, unpinned
windows, ignored Range across multiple windows, a growing second full
response, and independent parallel success/failure with cleanup.
`TestLegacyFullResponse --http` also uses the actual authenticated MiniServer
and HttpURLConnection transport; the default mode calls production Proxy
and toFile directly. Both run under `-Xmx32m` in the regular existing CI job.

## Initial job status is tri-state

The reviewed shared client treated a null first `/dlstate` result as a
confirmed end state. The new test executes actual `helper()`, its GM timeout
and error callbacks, and `proxyLoop()`: one initial timeout discarded a
valid pulled batch before any media request or upload.

The initial check now distinguishes active (`true`), terminal (`false`),
and temporarily unknown (`null`). Unknown status permits at most three
queries, separated by the existing cancellable two-second pause, matching
the watcher failure threshold. The already-pulled batch remains local;
no media slot is held during these retries. Confirmed termination stops
immediately. Healthy jobs still make only one initial status request.

The real-client tests cover timeout/error then success, immediate terminal
states, timeout then terminal, three failures with no further polling,
duplicate invocation, independent parallel jobs, exact GET/upload counts,
and complete slot/job/timer cleanup. A controlled clock replaces elapsed
waiting, not the production helper, queue, or cancellation functions.

## Scope and verification

The new current-source tests run unconditionally in the existing appropriate
PR/main jobs in both repositories. Historical failing-source demonstrations
remain limited to this PR branch. The shared `src/080-helper-app.js` is
byte-identical between repositories; the Helper userscript is regenerated
with the existing build tool. Existing bounded-memory, transfer integrity,
queue cancellation, key isolation and player-priority checks remain in place.
No Android runtime UI, pairing protocol, production policy, signing, or
release changes are included.

Real local HTTP transport is verified in CI because sandbox socket binding
is unavailable. Java heap limits are not a browser RSS guarantee: GM and
extension APIs can still buffer a complete response. Issue #5 remains open.
Physical Android/OEM behavior is not verified by these JVM/GM fixtures.
