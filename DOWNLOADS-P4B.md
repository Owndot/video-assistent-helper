# P4b – HLS→MP4 in der Helfer-App (Teststand, nicht für Produktion)

Produktion bleibt **UNAPPROVED** (`release/production-policy.json`). Issue #3 (kompromittierte APK-Signatur) bleibt
Release-Blocker, Issue #4 (Helper-Authentifizierung) offen bis zur unabhängigen Prüfung. Grüne Tests sind **keine**
Sicherheitsfreigabe. Keine APK wurde ausgeliefert, nichts ist auf einem Gerät installiert.

## 1. Pakete und Commits (Branch `feature/c3-browser-downloads`, Draft-PR #27)

| Paket | Inhalt | Commits |
|---|---|---|
| P4b-0 | API-34-Flakes der C2-Gerätetests: Ursache (Aufräumen ohne bestätigtes Löschen), deterministische Synchronisation, 20× Wiederholung | `cba03e0` |
| P4b-1 | Strenger HLS-Kern in der App (`hls/`: Parser, RFC-3986-Auflösung, Plan, Abruf nur über SafeHttpClient) | `727d0ea`, `e8156f1`, `d28325b`, `c5b24d9` |
| P4b-2 | Media3 1.11.1 (extractor, muxer) exakt gepinnt, Hashes in `gradle/verification-metadata.xml`, Guava strikt 33.7.2-android (CVE-2026-102554 in 33.3.1), Lizenz, R8 | `5f1e908`, `6d9d5fa` |
| P4b-3/5 | `HlsEngine` (Koordinator), `HlsJournal` (absturzsicher), verifizierter Segmentabruf, sichere Wiederaufnahme, gemeinsame `JobSlots` | `3c34e28` |
| P4b-4 | `Media3HlsRemuxer` (TS/fMP4 → MP4 ohne Neukodierung) + Ausgabeprüfung | `b3bd8b8` |
| P4b-5/6 | App-Anbindung (`HlsDownloads`, Dienst, Benachrichtigungen), Protokoll `hlsplan`/`hlsstart` v1, Gerätetests, API 29 in der Matrix | `884cded` |
| P4b-7 | Desktop-Harness mit echter `HlsEngine` für Kadris Ende-zu-Ende-Tests | `ad69aa2` |
| Korrektur | Android 10: Veröffentlichungsprüfung (MediaStore `SIZE` bleibt 0); Heap-Messung des Restbestands; deterministischer Paralleltest | `4436aaf` |
| P4b-8 | P4-Testapp `de.evp.helfer.p4test`, dauerhafter Signierweg (lokal, fail-closed), Doku | siehe Abschnitt 8 |

