#!/usr/bin/env python3
"""Liefer-Gate für EINEN Emulator-Job von helper-channel.yml (vollständiges Job-Log als Datei).

Review P4 (Codex-Folgereview) und Codex Release-Readiness P2 (Vollständigkeit).

Aufruf:  device_gate.py JOB_LOG --api N [--job-conclusion success|failure]
Exit:    0 = PASS (alles grün, vollständig belegt)
         3 = ACCEPTED_SIZE_ONLY (nur API 29: genau der dokumentierte Befund P4B-A10-SIZE, sonst alles belegt grün)
         1 = REJECT (mit Gründen)

Bewertet wird gegen eine FESTE erwartete Testmenge (Quellstand der Gerätetests, siehe EXPECTED_*). Bewertet werden:
  * Gerätebefehl: genau der erwartete Befehl (Mutantenprobe, Gerätetest, 20 Dreier- und 2 Klassen-Wiederholungen);
  * Mutantenprobe: genau ein Lauf mit genau dem erwarteten Fehlschlag, getrennt vor dem Gerätetest;
  * Haupttest: genau die 46 erwarteten Tests der 4 Klassen, jeder genau einmal begonnen und beendet, Zählung konsistent;
  * Veröffentlichungssonden (ab API 29): genau die 5 Sondentests, jede Variante genau einmal mit der festgelegten
    positiven Zyklenzahl und ohne Fehlschlag, alle Belegzeilen; unter API 29 ausdrücklich übersprungen;
  * echter Prozessneustart: 4 Phasen mit genau dem erwarteten Test;
  * Wiederholungen: genau eine REPEAT-Zeile für DIESE API mit 22 Läufen; jeder Fehlschlag vollständig belegt;
  * Absturzpuffer leer, keine System-/Emulator-/Prozessabstürze, Heap der Testumgebung >= 128 MiB, Port 47811 zu,
    Paketliste unverändert, Datei nach Deinstallation vorhanden;
  * Reihenfolge und Vollständigkeit des Logs (Job vollständig beendet), keine unbekannten Abschnitte oder Fehlerzeilen;
  * Script-Status und Job-Ergebnis passen zum Befund.
Nie automatisch akzeptiert: jeder Veröffentlichungsfehler ("publish …"), PUBLISH_PROBE_STATUS failed, fehlende oder
unvollständige Sondenergebnisse, Emulator-/System- oder Prozessabsturz, jeder weitere oder unbekannte Fehlschlag,
abgeschnittene Ausgabe. Die Ausnahme P4B-A10-SIZE gilt NUR für API 29 und NUR für genau diesen einen Test mit genau
der SIZE-Abweichung (Dateilänge = erwartete Länge, Spalte 0) – sie wird als solche ausgewiesen, nie als "PASS".
"""
import argparse
import re
import sys

