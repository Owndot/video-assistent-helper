#!/usr/bin/env python3
# LOCAL COMPARISON ONLY - not used by the A/B run (tools/run-pkgremoval-probe.sh uses tools/pkgab_observe.py); no causal
# conclusion of this file is part of any run result. Kept only to compare against earlier review rounds.
"""Regression-first-Nachweis zu Codex-NO-GO 1 (nur lokal, nicht in CI: braucht den alten Stand).

Bewertet jeden Fall aus tests/bootscan/test_pkgremoval_verdict.py mit dem ALTEN Bewerter (Stand 1578394) und dem NEUEN,
und gezielte Hostskript-Fälle (nachgebildetes adb) mit dem ALTEN und dem NEUEN Hostskript. Für den alten Bewerter werden die Eingaben in
das alte Format gebracht (keine op-/absent-/sync-Marker, QT-END ohne Endzeit), so wie das alte Hostskript sie erzeugte –
sonst würde der alte Bewerter schon an unbekannten Markern scheitern und keine Fehlentscheidung zeigen.

Aufruf: python3 -I tests/bootscan/regress_pkgab_nogo1.py ALT_VERZEICHNIS
  ALT_VERZEICHNIS enthält tools/{pkgremoval_verdict,bootscan_verdict,device_gate}.py und tools/run-pkgremoval-probe.sh
  aus 1578394 (z. B. per "git show 1578394:tools/…").
"""
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OLD = os.path.abspath(sys.argv[1])
sys.path.insert(0, HERE)
import test_pkgremoval_verdict as T  # noqa: E402


def old_format(sim):
    log = '\n'.join(l for l in sim['logcat'].splitlines()
                    if not re.search(r'BOOTSCAN PKG .*event=(op-begin|op-end|absent|sync)\b', l)) + '\n'
    hosts = re.sub(r'(?m)^(QT-END rc=\d+) t=\d+$', r'\1', sim['hosts'])
    return log, hosts


def run(tool, variant, run_id, log, hosts, instr, before, after, d):
    def w(n, s):
        p = os.path.join(d, n)
        open(p, 'w').write(s)
        return p
    lst = lambda m: ''.join('%s %d %s\n' % (v[0], v[1], k) for k, v in m.items()) + 'LIST-END\n'
    p = subprocess.run([sys.executable, '-I', tool, 'variant', '--variant', variant, '--run', run_id, '--logcat', w('l', log),
                        '--host-sampler', w('h', hosts), '--instr-pre', w('i1', instr['pre']), '--instr-post', w('i2', instr['post']),
                        '--instr-recheck', w('i3', instr['recheck']), '--foreign-before', w('b', lst(before)), '--foreign-after', w('a', lst(after))],
                       capture_output=True, text=True)
    m = re.findall(r'PKGAB_VARIANT variant=\w+ (?:run=\w+ )?result=(\w+)', p.stdout)
    why = re.findall(r'PKGAB_REASON variant=\w+ (.*)', p.stdout)
    return (m[-1] if m else 'NO-RESULT'), (why[0][:90] if why else '')


with tempfile.TemporaryDirectory() as d:
    print('| Befund | Fall | alt (1578394) | neu |')
    print('|---|---|---|---|')
    for finding, label, variant, sim, ok in T.cases():
        log, hosts = old_format(sim)
        old = run(os.path.join(OLD, 'tools', 'pkgremoval_verdict.py'), variant, sim['run'], log, hosts, sim['instr'], sim['before'], sim['after'], d)
        new = run(os.path.join(ROOT, 'tools', 'pkgremoval_verdict.py'), variant, sim['run'], sim['logcat'], sim['hosts'], sim['instr'],
                  sim['before'], sim['after'], d)
        print('| %s | %s | %s | %s |' % (finding, label, old[0], new[0]))
# Hostskript: gezielte Fälle mit altem und neuem Skript (nachgebildetes adb, Gerätesimulator)
src = open(os.path.join(HERE, 'test_pkgremoval_script.py')).read().split('# ---- Ablehnungen')[0]
print('\n| Befund | Hostskript-Fall | alt (1578394) | neu |')
print('|---|---|---|---|')
rows = {}
for tag, script in (('alt', os.path.join(OLD, 'tools', 'run-pkgremoval-probe.sh')), ('neu', os.path.join(ROOT, 'tools', 'run-pkgremoval-probe.sh'))):
    ns = {'__file__': os.path.join(HERE, 'test_pkgremoval_script.py')}
    sys.argv = ['x', '--script', script]
    exec(src, ns)
    sup = ns['SUPPORTED']
    for f, label, variant, kw in [('1', 'uninstall of the test package: Failure with rc=0', 'uninstall', dict(sim=dict(sup, uninstall_fail='test', uninstall_rc=0))),
                                  ('1', 'uninstall of the app package: rc=1', 'uninstall', dict(sim=dict(sup, uninstall_fail='app', uninstall_rc=1))),
                                  ('1', 'uninstall reports Success, app package stays installed', 'uninstall', dict(sim=dict(sup, uninstall_fail='lie-app'))),
                                  ('6', 'find fails while listing foreign files', 'control', dict(find_fails=True, sim={'scan_after_pkg_end': 2000})),
                                  ('6', 'rejected device (not an emulator): logs collected', 'control', dict(device='phone'))]:
        r = ns['run'](variant, **kw)
        res = re.findall(r'PKGAB_VARIANT variant=\w+ (?:run=\w+ )?result=(\w+)', r['out'] + r['verdict'])
        logs = [c for c in r['cmds'] if c.startswith('logcat')]
        un = [i for i, c in enumerate(r['cmds']) if c.startswith('uninstall')]
        reinstall = any(c.startswith('install') for c in r['cmds'][un[-1]:]) if un else False
        desc = 'exit %d, %s%s%s' % (r['rc'], res[-1] if res else 'no result', ', reinstalled' if reinstall else '',
                                    (', logcat: %s' % ('yes' if logs else 'no')) if 'device' in label else '')
        rows.setdefault((f, label), {})[tag] = desc
for (f, label), v in rows.items():
    print('| %s | %s | %s | %s |' % (f, label, v['alt'], v['neu']))
