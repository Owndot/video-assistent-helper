# P4-Lieferung – Vorschlag für eine unabhängige menschliche Freigabe und Entwurf einer Diagnoseausnahme

Stand 2026-11-17. **Nur Vorschlag und Entwurf.** Nichts davon ist eingerichtet, aktiviert oder erteilt. Beide
Lieferworkflows bleiben `LOCKED` (`DELIVERY_APPROVAL: 'none'`). Ein dokumentierter Entwurf ist keine Freigabe.

## 1. Befund: Was eine Freigabe heute NICHT unabhängig macht

- **Codex hat recht:** Eine Freigabe ist nicht unabhängig, solange sie nur aus diesen Bestandteilen besteht:
  - dem GitHub-Actor;
  - der Commit-Zeile `Delivery-Approval: <ID>`;
  - dem Wert `DELIVERY_APPROVAL` in der Workflowdatei.
- **Grund:** Der Coding-Agent pusht mit den Zugangsdaten des Eigentümers und erscheint dabei als Eigentümer.
- **Lesend geprüft:** Diese Zugangsdaten haben in beiden Repositories `admin: true`. Der Agent könnte also auch
  Environment-Schutzregeln anlegen, ändern oder entfernen. Dass der Proxy dieser Sitzung den Environments-Pfad
  sperrt, ist eine Sitzungseinstellung und keine verlässliche Grenze.
- **`workflow_dispatch` allein ist kein Schutz:**
  - Der Agent kann denselben Aufruf ausführen.
  - Zusätzlich müssen die Lieferworkflows auf `main` liegen, damit `workflow_dispatch` überhaupt nutzbar ist.
- **Folge:** Mit den vorhandenen Berechtigungen ist eine unabhängige menschliche Freigabe **technisch nicht
  durchsetzbar**. Sie braucht den folgenden manuellen Einrichtungsschritt durch den Eigentümer.

## 2. Vorgeschlagener Mechanismus (ab jetzt im Workflow verlangt, aber nicht eingerichtet)

Bereits umgesetzt, fail closed:

- Der Paketjob beider Lieferworkflows verlangt `environment: p4-delivery`. GitHub startet ihn erst, wenn ein
  **Required Reviewer** dieses Environments dem konkreten Lauf in der GitHub-Oberfläche zugestimmt hat.
- Die Freigabesperre (`tools/delivery/lock.py`) prüft vor jedem Schritt über die API, dass `p4-delivery` existiert und
  Required Reviewers hat. Fehlt das Environment oder der Schutz, oder ist er nicht lesbar, bleibt die Sperre zu.
  Heute ist das Environment nicht eingerichtet, die Sperre damit doppelt zu.
- Die Freigabe ist gebunden an:
  - die Quell-SHAs (Helper `SOURCE_SHA` und `KADRI_PAIR_SHA`; Kadri `SOURCE_SHA`, `HELPER_SHA` und
    `WASM_CORE_ORIGIN_SHA`);
  - den Lieferumfang `SCOPE` (`helper-p4test` bzw. `kadri-p4test`);
  - eine einmalige `ID` und `UNTIL` (höchstens 72 h).
  Sie gilt nur für den Push, der sie einführt, und nur im ersten Versuch.
- Der Paketjob führt die volle Sperre selbst erneut aus:
  - vor dem Checkout;
  - unmittelbar vor dem Signieren (Helper);
  - unmittelbar vor dem Upload.

  Ein Re-Run des Paketjobs mit wiederverwendeter Gate-Ausgabe (`GITHUB_RUN_ATTEMPT > 1`), eine zwischenzeitlich
  abgelaufene Freigabe oder eine anderweitig verbrauchte ID sperren dort.

**Manueller Einrichtungsschritt durch den Eigentümer**, nur in der GitHub-Oberfläche und nicht durch den Agenten:

1. In beiden Repositories unter Settings → Environments das Environment `p4-delivery` anlegen.
   - „Required reviewers“: das menschliche Konto des Eigentümers.
   - „Deployment branches“: nur `feature/c3-browser-downloads` (Helper) bzw. `feature/c3-helper-mp4` (Kadri).
2. Dem Coding-Agenten die Rechte entziehen, die die Freigabe umgehen könnten:
   - kein `Administration`-Recht auf diese Repositories;
   - kein Recht, Environments zu ändern;
   - kein Recht, wartende Deployments zu genehmigen.

   Konkret: die Agenten-Verbindung (GitHub-App bzw. Token des Agenten) auf Inhalte, Pull Requests und Actions-Lesen
   beschränken. Solange der Agent mit einem Admin-Token des Eigentümers handelt, kann er das Environment ändern und
   Deployments genehmigen; dann ist die Freigabe **nicht** unabhängig, auch mit Environment.
