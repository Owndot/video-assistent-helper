# Migration validation — production operations prohibited

## Confirmed containment and identity
Containment PR #2 was merged to main at a7d031e2f39109c237153029b1b79f2f0ac3efa8. Current main no longer contains the keystore or embedded signing passwords, and its APK workflow does not publish.
Historical copies remain compromised; current repository visibility is still public unless changed by the owner.
Read-only audit run 40117549391 independently exported the historical keystore certificate and cryptographically verified pinned release asset 667389794, checking its fixed file digest. The certificate SHA-256 matches on both:
ec17fab1a92079911ea50e6d308f8c70ad79efc3bb48e0231f2559b26b7e42e5
The audit did not sign, rotate, use repository production secrets or run PR application code. An APK copied from a known existing installation/offline archive remains useful independent distribution evidence.

## Approved owner policy: Option A

As of 2026-10-26, the owner has approved a new exclusively locally managed production signer, unchanged package `de.evp.helfer`, **no lineage and no reuse of the compromised signer**. All supported Android versions require full uninstall of the old production app, fresh installation and browser re-pairing. Later ordinary updates retain the same new signer and increase `versionCode`.

This supersedes the earlier API33+ v3.1 / API26–32 split and any alternate package proposal. Strategy approval does not authorize production operations in this documentation work package. No safe newly signed production APK is released; the historical 1.0.84 APK must not be installed. Key generation/signing-process work belongs to the separately commissioned work package 2. See [SIGNING-MIGRATION.md](SIGNING-MIGRATION.md) for the user procedure and [Issue #3](https://github.com/Owndot/video-assistent-helper/issues/3), which remains open.

## Settings and pairing

The existing SettingsTransfer code allowlists language, theme and boot preference, excluding pairing keys/status, runtime state, signing keys and Android permissions. This is not an upgrade bridge for the published historical APK: that APK has no export feature. Record its three settings manually; never sign an exporter with the compromised key.

Completely uninstall the old app without retaining/restoring its private data. A fresh trusted installation creates a new pairing key and requires re-pairing every browser, replacing cached old keys. Browser/userscript preferences are separate from Helper app data. Do not carry potentially disclosed old pairing secrets into the new installation. No signer-triggered runtime reset or protocol change is needed or implemented in this documentation round.

## Automated real-APK tests
Historical evidence, retained unchanged: migration-tests.yml creates actual debug-package APKs using two ephemeral test identities, never a production key, and retains artifacts for two days. It tests representative emulators API 26,28,31,32,33,35:
- new-key update without lineage must fail;
- modern minSdk33 artifact must fail on older systems;
- v3.1 update on 33+ must retain seeded private settings/pairing;
- higher-version predecessor-key update must fail after modern migration and after clean new-key installation;
- fresh new-key installation must succeed and not inherit old private preferences.
Build fixture properties affect only explicit test invocations; defaults retain minSdk26 and the normal version selection. These fixture results do not prove the future real production artifact. Their v3.1 branch is historical coverage and is not the selected Option A production path.

## Later production-artifact validation — not executed in this work package

After the separate signing preparation and before distribution, verify the actual fresh installation under `de.evp.helfer`, the new public signer fingerprint, absence of lineage/old-signer trust, manual settings recovery, new browser pairing and renewed permissions. Validate subsequent same-new-signer/increasing-versionCode updates and rejection of old-signer updates. Use [ANDROID-UPDATE-CHECK.md](ANDROID-UPDATE-CHECK.md) to distinguish those two stages.

Existing OEM/service/player checks remain recorded with their original limits; no new browser/emulator matrix is started here. Emulator fixture success is not physical-device or production-signature evidence. Earlier source-merge gates are historical: reviewed source PRs are merged; this does not grant production publication. No new production APK, key, lineage, tag or release is created by this work package.
