# P4c-3 – Abgleich des Codex-Reviews (geprüft auf `e600b79`) mit dem aktuellen Stand

Ausgangsstand: Helper `e67a6a0` (lokal = remote `feature/c3-browser-downloads`), Kadri `c69b9db`.
App-Code seit Codex unverändert (`bd5ab29`); `d2f3884`…`e67a6a0` ändern nur Tests/Dokumente. Kein Befund war daher
vorab im Produktcode behoben. Produktion bleibt **UNAPPROVED**; keine APK, keine Erweiterung, keine Installation.

| Befund | Ergebnis | Änderung |
|---|---|---|
| A Diagnose vor `await` | **bestätigt** (2 Stellen) | Test: Ergebnis zuerst abwarten, dann Diagnose |
| B RuntimeException → `network` | **bestätigt** (Regressionstest rot auf altem Code) | Produktcode: Klassifikation, nur Diagnose |
| C `IOException: publish` ungeklärt | bestätigt als Diagnoselücke | Produktcode: Diagnose je Eintrag, kein Retry |
| D Cleanup-Race | im Produktionsstart **nicht** möglich; nur im Test-Lebenszyklus | keine Produktänderung |
| E API-29-SIZE | bestätigt: Produktlogik nutzt echte Länge | nur Vorschlag, Regel unverändert |

## A – Auswertungsreihenfolge der Diagnose

Java wertet den Assertion-Text vor dem `await(...)`-Argument aus. In `publicFileForUninstallCheck` und im Absturz-Test
(`crashConsistencyBetweenJournalAndStorage`, Teil a) beschrieb `storageCause(...)` deshalb den Zustand **vor** dem
Endereignis. Jetzt: `String result = events.await(id, 30_000);` und danach `assertEquals("… (" + storageCause(…) + ")",
"done", result)`. Erwartung und Fehlerursache unverändert. Alle übrigen `storageCause`-Stellen warteten bereits vorher.

## B – Fehlerklassen in `DirectDownloads`

Grenzen: `store.create`, `target.out()`, `write`, `freeBytes`, `commit` (Ablage) · `listener.progress` (Anzeige) ·
HTTP/Body (Netz). Vorher fing nur `IOException` die Ablagegrenzen ab; eine `RuntimeException` (z. B. MediaStore
`IllegalStateException: … No item at content://media/…`) fiel in den Sammel-`catch (Exception)` → **`failed:network`**.

Jetzt (nur Klassifikation, Sicherheits-/Abbruchregeln und `finally { target.abort() }` unverändert):

- Ablagegrenzen fangen `IOException | RuntimeException` → `storage`.
- Fehler im Fortschrittslistener → `internal`; sonstige unerwartete `RuntimeException` → `internal`; `IOException` → `network`.
- `job.cause` (nur Diagnose, nie im Kanal) für Netzfehler nur **Klasse + erste App-Stelle**, nie der Meldungstext
  (kann einen Host nennen). `reasonText` hat feste Texte für `storage` und `internal`.

Regressionstest `P4c-3: failure classes are distinguishable` (JVM, echter `SafeHttpClient` gegen lokalen Server):
Commit wirft `IllegalStateException` → `failed:storage`, Eintrag abgebrochen, nichts veröffentlicht; Listener wirft →
`failed:internal`; `writable()` wirft `SecurityException` → `failed:internal`, 0 Anfragen; echter Abbruch des Bodys
ohne ETag → `failed:network`; keine Adresse/kein Geheimnis in der Ursache. Alter Code: **rot**
(`commit RuntimeException is a storage failure, got failed:network`). Drei Mutationsproben (`tests/java/dl/mutation.py`,
Abschnitt „Review B“) werden alle erkannt.

## C – Diagnose des Veröffentlichens (`Mp4Downloads.PendingEntry.commit`)

Weiterhin **genau ein** `update(IS_PENDING=0, DISPLAY_NAME)`; kein Wiederholen, kein zweites Veröffentlichen. Neu,
nur bei Fehlschlag und nur lesend:

- `rows=<n>` (unterscheidet 0, -1 und andere Werte) bzw. `threw=<Klasse>` (Providerfehler; Meldung nur in der bekannten
  Form `No item at content://media/…`, sonst nur die Klasse).
- `entry=` Zustand des eigenen Eintrags danach: `missing`, `unknown`, `query-<Klasse>` oder `pending=<0|1>/<pending-name|final-name|other-name>`
  (passt die URI noch zum eigenen Eintrag?).
- `proof=present|gone` (Besitzmarkierung).
- `cleanup=removed-this-entry | ran-<n>x | none` – die Bereinigung (`Downloads.initialize`) merkt sich die IDs der von ihr
  entfernten Einträge; so wird ein Entfernen **genau dieses** Eintrags belegt, nicht nur vermutet; dazu Lauf und
  Folgenummern der Bereinigung.
- `trail=insert#n+0ms,open#n+…,close#n+…,update=…#n+…` – Schritte des Eintrags mit einer gemeinsamen Folgenummer, die
  auch die Bereinigung zählt (eindeutige Reihenfolge je Auftrag), und ms seit dem Anlegen.

Keine Titel, keine Quelladressen; nur feste Wörter, Zahlen und die Inhalts-URI. Die Meldung fließt über
`Fail("storage", e)` in `job.cause` und damit in `storageCause()` der Gerätetests.

