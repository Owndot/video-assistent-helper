# Weiterentwickeln

Alles, was man braucht, um am Video-Assistent weiterzuarbeiten: Aufbau, Schnittstelle zwischen Skript und App,
Bauen, Tests und die Stolperfallen, die schon gelöst sind (bitte nicht wieder einbauen).

## 1. Aufbau des Userscripts (`userscript/video-assistent.user.js`)

Ein einziges Skript (~4.100 Zeilen, ohne Build-Schritt), `@inject-into content`, `@run-at document-start`,
nutzt `hls.js` per `@require`. Die Oberfläche liegt in einem **geschlossenen Shadow-DOM** an einem Element mit
Zufallsnamen (Seiten können sie weder sehen noch verändern). Abschnitte in der Datei, von oben nach unten:

| Abschnitt | Aufgabe |
|---|---|
| Einstellungen (`CONFIG`) | alle Schalter, siehe README |
| Eingebettete Fenster | Brücke iframe ↔ Hauptseite per `postMessage` (`__evpFrame1`): Kasten vergrößern, Vollbild, Drehen, Titel, Ränder (safe-area) |
| 1) Netzwerk-Quellen | `PerformanceObserver` sammelt HLS/DASH/MP4-Adressen (Fetch/XHR-Haken sehen im content-world nichts) |
| 2) Facebook/Instagram | fertige MP4s aus Seitendaten; Pufferwachstum ↔ Video-Zuordnung (nur ohne Spiegel-Modus) |
| 3) Passende Quelle | `resolve()` / `playerSources()` – welche Adresse gehört zu GENAU diesem Video |
| Externer Player | `intent://`-Adressen (VLC, YTDLnis …), `openExternal()` |
| Helfer-App | `helper()` (GM_xmlhttpRequest an 127.0.0.1:47811), Koppeln, gedrosseltes Senden (`helperSend`) |
| Laden über den Browser | `proxyLoop()`: holt Anfragen des Helfers ab (`/pull`), lädt mit Browser-Sitzung, liefert (`/push`) |
| Oberfläche / Button | Player-Knopf unter dem Video, verschiebbar, Position pro Seite gemerkt |
| Lupen-Glas | Verschiebungskarten (Canvas) als SVG-Filter für `backdrop-filter` |
| Eingebauter Player | `openPlayer()` … `closePlayer()`: Video, Gesten, Menüs, Vorschau, Ambient, Spiegeln (`captureStream`), Download-Wege |
| TikTok / Instagram / Diagnose | Sonderwege und Diagnose (lange auf „Player“ drücken oder Violentmonkey-Menü) |

Wichtige Abläufe:
- **Gesten:** `pointerdown` merkt Start (`g`), ab 14 px Bewegung wird die Art festgelegt (spulen/Helligkeit/Lautstärke);
  danach zählt jede Bewegung gleich (kein Sprung), Wert wird an den Enden abgeschnitten. Start am Rand (Zurück-Geste)
  oder oben (`topGuard`) → keine Helligkeit/Lautstärke.
- **Helligkeit mit Helfer:** Reglerwert ist „gefühlt gleichmäßig“ (Androids Kurve, `gammaToLin`/`linToGamma`).
  Unter 0 % weiter → `Auto` (`/end`). **Lautstärke:** Android-Stufen + Feinabstufung über die Videolautstärke
  (`volDb`, Androids Medien-Kurve).
- **Download-Knopf:** yt-dlp-Seite → YTDLnis · Helfer → `/download` (danach `/dlstate` beobachten; bei 401/403
  automatisch `p=1` = über den Browser) · sonst Browser-Download · lange drücken → `dlMenu()`.

## 2. Aufbau der Helfer-App (`app/src/main/java/de/evp/helfer/`)

Reines Java, keine Bibliotheken, `minSdk 26`, `targetSdk 34`.

