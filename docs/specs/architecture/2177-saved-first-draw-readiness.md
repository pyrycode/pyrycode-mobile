# Saved first-draw readiness investigation (#2177)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/SavedThreadFirstDrawDeviceTest.kt`: `awaitDeviceReadiness`, `measureSavedThreads`, `Probe`, and the two negative controls define the unchanged measurement contract.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/EmulatorCpuReadiness.kt`: `parseEmulatorCpuTicks`, `readEmulatorCpuTicks`, `withEmulatorCpuSample`, and `awaitEmulatorCpuIdle` separate parsing, bounded sampling, and readiness.
- `app/src/test/java/de/pyryco/mobile/e2e/EmulatorCpuReadinessTest.kt`: existing readiness, malformed-input, and cancellation controls must survive any repair.
- `docs/knowledge/features/thread-screen-testing-saved-first-draw.md`: responsive busy windows cannot establish held sampling or a measured draw miss; fixture construction and committed-frame endpoints must remain intact.
- `docs/knowledge/features/development-verification-test-scheduling.md`: focused device results supplement the dispatcher gates rather than replacing them.

## Context

Unchanged `4b21cef23ef9d395f667694ab2a6df97c380deaf` twice failed readiness before the first delayed-projection opening. Retained logs show responsive acquisition and reads, but lack component CPU deltas and contemporaneous host/device load attribution. Earlier fragmented draws of 1434/1436 ms remain independent failures requiring an evidenced disposition. No decision record is needed for a test-only repair.

## Design

First execute the unchanged complete device class and preserve fresh XML and phone logs. Compare source hashes, case/stage windows, and host/device CPU evidence rather than attributing load from the timeout. If existing diagnostics cannot distinguish counter interpretation from actual load, add bounded, numeric per-sample diagnostics at the test's sampling boundary, outside every timed opening. Keep diagnostics content-free and avoid changing the production cache, repository, ViewModel, or screen.

Select the smallest repair only after this comparison demonstrates its cause. A test-logic repair must have a controlled red regression before implementation and a recorded design revision. A required repair belonging to another repository becomes a linked blocker instead of an unevidenced mobile workaround. An independent residual draw regression becomes a separate latency ticket, preserving its measured failure.

The readiness contract remains four consecutive 250 ms windows at >=80% idle within 30000 ms. Retain bounded sampling cancellation, descriptor closure, ordinary and fragmented persisted fixtures, offline and held-newest first opens/reopens, cold construction inside the timer, exact newest committed-frame endpoint, 1000 ms bound, 1100 ms optional projection hold, and 3000 ms slow-restore control. No warmup, acceptance retry, skip, or threshold relaxation is permitted.

Overlap: #2009 touches the same three test/helper files; its cancellation implementation is already reused on main by #2143. Keep changes local and preserve those controls; no dependency on #2009 is required.

## State and concurrency model

No production state or jobs change. Existing acquisition runs in its owned IO scope, cancellation disposes late descriptors, and nonblocking reads remain cancellable. Diagnostic collection must stay outside timed construction and cannot make a held sampler outlive the readiness deadline.

## State transitions and identity reuse

| Event | Required coverage |
| --- | --- |
| Busy window resets an idle streak | `busyWindowRestartsTheFourConsecutiveIdleWindows` |
| Continuous load exhausts readiness | `continuouslyBusyDeviceFailsInsteadOfChangingTheDrawBound` |
| Acquisition or reading remains held through cancellation | Existing held-acquisition/read unit controls and `heldCpuPipeCancelsAndClosesItsDescriptor` |
| Same saved conversation reopens in a new ViewModel/composition | Both first-draw methods retain all eight openings |
| Slow restore exceeds the identical elapsed bound | `slowRestore_negativeControlRejectsTheSameFirstDrawBound` |

## Error handling

Malformed counters and sampling errors continue to fail; deadline failure remains readiness evidence without a Probe. Preserve case/stage diagnostics and resource cleanup on success, failure, and cancellation. No exception becomes a skip or a late measured pass.

## Testing strategy

Run the unchanged focused `SavedThreadFirstDrawDeviceTest` class, then the repaired class: both draw methods and both controls must execute with fresh counts, revisions, per-opening phases, and raw artifacts. Device execution is necessary for real CPU counters, descriptors, elapsed worker time, disk, and Android committed frames. Run `EmulatorCpuReadinessTest` and a controlled regression for repaired test logic. Retain raw evidence privately with verified hashes and commit content-free comparison summaries under test assets. Run focused units, lint, assemble, instrumentation compilation, formatting, and final `scripts/pre-verify.py --gradle`. Complete UI and scripted-all gates belong to the dispatcher; no real-Claude scenario is required.

