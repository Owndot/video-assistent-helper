# Isolierter Android-/Vivaldi-Capability-Test – Prüfbericht

**Nur Machbarkeitsnachweis.** Separater Branch `poc/android-vivaldi-capability`, abgezweigt vom eingefrorenen PoC-HEAD `b19851912f2e077391f14f01d6501a3ec145756b`. PR #20 und PR #21 bleiben unverändert. Keine Änderung an der produktiven Helper-App (`app/`), an Kadri, an produktiven Signierern, minSdk oder `release/production-policy.json` (bleibt `UNAPPROVED`). Issue #4 bleibt offen. **Nichts wurde auf einem Gerät des Eigentümers installiert, nichts veröffentlicht.**

**Ergebnis in einem Satz:** Der gemeinsame Rust-Kern läuft als reproduzierbar gebaute JNI-Bibliothek (arm64-v8a, x86_64) in einer separaten, mit Wegwerf-Schlüssel signierten Test-App; alle 11 Instrumentierungstests auf einem Wegwerf-Emulator (API 34) und die Desktop-Chromium-Vorprüfung der Vivaldi-Testerweiterung (16/16) bestehen. **Auf Vivaldi Android selbst ist noch nichts getestet** – das ist der nächste, gesondert freizugebende Schritt.

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
| Build | `tools/build-native.sh`, `tools/build-apk.sh`, `tools/build-ext.sh` | reproduzierbarer NDK-Build, Wegwerf-Signierer, Erweiterungsordner |
| Tests | `app-project/testapp/src/androidTest`, `tools/run-instrumentation.sh`, `tools/desktop_check.mjs` | Instrumentierung im Wegwerf-Emulator, Desktop-Vorprüfung |
| CI | `.github/workflows/poc-android-capability.yml` | nur dieser Branch; kein APK-Upload |
| Anleitung | [DEVICE-TEST-GUIDE.md](DEVICE-TEST-GUIDE.md) | Installation, 12 Prüfschritte, Entfernung – erst nach Freigabe |
| Firefox Android | [FIREFOX-ANDROID-PLAN.md](FIREFOX-ANDROID-PLAN.md) | separater späterer Test |

## 3. Android-Test-App

- **Trennung:** eigene `applicationId` `de.evp.pakepoc.test`, Label „TEST PAKE-PoC (kein Helper)“, roter Testhinweis; keine Daten, Schlüssel oder Ports des echten Helpers (`de.evp.helfer`, Port 47811). `allowBackup=false`. Der Service ist nicht exportiert; alle sicherheitsrelevanten Aktionen (Kopplungsfenster, Widerruf, Fremd-Listener-Simulation, Identitäts-Reset) gibt es nur als Tasten in der App (R5).
- **Endpunkte:** `POST /p1/pair/m0`, `/p1/pair/m2`, `/p1/ch/hs`, `/p1/ch/rec`; Anwendungsoperationen nur `ping` und begrenzte binäre `dummy`-Records (≤ 60 KiB je Record, ≤ 64 MiB je Transfer). Keine Downloads, URLs, Cookies, Legacy- oder Produktionsendpunkte. Browser-Gate wie im PoC (Methode, Host, Content-Type, `X-Kadri-Helper`, Erweiterungs-Origin) – Schutz gegen Webseiten, keine Authentisierung. `TCP_NODELAY` auf jedem Socket.
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
| Reinstallation | App-Daten und Keystore-Schlüssel werden mit gelöscht → neue Identität; bisherige Kadri-Pins passen nicht mehr → Handshake scheitert, **kein** automatisches Neu-Pinnen, Nutzer koppelt neu |
| Keystore-Verlust / beschädigte Daten | expliziter Fehlerzustand (`ERROR_KEY_MISSING`, `ERROR_CORRUPT`), Server startet nicht; neue Identität nur über die Taste „Testidentität zurücksetzen“ (löscht auch Registrierungen) |
| Registrierungen | `files/registrations.v1` = `KPR1` ‖ Bootzähler ‖ Kern-Export (nur öffentliche Kadri-Schlüssel und IDs), atomar geschrieben – behebt die bisher nicht-atomare Speicherung |

## 5. Sicherheitsauflagen R1–R5 und weitere Risiken