| Klasse | Aufgabe |
|---|---|
| `HelferService` | Vordergrund-Dienst (`specialUse`): Server starten, Helligkeit (1-px-Overlay mit `screenBrightness`, notfalls Systemeinstellung), Lautstärke, Wächter (30 s ohne Lebenszeichen → Helligkeit zurück) |
| `MiniServer` | HTTP nur auf 127.0.0.1, prüft den Schlüssel, GET + `POST /push`, Koppel-Seite `/pair` – **ohne Android-Teile** |
| `Keys` | eigener Zufallsschlüssel; alter fester Schlüssel `evp-1` gilt nur bis zur ersten Kopplung |
| `Fetcher` | Laden: `Http` (Referer, User-Agent, Cookies nur an die eigene Domain), `Proxy` (über den Browser), `toFile` (Dateien in 4-MB-Stücken) – **ohne Android-Teile** |
| `HlsCore` | HLS: beste Stufe, getrennte Tonspur, fMP4, AES-128, Byte-Bereiche, parallel – **ohne Android-Teile** |
| `DashCore` | DASH: SegmentTemplate (Timeline/Dauer), SegmentList, SegmentBase, längste Periode – **ohne Android-Teile** |
| `Downloads` | Aufträge, Benachrichtigungen, Download-Manager (mit Fehlerstatus), `MediaMuxer` → MP4, Speichern über MediaStore |
| `MainActivity` | Einstellungsseite (Rechte, Koppeln, Start/Stopp, Test) |
| `BootReceiver` | Autostart nach Neustart/Update |

Die mit „ohne Android-Teile“ markierten Klassen lassen sich am Rechner übersetzen und testen (`tests/java`).

## 3. Schnittstelle Skript ↔ App

Alle Anfragen mit Kopf `X-EVP-Key: <Schlüssel>` an `http://127.0.0.1:47811`. Webseiten können den Kopf nicht
setzen (Browser fragt vorher nach, der Server antwortet darauf nicht) – nur das Userscript über Violentmonkey.

| Aufruf | Wirkung |
|---|---|
| `GET /ping` | Zustand: `v`, `dl`, `dash`, `proxy`, Rechte (`overlay`/`write`), Lautstärke (`vol`, `volStep`, `volMax`), `bright` |
| `GET /bright?v=0..1` | Helligkeit setzen (linear) |
| `GET /vol?v=0..1` | Lautstärke-Stufe setzen (ohne `v`: abfragen) |
| `GET /beat` | Lebenszeichen, solange der Player offen ist (alle 2,5 s) |
| `GET /end` | Helligkeit zurück an das System (auch automatisch) |
| `GET /download?u=…&ref=…&ua=…&c=…&t=…&k=file\|hls\|dash&p=1` | Video speichern · `c` = Cookies der Seite · `p=1` = über den Browser |
| `GET /dlstate?id=…` | Zustand: `startet`, `läuft`, `12/40`, `zusammensetzen`, `fertig`, `Fehler: …` |
| `GET /pull?job=…` | über den Browser: offene Anfragen abholen (wartet bis 8 s) → `{reqs:[{id,u,o,l,s}],done}` |
| `POST /push?job=…&id=…&st=…&total=…` | über den Browser: Daten einer Anfrage (Körper = rohe Bytes) |
| `GET /pair` | Koppel-Seite, ohne Schlüssel abrufbar – der Schlüssel steht nur im `#`-Teil der Adresse |

**Koppeln:** App öffnet `http://127.0.0.1:47811/pair#SCHLÜSSEL` im Browser → Skript prüft ihn mit `/ping` und
speichert ihn per `GM_setValue('helperKey')` (übersteht Skript-Updates).

## 4. Bauen

- **App:** jeder Push auf `main` baut per GitHub Actions eine signierte APK (`keystore.jks`, Passwort im
  `app/build.gradle`) und ersetzt das Release **neueste**. Versionsnummer = Build-Nummer.
- **Skript:** kein Build. `@version` und die Zeile `Video-Assistent X.Y` in der Diagnose hochzählen, Datei nach
  `userscript/` kopieren.

## 5. Tests

**Browser-Tests** (`tests/browser`, Python + Playwright, Chromium oder Firefox):
```
pip install playwright==1.56.0 pillow && playwright install chromium
cd tests/browser && ./run_all.sh        # oder einzeln: python3 test_player.py
BROWSER=firefox ./run_all.sh            # dieselben Tests in Firefox (playwright install firefox)
TESTS="test_v8.py test_sfx.py" ./run_all.sh   # nur ausgewählte
```
`pwshim.py` (erste Zeile jedes Tests) tauscht bei `BROWSER=firefox` Chromium gegen Firefox und passt die Optionen an.

