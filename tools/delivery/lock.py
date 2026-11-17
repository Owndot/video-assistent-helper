#!/usr/bin/env python3
"""Freigabesperre für Testlieferungen (Codex Release-Readiness P1). Läuft im Workflow VOR jedem Checkout.

Diese Datei ist die maßgebliche Kopie. Der Job "gate" der Lieferworkflows enthält sie wörtlich als Inline-Skript
(tests/delivery/test_lock.py prüft die Byte-Gleichheit und die Workflow-Struktur). Helper und Kadri nutzen dieselbe Datei.

Standard: gesperrt. DELIVERY_APPROVAL: 'none' -> go=no, nichts wird ausgecheckt, gebaut, signiert oder hochgeladen.

Eine Freigabe ist genau ein Wert der env-Variable DELIVERY_APPROVAL in der Workflowdatei, in einfachen Anführungszeichen:
    SOURCE_SHA=<40hex>;<PAAR>=<40hex>[;...];SCOPE=<Lieferumfang>;ID=<id>;UNTIL=<YYYY-MM-DDTHH:MMZ>
Die Schlüssel sind genau die in LOCK_BIND genannten Pins plus SCOPE (= LOCK_SCOPE, der Lieferumfang), ID und UNTIL. Jeder Wert muss dem Pin derselben
Workflowdatei gleichen. Wer einen Pin ändert, ohne die Freigabe neu zu erteilen, sperrt damit die Lieferung.

Die Freigabe gilt nur einmal und nur für den Push, der sie einführt:
  * Ereignis push auf genau LOCK_BRANCH, erster Versuch (keine Wiederholung eines Laufs), Auslöser = Repo-Eigentümer;
  * der Kopf-Commit dieses Pushes trägt die Zeile "Delivery-Approval: <ID>" in der Commit-Nachricht;
  * am Stand vor dem Push (event.before) stand ein anderer DELIVERY_APPROVAL-Wert. Ein späterer Doku-Commit, eine
    Pin-Korrektur oder ein anderer Push erbt die Zeile unverändert und wird deshalb abgewiesen;
  * kein anderer Lauf dieses Workflows (irgendein Branch, irgendein Ergebnis) trägt dieselbe ID oder denselben Commit.
    Eine benutzte ID ist verbraucht, auch wenn die Lieferung danach scheiterte – GRENZE (Codex NO-GO 3): geprüft wird
    die Actions-Historie; wer Läufe löschen kann (Repo-Admin, auch ein Agent mit Adminrechten), kann eine ID wieder
    "frei" machen. Dauerhafte Einmaligkeit ist damit NICHT gewährleistet; Vorschlag einer gegen Agentenänderungen
    geschützten Verbrauchsregistrierung: P4-APPROVAL-PROPOSAL.md (nicht umgesetzt, nicht aktiviert);
  * UNTIL liegt in der Zukunft und höchstens 72 Stunden entfernt.
  * das GitHub-Environment LOCK_ENVIRONMENT existiert, Administratoren dürfen es NICHT umgehen (can_admins_bypass
    false), "Prevent self-review" ist an, die Required Reviewers sind GENAU die im Workflow festgelegten Benutzer
    (LOCK_REVIEWERS, nur Benutzer, keine Teams) und keiner davon ist der Auslöser/Eigentümer. "Irgendein Reviewer" genügt
    nicht (Codex NO-GO 3);
  * nur im Paketjob (LOCK_REQUIRE_GO=1): für DIESEN Lauf liegt eine Zustimmung ("approved") eines festgelegten Reviewers
    (≠ Auslöser) für LOCK_ENVIRONMENT vor und keine Ablehnung – eine Umgehung ohne Zustimmung sperrt.
Diese Prüfungen belegen nur die EINRICHTUNG und die ZUSTIMMUNG einer anderen GitHub-Identität. Dass hinter ihr ein
unabhängiger Mensch steht, dessen Zugang kein Agent besitzt, kann kein Workflow prüfen (Pflicht des Eigentümers, siehe
P4-APPROVAL-PROPOSAL.md).
Jede nicht prüfbare Bedingung (API-Fehler, fehlende Ereignisdaten) zählt als nicht erfüllt (fail closed).

Codex Release-Readiness 2: Dieselbe Prüfung läuft im Paketjob erneut (LOCK_REQUIRE_GO=1) – vor dem Checkout, vor dem
Signieren und vor dem Upload. Ein Re-Run eines fehlgeschlagenen Paketjobs (GITHUB_RUN_ATTEMPT > 1, Gate-Ausgabe
wiederverwendet), eine inzwischen abgelaufene Freigabe oder eine anderweitig verbrauchte ID sperren dort fail closed.

Exit 0 mit go=no: gesperrt (Normalzustand). Exit 0 mit go=yes: freigegeben.
Exit 1 mit go=no: Freigabe eingetragen, aber ungültig (sichtbar roter Lauf).
"""
import base64
import datetime
import json
import os
import re
import subprocess
import sys

