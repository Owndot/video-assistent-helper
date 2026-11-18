# P4a – HLS→MP4: Machbarkeit und Sicherheitsdesign (isolierter PoC)

Nicht Teil des Helper-Builds, kein Produktionscode, keine Geräte-APK. Ergebnisse und Entscheidung: `P4A-REPORT.md`.

| Pfad | Inhalt |
|---|---|
| `src/main/java/de/evp/helfer/hls/` | `HlsPlaylist` (strenger Parser, erstes Profil), `HlsUri` (RFC-3986-Auflösung gegen die finale Adresse), `HlsPlan` (Optionen ohne Adressen, Budgets, Fingerabdruck, DASH nur erkennen) – reines Java |
| `src/jvm/java/de/evp/helfer/hls/HlsFetch.java` | jeder Abruf einzeln über den bestehenden `SafeHttpClient` (nur JVM-Test, auf dem Gerät nicht nötig) |
| `jvm-test/` | JVM-Tests inkl. echter Abrufe gegen einen lokalen Server; `tools/mutation.py` Mutationsproben |
| `android/` | Test-App `de.evp.p4a` (nur Wegwerf-Emulator): Media3-1.11.1-Weg (TS/fMP4-Extractor → Mp4Muxer/FragmentedMp4Muxer), Plattform-Weg (MediaExtractor → MediaMuxer), Gerätedekodierung, Abbruch, Speicher, Parservergleich |
| `tools/make-fixtures.sh` | eigene synthetische HLS-Fixtures (Blitz + Piepton alle 2 s für A/V-Synchronität) |
| `tools/analyze.py`, `tools/evaluate.py` | Prüfung jeder Ausgabe gegen ihre HLS-Quelle (Dauer, Bilder, Spuren, Synchronität, Dekodierung) |
| `.github/workflows/p4a-hls-poc.yml` | JVM-Tests, Mutationsproben, Emulatoren API 26/29/34, nur Textbericht |

Lokal: `bash tools/make-fixtures.sh /tmp/fx && bash tools/run-jvm.sh /tmp/fx && python3 -I tools/mutation.py /tmp/fx`.
