# A/B-Diagnose: verzögerte Paket-Deinstallation im MediaProvider (Testplan)

Stand 2026-11-18. **Nur vorbereitet: kein Push, kein Emulatorlauf.** Produktcode unverändert (`546af14`, `7978a41`);
beide Lieferworkflows bleiben `LOCKED`. Das ist keine Lieferfreigabe.

## Hypothese (unbewiesen)

Der MediaProvider verarbeitet das `PACKAGE_FULLY_REMOVED` einer Deinstallation erst, wenn die Boot-Scans
(`REASON_MOUNTED`) auf demselben Worker-Thread beendet sind. Dann trifft das Verwaisen Zeilen der inzwischen neu
installierten App mit demselben Paketnamen:

- veröffentlichte Zeilen bekommen den Besitzer `NULL` und sind für die App unsichtbar (Muster von `0f989ca`);
- ausstehende Zeilen werden gelöscht (Muster `publish rows=0` von `be74008`).

Indizien:

- In beiden fehlgeschlagenen Läufen steht `owner_package_changed` 3–4 ms nach dem Scan-Ende, auf derselben TID.
- Dort ging dem Hauptlauf die Deinstallation der Mutanten-APK voraus.
- Diagnoselauf 44281951152 hatte **keine** Deinstallation, keine solche Zeile und 877/877 Downloads OK.

## Aufbau

Ein Job und ein Build: dieselbe Test-APK und dieselben synthetischen Daten. Der Inhalt hängt nur von `seq` ab; der
SHA-256 wird im Log mitgeschrieben. Zwei nacheinander frisch gestartete Wegwerf-Emulatoren, API 34 x86_64, jeweils mit
`-wipe-data` und Kaltstart. `tools/wait-device-ready.sh` läuft bewusst nicht, damit der Boot-Scan nicht abgewartet wird.

Beide Varianten durchlaufen dieselbe Befehlsfolge (`tools/run-pkgremoval-probe.sh`). Einziger Unterschied ist der
Paketschritt; das prüft der Selbsttest mit nachgebildetem adb.

| Schritt | A `control` | B `uninstall` |
|---|---|---|
| 1 | Frische prüfen: Emulator, API 34, Testpaket nicht installiert, keine Sondendateien; Liste fremder Dateien mit SHA-256 | gleich |
| 2 | Installation 1 (Marker `install1-begin/-end uid=`) | gleich |
| 3 | Phase `pre`: genau 3 Veröffentlichungen (seq 20000–20002), URI-Prüfungen bei +0/+250 ms | gleich |
| 4 | ab hier fragt der Host etwa alle 0,3 s die privilegierte MediaStore-Sicht mit Gerätezeit ab | gleich |
| 5 | **`install -r`** (Marker `update-begin/-end`) | **`uninstall …channeltest.test`, `uninstall …channeltest`**, sofort Neuinstallation (Marker `uninstall-*`, `install2-*`) |
| 6 | Phase `post` (seq ab 30000): 1 Auftrag, 500 ms Abstand, bis 8 s nach der Scanzeile, höchstens 120 Downloads/120 s; URI-Prüfungen bei +0/+250/+2000/+10000 ms | gleich |
| 7 | Phase `recheck` in neuem Prozess: jede ursprünglich erhaltene URI (pre und post), App-Sicht, privilegierte Sicht, Dateien mit SHA-256 | gleich |
| 8 | Liste fremder Dateien nachher; Bewertung `tools/pkgremoval_verdict.py variant` | gleich |

Die Marker werden mit `adb shell log -t BOOTSCAN` geschrieben, also mit derselben Uhr wie die MediaProvider-Zeilen
(`logcat -v epoch`). Mitgeschnitten werden die Puffer main, system, crash und events.

Downloadumfang: höchstens 123 je Variante, also höchstens 246 insgesamt. Erwartet werden deutlich weniger (Lauf
44281951152 hatte 877).

## Belege

Die Belege werden je Variante vor der Bewertung ins Job-Log geschrieben und als Textartefakt hochgeladen. Es wird keine
APK hochgeladen.