APIS = (26, 29, 34)
P = 'de.evp.helfer.'
EXPECTED_MAIN = {
    P + 'ChannelInstrumentedTest': [
        'identityStateMachineNeverRotatesSilently', 'lostIdentityFileStopsTheChannelAndKeepsPairings', 'registryStoreDetectsReboot',
        'pairingAndControlCommands', 'downloadCommandsUseOnlyTheHardenedClient', 'wrongCodeFailsAndBurnsTheWindow',
        'wrongPinIsRejectedByTheGenuineHelper', 'browserGateRefusesWebLikeRequests', 'nothingListensOnTheLegacyPort',
        'foreignListenerReceivesCiphertextOnlyAndSessionIsDropped', 'restartKeepsRegistrationsButNotSessions',
        'revokeOneKeepsTheOthersAcrossRestart', 'failedRevokeWarnsThatThePairingMaySurviveARestart', 'resetIdentityInvalidatesOldPins',
        'f3SlowClientsAreDroppedAndLegitimateRequestsServed', 'packageScreenAndBackupHardening'],
    P + 'net.SafeHttpInstrumentedTest': [
        'realHttpRequestsThroughTheSafeClient', 'realHttpsRequestThroughTheSafeClient', 'strictClientRefusesLocalAndPrivateTargets',
        'dnsRebindingAndPrivateAnswersAreRefused', 'noSystemProxiesAndNoAutomaticRepeats', 'streamingKeepsMemoryBounded',
        'networkNat64PrefixLookupWorks', 'startupComponentRemovedAndOkHttpInitialised', 'installedAppIsTheShrunkR8Build'],
    P + 'Mp4DownloadsInstrumentedTest': [
        'publishesThroughMediaStoreAndReadsBack', 'cpuStaysAwakeOnlyWhileDownloading', 'cancelRemovesEverything',
        'interruptionFailsCleanOrResumesExactly', 'notEnoughSpaceFailsBeforeAnyEntry', 'duplicateAndLateStartsFetchOnce',
        'leftoversAfterProcessDeathAreRemovedOnNextStart', 'storagePermissionOnlyOnAndroid8And9',
        'publishedFileSurvivesMarkerFailureAndRestart', 'crashConsistencyBetweenJournalAndStorage', 'publicFileForUninstallCheck',
        'storageWritableAfterRuntimeGrant'],
    P + 'HlsDownloadsInstrumentedTest': [
        'allProfilesRemuxPublishAndDecodeOnThisDevice', 'heapPerSampleSupportsTheBudget', 'memoryPressureAbortsCleanly',
        'cancelDuringRemuxIsFastAndLeavesNothing', 'serviceStopRestartAndResume', 'screenOffCompletesAndIsRecognisedAfterRestart',
        'persistentCancelWinsAfterRestart', 'fullStorageFailsBeforeAnySegment', 'parallelMp4AndHls'],
}
MP4 = P + 'Mp4DownloadsInstrumentedTest'
PROBE_CLASS = P + 'MediaStorePublishProbeTest'
PROBE_TESTS = ['repeatedPublicationsOfTheAppStore', 'processStartCleanupDuringAJobSignature', 'cleanupAttributionIsConservative',
               'concurrentDeletionDuringPublishRace', 'sameFinalNameTwice']
# Festgelegte positive Zyklenzahl je Variante (MediaStorePublishProbeTest: CYCLES, PAUSE_CYCLES, LOAD_CYCLES)
PROBE_CYCLES = {'PLAIN': 60, 'PAUSE_MID_WRITE': 20, 'PAUSE_BEFORE_CLOSE': 20, 'PAUSE_BEFORE_COMMIT': 20,
                'ABORTED_SIBLING_THEN_PAUSE_MID_WRITE': 20, 'LARGE_DELETE_BEFORE_COMMIT': 15, 'CONCURRENT_PROVIDER_LOAD': 15}
PROBE_EVIDENCE = ['PUBLISH_PROBE_SIGNATURE sdk={api} cleanupDuringJob:', 'PUBLISH_PROBE_ATTRIBUTION sdk={api} foreignDelete:',
                  'PUBLISH_PROBE_ATTRIBUTION sdk={api} duringCleanup:', 'PUBLISH_RACE sdk={api} ', 'PUBLISH_PROBE_SAMENAME sdk={api} ']
RESTART_CLASS = P + 'ProcessRestartInstrumentedTest'
RESTARTS = {'restart-force-stop-A': 'phaseA_startDownloadAndPairThenTheProcessDies',
            'restart-force-stop-B': 'phaseB_afterARealProcessRestart',
            'restart-process-replaced-A': 'phaseA_startDownloadAndPairThenTheProcessDies',
            'restart-process-replaced-B': 'phaseB_afterARealProcessRestart'}
