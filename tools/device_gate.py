#!/usr/bin/env python3
"""Liefer-Gate für EINEN Emulator-Job von helper-channel.yml (Job-Log als Datei). Review P4 (Codex-Folgereview).

Aufruf:  device_gate.py JOB_LOG --api N [--job-conclusion success|failure]
Exit:    0 = PASS (alles grün, vollständig belegt)
         3 = ACCEPTED_SIZE_ONLY (nur API 29: genau der dokumentierte Befund P4B-A10-SIZE, sonst alles belegt grün)
         1 = REJECT (mit Gründen)

Bewertet wird ausschließlich, was tools/run-channel-instrumentation.sh zwischen "GATE-BEGIN x" und "GATE-END x"
vollständig ausgibt, plus die Zeilen der beiden anderen Geräteskripte (Wiederholungen, Veröffentlichungs-Regression).
Nie automatisch akzeptiert: jeder Veröffentlichungsfehler ("publish …"), PUBLISH_PROBE_STATUS failed, fehlende oder
unvollständige Sondenergebnisse, Emulator-/System- oder Prozessabsturz, jeder weitere oder unbekannte Fehlschlag,
abgeschnittene Ausgabe. Die Ausnahme P4B-A10-SIZE gilt NUR für API 29 und NUR für genau diesen einen Test mit genau
der SIZE-Abweichung (Dateilänge = erwartete Länge, Spalte 0) – sie wird als solche ausgewiesen, nie als "PASS".
"""
import argparse
import re
import sys

MAIN_CLASSES = ['de.evp.helfer.ChannelInstrumentedTest', 'de.evp.helfer.net.SafeHttpInstrumentedTest',
                'de.evp.helfer.Mp4DownloadsInstrumentedTest', 'de.evp.helfer.HlsDownloadsInstrumentedTest']
PROBE_TESTS = ['repeatedPublicationsOfTheAppStore', 'processStartCleanupDuringAJobSignature',
               'cleanupAttributionIsConservative', 'concurrentDeletionDuringPublishRace']
PROBE_VARIANTS = ['PLAIN', 'PAUSE_MID_WRITE', 'PAUSE_BEFORE_CLOSE', 'PAUSE_BEFORE_COMMIT', 'ABORTED_SIBLING_THEN_PAUSE_MID_WRITE',
                  'LARGE_DELETE_BEFORE_COMMIT', 'CONCURRENT_PROVIDER_LOAD']
RESTARTS = ['restart-force-stop-A', 'restart-force-stop-B', 'restart-process-replaced-A', 'restart-process-replaced-B']
SIZE_TEST = 'publishesThroughMediaStoreAndReadsBack'
SIZE_RE = re.compile(r'AssertionError: SIZE column \(file [^)]*, length (\d+)\) expected:<\1> but was:<0>')
PUBLISH_FAILURE = re.compile(r'publish rows=|publish threw=|IOException: publish|publish result \(cause|failed:storag')
SYSTEM_CRASH = re.compile(r'FATAL EXCEPTION IN SYSTEM PROCESS|DeadSystemException|DeadObjectException|System has crashed'
                          r'|Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_FAILED|Watchdog|system_server.*died')
CODE_FAIL = {'-1', '-2'}   # AndroidJUnitRunner: -1 Fehler, -2 Fehlschlag (-3 ignoriert, -4 Annahme nicht erfüllt)


def strip(line):
    # Zeitstempel des Actions-Logs entfernen
    return re.sub(r'^\d{4}-\d\d-\d\dT[\d:.]+Z ', '', line.rstrip('\r'))


def sections(lines):
    out, cur, name, dup = {}, None, None, []
    for l in lines:
        m = re.match(r'^GATE-BEGIN (\S+)$', l)
        if m:
            name, cur = m.group(1), []
            continue
        if name and l == 'GATE-END ' + name:
            if name in out:
                dup.append(name)
            out[name] = cur
            name, cur = None, None
            continue
        if cur is not None:
            cur.append(l)
    return out, dup, name


