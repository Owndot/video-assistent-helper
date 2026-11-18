#!/usr/bin/env python3
"""Selbsttest für tools/run-bootscan-probe.sh gegen ein nachgebildetes adb (kein Gerät, kein Emulator).

Geprüft wird: Ablehnung ohne Emulator, mit falscher API, mit schon installiertem Testpaket, mit vorhandenen
Sondendateien und mit nicht funktionierender privilegierter Abfrage (jeweils BOOTSCAN_RESULT INVALID, Exit 2);
ein Lauf mit kaputter Instrumentierung endet INVALID, nie mit einem anderen Ergebnis, und legt vorher alle Belege ins
Log; in KEINEM Fall setzt das Skript einen verändernden Gerätebefehl ab (rm, delete, uninstall, content insert/update/
delete, pm clear, mv) und nie eine Datei außerhalb von /data/local/tmp/bootscan-*.sh ab; fremde Dateien bleiben gleich;
die Gerätehilfsskripte enthalten nur lesende Befehle.
Aufruf: python3 -I tests/bootscan/test_bootscan_script.py
"""
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(ROOT, 'tools', 'run-bootscan-probe.sh')
fails = []

FAKE_ADB = r'''#!/usr/bin/env python3
import os, subprocess, sys
F = os.environ['FAKE_ROOT']; SC = os.environ['FAKE_SCENARIO']
a = sys.argv[1:]
open(F + '/cmds.log', 'a').write(' '.join(a) + '\n')
def tr(c): return c.replace('/storage/emulated/0', F + '/storage/emulated/0').replace('/data/local/tmp', F + '/tmp')
if a[:1] in (['wait-for-device'],): sys.exit(0)
if a[:1] == ['install']: sys.exit(0)
if a[:1] == ['push']:
    os.makedirs(F + '/tmp', exist_ok=True); subprocess.run(['cp', a[1], tr(a[2])], check=True); sys.exit(0)
if a[:1] == ['logcat']:
    if '-G' in a: sys.exit(0)
    if '-d' in a: print(open(F + '/logcat.txt').read(), end=''); sys.exit(0)
    os.execvp('tail', ['tail', '-n', '+1', '-f', F + '/logcat.txt'])
if a[:1] == ['shell']:
    cmd = ' '.join(a[1:])
    if cmd.startswith('getprop ro.kernel.qemu') or cmd.startswith('getprop ro.boot.qemu'):
        print('0' if SC == 'not_emulator' else '1'); sys.exit(0)
    if cmd.startswith('getprop ro.build.version.sdk'): print('29' if SC == 'wrong_api' else '34'); sys.exit(0)
    if cmd.startswith('pm path'): print('package:/data/app/x/base.apk' if SC == 'installed' else ''); sys.exit(0)
    if cmd.startswith('pm grant'): sys.exit(0)
    if cmd.startswith('sh /data/local/tmp/bootscan-q.sh'):
        print('Q-BEGIN'); print('Error while accessing provider:media' if SC == 'query_broken' else 'No result found.')
        print('Q-END rc=1' if SC == 'query_broken' else 'Q-END rc=0'); sys.exit(0)
    if cmd.startswith('am instrument'):
        with open(F + '/logcat.txt', 'a') as l:
            l.write('  1763400030.000  1644  2571 I MediaProvider: Scanned external_primary due to REASON_MOUNTED, found 18 items in 184ms, 2 inserts 1 updates 0 deletes\n')
        print('INSTRUMENTATION_RESULT: shortMsg=Process crashed.'); print('INSTRUMENTATION_CODE: 0'); sys.exit(0)
    if "-name '*BootScanProbe-*'" in cmd:
        if SC == 'probe_present': print('/storage/emulated/0/Download/Video-Assistent/BootScanProbe-000000000000-1.mp4')
        sys.exit(0)
    if 'LIST-END' in cmd:   # nur das Arbeitsverzeichnis umlenken; ausgegebene Pfade bleiben Gerätepfade
        sys.exit(subprocess.run(['bash', '-c', cmd.replace('cd /storage/emulated/0', 'cd ' + F + '/storage/emulated/0')]).returncode)
sys.stderr.write('fake adb: unsupported %r\n' % a); sys.exit(1)
'''

DESTRUCTIVE = re.compile(r'(^|[\s;&|(])(rm|mv|uninstall|pm clear|content (insert|update|delete)|delete)\b')


def check(cond, what):
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


