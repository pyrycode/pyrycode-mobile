# Inherited effort replay observation (#2507)

## Files read
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: the inherited-effort scenario, `freshSettings`, `appliedEffortFooter` and `awaitFooter` retain the native contract.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SettingsReadProbe.kt` and `SettingsReadProbeTest.kt`: #2305 already establishes current-owner correlation and synchronous admission; later notifications may replace that admitted value.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RunConfigurationWait.kt` and `RunConfigurationWaitTest.kt`: section-local selection, pending and exact note share one semantics snapshot.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteConnections.kt`: scheduled delivery and downstream consumption are separate; `awaitIdle` alone proves only routing.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuitePolledReads.kt` and `RecordedSuiteTape.kt`: correlated snapshots update the polling cache, while independent minimal notifications still reach the app.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: owns the current phone connection and offline-only synchronization seams.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: authoritative settings replace the current reading, including an omitted applied field.
- `docs/recorded-tests.md`: variable successful poll counts use delivered snapshots; payload matching and complete consumption remain strict.
- `../pyrycode/docs/protocol-mobile.md`: Session settings (v2) and Thread query messages supply the controlled frame contracts; no wire contract changes.
- `docs/knowledge/features/thread-composer-footer.md`, `thread-composer-footer-effort-recall.md`, and `remote-conversation-repository-session-settings-read.md`: omitted applied effort must not stick; fresh correlation does not guarantee the reading remains current forever.

## Context
The retained #2493 macOS gate at `52e6085278` reported 333 executed, 332 passed and one failed, followed by a one-case passing rerun. The failure reached Effort with presence true, selection/note false and no pending marker. Retained stderr identifies the assertion; the bundle does not contain the original phone log. Historical #2467 evidence remains historical Linux evidence, outside this ticket's verification scope.

The genuine inherited-effort fixture includes repeated minimal settings notifications followed by complete correlated replies. Normalization keeps the notifications but turns correlated replies into polling-cache updates. A read can receive applied effort, then a later minimal notification replaces the admitted product reading; the subsequent cache update alone does not restore it. Scheduling relative to the sheet-open read can therefore leave its selection/note unavailable. Controlled replay will establish this ordering directly; a focused pass alone is not diagnosis. No decision record is needed.

One behaviour slice: reliably observe inherited applied effort after the owned completed phone turn, with its causal regression. The shared-file changes on #2505 (and its #2360 teardown ancestry) are separate additive acquisition/teardown work; preserve them. Other remote historical scenario branches do not restructure the edited block.

## Design source
N/A: test/replay synchronization only; product UI and its visual contract do not change.

## Design
Add an offline-only observation fence to `SuiteConnections`, exposed through `RecordedSuiteNative` and used only before the inherited-effort scenario's final `freshSettings`. Require the current action's captured settings on that phone to be released by their real causal requests, drain delivery work and await the current phone pump's downstream-consumed prefix. `RecordedSuiteTape.settingsObservationReady` checks the existing next-command boundary without releasing anything itself. Resolve the exact current phone owner and reject replacement/closure. The fence observes scheduling and consumption, never expected settings contents, and sends no request or reply. Keep the existing correlated fresh read after the fence and both exact sheet assertions.

The regression drives the real replay hub and authoritative repository with controlled captured-shaped minimal/full settings and holds downstream consumption. It first demonstrates the independent correlated reading succeeding and then being replaced by a later minimal notification while the complete snapshot remains only in the replay cache. The fenced read must wait for actual delivery and consumption, then obtain the exact applied reading. A separate missing-field arm must still return Unavailable and fail the applied expectation. Existing section semantics coverage remains in `RunConfigurationWaitTest`.

## State and concurrency model
The replay hub retains its existing parent-owned scope and single scheduled-delivery channel. The observation fence runs in the test coroutine, bounded by 30 seconds, and uses the existing delivery barrier and pump consumption counter. A content-free `MutableStateFlow<Long>` progress version advances under the existing request, lifecycle and routing monitor; an incomplete frontier suspends until a real event changes that version. Capture the version before observing work to avoid losing a concurrent release. No extra inbound collector, product coroutine, dispatcher, genuine fixture or saved state is introduced. The current pump identity is captured and checked across suspension; cancellation follows the test scope.

## State transitions and identity reuse
| Event | Required regression |
| --- | --- |
| Complete correlated read then later minimal notification | Controlled regression demonstrates admitted applied value becoming unavailable before the repair boundary. |
| Scheduled snapshot not routed yet | Fenced read waits, then uses the final delivered snapshot. |
| A causal query has not released later settings yet | `observationWaitsForSettingsStillHeldBehindTheirCausalQuery` proves the settings remain held and the fence waits for the actual query. |
| Routing completed but downstream emission held | Fenced read remains blocked until the selected pump consumes the prefix. |
| Repeated observation on the same owner | Fence completes without extra traffic or an old receipt supplying a new correlated read. |
| Owner ends/replaces during observation | Fence fails rather than borrowing another pump's consumption. |
| Final complete snapshot omits applied effort | Missing-field regression retains Unavailable; the scenario's original assertion still fails. |

## Error handling
Replay delivery errors, absent/changed phone ownership and timeout propagate as test failures. No retry, sleep-based settlement, expected-value filtering, timeout extension or relaxed assertion is added. All captured request matching and completion checks remain active.

