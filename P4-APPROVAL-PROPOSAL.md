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

## 2. Vorgeschlagener Mechanismus (im Workflow verlangt, aber nicht eingerichtet)

Bereits umgesetzt, fail closed:

- Der Paketjob beider Lieferworkflows verlangt `environment: p4-delivery`. GitHub startet ihn erst, wenn ein
  Required Reviewer dieses Environments dem konkreten Lauf zugestimmt hat.
- **Codex NO-GO 3: „irgendein Required Reviewer“ genügt nicht mehr.** Die Freigabesperre (`tools/delivery/lock.py`)
  verlangt über die API:
  - `can_admins_bypass` ist ausdrücklich `false` (Administratoren dürfen den Schutz nicht umgehen; ein fehlendes Feld
    zählt als nicht erfüllt);
  - „Prevent self-review“ (`prevent_self_review`) ist an;
  - die Required Reviewers sind **genau** die im Workflow festgelegten Benutzer `LOCK_REVIEWERS` (nur Einzelkonten,
    keine Teams, weil die Mitgliedschaft eines Zustimmenden nicht nachweisbar ist);
  - keiner dieser Reviewer ist der auslösende Eigentümer (`GITHUB_ACTOR`);
  - **im Paketjob** (Paketstart, vor dem Signieren, vor dem Upload): für **diesen** Lauf liegt laut
    `GET actions/runs/<id>/approvals` eine Zustimmung („approved“) eines festgelegten Reviewers ≠ Auslöser für
    `p4-delivery` vor und keine Ablehnung. Eine Umgehung ohne Zustimmung (z. B. Admin-Bypass) sperrt.
- `LOCK_REVIEWERS` ist in beiden Workflows leer (`''`). Damit ist die Sperre zusätzlich zu `DELIVERY_APPROVAL: 'none'`
  geschlossen. Eintragen darf es nur der Eigentümer im Rahmen der Einrichtung unten.
- **Grenze, ehrlich benannt:** Kein Workflow kann prüfen, dass hinter der Reviewer-Identität ein Mensch steht, dessen
  Zugang kein Agent besitzt. Die Prüfungen belegen nur Einrichtung und Zustimmung einer **anderen GitHub-Identität**.
- Die Freigabe ist gebunden an:
  - die Quell-SHAs (Helper `SOURCE_SHA` und `KADRI_PAIR_SHA`; Kadri `SOURCE_SHA`, `HELPER_SHA` und
    `WASM_CORE_ORIGIN_SHA`);
  - den Lieferumfang `SCOPE` (`helper-p4test` bzw. `kadri-p4test`);
  - eine einmalige `ID` und `UNTIL` (höchstens 72 h).
  Sie gilt nur für den Push, der sie einführt, und nur im ersten Versuch.
- Der Paketjob führt die volle Sperre selbst erneut aus: vor dem Checkout, unmittelbar vor dem Signieren (Helper) und
  unmittelbar vor dem Upload. Ein Re-Run mit wiederverwendeter Gate-Ausgabe (`GITHUB_RUN_ATTEMPT > 1`), eine
  zwischenzeitlich abgelaufene Freigabe oder eine anderweitig verbrauchte ID sperren dort.

### 2a. Reviewer-Identität, Admin-Bypass und Rechtetrennung

| Einstellung (Settings → Environments → `p4-delivery`) | erforderlich | warum |
|---|---|---|
| Required reviewers | genau **ein separates Reviewer-Konto** (oder ein zweiter Mensch), **nicht** das Konto, das pusht | Der Auslöser darf nicht selbst zustimmen. |
| Prevent self-review | **an** | Der Auslöser kann seinen eigenen Lauf nicht freigeben. |
| Allow administrators to bypass configured protection rules | **aus** | Sonst kann jedes Admin-Konto – auch ein Agent mit Admin-Token – ohne Zustimmung starten. |
| Deployment branches | nur `feature/c3-browser-downloads` (Helper) bzw. `feature/c3-helper-mp4` (Kadri) | Kein anderer Branch erreicht das Environment. |
| Workflow-Variable `LOCK_REVIEWERS` | Login des Reviewer-Kontos (in beiden Workflows, alle Lock-Stufen) | Die Sperre vergleicht exakt. |

