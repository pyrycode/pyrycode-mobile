# Early authoritative saved-history paging evidence

Historical evidence moved verbatim from the [owning paging topic](authoritative-reader-paging.md).
Current contracts and accepted receipts remain there; these earlier passes do not accept later candidates.

## Native and deterministic proof

`SavedThreadFirstDrawDeviceTest.databaseSavedThreads_upwardDemandAndRetry_offlineAndHeldNewest_coldReopen`
uses the real production negotiated destination and on-disk 18000-display-row fixture. Upward
pointer demand and explicit retry must bring exact older text inside the clear message viewport
on offline and held-newest openings and cold reopens. Each arm admits one successful 200-row
page, retains 400 displayed rows, keeps request counts independent of the held network response
and starts no extra page at rest. Gestures use the measured reading area; Retry is revealed
before the pointer click. The **1000 ms product target**, renderer-specific `firstDrawBoundMs`,
36000-entry/18000-span full-file fixture, delayed projection and slow control remain unchanged.

The production repository/ViewModel disk tests complement the native viewport check. The
18000-row traversal uses 120 saved groups of 150 separated by 250 genuinely absent identities,
plus 1000 non-rendering facts. Every page retains exact raw revisions/clears, display
identities/text/order and numeric query/result/decode counts, unchanged certification and daemon
continuation, owned-write preservation and fresh reopen under colliding ids on another host.
Retired-boundary tests cover ancestor movement/reranking and mixed standalone successors.
`survivingBoundaryRecoversMovedOlderComponents` adds the surviving-boundary case with 600
children and 200 standalone rows, plus 220 standalone rows whose nearest 20 predecessors do
not move and 420 standalone rows that put the moved group beyond the entire lookahead. Each
requires offline demand, exact ordering, no extra requests at rest, bounded
metadata/payload work, unchanged certification and complete fresh-reopen traversal.

The initial manual test-first run at `b7837a9df` executed **1 / 0 passed / 1 failed / 0 errors/skipped**
on Linux/build-fractal-2: no older children reached the selected projection. Retained XML is
`/tmp/manual-2334/red/TEST-de.pyryco.mobile.data.cache.AuthoritativeReaderPagingTest.xml` in the
mobile dispatcher container; this temporary location is copied into durable ticket evidence
before handoff. A passing repaired run and complete configured acceptance must be recorded
separately below; the previous review's green gates do not accept the new manual candidate.

The bounded-predecessor repair then passed the 200/220-standalone variants but failed the
new 420-standalone regression at `4b4c0ee11`: **1 executed / 0 passed / 1 failed / 0 errors/skipped**.
The intervening full paging run at `51e7a87bc` recorded **12 executed / 11 passed / 1 failed**,
with its remaining failure in the exact metadata-cost assertion after adding predecessor
lookups. Neither result is counted as acceptance of the final committed-bound design.

### Manual committed-bound paging result

The tested tree committed as **`e189309842e7d4eeea08fbea36f65c1efb1a14a9`** ran
`AuthoritativeReaderPagingTest`: **12 executed / 12 passed / 0 failures/errors/skips** on
Linux/build-fractal-2. Each of the 89 fragmented-history pages returned/decoded **200 payloads**,
with **200 metadata results / 6 queries / 1 evidence result and decode**, including the first
page after an owned write. Every 200/220/420-standalone movement variant returned/decoded
**201 payloads** (200 selected children plus their required parent), with **200 metadata
results / 7 queries / 1 evidence result and decode** on each of its three child pages.
Exact offline content/order, certification, request independence and full reopen walks passed.