**Auf GitHub** (`.github/workflows/browser-tests.yml`): bei jeder Änderung an Skript/Tests laufen alle Tests in
Chromium und im aktuellen Firefox; von Hand („Run workflow“) auch mit Auswahl von Tests und Browsern
(`firefox` = Firefox 142, `firefox-neu` = aktuelles). Die Ergebnisse stehen als Übersicht im Lauf und – samt
Protokoll je Test und Bildschirmfotos – in den Zweigen `ergebnisse-chromium` / `ergebnisse-firefox-neu`
(`git fetch origin '+refs/heads/ergebnisse-*:refs/remotes/origin/ergebnisse-*'`).
Jeder Test startet einen kleinen Webserver mit den Seiten aus `web/`, lädt das Skript (Shadow-DOM wird zum Testen
geöffnet) und prüft Verhalten und Aussehen (Screenshots landen im Ordner). Die Helfer-App wird nachgebaut
(`GM_xmlhttpRequest` → Python). Wichtige Tests:

| Test | prüft |
|---|---|
| `test_player.py`, `test_v8.py` | Grundfunktionen, Gesten, Menüs, Qualität |
| `test_helper.py` | Helligkeit/Lautstärke über den Helfer, Auto, Feinstufen, Lebenszeichen |
| `test_dl3.py` | Download-Wege (Helfer, YTDLnis direkt, Menü) |
| `test_e2e.py`, `test_pair.py` | **echter** Java-Server-Kern + Browser: Laden über die Sitzung, Cookies nur an die eigene Seite, Koppeln |
| `test_embed.py`, `test_nest.py` | eingebettete / verschachtelte Fenster |
| `test_mirror.py`, `test_fb*.py`, `test_ig.py`, `test_tt.py` | Facebook-Spiegel, Instagram, TikTok (nachgebaut) |
| `test_topguard.py`, `test_idle.py` | gesperrter oberer/unterer Rand, sparsamer Takt ohne Video |
| `test_tapzoom.py` | Doppeltipp-Zoom: füllt hochkant, Richtung zum Finger, 3× quer, Verschieben, Gummiband, Schwung, Drehen |
| `test_extras.py` | Untertitel (Seite, .srt/.vtt, Datei, Stream, Lage), ⋮ Mehr: Tonspur, Standbild, Einstellungen, Design, Media Session |
| `test_ui.py` | Aufteilung (⋮ oben, CC unten), Hinweis-Lage, Zeitleiste beim Hochwischen, Sprache Deutsch/Englisch |
| `test_pagesubs.py` | Untertitel nur in den Seitendaten (ARD-artig, EBU-TT ab 10:00:00), in nachgeladener Konfiguration (ZDF-artig, TTML), vom Seiten-Player geladen |
| `test_pixel.py` | sieht man wirklich etwas? Ambient-Licht im Rand, Vorschaubild = echtes Bild an der Stelle (auch nach 13 s) |

Bekannt: `test_tt.py` besteht 2 von 3 (TikTok liefert ohne Anmeldung keine Video-ID) – kein Fehler.
Tests tippen „fingerschnell“ (`click(force=True)`), sonst wartet Playwright, bis federnde Knöpfe stillstehen.
`test_ffcap.py` / `test_ffdiag.py` sind Untersuchungen (Spiegeln, Zeiger-Ereignisse) ohne Bestanden/Fehler.

**Java-Tests** (`tests/java`, braucht JDK 17, ffmpeg, openssl):
```
cd tests/java && ./run_core_tests.sh    # erzeugt Test-Streams, übersetzt, prüft mit ffprobe
```

Nicht am Rechner testbar: alles mit Android-Klassen (Overlay-Helligkeit, `MediaMuxer`, Download-Manager,
Benachrichtigungen) – das prüft nur das Handy.

## 6. Stolperfallen (schon gelöst – nicht wieder einbauen)

