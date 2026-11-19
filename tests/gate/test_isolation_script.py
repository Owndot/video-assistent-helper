#!/usr/bin/env python3
"""Runde 5 (Testisolation): tools/run-channel-isolation.sh ohne Gerät gegen ein nachgebildetes adb.

Geprüft (jede Abweichung = Fehlschlag des Selbsttests):
  * Frischenachweis fail-closed: Testpaket bzw. Testpaket .test vorhanden (auch "deinstalliert mit Daten"), zwei Geräte,
    Gerät offline, Paketliste nicht lesbar oder ohne "package:android", ungültige Boot-ID, Protokollstrom ohne
    Startmarkierung -> ISOLATION-FAILED, Exit 2, KEINE Installation;
  * Zeitfenster: Verwaisung der Testpakete vor der Installation oder während der Messung -> Rückgabe 1, gezählt und als
    ISOLATION-OWNER mit Fenster belegt; nach der eigenen Deinstallation -> dokumentiert, Rückgabe 0 (keine pauschale
    Null-Bedingung); fremde Pakete und owner_package_changed -> dokumentiert, Rückgabe 0;
  * Strom bricht ab oder Markierung fehlt -> stream=incomplete, Rückgabe 1;
  * Ausgabe genau im Format, das tools/device_gate.py erwartet (dessen Regex), und vom Gate akzeptierte Zählung.
Aufruf: python3 -I tests/gate/test_isolation_script.py
"""
import os
import stat
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import device_gate as G  # noqa: E402

LIB = os.path.join(ROOT, 'tools', 'run-channel-isolation.sh')
fails = []

# Nachgebildetes adb: Gerätelog als Datei (Epoche, Format "logcat -v epoch"), Gerätezeit = Zähler, Strom folgt der Datei.
FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, sys, time
F = os.environ['FAKE_ROOT']; SC = os.environ['FAKE_SCENARIO']
LOG = F + '/device.log'; ST = F + '/state.json'
st = json.load(open(ST)) if os.path.exists(ST) else {'t': 1763370000.0, 'installs': 0}
def save(): json.dump(st, open(ST, 'w'))
def emit(tag, msg, lvl='D'):
    st['t'] += 0.25
    with open(LOG, 'a') as f:
        f.write('%.3f  1001  1002 %s %s: %s\n' % (st['t'], lvl, tag, msg))
    save()
def orphan(pkg, n=22): emit('MediaProvider', 'Orphaned %d items belonging to %s on external_primary' % (n, pkg))
def changed(): emit('MediaProvider', 'Invalidating LocalCallingIdentity cache for package null. Reason: owner_package_changed:/storage/emulated/0/Download/Video-Assistent/C2 x.mp4')
a = sys.argv[1:]
if a == ['devices']:
    print('List of devices attached')
    if SC == 'two_devices': print('emulator-5554\tdevice\nemulator-5556\tdevice')
    elif SC == 'offline': print('emulator-5554\toffline')
    else: print('emulator-5554\tdevice')
    print(''); sys.exit(0)
if a[:1] == ['logcat']:
    if SC == 'stream_never_starts': sys.exit(1)
    pos, n = 0, 0
    while True:
        if os.path.exists(LOG):
            with open(LOG) as f:
                f.seek(pos); chunk = f.read(); pos = f.tell()
            for l in chunk.splitlines():
                if ' MediaProvider: ' in l or ' ISOLATION: ' in l:
                    sys.stdout.write(l + '\r\n'); sys.stdout.flush()
                    n += 1
                    if SC == 'stream_dies' and 'event=install' in l: sys.exit(0)
        time.sleep(0.02)
if a[:1] == ['install']:
    st['installs'] += 1; save()
    if SC == 'stale_orphan_measurement' and st['installs'] == 1: orphan('de.evp.helfer.channeltest', 3)
    if SC == 'foreign_and_changed' and st['installs'] == 1: orphan('com.example.other', 5); changed()
    sys.exit(0)
if a[:1] == ['uninstall']:
    if SC in ('own_uninstall_orphan', 'foreign_and_changed') and a[1] == 'de.evp.helfer.channeltest':
        changed(); orphan('de.evp.helfer.channeltest', 19)
    sys.exit(0)
