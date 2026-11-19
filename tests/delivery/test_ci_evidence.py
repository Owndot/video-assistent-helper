#!/usr/bin/env python3
"""Negative Regressionstests für tools/delivery/ci_evidence.py (Codex Release-Readiness 2), nur mit Attrappen.

Jede Codex-Gegenprobe (Pagination, frühere Versuche, abgebrochene Jobs mit Fehlerbelegen, laufende Läufe ohne Jobs,
fehlende API, fehlende Logs, API-Fehler, widersprüchliche Daten, nicht nachgewiesener Merge-Baum, alte Fehler neben
neuem Erfolg) muss REJECT ergeben; nur die vollständige Positivkontrolle ergibt PASS.
Aufruf: python3 -I tests/delivery/test_ci_evidence.py
"""
import copy
import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, 'tools', 'delivery'))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
sys.path.insert(0, os.path.join(ROOT, 'tests', 'gate'))
import ci_evidence as E  # noqa: E402
import device_gate as DG  # noqa: E402  (echte Fehlerklassifikation, dieselbe wie im Gate)
import make_fixtures as MF  # noqa: E402  (echte Bausteine eines Mutantenlaufs)

SRC, W2, BAD = 'a' * 40, 'b' * 40, 'c' * 40
BASE = 'd' * 40
MERGE_SHA = {SRC: 'e' * 40, W2: 'f' * 40, BAD: '9' * 40}
TREE = {SRC: '1' * 40, W2: '1' * 40, BAD: '2' * 40}
fails = []
TMP = tempfile.mkdtemp()
FAKE_GATE = os.path.join(TMP, 'fake_gate.py')
open(FAKE_GATE, 'w').write('''import re, sys
log = open(sys.argv[1]).read()
v = re.search(r'FAKE-VERDICT=(\\S+)', log).group(1)
# Runde 5: das echte Gate prüft die Schrittergebnisse gegen die Belege; die Attrappe verlangt sie und vergleicht sie mit
# FAKE-STEPS (Standard: alle drei grün bzw. beim SIZE-Befund Hauptlauf rot)
steps = sys.argv[sys.argv.index('--step-conclusions') + 1] if '--step-conclusions' in sys.argv else None
want = re.search(r'FAKE-STEPS=(\\S+)', log)
want = want.group(1) if want else ('mutant=success,main=failure,repeat=success' if v == 'ACCEPTED_SIZE_ONLY' else 'mutant=success,main=success,repeat=success')
if v != 'REJECT' and steps != want:
    v = 'REJECT'
print('DEVICE_GATE api=%s verdict=%s' % (sys.argv[3], v))
sys.exit({'PASS': 0, 'ACCEPTED_SIZE_ONLY': 3, 'INSUFFICIENT_EVIDENCE': 4}.get(v, 1))
''')


def check(cond, what):
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


def run(rid, sha, attempts=1, status='completed', conclusion='failure', event='pull_request'):
    return {'id': rid, 'head_sha': sha, 'path': E.CHANNEL, 'status': status, 'conclusion': conclusion,
            'run_attempt': attempts, 'event': event}


def plain(rid, path, sha=SRC):
    return {'id': rid, 'head_sha': sha, 'path': path, 'status': 'completed', 'conclusion': 'success', 'run_attempt': 1, 'event': 'pull_request'}


def dev_steps(api, mutant, main, repeat, build='success'):
    """Runde 5: Schritte eines Emulator-Jobs mit den drei Geräteschritten (Namen aus ci_evidence.DEVICE_STEPS)."""
    return [{'name': 'Test APKs with a throwaway signer', 'conclusion': build, 'number': 7}] + [
        {'name': E.DEVICE_STEPS[p] % api, 'conclusion': c, 'number': 10 + k} for k, (p, c) in enumerate(zip(E.DEVICE_PHASES, (mutant, main, repeat)))] + [
        {'name': 'Collect text-only report (no APK, no keys)', 'conclusion': 'success', 'number': 13}]


def job(jid, rid, attempt, api, conclusion='success', status='completed', started=True):
    """Gerätephasen passend zum Jobergebnis: success = alle grün; failure = Hauptlauf rot (z. B. SIZE auf API 29), die
    Wiederholungen laufen trotzdem; abgebrochen = Hauptlauf abgebrochen, Wiederholungen übersprungen."""
    if not started:
        steps = dev_steps(api, 'skipped', 'skipped', 'skipped', 'skipped')
    elif conclusion == 'success':
        steps = dev_steps(api, 'success', 'success', 'success')
    elif conclusion == 'failure':
        steps = dev_steps(api, 'success', 'failure', 'success')
    else:
        steps = dev_steps(api, 'success', conclusion, 'skipped')
    return {'id': jid, 'run_id': rid, 'run_attempt': attempt, 'name': 'Test package de.evp.helfer.channeltest on a disposable API %d emulator' % api,
            'status': status, 'conclusion': conclusion, 'started_at': '2026-11-17T10:00:00Z' if started else None, 'steps': steps}


