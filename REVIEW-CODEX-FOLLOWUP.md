# Codex-Folgereview von `291cff1` (CHANGES REQUIRED) – Abarbeitung

Ausgang: Helper `291cff1`, Kadri `858f1cc` (lokal = remote, Arbeitsbäume sauber). Produktion bleibt **UNAPPROVED**;
keine APK, keine Erweiterung, keine Installation, kein Merge/Tag/Release. Reale HTTP-404/412: nächstes Arbeitspaket.

## 0. Korrektur der CI-Bewertung von `291cff1` (selbst im Log nachgelesen)

Lauf `43970471504`, Job „API 29“:

- **Versuch 1:** 45/46, einziger Fehlschlag die SIZE-Spalte (P4B-A10-SIZE).
- **Versuch 2 (Job `133674082860`) – zwei weitere, getrennte Befunde, NICHT „nur SIZE“, NICHT „nur Emulator“:**
  1. `publishesThroughMediaStoreAndReadsBack` (64 MiB): `IOException: publish rows=0 entry=missing proof=present cleanup=none
     trail=insert#19+1ms,open#20+3ms,close#21+384ms,update=0#22+387ms`, danach `last abort: deleted:0`. Die letzte testseitige
     Bereinigung lief bei 41831 ms, der Eintrag entstand bei ≈42742 ms (nach ihr). Der MediaProvider protokollierte 10 ms vor
     dem Fehlschlag `Moving …/evp-publish-…pending--C2 Publish 9f21d59b1.mp4 to …/C2 Publish 9f21d59b1.mp4` – er hat die Datei
     also umbenannt, obwohl `update()` 0 meldete und die Zeile über die Downloads-Uri danach nicht mehr sichtbar war.
  2. ≈3 s später `OutOfMemoryError` im **Systemprozess** (Wachstumsgrenze 16 MiB) → `DeadSystemException`, u. a. aus
     `WakeLock.release` in `DirectDownloads.finish()` – der Arbeiterfaden `evp-mp4` starb (siehe P1).
  Ein kausaler Zusammenhang zwischen 1 und 2 ist **nicht** belegt. Meine frühere Aussage „maßgeblich bleibt Versuch 1,
  Ausfall nur Emulator“ war falsch: Versuch 2 enthält einen eigenständigen Veröffentlichungsfehler.
- `cleanup=none` war keine sichere Negativaussage (siehe P2) – die alte Zuordnung konnte laufende Löschungen übersehen.

## 1. Phase 1 (P1) – Abschluss trotz Aufräumfehlern

Regressionstest `P1: every job ends and frees its slot despite failing cleanup …` (JVM, echter `SafeHttpClient`,
lokaler Server; jeder Fall prüft Endereignis, ursprüngliche Ursache, freie Plätze und einen erfolgreichen Folgeauftrag):

| Fall | alter Code `291cff1` | neu |
|---|---|---|
| (1) `response.close()` wirft (Fehler- und Erfolgsweg) | **rot** – kein Endereignis (Timeout) | grün |
| (2) Output-Close wirft vor dem Veröffentlichen | grün (war schon richtig) | grün |
| (3) `target.abort()` wirft nach Schreibfehler | **rot** – kein Endereignis | grün, Ursache `IOException: ENOSPC` bleibt vorn |
| (4) Journal wirft RuntimeException vor Arbeiterstart | **rot** – Ausnahme an den Aufrufer, Auftrag dauerhaft reserviert | grün: `busy`, nicht reserviert, später startbar |
| (5) CPU-Sperre wirft beim Freigeben (wie `DeadSystemException`) | **rot** – kein Endereignis | grün, Journal `DONE`, Plätze frei |
| (6) Abschluss-Listener wirft | **rot** – Ausnahme verlässt den Arbeiter | grün, eingegrenzt |
| (7) nach dem Veröffentlichen scheitert ein Folgeschritt | **rot** – Endereignis fehlt | grün: bleibt `done`, Datei bleibt, nie abgebrochen |
| (8) `Error` im Ablauf | **rot** – kein Endereignis | grün: `failed:internal`, Plätze frei |

Korrektur (klein, ohne Umbau):

- **`DirectDownloads`:**
  - `start()`/`resumeStored()` geben bei jeder Journal-Ausnahme die Reservierung frei.
  - `run()`: Schließen und Abbrechen laufen unabhängig voneinander. Folgefehler werden als `cleanup <Schritt>: <Klasse at Stelle>` an die Ursache angehängt und ersetzen sie nie. Ein `Error` führt zu Endzustand und Freigaben und wird danach weitergeworfen.
  - Nach erfolgreichem `commit()` ist der Auftrag verbindlich `done`; ein späterer Fehler (z. B. `ref()`) wird nur vermerkt.
  - `finish()` setzt den Endzustand zuerst; Tresor, Plätze, Journal und Listener laufen danach je für sich.
