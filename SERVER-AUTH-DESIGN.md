# Helper #4 — vereinfachter Entwurf für ein eigenes Gerät

**Entscheidungsvorlage für Astra, keine Implementierungsfreigabe.** Es gibt genau einen Nutzer, keine zu erhaltenden Fremdinstallationen und keinen Legacy-Migrationsbedarf. Alte Kopplungen dürfen gelöscht werden. Dieser Stand ersetzt die frühere JWE-Empfehlung in PR #20. Produktionscode, minSdk, Schlüssel und Produktions-Policy bleiben unverändert.

**Vorschlag:** aktuelles Kadri/Vivaldi + eigener Helper über **normales lokales HTTPS/TLS 1.3**, mit bewusst eingerichteter privater Zertifikatsvertrauensstellung und anschließendem einmaligen Pairing-Code. **JWE vorerst verwerfen.** TLS vereinfacht Kryptografie und Medienübertragung. Die dafür nötige lokale CA ist allerdings eine zusätzliche Vertrauensentscheidung. Ohne deren Zustimmung und den tatsächlichen Samsung/Vivaldi-Nachweis ist auch dieser Kandidat **nicht implementierungsreif**. Kein automatischer HTTP- oder JWE-Rückfall.

## 1. Nachgewiesener Ausgangspunkt und Bedrohungsgrenze

Helper main bb8a42d83eac108c098600e50a20208446134e65; Kadri main d52faae455c4518c843bed4c50748c87aea1bf40. Heute erzeugt Keys 128 Zufallsbits in privaten Preferences. Die Fragmentkopplung führt zum ungeprüften X-EVP-Key-Request. Kadri liest zwar den aktuellen privilegierten Storage-Wert pro Request, authentisiert aber den Portbesitzer nicht. Downloadparameter können signierte URLs, Seiten-URL, Titel, User-Agent und lesbare Site-Cookies enthalten; /push trägt Medienbytes. Loopback, Host-/CORS-Regeln und Bind-Konfliktmeldung ersetzen keine Serveridentität.

