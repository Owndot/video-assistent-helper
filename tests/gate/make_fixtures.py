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
# Wortlaut wie in den echten Läufen (API 26/29/34): Ursache ist allein die absichtlich manipulierte Markierungsbereinigung
MUTANT_CAUSE = 'cause: IOException: Cannot remove pending download ownership; last abort: ; store: ; downloadsInitialized: false; now=27187ms; cleanups: []'


def mutant_stack(cause=MUTANT_CAUSE):
    return ['org.junit.ComparisonFailure: published file is successful despite marker failure (%s) expected:<[don]e> but was:<[failed:storag]e>' % cause,
            '\tat org.junit.Assert.assertEquals(Assert.java:117)',
            '\tat de.evp.helfer.Mp4DownloadsInstrumentedTest.publishedFileSurvivesMarkerFailureAndRestart(Mp4DownloadsInstrumentedTest.java:932)']


MUTANT_STACK = mutant_stack()
ROWS0_CAUSE = ('cause: IOException: publish rows=0 proof=present cleanup=not-observed trail=insert#1+3ms,update=0#4+5ms; last abort: deleted:1; '
               'store: ; downloadsInitialized: false; now=27187ms; cleanups: []')


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


def shell_copy(main, appended):
    """Was tools/run-channel-instrumentation.sh außerhalb der Abschnitte aus dem Hauptbericht ausgibt (tail -5 summary.txt,
    FAILED-Auszüge, Runner-Ergebnis, Fehlerliste) - wie die Shell, unabhängig vom Gate nachgebildet."""
    keys = ('INSTRUMENTATION_STATUS: test=', 'INSTRUMENTATION_STATUS: class=', 'INSTRUMENTATION_STATUS_CODE', 'OK (', 'FAILURES', 'Tests run',
            'AssertionError', 'Exception')
    summary = [l for l in main if any((l.startswith(k) if k == 'OK (' else k in l) for k in keys)] + list(appended)
    out = summary[-5:]
    t, n, failed = '', 0, []
    for l in main:
        if l.startswith('INSTRUMENTATION_STATUS: test='):
            t = l
        if l.startswith('INSTRUMENTATION_STATUS: stack='):
            failed.append('FAILED ' + t)
            n = 14
        if n > 0:
            failed.append(l)
            n -= 1
    out += failed[:150]
    out += [l for l in main if any(k in l for k in ('INSTRUMENTATION_RESULT', 'INSTRUMENTATION_FAILED', 'shortMsg', 'longMsg', 'Process crashed'))][:20]
    return out


def section(name, body):
    return ['GATE-BEGIN ' + name] + body + ['GATE-END ' + name]


def probe_lines(api, cycles=None, drop=None, dup=None, race=None, samename_twice=False):
    cycles = dict(G.PROBE_CYCLES, **(cycles or {}))
    L = ['PUBLISH_PROBE_ATTRIBUTION sdk=%d foreignDelete: IOException: publish threw=IllegalStateException(No item at content://media/external/downloads/39) proof=present cleanup=not-observed trail=insert#1+7ms' % api,
         'PUBLISH_PROBE_ATTRIBUTION sdk=%d duringCleanup: IOException: publish threw=IllegalStateException(No item at content://media/external/downloads/40) proof=present cleanup=overlap-observed(started=0,active=1,activeAtInsert=1)' % api,
         'PUBLISH_PROBE_SIGNATURE sdk=%d cleanupDuringJob: proofAfterCleanup=false stateAfterCleanup={rows=0} write=ok commit=IOException: publish threw=IllegalStateException(No item at content://media/external/downloads/41) proof=gone' % api,
        ] + ['PUBLISH_RACE sdk=%d count=%d outcome=%s' % (api, c, o) for c, o in (race if race is not None else [(30, 'published-then-deleted')])]
    for v, (c, ok, f) in [(v, n if isinstance(n, tuple) else (n, n, 0)) for v, n in cycles.items()]:
        if v != drop:
            L += ['PUBLISH_PROBE sdk=%d variant=%s cycles=%d ok=%d failed=%d maxCommitMs=21' % (api, v, c, ok, f)] * (2 if v == dup else 1)
    L += ['PUBLISH_PROBE_SAMENAME sdk=%d second=published name=<title>.mp4' % api] * (2 if samename_twice else 1)
    return L


