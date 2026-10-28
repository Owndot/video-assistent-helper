# Gerätetest – Anleitung (erst nach gesonderter Freigabe ausführen)

**Noch nicht ausführen.** Diese Anleitung gilt erst, wenn du die Installation auf deinem Gerät ausdrücklich freigegeben hast. Sie installiert zwei Wegwerf-Testkomponenten:

1. die Test-App **„TEST PAKE-PoC (kein Helper)“** (`de.evp.pakepoc.test`) – getrennt vom echten Helper `de.evp.helfer`, ohne Zugriff auf dessen Daten oder Schlüssel;
2. die Test-Erweiterung **„TEST – Kadri Android capability PoC (not Kadri)“** für Vivaldi.

Beide arbeiten nur mit Wegwerf-Testidentitäten und Dummy-Daten. Nirgends echte Medien, Cookies, URLs oder Konten verwenden.

## 0. Vorbereitung (auf deinem Mac, nicht auf dem Telefon)

Die Test-APK wird **nicht** veröffentlicht oder als CI-Artefakt bereitgestellt (öffentliches Repository). Du baust sie lokal. Die Skripte laufen auf macOS (Apple Silicon und Intel) und Linux und brechen ab, wenn eine Version nicht stimmt.

**Werkzeuge** (einmalig):

| Werkzeug | Version | Prüfung durch die Skripte |
| --- | --- | --- |
| JDK | **17** (z. B. Temurin 17; `JAVA_HOME` darauf setzen) | `java.specification.version = 17` |
| Rust | **1.97.0** – `rustup` wählt sie über `poc/pake-channel/core/rust-toolchain.toml` samt Android-Zielen automatisch | `rustc 1.97.0` |
| Android-SDK | Plattform 34, Build-Tools 34.0.0: `sdkmanager "platforms;android-34" "build-tools;34.0.0" "ndk;27.2.12479018"` | Build-Tools 34.0.0 vorhanden |
| NDK | **27.2.12479018 (r27c)**; auf dem Mac liegen die Werkzeuge unter `toolchains/llvm/prebuilt/darwin-x86_64` (Universal-Binaries, auch für Apple Silicon) | `Pkg.Revision` in `source.properties` |
| Gradle | **8.9** über den eingecheckten Wrapper `app-project/gradlew` – keine eigene Gradle-Installation nötig | Distribution-SHA-256 in `gradle-wrapper.properties` (`d725d707…ecab`), Gradle prüft ihn beim Download |
| wasm-bindgen | `cargo install wasm-bindgen-cli --version 0.2.104 --locked` | exakte Version |

Den Wrapper vor dem ersten Lauf prüfen: `shasum -a 256 poc/android-capability/app-project/gradle/wrapper/gradle-wrapper.jar` muss `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172` ergeben und in der Liste unter <https://gradle.org/release-checksums/> stehen (Wrapper-JAR von Gradle 8.14.3; CI prüft das zusätzlich mit `gradle/actions/wrapper-validation`). Ein Wrapper-JAR einer anderen Gradle-Version lädt trotzdem genau Gradle 8.9.

**Bauen:**

```sh
git fetch origin poc/android-vivaldi-capability && git checkout <geprüfter Commit>
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
OUT="$HOME/pake-poc-out"; mkdir -p "$OUT"
poc/android-capability/tools/build-native.sh "$OUT"   # zwei Bibliotheken je ABI, SHA-256 + Symbolprüfung
poc/android-capability/tools/build-apk.sh "$OUT"      # Wegwerf-Signierer, wird danach gelöscht
poc/android-capability/tools/build-ext.sh "$OUT"      # Ordner OUT/ext-vivaldi
```

**Was du vergleichst und was das bedeutet:**

