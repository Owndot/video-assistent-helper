#!/usr/bin/env python3
"""CI-Belege für eine P4-Testlieferung des Helpers (Codex Release-Readiness 1+2). Läuft im Paketjob NACH der
Freigabesperre, im Checkout mit vollständiger Historie; liest nur (GitHub-API, git). Jede Unklarheit sperrt.

Aufruf:
  ci_evidence.py --repo OWNER/NAME --source SHA --workflow-sha SHA --gate tools/device_gate.py [--log-dir DIR]

Regeln (alle müssen gelten, sonst Exit 1):
  1. Auf SOURCE_SHA sind build.yml, browser-tests.yml und hls-download.yml im neuesten Lauf erfolgreich.
  2. „Gleicher Anwendungscode“ = SOURCE_SHA und jeder Commit bis WORKFLOW_SHA, dessen Abweichung von SOURCE_SHA nur
     Liefer-/Prüfwerkzeuge betrifft (ALLOWED). Für ALLE helper-channel-Läufe dieser Commits (vollständig paginiert),
     ALLE Versuche (jeder Versuch einzeln abgefragt) und ALLE Emulator-Jobs gilt:
       * ein noch laufender Lauf oder Job sperrt – auch ein Lauf ohne gelistete Jobs;
       * success/failure: Log Pflicht (fehlt es → Sperre), Geräte-Gate: REJECT sperrt, INSUFFICIENT_EVIDENCE ist
         kein Beleg, PASS (bzw. auf API 29 ACCEPTED_SIZE_ONLY) ist ein Beleg;
       * cancelled/timed_out/…: Log wird trotzdem auf Fehlerbelege geprüft (Veröffentlichungsfehler, Absturz,
         gescheiterte Tests) – gefunden → Sperre; ein gestarteter Job ohne lesbares Log → Sperre;
       * skipped bzw. nie gestartet: kein Beleg (ausgewiesen);
       * unerwartete Jobnamen, widersprüchliche Zählungen oder Lauf-Ergebnisse → Sperre.
  3. Ein Positivbeleg zählt nur, wenn der getestete Code nachweislich dem Commit entspricht: Bei pull_request-Läufen
     nennt der Checkout „Merge <head> into <base>“; <head> muss der Lauf-Commit sein und <base> ein Vorgänger davon
     (dann ist der Merge-Baum gleich dem Commit). Sonst kein Positivbeleg (Fehlschläge zählen trotzdem).
  4. Abdeckung: für API 26, 29 und 34 je mindestens ein Positivbeleg auf gleichem Anwendungscode.
"""
import argparse
import json
import os
import re
import subprocess
import sys

# Nur Liefer-/Prüfwerkzeuge, die nicht in die Test-APK de.evp.helfer.p4test eingehen. Die Orphan-Sonde ist eine einzelne
# zusätzliche Testklasse des channeltest-Test-APK, die im normalen Gerätelauf nicht ausgeführt wird.
ALLOWED = re.compile(r'^(\.github/workflows/(p4-test-package|helper-channel|orphan-collision-probe)\.yml|tools/device_gate\.py'
                     r'|tools/delivery/[^/]+\.py|tools/run-flaky-repeat\.sh|tools/run-orphan-collision-probe\.sh'
                     r'|app/src/androidTestChanneltest/java/de/evp/helfer/OrphanNameCollisionProbeTest\.java'
                     r'|tests/(gate|delivery)/.+|evidence/.+|[^/]+\.md)$')
EMU = re.compile(r'^Test package de\.evp\.helfer\.channeltest on a disposable API (\d+) emulator$')
KNOWN_OTHER = {'Core, JVM channel checks, mutation probes, WASM unchanged',
               'P4 signing path on macOS (stock tools, throwaway key, nothing published)'}
