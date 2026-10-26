# Produktionsmigration: Option A freigegeben

Stand: 26.10.2026; geprüfter Ausgangsstand Helper `7c3c1c2`. Die Eigentümerentscheidung nach Astra-Prüfung ersetzt die früheren Vorschläge einer produktiven Lineage oder einer Aufteilung nach Android-Version.

- Neuer, ausschließlich lokal verwalteter Produktionssignierschlüssel; keine Produktionsschlüssel in GitHub, Actions, PRs oder Testcode.
- Unveränderte `applicationId`: `de.evp.helfer`.
- Keine Lineage und kein weiterer Einsatz des kompromittierten Signierschlüssels.
- Auf **allen unterstützten Android-Versionen**: alte Produktions-App vollständig deinstallieren, anschließend frisch installieren und alle Browser neu koppeln.
- Spätere reguläre Updates: derselbe neue Signierer und steigender `versionCode`.

**Noch keine sichere neue Produktions-APK verfügbar.** Arbeitspaket 2 bereitet ausschließlich den unsignierten Build und lokalen Prüf-/Signierablauf vor: [LOCAL-RELEASE-MACOS.md](LOCAL-RELEASE-MACOS.md). Produktionsschlüsselgenerierung, echte Signierung, Gerätetests und Veröffentlichung bleiben gesondert freizugeben. [Issue #3](https://github.com/Owndot/video-assistent-helper/issues/3) bleibt offen.

## Historische APK entfernt

Der Eigentümer hat die historische APK manuell entfernt; die GitHub-API bestätigt aktuell eine leere Releaseliste. Die früher veröffentlichte 1.0.84-APK verwendete den kompromittierten Signierer und darf auch aus vorhandenen Kopien nicht als Übergangslösung installiert werden. Kadri und das Userscript funktionieren ohne Helper; „Helper-App“ in den Player-Einstellungen deaktivieren. Die damalige [Entfernungsanleitung](HISTORICAL-RELEASE-WARNING.md) bleibt historische Dokumentation, ihr damaliger ausstehender Ausführungsstand ist durch diese aktuelle Prüfung überholt.

Die alte APK wurde bereits unabhängig vom Buildpfad geprüft: [Audit 40117549391](https://github.com/Owndot/video-assistent-helper/actions/runs/40117549391). Ihr Zertifikat stimmt mit dem exponierten historischen Schlüssel überein. [MIGRATION-RESULTS.md](MIGRATION-RESULTS.md) enthält die öffentlichen Hashwerte und Grenzen dieser Evidenz. Historische Kopien sind dadurch nicht widerrufen.

## Nutzeranleitung — erst nach separater APK-Freigabe anwenden

1. Helper-Nutzung in allen Browsern vorübergehend deaktivieren und laufende Helper-Downloads beenden. Sprache, Design und Autostart manuell notieren. Die historische APK hat keinen Export; keinen mit dem alten Schlüssel signierten Exporter nachinstallieren. Browser-/Userscript-Einstellungen sind unabhängig von der Helper-App.
2. Android-Einstellungen → Apps → Video-Assistent Helper → Deinstallieren. Die Produktions-App `de.evp.helfer` vollständig entfernen, einschließlich privater App-Daten. Keine Option zum Behalten der App-Daten wählen, keine alte App-Sicherung wiederherstellen. Nicht nur deaktivieren, nicht nur Cache leeren und nicht versuchen, den Nachfolger darüberzuinstallieren. Relevante bereits gespeicherte Downloads vorher außerhalb des privaten App-Speichers sichern.
3. Erst die separat freigegebene, neu signierte Produktions-APK aus der dann bekanntgegebenen Projektquelle verwenden und nach deren Verifikationsanleitung prüfen. Aktuell gibt es dafür **keinen empfohlenen Download**.
4. Frisch installieren, benötigte Android-Berechtigungen erneut erteilen und notierte Einstellungen manuell setzen. Die neue Installation erzeugt einen neuen Kopplungsschlüssel; den alten Schlüssel oder Kopplungsstatus nicht übernehmen.
5. Den vertrauenswürdigen neuen Helper starten, jeden Browser ausdrücklich neu koppeln und damit gespeicherte alte Client-Schlüssel ersetzen. Erst danach Helper-Nutzung aktivieren. Bei Portkonflikt oder Verdacht auf lokale Schadsoftware die [Wiederherstellungsanleitung](HELPER-BETA-SECURITY.md#bei-verdacht-zurücksetzen-und-neu-koppeln) befolgen. Ein Verbindungssymbol ist kein kryptografischer Servernachweis.
6. Nach dieser einmaligen vollständigen Migration sind reguläre Updates mit demselben neuen Signierer und höherem `versionCode` vorgesehen. Das gilt nicht für den Übergang vom alten kompromittierten Signierer.

## Nachweise und spätere Freigabe

[MIGRATION-VALIDATION.md](MIGRATION-VALIDATION.md) trennt historische Wegwerfschlüssel-Tests von der noch ausstehenden Prüfung des echten Nachfolgers. [ANDROID-UPDATE-CHECK.md](ANDROID-UPDATE-CHECK.md) unterscheidet die einmalige Neuinstallation von künftigen gleich signierten Updates. Es wurde hier weder eine neue APK noch ein Schlüssel oder eine Lineage erzeugt; bestehende Testresultate werden nicht als Produktionsfreigabe umgedeutet.
