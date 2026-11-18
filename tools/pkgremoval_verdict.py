#!/usr/bin/env python3
"""Bewertung des A/B-Diagnosetests "verzögerte Paket-Deinstallation" (tools/run-pkgremoval-probe.sh). Nur Diagnose; es
gibt kein PASS. Baut auf tools/bootscan_verdict.py auf (gleicher Parser, gleiche Klassen je Download).

  pkgremoval_verdict.py variant --variant control|uninstall --run RUN --logcat L --host-sampler H \
      --instr-pre I1 --instr-post I2 --instr-recheck I3 --foreign-before B --foreign-after A
  pkgremoval_verdict.py ab CONTROL_VERDICT EXPERIMENT_VERDICT      (Ausgaben der beiden variant-Aufrufe)

Generationen: install1 = Dateien der ersten Installation (Phase pre); install2 = nach Deinstallation und Neuinstallation
(Variante uninstall, Phase post); updated = nach "install -r" (Variante control, Phase post). Sie werden getrennt
ausgewiesen und bewertet.

Klassen je Download: wie bootscan_verdict (OK, TRANSIENT, OWNER_CHANGED, STATE_CHANGED, REPLACED, LOST, UNEXPLAINED,
PUBLISH_FAILED, DATA_LOSS, UNKNOWN), dazu für install1 in der Variante uninstall:
  ORPHANED_EXPECTED  gleiche ID, Datei unverändert, Besitzer aufgehoben (NULL) – das normale Verhalten bei Deinstallation
  NOT_ORPHANED       nach der Deinstallation weiterhin dem (neu installierten) Paket zugeordnet und sichtbar (gemeldet)

Zeit der Besitzer-Aufhebung T_o (nur Variante uninstall): frühester Beleg nach "uninstall-begin" – eine
"owner_package_changed"-Zeile des MediaProvider (genau) oder die erste privilegierte Aufnahme (Host mit Gerätezeit in
ms bzw. Testprozess), in der eine install1-Zeile nicht mehr dem Paket gehört; dazu die letzte Aufnahme davor (Intervall).
"Verzögert" heißt: T_o liegt nachweislich NACH dem Ende der Neuinstallation ("install2-end").

Ergebnis je Variante (Zeile PKGAB_VARIANT):
  uninstall: SUPPORTED     verzögert UND mindestens ein install2-Download, der vor T_o angelegt/veröffentlicht wurde, ist
                           OWNER_CHANGED, LOST, REPLACED, STATE_CHANGED oder PUBLISH_FAILED
             NOT_SUPPORTED verzögert, solche Downloads vorhanden, alle OK
             INCONCLUSIVE  keine Besitzer-Aufhebung beobachtet, Zeitpunkt nicht eindeutig, nicht verzögert, oder kein
                           install2-Download vor T_o
  control:   CLEAN         alle Downloads OK, keine Besitzer-Aufhebung, mindestens ein Download über das Ende des
                           Mount-Scans hinweg
             ANOMALY       irgendeine Abweichung (gemeldet)
             INCONCLUSIVE  kein Download überdeckt das Ende des Mount-Scans
  beide:     DATA_LOSS     Sondendatei fehlt/verändert oder vorhandene fremde Datei gelöscht/verändert (neu hinzugekommene
                           Systemdateien sind KEIN Datenverlust, sie werden als PKGAB_FOREIGN_ADDED ausgewiesen)
             INVALID       unvollständige oder widersprüchliche Belege
Ergebnis A/B (Zeile PKGAB_RESULT): HYPOTHESIS_SUPPORTED (uninstall SUPPORTED und control CLEAN),
HYPOTHESIS_NOT_SUPPORTED (uninstall NOT_SUPPORTED, control nicht INVALID/DATA_LOSS), sonst INCONCLUSIVE; INVALID bzw.
DATA_LOSS einer Variante haben Vorrang. Exit: 0 schlüssig (SUPPORTED oder NOT_SUPPORTED), 2 INVALID, 3 DATA_LOSS,
4 INCONCLUSIVE (für variant: 0 schlüssig/CLEAN, 5 ANOMALY).
"""
import argparse
import importlib.util
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('bootscan_verdict_for_pkgremoval', os.path.join(HERE, 'bootscan_verdict.py'))
B = importlib.util.module_from_spec(spec)
spec.loader.exec_module(B)

