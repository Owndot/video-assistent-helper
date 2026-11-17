# P4-Testlieferung – Vorbereitung nach dem Codex-Release-Readiness-Review

Stand 2026-11-17. Codex-Urteil: **CONDITIONAL GO** für eine isolierte Samsung-Android-17-Diagnoselieferung.
Diese Vorbereitung behebt die Lieferketten- und Gate-Blocker. **Keine Lieferung gestartet**, beide Freigaben
geschlossen (`DELIVERY_APPROVAL: 'none'`), Produktion **UNAPPROVED**.

## Runde 3 – Codex NO-GO abgearbeitet (2026-11-17, maßgeblich vor allem Folgenden)

Beide Lieferworkflows bleiben `LOCKED`. Keine APK, kein ZIP, keine Signierung, kein Upload. Anwendungscode unverändert
(Helper `546af14`, Kadri `7978a41`).

- **Freigabesperre gegen Re-Runs (P1).**
  - Der Paketjob führt die volle Sperre selbst erneut aus (`LOCK_REQUIRE_GO=1`): vor dem Checkout, vor dem Signieren,
    vor dem Upload.
  - Dabei wird jedes Mal geprüft: `GITHUB_RUN_ATTEMPT` = 1, Ablauf, Einmaligkeit der ID, Neueinführung,
    Pins, Lieferumfang und geschütztes Environment.
  - Ein Re-Run des Paketjobs mit wiederverwendeter Gate-Ausgabe oder eine inzwischen abgelaufene Freigabe sperrt.
- **Unabhängige menschliche Freigabe (P1).** Mit den heutigen Rechten nicht technisch durchsetzbar: Der Agent handelt
  mit `admin: true`. Neu ist die Pflicht `environment: p4-delivery` mit Required Reviewers; die Sperre prüft sie fail
  closed. Der manuelle Einrichtungsschritt steht in [P4-APPROVAL-PROPOSAL.md](P4-APPROVAL-PROPOSAL.md).
- **CI-Evidenz (`tools/delivery/ci_evidence.py`).**
  - Vollständige Pagination und jeder Versuch einzeln abgefragt.
  - Abgebrochene Jobs werden auf vorhandene Fehlerbelege geprüft.
  - Laufende Läufe, auch ohne gelistete Jobs, sperren.
  - Abdeckung von API 26, 29 und 34 verlangt.
  - Fehlende Logs, API-Fehler und Widersprüche sperren.
  - Positivbelege zählen nur, wenn der getestete Merge-Baum nachweislich dem Commit entspricht.
  - 30 Regressionstests mit Attrappen.
- **Geräte-Gate (`tools/device_gate.py`).**
  - Globale Prüfung des gesamten Logs auf Abstürze, auch App-FATAL außerhalb des Crash-Abschnitts und OOM, und auf
    Veröffentlichungsfehler. Ausgenommen sind nur der erwartete Mutantenlauf und die Sonden-Belegzeilen.
  - Der Mutantenlauf muss vollständig sein und aus genau dem erwarteten Grund scheitern.
  - Statuszeilen außerhalb der Abschnitte dürfen dem Hauptbericht nicht widersprechen.
  - Kein weiterer oder unvollständiger Block nach dem Runner-Ende.
  - Parserfehler führen zur Ablehnung.
  - 87 Fixtures: die bisherigen 63 und 24 neue Gegenbeispiele.
- **Alle 22 Wiederholungen belegt.**
  - `tools/run-flaky-repeat.sh` schreibt jeden Lauf mit Kennung und API ins Log.
  - Das Gate prüft Anzahl, Kennungen, API, Testmengen, kein `OK (0 tests)`, keine übersprungenen Tests und die
    Zusammenfassung.
  - Logs im alten Format ergeben `INSUFFICIENT_EVIDENCE` (Exit 4). Das ist nie PASS: Bisher grüne Läufe gelten
    deshalb nicht mehr als Beleg. Rote bleiben REJECT.
