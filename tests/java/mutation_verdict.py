#!/usr/bin/env python3
"""Gemeinsame, strenge Bewertung von Mutationsproben (alle Läufer unter tests/java/*/mutation.py).

Eine Probe gilt nur dann als erkannt (KILLED), wenn die Tests nachweislich an einer Prüfung gescheitert sind. Alles
andere ist ausdrücklich KEIN Erfolg und wird getrennt gezählt:

  KILLED        Exit-Code ≠ 0 UND die Ausgabe zeigt eine fehlgeschlagene Prüfung (Läufer-spezifisches Muster)
  SURVIVED      Exit-Code 0 – die Tests haben die Schwächung nicht bemerkt
  INVALID       der Mutant lässt sich nicht übersetzen (javac-Fehler) – die Probe ist ungültig und muss korrigiert werden
  INFRA         Infrastruktur (Abhängigkeit nicht ladbar, Prüfsumme, JVM startet nicht, Port belegt …)
  TIMEOUT       Zeitlimit überschritten – kein Nachweis, dass eine Prüfung die Schwächung erkannt hat
  UNCLASSIFIED  Exit-Code ≠ 0 ohne erkennbare fehlgeschlagene Prüfung
  STALE         der zu ändernde Ausschnitt kommt nicht genau einmal vor

Ein Lauf ist nur dann bestanden, wenn JEDE Probe KILLED ist.
"""
import re
import subprocess

COMPILE = re.compile(r'(?m)(^\S+\.java:\d+: error: |^error: compilation failed|^\d+ errors?$)')
INFRA = re.compile(r'(?m)(^curl: \(\d+\)|FAILED open or read|sha256sum: WARNING|: FAILED$|Error: Could not find or load main class'
                   r'|java\.lang\.NoClassDefFoundError|java\.lang\.UnsupportedClassVersionError|No space left on device'
                   r'|java\.net\.BindException|command not found)')

ORDER = ('KILLED', 'SURVIVED', 'INVALID', 'INFRA', 'TIMEOUT', 'UNCLASSIFIED', 'STALE')


def verdict(returncode, output, killed_patterns, timed_out=False):
    """returncode: Exit-Code; output: stdout+stderr; killed_patterns: Regexe für 'Prüfung fehlgeschlagen'."""
    if timed_out:
        return 'TIMEOUT'
    if returncode == 0:
        return 'SURVIVED'
    if COMPILE.search(output):
        return 'INVALID'
    if INFRA.search(output):
        return 'INFRA'
    for p in killed_patterns:
        if re.search(p, output, re.M):
            return 'KILLED'
    return 'UNCLASSIFIED'


def run(cmd, env, timeout, killed_patterns):
    """Führt eine Probe aus und liefert (Urteil, Ausgabe)."""
    try:
        r = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as e:
        out = (e.stdout or '') if isinstance(e.stdout, str) else (e.stdout or b'').decode('utf-8', 'replace')
        return 'TIMEOUT', out
    out = (r.stdout or '') + (r.stderr or '')
    return verdict(r.returncode, out, killed_patterns), out


class Tally:
    def __init__(self, label):
        self.label = label
        self.counts = {k: 0 for k in ORDER}
        self.bad = []

    def add(self, name, v, output=''):
        self.counts[v] += 1
        print('%-12s %s' % (v.lower() if v == 'KILLED' else v, name))
        if v != 'KILLED':
            self.bad.append((name, v))
            tail = '\n'.join(output.strip().splitlines()[-6:])
            if tail and v in ('INVALID', 'INFRA', 'UNCLASSIFIED'):
                print('             ' + tail.replace('\n', '\n             '))

    def finish(self):
        total = sum(self.counts.values())
        ok = self.counts['KILLED'] == total and total > 0
        detail = ' '.join('%s=%d' % (k, self.counts[k]) for k in ORDER if self.counts[k])
        print('RESULT %s %s %d/%d killed (%s)' % (self.label, 'PASS' if ok else 'FAIL', self.counts['KILLED'], total, detail))
        return 0 if ok else 1
