# Diagnose: MediaStore-Zeile während des ersten Volume-Scans (Option B)

Stand 2026-11-18. **Nur Testinstrumentierung, noch nicht ausgeführt.** Produktcode unverändert; beide Lieferworkflows
bleiben `LOCKED`.

## Anlass

Auf `0f989ca`/API 34 meldete die Engine „done“, danach fand die Abfrage über die erhaltene Content-URI keine Zeile.
Im selben Moment lief der erste Volume-Scan nach dem Booten:

> `Scanned external_primary due to REASON_MOUNTED, found 18 items …, 2 inserts 1 updates 0 deletes`

Dieselbe Signatur steht im früheren `publish rows=0`-Fall `be74008`. Ungeklärt ist der Mechanismus:

- Zeile **ersetzt**,
- **Besitzer geändert**,
- Zeile **verloren** oder
- nur **vorübergehend unsichtbar**.

## Bestandteile

| Datei | Zweck |
|---|---|
| `tests/bootscan/MediaStoreBootScanProbeTest.java` | Gerätetest; wird **nur** im Diagnose-Job vor dem Bau in die Test-APK kopiert |
| `tools/run-bootscan-probe.sh` | Hostskript, nur frischer Wegwerf-Emulator API 34 |
| `tools/bootscan_verdict.py` | Einordnung; gibt nie PASS aus |
| `tests/bootscan/test_bootscan_verdict.py` | 45 Fälle mit synthetischen Logs (3 für Regel `foreign=v2`, 6 für `snapshots=v2`) |
| `tests/bootscan/test_bootscan_script.py` | 11 Fälle, Hostskript gegen nachgebildetes adb |
| `.github/workflows/bootscan-probe.yml` | genau ein Job, startet nur bei einem Push dieser Dateien |

## Ablauf

1. **Logcat sofort mitschreiben.** Das geschieht ohne Löschen und mit `-v epoch`, sodass auch die Boot-Scan-Zeilen
   erhalten bleiben. `wait-device-ready.sh` wird bewusst nicht benutzt.
2. **Frische prüfen.**
   - Das Testpaket ist noch nicht installiert.
   - Es gibt noch keine Sondendateien.
   - Ausgangsbeleg: eine Liste aller übrigen Dateien unter `/storage/emulated/0` (ohne `Android/`) mit SHA-256.
3. **Phase `during`.** Der Test veröffentlicht sofort und durchgehend, mit höchstens zwei gleichzeitigen Aufträgen.
   - Er läuft bis 5 s nach der ersten `REASON_MOUNTED`-Zeile, höchstens 180 s.
   - Jede Datei heißt `BootScanProbe-<Lauf>-<n>.mp4`, hat 256 KiB und einen eigenen Inhalt.
   - Sie läuft über die echte Engine und die echte Ablage.
4. **Phase `after`.** Nach dem Ende des Scans folgen 15 s Abstand, dann 40 s kontrollierte Veröffentlichungen.
5. **Phase `recheck`** in einem neuen Prozess. Abgefragt werden:
   - jede ursprünglich erhaltene URI;
   - die privilegierte Sicht;
   - die Dateien.

   Danach wird die Liste der übrigen Dateien erneut erstellt.

**Pro Download protokolliert:**

- die erhaltene URI und die ID;
- die Zeitstempel von Anlegen, Commit (Start, Ende, Ergebnis) und „done“;
- die Abfrage über die URI bei +0, +250, +2000 und +10000 ms sowie in der Nachprüfung, jeweils mit Sichtbarkeit,
  Besitzer, Pending-Status und Pfad.

**Fortlaufend alle 500 ms:**

- die eigene Sicht der App;
- die privilegierte Sicht (Shell, alle Besitzer, auch ausstehende und im Papierkorb liegende Zeilen);
- alle 2 s die Dateien auf der Platte mit Größe und SHA-256.

## Sicherheitsgrenzen

- **Nur Wegwerf-Emulator:** `ro.kernel.qemu`, API 34 und `-wipe-data`. Sonst bricht das Skript mit INVALID ab.
- **Nur lesende Gegenprüfung:**
  - Auf dem Gerät werden ausschließlich zwei Hilfsskripte nach `/data/local/tmp/bootscan-*.sh` geschrieben.
  - Diese lesen nur (`content query`, `find`/`stat`/`sha256sum`).
  - Keine Schreibzugriffe auf die MediaProvider-Datenbank.
