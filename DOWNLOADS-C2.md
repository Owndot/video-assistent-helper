# Direkte MP4-Downloads (Phase C2)

Stand: Draft-PR #26 „secure direct MP4 downloads over SafeHttpClient“ auf Basis von C1 (PR #25). Nur direkte
Downloads **öffentlich erreichbarer MP4-Dateien ohne Browser-Sitzung**. Kein HLS/DASH, kein Browser-Push, keine
Cookies, keine Header vom Browser. Produktionsstatus: UNAPPROVED.

## Architektur

```
Kadri ──Noise KK (Phase 2)──> ChannelServer ──> ControlCommands.parse (streng)
                                                   │ dlstart / dlstatus / dlcancel
                                                   ▼
                                     DirectDownloads (de.evp.helfer.dl, ohne Android-Typen)
                                       │ einziger Netzwerkweg            │ Ablage, Journal, Rückmeldungen
                                       ▼                                 ▼
                         SafeHttpClient (C1, 4 Prüfschichten)   Mp4Downloads (Android)
                                                                 ├ API 29+: MediaStore-Pending-Eintrag
                                                                 ├ API 26–28: .part + Umbenennen + Medienscanner
                                                                 ├ StatFs, Journal (atomar), Benachrichtigungen
                                                                 └ Reste nach Prozessende: Downloads.initialize
```

| Teil | Aufgabe |
|---|---|
| `ControlCommands` | neue Befehle `{"op":"dlstart","id":"<32 hex>","url":"…"[,"name":"…"]}`, `{"op":"dlstatus","id":…}`, `{"op":"dlcancel","id":…}`. Jedes weitere Feld (Cookie, Header, Referer, Authorization …) wird abgelehnt; URL nur RFC-3986-Zeichen ≤ 2048, Name ohne Escapes/Steuerzeichen ≤ 120, `dlstart` ≤ 4096 Byte, alle anderen Befehle unverändert ≤ 256 Byte |
| `ChannelServer` | zusätzlicher Konstruktor mit `DownloadControl`; ohne ihn (Phase-2-Zustand) lehnt der Kanal jeden dl-Befehl ab |
| `DirectDownloads` | Auftragslogik: Doppelstart-Schutz, Parallelgrenze, alle Prüfungen, Fortsetzen, Abbruch, Bereinigung, Journal |
| `SafeHttpClient` | um ein Abbruch-Token erweitert (bricht Verbindungsaufbau, Header- und Rumpfphase ab, auch Weiterleitungen) |
| `Mp4Downloads` | Android-Ablage; je Prozess eine Instanz (überdauert einen Dienst-Neustart im selben Prozess) |
| `SafeHttp` / `Nat64Discovery` | NAT64-Präfix auch auf Android 8–10 (siehe unten) |
| `HelferService` | minimal: Engine an den Kanal übergeben, Abbruch aus der Benachrichtigung, `stopAll` beim Beenden |

### Ablauf eines Auftrags

1. `dlstart`: Kennung schon bekannt (laufend, fertig, fehlgeschlagen, abgebrochen, unterbrochen – auch aus dem Journal
   nach einem Neustart) → bekannter Zustand, **nichts** wird gestartet. Sonst höchstens 2 gleichzeitig (darüber
   `busy`, nicht vermerkt). Der Auftrag wird **zuerst** im Journal vermerkt (atomar, fsync), dann gestartet; gelingt das
   Vermerken nicht → `busy`, kein Abruf.
2. `GET` über `SafeHttpClient` (alle C1-Prüfungen, auch für jede Weiterleitung). Eine abgelehnte Prüfung
   (`NetBlockedException`) beendet den Auftrag mit `blocked_<grund>`; es gibt in dieser Klasse keinen anderen
   Abrufweg (CI-Sperre).
3. Status genau 200; `Content-Length` Pflicht, 1 … 16 GiB; `Content-Type` leer oder MP4/QuickTime/M4V/octet-stream;
   die ersten 8 Byte müssen eine ISO-BMFF-Box sein (`ftyp`, `moov`, `mdat`, `free`, `skip`, `wide`, `pnot`, `uuid`).
