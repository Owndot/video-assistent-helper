#!/usr/bin/env python3
"""Bewertung der Sonde "verwaiste Datei / gleicher Name" (tools/run-orphan-collision-probe.sh). Nur Testwerkzeug.

Aufruf:
  orphan_verdict.py --before B --after A --instr I --probe P --crash C --title T --orphan-sha S --orphan-size N
    B/A: Ordner-Aufnahmen "sha256 größe name" (eine Datei je Zeile, auch versteckte Dateien und Unterordner), letzte
         Zeile genau "SNAPSHOT-END" (sonst ist die Aufnahme unvollständig); vor und nach dem Download
    I:   vollständige am-instrument-Ausgabe des Sondentests
    P:   Datei mit den ORPHAN_PROBE-Zeilen des Tests (Titel bereits durch <title> ersetzt)
    C:   Crash-Puffer und Absturz-/OOM-Zeilen des Systemprotokolls NACH dem Test (muss leer sein)

Ergebnis (eine Zeile ORPHAN_RESULT, Exit-Code):
  PASS       (0) NUR wenn alles gilt:
                 * Ausgangslage: genau die verwaiste Datei mit bekanntem SHA-256/Größe, sonst keine Sondendatei;
                 * MediaStore-Ausgangsabfrage nachweislich erfolgreich (queryBefore=ok, kein null-Cursor) und OHNE
                   passenden Eintrag (rowsBefore=[]);
                 * Abschlussabfrage erfolgreich (queryAfter=ok) mit GENAU EINEM Eintrag: Inhalt = neuer SHA-256, Datei
                   (_data) = die neue Datei auf der Platte;
                 * auf der Platte GENAU EINE neue Sondendatei (anderer Name als die verwaiste) mit genau dem neuen SHA-256;
                 * verwaiste Datei bytegleich unter ihrem Namen; alle Nicht-Sondendateien (auch versteckte) unverändert;
                 * der benannte Sondentest vollständig und ordnungsgemäß beendet (genau ein Test, begonnen und mit Code 0
                   beendet, "OK (1 test)", Runner-Ende genau einmal, nichts danach), state=done;
                 * kein Absturz-, Abbruch- oder OOM-Beleg (Instrumentierung, Sondenzeile, Crash-Puffer).
  DATA_LOSS  (3) verwaiste Datei fehlt oder wurde verändert/überschrieben, oder Nicht-Sondendateien haben sich verändert
                 (hat Vorrang vor INVALID, sobald die Ausgangslage gültig war).
  INVALID    (2) alles andere: ungültige Ausgangslage, unvollständige/fehlgeschlagene Aufnahme, unvollständiger
                 Instrumentierungsbeleg, fehlende/widersprüchliche/zusätzliche Sondendaten, Absturzbeleg, unlesbare
                 Eingaben – nie ein Sicherheits-PASS.
"""
import argparse
import importlib.util
import os
import re
import sys

HEX64 = re.compile(r'^[0-9a-f]{64}$')
PROBE_CLASS = 'de.evp.helfer.OrphanNameCollisionProbeTest'
PROBE_TEST = 'downloadOverAnOrphanedFinalName'
PROBE_LINE = re.compile(r'^ORPHAN_PROBE sdk=(\d+) state=(\S+) reason=(\S*) newSha=([0-9a-f]{64}) queryBefore=(\S+) queryAfter=(\S+) '
                        r'rowsBefore=\[(.*)\] rowsAfter=\[(.*)\]$')
ROW = re.compile(r'(\d+)\|([^|]*)\|(\d+)\|([0-9a-f]{64}|unreadable:\w+)\|([^|]*)')
CRASH = re.compile(r'FATAL EXCEPTION|Fatal signal \d|ANR in |Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_FAILED'
                   r'|DeadSystemException|DeadObjectException|System has crashed|shortMsg=|backtrace:|OutOfMemoryError'
                   r'|INSTRUMENTATION_STATUS: Error=|system_server.*died|beginning of crash')


