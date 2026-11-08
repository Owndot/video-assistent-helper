# P4 – adressfreie Medienablage, nur isolierter Versuch

Stand 2026-11-08. **IN_PROGRESS**, Produktion **UNAPPROVED**. Keine neue App-Funktion,
kein Kanalbefehl, kein Netzwerk-Endpunkt, kein MediaStore-Aufruf, keine Geräte-APK ausgeliefert.
Die unveränderte bestehende CI baut intern kurzlebige Test-APKs für ihre Emulatorprüfungen;
keine neue manuelle APK-/Signier-/Veröffentlichungsaktion ausgelöst.
Maßgebliche Gesamtübergabe: [Kadri P4C3-HANDOFF.md](https://github.com/Owndot/Kadri/blob/feature/c3-helper-mp4/P4C3-HANDOFF.md).

## Nachgewiesen

**DONE – lokale Probe mit unverändertem App-Remuxer:**
`tests/java/hlsmux/de/evp/helfer/hlsmux/CapturedMediaInbox.java` ist ausschließlich Testcode.
Er nimmt numerischen Ressourcenindex/Offset und begrenzte Bytes an. Metadaten enthalten nur
Längen, SHA-256 und Laufzeit. Kein Feld für URLs, Dateinamen, Cookies, Header oder Browserdaten.
Private temporäre Dateien werden intern benannt; keine Veröffentlichungsfunktion vorhanden.

- Höchstens 2048 Ressourcen, 8 MiB pro Ressource, 64 MiB pro Versuch, 16 KiB pro Block.
  Diese kleinen Prototypgrenzen sind keine Freigabe für beliebige Filme oder parallele Aufträge.
- Reihenfolge, Offset, Längen und Hashes werden geprüft; abgeschlossene Ressourcen vor dem Remux
  erneut gelesen und gehasht. Kein ganzer Film im Speicher des Ablageprototyps.
- Vorhandener `Media3HlsRemuxer` unverändert: fMP4/H.264/AAC mit integrierter Tonspur → MP4;
  danach vorhandene Prüfung von Spuren, Samples, Zeitstempeln und Dauer (Testtoleranz 0,25 s).
  Unabhängiger vollständiger FFmpeg-Decode bestätigt Bild und Ton der bekannten 20-s-Fixture.
- Ablehnung von fehlenden Daten, falscher Reihenfolge/Offset, übergroßen Blöcken/Metadaten,
  abweichendem Ressourcenhash, nachträglicher Dateibeschädigung und HTML mit gültigem Hash.
- Abbruch verwirft private Daten; verspätete Blöcke bleiben gesperrt. Lokaler Abbruchmarker wird
  geschrieben und synchronisiert. Wiederöffnen erkennt den Marker. Andere unterbrochene Aufnahmen
  bleiben gesperrt und werden bereinigt; kein stillschweigender Neustart oder Resume-Fallback.
- Deterministische Nebenläufigkeitsproben: Abbruch vor Beginn des tatsächlichen Remux und nach
  erfolgreicher Medienprüfung, unmittelbar vor dem Zustandswechsel, verhindert `verified`.
  Keine Aussage über bereits veröffentlichte Dateien: der Versuch veröffentlicht nichts.

**DONE – erster Lauf 24/24** mit bestehenden synthetischen Fixture-Dateien.
**DONE – aktuell 26/26 lokale Prüfungen** mit den tatsächlich im Browser erfassten, adressfrei exportierten
Dateiblöcken aus dem Kadri-Test, einschließlich Metadatenbegrenzung vor dem Kopieren. Browser-Probe zuvor 51/51; Quellstand `0fa3bcbd8cad843d8cda9ea43aa78e2b5b989f4e`.
Keine Rohdiagnose, echte Medienseite oder Benutzersitzung verwendet.
**IN_PROGRESS – vollständige CI:** bestehende `HLS downloads (P4b)`-Pipeline führt den Test nach der bisherigen
Remuxer-Suite aus. Für Zwischenstand `2746b8f` sind Remuxer inklusive neuer Ablageprobe und
aller bisherigen Remuxer-Mutationen bereits grün:
https://github.com/Owndot/video-assistent-helper/actions/runs/42513744679.
Aktuelle Änderung begrenzt Länge und Hash vor der Metadatenkopie; neue CI nach dem Push nachtragen. Gepinnte Media3-1.11.1-Artefakte und bestehende Prüfsummen unverändert.

## Reproduktion

JDK 21, FFmpeg/FFprobe. `tests/java/hlsmux/run.sh` holt und prüft dieselben Media3-Artefakte wie bisher.
Nur die neue lokale Probe mit bereits vorhandenen Kadri-Fixtures:

```sh
HLSMUX_CAPTURE_ONLY=1 bash tests/java/hlsmux/run.sh ../Kadri/tests/web/public/p4 /tmp/hlsmux-deps
```

Mit tatsächlichen Browserdaten der **bekannten lokalen Fixture**, ohne reale Webseiten:

```sh
# Im Kadri-Repository; Ausgabeverzeichnis darf noch nicht existieren.
KADRI_CAPTURE_CHROMIUM=/usr/bin/chromium python3 tests/passive_player_capture.py --export-fixture /tmp/capture-fixture
# Danach im Helper-Repository:
HLSMUX_CAPTURE_ONLY=1 HLSMUX_CAPTURE_FIXTURE=/tmp/capture-fixture bash tests/java/hlsmux/run.sh ../Kadri/tests/web/public/p4 /tmp/hlsmux-deps
```

Der Browserexport ist ein lokales Testformat, **kein** freigegebenes Netzwerkprotokoll.
Ohne `HLSMUX_CAPTURE_ONLY=1` laufen unverändert auch alle bisherigen Remuxer-Prüfungen.

## Offene Grenzen und nächster Schritt

**BLOCKED – App-Integration:** keine Authentisierung/Bindung an Kanal, Auftrag, Tab oder Frame,
keine globale Speicherquote, keine Android-Crash-/Keystore-/Dateisystemprüfung, kein Resume,
keine MediaStore-Veröffentlichung. `recoverForTest()` simuliert Wiederöffnen auf der JVM;
es ist kein Prozesskill-/Stromverlustnachweis und kein Ersatz für den existierenden DownloadStore.
Der Marker synchronisiert seine Datei, nicht das Elternverzeichnis: Android-Dauerhaftigkeit bleibt ungeprüft.
Der Aufrufer muss später Abbruch bis einschließlich Veröffentlichung absichern; `verified` bedeutet
hier ausschließlich private, geprüfte Testausgabe. Ein SHA-256 des Senders beweist keine Quellautorisierung.

**TODO – nächster Schritt:** authentisierten, begrenzten Medientransfer samt Auftrag-/Absenderbindung
isoliert an den bestehenden Noise-Kanal anbinden und Negativfälle prüfen, bevor irgendein Endpunkt
oder UI-Modus aktiviert wird. Keine URLs/Browsercredentials im Medienpfad, keine beliebigen Helper-
Anfragen an den Browser, kein alter Proxy. Produktionsvalidatoren und Netzwerkarchitektur unverändert.
Passive Erfassung bleibt auf tatsächlich empfangene Wiedergabedaten beschränkt und ist noch kein
schneller vollständiger Download beliebiger Videos. Reale HTTP-404/412-Ursachen bleiben offen.

App-Anwendungscode weiterhin `8b906c5`, vorheriger Helper-HEAD `862c1d1`.
Samsung F1/F2/R1/R2/C1 bleiben PASS der bisherigen unabhängigen Basis. P4B-A10-SIZE bleibt rot.
Grüne CI ist keine unabhängige Sicherheitsfreigabe.
