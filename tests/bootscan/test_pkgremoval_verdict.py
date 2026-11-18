#!/usr/bin/env python3
"""Selbsttest für tools/pkgremoval_verdict.py mit synthetischen Belegen (kein Gerät).

Geprüft wird: SUPPORTED nur bei nachweislich verzögerter Besitzer-Aufhebung (nach dem Ende der Neuinstallation), die
neue Downloads trifft; NOT_SUPPORTED, wenn diese unversehrt bleiben; INCONCLUSIVE ohne beobachtete Aufhebung, bei nicht
verzögerter oder zeitlich mehrdeutiger Aufhebung und ohne neue Downloads davor; das normale Verwaisen der ersten
Installation (ORPHANED_EXPECTED) ist kein Befund; Generationen getrennt; Kontrolllauf CLEAN/ANOMALY/INCONCLUSIVE;
DATA_LOSS nur bei fehlenden/veränderten Dateien bzw. gelöschten/veränderten fremden Dateien, neu hinzugekommene
Systemdateien nur als Hinweis; unvollständige Belege INVALID; A/B-Kombination; nie PASS.
Aufruf: python3 -I tests/bootscan/test_pkgremoval_verdict.py
"""
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOOL = os.path.join(ROOT, 'tools', 'pkgremoval_verdict.py')
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import pkgremoval_verdict as V  # noqa: E402

RUN = 'a1b2c3d4e5f6'
PKG = 'de.evp.helfer.channeltest'
DIR = '/storage/emulated/0/Download/Video-Assistent/'
T0 = 1763400000000
SCAN = (T0 + 30_000 - 300, T0 + 30_000)   # externer Mount-Scan
fails, done = [], []


def check(cond, what):
    done.append(what)
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


def instr_ok():
    c, t = 'de.evp.helfer.MediaStoreBootScanProbeTest', 'probe'
    head = ['INSTRUMENTATION_STATUS: class=' + c, 'INSTRUMENTATION_STATUS: current=1', 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner',
            'INSTRUMENTATION_STATUS: numtests=1', 'INSTRUMENTATION_STATUS: stream=', 'INSTRUMENTATION_STATUS: test=' + t, 'INSTRUMENTATION_STATUS_CODE: 1']
    return '\n'.join(head + head[:4] + ['INSTRUMENTATION_STATUS: stream=.', 'INSTRUMENTATION_STATUS: test=' + t, 'INSTRUMENTATION_STATUS_CODE: 0',
                                         'INSTRUMENTATION_RESULT: stream=', '', 'Time: 9.1', '', 'OK (1 test)', '', '', 'INSTRUMENTATION_CODE: -1']) + '\n'


def sha(seq):
    return ('%064x' % (seq * 7919 + 13))[-64:]


def name(seq):
    return 'BootScanProbe-%s-%d.mp4' % (RUN, seq)