| Punkt | Stand |
| --- | --- |
| R1 HS1-Replay verdrängt neue Handshakes | **bekanntes Verfügbarkeitsrisiko, nicht behoben.** Reproduziert in Rust (`r1_hs1_replay_can_evict_a_fresh_legitimate_handshake`) und auf dem Emulator (`r1ReplayedHs1EvictsAFreshHandshake`, Messwert §7). Wirkung auf Worker-Neustarts: Ein Neustart erfordert ohnehin einen neuen Handshake; wird dieser durch Replays verdrängt, scheitert der erste Request mit 404 und der nächste Handshake gelingt, sobald die Replays aufhören. Keine Vertraulichkeitsfolge. |
| R2 Bekannte Session-ID erlaubt DoS | **bekanntes Verfügbarkeitsrisiko, nicht behoben.** Getestet (Rust und Emulator): Müll-Record beendet die Sitzung, Übernahme unmöglich, neuer Handshake funktioniert. Authentisierung nicht geschwächt. |
| R3 Wanduhr | behoben: Android `elapsedRealtime`, Bootzähler, Reboot-Import im Kern; Kadri-Erweiterung nutzt `performance.now()` für Flow-Fristen |
| R4 Vorzeitiger Pin-Austausch | behoben auf **beiden** Seiten: Kadri ersetzt den Pin erst nach erfolgreichem authentisiertem Round-Trip; der Helper zieht ältere Registrierungen desselben Schlüssels erst zurück, wenn eine bereits bestätigte Registrierung erneut benutzt wird. Test: Desktop-Vorprüfung „R4“ und `r4_rejected_new_pin_keeps_the_previous_registration`. Die Desktop-Vorprüfung hat gezeigt, dass die Helper-Seite vorher die alte Registrierung zu früh verwarf. |
| R5 Testbrücke/Hooks | nicht übernommen: keine Firefox-Brücke, keine Test-Hooks, kein fremd erreichbarer Kanal für Pin/Forget/Pair; Kadri-Seite für Instrumentierung nur über JNI im Testprozess (`jni-test-initiator`, nur Test-App) |
| Limits = 0 | behoben: `Limits::sanitized()` (Minimum 1) und Abbruch der Verdrängungsschleifen; Test |
| Session-Cleanup | Sweep vor jeder Operation; Widerruf entfernt Sitzungen; Fehler entfernen nur die betroffene Sitzung |
| Temporäre Schlüssel in JNI/WASM | Rust-Seite zeroized (`Zeroizing`); Java-Kopie wird genullt; im WASM-Linearspeicher und im JS-Heap bleiben Kopien bis zur Speicherwiederverwendung (nicht steuerbar), Kadris Identität liegt zudem in IndexedDB im Klartext des Erweiterungsursprungs |
| Nicht-atomare Registrierungsspeicherung | behoben (`AtomicFile`); JVM-Stand-in bleibt Testwerkzeug |

## 6. Änderungen am Kern gegenüber dem eingefrorenen PoC

Nur in diesem Branch: `Limits::sanitized()` und Schleifen-Guards; `import_registrations(blob, now, same_boot)`; R4-Rückzugsregel; Feature `jni-test-initiator`; `strip = true` im Release-Profil; `rust-toolchain.toml` (1.97.0); `hostInit` mit Zeit und Boot-Flag. Protokoll und Krypto-Komposition unverändert. Tests: 39 Rust-Tests, Mutationsprobe siehe §7.

## 7. Ergebnisse

CI-Workflow `PoC Android capability`: Lauf 40596803035 (Commit `61876cc`) und Lauf 40597486902 (Commit `625c7c8`), beide **success**; Messwerte unten aus beiden Läufen.

**Toolchain:** rustc 1.97.0 (2d8144b78 2026-07-07) über `rust-toolchain.toml`, `cargo --locked` mit `Cargo.lock`, NDK 27.2.12479018 (r27c), API-Level 29 für den Linker, 16-KB-Seitenausrichtung, `--remap-path-prefix`, `strip = true`; JDK 17, Gradle 8.9, AGP 8.5.2, compile/target SDK 34.

**Reproduzierbarkeit:** zwei Builds aus unterschiedlichen Verzeichnissen, bitgleich:

| ABI | SHA-256 `libkadri_pairing_poc.so` | Größe |
| --- | --- | --- |
| arm64-v8a | `8bcb196b2178bcacdc1783ba6dbd0cdad63eab29d0f9ed660cfa4a8c8fa63c79` | 559 464 B |
| x86_64 | `73141cd440e70e7e8addf0fcc605f10d7a50032ac6775e15f92c06d208407ab5` | 663 200 B |

Dieselben Hashes stehen in der gebauten APK (`lib/<abi>/`). Die APK-Hashes selbst (`pake-poc-testapp.apk` `94c79b0e…174b` im CI-Lauf) sind **nicht** reproduzierbar, weil jeder Build einen eigenen Wegwerf-Signierer erzeugt (`CN=PAKE PoC TEST ONLY - throwaway`, im CI-Lauf Zertifikat-SHA-256 `3ef6597c…6ef3`) und diesen danach löscht. Der bisherige Helper-Signierer wird nirgends verwendet.

**Tests:**

