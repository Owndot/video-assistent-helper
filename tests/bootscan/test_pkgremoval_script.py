#!/usr/bin/env python3
"""Selbsttest für tools/run-pkgremoval-probe.sh gegen ein nachgebildetes adb mit Gerätesimulator (kein Gerät).

Geprüft wird:
- Ablehnung ohne Emulator, mit falscher API, mit installiertem Testpaket, mit vorhandenen Sondendateien (measurement=REFUSED,
  Exit 2) OHNE jede Logcat-Erfassung (keine persönlichen Logs auf einem abgelehnten Gerät); defekte privilegierte Abfrage
  nach Beginn der Erfassung: Abbruch, Beobachtungsliste INCOMPLETE mit HOST_ABORT;
- vollständige synthetische Hostabläufe (control, uninstall mit verzögertem bzw. nur erstem Verwaisen): Exit 0,
  measurement=COMPLETE, overlap=PRESENT, Beobachtungsliste mit Rohbezügen, KEIN Kausalurteil; zweimal ausgeführt gleiche
  Beobachtungen (deterministisch, ohne Warten auf echte Zeit);
- jede Paketoperation mit op-begin/op-end, Rückgabecode und Ergebnis; Deinstallation nur von Test- und App-Paket, genau
  einmal, nach Phase pre, vor der Neuinstallation; Fehler EINES der beiden "adb uninstall" (auch mit rc=0 und "Failure"),
  sowie "Success" bei weiter installiertem Paket -> Abbruch, Exit 2, PKGOP_FAILED bzw. ABSENCE_NOT_PROVEN in der
  Beobachtungsliste, keine Neuinstallation, Rohbelege erhalten;
- fehlgeschlagenes find ergibt keine gültige Dateiliste (kein LIST-END) -> foreign_lists unvollständig;
- beide Varianten: gleiche Befehlsfolge bis auf den Paketschritt; Marker in Reihenfolge; Synchronisation des
  Logcat-Stroms über Marker statt Warten;
- kaputte Instrumentierung -> INCOMPLETE mit allen Belegen; kein sonstiger verändernder Gerätebefehl; nur
  /data/local/tmp/bootscan-{q,f,qt}.sh abgelegt; fremde Dateien unverändert; Hilfsskripte nur lesend.
Aufruf: python3 -I tests/bootscan/test_pkgremoval_script.py [--script PFAD]  (PFAD: anderes Hostskript, z. B. alter Stand)
"""
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SCRIPT = sys.argv[sys.argv.index('--script') + 1] if '--script' in sys.argv else os.path.join(ROOT, 'tools', 'run-pkgremoval-probe.sh')
PKG = 'de.evp.helfer.channeltest'
fails, done = [], []
DESTRUCTIVE = re.compile(r'(^|[\s;&|(])(rm|mv|uninstall|pm clear|pm uninstall|content (insert|update|delete)|delete)\b')
ALLOWED_PUSH = re.compile(r'^push \S+ /data/local/tmp/bootscan-(q|f|qt)\.sh$')
SUPPORTED = {'orphan': 'delayed-all', 'scan_after_pkg_end': 2000}
NOT_SUPPORTED = {'orphan': 'delayed-install1', 'scan_after_pkg_end': 2000}


def check(cond, what):
    done.append(what)
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


