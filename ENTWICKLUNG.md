# Weiterentwickeln

Alles, was man braucht, um am Video-Assistent weiterzuarbeiten: Aufbau, Schnittstelle zwischen Skript und App,
Bauen, Tests und die Stolperfallen, die schon gelöst sind (bitte nicht wieder einbauen).

## 1. Aufbau des Userscripts (`src/` → `userscript/video-assistent.user.js`)

**Seit 17.0 wird in `src/` gearbeitet**, nicht in der fertigen Datei. `src/` enthält das Skript in 28 Teilen
(`010-kopf.js` … `280-diagnose.js`, je ein Thema). Die Teile sind Abschnitte **einer** Funktion (das Skript läuft
als Ganzes in einer Hülle) – einzeln kein gültiges JavaScript, aneinandergehängt genau das Userscript:

```
node tools/build.js           # userscript/video-assistent.user.js aus src/ bauen (prüft die Syntax)
node tools/build.js --check   # passt die fertige Datei zu src/? (läuft in der CI vor den Tests)
node --expose-internals tools/unbenutzt.js   # Unbenutztes finden: Funktionen/Variablen, CONFIG, Übersetzungen, CSS
```

`tools/ersetze.py` (`rep(alt, neu)`) ändert Text in genau dem Teil, in dem er steht. Neue Teile: dreistellige
Nummer dazwischen wählen (z. B. `235-…`). Die Reihenfolge der Dateinamen ist die Reihenfolge im Skript.

~5.200 Zeilen, `@inject-into content`, `@run-at document-start`,
nutzt `hls.js` per `@require`. Die Oberfläche liegt in einem **geschlossenen Shadow-DOM** an einem Element mit
Zufallsnamen (Seiten können sie weder sehen noch verändern). Abschnitte, von oben nach unten:

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
| `test_dlclose.py` | nach Download an YTDLnis: Player zu beim Zurückkommen, Seite an der Stelle; Teilen abgebrochen: bleibt offen |
| `test_e2e.py`, `test_pair.py` | **echter** Java-Server-Kern + Browser: Laden über die Sitzung, Cookies nur an die eigene Seite, Koppeln |
| `test_embed.py`, `test_embed_nofs.py` | eingebettete Fenster (mit / ohne Vollbild) |
| `test_mirror.py`, `test_fb*.py`, `test_ig.py`, `test_tt.py` | Facebook-Spiegel, Instagram, TikTok (nachgebaut) |
| `test_topguard.py`, `test_idle.py` | gesperrter oberer/unterer Rand, sparsamer Takt ohne Video |
| `test_tapzoom.py` | Doppeltipp-Zoom: füllt hochkant, Richtung zum Finger, 3× quer, Verschieben, Gummiband, Schwung, Drehen |
| `test_extras.py` | Untertitel (Seite, .srt/.vtt, Datei, Stream, Lage), ⋮ Mehr: Tonspur, Standbild, Einstellungen, Design, Media Session |
| `test_menuback.py` | ⋮ ohne Überschrift / schließt, ‹ zurück, Zurück-Taste im Menü, Wiederholen 1× / ∞, Sleep-Timer, Dialog-Verstärker (auch Ablehnung bei fremdem Ton) |
| `test_fbshare.py` | Teilen in Facebook-Reels: laufendes Reel statt Seitenadresse |
| `test_mirrorprev.py` | Vorschau beim Spiegeln: ruhig nur die Zeit, kein Flackern beim Ziehen |
| `test_nextreel.py` | Facebook-Countdown „Next reel in 3“ wird bei offenem Player abgebrochen, fremde Dialoge nicht |
| `test_reelend.py` | Reels-Seite springt am Ende von selbst weiter: Player hält vorher an (Anzeige wie fertig, „noch einmal“), beim Schließen bleibt man beim Reel |
| `test_pipkeep.py` | Bild-in-Bild beim Startbildschirm: von außen angehalten → läuft weiter (auch Facebook gespiegelt); eigene Pause, ✕ bleiben |
| `test_leave.py` | App-Wechsel (Fokus/verdeckt) ≠ Zurück-Taste, „Beim Verlassen“, Bild-in-Bild ✕/Vollbild |
| `test_proof.py` | Stream-Nachweis: vorgeladenes nächstes Video wird nicht genommen; Datei-Videos: Gegenprobe der Länge im Player |
| `test_spaswitch.py` | Videowechsel ohne Neuladen (ARD-artig): neuer Stream, neue Seitenadresse beim Teilen, A → B → A |
| `test_scrubbar.py` | Leiste: antippen springt, Ziehen von der aktuellen Stelle, dick beim Ziehen, Abbrechen nach oben (und zurück), fein unter der Leiste |
| `test_livescrub.py` | Spulen im Bild: Leiste und Wischen, Video springt schon beim Ziehen mit; Hochwischen = zurück; auch gespiegelt |
| `test_ui.py` | Aufteilung (⋮ oben, CC unten), Hinweis-Lage, Zeitleiste beim Hochwischen, Sprache Deutsch/Englisch |
| `test_pagesubs.py` | Untertitel nur in den Seitendaten (ARD-artig, EBU-TT ab 10:00:00), in nachgeladener Konfiguration (ZDF-artig, TTML), vom Seiten-Player geladen |
| `test_pixel.py` | sieht man wirklich etwas? Ambient-Licht im Rand, Vorschaubild = echtes Bild an der Stelle (auch nach 13 s) |