PLAIN_WORKFLOWS = ['.github/workflows/build.yml', '.github/workflows/browser-tests.yml', '.github/workflows/hls-download.yml']
CHANNEL = '.github/workflows/helper-channel.yml'
APIS = (26, 29, 34)
FAILURE_EVIDENCE = re.compile(r'publish rows=0|IOException: publish rows|FATAL EXCEPTION|OutOfMemoryError|DeadSystemException'
                              r'|Process crashed|INSTRUMENTATION_ABORTED|^FAILURES!!!$|INSTRUMENTATION_STATUS_CODE: -[12]$', re.M)
MERGE = re.compile(r'(?m)^(?:\S+Z )?HEAD is now at [0-9a-f]+ Merge ([0-9a-f]{40}) into ([0-9a-f]{40})')
PER_PAGE = 100
MAX_PAGES = 50


class Github:
    def __init__(self, repo):
        self.repo = repo

    def get(self, path):
        p = subprocess.run(['gh', 'api', '-H', 'Accept: application/vnd.github+json', 'repos/%s/%s' % (self.repo, path)],
                           capture_output=True, text=True, timeout=300)
        if p.returncode != 0:
            raise RuntimeError('GitHub API %s: %s' % (path.split('?')[0], p.stderr.strip()[:200]))
        return json.loads(p.stdout)

    def log(self, job_id):
        token = os.environ.get('GH_TOKEN', '')
        p = subprocess.run(['curl', '-sSfL', '--retry', '3', '-H', 'Authorization: Bearer ' + token, '-H', 'Accept: application/vnd.github+json',
                            'https://api.github.com/repos/%s/actions/jobs/%d/logs' % (self.repo, job_id)], capture_output=True, text=True)
        if p.returncode != 0 or not p.stdout.strip():
            raise RuntimeError('job log %d unavailable: %s' % (job_id, p.stderr.strip()[:200]))
        return p.stdout


class Git:
    def run(self, *a):
        return subprocess.run(['git'] + list(a), check=True, capture_output=True, text=True).stdout

    def same_code_commits(self, source, workflow_sha):
        commits = [source] + [c for c in self.run('rev-list', '--reverse', workflow_sha, '^' + source).split() if c]
        out = []
        for c in commits:
            changed = [f for f in self.run('diff', '--name-only', source, c).split('\n') if f]
            if all(ALLOWED.match(f) for f in changed):
                out.append(c)
        return out

    def is_ancestor(self, a, b):
        return subprocess.run(['git', 'merge-base', '--is-ancestor', a, b], capture_output=True).returncode == 0


def paged(gh, path, key):
    """Alle Seiten; Gesamtzahl muss zur Summe passen (sonst Widerspruch → Ausnahme)."""
    items, total = [], None
    for page in range(1, MAX_PAGES + 1):
        data = gh.get('%s%sper_page=%d&page=%d' % (path, '&' if '?' in path else '?', PER_PAGE, page))
        batch = data.get(key)
        if not isinstance(batch, list):
            raise RuntimeError('%s: no %s list in the answer' % (path.split('?')[0], key))
        if total is None:
            total = data.get('total_count')
        items += batch
        if len(batch) < PER_PAGE:
            break
    else:
        raise RuntimeError('%s: more than %d pages' % (path.split('?')[0], MAX_PAGES))
    if total is None or total != len(items) or len({i['id'] for i in items}) != len(items):
        raise RuntimeError('%s: total_count %s, received %d (duplicates or missing pages)' % (path.split('?')[0], total, len(items)))
    return items


def gate_verdict(gate, log, api, conclusion, tmpdir, job_id):
    path = os.path.join(tmpdir, 'gate-job-%d.log' % job_id)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(log)
    g = subprocess.run([sys.executable, '-I', gate, path, '--api', str(api), '--job-conclusion', conclusion], capture_output=True, text=True)
    m = re.search(r'verdict=(\S+)', g.stdout)
    verdict = m.group(1) if m else 'GATE-ERROR'
    want = {'PASS': 0, 'ACCEPTED_SIZE_ONLY': 3, 'INSUFFICIENT_EVIDENCE': 4, 'REJECT': 1}.get(verdict)
    if want is None or g.returncode != want:
        return 'REJECT', 'gate output inconsistent (exit %d, verdict %s)' % (g.returncode, verdict)
    return verdict, '; '.join(l.strip() for l in g.stdout.splitlines()[1:4])


