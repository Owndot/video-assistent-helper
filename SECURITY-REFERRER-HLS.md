# Begrenzte Referrer-/hls.js-Korrektur

Basis: `862f41a48e7b27396d7994150f8abc98b4fcbb73` (main).
Kadri-Basis: `6199a550eb006b8939a01ef6c8aeff6dd6a15833`.
Keine Änderung an Helper-Authentifizierung, Produktionssignaturen, DNS oder Player-Oberfläche.

## Referrer

Der vorherige native Sanitizer entfernte Benutzerinfo und Fragment, übermittelte Pfad/Query aber auch an fremde Origins. Der tatsächliche alte `gmGet()` übermittelt synthetische Pfad-, Query-, Benutzerinfo- und Fragmentgeheimnisse; `tests/security/gm_headers.js` reproduziert dies am Ausgangscode. Ein CDN/Untertitelanbieter kann so vertrauliche Seitenparameter erhalten.

`Fetcher.Http.referrerFor()` vergleicht Schema, Host und effektiven Port. Gleiche Origin erhält die bereinigte vollständige Referenz; fremde Origin nur `scheme://host[:port]/`; HTTPS nach HTTP nichts. Rohe Medien-URLs einschließlich Reihenfolge und Kodierung signierter Parameter bleiben unverändert. Der native Client berechnet die Header vor jedem Redirect erneut. Nach einem TLS-Downgrade werden Seitenheader auch beim späteren Upgrade nicht wiederhergestellt.

Androids DownloadManager kann manuelle Header über Redirects wiederholen. Deshalb erhält `fileDownload()` **keinen** Referer. Dateiübertragungen, die Cookie oder Referer benötigen, laufen über den bereits vorhandenen nativen `streamToFile()`-Pfad mit begrenztem Puffer, Validator und Range-Prüfung. Das bestehende Parallelitätslimit dieses Pfades bleibt bestehen. Große anonyme Downloads mit Referer benutzen damit ebenfalls die App-Warteschlange und hängen von deren Lebensdauer ab; DownloadManager-Persistenz ist für diese Übertragungen nicht mehr zugesichert.

Die GM-Transport-APIs der Manager bieten keine einheitliche verlässlich nutzbare Header-Neuberechnung pro Redirect. Deshalb gilt dort eine strengere Transportgrenze: HTTPS-Seiten senden explizit keinen Referer; HTTP-Seiten höchstens ihre Origin, auch bei gleichem Ziel. Ein HTTPS-Seiten-Origin-Header wäre sonst bei automatischem HTTPS→HTTP-Redirect undurchsichtig weiterleitbar. Diese Einschränkung ist bewusst und kein Hotlink-Fallback. `gmGet()`, `finalUrlGM()`, Download-Probes, direkte Downloads und Untertitel verwenden dieselbe Funktion. Browser-fetch-Fallbacks setzen ausdrücklich `strict-origin-when-cross-origin`.

Kadri bereinigt den Header zusätzlich in `refererRule()`, setzt ihn nur für die exakte erste URL und unterdrückt ambienten Referrer bei automatischen Redirects. Eine fehlgeschlagene erforderliche Regel verhindert die Anfrage. Weiterleitungen können dadurch auch weniger als eine Origin erhalten. Cookies, private Netze und bisherige Request-Isolation werden nicht gelockert.

Die Änderung betrifft die manuell gesetzten Referer und die genannten fetch-Fallbacks. Native Medien-/hls.js-XHRs werden weiterhin vom Browser und der Seitenpolicy verwaltet; keine seitenweite Referrer-Policy wird umgeschrieben.

Kein konkreter öffentlicher Anbieter wurde in dieser Runde nachgewiesen, der zwingend den vollen Cross-Origin-Referrer benötigt. Eine solche Quelle muss einzeln mit synthetischem Testkonto, exaktem Ziel und abgegrenztem Headerbedarf reproduziert werden. Erst danach wäre eine ausdrücklich freizugebende, ausschließlich HTTPS nutzende Integration mit festem Quellen-/Zielpaar und kontrollierten Redirects zu prüfen. Es gibt keine allgemeine Ausnahme oder automatische Rückkehr zur vollständigen URL.

## hls.js

