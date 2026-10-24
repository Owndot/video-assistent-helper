# Release signing migration

The release application ID remains `de.evp.helfer`. Never replace the production key silently.

## Exposure assessment (2026-10-24)
The GitHub API now reports this repository as **public**. The earlier private-visibility observation is obsolete. The current main branch also still exposes signing material until containment PR #2 is merged.
The tracked keystore and embedded credentials were removed from branch HEAD, but remain reachable in parent commit `b05fd2de60d27d368eda60ff32be8b361a695f45`. Repository readers with history access can recover them. Moving credentials into secrets does not revoke historical copies.
No key rotation, history rewrite, release or tag mutation has been performed during this correction.

## Preserve updates
1. Make an encrypted offline backup of the original production keystore and restrict access.
2. Obtain a previously distributed production APK from a trusted installation or release. Run `apksigner verify --print-certs old.apk` locally. Record only the public signing-certificate SHA-256.
3. Independently compare that fingerprint with the existing keystore. Do not configure repository-wide production signing secrets for ordinary CI. Preserve the verified old identity offline pending the owner's rotation decision; any later production signing must use an independently reviewed, protected release process.
4. For local release builds also set EVP_CERT_SHA256 and the four EVP signing variables, plus a RELEASE_VERSION_CODE greater than the installed production APK.
5. Run Helper safe validation on this branch. It has no publish input or release job and uses disposable signing only. The only APK artifact is the separate debug app.
6. Verify the finished APK and perform the device procedure in ANDROID-UPDATE-CHECK.md before merging or distributing.
7. Publication is currently paused in containment PR #2 and absent from the security-branch validation workflow. The existing main workflow is still unsafe until disabled/replaced. No production release path may be enabled before the incident and signing decision are resolved.
8. Restrict workflow edits and signing-secret access. If historical key exposure warrants rotation, design and test an Android-supported signing lineage/migration for supported devices first. Keep compatibility for existing installations; neither a replacement key nor a history rewrite alone solves this.

## Verification limits
The workspace is unavailable. A trusted production APK fingerprint, actual repository-secret configuration, Gradle builds and device upgrades have NOT been verified here. The migration remains pending until these checks pass. No private passwords or key material belong in reports, screenshots or test output.