MUTANT_TEST = 'publishedFileSurvivesMarkerFailureAndRestart'
MUTANT_PASS = 'PASS: previous cleanup fails the publication regression (failed:storage instead of done)'
REPEAT_THREE = ['duplicateAndLateStartsFetchOnce', 'leftoversAfterProcessDeathAreRemovedOnNextStart', 'cancelRemovesEverything']
REPEAT_CLASS_EXCLUDED = 'publicFileForUninstallCheck'
REPEAT_N, REPEAT_CLASS_RUNS = 20, 2
REPEAT_RUNS = REPEAT_N + REPEAT_CLASS_RUNS
DEVICE_COMMAND = ('[command]/usr/bin/sh -c bash tools/wait-device-ready.sh && bash tools/run-channel-publication-regression.sh '
                  '"$RUNNER_TEMP/publication-mutant" && { bash tools/run-channel-instrumentation.sh "$RUNNER_TEMP/a"; s=$?; '
                  'bash tools/run-flaky-repeat.sh "$RUNNER_TEMP/a" %d && exit $s; }' % REPEAT_N)
SIZE_ERROR = "##[error]The process '/usr/bin/sh' failed with exit code 1"
JOB_END = 'Cleaning up orphan processes'

SIZE_TEST = 'publishesThroughMediaStoreAndReadsBack'
SIZE_RE = re.compile(r'AssertionError: SIZE column \(file [^)]*, length (\d+)\) expected:<\1> but was:<0>')
PUBLISH_FAILURE = re.compile(r'publish rows=|publish threw=|IOException: publish|publish result \(cause|failed:storag')
SYSTEM_CRASH = re.compile(r'FATAL EXCEPTION|DeadSystemException|DeadObjectException|System has crashed|Process crashed'
                          r'|INSTRUMENTATION_ABORTED|INSTRUMENTATION_FAILED|Watchdog|system_server.*died|Fatal signal'
                          r'|ANR in |shortMsg=|INSTRUMENTATION_STATUS: Error=|OutOfMemoryError')
CODE_FAIL = {'-1', '-2'}   # AndroidJUnitRunner: -1 Fehler, -2 Fehlschlag (-3 ignoriert, -4 Annahme nicht erfüllt)


def strip(line):
    # Zeitstempel des Actions-Logs und BOM entfernen
    return re.sub(r'^﻿?\d{4}-\d\d-\d\dT[\d:.]+Z ', '', line.rstrip('\r').lstrip('﻿'))


def sections(lines):
    """-> ({Name: Zeilen}, doppelte Namen, offener Abschnitt, Zeilenindex Begin je Name)."""
    out, cur, name, dup, pos = {}, None, None, [], {}
    for i, l in enumerate(lines):
        m = re.match(r'^GATE-BEGIN (\S+)$', l)
        if m:
            if name:
                dup.append('nested:' + m.group(1))
            name, cur = m.group(1), []
            pos.setdefault(name, i)
            continue
        if name and l == 'GATE-END ' + name:
            if name in out:
                dup.append(name)
            out[name] = cur
            name, cur = None, None
            continue
        if cur is not None:
            cur.append(l)
    return out, dup, name, pos


