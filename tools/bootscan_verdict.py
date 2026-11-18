#!/usr/bin/env python3
"""Bewertung der Diagnose "MediaStore-Zeile während des ersten Volume-Scans" (tools/run-bootscan-probe.sh,
tests/bootscan/MediaStoreBootScanProbeTest.java). Nur Diagnose; ordnet Rohbeobachtungen ein, bewertet kein Produkt als
"bestanden". Es gibt kein PASS.

Aufruf:
  bootscan_verdict.py --logcat L --run RUN --instr-during I1 --instr-after I2 --instr-recheck I3 \
                      --foreign-before B --foreign-after A [--package de.evp.helfer.channeltest]
    L: vollständige Logcat-Aufnahme mit "-v epoch" (enthält die MediaProvider-Scanzeilen und alle BOOTSCAN-Zeilen)
    I*: am-instrument-Ausgaben der drei Phasen
    B/A: Dateiliste "sha256 größe pfad" aller Nicht-Sondendateien unter /storage/emulated/0 (ohne Android/), letzte Zeile
         LIST-END – vor und nach dem Lauf

Klassen je Download (genau eine):
  OK             zu jedem Zeitpunkt über die erhaltene URI sichtbar (gleiche ID, eigener Besitzer, nicht ausstehend)
  TRANSIENT      zeitweise nicht sichtbar, in der Nachprüfung wieder sichtbar mit gleicher ID und gleichem Besitzer
  OWNER_CHANGED  Zeile mit gleicher ID existiert (privilegierte Sicht), Besitzer ist nicht mehr die App
  STATE_CHANGED  Zeile mit gleicher ID und eigenem Besitzer, aber wieder ausstehend oder im Papierkorb
  REPLACED       keine Zeile mit der ursprünglichen ID, aber eine andere Zeile für denselben Pfad
  LOST           weder die ursprüngliche ID noch irgendeine Zeile für den Pfad; Datei vorhanden
  UNEXPLAINED    App sieht die eigene, privilegiert vorhandene, veröffentlichte Zeile nicht (Widerspruch, gemeldet)
  PUBLISH_FAILED die Engine meldete nicht "done" (z. B. publish rows=0) – sichtbarer Fehler, nie OK
  DATA_LOSS      Datei fehlt oder Inhalt (SHA-256) weicht ab
  UNKNOWN        nötige Beobachtung fehlt oder war nicht verlässlich (Abfragefehler, unvollständige privilegierte
                 Abfrage, fehlende Nachprüfung) – fehlende Berechtigung beweist nie eine fehlende Zeile

Gruppen: OVERLAP (Lebensdauer des Eintrags vom Anlegen bis "done" schneidet einen "Scanned external_primary due to
REASON_MOUNTED"-Scan), DURING_OTHER (Phase during ohne Überschneidung), CONTROL (Phase after, ≥ 10 s nach dem Ende des
letzten Mount-Scans, ohne Überschneidung), AFTER_UNCONTROLLED (Phase after, Bedingung verletzt).

Aufnahmen (Regel snapshots=v2, nach Codex-NO-GO 1): jede Aufnahme wird aus ihren Änderungszeilen rekonstruiert und
streng geprüft – Änderungszeilen nur für den eigenen, abgeschlossenen Rahmen (gleiche Nummer, gültiger Status), Anzahl
gleich appN/privN/diskN, keine offenen Änderungen am Phasenende, DSNAP genau zur unmittelbar vorangehenden SNAP, App-Sicht
widerspruchsfrei zur privilegierten Sicht (gleiche ID, Besitzer, ausstehend, Name). Die Einordnung verwendet NUR die
tatsächlich letzte Aufnahme der Nachprüfung; ist sie ungültig, wird sie nie durch eine ältere ersetzt (UNKNOWN).

Ergebnis (eine Zeile BOOTSCAN_RESULT, Exit-Code):
  INVALID (2)       unvollständige oder widersprüchliche Belege (Phasen, Zähler, Logzeilen, Instrumentierung, Aufnahmen)
  DATA_LOSS (3)     irgendein Download DATA_LOSS oder eine schon vorher vorhandene fremde Datei gelöscht oder inhaltlich
                    verändert – hat Vorrang vor allem, auch vor INVALID, sofern der Verlust selbst aus vollständigen
                    Belegen folgt (vollständige Dateilisten bzw. eine in sich gültige letzte Dateiaufnahme). Seit Regel foreign=v2 (nach Lauf 44281951152, dessen
                    Ergebnis unverändert archiviert ist: evidence/bootscan-diagnosis/run44281951152/) sind NEU hinzugekommene
                    fremde Dateien (z. B. vom System angelegte .thumbnails/.nomedia) kein Datenverlust, sondern eine Zeile
                    BOOTSCAN_NOTE FOREIGN_ADDED; Löschen oder Verändern bleibt DATA_LOSS.
  INCONCLUSIVE (4)  kein Mount-Scan beobachtet, keine Veröffentlichung überschneidet ihn, keine Kontrolle danach, oder
                    alle überschneidenden Downloads UNKNOWN
  DIAGNOSED (0)     Vergleich möglich; die Klassen je Gruppe stehen in BOOTSCAN_GROUP, "reproduced" sagt, ob in OVERLAP
                    eine Abweichung (nicht OK) auftrat, "control_anomaly", ob auch ohne Scan
"""
import argparse
import hashlib
import importlib.util
import os
import re
import sys

