#!/usr/bin/env python3
"""Beobachter der A/B-Paketdiagnose (tools/run-pkgremoval-probe.sh). NUR Extraktion, Vollständigkeitsprüfung und
Sammlung beobachteter Auffälligkeiten. KEIN Kausalurteil: das Werkzeug sagt nie, ob die Deinstallations-Hypothese
zutrifft; die Auswertung macht ein Mensch bzw. Codex anhand der Rohbelege und dieser Beobachtungsliste.

  pkgab_observe.py observe --variant V --run RUN --out DIR --logcat L --logcat-dump D --host-sampler H --pkgops P \
      --instr-pre I1 --instr-post I2 --instr-recheck I3 --foreign-before B --foreign-after A [--abort GRUND]
  pkgab_observe.py index DIR_CONTROL DIR_UNINSTALL
  pkgab_observe.py check-upload DIR

observe schreibt DIR/observations.jsonl (ein JSON-Objekt je Zeile, Schema in PKGREMOVAL-AB-PROBE.md) und DIR/summary.txt.
Jeder Datensatz trägt run, variant und – wo er aus Rohdaten stammt – src: [{"file": Name, "line": Zeilennummer ab 1}].
Fehlende Werte sind null (nie ein Ersatzwert). Rohdateien werden nur gelesen, nie verändert.

Drei Aussagen, getrennt:
  measurement  COMPLETE | INCOMPLETE  (alle vorgesehenen Quellen vollständig? sonst Liste der unvollständigen Quellen)
  data_loss    PROVEN | NONE_PROVEN   (nur aus geprüften Rahmen bzw. vollständigen Dateilisten; gilt auch bei INCOMPLETE)
  overlap      PRESENT | ABSENT | UNDETERMINED  (Mess-Voraussetzung: ein externer Mount-Scan schneidet den
               Paketschritt UND mindestens ein Download der Phase post begann vor dem Ende dieses Scans)
Auffälligkeiten (finding) werden aus JEDER Beobachtung gesammelt und nie entfernt.

Exit observe: 3 data_loss PROVEN; sonst 2 measurement INCOMPLETE; sonst 0 = Messung vollständig und kein Dateischaden
nachgewiesen. 0 ist KEIN PASS des Helpers und keine Bestätigung oder Widerlegung der Hypothese.
Exit index: 3 Datenverlust in einem Arm; 2 ein Arm unvollständig/fehlt; 4 beide vollständig, aber A/B-Voraussetzungen
nicht erfüllt (z. B. keine Überschneidung); 0 Voraussetzungen erfüllt (wieder: kein Urteil über die Ursache).
"""
import argparse
import hashlib
import importlib.util
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location('bootscan_parser_for_observe', os.path.join(HERE, 'bootscan_verdict.py'))
B = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(B)   # nur Parser/Rekonstruktion: parse, phases, digest, listing, foreign_diff, report_class

TOOL = 'pkgab_observe v3'
PKG = 'de.evp.helfer.channeltest'
SEQ0 = {'pre': 20000, 'post': 30000}
OFFSETS = {'pre': [0, 250], 'post': [0, 250, 2000, 10000]}
STEPS = {'uninstall': ['install1-begin', 'install1-end', 'uninstall-begin', 'absent', 'uninstall-end', 'install2-begin', 'install2-end'],
         'control': ['install1-begin', 'install1-end', 'update-begin', 'update-end']}
CRASH_TAGS = {'AndroidRuntime', 'DEBUG', 'libc', 'crash_dump', 'crash_dump64', 'art'}
PROC_TAGS = {'am_kill', 'am_proc_died', 'am_crash', 'am_anr'}
RELEVANT = re.compile(r'de\.evp\.helfer|providers\.media|system_server')
HEX16 = re.compile(r'^[0-9a-f]{16}$')
QT_ROW = re.compile(r'^Row: (\d+) _id=(\d+), owner_package_name=([^,]*), is_pending=(\d+), is_trashed=(\d+), _data=([^,]*), _display_name=([^,]*), _size=([^,]*)$')
PROVEN_KINDS = ('FILE_DAMAGED', 'FILE_MISSING_AFTER_DONE', 'FOREIGN_REMOVED', 'FOREIGN_MODIFIED')
NOTES = ['no causal conclusion is drawn by this tool; interpretation is manual',
         'exit code 0 means only: planned measurements complete and no file damage proven; it is no approval of the helper and no statement about the hypothesis',
         'states shorter than the sampling interval (snapshots ~0.5 s, host queries ~0.3 s) may be invisible']


def ms(t):
    return int(round(float(t) * 1000))


def num(v):
    """'-1', '-' oder fehlend -> None (Stufe nicht erreicht bzw. nicht gemessen); sonst int."""
    return int(v) if v is not None and re.fullmatch(r'\d+', str(v)) else None


def read(path):
    if not path or not os.path.isfile(path):
        return None
    return open(path, encoding='utf-8', errors='replace').read()


class Observer:
    def __init__(self, variant, run):
        self.variant, self.run = variant, run
        self.recs, self.findings, self.sources = [], [], {}

    def rec(self, type_, **f):
        r = dict(type=type_, run=self.run, variant=self.variant, **f)
        self.recs.append(r)
        return r

    def source(self, name, problems, src=None, detail=None):
        """Eine Quelle ist complete nur ohne Probleme; einmal incomplete bleibt incomplete."""
        s = self.sources.setdefault(name, {'name': name, 'status': 'complete', 'problems': [], 'detail': detail})
        if problems:
            s['status'] = 'missing' if problems == ['missing'] else 'incomplete'
            s['problems'] += problems[:20]
        if detail is not None:
            s['detail'] = detail

    def find(self, kind, src, verified=True, **f):
        key = (kind, f.get('seq'), f.get('path'), f.get('pid'), f.get('state'), verified)
        for x in self.findings:
            if x['_key'] == key:
                x['count'] += 1
                return
        self.findings.append(dict(type='finding', run=self.run, variant=self.variant, kind=kind, verified=verified, count=1, src=src, _key=key, **f))


