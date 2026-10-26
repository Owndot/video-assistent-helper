# Technische Bestandsaufnahme und erster begrenzter Fix — 26.10.2026

## Ausgangsstände und Umfang

GitHub-main wurde vor Beginn erneut gelesen: Kadri `cc14bcdfb8a7a7d296c32ab3943c74b291be6ac4` (1.12.14), Helper `4541036dd8a90c0a661260b7df226eebdadf5978`. Beide entsprechen den Eigentümerreferenzen; keine neueren Arbeiten werden überschrieben. Die private Helper-APK 1.1.0/11000 ist laut Eigentümer auf dem S26 Ultra installiert und startet. Das ist keine bestätigte Gesamtintegration. Produktions-Policy bleibt UNAPPROVED; Schlüssel/Passwörter werden nicht untersucht. Ein Backup nur auf demselben Mac ist keine unabhängige Ausfallsicherung und bleibt zusammen mit fehlender Produktionsfreigabe ein Veröffentlichungsblocker.

Klassifikation: **nachgewiesen** = konkreter Test oder reproduzierbare Quellcode-Eigenschaft; **Risiko** = technisch plausibler Ablauf, nicht als Gerätefehler bestätigt; **Entscheidung** = bewusstes Verhalten; **Vermutung** = nicht reproduziert; **Hardware offen** = auf echter Hardware nicht verifiziert.

## Prioritäten

| Priorität | Bereich / vorhandenes Ticket | Einordnung | Entscheidung dieser Runde |
|---|---|---|---|
| Hoch für Alltag, kleiner Fix | Veraltete Schlüssel in offenen Tabs, Neustart-/Pairing-Bereich #7 | Nachgewiesen: bestehende Tab-/Userscript-Instanz sendet nach erneuter Kopplung weiter den alten Schlüssel. | Sofortiger begrenzter Client-Fix, keine Protokolländerung. |
| Mittel, abhängig von Mediengröße | Proxy-/Segment-RAM #5 | Nachgewiesene Vollpuffer und synthetischer Heap-Überlauf; tatsächliche Android-Spitzenbelegung Hardware offen. | Nächster sinnvoller Entwicklungsschritt; kein pauschales kleines Segmentlimit. |
| Mittel | Prozessende/Downloads #6 | Prozessgebundene Aufträge/Metadaten sind nachgewiesen; konkrete OEM-Abbruch-/Wiederaufnahmefolgen Risiko. | Getrennt von DownloadManager und Medienformaten untersuchen. |
| Mittel, akzeptiertes Beta-Restrisiko | Lokale Serveridentität #4, Kadri DNS-Grenze #2 | Bereits reproduzierte/erklärte Plattformgrenzen. | Keine neue Kryptografie, kein DNS-Dienst oder Protokollumbau. |
| Mittel für Validierung | PiP/Hintergrund #7 | Einzelbelege vorhanden; letzter Emulatorlauf mit UI-/Grafik-Timeout unvollständig. | Kein neuer Produktfehler oder Samsung-Pass behauptet. |
| Niedrig ohne neue Reproduktion | Firefox-Testtriage #9 | Acht Abweichungen auch auf älterem stabilen Code reproduziert. | Nicht als neue Regression behandeln; gezielt statt vollständiger Matrix testen. |
| Veröffentlichungsblocker | Signaturmigration #3 | Option A/Neusignierung inzwischen entschieden und Prozess vorbereitet; unabhängiges Backup/Freigabe fehlen. | Kein Schlüsselzugriff, keine Policyänderung, keine Veröffentlichung. |

#8 ist noch offen, beschreibt aber teilweise den Stand **vor** PR #10/#3: originabhängige Referrer und DownloadManager ohne manuellen Referer sind inzwischen implementiert und getestet. Nicht denselben behobenen Fehler neu eröffnen. Konkrete Provider-Kompatibilität bleibt eine gesonderte Grenze. #3 enthält ebenfalls historische Lineage-Kriterien; maßgeblich ist die spätere Option A ohne Lineage.

