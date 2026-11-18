#!/usr/bin/env python3
"""Selbsttest für tools/pkgremoval_verdict.py mit Belegen aus dem deterministischen Gerätesimulator
tests/bootscan/pkgab_sim.py (eine Geräteuhr, widerspruchsfreie Zeitachse) und gezielten Verfälschungen (kein Gerät).

Jeder Fall nennt den Codex-Befund (NO-GO 1), den er absichert. Positive Fälle: SUPPORTED (veröffentlichte Zeilen ->
Besitzer NULL; ausstehende Zeile gelöscht -> Commit-Fehler), NOT_SUPPORTED, CLEAN. Negative Fälle: unbewiesene
Paketoperationen, fehlende Überschneidung, unpassende Fehler (failed:network), UNKNOWN/TRANSIENT im kausalen Fenster,
zeitlich mehrdeutige Zeilen, verspätet protokollierte Besitzerwechsel, Ereignisse unbekannter Sondendateien,
Widersprüche Logcat/Provider, verlorene Änderungszeilen, offene Änderungen, ungültige Schlussaufnahme, leere
Provider-Antwort, Absturzbelege, falsche Generationen, unbekannte Ergebniswerte. Nie PASS.

cases() wird auch von tests/bootscan/regress_pkgab_nogo1.py benutzt (alter gegen neuen Bewerter).
Aufruf: python3 -I tests/bootscan/test_pkgremoval_verdict.py
"""
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
TOOL = os.path.join(ROOT, 'tools', 'pkgremoval_verdict.py')
sys.path.insert(0, HERE)
import pkgab_sim as S  # noqa: E402

SUP = {'orphan': 'delayed-all', 'scan_after_pkg_end': 2000}            # zwei veröffentlichte Zeilen getroffen
SUP_PENDING = {'orphan': 'delayed-all', 'scan_after_pkg_end': 2400}    # dazu eine ausstehende Zeile gelöscht
NOTSUP = {'orphan': 'delayed-install1', 'scan_after_pkg_end': 2000}
AMBIG = {'orphan': 'delayed-all', 'scan_after_pkg_end': 1500}         # Anlegen einer Zeile im Beobachtungsintervall
CTRL = {'scan_after_pkg_end': 2000}
OVERLAP_CREATE = {'orphan': 'delayed-all', 'scan_after_pkg_end': 1900, 'create_ms': 400}   # create() von seq 30001 reicht in das Verwaisungsintervall


def dl_line(sim, seq):
    return next(l for l in sim['logcat'].splitlines() if ' BOOTSCAN DL ' in l and ' seq=%d ' % seq in l)


def sub_line(sim, old, new):
    assert old in sim['logcat'], old
    sim['logcat'] = sim['logcat'].replace(old, new, 1)
    return sim


def drop_lines(sim, pred):
    sim['logcat'] = '\n'.join(l for l in sim['logcat'].splitlines() if not pred(l)) + '\n'
    return sim


