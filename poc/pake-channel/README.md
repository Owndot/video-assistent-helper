# Helper #4 – isolierter PAKE-/Kanal-Machbarkeitsnachweis (Kandidat B), Revision 2

**Nur Machbarkeitsnachweis. Keine Produktions- oder Implementierungsfreigabe.** Ausschließlich Wegwerf-Identitäten, künstliche Codes und Nicht-Produktionsports (47899 Helper, 47900 Testwebseite, 47902 Firefox-Testbrücke). Kein Android-Build, keine APK, keine Signierung, keine Änderung an `app/`, `src/`, `userscript/`, Kadri, CI, minSdk oder `release/production-policy.json` (bleibt `UNAPPROVED`). Issue #4 bleibt offen, PR #20 unverändert. Alle Änderungen liegen unter `poc/pake-channel/`.

Ausgangs-HEAD dieser Revision: 5ab5e21373c881d390be183a7af7894716a64eba (geprüfter Stand des unabhängigen Reviews). Basis weiterhin Helper `main` bb8a42d, Kadri `main` d52faae. Laufprotokolle: [RESULTS-2026-10-27-r2.md](RESULTS-2026-10-27-r2.md); Erstlauf: [RESULTS-2026-10-27.md](RESULTS-2026-10-27.md).

## Ergebnis

Protokollversion 2 bindet Pairing-/Registrierungs-ID kryptografisch (RFC-9382-AAD und Noise-Prolog), validiert alle X25519-Schlüssel, macht jeden Kanalfehler terminal und begrenzt HS1-Replays über eine kleine Helper-Sitzungstabelle. Derselbe, bytegleiche WASM-Kern (SHA-256 siehe Ergebnisdatei) läuft jetzt **real in Chromium 153 (MV3-Service-Worker) und Firefox Desktop 158.0.1 (MV2-Hintergrundseite)** gegen denselben JNI-Testhelper; beide Szenarien bestehen vollständig. **Weiterhin Produktionsblocker:** kein unabhängiges Audit von `pakery-*`, `p256`, `snow` und der Komposition; Android/Vivaldi/Firefox Android ungeprüft.

## 0. Review-Findings → Umsetzung

| # | Finding | Umsetzung | Nachweis |
| --- | --- | --- | --- |
| 1 | Pairing-/Registrierungs-ID nicht vollständig gebunden | ID wird im Helper **vor** `PartyB::start` gezogen; RFC-9382-AAD = `"kadri-helper-pairing-poc/2"` (26 B, feste Länge) ‖ ID (16 B) auf beiden Seiten; Noise-Prolog = `"kadri-helper-channel-poc/2"` (26 B) ‖ Registrierungs-ID; Registrierungs-ID = Pairing-ID. Feste Längen → eindeutige Kodierung in beiden Richtungen. | `pairing_id_is_bound_in_both_directions` (ID in M1 manipuliert, in M2 zurückgesetzt, in M3 manipuliert, ID-Verwechslung zweier paralleler Kopplungen), `prologue_binds_version_and_registration_id` (ID-Tausch zwischen zwei Registrierungen desselben Schlüssels) |
| 2 | Argon2-Profil nicht fixiert | Profil in §4 vollständig; KAT mit Referenz-C-Argon2 (argon2-cffi) | `tests/kat.rs`, `vectors/password-scalar-kat.json`, `vectors/argon2_kat.py` |
| 3 | Fehlersemantik nicht terminal | Jeder Fehler in `Channel::seal/open/seal_app/open_app` verwirft beide CipherStates; Initiator einmalig; Helper entfernt die Sitzung; WASM/JNI erben das | `every_receive_error_is_terminal` (10 Fehlerarten, danach weder Fortsetzung noch weitere Nonce-Nutzung), `replay_and_reorder_are_terminal`, `oversized_seal_is_terminal`, `record_errors_remove_only_that_session` |
| 4 | HS1-Replay erzeugt Sitzungen / verdrängt | Halb-offene Sitzungen in eigenem Pool (global 4, je Registrierung 2, 10 s), Beförderung erst durch einen authentisierten Record; etablierte Sitzungen (global 4, je Registrierung 2, 120 s idle, 4 h absolut) werden nie durch unauthentisierte Eingaben verdrängt | `replayed_hs1_never_displaces_established_sessions` (400 Replays), `global_half_open_pressure_spares_established_sessions`, Timeout-Tests |
| 5 | Pairing-/Browser-Guards | HTTP-Gate (nur POST, Host, `application/octet-stream`, `X-Kadri-Helper: 2`, nur Erweiterungs-Origin), keine CORS-Antwort; Low-Order-/nicht-kanonische Schlüssel abgelehnt; ein Kopplungsvorgang je Seite; Registrierung bis zur ersten authentisierten Sitzung „pending“ (10 min), dann bestätigt; Widerruf und Neukopplung | E2E-Abschnitt A/H in beiden Browsern; `invalid_or_low_order_keys_are_rejected`; Host-Tests |
| 6 | 12 von 17 Review-Mutanten überlebten | Gezielte Tests (Deadlines je Phase inkl. Grenze, ID-Konsistenz, echte Argon2-Ableitung, Recordlängen, leere Handshake-Payloads, geschlossene Sitzungen, Prologbindung, Codegrenzen/-verteilung, Replay, terminale Decodefehler); eigene Mutationsprobe mit 39 Mutanten und begründeten Überlebenden | §9 |
| 7 | Fremd-Listener-Test ohne lebende Sitzung | Neuer E2E-Abschnitt G: authentisierte lebende Sitzung → echter Helper beendet → fremder Listener übernimmt Port → Request → nur Chiffrat beim Listener, Sitzung verworfen, kein Fallback | E2E G in beiden Browsern |
| 8 | Firefox fehlt | Firefox-Adapter (MV2-Manifest, Hintergrundseite, CSP), echter Firefox-Desktop-Lauf | §8 |
| 9 | Durchsatz falsch zugeordnet | Korrigiert und nach Ebenen getrennt | §10 |
| 10 | Lieferkette | Befunde, Untergrenzen, `--locked`, Advisory-Prüfung | §2 |

