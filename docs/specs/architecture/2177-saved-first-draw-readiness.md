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

### 2026-10-17: fresh four-method acceptance after #2218 and the #2228 software bound

#2218 is closed. Its repair is the operator's #2228 on main (`99b731b8a`), which
supersedes this ticket's "no threshold relaxation" wording only under a software
renderer: `SavedThreadFirstDrawDeviceTest` keeps the 1000 ms product bound, uses
2000 ms when the guest GL renderer is SwiftShader, and holds the optional projection
for bound + 100 ms (2100 ms there). This ticket changes neither; no Mobile test,
helper, fixture or production source changed here. Readiness contract, cold
construction, committed-frame endpoint and 3000 ms slow restore are unchanged.

At Mobile `24a015e8b5f580a6933b45542ed01386c0b704f3` (main `46909f852` merged),
agents `c8edc37edbcc111bf769b93840beea0fe5e8c02f`, reused managed generation
`151a9dbd…`, on build-fractal-2 with renderer SwiftShader (logged `bound_ms=2000`),
the focused class executes 4, passes 4, fails 0, no errors/skips, exit 0, after
990 s waiting for admission. All 17 readiness barriers qualify over 77 windows,
with 17 Probes and maximum acquisition/read 9/32 ms. Fragmented offline/held-newest
first opens draw at 774/650 ms with projection held and 591/664 ms originally;
every one of the 16 openings also meets the 1000 ms product target, so the software
bound decided no outcome. The slow control is rejected at 3727 ms. The earlier
bound-1000 Fractal run (4/2/2/0/0) stays failed, and the historical 1434/1436 ms
misses keep #2202's disposition. No retry, warming or skip was used.

Checked results and all 17 opening records are in
`post-2218-{validation,openings}-20261017.*` under the evidence asset directory;
raw XML, phone logs, gate phases, image identity and a verified manifest are under
`$AGENTS_REPO_PATH/logs/evidence/2177/post-2218-class-20261017/`. Complete
dispatcher UI/scripted-all gates and documentation remain pending.

### 2026-10-17: readiness succeeds; measured latency now blocks acceptance on #2218

At Mobile `c8060639b8903a65b8e29641b8f812047cfcb9b0`, deployed agents
`9aad4b5db84f2897c5eda3e8ed78a5ec9256ad71`, the fresh complete focused class
executes 4, passes 2, fails 2, with no errors/skips, exit 1. Both controls pass;
the slow control rejects the unchanged bound at 3928 ms. Every opening qualifies
readiness: 17 barriers, 98 responsive windows, four qualifying final windows each,
17 installed Probes, maximum acquisition/read 22/80 ms. Both positive methods
retain all eight openings. The held-projection fragmented offline/held-newest first
opens draw at 1314/1032 ms (restore/snapshot/content 935/936/985 and 710/713/749 ms).
The original fragmented offline first open draws at 1026 ms (791/792/816 ms);
held newest passes at 619 ms. These are measured bound failures, not readiness
timeouts. No acceptance retry or assertion change is permitted or used.

Independently verified #2209 owner's 88-file original-host comparison: unchanged
Mobile `5e3e4471733f63e97ccb6c4fc5f054d8107f9445`, reference 4/3/1/0/0 and
prepared 4/4/0/0/0. Current and owner generation inputs differ only in the recorded
launcher identity: current swangle/SwiftShader versus owner auto-no-window/lavapipe.
Current OS preparation/restore final 40-window idle minima are 82.46/83.64%.
All eight measurement-source hashes match. This demonstrates the readiness repair
under the recorded conditions but does not explain the new draws or historical
1434/1436 ms misses. Thirty-six read-only host/process/guest observations add
diagnostic overhead; five-second guest intervals include up to 16.17% steal across
preparation/timed work. They cannot attribute individual draws. The pending search
process records zero additional ticks during observation.

