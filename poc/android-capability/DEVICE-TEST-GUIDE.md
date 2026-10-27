# Gerätetest – Anleitung (erst nach gesonderter Freigabe ausführen)

**Noch nicht ausführen.** Diese Anleitung gilt erst, wenn du die Installation auf deinem Gerät ausdrücklich freigegeben hast. Sie installiert zwei Wegwerf-Testkomponenten:

1. die Test-App **„TEST PAKE-PoC (kein Helper)“** (`de.evp.pakepoc.test`) – getrennt vom echten Helper `de.evp.helfer`, ohne Zugriff auf dessen Daten oder Schlüssel;
2. die Test-Erweiterung **„TEST – Kadri Android capability PoC (not Kadri)“** für Vivaldi.

Beide arbeiten nur mit Wegwerf-Testidentitäten und Dummy-Daten. Nirgends echte Medien, Cookies, URLs oder Konten verwenden.

## 0. Vorbereitung (auf deinem Mac, nicht auf dem Telefon)

Die Test-APK wird **nicht** veröffentlicht oder als CI-Artefakt bereitgestellt (öffentliches Repository). Du baust sie lokal:

- Rust über `rustup` (die Version 1.97.0 wird durch `poc/pake-channel/core/rust-toolchain.toml` automatisch gewählt), `wasm-bindgen-cli 0.2.104` (`cargo install wasm-bindgen-cli --version 0.2.104 --locked`)
- Android-SDK mit Plattform 34, Build-Tools 34.0.0 und **NDK 27.2.12479018**, JDK 17, Gradle 8.9

```sh
git fetch origin poc/android-vivaldi-capability && git checkout <geprüfter Commit>
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
OUT="$HOME/pake-poc-out"; mkdir -p "$OUT"
poc/android-capability/tools/build-native.sh "$OUT"   # zeigt SHA-256 der libkadri_pairing_poc.so
poc/android-capability/tools/build-apk.sh "$OUT"      # Wegwerf-Signierer, wird danach gelöscht
poc/android-capability/tools/build-ext.sh "$OUT"      # Ordner OUT/ext-vivaldi
```

Vergleiche die ausgegebenen SHA-256-Werte der `.so`-Dateien mit den Werten aus dem CI-Bericht des geprüften Commits (Datei `a.sha` im Artefakt `poc-android-capability-report`). Gleiche Werte bedeuten: dieselbe native Kryptobibliothek wie im CI-Test. Die APK-Prüfsumme unterscheidet sich, weil jeder Build einen eigenen Wegwerf-Signierer hat.

## 1. Installation

**Test-App** – eine der beiden Varianten:

- *Mit ADB (Entwicklerdiagnose):* USB-Debugging nur für den Test einschalten, `adb install "$OUT/apk/pake-poc-testapp.apk"`, danach USB-Debugging wieder ausschalten. ADB ist hier ein Entwicklerwerkzeug und **keine** Voraussetzung für die spätere öffentliche Kopplung.
- *Ohne ADB:* APK per Kabel auf das Telefon kopieren, im Dateimanager öffnen, „Installation aus unbekannten Quellen“ **nur für diesen Dateimanager und nur einmal** erlauben, installieren, die Erlaubnis danach sofort wieder entziehen.

Die Instrumentierungs-APK (`…-androidTest.apk`) wird auf deinem Telefon **nicht** benötigt.

**Test-Erweiterung in Vivaldi** (Vivaldi Android ≥ 8.2):

1. Ordner `OUT/ext-vivaldi` auf das Telefon kopieren, z. B. nach `Download/kadri-pake-poc-test/`.
2. Vivaldi → Erweiterungen-Symbol in der Adressleiste → *Erweiterungen verwalten* → oben rechts **Entwicklermodus** einschalten → **Entpackte Erweiterung laden** → den Ordner wählen. (Laut Vivaldi-Forum funktioniert das Laden aus ZIP-Dateien teils nicht; den entpackten Ordner verwenden.)
3. Prüfen, dass genau die Testerweiterung mit dem Namen „TEST – Kadri Android capability PoC (not Kadri)“ erscheint.

## 2. Prüfschritte

Ergebnisse jeweils auf der Selbsttest-Seite der Erweiterung („Ergebnis kopieren“) und im Ereignisprotokoll der Test-App ablesen. Beide enthalten keine Schlüssel und keinen Code.

