# Release signing migration

The release application ID remains `de.evp.helfer`. Never replace the production key silently.

## Exposure assessment (2026-10-24)
The GitHub API reports this repository as **private**. This is its current visibility; it does not establish that it was always private or that nobody copied it.
The tracked keystore and embedded credentials were removed from branch HEAD, but remain reachable in parent commit `b05fd2de60d27d368eda60ff32be8b361a695f45`. Repository readers with history access can recover them. Moving credentials into secrets does not revoke historical copies.
No key rotation, history rewrite, release or tag mutation has been performed during this correction.

## Preserve updates
1. Make an encrypted offline backup of the original production keystore and restrict access.
2. Obtain a previously distributed production APK from a trusted installation or release. Run `apksigner verify --print-certs old.apk` locally. Record only the public signing-certificate SHA-256.
3. Independently compare that fingerprint with the existing keystore. Configure EVP_CERT_SHA256, EVP_KEYSTORE_B64, EVP_STORE_PASSWORD, EVP_KEY_ALIAS and EVP_KEY_PASSWORD using the SAME verified production identity.
4. For local release builds also set EVP_CERT_SHA256 and the four EVP signing variables, plus a RELEASE_VERSION_CODE greater than the installed production APK.
5. Run the APK workflow on this branch with **publish=false**. It produces an artifact only. Branch runs cannot enter the release job, even with publish=true.
6. Verify the finished APK and perform the device procedure in ANDROID-UPDATE-CHECK.md before merging or distributing.
7. Publication is a separate, explicit workflow_dispatch on main with publish=true. It creates a new versioned tag/release; it never deletes or replaces an existing release/tag. Automatic main builds and default manual runs only upload artifacts. The old `neueste` release is left intact; consumers should use GitHub's latest-release asset URL for future releases.
8. Restrict workflow edits and signing-secret access. If historical key exposure warrants rotation, design and test an Android-supported signing lineage/migration for supported devices first. Keep compatibility for existing installations; neither a replacement key nor a history rewrite alone solves this.

## Verification limits
The workspace is unavailable. A trusted production APK fingerprint, actual repository-secret configuration, Gradle builds and device upgrades have NOT been verified here. The migration remains pending until these checks pass. No private passwords or key material belong in reports, screenshots or test output.
