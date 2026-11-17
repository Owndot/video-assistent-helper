# P4-Testlieferung – Vorbereitung nach dem Codex-Release-Readiness-Review

Stand 2026-11-17. Codex-Urteil: **CONDITIONAL GO** für eine isolierte Samsung-Android-17-Diagnoselieferung.
Diese Vorbereitung behebt die Lieferketten- und Gate-Blocker. **Keine Lieferung gestartet**, beide Freigaben
geschlossen (`DELIVERY_APPROVAL: 'none'`), Produktion **UNAPPROVED**.

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
- Das tatsächliche Gate-Urteil über die CI-Logs auf `SOURCE_SHA` innerhalb des Paketjobs. Lokal mit identischer
  Logik und den echten Logs geprüft.
- Der positive Pfad der Freigabesperre gegen die echte GitHub-API. Bisher nur mit Attrappen geprüft; die negativen
  Pfade laufen auch in der CI.

## Restrisiken (unverändert, keine Produktänderung in dieser Runde)

- Der sporadische MediaStore-Veröffentlichungsfehler (`publish rows=0`) ist nicht ursächlich erklärt.
- Die HLS-Engine besitzt noch ein separates, nicht vollständig abgesichertes Abschlussmuster.
- Bekannte Android-10-Abweichung: SIZE-Spalte 0, und die Ablehnung eines belegten Endnamens. Keine Android-10-Freigabe.
- Die realen HTTP-404/412-Probleme sind das nächste Produktarbeitspaket.
