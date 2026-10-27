# Helper #4 — vereinfachte Entscheidungsvorlage

**Keine Implementierungsfreigabe.** Der Eigentümer ist der einzige Nutzer. Keine Fremdinstallation oder alte Kopplung muss migriert werden. [SERVER-AUTH-DESIGN.md](SERVER-AUTH-DESIGN.md) ersetzt die bisherige JWE-Empfehlung in PR #20.

## Zur Entscheidung vorgeschlagen

1. **Android 13/API33 als Produktuntergrenze**, noch nicht setzen. Natives TLS 1.3 benötigt technisch nur Android 10/API29; die höhere Grenze ist eine Supportentscheidung, keine erfundene Crypto-Anforderung. Samsung dokumentiert die S26-Ultra-Familie mit Android 16. Konkreter Gerätebuild/Patchstand nicht ausgelesen. Höheres minSdk sichert Clipboard nicht automatisch.
2. **Normales lokales HTTPS/TLS 1.3 statt JWE bevorzugen**, wenn bewusst eingerichteter privater CA-Trust akzeptiert und der reale Samsung/Vivaldi-Pfad nachgewiesen wird. Serverkey im Android-Keystore, private CA ausschließlich auf eigenem Mac, Blattzertifikat nur IP-SAN 127.0.0.1. Kein APK-Signierschlüssel für TLS.
3. **Kein Clipboard-Pairing:** öffentliche Zertifikatsdaten unabhängig prüfen/einrichten; danach zehnstelligen Einmalcode aus nativer App manuell in Kadris gebündelte Optionsseite tippen. Erst auf vorab validiertem TLS senden; 180 Sekunden/fünf falsche Versuche/einmalige Nutzung, nur nativ neu öffnen. Langfristiger Clienttoken entsteht und reist nur in TLS, nie in URL/Seiten-HTML/Clipboard.
4. **Begrenzter Rohtransfer:** 16-KiB-Kontrollnachrichten, 8-KiB-Header, 64-KiB-Binärblöcke, zwei aktive Medientransfers global, ACK-Backpressure, begrenzte Queue/Disk/Timeouts. Echter Hintergrund-Fetch-BYOB statt GM-Vollpuffer; temporäre Dateien, Range-/Validator-/EOF-/Längenprüfung vor Veröffentlichung. 64 KiB begrenzt Blöcke, nicht legitime HLS-/DASH-Segmente.
5. **Sauberer Reset, kein Legacy:** alte Kopplungen löschen; HTTP-/X-EVP-Key-/Fragmentpairing-/unbeschränkte Upload-Endpunkte vollständig entfernen. Keine automatische Migration, Mischversion oder unsicheren Fallbacks.
6. **Kadri/Vivaldi + eigener Helper zuerst.** Firefox bleibt nächstes Unterstützungsziel. Userscript-Player bleibt nutzbar; sicherer Helpermodus erst nach realem Manager-TLS-/UI-/Speichernachweis. Keine universelle Managerarchitektur als Voraussetzung.

## Warum diese Revision JWE zurückstellt

Unbeschränkte AEAD-/Keystore-Entschlüsselung kann vollständige Nachrichten puffern; Spooling davor behebt das nicht. Ein kleines JWE-Blockprotokoll wäre denkbar, bräuchte aber weitere geprüfte Schlüssel-/IV-/Sitzungs-/Replayregeln und Codecs. TLS übernimmt die Kanalkryptografie standardisiert und erlaubt Rohbytes ohne mehrfache Base64-Kopien. Deshalb hier **kein weiterer JWE-Kanalentwurf**, keine eigene Crypto und kein PAKE-/JOSE-Paket.

HTTPS ist im Anwendungscode einfacher, die eigene CA jedoch **nicht kostenlos**: Einrichtungsaufwand und ggf. über Kadri hinaus wirksamer Trust. Root-Schlüsselverlust oder eine andere appkontrollierte bereits vertraute CA kann Browservertrauen unterlaufen. Fetch ist kein exklusiver SPKI-Pin. Kein geteilter CA-Privatschlüssel, keiner in der APK, keine Zertifikatsausnahmen. Der konkrete Samsung-Import mit Fingerabdruckprüfung und echte Vivaldi-Extension-Trust sind **noch nicht nachgewiesen**.

Wenn dieser zusätzliche Trust/Betriebsaufwand nicht akzeptiert oder die Machbarkeit nicht belegt wird, ist **noch keine sichere Erstimplementierung beschlossen**. Alternative separat neu bewerten, nicht heimlich zu HTTP/JWE zurückfallen.

## Vor Implementierung offen

- Eigentümerentscheidung zu API33, privater CA-Vertrauensstellung und begrenztem Erstumfang.
- Astra-Review des Trust-Bootstraps, der weiteren lokalen CA-Risiken sowie des Code-/Transfermodells.
- Später separat freigegebener Wegwerfnachweis: tatsächlicher Kadri-Hintergrund-TLS, Falschzertifikate, konkreter Samsung-Import, manuelle Optionsseitenkopplung und echter Fetch-BYOB.
- Speicher-/Range-/Abbruch-/Parallelitätsprüfungen unter 32 MiB Java-Heap. API-Dokumentation ist keine Gerätegarantie.

Nur Dokumentation: keine Schlüssel, APKs oder Pairing-Implementierung. minSdk bleibt 26, Produktions-Policy UNAPPROVED. #4 bleibt offen. Die bisher akzeptierte fehlende Serverauthentifizierung bleibt bis zum separat freigegebenen und nachgewiesenen Fix ein Restrisiko; heutiger Zustand/Reset: [HELPER-BETA-SECURITY.md](HELPER-BETA-SECURITY.md). Kein neuer Releasebeschluss.
