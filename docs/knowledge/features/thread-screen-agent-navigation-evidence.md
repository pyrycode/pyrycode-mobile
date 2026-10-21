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