APPROVAL_LINE = re.compile(r"^\s*DELIVERY_APPROVAL:\s*'([^'\n]*)'\s*(#.*)?$", re.M)
HEX40 = re.compile(r'^[0-9a-f]{40}$')
ID_RE = re.compile(r'^[a-z0-9][a-z0-9-]{7,39}$')
UNTIL_RE = re.compile(r'^\d{4}-\d\d-\d\dT\d\d:\d\dZ$')
MAX_VALIDITY = datetime.timedelta(hours=72)
CLOSED = ('', 'none')


def approval_in(text):
    """DELIVERY_APPROVAL-Wert einer Workflowdatei; None, wenn die Datei fehlt; ValueError bei mehrdeutiger Datei."""
    if text is None:
        return None
    found = [m.group(1) for m in APPROVAL_LINE.finditer(text)]
    if len(found) != 1:
        raise ValueError('expected exactly one DELIVERY_APPROVAL line, found %d' % len(found))
    return found[0]


def parse(value, bind):
    """'K=V;K=V' -> dict; Schlüssel genau bind + ID + UNTIL, keine Dubletten."""
    out = {}
    for part in value.split(';'):
        if '=' not in part:
            raise ValueError('malformed approval part %r' % part)
        k, v = part.split('=', 1)
        if k in out:
            raise ValueError('duplicate approval key %s' % k)
        out[k] = v
    want = set(bind) | {'SCOPE', 'ID', 'UNTIL'}
    if set(out) != want:
        raise ValueError('approval keys %s, expected %s' % (sorted(out), sorted(want)))
    return out


def protected(env_info, expected, actor):
    """Environment-Antwort der GitHub-API -> (geschützt, Grund). Codex NO-GO 3: Required Reviewers allein genügen nicht.
    Verlangt: keine Admin-Umgehung, Prevent self-review an, Reviewer = genau die festgelegten Benutzer, Auslöser keiner davon."""
    env_info = env_info or {}
    rules = [r for r in env_info.get('protection_rules') or [] if r.get('type') == 'required_reviewers']
    if len(rules) != 1:
        return False, 'environment has no (or more than one) required reviewers rule'
    rule = rules[0]
    reviewers = rule.get('reviewers') or []
    if not reviewers:
        return False, 'environment has no required reviewers'
    if env_info.get('can_admins_bypass') is not False:
        return False, 'administrators may bypass the environment protection (can_admins_bypass %s)' % env_info.get('can_admins_bypass')
    if rule.get('prevent_self_review') is not True:
        return False, 'prevent self-review is not enabled (%s)' % rule.get('prevent_self_review')
    if any(r.get('type') != 'User' for r in reviewers):
        return False, 'reviewers must be individual users (team membership of an approver is not provable)'
    logins = [(r.get('reviewer') or {}).get('login') for r in reviewers]
    if not expected:
        return False, 'no reviewer identity pinned in the workflow (LOCK_REVIEWERS)'
    if sorted(set(logins)) != sorted(set(expected)) or len(logins) != len(set(logins)):
        return False, 'reviewers %s are not exactly the pinned reviewers %s' % (sorted(logins), sorted(expected))
    if actor and actor in logins:
        return False, 'the triggering owner %s is a reviewer (no second identity)' % actor
    return True, 'reviewers %s (pinned), no admin bypass, self-review prevented' % sorted(logins)


def reviewed(records, ctx):
    """Zustimmungen des Laufs (GET actions/runs/<id>/approvals) -> (zugestimmt, Grund)."""
    env = ctx['environment']
    mine = [a for a in records or [] if env in [e.get('name') for e in a.get('environments') or []]]
    if any(a.get('state') != 'approved' for a in mine):
        return False, 'a review of this run for %s is not "approved"' % env
    ok = [a for a in mine if a.get('state') == 'approved'
          and (a.get('user') or {}).get('login') in ctx.get('reviewers', [])
          and (a.get('user') or {}).get('login') != ctx['actor']]
    if not ok:
        return False, 'no approval of this run for %s by a pinned reviewer other than %s' % (env, ctx['actor'] or '(missing)')
    return True, 'approved by %s' % ok[0]['user']['login']


