#!/usr/bin/env bash
# NUR TESTS. Installiert die Testvariante de.evp.helfer.channeltest auf einem Wegwerf-Emulator, führt die
# Kanal-Instrumentierungstests aus, sammelt einen geheimnisfreien Bericht und deinstalliert beide APKs wieder.
# Ein echtes Gerät wird abgelehnt. Das Produktionspaket de.evp.helfer wird weder installiert noch berührt.
# Aufruf: tools/run-channel-instrumentation.sh OUT   (OUT/apk aus tools/build-channel-apk.sh)
set -uo pipefail
OUT="$(cd "${1:?usage: run-channel-instrumentation.sh OUT}" && pwd)"
REPORT="$OUT/report"; mkdir -p "$REPORT"
PKG=de.evp.helfer.channeltest
adb wait-for-device
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ] && [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" != "1" ]; then
  echo "refusing: the connected device is not an emulator" >&2
  exit 2
fi
adb shell getprop ro.build.version.release > "$REPORT/device.txt"
adb shell getprop ro.build.version.sdk >> "$REPORT/device.txt"
adb shell getprop ro.product.cpu.abi >> "$REPORT/device.txt"
# Nur Befund (Text): Java-Heap-Vorgaben des Abbilds. Die Remux-Speichergrenze richtet sich nach Runtime.maxMemory();
# ein vom Emulator verlangter, aber nicht angewendeter Heap (ART-Standard 16 MiB) verfälscht sonst Gerätetests.
for prop in dalvik.vm.heapsize dalvik.vm.heapgrowthlimit qemu.dalvik.vm.heapsize ro.boot.qemu.dalvik.vm.heapsize ro.kernel.qemu.dalvik.vm.heapsize ro.build.fingerprint; do
  echo "DEVICE_PROP $prop=$(adb shell getprop "$prop" | tr -d '\r')"
done | tee -a "$REPORT/device.txt"
adb shell pm list packages de.evp.helfer > "$REPORT/packages-before.txt"
adb logcat -c
adb install -r -t "$OUT/apk/helper-channeltest.apk" > /dev/null
adb install -r -t "$OUT/apk/helper-channeltest-androidTest.apk" > /dev/null
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
# Android 8–9: READ/WRITE_EXTERNAL_STORAGE erteilt der Test selbst zur Laufzeit (wie der Dialog der App); hier nur
# der Ausgangszustand. Ab Android 10 sind beide nicht angefordert.
adb shell dumpsys package $PKG | grep -E 'WRITE_EXTERNAL_STORAGE|READ_EXTERNAL_STORAGE' | sed 's/^/STORAGE_BEFORE /' || true
adb shell am instrument -w -r -e class de.evp.helfer.ChannelInstrumentedTest,de.evp.helfer.net.SafeHttpInstrumentedTest,de.evp.helfer.Mp4DownloadsInstrumentedTest,de.evp.helfer.HlsDownloadsInstrumentedTest "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$REPORT/instrumentation.txt" 2>&1
status=0
if ! grep -q '^OK (' "$REPORT/instrumentation.txt"; then
  status=1
  # Review P2: Systemseite eines gescheiterten Hauptlaufs (nur Ablage-/MediaProvider-Meldungen, synthetische Testnamen),
  # z. B. ob der MediaProvider beim Veröffentlichen die Datei schon umbenannt hat ("Moving … to …").
  adb logcat -d | grep -E 'MediaProvider|FuseDaemon|MediaStore' | grep -iE 'C2 |rename|moving|update|pending|fail|error' | tail -60 | sed 's/^/MAIN_SYSTEM: /'
fi
# Diagnose (API 29+): wiederholte Veröffentlichungen über die echte App-Ablage (MediaStorePublishProbeTest). Eigener
# Lauf, eigene Datei; im Job-Log nur eindeutig markierte Zeilen. Ein Fehlschlag ist ein Befund und macht den Job rot.
PROBE_STATUS=skipped
if [ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" -ge 29 ]; then
  adb shell am instrument -w -r -e class de.evp.helfer.MediaStorePublishProbeTest "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$REPORT/publish-probe.txt" 2>&1
  adb logcat -d -s MP4_TEST:I | grep -E 'PUBLISH_PROBE|PUBLISH_RACE' | sed -E 's/^.*(PUBLISH_PROBE|PUBLISH_RACE)/\1/' | tee "$REPORT/publish-probe-lines.txt"
  if grep -q '^OK (' "$REPORT/publish-probe.txt"; then PROBE_STATUS=ok
  else
    PROBE_STATUS=failed; status=1
    grep -E 'AssertionError|Exception|shortMsg' "$REPORT/publish-probe.txt" | head -5 | sed 's/^/PUBLISH_PROBE_RESULT: /'
    # Systemseite des Fehlschlags (nur Ablage-/MediaProvider-Meldungen; Testdateinamen sind synthetisch)
    adb logcat -d | grep -E 'MediaProvider|FuseDaemon|MediaStore' | grep -iE 'C2 Probe|rename|update|pending|fail|error' | tail -40 | sed 's/^/PUBLISH_PROBE_SYSTEM: /'
  fi
fi
echo "PUBLISH_PROBE_STATUS $PROBE_STATUS"
# P4c-3: ECHTER Prozess-Neustart (ProcessRestartInstrumentedTest). Phase A lässt Dienst + Download laufen; dann endet
# der Prozess: (1) „am force-stop“, (2) der Start der nächsten Instrumentierung beendet den alten Prozess. Phase B läuft
# in einem neuen Prozess (prüft die Prozesskennung selbst).
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
RC=de.evp.helfer.ProcessRestartInstrumentedTest
for mode in force-stop process-replaced; do
  adb shell am instrument -w -r -e restartPhase A -e class "$RC#phaseA_startDownloadAndPairThenTheProcessDies" "$RUNNER" > "$REPORT/restart-$mode-A.txt" 2>&1
  [ "$mode" = force-stop ] && adb shell am force-stop "$PKG"
  adb shell am instrument -w -r -e restartPhase B -e class "$RC#phaseB_afterARealProcessRestart" "$RUNNER" > "$REPORT/restart-$mode-B.txt" 2>&1
  for ph in A B; do
    # Review P4: eigene Datei – summary.txt wird unten neu geschrieben (die Ergebnisse gingen dort bisher verloren)
    if grep -q '^OK (1 test)' "$REPORT/restart-$mode-$ph.txt"; then echo "RESTART $mode phase $ph OK" >> "$REPORT/restart-summary.txt"
    else echo "RESTART $mode phase $ph FAILED" >> "$REPORT/restart-summary.txt"; status=1
      grep -E 'AssertionError|Exception|INSTRUMENTATION_RESULT|shortMsg|longMsg' "$REPORT/restart-$mode-$ph.txt" | head -20 | sed "s/^/RESTART $mode $ph: /" >> "$REPORT/restart-summary.txt"
    fi
  done
done
grep -E 'INSTRUMENTATION_STATUS: (test|class)=|INSTRUMENTATION_STATUS_CODE|^OK \(|FAILURES|Tests run|AssertionError|Exception' "$REPORT/instrumentation.txt" > "$REPORT/summary.txt"
adb shell dumpsys meminfo $PKG > "$REPORT/meminfo.txt" 2>&1 || true
adb shell top -b -n 1 -p "$(adb shell pidof $PKG | tr -d '\r')" > "$REPORT/top.txt" 2>&1 || true
adb logcat -d -s CHANNEL_TEST:I OKHTTP_TEST:I MP4_TEST:I HLS_TEST:I RESTART_TEST:I > "$REPORT/measurements.txt"
# Abstürze des Testprozesses (z. B. "Process crashed" ohne Stacktrace im Runner-Ergebnis): Crash-Puffer sichern.
adb logcat -d -b crash > "$REPORT/crash.txt" 2>&1 || true
# Nichts lauscht mehr auf dem alten Port, solange der Dienst läuft.
adb shell 'cat /proc/net/tcp /proc/net/tcp6 2>/dev/null' | awk '$4=="0A"{print $2}' > "$REPORT/listeners.txt"
if grep -qi ':BA43$' "$REPORT/listeners.txt"; then echo "LISTENER ON 47811" >> "$REPORT/summary.txt"; status=1; fi
adb shell pm list packages de.evp.helfer > "$REPORT/packages-during.txt"
adb uninstall "$PKG.test" > /dev/null || true
adb uninstall "$PKG" > /dev/null || true
adb shell pm list packages de.evp.helfer > "$REPORT/packages-after.txt"
# Fertige Downloads bleiben nach der Deinstallation im öffentlichen Ordner (Testdatei aus publicFileForUninstallCheck).
persist="$(adb shell 'ls /sdcard/Download/Video-Assistent/ 2>/dev/null' | tr -d '\r' | grep '^C2 Persist ' | head -1 || true)"
if [ -n "$persist" ]; then
  echo "PERSIST_AFTER_UNINSTALL ok: $persist ($(adb shell "stat -c %s '/sdcard/Download/Video-Assistent/$persist'" | tr -d '\r') bytes)" >> "$REPORT/summary.txt"
  adb shell "rm -f '/sdcard/Download/Video-Assistent/$persist'" || true
else
  echo "PERSIST_AFTER_UNINSTALL missing" >> "$REPORT/summary.txt"; status=1
fi
diff -q "$REPORT/packages-before.txt" "$REPORT/packages-after.txt" > /dev/null || { echo "package list changed" >> "$REPORT/summary.txt"; status=1; }
tail -5 "$REPORT/summary.txt"
grep -h 'PERSIST_AFTER_UNINSTALL' "$REPORT/summary.txt" || true
cat "$REPORT/restart-summary.txt" 2>/dev/null || echo "RESTART results missing"
cat "$REPORT/restart-summary.txt" >> "$REPORT/summary.txt" 2>/dev/null || true
# Fehlgeschlagene Tests mit dem Anfang ihres Stacktraces ins Job-Log (nur Testausgaben mit Wegwerfdaten).
awk '/^INSTRUMENTATION_STATUS: test=/{t=$0} /^INSTRUMENTATION_STATUS: stack=/{print "FAILED " t; n=14} n>0{print; n--}' "$REPORT/instrumentation.txt" | head -150
# Runner-Ergebnis (Absturzmeldung, Liste aller Fehlschläge) und Crash-Puffer ins Job-Log.
grep -E 'INSTRUMENTATION_RESULT|INSTRUMENTATION_FAILED|shortMsg|longMsg|Process crashed' "$REPORT/instrumentation.txt" | head -20 || true
awk '/^There (was|were) [0-9]+ failure/{p=1} p&&/^[0-9]+\) /{n=6} p&&n>0{print; n--}' "$REPORT/instrumentation.txt" | head -60 || true
grep -E 'FATAL|Exception|Error|^\s+at |Abort|signal|backtrace|#[0-9]+ pc' "$REPORT/crash.txt" | head -80 || true
grep -E 'CHANNEL_|OKHTTP_|MP4_|HLS_|RESTART_' "$REPORT/measurements.txt" || true
grep -E 'TOTAL PSS|TOTAL:|Native Heap|Java Heap' "$REPORT/meminfo.txt" | head -5 || true
# Review P4: vollständige, eindeutig abgegrenzte Berichte für tools/device_gate.py (nur Testausgaben mit Wegwerfdaten).
# Das Gate bewertet nur diese Abschnitte – nie Zeilen, die zufällig an anderer Stelle im Job-Log stehen.
gate_section() { echo "GATE-BEGIN $1"; if [ -f "$2" ]; then cat "$2"; else echo "GATE-MISSING $1"; fi; echo "GATE-END $1"; }
echo "GATE-API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
gate_section main "$REPORT/instrumentation.txt"
if [ "$PROBE_STATUS" != skipped ]; then gate_section probe "$REPORT/publish-probe.txt"; gate_section probe-lines "$REPORT/publish-probe-lines.txt"; fi
for mode in force-stop process-replaced; do for ph in A B; do gate_section "restart-$mode-$ph" "$REPORT/restart-$mode-$ph.txt"; done; done
gate_section restart-summary "$REPORT/restart-summary.txt"
gate_section crash "$REPORT/crash.txt"
echo "GATE-SCRIPT-STATUS $status"
exit $status
