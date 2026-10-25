# Android-E2E: technischer Stand vom 25.10.2026

Aktueller Abschlussstatus und begrenzter Kernlauf: [FINAL-VALIDATION-REPORT.md](../../FINAL-VALIDATION-REPORT.md).
Die nachstehenden vollständigen Läufe sind historische Einzelbelege.

Diese Umgebung verwendet echte Android-x86_64-Systeme und installierte APKs.
API33 entspricht Android13, API35 Android15. Die lokale Ausführungsumgebung
steht nicht zur Verfügung; ausgeführte Gerätetests liefen auf GitHub-Actions-
Emulatoren mit KVM. Es wurde kein physisches Samsung-Gerät bedient.

## Quellstand und sichere Ausführung

- Helper-Testumgebung: Branch codex/android-e2e, vollständiger Vergleich
  [40197766277](https://github.com/Owndot/video-assistent-helper/actions/runs/40197766277),
  Automation c6d0cde3cb74e785888e3a1c55e9091deb708bb4.
- Android-App: c81bac088cbca74c534ad8f6fb76deed7237e0f0; APKs aus einem vorherigen
  Testlauf wiederverwendet. Vollständiger app-Gitbaum und Gradle-Eingaben wurden
  vor jeder Wiederverwendung verglichen; APK-SHA256 wurden geprüft. Der App-Code
  wurde seit den unten genannten Produktkorrekturen nicht verändert.
- Kadri: unverändertes dist/firefox aus
  f76c209db9a3749007f4cd6a180d6d8509eb5971. Kein offener Shadow-DOM-Testbau,
  keine GM-Simulation und keine Aufrufe interner Playerfunktionen.
- Offizielles Firefox158.0.1-x86_64 und echter Violentmonkey2.49.0; der unveränderte
  Userscript-Stand wird über dessen echte Installieren-Oberfläche eingerichtet.
  Das vorhandene veränderliche hls.js@1-CDN-Require bleibt bestehen; diese Route
  benötigt Internetzugang bei der Installation.
- Helper-Paket-IDs .debug und .e2ecompeting, neu erzeugter Testschlüssel mit
  zwei Tagen Zertifikatsgültigkeit; privater Schlüssel nach dem Build gelöscht.
  Kein Produktionsschlüssel, kein Produktionspaket, keine Veröffentlichung.
  Zertifikatsablauf/Artefaktlöschung widerrufen keine bereits heruntergeladene APK.
- Workflows haben Leserechte. Emulatoren starten nur über den reservierten
  Ausführungsbranch oder einen absichtlichen Dispatch; normale Entwicklungs-
  Commits starten keine Matrix. Maximal zwei API-Jobs, beide Browserrouten
  nacheinander. Der Inspektor liest vorhandene Artefakte ohne Emulatorneustart.
- ADB gewährt Overlay-/Benachrichtigungsrechte, Batterieausnahme und Firefox-
  Standardbrowserrolle. Dies ist Testaufbau, kein Nachweis der manuellen
  Berechtigungsdialoge. Connect, Kopplung, SAF, Player und Downloads werden
  anschließend über echte UI-Aktionen bedient.
- Der Runner verweigert physische Geräte vor Installation und Profilrücksetzung.
  Niemals gegen das persönliche Samsung-/Firefox-Profil ausführen.

## Nachgewiesene Produktfehler und Korrekturen

| Befund | Nachweis | Korrektur |
|---|---|---|
| HTTP-Medienserver durch Android-Netzwerkpolicy blockiert | Installierte APK in Run40140618467, zweimal Cleartext HTTP not permitted | 5f830f1: application usesCleartextTraffic für die bereits angebotenen HTTP-Medien; HTTPS-Prüfung bleibt aktiv |
| Explizites Cookie von DownloadManager an fremde Redirect-Domain gesendet | Installierte APK in Run40141678616, zweimal erster und fremder Host mit Cookie | e8c919a: Dateien mit erlaubtem Cookie über bestehende Fetcher.Http-Hop-Prüfung und 64KiB-Dateistream; DownloadManager erhält keine manuellen Cookies |

Die korrigierte APK speicherte auf API33 UND35 die Datei nach sicherem Redirect
mit identischer SHA256. Die positive Kontrolle verlangt ein Cookie am ersten
Host; der fremde Host darf weder Cookie noch Authorization erhalten.
Authorization wurde in diesem nativen Szenario nicht eingespeist. Es ist daher
kein allgemeiner Nachweis für jeden denkbaren Authorization-Pfad.

Der neue Credential-Dateistream nutzt maximal zwei eigene Dateiworker mit
Idle-Timeout; bestehende cookie-lose DownloadManager-Dateien bleiben erhalten.
Wiederaufnahme verlangt Validator und korrekten Content-Range; ignorierte Range
startet neu. Ein separater HTTP/JVM-Test überträgt 64MiB unter -Xmx32m und prüft
unbekannte Länge, Abbruch, abgeschnittene Antwort, Wiederaufnahme sowie falsche/
ignorierte Range. Das ist Host-Transportabdeckung, kein Android-UI-Test.

Materielle Grenze: Cookie-Dateien hängen nun wie natives HLS vom App-/FGS-
Prozess ab. Eine dauerhafte Wiederaufnahme nach hartem Prozessverlust ist nicht
implementiert oder nachgewiesen. Fertige Dateien verwenden die vorhandene
Cache-zu-MediaStore-Kopie. Deren zusätzlicher Plattenbedarf/I/O sowie Abbruch
während blockierter Header/Reads müssen weiter geprüft werden. Bestehende
Connect-/Read-Timeouts bleiben 15/30s. Browser-Proxy-Pfade mit unbekannter Länge
sind durch diesen gezielten Fix nicht pauschal speicherbegrenzt.

## Vor dem vollständigen Vergleich bestätigte reale Ergebnisse

| Prüfung | API33 | API35 | Art des Nachweises |
|---|---|---|---|
| Helper-Installation, Dienststart, Auth-/Host-Ablehnung | BESTANDEN | BESTANDEN | installierte APK/native API |
| SAF-Einstellungen exportieren, ändern, importieren | BESTANDEN | BESTANDEN | echte Dateiauswahl; Theme wiederhergestellt, Schlüssel ausgeschlossen und behalten |
| Acht Helper-Neustarts, Port und Schlüssel erhalten | BESTANDEN | BESTANDEN | echte force-stop/start-Zyklen |
| Debug-/zweite Test-App-Portkonflikt | BESTANDEN | BESTANDEN | zwei installierte Apps, tatsächlicher Socket |
| Native HLS-Datei speichern | BESTANDEN | BESTANDEN | ffprobe: Audio/Video und erwartete Dauer |
| Cookie-sicherer nativer Dateidownload | BESTANDEN | BESTANDEN | echte HTTP-Hops und SHA256 der gespeicherten Datei |
| Kadri installieren und über Connect koppeln | BESTANDEN | BESTANDEN | echte Firefox-/Addon-/Helper-UI und nativer Kopplungszustand |
| Violentmonkey + Userscript installieren und koppeln | BESTANDEN | BESTANDEN | echter Manager, eigene leere Profile |
| Übergabe, sichtbares bewegtes Video, Pause/Fortsetzen | BESTANDEN | BESTANDEN | beide Routen; echte Pixel, gerenderte Uhr und Play/Pause-Glyphen |
| Echte System-Lautstärke/Helligkeit über Kadri-Gesten | noch offen | BESTANDEN | Run40196780340: 0.4→0.7333 und 0.65→0.133 |
| Gesten über Userscript | noch offen | fehlgeschlagener früherer Ablauf | nach Filmende gestartet; isolierte Gegenprüfung erforderlich |
| Redirect-/HLS-Wiedergabe und Rotation, beide Routen | BESTANDEN | noch offen | Run40192612290, gerenderte Uhr; keine Audio-Hörprüfung |
| Eingebettetes Video | Userscript BESTANDEN, Kadri offen | noch offen | echter same-origin iframe; keine Aussage über fremde Frame-Isolation |
| Untertitel sichtbar | beide Routen visuell bestätigt | noch offen | API33-Screenshots enthalten ANDROID E2E SUBTITLE; alte OCR war falsch negativ |
| Download über echte Notification abbrechen | Kadri BESTANDEN | noch offen | echte Cancel-Aktion und nativer Jobzustand |

Relevante vollständige Vorläufe:
[40192612290](https://github.com/Owndot/video-assistent-helper/actions/runs/40192612290).
Fokussierter API35-Lauf:
[40196780340](https://github.com/Owndot/video-assistent-helper/actions/runs/40196780340).

## Testfehler, die getrennt vom Produkt korrigiert wurden

- Firefox Android lässt geschlossene Shadow-Elemente teilweise im AX-Baum aus.
  Tatsächliche Screenshots/Material-Glyphen und OCR ersetzen dort die fehlende
  Beobachtung; es wird weiterhin real getippt. Der Host prüft echte aufgezeichnete
  positive Beispiele und negative Bilder vor Emulatorstart.
- Androids Vollbild-Erstbenutzung, Firefox-Übersetzungsdialog und verspätete
  Benachrichtigungsfrage verdeckten Bedienelemente. Bekannte native Dialoge
  werden durch deren tatsächliche UI bestätigt.
- Die 320ms-Eingabesperre beim Einblenden sowie das spätere automatische
  Ausblenden wurden vom alten Treiber missachtet. Der Treiber zeigt die
  Bedienelemente frisch an und bestätigt die Wirkung von Schließen.
- double_click benötigt in uiautomator2-3.7.1 duration statt interval.
- Vollbild-OCR übersah den tatsächlich sichtbaren Untertitel. Die eigene
  16:9-Testquelle wird nun an der Untertitelposition mit Weißtextfilter gelesen;
  auf beiden unabhängigen Originalbildern geprüft, plus negativer Kontrolle.
- Mehrere Bedienprüfungen erbten denselben 60s-Clip und starteten am Filmende.
  Gesten und Spulen erhalten nun jeweils eine frische Quelle.
- Veraltete AX-Uhren sind kein Wiedergabenachweis. Uhrzeittests lesen gerenderte
  Footer-Pixel mit begrenzten Wiederholungen und bestehen nur bei Fortschritt
  beziehungsweise stabilem Pausenzustand.
- Frühere Hauptprozess-RAM-Dumps sind kein gesamter Firefox-RAM. Aktuell wird
  meminfo --package gespeichert, damit Content-/GPU-Prozesse einbezogen werden.
- Ein unvollständiger Job behält Teilberichte; fehlende Fälle gelten nie als
  bestanden.

## Messwerte und verbleibende Grenzen

Die acht frühen nativen Helper-Neustarts ergaben in einer API33-Route
31,755–31,984KiB PSS. Dies sind Beobachtungen desselben instrumentierten
Emulators, keine Samsung-Werte und kein Beweis gegen Speicherlecks.

Beobachtete Entry-to-controls-Zeiten enthalten UI-Automator-, Screenshot-/OCR-,
Polling- und teils Erstbenutzungsdialogkosten. Sie sind keine TTFF-Werte und
erlauben keine Aussage über eine Verschlechterung der Videostartgeschwindigkeit.
Ein stabiler alter Build wurde noch nicht unter identischen Bedingungen gegen
diesen Stand vermessen. Es gibt keine verlässliche Akku-, Peak-RAM-, CPU- oder
Lückenlosigkeits-Freigabe aus diesen ersten Daten.

Noch tatsächlich erforderlich: vollständige parallele UI-Downloads samt
überlappenden Server-Transferintervallen; sechs Öffnen/Schließen-Zyklen; zuverlässiger Quellenwechsel,
PiP/Hintergrund; Browsersteuerung nach Helper-Neustart und erneute Kopplung.
Die folgenden Ergebnisse des vollständigen Vergleichs entscheiden darüber.

Cross-origin/nested-iframe-Isolation, explizite Authorization-Weiterleitungen im Android-Browser,
Vivaldi, persistentes/signiertes Add-on nach Firefox-Prozesstod, echtes LAN,
ältere Android-UI-Versionen, Produktionssignaturmigration und reale Samsung-
Energie-/OEM-Prüfungen sind NICHT GETESTET. Die separate Migrationstestumgebung
mit Wegwerfschlüsseln ersetzt keinen Produktions-Update-Nachweis.

## Vollständiger Vergleich: noch nicht bestanden

Run40197766277 ist abgeschlossen. Er verwendete Automation c6d0cde und die
oben dokumentierten unveränderten Test-APKs.

| Route | API | Bestanden | Fehlgeschlagen | Blockiert | Nicht getestet |
|---|---:|---:|---:|---:|---:|
| Kadri | 33 | 17 | 12 | 0 | 6 |
| Userscript | 33 | 10 | 1 | 18 | 6 |
| Kadri | 35 | 16 | 12 | 1 | 6 |
| Userscript | 35 | 18 | 10 | 1 | 6 |

Dies sind getrennte Assertions, keine 140 unabhängig getesteten Funktionen.
Eine fehlgeschlagene Voraussetzung blockiert Folgefälle. Die Fail-Zahlen sind
nicht gleichbedeutend mit nachgewiesenen Produktfehlern.

Zusätzliche konkrete Bestätigungen aus diesem Lauf:
- Kadri/API33: Quellenwechsel und 134,217,728 Byte über die echte
  Helper-Downloadoption gespeichert. SHA256
  2d8f55f856e0ddc465ecb8dda33a2d3ab6d8838f33536b07a43baa0f688b5a13.
  11.439s einschließlich UI; fünf Helper-PSS-Proben:
  [43302,33643,33471,33475,35510]KiB. Kein Vergleichs-/Peak-Messwert.
- Kadri/API35: Redirect-Wiedergabe und eingebetteter Player bestanden.
- Userscript/API35: HLS, Untertitelauswahl samt gerenderter OCR-Bestätigung,
  Quellenwechsel und tatsächlicher Notification-Abbruch bestanden.
- Alle vier Präparationen: native HLS-/Cookie-Datei-/Auth-/SAF-/Restart-/
  Port-Prüfungen und Java/native/ANR-Crashkontrollen bestanden.
- Userscript/API33 scheiterte diesmal am sichtbaren Einstieg nach Installation;
  Folgefälle wurden blockiert. Eine vorher einmal erfolgreiche Installation
  ist kein Nachweis für Zuverlässigkeit aller nachfolgenden Läufe.

Kein vollständiger Nachweis für parallele Downloads, PiP-Rückkehr, sechs
Öffnen/Schließen-Zyklen, Spul-/Positionsübergabe und Browsersteuerung nach
Helper-Neustart/erneuter Kopplung. Einzelne ältere erfolgreiche Fälle werden
durch diesen Lauf nicht als dauerhafte Stabilität bestätigt.

## Nachschärfung und unabhängige Testtreiber-Regressionsnachweise

[40200226034](https://github.com/Owndot/video-assistent-helper/actions/runs/40200226034):
Die frühere normalisierte Formkorrelation akzeptierte auch auf 10% Helligkeit
reduzierte echte Play-/Close-Bilder. Der neue Matcher verlangt zusätzlich
sichtbare Helligkeit und Kontrast. Originale positive Bilder werden weiterhin
erkannt; der neue negative Test scheitert am tatsächlichen Matcher aus c6d0cde.
Dies ist eine nachgewiesene Treiberschwäche, keine pauschale Erklärung sämtlicher
UI-Fehler.

[40201238697](https://github.com/Owndot/video-assistent-helper/actions/runs/40201238697):
Der vorhandene TestMain verschluckte unerwartete Transportfehler. Ein neuer
Fehlerinjektionsfall scheitert am Original aus c6d0cde und besteht mit dem
korrigierten Test. Alle positiven DASH-/HLS-/Cookie-/Proxy-Fälle und die genau
erwarteten DRM-/Live-/403-Ablehnungen bestanden. ffmpeg decodierte alle
erzeugten erfolgreichen Medien mit -xerror. Das ist JVM-/Host-Abdeckung.
Ein absichtlich leerer Fehler-Testoutput wird vor der Medienprüfung entfernt;
die Fehlermeldung des alten Treibers im Nachweis ist ein erwarteter negativer
Test, kein Produktfehler.

[40200445981](https://github.com/Owndot/video-assistent-helper/actions/runs/40200445981):
Fokussierte echte API33-Wiederholung mit sichtbarem Kontrastfilter und
abgeschlossener Script-installed-Bestätigung. Kadri-Übergabe und Pause/Fortsetzen
bestanden; Gesten und Spulen weiterhin fehlgeschlagen. Userscript-Installation/
Kopplung bestanden, Übergabe noch instabil.

[40201365822](https://github.com/Owndot/video-assistent-helper/actions/runs/40201365822):
Die eigene Seite zeichnet native trusted Pointer-Metadaten auf, ohne Funktionen
des Players aufzurufen. Ein als 0.5s angeforderter Swipe dauerte real 2145ms.
Beim Doppeltipp lagen die UP-Dispatches 336ms auseinander, außerhalb des
vorhandenen 300ms-Playerfensters. Das ist ein konkreter ungültiger Testeingabefall.
Die Dauer des gesamten Swipes allein beweist noch nicht, dass dessen
14px-Bewegungsschwelle erst nach der 450ms-Langdruckgrenze erreicht wurde.

Die nächste Korrektur verringert die angeforderten Eingabedauern und prüft die
tatsächlich angekommenen Ereignisse: erste Bewegung über 14px vor 450ms,
Doppeltipp-UP-Abstand unter 300ms. Ungültige Eingaben bleiben FAIL mit
category=test_input; sie werden nicht als bestandene oder ununterstützte
Produktfunktion ausgegeben. Der neue fokussierte Lauf wird separat ergänzt.

## Fortsetzung nach der Remote-Unterbrechung

Die Remote-Branches wurden erneut mit GitHub abgeglichen. Es lag kein neuer,
fremder Arbeitsstand vor: Helper-Sicherheitsbranch 7122114, Kadri-Sicherheitsbranch
f76c209, Helper-E2E e3e5088 und Kadri-E2E 6a8acee. Die alten Emulatorläufe waren
abgeschlossen; kein unterbrochener Lauf wurde erneut gestartet. Seit dieser
Bestandsaufnahme wurden ausschließlich vorhandene Artefakte ausgewertet und
neue Testtreiber-Prüfungen ausgeführt. Erfolgreiche Emulator-/JVM-Suiten wurden
nicht nochmals gestartet. Die lokale Remote-Umgebung bleibt offline.

| Lauf | Scope | Ergebnis und Einordnung |
|---|---|---|
| [40201730932](https://github.com/Owndot/video-assistent-helper/actions/runs/40201730932) | API33, beide Routen, controls | Installation und Kopplung bestanden; Übergabe scheiterte vor den neuen Eingabeprüfungen. Diese Prüfungen wurden hier nicht ausgeführt. |
| [40202803284](https://github.com/Owndot/video-assistent-helper/actions/runs/40202803284) | API33, Kadri, controls | 8 PASS, 1 FAIL, 7 NOT_TESTED. Übergabe, Pause/Fortsetzen und reale Gesten bestanden. Spulen erreichte die Zielposition; Schließen/Positionsrückgabe blieb fehlgeschlagen. |
| [40203626744](https://github.com/Owndot/video-assistent-helper/actions/runs/40203626744) | API33, Kadri, controls | 7 PASS, 2 FAIL, 7 NOT_TESTED. Helligkeitsgeste war ungültige Testeingabe; Spulassertion las eine veraltete Uhr. Kein neuer Produktfehler nachgewiesen. |

Lauf25: tatsächliche Lautstärke 0.4→0.6, Helligkeit 0.65→0.153;
14px-Schwelle nach 242/263ms. Lauf26: linke Schwelle erst nach 868ms
(450ms-Langdruckgrenze); dies bleibt FAIL/category=test_input.
Der Doppeltipp in Lauf26 hatte einen gültigen UP/UP-Abstand von 220ms.

[40206876622](https://github.com/Owndot/video-assistent-helper/actions/runs/40206876622)
liest ausschließlich die bereits vorhandenen Aufnahmen, ohne Emulator oder
APK-Build. Im selben Video aus Lauf26 zeigt der eingebrannte Medienzeitstempel
bei Aufnahmesekunde20.53 etwa4s und bei21.03 etwa14s. Die Footer-Uhr zeigt
noch3s. Der tatsächliche Sprung ist sichtbar; die bisherige einzelne
Uhr-Abfrage nach0.6s kann diesen erfolgreichen Vorgang falsch ablehnen.
Daraus folgt keine Freigabe für Positionsrückgabe oder alle Spulfälle.

Der Testtreiber pausiert nun vor dem isolierten Spulvorgang, prüft die stabile
Ausgangsuhr und wartet begrenzt auf9–11s Positionsänderung. Er verlangt weiterhin
das sichtbare Play-Glyph des pausierten Players und speichert jede Uhr-Beobachtung.
Normales Weiterlaufen darf den Fall nicht bestehen lassen; ein nicht bestätigter
Sprung bleibt fehlgeschlagen. Das Produkt und seine Zeitsteuerung wurden dafür
nicht verändert. Neue Host-Regressionen decken verspätete/bis zum Timeout
fehlende Bestätigung, unbeabsichtigtes Fortsetzen, falsche Sprungweite und
Rundung ab. Dies sind Host-Tests der Beobachtungslogik, keine Browser-Simulation
und kein neuer Android-Nachweis. Der korrigierte pausierte Spulfall wurde noch
nicht auf einem Emulator ausgeführt.

[40207762825](https://github.com/Owndot/video-assistent-helper/actions/runs/40207762825)
prüfte ausschließlich die neue Beobachtungslogik aus Commit91284c6:
Python-Syntaxprüfung und sechs neue Host-Tests BESTANDEN. Zusätzlich wurde die
ursprüngliche Spulprüfung direkt aus e3e5088 geladen und mit einer zunächst
veralteten Uhr ausgeführt: Sie lehnt diese sofort ab. Die neue Beobachtung
akzeptiert eine verspätete Bestätigung, lehnt aber Timeout, unbeabsichtigtes
Weiterlaufen und falsche Sprungweite ab. Eingaben sind in diesem isolierten
Host-Nachweis ausdrücklich gestubbt. Kein Emulator, APK-Build, bereits
erfolgreicher JVM-/Gerätetest oder Release wurde dafür gestartet.

Die Ausführungskonfiguration verweist nicht mehr auf einen ablaufenden
APK-Artefaktlauf (reuse_bundle_run=0). Der schmale Scope API33/Kadri/controls
bleibt erhalten. Ein neuer Emulatorlauf wird dadurch nicht ausgelöst:
Nur der ausdrücklich verwendete Ausführungsbranch startet die Matrix.

Offene Fortsetzung: zuverlässige echte Eingabe für Helligkeit, Schließen und
Positionsrückgabe, parallele vollständige UI-Downloads, PiP/Hintergrund,
mehrfache Öffnen/Schließen-Zyklen, erneute Kopplung und Browsersteuerung nach
Helper-Neustart. Anschließend gezielter API35-/Userscript-Nachweis der geänderten
Testbedienung. Der bereits erfolgreiche 128MiB-Fall muss nicht zur reinen
Bestandsaufnahme wiederholt werden. Erst nach stabiler Bedienung ist ein
belastbarer Vergleich mit einer alten stabilen Version sinnvoll.

## Bewertung

Die reale Android-Testumgebung ist implementiert und hat zwei relevante
Produktfehler gefunden. Sie liefert reproduzierbare Teilnachweise und
auswertbare Fehlerartefakte. Die vollständige Browser-Funktionsmatrix ist
weiter rot. Deshalb sind die Sicherheits-PRs aus diesen Daten noch nicht für
einen abschließenden Merge freigegeben. Kein Merge, Release oder produktiver
Nachfolgeschlüssel wurde erzeugt.
