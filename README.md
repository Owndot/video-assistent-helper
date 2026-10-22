# Video-Assistent

Ein eigener Videoplayer für den Browser auf Android – wie der Video-Assistent von Samsung Internet, nur schöner und
auf allen Seiten. Besteht aus zwei Teilen:

| Teil | Was es ist | Wo |
|---|---|---|
| **Userscript** | der Player selbst (läuft in Vivaldi/Chrome **und Firefox** mit Violentmonkey) | `userscript/video-assistent.user.js` |
| **Helper-App** | kleine Android-App für alles, was ein Browser nicht darf (Helligkeit, Lautstärke, Downloads) | `app/` → fertige APK unter **Releases** |

Der Player funktioniert auch **ohne** Helper-App – dann regeln die Gesten nur das Video selbst.

---

## Was es kann

**Player**
- Knopf „Player“ unter jedem laufenden Video (verschiebbar) → eigener Vollbild-Player über der Seite
- Design im Stil von iOS/Samsung: Glasflächen mit feinem Rand und Wellen-/Lupen-Effekt, Ambient-Licht in den Rändern
- Wischen: **links** Helligkeit, **rechts** Lautstärke, **waagerecht** spulen (mit Vorschaubild)
  - oberer und unterer Rand (je 15 %) gesperrt, damit Schnelleinstellungen und die Home-Wischgeste nichts verstellen
  - ganz unten bei der Helligkeit **weiterwischen = Auto** (automatische Helligkeit des Handys)
- Doppeltippen links/rechts = ±10 s (mehrfach = 20, 30 … s), lange drücken = 2× schneller
- Doppeltippen in der Mitte (wie Telegram): 3× Zoom um die getippte Stelle (hochkant bildschirmfüllend), noch einmal
  = ganzes Bild; im Zoom verschiebt **ein Finger** das Bild (mit Schwung, federt an der Kante zurück)
- Zwei Finger = zoomen; Bildformat-Knopf wechselt bei jedem Tippen Original → Füllen → Strecken (wie Samsung); Qualität wählen
- Sperre (entsperren: 1 s halten oder doppeltippen), Bild-in-Bild, Drehen, Geschwindigkeit
- **In eine andere App wechseln** schließt den Player nicht (nur die Zurück-Taste); zurück im Browser holt ein Tipp
  das Vollbild zurück. Einstellung **Beim Verlassen**: Weiter oder Anhalten
- **Bild-in-Bild:** ✕ im kleinen Fenster hält das Video an, „Vollbild“ bringt dich zurück in den Vollbild-Player
- Spul-Vorschau: schon Angeschautes erscheint sofort (Bilder werden beim Abspielen nebenbei gemerkt)
  (auf Facebook und anderen gespiegelten Seiten zeigt die Vorschau nur die Zeit)
- dünne Fortschrittslinie, wenn die Leisten ausgeblendet sind; am Ende anhalten statt endlos schleifen – auf
  Reels-Seiten (Facebook u. a.) springt die Seite dabei nicht zum nächsten Reel
- Zeitleiste wie bei Samsung über der Knopfreihe (die Android-Wischgeste trifft die Knöpfe, nicht die Leiste), großer Kreis;
  deutlich nach oben wischen spult nicht – Halten und seitlich ziehen spult
- alle Hinweise erscheinen an einer Stelle oben im Bild (nicht unten über den Leisten)
- **Untertitel-Knopf (CC)** unten in der Leiste: von der Seite (auch wenn die Seite sie mit ihrem eigenen Player zeigt,
  z. B. ARD/ZDF-Mediathek), aus dem Stream oder eigene `.srt`/`.vtt`/`.ttml`-Datei vom Handy; Größe, Lage (unten/oben
  **im Bild**) und Verschiebung (früher/später)

**Aufteilung**: oben links ✕ und Titel, oben rechts nur **Download** und **⋮**; unten (am Daumen) Sperre links,
rechts **Untertitel (CC) · Wiederholen · Bildformat · Bild-in-Bild · Drehen**. Wiederholen: jedes Tippen
schaltet Aus → 1× → 5× (die Zahl am Knopf zeigt, wie oft noch).

**⋮ Mehr** (oben rechts im Player; noch einmal tippen schließt; Untermenüs mit ‹ zurück, auch die Zurück-Taste
geht im Menü nur eine Ebene zurück)
- **Link teilen** – Link zum Video (Beitrag bei Instagram/Facebook/TikTok, sonst die Seite)
- **In App öffnen** (z. B. VLC) und **Qualität**, wenn es sie für das Video gibt
- **Geschwindigkeit** (0,5× bis 2×, Liste zum Antippen)
- **Einstellungen** – kurz wie in einer Einstellungs-App: Design (Glas/Schlicht), Ambient, Fortschrittslinie, Vollbild,
  Sprungweite, Halten, Wischsperre oben/unten, Auto-Drehen, Beim Verlassen, Download, Helper-App, **Sprache** (Auto/Deutsch/English),
  Zurücksetzen – gelten für alle Seiten und **überstehen Updates**
