# Gezielte Sicherheitskorrektur — 25.10.2026

Ausgangspunkt unverändert: Kadri f76c209, Helper22d94d2.
Die bereits grüne CI40213454695 und die bisherigen Android-/Chromium-Ergebnisse
werden nicht als neue Ausführung ausgegeben. Samsung-Praxistests wurden vom
Eigentümer als gut gemeldet; eine vollständige manuelle Testmatrix ist damit
nicht dokumentiert.

## Behobene konkrete Fehler

1. **Hoch — HTTPS-Seitencookies an initiales HTTP-Ziel.**
   Fetcher.Http.cookieFor verglich nur die PSL-Domain. Der vorhandene Schutz
   gegen HTTPS→HTTP-Redirects griff nicht, wenn bereits die erste Medienadresse
   HTTP war. Eine HTTPS-Seite liefert document.cookie an den Helper; darin können
   auch für JavaScript sichtbare Secure-Cookies enthalten sein. Diese dürfen
   nicht über HTTP herausgehen.
   Jetzt prüft jeder native Hop zusätzlich den Transport der Herkunftsseite.
   HTTPS→HTTP erhält weder manuelle Cookies noch Referer/Origin der HTTPS-Seite.
   DownloadManager erhält beim direkten Downgrade ebenfalls keinen Referer.
   HTTP→HTTP-Medienserver, HTTPS→HTTPS und HTTP→HTTPS bleiben erlaubt.

2. **Hoch bei Credential-URLs — URL-Geheimnisse in Referer/Origin.**
   Die rohe Seitenadresse wurde als Referer gesendet; URL-Benutzerinfo gelangte
   zusätzlich in Origin. Fragmente können z.B. OAuth-Token enthalten. Native
   Requests und DownloadManager bekommen nun dieselbe bereinigte Referer-URL:
   Benutzerinfo und Fragment entfernt, roher Pfad und Query unverändert.
   Origin entsteht aus dieser bereinigten Adresse. TestReferrerPrivacy erfasst
   tatsächliche HTTP-Header, prüft IPv6/Prozentkodierung und erhält eigene Cookies.
   Der reale Vorgänger muss an der Fragment-/Benutzerinfo-Prüfung scheitern.

3. **Mittel — unbeschränkte unauthentifizierte Socket-Warteschlange / Stop-Leck.**
   Executors.newFixedThreadPool nahm beliebig viele Verbindungen an, bevor der
   Schlüssel geprüft wurde. shutdownNow schließt blockierende Socket-Lesezugriffe
   nicht zuverlässig; angenommene Verbindungen konnten nach Service-Stop bleiben.
   Die vorhandenen10 Arbeiter bleiben parallel, die Warteschlange ist auf32
   Verbindungen begrenzt. Überlast wird durch sofortiges Schließen abgewiesen.
   Stop schließt alle angenommenen Sockets; normale und fehlerhafte Handler
   schließen ihre Verbindung in finally. Ein gestoppter Server meldet bei
   Wiederverwendung keinen irreführenden erfolgreichen Start.
   Gegen einen aktiv flutenden lokalen Prozess ist dies keine vollständige
   Verfügbarkeitsgarantie, aber die Ressourcen wachsen nicht unbeschränkt.

## Gezielte Nachweise

TestTlsCookieBoundary verwendet echte lokale HTTPS-/HTTP-Server, einen
zweitägigen Wegwerfschlüssel und eine ausschließlich im Testclient vertraute
Zertifikatskette. Die Produktions-TLS-Prüfung wird nicht abgeschaltet; eine
negative Kontrolle verlangt die Ablehnung des Testzertifikats durch den
unveränderten Produktionsclient. Fälle: erste HTTP-Adresse, TLS-Downgrade,
Cross-Site-Redirect, Range sowie positive HTTP-/HTTPS-/Upgrade-Kompatibilität.

TestMiniServerLimits verwendet echte Loopback-Sockets: angenommener blockierter
Reader beim Stop, Überlast, begrenzte Warteschlange, Erholung, fehlender/falscher
Schlüssel, fremder Host und schlüsselfreie Kopplungsseite. Reflection
synchronisiert nur den tatsächlich angenommenen Worker; kein Serververhalten
wird simuliert.

Die CI lädt ausschließlich die beiden Java-Quelldateien aus dem tatsächlichen
Vorgänger22d94d2. Die neuen Regressionen müssen an dessen Cookie-Leck und
offenem Stop-Socket scheitern. Danach laufen die korrigierten Tests in der
bestehenden JVM-/Medienprüfung. Gradle prüft die Android-Kompilation.
Konkreter abschließend geprüfter Run und Ergebnis stehen im PR-Text und im
begleitenden Abschlussbericht. Diese Nachweise sind JVM-/TLS-/Socket-Tests,
keine neu ausgeführten Android-Gerätetests.

