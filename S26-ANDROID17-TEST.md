# Samsung S26 Ultra — kurzer Abschlusstest

Stand: 25.10.2026. Android/One UI sowie Vivaldi-/Firefox-Version am Gerät notieren.
Es wurde bisher kein physischer Samsung-Test ausgeführt.

## Testdateien und Vorbereitung

- Helper: [Testpaket aus dem Android-Kernlauf](https://github.com/Owndot/video-assistent-helper/actions/runs/40208810093/artifacts/12604838132).
  Im ZIP ausschließlich **helper-debug.apk** installieren. **firefox.apk ist x86_64
  für den Emulator und darf nicht auf dem Samsung installiert werden.**
  helper-competing.apk ist nur eine zweite Emulator-App für den Porttest.
- APK-Quellstand c81bac088cbca74c534ad8f6fb76deed7237e0f0; der Kernlauf prüft
  vor Wiederverwendung die Gleichheit aller Android-App-/Build-Eingaben mit
  b3719924cc7a227461ff3ca7696b8de9cb314847 sowie die APK-Dateihashes.
  app/build.gradle und der gesamte Android-App-Baum sind identisch.
  apk-provenance.txt enthält die Dateiprüfsummen, signature-provenance.txt den
  öffentlichen Testzertifikatsnachweis. Das ist kein Produktionsschlüssel.
- Kadri: [geprüfte Chrome-/Firefox-ZIPs, f76c209](https://github.com/Owndot/Kadri/actions/runs/40135017424/artifacts/12576498052).
  Nur den tatsächlich unterstützten Erweiterungs-Installationsweg verwenden;
  die ZIP ist keine APK. Firefox aus deinem normalen Browserkanal behalten.
- Userscript: [geprüfter aktueller Quellstand](https://raw.githubusercontent.com/Owndot/video-assistent-helper/71221148dab8b9783451bb20f6f55310c303e7e7/userscript/video-assistent.user.js).
  Browser-Routen nacheinander testen: Kadri ODER Userscript aktivieren.
- Laufende Downloads beenden. Vorhandenen Produktions-Helper über Android
  **Stopp erzwingen** beenden, seine Daten behalten. Debug und Release verwenden
  denselben Port47811 und dürfen für normale Tests nicht zugleich laufen.
  Die Debug-App de.evp.helfer.debug erhält eigene Daten und eine neue Kopplung.
- Das Paket ist kurzzeitig als CI-Artefakt verfügbar, ohne Release.
  Der private Testschlüssel wurde nach dem Build gelöscht. Zertifikatsablauf und
  Artefaktlöschung widerrufen eine bereits heruntergeladene APK nicht.

## Sechs praktische Prüfungen

1. **Start/Kopplung:** Debug-Helper öffnen, benötigte Overlay-/Benachrichtigungs-
   Freigaben erteilen, mit dem gewünschten Browser verbinden. Lautstärke wischen:
   Nur der laufende Debug-Helper soll reagieren.
2. **Öffnen/Schließen/Position:** Ein Video bis etwa20s spielen, eigenen Player
   öffnen, pausieren, zehn Sekunden vorspulen, fortsetzen und schließen.
   Seite spielt an derselben Position weiter; erneut öffnen. Dreimal wiederholen.
   Kein doppelter Ton, Rücksprung oder hängen gebliebenes Overlay.
3. **Gesten/Quellen:** Lautstärke und Helligkeit in beide Richtungen wischen,
   Quelle wechseln; HLS und einen eingebetteten Player kurz wiederholen.
   Richtige Quelle, bedienbarer Player und keine auffällige Übergabelücke.
4. **Downloads:** Zwei eigene Dateien über den Helper gleichzeitig laden.
   Währenddessen ein weiteres Video öffnen; einen Download abbrechen.
   Der andere endet vollständig und ist abspielbar.
5. **PiP/Hintergrund:** PiP/Home, App-Wechsel und Rückkehr testen.
   Wiedergabe und Position bleiben korrekt; ein fehlender PiP-Weg der konkreten
   Browserversion wird als „nicht unterstützt“ mit Versionsangabe notiert.
6. **Wiederanlauf:** Helper stoppen und erneut starten, Browser zurückholen.
   Verbindung und Gesten erneut prüfen. Anschließend Debug-Helper stoppen und
   bei Bedarf den bisherigen Produktions-Helper starten und neu koppeln.

Pro Schritt genügt PASS / FAIL / nicht verfügbar plus Browser und kurzer Notiz.
Bei Fehlern eine kurze Aufnahme ohne persönliche URLs/Cookies/Kopplungsschlüssel.
Echte Akkulaufzeit, Samsung-Energiesparen und Alltagserfahrungen prüfst du danach
bei normaler Nutzung. Keine produktive Migration/Installation über die alte App
und kein Löschen ihrer Daten sind für diesen Test erforderlich.
