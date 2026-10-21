# Video-Assistent

Ein eigener Videoplayer für den Browser auf Android – wie der Video-Assistent von Samsung Internet, nur schöner und
auf allen Seiten. Besteht aus zwei Teilen:

| Teil | Was es ist | Wo |
|---|---|---|
| **Userscript** | der Player selbst (läuft in Vivaldi/Chrome **und Firefox** mit Violentmonkey) | `userscript/video-assistent.user.js` |
| **Helfer-App** | kleine Android-App für alles, was ein Browser nicht darf (Helligkeit, Lautstärke, Downloads) | `app/` → fertige APK unter **Releases** |

Der Player funktioniert auch **ohne** Helfer-App – dann regeln die Gesten nur das Video selbst.

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
- Zwei Finger = zoomen, Bildformate (Original, Füllen, Strecken, 16:9, 4:3, 21:9), Qualität wählen
- Sperre (entsperren: 1 s halten oder doppeltippen), Bild-in-Bild, Drehen, Geschwindigkeit
- dünne Fortschrittslinie, wenn die Leisten ausgeblendet sind; am Ende anhalten statt endlos schleifen

**⋯ Mehr** (Knopf unten rechts im Player)
- **Untertitel**: von der Seite (auch wenn die Seite sie mit ihrem eigenen Player zeigt, z. B. ARD/ZDF-Mediathek –
  das Skript sucht sie beim Öffnen des Menüs), aus dem Stream oder eigene `.srt`/`.vtt`/`.ttml`-Datei vom Handy;
  Größe, Lage (unten/oben **im Bild** – auch bei Querformat-Videos hochkant) und Verschiebung (früher/später)
- **Tonspur** (Sprache) bei Streams mit mehreren Tonspuren
- **Standbild speichern** (JPG in Downloads) – wo die Seite es zulässt, nicht in Firefox auf dem Handy
- **Einstellungen**: Vollbild, Sprungweite (5/10/15/30 s), Tempo beim Halten, Randsperren, Weiterschauen, Ambient,
  Fortschrittslinie, Glas, Helfer-App, Download-Weg, Drehen – gelten für alle Seiten und **überstehen Updates**

**Weiterschauen**: bei Videos ab 3 Minuten merkt sich der Player die Stelle (bis zu 200 Videos) und bietet beim
nächsten Öffnen „Weiter ab 12:34“ an.

**Benachrichtigung & Sperrbildschirm**: Titel, Play/Pause, Springen und Position – auch Kopfhörer-Tasten.

**Seiten**
- Facebook (spiegelt das Bild des Facebook-Players → immer das richtige Video), Instagram, TikTok, Pornhub u. a.
- eingebettete Player (Video in einem fremden iframe): der Kasten wird über den ganzen Bildschirm gelegt,
  Titel kommt von der Hauptseite
- Seiten mit Kopierschutz (Netflix, Disney+, Prime …) bleiben bewusst aus

**Übergeben und Herunterladen** (Knopf ⬇ – Tippen = bester Weg, lange drücken = alle Wege)
- Seiten, die yt-dlp kennt → **direkt in YTDLnis** (ohne Teilen-Menü)
- mit Helfer-App: Videodateien, **HLS-** und **DASH-Streams** → MP4 in `Download/Video-Assistent`
  (auch verschlüsselte HLS, getrennte Tonspur); verlangt ein Server die Anmeldung des Browsers, lädt das Skript
  automatisch über die Browser-Sitzung
- ohne Helfer: Download des Browsers; „In App öffnen“ für VLC & Co.

**Helfer-App**
- echte **Bildschirmhelligkeit** (nur solange der Player offen ist, danach automatisch zurück) und
  **Medienlautstärke** in 1-%-Schritten
- Downloads mit Fortschritt und „Abbrechen“ in der Benachrichtigung
- nur vom Handy selbst erreichbar, mit eigenem geheimem Schlüssel (Koppeln)

**Nicht möglich:** Kopierschutz (DRM), Livestreams speichern, Videos ganz ohne Adresse (z. B. Facebook-Spiegel →
dort YTDLnis). Den Vollbild-Hinweis des Browsers („zum Beenden … von oben ziehen“) kann keine Seite abschalten.

---

## Installieren