PHASES = ('pre', 'post', 'recheck')
SEQ0 = {'pre': 20000, 'post': 30000}
OFFSETS = {'pre': [0, 250], 'post': [0, 250, 2000, 10000]}
EVENTS = {'uninstall': ['install1-begin', 'install1-end', 'uninstall-begin', 'uninstall-end', 'install2-begin', 'install2-end'],
          'control': ['install1-begin', 'install1-end', 'update-begin', 'update-end']}
BAD = ('OWNER_CHANGED', 'LOST', 'REPLACED', 'STATE_CHANGED', 'PUBLISH_FAILED')
QT_ROW = re.compile(r'^Row: \d+ _id=(\d+), owner_package_name=([^,]*), is_pending=(\d+), is_trashed=(\d+), _data=([^,]*), _display_name=([^,]*), _size=([^,]*)$')


def host_samples(text):
    """'QT-BEGIN t=… res=…' … 'QT-END rc=0' -> [(t_ms, res, {id: (owner, pending, trashed, path)})]; ungültige Blöcke fallen weg."""
    out, cur, t, res = [], None, None, None
    for l in text.splitlines():
        m = re.match(r'^QT-BEGIN t=(\d+) res=(\w+)$', l)
        if m:
            cur, t, res = {}, int(m.group(1)), m.group(2)
            continue
        if cur is None:
            continue
        if l == 'QT-END rc=0':
            if res in ('ms', 's') and cur is not False:
                out.append((t, res, cur))
            cur = None
        elif l.startswith('QT-END'):
            cur = None
        elif l == 'No result found.':
            pass
        elif cur is not False:
            m = QT_ROW.match(l)
            if m:
                cur[m.group(1)] = (m.group(2).replace(' ', '_'), m.group(3), m.group(4), m.group(5).replace(' ', '_'))
            else:
                cur = False   # unlesbare Zeile: ganzer Block kein Beleg
    return out


def markers(ev, run, variant, invalid):
    out = {}
    for kind, rest in ev:
        if kind != 'PKG':
            continue
        f = B.kv(rest)
        if f.get('run') != run or f.get('variant') != variant:
            invalid.append('package marker of another run/variant: %s' % rest[:80])
            continue
        if f.get('event') in out:
            invalid.append('package marker %s twice' % f.get('event'))
        out[f.get('event')] = f
    return out


def marker_times(logcat, run):
    """Logcat-Zeit (ms) je Paket-Marker."""
    out = {}
    for raw in logcat.splitlines():
        m = B.LINE.match(raw)
        if m and m.group(5).strip() == 'BOOTSCAN' and m.group(6).startswith('BOOTSCAN PKG ') and ('run=%s ' % run) in m.group(6):
            e = re.search(r'\bevent=(\S+)', m.group(6))
            if e:
                out[e.group(1)] = int(round(float(m.group(1)) * 1000))
    return out


def owner_changes(logcat, token):
    """owner_package_changed-Zeilen des MediaProvider, die eine Sondendatei dieses Laufs nennen -> [(t_ms, tid, Text)]."""
    out = []
    for raw in logcat.splitlines():
        m = B.LINE.match(raw)
        if m and m.group(5).strip() == 'MediaProvider' and 'owner_package_changed' in m.group(6) and token in m.group(6):
            out.append((int(round(float(m.group(1)) * 1000)), m.group(3), m.group(6)[-120:]))
    return out


def structure(ph, instr, invalid):
    Report = B.report_class()
    for name in PHASES:
        rep = Report(instr[name].splitlines())
        if rep.tests() != [(B.PROBE_CLASS, 'probe')] or not rep.ok or rep.problems:
            invalid.append('instrumentation of phase %s is not exactly the completed probe test (%s)' % (name, rep.problems[:2]))
        p = ph.get(name)
        if p is None:
            invalid.append('phase %s missing (no START)' % name)
            continue
        if p['end'] is None:
            invalid.append('phase %s has no END line' % name)
            continue
        ns = [s['n'] for s in p['snaps']]
        if ns != list(range(1, len(ns) + 1)) or len(ns) != int(p['end'].get('snapshots', -1)):
            invalid.append('phase %s snapshots not contiguous or count differs from END' % name)
        if not any(s['ok'] for s in p['dsnaps']):
            invalid.append('phase %s has no valid disk snapshot' % name)
        if name in SEQ0:
            seqs = [int(d['seq']) for d in p['dl']]
            if seqs != list(range(SEQ0[name], SEQ0[name] + len(seqs))) or len(seqs) != int(p['end'].get('downloads', -1)):
                invalid.append('phase %s downloads not contiguous or count differs from END' % name)
            for d in p['dl']:
                if d.get('uri', '-') != '-':
                    got = sorted(int(q['off']) for q in p['uriq'] if q['seq'] == d['seq'])
                    if got != OFFSETS[name]:
                        invalid.append('phase %s seq %s: URI checks at %s, expected %s' % (name, d['seq'], got, OFFSETS[name]))
                    if sum(1 for q in ph.get('recheck', {}).get('uriq', []) if q['seq'] == d['seq'] and q['off'] == '-1') != 1:
                        invalid.append('recheck has no single URI query for %s seq %s' % (name, d['seq']))
    if ph.get('pre') and len(ph['pre']['dl']) != 3:
        invalid.append('phase pre published %d files, expected 3' % len(ph['pre']['dl']))


