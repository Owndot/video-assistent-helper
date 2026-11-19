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
       * cancelled/timed_out/Runner-Verlust im Geräteschritt: nie ein Positivbeleg; das Log wird mit DERSELBEN
         Klassifikation wie im Geräte-Gate geprüft (device_gate.failure_evidence: publish/crash/test-failure sperren,
         ein streng gültiger Mutantenlauf ist ausgenommen, P4B-A10-SIZE auf API 29 ist ausgewiesen, aber kein Beleg);
         ein gestarteter Job ohne lesbares Log → Sperre; vor dem Geräteschritt gescheitert → kein Beleg (ausgewiesen);
       * vollständige Logs: zusätzlich dieselbe Klassifikation; widerspricht sie dem Gate-Urteil → Sperre;
       * skipped bzw. nie gestartet: kein Beleg (ausgewiesen);
       * unerwartete Jobnamen, widersprüchliche Zählungen oder Lauf-Ergebnisse → Sperre.
     Codex NO-GO 3: Pflichtjobs (REQUIRED_OTHER: Core/JVM-/Sicherheits-/Mutationsprüfungen, Signierpfad) jedes Versuchs:
       gescheitert mit Prüfungsergebnis oder unbekannter Ursache → Sperre; nur Infrastruktur (z. B. Maven 429) bzw.
       Abbruch ohne Prüfungsergebnis → der Versuch liefert KEINEN Positivbeleg; fehlend → Sperre.
  3. Ein Positivbeleg zählt nur, wenn der tatsächlich getestete Dateibaum nachweislich der des Lauf-Commits ist (Codex
     NO-GO 3, P2): genau EIN Checkout (ein Fetch, ein "git checkout", ein "git log -1 --format=%H" mit derselben
     vollständigen SHA), keine weitere git-Änderung des Arbeitsbaums; push: Checkout-SHA = Lauf-Commit; pull_request:
     der Merge-Commit (Checkout-SHA, per GitHub-API) hat den Lauf-Commit als Elternteil UND exakt dessen Dateibaum.
     Nicht nachweisbar → kein Positivbeleg (fail closed; Fehlschläge zählen trotzdem).
  4. Abdeckung: für API 26, 29 und 34 je mindestens ein Positivbeleg auf gleichem Anwendungscode.
  5. Runde 5 (Testisolation): Jeder Emulator-Job hat GENAU die drei benannten Geräteschritte (DEVICE_STEPS: mutant, main,
     repeat, je ein frischer Emulator) in dieser Reihenfolge; fehlend, doppelt, umbenannt, vertauscht oder der frühere
     Sammelschritt -> Sperre. Die Schrittergebnisse müssen zur Bedingungslogik von helper-channel.yml passen (Hauptlauf nur
     nach erfolgreicher Mutante, Wiederholungen nur nach gelaufenem Hauptlauf, ein grüner Job nur mit drei grünen
     Schritten), sonst Sperre; übersprungene/abgebrochene Schritte sind nie ein Positivbeleg. Das Geräte-Gate erhält die
     Schrittergebnisse (--step-conclusions) und prüft sie gegen die Belege jeder Phase.
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
                     r'|tools/delivery/[^/]+\.py|tools/run-flaky-repeat\.sh|tools/run-channel-publication-regression\.sh|tools/run-orphan-collision-probe\.sh'
                     r'|app/src/androidTestChanneltest/java/de/evp/helfer/OrphanNameCollisionProbeTest\.java'
                     r'|tests/(gate|delivery)/.+|evidence/.+|[^/]+\.md)$')
EMU = re.compile(r'^Test package de\.evp\.helfer\.channeltest on a disposable API (\d+) emulator$')
KNOWN_OTHER = {'Core, JVM channel checks, mutation probes, WASM unchanged',
               'P4 signing path on macOS (stock tools, throwaway key, nothing published)'}
