#!/usr/bin/env python3
"""Selbsttest für tools/bootscan_verdict.py mit synthetischen Logcat-Aufnahmen (kein Gerät).

Geprüft wird: jede Klasse (OK, TRANSIENT, OWNER_CHANGED, STATE_CHANGED, REPLACED, LOST, UNEXPLAINED, PUBLISH_FAILED,
DATA_LOSS) wird genau so erkannt; eine fehlende oder unvollständige privilegierte Abfrage ergibt UNKNOWN, NIE LOST;
ohne Überschneidung mit dem Mount-Scan, ohne Scan oder ohne Kontrolle ist das Ergebnis INCONCLUSIVE; unvollständige
oder widersprüchliche Belege ergeben INVALID; Datenverlust (auch fremder Dateien) hat Vorrang; es gibt nie PASS.
Aufruf: python3 -I tests/bootscan/test_bootscan_verdict.py
"""
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOOL = os.path.join(ROOT, 'tools', 'bootscan_verdict.py')
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import bootscan_verdict as V  # noqa: E402

RUN = 'a1b2c3d4e5f6'
PKG = 'de.evp.helfer.channeltest'
T0 = 1763400000000
SCAN_END = T0 + 30_000          # externer Mount-Scan [SCAN_END-184, SCAN_END]
DIR = '/storage/emulated/0/Download/Video-Assistent/'
fails = []


def check(cond, what):
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


def instr_ok():
    c, t = 'de.evp.helfer.MediaStoreBootScanProbeTest', 'probe'
    head = ['INSTRUMENTATION_STATUS: class=' + c, 'INSTRUMENTATION_STATUS: current=1', 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner',
            'INSTRUMENTATION_STATUS: numtests=1', 'INSTRUMENTATION_STATUS: stream=', 'INSTRUMENTATION_STATUS: test=' + t, 'INSTRUMENTATION_STATUS_CODE: 1']
    return '\n'.join(head + head[:4] + ['INSTRUMENTATION_STATUS: stream=.', 'INSTRUMENTATION_STATUS: test=' + t, 'INSTRUMENTATION_STATUS_CODE: 0',
                                         'INSTRUMENTATION_RESULT: stream=', '', 'Time: 60.1', '', 'OK (1 test)', '', '', 'INSTRUMENTATION_CODE: -1']) + '\n'


def sha(seq):
    return ('%064x' % (seq * 7919 + 13))[-64:]


def dl(seq, phase, fate='OK', lo=None, hi=None):
    """Ein Download mit seinem Schicksal; lo/hi = Lebensdauer des Eintrags (ms ab T0)."""
    return {'seq': seq, 'phase': phase, 'fate': fate, 'lo': lo, 'hi': hi, 'id': str(1000000000 + seq)}