def orphan_time(variant, ph, hosts, owner_lines, mt, pkg):
    """-> (T_low, T_high, Quelle) der Besitzer-Aufhebung von install1-Zeilen, oder None."""
    if variant != 'uninstall':
        return None
    start = mt.get('uninstall-begin')
    ids = {d['id'] for d in ph['pre']['dl'] if d.get('id', '-') != '-'}
    exact = [t for t, tid, msg in owner_lines if start is not None and t >= start]
    obs = [(t, res, rows) for t, res, rows in hosts if start is not None and t >= start]
    for s in ph['post']['snaps'] + ph['recheck']['snaps']:
        if s['valid']:
            obs.append((s['t'], 'ms', {k: v for k, v in s['priv'].items()}))
    obs.sort(key=lambda x: x[0])
    last_owned, first_changed = start, None
    for t, res, rows in obs:
        mine = [rows[i] for i in ids if i in rows]
        changed = any(r[0] != pkg for r in mine) or (len(mine) < len(ids) and t > (start or 0))
        if changed:
            first_changed = t if res == 'ms' else t + 999
            break
        last_owned = t if res == 'ms' else t
    if exact:
        return (min(exact), min(exact), 'owner_package_changed line')
    if first_changed is None:
        return None
    return (last_owned, first_changed, 'privileged snapshots')


