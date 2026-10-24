# Thread screen — Agent navigation evidence

Part of [subagent tool-row nesting](thread-screen-subagent-tool-rows.md).
The proof-entry evidence below predates the finished-marker recurrence repair.

### Agent navigation proof readiness (#2373)

Repository completion is not a render fence across reset/catch-up: an empty projection
temporarily replaces the message list with the empty-thread surface. Before its first
closed-run reveal, `verifyAgentRunNavigation` waits up to 10 seconds for one merged
scroll list whose synchronized `IndexForKey` sample contains `msg:<agentId>`,
`agent-start:<agentId>` and `msg:<runId>`. Missing or wrong-family content stays unready;
multiple lists fail immediately. Readiness emits static counts/indexes and never scrolls
or taps. The existing closed/navigation/open/owned-child/close proof, transition deadlines,
one physical tap per transition, placement, following, settlement and strict replay remain.

`BackgroundAgentBlocksScreenTest.resetBeforeClosedRunCheckWaitsForTheSameOwnedFamily`
empties the real screen after mounting the settled family, restores it only after the
readiness callback observes absence, then completes the pointer proof. Before repair its
retained XML records 1 executed, 1 failed, 0 errors/skips at the first `assertClosed` /
`questionAnswerLocation` / `performScrollToNode`, before input. Both trees lack a list in
that controlled state. After repair all 21 block methods pass, including this regression,
`anotherAgentsListCannotPassTheAgentNavigationProof` and
`missingListCannotPassTheAgentNavigationProof`; the latter two require timeout rejection.

