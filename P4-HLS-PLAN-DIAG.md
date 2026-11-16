# Adressfreie HLS-Planungsdiagnose

Stand 2026-11-16. **DONE – implementiert, lokal und in HLS-/Kanal-CI geprüft; NEEDS_TEST – Samsung.**
Produktion **UNAPPROVED**. Reale HTTP 404/412 weiterhin **OFFEN**, keine behauptete Downloadkorrektur.

**BLOCKED – Lieferung:** zusätzlicher API-34-MP4-Speicherfehler im Kanalworkflow; einmalige unveränderte
Wiederholung läuft. Aktuell und verbindlich:
[Claude-Übergabe](https://github.com/Owndot/Kadri/blob/feature/c3-helper-mp4/CLAUDE-HANDOFF.md).
Keine neue Geräte-APK verteilt; vollständiger Paketentwurf in [P4-DELIVERY-DRAFT.md](P4-DELIVERY-DRAFT.md).

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

**DONE – Kadri `7978a41`:** feste Phasen/Zähler an beiden Übergängen validiert, alle anderen Felder
verworfen; Webseitenknopf und Player verwenden denselben Diagnosepfad. Alte Helper ohne `diag` bleiben
kompatibel und werden weiterhin als genaue Phase unbekannt dargestellt. Gemeinsame echte Kanalprüfung
103/103, Kadri-CI `42547277661` und Helper-HLS-CI `42547144187` grün. Der zusätzliche Emulatorbefund
ist dadurch nicht erledigt und darf nicht als P4B-A10-SIZE-Ausnahme behandelt werden.

**NEEDS_TEST – Samsung nach geprüfter isolierter P4-Lieferung:** Seite neu laden, beim selben Video
„Stream speichern …“ wählen, Fehler abwarten, Erweiterungsmenü → „Diagnose anzeigen“ →
„Anonymisiert kopieren“. Benötigt werden HTTP/Grundcode, Planungsschritt und Listenanzahl.
Keine Rohdiagnose und keine Adressen senden. Noch kein neues Installationspaket aus diesem Dokument.

Samsung F1/F2/R1/R2/C1 bleiben PASS der bisherigen Basis; P4B-A10-SIZE bleibt rot.
