#!/usr/bin/env python3
"""Selbsttest für tools/delivery/orphan_verdict.py: nur PASS bei nachgewiesener Veröffentlichung, unveränderter verwaister
Datei und unveränderten übrigen Dateien; beobachteter Datenverlust = DATA_LOSS; jede ungültige oder unvollständige Sonde
= INVALID. Keine Geräte, nur synthetische Eingaben. Aufruf: python3 -I tests/delivery/test_orphan_verdict.py
Codex NO-GO 3: zusätzlich die reproduzierten False-PASS-Fälle (rowsBefore mit Eintrag/fehlend, rowsAfter fehlend/leer,
zusätzliche Sondendateien, unvollständiger Instrumentierungsbeleg, Absturz/OOM nach der Erfolgsmeldung, null-Cursor)."""
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOOL = os.path.join(ROOT, 'tools', 'delivery', 'orphan_verdict.py')
sys.path.insert(0, os.path.dirname(TOOL))
import orphan_verdict as V  # noqa: E402

T = 'C2 OrphanProbe a79f83375236'
N = T + '.mp4'
OSHA = '65b1cec04abe4c93f8470e3504dd82517a5aae6fcd7ce6bf2307ee399eeadb7e'   # echte Werte aus Lauf 44113819135
NSHA = '2e88a1ab58f3597ffd8031cf96906e76f08f10676fabf0447ff0d64dc0ca2d9a'
XSHA = 'f' * 64
OTHER = {'Unrelated.mp4': ('a' * 64, 1234)}
BEFORE = dict(OTHER, **{N: (OSHA, 98304)})
AFTER = dict(BEFORE, **{T + ' (1).mp4': (NSHA, 262144)})
C, TEST = 'de.evp.helfer.OrphanNameCollisionProbeTest', 'downloadOverAnOrphanedFinalName'
HEAD = ('INSTRUMENTATION_STATUS: class=%s\nINSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n'
        'INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: stream=\nINSTRUMENTATION_STATUS: test=%s\nINSTRUMENTATION_STATUS_CODE: 1\n' % (C, TEST))
INSTR_OK = (HEAD + 'INSTRUMENTATION_STATUS: class=%s\nINSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n'
            'INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: stream=.\nINSTRUMENTATION_STATUS: test=%s\nINSTRUMENTATION_STATUS_CODE: 0\n'
            'INSTRUMENTATION_RESULT: stream=\n\nTime: 2.1\n\nOK (1 test)\n\n\nINSTRUMENTATION_CODE: -1\n' % (C, TEST))
ROW = '1000000017|<title> (1).mp4|262144|%s|<title> (1).mp4' % NSHA
PROBE = ['ORPHAN_PROBE sdk=34 state=done reason= newSha=%s queryBefore=ok queryAfter=ok rowsBefore=[] rowsAfter=[%s]' % (NSHA, ROW)]
fails = []


def check(cond, what):
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


def run(before=BEFORE, after=AFTER, instr=INSTR_OK, probe=PROBE, osha=OSHA, osize=98304, crash=''):
    return V.verdict(before, after, instr, probe, T, osha, osize, crash)


def probe(**kw):
    """ORPHAN_PROBE-Zeile mit geänderten Feldern (None = Feld fehlt)."""
    f = dict(state='done', reason='', newSha=NSHA, queryBefore='ok', queryAfter='ok', rowsBefore='[]', rowsAfter='[%s]' % ROW)
    f.update(kw)
    parts = ['%s=%s' % (k, v) for k, v in f.items() if v is not None]
    return ['ORPHAN_PROBE sdk=34 ' + ' '.join(parts)]


r, why = run()
check(r == 'PASS', 'positive control (real values of run 44113819135): PASS (%s)' % why[0])

