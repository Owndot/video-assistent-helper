# Historischen APK-Download warnen — Arbeitspaket 1

## Tatsächlich beobachteter Zustand (26.10.2026)

Das Repository ist öffentlich. GitHubs `releases/latest` liefert Release-ID `421300300`, Tag `neueste`, Titel „Video-Assistent Helper 1.0.84“. Die APK ist weiter öffentlich als Asset `667389794` (`VideoAssistent-Helper.apk`, 50.616 Bytes) gelistet. Öffentlicher Dateihash: `bdbb2dd853ffcba80afb27492a31baf474ae1e3fd36418e3f7a5206abb760086`. Es wurde keine APK heruntergeladen, erzeugt oder verändert.

Die aktuelle Beschreibung fordert noch auf: „Auf dem Handy öffnen, VideoAssistent-Helper.apk antippen und installieren. Neue Versionen lassen sich einfach drüber installieren.“ Diese Aufforderung ist für den kompromittierten Signierer falsch.

**Die Live-Metadaten wurden in diesem Arbeitspaket noch nicht geändert:** Die verfügbare GitHub-Verbindung bietet nur lesenden Release-Zugriff; der CLI-Zugriff scheitert am Sandbox-Netzwerkzugang. Der Warnhinweis im Draft-PR ersetzt keine Warnung am veröffentlichten Release. Bis zur manuellen Änderung bleibt dort die irreführende Aufforderung sichtbar.

## Genaue manuelle Änderung durch den Repository-Eigentümer

1. Im angemeldeten GitHub-Konto [historischen Release bearbeiten](https://github.com/Owndot/video-assistent-helper/releases/edit/neueste) öffnen. Kontrollieren: Tag `neueste`, Version 1.0.84. Alternativ [Releaseansicht](https://github.com/Owndot/video-assistent-helper/releases/tag/neueste) → Stiftsymbol.
2. Nur den Release-Titel durch diesen Text ersetzen:

   **HISTORISCH – NICHT INSTALLIEREN – Helper 1.0.84 (kompromittierte Signatur)**

3. Die **gesamte** bisherige Beschreibung durch den folgenden Text ersetzen. Damit entfallen beide alten deutschen und englischen Installationsaufforderungen:

```markdown
> **NICHT INSTALLIEREN:** Diese historische APK verwendet einen kompromittierten Signierschlüssel. Eine sicher neu signierte Nachfolgeversion ist noch nicht freigegeben. Kadri und das Userscript können ohne Helper genutzt werden.
>
> **DO NOT INSTALL:** This historical APK uses a compromised signing key. No safely re-signed successor APK has been approved for distribution yet. Kadri and the userscript can be used without Helper.

Version 1.0.84 bleibt ausschließlich als historisches Artefakt erhalten. Auch eine „Latest“-Kennzeichnung durch GitHub ist keine Installationsempfehlung.

Die freigegebene Nachfolgestrategie ist Option A: neuer ausschließlich lokal verwalteter Produktionssignierer, unveränderte applicationId de.evp.helfer, keine Lineage und kein weiterer Einsatz des kompromittierten Signierers. Bestehende Nutzer müssen die alte Produktions-App vollständig deinstallieren, bevor sie einen separat freigegebenen Nachfolger frisch installieren und alle Browser neu koppeln. Künftige Updates verwenden denselben neuen Signierer und einen steigenden versionCode.

Aktuell keine historische APK installieren oder als Update darüberinstallieren. Bis zur gesonderten Freigabe einer neuen Produktions-APK den Player ohne Helper verwenden und „Helper-App“ in den Player-Einstellungen deaktivieren.
```

4. Tag, Zielcommit, Assets, Dateinamen sowie sonstige Release-Einstellungen unverändert lassen. Keine Datei entfernen, keine neue hochladen, keinen Tag oder Release löschen/erzeugen. Mit **Update release** speichern.
5. [Releaseansicht](https://github.com/Owndot/video-assistent-helper/releases/tag/neueste) und [GitHubs Latest-Ansicht](https://github.com/Owndot/video-assistent-helper/releases/latest) kontrollieren: Warnung ganz oben, historischer Titel, keine bisherige Installationsaufforderung. Die vorhandene APK bleibt absichtlich öffentlich; ihre Erreichbarkeit ist keine Empfehlung. Asset-ID/Dateihash und Tag müssen unverändert bleiben.

Nach manueller Anwendung kann dieser Statusvermerk in einer späteren reinen Dokumentationskorrektur aktualisiert werden. Die [Option-A-Anleitung](SIGNING-MIGRATION.md) wird mit diesem Draft-PR vorgeschlagen; es wird hier nichts gemergt und kein Produktionssignierprozess implementiert.