1. *APK enthält genau die gebaute native Bibliothek* – `build-apk.sh` vergleicht das selbst und bricht bei Abweichung ab (Zeilen „native libraries: build output vs. inside the APK“).
2. *Lokal reproduzierbar* – optional ein zweites Mal in ein anderes `OUT` bauen; die `.so`-Hashes müssen gleich sein.
3. *Gleich mit dem CI-Test?* – die arm64-Hashes mit der Tabelle im README bzw. `a.sha` des CI-Berichts vergleichen. **Bitgleichheit zwischen Linux- und macOS-Builds ist nicht nachgewiesen.** Sind die Hashes gleich, ist es dieselbe Bibliothek wie im CI-Test. Sind sie verschieden, ist das noch kein Fehler, aber dann ist deine `.so` nicht die im CI getestete. In dem Fall vor dem Gerätetest die Instrumentierung lokal auf einem **Emulator** laufen lassen (Android Studio, arm64-System-Image API 34): `poc/android-capability/tools/run-instrumentation.sh "$OUT"`. Das Skript verweigert echte Geräte.
4. *APK-Hash* – unterscheidet sich bei jedem Build, weil jeder Build einen eigenen Wegwerf-Signierer erzeugt; das Zertifikat heißt immer `CN=PAKE PoC TEST ONLY - throwaway`. Einen APK-Hash-Vergleich mit CI gibt es daher nicht.
5. *Erweiterung* – SHA-256 von `ext-vivaldi/pkg/kadri_pairing_poc_bg.wasm` (Ausgabe von `build-ext.sh`) mit dem README vergleichen; auch hier ist Gleichheit zwischen Linux und macOS nicht nachgewiesen.

## 1. Installation

**Test-App** – eine der beiden Varianten:

- *Mit ADB (Entwicklerdiagnose):* USB-Debugging nur für den Test einschalten, `adb install "$OUT/apk/pake-poc-testapp.apk"`, danach USB-Debugging wieder ausschalten. ADB ist hier ein Entwicklerwerkzeug und **keine** Voraussetzung für die spätere öffentliche Kopplung.
- *Ohne ADB:* APK per Kabel auf das Telefon kopieren, im Dateimanager öffnen, „Installation aus unbekannten Quellen“ **nur für diesen Dateimanager und nur einmal** erlauben, installieren, die Erlaubnis danach sofort wieder entziehen.

Die Instrumentierungs-APK (`…-androidTest.apk`) wird auf deinem Telefon **nicht** benötigt und nicht installiert. Nur sie enthält die Kadri-seitige Testbibliothek (`libkadri_pairing_poc_testinit.so`); die installierte Test-App enthält sie nicht.

**Test-Erweiterung in Vivaldi** (Vivaldi Android ≥ 8.2):

1. Ordner `OUT/ext-vivaldi` auf das Telefon kopieren, z. B. nach `Download/kadri-pake-poc-test/`.
2. Vivaldi → Erweiterungen-Symbol in der Adressleiste → *Erweiterungen verwalten* → oben rechts **Entwicklermodus** einschalten → **Entpackte Erweiterung laden** → den Ordner wählen. (Laut Vivaldi-Forum funktioniert das Laden aus ZIP-Dateien teils nicht; den entpackten Ordner verwenden.)
3. Prüfen, dass genau die Testerweiterung mit dem Namen „TEST – Kadri Android capability PoC (not Kadri)“ erscheint.

## 2. Prüfschritte

Ergebnisse jeweils auf der Selbsttest-Seite der Erweiterung („Ergebnis kopieren“) und im Ereignisprotokoll der Test-App ablesen. Beide enthalten keine Schlüssel und keinen Code.

