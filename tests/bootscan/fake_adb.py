#!/usr/bin/env python3
"""Nachgebildetes adb für tests/bootscan/test_pkgremoval_script.py (kein Gerät). Zustand: Gerätesimulator
tests/bootscan/pkgab_sim.Device, gespeichert in $FAKE_ROOT/dev.pkl; jeder Aufruf hält eine Dateisperre, also laufen
nebenläufige Aufrufe (Host-Abfragen, Logcat-Strom) nacheinander und deterministisch.

Logcat-Strom ("adb logcat -v epoch …" ohne -d): schreibt unter der Sperre den bisherigen Log in seine Ausgabedatei und
meldet diese Datei am Simulator an; jede spätere Logzeile wird unter derselben Sperre sofort dorthin geschrieben (keine
Verzögerung, kein Verlust). Bei SIGTERM meldet er sich ab.

Szenario: JSON in $FAKE_SCENARIO: device (emulator|phone), api, installed, probe_present, query_broken, find_fails,
instr_broken, sim (Szenario des Simulators).
"""
import fcntl
import json
import os
import pickle
import shlex
import signal
import subprocess
import sys
import time

F = os.environ['FAKE_ROOT']
SC = json.loads(os.environ['FAKE_SCENARIO'])
sys.path.insert(0, os.environ['FAKE_SIM_DIR'])
import pkgab_sim as S  # noqa: E402

a = sys.argv[1:]
lock = open(os.path.join(F, 'lock'), 'w')


def load():
    p = os.path.join(F, 'dev.pkl')
    if os.path.exists(p):
        return pickle.load(open(p, 'rb'))
    dev = S.Device(SC.get('sim'))
    if SC.get('installed'):
        dev.installed[S.PKG] = True
    return dev


def save(dev):
    pickle.dump(dev, open(os.path.join(F, 'dev.pkl.tmp'), 'wb'))
    os.replace(os.path.join(F, 'dev.pkl.tmp'), os.path.join(F, 'dev.pkl'))


def out(text, rc=0):
    if text:
        print(text)
    sys.exit(rc)


fcntl.flock(lock, fcntl.LOCK_EX)
open(os.path.join(F, 'cmds.log'), 'a').write(' '.join(a) + '\n')
dev = load()

if a[:1] == ['logcat'] and '-d' not in a and '-G' not in a:      # Strom
    target = os.readlink('/proc/self/fd/1')
    with open(target, 'a') as f:
        f.write(''.join(l + '\n' for l in dev.log))
    dev.stream = target
    save(dev)
    fcntl.flock(lock, fcntl.LOCK_UN)

    def stop(*_):
        fcntl.flock(lock, fcntl.LOCK_EX)
        d = load()
        d.stream = None
        save(d)
        sys.exit(0)
    signal.signal(signal.SIGTERM, stop)
    while True:
        time.sleep(3600)


def finish(text='', rc=0):
    save(dev)
    out(text, rc)


if a[:1] == ['wait-for-device']:
    finish()
if a[:1] == ['logcat']:
    finish('\n'.join(dev.log) if '-d' in a else '')
if a[:1] == ['push']:
    os.makedirs(os.path.join(F, 'tmp'), exist_ok=True)
    subprocess.run(['cp', a[1], a[2].replace('/data/local/tmp', os.path.join(F, 'tmp'))], check=True)
    finish()
if a[:1] == ['install']:
    pkg = S.TEST_PKG if 'androidTest' in a[-1] else S.PKG
    rc, text = dev.install(pkg)
    finish(text, rc)
if a[:1] == ['uninstall']:
    rc, text = dev.uninstall(a[1])
    finish(text, rc)
if a[:1] != ['shell']:
    finish('fake adb: unsupported %r' % a, 1)

cmd = ' '.join(a[1:])
if cmd.startswith('getprop ro.kernel.qemu') or cmd.startswith('getprop ro.boot.qemu'):
    finish('1' if SC.get('device', 'emulator') == 'emulator' else '0')
if cmd.startswith('getprop ro.build.version.sdk'):
    finish(str(SC.get('api', 34)))
if cmd.startswith('pm path '):
    pkg = cmd.split()[2].rstrip(';')
    p = dev.pm_path(pkg)
    if 'PM-RC' in cmd:   # "pm path X; echo PM-RC=$?": adb liefert den Status des letzten Befehls (echo) = 0
        finish('\n'.join(x for x in (p, 'PM-RC=%d' % (0 if p else 1)) if x), 0)
    finish(p, 0 if p else 1)
if cmd.startswith('cmd package list packages -U'):
    finish(dev.list_packages(S.PKG))
if cmd.startswith('cmd package list packages '):
    lines = [l.split(' uid:')[0] for l in dev.list_packages(S.PKG).splitlines()]
    finish('\n'.join(lines + (['LIST-RC=0'] if 'LIST-RC' in cmd else [])))
if cmd.startswith('pm grant'):
    finish()
if cmd.startswith('log -t BOOTSCAN'):
    msg = a[-1].strip("'")
    f = dict(t.split('=', 1) for t in msg.split() if '=' in t)
    extra = msg.split(' event=%s' % f.get('event'), 1)[-1].strip()
    dev.marker(f.get('run'), f.get('variant'), f.get('event'), extra)
    finish()
if cmd.startswith('sh /data/local/tmp/bootscan-qt.sh'):
    finish(dev.qt(a[-1], tick=False))
if cmd.startswith('sh /data/local/tmp/bootscan-q.sh'):
    if SC.get('query_broken'):
        finish('Q-BEGIN\nError while accessing provider:media\nQ-END rc=1')
    finish('Q-BEGIN\nNo result found.\nQ-END rc=0')
if cmd.startswith('am instrument'):
    words = shlex.split(cmd)
    args = {words[i + 1]: words[i + 2] for i, w in enumerate(words) if w == '-e' and i + 2 < len(words)}
    if SC.get('instr_broken'):
        finish('INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0')
    finish(dev.instrument(args['bootscanRun'], args['bootscanPhase'], args).rstrip('\n'))
if "-name '*BootScanProbe-*'" in cmd:
    lines = ['/storage/emulated/0/Download/Video-Assistent/BootScanProbe-000000000000-1.mp4'] if SC.get('probe_present') else []
    finish('\n'.join(lines + (['FIND-RC=0'] if 'FIND-RC' in cmd else [])))
if 'LIST-END' in cmd:   # nur das Arbeitsverzeichnis umlenken; ausgegebene Pfade bleiben Gerätepfade
    c = cmd.replace('cd /storage/emulated/0', 'cd ' + os.path.join(F, 'storage', 'emulated', '0'))
    if SC.get('find_fails'):
        c = c.replace('find . ', 'find ./does-not-exist ')
    save(dev)
    fcntl.flock(lock, fcntl.LOCK_UN)
    sys.exit(subprocess.run(['bash', '-c', c]).returncode)
finish('fake adb: unsupported %r' % a, 1)