def run(variant, **sc):
    d = tempfile.mkdtemp()
    b = os.path.join(d, 'bin')
    os.makedirs(b)
    for name, body in (('adb', '#!/bin/sh\nexec "%s" -I "%s" "$@"\n' % (sys.executable, os.path.join(HERE, 'fake_adb.py'))),
                       ('sleep', '#!/bin/sh\nexit 0\n'),   # Geräteuhr ist virtuell; Host-Abfragen verschieben sie nicht
                       ('od', '#!/bin/sh\necho " a1 b2 c3 d4 e5 f6"\n')):
        p = os.path.join(b, name)
        open(p, 'w').write(body)
        os.chmod(p, os.stat(p).st_mode | stat.S_IEXEC)
    root = os.path.join(d, 'storage', 'emulated', '0')
    os.makedirs(os.path.join(root, 'Download'))
    os.makedirs(os.path.join(root, 'Android', 'data'))
    open(os.path.join(root, 'Download', 'existing.pdf'), 'wb').write(b'foreign')
    open(os.path.join(root, 'Android', 'data', 'skip.bin'), 'wb').write(b'x')
    out = os.path.join(d, 'out')
    os.makedirs(os.path.join(out, 'apk'))
    env = dict(os.environ, PATH=b + os.pathsep + os.environ['PATH'], FAKE_ROOT=d, FAKE_SIM_DIR=HERE, FAKE_SCENARIO=json.dumps(sc))
    p = subprocess.run(['bash', SCRIPT, variant, out], capture_output=True, text=True, env=env, timeout=300)
    cmds = open(os.path.join(d, 'cmds.log')).read().splitlines() if os.path.exists(os.path.join(d, 'cmds.log')) else []
    foreign_ok = open(os.path.join(root, 'Download', 'existing.pdf'), 'rb').read() == b'foreign'
    rd = os.path.join(out, 'report-pkgremoval-' + variant)
    report = sorted(os.listdir(rd)) if os.path.isdir(rd) else []
    verdict = open(os.path.join(rd, 'summary.txt')).read() if 'summary.txt' in report else ''
    obs = [json.loads(l) for l in open(os.path.join(rd, 'observations.jsonl'))] if 'observations.jsonl' in report else []
    shutil.rmtree(d)
    return {'rc': p.returncode, 'out': p.stdout + p.stderr, 'cmds': cmds, 'foreign_ok': foreign_ok, 'report': report, 'verdict': verdict, 'obs': obs}


def result(r):
    return re.findall(r'PKGAB_SUMMARY variant=\w+ run=\w+ measurement=(\w+)', r['out'])


def finding(r, kind):
    return [x for x in r['obs'] if x['type'] == 'finding' and x['kind'] == kind]


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
            continue
        m = re.search(r'event=(\S+)', c)
        if c.startswith('shell log -t BOOTSCAN') and m:
            s.append('MARKER ' + m.group(1).strip("'"))
        elif c.startswith('shell am instrument'):
            s.append('INSTR ' + re.search(r'bootscanPhase (\w+)', c).group(1))
        else:
            s.append(re.sub(r'BootScanProbe-[0-9a-f]+-|/tmp/[^ ]+/', '', c))
    return s


# ---- Ablehnungen: vor jeder Logerfassung
for sc, want, logs_allowed in [(dict(device='phone'), 'not an emulator', False), (dict(api=33), 'API 33', False),
                               (dict(installed=True), 'already installed', False), (dict(probe_present=True), 'probe files already present', False),
                               (dict(query_broken=True), 'privileged query helper', True)]:
    for variant in ('control', 'uninstall'):
        r = run(variant, **sc)
        logcat = [c for c in r['cmds'] if c.startswith('logcat')]
        check(r['rc'] == 2 and ('measurement=REFUSED' in r['out'] if not logs_allowed else ('measurement=INCOMPLETE' in r['out'] and finding(r, 'HOST_ABORT')))
              and want in r['out'] and not unsafe(r['cmds']) and r['foreign_ok']
              and not any(c.startswith('shell am instrument') or c.startswith('install') for c in r['cmds'])
              and (logs_allowed or (not logcat and 'PKGAB_LOG' not in r['out'] and 'logcat-all.txt' not in r['report'])),
              'refuses %s (%s): exit %d, REFUSED/aborted, nothing installed, %s' % (sc, variant, r['rc'], 'logs only on an accepted emulator' if logs_allowed
                                                                              else 'no logcat collected at all %s' % logcat))
r = run('both')
check(r['rc'] == 2 and 'measurement=REFUSED' in r['out'] and not r['cmds'], 'unknown variant refused before any adb command (exit %d)' % r['rc'])

# ---- vollständig erfolgreiche synthetische Abläufe
ok = {'control': run('control', sim={'scan_after_pkg_end': 2000}), 'supported': run('uninstall', sim=SUPPORTED),
      'not_supported': run('uninstall', sim=NOT_SUPPORTED)}
