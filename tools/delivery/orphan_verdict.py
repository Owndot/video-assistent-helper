#!/usr/bin/env python3
"""Bewertung der Sonde "verwaiste Datei / gleicher Name" (tools/run-orphan-collision-probe.sh). Nur Testwerkzeug.

Aufruf:
  orphan_verdict.py --before B --after A --instr I --probe P --title T --orphan-sha S --orphan-size N
    B/A: Ordner-Aufnahmen "sha256 größe name" (eine Datei je Zeile), vor und nach dem Download
    I:   vollständige am-instrument-Ausgabe des Sondentests
    P:   Datei mit den ORPHAN_PROBE-Zeilen des Tests (Titel bereits durch <title> ersetzt)

Ergebnis (eine Zeile ORPHAN_RESULT, Exit-Code):
  PASS       (0) Ausgangslage gültig; Sondentest genau einmal OK; Download nachweislich veröffentlicht (state=done,
                 neue Datei mit genau dem neuen SHA-256 unter einem ANDEREN Namen auf der Platte); verwaiste Datei
                 bytegleich unter ihrem Namen; alle Nicht-Sondendateien unverändert.
  DATA_LOSS  (3) verwaiste Datei fehlt oder wurde verändert/überschrieben, oder Nicht-Sondendateien haben sich verändert
                 (hat Vorrang vor INVALID, sobald die Ausgangslage gültig war).
  INVALID    (2) ungültige Ausgangslage, Test nicht genau einmal OK, ORPHAN_PROBE fehlt/mehrdeutig, Veröffentlichung nicht
                 nachgewiesen (state != done oder neue Datei fehlt) oder unlesbare Eingaben – nie ein Sicherheits-PASS.
"""
import argparse
import re
import sys

HEX64 = re.compile(r'^[0-9a-f]{64}$')


def snapshot(path):
    """-> {name: (sha, size)}; ValueError bei unlesbaren oder doppelten Zeilen."""
    out = {}
    for line in open(path, encoding='utf-8').read().splitlines():
        if not line.strip():
            continue
        m = re.match(r'^([0-9a-f]{64}) (\d+) (.+)$', line)
        if not m:
            raise ValueError('unreadable snapshot line: %r' % line[:80])
        if m.group(3) in out:
            raise ValueError('duplicate file in snapshot: %r' % m.group(3))
        out[m.group(3)] = (m.group(1), int(m.group(2)))
    return out


def verdict(before, after, instr, probe_lines, title, orphan_sha, orphan_size):
    """-> (Ergebnis, [Gründe]). Reine Funktion."""
    name = title + '.mp4'
    invalid, loss = [], []
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
    # 3) Sonde vollständig und Veröffentlichung nachgewiesen
    ok = re.findall(r'(?m)^OK \((\d+) tests?\)\s*$', instr)
    if ok != ['1'] or re.search(r'(?m)^(FAILURES!!!|Tests run:)', instr) or re.search(r'INSTRUMENTATION_(ABORTED|FAILED)|Process crashed', instr):
        invalid.append('probe test did not end with exactly "OK (1 test)"')
    lines = [l for l in probe_lines if l.startswith('ORPHAN_PROBE ')]
    if len(lines) != 1:
        invalid.append('expected exactly one ORPHAN_PROBE line, found %d' % len(lines))
        return 'INVALID', invalid
    m = re.search(r' state=(\S+) reason=(\S*) newSha=([0-9a-f]{64}) ', lines[0])
    if not m:
        invalid.append('ORPHAN_PROBE line unreadable')
        return 'INVALID', invalid
    state, reason, new_sha = m.groups()
    if state != 'done':
        invalid.append('publication not proven: state=%s reason=%s (only "done" counts)' % (state, reason))
    if new_sha == orphan_sha:
        invalid.append('new content equals the orphan content: collision not tested')
    new_files = {n: v for n, v in probe_files(after).items() if n != name}
    if not any(v[0] == new_sha for v in new_files.values()):
        invalid.append('no new file with the downloaded SHA-256 next to the orphan (publication not proven on disk): %s'
                       % sorted(new_files))
    if invalid:
        return 'INVALID', invalid
    return 'PASS', ['orphan %s byte-identical; new download stored as %s; other files unchanged'
                    % (name.replace(title, '<title>'), [n.replace(title, '<title>') for n, v in new_files.items() if v[0] == new_sha])]


def main():
    a = argparse.ArgumentParser()
    for k in ('before', 'after', 'instr', 'probe', 'title', 'orphan-sha'):
        a.add_argument('--' + k, required=True)
    a.add_argument('--orphan-size', type=int, required=True)
    o = a.parse_args()
    try:
        result, reasons = verdict(snapshot(o.before), snapshot(o.after), open(o.instr, encoding='utf-8', errors='replace').read(),
                                  open(o.probe, encoding='utf-8', errors='replace').read().splitlines(), o.title, o.orphan_sha, o.orphan_size)
    except Exception as e:  # unlesbare Eingaben: nie PASS
        result, reasons = 'INVALID', ['evaluation error: %s: %s' % (type(e).__name__, e)]
    for r in reasons:
        print('ORPHAN_REASON ' + r.replace(o.title, '<title>'))
    print('ORPHAN_RESULT %s' % result)
    sys.exit({'PASS': 0, 'INVALID': 2, 'DATA_LOSS': 3}[result])


if __name__ == '__main__':
    main()
