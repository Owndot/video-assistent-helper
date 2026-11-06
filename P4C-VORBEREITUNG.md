# P4c – Vorbereitung des Samsung-Tests (HLS über die Test-App `de.evp.helfer.p4test`)

Stand der Vorbereitung. Produktion bleibt **UNAPPROVED**. Grüne Tests sind keine Sicherheitsfreigabe.
Nachtrag P4c: Der Eigentümer hat eine **temporär signierte** Test-APK für den kontrollierten Samsung-Test freigegeben
(Abschnitt 6); der dauerhafte P4-Schlüssel (Abschnitt 3) entsteht später und unverändert auf dem Mac.

Unverändert und unberührt: `de.evp.helfer` und `de.evp.helfer.channeltest` samt Daten und Kopplungen.

## 1. Entscheidungen des Eigentümers (übernommen)

- **Android 10 / API 29, MediaStore `SIZE` = 0:** vorläufig hingenommen *nur* als dokumentierte Einschränkung für die
  P4c-Gerätetests auf dem Samsung mit Android 17. **Keine Freigabe für Android 10.** Der rote Gerätetest
  `Mp4DownloadsInstrumentedTest#publishesThroughMediaStoreAndReadsBack` (API 29) bleibt sichtbar, die Prüfung bleibt
  unverändert, es entsteht kein zweiter MediaStore-Eintrag. Tatsächliche Länge, Integrität, Abspielbarkeit,
  Veröffentlichung und Wiedererkennung nach Neustart werden weiter geprüft (auch auf API 29 grün).
  → Offener Befund **P4B-A10-SIZE** für eine spätere, eigene Korrektur (siehe `DOWNLOADS-P4B.md` §7).
- Separate Test-App `de.evp.helfer.p4test` und separate P4-Testerweiterung (ID `pcccpjllneffccpenbepmfbkjedllkkf`).
- Der dauerhafte P4-Testschlüssel entsteht **nur auf dem Mac des Eigentümers**. Privater Schlüssel, Keystore-Datei und
  Passwort gehen nie an Claude, GitHub, CI oder einen Cloud-Dienst; übernommen wird nur der öffentliche Fingerabdruck.

## 2. Vor dem Gerätetest korrigiert (Android 17 / Samsung)

| Punkt | Befund | Maßnahme |
|---|---|---|
| CPU bei ausgeschaltetem Bildschirm | Der Vordergrunddienst hält den Prozess, nicht die CPU. Lange Downloads hätten bei Bildschirm aus stocken und als „unterbrochen“ enden können. | Teilweise CPU-Sperre nur solange ≥ 1 Download läuft (MP4 oder HLS), 30-min-Fenster, bei jedem Fortschritt verlängert; danach sofort frei. Berechtigung `WAKE_LOCK`. JVM-Test + 3 Mutanten, Gerätetest (API 26/29/34/37). |
| Beschreibung des Vordergrunddienstes | `specialUse`-Untertyp nannte nur Helligkeit/Lautstärke. | Nennt jetzt auch vom Nutzer gestartete Downloads. |
| Android 17 im Emulator | Bisher API 26/29/34. | **API 37 versucht, nicht testbar:** das Emulator-Abbild stürzt auf den CI-Runnern vor dem ersten Test ab (surfaceflinger: `Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma`; mit Gast-Rendering GL-Kontextfehler, danach „Broken pipe“ des Paketdienstes). Behoben auf unserer Seite: Bootzeit, Speicher, Datenpartition, Bereitschaftsprüfung (`tools/wait-device-ready.sh`), Absturzprotokoll. Kein App-Befund; Android 17 prüft erst der Gerätetest. |