- **Kadri-Freigabepfad.** Die normale CI unterscheidet jetzt LOCKED (`none`), eine gültige noch nicht ausgeführte
  Freigabe (PENDING, wird nicht abgelehnt) und eine ungültige Freigabe (Fehler). Der positive Pfad ist nur mit
  Attrappen getestet.
- **Veröffentlichungsfehler sind kein Android-10-Thema.**
  - `publish rows=0` bzw. `IOException: publish` tritt auf API 34 und API 29 auf. Er ist nicht behoben und nicht
    mit SIZE gleichzusetzen.
  - Eine OOM-Ursache ist nicht belegt: Auf API 34 lag kein Speicherengpass vor.
  - Alle bekannten Logs sind mit Run-, Attempt- und Job-ID sowie SHA-256 gesichert:
    [evidence/publish-failures/INDEX.md](evidence/publish-failures/INDEX.md).
- **Verwaiste Datei und Namenskollision.** Gezielter Test nur auf Wegwerf-Emulatoren:
  - Testklasse `OrphanNameCollisionProbeTest`, aufgerufen von `tools/run-orphan-collision-probe.sh`, Workflow
    `orphan-collision-probe.yml`.
  - Ergebnis siehe unten bzw. im Abschlussbericht.
- **Diagnoseausnahme:** nur als Entwurf in [P4-APPROVAL-PROPOSAL.md](P4-APPROVAL-PROPOSAL.md). Nicht aktiviert.

**CI-Stand Runde 3 (Helper `9d6b716`, gleicher Anwendungscode wie `546af14`, Lauf 44108658790):**

- **API 26 und API 34:** Gate `PASS` mit 22 einzeln belegten Wiederholungen. Das sind die ersten vollständigen
  Positivbelege im neuen Format.
- **API 29:** `REJECT`. `duplicateAndLateStartsFetchOnce` scheitert mit `publish rows=0`; die vollständige Datei
  (`diskFinal=exists(65536)`) liegt ohne MediaStore-Zeile auf der Platte. App-Heap 512 MiB, kein Systemabsturz: Auch
  auf API 29 tritt der Fehler also ohne Speicherengpass auf.
- **Lieferung:** Nach den Regeln weiter gesperrt (`ci_evidence.py`: REJECT wegen API 29 und der älteren Fälle).
- **Auf `4fd7350` (Lauf 44113835443):**
  - API 26 und API 34 `PASS`, API 29 `ACCEPTED_SIZE_ONLY`.
  - `ci_evidence.py` über alle 10 Commits mit gleichem Code: Positivbelege API 26 ×3, API 29 ×1, API 34 ×2.
  - Gesamturteil weiterhin REJECT: fünf abgewiesene Läufe mit Veröffentlichungsfehlern bzw. ungültiger Umgebung.

**Sonde verwaiste Datei / gleicher Name** (Lauf 44108642683, Wegwerf-Emulatoren, synthetische Datei mit bekanntem
SHA-256 ohne MediaStore-Zeile, danach echter Download desselben Titels mit anderem Inhalt):

- **API 34:**
  - Der Download ist `done` und wird als `<title> (1).mp4` veröffentlicht, mit genau dem neuen Inhalt.
  - Die verwaiste Datei bleibt bytegleich, andere Dateien sind unverändert.
  - Kein Überschreiben, kein Datenverlust.
- **API 29:**
  - Der Download ist `done`; die verwaiste Datei bleibt bytegleich erhalten.
  - Der neue Inhalt liegt als eigene Datei `<title> (1).mp4`.
  - Die MediaStore-Zeile trägt aber den Anzeigenamen `<title>.mp4`, während ihre Datei `<title> (1).mp4` heißt.