## Testing strategy
Write the controlled shared regression first and observe its causal red using the unfenced path. Run its JVM class, `SettingsReadProbeTest`, `RunConfigurationWaitTest`, and affected replay hub tests. Run the regression class and exact recorded method through the focused macOS UI gate; shared source allows the same controlled assertions on the managed device. Retain failed/repaired XML, phone logs, revisions, fixture hash and counts privately with an evidence manifest. Run lint, APK assembly, instrumented Kotlin compilation, formatting and final pre-verification. Complete configured suites stay pending for dispatcher/verifier, including proof the exact method executed. Request a wider investigation if controlled/focused checks cannot establish the relevant trigger.

## Open Questions
- Resolved: the controlled JVM regression reproduces the hypothesized ordering; native validation of the repair remains required.

## Revisions
- 2026-10-23: Controlled pre-repair JVM execution ran four tests and failed all four, with zero skips. The primary failure retains the independent correlated `Applied(high)` reading while the subsequently admitted repository value is `Unavailable`. The missing-field control instead receives the stale earlier applied snapshot; the held-consumer and closed-owner controls also fail. This establishes the replay boundary defect without a preceding scenario or load trigger. The native command was still queued when the repair was applied, so it validates the repaired committed source, not a native pre-repair failure. Original macOS phone logs remain unavailable; the exact historical interleaving is inferred from its section diagnostics, unchanged fixture and this causal reproduction.
- 2026-10-23: Self-review found that a query can still hold later settings while the queue is empty, as the genuine scenario's post-turn thread queries can do. The added causal-query control first proved its minimal notification was still held, then failed the initial fence: one executed, one failed, zero skipped. The contract now includes release of this action's captured settings before routing/consumption can finish the observation. A versioned event signal waits for real requests or delivery rather than spinning or sending a read retry. The original four controls passed natively before this refinement; final five-control native evidence remains required.

## Documentation handoff
Pending for documentation stage: `docs/recorded-tests.md`, successful settings polling/observation boundaries: explain the scenario's scheduling-and-consumption fence and preserve correlation, omission failure and strict consumption. `docs/knowledge/features/remote-conversation-repository-session-settings-read.md`, Native test reads: distinguish a fresh independently admitted reply from the later sheet's current reading. Record evidence limits: original macOS phone logs were not in its retained bundle.


## Approved manual continuation — 2026-10-24

Juhana explicitly authorized continuation after the earlier merge-commit approval
rejection. Before mutation, the saved resolution was checked byte-for-byte:
`modelReopenProgress` matched the upstream merge head and
`beforeSettingsObservation` matched the repair head. Full working/index patches,
all three index stages and a branch bundle preserve the stopped state. The
supported exact manual hold and approval authorization are retained separately.
The saved merge committed normally as `4c7eee39b`; current main `bee371a57`
then merged cleanly as `e30328514`. Both hooks and upstream helper changes remain.
All 589 retained builder evidence manifest entries verified before continuation.
The old rejection and every failed test remain recorded; the authorization is
bounded to this ticket's merge, focused checks, PR, independent review and docs.

Remove the obsolete numeric line/file/production-file forecast. It describes a
planning estimate rather than behavioral proof and no longer represents the
retained repair plus upstream integration. Keep no saved override, the owned
completed phone turn, current-owner correlated settings, exact applied-effort
label/note and permission assertions, omission failure, strict matching and
complete tape consumption. The original 30-second sheet deadline remains.
Focused integrated verification is pending; old green reports retain their own
source identities and are not acceptance of this integration.

## Security review

Self-review verdict: no new product security boundary or read authority is added.
The offline-only fence checks the exact existing phone owner across suspension,
requires the existing causal settings frontier and downstream consumption, then
performs the original independently correlated settings read. It observes no
expected setting value and fabricates no wire message or receipt. Closure cannot
borrow another owner's progress; omitted applied effort remains unavailable.
No credentials, production API/schema, persistence or genuine recording bytes
change. Independent review and configured acceptance remain required.


## Integrated focused results — 2026-10-24

On `e30328514` plus the documentation-only continuation plan, the final five
`InheritedEffortReplayTest` controls freshly executed/passed 5/5, zero failures,
errors or skips, exit 0. Invocation metadata records five actual executions and
no reused Gradle result. This includes the later causal-query frontier control,
downstream consumption, repeated observation, missing-field and closed-owner
requirements; the earlier causal-query red remains one executed/failed.

On clean committed `8de1d65febb46b72a981b18d54823294fcfed10e`, the focused Android
gate executed/passed all six selected methods: the five controls and
`RecordedMobileSuiteTest#interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn`,
zero failures/errors/skips, exit 0. Raw XML, phone logs, finished gate phases,
app/test BuildConfig and built APK hashes are retained privately under
`2507-manual-evidence/native/`; test BuildConfig reports `8de1d65fe`, app reports
`dev`. Built hashes are not installed-byte readback. No extra independent
per-process cleanup audit or reconstruction of the historical interleaving is
claimed. The wrapper's first launch failed before execution because its local
script path was wrong; zero tests executed, and that failure is retained.

The integrated replay-hub class then freshly passed 29/29, zero failures/errors/
skips, exit 0. Android lint passed. Initial final pre-verification stopped at one
Spotless line-wrapping violation in `settingsObservationReady`; no test failure
is inferred. Its log is retained. The formatter changed only whitespace in that Kotlin file; normalized source
bytes are identical. Rerun the required pre-verification. Previous runtime checks retain the source identities
above; they are not independent acceptance of subsequent formatting/docs commits.
Complete configured acceptance, source/security review and normal documentation
remain pending for the independent workflow.

The original builder bundle verified 589/589 manifest entries. Its later
causal-query red and refined exact-method native artifacts were additionally
retained before cleanup of temporary storage. No original failure is erased or
turned into acceptance by a later green run.