if a[:1] == ['shell']:
    cmd = ' '.join(a[1:])
    if cmd == 'getprop ro.build.version.sdk': print('34'); sys.exit(0)
    if cmd == 'cat /proc/sys/kernel/random/boot_id':
        print('not-a-boot-id' if SC == 'bad_boot' else '0d6f2a6e-3c1b-4b8e-9a51-5f7c2d9e1a03'); sys.exit(0)
    if cmd == 'pm list packages -u':
        if SC == 'pm_fails': print('Error: could not access the Package Manager'); sys.exit(255)
        pk = ['android', 'com.android.providers.media.module']
        if SC == 'empty_list': pk = []
        if SC == 'app_present': pk.append('de.evp.helfer.channeltest')
        if SC == 'test_present_keep_data': pk.append('de.evp.helfer.channeltest.test')
        print('\r\n'.join('package:' + p for p in pk)); sys.exit(0)
    if a[1:4] == ['log', '-t', 'ISOLATION']:
        msg = a[-1]
        if SC == 'end_marker_lost' and 'event=end' in msg: sys.exit(0)
        if SC == 'boot_orphan' and 'event=begin' in msg: orphan('de.evp.helfer.channeltest', 4)
        emit('ISOLATION', msg, 'I'); sys.exit(0)
    print('unexpected shell: ' + cmd, file=sys.stderr); sys.exit(1)
