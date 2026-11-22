# DASH S3b – Download-Koordinator im Helfer (DASH-VOD fMP4 H.264 + AAC → MP4)

**Stand:** S3b (`54398dd`) mit S3b-Fix 1 (Prüfbefunde P1 AAC-Konsistenz und P1 Dauerprüfung) und S3b-Fix 2 (Ausgabetoleranz ohne Periodenanteil), Abschnitt 8, auf S3a-Fix 1 (`4a3bc42`). Nur Helfer; Kadri-Produktcode ist unverändert (Anbindung folgt in S3c).

- Kein DRM-Bypass: verschlüsselte oder geschützte Inhalte werden vor dem ersten Medienstück abgelehnt.
- Keine Cookies, kein Authorization-Header, keine ungeprüften Abrufpfade: jeder Abruf läuft über den bestehenden `SafeHttpClient`.
- Der frühere, nicht angebundene DASH-Weg des Helfers (`DashCore`) bleibt unangetastet und unbenutzt.
- Die bestehenden MP4-/HLS-Funktionen sind unverändert; Änderungen außerhalb der neuen Dateien sind rein additiv (Abschnitt 6).

| Datei | Aufgabe |
|---|---|
| `dashdl/DashFetch` | MPD-Abruf über `SafeHttpClient` (Grenze 4 MB, HTTP 200, identity) |
| `dashdl/Fmp4Inspect` | Prüfung von Init (Container, Spur, Codec gegen MPD, Verschlüsselung) und Medienstücken (Boxstruktur) |
| `dashdl/DashEngine` | Koordinator: Plan, Start, Journal, Wiederaufnahme, Remux, Veröffentlichung, Abbruch |
| `DashDownloads` | Android-Verdrahtung (Speicherort, Remuxer, Slots, Netzwacht) – ohne eigene Logik |
| `ControlCommands`, `ChannelServer`, `HelferService` | additive Anbindung `dashplan`/`dashstart`, Fähigkeit `dashdl` |

## 1. Ablauf

1. **`dashplan`** (verschlüsselter Kanal, nur mit Kanal-Sitzung):
   - MPD laden (`DashFetch`), streng lesen (S3a-Kern), Strukturfingerabdruck `kadri-dash-fp/1` berechnen.
   - Vergleich mit dem von Kadri übergebenen `fp`; Abweichung → `manifest_mismatch` (keine Optionen).
   - S3-Profil und vollständiger Plan (S3a). Optionen = jede nutzbare Video- × Tondarstellung; Ton ist Pflicht (`dash_no_audio`).
   - Angeboten wird nur Ton mit `mp4a.40.2` (AAC-LC). Gibt es nur HE-AAC/HE-AACv2 (`mp4a.40.5`/`.29`) → `audio_codec`. Das S3a-Profil (`DashProfile`) liest diese Codecs weiterhin; der Ausschluss gilt für das Speichern (S3b) und ist bewusst fail-closed.
   - Option fällt weg, wenn Video + Ton zusammen mehr als 20 002 Ressourcen (je Spur Init + Stücke) oder mehr als 16 GiB geschätzt ergeben; keine Option übrig → `segments_limit`.
   - Plan nur im Arbeitsspeicher, gebunden an Kanal-Sitzung (`peer`) und Kadris `bind`, 10 min gültig, höchstens 4 je Sitzung und 16 insgesamt. Options-IDs sind gesalzene Hashes (24 Hex) ohne Rückschluss auf Adressen.
