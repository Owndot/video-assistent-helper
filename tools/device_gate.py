#!/usr/bin/env python3
"""Liefer-Gate für EINEN Emulator-Job von helper-channel.yml (vollständiges Job-Log als Datei).

Review P4 (Codex-Folgereview) und Codex Release-Readiness P2 (Vollständigkeit).

Aufruf:  device_gate.py JOB_LOG --api N [--job-conclusion success|failure] [--step-conclusions mutant=X,main=Y,repeat=Z]
Exit:    0 = PASS (alles grün, vollständig belegt)
         3 = ACCEPTED_SIZE_ONLY (nur API 29: genau der dokumentierte Befund P4B-A10-SIZE, sonst alles belegt grün)
         4 = INSUFFICIENT_EVIDENCE (kein Fehler belegt, aber nicht vollständig nachweisbar, z. B. altes Format der
             Wiederholungen ohne Einzelberichte) – nie PASS, nie Lieferfreigabe
         1 = REJECT (mit Gründen; auch jeder Parser-/Programmfehler des Gates)

Codex Release-Readiness 2: Das GESAMTE Log wird auf Abstürze (auch App-FATAL außerhalb des Crash-Abschnitts, OOM,
ANR, Instrumentierungsabbrüche) und Veröffentlichungsfehler geprüft. Ausgenommen sind nur der erwartete Mutantenlauf
(vollständig, genau ein Test, genau der erwartete Grund) und die ausdrücklichen Belegzeilen der Sonden. Statuszeilen
außerhalb der Abschnitte dürfen dem Hauptbericht nicht widersprechen. Jeder Abschnitt endet mit genau einem Runner-Ende,
ohne weitere oder unvollständige Blöcke. Alle 22 Wiederholungen werden einzeln mit Kennung und API geprüft.

Codex NO-GO 3: Der Mutantenlauf zählt nur mit GENAU der Ursache der manipulierten Markierungsbereinigung nach erfolgreicher
Veröffentlichung (MUTANT_EVIDENCE); jeder Veröffentlichungsfehler auch im Mutantenbereich ist REJECT. Protokollzeilen
außerhalb der Abschnitte müssen exakt die Kopie des Hauptberichts sein (raw_copy). Je Wiederholungsbericht genau ein
Kopf; PUBLISH_RACE-Zähler positiv mit Summe RACE_CYCLES; Crash-Puffer nach den 22 Wiederholungen erneut ausgelesen
(Format 3, Abschnitt repeat-crash, muss leer sein; Format 2 = INSUFFICIENT_EVIDENCE).

Runde 5 (Testisolation, P4-DELIVERY-READINESS.md): Der Job hat DREI Geräteschritte auf je einem eigenen, frisch erzeugten
Emulator: mutant (Mutantenprobe), main (Gerätetest, Sonden, Neustarts), repeat (20 Dreier- und 2 Klassen-Wiederholungen).
Jede Phase ist der Log-Bereich ab ihrem Gerätebefehl bis zum nächsten; jede Ausgabe muss in ihrer Phase stehen. Je Phase
Pflicht: genau ein DEVICE_READY, genau ein Frischenachweis (ISOLATION-FRESH: ein Gerät, beide Testpakete fehlen) vor
jeder Testausgabe, genau eine vollständige Zusammenfassung (ISOLATION-SUMMARY) mit zur Phase passenden Besitzbelegen
(ISOLATION-OWNER, Zeitfenster nachgerechnet); drei verschiedene Boot-IDs. Eine Verwaisung der Testpakete vor der
Installation oder während der Messung ist ein Isolationsbruch (REJECT); nach der eigenen Deinstallation ist sie erwartbar
und nur dokumentiert. Fehlerzeilen des Jobs werden je Phase geprüft; optional werden die Schrittergebnisse der GitHub-API
(--step-conclusions) gegen die Belege geprüft.

Bewertet wird gegen eine FESTE erwartete Testmenge (Quellstand der Gerätetests, siehe EXPECTED_*). Bewertet werden:
  * Gerätebefehle: genau die drei erwarteten Befehle in dieser Reihenfolge (Mutantenprobe, Gerätetest, 20 Dreier- und
    2 Klassen-Wiederholungen), jeder genau einmal, kein weiterer Gerätebefehl (auch nicht der frühere Sammelbefehl);
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
# Runde 5: drei Geräteschritte (helper-channel.yml), je ein frischer Emulator. Reihenfolge = PHASES.
PHASES = ('mutant', 'main', 'repeat')
COMMAND_PREFIX = '[command]/usr/bin/sh -c bash tools/'
DEVICE_COMMANDS = {
    'mutant': COMMAND_PREFIX + 'wait-device-ready.sh && bash tools/run-channel-publication-regression.sh "$RUNNER_TEMP/publication-mutant"',
    'main': COMMAND_PREFIX + 'wait-device-ready.sh && bash tools/run-channel-instrumentation.sh "$RUNNER_TEMP/a"',
    'repeat': COMMAND_PREFIX + 'wait-device-ready.sh && bash tools/run-flaky-repeat.sh "$RUNNER_TEMP/a" %d' % REPEAT_N,
}
# Früherer Sammelbefehl (alle drei Phasen auf EINEM Emulator): nie mehr zulässig (Isolationsbruch, A/B-Lauf 44405696921)
LEGACY_DEVICE_COMMAND = ('[command]/usr/bin/sh -c bash tools/wait-device-ready.sh && bash tools/run-channel-publication-regression.sh '
                         '"$RUNNER_TEMP/publication-mutant" && { bash tools/run-channel-instrumentation.sh "$RUNNER_TEMP/a"; s=$?; '
                         'bash tools/run-flaky-repeat.sh "$RUNNER_TEMP/a" %d && exit $s; }' % REPEAT_N)
TEST_PACKAGES = ('de.evp.helfer.channeltest', 'de.evp.helfer.channeltest.test')
_T = r'\d+\.\d+'
ISO_FRESH = re.compile(r'^ISOLATION-FRESH phase=(?P<phase>\S+) api=(?P<api>\d+) boot=(?P<boot>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) '
                       r'devices=1 listed=(?P<listed>[1-9]\d*) app=absent test=absent$')
ISO_OWNER = re.compile(r'^ISOLATION-OWNER phase=(?P<phase>\S+) api=(?P<api>\d+) t=(?P<t>' + _T + r') '
                       r'window=(?P<window>before-install|measurement|after-uninstall) '
                       r'event=(?:orphaned pkg=(?P<pkg>[A-Za-z0-9_.]+) count=(?P<count>\d+)|(?P<changed>owner-changed))$')
ISO_SUMMARY = re.compile(r'^ISOLATION-SUMMARY phase=(?P<phase>\S+) api=(?P<api>\d+) boot=(?P<boot>\S+) begin=(?P<begin>\S+) install=(?P<install>\S+) '
                         r'uninstall=(?P<uninstall>\S+) end=(?P<end>\S+) stream=(?P<stream>complete|incomplete) '
                         r'orphan_before=(?P<orphan_before>\d+) orphan_measurement=(?P<orphan_measurement>\d+) orphan_after=(?P<orphan_after>\d+) '
                         r'owner_changed_before=(?P<owner_changed_before>\d+) owner_changed_measurement=(?P<owner_changed_measurement>\d+) '
                         r'owner_changed_after=(?P<owner_changed_after>\d+)$')
WINDOWS = {'before-install': 'before', 'measurement': 'measurement', 'after-uninstall': 'after'}
STEP_RESULTS = ('success', 'failure')
SIZE_ERROR = "##[error]The process '/usr/bin/sh' failed with exit code 1"
JOB_END = 'Cleaning up orphan processes'

SIZE_TEST = 'publishesThroughMediaStoreAndReadsBack'
SIZE_RE = re.compile(r'AssertionError: SIZE column \(file [^)]*, length (\d+)\) expected:<\1> but was:<0>')
PUBLISH_FAILURE = re.compile(r'publish rows=|publish threw=|IOException: publish|publish result \(cause|failed:storag(?:e(?!\w)|\]e)')
SYSTEM_CRASH = re.compile(r'FATAL EXCEPTION|DeadSystemException|DeadObjectException|System has crashed|Process crashed'
                          r'|INSTRUMENTATION_ABORTED|INSTRUMENTATION_FAILED|Watchdog|system_server.*died|Fatal signal'
                          r'|ANR in |shortMsg=|INSTRUMENTATION_STATUS: Error=|OutOfMemoryError')
# Codex Release-Readiness 2: im GESAMTEN Log (nicht nur in Abschnitten) nie zulässig
CRASH_ANYWHERE = re.compile(r'FATAL EXCEPTION|Fatal signal \d|ANR in |Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_FAILED'
                            r'|DeadSystemException|DeadObjectException|System has crashed|shortMsg=|backtrace:|OutOfMemoryError'
                            r'|INSTRUMENTATION_STATUS: Error=|system_server.*died')
# Erwartete Belegzeilen der Veröffentlichungssonden, die absichtlich "IOException: publish …" enthalten
PROBE_EVIDENCE_LINE = re.compile(r'(^|: )(PUBLISH_PROBE_ATTRIBUTION|PUBLISH_PROBE_SIGNATURE|PUBLISH_RACE) sdk=\d+ ')
MUTANT_REASON = 'published file is successful despite marker failure'
# Codex NO-GO 3 (P1): Der Mutantennachweis gilt nur, wenn die Ursache GENAU die absichtlich manipulierte nachgelagerte
# Bereinigung der Besitzmarkierung ist. Diese Ursache entsteht im Produktcode ausschließlich NACH dem erfolgreichen
# Veröffentlichen (Mp4Downloads: "finished = true; cleanupPublishedProof(proof)" nach rows == 1 bzw. nach dem Verschieben
# unter API 29). "last abort" leer = kein Abbruch/Rückbau nach dem Veröffentlichen; "store" leer = kein Fehler der Ablage.
# Eine bloß passende Beschriftung, "failed:storage" oder Code -2 genügt nicht; "publish rows=0" o. ä. ist immer REJECT.
MUTANT_EVIDENCE = re.compile(r'(^|INSTRUMENTATION_STATUS: stack=)org\.junit\.ComparisonFailure: published file is successful despite marker '
                             r'failure \(cause: IOException: Cannot remove pending download ownership; last abort: ; store: ; '
                             r'downloadsInitialized: (true|false); now=\d+ms; cleanups: \[\]\) expected:<\[don\]e> but was:<\[failed:storag\]e>$')
# Veröffentlichungsfehler ohne die erwartete Beschriftung des Mutanten (failed:storage steht auch in dessen Beleg)
PUBLISH_CAUSE = re.compile(r'publish rows=|publish threw=|IOException: publish|publish result \(cause|MP4_STORE_FAILURE')
REPEAT_FORMAT = 'REPEAT-FORMAT 3'   # 3: zusätzlich Crash-Puffer NACH den 22 Wiederholungen (Abschnitt repeat-crash)
REPEAT_FORMAT_OLD = {'REPEAT-FORMAT 2'}
REPEAT_CRASH = 'repeat-crash'
# Runde 5: Crash-Puffer der Phase main nach Deinstallation und Persistenzprüfung (früher Teil von repeat-crash)
CRASH_AFTER_UNINSTALL = 'crash-after-uninstall'
RACE_CYCLES = 30   # MediaStorePublishProbeTest.RACE_CYCLES: Summe aller PUBLISH_RACE-Zähler
# Kopien des Hauptberichts außerhalb der Abschnitte (tools/run-channel-instrumentation.sh): exakt nachgebildet
SUMMARY_GREP = re.compile(r'INSTRUMENTATION_STATUS: (test|class)=|INSTRUMENTATION_STATUS_CODE|^OK \(|FAILURES|Tests run|AssertionError|Exception')
RESULT_GREP = re.compile(r'INSTRUMENTATION_RESULT|INSTRUMENTATION_FAILED|shortMsg|longMsg|Process crashed')
CODE_FAIL = {'-1', '-2'}   # AndroidJUnitRunner: -1 Fehler, -2 Fehlschlag (-3 ignoriert, -4 Annahme nicht erfüllt)


def strip(line):
    # Zeitstempel des Actions-Logs und BOM entfernen
    return re.sub(r'^﻿?\d{4}-\d\d-\d\dT[\d:.]+Z ', '', line.rstrip('\r').lstrip('﻿'))


def protocol(l):
    """Zeile des am-instrument-Protokolls (auch als FAILED-Kopie)."""
    return l.startswith('INSTRUMENTATION_') or l.startswith('FAILED ')


def raw_copy(main, appended):
    """Exakte Nachbildung der Protokollzeilen, die tools/run-channel-instrumentation.sh aus dem Hauptbericht
    (instrumentation.txt) außerhalb der Abschnitte ins Job-Log schreibt: tail -5 summary.txt (grep-Auszug + `appended`
    angehängte Zeilen), FAILED-Auszüge (awk, je 14 Zeilen ab stack=, head -150), Runner-Ergebnis (grep, head -20),
    Fehlerliste (awk, head -60). Nur Protokollzeilen werden verglichen."""
    summary = [l for l in main if SUMMARY_GREP.search(l)] + ['<appended>'] * appended
    out = summary[-5:]
    failed, t, n = [], '', 0
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
    out += [l for l in main if RESULT_GREP.search(l)][:20]
    there, p, n = [], False, 0
    for l in main:
        if re.match(r'^There (was|were) [0-9]+ failure', l):
            p = True
        if p and re.match(r'^[0-9]+\) ', l):
            n = 6
        if p and n > 0:
            there.append(l)
            n -= 1
    out += there[:60]
    return [l.rstrip() for l in out if protocol(l)]


def mutant_problems(lines, lo, hi):
    """Gründe, warum lines[lo:hi] KEIN gültiger Mutantennachweis ist (leer = gültig). Gemeinsam genutzt vom Gate und
    von failure_evidence() (abgebrochene Jobs), damit beide denselben Maßstab anlegen."""
    out = []
    mut = Report(lines[lo:hi])
    if mut.tests() != [(MP4, MUTANT_TEST)] or [t for _, t in mut.failed] != [MUTANT_TEST] or mut.failures != 1:
        out.append('mutant run is not exactly the expected single failing test %s' % MUTANT_TEST)
    for p in mut.problems:
        out.append('mutant report: %s' % p)
    if (MP4, MUTANT_TEST) in mut.final:
        code, st = mut.final[(MP4, MUTANT_TEST)]
        if code != '-2' or not MUTANT_EVIDENCE.search(st.split('\n')[0]):
            out.append('mutant failed for another reason than the deliberately broken marker cleanup after a successful '
                       'publication (code %s): %s' % (code, st.split('\n')[0][:160]))
    if SYSTEM_CRASH.search(mut.text):
        out.append('crash in the mutant run: %s' % SYSTEM_CRASH.search(mut.text).group(0))
    # Jede Zeile des Mutantenbereichs mit Veröffentlichungsfehler-Merkmal muss genau der Mutantenbeleg sein
    for j in range(lo, hi):
        l = lines[j]
        if (PUBLISH_FAILURE.search(l) or PUBLISH_CAUSE.search(l)) and not MUTANT_EVIDENCE.search(l):
            out.append('unexpected publish failure inside the mutant run (line %d): %s' % (j + 1, l.strip()[:120]))
    return out


def scan_failures(lines, exempt=range(0)):
    """Strukturierte Fehlerbelege an JEDER Stelle des Logs außer `exempt` (geprüfter Mutantenbereich):
    -> [(Art, Zeilenindex, Zeile)], Art in crash | publish. Belegzeilen der Veröffentlichungssonden sind ausgenommen."""
    out = []
    for i, l in enumerate(lines):
        if i in exempt:
            continue
        if CRASH_ANYWHERE.search(l):
            out.append(('crash', i, l))
        if (PUBLISH_FAILURE.search(l) or PUBLISH_CAUSE.search(l)) and not PROBE_EVIDENCE_LINE.search(l):
            out.append(('publish', i, l))
    return out


def failure_evidence(log, api):
    """Codex NO-GO 3 (P1): EINE Fehlerklassifikation für abgebrochene/unvollständige Logs (tools/delivery/ci_evidence.py),
    mit denselben Mustern wie das Gate. -> [(Art, Zeile Nr., Text)]. Arten:
      publish      Veröffentlichungsfehler (publish rows=/threw=, IOException: publish, MP4_STORE_FAILURE, failed:storage)
      crash        Absturz/Abbruch/OOM
      test-failure gescheiterter Test (Code -1/-2) oder Fehlerzusammenfassung ohne zuordenbaren Test
      size         NUR API 29: der dokumentierte Befund P4B-A10-SIZE (kein neuer Produktfehler, aber auch kein Beleg)
    Ausgenommen ist nur ein vollständig und streng gültiger Mutantenlauf (mutant_problems() leer)."""
    lines = [strip(l) for l in log.splitlines()]
    exempt = range(0)
    ready = [i for i, l in enumerate(lines) if re.match(r'^DEVICE_READY after \d+ s$', l)]
    if ready:
        lo = ready[0] + 1
        end = next((i for i in range(lo, len(lines)) if lines[i] == MUTANT_PASS), None)
        if end is None:   # Abbruch nach dem Mutantenlauf, vor der PASS-Zeile: Bericht bis zum Runner-Ende
            code_end = next((i for i in range(lo, len(lines)) if lines[i].startswith('INSTRUMENTATION_CODE:')), None)
            end = None if code_end is None else code_end + 1
        if end is not None and not mutant_problems(lines, lo, end):
            exempt = range(lo - 1, end + 1)
    out = [(k, i + 1, l.strip()[:160]) for k, i, l in scan_failures(lines, exempt)]
    failing, test, stack = [], None, None
    for i, l in enumerate(lines):
        if i in exempt:
            continue
        l = l[len('FAILED '):] if l.startswith('FAILED ') else l
        m = re.match(r'^INSTRUMENTATION_STATUS: test=(\S+)$', l)
        if m:
            test = m.group(1)
        if l.startswith('INSTRUMENTATION_STATUS: stack='):
            stack = l
        m = re.match(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)$', l)
        if m:
            if m.group(1) in CODE_FAIL:
                failing.append((i, test, stack))
            test, stack = None, None
    for i, t, st in failing:
        size = api == 29 and t == SIZE_TEST and st is not None and SIZE_RE.search(st) and not PUBLISH_FAILURE.search(st)
        out.append(('size' if size else 'test-failure', i + 1, '%s: %s' % (t, (st or 'no stack')[:120])))
    if not failing:
        for i, l in enumerate(lines):
            if i not in exempt and re.match(r'^(FAILURES!!!|Tests run: \d+,\s+Failures: [1-9]\d*)$', l):
                out.append(('test-failure', i + 1, 'failure summary without an identifiable failing test: ' + l))
                break
    return sorted(out, key=lambda x: x[1])


BLOCKING = ('publish', 'crash', 'test-failure')


def phase_regions(lines):
    """Runde 5: Bereich jeder Gerätephase = ab ihrem Gerätebefehl bis vor den nächsten (letzte Phase: bis Log-Ende).
    -> ({Phase: (lo, hi)} nur bei vollständiger, eindeutiger und geordneter Befehlsfolge, sonst {}), [Gründe]."""
    reasons = []
    cmds = [(i, l) for i, l in enumerate(lines) if l.startswith(COMMAND_PREFIX)]
    want = [DEVICE_COMMANDS[p] for p in PHASES]
    got = [l for _, l in cmds]
    if any(l == LEGACY_DEVICE_COMMAND for l in got):
        reasons.append('legacy device command: all phases on ONE emulator (not isolated)')
    for p in PHASES:
        n = got.count(DEVICE_COMMANDS[p])
        if n != 1:
            reasons.append('device command of phase %s: expected exactly one, found %d' % (p, n))
    unknown = [l for l in got if l not in want]
    if unknown:
        reasons.append('unexpected device command(s): %s' % [l[len(COMMAND_PREFIX) - len('bash tools/'):][:90] for l in unknown])
    if got != want:
        if not reasons:
            reasons.append('device phases out of order (expected %s)' % ' -> '.join(PHASES))
        else:
            reasons.append('device phases missing, repeated or out of order (expected %s)' % ' -> '.join(PHASES))
        return {}, reasons
    idx = [i for i, _ in cmds] + [len(lines)]
    return {p: (idx[k], idx[k + 1]) for k, p in enumerate(PHASES)}, reasons


def isolation_problems(lines, regions, api, ready):
    """Runde 5: Frischenachweis, Besitzbelege und Zusammenfassung je Phase (fail closed). ready: {Phase: DEVICE_READY}."""
    out = []
    found = {p: {'fresh': [], 'owner': [], 'summary': []} for p in PHASES}
    for i, l in enumerate(lines):
        if 'ISOLATION-' not in l:
            continue
        if l.startswith('ISOLATION-FAILED '):
            out.append('isolation precondition failed (line %d): %s' % (i + 1, l[:120]))
            continue
        for kind, rx in (('fresh', ISO_FRESH), ('owner', ISO_OWNER), ('summary', ISO_SUMMARY)):
            m = rx.match(l)
            if m:
                if m.group('phase') not in found:
                    out.append('isolation evidence of an unknown phase (line %d): %s' % (i + 1, l[:120]))
                else:
                    found[m.group('phase')][kind].append((i, m))
                break
        else:
            out.append('malformed or unexpected isolation line %d: %s' % (i + 1, l[:120]))
    boots = {}
    for p in PHASES:
        f = found[p]
        if p not in regions:
            continue
        lo, hi = regions[p]
        if len(f['fresh']) != 1:
            out.append('freshness evidence of phase %s: expected exactly one ISOLATION-FRESH, found %d' % (p, len(f['fresh'])))
            continue
        fi, fm = f['fresh'][0]
        if not (lo < fi < hi) or ready.get(p) is None or not (ready[p] < fi):
            out.append('freshness evidence of phase %s outside its phase or before its DEVICE_READY' % p)
        if int(fm.group('api')) != api:
            out.append('freshness evidence of phase %s is API %s, gate asked for API %d' % (p, fm.group('api'), api))
        test_out = [j for j in range(lo, hi) if lines[j].startswith(('INSTRUMENTATION_', 'GATE-', 'REPEAT-', 'PUBLISH_PROBE', 'PUBLISH_RACE'))]
        if test_out and test_out[0] < fi:
            out.append('phase %s: test output (line %d) before the freshness evidence' % (p, test_out[0] + 1))
        boots[p] = fm.group('boot')
        if len(f['summary']) != 1:
            out.append('isolation summary of phase %s: expected exactly one ISOLATION-SUMMARY, found %d' % (p, len(f['summary'])))
            continue
        si, sm = f['summary'][0]
        if not (fi < si < hi):
            out.append('isolation summary of phase %s outside its phase or before its freshness evidence' % p)
        if int(sm.group('api')) != api or sm.group('boot') != fm.group('boot'):
            out.append('isolation summary of phase %s contradicts its freshness evidence (API %s, boot %s)' % (p, sm.group('api'), sm.group('boot')))
        if sm.group('stream') != 'complete':
            out.append('isolation evidence of phase %s incomplete (MediaProvider/marker stream not complete)' % p)
            continue
        try:
            t = {k: float(sm.group(k)) for k in ('begin', 'install', 'uninstall', 'end')}
        except ValueError:
            out.append('isolation summary of phase %s: unreadable time window' % p)
            continue
        if not (t['begin'] <= t['install'] <= t['uninstall'] <= t['end']):
            out.append('isolation summary of phase %s: time window not monotone %s' % (p, t))
            continue
        orphan = {'before': 0, 'measurement': 0, 'after': 0}
        changed = dict(orphan)
        for oi, om in f['owner']:
            if not (fi < oi < si) or int(om.group('api')) != api:
                out.append('isolation owner evidence of phase %s outside its window in the log or of another API (line %d)' % (p, oi + 1))
                continue
            ts = float(om.group('t'))
            want = 'before' if ts < t['install'] else 'measurement' if ts < t['uninstall'] else 'after'
            if WINDOWS[om.group('window')] != want:
                out.append('isolation owner evidence of phase %s: time %s lies in window %s, labelled %s (line %d)' % (p, om.group('t'), want, om.group('window'), oi + 1))
            if om.group('changed'):
                changed[want] += 1
            elif om.group('pkg') in TEST_PACKAGES:
                orphan[want] += 1
        for w in ('before', 'measurement', 'after'):
            if int(sm.group('orphan_' + w)) != orphan[w] or int(sm.group('owner_changed_' + w)) != changed[w]:
                out.append('isolation summary of phase %s: counts for window %s do not match the owner evidence (summary orphan %s/changed %s, evidence %d/%d)'
                           % (p, w, sm.group('orphan_' + w), sm.group('owner_changed_' + w), orphan[w], changed[w]))
        for w, why in (('before', 'before the installation'), ('measurement', 'during the measurement')):
            if orphan[w] or int(sm.group('orphan_' + w)):
                out.append('stale package removal: MediaProvider orphaned rows of the test package %s in phase %s - not isolated' % (why, p))
    if len(boots) == len(PHASES) and len(set(boots.values())) != len(PHASES):
        out.append('device phases did not run on separate fresh emulators (boot ids %s)' % boots)
    return out


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
        if block:
            self.problems.append('incomplete status block at the end (keys %s)' % sorted(block))
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
        codes = [i for i, l in enumerate(lines) if l.startswith('INSTRUMENTATION_CODE:')]
        if len(codes) != 1:
            self.problems.append('expected exactly one runner end code, found %d' % len(codes))
        elif any(l.strip() for l in lines[codes[0] + 1:]):
            self.problems.append('output after the runner end (another or incomplete instrumentation block)')
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


def parse_steps(text):
    """'mutant=success,main=failure,repeat=success' -> {Phase: Ergebnis}; jede Abweichung -> ValueError (fail closed)."""
    out = {}
    for part in text.split(','):
        k, sep, v = part.partition('=')
        if not sep or k not in PHASES or k in out or not v:
            raise ValueError('step conclusions %r: expected %s' % (text, ','.join('%s=<result>' % p for p in PHASES)))
        out[k] = v
    if sorted(out) != sorted(PHASES):
        raise ValueError('step conclusions %r: expected all of %s' % (text, list(PHASES)))
    return out


def evaluate(log, api, job_conclusion=None, step_conclusions=None):
    """Jeder Parser- oder Programmfehler führt zur Ablehnung (fail closed). step_conclusions: {Phase: Ergebnis} der
    GitHub-API (tools/delivery/ci_evidence.py) oder None."""
    try:
        return _evaluate(log, api, job_conclusion, step_conclusions)
    except Exception as e:  # noqa: BLE001 - absichtlich: nie ein PASS durch einen Fehler im Gate
        return 'REJECT', ['gate parser error: %s: %s' % (type(e).__name__, e)]


def _evaluate(log, api, job_conclusion=None, step_conclusions=None):
    lines = [strip(l) for l in log.splitlines()]
    text = '\n'.join(lines)
    reasons, size_exception, repeat_size_only, insufficient = [], False, False, []
    if api not in APIS:
        return 'REJECT', ['API %d is not a gated emulator level %s' % (api, list(APIS))]
    secs, dup, open_sec, pos = sections(lines)
    if dup:
        reasons.append('section reported twice or nested: %s' % dup)
    if open_sec:
        reasons.append('section not terminated (truncated log): %s' % open_sec)
    allowed = {'main', 'restart-summary', 'crash', CRASH_AFTER_UNINSTALL} | set(RESTARTS) | ({'probe', 'probe-lines'} if api >= 29 else set())
    allowed |= {'repeat-three-%d' % i for i in range(1, REPEAT_N + 1)} | {'repeat-class-%d' % i for i in range(1, REPEAT_CLASS_RUNS + 1)}
    allowed |= {REPEAT_CRASH}
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

    # 0) Vollständiges Log, genau die drei erwarteten Gerätebefehle (Runde 5: je ein frischer Emulator), Reihenfolge
    regions, why = phase_regions(lines)
    reasons += why
    i_mut = idx(r'^' + re.escape(MUTANT_PASS) + r'$', 'expected mutant failure (publication regression)')
    i_status = idx(r'^PUBLISH_PROBE_STATUS \S+$', 'PUBLISH_PROBE_STATUS line')
    i_api = idx(r'^GATE-API \d+$', 'GATE-API line')
    i_script = idx(r'^GATE-SCRIPT-STATUS \d+$', 'GATE-SCRIPT-STATUS line')
    i_rep = idx(r'^REPEAT api=\d+ runs=\d+ passed=\d+ failed=\d+$', 'REPEAT result line')
    i_end = idx(r'^' + re.escape(JOB_END) + r'$', 'job end (complete job log)')
    readies = [i for i, l in enumerate(lines) if re.match(r'^DEVICE_READY after \d+ s$', l)]
    ready = {}
    if regions:
        for ph in PHASES:
            lo, hi = regions[ph]
            hits = [i for i in readies if lo < i < hi]
            if len(hits) != 1:
                reasons.append('DEVICE_READY of phase %s: expected exactly one, found %d' % (ph, len(hits)))
            else:
                ready[ph] = hits[0]
        if len(readies) != len(PHASES):
            reasons.append('DEVICE_READY lines: expected %d, found %d' % (len(PHASES), len(readies)))

        def inside(i, ph):
            return i is not None and regions[ph][0] < i < regions[ph][1]
        for i, ph, what in ((i_mut, 'mutant', 'mutant PASS line'), (i_status, 'main', 'PUBLISH_PROBE_STATUS'), (i_api, 'main', 'GATE-API'),
                            (i_script, 'main', 'GATE-SCRIPT-STATUS'), (i_rep, 'repeat', 'REPEAT result line'), (i_end, 'repeat', 'job end')):
            if i is not None and not inside(i, ph):
                reasons.append('%s outside device phase %s' % (what, ph))
        late = sorted(n for n, q in pos.items() if not n.startswith('repeat-') and not inside(q, 'main'))
        early = sorted(n for n, q in pos.items() if n.startswith('repeat-') and not inside(q, 'repeat'))
        if late or early:
            reasons.append('sections in the wrong phase: %s' % (late + early))
    order = [ready.get('mutant'), i_mut, ready.get('main'), i_status, i_api, pos.get('main'), i_script, ready.get('repeat'), i_rep, i_end]
    if None not in order and order != sorted(order):
        reasons.append('device output out of order (ready, mutant, ready, probe status, GATE-API, main, script status, ready, repeats, end)')
    if regions:
        reasons += isolation_problems(lines, regions, api, ready)
        iso_sum = {m.group('phase'): i for i, l in enumerate(lines) for m in [ISO_SUMMARY.match(l)] if m}
        if None not in (iso_sum.get('main'), i_status, i_api) and not (i_status < iso_sum['main'] < i_api):
            reasons.append('isolation summary of phase main not between the probe status and GATE-API')
        if None not in (iso_sum.get('repeat'), i_rep) and pos.get(REPEAT_CRASH) is not None and not (pos[REPEAT_CRASH] < iso_sum['repeat'] < i_rep):
            reasons.append('isolation summary of phase repeat not between the crash report after the repeats and the REPEAT line')
    if i_api is not None:
        m = re.match(r'^GATE-API (\d+)$', lines[i_api])
        if int(m.group(1)) != api:
            reasons.append('log is API %s, gate asked for API %d' % (m.group(1), api))

    # 0b) Mutantenprobe (eigene Phase vor dem Gerätetest, getrennt bewertet): genau der erwartete Fehlschlag
    i_ready = ready.get('mutant')
    if i_ready is not None and i_mut is not None and i_ready < i_mut:
        reasons += mutant_problems(lines, i_ready + 1, i_mut)

    # 0c) GESAMTES Log: Abstürze und Veröffentlichungsfehler an jeder Stelle (Codex Release-Readiness 2). Ausgenommen
    #     sind nur der erwartete Mutantenlauf und die ausdrücklichen Belegzeilen der Veröffentlichungssonden.
    mut_lo, mut_hi = (i_ready, i_mut) if (i_ready is not None and i_mut is not None and i_ready < i_mut) else (-1, -1)
    # einschließlich der PASS-Zeile der Mutantenprobe; der Bereich selbst wurde oben streng geprüft (mutant_problems)
    for kind, i, l in scan_failures(lines, range(mut_lo + 1, mut_hi + 1)):
        reasons.append('%s evidence in the job log (line %d): %s' % ('crash/abort' if kind == 'crash' else 'publish failure', i + 1, l.strip()[:120]))

    # 0d) Instrumentierungsausgabe außerhalb der Abschnitte: nur Kopien des Hauptlaufs (Zusammenfassung, FAILED-Auszug)
    in_sec = set()
    for n, p in pos.items():
        e = next((j for j in range(p + 1, len(lines)) if lines[j] == 'GATE-END ' + n), len(lines))
        in_sec.update(range(p, e + 1))
    raw = [(i, l[len('FAILED '):] if l.startswith('FAILED ') else l) for i, l in enumerate(lines)
           if i not in in_sec and not (mut_lo <= i <= mut_hi)]
    raw_codes = [l for i, l in raw if l.startswith('INSTRUMENTATION_CODE:')]
    if raw_codes:
        reasons.append('instrumentation run end outside the delimited reports (%d)' % len(raw_codes))
    raw_events, cur = [], {}
    for i, l in raw:
        m = re.match(r'^INSTRUMENTATION_STATUS: (class|test)=(\S+)$', l)
        if m:
            cur[m.group(1)] = m.group(2)
        m = re.match(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)$', l)
        if m:
            raw_events.append((cur.get('test'), m.group(1)))
            cur = {}
    # Codex NO-GO 3: Protokollzeilen außerhalb der Abschnitte müssen EXAKT die deterministische Kopie des Hauptberichts
    # sein (keine zusätzlichen, unvollständigen oder widersprüchlichen Blöcke, z. B. ein "-4" für einen grünen Test).
    raw_protocol = [lines[i].rstrip() for i, l in enumerate(lines) if i not in in_sec and not (mut_lo <= i <= mut_hi) and protocol(lines[i])]
    if secs.get('main') is not None:
        appended = 1 + sum(1 for l in lines if l in ('LISTENER ON 47811', 'package list changed'))
        want_copy = raw_copy(secs['main'], appended)
        if raw_protocol != want_copy:
            k = next((x for x in range(max(len(raw_protocol), len(want_copy)))
                      if x >= len(raw_protocol) or x >= len(want_copy) or raw_protocol[x] != want_copy[x]), 0)
            reasons.append('instrumentation output outside the reports is not exactly the copy of the main report '
                           '(%d lines, expected %d; first difference: %r)' % (len(raw_protocol), len(want_copy),
                                                                              (raw_protocol[k] if k < len(raw_protocol) else '<missing>')[:100]))

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
        finals = {t: c for (_, t), (c, _) in rep.final.items()}
        for t_, c in raw_events:
            if t_ is None and c not in CODE_FAIL:
                continue   # Bruchstück der gekürzten Zusammenfassung (tail) ohne Testnamen, kein Fehlschlag
            if t_ is None or t_ not in finals:
                reasons.append('instrumentation status outside the reports for an unknown test: %s' % t_)
            elif c in CODE_FAIL and finals[t_] != c:
                reasons.append('failure of %s outside the reports contradicts the main report' % t_)

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
                elif n > 1 and not need.startswith('PUBLISH_RACE'):
                    reasons.append('publish probe evidence reported %d times (expected once): %s' % (n, need.format(api=api).strip()))
            # Codex NO-GO 3: PUBLISH_RACE-Zähler müssen positiv sein und sich zu genau RACE_CYCLES addieren
            race = [l for l in plines if l.startswith('PUBLISH_RACE ')]
            rm = [re.match(r'^PUBLISH_RACE sdk=(\d+) count=(\d+) outcome=(\S.*)$', l) for l in race]
            if not race or not all(rm):
                reasons.append('publish race evidence missing or unreadable: %s' % [l[:80] for l in race if not re.match(r'^PUBLISH_RACE sdk=\d+ count=\d+ outcome=\S', l)][:2])
            else:
                counts = [int(m.group(2)) for m in rm]
                outcomes = [m.group(3) for m in rm]
                if min(counts) < 1 or sum(counts) != RACE_CYCLES or len(set(outcomes)) != len(outcomes):
                    reasons.append('publish race counts %s (expected positive counts of distinct outcomes summing to %d)' % (counts, RACE_CYCLES))
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
    after = secs.get(CRASH_AFTER_UNINSTALL)
    if after is None:
        reasons.append('crash buffer report after the uninstall of the main phase missing (%s)' % CRASH_AFTER_UNINSTALL)
    elif any(l.strip() for l in after):
        reasons.append('crash buffer not empty after the uninstall of the main phase: %s' % next(l for l in after if l.strip())[:120])
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

    # 5) Wiederholungen: genau diese API, 22 Läufe (20 Dreier + 2 Klassen). Format 2 (Codex Release-Readiness 2): JEDER
    #    Lauf als eigener Bericht mit Kennung und API; altes Format (nur Fehlschläge belegt) ist keine ausreichende Evidenz.
    rep_l = re.findall(r'(?m)^REPEAT api=(\d+) runs=(\d+) passed=(\d+) failed=(\d+)$', text)
    rsecs = {n: b for n, b in secs.items() if n.startswith('repeat-') and n != REPEAT_CRASH}
    fmt = [i for i, l in enumerate(lines) if l == REPEAT_FORMAT or l in REPEAT_FORMAT_OLD]
    want_labels = ['three-%d' % i for i in range(1, REPEAT_N + 1)] + ['class-%d' % i for i in range(1, REPEAT_CLASS_RUNS + 1)]
    class_set = {MP4: [t for t in EXPECTED_MAIN[MP4] if t != REPEAT_CLASS_EXCLUDED]}
    if len(fmt) > 1:
        reasons.append('repeat format marker reported %d times' % len(fmt))
    if not fmt:
        insufficient.append('repeat evidence in the old format: successful repeat runs are not reported individually')
    elif i_script is not None and i_rep is not None and not (i_script < fmt[0] < i_rep):
        reasons.append('repeat format marker out of order')
    # Format 3: Crash-Puffer nach ALLEN Wiederholungen erneut ausgelesen (umfasst Hauptlauf, Sonden, Neustarts und die 22
    # Wiederholungen seit dem logcat -c des Gerätetests); Format 2 hat diesen Beleg nicht -> nie PASS.
    if fmt and lines[fmt[0]] in REPEAT_FORMAT_OLD:
        insufficient.append('crash buffer was not read again after the %d repeat runs (repeat format 2)' % REPEAT_RUNS)
    elif fmt:
        rc = secs.get(REPEAT_CRASH)
        if rc is None:
            reasons.append('crash buffer report after the repeat runs missing (%s)' % REPEAT_CRASH)
        else:
            if any(l.strip() for l in rc):
                reasons.append('crash buffer not empty after the repeat runs: %s' % next(l for l in rc if l.strip())[:120])
            last_repeat = max([pos[n] for n in rsecs] or [-1])
            if not (last_repeat < pos[REPEAT_CRASH] and (i_rep is None or pos[REPEAT_CRASH] < i_rep)):
                reasons.append('crash buffer report after the repeat runs out of order')
    if len(rep_l) == 1:
        rapi, runs, passed, failed = (int(x) for x in rep_l[0])
        if rapi != api:
            reasons.append('repeat result is API %d, job is API %d' % (rapi, api))
        if runs != REPEAT_RUNS or passed + failed != runs:
            reasons.append('repeat runs %d (passed %d, failed %d), expected %d (%d three-test + %d class runs)'
                           % (runs, passed, failed, REPEAT_RUNS, REPEAT_N, REPEAT_CLASS_RUNS))
        if fmt and sorted(rsecs) != sorted('repeat-' + x for x in want_labels):
            missing = [x for x in want_labels if 'repeat-' + x not in rsecs]
            reasons.append('repeat reports incomplete: missing %s, unexpected %s'
                           % (missing, sorted(set(rsecs) - {'repeat-' + x for x in want_labels})))
        if not fmt and len(rsecs) != failed:
            reasons.append('repeat failed %d times but %d failure reports in the log' % (failed, len(rsecs)))
        n_ok = n_fail = 0
        for n, body in sorted(rsecs.items()):
            label = n[len('repeat-'):]
            if fmt:
                head = [l for l in body if l.strip()][:1]
                if head != ['REPEAT-RUN label=%s api=%d' % (label, api)]:
                    reasons.append('%s: missing or wrong run header %s (expected label %s, API %d)' % (n, head[:1], label, api))
                hdrs = [l for l in body if 'REPEAT-RUN' in l]
                if len(hdrs) != 1:
                    reasons.append('%s: %d run headers in one repeat report (expected exactly one)' % (n, len(hdrs)))
                body = [l for l in body if not l.startswith('REPEAT-RUN ')]
            rr = Report(body)
            expect_tests(rr, {MP4: REPEAT_THREE} if label.startswith('three-') else class_set, n, reasons)
            if rr.ok and not rr.failed:
                n_ok += 1
                continue
            n_fail += 1
            if label.startswith('three-'):
                reasons.append('%s failed: %s' % (n, [t for _, t in rr.failed] or 'unknown'))
            elif api == 29 and is_size_only(rr) and not rr.problems:
                # Ein Klassen-Fehlschlag zählt nur auf API 29 und nur, wenn er AUSSCHLIESSLICH P4B-A10-SIZE ist.
                repeat_size_only = True
            else:
                reasons.append('%s failed for another reason than P4B-A10-SIZE: %s' % (n, [t for _, t in rr.failed] or 'unknown'))
        if fmt and (n_ok, n_fail) != (passed, failed):
            reasons.append('repeat reports show %d passed / %d failed, summary says %d / %d' % (n_ok, n_fail, passed, failed))

    # 6) Fehlerzeilen des Jobs JE PHASE (Runde 5: jeder Geräteschritt meldet sein eigenes Ergebnis). Erlaubt ist nur der
    #    Exit-Fehler des Hauptlaufs bzw. der Wiederholungen bei genau dem dokumentierten Befund P4B-A10-SIZE (API 29).
    allowed_errors = {'before': [], 'mutant': [], 'main': [SIZE_ERROR] if size_exception else [],
                      'repeat': [SIZE_ERROR] if repeat_size_only else []}
    errors = {k: [] for k in allowed_errors}
    for i, l in enumerate(lines):
        if l.startswith('##[error]'):
            ph = next((q for q in PHASES if regions and regions[q][0] < i < regions[q][1]), 'before')
            errors[ph].append(l)
    if errors != allowed_errors:
        reasons.append('job error lines per phase %s (expected %s)' % ({k: [e[:80] for e in v] for k, v in errors.items() if v},
                                                                       {k: v for k, v in allowed_errors.items() if v}))

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
    # Runde 5: Schrittergebnisse der GitHub-API müssen genau zu den Belegen der Phasen passen (sonst widersprüchlich)
    if step_conclusions is not None:
        want_steps = {'mutant': 'success', 'main': 'failure' if size_exception else 'success',
                      'repeat': 'failure' if repeat_size_only else 'success'}
        if any(step_conclusions.get(p) not in STEP_RESULTS for p in PHASES):
            reasons.append('device step not completed (skipped, cancelled or missing): %s' % step_conclusions)
        elif not reasons and step_conclusions != want_steps:
            reasons.append('step conclusions %s contradict the evidence (expected %s)' % (step_conclusions, want_steps))
        if job_conclusion is not None and (job_conclusion == 'success') != all(step_conclusions.get(p) == 'success' for p in PHASES):
            reasons.append('job conclusion %s contradicts the step conclusions %s' % (job_conclusion, step_conclusions))
    if repeat_size_only and not size_exception:
        reasons.append('repeat runs failed by SIZE but the main run did not show the documented SIZE finding')

    if reasons:
        return 'REJECT', reasons
    if insufficient:
        # Kein Fehler belegt, aber nicht vollständig nachweisbar: nie PASS, nie ACCEPTED (Exit 4)
        return 'INSUFFICIENT_EVIDENCE', insufficient
    return ('ACCEPTED_SIZE_ONLY' if size_exception else 'PASS'), []


def main():
    a = argparse.ArgumentParser()
    a.add_argument('log')
    a.add_argument('--api', type=int, required=True)
    a.add_argument('--job-conclusion', choices=['success', 'failure'])
    a.add_argument('--step-conclusions', help='Runde 5: Ergebnisse der drei Geräteschritte, z. B. mutant=success,main=success,repeat=success')
    a.add_argument('--failure-evidence', action='store_true',
                   help='nur Fehlerbelege klassifizieren (abgebrochene Jobs); Exit 0 = keiner, 1 = blockierender Beleg oder Fehler')
    o = a.parse_args()
    if o.failure_evidence:
        try:
            ev = failure_evidence(open(o.log, encoding='utf-8', errors='replace').read(), o.api)
        except Exception as e:  # noqa: BLE001 - fail closed
            print('FAILURE_EVIDENCE parser-error line 0: %s: %s' % (type(e).__name__, e))
            sys.exit(1)
        for k, n, t in ev:
            print('FAILURE_EVIDENCE %s line %d: %s' % (k, n, t))
        blocking = [e for e in ev if e[0] in BLOCKING]
        print('FAILURE_EVIDENCE_RESULT %s' % ('blocking' if blocking else 'size-only' if ev else 'none'))
        sys.exit(1 if blocking else 0)
    try:
        steps = parse_steps(o.step_conclusions) if o.step_conclusions is not None else None
    except ValueError as e:
        print('DEVICE_GATE api=%d verdict=REJECT' % o.api)
        print('  REJECT-REASON %s' % e)
        sys.exit(1)
    verdict, reasons = evaluate(open(o.log, encoding='utf-8', errors='replace').read(), o.api, o.job_conclusion, steps)
    print('DEVICE_GATE api=%d verdict=%s' % (o.api, verdict))
    for r in reasons:
        print('  %s-REASON %s' % ('INSUFFICIENT' if verdict == 'INSUFFICIENT_EVIDENCE' else 'REJECT', r))
    if verdict == 'ACCEPTED_SIZE_ONLY':
        print('  ACCEPTED (owner decision, isolated Android 17 test only): P4B-A10-SIZE on API 29 - NOT an Android 10 approval')
    sys.exit({'PASS': 0, 'ACCEPTED_SIZE_ONLY': 3, 'INSUFFICIENT_EVIDENCE': 4}.get(verdict, 1))


if __name__ == '__main__':
    main()