| Belegzeile | Inhalt |
|---|---|
| `PKGAB_LOG` | Marker; `Scanned …`-Zeilen; `owner_package_changed`; `PACKAGE_(REMOVED\|FULLY_REMOVED\|ADDED\|REPLACED)`; alle Zeilen mit dem Paketnamen (mit Zeit, PID, TID) |
| `PKGAB_HOSTQ` | Host-Abfragen: Gerätezeit, `_id`, Besitzer, ausstehend/Papierkorb, Pfad |
| `PKGAB_INSTR` | vollständige Instrumentierungsausgaben der drei Phasen |
| `PKGAB_LIST` | fremde Dateien vorher und nachher |
| `PKGAB_DOWNLOAD` | je Download: Generation, Klasse, `at_risk`, ursprüngliche ID, Begründung (spätere ID, Besitzer, Hash) |
| `PKGAB_GENERATION`, `PKGAB_NOTE`, `PKGAB_REASON` | Zählung je Generation, Zeitachse, Gründe |
| `PKGAB_VARIANT` / `PKGAB_RESULT` | Ergebnis je Variante bzw. A/B-Gesamtergebnis |

## Klassen je Download

Die Generationen werden getrennt ausgewertet:

- `install1`: Phase pre, vor dem Paketschritt;
- `install2`: Phase post nach der Neuinstallation (B);
- `updated`: Phase post nach der Aktualisierung (A).

| Klasse | Bedeutung |
|---|---|
| `OK` | gleiche ID, eigener Besitzer, sichtbar, Hash gleich |
| `OWNER_CHANGED` | **Besitzerwechsel**: gleiche ID existiert privilegiert, Besitzer nicht mehr die App |
| `REPLACED` | **ID-Ersatz**: ursprüngliche ID fehlt, andere Zeile für denselben Pfad |
| `LOST` | **Verlust der veröffentlichten Zeile**: weder ID noch Zeile für den Pfad; Datei vorhanden |
| `STATE_CHANGED` | gleiche ID, wieder ausstehend oder im Papierkorb |
| `PUBLISH_FAILED` | Engine meldete nicht „done“ (z. B. `publish rows=0`) |
| `ORPHANED_EXPECTED` | **normales Deinstallationsverhalten**: `install1`-Zeile in B mit Besitzer `NULL` |
| `NOT_ORPHANED` | `install1`-Zeile in B gehört nach der Deinstallation weiter der App (berichtet) |
| `UNKNOWN` | Beobachtung fehlt oder ist unzuverlässig; ist nie ein Beweis |
| `DATA_LOSS` | Sondendatei fehlt oder Inhalt geändert |

Fremde Dateien (Regel `foreign=v2`):

- **gelöscht oder verändert** ergibt DATA_LOSS;
- **neu hinzugekommen** (z. B. vom System angelegte `.thumbnails/.nomedia`) ergibt nur `FOREIGN_ADDED` als Hinweis.

## Zeitpunkt des Verwaisens in B

Genau ist er, wenn eine `owner_package_changed`-Zeile ein Sondenkennzeichen nennt. Sonst gilt das Intervall zwischen
der letzten Beobachtung „gehört der App“ und der ersten Beobachtung „Besitzer weg“, aus Host-Abfragen und privilegierten
Abzügen.

- **verzögert**: das Intervall liegt vollständig nach `install2-end`;
- **nicht verzögert**: das Ende des Intervalls liegt vor `install2-end`;
- **mehrdeutig**: das Intervall überspannt `install2-end`.

`at_risk` bedeutet: ein `install2`-Download wurde angelegt, bevor das Verwaisen beobachtet war.

## Ergebnisse (nie PASS)

