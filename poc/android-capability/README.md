# Isolierter Android-/Vivaldi-Capability-Test – Prüfbericht

**Nur Machbarkeitsnachweis.** Separater Branch `poc/android-vivaldi-capability`, abgezweigt vom eingefrorenen PoC-HEAD `b19851912f2e077391f14f01d6501a3ec145756b`. PR #20 und PR #21 bleiben unverändert. Keine Änderung an der produktiven Helper-App (`app/`), an Kadri, an produktiven Signierern, minSdk oder `release/production-policy.json` (bleibt `UNAPPROVED`). Issue #4 bleibt offen. **Nichts wurde auf einem Gerät des Eigentümers installiert, nichts veröffentlicht.**

**Ergebnis in einem Satz:** Der gemeinsame Rust-Kern läuft als reproduzierbar gebaute JNI-Bibliothek (arm64-v8a, x86_64) in einer separaten, mit Wegwerf-Schlüssel signierten Test-App; nach den Korrekturen F1–F6 aus dem Security-Review (§6a) bestehen alle 15 Instrumentierungstests auf einem Wegwerf-Emulator (API 34), die JVM-Prüfungen samt Java-Mutationsprobe und die Desktop-Chromium-Vorprüfung der Vivaldi-Testerweiterung (16/16). **Auf Vivaldi Android selbst ist noch nichts getestet** – das ist der nächste, gesondert freizugebende Schritt.

## 1. Vivaldi-Android-Fähigkeiten: dokumentiert vs. getestet

| Fähigkeit | Dokumentiert | Getestet (Desktop-Chromium 153, Vorprüfung) | Getestet auf Vivaldi Android |
| --- | --- | --- | --- |
| Erweiterungen überhaupt | Ja: Vivaldi Android 8.2 (stable, 2026-09) unterstützt Chrome-Erweiterungen; Hilfe-Seite beschreibt Entwicklermodus, „Load unpacked“ (Ordner) und `.crx`-Drag-and-drop im Split-Screen | – | **offen** |
| MV3-Service-Worker als Hintergrund | Nicht ausdrücklich; Vivaldi warnt, nicht alle Erweiterungen seien kompatibel | ja (16/16) | **offen** |
| Gleicher Rust-/WASM-Kern | Chromium-Standard | ja | **offen** |
| CSP `'wasm-unsafe-eval'` (ohne `unsafe-eval`) | Chromium-Standard | ja: WASM läuft, `eval` bleibt blockiert | **offen** |
| Sichere Zufallszahlen (`crypto.getRandomValues`) | Web-Crypto-Standard | ja | **offen** |
| IndexedDB im Erweiterungsursprung, persistent | Chromium-Standard | ja (über Worker-Neustart) | **offen** |
| Schutz vor Webseiten und Content-Scripts | – | ja: Gate/Preflight, Absenderprüfung, kein `storage`-API, IndexedDB unerreichbar | **offen** |
| Optionsseite und Hintergrundnachrichten | `options_ui` Chromium-Standard; Öffnungsweg in Vivaldi Android ungeklärt (Selbsttest-Popup verlinkt die Optionsseite) | ja | **offen** |
| Service-Worker-Neustart und Wiederverbindung | Chromium-Standard | ja: neue Worker-ID, persistenter Pin, neuer Handshake | **offen** |
| Loopback-`fetch` an `127.0.0.1:47899`, Local Network Access | Chromium dokumentiert LNA; Ausnahmen für Erweiterungen mit Host-Berechtigung nicht belegt für Vivaldi | ja (keine Abfrage in Chromium 153 headless) | **offen** |

Warum nichts auf Vivaldi Android getestet ist: In dieser Umgebung sind Vivaldi-Server und `dl.google.com` gesperrt, es gibt kein KVM für einen Emulator, und der Weg „Load unpacked“ setzt die Android-Dateiauswahl voraus. Die Vivaldi-Fakten oben stammen aus Suchergebnissen zur Vivaldi-Hilfe und zum 8.2-Release; die Seiten selbst waren nicht abrufbar. **Stop-Kriterium beim Gerätetest:** fehlt WASM im Service-Worker, persistente IndexedDB oder erlaubter Loopback-Zugriff, ist dieser Weg für Vivaldi nicht bestanden – kein Ausweichen auf Seiten-Skripte, Bearer oder HTTP-Fallback.