VERDICT_WORDS = re.compile(r'\b(SUPPORTED|NOT_SUPPORTED|CLEAN|HYPOTHESIS\w*|ANOMALY|DIAGNOSED)\b|\bPASS\b')
for key in ('control', 'supported', 'not_supported'):
    r = ok[key]
    summ = r['obs'][-1] if r['obs'] else {}
    check(r['rc'] == 0 and result(r) == ['COMPLETE'] and summ.get('overlap') == 'PRESENT' and summ.get('data_loss') == 'NONE_PROVEN'
          and not VERDICT_WORDS.search(''.join(re.findall(r'PKGAB_\w+ .*', r['out']))),
          'complete synthetic host run %s: exit %d, measurement %s, overlap %s, no verdict word' % (key, r['rc'], result(r), summ.get('overlap')))
    check({'summary.txt', 'observations.jsonl', 'logcat.txt', 'logcat-all.txt', 'host-sampler.txt', 'pkgops.txt'} <= set(r['report'])
          and all(('PKGAB_INSTR %s instr-%s' % ('control' if key == 'control' else 'uninstall', ph)) in r['out'] for ph in ('pre', 'post', 'recheck'))
          and 'PKGAB_OPS' in r['out'] and 'PKGAB_HOSTQ' in r['out'] and 'PKGAB_LIST' in r['out'] and 'PKGAB_OBS' in r['out'],
          '%s: raw evidence and observations printed and kept in the report %s' % (key, r['report']))
again = run('uninstall', sim=SUPPORTED)
stable = lambda r: [x for x in r['obs'] if x['type'] in ('download', 'pkgop', 'scan', 'owner_event', 'file_obs', 'uri_check')] + \
    [{k: r['obs'][-1].get(k) for k in ('measurement', 'data_loss', 'overlap')}] if r['obs'] else []
check(stable(again) == stable(ok['supported']) and stable(again), 'deterministic: a second identical run yields identical observations')
check(bool(finding(ok['supported'], 'OWNER_EVENT')) and bool(finding(ok['supported'], 'ROW_OWNER_NOT_APP')) and not finding(ok['control'], 'OWNER_EVENT'),
      'delayed orphaning appears as listed facts (owner events, owner changes), not as a verdict; control has none')

c_steps, u_steps = steps(ok['control']['cmds']), steps(ok['supported']['cmds'])
mk = lambda st: [s[7:] for s in st if s.startswith('MARKER') and not s.startswith('MARKER op-') and not s.startswith('MARKER sync')]
check(mk(c_steps) == ['install1-begin', 'install1-end', 'update-begin', 'update-end'], 'control markers in order %s' % mk(c_steps))
check(mk(u_steps) == ['install1-begin', 'install1-end', 'uninstall-begin', 'absent', 'uninstall-end', 'install2-begin', 'install2-end'],
      'uninstall markers in order, absence proven before the reinstall %s' % mk(u_steps))
check(not any(c.startswith('uninstall') for c in ok['control']['cmds']), 'control never uninstalls')
un = [i for i, s in enumerate(u_steps) if s.startswith('uninstall')]
pos = lambda x: u_steps.index(x) if x in u_steps else -1
check([u_steps[i] for i in un] == ['uninstall %s.test' % PKG, 'uninstall %s' % PKG]
      and -1 < pos('INSTR pre') < pos('MARKER uninstall-begin') < un[0] < un[1] < pos('MARKER absent')
      < pos('MARKER install2-begin') < pos('INSTR post'),
      'uninstall: exactly test and app package once each, after phase pre, before the absence proof, reinstall and phase post')
ops = re.findall(r'event=op-end op=(\S+) attempt=\d+ rc=(\d+) result=(\S+)', '\n'.join(ok['supported']['cmds']))
check([o[0] for o in ops] == ['install-app', 'install-test', 'uninstall-test', 'uninstall-app', 'install-app', 'install-test']
      and all(o[1] == '0' and o[2].rstrip("'") == 'Success' for o in ops), 'every package operation logged with rc and result %s' % ops)
for name, st in (('control', c_steps), ('uninstall', u_steps)):
    ins = [s for s in st if s.startswith('INSTR')]
    check(ins == ['INSTR pre', 'INSTR post', 'INSTR recheck'], '%s: phases pre, post, recheck once each in order' % name)
    check(sum(1 for s in st if s.startswith('install -r -t')) == 4, '%s: two installations of both APKs (4 install commands)' % name)
    check(sum(1 for s in st if s == 'MARKER sync') == 2 and 'INSTR recheck' in st and st.index('MARKER sync') < st.index('INSTR recheck'),
          '%s: logcat stream synchronised by marker before the recheck and at the end' % name)


