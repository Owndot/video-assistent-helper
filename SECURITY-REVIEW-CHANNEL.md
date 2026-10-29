# Unterlage für den unabhängigen Security-Review: Steuerkanal Helper ↔ Kadri

Gilt für zwei Draft-PRs, die **zusammen** zu prüfen sind (identische Datei in beiden Repositories):

| | PR | Basis | Zweig |
| --- | --- | --- | --- |
| Helper | Owndot/video-assistent-helper#22 | `feature/helper-channel-dummy` (Stufe 1, geprüfter PoC) | `feature/helper-channel-control` |
| Kadri | Owndot/Kadri#10 | `main` (enthält Stufe 1 aus #7 und `main` mit #8/#9) | `feature/helper-channel-control` |

Status: **nicht freigegeben.** `release/production-policy.json` bleibt `UNAPPROVED`; Helper-Issues #3 (kompromittierte
Signatur) und #4 (Helper-Authentisierung) bleiben offen. Keine Releases, keine produktiven APKs, keine Beta. Der Autor
dieser Änderungen gibt keine Sicherheitsfreigabe für die eigene Arbeit.

## 1. Was geprüft werden soll

Der bisherige Klartext-Weg (`127.0.0.1:47811`, statischer Header `X-EVP-Key`, Kopplungsseite `/pair#SCHLÜSSEL`,
Downloads mit Medien-URL/Cookies im Klartext) wird vollständig entfernt. Ersatz ist ausschließlich der bereits geprüfte
Kanal (SPAKE2-Kopplung RFC 9382 über `pakery-spake2` 0.6.2, `Noise_KK_25519_ChaChaPoly_SHA256` über `snow` 0.10.0,
Rust-Kern als JNI/WASM, Protokoll-IDs unverändert) auf `127.0.0.1:47899`, und darüber **nur fünf Steuerbefehle**:
`status`, `beat`, `end`, `vol` (0…1, optional), `bright` (0,004…1). Downloads über den Helper sind vorübergehend aus.

**Neu gegenüber dem bereits geprüften Stand** (Schwerpunkt des Reviews):
1. JNI-Bindung `hostRevoke` (Einzelwiderruf) – ruft das vorhandene `Host::revoke`.
2. Einbau des Kanal-Servers in die echte Helper-App (`ChannelServer`, `ControlCommands`, `HelferService`, UI).
3. Einbau in die normale Kadri-Chrome-Fassung: Nachrichtenweg Inhaltsskript → Hintergrund → Kanal, Verwaltung über
   Einstellungsseite/Menü, DNR-Regel 40 statt Regel 50.
4. Abschalten des Helfers im Userscript und in Kadri für Firefox; Entfernen aller Reste des Klartext-Wegs.

## 2. Commits

**Helper #22** (gegen `feature/helper-channel-dummy`):

| Commit | Inhalt |
| --- | --- |
| `467cd2d` | Kern: JNI `hostRevoke` (nur Feature `jni`; WASM byte-identisch) |
| `af23431` | App: `ChannelServer`, `ControlCommands`, Identität/Registrierungen aus dem PoC, Widerruf, UI, Testvariante `de.evp.helfer.channeltest`; `MiniServer` (47811) nur noch Testgerüst |
| `5bc1a26` | Userscript (D2): `helper()` sendet nichts, alter Schlüssel gelöscht, keine Kopplungsseite |
| `5feca26` | Tests: Desktop-Prüfungen gegen den echten Server, Mutationsproben, Emulator-Tests, CI |
| `f7e89e3`, `5694f6a` | gemeinsames `src/080`: `helper()` gibt nur den Pfad an `KADRI_HELPER` (nur Kadri-Chrome), Einstellung „Helper-App“ bleibt wirksam |
| `d6d60d8` | Test-Harness: fremder Dienst fälscht formal gültige Handshake-Antworten |
| `0a02402` | Emulator-Test FLAG_SECURE auf frischer Activity |
| `3cbc063` | `bundle.py` erwartet die aktuelle Userscript-Version 19.30 |
| (dieser Commit) | Review-Unterlage, Gerätepaket-Workflow mit doppelter Upload-Sperre |

**Kadri #10** (gegen Stufe 1 `feature/helper-channel-dummy`; erster Elternteil):

