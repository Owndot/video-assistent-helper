# Download lifecycle (Helper issue #6)

Scope: safe completion/cancellation and disposal of interrupted private downloads. This is not automatic persistent download recovery. No browser protocol, authentication, media player, signing policy or APK change is part of this work.

## Responsibilities and guarantees

| Path | Network/status owner | Temporary data / publication | Resume after interruption |
| --- | --- | --- | --- |
| Uncredentialed native file | Android DownloadManager (DM) | Android owns its destination and notification. The Helper watches for up to 25 seconds; that in-memory status is not a durable DM history. | Android manages it, independently of the Helper service. Helper does not reconstruct its watcher after process death. |
| Cookie/referrer-bearing native file | `Fetcher.Http.streamToFile`, `Downloads` task, foreground Helper service | Fixed 64 KiB network buffer; private `cache/dl<ID>` work; then controlled publication | At most four attempts in the same worker. A trusted validator and matching 206 range permit appending. A changed validator or an ignored Range/200 response causes a fresh replacement, never mixed bytes. |
| Browser proxy file | Browser/GM fetches, `/pull` leases, file-backed authenticated `/push`, `Downloads` task | Existing bounded windows and file spools, then the same publication path | No durable browser/job recovery. Explicit cancellation closes that job's proxy; late uploads are rejected. Closing a tab alone need not notify the Helper: its existing finite request lease (10 minutes) bounds this wait. |
| HLS / DASH | Existing cores and bounded workers; native or proxy transport | Private parts and mux result; same publication path | Existing bounded per-part retries only while the job lives. No crash-resume journal. Android mux execution is not covered by the new host models. |

A task goes from `startet` through progress (and optionally `zusammensetzen`) to one of `fertig`, `abgebrochen`, or `Fehler: …`. Only successful file publication commits `fertig`. Cancellation and the final commit are serialized per task: cancellation before commit prevents publication; cancellation after a committed file is a no-op. Errors displaying a notification cannot retroactively invalidate a successfully committed file. Other tasks have independent cancellation, streams and output paths.

Service shutdown stops accepting new transfers, cancels queued/running Helper-owned tasks, interrupts their workers and closes their proxies. Android DM jobs remain OS-owned. No global network or playback cancellation is introduced. Service shutdown is cooperative; a blocked `HttpURLConnection` read is not guaranteed to unblock on `Thread.interrupt()`. The existing 15-second connect and 30-second read timeouts remain the native bounds per network operation. HLS mux and provider I/O can also delay cooperative cleanup; no immediate Android/OEM timing guarantee is claimed.

## Proven defects and corrections

The exact baseline is merge `044784068b4b74621f0b45dbbf203780d17ebefe`.

* An output-stream error left an inserted MediaStore `IS_PENDING=1` row. Write, flush/close, null-stream and zero-row publication failures now roll the row back. The pending flag is cleared only after the whole copy closes successfully and cancellation is checked. A provider failure preventing deletion is still an error and cannot be guaranteed away by Java cleanup.
* Cancelling during the save copy could still publish the file and report `fertig`. Task cancellation is checked through copying and immediately before publication, serialized with the commit. Both pending and private work are discarded on ordinary failure/cancellation.
* On API 26–28 saving directly into an existing same-name destination overwrote it and exposed partial bytes. A same-directory `.evp-pending-*.part` file is now copied, closed and synced before a no-replacement move; colliding names get a suffix. Failed staging is removed. The directory belongs to the app; no claim is made about arbitrary external writers racing on an unreliable filesystem or power-loss atomicity.
* The native credentialed reader could treat the declared length reached before a read failure as successful completion on the next attempt. It now requires a successfully validated end/close, retries from zero if a failed attempt already reached that length, and deletes its output after final error. This path now also uses the existing strict response-framing validation; contradictory TE/CL and invalid resumed range metadata are permanent errors, without retries.

For known HTTP lengths/ranges, completion requires the exact validated size. Unknown-size responses require a clean stream end; without a trusted length or independent digest no implementation can detect a server intentionally ending an otherwise valid unknown-length representation early. Content is not published before the transport/core and save checks finish.

## Hard process death and next start

A hard kill runs no Java `finally`/`onDestroy`. In-memory status, cancellation and resumable-prefix metadata disappear. No new process reuses or appends an old partial download. Durable reserved job IDs remain unchanged.

Before starting the local server, once per process, the Helper discards:

* its private `cache/dl<signed-number>` directories;
* its private external `Video-Assistent/.evp-pending-*.part` staging files;
* its own MediaStore rows with `IS_PENDING=1` in exactly `Download/Video-Assistent` (with/without trailing slash).

Published rows, foreign owners, other folders and completed legacy files are preserved. A regular service restart in the **same** process does not repeat recovery over workers still unwinding. Cleanup failure aborts startup/download admission instead of pretending recovery succeeded. This does not immediately remove residue while the process is dead, reconstruct progress, guarantee filesystem durability on power loss, or provide automatic resume after force-stop/reboot. A process can die just after a complete file is published and before its notification; that complete file is retained.

## Targeted evidence and limits

`bash tests/java/run_download_lifecycle.sh` runs actual production `Downloads` and `Fetcher` with explicit host models of Android APIs. The models fault output writes/closes/updates and notifications and implement a persistent, scoped provider model; they do **not** execute Android MediaProvider, DownloadManager, notification delivery, foreground-service scheduling, mux, or directory fsync.

`bash tests/java/run_download_lifecycle.sh --transport` additionally runs:

* real local HTTP parsing of conflicting/valid framing;
* stalled read/timeout cancellation, bounded retries and a simultaneous healthy download (test-only 150 ms read timeout; not a production timeout change);
* existing 64 MiB native downloads at `-Xmx32m`, exact SHA-256 bytes, valid Range/If-Range resume, ignored ranges, unknown lengths, invalid ranges and cancellation;
* a separate JVM killed forcibly **during an actual save**, leaving real private work and a partial pending provider-model file, followed by another JVM's actual startup recovery. Kept complete/foreign/unrelated bytes and refusal to resurrect state are asserted. This is a true host process kill, not a Java exception, and **not** an Android process-kill proof. Legacy crash staging cleanup is seeded, not a real Android 8/9 kill experiment.

The branch-only counterproof script checks four independent failures against unchanged baseline production classes. Permanent CI runs the corrected lifecycle tests for ordinary PRs and `main` pushes. The existing memory job retains native/proxy 32 MiB heap, HLS/AES/DASH, HTTP framing/range/validator, timeout, late-upload, restart-ID and authentication regressions. A compile-only check uses the hosted Android SDK for actual `Downloads`, `HelferService` and `Keys`; it builds no APK and does not execute the service.

Issue #6 remains **open** pending independent review and actual Android provider/service/force-stop/reboot validation. In particular API 26–28 external storage and API 29+ owned pending-row queries/deletion, Samsung/OEM service timing and real browser-process termination are **not verified on real hardware**. No manual Samsung checklist is required by this change. A larger persistent crash-recovery mechanism is deliberately deferred; discarding owned unfinished work is the smaller safety guarantee.