CASES = [
    ('DATA_LOSS', 'orphan overwritten with the new content', dict(after=dict(AFTER, **{N: (NSHA, 262144)}))),
    ('DATA_LOSS', 'orphan overwritten, same size, other hash', dict(after=dict(AFTER, **{N: (XSHA, 98304)}))),
    ('DATA_LOSS', 'orphan truncated', dict(after=dict(AFTER, **{N: (XSHA, 0)}))),
    ('DATA_LOSS', 'orphan missing', dict(after={k: v for k, v in AFTER.items() if k != N})),
    ('DATA_LOSS', 'other file changed', dict(after=dict(AFTER, **{'Unrelated.mp4': (XSHA, 1234)}))),
    ('DATA_LOSS', 'other file deleted', dict(after={k: v for k, v in AFTER.items() if k != 'Unrelated.mp4'})),
    ('DATA_LOSS', 'foreign file appeared', dict(after=dict(AFTER, **{'Stranger.mp4': (XSHA, 1)}))),
    ('DATA_LOSS', 'orphan lost even though the test reported failure', dict(after={k: v for k, v in AFTER.items() if k != N},
                                                                             instr='FAILURES!!!\nTests run: 1,  Failures: 1\n')),
    ('INVALID', 'orphan was not created (empty initial state)', dict(before=OTHER)),
    ('INVALID', 'orphan created with another hash', dict(before=dict(OTHER, **{N: (XSHA, 98304)}))),
    ('INVALID', 'orphan created with another size', dict(before=dict(OTHER, **{N: (OSHA, 5)}))),
    ('INVALID', 'extra probe file already present before the download', dict(before=dict(BEFORE, **{T + ' (1).mp4': (NSHA, 262144)}))),
    ('INVALID', 'malformed orphan hash', dict(osha='zz')),
    ('INVALID', 'probe test failed', dict(instr='FAILURES!!!\nTests run: 1,  Failures: 1\n')),
    ('INVALID', 'probe test crashed', dict(instr='INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n')),
    ('INVALID', 'no test result at all', dict(instr='')),
    ('INVALID', 'OK (0 tests)', dict(instr='OK (0 tests)\n')),
    ('INVALID', 'two OK lines', dict(instr=INSTR_OK + 'OK (1 test)\n')),
    ('INVALID', 'ORPHAN_PROBE line missing', dict(probe=[])),
    ('INVALID', 'ORPHAN_PROBE line twice', dict(probe=PROBE * 2)),
    ('INVALID', 'ORPHAN_PROBE unreadable', dict(probe=['ORPHAN_PROBE sdk=34 garbage'])),
    ('INVALID', 'download failed:storage (publication not proven)', dict(probe=[PROBE[0].replace('state=done reason=', 'state=failed reason=storage')])),
    ('INVALID', 'download failed:internal (publication not proven)', dict(probe=[PROBE[0].replace('state=done reason=', 'state=failed reason=internal')])),
    ('INVALID', 'download stopped before publication (not_mp4)', dict(probe=[PROBE[0].replace('state=done reason=', 'state=failed reason=not_mp4')])),
    ('INVALID', 'state done but no new file on disk', dict(after=BEFORE)),
    ('INVALID', 'new file on disk with another hash', dict(after=dict(BEFORE, **{T + ' (1).mp4': (XSHA, 262144)}))),
    ('INVALID', 'new content equal to the orphan content', dict(probe=[PROBE[0].replace(NSHA, OSHA)],
                                                                after=dict(BEFORE, **{T + ' (1).mp4': (OSHA, 98304)}))),
    # Codex NO-GO 3: reproduzierte False-PASS-Fälle
    ('INVALID', 'rowsBefore already contains a MediaStore entry', dict(probe=probe(rowsBefore='[1000000016|<title>.mp4|98304|%s|<title>.mp4]' % OSHA))),
    ('INVALID', 'rowsBefore missing', dict(probe=probe(rowsBefore=None))),
    ('INVALID', 'rowsAfter missing', dict(probe=probe(rowsAfter=None))),
    ('INVALID', 'rowsAfter empty', dict(probe=probe(rowsAfter='[]'))),
    ('INVALID', 'two MediaStore entries after the download', dict(probe=probe(rowsAfter='[%s, %s]' % (ROW, ROW.replace('1000000017', '1000000018'))))),
    ('INVALID', 'MediaStore entry points to another file than the new one', dict(probe=probe(rowsAfter='[%s]' % ROW.replace('|<title> (1).mp4', '|<title> (2).mp4', 1)[::-1].replace(')1( >eltit<', ')2( >eltit<', 1)[::-1]))),
    ('INVALID', 'MediaStore entry content is not the downloaded content', dict(probe=probe(rowsAfter='[%s]' % ROW.replace(NSHA, XSHA)))),
    ('INVALID', 'initial query returned a null cursor', dict(probe=probe(queryBefore='null-cursor'))),
    ('INVALID', 'final query failed', dict(probe=probe(queryAfter='error:SecurityException'))),
    ('INVALID', 'query status fields missing (old protocol line)', dict(probe=probe(queryBefore=None, queryAfter=None))),
    ('INVALID', 'additional probe file with wrong content', dict(after=dict(AFTER, **{T + ' (2).mp4': (XSHA, 5)}))),
    ('INVALID', 'second additional probe file with the correct download hash', dict(after=dict(AFTER, **{T + ' (2).mp4': (NSHA, 262144)}))),
    ('INVALID', 'additional hidden pending probe file', dict(after=dict(AFTER, **{'.pending-1700000000-' + T + '.mp4': (XSHA, 7)}))),
    ('INVALID', 'incomplete instrumentation evidence: only "OK (1 test)"', dict(instr='OK (1 test)\n')),
    ('INVALID', 'incomplete instrumentation evidence: runner end missing', dict(instr=INSTR_OK.replace('INSTRUMENTATION_CODE: -1\n', ''))),
    ('INVALID', 'instrumentation reports another test', dict(instr=INSTR_OK.replace(TEST, 'somethingElse'))),
    ('INVALID', 'FATAL EXCEPTION after the apparent success (instrumentation output)', dict(instr=INSTR_OK + 'E AndroidRuntime: FATAL EXCEPTION: evp-dl\n')),
    ('INVALID', 'OutOfMemoryError in the crash buffer after the success', dict(crash='E AndroidRuntime: java.lang.OutOfMemoryError: Failed to allocate\n')),
    ('INVALID', 'crash buffer not empty after the success', dict(crash='--------- beginning of crash\n')),
    ('INVALID', 'extra unknown line in the probe evidence', dict(probe=PROBE + ['something else'])),
    ('DATA_LOSS', 'orphan lost and extra probe data (data loss wins)', dict(after={k: v for k, v in dict(AFTER, **{T + ' (2).mp4': (NSHA, 1)}).items() if k != N})),
]
for want, what, kw in CASES:
    r, why = run(**kw)
    check(r == want and bool(why), '%s: %s (%s)' % (want, what, (why or ['NO REASON'])[0][:100]))

