#!/usr/bin/env python3
"""Selbsttest für tools/run-pkgremoval-probe.sh gegen ein nachgebildetes adb (kein Gerät, kein Emulator).

Geprüft wird: Ablehnung ohne Emulator, mit falscher API, mit schon installiertem Testpaket, mit vorhandenen
Sondendateien, mit nicht funktionierender privilegierter Abfrage und mit unbekannter Variante (PKGAB_RESULT INVALID,
Exit 2); die Variante control deinstalliert NIE; die Variante uninstall deinstalliert genau einmal genau das eigene
Test- und App-Paket, und zwar zwischen Phase pre und Phase post, eingerahmt von den Markern; beide Varianten setzen bis
auf den Paketschritt dieselbe Befehlsfolge ab; Marker in der festgelegten Reihenfolge; eine kaputte Instrumentierung
endet INVALID (nie ein anderes Ergebnis) und legt vorher alle Belege ins Log; kein sonstiger verändernder Gerätebefehl
(rm, mv, delete, content insert/update/delete, pm clear); abgelegt werden nur /data/local/tmp/bootscan-{q,f,qt}.sh;
fremde Dateien bleiben gleich; die Gerätehilfsskripte enthalten nur lesende Befehle.
Aufruf: python3 -I tests/bootscan/test_pkgremoval_script.py
"""
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(ROOT, 'tools', 'run-pkgremoval-probe.sh')
PKG = 'de.evp.helfer.channeltest'
fails, done = [], []

FAKE_ADB = r'''#!/usr/bin/env python3
import os, subprocess, sys, time
F = os.environ['FAKE_ROOT']; SC = os.environ['FAKE_SCENARIO']
a = sys.argv[1:]
open(F + '/cmds.log', 'a').write(' '.join(a) + '\n')
def tr(c): return c.replace('/storage/emulated/0', F + '/storage/emulated/0').replace('/data/local/tmp', F + '/tmp')
def log(tag, msg):
    with open(F + '/logcat.txt', 'a') as l:
        l.write('  %.3f  1644  2571 I %s: %s\n' % (time.time(), tag, msg))
if a[:1] == ['wait-for-device']: sys.exit(0)
if a[:1] == ['install']: sys.exit(0)
if a[:1] == ['uninstall']: print('Success'); sys.exit(0)
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
    if cmd.startswith('getprop ro.build.version.sdk'): print('33' if SC == 'wrong_api' else '34'); sys.exit(0)
    if cmd.startswith('pm path'): print('package:/data/app/x/base.apk' if SC == 'installed' else ''); sys.exit(0)
    if cmd.startswith('pm grant'): sys.exit(0)
    if cmd.startswith('cmd package list packages -U'): print('package:de.evp.helfer.channeltest uid:10194'); sys.exit(0)
    if cmd.startswith('log -t BOOTSCAN'): log('BOOTSCAN', a[-1].strip("'")); sys.exit(0)
    if cmd.startswith('sh /data/local/tmp/bootscan-qt.sh'):
        print('QT-BEGIN t=%d res=ms' % int(time.time() * 1000)); print('No result found.'); print('QT-END rc=0'); sys.exit(0)
    if cmd.startswith('sh /data/local/tmp/bootscan-q.sh'):
        print('Q-BEGIN'); print('Error while accessing provider:media' if SC == 'query_broken' else 'No result found.')
        print('Q-END rc=1' if SC == 'query_broken' else 'Q-END rc=0'); sys.exit(0)
    if cmd.startswith('am instrument'):
        log('MediaProvider', 'Scanned external_primary due to REASON_MOUNTED, found 18 items in 184ms, 2 inserts 1 updates 0 deletes')
        print('INSTRUMENTATION_RESULT: shortMsg=Process crashed.'); print('INSTRUMENTATION_CODE: 0'); sys.exit(0)
    if "-name '*BootScanProbe-*'" in cmd:
        if SC == 'probe_present': print('/storage/emulated/0/Download/Video-Assistent/BootScanProbe-000000000000-1.mp4')
        sys.exit(0)
    if 'LIST-END' in cmd:   # nur das Arbeitsverzeichnis umlenken; ausgegebene Pfade bleiben Gerätepfade
        sys.exit(subprocess.run(['bash', '-c', cmd.replace('cd /storage/emulated/0', 'cd ' + F + '/storage/emulated/0')]).returncode)
sys.stderr.write('fake adb: unsupported %r\n' % a); sys.exit(1)
'''

