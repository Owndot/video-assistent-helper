# Signing exposure containment — 2026-10-24

GitHub reports the repository PUBLIC. The main commit b05fd2de60d27d368eda60ff32be8b361a695f45 contains a keystore and matching embedded credentials. Treat that signing identity as potentially compromised. Do not print or copy credentials into tickets, logs or tests.

This minimal hotfix removes signing material from the current tree and pauses release builds/publication. Debug uses a separate application ID and the SDK debug key. No release/tag is changed by the replacement workflow.

## Immediate owner actions
1. Repository Settings > General > Danger Zone > Change repository visibility > Change to private. Confirm the repository name. This restricts future access, not existing copies.
2. Actions > APK bauen > workflow menu > Disable workflow BEFORE merging this hotfix. Cancel any queued/in-progress APK builds; disabling does not cancel running jobs. Other workflows, such as Browser-Tests, may have write permissions: disable them temporarily if not needed.
3. Settings > Collaborators and teams: remove unnecessary access. Settings > Actions > General > Workflow permissions: use Read repository contents permission.
4. Settings > Secrets and variables > Actions: review signing secrets and remove ordinary CI access. Use an owner-approved protected release environment for any future production signing; do not supply production secrets to PR code.
5. Secure an encrypted offline backup of the old key before any separately approved deletion/history procedure.

The hotfix PR is a proposal: main remains exposed and its old release workflow remains active until the owner disables it and merges the patch.
Removing current files or changing visibility does not revoke historical downloads. Rotation, trusted-APK comparison and device upgrade verification require separate steps; no rotation/history rewrite/publication is authorized by this hotfix.
