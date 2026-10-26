# Executed migration evidence — 2026-10-24

## Current decision — 2026-10-26
Option A is approved: a new exclusively locally managed production signer, unchanged `de.evp.helfer`, no lineage and no further use of the compromised signer. All supported Android versions require full uninstall/fresh install and browser re-pairing; future updates retain the same new signer and increase versionCode. No safe newly signed production APK is available yet. The historical 1.0.84 APK must not be installed; Kadri and the userscript work without Helper.

The results below remain historical fixture evidence, including the unselected v3.1 path. They are not a production-artifact approval. Current user procedure: [SIGNING-MIGRATION.md](SIGNING-MIGRATION.md); validation boundaries: [MIGRATION-VALIDATION.md](MIGRATION-VALIDATION.md). This work package does not generate a key or APK or implement signing.

## Containment
PR #2 is merged at a7d031e2f39109c237153029b1b79f2f0ac3efa8. Current main has no keystore or embedded signing passwords; its APK workflow is debug-only with no publication command. No active main workflow runs were observed at the final check.
The repository remains PUBLIC; historical copies are not revoked. Public source distribution does not make the historical APK safe.

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
No production key or lineage was generated in those tests. Option A now excludes a production lineage. Production signing and APK publication remain separate, unperformed steps.
The source PRs have since been merged after explicit owner approval. This does not certify a future production artifact; follow the current Option A validation plan in MIGRATION-VALIDATION.md before its separate release approval.