- **Tonspur** (Sprache) bei Streams mit mehreren Tonspuren
- **Standbild speichern** (JPG in Downloads) – wo die Seite es zulässt, nicht in Firefox auf dem Handy

**Sprache / Language**: Deutsch oder Englisch, automatisch nach Browsersprache (in den Einstellungen umstellbar).
The interface is available in English and German – it follows the browser language by default.

**Benachrichtigung & Sperrbildschirm**: Titel, Play/Pause, Springen und Position – auch Kopfhörer-Tasten.

**Seiten**
- Facebook (spiegelt das Bild des Facebook-Players → immer das richtige Video), Instagram, TikTok, Pornhub u. a.
- eingebettete Player (Video in einem fremden iframe): der Kasten wird über den ganzen Bildschirm gelegt,
  Titel kommt von der Hauptseite
- Seiten mit Kopierschutz (Netflix, Disney+, Prime …) bleiben bewusst aus

**Übergeben und Herunterladen** (Knopf ⬇ – Tippen = bester Weg, lange drücken = alle Wege)
- Seiten, die yt-dlp kennt → **direkt in YTDLnis** (ohne Teilen-Menü)
- mit Helper-App: Videodateien, **HLS-** und **DASH-Streams** → MP4 in `Download/Video-Assistent`
  (auch verschlüsselte HLS, getrennte Tonspur); verlangt ein Server die Anmeldung des Browsers, lädt das Skript
  automatisch über die Browser-Sitzung
- ohne Helper: Download des Browsers; „In App öffnen“ für VLC & Co.

**Helper-App**
- echte **Bildschirmhelligkeit** (nur solange der Player offen ist, danach automatisch zurück) und
  **Medienlautstärke** in 1-%-Schritten
- Downloads mit Fortschritt und „Abbrechen“ in der Benachrichtigung
- Deutsch/Englisch nach Sprache des Handys oder von Hand („Sprache · Language“ in der App)
- nur vom Handy selbst erreichbar, mit eigenem geheimem Schlüssel (Koppeln) – Details unter „Sicherheit“

**Nicht möglich:** Kopierschutz (DRM), Livestreams speichern, Videos ganz ohne Adresse (z. B. Facebook-Spiegel →
dort YTDLnis). Den Vollbild-Hinweis des Browsers („zum Beenden … von oben ziehen“) kann keine Seite abschalten.

---

## Installieren

1. **Violentmonkey** in Vivaldi installieren (Erweiterungen, ab Vivaldi 8.2 auf Android).
2. **Userscript:** `userscript/video-assistent.user.js` in Violentmonkey installieren (alte Version vorher löschen).
3. **Helper-App (optional):** unter **Releases → neueste** `VideoAssistent-Helper.apk` laden und installieren.
   In der App die Karten der Reihe nach antippen:
   1. Helligkeit erlauben („Über anderen Apps einblenden“)
   2. vom Akku-Sparen ausnehmen
   3. Benachrichtigung erlauben
   4. **Mit dem Browser verbinden** → Vivaldi wählen → „✓ Verbunden“
   „Ausprobieren: 3 Sekunden dunkel“ prüft die Helligkeit.

Neue App-Versionen einfach über die alte installieren (gleicher Signaturschlüssel).

### Firefox (Android)

Alles funktioniert auch in Firefox. Violentmonkey aus den Firefox-Add-ons installieren, dann dasselbe Skript.
Die Helper-App muss **jeden Browser einmal koppeln**: in der App bei „4 · Mit dem Browser verbinden“ auf
**Erneut** tippen und Firefox wählen (der Schlüssel bleibt derselbe, Vivaldi bleibt verbunden).

Unterschiede zu Vivaldi:
- Glas: einfaches Milchglas statt Wellen-/Lupen-Glas (Firefox kann diesen Filter nicht hinter Flächen zeichnen)
- Bild-in-Bild: Firefox' eigenes – im Vollbild die Home-Taste drücken (Firefox-Einstellung „Bild-im-Bild“)
- YTDLnis: Firefox fragt beim ersten Mal, ob die App geöffnet werden darf

## Sicherheit der Helper-App

