# Samsung S26 Ultra — kurzer Abschlusstest

Noch kein physischer Samsung-Test ausgeführt. Notiere Android-/One-UI- und
Browser-Version sowie PASS/FAIL pro Schritt.

## Vorbereitung

- [Helper-Testpaket herunterladen](https://github.com/Owndot/video-assistent-helper/actions/runs/40212461868/artifacts/12605463369), ZIP entpacken und **app-debug.apk** installieren.
  Separate App-ID de.evp.helfer.debug, Version1.0.188, zweitägiger Wegwerfschlüssel.
  Prüfsumme/Build-Commit/Testzertifikat stehen in den beigefügten debug-*.txt/sha256-Dateien.
- Bestehende Produktionsdaten behalten und laufende Downloads beenden.
  Den bisherigen Helper **vorher über „Stopp erzwingen“ beenden**: Beide Apps
  verwenden Port47811. Die Test-App erhält eigene Daten und eine neue Kopplung.
  Bei Signaturkonflikt mit einer älteren Debug-Test-App nur deren Testdaten sichern
  und nur diese Debug-App entfernen; nicht die Produktions-App löschen.
- [Kadri-Testpaket f76c209](https://github.com/Owndot/Kadri/actions/runs/40135017424/artifacts/12576498052)
  beziehungsweise [aktuelles Userscript](https://raw.githubusercontent.com/Owndot/video-assistent-helper/71221148dab8b9783451bb20f6f55310c303e7e7/userscript/video-assistent.user.js)
  über den tatsächlich unterstützten Browser-/Managerweg installieren.
  Jeweils nur Kadri ODER Userscript aktivieren. Unsigned Firefox-ZIP-Installation
  im normalen Android-Firefox ist nicht zugesichert; im Emulator wird web-ext
  verwendet. Deine normale Browserinstallation behalten.

## Sechs Prüfungen

1. Helper öffnen, Overlay-/Benachrichtigungsfreigaben erteilen, Browser koppeln.
   Lautstärkegeste muss den laufenden Test-Helper steuern.
2. Video bis20s spielen, Player öffnen, pausieren,10s vorspulen, fortsetzen,
   schließen und erneut öffnen. Dreimal: richtige Position, kein doppelter Ton.
3. Lautstärke/Helligkeit in beide Richtungen wischen und Quelle wechseln.
   HLS sowie einen eingebetteten Player kurz wiederholen.
4. Zwei eigene Dateien parallel laden, währenddessen ein weiteres Video öffnen.
   Einen Download abbrechen; der andere endet vollständig und ist abspielbar.
5. PiP/Home, App-Wechsel und Rückkehr: Wiedergabe und Position bleiben korrekt.
   Nicht angebotene PiP-/Erweiterungswege mit Browserversion als nicht verfügbar notieren.
6. Helper stoppen/neu starten und erneut über den Browser steuern.
   Nach dem Test Debug-Helper stoppen, dann bei Bedarf den bisherigen Helper starten.

Bei Fehlern genügt eine kurze Notiz oder Aufnahme ohne private URLs/Kopplungsschlüssel.
Akku und Samsung-Energiesparen danach im Alltag beobachten.
Keine produktive Migration, Schlüsselrotation oder Datenlöschung gehört zu diesem Test.
Technische Nachweise und offene Grenzen: [FINAL-VALIDATION-REPORT.md](FINAL-VALIDATION-REPORT.md).