Geprüft, ohne Änderungsbedarf: `targetSdk 34` → die Zeitgrenzen für `dataSync`/`mediaProcessing` (ab targetSdk 35)
und die Start-Sperren aus `BOOT_COMPLETED` für bestimmte Typen gelten nicht; `specialUse` hat keine Zeitgrenze.
Netz bleibt im Doze-Modus für Prozesse mit Vordergrunddienst erhalten. Emulator API 26/34: 45/45 (inkl. CPU-Sperre),
API 29: 44/45 (nur P4B-A10-SIZE); macOS-Signierweg auf einem macOS-15-Runner mit Bordmitteln zweimal grün
(mit `sha256sum` und mit `shasum`-Rückfall), alle 14 Ablehnungen greifen. Port 47899 fail-closed (belegt → kein Kanal,
kein Ausweichport). Nicht aus der Ferne prüfbar: Samsung-eigenes Energiesparen („Apps im Ruhezustand“, „Apps im
Tiefschlaf“) und Android-17-Änderungen jenseits des Emulators → im Gerätetest (Schritte S2, T11).

## 3. Signieren auf dem Mac – genaue Befehle

Voraussetzung einmalig (Homebrew vorhanden; sonst JDK 17 und Android-Kommandozeilenwerkzeuge von den offiziellen Seiten):

```sh
xcode-select --install                       # git (falls noch nicht vorhanden)
brew install --cask temurin@17               # JDK: keytool, java
brew install --cask android-commandlinetools # sdkmanager
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
sdkmanager --sdk_root="$ANDROID_HOME" "build-tools;34.0.0"
"$ANDROID_HOME/build-tools/34.0.0/apksigner" --version   # muss eine Version ausgeben
```

Skripte genau in dem Stand holen, den Claude nennt (`<SHA>` aus der Nachricht, 40 Zeichen):

```sh
mkdir -p ~/src && cd ~/src
git clone https://github.com/Owndot/video-assistent-helper.git
cd video-assistent-helper
git fetch origin feature/c3-browser-downloads
git checkout --detach <SHA>
git log -1 --format=%H                       # muss genau <SHA> ausgeben
```

**A. Schlüssel erzeugen (einmalig, außerhalb des Repositorys, nicht in iCloud):**

```sh
mkdir -p ~/P4-Signing && chmod 700 ~/P4-Signing   # Home-Ordner selbst wird nicht von iCloud synchronisiert
tmutil addexclusion ~/P4-Signing                   # nicht in Time Machine; Sicherung gezielt unter B
cd ~/src/video-assistent-helper
bash tools/p4test/keygen.sh ~/P4-Signing
```

`keytool` fragt zweimal nach dem Keystore-Passwort, das Skript danach ein drittes Mal (nur zum Auslesen des
Fingerabdrucks). Passwort lang und zufällig wählen (z. B. von einem lokalen Passwortmanager erzeugen lassen). Ausgabe:
`certificate_sha256=<64 Zeichen>`. Das Skript verweigert CI, Git-Ordner, Cloud-Ordner und ein Überschreiben.

**B. Privaten Schlüssel und Passwort getrennt sichern:**

- Keystore: verschlüsselter USB-Stick (Festplattendienstprogramm → „APFS (verschlüsselt)“), z. B. `/Volumes/P4-BACKUP`:
  ```sh
  cp ~/P4-Signing/helper-p4-test.p12 /Volumes/P4-BACKUP/
  shasum -a 256 ~/P4-Signing/helper-p4-test.p12 /Volumes/P4-BACKUP/helper-p4-test.p12   # beide Werte gleich
  diskutil eject /Volumes/P4-BACKUP
  ```
- Passwort: getrennt vom Stick – in einem **nur lokalen** Passwortmanager-Tresor (nicht iCloud-Schlüsselbund, keine
  Cloud-Synchronisierung) **oder** auf Papier an einem sicheren Ort. Nie zusammen mit der `.p12` aufbewahren.
- Nicht in Mail, Chat, Notizen-Apps, iCloud Drive, Dropbox o. Ä. ablegen.

**C. Nur den öffentlichen Fingerabdruck übergeben:**

