# Four review corrections (Helper #16 / Kadri #5)

Starting points: Helper 5e52c3228b7907fbced062acf83df47492fe48df,
Kadri 2cc7e597b725de8db0356f5ae3ccc6d8fda8e931. No merge/release is implied.

## 1. Transfer extent survives the size probe

The reviewed readers accepted size=8 followed by Content-Range bytes 0-5/6,
including an unchanged ETag. The client retained only the validator. toFile()
did not check the exact requested byte count or its final file size.

The per-job client transfer record now keeps both total and validator, even
without an ETag. Following responses may not contradict a known total.
Fetcher.toFile() keeps its own expected total, asks for exact windows, checks
each window and the final size, and deletes any unsuccessful output. Proxy
requests carry that expected total internally (no new wire field); the existing
/push total is checked before publication. Http windows also compare exposed
representation totals; the last ignored-Range window checks for excess decoded
bytes when the full length is unknown. Missing totals do not erase the expected
size; exact valid windows remain accepted. Fully unknown transfers still stream.
Permanent integrity errors are not retried. Native MP4 resume is unchanged.

## 2. Cancellation owns its queue entries and handles

Each proxyLoop has a cancellation state. Pending gate entries are removed on
cancel, active GET/upload handles are aborted, and only acquired slots are
released (once). Rejected/missing upload acknowledgements stop the affected job.
The existing /dlstate endpoint is checked before a batch and every two seconds
only while work is active/queued, with one status request in flight. Terminal or
missing-job states cancel immediately when observed. One transient status timeout
does not kill a healthy transfer; three consecutive unavailable status checks do.
There is no idle polling beyond existing /pull. Other jobs retain their slots.
Late SDK callbacks are ignored; helper watchdogs, status waits and abort handlers
are removed on completion. An API may still delay its physical network abort;
no manager-internal memory or instantaneous remote-cancel guarantee is added.

## 3. Durable job identity before publication

The old process-local job counter restarted at 101 and each Proxy request counter
at 1. An authenticated old upload therefore matched a fresh job after restart.

JobIds.reserve() appends one signed-int reservation to an app-private files-dir
journal under an OS file lock. Both data and parent-directory metadata are synced
before returning an ID. Android explicitly uses android.system.Os.fsync on the
parent directory; the host backend uses FileChannel.force. Directory-sync failure
publishes no ID and consumes any record already written. A record contains the ID and its complement, checked
against its position; this is torn-write detection, not cryptography or server
authentication. Partial/inconsistent records, I/O errors and exhaustion fail
closed; no automatic journal reset or repair is performed. Reading/allocating is
constant-memory and reads only the last record. The journal grows eight bytes
per job. Synchronization handles threads; FileLock handles separate JVM/processes.
A crash after reservation may waste an ID, which is deliberately never reused.

New job IDs use -2 downwards, disjoint from the normal non-overflowed legacy
positive namespace. All existing consumers treat IDs as opaque signed ints;
endpoints, JSON types and pairing are unchanged. Request IDs remain positive and
local to a durable job; overflow now fails rather than wrapping. Query IDs outside
the signed-int range are rejected, avoiding numeric aliases. Existing Android
allowBackup=false is unchanged; the journal is not a cache and must not be deleted
or restored independently while keeping the pairing state. Privileged rollback
or replacement of private app state cannot be detected by this local counter.
This is not a new authentication mechanism. Android/OEM fsync/power-loss behavior
is not verified on real hardware here; host file persistence/locking is tested.

## 4. Production player caller regains priority

finalUrlGM() itself again sets kadriPurpose='player-resolve'. The actual Chrome
prelude forwards it; the actual background/header gate prioritizes it over queued
normal requests. It cannot bypass an active header owner or change credential,
referrer or DNR rules. Firefox has no equivalent global Chromium DNR gate; its
adapter remains compatible. Both repositories contain identical shared source.

## Reproducible evidence

* TestTransferIntegrity: reviewed code succeeds with six bytes for the eight-byte
  transfer. Correction rejects it, changed totals, short/long windows and removes
  partial files; exact multi-window, absent-total, unknown and zero-length cases
  remain valid. Native and Proxy production readers are used at -Xmx32m.
* TestJobIdentity --legacy: models the old Downloads counter reset, verified
  against the pinned source. Actual MiniServer/Proxy accept OLD bytes for the new
  job. Correction uses the production allocator; --http restarts the real host
  listener with the same synthetic pairing credential, rejects the stale upload
  and accepts NEW bytes. This simulates Android state reset; it is not a device
  process-kill test. Separate JVMs exercise file locks and immediate restart;
  tests also cover parallel reservations, torn records and overflow.
* proxy_review: actual gmGet/proxyLoop/helper functions, unchanged ETag shrink,
  missing validator/total, four-item batch, rejected upload/status cancellation,
  independent jobs, timeouts/errors and late callbacks. Controlled GM transport,
  not an installed userscript manager.
* player_priority_check: actual finalUrlGM, actual Chrome prelude and actual
  background source with deterministic DNR/fetch. The reviewed caller fails,
  the previous main caller succeeds, and the corrected caller succeeds. Tests
  never inject a priority option into a replacement caller.

New fast regressions run in normal PR/main jobs; historical failure proofs are
limited to the work branch. Existing low-heap proxy/native/framing/media/key
regressions are retained. No APK, production key or policy change is involved.
The complete-response GM/Base64 limits in PROXY-MEMORY.md remain. Issue #5 stays
open, and Firefox #9 is not part of these corrections.
