#!/usr/bin/env python3
"""CI-Belege für eine P4-Testlieferung des Helpers (Codex Release-Readiness P2). Läuft im Paketjob NACH der
Freigabesperre, im Checkout des Workflow-Commits; liest nur (GitHub-API, git).

Aufruf:
  ci_evidence.py --repo OWNER/NAME --source SHA --workflow-sha SHA --gate tools/device_gate.py [--log-dir DIR]

Regeln (alle müssen gelten, sonst Exit 1):
  1. Auf SOURCE_SHA sind build.yml, browser-tests.yml und hls-download.yml im neuesten Lauf erfolgreich.
  2. Auf SOURCE_SHA gibt es einen abgeschlossenen helper-channel-Lauf, dessen neuester Versuch genau die fünf
     erwarteten Jobs hat; die beiden Nicht-Emulator-Jobs sind erfolgreich, API 26/29/34 sind vorhanden.
  3. „Gleicher Anwendungscode“ = SOURCE_SHA und jeder Commit bis WORKFLOW_SHA, dessen Abweichung von SOURCE_SHA nur
     Liefer-/Prüfwerkzeuge betrifft (ALLOWED). Für ALLE helper-channel-Läufe dieser Commits und ALLE ihre Versuche
     muss jeder abgeschlossene Emulator-Job (success/failure) das Geräte-Gate bestehen: API 26/34 PASS, API 29 PASS
     oder ACCEPTED_SIZE_ONLY. Ein späterer roter Lauf desselben Codes lässt sich also weder durch einen grünen Lauf
     auf SOURCE_SHA noch durch einen Re-Run verdecken. Abgebrochene/übersprungene Jobs sind kein Beleg und werden
     gezählt und ausgewiesen; ein noch laufender Job sperrt.
"""
import argparse
import json
import os
import re
import subprocess
import sys

ALLOWED = re.compile(r'^(\.github/workflows/(p4-test-package|helper-channel)\.yml|tools/device_gate\.py|tools/delivery/[^/]+\.py'
                     r'|tests/(gate|delivery)/.+|[^/]+\.md)$')
EMU = re.compile(r'^Test package de\.evp\.helfer\.channeltest on a disposable API (\d+) emulator$')
PLAIN_WORKFLOWS = ['.github/workflows/build.yml', '.github/workflows/browser-tests.yml', '.github/workflows/hls-download.yml']
CHANNEL = '.github/workflows/helper-channel.yml'


def gh(path):
    p = subprocess.run(['gh', 'api', '-H', 'Accept: application/vnd.github+json', path], capture_output=True, text=True, timeout=300)
    if p.returncode != 0:
        raise RuntimeError('gh api %s: %s' % (path.split('?')[0], p.stderr.strip()[:200]))
    return json.loads(p.stdout)


def git(*a):
    return subprocess.run(['git'] + list(a), check=True, capture_output=True, text=True).stdout


def same_code_commits(source, workflow_sha):
    commits = [source] + [c for c in git('rev-list', '--reverse', workflow_sha, '^' + source).split() if c]
    same = []
    for c in commits:
        changed = [f for f in git('diff', '--name-only', source, c).split('\n') if f]
        if all(ALLOWED.match(f) for f in changed):
            same.append(c)
    return same


def job_log(repo, job, log_dir):
    if log_dir:
        p = os.path.join(log_dir, 'job-%d.log' % job['id'])
        if os.path.exists(p):
            return open(p, encoding='utf-8', errors='replace').read()
    token = os.environ.get('GH_TOKEN', '')
    return subprocess.run(['curl', '-sSfL', '--retry', '3', '-H', 'Authorization: Bearer ' + token, '-H', 'Accept: application/vnd.github+json',
                           'https://api.github.com/repos/%s/actions/jobs/%d/logs' % (repo, job['id'])],
                          check=True, capture_output=True, text=True).stdout