- **`JobSlots`:** Zähler und `notifyAll` immer. Ein Fehler der Aktivitätsmeldung (CPU-Sperre) wird vermerkt (`lastActivityFailure`), nie weitergeworfen. Das gilt auch für HLS.
- **`Mp4Downloads`:**
  - `keepAwake`/`renewAwake` best effort; Fehler nur als Klasse vermerkt.
  - `PendingEntry.abort()`/`PublicFile.abort()`: ein Fehler beim Schließen verhindert das Entfernen nicht.
  - Markierungsbereinigung nach bestätigter Veröffentlichung fängt auch RuntimeExceptions.
- **Unverändert:** Abbruchvorrang, Idempotenz (doppelter Start), Wiederaufnahme nur mit starkem ETag, alle Netzgrenzen. Kein Retry, kein zweites Veröffentlichen.
- **Mutationsproben (dl):** 8 neue, alle erkannt; zusätzlich drei veraltete Anker angepasst.

Offen (bewusst nicht geändert, kleine Folgeaufgabe): `HlsEngine` hat dasselbe Muster (`target.abort()` im `finally`
und `listener.finished` ungeschützt); die gemeinsame Platzfreigabe ist über `JobSlots` bereits abgesichert.

## 2. Phase 2 (P2) – Diagnose ohne zusätzliche Blockade, konservative Zuordnung, Hypothesen zu `rows=0`

- **Keine zusätzliche Provider-Abfrage mehr auf dem Fehlerweg:** `PendingEntry.commit()` meldet nur sicher Bekanntes:
  - `rows=<Rückgabewert>` (0, -1, andere) bzw. `threw=<Klasse>`;
  - ob die Besitzmarkierung existiert (Dateisystem);
  - die Zuordnung zur Bereinigung;
  - die Schrittfolge.
  Die Diagnose kann selbst nicht werfen und ersetzt nie die primäre Ausnahme.
- **Zuordnung zur Bereinigung, konservativ (Zähler und Verlauf nur im eigenen Prozess):**
  - `removed-this-entry(proof-match)`: Die Bereinigung hat genau diesen Eintrag entfernt. Identität ist die zufällige Besitzmarkierung, nicht die wiederverwendbare Zeilen-ID; der Verlauf wird nie still geleert.
  - `overlap-observed(started=…,active=…,activeAtInsert=…)`: Während der Lebenszeit des Eintrags begann oder lief eine Bereinigung.
  - `not-observed(history-truncated)` bzw. `not-observed`: Nicht beobachtet heißt nicht ausgeschlossen. „none“ gibt es nicht mehr.
- **Nur im Gerätetest:** Detail-Zustand nach einem Fehlschlag (`CommitStateStore` → `provider={…}`):
  - Downloads-Zeile;
  - dieselbe ID in `MediaStore.Files`;
  - Endname unter anderer ID;
  - Dateien auf dem Datenträger.
- **Barrieren-/Kontrolltests** (`MediaStorePublishProbeTest`):
  - `processStartCleanupDuringAJobSignature` (Bereinigung zwischen Öffnen und Veröffentlichen) → `removed-this-entry`.
  - `cleanupAttributionIsConservative`: fremdes Entfernen ohne Bereinigung → `not-observed`, während einer laufenden Bereinigung → `overlap-observed`, kein `entry=`-Feld (keine Abfrage).
  - `concurrentDeletionDuringPublishRace`: H1-Wettläufe, 30 Zyklen, Ergebnisse nach Art und Zustand gezählt.
- **Hypothesen zu `rows=0`:**

  | Hypothese | Belegt / widerlegt / offen |
  |---|---|
  | H1 Eintrag verschwindet während der Update-Folge | **offen**; Wettlauf-Sonde liefert ab jetzt Muster (`PUBLISH_RACE`) |
  | H2 Zeile bleibt in `MediaStore.Files`, über Downloads unsichtbar | **offen**; Gerätetests protokollieren ab jetzt `filesSameId`/`finalNameOtherIds`/`diskFinal` |
  | H3 Provider-/Binder-/Systemfehler | **offen**; in Versuch 2 folgte 3 s später ein System-OOM, Zusammenhang nicht belegt |
  | Prozessstart-Bereinigung desselben Prozesses | für diesen Fall **unwahrscheinlich**: letzte Bereinigung vor dem Anlegen beendet; eine Bereinigung vor `update` liefert `threw=IllegalStateException`, nicht `rows=0` (Barriere) |

  Hinweis aus dem Systemprotokoll (kein Beweis): Der MediaProvider hat die Datei auf den Endnamen umbenannt, bevor `rows=0` zurückkam.

  **Restrisiko:** Danach meldet die App `failed:storage`. Der Abbruch findet keine Zeile mehr und entfernt die Markierung. Eine vollständige Datei unter dem Endnamen kann ohne eigenen Eintrag zurückbleiben. Das ist kein Datenverlust, aber eine Unstimmigkeit.

