#!/usr/bin/env python3
"""Negative Selbsttests der Mutationsbewertung: Übersetzungsfehler, Infrastrukturfehler, Zeitüberschreitung und ein
Abbruch ohne fehlgeschlagene Prüfung dürfen NIE als erkannte Mutanten zählen. Aufruf: python3 -I mutation_verdict_selftest.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mutation_verdict as mv  # noqa: E402

DL = [r'^FAIL ', r'dl tests: \d+ passed, [1-9]\d* failed']
fails = 0


def expect(name, got, want):
    global fails
    ok = got == want
    fails += 0 if ok else 1
    print('%s %s: %s' % ('PASS' if ok else 'FAIL', name, got if ok else 'got %s, want %s' % (got, want)))


# Synthetische Ausgaben (wie sie die Läufer wirklich sehen)
expect('assertion failure is KILLED', mv.verdict(1, 'PASS a\nFAIL P1: x: java.lang.AssertionError: timeout\ndl tests: 45 passed, 1 failed\n', DL), 'KILLED')
expect('green suite is SURVIVED', mv.verdict(0, 'dl tests: 46 passed, 0 failed\n', DL), 'SURVIVED')
expect('javac error is INVALID, not KILLED',
       mv.verdict(1, '/tmp/x/java/de/evp/helfer/dl/DirectDownloads.java:448: error: unreachable statement\n1 error\n', DL), 'INVALID')
expect('javac error with a FAIL-looking line stays INVALID',
       mv.verdict(1, 'X.java:1: error: cannot find symbol\nFAIL something\n1 error\n', DL), 'INVALID')
expect('Maven 429 is INFRA, not KILLED',
       mv.verdict(1, 'curl: (22) The requested URL returned error: 429\nsha256sum: /c/okhttp.jar: No such file or directory\n/c/okhttp.jar: FAILED open or read\n', DL), 'INFRA')
expect('missing main class is INFRA', mv.verdict(1, 'Error: Could not find or load main class de.evp.helfer.dl.TestDirectDownloads\n', DL), 'INFRA')
expect('crash without failed check is UNCLASSIFIED', mv.verdict(1, 'Exception in thread "main" java.lang.IllegalStateException: boom\n', DL), 'UNCLASSIFIED')
expect('exit 137 (killed by OOM) without check is UNCLASSIFIED', mv.verdict(137, 'PASS a\nPASS b\n', DL), 'UNCLASSIFIED')
expect('timeout is TIMEOUT even with FAIL lines', mv.verdict(1, 'FAIL x\n', DL, timed_out=True), 'TIMEOUT')
expect('"0 failed" alone is not KILLED', mv.verdict(1, 'dl tests: 46 passed, 0 failed\n', [r'dl tests: \d+ passed, [1-9]\d* failed']), 'UNCLASSIFIED')

# Echte Prozesse über mv.run (Zeitlimit, Exit-Codes, stderr)
env = dict(os.environ)
expect('real process: compile error on stderr', mv.run(['bash', '-c', 'echo "A.java:3: error: x" >&2; echo "1 error" >&2; exit 1'], env, 30, DL)[0], 'INVALID')
expect('real process: timeout', mv.run(['bash', '-c', 'echo "FAIL early"; sleep 5'], env, 1, DL)[0], 'TIMEOUT')
expect('real process: assertion', mv.run(['bash', '-c', 'echo "FAIL t: AssertionError"; exit 1'], env, 30, DL)[0], 'KILLED')
expect('real process: survived', mv.run(['bash', '-c', 'echo "dl tests: 3 passed, 0 failed"'], env, 30, DL)[0], 'SURVIVED')

# Gesamturteil: ein einziger nicht erkannter Mutant macht den Lauf rot
t = mv.Tally('selftest')
t.add('a', 'KILLED'); t.add('b', 'INVALID', 'A.java:1: error: x')
expect('tally with an INVALID mutant fails', t.finish(), 1)
t = mv.Tally('selftest')
t.add('a', 'KILLED'); t.add('b', 'INFRA')
expect('tally with an INFRA probe fails', t.finish(), 1)
t = mv.Tally('selftest')
expect('empty tally fails', t.finish(), 1)
t = mv.Tally('selftest')
t.add('a', 'KILLED'); t.add('b', 'KILLED')
expect('all killed passes', t.finish(), 0)

print('RESULT mutation-verdict-selftest %s (%d failed)' % ('PASS' if fails == 0 else 'FAIL', fails))
sys.exit(1 if fails else 0)
