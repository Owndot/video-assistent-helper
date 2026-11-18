# P4-Testlieferung – gesicherter Entwurf, nicht aktiviert

> **Überholt (2026-11-17):** maßgeblich ist [P4-DELIVERY-READINESS.md](P4-DELIVERY-READINESS.md). Die Pins unten
> (`bd5ab29` als Helper-Quelle) und `DELIVERY_REQUESTED` gelten nicht mehr; der Workflow nutzt die Freigabesperre.

Stand 2026-11-16 (Claude-Fortsetzung). **BLOCKED** – der API-34-Speicherfehler ist lokalisiert, aber nicht behoben:
`IOException: publish` in `Mp4Downloads.PendingEntry.commit()` (MediaStore-Update `IS_PENDING=0`/`DISPLAY_NAME`
betraf nicht genau einen Eintrag; Lauf 43874560604, Helper `d2f3884`). Ursache des Updates offen, Diagnosesonde
`MediaStorePublishProbeTest` ab `52702f6`. Kein Paket ausgeliefert, aktive Workflows unverändert.

**Vor Aktivierung zusätzlich nötig (aus dieser Prüfung):**
- `SOURCE_SHA` kann nicht mehr `bd5ab29` sein, sobald ein Fix oder geänderte Gerätetests gebraucht werden;
  die Scope-Prüfung erlaubt nach `SOURCE_SHA` nur diese Workflowdatei und Root-Markdown.
- Das CI-Gate erkennt bisher nur Fehlschläge des Hauptlaufs. Die neue Sonde läuft als eigener Schritt und
  meldet `PUBLISH_PROBE_STATUS ok|failed|skipped`. Im API-29-Ausnahmefall muss das Gate zusätzlich
  `PUBLISH_PROBE_STATUS ok` verlangen, sonst würde ein Sondenfehlschlag auf API 29 als P4B-A10-SIZE durchgehen.
- API 29 lief in `43846760700` einmal mit 16 MiB Java-Heap (Emulator verlangte 512m; ART-Standardwert) und
  scheiterte dadurch zusätzlich mit `memory` im Remux-Abbruchtest; in `43874560604` wieder 512 MiB und grün.
  Umgebungsbefund, kein App-Befund; das Gate lässt einen solchen Lauf korrekt nicht durch.

Vor Anwendung: maßgebliche `P4C3-HANDOFF.md` und Claude-Übergabe im Kadri-Repository lesen,
alle relevanten CI-Ergebnisse des exakten Quellstands prüfen. Nur P4B-A10-SIZE ist als
Gerätetest-Ausnahme freigegeben. Ein erneuter grüner Lauf erklärt den ersten Speicherfehler nicht.
Bei neuen Codeänderungen müssen die Quell-SHAs und Artefaktnamen angepasst werden.
Bestehender Branch und bestehender Workflow; keine Produktionssignierung, Tags oder Releases.

Der neue Wegwerf-Signierer verlangt beim Gerätewechsel ausschließlich die isolierte P4-App
zu ersetzen und erneut zu koppeln. Vorher laufende P4-Aufträge abschließen; keine anderen
Installationen ändern. Kadri-P4 nur aktualisieren. Noch keine Installationsaufforderung.

```diff
diff --git a/.github/workflows/p4-test-package.yml b/.github/workflows/p4-test-package.yml
index c8c5098..431fc28 100644
--- a/.github/workflows/p4-test-package.yml
+++ b/.github/workflows/p4-test-package.yml
@@ -30,8 +30,8 @@ concurrency:
   cancel-in-progress: false

 env:
-  SOURCE_SHA: 8b906c504f0612ff4af69a397a2745b717f156b3
-  KADRI_PAIR_SHA: ef9a5ed22920e8bee0a204323388b9104e58892e
+  SOURCE_SHA: bd5ab293bbc6729cabafe72b6bd5606f8c74f5e5
+  KADRI_PAIR_SHA: 7978a41232c3b1582289c53461da4fe63ad28ff9
   KADRI_P4_EXTENSION_ID: pcccpjllneffccpenbepmfbkjedllkkf
   NDK_VERSION: 27.2.12479018
   RETENTION_DAYS: 2
@@ -335,7 +335,7 @@ jobs:
             'workflow_run': f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
             'built_at': now.isoformat(), 'artifact_expires_at': (now + datetime.timedelta(days=days)).isoformat(),
             'known_limitation': 'P4B-A10-SIZE: Android 10 MediaStore SIZE column stays 0 (accepted by the owner only for the Android 17 test).',
-            'changes': 'P4c-2: automatic resume (backoff, connectivity), notification resume while the app instance lives, screen-off/restart regression',
+            'changes': 'Address-free HLS planning failure phase and processed-playlist count only. Existing download, resume, cancellation and network boundaries unchanged. Real HTTP 404/412 remains open.',
             'production_status': 'UNAPPROVED',
           }
           open(os.path.join(d, 'p4test-evidence.json'), 'w').write(json.dumps(ev, indent=2) + '\n')
@@ -347,7 +347,7 @@ jobs:
           [ "$(find "$D" -type f | wc -l)" = 3 ]
       - uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4
         with:
-          name: helper-p4test-8b906c5-run${{ github.run_id }}
+          name: helper-p4test-bd5ab29-run${{ github.run_id }}
           path: ${{ runner.temp }}/deliver
           retention-days: ${{ env.RETENTION_DAYS }}
           if-no-files-found: error
```
