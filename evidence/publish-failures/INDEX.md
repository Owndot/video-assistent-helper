# Gesicherte Fehlerlogs (Codex Release-Readiness 2, Punkt 7)

Vollständige Job-Logs der Helper-CI, gzip-komprimiert. `sha256_log` ist der SHA-256 des unkomprimierten Logs, so wie es
die GitHub-API ausgab. Alle Läufe stammen aus dem Workflow helper-channel.yml, Branch feature/c3-browser-downloads.

| Run | Attempt | Job | Commit | API | Test | Befund | Datei | sha256_log |
|---|---|---|---|---|---|---|---|---|
| 42240436344 | 1 | 127968972720 | `79b5fa4` | 34 | interruptionFailsCleanOrResumesExactly | failed:storage (cause not yet recorded by the test of that time) | `run42240436344-attempt1-job127968972720-api34.log.gz` | `53d430d30aa2180e5b144d93e058554de972dc963bdac86d6d9dbd14bbe4438d` |
| 42547150112 | 1 | 128934502193 | `bd5ab29` | 34 | publishesThroughMediaStoreAndReadsBack | failed:storage (cause not yet recorded) | `run42547150112-attempt1-job128934502193-api34.log.gz` | `6b30ee245f731a5e57e5a99af68821c61f6b384b9efa149690b36cdb8e95995d` |
| 43874560604 | 1 | 133324099640 | `d2f3884` | 34 | interruptionFailsCleanOrResumesExactly | IOException: publish (update did not hit exactly one row) | `run43874560604-attempt1-job133324099640-api34.log.gz` | `4fb5cc91b2f710ab382baf62c0910e36b49afe2842484a27e355519cedf3f168` |
| 43879157709 | 1 | 133340077519 | `4cbfb2e` | 34 | interruptionFailsCleanOrResumesExactly | IOException: publish | `run43879157709-attempt1-job133340077519-api34.log.gz` | `04a3b1db05b1bbf0b469acb98100aa622f9e16de73ff757854a27e60180a521f` |
| 43970471504 | 2 | 133674082860 | `291cff1` | 29 | publishesThroughMediaStoreAndReadsBack | publish rows=0; then system_server OutOfMemoryError (16 MiB growth limit) | `run43970471504-attempt2-job133674082860-api29.log.gz` | `1c348d481c5b4a5b759cd8a36807b93ca139223439f3b7b87e7ec136f0ba88c8` |
| 44054981212 | 1 | 133942049996 | `e5fb290` | 34 | duplicateAndLateStartsFetchOnce | publish rows=0 proof=present cleanup=not-observed (no provider snapshot yet) | `run44054981212-attempt1-job133942049996-api34.log.gz` | `05c7d65c33dfa83c0997975823a69b00e51ee2cd660966f85a7a6f8526ac21a7` |
| 44090927478 | 1 | 134065500998 | `f3bf6a4` | 29 | interruptionFailsCleanOrResumesExactly | publish rows=0; diskFinal=exists(8388608), no row; then system_server OutOfMemoryError (16 MiB) | `run44090927478-attempt1-job134065500998-api29.log.gz` | `9cedc3a8a31698f53703d36ac4d25c97d4ec6ee63ee87bc3345b9e5da640e5e4` |
| 44093078605 | 1 | 134073182519 | `b18a355` | 34 | duplicateAndLateStartsFetchOnce | publish rows=0; diskFinal=exists(65536), no row; app heap 192 MiB, no crash | `run44093078605-attempt1-job134073182519-api34.log.gz` | `adb0dea95d25201ff39d8c9840cb3851bfbc7f8e998aa19d553bed1b4ea9dac4` |
| 44096693307 | 1 | 134085570218 | `be74008` | 34 | interruptionFailsCleanOrResumesExactly | publish rows=0; diskFinal=exists(8388608), no row; app heap 192 MiB, no crash | `run44096693307-attempt1-job134085570218-api34.log.gz` | `967dd69d8cc10baf42bcd1dc3884ee5046b77b86c7c7a7dda23850f8470d9a3b` |
| 44093078605 | 1 | 134073182399 | `b18a355` | 29 | cancelDuringRemuxIsFastAndLeavesNothing, memoryPressureAbortsCleanly | invalid emulator environment: app heap 16 MiB (not a publish failure) | `run44093078605-attempt1-job134073182399-api29.log.gz` | `1f8dfaca297df3a018000405ee2126022d2d75f971eb3c21f25ba4ce5e7cd456` |
