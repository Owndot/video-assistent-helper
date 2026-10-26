# Proposal only: end-to-end bounded browser transport

Not implemented or approved by this PR. Existing wire compatibility is preferred
for the current correction. Issue #5 remains open; Firefox #9 is not part of it.

Kadri can stream remote fetch responses in its background, but the existing
Base64 port bridge accumulates responses and encodes entire uploads. Avoiding
that requires a bounded internal chunk/ack bridge (including cancellation and
message lifetime), or direct background forwarding with a reviewed trust check.
It must preserve trusted storage key snapshots, per-hop credential/referrer
rules and document/job ownership. Do not give page-controlled code a general
privileged localhost forwarder.

A browser fetch streaming request body is not a drop-in replacement for the
current HTTP/1.1 fixed-length loopback endpoint. GM manager support for streaming
and upload body types also varies. Negotiate capabilities rather than silently
claiming streaming or introducing a small segment cap. Unknown size and ignored
Range must have a reviewed policy on managers which cannot stream at all.

If chunked multi-request /push is needed, separately review transfer IDs,
ordered offsets, quotas/admission, bounded in-flight chunks, acknowledgements,
expiry, abort/cleanup, final byte count and a standard SHA-256 digest. Bind
Content-Range and strong ETag/If-Range to one transfer. Finalization must never
publish a partial file. This is data integrity, not a new authentication or
custom cryptography design; existing accepted authentication risks remain.

Tests before approval: real Chromium/Vivaldi and Firefox bridge/manager behavior,
measured JS/native/browser RSS, unknown and adversarial framing, response that
ignores Range, multiple tabs/jobs, backpressure, disconnect/restart/abort,
changed entities, exact hashes and HLS/DASH/MP4 fixtures. Android/OEM lifecycle
coverage remains separate from host transport tests.