CORE = 'Core, JVM channel checks, mutation probes, WASM unchanged'
MAC = 'P4 signing path on macOS (stock tools, throwaway key, nothing published)'


def other(jid, rid, attempt, name=CORE, conclusion='success'):
    return {'id': jid, 'run_id': rid, 'run_attempt': attempt, 'name': name,
            'status': 'completed', 'conclusion': conclusion, 'started_at': 'x', 'steps': [{'conclusion': conclusion}]}


def checkout(head=SRC, base=BASE, sha=None, extra=()):
    """Checkout-Zeilen wie actions/checkout (pull_request: Merge-Commit) mit Zeitstempeln."""
    sha = sha or MERGE_SHA.get(head, '8' * 40)
    L = ['[command]/usr/bin/git -c protocol.version=2 fetch --no-tags --prune --no-recurse-submodules --depth=1 origin +%s:refs/remotes/pull/27/merge' % sha,
         '[command]/usr/bin/git sparse-checkout disable',
         '[command]/usr/bin/git checkout --progress --force refs/remotes/pull/27/merge',
         "Note: switching to 'refs/remotes/pull/27/merge'.",
         'HEAD is now at %s Merge %s into %s' % (sha[:7], head, base),
         '[command]/usr/bin/git log -1 --format=%H', sha] + list(extra)
    return ''.join('2026-11-17T10:00:%02d.0000000Z %s\n' % (i, l) for i, l in enumerate(L))


def merge_log(verdict, head=SRC, base=BASE, **kw):
    return checkout(head, base, **kw) + 'FAKE-VERDICT=%s\n' % verdict


class FakeGh:
    def __init__(self, runs, jobs, logs, fail_paths=(), commits=None):
        self.runs, self.jobs, self.logs, self.fail_paths = runs, jobs, logs, fail_paths
        self.commits = commits if commits is not None else {MERGE_SHA[h]: {'tree': {'sha': TREE[h]}, 'parents': [{'sha': BASE}, {'sha': h}]}
                                                            for h in (SRC, W2, BAD)}

    def get(self, path):
        for f in self.fail_paths:
            if f in path:
                raise RuntimeError('HTTP 502')
        if path.startswith('git/commits/'):
            sha = path.split('/')[2]
            if sha not in self.commits:
                raise RuntimeError('HTTP 404 No commit found for SHA: %s' % sha)
            return self.commits[sha]
        base, _, q = path.partition('?')
        params = dict(p.split('=', 1) for p in q.split('&') if p)
        page, per = int(params.get('page', 1)), int(params.get('per_page', 30))
        if base == 'actions/runs':
            items, key = [r for r in self.runs if r.get('listed_for', r['head_sha']) == params['head_sha']], 'workflow_runs'
        else:
            parts = base.split('/')          # actions/runs/<id>/attempts/<n>/jobs
            items, key = self.jobs.get((int(parts[2]), int(parts[4])), []), 'jobs'
        total = getattr(self, 'total_override', {}).get(base, len(items))
        return {'total_count': total, key: items[(page - 1) * per:page * per]}

    def log(self, jid):
        if jid not in self.logs:
            raise RuntimeError('404')
        return self.logs[jid]


class FakeGit:
    def __init__(self, same=(SRC,), ancestors=((BASE, SRC), (BASE, W2))):
        self.same, self.anc = list(same), set(ancestors)

    def same_code_commits(self, source, workflow_sha):
        return self.same

    def is_ancestor(self, a, b):
        return (a, b) in self.anc

    def tree(self, sha):
        return getattr(self, 'trees', TREE)[sha]


def world():
    runs = [plain(1, '.github/workflows/build.yml'), plain(2, '.github/workflows/browser-tests.yml'),
            plain(3, '.github/workflows/hls-download.yml'), run(10, SRC)]
    jobs = {(10, 1): [other(100, 10, 1), job(126, 10, 1, 26), job(129, 10, 1, 29, 'failure'), job(134, 10, 1, 34), other(101, 10, 1, MAC)]}
    logs = {126: merge_log('PASS'), 129: merge_log('ACCEPTED_SIZE_ONLY'), 134: merge_log('PASS')}
    return runs, jobs, logs


import inspect  # noqa: E402
NEW_API = 'classifier' in inspect.signature(E.evaluate).parameters


def evaluate(gh, git, out):
    kw = {'classifier': (DG.failure_evidence, DG.BLOCKING)} if NEW_API else {}
    return E.evaluate(gh, git, SRC, W2, FAKE_GATE, TMP, out=out, **kw)


def verdict(runs, jobs, logs, git=None, fail_paths=(), total_override=None, commits=None, out=None):
    gh = FakeGh(runs, jobs, logs, fail_paths, commits)
    if total_override:
        gh.total_override = total_override
    out = [] if out is None else out
    ok = evaluate(gh, git or FakeGit(), out.append)
    return ok, [l for l in out if 'REJECT ' in l]