## Architektur und Schnittstellen

- Kadri baut die gemeinsame Player-Quelle zu Chromium-MV3 und Firefox-MV2. MAIN-world-Seitenhook erfasst Netzwerk-/Videodaten; isolierte Inhaltsskripte führen den Player aus. Extension-Speicher und Hintergrundanfragen ersetzen GM-APIs. Firefox hat einen eigenen webRequest-/DNS-Pfad; Chrome DNR-Berechtigungen werden pro Anfrage bereinigt. Diese Modelle sind keine plattformunabhängige DNS-Sicherheitsgarantie.
- Das Userscript nutzt die GM-APIs des Managers. Der Helper ist kein Browser-Videoplayer: er ergänzt Systemlautstärke/Helligkeit und Download-/Muxingfunktionen. 27 von 29 gemeinsamen Quellteilen waren identisch; die Abweichungen in 070/080 betreffen Extension-Netzwerkanpassungen.
- Gemeinsame Schnittstelle bleibt HTTP `127.0.0.1:47811`, Protokollstatus v3, `X-EVP-Key`, `/pair`, `/ping`, `/bright`, `/vol`, `/beat`, `/end`, `/download`, `/dlstate`, `/pull`, `/push`. Neue App-Signatur und Extension-Version sind keine RPC-Authentifizierung und müssen nicht numerisch übereinstimmen.
- Der echte Helper erzeugt/prüft einen zufälligen Bearer-Schlüssel aus privaten Einstellungen. Der historische Client-Platzhalter `evp-1` wird vom echten Server abgelehnt. Ein Portkonflikt verhindert dessen Bereitschaft und anfänglichen Kopplungsaufruf; er authentifiziert keinen fremden Listener gegenüber bereits gekoppelten Clients. Das akzeptierte Restrisiko umfasst Schlüssel, übertragene URLs/Cookies/Proxy-Daten unter der Voraussetzung eines passenden lokalen Angreifers.

## Erster Fix: laufende Clients nach neuer Kopplung

Ursache: `src/080-helper-app.js` las `helperKey` nur einmal beim Dokumentstart. In Kadri waren zusätzlich die GM-kompatiblen Werte im Inhaltsskript ein einmaliger Snapshot. Die bisherigen Pairingtests navigierten nach der Kopplung neu und deckten den weiterhin offenen Videotab nicht ab.

Korrigierter Stand nach unabhängigem Review: Jede neue Kadri-Helper-Anfrage liest asynchron direkt `storage.local`; ein dedizierter Leser in beiden Adaptern ist unabhängig von Dokument-Cache und `storage.onChanged`. Der erste Entwurf mit Cache-Listener hatte zwei kontrolliert reproduzierte Races und gilt nicht als ausreichend. Fehlende/entfernte/ungültige Werte und Fehler brechen ab; jeder Request behält seinen eigenen Lese-Snapshot. Andere Einstellungen behalten das vorherige Verhalten. Der gemeinsame Userscript-Zweig verwendet weiter die vorhandene GM-API. Kandidaten gelten nur für ihren Prüfrequest. Keine Wiederholung mit alten Schlüsseln, kein Redirect-/Serverwechsel oder Authentifizierungsbypass.

Die Änderung erfordert keine neue APK und bleibt zu Helper 1.1.0 kompatibel. Geplante Client-Kennzeichnungen im Draft: Kadri 1.12.15, Userscript 19.30; keine Veröffentlichung. Der Stand enthält keine Produktionssignaturänderung. War der Helper schon beim Öffnen des Players nicht verfügbar, bleibt dessen einmalige Funktionsabfrage ein separater Wiederverbindungsaspekt: der Fix verspricht keine vollständige automatische Neuerkennung eines erstmals gestarteten Helpers in jedem offenen Player.

## Ressourcen, Lifecycle und Fehlerfolgen