**Streng:** `run_all.sh` zählt jede fehlgeschlagene Prüfung (`FAIL`, `FEHLER`, „x von y“ mit x < y), jeden Absturz,
jede Zeitüberschreitung und jeden Test ohne `ERGEBNIS`-Zeile als Fehler und endet dann mit Fehlercode – CI wird rot.
Jeder `test_*.py` muss deshalb am Ende `ERGEBNIS: x von y` ausgeben.

**Messungen** (`mess_*.py`, kein Bestehen/Durchfallen, laufen nur auf Zuruf: `TESTS="mess_perf.py" ./run_all.sh`):
`mess_perf.py` (4× gedrosselt: Rechenzeit, Bilder/s, verworfene Videobilder, Tipp → Leisten; läuft in CI zusätzlich in
Chromium), `mess_prevlag.py` / `mess_prevspeed.py` (Vorschau), `mess_loop2.py` (Spiegel nach Neuladen),
`mess_ffcap.py` / `mess_ffdiag.py` (Untersuchungen), `mess_design.py`, `mess_lens_text.py`, `mess_nest.py`,
`mess_mini_sym.py`, `mess_dark2.py`, `mess_kvs.py`. Mit einer zweiten Skriptdatei als Argument vergleichen einige alt/neu.
Tests tippen „fingerschnell“ (`click(force=True)`), sonst wartet Playwright, bis federnde Knöpfe stillstehen.

**Java-Tests** (`tests/java`, braucht JDK 17, ffmpeg, openssl):
```
cd tests/java && ./run_core_tests.sh    # erzeugt Test-Streams, übersetzt, prüft mit ffprobe
```

Nicht am Rechner testbar: alles mit Android-Klassen (Overlay-Helligkeit, `MediaMuxer`, Download-Manager,
Benachrichtigungen) – das prüft nur das Handy.

## 6. Stolperfallen (schon gelöst – nicht wieder einbauen)

- Spul-Vorschau: einen langsamen Sprung des Vorschau-Videos **nie neu starten** (sonst wird er bei schwachem Netz nie
  fertig – 16.2-Fehler). Der Wächter greift nur, wenn der Sprung fertig ist, aber `seeked` fehlt (`!pv.seeking`).
- Vorschau beim Spiegeln: KEINE gemerkten Bilder nur für angeschaute Stellen – der Wechsel Bild ↔ Zeit flackert.
- Dialog-Verstärker/Verstärkung über 100 % nur mit `canBoost()`: `createMediaElementSource` macht Videos fremder Server
  ohne Freigabe (CORS) STUMM – dann lieber „hier nicht möglich“.
- Kein CSS-`filter: blur()` über große, sich ständig ändernde Flächen (Ambient): kostete auf dem Handy die Hälfte der
  Bilder. Weichzeichnen im Mini-Bild (`ctx.filter`), der Bildschirm skaliert nur.
- Leisten nach einem Tipp sofort zeigen – dann die frischen Knöpfe kurz ohne Tipps (`ctlnew`), sonst trifft der zweite
  Tipp eines Doppeltipps den Play-Knopf.