The [cause report](https://github.com/pyrycode/pyrycode-mobile/issues/2373#issuecomment-6317408688)
separates #2246's missing-list assertion at `bce55074fc` from suppressed replay-close
teardown fallout. Its rerun passed 1 executed, 0 failures/errors/skips. Historical logs
retain reset/zero rows but no simultaneous tree snapshot; a later unmerged match cannot
establish a merging defect.

Linux acceptance at `0934df51ee60ba8e51a8a09322e63d07f81e9704` includes five consecutive
separate native executions of
`RecordedMobileSuiteTest#interactiveTurn_backgroundAgent_followsBottomUntilFinished`, each
1 executed/passed, 0 failures/errors/skips, using #2246's newer merged genuine recording.
Earlier five passes at `528c4f966cf2af40e88c77283619845eda96be79` used the older capture.
Peer `late_tools` counts vary within one asset; source identity and strict replay identify
the recording. This repair authors none.

The [accepted verifier](https://github.com/pyrycode/pyrycode-mobile/pull/2377#issuecomment-6319512517)
confirms configured UI coverage: 337 executed/passed, 0 failures/errors, 1 additional
skipped, with the named recorded method present and passed once. JVM/build coverage:
6435 executed/passed, 0 failures/errors/skips, including 21 block, 9 prose and 4 authoritative
screen methods. Focused post-merge scripted background-Agent: 2 executed/passed, 0 failures/errors/skips.
Full scripted coverage executed 22: 21 passed, 1 failed, 1 additional skipped; the unrelated
model-switch failure passed its exact same-tree rerun (1 executed/passed, no failures/errors/skips)
and is tracked in #2380. macOS was outside this ticket's scope.

Raw XML/logs and source metadata were retained at documentation time under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2373/builder-20261021/`:
`reset-red/`, `blocks-newer-recording-green/`, `newer-native-1/` through `newer-native-5/`.
Verifier bundle: `795b38bd82594fad5cf8942dabda47efc0395ff1c30c65fbc8306247a7a56ccf`.

### Finished-marker reveal recurrence (#2373)

The [recurrence cause report](https://github.com/pyrycode/pyrycode-mobile/issues/2373#issuecomment-6325387770)
and [accepted verifier](https://github.com/pyrycode/pyrycode-mobile/pull/2430#issuecomment-6327757300)
identify the first #2334 failure at `2b4001820ed8b6dc409993dee99c0b4038ac5e05`:
the finished-marker display assertion after the later reply, before
`verifyAgentRunNavigation`. Its same-tree rerun executed/passed once, with zero
failures/errors/skips. The landed proof-entry readiness guard had not been reached.
Replay delivery/checkpoint/close mismatches were suppressed teardown fallout.
The builder inspected reset/catch-up and further publication in the phone timeline,
but the selected historical archive omitted this scenario's phone log. Surviving XML
proves the first assertion and passing rerun; exact historical geometry and interleaving
remain unknown. The controlled probes establish mechanisms without claiming that snapshot.

`assertFinishedAgentMarker` reveals the exact owned finished marker through the shared
chrome-aware geometry helper. For the later-reply boundary, the native scenario resolves
the raw Agent against the current admitted snapshot that also contains the settled later
reply. Authoritative generations re-scope rendered ids: retaining the running projection's
id, or refreshing it only once, can lose the marker during reset or geometry measurement.
The opt-in `questionAnswerTargetWithCurrentKey` bridge resolves current identity at each
key read. Readiness requires one merged list with that exact marker key; absent/wrong-family
content stays unready and multiple lists fail. Validate `IndexForKey` and invoke
`ScrollToIndex` together on the UI thread; a detached list or missing key dispatches
nothing and waits within the same deadline. A fresh key sampled before dispatch alone
still leaves a generation-change race.

The native repository wait and marker readiness share the original 30-second
`THREAD_TIMEOUT_MS` budget using monotonic elapsed time; the shared helper default is
10 seconds. Geometry retains three measurement/correction attempts, and the final
assertion requires the owned marker's finished text to be displayed. Default
`questionAnswerTarget`/`questionAnswerTap` callers retain strict removed-key failure.
The proof-entry reset and negative tests, one physical tap per transition, strict replay,
running/final placement, bottom following, nested child visibility/collapse and settlement
assertions remain. No production code, recording, sleep, forced expansion, scenario retry
or extra tap changes this contract.

All three causal controls in `BackgroundAgentBlocksScreenTest` executed once and failed
with zero errors/skips before their respective repairs; their XML and source patches are
retained. The growth control uses an extracted one-shot operation over base `908057ec7`;
generation geometry uses base `d442de05b`; dispatch uses base `d9350a790`, each with the
recorded working patch rather than claiming a clean committed red revision:

| Regression | Demonstrated pre-repair failure |
| --- | --- |
| `finishedMarkerIsRemeasuredAfterLaterReplyGrowth` | Later reply growth leaves the projected marker's finished text undisplayed. |
| `generationReplacementDuringFinishedRevealUsesTheCurrentOwnedKey` | Old key removed, replacement owned key present; `tap target left the projected list` during geometry. |
| `generationBetweenReadinessAndScrollDoesNotDispatchAStaleKey` | Generation replaced after readiness; `performScrollToKey` cannot find the sampled key. |

Keyed reveal can establish a history anchor and prevent displacement, so the repaired
growth probe requires positive finished display and chrome-clear geometry rather than
requiring the marker to become hidden first. `resetBeforeFinishedMarkerRevealWaitsForTheOwnedFamily`
restores the family only after observing absence. `anotherFinishedAgentCannotSatisfyTheOwnedMarkerCheck`
and `anotherAgentsFinishedLabelCannotSatisfyTheOwnedMarkerCheck` reject a wrong family
and another Agent's finished label while the owned Agent is running.

Final Linux source: `75bed5e5eaf1969e48a13dcbe191e69e65b194be`, with main
`d2130e229e5ae7382cf7f6cebe790db6ae8574d5` merged. Focused XML records 41 passed:
27 block, nine prose, four authoritative and the short-thread question-scroll method;
zero failures/errors/skips. The three causal controls and earlier readiness negatives
are present and passed. Five consecutive separate native executions of
`RecordedMobileSuiteTest#interactiveTurn_backgroundAgent_followsBottomUntilFinished`
each executed/passed once with zero failures/errors/skips and reached `closed-confirmed`.
Focused scripted background-Agent coverage executed/passed two, zero failures/errors/skips.
Earlier candidates remain separate evidence: `d9350a790` passed twice then failed its
third native run at stale keyed scroll; those runs do not establish acceptance.

The unchanged `interactiveTurn_backgroundAgent_followsBottomUntilFinished.json` recording
has SHA-256 `61709080f051154c15f0125e99e367ab011d0e8963f0074e6621ee18facd7baa`.
Raw files were retained at documentation time under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2373/recurrence-20261021/`:
`historical-available/`, `growth-red/`, `generation-red/`, `dispatch-red/`,
`atomic-blocks-green/`, `atomic-native-1/` through `atomic-native-5/`,
`atomic-scripted-green/`, and the other `atomic-*-green/` caller reports.
Failed candidates and the failed intermediate `keyed-anchor-probe/` are retained there
as failures. The verifier checked all 155 manifest entries. These private raw captures
have a retention limit; the counted results above are the durable evidence.

Configured Linux UI acceptance explicitly includes the named recorded method, passed
once: 337 executed/passed, zero failures/errors, one additional skip
(`renameAtFigmaViewport`). The fresh JVM/build gate executed 6,443, with 6,441 passed,
two failed and zero errors/skips; all 27 block, nine prose and four authoritative methods
passed. Preserve the accepted dispositions: `retentionInvariant_trimClearsCertification`
also failed on merged main and is tracked in #2439;
`heldInvariant_coverageIncludesNewerAcceptedRowsBeforeSatisfyingTheirCapture` passed its
named same-tree rerun. Scripted-all initially executed 22: 21 passed, one failed,
one additional skip (`interactiveTurn_seededChannel_durableGapCatchUp`). Its failed
`interactiveTurn_seededChannel_contextOverflowCompactReachesDaemon` passed the exact
rerun (one executed/passed, zero failures/errors/skips), tracked in #2442. Initial
failures remain failed; the verifier accepted these unrelated dispositions.
Verifier bundle: `e5eba22d4c5739ad10d084895ccb7b724dee3e17332f0e4485b00c54ec786b7e`;
its gate logs retain counted XML. No new real-Claude capture was needed, and macOS
was outside this Linux ticket's verification scope.

### Post-keyed-reveal reset recurrence (#2373)

The later #2387 failure at `eda298fca8e0584107dbbb1ec3993ffd13b45eb6` reached a
second, unguarded matcher scroll after the finished marker's atomic keyed reveal.
Its first assertion was the absent merged scroll container in `questionAnswerLocation` /
`performScrollToNode`, before the navigation proof. The original method failed once and
passed once in its same-tree two-method rerun. The published bundle
`1cac1378fe6fc1580d9df9e42e70367c6fb81b974eabdd229a9c6632ec1bd7ed`
retains the failed stack and counted summaries; it has no phone timeline for this
occurrence and no retained suppressed replay-close failure. A later unmerged match
does not establish simultaneous tree visibility or the historical reset interleaving.
The [cause report](https://github.com/pyrycode/pyrycode-mobile/issues/2373#issuecomment-6333715979)
records those limits and the original bundle digest.

The causal control `resetAfterKeyedRevealWaitsForTheOwnedFinishedMarker` uses baseline
`2df547ab26c1264ee79d9aca16709702487ec5cf`, containing both earlier repairs, plus the
retained new test and content-free boundary callback. It empties the real projection
after successful keyed placement and restores it only on a later identity sample.
The original operation failed once at the same missing-list matcher scroll. The repair
omits that redundant scroll only for the current-key bridge, allowing existing
readiness-aware geometry reads to resolve the current owned marker. The new control
then passed. The post-reveal wrong-Agent negative requires timeout rejection and cannot
accept another Agent's displayed finished label. Default helper callers keep their
matcher scroll. Production code, strict recording bytes, deadlines, geometry budget,
physical taps, placement, following and settlement assertions are unchanged.

The earlier #2377 guard protects navigation-proof entry, which this failure never
reached. The #2430 repair protects the earlier atomic keyed dispatch; it cannot keep
the projection mounted for an additional matcher scroll afterward. These causes and
their controlled regressions remain distinct. The retained builder source
`890702783a7611403693f8e7ce1b4a0426b73b29` passed 41 focused JVM checks, five separate
one-method native runs and two scripted background-Agent methods. Its final plan-only
head was `544fca1f7721f5ddf2a4092efd738251fb017958`. Those are historical repair
results. The subsequent verifier found no source defect but stopped after 211 script
tests, one failed, before the remaining configured gates ran; #2455 owns that separate
report-retention repair. No configured acceptance is inferred from the five focused passes.

Original red/green reports, source patches, historical failure summaries and the five
native runs remain under agents `logs/evidence/2373/recurrence-20261022/`. The unchanged
recording hash is the same `61709080f051154c15f0125e99e367ab011d0e8963f0074e6621ee18facd7baa`
recorded above. A fresh Linux integration check on
`67146d2defd26d204d15e292c56fb28bdc5ad1ae`, after merging main containing #2455,
passed 41 affected JVM methods: 28 block, eight prose, four authoritative scenario and
one short-thread question-scroll method, zero failures/errors/skips. This focused result
does not by itself establish complete configured acceptance or a fresh independent review.

The same integrated code candidate then passed complete configured Linux acceptance:
218 script tests, static checks, 5359 JVM methods, assembly, 333 executed device methods
and all 24 scripted methods, with zero failures/errors. The device report has one additional
skip, `RenameDialogCaptureTest.renameAtFigmaViewport`; the named recorded Agent method
executed and passed once in that full UI run, reaching `keyed-ready` and the final
`closed-confirmed` proof. Both new reset controls passed in the full JVM run. The scripted
report includes `interactiveTurn_seededChannel_backgroundAgentMovesAndSettles` and
`interactiveTurn_seededChannel_earlierSessionAgent_survivesRestartAndSettlementReconnect`,
both executed/passed. These gates use zero real Claude turns. They establish counted
execution on this candidate, not a fresh independent verifier verdict.

Manual-acceptance raw XML, phone/daemon/relay logs, command lines, source/runtime identities and
prior failed attempts are under agents `logs/evidence/2373/manual-20261023/`; its
1536-file `SHA256SUMS` digest is
`a5678adea178f1a5daf16bef9aa42546934afcad33c0e5500e9b4dafc9fa8c62`.
The device used the Android 13/API 33 x86_64 Google ATD image revision 1. The retained
metadata distinguishes the tested code candidate from later documentation-only commits.

The first manual script discovery executed 218 tests with three failures caused by the
runner exporting Android-only `PYRY_GATE_EVIDENCE_DIR` into script fixtures. Removing
that setting passed the same three checks, then the full 218-test run on unchanged
source. Those failed captures and the earlier #2455 failure remain failures.

The [independent verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2454#issuecomment-6360402269)
reviews `a32c07ca8450d4b2065c2bb0b3366e94d4cdb63d` on Linux. All six configured
gates passed: pre-verify, docs guard, 218 script tests, JVM with build/static checks,
UI and scripted-all. Counted reports record 5388 JVM methods executed/passed, zero
failures/errors/skips; 333 device methods executed/passed, zero failures/errors and
one additional skip (`RenameDialogCaptureTest.renameAtFigmaViewport`); and 24 scripted
methods executed/passed, zero failures/errors/skips. These are separate from the
5359-method manual JVM run above.

Both `resetAfterKeyedRevealWaitsForTheOwnedFinishedMarker` and
`resetAfterKeyedRevealCannotAcceptAnotherFinishedAgent` executed/passed in the full JVM
gate, alongside the earlier reset, growth, generation/dispatch and ownership controls.
The required `RecordedMobileSuiteTest#interactiveTurn_backgroundAgent_followsBottomUntilFinished`
executed/passed once in the full UI gate; the verifier confirmed `keyed-ready` and final
`closed-confirmed` phone stages. Both named scripted background-Agent methods above
executed/passed in scripted-all. No separate focused verifier run is implied.

The verifier confirmed both changed Kotlin files match manual candidate `67146d2` byte
for byte and the recording retains the hash above. It verified the 1536-file manual
manifest, causal red/green controls, five historical native manifests and the original
138-file recurrence manifest. Verifier bundle:
`b491f708417bb0d55152ecd5795b5f14210cdf98c8feca32d2f8993bbc6e7010`.
Counted XML was retained in agents `logs/verifier-gate_#2373_4.log`,
`logs/verifier-gate_#2373_5.log` and `logs/verifier-gate_#2373_6.log` at documentation
time; the bundle retains the JVM log tail and a pointer to its complete host log.
No fresh live-Claude observation is claimed. Historical geometry/timeline limits
remain unresolved by these passes; macOS is outside this Linux verification scope.

### Current-family navigation after readiness (#2493)

The finished-marker helper above already resolves the current rendered identity.
`verifyAgentRunNavigation` also needs the complete current Agent/run/children
family after readiness: a later admitted generation can replace all rendered keys
before navigation or during geometry measurement. Resolving only the marker, or
retaining the first ready family, leaves the owned expansion action unreachable.
The affected native caller now resolves the same raw Agent, settled reply and exact
late tool children together from one admitted snapshot. Navigation and reveal
boundaries resample that family; exact-key validation and scroll dispatch happen
together on the UI thread. Fixed-key callers keep strict missing-key failure.

One monotonic navigation budget and each existing open/close budget cover the
resampling; a replacement cannot restart a deadline. The proof still requires
exact closed/open child membership, positive attributed prose visibility,
chrome-clear geometry and one physical pointer tap per transition. Readiness alone
proves none of those later assertions. No production authority, recording bytes,
request matching, assertion or deadline changed.

The macOS review of `6c487ab17d4aa79b03c3b6b1ee35cf54998ea9d6` retained two native
failures: the full UI run could not find the owned “Using tools:” action; the exact
rerun reported `tap target left the projected list`. Main passed its exact
comparison. Two controlled real-screen probes over that source replaced the family
after readiness and during geometry: two executed/failed, zero errors/skips, with
those respective signatures. They demonstrate the mechanisms, not the exact old
phone interleaving.

The first repair attempt failed compilation before execution. The next two-case
run passed the stale-identity boundaries but both cases failed positive owned-prose
visibility. The compact fixture needed its existing exact `prose-$agent` reveal key,
including the initial family. With that fixture input, the next fresh pair passed
2/2, zero failures/errors/skips; viewport, replacement stimuli, physical taps and
assertions remained. The affected `BackgroundAgentBlocksScreenTest` then passed
30/30 and instrumented compilation passed. On committed
`d18cfe8b3d2f094e07b1602a3430d5e5423bfc34`, the exact previously twice-failed
`RecordedMobileSuiteTest.interactiveTurn_backgroundAgent_followsBottomUntilFinished`
passed 1/1, zero failures/errors/skips, exit 0, with finished pointer-proof phases.
These author results retain their own source identity.

Independent review accepted integrated macOS
`07b3eaadbc8b20ca6a3fbe64987454a5fcbc9cee`: the screen class passed 30/30 within
5,522 executed/passed JVM tests, zero failures/errors/skips. The named background-Agent
replay passed in the original full UI invocation: 250 executed, 248 passed, two
unrelated failures, zero errors/skips; both failures passed the exact 2/2 rerun.
The configured scripted run's five inherited failures remain failures, tracked
separately from this replay pass. See the
[accepted counts, runtime identities and evidence limits](../../recorded-tests.md#accepted-macos-integration-2493).

At documentation time, causal patches, failed attempts, XML and phone pointer proof
were retained under agents `logs/evidence/2493/manual-ui-rework-20261023/`, including
`evidence/red/`, `green-v2/`, `green-v3/`, `affected/` and `native/ui-otn5y02f/`.
The published manual bundle authenticated 201/201 entries; manifest SHA-256 is
`57c246ed50b710b0f29c8c8ba3f6377d5617f306052f09815d99b075499fac1b`.
Built APK hashes do not establish installed-byte identity, and no extra independent
per-process cleanup audit or historical-interleaving reconstruction is claimed.
Earlier finished-marker evidence and failures above retain their original results;
Linux is outside this macOS ticket's scope.