4. Speicher: frei ≥ Länge + 64 MiB, sonst `no_space` vor jedem Eintrag; alle 8 MiB erneut (Rest + 32 MiB).
5. Streaming mit 64-KiB-Puffer direkt in das unsichtbare Ziel; Schreibfehler → `storage` (nie fortsetzen).
6. Unterbrechung: Fortsetzen nur mit **starkem** Validator (ETag ohne `W/` oder Last-Modified), höchstens 3-mal,
   Range ab der empfangenen Länge an die endgültige URL (erneut geprüft); verlangt 206, `Content-Range` exakt
   `bytes <start>-<total-1>/<total>`, passende Länge und unveränderten Validator – sonst Fehler.
7. Ende: empfangen == `Content-Length`; dann schließen und veröffentlichen (MediaStore `IS_PENDING=0` bzw.
   Umbenennen ohne Ersetzen). Jedes andere Ende entfernt das Unfertige **bevor** das Ende gemeldet wird.
8. Antworten: `{"ok":true,"id":…,"state":running|done|failed|cancelled|interrupted,"bytes":N,"total":N,"reason":…}`,
   `unknown`, `busy`. Nie URL, Host oder Server-Text.

Dateiname und Benachrichtigungstitel stammen nur aus `name` (gefiltert: Buchstaben, Ziffern, wenige Satzzeichen,
≤ 80 Zeichen) oder „Video“, nie aus der URL.

## Getestete Sicherheitsgrenzen

| Grenze | Test |
|---|---|
| nur SafeHttpClient, kein HttpURLConnection/DownloadManager/Fetcher/Socket/WebView/Cookie-Weg in `dl/`, `Mp4Downloads`, `ControlCommands`, `ChannelServer`; `Downloads.start` ohne Aufrufer; `Mp4Downloads` nur mit `SafeHttp.client` | `tools/check-network-layer.sh` + Selbsttest (22 eingeschleuste Verstöße) |
| abgelehnte Prüfung → kein Ausweichen | JVM: 13 private/lokale/ungültige Ziele mit dem strengen Client → `blocked_*`, **0 Verbindungen** zum lauschenden Server; Weiterleitung auf ein unzulässiges Ziel → 1 Anfrage, Zielserver nie kontaktiert. Gerät: 8 Ziele über den echten Kanal mit dem Produktiv-Client, lokaler Port nie kontaktiert |
| keine Cookies/Zugangsdaten | Parser lehnt jedes Zusatzfeld ab (Kanal-Prüfungen, auch über Noise KK); Server sieht weder Cookie, Authorization, Referer noch Origin |
| Doppelstarts | gleiche Kennung während, nach und nach einem Neustart (Journal): genau 1 Anfrage; Journal-Fehler → kein Abruf; beschädigtes Journal → Downloads aus |
| Längen/Range | falscher Status, 206 auf normale Anfrage, chunked/0 ohne Länge, zu groß, Abbruch ohne/mit schwachem Validator, Fortsetzen erfolgreich (SHA-256 gleich), 200 auf Range, falscher Start/Ende/Gesamt, kaputter Header, falsche Länge, geänderter/fehlender ETag, 416, höchstens 3 Fortsetzungen |
| Abbruch | während des Rumpfs (< 2 s, Verbindung geschlossen, Rest entfernt), während der Server die Header zurückhält (kein Warten auf 30-s-Lesezeit), unbekannte Kennung, nach Ende, `stopAll` |
| Speicher | zu wenig Platz vor dem Start (nichts angelegt), Platz schwindet während des Downloads, Schreibfehler |
| keine URL in Antworten/Journal/Benachrichtigungen/Dateinamen/Logcat | JVM-Prüfung aller Ausgaben; Gerät: aktive Benachrichtigungen und Logcat ohne Token und Host |
| RAM | 512 MiB mit 48 MiB Heap (JVM), 64 MiB auf dem Gerät mit gemessenem Heap-Zuwachs |
| NAT64 (API 26–29) | JVM: Entscheidungsregeln mit 19 Prüfungen (keine/gescheiterte Erkennung, reines IPv6, /96 dekodiert, /32–/64 ganz gesperrt, unerklärte AAAA, u-Oktett, IPv4-mapped); Gerät: Abfrage im Emulator-Netz |