def run(scenario):
    d = tempfile.mkdtemp()
    b = os.path.join(d, 'bin')
    os.makedirs(b)
    for name, body in (('adb', FAKE_ADB), ('sleep', '#!/bin/sh\nexit 0\n'), ('od', '#!/bin/sh\necho " a1 b2 c3 d4 e5 f6"\n')):
        p = os.path.join(b, name)
        open(p, 'w').write(body)
        os.chmod(p, os.stat(p).st_mode | stat.S_IEXEC)
    root = os.path.join(d, 'storage', 'emulated', '0')
    os.makedirs(os.path.join(root, 'Download'))
    os.makedirs(os.path.join(root, 'Android', 'data'))
    open(os.path.join(root, 'Download', 'existing.pdf'), 'wb').write(b'foreign')
    open(os.path.join(root, 'Android', 'data', 'skip.bin'), 'wb').write(b'x')
    open(os.path.join(d, 'logcat.txt'), 'w').close()
    out = os.path.join(d, 'out')
    os.makedirs(os.path.join(out, 'apk'))
    env = dict(os.environ, PATH=b + os.pathsep + os.environ['PATH'], FAKE_ROOT=d, FAKE_SCENARIO=scenario)
    p = subprocess.run(['bash', SCRIPT, out], capture_output=True, text=True, env=env, timeout=120)
    cmds = open(os.path.join(d, 'cmds.log')).read().splitlines() if os.path.exists(os.path.join(d, 'cmds.log')) else []
    foreign_ok = open(os.path.join(root, 'Download', 'existing.pdf'), 'rb').read() == b'foreign'
    report = sorted(os.listdir(os.path.join(out, 'report-bootscan'))) if os.path.isdir(os.path.join(out, 'report-bootscan')) else []
    shutil.rmtree(d)
    return p.returncode, p.stdout + p.stderr, cmds, foreign_ok, report


def safe(cmds):
    bad = [c for c in cmds if c.startswith('shell') and DESTRUCTIVE.search(c.split(' ', 1)[1]) and 'LIST-END' not in c
           and not c.startswith('shell sh /data/local/tmp/bootscan-')]
    pushes = [c for c in cmds if c.startswith('push')]
    pushes_ok = all(re.match(r'^push \S+ /data/local/tmp/bootscan-[qf]\.sh$', c) for c in pushes)
    return not bad and pushes_ok, bad + [c for c in pushes if not re.match(r'^push \S+ /data/local/tmp/bootscan-[qf]\.sh$', c)]


for scenario, want in [('not_emulator', 'not an emulator'), ('wrong_api', 'API 29'), ('installed', 'already installed'),
                       ('probe_present', 'probe files already present'), ('query_broken', 'privileged query helper')]:
    rc, out, cmds, foreign_ok, report = run(scenario)
    ok_safe, bad = safe(cmds)
    check(rc == 2 and 'BOOTSCAN_RESULT INVALID' in out and want in out and ok_safe and foreign_ok,
          'refuses %s: exit %d, INVALID, no destructive device command %s, foreign files untouched %s' % (scenario, rc, bad, foreign_ok))

rc, out, cmds, foreign_ok, report = run('broken_instrumentation')
ok_safe, bad = safe(cmds)
results = re.findall(r'BOOTSCAN_RESULT (\w+)', out)
check(rc == 2 and results == ['INVALID'] and 'BOOTSCAN_INSTR instr-during' in out and 'BOOTSCAN_LIST foreign-before' in out
      and 'verdict.txt' in report and 'logcat.txt' in report and ok_safe and foreign_ok,
      'broken instrumentation: exit %d, only INVALID %s, evidence in the log and report %s, no destructive command %s' % (rc, results, report, bad))
check(any(c.startswith('shell am instrument') and 'bootscanPhase recheck' in c for c in cmds),
      'all three phases are run even when the first one breaks (recheck issued)')
lists = [l for l in out.splitlines() if l.startswith('BOOTSCAN_LIST ')]
check(any(l.endswith('/storage/emulated/0/Download/existing.pdf') for l in lists) and not any('/Android/' in l for l in lists)
      and any(l == 'BOOTSCAN_LIST foreign-before LIST-END' for l in lists),
      'listing of foreign files is complete (existing file with SHA-256, LIST-END) and excludes Android/')
check('instrumentation of phase during is not exactly the completed probe test' in out, 'INVALID names the broken instrumentation as reason')

src = open(SCRIPT).read()
helpers = re.findall(r"<<'(QSH|FSH)'\n(.*?)\n\1\n", src, re.S)
cmds_in_helpers = ' '.join(h[1] for h in helpers)
check(len(helpers) == 2 and not DESTRUCTIVE.search(cmds_in_helpers.replace('--where', '')) and 'content query' in cmds_in_helpers
      and 'content insert' not in cmds_in_helpers and '>' not in re.sub(r'2>&1|2>/dev/null', '', cmds_in_helpers),
      'device helper scripts only read (content query, find, stat, sha256sum; no redirection into files)')
check('wait-device-ready' not in re.sub(r'#.*', '', src), 'the probe does not wait for device readiness beyond install (boot scan not waited out)')

print('BOOTSCAN_SCRIPT_SELFTEST %s %d cases, %d failed' % ('FAIL' if fails else 'PASS', 11, len(fails)))
sys.exit(1 if fails else 0)
