#!/usr/bin/env python3
"""Selbsttest der Providerparser (Codex-NO-GO 2, P1-3): Java-Parser der Probe (tests/bootscan/ProbeParsers.java, mit
javac/java ohne Gerät) und Python-Hostparser (tools/pkgremoval_verdict.host_samples) mit denselben Gegenbeispielen:
doppelte IDs, Lücken in den Zeilennummern, stille leere Antwort, gemischte Antwort, Rahmenfehler; dazu gleiche Prüfsumme
in Java und Python. Ohne javac (lokal) wird der Java-Teil als NICHT GEPRÜFT gemeldet, nie als bestanden.
Aufruf: python3 -I tests/bootscan/test_probe_parsers.py
"""
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import pkgremoval_verdict as V  # noqa: E402

fails, done = [], []


def check(cond, what):
    done.append(what)
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


WANT = {'ok': ('ok', 2), 'empty-explicit': ('ok', 0), 'empty-silent': ('bad:empty', -1), 'dup-id-conflict': ('bad:dup-id', -1),
        'dup-id-same': ('bad:dup-id', -1), 'rownum-gap': ('bad:rownum', -1), 'rownum-start': ('bad:rownum', -1), 'mixed': ('bad:mixed', -1),
        'frame': ('bad:frame-Q-END_rc-1', -1), 'bad-line': ('bad:line', -1), 'disk-ok': ('ok', 2), 'disk-dup-path': ('bad:dup-path', -1),
        'disk-frame': ('bad:frame', -1)}
if shutil.which('javac') and shutil.which('java'):
    with tempfile.TemporaryDirectory() as d:
        c = subprocess.run(['javac', '-d', d, os.path.join(HERE, 'ProbeParsers.java'), os.path.join(HERE, 'ProbeParsersCheck.java')],
                           capture_output=True, text=True)
        check(c.returncode == 0, 'Java parser compiles without Android (%s)' % c.stderr.strip()[-200:])
        r = subprocess.run(['java', '-cp', d, 'ProbeParsersCheck'], capture_output=True, text=True)
        got = {l.split()[1]: (l.split()[2], int(l.split()[3])) for l in r.stdout.splitlines() if l.startswith('CASE ')}
        for name, want in WANT.items():
            check(got.get(name) == want, 'Java parser %s -> %s (got %s)' % (name, want, got.get(name)))
        dig = [l.split()[1] for l in r.stdout.splitlines() if l.startswith('DIGEST ')]
        check(dig == [V.B.digest({'1000000101': ('NULL', '0', '0', '/storage/emulated/0/Download/Video-Assistent/B-2.mp4'),
                                  '1000000100': ('de.evp.helfer.channeltest', '1', '0', '/x/.pending-1-B-1.mp4')}), V.B.digest({})],
              'Java and Python digests identical %s' % dig)
else:
    print('NOT CHECKED: javac/java not available, Java parser not verified')
    fails.append('java missing')

P1 = '/storage/emulated/0/Download/Video-Assistent/BootScanProbe-a1-1.mp4'


def qt(rows, end='QT-END rc=0 t=20', begin='QT-BEGIN t=10 res=ms'):
    return '\n'.join([begin] + rows + [end]) + '\n'


def row(n, i, owner='de.evp'):
    return 'Row: %d _id=%s, owner_package_name=%s, is_pending=0, is_trashed=0, _data=%s, _display_name=x, _size=1' % (n, i, owner, P1)


for name, text, n in [('ok', qt([row(0, '11'), row(1, '12')]), 1), ('empty explicit', qt(['No result found.']), 1),
                      ('empty silent', qt([]), 0), ('duplicate id', qt([row(0, '11'), row(1, '11', 'NULL')]), 0),
                      ('row number gap', qt([row(0, '11'), row(2, '12')]), 0), ('mixed', qt(['No result found.', row(0, '11')]), 0),
                      ('rc!=0', qt([row(0, '11')], end='QT-END rc=1 t=20'), 0), ('no end time', qt([row(0, '11')], end='QT-END rc=0'), 0)]:
    notes, invalid = [], []
    got = V.host_samples(text, notes, invalid)
    check(len(got) == n and not invalid, 'host parser %s -> %d usable sample(s) (got %d)' % (name, n, len(got)))
notes, invalid = [], []
V.host_samples(qt([row(0, '11')], end='QT-END rc=0 t=5'), notes, invalid)
check(bool(invalid), 'host parser: query ending before it began is INVALID, not silently dropped')

print('PROBE_PARSERS_SELFTEST %s %d cases, %d failed' % ('FAIL' if fails else 'PASS', len(done), len(fails)))
sys.exit(1 if fails else 0)
