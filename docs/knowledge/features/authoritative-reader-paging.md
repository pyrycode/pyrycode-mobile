# Authoritative saved-history reader paging (#2334)

The production [repository](caching-conversation-repository.md#older-authoritative-saved-pages-2334)
uses bounded saved pages before the existing daemon history coordinator. The full
[cache contract](conversation-cache-contract.md) retains storage, ownership and writer rules.

## Current DB and file acceptance scope — approved 2026-10-22

Juhana explicitly approved separating full-file latency from #2334. Normal channel opening
uses MigratingConversationCache and AuthoritativeRoomStorage to load roughly200 display
contributions plus required parent/active facts. FileConversationCache still supports
conversation metadata/read positions and one-time authoritative snapshot import. The direct
36000-fact FileConversationCache fixture is a compatibility stress case; its frame time is
neither normal bounded DB opening latency nor an actual file-to-Room import measurement.

The1000ms product target and2000ms SwiftShader assertion remain binding for DB opening and
reader demand/retry/reopen. Both file compatibility methods retain exact raw facts, numeric
spelling, ownership, immutability,36000facts/18000islands/18000visiblemessages and exact
read/request assertions. Their positive timing bound is enabled only by the test-only
requireFullFileLatencyBound=true instrumentation argument and belongs to separate
[#2456](https://github.com/pyrycode/pyrycode-mobile/issues/2456), which is not a #2334 blocker.
The actual3000ms slow-read negative control now uses DB newest-window reads in all four
offline/held-newest first/reopen arms and must reject the same renderer bound. The retired
projection/API mapping remains as documented below; no unused dispatcher delay is claimed.

This approved scope supersedes earlier positive full-file timing requirements in this
companion and the six issue contracts. Historical failures remain failures of their original
runs. Retained #2449 optimization code still requires independent correctness/security review
before merging; no further file-latency implementation is needed for DB acceptance. Every
child and the final parent retain independent complete configured JVM/UI/scripted gates,
actual documentation and reviewed merge/Done requirements.

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
with controlled transport; they do not execute an external daemon. Current amended-source
native validation and independent acceptance are pending.


## Approved-scope native validation

Source324df55be6a3978a5a166aac3bcfec3af1cd0acf passed forced formatting and native compilation/SpotlessCheck, then ui-maqwbx4h selected/executed6/pass6/fail0/error0/skip0. DB first-draw frames247/244/243/207ms retained200payloadresults/decodes,5queries,oneevidence row and nofullreads. The actualDB3000ms slow-control frames3868/3403/3343/3320ms all rejected the same2000ms bound while retaining identical counts. Paging/retry/reopen and descriptor cleanup passed. Both complete file compatibility methods logged required=false/followup2456, each8ordinary/fragmented offline/held-newest first/reopen arms, exact36000facts/18000islands/18000visiblemessages and fullreads offline1/online2/retiredAPIs0. File fragmented frames2166–2646ms remain measurements for2456, never normal DB latency or actual one-time import timing. Raw XML/phone/APKs/source/plan/image/runtime/receipt:2452/manual-20261022/db-file-separated-324d, manifestffce81fc6fb25b0fb76309e20130c9b9c8c29168ed1deea44ef14b935c33ad4c. The selected native runtime uses production destination/repository/ViewModel with controlled transport, no external daemon. Independent complete configured acceptance and actual documentation/merge remain pending.

## Storage continuation contract

`beforeAuthoritativeWindow` returns up to 200 additional display contributions plus validated
active/parent/placement extras. Content and launch contributions are selected independently;
non-rendering facts consume no row budget. `AuthoritativeDisplayCursor` is distinct from the
raw-fact continuation and daemon coverage, and binds host, conversation, epoch, incarnation,
storage-owned seek bound/generation. Storage retains one content-free record per live cursor
in a weak-key registry,
under its existing mutex. The owned writer's already-required affected-component traversal
compares each old contribution position with the reader's pre-mutation bound, extending that
bound to include the new placement. Reader bounds and the new header generation publish only
after that transaction commits; a rollback preserves the old receipt. It tracks older saved
components even when the boundary survives, nearer predecessors belong to other components,
or the moved group lies beyond a fixed lookahead. No whole-thread payload scan or payload lookahead
is added, and obsolete cursor records retain no payloads or accumulated identity lists.

A bounded page starts at its registered bound, filters admitted content/launch identities and
mints its continuation at the oldest newly selected contribution. Missing generation custody,
a replacement or a write by another storage instance makes the old cursor unavailable/retry;
it cannot authorize daemon work. The production migration-aware cache has one shared instance
for readers and writers. Fresh reopen selects current committed placement and mints a new
cursor. Exact owner, epoch, incarnation and observation lifetime fences remain in force.

One reader movement or explicit retry admits one local page independently of newest/catch-up;
completion/reconnect never walks history automatically. Failed local reads keep held content and
position for retry. Only successful local exhaustion forwards that demand to the existing
coordinator at its certified continuous daemon boundary. Local exhaustion changes no
`olderExists`, daemon continuation, checkpoint or coverage. Fragmented saved groups remain
reachable without making holes continuous. Subsequent owned saves retain unloaded facts and
durable certification; explicit retirement retains its existing checkpoint-zero/null-coverage
contract. Numeric read results include skipped admitted metadata candidates and
extras; selected payload/decode work stays bounded to the requested page and required closure.
[Paging and reopen evidence](#native-and-deterministic-proof)
records exact test/source identities and preserves historical failures.

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

## Local load ownership repair (#2437)

A local Loading/Retry record names the exact observation and runtime generation. Registration
replaces the previous status and cancels its local hydration. Runtime generation advancement,
including a reset reusing the same epoch text, clears obsolete status, cursor and work before
snapshot delivery. Result and cleanup publications update only their captured owner. Ending a
reader clears settled Retry as well as Loading; old cleanup cannot erase a replacement reader's
state. An ordinary failed read in the same generation keeps its cursor for explicit retry.
Daemon requests keep their existing slot and certification; they do not set the legacy/local
history-tail Loading flag. The reset probe asserts exact request ownership and None tails
while those requests are pending and after success/exhaustion, rather than inventing a new UI
loading state.

The production disk/repository/ViewModel reset probe opens 600 saved facts, fails local demand,
settles Retry, resets with reused epoch, admits separate daemon fact batches and empty terminal
coverage for newest and older responses, then checks exact facts 599/600, exhaustion, request
counts, no page at rest and fresh disk reopen. The replacement probe collects two cold
observations of the same repository, cancels the old reader while the new local read is held,
and proves its Loading/completion/ordinary Retry and final cleanup remain owned. Separate
destination factories create separate repositories and cannot establish this owner collision.

### Reader ownership at the application boundary

A local page and observation-owned supplement carry their captured reader through validation.
Before mutating either store or persisted baseline, the repository checks current reader
identity and coroutine cancellation under the store monitor. Registration and owned cleanup
share it; both application mutations commit there together. A page blocked entering that
monitor cannot apply after replacement. Cursor completion is serialized against replacement.
Disk reads and validation remain outside. Coordinator calls stay outside the monitor to retain
its lock order. This adds no schema, wire behavior, payload traversal or automatic demand.

Independent run `450817178f` rejected `d81339fd3`: its controlled production disk/repository/
ViewModel probe grew 200 facts to 400 after replacement without new demand. Configured gates
were green: **6462/6462 JVM, 338/338 UI, 22/22 scripted**, zero failures/errors, one UI and one
scripted skip. All six named saved-thread native methods passed. Database opening frames
were **269/274/212/222 ms**; full-file fragmented frames **605/218/612/156 ms**; delayed
fragmented frames **687/226/636/202 ms**. The unchanged 2000 ms SwiftShader bound rejected
the **3090 ms** slow control; product target 1000 ms and the 36000-entry/18000-span fixture
remain unchanged. These historical gates do not accept the application-boundary fix.

Complete XML, original native phone logs, APKs and source/daemon/image identities were copied
before cleanup to `logs/evidence/2437/independent-d813-20261022/`; the 963-file manifest was
`bc8423e315e61a9b30c10b02d6d170388ada1d3895bbc16295298fc68ce37e5f` before the final verdict
was added. The failing probe is retained separately in
`logs/evidence/2437/verifier-reader-apply-race-d813-20261022/`.

Permanent method `AuthoritativeReaderPagingTest.retiredReaderCannotApplyAfterReplacementAtStoreMonitor`
controls the same boundary by restore symbol rather than line number. It checks unchanged
facts, opening rows, complete snapshot and persisted baseline, then successful replacement
demand. Test-first source `cb2ed3808` executed **1 / 0 passed / 1 failed / 0 errors/skips**,
expected 200 but observed 400. Its red receipt is in
`logs/evidence/2437/manual-reader-apply-20261022/red-cb2ed/`, six files, manifest
`0530c5dab5d64d75cf27fc976fa0ac76b03d00285105eb4d01144c0791a0e4ad`.

Repaired formatted source **`c183f82d85cdc6de5b4ec64a25ee45d730b02f50`** passed **85/85 affected
JVM methods**, zero failures/errors/skips: paging 15, cache wrapper 26, authoritative cache 23,
store 15 and negotiated ViewModel 6. Forced format, lint, assembly and instrumented compilation
passed. Exact tested files, formatter diff, five raw XML reports and full logs are retained in
`logs/evidence/2437/manual-reader-apply-20261022/green-4e6/`, 15 files, manifest
**`9e812e67d8c8ea63f05703f5bc9d4cbc1eb8ee1009b4e10a4d5d3bc829967073`**. Three formatting
preparation failures are retained separately and executed no tests. Final pre-verification,
complete independent current-candidate acceptance and actual documentation completion remain
required. Child acceptance/merge must precede #2334's own final acceptance, documentation
and merged/Done workflow.

### Retained independent FAIL on the preceding candidate

Verifier `26f513ea363b0a75f3e0aa79933b529b79e1a995bc5dc0794e88429cfa343798`
reviewed `b7724753efb9cc9848cdea2bbe5ebb3370bb71e8` and confirmed the committed-bound storage
repair, but returned **FAIL** for settled Retry surviving reset. Its configured Linux gates
were accepted under the existing known-flake rerun policy, not as acceptance of this fix:

- JVM `jvm-o66eep9e`: **6450 executed/passed, 0 failures/errors/skips**.
- UI `ui-rkv5xsl0`: **338 executed, 337 passed, 1 failed, 0 errors, 1 skipped** (339 listed).
  The finished-Agent recorded method passed its named 1/1 rerun `ui-7i4n3at7`, tracked #2373.
- Scripted `scripted-all-9yjnp4uo`: **22 executed, 21 passed, 1 failed, 0 errors, 1 skipped**
  (23 listed). Reply-suggestion long-press passed named 1/1 rerun `scripted-all-von_3u71`,
  tracked #2436. Historical failures and both skips remain recorded.

All six saved-thread native methods passed in that UI run. Raw XML, phone/read records,
identities and the full FAIL are retained at
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2334/verifier-retry-reset-20261021/`;
manifest SHA-256 `853f92cc02d17f55546b58807a9ce8ce5bcc6afdeb77a1acccf35074b97b9f53`.
That result allocated blocking child #2437. Child closure cannot establish parent acceptance.

### Manual repair checks, before independent acceptance

Linux/build-fractal-2 source **`a0b55d71a`** executed **178 affected JVM methods / 178 passed /
0 failures/errors/skips**: paging 14, Room 15, opening 25, migration 17, placement 5, owned
removal 18, owned upsert 17, cache 23, store 15, queries 23 and negotiated ViewModel 6.
Lint, assembly, instrumented compilation and formatting passed. The focused two new methods
also passed separately, 2/2 with zero failures/errors/skips. Initial real test-first source
`9da782ce6` failed 1/1 with `expected Idle but was Retry`. Subsequent fixture mistakes are
retained separately: `focused/` 2/2 failed for an incorrect daemon Loading expectation and
separate-repository setup; `focused-2/` passed ownership but timed out on an invalid terminal
frame carrying facts. Those fixture failures do not count as new product defects or acceptance.

Native invocation **`ui-x6u8ru_r`** executed/passed all six existing
`SavedThreadFirstDrawDeviceTest` methods once, **0 failures/errors/skips**:

- `databaseSavedThreads_upwardDemandAndRetry_offlineAndHeldNewest_coldReopen`
- `databaseSavedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_coldOpenAndReopen`
- `savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen`
- `delayedOptionalProjection_cannotHoldFirstNewestDraw_firstOpenAndReopen`
- `slowRestore_negativeControlRejectsTheSameFirstDrawBound`
- `heldCpuPipeCancelsAndClosesItsDescriptor`

In offline open/reopen then held-newest open/reopen order, paging frames were
**529/414/396/291 ms**, with 400 rows after two attempts, exact older text visible and no
extra page at rest. Each successful page returned/decoded **200 payloads**, with **200 metadata
results, 6 queries, 1 evidence result/decode**. Database opening frames were
**302/321/322/326 ms**, each with 200 payload results/decodes, 0 ordinal results, 5 queries
and 1 evidence result/decode. Full-file compatibility retained **36000 durable entries,
18000 spans and 18000 rows**, with frames **598/270/585/285 ms**. Delayed fragmented frames
were **775/277/653/301 ms**. Every positive frame met the unchanged **1000 ms product target**;
SwiftShader used the unchanged **2000 ms assertion bound** and correctly rejected the
**3632 ms** slow control. Descriptor cancellation also passed.

Evidence is retained under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2437/manual-20261022/`:
`red/`, historical `focused/` and `focused-2/`, passing `focused-3/`, and `affected/` containing
fresh per-class XML/counts, Gradle output and the native invocation with all six raw phone logs.
App APK SHA-256: `5b3ba7ed9b134edc1a6c533c0b5a74370c6fb7801ec526c71576aad040214ca7`;
test APK: `5837434e99fef03299b4c6ebbb076ec73c1701b6f8cad9f6adb8984df4abdff5`.
Managed image identity: `cec1892f04abcb5b7c13b3954e66527b3567c105c6ee2d60b7e3d797ae0d0135`.
The later integration of main `4cf670c96` contains the independently merged #2373/#2436 test
and documentation repairs; it changes none of this repair's production or saved-thread native
code. Final pre-verification and independent complete configured acceptance must bind the
resulting candidate. These manual passes do not replace either child's review or parent's
current-candidate acceptance, documentation stage and final merged/Done workflow.

### Authoritative-only main integration: acceptance still blocked

The ordinary merge of reviewed main b6845bf5d into the manual ownership repair produced 4256ef355499. Its configured JVM/build check (jvm-sks0j84x) executed 5354 methods: 5353 passed, one failed, zero errors/skips. Native compilation failed before device execution because the retained paging probe lacked its assertIsDisplayed import. The sole JVM failure expected the retired legacy Offline tail after local state became Idle; authoritative-only presentation returns None. Both idle-boundary expectations and the import were corrected without changing production code or Loading/Retry, content, reader ownership or request assertions.

Repaired source 897ddbff4c514efb065bb9ca3ab86647b14e318b passed the single selected replacementReaderOwnsLocalRetryAndCleanup method (1 executed/passed, zero failed/errors/skips), forced Spotless (six tasks), and compileDebugAndroidTestKotlin. Native executed count remains zero for this integration. Retained raw reports, source and receipts are under logs/evidence/2437/manual-reader-apply-20261022/main-b684-integration/: failed-4256 manifest fb1723e1cde245782bb13821a28fc9185d1d77778c19f58b374636e2e98616d8; green-897d manifest 13b723c23395af59f24528c6932c7c00e83327a8a69761c0fc1d774937d6cb93. The original failure remains evidence; this focused rerun does not establish complete configured acceptance.

Main's reviewed runtime retirement removed three former native methods, the optional legacy projection consumer and multi-span legacy coverage. Current projectionDispatcher is unused, and SavedThreadCoverage represents one interval. A delayed unused dispatcher or a logged span count cannot substitute for the binding native proof. Manually held blocker #2447 requires an approved compatibility mapping before fixture implementation; all six methods, current independent gates, actual documentation and final workflow completion remain outstanding.

### Approved native acceptance mapping (#2447)

Juhana approved the authoritative-only replacement on 2026-10-22 after reviewed main #2367/#2379/#2385 removed legacy runtime content, unsigned-span coverage and the optional legacy projection consumer. Full-file acceptance now restores the supported FileConversationCache authoritative envelope through the real host registry/coordinator/repository/ViewModel destination. The 36000 actual raw facts occupy 18000 disjoint two-order islands; each island contains one visible message and one hidden forward-compatible fact, giving exactly 18000 visible messages. Decoded facts supply the cardinalities, exact raw/revision/order assertions and island count. Islands do not certify coverage holes; coverage is null. The small control has 20 visible facts.

Each offline and connected/held-newest arm creates a fresh cache, destination, repository and ViewModel for first opening and disk reopen. Timed placement and committed frames retain the 1000ms product target and unchanged renderer assertion policy (2000ms SwiftShader). The removed delayedOptionalProjection method maps to authoritativeSavedThreads_firstDrawDoesNotConsultRetiredLegacyReads_firstOpenAndReopen: actual retired readThread/readHistoryPosition calls are counted and guarded, and must remain zero while authoritative content draws. This explicitly retires the projection-specific requirement; delaying its now-unused dispatcher would prove nothing. The slow control delays the actual authoritative disk read 3000ms and must reject the same bound. Database opening, upward demand/retry/cold reopen and descriptor cancellation remain the other three native methods.

The supported full-file fallback restores for observation and again before a connected live lease: assert exact full-read counts offline1/online2, separate from indexed Room opening/page bounds. Initial native ui-5q4d3fq2 executed six methods: four passed, two failed, zero errors/skips. Both new file probes stopped at the ordinary online arm on an incorrect fixture expectation of one read; the 36000-fact arms had not executed. The four retained passes included the actual-read slow control (3874ms correctly rejected the unchanged2000ms bound). These failures remain in logs/evidence/2447/manual-20261022/second (manifest8550f857c55c2e5009cc18c941f21c350bbed92927fb1963200f17a3cb7cf827). First preparation failed formatting before native execution and remains in first/. Current corrected native validation, independent complete configured gates, actual documentation and final parent acceptance/merge/Done remain required.

### Authoritative validation allocation repair (#2449)

The corrected #2447 fixture on c2f6bcf804f4fec8e68ae53b12379c3a0861e182 exposed a real performance miss in ui-pej4ek4w: six native methods executed, four passed, two failed, zero errors/skips. Both 36000-fact offline arms actually restored all18000order islands/18000visible messages, with guarded committed frames5158/5094ms and ordinary-file5139/5387ms. Actual file restore was2756–2876ms and snapshot4457–4719ms; connected arms timed out. These are failures of both the1000ms target and2000ms SwiftShader bound, not permission to shrink the fixture. Raw XML/phone/APKs/source remain in logs/evidence/2447/manual-20261022/third, manifest52d0b1ddc9e573b491c00d6fe0444daedd198f49162e20f9980122e769037b91.

Bounded blocking child2449 removes repeated optional-field set/map allocation from ThreadItemDto.decodeReplacement and the per-primitive literal set in validThreadJson. Strict validation and every lexical/null-clear/unknown-field rule remain; it adds no decoded-result memoization, new lifecycle state or owner/generation bypass. Test-first source6659a0a9c ran one selected/executed regression against unchanged production: zero passed, one failed, zero errors/skips; it exposed one full raw-map enumeration where no optional clear needed a filtered view. Fresh raw XML/log/source/plan are retained in logs/evidence/2449/manual-20261022/red-6659, manifestc5dc6017c69f09a5e4c7feb597518dba3837fdb99f7b0616ef080354d30017d1. Repaired source de830201b is undergoing affected JVM/build and unchanged six-native validation. No current-candidate acceptance is claimed; independent configured gates and actual documentation/merge/Done remain required through2334.

### First allocation repair remains unaccepted (#2449)

On de830201b4a91b05c00090ae6e601542629c55c0 the affected checks executed160JVM methods, all passed, no failures/errors/skips; lint, APK build, native compilation and forced formatting passed. Native ui-jigxh9df executed6/pass4/fail2/errors0/skips0. Full36000fact offline frames improved to3426/3473/3875/3808ms but still exceeded2000ms SwiftShader and1000ms product target. Actual restore1961–2264ms and snapshot2786–3161ms remained expensive. Both connected fragmented arms timed out after20000ms with raw phone evidence of192MiB heap exhaustion during repeated restore. Database frames280/341/271/251ms passed; the actual3000msread slow control drew3877ms and correctly rejected the same bound. The complete attempt (whose directory name is not a green verdict) is logs/evidence/2449/manual-20261022/green-de830, manifest80bcec7b179ae605215cfb518f49a3bed9736feff27423e8e540613f65ac5d9b.

The next bounded2449 step is exact-byte, owner-keyed FileConversationCache decode reuse with a deeply owned read-only validated saved value. The current mutable-value/copy validation and all repository/store generation/reader checks must remain. Test-first regressions and subsequent acceptance are pending; no parent or child is accepted from these160JVM passes.

### Owned-reuse attempt remains unaccepted (#2449)

The deterministic reuse regression on ba545ce3e2 failed1/1 against unchanged production: identical disk bytes produced a new saved decode (manifestdb1715adf69a90443068b7430bed15eda3bb0cf130580936a734387bc0284ebb, reuse-red-ba545/). Repaired d87e5ff411 plus retained formatter output passed337/337affectedJVM methods across20classes with zero failures/errors/skips, plus forcedformat/lint/build/native compilation. Its new safety tests cover mutable ordinary values, copy revalidation, nested object/list immutability, same-size replacement, foreign owner, corruption, missing document and reset.

Native ui-k_00y57r executed6/pass3/fail3/errors0/skips0. The guarded full-file method completed all eight arms and exactcontent/read/request assertions, but fragmented frames4104/3867/3798/4654ms remained above2000ms. Restore2590–3150ms dominated. The other file method exhausted192MiB heap at its fragmented offline opening. Paging also failed an exact payload-decode count (expected200 actual400) in online opening0 after both offline arms passed; retain this independent finding rather than changing paging inside the performance child. Database first draws323/310/240/209ms passed; actual slow-control3884ms correctly rejected the unchanged bound. Full reports/phone/APKs/sources/image/runtimeidentity: logs/evidence/2449/manual-20261022/reuse-green-d87e, manifestab956819d75daad350e64bafd9e7b201c447a844dcc170a657a10ceee53f1474. Native timing/heap and the new count finding are unresolved; no acceptance is claimed.

### Combined numeric repair validation and repeated paging finding (not accepted)

Source33516287c3 plus formatter-only6a3a05479d passed340/340affectedJVM methods across20classes, zero failures/errors/skips; all10AuthoritativeFileCacheTest methods passed, including exact-number preservation and invalid-token rejection. Lint, assembly, native compilation and forced formatting passed. Native ui-qpyy9qa8 executed6/pass3/fail3/errors0/skips0. Both complete file methods ran every ordinary/fragmented offline/held-newest first/reopen arm, retaining exact36000rawfacts/18000islands/18000visiblemessages/read/request/zero-retired-read assertions without heap exhaustion. Fragmented draws still missed2000ms: guarded3251/3396/3294/3149ms, ordinary2909/3222/3169/3542ms. Parse1051–1542ms, treevalidation799–858ms, typed136–144ms, remainder10–30ms; ownedfreeze~0.01ms. No timing target or workload is relaxed. Database first draw passed all four arms232–295ms; actual3000msread slow control drew3784ms and correctly rejected the bound; descriptor cancellation passed. Full raw XML/phone/APKs/source/image/runtime are retained in logs/evidence/2451/manual-20261022/green-3351, manifest25ef9e1c4117f197164e49e018edcef0cf29f22bccf2aa30fd4816d9b70bc8bf.

The paging failure repeated expecteditemDecodes200/actual400 on offlineopening1 after offlineopening0 passed. Chronological phone records show each of the two explicit attempts decoding200, then a separate read_parent_closure after successful reader_page in the failing arm only. Protected child2452 owns its causal investigation; it blocks2451 and all parents. Preserve the earlier d87e failure and unchanged-tree named pass; this repeat is not accepted flakiness. Parent2449 retains latency ownership; numeric2451 stays limited to exact validated number encoding. No current independent candidate is accepted.

Numeric2451's additional real FileConversationCache→SavedAuthoritativeDestination/registry/coordinator/repository/ViewModel exact opaque-number cold-reopen probe ran on59f2079541 plus formatter-only7694aeb3af:1selected/executed/passed,0failures/errors/skips, four offline/held-newest first/reopen arms with exact raw values and no extra request at rest. Evidence logs/evidence/2451/manual-20261022/reopen-59f2, manifest0d7dac300d6aab6f12770f11a77080ac2603e7adaddd0145f4e073ad3164d46c. It supplements340green without claiming current native or independent acceptance. Every child and parent still requires its own independent complete configured Linux gates, actual documentation and reviewed merge/Done.

## Prior first-draw map retained during reviewed storage retirement

The following earlier map is retained verbatim as history. The approved authoritative-only #2447 mapping and current accepted-source evidence in this companion govern the pending acceptance. Historical focused passes do not accept a later candidate.

### Earlier #2334 map

The [paging proof and counted evidence](authoritative-reader-paging.md#native-and-deterministic-proof)
covers offline/held-newest demand, explicit retry and cold reopen through the production
negotiated destination and real database. It retains the 1000 ms product target, unchanged
renderer bound, full-file fixture, delayed projection and slow control below. The bounded
repair in #2437 adds production disk/repository/ViewModel reset-after-settled-Retry and exact
reader-replacement cleanup probes. Its focused manual 178 JVM and six native passes are
recorded separately from the parent's failed review and pending independent acceptance in
the companion; they do not establish final #2334 acceptance.

### Earlier updated first-draw map retained at integration

The [paging proof and counted evidence](authoritative-reader-paging.md#native-and-deterministic-proof)
covers offline/held-newest demand, explicit retry and cold reopen through the production
negotiated destination and real database. It retains the 1000 ms product target, unchanged
renderer bound and slow control. The [approved current native mapping](authoritative-reader-paging.md#approved-native-acceptance-mapping-2447) replaces retired legacy span/projection checks; the legacy measurements below remain historical. The bounded
repair in #2437 adds production disk/repository/ViewModel reset-after-settled-Retry and exact
reader-replacement cleanup probes. Its focused manual 178 JVM and six native passes are
recorded separately from the parent's failed review and pending independent acceptance in
the companion; they do not establish final #2334 acceptance.
