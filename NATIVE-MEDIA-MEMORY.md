# Helper #5: bounded native media transfers

This is a focused native-path correction based on main
`b42f213d22e5caef63efc3768958923c07077ea3`. Issue #5 remains open for the
browser-proxy paths described below. No protocol, player, signing policy or
Android UI changes are included.

## Reproduction and cause

`Fetcher.readAll(input, hint)` treated the hint as initial capacity, then grew a
byte array until EOF. `Http.fetch()` read the entire response before applying
`cut()`. A server ignoring a 4 MiB Range request could therefore cause a complete
64 MiB response to be buffered. HLS segments and DASH single-file tracks also
used this byte-array API. AES decryption allocated another complete segment.

`TestMemoryBaseline` runs that exact main implementation with synthetic 64 MiB
input and `-Xmx32m`, and requires the original OutOfMemoryError. It does not use
network data, real media URLs or production secrets.

### Review correction: contradictory response framing

Reviewed head `8074aff7f535f9faa738dd97a973fc46ea8de8d1` stopped a complete
HTTP 200 body after the declared Content-Length. With `Transfer-Encoding:
chunked`, `Content-Length: 4` and ten decoded bytes, it returned/saved `0123`
successfully. The platform decoded chunk framing while the application trusted
the contradictory length metadata.

`copyResponse()` now inspects both single-header access and the case-insensitive
header map before copying. Transfer-Encoding plus Content-Length, differing
Content-Length values, malformed lengths and unsupported transfer codings are
permanent ResponseErrors. Chunked without Content-Length remains supported.
For complete known-length 200/206 responses, an extra decoded byte also causes
a permanent error; intentionally sliced HTTP-200 Range windows still stop early.
Neither failure returns a byte array or a successful file; `Http.download()`
removes its temporary output and does not retry these errors.

`TestResponseFraming` uses a raw HTTP/1.1 socket fixture (a framework might
normalize conflicting headers). It explicitly checks what HttpURLConnection
exposes: both header APIs retain TE=chunked and CL=4, getContentLengthLong reports
4, but the decoded stream returns `0123456789`. The same production test must
fail against the reviewed head, showing both truncated outputs, and pass against
the correction. The simulated fixture additionally verifies the final-byte guard
when a transport exposes CL=4 without TE but still supplies ten decoded bytes.

These checks validate headers and bytes exposed by HttpURLConnection, not an
independent raw-wire parser in the app. CI verifies the OpenJDK provider. Android
and OEM provider behavior is not runtime-verified here. If a provider or upstream
intermediary hides contradictory framing and also truncates the stream, this API
cannot recover the original wire evidence; no universal raw-wire detection claim
is made. No new HTTP stack or protocol is introduced.

## Correction and compatibility

* Native HLS/DASH media goes through `Http.download()` to existing private staging
  files using a fixed 64 KiB copy buffer. Segment size is not capped at 4 MiB.
  Each worker writes synchronously; slow disk writes provide backpressure.
* Native byte-array responses (playlists, keys, requested windows) have a 4 MiB
  maximum, including unknown Content-Length. Known oversized responses fail
  before reading their body. A per-response bound is not a whole-process RAM
  guarantee; parsers, concurrent metadata requests and platform buffers add RAM.
* An ignored Range (HTTP 200) is skipped and sliced while reading. The connection
  is disconnected once the required window is obtained; the remaining body is
  not deliberately drained. Large offsets can still cost bandwidth and time.
* HTTP 206 requires matching Content-Range, consistent Content-Length and the
  requested window (or a verified shorter final range). Overflow, unexpected
  content encoding and surplus bodies fail. Truncated transfers have at most
  four attempts, restarting the segment without appending. Permanent response
  errors and 401/403/404/410 are not retried.
* AES-128 uses the existing JCE AES/CBC/PKCS5Padding and IV derivation, now with
  chunked update/doFinal. Padding errors propagate. Encrypted staging and failed
  plaintext files are removed. Encrypted downloads temporarily require both
  ciphertext and plaintext disk space; there is no new disk quota.
* Cancellation is checked between network/disk/decryption chunks. Native
  connections and streams close on failure; incomplete segment files are
  deleted. Connect/read timeouts remain 15/30 seconds. A blocked read can wait
  for its existing timeout; cancellation is not an instantaneous socket abort.
* Existing redirects, per-hop cookie/referrer rules and signed media URLs remain
  in use. The MP4 `streamToFile()` validator/resume implementation is unchanged.
* `Fetcher.download()` defaults to the previous byte-array fetch for custom and
  browser-proxy implementations. Existing `/pull` and `/push`, authentication and
  browser-manager compatibility are preserved.

## Remaining work and limits

**Browser Proxy is not yet bounded end to end.** GM/extension responses, Proxy
byte arrays, `Fetcher.toFile()` for unknown size, and MiniServer `/push` body
buffering retain their existing behavior. A large ignored Range can still
exhaust memory there. This PR must not close #5 or be described as fixing every
download path. A compatible browser transport/ingress design needs separate
analysis before changing the protocol; no silent small segment limit or unsafe
fallback is introduced here.

Process death, persistent resumption and OEM cleanup/background behavior are not
changed. Download lifecycle remains tracked separately in #6. Samsung/OEM and
Android-runtime memory behavior are **not verified on real hardware** by these
host tests. Disk exhaustion propagates as an I/O failure; no persistent recovery
after process death is promised.

## Focused verification

```
tests/java/build.sh
java -Xmx32m -cp tests/java/classes de.evp.helfer.TestMemoryWindows
java -Xmx32m -cp tests/java/classes de.evp.helfer.TestMemoryHttp
java -Xmx32m -cp tests/java/classes de.evp.helfer.TestResponseFraming --simulated
java -Xmx32m -cp tests/java/classes de.evp.helfer.TestResponseFraming
tests/java/run_core_tests.sh
javac -cp tests/java/classes -d tests/java/classes tests/android_e2e/TestCredentialedStream.java
java -Xmx32m -cp tests/java/classes de.evp.helfer.TestCredentialedStream
```

`TestMemoryWindows` exercises the real production reader and cores with a
controlled HttpURLConnection: 64 MiB known/unknown bodies, exact ignored-Range
windows without draining, strict/malformed ranges and lengths, overflow,
truncation/retries, cancellation, cleanup, two parallel transfers, HLS/AES/DASH
bytehashes, and failed AES finalization at a 32 MiB heap.

`TestMemoryHttp` repeats transport cases on a real loopback HTTP server with
known/chunked 64 MiB bodies, ranges, redirects, synthetic cookies, an unchanged
signed query, timeout, truncation and 403. Existing HTTP/HTTPS privacy tests and
the credentialed MP4 resume suite cover unchanged authentication/redirect paths.
The existing media fixtures exercise HLS AES with explicit/sequence IV, fMP4,
byte ranges, DASH, MP4 and Proxy compatibility; ffmpeg fully decodes the outputs.

The memory/framing tests run on normal PRs and main. Only the historical OOM and
reviewed-framing failure proofs and the reused broader media compatibility run
are limited to this work branch.
This branch skips APK, browser and emulator jobs. All fixtures and keys are
synthetic; CI has contents:read only and creates no APK or release.
