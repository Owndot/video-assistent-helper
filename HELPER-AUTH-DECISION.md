# Helper-Authentifizierung: Entscheidungsvorschlag, keine Implementierung

## Entscheidung für die erste öffentliche GitHub-Beta

Nach Astra-Prüfung der Stände Helper `e8c9584` und Kadri `cc14bcd` akzeptiert die Projektfreigabe die fehlende gegenseitige kryptografische Serverauthentifizierung als begrenztes Restrisiko. [Helper #4](https://github.com/Owndot/video-assistent-helper/issues/4) bleibt offen. Aktuelle Funktionsbeschreibung, Bedrohungsgrenze, Reset-Anleitung und gezielte Nachweise: [HELPER-BETA-SECURITY.md](HELPER-BETA-SECURITY.md). Kein neues Protokoll wird in dieser Beta-Runde eingeführt.

## Vorschläge für eine separat freizugebende spätere Phase

Die bestehende Authentifizierung bleibt in dieser Runde unverändert. Die Auswahl eines Verfahrens und jedes neue Protokoll benötigen eine separate Freigabe.

Geeignete standardisierte Optionen sind TLS zu einem verlässlich authentisierten lokalen Dienst mit zufälligen, widerrufbaren Zugangsdaten oder HTTP Message Signatures ([RFC 9421](https://www.rfc-editor.org/rfc/rfc9421)) mit einer etablierten Implementierung und standardisiertem HMAC-SHA-256 bzw. asymmetrischem Signaturverfahren. Ein Body würde über Content-Digest ([RFC 9530](https://www.rfc-editor.org/rfc/rfc9530)) eingebunden. Keine selbst erfundene Challenge-/Signaturkonstruktion und kein bloßes Hashen eines Tokens. OAuth-Loopback-Codefluss mit PKCE ([RFC 8252](https://www.rfc-editor.org/rfc/rfc8252), [RFC 7636](https://www.rfc-editor.org/rfc/rfc7636)) ist eine weitere Option, falls mehrere Clients und ein separater Autorisierungsdialog gewünscht sind; er ersetzt nicht die Authentisierung des Servers.

## API-Grenzen vor der Auswahl klären

| Ausführungskontext | HTTP-Seite | Geeignete API |
| --- | --- | --- |
| Chromium-/Firefox-Seiten-JavaScript | WebCrypto `subtle` ist auf gewöhnlichem unsicherem HTTP nicht zugesichert/verfügbar; `getRandomValues` ist kein Ersatz für eine Signatur-API | HTTPS/vertrauenswürdiger Kontext für `subtle` |
| Kadri-Extension-Hintergrund | Herkunft der aufrufenden HTTP-Seite ändert den privilegierten Hintergrundkontext nicht | WebCrypto im Hintergrund; ggf. allowlist-beschränktes Native Messaging |
| Violentmonkey Chromium/Firefox, `@inject-into content` | GM-Speicher/GM-Netzwerk funktionieren auch auf gematchten HTTP-Seiten; daraus folgt keine zugesicherte `crypto.subtle`-Verfügbarkeit | Konkreten Manager, Browser und Ausführungskontext vor Protokollwahl testen |
| Tampermonkey | Sandbox-/Browser-/Plattformunterschiede; GM-XHR ist keine allgemeine Kryptografie- oder Native-Messaging-API | Eigenen HTTP-/HTTPS-Kompatibilitätsnachweis verlangen |
| Greasemonkey | Kein Kompatibilitätsversprechen dieses Projekts | Separate API-/Speicher-/HTTP-Validierung nötig |

[WebCrypto Secure-Context-Vorgabe](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto) gilt auch bei der Planung eines Userscripts; isolierte Ausführung bedeutet nicht automatisch sicheren Ursprung. Loopback-HTTP kann als potentiell vertrauenswürdig gelten, während die fremde HTTP-Seite es nicht ist. Kein stilles Abschalten der Authentifizierung, wenn eine API fehlt. Keine automatische Kryptografie aus nachgeladenem Fremdcode als Fallback. Vor einer Zusage ist eine Matrix aus Chromium/Firefox, Android/Desktop, Manager-Version, HTTP/HTTPS und tatsächlichem Sandboxmodus erforderlich; diese Matrix ist hier nicht als bestanden behauptet.

## Erforderliche Protokollentscheidungen

Bei Message Signatures müssten Methode, normalisiertes Ziel einschließlich Query, Host/Port, Body-Digest, signierte Zeitgrenzen, `keyid` und ein pro Anfrage eindeutiger Wert verbindlich abgedeckt werden. Der Server muss Replay-Zustand atomar und begrenzt speichern, zulässige Zeitabweichung festlegen und Wiederholungen/Parallelität eindeutig behandeln. Ein kleiner Gültigkeitszeitraum allein verhindert Replay nicht. Änderungen am signierten Ziel durch Redirects dürfen nicht automatisch neu signiert werden.

Pairing braucht eine ausdrücklich bestätigte, gegen Mitlesen und Austauschen geschützte Erstübertragung oder einen separat überprüften Fingerabdruck. Fragmente vermeiden das Senden an den HTTP-Server, sind aber kein eigenständiger sicherer Schlüsseltransport: Seitenskripte, History, Zwischenablage und manipuliertes HTTP bleiben zu betrachten. Schlüssel gehören ausschließlich in privilegierten Managerspeicher/OS-Schlüsselspeicher, niemals in DOM, URL-Query oder Seiten-LocalStorage. Pairing-Codes müssen kurzlebig, einmalig und gegen Versuche begrenzt sein; Widerruf und getrennte Schlüssel pro Client sind vorzusehen.

Serverwechsel muss neu bestätigt werden. Host, Schema, Port, authentisierte Serveridentität und Schlüsselkontext müssen zusammenpassen; gleicher Port ist kein Identitätsnachweis. Kein Schlüsseltransfer an neue Endpunkte über Redirects, keine automatische Wiederverwendung eines Vertrauensentscheids beim Austausch des Servers. Rotation und Gerätewechsel benötigen ein separat spezifiziertes Re-Pairing-/Widerrufsverfahren.
