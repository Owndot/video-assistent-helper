# Release signing migration

This branch removes the tracked keystore and embedded signing credentials. It does not revoke a key or erase Git history.

Before merging:
1. Make an offline protected backup of the existing production keystore. The original remains in the parent commit; do not rewrite history before the backup and migration are verified.
2. Configure repository secrets EVP_KEYSTORE_B64, EVP_STORE_PASSWORD, EVP_KEY_ALIAS and EVP_KEY_PASSWORD using that SAME key.
3. Obtain the signing certificate SHA-256 from a previously installed/distributed production APK using apksigner verify --print-certs. Set EVP_CERT_SHA256 to that fingerprint. Do not derive the expected value solely from an unverified replacement key.
4. Run the APK workflow on this branch and test installation over the existing app without uninstalling; pairing and settings must survive.
5. Restrict signing-secret and release-workflow access. Anyone with the historical key and credentials may still sign; assess exposure and supported signing-key rotation separately.

Missing credentials or a mismatched certificate stop release builds. Debug uses a separate application ID and the normal Android debug key, so it cannot overwrite a production installation.

Validation here: reviewed configuration and checked removal of embedded signing passwords. Gradle, keytool and APK update tests were unavailable because the execution environment was offline. No GitHub signing secrets were created or inspected.