ok, why = verdict(*world())
check(ok, 'positive control: complete PASS evidence for 26/29/34 on proven merge trees -> PASS')

CASES = []


def case(name, mutate, git=None, fail_paths=(), total_override=None, commits=None):
    runs, jobs, logs = copy.deepcopy(world())
    mutate(runs, jobs, logs)
    CASES.append((name, runs, jobs, logs, git, fail_paths, total_override, commits))


def many_runs(runs, jobs, logs):  # rote Lauf-Evidenz erst auf Seite 2 der Läufe
    for i in range(120):
        runs.append(plain(1000 + i, '.github/workflows/other-%d.yml' % i))
    runs.append(run(11, SRC))
    jobs[(11, 1)] = [job(226, 11, 1, 26), job(229, 11, 1, 29, 'failure'), job(234, 11, 1, 34, 'failure')]
    logs.update({226: merge_log('PASS'), 229: merge_log('ACCEPTED_SIZE_ONLY'), 234: merge_log('REJECT')})


case('failing run only on page 2 of the run list (pagination)', many_runs)
case('failing emulator job only on page 2 of the job list', lambda r, j, l: (
    j[(10, 1)].extend([dict(other(5000 + i, 10, 1), name=MAC if i % 2 else CORE) for i in range(110)] + [job(334, 10, 1, 34, 'failure')]),
    l.update({334: merge_log('REJECT')})))
case('total_count larger than the jobs received (missing page)', lambda r, j, l: None,
     total_override={'actions/runs/10/attempts/1/jobs': 9})
case('earlier attempt failed, later re-run passed', lambda r, j, l: (
    r[3].update(run_attempt=2), j.update({(10, 2): j[(10, 1)]}),
    j.update({(10, 1): [job(926, 10, 1, 26), job(929, 10, 1, 29, 'failure'), job(934, 10, 1, 34, 'failure')]}),
    l.update({926: merge_log('PASS'), 929: merge_log('ACCEPTED_SIZE_ONLY'), 934: merge_log('REJECT')})))
case('cancelled job with publish rows=0 already in its log', lambda r, j, l: (
    r.append(run(12, SRC)), j.update({(12, 1): [job(426, 12, 1, 26, 'cancelled'), job(429, 12, 1, 29, 'cancelled'), job(434, 12, 1, 34, 'cancelled')]}),
    l.update({426: 'x', 429: 'x', 434: 'org.junit.ComparisonFailure: result (cause: IOException: publish rows=0 proof=present)'})))
case('cancelled job with an app crash already in its log', lambda r, j, l: (
    r.append(run(13, SRC)), j.update({(13, 1): [job(526, 13, 1, 26, 'cancelled'), job(529, 13, 1, 29, 'cancelled'), job(534, 13, 1, 34, 'cancelled')]}),
    l.update({526: 'E AndroidRuntime: FATAL EXCEPTION: main', 529: 'x', 534: 'x'})))
case('cancelled job that started but whose log is missing', lambda r, j, l: (
    r.append(run(14, SRC)), j.update({(14, 1): [job(626, 14, 1, 26, 'cancelled'), job(629, 14, 1, 29), job(634, 14, 1, 34)]}),
    l.update({629: merge_log('PASS'), 634: merge_log('PASS')})))
case('only cancelled evidence for API 34 (no coverage)', lambda r, j, l: (
    j[(10, 1)].__setitem__(3, job(134, 10, 1, 34, 'cancelled')), l.update({134: 'nothing'})))
case('running run without any listed jobs', lambda r, j, l: r.append(run(15, SRC, status='in_progress', conclusion=None)))
case('completed attempt without any jobs', lambda r, j, l: (r.append(run(16, SRC)), j.update({(16, 1): []})))
case('job still in progress', lambda r, j, l: (r.append(run(17, SRC)), j.update({(17, 1): [job(726, 17, 1, 26, None, 'in_progress'),
                                                                                        job(729, 17, 1, 29), job(734, 17, 1, 34)]}),
                                                 l.update({729: merge_log('PASS'), 734: merge_log('PASS')})))
case('API 29 job missing in the attempt', lambda r, j, l: j[(10, 1)].pop(2))
case('unexpected emulator API', lambda r, j, l: (j[(10, 1)].append(job(130, 10, 1, 30)), l.update({130: merge_log('PASS')})))
case('unexpected job name', lambda r, j, l: j[(10, 1)].append(dict(other(101, 10, 1), name='Mystery job')))
case('log of a completed emulator job unavailable', lambda r, j, l: l.pop(134))
case('GitHub API error while listing jobs', lambda r, j, l: None, fail_paths=('attempts/1/jobs',))
case('GitHub API error while listing runs', lambda r, j, l: None, fail_paths=('actions/runs?head_sha',))
case('job data contradicts its run', lambda r, j, l: j[(10, 1)][1].update(run_id=99))
case('run reports success although a job failed', lambda r, j, l: (r[3].update(conclusion='success'), j[(10, 1)][2].update(conclusion='failure')))
case('run head differs from the asked commit', lambda r, j, l: r.append(dict(run(18, SRC), head_sha=BAD, listed_for=SRC)))
case('merge checkout of another head (tested code not proven)', lambda r, j, l: l.update({134: merge_log('PASS', head=BAD)}))
case('merge base is not an ancestor (merge tree differs)', lambda r, j, l: None,
     git=type('G', (FakeGit,), {'trees': {SRC: '7' * 40, W2: '7' * 40, BAD: '2' * 40}})())