def tested_code_matches(git, log, head_sha, event):
    """(ok, Erklärung): entspricht der getestete Baum nachweislich dem Lauf-Commit?"""
    m = MERGE.findall(log)
    if event == 'pull_request':
        if len(set(m)) != 1:
            return False, 'merge checkout line missing or ambiguous'
        head, base = m[0]
        if head != head_sha:
            return False, 'log tested %s, run says %s' % (head[:7], head_sha[:7])
        if not git.is_ancestor(base, head):
            return False, 'base %s is not an ancestor of %s (merge tree differs)' % (base[:7], head[:7])
        return True, 'merge of %s into its own ancestor %s' % (head[:7], base[:7])
    if m:
        return False, 'unexpected merge checkout in a %s run' % event
    return (re.search(r'(?m)^(?:\S+Z )?HEAD is now at %s ' % head_sha[:7], log) is not None), 'checkout of %s' % head_sha[:7]


def evaluate(gh, git, source, workflow_sha, gate, tmpdir, out=print):
    fails, ignored, covered = [], [], {a: [] for a in APIS}
    try:
        src_runs = paged(gh, 'actions/runs?head_sha=%s' % source, 'workflow_runs')
        for path in PLAIN_WORKFLOWS:
            rs = [r for r in src_runs if r['path'] == path]
            if not rs:
                fails.append('no run of %s on %s' % (path, source[:7]))
                continue
            r = max(rs, key=lambda r: r['id'])
            if r['status'] != 'completed' or r['conclusion'] != 'success':
                fails.append('%s on %s: %s/%s' % (path, source[:7], r['status'], r['conclusion']))
            else:
                out('PASS %s on source: success (run %d, attempt %d)' % (path, r['id'], r['run_attempt']))
        same = git.same_code_commits(source, workflow_sha)
        out('same application code: %d commits (%s .. %s)' % (len(same), same[0][:7], same[-1][:7]))
        for c in same:
            runs = [r for r in (src_runs if c == source else paged(gh, 'actions/runs?head_sha=%s' % c, 'workflow_runs')) if r['path'] == CHANNEL]
            for r in runs:
                if r['head_sha'] != c:
                    fails.append('run %d lists head %s, asked for %s' % (r['id'], r['head_sha'][:7], c[:7]))
                    continue
                if r['status'] != 'completed':
                    fails.append('helper-channel run %d on %s is %s (no verdict possible)' % (r['id'], c[:7], r['status']))
                    continue
                for attempt in range(1, int(r['run_attempt']) + 1):
                    jobs = paged(gh, 'actions/runs/%d/attempts/%d/jobs' % (r['id'], attempt), 'jobs')
                    if not jobs:
                        fails.append('run %d attempt %d lists no jobs' % (r['id'], attempt))
                    apis = []
                    for j in jobs:
                        m = EMU.match(j['name'])
                        if not m:
                            if j['name'] not in KNOWN_OTHER:
                                fails.append('unexpected job %r in run %d' % (j['name'], r['id']))
                            continue
                        api = int(m.group(1))
                        apis.append(api)
                        where = '%s run %d attempt %d API %d (job %d)' % (c[:7], r['id'], attempt, api, j['id'])
                        if api not in APIS:
                            fails.append('unexpected emulator API %s' % where)
                            continue
                        if int(j.get('run_attempt', attempt)) != attempt or j['run_id'] != r['id']:
                            fails.append('job data contradicts run/attempt: %s' % where)
                            continue
                        if j['status'] != 'completed':
                            fails.append('emulator job not completed: %s' % where)
                            continue
                        concl = j['conclusion']
                        started = bool(j.get('started_at')) and any(s.get('conclusion') not in (None, 'skipped') for s in j.get('steps') or [{}])
                        if concl == 'skipped' or not started:
                            ignored.append('%s: %s, never started' % (where, concl))
                            continue
                        try:
                            log = gh.log(j['id'])
                        except Exception as e:  # fail closed
                            fails.append('log unavailable for %s: %s' % (where, e))
                            continue
                        if concl not in ('success', 'failure'):
                            # abgebrochen/Zeitüberschreitung: kein Urteil, aber vorhandene Fehlerbelege zählen
                            ev = FAILURE_EVIDENCE.search('\n'.join(l for l in log.splitlines() if 'PUBLISH_PROBE_ATTRIBUTION' not in l
                                                                   and 'PUBLISH_PROBE_SIGNATURE' not in l and 'PUBLISH_RACE' not in l
                                                                   and 'previous cleanup fails the publication regression' not in l))
                            if ev:
                                fails.append('failure evidence in %s job %s: %s' % (concl, where, ev.group(0)))
                            else:
                                ignored.append('%s: %s without failure evidence' % (where, concl))
                            continue
                        verdict, detail = gate_verdict(gate, log, api, concl, tmpdir, j['id'])
                        if verdict == 'REJECT':
                            fails.append('device gate rejected %s: %s' % (where, detail))
                        elif verdict == 'INSUFFICIENT_EVIDENCE':
                            ignored.append('%s: insufficient evidence (%s)' % (where, detail[:120]))
                        elif verdict == 'ACCEPTED_SIZE_ONLY' and api != 29:
                            fails.append('SIZE exception outside API 29: %s' % where)
                        else:
                            ok, why = tested_code_matches(git, log, c, r.get('event', ''))
                            if ok:
                                covered[api].append(where)
                            else:
                                ignored.append('%s: %s, tested code not proven (%s)' % (where, verdict, why))
                        out('%s %s: %s' % ('REJECT' if verdict == 'REJECT' else 'NOTE', where, verdict))
                    if sorted(apis) != list(APIS):
                        fails.append('run %d attempt %d emulator APIs %s, expected %s' % (r['id'], attempt, sorted(apis), list(APIS)))
                    if r['conclusion'] == 'success' and attempt == int(r['run_attempt']) and any(
                            j['conclusion'] not in ('success', 'skipped') for j in jobs):
                        fails.append('run %d reports success but attempt %d has unsuccessful jobs' % (r['id'], attempt))
        for api in APIS:
            if not covered[api]:
                fails.append('no positive evidence for API %d on the same application code' % api)
    except Exception as e:  # fail closed: API-Fehler, Widersprüche, unerwartete Daten
        fails.append('evidence collection failed: %s: %s' % (type(e).__name__, e))
    for i in ignored:
        out('NO EVIDENCE: ' + i)
    for f in fails:
        out('CI_EVIDENCE REJECT ' + f)
    out('CI_EVIDENCE %s: positive evidence %s; %d jobs without verdict'
        % ('PASS' if not fails else 'REJECT', {a: len(v) for a, v in covered.items()}, len(ignored)))
    return not fails


def main():
    a = argparse.ArgumentParser()
    a.add_argument('--repo', required=True)
    a.add_argument('--source', required=True)
    a.add_argument('--workflow-sha', required=True)
    a.add_argument('--gate', required=True)
    a.add_argument('--log-dir', help='lokale Logs job-<id>.log statt Abruf (nur Prüfung/Trockenlauf)')
    o = a.parse_args()
    gh = Github(o.repo)
    if o.log_dir:
        fetch = gh.log

        def log(job_id):
            p = os.path.join(o.log_dir, 'job-%d.log' % job_id)
            return open(p, encoding='utf-8', errors='replace').read() if os.path.exists(p) else fetch(job_id)
        gh.log = log
    ok = evaluate(gh, Git(), o.source, o.workflow_sha, o.gate, os.environ.get('RUNNER_TEMP', '/tmp'))
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