DESTRUCTIVE = re.compile(r'(^|[\s;&|(])(rm|mv|uninstall|pm clear|pm uninstall|content (insert|update|delete)|delete)\b')
ALLOWED_PUSH = re.compile(r'^push \S+ /data/local/tmp/bootscan-(q|f|qt)\.sh$')


def check(cond, what):
    done.append(what)
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


def run(variant, scenario='broken_instrumentation'):
    d = tempfile.mkdtemp()
    b = os.path.join(d, 'bin')
    os.makedirs(b)
    for name, body in (('adb', FAKE_ADB), ('sleep', '#!/bin/sh\nexec /bin/sleep 0.05\n'), ('od', '#!/bin/sh\necho " a1 b2 c3 d4 e5 f6"\n')):
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
    p = subprocess.run(['bash', SCRIPT, variant, out], capture_output=True, text=True, env=env, timeout=180)
    cmds = open(os.path.join(d, 'cmds.log')).read().splitlines() if os.path.exists(os.path.join(d, 'cmds.log')) else []
    foreign_ok = open(os.path.join(root, 'Download', 'existing.pdf'), 'rb').read() == b'foreign'
    rd = os.path.join(out, 'report-pkgremoval-' + variant)
    report = sorted(os.listdir(rd)) if os.path.isdir(rd) else []
    shutil.rmtree(d)
    return p.returncode, p.stdout + p.stderr, cmds, foreign_ok, report


def unsafe(cmds, allow_uninstall=False):
    bad = []
    for c in cmds:
        if c.startswith('push') and not ALLOWED_PUSH.match(c):
            bad.append(c)
        if c.startswith('uninstall') and not (allow_uninstall and c in ('uninstall %s.test' % PKG, 'uninstall %s' % PKG)):
            bad.append(c)
        if c.startswith('shell') and 'LIST-END' not in c and not c.startswith('shell sh /data/local/tmp/bootscan-') \
                and not c.startswith('shell log -t BOOTSCAN') and DESTRUCTIVE.search(c.split(' ', 1)[1]):
            bad.append(c)
    return bad


def steps(cmds):
    """Befehlsfolge ohne Hintergrundprozesse (Host-Abfragen, Logcat-Strom), Marker auf ihr Ereignis reduziert."""
    s = []
    for c in cmds:
        if c.startswith('shell sh /data/local/tmp/bootscan-qt.sh') or c.startswith('logcat -v epoch -b main'):
            continue   # Hintergrundprozesse: Anzahl bzw. Position zeitabhängig
        m = re.search(r'event=(\S+)', c)
        if c.startswith('shell log -t BOOTSCAN') and m:
            s.append('MARKER ' + m.group(1))
        elif c.startswith('shell am instrument'):
            s.append('INSTR ' + re.search(r'bootscanPhase (\w+)', c).group(1))
        else:
            s.append(re.sub(r'BootScanProbe-[0-9a-f]+-|/tmp/[^ ]+/', '', c))
    return s


for scenario, want in [('not_emulator', 'not an emulator'), ('wrong_api', 'API 33'), ('installed', 'already installed'),
                       ('probe_present', 'probe files already present'), ('query_broken', 'privileged query helper')]:
    for variant in ('control', 'uninstall'):
        rc, out, cmds, foreign_ok, _ = run(variant, scenario)
        bad = unsafe(cmds)
        check(rc == 2 and re.findall(r'PKGAB_RESULT (\w+)', out) == ['INVALID'] and want in out and not bad and foreign_ok
              and not any(c.startswith('shell am instrument') for c in cmds),
              'refuses %s (%s): exit %d, INVALID, nothing installed or run, no destructive command %s' % (scenario, variant, rc, bad))
rc, out, cmds, _, _ = run('both')
check(rc == 2 and 'PKGAB_RESULT INVALID' in out and not cmds, 'unknown variant refused before any adb command (exit %d)' % rc)

