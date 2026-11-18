# Gerätetest-Paket aus der Cloud: Test-App-APK (isoliert)

Workflow: `.github/workflows/poc-device-package.yml` (Branch `feature/helper-channel-dummy`). Baut **nur** die Test-App `de.evp.pakepoc.test` aus dem geprüften Commit `15fed6d52b6b4b7de6658910ac2d71e9be10e519`. Nie `de.evp.helfer`, kein produktiver Signierer, kein Secret, kein Release, kein Tag. `release/production-policy.json` bleibt `UNAPPROVED` (der Workflow prüft es).

## Was der Workflow tut

| Schritt | Prüfung |
| --- | --- |
| Quellen | genau Commit `15fed6d` (Prüfung per `git rev-parse`); Gradle-Wrapper durch `gradle/actions/wrapper-validation` geprüft |
| Toolchain | Rust 1.97.0 (`rust-toolchain.toml`), NDK 27.2.12479018, SDK 34, Build-Tools 34.0.0, JDK 17 (Temurin), Gradle 8.9 (Wrapper mit festgelegter Distributions-Prüfsumme) – die Build-Skripte brechen bei anderen Versionen ab |
| Signatur | Wegwerf-Schlüssel, im Job erzeugt und nach dem Build gelöscht (`build-apk.sh`); geprüft: genau ein Signierer, DN `CN=PAKE PoC TEST ONLY - throwaway`, Gültigkeit ≤ 3 Tage |
| Paket | `aapt2 dump badging`: Paket `de.evp.pakepoc.test`, kein `de.evp.helfer`, ABIs `arm64-v8a` + `x86_64` |
| Native Bibliotheken | nur `libkadri_pairing_poc.so` je ABI; Maschinentyp per `llvm-readelf` (AArch64 / X86-64); keine Testinitiator-Symbole; bytegleich mit dem reproduzierbaren Build |
| Test | Instrumentierung (15 Tests) gegen **genau diese** APKs auf einem Wegwerf-Emulator (API 34), danach Deinstallation |
| Ausgabe | `SHA256SUMS` (APK, `.so`, Commit, Lauf-ID) im Log |
| Upload | **nur** wenn `poc/android-capability/DEVICE-ARTIFACTS-APPROVED` im Commit existiert; dann nur die App-APK (nicht die Instrumentierungs-APK), `SHA256SUMS` und die Testzusammenfassung, Aufbewahrung 1 Tag |

**Neuer Signierer je Lauf:** Jede Cloud-APK ist mit einem anderen Wegwerf-Schlüssel signiert. Android installiert eine APK nicht über eine Version mit anderem Signierer – vor jeder neuen Test-App die alte deinstallieren (Testdaten gehen dabei verloren; danach neu koppeln). Die APK-Hashes sind deshalb nicht reproduzierbar; reproduzierbar sind die nativen Bibliotheken.

## Bereitstellung – Prüfung vor dem Upload

| Frage | Befund |
| --- | --- |
| Wer kann Artefakte herunterladen? | Beide Repositories sind **öffentlich**. Artefakte öffentlicher Repositories kann **jedes angemeldete GitHub-Konto** herunterladen, solange sie existieren (hier: 1 Tag). Sie sind also eine Veröffentlichung, nicht privat. |
| Nur öffentlich überprüfbarer Testcode? | Ja: gebaut aus öffentlichen Commits (`15fed6d` bzw. dem Kadri-Branch), Prüfsummen im öffentlichen Log. |
| Geheimnisse ausgeschlossen? | Der Wegwerf-Schlüssel wird vor dem Upload gelöscht und nie hochgeladen; der Workflow nutzt keine Secrets; die APK enthält keine Schlüssel, Codes, Cookies oder URLs (Identität entsteht erst auf dem Gerät). |
| Zusätzliches Risiko durch eine signierte Test-APK? | Gering, aber vorhanden: Dritte könnten eine installierbare „TEST“-App laden, die einen Loopback-Dienst auf Port 47899 öffnet; sie kann keine Daten anderer Apps lesen und nichts außerhalb des Geräts erreichen. Mit dem Schlüssel lässt sich nichts nachsignieren (gelöscht). Wer die öffentlichen Quellen hat, kann dieselbe App ohnehin selbst bauen. |

Optionen:

1. **Privates Repository (empfohlen, zugriffsbeschränkt):** Ein privates Repository (z. B. `Owndot/kadri-device-test`), in das die gepinnten Commits beider Repositories und die zwei Workflows gespiegelt werden; die Artefakte sind dann nur für berechtigte Konten sichtbar. Nötig: Du legst es an (oder erlaubst mir, es anzulegen) und gibst der Claude-GitHub-App Zugriff.
2. **Öffentliches Artefakt mit kurzer Aufbewahrung:** Upload nach deiner ausdrücklichen Zustimmung (Freigabedatei committen), 1 Tag sichtbar für angemeldete GitHub-Konten.
3. **Verschlüsseltes Artefakt im öffentlichen Repository:** ZIP mit AES-256 und einem Passwort aus einem von dir angelegten Repository-Secret. Nicht empfohlen ohne Prüfung: Ob „Eigene Dateien“ auf dem S26 AES-verschlüsselte ZIPs öffnet, ist hier nicht geprüft.

Ohne deine Entscheidung wird nichts Installierbares hochgeladen.

## Freigabedatei

Erst nach ausdrücklicher Zustimmung des Eigentümers anlegen. Inhalt: Datum, Zweck („Gerätetest S26 Ultra, Dummy-Daten“), gewählte Option. Nach dem Herunterladen wieder entfernen (Commit), damit spätere Läufe nichts hochladen.

## Schritte auf dem Samsung

Siehe `HELPER-CHANNEL-INTEGRATION.md` im Kadri-Repository, Abschnitt 10 (Branch `feature/helper-channel-dummy`): alte Test-App und Testerweiterung entfernen → APK und Erweiterungs-ZIP laden und Hashes prüfen → APK installieren → ZIP entpacken und in Vivaldi als entpackte Erweiterung laden → Prüfschritte → alles entfernen.