| # | Schritt | Erwartung |
| --- | --- | --- |
| 0 | Erweiterung → *Browser-Selbsttest* | `wasm.ok`, `evalBlocked: true`, `rng.distinct`, `rng.coreKeypair`. **Stop-Kriterium**, falls WASM, IndexedDB oder Service-Worker fehlen. |
| 1 | Test-App → „Kopplungscode anzeigen“; Code in der Erweiterungs-**Optionsseite** eintippen | „Gekoppelt“; App-Protokoll `PAIRED_PENDING`, danach `REC op=ping` (beidseitige Bestätigung + erster authentisierter Round-Trip). Während der Code sichtbar ist, „Letzte Apps“ öffnen: die Test-App erscheint ohne Inhalt (FLAG_SECURE); ein Screenshot ist gesperrt |
| 2 | – | wie 1: die Kopplung gilt erst nach erfolgreicher Bestätigung beider Seiten |
| 3 | Erweiterung → *Helper-Selbsttest* | `handshakeMs`, `ping` mit `android-test-app` |
| 4 | dito | `dummy`: 1 MiB in 18 Records (je ≤ 60 KiB), keine Fehler |
| 5a | Neuer Code, absichtlich **falsch** eintippen | „Fehlgeschlagen“, vorherige Kopplung bleibt (Status unverändert); der Code ist verbraucht |
| 5b | Test-App → „Testidentität zurücksetzen“; dann *Helper-Selbsttest* | Fehler (falscher Pin), **kein** automatisches Neu-Pinnen; danach regulär neu koppeln |
| 6 | Test-App → „Fremd-Listener simulieren: EIN“; *Helper-Selbsttest*; danach „… AUS“ | Selbsttest scheitert; App-Protokoll `ROGUE_OFF … plaintextHits=0`; nach „AUS“ funktioniert der Selbsttest wieder |
| 7 | Test-App und Vivaldi vollständig schließen (aus „Letzte Apps“ wischen), beide neu öffnen; *Helper-Selbsttest* | funktioniert ohne neue Kopplung (Pins persistent, neue Sitzung) |
| 8 | Vivaldi ≥ 1 Minute im Hintergrund lassen, danach *Helper-Selbsttest* | `workerStarts` erhöht oder gleiche `worker`-ID; in beiden Fällen erfolgreicher neuer Handshake |
| 9 | Test-App → „Alle Registrierungen widerrufen“; dann „Testserver beenden“ und „Testserver starten“; *Helper-Selbsttest*; danach neu koppeln | Protokoll `REVOKED_ALL (saved)`; der Widerruf überlebt den Neustart (Selbsttest scheitert); nach Neukopplung wieder erfolgreich. Bei `REVOKE_NOT_DURABLE` stoppt der Server – Test abbrechen und melden |
| 10 | Test-App → „Speicher messen“ (vor/nach Schritt 4); optional `adb shell dumpsys meminfo de.evp.pakepoc.test` | Java-Heap, Native-Heap, PSS, RSS notieren |
| 11 | siehe 4 | begrenzte binäre Dummy-Records |
| 12 | Probe-Seite `http://127.0.0.1:47900/kadri-poc-probe.html` in Vivaldi öffnen | Webseite: `preflighted: blocked…`; Content-Script: `pair`, `selftest`, `status` → `refused`, `storageApi: undefined`, `idb: []`; App-Protokoll zeigt nur `GATE_REJECT` |

Zusätzlich festhalten: Vivaldi-Version (vivaldi://about), Keystore-Sicherheitsstufe aus der App-Statusanzeige („Schutz: …“, 1 = TEE, 2 = StrongBox), Android-Version und Patchstand, Gerätemodell, ob Vivaldi beim Loopback-Zugriff eine Berechtigung (Local Network Access) abgefragt hat.

## 3. Vollständige Entfernung

1. Vivaldi → Erweiterungen verwalten → Testerweiterung **entfernen**; **Entwicklermodus ausschalten**.
2. Android-Einstellungen → Apps → „TEST PAKE-PoC (kein Helper)“ → **Deinstallieren**. Dabei löscht Android die App-Daten (verschlüsselte Testidentität, Registrierungen) und den zugehörigen Keystore-Schlüssel.
3. Kopierten Ordner `Download/kadri-pake-poc-test/` und eine eventuell kopierte APK löschen.
4. Falls genutzt: Erlaubnis „Unbekannte Apps installieren“ entziehen, USB-Debugging ausschalten.
5. Auf dem Mac `OUT` löschen. Der Wegwerf-Signierschlüssel existiert bereits nicht mehr.

Der echte Helper und Kadri werden durch den Test nicht berührt. Port 47899 ist nicht der Helper-Port 47811; beide Apps können parallel installiert sein.