- **Nachgewiesen:** `Fetcher.readAll` sammelt bis EOF in ByteArrayOutputStream; `hint` ist kein Limit. Ein tatsächlicher Aufruf mit synthetischem 64-MiB-Input und 4-MiB-Hinweis unter `java -Xmx32m` löste reproduzierbar OutOfMemoryError aus. Kein Netzwerk/Android war daran beteiligt. Das zeigt einen konkreten problematischen Pfad, nicht die Maximalbelegung der App auf Samsung.
- **Nachgewiesen:** Chrome/Firefox-GM-Adapter sammeln Medienchunks; ArrayBuffer-Zusammenbau erzeugt weitere Kopien, `/push` zusätzlich Base64/JSON. `gmGet` liest volle Antwortkörper, auch wenn Range ignoriert wird. Der authentifizierte MiniServer akzeptiert bis 256 MiB Körper pro `/push` und allokiert ein zusammenhängendes Array. Zehn Worker begrenzen Verbindungen, nicht die gesamte Medienbelegung. HLS/DASH halten Segment-Bytearrays; parallele Downloads verstärken Spitzen.
- Bereits **begrenzt**: native Cookie-/Referrer-Dateien verwenden `Http.streamToFile` mit kleinem Puffer, Resume-/Validatorprüfungen und Abbruch. Frühere 64-MiB-/32-MiB-Heap-Nachweise gelten für diesen Stream, nicht für alle Segment-/Proxy-/Blobpfade. Nächste Arbeit: ignorierte Range und Größe unbekannter Antworten gezielt behandeln; legitime große Segmente/Streams erhalten.
- **Nachgewiesene Architekturentscheidung:** Native Jobs, Proxyzustände und Downloadstatus sind prozesslokal. START_STICKY rekonstruiert keinen verlorenen Downloadauftrag. DownloadManager kann unabhängig weiterarbeiten. Der Service schließt beim Stoppen Listener/Overlay; das entspricht keinem dauerhaften Jobjournal. Browser-Proxy braucht die offene Seite; sein Timeout ist keine garantierte Wiederaufnahme.
- **Risiken, noch kein Android-Fehlerinjektionstest:** Prozessende kann temporäre Cachedateien oder MediaStore-Pending-Zustände hinterlassen. `save` setzt IS_PENDING vor dem Kopieren und hat keinen eigenen Fehler-Löschpfad. Der Downloadstatus hält abgeschlossene IDs bis zum Prozessende; die Queues sind nicht insgesamt durch eine Nutzer-Auftragszahl begrenzt. Blockierendes Lesen kann Abbruch bis zum Timeout verzögern. Diese Punkte gehören zu #5/#6, nicht zu einem stillen Komplettumbau.
- **Statische positive Befunde, keine Leak-Freiheitsgarantie:** Player-Cleanup beendet HLS-/Vorschauinstanzen, Audiokontexte, Timer und Medienquellen. Vorschaubilder werden beim Schließen gelöscht und zeitlich gerastert. Es laufen weiterhin seitenweite Erkennungs-/Player-Timer; ein Langzeit-RAM-/CPU-/Akkuvergleich auf Samsung wurde nicht ausgeführt. Der Pairing-Fix ergänzt keine Polling-Timer oder zusätzlichen Netzwerkaufrufe, nur eine gezielte asynchrone Speicherabfrage je neuer Kadri-Helper-Anfrage; keine neuen Polling-Timer.

## Medien, Übergabe und Browserunterschiede

Erkennung kombiniert DOM, Seiten-/API-Daten und Netzwerkquellen; eigene Wiedergabe, Spiegelung und asynchrones HD-Upgrade sind bewusste Pfade. Startposition, Qualitäts-/Quellenwechsel und Rückgabe an das Originalvideo wurden zuvor gezielt korrigiert/getestet. HLS lädt die geprüften Vendor-Bytes; Kadri lädt sie bei Bedarf nach. Untertitel haben eigene Parser-/Darstellungspfade. Diese Runde ändert keinen dieser Bereiche.