- Reels-Seiten reagieren auf `ended` – manche schon auf „fast am Ende“. Gespiegelt daher 0,6 s vor dem Ende anhalten
  (`nearEnd`, 40 ms), die letzten Meldungen per Einfang-Zuhörer auf `window` abfangen (`gate`) und beim Schließen am
  Ende das Original NICHT weiterspielen lassen.
- Facebook-Reels: `location.href` bleibt beim ersten Reel – für Links die ID aus dem Kasten des Videos nehmen.
- Zurück-Geste: Nicht sichtbar auf die Entscheidung warten – `veil(true)` sofort, bei Drehen/App-Wechsel/Bild-in-Bild
  `veil(false)`. Jeder „behalten“-Weg in `onFs` muss `veil(false)` aufrufen.
- Vollbild-Ende ist nur dann die Zurück-Taste, wenn die Seite dabei **sichtbar und im Fokus** bleibt – beim
  App-Wechsel kommen `blur`/`visibilitychange` (manchmal erst nach dem Vollbild-Ende, daher der zweite Blick nach 750 ms).
- App-Texte: `Tr.t("deutsch", "english")` (nach Sprache des Handys), App-Name in `res/values*/strings.xml`.
  Die Zustände von `/dlstate` (`fertig`, `läuft`, `Fehler: …`) sind Schnittstelle zum Skript – nicht übersetzen.
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
| 16.3 | Hinweise unter dem Bild (mit Abstand, sonst über der Leiste/dem Menü/den Untertiteln) im Glas der Knöpfe; Wischsperre oben/unten in Worten mit Erklärung; Teilen-Knopf (Beitragslink, im eingebetteten Fenster der Link der Hauptseite); „Helper“ statt „Helfer“. App: Deutsch/Englisch (`Tr`), nur noch eigener Schlüssel, Host-Prüfung gegen DNS-Rebinding |
| 16.4 | Aufteilung „oben schlank“: oben Download + ⋮, unten Tempo · CC · Format · Bild-in-Bild · Drehen; Teilen, In App öffnen, Qualität im ⋮-Menü (Knöpfe unsichtbar in `.vstash`, das Menü löst sie aus) |
| 16.5 | Tempo aus der unteren Leiste ins ⋮-Menü (Liste 0,5×–2×). App: Sprache von Hand (Auto/Deutsch/English, `Keys.lang` → `Tr.force`) |
| 17.0 | Quelltext in `src/` (28 Teile, `tools/build.js`, CI prüft); Altlasten entfernt (Aufklapp-Knopf, feste Seitenverhältnisse, Weiterschauen-Aufräumen). Vorschau: Bilder aus dem laufenden Video, Finger vor Raster, Raster nahe am Finger, Wächter startet langsame Sprünge nicht mehr neu. App-Wechsel schließt den Player nicht; „Beim Verlassen“ (Weiter/Anhalten); Bild-in-Bild ✕ = Pause, „Vollbild“ = zurück in den Vollbild-Player |
| 17.1 | Vorschau beim Spiegeln: gemerkte Bilder aus dem gespiegelten Bild; gemerkte Bilder bleiben beim Wechsel der Vorschau-Quelle |
| 17.2 | Reels: gespiegeltes Original kurz VOR dem Ende anhalten (Seite springt nicht zum nächsten Reel), am Ende geschlossen = Original bleibt angehalten. Vorschau aus Facebook-Seitendaten wieder entfernt (zu unzuverlässig) |
| 17.3 | Vorschau beim Spiegeln wieder nur die Zeit (gemerkte Bilder nur für Angeschautes flackerten beim Spulen: Bild ↔ Zeit) |
| 17.4 | ⋮-Menü ohne Überschrift, zweiter Tipp schließt; Untermenüs mit ‹ (`openMenu(…, back)`), Zurück-Taste bei offenem Menü nur eine Ebene zurück; Wiederholen-Knopf (Aus → 1× → 5×); Zoom-Wischgesten bei sichtbaren Leisten wieder entfernt. App: neue Oberfläche (ohne Titelleiste, erledigte Schritte als Zeile, Sprachwahl Auto/Deutsch/English) |
| 17.5 | Sleep-Timer (15–60 min / Ende des Videos, 8 s ausblenden), Dialog-Verstärker (Hochpass, Sprachanhebung, Kompressor – nur bei freigegebenem Ton), Wiederholen Aus → 1× → ∞, Akzent einfarbig (helle Fläche statt Hellblau). App: Design Auto/Hell/Dunkel, einfarbige Knöpfe |
| 17.6 | Reels: 0,6 s vor dem Ende anhalten + Schleuse (`ended` nie, `timeupdate`/`pause` in der letzten Sekunde nicht zur Seite) – auch Seiten, die schon kurz vor „ended“ weiterspringen. Teilen/Download in Facebook-Reels: Video-ID aus dem eigenen Kasten (`fbVideoId`) statt der Seitenadresse, die beim ersten Reel stehen bleibt; Diagnose zeigt den Link |
| 17.7 | Flüssiger: Ambient weichgezeichnet IM 32-px-Bild (kein Vollbild-`blur(48px)` mehr, Rand per Verlauf statt CSS-Maske), 2,5 statt 8 Bilder/s; Vorschaubilder im Leerlauf; Zeiten/Zeitleiste nur bei sichtbaren Leisten und nur bei Änderung; Tipp → Leisten sofort (statt 280 ms), frisch eingeblendete Knöpfe 300 ms ohne Tipps (Doppeltipp trifft nicht Play). Messung `mess_perf.py`: 35 → 60 Bilder/s, 23 → 0 verworfene Videobilder, Tipp 312 → 25 ms |
| 17.8 | Zurück-Geste: Player beim Vollbild-Ende sofort unsichtbar und stumm (`veil`, Klasse `vgone`), Prüfung (Drehen/App-Wechsel) läuft unsichtbar weiter – kein Zwischenbild mehr mit Browserleisten |
| 17.9 | Übergabe an die Seite (`handBack`): bei der Zurück-Geste springt das Seitenvideo sofort an deine Stelle und läuft mit Ton weiter; bis es dort ein Bild hat, liegt dein letztes Bild darüber (`coverOver`, Canvas im Schatten-DOM; Firefox-Handy: Seitenvideo kurz unsichtbar). Kein „Anfang zeigen, dann springen“ mehr. Doch kein Zurück (Drehen/App-Wechsel): `unHand` macht die Seite wieder still |
| 18.0 | Videowechsel ohne Neuladen (ARD-Mediathek & Co.): „Stream (läuft gerade)“ schaut nie vor den letzten Adresswechsel zurück (`navAt` = letzter Moment mit alter Adresse, auch bei `popstate`, Prüfung beim Tippen) – vorher kam bei schnellem Wechsel der Stream des VORIGEN Videos. Teilen: auf Seiten für genau ein Video (`VIDEO_PAGE_RE`, /video/…-Kennung) die Seitenadresse vor Links neben dem Player. Test `test_spaswitch.py` |
| 18.1 | Stream-Nachweis statt Raten: Stream-Listen (m3u8/mpd), die die Seite lädt, werden mitgelesen (`notePlaylist`: Unter-Listen, Stück-Ordner, Länge; kein eigener Abruf). Lädt die Seite Stücke aus einem dieser Ordner (`noteSegment`, eigener Player zählt nicht), steht dieser Stream als „läuft gerade, geprüft“ vorn (`playingStream`). Gegenprobe Länge: bei der Auswahl (`streamLen`, falsche Länge ans Ende) und im Player (`st.lenOk`: falsche Länge → nächste Quelle, passt keine → zurück zur ersten). Erneut geladene Adressen gelten wieder als neu (A → B → A). Diagnose: Zeile „Stream-Nachweis“. Tests `test_proof.py`, `test_spaswitch.py` |
| 18.2 | Am Ende angehalten (Reels, 0,6 s vor Schluss): Anzeige wie fertig – Zeit = Länge, Leiste und Linie voll, Symbol „noch einmal“ (`heldAtEnd`); zurückgespult: normale Anzeige, Play läuft ab dort statt von vorn. Tests: streng (`run_all.sh` rot bei jedem FAIL/Absturz), Messungen als `mess_*.py`, veraltete entfernt |
| 18.3 | Facebook-Countdown „Next reel in 3 … Cancel“ (läuft auch bei angehaltenem Video): solange der Player offen ist und 10 s danach tippt das Skript sofort auf „Abbrechen“ (`armNextGuard`, MutationObserver nur auf geänderte Teile; nur kleiner Kasten mit Reel/Video + Countdown-Zahl + „weiter/next“-Wort UND Abbrechen-Knopf). Diagnose zählt die Abbrüche. Test `test_nextreel.py` (mit Gegenprobe: anderer Dialog mit „Cancel“ bleibt unberührt) |
| 18.4 | Reels: Halt am Ende 0,2 s vor Schluss statt 0,6 s (`END_GAP`) – der Countdown der Seite wird ja abgebrochen |
| 18.5 | Bild-in-Bild läuft weiter: Hält etwas anderes als du das Video rund um den Wechsel ins kleine Fenster (±2,5 s) oder darin an – Facebook beim Verdecken, der Browser, „Beim Verlassen: Anhalten“ –, läuft es sofort weiter (max. 8×). Gewollt bleibt gewollt: Play/Pause, Knopf im kleinen Fenster (Media Session), Sleep-Timer, Reel-Ende, ✕ (`st.userPauseAt`). Test `test_pipkeep.py` |
| 18.6 | (Erster Versuch – Facebook im Hintergrund weiterlaufen lassen – wieder entfernt: half nicht, zu viel Eingriff.) Bild-in-Bild **aus** beim Spiegeln und auf Facebook/Instagram/Threads (`setPipAllowed`: kein Knopf, `disablePictureInPicture` für Player- und Seitenvideo, kein automatisches kleines Fenster). Vollbild zurück: beim Zurückkommen (sichtbar, Fokus, pageshow) selbst versuchen – sofort, nach 250 und 700 ms (`tryRegain`); aus dem kleinen Fenster sofort; sonst holt JEDE Berührung es zurück, auch Wischen/Spulen/Zoomen (`onAnyTouch`, touchend/pointerup). Hinweis erst nach 0,9 s |
| 18.7 | Download an YTDLnis / Teilen-Menü (`dlOut`): wird die Seite danach verdeckt, hält der Player an; beim Zurückkommen ist er zu, das Seitenvideo steht angehalten an deiner Stelle (`st.closePaused`). Teilen-Menü abgebrochen (`shareUrl` liefert jetzt true/false): Player bleibt offen. Test `test_dlclose.py` |
| 18.8 | Grundlage für die Erweiterung **Kadri** (Repository Owndot/Kadri, baut aus denselben `src/`-Teilen): `IS_KADRI` (die Erweiterung setzt `__KADRI__`), dort ohne laufenden Helfer keine Helligkeit beim Wischen und kein Eintrag „Helper-App“; `kadri-net`-Ereignis nimmt Stream-Listen/API-Antworten aus dem Seiten-Skript der Erweiterung an. Skript und Erweiterung gleichzeitig: Zeichen `data-evp-on` an der Seite – wer zuerst da ist, läuft. Im Userscript sonst keine Änderung |
| 19.0 | Für Kadri 1.1 (im Userscript ohne Wirkung, alles hinter `IS_KADRI`): Facebook zuerst eigener Player, wenn die MP4 sicher zum laufenden Video gehört (`FB_TRY_OWN`, `fbSureSources`: Video-ID = gerade geladene Stücke), falsche Länge → spiegeln (`capFallback`); Bild-in-Bild im eigenen Player auch auf Facebook/Instagram; beim Spiegeln Bild-in-Bild mit „Schild“ (`kadriShield`, page.js verbirgt „verdeckt“ vor der Seite, eigene Zuhörer über `onVisChange` + `kadri-vis`); Stream-Listen vom Hintergrund (Content-Type, `KADRI_HOOKS.onSrc`, Kennzeichen `#kadri.m3u8`). Seitenlisten (`ytdlpSites`, `downloadOnlySites`, `disabledSites`) jetzt Einstellungen (kaputte Liste → Standard) |
| 19.1 | Kadri: ohne Helfer auch keine Lautstärke beim Wischen; Bild-in-Bild-Knopf immer (ohne Browser-Bild-in-Bild: Hinweis auf den Startbildschirm). Diagnose: Zeile „Bild-in-Bild … | Vorschau …“ |
| 19.2 | Vollbild von selbst nur versuchen, wenn der Browser es gerade erlauben würde (`touchFresh`: `navigator.userActivation.isActive`) – sonst nur Fehlermeldungen in der Konsole („API can only be initiated by a user gesture“); gilt fürs Zurückkommen, das kleine Fenster und die Hauptseite eingebetteter Player (`frameFs`). Hinweis: Meldungen „accelerometer/deviceorientation blocked“ auf Facebook kommen vom Browser (Drehen-für-Vollbild jedes Videos), nicht vom Skript |
| 19.3 | Spulen „im Bild“ (wie Samsung Internet, neue Einstellung `seekPreview`, Standard `live`): beim Ziehen der Leiste und beim Wischen springt das Video selbst mit – geht überall, auch beim Spiegeln; immer nur ein Sprung auf einmal, danach die neueste Stelle (`liveTo`/`livePump`); Hochwischen über die Leiste → zurück an die alte Stelle. `thumb` = kleines Vorschaubild wie bisher. Kadri/Facebook: eigener Player nur, wenn die MP4 mindestens ~so scharf ist wie das Laufende (`fbRes`), sonst spiegeln. Test `test_livescrub.py`; Vorschau-Tests laufen mit `thumb` |
| 19.7 | Spulen mit Lupe nach dem Vorbild von YouTubes „präzisem Spulen“ (halten = genauer Modus) statt Tempo-Automatik: ziehen = Stelle unter dem Finger; beim Ziehen ~½ s stillhalten (unter 6 px) oder die Leiste gedrückt halten → kurzes Vibrieren, die Leiste zeigt nur einen Ausschnitt (Länge ÷ 8, 20–60 s; nur bei Videos ab 1,5 min), dessen Grenzen links/rechts als Glas-Schilder (`.vtz`), Zeit-Schild violett (`.vp.zoom`). Finger an ein Ende der Leiste (28 px) = Ausschnitt wandert mit (bis ~0,6 Ausschnitte/s, `panTick`). Bleibt bis zum Loslassen. Zeit über der Vorschau jetzt im Glas des Designs (`.g`: Glas mit Lichtbrechung, Schlicht = Milchglas), rot getönt beim Abbrechen. `prevShow(p, t)`: Zeit getrennt von der Stelle auf der Leiste. Test `test_scrubbar.py` mit langem Video (Lupe, Wandern, Design) |
| 19.6 | Spulen: Stelle (Punkt) beim Ziehen genau unter dem Finger, wie bei YouTube – Anfang/Ende an den Enden der Leiste, der Finger läuft nie aus dem Bild (19.5 zog von der alten Stelle aus und lief deshalb oft an den Rand). Fein nur noch beim bewussten Kriechen (unter ~35 px/s, voll „unter dem Finger“ ab ~120 px/s); danach gleitet die Stelle bei normaler Bewegung wieder unter den Finger (Abstand × e^(−px/25), kein Sprung). Abstand-Anzeige, Einrasten an der alten Stelle und Abbrechen nach oben wie 19.5 |
| 19.5 | Feines Spulen neu (nach dem Vorbild von iOS – Spul-Tempo – und YouTube „Präzises Spulen“): statt „unter der Leiste = ×0,2“ richtet sich das Tempo nach dem Finger wie bei einem Mauszeiger – langsam (unter 60 px/s) = fein (bis ⅛, mindestens 0,08 s pro Pixel), schnell (ab 300 px/s) = wie die Leiste. Über der Vorschau steht der Abstand zur alten Stelle („0:38 · +18 s“). Weißer Strich auf der Leiste = alte Stelle (`.vbarmk`); dorthin zurück rastet ein (kurzes Vibrieren, „± 0“, `.scrubsnap`), loslassen dort = nichts ändern. Facebook beim Spiegeln (Kadri): Vorschaubild aus der fertigen MP4 desselben Videos (sicher erkannt, jede Schärfe, kleinste zuerst – `fbSureSources(v, null, true)`, `st.fbPrev`); falsche Länge → nie wieder (`st.fbBad`), dann nur die Zeit. Diagnose: „Vorschau: Bilder aus der MP4 (gespiegelt)“. Test `test_scrubbar.py` erweitert |
| 19.4 | Spul-Vorschau wieder Standard „Kleines Bild“ (`thumb`), „Im Bild“ als Wahl. Leiste: eigene, 56 px hohe Berührungsfläche (`.vbarhit`) statt des Schiebereglers – antippen = hinspringen, ziehen = von der aktuellen Stelle aus (kein Sprung zum Finger), Leiste beim Ziehen dick ohne Punkt (`.thick`), unter der Leiste fein (×0,2, „fein“), weit nach oben = Abbrechen-Bereich mit Hinweis (`.scrubx`), loslassen dort = zurück, wieder runter = weiter; Android übernimmt die Geste = abbrechen. Test `test_scrubbar.py` |