## 1. Bestandsaufnahme (unverändert gegenüber Revision 1)

Für die spätere produktive Umsetzung zu ändern: Helper `Keys.java` (Identität, Registrierungen), Transportteil von `MiniServer.java` (`X-EVP-Key` und `/pair` entfallen), Adapter vor `HelferService.route()`, `MainActivity.pair()` (Code anzeigen, Widerruf), Gradle/JNI. Kadri: `background.js` (einziger Helper-Zugriff), `src/080-helper-app.js` (semantische Nachrichten statt Bearer), `popup.js`, Optionsseite, Manifest-CSP und `tools/build.js`. Insbesondere liest Kadri heute `gm:helperKey` im Content-Script (`prelude.js`); dieser Bearer liegt damit im Renderer beliebiger Webseiten. Download/Proxy/HLS/DASH/Player sind für die Kopplung nicht zu ändern (Streaming → #5). Details: Revision 1 dieses Dokuments (Commit 5ab5e21).

## 2. Bibliotheken und Lieferkette

Exakte Pins (Manifest `=`): `pakery-spake2/-core/-crypto 0.6.2`, `snow 0.10.0`, `curve25519-dalek 4.1.3`, `wasm-bindgen 0.2.104`, `jni 0.21.1`. `rand_core 0.10.1`. Alle Builds mit `cargo build --locked`; das Lockfile ist die Wahrheit, Upgrades erfolgen nicht stillschweigend.

| Bibliothek | Befund (Stand 2026-10-27) |
| --- | --- |
| `pakery-spake2 0.6.2` (+`-core`, `-crypto`) | RFC 9382 P-256; **kein unabhängiges Audit**. Ein Maintainer-Projekt, Erstveröffentlichung 2026-03-07, 0.6.2 vom 2026-10-26. Laut CHANGELOG enthielt **jede Version vor 0.6.1** einen Constant-Time-Defekt im P-256-Encoder der Basis-OPRF und des P-256-DH (betrifft OPAQUE/OPRF; der SPAKE2-Pfad kodiert `K` laut Maintainer nur nach Identitätsprüfung und sei nicht betroffen gewesen; dennoch seit 0.6.1 ebenfalls über einen CT-Encoder). 0.6.2 aktiviert u. a. Zeroize für SHA-2/Argon2/BLAKE2b-Zustände; der Maintainer nennt ausdrücklich verbleibende ungelöschte Werte (u. a. SPAKE2-`K`). Eigene dudect/Valgrind-CT-Jobs im Upstream, **nicht von uns nachvollzogen**. |
| `crypto-bigint`, `ctutils` (transitiv über `p256 0.14`) | RUSTSEC-2026-0387 (crypto-bigint < 0.7.6, Timing) und RUSTSEC-2026-0385 (ctutils < 0.4.4, nicht-CT Masken/`u128`-Vergleich). Gesperrt: **0.7.10** bzw. **0.4.6**. Zusätzlich direkte Untergrenzen `^0.7.6`/`^0.4.4` im Manifest: `cargo update --precise 0.7.5`/`0.4.3` wird von Cargo abgelehnt (geprüft). |
| `p256 0.14.0` | README: Kurvenarithmetik **nie unabhängig auditiert**. |
| `snow 0.10.0` | README: **kein formales Audit**. RUSTSEC-2024-0011 (unauthentisiertes Nonce-Inkrement, < 0.9.5) betrifft 0.10.0 nicht; unabhängig davon verwirft der PoC nach jedem Fehler den gesamten Transportzustand. `std`-Feature deaktiviert (zieht sonst `ring`). |
| `curve25519-dalek 4.1.3` | RUSTSEC-2024-0344 (Timing in `Scalar::sub`, < 4.1.3) behoben. Zusätzlich nur für Schlüsselvalidierung direkt genutzt (gleiche Version wie in snow). |
| `chacha20poly1305 0.10.1` | README: ein NCC-Audit ohne wesentliche Befunde (für eine frühere Version; Umfang für 0.10.1 nicht belegt). |
| `argon2 0.6.0` | über `pakery-crypto`; Profil §4; KAT gegen Referenz-C-Implementierung. |

`tools/advisory_check.py` prüft das Lockfile gegen eine lokale RustSec-advisory-db (geprüft: Stand `99670cf`, 2026-10-27): **123 Pakete, kein Treffer**. Gegenprobe: ein Lockfile mit crypto-bigint 0.7.5, ctutils 0.4.3, snow 0.9.4, curve25519-dalek 4.1.2 wird mit allen vier Advisories gemeldet. `cargo-audit` selbst wurde nicht ausgeführt.

Bewertete, aber verworfene Alternativen (Details Revision 1): RustCrypto `spake2 0.4.0` (nicht RFC 9382, keine Bestätigung), `opaque-ke 4.0.1` (Audit nur v0.5–v1.2; aPAKE-Registrierung passt nicht), BoringSSL SPAKE2+ (bessere Herkunft, interne API, hoher Build-/Größenaufwand – weiterhin die ernsthafteste Alternative), CPace (Draft), JS-SPAKE2-Pakete (veraltet, nicht RFC-konform).

## 3. Protokollversion 2 (Komposition, keine eigene Kryptografie)

```
M0 K→H  "KHP2" 01 | kadri_pub[32]
M1 H→K  "KHP2" 02 | pairing_id[16] | helper_pub[32] | pB[65]
M2 K→H  "KHP2" 03 | pairing_id[16] | pA[65] | cA[32]
M3 H→K  "KHP2" 04 | pairing_id[16] | cB[32]
HS1 K→H "KHC2" 10 | registration_id[16] | Noise msg1 (48 B, leere Nutzlast)
HS2 H→K "KHC2" 11 | session_id[16]      | Noise msg2 (48 B, leere Nutzlast)
REC     "KHC2" 20 | session_id[16]      | Noise-Transportnachricht 16..65 535 B
```

- SPAKE2 (RFC 9382, P256-SHA256-HKDF-HMAC), Kadri = A, Helper = B. `A = "kadri-ext-x25519/2:" ‖ kadri_pub`, `B = "kadri-helper-x25519/2:" ‖ helper_pub`, `AAD = "kadri-helper-pairing-poc/2" ‖ pairing_id`. Der Helper zieht `pairing_id` vor seinem SPAKE2-Vorgang, verifiziert `cA` vor Herausgabe von `cB`. `Ke` bleibt ungenutzt (der Kanal hat eigene Schlüssel aus Noise).
- Schlüsselvalidierung (beide Seiten, alle Stellen): kanonische Kodierung, auf der Hauptkurve, nicht kleiner Ordnung, nicht der eigene Schlüssel – über `curve25519-dalek` (`to_edwards`, `is_small_order`, Rundweg-Kanonizität).
- `Noise_KK_25519_ChaChaPoly_SHA256`, Prolog `"kadri-helper-channel-poc/2" ‖ registration_id`. Keine Aushandlung, kein Fallback. Jede Verbindung neuer Handshake.
- Anwendungsrecords: Kontrolle ≤ 16 KiB, Medien ≤ 60 KiB (61 455 B Klartext + 16 B Tag), Ende-Record; exakte Längenprüfung.

## 4. Argon2-Profil (für Protokollversion 2 fixiert)

| Parameter | Wert |
| --- | --- |
| Funktion / Version | Argon2id, Version 0x13 (19) |
| Implementierung | `argon2 0.6.0` über `pakery-crypto 0.6.2` (`Argon2idKsfWithParams<PocArgon2>`) |
| Passwort | genau 8 ASCII-Ziffern (`0x30`–`0x39`), keine Normalisierung, alles andere wird abgelehnt |
| Salt | **16 Nullbytes**, in pakery-crypto 0.6.2 fest verdrahtet (RFC 9807 §7-Wert) |
| Speicher / Iterationen / Parallelität | m = 8192 KiB, t = 2, p = 1 |
| Secret / Associated Data | keine |
| Ausgabelänge | 64 Byte |
| Reduktion | 64 Byte als **Big-Endian**-Ganzzahl (512 bit) modulo der P-256-Gruppenordnung n; Kodierung des Skalars 32 Byte Big-Endian |
| KAT | 4 Codes, erzeugt mit `argon2-cffi 25.1.0`/`argon2-cffi-bindings 26.1.0` (Referenz-C `libargon2`) und Python-Ganzzahlen, unabhängig von pakery/argon2-rs |

**Abweichungen gegenüber RFC 9382 (präzise, soweit ohne RFC-Volltext prüfbar):** RFC 9382 schreibt `w = MHF(pw) mod p` vor (p dort die Gruppenordnung) und legt die konkrete MHF und ihre Parameter in die Hand der Anwendung. Unsere Festlegungen sind daher Profilentscheidungen, keine Abweichung vom Protokollkern: (a) fester Null-Salt – vertretbar, weil der Code einmalig, zufällig und nirgends gespeichert ist und SPAKE2 keine Offline-Prüfung aus Mitschnitten erlaubt; (b) moderate Kosten, weil Online-Versuche ohnehin auf einen je Fenster begrenzt sind (15–21 ms nativ gemessen); (c) 512-bit-Ausgabe mit Reduktion, damit der Bias vernachlässigbar ist; (d) `Ke` ungenutzt; (e) Identitäten tragen Schlüssel, AAD trägt die Pairing-ID. Der genaue RFC-Wortlaut zu MHF-Ausgabelänge und -Wahl konnte hier nicht zitiert werden (siehe §9, RFC-Quellen) und ist im nächsten Review gegen §3.2 zu prüfen.

## 5. Fehlersemantik

Jeder Fehler eines Kopplungsvorgangs (`KadriPairing`, `HelperPairingWindow`) ist terminal (Fenster „verbrannt“ bzw. Vorgang „Failed“). Jeder Fehler in `Channel` – falsche Magic/Typ, falsche Session-ID, Länge (Header, < Tag, > 65 535 B), Authentisierungsfehler (Manipulation, Abschneiden, Replay, Umordnung, Reflexion), authentisierter aber ungültiger Anwendungsrecord, übergroßer `seal` – setzt den Transportzustand auf `None`, bevor der Fehler zurückkehrt; danach liefern `seal`/`open` nur noch `WrongState`, ohne die CipherStates zu berühren (keine Fortsetzung, keine weitere Nonce-Nutzung). `Initiator::finish` ist einmalig. Der Helper (`HelperHost`) entfernt die betroffene Sitzung; unbekannte Session-IDs ändern nichts. Kadri verwirft die Sitzung nach jedem Fehler und baut nur durch einen neuen Handshake wieder auf.

Abwägung: Wer eine Session-ID kennt, kann diese Sitzung durch einen Müll-Record beenden (DoS). Session-IDs sind 128-bit-zufällig und nur auf dem Loopback-Pfad sichtbar; ein fremder Portbesitzer sieht nur IDs bereits toter Sitzungen.

## 6. Helper-Sitzungen und Limits (`core/src/host.rs`)

| Grenze | Wert |
| --- | --- |
| Registrierungen | max. 4; neue Kopplung bei vollem Register scheitert (`RegistryFull`, Fenster verbrannt) |
| Pending-Registrierung (M3 verloren / nie benutzt) | verfällt nach 10 min ohne erste authentisierte Sitzung |
| Halb-offene Sitzungen | global 4, je Registrierung 2, Handshake-Frist 10 s; Druck verdrängt nur andere halb-offene |
| Etablierte Sitzungen | global 4, je Registrierung 2; Leerlauf 120 s, absolut 4 h; Verdrängung nur zugunsten einer neu authentisierten Sitzung, zuerst derselben Registrierung |
| Bestätigung | erster authentisierter Record bestätigt die Registrierung und zieht ältere Registrierungen desselben Kadri-Schlüssels zurück |
| Widerruf | entfernt Registrierung(en) und alle zugehörigen Sitzungen |

Replay-Analyse: Ein mitgeschnittenes HS1 (Noise KK Nachricht 1) ist beliebig wiederholbar und erzeugt jeweils nur eine halb-offene Sitzung samt einigen DH-Operationen (Handshake beidseitig < 1 ms nativ). Ohne Kadris privaten Schlüssel kann der Angreifer keinen Record erzeugen, der sie befördert; nach 10 s oder bei Druck verfällt sie. Folge: reine Ressourcen-/DoS-Wirkung auf neue Handshakes, keine Verdrängung etablierter Sitzungen, keine Authentisierungs- oder Vertraulichkeitsverletzung.

## 7. Pairing- und Browser-Guards – DoS vs. Authentisierung

| Prüfpunkt | Ergebnis | Einordnung |
| --- | --- | --- |
| Webseite sendet einfachen `no-cors`-POST an `/p1/pair/m0` | erreicht den Server, wird am Gate abgewiesen (`content-type`/`origin`); Fenster bleibt offen | Ohne Gate wäre es DoS (Fenster verbrennen), keine Auth-Verletzung: die Seite kann M1 nicht lesen |
| Webseite sendet POST mit `X-Kadri-Helper`/Octet-Stream | Preflight `OPTIONS` → 403 ohne CORS-Header → `fetch` scheitert, POST wird nie gesendet | Browser-Gate, **keine Authentisierung**: lokale Apps setzen beliebige Header |
| Content-Script der Erweiterung | `pair`/Test-Hooks abgelehnt (Absenderprüfung auf Erweiterungsseite), `storage.local` leer, Erweiterungs-IndexedDB nicht erreichbar | Identität/Pin nur in IndexedDB des Erweiterungsursprungs |
| Parallele Kopplung | Helper: zweites M0 → `WrongState`, erste Kopplung läuft weiter; Kadri: zweiter Vorgang → „pairing already running“ | DoS-Begrenzung |
| Verlorenes M3 | Kadri pinnt nichts; Helper-Registrierung bleibt pending und verfällt | keine Auth-Verletzung |
| Fremde App verbraucht das Fenster | Nutzer sieht Fehlschlag, neuer Code nötig; Erfolgswahrscheinlichkeit einer Vermutung 10⁻⁸ | DoS + eine Online-Vermutung |
| Low-Order-/nicht-kanonische Schlüssel | 12 bekannte Werte + High-Bit-Variante + eigener Schlüssel abgelehnt, in M0, M1, Pins und Registrierungsimport | verhindert DH mit trivialem Ergebnis |

## 8. Firefox und Chromium: ein Kern, kleine Adapter

| | Chromium 153 | Firefox Desktop 158.0.1 |
| --- | --- | --- |
| Manifest | MV3, `background.service_worker` (ES-Modul) | MV2, `background.page` mit `<script type="module">` (wie Kadris heutiger Firefox-Build) |
| CSP | `extension_pages: script-src 'self' 'wasm-unsafe-eval'; object-src 'self'` | `script-src 'self' 'wasm-unsafe-eval'; object-src 'self'` |
| Kern | identische `kadri_pairing_poc_bg.wasm` (gleicher SHA-256) und JS-Glue | dito |
| RNG | `getrandom` → `crypto.getRandomValues` | dito |
| Ablage | IndexedDB des Erweiterungsursprungs (nicht `storage.local`) | dito |
| Gemeinsamer Code | `ext/shared/background.js`, Options-, Test- und Probe-Seiten | dito |
| Browserspezifisch | nur `manifest.json` | `manifest.json`, `background.html`, Test-Startskript und Testbrücke |
| Testtreiber | Playwright 1.63 | Selenium 4.38 + geckodriver 0.37.1 |

Firefox-Besonderheiten: (1) Content-Script-Match-Patterns erlauben in Firefox keinen Port – daher `http://127.0.0.1/*` in beiden Manifesten; die Probe läuft nur auf Port 47900. (2) WebDriver darf `moz-extension://`-Seiten weder ansteuern noch skripten. Die Erweiterungsseiten werden deshalb im Firefox-Testbuild über `ext/firefox/ff-bridge.js` gesteuert (Kommandos per Loopback vom Runner; die Optionsseite füllt ihr eigenes Formular und löst ihren Button aus, statt echter Tastatureingaben). Diese Brücke und `ff-boot.js` sind reine Testinfrastruktur. Webseitenbesuche laufen über echtes WebDriver. Firefox stammt aus dem conda-forge-Paket `firefox-158.0.1-hee9eb32_0` (Mozilla-Release-Build 20261019185856; Mozilla-Downloadserver waren aus der Umgebung gesperrt).

**Firefox Android ist nicht geprüft.** Aus dem Desktop-Lauf folgt keine Android-Aussage.

## 9. Nachweise

| Bereich | Ergebnis |
| --- | --- |
| RFC 9382 Appendix B (4 Vektoren) über die genutzten Bibliothekseinstiege | PASS |
| Unabhängige Python-Gegenrechnung (inkl. M/N aus Seed regeneriert) | 36/36 PASS |
| Cacophony-Vektor `Noise_KK_25519_ChaChaPoly_SHA256` | PASS |
| Argon2-KAT (4 Codes, Referenz-C-Argon2) – Rust-Ergebnis und unabhängige Neuerzeugung identisch | PASS |
| Rust-Tests gesamt (`cargo test --locked`) | **34/34**: Protokoll 20, Host 9, Record 2, KAT 1, RFC 1, Noise 1 |
| Mutationsprobe (`tools/mutation_check.py`, 39 Mutanten) | **36 getötet, 3 begründet überlebend, 0 unerklärt** (s. u.) |
| RustSec (advisory-db `99670cf`, 2026-10-27) | 123 Pakete, kein Treffer; Gegenprobe mit 4 bekannt verwundbaren Versionen meldet alle 4 |
| E2E Chromium 153.0.8010.12 (MV3) gegen JNI-Helper | **39/39** (mit und ohne `TCP_NODELAY`) |
| E2E Firefox Desktop 158.0.1 (MV2) gegen JNI-Helper | **39/39** (mit und ohne `TCP_NODELAY`) |
| Identischer Kern | `kadri_pairing_poc_bg.wasm` SHA-256 `71c66307279ea0f176bcc1dd50d9b63332f073f174ea22cdcc6d44099f1ded50` in beiden Erweiterungen; 274 344 B (gzip 83 909 B); JNI-`.so` x86_64-Linux 826 360 B, **Android-ABIs nicht gebaut** |

E2E-Szenario (je Browser identisch, 39 Prüfungen): A) Webseite und Content-Script (Gate, Preflight, keine Erweiterungsglobals, Pairing/Test-Hooks abgelehnt, `storage.local` leer, Erweiterungs-IndexedDB unerreichbar); B) Kopplung über die Optionsseite, Pin = Helper-Schlüssel, Registrierung durch ersten authentisierten Round-Trip bestätigt; C) Kanal, vertrauliche Marker nur beim echten Helper, zwei parallele Transfers byte-genau mit ≤ 60-KiB-Records, Speicher konstant; D) manipulierter Record → Sitzung tot; E) Helper-Neustart → alte Sitzung 404, nicht wiederverwendet, neuer Handshake; F) nicht-kanonischer Pin lokal abgelehnt, gültiger falscher Pin vom echten Helper abgelehnt; **G) lebende authentisierte Sitzung → echter Helper beendet → fremder Listener auf dem Port → Request scheitert, Sitzung verworfen, kein weiterer Request ohne neuen Handshake, Handshake und Kopplung mit geratenem Code scheitern, Pin unverändert, der Listener erhielt nur Chiffrat (`/p1/ch/rec`) und Handshake-/Pairing-Bytes ohne URL, Cookie, Code, Marker oder Bearer**; H) falscher Code, verbranntes Fenster, Widerruf beendet laufende Sitzung, widerrufene Registrierung kann nicht handshaken, nur ein Kopplungsvorgang gleichzeitig, Neukopplung erzeugt neue Registrierung.

