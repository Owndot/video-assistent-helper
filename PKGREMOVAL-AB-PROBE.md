# A/B-Diagnose: verzögerte Paket-Deinstallation im MediaProvider (Testplan)

Stand 2026-11-18, überarbeitet nach Codex-NO-GO 1 und NO-GO 2 (Regeln `pkgab=v3`, `snapshots=v3`, `foreign=v2`).
**Nur vorbereitet: kein Push, kein Emulatorlauf.** Produktcode unverändert (`546af14`, `7978a41`); beide
Lieferworkflows bleiben `LOCKED`. Das ist keine Lieferfreigabe.

## Hypothese (unbewiesen) und was der Test messen kann

Der MediaProvider verarbeitet das `PACKAGE_FULLY_REMOVED` einer Deinstallation erst nach den Boot-Scans
(`REASON_MOUNTED`) auf demselben Worker-Thread. Dann trifft das Verwaisen Zeilen der inzwischen neu installierten App
mit demselben Paketnamen:

- veröffentlichte Zeilen bekommen den Besitzer `NULL` (Muster von `0f989ca`);
- ausstehende Zeilen werden gelöscht, der Commit scheitert (Muster `publish rows=0` von `be74008`).

Indizien: In beiden fehlgeschlagenen Läufen steht `owner_package_changed:<Pfad>` 3–4 ms nach dem Scan-Ende auf
derselben TID; dem Hauptlauf ging die Deinstallation der Mutanten-APK voraus.

**Messbar** ist nur die zeitliche Abfolge: Mount-Scan, Paketoperation, Besitzerwechsel bzw. Verlust **konkreter**
MediaStore-Zeilen. Eine passende Abfolge stützt die Hypothese (Korrelation). Eine interne MediaProvider-Warteschlange
als Ursache beweist sie nicht; jedes `SUPPORTED` trägt deshalb die Zeile `CLAIM …`. Fehlt die relevante Überschneidung,
ist das Ergebnis INCONCLUSIVE.

Zu Lauf 44281951152 (korrigiert): Dort wurde der Boot-Scan allein **nicht als Ursache nachgewiesen**. Generell
widerlegt ist er nicht. Die strengere Aufnahmeprüfung zeigt außerdem, dass in jenem Belegstrom Änderungszeilen fehlen
(siehe `evidence/bootscan-diagnosis/run44281951152/INDEX.md`; Originalergebnis DATA_LOSS bleibt byte-gleich archiviert).

## Ablauf (`tools/run-pkgremoval-probe.sh`)

Ein Job und ein Build: dieselbe Test-APK, dieselben synthetischen Daten (Inhalt hängt nur von `seq` ab). Es laufen zwei
nacheinander frisch gestartete Wegwerf-Emulatoren, API 34 x86_64, jeweils mit `-wipe-data` und Kaltstart. Der Boot-Scan
wird nicht abgewartet.

| Schritt | A `control` | B `uninstall` |
|---|---|---|
| 0 | Emulator, API 34, Testpaket nicht installiert (`pm path` mit Statuszeile), keine Sondendateien (`find` mit Statuszeile) – **vor** jeder Logerfassung; ein abgelehntes Gerät liefert keine Logs | gleich |
| 1 | Logcat-Strom (`-v epoch`); Liste fremder Dateien mit SHA-256 (fehlgeschlagenes `find` = keine gültige Liste) | gleich |
| 2 | Installation 1; jede Operation mit `op-begin`/`op-end`, `rc`, Ergebnis, Gerätezeit | gleich |
| 3 | Phase `pre`: genau 3 Veröffentlichungen (seq 20000–20002), URI-Prüfungen +0/+250 ms | gleich |
| 4 | Host fragt ab hier etwa alle 0,3 s die privilegierte Sicht ab (Gerätezeit vor **und** nach der Abfrage) | gleich |
| 5 | **`install -r`** beider APKs | **`uninstall …test`, `uninstall …channeltest`** (je `rc=0` **und** `Success`), dann Nachweis der Abwesenheit (`pm path` beider Pakete, Paketliste); erst dann Neuinstallation. Sonst INVALID mit allen Rohbelegen, keine Neuinstallation |
| 6 | Phase `post` (seq ab 30000): 1 Auftrag, 500 ms Abstand, bis 8 s nach der Scanzeile, höchstens 120 Downloads/120 s; URI-Prüfungen +0/+250/+2000/+10000 ms | gleich |
| 7 | Synchronisationsmarker muss im Logcat-Strom angekommen sein (begrenzte Abfrage statt blindem Warten); Phase `recheck`; Liste nachher; zweiter Synchronisationsmarker; Bewertung | gleich |

