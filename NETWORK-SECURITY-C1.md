# Sichere Netzwerkschicht (Phase C1)

Stand: Draft-PR „hardened HTTP client (Phase C1)“ auf Basis von Phase B (OkHttp 5.5.1, R8). **Noch keine
Download-Integration, kein Browser-Push; die Phase-2-Steuerung ist unverändert.** Produktionsstatus: UNAPPROVED.

## Aufbau

Paket `de.evp.helfer.net` – der einzige Weg des Produktivcodes zu OkHttp (CI-Sperre `tools/check-network-layer.sh`):

| Klasse | Aufgabe |
|---|---|
| `SafeHttp` | Android-Einstieg: ein gemeinsamer Client mit strengen Regeln; `OkHttp.initialize(context)`; NAT64-Präfix des aktiven Netzes (ab API 30, Berechtigung `ACCESS_NETWORK_STATE`) |
| `SafeHttpClient` | einziger `OkHttpClient` (privat, nie herausgegeben); `get(url, [range], maxBodyBytes, timeoutMs)` mit manuell geprüften Weiterleitungen |
| `SafeResponse` | Status, wenige Metadaten-Header, einmal lesbarer, größenbegrenzter Rumpf-Stream |
| `NetRules` | Schema, Port, Zugangsdaten, Hostname, Adresse – für Ausgangs-URL, Weiterleitung, DNS-Antwort, Verbindungsaufbau, verbundene Gegenstelle |
| `AddressPolicy` | nur global erreichbare Unicast-Adressen (siehe unten) |
| `GuardedSocketFactory` | Prüfung unmittelbar vor `connect()`, Sockets mit `Proxy.NO_PROXY` |
| `NetBlockedException` | jede Ablehnung mit Grund (`Reason`), nie stilles Ausweichen |

### Vier unabhängige Prüfschichten

Jede Schicht lehnt ein unzulässiges Ziel für sich ab (einzeln getestet und per Mutationsprobe als wirksam belegt):

1. **URL** (Ausgangs-URL und jede Weiterleitung): nur `http`/`https`, nur Port 80/443, keine Zugangsdaten in der URL,
   keine lokalen Namen (`localhost`, `*.localhost`, `*.local`, `*.lan`, `*.internal`, `*.home.arpa`, einteilige Namen),
   IP-Literale nach `AddressPolicy`; Hosts, deren letztes Label eine Zahl ist (WHATWG-IPv4-Formen wie `2130706433`,
   `0x7f.1`, `0177.0.0.1`, `127.1`), nur als strikte Punktschreibweise.
2. **DNS** (`Dns` von OkHttp): alle Antworten müssen zulässig sein, sonst wird der Name als Ganzes abgelehnt.
3. **Socket**: `GuardedSocket.connect()` prüft genau die Adresse und den Port, zu denen verbunden wird – für jeden
   Verbindungsweg von OkHttp (DNS-Ergebnis, IP-Literal, Fast-Fallback, neue Pool-Verbindung) – und danach, dass die
   verbundene Gegenstelle dieselbe ist. Kein TCP-SYN zu einem unzulässigen Ziel.
4. **Netzwerk-Interceptor**: vor jeder Anfrage die tatsächlich verbundene Gegenstelle (`connection().socket()`), auch
   über eine wiederverwendete Pool-Verbindung; Protokoll HTTP/1.1; keine Zugangsdaten-Header; höchstens eine
   Netzwerkanfrage je Aufruf.

### Kritische Prüfung der Socket-Factory

Die Socket-Factory allein sichert **nicht** alle Wege ab. Gefundene Lücken und ihre Schließung:

