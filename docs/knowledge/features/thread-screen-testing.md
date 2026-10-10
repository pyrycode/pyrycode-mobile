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
managed device. It prepares and persists each active source fixture once before its case group,
then copies its complete persisted bytes to independent offline/held-newest roots: **20 ordinary
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
ordinary group delayed even a 20-row snapshot/draw. Prepare only the active source
fixture once, copy its persisted bytes for each mode
(as refined in #2027 below), and return its already-bound canonical coverage instead
of a setup codec echo.
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

**Residual in-depth failure (#2027), 2026-10-10.** The original
[main-sweep evidence](https://github.com/pyrycode/pyrycode-mobile/issues/2027#issuecomment-6093748684)
on `f3186e5a5ed013540cf74ff410e9e36dcd75b775` exited 1 after 744 seconds:
**1571/1570/1/2/0** executed/passed/failed/skipped/errors. Fragmented offline
first open drew at **1577 ms**; the negative control passed. The interval from
last passing sweep `60ba3f24fed1e82630d03761ee7eef585bff19f4` establishes no
causal commit. Launcher focus followed activity `DESTROYED`, so it does not
establish focus loss. Originals are retained in the issue's refinement comment
and copied under `/tmp/builder-2027/prior/`.

The landed #2026 repair at `79b212e949b840b3aad5f96137bd6ad4377a35b9`
reduced production restore allocations, but did not eliminate residual isolated
misses. The [post-#2026 evidence](https://github.com/pyrycode/pyrycode-mobile/issues/2027#issuecomment-6094369932)
retains fragmented offline first-open tuples **737/1032/1277/1402 ms** on
`f0b4b2c1b00dc12e7f27b641be59ef69c0ffa8ad` and **1225/1491/1765/2054 ms**
on the landed merge base. Each exited 1, **1/0/1/0/0**. Originals remain under
`/tmp/builder-2006/rework-evidence/post-2026-{branch,merge-base}/`.

The [residual diagnosis and verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2037#issuecomment-6095042198)
retain these additional isolated first-open misses under `/tmp/builder-2027/`;
each exited 1 with **1/0/1/0/0**:

| Directory | Fixture / mode | Cumulative phases (ms) |
| --- | --- | --- |
| `baseline-isolated` | fragmented / offline | 711/997/1135/1264 |
| `bluetooth-isolated-1` | fragmented / offline | 656/917/1139/1315 |
| `state-isolated-2` | fragmented / held newest | 593/829/1011/1157 |

The baseline isolated worker used **631 wall / 328 CPU ms**, versus
**382/334 ms** in the unchanged in-depth pass: comparable CPU work did not
explain time off CPU. The held-newest miss persisted without Bluetooth native
aborts/restarts and logged app GC reclaiming **58 MB** during restoration and
continuing through projection/draw; its worker used **578/364 ms**. Repeating
complete synthetic serialization, mapping and proofs between modes recreated
\#2018's setup-allocation hazard. Persist each source fixture once and copy its
complete app-private directory to independent mode roots. Copying invokes no
reader, decoder, repository, ViewModel or composition. Each first-open timer
still starts before fresh cache/repository construction; each reopen keeps only
its own repository and creates a fresh ViewModel/composition. Source and copies
are cleaned in `finally`. Production code and persistence validation are unchanged.

After copying, isolated held-newest workers measured **253/239** and **265/244 ms**,
with no app GC overlapping those held-newest timed intervals. Repeated isolated,
class, in-depth and regular UI passes support this allocation repair without
attributing every historical miss to one cause. All eight probes are retained
and their unchanged **1000 ms** bounds asserted after cleanup; a timing miss
fails the method without retrying or hiding later cases. Worker queue/wall/CPU
and ViewModel installation/teardown events are diagnostic only. CPU time never
replaces monotonic wall time through the exact newest-text committed frame.

Bluetooth setup now requests emulator shutdown regardless of the persisted off
preference, observes exact OFF with no pending enable, requests shutdown again
and rechecks within a ten-second responsive-polling deadline. API 33 does not
support the attempted platform wait command; those `ready-*` attempts executed
zero tests and are not passes. OFF can precede queued recovery: restart/native
activity remains in some passing logs, so readiness observations prove neither
permanent quiescence nor the sole cause of latency. The verifier also retained
nonblocking findings about dialog restoration on setup failure and synchronous
shell calls exceeding the coroutine deadline; fake responsive-shell timeout
coverage does not prove stalled shell cancellation.

**Counted final builder comparison.** Runtime revision
`d08cb44916994cc9f7785d7684c97838746cd302` contains #2026. Later source changes
only clarify comments and rename a unit method. The isolated commands are the
same managed Pixel 2 API 33 ATD commands recorded for #2026 above, selecting each
named method separately, with FIFO coordination and animations disabled. The
whole-class run omits `#METHOD`. In-depth omits the class argument entirely and
adds `-Pandroid.experimental.androidTest.numManagedDeviceShards=2`, selecting
all non-e2e device tests. These selections differ from the configured regular UI
gate. Counts are **executed/passed/failed/skipped/errors**.

| Directory under `/tmp/builder-2027/` | Selection | Exit | Counts | XML timestamp (UTC, 2026-10-10) |
| --- | --- | ---: | --- | --- |
| `copy-isolated-1` | named first draw | 0 | 1/1/0/0/0 | 06:36:32 |
| `copy-isolated-2` | named first draw | 0 | 1/1/0/0/0 | 06:37:23 |
| `copy-control-1` | named negative control | 0 | 1/1/0/0/0 | 06:36:57 |
| `copy-control-2` | named negative control | 0 | 1/1/0/0/0 | 06:37:43 |
| `copy-class` | affected class | 0 | 2/2/0/0/0 | 06:38:12 |
| `copy-in-depth` | all non-e2e, two shards | 1 | 1573/1572/1/2/0 | 06:49:43; 06:49:23 |

Each directory retains fresh `TEST-pixel2Api33Atd*.xml`, selected-method
`logcat-…SavedThreadFirstDrawDeviceTest-<method>.txt` and `run.json` with revision,
command and exit; in-depth logcats are in `shard_0/`. Both named methods executed
and passed individually in the final in-depth XML, each **1/1/0/0/0**. The class
maximum draw/control was **773/3299 ms**. The unchanged baseline and intermediate
in-depth runs also exited 1, **1573/1572/1/2/0**, with both saved-thread methods
passing. The sole failure was the unrelated Keystore cleanup method
`AnswerHostSetupCleanupTest.targetedPairingAfterFixtureTeardownKeepsItsHostGuard`,
tracked as [#2036](https://github.com/pyrycode/pyrycode-mobile/issues/2036).
`focused-evidence.json` and the [plan revisions](../../specs/architecture/2027-in-depth-saved-thread-first-draw.md#revisions)
retain all attempts, including partial passes and misses.

**Dispatcher regular UI acceptance.** On reviewed head
`41aa0657a3e48c6d67a650fa5dd855036e6b6b41`, the configured command
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`
exited 0, **256/256/0/1/0**. Inspected XML
`/tmp/verifier-2037/evidence/dispatcher-ui.xml` has timestamp
**2026-10-10T07:11:03**; both named saved-thread methods executed once and
passed, each **1/1/0/0/0**. Both selected logcats are beside it. The sole skip
remains `RenameDialogCaptureTest.renameAtFigmaViewport`; the #2036 method passed
in this regular selection. This gate pass does not convert the builder's
in-depth selection into a zero-failure run.

Selected logs retain all eight cumulative
**restore/snapshot/complete-content/committed-draw** tuples, monotonic ms:

| Case | Builder isolated 1 | Builder isolated 2 | Builder in-depth | Dispatcher regular UI |
| --- | --- | --- | --- | --- |
| Ordinary offline first | 20/334/345/613 | 215/238/255/349 | 3/17/50/84 | 7/9/24/146 |
| Ordinary offline reopen | 81/118/157/293 | 146/167/170/290 | 1/9/49/137 | 14/19/100/150 |
| Ordinary held newest first | 26/57/93/183 | 21/28/109/169 | 7/8/84/143 | 10/13/21/66 |
| Ordinary held newest reopen | 39/71/96/200 | 7/30/38/101 | 6/8/52/84 | 13/14/39/86 |
| Fragmented offline first | 312/450/593/682 | 331/437/579/715 | 334/436/588/647 | 332/431/560/624 |
| Fragmented offline reopen | 45/166/294/371 | 36/114/217/318 | 25/121/245/305 | 18/91/243/300 |
| Fragmented held newest first | 262/359/470/546 | 268/344/453/513 | 278/357/462/522 | 286/352/461/550 |
| Fragmented held newest reopen | 45/132/256/319 | 24/94/203/275 | 28/97/267/342 | 16/89/205/251 |
| Delayed negative control | 3017/3140/3154/3231 | 3035/3142/3164/3228 | 3045/3051/3115/3150 | 3017/3026/3038/3092 |

Both separate isolated controls and the full-selection controls passed by
rejecting the identical bound after the injected **3000 ms** restore delay.
The ordinary **20 messages** and fragmented **18000 displayed rows / 36000
durable entries / 18000 spans**, exact saved rows, every marker anchor and
zero offline / one held connected newest-page request per opening remain asserted.
The dispatcher scripted-all gate also exited 0, **22/22/0/0**, per its counted
gate report; it does not run these two methods. No live daemon/Claude scenario
was added or required. The automatic post-merge main sweep remains dispatcher-owned
and pending; it is not a branch acceptance gate. Documentation obtained no device runs.

**Residual isolated failure and combined repair (#2039), 2026-10-10.**
The [verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2041#issuecomment-6097258649)
and [controlled comparisons](../../specs/architecture/2039-saved-thread-residual-first-draw.md#revisions)
establish two contributors after #2027: unrelated device CPU contention and
unnecessary first-composition viewport allocation. Comparable worker CPU with
inflated wall time and zero queue delay does not establish a cache-work regression.
Pre-Probe CPU sampling observed twelve busy windows at **3–45% idle**, followed
by ready windows at **96/96/87/87%**; the timed cache/repository/ViewModel did not
yet exist. Bluetooth OFF/focus snapshots did not diagnose this load, and disabling
Bluetooth recovery still missed both fragmented first opens (**1231/1199 ms**).
No single OS service or GC is established as the cause of every missed millisecond.

`ThreadListViewport.relocationFor` now checks for a previous agent marker before
building placement-key sets or scanning new blocks: without that prerequisite,
its later finished-marker predicate cannot relocate an agent. `onRowsChanged`
prunes measured keys only when measurements exist. An empty map cannot lose a key.
These guards remove roughly **8 MB** of post-snapshot allocation: first-open total
fell from about **200 to 192 MB**, reopen from **88 to 81 MB**, with restore/snapshot
allocation unchanged. Independent 18000-row guarded-list regressions failed on
the old paths and pass with each guard. Existing relocation, reader-anchor and
following tests protect later callbacks with markers/measurements; the visual
and persistence contracts remain unchanged.

Before each original Probe, including the slow control, test-only
`awaitEmulatorCpuIdle` requires four consecutive **250 ms** CPU windows at least
**80% idle**, resetting on renewed busy activity. It reads `/proc/stat`, excluding
already-counted guest ticks, without warming cache/restore/composition. Responsive
continuous load fails the **30-second** readiness timeout rather than weakening
or skipping the **1000 ms** draw assertion. The verifier's nonblocking limitation
remains: synchronous shell acquisition/pipe `readLine()` cannot be interrupted by
that coroutine timeout; responsive-sampler tests do not prove stalled-sampler
cancellation. No stall occurred in the retained runs.

**Retained counterevidence.** The supplied main baseline at
`d3ecf87758e20f97e5226f5c52934c551c14a128` passed three isolated invocations
before `baseline-4/5` failed, each **1 executed / 0 passed / 1 failed / 0 errors /
0 skipped**. Fragmented offline first-open tuples were **719/902/1091/1225** and
**1002/1299/1637/1923 ms**; baseline-5 also drew held newest at **1814 ms**.
Branch misses at **1181/1162/1965 ms** remain alongside those passes.
A cancelled zero-execution invocation is excluded from test proof.

Every comparison remains under
`/Users/juhanailmoniemi/.codex/publish/pyrycode-mobile/builder-2039/evidence/`:
`REPORT.md` records cumulative tuples and commands; per-attempt manifests, fresh
XML, source diffs and full/selected worker/GC/lifecycle logs retain revisions,
exits and counts. `prior/` preserves supplied evidence; the verifier checked all
**546** checksum entries. Raw non-FIFO reproduction, sampled tracing and
phase diagnosis overlapping compilation are diagnostic only. Explicit setup GC
still missed (**1860/1873 ms**); its collection-disabled pass was counterevidence.
Streaming proof passed but removed only **4.8 MB** and increased CPU; streaming
decode removed **1.1 MB** and still missed (**1064 ms**). Both were rejected.
Releasing unused fixture coverage reduced live heap about **8 MB**, but its fixed
sample (`acceptance-b`) passed four/failed one (**1428 ms**); full coverage is
retained in the final repair. No serializer, cache codec, coverage-release,
Bluetooth-shutdown, trace or manual-GC change remains.

Guards alone (`acceptance-a`) passed one/failed four of five isolated methods;
the whole class passed the control/failed first draw (**2185/2097 ms** first opens).
Readiness alone (`acceptance-c`) passed four/failed one: held-connected reopen
was **58/243/378/1001 ms** despite **98/96/92/94%** idle setup. Its restore worker
used **30 wall / 26 CPU / 0 queue ms**; content-to-commit took **623 ms**, overlapping
**107 ms** young GC, insufficient to explain the whole interval. Neither isolated
contributor is a sufficient repair. The combined source below is a new controlled
comparison, not replacement retries of those failed samples.

**Counted final builder sample, separate from regular UI.** `acceptance-d` at
`c72eebcba05ac6cedcb8a65811c59c7e3fda6674` uses the same Pixel 2 API 33 ATD,
FIFO `device_hold`, disabled animations and isolated commands shown above, with
the original full fixtures. Five isolated first-draw invocations each exited 0,
**1/1/0/0/0**; maxima were **654, 703, 659, 617, 642 ms**. The separate isolated
slow control exited 0, **1/1/0/0/0**; the whole class exited 0, **2/2/0/0/0**,
maximum **618 ms**. Counts here are **executed/passed/failed/errors/skipped**.
Fresh XML timestamps for attempts 1–7 are **09:59:01, 09:59:35, 10:00:09,
10:00:46, 10:01:20, 10:01:41, 10:02:16 UTC**, 2026-10-10. Focused controls passed
**56/56/0/0/0**: 18 list-follow, 23 agent-viewport, 11 screen-follow and four
readiness tests (parsing, malformed counters, busy-window reset and timeout).

**Dispatcher regular UI acceptance.** On reviewed head
`5550800e2068f9a86961c1856a81fb7ea1261cf8` (only the plan changed after the
builder sample), `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`
exited 0: **256/256/0/0/1**. Inspected XML under
`/Users/juhanailmoniemi/.codex/publish/pyrycode-mobile/verifier-2039/regular-ui/`
has timestamp **2026-10-10T11:52:31**, 257 reported cases including the sole skip.
Both `savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen`
and `slowRestore_negativeControlRejectsTheSameFirstDrawBound` executed once and
passed, each **1/1/0/0/0**; both selected logs are beside the XML. This completes
the configured regular UI handoff independently of the builder's isolated/in-depth
comparison. Scripted-all separately passed **22/22/0/0/0** and does not select
these methods. No daemon or real-Claude scenario is required.

All tuples below are cumulative **restore/snapshot/complete-content/committed-draw**,
monotonic ms. Each isolated control and class/UI control passed by rejecting the
identical 1000 ms bound with the injected 3000 ms restore delay inside the timer.

| Case | Isolated 1 | Isolated 2 | Isolated 3 | Isolated 4 | Isolated 5 | Whole class | Regular UI |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Ordinary offline first | 160/182/199/289 | 166/177/194/275 | 148/170/187/282 | 227/253/269/368 | 156/177/205/296 | 60/68/86/160 | 22/28/46/84 |
| Ordinary offline reopen | 53/62/78/143 | 58/61/78/147 | 33/48/74/151 | 55/64/80/142 | 53/62/78/137 | 49/58/75/136 | 19/33/43/90 |
| Ordinary held newest first | 52/61/78/141 | 48/62/73/148 | 33/41/58/123 | 49/58/89/143 | 47/56/86/140 | 51/61/77/134 | 10/14/33/72 |
| Ordinary held newest reopen | 42/51/67/126 | 45/53/69/122 | 45/68/84/143 | 46/55/71/120 | 56/60/77/130 | 38/48/65/126 | 19/26/56/93 |
| Fragmented offline first | 372/479/589/654 | 387/519/623/703 | 360/473/588/659 | 334/444/551/617 | 348/454/560/642 | 351/457/555/618 | 360/467/565/616 |
| Fragmented offline reopen | 61/151/261/320 | 45/141/265/328 | 64/151/243/304 | 65/155/248/305 | 70/155/261/323 | 63/149/240/302 | 21/97/188/229 |
| Fragmented held newest first | 309/381/456/522 | 356/450/532/589 | 322/395/488/544 | 284/355/452/508 | 398/470/561/618 | 310/388/491/540 | 271/343/433/487 |
| Fragmented held newest reopen | 41/116/194/255 | 60/163/267/326 | 43/123/213/272 | 58/135/222/280 | 57/146/250/306 | 56/131/211/258 | 38/122/202/237 |
| Delayed control | — | — | — | — | — | 3039/3171/3206/3281 | 3005/3025/3045/3075 |

The separate isolated control (`acceptance-d-6`) was **3045/3180/3195/3255 ms**.
All eight cases retain **20 ordinary messages**, **18000 displayed fragmented rows /
36000 durable entries / 18000 spans**, exact saved rows and every marker anchor,
zero offline/one held-connected newest request per opening and the held response.
First open times fresh cache/repository construction and restoration; reopen keeps
only its mode's repository and creates a fresh ViewModel/composition. Exact newest
text wholly within the viewport through frame commit remains the endpoint.
Documentation inspected retained reports and ran no device tests.

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