- Sprache: alle sichtbaren Texte bleiben im Code deutsch und laufen durch `tr()` (in `h()`, `toast()`, `showInd()`,
  Menüs). Neue Texte → Eintrag in `EN` (oder Muster in `EN_RX`). Tests laufen mit `locale de-DE` (pwshim.py).

- **Erzwungener Dunkelmodus** (Vivaldi „Dunkle Webseiten“) färbt helles Glas dunkel. Lösung: helle Teile dunkel
  zeichnen und mit `filter: invert(1)` umdrehen (`color-scheme: only light` reicht in Chromium nicht).
- **`all: unset`** bei `.vb` setzt auch `backdrop-filter`/`background` von `.g` zurück → eigene Regel `.vb.g`.
- **`CSS` ist im Skript der Stil-Text** – für `CSS.supports` immer `window.CSS` nehmen.
- **SVG-Filter für `backdrop-filter`** müssen im **selben Shadow-DOM** liegen wie die Fläche. Chrome lädt das
  `feImage`-Bild erst beim ersten Zeichnen und rechnet ruhige Flächen danach nicht neu → kurz ab- und wieder
  anschalten (180 ms / 900 ms).
- **Verschiebungskarte:** die Steigung muss überall < 1 bleiben, sonst überschlägt sich das Bild (Text gespiegelt).
  Test immer über Text/Gitter – Streifen verdecken Fehler.
- **Ein-/Ausblenden der Leisten** nie über `opacity` eines Vorfahren der Glasflächen: darunter rechnet Chrome
  `backdrop-filter` erst nach dem Übergang (erst hell, dann dunkler). `.vchrome` schaltet nur `visibility`, jede
  Fläche (`.g`, Titel, Zeitleiste, Abdunklung) blendet sich selbst ein.
- **Video zentrieren ohne `transform`**: Android setzte die Verschiebung in eingebetteten Vollbild-Fenstern falsch
  um (Bild ¼ Höhe zu hoch). Jetzt `inset:0; margin:auto`.
- **Player-Container `overflow: clip`** + Scroll zurücksetzen – sonst verschiebt das Ambient-Canvas alles.
- **iframes:** `url(#…)`/Vollbild/Drehen nur über die Hauptseite; Rand-Abstände (safe-area) kommen von dort.
  Fremde Fenster dürfen sich nur nach einem echten Tipp und ab sichtbarer Größe vergrößern.
- **Facebook:** Zuordnung Video ↔ Datei ist unsicher (Reels-Feed lädt vor) → Standard ist Spiegeln
  (`captureStream`), mit Wächter für schwarzes Bild und „am Ende anhalten“.
- **Instagram:** eigene Abfragen über `rawFetch` (sonst sieht der eigene Haken sie).
- **Vollbild-Ende ≠ Zurück-Taste**, wenn gerade gedreht wurde oder Bild-in-Bild startet (`onFs`-Logik).
- **Laden über den Browser:** Cookies nur an Seite + Videoserver (gleiche Haupt-Domain), fremde Adressen aus einer
  Playlist ohne Sitzung (`anonymous`).
- **Testumgebung:** Playwright-Chromium kann kein H.264 abspielen → WebM für Abspiel-Tests; Bindungen von
  `expose_function` laufen nur während Playwright-Aufrufen (`page.wait_for_timeout` statt `time.sleep`).
- **Vollbild-Hinweis des Browsers** lässt sich nicht abschalten (weder Seite noch ADB) – nur `startFullscreen: false`.

- **Speicher:** `store.get/set` = `GM_getValue/GM_setValue` (ersatzweise `localStorage` `evp-s-…`, so in den Tests).
  `settings` (nur die Schlüssel in `SETTING_KEYS`) überschreibt `CONFIG` beim Start. (Weiterschauen wurde in 15.0
  entfernt – alte `resume`-Daten löscht das Skript einmalig.)
- **Untertitel** zeichnet der Player selbst (`.vsub`, nur Text – weiß mit Kontur, ohne Fläche). hls.js liefert Zeilen nur an eine eingeschaltete Spur
  → `subtitleDisplay` an, Spur aber auf `hidden`; hls.js hängt außerdem einen leeren `<track src="data:,WEBVTT">` an
  (ignorieren).