def build(downloads, scans=((SCAN_END - 184, SCAN_END),), recheck_priv='ok', recheck_cross='ok', recheck_uri_error=(),
          drop=(), other_lines=(), foreign_changed=False, instr_bad=(), extra=None, foreign_added=False, foreign_removed=False):
    """-> (logcat, instr dict, before, after). drop: Zeilenarten, die (zur Prüfung der Vollständigkeit) fehlen sollen."""
    L = []

    def line(t_ms, tag, msg, pid=4381):
        L.append('  %d.%03d  %d  %d I %s: %s' % (t_ms // 1000, t_ms % 1000, pid, pid + 26, tag, msg))

    line(T0 - 40_000, 'MediaProvider', 'Scanned internal due to REASON_MOUNTED, found 227 items in 30418ms, 224 inserts 3 updates 0 deletes', 1644)
    for s, e in scans:
        line(e, 'MediaProvider', 'Scanned external_primary due to REASON_MOUNTED, found 18 items in %dms, 2 inserts 1 updates 0 deletes' % (e - s), 1644)
    for ol in other_lines:
        L.append(ol)
    for phase, t_start in (('during', T0), ('after', SCAN_END + 15_000)):
        own = [d for d in downloads if d['phase'] == phase]
        line(t_start, 'BOOTSCAN', 'BOOTSCAN START run=%s phase=%s sdk=34 pkg=%s t=%d' % (RUN, phase, PKG, t_start))
        for d in own:
            lo = T0 + d['lo'] if d['lo'] is not None else t_start + 1000 + 400 * d['seq'] % 10000
            hi = T0 + d['hi'] if d['hi'] is not None else lo + 300
            done = d['fate'] != 'PUBLISH_FAILED'
            uri = 'content://media/external_primary/downloads/' + d['id'] if done else '-'
            line(hi, 'BOOTSCAN', 'BOOTSCAN DL run=%s phase=%s seq=%d name=BootScanProbe-%s-%d.mp4 sha=%s size=262144 insertStart=%d insertEnd=%d '
                 'commitStart=%d commitEnd=%d commit=%s done=%d state=%s reason=%s uri=%s id=%s'
                 % (RUN, phase, d['seq'], RUN, d['seq'], sha(d['seq']), lo, lo + 5, hi - 10, hi - 2, 'ok' if done else 'ex:IOException', hi,
                    'done' if done else 'failed', '-' if done else 'storage', uri, d['id'] if done else '-'))
            if done and 'uriq' not in drop:
                for off in (0, 250, 2000, 10000):
                    hidden = (d['fate'] == 'TRANSIENT' and off == 250) or (d['fate'] in ('OWNER_CHANGED', 'REPLACED', 'LOST', 'STATE_CHANGED', 'UNEXPLAINED') and off >= 250)
                    res = 'none' if hidden else 'found id=%s owner=%s pending=0 data=%sBootScanProbe-%s-%d.mp4' % (d['id'], PKG, DIR, RUN, d['seq'])
                    line(hi + off, 'BOOTSCAN', 'BOOTSCAN URIQ run=%s seq=%d off=%d t=%d res=%s' % (RUN, d['seq'], off, hi + off, res))
        t_snap = t_start + 50
        line(t_snap, 'BOOTSCAN', 'BOOTSCAN SNAP run=%s n=1 t=%d app=ok appN=0 priv=ok privN=0 cross=ok' % (RUN, t_snap))
        if 'dsnap' not in drop:
            line(t_snap + 5, 'BOOTSCAN', 'BOOTSCAN DSNAP run=%s n=1 t=%d disk=ok diskN=0' % (RUN, t_snap + 5))
        if not ('end' in drop and phase == 'after'):
            snaps = 2 if 'gap' in drop and phase == 'during' else 1
            line(t_start + 70_000, 'BOOTSCAN', 'BOOTSCAN END run=%s phase=%s downloads=%d snapshots=%d invalid=0 t=%d' % (RUN, phase, len(own), snaps, t_start + 70_000))
    # Nachprüfung: URI-Abfrage je Download, eine Aufnahme mit App-, privilegierter und Dateisicht
    if 'recheck' not in drop:
        t = SCAN_END + 90_000
        line(t, 'BOOTSCAN', 'BOOTSCAN START run=%s phase=recheck sdk=34 pkg=%s t=%d' % (RUN, PKG, t))
        app, priv, disk = {}, {}, {}
        for d in downloads:
            name = 'BootScanProbe-%s-%d.mp4' % (RUN, d['seq'])
            f = d['fate']
            if f not in ('DATA_LOSS_MISSING',):
                disk[DIR + name] = (262144, ('0' * 64) if f == 'DATA_LOSS_SHA' else sha(d['seq']))
            if f == 'PUBLISH_FAILED':
                continue
            vis = f in ('OK', 'TRANSIENT', 'DATA_LOSS_SHA', 'DATA_LOSS_MISSING')
            if d['seq'] in recheck_uri_error:
                res = 'error:SecurityException'
            else:
                res = ('found id=%s owner=%s pending=0 data=%s%s' % (d['id'], PKG, DIR, name)) if vis else 'none'
            if 'recheckuri' not in drop:
                line(t + 10, 'BOOTSCAN', 'BOOTSCAN URIQ run=%s seq=%d off=-1 t=%d res=%s' % (RUN, d['seq'], t + 10, res))
            if vis:
                app[d['id']] = (PKG, '0', name)
                priv[d['id']] = (PKG, '0', '0', DIR + name)
            elif f == 'OWNER_CHANGED':
                priv[d['id']] = ('NULL', '0', '0', DIR + name)
            elif f == 'STATE_CHANGED':
                priv[d['id']] = (PKG, '1', '0', DIR + name)
            elif f == 'UNEXPLAINED':
                priv[d['id']] = (PKG, '0', '0', DIR + name)
            elif f == 'REPLACED':
                priv[str(int(d['id']) + 5000)] = ('NULL', '0', '0', DIR + name)
        n = 1
        for k, v in app.items():
            line(t + 20, 'BOOTSCAN', 'BOOTSCAN APP run=%s n=%d +%s|%s' % (RUN, n, k, '|'.join(v)))
        if recheck_priv == 'ok' and recheck_cross == 'ok':
            for k, v in priv.items():
                line(t + 20, 'BOOTSCAN', 'BOOTSCAN PRIV run=%s n=%d +%s|%s' % (RUN, n, k, '|'.join(v)))
        line(t + 21, 'BOOTSCAN', 'BOOTSCAN SNAP run=%s n=%d t=%d app=ok appN=%d priv=%s privN=%d cross=%s'
             % (RUN, n, t + 21, len(app), recheck_priv, len(priv) if recheck_priv == 'ok' else -1, recheck_cross if recheck_priv == 'ok' else 'na'))
        for k, v in disk.items():
            line(t + 22, 'BOOTSCAN', 'BOOTSCAN DISK run=%s n=%d +%s|%d|%s' % (RUN, n, k, v[0], v[1]))
        line(t + 23, 'BOOTSCAN', 'BOOTSCAN DSNAP run=%s n=%d t=%d disk=ok diskN=%d' % (RUN, n, t + 23, len(disk)))
        line(t + 30, 'BOOTSCAN', 'BOOTSCAN END run=%s phase=recheck downloads=0 snapshots=1 invalid=0 t=%d' % (RUN, t + 30))
    if extra:
        L.extend(extra)
    instr = {p: ('FAILURES!!!\nTests run: 1,  Failures: 1\n' if p in instr_bad else instr_ok()) for p in ('during', 'after', 'recheck')}
    before = {'/storage/emulated/0/Download/other.txt': ('b' * 64, 3)}
    after = {'/storage/emulated/0/Download/other.txt': ('c' * 64 if foreign_changed else 'b' * 64, 3)}
    if foreign_added:   # wie Lauf 44281951152: vom System angelegte .nomedia-Dateien
        after.update({'/storage/emulated/0/%s/.thumbnails/.nomedia' % d: ('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 0)
                      for d in ('DCIM', 'Movies', 'Pictures')})
    if foreign_removed:
        del after['/storage/emulated/0/Download/other.txt']
    return '\n'.join(L) + '\n', instr, before, after


def verdict(*a, **kw):
    log, instr, before, after = build(*a, **kw)
    return V.evaluate(log, RUN, instr, before, after, PKG)


def overlap(seq, fate):
    """Download, dessen Lebensdauer den Scan schneidet."""
    return dl(seq, 'during', fate, lo=30_000 - 150, hi=30_000 + 120)


BASE = [dl(0, 'during'), dl(10000, 'after'), dl(10001, 'after')]

# --- jede Klasse genau erkannt (Überschneidungsgruppe), Ergebnis DIAGNOSED mit reproduced=yes
for fate in ('OWNER_CHANGED', 'STATE_CHANGED', 'REPLACED', 'LOST', 'UNEXPLAINED', 'TRANSIENT', 'PUBLISH_FAILED'):
    r, why, rows, groups = verdict(BASE + [overlap(1, fate)])
    cls = {s: c for s, g, c, w in rows}
    check(r == 'DIAGNOSED' and cls.get('1') == fate and groups.get('OVERLAP') == {fate: 1} and why == ['reproduced=yes control_anomaly=no'],
          'class %s recognised in the overlap group (%s; %s)' % (fate, r, [w for s, g, c, w in rows if s == '1'][:1]))

r, why, rows, groups = verdict(BASE + [overlap(1, 'OK')])
check(r == 'DIAGNOSED' and why == ['reproduced=no control_anomaly=no'] and groups['OVERLAP'] == {'OK': 1} and groups['CONTROL'] == {'OK': 2},
      'overlap OK and control OK -> DIAGNOSED reproduced=no (never PASS) %s' % why)
r, why, rows, groups = verdict([dl(0, 'during'), overlap(1, 'OK'), dl(10000, 'after', 'OWNER_CHANGED'), dl(10001, 'after')])
check(r == 'DIAGNOSED' and why == ['reproduced=no control_anomaly=yes'], 'anomaly only in the control -> control_anomaly=yes %s' % why)

# --- Datenverlust hat Vorrang
r, why, _, _ = verdict(BASE + [overlap(1, 'DATA_LOSS_SHA')])
check(r == 'DATA_LOSS' and 'changed' in why[0], 'content changed (SHA-256) -> DATA_LOSS %s' % why[:1])
r, why, _, _ = verdict(BASE + [overlap(1, 'DATA_LOSS_MISSING')])
check(r == 'DATA_LOSS' and 'missing' in why[0], 'file missing -> DATA_LOSS %s' % why[:1])
r, why, _, _ = verdict(BASE + [overlap(1, 'OK')], foreign_changed=True)
check(r == 'DATA_LOSS' and 'outside the probe' in why[0], 'foreign file changed -> DATA_LOSS %s' % why[:1])
notes = []
log, instr, before, after = build(BASE + [overlap(1, 'OK')], foreign_added=True)
r, why, _, _ = V.evaluate(log, RUN, instr, before, after, PKG, notes)
check(r != 'DATA_LOSS' and any(n.startswith('FOREIGN_ADDED') and 'foreign=v2' in n for n in notes),
      'rule foreign=v2: files added by the system (.thumbnails/.nomedia) are a note, not DATA_LOSS (%s)' % r)
r, why, _, _ = verdict(BASE + [overlap(1, 'OK')], foreign_added=True, foreign_removed=True)
check(r == 'DATA_LOSS' and 'removed' in why[0] and 'other.txt' in why[0], 'existing foreign file removed -> DATA_LOSS even with added files %s' % why[:1])
r, why, _, _ = verdict(BASE + [overlap(1, 'OK')], foreign_added=True, foreign_changed=True)
check(r == 'DATA_LOSS' and 'modified' in why[0], 'existing foreign file modified -> DATA_LOSS even with added files %s' % why[:1])
r, why, _, _ = verdict(BASE + [overlap(1, 'DATA_LOSS_SHA')], scans=())
check(r == 'DATA_LOSS', 'data loss is reported even without an observed scan (not hidden behind INCONCLUSIVE)')

# --- fehlende/unvollständige privilegierte Abfrage ist kein Beweis
r, why, rows, _ = verdict(BASE + [overlap(1, 'LOST')], recheck_priv='bad:frame-Q-END_rc-1')
check(r == 'INCONCLUSIVE' and {s: c for s, g, c, w in rows}.get('1') == 'UNKNOWN', 'privileged query failed -> UNKNOWN, never LOST (%s)' % r)
r, why, rows, _ = verdict(BASE + [overlap(1, 'LOST')], recheck_cross='missing:1000000000')
check({s: c for s, g, c, w in rows}.get('1') == 'UNKNOWN', 'privileged query incomplete (misses rows the app sees) -> UNKNOWN, never LOST')
r, why, rows, _ = verdict(BASE + [overlap(1, 'OWNER_CHANGED')], recheck_uri_error=(1,))
check({s: c for s, g, c, w in rows}.get('1') == 'UNKNOWN', 'recheck URI query error (missing permission) -> UNKNOWN')

# --- ohne Überschneidung, ohne Scan, ohne Kontrolle: INCONCLUSIVE, nie PASS
r, why, _, _ = verdict(BASE, )
check(r == 'INCONCLUSIVE' and any('overlapped' in w for w in why), 'no publication overlapped the scan -> INCONCLUSIVE %s' % why)
r, why, _, _ = verdict(BASE + [overlap(1, 'LOST')], scans=())
check(r == 'INCONCLUSIVE' and any('REASON_MOUNTED' in w for w in why), 'no mount scan observed -> INCONCLUSIVE (even with an anomaly) %s' % why[:1])
r, why, rows, _ = verdict([dl(0, 'during'), overlap(1, 'OK'), dl(10000, 'after', lo=30_000 + 3_000, hi=30_000 + 3_300)])
check(r == 'INCONCLUSIVE' and any('controlled' in w for w in why), 'after-publication within 10 s of the scan is not a control -> INCONCLUSIVE %s' % why)
r, why, _, _ = verdict(BASE + [dl(1, 'during', 'OK', lo=30_000 + 1, hi=30_000 + 200)], scans=((30_000 - 184, 30_000),))
check(r == 'INCONCLUSIVE', 'publication starting just after the scan end does not count as overlap')

# --- unvollständige oder widersprüchliche Belege: INVALID
for label, kw in [('END of a phase missing', dict(drop=('end',))), ('snapshot count differs from END', dict(drop=('gap',))),
                  ('URI checks after done missing', dict(drop=('uriq',))), ('disk snapshot missing', dict(drop=('dsnap',))),
                  ('recheck phase missing', dict(drop=('recheck',))), ('recheck URI query missing', dict(drop=('recheckuri',))),
                  ('instrumentation not OK', dict(instr_bad=('after',))),
                  ('line of another run', dict(extra=['  1763400050.000  4381  4407 I BOOTSCAN: BOOTSCAN SNAP run=ffffffffffff n=9 t=1 app=ok appN=0 priv=ok privN=0 cross=ok'])),
                  ('chatty suppression of the probe process', dict(extra=['  1763400050.000  4381  4407 I chatty  : uid=10194(de.evp.helfer.channeltest) BOOTSCAN identical 12 lines']))]:
    r, why, _, _ = verdict(BASE + [overlap(1, 'OK')], **kw)
    check(r == 'INVALID' and bool(why), 'INVALID: %s (%s)' % (label, why[0][:90] if why else 'NO REASON'))
log, instr, before, after = build(BASE + [overlap(1, 'OK')])
log = log.replace('seq=10001 ', 'seq=10003 ')
check(V.evaluate(log, RUN, instr, before, after, PKG)[0] == 'INVALID', 'INVALID: download sequence gap')
log, instr, before, after = build(BASE + [overlap(1, 'OK')])
r, why = V.evaluate(log.replace('|0|0|/storage', '|0|/storage', 1), RUN, instr, before, after, PKG)[:2]
check(r == 'INVALID' and 'change line with' in why[0], 'INVALID: unreadable change line inside a phase (%s)' % why[:1])

# --- Kommandozeile: Exit-Codes, nie PASS, unvollständige Dateiliste
with tempfile.TemporaryDirectory() as d:
    def w(n, s):
        p = os.path.join(d, n)
        open(p, 'w').write(s)
        return p

    def lst(m, end=True):
        return ''.join('%s %d %s\n' % (v[0], v[1], k) for k, v in m.items()) + ('LIST-END\n' if end else '')

    for label, downloads, kw, code, word, end in [('diagnosed', BASE + [overlap(1, 'OWNER_CHANGED')], {}, 0, 'DIAGNOSED', True),
                                                  ('inconclusive', BASE, {}, 4, 'INCONCLUSIVE', True),
                                                  ('data loss', BASE + [overlap(1, 'DATA_LOSS_SHA')], {}, 3, 'DATA_LOSS', True),
                                                  ('invalid', BASE + [overlap(1, 'OK')], dict(drop=('end',)), 2, 'INVALID', True),
                                                  ('foreign list incomplete', BASE + [overlap(1, 'OK')], {}, 2, 'INVALID', False)]:
        log, instr, before, after = build(downloads, **kw)
        p = subprocess.run([sys.executable, '-I', TOOL, '--logcat', w('l', log), '--run', RUN, '--instr-during', w('i1', instr['during']),
                            '--instr-after', w('i2', instr['after']), '--instr-recheck', w('i3', instr['recheck']),
                            '--foreign-before', w('b', lst(before)), '--foreign-after', w('a', lst(after, end))], capture_output=True, text=True)
        check(p.returncode == code and ('BOOTSCAN_RESULT ' + word) in p.stdout and 'PASS' not in p.stdout,
              'cli %s: exit %d, BOOTSCAN_RESULT %s, no PASS' % (label, p.returncode, word))

n = 7 + 2 + 4 + 3 + 4 + 11 + 5 + 3
print('BOOTSCAN_VERDICT_SELFTEST %s %d cases, %d failed' % ('FAIL' if fails else 'PASS', n, len(fails)))
sys.exit(1 if fails else 0)