```sh
keytool -list -v -keystore ~/P4-Signing/helper-p4-test.p12 -storetype PKCS12 -alias helper-p4-test | grep -E "Owner|SHA256:"
```

`Owner` muss `CN=Helper P4 TEST ONLY - durable` sein. Den Wert aus `certificate_sha256=…` (aus Schritt A, klein, ohne
Doppelpunkte; dieselben Hex-Zeichen wie `SHA256:` hier) an Claude schicken – sonst nichts. Claude legt ihn in
`release/p4test-signer.json` fest. Danach prüfen:

```sh
cd ~/src/video-assistent-helper && git fetch origin feature/c3-browser-downloads
git checkout --detach <NEUER_SHA>
grep certificate_sha256 release/p4test-signer.json         # muss genau deinen Fingerabdruck zeigen
```

**D. Später gelieferte unsignierte P4-Test-APK prüfen und signieren** (erst nach Freigabe der Lieferung):

```sh
mkdir -p ~/P4-Delivery && chmod 700 ~/P4-Delivery            # gelieferte Dateien hierher (nicht ins Repository)
cd ~/P4-Delivery && shasum -a 256 -c SHA256SUMS              # Prüfsumme der gelieferten APK
cd ~/src/video-assistent-helper
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
bash tools/p4test/sign.sh ~/P4-Delivery/helper-p4test-unsigned.apk ~/P4-Signing/helper-p4-test.p12 ~/P4-Delivery/helper-p4test-signed.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs ~/P4-Delivery/helper-p4test-signed.apk | grep SHA-256
```

`sign.sh` fragt das Passwort ab (nie als Argument, nicht in der Shell-Historie) und lehnt ab: anderes Paket als
`de.evp.helfer.p4test`, debugfähige oder bereits signierte Eingabe, fehlende/zusätzliche Bibliotheken, Zertifikat ≠
Festlegung, Schlüssel oder Ausgabe in Git- oder Cloud-Ordnern. Ausgabe am Ende: `SIGNED de.evp.helfer.p4test
versionCode=… cert_sha256=<dein Fingerabdruck> apk_sha256=…`. Spätere Updates: derselbe Schlüssel, höherer Versionscode.
Läuft unter macOS mit Bordmitteln (bash 3.2, LibreSSL, `shasum`) – in CI auf einem macOS-Runner geprüft.

Schlüssel verloren? Nie einen Produktions- oder Wegwerfschlüssel nehmen; neue P4-Identität erzeugen, nur p4test
deinstallieren und neu koppeln.

## 4. Testseite und Testerweiterung (in Kadri vorbereitet)

- Seite `tests/web/public/p4-hls.html` mit eigenem synthetischem Material (`tests/web/public/p4/`, 14 MB, Prüfsummen):
  T1–T7 unterstützt, T14a AES-128 und T14b Live müssen abgelehnt werden. Gegen die echte HlsEngine geprüft (alle
  unterstützten Fälle planen und laden; AES → `encrypted`, Live → `live`). Öffentlich über einen Commit-Link wie die
  C3-Seite (Link in der P4c-Nachricht).
- Testerweiterung „Kadri P4 TEST“: `tools/p4test/pack-extension.py` (nur Name, Beschreibung, öffentlicher Schlüssel
  verschieden), in CI aus zwei Build-Wegen bytegleich und im Wegwerf-Chromium mit fester ID und getrennten Daten.

## 5. Samsung-Testmatrix (Android 17, Vivaldi/Chromium mit „Kadri P4 TEST“, nur nach Freigabe)

Vorbereitung S1–S3: **S1** `channeltest` „Stopp erzwingen“ (nicht deinstallieren), C3-Testerweiterung deaktivieren (nicht
entfernen). **S2** p4test: Benachrichtigungen erlauben, Akku „Nicht eingeschränkt“, nicht in „Apps im Ruhezustand/Tiefschlaf“.
**S3** p4test starten, mit „Kadri P4 TEST“ koppeln. Jeder Fall: Seite öffnen, Fall wählen, Kadri-Player, ⬇ → „Stream speichern …“.

