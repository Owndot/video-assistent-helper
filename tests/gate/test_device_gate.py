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
    print('%s %-40s api=%s want=%-18s got=%s%s' % ('PASS' if ok else 'FAIL', name, api, want, got,
                                                  '' if ok or not reasons else '  ' + '; '.join(reasons[:3])))
    if want == 'REJECT' and ok and not reasons:
        fails += 1
        print('FAIL %s rejected without a reason' % name)

# Exit-Codes und Auftragsergebnis (Kommandozeile)
def cli(name, api, conclusion=None, steps=None):
    cmd = [sys.executable, '-I', os.path.join(ROOT, 'tools', 'device_gate.py'), os.path.join(FX, name + '.log'), '--api', str(api)]
    if conclusion:
        cmd += ['--job-conclusion', conclusion]
    if steps is not None:
        cmd += ['--step-conclusions', steps]
    return subprocess.run(cmd, capture_output=True, text=True).returncode

for name, api, conclusion, want in [('pass_api34', 34, 'success', 0), ('size_only_api29', 29, 'failure', 3),
                                    ('size_only_api29', 29, 'success', 1), ('pass_api34', 34, 'failure', 1),
                                    ('publish_rows0_api29', 29, 'failure', 1), ('size_only_api29', 34, None, 1),
                                    ('pass_api26_no_probe', 26, 'success', 0), ('probe_cycles_zero_api34', 34, 'success', 1),
                                    ('repeat_old_format_green_api34', 34, 'success', 4), ('mutant_wrong_reason_api34', 34, 'success', 1)]:
    rc = cli(name, api, conclusion)
    ok = rc == want
    fails += 0 if ok else 1
    print('%s cli %-20s api=%d conclusion=%s exit=%d (want %d)' % ('PASS' if ok else 'FAIL', name, api, conclusion, rc, want))

# Runde 5: Schrittergebnisse der drei Geräteschritte (GitHub-API) gegen die Belege; übersprungen/abgebrochen nie grün
OK3 = 'mutant=success,main=success,repeat=success'
for name, api, conclusion, steps, want in [
        ('pass_api34', 34, 'success', OK3, 0),
        ('pass_api34', 34, 'success', 'mutant=success,main=failure,repeat=success', 1),       # Schritt rot, Belege grün
        ('pass_api34', 34, 'failure', 'mutant=success,main=success,repeat=failure', 1),       # Wiederholungen rot, Belege grün
        ('pass_api34', 34, 'success', 'mutant=success,main=success,repeat=skipped', 1),       # übersprungen zählt nie
        ('pass_api34', 34, 'success', 'mutant=cancelled,main=success,repeat=success', 1),     # abgebrochen zählt nie
        ('pass_api34', 34, 'failure', OK3, 1),                                                # Job rot, alle Schritte grün
        ('pass_api34', 34, None, 'mutant=success,main=success', 1),                           # Schritt fehlt
        ('pass_api34', 34, None, OK3 + ',main=success', 1),                                   # Schritt doppelt
        ('pass_api34', 34, None, 'main=success,mutant=success,repeat=success,extra=success', 1),
        ('size_only_api29', 29, 'failure', 'mutant=success,main=failure,repeat=success', 3),
        ('size_only_api29', 29, 'failure', 'mutant=success,main=success,repeat=failure', 1),
        ('size_only_api29', 29, 'success', OK3, 1),
        ('size_only_with_class_repeats_api29', 29, 'failure', 'mutant=success,main=failure,repeat=failure', 3),
        ('size_only_with_class_repeats_api29', 29, 'failure', 'mutant=success,main=failure,repeat=success', 1),
        ('main_failed_repeats_green_api34', 34, 'failure', 'mutant=success,main=failure,repeat=success', 1),
        ('main_failed_repeats_green_api34', 34, 'success', OK3, 1),
        ('main_green_repeats_failed_api34', 34, 'failure', 'mutant=success,main=success,repeat=failure', 1),
        ('main_green_repeats_failed_api34', 34, 'success', OK3, 1)]:
    rc = cli(name, api, conclusion, steps)
    ok = rc == want
    fails += 0 if ok else 1
    print('%s cli %-36s api=%d job=%s steps=%s exit=%d (want %d)' % ('PASS' if ok else 'FAIL', name, api, conclusion, steps, rc, want))