Following the planned residual-latency seam, filed and verified open #2218 in
Backlog at priority:low and read back its native blocked-by link from #2177.
Its independent repair must precede this ticket's required fresh four-method green
acceptance. No Mobile logic defect is established, and no renderer/process setting
was changed. Fresh ping passes 2/2. Checked counts, all 51 comparison opening
records and identities are in the managed-image evidence assets; raw XML, every
phone log, numeric CPU observations, image qualification records and verified
manifests are durable under this ticket's evidence root. Complete configured
dispatcher UI/scripted-all gates and the documentation update remain pending.

### 2026-10-17: resume after managed-image repair #2209

#2209 is closed after agents PRs #190 and #191 deployed at
`9aad4b5db84f2897c5eda3e8ed78a5ec9256ad71`. The runner now prepares an OS-only
generation, qualifies sustained guest quietness before saving RAM/disk checkpoints,
and verifies a read-only restore before installing Mobile. It preserves Play
Services, the signer, shared admission and cleanup custody. This repairs the
demonstrated lifecycle gap: Gradle's boot-plus-fixed-delay snapshot could retain
startup work. The initiating Play Services operation remains unproved.

Review the owner's retained unchanged-source Fractal comparison (reference 4/3/1,
prepared 4/4/0; no skips), image qualification and source hashes. Then run this
branch's unchanged four-method class and focused scripted ping, followed by the
required builder checks. Retain fresh counted XML, all phone logs, per-opening
readiness/draw phases, runner/generation identity and verified manifests. Do not
repeat acceptance on failure or alter any measurement contract. Complete configured
UI/scripted-all gates remain dispatcher-owned; the owner comparison and focused
passes do not replace them. #2202 now records the historical latency disposition:
failure retained, cause unresolved, not reproduced under recorded repaired-runner
conditions; its closure does not attribute the old 1434/1436 ms misses.

### 2026-10-17: guest load still prevents current acceptance; wait on #2209

After merging #2198, focused scripted ping passes: 2 executed, 2 passed, no
failures/errors/skips, exit 0. Its earlier failure remains retained. Main is merged
at `5e3e4471733f63e97ccb6c4fc5f054d8107f9445`; deployed agents are now
`49cd6dd07c163a7d0e9583850aafb28378ec0b3c`.

The fresh complete first-draw class instead executes 4, passes 3, fails 1, skips 0
(exit 1). Both draw methods retain and pass all eight openings, and held-pipe
cancellation passes. The first executed slow-restore control exhausts readiness:
109 responsive windows at 0–49% idle, none >=80%, ending at `stage=window` before
any Probe. It supplies no slow-draw or bound-rejection measurement. A separate
isolated diagnostic reproduces that failure (1 executed, 0 passed, 1 failed,
0 errors/skips, exit 1): 110 responsive windows at 0–49% idle, no Probe.

Five-second CPU deltas overlapping diagnostic readiness show guest idle
3.97–37.83%, guest steal 0–0.20%, and host idle 45.28–74.39%. A fresh one-second
guest process sample near the deadline/teardown reports 9% idle, 0 host/steal,
Google Play Services persistent/main at 66/23 percentage points of two-core CPU
capacity, system_server at 26 and Mobile at 3. This demonstrates guest background
load near this failure, without proving the initiating work or attributing every
window. The first class also had a pending documentation-search request; neither
its lifetime CPU average nor its timeout diagnoses that failure. The isolated
diagnostic records zero additional search-process CPU ticks. `dumpsys cpuinfo`
repeats an old boot interval and is not current process attribution.

