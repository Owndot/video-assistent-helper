#!/usr/bin/env python3
"""Selbsttest der Freigabesperre (Codex Release-Readiness P1). Baut, signiert und lädt nichts.

Aufruf:  test_lock.py WORKFLOW BRANCH "PIN1 PIN2 ..."
Prüft:
  1. Der Job "gate" des Workflows enthält tools/delivery/lock.py byte-genau als Inline-Skript (keine Abweichung zwischen
     getesteter und ausgeführter Sperre) und läuft ohne Checkout.
  2. Struktur: nur push auf BRANCH mit genau dieser Workflowdatei als Pfad; nur die Jobs gate und package; package hängt
     von gate ab, läuft nur bei go == 'yes' und prüft die Sperre erneut, BEVOR irgendeine Action (Checkout) läuft;
     DELIVERY_APPROVAL steht auf 'none'; Pins sind volle Commit-SHAs; keine Schreibrechte.
  3. Entscheidung: gesperrt im Normalzustand; jede einzelne verletzte Bedingung -> gesperrt; nur die vollständige,
     neu eingeführte, unverbrauchte Freigabe öffnet (Positivkontrolle, nur mit Attrappen).
  4. Ende-zu-Ende: das Inline-Skript selbst mit Workflow-Umgebung -> go=no, Exit 0 (gesperrt) bzw. Exit 1 (ungültige
     Freigabe ohne erreichbare API: fail closed).
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
LOCK = os.path.join(ROOT, 'tools', 'delivery', 'lock.py')
fails = []


def check(cond, what):
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


text = open(os.path.join(ROOT, WORKFLOW), encoding='utf-8').read()
wf = yaml.safe_load(text)
lock_src = open(LOCK, encoding='utf-8').read()

# 1) Inline-Skript == maßgebliche Datei
jobs = wf.get('jobs', {})
gate = jobs.get('gate', {})
lock_steps = [s for s in gate.get('steps', []) if s.get('id') == 'lock']
check(len(lock_steps) == 1, 'gate job has exactly one step with id lock')
inline = None
if lock_steps:
    m = re.search(r"python3 -I - <<'PY'\n(.*?\n)PY\n", lock_steps[0].get('run', ''), re.S)
    inline = m.group(1) if m else None
check(inline == lock_src, 'gate step runs tools/delivery/lock.py byte for byte')
check(all('uses' not in s for s in gate.get('steps', [])), 'gate job uses no action (no checkout before the decision)')
check('needs' not in gate, 'gate job runs first')
genv = lock_steps[0].get('env', {}) if lock_steps else {}
check(genv.get('LOCK_BIND') == ' '.join(BIND), 'LOCK_BIND binds exactly %s' % BIND)
check(genv.get('LOCK_BRANCH') == BRANCH and genv.get('LOCK_WORKFLOW') == WORKFLOW, 'LOCK_BRANCH/LOCK_WORKFLOW match')
check(gate.get('outputs', {}).get('go') == '${{ steps.lock.outputs.go }}', 'gate exports go from the lock step')

# 2) Struktur
on = wf.get(True, wf.get('on'))
check(list(on) == ['push'], 'only trigger is push (no dispatch, schedule, pull_request, workflow_run)')
check(on['push'] == {'branches': [BRANCH], 'paths': [WORKFLOW]}, 'push only on %s and only for this workflow file' % BRANCH)
check(sorted(jobs) == ['gate', 'package'], 'only jobs gate and package')
pkg = jobs.get('package', {})
check(pkg.get('needs') in ('gate', ['gate']), 'package needs gate')
check(pkg.get('if') == "needs.gate.outputs.go == 'yes'", "package runs only if gate said go == 'yes'")
steps = pkg.get('steps', [])
first_action = next((i for i, s in enumerate(steps) if 'uses' in s), len(steps))
recheck = [i for i, s in enumerate(steps) if 'DELIVERY_LOCK_RECHECK' in (s.get('run') or '')]
check(recheck == [0] and first_action > 0, 'package re-checks the lock in its first step, before any action/checkout')
env = wf.get('env', {})
check(env.get('DELIVERY_APPROVAL') == 'none', "DELIVERY_APPROVAL is 'none' (lock closed)")
check(len(re.findall(r'(?m)^\s*DELIVERY_APPROVAL:', text)) == 1, 'exactly one DELIVERY_APPROVAL line')
for n in BIND:
    check(bool(re.match(r'^[0-9a-f]{40}$', str(env.get(n, '')))), 'pin %s is a full commit SHA' % n)
perms = wf.get('permissions', {})
check(perms and all(v in ('read', 'none') for v in perms.values()), 'workflow permissions read-only')
check(all('permissions' not in j for j in jobs.values()), 'no job widens permissions')
uploads = [(n, i) for n, j in jobs.items() for i, s in enumerate(j.get('steps', [])) if 'upload-artifact' in str(s.get('uses', ''))]
check(all(n == 'package' for n, _ in uploads), 'artifact upload only inside package')

# 3) Entscheidung
ns = {'__name__': 'lock_under_test'}
exec(compile(lock_src, LOCK, 'exec'), ns)
decide = ns['decide']
NOW = datetime.datetime(2026, 11, 17, 12, 0, tzinfo=datetime.timezone.utc)
PINS = {n: ('%x' % (i + 10)) * 40 for i, n in enumerate(BIND)}
PINS = {n: v[:40] for n, v in PINS.items()}
OWNER, SHA, BEFORE = 'owner', 'c' * 40, 'b' * 40


def approval(**over):
    a = dict(PINS)
    a.update(ID='p4-test-0001', UNTIL='2026-11-18T12:00Z')
    a.update(over)
    return ';'.join('%s=%s' % kv for kv in a.items() if kv[1] is not None)


def ctx(**over):
    c = {'approval': approval(), 'bind': BIND, 'pins': dict(PINS), 'event_name': 'push', 'ref': 'refs/heads/' + BRANCH,
         'branch': BRANCH, 'sha': SHA, 'before': BEFORE, 'run_attempt': '1', 'run_id': '900', 'actor': OWNER, 'owner': OWNER,
         'head_id': SHA, 'head_message': 'deliver\n\nDelivery-Approval: p4-test-0001\n'}
    c.update(over)
    return c


def prev_file(value):
    return lambda sha: "env:\n  DELIVERY_APPROVAL: '%s'\n" % value


def runs(*extra):
    return lambda: [{'id': 900, 'head_sha': SHA, 'message': 'deliver\n\nDelivery-Approval: p4-test-0001'}] + list(extra)


def boom(*a):
    raise RuntimeError('API unreachable')


def verdict(c, fetch=prev_file('none'), lr=runs(), now=NOW):
    return decide(c, fetch, lr, now)


go, why, _ = verdict(ctx(approval='none'))
check(not go and why == ['delivery lock closed (DELIVERY_APPROVAL none)'], 'closed by default: none -> locked')
go, why, _ = verdict(ctx(approval=''))
check(not go, 'empty approval -> locked')
go, why, aid = verdict(ctx())
check(go and not why and aid == 'p4-test-0001', 'positive control: complete, new, unused approval -> go (fakes only)')
go, _, _ = verdict(ctx(), fetch=lambda sha: None)
check(go, 'positive control: workflow file absent before the push counts as newly introduced')

NEG = [
    ('pin changed after the approval', ctx(pins=dict(PINS, **{BIND[0]: 'd' * 40})), {}),
    ('approval names another source commit', ctx(approval=approval(**{BIND[0]: 'e' * 40})), {}),
    ('approval misses a bound pin', ctx(approval=approval(**{BIND[-1]: None})), {}),
    ('approval carries an extra key', ctx(approval=approval(EXTRA='1')), {}),
    ('approval with a short SHA', ctx(approval=approval(**{BIND[0]: PINS[BIND[0]][:7]}), pins=dict(PINS, **{BIND[0]: PINS[BIND[0]][:7]})), {}),
    ('duplicate key', ctx(approval=approval() + ';ID=p4-test-0002'), {}),
    ('malformed approval', ctx(approval='yes'), {}),
    ('bad ID', ctx(approval=approval(ID='X'), head_message='Delivery-Approval: X'), {}),
    ('expired', ctx(approval=approval(UNTIL='2026-11-17T11:59Z')), {}),
    ('valid longer than 72 h', ctx(approval=approval(UNTIL='2026-11-21T12:01Z')), {}),
    ('UNTIL malformed', ctx(approval=approval(UNTIL='tomorrow')), {}),
    ('workflow_dispatch', ctx(event_name='workflow_dispatch'), {}),
    ('pull_request', ctx(event_name='pull_request'), {}),
    ('other branch', ctx(ref='refs/heads/main'), {}),
    ('re-run (attempt 2)', ctx(run_attempt='2'), {}),
    ('actor is not the owner', ctx(actor='someone-else'), {}),
    ('head commit is not the workflow commit', ctx(head_id='f' * 40), {}),
    ('commit message lacks the approval line', ctx(head_message='docs: fix pins'), {}),
    ('approval line for another ID', ctx(head_message='Delivery-Approval: p4-test-0002'), {}),
    ('inherited by a later docs or pin commit', ctx(head_message='docs\n\nDelivery-Approval: p4-test-0001'),
     {'fetch': prev_file(approval())}),
    ('new branch without previous state', ctx(before='0' * 40), {}),
    ('previous state missing', ctx(before=''), {}),
    ('previous approval unreadable (API error)', ctx(), {'fetch': boom}),
    ('previous file ambiguous', ctx(), {'fetch': lambda sha: "  DELIVERY_APPROVAL: 'none'\n  DELIVERY_APPROVAL: 'none'\n"}),
    ('run list unavailable (API error)', ctx(), {'lr': boom}),
    ('ID already used by an earlier run', ctx(), {'lr': runs({'id': 5, 'head_sha': 'a' * 40, 'message': 'x\nDelivery-Approval: p4-test-0001'})}),
    ('same commit already ran this workflow', ctx(), {'lr': runs({'id': 6, 'head_sha': SHA, 'message': 'x'})}),
]
for what, c, kw in NEG:
    go, why, _ = verdict(c, **kw)
    check(not go and bool(why), 'locked: %s (%s)' % (what, why[0] if why else 'NO REASON'))


def decision_failures(dec):
    """Wie viele Entscheidungsfälle eine (mutierte) decide-Funktion falsch beantwortet."""
    bad = 0
    for c, kw, want in [(ctx(approval='none'), {}, False), (ctx(), {}, True)] + [(c, kw, False) for _, c, kw in NEG]:
        try:
            go = dec(c, kw.get('fetch', prev_file('none')), kw.get('lr', runs()), NOW)[0]
        except Exception:
            go = None
        bad += go is not want
    return bad


# 3b) Mutationsprobe: jede Abschwächung einer Sperrbedingung muss mindestens einen Fall kippen
MUTANTS = {
    'closed state opens': ("return False, ['delivery lock closed (DELIVERY_APPROVAL none)'], None", "return True, [], 'x'"),
    'pin not compared': ("elif a[name] != pin:", "elif False:"),
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
}
check(decision_failures(decide) == 0, 'unmutated lock answers every decision case correctly')
for what, (a, b) in MUTANTS.items():
    if lock_src.count(a) != 1:
        check(False, 'mutant anchor missing: %s' % what)
        continue
    mns = {'__name__': 'lock_mutant'}
    exec(compile(lock_src.replace(a, b), LOCK, 'exec'), mns)
    check(decision_failures(mns['decide']) > 0, 'mutant killed: %s' % what)

# 4) Ende-zu-Ende mit dem Inline-Skript (Workflow-Umgebung, ohne gh auf PATH)
with tempfile.TemporaryDirectory() as tmp:
    script = os.path.join(tmp, 'inline_lock.py')
    open(script, 'w').write(inline or '')
    out = os.path.join(tmp, 'out')
    event = os.path.join(tmp, 'event.json')
    open(event, 'w').write('{"before": "%s", "head_commit": {"id": "%s", "message": "x\\nDelivery-Approval: p4-test-0001"}}' % (BEFORE, SHA))
    base = dict(PATH=os.path.join(tmp, 'nobin'), GITHUB_REPOSITORY='o/r', LOCK_WORKFLOW=WORKFLOW, LOCK_BIND=' '.join(BIND),
                LOCK_BRANCH=BRANCH, GITHUB_EVENT_NAME='push', GITHUB_REF='refs/heads/' + BRANCH, GITHUB_SHA=SHA,
                GITHUB_RUN_ATTEMPT='1', GITHUB_RUN_ID='900', GITHUB_ACTOR=OWNER, GITHUB_REPOSITORY_OWNER=OWNER,
                GITHUB_EVENT_PATH=event, GITHUB_OUTPUT=out, **PINS)
    for label, appr, code in [('closed', env.get('DELIVERY_APPROVAL', 'none'), 0),
                              ('approval set but GitHub API unreachable', approval(UNTIL=(datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=1)).strftime('%Y-%m-%dT%H:%MZ')), 1)]:
        open(out, 'w').close()
        p = subprocess.run([sys.executable, '-I', script], env=dict(base, DELIVERY_APPROVAL=appr), capture_output=True, text=True)
        o = open(out).read()
        check(p.returncode == code and 'go=no\n' in o and 'go=yes' not in o,
              'end-to-end %s: exit %d, go=no (%s)' % (label, p.returncode, p.stdout.strip().splitlines()[-1:] or p.stderr[-200:]))

print('DELIVERY_LOCK_SELFTEST %s %d checks failed' % ('FAIL' if fails else 'PASS', len(fails)))
sys.exit(1 if fails else 0)