case('merge checkout line missing in a pull_request run', lambda r, j, l: l.update({126: 'FAKE-VERDICT=PASS\n'}))
case('only insufficient evidence (old repeat format)', lambda r, j, l: l.update({126: merge_log('INSUFFICIENT_EVIDENCE')}))
case('SIZE exception reported on API 34', lambda r, j, l: (j[(10, 1)][3].update(conclusion='failure'), l.update({134: merge_log('ACCEPTED_SIZE_ONLY')})))
case('gate exit code contradicts its verdict', lambda r, j, l: l.update({126: merge_log('MYSTERY')}))
case('old REJECT on a same-code commit next to a new PASS', lambda r, j, l: (
    r.append(run(19, W2)), j.update({(19, 1): [job(826, 19, 1, 26), job(829, 19, 1, 29, 'failure'), job(834, 19, 1, 34, 'failure')]}),
    l.update({826: merge_log('PASS', W2), 829: merge_log('ACCEPTED_SIZE_ONLY', W2), 834: merge_log('REJECT', W2)})),
    git=FakeGit(same=(SRC, W2)))
case('plain workflow on the source not successful', lambda r, j, l: r[1].update(conclusion='failure'))

# ---- Codex NO-GO 3 (P1): strukturierte Klassifikation abgebrochener Jobs, Pflichtjobs -----------------------------------
INFRA_429 = ('##[group]Run ./gradlew -q :app:dependencies\n##[endgroup]\nFAILURE: Build completed with 2 failures.\n'
             "> Could not GET 'https://repo.maven.apache.org/maven2/x.pom'. Received status code 429 from server: Too Many Requests\n"
             '##[error]Process completed with exit code 1.')


def cancelled_run(rid, log34, log26='x', log29='x'):
    def f(r, j, l):
        r.append(run(rid, SRC))
        j[(rid, 1)] = [other(rid * 100, rid, 1), other(rid * 100 + 1, rid, 1, MAC), job(rid * 10 + 6, rid, 1, 26, 'cancelled'),
                       job(rid * 10 + 9, rid, 1, 29, 'cancelled'), job(rid * 10 + 4, rid, 1, 34, 'cancelled')]
        l.update({rid * 10 + 6: log26, rid * 10 + 9: log29, rid * 10 + 4: log34})
    return f


case('NO-GO3: cancelled job with java.io.IOException: publish', cancelled_run(30,
     'Caused by: java.io.IOException: publish threw=IllegalStateException(No item at content://media/external/downloads/38) proof=present'))
case('NO-GO3: cancelled job with MP4_STORE_FAILURE ... publish threw=IllegalStateException', cancelled_run(31,
     '11-17 13:37:20.581  4597  5046 I MP4_TEST: MP4_STORE_FAILURE sdk=34 commit java.io.IOException: publish threw=IllegalStateException(No item at content://media/external/downloads/41)'))
case('NO-GO3: cancelled job with failed:storage', cancelled_run(32,
     'org.junit.ComparisonFailure: download result expected:<done> but was:<failed:storage>'))
MUTANT_ONLY = '\n'.join(['DEVICE_READY after 9 s'] + MF.instr({MF.MP4: [DG.MUTANT_TEST]}, {DG.MUTANT_TEST: MF.MUTANT_STACK}) + [DG.MUTANT_PASS,
                        '##[error]The operation was canceled.']) + '\n'
MUTANT_ROWS0 = MUTANT_ONLY.replace(MF.MUTANT_CAUSE, MF.ROWS0_CAUSE)
case('NO-GO3: cancelled after a mutant run whose cause is publish rows=0', cancelled_run(33, MUTANT_ROWS0))
case('NO-GO3: required Core/JVM job failed with a test failure, emulators green', lambda r, j, l: (
    j[(10, 1)].__setitem__(0, other(100, 10, 1, CORE, 'failure')), l.update({100: 'test result: FAILED. 41 passed; 1 failed'})))
case('NO-GO3: required job failed by infrastructure only in the only covering run (no evidence left)', lambda r, j, l: (
    j[(10, 1)].__setitem__(0, other(100, 10, 1, CORE, 'failure')),
    l.update({100: INFRA_429})))
case('NO-GO3: required job failed for an unknown reason', lambda r, j, l: (
    j[(10, 1)].__setitem__(4, other(101, 10, 1, MAC, 'failure')), l.update({101: '##[group]Run bash tools/p4test/selftest.sh\nsomething odd\n##[endgroup]\n##[error]Process completed with exit code 1.'})))
