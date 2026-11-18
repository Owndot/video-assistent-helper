#!/usr/bin/env python3
"""Bewertung des A/B-Diagnosetests "verzögerte Paket-Deinstallation" (tools/run-pkgremoval-probe.sh). Nur Diagnose; es
gibt kein PASS. Baut auf tools/bootscan_verdict.py auf (Parser, strenge Aufnahmeprüfung snapshots=v2, Klassen je Download).
Regeln Stand Codex-NO-GO 1 (Regel pkgab=v2).

  pkgremoval_verdict.py variant --variant control|uninstall --run RUN --logcat L --host-sampler H \
      --instr-pre I1 --instr-post I2 --instr-recheck I3 --foreign-before B --foreign-after A
  pkgremoval_verdict.py ab CONTROL_VERDICT EXPERIMENT_VERDICT      (Ausgaben der beiden variant-Aufrufe)

Was gemessen wird – und was nicht: Der Test misst die zeitliche Abfolge von Mount-Scan, Paketoperation und
Besitzerwechsel bzw. Verlust KONKRETER MediaStore-Zeilen. Eine passende Abfolge STÜTZT die Hypothese; sie beweist nicht,
dass eine interne MediaProvider-Warteschlange die Ursache ist (PKGAB_CLAIM).

Paketoperationen (Marker "BOOTSCAN PKG … event=…", Gerätezeit): jede Operation hat op-begin/op-end mit Rückgabecode und
Ergebnis. Installationen: letzter Versuch je APK rc=0 und "Success". Deinstallation (nur uninstall): genau
uninstall-test und uninstall-app, je rc=0 und "Success", danach "absent" mit pm path und Paketliste leer – erst dann
Neuinstallation. Ein UID-Wechsel ist KEIN Nachweis. Fehlt etwas davon: INVALID.

Generationen: install1 = Phase pre (START nach install1-end, gleiche appUid wie der Marker, Ende vor dem Paketschritt);
install2 bzw. updated = Phase post (START nach dem Ende des Paketschritts, appUid wie der Marker). Jede Zeile muss in ID,
URI, Pfad und Zeitachse zu ihrer Phase passen, sonst INVALID.

Besitzer-Zeitachse je Zeile: Beobachtungen aus gültigen privilegierten Aufnahmen des Testprozesses [t, tEnd] und aus
gültigen Host-Abfragen [QT-BEGIN t, QT-END t] (Zustand: own | NULL | anderer Besitzer | absent). Wechsel = Intervall
[Beginn der letzten own-Beobachtung, Ende der ersten anderen Beobachtung]; eine owner_package_changed-Zeile für GENAU
diesen Pfad kann das Ende nur vorziehen (Protokollzeit = spätestens), nie einen exakten Zeitpunkt erzeugen. Zeilen für
unbekannte Sondendateien: INVALID. Widerspruch Logcat/Provider (own nach protokolliertem Wechsel, Rückwechsel): kein
Urteil möglich (INCONCLUSIVE).

Verwaisen der ersten Installation O = Schnitt der Wechselintervalle aller drei install1-Zeilen (alle nach NULL).
Relevante Überschneidung: ein externer REASON_MOUNTED-Scan [Beginn, Ende] schneidet den Paketschritt
(uninstall-begin..uninstall-end bzw. update-begin..update-end). Verzögert: O beginnt nach install2-end und endet nicht
vor dem Scan-Ende. install2-Zeilen: at_risk (Anlegen vor Beginn von O), ambiguous (Anlegen in O), late (nach O).

Ergebnis je Variante (Zeile PKGAB_VARIANT):
  uninstall: SUPPORTED     alle Bedingungen erfüllt und mindestens eine at_risk-Zeile mit PASSENDEM Schaden: Besitzer NULL
                           bzw. Zeile verschwunden mit Wechselintervall in O, oder Veröffentlichungsfehler im Commit
                           (commit=ex:…, nicht z. B. failed:network) nach Beginn von O ohne verbleibende Zeile, Datei intakt;
                           keine at_risk-Zeile unversehrt, nichts Unsicheres, keine späten Fehler
             NOT_SUPPORTED alle Bedingungen erfüllt, jede at_risk-/ambiguous-Zeile OK UND nach O privilegiert als eigen
                           beobachtet, keine späten Fehler, keine Absturz-/OOM-Belege
             INCONCLUSIVE  alles andere (keine Überschneidung, kein/teilweises/mehrdeutiges/nicht verzögertes Verwaisen,
                           keine at_risk-Zeile, UNKNOWN/TRANSIENT/UNEXPLAINED, unpassende Schäden, Widersprüche,
                           zu kurze Nachbeobachtung, Absturz-/OOM-Belege)
  control:   CLEAN         Überschneidung Scan/Aktualisierung, alle Zeilen OK, keine owner_package_changed-Zeile einer
                           Sondendatei, mind. eine updated-Zeile vor dem Scan-Ende angelegt und danach als eigen beobachtet
             ANOMALY       Abweichung ohne Deinstallation (gemeldet)
             INCONCLUSIVE  sonst
  beide:     DATA_LOSS     belegt (vollständige Dateilisten bzw. gültige letzte Dateiaufnahme) – auch wenn andere Belege
                           unvollständig sind; neu hinzugekommene fremde Dateien nur FOREIGN_ADDED
             INVALID       unvollständige oder widersprüchliche Belege
ORPHANED_EXPECTED nur für nachgewiesene install1-Zeilen der Variante uninstall (gleiche ID und Pfad, Besitzer NULL,
veröffentlicht, Datei unverändert, Wechsel beobachtet) – das normale Deinstallationsverhalten.

A/B (Zeile PKGAB_RESULT), nur aus definierten, gültigen Arm-Ergebnissen zweier verschiedener Läufe:
  DATA_LOSS, wenn ein Arm DATA_LOSS; sonst INVALID, wenn ein Arm INVALID oder ein Ergebnis fehlt/unbekannt ist;
  HYPOTHESIS_SUPPORTED nur bei uninstall SUPPORTED und control CLEAN; HYPOTHESIS_NOT_SUPPORTED nur bei uninstall
  NOT_SUPPORTED und control CLEAN; sonst INCONCLUSIVE. Beide Arm-Ergebnisse werden immer mit ausgegeben (PKGAB_ARM).
Exit: 0 HYPOTHESIS_SUPPORTED/NOT_SUPPORTED, 2 INVALID, 3 DATA_LOSS, 4 INCONCLUSIVE (variant: 0 SUPPORTED/NOT_SUPPORTED/
CLEAN, 5 ANOMALY, 2, 3, 4).
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

RULES = 'v2'
PHASES = ('pre', 'post', 'recheck')
SEQ0 = {'pre': 20000, 'post': 30000}
OFFSETS = {'pre': [0, 250], 'post': [0, 250, 2000, 10000]}
STEPS = {'uninstall': ['install1-begin', 'install1-end', 'uninstall-begin', 'absent', 'uninstall-end', 'install2-begin', 'install2-end'],
         'control': ['install1-begin', 'install1-end', 'update-begin', 'update-end']}
STEP_OPS = {('install1-begin', 'install1-end'): 'install', ('install2-begin', 'install2-end'): 'install', ('update-begin', 'update-end'): 'install',
            ('uninstall-begin', 'absent'): 'uninstall'}
RESULTS = {'uninstall': ('SUPPORTED', 'NOT_SUPPORTED', 'INCONCLUSIVE', 'INVALID', 'DATA_LOSS'),
           'control': ('CLEAN', 'ANOMALY', 'INCONCLUSIVE', 'INVALID', 'DATA_LOSS')}
OBSERVE_AFTER_MS = 5000
QT_ROW = re.compile(r'^Row: \d+ _id=(\d+), owner_package_name=([^,]*), is_pending=(\d+), is_trashed=(\d+), _data=([^,]*), _display_name=([^,]*), _size=([^,]*)$')
OWNER_LINE = re.compile(r'Reason: owner_package_changed:(.+)$')
RELEVANT_PROC = re.compile(r'de\.evp\.helfer|providers\.media|system_server')
CLAIM = ('correlation of mount scan, package operation and owner change/loss of concrete rows; this does not prove an '
         'internal MediaProvider queue as the cause')


# ------------------------------------------------------------------------------------------------ Eingaben
def host_samples(text, notes):
    """'QT-BEGIN t=… res=ms' … 'QT-END rc=0 t=…' -> [(a, b, {id: (owner, pending, trashed, path)})]. Nur vollständige
    Blöcke: Rahmen, rc=0, Endzeit, jede Zeile lesbar, leer nur mit ausdrücklichem "No result found."."""
    out, bad, cur = [], 0, None
    for l in text.splitlines():
        m = re.match(r'^QT-BEGIN t=(\d+) res=(\w+)$', l)
        if m:
            if cur is not None:
                bad += 1
            cur = {'a': int(m.group(1)), 'res': m.group(2), 'rows': {}, 'none': False, 'ok': True}
            continue
        if cur is None:
            continue
        if l.startswith('QT-END'):
            e = re.match(r'^QT-END rc=0 t=(\d+)$', l)
            if e and cur['ok'] and cur['res'] in ('ms', 's') and (cur['none'] != bool(cur['rows'])):
                b = int(e.group(1)) + (999 if cur['res'] == 's' else 0)
                out.append((cur['a'], b, cur['rows']))
            else:
                bad += 1
            cur = None
        elif l == 'No result found.':
            cur['none'] = True
        else:
            r = QT_ROW.match(l)
            if r:
                cur['rows'][r.group(1)] = (r.group(2).replace(' ', '_'), r.group(3), r.group(4), r.group(5).replace(' ', '_'))
            else:
                cur['ok'] = False
    if cur is not None:
        bad += 1
    notes.append('host samples: %d complete, %d discarded (incomplete frame, error, missing end time or empty without "No result found.")' % (len(out), bad))
    return out


def markers(logcat, run, variant, invalid):
    """-> Liste {'t', 'event', 'f'} aller PKG-Marker in Logreihenfolge."""
    out = []
    for raw in logcat.splitlines():
        m = B.LINE.match(raw)
        if not (m and m.group(5).strip() == 'BOOTSCAN' and m.group(6).startswith('BOOTSCAN PKG ')):
            continue
        f = B.kv(m.group(6)[len('BOOTSCAN PKG '):])
        if f.get('run') != run or f.get('variant') != variant:
            invalid.append('package marker of another run/variant: %s' % m.group(6)[:90])
            continue
        out.append({'t': int(round(float(m.group(1)) * 1000)), 'event': f.get('event'), 'f': f})
    return out


def package_steps(variant, mk, invalid, notes):
    """Prüft Reihenfolge der Schritte und jede Paketoperation. -> {Schritt: Zeit}, {Schritt: uid}."""
    steps = [x for x in mk if x['event'] not in ('op-begin', 'op-end', 'sync')]
    names = [x['event'] for x in steps]
    if names != STEPS[variant]:
        invalid.append('package steps %s, expected exactly %s' % (names, STEPS[variant]))
        return None, None
    t = {x['event']: x['t'] for x in steps}
    if [t[e] for e in names] != sorted(t[e] for e in names):
        invalid.append('package steps out of time order')
        return None, None
    for (a, b), kind in STEP_OPS.items():
        if a not in t:
            continue
        inside = [x for x in mk if t[a] < x['t'] < t[b] and x['event'] in ('op-begin', 'op-end')]
        ops, open_op = [], None
        for x in inside:
            if x['event'] == 'op-begin':
                if open_op:
                    invalid.append('operation %s not closed before %s' % (open_op['f'].get('op'), x['f'].get('op')))
                open_op = x
            else:
                if not open_op or open_op['f'].get('op') != x['f'].get('op') or open_op['f'].get('attempt') != x['f'].get('attempt'):
                    invalid.append('operation end without matching begin: %s' % x['f'])
                    continue
                ops.append({'op': x['f'].get('op'), 'rc': x['f'].get('rc'), 'result': x['f'].get('result'), 'begin': open_op['t'], 'end': x['t']})
                open_op = None
        if open_op:
            invalid.append('operation %s without end marker' % open_op['f'].get('op'))
        for o in ops:
            notes.append('op %s..%s %s rc=%s result=%s (%d..%d)' % (a, b, o['op'], o['rc'], o['result'], o['begin'], o['end']))
        if kind == 'install':
            for apk in ('install-app', 'install-test'):
                mine = [o for o in ops if o['op'] == apk]
                if not mine or mine[-1]['rc'] != '0' or mine[-1]['result'] != 'Success':
                    invalid.append('%s between %s and %s not proven successful: %s' % (apk, a, b, mine[-1:] or 'missing'))
            if [o['op'] for o in ops if o['op'] not in ('install-app', 'install-test')]:
                invalid.append('unexpected operation between %s and %s' % (a, b))
        else:
            if [o['op'] for o in ops] != ['uninstall-test', 'uninstall-app'] or any(o['rc'] != '0' or o['result'] != 'Success' for o in ops):
                invalid.append('uninstall not proven: expected uninstall-test then uninstall-app, each rc=0 result=Success, got %s'
                               % [(o['op'], o['rc'], o['result']) for o in ops])
    if variant == 'uninstall':
        ab = [x for x in steps if x['event'] == 'absent'][0]['f']
        if (ab.get('pmApp'), ab.get('pmTest'), ab.get('listed')) != ('none', 'none', 'none'):
            invalid.append('absence of both packages after the uninstall not proven: %s' % ab)
    uid = {x['event']: x['f'].get('uid') for x in steps if x['event'].endswith('-end') and x['f'].get('uid')}
    return t, uid


def owner_events(logcat):
    """-> [(t_ms, pfad)] aller owner_package_changed-Zeilen des MediaProvider."""
    out = []
    for raw in logcat.splitlines():
        m = B.LINE.match(raw)
        if m and m.group(5).strip() == 'MediaProvider':
            o = OWNER_LINE.search(m.group(6))
            if o:
                out.append((int(round(float(m.group(1)) * 1000)), o.group(1).strip()))
    return out


def crash_evidence(logcat, since):
    """Absturz-, ANR-, OOM- oder Prozesstod-Belege der App, des MediaProvider oder von system_server ab 'since'."""
    out, fatal = [], False
    for raw in logcat.splitlines():
        m = B.LINE.match(raw)
        if not m or int(round(float(m.group(1)) * 1000)) < since:
            continue
        tag, msg = m.group(5).strip(), m.group(6)
        if 'OutOfMemoryError' in msg or 'FATAL EXCEPTION IN SYSTEM PROCESS' in msg:
            out.append(raw.strip()[:140])
        elif tag == 'AndroidRuntime' and msg.startswith('FATAL EXCEPTION'):
            fatal = True
        elif tag == 'AndroidRuntime' and fatal and msg.startswith('Process: '):
            fatal = False
            if RELEVANT_PROC.search(msg):
                out.append(raw.strip()[:140])
        elif tag in ('am_crash', 'am_anr', 'am_proc_died', 'am_kill') and RELEVANT_PROC.search(msg):
            out.append(raw.strip()[:140])
    return out


# ------------------------------------------------------------------------------------------------ Struktur
def structure(ph, instr, steps, uid, variant, invalid):
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
        if ns != list(range(1, len(ns) + 1)) or len(ns) != int(p['end'].get('snapshots', -1)) or p['end'].get('final') != str(len(ns)):
            invalid.append('phase %s snapshots not contiguous, count differs from END or final snapshot not announced' % name)
        if not p['snaps'] or not p['snaps'][-1]['final'] or not p['dsnaps'] or not p['dsnaps'][-1]['final']:
            invalid.append('phase %s has no final snapshot with disk snapshot' % name)
        if name in SEQ0:
            seqs = [int(d['seq']) for d in p['dl']]
            if seqs != list(range(SEQ0[name], SEQ0[name] + len(seqs))) or len(seqs) != int(p['end'].get('downloads', -1)):
                invalid.append('phase %s downloads not contiguous or count differs from END' % name)
            for d in p['dl']:
                check_download(name, d, p, ph, invalid)
    if ph.get('pre') and len(ph['pre']['dl']) != 3:
        invalid.append('phase pre published %d files, expected 3' % len(ph['pre']['dl']))
    if invalid or steps is None:
        return
    step = 'uninstall-begin' if variant == 'uninstall' else 'update-begin'
    end2 = 'install2-end' if variant == 'uninstall' else 'update-end'
    t = lambda name, k: int(ph[name]['start' if k == 'start' else 'end'].get('t', -1))
    order = [('install1-end', steps['install1-end']), ('pre START', t('pre', 'start')), ('pre END', t('pre', 'end')), (step, steps[step]),
             (end2, steps[end2]), ('post START', t('post', 'start')), ('post END', t('post', 'end')), ('recheck START', t('recheck', 'start'))]
    if [v for _, v in order] != sorted(v for _, v in order):
        invalid.append('time line out of order: %s' % order)
    for name, mark in (('pre', 'install1-end'), ('post', end2)):
        if ph[name]['start'].get('appUid') != uid.get(mark):
            invalid.append('phase %s ran with appUid %s, package step %s reported uid %s' % (name, ph[name]['start'].get('appUid'), mark, uid.get(mark)))


def check_download(name, d, p, ph, invalid):
    """Zeitachse, URI, ID und Pfad eines Downloads müssen zueinander und zur Phase passen."""
    try:
        lo, done = int(d.get('insertStart', -1)), int(d.get('done', -1))
    except ValueError:
        invalid.append('phase %s seq %s: unreadable times' % (name, d.get('seq')))
        return
    if not (int(p['start'].get('t', -1)) <= lo <= done <= int(p['end'].get('t', -1))):
        invalid.append('phase %s seq %s: insert/done outside the phase' % (name, d.get('seq')))
    if d.get('state') == 'done':
        uri, sid = d.get('uri', '-'), d.get('id', '-')
        if not sid.isdigit() or not uri.endswith('/' + sid):
            invalid.append('phase %s seq %s: URI %s does not end with id %s' % (name, d.get('seq'), uri, sid))
        got = sorted(int(q['off']) for q in p['uriq'] if q['seq'] == d['seq'])
        if got != OFFSETS[name]:
            invalid.append('phase %s seq %s: URI checks at %s, expected %s' % (name, d['seq'], got, OFFSETS[name]))
        for q in p['uriq'] + ph.get('recheck', {}).get('uriq', []):
            if q['seq'] == d['seq'] and q['res'].startswith('found '):
                f = B.kv(q['res'][len('found '):])
                if f.get('id') != sid or f.get('data') != B.DIR + d['name']:
                    invalid.append('phase %s seq %s: URI query found id %s path %s, expected %s %s' % (name, d['seq'], f.get('id'), f.get('data'), sid, d['name']))
        if sum(1 for q in ph.get('recheck', {}).get('uriq', []) if q['seq'] == d['seq'] and q['off'] == '-1') != 1:
            invalid.append('recheck has no single URI query for %s seq %s' % (name, d['seq']))
    elif d.get('state') not in ('failed',):
        invalid.append('phase %s seq %s: unknown engine state %s' % (name, d.get('seq'), d.get('state')))


# ------------------------------------------------------------------------------------------------ Zeitachsen je Zeile
def observations(ph, hosts):
    """-> [(a, b, {id: (owner, pending, trashed, path)})] aus gültigen privilegierten Aufnahmen und Host-Abfragen."""
    obs = [(s['t'], s['t_end'], s['priv']) for name in PHASES for s in ph[name]['snaps'] if s['valid']]
    obs += hosts
    return sorted(obs, key=lambda o: (o[0], o[1]))


def row_state(rows, rid, path, pkg):
    hit = rows.get(rid) if rid and rid != '-' else None
    if hit is None:
        same = [v for v in rows.values() if v[3] == path or re.fullmatch(r'.*/\.(pending|trashed)-\d+-' + re.escape(path.rsplit('/', 1)[-1]), v[3])]
        hit = same[0] if len(same) == 1 else None
        if len(same) > 1:
            return 'ambiguous'
    if hit is None:
        return 'absent'
    return 'own' if hit[0] == pkg else ('NULL' if hit[0] == 'NULL' else 'other')


def change(row, obs, events, pkg):
    """Wechsel einer Zeile weg von 'own'. -> dict(changed, lo, hi, to, n_events, contradiction)."""
    seen = [(a, b, row_state(r, row['id'], row['path'], pkg)) for a, b, r in obs if b >= row['lo']]
    seen = [x for x in seen if x[2] != 'ambiguous']
    first_own = next((i for i, x in enumerate(seen) if x[2] == 'own'), None)
    out = {'changed': False, 'lo': None, 'hi': None, 'to': None, 'n_events': len(events), 'contradiction': None, 'unbracketed': False}
    if first_own is None:
        out['unbracketed'] = bool(events)   # Wechsel protokolliert, aber nie als eigen beobachtet: Zeitpunkt nicht eingrenzbar
        return out
    k = next((i for i in range(first_own, len(seen)) if seen[i][2] != 'own'), None)
    if k is None:
        if events:
            out['contradiction'] = 'owner change logged at %s but the row stays own until %d' % ([t for t, _ in events], seen[-1][0])
        return out
    last_own = max(a for a, b, s in seen[:k] if s == 'own')
    out.update(changed=True, lo=last_own, hi=seen[k][1], to=seen[k][2])
    if any(s == 'own' for a, b, s in seen[k + 1:]):
        out['contradiction'] = 'row observed own again after it changed'
    for t, _ in events:
        if any(s == 'own' and a > t for a, b, s in seen):
            out['contradiction'] = 'row observed own entirely after its logged owner change at %d' % t
        if t >= out['lo']:
            out['hi'] = min(out['hi'], t)
    return out


def inter(a, b):
    return a[0] <= b[1] and b[0] <= a[1]


# ------------------------------------------------------------------------------------------------ Bewertung
def variant_verdict(variant, run, logcat, hosts_text, instr, before, after, pkg='de.evp.helfer.channeltest'):
    invalid, notes = [], ['rules pkgab=%s snapshots=%s foreign=%s' % (RULES, B.SNAPSHOT_RULES, B.FOREIGN_RULES)]
    scans, ev, problems = B.parse(logcat, run)
    invalid += problems
    mk = markers(logcat, run, variant, invalid)
    steps, uid = package_steps(variant, mk, invalid, notes)
    ev = [(k, r) for k, r in ev if k != 'PKG']
    try:
        ph = B.phases(ev, run, invalid)
    except B.Invalid as e:
        return 'INVALID', [str(e)], [], notes
    if all(n in ph for n in PHASES):
        structure(ph, instr, steps, uid, variant, invalid)
    else:
        invalid.append('phases missing: %s' % [n for n in PHASES if n not in ph])
    for p in ph.values():
        if p.get('pkg') != pkg:
            invalid.append('phase %s ran in package %s' % (p['phase'], p.get('pkg')))
    token = 'BootScanProbe-%s-' % run
    known = {B.DIR + d['name']: (name, d) for name in ('pre', 'post') for d in (ph.get(name) or {}).get('dl', [])}
    evs = owner_events(logcat)
    for t, path in evs:
        if token in path and path not in known:
            invalid.append('owner_package_changed for an unknown probe file %s at %d' % (path, t))
    other = [p for t, p in evs if 'BootScanProbe-' not in p]
    if other:
        notes.append('owner_package_changed for %d non-probe paths (not evaluated)' % len(other))
    removed, modified, added = B.foreign_diff(before, after)
    if added:
        notes.append('FOREIGN_ADDED (not data loss): %s' % added[:6])
    if invalid:
        loss = B.proven_loss(ph, ('pre', 'post'), before, after)
        if loss:
            return 'DATA_LOSS', loss + ['other evidence incomplete or contradictory: %s' % invalid[:3]], [], notes
        return 'INVALID', invalid, [], notes

    hosts = host_samples(hosts_text, notes)
    obs = observations(ph, hosts)
    ext = [s for s in scans if s['volume'] == 'external_primary' and s['reason'] == 'REASON_MOUNTED']
    step_a, step_b = (steps['uninstall-begin'], steps['uninstall-end']) if variant == 'uninstall' else (steps['update-begin'], steps['update-end'])
    end2 = steps['install2-end' if variant == 'uninstall' else 'update-end']
    notes.append('time line: %s' % ' '.join('%s=%d' % (e, steps[e]) for e in STEPS[variant]))
    notes.append('mount scans external_primary %s' % [(s['start'], s['end'], s['tid'] if 'tid' in s else '-') for s in ext])
    notes.append('app uid pre=%s post=%s (a uid change alone proves nothing)' % (ph['pre']['start'].get('appUid'), ph['post']['start'].get('appUid')))
    final = ph['recheck']['snaps'][-1]
    rows, data_loss = [], []
    for name in ('pre', 'post'):
        gen = 'install1' if name == 'pre' else ('install2' if variant == 'uninstall' else 'updated')
        for d in ph[name]['dl']:
            d['phase'] = name
            cls, why = B.classify(d, ph, pkg)
            path = B.DIR + d['name']
            row = {'seq': d['seq'], 'gen': gen, 'cls': cls, 'why': why, 'id': d.get('id', '-'), 'path': path, 'd': d,
                   'lo': int(d['insertStart']), 'done': int(d['done']), 'risk': '-'}
            row['events'] = [(t, p) for t, p in evs if p == path]
            row['ch'] = change(row, obs, row['events'], pkg)
            if cls == 'DATA_LOSS':
                data_loss.append('seq %s: %s' % (d['seq'], why))
            rows.append(row)
    if removed or modified:
        data_loss.append('existing files outside the probe removed %s or modified %s' % (removed[:5], modified[:5]))
    if data_loss:
        return 'DATA_LOSS', data_loss, rows, notes
    crash = crash_evidence(logcat, steps['install1-begin'])
    pre_rows = [r for r in rows if r['gen'] == 'install1']
    new_rows = [r for r in rows if r['gen'] != 'install1']
    # erste Installation: alle drei veröffentlicht und unmittelbar sichtbar
    pre_bad = [r['seq'] for r in pre_rows if r['d'].get('state') != 'done'
               or [B.visible(q['res'], r['id'], pkg) for q in ph['pre']['uriq'] if q['seq'] == r['seq']] != [True, True]]
    over = [s for s in ext if inter((s['start'], s['end']), (step_a, step_b))]
    contradictions = ['seq %s: %s' % (r['seq'], r['ch']['contradiction']) for r in rows if r['ch']['contradiction']]
    reasons = []
    if crash:
        reasons.append('crash/ANR/OOM evidence of the app, MediaProvider or system_server: %s' % crash[:2])
    if pre_bad:
        reasons.append('first installation not valid: downloads %s not published and visible' % pre_bad)
    if not over:
        reasons.append('no external mount scan overlaps the package step %d..%d (scans %s)' % (step_a, step_b, [(s['start'], s['end']) for s in ext]))
    elif len(over) > 1:
        reasons.append('more than one mount scan overlaps the package step')
    if contradictions:
        reasons.append('logcat and provider observations contradict: %s' % contradictions[:3])
    scan = over[0] if len(over) == 1 else None

    if variant == 'control':
        if any(r['events'] for r in rows):
            return 'ANOMALY', ['owner_package_changed for probe rows without an uninstall: %s' % [(r['seq'], r['events']) for r in rows if r['events']][:3]], rows, notes
        bad = [r for r in rows if r['cls'] not in ('OK', 'UNKNOWN')]
        if bad:
            return 'ANOMALY', ['%s seq %s: %s (%s)' % (r['gen'], r['seq'], r['cls'], r['why']) for r in bad][:10], rows, notes
        if any(r['cls'] == 'UNKNOWN' for r in rows):
            reasons.append('UNKNOWN classifications: %s' % [r['seq'] for r in rows if r['cls'] == 'UNKNOWN'])
        if reasons:
            return 'INCONCLUSIVE', reasons, rows, notes
        before_end = [r for r in new_rows if r['lo'] < scan['end']]
        for r in new_rows:
            r['risk'] = 'before-scan-end' if r['lo'] < scan['end'] else 'after-scan-end'
        if not before_end:
            return 'INCONCLUSIVE', ['no updated download was created before the end of the mount scan %d' % scan['end']], rows, notes
        if final['t'] < scan['end'] + OBSERVE_AFTER_MS:
            return 'INCONCLUSIVE', ['observation after the scan end too short'], rows, notes
        unseen = [r['seq'] for r in before_end if not any(a >= scan['end'] and row_state(o, r['id'], r['path'], pkg) == 'own' for a, b, o in obs)]
        if unseen:
            return 'INCONCLUSIVE', ['rows %s not observed as own after the scan end' % unseen], rows, notes
        return 'CLEAN', ['all %d downloads OK, no owner change of probe rows, %d updated rows created before the scan end (%d) and seen own after it'
                         % (len(rows), len(before_end), scan['end'])], rows, notes

    # ---- Variante uninstall
    orphan, O = [], None
    for r in pre_rows:
        ch = r['ch']
        if r['cls'] == 'OWNER_CHANGED' and ch['changed'] and ch['to'] == 'NULL' and not ch['contradiction']:
            fin = final['priv'].get(r['id'])
            if fin and fin[0] == 'NULL' and fin[1] == '0' and fin[2] == '0' and fin[3] == r['path']:
                r['cls'], r['why'] = 'ORPHANED_EXPECTED', 'normal uninstall behaviour: owner NULL between %d and %d; %s' % (ch['lo'], ch['hi'], r['why'])
                orphan.append(r)
        elif r['cls'] == 'OK':
            r['cls'], r['why'] = 'NOT_ORPHANED', 'still owned by the package name after the uninstall: ' + r['why']
    if len(orphan) != len(pre_rows):
        reasons.append('orphaning of the first installation not observed for all rows (%d of %d)' % (len(orphan), len(pre_rows)))
    else:
        O = (max(r['ch']['lo'] for r in orphan), min(r['ch']['hi'] for r in orphan))
        notes.append('orphaning of install1 rows between %d and %d (observation interval, not an exact time)' % O)
        if O[0] > O[1]:
            reasons.append('install1 rows orphaned at different times')
            O = None
    if O and not reasons:
        if O[1] <= end2:
            reasons.append('orphaning not delayed: completed by %d, reinstall ended %d (normal uninstall behaviour)' % (O[1], end2))
        elif O[0] < end2:
            reasons.append('orphaning time ambiguous: between %d and %d, reinstall ended %d' % (O[0], O[1], end2))
        elif O[1] < scan['end']:
            reasons.append('orphaning completed (%d) before the mount scan ended (%d): delay not related to the scan' % (O[1], scan['end']))
        elif final['t'] < O[1] + OBSERVE_AFTER_MS:
            reasons.append('observation after the orphaning too short (final snapshot %d)' % final['t'])
    if reasons:
        return 'INCONCLUSIVE', reasons, rows, notes
    for r in new_rows:
        r['risk'] = 'at_risk' if r['lo'] < O[0] else ('ambiguous' if r['lo'] <= O[1] else 'late')
    at_risk = [r for r in new_rows if r['risk'] == 'at_risk']
    if not at_risk:
        return 'INCONCLUSIVE', ['delayed orphaning between %d and %d, but no download of the new installation was created before it' % O], rows, notes
    matched, unaffected, uncertain, unmatched = [], [], [], []
    for r in at_risk:
        ch, d = r['ch'], r['d']
        seen_after = any(a >= O[1] and row_state(o, r['id'], r['path'], pkg) == 'own' for a, b, o in obs)
        if r['cls'] == 'OK':
            (unaffected if seen_after else uncertain).append(r)
        elif r['cls'] == 'OWNER_CHANGED' and ch['changed'] and ch['to'] == 'NULL' and inter((ch['lo'], ch['hi']), O) \
                and (final['priv'].get(r['id']) or ('',))[0] == 'NULL':
            matched.append(r)
        elif r['cls'] == 'LOST' and ch['changed'] and ch['to'] == 'absent' and inter((ch['lo'], ch['hi']), O):
            matched.append(r)
        elif r['cls'] == 'PUBLISH_FAILED' and d.get('state') == 'failed' and d.get('commit', '').startswith('ex:') \
                and int(d.get('commitEnd', -1)) >= O[0] and 'network' not in d.get('reason', '') \
                and not any(v[3] == r['path'] for v in final['priv'].values()) and 'file on disk: True' in r['why']:
            matched.append(r)
        elif r['cls'] in ('UNKNOWN', 'TRANSIENT', 'UNEXPLAINED') or ch['unbracketed'] or (r['cls'] in ('OWNER_CHANGED', 'LOST') and not ch['changed']):
            uncertain.append(r)
        else:
            unmatched.append(r)
    for r in matched:
        r['why'] = 'MATCHES delayed orphaning: ' + r['why']
    amb_bad = [r for r in new_rows if r['risk'] == 'ambiguous' and r['cls'] != 'OK']
    amb_unseen = [r for r in new_rows if r['risk'] == 'ambiguous' and r['cls'] == 'OK'
                  and not any(a >= O[1] and row_state(o, r['id'], r['path'], pkg) == 'own' for a, b, o in obs)]
    late_bad = [r for r in new_rows if r['risk'] == 'late' and r['cls'] != 'OK']
    out = []
    if uncertain or amb_bad or amb_unseen:
        out.append('uncertain evidence in the causal window: %s' % [(r['seq'], r['cls'], r['risk']) for r in uncertain + amb_bad + amb_unseen][:6])
    if unmatched:
        out.append('damage that does not match the hypothesis (class, timing or failure kind): %s' % [(r['seq'], r['cls'], r['d'].get('commit'), r['d'].get('reason')) for r in unmatched][:6])
    if late_bad:
        out.append('%d downloads created after the orphaning failed too: %s' % (len(late_bad), [(r['seq'], r['cls']) for r in late_bad][:6]))
    if matched and unaffected:
        out.append('partial effect: %d rows hit, %d rows created before the orphaning unaffected' % (len(matched), len(unaffected)))
    if out:
        return 'INCONCLUSIVE', out, rows, notes
    if matched:
        return 'SUPPORTED', ['delayed orphaning (%d..%d, reinstall ended %d, scan ended %d) hit all %d downloads of the new installation created before it: %s'
                             % (O[0], O[1], end2, scan['end'], len(matched), sorted({r['cls'] for r in matched})), 'CLAIM ' + CLAIM], rows, notes
    return 'NOT_SUPPORTED', ['delayed orphaning (%d..%d), all %d downloads of the new installation created before it stayed OK and were seen own after it'
                             % (O[0], O[1], len(unaffected))], rows, notes


def ab(control, experiment):
    """-> (Ergebnis, Gründe, Arme)."""
    arms, reasons = {}, []
    for variant, text in (('control', control), ('uninstall', experiment)):
        res = re.findall(r'(?m)^PKGAB_VARIANT variant=(\w+) run=(\w+) result=(\w+)$', text)
        if len(res) != 1 or res[0][0] != variant:
            reasons.append('%s: exactly one PKGAB_VARIANT line of this variant required, got %s' % (variant, res))
            arms[variant] = ('MISSING', '-')
            continue
        if res[0][2] not in RESULTS[variant]:
            reasons.append('%s: undefined result %s' % (variant, res[0][2]))
        arms[variant] = (res[0][2], res[0][1])
    c, e = arms['control'][0], arms['uninstall'][0]
    if not reasons and arms['control'][1] == arms['uninstall'][1]:
        reasons.append('both arms report the same run %s (no independent emulator states)' % arms['control'][1])
    if 'DATA_LOSS' in (c, e):
        return 'DATA_LOSS', ['control %s, uninstall %s' % (c, e)] + reasons, arms
    if reasons or 'INVALID' in (c, e):
        return 'INVALID', ['control %s, uninstall %s' % (c, e)] + reasons, arms
    if e == 'SUPPORTED' and c == 'CLEAN':
        return 'HYPOTHESIS_SUPPORTED', ['uninstall SUPPORTED, control CLEAN', 'CLAIM ' + CLAIM], arms
    if e == 'NOT_SUPPORTED' and c == 'CLEAN':
        return 'HYPOTHESIS_NOT_SUPPORTED', ['uninstall NOT_SUPPORTED, control CLEAN'], arms
    return 'INCONCLUSIVE', ['control %s, uninstall %s' % (c, e)], arms


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
            result, reasons, arms = ab(read(o.control), read(o.experiment))
        except Exception as e:  # noqa: BLE001
            result, reasons, arms = 'INVALID', ['evaluation error: %s: %s' % (type(e).__name__, e)], {}
        for k, (res, run) in sorted(arms.items()):
            print('PKGAB_ARM variant=%s run=%s result=%s' % (k, run, res))
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
        ch = r.get('ch') or {}
        print('PKGAB_DOWNLOAD variant=%s seq=%s gen=%s class=%s risk=%s id=%s insert=%d done=%d change=%s..%s->%s events=%s %s'
              % (o.variant, r['seq'], r['gen'], r['cls'], r['risk'], r['id'], r['lo'], r['done'], ch.get('lo'), ch.get('hi'), ch.get('to'),
                 [t for t, _ in r.get('events', [])], r['why']))
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
    if not re.fullmatch(r'[0-9a-f]{12}', o.run):
        result = 'INVALID'
    print('PKGAB_VARIANT variant=%s run=%s result=%s' % (o.variant, o.run if re.fullmatch(r'[0-9a-f]{12}', o.run) else 'invalid', result))
    sys.exit({'SUPPORTED': 0, 'NOT_SUPPORTED': 0, 'CLEAN': 0, 'ANOMALY': 5, 'INVALID': 2, 'DATA_LOSS': 3, 'INCONCLUSIVE': 4}[result])


if __name__ == '__main__':
    main()