- **Endgültige Fassung `4fd7350` (Lauf 44113819135):** Pfad je Zeile protokolliert, Aufnahme durch den Host vor dem
  Aufräumen.
  - Auf **beiden** APIs gilt: verwaiste Datei unverändert, neuer Download als eigene Datei, andere Dateien unverändert.
  - Kein Überschreiben und kein Datenverlust nachgewiesen. Ein Fixvorschlag gegen Überschreiben ist deshalb nicht nötig.
  - Restrisiko: Die verwaiste Datei bleibt dauerhaft ohne Eintrag liegen.
  - Auf Android 10 weichen zusätzlich Anzeigename und Dateiname voneinander ab.
- **Bewertung der Sonde** (`tools/delivery/orphan_verdict.py`, nach dem Codex-Hinweis zur Sondenschwäche):
  - **PASS (Exit 0)** nur, wenn alles zusammen gilt:
    - gültige Ausgangslage: genau die verwaiste Datei mit bekanntem SHA-256;
    - Test genau einmal `OK (1 test)`;
    - `state=done`;
    - eine neue Datei mit genau dem neuen SHA-256 unter einem anderen Namen;
    - die verwaiste Datei ist bytegleich;
    - alle übrigen Dateien sind unverändert.
  - **DATA_LOSS (3):** Die verwaiste Datei fehlt oder wurde verändert, oder andere Dateien haben sich verändert.
  - **INVALID (2):** jede ungültige oder unvollständige Sonde, darunter `failed:storage`/`failed:internal` und keine
    neue Datei. Das ist nie ein Sicherheits-PASS.
  - Der Test selbst verlangt `done`.
  - Beide Aufnahmen werden mit SHA-256 vor dem Aufräumen ins Log geschrieben.
  - Aufgeräumt werden in jedem Fall nur die synthetischen Dateien mit dem zufälligen Sondennamen.
  - 33 Selbsttests in `tests/delivery/test_orphan_verdict.py`.
- **Erster Lauf (`cb45ca2`/`7513ae9`):** nicht aussagekräftig. Er brach mit `not_mp4` vor der Veröffentlichung ab.

## Verbindliche Quellen (Pins)

| Rolle | Commit |
|---|---|
| Helper-Anwendungscode der Test-APK `de.evp.helfer.p4test` (`SOURCE_SHA`) | `546af144fe2c2efa00c9c93b745c695f6077dbaa` |
| Gepaarte Kadri-Quelle (`KADRI_PAIR_SHA`) | `7978a41232c3b1582289c53461da4fe63ad28ff9` |
| Kadri-P4-Erweiterungs-ID | `pcccpjllneffccpenbepmfbkjedllkkf` |
| Herkunft des in Kadri vendorten WASM-Kerns (nur Information, nicht diese APK) | `bd5ab293bbc6729cabafe72b6bd5606f8c74f5e5` |

Die APK baut ihre native Kanalbibliothek selbst aus `SOURCE_SHA`. Der WASM-Kern in Kadri stammt nachweislich aus
`bd5ab29` (bitgenauer Neuaufbau in Kadri); er wird nicht auf `546af14` umetikettiert. Ob sich die Kernquellen
zwischen beiden Commits unterscheiden, zeichnet die Kadri-Lieferung als eigene Angabe auf (lokal geprüft: keine
Änderung unter `poc/pake-channel/core` zwischen `bd5ab29` und `546af14`).

Nach `SOURCE_SHA` weichen nur Liefer- und Prüfwerkzeuge ab, die nicht in die APK eingehen:
`.github/workflows/p4-test-package.yml`, `.github/workflows/helper-channel.yml`, `tools/device_gate.py`,
`tools/delivery/*.py`, `tests/gate/**`, `tests/delivery/**` und Root-Markdown. Die Scope-Prüfung des Paketjobs
lässt genau diese Liste zu; gebaut wird aus einem Checkout von `SOURCE_SHA`.

## P1 – Freigabesperre (beide Lieferworkflows)