- **Nur auf dem Handy:** Die App lauscht nur auf `127.0.0.1` – aus dem WLAN oder Internet ist sie nicht erreichbar.
- **Eigener Schlüssel:** Beim ersten Start erzeugt die App einen zufälligen 128-Bit-Schlüssel (`SecureRandom`), nur
  in ihrem privaten Speicher. Jede Installation hat einen anderen; „Verbinden“ übergibt ihn nur im `#`-Teil der
  Adresse an das Skript (den sieht kein Server), das Skript entfernt ihn sofort aus der Adresse. Ohne den Schlüssel
  antwortet die App mit „verboten“ (den alten festen Schlüssel früherer Versionen nimmt sie nicht mehr an).
- **Webseiten kommen nicht ran:** Der Schlüssel steckt in einem eigenen Kopf (`X-EVP-Key`) – den dürfen Webseiten
  nicht an fremde Adressen schicken. Seiten, deren Name auf 127.0.0.1 zeigt („DNS-Rebinding“), werden am Namen erkannt
  und abgelehnt.
- **Cookies:** Die App hat keinen Zugriff auf die Cookies des Browsers. Nur beim Download schickt das Skript die
  Cookies **der Seite mit dem Video** mit; die App gibt sie nur an diese Seite weiter, nie an fremde Server aus einer
  Playlist, und speichert sie nicht. Beim „Laden über den Browser“ gehen Cookies nur an die Seite selbst und den
  Server des Videos.
- **Rechte:** Internet (nur für Downloads und den lokalen Empfänger), über anderen Apps einblenden (unsichtbares
  1-Pixel-Fenster für die Helligkeit), Benachrichtigung, Start nach dem Einschalten. Kein Zugriff auf Kontakte,
  Fotos, Standort, Mikrofon oder andere Apps.
- Ein neuer Schlüssel: in der App den Status „✓ Verbunden“ lange drücken, dann neu verbinden.

## Einstellungen

Die wichtigsten stellst du **im Player unter ⋯ → Einstellungen** ein – die gelten für alle Seiten und bleiben bei
Updates erhalten. Alle Werte stehen außerdem oben im Userscript (`CONFIG`, Standardwerte):

| Einstellung | Standard | Bedeutung |
|---|---|---|
| `startFullscreen` | `true` | Player im Vollbild öffnen (`false` = ohne Browser-Hinweis, aber mit Statusleiste) |
| `lensGlass` | `true` | Design: Glas mit Lupe (`false` = Schlicht: nur Milchglas, ohne Glaskante – spart etwas Grafikleistung) |
| `ambient` / `progressLine` | `true` | Ambient-Licht / Fortschrittslinie (auch im Format-Menü umschaltbar) |
| `topGuard` / `bottomGuard` | `0.15` | oberer / unterer Bereich ohne Helligkeits-/Lautstärke-Gesten (Anteil der Höhe) |
| `holdSpeed` | `2` | Tempo beim langen Drücken |
| `skipSeconds` | `10` | Sprungweite für Doppeltipp und ±-Knöpfe (5, 10, 15, 30) |
| `subSize` / `subHigh` | `'m'` / `false` | Untertitel-Größe (`s`/`m`/`l`) und oben im Bild statt unten |
| `systemHelper` | `true` | Helper-App benutzen, wenn sie läuft |
| `downloader` | `'ytdlnis'` | `'share'` = Teilen-Menü statt direkt YTDLnis |
| `fbMirror` | `true` | Facebook: Bild des Facebook-Players spiegeln |
| `buttonAt` | `'below'` | Player-Knopf `'below'` (unter dem Video), `'video'` (im Video) oder `'top'` |
| `onlyWithSound` | `true` | Knopf nur bei Videos mit Ton (stumme Feed-Videos lösen ihn nicht aus) |
| `autoRotate` | `false` | Querformat-Videos automatisch drehen |
| `resumeAfterClose` | `true` | nach dem Schließen läuft das Video auf der Seite weiter |
| `ytdlpSites`, `downloadOnlySites`, `disabledSites` | Listen | wohin ⬇ YTDLnis nimmt / nur Download / Skript ganz aus |

Änderungen direkt im Skript gehen bei einem Update verloren – darum besser das Menü im Player nutzen
(gespeichert wie der Schlüssel der Helper-App).

## Repository

```
src/                   Quelltext des Userscripts in 28 Teilen (hier ändern)
tools/                 build.js baut daraus das Userscript, unbenutzt.js findet Unbenutztes
userscript/            das fertige Userscript (aktuelle Version – zum Installieren)
app/                   Helper-App (Java, ohne Bibliotheken)
tests/browser/         Browser-Tests (Python + Playwright) mit Testseiten in web/
tests/java/            Tests für den Download-Kern (ohne Android) + Erzeugung der Test-Streams
.github/workflows/     baut bei jedem Push die APK (Release) und testet das Skript in Chromium + Firefox
ENTWICKLUNG.md         Aufbau, Schnittstelle, Tests, Stolperfallen – zum Weiterarbeiten
```
