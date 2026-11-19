#!/usr/bin/env python3
"""Codex NO-GO 3 (P2): tools/run-orphan-collision-probe.sh ohne Gerät gegen ein nachgebildetes adb (Wegwerf-Verzeichnis
statt Emulator). Geprüft werden Dateisystembelege und Aufräumen:
  * Veränderung einer VERSTECKTEN fremden Datei -> DATA_LOSS;
  * zusätzliche VERSTECKTE Sondendatei -> INVALID;
  * fehlgeschlagene Vorher- bzw. Nachher-Aufnahme -> INVALID (nie DATA_LOSS/PASS), Fehler im Log;
  * abgebrochene Instrumentierung, unvollständiger Instrumentierungsbeleg, Absturz nach der Erfolgsmeldung -> INVALID;
  * Skriptabbruch mitten im Lauf -> Belege trotzdem im Log, Aufräumen trotzdem;
  * nach JEDEM Lauf: alle synthetischen Sondendateien entfernt, fremde Dateien (auch versteckte) unangetastet;
  * Positivkontrolle -> PASS.
Aufruf: python3 -I tests/delivery/test_orphan_probe_script.py [SKRIPT] (Standard: tools/run-orphan-collision-probe.sh)
"""
import hashlib
import os
import shutil
import stat
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(ROOT, 'tools', 'run-orphan-collision-probe.sh')
fails = []

FAKE_ADB = r'''#!/usr/bin/env python3
import hashlib, json, os, re, subprocess, sys
F = os.environ['FAKE_ROOT']; SC = os.environ['FAKE_SCENARIO']; API = os.environ.get('FAKE_API', '34')
LOWER = F + '/data/media/0/Download/Video-Assistent'
state_p = F + '/state.json'
st = json.load(open(state_p)) if os.path.exists(state_p) else {'snap': 0, 'logcat': ''}
def save(): json.dump(st, open(state_p, 'w'))
def tr(cmd): return cmd.replace('/data/media/0', F + '/data/media/0').replace('/data/local/tmp', F + '/tmp')
a = sys.argv[1:]
if a[:1] in (['wait-for-device'], ['root'], ['unroot'], ['uninstall']): sys.exit(0)
if a[:1] == ['install']:
    sys.exit(1 if SC == 'install_fails' else 0)
if a[:1] == ['push']:
    os.makedirs(F + '/tmp', exist_ok=True); subprocess.run(['cp', a[1], tr(a[2])], check=True); sys.exit(0)
if a[:1] == ['logcat']:
    if '-c' in a: st['logcat'] = ''; save(); sys.exit(0)
    if '-b' in a and a[a.index('-b') + 1] == 'crash':
        print('--------- beginning of crash\nE AndroidRuntime: FATAL EXCEPTION: evp-dl' if SC == 'fatal_after_success' else '', end=''); sys.exit(0)
    if '-b' in a: sys.exit(0)
    print(st['logcat'], end=''); sys.exit(0)
if a[:1] == ['shell']:
    cmd = ' '.join(a[1:])
    if cmd.startswith('getprop ro.kernel.qemu'): print('1'); sys.exit(0)
    if cmd.startswith('getprop ro.boot.qemu'): print('1'); sys.exit(0)
    if cmd.startswith('getprop ro.build.version.sdk'): print(API); sys.exit(0)
    if cmd == 'id -u': print('0'); sys.exit(0)
    if cmd.startswith('am instrument'):
        title = re.search(r"orphanTitle '([^']+)'", cmd).group(1)
        body = b'NEW-PROBE-CONTENT-' * 1000
        sha = hashlib.sha256(body).hexdigest()
        new = '%s (1).mp4' % title
        if SC != 'instr_aborted':
            open(os.path.join(LOWER, new), 'wb').write(body)
        if SC == 'hidden_extra_probe_file':
            open(os.path.join(LOWER, '.pending-1700000000-%s.mp4' % title), 'wb').write(b'partial')
        if SC == 'hidden_foreign_changed':
            open(os.path.join(LOWER, '.foreign-cache'), 'ab').write(b'changed by someone')
        row = '1000000017|<title> (1).mp4|%d|%s|<title> (1).mp4' % (len(body), sha)
        st['logcat'] = ('11-17 10:00:00.000 1 2 I MP4_TEST: ORPHAN_PROBE sdk=%s state=done reason= newSha=%s queryBefore=ok queryAfter=ok rowsBefore=[] rowsAfter=[%s]\n'
                        % (API, sha, row)).replace('<title>', title)
        save()
        C, T = 'de.evp.helfer.OrphanNameCollisionProbeTest', 'downloadOverAnOrphanedFinalName'
        head = ['INSTRUMENTATION_STATUS: class=' + C, 'INSTRUMENTATION_STATUS: current=1', 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner',
                'INSTRUMENTATION_STATUS: numtests=1', 'INSTRUMENTATION_STATUS: stream=', 'INSTRUMENTATION_STATUS: test=' + T, 'INSTRUMENTATION_STATUS_CODE: 1']
        if SC == 'instr_aborted':
            out = head + ['INSTRUMENTATION_RESULT: shortMsg=Process crashed.', 'INSTRUMENTATION_CODE: 0']
        elif SC == 'instr_incomplete':
            out = ['OK (1 test)']
        else:
            out = head + ['INSTRUMENTATION_STATUS: class=' + C, 'INSTRUMENTATION_STATUS: current=1', 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner',
                          'INSTRUMENTATION_STATUS: numtests=1', 'INSTRUMENTATION_STATUS: stream=.', 'INSTRUMENTATION_STATUS: test=' + T,
                          'INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_RESULT: stream=', '', 'Time: 2.1', '', 'OK (1 test)', '', '', 'INSTRUMENTATION_CODE: -1']
        print('\n'.join(out)); sys.exit(0)
    if 'find . -type f' in cmd and 'SNAPSHOT-END' in cmd or ('for f in *' in cmd and 'sha256sum' in cmd):
        st['snap'] += 1; save()
        if (SC == 'snapshot_fails_before' and st['snap'] == 1) or (SC == 'snapshot_fails_after' and st['snap'] == 2):
            sys.stderr.write('find: .: Input/output error\n'); sys.exit(1)
    r = subprocess.run(['bash', '-c', tr(cmd)])
    sys.exit(r.returncode)
sys.stderr.write('fake adb: unsupported %r\n' % a); sys.exit(1)
'''