PLAIN_WORKFLOWS = ['.github/workflows/build.yml', '.github/workflows/browser-tests.yml', '.github/workflows/hls-download.yml']
CHANNEL = '.github/workflows/helper-channel.yml'
# Runde 5: drei Geräteschritte je Emulator-Job (Reihenfolge = Phasen des Geräte-Gates). Jeder Schritt, der wie ein
# Geräteschritt aussieht (auch der frühere Sammelschritt), wird erfasst und muss genau einem erwarteten Namen entsprechen.
DEVICE_PHASES = ('mutant', 'main', 'repeat')
DEVICE_STEPS = {'mutant': 'Device phase 1/3 - publication mutant on a fresh API %d x86_64 emulator',
                'main': 'Device phase 2/3 - main instrumentation on a fresh API %d x86_64 emulator',
                'repeat': 'Device phase 3/3 - repeat runs on a fresh API %d x86_64 emulator'}
DEVICE_STEP_LIKE = re.compile(r'^(Device phase|Instrumentation on a disposable API)', re.I)
NOT_RUN = (None, 'skipped')
APIS = (26, 29, 34)
# Codex NO-GO 3 (P1): Pflichtjobs außer den Emulatoren (Core/JVM/Sicherheits-/Mutationsprüfungen, Signierpfad). Ein
# Positivbeleg eines Laufversuchs zählt nur, wenn ALLE Pflichtjobs dieses Versuchs erfolgreich waren.
REQUIRED_OTHER = sorted(KNOWN_OTHER)
# Nur Infrastruktur (vor/ohne Prüfungsergebnis): Abhängigkeits-Download, Netzwerk, Runner-Verlust
INFRA = re.compile(r'Received status code (429|5\d\d)|Could not GET |Could not resolve all (artifacts|dependencies|files)|Could not resolve [\w.-]+:[\w.-]+:'
                   r'|Connection reset|Read timed out|ETIMEDOUT|ECONNRESET|429 Too Many Requests|The runner has received a shutdown signal'
                   r'|lost communication with the server|The hosted runner encountered an error')
# Ergebnis einer Prüfung (Test, Selbsttest, Mutation, Kompilierung): nie Infrastruktur
PRODUCT = re.compile(r'AssertionError|ComparisonFailure|panicked at|test result: FAILED|FAILURES!!!|Tests run: \d+,\s+Failures: [1-9]'
                     r'|\bSURVIVED\b|\bsurvived\b|SELFTEST FAIL|selftest FAIL|^FAIL |\bFAIL: |RESULT \S+ FAIL|error\[E\d{4}\]|error: could not compile'
                     r'|cannot find symbol|Compilation failed|compileJava FAILED|FATAL EXCEPTION|OutOfMemoryError|publish rows=|publish threw=', re.M)
# Getesteter Code: genau EIN Checkout (actions/checkout) mit vollständiger SHA; jede weitere Arbeitsbaum-Änderung per git
CHECKOUT_FETCH = re.compile(r'^\[command\]/usr/bin/git -c protocol\.version=2 fetch .* origin \+?([0-9a-f]{40}):(\S+)$')
CHECKOUT_CMD = re.compile(r'^\[command\]/usr/bin/git checkout --progress --force \S+')
# Runde 6: auch als Pfad aufgerufenes git ("[command]/usr/bin/git switch …") zählt; vorher übersah das Muster diese Form.
# Runde 6b: beliebig viele globale Optionen vor dem Unterbefehl ("git -c k=v checkout", "git --git-dir=… reset",
# "git --work-tree /w restore"); Optionen mit getrenntem Argument (-c, -C, --git-dir, --work-tree, --namespace, …)
# verbrauchen ihr Argument, jede andere Option ist ein einzelnes Wort, das mit "-" beginnt.
GIT_GLOBAL_OPT = r'(?:\s+(?:-[cC]|--git-dir|--work-tree|--namespace|--exec-path|--config-env|--super-prefix|--list-cmds)\s+\S+|\s+-\S+)*'
TREE_CHANGE = re.compile(r'(^|[\s;&|(`/])git' + GIT_GLOBAL_OPT + r'\s+(checkout|switch|reset|restore|stash|merge|rebase|cherry-pick|am|apply|pull|read-tree|worktree|clone|fetch)\b')
HEAD_AT = re.compile(r'^HEAD is now at ([0-9a-f]{7,40}) (.*)$')
# Runde 6: Hinweistext von git beim Wechsel auf einen losgelösten HEAD (actions/checkout, git >= 2.23). Er enthält zwei
# Beispielzeilen ("git switch …"), die TREE_CHANGE sonst als Befehl wertet. Ausgenommen werden NUR diese zwei Zeilen und
# NUR, wenn unmittelbar nach dem einen Checkout-Befehl exakt dieser Text steht (Zeile für Zeile, ohne Zusatz). Jede
# Abweichung (eingefügte, fehlende oder geänderte Zeile) hebt die Ausnahme auf: dann zählen alle Zeilen (fail closed).
DETACHED_NOTE = re.compile(r"^Note: switching to '[^']+'\.$")
DETACHED_ADVICE = ['',
                   "You are in 'detached HEAD' state. You can look around, make experimental",
                   'changes and commit them, and you can discard any commits you make in this',
                   'state without impacting any branches by switching back to a branch.',
                   '',
                   'If you want to create a new branch to retain commits you create, you may',
                   'do so (now or later) by using -c with the switch command. Example:',
                   '',
                   '  git switch -c <new-branch-name>',
                   '',
                   'Or undo this operation with:',
                   '',
                   '  git switch -',
                   '',
                   'Turn off this advice by setting config variable advice.detachedHead to false',
                   '']
