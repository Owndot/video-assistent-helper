#!/usr/bin/env python3
"""Runde 8: Umschreibung der apt-Spiegel in den CI-Werkzeugschritten (nur Infrastruktur, keine Testlogik).

Die sed-Ausdrücke werden DIREKT aus den Workflows gelesen, d. h. aus den beiden "Werkzeuge"-Schritten von
browser-tests.yml (focused-precheck, test) und aus dem Schritt "Test tools only" des Jobs privacy in build.yml. Sie
werden mit GNU sed -E auf Beispielzeilen angewandt (Spiegelliste und deb822-Format).
  * offizielle regionale EC2-Spiegel (u. a. us-east-2.ec2.archive.ubuntu.com aus Job 135655434596) und der
    Azure-Spiegel werden auf https://archive.ubuntu.com/ubuntu umgeschrieben;
  * Hauptspiegel, security.ubuntu.com, fremde Hosts (auch mit eingebettetem Spiegelnamen) und Nicht-Ubuntu-Pfade
    bleiben unverändert;
  * alle drei Schritte verwenden dieselben zwei Ausdrücke.
Liegt unter tests/gate/: der Pfadfilter von helper-channel.yml (tests/gate/**) löst damit auf demselben Commit auch
die Emulator-Matrix aus, die tools/delivery/ci_evidence.py auf dem Quell-Commit verlangt.
Aufruf: python3 -I tests/gate/test_apt_mirror_rewrite.py
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
fails = []


def check(cond, what):
    print(('PASS ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


def step_text(path, job, step):
    """Run-Text des Schritts `step` im Job `job` (reiner Textschnitt, ohne YAML-Bibliothek)."""
    s = open(os.path.join(ROOT, path), encoding='utf-8').read()
    j = re.search(r'(?m)^  %s:\n' % re.escape(job), s)
    if not j:
        return None
    nxt = re.search(r'(?m)^  [A-Za-z0-9_-]+:\n', s[j.end():])
    body = s[j.end():j.end() + nxt.start()] if nxt else s[j.end():]
    m = re.search(r'(?m)^      - name: %s\n' % re.escape(step), body)
    if not m:
        return None
    nxt = re.search(r'(?m)^      - ', body[m.end():])
    return body[m.end():m.end() + nxt.start()] if nxt else body[m.end():]


def sed_exprs(text):
    """Die s#…#…#g-Ausdrücke des einen "sudo sed -Ei"-Aufrufs (mit Zeilenfortsetzung)."""
    m = re.search(r'sudo sed -Ei((?:[^\n]*\\\n)*[^\n]*)', text or '')
    return re.findall(r"'(s#[^']+#[^']*#g)'", m.group(1)) if m else []


STEPS = [('.github/workflows/browser-tests.yml', 'focused-precheck', 'Werkzeuge'),
         ('.github/workflows/browser-tests.yml', 'test', 'Werkzeuge'),
         ('.github/workflows/build.yml', 'privacy', 'Test tools only')]
CASES = [
    # (Eingabe, erwartete Ausgabe)
    ('http://us-east-2.ec2.archive.ubuntu.com/ubuntu/\tpriority:1', 'https://archive.ubuntu.com/ubuntu/\tpriority:1'),
    ('http://us-west-2.ec2.archive.ubuntu.com/ubuntu/', 'https://archive.ubuntu.com/ubuntu/'),
    ('https://eu-central-1.ec2.archive.ubuntu.com/ubuntu', 'https://archive.ubuntu.com/ubuntu'),
    ('URIs: http://us-east-2.ec2.archive.ubuntu.com/ubuntu/', 'URIs: https://archive.ubuntu.com/ubuntu/'),
    ('http://azure.archive.ubuntu.com/ubuntu/\tpriority:2', 'https://archive.ubuntu.com/ubuntu/\tpriority:2'),
    ('URIs: http://azure.archive.ubuntu.com/ubuntu/', 'URIs: https://archive.ubuntu.com/ubuntu/'),
    # unverändert
    ('http://archive.ubuntu.com/ubuntu/\tpriority:3', 'http://archive.ubuntu.com/ubuntu/\tpriority:3'),
    ('http://security.ubuntu.com/ubuntu/', 'http://security.ubuntu.com/ubuntu/'),
    ('http://us-east-2.ec2.archive.ubuntu.com.evil.example/ubuntu/', 'http://us-east-2.ec2.archive.ubuntu.com.evil.example/ubuntu/'),
    ('http://evil.example/us-east-2.ec2.archive.ubuntu.com/ubuntu/', 'http://evil.example/us-east-2.ec2.archive.ubuntu.com/ubuntu/'),
    ('http://azure.archive.ubuntu.com.evil.example/ubuntu/', 'http://azure.archive.ubuntu.com.evil.example/ubuntu/'),
    ('http://us-east-2.ec2.archive.ubuntu.com/debian/', 'http://us-east-2.ec2.archive.ubuntu.com/debian/'),
    ('http://packages.microsoft.com/ubuntu/24.04/prod', 'http://packages.microsoft.com/ubuntu/24.04/prod'),
    ('http://ppa.launchpadcontent.net/x/y/ubuntu', 'http://ppa.launchpadcontent.net/x/y/ubuntu'),
]

found = {}
for path, job, step in STEPS:
    ex = sed_exprs(step_text(path, job, step))
    found[(path, job)] = ex
    check(len(ex) == 2, '%s %s/%s: exactly two mirror expressions %s' % (os.path.basename(path), job, step, ex))
check(len({tuple(v) for v in found.values()}) == 1, 'all three tool steps use the identical mirror rewrite')

for (path, job), ex in found.items():
    if len(ex) != 2:
        continue
    inp = '\n'.join(c[0] for c in CASES) + '\n'
    out = subprocess.run(['sed', '-E'] + sum((['-e', e] for e in ex), []), input=inp, capture_output=True, text=True, check=True).stdout
    for (src, want), got in zip(CASES, out.split('\n')):
        check(got == want, '%s %s: %r -> %r' % (os.path.basename(path), job, src, got))

print('RESULT apt-mirror-rewrite-selftest %s (%d failed)' % ('PASS' if not fails else 'FAIL', len(fails)))
sys.exit(1 if fails else 0)