Die bewegliche `hls.js@1`-Angabe konnte je nach Installationszeit andere Bytes ausführen. Die bereits in Kadri und den Browserfixtures enthaltene Bibliothek meldet **1.7.3**. Welche Version einzelne bestehende Managerinstallationen früher gecacht hatten, lässt sich aus dem Repository nicht feststellen.

Die neue Userscript-Ausgabe 19.29 enthält genau die vorhandene Bibliothek mit SHA-256:

`a12e7ee1cd64a69dcdb314157e45dafcba705bfb0b1440b7935cb265d374423e`

Herkunft, ursprünglicher Git-Blob, Version und Digest stehen in `vendor/hls.lock.json`. `vendor/hls.LICENSE` bleibt enthalten. Der Build verweigert veränderte Bytes, bevor er deren Versionsangabe ausführt; es gibt keinen CDN-/Netzwerk-Fallback, kein `eval()` und keine neue Laufzeitabhängigkeit. Die Bibliothek wird erst bei der ersten HLS-Quelle initialisiert. Kadris bestehendes verzögertes Laden bleibt bestehen.

Die einzelne Userscript-Datei wächst um etwa 620 kB; zuvor musste derselbe Code separat als `@require` geladen werden. Gesamtcodegröße, Browser-Startzeiten mit frischen Seiten, Manipulationsablehnung und Versionsmetadaten werden in `tests/security/bundle.py` geprüft; Rohmessungen liefert `hls-bundle.json`. Diese Messung ist kein Samsung-/Android-Leistungsnachweis. Ein Userscript-Update muss die größere einzelne Datei übertragen; der separate Manager-Cache für die Bibliothek entfällt.

[Tampermonkey dokumentiert SHA-256-SRI für @require](https://www.tampermonkey.net/documentation.php?q=sri). [Violentmonkeys Metadaten-Dokumentation](https://violentmonkey.github.io/api/metadata-block/#require) beschreibt @require, sichert aber keine entsprechende Integritätsprüfung zu. Das Projekt empfiehlt Violentmonkey auf Chromium und Firefox; Greasemonkey wird damit nicht als vollständig unterstützt behauptet. Die eingebundene Kopie macht die Integrität unabhängig von diesen Unterschieden. Der Digest bindet geprüfte Bytes, beweist aber keine unabhängige Authentizität des Upstream-Builds oder die Sicherheit der Bibliothek. Auch das gesamte Userscript-Update und das GitHub-Projekt bleiben Vertrauensanker; dies ist keine signierte Release- oder Signaturmigration.

## Reproduzierbare Validierung

Lokal ausgeführt: Userscript-Build/--check, JS-Policytests, tatsächlicher alter/aktueller gmGet-Headervergleich und JVM-Kompilierung. Lokale Socket-Tests sind in der Cloud-Sandbox blockiert und werden nicht als bestanden ausgegeben.

Die ergänzten Jobs im bestehenden read-only PR-Workflow verwenden weder Produktionssecrets noch Veröffentlichungsrechte. Sie prüfen den echten Ausgangscode, lokale HTTP-/HTTPS-Server, native Redirects/Dateien, den echten Android DownloadManager auf einem API-30-Emulator sowie Installation/Update und Medienfunktionen mit einer per Asset-Digest fixierten Violentmonkey-2.48.0-Testinstallation und Chrome for Testing 141.0.7390.122 (für AVC-Codecs). Die Startzeitmessung verwendet unverändert Playwrights Chromium. Testhooks existieren ausschließlich in der installierten Testkopie. Bereits vorhandene Sicherheits-/Medientests bleiben aktiv. Maßgeblich sind die Joblogs und Artefakte des PR-Kopfcommits; fehlgeschlagene/ausstehende Jobs sind kein Kompatibilitätsnachweis.

Offen außerhalb der CI-Abdeckung: physisches Samsung/Android, Firefox-Manager-/Erweiterungslauf, Tampermonkey-Lauf, andere OS-Versionen, reale Anbieter/Hotlink-Anforderungen, Hintergrund-Lebensdauer und Akkulast. Das Android-Instrumentierungstest prüft den DownloadManager selbst über HTTP→andere HTTP-Port-Origin ohne Referer; TLS-Downgrade und HTTPS-Originwechsel des verwendeten nativen Downloadpfades werden separat mit kontrolliertem TLS geprüft.