Die Probe-Testklasse protokolliert jede Aufnahme mit Prüfsumme (`appH`, `privH`, `diskH`), drosselt die
Änderungszeilen und schließt jede Phase mit einer erzwungenen Schlussaufnahme (`final=1`, mit Dateiaufnahme). Eine leere
Provider-Antwort gilt nur mit ausdrücklichem „No result found.“.

## Bewertung (`tools/pkgremoval_verdict.py`)

- **Paketoperationen:**
  - Installationen: letzter Versuch je APK mit `rc=0` und `Success`.
  - Deinstallation: genau Test- dann App-Paket, beide erfolgreich, danach `absent` mit leeren `pm path` und leerer Paketliste.
  - Ein UID-Wechsel ist kein Nachweis. Fehlt etwas davon, ist das Ergebnis INVALID.
- **Generationen:** `pre` startet nach `install1-end` mit dessen UID und endet vor dem Paketschritt; `post` startet nach
  dem Paketschritt mit dessen UID. ID, URI, Pfad und Zeitachse jedes Downloads müssen passen, sonst INVALID.
  `ORPHANED_EXPECTED` gibt es nur für so nachgewiesene `install1`-Zeilen.
- **Aufnahmen (`snapshots=v2`):**
  - Rekonstruierte Sichten müssen zu Prüfsumme und Anzahl passen.
  - Am Phasenende darf keine Änderung offen sein; jede Dateiaufnahme gehört zu ihrer Aufnahme.
  - App- und privilegierte Sicht dürfen sich nicht widersprechen (nur Vorwärtsübergänge zwischen den Abfragen).
  - Es zählt nur die tatsächlich letzte Aufnahme; ist sie ungültig, ergibt das UNKNOWN, nie einen Ersatz.
- **Zeitachse je Zeile:**
  - Wechselintervall = [letzte Beobachtung „eigen“, erste andere Beobachtung].
  - `owner_package_changed` für genau diesen Pfad kann das Ende nur vorziehen; einen exakten Zeitpunkt ergibt es nie.
  - Ereignisse für unbekannte Sondendateien ergeben INVALID.
  - Widersprüche zwischen Logcat und Provider ergeben INCONCLUSIVE.
- **Verwaisen O** = Schnitt der Intervalle aller drei `install1`-Zeilen. Verzögert heißt: O beginnt nach `install2-end`
  und endet nicht vor dem Scan-Ende.
- **Einteilung der `install2`-Zeilen:** `at_risk` (vor O angelegt), `ambiguous` (in O angelegt) und `late` (nach O angelegt).

| Ergebnis | Bedingung |
|---|---|
| `SUPPORTED` | Ein Mount-Scan überschneidet den Paketschritt, die erste Installation ist gültig, das Verwaisen ist verzögert und genug nachbeobachtet (≥ 5 s), und es gibt keine Absturz-/ANR-/OOM-Belege. Jede `at_risk`-Zeile hat einen **passenden** Schaden: Besitzer NULL bzw. Zeile verschwunden mit Intervall in O, oder Commit-Fehler (`commit=ex:…`) nach Beginn von O ohne verbleibende Zeile bei intakter Datei. Nichts Unsicheres oder Unpassendes, keine späten Fehler |
| `NOT_SUPPORTED` | Dieselben Vorbedingungen; jede `at_risk`- und `ambiguous`-Zeile ist OK **und** wurde nach O privilegiert als eigen beobachtet |
| `INCONCLUSIVE` | Alles andere, u. a.: keine Überschneidung; nicht, teilweise, mehrdeutig oder nicht verzögert verwaist; keine `at_risk`-Zeile; UNKNOWN/TRANSIENT/UNEXPLAINED; `failed:network` oder andere unpassende Fehler; teilweise Wirkung; Widersprüche; Absturzbelege |
| `CLEAN` (A) | Aktualisierung überschneidet den Scan; alle Zeilen OK; keine `owner_package_changed`-Zeile einer Sondendatei; ≥ 1 Zeile vor dem Scan-Ende angelegt und danach als eigen beobachtet |
| `ANOMALY` (A) | Abweichung ohne Deinstallation |
| `DATA_LOSS` | Belegter Verlust (vollständige Dateilisten oder gültige letzte Dateiaufnahme), **auch wenn andere Belege unvollständig sind**; neu hinzugekommene fremde Dateien sind nur `FOREIGN_ADDED` |
| `INVALID` | Unvollständige oder widersprüchliche Belege |

