#!/usr/bin/env python3
# LOCAL COMPARISON ONLY - not used by the A/B run (tools/run-pkgremoval-probe.sh uses tools/pkgab_observe.py); no causal
# conclusion of this file is part of any run result. Kept only to compare against earlier review rounds.
"""Regression-first-Nachweis zu Codex-NO-GO 2 (nur lokal, braucht den alten Stand 5627df2).

1. A/B-Bewerter: die Fälle P1-1 … P2-5 aus tests/bootscan/test_pkgremoval_verdict.py mit altem und neuem Bewerter. Die
   P1-Fälle werden OHNE reguläre Prozessereignisse erzeugt (PKGAB_SIM_NO_PROC_EVENTS=1), weil der alte Stand sonst schon
   an P2-5 scheitert und die anderen Befunde verdeckt; die P2-5-Fälle mit ihnen.
2. Boot-Scan-Bewerter: Schlussaufnahme gegen frühere URI-Abfrage und Dateischaden bei ungültiger Provideraufnahme.
3. Java-Providerparser: der alte privRows-Rumpf aus 5627df2 (herausgelöst, Shell-Aufruf durch Eingabezeilen ersetzt) gegen
   ProbeParsers.parsePriv mit denselben Gegenbeispielen (nur mit javac/java).

Aufruf: python3 -I tests/bootscan/regress_pkgab_nogo2.py ALT_VERZEICHNIS
  ALT_VERZEICHNIS: tools/{pkgremoval_verdict,bootscan_verdict,device_gate}.py und tests/bootscan/MediaStoreBootScanProbeTest.java
  aus 5627df2 (git show 5627df2:…).
"""
import importlib.util
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OLD = os.path.abspath(sys.argv[1])
sys.path.insert(0, HERE)


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


OLD_V = load(os.path.join(OLD, 'tools', 'pkgremoval_verdict.py'), 'old_pkgremoval_verdict')
NEW_V = load(os.path.join(ROOT, 'tools', 'pkgremoval_verdict.py'), 'new_pkgremoval_verdict')


def ev(V, variant, sim):
    try:
        return V.variant_verdict(variant, sim['run'], sim['logcat'], sim['hosts'], {k: sim['instr'][k] for k in ('pre', 'post', 'recheck')},
                                 sim['before'], sim['after'])[0]
    except Exception as e:  # noqa: BLE001 - der alte Stand darf an neuen Eingaben scheitern; das wird gezeigt
        return 'ERROR:%s' % type(e).__name__


print('| Befund | Fall | alt (5627df2) | neu |')
print('|---|---|---|---|')
for env, prefix in (('1', 'P1-'), (None, 'P2-')):
    if env:
        os.environ['PKGAB_SIM_NO_PROC_EVENTS'] = env
    else:
        os.environ.pop('PKGAB_SIM_NO_PROC_EVENTS', None)
    T = load(os.path.join(HERE, 'test_pkgremoval_verdict.py'), 'cases_%s' % prefix.strip('-'))
    for finding, label, variant, sim, ok in T.cases():
        if finding.startswith(prefix):
            print('| %s | %s | %s | %s |' % (finding, label, ev(OLD_V, variant, sim), ev(NEW_V, variant, sim)))
os.environ.pop('PKGAB_SIM_NO_PROC_EVENTS', None)

# ---- Boot-Scan-Bewerter (gemeinsame Klassifikation)
src = open(os.path.join(HERE, 'test_bootscan_verdict.py')).read().split('# --- jede Klasse genau erkannt')[0]
ns = {'__file__': os.path.join(HERE, 'test_bootscan_verdict.py')}
exec(src, ns)
OB = load(os.path.join(OLD, 'tools', 'bootscan_verdict.py'), 'old_bootscan_verdict')
NB = load(os.path.join(ROOT, 'tools', 'bootscan_verdict.py'), 'new_bootscan_verdict')
print('\n| Befund | Boot-Scan-Fall | alt (5627df2) | neu |')
print('|---|---|---|---|')
for label, downloads, kw in [('URI visible in the recheck, row lost in the final snapshot', [ns['overlap'](1, 'VIS_THEN_LOST')], {}),
                             ('URI visible in the recheck, owner NULL in the final snapshot', [ns['overlap'](1, 'VIS_THEN_NULL')], {}),
                             ('URI visible in the recheck, pending=1 in the final snapshot', [ns['overlap'](1, 'VIS_THEN_PENDING')], {}),
                             ('final privileged query failed, valid disk snapshot shows changed file', [ns['overlap'](1, 'DATA_LOSS_SHA')],
                              {'recheck_priv': 'bad:frame-Q-END_rc-1'})]:
    log, instr, before, after = ns['build'](ns['BASE'] + downloads, **kw)
    out = []
    for M in (OB, NB):
        r, why, rows, groups = M.evaluate(log, ns['RUN'], instr, before, after, ns['PKG'])
        out.append('%s (seq 1: %s)' % (r, {s_: c for s_, g, c, w in rows}.get('1')))
    print('| %s | %s | %s | %s |' % ('P1-2' if 'disk' in label else 'P1-1', label, out[0], out[1]))