Gerätetest: `MediaStorePublishProbeTest.processStartCleanupDuringAJobSignature` (deterministische Barriere: Eintrag
angelegt und halb geschrieben, dann Bereinigung) prüft jetzt zusätzlich, dass die Diagnose diesen Ablauf eindeutig
zuordnet (`IOException: publish …`, `cleanup=removed-this-entry`, `proof=gone`, vollständige Schrittfolge).

## D – Cleanup-Hypothese, getrennt untersucht

1. **Produktionsstart:** `HelferService.onCreate` → `Downloads.serviceStarted` → `initialize` (einmal je Prozess,
   `synchronized`, Merker erst bei Erfolg). Engines (`Mp4Downloads.get`, `HlsDownloads.get`) entstehen **nur danach**;
   das sind die einzigen Stellen, an denen Pending-Einträge angelegt werden (auch der ältere Pfad in `Downloads` läuft
   nur in Aufträgen nach dem Start). Kein `android:process` im Manifest. → Zur Laufzeit der Bereinigung existiert im
   Prozess kein eigener aktiver Eintrag; entfernt werden nur Reste toter Prozesse (gewollt).
2. **Erneute Initialisierung nach Fehlschlag:** scheitert `initialize`, ruft `onCreate` `stopSelf()` und legt **keine**
   Engine an; der nächste Start wiederholt die Bereinigung – wieder ohne aktive Einträge. War sie einmal erfolgreich,
   läuft sie in diesem Prozess nie wieder. Kein Produktpfad zur Race.
3. **Test-Lebenszyklus mit Reflection-Reset:** `newProcessCleanup()` (3 Tests) und die Sonde setzen `initialized` per
   Reflection zurück und rufen `initialize` im **laufenden** Prozess auf. Das ist die einzige Stelle, an der die
   Bereinigung neben einer Engine laufen kann. Die Barrierenprobe zeigt: Liegt die Besitzmarkierung bei Beginn der
   Bereinigung vor und ist der Eintrag `is_pending=1` mit `proof--name`, entfernt sie ihn; das spätere Veröffentlichen
   scheitert dann mit `IllegalStateException … No item at …` – **nicht** mit `IOException: publish`.
4. **`isolateLeftovers()` / nicht vollständig gestoppte Worker:** `@After` wartet nur auf `engine`; zusätzliche Engines
   (`crashing`, `crashing2`, `restarted*`, `denied`) werden nur gestoppt. In den betroffenen Tests ist deren Auftrag beim
   Stoppen bereits beendet (Ergebnis abgewartet), also ohne aktiven Eintrag. `isolateLeftovers()` entfernt nur Einträge
   zu Markierungen, die in **diesem** Test neu entstanden sind.

Wichtig: Vor Fix B wurde genau diese Cleanup-Signatur als `failed:network` gemeldet. Der bisher ungeklärte
`failed:network` in `publicFileForUninstallCheck` (Lauf 43883581638) ist damit ein **Kandidat** für eine
Testinteraktion – **nicht bewiesen**. Der API-34-Befund `IOException: publish` (rows ≠ 1, keine Ausnahme) passt
**nicht** zu dieser Signatur. Die neue Diagnose entscheidet beim nächsten Auftreten, ob die Bereinigung beteiligt war.
Das produktive Cleanup bleibt unverändert (kein konkreter Fehler, kein Integritätsproblem nachgewiesen).

## E – API-29-SIZE (P4B-A10-SIZE), nur Vorschlag

Bestätigt: Die Produktlogik (`Mp4Downloads.isPublished`) nutzt `IS_PENDING == 0` und die echte Länge über
`openFileDescriptor(...).getStatSize()`, nicht die Spalte `SIZE`. Ein `SIZE = 0` ist also kein fehlgeschlagenes
Veröffentlichen. Der rote Gerätetest `publishesThroughMediaStoreAndReadsBack` prüft aber eine **andere Eigenschaft**:
dass das System (Dateimanager, Galerie) die richtige Größe kennt. Das ist auf Android 10 nicht erfüllt.

Vorschlag (nicht umgesetzt, Entscheidung des Eigentümers):

1. Den Test in zwei Prüfungen trennen: (a) **Veröffentlichung**: `IS_PENDING == 0`, echte Länge = erwartete Länge,
   SHA-256, Ort, genau ein Eintrag – auf allen APIs Pflicht; (b) **System-Metadaten**: `SIZE == Länge` – auf API ≥ 30
   Pflicht, auf API 29 als eigener, weiterhin **roter** Befund `P4B-A10-SIZE`, ausdrücklich nicht übersprungen.
2. Android 10 als eigene Kompatibilitätsgrenze dokumentieren: „Datei korrekt veröffentlicht, Größenangabe des Systems
   0 B“ bis zur Korrektur (z. B. Größe vor dem Schreiben angeben oder Scan des bestehenden Pfades mit Abgleich der
   Zeilen-ID, beides mit Gerätetest gegen doppelte Einträge).
3. Bis zur Entscheidung gilt die bisherige Regel unverändert: API 29 bleibt rot, Ausnahme nur für den isolierten
   Android-17-Test; **keine Android-10-Freigabe**.

## Offene Fragen

- Ursache von `IOException: publish` auf API 34 (rows ≠ 1): mit der neuen Diagnose beim nächsten Auftreten Rückgabewert,
  Eintragszustand und Cleanup-Beteiligung ablesen. Bis dahin **nicht nachgewiesen**.
- Ob `failed:network` in `publicFileForUninstallCheck` die Cleanup-Signatur war: jetzt als `failed:storage` mit
  `cleanup=…` unterscheidbar.
- Reale HTTP-404/412-Probleme: separates Arbeitspaket, hier nicht berührt.
