# Helper #4 – isolierter PAKE-/Kanal-Machbarkeitsnachweis (Kandidat B)

**Nur Machbarkeitsnachweis. Keine Produktionsfreigabe, keine Implementierungsfreigabe.** Ausschließlich Wegwerf-Identitäten, künstliche Codes und der Nicht-Produktionsport 47899. Kein Android-Build, keine APK, keine Signierung, keine Änderung an `app/`, `src/`, `userscript/`, Kadri, CI, minSdk oder `release/production-policy.json` (bleibt `UNAPPROVED`). Issue #4 bleibt offen. PR #20 (`codex/server-auth-design`) bleibt unverändert und getrennt.

Basis: Helper `main` bb8a42d83eac108c098600e50a20208446134e65, Kadri `main` d52faae455c4518c843bed4c50748c87aea1bf40 (beide am 2026-10-27 als aktuelle Remote-Stände geprüft). Laufergebnisse: [RESULTS-2026-10-27.md](RESULTS-2026-10-27.md).

## Ergebnis in einem Satz

Die Kombination **SPAKE2 (RFC 9382, P-256) mit den langfristigen X25519-Schlüsseln als RFC-Identitäten A/B, danach `Noise_KK_25519_ChaChaPoly_SHA256` mit genau diesen gepinnten Schlüsseln** läuft als *ein* Rust-Kern sowohl als WASM im echten Chromium-MV3-Service-Worker als auch per JNI auf der JVM. Alle Positiv- und Negativtests bestehen. **Produktionsblocker:** Weder die PAKE-Bibliothek (`pakery-spake2 0.6.2`) noch `snow 0.10.0` noch die Komposition sind unabhängig auditiert; Android/Vivaldi ist nicht auf Hardware geprüft.

## 1. Bestandsaufnahme (am Code bestätigt)

