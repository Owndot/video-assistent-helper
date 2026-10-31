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
                                                                 ├ API 26–28: öffentlicher Ordner (Berechtigung),
                                                                 │   .part + Umbenennen + Medienscanner
                                                                 ├ StatFs, Journal v2 (atomar, mit Ablage-Verweis)
                                                                 └ Reste nach Prozessende: Downloads.initialize
                                                                     (API 29+) bzw. eigene .part-Dateien (API 26–28)
```

| Teil | Aufgabe |
|---|---|
| `ControlCommands` | neue Befehle `{"op":"dlstart","id":"<32 hex>","url":"…"[,"name":"…"]}`, `{"op":"dlstatus","id":…}`, `{"op":"dlcancel","id":…}`. Jedes weitere Feld (Cookie, Header, Referer, Authorization …) wird abgelehnt; URL nur RFC-3986-Zeichen ≤ 2048, Name ohne Escapes/Steuerzeichen ≤ 120, `dlstart` ≤ 4096 Byte, alle anderen Befehle unverändert ≤ 256 Byte |
| `ChannelServer` | zusätzlicher Konstruktor mit `DownloadControl`; ohne ihn (Phase-2-Zustand) lehnt der Kanal jeden dl-Befehl ab |
| `DirectDownloads` | Auftragslogik: Doppelstart-Schutz, Parallelgrenze, alle Prüfungen, Fortsetzen, Abbruch, Bereinigung, Journal |
| `SafeHttpClient` | um ein Abbruch-Token erweitert (bricht Verbindungsaufbau, Header- und Rumpfphase ab, auch Weiterleitungen) und um `If-Range` (nur mit Byte-Bereich, nur starker ETag; sonst `BAD_ARGUMENT` ohne Verbindung) |
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
6. Unterbrechung: Fortsetzen **nur mit starkem ETag** (siehe „Wiederaufnahme“), höchstens 3-mal, `Range` ab der
   empfangenen Länge plus `If-Range: <ETag>` an die endgültige URL (erneut geprüft); verlangt 206 mit demselben
   starken ETag, genau einem `Content-Range` `bytes <start>-<total-1>/<total>`, passender Länge und ohne
   `Content-Encoding` – sonst Fehler. Ohne starken ETag (auch mit Last-Modified) scheitert der Download sofort und
   die unvollständige Datei wird entfernt.
7. Ende: empfangen == `Content-Length`; dann schließen und veröffentlichen (MediaStore `IS_PENDING=0` bzw.
   Umbenennen ohne Ersetzen). Jedes andere Ende entfernt das Unfertige **bevor** das Ende gemeldet wird.
8. Antworten: `{"ok":true,"id":…,"state":running|done|failed|cancelled|interrupted,"bytes":N,"total":N,"reason":…}`,
   `unknown`, `busy`. Nie URL, Host oder Server-Text.

## Korrekturen nach der Prüfung

### Wiederaufnahme nur mit starkem ETag

Die frühere Aussage, Last-Modified sei ein starker Validator, war falsch: ein Datum mit Sekundenauflösung ist nach
RFC 9110 (8.8.2.2) nur unter Zusatzbedingungen stark und taugt nicht als sicherer Validator für `If-Range`
(13.1.5 verlangt dort einen starken Vergleich). Jetzt:

| Regel | Umsetzung |
|---|---|
| Fortsetzen nur mit starkem ETag | `"…"` aus `etagc` (RFC 9110 8.8.3, ohne obs-text), kein `W/`, ≤ 512 Zeichen; sonst kein Fortsetzen |
| Validator geht mit der Anfrage | `Range: bytes=<n>-<total-1>` **und** `If-Range: <ETag der ersten Antwort>`; ein korrekter Server liefert bei geänderter Darstellung 200 statt 206 → Fehler `range_ignored`, nie gemischte Bytes |
| Antwort geprüft (auch wenn der Server If-Range ignoriert) | 206; `ETag` vorhanden, stark und zeichengleich (starker Vergleich, 8.8.3.2) → sonst `changed`; kein `multipart/byteranges` → sonst `range_multipart`; genau `bytes <n>-<total-1>/<total>` → sonst `range_header`/`range_mismatch`; Länge = Rest → sonst `range_length`; keine Inhaltskodierung → sonst `encoding` |
| ohne starken ETag | Unterbrechung = `network`, unvollständige Datei entfernt, keine zweite Anfrage |
| auch die erste Antwort | `Content-Encoding` außer `identity` → `encoding` (Längen und Bereiche wären sonst verfälscht) |

Negativtests (JVM, echte Sockets): Last-Modified allein, schwacher ETag, 6 ungültige ETag-Schreibweisen (`abc`,
Leerzeichen, `W/`, `w/`, fehlendes Anführungszeichen, doppelte Anführungszeichen), If-Range mit geänderter
Darstellung (Server antwortet korrekt mit 200), 200 auf Range, schwacher/fehlender/geänderter ETag in der 206,
multipart/byteranges, gzip in 206 und in der ersten Antwort, falscher Start/Ende/Gesamtwert, kaputter oder fehlender
`Content-Range`, falsche Länge, 416. In jedem Fall wird `If-Range` mit dem ersten ETag gesendet und das Unfertige
entfernt. `SafeHttpClient` lehnt If-Range ohne Range und schwache oder ungültige Werte (auch mit CR/LF) ab, bevor eine
Verbindung entsteht.

### Dauerhafte Speicherung unter Android 8–9

Kleinste zuverlässige Lösung: der öffentliche Ordner `Download/Video-Assistent` mit der klassischen
Speicherberechtigung – auf API 26–28 gibt es weder `MediaStore.Downloads` noch einen berechtigungsfreien Weg in einen
öffentlichen Ordner (der Storage Access Framework-Dialog je Download wäre ohne Browser-Integration nicht bedienbar).

| Punkt | Umsetzung |
|---|---|
| Berechtigungen | `READ_EXTERNAL_STORAGE` **und** `WRITE_EXTERNAL_STORAGE`, beide mit `maxSdkVersion="28"`. WRITE war schon deklariert; READ ist jetzt ausdrücklich deklariert – auf API 26–28 war es über die Split-Permission ohnehin angefordert (`dumpsys package` vorher: angefordert, nicht erteilt). Grund (vom API-26-Gerätetest gefunden): Android 8–9 vergibt die Schreib-Ansicht des gemeinsamen Speichers nur, wenn beide erteilt sind; mit WRITE allein bekommt der Prozess die Standard-Ansicht (`/proc/self/mountinfo`: `/runtime/default`, `AccessDeniedException` beim Anlegen des Ordners). Android 10+ fordert keine der beiden an (Gerätetest auf API 34: keine Speicher- oder Medienberechtigung in `requestedPermissions`) |
| Laufzeitabfrage | in der App ein zusätzlicher Schritt „Downloads speichern“ (nur API < 29), fragt READ und WRITE gemeinsam an (Android 8+ erteilt nur, was angefragt wird); nach „Nicht mehr fragen“ öffnet derselbe Knopf die App-Einstellungen; das Erteilen startet den Dienst nicht neu (laufende Downloads bleiben). Gerätetest: Erteilen im laufenden Prozess schaltet die Speicher-Ansicht ohne Neustart auf Schreiben um |
| verweigert (eine oder beide) | Auftrag endet mit `storage_permission` **vor jedem Netzwerkzugriff** (0 Verbindungen, nichts angelegt); Benachrichtigung „Speicherzugriff fehlt – in der App erlauben“ öffnet die App; Steuerung und Kopplung unberührt; kein Ausweichen in den app-eigenen Ordner |
| Ablage | Teil-Datei `.evp-pending-<Markierung>.part` direkt im Zielordner (Punkt-Datei, vom Medienscanner nicht aufgenommen), Umbenennen ohne Ersetzen in den beim Anlegen reservierten Namen, dann Medienscanner |
| nach Deinstallation | bleibt erhalten: das Instrumentierungsskript prüft nach dem Deinstallieren, dass die Testdatei noch in `/sdcard/Download/Video-Assistent` liegt (API 26 und 34), und löscht sie dann |

### Journal und Ablage bei Abstürzen

Journal v2 vermerkt zu jedem laufenden Auftrag den Verweis der Ablage (API 29+: MediaStore-Uri; API 26–28: der
reservierte endgültige Pfad), **bevor** Daten geschrieben werden; v1-Journale bleiben lesbar.

| Absturz | Zustand nach dem Neustart | Ablage |
|---|---|---|
| vor dem Vermerken des Verweises | `interrupted` | Pending-Eintrag bzw. Teil-Datei wird beim Start entfernt (Besitzmarkierung) |
| während des Schreibens | `interrupted` (Verweis zeigt auf nichts Veröffentlichtes) | dito; nie unter dem endgültigen Namen sichtbar |
| nach dem Veröffentlichen, bevor „DONE“ im Journal stand | **`done`**, Bytes = Länge, kein zweiter Download | Datei bleibt; nur als fertig gewertet, wenn sie sichtbar ist und genau `total` Byte hat |
| Prüfung der Ablage scheitert | `interrupted` (sicher, nie ungeprüft `done`) | – |
| nach „DONE“ | `done`, auch wenn der Nutzer die Datei später löscht (Verlauf, kein Neustart) | – |

Ein abgeschlossener Download bleibt nach einem Prozessneustart abgeschlossen (JVM und Gerät: neue Instanz mit
demselben Journal → `done`, später `dlstart` → `done`, keine Anfrage).

### Kann eine öffentliche Datei bei einem fehlgeschlagenen Download sichtbar bleiben?

- **API 29+:** Der Eintrag ist bis zum Veröffentlichen `IS_PENDING=1` und für andere Apps unsichtbar; jedes andere
  Ende löscht ihn. Gelingt das Löschen nicht, bleibt die Besitzmarkierung und `Downloads.initialize` entfernt ihn
  beim nächsten Start; MediaStore verwirft Pending-Einträge zudem nach 7 Tagen. Veröffentlichen ist der letzte
  Schritt nach allen Prüfungen.
- **API 26–28:** Während des Downloads existiert eine versteckte Punkt-Datei `.evp-pending-….part` im öffentlichen
  Ordner. Dateimanager mit „versteckte Dateien anzeigen“ sehen sie; der Medienscanner nimmt sie nicht auf. Jedes
  Ende ohne Erfolg löscht sie; nach einem Absturz entfernt der nächste Prozessstart sie (nur mit eigener Markierung,
  fremde Dateien bleiben unberührt). Unter dem endgültigen Namen erscheint nur eine vollständig geprüfte Datei.
- **Restfall:** Absturz zwischen Umbenennen und Journal – die sichtbare Datei ist vollständig und wird beim Neustart
  als `done` erkannt.

Dateiname und Benachrichtigungstitel stammen nur aus `name` (gefiltert: Buchstaben, Ziffern, wenige Satzzeichen,
≤ 80 Zeichen) oder „Video“, nie aus der URL.

## Getestete Sicherheitsgrenzen

| Grenze | Test |
|---|---|
| nur SafeHttpClient, kein HttpURLConnection/DownloadManager/Fetcher/Socket/WebView/Cookie-Weg in `dl/`, `Mp4Downloads`, `ControlCommands`, `ChannelServer`; `Downloads.start` ohne Aufrufer; `Mp4Downloads` nur mit `SafeHttp.client` | `tools/check-network-layer.sh` + Selbsttest (22 eingeschleuste Verstöße) |
| abgelehnte Prüfung → kein Ausweichen | JVM: 13 private/lokale/ungültige Ziele mit dem strengen Client → `blocked_*`, **0 Verbindungen** zum lauschenden Server; Weiterleitung auf ein unzulässiges Ziel → 1 Anfrage, Zielserver nie kontaktiert. Gerät: 8 Ziele über den echten Kanal mit dem Produktiv-Client, lokaler Port nie kontaktiert |
| keine Cookies/Zugangsdaten | Parser lehnt jedes Zusatzfeld ab (Kanal-Prüfungen, auch über Noise KK); Server sieht weder Cookie, Authorization, Referer noch Origin |
| Doppelstarts | gleiche Kennung während, nach und nach einem Neustart (Journal): genau 1 Anfrage; Journal-Fehler → kein Abruf; beschädigtes Journal → Downloads aus |
| Längen/Range | falscher Status, 206 auf normale Anfrage, chunked/0 ohne Länge, zu groß, Abbruch ohne ETag, mit schwachem oder ungültigem ETag und nur mit Last-Modified (kein Fortsetzen), Fortsetzen mit If-Range erfolgreich (SHA-256 gleich), If-Range bei geänderter Darstellung, 200 auf Range, falscher Start/Ende/Gesamt, kaputter Header, falsche Länge, geänderter/fehlender/schwacher ETag, multipart, gzip, 416, höchstens 3 Fortsetzungen |
| Speicherberechtigung (API 26–28) | verweigert → `storage_permission`, 0 Verbindungen (JVM und API-26-Gerät); API 34: keine Speicherberechtigung angefordert |
| Absturzkonsistenz | Absturz nach Veröffentlichen → `done`; während des Schreibens → `interrupted`, Rest beim Start entfernt; unsichere Prüfung → `interrupted`; Journal-Fehler nach dem Anlegen → `storage`, Rest entfernt (JVM und Gerät) |
| Abbruch | während des Rumpfs (< 2 s, Verbindung geschlossen, Rest entfernt), während der Server die Header zurückhält (kein Warten auf 30-s-Lesezeit), unbekannte Kennung, nach Ende, `stopAll` |
| Speicher | zu wenig Platz vor dem Start (nichts angelegt), Platz schwindet während des Downloads, Schreibfehler |
| keine URL in Antworten/Journal/Benachrichtigungen/Dateinamen/Logcat | JVM-Prüfung aller Ausgaben; Gerät: aktive Benachrichtigungen und Logcat ohne Token und Host |
| RAM | 512 MiB mit 48 MiB Heap (JVM), 64 MiB auf dem Gerät mit gemessenem Heap-Zuwachs |
| NAT64 (API 26–29) | JVM: Entscheidungsregeln mit 19 Prüfungen (keine/gescheiterte Erkennung, reines IPv6, /96 dekodiert, /32–/64 ganz gesperrt, unerklärte AAAA, u-Oktett, IPv4-mapped); Gerät: Abfrage im Emulator-Netz |

Mutationsproben: Download-Engine 29/29 (davon 10 neu für ETag/If-Range, Kodierung, Multipart, Berechtigung und
Absturzwiederherstellung), Kanal 19/19 (davon 8 für die dl-Befehle), Netzwerkschicht 29/29 (davon 4 NAT64-Erkennung,
2 If-Range) – jede abgeschwächte Prüfung lässt die Tests scheitern. Nicht als Probe: die
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

Verbleibende NAT64-Risiken:

| # | Risiko | Bewertung |
|---|---|---|
| N-a | NAT64 **ohne** DNS64 in einem Netz **mit** eigener IPv4-Adresse (Android 8–10): Präfix ohne Plattform-API nicht ermittelbar; ein AAAA-Eintrag `<Präfix>::<privates IPv4>` gälte als öffentlich | ungewöhnliche Netzform; Ziel läge im Betreibernetz, nicht im Heimnetz |
| N-b | Die Erkennung nutzt den DNS-Resolver des Netzes: ein manipulierter Resolver kann für `ipv4only.arpa` „kein DNS64“ melden | wer den Resolver kontrolliert, kann ohnehin beliebige Adressen liefern; die Adressprüfung auf jede Antwort bleibt |
| N-c | Präfix bis zu 60 s zwischengespeichert; ein Netzwechsel wird über die geänderte `Network`-Kennung erkannt, eine Präfixänderung im selben Netz erst nach Ablauf | gering |
| N-d | Ab Android 11 hängt alles am Präfix, das die Plattform meldet (RFC 7050 bzw. RA PREF64); meldet sie keins, gilt nur 64:ff9b::/96 | Plattformverhalten |
| N-e | Sicheres Fehlschlagen auf reinen IPv6-Netzen unter Android 8–10 macht Downloads von Namen mit AAAA-Einträgen dort unmöglich | gewollt (Verfügbarkeit gegen Sicherheit) |
| N-f | Getestet nur mit Entscheidungsregeln auf der JVM und der Abfrage im Emulator-Netz (IPv4, kein DNS64); kein echtes NAT64-Netz | vor einer Freigabe in einem IPv6-only-Netz mit DNS64 prüfen |

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

Emulatoren x86_64 (`google_apis`), geschrumpfte Testvariante (R8), Wegwerf-Signierer. Seit den Korrekturen 35
Gerätetests je Version: 16 Kanal (davon 1 für Download-Befehle über den echten Kanal), 9 Netzwerkschicht, 10
MP4-Downloads.

| | API 34 | API 26 |
|---|---|---|
| Veröffentlichung | MediaStore `Download/Video-Assistent`, `IS_PENDING=0`, Größe, MIME, SHA-256 gleich | öffentlicher Ordner `/storage/emulated/0/Download/Video-Assistent`, Medienscanner liefert `content://media/…`, SHA-256 gleich |
| nach Deinstallation | `C2 Persist 34.mp4` (65.536 B) vorhanden | `C2 Persist 26.mp4` (65.536 B) vorhanden |
| Speicherberechtigung | keine angefordert, trotzdem beschreibbar | ohne: `storage_permission`, 0 Verbindungen; nach Erteilen (READ+WRITE) im laufenden Prozess Speicher-Ansicht `/runtime/write`, Ordner beschreibbar |
| Absturz nach/vor Veröffentlichen | `done` / `interrupted`, Reste 0 | `done` / `interrupted`, Reste 0 |
| Abbruch | 8 ms, kein Eintrag, keine Markierung | 24 ms, keine Teil-Datei |
| zu wenig Speicher (StatFs) | `no_space`, nichts angelegt | `no_space`, nichts angelegt |
| 64 MiB bis zur fertigen Datei | 450 ms (142 MiB/s), CPU 340 ms, Heap nach GC +464 KiB | 4,8 s (13,4 MiB/s, FUSE-Schreibweg des öffentlichen Speichers), CPU 2,1 s, Heap nach GC +608 KiB |
| NAT64-Abfrage | Plattform-API, kein Präfix | RFC 7050 im Emulator-Netz, kein Präfix (IPv4 vorhanden, kein DNS64) |
| private Ziele über den Kanal | 8/8 `blocked_*`, lokaler Port nie kontaktiert | 8/8 |
| Logcat | ohne Token und Testhost | ohne Token und Testhost |

