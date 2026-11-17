#!/usr/bin/env python3
"""Selbsttest der Freigabesperre (Codex Release-Readiness 1+2). Baut, signiert und lädt nichts.

Aufruf:  test_lock.py WORKFLOW BRANCH "PIN1 PIN2 ..." [SIGN_STEP_PREFIX]
Prüft:
  1. Jeder LOCKPY-Block des Workflows (Job "gate" und Paketjob) ist byte-gleich mit tools/delivery/lock.py.
  2. Struktur: nur push auf BRANCH mit genau dieser Workflowdatei; nur Jobs gate und package; gate ohne Checkout;
     package hängt von gate ab, läuft nur bei go == 'yes', verlangt das geschützte Environment p4-delivery und führt
     die VOLLE Sperre selbst erneut aus (LOCK_REQUIRE_GO=1): als ersten Schritt vor jeder Action, unmittelbar vor dem
     Signierschritt (falls vorhanden) und unmittelbar vor dem Upload; keine Schreibrechte.
  3. Freigabezustand der Workflowdatei: 'none' = LOCKED; eine syntaktisch gültige, an Pins und Lieferumfang gebundene
     Freigabe = PENDING (noch nicht ausgeführt, die echte Prüfung erfolgt erst im Lauf); alles andere = FEHLER.
     So blockiert die normale CI eine gültige Freigabe nicht, ohne sie als erteilt zu werten.
  4. Entscheidung (nur Attrappen): gesperrt im Normalzustand; jede einzelne verletzte Bedingung sperrt, einschließlich
     Re-Run des Paketjobs mit wiederverwendeter Gate-Ausgabe, Ablauf zwischen Gate und Paketbau, fehlendem Schutz des
     Environments und falschem Lieferumfang; nur die vollständige Freigabe öffnet (Positivkontrolle).
  5. Mutationsprobe: jede Abschwächung einer Sperrbedingung wird erkannt.
  6. Ende-zu-Ende mit dem eingebetteten Skript: gesperrt → im Gate Exit 0/go=no, im Paketjob (LOCK_REQUIRE_GO=1) Exit 1.
"""
import datetime
import os
import re
import subprocess
import sys
import tempfile

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
WORKFLOW, BRANCH, BIND = sys.argv[1], sys.argv[2], sys.argv[3].split()
SIGN_STEP = sys.argv[4] if len(sys.argv) > 4 else None
LOCK = os.path.join(ROOT, 'tools', 'delivery', 'lock.py')
fails = []


def check(cond, what):
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


text = open(os.path.join(ROOT, WORKFLOW), encoding='utf-8').read()
wf = yaml.safe_load(text)
lock_src = open(LOCK, encoding='utf-8').read()
ns = {'__name__': 'lock_under_test'}
exec(compile(lock_src, LOCK, 'exec'), ns)
decide, parse = ns['decide'], ns['parse']

# 1) alle eingebetteten Kopien == maßgebliche Datei
jobs = wf.get('jobs', {})
blocks = []
for jn, j in jobs.items():
    for s in j.get('steps', []):
        for m in re.finditer(r"<<'LOCKPY'\n(.*?\n)LOCKPY\n", s.get('run', '') or '', re.S):
            blocks.append((jn, m.group(1)))
check([jn for jn, _ in blocks] == ['gate', 'package'], 'exactly one embedded lock in gate and one in package (%s)' % [jn for jn, _ in blocks])
check(all(b == lock_src for _, b in blocks), 'every embedded lock is tools/delivery/lock.py byte for byte')

