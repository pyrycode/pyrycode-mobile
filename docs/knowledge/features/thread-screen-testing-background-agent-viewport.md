# Thread screen — background-agent viewport testing

Split from [thread screen testing](thread-screen-testing.md) for #2039 to keep
the overview under its size cap. Existing coverage and evidence are preserved.

## Reader geometry during background Agent relocation (#1955)

`BackgroundAgentViewportTest` mounts the real `ThreadScreen`; Android-visible
`BackgroundAgentViewportDeviceTest` overrides select all 23 shared methods into
the routine UI gate. Native View draws are sampled at explicitly advanced frames
from publication through settlement. Followers must remain at index/offset zero in
every rendered frame. Readers retain stationary row membership and coordinates
within one physical pixel, except for the asserted normal newest-end clamp, and
remain unfollowed afterward. Idle-only assertions could pass after an intervening
jump. These probes establish [viewport transfer](thread-screen-how-it-works-list-and-status-row.md),
while the retained E2E scenarios establish placement/navigation integration.

Keep the moving block bottom-most visible with collapse on and off, offscreen moves,
multiple completions, another running block, simultaneous stationary growth and a
viewport wholly inside a tall child. Delayed-receipt probes publish the finished
roster first, then its receipt in history or at the newest end. Block-crossing probes
cover both still-running and already-finished neighbours, including direct and split
completion; terminal replays must remain inert. A neighbour's insertion changes a
stationary finished block's predecessor without moving that block's own destination.

Boundary probes must prove cold geometry rather than merely pass with warmed heights.
The cold-measurement fixture calibrates in a retired composition, then creates a
fresh list directly inside `tall-a`; its measurement observer rejects older completing
block rows before completion. A tall remaining running block prevents clamping from
hiding an incorrect boundary. Separate probes invalidate an expanded offscreen tool's
cached neighbour and restore saved expansion into a cold cache. Unplaced measurement
must share actual saved tool expansion, not the default of a new state owner.
Negative controls on the preceding implementation lose stationary finished B's
visible membership and shift expanded boundaries by 96 physical pixels.

Retain the logical reverse-layout offset negation and exclude wholly newest-side
chrome-hidden anchors. Observe the relocation generation only after placement, with
intermediate follow bookkeeping suppressed; otherwise a key transfer or end clamp
can be mistaken for reader input. Simultaneous stationary-anchor growth needs its
old height carried into #1942's correction as well as the padding delta.