Gleiche Sperre in Helper (`p4-test-package.yml`) und Kadri (`p4-test-extension.yml`), maßgebliche Datei
`tools/delivery/lock.py` (in beiden Repositories byte-gleich, im Workflow wörtlich eingebettet).

- Job `gate` entscheidet **ohne Checkout**. Der Paketjob läuft nur bei `go == 'yes'` und prüft die Sperre in seinem
  ersten Schritt erneut, vor Checkout, Bau, Signierung und Upload.
- Standard gesperrt: `DELIVERY_APPROVAL: 'none'`. Ein normaler Push, ein Doku-Commit oder eine Pin-Korrektur
  liefert nie.
- Eine Freigabe ist ein Wert `SOURCE_SHA=…;KADRI_PAIR_SHA=…;ID=<id>;UNTIL=<YYYY-MM-DDTHH:MMZ>` (Kadri:
  `SOURCE_SHA`, `HELPER_SHA`, `WASM_CORE_ORIGIN_SHA`). Er gilt nur, wenn:
  - jeder Wert dem Pin entspricht;
  - der Push genau diese Zeile neu einführt (Stand vor dem Push abweichend);
  - die Kopf-Commit-Nachricht die Zeile `Delivery-Approval: <id>` trägt;
  - kein anderer Lauf dieses Workflows die ID oder denselben Commit schon trug;
  - es der erste Versuch ist (kein Re-Run);
  - der Auslöser der Repo-Eigentümer ist;
  - `UNTIL` in der Zukunft liegt, höchstens 72 h entfernt.
- Jede nicht prüfbare Bedingung (API-Fehler) sperrt. Eine benutzte ID ist verbraucht, auch wenn die Lieferung
  danach scheitert.
- Selbsttest `tests/delivery/test_lock.py`:
  - Inline-Kopie == Datei.
  - Workflowstruktur.
  - Standard gesperrt.
  - 27 Einzelverletzungen werden jeweils gesperrt.
  - Positivkontrolle nur mit Attrappen.
  - Mutationsprobe: 12 abgeschwächte Sperrbedingungen werden alle erkannt.
  - Ende-zu-Ende ohne GitHub-API: gesperrt bzw. fail closed.
  - Läuft in der CI (`helper-channel.yml`, Kadri `ci.yml`).

Grenze: Ein Werkzeug, das mit den GitHub-Zugangsdaten des Eigentümers pusht, erscheint als Eigentümer. Die Sperre
verhindert jede *automatische* oder *geerbte* Lieferung, ersetzt aber nicht die Regel, dass nur der Eigentümer eine
Freigabezeile einträgt.

### Freigabe durch den Eigentümer (erst nach dem letzten Codex-Review)

1. In `.github/workflows/p4-test-package.yml` `DELIVERY_APPROVAL: 'none'` ersetzen durch
   `DELIVERY_APPROVAL: 'SOURCE_SHA=546af144fe2c2efa00c9c93b745c695f6077dbaa;KADRI_PAIR_SHA=7978a41232c3b1582289c53461da4fe63ad28ff9;ID=<neue-id>;UNTIL=<UTC-Zeit, ≤ 72 h>'`.
2. Committen mit der Nachrichtenzeile `Delivery-Approval: <neue-id>` und als einzelnen Push auf
   `feature/c3-browser-downloads` senden.
3. Kadri entsprechend in `p4-test-extension.yml` mit
   `SOURCE_SHA=7978a41…;HELPER_SHA=546af14…;WASM_CORE_ORIGIN_SHA=bd5ab29…;ID=<andere-id>;UNTIL=…`.
4. Danach `'none'` wieder eintragen. Das ist nicht nötig für die Sicherheit, weil eine geerbte Freigabe nie
   liefert, aber es schafft Klarheit.

## P2 – Geräte-Gate (`tools/device_gate.py`)

Das Gate prüft jetzt gegen eine feste erwartete Menge:

- **Gerätebefehl:** genau der erwartete Befehl (Mutantenprobe, Gerätetest, 20 Dreier- und 2 Klassen-Wiederholungen).
- **Mutantenprobe:** genau ein Lauf mit genau dem erwarteten Fehlschlag, vor dem Gerätetest.
- **Haupttest:** genau 46 benannte Tests. Jeder wird genau einmal begonnen und beendet. Die Zählung ist konsistent.
  Kein ignorierter oder übersprungener Test.
- **Sonden (ab API 29):** genau 5 Tests.
  - Jede der 7 Varianten genau einmal, mit der festgelegten **positiven** Zyklenzahl:
    PLAIN 60, Pausen-Varianten 20, LARGE_DELETE und CONCURRENT_PROVIDER_LOAD je 15.
    `ok == cycles` und `failed == 0` genügen allein nicht mehr.
  - Alle Belegzeilen vorhanden, nur für die eigene API.
  - Auf API 26 ausdrücklich übersprungen.
- **Neustart:** 4 Phasen mit genau dem erwarteten Test.
- **Wiederholungen:** genau eine Zeile für **diese** API mit 22 Läufen. Jeder Fehlschlag ist vollständig belegt und
  der richtigen Testart zugeordnet. Klassenläufe enthalten genau 11 Tests. Ein Fehlschlag eines Dreierlaufs wird nie
  akzeptiert.
- **Abstürze, Umgebung, Nebenbedingungen:**
  - Crash-Puffer leer; kein System-, Prozess- oder Emulatorabsturz irgendwo im Log.
  - Heap-Beleg ≥ 128 MiB Pflicht.
  - Port 47811 zu, Paketliste unverändert, Datei nach Deinstallation vorhanden.
  - Nur bekannte Abschnitte in richtiger Reihenfolge.
  - Fehlerzeilen des Jobs nur die eine erwartete beim SIZE-Befund.
  - Vollständiges Job-Log bis zum Job-Ende.

Unverändert: Einzige Ausnahme ist P4B-A10-SIZE auf API 29, nur für den isolierten Android-17-Test. Nie akzeptiert
werden `publish rows=0`, unvollständige Sonden oder Systemabstürze.

Belege (`tests/gate/test_device_gate.py`):

- **63 synthetische Logs:** 4 zulässig, 59 abgewiesen. Darunter `cycles=0`, zu wenige Zyklen, fehlende oder
  zusätzliche Tests, falsche API, falsche Wiederholungs-API, zu wenige Wiederholungen, zusätzliche Fehler,
  Systemabsturz, unvollständige Logs.
- **Abgleich mit dem Quellstand:** `@Test`-Methoden, Varianten und Zyklenkonstanten, `run-flaky-repeat.sh` und der
  Gerätebefehl in `helper-channel.yml`.
- **Echte vollständige Job-Logs** (lokal mit `test_device_gate.py <dir>` geprüft, Logs nicht im Repository):
  - `546af14`: API 26 PASS, API 29 ACCEPTED_SIZE_ONLY, API 34 PASS (gleiches Urteil wie die alte Fassung).
  - `261b77c`: API 29 ACCEPTED_SIZE_ONLY.
  - Früher fehlerhafte Läufe bleiben REJECT: `291cff1` API 29 (`publish rows=0` + Systemspeicherfehler),
    `e5fb290` API 34 (`publish rows=0`), `e5fb290` API 29.

Der Paketjob nimmt das Gate aus dem Workflow-Commit (nicht aus `SOURCE_SHA`) und schreibt dessen SHA-256 in die
Evidenz.

**CI-Belege ohne Rosinenpicken** (`tools/delivery/ci_evidence.py`, im Paketjob nach der Sperre):

- Nicht nur der Lauf auf `SOURCE_SHA` zählt, sondern **jeder** abgeschlossene Emulator-Job aller helper-channel-Läufe
  und aller Versuche auf jedem Commit mit gleichem Anwendungscode (`SOURCE_SHA` bis zum Workflow-Commit).