| Weg | Problem | Lösung |
|---|---|---|
| `new Socket()` | folgt dem `ProxySelector` für `socket://` – mit `socksProxyHost` geht die Verbindung über einen SOCKS-Proxy, die Prüfung sieht nur das Ziel, nicht den Proxy | Sockets mit `new Socket(Proxy.NO_PROXY)`; Gegenprobe im Test: ein gewöhnlicher Socket nutzt den SOCKS-Selector, der geprüfte nicht |
| SOCKS-/HTTP-Proxy in OkHttp | SOCKS-Routen erzeugen `new Socket(proxy)` an der Factory vorbei | `.proxy(Proxy.NO_PROXY)`; System-Eigenschaften und `ProxySelector.setDefault` im Test wirkungslos |
| IP-Literal in der URL | OkHttp fragt bei Literalen kein `Dns` | URL-Prüfung + Socket-Prüfung |
| HTTP/2-Verbindungsbündelung | eine bestehende Verbindung kann für einen anderen Hostnamen wiederverwendet werden | nur HTTP/1.1 (ALPN bietet nur `http/1.1`, im Test geprüft) |
| Pool-Wiederverwendung | keine neue Verbindung, also keine Socket-Prüfung | Interceptor prüft die Gegenstelle bei jeder Anfrage; die Verbindung wurde beim Aufbau geprüft, Rebinding kann sie nicht umlenken |
| Interceptor-„Superkräfte“ (OkHttp 5.4: Interceptoren dürfen DNS/Socket-Factory je Aufruf ersetzen) | fremder Interceptor könnte Prüfungen umgehen | Client und Builder nie herausgegeben; keine fremden Interceptoren; CI-Sperre |
| andere `createSocket(host, port…)`-Überladungen | würden ohne Prüfung verbinden | implementiert über den geprüften `connect()` |

### Adressregeln (`AddressPolicy`)

- IPv4 gesperrt: 0/8, 10/8, 100.64/10, 127/8, 169.254/16, 172.16/12, 192.0.0/24, 192.0.2/24, 192.88.99/24, 192.168/16,
  198.18/15, 198.51.100/24, 203.0.113/24, 224/4, 240/4 (inkl. Broadcast).
- IPv6 zulässig nur 2000::/3, davon gesperrt 2001::/23 (Teredo, ORCHID, Benchmark …), 2001:db8::/32, 3fff::/20, 2002::/16.
- Eingebettetes IPv4 nach dem IPv4-Teil: IPv4-mapped (`::ffff:a.b.c.d`, auch als `Inet6Address`), NAT64 `64:ff9b::/96`
  und das netzspezifische NAT64-Präfix (/96; andere Längen ganz gesperrt). Gesperrt: IPv4-kompatibel (`::/96`),
  `::ffff:0:0:0/96`, `64:ff9b:1::/48`, ULA, Link-/Site-Local, Multicast, Discard.

### Weitere Eigenschaften

- Weiterleitungen nur manuell (301/302/303/307/308), jede geprüft, kein HTTPS→HTTP, höchstens 5.
- Keine Cookies (`CookieJar.NO_COOKIES`), keine Authenticator, verbotene Header (`Cookie`, `Authorization`,
  `Proxy-Authorization`, `X-Forwarded-For`, `Forwarded` …) werden abgelehnt; die API nimmt keine Header an.
- Keine automatischen Wiederholungen: `retryOnConnectionFailure(false)`; `Retry-After` bei 503 wird entfernt (OkHttp
  würde sonst bei `Retry-After: 0` erneut senden); zusätzlich lehnt der Interceptor jede zweite Netzwerkanfrage
  innerhalb eines Aufrufs ab (Zähler im Request-Tag, den OkHttp in Folgeanfragen übernimmt).
- Zeiten: Verbindung 10 s, Lesen 30 s, Schreiben 15 s; Gesamtdauer je Aufruf vom Aufrufer (≤ 6 h), einschließlich
  Weiterleitungen und Rumpf.
- Größen: Antwort-Header ≤ 32 KiB (OkHttp liest höchstens 256 KiB); Rumpf ≤ `maxBodyBytes` (deklarierte Länge vorab,
  gestreamte Länge laufend). `Accept-Encoding: identity`, kein transparentes gzip.