| ID | Fall | Erwartung | Ergebnis |
|---|---|---|---|
| T1 | HLS-TS, Ton getrennt | 1 Option 360p · de; Datei 20 s, Blitz = Piepton, in Galerie/Player abspielbar | |
| T2 | HLS-TS, Ton im Video | 1 Option „Ton im Stream“; Datei mit Ton | |
| T3 | fMP4, Ton getrennt | wie T1 | |
| T4 | fMP4, Ton im Video | wie T2 | |
| T5 | Qualitätsauswahl | 2 Optionen 360p/180p; Datei hat die gewählte Auflösung | |
| T6 | Audiospuren | 2 Optionen de/en; de = hoher, en = tiefer Piepton – genau die gewählte Spur | |
| T7 | Abbruch | T7-Fall starten, nach ~5 s abbrechen → „abgebrochen“, keine Datei, keine Teildatei | |
| T8 | Abbruch + Neustart | starten, abbrechen, p4test „Stopp erzwingen“, neu öffnen → bleibt abgebrochen | |
| T9 | Wiederaufnahme nach Netzabbruch | starten, Flugmodus an → „unterbrochen“; Flugmodus aus → „Fortsetzen“ → fertig, Datei vollständig | |
| T10 | Wiederaufnahme nach App-Ende | starten, p4test „Stopp erzwingen“, neu öffnen, „Fortsetzen“ → fertig | |
| T11 | Bildschirm aus | starten, Bildschirm sofort aus, 3 min warten → fertig, nicht „unterbrochen“ | |
| T12 | Parallel MP4 + HLS | C3-MP4 (C3-Seite) und T7 gleichzeitig → beide fertig | |
| T13 | Dritter Download | während zwei laufen, dritten starten → „belegt“ | |
| T14 | Nicht unterstützt | T14a → „verschlüsselt“, T14b → „Live“, keine Datei | |
| T15 | Wiedererkennung | nach T1 Handy neu starten → Download weiter „fertig“, kein erneuter Abruf | |
| T16 | Aufräumen | p4test deinstallieren, P4-Testerweiterung entfernen; channeltest/C3-Paar unverändert | |

Für jeden Fall notieren: PASS/FAIL, angezeigte Dateigröße und Dauer, bei Fehlern den angezeigten Grund. Keine privaten
Adressen, Kopplungscodes oder Schlüssel in Notizen.

## 6. P4c-Durchführung ohne Mac: temporär signierte Test-APK (Freigabe des Eigentümers)

Freigegeben für den kontrollierten Samsung-Test, keine Produktionsfreigabe. Workflow
`.github/workflows/p4-test-package.yml` (läuft nur bei Änderung der eigenen Datei auf diesem Branch):

- Baut exakt den Quell-Commit `9da4434` (sonst nur die Workflow-Datei bzw. Doku verschieden), nur wenn dessen CI grün
  ist; einzige zugelassene Ausnahme: genau ein Fehlschlag auf API 29, genau `publishesThroughMediaStoreAndReadsBack`
  mit `SIZE … but was:<0>` (P4B-A10-SIZE).
- Unsignierte P4-Testapp aus zwei Pfaden, alle APK-Einträge bytegleich.
- **Getrennter, temporärer Testsignierweg:** Wegwerf-Schlüssel nur in diesem Lauf (RSA-3072, 2 Tage, DN
  `CN=Helper P4 TEST ONLY - throwaway`, zufälliges Passwort nur im Prozess-Umfeld), einmal benutzt über die
  fail-closed Prüfungen von `tools/p4test/sign.sh` mit einer Wegwerf-Festlegung im Wegwerf-Ordner, danach Schlüssel,
  Festlegung und Passwort gelöscht; geprüft, dass kein Schlüsselspeicher übrig ist und `release/p4test-signer.json`
  (`null`, `KEY_NOT_YET_CREATED`) sowie `tools/p4test/**` unverändert sind. Nie Produktion, nie der alte Signierer
  `ec17fab1…` (zusätzlich abgewiesen).
