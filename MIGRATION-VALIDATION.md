# Migration validation — production operations prohibited

## Confirmed containment and identity
Containment PR #2 was merged to main at a7d031e2f39109c237153029b1b79f2f0ac3efa8. Current main no longer contains the keystore or embedded signing passwords, and its APK workflow does not publish.
Historical copies remain compromised; current repository visibility is still public unless changed by the owner.
Read-only audit run 40117549391 independently exported the historical keystore certificate and cryptographically verified pinned release asset 667389794, checking its fixed file digest. The certificate SHA-256 matches on both:
ec17fab1a92079911ea50e6d308f8c70ad79efc3bb48e0231f2559b26b7e42e5
The audit did not sign, rotate, use repository production secrets or run PR application code. An APK copied from a known existing installation/offline archive remains useful independent distribution evidence.

## Owner policy
Security takes precedence over seamless legacy updates. Android 8.x support is desirable, but clean installation/separate migration is allowed. No production key generation, lineage creation, rotation or release is authorized.

## Proposed distribution separation
- Android 13+ / API 33+: evaluate v3.1 with an explicitly approved successor key and lineage; deliver a modern APK whose manifest minSdk is 33. Do not distribute a universal APK that silently retains old-key updates on earlier devices.
- Android 8-12L / API 26-32: prefer fresh-key installation after uninstall, with an explicit migration guide. This avoids relying on legacy rotation quirks or continuing to trust the exposed key.
- Android 9-12 v3 rotation is technically available but is not the default selected path: platform constraints and signature-permission behaviors require separate proof. No legacy in-place compatibility is promised.
- A new package ID remains an alternative if side-by-side data migration is needed. Both apps cannot own helper port 47811 at once.
- A v3.1 successor cannot be declared safe until an installed successor rejects higher-version APKs signed with the exposed predecessor. The fixture suite tests that exact negative case with disposable keys.

## Settings and pairing
SettingsTransfer exports/imports only language, theme and boot preference via the Android document picker, with an 8 KiB limit and strict allowlist. It never imports/exports pairing keys, paired status, runtime state, signing keys or Android permissions. The fixture verifies that an Android in-place update preserves private data. That is a compatibility test, not the final security policy: the approved production migration should invalidate the old RPC pairing key before enabling the helper endpoint, including on modern in-place updates. A clean reinstall generates a new pairing key and requires re-pairing. Do not implement the production signer-triggered reset before the successor identity and migration are approved.
An existing malicious update could have read the old pairing key: do not carry it into the fresh installation.
The already published legacy APK has no export feature. Do not sign an exporter update with the compromised key just to bridge this gap. Its three helper settings can be recorded manually; userscript/browser settings are separate and survive uninstalling only the helper. Future approved versions will provide the transfer feature.

## Automated real-APK tests
migration-tests.yml creates actual debug-package APKs using two ephemeral test identities, never a production key, and retains artifacts for two days. It tests representative emulators API 26,28,31,32,33,35:
- new-key update without lineage must fail;
- modern minSdk33 artifact must fail on older systems;
- v3.1 update on 33+ must retain seeded private settings/pairing;
- higher-version predecessor-key update must fail after modern migration and after clean new-key installation;
- fresh new-key installation must succeed and not inherit old private preferences.
Build fixture properties affect only explicit test invocations; defaults retain minSdk26 and the normal version selection. No production distribution decision follows from fixture success.

## Still required on physical devices
Confirm real production-APK provenance, the approved future signer/lineage per API, settings document export/import through real providers, new pairing on fresh install, overlay and notification permissions, foreground-service behavior, brightness/volume reset, media-server playback, HLS/subtitles, PiP/casting, interrupted/parallel downloads, and actual player handoff latency/memory/CPU/battery. Record device and browser versions plus old/new artifact hashes.
Emulator tests are Android runtime tests, not physical-device or battery tests. Do not merge the main security PRs or publish until remaining review/device gates are resolved.