[CI 40514903992](https://github.com/Owndot/video-assistent-helper/actions/runs/40514903992), Testcommit 3e93e7c135c4d3cd01b2f053662206cf98fc524d: echter HTTP-Listener auf 127.0.0.1:47811 empfängt bei tatsächlicher helper()-Funktion und aktuellen Chrome-/Firefox-Storage-Readern synthetischen Schlüssel, Cookie-/URL-Marker und 20 Uploadbytes; gefälschtes ok wird akzeptiert. Verwendet wurden Node-GM-/Storage-Adapter, **keine installierten Browser/Manager oder Android-Hardware**. Echte MiniServer-/Keys-Hosttests für Portkonflikt, Reset, Altschlüssel und Restart/Re-Pairing bestehen. Lokales Binden wurde einmalig mit Sandbox-EPERM verhindert; keine Wiederholung.

| Angreifer | Garantie / Grenze |
| --- | --- |
| Gewöhnliche bösartige Webseite | Kein automatischer Zugriff auf Extension-/GM-Storage. Sender-, Origin-, Frame-, Host- und Cookie-Gates bleiben erhalten; keine allein webbasierte Schlüsselauslese behauptet. CORS verhindert nicht jede Anfrage/DoS. |
| App / Prozess mit Loopback-Zugriff | Kann Port besetzen und Verfügbarkeit stören. Ohne gültigen TLS-Privatschlüssel oder missbrauchbaren Browser-Vertrauensanker soll sie keinen Auth-/Medienklartext erhalten. |
| App mit eigener bereits vertrauter CA, Accessibility, Overlay- oder Tastaturkontrolle | Zusätzliche Fähigkeit, die TLS/Pairing unterlaufen kann. Nicht mit bloßer INTERNET-Berechtigung gleichsetzen. Vorhandene lokale CA-Vertrauensanker müssen geprüft werden. |
| Kompromittierter Browser/privilegierte Erweiterung | Kann Token/Klartext missbrauchen; außerhalb der Kanalgarantie. |
| Kompromittierter Helper/Android | Keystore heilt keinen kompromittierten Endpunkt; keine umfassende Gerätesicherheitsgarantie. |

Ein erfolgreicher Challenge-/ping-Request und danach HTTP mit Bearer wäre weiterhin unsicher. HTTPS schützt stattdessen **jeden wirklichen Request auf seiner Verbindung**. Ein neuer Portbesitzer muss selbst eine gültige TLS-Verbindung anbieten; er kann keine gültigen Records in die alte Verbindung einschieben. Ein verschlüsselndes Relais erhält ohne Schlüssel keinen Klartext. Normale Browser-Zertifikatsprüfung ist **kein exklusiver SPKI-Pin auf unsere CA**: eine weitere missbrauchbare vertraute CA bleibt ein Gegenangriff.

## 2. Mindestversion: Android 13 vorschlagen, nichts ändern

| Grundlage | Bewertung |
| --- | --- |
| Aktueller Build | minSdk=26, targetSdk=34; beides bleibt unverändert. |
| Native TLS-APIs | TLS 1.3 ab Android 10 / API29 öffentlich unterstützt/standardmäßig aktiv. Technische Untergrenze ohne zusätzlichen TLS-Provider. EC-/TLS-Keystore verlangt hier keine neue ECDH-/PAKE-Spezial-API. |
| Produktuntergrenze | **Android 13 / API33 empfehlen.** Bewusste Supportentscheidung für den kleinen aktuellen Nutzerkreis, weniger Alt-Lifecycle-/Storage-/Berechtigungsvarianten. Keine Behauptung, AES oder TLS benötigten API33. |
| Eigenes Gerät | Samsung dokumentiert die S26-Ultra-Modellfamilie mit Android 16 / API36. Konkreter Eigentümer-Build/Patchstand hier nicht ausgelesen; API33 liegt darunter. |

Android 8/9 können künftig bewusst entfallen; 10–12 wären technisch denkbar, müssen aber nicht jetzt mitgetragen werden. Ein API-Level garantiert keine aktuellen Sicherheitspatches. Erste Zielumgebung ist das aktuelle eigene Samsung mit aktuellem Vivaldi, keine historische Matrix. **Keine minSdk-Änderung ohne Eigentümerentscheidung.**

Android 10 schränkt Clipboard-Zugriffe ein, Android 13 ergänzt weitere Clipboard-Funktionen. Daraus folgt kein sicherer Geheimnis-Import: Vordergrund, Tastatur und zusätzliche Berechtigungen bleiben relevant. Das neue Pairing benutzt **überhaupt kein Clipboard**. Höheres minSdk ersetzt weder sicheren Trust-Bootstrap noch Pairing.

## 3. Alternativen: warum TLS bevorzugen

| Variante | Einzelnutzer-Bewertung |
| --- | --- |
| **TLS 1.3 + bewusster lokaler Trust + Clienttoken** | Standard-Kanal, native Record-Verarbeitung, binäres Streaming. Keine JOSE-Sitzungskrypto/IV-Budgets/Medien-Base64. Dafür einmalige Zertifikatseinrichtung und zusätzlicher Trust-Anker. **Bevorzugt, wenn dessen Risiken akzeptiert und die reale Browserfunktion bewiesen sind.** |
| Bisheriges unbeschränktes JWE mit Keystore-AES | Verwerfen: AEAD-Provider können bis zur Tag-Prüfung ganze Nachrichten puffern. Spooling vor anschließend unbeschränktem doFinal löst das nicht. Doppelte Base64-Stufe des bisherigen JSON-/JWE-Profils kostet rund 78 Prozent Binärtransportaufschlag. |
| Strikt kleine JWE-Blöcke | Denkbar, aber zusätzlich Blockbindung, Schlüssel-/Nonce-/Replay-/Sitzungsregeln und Library-/Keystore-Interop. Nicht als einfachster Weg belegt; hier kein zweites Crypto-Kanalprofil entwerfen. |
| HTTP-Signaturen / Challenge | Verbergen keine Cookies, URLs oder Medienbytes. Späterer Klartext-Bearer bleibt unsicher. |
| PAKE | Für schwache Pairingcodes über ungesicherte Kanäle fachlich geeignet, aber zusätzliche Bibliothek plus Datenkanal nötig. Bei zuverlässig eingerichtetem HTTPS für dieses Einzelgerät nicht erste Wahl. |
| Native Messaging / WebRTC / TLS-in-JavaScript | Keine belegte einfache Vivaldi-Android-Brücke zur bestehenden APK; größere Abhängigkeiten/Architektur. Nicht jetzt einführen. |

Die bisher pauschal abgelehnte lokale CA wird neu bewertet: kein sinnvoller automatischer universeller Installationsweg, aber ein bewusst manueller privater Trust-Anker ist bei **einem Eigentümer** eine prüfbare Option. Er wird nicht automatisch installiert. Wenn der Eigentümer diese Vertrauensausweitung ablehnt oder Samsung/Vivaldi nicht zuverlässig funktionieren, bleibt die Auswahl offen. Keine Zertifikatsausnahme, kein ungeprüfter TrustManager, kein heimlicher HTTP-/JWE-Fallback.

## 4. Konkrete einmalige Einrichtung

Alles Folgende ist **erst später separat freizugeben**. Jetzt keine Schlüssel/Zertifikate/Pairingcodes erzeugen. APK-Signierschlüssel bleiben unberührt.

### A. Öffentliche Zertifikatsdaten bereitstellen

1. Auf dem vertrauenswürdigen Mac eine ausschließlich eigene lokale CA vorsehen. Deren privater Schlüssel bleibt sicher außerhalb Repository und Smartphone. Kein geteilter CA-Privatschlüssel aus GitHub, keine Cloud-/Codex-Erzeugung, keine Wiederverwendung des APK-Signierers.
2. Helper erzeugt seinen nicht exportierbaren TLS-Schlüssel im Android-Keystore; nur ein öffentlicher Standard-Zertifikatsantrag gelangt zum Mac. Vor Signierung dessen SHA-256-SPKI-Fingerabdruck mit der **nativen Helper-Anzeige** vergleichen. Nie Port-HTML als Vertrauensanker benutzen oder einen fremden ausgetauschten Antrag signieren. Standard-CSR-/KeyManager-Interop später prüfen; kein eigenes ASN.1/Signaturverfahren.
3. Mac signiert genau ein Blattzertifikat: CA=false, serverAuth, SAN **IP 127.0.0.1**, keine Wildcards/fremden DNS-Namen. Helper importiert nur eine gültige Kette passend zu seinem eigenen Keystore-Schlüssel. Keine allgemeine Signier-/Exportfunktion am Socket.
4. Nur öffentliche CA-/Blattzertifikate über vertrauenswürdige USB-/Dateiübertragung zum Samsung bringen. **Vor bewusstem Trust-Import** den CA-Fingerabdruck gegen den vom Mac bestätigten Wert prüfen; anschließend den tatsächlich installierten Eintrag prüfen. Für die konkrete Samsung-Oberfläche müssen ein zuverlässiger Installations-/Prüfpfad und Dateiprovenienz feststehen. Gleicher Zertifikatsname, ungeprüfte Download-Datei oder eine Loopback-Seite genügt nicht. Falls das nicht zuverlässig möglich ist: **keine Freigabe**, nicht einfach trotzdem installieren.
5. Nachweisen, dass aktuelles Vivaldi diesen Trust beim **Kadri-Hintergrund-Fetch** tatsächlich nutzt. Guter Peer ohne Warnung; falsches, abgelaufenes und namensfalsches Zertifikat scheitert **vor geheimen HTTP-Daten**. Keine Ausnahme klicken, kein ignore-certificate-errors. Erst danach Pairing erlauben.

Dies ist ein überprüfbarer Einrichtungsablauf, **kein bereits bestandener Samsung-UI-Test**. Public-Key-Material ist nicht geheim, Austausch ist trotzdem gefährlich und erfordert die unabhängige Prüfung. Ein Smartphone kann nicht seine eigene QR-Anzeige mit seiner Kamera scannen; das wird nicht als Ein-Gerät-Lösung behauptet. Mac/USB gehört bereits zur Eigentümerumgebung.

**Zusätzlicher Trust hat echte Kosten:** Eine installierte CA kann auch außerhalb Kadri wirken. Root-Schlüsselverlust könnte weitere HTTPS-Verbindungen gefährden. Deshalb Root privat nur auf dem Mac, Serverblatt nicht als CA. Auf Root-nameConstraints nicht ungeprüft als universelle Einschränkung verlassen. Andere appkontrollierte lokale CAs, etwa HTTPS-Filter, gehören zur Prüfung: Standard-Fetch kann sie nicht pro Kadri-Request exklusiv ausschließen. Erforderliche Trust-/Profiländerungen müssen entschieden und praktisch nachgewiesen werden; kein fremdes Zertifikat automatisch entfernen. Bei nicht kontrollierbarem Trust ist dieser TLS-Pfad nicht freigabefähig.

### B. Kopplung ohne Clipboard

1. TLS ist nach A eingerichtet. Nutzer startet **in der nativen App** „Kadri koppeln“: zehn zufällige Dezimalstellen, einmalig, maximal 180 Sekunden. Nach fünf falschen Versuchen Fenster schließen; Neustart schließt es ebenfalls. Nur erneute native Nutzeraktion öffnet ein neues Fenster, nicht ein Netzwerkrequest.
2. Über die Browser-Erweiterungsverwaltung Kadris **gebündelte Optionsseite** öffnen. Code aus der nativen Anzeige merken oder kurz auf Papier notieren und **manuell tippen**. Kein Clipboard, Screenshot, Geheimnis-Intent, URL-Fragment oder Dialog im DOM einer Medienseite. Keine fremden Skripte in Optionsseite, keine Code-Speicherung/Autofill/Logs. Vertrauenswürdige Tastatur vorausgesetzt; native Vorschau/Screenshots soweit möglich per FLAG_SECURE verhindern. Dieser Schutz ersetzt keinen vertrauenswürdigen Browser/IME.
3. Code nur im kleinen POST-Body an **https://127.0.0.1:47811/v1/pair** senden. Zertifikats-/Namens-/Besitzprüfung erfolgt auf derselben TLS-Verbindung vor HTTP. Ein gewöhnlicher fremder Portlistener erhält nur TLS-Handshakedaten, nicht Code/Token/Medienparameter. Versuchslimit global/atomar, nicht pro Socket; generische Fehler und keine CORS-Freigabe für beliebige Webseiten.
4. Gültigen Code atomar verbrauchen. Neuer zufälliger 256-Bit-Clienttoken nur im TLS-Response; Kadri speichert ihn privilegiert im Hintergrund, Helper nur einen Tokenprüfwert in privaten Einstellungen. Zunächst **eine** aktive Browserregistrierung. Neue native Kopplung widerruft die alte. Verlorene Antwort: bewusst neu koppeln, kein Fallback.
5. Spätere Requests: Authorization: Bearer ausschließlich im festgelegten HTTPS-Kanal. Kein JWT/OAuth-Autorisierungsserver oder mTLS-System. Serverauthentisierung ist TLS, Clientauthentisierung Bearer; nicht fälschlich „gegenseitiges TLS“ nennen. Tokenverlust bei Endpunktkompromittierung bleibt gefährlich.

Der kurze Code ist nur mit vorab authentisiertem TLS und dem Online-Limit vertretbar: höchstens fünf unabhängige Versuche gegen zehn Dezimalstellen pro nativ geöffnetem Fenster. **Nicht über HTTP übertragen und nicht als Ersatz für PAKE über unsichere Kanäle verwenden.** Port-DoS bleibt möglich.

## 5. Verbindliches begrenztes Medien-/Uploadprofil

TLS-Records allein verhindern keine Vollpufferung in unserer Anwendung. Keine großen JSON-/JWE-/Base64-Medienbodies, keine neue allgemeine Proxy-Plattform.

| Grenze | Vorgabe für den ersten sicheren Pfad |
| --- | --- |
| Kontrolle | **16 KiB UTF-8-JSON** pro Request/Response, inklusive URLs/Cookies/Metadaten; vor Parsing begrenzen. Nicht abschneiden, sondern klar ablehnen. |
| HTTP-Header | **8 KiB gesamt**; widersprüchliche Längen/TE+CL/unzulässige Methoden ablehnen. |
| Datenblock | **64 KiB Rohbytes**, application/octet-stream über HTTPS, exakte bekannte Länge je Upload. Reader höchstens Limit+1 vor Fehler; kein JSON-/Base64-Body. |
| Worker-Puffer | Ein 64-KiB-Lesepuffer plus begrenzte Blockrepräsentation; keine Liste aller Blöcke, kein Medien-readAllBytes. TLS-/Codec-Overhead getrennt messen. |
| Parallelität | **Zwei aktive Medientransfers global** im Helper und Kadri-Hintergrund; höchstens zwei wartende Starts, danach ablehnen. Pro Transfer nur ein Block unterwegs; ACK vor weiterem Lesen/Senden. Höchstens zwei zusätzliche kleine Kontrollrequests; Zusätzlich höchstens zwei gleichzeitige TLS-Handshakes und vier wartende unauthentisierte Sockets mit 5-s-Deadline; Executor-Queues nicht unbeschränkt wachsen lassen. |
| Disk | **8 GiB pro Transfer**, **16 GiB aktive Spools global**, mindestens **128 MiB freie Reserve**; vor Wachstum prüfen. 64-Bit-Offsets. Explizite Produktgrenze, kein RAM-Puffer. |
| Zeit | 5 s Connect/Handshake, 10 s ohne Datenfortschritt, 30 s je Block/Kontrolloperation, maximal 4 h je Transfer. Abbruch beendet Fetch/Reader/Socket und eingereihte Arbeit. |

**Datenfluss:** Kadri-Hintergrund liest Origin-Response als Byte-Stream mit begrenztem **BYOB-Puffer** und lädt jeweils einen Block hoch. Große Medienbytes dürfen nicht erst vollständig durch Dokument-/GM-/Runtime-ArrayBuffer gehen. BYOB muss am **echten Fetch-Response-Body** funktionieren; Konstruktorvorhandensein reicht nicht. Kein arrayBuffer-/Blob-Vollpuffer-Fallback. Wenn die Browserkombination das nicht kann, diesen sicheren Proxy-Pfad nicht anbieten. Nativer direkter Helper-Download nur, wenn die bestehende Quellen-/Cookie-Policy diesen Weg wirklich erlaubt.

Transfer erhält eine eindeutige ID und erwartet exakt nächsten Index/Offset. Vor begrenztem Schreiben in private temporäre Datei Token und Zustand prüfen; ACK nach erfolgreichem Block. Falsche/parallele/alte Blöcke nicht anhängen. Verlorenes ACK bedeutet Ergebnis unbekannt: im einfachen Erstumfang abbrechen/aufräumen und bewusst neu starten, keine blinde mutierende Wiederholung und kein neues Crash-Resume-System. Reset/Prozessende invalidiert Transfers; vorhandene restartfeste Identität und Cleanup-Regeln bewahren.

**Range/Bytes:** 206 muss zu angefordertem Bereich, bekannter Gesamtgröße und Validatoren passen. Ignoriert Origin Range mit 200, liegt eine vollständige Repräsentation vor: von null über denselben begrenzten Stream neu beginnen oder Resume ablehnen; nicht als passende 206 ausgeben. Bekannte Länge bis EOF prüfen. Unbekannte Größe darf von null bis echtem EOF unter Disk-/Zeitbudget fließen, ohne erfundene Gesamtlänge. Abbruch, Framingfehler, Größen-/Validatorwechsel: Fehler/Cleanup, keine Veröffentlichung. Ohne verlässliche Größen-/Validatorinformation keine automatische Wiederaufnahme.

Am Ende expliziter Commit nur bei zur Datei passender beobachteter Bytezahl und ggf. erwarteter Gesamtgröße; erst dann bestehende sichere Veröffentlichung verwenden. HLS-/DASH-Segmente und MP4 dürfen viel größer als 64 KiB sein — **das ist nur die Blockgröße**, Datei bis zum Gesamtbudget. Manifeste/Untertitel ebenfalls als Nutzdaten, nicht riesige Kontroll-JSON. Kein vollständiger Datei-ArrayBuffer für WebCrypto-Hash; Testhashes extern/incrementell berechnen.

Anwendungsarbeit ist damit nach Anzahl/Blockgröße begrenzt; das ist **kein bewiesenes Gesamt-RAM-Limit**. Browser-Netzwerkpuffer, TLS-Provider, GC und Decoder zusätzlich berücksichtigen. BYOB/ACK ist Anwendung-Backpressure, keine browserweite RAM-Garantie. Pflichtnachweis vor Freigabe: tatsächliche Produktionsklassen/Transporte mit **32 MiB Java-Heap**, großen bekannten/unbekannten Antworten, ignoriertem Range, zwei parallelen Transfers, Abbruch und Bytehash-Vergleich. Nicht bestehender Test = keine Speicherfreigabe.

## 6. Betrieb ohne Migration oder eigene Crypto-Sitzung

TLS 1.3 mit normaler Ketten-/Namens-/Zeitprüfung, **kein 0-RTT**, redirect:error, credentials:omit, referrerPolicy:no-referrer. HTTP-Medienseiten nutzen den privilegierten Hintergrund, nicht eigene Seiten-WebCrypto. Keine alternativen Hosts/Ports/HTTP-Fallbacks bei Fehlern.

TLS übernimmt Record-Authentisierung/Netzwerk-Replay-Schutz; keine eigene Nonce-/Challenge-Sitzung. Doppelte HTTP-Aktionen eines Clients sind damit nicht ausgeschlossen: einmaliger Pairingcode, Transferzustände und keine blinden mutierenden Retries bleiben erforderlich. Aktuellen vertrauenswürdigen Storage-Zustand je neuem Request lesen; kein alter Cache-/Schlüssel-Fallback.

Normales Update/Restart erhält Zertifikat und Tokenregistrierung, nicht Pairingfenster/aktive Transfers. Ablauf: Fehler und bewusste Zertifikatserneuerung, niemals Prüfung ausschalten. Reinstallation, Datenlöschung oder fehlender Keystore-Alias: Trust-/Zertifikatseinrichtung und Kopplung neu durchführen. Browserneuinstallation: neu koppeln. Reset widerruft Token/Jobs sofort. Private CA bei Widerruf bewusst aus Gerä­te-/Browsertrust entfernen; keinen unbelegten lokalen CRL-/OCSP-Mechanismus behaupten.

**Sauberer Schnitt:** alte Kopplungen einmalig löschen. HTTP-Server, /pair#KEY, X-EVP-Key-Endpunkte und unbeschränkte /push-Aufrufe vollständig entfernen. Keine automatische Legacy-Migration, Mischversion oder Downgradeoption. Debug/Release getrennte UIDs/Keystore-Aliase, keine gemeinsamen Serverprivatschlüssel; weiterhin nur eine Instanz auf Port 47811.

APK-Signatur #3 ist unabhängig: kein Einsatz/Export/Rotation des APK-Signierers für TLS. Produktions-Policy bleibt UNAPPROVED. Keine neue Arbeit an #5/#6/#7.

## 7. Unterstützungsreihenfolge und Nachweisstand

| Weg | Grundlage | Sichere Freigabe |
| --- | --- | --- |
| **Aktuelles Kadri/Vivaldi Android + eigenes Samsung** | Hintergrund-Fetch/Storage vorhanden. Chromium dokumentiert lokale Trust-Anker, aber kein Beweis für konkrete Vivaldi-Version/Extension-Anfrage. | **Erstes Ziel**, erst nach realem CA-/TLS-/Optionsseiten-/BYOB-/Abortnachweis. |
| Kadri Firefox Android | Hintergrundtransport vorhanden; eigener CA-/Third-Party-Trust und API-Details. | Nächster separat zu prüfender Weg, nicht gestrichen. Chromium-Test gilt nicht dafür. |
| Userscript, VM/TM | GM-Netzwerk/Storage dokumentiert, aber keine universelle sichere Pairing-UI oder begrenzter Fetch-Stream zugesichert. | Player ohne Helper bleibt; sicherer Helper erst nach Manager-spezifischem TLS-/UI-/Speichernachweis. Keine unsichere Nebenverbindung und kein nachgeladenes Fremd-Crypto. |
| Helper Android | Öffentliche Keystore-/TLS-APIs vorhanden. | Keine neue Android-Provider-/Hardware-/StrongBox-Garantie aus dieser Revision. |

Fetch/GM bietet weiterhin keinen portablen eigenen Vorab-Zertifikat-Pin. Der Kandidat löst Trust bewusst über Browser-/Geräteeinrichtung; Firefox SecurityInfo nach Antwortheadern ist kein Ersatz. Keine neuen Browser-/Manager-/TLS-/BYOB-Laufzeitergebnisse in dieser Dokumentationsrevision behaupten.

## 8. Entscheidungen und gezielter nächster Nachweis

Vor Produktionsimplementierung entscheiden: **API33**, **zusätzlicher privater CA-Trust samt Risiken**, **nachweisbarer Samsung-Import/Vivaldi-Trust** und **begrenzter Kadri-Erstumfang/sauberer Reset**. Jetzt nichts davon durch Code vorwegnehmen.

Danach separat freizugebender kleiner Machbarkeitsnachweis mit Wegwerfzertifikaten/-tokens:
- Guter/falscher/abgelaufener/namensfalscher TLS-Peer über echten Kadri-Hintergrund; Portwechsel/Redirect; keine Geheimnisse beim falschen Listener. Andere appkontrollierte CA als Gegenfall. Keine ignorierten Zertifikatsfehler.
- Tatsächlicher Samsung-Zertifikatsimport mit unabhängiger öffentlicher Fingerabdruckprüfung; Optionsseiten-Codeeingabe, Zeit-/Versuchs-/Einmaligkeitsgrenzen, Reset/Restart. Clipboard-Modell ist kein Nachweis.
- Echter Fetch-BYOB und begrenzte Rohblöcke mit 200/206/unbekannt/trunkiert, ACK-Backpressure, Parallelität, Disk-/Timeout-/Abbruchfehlern, Hashgegenprobe und Java-Heap 32 MiB. Kein erreichbarer Vollpuffer-Fallback.

Erst danach kleiner Implementierungs-PR für Transport/Einrichtung, native Pairing-UI/Kadri-Optionsseite und begrenzten Transferpfad. Kein JOSE-/PAKE-Paket, allgemeines Signier-/Fetch-Orakel, Player-Umbau oder Crash-Recovery-System. CSR-/Zertifikatsverwaltung kann einen kleinen etablierten Codec brauchen; separat prüfen, kein eigenes ASN.1.

**Status: nur Entwurf, #4 bleibt offen.** Bestehende Beta-Risikoakzeptanz ist kein Fix. Wenn CA-Entscheidung/Machbarkeit negativ ausfällt, ist noch keine sichere Implementierung ausgewählt; Alternative erneut begrenzt prüfen. Keine heimliche Rückkehr zu JWE/HTTP.

## Primärquellen dieser Revision

- [TLS 1.3 RFC 8446](https://www.rfc-editor.org/rfc/rfc8446.html), [X.509 RFC 5280](https://www.rfc-editor.org/rfc/rfc5280.html), [Bearer RFC 6750](https://www.rfc-editor.org/rfc/rfc6750.html). Bearer-Syntax ist kein vollständiges OAuth-System.
- [Android TLS](https://developer.android.com/privacy-and-security/security-ssl), [Keystore](https://developer.android.com/privacy-and-security/keystore), [KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec), [Android-10-Privatsphäre](https://developer.android.com/about/versions/10/privacy/changes), [Clipboard](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste).
- [Chromium Trust-FAQ](https://chromium.googlesource.com/chromium/src/+/HEAD/net/data/ssl/chrome_root_store/faq.md), [Firefox CA-Fehlerhistorie](https://bugzilla.mozilla.org/show_bug.cgi?id=1812068), [Firefox SecurityInfo](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/webRequest/getSecurityInfo). Historische Bugs beweisen keinen aktuellen Samsung-Fehler.
- [Response.body/BYOB](https://developer.mozilla.org/en-US/docs/Web/API/Response/body), [Byte-Streams](https://developer.mozilla.org/en-US/docs/Web/API/Streams_API/Using_readable_byte_streams), [Content-Kontexte](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Content_scripts), [VM](https://violentmonkey.github.io/api/gm/), [TM](https://www.tampermonkey.net/documentation.php?ext=d2&q=GM_xmlhttpRequest).
- [Samsung S26-Ultra-Modellfamilie/Android-Version](https://doc.samsungmobile.com/SM-S948B/037487260318/eng.html). Keine Abfrage des Eigentümergeräts.