def variant_verdict(variant, run, logcat, hosts_text, instr, before, after, pkg='de.evp.helfer.channeltest'):
    invalid, notes = [], []
    scans, ev, problems = B.parse(logcat, run)
    invalid += problems
    mk = markers(ev, run, variant, invalid)
    ev = [(k, r) for k, r in ev if k != 'PKG']
    try:
        ph = B.phases(ev, run, invalid)
    except B.Invalid as e:
        return 'INVALID', [str(e)], [], notes
    structure(ph, instr, invalid)
    missing = [e for e in EVENTS[variant] if e not in mk]
    if missing:
        invalid.append('package markers missing: %s' % missing)
    for p in ph.values():
        if p.get('pkg') != pkg:
            invalid.append('phase %s ran in package %s' % (p['phase'], p.get('pkg')))
    if invalid:
        return 'INVALID', invalid, [], notes
    mt = marker_times(logcat, run)
    order = [mt.get(e) for e in EVENTS[variant]]
    if None in order or order != sorted(order):
        return 'INVALID', ['package markers out of order or without time: %s' % dict(zip(EVENTS[variant], order))], [], notes
    pkg_end = mt['install2-end' if variant == 'uninstall' else 'update-end']
    uids = {B.kv(r).get('phase'): B.kv(r).get('appUid') for k, r in ev if k == 'START'}
    hosts = host_samples(hosts_text)
    owner_lines = owner_changes(logcat, 'BootScanProbe-%s-' % run)
    ext = [s for s in scans if s['volume'] == 'external_primary' and s['reason'] == 'REASON_MOUNTED']
    notes.append('markers %s' % ' '.join('%s=%d' % (e, mt[e]) for e in EVENTS[variant]))
    notes.append('mount scans external_primary %s' % [(s['start'], s['end'], s['inserts'], s['updates']) for s in ext])
    notes.append('owner_package_changed lines %s' % [(t, tid, m[-60:]) for t, tid, m in owner_lines])
    notes.append('host samples %d (%s)' % (len(hosts), sorted({r for _, r, _ in hosts})))
    notes.append('app uid pre=%s post=%s recheck=%s' % (uids.get('pre'), uids.get('post'), uids.get('recheck')))
    rows, data_loss = [], []
    to = orphan_time(variant, ph, hosts, owner_lines, mt, pkg)
    if to:
        notes.append('owner removal of install1 rows between %d and %d (%s); package step ended %d' % (to[0], to[1], to[2], pkg_end))
    for name in ('pre', 'post'):
        gen = 'install1' if name == 'pre' else ('install2' if variant == 'uninstall' else 'updated')
        for d in ph[name]['dl']:
            d['phase'] = name
            cls, why = B.classify(d, ph, pkg)
            if gen == 'install1' and variant == 'uninstall':
                if cls == 'OWNER_CHANGED' and 'owner NULL' in why:
                    cls, why = 'ORPHANED_EXPECTED', 'normal uninstall behaviour: ' + why
                elif cls == 'OK':
                    cls, why = 'NOT_ORPHANED', 'still owned by the reinstalled package after the uninstall: ' + why
            lo = int(d.get('insertStart', -1))
            hi = max(int(d.get('commitEnd', -1)), int(d.get('done', -1)))
            at_risk = to is not None and gen == 'install2' and lo > 0 and lo <= to[1]
            spans_scan_end = any(lo <= s['end'] <= hi + 10_000 for s in ext) if lo > 0 else False
            rows.append({'seq': d['seq'], 'gen': gen, 'cls': cls, 'why': why, 'at_risk': at_risk, 'spans_scan_end': spans_scan_end,
                         'id': d.get('id'), 'lo': lo, 'hi': hi})
            if cls == 'DATA_LOSS':
                data_loss.append('seq %s: %s' % (d['seq'], why))
    removed, modified, added = B.foreign_diff(before, after)
    if added:
        notes.append('FOREIGN_ADDED (not data loss): %s' % added[:6])
    if removed or modified:
        data_loss.append('existing files outside the probe removed %s or modified %s' % (removed[:5], modified[:5]))
    if data_loss:
        return 'DATA_LOSS', data_loss, rows, notes
    if variant == 'control':
        bad = [r for r in rows if r['cls'] != 'OK']
        orphan_like = [t for t, tid, m in owner_lines if t >= mt['update-begin']]
        if bad or orphan_like:
            return 'ANOMALY', ['%s seq %s: %s' % (r['gen'], r['seq'], r['cls']) for r in bad][:10] + (
                ['owner_package_changed after the update: %s' % orphan_like[:3]] if orphan_like else []), rows, notes
        if not any(r['spans_scan_end'] for r in rows if r['gen'] == 'updated'):
            return 'INCONCLUSIVE', ['no download after the update spans the end of the mount scan'], rows, notes
        return 'CLEAN', ['all %d downloads OK, no owner removal, downloads span the mount scan end' % len(rows)], rows, notes
    late_bad = [r for r in rows if r['gen'] == 'install2' and not r['at_risk'] and r['cls'] in BAD]
    if late_bad:
        notes.append('new downloads failing outside the hypothesis window: %s' % [(r['seq'], r['cls']) for r in late_bad][:10])
    if to is None:
        return 'INCONCLUSIVE', ['no owner removal of the first installation observed after the uninstall'], rows, notes
    if to[1] <= pkg_end:
        return 'INCONCLUSIVE', ['owner removal happened before the reinstall ended (not delayed): %d <= %d' % (to[1], pkg_end)], rows, notes
    if to[0] < pkg_end:
        return 'INCONCLUSIVE', ['owner removal time ambiguous: between %d and %d, reinstall ended %d' % (to[0], to[1], pkg_end)], rows, notes
    risk = [r for r in rows if r['at_risk']]
    if not risk:
        return 'INCONCLUSIVE', ['delayed owner removal at %d, but no download of the new installation before it' % to[1]], rows, notes
    hit = [r for r in risk if r['cls'] in BAD]
    if late_bad:
        return 'INCONCLUSIVE', ['%d new downloads made after the delayed owner removal failed too (%s): the hypothesis does not explain them'
                                % (len(late_bad), sorted({r['cls'] for r in late_bad}))], rows, notes
    if hit:
        return 'SUPPORTED', ['delayed owner removal (%d > reinstall end %d) hit %d of %d new downloads made before it: %s'
                             % (to[0], pkg_end, len(hit), len(risk), sorted({r['cls'] for r in hit}))], rows, notes
    if any(r['cls'] == 'UNKNOWN' for r in risk):
        return 'INCONCLUSIVE', ['new downloads before the delayed owner removal include UNKNOWN classifications'], rows, notes
    return 'NOT_SUPPORTED', ['delayed owner removal (%d > reinstall end %d), all %d new downloads made before it stayed OK'
                             % (to[0], pkg_end, len(risk))], rows, notes