class Report:
    """Strikt geparster am-instrument-Bericht (Statusblöcke key=value bis INSTRUMENTATION_STATUS_CODE)."""

    def __init__(self, lines):
        self.events, self.problems = [], []
        block, key = {}, None
        for l in lines:
            m = re.match(r'^INSTRUMENTATION_STATUS: (\w+)=(.*)$', l)
            if m:
                key = m.group(1)
                block[key] = m.group(2)
                continue
            m = re.match(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)$', l)
            if m:
                if 'test' in block and 'class' in block:
                    self.events.append((block['class'], block['test'], m.group(1), block.get('stack', '')))
                elif m.group(1) != '1' or block:
                    self.problems.append('status block without class/test (code %s)' % m.group(1))
                block, key = {}, None
                continue
            if l.startswith('INSTRUMENTATION_'):
                key = None
                continue
            if key == 'stack':
                block['stack'] += '\n' + l
        text = '\n'.join(lines)
        self.text = text
        ok = re.findall(r'(?m)^OK \((\d+) tests?\)$', text)
        tr = re.findall(r'(?m)^Tests run: (\d+),\s+Failures: (\d+)$', text)
        self.ok = len(ok) == 1 and not tr
        if len(ok) + len(tr) != 1:
            self.run = self.failures = None
            self.problems.append('expected exactly one result line, found %d' % (len(ok) + len(tr)))
        else:
            self.run = int(ok[0]) if ok else int(tr[0][0])
            self.failures = 0 if ok else int(tr[0][1])
        self.finished = len(re.findall(r'(?m)^INSTRUMENTATION_CODE: -1$', text)) == 1
        if not self.finished:
            self.problems.append('runner did not finish normally (INSTRUMENTATION_CODE: -1 exactly once)')
        # Jeder Test genau einmal begonnen (Code 1) und genau einmal beendet
        self.final, started = {}, {}
        for cls, t, code, stack in self.events:
            k = (cls, t)
            if code == '1':
                if k in started:
                    self.problems.append('test started twice: %s' % t)
                started[k] = True
            else:
                if k not in started:
                    self.problems.append('test ended without start: %s' % t)
                if k in self.final:
                    self.problems.append('test ended twice: %s' % t)
                self.final[k] = (code, stack)
        for k in started:
            if k not in self.final:
                self.problems.append('test started but never ended: %s' % k[1])
        if self.run is not None and self.run != len(self.final):
            self.problems.append('result line says %d tests, report shows %d' % (self.run, len(self.final)))
        self.failed = sorted(k for k, (c, _) in self.final.items() if c in CODE_FAIL)
        if self.failures is not None and self.failures != len(self.failed):
            self.problems.append('failure count %d does not match failed tests %s' % (self.failures, [t for _, t in self.failed]))
        other = sorted(t for (_, t), (c, _) in self.final.items() if c not in CODE_FAIL and c != '0')
        if other:
            self.problems.append('tests ignored or assumption-skipped: %s' % other)
        if SYSTEM_CRASH.search(text):
            self.problems.append('crash in this run: %s' % SYSTEM_CRASH.search(text).group(0))

    def tests(self):
        return sorted(self.final)


def expect_tests(rep, expected, what, reasons):
    got = set(rep.tests())
    want = {(c, t) for c, ts in expected.items() for t in ts}
    if got != want:
        miss, extra = sorted(t for _, t in want - got), sorted(t for _, t in got - want)
        reasons.append('%s test set differs from the expected %d tests (missing %s, unexpected %s)' % (what, len(want), miss, extra))
    for p in rep.problems:
        reasons.append('%s report: %s' % (what, p))


def is_size_only(rep):
    if [t for _, t in rep.failed] != [SIZE_TEST] or rep.failures != 1:
        return False
    st = rep.final[(MP4, SIZE_TEST)][1]
    return bool(SIZE_RE.search(st)) and not PUBLISH_FAILURE.search(st)