- **Kein Löschen, kein Deinstallieren, kein Aufräumen:** Der Emulator wird verworfen. Fremde Dateien werden vorher
  und nachher mit SHA-256 verglichen.
- **Belege werden immer gesichert:** vollständiges Logcat, Instrumentierung, Dateilisten und Bewertung, auch bei einem
  Abbruch. Sie gehen ins Job-Log und als Textartefakt (ohne APK).
- **Fehlende Berechtigung beweist nichts:** Eine privilegierte Abfrage zählt nur mit vollständigem Rahmen und nur,
  wenn sie jede Zeile enthält, die die App selbst sieht. Sonst ergibt sie UNKNOWN, nie LOST.

## Ergebnisklassen

**Je Download:**

| Klasse | Bedeutung |
|---|---|
| `OK` | zu jedem Zeitpunkt sichtbar |
| `TRANSIENT` | zeitweise unsichtbar, dann wieder sichtbar mit gleicher ID und gleichem Besitzer |
| `OWNER_CHANGED` | gleiche ID, anderer bzw. kein Besitzer |
| `STATE_CHANGED` | gleiche ID, wieder ausstehend oder im Papierkorb |
| `REPLACED` | andere ID für denselben Pfad |
| `LOST` | keine Zeile, Datei vorhanden |
| `UNEXPLAINED` | Widerspruch zwischen App- und privilegierter Sicht |
| `PUBLISH_FAILED` | kein „done“, z. B. `publish rows=0` |
| `DATA_LOSS` | Datei fehlt oder SHA-256 weicht ab |
| `UNKNOWN` | nötige Beobachtung fehlt oder ist nicht verlässlich |

**Gruppen:**

- `OVERLAP`: Lebensdauer des Eintrags schneidet den Mount-Scan.
- `DURING_OTHER`: Phase `during` ohne Überschneidung.
- `CONTROL`: Phase `after`, mindestens 10 s nach dem Scan.
- `AFTER_UNCONTROLLED`: Phase `after`, Bedingung verletzt.

**Gesamtergebnis (nie PASS):**

| Ergebnis | Exit | Bedeutung |
|---|---|---|
| `DIAGNOSED` | 0 | Vergleich möglich; `reproduced=yes/no`, `control_anomaly=yes/no` |
| `INVALID` | 2 | Belege unvollständig |
| `DATA_LOSS` | 3 | hat Vorrang; seit Regel `foreign=v2` nur bei gelöschten oder veränderten fremden Dateien, neu hinzugekommene sind `BOOTSCAN_NOTE FOREIGN_ADDED` (Lauf 44281951152 bleibt DATA_LOSS, archiviert unter `evidence/bootscan-diagnosis/run44281951152/`) |
| `INCONCLUSIVE` | 4 | kein Scan beobachtet, keine Überschneidung oder keine Kontrolle |

## Voraussichtliche Ergebnisse

- **Wahrscheinlich:** `DIAGNOSED`, `reproduced=yes` mit `OWNER_CHANGED` oder `REPLACED` in `OVERLAP` und nur `OK` in
  `CONTROL`. Das würde den Scan als Ursache bestätigen und den Fix-Weg bestimmen.
- **Möglich:** `INCONCLUSIVE`. Der Scan kann zu kurz sein (19–184 ms) oder vor dem ersten Auftrag fertig sein; dann
  bleibt die Ursache offen.
- **Auch möglich:** `TRANSIENT` (ein Sichtbarkeitsproblem statt Verlust) oder `PUBLISH_FAILED` (die bekannte
  `rows=0`-Familie).

## Ausführung (noch NICHT erfolgt)

Ein Push nur dieser Dateien auf `feature/c3-browser-downloads` startet genau einen Job „Boot-scan diagnosis“.
Zusätzlich startet über PR #27 die leichte „Helper safe validation“ (`build.yml`, ohne Emulator). Die Kanal-CI mit
Emulatoren startet nicht.

## Offener Punkt (bewusst nicht geändert)

Die neuen Dateien stehen noch nicht in der Liste der Liefer- und Prüfwerkzeuge (`ALLOWED` in
`tools/delivery/ci_evidence.py` bzw. in der Bereichsprüfung von `p4-test-package.yml`). Damit gelten spätere Commits
nicht als „gleicher Anwendungscode“ wie `546af14`. Das sperrt Evidenz (fail closed) und öffnet nichts. Die Ergänzung
würde die große Kanal-CI auslösen und folgt deshalb erst mit der nächsten ohnehin nötigen Werkzeugänderung.
