# Helfer-Kanal – Integrationsstufe 1: Helfer-Seite

**Nur Testwerkzeuge, keine Änderung der produktiven Helper-App (`app/`).** Branch `feature/helper-channel-dummy`, basierend auf dem auf dem S26 Ultra getesteten Stand `bf84258`. Der Rust-Kern (`poc/pake-channel/core`) ist unverändert. `release/production-policy.json` bleibt `UNAPPROVED`; Issue #3 bleibt Release-Blocker, Issue #4 offen.

Die Kadri-Seite (isolierter Test-Build, Architektur, Tests, Restrisiken, Review-Schritte) beschreibt `HELPER-CHANNEL-INTEGRATION.md` im Kadri-Repository, Branch `feature/helper-channel-dummy`.

## Was dieser Branch ergänzt

| Datei | Zweck |
| --- | --- |
| `app-project/testapp/src/jvmTest/java/de/evp/pakepoc/DesktopHelperHost.java` | Hostet den **unveränderten** `PocServer`, `HttpRequestReader` und `SnapshotWriter` der Test-App auf einem Desktop-JVM (Kern über JNI). Ersetzt nur `PocService` (Android-Lebenszyklus, Keystore) und bildet dessen Logik nach: Kopplungsfenster, Widerruf mit Speicherung vor Erfolgsmeldung, Neustart, Identitäts-Reset, Fremd-Listener, injizierbarer Speicherfehler. Identität im Klartext in einem Temp-Ordner – **nur Testwerkzeug**, nie in einer APK (`jvmTest` gehört zu keinem Android-Source-Set). |
| `tools/build-desktop-helper.sh` | baut Kern (Feature `jni`, Host-Plattform) und die obigen Klassen |
| `tools/build-wasm-kadri.sh` | baut den Kadri-seitigen WASM-Kern mit wasm-bindgen `--target no-modules` für Kadris klassischen Service Worker, aus unverändertem Kern-Quelltext. Seit diesem Branch mit `--remap-path-prefix` (auch in `build-ext.sh`), damit die `.wasm` unabhängig von Pfad und Rechner gleich ist (`d84e09bb…`, in CI und lokal aus verschiedenen Pfaden bestätigt). |
| `.github/workflows/poc-android-capability.yml` | läuft zusätzlich auf diesem Branch (JVM-Prüfungen, NDK-Build, Emulator-Instrumentierung) |

## Korrektur: WASM-Bitgleichheit mit dem Gerätetest war nicht belegt

Frühere Angaben (Commit-Text von `cf76ed1`, erste Fassung dieses Dokuments), die Kadri-WASM sei „bitgleich mit der im Gerätetest geladenen“, waren falsch. Bitgleich war sie nur mit der im Container gebauten Datei `18d07767…`. Die WASM-Builds vor diesem Branch enthielten absolute Pfade des Bau-Rechners (Cargo-Home, Checkout) in Panic-Meldungen; die auf dem S26 geladene Datei wurde lokal auf einem anderen Rechner gebaut und ihr Hash ist nicht dokumentiert. Belegt ist: gleicher Kern-Quelltext (`bf84258`), gleiche Toolchain und Abhängigkeiten. Seit diesem Branch sind die Pfade umgeschrieben und der Build ist rechnerunabhängig reproduzierbar.

## Befund aus den Integrationstests

Ein Neustart des Servers direkt nach `stop()` scheiterte gelegentlich mit „port busy“ (etwa 3 von 150 Neustarts): Auf dem JDK wird ein lauschender Socket, der geschlossen wird, während ein anderer Thread in `accept()` blockiert, erst verzögert freigegeben. Der Desktop-Host wiederholt den Start bis zu 3 s mit neuen Instanzen; `PocServer` selbst ist unverändert. Die Android-Test-App startet den Server bei Fremd-Listener AUS und Identitäts-Reset auf dieselbe Weise neu; dort zeigt sie dann „port 47899 busy“ (sicherer Zustand, nur Verfügbarkeit). Für die spätere Integration in die produktive App einplanen (Warten auf Freigabe oder Wiederverwendung des Sockets).

## Plan für die produktive Helper-App (nicht umgesetzt, eigene Freigabe nötig)

1. Voraussetzung: Issue #3 gelöst (neue Signatur), unabhängiger Review der Kadri-Integrationsstufe.
2. Kern + `PocServer`-Nachfolger als getrenntes Modul neben dem alten `MiniServer` (Port 47811); eigener Port; Identität wie in der Test-App (Keystore-umhüllt, Zustandsmaschine F2); Registrierungen wie F1/F4.
3. Befehle einzeln vom alten auf den neuen Server umziehen; jeder Umzug schaltet den alten Endpunkt für diesen Befehl ab. Kein Rückfall vom neuen auf den alten Weg.
4. Zum Schluss alten Server und `X-EVP-Key` entfernen.