## 2. Bestandteile

| Teil | Ort | Zweck |
| --- | --- | --- |
| Rust-Kern | `poc/pake-channel/core` (gleicher Kern wie PR #21, siehe §6) | SPAKE2 + Noise KK, JNI und WASM |
| Vivaldi-Testerweiterung | `ext-vivaldi/` | MV3, ohne Test-Hooks und ohne Testbrücke (R5); Kopplung nur über die Optionsseite, nur nicht-destruktiver Selbsttest über `selftest.html` |
| Android-Test-App | `app-project/testapp` | `de.evp.pakepoc.test`, minSdk 29 (nur Test-App), Server nur `127.0.0.1:47899`, Probe-Seite getrennt auf `127.0.0.1:47900` |
| Build | `tools/build-native.sh`, `tools/build-apk.sh`, `tools/build-ext.sh`, `tools/lib-checks.sh`, Gradle-Wrapper `app-project/gradlew` | reproduzierbarer NDK-Build (Linux und macOS), Versionsprüfungen, Wegwerf-Signierer, APK-Inhaltsprüfung, Erweiterungsordner |
| Tests | `app-project/testapp/src/androidTest`, `app-project/testapp/src/jvmTest`, `tools/run-instrumentation.sh` (nur Emulatoren), `tools/run-jvm-checks.sh`, `tools/java_mutation_check.py`, `tools/desktop_check.mjs` | Instrumentierung im Wegwerf-Emulator, JVM-Prüfungen mit Mutationsprobe, Desktop-Vorprüfung |
| CI | `.github/workflows/poc-android-capability.yml` | nur dieser Branch; kein APK-Upload |
| Anleitung | [DEVICE-TEST-GUIDE.md](DEVICE-TEST-GUIDE.md) | Installation, 12 Prüfschritte, Entfernung – erst nach Freigabe |
| Firefox Android | [FIREFOX-ANDROID-PLAN.md](FIREFOX-ANDROID-PLAN.md) | separater späterer Test |

## 3. Android-Test-App

- **Trennung:** eigene `applicationId` `de.evp.pakepoc.test`, Label „TEST PAKE-PoC (kein Helper)“, roter Testhinweis; keine Daten, Schlüssel oder Ports des echten Helpers (`de.evp.helfer`, Port 47811). `allowBackup=false` plus `dataExtractionRules`/`fullBackupContent`, die alle Daten von Cloud-Backup und Gerätetransfer ausschließen (F5). Fenster mit `FLAG_SECURE` (Code nicht in „Letzte Apps“/Screenshots). Der Service ist nicht exportiert; alle sicherheitsrelevanten Aktionen (Kopplungsfenster, Widerruf, Fremd-Listener-Simulation, Identitäts-Reset) gibt es nur als Tasten in der App (R5). Die Kadri-seitige Testbibliothek liegt nur in der Instrumentierungs-APK (F5).
- **Endpunkte:** `POST /p1/pair/m0`, `/p1/pair/m2`, `/p1/ch/hs`, `/p1/ch/rec`; Anwendungsoperationen nur `ping` und begrenzte binäre `dummy`-Records (≤ 60 KiB je Record, ≤ 64 MiB je Transfer). Keine Downloads, URLs, Cookies, Legacy- oder Produktionsendpunkte. Browser-Gate wie im PoC (Methode, Host, Content-Type, `X-Kadri-Helper`, Erweiterungs-Origin) – Schutz gegen Webseiten, keine Authentisierung. `TCP_NODELAY` auf jedem Socket. Jede Anfrage muss binnen 3 s ab Annahme der Verbindung vollständig eingelesen sein (F3); Grenzen unverändert: Header ≤ 8 KiB, Body ≤ 65 556 B, 4 Worker, Warteschlange 16.
- **Probe-Seite** auf `127.0.0.1:47900`, also ein anderer Ursprung: Würde der Helper-Port selbst Webinhalte liefern, wären diese gleichen Ursprungs, und CORS böte keinen Schutz mehr (Befund aus der Desktop-Vorprüfung).
- **Uhren (R3):** alle Fristen auf `SystemClock.elapsedRealtime()`. Registrierungen werden mit `Settings.Global.BOOT_COUNT` gespeichert; nach einem Reboot verwirft der Kern pending Registrierungen und setzt die Zeit bestätigter Registrierungen neu (getestet).

## 4. Speicherung der Testidentität

| Aspekt | Umsetzung |
| --- | --- |
| Identität | zufälliges X25519-Schlüsselpaar je Installation (`Core.identityGenerate`, getrandom/OS-RNG) |
| Schutz | AES-256-GCM mit einem Schlüssel im **Android Keystore** (`pakepoc-identity-wrap-v1`, zufälliger IV, AAD = Formatversion + Paketname) |
| Datei | `files/identity.v1` = `KPI1` ‖ IV(12) ‖ Chiffrat(64 + 16), atomar über `AtomicFile`; kein SharedPreferences-Klartext, keine Logs |
| Lebenszyklus im Speicher | beim Serverstart entschlüsselt → an den Rust-Kern übergeben → Java-Kopie sofort genullt; im Kern liegt der private Schlüssel als `Zeroizing<[u8; 32]>` (64 Byte inkl. öffentlichem Teil) für die Laufzeit des Hosts, weil snow ihn für jeden Handshake braucht; Freigabe beim Prozessende bzw. nächsten `hostInit` |
| Grenze | Der X25519-Schlüssel ist **nicht** hardwareisoliert und nicht nicht-exportierbar; nur der Umhüllungsschlüssel liegt im Keystore. Ein kompromittierter App-Prozess kann ihn lesen. |
| Zustände (F2) | Datei fehlt + Schlüssel fehlt → neue Identität **nur** bei Erstinstallation (keine Registrierungen gespeichert), sonst `ERROR_INCONSISTENT`; Datei + Schlüssel + GCM ok → `OK`; Datei da, Schlüssel fehlt → `ERROR_KEY_MISSING`; Datei fehlt, Schlüssel da → `ERROR_INCONSISTENT` (keine Rotation, Schlüssel bleibt); Format falsch oder GCM-Authentisierung scheitert → `ERROR_CORRUPT`. Bei jedem Fehler startet der Server nicht und nichts wird gelöscht oder neu erzeugt. |
| Reinstallation | App-Daten und Keystore-Schlüssel werden mit gelöscht → Erstinstallation mit neuer Identität; bisherige Kadri-Pins passen nicht mehr → Handshake scheitert, **kein** automatisches Neu-Pinnen, Nutzer koppelt neu |
| Neue Identität sonst | nur über die Taste „Testidentität zurücksetzen“: erst Registrierungen löschen, dann Identität (ein unterbrochener Reset hinterlässt die alte Identität ohne Registrierungen, nie Registrierungen ohne Identität) |
| Registrierungen | `files/registrations.v1` = `KPR1` ‖ Bootzähler ‖ Kern-Export (nur öffentliche Kadri-Schlüssel und IDs), atomar geschrieben; Export und Schreiben serialisiert, Schreiben nur bei Änderung (F1, F4) |

## 5. Sicherheitsauflagen R1–R5 und weitere Risiken

| Punkt | Stand |
| --- | --- |
| R1 HS1-Replay verdrängt neue Handshakes | **bekanntes Verfügbarkeitsrisiko, nicht behoben.** Reproduziert in Rust (`r1_hs1_replay_can_evict_a_fresh_legitimate_handshake`) und auf dem Emulator (`r1ReplayedHs1EvictsAFreshHandshake`, Messwert §7). Wirkung auf Worker-Neustarts: Ein Neustart erfordert ohnehin einen neuen Handshake; wird dieser durch Replays verdrängt, scheitert der erste Request mit 404 und der nächste Handshake gelingt, sobald die Replays aufhören. Keine Vertraulichkeitsfolge. |
| R2 Bekannte Session-ID erlaubt DoS | **bekanntes Verfügbarkeitsrisiko, nicht behoben.** Getestet (Rust und Emulator): Müll-Record beendet die Sitzung, Übernahme unmöglich, neuer Handshake funktioniert. Authentisierung nicht geschwächt. |
| R3 Wanduhr | behoben: Android `elapsedRealtime`, Bootzähler, Reboot-Import im Kern; Kadri-Erweiterung nutzt `performance.now()` für Flow-Fristen |
| R4 Vorzeitiger Pin-Austausch | behoben auf **beiden** Seiten: Kadri ersetzt den Pin erst nach erfolgreichem authentisiertem Round-Trip; der Helper zieht ältere Registrierungen desselben Schlüssels erst zurück, wenn eine bereits bestätigte Registrierung erneut benutzt wird. Test: Desktop-Vorprüfung „R4“ und `r4_rejected_new_pin_keeps_the_previous_registration`. Die Desktop-Vorprüfung hat gezeigt, dass die Helper-Seite vorher die alte Registrierung zu früh verwarf. |
| R5 Testbrücke/Hooks | nicht übernommen: keine Firefox-Brücke, keine Test-Hooks, kein fremd erreichbarer Kanal für Pin/Forget/Pair. Die Kadri-Seite für die Instrumentierung (`jni-test-initiator`) ist eine eigene Bibliothek `libkadri_pairing_poc_testinit.so`, die nur in der Instrumentierungs-APK liegt; `build-native.sh` prüft per Symboltabelle, dass die App-Bibliothek keine `TestInitiator`-Symbole enthält, `build-apk.sh`, dass die installierbare APK sie nicht enthält. Einziger Test-Hook im App-Code: `SnapshotWriter.testHook` (Java-Feld, nur aus Code im selben Prozess setzbar, von der App nie gesetzt). |
| Limits = 0 | behoben: `Limits::sanitized()` (Minimum 1) und Abbruch der Verdrängungsschleifen; Test |
| Session-Cleanup | Sweep vor jeder Operation; Widerruf entfernt Sitzungen; Fehler entfernen nur die betroffene Sitzung |
| Temporäre Schlüssel in JNI/WASM | Rust-Seite zeroized (`Zeroizing`); Java-Kopie wird genullt; im WASM-Linearspeicher und im JS-Heap bleiben Kopien bis zur Speicherwiederverwendung (nicht steuerbar), Kadris Identität liegt zudem in IndexedDB im Klartext des Erweiterungsursprungs |
| Nicht-atomare Registrierungsspeicherung | behoben (`AtomicFile`); JVM-Stand-in bleibt Testwerkzeug |
| Langsamer Leser (Antwort nicht abholen) | nicht begrenzt: Java-Schreibvorgänge haben kein Timeout. Unauthentisierte Antworten sind klein (Kopplungs- und Handshake-Nachrichten unter 200 B, leere 4xx) und passen in den Socket-Puffer; große Antworten (Dummy-Records) gibt es nur über eine authentisierte Sitzung. Restrisiko dokumentiert. |
| Dauerhafte Angreifer, die sich ständig neu verbinden | konkurrieren weiter um die 4 Worker; F3 begrenzt nur die Zeit je Verbindung. Lokaler DoS bleibt möglich (wie R1/R2), keine Vertraulichkeitsfolge. |

## 6. Änderungen am Kern gegenüber dem eingefrorenen PoC

Nur in diesem Branch: `Limits::sanitized()` und Schleifen-Guards; `import_registrations(blob, now, same_boot)`; R4-Rückzugsregel; Feature `jni-test-initiator` (seit F5 ohne die Helper-JNI-Oberfläche, als eigene Bibliothek); `strip = true` im Release-Profil; `rust-toolchain.toml` (1.97.0); `hostInit` mit Zeit und Boot-Flag. Protokoll und Krypto-Komposition unverändert. Tests: 39 Rust-Tests, Mutationsprobe siehe §7.

## 6a. Korrekturen nach dem Security-Review (F1–F6)

| Befund | Korrektur | Nachweis |
| --- | --- | --- |
| **F1** Widerruf nach Neustart rückgängig (Lost Update) | `SnapshotWriter`: Export (unter Kern-Sperre) und atomares Schreiben unter **einer** Sperre, Schreibreihenfolge = Exportreihenfolge. `revokeAll()` widerruft im Kern (Registrierungen, Sitzungen und offenes Kopplungsfenster – eine vorher begonnene Kopplung kann danach nicht abgeschlossen werden), speichert und meldet erst danach Erfolg (`REVOKED_ALL (saved)`); scheitert das Speichern, stoppt der Server (`REVOKE_NOT_DURABLE`). Jede Route speichert vor der Antwort; ohne Speicherung kein M3. Zusätzlich: Anfragen eines gestoppten oder ersetzten Servers dürfen den Kern nicht mehr anfassen (`coreOwner`) – verhindert z. B., dass eine späte Fremd-Listener-Anfrage ein Fenster mit bekanntem Code auf dem echten Host öffnet. | JVM: `oldSnapshotNeverOverwritesRevocation` (Latches, deterministisch). Emulator: `f1StaleSnapshotCannotUndoRevocationAcrossRestart` – A bestätigt; parallele Kopplung B exportiert {A, B} und wird per Latch vor dem Schreiben angehalten; Widerruf darf nicht vorher Erfolg melden; nach Freigabe und Neustart: 0 Registrierungen, A und B scheitern im Handshake. Mutanten „nicht serialisiert“, „fehlgeschlagenes Schreiben als dauerhaft“, „veralteter Owner schreibt“ werden erkannt. |
| **F2** Stille Identitätsrotation | Zustandsmaschine §4; `ERROR_INCONSISTENT`; Erstanlage nur ohne gespeicherte Registrierungen | Emulator: `identityStateMachineNeverRotatesSilently` (alle Kombinationen inkl. GCM-Tag-/Chiffrat-Bitfehler, Kürzung, falsche AAD) und `lostIdentityFileStopsTheServerAndKeepsPairings` (Datei weg → Server aus, Registrierungsdatei bytegleich, Keystore-Schlüssel bleibt; Datei zurück → alte Kopplung funktioniert) |
| **F3** Slow-Client-DoS | `HttpRequestReader`: gemeinsames monotones Budget von 3 s für Header und Body ab Annahme der Verbindung (auch Wartezeit in der Warteschlange zählt); jedes Lesen wartet höchstens bis zur Frist; gepuffertes Lesen | JVM: tröpfelnder Header, tröpfelnder Body, in der Warteschlange abgelaufene Verbindung, Grenzen. Emulator: `f3SlowClientsAreDroppedAndLegitimateRequestsServed` mit 24 parallelen Angreifern (1 Byte / 250 ms) |
| **F4** fsync je Record | Schreiben nur, wenn sich der exportierte Registrierungszustand ändert (neue Kopplung, erste Bestätigung, Rückzug, Ablauf, Widerruf, Reset); Sitzungs-/Transportzustand ist nicht Teil des Exports | JVM: `writesOnlyOnChange`. Emulator: 0 Registry-Schreibvorgänge während 8 MiB Dummy-Transfer (Assertion) |
| **F5** Android-Härtung | `FLAG_SECURE` (+ `setRecentsScreenshotEnabled(false)` ab API 33); `dataExtractionRules` und `fullBackupContent` schließen alles aus; keine Logcat-Ausgabe im App-Code; Testbibliothek getrennt (R5-Zeile) | Emulator: `f5ScreenAndBackupHardening`; `build-apk.sh` prüft Manifest und APK-Inhalt; Logcat-Prüfung jetzt auch auf den privaten Schlüssel der Helper-Identität |
| **F6** Mac-Build | `lib-checks.sh` (`sha256sum`/`shasum -a 256`, JDK 17, Rust 1.97.0, NDK r27c); Gradle-8.9-Wrapper mit festgelegtem Distribution-SHA-256 (CI vergleicht ihn mit dem von Gradle veröffentlichten Wert und validiert das Wrapper-JAR); APK-interne `.so` werden mit dem Build verglichen; `run-instrumentation.sh` verweigert echte Geräte | CI-Lauf §7; Anleitung DEVICE-TEST-GUIDE §0 |

## 7. Ergebnisse

Stand nach den Korrekturen: Code-Commit `70dcd826f109830f39b7145a0e9198f3c4820e3d`, CI-Lauf [40649090099](https://github.com/Owndot/video-assistent-helper/actions/runs/40649090099) **success**; Commit `63314d0b1dc0f2da7264b4602650e40e28d56770` (zusätzlich Gerätesperre in `run-instrumentation.sh` und Dokumentation), CI-Lauf [40650608324](https://github.com/Owndot/video-assistent-helper/actions/runs/40650608324) **success** mit denselben `.so`-Hashes. Messwerte unten aus beiden Läufen. Frühere Läufe vor den Korrekturen: 40596803035, 40597486902.

**Toolchain:** rustc 1.97.0 (2d8144b78 2026-07-07) über `rust-toolchain.toml`, `cargo --locked` mit `Cargo.lock`, NDK 27.2.12479018 (r27c), API-Level 29 für den Linker, 16-KB-Seitenausrichtung, `--remap-path-prefix`, `strip = true`; JDK 17, Gradle 8.9 (Wrapper, Distribution-SHA-256 `d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab`, in CI gegen den veröffentlichten Wert geprüft), AGP 8.5.2, compile/target SDK 34. Build-Host in CI: Linux x86_64.

**Reproduzierbarkeit:** zwei Builds aus unterschiedlichen Verzeichnissen auf Linux, bitgleich; dieselben Bytes liegen in den APKs (von `build-apk.sh` verglichen):

| ABI | Bibliothek | SHA-256 | Größe | liegt in |
| --- | --- | --- | --- | --- |
| arm64-v8a | `libkadri_pairing_poc.so` | `ce2be03d4627472b659af31529efe9faa8413c185ad0afddfd9229436da53bba` | 543 480 B | Test-App |
| x86_64 | `libkadri_pairing_poc.so` | `cf289573feab91285b09cb8d18a11447f8b4473f725caff41c051a11e7ce5d82` | 645 560 B | Test-App |
| arm64-v8a | `libkadri_pairing_poc_testinit.so` | `31701f8a6a3811cf1e30c40f7815048a1e11fdbcfec6a9a14409b32ce1a3698c` | 529 568 B | nur Instrumentierungs-APK |
| x86_64 | `libkadri_pairing_poc_testinit.so` | `e772b0c0d2dc86d8d660d8ec4ae7b10b69adbca3c4b8d5748acb9d98a7c3065d` | 628 016 B | nur Instrumentierungs-APK |

Die Hashes der App-Bibliothek haben sich gegenüber dem ersten Bericht geändert, weil die Testinitiator-Symbole nicht mehr enthalten sind. **Bitgleichheit zwischen Linux und macOS ist nicht nachgewiesen** (DEVICE-TEST-GUIDE §0, Punkt 3). Die APK-Hashes sind **nicht** reproduzierbar, weil jeder Build einen eigenen Wegwerf-Signierer erzeugt (`CN=PAKE PoC TEST ONLY - throwaway`, im CI-Lauf Zertifikat-SHA-256 `e51b4939…dfd4`) und ihn danach löscht. Der bisherige Helper-Signierer wird nirgends verwendet.

**WASM der Testerweiterung:** `kadri_pairing_poc_bg.wasm` SHA-256 `18d077679df9c70b64ce326bb890681d80f02a2d6ef782fdcb88434e433eac6f` (Linux-Build, unverändert, da die Korrekturen nur Android-/JNI-Code betreffen).

**Tests:**

| Ebene | Ergebnis |
| --- | --- |
| Rust-Kern (`cargo test --locked`) | 39/39 (lokal und CI) |
| Kern-Mutationsprobe (`poc/pake-channel/tools/mutation_check.py`) | PASS: 43 Mutanten, 39 getötet, 4 begründet akzeptiert (unverändert gegenüber dem ersten Bericht; der Kern wurde für F1–F6 nur im Cargo-Feature geändert) |
| JVM-Prüfungen (`tools/run-jvm-checks.sh`) | 9/9 (lokal und CI) |
| Java-Mutationsprobe (`tools/java_mutation_check.py`) | PASS: 10/10 Mutanten erkannt (lokal und CI) |
| Desktop-Chromium-Vorprüfung der Vivaldi-Testerweiterung (`tools/desktop_check.mjs`) | 16/16 (lokal, Chromium 153) |
| Instrumentierung auf API-34-x86_64-Wegwerf-Emulator | **OK (15 tests)**, danach Logcat-Prüfung ohne Treffer auf Codes, Kadri-Schlüssel oder den Helper-Schlüssel |

Instrumentierungstests: Identitäts-Zustandsmaschine (F2); verlorene Identitätsdatei (F2); Reboot-Erkennung der Registrierungen; Kopplung + Handshake + Ping + 8 MiB Dummy ohne Registry-Schreibvorgang (F4); falscher Code verbraucht das Fenster; falscher Pin; Browser-Gate und getrennter Probe-Port; Fremd-Listener nach aktiver Sitzung sieht nur Chiffrat; Neustart behält Registrierungen, nicht Sitzungen; Widerruf und Neukopplung; F1-Regression; F3 mit 24 Angreifern; F5; R1; R2.

**Messwerte (Emulator, x86_64, nicht repräsentativ für ein Telefon):**

| Messung | Wert |
| --- | --- |
| Prozess beim Teststart | Java-Heap 8,3 MiB, Native-Heap 16,3 MiB, PSS 41,2 MiB, RSS 134 MiB |
| nach 8 MiB Dummy-Transfer | Java-Heap 8,3 MiB, Native-Heap 11,4 MiB, PSS 38,9 MiB, RSS 133 MiB, Spitzen-RSS (VmHWM) 154 MiB |
| Durchsatz nach F4 | 8 MiB in 137 Records in 2,15 s bzw. 3,08 s ≈ **3,7 bzw. 2,6 MiB/s** (vorher 1,5–2,0 MiB/s mit einem atomaren Schreibvorgang je Record; enthält auch das gepufferte Einlesen aus F3). Weiterhin ein HTTP-Request je 60-KiB-Record inkl. byteweiser Musterprüfung im Testprozess – kein Wert für späteres Streaming |
| F3 Slow-Client | 24 Angreifer, Budget 3 000 ms: alle spätestens nach 3 616 bzw. 3 452 ms getrennt; legitimer Handshake + Ping nach 3 435 bzw. 3 214 ms bedient (24 bzw. 23 Versuche im 100-ms-Takt; die Versuche während der Last werden abgewiesen) |
| R1 | 2 Replays verdrängen einen frischen Handshake in 12–18 ms; der nächste legitime Handshake gelingt in 36–60 ms (frühere Läufe 9–33 / 16–42 ms) |
| Keystore-Umhüllungsschlüssel | AES-256-GCM, Sicherheitsstufe 0 = `SECURITY_LEVEL_SOFTWARE` auf dem Emulator (erwartet); auf dem Telefon in der Statusanzeige der App („Schutz: …“) ablesen (TEE = 1, StrongBox = 2) |

Die Messwerte enthalten Test-Runner und Instrumentierung im selben Prozess. Auf dem Gerät werden sie über die Taste „Speicher messen“ bzw. `dumpsys meminfo de.evp.pakepoc.test` erhoben (DEVICE-TEST-GUIDE, Schritt 10).

**Trennung vom produktiven Helper (geprüft):** Gegenüber `b198519` ändert der Branch außerhalb von `poc/` nur `.github/workflows/poc-android-capability.yml`; `app/`, `release/` (Policy `UNAPPROVED`) und die Root-`settings.gradle` (nur `:app`) sind unverändert. Test-App: eigenes Paket `de.evp.pakepoc.test` statt `de.evp.helfer`, Port 47899/47900 statt 47811, eigener Wegwerf-Signierer, eigenes Gradle-Projekt.

**Wo die Test-APK ist:** nirgends dauerhaft. CI baut sie im Job, installiert sie im Wegwerf-Emulator und verwirft sie mit dem Runner; hochgeladen wird nur ein Textbericht ohne APK und ohne Schlüssel. Für den Gerätetest baust du sie lokal (DEVICE-TEST-GUIDE, Schritt 0) und vergleichst die `.so`-Hashes mit der Tabelle oben.

## 8. Noch nicht auf echter Hardware geprüft

Alles aus §1 in der Spalte „Vivaldi Android“; Installation über „Load unpacked“; Hintergrundverhalten von Vivaldi auf einem echten Gerät (Akkuoptimierung, Prozess-Kill); Keystore auf echter Hardware (Sicherheitsstufe des Umhüllungsschlüssels); Speicher-/RSS-Werte auf arm64; Verhalten nach echtem Reboot; Local-Network-Access-Abfragen; Firefox Android insgesamt.

## 9. Vor einer späteren öffentlichen Veröffentlichung

Unverändert aus PR #21 plus: positiver Vivaldi-Gerätetest nach dieser Anleitung; Firefox-Android-Test; Audit-Entscheidung für pakery/snow; Entscheidung, wie eine produktive Kadri-Erweiterung auf Vivaldi Android installiert wird (Chrome Web Store statt Entwicklermodus); R1/R2 als akzeptierte Restrisiken oder eigenes Design; produktive Umsetzung nur nach erneuter Freigabe.

## Stand

Branch `poc/android-vivaldi-capability` (Basis `b19851912f2e077391f14f01d6501a3ec145756b`). Kein Pull Request, kein Tag, kein Release. Die Workflow-Datei `.github/workflows/poc-android-capability.yml` liegt nur auf diesem Branch und läuft nur bei Pushes auf ihn. Produktions-Policy: `UNAPPROVED`. Issue #4: offen.