# ---- Java-Providerparser
if shutil.which('javac') and shutil.which('java'):
    old_java = open(os.path.join(OLD, 'tests', 'bootscan', 'MediaStoreBootScanProbeTest.java')).read()
    body = re.search(r'    Map<String, String> privRows\(String\[\] status\) \{\n(.*?)\n    \}\n', old_java, re.S).group(1)
    body = re.sub(r'        List<String> lines;\n        try \{ lines = shell\([^\n]*\n        catch [^\n]*\n', '', body)
    row = re.search(r'static final Pattern ROW = (Pattern\.compile\(.*?\));', old_java, re.S).group(1)
    harness = ('import java.util.*; import java.util.regex.*;\npublic class OldPriv {\n static final Pattern ROW = %s;\n'
               ' static String clean(String v) { return v == null ? "NULL" : v.replace(\' \', \'_\').replace(\'|\', \'_\'); }\n'
               ' static Map<String, String> privRows(List<String> lines, String[] status) {\n%s\n }\n'
               ' public static void main(String[] a) throws Exception {\n'
               '  List<String> l = new ArrayList<>(); String name = null;\n'
               '  for (String s : new String(System.in.readAllBytes()).split("\\n")) {\n'
               '   if (s.startsWith("CASE ")) { name = s.substring(5); l.clear(); }\n'
               '   else if (s.equals("END")) { String[] st = {"?"}; Map<String, String> m = privRows(l, st); System.out.println("CASE " + name + " " + st[0] + " " + (m == null ? -1 : m.size())); }\n'
               '   else l.add(s); } } }\n') % (row, body)
    p1 = '/storage/emulated/0/Download/Video-Assistent/BootScanProbe-a1-1.mp4'
    rw = lambda n, i, o: 'Row: %d _id=%s, owner_package_name=%s, is_pending=0, is_trashed=0, _data=%s, _display_name=x, _size=1' % (n, i, o, p1)
    cases = {'dup-id-conflict': ['Q-BEGIN', rw(0, '11', 'de.evp'), rw(1, '11', 'NULL'), 'Q-END rc=0'],
             'rownum-gap': ['Q-BEGIN', rw(0, '11', 'de.evp'), rw(2, '12', 'de.evp'), 'Q-END rc=0'],
             'rownum-start': ['Q-BEGIN', rw(1, '11', 'de.evp'), 'Q-END rc=0']}
    with tempfile.TemporaryDirectory() as d:
        open(os.path.join(d, 'OldPriv.java'), 'w').write(harness)
        subprocess.run(['javac', '-d', d, os.path.join(d, 'OldPriv.java')], check=True, capture_output=True)
        inp = ''.join('CASE %s\n%s\nEND\n' % (k, '\n'.join(v)) for k, v in cases.items())
        old = subprocess.run(['java', '-cp', d, 'OldPriv'], input=inp, capture_output=True, text=True).stdout
        subprocess.run(['javac', '-d', d, os.path.join(HERE, 'ProbeParsers.java'), os.path.join(HERE, 'ProbeParsersCheck.java')], check=True, capture_output=True)
        new = subprocess.run(['java', '-cp', d, 'ProbeParsersCheck'], capture_output=True, text=True).stdout
    og = {l.split()[1]: ' '.join(l.split()[2:]) for l in old.splitlines() if l.startswith('CASE ')}
    ng = {l.split()[1]: ' '.join(l.split()[2:]) for l in new.splitlines() if l.startswith('CASE ')}
    print('\n| Befund | Java-Parserfall | alt (5627df2) | neu |')
    print('|---|---|---|---|')
    for k in cases:
        print('| P1-3 | %s | %s | %s |' % (k, og.get(k), ng.get(k)))
else:
    print('\nJava parser comparison NOT RUN: javac/java not available')

# ---- Python-Hostparser (Host-Abfragen mit Gerätezeit)
P = '/storage/emulated/0/Download/Video-Assistent/BootScanProbe-a1-1.mp4'
hrow = lambda n, i, o: 'Row: %d _id=%s, owner_package_name=%s, is_pending=0, is_trashed=0, _data=%s, _display_name=x, _size=1' % (n, i, o, P)
hqt = lambda rows, end='QT-END rc=0 t=20': '\n'.join(['QT-BEGIN t=10 res=ms'] + rows + [end]) + '\n'
print('\n| Befund | Hostparser-Fall | alt (5627df2) | neu |')
print('|---|---|---|---|')
for name, text in [('duplicate id with conflicting owner', hqt([hrow(0, '11', 'de.evp'), hrow(1, '11', 'NULL')])),
                   ('row number gap', hqt([hrow(0, '11', 'de.evp'), hrow(2, '12', 'de.evp')])),
                   ('query ends before it began', hqt([hrow(0, '11', 'de.evp')], 'QT-END rc=0 t=5'))]:
    o = OLD_V.host_samples(text, [])
    inv = []
    n = NEW_V.host_samples(text, [], inv)
    print('| %s | %s | %d sample(s) used | %d sample(s) used%s |' % ('P1-4' if 'began' in name else 'P1-3', name, len(o), len(n), ', INVALID' if inv else ''))