def decide(ctx, fetch_workflow_at, list_runs, now, fetch_environment=None, fetch_approvals=None):
    """-> (go, reasons, approval_id). Reine Funktion; fetch_workflow_at/list_runs dürfen Ausnahmen werfen."""
    value = ctx['approval']
    if value in CLOSED:
        return False, ['delivery lock closed (DELIVERY_APPROVAL none)'], None
    reasons = []
    try:
        a = parse(value, ctx['bind'])
    except ValueError as e:
        return False, ['approval unreadable: %s' % e], None
    aid = a['ID']
    if not ID_RE.match(aid):
        reasons.append('approval ID %r has the wrong form' % aid)
    for name in ctx['bind']:
        pin = ctx['pins'].get(name, '')
        if not HEX40.match(a[name]):
            reasons.append('approved %s is not a full commit SHA' % name)
        elif a[name] != pin:
            reasons.append('approved %s %s differs from the pinned %s' % (name, a[name], pin or '(missing)'))
    if a['SCOPE'] != ctx.get('scope'):
        reasons.append('approval scope %r is not this delivery (%r)' % (a['SCOPE'], ctx.get('scope')))
    if not UNTIL_RE.match(a['UNTIL']):
        reasons.append('UNTIL %r has the wrong form' % a['UNTIL'])
    else:
        until = datetime.datetime.strptime(a['UNTIL'], '%Y-%m-%dT%H:%MZ').replace(tzinfo=datetime.timezone.utc)
        if until <= now:
            reasons.append('approval expired at %s' % a['UNTIL'])
        elif until - now > MAX_VALIDITY:
            reasons.append('approval valid for more than 72 hours (until %s)' % a['UNTIL'])
    if ctx['event_name'] != 'push':
        reasons.append('event %s (only the push that introduces the approval may deliver)' % ctx['event_name'])
    if ctx['ref'] != 'refs/heads/' + ctx['branch']:
        reasons.append('ref %s is not refs/heads/%s' % (ctx['ref'], ctx['branch']))
    if ctx['run_attempt'] != '1':
        reasons.append('run attempt %s (a re-run never delivers again)' % ctx['run_attempt'])
    if not ctx['actor'] or ctx['actor'] != ctx['owner']:
        reasons.append('actor %s is not the repository owner' % (ctx['actor'] or '(missing)'))
    if ctx['head_id'] != ctx['sha']:
        reasons.append('head commit %s is not the workflow commit %s' % (ctx['head_id'] or '(missing)', ctx['sha']))
    marker = 'Delivery-Approval: ' + aid
    if marker not in (ctx['head_message'] or '').splitlines():
        reasons.append('head commit message lacks the line "%s"' % marker)
    before = ctx['before'] or ''
    if not HEX40.match(before) or before == '0' * 40:
        reasons.append('no previous branch state to compare (before=%s)' % (before or '(missing)'))
    else:
        try:
            prev = approval_in(fetch_workflow_at(before))
        except Exception as e:  # fail closed
            reasons.append('cannot read the previous approval at %s: %s' % (before, e))
        else:
            if prev == value:
                reasons.append('approval not introduced by this push (already present at %s)' % before)
    if not ctx.get('environment'):
        reasons.append('no protected environment configured for the package job')
    else:
        try:
            ok, why = protected(fetch_environment(ctx['environment']) if fetch_environment else None, ctx.get('reviewers') or [], ctx['actor'])
        except Exception as e:  # fail closed
            ok, why = False, 'cannot read environment %s: %s' % (ctx['environment'], e)
        if not ok:
            reasons.append('environment %s not protected: %s' % (ctx['environment'], why))
        if ctx.get('require_review'):
            try:
                rok, rwhy = reviewed(fetch_approvals() if fetch_approvals else None, ctx)
            except Exception as e:  # fail closed
                rok, rwhy = False, 'cannot read the approvals of this run: %s' % e
            if not rok:
                reasons.append('package stage without the reviewer approval: %s' % rwhy)
    try:
        runs = list_runs()
    except Exception as e:  # fail closed
        reasons.append('cannot list earlier runs of this workflow: %s' % e)
    else:
        for r in runs:
            if str(r.get('id')) == str(ctx['run_id']):
                continue
            if marker in (r.get('message') or '').splitlines():
                reasons.append('approval ID %s already used by run %s' % (aid, r.get('id')))
            if r.get('head_sha') == ctx['sha']:
                reasons.append('commit %s already ran this workflow in run %s' % (ctx['sha'], r.get('id')))
    return (not reasons), reasons, aid