Mutationsproben: Download-Engine 21/21, Kanal 19/19 (davon 8 neu für die dl-Befehle), Netzwerkschicht 27/27
(davon 4 neu für die NAT64-Erkennung) – jede abgeschwächte Prüfung lässt die Tests scheitern. Nicht als Probe: die
Prüfung „vorzeitiges Stream-Ende“ (`n < 0`) und „zu langer Rumpf“; beide sind über OkHttp nicht erreichbar (bei
Content-Length wirft OkHttp selbst bei vorzeitigem Ende eine `ProtocolException` und liest nie darüber hinaus) und
bleiben als zweite Absicherung.

## NAT64 auf Android 8–10

Risiko (C1, N1): Vor API 30 nennt Android das netzspezifische NAT64-Präfix nicht. Ein Angreifer-Name mit
AAAA-Eintrag `<Betreiber-Präfix>::10.0.0.1` (oder eine von DNS64 synthetisierte Adresse) sähe wie eine öffentliche
IPv6-Adresse aus; das NAT64-Gateway würde zu einer privaten IPv4-Adresse im Betreibernetz übersetzen.

Absicherung: Erkennung nach RFC 7050 im aktiven Netz (`Network.getAllByName("ipv4only.arpa")`, Einbettung von
192.0.0.170/171 nach RFC 6052, Längen 32–96), je Netz 60 s zwischengespeichert. **Sicher fehlschlagen** – alle
IPv6-Ziele gesperrt (IPv4 bleibt nach den IPv4-Regeln) – wenn:

- die Abfrage scheitert (10 s Cache, dann neuer Versuch);
- eine AAAA-Antwort keine erkennbare Einbettung hat;
- das Netz keine eigene IPv4-Adresse hat (reines IPv6, NAT64/464XLAT ohne erkennbares DNS64-Präfix);
- kein aktives Netz besteht.

Ein gefundenes /96-Präfix wird wie 64:ff9b::/96 dekodiert (eingebettetes IPv4 nach IPv4-Regeln); andere Längen
sperren das ganze Präfix. Bei Mehrdeutigkeit gilt die kürzere (größere) Sperre. Ab API 30 unverändert das Präfix der
Plattform (C1).

Folge: In reinen IPv6-Mobilfunknetzen ohne DNS64-Erkennung schlagen unter Android 8–10 Namen mit AAAA-Einträgen fehl
(C1-Regel: alle DNS-Antworten müssen zulässig sein). Das ist das verlangte sichere Fehlschlagen.

Nicht absicherbar und daher verbleibendes Risiko: NAT64 **ohne** DNS64 in einem Netz **mit** eigener IPv4-Adresse
(ungewöhnlich; das Präfix ist dann ohne Plattform-API nicht ermittelbar).

## Alte Download-Pfade

| Pfad | noch vorhanden | warum nicht erreichbar |
|---|---|---|
| `Downloads.start` → `fileDownload` (Android DownloadManager, Referer/User-Agent) | ja | kein Aufrufer seit Phase 2 (MiniServer entfernt); CI-Sperre verbietet Aufrufe, auch innerhalb von `Downloads.java` |
| `Downloads.start` → `streamJob` → `Fetcher.Http` (HttpURLConnection, Cookies, Referer), `Fetcher.Proxy` (über den Browser), `HlsCore`, `DashCore`, `mux` | ja | nur über `Downloads.start` erreichbar; der Kanal kennt nur die neuen dl-Befehle, die ausschließlich `DirectDownloads` aufrufen; CI-Sperre verbietet `Fetcher`/`HlsCore`/`DashCore`/`Downloads.start` in Kanal- und C2-Code |
| `Downloads.save(Context, File, …)` | ja | kein Aufrufer |
| `HelferService` Aktion `cancel` | ja | bricht nur alte Aufträge ab (es gibt keine), startet nichts |
| `Downloads.initialize`/`serviceStarted`/`stopAll` | ja, **genutzt** | Bereinigung unvollständiger Einträge nach Prozessende – C2 nutzt dieselbe Besitzmarkierung |
| Status `"dl":false` | ja | unverändert: meldet, dass die alte Download-Funktion aus ist; Kadri nutzt die neuen Befehle getrennt |
| `androidTest/ReferrerDownloadTest` (Debug-Variante) | ja | testet nur den alten Fetcher; nicht Teil der Kanal-Testvariante |