2. **`dashstart`** (Kennung, Plan-ID, Option, gleiche Bindung): Plan wird verbraucht. Abweichende Sitzung oder Bindung, abgelaufener oder unbekannter Plan → `plan_stale`; unbekannte Option → `option_unknown`.
3. **Jeder Versuch** (auch die Wiederaufnahme) lädt das MPD neu und vergleicht den Fingerabdruck mit dem Plan; Abweichung → FAILED `plan_changed`.
4. Zuerst beide Init-Ressourcen laden und mit `Fmp4Inspect.init` prüfen. Erst danach werden Medienstücke geladen.
5. Jedes Stück: Laden über `HlsFetch.resourceVerified` (Längengrenze, SHA-256, fsync), dann `Fmp4Inspect.segment`, erst dann Aufnahme ins Journal.
6. **Vollständigkeit (Fix 1):** Vor dem Zusammenbau wird je Spur die tatsächliche Dauer bestimmt (Summe der Probendauern aller Stücke, `Fmp4Inspect.duration`) und gegen den Plan geprüft (Abschnitt 8). Abweichung → FAILED `media_duration`.
7. Remux (global genau einer) über `HlsRemux`/`Media3HlsRemuxer` mit den unveränderten Prüfungen der Ausgabe (Spuren, Samples, Zeitstempel, Dauer, A/V-Versatz). Die Dauer der Ausgabe wird strikt gegen die tatsächliche Stückdauer geprüft (± 1 s), danach atomare Veröffentlichung über `DownloadStore`.

## 2. Fmp4Inspect (fail-closed)

- **Init:** oberste Ebene nur `ftyp`/`moov`/`free`/`skip`; `moov` mit genau einer `trak` und `mvex`; `hdlr` `vide` bzw. `soun`; `stsd` mit genau einem Eintrag.
  - Video `avc1`/`avc3`; die Bytes 1–3 von `avcC` (Profil, Kompatibilität, Level) müssen den sechs Hex-Ziffern des MPD-Codecs entsprechen.
  - Ton nur AAC-LC: MPD genau `mp4a.40.2`; `mp4a`, `esds` Version 0, OTI `0x40`, DecoderSpecificInfo mit AudioSpecificConfig: audioObjectType 2, Kanalkonfiguration 1…7 (0 = PCE wird nicht geprüft → abgelehnt), kein extensionFlag, keine SBR/PS-Signalisierung. Eine Synchronisationserweiterung `0x2b7` wird nur als „ausdrücklich ohne SBR“ (Typ 5, sbrPresentFlag 0, so schreibt sie FFmpeg) angenommen; übrige Bits müssen 0 sein.
  - Zeit: Zeitmaßstab aus `mdhd`, Standard-Probendauer aus `trex`; je Stück Summe aus `trun` (Dauer je Probe) bzw. `tfhd`-/`trex`-Standard. Keine Quelle → `segment_timing`.
  - Größe ≤ 1 MiB.
- **Verschlüsselung:** `encv`/`enca` oder irgendwo `sinf`, `frma`, `schm`, `schi`, `tenc`, `pssh`, `senc`, `saiz`, `saio`, `sgpd`/`sbgp` mit `seig`, PIFF-`uuid` → `encrypted`. Zusätzlich lehnt schon das S3-Profil `ContentProtection` ab.
- **Stück:** oberste Ebene nur `styp`/`sidx`/`ssix`/`prft`/`emsg`/`moof`/`mdat`/`free`/`skip`, `moof` und `mdat` Pflicht; `moof` nur `mfhd`/`traf`; `traf` nur `tfhd`/`tfdt`/`trun`/`sdtp`/`sbgp`/`sgpd`/`subs`/`uuid`.
- Codes: `init_size`, `mp4_structure`, `mp4_boxes`, `init_box`, `init_track`, `codec_mismatch`, `encrypted`, `segment_box`, `segment_timing`.

## 3. Journal, Wiederaufnahme, Fehlerzustände

