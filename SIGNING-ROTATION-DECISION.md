# Signing incident: decision required, no rotation performed

The supported minimum is Android 8.0 / API 26, and APKs are sideloaded through GitHub Releases. The current key must be treated as compromised. Do not use it for PR/CI signing. Safe validation uses SDK debug signing and an ephemeral CI identity; no production secrets are referenced.

## Platform constraints
- Android 8.x / API 26-27 has no v3 proof-of-rotation support. Replacing the signer for the same package cannot provide an ordinary in-place upgrade. Continuing old-key updates preserves compatibility but retains the compromised identity; an independently signed replacement package requires a deliberate data/settings migration.
- Android 9-12L / API 28-32 supports v3 lineage, but AOSP cautions against rotation on Android 12 and earlier. A rotation-min-sdk-version of 28 requires device-specific update, permission and settings tests.
- Android 13+ / API 33+ is the preferred v3.1 target. Default rotation keeps the original signer for older platforms; that compatibility fallback does not remediate compromise for those devices.
- Play Console key upgrades cannot be assumed to manage APKs distributed directly through GitHub.
- A lineage requires an explicitly authorized operation with the old and new keys. It does not retroactively revoke downloaded copies or protect devices that have not migrated.

Official references:
https://source.android.com/docs/security/features/apksigning/v3
https://source.android.com/docs/security/features/apksigning/v3-1
https://developer.android.com/tools/apksigner

## Recommended decision sequence
1. Restrict repository/workflow access and verify the exposed certificate against a trusted previously installed APK. A release download from the repository is evidence of the current asset only; independently confirm it against an existing device/offline copy.
2. Owner chooses whether to retain Android 8.x and what migration/support policy those users receive. There is no universal same-package, new-key, seamless update for API 26-27.
3. Prepare a new offline protected key and a lineage only after owner approval. Prefer a tested Android 13+ rotation path; separately evaluate API 28-32 if required.
4. Before distribution test old-to-new APK upgrades on API 26/27, 28, 31/32 and 33+ as relevant. Verify settings, pairing, downloads, overlay permission, service startup, brightness/volume and player handoff. The test must confirm the effective signer per platform, not only the newest certificate.
5. Existing single-fingerprint validators intentionally accept only the unrotated identity. Adapt them for an approved lineage with per-API apksigner verification before any rotated build is accepted.

No production signing, key generation/rotation, lineage creation, history rewrite or release mutation has been performed. All Android device checks remain UNTESTED.