- Jeder dieser Jobs muss das Gate bestehen. Ein späterer roter Lauf desselben Codes lässt sich weder durch einen
  grünen Lauf noch durch einen Re-Run verdecken.
- Abgebrochene Jobs zählen nicht als Beleg und werden ausgewiesen; ein laufender Job sperrt.
- Trockenlauf gegen die echten Läufe (nur lesend): 8 Emulator-Jobs auf `546af14`, `261b77c` und `f3bf6a4` bewertet,
  Ergebnis **REJECT**, weil API 29 auf `f3bf6a4` abgewiesen wird (siehe unten).

### Neuer Befund: API 29 auf `f3bf6a4` (Anwendungscode = `546af14`), Lauf 44090927478

- **`publish rows=0`** in `interruptionFailsCleanOrResumesExactly`, `proof=present cleanup=not-observed`.
  Der Provider-Schnappschuss lautet `downloads=absent filesSameId=absent finalNameOtherIds=0 diskFinal=exists(8388608)
  diskPending=absent`. Die vollständige Datei liegt also unter dem Endnamen auf der Platte, ohne MediaStore-Zeile.
  Der MediaProvider hatte sie vorher umbenannt ("Moving …pending--C2 Resume … to …C2 Resume …").
- **Etwa 0,6 s später OOM im Systemprozess** (`FATAL EXCEPTION IN SYSTEM PROCESS`, Wachstumslimit 16 MiB), danach
  `DeadSystemException`. Hauptlauf und Sonden wurden abgebrochen.
- **Gleiches Muster wie `291cff1`** (API 29, Versuch 2: `publish rows=0` und System-OOM mit 16 MiB). In grünen
  API-29-Läufen hat der App-Prozess 512 MiB; die Geräte-Properties sind identisch.
- **Hypothese, nicht bewiesen:** Auf API 29 entsteht `rows=0` in Emulator-Starts mit Systemspeicher-Engpass
  (MediaProvider bricht nach dem Umbenennen ab, H3).
- **Folge für die Wiederherstellung:** Eine solche Datei bleibt ohne Eintrag im Download-Ordner, während die App
  „Speichern fehlgeschlagen“ meldet.
- Für API 34 (`e5fb290`) ist kein Speicherengpass belegt.
- Das Gate weist den Lauf korrekt ab (Veröffentlichungsfehler, Systemabsturz, unvollständige Tests). Nach den Regeln
  ist eine Lieferung von `546af14` deshalb **derzeit gesperrt**, bis der Eigentümer über diesen Befund entscheidet.
  Ein grüner Wiederholungslauf hebt den roten nicht auf.

### Weitere Befunde auf `b18a355` (Anwendungscode = `546af14`), Lauf 44093078605

- **API 34:** `duplicateAndLateStartsFetchOnce` scheiterte mit `publish rows=0 proof=present cleanup=not-observed`.
  - Kein Speicherengpass: App-Heap 192 MiB, kein Systemabsturz. Die Wiederholungen waren 22/22 grün.
  - Provider-Schnappschuss: `downloads=absent filesSameId=absent finalNameOtherIds=0 diskFinal=exists(65536) diskPending=absent`.
  - Das Gate weist den Lauf ab.
- **API 29:** Der Emulator lief mit 16 MiB App-Heap (ungültige Testumgebung, zwei HLS-Speichertests rot). Das Gate
  weist den Lauf ab.

**Korrektur früherer Aussagen zu `publish rows=0`.** Bisher hieß es „sauber beendet, keine Reste“. Das ist widerlegt.

- In beiden CI-Fällen mit Provider-Schnappschuss (API 29 `f3bf6a4`, API 34 `b18a355`; der frühere API-34-Fall `e5fb290`
  hatte noch keinen Schnappschuss) liegt die vollständige Datei unter dem Endnamen auf der Platte. Es gibt aber keine MediaStore-Zeile mehr und keine
  ausstehende Datei.