Kadri (Branch `feature/c3-helper-mp4`, Draft-PR #11): `83afa8f` (P4b-7, Menü „Stream speichern …“) und P4b-8
(P4-Testerweiterungsidentität, Doku).

## 2. Umfang

Unterstützt: VOD, unverschlüsselt, H.264 + AAC-LC (Opus nur ab Android 10 mit Decoder), MPEG-TS und fMP4, Ton im
Videostrom oder als eigene Spur (EXT-X-MEDIA). Ausgeschlossen (klarer Grund, nie stiller Rückfall): DRM, AES-128,
SAMPLE-AES, Live/EVENT, DASH, HEVC, HE-AAC, AC-3, Byte-Ranges, Discontinuities. Keine Neukodierung; fehlt der Ton
einer gewählten Option, wird nicht ohne Ton gespeichert. `dlstart` und der C3-MP4-Weg sind unverändert (gleiche
Tests grün).

## 3. Ablauf und Architektur

```
Kadri ──hlsplan{v:1,url,bind}──▶ HlsEngine.plan: Master laden (≤2 MiB), streng prüfen, Optionen + Medienlisten
       ◀── planId (an Kanal-Sitzung + bind gebunden, 10 min) + Optionen (Höhe, Breite, Bitrate, Ton, Codecs)
Kadri ──hlsstart{v:1,id,planId,option,bind}──▶ Master + Listen neu laden (SHA bzw. Fingerabdruck muss gleich sein,
       sonst plan_changed) → Speicherquote → Job
Job: je Segment .part → SHA-256 + fsync → atomar .seg → Journal-Eintrag (CRC) → … → 1 Remuxer (Media3) →
     Prüfung (Spuren, Codecs, Samples, Dauer, Sync-Start) → Veröffentlichung über DownloadStore → DONE
```

- Pläne nur im Speicher, je Kanal-Sitzung (`sid` aus dem Rust-Kern, nie aus der Anfrage) und `bind`
  (`player|gen|rev`); max. 4 je Sitzung, 16 gesamt; ein Plan startet genau einen Auftrag.
- `dlstatus`/`dlcancel` bedienen beide Arten; eine Kennung gehört genau einer Art (`hlsstart` mit MP4-Kennung →
  `id_in_use`). Alter Helfer: HLS-Anfragen abgelehnt, Status ohne `hls`-Feld; neuer Helfer: `hls{v:1,profiles,opus}`.
- Journal: Kopf atomar (tmp + fsync + ATOMIC_MOVE + Ordner-fsync), `segments.log` nur anhängend mit CRC-32 je Zeile;
  abgerissenes Ende wird abgeschnitten, jede andere Beschädigung verwirft den Auftrag. **Keine Adressen** im Journal;
  Form-Fingerabdruck gesalzen über Pfade ohne Query (Token-Wechsel erlaubt Wiederaufnahme).
- Wiederaufnahme nur durch Nutzeraktion (neues `hlsplan` + `hlsstart` mit gleicher Kennung). Ein Segment wird nur
  wiederverwendet, wenn Länge + SHA-256 stimmen **und** ein starker ETag vorliegt **und** der Server per
  `Range: bytes=0-0` + `If-Range` mit 206, exakter `Content-Range` und gleichem ETag bestätigt. Schwache oder
  fehlende Validatoren → neu laden.
- Abbruch: Abbruchwunsch wird zuerst dauerhaft geschrieben und gewinnt nach Neustart vor jeder Wiederaufnahme;
  Daten werden gelöscht; Endzustand wird erst nach dem Aufräumen gemeldet.
- Zustände: INTERRUPTED (wieder aufnehmbar: io, http_403/404/410/429/5xx, Stopp, Neustart), FAILED (Daten gelöscht),
  CANCELLED, DONE.

## 4. Grenzen (durchgesetzt und getestet)

2 Aufträge, 4 HTTP-Anfragen gesamt (MP4 + HLS teilen `JobSlots`), 1 Remuxer; Manifest ≤ 2 MiB; ≤ 64 Varianten;
≤ 20 000 Segmente; Segment ≤ 128 MiB; ≤ 16 GiB je Auftrag; Speicherquote 2 × Schätzung + 256 MiB vor dem Start und
während des Laufs; Sample-Budget `max(10 000, min(1 000 000, maxHeap/2/128 B))`; Remux bricht bei < 15 % freiem Heap
(nach GC, Prüfung alle 2048 Samples ab dem ersten) sauber mit `memory` ab. Keine Cookies, kein Authorization,
kein Referer, kein Proxy, keine allgemeinen Wiederholungen; private Ziele blockiert (SafeHttpClient).

## 5. Tests

JVM (lokal und CI): HLS-Kern 80 + Engine 170 Prüfungen (6 Läufe stabil), 37/37 Mutanten getötet; Remuxer mit echten
Media3-AARs 24/24, 12/12 Mutanten; MP4-Engine 35/35, 30/30 Mutanten; Kanal 14/14, 31/31 Mutanten (davon 12 HLS).
Abgedeckt: A/V-Sync, getrennte Spuren, TS/fMP4, Playlist-Änderung und abgelaufene Quellen, private Ziele, fehlende
Content-Length und Übergröße, Prozessabbruch an jeder Schreib-/Commit-Grenze, Wiederaufnahme, dauerhafter Abbruch,
voller Speicher, MP4 + HLS parallel, alter Helfer ohne HLS.

Emulator (x86_64, Testpaket `de.evp.helfer.channeltest`, Wegwerf-Signierer, nichts hochgeladen), je API 26/29/34:
alle Profile umpacken + veröffentlichen + auf dem Gerät decodieren, Heap je Sample, Speicherdruck, Abbruch im Remux,
Stopp/Neustart/Wiederaufnahme, Abbruch nach Neustart, voller Speicher, MP4 + HLS parallel; dazu alle C2/C3-Tests.
Ergebnisse je Version: Abschnitt 9.

Heap-Messung: Steigung (Regression) des nach vollständiger GC belegten Heaps an den Prüfpunkten des Remuxers
(alle 2048 Samples, im Remux-Thread), 10-min-Fixture (~43 000 Samples); Annahme ≤ 128 B/Sample und
„feste Kosten + Budget × Steigung ≤ halber Heap“ müssen gelten. Die frühere Messung (Spitze inkl. Müll, 3977 B auf
API 29) maß nicht den Restbestand und wurde ersetzt, nicht gelockert.

## 6. Lieferkette

Media3 1.11.1 (common, container, decoder, extractor, muxer) und Guava 33.7.2-android: OSV ohne Meldungen; Lizenz
Apache-2.0 (`licenses/media3-1.11.1.LICENSE`); Hashes in `gradle/verification-metadata.xml` – **Trust on first use**
aus dem CI-Lauf, nicht unabhängig geprüft. R8: Regeln für die App unverändert, Test-Keep-Regeln um
`ListenableFuture` ergänzt.

## 7. Korrektur Android 10 (bestehender C2-Fehler)

Android 10 trägt bei `openOutputStream` die MediaStore-Spalte `SIZE` nicht ein (bleibt 0). `isPublished` prüfte diese
Spalte → ein fertiger Download galt nach Neustart als unvollständig („interrupted“, erneuter Download). Erst mit
API 29 in der Matrix sichtbar. Jetzt: `IS_PENDING == 0` und echte Länge über den Dateideskriptor; auf API 29 wird die
gemessene Länge beim Veröffentlichen in `SIZE` geschrieben (Dateimanager), ab API 30 pflegt der Provider sie selbst.

## 8. P4-Testidentität und Signierweg (Option B)

- Neue Testapp **`de.evp.helfer.p4test`** (Label „P4 TEST Helfer“), eigene Daten, eigene Keystore-Identität, eigene
  Kopplung. `de.evp.helfer.channeltest` und `de.evp.helfer` bleiben unberührt (kein Update, keine Deinstallation).
- Gradle baut sie **nur unsigniert** (`tools/p4test/build-unsigned.sh`, `-Pp4VersionCode` monoton Pflicht).
- Signieren **nur lokal beim Eigentümer**: `tools/p4test/keygen.sh` (einmalig, RSA-3072, 5 Jahre,
  `CN=Helper P4 TEST ONLY - durable`; verweigert CI, Git-Ordner und Überschreiben) und `tools/p4test/sign.sh`
  (nur Paket p4test, nicht debugfähig, Kanalbibliothek arm64-v8a + x86_64, keine Testbibliothek, Eingabe unsigniert;
  Zertifikat muss dem in `release/p4test-signer.json` festgelegten SHA-256 entsprechen; Schlüssel und Ausgabe nie in
  einem Git-Ordner; danach genau ein Signierer mit diesem Zertifikat).
- Der private Schlüssel liegt damit nie im Repository, in CI oder in Artefakten; Verwahrung: `.p12` und Passwort
  getrennt (Passwortmanager + Offline-Kopie). Für Updates wird derselbe Schlüssel verwendet, kein neuer.
- CI (API-34-Job): unsignierter Build + Selbsttest des Signierwegs mit **Wegwerf**-Schlüssel und
  Wegwerf-Festlegung (jede Ablehnung muss greifen), danach alles gelöscht; nichts hochgeladen.
- **Status: Schlüssel noch nicht erzeugt** (`KEY_NOT_YET_CREATED`). Eine sicher dauerhaft verwahrbare Signatur kann
  nur der Eigentümer auf eigenem Rechner anlegen; aus dieser Umgebung (öffentliches Repo, flüchtiger Container) ist das
  nicht möglich → **Blocker für P4c**, nicht für P4b.
- Port 47899 für alle Varianten; belegt → der Dienst startet den Kanal nicht (kein Ausweichport). Es darf nur ein
  Helfer gleichzeitig laufen; ein falscher Helfer scheitert zusätzlich an der Kopplung (Noise KK, fail-closed).
- Erweiterung: eine **eigene P4-Testerweiterungsidentität ist nötig**, sonst würde das Koppeln mit p4test die
  Kopplung der C3-Testerweiterung überschreiben. Vorbereitet in Kadri `tools/p4test/identity.json`
  (ID `pcccpjllneffccpenbepmfbkjedllkkf`, nur öffentlicher Schlüssel; geprüft von `tests/channel/p4_identity_check.js`).

## 9. Ergebnisse je Android-Version

Wird mit dem letzten CI-Lauf ergänzt.

## 10. Offene Sicherheits- und Funktionsgrenzen

1. Keine unabhängige Sicherheitsprüfung von P4b; Issue #4 offen; Produktion UNAPPROVED.
2. Media3-/Guava-Hashes nur Trust on first use.
3. Nur x86_64-Emulatoren; kein ARM-/Samsung-Gerät, keine Galerie-/Player-Prüfung auf echter Hardware.
4. Sample-Index im Speicher (Mp4Muxer, nicht fragmentiert) → Budget statt unbegrenzter Länge.
5. Ablaufende Token in Segmentadressen → INTERRUPTED; Wiederaufnahme nur mit neuem Plan und starken ETags,
   sonst Neuladen der betroffenen Segmente.
6. Pläne liegen im Speicher (Helfer und Kadri); nach Neustart des Service Workers oder der App neu planen.
7. Kadris Browser-Tests nutzen im Desktop-Harness eine Test-Verkettung statt Media3 (echtes Umpacken nur in
   Helfer-JVM- und Emulatortests).
8. Vordergrunddienst `specialUse` (für Browsersteuerung deklariert) trägt jetzt auch lange Downloads; Eignung für
   Android 17 und Samsung-Energiesparen auf dem Gerät prüfen (P4c).
9. Opus nur mit Geräteprobe ab Android 10.

## 11. Samsung-Testplan (P4c, nur nach ausdrücklicher Freigabe)

1. Eigentümer: Schlüssel mit `keygen.sh` erzeugen, Fingerabdruck festlegen lassen; unsignierte APK aus
   verschlüsselter Lieferung lokal mit `sign.sh` signieren; P4-Testerweiterung (eigene ID) laden.
2. Nur `de.evp.helfer.p4test` starten (channeltest vorher stoppen, nicht deinstallieren); koppeln.
3. Eigene Testseite (D2, HTTPS): TS getrennt, fMP4 getrennt, TS gemuxt, Option mit anderer Tonspur → je
   „Stream speichern …“ → Datei in Download/…, in Galerie und Player abspielen (Bild + Ton synchron, Dauer).
4. Abbruch mitten im Laden; App beenden und neu starten → Abbruch bleibt; Wiederaufnahme nach Unterbrechung.
5. Parallel ein MP4 und ein HLS; dritter Start → „belegt“.
6. Nicht unterstützter Stream (verschlüsselt/Live) → klarer Grund, keine Datei.
7. Aufräumen: p4test deinstallieren, P4-Testerweiterung entfernen; channeltest/C3-Paar unverändert prüfen.
