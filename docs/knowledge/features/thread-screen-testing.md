# Thread screen — testing

Split out of [Thread screen](thread-screen.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

## Passive reopen evidence (#2317)

Thread retention assertions can themselves collect cold repository flows and send
backfill. The peer-started-turn scenario uses a scoped tap of the application's
existing destination subscription for initial completion and reopened retention.
Require current host/conversation/connection ownership and a new active subscription
after navigation; saved rows from the old destination cannot prove reopen delivery.
Establish exact user uniqueness in current data, count its semantics while composed,
then reveal each retained reply row and revalidate current evidence. Scrolling away
can dispose the user row without removing it from the thread.

See [passive assertion contracts and counted Linux evidence](../../recorded-tests.md#passive-thread-assertion-evidence-2317)
for ownership/cleanup regressions, historical strict-replay failures, focused passes
and the later full UI gate pass. The original failure was request admission;
offscreen loss was not established.

## Supplied authoritative renderer (#1985)

`AuthoritativeThreadProjectionTest` supplies complete held snapshots;
`AuthoritativeThreadScreenTest` mounts the real `ThreadScreen` through the optional
presentation carrier intended for #1988. Keep all eight kinds, shown/positive-order
admission, inert unknown/malformed summary/status, attachments/markdown/copy,
complete historical/active/hidden parents, missing ancestors versus cycles,
prior-session terminal beside current-session active agents, duplicate/permuted
snapshot delivery, same-id revisions and epoch reset. Assert snapshot immutability
as well as displayed identity. A newest-page-only fixture misses held parentage.

Task probes cover recorded links without type and roster-shaped links, deduplication,
scoped item expansion across task-id reuse, authoritative running/finished groups,
capability gating and real screen-to-ViewModel recorded Stop dispatch. Retain a single
presentation instance outside recomposition when replacing callbacks: otherwise the
fixture can accidentally republish and hide a broken action binding.

The screen lifecycle regressions are
`retainedPresentationRebindsRecordedStopToNewReceiverAndPreservesPendingAttempts`,
`removingAuthoritativeRouteClearsFactsOnReboundReceiver`,
`simultaneousCallbackReplacementAndRouteRemovalRetiresPublishedOwnerAndRestoresActions`,
`ownerTransferRetiresOldFactsAndPendingWhileCurrentOwnerSendsOnce` and
`compositionExitRetiresPublishedOwnerAndReentryRestoresRecordedStop`.
They require stale Stop rejection, pending cleanup, restored legacy actions and one
recorded invocation on the current owner. `ThreadViewModelTaskStopTest` adds
`staleBindingRetirementCannotClearNewerFactsOrPendingAttempt` plus capability,
foreign-owner, epoch, replacement, terminal and legacy guards. Separate callback
replacement and route removal cannot substitute for their simultaneous probe.
See [settlement frame and sibling-order coverage](thread-screen-testing-background-agent-viewport.md#supplied-authoritative-settlement-1985).

**Counted evidence, 2026-10-11.** The
[final verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2076#issuecomment-6107772890)
on `63c64b73b909` records `./gradlew check`: **5,409 executed, 5,409 passed,
0 failed, 0 skipped**. Class counts are **25/0/0** projection, **23/0/0** supplied
screen and **10/0/0** ViewModel Stop (executed/failed/skipped). Documentation also
read the fresh retained focused XML under `/tmp/builder-1985/bash32-focused/`,
timestamps `2026-10-11T09:29:06`–`09:29:13`: the same **58 executed, 58 passed,
0 failed, 0 skipped**, including every named lifecycle probe above.

The configured full UI gate passed **258 executed, 258 passed, 0 failed, 1 skipped**;
`RenameDialogCaptureTest.renameAtFigmaViewport` was the unrelated skip. These are
suite counts, not a claim that the supplied-screen class ran on Android. Full
`scripted-all` passed **22 executed, 22 passed, 0 failed, 0 skipped**; the repaired
stream methods' evidence is in [the ladder](../../e2e-interactive-stream.md#supplied-renderer-and-scripted-display-fences-1985).
Documentation ran only the docs guard.

Production negotiation/store collection and activated-route proof remain with
[#1988](https://github.com/pyrycode/pyrycode-mobile/issues/1988):
`InteractiveStreamE2ETest.interactiveTurn_backgroundAgent_followsBottomUntilFinished`,
`interactiveTurn_backgroundAgent_replyStaysUnderAgent`, the prior-session terminal
regression and combined daemon #3077 proof. Supplied snapshots establish presentation
invariants, not that integration; no new real-Claude scenario is claimed here.

## Foreground read tracking (#1912, #1953)

See [foreground read tracking tests](thread-screen-testing-foreground-read-tracking.md#foreground-read-tracking-1912-1953)
for production-host composition isolation, content-edge measurement and retained gate evidence.

## Frame-paced content (#1968)

`ThreadFramePacingTest` drives explicit 60 Hz and 120 Hz frame arrivals and held
worker execution. Retain final delivery without another input, both finalized
message/turn-end orders, repeated ids, boundaries, reconnect overlap and held row
identity. The saturated raw-order probe uses the production-shaped 64-slot
`DROP_OLDEST` source plus coordinator switching seam and 1,000 deltas. A larger
suspending fake or a burst below combined buffer capacity can pass while losing
real inputs. The boundary probe queues 200 snapshots before a boundary and newer
outcome; a two-snapshot probe cannot expose delayed intake bookkeeping.

`ThreadPacedReadViewportTest.readVersionInvariant_pendingAndSkippedContent_waitForFrameLifecycleAndReveal`
presents one version while newer content waits, then exercises the real screen's
lifecycle, overlay, viewport and reveal barriers. Pair it with the ViewModel
read-version and receipt-barrier probes: old displayed rows must never receive
newer receipt claims, including evidence-only updates.

`ThreadFramePacingDeviceTest.productionDestination_burstPublishesOncePerFrame_finalDelivers_andRecollectionCleansUp`
uses the production destination factory and real Android frame clock. Controlled
probes establish the rate bound; the retained live ping and scripted multi-delta
scenarios establish streaming integration. See
[counted dispatcher evidence](../../e2e-interactive-stream.md#verification-status).

## Saved-thread first draw (#1949)

See [Thread screen — saved-thread first-draw testing](thread-screen-testing-saved-first-draw.md) for the preserved section and evidence.

## Folded-row composition reuse (#1954)

`ThreadRowContentTypeTest.foldedRows_exposeDistinctKindsAndSharedMessageTypeInActualListLayout`
(in `sharedTest`) mounts the real `ThreadScreen` and obtains its `LazyListState`
through `LocalThreadListCompositionObserver`. Scroll to each keyed entry before
reading `layoutInfo.visibleItemsInfo.contentType`: the queued row, collapsed tool-run
header, individual tool and message bubble must have four distinct, non-null types,
and two different user/assistant messages must share a type. A pure helper test alone
can pass while the screen omits the `itemsIndexed` content-type lambda.

`ThreadRowContentTypeMappingTest` (in `test`) covers every folded and delivered
sealed arm, nonempty distinct kind tokens, and stability across message identity,
text/role, tool identity, queue correlation, run expansion, agent completion and
banner level/text. Preserve both levels of coverage when adding a row kind.
These assertions establish [reuse metadata](thread-screen-how-it-works-list-and-status-row.md#folded-row-content-types-1954),
not a measured scrolling speedup or physical-device frame-time improvement.
See the [verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/1962#issuecomment-6057266904)
for acceptance evidence and its limits.

## Stable row anchoring (#1940)

The real-screen shared `ThreadRowAnchorTest` and Android-visible `ThreadRowAnchorDeviceTest`
prove singleton-to-run anchoring with queued rows below. See
[the key contract, counted red/JVM/device evidence and navigation assertion lesson](thread-screen-subagent-tool-rows.md#collapsing-runs-of-consecutive-tool-rows-1635).

## Reader geometry during streaming (#1942)

`ThreadReaderGeometryTest` in `sharedTest` mounts the real `ThreadScreen` with an
overflowing history and a pre-existing streaming reply taller than the viewport,
then scrolls to its top. Each advanced frame explicitly draws the native
`View` into a `Canvas` and samples composed bubble coordinates. An idle-only assertion
can miss transient drift; a root draw modifier alone recorded no native JVM frames.
The bound is one physical pixel for the reply top and older displayed rows. Motion
samples subtract consumed nested-scroll movement, preserving the user's requested
movement while detecting update-induced displacement.

| Named method | Coverage |
| --- | --- |
| `streamingReader_holdsTopAndOlderRowsEveryFrame` | Appended deltas, progressive reveal and completion at a tall index-zero reply. |
| `settledMarkdown_holdsTopForGrowthAndShrink` | Fully revealed identical-source settlement in both height directions. |
| `settlementFixtures_changeHeightWithoutProgressiveReveal` | Direct streaming/settled renderer comparisons with both caret states, independent of reveal. |
| `restingTouch_holdsReaderEveryFrame` | Held pointer during reveal and verified settlement growth/shrink. |
| `movingReader_preservesConsumedMovement` | Real drag and live fling through geometry and spacing changes, verified settlement in both directions, and natural fling completion. |
| `endSpacing_preservesReaderInBothDirections` | Ordinary-message 4dp → 16dp → 4dp adjustment at index zero and after moving to a history key. |

Settlement fixtures must hold the complete revealed source constant. Advance the
screen for 720ms, beyond the 495ms reveal catch-up budget, before recording the
streaming baseline; assert the actual height direction at completion as well as
position stability. An unfinished link grows when settled literally, while a fenced
block shrinks when its reserved caret line disappears. Heading syntax shares styles
between renderers and cannot establish settlement growth; unrevealed text flushed
on completion can masquerade as a renderer height change. The direct fixture method
checks both caret-on and caret-off states.

Select older/history bubbles by fixture identity and require them to be displayed.
Android lazy prefetch can retain an off-screen item's overlapping y coordinate, so
choosing a row by y alone can report false drift. A resting pointer need not set
`isScrollInProgress`; check the held pointer's rendered geometry. For motion, require
consumed drag displacement, fresh release velocity and an active fling at each
geometry update, then verify that the fling finishes naturally.

`ThreadReaderGeometryDeviceTest` in `androidTest` overrides all six named shared
methods with Android-visible `@Test` methods. This selects them in the routine UI
gate, which excludes shared-only classes; see
[where a screen test goes](development-verification-gates.md#where-a-screen-test-goes).
The live ping scenario checks integrated reply rendering; these frame probes establish
viewport stability.

**Counted evidence, 2026-10-09.** The
[verifier PASS on `fa3594797615`](https://github.com/pyrycode/pyrycode-mobile/pull/2002#issuecomment-6085143200)
records each table method as executed/failed/skipped **1/0/0** on JVM, focused managed
Android 13 and full UI Android. The preserved focused geometry XML and JVM geometry
XML each contain six executed, six passed, zero failed and zero skipped. The focused
JVM selection totals 53 executed/passed, zero failed/skipped, including
`ThreadListFollowTest` (15) and `ThreadScreenFollowTest` (11). Earlier old-behavior
negative controls executed five viewport methods: all five failed, none skipped;
this does not claim a negative-control rerun of the repaired settlement fixtures.

The dispatcher's full UI command
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui` recorded
**219 executed, 219 passed, 0 failed, 1 skipped**; every table method ran and passed.
The skip was `RenameDialogCaptureTest.renameAtFigmaViewport`. The full scripted-all
gate recorded **22 executed, 22 passed, 0 failed, 0 skipped**, using no real Claude
turns. Preserve focused geometry XML separately from scripted results: the initial
archive had been overwritten with a single scripted-stream case and was withdrawn.

The fresh full real-Claude run used
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` on
`fa3594797615` merged with `origin/main` at `516de44cb5b8`. The dispatcher report
`2026-10-09T16-39-50-798Z` records **65 executed, 64 passed, 1 failed, 0 skipped**.
`InteractiveStreamE2ETest.interactiveTurn_pingPrompt_streamsPingReplyIntoThread`
executed and passed (**1/0/0**); it was unchanged by this ticket.
`InteractiveStreamE2ETest.interactiveTurn_backgroundAgent_followsBottomUntilFinished`
failed initially, then passed in the dispatcher's one-test same-tree rerun
(**1 executed, 1 passed, 0 failed, 0 skipped**). The
[live-gate evidence comment](https://github.com/pyrycode/pyrycode-mobile/issues/1942#issuecomment-6085431737)
accepts the gate after that retry and records the flake. This is a full-suite ping
pass, not a separate focused ping run or a zero-failure initial full suite.

## Reader geometry during background Agent relocation (#1955)

See [background-agent viewport testing](thread-screen-testing-background-agent-viewport.md#reader-geometry-during-background-agent-relocation-1955)
for the 23 shared/Android frame probes, cold-boundary controls and counted evidence.

## Detailed coverage

See [thread screen testing coverage](thread-screen-testing-coverage.md#testing)
for screen/ViewModel regressions, device evidence, session-error races, short-stream
anchoring and ViewModel re-sourcing. Split out after #1968 to keep this parent
under the overview size cap; the coverage headings retain their anchors.
