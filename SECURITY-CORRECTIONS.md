# Current validation status — 2026-10-24

The report below is historical; its earlier visibility, test and fingerprint statements are superseded by VALIDATION-SAFETY.md, MIGRATION-VALIDATION.md and FINAL-VALIDATION-REPORT.md.

Containment PR #2 is merged. Current main has no embedded key/passwords and cannot publish from its current workflow. The repository is still public and historical copies remain compromised. A read-only audit verified the exposed certificate against the pinned published APK. Normal validation uses disposable signing identities only; the verified compromised fingerprint is blocked by release validation.

Additional validation found and fixed a rapid listener-restart race, Android cookie sharing between unrelated PSL tenants, native Android redirect cookie forwarding, and the corresponding userscript session-permission boundary. These are targeted corrections, not a player redesign. Physical-device validation and production migration remain pending.

# Phase 1 corrections — 2026-10-24

## Implemented
- Build job has contents:read; publication is a separate contents:write job requiring an explicit manual main-branch publish input. No release/tag deletion or asset overwrite remains. Development branch builds are artifact-only.
- Local release builds require the expected public production certificate SHA-256, the existing external signing key, and an explicit versionCode outside CI. The keystore certificate is verified; assembleRelease also verifies the finished APK using SDK apksigner and checks its signer.
- Production applicationId stays de.evp.helfer. A host preflight checks both APK signatures, identical production IDs and increasing versionCode.
- MiniServer binds before reporting success. A service with a port conflict remains not ready, stops and reports an actionable error. Pairing waits for this app's successful startup; it does not open a URL against a competing helper. Endpoint remains 127.0.0.1:47811 for browser compatibility. Switching debug/release intentionally requires stopping the other app and pairing the intended one.

## Exposure evidence and limits
GitHub reports the repository private as of this review. The parent Git tree b05fd2de60d27d368eda60ff32be8b361a695f45 still lists keystore.jks (2578 bytes) and historical signing configuration. Removing files at HEAD does not remove those historical objects or invalidate copies. Past visibility and copies are unknown.
The existing published release neueste has an APK asset, but no trusted production fingerprint was independently extracted here. The execution workspace is unavailable; SDK keytool/apksigner, Gradle, JVM and Android device tests could not run. Repository signing secrets were neither configured nor inspected.
No rotation, history rewrite, release, tag mutation or workflow dispatch was performed.

## Validation
Nine JS-executed static source assertions passed: default read permission, no build-job write permission/publication command, main-only explicit release guard, false default publish input, no deletion/overwrite, exact release target and configured finished-APK verification.
These checks are static source inspection, not YAML/Actions execution, Gradle execution or cryptographic verification.
Added Python unittest cases for update identity/version checks, SDK-output parsing and workflow policy, plus a JVM regression for competing listeners, shutdown and retry. They are committed but NOT executed here.
The JVM regression requires start() to return readiness; it cannot pass unchanged against the previous void-returning implementation.

## Required next validation
Run python3 -m unittest discover -s tests/security -v, tests/java/build.sh and the JVM core tests. Run the workflow with publish=false on this branch after checking secrets. Verify a trusted old APK and the new APK with tools/check-update.py. Follow ANDROID-UPDATE-CHECK.md on actual devices before release.
SIGNING-MIGRATION.md describes backup, exposure assessment and a compatibility-preserving migration. Production identity verification, state-preserving upgrade tests and any supported key-rotation decision remain pending.
