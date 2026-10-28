# Video-Assistent

Ein eigener Videoplayer für den Browser auf Android – wie der Video-Assistent von Samsung Internet, nur schöner und
auf allen Seiten. Besteht aus zwei Teilen:

| Teil | Was es ist | Wo |
|---|---|---|
| **Userscript** | der Player selbst (läuft in Vivaldi/Chrome **und Firefox** mit Violentmonkey) | `userscript/video-assistent.user.js` |
| **Helper-App** | kleine Android-App für alles, was ein Browser nicht darf (Helligkeit, Lautstärke, Downloads) | `app/` → derzeit **keine sicher neu signierte Produktions-APK freigegeben** |

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
schaltet Aus → 1× → ∞ (endlos).

**⋮ Mehr** (oben rechts im Player; noch einmal tippen schließt; Untermenüs mit ‹ zurück, auch die Zurück-Taste
geht im Menü nur eine Ebene zurück)
- **Link teilen** – Link zum Video (Beitrag bei Instagram/Facebook/TikTok, sonst die Seite)
- **In App öffnen** (z. B. VLC) und **Qualität**, wenn es sie für das Video gibt
- **Geschwindigkeit** (0,5× bis 2×, Liste zum Antippen)
- **Sleep-Timer**: 15/30/45/60 Minuten oder Ende des Videos – wird sanft leiser, dann Pause
- **Dialog-Verstärker**: Stimmen deutlicher, laute Stellen leiser (bei Streams und Videos der Seite selbst; nicht, wo die
  Seite den Ton nicht freigibt, z. B. Facebook)
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
- Deutsch/Englisch nach Sprache des Handys oder von Hand („Sprache · Language“ in der App), Design hell/dunkel/automatisch
- nur vom Handy selbst erreichbar, mit eigenem geheimem Schlüssel (Koppeln) – Details unter „Sicherheit“

**Nicht möglich:** Kopierschutz (DRM), Livestreams speichern, Videos ganz ohne Adresse (z. B. Facebook-Spiegel →
dort YTDLnis). Den Vollbild-Hinweis des Browsers („zum Beenden … von oben ziehen“) kann keine Seite abschalten.

---

## Installieren

> **NICHT INSTALLIEREN:** Die historische Helper-APK 1.0.84 verwendet einen kompromittierten Signierschlüssel. Eine sicher neu signierte Nachfolgeversion ist noch nicht freigegeben. Kadri und das Userscript können ohne Helper genutzt werden. Auch GitHubs „Latest“-Kennzeichnung ist derzeit keine Installationsempfehlung.

Die Entfernung historischer APK-Assets aus den offiziellen Releases ist beauftragt. Solange sie noch erreichbar sind, bleiben sie ausdrücklich **nicht zur Installation freigegeben**; [Ausführungsstand und Löschanleitung](HISTORICAL-RELEASE-WARNING.md).

1. **Violentmonkey** in Vivaldi installieren (Erweiterungen, ab Vivaldi 8.2 auf Android).
2. **Userscript:** [video-assistent.user.js](userscript/video-assistent.user.js) in Violentmonkey installieren (alte Version vorher löschen).
3. **Helper vorerst weglassen:** keine historische APK installieren. In den Player-Einstellungen „Helper-App“ deaktivieren, insbesondere bei einer alten Installation. Ohne Helper bleiben der Player und die Browser-/externen Downloadwege nutzbar; Systemhelligkeit, Systemlautstärke und Helper-Downloads fehlen.

### Spätere Helper-Migration: freigegebene Option A

Für alle unterstützten Android-Versionen bleibt die `applicationId` **`de.evp.helfer`**. Der Nachfolger erhält einen neuen, ausschließlich lokal verwalteten Produktionssignierschlüssel. **Keine Lineage, kein weiterer Einsatz des kompromittierten Signierers und kein Darüberinstallieren über die alte Produktions-App.** Die Freigabe dieser Strategie bedeutet noch keine Freigabe einer neuen APK; Erzeugung des Schlüssels und Produktionssignierung gehören erst zum separaten Arbeitspaket 2.

Erst wenn eine sicher neu signierte Produktions-APK separat freigegeben ist:

