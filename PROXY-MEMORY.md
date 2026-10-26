# Browser Proxy memory: compatible correction and remaining limits

Base: Helper f5040b02e3184a995462fbfcb1e6aaf3f3d54adb (reviewed PR #15 merge),
Kadri 186b25072223c3a25a62176ae4063e1bef3c6344. Issue #5 remains open.

## Actual data flow

| Component | Before | This change / limit |
|---|---|---|
| GM media GET | Complete ArrayBuffer, including ignored Range/size probes | Size probes abort at headers when available. Data still uses the complete GM response. |
| Kadri background/prelude | ReadableStream -> Base64 messages -> accumulated byte chunks -> contiguous ArrayBuffer | Abort/error clears accumulated chunks immediately. Complete-response bridge and Base64 copies remain. |
| Proxy loop | Four concurrent bodies per job, unlimited accumulation across jobs in the same document | Two fetch-through-upload operations per script document. Other tabs/documents have independent gates. |
| Helper POST /push | Allocate entire Content-Length (up to 256 MiB) before delivering to Proxy | Authenticated fixed-length input streams in 64 KiB chunks to private cache files. |
| Proxy Req/fetch | Entire media byte array; ignored-Range cut adds a copy | File-backed response; only bounded metadata/windows become byte arrays (4 MiB). |
| Proxy HLS/DASH/download | Default download materializes whole segment | Move completed spool to existing staging file, or copy a requested window with 64 KiB buffer. No 4 MiB media-segment cap. |
| Unknown-size proxy file | One unbounded fetch byte array | Existing download API now handles unknown size through the file-backed Proxy override. |

The old 256 MiB **per-upload transport limit** is retained; it is not newly reduced
for legitimate HLS/DASH segments. Disk space is not globally reserved. Parallel
uploads can consume considerable cache space; disk errors fail and clean up the
partial file. Native paths from PR #15 are unchanged.

## Integrity, cancellation and compatibility

The wire format stays /pull + fixed-length POST /push with the same job/id/st/
total fields, X-EVP-Key, origin/cookie rules and pairing. Production uses the new
streaming handler; small custom Java handlers have a bounded compatibility
adapter. Authentication and Host validation precede body processing. Inbound
Transfer-Encoding, conflicting/missing/invalid lengths are rejected. Duplicate
or expired request IDs cannot overwrite a delivered response.

The client validates exposed upstream framing, Content-Length where applicable,
Content-Range start/end/total and actual decoded body length. HTTP 200 despite
Range is sliced to the exact window and normalized to 206 for the existing wire
format. Invalid responses become terminal 422 errors instead of corrupt files.
Strong ETag (or Last-Modified) captured by a size probe is sent as If-Range and
checked on subsequent windows. A changed/missing validator fails the request.
Without a usable validator, the unchanged protocol cannot prove identity across
separate range requests. Last-Modified also has weaker granularity than strong
ETag. Encoded complete responses use decoded byte length; encoded byte ranges
are rejected to avoid ambiguous offsets.

Helper validates delivered window length/total and owns file cleanup. The old
wire format does not carry upstream Content-Range start or ETag, so old clients
retain their weaker upstream-metadata checks. New Helper works with old clients;
new clients work with old Helper, but old Helper still has the ingress OOM.
Use both reviewed changes together for their combined benefit. Shared source is
identical in the two PRs. No release/version/policy approval is implied.

Closing/cancelling a Proxy wakes pending waiters, rejects late pushes and cleans
owned files. Copy loops check cancellation between chunks. A blocked ingress
read can wait for the existing 20-second socket timeout. Each Proxy request now
has a ten-minute absolute deadline, including time queued behind the client gate.
The old 45-second /pull-silence heuristic is removed: the client deliberately
stops polling while it fetches/uploads, so silence does not prove page closure.
This avoids false failures for queued transfers but a closed browser can now take
up to the request deadline to be detected; explicit app cancellation wakes waiters
immediately. Network failures
retain at most three existing Proxy attempts; permanent 4xx errors are not
retried. Browser GET has its existing 60-second timeout plus an abort watchdog;
completed helper upload watchdogs are cleared promptly. No persistent resume
after Android process death is added.

## Measured tests and honest bounds

`TestProxyMemory` uses actual Proxy, HlsCore and DashCore with generated 64 MiB
bodies, known/unknown totals, ignored Range, valid/invalid 206, two parallel
transfers, metadata budgets, timeouts, late pushes, cancellation and bytehashes
at -Xmx32m. `TestProxyIngress` uses actual MiniServer and authenticated HTTP POST,
with a generated streaming sender, parallel uploads, auth/framing checks and
exact bytes at -Xmx32m. The predecessor MiniServer must fail this test with Java
heap OOM before the correction.

`proxy_memory.js` executes the actual gmGet/proxyLoop source with controlled GM
callbacks. It covers early size-probe abort, valid/malformed metadata, exact
windows, validators, timeout/abort and eight 64 MiB responses across two jobs.
Local measurement: two active bodies, about 128 MiB Node ArrayBuffer storage,
about 155 MiB process RSS. Node's 32 MiB V8 old-space flag does **not** bound
ArrayBuffers, browser memory or total process RSS. The old size-probe test must
fail. Existing media fixtures and key/privacy regressions are reused in CI.

This is not end-to-end bounded-memory browser streaming. A GM manager may buffer
internally before callbacks; a manager without early headers still buffers its
size probe. Kadri's bridge accumulates one complete response and Base64 upload
copies. A single huge response or several tabs can still exhaust browser RAM.
The Android Java heap is bounded per transfer; metadata parsers and concurrency
still add memory. Host tests do not verify Android/OEM cache eviction, process
lifecycle or real Samsung memory behavior. Actual manager/browser RSS is not
measured by the callback fixture. Issue #5's complete criteria remain unmet.

See PROXY-STREAMING-PROPOSAL.md for the unimplemented larger transport work.
