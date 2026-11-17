#!/usr/bin/env python3
"""Synthetische Job-Logs für tools/device_gate.py (Review P4). Format wie die echten Läufe von helper-channel.yml
(am instrument -r, Zeitstempel des Actions-Logs, Markierungen von tools/run-channel-instrumentation.sh).
Jede Datei: tests/gate/fixtures/<name>.log; erwartetes Urteil in tests/gate/fixtures/EXPECTED.
Aufruf: python3 -I tests/gate/make_fixtures.py"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'fixtures')
CLASSES = {
    'de.evp.helfer.ChannelInstrumentedTest': ['channelRoundTrip', 'rogueBind'],
    'de.evp.helfer.net.SafeHttpInstrumentedTest': ['blocksPrivate', 'streams64MiB'],
    'de.evp.helfer.Mp4DownloadsInstrumentedTest': ['publishesThroughMediaStoreAndReadsBack', 'publicFileForUninstallCheck',
                                                  'cpuStaysAwakeOnlyWhileDownloading', 'crashConsistencyBetweenJournalAndStorage'],
    'de.evp.helfer.HlsDownloadsInstrumentedTest': ['remuxesTs', 'remuxesFmp4'],
}
MP4 = CLASSES['de.evp.helfer.Mp4DownloadsInstrumentedTest']
PROBES = ['repeatedPublicationsOfTheAppStore', 'processStartCleanupDuringAJobSignature', 'cleanupAttributionIsConservative',
          'concurrentDeletionDuringPublishRace']
VARIANTS = ['PLAIN', 'PAUSE_MID_WRITE', 'PAUSE_BEFORE_CLOSE', 'PAUSE_BEFORE_COMMIT', 'ABORTED_SIBLING_THEN_PAUSE_MID_WRITE',
            'LARGE_DELETE_BEFORE_COMMIT', 'CONCURRENT_PROVIDER_LOAD']
SIZE_STACK = ['INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: SIZE column (file /storage/emulated/0/Download/Video-Assistent/C2 Publish 1.mp4, length 67108864) expected:<67108864> but was:<0>',
              '\tat org.junit.Assert.fail(Assert.java:89)',
              '\tat de.evp.helfer.Mp4DownloadsInstrumentedTest.publishesThroughMediaStoreAndReadsBack(Mp4DownloadsInstrumentedTest.java:426)']
PUBLISH_STACK = ['INSTRUMENTATION_STATUS: stack=org.junit.ComparisonFailure: publish result (cause: IOException: publish rows=0 proof=present cleanup=not-observed trail=insert#19+1ms,open#20+3ms,close#21+384ms,update=0#22+387ms uri=content://media/external/downloads/18) expected:<[don]e> but was:<[failed:storag]e>',
                 '\tat org.junit.Assert.assertEquals(Assert.java:117)']
OTHER_STACK = ['INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: channel answer', '\tat de.evp.helfer.ChannelInstrumentedTest.channelRoundTrip(ChannelInstrumentedTest.java:88)']


def instr(classes, fails=None, crash=None, finish=True):
    fails = fails or {}
    out, n, nf = [], 0, 0
    for cls, tests in classes.items():
        for t in tests:
            n += 1
            out += ['INSTRUMENTATION_STATUS: class=' + cls, 'INSTRUMENTATION_STATUS: current=%d' % n, 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner',
                    'INSTRUMENTATION_STATUS: test=' + t, 'INSTRUMENTATION_STATUS_CODE: 1']
            out += ['INSTRUMENTATION_STATUS: class=' + cls, 'INSTRUMENTATION_STATUS: current=%d' % n, 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner']
            if crash == t:
                out += ['INSTRUMENTATION_STATUS: test=' + t, 'INSTRUMENTATION_STATUS: stack=java.lang.RuntimeException: android.os.DeadSystemException',
                        'INSTRUMENTATION_STATUS_CODE: -2', 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.', 'INSTRUMENTATION_CODE: 0']
                return out
            if t in fails:
                nf += 1
                out += fails[t] + ['INSTRUMENTATION_STATUS: test=' + t, 'INSTRUMENTATION_STATUS_CODE: -2']
            else:
                out += ['INSTRUMENTATION_STATUS: test=' + t, 'INSTRUMENTATION_STATUS_CODE: 0']
    out += ['INSTRUMENTATION_RESULT: stream=', '', 'Time: 312.5']
    out += ['OK (%d tests)' % n] if nf == 0 else ['FAILURES!!!', 'Tests run: %d,  Failures: %d' % (n, nf)]
    if finish:
        out += ['', 'INSTRUMENTATION_CODE: -1']
    return out


def section(name, body):
    return ['GATE-BEGIN ' + name] + body + ['GATE-END ' + name]


def job(api=34, main=None, probe_status='ok', probe=None, probe_lines=None, restart_fail=None, restart_drop=None, crash=(),
        repeat='passed=22 failed=0', repeat_reports=(), regression=True, script_status=None, drop_api=False, extra=(), truncate=False):
    main = instr(CLASSES) if main is None else main
    L = ['DEVICE_READY after 11 s']
    if regression:
        L += ['INSTRUMENTATION_STATUS: test=publishedFileSurvivesMarkerFailureAndRestart', 'INSTRUMENTATION_STATUS_CODE: -2',
              'FAILURES!!!', 'Tests run: 1,  Failures: 1',
              'PASS: previous cleanup fails the publication regression (failed:storage instead of done)']
    L += ['DEVICE_PROP dalvik.vm.heapsize=512m']
    if api >= 29:
        lines = probe_lines if probe_lines is not None else (
            ['PUBLISH_PROBE_SIGNATURE sdk=%d cleanupDuringJob: proofAfterCleanup=false commit=IOException: publish threw=IllegalStateException(No item at content://media/external/downloads/39) proof=gone cleanup=removed-this-entry(proof-match) trail=insert#1+7ms,open#2+11ms,close#5+20ms,update!#6+21ms' % api]
            + ['PUBLISH_PROBE_ATTRIBUTION sdk=%d foreignDelete: IOException: publish threw=IllegalStateException(No item at content://media/external/downloads/40) proof=present cleanup=not-observed' % api,
               'PUBLISH_PROBE_ATTRIBUTION sdk=%d duringCleanup: IOException: publish threw=IllegalStateException(No item at content://media/external/downloads/41) proof=present cleanup=overlap-observed(started=0,active=1,activeAtInsert=0)' % api]
            + ['PUBLISH_PROBE sdk=%d variant=%s cycles=20 ok=20 failed=0 maxCommitMs=15' % (api, v) for v in VARIANTS]
            + ['PUBLISH_RACE sdk=%d count=30 outcome=threw=IllegalStateException downloads=absent filesSameId=absent finalNameOtherIds=0 diskFinal=absent diskPending=absent' % api])
        L += lines
    L += ['PUBLISH_PROBE_STATUS ' + probe_status]
    L += ['RESTART force-stop phase A OK', 'RESTART force-stop phase B OK', 'RESTART process-replaced phase A OK', 'RESTART process-replaced phase B OK']
    L += list(extra)
    status = script_status if script_status is not None else (0 if 'OK (' in '\n'.join(main) and probe_status in ('ok', 'skipped') and not restart_fail else 1)
    if not drop_api:
        L += ['GATE-API %d' % api]
    L += section('main', main)
    if api >= 29:
        L += section('probe', probe if probe is not None else instr({'de.evp.helfer.MediaStorePublishProbeTest': PROBES}))
        L += section('probe-lines', probe_lines if probe_lines is not None else [l for l in L if l.startswith(('PUBLISH_PROBE ', 'PUBLISH_PROBE_SIGNATURE', 'PUBLISH_PROBE_ATTRIBUTION', 'PUBLISH_RACE'))])
    rs = []
    for mode in ('force-stop', 'process-replaced'):
        for ph in 'AB':
            name = 'restart-%s-%s' % (mode, ph)
            if restart_drop == name:
                continue
            ok = restart_fail != name
            L += section(name, ['INSTRUMENTATION_STATUS: test=phase' + ph, 'INSTRUMENTATION_STATUS_CODE: %s' % ('0' if ok else '-2'),
                                'OK (1 test)' if ok else 'Tests run: 1,  Failures: 1', 'INSTRUMENTATION_CODE: -1'])
            rs.append('RESTART %s phase %s %s' % (mode, ph, 'OK' if ok else 'FAILED'))
    L += section('restart-summary', rs)
    L += section('crash', list(crash))
    L += ['GATE-SCRIPT-STATUS %d' % status]
    for label, body in repeat_reports:
        L += section('repeat-' + label, body)
    L += ['REPEAT api=%d runs=22 %s' % (api, repeat)] if repeat else []
    if truncate:
        L = L[:L.index('GATE-END main')]
    return '\n'.join('2026-11-16T22:59:%02d.%07dZ %s' % (i % 60, i, l) for i, l in enumerate(L)) + '\n'


CASES = {
    # erlaubt
    'pass_api34': (34, 'PASS', job(34)),
    'pass_api26_no_probe': (26, 'PASS', job(26, probe_status='skipped')),
    'size_only_api29': (29, 'ACCEPTED_SIZE_ONLY', job(29, main=instr(CLASSES, {'publishesThroughMediaStoreAndReadsBack': SIZE_STACK}))),
    # wie in CI auf e5fb290: die zwei Läufe der ganzen MP4-Klasse scheitern nur an SIZE (Belege vollständig im Log)
    'size_only_with_class_repeats_api29': (29, 'ACCEPTED_SIZE_ONLY', job(29, main=instr(CLASSES, {'publishesThroughMediaStoreAndReadsBack': SIZE_STACK}),
        repeat='passed=20 failed=2', repeat_reports=[(l, instr({'de.evp.helfer.Mp4DownloadsInstrumentedTest': MP4}, {'publishesThroughMediaStoreAndReadsBack': SIZE_STACK})) for l in ('class-1', 'class-2')])),
    'repeat_publish_failure_api29': (29, 'REJECT', job(29, main=instr(CLASSES, {'publishesThroughMediaStoreAndReadsBack': SIZE_STACK}),
        repeat='passed=20 failed=2', repeat_reports=[('class-1', instr({'de.evp.helfer.Mp4DownloadsInstrumentedTest': MP4}, {'publishesThroughMediaStoreAndReadsBack': SIZE_STACK})),
                                                     ('class-2', instr({'de.evp.helfer.Mp4DownloadsInstrumentedTest': MP4}, {'publishesThroughMediaStoreAndReadsBack': PUBLISH_STACK}))])),
    'repeat_without_evidence_api29': (29, 'REJECT', job(29, main=instr(CLASSES, {'publishesThroughMediaStoreAndReadsBack': SIZE_STACK}), repeat='passed=20 failed=2')),
    'repeat_size_but_main_green_api29': (29, 'REJECT', job(29, repeat='passed=21 failed=1',
        repeat_reports=[('class-1', instr({'de.evp.helfer.Mp4DownloadsInstrumentedTest': MP4}, {'publishesThroughMediaStoreAndReadsBack': SIZE_STACK}))])),
    'repeat_three_failed_api29': (29, 'REJECT', job(29, main=instr(CLASSES, {'publishesThroughMediaStoreAndReadsBack': SIZE_STACK}), repeat='passed=21 failed=1',
        repeat_reports=[('three-7', instr({'de.evp.helfer.Mp4DownloadsInstrumentedTest': ['duplicateAndLateStartsFetchOnce']}, {'duplicateAndLateStartsFetchOnce': OTHER_STACK}))])),
    # nie automatisch akzeptiert
    'size_on_api34_rejected': (34, 'REJECT', job(34, main=instr(CLASSES, {'publishesThroughMediaStoreAndReadsBack': SIZE_STACK}))),
    'publish_rows0_api29': (29, 'REJECT', job(29, main=instr(CLASSES, {'publishesThroughMediaStoreAndReadsBack': PUBLISH_STACK}))),
    'size_plus_publish_api29': (29, 'REJECT', job(29, main=instr(CLASSES, {'publishesThroughMediaStoreAndReadsBack': SIZE_STACK,
                                                                         'publicFileForUninstallCheck': PUBLISH_STACK}))),
    'size_plus_unknown_api29': (29, 'REJECT', job(29, main=instr(CLASSES, {'publishesThroughMediaStoreAndReadsBack': SIZE_STACK, 'channelRoundTrip': OTHER_STACK}))),
    'unknown_failure_api34': (34, 'REJECT', job(34, main=instr(CLASSES, {'channelRoundTrip': OTHER_STACK}))),
    'probe_status_failed_api29': (29, 'REJECT', job(29, main=instr(CLASSES, {'publishesThroughMediaStoreAndReadsBack': SIZE_STACK}), probe_status='failed')),
    'probe_variant_failed_api34': (34, 'REJECT', job(34, probe_lines=['PUBLISH_PROBE_SIGNATURE sdk=34 x', 'PUBLISH_PROBE_ATTRIBUTION sdk=34 foreignDelete: x',
                                                                      'PUBLISH_PROBE_ATTRIBUTION sdk=34 duringCleanup: x', 'PUBLISH_RACE sdk=34 count=30 outcome=x']
                                                     + ['PUBLISH_PROBE sdk=34 variant=%s cycles=20 ok=%d failed=%d maxCommitMs=1' % (v, 19 if v == 'PLAIN' else 20, 1 if v == 'PLAIN' else 0) for v in VARIANTS])),
    'probe_variant_missing_api34': (34, 'REJECT', job(34, probe_lines=['PUBLISH_PROBE sdk=34 variant=%s cycles=20 ok=20 failed=0 maxCommitMs=1' % v for v in VARIANTS[:-1]]
                                                      + ['PUBLISH_PROBE_SIGNATURE sdk=34 x', 'PUBLISH_PROBE_ATTRIBUTION sdk=34 foreignDelete: x',
                                                         'PUBLISH_PROBE_ATTRIBUTION sdk=34 duringCleanup: x', 'PUBLISH_RACE sdk=34 count=30 outcome=x'])),
    'probe_test_missing_api34': (34, 'REJECT', job(34, probe=instr({'de.evp.helfer.MediaStorePublishProbeTest': PROBES[:2]}))),
    'probe_status_missing_api34': (34, 'REJECT', job(34, probe_status='')),
    'system_crash_api29': (29, 'REJECT', job(29, main=instr(CLASSES, crash='cpuStaysAwakeOnlyWhileDownloading'),
                                             crash=['11-16 22:59:20.364  1904  6709 E AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS: Thread-123',
                                                    '11-16 22:59:20.364  1904  6709 E AndroidRuntime: java.lang.OutOfMemoryError: Failed to allocate a 4194320 byte allocation'])),
    'app_crash_in_buffer_api34': (34, 'REJECT', job(34, crash=['11-16 22:59:20.392  5758  6712 E AndroidRuntime: FATAL EXCEPTION: evp-mp4'])),
    'restart_failed_api34': (34, 'REJECT', job(34, restart_fail='restart-process-replaced-B')),
    'restart_missing_api34': (34, 'REJECT', job(34, restart_drop='restart-force-stop-A')),
    'repeat_failed_api34': (34, 'REJECT', job(34, repeat='passed=21 failed=1')),
    'regression_not_shown_api34': (34, 'REJECT', job(34, regression=False)),
    'truncated_log_api34': (34, 'REJECT', job(34, truncate=True)),
    'old_script_without_sections_api29': (29, 'REJECT', job(29, drop_api=True)),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    exp = []
    for name, (api, verdict, log) in sorted(CASES.items()):
        open(os.path.join(OUT, name + '.log'), 'w', encoding='utf-8').write(log)
        exp.append('%s %d %s' % (name, api, verdict))
    open(os.path.join(OUT, 'EXPECTED'), 'w').write('\n'.join(exp) + '\n')
    print('wrote %d fixtures' % len(exp))


if __name__ == '__main__':
    main()