| # | Schritt | Erwartung |
| --- | --- | --- |
| 0 | Erweiterung → *Browser-Selbsttest* | `wasm.ok`, `evalBlocked: true`, `rng.distinct`, `rng.coreKeypair`. **Stop-Kriterium**, falls WASM, IndexedDB oder Service-Worker fehlen. |
| 1 | Test-App → „Kopplungscode anzeigen“; Code in der Erweiterungs-**Optionsseite** eintippen | „Gekoppelt“; App-Protokoll `PAIRED_PENDING`, danach `REC op=ping` (beidseitige Bestätigung + erster authentisierter Round-Trip) |
| 2 | – | wie 1: die Kopplung gilt erst nach erfolgreicher Bestätigung beider Seiten |
| 3 | Erweiterung → *Helper-Selbsttest* | `handshakeMs`, `ping` mit `android-test-app` |
| 4 | dito | `dummy`: 1 MiB in 18 Records (je ≤ 60 KiB), keine Fehler |
| 5a | Neuer Code, absichtlich **falsch** eintippen | „Fehlgeschlagen“, vorherige Kopplung bleibt (Status unverändert); der Code ist verbraucht |
| 5b | Test-App → „Testidentität zurücksetzen“; dann *Helper-Selbsttest* | Fehler (falscher Pin), **kein** automatisches Neu-Pinnen; danach regulär neu koppeln |
| 6 | Test-App → „Fremd-Listener simulieren: EIN“; *Helper-Selbsttest*; danach „… AUS“ | Selbsttest scheitert; App-Protokoll `ROGUE_OFF … plaintextHits=0`; nach „AUS“ funktioniert der Selbsttest wieder |
| 7 | Test-App und Vivaldi vollständig schließen (aus „Letzte Apps“ wischen), beide neu öffnen; *Helper-Selbsttest* | funktioniert ohne neue Kopplung (Pins persistent, neue Sitzung) |
| 8 | Vivaldi ≥ 1 Minute im Hintergrund lassen, danach *Helper-Selbsttest* | `workerStarts` erhöht oder gleiche `worker`-ID; in beiden Fällen erfolgreicher neuer Handshake |
| 9 | Test-App → „Alle Registrierungen widerrufen“; *Helper-Selbsttest*; danach neu koppeln | erst Fehler, nach Neukopplung wieder erfolgreich |
| 10 | Test-App → „Speicher messen“ (vor/nach Schritt 4); optional `adb shell dumpsys meminfo de.evp.pakepoc.test` | Java-Heap, Native-Heap, PSS, RSS notieren |
| 11 | siehe 4 | begrenzte binäre Dummy-Records |
| 12 | Probe-Seite `http://127.0.0.1:47900/kadri-poc-probe.html` in Vivaldi öffnen | Webseite: `preflighted: blocked…`; Content-Script: `pair`, `selftest`, `status` → `refused`, `storageApi: undefined`, `idb: []`; App-Protokoll zeigt nur `GATE_REJECT` |

Zusätzlich festhalten: Vivaldi-Version (vivaldi://about), Android-Version und Patchstand, Gerätemodell, ob Vivaldi beim Loopback-Zugriff eine Berechtigung (Local Network Access) abgefragt hat.

## 3. Vollständige Entfernung

1. Vivaldi → Erweiterungen verwalten → Testerweiterung **entfernen**; **Entwicklermodus ausschalten**.
2. Android-Einstellungen → Apps → „TEST PAKE-PoC (kein Helper)“ → **Deinstallieren**. Dabei löscht Android die App-Daten (verschlüsselte Testidentität, Registrierungen) und den zugehörigen Keystore-Schlüssel.
3. Kopierten Ordner `Download/kadri-pake-poc-test/` und eine eventuell kopierte APK löschen.
4. Falls genutzt: Erlaubnis „Unbekannte Apps installieren“ entziehen, USB-Debugging ausschalten.
5. Auf dem Mac `OUT` löschen. Der Wegwerf-Signierschlüssel existiert bereits nicht mehr.

Der echte Helper und Kadri werden durch den Test nicht berührt. Port 47899 ist nicht der Helper-Port 47811; beide Apps können parallel installiert sein.