# 2) Struktur
gate, pkg = jobs.get('gate', {}), jobs.get('package', {})
lock_steps = [s for s in gate.get('steps', []) if s.get('id') == 'lock']
check(len(lock_steps) == 1, 'gate job has exactly one step with id lock')
genv = lock_steps[0].get('env', {}) if lock_steps else {}
SCOPE = genv.get('LOCK_SCOPE', '')
check(bool(re.match(r'^[a-z0-9-]{4,40}$', SCOPE)), 'gate binds a delivery scope (%r)' % SCOPE)
check(genv.get('LOCK_ENVIRONMENT') == 'p4-delivery', 'gate requires the protected environment p4-delivery')
check(all('uses' not in s for s in gate.get('steps', [])), 'gate job uses no action (no checkout before the decision)')
check('needs' not in gate, 'gate job runs first')
check(genv.get('LOCK_BIND') == ' '.join(BIND), 'LOCK_BIND binds exactly %s' % BIND)
check(genv.get('LOCK_BRANCH') == BRANCH and genv.get('LOCK_WORKFLOW') == WORKFLOW, 'LOCK_BRANCH/LOCK_WORKFLOW match')
check(gate.get('outputs', {}).get('go') == '${{ steps.lock.outputs.go }}', 'gate exports go from the lock step')
on = wf.get(True, wf.get('on'))
check(list(on) == ['push'], 'only trigger is push (no dispatch, schedule, pull_request, workflow_run)')
check(on['push'] == {'branches': [BRANCH], 'paths': [WORKFLOW]}, 'push only on %s and only for this workflow file' % BRANCH)
check(sorted(jobs) == ['gate', 'package'], 'only jobs gate and package')
check(pkg.get('needs') in ('gate', ['gate']), 'package needs gate')
check(pkg.get('if') == "needs.gate.outputs.go == 'yes'", "package runs only if gate said go == 'yes'")
check(pkg.get('environment') == 'p4-delivery', 'package job waits for the required reviewers of environment p4-delivery')
steps = pkg.get('steps', [])


def is_lock_rerun(s, stage):
    e = s.get('env', {}) or {}
    return (e.get('LOCK_REQUIRE_GO') == '1' and e.get('LOCK_STAGE') == stage and e.get('LOCK_SCOPE') == SCOPE
            and e.get('LOCK_BIND') == ' '.join(BIND) and e.get('LOCK_ENVIRONMENT') == 'p4-delivery'
            and 'python3 -I "$RUNNER_TEMP/delivery_lock.py"' in (s.get('run') or ''))


first_action = next((i for i, s in enumerate(steps) if 'uses' in s), len(steps))
check(bool(steps) and is_lock_rerun(steps[0], 'package-start') and first_action > 0,
      'package re-runs the full lock in its first step, before any action/checkout')
up = [i for i, s in enumerate(steps) if 'upload-artifact' in str(s.get('uses', ''))]
check(len(up) == 1 and up[0] > 0 and is_lock_rerun(steps[up[0] - 1], 'before-upload'), 'full lock re-run immediately before the upload')
check(all('sha256sum -c "$RUNNER_TEMP/delivery_lock.sha256"' in (s.get('run') or '') for s in steps
          if (s.get('env') or {}).get('LOCK_STAGE') in ('before-signing', 'before-upload')), 're-runs verify the lock copy hash')
if SIGN_STEP:
    sg = [i for i, s in enumerate(steps) if (s.get('name') or '').startswith(SIGN_STEP)]
    check(len(sg) == 1 and is_lock_rerun(steps[sg[0] - 1], 'before-signing'), 'full lock re-run immediately before signing')
perms = wf.get('permissions', {})
check(perms and all(v in ('read', 'none') for v in perms.values()), 'workflow permissions read-only')
check(all('permissions' not in j for j in jobs.values()), 'no job widens permissions')
env = wf.get('env', {})
check(len(re.findall(r'(?m)^\s*DELIVERY_APPROVAL:', text)) == 1, 'exactly one DELIVERY_APPROVAL line')
for n in BIND:
    check(bool(re.match(r'^[0-9a-f]{40}$', str(env.get(n, '')))), 'pin %s is a full commit SHA' % n)


# 3) Freigabezustand der Workflowdatei
def approval_state(value, pins):
    if value in ('', 'none'):
        return 'LOCKED', ''
    try:
        a = parse(value, BIND)
    except ValueError as e:
        return 'INVALID', str(e)
    bad = [n for n in BIND if a[n] != pins.get(n)]
    if bad or a['SCOPE'] != SCOPE or not re.match(r'^[a-z0-9][a-z0-9-]{7,39}$', a['ID']) or not re.match(r'^\d{4}-\d\d-\d\dT\d\d:\d\dZ$', a['UNTIL']):
        return 'INVALID', 'not bound to pins %s / scope / ID / UNTIL format' % bad
    return 'PENDING', 'valid form; the real decision happens only in the delivery run'