def ab(control, experiment):
    c = re.findall(r'(?m)^PKGAB_VARIANT variant=control result=(\w+)$', control)
    e = re.findall(r'(?m)^PKGAB_VARIANT variant=uninstall result=(\w+)$', experiment)
    if len(c) != 1 or len(e) != 1:
        return 'INVALID', ['variant results missing or ambiguous (control %s, uninstall %s)' % (c, e)]
    c, e = c[0], e[0]
    if 'INVALID' in (c, e):
        return 'INVALID', ['control %s, uninstall %s' % (c, e)]
    if 'DATA_LOSS' in (c, e):
        return 'DATA_LOSS', ['control %s, uninstall %s' % (c, e)]
    if e == 'SUPPORTED' and c == 'CLEAN':
        return 'HYPOTHESIS_SUPPORTED', ['uninstall SUPPORTED, control CLEAN']
    if e == 'NOT_SUPPORTED':
        return 'HYPOTHESIS_NOT_SUPPORTED', ['uninstall NOT_SUPPORTED, control %s' % c]
    return 'INCONCLUSIVE', ['control %s, uninstall %s' % (c, e)]


def main():
    a = argparse.ArgumentParser()
    sub = a.add_subparsers(dest='mode', required=True)
    v = sub.add_parser('variant')
    v.add_argument('--variant', required=True, choices=['control', 'uninstall'])
    for k in ('run', 'logcat', 'host-sampler', 'instr-pre', 'instr-post', 'instr-recheck', 'foreign-before', 'foreign-after'):
        v.add_argument('--' + k, required=True)
    v.add_argument('--package', default='de.evp.helfer.channeltest')
    w = sub.add_parser('ab')
    w.add_argument('control')
    w.add_argument('experiment')
    o = a.parse_args()
    read = lambda p: open(p, encoding='utf-8', errors='replace').read()
    if o.mode == 'ab':
        try:
            result, reasons = ab(read(o.control), read(o.experiment))
        except Exception as e:  # noqa: BLE001
            result, reasons = 'INVALID', ['evaluation error: %s: %s' % (type(e).__name__, e)]
        for r in reasons:
            print('PKGAB_REASON ' + r)
        print('PKGAB_RESULT ' + result)
        sys.exit({'HYPOTHESIS_SUPPORTED': 0, 'HYPOTHESIS_NOT_SUPPORTED': 0, 'INVALID': 2, 'DATA_LOSS': 3, 'INCONCLUSIVE': 4}[result])
    rows, notes = [], []
    try:
        result, reasons, rows, notes = variant_verdict(o.variant, o.run, read(o.logcat), read(o.host_sampler),
                                                       {'pre': read(o.instr_pre), 'post': read(o.instr_post), 'recheck': read(o.instr_recheck)},
                                                       B.listing(o.foreign_before), B.listing(o.foreign_after), o.package)
    except Exception as e:  # noqa: BLE001 - unlesbare Eingaben sind INVALID
        result, reasons = 'INVALID', ['evaluation error: %s: %s' % (type(e).__name__, e)]
    for r in rows:
        print('PKGAB_DOWNLOAD variant=%s seq=%s gen=%s class=%s at_risk=%s id=%s insert=%d done=%d %s'
              % (o.variant, r['seq'], r['gen'], r['cls'], r['at_risk'], r['id'], r['lo'], r['hi'], r['why']))
    counts = {}
    for r in rows:
        counts.setdefault(r['gen'], {}).setdefault(r['cls'], 0)
        counts[r['gen']][r['cls']] += 1
    for g, c in sorted(counts.items()):
        print('PKGAB_GENERATION variant=%s %s %s' % (o.variant, g, ' '.join('%s=%d' % kv for kv in sorted(c.items()))))
    for n in notes:
        print('PKGAB_NOTE variant=%s %s' % (o.variant, n))
    for r in reasons:
        print('PKGAB_REASON variant=%s %s' % (o.variant, r))
    print('PKGAB_VARIANT variant=%s result=%s' % (o.variant, result))
    sys.exit({'SUPPORTED': 0, 'NOT_SUPPORTED': 0, 'CLEAN': 0, 'ANOMALY': 5, 'INVALID': 2, 'DATA_LOSS': 3, 'INCONCLUSIVE': 4}[result])


if __name__ == '__main__':
    main()
