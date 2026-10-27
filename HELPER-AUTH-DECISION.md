# Helper #4 — Entscheidung für öffentliches Onboarding

**Nur Architekturentwurf.** Die öffentliche Kadri-/Helper-Version muss ohne private CA, Vertrauensstoreänderung, PC/ADB, lange Geheimnisse und Sicherheitsausnahmen koppelbar sein. Der private Samsung-/Mac-CA-Vorschlag aus 29652a4 ist dafür **zurückgezogen**. Dass noch keine externe Nutzerbasis existiert, erlaubt den sauberen Protokollschnitt, keine private Speziallösung. Vollständiger Vergleich: [SERVER-AUTH-DESIGN.md](SERVER-AUTH-DESIGN.md).

## Drei Kandidaten, noch keine Implementierungsfreigabe

| Kandidat | Vorteil | Hauptkosten / offene Voraussetzung |
| --- | --- | --- |
| A: eigener DNS-Name + öffentliches TLS-Zertifikat je Gerät | Normale Browser-TLS-Prüfung, kleinster eigener Datenkanalcode, keine Nutzer-CA. | Geräteweise DNS-01-Provisionierung/Renewal/Abuse/Quoten und zentrale Vertrauensabhängigkeit; sicheres Erst-Bootstrap sowie Loopback-DNS/LNA müssen nachgewiesen sein. Kein gemeinsamer oder geräteübergreifender Wildcard-Privatschlüssel. |
| B: kurzer Code + etabliertes PAKE + geprüfter gebundener Kanal | Einfaches manuelles Code-Onboarding ohne Geräte-PKI; perspektivisch gut für Firefox. | Aktuelle geprüfte Library-/JNI-/WASM-Kombination und vollständiges Key-Confirmation/Kanal-Binding fehlen noch. PAKE-Export-Key ist kein fertiger Medienkanal. Keine eigene KDF-/AEAD-Konstruktion. |
| C: verifizierte App-/Custom-Tabs-Brücke + gepinnter verschlüsselter Kanal | App bestätigen, keine CA/Codekopie; nur statische Projekt-HTTPS-Infrastruktur, Daten bleiben lokal. | Vivaldi muss Relationship-Validation, authentisierten Rückkanal und eigene Extension im selben Custom Tab/Profil wirklich unterstützen. Kanalbibliothek ebenfalls separat zu prüfen. |

**Zuerst C als kleinen Plattform-Machbarkeitsnachweis planen**, noch nicht implementieren. Bei Erfolg ist C die bevorzugte infrastrukturarme Richtung; bei fehlendem Vivaldi-Rückweg stoppen und B neu entscheiden. A ist grundsätzlich möglich, benötigt aber für ein kleines Projekt vergleichsweise viel dauerhaften Zertifikats-/Domainbetrieb. Keine Variante ist heute als fertige sichere Implementierung belegt.

## Nutzerablauf und Vertrauen

Ziel: installieren → in Kadri „Verbinden“ → verifizierter Link öffnet echte Helper-App → Nutzer bestätigt → browsergeprüfter Native-Origin-Rückweg zu Kadris tatsächlichem Hintergrundvorgang → Identitäten pinnen → vollständigen privaten Schlüsselbesitz im verschlüsselten Loopback-Kanal prüfen → erst dann Medien/Befehle.

App Link allein, ein Rück-URL-Parameter, ein neuer selbst signierter Public Key oder eine Origin-Behauptung im Payload genügt **nicht**. Die App-/Origin-Zuordnung ist Android-/browservermittelt, kein unabhängig exportiertes APK-Attest. Der spätere Kanal beweist den Besitz der zugeordneten Schlüssel bei jeder Neuverbindung. Austausch/Widerruf/Replay/Downgrade müssen scheitern; keine automatisch neu akzeptierte Identität.

C verwendet zunächst nur öffentliche Bootstrapdaten über kontrollierte HTTPS-Ursprünge. Keine Cloud-Medienproxyfunktion, Cookies/URLs/Bodies oder Private Keys auf dem Dienst. Domain/Hosting bleibt ein Trust-Anker für neue Kopplungen, vorhandene Pins werden nicht automatisch ersetzt. Normale Website-Zertifikate sind Betreiberaufgabe; Nutzer verwalten keine CA.

## Medien und Unterstützung

Zwei aktive Transfers global, ACK-Backpressure, begrenzte Queues/Reader/Dateispools, keine Vollpuffer. TLS könnte 64-KiB-Rohblöcke verwenden; Noise erlaubt maximal 65535 Bytes inklusive Tag, deshalb **60-KiB-Nutzblöcke** im vorgeschlagenen C-Profil. Große HLS-/DASH-Segmente bestehen aus vielen Blöcken. 16-KiB-Kontrolle, 8-KiB-Header, bestehende Range-/Validator-/Integritäts-/Cleanup-Regeln und spätere echte 32-MiB-Heap-Nachweise bleiben Pflicht. Keine Keystore-AEAD für ganze Medien.

Kein automatisches API33: Vivaldi unterstützt laut Hersteller ab Android10/API29. **API29 als vorläufige öffentliche Zieluntergrenze prüfen**, erst nach tatsächlichem Capability-Nachweis entscheiden. Aktuelles minSdk bleibt26. API31 verbessert App-Link-Verifikation; kein erfundener API33-Crypto-Zwang. Marktanteile/Patchgarantie sind nicht aus unserem einzelnen Samsung oder veralteten Verteilungsdaten ableitbar.

Firefox ist eigenes nächstes Capability-Ziel, nicht gestrichen. Userscript-Player bleibt nutzbar, sichere Helper-Kopplung erst nach realem Manager-/UI-/Transportnachweis. Keine unsichere Parallelverbindung alter Clients.

## Review und klarer Abbruchpunkt

Astra prüft zuerst Dreiervergleich, C-Trust-Kette und den geplanten begrenzten Test. Dieser soll nur öffentliche Dummy-Marker transportieren, **noch keine Kryptobibliothek/Medien/Geheimnisse**: echte verifizierte App Links, tatsächliches Relationship-Ergebnis und Channel-Ready, Vivaldi-Profil/Extension-Rückweg, dieselbe Positivkontrolle plus falsche App/DAL/Origin/JS-Events/Pending-ID/Replay als Negativkontrollen. Nur API=true oder eine erfolgreiche Chrome-Demo zählt nicht.

Ein sauberer Lauf plus höchstens eine begründete Wiederholung pro Fall, äußerer Watchdog zwei Minuten. Keine sichere Extension-/Rückroute → C nicht bestanden, kein URL-/WebView-/Legacy-Ersatz. Erst bei Erfolg separat autorisierten Library-/Kanal-/Speichernachweis planen. Noise ist offen spezifiziert, nicht IETF-TLS; Snow ist ausdrücklich nicht formal auditiert. Der historische opaque-ke-Audit deckt nicht automatisch heutige Versionen und unsere Integration ab. Keine Bibliothek ist durch diese Recherche freigegeben.

Nur SERVER-AUTH-DESIGN.md und dieses Dokument geändert. Keine App-/Pairing-/Transportimplementierung, APKs, echten Zertifikate, Produktions- oder Pairingschlüssel, Releases/Tags. Produktions-Policy UNAPPROVED. #4 bleibt offen; bestehende Exposure-CI ist Nachweis des alten Fehlers, kein Erfolg des neuen Designs. Umsetzung erst nach unabhängigem Review und erneuter Eigentümerfreigabe.
