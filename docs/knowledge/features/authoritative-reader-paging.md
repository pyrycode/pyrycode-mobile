# Authoritative saved-history reader paging (#2334)

The production [repository](caching-conversation-repository.md#older-authoritative-saved-pages-2334)
uses bounded saved pages before the existing daemon history coordinator. The full
[cache contract](conversation-cache-contract.md) retains storage, ownership and writer rules.

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