res = {v: run(v) for v in ('control', 'uninstall')}
for v, (rc, out, cmds, foreign_ok, report) in res.items():
    bad = unsafe(cmds, allow_uninstall=(v == 'uninstall'))
    results = re.findall(r'PKGAB_VARIANT variant=\w+ result=(\w+)', out)
    check(rc == 2 and results == ['INVALID'] and not re.search(r'result=(PASS|SUPPORTED|NOT_SUPPORTED|CLEAN)', out),
          '%s with broken instrumentation: exit %d, only INVALID %s' % (v, rc, results))
    check(all(('PKGAB_INSTR %s instr-%s' % (v, ph)) in out for ph in ('pre', 'post', 'recheck')) and ('PKGAB_LIST %s foreign-before' % v) in out
          and ('PKGAB_LIST %s foreign-after' % v) in out and ('PKGAB_HOSTQ %s QT-BEGIN' % v) in out and ('PKGAB_LOG %s' % v) in out
          and {'verdict.txt', 'logcat.txt', 'host-sampler.txt'} <= set(report), '%s: evidence printed (instr, lists, host samples, log) and report %s' % (v, report))
    check(not bad and foreign_ok, '%s: no destructive device command besides the planned package step %s, foreign files untouched' % (v, bad))
    lists = [l for l in out.splitlines() if l.startswith('PKGAB_LIST %s foreign-before ' % v)]
    check(any(l.endswith('/storage/emulated/0/Download/existing.pdf') for l in lists) and not any('/Android/' in l for l in lists)
          and lists[-1:] == ['PKGAB_LIST %s foreign-before LIST-END' % v], '%s: listing of foreign files complete (SHA-256, LIST-END), Android/ excluded' % v)

c_steps, u_steps = steps(res['control'][2]), steps(res['uninstall'][2])
c_mk = [s[7:] for s in c_steps if s.startswith('MARKER')]
u_mk = [s[7:] for s in u_steps if s.startswith('MARKER')]
check(c_mk == ['install1-begin', 'install1-end', 'update-begin', 'update-end'], 'control markers in order %s' % c_mk)
check(u_mk == ['install1-begin', 'install1-end', 'uninstall-begin', 'uninstall-end', 'install2-begin', 'install2-end'], 'uninstall markers in order %s' % u_mk)
check(not any(c.startswith('uninstall') or ' uninstall' in c for c in res['control'][2]), 'control never uninstalls')
un = [i for i, s in enumerate(u_steps) if s.startswith('uninstall')]
check([u_steps[i] for i in un] == ['uninstall %s.test' % PKG, 'uninstall %s' % PKG]
      and u_steps.index('INSTR pre') < u_steps.index('MARKER uninstall-begin') < un[0] < un[1] < u_steps.index('MARKER uninstall-end')
      < u_steps.index('MARKER install2-begin') < u_steps.index('MARKER install2-end') < u_steps.index('INSTR post'),
      'uninstall: exactly the test and app package once each, after phase pre, inside the markers, before reinstall and phase post')
for name, st in (('control', c_steps), ('uninstall', u_steps)):
    ins = [i for i, s in enumerate(st) if s.startswith('INSTR')]
    check([st[i] for i in ins] == ['INSTR pre', 'INSTR post', 'INSTR recheck'], '%s: phases pre, post, recheck once each in order' % name)
    check(sum(1 for s in st if s.startswith('install -r -t')) == 4, '%s: two installations of both APKs (4 install commands)' % name)


def common(st, a, b):
    return st[:st.index('MARKER ' + a)] + ['PACKAGE-STEP'] + st[st.index('MARKER ' + b) + 1:]


check(common(c_steps, 'update-begin', 'update-end') == common(u_steps, 'uninstall-begin', 'install2-end'),
      'both variants issue the same command sequence except the package step')
post_c = [c for c in res['control'][2] if 'bootscanPhase post' in c]
post_u = [c for c in res['uninstall'][2] if 'bootscanPhase post' in c]
check(len(post_c) == 1 and re.sub(r'bootscanRun \w+', '', post_c[0]) == re.sub(r'bootscanRun \w+', '', post_u[0])
      and 'bootscanPaceMs 500' in post_c[0] and 'bootscanMax 120' in post_c[0], 'phase post with identical, paced, capped parameters in both variants')

src = open(SCRIPT).read()
helpers = re.findall(r"<<'(QSH|FSH|QTSH)'\n(.*?)\n\1\n", src, re.S)
body = ' '.join(h[1] for h in helpers)
check(len(helpers) == 3 and not DESTRUCTIVE.search(body.replace('--where', '')) and body.count('content query') == 2
      and 'content insert' not in body and '>' not in re.sub(r'2>&1|2>/dev/null', '', body),
      'device helper scripts only read (content query, find, stat, sha256sum; no redirection into files)')
check('wait-device-ready' not in re.sub(r'#.*', '', src), 'the probe does not wait for device readiness (boot scan not waited out)')

print('PKGAB_SCRIPT_SELFTEST %s %d cases, %d failed' % ('FAIL' if fails else 'PASS', len(done), len(fails)))
sys.exit(1 if fails else 0)