def job(api=34, main=None, probe=None, plines=None, probe_status=None, restarts=None, crash=(), heap=512, heap_line=True,
        repeat=None, repeat_reports=(), mutant=None, mutant_pass=True, command_n=G.REPEAT_N, gate_api=None, extra=(),
        errors=None, script_status=None, end=True, truncate_at=None, move_repeat_before_status=False, unknown_section=False,
        old_repeat_format=False, repeat_header_api=None, drop_repeat=None, dup_repeat=None, raw_extra=(),
        persist=True, repeat_format=G.REPEAT_FORMAT, repeat_crash=(), drop_repeat_crash=False, repeat_extra_header=None):
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
    # Kopien des Hauptberichts außerhalb der Abschnitte, wie sie die Shell ausgibt
    appended = [l for l in extra if l == 'LISTENER ON 47811'] + (['PERSIST_AFTER_UNINSTALL ok: C2 Persist %d.mp4 (65536 bytes)' % api] if persist
                else ['PERSIST_AFTER_UNINSTALL missing']) + [l for l in extra if l == 'package list changed']
    L += shell_copy(main, appended)
    L += list(extra) + list(raw_extra)
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
    # Wiederholungen (Format 2): JEDER Lauf als Bericht mit Kennung und API; repeat_reports überschreibt einzelne Läufe.
    overrides = dict(repeat_reports)
    failed_repeats = 0
    rep_lines = [] if old_repeat_format else [repeat_format]
    labels = ['three-%d' % i for i in range(1, G.REPEAT_N + 1)] + ['class-%d' % i for i in range(1, G.REPEAT_CLASS_RUNS + 1)]
    for label in labels + ([dup_repeat] if dup_repeat else []):
        if label == drop_repeat:
            continue
        body = overrides.get(label) or instr(THREE if label.startswith('three-') else CLASS_REPEAT)
        failed = 'FAILURES!!!' in body or not any(l.startswith('OK (') for l in body)
        failed_repeats += 1 if failed and label != dup_repeat else 0
        if old_repeat_format:
            if failed:
                rep_lines += section('repeat-' + label, body)
            continue
        head = ['REPEAT-RUN label=%s api=%d' % (label, repeat_header_api or api)]
        if repeat_extra_header and label == repeat_extra_header[0]:
            head.append(repeat_extra_header[1])
        rep_lines += section('repeat-' + label, head + body)
    if not old_repeat_format and repeat_format == G.REPEAT_FORMAT and not drop_repeat_crash:
        rep_lines += section(G.REPEAT_CRASH, list(repeat_crash))
    sst = script_status if script_status is not None else (1 if main_failed or status == 'failed' or 'fail' in restarts.values() else 0)
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
    # Codex Release-Readiness 2: konkrete Gegenbeispiele (müssen alle REJECT sein)
    'mutant_without_end_code_api34': (34, 'REJECT', job(34, mutant=instr({MP4: [G.MUTANT_TEST]}, {G.MUTANT_TEST: MUTANT_STACK}, finish=False))),
    'mutant_wrong_reason_api34': (34, 'REJECT', job(34, mutant=instr({MP4: [G.MUTANT_TEST]}, {G.MUTANT_TEST: OTHER_STACK}))),
    'extra_publish_rows0_outside_sections_api34': (34, 'REJECT', job(34, raw_extra=[
        '11-17 13:37:20.581  4597  5046 I MP4_TEST: MP4_STORE_FAILURE sdk=34 commit java.io.IOException: publish rows=0 proof=present cleanup=not-observed'])),
    'size_plus_publish_outside_sections_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, raw_extra=[
        '11-17 13:21:18.900  5826  5845 I MP4_TEST: MP4_STORE_FAILURE sdk=29 commit java.io.IOException: publish rows=0 proof=present cleanup=not-observed'])),
    'extra_incomplete_block_outside_sections_api34': (34, 'REJECT', job(34, raw_extra=[
        'INSTRUMENTATION_STATUS: class=de.evp.helfer.Mp4DownloadsInstrumentedTest', 'INSTRUMENTATION_STATUS: test=hiddenExtraTest',
        'INSTRUMENTATION_STATUS_CODE: 1'])),
    'extra_failure_copy_contradicts_main_api34': (34, 'REJECT', job(34, raw_extra=[
        'FAILED INSTRUMENTATION_STATUS: test=cancelRemovesEverything', 'INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: x',
        'INSTRUMENTATION_STATUS_CODE: -2'])),
    'extra_incomplete_block_in_main_api34': (34, 'REJECT', job(34, main=instr(MAIN) + [
        'INSTRUMENTATION_STATUS: class=de.evp.helfer.ChannelInstrumentedTest', 'INSTRUMENTATION_STATUS: test=pairingAndControlCommands'])),
    'second_run_in_main_section_api34': (34, 'REJECT', job(34, main=instr(MAIN) + instr({MP4: ['cancelRemovesEverything']}))),
    'app_fatal_outside_crash_section_api34': (34, 'REJECT', job(34, raw_extra=['11-17 13:21:19.606  2042  2042 E AndroidRuntime: FATAL EXCEPTION: main'])),
    'app_fatal_in_restart_area_api34': (34, 'REJECT', job(34, extra=['11-17 13:21:19.606  6000  6000 E AndroidRuntime: Process: de.evp.helfer.channeltest, PID: 6000',
                                                                     '11-17 13:21:19.606  6000  6000 E AndroidRuntime: FATAL EXCEPTION: evp-dl'])),
    'oom_outside_sections_api34': (34, 'REJECT', job(34, raw_extra=['11-17 13:21:19.341  1876  6827 E AndroidRuntime: java.lang.OutOfMemoryError: Failed to allocate'])),
    # Wiederholungen Format 2: jeder der 22 Läufe muss belegt und korrekt sein
    'repeat_v2_wrong_api_header_api34': (34, 'REJECT', job(34, repeat_header_api=29)),
    'repeat_v2_missing_run_api34': (34, 'REJECT', job(34, drop_repeat='three-13')),
    'repeat_v2_duplicate_label_api34': (34, 'REJECT', job(34, dup_repeat='three-4')),
    'repeat_v2_ok_zero_tests_api34': (34, 'REJECT', job(34, repeat_reports=[('three-2', ['INSTRUMENTATION_RESULT: stream=', '', 'Time: 0.01', 'OK (0 tests)', '', '', 'INSTRUMENTATION_CODE: -1'])])),
    'repeat_v2_skipped_test_api34': (34, 'REJECT', job(34, repeat_reports=[('three-9', instr(THREE, codes={'cancelRemovesEverything': '-3'}))])),
    'repeat_v2_assumption_skip_api26': (26, 'REJECT', job(26, repeat_reports=[('class-2', instr(CLASS_REPEAT, codes={'storageWritableAfterRuntimeGrant': '-4'}))])),
    'repeat_v2_class_wrong_set_api34': (34, 'REJECT', job(34, repeat_reports=[('class-1', instr({MP4: MAIN[MP4]}))])),
    'repeat_v2_three_wrong_set_api34': (34, 'REJECT', job(34, repeat_reports=[('three-1', instr({MP4: G.REPEAT_THREE[:2]}))])),
    'repeat_v2_summary_mismatch_api34': (34, 'REJECT', job(34, repeat='REPEAT api=34 runs=22 passed=22 failed=0',
                                                           repeat_reports=[('three-5', instr(THREE, {'cancelRemovesEverything': OTHER_STACK}))], errors=[G.SIZE_ERROR])),
    'repeat_v2_unfinished_run_api34': (34, 'REJECT', job(34, repeat_reports=[('three-6', instr(THREE, finish=False))])),
    # Codex NO-GO 3 (P1): Mutantennachweis nur bei erfolgreicher Veröffentlichung + manipulierter Markierungsbereinigung
    'mutant_publish_rows0_api34': (34, 'REJECT', job(34, mutant=instr({MP4: [G.MUTANT_TEST]}, {G.MUTANT_TEST: mutant_stack(ROWS0_CAUSE)}))),
    'mutant_publish_threw_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, mutant=instr({MP4: [G.MUTANT_TEST]}, {G.MUTANT_TEST: mutant_stack(
        ROWS0_CAUSE.replace('publish rows=0', 'publish threw=IllegalStateException(No item at content://media/external/downloads/38)'))}))),
    'mutant_abort_after_publish_api34': (34, 'REJECT', job(34, mutant=instr({MP4: [G.MUTANT_TEST]}, {G.MUTANT_TEST: mutant_stack(
        MUTANT_CAUSE.replace('last abort: ;', 'last abort: deleted:1;'))}))),
    'mutant_store_failure_line_in_region_api34': (34, 'REJECT', job(34, mutant=instr({MP4: [G.MUTANT_TEST]}, {G.MUTANT_TEST: MUTANT_STACK})[:-4] + [
        '11-17 19:30:00.000  4000  4001 I MP4_TEST: MP4_STORE_FAILURE sdk=34 commit java.io.IOException: publish rows=0 proof=present']
        + instr({MP4: [G.MUTANT_TEST]}, {G.MUTANT_TEST: MUTANT_STACK})[-4:])),
    # Codex NO-GO 3 (P2): weitere Lücken des Geräte-Gates
    'raw_incomplete_block_without_code_api34': (34, 'REJECT', job(34, raw_extra=[
        'INSTRUMENTATION_STATUS: class=de.evp.helfer.ChannelInstrumentedTest', 'INSTRUMENTATION_STATUS: test=pairingAndControlCommands'])),
    'raw_contradicting_minus4_api34': (34, 'REJECT', job(34, raw_extra=[
        'INSTRUMENTATION_STATUS: class=de.evp.helfer.Mp4DownloadsInstrumentedTest', 'INSTRUMENTATION_STATUS: test=cancelRemovesEverything',
        'INSTRUMENTATION_STATUS_CODE: -4'])),
    'repeat_second_header_api34': (34, 'REJECT', job(34, repeat_extra_header=('three-5', 'REPEAT-RUN label=class-9 api=29'))),
    'publish_race_count_zero_api34': (34, 'REJECT', job(34, plines=probe_lines(34, race=[(0, 'published-then-deleted')]))),
    'publish_race_sum_mismatch_api29': (29, 'REJECT', job(29, main=SIZE_MAIN, plines=probe_lines(29, race=[(29, 'published-then-deleted')]))),
    'publish_race_missing_api34': (34, 'REJECT', job(34, plines=probe_lines(34, race=[]))),
    'publish_samename_twice_api34': (34, 'REJECT', job(34, plines=probe_lines(34, samename_twice=True))),
    'crash_after_repeats_api34': (34, 'REJECT', job(34, repeat_crash=['--------- beginning of crash',
                                                                     '11-17 19:50:01.000  7001  7020 E AndroidRuntime: Process: de.evp.helfer.channeltest, PID: 7001'])),
    'repeat_crash_report_missing_api34': (34, 'REJECT', job(34, drop_repeat_crash=True)),
    'repeat_format2_without_crash_report_api34': (34, 'INSUFFICIENT_EVIDENCE', job(34, repeat_format='REPEAT-FORMAT 2')),
    'repeat_format2_size_api29': (29, 'INSUFFICIENT_EVIDENCE', job(29, main=SIZE_MAIN, repeat_format='REPEAT-FORMAT 2', repeat_reports=SIZE_CLASS)),
    # altes Wiederholungsformat: nie PASS (grün = unzureichend, mit Fehler = REJECT)
    'repeat_old_format_green_api34': (34, 'INSUFFICIENT_EVIDENCE', job(34, old_repeat_format=True)),
    'repeat_old_format_size_api29': (29, 'INSUFFICIENT_EVIDENCE', job(29, main=SIZE_MAIN, old_repeat_format=True, repeat_reports=SIZE_CLASS)),
    'repeat_old_format_failure_api34': (34, 'REJECT', job(34, old_repeat_format=True, repeat_reports=[('three-3', instr(THREE, {'duplicateAndLateStartsFetchOnce': PUBLISH_STACK}))])),
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