| Ebene | Ergebnis |
| --- | --- |
| Rust-Kern (`cargo test --locked`) | 39/39 |
| Mutationsprobe (`tools/mutation_check.py`) | PASS: 43 Mutanten, 39 getötet, 4 begründet akzeptiert (redundante Schutzprüfungen bzw. Verteidigung in der Tiefe, z. B. Schleifen-Guard hinter `sanitized()`) |
| Desktop-Chromium-Vorprüfung der Vivaldi-Testerweiterung (`tools/desktop_check.mjs`) | 16/16 |
| Instrumentierung auf API-34-x86_64-Wegwerf-Emulator | **OK (11 tests)**, danach Logcat-Prüfung ohne Treffer auf Code, Schlüssel oder Session-Material |

Instrumentierungstests: Keystore-Umhüllung und explizite Fehlerzustände (manipulierte Datei, gelöschter Keystore-Schlüssel, nur expliziter Reset erzeugt neue Identität); Reboot-Erkennung der Registrierungen; Kopplung + Handshake + Ping + 8 MiB Dummy in begrenzten Records; falscher Code verbraucht das Fenster; falscher Pin; Browser-Gate und getrennter Probe-Port; Fremd-Listener nach aktiver Sitzung sieht nur Chiffrat, alte Sitzung ist danach unbrauchbar, Echt-Helper funktioniert wieder; Neustart behält Registrierungen, nicht Sitzungen; Widerruf und Neukopplung; R1; R2.

**Messwerte (Emulator, x86_64, nicht repräsentativ für ein Telefon):**

| Messung | Wert |
| --- | --- |
| Prozess beim Teststart | Java-Heap 8,4 MiB, Native-Heap 16,3 MiB, PSS 41,8 MiB, RSS 132 MiB |
| nach 8 MiB Dummy-Transfer | Java-Heap 3,6 MiB, Native-Heap 15,4 MiB, PSS 40,9 MiB, RSS 129 MiB, Spitzen-RSS (VmHWM) 160 MiB |
| Durchsatz | 8 MiB in 137 Records in 3,99 s bzw. 5,23 s ≈ 2,0 bzw. 1,5 MiB/s – ein HTTP-Request je 60-KiB-Record inklusive byteweiser Musterprüfung im Testprozess; Obergrenze für dieses Request-Muster auf dem Emulator, kein Wert für das spätere Streaming |
| R1 | 2 Replays verdrängen einen frischen Handshake in 9–33 ms; der nächste legitime Handshake gelingt in 16–42 ms |
| Keystore-Umhüllungsschlüssel | AES-256-GCM, Sicherheitsstufe 0 = `SECURITY_LEVEL_SOFTWARE` auf dem Emulator (erwartet); auf dem Telefon in der Statusanzeige der App („Schutz: …“) ablesen (TEE = 1, StrongBox = 2) |

Die Messwerte enthalten Test-Runner und Instrumentierung im selben Prozess. Auf dem Gerät werden sie über die Taste „Speicher messen“ bzw. `dumpsys meminfo de.evp.pakepoc.test` erhoben (DEVICE-TEST-GUIDE, Schritt 10).

**Wo die Test-APK ist:** nirgends dauerhaft. CI baut sie im Job, installiert sie im Wegwerf-Emulator und verwirft sie mit dem Runner; hochgeladen wird nur ein Textbericht ohne APK und ohne Schlüssel. Für den Gerätetest baust du sie lokal (DEVICE-TEST-GUIDE, Schritt 0) und vergleichst die `.so`-Hashes mit der Tabelle oben.

## 8. Noch nicht auf echter Hardware geprüft

Alles aus §1 in der Spalte „Vivaldi Android“; Installation über „Load unpacked“; Hintergrundverhalten von Vivaldi auf einem echten Gerät (Akkuoptimierung, Prozess-Kill); Keystore auf echter Hardware (Sicherheitsstufe des Umhüllungsschlüssels); Speicher-/RSS-Werte auf arm64; Verhalten nach echtem Reboot; Local-Network-Access-Abfragen; Firefox Android insgesamt.

## 9. Vor einer späteren öffentlichen Veröffentlichung

Unverändert aus PR #21 plus: positiver Vivaldi-Gerätetest nach dieser Anleitung; Firefox-Android-Test; Audit-Entscheidung für pakery/snow; Entscheidung, wie eine produktive Kadri-Erweiterung auf Vivaldi Android installiert wird (Chrome Web Store statt Entwicklermodus); R1/R2 als akzeptierte Restrisiken oder eigenes Design; produktive Umsetzung nur nach erneuter Freigabe.

## Stand

Branch `poc/android-vivaldi-capability` (Basis `b19851912f2e077391f14f01d6501a3ec145756b`). Kein Pull Request, kein Tag, kein Release. Die Workflow-Datei `.github/workflows/poc-android-capability.yml` liegt nur auf diesem Branch und läuft nur bei Pushes auf ihn. Produktions-Policy: `UNAPPROVED`. Issue #4: offen.
