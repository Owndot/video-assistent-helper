# Lokaler APK-Releaseprozess — Option A, noch nicht produktiv freigegeben

Arbeitspaket 2 bereitet Build und Prüfung vor. **Jetzt keinen Produktionsschlüssel erzeugen und keine produktive APK signieren.** Erst nach gesonderter Freigabe des Prozesses folgen die lokale Schlüsselerzeugung und Samsung-Gerätetests. GitHubs Releaseliste ist nach der manuellen Entfernung leer; eine sichere neue Produktions-APK ist weiterhin nicht verfügbar.

## Sicherheitsgrenzen und reproduzierbarer Buildweg

Gradle erzeugt ausschließlich eine **unsignierte** Release-APK. Es liest keinen Keystore und keine EVP-Passwortvariablen mehr. Debug bleibt `de.evp.helfer.debug`; die Produktions-ID bleibt `de.evp.helfer`, minSdk 26, targetSdk 34, Release nicht debuggable. Migration-Fixture-Parameter werden bei Release-Tasks abgelehnt. Release-Versionen müssen explizit angegeben werden; GITHUB_RUN_NUMBER und RELEASE_VERSION_CODE sind keine Produktionsversionsquelle.

Voraussetzungen auf einem vertrauenswürdigen Mac: Git, Python 3.12, JDK 17, Gradle **8.9**, Android-SDK-Plattform **34**, Build-Tools **34.0.0**, AGP **8.5.2** (im Projekt gepinnt). SDK über die offiziellen Android-Werkzeuge installieren. Java/Gradle/SDK-Patchstände und OS für spätere Wiederholungen zusätzlich protokollieren. Der Weg fixiert Quellcommit und wesentliche Werkzeugversionen; eine bitidentische, hermetische Reproduktion über alle Hosts wird damit nicht behauptet. Abhängigkeiten werden über die bestehenden offiziellen Gradle-Repositories aufgelöst; es gibt keine neue Lieferkettenarchitektur.

Nach Review den vollständigen freigegebenen 40-stelligen Quellcommit und die vom Eigentümer gewählte Version einsetzen. Keine Platzhalter ungeprüft übernehmen. Es werden nur Commits ab dem sicheren Ausgangsstand `d111923` akzeptiert. Der Builder verwendet einen separaten, sauberen Git-Worktree dieses Commits; lokale Änderungen im Arbeitsverzeichnis fließen nicht in die APK ein.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/build-tools/34.0.0:$PATH"
# JDK 17 und Gradle 8.9 vorher prüfen; kein Shell-Tracing einschalten.
python3 tools/prepare-release.py --commit REVIEWED_FULL_40_HEX_COMMIT \
  --version-code OWNER_CHOSEN_CODE --version-name OWNER_CHOSEN_NAME \
  --out "$HOME/Helper-Releases/NEW_EMPTY_BUILD_DIRECTORY"
```

Ergebnis: `helper-unsigned-aligned.apk` und `build-provenance.json` mit Quellcommit, Versionen und SHA-256 der unsignierten APK. Der Ausgabeordner muss neu sein. Die APK ist zipaligned und ohne Signaturen, **kein installierbarer, freigegebener Produktionsrelease**. Keine Datei nach dem Signieren zipalignen oder anderweitig verändern. Metadaten im Provenienzbericht sind keine unabhängige Build-Attestation.

## Später: neuen Schlüssel ausschließlich auf dem Mac erzeugen

**Nur als Anleitung für den später genehmigten Schritt, nicht jetzt ausführen.** Der Keystore muss außerhalb jedes Repositorys, Build-/CI-Verzeichnisses und automatisch synchronisierten öffentlichen Ordners liegen. Kein Upload zu Codex, GitHub, Actions oder Tests.

```sh
umask 077
mkdir -p "$HOME/Library/Application Support/Helper-Signing"
chmod 700 "$HOME/Library/Application Support/Helper-Signing"
keytool -genkeypair -storetype PKCS12 \
  -keystore "$HOME/Library/Application Support/Helper-Signing/production.p12" \
  -alias helper-production -keyalg RSA -keysize 3072 -validity 10000
```

`keytool` fragt Passwort und Zertifikatsangaben interaktiv ab. Ein starkes individuelles Passwort verwenden und getrennt im Passwortmanager verwahren. Keine Passwortoptionen `-storepass`, `-keypass`, `pass:` oder Passwort-Umgebungsvariablen für Produktionssignierung benutzen; keine Eingaben über `echo`, Shell-History, Screenshots oder Logs übertragen. Nicht mit `set -x` arbeiten. Dateirechte auf 600 prüfen. Zwei verschlüsselte Offline-Backups an getrennten geschützten Orten anlegen, Wiederherstellbarkeit lokal prüfen und Passwort getrennt verwahren. Kein alter kompromittierter Keystore wird benötigt oder verwendet.

Den **öffentlichen** Zertifikatsfingerabdruck lokal anzeigen (Passwort wird wieder interaktiv abgefragt):

```sh
keytool -list -v -storetype PKCS12 \
  -keystore "$HOME/Library/Application Support/Helper-Signing/production.p12" \
  -alias helper-production
