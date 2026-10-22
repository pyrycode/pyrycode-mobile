# Thread screen — saved-thread first-draw testing

Split from [thread-screen-testing.md](thread-screen-testing.md) for #1985 to keep the overview under its size cap.
The [historical comparisons](thread-screen-testing-saved-first-draw-comparisons.md)
retain allocation, restore and projection evidence, including every earlier miss.

## Authoritative reader paging (#2334)

The [paging proof and counted evidence](authoritative-reader-paging.md#native-and-deterministic-proof)
covers offline/held-newest demand, explicit retry and cold reopen through the production
negotiated destination and real database. It retains the 1000 ms product target, unchanged
renderer bound, full-file fixture, delayed projection and slow control below. The bounded
repair in #2437 adds production disk/repository/ViewModel reset-after-settled-Retry and exact
reader-replacement cleanup probes. Its focused manual 178 JVM and six native passes are
recorded separately from the parent's failed review and pending independent acceptance in
the companion; they do not establish final #2334 acceptance.

## Bounded database and retained file acceptance (#2333)

The database opening/offline/older-demand requirements remain current. The file-compatibility
measurements below are historical. [Integrated storage acceptance](conversation-cache-testing.md#integrated-storage-retirement-acceptance-2364)
reuses native upgrade evidence; it supplies no replacement first-draw timing probes for #2447.

`SavedThreadFirstDrawDeviceTest.databaseSavedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_coldOpenAndReopen`
adds a real on-disk 18000-display-row authoritative fixture through the actual production
negotiated destination. Both offline and held-newest openings, including cold reopen, create
fresh database/cache/repository/ViewModel instances with no predecoded state. Timing starts
before that construction and ends only at the real committed frame showing exact newest text
wholly inside the message viewport. Readiness, no warm-up, the **1000 ms product target** and
renderer-specific `firstDrawBoundMs` policy below remain unchanged. The listener requests a
subsequent frame after callback registration during `OnDraw`; only its actual commit callback
can end timing. Assert the production `authoritativeThread` destination and exact saved raw facts,
including certification; local page exhaustion never supplies daemon completeness or a cursor.

The [final verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2341#issuecomment-6312130382)
accepts Mobile **`a5693dd3c4b26d37e4d1fad478251b30c69e826c`**, merged main
`cba8512ee195dc2bef3d6908a1e30997539fe3cf`, on Linux/build-hp-1. Dispatcher revision was
`1f8d5e30508561adb2fb7eb5c20e2dfc42fb67f9`; managed-image identity was
`cec1892f04abcb5b7c13b3954e66527b3567c105c6ee2d60b7e3d797ae0d0135`.
ANGLE/SwiftShader selected the unchanged **2000 ms** bound. Every positive opening below also
met the product target. These are complete configured-gate measurements, separate from the
builder's focused database-method pass (681/254/284/236 ms committed frames).

| Configured command | Report invocation | Executed | Passed | Failed/errors | Skipped |
| --- | --- | ---: | ---: | ---: | ---: |
| `python3 scripts/jvm-test-gate.py --with-build-checks` | `jvm-ss8vssgo` | 6417 | 6417 | 0 | 0 |
| `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui` | `ui-2kop75ow` | 336 | 336 | 0 | 1 |
| `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py scripted-all` | `scripted-all-xbk9otlv` | 21 | 21 | 0 | 1 |

The UI skip was `RenameDialogCaptureTest.renameAtFigmaViewport`; scripted-all skipped
`interactiveTurn_seededChannel_durableGapCatchUp`. Each required method below executed **once**
and passed in that full UI invocation: **1 executed / 1 passed / 0 failed/errors/skipped** each.

- `databaseSavedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_coldOpenAndReopen`
- `savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen`
- `delayedOptionalProjection_cannotHoldFirstNewestDraw_firstOpenAndReopen`
- `slowRestore_negativeControlRejectsTheSameFirstDrawBound`

The class total was **5/5/0/0/0** in executed/passed/failed/errors/skipped order, including
CPU-pipe cleanup. `RecordedMobileSuiteTest.interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`
also executed once and passed without failures/errors/skips. Scripted-all used zero real Claude
turns with daemon source/binary revision `2c68b595440dd7e8e843af9cff0d9a708bcc81a7`.
No new real-Claude capture was required; route activation evidence remains with #2245/#2246.
Other operating systems are outside this Linux ticket's verification scope.

Every tuple below is cumulative **restore / snapshot / complete content / committed frame**
in monotonic milliseconds, not independent phase durations. The file rows below are historical
measurements of the then-supported **36000-entry / 18000-span / 18000-display-row** fragmented
fixture and 20-row ordinary fixture. They are not compatibility requirements for retired storage.
The historical optional-projection twin held for the applied bound plus 100 ms (**2100 ms** here).

| Fixture | Offline first | Offline reopen | Held-newest first | Held-newest reopen |
| --- | --- | --- | --- | --- |
| Database authoritative | 67/95/118/149 | 80/111/127/169 | 53/73/77/111 | 82/112/122/152 |
| Ordinary file | 39/45/49/81 | 49/55/59/94 | 14/19/37/64 | 14/19/37/65 |
| Fragmented file | 283/284/304/365 | 31/45/50/115 | 280/280/294/369 | 60/75/99/161 |
| Delayed projection, ordinary file | 16/22/50/89 | 35/41/57/94 | 16/22/38/65 | 14/19/36/75 |
| Delayed projection, fragmented file | 352/353/379/455 | 34/48/64/107 | 295/296/315/384 | 38/39/64/107 |

The slow control recorded **3009/3039/3057/3082 ms** and passed by rejecting the identical
renderer bound. Each database opening recorded **200 SQL item results / 200 payload decodes /
5 queries / 1 evidence result / 1 evidence decode / 0 ordinal results**, with no full-thread
read. These numeric bounds cover the plain native fixture; renderer parents, active extras and
witnesses are separately counted in the deterministic fixtures. Query-plan assertions also
check indexed range seeks, since a bounded result count alone cannot exclude a full index scan.

Latency acceptance does not prove placement, validation or lifecycle correctness. In the full
JVM invocation, `AuthoritativeWindowRestoreTest` executed/passed **25**, with zero failures,
errors or skips; its fresh XML includes every named opening, demand, closure, identity,
generation, exit, retirement, certification, migration, placement and initial-validation probe.
`SavedAuthoritativePlacementTest` passed **5**, `AuthoritativeRoomStorageTest` **14**,
`AuthoritativeOwnedUpsertTest` **17**, `AuthoritativeOwnedRemovalTest` **18**,
`AuthoritativeThreadCacheTest` **23**, `AuthoritativeThreadStoreTest` **15**,
`AuthoritativeThreadProjectionTest` **28**, `MigratingConversationCacheTest` **17** and
`AuthoritativeReadSightTest` **11**, all without failures/errors/skips. Deep-parent/cycle and
shared-rank allocation/storage probes preserve the
[bounded placement contract](conversation-cache-contract.md#placement-metadata-and-deep-parent-availability).
The unordered-family production reproducer records **202 payload results/decodes, 7 queries,
1 evidence result and zero full reads**, including its two required extras.

The raw XML (`dispatcher.xml` and per-class/per-test reports), invocation/source identities,
managed-image metadata, phone logs with renderer/readiness/containment, every timing and raw
bounded-read records were retained at
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2333/verifier-a5693dd-20261021/`, under the
three invocation directories above, with SHA256 manifest. These are retained locations at the
time, subject to pruning. Documentation read the issue/PR evidence, accepted handoff and current
JVM XML from bundle `b108d3822f702c10bbcc4ca49a934ecab8872644b895ed04ea8bf8fc67828dfd`.
Its UI/scripted XML and phone logs are hash-addressed pointers on build-hp-1, omitted from the
downloaded bundle's size budget; the counts and phases above rely on the verifier's recorded
inspection of those reports, not a new local raw-log recount. Documentation ran no acceptance
tests. The prompt's gate pass header and standalone pass JSON name older `76322d3406e4`;
current acceptance uses the final reviewed head and invocation identities above.

Historical failures stay failed: the initial production-route expansion restored all 18000
facts; its retained run had **10 executed / 5 passed / 5 failed**. A later renderer/index run
had **18 executed / 2 passed / 16 failed**, followed by **18/16/2** before demand fixture repair.
The missing commit callback diagnostic is not a draw pass. The safe ancestry-storage regression
retains **4 executed / 3 passed / 1 failed / 0 errors/skips**, maximum key length **15380**, at
`builder-rework6-20261021-placement-storage/red/`. Earlier `fcc9cce870a9` acceptance retains
**6405 executed / 6404 passed / 1 failed**, with a separate **1 executed/passed** accepted flaky
retry, UI **336 passed / 1 skipped** and scripted **21 passed / 1 skipped**, at
`verifier-fcc9cce-20261021/`. None supplies a pass for the final placement repair. The clean
current run neither attributes nor erases those earlier failures or this document's older
file-latency misses.

## Saved-thread first draw (#1949)

The supported timed path is the on-disk authoritative database opening described above.
\#2387 removes legacy row/history storage APIs and codecs; do not seed discarded `threads/`
files or use old row/coverage decoders to establish visible content. Supported full-file cache
fixtures use `SavedAuthoritativeThread` and authoritative projection. Unknown-owner obsolete
files remain inert, including across reopen. See the [supported cache contract](conversation-cache-contract.md#the-contract),
[#2385 import evidence](conversation-cache-testing.md#authoritative-import-and-inert-legacy-files-2385)
and [#2386 schema evidence](conversation-cache-testing.md#legacy-discard-upgrades-and-reopen-proof-2386).

\#2447 owns restoration of the removed saved-first-draw fixtures and their optional-projection/
slow-restore controls. Preserve cold opening and reopening, offline and held-newest modes,
large displayed history, exact newest text and placement, reader-triggered older paging and
zero-offline/one-connected request assertions through supported facts. Existing historical
file results below do not prove those replacement fixtures executed. #2364 accepts storage
retirement only; #2447 remains under `needs-human:manual-takeover` for #2334/#2437.
The timing and negative-control requirements remain as follows.

**Software-rendering bound (#2222).** 1,000 ms stays the product target. When the
guest GL renderer string contains SwiftShader, the bound is **2,000 ms**. The SwANGLE
emulator launcher on `build-hp-1` and `build-fractal-2` reports
`ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device ...))`. Under it the worst
18,000-row first opens were **555 ms** on HP and **1,314 ms** on Fractal, which missed 3 of
16; Fractal's native renderer worst was **816 ms**. The test reads the renderer once
through a 1x1 EGL pbuffer before any timing, logs `event=first_draw_bound` with
`bound_ms` and `renderer`, and names both in every bound failure message. Nothing is
warmed or moved out of the timer. The optional-projection twin holds projection for
the applied bound plus **100 ms**, so it still fails a draw that waits for projection.

`SystemClock.elapsedRealtimeNanos` starts before cache/repository and ViewModel
construction. Cache reading, collection, projection and drawing are inside the interval;
fixture generation and APK/activity startup are outside it. Cumulative probes record
row restore, repository snapshot, complete screen content and the committed newest-row
frame. A root `OnDrawListener` checks exact newest text, placed semantics and nonzero
bounds wholly inside the message viewport, then registers a frame-commit callback.
Only that callback records draw time. A parent draw modifier missed child render-layer
updates; first StateFlow emission, Compose virtual time, loading semantics and eventual
idleness cannot establish the draw deadline. Polling only waits for recorded evidence.

The slow-restore negative control must insert **3,000 ms** into cache reading in the same
timed opening and catch the identical bound assertion's failure. That delay exceeds both the
1,000 ms and software 2,000 ms bounds. Historically,
`slowRestore_negativeControlRejectsTheSameFirstDrawBound` drew at **3,188 ms** in the #2018
full UI run, with restore/snapshot/content **3,040/3,048/3,102 ms**. A passing negative control
means the latency assertion rejected the slow path. The optional-projection control must hold
work beyond the applied bound without delaying the first authoritative newest frame.

Opening and reconnect must not create older-reader demand. Keep bounded authoritative
window/read counters, exact saved facts and placement, and reader-only paging/cancellation
assertions from [bounded opening](caching-conversation-repository.md#bounded-authoritative-opening-2333)
and [older demand](caching-conversation-repository.md#older-saved-history-demand).
A sparse fixture and a 15-second eventual-completion allowance cannot establish latency for
large displayed history; retain phase measurements and the real committed-frame deadline.

The remaining sections preserve accepted and failed observations from earlier implementations,
including retired test names and decoder measurements. They are historical evidence, not
instructions to restore legacy-file compatibility.

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

### Initial snapshot reconciliation and the later draw miss (#2153)

The [reported main sweep](https://github.com/pyrycode/pyrycode-mobile/issues/2153#issuecomment-6162189052)
at `1fb92599b2df99b0380f48c1be8156bf0ffdfc75` exited **1** after **1074 seconds**:
**1609 executed, 1608 passed, 1 failed**. The delayed-projection method completed
all eight openings, drawing at **140/180/167/175/1048/475/998/320 ms** in ordinary
offline first/reopen, ordinary connected first/reopen, fragmented offline
first/reopen and fragmented connected first/reopen order. Fragmented offline first
open missed the **1000 ms** committed-frame bound; connected first open was **998 ms**.
This is distinct from the earlier readiness timeout. Original raw phase logs are
unavailable: the **historical production-versus-environment cause remains unresolved**.
An activity-destroyed focus snapshot after execution and later passing comparisons
cannot establish that cause. No known-good post-merge main sweep is claimed.

Fresh unchanged phase evidence independently demonstrates a material preparation
cost: the fragmented projection twin spent **69–157 ms** between restored rows and
repository snapshot. Its first offline worker used **161 wall / 103 CPU / 0 queue ms**;
connected reopen used **155/59/0 ms**. The two targeted `CachedThreadWorkerTest`
probes executed **2**, passed **0**, failed **2**, with **0 errors/skips** against
unchanged production: the guarded 100000-row cache recorded **200000 accesses before
delivery**, and recollection returned a reconstructed list. Passing isolated draws
alone would not expose that redundant traversal.

The [repair and verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2155#issuecomment-6163707449)
remove reconstruction only for each collection's first empty, unsuppressed delegate
snapshot. `CachingConversationRepository.observeThreadSnapshot` publishes the
already validated cache list through the existing processing dispatcher, preserving
exact row objects/keys, order and receipt metadata, generation allocation and deferred
writer traversal. A repository-wide or conversation-wide flag would break repeated
collections. Initial nonempty/suppressed snapshots and every subsequent snapshot
retain reconciliation, including disconnect/reconnect; cancellation still prevents
held preparation from publishing after exit. See the
[restore contract](caching-conversation-repository.md).

The repaired twin's fragmented row-to-snapshot intervals are **1–16 ms**; direct initial
publication takes **0 ms** at worker-log resolution. Both in-depth twins retain
**40 readiness samples**, maximum acquire/read **7/11 ms**, with idle windows
**92–100%** before and **86–100%** after. No readiness or timing-harness defect is
demonstrated. These measurements establish the removed cost, without attributing the
historical 1048 ms sample to it.

Both builder comparisons used the original
`UI_GATE_FULL=1 UI_DEVICE_ALL=1 python3 scripts/android-test-gate.py ui` selection:
two Pixel 2 API 33 ATD shards, non-e2e device/shared tests and disabled animations;
the unchanged revision ran in an isolated worktree. Inspected retained XML counts
below are **executed/passed/failed/errors/skipped**.

| Run | Source revision | Counts |
| --- | --- | --- |
| Unchanged in-depth | `1fb92599b2df99b0380f48c1be8156bf0ffdfc75` | 1609/1609/0/0/2 |
| Repaired in-depth | `6eb138928073c30ef41c5a064854bef7776b5dd5` | 1609/1609/0/0/2 |
| Focused complete device class | candidate source hashes in `verified-source.json` | 4/4/0/0/0 |
| Configured dispatcher UI | `ec8cd113aecd839fac925b92a152c681e31c64ee` | 261/261/0/0/1 |

Each run executed and passed all four methods once: the original first-draw method,
`delayedOptionalProjection_cannotHoldFirstNewestDraw_firstOpenAndReopen`,
`slowRestore_negativeControlRejectsTheSameFirstDrawBound` and
`heldCpuPipeCancelsAndClosesItsDescriptor`. Fresh XML timestamps are baseline shards
**2026-10-14T06:35:21 / 06:36:00**, repaired shards **06:57:59 / 06:58:38**,
focused class **06:39:06** and dispatcher UI **07:49:10**.

All eight cases for both draw methods are retained below. Tuples are cumulative
**restore/snapshot/complete-content/committed-draw** monotonic milliseconds;
“twin” is the delayed-projection method.

| Case | Unchanged original | Unchanged twin | Repaired original | Repaired twin |
| --- | --- | --- | --- | --- |
| Ordinary offline first | 6/31/47/105 | 22/29/45/85 | 19/24/29/82 | 34/43/57/121 |
| Ordinary offline reopen | 21/38/57/126 | 2/29/44/104 | 16/21/26/75 | 20/26/43/108 |
| Ordinary held newest first | 17/23/40/99 | 23/30/46/105 | 37/40/57/97 | 7/34/50/116 |
| Ordinary held newest reopen | 28/33/38/87 | 22/28/44/92 | 18/24/41/87 | 15/21/26/74 |
| Fragmented offline first | 310/402/414/494 | 369/522/538/641 | 311/312/324/406 | 419/420/444/538 |
| Fragmented offline reopen | 61/107/129/191 | 51/126/145/222 | 39/63/80/171 | 44/60/73/154 |
| Fragmented held newest first | 358/512/523/586 | 316/385/409/487 | 305/306/320/403 | 294/295/309/399 |
| Fragmented held newest reopen | 43/112/133/208 | 44/201/228/318 | 58/73/86/155 | 46/56/86/158 |

Focused original/twin maxima were **430/578 ms**. Dispatcher UI original draws were
**104/93/100/95/437/166/402/137 ms**, twin draws **89/88/90/81/469/120/421/144 ms**
in the same case order. The **3000 ms** slow-restore control rejected the identical
**1000 ms** assertion at **3063/3080/3480/3116 ms** in unchanged/repaired/focused/
dispatcher runs. Passing that control proves rejection of the slow draw.

The device-test source is unchanged: the cancellable **1100 ms** optional-projection
hold, eight cases, fixture sizes, real persisted bytes, cold timed construction,
fresh ViewModel/composition on reopen, exact rows/marker anchors, zero offline
requests and one held newest request per connected opening all remain asserted.
Validation is not deferred and measured work is not warmed or moved outside the timer.
Affected nine-class units passed **148/148/0/0/0**; final worker controls passed
**10/10/0/0/0**, including empty/nonempty cancellation and recollection order metadata.
The intermediate candidate's **148/145/3/0/0** logger-stub fixture failures remain
retained; the JVM fixture now installs/restores the existing logger sink.

The configured regular dispatcher UI gate completed with
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`; its only
skip was `RenameDialogCaptureTest.renameAtFigmaViewport`. Scripted-all separately
passed **22/22/0/0/0**, selecting none of the four saved-thread methods. The subsequent
post-merge main sweep remains **dispatcher-owned and pending**, outside branch
acceptance. No new real-Claude scenario or ladder change is required.

[Checked evidence summaries](../../../app/src/androidTest/assets/saved-first-draw-2153/)
retain the original miss, failed unit comparisons, configurations, source hashes and
all phase measurements. Raw XML, readiness/worker/phone logs and manifests survive
under `/work/Projects/pyrycode-mobile-agents/logs/evidence/2153/`; reviewed dispatcher
XML and four phone logs are under
`/Users/juhanailmoniemi/.codex/publish/pyrycode-mobile/verifier-2153/evidence/`.
Documentation inspected retained reports and ran no comparisons.

## Readiness and sampling cancellation (#2143)

Before each Probe, including the slow-restore control, `awaitDeviceReadiness`
requires four consecutive **250 ms** windows at **>=80% idle** within **30000 ms**.
A busy window resets the streak. Readiness exhaustion fails before that opening's
cache/repository/ViewModel construction; it supplies no draw measurement. It never
skips the case, retries acceptance, warms measured work or changes the
renderer-selected committed-frame bound. Worker CPU time and activity focus snapshots cannot replace
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

### Responsive load and focused comparisons (#2177)

The [readiness investigation](https://github.com/pyrycode/pyrycode-mobile/pull/2203)
identified responsive readiness exhaustion under external compiler/GC pressure.
Runner repair [#2178 / agents PR #186](https://github.com/pyrycode/pyrycode-mobile-agents/pull/186)
keeps Kotlin compilation in the owned Gradle lifetime. Subsequent focused class
comparisons on Linux/build-fractal-2, Mobile
`4b21cef23ef9d395f667694ab2a6df97c380deaf` and
`e8a4508f23f1b99e0c22494656fad1fe4a9ecf7d`, each recorded
**4 executed / 4 passed / 0 failed / 0 errors / 0 skipped** with agents
`800e719515a91bf2040d9d26b0e0a802e931bca3`. The unchanged baseline's fragmented
offline/held-newest first draws were **676/609 ms** normally and **841/678 ms**
with projection held; slow controls rejected the bound at **3800/3768 ms** in
baseline/current. These establish valid measurements under those conditions,
without attributing or repairing the historical committed-frame misses below.

Later focused evidence at Mobile `5e3e4471733f63e97ccb6c4fc5f054d8107f9445`, agents
`49cd6dd07c163a7d0e9583850aafb28378ec0b3c`, remains **4/3/1/0/0** in
executed/passed/failed/errors/skipped order. Both draw methods passed all eight
openings and held-pipe cancellation passed, but the slow control exhausted readiness
after **109 responsive windows at 0–49% idle**, before installing its Probe.
A separate diagnostic retained **1/0/1/0/0**, with **110 windows at 0–49% idle**
and no Probe. Neither failure supplies a slow-draw or bound-rejection measurement.
Fresh guest counter deltas and a one-second process interval showed substantial
Google Play Services/system_server work; the initiating operation and attribution
across every window remain unproved. Lifetime process CPU averages and boot-restored
`dumpsys cpuinfo` snapshots cannot identify current load.

Those startup-load failures remain owned by
[#2209](https://github.com/pyrycode/pyrycode-mobile/issues/2209).
The later complete #2202 selection reaches these methods under different conditions;
its passes do not clear that report or replace #2177's own acceptance handoff.
The checked #2177 `comparison.json` and `resume-validation-20261017.json` are also
retained with #2202's reviewed evidence. Original raw comparisons were at
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2177/`, in
`post-runner-baseline-20261016/`, `post-runner-current-20261016/`,
`resume-first-draw-20261017-0014/` and `resume-negative-diagnostic-20261017/`.

**Final acceptance, after #2209 and the #2228 software-rendering bound.** At Mobile
`24a015e8b5f580a6933b45542ed01386c0b704f3` (main `46909f852` merged), agents
`c8edc37edbcc111bf769b93840beea0fe5e8c02f` reusing managed generation `151a9dbd…`
on build-fractal-2 with the SwiftShader guest renderer (`event=first_draw_bound
bound_ms=2000`), the focused class executed **4/4/0/0/0**, exit 0, after a 990 s
admission wait. All 17 readiness barriers qualified over 77 windows, with 17
installed Probes and maximum acquisition/read **9/32 ms**. Fragmented
offline/held-newest first draws were **774/650 ms** with projection held and
**591/664 ms** originally — inside both the 2000 ms software bound and the
unchanged 1000 ms product target, so the software bound decided no outcome here.
The slow-restore control rejected at **3727 ms**. The earlier bound-1000
build-fractal-2 run stays recorded as failed (**4/2/2/0/0**, see #2218's table
below); no Mobile measurement logic changed between the two runs.

The configured dispatcher gates then completed for #2177:
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui` passed
**335/335/0/0/1** (sole skip `RenameDialogCaptureTest.renameAtFigmaViewport`),
executing and passing both `delayedOptionalProjection_cannotHoldFirstNewestDraw_firstOpenAndReopen`
and `savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen`,
plus both `slowRestore_negativeControlRejectsTheSameFirstDrawBound` and
`heldCpuPipeCancelsAndClosesItsDescriptor` controls; scripted-all passed
**22/22/0/0/0**, including `interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread`.

This closes #2177's own acceptance handoff. The diagnosed cause was never a Mobile
measurement defect: it moved through external host/runner compiler-and-GC
contention (repaired by #2178), a managed-image lifecycle gap that let guest
background load into the readiness window (repaired by #2209), and finally
measured draws exceeding the then-**1000 ms** bound under software rendering,
repaired by #2228's SwiftShader-conditional **2000 ms** bound (detailed under
"Cold-decode localization and the software-rendering bound (#2218)" below). No
`SavedThreadFirstDrawDeviceTest` or `EmulatorCpuReadiness.kt` assertion, fixture,
warmup, retry or skip changed to reach this pass. The historical #2163
**1434/1436 ms** misses keep #2202's disposition below: retained as a failure,
cause unresolved, not reproduced under these repaired conditions.

Checked counts and all 17 opening records for this final run are in
`post-2218-{validation,openings}-20261017.*` under
`../../../app/src/androidTest/assets/readiness-2177/`; raw XML, phone logs, gate
phases, image identity and a verified manifest are under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2177/post-2218-class-20261017/`.
Documentation inspected these retained reports and the gate report's counted
results; it ran no comparisons itself.

### Historical fragmented latency disposition (#2202)

The [historical #2163 verifier failure](https://github.com/pyrycode/pyrycode-mobile/pull/2168#issuecomment-6201154344)
at Mobile `23176249515918b2a776c4b3429d8ce112d363e0`, build-fractal-2, retains
**261 executed / 260 passed / 1 failed / 0 errors / 1 skipped**. The delayed-projection
method drew at **260/357/258/207/1434/478/1436/506 ms** in ordinary offline
first/reopen, ordinary held-newest first/reopen, fragmented offline first/reopen,
fragmented held-newest first/reopen order. Its **1434/1436 ms** fragmented first
opens remain failures against **1000 ms**. The new Room backend was unbound.

The retrieved bundle retains counted XML, verdict and final draw values, but lacks
contemporaneous host/device CPU/load, raw readiness/worker logs, detailed cumulative
restore/snapshot/content/frame records, detailed host/device configuration and the
exact measurement runner revision. Its `pointers.json` is empty. A dispatcher
revision alone does not identify that runner. These unavailable measurements prevent
historical attribution; runner remediation and subsequent passes cannot supply them.

The [bounded investigation and verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2211#issuecomment-6229082234)
record two separate complete regular UI runs on Linux/build-hp-1, one managed
Pixel 2 API 33 Google ATD x86_64 shard with animations disabled. Counts are
**executed/passed/failed/errors/skipped**, from inspected fresh XML:

| Run | Mobile revision | XML timestamp (2026-10-17) | Counts |
| --- | --- | --- | --- |
| Unchanged-production builder baseline | `33b1353bf5d8929f81690acf376ba47e51b82043` | 01:37:48 | 334/334/0/0/1 |
| Configured dispatcher UI | `80aa4bcfbbc8d0625c3780891873ebe771ae88d6` | 03:26:35 | 334/334/0/0/1 |

Both used agents `49cd6dd07c163a7d0e9583850aafb28378ec0b3c` and
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`;
the builder additionally set `UI_GATE_FULL=1`. That flag was absent from the
configured dispatcher command, but counted XML proves the intended complete
selection executed. Neither used the in-depth sweep. Each run executed/passed
all four `SavedThreadFirstDrawDeviceTest` methods once, **1/1/0/0/0** each:
`savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen`,
`delayedOptionalProjection_cannotHoldFirstNewestDraw_firstOpenAndReopen`,
`slowRestore_negativeControlRejectsTheSameFirstDrawBound` and
`heldCpuPipeCancelsAndClosesItsDescriptor`. The sole whole-gate skip was the existing
unrelated `RenameDialogCaptureTest.renameAtFigmaViewport`.

All sixteen positive openings per run follow. Tuples are cumulative
**restore/snapshot/complete-content/committed-newest-frame** monotonic milliseconds,
not independent task durations; “twin” holds optional projection for **1100 ms**.

| Fixture / mode / opening | Baseline normal | Baseline twin | Dispatcher normal | Dispatcher twin |
| --- | --- | --- | --- | --- |
| Ordinary / offline / first | 40/46/50/98 | 25/30/35/82 | 21/28/44/84 | 36/38/55/93 |
| Ordinary / offline / reopen | 15/20/38/81 | 16/20/39/83 | 17/19/25/71 | 34/41/57/102 |
| Ordinary / held newest / first | 30/36/53/92 | 14/19/24/72 | 18/24/41/90 | 34/40/56/104 |
| Ordinary / held newest / reopen | 37/39/56/98 | 16/23/27/75 | 19/24/30/80 | 36/41/58/100 |
| Fragmented / offline / first | 329/329/351/431 | 326/327/336/424 | 304/304/309/391 | 310/310/332/420 |
| Fragmented / offline / reopen | 44/45/71/138 | 46/46/60/123 | 57/60/82/140 | 49/62/89/176 |
| Fragmented / held newest / first | 312/313/331/404 | 341/342/353/433 | 299/299/312/390 | 331/332/340/410 |
| Fragmented / held newest / reopen | 42/43/69/129 | 42/43/63/125 | 60/66/86/163 | 31/44/49/105 |

Positive draws were **72–433 ms** in the baseline and **71–420 ms** in dispatcher
verification, leaving at least **567/580 ms** headroom respectively. Baseline
cumulative restore/snapshot/content ranges were **14–341 / 19–342 / 24–353 ms**.
The **3000 ms** slow control passed by rejecting the identical **1000 ms** bound:
baseline phases **3015/3052/3080/3123 ms**, dispatcher **3019/3047/3066/3092 ms**.
Held-pipe cancellation and descriptor closure passed in both runs.

Each run passed all **17 readiness barriers**, each after exactly four windows:
baseline **68 windows at 82–100% idle**, dispatcher **68 at 88–100%**. Baseline
acquisition/read ranges were **0–6/10–33 ms**. Cold timed construction, persisted
ordinary/fragmented fixtures, fresh ViewModel/composition on reopen, exact newest
committed frame, rows/markers and zero-offline/one-connected request assertions
remain intact. No Mobile production/test logic, warmup, acceptance retry, skip or
threshold change was introduced.

All eight measurement-source hashes match the historical revision and inspected
\#2177 comparisons. The wider trees and conditions differ: #2177 selected a focused
class on build-fractal-2 with the earlier runner, while these complete runs include
**73 additional recorded-client cases** versus #2163. The current runner uses its
owned Gradle JVM launcher; read-confirmation, DI/push and recorded-test changes also
separate the wider trees. Source equality does not make execution conditions equal.

The builder retained **355 host / 289 guest CPU samples** and **158 worker events**.
Five-second read-only observation adds overhead; device-phase host idle ranged
**54.54–98.98%**, while guest intervals across boot and all methods ranged
**3.30–99.60% idle / 0–4.80% steal**. Those coarse intervals cannot attribute an
individual subsecond draw. Dispatcher logs retain readiness and worker CPU;
external host/device CPU observation belongs to the separate builder baseline.
Neither readiness nor observed headroom establishes general reliability.

The disposition is **“historical failure retained; cause unresolved; not reproduced
under the recorded repaired-runner conditions.”** No Mobile repair is claimed.
Separate configured dispatcher JVM and scripted-all results were
**5733/5733/0/0/0** and **22/22/0/0/0**; scripted-all selects none of these four
methods. No in-depth main sweep or real-Claude turn was required or run. Other
operating systems are outside this Linux ticket's verification scope.

[Checked baseline summaries and every opening](../../../app/src/androidTest/assets/latency-2202/)
retain identities/configuration, eight source hashes, phase/readiness records and
evidence manifests. Raw files were retained under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2202/`: `baseline-20261017/`
holds XML, 335 phone logs, CPU samples and configuration; `historical-2163/` holds
the retrieved failed bundle; `audit-baseline-20261017/` holds the checked audit;
`verifier-80aa4bc-20261017/` holds fresh dispatcher XML, phone/worker/readiness logs,
checked phase/source summary and verified manifest. The baseline 348-file manifest
SHA-256 is `314c33855de229498ec0e1cab06f0e2af16a76477bb5ac22fce56dbc0d105c55`.
These raw locations are subject to retention; the counted results and phases above
preserve the disposition. Documentation inspected existing evidence and generated
no acceptance results. Closed #2202's historical **1434/1436 ms** disposition stays
as recorded above; #2218 below is a separate, later investigation and does not
reopen or supersede it.

### Cold-decode localization and the software-rendering bound (#2218)

After the readiness repair, #2177 reached valid readiness for every opening on
Linux/build-fractal-2 but failed both positive methods, **4 executed / 2 passed /
2 failed**: fragmented first opens drew at **1314/1032 ms** (delayed-projection,
offline/held-newest) and **1026 ms** (original, offline). #2218 localized that cost
without changing any Mobile production or test source.

**Localization.** In the failing fractal-2 first opens, the cold row decode in
`FileConversationCache.readDecodedThread` took **624–789 ms** of **710–935 ms**
restore; reading the bytes took **5–14 ms** and validation **33–64 ms**. The same
bytes decoded in **395 ms** in the passing held-newest first open of the same
process — roughly a twofold variance on identical work. Content-to-committed-frame
took **210–329 ms** in the failing first opens, and even the 20-row ordinary
fixture took **138–232 ms**, against roughly **40–60 ms** in the #2202 build-hp-1
runs — the frame path itself ran slow on that host, independent of row count. The
lazy history decode (metadata **1069–1465 ms**, proofs **626–929 ms**) starts when
`ThreadViewModel.historySeed` releases the cache mutex and so overlaps the first
draw; each failing first open had two app garbage collections overlapping
content-to-frame (one or two overlapping restore), where the passing first open had
none. Reopens with no history decode still took **144–258 ms** content-to-frame, so
the GC overlap is a measured contributor, not a demonstrated sole cause.

**Same-host, same-runner launcher comparison**, build-fractal-2, agents
`9aad4b5d…`, all eight measurement-source hashes matching:

| Run | Generation / launcher | Counts | Fragmented first draws (ms) |
| --- | --- | --- | --- |
| Owner `fractal-prepared` (passing) | `07867931…` / `emulator`, `auto-no-window`, lavapipe | 4/4/0/0/0 | 653, 582, 816, 707 |
| #2177 failing | `151a9dbd…` / `emulator.hp-native`, `-gpu swangle`, swiftshader | 4/2/2/0/0 | 1026, 619, 1314, 1032 |

Only the launcher-identity input differs between those two generations; the
failing run also recorded guest steal up to **16.17%**, against **0–3.88%** in the
earlier fractal reference run. This localizes the regression to fractal-2's
execution environment at the time (SwiftShader host-side rasterization competing
for virtual CPUs), but two runs are not a repeated A/B, so the renderer was not
proven causal by this comparison alone.

**Operator resolution (#2222) and the software-rendering bound (#2228).** The
operator closed blocker #2222 directly: the SwANGLE launcher is intended on both
`build-hp-1` and `build-fractal-2`, so no native-launcher A/B runs, and the
operator attributes the remaining difference to Fractal's slower processor under
software rendering. PR #2228 (merged outside the pipeline) added the **2000 ms**
software-rendering bound described above — see "Software-rendering bound (#2222)"
at the top of this document for the mechanism. That bound is the repair; no
Mobile restore or decode change followed, because no measurement showed a Mobile
defect, and the candidates considered (deferring the history seed, rewriting
decode) remain unimplemented.

**Four counted runs**, Linux, cumulative fragmented first-open phases
(restore/snapshot/content/committed-frame not repeated here — see counts and
headline draws):

| Run | Host / generation | Bound | Counts | Fragmented first draws (offline/held-newest) |
| --- | --- | --- | --- | --- |
| #2177 failing (above) | fractal-2, `151a9dbd…` | 1000 ms | 4/2/2/0/0 | 1026/1314 (original/twin offline), 619/1032 (held-newest) |
| Same-host native comparison | fractal-2, `07867931…` | 1000 ms | 4/4/0/0/0 | 582/816, 653/707 |
| Fresh focused class, unchanged main | build-hp-1, `151a9dbd…` | 1000 ms | 4/4/0/0/0 | 433/555, 407/420 |
| Fresh acceptance with #2228, main `51954f1d…` | build-hp-1, `151a9dbd…` | 2000 ms (SwiftShader) | 4/4/0/0/0 | 441/614, 415/416 |

The last run is also the first device run of #2228's renderer detection: every
installed class logged `event=first_draw_bound bound_ms=2000` with the SwiftShader
renderer, and the optional-projection twin held for **2100 ms**. Cold decode in
that run was **247–318 ms**, ordinary draws **118–258 ms**, and every opening also
stayed within the unchanged **1000 ms** product target. The slow-restore control
drew at **3473 ms** and was rejected (3000 ms inserted delay against either bound).
Readiness passed all **17 barriers** over **83 windows**, maximum acquire/read
**10/37 ms**. The build-hp-1 focused class before it recorded cold decode
**249–291 ms**, history metadata **362–522 ms**, ordinary draws **129–241 ms**, its
slow control rejected at **3494 ms**, and readiness passed 17 barriers over 85
windows with maximum acquire/read 14/35 ms.

**Attribution limits.** Readiness passed for every opening in every run above —
these are elapsed-draw results, not readiness failures. Thirty-six bounded
host/process/guest observations during the investigation add diagnostic overhead
only: five-second guest intervals (with up to 16.17% steal) cannot attribute an
individual subsecond frame, documentation search adds zero CPU ticks, lifetime
process-CPU averages are not current-load evidence, and a teardown's
destroyed-activity focus record is not a crash diagnosis. The unchanged-Mobile
owner comparison passing 4/4 after the readiness repair (versus 4/3 before)
clears neither this later red nor a latency cause. `build-fractal-2` was not run
under the new #2228 bound by this ticket; a Fractal result under that bound comes
only from the dispatcher's configured UI gate, never from this investigation.
Closed #2202's historical **1434/1436 ms** disposition remains separate and
unattributed by this work.

[Checked summaries](../../../app/src/androidTest/assets/saved-first-draw-2218/)
retain per-opening phases, the launcher/generation comparison, FileConversationCache
stage logs and GC overlap for the failing run, and all four runs' counted results.
Raw XML, phone logs and manifests are under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2218/` (`hp1-focused-20261017/`,
`hp1-software-bound-20261017/`); the #2177 failing originals are in bundle run
`0ad15d3ecd76afa6fdb7df76c2d873f197352b0fa42649e2a683fdcdaaf7c7da`. Documentation
inspected these retained reports and ran no comparisons.
