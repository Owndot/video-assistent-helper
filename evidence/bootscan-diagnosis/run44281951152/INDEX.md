# Archiv: Boot-Scan-Diagnose, Lauf 44281951152 (Ergebnis unverändert: DATA_LOSS)

Einziger genehmigter Boot-Scan-Diagnoselauf (Option B). Workflow `bootscan-probe.yml`, Branch `diag/bootscan-probe`,
Commit `8c4b2cb` (Probe-Werkzeuge aus `c86d84b`), Job 134724661171, Attempt 1, API 34 x86_64, Wegwerf-Emulator.

| Datei | Inhalt | SHA-256 |
|---|---|---|
| `run44281951152-attempt1-job134724661171-api34.log.gz` | vollständiges Job-Log, gzip (`-9 -n`) | gz: `84d1f31dc88748925b0c0398a7c65d47bfee5bbe8405171349d8a5568232c6f7`; unkomprimiert, so wie die GitHub-API es ausgab: `8a2f478138b940c13932592b6c54024bf8bfd3b290e95ded1cd40b4ab2d7b9c8` |
| `original-verdict-lines.txt` | wörtliche Bewertungszeilen des Laufs (`BOOTSCAN_DOWNLOAD`/`GROUP`/`REASON`/`RESULT`, 882 Zeilen, ohne GitHub-Zeitstempel) | `9c5f3524f6bf5d10317497ac7ca7d4a784f144ce5601913c803fc4f2389440d0` |
| `reconstruct.py` | stellt aus dem Job-Log die Eingaben des Bewerters wieder her (Logcat-Auszug, drei Instrumentierungen, beide Fremdlisten) | – |

Vor dem Einchecken geprüft: keine Schlüssel, Token oder Kennwörter im Log (nur von GitHub maskierte `***`), keine
vertraulichen Seitennamen; einzige Hosts im Log sind `github.com`/`github.blog`.

## Ursprüngliches Ergebnis (verbindlich, unverändert)

```
BOOTSCAN_GROUP CONTROL OK=624
BOOTSCAN_GROUP DURING_OTHER OK=249
BOOTSCAN_GROUP OVERLAP OK=4
BOOTSCAN_REASON files outside the probe changed: ['/storage/emulated/0/Movies/.thumbnails/.nomedia', '/storage/emulated/0/Music/.thumbnails/.nomedia', '/storage/emulated/0/Pictures/.thumbnails/.nomedia']
BOOTSCAN_RESULT DATA_LOSS
```

Bewerter `tools/bootscan_verdict.py` in Stand `8c4b2cb` (Regel „foreign=v1“: jede Abweichung der Liste fremder Dateien
ist DATA_LOSS). Dieses Ergebnis wird **nicht** umgeschrieben und gilt für diesen Lauf weiter.

## Anmerkung (keine Änderung des Ergebnisses)

- Die drei Abweichungen sind ausschließlich **neu hinzugekommene** Dateien `.thumbnails/.nomedia` (0 Byte, SHA-256 der
  leeren Datei) unter Movies, Music und Pictures, vom System angelegt. Keine vorher vorhandene Datei wurde gelöscht oder
  verändert (die drei `.thumbnails/.database_uuid` sind vorher und nachher gleich).
- Laut Bewertung waren alle 877 Sondendownloads OK, die 4 den Mount-Scan überschneidenden (seq 149–152) ebenfalls;
  kein `owner_package_changed`. Einschränkung (erkannt mit Regel snapshots=v2, s. u.): Im Belegstrom fehlen
  Änderungszeilen der Aufnahmen (u. a. rekonstruiert 632 statt 877 privilegierte Zeilen in der Nachprüfung). Die
  OK-Einstufung beruht auf den URI-Abfragen; die privilegierte Sicht dieses Laufs ist unvollständig.
- Seit Regel „foreign=v2“ (Branch `diag/pkgremoval-ab`, lokal) unterscheidet `bootscan_verdict.py` hinzugefügte fremde
  Dateien (`BOOTSCAN_NOTE FOREIGN_ADDED`) von gelöschten oder veränderten (weiterhin DATA_LOSS) und gibt die Regel als
  `BOOTSCAN_RULES foreign=v2` aus.

## Nachbewertung (nur zur Information, ausdrücklich als solche gekennzeichnet)

Rekonstruktion aus dem archivierten Log; GitHub-Zeitstempel und Byte-Order-Marks an Chunk-Grenzen des Log-Downloads
werden entfernt:

```
python3 -I reconstruct.py <(gzip -dc run44281951152-attempt1-job134724661171-api34.log.gz) OUTDIR
A="--logcat OUTDIR/logcat.txt --run 98f4fb2769c8 --instr-during OUTDIR/instr-during.txt --instr-after OUTDIR/instr-after.txt \
   --instr-recheck OUTDIR/instr-recheck.txt --foreign-before OUTDIR/foreign-before.txt --foreign-after OUTDIR/foreign-after.txt"
```

| Bewerter | Ergebnis | Bemerkung |
|---|---|---|
| `8c4b2cb` (`git show 8c4b2cb:tools/bootscan_verdict.py` und `…:tools/device_gate.py`) | DATA_LOSS, Exit 3 | Bewertungszeilen byte-gleich mit `original-verdict-lines.txt` – die Rekonstruktion ist vollständig |
| foreign=v2, snapshots=v1 (Stand `1578394`) | DIAGNOSED, `reproduced=no control_anomaly=no`, Exit 0; `BOOTSCAN_NOTE FOREIGN_ADDED … 3 new files` | Nachbewertung, ersetzt das Ergebnis oben nicht |
| foreign=v2, snapshots=v2 (nach Codex-NO-GO 1) | INVALID, Exit 2: rekonstruierte Aufnahmen passen nicht zu `diskN`/`privN` (after: 700 statt 834 und 743 statt 877 Dateien; recheck: 632 statt 877 privilegierte Zeilen) | Nachbewertung; zeigt verlorene Änderungszeilen im Belegstrom, ersetzt das Ergebnis oben nicht |

Inhaltlich (korrigiert): In diesem Lauf wurde der Boot-Scan allein **nicht als Ursache nachgewiesen** – keine der vier
überschneidenden Veröffentlichungen fiel auf. Generell widerlegt ist er damit nicht: ein einzelner Lauf, nur vier
Überschneidungen, und die privilegierte Sicht ist unvollständig (s. o.). Offene Hypothese (ungeprüft): verzögerte
Verarbeitung einer Paket-Deinstallation – siehe `PKGREMOVAL-AB-PROBE.md`.