## 3. Phase 3 (P3) – Testqualität

- **Isolation (`Mp4DownloadsInstrumentedTest`):**
  - Alle 13 Engine-Erzeugungen sind registriert (`track`). Der Teardown stoppt und erwartet jede; nur wenn alle ruhen, werden Einträge und Markierungen dieses Tests entfernt, sonst nichts gelöscht und der Test rot.
  - `isolateLeftovers()` entfernt eine Markierung nur, wenn der Rest nachweislich weg ist: Provider-Abfrage = 0 Zeilen (API 29+) bzw. `.part`-Datei weg (API 26–28).
- **Mutationsläufer (alle fünf):** gemeinsame strenge Bewertung `tests/java/mutation_verdict.py`. Nur ein sichtbar gescheiterter Test ist „KILLED“; Übersetzungsfehler (INVALID), Infrastruktur (INFRA), Zeitüberschreitung (TIMEOUT), unklarer Abbruch (UNCLASSIFIED) und veraltete Anker (STALE) zählen getrennt und machen den Lauf rot. Negativer Selbsttest `tests/java/mutation_verdict_selftest.py` (in CI).
- **Gefundene ungültige Mutanten (früher als „erkannt“ gezählt):** dl `write error resumed`, `unchecked restore as done`, `start before journal`, `first request not retried` (alle nicht übersetzbar) → mit gleicher Absicht gültig neu formuliert, jetzt erkannt. Die Kanal-Probe zählte Zeitüberschreitungen als erkannt → behoben.
  hls: `segment-size` (nicht übersetzbar) → gültig neu formuliert. Kanal: „HLS ohne Kanal-Sitzung“ wurde nur zufällig
  durch eine NullPointerException im Test-Double „erkannt“ → Double null-sicher, ausdrückliche Prüfung „kein Aufruf ohne Sitzung“.
- **Lokale Läufe mit strenger Bewertung:** dl 57/57 (vorher 52 + 4 INVALID + 1 SURVIVED, alle 5 korrigiert und einzeln
  erneut geprüft), net 29/29, channel 31/31 (nach Korrektur), hls 55/55 (nach Korrektur der einen ungültigen Probe).
  hlsmux nur in CI (Media3 hier nicht ladbar).

## 4. Phase 4 – Liefer-Gate

- **`tools/device_gate.py`** bewertet jeden Emulator-Job (API 26, 29, 34) vollständig, ob grün oder rot:
  - Hauptlauf: alle vier Klassen, normales Ende, Fehlerzahl passt;
  - Veröffentlichungssonden: Status, vier Tests, sieben Varianten mit `failed=0`, Signatur-, Zuordnungs- und Wettlaufzeilen;
  - vier echte Prozessneustart-Phasen;
  - Wiederholungen;
  - erwarteter Mutantenfehler der Veröffentlichungs-Regression;
  - leerer Absturzpuffer;
  - Script-Status passt zum Job-Ergebnis.
- **Nie akzeptiert:**
  - jeder Veröffentlichungsfehler (`publish rows=…`, `publish threw=…`, `IOException: publish`);
  - `PUBLISH_PROBE_STATUS failed`;
  - fehlende oder unvollständige Sondenergebnisse;
  - Emulator-, System- oder Prozessabsturz;
  - unbekannter weiterer Fehlschlag;
  - abgeschnittene Ausgabe.

  **Einzige Ausnahme:** P4B-A10-SIZE, nur auf API 29, nur genau dieser Test mit genau der SIZE-Abweichung. Sie wird als `ACCEPTED_SIZE_ONLY` ausgewiesen, nie als PASS, und ist **keine Android-10-Freigabe**.
- **Synthetische Fixtures:** `tests/gate/fixtures` (21 Fälle, erzeugt von `make_fixtures.py`), geprüft von `test_device_gate.py` in CI, einschließlich Exit-Codes und Job-Ergebnis.
- **`tools/run-channel-instrumentation.sh`:**
  - gibt alle Berichte vollständig zwischen `GATE-BEGIN`/`GATE-END` aus;
  - **Fehler behoben:** Die Neustart-Ergebnisse gingen bisher verloren, weil `summary.txt` danach überschrieben wurde.
  - `PUBLISH_RACE`-Zeilen werden mit erfasst.

  Die Wiederholungen laufen jetzt auch nach einem roten Hauptlauf; der Exit bleibt rot.
- **`p4-test-package.yml`:**
  - Das Gate ruft für alle drei Emulator-Jobs `tools/device_gate.py` aus `SOURCE_SHA` auf.
  - Ohne `DELIVERY_REQUESTED: 'yes'` wird der Paket-Job übersprungen. Diese Änderung startet also **keine** Lieferung.
  - `SOURCE_SHA` bleibt unverändert; der alte Stand hätte das neue Gate ohnehin nicht (fail-closed).