**A/B:**
- Gebildet wird das Ergebnis nur aus definierten Arm-Ergebnissen zweier verschiedener Läufe.
- Fehlt ein Ergebnis, ist es unbekannt, steht es doppelt da oder haben beide Arme dieselbe Lauf-ID, ist es INVALID.
- `HYPOTHESIS_SUPPORTED` gibt es nur bei B `SUPPORTED` und A `CLEAN`, `HYPOTHESIS_NOT_SUPPORTED` nur bei B `NOT_SUPPORTED` und A `CLEAN`.
- Sonst INCONCLUSIVE, wobei DATA_LOSS bzw. INVALID eines Arms Vorrang haben. Beide Arm-Ergebnisse stehen immer in `PKGAB_ARM`.
- Exit-Codes: 0, 2 = INVALID, 3 = DATA_LOSS, 4 = INCONCLUSIVE.

## Regression-first (Codex-NO-GO 1)

`tests/bootscan/regress_pkgab_nogo1.py ALT` bewertet dieselben synthetischen Fälle mit dem Stand `1578394` und dem
neuen Stand. Für den alten Bewerter werden die Eingaben in das alte Format gebracht.

| Befund | Fall | alt (`1578394`) | neu |
|---|---|---|---|
| 1 | Deinstallation Testpaket „Failure“ mit rc=0 | SUPPORTED | INVALID |
| 1 | Deinstallation App-Paket rc=1 | SUPPORTED | INVALID |
| 1 | Abwesenheit nicht bewiesen (Paket noch gelistet) | SUPPORTED | INVALID |
| 1 | nur UID-Wechsel, keine Operations-/Abwesenheitsmarker | SUPPORTED | INVALID |
| 2 | Veröffentlichungsfehler anderer Art (Commit nicht erreicht, `network`) | SUPPORTED | INCONCLUSIVE |
| 2 | UNKNOWN einer `at_risk`-Zeile | SUPPORTED | INCONCLUSIVE |
| 2 | erste Installation ungültig | SUPPORTED | INCONCLUSIVE |
| 3 | Anlegen im Beobachtungsintervall des Verwaisens | SUPPORTED | INCONCLUSIVE |
| 3 | verspätet protokollierter Besitzerwechsel | NOT_SUPPORTED | INCONCLUSIVE |
| 3 | Besitzerwechsel einer unbekannten Sondendatei | INCONCLUSIVE | INVALID |
| 3 | Wechsel protokolliert, Provider zeigt weiter „eigen“ | NOT_SUPPORTED | INCONCLUSIVE |
| 4 | verlorene PRIV-Änderungszeile | SUPPORTED | INVALID |
| 4 | offene Änderung am Phasenende | SUPPORTED | INVALID |
| 4 | letzte Aufnahme ungültig (kein Ersatz durch ältere) | SUPPORTED | INCONCLUSIVE |
| 4 | belegter Datenverlust bei sonst unvollständigen Belegen | INVALID | DATA_LOSS |
| 4 | App-Sicht widerspricht privilegierter Sicht | SUPPORTED | INVALID |
| 5 | Phase pre mit fremder UID | SUPPORTED | INVALID |
| 5 | URI und ID passen nicht | SUPPORTED | INVALID |
| 5 | TRANSIENT-Zeile im kausalen Fenster | NOT_SUPPORTED | INCONCLUSIVE |
| 5 | Absturz des MediaProvider im Fenster | NOT_SUPPORTED | INCONCLUSIVE |

Hostskript gegen nachgebildetes adb mit Gerätesimulator:

| Befund | Fall | alt | neu |
|---|---|---|---|
| 1 | `uninstall` Testpaket: „Failure“ mit rc=0 | Exit 0, SUPPORTED, neu installiert | Exit 2, INVALID |
| 1 | `uninstall` App-Paket: rc=1 | Exit 0, SUPPORTED, neu installiert | Exit 2, INVALID |
| 1 | `uninstall` meldet Success, Paket bleibt installiert | Exit 0, SUPPORTED, neu installiert | Exit 2, INVALID |
| 6 | `find` scheitert bei der Liste fremder Dateien | Exit 0, CLEAN | Exit 2, INVALID |
| 6 | abgelehntes Gerät (kein Emulator) | Logcat (`-b all`) erfasst | keine Logerfassung |