1. **Violentmonkey** in Vivaldi installieren (Erweiterungen, ab Vivaldi 8.2 auf Android).
2. **Userscript:** `userscript/video-assistent.user.js` in Violentmonkey installieren (alte Version vorher löschen).
3. **Helfer-App (optional):** unter **Releases → neueste** `VideoAssistent-Helfer.apk` laden und installieren.
   In der App die Karten der Reihe nach antippen:
   1. Helligkeit erlauben („Über anderen Apps einblenden“)
   2. vom Akku-Sparen ausnehmen
   3. Benachrichtigung erlauben
   4. **Mit dem Browser verbinden** → Vivaldi wählen → „✓ Verbunden“
   „Ausprobieren: 3 Sekunden dunkel“ prüft die Helligkeit.

Neue App-Versionen einfach über die alte installieren (gleicher Signaturschlüssel).

### Firefox (Android)

Alles funktioniert auch in Firefox. Violentmonkey aus den Firefox-Add-ons installieren, dann dasselbe Skript.
Die Helfer-App muss **jeden Browser einmal koppeln**: in der App bei „4 · Mit dem Browser verbinden“ auf
**Erneut** tippen und Firefox wählen (der Schlüssel bleibt derselbe, Vivaldi bleibt verbunden).

Unterschiede zu Vivaldi:
- Glas: einfaches Milchglas statt Wellen-/Lupen-Glas (Firefox kann diesen Filter nicht hinter Flächen zeichnen)
- Bild-in-Bild: Firefox' eigenes – im Vollbild die Home-Taste drücken (Firefox-Einstellung „Bild-im-Bild“)
- YTDLnis: Firefox fragt beim ersten Mal, ob die App geöffnet werden darf

## Einstellungen

Die wichtigsten stellst du **im Player unter ⋯ → Einstellungen** ein – die gelten für alle Seiten und bleiben bei
Updates erhalten. Alle Werte stehen außerdem oben im Userscript (`CONFIG`, Standardwerte):

| Einstellung | Standard | Bedeutung |
|---|---|---|
| `startFullscreen` | `true` | Player im Vollbild öffnen (`false` = ohne Browser-Hinweis, aber mit Statusleiste) |
| `lensGlass` | `true` | Wellen-/Lupen-Glas (`false` = einfaches Milchglas, spart etwas Grafikleistung) |
| `ambient` / `progressLine` | `true` | Ambient-Licht / Fortschrittslinie (auch im Format-Menü umschaltbar) |
| `topGuard` / `bottomGuard` | `0.15` | oberer / unterer Bereich ohne Helligkeits-/Lautstärke-Gesten (Anteil der Höhe) |
| `holdSpeed` | `2` | Tempo beim langen Drücken |
| `skipSeconds` | `10` | Sprungweite für Doppeltipp und ±-Knöpfe (5, 10, 15, 30) |
| `resumePlayback` | `true` | Weiterschauen (Stelle merken ab 3 Minuten Länge) |
| `subSize` / `subHigh` | `'m'` / `false` | Untertitel-Größe (`s`/`m`/`l`) und oben im Bild statt unten |
| `systemHelper` | `true` | Helfer-App benutzen, wenn sie läuft |
| `downloader` | `'ytdlnis'` | `'share'` = Teilen-Menü statt direkt YTDLnis |
| `fbMirror` | `true` | Facebook: Bild des Facebook-Players spiegeln |
| `buttonAt` | `'below'` | Player-Knopf `'below'` (unter dem Video), `'video'` (im Video) oder `'top'` |
| `onlyWithSound` | `true` | Knopf nur bei Videos mit Ton (stumme Feed-Videos lösen ihn nicht aus) |
| `autoRotate` | `false` | Querformat-Videos automatisch drehen |
| `resumeAfterClose` | `true` | nach dem Schließen läuft das Video auf der Seite weiter |
| `ytdlpSites`, `downloadOnlySites`, `disabledSites` | Listen | wohin ⬇ YTDLnis nimmt / nur Download / Skript ganz aus |

Änderungen direkt im Skript gehen bei einem Update verloren – darum besser das Menü im Player nutzen
(gespeichert wie der Schlüssel der Helfer-App).

## Repository

```
userscript/            das Userscript (aktuelle Version)
app/                   Helfer-App (Java, ohne Bibliotheken)
tests/browser/         Browser-Tests (Python + Playwright) mit Testseiten in web/
tests/java/            Tests für den Download-Kern (ohne Android) + Erzeugung der Test-Streams
.github/workflows/     baut bei jedem Push die APK (Release) und testet das Skript in Chromium + Firefox
ENTWICKLUNG.md         Aufbau, Schnittstelle, Tests, Stolperfallen – zum Weiterarbeiten
```
