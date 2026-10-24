# Samsung S26 Ultra — Android 17 — Vivaldi und Firefox

**Noch kein physischer Gerätetest ausgeführt.** Alle 84 Gerätezeilen in DEVICE-RESULTS.csv stehen auf NOT TESTED. Die grüne CI ersetzt diese Tests nicht.

## 1. Vorbereitung und sichere Test-APK
1. Notiere unter „Telefoninfo / Softwareinformationen“ Android- und One-UI-Version sowie die genauen Vivaldi-/Firefox-Versionen. Mit ADB zusätzlich: `adb shell getprop ro.build.version.sdk`.
2. Notiere Sprache, Thema und Autostart des vorhandenen Helpers. Stoppe laufende Downloads. Lösche keine Produktionsdaten.
3. Geprüfte Test-APK: [CI-Lauf 40131433569](https://github.com/Owndot/video-assistent-helper/actions/runs/40131433569), Quellstand `8561f7e7d2c5606d9593cae1ea67926d4e4fa2f1`, Artefakt [helper-debug-NOT-production](https://github.com/Owndot/video-assistent-helper/actions/runs/40131433569/artifacts/12575590883).
4. Im Archiv stehen app-debug.apk, debug-apk.sha256, debug-source-commit.txt und debug-signature.txt. Vergleiche die **APK-Dateiprüfsumme** mit dem Manifest. Die ZIP-Prüfsumme und der Zertifikatsfingerabdruck sind andere Werte.
5. Diese APK hat die separate App-ID `de.evp.helfer.debug` und einen Testschlüssel. Sie aktualisiert die Produktions-App `de.evp.helfer` nicht. Eine fehlgeschlagene Installation erlaubt nicht, die Produktions-App ohne Sicherung zu löschen.
6. Stoppe den vorhandenen Produktions-Helper während normaler Debug-Tests. Beide nutzen Port 47811; gleichzeitig laufen sie nur beim ausdrücklichen Konflikttest.
7. Nutze Testkonten und feste, erlaubte Quellen: direkte MP4/WebM, HLS, eingebetteter Player, SRT/VTT, eigener Medienserver und große Datei mit bekannter SHA-256-Prüfsumme. Keine echten Cookies oder Kopplungsschlüssel weitergeben.
8. Notiere Bildschirmhelligkeit/Bildrate, Lautstärke, WLAN, Energiesparmodus, Temperatur und Samsung-Hintergrundbeschränkungen. Ändere diese nicht stillschweigend zwischen Vergleichsläufen.

## 2. Browserwege getrennt testen
- A: Vivaldi + Kadri; das Player-Userscript deaktivieren.
- B: Vivaldi + Userscript über den tatsächlich unterstützten Manager; Kadri deaktivieren.
- C: Firefox + den tatsächlich verfügbaren Kadri- oder Userscript-Weg; nur einen Player aktivieren.

Prüfe zuerst, ob deine konkrete Android-Browserversion den jeweiligen Erweiterungs-/Managerweg unterstützt. Nicht verfügbare Wege bekommen NOT TESTED mit Begründung. Desktop-Erweiterungen nicht als auf dem Handy installiert voraussetzen.
Notiere den getesteten **Commit**; dieselbe Entwicklungs-Versionsnummer beweist nicht dieselben Dateiinhalte.

## 3. Funktionstests
Trage für jeden verfügbaren Browserweg eine eigene Zeile ein: PASS = tatsächlich bestanden, FAIL = Abweichung, NOT TESTED = nicht ausgeführt/nicht verfügbar. Verlinke Beobachtungen oder eine Aufnahme ohne Geheimnisse.

| ID | Schritte | Erwartung |
|---|---|---|
| F01 | Debug-APK installieren, App öffnen, erforderliche Benachrichtigungs-/Overlay-Freigaben erteilen. Helper zweimal stoppen/starten und zu einer anderen App wechseln. | Eindeutige Bereitschaft; kein Absturz. Startdauer und Freigaben notieren. |
| F02 | In der App „Verbinden“ wählen, gewünschten Browser öffnen, zum Video zurückkehren und Lautstärke wischen. Browser neu starten und wiederholen. | Nur der richtige laufende Helper reagiert; Kopplung bleibt nach Browser-Neustart erhalten. |
| F03 | Direktes Video bis ungefähr 20 Sekunden abspielen. Eigenen Player öffnen und schließen; fünfmal wiederholen. | Position und Ton bleiben korrekt; kein Neustart bei 0, doppelter Ton oder hängen gebliebenes Overlay. Sichtbare Lücke aufnehmen. |
| F04 | F03 mit HLS wiederholen, Qualität/Tonspur wechseln und vor-/zurückspulen. | Wiedergabe und Position bleiben korrekt; benötigte Segmente laden. |
| F05 | Dasselbe in einem eingebetteten Player testen, möglichst einmal gleiche und einmal andere Herkunft. Frame neu laden und Player erneut öffnen. | Richtige Quelle und erwartetes Vollbild. Browsereinschränkungen ausdrücklich notieren. |
| F06 | SRT und VTT sowie vorhandene Seiten-Untertitel wählen; Größe/Versatz ändern, spulen und erneut öffnen. | Text, Timing und gespeicherte Einstellungen stimmen; keine doppelten Untertitel. |
| F07 | PiP öffnen, Home drücken, sperren/entsperren, ins Vollbild zurückkehren und PiP schließen. Zurück-Taste separat testen. | Erwartete Wiedergabe, erhaltene Position, eine Tonquelle und keine klemmenden Bedienelemente. |
| F08 | Vom eigenen Medienserver abspielen; zwei Downloads starten und währenddessen ein weiteres Video auflösen/öffnen. Einen Download abbrechen. | Player bedienbar; anderer Download endet korrekt mit passender Dateiprüfsumme. |
| F09 | Systemlautstärke/-helligkeit notieren. Beide Wischregler und Auto/manuell prüfen. Player normal schließen; separat Browser zwangsweise stoppen. | Richtige Regler; Helligkeit wird auch im Wiederherstellungspfad zurückgesetzt. Tatsächliche Dauer notieren. |
| F10 | Hoch-/Querformat, Rotationssperre, App-Wechsel und Rückkehr testen. | Richtiges Bildformat, Position und Ton; kein ungewolltes Schließen. |
| F11 | Sprache/Thema/Autostart ändern und über den System-Dateidialog exportieren. Werte ändern, importieren und App neu starten. Auch defekte bzw. über 8 KiB große Datei versuchen. | Drei Einstellungen werden korrekt übertragen; ungültige Dateien ändern nichts teilweise. Export enthält keinen Kopplungsschlüssel/paired-Status. |
| F12 | Mit vorhandener vertrauenswürdiger Produktions-App: zuerst Produktion, dann Debug starten; Reihenfolge umkehren. Besitzer stoppen, andere App starten und ausdrücklich neu koppeln. | Nur Portbesitzer wird bereit. Andere App meldet verständlichen Konflikt und öffnet keine Kopplungsseite. Alter Browserschlüssel steuert die neue Installation nicht. |
| F13 | Unterstützte Debug-Einstellungen sichern. Nur Debug-App entfernen, testweise neu signierte Debug-Variante installieren, importieren und neu koppeln. | Kein übernommener privater Schlüssel; Einstellungen stimmen; Kopplung und Android-Freigaben werden erneuert. |
| F14 | Geeignete erlaubte Quelle mit Spiegelung verwenden; fünfmal öffnen/schließen, soweit unterstützt spulen und App wechseln. | Richtiges Bild, nur eine Tonquelle und erhaltene Seitenposition. Ohne geeignete Quelle NOT TESTED. |
| F15 | Wenn vorhanden, an unterstützten externen Player/Cast-Empfänger übergeben, zurückkehren und an notierter Position erneut öffnen. | Richtige Quelle/Position; kein ungewollter zweiter Stream. Empfänger/App notieren. |

F12 erfordert keine Installation einer unbekannten Produktions-APK. Fehlt eine bereits vertrauenswürdige zweite App, diesen Teil nicht ausführen.
Die alte veröffentlichte App hat noch keinen Exporter: ihre drei Helper-Einstellungen manuell notieren. Browser-/Userscript-Einstellungen bleiben davon getrennt. Kompromittierte Kopplungsdaten werden nicht exportiert.

## 4. Vergleich und Performance
Für den Quellvergleich nutze die beiden **mit Testschlüsseln neu gebauten Debug-APKs** aus [Vergleichslauf 40131293057](https://github.com/Owndot/video-assistent-helper/actions/runs/40131293057). Nutze hierfür nicht die alte produktiv signierte APK. Artefakte haben begrenzte Aufbewahrung; bei Ablauf kann der schreibgeschützte Vergleichsworkflow erneut laufen.
Bei sauberen Installationsvergleichen nur `de.evp.helfer.debug` entfernen. Produktionsdaten unangetastet lassen.

Zuerst F01-F05/F08/F09/F12 als kurzen Funktionstest durchführen. Bei Fehlern Beobachtung festhalten, bevor lange Akkuvergleiche beginnen.
Danach mindestens drei Blöcke stabil/aktuell/aktuell/stabil unter gleichen Bedingungen durchführen. Zwischen Läufen abkühlen lassen; Kaltstart und warme Wiederholungen getrennt halten.

| ID | Messung |
|---|---|
| P01 | Vom Start-Tippen bis zum ersten **sichtbaren** Videobild messen, möglichst mit Kamera/Zeitcodeaufnahme. Browser-Frame-Callback ist nur ein ergänzender Messwert. |
| P02 | Tippen → eigener sichtbarer Player und Schließen → sichtbare Seite aufnehmen. Position und hörbare Lücken notieren. |
| P03 | URL-Auflösung ohne Last, mit zwei Downloads, verzögerten Antwort-Headern und gedrosseltem Server vergleichen. Warteschlange/Header/Body sowie Fallback getrennt erfassen. Ein schneller Fallback ist keine erfolgreiche Auflösung. |
| P04 | Browser und Helper getrennt messen: PSS und CPU vor Wiedergabe, nach 5/15/30 Minuten und während Downloads. Gleiche Messfenster und Profilierungsverfahren verwenden. |
| P05 | Ohne Ladekabel vergleichbare Sitzungen von mindestens 30 Minuten durchführen; Helligkeit/Bildrate, WLAN, Start-Ladestand und Temperatur angleichen. Hintergrundaktivität notieren. Emulatorwerte sind keine Akkuwerte. |
| P06 | Eigene Multi-GB-Testdatei mit bekannter Prüfsumme laden; langsamen Server, Range/Fortsetzung soweit unterstützt und Abbruch prüfen. Freien Speicher, Ergebnisdatei und PSS-Verlauf erfassen. Vorhandene Blob-Pfade puffern weiterhin vollständige Daten. |
| P07 | Player 20-/50-mal öffnen/schließen; Position, Ton, Overlays und Speicher nach Beruhigung vergleichen. Steigender kurzzeitiger Speicher allein beweist noch kein dauerhaftes Leck. |

Rohwerte, Median und Streuung zuerst erfassen. Es gibt keine willkürlich gesetzte Latenz-/Akku-Grenze. Desktop-/Emulatorzeiten nicht direkt mit Samsung-Zeiten vergleichen. Bildschirmaufnahme verursacht zusätzliche Last und muss in beiden Versionen gleich eingesetzt werden.

Optional mit eigenem ADB-Rechner:
`tools/collect-device-snapshot.sh results/vivaldi-before com.vivaldi.browser`
Danach gleiche Last ausführen und mit anderem Ausgabeordner wiederholen. Für Firefox die tatsächlich installierte Paket-ID prüfen, meist `org.mozilla.firefox`.
Der Sammler liest nur: keine Installation, kein Datenlöschen, kein Zurücksetzen der Akkustatistik und kein Lesen der Kopplungspräferenzen. Dateien vor Weitergabe lokal prüfen; Akkustatistiken können andere Gerätenutzung enthalten.
Ohne ADB CPU/PSS als NOT TESTED markieren; die allgemeine Samsung-Speicheranzeige ersetzt keine Prozess-PSS-Messung.

## 5. Sicherheits- und Migrationszeilen
- S01: Auf kontrollierten Testservern mit künstlichen Cookies/Authorization prüfen, was nach Weiterleitungen tatsächlich ankommt.
- S02: Gleichzeitige lokale/öffentliche Anfragen und unerlaubte LAN-Weiterleitungen prüfen.
- S03: Tatsächlichen Erweiterungs-Worker während Setup, Header-Warten, Redirect und Body beenden/neustarten; temporäre Freigaben und weitere Anfragen prüfen.
- S04: In Warteschlange, Header-Phase und Body abbrechen; Timeout prüfen; weitere Anfragen müssen sicher weiterarbeiten.
Fehlen dafür Serverprotokolle oder Browser-Werkzeuge, NOT TESTED mit Grund eintragen. CI füllt diese physischen Zeilen nicht automatisch.

### M01: Vorhandene Produktions-App unabhängig prüfen — nur lesend
1. `adb shell pm path de.evp.helfer` ausführen.
2. Den ausgegebenen base.apk-Pfad ohne führendes `package:` kopieren.
3. `adb pull KOPIERTER_BASE_APK_PFAD trusted-installed-helper.apk` ausführen.
4. Mit SDK `apksigner verify --verbose --print-certs trusted-installed-helper.apk` prüfen.
5. **Zertifikat-SHA-256** mit `ec17fab1a92079911ea50e6d308f8c70ad79efc3bb48e0231f2559b26b7e42e5` vergleichen; APK-Dateiprüfsumme zusätzlich getrennt notieren.

Ein Treffer bestätigt die alte Signieridentität und macht den exponierten Schlüssel nicht sicher. Bei abweichendem Zertifikat Migrationsannahmen stoppen und Nachweis behalten. Keine privaten App-Präferenzen auslesen oder teilen.

### M02: Produktiver Nachfolger — weiterhin NICHT TESTEN
Es gibt keinen freigegebenen Nachfolgeschlüssel und keine produktive Lineage. Keine Schlüssel erzeugen, rotieren oder Fixture-APK über die Produktions-App installieren.
Erst ein separat genehmigter Nachfolger kann als reales Produktionsupdate mit Einstellungen, Freigaben und neuer Kopplung getestet werden. Die grünen Testschlüssel-Emulatorläufe ersetzen dies nicht.

Android 8-12L braucht zusätzliche repräsentative Geräte für den erlaubten Neuinstallationsweg. Ein Erfolg auf Android 17 deckt sie nicht ab. Samsung-Hintergrunddienst, PiP, Lautstärke-/Helligkeitsrücksetzung und Akku bleiben bis zur tatsächlichen Durchführung ungeprüft.

**Nichts mergen oder veröffentlichen.** Vollständige technische Nachweise stehen in FINAL-VALIDATION-REPORT.md.
