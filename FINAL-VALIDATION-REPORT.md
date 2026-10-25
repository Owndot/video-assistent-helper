# Stabilisierung — abschließender technischer Stand

Stand: 25.10.2026. Maßgeblicher Abschlussbericht; ältere Berichte bleiben als
historische Evidenz erhalten. Keine produktive Veröffentlichung oder Merge-Freigabe.

## Letzte Validierung

[Android-Kernlauf40208810093](https://github.com/Owndot/video-assistent-helper/actions/runs/40208810093):
API33, Kadri und echtes Violentmonkey/Userscript nacheinander, korrigierter
pausierter Spultest zuerst. Nur Kernfunktionen, keine vollständige Matrix.
Build,11 Fixture-Tests,6 Uhr-Regressionen und vollständige JVM-/Medientests PASS.

**Teilabschluss; der Workflow insgesamt bleibt FAIL/Timeout.**
Der letzte echte API33/Kadri-Durchlauf bestätigt:
- PASS: Helper-Start, echter Firefox, unveränderte Kadri-Installation und Kopplung.
- PASS: pausiertes Spulen9→19s, Schließen und Originalposition19.024687s.
- PASS: bewegtes Videobild, Player-Übergabe, Pause/Fortsetzen.
- PASS: Lautstärke0.4→0.6667 und Helligkeit0.65→0.246 durch echte Gesten;
  Bewegungsschwellen203/432ms, gültige Testeingaben.
- PASS: tatsächlicher Quellenwechsel und Schließen/Wiederöffnen.
Neun Fälle sind vollständig als PASS gespeichert.

Die zusammengesetzte Parallelprüfung ist formal FAIL, aber ihr Fehler liegt
bei der Abschlussbeobachtung. Beide verschiedenen gespeicherten Dateihashes
wurden vor dieser Assertion erfolgreich geprüft. Die späteren vorhandenen
Ereignisschnappschüsse zeigen zwei vollständige16,777,216-Byte-Transfers:
- erste Datei: Start492.240599421s, Ende543.845149007s;
- zweite Datei: Start511.598961759s, Ende563.171625871s;
- echte Überlappung32.246187248s; Redirect-Seite währenddessen bei520.464059914s
  geöffnet, anschließender eigener Player im Screenshot/Video sichtbar.
[Artefaktkorrelation40212227757](https://github.com/Owndot/video-assistent-helper/actions/runs/40212227757)
liest nur diese vorhandenen Aufzeichnungen; kein weiterer Emulator.
Der Test wartete nach früh sichtbaren Dateien nur5s auf die Server-Endereignisse,
obwohl die absichtlich langsamen Fixtures etwa52s benötigen.
DownloadManager kann die Null-Padding-Dateien bereits vor Ende vorallokieren.
Korrektur ausschließlich im Test: vollständige Transfer-Endereignisse bis60s
abwarten, erst danach gespeicherte Dateien prüfen. Dieser korrigierte Testablauf
wurde nicht nochmals im Emulator ausgeführt. Der alte rote Gesamtfall wird
nicht rückwirkend als bestandener vollständiger Test umetikettiert.

PiP/Home-Rückkehr blieb unvollständig. Bei15:59:00 meldet der Emulator
„bad color buffer handle1133“; UI-Automator-Aufrufe kehrten minutenlang nicht
zurück. Der nominelle15s-Poll-Timeout begrenzt blockierende Unteraufrufe nicht.
Die Aktion erreichte ihr35-Minuten-Limit. Browser-/Emulator-/Grafikursache
ist nicht abschließend getrennt; ein neuer Helper-Produktfehler ist damit nicht
nachgewiesen. Kein weiterer Architektur-/Automatisierungsumbau wird dafür begonnen.
Der Userscript-Teil dieses Kernlaufs wurde nicht mehr gestartet; ebenso fehlt
die abschließende Crashkontrolle dieses Laufs. Frühere API33/35-Ergebnisse bleiben
Einzelbelege, ersetzen diese fehlenden Abschlussprüfungen jedoch nicht.


[Helper-PR-Build40209479380](https://github.com/Owndot/video-assistent-helper/actions/runs/40209479380):
PASS für10 Sicherheitsfälle, JS-Cookie-Grenzen, JVM-Port-/Cookie-/Redirect-/
Settings-/Medientests, Gradle-Debug- und Wegwerfschlüssel-Release-Verifikation,
apksigner und Ablehnung der kompromittierten Produktionsidentität.
Das Release-Testprodukt wird nicht als installierbare APK hochgeladen.
Der veröffentlichte alte APK-Zertifikatsnachweis wird nur lesend geprüft.
Neue installierbare Test-APK: separate ID de.evp.helfer.debug, Version1.0.187,
[Artefakt12605157276](https://github.com/Owndot/video-assistent-helper/actions/runs/40209479380/artifacts/12605157276).
Testzertifikat-SHA256:
3ff851e422f6a95098211a12522835cd108e313f49b8ce35e488a873cb567beb.

## Geprüfter Code und Branch-Zuordnung

- Kadri PR#1: f76c209db9a3749007f4cd6a180d6d8509eb5971, unveränderte Runtime.
- Helper PR#1: Die E2E-Änderungen wurden mit be95a762ce13dfe3b7908c41bfd21e695a113e20 als ein Commit auf
  codex/security-phase-1 übernommen; kein PR oder Default-Branch wird gemergt.
  Der vollständige Android-App-Baum hat dieselbe Git-ID
  9f3cbd4c0d1d65c76007fb805f41ae3951e28af8 wie die tatsächlich installierten APKs.
- Android-Testautomation b3719924cc7a227461ff3ca7696b8de9cb314847,
  unveränderte Android-App-/Build-Eingaben gegenüber der wiederverwendeten
  APK aus c81bac088cbca74c534ad8f6fb76deed7237e0f0. Der Workflow erzwingt diesen
  Vergleich und prüft die APK-Dateihashes.
- src/, userscript/ und Player-Architektur wurden für diesen Abschluss nicht
  verändert. Die nachgewiesenen Chrome-/Cookie-/Signatur-/Portkorrekturen bleiben.
- Die tatsächlichen E2E-Produktkorrekturen sind begrenzt auf erlaubte HTTP-
  Medienquellen und Cookie-tragende native Dateien über den vorhandenen sicheren
  Streaming-Client. Details und ältere API33/35-Belege:
  [tests/android_e2e/FINDINGS.md](tests/android_e2e/FINDINGS.md).

## Erfolgreiche vorhandene Nachweise

| Prüfung | Ergebnis/Evidenz |
|---|---|
| Kadri-Build, native/Chrome-DNR-Regressionen und echtes Chromium | PASS, [40135017424](https://github.com/Owndot/Kadri/actions/runs/40135017424) |
| Gesamte Chromium-Userscript-Suite | PASS, [40136368628](https://github.com/Owndot/video-assistent-helper/actions/runs/40136368628), Job120998632283; Player-Runtime seither unverändert |
| Quellenposition, Übergabe und Player-Steuerung | PASS, [40134814582](https://github.com/Owndot/video-assistent-helper/actions/runs/40134814582); alter stabiler Code scheitert am expliziten Quellenpositionsfall, korrigierter Code besteht |
| Native APK-Funktionen auf API33/35 | HLS, Cookie-Redirect-Dateihash, Auth/Host, SAF, Neustarts und Portkonflikt bestanden; Links/Einzelergebnisse im E2E-Bericht |
| Reale128MiB-Datei über Browser-UI | PASS, API33/Kadri, [40197766277](https://github.com/Owndot/video-assistent-helper/actions/runs/40197766277); gespeicherter Hash geprüft |
| Testtreiber-Uhrkorrektur | 6 Host-Regressionen PASS, [40207762825](https://github.com/Owndot/video-assistent-helper/actions/runs/40207762825); zusätzlich jetzt echter API33-Spul-/Schließ-/Positionsnachweis im Kernlauf oben |
| Signaturmigration mit Wegwerfschlüsseln | PASS, [40130766838](https://github.com/Owndot/video-assistent-helper/actions/runs/40130766838), API26/28/31/32/33/35 |

Ein direkter Git-Blob-Vergleich mit dem verfügbaren stabilen Stand b05fd2de
bestätigt unveränderte Dateien130-player-aufbau,160-player-anzeige,
180-player-bedienung,240-player-gesten und260-player-schliessen.
Der spätere Quellenpositions-Fix in170 bleibt durch den expliziten
Alt/Neu-Regressionstest begründet; an Gesten/Anzeige/Schließen wurde für
diese Stabilisierung nichts geändert.

Die acht alten Firefox-Dateien bleiben FAIL. Sie scheitern auch mit dem
unveränderten älteren Skript b05fd2de, dessen Hash mit6111df1a identisch ist:
[40130166285](https://github.com/Owndot/video-assistent-helper/actions/runs/40130166285).
Dies ist kein Beleg für neu eingeführte Fehler und wird jetzt nicht zu einer
weiteren Umbau-/Testbaustelle. Der verfügbare gepinnte stabile Stand ist damit
verglichen; eine nicht identifizierte „Claude-Originalversion“ wird nicht behauptet.

## Begrenzte Restpunkte und Sicherheitsgrenzen

1. Automatisierte Android-Bedienung war bei Close, Gesten und PiP mehrfach
   zeitabhängig. Einzelne rote Fälle gelten weder automatisch als Produktfehler
   noch als bestanden. Es gibt keine neue Player-Architektur, um sie grün zu machen.
2. Cookie-geschützte native Dateidownloads sind an Helper-/Foreground-Service-
   Lebensdauer gebunden. Nach hartem Prozessende ist dauerhafte Wiederaufnahme
   nicht garantiert. Unauthentifizierte Dateien bleiben bei DownloadManager.
3. Keine Samsung-/Vivaldi-, physische Akku-, Spitzen-RAM- oder vollständige
   Android17-/OEM-Freigabe. Vorhandene Emulator-Messwerte sind keine solchen Belege.
4. Produktionssignatur bleibt kompromittiert, historische öffentliche Kopien
   bestehen. Main-Hotfix#2 ist bereits enthalten; Produktion bleibt gesperrt.
   API33+ v3.1 und Neuinstallation für API26–32 sind mit Testidentitäten vorbereitet.
   Kein Nachfolgeschlüssel und keine produktive Lineage wurde erzeugt.
5. Chrome-DNS-Rebinding bleibt die dokumentierte Browsergrenze. Tests verwenden
   ausschließlich eigene Medien und Wegwerf-Kopplungsdaten.

## CI und eigener Gerätetest

Keine neue vollständige Emulator-Matrix. Optionale Helper-Performancemessungen
werden nur manuell gestartet. Native Java-Änderungen lösen die native PR-Prüfung
aus; die unveränderte vollständige Browser-Suite wird dafür nicht automatisch
nochmals auf beiden Desktop-Engines gestartet.
Reguläre PR-Validierung bleibt read-only, mit kurzlebigen Testschlüsseln.
Ein Testlauf kann keine Release-APKs oder Tags veröffentlichen.

Installierbare APK und kurze6-Punkte-Anleitung:
[S26-ANDROID17-TEST.md](S26-ANDROID17-TEST.md).
Kein bestehender Produktionsdatenbestand wird dadurch überschrieben.
Echte Samsung-Ergebnisse sind vor einer persönlichen Merge-Freigabe noch offen.

## Abschlussentscheidung und Commits

Für einen kontrollierten Samsung-Test mit separater Debug-App ausreichend
vorbereitet. Keine vollständig grüne Android-Abschlussmatrix und keine
pauschale Freigabe für alle Browser/OEMs. Keine neue kritische Regression
nachgewiesen; die genannten Grenzen bleiben ausdrücklich offen.

Empfehlung: Kadri PR#1 und Helper PR#1 nach den sechs Samsung-Kernprüfungen und
ausdrücklicher Eigentümerfreigabe mergen, sofern dort kein kritischer Fehler
auftritt. Besonders PiP/Hintergrund sowie gleichzeitige Downloads und ein
neues Video prüfen. Die acht alten Firefox-Harnessabweichungen allein sind
kein Anlass für einen Player-Umbau. Produktive Veröffentlichung/Migration
bleibt eine separate spätere Freigabe; neuer Schlüssel und Lineage fehlen
absichtlich noch. Kein Merge oder Release wurde ausgeführt.

- b371992: begrenzter E2E-Kernscope, korrigierter Spultest zuerst.
- be95a76: getestete E2E-/Android-Korrekturen in bestehendem Helper-PR gesammelt,
  native App-Baum-ID9f3cbd4 unverändert; optionale Emulatorläufe manuell,
  native Änderungen starten keine vollständige Desktop-Browserwiederholung.
- Abschließende Test-/Dokumentationskorrektur: Transferabschluss vor Dateiprüfung,
  vorhandene Streaming-/Uhr-Regressionen in regulärer PR-CI, aktuelle Test-APK
  und kurze Samsung-Anleitung. Keine zusätzliche Runtime-Änderung.
Die beiden temporären Artefaktleser-Commits42b21a2/2836223 liegen ausschließlich
auf dem separaten Diagnosebranch, nicht auf dem Merge-Branch.