- Geprüft vor und nach einem Installationstest auf einem Wegwerf-Emulator (API 34): Paket, Bezeichnung, Versionscode,
  nicht debugfähig/testOnly, `allowBackup=false`, genau die 12 Manifest-Berechtigungen, genau eine Signatur (v2+v3)
  mit dem Wegwerf-Zertifikat dieses Laufs, Kanalbibliothek nur für arm64-v8a/x86_64 ohne Testinitiator; Dienst läuft,
  Kanal lauscht nur auf `127.0.0.1:47899`, alter Port 47811 zu; danach deinstalliert, Paketliste wie vorher.
- Auslieferung: `helper-p4test.apk`, `SHA256SUMS`, `p4test-evidence.json` (APK- und Zertifikats-SHA-256, Gültigkeit,
  Helfer- und Kadri-Commits, Erweiterungs-ID, Ablaufdatum). Öffentliches Actions-Artefakt, **2 Tage** Aufbewahrung.
- Gegenstück in Kadri: `.github/workflows/p4-test-extension.yml` (Testerweiterung „Kadri P4 TEST“, Kadri-Commit mit
  `helper_commit=9da4434`).

**Folge des Wegwerf-Schlüssels:** Der Schlüssel existiert nach dem Lauf nicht mehr. Jede spätere p4test-APK (neuer
Wegwerf-Schlüssel oder die dauerhafte Mac-Identität) hat eine andere Signatur: Android lehnt sie als Update ab. Dann
**nur `de.evp.helfer.p4test` deinstallieren** (seine Daten, Kopplung und laufenden Downloads gehen verloren; fertige
Dateien in `Download/Video-Assistent` bleiben) und mit „Kadri P4 TEST“ neu koppeln. `de.evp.helfer`,
`de.evp.helfer.channeltest` und die C3-Testerweiterung sind davon nie betroffen.

**Portregel:** channeltest und p4test benutzen denselben Port 47899. Es lauscht nur die zuerst gestartete App, die
andere meldet „Port 47899 ist belegt“. Für P4-Tests channeltest „Stopp erzwingen“ (auch nach jedem Neustart des Handys,
weil beide beim Hochfahren starten), danach p4test öffnen. Den Kopplungscode nur in den Einstellungen von „Kadri P4
TEST“ eingeben, nie in der C3-Testerweiterung (sonst würde deren Kopplung ersetzt).

## 7. P4c-2: automatische Wiederaufnahme, Downloads unabhängig vom Player (nach dem ersten Samsung-Test)

**Befund „Player sofort geschlossen“:** Die Download-Verwaltung läuft im Service Worker, ein bestätigter Auftrag im
eigenen Thread der Helfer-App; beides hängt nicht am Player. Nachgestellt (Desktop-Helfer, echtes Chromium): Schließen
0/100/500 ms nach dem Tipp, Seite verlassen, Tab-Wechsel – die Datei wird jedes Mal fertig. Gefunden und behoben
wurden die Schwachstellen drumherum: (1) Netzabbruch ⇒ HLS „unterbrochen“ bzw. MP4 „fehlgeschlagen“ (die drei
MP4-Fortsetzungen liefen ohne Pause hintereinander und scheiterten alle im Flugmodus); (2) ein unbestätigter Start
wurde erst geklärt, wenn der Player wieder fragte; (3) ein im Speicher veraltetes „läuft“ (Player war zu, niemand fragte
nach) blockierte einen späteren erneuten Download derselben Datei („Läuft schon“). Die genaue Geräte-Ursache des
T-Befunds ist ohne Gerätedaten nicht belegt; der Nachtest prüft genau diesen Fall.

