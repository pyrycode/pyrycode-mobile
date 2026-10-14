# Thread screen — saved-thread first-draw comparisons

Historical allocation, restore and projection comparisons split from
[saved-thread first-draw testing](thread-screen-testing-saved-first-draw.md) for
\#2143. The parent owns the measurement contract and current readiness behavior.
Every earlier miss and comparison remains retained below.

## Allocation, restore and projection evidence

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
or skipping the **1000 ms** draw assertion. At #2039, synchronous shell
acquisition/pipe `readLine()` could not be interrupted by that coroutine timeout; responsive-sampler tests did not prove stalled-sampler
cancellation. No stall occurred in those retained runs. The
[cancellation repair and controlled evidence](thread-screen-testing-saved-first-draw.md#readiness-and-sampling-cancellation-2143)
now address that limitation without attributing historical misses to it.

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

**Residual optional-history dependency (#2050), 2026-10-10.** The
[verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2066#issuecomment-6102499328)
and [controlled comparisons](../../specs/architecture/2050-saved-thread-residual-restore.md#revisions)
establish that validated rows waited for optional coverage proofs and repository
saved-order seeding. Matching dark-fixture main `969d9c3c8847` missed fragmented
first opens at **1606/1344 ms**, with workers **884/778 wall, 862/760 CPU ms** and
zero queue delay. Rows-first `8c5ae3703` drew them at **718/510 ms** while optional
history still took **1259/1012 wall, 1115/925 CPU ms**. Removing the dependency
improves first draw without eliminating validation cost.

The quiet baseline missed at **1711/1655 ms** despite readiness and negligible
queue delay. Its offline interval had no app GC; held-connected collections had
microsecond pauses. The rows-first comparison passed despite a **28.7 ms** young-GC
pause. Both retained all eight install/clear pairs without lifecycle failure or
ANR. Host quota contention contributes variance but cannot explain away this
critical-path product cost. Timestamp, vendored-digest and decimal-parser candidates
were rejected; the final repair retains the original serializer/provider and does
not integrate #2042's decoder. See [cache reader ownership](conversation-cache-layout.md#thread-document-readers-1949)
and [live merge ordering](caching-conversation-repository.md#how-the-restore-merges-with-live-rows).

`readableRowsDoNotWaitForOptionalHistoryButLiveMergeDoes` holds metadata separately:
validated rows and atomic suppression must publish, but nonempty live merges must
wait for saved unsigned order. Marker anchors are checked after coverage restoration;
that wait cannot turn a late draw into a pass. Allocate each upstream snapshot's
generation **before** deferred history reads
or ordering dispatch: allocation afterward lets old content outrank a newer coverage
save. Four read/dispatch × quiet-period/cancellation probes failed before that fix
(**4 executed/failed**); two inherited removal regressions also failed (**2 executed/failed**).
All six then passed, retaining exact emission sequence, fresh-file rows, complete
saved position and write history. Affected checks passed **76 executed/passed**;
dispatcher units passed **5219 executed/passed**, both with zero failures/errors/skips.

**Retained misses and counterevidence.** Supplied #2039 main `c88a07d6` drew
fragmented first opens at **1630/1388 ms**; unintegrated #2042 `b15ed69c` at
**2024/1721 ms**, each one executed/failed, zero errors/skips. The earlier #2039
five-isolated and regular UI passes above remain counterevidence. #2050 retains its
baseline, traced and parser-only failures, all comparisons and the first red fixed
sample. That sample passed four/failed one isolated first-draw methods; the ordinary
offline outlier drew at **1271 ms** after rows decoded within 100 ms, with a 34-frame
skip. Its isolated control failed readiness before any Probe; the 3000 ms restore
never began. Its whole class passed both methods. Neither passing diagnostics nor
later green samples establishes an environment defect for these two unclassified
outcomes. No speculative ordinary-path or readiness repair ships.

**Final builder sample, separate from regular gates.** Actual rework source
`ac2337e347026da719810547ace561b654737d29` passed five isolated first-draw methods,
one isolated control and one whole class: **8 executed/passed, 0 failed/errors/skipped**,
all seven exits 0, existing build-hp-1 Pixel 2 API 33 ATD, FIFO coordination and
disabled animations. Forty isolated draws span **164–883 ms**. Controls draw at
**3419/3433 ms**, rejecting the same 1000 ms bound. Whole-class ordinary content
follows the control and is not an isolated cold-process sample.

Evidence root `/Users/juhanailmoniemi/.codex/publish/pyrycode-mobile/builder-2050/evidence/`
retains earlier attempts in `REPORT.md`/`fixed-sample.json`, resumed comparisons in
`RESUME-REPORT.md`/`resume-counts-and-timings.json`, and final proof in
`rework/REWORK-REPORT.md`/`rework-counts-and-timings.json`: commands, exits, fresh
XML/hashes, all case phases and full/selected logcat. `rework/verified-source.json`
corrects the mistyped runner revision label using matching production/test hashes;
original labels remain retained. Subsequent commits change only the plan.

**Dispatcher gates on `78d7a7b465c6`.** Configured UI and scripted-all commands
above exited 0: respectively **258 executed/passed, 0 failed/errors, 1 skipped**
and **22 executed/passed, 0 failed/errors/skipped**. Both named saved-thread methods
executed/passed once each in UI; scripted-all runs neither. UI draw vector is
**99/137/87/77/311/218/226/196 ms**, control **3101 ms**. The sole skip is rename
capture. Fresh UI XML timestamp **2026-10-10T21:38:34**, XML, named logcats and
count summary are retained under `/tmp/verifier-2066/re-review-78d7a7b4/`.
The original eight-case fixture and timed committed-frame contract remain intact.
Documentation obtained no test runs.

**Ready-coverage display projection (#2087), 2026-10-11.** The
[controlled comparisons](../../specs/architecture/2087-saved-thread-recurring-first-draw.md#revisions)
and [verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2093#issuecomment-6111571698)
establish a readiness inversion: a cold open can draw while optional coverage
validates, but already-ready coverage can put display projection on the initial
readable-content path. Baseline fragmented reopens spent **234/217 ms** between
snapshot and content, versus **35/25 ms** on first opens. Holding the projection
worker separately exposes this dependency; holding only metadata or the newest
response misses it. Queued canceled empty projections compounded the controlled
wait. This diagnoses the controlled dependency, without establishing GC, host
contention, lifecycle or a causal commit for the historical full-suite miss.

`ThreadViewModel.threadContent` now skips projection for empty rows and publishes
its first nonempty readable content before optional projection, withholding
markers and read evidence until the unchanged complete projected result arrives.
The first-publication flag belongs to each collection, so reopening and
WhileSubscribed re-collection regain this behavior. Later inputs keep prepared
markers and rows until their complete replacement is ready, preserving latest-input
cancellation and projected row/evidence coupling. Production frame pacing remains
in place. See [independent cache readers](conversation-cache-layout.md#thread-document-readers-1949)
and [saved/live ordering](caching-conversation-repository.md#how-the-restore-merges-with-live-rows).

`SavedThreadOpenTest.readyCoverageCannotHoldFirstSavedRowsBehindProjection_firstOpenAndReopen`
restores complete coverage before rows and holds the injected projection dispatcher.
It failed at the readable-row assertion before the correction (one executed/failed,
also one executed/failed in the bounded assertion rerun). Afterward it checks empty
rows queue no projection, exact markers after release, prepared markers during later
inputs, re-collection without an extra newest request, both connection modes and
repeated new ViewModels. The affected worker/frame/demand/open selection passed
**43 executed/passed, 0 failures/errors/skips**. Existing
`mainProgressInvariant_pendingWorkerAllowsDraftEdit_latestInputWins_andExitCancels`
covers later-input cancellation and exit.

The device twin
`SavedThreadFirstDrawDeviceTest.delayedOptionalProjection_cannotHoldFirstNewestDraw_firstOpenAndReopen`
adds a cancellable **1100 ms** hold only to display projection, using the original
eight-case fixture/open/assertion helper. Both methods retain **20 ordinary messages**,
**18000 displayed fragmented rows / 36000 durable entries / 18000 spans**, exact
rows and every marker anchor, zero offline requests and exactly one newest-page
request per connected opening with its response held. Timing still starts before
cold cache/repository and ViewModel construction and ends at the exact newest text
wholly within the viewport through frame commit. Each draw retains **1000 ms**;
marker checks after projection cannot turn a late draw into a pass. No measured
work is warmed and no retry accepts a miss.

**Retained historical miss and focused counterevidence.** The
[refinement evidence](https://github.com/pyrycode/pyrycode-mobile/issues/2087#issuecomment-6110814251)
retains the configured UI failure at `85a054e743711dd621b206f7369e84d6ba1a1948`:
exit 1, **258 executed, 257 passed, 1 failed, 0 errors, 1 skipped**; first draw
failed and delayed control passed. Draw vector **158/135/130/138/1051/1258/945/684 ms**
uses ordinary offline first/reopen, ordinary held-newest first/reopen, fragmented
offline first/reopen, fragmented held-newest first/reopen order throughout below.
Its phase logcats are unavailable. The isolated merge-base
`5ff194ef5b890d1e5045b754abc35935db25ad75` passed **1/1/0/0/0**
(executed/passed/failed/errors/skipped), fragmented offline **738/561 ms**.
Focused branch `998a55daefd458e8e4ecba36c488af4bf448ccee` and main source archive
`ea3ab23e7e14aac63e59a3d05d28473e3905323f` each exited 0, **1/1/0/0/0**,
with vectors **546/253/202/187/730/571/569/492** and
**570/226/199/186/701/589/584/490 ms**. Their XML, source identities and bounded
logs are recorded under
`/Users/juhanailmoniemi/.codex/publish/pyrycode-mobile/builder-2057-rework-85a054e74371/evidence/`.
These focused passes do not reproduce full-suite state or diagnose that miss.

**Builder-recorded controlled comparison.** The same forced, single-shard,
animations-disabled Pixel 2 API 33 ATD affected-class command is retained in the
[PR testing record](https://github.com/pyrycode/pyrycode-mobile/pull/2093) and plan.
Baseline `d038af89f57ebe4b21816b32fe54aac729296797` exited 0,
**2/2/0/0/0**: original vector **237/219/215/199/713/531/563/450 ms**,
control **3481 ms**. An isolated twin first failed readiness before any Probe or
projection: **1/0/1/0/0**, XML **2026-10-11T16:09:16**. It has no draw measurements
and establishes no latency cause.

Before correction, `e5e9b942dc4e27a1a430d2923cf3770bf219c170` exited 1,
**3/2/1/0/0**: the twin failed with **2468/2440/2413/2412/712/2680/541/3712 ms**;
the original method and restore control passed in that class. Ordinary rows restored
in **43–79 ms**, but content waited until **2286–2315 ms** behind queued projection.
Cold fragmented first opens could draw while coverage remained pending; ready-coverage
reopens could not. After correction,
`ce9da0f3907c3e7452f3ada2bae7f0ca28aaf196` exited 0, **3/3/0/0/0**,
XML **2026-10-11T16:18:43**: original vector
**159/128/141/143/501/235/467/259 ms**, twin with the unchanged hold
**257/224/176/176/726/313/564/269 ms**, restore control **3476 ms** rejecting
the same bound. The reviewed production/tests match this green source.

The builder records retained XML/counts, revisions/source hashes, all eight phase
timings and bounded readiness/worker logs in
`/Users/juhanailmoniemi/.codex/publish/pyrycode-mobile/builder-2087/evidence/`
(temporary equivalent `/tmp/builder-2087/evidence/`): `original-gate/`,
`baseline-class/`, `red-unit-bounded/`, `red-device-readiness/`, `red-device-class/`,
`green-units/`, `green-device-class/`. Both builder locations are absent from the
verifier and documentation environments. Earlier raw reports could not be independently
inspected or checksummed here; these comparisons are attributed to the plan/PR,
with that limitation preserved alongside the fresh full-gate inspection below.

**Fresh configured dispatcher UI acceptance.** On
`3ccd263b2c9b2d32a082765af9f2be32349b6be9`,
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`
exited **0**. Inspected raw XML timestamp **2026-10-11T17:02:18**, matching the
counted report: **260 recorded, 259 executed, 259 passed, 0 failed, 0 errors,
1 skipped**. All three methods named above each executed once and passed,
**1/1/0/0/0**; the sole skip was `RenameDialogCaptureTest.renameAtFigmaViewport`.
This full configured run supplies acceptance independently of focused comparisons.

Cumulative **restore/snapshot/content/committed-draw** monotonic milliseconds:

| Case | Original phases | Delayed-projection twin phases |
| --- | --- | --- |
| Ordinary offline first | 19/24/42/84 | 20/27/43/99 |
| Ordinary offline reopen | 17/23/40/83 | 18/24/41/86 |
| Ordinary held-newest first | 19/26/42/85 | 22/23/40/84 |
| Ordinary held-newest reopen | 21/23/40/87 | 17/19/27/70 |
| Fragmented offline first | 271/369/393/458 | 329/446/472/597 |
| Fragmented offline reopen | 35/105/126/193 | 35/115/127/253 |
| Fragmented held-newest first | 273/353/368/430 | 295/371/388/452 |
| Fragmented held-newest reopen | 42/108/121/193 | 32/102/125/187 |

The delayed restore control passed by rejecting the unchanged bound at
**3008/3032/3038/3070 ms**. Fresh raw/count-derived XML, `counts-and-timings.json`,
named logcats with bounded phase/worker/readiness evidence, source hashes and
`command-result.txt`/`gate-pass.json` are retained under
`/Users/juhanailmoniemi/.codex/publish/pyrycode-mobile/verifier-2093/full-ui/`.
Scripted-all separately exited 0, **22/22/0/0/0**, and selects none of these methods.
No live/Claude scenario is required. Documentation inspected existing evidence
and obtained no test runs.
