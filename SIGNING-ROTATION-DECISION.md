# Owner policy update

Security takes priority over seamless legacy updates. Android 8.x support is desirable, but reinstall/separate migration is explicitly permitted; API 28-32 legacy rotation is not the default. Proposed paths: minSdk33 v3.1 modern artifact, fresh-key clean installation for API26-32. Production rotation/publication is still prohibited pending approval and physical-device validation. See MIGRATION-VALIDATION.md; the prior alternatives below are background, not an open legacy-compatibility requirement.

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
2. Owner has chosen security over seamless legacy updates. Prefer a modern API33+ v3.1 path, with fresh-key clean installation and explicit settings migration for API26-32. There is no universal same-package, new-key, seamless update for API26-27; do not keep using the compromised signer to preserve compatibility.
3. Prepare a new offline protected key and a lineage only after owner approval. Prefer a tested Android 13+ rotation path; separately evaluate API 28-32 if required.
4. Before distribution test old-to-new APK upgrades on API 26/27, 28, 31/32 and 33+ as relevant. Verify settings, pairing, downloads, overlay permission, service startup, brightness/volume and player handoff. The test must confirm the effective signer per platform, not only the newest certificate.
5. Existing single-fingerprint validators intentionally accept only the unrotated identity. Adapt them for an approved lineage with per-API apksigner verification before any rotated build is accepted.

No production signing, key generation/rotation, lineage creation, history rewrite or release mutation has been performed. Physical Android checks remain NOT TESTED. Disposable APK migration mechanics have executed on emulator APIs 26, 28, 31, 32, 33 and 35; see MIGRATION-VALIDATION.md and FINAL-VALIDATION-REPORT.md.

## Fingerprint comparison
Run on a trusted workstation (Java keytool and Android SDK apksigner in PATH):
`python3 tools/compare-exposed-signature.py /secure/old-keystore.jks /secure/trusted-old.apk --alias YOUR_ALIAS`
The password is prompted without echo; it is not embedded in code or arguments. Output contains only public certificate/file hashes.
The currently published GitHub asset has ID 667389794 and SHA-256 bdbb2dd853ffcba80afb27492a31baf474ae1e3fd36418e3f7a5206abb760086 (metadata observed 2026-10-24). Compare an independent existing-installation/offline APK as well; repository publication alone is not independent trust.
The published-asset comparison has now executed successfully in read-only Actions audit 40117549391: historical keystore and pinned published APK both use certificate SHA-256 ec17fab1a92079911ea50e6d308f8c70ad79efc3bb48e0231f2559b26b7e42e5. This does not independently authenticate an owner's installed/offline copy; that additional comparison remains NOT TESTED. No private-key signing occurred. Do not infer a certificate fingerprint from the APK file digest.