Die positiven Fälle (SUPPORTED, SUPPORTED mit Commit-Fehler, NOT_SUPPORTED, CLEAN) ergeben in beiden Ständen dasselbe.
Kein bestehender Test wurde abgeschwächt: Alle Prüfungen des alten Stands sind übernommen oder verschärft.

**Nicht reproduziert:** die zwei von Codex gemeldeten fehlgeschlagenen Selbsttests. Der alte Stand war hier in 9 Läufen
grün, 6 davon parallel unter Last. Die wahrscheinlichen Ursachen sind beseitigt:
- Der Logcat-Strom war über `tail -f` zeitabhängig.
- Es gab echte Wartezeiten (`sleep 0.05`) und blinde `sleep 2` im Skript.

Jetzt laufen der Strom synchron unter einer Dateisperre und die Geräteuhr virtuell. Host-Abfragen verschieben die Uhr
nicht, und das Skript gleicht über Synchronisationsmarker ab. Zwei identische Läufe ergeben nachweislich dieselben
Klassen.

## Codex-NO-GO 2: fünf Blocker (Regeln `pkgab=v3`, `snapshots=v3`)

| Befund | Korrektur (Invariante) |
|---|---|
| P1-1 | Die gültige Schlussaufnahme der Nachprüfung ist verbindlich. `OK` nur, wenn sie ID, Pfad, Besitzer, „veröffentlicht“ bestätigt, die App-Sicht die Zeile enthält und jede URI-Abfrage sie sah; eine frühere erfolgreiche URI-Abfrage verdeckt keinen späteren Verlust, Besitzerwechsel oder Pending-Wechsel |
| P1-2 | Dateien und Providerzustand getrennt: eine gültige Schluss-Dateiaufnahme gilt auch bei ungültiger Provideraufnahme (veröffentlichte Datei beschädigt → DATA_LOSS). Fehlgeschlagene Downloads: Restdatei `intact` nur bei passender Größe und SHA-256, sonst `truncated`/`altered`/`missing` (kein Datenverlust, aber kein passender Schaden). Passender Veröffentlichungsfehler nur ausdrücklich `commit=ex:IOException` mit `reason=storage`, Commit nach Beginn des Verwaisens, Restdatei intakt, keine Zeile mehr |
| P1-3 | Im A/B-Format sind Prüfsummen (`appH`, `privH`, `diskH`, 16 Hex) und `tEnd` für jede gültige Sicht Pflicht; doppelte Änderungen eines Schlüssels im selben Rahmen sind ungültig. Java-Providerparser in `ProbeParsers.java` (ohne Android prüfbar) und Python-Hostparser: lückenlose Zeilennummern ab 0, eindeutige IDs/Pfade, leer nur mit genau einem „No result found.“. Historische Logs bleiben nach ihrer Version auswertbar (Prüfsummen dort nicht verlangt) |
| P1-4 | Sicher vor dem Verwaisen nur, wenn `create()` vorher **abgeschlossen** war (`insertEnd` < Beginn des Intervalls); überschneidet `create()` das Intervall, ist die Zeile `ambiguous`. Zeitachsen geprüft: `insertStart ≤ insertEnd ≤ commitStart ≤ commitEnd ≤ done` innerhalb der Phase, URI-Prüfungen nach `done`, Aufnahmen `t ≤ tEnd` und monoton, Host-Abfragen Ende ≥ Beginn – sonst INVALID |
| P2-5 | Prozessereignisse nach PID, Zeit und Grund zugeordnet: `am_kill … finished inst` des Testprozesses nach seinem END, `am_kill` durch `installPackageLI`/`deletePackageX` im Paketschritt und das zugehörige `am_proc_died` sind erwartet; MediaProvider/system_server-Tod, andere Gründe, AndroidRuntime FATAL, OOM, ANR verhindern weiter ein definitives Ergebnis. Der Simulator erzeugt beide Arten |

Regression-first (`tests/bootscan/regress_pkgab_nogo2.py ALT`, alter Stand `5627df2`). Die P1-Fälle wurden ohne reguläre
Prozessereignisse erzeugt, sonst verdeckt P2-5 im alten Stand alles.