| Variante | Ergebnis |
|---|---|
| B | `SUPPORTED`: verzögertes Verwaisen, und mindestens ein `at_risk`-Download ist OWNER_CHANGED, LOST, REPLACED, STATE_CHANGED oder PUBLISH_FAILED; spätere Downloads sind unauffällig |
| B | `NOT_SUPPORTED`: verzögertes Verwaisen, alle `at_risk`-Downloads OK |
| B | `INCONCLUSIVE`: kein Verwaisen beobachtet; nicht verzögert; Zeit mehrdeutig; kein `at_risk`-Download; `UNKNOWN` unter `at_risk`; oder auch spätere Downloads fehlerhaft (dann erklärt die Hypothese den Fehler nicht) |
| A | `CLEAN`: alle OK, kein `owner_package_changed` nach der Aktualisierung, mindestens ein Download überspannt das Scan-Ende |
| A | `ANOMALY`: Abweichung ohne Deinstallation |
| A | `INCONCLUSIVE`: keine Überschneidung mit dem Scan-Ende |
| beide | `DATA_LOSS`; `INVALID` bei unvollständigen Belegen (Marker, Reihenfolge, Phasen, Instrumentierung, URI-Prüfungen, Fremdliste) |

A/B-Gesamtergebnis:

- `HYPOTHESIS_SUPPORTED` nur bei B `SUPPORTED` **und** A `CLEAN`;
- `HYPOTHESIS_NOT_SUPPORTED` bei B `NOT_SUPPORTED`;
- `INVALID` oder `DATA_LOSS`, wenn eine Variante es ist;
- sonst `INCONCLUSIVE`.

Exit-Codes von `ab`: 0 = SUPPORTED oder NOT_SUPPORTED, 2 = INVALID, 3 = DATA_LOSS, 4 = INCONCLUSIVE. Ein roter Job ist
also bei INCONCLUSIVE erwartbar und kein Produktbefund.

Tritt die relevante Überschneidung nicht ein, ist das Ergebnis INCONCLUSIVE, nie eine Bestätigung. Dafür muss die
Deinstallation während des Scans liegen und post-Downloads müssen vor dem Verwaisen beginnen.

## Grenzen

- A läuft vor B auf demselben Runner. Der zweite Kaltstart kann wegen warmer Host-Caches schneller sein. Die Zeitachse
  (Marker gegenüber Scan-Ende) steht deshalb je Variante in `PKGAB_NOTE`.
- Ob der Paketschritt in den Scan fällt, lässt sich nicht erzwingen. In Lauf 44281951152 endete der externe Mount-Scan
  etwa 38 s nach dem Start der ersten Testphase. Der Paketschritt hier liegt erwartungsgemäß etwa 10–25 s danach.
- Ein Ergebnis gilt nur für API 34 und den Emulator; auf Geräten wurde nichts geprüft.

## Lokal geprüft

| Prüfung | Ergebnis |
|---|---|
| `tests/bootscan/test_pkgremoval_verdict.py` | PASS, 37 Fälle |
| `tests/bootscan/test_pkgremoval_script.py` | PASS, 31 Fälle (nachgebildetes adb) |
| `tests/bootscan/test_bootscan_verdict.py` | PASS, 39 Fälle, davon 3 neu für `foreign=v2` |
| `tests/bootscan/test_bootscan_script.py` | PASS, 11 Fälle |
| Java-Kompilierung der Probe gegen android-all und Stubs | fehlerfrei; nur die bekannten umgebungsbedingten Fehler in Produktdateien, unverändert |
| `bash -n`, YAML | geprüft |
| Nachbewertung von Lauf 44281951152 | `evidence/bootscan-diagnosis/run44281951152/INDEX.md`; Originalergebnis DATA_LOSS bleibt |

## Auslöser (exakt)

Ein **Push des neuen Branches `diag/pkgremoval-ab`** startet von 21 Workflows nur `pkgremoval-probe.yml`:

- Es ist der einzige mit passendem Branch-Filter; alle anderen nennen den Branch nicht.
- Der Pfadfilter passt bei jeder Vergleichsbasis, weil die Workflowdatei selbst neu ist.
- Es entsteht kein Pull Request, also kein `pull_request`-Lauf; es gibt keine `workflow_run`- oder `create`-Auslöser.
- Die installierten Apps legen erfahrungsgemäß nur leere Check-Suites an.

Nicht berührt werden PR #21, PR #27, `ccr-791d6ed0-rmfzvd`, `feature/c3-browser-downloads`, `diag/bootscan-probe` und
Kadri.

Ein weiterer Push auf den Branch würde erneut auslösen, wenn er Dateien aus dem Pfadfilter ändert. Deshalb genau ein
Push und danach keiner mehr ohne neue Freigabe.
