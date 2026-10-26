# Signaturentscheidung: Option A freigegeben

Stand: 26.10.2026. Die Eigentümerfreigabe nach abschließender Astra-Prüfung wählt **Option A** für die öffentliche GitHub-Beta:

- Neuer, ausschließlich lokal verwalteter Produktionssignierschlüssel.
- Gleiche `applicationId`: `de.evp.helfer`.
- Keine Lineage; der kompromittierte Signierer wird nicht erneut verwendet.
- Bestehende Nutzer deinstallieren die alte Produktions-App auf allen unterstützten Android-Versionen vollständig. Anschließend erfolgen frische Installation und erneute Browserkopplung.
- Künftige Updates nutzen denselben neuen Signierer und steigenden `versionCode`.

Frühere Vorschläge für v3.1 auf API33+, ältere v3-Lineages oder einen anderen Paketnamen sind **nicht die gewählte Produktionsstrategie**. Androids technische Rotationsmöglichkeiten rechtfertigen keinen weiteren Einsatz des kompromittierten Schlüssels.

Die Freigabe betrifft die Strategie. In Arbeitspaket 1 werden nur die historische Downloadwarnung und Dokumentation bearbeitet. Es gibt noch keine sicher neu signierte, freigegebene Produktions-APK; Schlüsselgenerierung und Produktionssignierprozess folgen erst im separat beauftragten Arbeitspaket 2. [Issue #3](https://github.com/Owndot/video-assistent-helper/issues/3) bleibt bis zum Abschluss der getrennten Arbeiten offen; seine früheren Lineage-Kriterien sind durch Option A überholt.

## Bestehende Evidenz

Der veröffentlichte historische APK-Download verwendet nach Audit [40117549391](https://github.com/Owndot/video-assistent-helper/actions/runs/40117549391) den kompromittierten Signierer. Öffentliche Zertifikats-/Dateihashes, historische Testläufe und deren Grenzen stehen in [MIGRATION-RESULTS.md](MIGRATION-RESULTS.md). Vorhandene v3.1-Wegwerfschlüssel-Tests bleiben historische Mechaniknachweise; sie verlangen und genehmigen keine produktive Lineage.

Nutzeranleitung: [SIGNING-MIGRATION.md](SIGNING-MIGRATION.md). Prüfplan für den späteren echten Nachfolger: [MIGRATION-VALIDATION.md](MIGRATION-VALIDATION.md). Kadri und das Userscript können bis dahin ohne Helper genutzt werden.