state, why = approval_state(env.get('DELIVERY_APPROVAL', ''), {n: env.get(n) for n in BIND})
print('DELIVERY_APPROVAL state: %s %s' % (state, why))
check(state in ('LOCKED', 'PENDING'), 'workflow approval is LOCKED or a valid pending approval (state %s)' % state)
PINS_WF = {n: env.get(n) for n in BIND}
valid_pending = ';'.join(['%s=%s' % (n, PINS_WF[n]) for n in BIND] + ['SCOPE=' + SCOPE, 'ID=p4-test-0001', 'UNTIL=2026-11-18T12:00Z'])
check(approval_state(valid_pending, PINS_WF)[0] == 'PENDING', 'a valid owner approval is recognised as PENDING (not rejected by CI)')
for label, v in [('wrong source', valid_pending.replace(PINS_WF[BIND[0]], 'e' * 40)), ('wrong scope', valid_pending.replace('SCOPE=' + SCOPE, 'SCOPE=other-scope')),
                 ('missing key', valid_pending.replace(';UNTIL=2026-11-18T12:00Z', '')), ('free text', 'yes please')]:
    check(approval_state(v, PINS_WF)[0] == 'INVALID', 'CI rejects an invalid approval: %s' % label)

# 4) Entscheidung
NOW = datetime.datetime(2026, 11, 17, 12, 0, tzinfo=datetime.timezone.utc)
PINS = {n: ('%x' % (i + 10)) * 40 for i, n in enumerate(BIND)}
PINS = {n: v[:40] for n, v in PINS.items()}
OWNER, SHA, BEFORE = 'owner', 'c' * 40, 'b' * 40
PROTECTED = {'name': 'p4-delivery', 'protection_rules': [{'type': 'required_reviewers', 'reviewers': [{'type': 'User', 'reviewer': {'login': 'owner'}}]}]}


def approval(**over):
    a = dict(PINS)
    a.update(SCOPE=SCOPE, ID='p4-test-0001', UNTIL='2026-11-18T12:00Z')
    a.update(over)
    return ';'.join('%s=%s' % kv for kv in a.items() if kv[1] is not None)


def ctx(**over):
    c = {'approval': approval(), 'bind': BIND, 'pins': dict(PINS), 'event_name': 'push', 'ref': 'refs/heads/' + BRANCH,
         'branch': BRANCH, 'sha': SHA, 'before': BEFORE, 'run_attempt': '1', 'run_id': '900', 'actor': OWNER, 'owner': OWNER,
         'head_id': SHA, 'head_message': 'deliver\n\nDelivery-Approval: p4-test-0001\n', 'scope': SCOPE, 'environment': 'p4-delivery'}
    c.update(over)
    return c


def prev_file(value):
    return lambda sha: "env:\n  DELIVERY_APPROVAL: '%s'\n" % value


def runs(*extra):
    return lambda: [{'id': 900, 'head_sha': SHA, 'message': 'deliver\n\nDelivery-Approval: p4-test-0001'}] + list(extra)


def boom(*a):
    raise RuntimeError('API unreachable')


def verdict(c, fetch=prev_file('none'), lr=runs(), now=NOW, envf=lambda name: PROTECTED, dec=None):
    return (dec or decide)(c, fetch, lr, now, envf)


go, why, _ = verdict(ctx(approval='none'))
check(not go and why == ['delivery lock closed (DELIVERY_APPROVAL none)'], 'closed by default: none -> locked')
go, why, _ = verdict(ctx(approval=''))
check(not go, 'empty approval -> locked')
go, why, aid = verdict(ctx())
check(go and not why and aid == 'p4-test-0001', 'positive control: complete, new, unused, protected approval -> go (fakes only)')
go, _, _ = verdict(ctx(), fetch=lambda sha: None)
check(go, 'positive control: workflow file absent before the push counts as newly introduced')
go, _, _ = verdict(ctx(approval=valid_pending.replace(PINS_WF[BIND[0]], PINS[BIND[0]]).replace(PINS_WF[BIND[-1]], PINS[BIND[-1]]) if len(BIND) == 2 else approval()))
check(go, 'positive control: the PENDING form accepted by CI is also accepted by the decision (fakes only)')