def build(variant='uninstall', post=(), install1='orphaned', owner_line=T0 + 30_050, host=(), pkg_times=None, drop=(),
          foreign=('same',), recheck_priv='ok', extra=(), post_snap_owned=True):
    """post: [(seq, lo_ms, hi_ms, fate)] ab T0 (seq wird lückenlos ab 30000 neu nummeriert); host: [(t_ms ab T0, owned)]
    Host-Abfragen der install1-Zeilen; post_snap_owned: Besitzer der install1-Zeilen im ersten privilegierten post-Abzug."""
    L = []
    post = [(30000 + i, lo, hi, fate) for i, (_, lo, hi, fate) in enumerate(post)]

    def line(t, tag, msg, pid=4381, tid=None):
        L.append('  %d.%03d  %d  %d I %s: %s' % (t // 1000, t % 1000, pid, tid or pid + 26, tag, msg))

    line(SCAN[1], 'MediaProvider', 'Scanned external_primary due to REASON_MOUNTED, found 30 items in %dms, 2 inserts 0 updates 0 deletes' % (SCAN[1] - SCAN[0]), 1642, 2289)
    ev = V.EVENTS[variant]
    times = pkg_times or ({'install1-begin': 2_000, 'install1-end': 9_000, 'uninstall-begin': 16_000, 'uninstall-end': 17_000,
                           'install2-begin': 17_100, 'install2-end': 25_000} if variant == 'uninstall' else
                          {'install1-begin': 2_000, 'install1-end': 9_000, 'update-begin': 16_000, 'update-end': 25_000})
    for e in ev:
        if ('marker', e) in drop:
            continue
        line(T0 + times[e], 'BOOTSCAN', 'BOOTSCAN PKG run=%s variant=%s event=%s uid=10194' % (RUN, variant, e), 2000)
    if owner_line is not None and variant == 'uninstall':
        line(owner_line, 'MediaProvider', 'Invalidating LocalCallingIdentity cache for package null. Reason: owner_package_changed:%s%s' % (DIR, name(20000)), 1642, 2289)
    pre = [(20000 + i, 10_000 + 300 * i, 10_000 + 300 * i + 200, 'OK') for i in range(3)]
    for ol in extra:
        L.append(ol)
    for ph, t_start, dls, offs in (('pre', T0 + 9_500, pre, (0, 250)), ('post', T0 + 25_500, list(post), (0, 250, 2000, 10000))):
        if ('phase', ph) in drop:
            continue
        line(t_start, 'BOOTSCAN', 'BOOTSCAN START run=%s phase=%s sdk=34 pkg=%s appUid=%d pid=1 t=%d' % (RUN, ph, PKG, 10194 if ph == 'pre' else 10195, t_start))
        for seq, lo, hi, fate in dls:
            done = fate != 'PUBLISH_FAILED'
            line(T0 + hi, 'BOOTSCAN', 'BOOTSCAN DL run=%s phase=%s seq=%d name=%s sha=%s size=262144 insertStart=%d insertEnd=%d commitStart=%d '
                 'commitEnd=%d commit=%s done=%d state=%s reason=%s uri=%s id=%s'
                 % (RUN, ph, seq, name(seq), sha(seq), T0 + lo, T0 + lo + 5, T0 + hi - 10, T0 + hi - 2, 'ok' if done else 'ex:IOException', T0 + hi,
                    'done' if done else 'failed', '-' if done else 'storage', 'content://media/external/downloads/%d' % (100000 + seq) if done else '-',
                    str(100000 + seq) if done else '-'))
            if done and ('uriq', ph) not in drop:
                for off in offs:
                    hidden = fate in ('OWNER_CHANGED', 'LOST', 'REPLACED') and off >= 250
                    res = 'none' if hidden else 'found id=%d owner=%s pending=0 data=%s%s' % (100000 + seq, PKG, DIR, name(seq))
                    line(T0 + hi + off, 'BOOTSCAN', 'BOOTSCAN URIQ run=%s seq=%d off=%d t=%d res=%s' % (RUN, seq, off, T0 + hi + off, res))
        snap = {} if ph == 'pre' else {str(100000 + s_): (PKG if post_snap_owned else 'NULL', '0', '0', DIR + name(s_)) for s_, _, _, _ in pre}
        for k, v in snap.items():
            line(t_start + 1, 'BOOTSCAN', 'BOOTSCAN PRIV run=%s n=1 +%s|%s' % (RUN, k, '|'.join(v)))
        line(t_start + 1, 'BOOTSCAN', 'BOOTSCAN SNAP run=%s n=1 t=%d app=ok appN=0 priv=ok privN=%d cross=ok' % (RUN, t_start + 1, len(snap)))
        line(t_start + 2, 'BOOTSCAN', 'BOOTSCAN DSNAP run=%s n=1 t=%d disk=ok diskN=0' % (RUN, t_start + 2))
        line(t_start + 40_000, 'BOOTSCAN', 'BOOTSCAN END run=%s phase=%s downloads=%d snapshots=1 invalid=0 t=%d' % (RUN, ph, len(dls), t_start + 40_000))
    # Nachprüfung
    t = T0 + 90_000
    if ('phase', 'recheck') not in drop:
        line(t, 'BOOTSCAN', 'BOOTSCAN START run=%s phase=recheck sdk=34 pkg=%s appUid=10195 pid=2 t=%d' % (RUN, PKG, t))
        app, priv, disk = {}, {}, {}
        for seq, lo, hi, fate in pre + list(post):
            disk[DIR + name(seq)] = (262144, ('0' * 64) if fate == 'DATA_LOSS' else sha(seq))
            if fate == 'PUBLISH_FAILED':
                continue
            sid = str(100000 + seq)
            gen1 = seq < 30000
            if gen1 and variant == 'uninstall' and install1 == 'orphaned':
                vis, own = False, 'NULL'
            else:
                vis, own = fate in ('OK', 'DATA_LOSS'), PKG
            if fate == 'OWNER_CHANGED':
                own = 'NULL'
            res = ('found id=%s owner=%s pending=0 data=%s%s' % (sid, PKG, DIR, name(seq))) if vis else 'none'
            if fate == 'UNKNOWN':
                res = 'error:SecurityException'
            line(t + 5, 'BOOTSCAN', 'BOOTSCAN URIQ run=%s seq=%d off=-1 t=%d res=%s' % (RUN, seq, t + 5, res))
            if vis:
                app[sid] = (PKG, '0', name(seq))
            if fate == 'REPLACED':
                priv[str(900000 + seq)] = ('NULL', '0', '0', DIR + name(seq))
            elif fate != 'LOST':
                priv[sid] = (own, '0', '0', DIR + name(seq))
        for k, v in app.items():
            line(t + 10, 'BOOTSCAN', 'BOOTSCAN APP run=%s n=1 +%s|%s' % (RUN, k, '|'.join(v)))
        if recheck_priv == 'ok':
            for k, v in priv.items():
                line(t + 10, 'BOOTSCAN', 'BOOTSCAN PRIV run=%s n=1 +%s|%s' % (RUN, k, '|'.join(v)))
        line(t + 11, 'BOOTSCAN', 'BOOTSCAN SNAP run=%s n=1 t=%d app=ok appN=%d priv=%s privN=%d cross=%s'
             % (RUN, t + 11, len(app), recheck_priv, len(priv), 'ok' if recheck_priv == 'ok' else 'na'))
        for k, v in disk.items():
            line(t + 12, 'BOOTSCAN', 'BOOTSCAN DISK run=%s n=1 +%s|%d|%s' % (RUN, k, v[0], v[1]))
        line(t + 13, 'BOOTSCAN', 'BOOTSCAN DSNAP run=%s n=1 t=%d disk=ok diskN=%d' % (RUN, t + 13, len(disk)))
        line(t + 20, 'BOOTSCAN', 'BOOTSCAN END run=%s phase=recheck downloads=0 snapshots=1 invalid=0 t=%d' % (RUN, t + 20))
    hosts = []
    for ht, owned in host:
        hosts += ['QT-BEGIN t=%d res=ms' % (T0 + ht)] + ['Row: %d _id=%d, owner_package_name=%s, is_pending=0, is_trashed=0, _data=%s%s, _display_name=%s, _size=262144'
                                                          % (i, 100000 + 20000 + i, PKG if owned else 'NULL', DIR, name(20000 + i), name(20000 + i)) for i in range(3)] + ['QT-END rc=0']
    instr = {p: instr_ok() for p in V.PHASES}
    before = {'/storage/emulated/0/Movies/.thumbnails/.database_uuid': ('a' * 64, 36)}
    after = dict(before)
    if 'added' in foreign:
        after['/storage/emulated/0/Movies/.thumbnails/.nomedia'] = ('e' * 64, 0)
    if 'removed' in foreign:
        after = {}
    if 'modified' in foreign:
        after = {k: ('b' * 64, 36) for k in before}
    return '\n'.join(L) + '\n', '\n'.join(hosts) + '\n', instr, before, after


def verdict(variant='uninstall', **kw):
    log, hosts, instr, before, after = build(variant, **kw)
    return V.variant_verdict(variant, RUN, log, hosts, instr, before, after, PKG)


# post-Downloads: vor der Besitzer-Aufhebung (T0+30_050) bzw. danach
BEFORE = [(30000, 26_000, 26_300, 'OK'), (30001, 27_000, 27_300, 'OK')]
AFTER = [(30002, 40_000, 40_300, 'OK'), (30003, 41_000, 41_300, 'OK')]

r, why, rows, notes = verdict(post=[(30000, 26_000, 26_300, 'OWNER_CHANGED'), (30001, 27_000, 27_300, 'OK')] + AFTER)
gen = {x['seq']: (x['gen'], x['cls'], x['at_risk']) for x in rows}
check(r == 'SUPPORTED' and gen['20000'] == ('install1', 'ORPHANED_EXPECTED', False) and gen['30000'] == ('install2', 'OWNER_CHANGED', True)
      and gen['30002'][2] is False, 'SUPPORTED: delayed owner removal hits a new download; install1 orphaning is expected (%s)' % why[:1])
for fate in ('LOST', 'REPLACED', 'PUBLISH_FAILED'):
    r, why, rows, _ = verdict(post=[(30000, 26_000, 26_300, fate)] + AFTER)
    check(r == 'SUPPORTED' and any(x['cls'] == fate and x['at_risk'] for x in rows), 'SUPPORTED with %s of a new download (%s)' % (fate, why[:1]))
r, why, rows, notes = verdict(post=[(30000, 26_000, 26_300, 'OWNER_CHANGED'), (30001, 27_000, 27_300, 'OK'), (30002, 40_000, 40_300, 'LOST')])
check(r == 'INCONCLUSIVE' and 'after the delayed owner removal failed too' in why[0], 'failure after the owner removal too -> INCONCLUSIVE, not SUPPORTED %s' % why)
r, why, rows, _ = verdict(post=BEFORE + AFTER)
check(r == 'NOT_SUPPORTED' and all(x['cls'] == 'OK' for x in rows if x['gen'] == 'install2'), 'NOT_SUPPORTED: delayed removal, new downloads stay OK %s' % why)
r, why, rows, _ = verdict(post=BEFORE + AFTER, install1='kept', owner_line=None, host=[(17_500, True), (60_000, True)])
check(r == 'INCONCLUSIVE' and any(x['cls'] == 'NOT_ORPHANED' for x in rows) and 'no owner removal' in why[0],
      'no owner removal observed -> INCONCLUSIVE, install1 NOT_ORPHANED reported %s' % why)
r, why, _, _ = verdict(post=BEFORE + AFTER, owner_line=T0 + 20_000)
check(r == 'INCONCLUSIVE' and 'not delayed' in why[0], 'owner removal before the reinstall ended -> INCONCLUSIVE (not delayed) %s' % why)
r, why, _, _ = verdict(post=BEFORE + AFTER, owner_line=None, host=[(18_000, True), (32_000, False)], post_snap_owned=False)
check(r == 'INCONCLUSIVE' and 'ambiguous' in why[0], 'removal only seen between samples spanning the reinstall end -> INCONCLUSIVE (ambiguous) %s' % why)
r, why, _, _ = verdict(post=BEFORE + AFTER, owner_line=None, host=[(18_000, True), (26_000, True), (31_000, False)])
check(r == 'NOT_SUPPORTED' and 'between' not in why[0], 'removal bracketed by host samples after the reinstall end counts as delayed %s' % why)
r, why, _, _ = verdict(post=AFTER)
check(r == 'INCONCLUSIVE' and 'no download of the new installation before it' in why[0], 'no new download before the delayed removal -> INCONCLUSIVE %s' % why)
r, why, rows, _ = verdict(post=[(30000, 26_000, 26_300, 'UNKNOWN')] + AFTER)
check(r == 'INCONCLUSIVE' and any(x['cls'] == 'UNKNOWN' for x in rows), 'unreliable recheck of a new download -> INCONCLUSIVE, never NOT_SUPPORTED %s' % why)
r, why, rows, _ = verdict(post=[(30000, 26_000, 26_300, 'LOST')] + AFTER, recheck_priv='bad:frame')
check(r != 'SUPPORTED' and not any(x['cls'] == 'LOST' for x in rows), 'privileged recheck failed -> no LOST claim, no SUPPORTED (%s)' % r)

# Kontrolllauf
r, why, rows, _ = verdict('control', post=[(30000, 29_800, 30_100, 'OK')] + AFTER, owner_line=None)
check(r == 'CLEAN' and {x['gen'] for x in rows} == {'install1', 'updated'}, 'control CLEAN: all OK, downloads span the scan end %s' % why)
r, why, _, _ = verdict('control', post=AFTER, owner_line=None)
check(r == 'INCONCLUSIVE', 'control without a download spanning the scan end -> INCONCLUSIVE %s' % why)
r, why, _, _ = verdict('control', post=[(30000, 29_800, 30_100, 'OWNER_CHANGED')] + AFTER, owner_line=None)
check(r == 'ANOMALY', 'control with an owner change -> ANOMALY %s' % why[:1])

# Dateien
r, why, _, notes = verdict(post=BEFORE + AFTER, foreign=('added',))
check(r == 'NOT_SUPPORTED' and any('FOREIGN_ADDED' in n for n in notes), 'added system files are only a note, not DATA_LOSS (%s)' % r)
for kind in ('removed', 'modified'):
    r, why, _, _ = verdict(post=BEFORE + AFTER, foreign=(kind,))
    check(r == 'DATA_LOSS', 'existing foreign file %s -> DATA_LOSS %s' % (kind, why[:1]))
r, why, _, _ = verdict(post=[(30000, 26_000, 26_300, 'DATA_LOSS')] + AFTER)
check(r == 'DATA_LOSS', 'probe file content changed -> DATA_LOSS %s' % why[:1])

# unvollständige Belege
for label, kw in [('marker missing', dict(drop=(('marker', 'install2-end'),))), ('post phase missing', dict(drop=(('phase', 'post'),))),
                  ('recheck missing', dict(drop=(('phase', 'recheck'),))), ('URI checks missing', dict(drop=(('uriq', 'post'),)))]:
    r, why, _, _ = verdict(post=BEFORE + AFTER, **kw)
    check(r == 'INVALID' and bool(why), 'INVALID: %s (%s)' % (label, why[0][:80] if why else 'NO REASON'))
r, why, _, _ = verdict(post=BEFORE + AFTER, pkg_times={'install1-begin': 2_000, 'install1-end': 9_000, 'uninstall-begin': 16_000, 'uninstall-end': 15_000,
                                                       'install2-begin': 17_100, 'install2-end': 25_000})
check(r == 'INVALID' and 'out of order' in why[0], 'INVALID: package markers out of order')
log, hosts, instr, before, after = build(post=BEFORE + AFTER)
check(V.variant_verdict('control', RUN, log, hosts, instr, before, after, PKG)[0] == 'INVALID', 'INVALID: markers of another variant')

# A/B
for c, e, want in [('CLEAN', 'SUPPORTED', 'HYPOTHESIS_SUPPORTED'), ('ANOMALY', 'SUPPORTED', 'INCONCLUSIVE'), ('INCONCLUSIVE', 'SUPPORTED', 'INCONCLUSIVE'),
                   ('CLEAN', 'NOT_SUPPORTED', 'HYPOTHESIS_NOT_SUPPORTED'), ('CLEAN', 'INCONCLUSIVE', 'INCONCLUSIVE'),
                   ('INVALID', 'SUPPORTED', 'INVALID'), ('CLEAN', 'DATA_LOSS', 'DATA_LOSS')]:
    got = V.ab('PKGAB_VARIANT variant=control result=%s\n' % c, 'PKGAB_VARIANT variant=uninstall result=%s\n' % e)[0]
    check(got == want, 'A/B control=%s uninstall=%s -> %s' % (c, e, got))
check(V.ab('', 'PKGAB_VARIANT variant=uninstall result=SUPPORTED\n')[0] == 'INVALID', 'A/B with a missing variant -> INVALID')

# Kommandozeile
with tempfile.TemporaryDirectory() as d:
    def w(n, s):
        p = os.path.join(d, n)
        open(p, 'w').write(s)
        return p

    def lst(m):
        return ''.join('%s %d %s\n' % (v[0], v[1], k) for k, v in m.items()) + 'LIST-END\n'

    outs = {}
    for variant, kw, code, word in [('uninstall', dict(post=[(30000, 26_000, 26_300, 'OWNER_CHANGED')] + AFTER), 0, 'SUPPORTED'),
                                    ('control', dict(post=[(30000, 29_800, 30_100, 'OK')] + AFTER, owner_line=None), 0, 'CLEAN')]:
        log, hosts, instr, before, after = build(variant, **kw)
        p = subprocess.run([sys.executable, '-I', TOOL, 'variant', '--variant', variant, '--run', RUN, '--logcat', w('l', log), '--host-sampler', w('h', hosts),
                            '--instr-pre', w('i1', instr['pre']), '--instr-post', w('i2', instr['post']), '--instr-recheck', w('i3', instr['recheck']),
                            '--foreign-before', w('b', lst(before)), '--foreign-after', w('a', lst(after))], capture_output=True, text=True)
        outs[variant] = w('v-' + variant, p.stdout)
        check(p.returncode == code and ('PKGAB_VARIANT variant=%s result=%s' % (variant, word)) in p.stdout and 'PASS' not in p.stdout,
              'cli variant %s: exit %d, %s, no PASS' % (variant, p.returncode, word))
    p = subprocess.run([sys.executable, '-I', TOOL, 'ab', outs['control'], outs['uninstall']], capture_output=True, text=True)
    check(p.returncode == 0 and 'PKGAB_RESULT HYPOTHESIS_SUPPORTED' in p.stdout and 'PASS' not in p.stdout, 'cli ab: exit %d, HYPOTHESIS_SUPPORTED' % p.returncode)

n = len(done)
print('PKGAB_VERDICT_SELFTEST %s %d cases, %d failed' % ('FAIL' if fails else 'PASS', n, len(fails)))
sys.exit(1 if fails else 0)