- Die Datei wurde also nicht gelöscht: Der MediaProvider hat sie umbenannt, die Zeile ging verloren, und das Update
  meldete 0.
- Damit erklärt H1 (Löschen während des Updates; in der Sonde bleibt dabei nichts zurück) die CI-Fehler **nicht**.
  Die Belege sprechen für eine Ursache auf der Provider-Seite (H2/H3). Bewiesen ist sie nicht.
- **Folge im Produkt:** Die App meldet „Speichern fehlgeschlagen“, die fertige Datei bleibt aber ohne Eintrag im
  Download-Ordner. Sie ist für die Galerie unsichtbar, bis neu gescannt wird, und ein neuer Download erzeugt eine
  zweite Datei.
- **Häufigkeit** unter dem gleichen Code seit `546af14`: API 34 in 1 von 3 Läufen, API 29 in 1 von 3 Läufen (dort
  zusammen mit dem System-OOM).

## P2 – Liefermetadaten

- Der Artefaktname wird aus `SOURCE_SHA` und der Freigabe-ID gebildet; alte, fest kodierte Namen sind entfernt.
- `helper_update_required: true`, `pairing_reset_required: true`, `existing_p4_install_compatible: false`.
  Grund: Jeder Lauf erzeugt einen neuen Wegwerf-Signierer. Eine vorhandene `de.evp.helfer.p4test` muss deshalb
  deinstalliert werden (ein Update ist unmöglich, App-Daten und Kopplung gehen verloren), und Kadri P4 TEST muss neu
  gekoppelt werden. Andere Installationen bleiben unberührt.
- In der Evidenz stehen außerdem:
  - Freigabe-ID, Quell- und Workflow-Commit;
  - Gate-Hash;
  - Kadri-Paar, Erweiterungs-ID und WASM-Herkunft;
  - APK-SHA-256, Signierer-Fingerabdruck, Zertifikatsgültigkeit (≤ 3 Tage) und Artefakt-Ablauf (2 Tage);
  - die Restrisiken und `production_status: UNAPPROVED`.
- Die Änderungsbeschreibung bezieht sich auf die letzte P4-Lieferung `8b906c5`.

## Erst bei einem genehmigten Paketbau prüfbar (nicht ausgeführt, nicht als bestanden gemeldet)

- Reproduzierbarer Bau (zwei Wege) und Wegwerf-Signierung samt Schlüssellöschung.
- Prüfung der signierten APK vor und nach dem Emulator: Paket, Berechtigungen, Signierer, Bibliotheken.
- Installations-Rauchtest auf API 34 und der Upload.
- Das tatsächliche Urteil von `ci_evidence.py` im Paketjob (Log-Abruf mit dem Workflow-Token). Lokal mit identischer
  Logik, `gh` lesend und den echten Logs geprüft; Ergebnis derzeit REJECT.
- Der positive Pfad der Freigabesperre gegen die echte GitHub-API. Bisher nur mit Attrappen geprüft; die negativen
  Pfade laufen auch in der CI.

## Restrisiken (unverändert, keine Produktänderung in dieser Runde)

- Der sporadische MediaStore-Veröffentlichungsfehler (`publish rows=0`) ist nicht ursächlich erklärt und tritt auf API 29
  und API 34 auf. Dabei bleibt die fertige Datei ohne MediaStore-Eintrag zurück, während die App den Fehler meldet.
- Die API-29-Testumgebung ist instabil (sporadisch 16 MiB Heap für App oder Systemprozess).
- Die HLS-Engine besitzt noch ein separates, nicht vollständig abgesichertes Abschlussmuster.
- Bekannte Android-10-Abweichung: SIZE-Spalte 0, und die Ablehnung eines belegten Endnamens. Keine Android-10-Freigabe.
- Die realen HTTP-404/412-Probleme sind das nächste Produktarbeitspaket.
