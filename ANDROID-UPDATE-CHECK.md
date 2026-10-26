# Option A: spätere Produktionsprüfung — noch nicht ausgeführt

Die freigegebene [Option A](SIGNING-MIGRATION.md) behält `de.evp.helfer`, verwendet einen neuen ausschließlich lokal verwalteten Signierer und **keine Lineage**. Es gibt noch keine sicher neu signierte freigegebene Produktions-APK. Dieser Prüfplan erzeugt oder veröffentlicht nichts und ist kein Auftrag, die Prüfungen in Arbeitspaket 1 zu beginnen.

## Einmaliger Wechsel vom kompromittierten Signierer

- Eine alte APK nicht zur Testvorbereitung erneut installieren. Auf einem bereits vorhandenen Testbestand Downloads beenden und die drei Helper-Einstellungen manuell notieren. Die historische APK hat keinen Export.
- Alten Produktions-Helper vollständig deinstallieren, ohne App-Daten zu behalten oder wiederherzustellen. Keine Installation mit `adb install -r` über die alte App und kein Signatur-/Downgrade-Bypass.
- Die später separat freigegebene neue APK anhand ihrer veröffentlichten Verifikationsanleitung prüfen: Paket-ID, APK-Hash, neuer öffentlicher Signierer und keine Lineage/Vertrauensbeziehung zum alten Signierer. Den privaten Schlüssel niemals in CI oder Prüfberichte übertragen.
- Frisch installieren; Einstellungen manuell setzen, Systemberechtigungen erneuern und jeden Browser neu koppeln. Die neue App darf keine alten privaten Daten oder Kopplungsschlüssel übernehmen.
- Ein höher versioniertes Update des alten Signierers muss nach der Migration abgewiesen werden. Für mechanische Tests nur vorhandene Wegwerfidentitäten verwenden; den kompromittierten Produktionsschlüssel nicht erneut einsetzen.

## Reguläre Updates nach abgeschlossener Migration

Erst für zwei APKs **desselben neuen Signierers** ist der bestehende Host-Preflight vorgesehen:

`python3 tools/check-update.py trusted-new-signer-previous.apk next.apk --expected-cert NEW_PUBLIC_CERT_SHA256`

Er prüft mit Android-SDK-Werkzeugen die APK-Signaturen, gleiche Produktions-Paket-ID und steigenden `versionCode`. Dieser Same-Signer-Check ist kein Lineage-Verifikator und keine Freigabe eines Alt-zu-Neu-In-place-Upgrades. Bei solchen späteren regulären Updates ist `adb install -r next.apk` der normale Prüfweg; App-Einstellungen und die nach der Migration neu eingerichtete Kopplung sollen erhalten bleiben.

## Evidenzgrenzen

Den tatsächlichen Nachfolger und spätere gleich signierte Updates erst im gesonderten Freigabeschritt prüfen. Modell/Android-Version, Paket-ID, versionCode, öffentliche Zertifikats- und APK-SHA-256 sowie reale Ergebnisse protokollieren; keine Schlüssel oder Cookies. Vorhandene Funktions-/Gerätechecklisten bleiben bestehen, werden hier aber nicht ausgeführt. Aktueller Status der echten Option-A-Produktionsmigration: **NICHT GETESTET**. [Historische Wegwerfschlüssel-Nachweise](MIGRATION-RESULTS.md) ersetzen diese Prüfung nicht.