- Journal und Stückliste über den unveränderten `HlsJournal` (CRC je Zeile, atomarer Kopf, keine Adressen); Zustände wie bei HLS: RUNNING, INTERRUPTED, RETRY_WAIT, PUBLISHING, DONE, FAILED, CANCELLED.
- Eine geladene Ressource wird nur mit gleicher Länge, gleichem SHA-256 und vom Server bestätigtem starkem ETag (`HlsFetch.unchanged`, If-Range) wiederverwendet; sonst neu geladen.
- Netzverlust / 429 / 5xx / Zeitüberschreitung → RETRY_WAIT mit `Retry`-Plan (höchstens 64 automatische Versuche), Fortsetzung über `NetWatch`.
- Prozessneustart: `recover()` setzt RUNNING auf INTERRUPTED (`restarted`), schließt eine begonnene Veröffentlichung ab oder verwirft sie (`PUBLISHING` prüft, ob die Datei vollständig veröffentlicht ist), vollzieht einen dauerhaft vermerkten Abbruch und räumt abgelaufene Aufträge (7 Tage) auf.
- **Keine verschlüsselt gespeicherte Quelle für DASH** (der HLS-`SourceVault` kennt nur `hls|mp4` und bleibt unverändert). Nach einem Prozessneustart setzt ein neuer `dashstart` mit derselben Kennung fort; solange der Prozess lebt, auch „Fortsetzen“ aus der Benachrichtigung.
- Remux-IOException (z. B. vertauschte oder kaputte Init) → FAILED `remux_io`, ohne automatischen Neuversuch.
- Fehlerort nur als fester Code: `at` ∈ {`mpd`, `video_init`, `audio_init`, `video_segment`, `audio_segment`} mit `at_index`. Nie Adressen, Hosts oder Manifestinhalt.

## 4. Protokoll (nur über den bestehenden verschlüsselten Kanal)

```
{"op":"dashplan","v":1,"url":"…","fp":"<64 hex>","bind":{…}[,"played":"0,3"][,"ref":"…"][,"ua":"…"]}
→ {"ok":true,"planId":"<32 hex>","expiresSec":600,"duration":…,"fp":"kadri-dash-fp/1",
   "options":[{"id":"<24 hex>","height":…,"width":…,"bandwidth":…,"video":"avc1.…","audio":"mp4a.40.2","lang":"…","played":true|false}]}
→ {"ok":false,"reason":"<code>","diag":{"v":1,"phase":"mpd_fetch|mpd_parse|fingerprint|profile|options"}}

{"op":"dashstart","v":1,"id":"<32 hex>","planId":"<32 hex>","option":"<24 hex>","bind":{…}[,"name":"…"]}
→ Status wie bei HLS, zusätzlich "kind":"dash"
```

- `v` genau 1 (= `kadri-dash-fp/1`). `fp`/`played` nur bei `dashplan`; `played` ist nur ein Hinweis (≤ 8 Indizes 0…4095, ohne Wiederholung), nie Bindungsevidenz.
- Eine Kennung gehört genau einer Art (MP4, HLS, DASH): Wiederverwendung über Arten hinweg → `id_in_use`. `dlstatus`/`dlcancel` gehen an die Art, die die Kennung kennt.
- Fähigkeit im Status: `"dashdl":{"v":1,"profiles":["fmp4-avc-aac"],"fp":"kadri-dash-fp/1","ctx":1}`, nur wenn der Koordinator angebunden ist. Ohne Anbindung wird `dashplan`/`dashstart` abgelehnt wie bei einem alten Helfer. Das alte Feld `"dash":false` bleibt unverändert.
- Gründe (Auswahl): `fingerprint_missing`, `manifest_mismatch`, `audio_codec`, `media_duration`, `segment_timing`, `plan_stale`, `option_unknown`, `plan_changed`, `dash_no_audio`, `segments_limit`, `size_limit`, `segment_size`, `encrypted`, `codec_mismatch`, `remux_io`, `no_space`, `storage`, `http_NNN`, `blocked_*`, `network`, `timeout`, `cancelled`, `busy`.

## 5. Grenzen