Von den API-26-Tests gefunden und behoben:
1. fsync nach dem Schließen (C2): jeder Download endete als `storage`.
2. Nur WRITE angefragt (diese Runde): ohne READ vergibt Android 8–9 keine Schreib-Ansicht des gemeinsamen
   Speichers, der Ordner ließ sich nicht anlegen. Jetzt READ+WRITE (beide nur bis API 28).

## Verbleibende Risiken

| # | Risiko | Bewertung |
|---|---|---|
| D1 | API 26–28: Downloads brauchen die Speicherberechtigung; ohne sie scheitern sie (kein Ausweichen). Die Berechtigung gilt für den ganzen gemeinsamen Speicher, nicht nur für den Download-Ordner | kleinste verfügbare Lösung vor Android 10; nur angefordert, wenn der Nutzer den Schritt antippt |
| D1b | API 26–28: versteckte `.part`-Datei im öffentlichen Ordner während des Downloads (für Dateimanager mit versteckten Dateien sichtbar) | nach Ende oder beim nächsten Start entfernt |
| D1c | Update eines Geräts von Android 9 auf 10 mit einer verwaisten `.part`-Datei: ab API 29 räumt nur `Downloads.initialize` (MediaStore) auf, die Punkt-Datei bliebe | sehr selten; Datei unsichtbar für Medien-Apps |
| D2 | NAT64 ohne DNS64 in Netzen mit IPv4 unter Android 8–10 nicht erkennbar | gering, s. o. |
| D3 | Ein Server kann unter demselben starken ETag andere Bytes liefern (falsch konfigurierter oder böswilliger Server); dann enthält die fortgesetzte Datei gemischte Bytes | nicht prüfbar ohne Prüfsumme der Quelle; Fortsetzen höchstens 3-mal |
| D4 | MP4-Prüfung nur an den ersten 8 Byte (Größe + Boxtyp `ftyp`/`moov`/`mdat`/`free`/`skip`/`wide`/`pnot`/`uuid`): keine Prüfung der Boxstruktur, der Boxgrößen, des Codecs oder der Abspielbarkeit; eine Datei mit passendem Anfang und beliebigem Rest wird als `.mp4` gespeichert; kein Virenschutz | die Datei wird nie vom Helper ausgeführt oder geparst; geöffnet wird sie vom Player des Systems, dessen Parser die eigentliche Angriffsfläche ist (wie bei jedem Download aus dem Browser). Eine vollständige Strukturprüfung wäre ein eigener Parser (neue Angriffsfläche) und ist bewusst nicht Teil von C2 |
| D4b | MIME-Typ und Dateiendung sind immer `video/mp4`/`.mp4`, auch für QuickTime (`.mov`) | Player erkennen den Inhalt; nur Anzeige |
| D5 | Journal hält höchstens 256 beendete Aufträge; eine sehr alte Kennung könnte nach Verdrängung erneut starten | Kennungen sind zufällig (128 bit) und vom Browser je Download neu |
| D6a | Absturz genau zwischen Umbenennen und Journal, während eine fremde Datei denselben reservierten Namen belegt hat (Android 8–9) | Datei unter dem nächsten freien Namen vollständig vorhanden, Status `interrupted`; extrem unwahrscheinlich |
| D6 | Unklarheiten bei `Downloads.initialize` (z. B. MediaProvider verweigert Löschen) lassen die Markierung stehen; nächster Prozessstart versucht es erneut; MediaStore löscht Pending-Einträge nach 7 Tagen selbst | wie bestehender Code |
| D7 | Benachrichtigungstitel zeigt den vom Browser gelieferten Namen (gefiltert) | Name, nicht URL; Kadri sollte den Seitentitel nur auf Wunsch senden |
| D8 | Gerätetests nur auf x86_64-Emulatoren (API 34, 26), lokale Testserver, kein echtes IPv6-only-Netz | arm64-Gerätetest und NAT64-Netz vor einer Freigabe |
| D9 | alte Download-Pfade als toter Code in der APK | unerreichbar (s. o.), Entfernen später |
| D10 | Kadri-Seite (Senden von dlstart) nicht Teil dieses PR | eigener Schritt nach Prüfung |