Begründete Mutations-Überlebende: (1) Responder-Prüfung „Handshake-Payload leer“ entfernt – redundant, HS1 hat eine feste Länge ohne Platz für Nutzlast, der äußere Längencheck greift (Test `handshake_payloads_must_be_empty` belegt das); (2) „Initiator einmalig“ – Kontrollmutant ohne Wirkung, `take()` konsumiert bereits; (3) „Widerruf behält Sitzungen“ – `sweep()` entfernt Sitzungen widerrufener Registrierungen vor jeder Recordverarbeitung. Gegenüber Revision 1 neu getötet u. a.: Deadlines je Phase inkl. Off-by-one, Pairing-ID-Bindung, Prologbindung, Schlüsselvalidierung (inkl. Reflexion des eigenen Schlüssels), Argon2-Parameter, Codegrenze, alle Terminalfälle, exakte Recordlängen, Host-Limits und Timeouts.

**RFC-9382-Quellen:** Ein direkter Abgleich mit einer offiziellen Quelle war erneut nicht möglich: rfc-editor.org, www.ietf.org, datatracker.ietf.org und der JPNIC-RFC-Spiegel (ftp.nic.ad.jp) wurden von der Egress-Policy abgelehnt (2026-10-27). Unverändert gilt: Vektoren aus pakery-Repo-Commit 6abbf82, unabhängig nachgerechnet (36/36), M/N aus dem RFC-Seed regeneriert. Der Textabgleich gegen RFC 9382 Appendix B bleibt eine 5-Minuten-Aufgabe für den nächsten Review.

