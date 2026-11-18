#!/usr/bin/env python3
"""Selbsttest für tools/pkgab_observe.py (Beobachter ohne Kausalurteil) mit Eingaben aus dem deterministischen
Gerätesimulator tests/bootscan/pkgab_sim.py und gezielten Verfälschungen. Kein Gerät.

Geprüft wird: Vollständigkeit getrennt von der Überschneidung (Mess-Voraussetzung); Auffälligkeiten bleiben erhalten
(spätere unauffällige Beobachtungen und ungültige Schlussaufnahmen entfernen nichts); nachgewiesener Dateischaden auch bei
unvollständiger Messung; fehlgeschlagene Downloads sind kein Datenverlust; Crashblöcke vollständig und wörtlich; jeder
Datensatz ist über run, Phase/seq/pid, Zeit und Quellzeile rückführbar; fehlende Werte sind null; deterministische
Ausgabe; Upload-Prüfung auf Zugangsdaten; keine Urteilswörter.
Aufruf: python3 -I tests/bootscan/test_pkgab_observe.py
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
TOOL = os.path.join(ROOT, 'tools', 'pkgab_observe.py')
sys.path.insert(0, HERE)
import pkgab_sim as S  # noqa: E402

FORBIDDEN = re.compile(r'\b(SUPPORTED|NOT_SUPPORTED|CLEAN|HYPOTHESIS\w*|PASS|ANOMALY|DIAGNOSED)\b')
CTRL = {'scan_after_pkg_end': 2000}
SUP = {'orphan': 'delayed-all', 'scan_after_pkg_end': 2000}
SUP_PENDING = {'orphan': 'delayed-all', 'scan_after_pkg_end': 2400}
TMP = tempfile.mkdtemp()
fails, done, outputs = [], [], []


def check(cond, what):
    done.append(what)
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


def observe(sim, variant, name, abort=None, drop=(), dump=None):
    d = os.path.join(TMP, name)
    os.makedirs(d)
    lst = lambda m: ''.join('%s %d %s\n' % (v[0], v[1], k) for k, v in m.items()) + 'LIST-END\n'
    files = {'logcat': ('logcat.txt', sim['logcat']), 'logcat-dump': ('logcat-all.txt', sim['logcat'] if dump is None else dump),
             'host-sampler': ('host-sampler.txt', sim['hosts']), 'pkgops': ('pkgops.txt', 'op=install-app attempt=1 rc=0 result=Success output=Success\n'),
             'instr-pre': ('instr-pre.txt', sim['instr']['pre']), 'instr-post': ('instr-post.txt', sim['instr']['post']),
             'instr-recheck': ('instr-recheck.txt', sim['instr']['recheck']),
             'foreign-before': ('foreign-before.txt', sim.get('before_text') or lst(sim['before'])),
             'foreign-after': ('foreign-after.txt', sim.get('after_text') or lst(sim['after']))}
    args = []
    for k, (fn, text) in files.items():
        p = os.path.join(d, fn)
        if k not in drop:
            open(p, 'w').write(text)
        args += ['--' + k, p]
    cmd = [sys.executable, '-I', TOOL, 'observe', '--variant', variant, '--run', sim['run'], '--out', os.path.join(d, 'report')] + args
    if abort:
        cmd += ['--abort', abort]
    p = subprocess.run(cmd, capture_output=True, text=True)
    recs = [json.loads(l) for l in open(os.path.join(d, 'report', 'observations.jsonl'))] if os.path.exists(os.path.join(d, 'report', 'observations.jsonl')) else []
    outputs.append(p.stdout + p.stderr + ''.join(json.dumps(r) for r in recs))
    return {'rc': p.returncode, 'out': p.stdout + p.stderr, 'recs': recs, 'sum': recs[-1] if recs else {}, 'dir': d, 'files': files,
            'find': [r for r in recs if r['type'] == 'finding'], 'src': {r['name']: r for r in recs if r['type'] == 'source'}}


def kinds(r, kind, **f):
    return [x for x in r['find'] if x['kind'] == kind and all(x.get(k) == v for k, v in f.items())]


def lines_of(sim, cond):
    return [l for l in sim['logcat'].splitlines() if cond(l)]


def edit(sim, old, new):
    assert old in sim['logcat'], old[:80]
    sim['logcat'] = sim['logcat'].replace(old, new, 1)
    return sim


def drop_line(sim, line):
    sim['logcat'] = '\n'.join(l for l in sim['logcat'].splitlines() if l != line) + '\n'
    return sim


def corrupt(seq):
    def f(dev):
        dev.disk[S.DIR + 'BootScanProbe-a1b2c3d4e5f6-%d.mp4' % seq] = (S.SIZE, '0' * 64)
    return f


def invalidate_final(sim, what):
    """Schlussaufnahme der Nachprüfung ungültig machen (priv bzw. disk), samt ihrer Änderungszeilen."""
    L = sim['logcat'].splitlines()
    rs = next(i for i, l in enumerate(L) if ' BOOTSCAN START ' in l and 'phase=recheck' in l)
    tag, kind = ('SNAP', 'PRIV') if what == 'priv' else ('DSNAP', 'DISK')
    li = max(i for i, l in enumerate(L) if (' BOOTSCAN %s ' % tag) in l and 'final=1' in l)
    n = re.search(r' n=(\d+) ', L[li]).group(1)
    L[li] = re.sub(r'priv=ok privN=\d+ privH=\w+ cross=ok', 'priv=bad:frame-Q-END_rc-1 privN=-1 privH=- cross=na', L[li]) if what == 'priv' \
        else re.sub(r'disk=ok diskN=\d+ diskH=\w+', 'disk=bad:frame diskN=-1 diskH=-', L[li])
    sim['logcat'] = '\n'.join(l for i, l in enumerate(L) if not (rs < i < li and (' BOOTSCAN %s ' % kind) in l and ' n=%s ' % n in l)) + '\n'
    return sim


# ---- vollständige Abläufe: nur Messstatus, kein Urteil
r = observe(S.simulate('control', CTRL), 'control', 'control')
check(r['rc'] == 0 and r['sum']['measurement'] == 'COMPLETE' and r['sum']['data_loss'] == 'NONE_PROVEN' and r['sum']['overlap'] == 'PRESENT'
      and not [f for f in r['find'] if f['kind'].startswith(('FILE_', 'ROW_', 'URI_'))],
      'control complete: exit 0, COMPLETE, NONE_PROVEN, overlap PRESENT, no row/file findings (%s)' % r['sum'].get('findings_by_kind'))
check(len([x for x in r['recs'] if x['type'] == 'pkgop']) == 4 and len([x for x in r['recs'] if x['type'] == 'download']) == 15
      and any(x['type'] == 'scan' for x in r['recs']), 'package operations, downloads and scans are recorded as records')
check(any('no approval of the helper' in n for n in r['sum']['notes']) and any('no causal conclusion' in n for n in r['sum']['notes']),
      'summary states: exit 0 is no approval, no causal conclusion')
r = observe(S.simulate('uninstall', SUP), 'uninstall', 'uninstall')
check(r['rc'] == 0 and r['sum']['measurement'] == 'COMPLETE' and kinds(r, 'OWNER_EVENT') and kinds(r, 'ROW_OWNER_NOT_APP', seq=30000)
      and kinds(r, 'ROW_OWNER_NOT_APP', seq=20000), 'uninstall with delayed orphaning: owner events and owner changes are listed as facts, no verdict')

# ---- Regression: Dateischaden mit fehlgeschlagener Schlussaufnahme
s = invalidate_final(invalidate_final(S.simulate('uninstall', SUP, before_recheck=corrupt(30005)), 'priv'), 'disk')
r = observe(s, 'uninstall', 'damage_final_failed')
check(r['rc'] == 3 and r['sum']['data_loss'] == 'PROVEN' and r['sum']['measurement'] == 'INCOMPLETE' and kinds(r, 'FILE_DAMAGED', seq=30005, verified=True),
      'file damage seen in a verified frame stays PROVEN although the final snapshots failed (exit %d, %s)' % (r['rc'], r['sum'].get('incomplete_sources')))
check(kinds(r, 'ROW_OWNER_NOT_APP', seq=30000), 'earlier row findings survive the failed final snapshot')

# ---- Regression: verschachtelte Fatal-Exception-Blöcke, wörtlich und vollständig
s = S.simulate('control', CTRL)
t = s['dev'].marks['update-end'] + 5000
crash = [('AndroidRuntime', 'FATAL EXCEPTION: main'), ('AndroidRuntime', 'Process: com.android.providers.media.module, PID: 4400'),
         ('AndroidRuntime', 'java.lang.IllegalStateException: outer'), ('AndroidRuntime', '\tat a.B.c(B.java:1)'),
         ('AndroidRuntime', 'Caused by: java.lang.RuntimeException: inner'), ('AndroidRuntime', '\tat d.E.f(E.java:2)'),
         ('AndroidRuntime', '\t... 5 more'), ('AndroidRuntime', 'FATAL EXCEPTION: worker'), ('AndroidRuntime', 'Caused by: java.lang.OutOfMemoryError: x')]
blk = ['  %d.%03d  4400  4400 E %s: %s' % ((t + i) // 1000, (t + i) % 1000, tag, msg) for i, (tag, msg) in enumerate(crash)]
other = '  %d.%03d  4500  4500 I SomeTag: interleaved' % ((t + 3) // 1000, (t + 3) % 1000)
native = ['  %d.%03d  4600  4600 F libc: Fatal signal 11 (SIGSEGV)' % ((t + 20) // 1000, (t + 20) % 1000),
          '  %d.%03d  4600  4600 F DEBUG: *** *** *** *** ***' % ((t + 21) // 1000, (t + 21) % 1000),
          '  %d.%03d  4600  4600 F DEBUG: backtrace:' % ((t + 22) // 1000, (t + 22) % 1000)]
s['logcat'] += '\n'.join(blk[:3] + [other] + blk[3:] + native) + '\n'
r = observe(s, 'control', 'nested_crash')
cb = [x for x in r['recs'] if x['type'] == 'crash_block']
check(len(cb) == 2 and cb[0]['lines'] == blk and cb[0]['process'] == 'com.android.providers.media.module' and cb[1]['lines'] == native
      and len(kinds(r, 'CRASH_BLOCK')) == 2, 'nested fatal blocks kept verbatim and complete (incl. Caused by, nested FATAL), interleaved lines excluded')
check(r['sum']['measurement'] == 'COMPLETE' and r['rc'] == 0 and cb[0]['window'], 'a crash is a listed fact with its time window, not a measurement verdict')

# ---- Regression: widersprüchliche Aufnahmezeiten
s = S.simulate('uninstall', SUP)
sn = lines_of(s, lambda l: ' BOOTSCAN SNAP ' in l and ' n=4 ' in l and 'phase' not in l)[1]
t0 = int(re.search(r' t=(\d+)', sn).group(1))
r = observe(edit(s, sn, re.sub(r'tEnd=\d+', 'tEnd=%d' % (t0 - 7), sn)), 'uninstall', 'snap_times')
check(r['rc'] == 2 and r['sum']['measurement'] == 'INCOMPLETE' and any(n.startswith('phase_') for n in r['sum']['incomplete_sources'])
      and kinds(r, 'ROW_OWNER_NOT_APP', seq=30000), 'contradictory snapshot times -> INCOMPLETE, findings kept (%s)' % r['sum']['incomplete_sources'])

# ---- Regression: fehlerhafte Paketoperationen
s = S.simulate('uninstall', SUP)
r = observe(edit(s, 'op=uninstall-app attempt=1 rc=0 result=Success', 'op=uninstall-app attempt=1 rc=1 result=Failure'), 'uninstall', 'pkgop_failed')
check(r['rc'] == 2 and kinds(r, 'PKGOP_FAILED', op='uninstall-app', rc=1) and 'package_steps' in r['sum']['incomplete_sources'],
      'failed uninstall operation -> finding PKGOP_FAILED and package_steps incomplete')
s = S.simulate('control', CTRL)
first = lines_of(s, lambda l: 'event=op-begin op=install-app attempt=1' in l)[0]
fail = [first.replace('attempt=1', 'attempt=0'), first.replace('event=op-begin', 'event=op-end').replace('attempt=1', 'attempt=0 rc=1 result=Failure__INSTALL_FAILED')]
r = observe(edit(s, first, '\n'.join(fail + [first])), 'control', 'install_retry')
check(kinds(r, 'PKGOP_FAILED', op='install-app', rc=1) and r['sum']['measurement'] == 'COMPLETE',
      'failed install attempt before a successful retry stays a finding (measurement still complete)')
r = observe(S.simulate('uninstall', SUP), 'uninstall', 'abort', abort='uninstall of de.evp.helfer.channeltest not successful')
check(r['rc'] == 2 and kinds(r, 'HOST_ABORT') and 'host_script' in r['sum']['incomplete_sources'], 'host script abort -> HOST_ABORT, INCOMPLETE')

# ---- Regression: fehlende Providerzeilen
r = observe(S.simulate('control', dict(CTRL, recheck_mutation=(30001, 'lost'))), 'control', 'row_lost')
check(kinds(r, 'ROW_ABSENT_AFTER_PRESENT', seq=30001, verified=True) and not kinds(r, 'URI_NOT_VISIBLE', seq=30001),
      'row lost after a successful URI check -> ROW_ABSENT_AFTER_PRESENT kept')
s = S.simulate('control', CTRL)
pl = lines_of(s, lambda l: ' BOOTSCAN PRIV ' in l and ' n=2 ' in l)[0]
r = observe(drop_line(s, pl), 'control', 'priv_line_lost')
check(r['rc'] == 2 and 'phase_pre' in r['sum']['incomplete_sources']
      and any(x['type'] == 'row_span' and not x['frames_verified'] for x in r['recs']), 'lost provider change line -> INCOMPLETE, affected spans unverified')
for kind, want in (('null', 'ROW_OWNER_NOT_APP'), ('pending', 'ROW_PENDING_OR_TRASHED_AGAIN')):
    r = observe(S.simulate('control', dict(CTRL, recheck_mutation=(30001, kind))), 'control', 'mut_' + kind)
    check(kinds(r, want, seq=30001), 'URI visible, later %s -> %s listed' % (kind, want))

# ---- Regression: keine Scan-Überschneidung
rn = observe(S.simulate('uninstall', {'orphan': 'delayed-all', 'scan_after_pkg_end': None}), 'uninstall', 'no_overlap')
check(rn['sum']['measurement'] == 'COMPLETE' and rn['sum']['overlap'] == 'ABSENT', 'complete measurement without scan overlap -> overlap ABSENT (%s)' % rn['sum']['overlap'])

# ---- Monotonie: spätere unauffällige Beobachtung entfernt nichts
s = S.simulate('uninstall', SUP)
rid = re.search(r' id=(\d+)$', lines_of(s, lambda l: ' BOOTSCAN DL ' in l and ' seq=30000 ' in l)[0]).group(1)
end = s['dev'].clock + 5000
s['hosts'] += 'QT-BEGIN t=%d res=ms\nRow: 0 _id=%s, owner_package_name=%s, is_pending=0, is_trashed=0, _data=%sBootScanProbe-a1b2c3d4e5f6-30000.mp4, ' \
              '_display_name=x, _size=262144\nQT-END rc=0 t=%d\n' % (end, rid, S.PKG, S.DIR, end + 3)
r = observe(s, 'uninstall', 'revert')
spans = [x for x in r['recs'] if x['type'] == 'row_span' and x['seq'] == 30000 and x['source'] == 'host']
check(kinds(r, 'ROW_OWNER_NOT_APP', seq=30000) and isinstance(spans[-1]['state'], dict) and spans[-1]['state']['owner'] == S.PKG,
      'a later inconspicuous observation (owner back) does not remove the earlier owner finding')

# ---- Dateien: fehlgeschlagene Downloads kein Datenverlust; ungeprüfter Rahmen nicht PROVEN
for kind in ('intact', 'truncated', 'altered', 'missing'):
    def res(dev, k=kind):
        p = S.DIR + 'BootScanProbe-a1b2c3d4e5f6-30002.mp4'
        if k == 'truncated':
            dev.disk[p] = (S.SIZE // 2, '1' * 64)
        elif k == 'altered':
            dev.disk[p] = (S.SIZE, '0' * 64)
        elif k == 'missing':
            del dev.disk[p]
    r = observe(S.simulate('uninstall', SUP_PENDING, before_recheck=res), 'uninstall', 'residual_' + kind)
    check(kinds(r, 'RESIDUAL_FILE_AFTER_FAILURE', seq=30002, state=kind) and r['sum']['data_loss'] == 'NONE_PROVEN' and kinds(r, 'PUBLISH_FAILED', seq=30002),
          'failed download, residual file %s -> listed, never data loss' % kind)
s = S.simulate('control', CTRL, before_recheck=corrupt(30001))
rs_ = s['logcat'].index('phase=recheck sdk=')
s['logcat'] = s['logcat'][:rs_] + re.sub(r'diskH=[0-9a-f]{16}', 'diskH=0123456789abcdef', s['logcat'][rs_:])   # alle Dateirahmen der Nachprüfung ungeprüft
r = observe(s, 'control', 'unverified_damage')
check(kinds(r, 'FILE_DAMAGED', seq=30001, verified=False) and not kinds(r, 'FILE_DAMAGED', seq=30001, verified=True)
      and r['sum']['data_loss'] == 'NONE_PROVEN' and r['sum']['measurement'] == 'INCOMPLETE',
      'damage seen only in unverified frames is listed as unverified, not PROVEN, measurement INCOMPLETE')
r = observe(S.simulate('control', CTRL, before_recheck=corrupt(30001)), 'control', 'damage')
check(r['rc'] == 3 and kinds(r, 'FILE_DAMAGED', seq=30001, verified=True) and r['sum']['data_loss_evidence'], 'damaged published file -> PROVEN with evidence, exit 3')
for kind in ('removed', 'modified', 'added'):
    s = S.simulate('control', CTRL)
    s['after'] = {} if kind == 'removed' else ({k: ('b' * 64, 36) for k in s['before']} if kind == 'modified'
                                               else dict(s['before'], **{'/storage/emulated/0/Movies/.thumbnails/.nomedia': ('e' * 64, 0)}))
    r = observe(s, 'control', 'foreign_' + kind)
    check((r['sum']['data_loss'] == 'PROVEN') == (kind != 'added') and kinds(r, 'FOREIGN_' + kind.upper()), 'foreign file %s -> %s' % (kind, r['sum']['data_loss']))

# ---- Regression (GPT-6-Prüfung V4): Host-Abfragen fail-closed
def host_case(name, mutate):
    s_ = S.simulate('control', CTRL)
    s_['hosts'] = mutate(s_['hosts'])
    return observe(s_, 'control', 'host_' + name)


def first_block(h):
    L = h.splitlines(True)
    a_ = next(k for k, l in enumerate(L) if l.startswith('QT-BEGIN'))
    b_ = next(k for k, l in enumerate(L) if l.startswith('QT-END') and k > a_)
    return L, a_, b_


def rc1(h):
    L, a_, b_ = first_block(h)
    L[b_] = L[b_].replace('QT-END rc=0', 'QT-END rc=1')
    return ''.join(L)


def unterminated(h):
    L, a_, b_ = first_block(h)
    return ''.join(L) + L[a_]          # zusätzliche Abfrage ohne QT-END am Ende


def one_bad(h):
    L, a_, b_ = first_block(h)
    return ''.join(L[a_:b_ + 1]) + ''.join(L[a_:b_]) + 'Error while accessing provider:media\n' + L[b_]   # ein gültiger, ein ungültiger Block


for name, mutate in (('empty file', lambda h: ''), ('query with QT-END rc=1', rc1), ('QT-BEGIN without QT-END', unterminated),
                     ('one valid and one invalid block', one_bad)):
    r = host_case(name.replace(' ', '_').replace('=', ''), mutate)
    check(r['src']['host_queries']['status'] == 'incomplete' and r['sum']['measurement'] == 'INCOMPLETE' and r['rc'] == 2
          and 'host_queries' in r['sum']['incomplete_sources'], 'host queries: %s -> host_queries incomplete, measurement INCOMPLETE (%s)'
          % (name, r['src']['host_queries']['problems'][:1]))

# ---- Regression (Prüfung 63cf78d): zeitliche Abdeckung und isolierte/beschädigte QT-Marker
def blocks(h):
    out, cur = [], []
    for l in h.splitlines(True):
        cur.append(l)
        if l.startswith('QT-END'):
            out.append(''.join(cur))
            cur = []
    return out


r = host_case('full_coverage', lambda h: h)
det = r['src']['host_queries'].get('detail') or {}
check(r['src']['host_queries']['status'] == 'complete' and len(blocks(r['files']['host-sampler'][1])) > 3
      and det.get('max_gap_ms') is not None and det['max_gap_ms'] <= det.get('tolerance_ms', 0),
      'host queries covering the whole window (package step .. end of post) -> complete (max gap %s ms)' % det.get('max_gap_ms'))
r = host_case('only_first', lambda h: blocks(h)[0])
check(r['src']['host_queries']['status'] == 'incomplete' and r['sum']['measurement'] == 'INCOMPLETE'
      and any('ends' in p_ and 'before the end of phase post' in p_ for p_ in r['src']['host_queries']['problems']),
      'only the first host query, all later ones missing -> incomplete (coverage)')
for label, extra in (('isolated QT-END rc=1', 'QT-END rc=1 t=1\n'), ('malformed QT-BEGIN', 'QT-BEGIN garbage\n'),
                     ('provider error text outside a query', 'Error while accessing provider:media\n')):
    for where, mut in (('after the first query', lambda h, e=extra: blocks(h)[0] + e),
                       ('inside otherwise complete coverage', lambda h, e=extra: ''.join(blocks(h)[:5]) + e + ''.join(blocks(h)[5:]))):
        r = host_case('%s_%s' % (label, where), mut)
        check(r['src']['host_queries']['status'] == 'incomplete' and r['sum']['measurement'] == 'INCOMPLETE'
              and any('discarded' in p_ for p_ in r['src']['host_queries']['problems']),
              'host queries: %s %s -> incomplete evidence (%s)' % (label, where, r['src']['host_queries']['problems'][:1]))


def gap(h):
    b = blocks(h)
    t = lambda x: int(re.search(r'QT-BEGIN t=(\d+)', x).group(1))
    mid = (t(b[0]) + t(b[-1])) // 2
    return ''.join(x for x in b if not (mid <= t(x) <= mid + 8000))   # 8 s ohne Abfrage mitten im Fenster


r = host_case('gap', gap)
check(r['src']['host_queries']['status'] == 'incomplete' and any('gaps longer than' in p_ for p_ in r['src']['host_queries']['problems']),
      'an 8 s gap without host queries inside the window -> incomplete (tolerance %d ms for adb delays)' % 5000)

# ---- unvollständige Quellen
s = S.simulate('control', CTRL)
h = s['hosts'].splitlines()
i = next(k for k, l in enumerate(h) if l.startswith('QT-END'))
h[i] = 'QT-END rc=0 t=5'
s['hosts'] = '\n'.join(h) + '\n'
r = observe(s, 'control', 'host_time')
check('host_queries' in r['sum']['incomplete_sources'], 'host query ending before it began -> host_queries incomplete')
s = S.simulate('control', CTRL)
r = observe(drop_line(s, lines_of(s, lambda l: 'event=sync id=2' in l)[0]), 'control', 'nosync')
check('logcat_stream' in r['sum']['incomplete_sources'], 'missing sync marker -> logcat_stream incomplete')
s = S.simulate('control', CTRL)
dl = lines_of(s, lambda l: ' BOOTSCAN DL ' in l)[2]
r = observe(drop_line(dict(s), dl), 'control', 'stream_lost', dump=s['logcat'])
check('logcat_stream' in r['sum']['incomplete_sources'], 'run line in the dump but not in the stream -> logcat_stream incomplete')
r = observe(S.simulate('control', CTRL), 'control', 'no_instr', drop=('instr-post',))
check(r['src']['instrumentation_post']['status'] == 'missing' and r['rc'] == 2, 'missing instrumentation output -> source missing, INCOMPLETE')
s = S.simulate('control', CTRL)
s['after_text'] = '/storage/x\n'
r = observe(s, 'control', 'foreign_incomplete')
check('foreign_lists' in r['sum']['incomplete_sources'] and not kinds(r, 'FOREIGN_REMOVED'), 'incomplete foreign list -> incomplete, no removal claimed')

# ---- fehlende Werte sind null, keine Ersatzwerte
s = S.simulate('uninstall', SUP_PENDING)
fl = lines_of(s, lambda l: ' BOOTSCAN DL ' in l and ' seq=30002 ' in l)[0]
r = observe(edit(s, fl, re.sub(r'commitStart=\d+ commitEnd=\d+ commit=ex:IOException', 'commitStart=-1 commitEnd=-1 commit=-', fl)), 'uninstall', 'nulls')
d30002 = [x for x in r['recs'] if x['type'] == 'download' and x['seq'] == 30002][0]
check(d30002['commitStart'] is None and d30002['commitEnd'] is None and d30002['id'] is None and d30002['uri'] is None,
      'values not measured (-1, -) appear as null, never as a default that looks like an observation')

# ---- Rückführbarkeit: jeder Datensatz mit run und gültiger Quellzeile
r = observe(S.simulate('uninstall', SUP), 'uninstall', 'trace')
texts = {fn: open(os.path.join(r['dir'], fn)).read().splitlines() for fn, _ in r['files'].values()}
bad = []
for x in r['recs']:
    if x.get('run') != 'a1b2c3d4e5f6' or 'variant' not in x:
        bad.append(('run/variant', x['type']))
    for ref in (x.get('src') or []) + (x.get('src_first') or []):
        if 'line' in ref:
            ln = texts.get(ref['file'], [])
            if not (1 <= ref['line'] <= len(ln)):
                bad.append(('line', x['type'], ref))
            elif x['type'] == 'download' and ' seq=%d ' % x['seq'] not in ln[ref['line'] - 1]:
                bad.append(('content', x['type'], x['seq']))
            elif x['type'] == 'uri_check' and 'URIQ' not in ln[ref['line'] - 1]:
                bad.append(('content', x['type']))
    if x['type'] in ('download', 'uri_check', 'scan', 'owner_event', 'pkgop', 'marker', 'proc_event', 'crash_block') and not x.get('src'):
        bad.append(('no src', x['type']))
check(not bad, 'every record carries run/variant and source references to existing raw lines with matching content %s' % bad[:3])

# ---- deterministisch, Upload-Prüfung, Index, keine Urteilswörter
a = observe(S.simulate('uninstall', SUP), 'uninstall', 'det1')
b = observe(S.simulate('uninstall', SUP), 'uninstall', 'det2')
check(open(os.path.join(a['dir'], 'report', 'observations.jsonl')).read() == open(os.path.join(b['dir'], 'report', 'observations.jsonl')).read(),
      'identical input -> byte-identical observations.jsonl')
up = os.path.join(TMP, 'upload')
os.makedirs(up)
open(os.path.join(up, 'ok.txt'), 'w').write('nothing secret\n')
p = subprocess.run([sys.executable, '-I', TOOL, 'check-upload', up], capture_output=True, text=True)
check(p.returncode == 0 and 'PKGAB_UPLOAD_CHECK OK' in p.stdout, 'upload check passes clean text')
tok = 'ghp_' + 'A1b2C3d4' * 5
open(os.path.join(up, 'leak.txt'), 'w').write('x\nheader ' + tok + '\nstorePassword=hunter22\n')
p = subprocess.run([sys.executable, '-I', TOOL, 'check-upload', up], capture_output=True, text=True)
check(p.returncode == 1 and 'BLOCKED' in p.stdout and tok not in p.stdout and 'hunter22' not in p.stdout and p.stdout.count('PKGAB_UPLOAD_BLOCKED') == 2,
      'upload check blocks tokens and passwords without printing them')
c = observe(S.simulate('control', CTRL, run='b1b2c3d4e5f6'), 'control', 'idx_c')
u = observe(S.simulate('uninstall', SUP), 'uninstall', 'idx_u')
for label, dirs, code, word in [('both complete with overlap', [c, u], 0, 'MET'), ('uninstall arm without overlap', [c, rn], 4, 'NOT_MET'),
                                ('one arm incomplete', [c, observe(S.simulate('uninstall', SUP), 'uninstall', 'idx_inc', drop=('instr-pre',))], 2, 'NOT_MET'),
                                ('same run id in both arms', [observe(S.simulate('control', CTRL), 'control', 'idx_same'), u], 4, 'NOT_MET')]:
    p = subprocess.run([sys.executable, '-I', TOOL, 'index'] + [os.path.join(x['dir'], 'report') for x in dirs], capture_output=True, text=True)
    outputs.append(p.stdout)
    check(p.returncode == code and ('PKGAB_AB_PRECONDITIONS ' + word) in p.stdout and 'PKGAB_FILE ' in p.stdout,
          'index: %s -> %s, exit %d' % (label, word, p.returncode))
check(not any(FORBIDDEN.search(o_) for o_ in outputs), 'no output or record uses a verdict word (SUPPORTED, NOT_SUPPORTED, CLEAN, HYPOTHESIS, PASS, ANOMALY)')

shutil.rmtree(TMP)
print('PKGAB_OBSERVE_SELFTEST %s %d cases, %d failed' % ('FAIL' if fails else 'PASS', len(done), len(fails)))
sys.exit(1 if fails else 0)