case('NO-GO3: required job failed in a check step that also shows a 429', lambda r, j, l: (
    j[(10, 1)].__setitem__(0, other(100, 10, 1, CORE, 'failure')),
    l.update({100: INFRA_429.replace('##[error]', 'Tests run: 3,  Failures: 1\n##[error]')})))
case('NO-GO3: required job missing in the attempt', lambda r, j, l: j[(10, 1)].pop(4))
case('NO-GO3: required job failed, log unavailable', lambda r, j, l: j[(10, 1)].__setitem__(0, other(100, 10, 1, CORE, 'failure')))
case('NO-GO3: emulator job success although the device step was skipped', lambda r, j, l: j[(10, 1)][1]['steps'][1].update(conclusion='skipped'))
case('NO-GO3: complete log with failure evidence that the gate did not reject', lambda r, j, l: l.update({134: merge_log('PASS') + 'java.io.IOException: publish rows=0\n'}))
# ---- Codex NO-GO 3 (P2): tatsächlich getesteter Code (Checkout-SHA + Dateibaum) ------------------------------------------
case('NO-GO3: second checkout of other application code in the job', lambda r, j, l: l.update({134: merge_log('PASS', extra=[
    '[command]/usr/bin/git checkout --progress --force refs/remotes/pull/26/merge', 'HEAD is now at 9999999 other'])}))
case('NO-GO3: run step checks out other application files', lambda r, j, l: l.update({134: merge_log('PASS', extra=[
    '\x1b[36;1mgit checkout 1234567 -- app/src/main\x1b[0m'])}))
case('NO-GO3: merge commit tree differs from the run commit tree', lambda r, j, l: None,
     commits={MERGE_SHA[SRC]: {'tree': {'sha': '6' * 40}, 'parents': [{'sha': BASE}, {'sha': SRC}]}})
case('NO-GO3: merge commit not retrievable (tree not provable)', lambda r, j, l: None, commits={})
case('NO-GO3: merge commit without the run commit as parent', lambda r, j, l: None,
     commits={MERGE_SHA[SRC]: {'tree': {'sha': TREE[SRC]}, 'parents': [{'sha': BASE}, {'sha': BAD}]}})
case('NO-GO3: printed checkout SHA differs from the fetched SHA', lambda r, j, l: l.update({134: merge_log('PASS').replace(
    'Z ' + MERGE_SHA[SRC] + '\n', 'Z ' + '5' * 40 + '\n')}))
case('NO-GO3: push run whose checkout is another commit', lambda r, j, l: (r[3].update(event='push'), l.update({
    134: merge_log('PASS', sha=BAD).replace('Merge %s into %s' % (SRC, BASE), 'docs: something')})))

case('device step missing in an emulator job', lambda r, j, l: j[(10, 1)][1].update(steps=[{'name': 'x', 'conclusion': 'success'}]))
case('build failed before the device step, no other evidence for API 26', lambda r, j, l: j[(10, 1)][1].update(
    conclusion='failure', steps=dev_steps(26, 'skipped', 'skipped', 'skipped', 'failure')))
case('device step ran and failed (log says REJECT) although an earlier step failed too', lambda r, j, l: (j[(10, 1)][3].update(
    conclusion='failure', steps=dev_steps(34, 'failure', None, None, 'failure')),
    l.update({134: merge_log('REJECT')})))

# ---- Runde 5 (Testisolation): genau drei benannte Geräteschritte, Bedingungslogik, Schrittergebnisse --------------------


def steps_of(jidx, steps):
    return lambda r, j, l: j[(10, 1)][jidx].update(steps=steps)


def without(api, phase):
    return [x for x in dev_steps(api, 'success', 'success', 'success') if x['name'] != E.DEVICE_STEPS[phase] % api]


case('R5: device phase repeat missing (API 34 job)', steps_of(3, without(34, 'repeat')))
case('R5: device phase mutant missing (API 26 job)', steps_of(1, without(26, 'mutant')))
case('R5: device phase main duplicated', steps_of(3, dev_steps(34, 'success', 'success', 'success')[:3] + dev_steps(34, 'success', 'success', 'success')[2:]))
case('R5: device phases in swapped order (by step number)', steps_of(3, [dict(x, number={11: 12, 12: 11}.get(x['number'], x['number']))
                                                                          for x in dev_steps(34, 'success', 'success', 'success')]))
case('R5: device step renamed (other API in the name)', steps_of(1, [dict(x, name=x['name'].replace('API 26', 'API 34')) for x in dev_steps(26, 'success', 'success', 'success')]))
case('R5: legacy single device step (all phases on one emulator)', steps_of(3, [{'name': 'Test APKs with a throwaway signer', 'conclusion': 'success'},
                                                                                 {'name': 'Instrumentation on a disposable API 34 x86_64 emulator', 'conclusion': 'success'}]))
