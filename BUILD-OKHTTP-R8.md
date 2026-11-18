# OkHttp 5.5.1 und R8 im Helper (Phase B)

> **Phase C1:** `HttpClients` (ungehärteter Standardclient) ist entfernt; OkHttp ist nur noch über `de.evp.helfer.net` erreichbar – siehe `NETWORK-SECURITY-C1.md`.

Stand: PR „OkHttp 5.5.1 with R8“ auf Basis von Phase A (AGP 9.1.1, Gradle 9.3.1, compileSdk 37, targetSdk 34).
Noch **keine** neue Download-Funktion und **kein** SSRF-Schutz – das folgt in Phase C. Produktionsstatus: UNAPPROVED.

## Was eingebaut ist

| Teil | Umsetzung |
|---|---|
| Abhängigkeit | `com.squareup.okhttp3:okhttp-android:5.5.1` (zieht okio 3.18.1, kotlin-stdlib 2.2.21, androidx.annotation 1.10.0, androidx.startup 1.2.0, androidx.tracing 1.0.0) |
| Nutzung | nur `HttpClients` (einziger Ort für OkHttp-Clients); keine Funktion verwendet ihn bis Phase C |
| R8 | `release` und `channeltest` mit **identischen** Regeln; `debug` unverändert ohne R8 |
| App- und JNI-Code | `-keep class de.evp.helfer.** { *; }`, `-keep class poc.** { *; }`: nichts davon wird entfernt oder umbenannt. Der Rust-Kern nutzt per JNI nur `poc.Core` (native Methoden) und `java.lang.SecurityException` |
| Umbenennung | aus (`-dontobfuscate`): lesbare Stacktraces ohne Mapping-Datei; Test-APK und App verwenden dieselben Namen |
| Test-Keep-Regeln | `app/r8-test-references.pro`, erzeugt von `:app:traceTestKeepRules` (R8 TraceReferences): was die Instrumentierungs-APK aus dem Laufzeit-Klassenpfad der App nutzt (154 Regeln: Kotlin, androidx.annotation/tracing, OkHttp-API der Tests). `tools/build-channel-apk.sh` bricht ab, wenn die Datei veraltet ist oder die App nicht von R8 verarbeitet wurde |
| AndroidX-Startkomponente | `androidx.startup.InitializationProvider` per `tools:node="remove"` aus dem Manifest entfernt (androidx.startup: „Disable automatic initialization“). Sie setzte nur den Anwendungs-Context; das macht jetzt `OkHttp.initialize` (offizielle API für genau diesen Fall). `PlatformInitializer` bleibt als toter Code: die R8-Regel von androidx.startup (`-keep class * extends androidx.startup.Initializer`) behält ihn |

### Warum es den NoClassDefFoundError gab und wie er behoben ist

AGP nimmt Bibliotheken, die auch die App enthält (Kotlin, androidx.tracing, OkHttp …), nicht in die Test-APK auf, berücksichtigt
aber beim Schrumpfen der App nicht, was die Tests davon brauchen (AGP-Issue 126429384). R8 entfernte daher z. B.
`kotlin.jvm.internal.Lambda` und `androidx.tracing.Trace`, und der Testrunner stürzte vor dem ersten Test ab. Die aus der
Test-APK erzeugten Keep-Regeln gelten für alle geschrumpften Varianten, damit die getestete APK dieselben Regeln trägt wie
die ausgelieferte. Bei Teständerungen: `:app:traceTestKeepRules` ausführen und die erzeugte Datei (ohne Kopfzeilen)
übernehmen; die CI prüft das bei jedem Kanal-Build.

## Gerätetests (API-34-Emulator, geschrumpfte Testvariante)

19 Tests, alle grün: die 15 bestehenden Kanal-Tests (Kopplung, Noise KK, Steuerbefehle, Widerruf, Neustart, langsame
Clients, fremder Port-Inhaber, kein Dienst auf 47811, Bildschirm- und Backup-Schutz, Logcat ohne Geheimnisse) und
`OkHttpInstrumentedTest`:

- echte HTTP-Anfragen gegen einen lokalen Server auf 127.0.0.1: Content-Length, 4 MiB chunked gestreamt (SHA-256 geprüft),
  302-Weiterleitung, alle vier Anfragen über **eine** Pool-Verbindung;
- echte HTTPS-Anfrage: TLS 1.3 (`TLS_AES_128_GCM_SHA256`) über die Android-TLS-Plattform von OkHttp; Server-Identität ist
  ein Wegwerf-EC-Schlüssel im AndroidKeyStore, der Client vertraut nur diesem Zertifikat;
- Startkomponente nicht registriert, Public-Suffix-Liste trotzdem verfügbar (`topPrivateDomain`);
- die installierte App ist die R8-Ausgabe: nicht debugfähig, `InitializationProvider` und `RealWebSocket` entfernt,
  App- und JNI-Klassen unter ihren Namen vorhanden.

## Größe

Testvariante (signiert, Wegwerf-Schlüssel) und unsignierte Release-APK, gemessen in CI:

| | Phase A | Phase B | Differenz |
|---|---|---|---|
| Testvariante APK | 1.344.830 B | 1.568.916 B | +224.086 B (+16,7 %) |
| Release-APK (unsigniert) | 124.215 B | 350.045 B | +225.830 B |
| dex (unkomprimiert) | 135.332 B, 100 Klassen | 489.296 B, 527 Klassen | |
| installiert, nach Installation | 1.503.722 B | 2.092.092 B | +588.370 B |
| installiert, nach `speed`-Kompilierung | 1.975.178 B | 3.526.916 B | +1.551.738 B |

