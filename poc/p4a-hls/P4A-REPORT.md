# P4a – HLS→MP4: Ergebnis, Entscheidung, Architektur und P4b-Plan

Stand: Helper-Branch `feature/c3-browser-downloads` (Draft PR #27), isolierter PoC unter `poc/p4a-hls/`.
**Produktion bleibt UNAPPROVED. P4b ist nicht begonnen.** Diese Arbeit ist keine Sicherheitsfreigabe.

## 1. Entscheidung

| Frage | Antwort (nachgewiesen) |
|---|---|
| Ist HLS→MP4 ohne Neukodierung geeignet? | **Ja**, für das erste Profil: vollständige, synchrone, auf API 26/29/34 dekodierbare MP4 aus TS und fMP4, gemeinsamer und getrennter Ton, mehrere Qualitäten. |
| Parser | **Eigener strenger Parser** (`HlsPlaylist`) als Sicherheitstor. Media3s `HlsPlaylistParser` nimmt Live, AES-128, SAMPLE-AES (ohne Schutzmodell!), Byte-Ranges, Discontinuities, LL-HLS, `file:`-Adressen und unbekannte Tags kommentarlos an – nicht als Tor geeignet, wird nicht gebraucht. |
| Extractor | **Media3 1.11.1**: `TsExtractor` im Modus `MODE_SINGLE_PMT` über die verketteten, verifizierten Segmente (nicht `MODE_HLS`: verliert das letzte Bild) und `FragmentedMp4Extractor` mit eigener, eng begrenzter Editlisten-Korrektur (`Fmp4Edits`). |
| Muxer | **Media3 `Mp4Muxer`** (reines Java, versionsunabhängig). `FragmentedMp4Muxer` verwirft den Startversatz je Spur (bis 320 ms Versatz) und ist auf API 26/29 nicht lesbar → abgelehnt. Plattform `MediaMuxer`/`MediaExtractor` → abgelehnt (Befunde unten). |
| Funktionierende Profile | TS + H.264 + AAC-LC (gemeinsam/getrennt), fMP4 + H.264 + AAC-LC (gemeinsam/getrennt, mit/ohne Editlisten), Startversatz zwischen Bild und Ton, Qualitätsauswahl; Opus (fMP4) **nur ab API 29**. |
| Blocker | Signierung der Geräte-Test-APK (Abschnitt 7); unabhängige Prüfung/Pinning der Media3-Abhängigkeit; Gerätetest auf Samsung (P4c). |

## 2. Testaufbau (reproduzierbar)

- Fixtures: `tools/make-fixtures.sh` erzeugt eigenes Material (Testbild, alle 2 s weißes Bild + 1-kHz-Ton im selben
  Moment). `tools/analyze.py` prüft jede Ausgabe gegen ihre HLS-Quelle (ffmpeg liest die Quelle als Referenz): genau
  eine H.264- und eine Tonspur, Dauer ±0,15 s, gleiche Bildanzahl, fehlerfreie Dekodierung, **Blitz-/Ton-Versatz gleich
  der Quelle (±30 ms)** und ohne Drift. Gegenproben: 300 ms verschobener Ton, Video ohne Ton, 12-s-Ausschnitt → FAIL.
- Geräte: Workflow `p4a-hls-poc.yml`, Wegwerf-Emulatoren x86_64 API 26/29/34; jede Ausgabe wird zusätzlich **auf dem
  Gerät** mit `MediaExtractor` + `MediaCodec` vollständig dekodiert. Nur Textberichte verlassen den Job.
- JVM: `tools/run-jvm.sh` (80/80), `tools/mutation.py` (12/12 Mutationen erkannt).

## 3. Ergebnisse (CI-Lauf 41984708715 auf `f27aac5`; Abbruch/Opus-Richtlinie im Folgelauf)

**Media3 + Mp4Muxer** – alle Fixtures, alle drei API-Stufen:

| Fixture | API 26 | API 29 | API 34 |
|---|---|---|---|
| ts_muxed / ts_sep / ts_multi | PASS 500/500 Bilder, Sync −1,3→−1,3 ms, Gerät v500/500 a939/939 | PASS | PASS |
| ts_sep_offset (Ton +300 ms) | PASS (Sync −1,3→−1,3) | PASS | PASS |
| fmp4_muxed / fmp4_sep | PASS (−2,0→−2,0) | PASS | PASS |
| fmp4_sep_offset (Ton +300 ms, Editliste) | PASS (−2,0→−2,0) | PASS | PASS |
| fmp4_sep_noedts (ohne Editlisten) | PASS (−60→−60, wie Quelle) | PASS | PASS |
| fmp4_opus_sep | Gerät kann Opus-in-MP4 nicht lesen → **abgelehnt** | PASS a1001/1001 | PASS |

**Verworfen (gemessen):**
- *Media3 `FragmentedMp4Muxer`*: Speicher konstant (~4 MB), aber Startversatz je Spur geht verloren (Sync −320 ms bei
  Ton +300 ms, −20 ms sonst); Ausgabe auf API 26 nur 2 Bilder, auf API 29 0 Samples über den Plattform-Extractor lesbar.
- *Plattform `MediaExtractor` → `MediaMuxer`*: letztes/vorletztes Bild fehlt (498–499/500); aus TS über
  `MediaDataSource` liest der Plattform-Extractor nur ~58 Ton-Samples (Ton auf dem Gerät nicht dekodierbar);
  Versatz bis −360 ms; „non monotonically increasing dts"; Opus: „Failed to add the track to the muxer" (29/34).
- *Media3 `TsExtractor` in `MODE_HLS`*: letztes Bild fehlt am Ende (499/500); `MODE_SINGLE_PMT` behebt das.
- *Media3 `FragmentedMp4Extractor` allein*: wendet nur eine einzelne Edit an; ffmpeg-fMP4 (leere Edit + Medien-Edit)
  ergab 60 ms bzw. 360 ms Versatz. Mit `Fmp4Edits` (nur „keine", „eine Edit", „leere + Medien-Edit", Rate 1; sonst
  Ablehnung) korrekt.

**Speicher (ART, Java-Heap nach GC, 10-min-Fixture, 43 126 Samples):** `Mp4Muxer` ≈ **58–59 Byte je Sample**
(API 26: 2,68→4,38 MB, API 29: 3,34→5,06 MB, API 34: 4,35→6,06 MB je 10 000→40 000 Samples). JVM-Messung über
1 h (258 751 Samples): ~72 Byte/Sample. Eigene Puffer fest: Sample ≤ 8 MiB, Warteschlange je Spur ≤ 32 MiB.
Folgerung: Sample-Budget je Auftrag nötig (Vorschlag 1 000 000 Samples ≈ 60 MB, ≈ 3,5 h bei 30 fps + AAC 48 kHz);
genau ein Remuxer gleichzeitig.

**Abbruch:** zwischen jedem Lese- und Schreibschritt; Ausgabe wird bei Abbruch/Fehler gelöscht (Folgelauf misst die
Reaktionszeit mit gedrosselter Quelle).

**Netz (JVM, echter SafeHttpClient gegen lokalen Server):** finale Adresse nach Weiterleitung als Basis; kein geerbtes
Query-Token; kein Cookie/Authorization/Referer/Origin; private Netze, Link-Local-Metadatenadresse und `file:`-Weiterleitung
gesperrt; Segmentgrenze mit/ohne Content-Length (auch genau +1 Byte); falsche Länge; HTTP 404 ohne Wiederholung;
Abbruch eines laufenden Abrufs < 2 s; strenge Produktionsregeln sperren Loopback.

## 4. Architektur P4b (Helper)

```
Kadri ──Noise-KK──▶ ControlCommands ─▶ HlsCoordinator ─┬─▶ HlsPlanBuilder (HlsPlaylist/HlsUri/HlsPlan) ◀─ SafeHttpClient
                                                     ├─▶ SegmentScheduler (≤4 HTTP global) ─▶ SegmentStore (+ SegmentJournal)
                                                     └─▶ HlsRemuxer (Media3 Extractor + Fmp4Edits + Mp4Muxer, 1 global)
                                                           └─▶ Verifier ─▶ DownloadStore (bestehende atomare Veröffentlichung)
```
- **Kein** Media3-`HlsDownloader`/`DataSource`, keine ExoPlayer-Netzschicht: jeder Abruf über `SafeHttpClient`.
- Ressourcen werden einzeln geladen, auf Länge/Status geprüft, SHA-256 berechnet und als verifiziert im Journal
  markiert; der Remuxer liest sie über eine Verkettungssicht (keine Kopie, kein doppelter Speicher).
- Verifier vor Veröffentlichung: Ausgabe mit Media3 erneut lesen – genau 1 Video + 1 Ton, Bildanzahl = extrahierte
  Samples, Dauer je Spur ≈ Plan (±max(1 s, 1 Segment)), Zeitstempel monoton; sonst nichts veröffentlichen.

## 5. Protokoll (Entwurf, eng versioniert, auf dem bestehenden Noise-KK-Kanal)

Fähigkeit: `caps` meldet zusätzlich `hls:{v:1,profiles:["ts-avc-aac","fmp4-avc-aac"],opus:<bool aus Geräteprobe>}`.
Alte Helfer melden nichts → Kadri bietet HLS weiterhin nicht an. Unbekannte Felder/Versionen → Ablehnung.

1. `hlsplan` (neu) `{op:"hlsplan", v:1, id, url, bind:{player, gen, rev}}`
   - `url`: die von Kadri gebundene Master-/Medienliste (wie bei `dlstart` nur die Adresse; keine Cookies/Header).
   - `bind`: undurchsichtige Player-Kennung, Mediengeneration (Registry-Epoche) und Quellrevision aus Kadris Registry;
     Peer = Noise-Schlüssel des Kanals (helferseitig bekannt, nicht übertragen).
   - Helfer lädt und prüft Listen (SafeHttpClient, Parser, Budgets) und antwortet nur mit
     `{planId (128 Bit zufällig), expiresSec, duration, options:[{id,height,width,bandwidth,audio:{lang,name}|"muxed",codecs:"ok"|"unknown"}], reason?}`
     – keine Adressen, keine Playlists, Texte bereinigt und begrenzt, ≤ 64 Optionen.
   - Plan liegt nur im Helfer-Arbeitsspeicher, gebunden an (Peer, planId, bind), läuft ab (Vorschlag 10 min), höchstens
     4 offene Pläne je Peer.
2. `hlsstart` (neu) `{op:"hlsstart", v:1, id, planId, option, bind, name}`
   - nur mit identischem Peer und `bind`; abgelaufene/fremde/ersetzte Pläne → `plan-stale`.
   - lädt Medienlisten erneut und vergleicht den Plan-Fingerabdruck → bei Änderung `plan-changed` (kein stilles Weiterladen).
3. `dlstatus` / `dlcancel` bleiben (gemeinsamer Auftragsraum, `kind:"hls"`, Fortschritt Segmente/Bytes, neutrale Gründe).

## 6. Grenzen, Journal, Abbruch

- Budgets (aus dem Auftrag, gemessen bestätigt bzw. ergänzt): 2 aktive Aufträge, 4 HTTP-Anfragen global, 1 Remuxer;
  Manifest ≤ 2 MiB; ≤ 64 Varianten; ≤ 20 000 Segmente inkl. Ton; Segment ≤ 128 MiB; Medien ≤ 16 GiB je Auftrag;
  **neu:** ≤ 1 000 000 Samples je Auftrag, Sample ≤ 8 MiB; Speicherquote vor Start und vor dem Mux:
  Mediendaten + Ausgabe (≈ gleiche Größe) + 256 MiB Reserve (StatFs).
- Journal (app-privat, versioniert, ohne Klartext-Adressen): Auftrag {id, Generation, Plan-Fingerabdruck,
  Form-Fingerabdruck (Dauern, Init ja/nein, Codecs, Pfade ohne Query, gesalzen gehasht), Option, Ressourcen
  [{Index, Spur, Länge, SHA-256, starker ETag}], cancelRequested, Zustand}. Ressource erst nach Schreiben + fsync +
  Prüfung als verifiziert markieren.
- Wiederaufnahme nur nach Nutzeraktion: Kadri erkennt die Quelle neu → `hlsplan` → gleicher Form-Fingerabdruck → nur
  Ressourcen mit starkem ETag werden per `If-None-Match` (304) bestätigt und wiederverwendet, alle anderen neu geladen.
  Kein Boot-Autostart.
- Abbruch: `cancelRequested` wird zuerst persistiert, gewinnt nach App-/Browserneustart vor jeder Wiederaufnahme;
  laufende Abrufe (SafeHttpClient-Cancellation), Warteschlange und Remuxer stoppen; Teil-Ausgabe wird gelöscht.
- Veröffentlichung nur nach Verifier; Fehler danach (Benachrichtigung, Aufräumen) kehren den Erfolg nicht um.

## 7. Signierung und Test-APK (Blocker)

- `tools/build-channel-apk.sh` erzeugt je Lauf einen **Wegwerf-Schlüssel** (RSA-3072, Zertifikat 3 Tage) und löscht ihn
  nach dem Build. Die installierte Test-APK `de.evp.helfer.channeltest` (Lauf 41284918162, Quelle `ca60540`) ist damit
  signiert; der Fingerabdruck steht nur in der verschlüsselten Lieferung (`build-evidence.json`, Feld `signer_sha256`).
- Der passende private Schlüssel existiert nicht mehr → **ein Update ohne Deinstallation ist nicht möglich**. Eine neue
  APK mit anderem Schlüssel wird nicht als Update ausgegeben.
- Entscheidung nötig (Nutzer): (a) neue, dauerhaft und sicher verwahrte Test-Signieridentität ausschließlich für
  Gerätetests (getrennt von Emulator-Wegwerfschlüsseln) + einmalige Neuinstallation der Test-App (Testdaten/Kopplung
  gehen verloren), oder (b) P4c-Test-App unter neuer Paketkennung neben der bestehenden. Keine Produktionsschlüssel.

## 8. Offene Probleme und Sicherheitsblocker

1. Signierblocker (Abschnitt 7).
2. Media3 1.11.1 ist nicht unabhängig geprüft: Hashes wurden im CI-Lauf nur festgehalten (Trust on first use,
   `verification-metadata-p4a.xml` im Textbericht); Lizenz (Apache-2.0), bekannte Meldungen und R8-Regeln vor P4b prüfen.
3. Gerätevielfalt: nur Emulatoren x86_64; Samsung-Abspieltest (Galerie/Player) erst P4c.
4. Opus nur ab API 29 und nur mit Geräteprobe; HE-AAC, AC-3, HEVC, Byte-Ranges, Discontinuities, AES-128 bleiben abgelehnt.
5. Lange Videos: Sample-Index im Speicher (≈ 59 B/Sample) – Budget statt Fragmentierung (fragmentierte Ausgabe ist
   heute nicht korrekt/abspielbar).
6. Tokens in Segmentadressen können während langer Downloads ablaufen (gleiche Klasse wie R3) → sauberer Abbruch mit
   Grund, keine Umgehung.
7. Der vorhandene `specialUse`-Vordergrunddienst ist für Browsersteuerung deklariert; Eignung für lange Downloads und
   Android-17-Lebenszyklus in P4b prüfen.

## 9. P4b – kleine Arbeitspakete (erst nach Freigabe)

| Paket | Inhalt | Akzeptanz |
|---|---|---|
| P4b-1 | `HlsPlaylist`/`HlsUri`/`HlsPlan` nach `app/.../hls`, JVM-Tests + Mutationsproben in CI | 80+/80+, Mutationen 100 % |
| P4b-2 | Media3 1.11.1 (`extractor`, `muxer`) pinnen: verification-metadata, Lizenz, R8-Regeln, APK-Größe | Build reproduzierbar, keine weiteren Abhängigkeiten |
| P4b-3 | `SegmentFetcher` (SafeHttpClient, global 4, Längen/SHA-256, Abbruch) + `SegmentJournal` (fsync, Prozessabbruch-Tests) | JVM: Absturz an jeder Grenze, nichts doppelt/halb übernommen |
| P4b-4 | `HlsRemuxer` (MODE_SINGLE_PMT, `Fmp4Edits`, Mp4Muxer, Sample-/Queue-Budgets) + `Verifier` | Emulator 26/29/34: alle Fixtures PASS, Abbruch < 1 s, keine Teil-Ausgabe |
| P4b-5 | `HlsCoordinator`: 2 Aufträge, 1 Remuxer, Speicherquote, Veröffentlichung über `DownloadStore`, Abbruchvorrang nach Neustart | Instrumentierung: Neustart, voller Speicher, paralleles MP4/HLS |
| P4b-6 | Protokoll `hlsplan`/`hlsstart` + `caps.hls` in `ControlCommands`, strenge Schemata, Bindung, Ablauf | Kanal-Tests + Mutationen; alte Helfer unverändert |
| P4b-7 | Kadri: HLS-Optionen aus der Registry (nur gebundene Listen, „diese Wiedergabe"/„laut Seite"), Menü/Popup/Diagnose | Browser-Tests gegen echten Helfer-Harness mit lokalen HLS-Fixtures |
| P4b-8 | Doku, CI, Bericht; danach P4c (Signierentscheidung, Samsung) | – |
