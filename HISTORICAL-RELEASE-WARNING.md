# Historische APK-Assets entfernen — aktualisierte Eigentümerfreigabe

## Auftrag und tatsächlich geprüfter Zustand

Die Eigentümerfreigabe erlaubt jetzt ausdrücklich die dauerhafte Löschung historischer Release-APK-Assets mit kompromittiertem Signierer oder ohne öffentliche Installationsfreigabe. Sie ersetzt die frühere Vorgabe, diese APK-Assets zu erhalten. Git-Tags, Quellcode, Git-Historie, Release-Datensätze und andere Dateien bleiben erhalten.

Die vollständige zugängliche Releaseliste wurde über die GitHub-API erneut geprüft (100 Einträge pro Seite; die zweite Seite ist leer). Es existiert genau ein öffentliches Release und genau ein APK-Asset:

| Release / Tag | Release-ID | APK / Asset-ID | Grund für Entfernung |
|---|---|---|---|
| Video-Assistent Helper 1.0.84 / `neueste` | `421300300` | `VideoAssistent-Helper.apk` / `667389794` | Kompromittierter Signierer, bereits durch Audit 40117549391 belegt; keine sichere Installationsfreigabe |

APK-Dateigröße: 50.616 Bytes. Öffentlicher SHA-256: `bdbb2dd853ffcba80afb27492a31baf474ae1e3fd36418e3f7a5206abb760086`.

**Ausführungsstand: noch kein Asset gelöscht, kein Release umbenannt.** Die GitHub-Verbindung stellt keine Release-/Asset-Schreiboperation bereit. Der CLI-Zugriff blieb bei der Sandbox-Netzwerkfreigabe hängen und wurde abgebrochen. Die abschließende API-Abfrage listet das APK-Asset weiterhin öffentlich. Dieser Dokumentations-PR entfernt keine Release-Datei; die nachstehenden Schritte sind noch durch den Eigentümer auszuführen.

## Genaue manuelle Schritte

1. [Historischen Release bearbeiten](https://github.com/Owndot/video-assistent-helper/releases/edit/neueste) im berechtigten GitHub-Konto öffnen. Version 1.0.84, Tag `neueste`, Release-ID `421300300` prüfen.
2. Unter den vorhandenen Assets **ausschließlich** `VideoAssistent-Helper.apk` entfernen und die dauerhafte Löschung bestätigen. Keine Quellcodearchive, Tags, anderen Dateien oder den Release selbst löschen. Alternativ im bereits authentifizierten lokalen GitHub-CLI genau diesen Asset-Endpunkt löschen:

```sh
# Vorher Identität/Datei nochmals prüfen; bei Abweichung anhalten.
gh api repos/Owndot/video-assistent-helper/releases/assets/667389794 \
  --jq '{id,name,size,digest}'
# Ausschließlich das freigegebene APK-Asset dauerhaft löschen.
gh api --method DELETE repos/Owndot/video-assistent-helper/releases/assets/667389794
```

3. Nach erfolgreicher Entfernung den Release-Titel setzen auf:

   **HISTORISCH – KEINE APK VERFÜGBAR – Helper 1.0.84**

4. Die gesamte bisherige Beschreibung durch den folgenden Text ersetzen, damit die alten Installationsaufforderungen entfallen. Falls die Löschung noch nicht gelingt, zunächst nur die Warnung übernehmen und nicht behaupten, das Asset sei bereits entfernt.

```markdown
> **NICHT INSTALLIEREN:** Die historische APK dieser Version verwendet einen kompromittierten Signierschlüssel. Eine sicher neu signierte Nachfolgeversion ist noch nicht freigegeben. Kadri und das Userscript können ohne Helper genutzt werden.
>
> **DO NOT INSTALL:** This version's historical APK uses a compromised signing key. No safely re-signed successor APK has been approved for distribution yet. Kadri and the userscript can be used without Helper.

Historische, nicht mehr installierbare Version 1.0.84. Das alte APK-Asset wurde aus diesem Release entfernt. Releaseinformationen, Git-Tag und Quellcode bleiben als historische Dokumentation erhalten. Eine „Latest“-Kennzeichnung ist keine Installationsempfehlung.

Die freigegebene Nachfolgestrategie ist Option A: neuer ausschließlich lokal verwalteter Produktionssignierer, unveränderte applicationId de.evp.helfer, keine Lineage und kein weiterer Einsatz des kompromittierten Signierers. Bestehende Nutzer müssen die alte Produktions-App vollständig deinstallieren, bevor sie einen separat freigegebenen Nachfolger frisch installieren und alle Browser neu koppeln. Künftige Updates verwenden denselben neuen Signierer und einen steigenden versionCode.

Bis zur gesonderten Freigabe einer neuen Produktions-APK den Player ohne Helper verwenden und „Helper-App“ in den Player-Einstellungen deaktivieren.
```

5. Mit **Update release** speichern; Tag, Zielcommit und sonstige Release-Einstellungen unverändert lassen. Keine neue Datei hochladen.

## Verifikation nach manueller Ausführung — derzeit noch ausstehend

- Alle Release-Assets erneut paginiert prüfen. Die folgende Ausgabe muss für APK-Assets leer sein; zusätzliche noch vorhandene APK-Assets müssen einzeln auf Freigabe geprüft werden.
- Die Asset-ID `667389794` muss anschließend für den berechtigten Eigentümer mit „Not Found“ antworten. Ein Berechtigungs- oder Netzwerkfehler allein ist kein Löschbeweis.
- [Releaseansicht](https://github.com/Owndot/video-assistent-helper/releases/tag/neueste) und [Latest-Ansicht](https://github.com/Owndot/video-assistent-helper/releases/latest) müssen den historischen Titel und die Warnung zeigen, ohne APK-Download. Den bisherigen direkten APK-Download zusätzlich auf fehlende Verfügbarkeit prüfen.
- Release `421300300` und Tag `neueste` müssen weiterhin existieren; deren Erhalt ist beabsichtigt.

```sh
gh api --paginate 'repos/Owndot/video-assistent-helper/releases?per_page=100' \
  --jq '.[] | {release: .id, tag: .tag_name, apks: [.assets[] | select(.name | test("\\.apk$"; "i")) | {id,name}]}'
gh api repos/Owndot/video-assistent-helper/releases/assets/667389794
gh api repos/Owndot/video-assistent-helper/releases/421300300 --jq '{id,tag_name,name,assets:[.assets[].name]}'
gh api repos/Owndot/video-assistent-helper/git/ref/tags/neueste --jq .object.sha
```

Ein Entfernen offizieller Downloads widerruft keine bereits heruntergeladenen Kopien. Nutzeranleitung und Option A: [SIGNING-MIGRATION.md](SIGNING-MIGRATION.md). Noch keine sichere neue Produktions-APK verfügbar; keine neue APK, Schlüsselgenerierung oder Signierimplementierung in diesem Arbeitspaket.