R8 behält diese Klassen (`-keep class de.evp.helfer.** { *; }`); sie sind toter Code in der APK. Entfernen wäre der
nächste Schritt, sobald HLS/DASH entschieden ist.

## Änderungen an der Phase-2-Steuerung (gesondert begründet)

| Änderung | Begründung | Test |
|---|---|---|
| `ControlCommands.handle(byte[], Device, DownloadControl)`, neue Felder `id`/`url`/`name` nur für dl-Befehle, Größe 4096 nur für `dlstart` | Start, Status und Abbruch müssen über den vorhandenen verschlüsselten Kanal laufen; eine URL passt nicht in 256 Byte | alle bisherigen gültigen/ungültigen Fälle unverändert (auch mit Handler); 33 neue ungültige dl-Fälle; die 6 alten Mutationsproben gelten unverändert, 8 neue |
| `ChannelServer(Host, Device, DownloadControl)`; alter Konstruktor = ohne Downloads | Handler übergeben, ohne die Kanal-Logik zu ändern | Kanal-Prüfungen und Gerätetest: ohne Handler werden dl-Befehle abgelehnt, mit Handler erreichen sie nur die Download-Steuerung, nie das Gerät |
| `HelferService`: 3 Stellen | Engine anbinden, Abbruch-Aktion, `stopAll` | Gerätetest über den echten Kanal; alle bisherigen Kanal-Gerätetests |

## Größe, RAM, CPU

Größe (CI, temporärer Messzweig, C1 = 60b947a gegen C2; gleicher App-Code wie der PR-Kopf):

| | C1 | C2 | Differenz |
|---|---|---|---|
| Testvariante APK | 1.568.948 B | 1.585.332 B | +16.384 B |
| Release-APK (unsigniert) | 356.277 B | 367.329 B | +11.052 B |
| dex (unkomprimiert) / Klassen | 504.688 B / 544 | 525.696 B / 561 | +21.008 B / +17 |
| installiert (API 34) | 2.107.672 B | 2.145.220 B | +37.548 B |
| installiert nach `speed` | 3.601.104 B | 3.726.356 B | +125.252 B |

Manifest und Berechtigungen unverändert (keine neue Berechtigung, keine neue Komponente).

RAM und CPU (Gerätetest `publishesThroughMediaStoreAndReadsBack`, 64 MiB über einen lokalen Testserver bis zur
fertigen MediaStore-Datei, x86_64-Emulator):

| | API 34 | API 26 |
|---|---|---|
| Dauer / Durchsatz | 663 ms / 96,5 MiB/s | 900 ms / 71,1 MiB/s |
| Prozess-CPU-Zeit | 272 ms | 789 ms |
| Heap nach GC (Zuwachs) | −1.077 KiB | +608 KiB |
| Heap-Spitze (Zuwachs, ohne GC) | 256 KiB | 424 KiB |
| PSS-Zuwachs | −449 KiB | +425 KiB |

JVM (48 MiB Heap): 512 MiB in ≈ 3,7 s, Heap-Spitze 14 MiB (inkl. Testserver im selben Prozess). Puffer der Engine:
64 KiB je Auftrag, höchstens 2 Aufträge; kein Puffern der Datei, keine Zwischendatei auf API 29+.

## Ergebnisse API 34 und API 26

