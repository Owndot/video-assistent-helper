# DASH S3b – Download-Koordinator im Helfer (DASH-VOD fMP4 H.264 + AAC → MP4)

**Stand:** S3b auf S3a-Fix 1 (`4a3bc42`). Nur Helfer; Kadri-Produktcode ist unverändert (Anbindung folgt in S3c).

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
   - Option fällt weg, wenn Video + Ton zusammen mehr als 20 002 Ressourcen (je Spur Init + Stücke) oder mehr als 16 GiB geschätzt ergeben; keine Option übrig → `segments_limit`.
   - Plan nur im Arbeitsspeicher, gebunden an Kanal-Sitzung (`peer`) und Kadris `bind`, 10 min gültig, höchstens 4 je Sitzung und 16 insgesamt. Options-IDs sind gesalzene Hashes (24 Hex) ohne Rückschluss auf Adressen.
2. **`dashstart`** (Kennung, Plan-ID, Option, gleiche Bindung): Plan wird verbraucht. Abweichende Sitzung oder Bindung, abgelaufener oder unbekannter Plan → `plan_stale`; unbekannte Option → `option_unknown`.
3. **Jeder Versuch** (auch die Wiederaufnahme) lädt das MPD neu und vergleicht den Fingerabdruck mit dem Plan; Abweichung → FAILED `plan_changed`.
4. Zuerst beide Init-Ressourcen laden und mit `Fmp4Inspect.init` prüfen. Erst danach werden Medienstücke geladen.
5. Jedes Stück: Laden über `HlsFetch.resourceVerified` (Längengrenze, SHA-256, fsync), dann `Fmp4Inspect.segment`, erst dann Aufnahme ins Journal.
6. Remux (global genau einer) über `HlsRemux`/`Media3HlsRemuxer` mit den unveränderten Prüfungen der Ausgabe (Spuren, Samples, Dauer ≈ Plan), dann atomare Veröffentlichung über `DownloadStore`.

## 2. Fmp4Inspect (fail-closed)

- **Init:** oberste Ebene nur `ftyp`/`moov`/`free`/`skip`; `moov` mit genau einer `trak` und `mvex`; `hdlr` `vide` bzw. `soun`; `stsd` mit genau einem Eintrag.
  - Video `avc1`/`avc3`; die Bytes 1–3 von `avcC` (Profil, Kompatibilität, Level) müssen den sechs Hex-Ziffern des MPD-Codecs entsprechen.
  - Ton `mp4a`, `esds` Version 0, OTI `0x40`.
  - Größe ≤ 1 MiB.
- **Verschlüsselung:** `encv`/`enca` oder irgendwo `sinf`, `frma`, `schm`, `schi`, `tenc`, `pssh`, `senc`, `saiz`, `saio`, `sgpd`/`sbgp` mit `seig`, PIFF-`uuid` → `encrypted`. Zusätzlich lehnt schon das S3-Profil `ContentProtection` ab.
- **Stück:** oberste Ebene nur `styp`/`sidx`/`ssix`/`prft`/`emsg`/`moof`/`mdat`/`free`/`skip`, `moof` und `mdat` Pflicht; `moof` nur `mfhd`/`traf`; `traf` nur `tfhd`/`tfdt`/`trun`/`sdtp`/`sbgp`/`sgpd`/`subs`/`uuid`.
- Codes: `init_size`, `mp4_structure`, `mp4_boxes`, `init_box`, `init_track`, `codec_mismatch`, `encrypted`, `segment_box`.

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
- Gründe (Auswahl): `fingerprint_missing`, `manifest_mismatch`, `plan_stale`, `option_unknown`, `plan_changed`, `dash_no_audio`, `segments_limit`, `size_limit`, `segment_size`, `encrypted`, `codec_mismatch`, `remux_io`, `no_space`, `storage`, `http_NNN`, `blocked_*`, `network`, `timeout`, `cancelled`, `busy`.

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

## 6. Begründung der Änderungen außerhalb der neuen Dateien (alle additiv)

- `ControlCommands`: neue Schnittstelle `DashControl`, neue `handle`-Überladung (die alte delegiert mit `dash = null`, Verhalten unverändert), neue Felder nur für `dashplan`/`dashstart`, Kennungs-Eindeutigkeit über die Arten. Bestehende Befehle parsen unverändert; `fp`/`played` werden bei allen anderen Befehlen abgelehnt.
- `ChannelServer`: zusätzlicher Konstruktor mit `dash`; der bisherige delegiert mit `null`.
- `HelferService`: legt `DashDownloads` nur an, wo auch MP4-Downloads existieren; leitet Abbruch/Fortsetzen weiter und meldet die Fähigkeit.
- `tools/check-network-layer.sh`: neue Regeln für `de.evp.helfer.dash` (kein Netz/Datei/Android) und `dashdl` (nur `SafeHttpClient`, keine Android-Typen, kein alter DASH-Weg) mit Selbsttest-Proben.
- CI: `dash-core.yml` (JVM-Tests, Mutationsproben, Netzwerk-Wächter), `helper-channel.yml` (DASH-Fixtures), `run-channel-instrumentation.sh` (separater Lauf des DASH-Gerätetests).

## 7. Tests

- `tests/java/dashdl/run.sh FX [CACHE]` – `TestDashEngine` (88 Prüfungen) (Fixtures aus `make-dash-fixtures.sh`, ffmpeg): Fmp4Inspect mit echten und gezielt veränderten Dateien, Kanalbefehle, Planung, Bindung, Gutfall, Negativfälle, Wiederaufnahme, Abstürze an 9 Haltepunkten, Abbruch.
- `tests/java/dashdl/run_remux.sh FX [CACHE]` – `TestDashRemux` (4) und `TestDashEndToEnd` (3, Ausgabe mit `analyze.py` geprüft) mit echtem Media3-Remuxer (JVM).
- `tests/java/dashdl/mutation.py FX [CACHE]` – 37 Mutationsproben über `DashEngine`, `Fmp4Inspect` und die DASH-Kanalbefehle.
- `DashDownloadsInstrumentedTest` (Emulator API 26/29/34): eigener Lauf in `run-channel-instrumentation.sh`, **nicht** Teil der festen Gerätetor-Liste (`tools/device_gate.py` unverändert). Lokal ohne KVM/SDK **nicht ausgeführt**.

## 8. Offen für S3c (nicht Teil von S3b)

- Kadri: Fingerabdruck übergeben, `dashplan`/`dashstart` anbieten, Optionsauswahl, Statusanzeige.
- Hinweis zu nicht unterstützten Spielern (WebRTC/P2P, WebM) in Kadri dokumentieren.
- Optional: verschlüsselt gespeicherte Quelle für DASH (Fortsetzen nach Neustart ohne neuen `dashstart`).
