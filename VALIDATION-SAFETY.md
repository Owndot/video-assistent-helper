# Validation safety — current status, 2026-10-24

## Containment and public exposure
Containment PR #2 is merged into main (a7d031e2f39109c237153029b1b79f2f0ac3efa8). Current main contains no keystore and no embedded signing passwords. Its build workflow is debug-only, contents:read, with no release/tag deletion, creation or asset upload. Release tasks are blocked.

The repository is still PUBLIC. Removing files from main does not remove the historical key/passwords or revoke copies. Treat the old production signer as compromised. Owner access controls: Settings > General > Danger Zone > Change repository visibility > Private; review collaborators and Actions permissions; disable/cancel obsolete historical publishing workflows/runs if any. No visibility change or history rewrite was performed here.

## Verified public signing identity
Read-only Actions audit [40117549391](https://github.com/Owndot/video-assistent-helper/actions/runs/40117549391) extracted the certificate from the exposed historical keystore and verified the pinned published APK using apksigner. Both certificate SHA-256 values were:

`ec17fab1a92079911ea50e6d308f8c70ad79efc3bb48e0231f2559b26b7e42e5`

Published asset ID: 667389794. APK SHA-256: `bdbb2dd853ffcba80afb27492a31baf474ae1e3fd36418e3f7a5206abb760086`.
These are public hashes, never passwords. Matching the repository's current release asset is cryptographic evidence of that asset's signer; independent trust against an owner's already installed/offline APK remains NOT TESTED. Do not equate an APK file hash with its certificate hash.

The audit only exported public certificates and verified an APK; it did not sign a build. Ordinary build/performance/migration workflows do not retrieve the exposed keystore/passwords.

## CI boundaries
- build.yml: contents:read, no production secrets, no pull_request_target, no release commands. Debug APK uses isolated de.evp.helfer.debug. Release-verifier test uses a newly generated short-lived test identity; that production-ID test APK is not uploaded.
- helper-performance.yml: stable/current source comparison with isolated debug signing. Historical baseline fetch selects app/src and root build configuration only; never executes historical app/build.gradle.
- migration-tests.yml: disposable unrelated APK identities only; no production lineage.
- browser-tests.yml and userscript-performance.yml: read-only source/browser checks and artifact uploads; no result-branch writes or publishing.
- Normal PR validation can read and verify the public pinned release APK; it cannot sign with its private key.

## Evidence and remaining gates
Host/JVM/Gradle checks and the emulated migration matrix have actually executed. See FINAL-VALIDATION-REPORT.md for exact commits, runs, failures and limits. Physical S26 Ultra / Android 17 / Vivaldi / Firefox rows remain NOT TESTED. Emulator results do not certify OEM foreground-service/PiP behavior, energy consumption, or production update compatibility.

No production successor key, rotation, production build publication, release mutation or main security-PR merge is authorized by this validation.