print('unexpected adb: %r' % a, file=sys.stderr); sys.exit(1)
'''

DRIVER = r'''set -uo pipefail
. "$LIB"
iso_begin "$PHASE" "$REP"
trap iso_stop EXIT
iso_mark install
adb install -r -t x.apk > /dev/null
adb install -r -t y.apk > /dev/null
iso_mark uninstall
adb uninstall de.evp.helfer.channeltest.test > /dev/null
adb uninstall de.evp.helfer.channeltest > /dev/null
iso_end; rc=$?
echo "DRIVER-END rc=$rc"
'''


def run(scenario, phase='main', strict=False):
    d = tempfile.mkdtemp()
    bin_ = os.path.join(d, 'bin')
    os.makedirs(bin_)
    adb = os.path.join(bin_, 'adb')
    open(adb, 'w').write(FAKE_ADB)
    os.chmod(adb, os.stat(adb).st_mode | stat.S_IEXEC)
    env = dict(os.environ, PATH=bin_ + os.pathsep + os.environ['PATH'], FAKE_ROOT=d, FAKE_SCENARIO=scenario, LIB=LIB, PHASE=phase,
               REP=os.path.join(d, 'report'))
    drv = DRIVER.replace('set -uo pipefail', 'set -euo pipefail').replace('iso_end; rc=$?', 'rc=0; iso_end || rc=$?') if strict else DRIVER
    p = subprocess.run(['bash', '-c', drv], env=env, capture_output=True, text=True, timeout=120)
    st = open(os.path.join(d, 'state.json')).read() if os.path.exists(os.path.join(d, 'state.json')) else '{}'
    return p.returncode, p.stdout.splitlines(), '"installs": 0' in st or st == '{}'


def check(cond, what, detail=''):
    print(('PASS ' if cond else 'FAIL ') + what + ('' if cond else '  ' + detail))
    if not cond:
        fails.append(what)


def summary(lines):
    s = [G.ISO_SUMMARY.match(l) for l in lines if l.startswith('ISOLATION-SUMMARY')]
    return s[0] if len(s) == 1 and s[0] else None


# 1) Vorbedingungen: Abbruch vor jeder Installation
for sc, reason in [('app_present', 'package-present:de.evp.helfer.channeltest'), ('test_present_keep_data', 'package-present:de.evp.helfer.channeltest.test'),
                   ('two_devices', 'device-count'), ('offline', 'device-count'), ('pm_fails', 'package-list'), ('empty_list', 'package-list'),
                   ('bad_boot', 'boot-id'), ('stream_never_starts', 'stream-begin')]:
    rc, out, no_install = run(sc)
    check(rc == 2 and out == ['ISOLATION-FAILED phase=main reason=%s' % reason] and no_install,
          'precondition %s -> ISOLATION-FAILED %s, exit 2, nothing installed' % (sc, reason), 'rc=%d out=%r installs-none=%s' % (rc, out[:3], no_install))

# 2) sauberer Lauf
rc, out, _ = run('clean')
m = summary(out)
check(rc == 0 and 'DRIVER-END rc=0' in out and G.ISO_FRESH.match(out[0]) and m and m.group('stream') == 'complete'
      and [m.group(k) for k in ('orphan_before', 'orphan_measurement', 'orphan_after')] == ['0', '0', '0'],
      'clean phase: FRESH first, complete stream, no owner events, rc 0', repr(out))
check(not [l for l in out if l.startswith('ISOLATION-OWNER')], 'clean phase: no ISOLATION-OWNER lines')

# 3) eigene Deinstallation verwaist Zeilen: dokumentiert, kein Fehler
rc, out, _ = run('own_uninstall_orphan')
m = summary(out)
own = [l for l in out if l.startswith('ISOLATION-OWNER')]
check('DRIVER-END rc=0' in out and m and m.group('orphan_after') == '1' and m.group('owner_changed_after') == '1'
      and m.group('orphan_measurement') == '0' and any('window=after-uninstall event=orphaned pkg=de.evp.helfer.channeltest count=19' in l for l in own)
      and all(G.ISO_OWNER.match(l) for l in own),
      'orphaning after the own uninstall is documented (window after-uninstall) and allowed', repr(out))

# 4) verspätete Paketentfernung während der Messung: Isolationsbruch
rc, out, _ = run('stale_orphan_measurement')
m = summary(out)
check('DRIVER-END rc=1' in out and m and m.group('orphan_measurement') == '1'
      and any('window=measurement event=orphaned pkg=de.evp.helfer.channeltest count=3' in l for l in out),
      'orphaning of the test package during the measurement -> rc 1, counted and documented', repr(out))

# 5) Verwaisung schon vor der Installation (seit Boot im Strom): Isolationsbruch
rc, out, _ = run('boot_orphan')
m = summary(out)
check('DRIVER-END rc=1' in out and m and m.group('orphan_before') == '1'
      and any('window=before-install event=orphaned pkg=de.evp.helfer.channeltest count=4' in l for l in out),
      'orphaning of the test package before the installation -> rc 1', repr(out))

# 6) fremdes Paket und owner_package_changed während der Messung: dokumentiert, kein Fehler
rc, out, _ = run('foreign_and_changed')
m = summary(out)
check('DRIVER-END rc=0' in out and m and m.group('orphan_measurement') == '0' and m.group('owner_changed_measurement') == '1'
      and any('window=measurement event=orphaned pkg=com.example.other count=5' in l for l in out),
      'foreign package orphaned and owner changes are documented, not counted against the test package', repr(out))

# 7) Strom unvollständig
for sc in ('stream_dies', 'end_marker_lost'):
    rc, out, _ = run(sc)
    m = summary(out)
    check('DRIVER-END rc=1' in out and m and m.group('stream') == 'incomplete', '%s -> stream=incomplete, rc 1' % sc, repr(out))

# 8) Phase im Beleg
rc, out, _ = run('clean', 'repeat')
check(out[0].startswith('ISOLATION-FRESH phase=repeat ') and summary(out) and summary(out).group('phase') == 'repeat', 'phase name in the evidence')
rc, out, _ = run('clean', 'other')
check(rc == 2 and out == ['ISOLATION-FAILED phase=other reason=bad-phase'], 'unknown phase refused')

# 8b) unter "set -euo pipefail" (wie tools/run-channel-publication-regression.sh): kein vorzeitiger Abbruch der Bibliothek
for sc, want in (('clean', 0), ('stale_orphan_measurement', 1), ('end_marker_lost', 1), ('own_uninstall_orphan', 0)):
    rc, out, _ = run(sc, 'mutant', strict=True)
    check(rc == 0 and 'DRIVER-END rc=%d' % want in out and summary(out) is not None, 'set -e: %s -> library returns %d, script continues' % (sc, want), repr(out[-2:]))
rc, out, no_install = run('app_present', 'mutant', strict=True)
check(rc == 2 and no_install, 'set -e: package present -> exit 2 before any installation')

# 9) Quellstand: jede Phase prüft die Frische VOR der ersten Installation und belegt Installation/Deinstallation
for script, phase in [('run-channel-publication-regression.sh', 'mutant'), ('run-channel-instrumentation.sh', 'main'), ('run-flaky-repeat.sh', 'repeat')]:
    src = open(os.path.join(ROOT, 'tools', script), encoding='utf-8').read()
    lines = [l.strip() for l in src.splitlines() if not l.strip().startswith('#')]
    def first(pred):
        return next((i for i, l in enumerate(lines) if pred(l)), None)
    i_src = first(lambda l: 'run-channel-isolation.sh' in l and l.startswith('.'))
    i_begin = first(lambda l: l == 'iso_begin %s "$%s"' % (phase, 'OUT/report' if phase == 'mutant' else 'REPORT'))
    i_mark = first(lambda l: l == 'iso_mark install')
    i_inst = first(lambda l: l.startswith('adb install'))
    i_un_mark = first(lambda l: l == 'iso_mark uninstall')
    i_un = first(lambda l: l.startswith('adb uninstall') or l == 'cleanup')
    i_end = first(lambda l: l.startswith('iso_end'))
    ok = None not in (i_src, i_begin, i_mark, i_inst, i_un_mark, i_un, i_end) and i_src < i_begin < i_mark < i_inst < i_un_mark < i_un < i_end
    ok = ok and src.count('iso_begin ') == 1 and src.count('iso_mark install') == 1 and src.count('iso_mark uninstall') == 1
    check(ok, '%s: source, iso_begin, install mark, install, uninstall mark, uninstall, iso_end in this order (%s)' % (script, phase),
          repr((i_src, i_begin, i_mark, i_inst, i_un_mark, i_un, i_end)))

print('RESULT isolation-script-selftest %s (%d failed)' % ('PASS' if not fails else 'FAIL', len(fails)))
sys.exit(1 if fails else 0)