def add_line(sim, t, tag, msg, pid=1642, tid=2289):
    sim['logcat'] += '  %d.%03d  %d  %d I %s: %s\n' % (t // 1000, t % 1000, pid, tid, tag, msg)
    return sim


def mark_t(sim, event):
    return sim['dev'].marks[event]


def path(sim, seq):
    return S.DIR + 'BootScanProbe-%s-%d.mp4' % (sim['run'], seq)


def corrupt(seq):
    def f(dev):
        p = S.DIR + 'BootScanProbe-a1b2c3d4e5f6-%d.mp4' % seq
        dev.disk[p] = (S.SIZE, '0' * 64)
    return f


def cases():
    """-> [(befund, label, variante, sim-dict, prüfung(result, why, rows) -> bool)]."""
    C = []
    add = lambda f, label, variant, sim, ok: C.append((f, label, variant, sim, ok))
    res = lambda want: (lambda r, why, rows: r == want)
    cls = lambda r, rows, seq: {x['seq']: x['cls'] for x in rows}.get(str(seq))

    # ---- positive Abläufe
    add('positiv', 'delayed orphaning hits published rows of the new installation -> SUPPORTED', 'uninstall', S.simulate('uninstall', SUP),
        lambda r, why, rows: r == 'SUPPORTED' and any(w.startswith('CLAIM ') and 'does not prove' in w for w in why)
        and [x['cls'] for x in rows if x['gen'] == 'install1'] == ['ORPHANED_EXPECTED'] * 3)
    add('positiv', 'delayed orphaning deletes a pending row (commit failure) -> SUPPORTED with PUBLISH_FAILED', 'uninstall', S.simulate('uninstall', SUP_PENDING),
        lambda r, why, rows: r == 'SUPPORTED' and any(x['cls'] == 'PUBLISH_FAILED' and x['risk'] == 'at_risk' for x in rows))
    add('positiv', 'delayed orphaning only of the first installation, new rows seen own afterwards -> NOT_SUPPORTED', 'uninstall',
        S.simulate('uninstall', NOTSUP), res('NOT_SUPPORTED'))
    add('positiv', 'control: update during the scan, all rows OK and seen own after the scan end -> CLEAN', 'control', S.simulate('control', CTRL), res('CLEAN'))

    # ---- Befund 1: Paketoperationen beweisbar erfolgreich
    s = S.simulate('uninstall', SUP)
    add('1', 'uninstall of the test package with result Failure (rc=0) -> INVALID', 'uninstall',
        sub_line(s, 'op=uninstall-test attempt=1 rc=0 result=Success', 'op=uninstall-test attempt=1 rc=0 result=Failure__DELETE_FAILED_INTERNAL_ERROR_'), res('INVALID'))
    s = S.simulate('uninstall', SUP)
    add('1', 'uninstall of the app package with rc=1 -> INVALID', 'uninstall',
        sub_line(s, 'op=uninstall-app attempt=1 rc=0 result=Success', 'op=uninstall-app attempt=1 rc=1 result=Failure'), res('INVALID'))
    s = S.simulate('uninstall', SUP)
    add('1', 'absence after the uninstall not proven (package still listed) -> INVALID', 'uninstall',
        sub_line(s, 'event=absent pmApp=none pmTest=none listed=none', 'event=absent pmApp=none pmTest=none listed=present'), res('INVALID'))
    s = S.simulate('uninstall', SUP)
    add('1', 'only a uid change, no operation and absence markers -> INVALID', 'uninstall',
        drop_lines(s, lambda l: ('event=op-' in l and 'uninstall-' in l) or 'event=absent' in l), res('INVALID'))

    # ---- Befund 2: keine falsche Bestätigung
    s = S.simulate('uninstall', SUP_PENDING)
    fl = dl_line(s, 30002)
    add('2', 'publish failure of a different kind (commit not reached, reason network) never supports -> INCONCLUSIVE', 'uninstall',
        sub_line(s, fl, re.sub(r'commitStart=\d+ commitEnd=\d+ commit=ex:IOException', 'commitStart=-1 commitEnd=-1 commit=-', fl).replace('reason=storage', 'reason=network')),
        res('INCONCLUSIVE'))
    add('2', 'no overlap of mount scan and package step -> INCONCLUSIVE', 'uninstall',
        S.simulate('uninstall', {'orphan': 'delayed-all', 'scan_after_pkg_end': None}), res('INCONCLUSIVE'))
    add('2', 'orphaning not delayed (at the uninstall) is normal behaviour -> INCONCLUSIVE', 'uninstall',
        S.simulate('uninstall', {'orphan': 'immediate', 'scan_after_pkg_end': 2000}),
        lambda r, why, rows: r == 'INCONCLUSIVE' and 'not delayed' in why[0] and all(x['cls'] == 'ORPHANED_EXPECTED' for x in rows if x['gen'] == 'install1'))
    add('2', 'no orphaning observed -> INCONCLUSIVE', 'uninstall', S.simulate('uninstall', {'orphan': None, 'scan_after_pkg_end': 2000}),
        lambda r, why, rows: r == 'INCONCLUSIVE' and all(x['cls'] == 'NOT_ORPHANED' for x in rows if x['gen'] == 'install1'))
    s = S.simulate('uninstall', NOTSUP)   # at_risk-Zeile sonst unversehrt: nur die URI-Abfrage ist unzuverlässig
    q = next(l for l in s['logcat'].splitlines() if ' URIQ ' in l and ' seq=30000 off=-1 ' in l)
    add('2', 'UNKNOWN (recheck query error) of an otherwise intact at-risk row stays uncertainty -> INCONCLUSIVE, not NOT_SUPPORTED', 'uninstall',
        sub_line(s, q, q.split(' res=')[0] + ' res=error:SecurityException'),
        lambda r, why, rows: r == 'INCONCLUSIVE' and cls(r, rows, 30000) == 'UNKNOWN')
    s = S.simulate('uninstall', SUP)
    q = next(l for l in s['logcat'].splitlines() if ' URIQ ' in l and ' seq=20001 off=250 ' in l)
    add('2', 'first installation not valid (pre row invisible at +250 ms) -> INCONCLUSIVE', 'uninstall',
        sub_line(s, q, q.split(' res=')[0] + ' res=none'), lambda r, why, rows: r == 'INCONCLUSIVE' and 'first installation' in ' '.join(why))

    # ---- Befund 3: zeilenbezogene Besitzer-Korrelation
    add('3', 'download created inside the orphaning observation interval is ambiguous, not at risk -> INCONCLUSIVE', 'uninstall',
        S.simulate('uninstall', AMBIG), lambda r, why, rows: r == 'INCONCLUSIVE' and any(x['risk'] == 'ambiguous' for x in rows))
    s = S.simulate('uninstall', {'orphan': 'immediate', 'scan_after_pkg_end': 2000, 'owner_log': False})
    late = mark_t(s, 'install2-end') + 3000
    for k in range(3):   # verspätet protokolliert: Logzeile nach der Neuinstallation, Provider sah NULL schon davor
        add_line(s, late + k, 'MediaProvider', 'Invalidating LocalCallingIdentity cache for package null. Reason: owner_package_changed:' + path(s, 20000 + k))
    add('3', 'owner change logged late: no exact time from the log line, provider shows it before the reinstall -> INCONCLUSIVE', 'uninstall', s,
        lambda r, why, rows: r == 'INCONCLUSIVE' and 'not delayed' in why[0])
    s = S.simulate('uninstall', SUP)
    add('3', 'owner change for an unknown probe file -> INVALID', 'uninstall',
        add_line(s, mark_t(s, 'install2-end') + 50, 'MediaProvider', 'Invalidating LocalCallingIdentity cache for package null. Reason: owner_package_changed:'
                 + S.DIR + 'BootScanProbe-a1b2c3d4e5f6-39999.mp4'), res('INVALID'))
    s = S.simulate('uninstall', NOTSUP)
    add('3', 'owner change logged for a row the provider keeps showing as own -> INCONCLUSIVE (contradiction)', 'uninstall',
        add_line(s, mark_t(s, 'install2-end') + 700, 'MediaProvider', 'Invalidating LocalCallingIdentity cache for package null. Reason: owner_package_changed:'
                 + path(s, 30000)), lambda r, why, rows: r == 'INCONCLUSIVE' and 'contradict' in ' '.join(why))
    s = S.simulate('control', CTRL)
    add('3', 'control: owner change of a probe row without uninstall -> ANOMALY', 'control',
        add_line(s, mark_t(s, 'update-end') + 900, 'MediaProvider', 'Invalidating LocalCallingIdentity cache for package null. Reason: owner_package_changed:'
                 + path(s, 30000)), res('ANOMALY'))

    # ---- Befund 4: Aufnahmen streng
    s = S.simulate('uninstall', SUP)
    first_priv = next(l for l in s['logcat'].splitlines() if ' BOOTSCAN PRIV ' in l and 'phase' not in l and ' n=2 ' in l)
    add('4', 'lost PRIV change line (digest mismatch) -> INVALID', 'uninstall', drop_lines(s, lambda l: l == first_priv), res('INVALID'))
    s = S.simulate('uninstall', SUP)
    lines = s['logcat'].splitlines()
    end_post = next(i for i, l in enumerate(lines) if ' BOOTSCAN END ' in l and 'phase=post' in l)
    lines.insert(end_post, lines[end_post].split('BOOTSCAN: ')[0] + 'BOOTSCAN: BOOTSCAN DISK run=a1b2c3d4e5f6 n=99 +/storage/x|1|' + 'a' * 64)
    s['logcat'] = '\n'.join(lines) + '\n'
    add('4', 'open change line at the end of a phase -> INVALID', 'uninstall', s, res('INVALID'))
    s = S.simulate('uninstall', SUP)
    lines = s['logcat'].splitlines()
    rs = next(i for i, l in enumerate(lines) if ' BOOTSCAN START ' in l and 'phase=recheck' in l)
    li = max(i for i, l in enumerate(lines) if ' BOOTSCAN SNAP ' in l and 'final=1' in l)
    n = re.search(r' n=(\d+) ', lines[li]).group(1)
    lines[li] = re.sub(r'priv=ok privN=\d+ privH=\w+ cross=ok', 'priv=bad:frame-Q-END_rc-1 privN=-1 privH=- cross=na', lines[li])
    lines = [l for i, l in enumerate(lines) if not (rs < i < li and ' BOOTSCAN PRIV ' in l and ' n=%s ' % n in l)]
    s['logcat'] = '\n'.join(lines) + '\n'
    add('4', 'final snapshot of the recheck not valid -> never replaced by an older valid one, no SUPPORTED', 'uninstall', s,
        lambda r, why, rows: r == 'INCONCLUSIVE' and all(x['cls'] == 'UNKNOWN' for x in rows))
    s = S.simulate('uninstall', SUP, before_recheck=corrupt(30005))
    end_pre = next(l for l in s['logcat'].splitlines() if ' BOOTSCAN END ' in l and 'phase=pre' in l)
    add('4', 'proven data loss stays visible although other evidence is incomplete -> DATA_LOSS', 'uninstall',
        drop_lines(s, lambda l: l == end_pre), lambda r, why, rows: r == 'DATA_LOSS' and 'incomplete' in why[-1])
    add('4', 'app view contradicts the privileged view (other owner, digests consistent) -> INVALID', 'uninstall',
        S.simulate('uninstall', dict(SUP, app_owner_lie=True)), lambda r, why, rows: r == 'INVALID' and 'contradicts' in why[0])

    # ---- Befund 5: Generationen und negative Aussagen
    s = S.simulate('uninstall', SUP)
    st = next(l for l in s['logcat'].splitlines() if ' BOOTSCAN START ' in l and 'phase=pre' in l)
    add('5', 'phase pre ran with another app uid than install1 -> INVALID (generation not proven)', 'uninstall',
        sub_line(s, st, re.sub(r'appUid=\d+', 'appUid=99999', st)), res('INVALID'))
    s = S.simulate('uninstall', SUP)
    fl = dl_line(s, 20001)
    add('5', 'URI and id of a download disagree -> INVALID', 'uninstall', sub_line(s, fl, re.sub(r'(uri=\S+/)(\d+)', r'\g<1>1', fl)), res('INVALID'))
    s = S.simulate('uninstall', NOTSUP)
    q = next(l for l in s['logcat'].splitlines() if ' URIQ ' in l and ' seq=30000 off=250 ' in l)
    add('5', 'TRANSIENT at-risk row is not "all OK" -> INCONCLUSIVE', 'uninstall', sub_line(s, q, q.split(' res=')[0] + ' res=none'),
        lambda r, why, rows: r == 'INCONCLUSIVE' and cls(r, rows, 30000) == 'TRANSIENT')
    s = S.simulate('uninstall', NOTSUP)
    t = mark_t(s, 'install2-end') + 4000
    add_line(s, t, 'AndroidRuntime', 'FATAL EXCEPTION: main', 4400, 4400)
    add_line(s, t + 1, 'AndroidRuntime', 'Process: com.android.providers.media.module, PID: 4400', 4400, 4400)
    add('5', 'MediaProvider crash during the window -> INCONCLUSIVE, never NOT_SUPPORTED', 'uninstall', s, res('INCONCLUSIVE'))
    # ======== Codex-NO-GO 2 (zweiter Review von 5627df2)
    # ---- P1-1: Schlussaufnahme verbindlich, frühere URI-Abfrage überstimmt sie nicht
    add('P1-1', 'control: URI visible in the recheck, row lost afterwards -> not CLEAN', 'control',
        S.simulate('control', dict(CTRL, recheck_mutation=(30001, 'lost'))), lambda r, why, rows: r == 'ANOMALY' and cls(r, rows, 30001) == 'LOST')
    add('P1-1', 'URI visible in the recheck, owner NULL afterwards -> not NOT_SUPPORTED', 'uninstall',
        S.simulate('uninstall', dict(NOTSUP, recheck_mutation=(30000, 'null'))),
        lambda r, why, rows: r == 'INCONCLUSIVE' and cls(r, rows, 30000) == 'OWNER_CHANGED')
    add('P1-1', 'URI visible in the recheck, pending=1 afterwards (late row) -> not SUPPORTED', 'uninstall',
        S.simulate('uninstall', dict(SUP, recheck_mutation=(30008, 'pending'))),
        lambda r, why, rows: r == 'INCONCLUSIVE' and cls(r, rows, 30008) == 'STATE_CHANGED')

    # ---- P1-2: Dateien unabhängig vom Providerzustand; nur nachgewiesene passende Fehler
    def residual(kind):
        def f(dev):
            p = S.DIR + 'BootScanProbe-a1b2c3d4e5f6-30002.mp4'
            if kind == 'truncated':
                dev.disk[p] = (S.SIZE // 2, S.sha(30002)[::-1])
            elif kind == 'altered':
                dev.disk[p] = (S.SIZE, '0' * 64)
            elif kind == 'missing':
                del dev.disk[p]
        return f
    add('P1-2', 'failed publish with complete, intact residual file -> SUPPORTED', 'uninstall', S.simulate('uninstall', SUP_PENDING, before_recheck=residual('intact')),
        lambda r, why, rows: r == 'SUPPORTED' and 'residual file intact' in {x['seq']: x['why'] for x in rows}['30002'])
    for kind in ('truncated', 'altered', 'missing'):
        add('P1-2', 'failed publish with %s residual file -> not SUPPORTED, not DATA_LOSS (cleanup may be regular)' % kind, 'uninstall',
            S.simulate('uninstall', SUP_PENDING, before_recheck=residual(kind)),
            lambda r, why, rows, k=kind: r == 'INCONCLUSIVE' and ('residual file %s' % k) in {x['seq']: x['why'] for x in rows}['30002'])
    s = S.simulate('uninstall', SUP, before_recheck=corrupt(30005))
    lines = s['logcat'].splitlines()
    rs = next(i for i, l in enumerate(lines) if ' BOOTSCAN START ' in l and 'phase=recheck' in l)
    li = max(i for i, l in enumerate(lines) if ' BOOTSCAN SNAP ' in l and 'final=1' in l)
    n = re.search(r' n=(\d+) ', lines[li]).group(1)
    lines[li] = re.sub(r'priv=ok privN=\d+ privH=\w+ cross=ok', 'priv=bad:frame-Q-END_rc-1 privN=-1 privH=- cross=na', lines[li])
    s['logcat'] = '\n'.join(l for i, l in enumerate(lines) if not (rs < i < li and ' BOOTSCAN PRIV ' in l and ' n=%s ' % n in l)) + '\n'
    add('P1-2', 'final privileged snapshot invalid, valid final disk snapshot shows a damaged published file -> DATA_LOSS', 'uninstall', s,
        lambda r, why, rows: r == 'DATA_LOSS' and any('30005' in w for w in why))
    for label, old_, new_ in [('reason=internal', 'reason=storage', 'reason=internal'),
                              ('another exception in the commit', 'commit=ex:IOException', 'commit=ex:IllegalStateException')]:
        s = S.simulate('uninstall', SUP_PENDING)
        fl = dl_line(s, 30002)
        add('P1-2', 'engine error not explicitly matching (%s) never supports -> INCONCLUSIVE' % label, 'uninstall', sub_line(s, fl, fl.replace(old_, new_)),
            res('INCONCLUSIVE'))

    s = S.simulate('control', CTRL)    # Wechselwirkung: Provider bestätigt alles, die Schluss-Dateiaufnahme ist ungültig
    lines = s['logcat'].splitlines()
    rs = next(i for i, l in enumerate(lines) if ' BOOTSCAN START ' in l and 'phase=recheck' in l)
    li = max(i for i, l in enumerate(lines) if ' BOOTSCAN DSNAP ' in l and 'final=1' in l)
    n = re.search(r' n=(\d+) ', lines[li]).group(1)
    lines[li] = re.sub(r'disk=ok diskN=\d+ diskH=\w+', 'disk=bad:frame diskN=-1 diskH=-', lines[li])
    s['logcat'] = '\n'.join(l for i, l in enumerate(lines) if not (rs < i < li and ' BOOTSCAN DISK ' in l and ' n=%s ' % n in l)) + '\n'
    add('P1-2', 'final disk snapshot invalid, provider confirms every row -> files unconfirmed, never CLEAN', 'control', s,
        lambda r, why, rows: r == 'INCONCLUSIVE' and all(x['cls'] == 'UNKNOWN' for x in rows))

    # ---- P1-3: Prüfsummen und Parser fail-closed
    s = S.simulate('uninstall', SUP)
    ds = next(l for l in s['logcat'].splitlines() if ' BOOTSCAN DSNAP ' in l and 'phase' not in l and 'disk=ok' in l)
    add('P1-3', 'valid disk snapshot with diskH=- -> INVALID', 'uninstall', sub_line(s, ds, re.sub(r'diskH=\w+', 'diskH=-', ds)), res('INVALID'))
    s = S.simulate('uninstall', SUP)
    sn = next(l for l in s['logcat'].splitlines() if ' BOOTSCAN SNAP ' in l and 'priv=ok' in l)
    add('P1-3', 'valid snapshot without privH field -> INVALID', 'uninstall', sub_line(s, sn, re.sub(r' privH=\w+', '', sn)), res('INVALID'))
    s = S.simulate('uninstall', SUP)
    pl = next(l for l in s['logcat'].splitlines() if ' BOOTSCAN PRIV ' in l and ' +' in l)
    add('P1-3', 'two change operations for the same key in one frame -> INVALID', 'uninstall', sub_line(s, pl, pl + '\n' + pl), res('INVALID'))

    # ---- P1-4: zeitliche Kausalität und Zeitachsen
    add('P1-4', 'create() overlapping the orphaning interval is not "before" it -> no SUPPORTED', 'uninstall', S.simulate('uninstall', OVERLAP_CREATE),
        lambda r, why, rows: r == 'INCONCLUSIVE' and any(x['seq'] == '30001' and x['risk'] == 'ambiguous' and x['lo'] < x['O'][0] <= x['lo_end'] for x in rows))
    for label, seq, field, delta in [('insertEnd before insertStart', 20001, 'insertEnd', -10), ('commitEnd before commitStart', 30000, 'commitEnd', -20),
                                     ('done before commitEnd', 30001, 'done', -15)]:
        s = S.simulate('uninstall', NOTSUP)
        fl = dl_line(s, seq)
        if field == 'insertEnd':
            v = int(re.search(r'insertStart=(\d+)', fl).group(1)) + delta
        elif field == 'commitEnd':
            v = int(re.search(r'commitStart=(\d+)', fl).group(1)) + delta
        else:
            v = int(re.search(r'commitEnd=(\d+)', fl).group(1)) + delta
        add('P1-4', 'impossible time order: %s -> INVALID' % label, 'uninstall', sub_line(s, fl, re.sub(r'%s=\d+' % field, '%s=%d' % (field, v), fl)),
            res('INVALID'))
    s = S.simulate('uninstall', NOTSUP)
    sn = next(l for l in s['logcat'].splitlines() if ' BOOTSCAN SNAP ' in l and 'phase' not in l and ' n=3 ' in l)
    t0 = int(re.search(r' t=(\d+)', sn).group(1))
    add('P1-4', 'snapshot ending before it began -> INVALID', 'uninstall', sub_line(s, sn, re.sub(r'tEnd=\d+', 'tEnd=%d' % (t0 - 5), sn)), res('INVALID'))
    s = S.simulate('uninstall', NOTSUP)
    q = next(l for l in s['logcat'].splitlines() if ' URIQ ' in l and ' seq=30001 off=2000 ' in l)
    dt = int(re.search(r'done=(\d+)', dl_line(s, 30001)).group(1))
    add('P1-4', 'URI check before the download was done -> INVALID', 'uninstall', sub_line(s, q, re.sub(r' t=\d+', ' t=%d' % (dt - 100), q)), res('INVALID'))
    s = S.simulate('uninstall', NOTSUP)
    h = s['hosts'].splitlines()
    i = next(k for k, l in enumerate(h) if l.startswith('QT-END'))
    a = int(next(l for l in h[:i] if l.startswith('QT-BEGIN')).split('t=')[1].split()[0])
    h[i] = 'QT-END rc=0 t=%d' % (a - 50)
    s['hosts'] = '\n'.join(h) + '\n'
    add('P1-4', 'host query ending before it began -> INVALID', 'uninstall', s, res('INVALID'))

    # ---- P2-5: reguläre Prozessbeendigungen vs. echte Abstürze
    add('P2-5', 'regular "finished inst" kills after each instrumentation keep the definitive result (SUPPORTED)', 'uninstall',
        S.simulate('uninstall', SUP), res('SUPPORTED'))
    add('P2-5', 'process killed by the package update (installPackageLI) is expected -> CLEAN', 'control',
        S.simulate('control', dict(CTRL, pkg_step_kill=True)), res('CLEAN'))
    add('P2-5', 'process killed by the uninstall (deletePackageX) is expected -> NOT_SUPPORTED stays', 'uninstall',
        S.simulate('uninstall', dict(NOTSUP, pkg_step_kill=True)), res('NOT_SUPPORTED'))
    for kind in ('app_fatal', 'app_kill_lmk', 'provider_died', 'oom', 'anr'):
        add('P2-5', 'real crash evidence (%s) during post -> INCONCLUSIVE' % kind, 'uninstall', S.simulate('uninstall', dict(NOTSUP, crash=kind)),
            lambda r, why, rows: r == 'INCONCLUSIVE' and 'crash' in why[0])
    return C


def instr_of(sim):
    return {k: sim['instr'][k] for k in ('pre', 'post', 'recheck')}


if __name__ == '__main__':
    sys.path.insert(0, os.path.join(ROOT, 'tools'))
    import pkgremoval_verdict as V
    fails, done = [], []

    def check(cond, what):
        done.append(what)
        print(('ok   ' if cond else 'FAIL ') + what)
        if not cond:
            fails.append(what)

    for finding, label, variant, sim, ok in cases():
        r, why, rows, notes = V.variant_verdict(variant, sim['run'], sim['logcat'], sim['hosts'], instr_of(sim), sim['before'], sim['after'])
        check(ok(r, why, rows) and r != 'PASS', '[%s] %s (%s: %s)' % (finding, label, r, (why[0] if why else '')[:110]))

    # Host-Abfragen: leere Antwort nur mit "No result found.", Rahmen und Endzeit nötig
    notes = []
    h = V.host_samples('QT-BEGIN t=5 res=ms\nQT-END rc=0 t=9\nQT-BEGIN t=10 res=ms\nNo result found.\nQT-END rc=0 t=12\n'
                       'QT-BEGIN t=20 res=ms\nNo result found.\nQT-END rc=0\nQT-BEGIN t=30 res=ms\nError while accessing provider\nQT-END rc=0 t=31\n', notes)
    check(h == [(10, 12, {})] and '3 discarded' in notes[0], '[4] empty provider answer accepted only with "No result found.", frame and end time')

    # Dateien
    s = S.simulate('uninstall', SUP, before_recheck=corrupt(30005))
    check(V.variant_verdict('uninstall', s['run'], s['logcat'], s['hosts'], instr_of(s), s['before'], s['after'])[0] == 'DATA_LOSS',
          '[8] probe file content changed -> DATA_LOSS')
    for kind in ('removed', 'modified', 'added'):
        s = S.simulate('control', CTRL)
        after = {} if kind == 'removed' else ({k: ('b' * 64, 36) for k in s['before']} if kind == 'modified'
                                              else dict(s['before'], **{'/storage/emulated/0/Movies/.thumbnails/.nomedia': ('e' * 64, 0)}))
        r, why, rows, notes = V.variant_verdict('control', s['run'], s['logcat'], s['hosts'], instr_of(s), s['before'], after)
        want = 'CLEAN' if kind == 'added' else 'DATA_LOSS'
        check(r == want and (kind != 'added' or any('FOREIGN_ADDED' in n for n in notes)), '[8] foreign file %s -> %s (%s)' % (kind, want, r))

    # A/B nur aus definierten, gültigen Arm-Ergebnissen
    line = lambda v, run, res: 'PKGAB_VARIANT variant=%s run=%s result=%s\n' % (v, run, res)
    for c, e, want in [('CLEAN', 'SUPPORTED', 'HYPOTHESIS_SUPPORTED'), ('CLEAN', 'NOT_SUPPORTED', 'HYPOTHESIS_NOT_SUPPORTED'),
                       ('ANOMALY', 'NOT_SUPPORTED', 'INCONCLUSIVE'), ('INCONCLUSIVE', 'NOT_SUPPORTED', 'INCONCLUSIVE'),
                       ('ANOMALY', 'SUPPORTED', 'INCONCLUSIVE'), ('CLEAN', 'INCONCLUSIVE', 'INCONCLUSIVE'), ('INVALID', 'SUPPORTED', 'INVALID'),
                       ('INVALID', 'DATA_LOSS', 'DATA_LOSS'), ('CLEAN', 'PASS', 'INVALID'), ('CLEAN', 'SUPPORTED_MAYBE', 'INVALID'),
                       ('SUPPORTED', 'SUPPORTED', 'INVALID')]:
        got, why, arms = V.ab(line('control', '111111111111', c), line('uninstall', '222222222222', e))
        check(got == want and arms['control'][0] == c and arms['uninstall'][0] == e, '[5] A/B control=%s uninstall=%s -> %s (%s)' % (c, e, want, got))
    check(V.ab(line('control', '111111111111', 'CLEAN'), line('uninstall', '111111111111', 'SUPPORTED'))[0] == 'INVALID',
          '[5] A/B with the same run in both arms -> INVALID')
    check(V.ab('', line('uninstall', '222222222222', 'SUPPORTED'))[0] == 'INVALID', '[5] A/B with a missing arm -> INVALID')
    check(V.ab(line('control', '111111111111', 'CLEAN') * 2, line('uninstall', '222222222222', 'SUPPORTED'))[0] == 'INVALID',
          '[5] A/B with two results for one arm -> INVALID')

    # Kommandozeile
    with tempfile.TemporaryDirectory() as d:
        def w(n, s_):
            p = os.path.join(d, n)
            open(p, 'w').write(s_)
            return p

        def lst(m):
            return ''.join('%s %d %s\n' % (v[0], v[1], k) for k, v in m.items()) + 'LIST-END\n'

        outs = {}
        for variant, sc, code, word in [('uninstall', SUP, 0, 'SUPPORTED'), ('control', CTRL, 0, 'CLEAN')]:
            s = S.simulate(variant, sc, run='a1b2c3d4e5f6' if variant == 'uninstall' else 'b1b2c3d4e5f6')
            p = subprocess.run([sys.executable, '-I', TOOL, 'variant', '--variant', variant, '--run', s['run'], '--logcat', w('l', s['logcat']),
                                '--host-sampler', w('h', s['hosts']), '--instr-pre', w('i1', s['instr']['pre']), '--instr-post', w('i2', s['instr']['post']),
                                '--instr-recheck', w('i3', s['instr']['recheck']), '--foreign-before', w('b', lst(s['before'])),
                                '--foreign-after', w('a', lst(s['after']))], capture_output=True, text=True)
            outs[variant] = w('v-' + variant, p.stdout)
            check(p.returncode == code and ('PKGAB_VARIANT variant=%s run=%s result=%s' % (variant, s['run'], word)) in p.stdout
                  and 'PASS' not in p.stdout, 'cli variant %s: exit %d, %s, no PASS' % (variant, p.returncode, word))
        p = subprocess.run([sys.executable, '-I', TOOL, 'ab', outs['control'], outs['uninstall']], capture_output=True, text=True)
        check(p.returncode == 0 and 'PKGAB_RESULT HYPOTHESIS_SUPPORTED' in p.stdout and 'PKGAB_ARM variant=control' in p.stdout
              and 'does not prove' in p.stdout, 'cli ab: exit %d, HYPOTHESIS_SUPPORTED with both arms and the claim limit' % p.returncode)

    print('PKGAB_VERDICT_SELFTEST %s %d cases, %d failed' % ('FAIL' if fails else 'PASS', len(done), len(fails)))
    sys.exit(1 if fails else 0)