# Parser-/Programmfehler im Gate führen immer zur Ablehnung (fail closed)
for bad_input in [None, 12345, b'GATE-API 34']:
    got, reasons = device_gate.evaluate(bad_input, 34)
    ok = got == 'REJECT' and reasons and reasons[0].startswith('gate parser error')
    fails += 0 if ok else 1
    print('%s parser error on %r input -> %s' % ('PASS' if ok else 'FAIL', type(bad_input).__name__, got))

# Erwartete Testmengen des Gates == Quellstand der Gerätetests (keine stille Abweichung nach Teständerungen)
import re  # noqa: E402
SRC = os.path.join(ROOT, 'app', 'src', 'androidTestChanneltest', 'java')


def tests_in(cls):
    src = open(os.path.join(SRC, *cls.split('.')) + '.java', encoding='utf-8').read()
    return re.findall(r'@Test(?:\([^)]*\))?\s+public void (\w+)\(', src), src


for cls, want in list(device_gate.EXPECTED_MAIN.items()) + [(device_gate.PROBE_CLASS, device_gate.PROBE_TESTS)]:
    got, _ = tests_in(cls)
    ok = got == want
    fails += 0 if ok else 1
    print('%s source %s: %d @Test methods match the gate' % ('PASS' if ok else 'FAIL', cls.split('.')[-1], len(got)))
got, src = tests_in(device_gate.RESTART_CLASS)
ok = sorted(set(got)) == sorted(set(device_gate.RESTARTS.values()))
fails += 0 if ok else 1
print('%s source ProcessRestartInstrumentedTest phases match the gate' % ('PASS' if ok else 'FAIL'))
_, src = tests_in(device_gate.PROBE_CLASS)
consts = {k: int(v) for k, v in re.findall(r'static final int (CYCLES|PAUSE_CYCLES|LOAD_CYCLES) = (\d+);', src)}
variants = re.search(r'enum Variant \{([^}]*)\}', src).group(1).replace('\n', ' ')
variants = [v.strip() for v in variants.split(',') if v.strip()]
want = {v: consts['CYCLES'] if v == 'PLAIN' else consts['LOAD_CYCLES'] if v in ('LARGE_DELETE_BEFORE_COMMIT', 'CONCURRENT_PROVIDER_LOAD') else consts['PAUSE_CYCLES'] for v in variants}
ok = want == device_gate.PROBE_CYCLES and all(n > 0 for n in want.values())
fails += 0 if ok else 1
print('%s source probe variants and positive cycle counts match the gate %s' % ('PASS' if ok else 'FAIL', want))
race = re.search(r'static final int RACE_CYCLES = (\d+);', src)
ok = race is not None and int(race.group(1)) == device_gate.RACE_CYCLES > 0
fails += 0 if ok else 1
print('%s source PUBLISH_RACE cycle count matches the gate (%s)' % ('PASS' if ok else 'FAIL', race and race.group(1)))
rep = open(os.path.join(ROOT, 'tools', 'run-flaky-repeat.sh'), encoding='utf-8').read()
# Codex NO-GO 3: Crash-Puffer nach den Wiederholungen (Format 3) und strenge Mutanten-Shellprüfung
ok = ('adb logcat -d -b crash > "$REPORT/repeat-crash.txt"' in rep and 'echo "GATE-BEGIN %s"' % device_gate.REPEAT_CRASH in rep
      and rep.index('repeat-crash.txt') > rep.index('run "class-$i"'))
fails += 0 if ok else 1
print('%s run-flaky-repeat.sh reads the crash buffer again after all repeat runs' % ('PASS' if ok else 'FAIL'))
main_sh = open(os.path.join(ROOT, 'tools', 'run-channel-instrumentation.sh'), encoding='utf-8').read()
ok = ('adb logcat -d -b crash > "$REPORT/crash-after-uninstall.txt"' in main_sh and 'gate_section %s "$REPORT/crash-after-uninstall.txt"' % device_gate.CRASH_AFTER_UNINSTALL in main_sh
      and main_sh.index('crash-after-uninstall.txt') > main_sh.index('adb uninstall "$PKG" > /dev/null'))
fails += 0 if ok else 1
print('%s run-channel-instrumentation.sh reads the crash buffer again after the uninstall (Runde 5)' % ('PASS' if ok else 'FAIL'))
mut = open(os.path.join(ROOT, 'tools', 'run-channel-publication-regression.sh'), encoding='utf-8').read()
ok = ("echo '%s'" % device_gate.MUTANT_PASS in mut and 'Cannot remove pending download ownership; last abort: ; store: ;' in mut
      and 'publish rows=|publish threw=|IOException: publish' in mut and mut.index('publish rows=|') < mut.index(device_gate.MUTANT_PASS))
