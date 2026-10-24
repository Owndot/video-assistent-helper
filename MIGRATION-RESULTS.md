# Executed migration evidence — 2026-10-24

## Containment
PR #2 is merged at a7d031e2f39109c237153029b1b79f2f0ac3efa8. Current main has no keystore or embedded signing passwords; its APK workflow is debug-only with no publication command. No active main workflow runs were observed at the final check.
The repository remains PUBLIC; historical copies are not revoked. The owner should still restrict access.

## Actual production identity
Read-only, no-checkout audit:
https://github.com/Owndot/video-assistent-helper/actions/runs/40117549391
Historical commit b05fd2de60d27d368eda60ff32be8b361a695f45 keystore certificate matches cryptographically verified release asset 667389794.
Certificate SHA-256: ec17fab1a92079911ea50e6d308f8c70ad79efc3bb48e0231f2559b26b7e42e5
APK file SHA-256: bdbb2dd853ffcba80afb27492a31baf474ae1e3fd36418e3f7a5206abb760086
No signing, rotation, release modification or PR application-code execution occurred in this audit.

## Host and build checks
https://github.com/Owndot/video-assistent-helper/actions/runs/40117868729
Successful: 9 Python security tests, JVM compilation, port regression, settings round trip/allowlist/secret exclusion/size limit, media core script, debug build and ephemeral-key release build including final APK signature verification.
Settings export/import through an actual Android document provider is not covered by the JVM test.

## Real APKs on Android emulators
https://github.com/Owndot/video-assistent-helper/actions/runs/40118147169
Source: 6bbebbb7a50c6a58a2232e783811ae213f921f8a.
Fixtures and all emulator jobs API 26,28,31,32,33,35 succeeded.
The fixtures use the actual helper debug package and disposable keys, not the production identity:
- API33/35: v3.1 update preserves seeded private state; a higher-version APK signed only with the predecessor is rejected.
- API26/28/31/32: modern artifact rejected; the old app and seeded settings remain intact. Host independently verifies modern manifest minSdk33. Some older platforms reject signature compatibility before minimum SDK.
- Every tested API rejects an unapproved new-key in-place update; clean installation works; subsequent old-key update is rejected.

Initial matrix failures were strict legacy error-code assumptions in the harness. Corrected runs log the actual failure and accept only the documented SDK/signature rejection codes. No failures are represented as passes.

## Limits and next gate
This proves installation mechanics for disposable APKs on representative emulators. It does not test every OEM or intermediate Android release, production signer migration, actual pairing RPC, file picker UI, physical brightness/volume, battery or player performance.
The new production key and lineage do not exist in this task. No production rotation or release is approved or performed.
Use MIGRATION-VALIDATION.md for the physical-device checklist. Complete physical-device and performance review before merging the two main security PRs. The separate containment hotfix is already merged by explicit owner instruction.