DETACHED_HINT_LINES = ('  git switch -c <new-branch-name>', '  git switch -')
ANSI = re.compile(r'\x1b\[[0-9;]*m|\[[0-9;]*m')
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

    def tree(self, sha):
        return self.run('rev-parse', sha + '^{tree}').strip()

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


def device_steps(steps, api):
    """Runde 5: -> ({Phase: Schritt}, '') bei genau den drei erwarteten Geräteschritten in dieser Reihenfolge, sonst
    (None, Grund). Reihenfolge nach Schrittnummer (falls angegeben), sonst nach Listenposition."""
    steps = list(steps or [])
    if all(isinstance(x.get('number'), int) for x in steps):
        steps.sort(key=lambda x: x['number'])
    like = [x for x in steps if DEVICE_STEP_LIKE.match(x.get('name') or '')]
    want = [DEVICE_STEPS[p] % api for p in DEVICE_PHASES]
    got = [x.get('name') for x in like]
    if got != want:
        return None, 'found %s, expected %s' % (got, want)
    return dict(zip(DEVICE_PHASES, like)), ''


def step_contradiction(dc, concl):
    """Runde 5: Schrittergebnisse eines abgeschlossenen (success/failure) Jobs gegen die Bedingungslogik von
    helper-channel.yml. -> Grund oder ''. Jeder Widerspruch sperrt (fail closed)."""
    ran = {p: dc[p] in ('success', 'failure') for p in DEVICE_PHASES}
    if ran['main'] and dc['mutant'] != 'success':
        return 'main instrumentation ran without a successful mutant phase'
    if ran['repeat'] and not (dc['mutant'] == 'success' and ran['main']):
        return 'repeat runs ran without a mutant phase and a main run'
    if dc['mutant'] == 'success' and not ran['main']:
        return 'main instrumentation skipped after a successful mutant phase'
    if ran['main'] and not ran['repeat']:
        return 'repeat runs skipped after a main run'
    if concl == 'success' and any(dc[p] != 'success' for p in DEVICE_PHASES):
        return 'job success although a device step did not succeed'
    if any(dc[p] == 'failure' for p in DEVICE_PHASES) and concl != 'failure':
        return 'device step failed but job conclusion %s' % concl
    return ''