The passing XML and all raw page records are retained at
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2334/manual-20261021/paging/`.
Historical red XML/logs remain alongside it in `red/`, `frontier-red/` and `predecessor-run/`.
The final main merge adds unused test-fixture helpers and a separate screen regression;
production paging code is identical. The remaining manual writer/native checks passed as recorded below. Independent complete
configured acceptance must still validate the final candidate before acceptance is claimed.

### Retained manual JVM regressions

On Linux source **`40d3fd69e22309bd99af423155f08cff713e1d85`**, the following affected classes
passed with **zero failures/errors/skips**. This merges current main without changing the
production paging behavior tested above.

| Class | Executed/passed |
| --- | ---: |
| `AuthoritativeRoomStorageTest` | 15/15 |
| `AuthoritativeWindowRestoreTest` | 25/25 |
| `AuthoritativeOwnedUpsertTest` | 17/17 |
| `AuthoritativeOwnedRemovalTest` | 18/18 |
| `AuthoritativeThreadCacheTest` | 23/23 |
| `AuthoritativeThreadQueriesTest` | 23/23 |
| `AuthoritativeThreadStoreTest` | 15/15 |
| `MigratingConversationCacheTest` | 17/17 |
| `NegotiatedThreadViewModelTest` | 6/6 |
| `SavedAuthoritativePlacementTest` | 5/5 |

Together with the 12 paging methods, this is **176 executed / 176 passed / 0 failures/errors/skips**.
The new `readerBoundsRequireCommittedOwnedPlacementHistory` method proves rollback preserves
both content and the old bounded position, committed movement recovers the older component,
and an unknown storage writer or intervening generation cannot authorize exhaustion. A later
ordinary write cannot bless that missing receipt; a fresh committed opening obtains a new one.
Per-class XML and logs remain at the `manual-20261021/` evidence root above.

### Manual native, build and scripted receipt

Source **`40d3fd69e22309bd99af423155f08cff713e1d85`** passed forced
`lint assembleDebug compileDebugAndroidTestKotlin spotlessCheck --rerun-tasks` through the
shared Linux admission gate. Native invocation **`ui-wbh4wodd`** executed **6 / 6 passed /
0 failures/errors/skips**, each named method once:

- `databaseSavedThreads_upwardDemandAndRetry_offlineAndHeldNewest_coldReopen`
- `databaseSavedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_coldOpenAndReopen`
- `savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen`
- `delayedOptionalProjection_cannotHoldFirstNewestDraw_firstOpenAndReopen`
- `slowRestore_negativeControlRejectsTheSameFirstDrawBound`
- `heldCpuPipeCancelsAndClosesItsDescriptor`

Positive committed-frame measurements in offline first/reopen, held-newest first/reopen order:

| Fixture/operation | Frames (ms) | Retained bounded-read evidence |
| --- | --- | --- |
| Database upward demand/retry/reopen | 499 / 345 / 410 / 281 | Two attempts, 400 displayed rows; successful page: 200 payload results/decodes, 200 metadata results, 6 queries, 1 evidence result/decode |
| Database opening/reopen | 320 / 329 / 349 / 308 | 200 payload results/decodes, 0 metadata results, 5 queries, 1 evidence result/decode |
| Fragmented full-file compatibility | 660 / 293 / 622 / 327 | 36000 durable entries / 18000 spans / 18000 rows preserved |
| Ordinary full-file compatibility | 189 / 204 / 198 / 187 | Complete compatibility restore |
| Fragmented delayed projection | 813 / 254 / 648 / 337 | Optional projection cannot hold the newest frame |
| Ordinary delayed projection | 228 / 174 / 203 / 193 | Same delayed-projection contract |

All positive frames are below the unchanged **1000 ms product target**. This managed emulator
reports ANGLE/Vulkan SwiftShader and the unchanged **2000 ms renderer-specific assertion bound**.
The slow negative control committed at **3774 ms** (restore 3126 / snapshot 3598 / complete 3628)
and correctly rejected that same bound. The fixture, delay and control were not reduced.
Paging asserted exact older text inside the clear viewport, request independence and no extra
page at rest in all four openings.

Scripted invocation **`scripted-rpezety1`** executed **1 / 1 passed / 0 failures/errors/skips**:
`DeterministicInteractiveStreamE2ETest#interactiveTurn_seededChannel_missedEventsReplayInOrderAfterReconnect`.
It used zero real Claude turns. Daemon source/revision was
`647a4e7526f581f78d422195cb22b3e9b979e767`, binary SHA-256
`871b3cf96e8c8993f09cedc97b8269bb2f4c80b7f829743afaae7f5a4d32a49a`.
Retained harness inputs/logs are under ticket evidence
`harness/fault-cf97a5cfc843964f50eef57a35e9ee31/`.

