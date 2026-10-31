# Phase C3 – Helper-Seite der Browser-Anbindung für direkte MP4-Downloads

Stand: 31.10.2026 · Branch `feature/c3-browser-downloads` (Basis `feature/c2-mp4-downloads`, PR #26 unverändert).
**Produktionsstatus bleibt `UNAPPROVED`** (`release/production-policy.json`). Keine Releases, keine produktiven APKs,
kein Build oder Signieren von `de.evp.helfer`. Keine Sicherheitsfreigabe durch mich.

Die Browser-Seite (Kadri, Branch `feature/c3-helper-mp4`) nutzt ausschließlich die vorhandenen C2-Befehle
`dlstart`/`dlstatus`/`dlcancel` über den Noise-KK-Kanal. Am Protokoll, an `ControlCommands`, am Kanal, an der
Kryptografie, am Manifest und an den Berechtigungen ändert C3 nichts. Details zum Ablauf, zu Lebenszyklen und
Sicherheitsgrenzen: `DOWNLOADS-C3.md` im Kadri-Repository.

## Änderungen

1. **Fehlerbehebung in der App (gefunden durch Kadris C3-Browsertests):** Nach einem fertigen Download scheiterte der
   nächste an denselben Server mit `network`. Ursache: OkHttp nahm eine ruhende Pool-Verbindung, die der Server
   inzwischen geschlossen hatte (Keep-Alive-Zeitgrenze, HTTP/1.0-Server); weil `SafeHttpClient` bewusst nie
   automatisch wiederholt (`retryOnConnectionFailure(false)`, C1), wurde daraus ein Fehlschlag. Behebung:
   `SafeHttpClient.closeIdleConnections()` (schließt nur ruhende Verbindungen) und `DirectDownloads.run()` ruft es vor
   jedem Auftrag. Wiederverwendung innerhalb eines Auftrags, Weiterleitungen, alle Prüfschichten (Adressregeln, DNS,
   Socket-Guard, Netzwerk-Interceptor bei jeder Anfrage) bleiben unverändert. Neuer JVM-Test
   `staleIdleConnection` (ein Client für drei Aufträge, Server schließt nach jeder Antwort; ohne Behebung: Auftrag 2
   `failed:network`) und Mutationsprobe „stale idle connection reused“.
2. **Desktop-Harness, Download-Modus (nur Test, nie ausgeliefert):** `tests/java/channel/…/HarnessDownloads.java`
   betreibt die echte `DirectDownloads`-Engine mit echtem `SafeHttpClient` (Testregeln: einziges erlaubtes Ziel
   `127.0.0.1:<Medienport>`, alles andere gesperrt) hinter dem echten `ChannelServer`. Ersetzt ist nur die
   Android-Ablage (Ordner, Teil-Datei, Umbenennen, Journal daneben, Schalter für die Speicherberechtigung). Befehle:
   `downloads <port>`, `storage-permission on|off`, `dl-files`, `dl-parts`, `dl-journal`; der Modus überdauert einen
   Neustart des Prozesses (Datei `downloads.port` im Zustandsverzeichnis).
   - Die Harness-Ablage reserviert beim Anlegen einen freien Namen (wie `Mp4Downloads.PublicFile`); sonst hielt die
     Absturz-Wiederherstellung eine ältere gleichnamige Datei gleicher Größe für fertig.
   - Ein im Zeichensatz des Desktops nicht darstellbarer Dateiname ist ein Ablagefehler (`storage`), keine
     RuntimeException (die Engine meldete sonst `network`). Kadris Tests starten den Harness mit `LC_ALL=C.UTF-8`.
   - `tests/java/channel/build.sh` übersetzt dazu `net` (ohne `SafeHttp.java`), `dl` und `LocalTestClients` gegen die
     gepinnten JVM-Artefakte (okhttp-jvm 5.5.1, okio-jvm 3.18.1, kotlin-stdlib 2.2.21, SHA-256 wie
     `tests/java/net/run.sh`). Die nativen Bibliotheken des Harness sind unverändert (SHA-256 `c6b63848…`, `ed0152c1…`).

## Tests (lokal, Desktop-JVM 17)

- `tests/java/dl/run.sh`: 33/33 (neu: `staleIdleConnection`); `tests/java/dl/mutation.py`: 30/30 erkannt.
- `tests/java/net/run.sh`: 35/35; `tools/check-network-layer.sh`: OK.
- `tests/java/channel/run.sh` (ChannelChecks): 12/12.
- Kadri gegen diesen Commit: Download-Integration (Node) und `test_mp4.py` (Chromium), siehe Kadri `DOWNLOADS-C3.md`.

Emulator/Gerät: C3 ändert an der Android-Ablage nichts; deren Tests (Android 8–9 öffentlicher Ordner mit
READ/WRITE-Berechtigung, Android 10+ MediaStore, Absturz-Wiederherstellung) liefen in C2 auf Emulatoren API 26 und 34.
Die Behebung (1) läuft in der CI zusätzlich in den Emulator-Tests mit (`Mp4DownloadsInstrumentedTest`). Kein echtes
Gerät benutzt.