case('R5: legacy single step next to the three phases', steps_of(3, dev_steps(34, 'success', 'success', 'success') + [
    {'name': 'Instrumentation on a disposable API 34 x86_64 emulator', 'conclusion': 'success', 'number': 14}]))
case('R5: job success although the repeat phase was skipped', steps_of(3, dev_steps(34, 'success', 'success', 'skipped')))
case('R5: job success although the main phase failed', steps_of(3, dev_steps(34, 'success', 'failure', 'success')))
case('R5: job success although the mutant phase failed (main and repeats green)', steps_of(1, dev_steps(26, 'failure', 'success', 'success')))
case('R5: main failed, repeats green, job failure (gate rejects the main failure)', lambda r, j, l: (
    j[(10, 1)][3].update(conclusion='failure', steps=dev_steps(34, 'success', 'failure', 'success')), l.update({134: merge_log('REJECT')})))
case('R5: main green, repeats failed, job failure (gate rejects the repeat failure)', lambda r, j, l: (
    j[(10, 1)][3].update(conclusion='failure', steps=dev_steps(34, 'success', 'success', 'failure')), l.update({134: merge_log('REJECT')})))
case('R5: main ran although the mutant phase failed', lambda r, j, l: j[(10, 1)][3].update(conclusion='failure', steps=dev_steps(34, 'failure', 'success', 'success')))
case('R5: repeats ran although the main phase was skipped', lambda r, j, l: j[(10, 1)][3].update(conclusion='failure', steps=dev_steps(34, 'success', 'skipped', 'success')))
case('R5: main skipped after a successful mutant phase (not cancelled)', lambda r, j, l: j[(10, 1)][3].update(conclusion='failure', steps=dev_steps(34, 'success', 'skipped', 'skipped')))
case('R5: repeats skipped after a main run (not cancelled)', lambda r, j, l: j[(10, 1)][3].update(conclusion='failure', steps=dev_steps(34, 'success', 'failure', 'skipped')))
case('R5: device step failed but job conclusion success', lambda r, j, l: j[(10, 1)][2].update(steps=dev_steps(29, 'success', 'failure', 'success'), conclusion='success'))
case('R5: mutant failed, later phases skipped, job failure (gate rejects)', lambda r, j, l: (
    j[(10, 1)][3].update(conclusion='failure', steps=dev_steps(34, 'failure', 'skipped', 'skipped')), l.update({134: merge_log('REJECT')})))
case('R5: step results differ from what the gate saw in the log', lambda r, j, l: l.update({126: merge_log('PASS') + 'FAKE-STEPS=mutant=success,main=failure,repeat=success\n'}))

for name, runs, jobs, logs, git, fp, to, cm in CASES:
    ok, why = verdict(runs, jobs, logs, git, fp, to, cm)
    check(not ok and bool(why), 'REJECT: %s (%s)' % (name, why[0][len('CI_EVIDENCE REJECT '):][:90] if why else 'NO REASON'))

# Ein Commit mit anderem Anwendungscode zählt nicht als Beleg (FakeGit liefert nur SRC): sein roter Lauf wird nicht
# einbezogen, seine grünen Läufe aber auch nie als Abdeckung.
runs, jobs, logs = world()
runs.append(run(20, BAD))
jobs[(20, 1)] = [job(1026, 20, 1, 26, 'failure')]
logs[1026] = merge_log('REJECT', BAD)
ok, _ = verdict(runs, jobs, logs)
check(ok, 'runs of a commit with different application code are not used as evidence')

# Ein Bau-Fehlschlag VOR dem Geräteschritt ist kein Beleg über die App: ausgewiesen, aber weder PASS noch REJECT;
# die Abdeckung muss aus anderen Läufen kommen.
runs, jobs, logs = world()
runs.append(run(21, SRC))
jobs[(21, 1)] = [other(2100, 21, 1), other(2101, 21, 1, MAC), dict(job(1126, 21, 1, 26, 'failure'), steps=dev_steps(26, 'skipped', 'skipped', 'skipped', 'failure')),
                 job(1129, 21, 1, 29, 'failure'), job(1134, 21, 1, 34)]
logs.update({1129: merge_log('ACCEPTED_SIZE_ONLY'), 1134: merge_log('PASS')})
out = []
ok = evaluate(FakeGh(runs, jobs, logs), FakeGit(), out.append)
check(ok and any('before the device step ran' in l for l in out), 'build failure before the device step is listed as no device evidence (not hidden, not PASS)')

# Codex NO-GO 3: kein neuer Produktfehler, aber auch kein Positivbeleg (zusätzlicher Lauf neben voller Abdeckung)
NEUTRAL = []


def neutral(name, mutate, expect):
    runs, jobs, logs = copy.deepcopy(world())
    mutate(runs, jobs, logs)
    out = []
    ok, why = verdict(runs, jobs, logs, out=out)
    NEUTRAL.append(name)
    check(ok and any(expect in l for l in out if l.startswith('NO EVIDENCE')),
          'NOT A PRODUCT FAILURE, NO EVIDENCE: %s (%s)' % (name, why[0][len('CI_EVIDENCE REJECT '):][:90] if why else expect))


