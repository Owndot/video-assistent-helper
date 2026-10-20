# Video-Assistent Helfer

Kleine Android-App, mit der der eingebaute Videoplayer des Userscripts **Video-Assistent** (Violentmonkey) die echte
**Bildschirmhelligkeit** und die **Medienlautstärke** des Handys steuern kann – wie Samsung Internet.

## So funktioniert es
- Die App startet einen Dienst, der nur auf `127.0.0.1:47811` lauscht (vom WLAN/Internet nicht erreichbar).
- Wischt du im Player links (Helligkeit) oder rechts (Lautstärke), schickt das Userscript den Wert an den Helfer.
- **Helligkeit:** ein unsichtbares 1-Pixel-Fenster „über anderen Apps“ trägt den Helligkeitswunsch. Wird der Player
  geschlossen (oder kommt 30 s kein Lebenszeichen), verschwindet es – die normale (auch automatische) Helligkeit ist
  sofort zurück. Ohne dieses Recht, aber mit „Systemeinstellungen ändern“, wird notfalls die Einstellung gesetzt und
  danach zurückgestellt.
- **Lautstärke:** direkt die Android-Medienlautstärke (keine Berechtigung nötig).
- **Downloads:** Videodateien über Androids Download-Manager (mit Herkunftsseite als Referer und den Cookies der Seite),
  HLS-Streams (auch AES-128, getrennte Tonspur, fMP4) und DASH-Streams (SegmentTemplate/Timeline, SegmentList,
  SegmentBase) werden Stück für Stück geladen und ohne Neukodieren zu MP4 zusammengesetzt.
  Weist ein Server den Helfer ab (401/403), lädt das Userscript die Daten mit der Sitzung des Browsers und reicht sie
  an den Helfer weiter (`/pull` + `POST /push`) – dafür muss die Seite offen bleiben.
  Gespeichert in `Download/Video-Assistent`, Fortschritt und Abbrechen in der Benachrichtigung.
- Läuft der Helfer nicht, regelt der Player wie bisher nur das Video.

## Schnittstelle (nur mit Kopf `X-EVP-Key: evp-1`)
| Aufruf | Wirkung |
|---|---|
| `GET /ping` | Zustand: Rechte, Lautstärke, Helligkeit |
| `GET /bright?v=0..1` | Helligkeit setzen |
| `GET /vol?v=0..1` | Lautstärke setzen (ohne `v`: abfragen) |
| `GET /beat` | Lebenszeichen, solange der Player offen ist |
| `GET /end` | Player zu: Helligkeit zurück |
| `GET /download?u=…&ref=…&ua=…&c=…&t=…&k=file\|hls\|dash&p=1` | Video speichern (`c` = Cookies, `p=1` = über den Browser laden) |
| `GET /pull?job=…` | Laden über den Browser: offene Anfragen abholen (wartet bis zu 8 s) |
| `POST /push?job=…&id=…&st=…&total=…` | Laden über den Browser: Daten einer Anfrage liefern |
| `GET /dlstate?id=…` | Fortschritt eines Downloads |

Normale Webseiten können den Kopf nicht mitschicken (der Browser fragt vorher nach, der Helfer antwortet darauf nicht).

## Bauen
GitHub Actions baut bei jedem Push automatisch eine signierte APK und veröffentlicht sie unter **Releases → neueste**.
Der Signaturschlüssel liegt fest im Repo (`keystore.jks`), damit sich neue Versionen über alte installieren lassen.

`userscript/` enthält die jeweils aktuelle Version des Userscripts.