def gate_verdict(gate, log, api, conclusion, tmpdir, job_id, step_conclusions=None):
    path = os.path.join(tmpdir, 'gate-job-%d.log' % job_id)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(log)
    cmd = [sys.executable, '-I', gate, path, '--api', str(api), '--job-conclusion', conclusion]
    if step_conclusions is not None:
        cmd += ['--step-conclusions', ','.join('%s=%s' % (p, step_conclusions[p] or 'none') for p in DEVICE_PHASES)]
    g = subprocess.run(cmd, capture_output=True, text=True)
    m = re.search(r'verdict=(\S+)', g.stdout)
    verdict = m.group(1) if m else 'GATE-ERROR'
    want = {'PASS': 0, 'ACCEPTED_SIZE_ONLY': 3, 'INSUFFICIENT_EVIDENCE': 4, 'REJECT': 1}.get(verdict)
    if want is None or g.returncode != want:
        return 'REJECT', 'gate output inconsistent (exit %d, verdict %s)' % (g.returncode, verdict)
    return verdict, '; '.join(l.strip() for l in g.stdout.splitlines()[1:4])


def load_gate(path):
    """Gemeinsame Fehlerklassifikation aus dem Geräte-Gate (failure_evidence), damit vollständige und abgebrochene Logs
    nach denselben Mustern bewertet werden."""
    import importlib.util
    spec = importlib.util.spec_from_file_location('device_gate_for_ci_evidence', path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.failure_evidence, mod.BLOCKING


def strip_ts(line):
    return ANSI.sub('', re.sub(r'^﻿?\d{4}-\d\d-\d\dT[\d:.]+Z ', '', line.rstrip('\r')))


def detached_hint_lines(lines, i_cmd):
    """Runde 6: Indizes der zwei Beispielzeilen des git-Hinweises, nur wenn direkt nach dem Checkout-Befehl (Zeile i_cmd)
    genau "Note: switching to '<ref>'.", der vollständige bekannte Hinweistext und "HEAD is now at …" folgen. Sonst leer."""
    lo, n = i_cmd + 1, len(DETACHED_ADVICE)
    block = [l.rstrip() for l in lines[lo + 1:lo + 1 + n]]
    if (lo + 1 + n >= len(lines) or not DETACHED_NOTE.match(lines[lo].rstrip()) or block != DETACHED_ADVICE
            or not HEAD_AT.match(lines[lo + 1 + n])):
        return set()
    return {lo + 1 + k for k, l in enumerate(DETACHED_ADVICE) if l in DETACHED_HINT_LINES}


def checkout_of(log):
    """-> (vollständige Checkout-SHA, Erklärung) oder (None, Grund). Genau ein actions/checkout: ein Fetch der SHA, ein
    "git checkout", ein "git log -1 --format=%H" mit derselben SHA; keine weitere Arbeitsbaum-Änderung per git."""
    lines = [strip_ts(l) for l in log.splitlines()]
    fetch = [m for m in (CHECKOUT_FETCH.match(l) for l in lines) if m]
    cmds = [i for i, l in enumerate(lines) if CHECKOUT_CMD.match(l)]
    logs = [i for i, l in enumerate(lines) if l == '[command]/usr/bin/git log -1 --format=%H']
    if len(fetch) != 1 or len(cmds) != 1 or len(logs) != 1:
        return None, 'expected exactly one checkout (fetch %d, checkout %d, git log %d)' % (len(fetch), len(cmds), len(logs))
    nxt = next((l for l in lines[logs[0] + 1:] if l.strip()), '')
    if not re.match(r'^[0-9a-f]{40}$', nxt):
        return None, 'checkout SHA not printed'
    if nxt != fetch[0].group(1):
        return None, 'checked-out %s differs from the fetched %s' % (nxt[:7], fetch[0].group(1)[:7])
    hint = detached_hint_lines(lines, cmds[0])
    other = [l for i, l in enumerate(lines) if i != cmds[0] and i not in hint and TREE_CHANGE.search(l) and not CHECKOUT_FETCH.match(l)]
    if other:
        return None, 'further git command that can change the tested tree: %s' % other[0][:100]
    heads = [m for m in (HEAD_AT.match(l) for l in lines) if m]
    if len(heads) > 1 or (heads and not nxt.startswith(heads[0].group(1))):
        return None, 'checkout lines contradict each other (%d "HEAD is now at")' % len(heads)
    return nxt, heads[0].group(2) if heads else ''


def tested_code_matches(gh, git, log, head_sha, event):
    """(ok, Erklärung): der tatsächlich getestete Dateibaum ist nachweislich der des Lauf-Commits. Fail closed."""
    try:
        sha, subject = checkout_of(log)
        if sha is None:
            return False, subject
        if event == 'push':
            return (sha == head_sha), 'checkout %s, run commit %s' % (sha[:7], head_sha[:7])
        if event != 'pull_request':
            return False, 'unsupported event %s' % event
        m = re.match(r'^Merge ([0-9a-f]{40}) into ([0-9a-f]{40})$', subject)
        if not m or m.group(1) != head_sha:
            return False, 'merge checkout of another head (%s)' % subject[:60]
        c = gh.get('git/commits/%s' % sha)
        tree, parents = c['tree']['sha'], [x['sha'] for x in c.get('parents', [])]
        if head_sha not in parents:
            return False, 'merge commit %s does not have %s as a parent' % (sha[:7], head_sha[:7])
        want = git.tree(head_sha)
        if tree != want:
            return False, 'merge tree %s differs from the tree %s of %s' % (tree[:7], want[:7], head_sha[:7])
        return True, 'merge commit %s has exactly the tree of %s' % (sha[:7], head_sha[:7])
    except Exception as e:  # noqa: BLE001 - kein Nachweis
        return False, 'tested tree not provable: %s: %s' % (type(e).__name__, str(e)[:100])


def required_job_failure(log, conclusion, steps):
    """Fehlgeschlagener/abgebrochener Pflichtjob -> 'product' (sperrt) | 'infra' | 'cancelled' (beides: kein Beleg).
    Bewertet wird nur die Ausgabe des GESCHEITERTEN Schritts (vom letzten "##[group]Run" bis zu seinem "##[error]"),
    nicht das ganze Log: Selbsttests geben absichtlich Wörter wie SURVIVED/FAIL für ihre Negativfälle aus.
      * kein Schritt mit "failure" und Job abgebrochen -> 'cancelled' (kein Prüfungsergebnis);
      * jeder gescheiterte Schritt zeigt NUR Infrastrukturmerkmale (z. B. Maven 429) -> 'infra';
      * sonst (Prüfungsergebnis, unbekannte Ursache, kein zuordenbarer Fehler) -> 'product' (fail closed)."""
    failed_steps = [x for x in steps or [] if x.get('conclusion') == 'failure']
    lines = [strip_ts(l) for l in log.splitlines()]
    errors = [i for i, l in enumerate(lines) if l.startswith('##[error]Process completed with exit code')]
    if not failed_steps and not errors:
        return 'cancelled' if conclusion in ('cancelled', 'timed_out') else 'product'
    if not errors:
        return 'product'
    for i in errors:
        start = max([k for k in range(i) if lines[k].startswith('##[group]Run ')] or [0])
        segment = '\n'.join(lines[start:i])
        if PRODUCT.search(segment) or not INFRA.search(segment):
            return 'product'
    return 'infra'


def evaluate(gh, git, source, workflow_sha, gate, tmpdir, out=print, classifier=None):
    fails, ignored, covered = [], [], {a: [] for a in APIS}
    try:
        classify, blocking = classifier or load_gate(gate)
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
                    apis, candidates, required_ok, seen_required = [], [], True, set()
                    for j in jobs:
                        m = EMU.match(j['name'])
                        if not m:
                            if j['name'] not in KNOWN_OTHER:
                                fails.append('unexpected job %r in run %d' % (j['name'], r['id']))
                                continue
                            # Pflichtjob (Core/JVM/Sicherheit/Signierpfad)
                            where = '%s run %d attempt %d %r (job %d)' % (c[:7], r['id'], attempt, j['name'], j['id'])
                            seen_required.add(j['name'])
                            if j['status'] != 'completed':
                                fails.append('required job not completed: %s' % where)
                                required_ok = False
                            elif j['conclusion'] == 'success':
                                pass
                            elif j['conclusion'] == 'skipped' or not j.get('started_at'):
                                required_ok = False
                                ignored.append('%s: required job %s, never ran - no evidence for this attempt' % (where, j['conclusion']))
                            else:
                                required_ok = False
                                try:
                                    jlog = gh.log(j['id'])
                                except Exception as e:  # noqa: BLE001 - fail closed
                                    fails.append('log unavailable for failed required job %s: %s' % (where, e))
                                    continue
                                kind = required_job_failure(jlog, j['conclusion'], j.get('steps'))
                                if kind == 'product':
                                    fails.append('required job failed: %s (%s)' % (where, j['conclusion']))
                                else:
                                    ignored.append('%s: required job %s (%s, no check result) - no evidence for this attempt' % (where, j['conclusion'], kind))
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
                        dev, why = device_steps(j.get('steps'), api)
                        if dev is None:
                            fails.append('device steps missing, duplicated, renamed or out of order in %s: %s' % (where, why))
                            continue
                        dc = {p: dev[p].get('conclusion') for p in DEVICE_PHASES}
                        if all(dc[p] in NOT_RUN for p in DEVICE_PHASES) and concl != 'success':
                            # Bau/Vorbereitung scheiterte, kein Gerätetest lief: kein Beleg über die App (ausgewiesen)
                            failed = [s.get('name') for s in j.get('steps') or [] if s.get('conclusion') == 'failure']
                            ignored.append('%s: %s before the device step ran (failed step %s) - no device evidence' % (where, concl, failed))
                            continue
                        aborted = concl not in ('success', 'failure') or any(dc[p] not in NOT_RUN + ('success', 'failure') for p in DEVICE_PHASES)
                        if not aborted:
                            bad = step_contradiction(dc, concl)
                            if bad:
                                fails.append('device step results contradictory in %s: %s %s' % (where, bad, dc))
                                continue
                        try:
                            log = gh.log(j['id'])
                        except Exception as e:  # fail closed
                            fails.append('log unavailable for %s: %s' % (where, e))
                            continue
                        try:
                            ev = classify(log, api)
                        except Exception as e:  # noqa: BLE001 - fail closed
                            fails.append('failure classification error for %s: %s' % (where, e))
                            continue
                        block = [e for e in ev if e[0] in blocking]
                        if aborted:
                            # abgebrochen/Zeitüberschreitung/Runner-Verlust im Geräteschritt: nie ein Positivbeleg; vorhandene
                            # Fehlerbelege (gleiche Klassifikation wie das Gate) sperren
                            if block:
                                fails.append('failure evidence in %s job %s: %s line %d: %s' % (concl, where, block[0][0], block[0][1], block[0][2][:100]))
                            elif ev:
                                ignored.append('%s: %s, only the documented P4B-A10-SIZE finding - no evidence' % (where, concl))
                            else:
                                ignored.append('%s: %s without failure evidence (expected mutant run only or nothing) - no evidence' % (where, concl))
                            continue
                        verdict, detail = gate_verdict(gate, log, api, concl, tmpdir, j['id'], dc)
                        if block and verdict != 'REJECT':
                            fails.append('failure evidence contradicts gate verdict %s in %s: %s' % (verdict, where, block[0][2][:100]))
                            continue
                        if verdict == 'REJECT':
                            fails.append('device gate rejected %s: %s' % (where, detail))
                        elif verdict == 'INSUFFICIENT_EVIDENCE':
                            ignored.append('%s: insufficient evidence (%s)' % (where, detail[:120]))
                        elif verdict == 'ACCEPTED_SIZE_ONLY' and api != 29:
                            fails.append('SIZE exception outside API 29: %s' % where)
                        else:
                            ok, why = tested_code_matches(gh, git, log, c, r.get('event', ''))
                            if ok:
                                candidates.append((api, where))
                            else:
                                ignored.append('%s: %s, tested code not proven (%s)' % (where, verdict, why))
                        out('%s %s: %s' % ('REJECT' if verdict == 'REJECT' else 'NOTE', where, verdict))
                    missing_required = [n for n in REQUIRED_OTHER if n not in seen_required]
                    if jobs and missing_required:
                        fails.append('run %d attempt %d: required jobs missing %s' % (r['id'], attempt, missing_required))
                        required_ok = False
                    for api, where in candidates:
                        if required_ok:
                            covered[api].append(where)
                        else:
                            ignored.append('%s: device evidence not counted, a required job of this attempt did not succeed' % where)
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
