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
- **Downloads:** Videodateien über Androids Download-Manager (mit der Herkunftsseite als Referer), HLS-Streams werden
  Stück für Stück geladen (auch AES-128, getrennte Tonspur, fMP4) und ohne Neukodieren zu MP4 zusammengesetzt.
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
| `GET /download?u=…&ref=…&ua=…&t=…&k=file\|hls` | Video speichern (Datei über den Download-Manager, HLS-Stream → MP4) |
| `GET /dlstate?id=…` | Fortschritt eines Downloads |

Normale Webseiten können den Kopf nicht mitschicken (der Browser fragt vorher nach, der Helfer antwortet darauf nicht).

## Bauen
GitHub Actions baut bei jedem Push automatisch eine signierte APK und veröffentlicht sie unter **Releases → neueste**.
Der Signaturschlüssel liegt fest im Repo (`keystore.jks`), damit sich neue Versionen über alte installieren lassen.

`userscript/` enthält die jeweils aktuelle Version des Userscripts.
