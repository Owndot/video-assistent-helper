# P4-Eigentümertest über GitHub Actions – separat freizugebender Testweg

**Keine reguläre P4-Lieferung und keine Produktionsfreigabe.**

**Abgrenzung:**
- Workflow `.github/workflows/p4-owner-test.yml`, nur auf dem Branch `p4-owner-test`, kein Environment, keine Secrets.
- Unverändert bleiben: die gesperrten Lieferworkflows (`p4-test-package.yml`, Kadri `p4-test-extension.yml`), `release/production-policy.json` (UNAPPROVED) und `release/p4test-signer.json` (null).
- Aus diesem Branch nie einen Pull Request öffnen und nie mergen.

## Was der Lauf tut (ein Job, keine Secrets)

1. **Freigabe:**
   - Nur nach Push auf `p4-owner-test` durch den Repo-Eigentümer, nur der erste Versuch.
   - Die Freigabe ist der Push selbst: Er erfolgt nur nach Ihrer ausdrücklichen Freigabe. GitHub verbucht auch Pushes aus der Claude-Sitzung als Eigentümer; einen zusätzlichen technischen Freigabeschritt gibt es nicht.
2. **Geprüfte Quellen:**
   - Helper `1fc0063d29de4a88f7a3500ca4894ddaffdcf7ac` und Kadri `7978a41232c3b1582289c53461da4fe63ad28ff9`, je exakt ausgecheckt.
   - Zusätzlich geprüft: Produktionsfreigabe UNAPPROVED, dauerhafte P4-Festlegung `null`.
3. **Build:**
   - `build-channel-native.sh` und `build-unsigned.sh`, nur `de.evp.helfer.p4test`.
   - Es entsteht keine Release-, Debug- oder `channeltest`-Ausgabe; das Arbeitsverzeichnis bleibt sauber.
4. **Temporärer Testsignierer** (wie im Lieferworkflow):
   - Schlüssel im Lauf erzeugt (RSA-3072, 2 Tage), einmal über `sign.sh` benutzt, danach gelöscht.
   - Geprüft wird, dass kein Schlüsselmaterial übrig ist.
5. **Prüfung der signierten APK** (wie `verify-p4.sh` im Lieferworkflow):
   - Paket, Versionscode, Name `-p4test`, Bezeichnung, ABIs, targetSdk 34;
   - nicht debugfähig und nicht testOnly;
   - genau 12 Berechtigungen; `allowBackup=false`;
   - Quell-Commit in der APK;
   - v2 und v3, genau ein Signierer gleich dem Zertifikat dieses Laufs, höchstens 3 Tage gültig, nie `ec17fab1…`;
   - genau die zwei `.so`, Architektur, JNI-Einstieg, kein Testinitiator.

   Zusätzlich die **16-KB-Prüfung** `tools/p4owner/p4-native-preflight.sh`:
   - SHA-256;
   - `zipalign -c -P 16 -v 4` (build-tools 36.0.0);
   - jedes ELF-`LOAD`-Segment `Align ≥ 0x4000`.
6. **Kadri „Kadri P4 TEST“:**
   - `build.js`, Suche nach dem Altprotokoll, `p4_identity_check.js`, `pack-extension.py`;
   - Manifestprüfung;
   - Inhalts-Prüfsumme `e87a16fb…`.
7. **Artefakt** `p4-owner-test-<Laufnummer>`, 2 Tage: `helper-p4test-<N>.apk`, `kadri-p4test.zip`, `SHA256SUMS`, `evidence.txt`.

Jede Abweichung bricht ab, und es entsteht kein Artefakt.

## Wichtig: temporärer Signierer

- Jeder Lauf erzeugt einen **neuen** Signierer, und der Schlüssel existiert danach nicht mehr.
- Eine spätere Test-APK (auch aus diesem Weg) lässt sich daher **nicht als Update installieren**.
- Dafür muss `de.evp.helfer.p4test` vorher **deinstalliert** werden. Ihre Daten, Kopplung und laufenden Downloads gehen verloren; fertige Dateien in `Download/Video-Assistent` bleiben. Danach mit „Kadri P4 TEST“ neu koppeln.
- Dasselbe gilt für eine schon vorhandene `p4test` aus einer früheren Lieferung.
- `de.evp.helfer`, `de.evp.helfer.channeltest` und die übrigen Erweiterungen sind davon nie betroffen.
- Updates ohne Datenverlust bräuchten später einen dauerhaften Testschlüssel; das wäre eine eigene Entscheidung.

## Einrichtung und Lauf

1. Push des Branches `p4-owner-test`, nur nach Ihrer Freigabe. Der Lauf startet sofort.
2. Nach Ende das Artefakt herunterladen.
3. Mit `SHA256SUMS` und `evidence.txt` abgleichen.
4. Installation erst nach eigener Freigabe gemäß Samsung-Testplan.

Ein neuer Lauf braucht einen neuen Commit, der diese Workflow-Datei ändert; ein Re-Run wird abgelehnt.

## Grenzen

- Das Repository ist öffentlich: Angemeldete GitHub-Nutzer können das Artefakt 2 Tage lang herunterladen. Es enthält keinen Schlüssel.
- Erhält `apksigner` aus build-tools 34.0.0 die 16-KB-Ausrichtung nicht, bricht der Lauf an der 16-KB-Prüfung ab. Dann STOP, Log sichern, eigene Entscheidung.