- **Untertitel der Seite** (`findPageSubs`, erst beim Öffnen des Menüs): URLs mit Untertitel-Merkmal aus `innerHTML`
  (JSON mit `\u002F` entschärft), aus `performance`-Einträgen und aus bis zu 8 nachgelesenen Player-Konfigurationen;
  ARD zusätzlich `api.ardmediathek.de/page-gateway/pages/ard/item/<id>` (wie yt-dlp). Jede Datei wird geladen und
  geparst (WebVTT/SRT/TTML), WebVTT+EBU-TT derselben Untertitel nur einmal. Zeiten ab 10:00:00 (Sendezeit-Code)
  werden auf die Videolänge zurückgerechnet. Cookies nur an die eigene Seite.

**Firefox** (`GECKO` im Skript):
- Ein Tipp im eingebetteten Fenster gilt **nicht** für die Hauptseite (`navigator.userActivation` dort inaktiv) →
  als Beweis zählt der Fokus auf dem Fenster. Darf die Hauptseite nicht ins Vollbild, meldet sie `fsno` und das
  Fenster schaltet selbst (dort gilt der Tipp noch).
- Ältere Versionen haben nur `mozCaptureStream` – der Ton geht dann **in** die Spiegelung (`FF_CAP`: Player-Video
  nicht stumm, Lautstärke am Player-Video). Neuere haben `captureStream` wie Chrome.
- Lädt das Original neu (Reel-Schleife), bekommt der Spiegel-Strom neue Spuren, aber dasselbe Videoelement zeigt
  sie erst nach erneutem Zuweisen (`rebind`); ein frisch abgegriffener Strom bleibt dort stumm.
- Ein angehaltenes, frisch geladenes Original liefert kein Bild (Chrome: letztes Bild) → Standbild-Canvas `.vhold`,
  bis wieder Bilder kommen.
- Abfragen im Namen der Seite (Instagram/TikTok) über `content.fetch`.
- **Firefox auf Android** (`FF_MOBILE`): Videobilder kommen auf einer Zeichenfläche (`drawImage`) leer an
  (Mozilla-Bug 1479947) → Ambient ist dort ein zweites `<video>` mit demselben Bildstrom (Spiegel-Strom bzw.
  `captureStream()` des Player-Videos, nie `mozCaptureStream` – das nähme den Ton mit), die Vorschau zeigt das
  Vorschau-Video selbst (`.vprev.live`, kein Bilder-Raster). Am Rechner tritt das nicht auf – die Testweiche schaltet
  Firefox per `evp-ffmobile` auf diesen Weg. `test_pixel.py` prüft an den Pixeln (nicht nur „eingeschaltet“).
- Kein Wellen-/Lupen-Glas (SVG-Filter in `backdrop-filter`) → Milchglas; `LENS_OK` prüft auf Chrome.
- Testumgebung: Vollbild ohne `full-screen-api.ignore-widgets` springt auf Bildschirmgröße (1280 px); Größenänderung
  im Vollbild hängt → Tests mit `set_viewport_size` bekommen ein nachgebautes Vollbild; Firefox 142 erzeugt mit
  `has_touch` bei Maus keine Zeiger-Ereignisse (nur bei Berührung). Desktop-Firefox legt einen eigenen Bild-im-Bild-Knopf über Videos, der Klicks
  rechts abfängt, auch durch Overlays hindurch (in der Testweiche abgeschaltet, Android hat ihn nicht). Schriftmaße weichen ab → Tests prüfen Lage,
  nicht Pixelhöhen. GitHub-Protokolle sind aus der Entwicklungsumgebung nicht abrufbar → Ergebnis-Zweige.

## 7. Versionen (kurz)