## Open Questions

- Does a fresh unchanged run reproduce the readiness failure, and what work consumes the sampled CPU time?
- Are CPU deltas interpreted correctly on the configured image, and is host contention material?
- Do valid repaired measurements resolve the earlier fragmented first-open misses, or require a separate latency ticket?

## Documentation handoff

Pending for documentation: update `docs/knowledge/features/thread-screen-testing-saved-first-draw.md`, section "Readiness and sampling cancellation (#2143)", with the diagnosed cause, repair, valid comparison, and disposition of the earlier misses. Preserve historical failures and the distinction between readiness and elapsed-draw evidence. Record actual dispatcher UI/scripted-all counts when those later gates finish.

## Revisions

### 2026-10-16: resume after the external runner repair

#2178 is closed after agents PR #186 (`800e719`) deployed shared admission for APK
preparation and device execution, owned preparation cleanup, validated focused UI
selectors and in-process Kotlin compilation. Its controlled runner regressions
reported 83 passing checks. The original compiler's initiating ownership remains
unproved; safe remediation and the runner repair do not attribute historical draws.

Compare the exact unchanged `4b21cef23ef9d395f667694ab2a6df97c380deaf` in a temporary
detached baseline worktree against this branch, using the deployed supported focused
gate for all four methods. Retain numeric host/guest CPU counters every five seconds,
gate admission/preparation phases, original per-opening readiness/draw logs and XML.
No Mobile measurement logic repair is evidenced or planned. The current branch has
unrelated main changes since the baseline; record both revisions and test/helper
hashes rather than treating these trees as identical. Independent remaining latency
misses require a separate follow-up, without weakening any assertion.

### 2026-10-15: demonstrated external host pressure; wait on #2178

The unchanged focused class executed four tests: one passed, three failed, none skipped (exit 1). Both draw methods and the slow-restore control failed readiness before installing an opening; the held-pipe control passed. The three failed methods sampled 102/102/99 responsive windows, with idle ranges 1–55% / 1–40% / 0–36% and zero windows at >=80% idle. No draw phases were measured.

Thirty-six contemporaneous host samples show approximately 95.7% busy CPU, 0.1% I/O wait, and one-minute load 12.34–19.34 on eight logical CPUs. Subsequent one-second deltas isolate an existing Kotlin compiler daemon at 587–661% CPU while this baseline's Gradle daemon was idle. Two read-only JVM thread dumps show its eight GC threads consuming 163263.68 CPU-ms over 28.67 seconds; its old generation is 99% occupied. A guest snapshot shows 79 percentage points of host/steal time out of two cores, alongside Google Play Services work. This establishes an external resource problem in the fresh run, without establishing the initiating compiler defect or attributing the historical #2163 measurements.

The documented direct focused Gradle invocation also bypasses the agents runner's shared device hold, allowing this baseline's emulator to overlap the #2169 dispatcher UI gate. A second unchanged comparison used the existing `device_hold` and `run_on_device` helpers, queued behind that gate, then was cancelled cleanly before device acquisition or Gradle launch after the external blocker was confirmed. It executed zero tests and supplies no result. No warming, retry acceptance, measurement threshold, fixture, or production/test source change was made.

Filed and verified open blocker [#2178](https://github.com/pyrycode/pyrycode-mobile/issues/2178), in Backlog at low priority, for agents/runner ownership and remediation of the compiler/GC resource problem and focused device custody. The mobile runner entry point delegates to the agents repository; changing host process lifecycle or its admission policy is outside this builder's allowed files. Preserve the current test contracts and resume the planned baseline/repaired comparison after that repair. A controlled regression for the demonstrated external repair belongs in its owning repository.

Checked content-free evidence is committed in `app/src/androidTest/assets/readiness-2177/unchanged-baseline.json`; raw XML, four phone logs, host/device snapshots, JVM diagnostics, comparison scripts, and verified manifests are retained under `/work/Projects/pyrycode-mobile-agents/logs/evidence/2177/unchanged-concurrent-baseline/`. The earlier 1434/1436 ms fragmented first-open misses remain unresolved: current readiness exhaustion supplies no latency comparison. Their disposition and any required separate latency ticket remain work for #2177 after unblocking. Complete UI/scripted-all gates and documentation remain pending.