def _report_class():
    """Strenger am-instrument-Parser des Geräte-Gates (tools/device_gate.py), damit beide denselben Maßstab haben."""
    path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'device_gate.py')
    spec = importlib.util.spec_from_file_location('device_gate_for_orphan_verdict', path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.Report


def snapshot(path):
    """-> {name: (sha, size)}; ValueError bei unlesbaren/doppelten Zeilen oder fehlendem Abschluss SNAPSHOT-END."""
    lines = [l for l in open(path, encoding='utf-8').read().splitlines() if l.strip()]
    if not lines or lines[-1] != 'SNAPSHOT-END' or lines.count('SNAPSHOT-END') != 1:
        raise ValueError('snapshot incomplete (no single final SNAPSHOT-END)')
    out = {}
    for line in lines[:-1]:
        m = re.match(r'^([0-9a-f]{64}) (\d+) (.+)$', line)
        if not m:
            raise ValueError('unreadable snapshot line: %r' % line[:80])
        if m.group(3) in out:
            raise ValueError('duplicate file in snapshot: %r' % m.group(3))
        out[m.group(3)] = (m.group(1), int(m.group(2)))
    return out


def rows(text):
    """'a, b' (Java List.toString) -> [(id, name, size, sha, file)]; ValueError, wenn nicht vollständig lesbar."""
    if text == '':
        return []
    parts = text.split(', ')
    out = []
    for p in parts:
        m = ROW.fullmatch(p)
        if not m:
            raise ValueError('unreadable MediaStore row: %r' % p[:80])
        out.append(m.groups())
    return out


def verdict(before, after, instr, probe_lines, title, orphan_sha, orphan_size, crash=''):
    """-> (Ergebnis, [Gründe]). Reine Funktion."""
    name = title + '.mp4'
    invalid, loss = [], []
    mask = lambda n: n.replace(title, '<title>')
    probe_files = lambda snap: {n: v for n, v in snap.items() if title in n}
    # 1) Ausgangslage: genau die verwaiste Datei mit bekanntem Hash, sonst keine Sondendatei
    if not HEX64.match(orphan_sha or ''):
        invalid.append('orphan SHA-256 malformed')
    if probe_files(before) != {name: (orphan_sha, orphan_size)}:
        invalid.append('initial state is not exactly the orphan %s (%s, %d bytes): %s' % (name, orphan_sha[:12], orphan_size, sorted(probe_files(before))))
        return 'INVALID', invalid   # ohne gültige Ausgangslage ist keine Aussage möglich
    # 2) Datenverlust (beobachtet)
    if name not in after:
        loss.append('orphan missing after the download')
    elif after[name] != (orphan_sha, orphan_size):
        loss.append('orphan overwritten or changed: %s %d -> %s %d' % (orphan_sha[:12], orphan_size, after[name][0][:12], after[name][1]))
    others_before = {n: v for n, v in before.items() if title not in n}
    others_after = {n: v for n, v in after.items() if title not in n}
    if others_before != others_after:
        changed = sorted(set(others_before.items()) ^ set(others_after.items()))
        loss.append('files outside the probe changed: %s' % [c[0] for c in changed][:5])
    if loss:
        return 'DATA_LOSS', loss
    # 3) Instrumentierung: genau der benannte Test, vollständig und ordnungsgemäß beendet, kein Absturz
    rep = _report_class()(instr.splitlines())
    if rep.tests() != [(PROBE_CLASS, PROBE_TEST)] or not rep.ok or rep.failures != 0 or rep.final.get((PROBE_CLASS, PROBE_TEST), ('',))[0] != '0':
        invalid.append('instrumentation is not exactly the completed, passing test %s#%s' % (PROBE_CLASS, PROBE_TEST))
    for p in rep.problems:
        invalid.append('instrumentation report: %s' % p)
    for what, text in (('instrumentation', instr), ('probe lines', '\n'.join(probe_lines)), ('crash buffer/system log', crash)):
        m = CRASH.search(text)
        if m:
            invalid.append('crash/abort evidence in the %s: %s' % (what, m.group(0)))
    if crash.strip():
        invalid.append('crash buffer/system log after the probe is not empty')
    # 4) Sondenzeile: genau eine, vollständig lesbar
    lines = [l for l in probe_lines if l.strip()]
    if len(lines) != 1 or not lines[0].startswith('ORPHAN_PROBE '):
        invalid.append('expected exactly one ORPHAN_PROBE line and nothing else, found %d lines' % len(lines))
        return 'INVALID', invalid
    m = PROBE_LINE.match(lines[0])
    if not m:
        invalid.append('ORPHAN_PROBE line unreadable or incomplete (state, newSha, queryBefore/After, rowsBefore/After)')
        return 'INVALID', invalid
    _, state, reason, new_sha, q_before, q_after, r_before, r_after = m.groups()
    if state != 'done':
        invalid.append('publication not proven: state=%s reason=%s (only "done" counts)' % (state, reason))
    if new_sha == orphan_sha:
        invalid.append('new content equals the orphan content: collision not tested')
    if q_before != 'ok':
        invalid.append('initial MediaStore query not proven successful: queryBefore=%s' % q_before)
    if q_after != 'ok':
        invalid.append('final MediaStore query not proven successful: queryAfter=%s' % q_after)
    try:
        rb, ra = rows(r_before), rows(r_after)
    except ValueError as e:
        invalid.append(str(e))
        return 'INVALID', invalid
    if rb:
        invalid.append('initial MediaStore query already found %d matching entr%s (orphan must have no row)' % (len(rb), 'y' if len(rb) == 1 else 'ies'))
    # 5) Genau eine neue Datei und genau ein neuer Eintrag, übereinstimmend
    new_files = {n: v for n, v in probe_files(after).items() if n != name}
    if len(new_files) != 1:
        invalid.append('expected exactly one new probe file next to the orphan, found %d: %s' % (len(new_files), sorted(mask(n) for n in new_files)))
    elif next(iter(new_files.values()))[0] != new_sha:
        invalid.append('new probe file %s does not have the downloaded SHA-256' % mask(next(iter(new_files))))
    if len(ra) != 1:
        invalid.append('expected exactly one MediaStore entry after the download, found %d' % len(ra))
    else:
        _, _, _, row_sha, row_file = ra[0]
        if row_sha != new_sha:
            invalid.append('MediaStore entry content %s is not the downloaded content' % row_sha[:16])
        if len(new_files) == 1 and row_file != mask(next(iter(new_files))):
            invalid.append('MediaStore entry file %r is not the new file on disk %r' % (row_file, mask(next(iter(new_files)))))
    if invalid:
        return 'INVALID', invalid
    return 'PASS', ['orphan %s byte-identical; exactly one new file %s with the downloaded content and exactly one MediaStore entry for it; '
                    'initial query ok without entry; other files unchanged; test completed without crash' % (mask(name), mask(next(iter(new_files))))]


def main():
    a = argparse.ArgumentParser()
    for k in ('before', 'after', 'instr', 'probe', 'crash', 'title', 'orphan-sha'):
        a.add_argument('--' + k, required=True)
    a.add_argument('--orphan-size', type=int, required=True)
    o = a.parse_args()
    try:
        result, reasons = verdict(snapshot(o.before), snapshot(o.after), open(o.instr, encoding='utf-8', errors='replace').read(),
                                  open(o.probe, encoding='utf-8', errors='replace').read().splitlines(), o.title, o.orphan_sha, o.orphan_size,
                                  open(o.crash, encoding='utf-8', errors='replace').read())
    except Exception as e:  # unlesbare Eingaben: nie PASS
        result, reasons = 'INVALID', ['evaluation error: %s: %s' % (type(e).__name__, e)]
    for r in reasons:
        print('ORPHAN_REASON ' + r.replace(o.title, '<title>'))
    print('ORPHAN_RESULT %s' % result)
    sys.exit({'PASS': 0, 'INVALID': 2, 'DATA_LOSS': 3}[result])


if __name__ == '__main__':
    main()