| Commit | Inhalt |
| --- | --- |
| `e24a0ba` | Merge von `main` (#8, #9: DNR-Regeln nur für Kadris eigene Anfragen) |
| `b093b5e` | Kanal in `dist/chrome`, Broker, `KADRI_HELPER`, Regel 40, Entfernen des Klartext-Wegs, UI |
| `a064d18` | Tests gegen den echten Server, Ersatz der Alt-Tests, CI |
| `7ca4459` | Dokumentation |
| `0ba5537`, `94462b0` | Testreihenfolge/Anzahl (CI) |
| (dieser Commit) | Review-Unterlage, Gerätepaket-Workflow mit doppelter Upload-Sperre |

Stufe 1 (Kadri #7, schon unabhängig geprüft, Entscheidung A): `439df7c` … `5845abb`.

## 3. Geänderte Dateien (sicherheitsrelevant)

**Helper**
- `poc/pake-channel/core/src/jni_api.rs` – `Java_poc_Core_hostRevoke`
- `app/src/main/java/de/evp/helfer/ChannelServer.java` – Loopback-Server, Browser-Schutz, Routen, Kernsperre
- `app/src/main/java/de/evp/helfer/ControlCommands.java` – strenger Parser, feste Status-JSON
- `app/src/main/java/de/evp/helfer/HelferService.java` – Start (fail-closed), Kopplungsfenster, Registrierungen, Widerruf, Zurücksetzen, Gerätebefehle
- `app/src/main/java/de/evp/helfer/{IdentityStore,RegistryStore,SnapshotWriter,HttpRequestReader}.java` – aus dem PoC (nur Paket/AAD-Kontext)
- `app/src/main/java/de/evp/helfer/MainActivity.java`, `Keys.java` – Code-Anzeige (FLAG_SECURE), Verwaltung, Löschen des Altschlüssels
- `app/src/main/AndroidManifest.xml`, `res/xml/*` – keine Sicherung/Übertragung
- `app/build.gradle`, `app/src/channeltest/` – Testvariante, Bibliothek nur dort
- `src/080-helper-app.js`, `userscript/video-assistent.user.js` – D2
- Tests/CI: `tests/java/channel/*`, `app/src/androidTestChanneltest/*`, `tests/security/helper_disabled.js`, `.github/workflows/helper-channel.yml`, `.github/workflows/channel-device-package.yml`, `.github/workflows/build.yml` (kein Debug-APK-Upload für diesen Zweig)

**Kadri**
- `channel/channel-service.js` – Broker (Nachrichtenprüfung, Absenderprüfung)
- `channel/channel-session.js` – `controlMessage()`, `command()`, Wiederholungsregel
- `chrome/prelude.js` – `KADRI_HELPER` (Pfad → Befehl)
- `chrome/background.js`, `firefox/background.js` – Regel 40, Entfernen von Regel 50 und `HELPER_ORIGIN`-Ausnahmen, Laden des Kanals
- `chrome/options.js`, `chrome/popup.js`, `firefox/popup.js`, `firefox/options.js`, `*/common-ui.js` – UI
- `chrome/manifest.json` – CSP `'wasm-unsafe-eval'`
- `src/080-helper-app.js` – identisch mit dem Helper-Repository
- `tools/build.js`, `vendor/kadri-pairing-poc/*` – Kanal nur in `dist/chrome`; Herkunft des WASM
- Tests/CI: `tests/channel/*`, `tests/test_helper.py`, `tests/helper_route_check.js`, `tests/guard_check.js`, `tests/ff_net_check.js`, `tests/header_context_check.js`, `.github/workflows/ci.yml`, `.github/workflows/channel-device-package.yml`

## 4. Sicherheitsgrenzen

| Grenze | Durchsetzung | Belegt durch |
| --- | --- | --- |
| Nur der gekoppelte Browser steuert den Helper | Noise KK mit gepinnten Schlüsseln; Registrierung nur per SPAKE2 mit 8-stelligem Einmal-Code (120 s, ein Versuch) | ChannelChecks, Emulator, Kadri-Integration |
| Kein Klartext, kein statischer Schlüssel, kein alter Port | `ChannelServer` nur 47899; 47811 in der App entfernt; Kadri: Regel 50 entfernt, `guard()` ohne Ausnahme; Userscript/Firefox ohne Weg | Emulator „nothingListensOnTheLegacyPort“; Kadri Browser-/Python-Tests mit Zähl-Dienst auf 47811 (nie erreicht) |
| Nur fünf Befehle, keine URLs/Cookies/Medien | `ControlCommands.parse` (Helper) und `controlMessage` (Kadri) gleich streng; `KADRI_HELPER` lässt nur fünf Pfade durch | Parser-Tests, Mutationsproben, `helper_route_check.js`, Browser-Probe |
| Verwaltung nur durch den Nutzer | Kadri: `pair`/`forget` nur von `options.html`, `status`/`check` von `options.html`/`popup.html` (Prüfung über `sender.url`/`sender.origin`, vom Browser gesetzt); Helper: Widerruf/Zurücksetzen nur in der App | Browser-Probe („Content-Script: Verwaltung abgewiesen“, „Menü darf nicht koppeln“) |
| Keine stille Identitätsannahme | fremde Antwort → `identity-mismatch`, Widerruf/Reset → `rejected`; Pin unverändert; keine automatische Neukopplung; Pin erst nach bestätigtem Austausch (R4) | Integration/Browser „fremder Dienst“, Mutanten R4 |
| Netzwerk nur mit stehenden Schutzregeln | Kanal-Transport nur, wenn `netGuard()` die Regeln (inkl. 40) verifiziert hat | `channel_netguard_failure_check.js` |
| Regel 40 eng | `POST`, Regex auf vier Pfade, `tabIds [-1]` + `initiatorDomains` = eigene ID | `guard_check.js`, Browser-Test, Mutanten |
| Geheimnisse bleiben lokal | Kadri: IndexedDB des Erweiterungs-Ursprungs (nicht `storage.local`); Helper: Keystore-gebundene Identität, `allowBackup=false`, Extraktionsregeln; keine Codes/Schlüssel im Logcat | Browser-Probe (IndexedDB/Storage), Emulator-Logcat-Scan |
| Fehler schließen | ohne Identität/Bibliothek kein Kanal; Widerruf nicht gespeichert → Kanal aus; Kadri ohne Regeln → keine Anfrage | Emulator, ChannelChecks, netguard-Test |

## 5. Nachrichtenwege: Prüfung auf unberechtigte Aktionen

**Wer kann was senden?**

| Absender | Weg | Erreichbar | Nicht erreichbar |
| --- | --- | --- | --- |
| Webseite (auch Service Worker) | – (kein `externally_connectable`) | nichts in Kadri | alles; direkte Anfragen an 47899 scheitern am Helper-Gate (Preflight/Origin/Content-Type/Header) |
| Andere Erweiterung | `onMessageExternal` | nichts (kein Empfänger) | – |
| Kadris Inhaltsskript (jede Seite/jeder Frame) | `{t:'kadri-helper', op, v}` | die fünf Befehle in strenger Form | Verwaltung (`refused`), fremde Felder, andere Befehle, Werte außerhalb; Kanal-Daten (IndexedDB) |
| Kadris Inhaltsskript | `kadri-xhr`-Port | GET an öffentliche Adressen (Schutzregeln) | POST an den Kanal (`nur GET`), 47811/47899 (`eigenes Netz`) |
| Menü (`popup.html`) | `kadri-channel` | `status`, `check` | `pair`, `forget` |
| Einstellungsseite (`options.html`) | `kadri-channel` | `status`, `check`, `pair` (Code des Nutzers), `forget` | – |
| Lokale App/Prozess auf dem Gerät | TCP 47899 | Verbindungsaufbau (Verfügbarkeit, §7) | Befehle (kein Schlüssel), Registrierung (kein Code) |

**Was eine Webseite über Kadri indirekt auslösen kann:** Die Player-Oberfläche liegt in einem geschlossenen
Shadow-DOM; Gesten sind nicht von außen auslösbar. Eine Seite kann aber das Sichtbarkeitsereignis `kadri-vis`
auslösen. Dann sendet Kadri bei offenem Player die **vom Nutzer bereits gewählte** Helligkeit erneut bzw. bei
tatsächlich verborgenem Tab `end`. Neue Werte kann sie so nicht setzen; die Sendefolge ist auf einen offenen Befehl je
Art begrenzt. Bewertung: gering (Last, keine neue Wirkung).

**Kompromittierter Renderer (Content-Script-Prozess):** kann jeden **gültigen** Steuerbefehl senden (Helligkeit
0,004…1, Lautstärke 0…1, `end`) – keine Verwaltung, keine anderen Befehle, keine Daten aus dem Kanal-Speicher.
Bewertung: Belästigung möglich (Helligkeit/Lautstärke), keine Vertraulichkeits- oder Integritätsfolgen. Gleich wie
beim bisherigen Helper; eine Bindung an Nutzergesten ist bewusst nicht Teil dieser Stufe.

**Kopplung:** Nur die Einstellungsseite kann `pair` mit einem Code auslösen; der Code steht nur in der Helper-App
(FLAG_SECURE). Ein falscher Code verbraucht das Fenster. Auf Helper-Seite wird die Registrierung vor M3 gespeichert;
scheitert das, gibt es kein M3 (Test „unsavedPairingIsNeverConfirmed“).

**Einzelwiderruf:** nur in der Helper-App (Nutzer). `hostRevoke` beendet die Sitzungen genau dieser Registrierung; der
Widerruf ist erst nach dem Speichern erfolgreich (sonst Kanal aus). Kadri sieht danach `rejected` und koppelt **nicht**
automatisch neu. Andere Browser bleiben unberührt. Unbekannte IDs: wirkungslos; falsche Länge: Fehler ohne Wirkung.
Belegt: ChannelChecks, Emulator „revokeOneKeepsTheOthersAcrossRestart“, Kadri-Integration/Browser.

**Identitätspinning:** Handshake nur mit dem gepinnten Helper-Schlüssel. Fremder Dienst auf 47899 →
`tampered`/`identity-mismatch`, kein Befehl kommt an, er sieht nur Chiffretext (Zähl-/Markertest). Helper-Reset →
neue Identität, alte Pins abgewiesen; neue Kopplung meldet `helperChanged`.

**Neustarts:** Helper-Neustart verwirft Sitzungen, Registrierungen bleiben (Neustart-Erkennung über Boot-Zähler, R3).
Kadri baut nach HTTP 404 genau einmal neu auf, und nur für die fünf Befehle (absolute Werte/Abfragen – eine
Wiederholung bewirkt nichts anderes). Kadri-Worker-Neustart: Sitzung nur im Speicher, neuer Handshake; Pin bleibt.

**DNR-Regeln:** Regel 40 (allow, Prio 10) gilt nur für Kadris eigene Anfragen ohne Tab und nur für POST an die vier
Pfade; die Sperrregeln 51–58/70 (Prio 5) sperren alles übrige im eigenen Netz, auch 47811 und andere Pfade auf 47899.
Fremde Service Worker sind von keiner Kadri-Regel betroffen (#9). Kadris Einstellungsseite/Menü (eigener Tab) stellen
selbst keine Anfragen an den Helper – das tut nur der Hintergrund.

## 6. Verbleibende Risiken

| # | Risiko | Bewertung |
| --- | --- | --- |
| 1 | Verfügbarkeit: langsame lokale Clients (§7) | mittel (nur Verfügbarkeit) |
| 2 | R1/R2 aus dem PoC: wiederholtes HS1 verdrängt eine frische Sitzung; bekannte Sitzungs-ID erlaubt Abbruch | gering (nur Verfügbarkeit) |
| 3 | Kompromittierter Renderer kann gültige Steuerbefehle senden (§5) | gering |
| 4 | Seite löst über `kadri-vis` erneutes Senden bestehender Werte aus (§5) | gering |
| 5 | Kadris bestehende Ausnahme „Seite im eigenen Netz“: Seite auf `127.0.0.1` darf über Kadri GET an denselben Rechner | gering für den Kanal (GET → Gate 403); bestehendes Verhalten |
| 6 | Kopplung per 8-stelligem Code: Schulterblick/Mitlesen innerhalb von 120 s | gering (FLAG_SECURE, ein Versuch, Einzelwiderruf) |
| 7 | Kein unabhängiger Audit des Protokolls/der Bibliotheken | offen |
| 8 | Issue #3 (Signatur) / #4 offen; Testvariante nur wegwerf-signiert | Release-Blocker |

## 7. Verfügbarkeit: langsame lokale Clients (dokumentiert, nicht geändert)

Der Helper-Server hat 4 Worker und eine Warteschlange von 16; jede Verbindung muss ihre Anfrage innerhalb von 3 s
vollständig liefern (Lesefrist ab Annahme, F3), sonst wird sie getrennt. Darüber hinausgehende Verbindungen werden sofort
geschlossen.

Gemessen (Emulator, API 34): 24 lokale Clients, die je ein Byte alle 250 ms senden, werden spätestens nach etwa 3,1 s
getrennt; eine legitime Anfrage wurde nach etwa 3,2 s bedient.

Folge: Eine bösartige App auf dem Gerät, die fortlaufend neue langsame Verbindungen öffnet, kann die Worker belegen.
Kadris Befehle laufen dann in die Zeitgrenze (5 s) und gelten als „Helfer nicht erreichbar“; Helligkeit/Lautstärke
fallen auf den Normalzustand zurück (der Helper gibt die Helligkeit nach 30 s ohne Lebenszeichen selbst zurück).
**Keine** Auswirkungen auf Vertraulichkeit oder Integrität: Ohne Schlüssel kann die App keinen Befehl ausführen und
keine Registrierung anlegen. Voraussetzung ist bereits laufender fremder Code auf dem Gerät.

Mögliche Gegenmaßnahmen (nicht umgesetzt, bewusst ohne Architekturänderung; nur nach Rücksprache):
- kleiner Eingriff: kürzere Lesefrist für den Anfragekopf (z. B. 1 s) und Begrenzung gleichzeitiger halboffener
  Verbindungen;
- größer: nicht blockierender Server (NIO) oder Zuordnung der Verbindungen zu Apps (Android-UID über `/proc/net/tcp`)
  mit Begrenzung je App.

## 8. Testpakete für den Gerätetest (vorbereitet, nichts hochgeladen)

- Helper: `.github/workflows/channel-device-package.yml` baut `de.evp.helfer.channeltest` zweimal aus verschiedenen
  Pfaden (native Bibliotheken byte-identisch; APK-Inhalt ohne Signaturdateien identisch; Signatur je Bau mit
  Wegwerf-Schlüssel), prüft Paket/Name/Signierer/Sicherungsregeln/Bibliotheken, testet genau diese APK im Emulator.
- Kadri: `.github/workflows/channel-device-package.yml` baut `dist/chrome` mit Testnamen „Kadri TEST – Helfer-Kanal“
  zweimal als deterministisches ZIP (byte-identisch), prüft, dass nur der Name abweicht, und führt den Kanal-Browsertest
  gegen genau dieses Paket aus.
- Upload in beiden nur mit **zwei** Freigaben: committete Datei `.github/CHANNEL-DEVICE-ARTIFACTS-APPROVED` **und**
  Handstart mit Bestätigungswort. Sonst nur Textbericht.
- Auswirkungen auf bestehende Installationen: eigenes Paket bzw. eigene Erweiterungs-ID; überschreibt weder
  `de.evp.helfer` noch eine bestehende Kadri-Installation. Hinweise für den Test: die frühere PoC-Test-App
  `de.evp.pakepoc.test` vorher deinstallieren (gleicher Port 47899 → sonst „Port belegt“ bzw. fremde Identität);
  eine vorhandene Kadri-Installation während des Tests deaktivieren (sonst zwei Player auf jeder Seite).

## 9. Bitte an den Reviewer

1. Abschnitte 4–5 gegen den Code prüfen, insbesondere `channel-service.js` (Absender- und Formprüfung), `prelude.js`
   (`KADRI_HELPER`), `ChannelServer.route/gate`, `ControlCommands.parse`, `HelferService.revoke/startChannel`, Regel 40.
2. Bewertung der Risiken 1–6, besonders §7 (ob die Lesefrist vor dem Gerätetest gekürzt werden soll).
3. Prüfen, dass keine Spur des Klartext-Wegs übrig ist (App, Userscript, Kadri Chrome/Firefox).