1. Laufende Helper-Downloads beenden und Sprache, Design und Autostart manuell notieren. Die historische APK hat keinen Einstellungsexport. Browser-/Userscript-Einstellungen sind getrennt; alte Kopplungsschlüssel nicht übernehmen.
2. In Android unter Einstellungen → Apps → Video-Assistent Helper die alte Produktions-App **vollständig deinstallieren**. Keine App-Daten behalten oder aus einer alten App-Sicherung zurückspielen.
3. Die dann ausdrücklich freigegebene neue APK aus der angekündigten Projektquelle prüfen und frisch installieren; Systemberechtigungen erneut erteilen und die drei Einstellungen von Hand setzen.
4. Den vertrauenswürdigen neuen Helper starten und jeden gewünschten Browser über „Mit dem Browser verbinden“ neu koppeln; erst danach Helper-Nutzung wieder aktivieren. Bei Portkonflikt oder Verdacht auf fremde lokale Software zuerst den [vertrauenswürdigen Zustand wiederherstellen](HELPER-BETA-SECURITY.md#bei-verdacht-zurücksetzen-und-neu-koppeln).
5. Künftige reguläre Updates verwenden **denselben neuen Signierer** und einen **steigenden `versionCode`**.

Vollständige Anleitung und Entscheidungsgrenzen: [SIGNING-MIGRATION.md](SIGNING-MIGRATION.md). Teststatus: [MIGRATION-RESULTS.md](MIGRATION-RESULTS.md), [MIGRATION-VALIDATION.md](MIGRATION-VALIDATION.md). Test-/Debug-APKs sind kein sicher freigegebener Produktionsnachfolger.

### Firefox (Android)

Alles funktioniert auch in Firefox. Violentmonkey aus den Firefox-Add-ons installieren, dann dasselbe Skript.
Bei einem später freigegebenen neuen Helper muss **jeder Browser neu gekoppelt werden**: in der App bei „Mit dem Browser verbinden“ auf
**Weiterer Browser** tippen und Firefox wählen (der Schlüssel bleibt derselbe, Vivaldi bleibt verbunden).

Unterschiede zu Vivaldi:
- Glas: einfaches Milchglas statt Wellen-/Lupen-Glas (Firefox kann diesen Filter nicht hinter Flächen zeichnen)
- Bild-in-Bild: Firefox' eigenes – im Vollbild die Home-Taste drücken (Firefox-Einstellung „Bild-im-Bild“)
- YTDLnis: Firefox fragt beim ersten Mal, ob die App geöffnet werden darf

## Sicherheit der Helper-App

- **Integrationszweig `feature/helper-channel-control`:** Der Klartext-Port 47811 und der `X-EVP-Key` entfallen. Der Helper spricht nur noch einen gekoppelten, verschlüsselten Kanal (127.0.0.1:47899, SPAKE2 + Noise KK) mit Steuerbefehlen für Helligkeit und Lautstärke; Downloads über den Helper und die Helferfunktionen des Userscripts sind dort vorübergehend aus. Siehe [HELPER-CHANNEL.md](HELPER-CHANNEL.md). Nicht freigegeben.
- **Loopback und Schlüssel:** Der echte Helper lauscht auf `127.0.0.1:47811` und prüft bei geschützten Anfragen einen zufälligen 128-Bit-Schlüssel aus privatem App-Speicher (`X-EVP-Key`). Der Port ist kein Identitätsnachweis. Das anfängliche Kopplungsfragment wird nicht im HTTP-Abruf übertragen; anschließend sendet der Client den Schlüssel als Header. Der alte feste Schlüssel wird abgelehnt.
- **Grenze der ersten Beta:** Gegenseitige kryptografische Serverauthentifizierung fehlt. Ein fremder lokaler Listener kann unter bestimmten Bedingungen den gespeicherten Schlüssel und sensible Helper-Daten erhalten. Host-/CORS-Schutz gegen gewöhnliche Webseiten schützt nicht vor bereits installierter Schadsoftware. Dieses Restrisiko ist für die erste GitHub-Beta akzeptiert; [Issue #4](https://github.com/Owndot/video-assistent-helper/issues/4) bleibt offen.
- **Cookies:** Die App hat keinen Zugriff auf die Cookies des Browsers. Nur beim Download schickt das Skript die
  Cookies **der Seite mit dem Video** mit; die App gibt sie nur an diese Seite weiter, nie an fremde Server aus einer
  Playlist, und speichert sie nicht. Beim „Laden über den Browser“ gehen Cookies nur an die Seite selbst und den
  Server des Videos.
- **Rechte:** Internet (nur für Downloads und den lokalen Empfänger), über anderen Apps einblenden (unsichtbares
  1-Pixel-Fenster für die Helligkeit), Benachrichtigung, Start nach dem Einschalten. Kein Zugriff auf Kontakte,
  Fotos, Standort, Mikrofon oder andere Apps.
- **Bei Verdacht:** Helper-Nutzung zunächst deaktivieren, den vertrauenswürdigen Geräte-/Portzustand herstellen, dann die Karte „Mit dem Browser verbinden“ lange drücken und alle Browser neu koppeln. Ein Reset allein beseitigt keine aktive Schadsoftware. Details, Grenzen und die vollständige Anleitung: [Helper-Beta-Sicherheit](HELPER-BETA-SECURITY.md).

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
.github/workflows/     baut APK-Artefakte; Veröffentlichung nur ausdrücklich auf main und testet das Skript in Chromium + Firefox
ENTWICKLUNG.md         Aufbau, Schnittstelle, Tests, Stolperfallen – zum Weiterarbeiten
```