LINE = re.compile(r'^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.+?)\s*: (.*)$')
SCAN = re.compile(r'^Scanned (\S+) due to (REASON_\w+), found (\d+) items in (\d+)ms, (\d+) inserts (\d+) updates (\d+) deletes')
PROBE_CLASS = 'de.evp.helfer.MediaStoreBootScanProbeTest'
OFFSETS = (0, 250, 2000, 10000)
CONTROL_GAP_MS = 10_000
DIR = '/storage/emulated/0/Download/Video-Assistent/'


class Invalid(Exception):
    pass


def kv(text):
    """'a=1 b=2 res=found id=3 ...' -> dict (res behält den Rest der Zeile)."""
    out = {}
    if ' res=' in ' ' + text:
        head, res = (' ' + text).split(' res=', 1)
        out['res'] = res.strip()
        text = head
    for tok in text.split():
        if '=' in tok:
            k, v = tok.split('=', 1)
            out[k] = v
    return out


def report_class():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'device_gate.py')
    spec = importlib.util.spec_from_file_location('device_gate_for_bootscan', path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.Report


def listing(path):
    lines = [l for l in open(path, encoding='utf-8', errors='replace').read().splitlines() if l.strip()]
    if not lines or lines[-1] != 'LIST-END' or lines.count('LIST-END') != 1:
        raise Invalid('file listing %s incomplete (no final LIST-END)' % os.path.basename(path))
    out = {}
    for l in lines[:-1]:
        m = re.match(r'^([0-9a-f]{64}) (\d+) (/storage/\S.*)$', l)
        if not m:
            raise Invalid('file listing %s unreadable line %r' % (os.path.basename(path), l[:80]))
        out[m.group(3)] = (m.group(1), int(m.group(2)))
    return out


def parse(logcat, run):
    scans, ev, problems = [], [], []
    for raw in logcat.splitlines():
        m = LINE.match(raw)
        if not m:
            continue
        t, tag, msg = float(m.group(1)), m.group(5).strip(), m.group(6)
        if tag == 'MediaProvider':
            s = SCAN.match(msg)
            if s:
                end = int(round(t * 1000))
                scans.append({'volume': s.group(1), 'reason': s.group(2), 'items': int(s.group(3)), 'start': end - int(s.group(4)), 'end': end,
                              'inserts': int(s.group(5)), 'updates': int(s.group(6)), 'deletes': int(s.group(7))})
        elif tag == 'BOOTSCAN' and msg.startswith('BOOTSCAN '):
            body = msg[len('BOOTSCAN '):]
            kind = body.split(' ', 1)[0]
            ev.append((kind, body[len(kind):].strip()))
        if tag == 'chatty' and 'de.evp.helfer' in msg:
            problems.append('logcat suppressed lines of the probe process (chatty): %s' % raw.strip()[:100])
    for kind, rest in ev:
        r = re.search(r'\brun=(\S+)', rest)
        if r and r.group(1) != run:
            problems.append('BOOTSCAN line of another run %s' % r.group(1))
    return scans, ev, problems


HEX16 = re.compile(r'^[0-9a-f]{16}$')


def phases(ev, run, invalid, require_digest=False):
    """Zerlegt die Ereignisse in Phasen und rekonstruiert die Aufnahmen aus den Änderungszeilen.
    require_digest=True (aktuelles A/B-Format): jede gültige Sicht braucht eine 16-stellige Prüfsumme und tEnd; Platzhalter
    wie "-" oder fehlende Felder machen den Rahmen ungültig. Historische Logs (ohne Prüfsummen) werten ohne diese Pflicht."""
    out, cur = {}, None
    for kind, rest in ev:
        f = kv(rest)
        if kind == 'START':
            if f.get('phase') in out:
                invalid.append('phase %s started twice' % f.get('phase'))
            cur = {'phase': f.get('phase'), 'dl': [], 'uriq': [], 'snaps': [], 'dsnaps': [], 'scanseen': [], 'end': None, 'start': f,
                   'app': {}, 'priv': {}, 'disk': {}, 'pend_app': [], 'pend_priv': [], 'pend_disk': [], 'pkg': f.get('pkg'), 'frame_problems': []}
            out[cur['phase']] = cur
            continue
        if cur is None:
            invalid.append('BOOTSCAN %s before START' % kind)
            continue
        if kind == 'DL':
            cur['dl'].append(f)
        elif kind == 'URIQ':
            cur['uriq'].append(f)
        elif kind == 'SCANSEEN':
            cur['scanseen'].append(f)
        elif kind in ('APP', 'PRIV', 'DISK'):
            item = rest.split()[-1]
            cur['pend_' + kind.lower()].append((int(f.get('n', -1)), item))
        elif kind == 'SNAP':
            n = int(f['n'])
            valid = f.get('app') == 'ok' and f.get('priv') == 'ok' and f.get('cross') == 'ok'
            allowed = {'app': f.get('app') == 'ok', 'priv': valid}
            if require_digest:
                for name in ('app', 'priv'):
                    if allowed[name] and not HEX16.match(f.get(name + 'H', '')):
                        frame(cur, invalid, 'phase %s snapshot %d: %s view valid but digest %r missing or not a digest' % (cur['phase'], n, name, f.get(name + 'H')))
                if not f.get('tEnd', '').isdigit():
                    frame(cur, invalid, 'phase %s snapshot %d: no end time' % (cur['phase'], n))
            t_snap, t_end = int(f['t']), int(f.get('tEnd', f['t']))
            if t_end < t_snap or (cur['snaps'] and t_snap < cur['snaps'][-1]['t']):
                frame(cur, invalid, 'phase %s snapshot %d: impossible time (t=%d tEnd=%d, previous t=%s)'
                      % (cur['phase'], n, t_snap, t_end, cur['snaps'][-1]['t'] if cur['snaps'] else '-'))
            for name in ('app', 'priv'):
                dup_keys(cur, invalid, name, n)
                for pn, item in cur['pend_' + name]:
                    if pn != n:
                        frame(cur, invalid, '%s change of snapshot %d logged at snapshot %d' % (name.upper(), pn, n))
                    if not allowed[name]:
                        frame(cur, invalid, '%s change logged for snapshot %d whose %s view is not valid' % (name.upper(), n, name))
                        continue
                    apply(cur[name], item, 4 if name == 'priv' else 3)
                cur['pend_' + name] = []
            for name in ('app', 'priv'):
                h = f.get(name + 'H')
                if allowed[name] and h not in (None, '-') and digest(cur[name]) != h:
                    frame(cur, invalid, 'phase %s snapshot %d: reconstructed %s view does not match its digest (change lines lost or altered)'
                          % (cur['phase'], n, name))
            if allowed['app'] and str(len(cur['app'])) != f.get('appN'):
                frame(cur, invalid, 'phase %s snapshot %d: reconstructed app view has %d rows, appN=%s' % (cur['phase'], n, len(cur['app']), f.get('appN')))
            if valid and str(len(cur['priv'])) != f.get('privN'):
                frame(cur, invalid, 'phase %s snapshot %d: reconstructed privileged view has %d rows, privN=%s' % (cur['phase'], n, len(cur['priv']), f.get('privN')))
            if valid:
                bad = cross_check(cur['app'], cur['priv'])
                if bad:
                    frame(cur, invalid, 'phase %s snapshot %d: app view contradicts privileged view %s' % (cur['phase'], n, bad[:3]))
            cur['snaps'].append({'n': n, 't': int(f['t']), 't_end': int(f.get('tEnd', f['t'])), 'valid': valid, 'app_ok': f.get('app') == 'ok',
                                 'app': dict(cur['app']), 'priv': dict(cur['priv']), 'raw': f, 'final': f.get('final') == '1'})
        elif kind == 'DSNAP':
            n = int(f['n'])
            ok = f.get('disk') == 'ok'
            if not cur['snaps'] or cur['snaps'][-1]['n'] != n or any(d['n'] == n for d in cur['dsnaps']):
                frame(cur, invalid, 'phase %s disk snapshot %d does not belong to the preceding snapshot or is repeated' % (cur['phase'], n))
            if require_digest and ok and not HEX16.match(f.get('diskH', '')):
                frame(cur, invalid, 'phase %s disk snapshot %d valid but digest %r missing or not a digest' % (cur['phase'], n, f.get('diskH')))
            if cur['snaps'] and int(f['t']) < cur['snaps'][-1]['t']:
                frame(cur, invalid, 'phase %s disk snapshot %d taken before its snapshot' % (cur['phase'], n))
            dup_keys(cur, invalid, 'disk', n)
            for pn, item in cur['pend_disk']:
                if pn != n:
                    frame(cur, invalid, 'DISK change of snapshot %d logged at disk snapshot %d' % (pn, n))
                if not ok:
                    frame(cur, invalid, 'DISK change logged for disk snapshot %d that is not valid' % n)
                    continue
                apply(cur['disk'], item, 2)
            cur['pend_disk'] = []
            if ok and f.get('diskH') not in (None, '-') and digest(cur['disk']) != f.get('diskH'):
                frame(cur, invalid, 'phase %s disk snapshot %d: reconstructed files do not match the digest (change lines lost or altered)' % (cur['phase'], n))
            if ok and str(len(cur['disk'])) != f.get('diskN'):
                frame(cur, invalid, 'phase %s disk snapshot %d: reconstructed %d files, diskN=%s' % (cur['phase'], n, len(cur['disk']), f.get('diskN')))
            cur['dsnaps'].append({'n': n, 't': int(f['t']), 'ok': ok, 'disk': dict(cur['disk']), 'final': f.get('final') == '1'})
        elif kind == 'END':
            for name in ('app', 'priv', 'disk'):
                if cur['pend_' + name]:
                    frame(cur, invalid, 'phase %s ends with %d open %s changes (no closing snapshot)' % (cur['phase'], len(cur['pend_' + name]), name.upper()))
            cur['end'] = f
            cur = None
        elif kind == 'SNAPERR':
            invalid.append('sampler error in phase %s: %s' % (cur['phase'], f.get('error')))
        else:
            invalid.append('unknown BOOTSCAN line kind %s' % kind)
    return out


def frame(cur, invalid, msg):
    """Fehler eines Aufnahmerahmens: macht den Lauf INVALID und wird je Phase vermerkt (für belegten Datenverlust)."""
    invalid.append(msg)
    cur['frame_problems'].append(msg)


def digest(state):
    """Wie MediaStoreBootScanProbeTest.digest: SHA-256 über "schlüssel|wert", nach Schlüssel sortiert, erste 16 Hex."""
    text = '\n'.join('%s|%s' % (k, '|'.join(state[k])) for k in sorted(state))
    return hashlib.sha256(text.encode('utf-8')).hexdigest()[:16]


def dup_keys(cur, invalid, name, n):
    """Zwei Änderungsoperationen für denselben Schlüssel im selben Rahmen sind unzulässig (die Probe schreibt je Schlüssel
    höchstens eine)."""
    keys = [item[1:].split('|', 1)[0] for pn, item in cur['pend_' + name]]
    dups = sorted({k for k in keys if keys.count(k) > 1})
    if dups:
        frame(cur, invalid, 'phase %s frame %d: %s changes for the same key twice %s' % (cur['phase'], n, name.upper(), dups[:3]))


def cross_check(app, priv):
    """App-Sicht {id: (owner, pending, name)} gegen die unmittelbar danach abgefragte privilegierte Sicht
    {id: (owner, pending, trashed, path)}. Zulässig sind gleiche Werte oder Übergänge, die in der Zeit zwischen beiden
    Abfragen vorwärts geschehen sein können (ausstehend 1 -> 0 mit Umbenennung beim Veröffentlichen, Besitzer -> NULL);
    alles andere ist ein Widerspruch."""
    bad = []
    for i, (owner, pending, name) in app.items():
        p = priv.get(i)
        if p is None:
            bad.append('%s missing' % i)
            continue
        published_between = pending == '1' and p[1] == '0'
        owner_ok = p[0] == owner or p[0] == 'NULL'
        pending_ok = p[1] == pending or published_between
        base = p[3].rsplit('/', 1)[-1]
        # ausstehende/gelöschte Dateien liegen als ".pending-<Ablauf>-<Name>" bzw. ".trashed-<Ablauf>-<Name>" auf der Platte
        name_ok = base == name or published_between or re.fullmatch(r'\.(pending|trashed)-\d+-' + re.escape(name), base) is not None
        if not (owner_ok and pending_ok and name_ok):
            bad.append('%s app %s/%s/%s priv %s/%s/%s' % (i, owner, pending, name, p[0], p[1], p[3]))
    return bad


def _final_ok(rc, frames, what):
    end = rc.get('end') or {}
    if 'final' in end and (frames[-1]['n'] != int(end['final']) or not frames[-1]['final']):
        return 'the final %s announced by END (%s) is not the last one' % (what, end['final'])
    last_q = max([int(q['t']) for q in rc.get('uriq', []) if q.get('t', '').isdigit()] or [0])
    if frames[-1]['t'] < last_q:
        return 'last %s of the recheck was taken before the last URI query' % what
    return ''


def final_priv(rc):
    """-> (letzte privilegierte Aufnahme oder None, Grund). Nur die tatsächlich letzte zählt; ist sie ungültig, gibt es
    keinen Ersatz durch eine ältere gültige Aufnahme."""
    snaps = rc.get('snaps', [])
    if not snaps or not snaps[-1]['valid']:
        return None, 'last privileged snapshot of the recheck is missing or not valid (%s)' % (snaps[-1]['raw'] if snaps else 'none')
    why = _final_ok(rc, snaps, 'snapshot')
    return (None, why) if why else (snaps[-1], '')


def final_disk(rc):
    """-> (letzte Dateiaufnahme oder None, Grund) – unabhängig davon, ob die privilegierte MediaStore-Abfrage gültig war."""
    ds = rc.get('dsnaps', [])
    if not ds or not ds[-1]['ok']:
        return None, 'last disk snapshot of the recheck is missing or not valid'
    why = _final_ok(rc, ds, 'disk snapshot')
    return (None, why) if why else (ds[-1], '')


def final_frames(rc):
    """Rückwärtskompatibel: (letzte privilegierte Aufnahme, letzte Dateiaufnahme, Grund)."""
    snap, w1 = final_priv(rc)
    dsnap, w2 = final_disk(rc)
    return snap, dsnap, w1 or w2


def residual(d, disk):
    """Zustand der Datei eines Downloads in der letzten Dateiaufnahme: unknown | missing | intact | truncated | altered."""
    if disk is None:
        return 'unknown'
    got = disk.get(DIR + d['name'])
    if got is None:
        return 'missing'
    if got == (d.get('size'), d.get('sha')):
        return 'intact'
    try:
        return 'truncated' if int(got[0]) < int(d.get('size', -1)) else 'altered'
    except ValueError:
        return 'altered'


def apply(state, item, nfields):
    if item.startswith('-'):
        state.pop(item[1:], None)
        return
    if not item.startswith('+'):
        raise Invalid('unreadable change line %r' % item[:60])
    parts = item[1:].split('|')
    if len(parts) != nfields + 1:
        raise Invalid('change line with %d fields, expected %d: %r' % (len(parts), nfields + 1, item[:80]))
    state[parts[0]] = tuple(parts[1:])


def check_structure(ph, instr, Report, invalid):
    for name in ('during', 'after', 'recheck'):
        rep = Report(instr[name].splitlines())   # immer prüfen, auch wenn die Phase keine Belege lieferte
        if rep.tests() != [(PROBE_CLASS, 'probe')] or not rep.ok or rep.problems:
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
            invalid.append('phase %s snapshots not contiguous or count differs from END (%d lines, END %s)' % (name, len(ns), p['end'].get('snapshots')))
        if name != 'recheck':
            seqs = [int(d['seq']) for d in p['dl']]
            seq0 = 0 if name == 'during' else 10000
            if seqs != list(range(seq0, seq0 + len(seqs))) or len(seqs) != int(p['end'].get('downloads', -1)):
                invalid.append('phase %s downloads not contiguous or count differs from END' % name)
            for d in p['dl']:
                if d.get('uri', '-') != '-':
                    got = sorted(int(q['off']) for q in p['uriq'] if q['seq'] == d['seq'])
                    if got != list(OFFSETS):
                        invalid.append('phase %s seq %s: URI checks at %s, expected %s' % (name, d['seq'], got, list(OFFSETS)))
        if not p['dsnaps'] or not any(s['ok'] for s in p['dsnaps']):
            invalid.append('phase %s has no valid disk snapshot' % name)
    rc = ph.get('recheck')
    if rc is not None:
        # Nachprüfung: genau eine URI-Abfrage (off=-1) für jeden Download mit erhaltener URI
        for name in ('during', 'after'):
            for d in (ph.get(name) or {}).get('dl', []):
                if d.get('uri', '-') != '-' and sum(1 for q in rc['uriq'] if q['seq'] == d['seq'] and q['off'] == '-1') != 1:
                    invalid.append('recheck has no single URI query for %s seq %s' % (name, d['seq']))


def visible(res, sid, pkg):
    """URI-Abfrage -> True (eigene, veröffentlichte Zeile), False (keine Zeile), None (keine verlässliche Aussage)."""
    if res is None or res.startswith('error') or res == 'null-cursor':
        return None
    if res == 'none':
        return False
    f = kv(res.replace('found ', ''))
    return f.get('id') == sid and f.get('owner') == pkg and f.get('pending') == '0'


def classify(d, ph, pkg):
    """-> (Klasse, Begründung). Verbindlich ist der Zustand der tatsächlich letzten Aufnahmen der Nachprüfung: Dateien
    (Größe, SHA-256) und Providerzeile (ID, Pfad, Besitzer, veröffentlicht) werden getrennt bewertet; eine frühere
    erfolgreiche URI-Abfrage kann einen später beobachteten Schaden nie verdecken. OK nur, wenn die letzte Beobachtung ID,
    Pfad, Besitzer und veröffentlichten Status bestätigt und alle URI-Abfragen die Zeile sahen."""
    name, sid = d['name'], d.get('id', '-')
    path = DIR + name
    rc = ph['recheck']
    dsnap, dwhy = final_disk(rc)
    snap, pwhy = final_priv(rc)
    disk = dsnap['disk'] if dsnap else None
    if d.get('state') != 'done':
        rows = sorted(k for k, v in snap['priv'].items() if v[3] == path) if snap else None
        return 'PUBLISH_FAILED', 'state=%s reason=%s commit=%s; residual file %s; rows for the path: %s' % (
            d.get('state'), d.get('reason'), d.get('commit'), residual(d, disk), 'unknown' if rows is None else rows)
    # Dateien zuerst und unabhängig vom Providerzustand
    if disk is not None:
        if path not in disk:
            return 'DATA_LOSS', 'file %s missing at the end' % name
        if disk[path] != (d['size'], d['sha']):
            return 'DATA_LOSS', 'file %s changed: size/sha %s, expected %s/%s' % (name, disk[path], d['size'], d['sha'][:16])
    if snap is None:
        return 'UNKNOWN', pwhy + ('; ' + dwhy if dwhy else '')
    priv = snap['priv']
    row = priv.get(sid)
    phase = ph[d['phase']]
    early = [(int(q['off']), visible(q['res'], sid, pkg)) for q in phase['uriq'] if q['seq'] == d['seq']]
    hidden_at = [o for o, v in early if v is False]
    if row is None:
        same = sorted((k, v[0]) for k, v in priv.items() if v[3] == path)
        if same:
            return 'REPLACED', 'original id %s gone in the final snapshot; path now owned by rows %s' % (sid, same)
        return 'LOST', 'no row for id %s or path %s in the final snapshot; file %s' % (sid, name, 'complete on disk' if disk else 'state unknown')
    owner, pending, trashed, ppath = row
    if owner != pkg:
        return 'OWNER_CHANGED', 'row %s exists in the final snapshot, owner %s (expected %s); invisible since +%s ms' % (sid, owner, pkg, hidden_at)
    if pending != '0' or trashed != '0':
        return 'STATE_CHANGED', 'row %s in the final snapshot: own, pending=%s trashed=%s' % (sid, pending, trashed)
    if ppath != path:
        return 'STATE_CHANGED', 'row %s in the final snapshot points to %s, expected %s' % (sid, ppath, path)
    if disk is None:
        return 'UNKNOWN', dwhy
    rq = [q for q in rc['uriq'] if q['seq'] == d['seq'] and q['off'] == '-1']
    if len(rq) != 1:
        return 'UNKNOWN', 'recheck URI query missing'
    final_vis = visible(rq[0]['res'], sid, pkg)
    if final_vis is None:
        return 'UNKNOWN', 'recheck URI query not reliable: %s' % rq[0]['res']
    if not final_vis or snap['app'].get(sid) != (pkg, '0', name):
        return 'UNEXPLAINED', 'row %s exists, own, published, but the app cannot see it (URI %s, app view %s)' % (sid, rq[0]['res'][:40], snap['app'].get(sid))
    if hidden_at:
        return 'TRANSIENT', 'invisible at +%s ms after done, visible again in the recheck (same id %s)' % (hidden_at, sid)
    if any(v is None for _, v in early):
        return 'UNKNOWN', 'some URI checks after done were not reliable'
    return 'OK', 'final snapshot confirms id %s, path, owner, published; visible at every URI check' % sid


def group(d, scans):
    lo = int(d.get('insertStart', -1))
    hi = max(int(d.get('commitEnd', -1)), int(d.get('done', -1)))
    ext = [s for s in scans if s['volume'] == 'external_primary' and s['reason'] == 'REASON_MOUNTED']
    overlap = lo > 0 and hi > 0 and any(lo <= s['end'] and hi >= s['start'] for s in ext)
    if overlap:
        return 'OVERLAP'
    if d['phase'] == 'during':
        return 'DURING_OTHER'
    last = max(s['end'] for s in ext) if ext else None
    if last is not None and lo >= last + CONTROL_GAP_MS:
        return 'CONTROL'
    return 'AFTER_UNCONTROLLED'


FOREIGN_RULES = 'v2'   # v1 (c86d84b/8c4b2cb): jede Abweichung der Fremdliste = DATA_LOSS; v2: Hinzufügen nur Hinweis
SNAPSHOT_RULES = 'v3'  # v1 (bis 1578394): letzte GÜLTIGE Aufnahme; v2 (5627df2): Zähler/Prüfsummen, letzte Aufnahme;
#                       v3: Schlussaufnahme verbindlich vor URI-Abfrage, Dateien unabhängig vom Provider, Pflicht-Prüfsummen
#                       im A/B-Format, doppelte Änderungen, Zeitprüfung der Aufnahmen


def proven_loss(ph, dl_phases, before, after):
    """Datenverlust, der trotz sonst unvollständiger Belege feststeht: fremde Dateien aus vollständigen Listen gelöscht
    oder verändert; Sondendateien fehlen oder sind verändert laut einer in sich gültigen letzten Dateiaufnahme der
    Nachprüfung (Rahmen ohne Fehler)."""
    out = []
    removed, modified, _ = foreign_diff(before, after)
    if removed or modified:
        out.append('files outside the probe changed: removed %s, modified %s' % (removed[:5], modified[:5]))
    rc = ph.get('recheck') or {}
    ds = rc.get('dsnaps') or []
    if ds and ds[-1]['ok'] and not any('DISK' in m or 'disk' in m for m in rc.get('frame_problems', [])):
        disk = ds[-1]['disk']
        for name in dl_phases:
            for d in (ph.get(name) or {}).get('dl', []):
                path = DIR + d.get('name', '')
                if d.get('state') != 'done' or d.get('sha', '-') in ('-', '') or not d.get('name'):
                    continue   # nur fertig gemeldete Dateien; ein fehlgeschlagener Download darf aufgeräumt sein
                if path not in disk:
                    out.append('seq %s: file %s missing in the last valid disk snapshot' % (d.get('seq'), d['name']))
                elif disk[path] != (d.get('size'), d['sha']):
                    out.append('seq %s: file %s changed in the last valid disk snapshot' % (d.get('seq'), d['name']))
    return out


def foreign_diff(before, after):
    """-> (gelöscht, verändert, hinzugefügt) zwischen zwei Listen {pfad: (sha256, größe)}."""
    removed = sorted(set(before) - set(after))
    modified = sorted(k for k in set(before) & set(after) if before[k] != after[k])
    added = sorted(set(after) - set(before))
    return removed, modified, added


def evaluate(logcat, run, instr, before, after, pkg='de.evp.helfer.channeltest', notes=None):
    notes = [] if notes is None else notes
    invalid = []
    scans, ev, problems = parse(logcat, run)
    invalid += problems
    try:
        ph = phases(ev, run, invalid)
    except Invalid as e:
        return 'INVALID', [str(e)], [], {}
    check_structure(ph, instr, report_class(), invalid)
    for p in ph.values():
        if p.get('pkg') != pkg:
            invalid.append('phase %s ran in package %s, expected %s' % (p['phase'], p.get('pkg'), pkg))
    if invalid:
        loss = proven_loss(ph, ('during', 'after'), before, after)
        if loss:
            return 'DATA_LOSS', loss + ['other evidence incomplete or contradictory: %s' % invalid[:3]], [], {}
        return 'INVALID', invalid, [], {}
    rows, groups, data_loss = [], {}, []
    for name in ('during', 'after'):
        for d in ph[name]['dl']:
            d['phase'] = name
            cls, why = classify(d, ph, pkg)
            g = group(d, scans)
            rows.append((d['seq'], g, cls, why))
            groups.setdefault(g, {}).setdefault(cls, 0)
            groups[g][cls] += 1
            if cls == 'DATA_LOSS':
                data_loss.append('seq %s: %s' % (d['seq'], why))
    removed, modified, added = foreign_diff(before, after)
    if added:
        notes.append('FOREIGN_ADDED (not data loss, rule foreign=%s): %d new files outside the probe %s' % (FOREIGN_RULES, len(added), added[:6]))
    if removed or modified:
        data_loss.append('files outside the probe changed: removed %s, modified %s' % (removed[:5], modified[:5]))
    reasons = []
    ext = [s for s in scans if s['volume'] == 'external_primary' and s['reason'] == 'REASON_MOUNTED']
    if data_loss:
        return 'DATA_LOSS', data_loss, rows, groups
    if not ext:
        reasons.append('no "Scanned external_primary due to REASON_MOUNTED" line observed')
    ov = groups.get('OVERLAP', {})
    if not ov:
        reasons.append('no publication overlapped the mount scan')
    elif set(ov) == {'UNKNOWN'}:
        reasons.append('every overlapping publication is UNKNOWN')
    if not groups.get('CONTROL'):
        reasons.append('no controlled publication after the scan')
    if reasons:
        return 'INCONCLUSIVE', reasons, rows, groups
    reproduced = any(c != 'OK' for c in ov)
    control = any(c != 'OK' for c in groups['CONTROL'])
    return 'DIAGNOSED', ['reproduced=%s control_anomaly=%s' % ('yes' if reproduced else 'no', 'yes' if control else 'no')], rows, groups


def main():
    a = argparse.ArgumentParser()
    for k in ('logcat', 'run', 'instr-during', 'instr-after', 'instr-recheck', 'foreign-before', 'foreign-after'):
        a.add_argument('--' + k, required=True)
    a.add_argument('--package', default='de.evp.helfer.channeltest')
    o = a.parse_args()
    rows, groups, notes = [], {}, []
    print('BOOTSCAN_RULES foreign=%s snapshots=%s' % (FOREIGN_RULES, SNAPSHOT_RULES))
    try:
        read = lambda p: open(p, encoding='utf-8', errors='replace').read()
        result, reasons, rows, groups = evaluate(read(o.logcat), o.run, {'during': read(o.instr_during), 'after': read(o.instr_after),
                                                                         'recheck': read(o.instr_recheck)},
                                                 listing(o.foreign_before), listing(o.foreign_after), o.package, notes)
    except Exception as e:  # noqa: BLE001 - jede unlesbare Eingabe ist INVALID, nie ein Ergebnis
        result, reasons = 'INVALID', ['evaluation error: %s: %s' % (type(e).__name__, e)]
    for seq, g, cls, why in rows:
        print('BOOTSCAN_DOWNLOAD seq=%s group=%s class=%s %s' % (seq, g, cls, why))
    for g, counts in sorted(groups.items()):
        print('BOOTSCAN_GROUP %s %s' % (g, ' '.join('%s=%d' % kv_ for kv_ in sorted(counts.items()))))
    for n in notes:
        print('BOOTSCAN_NOTE ' + n)
    for r in reasons:
        print('BOOTSCAN_REASON ' + r)
    print('BOOTSCAN_RESULT ' + result)
    sys.exit({'DIAGNOSED': 0, 'INVALID': 2, 'DATA_LOSS': 3, 'INCONCLUSIVE': 4}[result])


if __name__ == '__main__':
    main()
