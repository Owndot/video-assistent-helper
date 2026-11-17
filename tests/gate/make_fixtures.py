#!/usr/bin/env python3
"""Synthetische Job-Logs für tools/device_gate.py (Review P4, Codex Release-Readiness P2).

Format wie die echten Läufe von helper-channel.yml (am instrument -r, Zeitstempel des Actions-Logs, Gerätebefehl,
Mutantenprobe, Markierungen von tools/run-channel-instrumentation.sh und tools/run-flaky-repeat.sh, Job-Ende).
Die erwarteten Testmengen kommen aus tools/device_gate.py; tests/gate/test_device_gate.py prüft diese ihrerseits gegen
den Quellstand der Gerätetests. Jede Datei: tests/gate/fixtures/<name>.log; erwartetes Urteil in fixtures/EXPECTED.
Aufruf: python3 -I tests/gate/make_fixtures.py
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', '..', 'tools'))
import device_gate as G  # noqa: E402

OUT = os.path.join(HERE, 'fixtures')
MAIN = G.EXPECTED_MAIN
MP4 = G.MP4
CLASS_REPEAT = {MP4: [t for t in MAIN[MP4] if t != G.REPEAT_CLASS_EXCLUDED]}
THREE = {MP4: G.REPEAT_THREE}
SIZE = G.SIZE_TEST
SIZE_STACK = ['java.lang.AssertionError: SIZE column (file /storage/emulated/0/Download/Video-Assistent/C2 Publish 1.mp4, length 67108864) expected:<67108864> but was:<0>',
              '\tat org.junit.Assert.fail(Assert.java:89)',
              '\tat de.evp.helfer.Mp4DownloadsInstrumentedTest.publishesThroughMediaStoreAndReadsBack(Mp4DownloadsInstrumentedTest.java:470)']
PUBLISH_STACK = ['org.junit.ComparisonFailure: publish result (cause: IOException: publish rows=0 proof=present cleanup=not-observed trail=insert#19+1ms,open#20+3ms,close#21+384ms,update=0#22+387ms uri=content://media/external/downloads/18) expected:<[don]e> but was:<[failed:storag]e>',
                 '\tat org.junit.Assert.assertEquals(Assert.java:117)']
OTHER_STACK = ['java.lang.AssertionError: channel answer', '\tat de.evp.helfer.ChannelInstrumentedTest.pairingAndControlCommands(ChannelInstrumentedTest.java:88)']
MUTANT_STACK = ['org.junit.ComparisonFailure: published file is successful despite marker failure (cause: IOException: Cannot remove pending download ownership) expected:<[don]e> but was:<[failed:storag]e>',
                '\tat de.evp.helfer.Mp4DownloadsInstrumentedTest.publishedFileSurvivesMarkerFailureAndRestart(Mp4DownloadsInstrumentedTest.java:932)']


def instr(classes, fails=None, codes=None, crash=None, finish=True, claim=None, dup=None):
    """am instrument -r Ausgabe. fails: {Test: Stack} (-2); codes: {Test: Endcode}; claim: abweichende Ergebniszahl."""
    fails, codes = fails or {}, codes or {}
    out, n, nf = [], 0, 0
    for cls, tests in classes.items():
        for t in tests + ([dup] if dup in tests else []):
            n += 1
            out += ['INSTRUMENTATION_STATUS: class=' + cls, 'INSTRUMENTATION_STATUS: current=%d' % n, 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner',
                    'INSTRUMENTATION_STATUS: numtests=%d' % sum(len(v) for v in classes.values()), 'INSTRUMENTATION_STATUS: stream=',
                    'INSTRUMENTATION_STATUS: test=' + t, 'INSTRUMENTATION_STATUS_CODE: 1']
            out += ['INSTRUMENTATION_STATUS: class=' + cls, 'INSTRUMENTATION_STATUS: current=%d' % n, 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner']
            if crash == t:
                out += ['INSTRUMENTATION_STATUS: test=' + t, 'INSTRUMENTATION_STATUS: stack=java.lang.RuntimeException: android.os.DeadSystemException',
                        'INSTRUMENTATION_STATUS_CODE: -2', 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.', 'INSTRUMENTATION_CODE: 0']
                return out
            if t in fails:
                nf += 1
                out += ['INSTRUMENTATION_STATUS: stack=' + fails[t][0]] + fails[t][1:] + ['', 'INSTRUMENTATION_STATUS: stream=',
                        'INSTRUMENTATION_STATUS: test=' + t, 'INSTRUMENTATION_STATUS_CODE: -2']
            else:
                out += ['INSTRUMENTATION_STATUS: stream=.', 'INSTRUMENTATION_STATUS: test=' + t, 'INSTRUMENTATION_STATUS_CODE: %s' % codes.get(t, '0')]
    out += ['INSTRUMENTATION_RESULT: stream=', '', 'Time: 312.5']
    shown = claim if claim is not None else n
    out += ['OK (%d test%s)' % (shown, '' if shown == 1 else 's')] if nf == 0 else ['FAILURES!!!', 'Tests run: %d,  Failures: %d' % (shown, nf)]
    if finish:
        out += ['', '', 'INSTRUMENTATION_CODE: -1']
    return out


def section(name, body):
    return ['GATE-BEGIN ' + name] + body + ['GATE-END ' + name]


def probe_lines(api, cycles=None, drop=None, dup=None):
    cycles = dict(G.PROBE_CYCLES, **(cycles or {}))
    L = ['PUBLISH_PROBE_ATTRIBUTION sdk=%d foreignDelete: IOException: publish threw=IllegalStateException(No item at content://media/external/downloads/39) proof=present cleanup=not-observed trail=insert#1+7ms' % api,
         'PUBLISH_PROBE_ATTRIBUTION sdk=%d duringCleanup: IOException: publish threw=IllegalStateException(No item at content://media/external/downloads/40) proof=present cleanup=overlap-observed(started=0,active=1,activeAtInsert=1)' % api,
         'PUBLISH_PROBE_SIGNATURE sdk=%d cleanupDuringJob: proofAfterCleanup=false stateAfterCleanup={rows=0} write=ok commit=IOException: publish threw=IllegalStateException(No item at content://media/external/downloads/41) proof=gone' % api,
         'PUBLISH_RACE sdk=%d count=30 outcome=published-then-deleted' % api]
    for v, (c, ok, f) in [(v, n if isinstance(n, tuple) else (n, n, 0)) for v, n in cycles.items()]:
        if v != drop:
            L += ['PUBLISH_PROBE sdk=%d variant=%s cycles=%d ok=%d failed=%d maxCommitMs=21' % (api, v, c, ok, f)] * (2 if v == dup else 1)
    L += ['PUBLISH_PROBE_SAMENAME sdk=%d second=published name=<title>.mp4' % api]
    return L


def job(api=34, main=None, probe=None, plines=None, probe_status=None, restarts=None, crash=(), heap=512, heap_line=True,
        repeat=None, repeat_reports=(), mutant=None, mutant_pass=True, command_n=G.REPEAT_N, gate_api=None, extra=(),
        errors=None, script_status=None, end=True, truncate_at=None, move_repeat_before_status=False, unknown_section=False,
        persist=True):
    main = instr(MAIN) if main is None else main
    main_failed = 'FAILURES!!!' in main
    L = ['Current runner version: \'2.338.2\'', '##[group]Run reactivecircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d',
         G.DEVICE_COMMAND.replace('flaky-repeat.sh "$RUNNER_TEMP/a" %d' % G.REPEAT_N, 'flaky-repeat.sh "$RUNNER_TEMP/a" %d' % command_n),
         'DEVICE_READY after 11 s']
    L += mutant if mutant is not None else instr({MP4: [G.MUTANT_TEST]}, {G.MUTANT_TEST: MUTANT_STACK})
    if mutant_pass:
        L += [G.MUTANT_PASS]
    L += ['DEVICE_PROP dalvik.vm.heapsize=512m']
    pl = plines if plines is not None else (probe_lines(api) if api >= 29 else [])
    L += pl
    status = probe_status if probe_status is not None else ('ok' if api >= 29 else 'skipped')
    if status != '-':
        L += ['PUBLISH_PROBE_STATUS ' + status]
    L += ['PERSIST_AFTER_UNINSTALL ok: C2 Persist %d.mp4 (65536 bytes)' % api] if persist else ['PERSIST_AFTER_UNINSTALL missing']
    rnames = list(G.RESTARTS)
    restarts = restarts if restarts is not None else {}
    rs = ['RESTART %s phase %s %s' % (r[len('restart-'):-2], r[-1], 'FAILED' if restarts.get(r) == 'fail' else 'OK') for r in rnames if restarts.get(r) != 'drop']
    L += rs
    if heap_line:
        L += ['11-17 10:39:38.062  6241  6260 I HLS_TEST: HLS_MEASURE api=%d samples=43126 ms=4475 points=41 retainedFixedKiB=0 retainedEndKiB=3409 bytesPerSample=42 maxHeapMiB=%d budgetSamples=1000000 budgetHeapMiB=40' % (api, heap)]
    L += list(extra)
    L += ['GATE-API %d' % (gate_api if gate_api is not None else api)]
    L += section('main', main)
    if api >= 29 and status != 'skipped':
        L += section('probe', probe if probe is not None else instr({G.PROBE_CLASS: G.PROBE_TESTS}))
        L += section('probe-lines', pl)
    for r in rnames:
        if restarts.get(r) == 'drop':
            continue
        test = G.RESTARTS[r] if restarts.get(r) != 'wrongtest' else 'phaseA_somethingElse'
        if restarts.get(r) == 'fail':
            L += section(r, instr({G.RESTART_CLASS: [test]}, {test: OTHER_STACK}))
        else:
            L += section(r, instr({G.RESTART_CLASS: [test]}))
    L += section('restart-summary', rs)
    L += section('crash', list(crash))
    if unknown_section:
        L += section('debug-extra', ['nothing'])
    failed_repeats = len(repeat_reports)
    sst = script_status if script_status is not None else (1 if main_failed or status == 'failed' or 'fail' in restarts.values() else 0)
    rep_lines = []
    for label, body in repeat_reports:
        rep_lines += section('repeat-' + label, body)
    if move_repeat_before_status:
        L += rep_lines + ['GATE-SCRIPT-STATUS %d' % sst]
    else:
        L += ['GATE-SCRIPT-STATUS %d' % sst] + rep_lines
    if repeat is None:
        repeat = 'REPEAT api=%d runs=%d passed=%d failed=%d' % (api, G.REPEAT_RUNS, G.REPEAT_RUNS - failed_repeats, failed_repeats)
    if repeat:
        L += [repeat]
    if errors is None:
        errors = [G.SIZE_ERROR] if (sst != 0 or failed_repeats) else []
    L += errors
    L += ['##[group]Terminate Emulator', 'OK: killing emulator, bye bye', '##[endgroup]', 'Post job cleanup.']
    if end:
        L += [G.JOB_END]
    if truncate_at:
        L = L[:L.index(truncate_at)]
    return '\n'.join('2026-11-17T10:%02d:%02d.%07dZ %s' % (i // 60 % 60, i % 60, i, l) for i, l in enumerate(L)) + '\n'


SIZE_MAIN = instr(MAIN, {SIZE: SIZE_STACK})
SIZE_CLASS = [(l, instr(CLASS_REPEAT, {SIZE: SIZE_STACK})) for l in ('class-1', 'class-2')]
MAIN_MINUS_ONE = {c: [t for t in ts if t != 'nothingListensOnTheLegacyPort'] for c, ts in MAIN.items()}
MAIN_PLUS_ONE = {c: ts + (['debugOnlyTest'] if c.endswith('ChannelInstrumentedTest') else []) for c, ts in MAIN.items()}

CASES = {
    # erlaubt
    'pass_api34': (34, 'PASS', job(34)),
    'pass_api26_no_probe': (26, 'PASS', job(26)),
    'size_only_api29': (29, 'ACCEPTED_SIZE_ONLY', job(29, main=SIZE_MAIN)),
    'size_only_with_class_repeats_api29': (29, 'ACCEPTED_SIZE_ONLY', job(29, main=SIZE_MAIN, repeat_reports=SIZE_CLASS)),
    # Veröffentlichungssonden: positive Zyklenzahl, Vollständigkeit, Eindeutigkeit
    'probe_cycles_zero_api34': (34, 'REJECT', job(34, plines=probe_lines(34, {'PAUSE_MID_WRITE': 0}))),
    'probe_cycles_all_zero_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, plines=probe_lines(29, {v: 0 for v in G.PROBE_CYCLES}))),
    'probe_cycles_too_few_api34': (34, 'REJECT', job(34, plines=probe_lines(34, {'PLAIN': 5}))),
    'probe_variant_failed_api34': (34, 'REJECT', job(34, plines=probe_lines(34, {'PLAIN': (60, 59, 1)}))),
    'probe_variant_missing_api34': (34, 'REJECT', job(34, plines=probe_lines(34, drop='CONCURRENT_PROVIDER_LOAD'))),
    'probe_variant_twice_api34': (34, 'REJECT', job(34, plines=probe_lines(34, dup='PLAIN'))),
    'probe_lines_other_api_api34': (34, 'REJECT', job(34, plines=probe_lines(29))),
    'probe_test_missing_api34': (34, 'REJECT', job(34, probe=instr({G.PROBE_CLASS: G.PROBE_TESTS[:4]}))),
    'probe_status_failed_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, probe_status='failed')),
    'probe_status_missing_api34': (34, 'REJECT', job(34, probe_status='-')),
    'probe_skipped_on_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, probe_status='skipped', plines=[])),
    'probe_output_on_api26': (26, 'REJECT', job(26, plines=probe_lines(26))),
    # Haupttest: feste Testmenge, konsistente Berichte
    'main_test_missing_api34': (34, 'REJECT', job(34, main=instr(MAIN_MINUS_ONE))),
    'main_unexpected_test_api34': (34, 'REJECT', job(34, main=instr(MAIN_PLUS_ONE))),
    'main_count_mismatch_api34': (34, 'REJECT', job(34, main=instr(MAIN, claim=47))),
    'main_test_twice_api34': (34, 'REJECT', job(34, main=instr(MAIN, dup='cancelRemovesEverything'))),
    'main_test_ignored_api34': (34, 'REJECT', job(34, main=instr(MAIN, codes={'parallelMp4AndHls': '-3'}))),
    'main_assumption_skip_api26': (26, 'REJECT', job(26, main=instr(MAIN, codes={'storageWritableAfterRuntimeGrant': '-4'}))),
    'main_not_finished_api34': (34, 'REJECT', job(34, main=instr(MAIN, finish=False))),
    'size_on_api34_rejected': (34, 'REJECT', job(34, main=SIZE_MAIN)),
    'publish_rows0_api29': (29, 'REJECT', job(29, main=instr(MAIN, {SIZE: PUBLISH_STACK}))),
    'size_plus_publish_api29': (29, 'REJECT', job(29, main=instr(MAIN, {SIZE: SIZE_STACK, 'publicFileForUninstallCheck': PUBLISH_STACK}))),
    'size_plus_unknown_api29': (29, 'REJECT', job(29, main=instr(MAIN, {SIZE: SIZE_STACK, 'pairingAndControlCommands': OTHER_STACK}))),
    'unknown_failure_api34': (34, 'REJECT', job(34, main=instr(MAIN, {'pairingAndControlCommands': OTHER_STACK}))),
    # API-Zuordnung
    'wrong_api_asked_api29': (29, 'REJECT', job(34)),
    'gate_api_mismatch_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, gate_api=34)),
    'repeat_other_api_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, repeat='REPEAT api=34 runs=22 passed=22 failed=0', errors=[G.SIZE_ERROR])),
    'ungated_api_api30': (30, 'REJECT', job(30)),
    # Wiederholungen: Umfang und Testarten
    'repeat_too_few_runs_api34': (34, 'REJECT', job(34, repeat='REPEAT api=34 runs=12 passed=12 failed=0')),
    'repeat_inconsistent_api34': (34, 'REJECT', job(34, repeat='REPEAT api=34 runs=22 passed=22 failed=1')),
    'repeat_command_n5_api34': (34, 'REJECT', job(34, command_n=5)),
    'repeat_missing_api34': (34, 'REJECT', job(34, repeat='')),
    'repeat_failed_api34': (34, 'REJECT', job(34, repeat='REPEAT api=34 runs=22 passed=21 failed=1', errors=[G.SIZE_ERROR])),
    'repeat_publish_failure_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, repeat_reports=[SIZE_CLASS[0], ('class-2', instr(CLASS_REPEAT, {SIZE: PUBLISH_STACK}))])),
    'repeat_without_evidence_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, repeat='REPEAT api=29 runs=22 passed=20 failed=2')),
    'repeat_size_but_main_green_api29': (29, 'REJECT', job(29, repeat_reports=SIZE_CLASS[:1], script_status=0)),
    'repeat_three_failed_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, repeat_reports=[('three-7', instr(THREE, {'duplicateAndLateStartsFetchOnce': OTHER_STACK}))])),
    'repeat_class_incomplete_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, repeat_reports=[('class-1', instr({MP4: CLASS_REPEAT[MP4][:5]}, {SIZE: SIZE_STACK}))])),
    'repeat_class_includes_excluded_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, repeat_reports=[('class-1', instr(MAIN and {MP4: MAIN[MP4]}, {SIZE: SIZE_STACK}))])),
    'repeat_section_before_status_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, repeat_reports=SIZE_CLASS, move_repeat_before_status=True)),
    # Mutantenprobe getrennt und genau
    'regression_not_shown_api34': (34, 'REJECT', job(34, mutant_pass=False)),
    'mutant_wrong_test_api34': (34, 'REJECT', job(34, mutant=instr({MP4: ['cancelRemovesEverything']}, {'cancelRemovesEverything': MUTANT_STACK}))),
    'mutant_passed_api34': (34, 'REJECT', job(34, mutant=instr({MP4: [G.MUTANT_TEST]}))),
    # Neustart
    'restart_failed_api34': (34, 'REJECT', job(34, restarts={'restart-process-replaced-B': 'fail'})),
    'restart_missing_api34': (34, 'REJECT', job(34, restarts={'restart-force-stop-A': 'drop'})),
    'restart_wrong_test_api34': (34, 'REJECT', job(34, restarts={'restart-force-stop-A': 'wrongtest'})),
    # Abstürze, Umgebung, Nebenbedingungen
    'system_crash_api29': (29, 'REJECT', job(29, main=instr(MAIN, crash='cpuStaysAwakeOnlyWhileDownloading'),
                                             crash=['11-16 22:59:20.364  1904  6709 E AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS: Thread-123',
                                                    '11-16 22:59:20.364  1904  6709 E AndroidRuntime: java.lang.OutOfMemoryError: Failed to allocate a 4194320 byte allocation'])),
    'system_crash_outside_sections_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, extra=['11-16 22:59:20.364  1904  6709 E AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS: android.bg'])),
    'small_app_heap_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, heap=16)),
    'heap_evidence_missing_api34': (34, 'REJECT', job(34, heap_line=False)),
    'app_crash_in_buffer_api34': (34, 'REJECT', job(34, crash=['11-16 22:59:20.392  5758  6712 E AndroidRuntime: FATAL EXCEPTION: evp-mp4'])),
    'legacy_port_open_api34': (34, 'REJECT', job(34, extra=['LISTENER ON 47811'])),
    'package_list_changed_api34': (34, 'REJECT', job(34, extra=['package list changed'])),
    'persist_missing_api34': (34, 'REJECT', job(34, persist=False)),
    'unknown_job_error_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, errors=[G.SIZE_ERROR, '##[error]Process completed with exit code 137.'])),
    'unknown_section_api34': (34, 'REJECT', job(34, unknown_section=True)),
    # unvollständige Logs
    'truncated_log_api34': (34, 'REJECT', job(34, truncate_at='GATE-END main')),
    'job_log_without_end_api34': (34, 'REJECT', job(34, end=False)),
    'old_script_without_sections_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, gate_api=-1).replace('GATE-API -1', 'nothing')),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        if f.endswith('.log'):
            os.remove(os.path.join(OUT, f))
    exp = []
    for name, (api, verdict, log) in sorted(CASES.items()):
        open(os.path.join(OUT, name + '.log'), 'w', encoding='utf-8').write(log)
        exp.append('%s %d %s' % (name, api, verdict))
    open(os.path.join(OUT, 'EXPECTED'), 'w').write('\n'.join(exp) + '\n')
    print('wrote %d fixtures' % len(exp))


if __name__ == '__main__':
    main()