Aufschlüsselung (unsignierte Release-APK): mit Umbenennung wäre sie 8.136 B kleiner (341.909 B); ohne Test-Keep-Regeln
194.125 B – die Test-Keep-Regeln kosten also 155.920 B. Ein großer Teil davon ist Kotlin-Code, den nur der Testrunner
braucht (u. a. `kotlin.coroutines`). Ohne sie wäre OkHttp heute fast vollständig entfernt, weil noch keine Funktion es
nutzt; die Netzwerkschicht aus Phase C wird einen Teil davon ohnehin benötigen. Neue Einträge in der APK:
`assets/PublicSuffixDatabase.list`, Baseline-Profile, `kotlin/*.kotlin_builtins`, Lizenz- und Versionsdateien.
Berechtigungen unverändert.

## Reproduzierbarkeit

Zwei Builds aus verschiedenen Pfaden: App- und Test-APK bis auf die Signaturdateien byte-identisch (R8 deterministisch).
Native Bibliotheken unverändert (`5b991a3d…` arm64-v8a, `6079121…` x86_64).

## Sicherheits- und Release-Risiken

| # | Risiko | Bewertung |
|---|---|---|
| R1 | Neue Laufzeit-Abhängigkeiten (7 Artefakte) ohne Gradle-Abhängigkeitsprüfung (`verification-metadata.xml`); Integrität hängt an TLS zu Maven Central/Google Maven | vor einem Release Checksummen-Prüfung einführen |
| R2 | `HttpClients` liefert einen OkHttp-Standardclient (folgt Weiterleitungen, nutzt System-Proxys, wiederholt bei 503 `Retry-After: 0`) | darf bis Phase C nicht verwendet werden; Phase C ersetzt die Konfiguration |
| R3 | Test-Keep-Regeln vergrößern die ausgelieferte APK (≈ 156 KB, hauptsächlich Kotlin-Code nur für den Testrunner) | bewusster Tausch gegen „getestete = ausgelieferte Regeln“; alternativ nur für `channeltest` (dann ist die Release-APK nicht exakt die getestete) |
| R4 | Versionsabweichung androidx.tracing: Testrunner erwartet 1.1-API (`forceEnableAppTracing`), App liefert 1.0.0 (TraceReferences-Warnung) | betrifft nur Tests mit aktiviertem Tracing; keine Auswirkung auf die App |
| R5 | AGP 9: die zwei Phase-A-Opt-outs (`android.builtInKotlin=false`, `android.sdk.defaultTargetSdkToCompileSdkIfUnset=false`) sind veraltet und fallen mit AGP 10 weg | vor AGP 10 Test-targetSdk explizit setzen |
| R6 | compileSdk 37 bei minSdk 26 und `lint { abortOnError false; checkReleaseBuilds false }`: falsche Nutzung neuer APIs bricht den Build nicht | bestehende Lint-Einstellung; `NewApi` als Fehler empfehlenswert |
| R7 | Gradle-Wrapper-JAR stammt aus Gradle 8.9 und startet 9.3.1 (Distribution per SHA-256 festgelegt, JAR von der Wrapper-Prüfung als offiziell bestätigt) | funktional; JAR bei Gelegenheit erneuern |
| R8 | R8-optimierter Bibliothekscode nur auf x86_64-Emulator (API 34) getestet | Gerätetest (arm64, Samsung) vor einer Freigabe |
| R9 | Keine Umbenennung: Code leichter lesbar | ohne Belang (Quelltext öffentlich); Vorteil: Stacktraces ohne Mapping |
| R10 | CI: `tools/build-channel-apk.sh … \| tee` ohne `pipefail` verdeckte Build-Fehler in `helper-channel.yml` | in diesem PR behoben. Gleiches Muster noch in `build.yml` (apksigner-Ausgabe), `firefox-stable-validation.yml`, `poc-android-capability.yml` |

Keine neue exportierte Komponente, keine neue Berechtigung; mit der entfernten Startkomponente läuft beim Prozessstart kein
zusätzlicher Bibliothekscode.

## Nicht automatisch getestete, manuelle Workflows

Diese Workflows bauen die App, starten nur von Hand (`workflow_dispatch` bzw. eigene Zweige) und **laden installierbare
APKs als öffentliche Artefakte hoch**. Ohne ausdrückliche Freigabe wurden sie weder in Phase A noch in Phase B ausgeführt;
in Phase A wurde nur ihre Gradle-Version auf 9.3.1 gesetzt.

| Workflow | Prüft | hochgeladene Artefakte | Risiko, solange ungetestet |
|---|---|---|---|
| `migration-tests.yml` | Update-Installationen über ältere Fixture-Versionen (Daten bleiben erhalten) auf mehreren API-Stufen | `migration-fixtures-TEST-ONLY` (APKs) | Update-Pfad mit R8-Release-APK unbelegt |
| `helper-performance.yml` | Debug-Build gegen stabile Basis, Cookie-Verhalten auf API 33 | `helper-baseline-APKs-TEST-ONLY` (APKs) | Leistungsvergleich fehlt |
| `android-e2e.yml` / `android-e2e-inspect.yml` | Ende-zu-Ende mit Firefox Android und Testidentitäten | `android-e2e-bundle-TEST-ONLY` (APKs) | kein Ende-zu-Ende-Lauf mit neuem Build |

Vorschlag: vor einer Freigabe diese Workflows mit gesperrtem APK-Upload (Text-Bericht wie im Kanal-Workflow) ausführen.