NEG = [
    ('pin changed after the approval', ctx(pins=dict(PINS, **{BIND[0]: 'd' * 40})), {}),
    ('approval names another source commit', ctx(approval=approval(**{BIND[0]: 'e' * 40})), {}),
    ('approval misses a bound pin', ctx(approval=approval(**{BIND[-1]: None})), {}),
    ('approval carries an extra key', ctx(approval=approval(EXTRA='1')), {}),
    ('approval without scope', ctx(approval=approval(SCOPE=None)), {}),
    ('approval for another delivery scope', ctx(approval=approval(SCOPE='kadri-p4test' if SCOPE != 'kadri-p4test' else 'helper-p4test')), {}),
    ('approval with a short SHA', ctx(approval=approval(**{BIND[0]: PINS[BIND[0]][:7]}), pins=dict(PINS, **{BIND[0]: PINS[BIND[0]][:7]})), {}),
    ('duplicate key', ctx(approval=approval() + ';ID=p4-test-0002'), {}),
    ('malformed approval', ctx(approval='yes'), {}),
    ('bad ID', ctx(approval=approval(ID='X'), head_message='Delivery-Approval: X'), {}),
    ('expired', ctx(approval=approval(UNTIL='2026-11-17T11:59Z')), {}),
    ('expired between gate and package build', ctx(approval=approval(UNTIL='2026-11-17T12:30Z')), {'now': NOW + datetime.timedelta(minutes=45)}),
    ('valid longer than 72 h', ctx(approval=approval(UNTIL='2026-11-21T12:01Z')), {}),
    ('UNTIL malformed', ctx(approval=approval(UNTIL='tomorrow')), {}),
    ('workflow_dispatch', ctx(event_name='workflow_dispatch'), {}),
    ('pull_request', ctx(event_name='pull_request'), {}),
    ('other branch', ctx(ref='refs/heads/main'), {}),
    ('re-run of the whole run (attempt 2)', ctx(run_attempt='2'), {}),
    ('re-run of only the failed package job (gate output reused, attempt 3)', ctx(run_attempt='3'), {}),
    ('actor is not the owner', ctx(actor='someone-else'), {}),
    ('head commit is not the workflow commit', ctx(head_id='f' * 40), {}),
    ('commit message lacks the approval line', ctx(head_message='docs: fix pins'), {}),
    ('approval line for another ID', ctx(head_message='Delivery-Approval: p4-test-0002'), {}),
    ('inherited by a later docs or pin commit', ctx(head_message='docs\n\nDelivery-Approval: p4-test-0001'), {'fetch': prev_file(approval())}),
    ('new branch without previous state', ctx(before='0' * 40), {}),
    ('previous state missing', ctx(before=''), {}),
    ('previous approval unreadable (API error)', ctx(), {'fetch': boom}),
    ('previous file ambiguous', ctx(), {'fetch': lambda sha: "  DELIVERY_APPROVAL: 'none'\n  DELIVERY_APPROVAL: 'none'\n"}),
    ('run list unavailable (API error)', ctx(), {'lr': boom}),
    ('ID already used by an earlier run', ctx(), {'lr': runs({'id': 5, 'head_sha': 'a' * 40, 'message': 'x\nDelivery-Approval: p4-test-0001'})}),
    ('same commit already ran this workflow', ctx(), {'lr': runs({'id': 6, 'head_sha': SHA, 'message': 'x'})}),
    ('environment not configured', ctx(environment=''), {}),
    ('environment without required reviewers', ctx(), {'envf': lambda n: {'name': n, 'protection_rules': [{'type': 'wait_timer', 'wait_timer': 5}]}}),
    ('environment with an empty reviewer list', ctx(), {'envf': lambda n: {'name': n, 'protection_rules': [{'type': 'required_reviewers', 'reviewers': []}]}}),
    ('environment unreadable (API error)', ctx(), {'envf': boom}),
]
for what, c, kw in NEG:
    go, why, _ = verdict(c, **kw)
    check(not go and bool(why), 'locked: %s (%s)' % (what, why[0] if why else 'NO REASON'))


def decision_failures(dec):
    bad = 0
    for c, kw, want in [(ctx(approval='none'), {}, False), (ctx(), {}, True)] + [(c, kw, False) for _, c, kw in NEG]:
        try:
            go = verdict(c, dec=dec, **kw)[0]
        except Exception:
            go = None
        bad += go is not want
    return bad


