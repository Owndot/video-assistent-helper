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
import ci_evidence as E  # noqa: E402

SRC, W2, BAD = 'a' * 40, 'b' * 40, 'c' * 40
BASE = 'd' * 40
fails = []
TMP = tempfile.mkdtemp()
FAKE_GATE = os.path.join(TMP, 'fake_gate.py')
open(FAKE_GATE, 'w').write('''import re, sys
log = open(sys.argv[1]).read()
v = re.search(r'FAKE-VERDICT=(\\S+)', log).group(1)
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


def job(jid, rid, attempt, api, conclusion='success', status='completed', started=True):
    return {'id': jid, 'run_id': rid, 'run_attempt': attempt, 'name': 'Test package de.evp.helfer.channeltest on a disposable API %d emulator' % api,
            'status': status, 'conclusion': conclusion, 'started_at': '2026-11-17T10:00:00Z' if started else None,
            'steps': [{'conclusion': 'success' if started else 'skipped'}]}


def other(jid, rid, attempt):
    return {'id': jid, 'run_id': rid, 'run_attempt': attempt, 'name': 'Core, JVM channel checks, mutation probes, WASM unchanged',
            'status': 'completed', 'conclusion': 'success', 'started_at': 'x', 'steps': [{'conclusion': 'success'}]}


def merge_log(verdict, head=SRC, base=BASE):
    return 'HEAD is now at 1234567 Merge %s into %s\nFAKE-VERDICT=%s\n' % (head, base, verdict)


class FakeGh:
    def __init__(self, runs, jobs, logs, fail_paths=()):
        self.runs, self.jobs, self.logs, self.fail_paths = runs, jobs, logs, fail_paths

    def get(self, path):
        for f in self.fail_paths:
            if f in path:
                raise RuntimeError('HTTP 502')
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


def world():
    runs = [plain(1, '.github/workflows/build.yml'), plain(2, '.github/workflows/browser-tests.yml'),
            plain(3, '.github/workflows/hls-download.yml'), run(10, SRC)]
    jobs = {(10, 1): [other(100, 10, 1), job(126, 10, 1, 26), job(129, 10, 1, 29, 'failure'), job(134, 10, 1, 34)]}
    logs = {126: merge_log('PASS'), 129: merge_log('ACCEPTED_SIZE_ONLY'), 134: merge_log('PASS')}
    return runs, jobs, logs


def verdict(runs, jobs, logs, git=None, fail_paths=(), total_override=None):
    gh = FakeGh(runs, jobs, logs, fail_paths)
    if total_override:
        gh.total_override = total_override
    out = []
    ok = E.evaluate(gh, git or FakeGit(), SRC, W2, FAKE_GATE, TMP, out=out.append)
    return ok, [l for l in out if 'REJECT ' in l]


ok, why = verdict(*world())
check(ok, 'positive control: complete PASS evidence for 26/29/34 on proven merge trees -> PASS')

CASES = []


def case(name, mutate, git=None, fail_paths=(), total_override=None):
    runs, jobs, logs = copy.deepcopy(world())
    mutate(runs, jobs, logs)
    CASES.append((name, runs, jobs, logs, git, fail_paths, total_override))


def many_runs(runs, jobs, logs):  # rote Lauf-Evidenz erst auf Seite 2 der Läufe
    for i in range(120):
        runs.append(plain(1000 + i, '.github/workflows/other-%d.yml' % i))
    runs.append(run(11, SRC))
    jobs[(11, 1)] = [job(226, 11, 1, 26), job(229, 11, 1, 29, 'failure'), job(234, 11, 1, 34, 'failure')]
    logs.update({226: merge_log('PASS'), 229: merge_log('ACCEPTED_SIZE_ONLY'), 234: merge_log('REJECT')})


case('failing run only on page 2 of the run list (pagination)', many_runs)
case('failing emulator job only on page 2 of the job list', lambda r, j, l: (
    j[(10, 1)].extend([other(5000 + i, 10, 1) for i in range(110)] + [job(334, 10, 1, 34, 'failure')]),
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
case('merge base is not an ancestor (merge tree differs)', lambda r, j, l: None, git=FakeGit(ancestors=()))
case('merge checkout line missing in a pull_request run', lambda r, j, l: l.update({126: 'FAKE-VERDICT=PASS\n'}))
case('only insufficient evidence (old repeat format)', lambda r, j, l: l.update({126: merge_log('INSUFFICIENT_EVIDENCE')}))
case('SIZE exception reported on API 34', lambda r, j, l: (j[(10, 1)][3].update(conclusion='failure'), l.update({134: merge_log('ACCEPTED_SIZE_ONLY')})))
case('gate exit code contradicts its verdict', lambda r, j, l: l.update({126: merge_log('MYSTERY')}))
case('old REJECT on a same-code commit next to a new PASS', lambda r, j, l: (
    r.append(run(19, W2)), j.update({(19, 1): [job(826, 19, 1, 26), job(829, 19, 1, 29, 'failure'), job(834, 19, 1, 34, 'failure')]}),
    l.update({826: merge_log('PASS', W2), 829: merge_log('ACCEPTED_SIZE_ONLY', W2), 834: merge_log('REJECT', W2)})),
    git=FakeGit(same=(SRC, W2)))
case('plain workflow on the source not successful', lambda r, j, l: r[1].update(conclusion='failure'))

for name, runs, jobs, logs, git, fp, to in CASES:
    ok, why = verdict(runs, jobs, logs, git, fp, to)
    check(not ok and bool(why), 'REJECT: %s (%s)' % (name, why[0][len('CI_EVIDENCE REJECT '):][:90] if why else 'NO REASON'))

# Ein Commit mit anderem Anwendungscode zählt nicht als Beleg (FakeGit liefert nur SRC): sein roter Lauf wird nicht
# einbezogen, seine grünen Läufe aber auch nie als Abdeckung.
runs, jobs, logs = world()
runs.append(run(20, BAD))
jobs[(20, 1)] = [job(1026, 20, 1, 26, 'failure')]
logs[1026] = merge_log('REJECT', BAD)
ok, _ = verdict(runs, jobs, logs)
check(ok, 'runs of a commit with different application code are not used as evidence')

print('CI_EVIDENCE_SELFTEST %s %d cases, %d failed' % ('FAIL' if fails else 'PASS', len(CASES) + 2, len(fails)))
sys.exit(1 if fails else 0)