| Version | Wichtigstes |
|---|---|
| bis 11.x | Player, Gesten, Glas-Design, Facebook-Spiegel, Instagram/TikTok, Vorschau, Ambient, Sperre, ±10-Anzeige |
| 12.0–12.2 | Fortschrittslinie, symmetrisches Querformat, Menü-Auslaufen nur bei Bedarf |
| 12.3–12.4 | eingebettete Fenster über den ganzen Bildschirm, Zentrierung ohne `transform` |
| 12.5–12.7 | Helfer-App: echte Helligkeit/Lautstärke, Feinstufen, Auto durch Weiterwischen |
| 12.8–13.1 | iOS-Regler, Lupen-/Wellen-Glas, Play/±10/Menüs wieder schlicht |
| 13.2–13.3 | Downloads: YTDLnis direkt, Helfer lädt Dateien/HLS/DASH, Cookies, Laden über den Browser |
| 13.4 | oberer Rand ohne Helligkeit/Lautstärke |
| 13.5 | eigener Schlüssel + Koppeln, Cookies nur an die eigene Seite, sparsamer Takt |
| 13.6 | Firefox: eingebettete Player, Facebook-Spiegel (Ton, Neuladen, Standbild), Seiten-Abfragen; Tests in beiden Browsern |
| 13.7 | Firefox-Handy: Ambient-Licht und Spul-Vorschau ohne Kopieren von Videobildern; Pixel-Test |
| 13.8 | auch unterer Rand ohne Helligkeit/Lautstärke (Home-Wischgeste) |
| 13.9 | Doppeltipp Mitte = Bild füllen zum Finger hin; Verschieben nicht mehr in die schwarzen Ränder |
| 14.0 | Zoom wie Telegram (aus dessen offenem Quellcode): 3× um die Tippstelle, ein Finger verschiebt, Gummiband ⅓, Schwung, 250 ms |
| 14.1 | ⋯ Mehr: Untertitel (Seite/Stream/Datei), Tonspur, Standbild, Einstellungen (dauerhaft); Weiterschauen; Media Session |
| 14.2 | Untertitel der Seite finden (Seitendaten, vom Player Geladenes, ARD-Schnittstelle); TTML/EBU-TT-Leser |
| 14.3 | Untertitel-Lage am sichtbaren Bild (unten/oben im Bild, folgt Zoom, weicht den Leisten aus) |
| 14.4 | Querformat: Untertitel bleiben beim Einblenden der Leisten stehen (nur über die Zeitleiste) |
| 14.5 | Querformat, Untertitel oben: direkt unter Titel/Knöpfen (gemessen) |
| 14.6 | Glas-Einstellung wirkt sofort (Wellen/Lupe ↔ Milchglas, `setLens`) |
| 14.7 | Design-Wahl: Glas ↔ Schlicht (`:host(.flat)` – nur Milchglas, ohne Kante/Glanz/Lupe, überall) |
| 14.8 | Schlicht hell wie One UI; Leisten blenden je Fläche ein (kein „erst hell, dann dunkel“) |
| 14.9 | Schlicht: Play/±10 zurückhaltender (Video scheint durch) |
| 15.0 | Hinweise und Untertitel im Stil des gewählten Designs; Weiterschauen entfernt |
| 15.1 | Schlicht: Play/±10 fast durchsichtig |
| 15.2 | Untertitel wie in üblichen Playern: nur Schrift mit Kontur, ohne Fläche |
| 15.3 | Untertitel wie Fernseh-/Streaming-Untertitel: mittlere Stärke, Haarlinien-Kontur, mehr Buchstabenabstand |
| 15.4 | Untertitel-Schriftgröße nach Bildhöhe (≈ 5 %, 14–28 px) – hochkant wie quer |
| 16.0 | Neue Aufteilung: ⋮ Mehr oben rechts, CC-Knopf unten, Bildformat wechselt je Tippen (Original/Füllen/Strecken), kurze Einstellungen inkl. Ambient/Fortschrittslinie; Deutsch/Englisch (`language`, `tr()`); Hinweise an einer Stelle (`notePos`); Zeitleiste: Hochwischen spult nicht |
| 16.1 | Wie Samsung: Zeitleiste über der Knopfreihe (Knöpfe zwischen Leiste und Android-Geste), größerer Kreis; Hochwisch-Schutz mild (ab 10 px seitlich nie Abbruch, sonst erst 40 px hoch oder `pointercancel`) |
| 16.2 | Spul-Vorschau: Wächter gegen hängende Sprünge (kein `seeked` bei Streams → nach 1,2 s neu fragen, Vorschau bleibt nicht stehen) |