neutral('NO-GO3: only the expected mutant run, then an infrastructure cancellation', cancelled_run(40, MUTANT_ONLY), 'expected mutant run only')
neutral('NO-GO3: mutant report complete, cancelled before its PASS line', cancelled_run(41, MUTANT_ONLY.replace(DG.MUTANT_PASS + '\n', '')),
        'expected mutant run only')
neutral('NO-GO3: required job failed by Maven 429 in an additional run', lambda r, j, l: (
    r.append(run(42, SRC)), j.update({(42, 1): [other(4200, 42, 1, CORE, 'failure'), other(4201, 42, 1, MAC), job(426, 42, 1, 26),
                                               job(429, 42, 1, 29, 'failure'), job(424, 42, 1, 34)]}),
    l.update({4200: INFRA_429,
              426: merge_log('PASS'), 429: merge_log('ACCEPTED_SIZE_ONLY'), 424: merge_log('PASS')})), 'device evidence not counted')
neutral('NO-GO3: runner lost during the device step without failure evidence', lambda r, j, l: (
    r.append(run(43, SRC)), j.update({(43, 1): [other(4300, 43, 1), other(4301, 43, 1, MAC), job(436, 43, 1, 26),
                                               job(439, 43, 1, 29, 'failure'),
                                               dict(job(434, 43, 1, 34, 'failure'), steps=dev_steps(34, 'success', 'cancelled', 'skipped'))]}),
    l.update({436: merge_log('PASS'), 439: merge_log('ACCEPTED_SIZE_ONLY'), 434: merge_log('PASS') + 'DEVICE_READY after 9 s\n'})), 'without failure evidence')
neutral('NO-GO3: required job cancelled (no failed step) although its self-tests print SURVIVED/FAIL words', lambda r, j, l: (
    r.append(run(44, SRC)), j.update({(44, 1): [other(4400, 44, 1, CORE, 'cancelled'), other(4401, 44, 1, MAC), job(446, 44, 1, 26),
                                               job(449, 44, 1, 29, 'failure'), job(444, 44, 1, 34)]}),
    l.update({4400: 'PASS green suite is SURVIVED: SURVIVED\nRESULT selftest FAIL 1/2 killed (KILLED=1 INVALID=1)\n##[error]The operation was canceled.',
              446: merge_log('PASS'), 449: merge_log('ACCEPTED_SIZE_ONLY'), 444: merge_log('PASS')})), 'no check result')
runs, jobs, logs = world()
runs[3].update(event='push')
logs.update({k: merge_log(v, sha=SRC).replace('Merge %s into %s' % (SRC, BASE), 'test: x') for k, v in ((126, 'PASS'), (129, 'ACCEPTED_SIZE_ONLY'), (134, 'PASS'))})
ok, why = verdict(runs, jobs, logs)
check(ok, 'push run whose single checkout is exactly the run commit is positive evidence %s' % (why[:1] or ''))

# Runde 5: Übergabe der Schrittergebnisse an das ECHTE Geräte-Gate (Format --step-conclusions) an echten Fixture-Logs
REAL_GATE = os.path.join(ROOT, 'tools', 'device_gate.py')
FXD = os.path.join(ROOT, 'tests', 'gate', 'fixtures')
for fx, api, concl, dc, want in [
        ('pass_api34', 34, 'success', {'mutant': 'success', 'main': 'success', 'repeat': 'success'}, 'PASS'),
        ('size_only_api29', 29, 'failure', {'mutant': 'success', 'main': 'failure', 'repeat': 'success'}, 'ACCEPTED_SIZE_ONLY'),
        ('size_only_api29', 29, 'failure', {'mutant': 'success', 'main': 'success', 'repeat': 'failure'}, 'REJECT'),
        ('pass_api34', 34, 'success', {'mutant': 'success', 'main': 'success', 'repeat': None}, 'REJECT'),
        ('pass_api34', 34, 'success', {'mutant': 'success', 'main': 'success', 'repeat': 'skipped'}, 'REJECT'),
        ('main_failed_repeats_green_api34', 34, 'failure', {'mutant': 'success', 'main': 'failure', 'repeat': 'success'}, 'REJECT')]:
    got, detail = E.gate_verdict(REAL_GATE, open(os.path.join(FXD, fx + '.log'), encoding='utf-8').read(), api, concl, TMP, 7, dc)
    check(got == want, 'real gate with step conclusions: %s %s -> %s (got %s %s)' % (fx, dc, want, got, detail[:80]))