# Kommandozeile: Exit-Codes, unlesbare Eingaben, ausgegebene Belege
with tempfile.TemporaryDirectory() as d:
    def write(name, text):
        p = os.path.join(d, name)
        open(p, 'w').write(text)
        return p

    def snap(s, end=True):
        return ''.join('%s %d %s\n' % (v[0], v[1], k) for k, v in sorted(s.items())) + ('SNAPSHOT-END\n' if end else '')

    def cli(before, after, instr=INSTR_OK, probe='\n'.join(PROBE) + '\n', crash=''):
        p = subprocess.run([sys.executable, '-I', TOOL, '--before', write('b', before), '--after', write('a', after), '--instr', write('i', instr),
                            '--probe', write('p', probe), '--crash', write('c', crash), '--title', T, '--orphan-sha', OSHA, '--orphan-size', '98304'],
                           capture_output=True, text=True)
        return p.returncode, p.stdout

    for label, args, code, word in [('PASS', (snap(BEFORE), snap(AFTER)), 0, 'ORPHAN_RESULT PASS'),
                                    ('DATA_LOSS', (snap(BEFORE), snap(dict(AFTER, **{N: (NSHA, 262144)}))), 3, 'ORPHAN_RESULT DATA_LOSS'),
                                    ('INVALID', (snap(BEFORE), snap(AFTER), INSTR_OK, ''), 2, 'ORPHAN_RESULT INVALID'),
                                    ('unreadable snapshot', ('garbage line\n', snap(AFTER)), 2, 'ORPHAN_RESULT INVALID'),
                                    ('duplicate snapshot line', (snap(BEFORE) * 2, snap(AFTER)), 2, 'ORPHAN_RESULT INVALID'),
                                    ('before snapshot incomplete (no SNAPSHOT-END)', (snap(BEFORE, end=False), snap(AFTER)), 2, 'ORPHAN_RESULT INVALID'),
                                    ('after snapshot incomplete (no SNAPSHOT-END)', (snap(BEFORE), snap(AFTER, end=False)), 2, 'ORPHAN_RESULT INVALID'),
                                    ('snapshot error line', (snap(BEFORE), 'SNAPSHOT-ERROR x\n' + snap(AFTER)), 2, 'ORPHAN_RESULT INVALID'),
                                    ('crash file not empty', (snap(BEFORE), snap(AFTER), INSTR_OK, '\n'.join(PROBE) + '\n', 'FATAL EXCEPTION: main\n'), 2,
                                     'ORPHAN_RESULT INVALID')]:
        rc, out = cli(*args)
        check(rc == code and word in out and T not in out, 'cli %s: exit %d, %s, title masked' % (label, rc, word))

print('ORPHAN_VERDICT_SELFTEST %s %d cases, %d failed' % ('FAIL' if fails else 'PASS', len(CASES) + 10, len(fails)))
sys.exit(1 if fails else 0)