## 10. Durchsatz und Speicher – nach Ebenen getrennt

| Ebene | Messung | Aussage |
| --- | --- | --- |
| 1. Kryptografie allein (nativ, `examples/bench.rs`, opt-level s) | 60-KiB-Records seal+open ≈170 MiB/s; KK-Handshake beidseitig 0,65 ms; `password_scalar` 15 ms | Nicht der Engpass. Der Review maß isoliert ≈484 MiB/s; unsere Zahl enthält Record-Kodierung und Kopien. |
| 2. HTTP/Socket | eine neue HTTP/1.1-Verbindung je 60-KiB-Record, ACK vor dem nächsten | Kosten pro Record, aber nicht die Hauptursache der 1,2 MiB/s |
| 3. JVM-Stand-in | E2E-Transfers (8,47 MB, zwei parallel): Chromium 1,1 → 8,0 MiB/s, Firefox 1,1 → 6,1 MiB/s (ohne → mit `TCP_NODELAY`); in Einzelläufen zuvor 9,6/10,0 MiB/s | Der Einbruch kam vom Nagle-/Delayed-ACK-Zusammenspiel im JDK-`HttpServer`; mit `-Dsun.net.httpserver.nodelay=true` 6–10 MiB/s (Streuung zwischen Läufen und Browsern). **Die Aussage in Revision 1 („neue HTTP-Verbindung je Record“) war falsch und ist hiermit korrigiert.** |
| 4. Echter Android-`MiniServer` | nicht gemessen | Eigener Socket-Code; Nagle-Verhalten, Puffer und Heap sind dort separat zu messen (#5). Keine Übertragung der JVM-Zahlen. |

Speicher: WASM-Linearspeicher 1,06 MiB initial → 9,19 MiB nach Kopplung (Argon2-Hochwasser von 8 MiB; wächst danach nicht), unverändert während zweier paralleler Transfers. JNI: Argon2 belegt transient 8 MiB nativ außerhalb des Java-Heaps (`-Xmx32m` im Stand-in ausreichend).

## 11. Offene Punkte und Restrisiken

1. **Kein unabhängiges Audit** von `pakery-*` 0.6.2, `p256 0.14.0`, `snow 0.10.0` und dieser Komposition (inkl. Host-Sitzungslogik). Pakery ist jung und ändert sich schnell.
2. RFC-Textabgleich der Vektoren und von §3.2 (MHF) noch offen (Netzwerkpolicy).
3. Android, Vivaldi Android und Firefox Android ungeprüft (NDK-Build, Keystore-Umhüllung, Gerät, LNA/Loopback aus dem Erweiterungshintergrund, Lebenszyklus).
4. DoS durch lokale Apps bleibt möglich (Port belegen, Fenster verbrauchen, Handshake-Pool füllen, bekannte Session-IDs beenden) – keine Vertraulichkeits- oder Authentisierungsverletzung.
5. Kompromittierter Browser/Erweiterungsprozess, Helper oder Android liegen außerhalb des Modells; X25519-Privatschlüssel liegen im PoC im Klartext in Erweiterungs-IndexedDB bzw. in einer Datei des Stand-ins.
6. Userscript-Manager: kein vertrauenswürdiger Bootstrap/WASM-Kontext belegt.
7. Streaming (#5): Proxy-Route puffert weiterhin 4-MiB-Fenster; persistente Verbindung/Batching ist ein eigenes Design.
8. Testbrücke in Firefox ersetzt echte Tastatureingaben auf Erweiterungsseiten.

## 12. Vor einer Android-/Vivaldi-Prüfung nötig

1. Unabhängiger Re-Review dieser Revision (Komposition v2, `host.rs`-Policy, Fehlersemantik, Tests).
2. Entscheidung über den Audit-Pfad: gezieltes Audit von pakery-spake2 (+genutzte Teile von core/crypto) und snow, oder Evaluation von BoringSSL-SPAKE2+.
3. RFC-9382-Textabgleich (Vektoren, §3.2).
4. Freigabe einer Wegwerf-Test-App (Testsignatur, kein Produktionssignierer) und eines minimalen NDK-Build-Pfads (cargo-ndk, ABI-Liste, Größen) – weiterhin ohne Produktionsänderung.
5. Festlegung der Android-Schlüsselablage (Keystore-umhülltes X25519) und der Vivaldi-Hintergrundfähigkeiten (MV3-SW, WASM-CSP, Loopback).

## 13. Bedingungen vor einer öffentlichen Veröffentlichung

Abgeschlossenes unabhängiges Audit der gepinnten Bibliotheksversionen und der Komposition; RFC-Abgleich; produktive Umsetzung erst nach erneuter Eigentümerfreigabe (Policy bleibt bis dahin `UNAPPROVED`); kein paralleler Bearer-/Legacy-Pfad; Kadri-Schlüssel nur im Erweiterungsursprung; echte Android-/Vivaldi-/Firefox-Android-Nachweise inkl. 32-MiB-Heap und nativer Speicher; Streaming-Design #5; reproduzierbare, gesperrte Builds mit Advisory-Prüfung in CI; Signier-Thema #3 getrennt gelöst (kein kompromittierter Signierer); dokumentierter Widerruf/Neukopplung für Nutzer.

## Reproduktion

```sh
tools/build.sh /tmp/poc-build            # --locked; JNI + eine WASM-Datei für beide Erweiterungen; keine APK
python3 -I vectors/independent_check.py vectors/rfc9382-appendix-b.json
python3 -I vectors/argon2_kat.py /tmp/kat.json && diff <(python3 -m json.tool /tmp/kat.json) <(python3 -m json.tool vectors/password-scalar-kat.json)
(cd core && cargo test --locked && cargo run --locked --release --example bench)
python3 -I tools/mutation_check.py
python3 -I tools/advisory_check.py core/Cargo.lock <advisory-db>
node browser/run.mjs chromium /tmp/poc-build "$PWD/core/target/release/libkadri_pairing_poc.so" [--nodelay]
FIREFOX_BIN=… GECKODRIVER=… SELENIUM_DIR=… node browser/run.mjs firefox /tmp/poc-build "$PWD/core/target/release/libkadri_pairing_poc.so" [--nodelay]
```