**Counted evidence, 2026-10-09.** The
[final verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2016#issuecomment-6088220558)
on `ef4b4b21657f` confirms matching sets of 23 viewport methods in fresh JVM XML and
full Android UI XML. Each method below executed once and passed; counts are
**executed/failed/skipped**. Documentation also inspected the preserved final builder
JVM report in `/tmp/builder-1955/rework3/focused-jvm/` and Android report in
`/tmp/builder-1955/rework3/viewport-question-device/`: each viewport class is
**23 executed, 23 passed, 0 failed, 0 skipped**.

| Named method | JVM | Full Android UI gate |
| --- | --- | --- |
| `completionAcrossFinishedBlock_preservesTallStationaryChild` | 1/0/0 | 1/0/0 |
| `delayedReceiptAtNewest_followerKeepsNewestEveryFrame` | 1/0/0 | 1/0/0 |
| `delayedReceiptAtNewest_readerKeepsStationaryRows` | 1/0/0 | 1/0/0 |
| `delayedReceipt_followerKeepsNewestEveryFrame` | 1/0/0 | 1/0/0 |
| `delayedReceipt_readerKeepsStationaryRows_collapsed` | 1/0/0 | 1/0/0 |
| `delayedReceipt_readerKeepsStationaryRows_uncollapsed` | 1/0/0 | 1/0/0 |
| `followerCompletion_keepsNewestEveryRenderedFrame` | 1/0/0 | 1/0/0 |
| `followerCompletion_withAnotherRunningBlock_keepsNewestEveryFrame` | 1/0/0 | 1/0/0 |
| `followerMultipleCompletions_keepNewestEveryFrame` | 1/0/0 | 1/0/0 |
| `fullViewportCompletion_fillsVacancyAndClamps` | 1/0/0 | 1/0/0 |
| `fullViewportCompletion_retainsOlderBoundaryAgainstRemainingBlock` | 1/0/0 | 1/0/0 |
| `fullViewportCompletion_withColdMeasurements_retainsOlderBoundary` | 1/0/0 | 1/0/0 |
| `fullViewportCompletion_withInvalidatedExpandedTool_retainsBoundary` | 1/0/0 | 1/0/0 |
| `fullViewportCompletion_withRestoredExpandedTool_retainsBoundary` | 1/0/0 | 1/0/0 |
| `multipleCompletions_keepStationaryReader` | 1/0/0 | 1/0/0 |
| `newestReceiptAcrossRunningBlock_followerKeepsNewestEveryFrame` | 1/0/0 | 1/0/0 |
| `newestReceiptAcrossRunningBlock_readerKeepsStationaryRows_collapsed` | 1/0/0 | 1/0/0 |
| `newestReceiptAcrossRunningBlock_readerKeepsStationaryRows_uncollapsed` | 1/0/0 | 1/0/0 |
| `offscreenCompletion_preservesStationaryRows` | 1/0/0 | 1/0/0 |
| `splitCompletionAcrossFinishedBlock_preservesTallStationaryChild` | 1/0/0 | 1/0/0 |
| `visibleCompletion_preservesStationaryRows_collapsed` | 1/0/0 | 1/0/0 |
| `visibleCompletion_preservesStationaryRows_uncollapsed` | 1/0/0 | 1/0/0 |
| `visibleCompletion_withStationaryGrowth_preservesTopEveryFrame` | 1/0/0 | 1/0/0 |

The dispatcher full UI command
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui` passed:
**245 executed, 245 passed, 0 failed, 1 skipped**. The sole skip was
`RenameDialogCaptureTest.renameAtFigmaViewport`; none of the viewport methods skipped.
Required existing JVM classes passed with executed/failed/skipped counts:
`BackgroundAgentBlocksTest` **21/0/0**, `BackgroundAgentBlocksScreenTest` **15/0/0**,
`ThreadListFollowTest` **16/0/0**, `ThreadScreenFollowTest` **11/0/0** and
`ThreadReaderGeometryTest` **6/0/0**; `ToolCallRowTest` also passed **26/0/0**.
The full UI gate retained the six streaming-geometry device passes and the repaired
`QuestionBatchModalTest.ime_keeps_the_last_other_field_and_actions_reachable_at_320_by_700`
pass (**1/0/0**). Its synthetic append explicitly selects the draft end after IME
reveal, retaining exact `draft typed`, focus, visibility and action reachability
assertions. Real IME behavior needs the device probe.

Fresh full scripted and live runs, including each retained named background-agent
method's **1/0/0** result, are recorded in
[background-agent acceptance evidence](../../e2e-interactive-stream.md#background-agent-viewport-preservation-1955).
Documentation ran only the docs guard.

## Supplied authoritative settlement (#1985)

`AuthoritativeThreadScreenTest` additionally uses complete snapshots through the
same screen. `hiddenRootSettlementPreservesReaderInItsChildThroughRenderedFrames`
and `outerSettlementPreservesReaderInNestedForegroundChildThroughRenderedFrames`
sample native rendered frames while the reader is inside a departing subtree.
Require stationary history membership/coordinates throughout transfer, not just
idle state; visible-root-only membership loses hidden roots and nested foreground
children. `terminalBlockAlreadyAtItsRecordedEndDoesNotRelocateItsReader` rejects
movement inferred only from another block's insertion.

`readerAwayFromNewestRetainsStationaryAnchorAcrossSettlementAndReload` and
`followerStaysAtNewestAcrossSettlementAndRepeatedDelivery` preserve the reader/follower
split across replay. `nestedLaunchStaysBeforeLaterForegroundSiblingAcrossSettlement`
and `nestedLaunchStaysBeforeLaterSiblingOfHiddenForegroundParentAcrossSettlement`
retain launch-marker sibling order while the child block settles independently.
Four projection probes cover active/terminal children under shown/hidden foreground
parents, with permutation and replay assertions. Also retain two-active-agent,
prior-session terminal, nested-background independent placement and foreground
Read/thinking probes: visual bottom pinning must not determine main-turn activity.

All seven named screen methods above executed once and passed in the retained
`/tmp/builder-1985/bash32-focused/TEST-de.pyryco.mobile.ui.conversations.thread.AuthoritativeThreadScreenTest.xml`
(`2026-10-11T09:29:06.743Z`): screen class **23 executed, 23 passed, 0 failed,
0 skipped**. The [final verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2076#issuecomment-6107772890)
confirms matching **23/0/0** screen and **25/0/0** projection counts in the full JVM
gate. These supplied-screen probes are JVM evidence; the full UI gate's
**258/0/1** counts do not establish Android execution of this class. Existing legacy
viewport coverage above remains intact. [#1988](https://github.com/pyrycode/pyrycode-mobile/issues/1988)
retains production activation and the two named real-Claude background-agent
methods plus prior-session terminal integration proof; see [the renderer coverage](thread-screen-testing.md#supplied-authoritative-renderer-1985).

## Exact Agent ownership on outlined tools (#2340)

`AuthoritativeAgentOwnershipTest` mounts `ThreadScreen` with real
`projectAuthoritativeThread` output. `exactToolOwnerSurvivesCompletionAndThreadReopen`
uses collapse off; `exactToolOwnerSurvivesCollapseReopenAndThreadReopen` uses collapse on.
Both retain the positive unmerged matcher
`hasTestTag(TOOL_ROW_TAG) and hasAnyAncestor(hasTestTag("background-agent-child:<exact Agent message ID>"))`,
further selecting each tool by content. They cover direct and indirectly parented children
while running and completed, full composition removal/reopen, and exclusion of foreground
tools and another Agent's tools from the selected owner. The collapse probe also closes and
reopens the run in each lifecycle state. It requires the first tool key present as the closed
header, sibling keys absent and all outlined tools under that owner absent; expansion restores
individual keys and exact owners. Key membership alone cannot establish outlined-row ownership.
See [ownership and run identity](thread-screen-subagent-tool-rows.md#collapsing-runs-of-consecutive-tool-rows-1635).

**Retained native evidence, macOS, 2026-10-20.** Documentation inspected `revision.json`
and `dispatcher.xml` for all three focused runs under
`$AGENTS_REPO_PATH/logs/evidence/2340/builder-20261020-macos/`, the raw location at review
time. The command was
`python3 scripts/android-test-gate.py ui --tests de.pyryco.mobile.ui.conversations.thread.AuthoritativeAgentOwnershipTest`.

| Run | Executed | Passed | Failed | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| Native red, `8030c6d5751298a7bf4fcb96294ce198438a23fa` | 2 | 0 | 2 | 0 | 0 |
| Intermediate repair, incorrect collapsed-key expectation | 2 | 1 | 1 | 0 | 0 |
| Native green, `9fdaf07bf3e4757693ad297744f4caa5afdd4db6` | 2 | 2 | 0 | 0 | 0 |

The red used unchanged production at `1a845e57d9483e42d2b9a71c7196135e49c506eb`;
both methods failed at the exact owner matcher. The intermediate failure concerned only
the new test's expectation that the collapsed representative key disappear. Both named
methods are present and passed in green XML. The
[verifier's accepted PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2342#issuecomment-6301421930)
also confirms their phone-log completion and matching native hash manifests. Focused bundles
omit wrapper process exit codes and the builder command output is truncated, so these are
counted XML results, not independently verified wrapper exit statuses.

The dispatcher JVM/build report records **6,355 executed/passed, 0 failed, 0 skipped**,
including both ownership methods. Initial full UI results were **335 executed, 331 passed,
4 failed, 1 additional skipped**; scripted results were **21 executed, 19 passed, 2 failed,
1 additional skipped**. The verifier accepted inherited failures tracked in #2343/#2344
and dispatcher-confirmed passing reruns for the two intermittent failures; these initial
runs were not all-pass runs. No tests were executed during documentation.

This ticket's evidence is macOS-scoped. The isolated PR #2337 Linux combination and its
`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_backgroundAgentMovesAndSettles`
and `interactiveTurn_seededChannel_earlierSessionAgent_survivesRestartAndSettlementReconnect`
pair were not run here and remain outside this ticket's OS verification scope. The supplied
scripted gate reports the first method passed; it does not prove the isolated combination
or the second method. Genuine capture acceptance remains with #2246.
