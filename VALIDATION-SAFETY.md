# Safe validation and current incident status — 2026-10-24

## Containment proposal
Separate draft PR #2 targets main. It removes keystore.jks and embedded passwords, blocks release tasks, and replaces the old publishing workflow with debug-only validation.
Until the owner disables the current main workflow and merges containment, main still exposes the original key and can overwrite release neueste. No repository-visibility, workflow-disable, main-merge, rotation, release or history mutation was performed by this correction.

## Owner access steps
Settings > General > Danger Zone > Change repository visibility > Private.
Actions > APK bauen > workflow menu > Disable workflow; cancel queued and running builds separately.
Review Settings > Collaborators and teams and Settings > Actions > General > Workflow permissions.
Treat the key as potentially compromised even after access is restricted.

## Safe security-branch CI
build.yml uses contents:read, no repository signing secrets, no pull_request_target and no release command. It compiles JVM sources, runs the port/core tests and Python security tests, builds the isolated debug package, then exercises release verification using a newly generated test-only identity. The test-signed release APK is never uploaded. Only the separate debug APK is an artifact.
browser-tests.yml no longer force-pushes result branches; it uploads artifacts with read permission.

## Executed evidence
Actions run 40116464117, commit facc175ed5f0e8f3a3846053ef7c773e3450de66, completed successfully.
Logs confirm 7 Python unittest cases; JVM compilation and exclusive-port regression; core media test script completion with 11 generated tracks checked by ffmpeg and zero decode errors; assembleDebug; test-key assembleRelease including verifyReleaseSigning and verifyReleaseApk.
This is host/CI evidence, not an Android device test. No production fingerprint match was established.
The offline public-certificate comparison script was added later and remains unexecuted. Its password prompt is intended only for a trusted workstation, never PR CI.

See SIGNING-ROTATION-DECISION.md for platform constraints and the required owner decision. Device updates, persisted pairing/settings, overlay permissions, real brightness/volume and handoff remain UNTESTED.
