#!/usr/bin/env python3
"""Selbsttest des Liefer-Gates (tools/device_gate.py) an synthetischen Job-Logs (tests/gate/fixtures, erzeugt von
make_fixtures.py). Jeder Fall muss genau das erwartete Urteil liefern; zusätzlich prüft der Test die Exit-Codes des
Kommandozeilenaufrufs. Aufruf: python3 -I tests/gate/test_device_gate.py"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import device_gate  # noqa: E402

FX = os.path.join(HERE, 'fixtures')
fails = 0
cases = [l.split() for l in open(os.path.join(FX, 'EXPECTED')).read().splitlines() if l.strip()]
for name, api, want in cases:
    log = open(os.path.join(FX, name + '.log'), encoding='utf-8').read()
    got, reasons = device_gate.evaluate(log, int(api))
    ok = got == want
    fails += 0 if ok else 1
    print('%s %-36s api=%s want=%-18s got=%s%s' % ('PASS' if ok else 'FAIL', name, api, want, got,
                                                  '' if ok or not reasons else '  ' + '; '.join(reasons[:3])))
    if want == 'REJECT' and ok and not reasons:
        fails += 1
        print('FAIL %s rejected without a reason' % name)

# Exit-Codes und Auftragsergebnis (Kommandozeile)
def cli(name, api, conclusion=None):
    cmd = [sys.executable, '-I', os.path.join(ROOT, 'tools', 'device_gate.py'), os.path.join(FX, name + '.log'), '--api', str(api)]
    if conclusion:
        cmd += ['--job-conclusion', conclusion]
    return subprocess.run(cmd, capture_output=True, text=True).returncode

for name, api, conclusion, want in [('pass_api34', 34, 'success', 0), ('size_only_api29', 29, 'failure', 3),
                                    ('size_only_api29', 29, 'success', 1), ('pass_api34', 34, 'failure', 1),
                                    ('publish_rows0_api29', 29, 'failure', 1), ('size_only_api29', 34, None, 1)]:
    rc = cli(name, api, conclusion)
    ok = rc == want
    fails += 0 if ok else 1
    print('%s cli %-20s api=%d conclusion=%s exit=%d (want %d)' % ('PASS' if ok else 'FAIL', name, api, conclusion, rc, want))

print('RESULT device-gate-selftest %s %d cases (%d failed)' % ('PASS' if fails == 0 else 'FAIL', len(cases), fails))
sys.exit(1 if fails else 0)
