# Helper-Steuerkanal (Integrationszweig, nicht freigegeben)

Status: **Entwurf für den Integrationszweig.** Produktionsstatus bleibt `UNAPPROVED`
(`release/production-policy.json`). Issue #3 (kompromittierte Signatur) und Issue #4 (Helper-Authentifizierung)
bleiben offen, bis ein unabhängiger Security-Review vorliegt. Keine Releases, keine produktiven APKs.

## Was sich ändert

| Vorher | Jetzt |
|---|---|
| `127.0.0.1:47811`, Klartext-HTTP, fester Schlüssel `X-EVP-Key`, Kopplungsseite `/pair#…` | `127.0.0.1:47899`, nur `POST /p1/pair/m0`, `/p1/pair/m2`, `/p1/ch/hs`, `/p1/ch/rec` |
| Schlüssel im App-Speicher, in jeder Anfrage gesendet | SPAKE2-Kopplung mit 8-stelligem Einmal-Code (120 s, ein Versuch), danach Noise KK mit gepinnten Identitäten |
| Downloads, Proxy, DASH, Cookies über den Helper | **vorübergehend aus** (`dl`, `dash`, `proxy` = `false`); keine Cookies, URLs oder Medien im Kanal |
| Userscript spricht den Helper | Userscript-Helferfunktionen aus (D2), kein Rückfall |

Kryptografie, Protokoll-IDs und Grenzen kommen unverändert aus dem geprüften Kern `poc/pake-channel/core`
(pakery 0.6.2 SPAKE2, snow 0.10.0 Noise KK). Neu im Kern ist nur die JNI-Bindung `hostRevoke` für den
Einzelwiderruf; sie ruft den vorhandenen `Host::revoke` auf. Kadris WASM bleibt byte-identisch (CI prüft das).

## Steuerbefehle (einzige erlaubte Inhalte eines Steuer-Records, ≤ 256 Byte)

`{"op":"status"}`, `{"op":"beat"}`, `{"op":"end"}`, `{"op":"vol"}`, `{"op":"vol","v":0…1}`,
`{"op":"bright","v":0.004…1}`. Andere Felder, doppelte Felder, Escapes, Exponenten, Werte außerhalb des Bereichs
oder ungültiges UTF-8 → `{"ok":false}`, ohne das Gerät zu berühren. Die Antwort ist die bisherige Status-JSON
(feste Felder, App-Version bereinigt) bzw. `{"ok":true}` für `end`. Medien- und Ende-Records beendet der Kern.

## Bausteine in der App

- `ChannelServer` – Loopback-Server (4 Worker, Warteschlange 16, 3-s-Lesefrist), Browser-Schutz (Host, eigener
  Header, Content-Type, Erweiterungs-Origin). Kernaufrufe unter einer Sperre, Befehle außerhalb.
- `ControlCommands` – strenge Prüfung und Status-JSON (Android-frei, am Desktop getestet).
- `IdentityStore` (Keystore-AES-GCM, an den Paketnamen gebunden, keine stille Rotation),
  `RegistryStore` (Neustart-Erkennung), `SnapshotWriter` (geordnete, nur geänderte Schreibvorgänge) – aus dem
  Geräte-PoC übernommen, nur das Paket und der AAD-Kontext geändert.
- `HelferService` – startet den Kanal; ohne lesbare Identität oder native Bibliothek bleibt er aus („Sicherer
  Kanal nicht verfügbar“), kein Ausweichen. Widerruf meldet Erfolg erst nach dem Speichern, sonst Kanal aus.
- `MainActivity` – „Code anzeigen“ (FLAG_SECURE, Countdown), „Browser verwalten“ (einzeln/alle trennen),
  langes Drücken: Identität zurücksetzen. Der alte Kopplungsschlüssel wird beim Start gelöscht.
- Keine Sicherung/Geräteübertragung der Kanaldaten (`allowBackup=false`, Backup-/Extraktionsregeln).

## Testvariante

`de.evp.helfer.channeltest` („TEST Helper-Kanal“) installiert sich neben, nie über `de.evp.helfer`. Nur sie trägt die
native Bibliothek; Debug- und Release-Builds ohne Bibliothek starten den Kanal nicht. Sie ist **nicht debugfähig**
(kein `run-as`/Debugger auf die Kanaldaten); die Instrumentierung braucht nur denselben Wegwerf-Signierer.

```sh
ANDROID_NDK_HOME=… tools/build-channel-native.sh OUT     # Rust → JNI, arm64-v8a + x86_64, API 26
tools/build-channel-apk.sh OUT                           # Wegwerf-Signierer, prüft Paket, Backup, Bibliotheken
tools/run-channel-instrumentation.sh OUT                 # nur Emulator; deinstalliert danach
tests/java/channel/run.sh                                # Desktop: echter Server + Kern, 10 Prüfgruppen
python3 tests/java/channel/mutation.py OUT               # 11 Mutanten müssen scheitern
```

`tests/java/channel/ChannelHarness` betreibt denselben Server am Desktop für Kadris Integrationstests
(`open`, `list`, `revoke <id>`, `revoke-all`, `restart`, `reset-identity`, `rogue-on/off`, `device`, `stats`).

CI: `.github/workflows/helper-channel.yml` (Kern-Tests, JVM-Prüfungen, Mutationsproben, WASM-Vergleich,
Emulator API 34). Es wird keine APK hochgeladen, nur Text-Berichte. Der bestehende Debug-APK-Upload in `build.yml`
ist für diesen Zweig abgeschaltet.

## Verfügbarkeit (langsame lokale Clients)

4 Worker, Warteschlange 16, 3 s Lesefrist. Eine bösartige App auf dem Gerät kann durch fortlaufend neue langsame
Verbindungen die Worker belegen; Kadri meldet dann „Helfer nicht erreichbar“. Nur Verfügbarkeit, keine Befehle ohne
Schlüssel. Messwerte und mögliche Gegenmaßnahmen (nicht umgesetzt): `SECURITY-REVIEW-CHANNEL.md` §7.

## Offen

- Unabhängiger Security-Review vor dem Gerätetest (Samsung).
- Downloads über den Helper: später, mit einer Lösung, bei der DNS-Auflösung, Zielprüfung und Verbindung nicht
  auseinanderfallen. Bis dahin aus.
- Userscript bekommt den Kanal frühestens zusammen mit Kadris WASM-Lösung; bis dahin ohne Helfer.
- R1/R2 (Verfügbarkeit: wiederholtes HS1 verdrängt eine frische Sitzung; bekannte Sitzungs-ID erlaubt nur Abbruch)
  bleiben wie im PoC dokumentiert.