Die ältere TestMain-Fixture verwendet jetzt HTTP als synthetische Herkunft,
weil ihr Server reines HTTP ist. Alle Cookie-Fremdseiten-/Medienassertionen
bleiben bestehen; HTTPS-Verhalten prüft der echte TLS-Test separat.

Keine Player-/Userscript-/Kadri-Runtime wurde verändert. Keine vollständige
Browser- oder Emulator-Matrix und kein neues Testframework sind vorgesehen.

## Weiterhin begrenzte / offene Fälle

- PiP/Home: der letzte API33-Lauf hatte einen Renderer-/UI-Automator-Ausfall;
  Ursache nicht abschließend zwischen Firefox, Emulator und OEM getrennt.
  Kein Player-Umbau ohne tatsächlichen Samsung-Befund.
- Die acht Firefox-Harnessabweichungen wurden auch mit dem älteren stabilen
  Skript reproduziert. Keine neu belegte Regression; keine weitere Großbaustelle.
- Cookie-Downloads haben keine garantierte Wiederaufnahme nach hartem
  Prozessende. Blockierende Netz-Lesezugriffe können Abbruch bis zum bestehenden
  Timeout verzögern. Unknown-length-Browserproxy und große Segmentkörper sind
  nicht durchgehend speicherbegrenzt; echte vollständige Streaming-Umstellung
  des Proxy-Protokolls ist außerhalb dieser kleinen Korrektur.
- Mehrere Player-Zyklen/physische Akkumessungen sind nicht neu ausgeführt.
  Kein behaupteter Leak-Freiheits- oder Performancegewinn; Player-Code bleibt gleich.
- Lokaler Server: Bearer-Schlüssel authentifiziert den Client, nicht die
  Identität eines auf dem Port lauschenden anderen lokalen Prozesses.
  Ein bereits installierter bösartiger Prozess könnte den freien Helper-Port
  besetzen und vorhandene Browser-Schlüssel abfangen. Dies betrifft lokale
  Schadsoftware, kein nachgewiesenes reines Webseiten-Szenario. Gegenseitige
  Serverauthentifizierung erfordert einen gesondert getesteten Protokollwechsel
  für Extension und Userscript; hier bewusst nicht eigenmächtig umgesetzt.
- Explizite volle Referer-URLs an Medienanbieter bleiben eine
  Kompatibilitäts-/Datenschutzgrenze. Cookie-freie DownloadManager-Redirects
  bekommen keine neue per-Hop-Header-Architektur. Keine pauschale Behauptung,
  dass beliebige URL-Tokens in allen Provider-Szenarien abgeschirmt sind.
- Chrome-DNS-Rebinding bleibt die bekannte Browsergrenze.
- Historische Produktionssignatur bleibt kompromittiert. Aktueller Dateistand
  enthält keine Signiergeheimnisse; CI nutzt ausschließlich Testidentitäten.
  Produktive Rotation/Lineage/Veröffentlichung bleiben ausdrücklich ungeprüft
  und unautorisiert. Vor Veröffentlichung: freigegebene geschützte neue Identität,
  API33+ v3.1-Prüfung und separater API26–32-Migrationsweg samt erneuter Kopplung.

Diese Runde endet nach den konkreten Regressionen und Build-Prüfungen.
Keine PRs mergen, keine produktiven Schlüssel erzeugen oder verwenden,
keine Releases/Tags verändern.

## Tatsächliche Nachweise dieser Runde

- f02454c: Cookie-/Socket-Korrektur und neue Tests. Deren Alt/Neu-Nachweise
  bestanden; der Gesamtjob40218799431 scheiterte an der alten gemischten
  HTTPS-/HTTP-Testfixture, nicht am neuen TLS- oder Socket-Test.
- 4187b79: Fixture korrigiert. Reguläre CI40219171842 vollständig PASS,
  einschließlich Medien, Streaming, Android-Build und Testsignaturen.
- 29158de: Referer-/Origin-Bereinigung. Diagnose40220447681 prüfte genau diesen
  Source-Commit: alle drei neuen realen TLS-/HTTP-/Socket-Prüfungen PASS.
- Reguläre CI40219442784 wurde kontrolliert abgebrochen. Ihre alten
  Negativnachweise waren bestanden; anschließend blockierte die langsame
  Installation von FFmpeg-Paketen. Es war kein neuer Test-/Produkt-Hänger.
  Die Diagnose hatte Actions-Schreibrecht ausschließlich in einem getrennten
  Job zum Abbrechen dieses einen festen Testlaufs; ihr Testjob war read-only.
  Dieser temporäre Diagnoseworkflow liegt nicht auf dem Merge-Branch.
- Der letzte Workflowwechsel betrifft nur diese CI-Installation: offizieller
  signierter Ubuntu-Mirror, keine empfohlenen Zusatzpakete, feste Paket-
  Zeitlimits. Debug-/Release-Testbuilds und APK-Provenienz laufen davor.
  Es gibt keine weitere Produktänderung und keine neue Emulator-Matrix.
  Den abschließend geprüften regulären Run dokumentiert der PR-Text.