**Rechtetrennung:**

- Das Reviewer-Konto braucht nur **Lesezugriff** auf beide Repositories (Mindestrecht für Required Reviewers), keine
  Admin-, Schreib- oder Environment-Rechte.
- Es wird **nie** mit Claude, einer GitHub-App, einem Agenten, einem Personal Access Token oder einer CI verbunden.
  Anmeldung nur mit eigenem Passkey/Hardware-Schlüssel, in einem eigenen Browserprofil.
- Der Coding-Agent (GitHub-App bzw. Token) sollte **kein** `Administration`-Recht haben. Lesend geprüft hat er heute
  `admin: true`. Mit Admin kann er Environments, Workflows und Läufe ändern oder löschen. Die neuen Prüfungen erkennen
  eine geänderte Environment-Einrichtung zur Laufzeit und verlangen die Zustimmung des festgelegten Reviewer-Kontos;
  eine **Zustimmung im Namen dieses Kontos** kann der Agent ohne dessen Zugangsdaten nicht erzeugen. Löschen von
  Läufen (Einmaligkeit) und Ändern von Regeln bleibt mit Admin-Recht möglich, siehe Abschnitt 3.

### 2b. Konflikt: Eigentümer als Auslöser und „Prevent self-review“

- Die Sperre verlangt, dass der Push der Freigabe vom Repo-Eigentümer kommt (`GITHUB_ACTOR == owner`).
- Ist derselbe Eigentümer auch der einzige Required Reviewer **und** „Prevent self-review“ an, kann **niemand**
  zustimmen: der Lauf bleibt bis zum Zeitlimit hängen (keine Lieferung – sicher, aber nicht funktionsfähig).
- Ist „Prevent self-review“ aus, stimmt der Eigentümer seinem eigenen Lauf zu. Das ist kein Vier-Augen-Prinzip, und
  ein Agent mit den Zugangsdaten des Eigentümers könnte über die API (`pending_deployments`) dasselbe tun. Die Sperre
  weist diese Einrichtung deshalb ab (Reviewer = Auslöser, Self-Review nicht verhindert).

### 2c. Funktionsfähige, sicher getrennte Abläufe

**A) Vier-Augen-Verfahren (empfohlen):**

1. Ein zweiter Mensch mit eigenem GitHub-Konto (nur Lesezugriff) ist der einzige Required Reviewer; sein Login steht
   in `LOCK_REVIEWERS`.
2. Der Eigentümer trägt die Freigabe ein und pusht einmal (Commit-Zeile `Delivery-Approval: <ID>`).
3. Die Sperre prüft Bindung, Neueinführung, Einmaligkeit, Gültigkeit und die Environment-Einrichtung.
4. Der zweite Mensch prüft in der GitHub-Oberfläche genau diesen Lauf (Commit, SHAs, ID, Workflow-Diff) und stimmt zu.
5. Der Paketjob prüft vor Checkout, Signieren und Upload erneut, einschließlich der Zustimmung dieses Laufs.

**B) Ein-Personen-Verfahren mit getrennter Identität** (wenn kein zweiter Mensch verfügbar ist):

1. Der Eigentümer legt ein **zweites, eigenes GitHub-Konto** („Release-Konto“) an: eigener Passkey/Hardware-Schlüssel,
   eigenes Browserprofil, nie mit Claude, Apps, Tokens oder CI verbunden, nur Lesezugriff auf beide Repositories.
2. Das Release-Konto ist der einzige Required Reviewer; sein Login steht in `LOCK_REVIEWERS`.
3. Freigabe-Push mit dem Eigentümerkonto wie oben; Zustimmung ausschließlich mit dem Release-Konto.
4. **Grenze:** Das trennt die Freigabe vom Agenten (er besitzt die Zugangsdaten des Release-Kontos nicht), ist aber
   **kein** Vier-Augen-Prinzip – es bleibt derselbe Mensch.

