# Helfer-Kanal – Integrationsstufe 1: Helfer-Seite

**Nur Testwerkzeuge, keine Änderung der produktiven Helper-App (`app/`).** Branch `feature/helper-channel-dummy`, basierend auf dem auf dem S26 Ultra getesteten Stand `bf84258`. Der Rust-Kern (`poc/pake-channel/core`) ist unverändert. `release/production-policy.json` bleibt `UNAPPROVED`; Issue #3 bleibt Release-Blocker, Issue #4 offen.

Die Kadri-Seite (isolierter Test-Build, Architektur, Tests, Restrisiken, Review-Schritte) beschreibt `HELPER-CHANNEL-INTEGRATION.md` im Kadri-Repository, Branch `feature/helper-channel-dummy`.

## Was dieser Branch ergänzt

| Datei | Zweck |
| --- | --- |
| `app-project/testapp/src/jvmTest/java/de/evp/pakepoc/DesktopHelperHost.java` | Hostet den **unveränderten** `PocServer`, `HttpRequestReader` und `SnapshotWriter` der Test-App auf einem Desktop-JVM (Kern über JNI). Ersetzt nur `PocService` (Android-Lebenszyklus, Keystore) und bildet dessen Logik nach: Kopplungsfenster, Widerruf mit Speicherung vor Erfolgsmeldung, Neustart, Identitäts-Reset, Fremd-Listener, injizierbarer Speicherfehler. Identität im Klartext in einem Temp-Ordner – **nur Testwerkzeug**, nie in einer APK (`jvmTest` gehört zu keinem Android-Source-Set). |
| `tools/build-desktop-helper.sh` | baut Kern (Feature `jni`, Host-Plattform) und die obigen Klassen |
| `tools/build-wasm-kadri.sh` | baut den Kadri-seitigen WASM-Kern mit wasm-bindgen `--target no-modules` für Kadris klassischen Service Worker. Die `.wasm` ist bitgleich mit der im Gerätetest geladenen (`18d07767…`). |
| `.github/workflows/poc-android-capability.yml` | läuft zusätzlich auf diesem Branch (JVM-Prüfungen, NDK-Build, Emulator-Instrumentierung) |

## Befund aus den Integrationstests

Ein Neustart des Servers direkt nach `stop()` scheiterte gelegentlich mit „port busy“ (etwa 3 von 150 Neustarts): Auf dem JDK wird ein lauschender Socket, der geschlossen wird, während ein anderer Thread in `accept()` blockiert, erst verzögert freigegeben. Der Desktop-Host wiederholt den Start bis zu 3 s mit neuen Instanzen; `PocServer` selbst ist unverändert. Die Android-Test-App startet den Server bei Fremd-Listener AUS und Identitäts-Reset auf dieselbe Weise neu; dort zeigt sie dann „port 47899 busy“ (sicherer Zustand, nur Verfügbarkeit). Für die spätere Integration in die produktive App einplanen (Warten auf Freigabe oder Wiederverwendung des Sockets).

## Plan für die produktive Helper-App (nicht umgesetzt, eigene Freigabe nötig)

1. Voraussetzung: Issue #3 gelöst (neue Signatur), unabhängiger Review der Kadri-Integrationsstufe.
2. Kern + `PocServer`-Nachfolger als getrenntes Modul neben dem alten `MiniServer` (Port 47811); eigener Port; Identität wie in der Test-App (Keystore-umhüllt, Zustandsmaschine F2); Registrierungen wie F1/F4.
3. Befehle einzeln vom alten auf den neuen Server umziehen; jeder Umzug schaltet den alten Endpunkt für diesen Befehl ab. Kein Rückfall vom neuen auf den alten Weg.
4. Zum Schluss alten Server und `X-EVP-Key` entfernen.