def instrumentation(lines):
    """Ergebnis eines am-instrument-Laufs: (Klassen, {Test: (Code, Stack)}, ok, run, failures, beendet)."""
    tests, classes, cur, cls, stack, in_stack = {}, set(), None, None, [], False
    for l in lines:
        m = re.match(r'^INSTRUMENTATION_STATUS: class=(\S+)', l)
        if m:
            cls = m.group(1); classes.add(cls); in_stack = False; continue
        m = re.match(r'^INSTRUMENTATION_STATUS: test=(\S+)', l)
        if m:
            cur = m.group(1); in_stack = False; continue
        if l.startswith('INSTRUMENTATION_STATUS: stack='):
            stack = [l]; in_stack = True; continue
        m = re.match(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)', l)
        if m and cur:
            code = m.group(1)
            if code != '1':
                tests[cur] = (code, '\n'.join(stack) if code in CODE_FAIL else '')
            stack, in_stack = [], False
            continue
        if in_stack and l.startswith('INSTRUMENTATION_STATUS:'):
            in_stack = False
        elif in_stack:
            stack.append(l)
    text = '\n'.join(lines)
    ok = re.search(r'(?m)^OK \((\d+) tests?\)', text)
    tr = re.search(r'(?m)^Tests run: (\d+),\s+Failures: (\d+)', text)
    run = int(ok.group(1)) if ok else int(tr.group(1)) if tr else None
    failures = 0 if ok else int(tr.group(2)) if tr else None
    finished = re.search(r'(?m)^INSTRUMENTATION_CODE: -1$', text) is not None
    return classes, tests, bool(ok), run, failures, finished