Emulatoren x86_64 (`google_apis`), geschrumpfte Testvariante (R8), Wegwerf-Signierer, je 31 Gerätetests: 16 Kanal
(davon 1 neu: Download-Befehle über den echten Kanal), 9 Netzwerkschicht, 6 MP4-Downloads.

| | API 34 | API 26 |
|---|---|---|
| Ergebnis | **OK (31 Tests)** | **OK (31 Tests)** (nach der Korrektur unten) |
| Veröffentlichung | MediaStore `Download/Video-Assistent`, `IS_PENDING=0`, Größe, MIME, Inhalt SHA-256 gleich | `.part` im app-eigenen Ordner, Umbenennen, Medienscanner liefert `content://media/…` (MediaStore-Eintrag vorhanden), Inhalt SHA-256 gleich |
| Abbruch | 6 ms, kein Eintrag, keine Markierung | 18 ms, keine Teil-Datei |
| zu wenig Speicher (StatFs) | `no_space`, nichts angelegt (5.061 MiB frei) | `no_space`, nichts angelegt (5.818 MiB frei) |
| NAT64-Abfrage | Plattform-API, kein Präfix | RFC 7050 im Emulator-Netz, kein Präfix (IPv4 vorhanden, kein DNS64) |
| private Ziele über den Kanal | 8/8 `blocked_*`, lokaler Port nie kontaktiert | 8/8 |
| Logcat | ohne Token und Testhost | ohne Token und Testhost |

Gefunden und behoben durch die API-26-Tests: Auf Android 8–9 endete jeder erfolgreiche Download als `storage`, weil
die Teil-Datei nach dem Schließen ein zweites Mal synchronisiert werden sollte (fsync jetzt beim einmaligen Schließen).

## Verbleibende Risiken

| # | Risiko | Bewertung |
|---|---|---|
| D1 | API 26–28: Datei liegt im app-eigenen Ordner `Android/data/…/Download/Video-Assistent` (kein `MediaStore.Downloads` vor API 29; öffentlicher Ordner bräuchte die Laufzeit-Berechtigung WRITE_EXTERNAL_STORAGE); Eintrag in den MediaStore über den Medienscanner (auf API 26 belegt: `content://media/…`); wird bei Deinstallation gelöscht | bewusst wie der bestehende Code; dokumentierte Einschränkung |
| D2 | NAT64 ohne DNS64 in Netzen mit IPv4 unter Android 8–10 nicht erkennbar | gering, s. o. |
| D3 | Ein Server kann innerhalb desselben ETag andere Bytes liefern (falsch konfigurierter Server) | nicht prüfbar; MP4-Signatur nur am Anfang |
| D4 | Inhalt wird nur an den ersten 8 Byte als ISO-BMFF erkannt; keine vollständige MP4-Prüfung, kein Virenschutz | Medien werden nicht ausgeführt; Player des Systems öffnet sie |
| D5 | Journal hält höchstens 256 beendete Aufträge; eine sehr alte Kennung könnte nach Verdrängung erneut starten | Kennungen sind zufällig (128 bit) und vom Browser je Download neu |
| D6 | Unklarheiten bei `Downloads.initialize` (z. B. MediaProvider verweigert Löschen) lassen die Markierung stehen; nächster Prozessstart versucht es erneut; MediaStore löscht Pending-Einträge nach 7 Tagen selbst | wie bestehender Code |
| D7 | Benachrichtigungstitel zeigt den vom Browser gelieferten Namen (gefiltert) | Name, nicht URL; Kadri sollte den Seitentitel nur auf Wunsch senden |
| D8 | Gerätetests nur auf x86_64-Emulatoren (API 34, 26), lokale Testserver, kein echtes IPv6-only-Netz | arm64-Gerätetest und NAT64-Netz vor einer Freigabe |
| D9 | alte Download-Pfade als toter Code in der APK | unerreichbar (s. o.), Entfernen später |
| D10 | Kadri-Seite (Senden von dlstart) nicht Teil dieses PR | eigener Schritt nach Prüfung |