PiP hängt von Quelle, Browser-API, Aktivierung, Vollbild- und Sichtbarkeitsereignissen ab; Fallback über Android-Home ist browserabhängig. Helper-Overlay wird über `/end` beziehungsweise den Watchdog zurückgesetzt; OEM-Prozessende ist kein DOM-Ereignis. Echte Samsung-/Vivaldi-PiP-, Lautstärke-/Helligkeits-, Android-Hintergrund- und Energiemessungen sind **auf echter Hardware nicht verifiziert**. Der Besitzer soll dafür keine regelmäßige manuelle Testmatrix übernehmen; konkrete Alltagsberichte dienen zur gezielten Triage.

## Qualität und Grenzen der vorhandenen Tests

- Frühere grüne Chromium-, Referrer-/TLS-, DNR-Recovery-, JVM-/Dateistream-, Socket- und Signaturprozessläufe bleiben ihren geprüften Dateien/Commits zugeordnet. Sie werden nicht ohne Anlass wiederholt.
- Der Android-Kernlauf 40208810093 lieferte echte Einzelbelege, endete aber insgesamt mit PiP-/Grafik-/UI-Automator-Timeout. Kein vollständiger grüner OEM-Nachweis. Ein zuvor zu früher Download-Abschlusscheck wurde im Test korrigiert; das macht den alten Lauf nicht rückwirkend grün.
- Einige bestehende Browser-Helper-Tests simulieren noch den früheren festen Startschlüssel. Das ist UI-/Transportabdeckung eines Stubs, kein Nachweis der heutigen Android-Authentifizierung. Der neue Regressionstest verwendet ausschließlich synthetische eigene Schlüssel.
- Die Firefox-Weiche simuliert in einigen Dateien Vollbild und mobiles Verhalten. Ihre Resultate belegen weder echtes Android-PiP noch OEM-Energieverbrauch. Die acht alten Fehlschläge sind für sich kein Nachweis eines Harnessfehlers **oder** eines neuen Produktfehlers; die konkrete Assertion muss getrennt reproduziert werden.
- Neue lokale Tests führen die tatsächliche `helper()`-Funktion und beide tatsächlichen Extension-Adapter im Node-Kontext aus. Baseline scheitert am veralteten Schlüssel; korrigiert bestehen Mehrtab-/Schlüsselentfernung-/Storagebereich-/Neustart-/Fehlerprüfungen. Das Modell ersetzt keinen Browser.
- Ergänzter Chromium-Test verwendet die echte Erweiterung und einen kontrollierten HTTP-Helper: zwei offene Videodokumente, tatsächliche Kopplung in drittem Tab, Heartbeat/Gesten ohne Neuladen und Persistenz nach Browserprozessneustart. CI-Ergebnisse und genaue Commitzuordnung stehen in den Draft-PRs; keine vollständige Browser-/Emulatormatrix, keine APK-Signierung.

## Weiteres Vorgehen

Zuerst die begrenzte Pairing-Korrektur reviewen. Danach #5 anhand eines ignorierten Range und unbekannter Länge gezielt bearbeiten; Speicher-/Bytehash-/Abbruchtests vor einer Änderung. #6 bleibt der nächste Lifecycle-Schwerpunkt. Keine automatischen Produktionsfreigaben, keine Schlüssel-/Backupmanipulation und keine Protokollumstellung aus diesem Bericht ableiten.

## Review-Nachkorrekturen dieser PRs

Die Node-Tests steuern die Zustellung von Ereignissen sowie Initial-/Schlüsselreads und parallele Auflösung; die echten Adapter werden ausgeführt. Der Chromium-Test liefert nun das reguläre `check()`/`done()`-/`ERGEBNIS:`-Format, der Runner bleibt streng. Die schnellen Regressionen laufen branchunabhängig in den regulären passenden PR-/main-Workflows; historische Gegenproben bleiben Reviewbranch-spezifisch. Helper-main-Pushes starten nur den schnellen neuen Job, keine bisher nicht automatisch gestarteten APK-/Emulatorjobs. Das Speicherproblem #5 bleibt ausdrücklich der separaten nächsten Runde vorbehalten.