def gh_json(path):
    p = subprocess.run(['gh', 'api', '-H', 'Accept: application/vnd.github+json', path],
                       capture_output=True, text=True, timeout=120)
    if p.returncode != 0:
        raise RuntimeError('gh api %s failed: %s' % (path.split('?')[0], (p.stderr or '').strip()[:200]))
    return json.loads(p.stdout)


def main():
    env = os.environ
    repo = env['GITHUB_REPOSITORY']
    wf = env['LOCK_WORKFLOW']
    bind = env['LOCK_BIND'].split()
    event = {}
    if env.get('GITHUB_EVENT_PATH') and os.path.exists(env['GITHUB_EVENT_PATH']):
        event = json.load(open(env['GITHUB_EVENT_PATH']))
    head = event.get('head_commit') or {}
    ctx = {
        'approval': env.get('DELIVERY_APPROVAL', ''), 'bind': bind, 'pins': {n: env.get(n, '') for n in bind},
        'event_name': env.get('GITHUB_EVENT_NAME', ''), 'ref': env.get('GITHUB_REF', ''), 'branch': env['LOCK_BRANCH'],
        'sha': env.get('GITHUB_SHA', ''), 'before': event.get('before', ''), 'run_attempt': env.get('GITHUB_RUN_ATTEMPT', ''),
        'run_id': env.get('GITHUB_RUN_ID', ''), 'actor': env.get('GITHUB_ACTOR', ''),
        'owner': env.get('GITHUB_REPOSITORY_OWNER', ''), 'head_id': head.get('id', ''), 'head_message': head.get('message', ''),
        'scope': env.get('LOCK_SCOPE', ''), 'environment': env.get('LOCK_ENVIRONMENT', ''),
        'reviewers': env.get('LOCK_REVIEWERS', '').split(), 'stage': env.get('LOCK_STAGE', 'gate'),
        'require_review': env.get('LOCK_REQUIRE_GO') == '1',
    }

    def fetch_workflow_at(sha):
        p = subprocess.run(['gh', 'api', 'repos/%s/contents/%s?ref=%s' % (repo, wf, sha)],
                           capture_output=True, text=True, timeout=120)
        if p.returncode != 0:
            if 'HTTP 404' in (p.stderr or ''):
                return None
            raise RuntimeError((p.stderr or '').strip()[:200])
        return base64.b64decode(json.loads(p.stdout)['content']).decode('utf-8')

    def list_runs():
        out, name = [], os.path.basename(wf)
        for page in range(1, 31):
            batch = gh_json('repos/%s/actions/workflows/%s/runs?per_page=100&page=%d' % (repo, name, page))['workflow_runs']
            out += [{'id': r['id'], 'head_sha': r['head_sha'], 'message': (r.get('head_commit') or {}).get('message', '')}
                    for r in batch]
            if len(batch) < 100:
                return out
        raise RuntimeError('more than 3000 runs: cannot prove the approval is unused')

    def fetch_environment(name):
        return gh_json('repos/%s/environments/%s' % (repo, name))

    def fetch_approvals():
        return gh_json('repos/%s/actions/runs/%s/approvals' % (repo, ctx['run_id']))

    now = datetime.datetime.now(datetime.timezone.utc)
    go, reasons, aid = decide(ctx, fetch_workflow_at, list_runs, now, fetch_environment, fetch_approvals)
    for r in reasons:
        print('DELIVERY_LOCK ' + r)
    print('DELIVERY_LOCK stage=%s verdict=%s approval_id=%s pins=%s' % (env.get('LOCK_STAGE', 'gate'), 'GO' if go else 'LOCKED', aid or '-',
                                                               ' '.join('%s=%s' % (n, ctx['pins'][n]) for n in bind)))
    if env.get('GITHUB_OUTPUT'):
        with open(env['GITHUB_OUTPUT'], 'a') as f:
            f.write('go=%s\napproval_id=%s\n' % ('yes' if go else 'no', aid if go else ''))
    if env.get('LOCK_REQUIRE_GO') == '1' and not go:
        sys.exit(1)   # Paketjob: ohne gültige Freigabe JETZT kein Schritt weiter (auch nach Re-Run oder Ablauf)
    sys.exit(0 if go or ctx['approval'] in CLOSED else 1)


if __name__ == '__main__':
    main()