fails += 0 if ok else 1
print('%s run-channel-publication-regression.sh requires the marker-cleanup cause and refuses publish failures' % ('PASS' if ok else 'FAIL'))
three = re.search(r'run "three-\$i" "([^"]+)"', rep).group(1)
ok = ([x.split('#')[1] for x in three.split(',')] == device_gate.REPEAT_THREE and 'for i in 1 2; do run "class-$i" "$T" "$T#%s"' % device_gate.REPEAT_CLASS_EXCLUDED in rep
      and device_gate.REPEAT_CLASS_RUNS == 2 and 'echo "%s"' % device_gate.REPEAT_FORMAT in rep
      and 'echo "REPEAT-RUN label=$1 api=$API"' in rep)
fails += 0 if ok else 1
print('%s run-flaky-repeat.sh: three-test runs and class runs match the gate' % ('PASS' if ok else 'FAIL'))
wf = open(os.path.join(ROOT, '.github', 'workflows', 'helper-channel.yml'), encoding='utf-8').read()
# Runde 5: genau drei Geräteschritte (je ein frischer Emulator) mit genau den gegateten Befehlen, in dieser Reihenfolge
emu = wf[wf.index('\n  emulator:\n'):]
steps = re.split(r'\n      - ', emu)
dev = [st for st in steps if 'android-emulator-runner@' in st]
want_ids = ['dev_' + p for p in device_gate.PHASES]
got_ids = [re.search(r'\n        id: (\S+)', st).group(1) if re.search(r'\n        id: (\S+)', st) else None for st in dev]
scripts = [re.findall(r'\n          script: (.*)', st) for st in dev]
want_scripts = [[device_gate.DEVICE_COMMANDS[p][len('[command]/usr/bin/sh -c '):]] for p in device_gate.PHASES]
ok = got_ids == want_ids and scripts == want_scripts
fails += 0 if ok else 1
print('%s helper-channel.yml: exactly three emulator steps %s running exactly the gated commands' % ('PASS' if ok else 'FAIL', got_ids))
ok = all(st.count('-wipe-data') == 1 and '-no-snapshot' in st for st in dev) and len(dev) == 3
fails += 0 if ok else 1
print('%s helper-channel.yml: every device phase starts a fresh emulator (-wipe-data, -no-snapshot)' % ('PASS' if ok else 'FAIL'))
conds = [re.findall(r'\n        if: (.*)', st) for st in dev]
ok = conds == [[], ["steps.dev_mutant.outcome == 'success'"],
               ["${{ !cancelled() && steps.dev_mutant.outcome == 'success' && (steps.dev_main.outcome == 'success' || steps.dev_main.outcome == 'failure') }}"]]
fails += 0 if ok else 1
print('%s helper-channel.yml: main only after a proven mutant, repeats also after a red main, never after cancel/red mutant %s' % ('PASS' if ok else 'FAIL', conds))
ok = not re.search(r'^\s*continue-on-error:', emu, re.M) and device_gate.LEGACY_DEVICE_COMMAND[len('[command]/usr/bin/sh -c '):] not in wf
fails += 0 if ok else 1
print('%s helper-channel.yml: no continue-on-error in the emulator job, legacy single-emulator command gone' % ('PASS' if ok else 'FAIL'))

# Optional: echte, vollständige Job-Logs (Verzeichnis mit <name>-api<N>.log und EXPECTED-Zeilen "<datei> <api> <urteil>")
if len(sys.argv) > 1:
    real = sys.argv[1]
    for line in open(os.path.join(real, 'EXPECTED')).read().splitlines():
        if not line.strip():
            continue
        f, api, want = line.split()
        got, reasons = device_gate.evaluate(open(os.path.join(real, f), encoding='utf-8', errors='replace').read(), int(api))
        ok = got == want
        fails += 0 if ok else 1
        print('%s real %-28s api=%s want=%-18s got=%s%s' % ('PASS' if ok else 'FAIL', f, api, want, got, '' if ok else '  ' + '; '.join(reasons[:3])))

print('RESULT device-gate-selftest %s %d cases (%d failed)' % ('PASS' if fails == 0 else 'FAIL', len(cases), fails))
sys.exit(1 if fails else 0)