**Manueller Einrichtungsschritt durch den Eigentümer** (nur in der GitHub-Oberfläche, nicht durch den Agenten):

1. In beiden Repositories `p4-delivery` laut Tabelle 2a anlegen.
2. `LOCK_REVIEWERS` in beiden Workflows auf das Reviewer-Login setzen (Commit des Eigentümers, getrennt von jeder
   Freigabe).
3. Dem Coding-Agenten das `Administration`-Recht entziehen (GitHub-App-Berechtigungen bzw. Token).
4. Danach prüft ein unabhängiger Review, dass der Agent keine Admin- und Deployment-Rechte mehr hat (Beleg: ein
   API-Aufruf des Agenten auf `PUT /environments` wird abgelehnt) und dass die Sperre die Einrichtung als geschützt
   erkennt.

**Ablauf einer späteren, einmaligen Freigabe** (nur nach Ihrer ausdrücklichen Entscheidung): wie 2c A bzw. B; danach
wird `DELIVERY_APPROVAL` wieder auf `'none'` gesetzt.

## 3. Vorschlag: gegen Agentenänderungen geschützte Verbrauchsregistrierung (NICHT umgesetzt)

**Heutiger Stand:** Eine ID gilt als verbraucht, wenn ein Lauf des Lieferworkflows sie trägt. Diese Actions-Historie
kann jedes Admin-Konto löschen – auch ein Agent mit Admin-Token. **Dauerhafte Einmaligkeit wird deshalb nicht
behauptet.** Abgemildert wird das dadurch, dass jede Lieferung zusätzlich die Zustimmung des Reviewer-Kontos für genau
diesen Lauf braucht.

**Vorschlag** (erfordert Einrichtung durch den Eigentümer; mit den heutigen Adminrechten des Agenten im
Haupt-Repository allein nicht durchsetzbar):

1. **Getrenntes Ledger-Repository** (z. B. `delivery-ledger`), Eigentümer ist das Reviewer- bzw. Release-Konto.
   Die Agenten-App ist dort **nicht** installiert; das Eigentümerkonto hat höchstens Lesezugriff.
2. **Append-only:** Branch-Schutz mit „Require signed commits“, keine Force-Pushes, keine Löschungen, „Do not allow
   bypassing“ für Administratoren. Nur das Ledger-Konto ist dort Admin.
3. **Schreibweg:** Ein Fine-grained Token des Ledger-Kontos, begrenzt auf `contents: write` nur für dieses Repository,
   liegt als **Environment-Secret von `p4-delivery`**. Er ist nur in einem Lauf verfügbar, dem das Reviewer-Konto
   zugestimmt hat. Der Paketjob legt **vor dem Signieren** `consumed/<ID>` per Contents-API ohne `sha` an. Das ist
   reines Anlegen: existiert die Datei schon, antwortet die API mit 422 und die Lieferung sperrt (atomar, einmalig).
4. **Leseweg:** Gate und jede Lock-Stufe lesen `consumed/<ID>` und sperren, wenn die Datei existiert.
5. **Grenzen:**
   - Ein Admin des Haupt-Repositories könnte den Workflow so ändern, dass er das Secret missbraucht. Das wirkt aber
     nur in einem Lauf, dem das Reviewer-Konto zustimmt; der Reviewer muss den Workflow-Diff prüfen.
   - Gelangt der Token des Ledger-Kontos nach außen, ist das Ledger fälschbar.
   - Solange der Agent Admin im Haupt-Repository ist, kann er die Prüfung im Workflow entfernen. Auch dann bleibt die
     Zustimmung des Reviewer-Kontos nötig.

## 4. Entwurf: einmalige Diagnoseausnahme (NICHT aktiviert, NICHT erteilt)

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
    3. die Datei ist durch die Environment-Zustimmung des festgelegten Reviewer-Kontos für genau diesen Lauf gedeckt;
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
