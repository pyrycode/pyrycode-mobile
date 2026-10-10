# Thread screen — testing

Split out of [Thread screen](thread-screen.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

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

`SavedThreadFirstDrawDeviceTest.savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen`
uses real `FileConversationCache`, `CachingConversationRepository`, `ThreadViewModel`,
production `ThreadContentScheduling` and `ThreadScreen` on the configured Android 13
managed device. It prepares and persists each active fixture before its case group: **20 ordinary
saved messages**, then **18,000 displayed message rows / 36,000 durable entries /
18,000 spans**. Each
fixture runs offline and with a connected delegate whose newest response stays held.
First opens create fresh cache/repository instances; reopening uses that repository
with a new ViewModel and composition. All eight cases require the newest saved message
to draw within **1,000 ms**, without waiting for the newest response.

`SystemClock.elapsedRealtimeNanos` starts before cache/repository and ViewModel
construction. Cache reading, collection, projection and drawing are inside the interval;
fixture generation and APK/activity startup are outside it. Cumulative probes record
row restore, repository snapshot, complete screen content and the committed newest-row
frame. A root `OnDrawListener` checks exact newest text, placed semantics and nonzero
bounds wholly inside the message viewport, then registers a frame-commit callback.
Only that callback records draw time. A parent draw modifier missed child render-layer
updates; first StateFlow emission, Compose virtual time, loading semantics and eventual
idleness cannot establish the draw deadline. Polling only waits for recorded evidence.

`slowRestore_negativeControlRejectsTheSameFirstDrawBound` inserts **3,000 ms** into
cache reading in the same timed open path and catches the identical bound assertion's
failure. The #2018 full UI run drew at **3,188 ms**, with restore/snapshot/content at
**3,040/3,048/3,102 ms**. Passing this test means the latency assertion rejected the
slow path, not that the slow path met the deadline.

`SavedThreadOpenTest.savedRowsDoNotWaitForNewestResponse` independently holds newest
completion in controlled tests. Device assertions retain exact saved rows, marker
anchors, one newest ask per connected opening and zero offline asks. Keep
`ThreadHistoryProjectionWorkerTest` and
`ThreadFragmentedHistoryDeviceTest.sparseFragmentedRestore_opensEditsAndScrolls_onePagePerPull`
for projection and reader-only gap paging. The older sparse four-row fixture and its
15-second eventual-completion allowance concealed large displayed-history allocations;
keep the full displayed-row fixture and phase measurements when changing restore work.
See [validated decode reuse](conversation-cache-layout.md#thread-document-readers-1949)
for exact-byte freshness and proof compatibility.

**Counted evidence, 2026-10-09.** The original
[full UI failure](https://github.com/pyrycode/pyrycode-mobile/pull/2015#issuecomment-6086154606)
on `2367d43a7` recorded **222 executed, 221 passed, 1 failed, 1 skipped**. Fragmented
offline first open drew at **1,026 ms** (restore/snapshot/content **600/808/933 ms**),
stopping the method before the other three fragmented cases. A focused reproduction
missed at **1,012 ms** (**533/764/932 ms**). The PR also preserves a repaired-commit
run under competing host build load that missed at **1,334 ms** (**705/1,014/1,249 ms**).
Neither a focused pass nor later success erases these misses; the measured bound is for
the configured device, not arbitrary host contention.

The repaired
[verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2015#issuecomment-6087126093)
on `c5b6319c78c2` cites fresh UI XML timestamp **2026-10-09T18:36:59** and records
these cumulative monotonic milliseconds:

| Fixture / mode / opening | Restore | Snapshot | Complete content | Newest drawn |
| --- | ---: | ---: | ---: | ---: |
| Ordinary / offline / first | 12 | 16 | 57 | 116 |
| Ordinary / offline / reopen | 11 | 12 | 64 | 109 |
| Ordinary / held newest / first | 12 | 16 | 67 | 116 |
| Ordinary / held newest / reopen | 12 | 13 | 22 | 149 |
| Fragmented / offline / first | 480 | 657 | 766 | 852 |
| Fragmented / offline / reopen | 82 | 267 | 407 | 453 |
| Fragmented / held newest / first | 585 | 752 | 850 | 913 |
| Fragmented / held newest / reopen | 90 | 272 | 351 | 400 |

`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui` recorded
**222 executed, 222 passed, 0 failed, 1 skipped**. The first-draw, negative-control
and sparse reader-paging methods each executed once and passed (**1/0/0** for
executed/failed/skipped); the sole skip was `RenameDialogCaptureTest.renameAtFigmaViewport`.
The full scripted-all gate recorded **22 executed, 22 passed, 0 failed, 0 skipped**.
Fresh unit evidence in the review records `DecodedThreadRestoreTest` (7),
`HistoryHashCompatibilityTest` (5), the held-newest method (1) and
`ThreadHistoryProjectionWorkerTest` (6), all passed with zero failures or skips.

The subsequent dispatcher
[full live PASS](https://github.com/pyrycode/pyrycode-mobile/issues/1949#issuecomment-6087370255)
ran `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` on
`c5b6319c78c2` merged with `origin/main` at `7c1eb26fccfb`. The dispatcher report
`2026-10-09T18-45-37-128Z` records **65 executed, 65 passed, 0 failed, 0 skipped**,
with no flaky passes.
`InteractiveStreamE2ETest.interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`
executed and passed (**1/0/0**) in that full suite. This retained rung-3 operator flow
proves offline/reconnect integration; the controlled device fixtures establish the
latency bound. No separate focused live run or new ladder scenario is claimed.

### Allocation margin and retained evidence (#2018)

The [repair and final verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2024#issuecomment-6091171021)
preserve the same first-draw bound and endpoint. At builder base
`5133aa796352a5136df426979ab0634e8a554140`, the whole class passed (2/2/0/0),
then the isolated named method failed (1/0/1/0, exit 1). Fresh baseline XML
`/tmp/builder-2018/baseline-isolated/TEST-pixel2Api33Atd-_app-.xml`, timestamp
`2026-10-09T22:01:09`, records fragmented offline first-open phases
**954/1325/1579/1722 ms**. The raw focused command used
`./gradlew :app:pixel2Api33AtdDebugAndroidTest --rerun` with
`-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.ui.conversations.thread.SavedThreadFirstDrawDeviceTest#savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen`
and `--console=plain`; retained invocations and revisions are in the
[plan](../../specs/architecture/2018-saved-thread-first-draw.md).
Counts below are **executed/passed/failed/skipped**, and phase tuples are
**restore/snapshot/complete-content/committed-draw**, cumulative monotonic ms.

Cache decode, validation and proofs dominated restore, amplified by GC/scheduling:
the IO-worker diagnostic measured **1293 ms elapsed / 577 ms CPU**. CPU time
explains elapsed variation but never replaces the wall-clock acceptance interval.
Duplicate metadata-write mapping and setup serialization also generated garbage
that survived into restore. Preparing the entire fragmented fixture before the
ordinary group delayed even a 20-row snapshot/draw. Prepare only the active fixture
and return its already-bound canonical coverage instead of a setup codec echo.
Generation remains outside the timer; real writes/reads and cold construction remain
intact. `fragmentedFixtureIsCanonicalWithoutDependingOnASetupCodecEcho` pins canonical
codec equality, counts, sampled rows, endpoint ids and proofs. Neither serializer
warming nor delaying the timer is a repair.

All partial-repair misses remain evidence. Each directory below is under
`/tmp/builder-2018/` and contains fresh `TEST-pixel2Api33Atd-_app-.xml` and logcat;
every run exited 1. They are first opens; the negative control is excluded here.

| Evidence directory | Fixture / mode | Cumulative phases | Counts |
| --- | --- | --- | --- |
| `baseline-isolated` | fragmented / offline | 954/1325/1579/1722 | 1/0/1/0 |
| `diagnosis` | fragmented / held newest | 758/1157/1337/1476 | 1/0/1/0 |
| `repair-class-miss` | fragmented / offline | 1323/1727/1877/2058 | 2/1/1/0 |
| `stream-miss` | fragmented / offline | 1216/1542/1851/2103 | 1/0/1/0 |
| `lazy-miss` | fragmented / held newest | 798/1004/1182/1297 | 1/0/1/0 |
| `writer-miss` | fragmented / offline | 843/1139/1273/1369 | 1/0/1/0 |
| `typed-writer-miss` | fragmented / offline | 940/1191/1378/1483 | 1/0/1/0 |
| `validation-miss` | fragmented / offline | 1451/1843/2208/2344 | 1/0/1/0 |
| `exclusive-miss` | fragmented / held newest | 784/947/1046/1146 | 1/0/1/0 |
| `streamed-write-miss` | fragmented / offline | 1052/1361/1560/1668 | 1/0/1/0 |
| `cpu-diagnosis` | fragmented / offline | 1313/1659/1890/2028 | 1/0/1/0 |
| `setup-echo-miss` | ordinary / offline | 89/905/933/1330 | 1/0/1/0 |

The final isolated pass on `b3cbeb687d812bcb81f832106252dee2c8be87a4` exited 0,
**1/1/0/0**, XML `/tmp/builder-2018/final-isolated/TEST-pixel2Api33Atd-_app-.xml`
(`2026-10-09T22:59:04`). The subsequent focused class plus fragmented interaction
passed **3/3/0/0**, saved-thread class **2/2/0/0**, both named methods **1/1/0/0**;
XML `/tmp/builder-2018/final-class/TEST-pixel2Api33Atd-_app-.xml`
(`2026-10-09T23:00:14`). Its negative control measured **3011/3074/3106/3160 ms**.

After destination-worker rework, runtime commit
`5f48c2c51bac985adee1c597d9b8e2fe7dc1c586` passed the focused device selection:
`python3 /tmp/builder-2018/focused-device.py` with fully qualified
`SavedThreadFirstDrawDeviceTest`, `ThreadFramePacingDeviceTest` and
`ThreadFragmentedHistoryDeviceTest` class arguments, comma-separated. The wrapper
acquires the existing FIFO `device_hold`, then runs the managed-device Gradle task
above with that class selection, `-Pandroid.testInstrumentationRunnerArguments.notPackage=de.pyryco.mobile.e2e`
and `-Pandroid.testInstrumentationRunnerArguments.disableAnimations=true`, as the
UI gate does. Exit 0; suite **4/4/0/0**, saved-thread class **2/2/0/0**, each named
first-draw/negative-control method **1/1/0/0**. Fresh XML is
`/tmp/builder-2018/rework-final-device/TEST-pixel2Api33Atd-_app-.xml`
(`2026-10-09T23:21:13`); logcats and `result.json` are beside it.

The dispatcher full UI command
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`
exited 0 on reviewed head `38659221b1dfa6949f979c2fcedb04690bf4cfda`:
**254/254/0/1**, saved-thread class **2/2/0/0**, each named method **1/1/0/0**.
Inspected XML `/tmp/verifier-2024/re-review-evidence/dispatcher-ui.xml`
(`2026-10-09T23:37:25`) and saved-thread logcats agree. The sole skip is
`RenameDialogCaptureTest.renameAtFigmaViewport`. The scripted-all gate passed
**22/22/0/0**; no new real-Claude scenario is needed.

| Case | Isolated | Initial focused class | Final focused class | Full UI gate |
| --- | --- | --- | --- | --- |
| Ordinary offline first | 180/202/224/330 | 35/44/57/154 | 28/30/53/141 | 21/23/46/98 |
| Ordinary offline reopen | 29/39/54/136 | 23/31/35/128 | 19/24/52/101 | 10/12/57/126 |
| Ordinary held newest first | 20/27/56/117 | 20/26/55/116 | 4/24/56/118 | 17/19/64/99 |
| Ordinary held newest reopen | 19/27/61/150 | 18/23/64/143 | 17/22/50/157 | 18/25/41/105 |
| Fragmented offline first | 420/547/752/858 | 372/481/583/698 | 376/479/639/724 | 381/480/592/667 |
| Fragmented offline reopen | 68/234/417/577 | 25/115/249/338 | 25/107/246/356 | 19/107/236/300 |
| Fragmented held newest first | 311/378/494/604 | 326/408/509/619 | 327/407/531/616 | 405/475/574/678 |
| Fragmented held newest reopen | 26/97/262/341 | 23/96/201/267 | 34/116/209/268 | 22/95/221/269 |

The final focused negative control measured **3043/3054/3099/3163 ms**;
the full UI control measured **3040/3048/3102/3188 ms**. Both passed by rejecting
the identical 1000 ms assertion with the 3000 ms read delay still inside the timed
path. Preserve all misses alongside these passes, exact rows/marker anchors, zero
offline asks, one newest-page ask per connected opening and the held response.
Device execution coordination supplements diagnosis; it does not erase a miss or
replace full-suite evidence.

**Post-#2018 restoration repair (#2026), 2026-10-10.** The
[verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2035#issuecomment-6094236218)
and [measured plan](../../specs/architecture/2026-saved-thread-restore-latency.md)
establish unnecessary JSON decoder/null-tracking allocation and general timestamp
parser state as the remaining restore cost. The baseline cache phases
read/decode/row-validation/metadata/proofs were **7/517/150/23/123 ms**, worker
**824 ms wall / 416 ms CPU**. A decoder-only configuration reduced allocations but
still missed the draw bound. Combining it with the strictly calendar-validated
whole-second UTC timestamp path reduced fragmented offline phases to
**1/168/10/14/82 ms**, worker **278 ms wall / 269 ms CPU**. The required metadata
writer reread also benefits. Keep the [decoder separate from encoding and retain
the legacy timestamp fallback](conversation-cache-layout.md#thread-document-readers-1949);
canonical hashes and persisted bytes must not change to gain margin.

No repository, scheduler, device probe or fixture change was needed. The timer
still starts before fresh cache/repository construction on first open, and before
a fresh ViewModel/composition on reopen with the same repository. Exact newest
text wholly inside the message viewport and its committed frame remain the
endpoint, with the original 20-message and 18,000-row / 36,000-entry / 18,000-span
fixtures, exact rows/marker anchors and zero offline / one held connected newest
request per opening. Whole-class success cannot replace isolated-method evidence.

The following retained fragmented offline first-open misses supplement every
earlier miss above. Directories are under `/tmp/builder-2026/`, each with XML,
selected-method logcat and `run.json`; all exited 1 with **1/0/1/0/0**
executed/passed/failed/skipped/errors. Originals under
`/tmp/builder-2006/rework-evidence/` remain untouched.

| Evidence directory | XML timestamp | Cumulative phases (ms) |
| --- | --- | --- |
| `prior/post-2018-branch` | 2026-10-09T23:53:04 | 721/848/994/1119 |
| `prior/post-2018-merge-base` | 2026-10-09T23:54:15 | 922/1212/1511/1688 |
| `baseline` | 2026-10-10T04:31:30 | 845/1053/1193/1314 |
| `decode-diagnosis` | 2026-10-10T04:46:43 | 602/800/1010/1153 |
| `decoder-isolated` | 2026-10-10T05:00:16 | 569/748/946/1139 |

**Isolated focused acceptance.** On source revision
`b1029033bd5cea81b457e3969a89dd2152791ef0`, each named method ran alone in a
separate invocation under the existing FIFO device hold:

```sh
./gradlew :app:pixel2Api33AtdDebugAndroidTest --rerun '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.ui.conversations.thread.SavedThreadFirstDrawDeviceTest#METHOD' -Pandroid.testInstrumentationRunnerArguments.notPackage=de.pyryco.mobile.e2e -Pandroid.testInstrumentationRunnerArguments.disableAnimations=true --console=plain
```

`METHOD` was separately
`savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen`
and `slowRestore_negativeControlRejectsTheSameFirstDrawBound`. Each exited 0,
**1/1/0/0/0** executed/passed/failed/skipped/errors. Fresh XML
`TEST-pixel2Api33Atd-_app-.xml` is retained in
`/tmp/builder-2026/timestamp-isolated/` (timestamp **2026-10-10T05:07:37**) and
`timestamp-control/` (**2026-10-10T05:07:59**), with selected-method logcat and
manifests beside it. `/tmp/builder-2026/focused-evidence.json` records commands,
revision, exits, counts and all cumulative tuples.

**Dispatcher full UI acceptance.** The unchanged
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`
exited 0 on reviewed head `753bd3bb444a8d7b1e56f171db6f3fc2af114fdc`, which
differs from the focused source revision only in the plan. Inspected XML
`/tmp/verifier-2035/evidence/dispatcher-ui.xml` (timestamp **2026-10-10T05:25:51**)
counts **256/256/0/1/0** executed/passed/failed/skipped/errors; both named methods
executed and passed individually, **1/1/0/0/0** each. The sole skip remains
`RenameDialogCaptureTest.renameAtFigmaViewport`. Selected-method logcats are
retained beside the XML. These are full-suite results, separate from the focused
runs above.

| Case | Isolated focused phases | Dispatcher full UI phases |
| --- | --- | --- |
| Ordinary offline first | 178/199/227/343 | 11/13/21/83 |
| Ordinary offline reopen | 35/68/104/218 | 8/10/47/125 |
| Ordinary held newest first | 20/26/55/117 | 11/13/82/171 |
| Ordinary held newest reopen | 20/26/54/100 | 14/20/76/137 |
| Fragmented offline first | 293/407/523/609 | 343/439/595/670 |
| Fragmented offline reopen | 37/118/247/339 | 19/93/225/273 |
| Fragmented held newest first | 258/346/472/611 | 252/321/452/498 |
| Fragmented held newest reopen | 29/175/374/493 | 18/87/203/278 |
| Delayed negative control | 3036/3389/3434/3533 | 3042/3051/3128/3206 |

Tuples remain cumulative **restore/snapshot/complete-content/committed-draw**, in
wall-clock milliseconds. Both controls passed by rejecting the identical
**1,000 ms** assertion after the injected **3,000 ms** restore delay. Focused
cache/proof/coverage/repository/held-worker checks passed **181/181/0/0/0**;
expanded timestamp compatibility separately passed **1/1/0/0/0**. The dispatcher
scripted-all gate exited 0, **22/22/0/0/0** (retained
`/tmp/verifier-2035/evidence/dispatcher-scripted.xml`). This repair adds no
real-Claude flow or ladder scenario.

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

## Detailed coverage

See [thread screen testing coverage](thread-screen-testing-coverage.md#testing)
for screen/ViewModel regressions, device evidence, session-error races, short-stream
anchoring and ViewModel re-sourcing. Split out after #1968 to keep this parent
under the overview size cap; the coverage headings retain their anchors.