def check(cond, what):
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


def run(scenario, api='34'):
    d = tempfile.mkdtemp()
    bin_ = os.path.join(d, 'bin')
    os.makedirs(bin_)
    for name, body in (('adb', FAKE_ADB), ('sleep', '#!/bin/sh\nexit 0\n')):
        p = os.path.join(bin_, name)
        open(p, 'w').write(body)
        os.chmod(p, os.stat(p).st_mode | stat.S_IEXEC)
    lower = os.path.join(d, 'data', 'media', '0', 'Download', 'Video-Assistent')
    os.makedirs(os.path.join(lower, 'sub'))
    foreign = {'Unrelated.mp4': b'foreign video', '.foreign-cache': b'hidden foreign data', 'sub/Nested.mp4': b'nested foreign'}
    for n, b in foreign.items():
        open(os.path.join(lower, n), 'wb').write(b)
    out_dir = os.path.join(d, 'out')
    os.makedirs(os.path.join(out_dir, 'apk'))
    env = dict(os.environ, PATH=bin_ + os.pathsep + os.environ['PATH'], FAKE_ROOT=d, FAKE_SCENARIO=scenario, FAKE_API=api)
    p = subprocess.run(['bash', SCRIPT, out_dir], capture_output=True, text=True, env=env, timeout=300)
    left = []
    for base, _, files in os.walk(lower):
        for f in files:
            left.append(os.path.relpath(os.path.join(base, f), lower))
    probe_left = [f for f in left if 'OrphanProbe' in f]
    foreign_ok = all(os.path.exists(os.path.join(lower, n)) for n in foreign) and \
        all(open(os.path.join(lower, n), 'rb').read() == b for n, b in foreign.items() if n != '.foreign-cache')
    shutil.rmtree(d)
    return p.returncode, p.stdout, probe_left, foreign_ok


for scenario, want_rc, want, extra in [
        ('pass', 0, 'ORPHAN_RESULT PASS', None),
        ('hidden_foreign_changed', 3, 'ORPHAN_RESULT DATA_LOSS', '.foreign-cache'),
        ('hidden_extra_probe_file', 2, 'ORPHAN_RESULT INVALID', 'exactly one new probe file'),
        ('snapshot_fails_before', 2, 'ORPHAN_RESULT INVALID', 'ORPHAN_SNAPSHOT_ERROR'),
        ('snapshot_fails_after', 2, 'ORPHAN_RESULT INVALID', 'ORPHAN_SNAPSHOT_ERROR'),
        ('instr_aborted', 2, 'ORPHAN_RESULT INVALID', 'ORPHAN_INSTR INSTRUMENTATION_RESULT: shortMsg=Process crashed.'),
        ('instr_incomplete', 2, 'ORPHAN_RESULT INVALID', 'not exactly the completed, passing test'),
        ('fatal_after_success', 2, 'ORPHAN_RESULT INVALID', 'ORPHAN_CRASH'),
        ('install_fails', None, None, 'ORPHAN_HOST before ')]:
    rc, out, probe_left, foreign_ok = run(scenario)
    masked = 'C2 OrphanProbe ' not in out.replace('C2 OrphanProbe <', '')
    if want is None:   # Skriptabbruch: kein Ergebnis, aber Belege und Aufräumen
        check(rc != 0 and 'ORPHAN_RESULT PASS' not in out and extra in out and not probe_left and foreign_ok and masked,
              'script abort (%s): exit %d, no PASS, evidence kept in the log, probe files removed %s, foreign files untouched %s'
              % (scenario, rc, probe_left, foreign_ok))
        continue
    ok = rc == want_rc and want in out and (extra is None or extra in out) and not probe_left and foreign_ok and masked
    check(ok, '%s: exit %d (want %d), %s, probe files left %s, foreign files untouched %s, title masked %s'
          % (scenario, rc, want_rc, [l for l in out.splitlines() if l.startswith('ORPHAN_RESULT')], probe_left, foreign_ok, masked))
    if scenario == 'pass':
        check(out.count('ORPHAN_HOST before ') >= 4 and 'ORPHAN_HOST before ' + hashlib.sha256(b'hidden foreign data').hexdigest() in out
              and 'sub/Nested.mp4' in out, 'pass: snapshots list hidden files and files in subfolders')

print('ORPHAN_PROBE_SCRIPT_SELFTEST %s %d cases, %d failed' % ('FAIL' if fails else 'PASS', 10, len(fails)))
sys.exit(1 if fails else 0)