def evaluate(log, api, job_conclusion=None):
    lines = [strip(l) for l in log.splitlines()]
    text = '\n'.join(lines)
    reasons, size_exception, repeat_size_only = [], False, False
    if api not in APIS:
        return 'REJECT', ['API %d is not a gated emulator level %s' % (api, list(APIS))]
    secs, dup, open_sec, pos = sections(lines)
    if dup:
        reasons.append('section reported twice or nested: %s' % dup)
    if open_sec:
        reasons.append('section not terminated (truncated log): %s' % open_sec)
    allowed = {'main', 'restart-summary', 'crash'} | set(RESTARTS) | ({'probe', 'probe-lines'} if api >= 29 else set())
    allowed |= {'repeat-three-%d' % i for i in range(1, REPEAT_N + 1)} | {'repeat-class-%d' % i for i in range(1, REPEAT_CLASS_RUNS + 1)}
    unknown = sorted(set(secs) - allowed)
    if unknown:
        reasons.append('unexpected sections for API %d: %s' % (api, unknown))
    for name, body in secs.items():
        if any(l.startswith('GATE-MISSING') for l in body):
            reasons.append('report missing: %s' % name)

    def idx(pattern, what, unique=True):
        hits = [i for i, l in enumerate(lines) if re.match(pattern, l)]
        if not hits or (unique and len(hits) != 1):
            reasons.append('%s: expected exactly one, found %d' % (what, len(hits)))
            return None
        return hits[0]

    # 0) Vollständiges Log, genau der erwartete Gerätebefehl, Reihenfolge der Phasen
    i_cmd = idx(r'^' + re.escape(DEVICE_COMMAND) + r'$', 'device command (mutant probe, device test, %d+%d repeats)' % (REPEAT_N, REPEAT_CLASS_RUNS))
    i_ready = idx(r'^DEVICE_READY after \d+ s$', 'DEVICE_READY line')
    i_mut = idx(r'^' + re.escape(MUTANT_PASS) + r'$', 'expected mutant failure (publication regression)')
    i_status = idx(r'^PUBLISH_PROBE_STATUS \S+$', 'PUBLISH_PROBE_STATUS line')
    i_api = idx(r'^GATE-API \d+$', 'GATE-API line')
    i_script = idx(r'^GATE-SCRIPT-STATUS \d+$', 'GATE-SCRIPT-STATUS line')
    i_rep = idx(r'^REPEAT api=\d+ runs=\d+ passed=\d+ failed=\d+$', 'REPEAT result line')
    i_end = idx(r'^' + re.escape(JOB_END) + r'$', 'job end (complete job log)')
    order = [i_cmd, i_ready, i_mut, i_status, i_api, pos.get('main'), i_script, i_rep, i_end]
    if None not in order and order != sorted(order):
        reasons.append('device output out of order (command, ready, mutant, probe status, GATE-API, main, script status, repeats, end)')
    if i_script is not None:
        late = [n for n, p in pos.items() if not n.startswith('repeat-') and p > i_script]
        early = [n for n, p in pos.items() if n.startswith('repeat-') and p < i_script]
        if late or early:
            reasons.append('sections in the wrong phase: %s' % (late + early))
    if i_api is not None:
        m = re.match(r'^GATE-API (\d+)$', lines[i_api])
        if int(m.group(1)) != api:
            reasons.append('log is API %s, gate asked for API %d' % (m.group(1), api))

    # 0b) Mutantenprobe (vor dem Gerätetest, getrennt bewertet): genau der erwartete Fehlschlag
    if i_ready is not None and i_mut is not None and i_ready < i_mut:
        mut = Report(lines[i_ready + 1:i_mut])
        if mut.tests() != [(MP4, MUTANT_TEST)] or [t for _, t in mut.failed] != [MUTANT_TEST] or mut.failures != 1:
            reasons.append('mutant run is not exactly the expected single failing test %s' % MUTANT_TEST)
        if SYSTEM_CRASH.search(mut.text):
            reasons.append('crash in the mutant run: %s' % SYSTEM_CRASH.search(mut.text).group(0))

    # 1) vollständiger Gerätetest (feste Testmenge)
    main = secs.get('main')
    if main is None:
        reasons.append('main instrumentation report missing')
    else:
        rep = Report(main)
        expect_tests(rep, EXPECTED_MAIN, 'main', reasons)
        if rep.failed:
            if api == 29 and is_size_only(rep):
                size_exception = True
            else:
                for _, t in rep.failed:
                    st = rep.final[(_, t)][1]
                    kind = 'publish failure' if PUBLISH_FAILURE.search(st) else 'SIZE (not allowed here)' if SIZE_RE.search(st) else 'failure'
                    reasons.append('main test failed (%s): %s' % (kind, t))
        elif PUBLISH_FAILURE.search(rep.text):
            reasons.append('publish failure text in a green main run')

    # 2) Veröffentlichungssonden (ab API 29 Pflicht)
    status = re.findall(r'(?m)^PUBLISH_PROBE_STATUS (\S+)$', text)
    probe_lines = [l for l in lines if re.match(r'^(PUBLISH_PROBE|PUBLISH_RACE)', l) and not l.startswith('PUBLISH_PROBE_STATUS')]
    if api >= 29:
        if status != ['ok']:
            reasons.append('publish probe status %s (must be exactly one "ok")' % (status or 'missing'))
        probe, plines = secs.get('probe'), secs.get('probe-lines')
        if probe is None or plines is None:
            reasons.append('publish probe report missing')
        else:
            pr = Report(probe)
            expect_tests(pr, {PROBE_CLASS: PROBE_TESTS}, 'publish probe', reasons)
            if not pr.ok:
                reasons.append('publish probe run not OK')
            for v, n in PROBE_CYCLES.items():
                mm = [re.match(r'^PUBLISH_PROBE sdk=(\d+) variant=%s cycles=(\d+) ok=(\d+) failed=(\d+)( |$)' % v, l) for l in plines]
                mm = [m for m in mm if m]
                if len(mm) != 1:
                    reasons.append('publish probe variant %s reported %d times (expected once)' % (v, len(mm)))
                    continue
                sdk, cyc, ok, fl = (int(x) for x in mm[0].groups()[:4])
                if sdk != api or cyc != n or ok != n or fl != 0:
                    reasons.append('publish probe variant %s: sdk=%d cycles=%d ok=%d failed=%d (expected sdk=%d cycles=ok=%d failed=0)'
                                   % (v, sdk, cyc, ok, fl, api, n))
            unknown_v = [l for l in plines if l.startswith('PUBLISH_PROBE sdk=') and not re.search(r' variant=(%s) ' % '|'.join(PROBE_CYCLES), l + ' ')]
            if unknown_v:
                reasons.append('unknown publish probe variant: %s' % unknown_v[0][:120])
            for need in PROBE_EVIDENCE:
                n = sum(1 for l in plines if l.startswith(need.format(api=api)))
                if n < 1:
                    reasons.append('publish probe evidence missing: %s' % need.format(api=api).strip())
            other_sdk = [l for l in plines if re.search(r'sdk=(\d+)', l) and int(re.search(r'sdk=(\d+)', l).group(1)) != api]
            if other_sdk:
                reasons.append('publish probe lines of another API: %s' % other_sdk[0][:120])
            if [l for l in plines if l.strip()] != [l for l in plines if re.match(r'^(PUBLISH_PROBE|PUBLISH_RACE)', l)]:
                reasons.append('unexpected lines in the probe evidence section')
    else:
        if status != ['skipped']:
            reasons.append('publish probe status %s on API %d (expected skipped)' % (status or 'missing', api))
        if probe_lines:
            reasons.append('publish probe output on API %d where the probe must be skipped' % api)

    # 3) echter Prozessneustart
    rs = secs.get('restart-summary') or []
    for r, test in RESTARTS.items():
        mode, ph = r[len('restart-'):-2], r[-1]
        body = secs.get(r)
        if body is None:
            reasons.append('restart report missing: %s' % r)
        else:
            rr = Report(body)
            if rr.tests() != [(RESTART_CLASS, test)] or not rr.ok or rr.problems:
                reasons.append('restart phase failed or incomplete: %s %s' % (r, rr.problems[:2]))
        if rs.count('RESTART %s phase %s OK' % (mode, ph)) != 1:
            reasons.append('restart summary not OK: %s %s' % (mode, ph))
    if [l for l in rs if l.strip()] and len([l for l in rs if l.strip()]) != len(RESTARTS):
        reasons.append('restart summary has unexpected lines')
    if 'RESTART results missing' in text:
        reasons.append('restart results missing')

    # 4) Absturzpuffer, System, Umgebung, Nebenbedingungen des Gerätelaufs
    crash = '\n'.join(secs.get('crash') or [])
    if secs.get('crash') is None:
        reasons.append('crash buffer report missing')
    elif crash.strip():
        reasons.append('crash buffer not empty: %s' % crash.strip().splitlines()[0][:120])
    if re.search(r'FATAL EXCEPTION IN SYSTEM PROCESS|System has crashed|DeadSystemException|system_server.*died|Watchdog', text):
        reasons.append('emulator/system crash in this job')
    for pat, why in [(r'(?m)^LISTENER ON 47811$', 'legacy port 47811 listening'), (r'(?m)^package list changed$', 'package list changed'),
                     (r'(?m)^PERSIST_AFTER_UNINSTALL missing$', 'published file missing after uninstall')]:
        if re.search(pat, text):
            reasons.append(why)
    if not re.search(r'(?m)^PERSIST_AFTER_UNINSTALL ok: ', text):
        reasons.append('no evidence that the published file survives uninstall')
    heaps = [int(h) for h in re.findall(r'HLS_MEASURE api=%d .*?maxHeapMiB=(\d+)' % api, text)]
    if not heaps:
        reasons.append('no HLS_MEASURE heap evidence for API %d' % api)
    elif min(heaps) < 128:
        reasons.append('invalid emulator environment: app heap %d MiB (expected >= 128)' % min(heaps))

    # 5) Wiederholungen: genau diese API, 22 Läufe (20 Dreier + 2 Klassen), jeder Fehlschlag vollständig belegt
    rep_l = re.findall(r'(?m)^REPEAT api=(\d+) runs=(\d+) passed=(\d+) failed=(\d+)$', text)
    rsecs = {n: b for n, b in secs.items() if n.startswith('repeat-')}
    if len(rep_l) == 1:
        rapi, runs, passed, failed = (int(x) for x in rep_l[0])
        if rapi != api:
            reasons.append('repeat result is API %d, job is API %d' % (rapi, api))
        if runs != REPEAT_RUNS or passed + failed != runs:
            reasons.append('repeat runs %d (passed %d, failed %d), expected %d (%d three-test + %d class runs)'
                           % (runs, passed, failed, REPEAT_RUNS, REPEAT_N, REPEAT_CLASS_RUNS))
        if len(rsecs) != failed:
            reasons.append('repeat failed %d times but %d failure reports in the log' % (failed, len(rsecs)))
        for n, body in sorted(rsecs.items()):
            rr = Report(body)
            if n.startswith('repeat-three-'):
                expect_tests(rr, {MP4: REPEAT_THREE}, n, reasons)
                reasons.append('%s failed: %s' % (n, [t for _, t in rr.failed] or 'unknown'))
                continue
            expect_tests(rr, {MP4: [t for t in EXPECTED_MAIN[MP4] if t != REPEAT_CLASS_EXCLUDED]}, n, reasons)
            # Ein Klassen-Fehlschlag zählt nur auf API 29 und nur, wenn er AUSSCHLIESSLICH P4B-A10-SIZE ist.
            if api == 29 and is_size_only(rr) and not rr.problems:
                repeat_size_only = True
            else:
                reasons.append('%s failed for another reason than P4B-A10-SIZE: %s' % (n, [t for _, t in rr.failed] or 'unknown'))

    # 6) Unbekannte Fehlerzeilen des Jobs
    errors = [l for l in lines if l.startswith('##[error]')]
    allowed_errors = [SIZE_ERROR] if size_exception else []
    if errors != allowed_errors:
        reasons.append('job error lines %s (expected %s)' % ([e[:100] for e in errors], allowed_errors))

    # 7) Script-Status und Job-Ergebnis passen zum Ergebnis
    st = re.findall(r'(?m)^GATE-SCRIPT-STATUS (\d+)$', text)
    if len(st) == 1 and not reasons:
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
