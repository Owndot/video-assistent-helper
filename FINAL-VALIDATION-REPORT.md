# Stabilisierung — abschließender technischer Stand

Stand: 25.10.2026. Maßgeblicher Abschlussbericht; ältere Berichte bleiben als
historische Evidenz erhalten. Keine produktive Veröffentlichung oder Merge-Freigabe.

## Aktuell laufende Validierung

[Android-Kernlauf40208810093](https://github.com/Owndot/video-assistent-helper/actions/runs/40208810093)
prüft API33 mit Kadri und echtem Violentmonkey/Userscript nacheinander.
Die korrigierte pausierte Spulprüfung läuft zuerst. Anschließend nur Übergabe,
Pause, Gesten, Quellenwechsel, parallele Downloads und PiP/Hintergrund.
Build,11 Fixture-Tests,6 neue Uhr-Regressionen und vollständige native
JVM-/Medienprüfungen sind bestanden; der Emulator läuft noch.
Der endgültige Android- und PR-Buildstatus wird nach Abschluss hier ergänzt.

## Geprüfter Code und Branch-Zuordnung

- Kadri PR#1: f76c209db9a3749007f4cd6a180d6d8509eb5971, unveränderte Runtime.
- Helper PR#1: Die E2E-Änderungen werden als ein nachvollziehbarer Commit auf
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
| Testtreiber-Uhrkorrektur | 6 Host-Regressionen PASS, [40207762825](https://github.com/Owndot/video-assistent-helper/actions/runs/40207762825); kein Android-Nachweis dieses korrigierten Ablaufs |
| Signaturmigration mit Wegwerfschlüsseln | PASS, [40130766838](https://github.com/Owndot/video-assistent-helper/actions/runs/40130766838), API26/28/31/32/33/35 |

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