def main():
    a = argparse.ArgumentParser()
    a.add_argument('--repo', required=True)
    a.add_argument('--source', required=True)
    a.add_argument('--workflow-sha', required=True)
    a.add_argument('--gate', required=True)
    a.add_argument('--log-dir')
    o = a.parse_args()
    fails = []

    def runs_for(sha):
        return gh('repos/%s/actions/runs?head_sha=%s&per_page=100' % (o.repo, sha))['workflow_runs']

    # 1) einfache Workflows auf SOURCE_SHA
    src_runs = runs_for(o.source)
    for path in PLAIN_WORKFLOWS:
        rs = [r for r in src_runs if r['path'] == path]
        if not rs:
            fails.append('no run of %s on %s' % (path, o.source))
            continue
        r = max(rs, key=lambda r: r['id'])
        if r['status'] != 'completed' or r['conclusion'] != 'success':
            fails.append('%s on %s: %s/%s' % (path, o.source, r['status'], r['conclusion']))
        else:
            print('PASS %s on source: success (run %d, attempt %d)' % (path, r['id'], r['run_attempt']))

    # 2) helper-channel auf SOURCE_SHA vollständig
    ch = [r for r in src_runs if r['path'] == CHANNEL and r['status'] == 'completed']
    if not ch:
        fails.append('no completed helper-channel run on the source commit')
    else:
        r = max(ch, key=lambda r: r['id'])
        jobs = gh('repos/%s/actions/runs/%d/jobs?per_page=100' % (o.repo, r['id']))['jobs']
        emu = sorted(int(EMU.match(j['name']).group(1)) for j in jobs if EMU.match(j['name']))
        other = [j for j in jobs if not EMU.match(j['name'])]
        if len(jobs) != 5 or emu != [26, 29, 34]:
            fails.append('helper-channel run %d on source has jobs %s' % (r['id'], [j['name'] for j in jobs]))
        bad = [(j['name'], j['conclusion']) for j in other if j['conclusion'] != 'success']
        if bad:
            fails.append('helper-channel run %d on source: non-emulator jobs not successful %s' % (r['id'], bad))
        print('source helper-channel run %d (attempt %d): emulator APIs %s' % (r['id'], r['run_attempt'], emu))

    # 3) alle Emulator-Jobs aller Versuche aller Läufe mit gleichem Anwendungscode
    same = same_code_commits(o.source, o.workflow_sha)
    print('same application code: %d commits (%s .. %s)' % (len(same), same[0][:7], same[-1][:7]))
    evaluated, ignored = 0, []
    for c in same:
        for r in [r for r in (src_runs if c == o.source else runs_for(c)) if r['path'] == CHANNEL]:
            for j in gh('repos/%s/actions/runs/%d/jobs?filter=all&per_page=100' % (o.repo, r['id']))['jobs']:
                m = EMU.match(j['name'])
                if not m:
                    continue
                api = int(m.group(1))
                where = '%s run %d attempt %d API %d (job %d)' % (c[:7], r['id'], j['run_attempt'], api, j['id'])
                if j['status'] != 'completed':
                    fails.append('emulator job still running: %s' % where)
                    continue
                if j['conclusion'] not in ('success', 'failure'):
                    ignored.append('%s: %s' % (where, j['conclusion']))
                    continue
                path = os.path.join(os.environ.get('RUNNER_TEMP', '/tmp'), 'gate-job-%d.log' % j['id'])
                open(path, 'w', encoding='utf-8').write(job_log(o.repo, j, o.log_dir))
                g = subprocess.run([sys.executable, '-I', o.gate, path, '--api', str(api), '--job-conclusion', j['conclusion']],
                                   capture_output=True, text=True)
                verdict = (re.search(r'verdict=(\S+)', g.stdout) or [None, '?'])[1]
                allowed = (0, 3) if api == 29 else (0,)
                evaluated += 1
                print('%s %s: %s' % ('PASS' if g.returncode in allowed else 'REJECT', where, verdict))
                if g.returncode not in allowed:
                    fails.append('device gate rejected %s: %s' % (where, '; '.join(l.strip() for l in g.stdout.splitlines()[1:4])))
    for i in ignored:
        print('NOT EVIDENCE (no verdict): ' + i)
    if evaluated == 0:
        fails.append('no emulator job evaluated')
    for f in fails:
        print('CI_EVIDENCE REJECT ' + f)
    print('CI_EVIDENCE %s: %d emulator jobs of the same application code evaluated, %d without verdict'
          % ('PASS' if not fails else 'REJECT', evaluated, len(ignored)))
    sys.exit(1 if fails else 0)


if __name__ == '__main__':
    main()