- TLS: System-Vertrauensspeicher, OkHttp-Hostnamenprüfung, TLS 1.2/1.3 (`MODERN_TLS`).
- Streaming: HTTP/1.1 mit Gegendruck über TCP; kein 16-MiB-Empfangsfenster wie bei HTTP/2.

## Tests

| Test | Umfang |
|---|---|
| `tests/java/net/run.sh` (JVM, echte Sockets, 48 MiB Heap) | 33 Tests: Adressregeln (75 Fälle), URL-Regeln (25), jede Schicht einzeln, DNS-Rebinding, Mischantworten, IPv6-Sonderfälle, 10 Weiterleitungsfälle, TLS inkl. ALPN und Downgrade, System-Proxys/ProxySelector inkl. SOCKS-Gegenprobe, keine Wiederholungen (503/408/421/500/Abbruch), Rückfallzähler, Zugangsdaten-Header, Zeitgrenzen (stummer Server, tröpfelnde Header, tröpfelnder Rumpf), Header- und Rumpfgrenzen, kein gzip, Pool-Wiederverwendung, 256 MiB Streaming |
| `tests/java/net/mutation.py` | 23 Mutationsproben – jede abgeschwächte Schutzmaßnahme muss die Tests scheitern lassen |
| `tools/check-network-layer.sh --self-test` | 10 eingeschleuste Verstöße gegen die CI-Sperre |
| `SafeHttpInstrumentedTest` (Emulator API 34 und 26, geschrumpfte APK) | echte HTTP-/HTTPS-Anfragen, strenger Produktivclient gegen lokale/private Ziele (inkl. `10.0.2.2`, Metadaten-Adresse), Socket-Schicht auf dem Android-Pfad, Rebinding, Proxys, Wiederholungen, RAM, NAT64-Abfrage, Startkomponente, R8-Ausgabe |

## Verbleibende Risiken

| # | Risiko | Bewertung |
|---|---|---|
| N1 | Netzspezifisches NAT64-Präfix erst ab API 30 bekannt; davor wäre eine per DNS64 erzeugte Adresse mit eingebettetem privatem IPv4 im NAT64-Präfix des Netzbetreibers zulässig (Ziel liegt im Betreibernetz, nicht im Heimnetz) | gering; nur IPv6-only-Netze mit NAT64 unter Android 8–10 |
| N2 | Unverschlüsseltes HTTP bleibt zulässig (Medienserver; `usesCleartextTraffic=true` besteht schon) | gewollt; nur Weiterleitung HTTPS→HTTP gesperrt |
| N3 | VPN-Apps oder systemweite Weiterleitung liegen unterhalb der App | außerhalb des Bedrohungsmodells |
| N4 | Der bestehende Download-Code (`Fetcher`/`Downloads`, `HttpURLConnection`, DownloadManager) ist **nicht** abgesichert | bis zur Umstellung in C2 unverändert; nicht über die neue Schicht |
| N5 | Andere Hosts können über öffentliche Weiterleitungsdienste erreicht werden | zulässig, sofern das Ziel öffentlich ist; jede Weiterleitung wird geprüft |
| N6 | Gerätetests nur auf x86_64-Emulatoren (API 34, 26) | arm64-Gerätetest vor einer Freigabe |

## Dependency Verification

`gradle/verification-metadata.xml` (SHA-256, 338 Komponenten, 589 Artefakte) für alle Gradle-Aufgaben von CI und
Release-Weg, inkl. aapt2 für Linux, macOS und Windows. Neu erzeugen nur mit `tools/update-verification-metadata.sh` und
den Diff prüfen. Geprüft: Build aus leerem Gradle-Cache besteht; eine geänderte okhttp-android-Prüfsumme lässt den
Build scheitern. Andere Gradle-Aufgaben (z. B. `lint` anderer Varianten) brauchen ggf. neue Einträge.