| Befund | Fall | alt | neu |
|---|---|---|---|
| P1-1 | control: URI sichtbar, Zeile danach verloren | CLEAN | ANOMALY |
| P1-1 | URI sichtbar, danach `owner=NULL` | NOT_SUPPORTED | INCONCLUSIVE |
| P1-1 | URI sichtbar, danach `pending=1` | SUPPORTED | INCONCLUSIVE |
| P1-1 | Boot-Scan: URI sichtbar, Schlussaufnahme ohne Zeile / NULL / pending | OK / OK / OK | LOST / OWNER_CHANGED / STATE_CHANGED |
| P1-2 | Restdatei verkürzt bzw. verändert | SUPPORTED | INCONCLUSIVE |
| P1-2 | Provideraufnahme ungültig, Dateischaden belegt | INCONCLUSIVE | DATA_LOSS |
| P1-2 | `reason=internal` bzw. andere Exception im Commit | SUPPORTED | INCONCLUSIVE |
| P1-3 | `diskH=-`, fehlendes `privH`, doppelte Änderung im Rahmen | SUPPORTED | INVALID |
| P1-3 | Java-Parser: doppelte ID mit Widerspruch / Lücke / Start ≠ 0 | ok | bad:dup-id / bad:rownum |
| P1-3 | Hostparser: doppelte ID / Zeilenlücke | Abfrage verwendet | verworfen |
| P1-4 | `create()` überschneidet das Verwaisungsintervall | SUPPORTED | INCONCLUSIVE |
| P1-4 | rückwärts laufende Zeiten (insert, commit, done, Aufnahme, URI, Host) | NOT_SUPPORTED | INVALID |
| P2-5 | reguläres `finished inst` bzw. Paketaktion | INCONCLUSIVE | SUPPORTED / CLEAN / NOT_SUPPORTED |
| P2-5 | echter Absturz, LMK-Kill, MediaProvider-Tod, OOM, ANR | INCONCLUSIVE | INCONCLUSIVE |

Zwei bestehende Fälle wurden an die neue Invariante angepasst, ohne die geprüfte Eigenschaft zu schwächen:

- **URI-Fehler:** Bei unversehrter Zeile ergibt ein URI-Fehler weiter UNKNOWN, nie OK. Belegt die Schlussaufnahme
  dagegen `owner=NULL`, gilt jetzt OWNER_CHANGED (P1-1).
- **Netzwerkfehler-Fixture:** Ein Fehler vor dem Commit hat keine Commit-Zeiten. Die frühere Fixture war in sich
  widersprüchlich und ergäbe jetzt INVALID.

## Selbsttests (lokal)

| Test | Ergebnis |
|---|---|
| `tests/bootscan/test_pkgremoval_verdict.py` | PASS, 79 Fälle (Simulator-Fixtures mit einer Geräteuhr) |
| `tests/bootscan/test_pkgremoval_script.py` | PASS, 44 Fälle, darunter vollständige Hostabläufe CLEAN, SUPPORTED, NOT_SUPPORTED (mit regulären Prozessereignissen) |
| `tests/bootscan/test_bootscan_verdict.py` | PASS, 50 Fälle (11 für `snapshots=v2/v3`) |
| `tests/bootscan/test_bootscan_script.py` | PASS, 11 Fälle |
| `tests/bootscan/test_probe_parsers.py` | PASS, 24 Fälle (Java-Parser mit javac/java, Hostparser, Prüfsumme Java = Python) |
| `tests/bootscan/test_archive_integrity.py` | PASS, Originalbelege byte-gleich, Ergebnis DATA_LOSS |
| Java-Kompilierung der Probe (mit `ProbeParsers.java`) gegen Stubs | nur die bekannten umgebungsbedingten Fehler in Produktdateien |

## Grenzen

- A läuft vor B auf demselben Runner. Die Zeitachse je Variante steht in `PKGAB_NOTE`.
- Ob der Paketschritt in den Scan fällt und ob eine Zeile vor dem Verwaisen sicher beobachtet wird, lässt sich nicht
  erzwingen. Dann ist das Ergebnis INCONCLUSIVE, nie eine Bestätigung.
- Abfrageintervalle (Aufnahmen etwa 0,5 s, Host etwa 0,3 s) begrenzen die zeitliche Auflösung; Zeilen im Intervall sind
  `ambiguous`.
- Gilt nur für API 34 und den Emulator.

## Auslöser (unverändert)

- Ein Push des neuen Branches `diag/pkgremoval-ab` startet nur `pkgremoval-probe.yml`.
- Es entsteht kein Pull Request, also kein `pull_request`-Lauf.
- Jeder weitere Push mit Änderungen unter `tests/bootscan/**` oder an den A/B-Werkzeugen löst erneut aus.
- **Vor einem GO von Codex wird nichts gepusht.**
