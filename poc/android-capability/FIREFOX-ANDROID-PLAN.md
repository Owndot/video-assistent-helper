# Firefox Android – geplanter separater Capability-Test (noch nicht durchgeführt)

**Status: Plan, keine Aussage über Kompatibilität.** Der Firefox-Desktop-Lauf (158.0.1, 39/39) ist kein Nachweis für Firefox Android. Ausführung erst nach dem Vivaldi-Gerätetest und separater Freigabe.

## Ausgangslage im Repository

Der bestehende Workflow `android-e2e.yml` hat bereits gezeigt, dass in CI ein Wegwerf-Emulator (API 33/35) mit dem **offiziellen Firefox-Android-APK** (über Mozilla Product Details, Digest protokolliert) und `web-ext` über Remote-Debugging eine **unveränderte** Erweiterung laden kann. Damit ist ein Emulator-Test ohne zusätzliche Infrastruktur möglich – derselbe Kern, dieselbe Test-App wie für Vivaldi.

## Vorgehen

1. **Firefox-Variante der Testerweiterung** aus `ext-vivaldi`: MV2-Manifest mit Hintergrundseite (wie Kadris heutiger Firefox-Build und der Desktop-PoC), `content_security_policy: "script-src 'self' 'wasm-unsafe-eval'; object-src 'self'"`, Content-Script-Matches **ohne Port** (`http://127.0.0.1/*`, Firefox unterstützt keine Ports in Match-Patterns), `browser_specific_settings.gecko.id`. Dieselbe WASM-Datei (SHA-256 wird verglichen). **Keine** Testbrücke, **keine** Test-Hooks (R5); Steuerung nur über Options- und Selbsttest-Seite.
2. **Emulator-Lauf in CI** (gleicher Branch, ein Job): offizielles Firefox-APK, Test-App, `web-ext run --target=firefox-android`; Seiten über UI Automator bedienen (wie im bestehenden E2E), keine Remote-Skript-Injektion in Erweiterungsseiten.
3. **Gerätelauf** analog zur Vivaldi-Anleitung, nur mit gesonderter Freigabe.

## Zu prüfen (tatsächlich, nicht aus Desktop abgeleitet)

| Fähigkeit | Offene Frage |
| --- | --- |
| Hintergrundkontext | Lebensdauer/Aussetzen der Hintergrundseite bzw. des Event-Page-Modells auf Android; Wiederaufbau der Sitzung danach |
| WASM | Instanziierung unter `wasm-unsafe-eval` im Erweiterungshintergrund auf Android |
| Zufall | `crypto.getRandomValues` im Hintergrund (für `getrandom`) |
| IndexedDB | Persistenz im Erweiterungsursprung über App-Neustarts; Unerreichbarkeit für Content-Scripts |
| Loopback | `fetch` vom `moz-extension://`-Ursprung an `127.0.0.1:47899`, Verhalten von Firefox' Local-Network-Access-Schutz, Origin-Header (Gate) |
| Optionsseite | Erreichbarkeit von `options_ui` auf Android, Nachrichten an den Hintergrund, Absenderprüfung |
| Installation | Weg für Nutzer ohne `web-ext` (Firefox Android: nur AMO-signierte Erweiterungen in Release; Testkanal klären) |

## Abbruchkriterien

Fehlende WASM-Ausführung, fehlende persistente Ablage im Erweiterungsursprung oder kein erlaubter Loopback-Zugriff aus dem Erweiterungshintergrund → Firefox Android für diesen Weg **nicht bestanden**; kein Ausweichen auf Seiten-Skripte, Bearer oder HTTP-Fallback.

## Was heute fehlt

Kein Firefox-Android-Lauf mit diesem Kern; keine Aussage zu Hintergrundlebensdauer, LNA-Verhalten, IndexedDB-Persistenz oder Installationsweg auf Firefox Android.
