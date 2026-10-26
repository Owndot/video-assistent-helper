# Helper-Kopplung: Sicherheitsgrenze der ersten GitHub-Beta

Die fehlende gegenseitige kryptografische Serverauthentifizierung wird für die erste öffentliche GitHub-Beta ausdrücklich als begrenztes Restrisiko akzeptiert. [Helper #4](https://github.com/Owndot/video-assistent-helper/issues/4) bleibt offen. Diese Entscheidung ersetzt keine spätere Protokollfreigabe. Grundlage: Helper `e8c9584` und Kadri `cc14bcd`; beide Clients verwenden dieselbe lokale Schnittstelle. In dieser Runde werden ausschließlich Dokumentation und Tests geändert.

## Was heute geschützt wird

Der echte Android-Helper bindet ausschließlich an `127.0.0.1:47811`. Loopback begrenzt die direkte Erreichbarkeit auf das Gerät; es beweist keine Serveridentität. Der Port ist eine feste Adresse, kein Geheimnis und kein zusätzlicher Authentifizierungsfaktor.

`Keys` erzeugt mit `SecureRandom` einen zufälligen 128-Bit-Schlüssel (32 Hexzeichen) und speichert ihn in privaten Android-Einstellungen. Die Kopplungsadresse lautet `http://127.0.0.1:47811/pair#SCHLÜSSEL`. Das Fragment wird beim anfänglichen HTTP-Abruf nicht übertragen. Der Client liest es, entfernt es aus der sichtbaren Adresse und sendet den Kandidaten anschließend als `X-EVP-Key` an `/ping`. Bei erfolgreicher Antwort speichert er den Schlüssel im privilegierten Userscript-/Extension-Speicher. Die Fragmentform schützt nicht vor manipuliertem lokalem Seiteninhalt, Zugriff auf den Browser oder anderen Fähigkeiten bereits installierter Schadsoftware.

Der echte Helper prüft den aktuellen Schlüssel bei jeder geschützten Anfrage und setzt bei Erfolg seinen Kopplungsstatus. `/pair` ist ohne Schlüssel abrufbar und enthält selbst keinen Schlüssel. Der historische Client-Fallback `evp-1` wird vom aktuellen Helper nicht akzeptiert. Alle gekoppelten Browser einer Helper-Installation teilen denselben Schlüssel; „Weiterer Browser“ erzeugt keinen neuen. Es gibt keinen einzelnen Client-Widerruf.

Host-Prüfungen und fehlende CORS-Freigaben erschweren Zugriffe gewöhnlicher Webseiten und DNS-Rebinding. Sie authentifizieren weder einen lokalen Prozess noch die Antworten an den privilegierten Client. „Verbunden“ zeigt eine erfolgreiche Antwort mit dem gespeicherten Schlüssel an, keinen kryptografisch geprüften Server. Entsprechend sind absolute Aussagen wie „nur dein Skript kann zugreifen“ oder „keine andere App kann den Schlüssel sehen“ keine Sicherheitsgarantie.

## Fremder lokaler Listener und Portkonflikt

Belegt ein anderer Prozess den Loopback-Port, schlägt der echte Helper-Start fehl. Er meldet den Konflikt, bleibt nicht bereit und öffnet bei dieser fehlgeschlagenen Erstkopplung keine Browser-Kopplungsadresse. Ein Konflikt kann auch durch eine zweite vertrauenswürdige Helper-Installation entstehen; er beweist allein keine Schadsoftware.

Bereits gekoppelte Clients können ihren gespeicherten Schlüssel trotzdem an einen fremden Listener auf diesem Port senden, etwa wenn der echte Helper gestoppt wurde. Sie prüfen keine kryptografische Serveridentität und akzeptieren passende JSON-Erfolgsantworten. Ein solcher Listener kann dadurch den Schlüssel sowie übertragene Medien-URLs, Downloadparameter, gegebenenfalls übergebene Cookies und Browser-Proxy-Daten erhalten. Gefälschte Antworten können außerdem nachfolgende Helper-Abläufe beeinflussen. Der genaue Umfang hängt von den aktiven Funktionen und Berechtigungen ab.

Das setzt einen passenden lokalen Prozess beziehungsweise bereits installierte bösartige Software und die Kontrolle über den Endpoint zum Anfragezeitpunkt voraus. Es ist kein Nachweis, dass jede beliebige Webseite den Schlüssel auslesen kann. Privater App-/Managerspeicher und die Portprüfung beseitigen diese lokale Bedrohung nicht. Der lokale HTTP-Kanal hat weder gegenseitige kryptografische Authentifizierung noch einen eigenständigen Replay-Schutz; ein erlangter Schlüssel ist bis zum Zurücksetzen ein wiederverwendbares Bearer-Geheimnis.

## Bei Verdacht zurücksetzen und neu koppeln

1. Helper-Nutzung in allen betroffenen Browsern über die Einstellung „Helper-App“ deaktivieren und laufende Helper-Downloads beenden. Bei ungeklärtem Portkonflikt nicht wiederholt koppeln oder vorhandene Kopplungsadressen öffnen.
2. Den fremden Listener beziehungsweise verdächtige Software beseitigen und einen vertrauenswürdigen Geräte-, Browser- und App-Zustand herstellen. Bei einem harmlosen Konflikt die andere Helper-Installation stoppen. Ist die Kontrolle über Gerät und Port nicht wiederherstellbar, bleibt der Helper deaktiviert. Zurücksetzen allein schützt nicht vor weiter aktiver Schadsoftware.
3. In der vertrauenswürdigen Helper-App die Karte „Mit dem Browser verbinden“ lange drücken. Dadurch werden der bisherige Schlüssel ersetzt und der Kopplungsstatus gelöscht. Alle bisherigen Clients sind am echten Helper sofort ungültig, auch während dessen Server läuft; der alte feste Schlüssel wird dadurch nicht reaktiviert.
4. Den vertrauenswürdigen Helper starten und einen fehlerfreien Start ohne Portkonflikt sicherstellen. Über die Karte eine frische Kopplungsadresse öffnen; jeden gewünschten Browser erneut koppeln. Alte gespeicherte Client-Schlüssel werden bei erfolgreicher Kopplung ersetzt. Weitere Browser verwenden denselben neuen Schlüssel. Kopplungsadressen und Screenshots nicht teilen.
5. Erst nach Wiederherstellung des vertrauenswürdigen Zustands und erfolgreicher neuer Kopplung die Helper-Nutzung wieder aktivieren. Ein Neustart allein widerruft den Schlüssel nicht. Auch ein erfolgreicher Neustart und das Verbindungssymbol beweisen keine Serveridentität.

## Reproduzierbare, gezielte Nachweise

```sh
bash tests/java/run_beta_auth_tests.sh
node tests/security/helper_listener.js
# Optional: unverändertes src/080-helper-app.js aus Kadri cc14bcd als Argument
node tests/security/helper_listener.js /tmp/kadri-helper.js
```

Der JVM-Lauf nutzt den echten `MiniServer`, die unveränderte Produktionsklasse `Keys` und den aus `HelferService` extrahierten aktuellen Prüfblock. Nur Android-Context/SharedPreferences werden durch einen In-Memory-Testadapter ersetzt. Er prüft konkurrierenden Listener, Bind-Konflikt ohne Bereitschaft, fehlende/alte/feste Schlüssel, Reset, sofortigen Widerruf, Listener-Neustart und erneute Kopplung. Der vorhandene Portkonflikt- und Socket-Limit-Test wird mit ausgeführt. UI-Bereitschafts- und Fehlerpfade werden zusätzlich nur am Quelltext geprüft.

Der Node-Test führt die tatsächliche Client-Anfragefunktion gegen einen kontrollierten fremden HTTP-Listener auf `127.0.0.1:47811` aus. Ein Node-Adapter bildet ausschließlich den GM-Transport ab. Nur synthetische Schlüssel, URLs, Cookies und Nutzdaten werden verwendet. Das erwartete Ergebnis bestätigt ausdrücklich die verbleibende Lücke; die deaktivierte Helper-Option ist die Gegenprobe. Der Test beendet keine fremden Prozesse, sondern schlägt bei einem bereits belegten Port fehl.

Diese Nachweise sind keine Browser-/Manager-, Android-UI-, Geräte-Neustart- oder Festplattenpersistenztests. Die Tests erhalten ausschließlich den simulierten privaten Einstellungsspeicher über den Listener-Neustart hinweg. Es werden keine APKs oder Signierschlüssel erzeugt. Der vorhandene Workflow wählt für den dedizierten Dokumentations-/Testbranch nur diesen Job; eine Dateiliste verhindert dabei unbemerkte Laufzeitänderungen. Bestehende vollständige Prüfungen bleiben für andere Branches erhalten.
