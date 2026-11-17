#!/usr/bin/env python3
"""Selbsttest für tools/delivery/orphan_verdict.py: nur PASS bei nachgewiesener Veröffentlichung, unveränderter verwaister
Datei und unveränderten übrigen Dateien; beobachteter Datenverlust = DATA_LOSS; jede ungültige oder unvollständige Sonde
= INVALID. Keine Geräte, nur synthetische Eingaben. Aufruf: python3 -I tests/delivery/test_orphan_verdict.py"""
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
INSTR_OK = 'INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\n\nOK (1 test)\n\n\nINSTRUMENTATION_CODE: -1\n'
PROBE = ['ORPHAN_PROBE sdk=34 state=done reason= newSha=%s rowsBefore=[] rowsAfter=[1|<title> (1).mp4|262144|%s|<title> (1).mp4]' % (NSHA, NSHA)]
fails = []


def check(cond, what):
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond:
        fails.append(what)


def run(before=BEFORE, after=AFTER, instr=INSTR_OK, probe=PROBE, osha=OSHA, osize=98304):
    return V.verdict(before, after, instr, probe, T, osha, osize)


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

    def snap(s):
        return ''.join('%s %d %s\n' % (v[0], v[1], k) for k, v in sorted(s.items()))

    def cli(before, after, instr=INSTR_OK, probe='\n'.join(PROBE) + '\n'):
        p = subprocess.run([sys.executable, '-I', TOOL, '--before', write('b', before), '--after', write('a', after), '--instr', write('i', instr),
                            '--probe', write('p', probe), '--title', T, '--orphan-sha', OSHA, '--orphan-size', '98304'],
                           capture_output=True, text=True)
        return p.returncode, p.stdout

    for label, args, code, word in [('PASS', (snap(BEFORE), snap(AFTER)), 0, 'ORPHAN_RESULT PASS'),
                                    ('DATA_LOSS', (snap(BEFORE), snap(dict(AFTER, **{N: (NSHA, 262144)}))), 3, 'ORPHAN_RESULT DATA_LOSS'),
                                    ('INVALID', (snap(BEFORE), snap(AFTER), INSTR_OK, ''), 2, 'ORPHAN_RESULT INVALID'),
                                    ('unreadable snapshot', ('garbage line\n', snap(AFTER)), 2, 'ORPHAN_RESULT INVALID'),
                                    ('duplicate snapshot line', (snap(BEFORE) * 2, snap(AFTER)), 2, 'ORPHAN_RESULT INVALID')]:
        rc, out = cli(*args)
        check(rc == code and word in out and T not in out, 'cli %s: exit %d, %s, title masked' % (label, rc, word))

print('ORPHAN_VERDICT_SELFTEST %s %d cases, %d failed' % ('FAIL' if fails else 'PASS', len(CASES) + 6, len(fails)))
sys.exit(1 if fails else 0)