| Grenze | Wert |
|---|---|
| MPD | ≤ 4 MB (`DashLimits.MAX_MANIFEST_BYTES`; `HlsFetch.manifest` bleibt bei 2 MB) |
| Init | ≤ 1 MiB |
| Stück | ≤ 128 MiB (`HlsPlan.MAX_SEGMENT_BYTES`) |
| Ressourcen je Auftrag | ≤ 20 002 (Video + Ton, je Init + Stücke) |
| Mediendaten je Auftrag | ≤ 16 GiB, Speicherreserve 256 MiB |
| Pläne | 10 min, 4 je Sitzung, 16 gesamt |
| Parallelität | 2 Abrufe je Auftrag, Slots mit MP4/HLS geteilt, genau ein Remux |
| Dauer je Spur | tatsächlich ∈ [Plan − 1 s, volle Stückdauern + 1 s]; Ausgabe = tatsächlich ± 1 s |

## 6. Begründung der Änderungen außerhalb der neuen Dateien (alle additiv)

- `ControlCommands`: neue Schnittstelle `DashControl`, neue `handle`-Überladung (die alte delegiert mit `dash = null`, Verhalten unverändert), neue Felder nur für `dashplan`/`dashstart`, Kennungs-Eindeutigkeit über die Arten. Bestehende Befehle parsen unverändert; `fp`/`played` werden bei allen anderen Befehlen abgelehnt.
- `ChannelServer`: zusätzlicher Konstruktor mit `dash`; der bisherige delegiert mit `null`.
- `HelferService`: legt `DashDownloads` nur an, wo auch MP4-Downloads existieren; leitet Abbruch/Fortsetzen weiter und meldet die Fähigkeit.
- `tools/check-network-layer.sh`: neue Regeln für `de.evp.helfer.dash` (kein Netz/Datei/Android) und `dashdl` (nur `SafeHttpClient`, keine Android-Typen, kein alter DASH-Weg) mit Selbsttest-Proben.
- CI: `dash-core.yml` (JVM-Tests, Mutationsproben, Netzwerk-Wächter), `helper-channel.yml` (DASH-Fixtures), `run-channel-instrumentation.sh` (separater Lauf des DASH-Gerätetests).

## 7. Tests

- `tests/java/dashdl/run.sh FX [CACHE]` – `TestDashEngine` (112 Prüfungen) (Fixtures aus `make-dash-fixtures.sh`, ffmpeg): Fmp4Inspect mit echten und gezielt veränderten Dateien, Kanalbefehle, Planung, Bindung, Gutfall, Negativfälle, Wiederaufnahme, Abstürze an 9 Haltepunkten, Abbruch.
- `tests/java/dashdl/run_remux.sh FX [CACHE]` – `TestDashRemux` (4) und `TestDashEndToEnd` (18: Zeitleiste, `$Number$`/`@duration`, angeschnittene Periode, Periode 10 s mit deklarierten 3600-s-Stücken – jeweils `analyze.py` –, das Gegenbeispiel mit zu kurzen Fragmenten und eine deutlich verkürzte Remux-Ausgabe) mit echtem Media3-Remuxer (JVM).
- `tests/java/dashdl/mutation.py FX [CACHE]` – 53 Mutationsproben über `DashEngine`, `Fmp4Inspect` und die DASH-Kanalbefehle.
- `DashDownloadsInstrumentedTest` (Emulator API 26/29/34): eigener Lauf in `run-channel-instrumentation.sh`, **nicht** Teil der festen Gerätetor-Liste (`tools/device_gate.py` unverändert). Lokal ohne KVM/SDK **nicht ausgeführt**.

## 8. S3b-Fix 1: Begründung der beiden Korrekturen

**P1 AAC.** OTI `0x40` steht für jede MPEG-4-Audio-Variante. AAC-LC, HE-AAC (SBR) und HE-AACv2 (PS) unterscheidet erst die AudioSpecificConfig, und auch dort nur, wenn SBR/PS explizit oder rückwärtskompatibel signalisiert ist. Implizites SBR steht nur im Bitstrom und ist ohne Decoder nicht prüfbar. Deshalb gilt:
- MPD `mp4a.40.5`/`.29` wird nicht angeboten (`audio_codec`) und in `Fmp4Inspect` abgelehnt (`codec_mismatch`).
- Bei `mp4a.40.2` muss die ASC AAC-LC ohne SBR/PS-Signalisierung sein.

