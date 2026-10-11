# Archive order live diagnosis (#2092)

Cause and behavioural repair remain pending. These records validate the bounded
probe and show that focused contexts have not reproduced the historical failure.

## Historical failing stage

The #1977 and #2009 branch stacks stop in the initial `hostConversationIds`, before
creating A. #2009 base has only an unlocalized coroutine timeout. Archive
acknowledgements, archived memberships and row-order assertions were not reached
in those branch failures.

Retained dispatcher reports are under `/work/Projects/pyrycode-mobile-agents/logs/`:

- `2026-10-11T14-45-49-322Z_real-claude-gate_#1977.log` and `.stderr.log`: Mobile
  `2bd039944754f24c920feef0efaec36a8ea7f6db`, daemon
  `63aabec70dad3948c916332a139eb22533b8f4b8`; 65 executed, 9 failed, 0 skipped.
- Its `-rerun_#1977.log`: 9 executed, 3 failed, 0 skipped; target method passed.
- `2026-10-11T20-44-12-934Z_real-claude-gate_#2009.log` and `.stderr.log`: Mobile
  `fa1a3bc04dcabd1cd78d44d0d37d7ed50736869c`, daemon
  `b36441f9d03c7e7b78ce7c41fe6963e225c59ad5`; 65 executed, 41 failed, 0 skipped.
- Its `-base_#2009.log` and `.stderr.log`: Mobile
  `b44067bbd991b7d68e0418f88cc2a9e721671c61`, same daemon; 25 executed,
  17 failed, 0 skipped. The preceding offline-retry method failed its initial
  list read before invoking `DaemonFaultControl.stopUntilRetryWindow`.

The original phone/daemon capture directories named by these logs are absent.
Cleanup-time focus records cannot establish lifecycle or relay-loss timing at
failure. #2101 has no demonstrated shared mechanism or repair.

## Fresh focused captures

`evidence.json` records exact revisions and raw capture paths. `*-stages.log`
retain only this probe's content-free stage/status observations. Counted XML was checked for safe device/flavor/project properties before copying.
The archived live XML normalizes only CRLF to LF; every parsed record is unchanged,
and raw original paths remain in `evidence.json`.

| Context | Executed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `ArchiveOrderProbeTest` | 7 | 0 | 0 | 0 |
| Existing `LiveConnectionReadsTest` | 4 | 0 | 0 | 0 |
| Target method alone | 1 | 0 | 0 | 0 |
| Two-host archive, offline retry, target | 3 | 0 | 0 | 0 |
| Original last six methods, starting with Stop | 6 | 0 | 0 | 0 |
| Status-sheet ping, create/edit/archive channel, target | 3 | 0 | 0 | 0 |

All fresh captures used Mobile `1a88f0a4088c841369855a0571eaaf85c1ef03c0`.
The isolated and pairing/retry contexts used daemon
`b36441f9d03c7e7b78ce7c41fe6963e225c59ad5`. The last-six and channel contexts used daemon
`347981ae81f93d7ec8d4b5ffd2a5dd8f006edddb`; its intervening commits concern
hosted apps and admission documentation, so this is recorded as a revision change.

At every retained target-method observation, the target host was selected,
authenticated, had a repository and loaded rows. The bounded/conflated observer
does not prove that every transient state was sampled. Initial list delivery took 13 ms alone,
1 ms after pairing/retry and 2 ms after the original last-six context. The
pairing/retry capture retains the preceding `transport_end`/`pump_teardown`
events: the final daemon-absent close was at 22:39:10.768 UTC, and the target's
successful initial list read began at 22:39:16.286 UTC. This demonstrates recovery
in that control, not the cause of the historical failures.

## Reproduction and remaining evidence

Use `python3 scripts/android-test-gate.py live --tests "Class#method,..."` through
the approved Mobile live launcher. The focused contexts preserve method order
and every existing assertion. JVM checks select `ArchiveOrderProbeTest` and
`LiveConnectionReadsTest` through `testDebugUnitTest`. The initial diagnostic
regressions could not compile before the helper existed; all 11 now execute and
pass. They validate diagnostics, not a causal behavioural repair.

A first live admission attempt returned exit 75 after a 300-second device-lock
wait, executing zero tests. Temporary native-thread exhaustion affected later
local JVM checks; a fresh single-use JVM with two active processors and one
Gradle worker completed the checks. No host configuration was changed.

Required next evidence: a failing full-suite context on this instrumented tree,
with exact Mobile/daemon revisions, counted XML, the target method's raw logcat
and bounded stage trace, preceding method timestamps, and content-free primary
daemon/relay events before cleanup. The dispatcher owns the full suite. Diagnose
from those records, then add the demonstrated-condition regression and localized
repair; a fresh passing full dispatcher gate remains acceptance work afterward.