# ------------------------------------------------------------------------------------------------ Logcat
def scan_logcat(o, text, fname, run):
    """Ein Durchlauf: Zeilennummern je BOOTSCAN-Zeile, Scans, Besitzer-, Prozess- und Crashzeilen."""
    refs, markers, open_blocks, unparsed = {}, [], {}, 0
    phase = None
    for i, raw in enumerate(text.splitlines(), 1):
        m = B.LINE.match(raw)
        if not m:
            unparsed += bool(raw.strip())
            continue
        t, pid, tid, tag, msg = ms(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(5).strip(), m.group(6)
        src = [{'file': fname, 'line': i}]
        blk = open_blocks.get(pid)
        if blk is not None:
            if tag in CRASH_TAGS:
                blk['lines'].append(raw)
                blk['src'].append({'file': fname, 'line': i})
                continue
            close_block(o, open_blocks.pop(pid))
        if (tag == 'AndroidRuntime' and 'FATAL EXCEPTION' in msg) or (tag == 'DEBUG' and '*** ***' in msg) \
                or (tag == 'libc' and 'Fatal signal' in msg) or 'OutOfMemoryError' in msg:
            open_blocks[pid] = {'t_first': t, 'pid': pid, 'lines': [raw], 'src': list(src)}
            continue
        if tag == 'BOOTSCAN' and msg.startswith('BOOTSCAN '):
            body = msg[len('BOOTSCAN '):]
            kind = body.split(' ', 1)[0]
            f = B.kv(body[len(kind):].strip())
            if kind == 'PKG':
                markers.append({'t': t, 'f': f, 'src': src})
            elif kind == 'START':
                phase = f.get('phase')
                refs[('START', phase)] = src
            elif kind == 'END':
                refs[('END', phase)] = src
                phase = None
            elif kind == 'DL':
                refs[('DL', f.get('phase'), f.get('seq'))] = src
            elif kind == 'URIQ':
                refs.setdefault(('URIQ', phase, f.get('seq'), f.get('off')), []).extend(src)
            elif kind in ('SNAP', 'DSNAP'):
                refs[(kind, phase, f.get('n'))] = src
        elif tag == 'MediaProvider':
            s = B.SCAN.match(msg)
            if s:
                o.rec('scan', volume=s.group(1), reason=s.group(2), t_start=t - int(s.group(4)), t_end=t, items=int(s.group(3)), inserts=int(s.group(5)),
                      updates=int(s.group(6)), deletes=int(s.group(7)), pid=pid, tid=tid, src=src)
            ow = re.search(r'Reason: owner_package_changed:(.+)$', msg)
            if ow:
                o.rec('owner_event', t=t, pid=pid, tid=tid, path=ow.group(1).strip(), src=src)
        if tag in PROC_TAGS and RELEVANT.search(msg):
            f = msg.strip('[]').split(',')
            o.rec('proc_event', t=t, tag=tag, pid=num(f[1]) if len(f) > 1 else None, process=f[2] if len(f) > 2 else None,
                  reason=','.join(f[4:]) if len(f) > 4 else None, raw=raw, src=src)
            o.find('PROC_EVENT', src, tag=tag, pid=num(f[1]) if len(f) > 1 else None, t=t)
    for blk in open_blocks.values():
        close_block(o, blk)
    return refs, markers, unparsed


def close_block(o, blk):
    proc = next((re.search(r'Process: ([^,]+)', l).group(1) for l in blk['lines'] if re.search(r'Process: ([^,]+)', l)), None)
    o.rec('crash_block', t_first=blk['t_first'], pid=blk['pid'], process=proc, lines=blk['lines'], src=blk['src'])
    o.find('CRASH_BLOCK', blk['src'][:1], pid=blk['pid'], process=proc, t=blk['t_first'])


# ------------------------------------------------------------------------------------------------ Paketschritte
def package_steps(o, variant, markers):
    steps, ops, open_ops, problems = {}, [], {}, []
    for mk in markers:
        f, ev = mk['f'], mk['f'].get('event')
        if f.get('run') != o.run or f.get('variant') != variant:
            problems.append('marker of another run/variant at %s' % mk['src'])
            continue
        o.rec('marker', event=ev, t=mk['t'], uid=f.get('uid'), fields={k: v for k, v in f.items() if k not in ('run', 'variant', 'event')}, src=mk['src'])
        if ev == 'op-begin':
            open_ops[(f.get('op'), f.get('attempt'))] = mk
        elif ev == 'op-end':
            b = open_ops.pop((f.get('op'), f.get('attempt')), None)
            r = o.rec('pkgop', op=f.get('op'), attempt=f.get('attempt'), rc=num(f.get('rc')), result=f.get('result'),
                      t_begin=b['t'] if b else None, t_end=mk['t'], src=(b['src'] if b else []) + mk['src'])
            ops.append(r)
            if b is None:
                problems.append('op-end without op-begin: %s' % f.get('op'))
            if r['rc'] != 0 or r['result'] != 'Success':
                o.find('PKGOP_FAILED', r['src'], op=r['op'], attempt=r['attempt'], rc=r['rc'], result=r['result'])
        elif ev == 'absent':
            o.rec('absence', pmApp=f.get('pmApp'), pmTest=f.get('pmTest'), listed=f.get('listed'), t=mk['t'], src=mk['src'])
            if (f.get('pmApp'), f.get('pmTest'), f.get('listed')) != ('none', 'none', 'none'):
                o.find('ABSENCE_NOT_PROVEN', mk['src'], fields=f)
                problems.append('absence after the uninstall not proven')
        elif ev == 'absent-failed':
            o.find('ABSENCE_NOT_PROVEN', mk['src'], fields=f)
            problems.append('absence after the uninstall not proven')
        if ev in STEPS[variant]:
            if ev in steps:
                problems.append('step %s twice' % ev)
            steps[ev] = mk['t']
    problems += ['operation %s attempt %s without end marker' % k for k in open_ops]
    order = [e for e in STEPS[variant] if e in steps]
    if order != STEPS[variant] or [steps[e] for e in order] != sorted(steps[e] for e in order):
        problems.append('package steps %s incomplete or out of order (expected %s)' % (order, STEPS[variant]))
    if variant == 'uninstall':
        un = [r for r in ops if r['op'] in ('uninstall-test', 'uninstall-app')]
        if [r['op'] for r in un] != ['uninstall-test', 'uninstall-app'] or any(r['rc'] != 0 or r['result'] != 'Success' for r in un):
            problems.append('uninstall of test and app package not both proven successful')
    for apk in ('install-app', 'install-test'):
        if not any(r['op'] == apk and r['rc'] == 0 and r['result'] == 'Success' for r in ops):
            problems.append('%s never succeeded' % apk)
    o.source('package_steps', problems)
    return steps


# ------------------------------------------------------------------------------------------------ Phasen und Downloads
def phase_sources(o, ph, instr, refs, fname):
    Report = B.report_class()
    for name in ('pre', 'post', 'recheck'):
        text = instr.get(name)
        if text is None:
            o.source('instrumentation_' + name, ['missing'])
        else:
            rep = Report(text.splitlines())
            ok = rep.tests() == [(B.PROBE_CLASS, 'probe')] and rep.ok and not rep.problems
            o.source('instrumentation_' + name, [] if ok else ['not exactly the completed probe test %s' % rep.problems[:2]])
        p = ph.get(name)
        if p is None or p.get('end') is None:
            o.source('phase_' + name, ['START or END missing'])
            continue
        probs = list(p['frame_problems'])
        ns = [s['n'] for s in p['snaps']]
        if ns != list(range(1, len(ns) + 1)) or str(len(ns)) != p['end'].get('snapshots') or p['end'].get('final') != str(len(ns)):
            probs.append('snapshots not contiguous or not as announced by END')
        if not p['snaps'] or not p['snaps'][-1]['final'] or not p['snaps'][-1]['valid']:
            probs.append('final privileged snapshot missing or not valid')
        if not p['dsnaps'] or not p['dsnaps'][-1]['final'] or not p['dsnaps'][-1]['ok']:
            probs.append('final disk snapshot missing or not valid')
        o.source('phase_' + name, probs, src=refs.get(('END', name)))
        o.rec('phase', phase=name, pid=num(p['start'].get('pid')), app_uid=p['start'].get('appUid'), t_start=num(p['start'].get('t')),
              t_end=num(p['end'].get('t')), snapshots=len(ns), src=(refs.get(('START', name)) or []) + (refs.get(('END', name)) or []))


def downloads(o, ph, refs):
    out, probs = [], []
    for name in ('pre', 'post'):
        p = ph.get(name)
        if not p:
            continue
        seqs = [num(d.get('seq')) for d in p['dl']]
        if seqs != list(range(SEQ0[name], SEQ0[name] + len(seqs))) or (p.get('end') and str(len(seqs)) != p['end'].get('downloads')):
            probs.append('phase %s downloads not contiguous or not as announced by END' % name)
        for d in p['dl']:
            t = {k: num(d.get(k)) for k in ('insertStart', 'insertEnd', 'commitStart', 'commitEnd', 'done')}
            src = refs.get(('DL', name, d.get('seq')), [])
            r = o.rec('download', phase=name, seq=num(d.get('seq')), id=d.get('id') if d.get('id') not in (None, '-') else None,
                      uri=d.get('uri') if d.get('uri') not in (None, '-') else None, name=d.get('name'), path=B.DIR + d.get('name', ''),
                      state=d.get('state'), reason=d.get('reason'), commit=d.get('commit'), size=num(d.get('size')), sha256=d.get('sha'), src=src, **t)
            out.append(r)
            present = [v for v in t.values() if v is not None]
            p0, p1 = num(p['start'].get('t')), num((p.get('end') or {}).get('t'))
            if present != sorted(present) or t['done'] is None or (p0 is not None and p1 is not None and any(v < p0 or v > p1 for v in present)):
                probs.append('seq %s: impossible or out-of-phase times %s' % (r['seq'], t))
            if r['state'] == 'done' and (r['id'] is None or not (r['uri'] or '').endswith('/' + r['id']) or None in t.values()):
                probs.append('seq %s: done without matching id/uri or stage times' % r['seq'])
            if r['state'] != 'done':
                o.find('PUBLISH_FAILED', src, seq=r['seq'], phase=name, state=r['state'], reason=r['reason'], commit=r['commit'])
    if len((ph.get('pre') or {}).get('dl', [])) != 3:
        probs.append('phase pre did not publish exactly 3 files')
    o.source('downloads', probs)
    return out


def uri_checks(o, ph, refs, dls):
    probs = []
    by = {(d['phase'], d['seq']): d for d in dls}
    rc = ph.get('recheck') or {}
    for name in ('pre', 'post', 'recheck'):
        for q in (ph.get(name) or {}).get('uriq', []):
            seq, off, t = num(q.get('seq')), int(q.get('off', 0)), num(q.get('t'))
            d = by.get(('pre', seq)) or by.get(('post', seq))
            res = q.get('res', '')
            f = B.kv(res[len('found '):]) if res.startswith('found ') else {}
            src = refs.get(('URIQ', name, q.get('seq'), q.get('off')), [])
            o.rec('uri_check', phase=name, seq=seq, off=off, t=t, result=res.split(' ', 1)[0] if res else None, id=f.get('id'), owner=f.get('owner'),
                  pending=f.get('pending'), path=f.get('data'), src=src)
            if d is None:
                probs.append('URI check for unknown seq %s' % seq)
                continue
            if t is None or (d['done'] is not None and t < d['done']):
                probs.append('seq %s: URI check at %s before done %s' % (seq, t, d['done']))
            if res == 'none':
                o.find('URI_NOT_VISIBLE', src, seq=seq, phase=name, off=off, t=t)
            elif not res.startswith('found '):
                o.find('URI_QUERY_ERROR', src, seq=seq, phase=name, off=off, t=t, result=res)
            elif (f.get('id'), f.get('owner'), f.get('pending'), f.get('data')) != (d['id'], PKG, '0', d['path']):
                o.find('URI_ROW_DIFFERS', src, seq=seq, phase=name, off=off, t=t, id=f.get('id'), owner=f.get('owner'), pending=f.get('pending'))
    for d in dls:
        if d['state'] != 'done':
            continue
        got = sorted(int(q['off']) for q in (ph.get(d['phase']) or {}).get('uriq', []) if num(q.get('seq')) == d['seq'])
        if got != OFFSETS[d['phase']]:
            probs.append('seq %s: URI checks at %s, expected %s' % (d['seq'], got, OFFSETS[d['phase']]))
        if sum(1 for q in rc.get('uriq', []) if num(q.get('seq')) == d['seq'] and q.get('off') == '-1') != 1:
            probs.append('seq %s: no single recheck URI query' % d['seq'])
    o.source('uri_checks', probs)


# ------------------------------------------------------------------------------------------------ Beobachtungen der Zeilen und Dateien
def snapshot_frames(ph, refs):
    """-> [(phase, n, t, tEnd, priv-dict, verified, src)] aller gültigen privilegierten Aufnahmen und Dateiaufnahmen."""
    priv, disk = [], []
    for name in ('pre', 'post', 'recheck'):
        for s in (ph.get(name) or {}).get('snaps', []):
            raw = s['raw']
            ok = s['valid'] and HEX16.match(raw.get('privH', '')) is not None and B.digest(s['priv']) == raw.get('privH') \
                and str(len(s['priv'])) == raw.get('privN') and s['t_end'] >= s['t']
            if s['valid']:
                priv.append((name, s['n'], s['t'], s['t_end'], s['priv'], ok, refs.get(('SNAP', name, str(s['n'])), [])))
        for s in (ph.get(name) or {}).get('dsnaps', []):
            raw = s['raw']
            ok = s['ok'] and HEX16.match(raw.get('diskH', '')) is not None and B.digest(s['disk']) == raw.get('diskH') and str(len(s['disk'])) == raw.get('diskN')
            if s['ok']:
                disk.append((name, s['n'], s['t'], s['t'], s['disk'], ok, refs.get(('DSNAP', name, str(s['n'])), [])))
    return priv, disk


def host_frames(o, text, fname):
    out, used, bad, probs, cur = [], 0, [], [], None
    for i, l in enumerate((text or '').splitlines(), 1):
        if l.startswith('QT-BEGIN'):
            m = re.match(r'^QT-BEGIN t=(\d+) res=(\w+)$', l)
            if cur:
                bad.append('unterminated block at line %d' % cur['line'])
            cur = {'a': int(m.group(1)), 'res': m.group(2), 'rows': {}, 'none': 0, 'n': 0, 'ok': True, 'line': i} if m else None
            if not m:
                bad.append('malformed QT-BEGIN at line %d' % i)
            continue
        if cur is None:   # isolierter QT-END-Marker, Fehlertext oder sonstige Ausgabe außerhalb einer Abfrage
            bad.append('unexpected output outside a query at line %d' % i)
            continue
        if l.startswith('QT-END'):
            e = re.match(r'^QT-END rc=(\d+) t=(\d+)$', l)
            if e and int(e.group(2)) < cur['a']:
                probs.append('host query ends before it began (lines %d-%d)' % (cur['line'], i))
            if e and e.group(1) == '0' and cur['ok'] and cur['res'] == 'ms' and ((cur['none'] == 1) != (cur['n'] > 0)) and int(e.group(2)) >= cur['a']:
                out.append(('host', cur['line'], cur['a'], int(e.group(2)), cur['rows'], True, [{'file': fname, 'line': cur['line']}, {'file': fname, 'line': i}]))
                used += 1
            else:
                bad.append('lines %d-%d' % (cur['line'], i))
            cur = None
        elif l == 'No result found.':
            cur['none'] += 1
        else:
            r = QT_ROW.match(l)
            if r and int(r.group(1)) == cur['n'] and r.group(2) not in cur['rows']:
                cur['rows'][r.group(2)] = (r.group(3), r.group(4), r.group(5), r.group(6))
                cur['n'] += 1
            else:
                cur['ok'] = False
    if cur is not None:   # QT-BEGIN ohne abschließendes QT-END
        bad.append('unterminated block at line %d' % cur['line'])
    if text is None:
        o.source('host_queries', ['missing'])
        return out
    # Fail-closed: jede verworfene oder unvollständige Abfrage und eine Datei ohne verwertbare Abfrage machen die Quelle
    # unvollständig (verworfene Blöcke fließen nicht in die Beobachtungen ein, die Messung ist dann nicht COMPLETE)
    if bad:
        probs.append('%d host queries discarded, unterminated or unexpected output: %s' % (len(bad), bad[:10]))
    if used == 0:
        probs.append('no usable host query')
    o.source('host_queries', probs, detail={'used': used, 'discarded': len(bad), 'discarded_at': bad[:20]})
    return out


# Größte zulässige Lücke zwischen zwei verwertbaren Host-Abfragen im Beobachtungsfenster (Beginn des Paketschritts bis
# Ende der Phase post). Der Sampler fragt etwa alle 0,3 s plus Dauer von "adb shell content query" ab; 5 s lassen
# normale adb-Verzögerungen zu. Eine längere Lücke heißt: Zeitraum ohne Host-Beobachtung -> host_queries unvollständig.
HOST_MAX_GAP_MS = 5000


def host_coverage(o, hosts, steps, ph, variant):
    """Zeitliche Abdeckung der Host-Abfragen über das Beobachtungsfenster; eine einzelne frühe Abfrage genügt nie."""
    begin = steps.get('uninstall-begin' if variant == 'uninstall' else 'update-begin')
    end = num(((ph.get('post') or {}).get('end') or {}).get('t'))
    det = o.sources.get('host_queries', {}).get('detail') or {}
    if begin is None or end is None:
        o.source('host_queries', ['coverage not checkable: start of the package step or end of phase post missing'])
        return
    used = sorted((x[2], x[3]) for x in hosts)
    probs = []
    if not used:
        probs.append('no host query in the observation window')
    else:
        if used[0][0] > begin + HOST_MAX_GAP_MS:
            probs.append('first host query begins %d ms after the package step began' % (used[0][0] - begin))
        if used[-1][1] < end - HOST_MAX_GAP_MS:
            probs.append('last host query ends %d ms before the end of phase post' % (end - used[-1][1]))
        gaps = [a_n - b_p for (_, b_p), (a_n, _) in zip(used, used[1:]) if a_n > begin and b_p < end]
        long_gaps = [g for g in gaps if g > HOST_MAX_GAP_MS]
        if long_gaps:
            probs.append('%d gaps longer than %d ms between host queries (largest %d ms)' % (len(long_gaps), HOST_MAX_GAP_MS, max(long_gaps)))
        det.update(window=[begin, end], max_gap_ms=max(gaps) if gaps else None, tolerance_ms=HOST_MAX_GAP_MS)
    o.source('host_queries', probs)


def row_state(rows, d):
    if d['id'] and d['id'] in rows:
        r = rows[d['id']]
        return {'id': d['id'], 'owner': r[0], 'pending': r[1], 'trashed': r[2], 'path': r[3]}
    same = sorted(k for k, v in rows.items() if v[3] == d['path'])
    return {'id': same[0], 'owner': rows[same[0]][0], 'pending': rows[same[0]][1], 'trashed': rows[same[0]][2], 'path': rows[same[0]][3]} \
        if (d['id'] is None and len(same) == 1) else ('absent' if not same else {'other_ids': same})


def rows_and_files(o, dls, priv, disk, hosts):
    for d in dls:
        for src_name, frames in (('snapshot', priv), ('host', hosts)):
            spans, seen_present = [], False
            for ph_, n, a, b, rows, verified, src in sorted(frames, key=lambda x: (x[2], x[3])):
                if d['insertStart'] is not None and b < d['insertStart']:
                    continue
                st = row_state(rows, d)
                if spans and spans[-1]['state'] == st and spans[-1]['frames_verified'] == verified:
                    sp = spans[-1]
                    sp['t_last_end'], sp['count'], sp['src_last'] = b, sp['count'] + 1, src
                else:
                    spans.append({'state': st, 't_first_begin': a, 't_last_end': b, 'count': 1, 'frames_verified': verified, 'src_first': src, 'src_last': src})
                after = d['done'] is not None and a >= d['done'] and d['state'] == 'done'
                if isinstance(st, dict) and 'other_ids' in st:
                    o.find('ROW_OTHER_ID_FOR_PATH', src, verified, seq=d['seq'], phase=d['phase'], source=src_name, t=a, ids=st['other_ids'])
                elif st == 'absent':
                    if seen_present:
                        o.find('ROW_ABSENT_AFTER_PRESENT', src, verified, seq=d['seq'], phase=d['phase'], source=src_name, t=a)
                else:
                    seen_present = True
                    if after and st['owner'] != PKG:
                        o.find('ROW_OWNER_NOT_APP', src, verified, seq=d['seq'], phase=d['phase'], source=src_name, t=a, owner=st['owner'])
                    if after and (st['pending'] != '0' or st['trashed'] != '0'):
                        o.find('ROW_PENDING_OR_TRASHED_AGAIN', src, verified, seq=d['seq'], phase=d['phase'], source=src_name, t=a,
                               pending=st['pending'], trashed=st['trashed'])
                    if after and st['path'] != d['path']:
                        o.find('ROW_PATH_DIFFERS', src, verified, seq=d['seq'], phase=d['phase'], source=src_name, t=a, path=st['path'])
            for sp in spans:
                o.rec('row_span', seq=d['seq'], phase=d['phase'], id=d['id'], source=src_name, **sp)
        last = None
        for ph_, n, a, b, files, verified, src in disk:
            got = files.get(d['path'])
            observed = {'size': num(got[0]), 'sha256': got[1]} if got else 'missing'
            if d['state'] != 'done' or d['done'] is None or a < d['done']:
                continue
            if (observed, verified) != last:
                o.rec('file_obs', seq=d['seq'], phase=d['phase'], snapshot_phase=ph_, snapshot_n=n, t=a, observed=observed,
                      expected={'size': d['size'], 'sha256': d['sha256']}, frame_verified=verified, src=src)
                last = (observed, verified)
            if observed == 'missing':
                o.find('FILE_MISSING_AFTER_DONE', src, verified, seq=d['seq'], phase=d['phase'], t=a, snapshot=n)
            elif (observed['size'], observed['sha256']) != (d['size'], d['sha256']):
                o.find('FILE_DAMAGED', src, verified, seq=d['seq'], phase=d['phase'], t=a, snapshot=n, observed=observed)
        if d['state'] != 'done':
            fin = [x for x in disk if x[0] == 'recheck']
            if not fin:
                o.find('RESIDUAL_FILE_AFTER_FAILURE', [], False, seq=d['seq'], phase=d['phase'], state='unknown')
            else:
                ph_, n, a, b, files, verified, src = fin[-1]
                got = files.get(d['path'])
                st = 'missing' if not got else ('intact' if (num(got[0]), got[1]) == (d['size'], d['sha256'])
                                                 else ('truncated' if num(got[0]) is not None and d['size'] is not None and num(got[0]) < d['size'] else 'altered'))
                o.find('RESIDUAL_FILE_AFTER_FAILURE', src, verified, seq=d['seq'], phase=d['phase'], state=st, note='not data loss: download did not finish')


# ------------------------------------------------------------------------------------------------ Hauptablauf
def observe(a):
    o = Observer(a.variant, a.run)
    text = read(a.logcat)
    dump = read(a.logcat_dump)
    o.rec('run', tool=TOOL, inputs={k: os.path.basename(v) if v else None for k, v in vars(a).items() if k not in ('variant', 'run', 'out', 'abort', 'cmd')})
    if a.abort:
        o.find('HOST_ABORT', [], reason=a.abort)
        o.source('host_script', ['aborted: %s' % a.abort])
    if text is None:
        o.source('logcat_stream', ['missing'])
        text = ''
    refs, markers, unparsed = scan_logcat(o, text, os.path.basename(a.logcat or 'logcat'), a.run)
    syncs = sum(1 for mk in markers if mk['f'].get('event') == 'sync')
    lost = []
    if dump is not None:
        stream_lines = set(l.strip() for l in text.splitlines())
        lost = [l for l in dump.splitlines() if ' BOOTSCAN: BOOTSCAN ' in l and ('run=%s ' % a.run) in l + ' ' and l.strip() not in stream_lines]
    if text:
        o.source('logcat_stream', (['%d of 2 sync markers' % syncs] if syncs != 2 else []) + (['%d run lines in the dump missing from the stream' % len(lost)] if lost else []),
                 detail={'unparsed_nonempty_lines': unparsed})
    o.source('logcat_dump', ['missing'] if dump is None else [])
    scans, ev, problems = B.parse(text, a.run)
    o.source('logcat_run_lines', problems)
    steps = package_steps(o, a.variant, markers)
    invalid = []
    try:
        ph = B.phases([(k, r) for k, r in ev if k != 'PKG'], a.run, invalid, require_digest=True)
    except B.Invalid as e:
        ph, invalid = {}, invalid + [str(e)]
    if invalid and not any(p.get('frame_problems') for p in ph.values()):
        o.source('phase_parse', invalid)
    instr = {'pre': read(a.instr_pre), 'post': read(a.instr_post), 'recheck': read(a.instr_recheck)}
    phase_sources(o, ph, instr, refs, os.path.basename(a.logcat or ''))
    dls = downloads(o, ph, refs)
    uri_checks(o, ph, refs, dls)
    priv, disk = snapshot_frames(ph, refs)
    hosts = host_frames(o, read(a.host_sampler), os.path.basename(a.host_sampler or 'host-sampler'))
    if read(a.host_sampler) is not None:
        host_coverage(o, hosts, steps, ph, a.variant)
    rows_and_files(o, dls, priv, disk, hosts)
    windows = [('phase %s' % n, num(p['start'].get('t')), num((p.get('end') or {}).get('t'))) for n, p in ph.items() if p.get('end')]
    windows += [('package step %s' % e.rsplit('-', 1)[0], steps[e], steps.get(e.rsplit('-', 1)[0] + '-end')) for e in steps if e.endswith('-begin')]
    for r in [x for x in o.recs if x['type'] in ('proc_event', 'crash_block')]:   # nur beschreibend: in welchem Zeitfenster
        t = r.get('t', r.get('t_first'))
        r['window'] = [w for w, a0, b0 in windows if a0 is not None and b0 is not None and a0 <= t <= b0] or \
            ['after %s (+%d ms)' % (w, t - b0) for w, a0, b0 in windows if b0 is not None and 0 < t - b0 <= 10_000]
    known = {d['path']: d['seq'] for d in dls}
    for r in [x for x in o.recs if x['type'] == 'owner_event']:
        r['known_seq'] = known.get(r['path'])
        if 'BootScanProbe-' in r['path']:
            o.find('OWNER_EVENT', r['src'], path=r['path'], seq=r['known_seq'], t=r['t'], unknown_probe_file=r['known_seq'] is None)
    pk = read(a.pkgops)
    o.source('pkgops_file', ['missing'] if pk is None else [])
    for i, l in enumerate((pk or '').splitlines(), 1):
        o.rec('pkgop_output', text=l, src=[{'file': os.path.basename(a.pkgops), 'line': i}])
    try:
        before, after = B.listing(a.foreign_before), B.listing(a.foreign_after)
        removed, modified, added = B.foreign_diff(before, after)
        o.rec('foreign', removed=removed, modified=modified, added=added, lists_complete=True)
        for p in removed:
            o.find('FOREIGN_REMOVED', [{'file': os.path.basename(a.foreign_before)}], path=p)
        for p in modified:
            o.find('FOREIGN_MODIFIED', [{'file': os.path.basename(a.foreign_after)}], path=p)
        for p in added:
            o.find('FOREIGN_ADDED', [{'file': os.path.basename(a.foreign_after)}], path=p, note='not data loss')
        o.source('foreign_lists', [])
    except Exception as e:  # noqa: BLE001 - unvollständige Liste ist kein Beleg
        o.rec('foreign', removed=None, modified=None, added=None, lists_complete=False)
        o.source('foreign_lists', ['%s: %s' % (type(e).__name__, e)])
    # Überschneidung (Mess-Voraussetzung, kein Urteil)
    ext = [r for r in o.recs if r['type'] == 'scan' and r['volume'] == 'external_primary' and r['reason'] == 'REASON_MOUNTED']
    a_, b_ = ('uninstall-begin', 'uninstall-end') if a.variant == 'uninstall' else ('update-begin', 'update-end')
    hit = [s for s in ext if a_ in steps and b_ in steps and s['t_start'] <= steps[b_] and s['t_end'] >= steps[a_]]
    early = [d['seq'] for d in dls if d['phase'] == 'post' and d['insertStart'] is not None and hit and d['insertStart'] <= max(s['t_end'] for s in hit)]
    needed = ('logcat_stream', 'package_steps', 'phase_post', 'downloads')
    if hit and early:
        overlap = 'PRESENT'
    elif all(o.sources.get(n, {}).get('status') == 'complete' for n in needed):
        overlap = 'ABSENT'
    else:
        overlap = 'UNDETERMINED'
    o.rec('overlap', status=overlap, package_step=[steps.get(a_), steps.get(b_)], scans_intersecting_step=[[s['t_start'], s['t_end']] for s in hit],
          post_downloads_started_before_scan_end=early, note='measurement precondition only')
    for s in o.sources.values():
        if s['status'] != 'complete':
            o.find('MEASUREMENT_INCOMPLETE', [], source=s['name'], problems=s['problems'][:5])
    measurement = 'COMPLETE' if all(s['status'] == 'complete' for s in o.sources.values()) else 'INCOMPLETE'
    proven = [f for f in o.findings if f['kind'] in PROVEN_KINDS and f['verified']]
    summary = {'type': 'summary', 'run': a.run, 'variant': a.variant, 'measurement': measurement,
               'incomplete_sources': sorted(s['name'] for s in o.sources.values() if s['status'] != 'complete'),
               'data_loss': 'PROVEN' if proven else 'NONE_PROVEN', 'data_loss_evidence': [{k: f.get(k) for k in ('kind', 'seq', 'path', 't', 'src')} for f in proven],
               'overlap': overlap, 'findings_by_kind': {}, 'notes': NOTES}
    for f in o.findings:
        summary['findings_by_kind'][f['kind']] = summary['findings_by_kind'].get(f['kind'], 0) + 1
    os.makedirs(a.out, exist_ok=True)
    out = [dict(type='source', run=a.run, variant=a.variant, **s) for s in o.sources.values()] + o.recs + \
          [{k: v for k, v in f.items() if k != '_key'} for f in o.findings] + [summary]
    with open(os.path.join(a.out, 'observations.jsonl'), 'w') as fh:
        for r in out:
            fh.write(json.dumps(r, sort_keys=True, ensure_ascii=False) + '\n')
    lines = ['PKGAB_SUMMARY variant=%s run=%s measurement=%s data_loss=%s overlap=%s findings=%d'
             % (a.variant, a.run, measurement, summary['data_loss'], overlap, len(o.findings))]
    lines += ['PKGAB_SOURCE %s %s %s' % (s['name'], s['status'], '; '.join(s['problems'][:3])) for s in o.sources.values()]
    lines += ['PKGAB_FINDING %s verified=%s count=%d %s' % (f['kind'], f['verified'], f['count'],
                                                           json.dumps({k: v for k, v in f.items() if k not in ('_key', 'type', 'run', 'variant', 'kind', 'verified', 'count')}, sort_keys=True)[:300])
              for f in o.findings]
    lines += ['PKGAB_NOTE ' + n for n in NOTES]
    open(os.path.join(a.out, 'summary.txt'), 'w').write('\n'.join(lines) + '\n')
    # Standardausgabe (öffentliches Job-Log): nur Werte aus festen Mengen, keine Rohinhalte, Pfade oder Problemtexte
    print('\n'.join(joblog_lines(a.variant, a.run, measurement, summary, o)))
    return 3 if proven else (0 if measurement == 'COMPLETE' else 2)


SAFE_TOKEN = re.compile(r'^[A-Za-z0-9_]{1,64}$')


def joblog_lines(variant, run, measurement, summary, o):
    """Zeilen für das öffentliche Job-Log: Variante, Lauf-ID (12 Hex), Aufzählungswerte, Quellnamen mit Status und Anzahl
    Befunde je Art. Alles andere (Problemtexte, Pfade, Rohzeilen, Ergebnisse von Paketoperationen) steht nur in den
    Dateien im Runner-Verzeichnis bzw. im geprüften Artefakt."""
    safe = lambda v: v if SAFE_TOKEN.match(str(v)) else 'redacted'
    out = ['PKGAB_SUMMARY variant=%s run=%s measurement=%s data_loss=%s overlap=%s findings=%d'
           % (safe(variant), safe(run), safe(measurement), safe(summary['data_loss']), safe(summary['overlap']), len(o.findings))]
    out += ['PKGAB_SOURCE %s %s' % (safe(s['name']), safe(s['status'])) for s in o.sources.values()]
    out.append('PKGAB_FINDINGS %s' % (' '.join('%s=%d' % (safe(k), v) for k, v in sorted(summary['findings_by_kind'].items())) or 'none'))
    out += ['PKGAB_NOTE ' + n for n in NOTES]
    out.append('PKGAB_NOTE details (problems, findings, raw data) are only in summary.txt, observations.jsonl and the raw files')
    return out


def last_summary(d):
    p = os.path.join(d, 'observations.jsonl')
    if not os.path.isfile(p):
        return None
    recs = [json.loads(l) for l in open(p) if l.strip()]
    return recs[-1] if recs and recs[-1].get('type') == 'summary' else None


def index(dirs):
    arms, reasons = {}, []
    for d in dirs:
        s = last_summary(d)
        if s is None:
            reasons.append('%s: no summary' % d)
            continue
        arms[s['variant']] = s
        print('PKGAB_ARM variant=%s run=%s measurement=%s data_loss=%s overlap=%s' % (s['variant'], s['run'], s['measurement'], s['data_loss'], s['overlap']))
    for v in ('control', 'uninstall'):
        if v not in arms:
            reasons.append('arm %s missing' % v)
        elif arms[v]['measurement'] != 'COMPLETE':
            reasons.append('arm %s measurement %s' % (v, arms[v]['measurement']))
        elif arms[v]['overlap'] != 'PRESENT':
            reasons.append('arm %s overlap %s' % (v, arms[v]['overlap']))
    if len(arms) == 2 and arms['control']['run'] == arms['uninstall']['run']:
        reasons.append('both arms report the same run id')
    loss = any(s['data_loss'] == 'PROVEN' for s in arms.values())
    print('PKGAB_AB_PRECONDITIONS %s%s' % ('NOT_MET' if reasons else 'MET', ' ' + '; '.join(reasons) if reasons else ''))
    print('PKGAB_NOTE preconditions describe the measurement only; no causal conclusion is drawn')
    for d in dirs:
        for root, _, files in sorted(os.walk(d)):
            for f in sorted(files):
                p = os.path.join(root, f)
                print('PKGAB_FILE %s %d %s' % (hashlib.sha256(open(p, 'rb').read()).hexdigest(), os.path.getsize(p), os.path.relpath(p, os.path.dirname(d.rstrip('/')))))
    if loss:
        return 3
    if any('missing' in r or 'measurement' in r or 'no summary' in r for r in reasons):
        return 2
    return 4 if reasons else 0


SECRET = [('github token', re.compile(r'\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}')),
          ('private key', re.compile(r'-----BEGIN [A-Z ]*PRIVATE KEY-----')), ('aws key', re.compile(r'\bAKIA[0-9A-Z]{16}\b')),
          ('authorization header', re.compile(r'(?i)authorization:\s*(bearer|basic|token)\s+[A-Za-z0-9+/=._-]{8,}')),
          ('keystore password', re.compile(r'(?i)(channelKeystorePass|storePassword|keyPassword|storepass|keypass)\s*[=:]\s*\S{4,}'))]


def check_upload(d):
    bad = []
    for root, _, files in os.walk(d):
        for f in files:
            p = os.path.join(root, f)
            if f.endswith(('.apk', '.so', '.p12', '.jks', '.keystore')):
                bad.append('%s: binary/key file' % p)
                continue
            for i, l in enumerate(open(p, encoding='utf-8', errors='replace'), 1):
                for name, rx in SECRET:
                    if rx.search(l):
                        bad.append('%s:%d: %s' % (os.path.relpath(p, d), i, name))   # Wert wird nie ausgegeben
    for b in bad:
        print('PKGAB_UPLOAD_BLOCKED ' + b)
    print('PKGAB_UPLOAD_CHECK %s %d finding(s)' % ('BLOCKED' if bad else 'OK', len(bad)))
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    v = sub.add_parser('observe')
    v.add_argument('--variant', required=True, choices=['control', 'uninstall'])
    v.add_argument('--run', required=True)
    v.add_argument('--out', required=True)
    for k in ('logcat', 'logcat-dump', 'host-sampler', 'pkgops', 'instr-pre', 'instr-post', 'instr-recheck', 'foreign-before', 'foreign-after'):
        v.add_argument('--' + k)
    v.add_argument('--abort')
    i = sub.add_parser('index')
    i.add_argument('dirs', nargs='+')
    c = sub.add_parser('check-upload')
    c.add_argument('dir')
    a = ap.parse_args()
    if a.cmd == 'index':
        sys.exit(index(a.dirs))
    if a.cmd == 'check-upload':
        sys.exit(check_upload(a.dir))
    if not re.fullmatch(r'[0-9a-f]{12}', a.run):
        print('PKGAB_SUMMARY variant=%s run=invalid measurement=INCOMPLETE data_loss=NONE_PROVEN overlap=UNDETERMINED findings=0' % a.variant)
        sys.exit(2)
    sys.exit(observe(a))


if __name__ == '__main__':
    main()