Filed and verified open external runner/device repair [#2209](https://github.com/pyrycode/pyrycode-mobile/issues/2209),
Backlog, low priority, and the native blocked-by link from #2177. Its owning work
is in the agents runner/managed-image lifecycle, outside this Mobile builder's
allowed files. No Mobile measurement logic defect is established. No process or
device settings were changed, and no warming, retries for acceptance, skips or
threshold changes were used. The isolated run is diagnostic evidence, preserving
the failed class. Resume acceptance after an evidenced external repair, including
fresh execution of all four methods; the earlier 4+4 passes do not clear this red.

Checked counts, revisions, hashes and all sixteen valid openings plus the failed
readiness phase are in `resume-validation-20261017.json` and
`resume-openings-20261017.jsonl` under the existing evidence asset directory. Raw
XML, phone logs, CPU observations, scripts and verified manifests are durable under
`$AGENTS_REPO_PATH/logs/evidence/2177/resume-{first-draw-20261017-0014,negative-diagnostic-20261017,ping-20261017}/`.
Historical 1434/1436 ms misses remain unresolved in #2202; current valid fragmented
first draws of 601/589 ms normally and 852/667 ms with projection held do not
attribute those misses. Complete dispatcher UI/scripted-all gates and documentation
remain pending. Documentation must retain this renewed guest-load failure as well
as the earlier host/compiler diagnosis and original passing comparisons.

### 2026-10-16: separate inherited scripted failure blocks green validation

Final focused scripted `ping` executed 2, passed 1, failed 1, skipped 0, exit 1.
`interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` rendered the
finished reply and obtained checkpoint 12 with complete receipt facts, then failed
its unchanged 90000 ms `observeReadMarks` wait; the durable-gap method passed.
The final destroyed-activity focus record follows teardown and is not a crash
diagnosis. No Mobile production, deterministic-test or harness logic changed here.

Reused open [#2198](https://github.com/pyrycode/pyrycode-mobile/issues/2198), whose
clean-main reproduction names the same assertion and 2/1/1/0 counts. Its repair is
in flight and outside #2177. Added and read back the native blocked-by link from
#2177 to #2198. Preserve the failing assertion without retry or skip; resume green
scripted verification after that repair. Readiness unit controls passed 12/12 with
no errors/skips, alongside lint, debug assembly and instrumentation compilation.
The 4+4 focused first-draw passes remain valid. Final formatting/compilation can
proceed, but complete dispatcher UI/scripted-all results remain pending.

### 2026-10-16: valid measurement restored; historical latency tracked separately

Both the exact unchanged baseline `4b21cef23ef9d395f667694ab2a6df97c380deaf` and
current `e8a4508f23f1b99e0c22494656fad1fe4a9ecf7d` passed the supported focused
four-method class: each 4 executed, 4 passed, 0 failed/errors/skipped, exit 0.
Both draw methods retained all eight openings; the slow control rejected its
3800/3768 ms draw, and the held-pipe control passed. All 17 readiness barriers per
run completed without sampling failure. Test/helper, fixture and four primary
production measurement-file hashes match between the trees. No Mobile logic repair
is warranted: the repair and its controlled regressions belong to external #2178.

The first open question is resolved for current conditions: measurement is valid
after runner/host remediation. The second is resolved within the retained evidence:
earlier load was real host/compiler/GC pressure with guest steal, not established
counter misinterpretation or held sampling. Current 250 ms windows reach the same
four-window contract; maximum acquisition/read is 9/32 ms baseline and 24/32 ms
current. Five-second numeric host/guest samples and gate phase boundaries are
retained separately, including startup busy windows and the baseline observer's
device-restart counter reset. They do not replace per-opening readiness.

The third question remains historically unresolved. Fragmented offline/held-newest
first draws are 676/609 ms (baseline original) and 639/583 ms (current original),
841/678 ms (baseline projection twin) and 839/687 ms (current twin). These do not
establish the cause or repair of the old 1434/1436 ms full-gate misses. Filed and
verified [#2202](https://github.com/pyrycode/pyrycode-mobile/issues/2202), Backlog,
low priority, for that independent latency disposition. It is not a readiness
dependency. The raw original verifier bundle was retrieved and retained.

Checked content-free comparison, every opening's readiness/draw phases, source
hashes and raw-manifest hashes are under `app/src/androidTest/assets/readiness-2177/`.
Raw reports, phone logs, CPU samples and analysis scripts are private under
`$AGENTS_REPO_PATH/logs/evidence/2177/post-runner-{baseline,current}-20261016/`.
The evidence README records environment differences: owned shared admission,
remediated compiler pressure, configured 2048 MiB versus earlier explicit 4 GiB
Gradle heap, preparation/device timing and an unsuccessful optional property read
during emulator startup. No warming, retries, skips or threshold change occurred.
Complete configured UI/scripted-all gates and the documentation update remain
later-stage work; no complete-gate PASS is claimed by these focused comparisons.

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