The exact APKs predate the native invocation's completed preparation and were unchanged
through the scripted run: application SHA-256
`07e4e41c19b64a18a4648faeb0a94e1a5b644435b8415512a8b75666552495b9`,
instrumentation SHA-256 `9f51df7cdc87f14eaaffec41ccec60cbaa52ff758b1f53bdf5b220a12974a6ef`.
Managed image identity:
`cec1892f04abcb5b7c13b3954e66527b3567c105c6ee2d60b7e3d797ae0d0135`.
The durable `manual-20261021/` root retains `build.log`, `native.log`, `scripted.log`,
`apk-identities.json`, each invocation's counted `dispatcher.xml`, original XML, phases,
managed-image metadata and raw phone logs, plus `SHA256SUMS`.
These manual results complement the 176 JVM methods; independent review and complete configured
acceptance remain required on the repaired candidate and must receive a separate final receipt.

## Scope-amendment history

### Last integrated run before the scope amendment

Source b5618a873fcc795133bbb99cd0feb27022c0964e passed333/333affectedJVM methods across21classes,
zero failures/errors/skips, plus build,lint,native compilation and formatting. Native
ui-fkym3jib executed6/pass4/fail2/errors0/skips0 under the earlier file timing contract.
DB first-draw frames258/243/228/215ms passed, each with200payloadresults/decodes,5queries and
zero full reads. Paging/retry/reopen,descriptor cancellation and the earlier file slow control
passed. Both file methods completed all exact content/read/request arms without heap
exhaustion, but guarded frames2561/2260/2359/2187ms and ordinary frames2106/2301/2415/2437ms
missed2000ms. Tree validation measured97–145ms after the equivalence-tested positive-integer
allocation change. This attempt remains failed; the amended fixture must execute afresh.
Complete raw reports/phone/APKs/sources/image/runtime and receipt are retained at
logs/evidence/2452/manual-20261022/integrated-b561, manifest
a3e05366ced8ae79d5fc5bbd3711acf251a1ffe3b92f18ff34400ac981d66761.
The six selected native methods use the production registry/coordinator/repository/ViewModel
with controlled transport; they do not execute an external daemon. The amended native pass and subsequent independent #2452 acceptance are recorded below.

## Prior first-draw map retained during reviewed storage retirement

The following earlier map is retained verbatim as history. The approved authoritative-only #2447 mapping and current accepted-source evidence in this companion govern the pending acceptance. Historical focused passes do not accept a later candidate.

### Earlier #2334 map

The [paging proof and counted evidence](authoritative-reader-paging-early-evidence.md#native-and-deterministic-proof)
covers offline/held-newest demand, explicit retry and cold reopen through the production
negotiated destination and real database. It retains the 1000 ms product target, unchanged
renderer bound, full-file fixture, delayed projection and slow control below. The bounded
repair in #2437 adds production disk/repository/ViewModel reset-after-settled-Retry and exact
reader-replacement cleanup probes. Its focused manual 178 JVM and six native passes are
recorded separately from the parent's failed review and pending independent acceptance in
the companion; they do not establish final #2334 acceptance.

### Earlier updated first-draw map retained at integration

The [paging proof and counted evidence](authoritative-reader-paging-early-evidence.md#native-and-deterministic-proof)
covers offline/held-newest demand, explicit retry and cold reopen through the production
negotiated destination and real database. It retains the 1000 ms product target, unchanged
renderer bound and slow control. The [approved current native mapping](authoritative-reader-paging.md#approved-native-acceptance-mapping-2447) replaces retired legacy span/projection checks; the legacy measurements below remain historical. The bounded
repair in #2437 adds production disk/repository/ViewModel reset-after-settled-Retry and exact
reader-replacement cleanup probes. Its focused manual 178 JVM and six native passes are
recorded separately from the parent's failed review and pending independent acceptance in
the companion; they do not establish final #2334 acceptance.