3. Danach erst prüft ein unabhängiger Review, ob der Agent wirklich keine Admin- und Deployment-Rechte mehr hat.
   Beleg dafür: ein API-Aufruf des Agenten auf `PUT /environments` wird abgelehnt.

**Ablauf einer späteren, einmaligen Freigabe** (nur nach Ihrer ausdrücklichen Entscheidung):

1. Der Eigentümer trägt `DELIVERY_APPROVAL: '<SHAs>;SCOPE=…;ID=…;UNTIL=…'` ein. Er committet mit
   `Delivery-Approval: <ID>` und pusht einmal.
2. Die Sperre prüft Bindung, Neueinführung, Einmaligkeit und Gültigkeit sowie das geschützte Environment.
3. GitHub hält den Paketjob an. Der Eigentümer prüft in der Oberfläche genau diesen Lauf (Commit, SHAs, ID) und
   stimmt zu.
4. Der Paketjob prüft die Sperre erneut vor Checkout, Signieren und Upload. Danach wird `'none'` zurückgesetzt.

## 3. Entwurf: einmalige Diagnoseausnahme (NICHT aktiviert, NICHT erteilt)

Zweck: Eine bewusste, einzelne Entscheidung des Eigentümers soll das isolierte Samsung-Android-17-Testpaar trotz
bekannter roter CI-Befunde erlauben. Die Befunde werden dabei weder versteckt noch umgedeutet.

### Eigenschaften

- **Separate Datei** `evidence/diagnostic-exception.json`, im Repository versioniert. Sie enthält **jeden** Befund
  wörtlich als Eintrag:
  - Run-, Attempt- und Job-ID;
  - API;
  - Gate-Urteil;
  - SHA-256 des vollständigen Logs (vgl. `evidence/publish-failures/INDEX.md`);
  - eine Kurzbeschreibung.
- **Pflichtliste der Befunde.** Sie umfasst mindestens:
  - die `publish rows=0`-Fehler auf API 34: `b18a355` Job 134073182519, `be74008` Job 134085570218, `e5fb290`
    Job 133942049996 sowie die älteren `IOException: publish`/`failed:storage`-Läufe;
  - die Fälle auf API 29: `f3bf6a4` Job 134065500998 und `291cff1` Job 133674082860, jeweils mit OOM im
    Systemprozess;
  - die ungültige Emulatorumgebung auf API 29: `b18a355` Job 134073182399 (16 MiB Heap).
- **Bindung** an:
  - Freigabe-ID;
  - Helper `546af14`, Kadri `7978a41`, WASM-Herkunft `bd5ab29`;
  - Lieferumfang `helper-p4test` und `kadri-p4test`;
  - Testpaket `de.evp.helfer.p4test` und Erweiterungs-ID `pcccpjllneffccpenbepmfbkjedllkkf`;
  - die exakte Befundliste (SHA-256 der Datei);
  - Gültigkeit höchstens 72 h.
- **Wirkung nur im Paketjob.**
  - `ci_evidence.py` bliebe unverändert streng und gibt REJECT aus, mit allen Gründen im Log.
  - Eine eigene Prüfung `exception_check.py` würde danach verlangen:
    1. die Menge der tatsächlich abgewiesenen Jobs ist **gleich** der Liste in der Datei, und es gibt keinen
       zusätzlichen oder neuen Befund;
    2. jeder Log-Hash stimmt;
    3. die Datei ist durch die Environment-Zustimmung des Eigentümers für genau diesen Lauf gedeckt;
    4. die ID ist unverbraucht.
- **Nicht übertragbar:**
  - nie für Produktions-Workflows;
  - nie für andere SHAs, anderen Lieferumfang oder spätere Läufe;
  - ein neuer Befund oder eine geänderte Liste macht sie ungültig.
- **Sichtbarkeit:** Alle ursprünglichen FAIL- und REJECT-Befunde stehen weiter im Log und in der Evidenz der
  Lieferung (`ci_evidence_verdict: REJECT`, `diagnostic_exception: <ID>`, Befundliste). Sie werden nie als PASS
  ausgewiesen.

### Nicht umgesetzt

Die Datei, `exception_check.py` und jede Verdrahtung im Workflow existieren **nicht**. Ob es eine solche Ausnahme
geben soll, entscheidet allein der Eigentümer nach einem unabhängigen Review. Bis dahin sperrt `ci_evidence.py` jede
Lieferung, solange abgewiesene Läufe desselben Anwendungscodes existieren.