**P1 Dauer.** Vorher war die Toleranz der Ausgabeprüfung die größte Stückdauer (S3a erlaubt bis 3 600 s). Gegenbeispiel: ein formal gültiges MPD mit 600-s-Schlussstück (Periode 616 s) und echten 20-s-Fragmenten ergab |≈20 − 616| = 596 < 600, also **veröffentlicht**. Das ist mit echtem Media3 auf `54398dd` reproduziert. Neu sind zwei Stufen:
1. Die tatsächliche Dauer je Spur aus den Fragmenten muss in [Plandauer − 1 s, Summe der vollen Stückdauern + 1 s] liegen.
   - Die Plandauer ist auf die Periode beschnitten; S3a hält sie schon bis auf 1 s an der MPD-Dauer.
   - Die obere Grenze lässt genau die Medien zu, die das MPD ausdrücklich vor bzw. hinter die Periode legt (angeschnittenes erstes/letztes Stück).
   - `$Number$`/`@duration` mit kürzerem Schlussstück liegt damit innerhalb der Grenzen.
2. Die Ausgabe des Remuxers muss der tatsächlichen Dauer entsprechen (± 1 s, **Fix 2:** ohne Anteil außerhalb der Periode). Zusammen mit den unveränderten Sample-Zahlen erkennt das auch Lücken und Überlappungen.

**Fix 2 – Begründung.** Der unveränderte Remuxer bekommt nur die Stückdateien (`HlsRemux.Input`: Video, Ton, fMP4, Probengrenze). Weder MPD noch Periode erreichen ihn. Er schreibt jede Probe, und `Fmp4Edits` verschiebt Zeitstempel nur um die Editlisten-Korrektur, ohne Proben zu verwerfen. `verify` verlangt außerdem genau die gemeldete Probenzahl.

Angeschnittene Stücke erscheinen also vollständig in der Ausgabe. Ihr Maß ist die schon ermittelte tatsächliche Dauer (`vActual`/`aActual`), nicht die Periode. Die frühere Zusatztoleranz „Anteil außerhalb der Periode“ entfällt.

Sie hätte bei einer Periode von 10 s und deklarierten 3600-s-Stücken (S3a nimmt das an) 1 s + 3590 s ergeben. Auf `5657b0e` wurde mit dieser Toleranz eine echte, auf 4 s verkürzte Remux-Ausgabe veröffentlicht (reproduziert). Die Vorprüfung `checkDuration` behält den Periodenanteil als obere Grenze für legitime, angeschnittene erste/letzte Stücke.

Die Sekunde ist dieselbe Grenze wie in S3a (`COVERAGE_TOLERANCE_SECONDS`) und wie die Untergrenze im HLS-Koordinator.

Abgesichert durch echte FFmpeg-Fixtures:
- Zeitleiste 20 s;
- `$Number$`/`@duration` 18 s (letztes Stück 2 s statt 4 s);
- Periode 18,5 s über 20 s Medien.

Alle drei laufen mit echtem Media3 und `analyze.py` auf `done`. Gegenbeispiele (alle `media_duration`, nichts veröffentlicht):
- 600-s-Schlussstück;
- alle Stücke doppelt so lang wie die Fragmente;
- halb so lang (obere Grenze);
- ein Videostück durch ein echtes 2-s-Stück bzw. ein Tonstück durch ein echtes 0,06-s-Stück ersetzt.

`HlsRemux`, `Media3HlsRemuxer` und der HLS-Koordinator sind unverändert.

## 9. Offen für S3c (nicht Teil von S3b)

- Kadri: Fingerabdruck übergeben, `dashplan`/`dashstart` anbieten, Optionsauswahl, Statusanzeige.
- Hinweis zu nicht unterstützten Spielern (WebRTC/P2P, WebM) in Kadri dokumentieren.
- Optional: verschlüsselt gespeicherte Quelle für DASH (Fortsetzen nach Neustart ohne neuen `dashstart`).