def common(st, a, b):
    if 'MARKER ' + a not in st or 'MARKER ' + b not in st:
        return ['markers %s/%s missing' % (a, b)]
    return st[:st.index('MARKER ' + a)] + ['PACKAGE-STEP'] + st[st.index('MARKER ' + b) + 1:]


check(common(c_steps, 'update-begin', 'update-end') == common(u_steps, 'uninstall-begin', 'install2-end'),
      'both variants issue the same command sequence except the package step')
for key in ok:
    check(not unsafe(ok[key]['cmds'], allow_uninstall=(key != 'control')) and ok[key]['foreign_ok'],
          '%s: no destructive device command besides the planned uninstall, foreign files untouched' % key)

# ---- fehlgeschlagene Deinstallation: INVALID, keine Neuinstallation, Rohbelege erhalten
for label, sim in [('uninstall of the test package reports Failure with rc=0', dict(SUPPORTED, uninstall_fail='test', uninstall_rc=0)),
                   ('uninstall of the app package fails with rc=1', dict(SUPPORTED, uninstall_fail='app', uninstall_rc=1)),
                   ('uninstall of the app package reports Failure with rc=0', dict(SUPPORTED, uninstall_fail='app', uninstall_rc=0)),
                   ('uninstall reports Success but the app package stays installed', dict(SUPPORTED, uninstall_fail='lie-app')),
                   ('uninstall reports Success but the test package stays installed', dict(SUPPORTED, uninstall_fail='lie-test'))]:
    r = run('uninstall', sim=sim)
    after = r['cmds'][max(i for i, c in enumerate(r['cmds']) if c.startswith('uninstall')):]
    check(r['rc'] == 2 and 'measurement=INCOMPLETE' in r['verdict'] and finding(r, 'HOST_ABORT')
          and (finding(r, 'PKGOP_FAILED') or finding(r, 'ABSENCE_NOT_PROVEN'))
          and not any(c.startswith('install') or 'bootscanPhase post' in c for c in after)
          and 'PKGAB_OPS' in r['out'] and 'PKGAB_LOG' in r['out'] and 'logcat.txt' in r['report'],
          'aborted, INCOMPLETE: %s (failure listed, no reinstall, raw evidence kept)' % label)

# ---- find-Fehler, kaputte Instrumentierung
r = run('control', find_fails=True, sim={'scan_after_pkg_end': 2000})
lists = [l for l in r['out'].splitlines() if l.startswith('PKGAB_LIST control foreign-before')]
check(result(r) == ['INCOMPLETE'] and 'foreign_lists' in r['obs'][-1]['incomplete_sources'] and not any(l.endswith('LIST-END') for l in lists)
      and any('LIST-ERROR' in l for l in lists), 'failed find gives no valid listing (no LIST-END) -> foreign_lists incomplete %s' % result(r))
r = run('control', instr_broken=True, sim={'scan_after_pkg_end': 2000})
check(r['rc'] == 2 and result(r) == ['INCOMPLETE'] and 'PKGAB_INSTR control instr-pre' in r['out'] and 'PKGAB_LIST control foreign-before' in r['out'],
      'broken instrumentation -> INCOMPLETE, evidence printed')
lists = [l for l in ok['control']['out'].splitlines() if l.startswith('PKGAB_LIST control foreign-before ')]
check(any(l.endswith('/storage/emulated/0/Download/existing.pdf') for l in lists) and not any('/Android/' in l for l in lists)
      and lists[-1:] == ['PKGAB_LIST control foreign-before LIST-END'], 'listing of foreign files complete (SHA-256, LIST-END), Android/ excluded')

src = open(SCRIPT).read()
helpers = re.findall(r"<<'(QSH|FSH|QTSH)'\n(.*?)\n\1\n", src, re.S)
body = ' '.join(h[1] for h in helpers)
check(len(helpers) == 3 and not DESTRUCTIVE.search(body.replace('--where', '')) and body.count('content query') == 2
      and 'content insert' not in body and '>' not in re.sub(r'2>&1|2>/dev/null', '', body),
      'device helper scripts only read (content query, find, stat, sha256sum; no redirection into files)')
check('wait-device-ready' not in re.sub(r'#.*', '', src), 'the probe does not wait for device readiness (boot scan not waited out)')

print('PKGAB_SCRIPT_SELFTEST %s %d cases, %d failed' % ('FAIL' if fails else 'PASS', len(done), len(fails)))
sys.exit(1 if fails else 0)