| Komponente | Heutiger Stand | Für sichere Kopplung ändern? |
| --- | --- | --- |
| Helper `Keys.java` | 128-bit-Zufalls-Bearer als Hex in `SharedPreferences("helfer")`, Flag `paired`. | **Ja:** X25519-Helperidentität (Keystore-umhüllt), Registrierungsliste (Registrierungs-ID → Kadri-Schlüssel), Kopplungsfenster. |
| Helper `MiniServer.java` | HTTP/1.1, eine Anfrage je Verbindung, `127.0.0.1:47811`, `X-EVP-Key`-Prädikat, unauthentisiertes `GET /pair`, `POST /push` bis 256 MiB gestreamt, Host-Check. | **Ja (Transportkern):** Pairing-/Handshake-/Record-Endpunkte mit Bodylimit 65 556 B; `X-EVP-Key` und `/pair` entfernen. Host-Check, Socket-/Poolgrenzen behalten. |
| Helper `HelferService.route()` | `/ping /bright /vol /beat /download /pull /push /dlstate /end` aus Query-Parametern. | **Adapter:** gleiche Routenlogik, Eingabe aus entschlüsselten Kontrollrecords statt Query/Header. |
| Helper `MainActivity.pair()` | Öffnet `http://127.0.0.1:47811/pair#KEY` im Browser; `Keys.reset`. | **Ja (UI):** „Kopplungscode anzeigen“ (Fenster öffnen, 8-stelliger Code, Countdown), „Kopplung widerrufen“. |
| Helper `Downloads`/`Fetcher`/`HlsCore`/`DashCore` | Proxy `pull`/`push` mit bis zu 4-MiB-Fenstern (`Fetcher.MAX_ARRAY_BYTES`). | **Nein für Kopplung.** Streaming → #5. |
| Helper `SettingsTransfer` | Exportiert keine Kopplungsdaten. | Nein (beibehalten). |
| Helper `app/build.gradle` | Keine nativen Bibliotheken. | **Ja (Build):** JNI-`.so` je ABI, Rust-Toolchain/cargo-ndk – Owner-Entscheidung. |
| Kadri `chrome|firefox/prelude.js` `KADRI_HELPER_KEY` | Content-Script liest `gm:helperKey` aus `storage.local` → **Bearer liegt im Renderer beliebiger Webseiten**. | **Ja:** entfernen. Schlüssel/Kanal nur im Hintergrund. |
| Kadri `src/080-helper-app.js` `helper()` | Baut `X-EVP-Key`-Header, `/pair#`-Hash-Übernahme, Proxy-`gmGet` puffert Fenster als `ArrayBuffer`. | **Ja:** `helper()` wird semantische Nachricht an den Hintergrund; `/pair`-Übernahme entfällt. Proxy-Pufferung → #5. |
| Kadri `background.js` | Generischer `kadri-xhr`-Port; Helper-Ausnahme in `guard()` (POST erlaubt, `credentials: omit`, `redirect: error`), DNR-Allow-Regel 50. | **Ja:** Helper-Client (WASM) hier; Helperzugriff aus dem generischen Port entfernen. |
| Kadri `popup.js` | `fetch /ping` direkt mit Schlüssel. | **Ja:** über Hintergrund. |
| Kadri `options.html/js` | Keine Kopplungs-UI. | **Ja:** Codeeingabe (vertrauenswürdige Erweiterungsseite). |
| Kadri `manifest.json` / `tools/build.js` | Keine WASM-CSP, kein WASM-Artefakt. | **Ja:** `'wasm-unsafe-eval'` nur für `extension_pages` (MV3) bzw. Firefox-Äquivalent; reproduzierbarer WASM-Build. |
| Userscript-Route | Gemeinsame `src/`, GM-Manager. | Kein belegter vertrauenswürdiger Bootstrap/WASM-Kontext → **nicht abgedeckt**; alter Bearer darf nicht parallel weiterlaufen (Produktentscheidung). |
| Tests | `tests/security/helper_listener.js` (PR #20) reproduziert den Abfluss; Kadri `test_helper.py`, `helper_repair_check.js` setzen den Bearer voraus. | Später: Exposure-Test wird Regressionstest „fremder Listener erhält nichts Vertrauliches“; Bearer-Tests ersetzen. |

## 2. Bibliotheksbewertung (Stand 2026-10-27, Metadaten von crates.io/npm abgefragt)

### PAKE

| Kandidat (exakte Version) | Standard / Vektoren | Audit | Pflege | Bindings | Bewertung |
| --- | --- | --- | --- | --- | --- |
| `pakery-spake2 0.6.2` + `pakery-core 0.6.2` + `pakery-crypto 0.6.2` (github.com/djx-y-z/pakery, MIT/Apache-2.0) | RFC 9382, P256-SHA256-HKDF-HMAC; alle 4 Appendix-B-Vektoren bestehen, **unabhängig nachgerechnet** (eigene Python-P-256-Arithmetik, M/N aus dem RFC-Seed neu erzeugt) | **keines** | Erstveröffentlichung 2026-03-07, 10 Releases in 7 Monaten, 0.3→0.6 in sechs Wochen, letzte Version 2026-10-26, ~3 300 Downloads | Rust, `forbid(unsafe_code)`; WASM und JNI im PoC belegt | **PoC-Kandidat, nicht produktionsreif.** Junge, schnell wechselnde API; Abhängigkeit `p256 0.14.0` (README: Arithmetik nie unabhängig auditiert). |
| RustCrypto `spake2 0.4.0` | Nicht RFC 9382 (Ed25519-Gruppe, python-spake2-kompatibel), keine Key-Confirmation | keines (README) | gepflegt, weit verbreitet | Rust | Ungeeignet: keine RFC-Vektoren, Bestätigung müsste selbst gebaut werden. |
| `opaque-ke 4.0.1` (Meta) | RFC 9807 | NCC 2021 für v0.5 → Fixes in v1.2; **nicht 4.0.1** | gepflegt | Rust; WASM-Wrapper `@serenity-kit/opaque 1.1.0` | Für Kopplung **nicht empfohlen**: aPAKE braucht authentisierte Registrierung; lokale Selbstregistrierung im Helper kann Kadris eigene Identität nicht binden. Argon2-Standardparameter speicherintensiv. |
| BoringSSL `spake2plus` (RFC 9383) bzw. TLS-PAKE (`SSL_CREDENTIAL_new_spake2plusv1_*`, draft-bmw-tls-pake13) | RFC 9383, Vektoren im Projekt | Google-intern gepflegt, kein öffentlicher Einzelaudit bekannt | aktiv | C++; Prover/Verifier nur interne API; Browser-`fetch` kann kein TLS-PAKE; WASM- und NDK-Build nötig | Bessere Herkunft, aber deutlich höhere Größe/Build-/Wartungskosten; hier **nicht zur Laufzeit geprüft**. Alternative für die nächste Entscheidung. |
| CPace (draft-irtf-cfrg-cpace), `pake-cpace 0.1.7` (2023) | Entwurf, älterer Draftstand | keines | gering | Rust | Nicht jetzt. |
| JS `spake2 1.0.2` (2019), `@niomon/spake2 1.0.4` (2022) | eigene Ed25519-Draft-Vektoren, nicht RFC 9382 P-256 | keines | inaktiv | JS | Ungeeignet. |
| Java | Kein gepflegtes SPAKE2/RFC-9382 gefunden (Bouncy Castle: J-PAKE, SRP-6a) | – | – | – | Daher Rust-Kern per JNI. |

### Noise

| Kandidat | Audit | Befund | Bewertung |
| --- | --- | --- | --- |
| `snow 0.10.0` (Apache-2.0/MIT, 2025-07-19, ~29,7 Mio. Downloads) | README: **„has not received any formal audit“** | Cacophony-Vektor (unabhängige Haskell-Implementierung) für exakt `Noise_KK_25519_ChaChaPoly_SHA256` besteht. Reine Rust-Primitiven: `curve25519-dalek 4.1.3`, `chacha20poly1305 0.10.1` (README: ein NCC-Audit ohne wesentliche Befunde), `sha2 0.10.9`. snow-Feature `std` zieht `ring` (C/asm) → im PoC deaktiviert. | **PoC-Kandidat**, Auditlücke. |
| `clatter 2.3.0` | README: kein Audit | PQ-Schwerpunkt | Nicht nötig. |
| `noise-protocol 0.2.1` (Unlicense) | unbekannt | klein | Nicht weiter verfolgt. |
| JS `noise-handshake 4.2.0`, `@chainsafe/libp2p-noise 17.0.0` | nicht belegt | libp2p-Variante XX-zentriert | Zwei Implementierungen = doppelter Interop-/Auditumfang → nicht gewählt. |
| Java `noise-java` | nicht geprüft (Maven-Suche aus Sandbox gesperrt) | – | Nicht gewählt. |

**Kleinste Kombination:** ein Rust-Kern (`pakery-spake2`/`-core`/`-crypto` =0.6.2, `snow` =0.10.0 ohne `std`), Bindings über `wasm-bindgen =0.2.104` und `jni =0.21.1`. Eine Codebasis, ein Auditumfang, identisches Verhalten auf beiden Seiten. 118 transitive Crates (inkl. Bindings), ausschließlich permissive Lizenzen (MIT/Apache-2.0/BSD-3/Unlicense-OR-MIT) – mit Kadris GPL-3.0 kompatibel; der Helper hat keine Wurzellizenz (Owner-Entscheidung).

## 3. Geprüfte Komposition (keine eigene Kryptografie)

Der Rust-Kern (`core/src`, ~870 Zeilen inkl. Bindings) ruft nur Bibliotheksfunktionen auf und rahmt Bytes. Keine eigene KDF, AEAD, Nonce- oder Handshakelogik.

**Kopplung (SPAKE2, Kadri = RFC-Partei A, Helper = B):**

```
M0 K→H  "KHP1" 01 | kadri_pub[32]
M1 H→K  "KHP1" 02 | pairing_id[16] | helper_pub[32] | pB[65]
M2 K→H  "KHP1" 03 | pairing_id[16] | pA[65] | cA[32]
M3 H→K  "KHP1" 04 | pairing_id[16] | cB[32]
```

- RFC-Identitäten: `A = "kadri-ext-x25519/1:" || kadri_pub`, `B = "kadri-helper-x25519/1:" || helper_pub`; AAD `"kadri-helper-pairing-poc/1"`. Die beidseitige RFC-Key-Confirmation authentisiert damit genau diese öffentlichen Schlüssel. `Ke` bleibt ungenutzt.
- `w` = Argon2id (m = 8 MiB, t = 2, p = 1, über `pakery-crypto`) des 8-stelligen Codes, Reduktion mod p. Code: 8 Ziffern (≈26,6 bit), im Helper unverzerrt erzeugt.
- Helper verifiziert `cA`, **bevor** er `cB` sendet. Ein Versuch je Kopplungsfenster, jeder Fehler verbrennt Fenster bzw. Kadri-Vorgang. TTL Helper 120 s, Kadri 60 s. Pin erst nach erfolgreicher Bestätigung.

**Kanal (Noise KK mit beiden gepinnten statischen Schlüsseln):**

```
HS1 K→H  "KHC1" 10 | registration_id[16] | noise msg1 (48 B, leere Nutzlast)
HS2 H→K  "KHC1" 11 | session_id[16]      | noise msg2 (48 B, leere Nutzlast)
REC      "KHC1" 20 | session_id[16]      | Noise-Transportnachricht ≤ 65 535 B
```

- Prolog `"kadri-helper-channel-poc/1"`, feste Parameter, keine Aushandlung, kein Fallback. Jede Verbindung neuer Handshake; nach Neustart nur Pins persistent, nie Transportzustand.
- Zustandsbehafteter `TransportState`: implizite Nonces, strikte Reihenfolge; jeder Authentisierungsfehler macht den Kanal endgültig unbrauchbar.
- Anwendungsrecords: Kontrolle ≤ 16 KiB, Medien ≤ 60 KiB (61 455 B Klartext + 16 B Tag ≤ 65 535 B), Ende-Record mit Gesamtlänge; Längen exakt geprüft vor Allokation.

**Was ein fremder Portbesitzer maximal erreicht:** DoS; das Kopplungsfenster durch ein eigenes M0 verbrauchen (Nutzer sieht Fehlschlag, neuer Code nötig); eine einzige Online-Codevermutung je Fenster (Erfolgswahrscheinlichkeit 10⁻⁸); öffentliche Schlüssel, Registrierungs-ID und Zeitpunkte sehen. Keine Offline-Prüfung des Codes, kein Handshake-Abschluss, keine Anwendungsdaten.

## 4. Nachweise (Details: [RESULTS-2026-10-27.md](RESULTS-2026-10-27.md))

| Bereich | Ergebnis |
| --- | --- |
| RFC 9382 Appendix B, 4 Vektoren, über die genutzten Bibliothekseinstiege | PASS |
| Unabhängige Python-Gegenrechnung (pA, pB, K beidseitig, TT, Hash, Ke, KcA/KcB, cA/cB; M/N aus Seed regeneriert) | 36/36 PASS |
| Cacophony-Vektor `Noise_KK_25519_ChaChaPoly_SHA256` (Handshake, Handshake-Hash, 4 Transportnachrichten) | PASS |
| Rust-Protokolltests: Pairing positiv; falscher Code; fremder Listener mit geratenem Code; MITM-Identitätstausch (beide Richtungen); veränderte/ungültige Shares; Reflexion; Replay kompletter Altläufe mit gleichem Code; Timeout; parallele Versuche; Codeformat; Kanal positiv mit 60-KiB-Records; fremder Listener (auch mit eigenem gültigen KK-Responder); falscher Pin beidseitig; widerrufene Registrierung; Manipulation; Replay; Umordnung; Reflexion; Neustart; Prolog-/Downgrade-Abweichung | 12/12 PASS |
| Mutationsprobe (Identitätsbindung, cA-Prüfung, cB-Prüfung, Fensterverbrennung, Deadline entfernt) | 5/5 Mutanten erkannt |
| End-to-End: Chromium 153.0.8010.12, MV3-Service-Worker mit WASM unter `'wasm-unsafe-eval'` ↔ JVM-Helper (JNI, `-Xmx32m`) über echtes Loopback-HTTP; Kopplung über Optionsseite | 23/23 PASS |
| Fremder Listener im E2E: erhält nur M0 (37 B), M2 (118 B), HS1 (69 B); keine URL-, Cookie-, Code-, Marker- oder `X-EVP-Key`-Bytes; kein Record nach fehlgeschlagenem Handshake; bestehender Pin unverändert | PASS |
| Größen | WASM 270 001 B (gzip 82 317 B) + 14 957 B JS-Glue; JNI-`.so` x86_64-Linux 803 936 B (gzip 321 135 B) – **Android-ABIs nicht gebaut** |
| Speicher | WASM-Linearspeicher 1,06 MiB initial → 9,19 MiB nach Kopplung (Argon2-Hochwasser, wächst nicht weiter), unverändert während 2 paralleler Transfers (4,2 MB) |
| RNG | Rust `getrandom` → `crypto.getRandomValues` (WASM) bzw. OS-RNG (JNI) |

## 5. Kopplung vs. Streaming – klare Trennung

**Mit Kopplung/Kanal gelöst (im PoC belegt):** Partnerauthentisierung vor jeglichen Anwendungsdaten, verschlüsselte und authentisierte Requests/Responses, Records ≤ 65 535 B mit 60-KiB-Nutzlast, Replay-/Reflexions-/Umordnungsschutz, ACK-getriebenes Ein-Record-pro-Transfer-Muster mit zwei parallelen Transfers.

**Nicht gelöst – nachgelagert (#5):** Die heutige Proxy-Route puffert bis zu 4-MiB-Fenster vollständig (`gmGet` → `ArrayBuffer`, Base64-Stücke im Hintergrund, `/push`-Body). Der PoC-Durchsatz von **1,2 MiB/s** stammt von einer neuen HTTP-Verbindung je 60-KiB-Record plus JS-Byteprüfung – für Medien unzureichend und ein eigenes, separat zu prüfendes Transportdesign (persistente Verbindung oder Batching ohne Ordnungsbruch, Fetch-BYOB, Spoolgrenzen, Abbruch/Cleanup). Ebenfalls offen: reale 32-MiB-Heap-Messungen auf Android inklusive nativer JNI-Allokationen (Argon2 8 MiB transient).

## 6. Exakte Produktionsblocker

1. **Kein unabhängiges Audit** von `pakery-spake2/-core/-crypto 0.6.2`, der `p256 0.14.0`-Arithmetik, `snow 0.10.0` und dieser Komposition. pakery ist sehr jung und API-instabil.
2. **RFC-Text nicht direkt geprüft:** rfc-editor.org/ietf.org waren aus der Sandbox gesperrt. Die Vektordatei stammt aus dem pakery-Repo (Commit 6abbf8235e40d7bce975a44aaf423d0e3c436df6) und muss gegen RFC 9382 Appendix B gedifft werden. M und N wurden unabhängig aus dem RFC-Seed-Verfahren regeneriert.
3. **Android ungeprüft:** kein NDK-Build, keine ARM-Größen, kein Keystore-Wrapping des X25519-Schlüssels, kein Gerät. **Vivaldi Android ungeprüft:** MV3-Service-Worker, WASM-CSP, Loopback-`fetch`/LNA aus dem Erweiterungshintergrund.
4. **Firefox ungeprüft:** MV2-Hintergrundseite mit WASM-CSP.
5. **Userscript-Manager:** kein vertrauenswürdiger Bootstrap/WASM-Kontext belegt.
6. **Kadri-Schlüsselablage:** privater Schlüssel und Pin nur im Erweiterungsursprung, nicht für Content-Scripts lesbar (heutiges `gm:helperKey` verletzt das). Der PoC nutzt `storage.local`, weil die Testerweiterung keine Content-Scripts hat – das ist **keine** Produktionslösung.
7. **Streaming-Transport (#5)** wie oben.
8. **Lieferkette/Build:** Vendoring und gesperrtes Lockfile, reproduzierbarer WASM-Build, cargo-ndk im Gradle-Build, Helper-Lizenz.

**Realistische Alternativen, falls 1 nicht lösbar ist:** (a) fokussiertes Audit genau dieses gepinnten Paares (pakery-spake2 ≈ 850 Zeilen + genutzte Teile von core/crypto, snow-Handshake/Transport) plus Komposition; (b) BoringSSL-SPAKE2+ (RFC 9383) als PAKE-Kern prüfen – bessere Herkunft, höhere Größe und Buildkosten, interne API; (c) CPace nach RFC-Veröffentlichung; (d) Kandidat C (Plattform-Brücke) bzw. A (Geräte-TLS) laut PR #20.

## 7. Empfohlener nächster unabhängiger Prüfschritt

Vor jeder Produktionsänderung prüft ein unabhängiger Reviewer:

1. die **Kompositionsspezifikation** aus Abschnitt 3 (Identitäten als RFC-Identitäten, Rollen, AAD, MHF-Wahl, Codelänge und Versuchspolitik, KK mit Pins, Registrierungs-ID, Sitzungen, kein Fallback, Restrisiko „Fenster verbrauchen“),
2. den **PoC-Code** in `core/src` (≈870 Zeilen) und die Testabdeckung,
3. die **Bibliotheksentscheidung**: Audit von pakery+snow beauftragen *oder* BoringSSL-SPAKE2+ evaluieren.

Erst danach, getrennt freigegeben: Android-NDK-/Vivaldi-Fähigkeitsnachweis mit Wegwerf-Test-App (kein Produktionssignierer), danach Streamingdesign #5.

## 8. Reproduktion

```sh
tools/build.sh /tmp/poc-build                       # Rust (JNI + WASM), wasm-bindgen 0.2.104, javac – keine APK
python3 -I vectors/independent_check.py vectors/rfc9382-appendix-b.json
(cd core && cargo test)
tools/mutation_check.sh
node browser/run.mjs /tmp/poc-build/jvm "$PWD/core/target/release/libkadri_pairing_poc.so"
```

Voraussetzungen: Rust ≥ 1.88 mit `wasm32-unknown-unknown`, `wasm-bindgen-cli 0.2.104`, JDK 17+, Node 22, Playwright mit Chromium. Kein Netzwerkzugriff außer Loopback während der Tests.