```

Den SHA-256-Wert des Zertifikats notieren, nicht mit dem APK-Dateihash verwechseln. Eine separat gesicherte öffentliche Zertifikatskopie beziehungsweise ein unabhängig dokumentierter Fingerabdruck dient zur späteren Eigentümerbestätigung.

## Unabhängige Eigentümerbestätigung — aktuell absichtlich gesperrt

`release/production-policy.json` enthält `UNAPPROVED`, keinen Fingerabdruck und keine Bestätigung. Der Prüfer lehnt damit jede APK ab, auch eine technisch gültige neue Signatur.

Der Eigentümer muss den öffentlichen Wert **separat prüfen und ausdrücklich bestätigen**, beispielsweise mit öffentlicher Zertifikatskopie/Fingerabdruck auf einem zweiten vertrauenswürdigen Gerät, und ihn in einem getrennt geprüften Policy-Commit festhalten. Erst dann `status: OWNER_CONFIRMED`, den exakten `certificate_sha256` und einen nachvollziehbaren `owner_confirmation`-Verweis setzen. Keine automatische Übernahme aus dem gerade verwendeten Keystore, einer Testsignatur, EVP_CERT_SHA256 oder der zu prüfenden APK. Eine fremde oder provisorische Policy-Datei darf nicht als Produktionsfreigabe dienen. Der Prüfer kann die tatsächliche menschliche Bestätigung nicht kryptografisch beweisen; das ist eine ausdrückliche Eigentümer-/Reviewgrenze.

## Später: ausgerichtete APK lokal signieren

Nur den überprüften neuen Keystore benutzen. Die offiziellen SDK-Werkzeuge müssen aus dem oben festgelegten SDK stammen. Die folgende Form verwendet **interaktive Passwortabfragen**, keine Passwörter in Argumenten oder Skripten:

```sh
apksigner sign \
  --ks "$HOME/Library/Application Support/Helper-Signing/production.p12" \
  --ks-key-alias helper-production \
  --v1-signing-enabled false --v2-signing-enabled true \
  --v3-signing-enabled false --v4-signing-enabled false \
  --out "$HOME/Helper-Releases/NEW_EMPTY_BUILD_DIRECTORY/helper-signed.apk" \
  "$HOME/Helper-Releases/NEW_EMPTY_BUILD_DIRECTORY/helper-unsigned-aligned.apk"
```

Kein `--next-signer`, kein `--lineage`, kein alter Schlüssel. V2 deckt Android ab API 26 ab. Die Prüfung weist v1-Signaturen und **sämtliche v3/v3.1-Blöcke** zurück; damit akzeptiert dieser eng begrenzte Option-A-Prozess keine darin enthaltene Rotationshistorie. Das ist eine bewusste Formatregel, keine neue Kryptografie. Die kryptografische Prüfung übernimmt ausschließlich das offizielle `apksigner`.

## Unabhängig prüfen, erst danach Prüfsumme verwenden

Prüfung mit unverändertem, geprüftem Toolstand und **separat bestätigter** öffentlicher Policy durchführen; bei Bedarf auf einem zweiten vertrauenswürdigen Rechner, der keinen privaten Schlüssel erhält:

```sh
python3 tools/verify-release.py \
  "$HOME/Helper-Releases/NEW_EMPTY_BUILD_DIRECTORY/helper-signed.apk" \
  --version-code OWNER_CHOSEN_CODE --version-name OWNER_CHOSEN_NAME
```

Der Prüfer erzwingt die Policy, gültige SDK-Signatur, genau einen bestätigten Signierer, Ausschluss des kompromittierten Zertifikats, v2 ohne v1/v3/v3.1/Lineage, Produktions-ID, API 26, targetSdk 34, effektives `debuggable=false`, Version und Alignment. **Nur bei Erfolg** schreibt er `helper-signed.apk.sha256`. Auf dem Mac zusätzlich `shasum -a 256 helper-signed.apk` ausführen und vergleichen. Die Prüfsumme identifiziert Bytes, ersetzt aber weder Signatur noch Veröffentlichungsfreigabe.

Für ein **späteres reguläres Update** ergänzen:

```sh
python3 tools/verify-release.py NEXT_SIGNED.apk \
  --version-code NEXT_CODE --version-name NEXT_NAME --previous-apk PREVIOUS_APPROVED_NEW_SIGNER.apk
```

Beide APKs müssen die unabhängig bestätigte Policy erfüllen; der versionCode muss steigen und der Signierer identisch sein. Dies ist ausdrücklich kein Updatepfad von der kompromittierten Installation: dort bleibt vollständige Deinstallation, frische Installation und erneute Browserkopplung gemäß [SIGNING-MIGRATION.md](SIGNING-MIGRATION.md) erforderlich.

## Was noch aussteht

Gezielte Tests verwenden ausschließlich temporäre Wegwerfidentitäten und temporäre Fixture-Policy-Dateien. Sie signieren nur Testprodukte mit Produktions-Paket-ID zur Prüfung und laden diese niemals als installierbare Artefakte hoch. Der echte Produktionsfingerabdruck bleibt unverändert unbestätigt. CI, Linux-SDK-Tests oder eine neue Signatur ersetzen weder den echten macOS-Passwortdialog noch Samsung-/Installations-/Updateprüfungen. Kein APK, Release oder Tag wird hier veröffentlicht. Nach Astra-Review und separater Freigabe liegen Schlüsselgenerierung, Offline-Backups, unabhängige öffentliche Fingerabdruckbestätigung und reale Gerätetests beim Eigentümer.
