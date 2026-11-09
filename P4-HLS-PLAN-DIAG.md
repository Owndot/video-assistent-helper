# Adressfreie HLS-Planungsdiagnose

Stand 2026-11-09. **DONE – implementiert und lokal geprüft; NEEDS_TEST – CI und Samsung.**
Produktion **UNAPPROVED**. Reale HTTP 404/412 weiterhin **OFFEN**, keine behauptete Downloadkorrektur.

Der vorhandene `hlsplan` gab bei allen Listenabrufen denselben neutralen Fehlercode zurück.
Dadurch war ein HTTP 412 der Einstiegsadresse nicht von einem HTTP 412 der Video-/Audioliste unterscheidbar.
Fehlerantworten erhalten jetzt optional `diag: {v:1, phase, playlists}`. Nur feste Phasen und 0–3
erfolgreich geparste/typgeprüfte Listen; keine URL, Domain, Pfade, Querywerte, IDs, Header oder Antwortinhalte.
Der bisherige `reason` und die erfolgreiche Planantwort bleiben unverändert. Keine zusätzlichen Anfragen,
Retries, Credentials, Proxywege oder Änderung von Quellbindung, Download-/Abbruch-/Resume-Verhalten.

| Phase | Aussage |
|---|---|
| `source_fetch` | Abruf der übergebenen Einstiegsplaylist; vor Verarbeitung ist deren tatsächlicher Typ unbekannt |
| `source_parse` | Verarbeitung dieser Playlist |
| `source_options` | Unterstützte Optionen aus der erfolgreich verarbeiteten Quelle bestimmen |
| `video_fetch` / `video_parse` | Abruf / Verarbeitung der gewählten Video-Medienliste |
| `audio_fetch` / `audio_parse` | Abruf / Verarbeitung einer separaten Audio-Medienliste |
| `plan_build` | Zusammenstellen und Prüfen des Plans aus den verarbeiteten Listen |

Die Diagnose gilt ausschließlich für fehlgeschlagenes `hlsplan`. Init/Segmente werden dort noch nicht
geladen. Ein späterer Fehler bei `hlsstart`, Downloads oder Wiederaufnahme erhält dadurch **keine**
genauere Ressourcenphase. Redirect-Ziele/Ketten werden nicht ausgegeben; ein Fehler nach Redirect
bleibt dem jeweiligen Listenabruf zugeordnet. Transport-Timeout ohne Helperantwort bleibt unbekannt.

**DONE – lokale Nachweise:** bestehende HLS-JVM-Suite, Koordinator **255/255**, Netzschichtwächter grün.
Neue Fälle: 404 und 412 jeweils an Einstieg/Video/Audio; ungültiger Inhalt jeweils an diesen drei Stellen;
Redirect zu fehlender Liste; danach erfolgreicher Plan ohne alte Diagnose. Keine Segmente/Veröffentlichung
bei Planfehler, keine Credentials am Testserver, keine Adressen/Token in Antworten und Journalen.
Die Negativtests verwenden nur kontrollierte lokale Fixtures, keine realen Webseiten.

**IN_PROGRESS – Kadri:** feste Phasen/Zähler an beiden Übergängen validieren, alle anderen Felder
verwerfen; Webseitenknopf und Player verwenden denselben Diagnosepfad. Alte Helper ohne `diag` bleiben
kompatibel und werden weiterhin als genaue Phase unbekannt dargestellt. Vor Lieferung müssen beide
Quellstände zusammen über den tatsächlichen Kanal und in CI geprüft sein.

**NEEDS_TEST – Samsung nach geprüfter isolierter P4-Lieferung:** Seite neu laden, beim selben Video
„Stream speichern …“ wählen, Fehler abwarten, Erweiterungsmenü → „Diagnose anzeigen“ →
„Anonymisiert kopieren“. Benötigt werden HTTP/Grundcode, Planungsschritt und Listenanzahl.
Keine Rohdiagnose und keine Adressen senden. Noch kein neues Installationspaket aus diesem Dokument.

Samsung F1/F2/R1/R2/C1 bleiben PASS der bisherigen Basis; P4B-A10-SIZE bleibt rot.
