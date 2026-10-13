# Thread screen — saved-thread first-draw testing

Split from [thread-screen-testing.md](thread-screen-testing.md) for #1985 to keep the overview under its size cap.
The [historical comparisons](thread-screen-testing-saved-first-draw-comparisons.md)
retain allocation, restore and projection evidence, including every earlier miss.

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

## Readiness and sampling cancellation (#2143)

Before each Probe, including the slow-restore control, `awaitDeviceReadiness`
requires four consecutive **250 ms** windows at **>=80% idle** within **30000 ms**.
A busy window resets the streak. Readiness exhaustion fails before that opening's
cache/repository/ViewModel construction; it supplies no draw measurement. It never
skips the case, retries acceptance, warms measured work or changes the **1000 ms**
committed-frame bound. Worker CPU time and activity focus snapshots cannot replace
elapsed draw time or identify the cause of a readiness failure.

Responsive busy sampling and held sampling require different evidence. A 30000 ms
coroutine exception identifies the readiness deadline, but cannot distinguish
continued load from stalled shell acquisition or pipe reading. Responsive-sampler
controls and isolated passing draws cannot prove cancellation of blocking sampling.
The [controlled repair and verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2146#issuecomment-6143390224)
establish the synchronous acquisition/reading defect independently of the historical
sweep. The extracted synchronous acquisition control
`heldAcquisitionCannotHoldTheReadinessDeadline` failed its clock-progress assertion;
the real Android `heldCpuPipeCancelsAndClosesItsDescriptor` failed cancellation
before writer release. Each red executed **1**, failed **1**, with **0 errors/skips**;
bounded emergency releases let those comparisons finish.

The test-only repair reuses [PR #2080](https://github.com/pyrycode/pyrycode-mobile/pull/2080)
at `fa1a3bc04dcabd1cd78d44d0d37d7ed50736869c` byte for byte in all three test
sources; [reuse provenance](../../../app/src/androidTest/assets/readiness-2143/reuse.json)
records their hashes. `withEmulatorCpuSample` suspends the caller independently of
Binder acquisition that may ignore interruption. Cancellation closes a late descriptor
without reading it; `use` closes an acquired source on success, malformed input or
reading cancellation. `readEmulatorCpuTicks` reads only the bounded **4096-byte**
first CPU line through a nonblocking Android reader and suspends on no available data.
Readiness diagnostics name the case and acquire/read/window phase, durations and
last twelve samples/windows; failures propagate. Opening/worker/frame logs continue
to distinguish preparation, timed work and cleanup. No production path changed.

### Original timeout and fresh comparisons

The original main sweep at `b107f97a743a508fb9182fdc165b3e084180e250` exited **1**
after **1068 seconds**: **1608 executed, 1607 passed, 1 failed, 0 errors, 2 skipped**.
The original first-draw method failed at the readiness deadline; the projection twin
and slow-restore control passed. Its case and acquisition/read/window detail are
unavailable. This is a readiness failure before a Probe for the affected opening,
not evidence of a one-second draw miss or cleanup failure. The unchanged measurement
path in the intervening merges, passing baseline and controlled sampling failures
leave the **historical cause unresolved**; neither held sampling nor responsive
busy windows is established for that sweep.

Builder comparisons used the original
`UI_GATE_FULL=1 UI_DEVICE_ALL=1 python3 scripts/android-test-gate.py ui` selection
in an isolated task worktree: two managed Pixel 2 API 33 ATD shards, non-e2e
device/shared tests and disabled animations. Counts below are
**executed/passed/failed/errors/skipped**, from inspected retained XML.

| Run | Source revision | Counts |
| --- | --- | --- |
| Unchanged in-depth | `b107f97a743a508fb9182fdc165b3e084180e250` | 1608/1608/0/0/2 |
| Repaired in-depth | `cfa988b2fd4c9f4b082d723ff33ddce25595bd07` | 1609/1609/0/0/2 |
| Fresh focused complete class | same repaired test-source hashes, after final main merge | 4/4/0/0/0 |
| Configured dispatcher UI | `a15cc8ab721c7de303d31b864611dceb3f5ae06c` | 261/261/0/0/1 |

The unchanged baseline executed/passed the original method and both controls once.
The repaired in-depth, fresh focused class and dispatcher UI each executed/passed
all four methods once: the original first-draw method,
`delayedOptionalProjection_cannotHoldFirstNewestDraw_firstOpenAndReopen`,
`slowRestore_negativeControlRejectsTheSameFirstDrawBound` and
`heldCpuPipeCancelsAndClosesItsDescriptor`. Fresh `EmulatorCpuReadinessTest` evidence
is **12/12/0/0/0**, covering held acquisition/reading, late-source closure, repeated
successful samples, malformed/partial/EOF/overlong input, busy reset and exhaustion.
The repaired in-depth logs retain **86 responsive samples**, maximum acquisition/read
**11/33 ms**, including a **77% idle** window resetting readiness. No held sample
or measured-path miss occurred in that comparison.

Committed-draw times in monotonic milliseconds; all eight original cases remain:

| Case | Baseline in-depth | Repaired in-depth | Fresh focused class | Dispatcher UI |
| --- | ---: | ---: | ---: | ---: |
| Ordinary offline first | 141 | 83 | 128 | 99 |
| Ordinary offline reopen | 82 | 107 | 119 | 97 |
| Ordinary held-newest first | 122 | 88 | 161 | 100 |
| Ordinary held-newest reopen | 81 | 89 | 114 | 105 |
| Fragmented offline first | 489 | 499 | 503 | 441 |
| Fragmented offline reopen | 203 | 206 | 259 | 188 |
| Fragmented held-newest first | 514 | 456 | 512 | 481 |
| Fragmented held-newest reopen | 198 | 191 | 274 | 191 |

In that case order, the **1100 ms** projection twin drew at
**90/74/106/74/582/227/513/176 ms** in repaired in-depth,
**238/220/185/189/677/293/544/269 ms** in fresh focused class and
**106/76/90/121/629/230/495/181 ms** in dispatcher UI.
The **3000 ms** restore control rejected the identical **1000 ms** assertion at
**3073/3074/3478/3086 ms** respectively across baseline/repaired/focused/dispatcher.
Exact rows/marker anchors, fixture sizes, zero offline requests, one newest request
per connected opening and its held response remain asserted. Marker checks after
projection never turn a late committed draw into a pass.

The configured dispatcher UI command remained
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`; inspected
XML timestamp **2026-10-13T10:09:44**, device `pixel2Api33Atd_0`, agrees with the
counted gate report. Its sole skip is `RenameDialogCaptureTest.renameAtFigmaViewport`.
Scripted-all separately passed **22/22/0/0/0**, selecting none of these methods.
Configured dispatcher execution is complete; the later post-merge main sweep remains
**dispatcher-owned and pending**, outside branch acceptance. No real-Claude scenario
or ladder change is required.

[Committed evidence summaries and bounded phase logs](../../../app/src/androidTest/assets/readiness-2143/)
retain revisions, configurations, source hashes and all comparisons. Raw XML,
phone logs and manifests survive under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2143/`; reviewed dispatcher XML,
four selected phone logs and checksums are under
`/Users/juhanailmoniemi/.codex/publish/pyrycode-mobile/verifier-2143/evidence/`.
Documentation inspected these existing reports and logs and ran no tests.