# ---- Runde 6: echter Checkout-Log (git-Hinweis zum losgelösten HEAD) und gezielte Manipulationen ------------------------
REAL_CO = open(os.path.join(ROOT, 'tests', 'delivery', 'fixtures', 'checkout-real-api34-b01db6e.log'), encoding='utf-8').read()
REAL_MERGE, REAL_HEAD, REAL_BASE = '0b18305ed3a29641d82277eb5b487b822b22d2ee', 'b01db6e7bbd80f50a8e0ed8525333719ee6f2c1e', '61612ef3bf9cc062992a0ca3ae6eb8ebc1ab70cb'
REAL_TREE = '2932dfa952beddf83668cd46410a0720bd3fb4e3'


def ts(text):
    return '2026-11-19T08:19:03.9999999Z ' + text


def after(marker, *new):
    """Fügt Zeilen direkt nach der ersten Zeile ein, die marker enthält."""
    L = REAL_CO.split('\n')
    i = next(k for k, l in enumerate(L) if marker in l)
    return '\n'.join(L[:i + 1] + [ts(x) for x in new] + L[i + 1:])


def replace_line(old, new):
    L = REAL_CO.split('\n')
    i = next(k for k, l in enumerate(L) if l.endswith(' ' + old) or l.endswith('Z ' + old))
    return '\n'.join(L[:i] + [ts(new)] + L[i + 1:])


sha, why = E.checkout_of(REAL_CO)
check(sha == REAL_MERGE, 'R6 positive: real actions/checkout log with the detached-HEAD advice -> checkout %s (%s)' % ((sha or '')[:7], why[:60]))
real_gh = FakeGh([], {}, {}, commits={REAL_MERGE: {'tree': {'sha': REAL_TREE}, 'parents': [{'sha': REAL_BASE}, {'sha': REAL_HEAD}]}})
real_git = type('G', (FakeGit,), {'trees': {REAL_HEAD: REAL_TREE}})()
ok, why = E.tested_code_matches(real_gh, real_git, REAL_CO, REAL_HEAD, 'pull_request')
check(ok, 'R6 positive: real checkout + merge tree equal to the run commit -> tested code proven (%s)' % why[:70])
ok, why = E.tested_code_matches(real_gh, type('G', (FakeGit,), {'trees': {REAL_HEAD: '7' * 40}})(), REAL_CO, REAL_HEAD, 'pull_request')
check(not ok, 'R6 negative: real checkout but different tree of the run commit -> not proven (%s)' % why[:60])
R6_NEG = [
    ('tree-changing git command injected inside the advice block', after('  git switch -c <new-branch-name>', '  git checkout 1234567 -- app/src/main')),
    ('hint line altered into a real command', replace_line('  git switch -', '  git switch - && git reset --hard HEAD~1')),
    ('hint line replaced by another switch target', replace_line('  git switch -c <new-branch-name>', '  git switch -c evil origin/other')),
    ('extra git reset line appended to the advice', after('Turn off this advice by setting config variable advice.detachedHead to false', '  git reset --hard 1234567')),
    ('advice line removed (block no longer exact)', REAL_CO.replace(' Or undo this operation with:\n', ' \n', 1)),
    ('Note line missing (block not anchored at the checkout)', '\n'.join(l for l in REAL_CO.split('\n') if 'Note: switching to' not in l)),
    ('hint text copied outside the advice block', REAL_CO + '\n' + ts('  git switch -c <new-branch-name>')),
    ('run step executes git switch (ANSI script line)', REAL_CO + '\n' + ts('\x1b[36;1mgit switch -c x\x1b[0m')),
    ('run step executes git checkout of other files', REAL_CO + '\n' + ts('\x1b[36;1mgit checkout 1234567 -- app/src/main\x1b[0m')),
    ('executed git command logged as [command]', REAL_CO + '\n' + ts('[command]/usr/bin/git switch -')),
    ('executed git restore after the checkout', REAL_CO + '\n' + ts('git restore --source=1234567 app')),
    ('second checkout command with its own advice', REAL_CO + '\n' + ts('[command]/usr/bin/git checkout --progress --force refs/remotes/pull/26/merge')),
]
for name, log in R6_NEG:
    sha, why = E.checkout_of(log)
    check(log != REAL_CO and sha is None, 'R6 negative: %s -> rejected (%s)' % (name, why[:70]))

# Runde 5: die Schrittnamen in helper-channel.yml sind genau die erwarteten (Matrix-Ausdruck statt API-Nummer)
import re  # noqa: E402
wf = open(os.path.join(ROOT, '.github', 'workflows', 'helper-channel.yml'), encoding='utf-8').read()
names = re.findall(r'^      - name: (Device phase .*|Instrumentation on a disposable API.*)$', wf, re.M)
check(names == [E.DEVICE_STEPS[p].replace('%d', '${{ matrix.api }}') for p in E.DEVICE_PHASES],
      'helper-channel.yml device step names are exactly ci_evidence.DEVICE_STEPS in order %s' % names)

print('CI_EVIDENCE_SELFTEST %s %d cases, %d failed' % ('FAIL' if fails else 'PASS', len(CASES) + 11 + 3 + len(R6_NEG) + len(NEUTRAL), len(fails)))
sys.exit(1 if fails else 0)