**Automatische Wiederaufnahme (`dl/Retry`, MP4 und HLS):**
- Pausen 2 s, 5 s, 15 s, 30 s, 1, 2, 5, 10 min (höchstens 8 Versuche ohne Fortschritt; HLS insgesamt höchstens 64).
- Ohne Netz (Android `ConnectivityManager`, Standardnetz mit INTERNET): warten ohne Anfrage und ohne Versuch zu
  verbrauchen, höchstens 2 h; Netz zurück ⇒ nach 1 s weiter. Status `"wait":"offline"|"retry"`, Benachrichtigung
  „Wartet auf Internet – setzt automatisch fort“ mit „Abbrechen“.
- Nur bei Netzwerk/IO, Zeitüberschreitung, 408, 429, 500/502/503/504. Nie bei Abbruch (gewinnt immer, beendet jedes
  Warten in ≤ 250 ms), Dienstende, 401/403/404/410 (abgelaufene Signatur, fehlende Berechtigung, entfernt), geänderter
  Quelle, Sicherheitsprüfung oder ohne starken ETag (MP4).
- Jeder HLS-Versuch ist eine vollständige Wiederaufnahme: Listen neu laden und vergleichen, früher geladene Segmente
  nur mit Länge, SHA-256 und starkem ETag übernehmen. Neu geladen wird höchstens das beim Abriss gerade laufende Segment.
- „Fortsetzen“ in der Benachrichtigung eines unterbrochenen Streams, solange die App-Instanz lebt (Quelle nur im
  Arbeitsspeicher).

**Nach einem Prozessende (T10)** gibt es keine gespeicherte Quelle (Journal ohne Adresse, unverändert). Fortsetzen:
Webseite öffnen und ⬇ am Video bzw. im Player „Fortsetzen“ (neuer Plan, dieselbe Kennung, gleiche Prüfungen). Eine
selbstständige Wiederaufnahme bräuchte eine neue, dauerhafte Speicherung der Quelle – **nicht umgesetzt**, Entwurf und
Freigabeantrag unten.

### Entwurf zur Freigabe: verschlüsselte, zeitlich begrenzte Quelle für die Wiederaufnahme nach Prozessende

- Gespeichert wird nur die vom Nutzer gewählte Hauptadresse (HLS-Liste bzw. MP4-Datei) eines laufenden oder
  unterbrochenen Auftrags; Segmentadressen werden wie heute aus der Liste abgeleitet.
- AES-256-GCM mit einem nicht exportierbaren Schlüssel im Android-Keystore (nur diese App, kein Backup, kein Export),
  Auftragskennung als zusätzliche Authentisierung (AAD); Datei in `noBackupFilesDir` neben dem Journal, nie im Journal,
  Kanal, Log oder in einer Benachrichtigung.
- Ablauf: höchstens 24 h (Vorschlag), sofort gelöscht bei fertig, fehlgeschlagen, Abbruch (Abbruchwunsch zuerst) oder
  wenn der Auftrag älter wird; beim Löschen der App-Daten mit weg.
- Nach dem Neustart dieselben Prüfungen wie heute (Listen-Hash, Fingerabdruck, starker ETag); 401/403 (Token abgelaufen,
  andere IP) ⇒ keine Automatik, Hinweis „im Video fortsetzen“.
- Risiken: Zugangs-Token in der Adresse liegt bis zu 24 h verschlüsselt auf dem Gerät (lesbar nur für ein Gerät mit
  Zugriff auf diesen Keystore-Schlüssel, z. B. gerootet/kompromittiert); Abrufe Stunden später können vom Server
  abgelehnt werden; zusätzliche Speicherlogik (nur Plattform-Krypto, keine Eigenentwicklung).
- Offen für die Entscheidung des Eigentümers: Freigabe ja/nein, Gültigkeit (24 h / 6 h), nur HLS oder auch MP4.