# 5) Mutationsprobe
MUTANTS = {
    'closed state opens': ("return False, ['delivery lock closed (DELIVERY_APPROVAL none)'], None", "return True, [], 'x'"),
    'pin not compared': ("elif a[name] != pin:", "elif False:"),
    'scope ignored': ("if a['SCOPE'] != ctx.get('scope'):", "if False:"),
    'expiry ignored': ("if until <= now:", "if False:"),
    'any event': ("if ctx['event_name'] != 'push':", "if False:"),
    'any branch': ("if ctx['ref'] != 'refs/heads/' + ctx['branch']:", "if False:"),
    're-run allowed': ("if ctx['run_attempt'] != '1':", "if False:"),
    'any actor': ("if not ctx['actor'] or ctx['actor'] != ctx['owner']:", "if False:"),
    'no approval line needed': ("if marker not in (ctx['head_message'] or '').splitlines():", "if False:"),
    'inherited approval allowed': ("if prev == value:", "if False:"),
    'used ID allowed': ("if marker in (r.get('message') or '').splitlines():", "if False:"),
    'run list error ignored': ("reasons.append('cannot list earlier runs of this workflow: %s' % e)", "pass"),
    'previous state error ignored': ("reasons.append('cannot read the previous approval at %s: %s' % (before, e))", "pass"),
    'environment protection ignored': ("        if not ok:\n            reasons.append('environment %s not protected: %s' % (ctx['environment'], why))", "        pass"),
    'environment API error ignored': ("ok, why = False, 'cannot read environment %s: %s' % (ctx['environment'], e)", "ok, why = True, 'x'"),
}
check(decision_failures(decide) == 0, 'unmutated lock answers every decision case correctly')
for what, (a, b) in MUTANTS.items():
    if lock_src.count(a) != 1:
        check(False, 'mutant anchor missing: %s' % what)
        continue
    mns = {'__name__': 'lock_mutant'}
    exec(compile(lock_src.replace(a, b), LOCK, 'exec'), mns)
    check(decision_failures(mns['decide']) > 0, 'mutant killed: %s' % what)

# 6) Ende-zu-Ende mit dem eingebetteten Skript (ohne gh auf PATH)
with tempfile.TemporaryDirectory() as tmp:
    script = os.path.join(tmp, 'inline_lock.py')
    open(script, 'w').write(blocks[-1][1] if blocks else '')
    out = os.path.join(tmp, 'out')
    event = os.path.join(tmp, 'event.json')
    open(event, 'w').write('{"before": "%s", "head_commit": {"id": "%s", "message": "x\\nDelivery-Approval: p4-test-0001"}}' % (BEFORE, SHA))
    base = dict(PATH=os.path.join(tmp, 'nobin'), GITHUB_REPOSITORY='o/r', LOCK_WORKFLOW=WORKFLOW, LOCK_BIND=' '.join(BIND),
                LOCK_BRANCH=BRANCH, LOCK_SCOPE=SCOPE, LOCK_ENVIRONMENT='p4-delivery', GITHUB_EVENT_NAME='push',
                GITHUB_REF='refs/heads/' + BRANCH, GITHUB_SHA=SHA, GITHUB_RUN_ATTEMPT='1', GITHUB_RUN_ID='900', GITHUB_ACTOR=OWNER,
                GITHUB_REPOSITORY_OWNER=OWNER, GITHUB_EVENT_PATH=event, GITHUB_OUTPUT=out, **PINS)
    soon = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=1)).strftime('%Y-%m-%dT%H:%MZ')
    for label, extra, code in [('gate, closed', {'DELIVERY_APPROVAL': 'none'}, 0),
                               ('gate, approval set but GitHub API unreachable', {'DELIVERY_APPROVAL': approval(UNTIL=soon)}, 1),
                               ('package job, closed (LOCK_REQUIRE_GO=1)', {'DELIVERY_APPROVAL': 'none', 'LOCK_REQUIRE_GO': '1'}, 1),
                               ('package job re-run (attempt 2) with reused gate output', {'DELIVERY_APPROVAL': approval(UNTIL=soon), 'LOCK_REQUIRE_GO': '1',
                                                                                           'GITHUB_RUN_ATTEMPT': '2'}, 1),
                               ('package job after expiry', {'DELIVERY_APPROVAL': approval(UNTIL='2020-01-01T00:00Z'), 'LOCK_REQUIRE_GO': '1'}, 1)]:
        open(out, 'w').close()
        p = subprocess.run([sys.executable, '-I', script], env=dict(base, **extra), capture_output=True, text=True)
        o = open(out).read()
        check(p.returncode == code and 'go=no\n' in o and 'go=yes' not in o,
              'end-to-end %s: exit %d, go=no (%s)' % (label, p.returncode, (p.stdout.strip().splitlines() or [p.stderr[-150:]])[-1][:110]))

print('DELIVERY_LOCK_SELFTEST %s %d checks failed' % ('FAIL' if fails else 'PASS', len(fails)))
sys.exit(1 if fails else 0)