def evaluate(log, api, job_conclusion=None):
    lines = [strip(l) for l in log.splitlines()]
    text = '\n'.join(lines)
    reasons, size_exception = [], False
    repeat_size_only = False
    secs, dup, open_sec = sections(lines)
    if dup:
        reasons.append('section reported twice: %s' % dup)
    if open_sec:
        reasons.append('section not terminated (truncated log): %s' % open_sec)
    m = re.search(r'(?m)^GATE-API (\d+)$', text)
    if not m:
        reasons.append('no GATE-API line (old or truncated device script output)')
    elif int(m.group(1)) != api:
        reasons.append('log is API %s, gate asked for API %d' % (m.group(1), api))
    for name, body in secs.items():
        if any(l.startswith('GATE-MISSING') for l in body):
            reasons.append('report missing: %s' % name)

    # 1) vollständiger Gerätetest
    main = secs.get('main')
    if main is None:
        reasons.append('main instrumentation report missing')
    else:
        classes, tests, ok, run, failures, finished = instrumentation(main)
        mt = '\n'.join(main)
        if not finished:
            reasons.append('main run did not finish normally (no INSTRUMENTATION_CODE: -1)')
        if run is None:
            reasons.append('main run has no result line (OK/Tests run)')
        missing = [c for c in MAIN_CLASSES if c not in classes]
        if missing:
            reasons.append('main run incomplete, classes not run: %s' % missing)
        if SYSTEM_CRASH.search(mt):
            reasons.append('crash in main run: %s' % SYSTEM_CRASH.search(mt).group(0))
        failed = sorted(t for t, (c, _) in tests.items() if c in CODE_FAIL)
        if failures is not None and failures != len(failed):
            reasons.append('failure count %s does not match failed tests %s' % (failures, failed))
        if failed:
            only_size = (api == 29 and failed == [SIZE_TEST] and failures == 1)
            stack = tests[SIZE_TEST][1] if SIZE_TEST in tests else ''
            if only_size and SIZE_RE.search(stack) and not PUBLISH_FAILURE.search(stack):
                size_exception = True
            else:
                for t in failed:
                    st = tests[t][1]
                    kind = 'publish failure' if PUBLISH_FAILURE.search(st) else 'SIZE (not allowed here)' if SIZE_RE.search(st) else 'failure'
                    reasons.append('main test failed (%s): %s' % (kind, t))
        if PUBLISH_FAILURE.search(mt) and not failed:
            reasons.append('publish failure text in a green main run')

    # 2) Veröffentlichungssonden (ab API 29 Pflicht)
    status = re.findall(r'(?m)^PUBLISH_PROBE_STATUS (\S+)$', text)
    if api >= 29:
        if status != ['ok']:
            reasons.append('publish probe status %s (must be exactly one "ok")' % (status or 'missing'))
        probe, plines = secs.get('probe'), secs.get('probe-lines')
        if probe is None or plines is None:
            reasons.append('publish probe report missing')
        else:
            _, ptests, pok, prun, pfail, pfin = instrumentation(probe)
            if not (pok and pfin):
                reasons.append('publish probe run not OK/finished')
            pmissing = [t for t in PROBE_TESTS if ptests.get(t, ('?',))[0] != '0']
            if pmissing:
                reasons.append('publish probe tests missing or not passed: %s' % pmissing)
            pl = '\n'.join(plines)
            for v in PROBE_VARIANTS:
                mm = re.search(r'(?m)^PUBLISH_PROBE sdk=%d variant=%s cycles=(\d+) ok=(\d+) failed=(\d+)' % (api, v), pl)
                if not mm:
                    reasons.append('publish probe result missing: variant %s' % v)
                elif mm.group(3) != '0' or mm.group(1) != mm.group(2):
                    reasons.append('publish probe variant %s failed %s of %s' % (v, mm.group(3), mm.group(1)))
            for need in ('PUBLISH_PROBE_SIGNATURE sdk=%d' % api, 'PUBLISH_PROBE_ATTRIBUTION sdk=%d foreignDelete:' % api,
                         'PUBLISH_PROBE_ATTRIBUTION sdk=%d duringCleanup:' % api, 'PUBLISH_RACE sdk=%d' % api):
                if need not in pl:
                    reasons.append('publish probe evidence missing: %s' % need)
    elif status != ['skipped']:
        reasons.append('publish probe status %s on API %d (expected skipped)' % (status or 'missing', api))

    # 3) echter Prozessneustart
    rs = '\n'.join(secs.get('restart-summary') or [])
    for r in RESTARTS:
        mode, ph = r[len('restart-'):-2], r[-1]
        body = secs.get(r)
        if body is None:
            reasons.append('restart report missing: %s' % r)
        elif not re.search(r'(?m)^OK \(1 test\)$', '\n'.join(body)):
            reasons.append('restart phase failed: %s' % r)
        if not re.search(r'(?m)^RESTART %s phase %s OK$' % (mode, ph), rs):
            reasons.append('restart summary not OK: %s %s' % (mode, ph))

    # 4) Absturzpuffer, System, Gesamtprotokoll
    crash = '\n'.join(secs.get('crash') or [])
    if secs.get('crash') is None:
        reasons.append('crash buffer report missing')
    elif re.search(r'FATAL EXCEPTION|AndroidRuntime: \*\*\*|Fatal signal|backtrace:', crash):
        reasons.append('crash buffer not empty: %s' % re.search(r'FATAL EXCEPTION[^\n]*|Fatal signal[^\n]*|backtrace:', crash).group(0)[:120])
    if re.search(r'FATAL EXCEPTION IN SYSTEM PROCESS|System has crashed|DeadSystemException', text):
        reasons.append('emulator/system crash in this job')

    # 4b) Testumgebung: Heap des App-Prozesses (HLS_MEASURE); auf API 29 wurde sporadisch 16 MiB statt 512 MiB beobachtet –
    # dann sind Ergebnisse nicht aussagekräftig (Speicherfehler in HLS-Tests, OOM in Prüfschritten). Nie akzeptiert.
    heaps = [int(h) for h in re.findall(r'HLS_MEASURE api=\d+ .*?maxHeapMiB=(\d+)', text)]
    if heaps and min(heaps) < 128:
        reasons.append('invalid emulator environment: app heap %d MiB (expected >= 128)' % min(heaps))

    # 5) Wiederholungen und erwarteter Mutantenfehler (Veröffentlichungs-Regression)
    rep = re.findall(r'(?m)^REPEAT api=(\d+) runs=(\d+) passed=(\d+) failed=(\d+)$', text)
    rsecs = {n: b for n, b in secs.items() if n.startswith('repeat-')}
    if len(rep) != 1:
        reasons.append('flaky repeat result missing or repeated: %d lines' % len(rep))
    elif int(rep[0][1]) < 1 or int(rep[0][2]) + int(rep[0][3]) != int(rep[0][1]):
        reasons.append('flaky repeat counts inconsistent: %s' % ' '.join(rep[0]))
    elif rep[0][3] != '0':
        # Ein gescheiterter Wiederholungslauf zählt nur auf API 29 und nur, wenn er nachweislich AUSSCHLIESSLICH an
        # P4B-A10-SIZE scheiterte (die ganze MP4-Klasse enthält den SIZE-Test); jeder Lauf muss vollständig belegt sein.
        if api != 29:
            reasons.append('flaky repeat failed: %s' % ' '.join(rep[0]))
        elif len(rsecs) != int(rep[0][3]):
            reasons.append('flaky repeat failed %s times but %d failure reports in the log' % (rep[0][3], len(rsecs)))
        else:
            for n, body in sorted(rsecs.items()):
                _, rt, _, _, rf, rfin = instrumentation(body)
                rfailed = sorted(t for t, (c, _) in rt.items() if c in CODE_FAIL)
                rst = rt.get(SIZE_TEST, ('', ''))[1]
                bt = '\n'.join(body)
                if not (rfin and rfailed == [SIZE_TEST] and rf == 1 and SIZE_RE.search(rst) and not PUBLISH_FAILURE.search(rst)
                        and not SYSTEM_CRASH.search(bt)):
                    reasons.append('flaky repeat %s failed for another reason than P4B-A10-SIZE: %s' % (n, rfailed or 'unknown'))
                else:
                    repeat_size_only = True
    if rsecs and (not rep or rep[0][3] == '0'):
        reasons.append('repeat failure reports present although no repeat failed')
    if 'PASS: previous cleanup fails the publication regression (failed:storage instead of done)' not in text:
        reasons.append('expected mutant failure (publication regression) not shown')

    # 6) Script-Status passt zum Ergebnis
    st = re.findall(r'(?m)^GATE-SCRIPT-STATUS (\d+)$', text)
    if len(st) != 1:
        reasons.append('device script status missing')
    elif not reasons:
        if size_exception and st[0] != '1':
            reasons.append('SIZE exception but device script reported success')
        if not size_exception and st[0] != '0':
            reasons.append('device script reported failure without a visible reason')
    if job_conclusion is not None and not reasons:
        if size_exception and job_conclusion != 'failure':
            reasons.append('SIZE exception but job conclusion %s' % job_conclusion)
        if not size_exception and job_conclusion != 'success':
            reasons.append('all evidence green but job conclusion %s' % job_conclusion)

    if repeat_size_only and not size_exception:
        reasons.append('repeat runs failed by SIZE but the main run did not show the documented SIZE finding')
    if reasons:
        return 'REJECT', reasons
    return ('ACCEPTED_SIZE_ONLY' if size_exception else 'PASS'), []


def main():
    a = argparse.ArgumentParser()
    a.add_argument('log')
    a.add_argument('--api', type=int, required=True)
    a.add_argument('--job-conclusion', choices=['success', 'failure'])
    o = a.parse_args()
    verdict, reasons = evaluate(open(o.log, encoding='utf-8', errors='replace').read(), o.api, o.job_conclusion)
    print('DEVICE_GATE api=%d verdict=%s' % (o.api, verdict))
    for r in reasons:
        print('  REJECT-REASON ' + r)
    if verdict == 'ACCEPTED_SIZE_ONLY':
        print('  ACCEPTED (owner decision, isolated Android 17 test only): P4B-A10-SIZE on API 29 - NOT an Android 10 approval')
    sys.exit(0 if verdict == 'PASS' else 3 if verdict == 'ACCEPTED_SIZE_ONLY' else 1)


if __name__ == '__main__':
    main()
