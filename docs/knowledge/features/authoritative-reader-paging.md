# Authoritative saved-history reader paging (#2334)

The production [repository](caching-conversation-repository.md#older-authoritative-saved-pages-2334)
uses bounded saved pages before the existing daemon history coordinator. The full
[cache contract](conversation-cache-contract.md) retains storage, ownership and writer rules.

## Current DB and file acceptance scope — approved 2026-10-22

Juhana explicitly approved separating full-file latency from #2334. Normal channel opening
uses MigratingConversationCache and AuthoritativeRoomStorage to load roughly 200 display
contributions plus required parent/active facts. FileConversationCache still supports
conversation metadata/read positions and one-time authoritative snapshot import. The direct
36000-fact FileConversationCache fixture is a compatibility stress case; its frame time is
neither normal bounded DB opening latency nor an actual file-to-Room import measurement.

The 1000 ms product target and 2000 ms SwiftShader assertion remain binding for DB opening and
reader demand/retry/reopen. Both file compatibility methods retain exact raw facts, numeric
spelling, ownership, immutability, 36000 facts / 18000 islands / 18000 visible messages and exact
read/request assertions. Their positive timing bound is enabled only by the test-only
`requireFullFileLatencyBound=true` instrumentation argument and belongs to separate
[#2456](https://github.com/pyrycode/pyrycode-mobile/issues/2456), which is not a #2334 blocker.
The actual 3000 ms slow-read negative control now uses DB newest-window reads in all four
offline/held-newest first/reopen arms and must reject the same renderer bound. The retired
projection/API mapping remains as documented below; no unused dispatcher delay is claimed.

On 2026-10-23, the manual continuation of that measurement ticket removed the requirement
to meet its historical full-file target before documenting the benchmark. The optional
assertion remains available, and a miss remains a failed assertion in that run. Reporting
the result does not require a production optimisation. The Linux-only scheduling constraint
was also removed: record the actual host and renderer, and do not treat measurements from
different hosts or source revisions as a controlled optimisation comparison. Correctness
checks and the normal DB timing bounds above are unchanged. A real one-time file-to-Room
import includes a different transaction and must be measured separately if investigated;
neither the direct-file fixture nor a later bounded DB reopen measures that whole import.

This approved scope supersedes earlier positive full-file timing requirements in this
companion and the six issue contracts. Historical failures remain failures of their original
runs. Retained #2449 optimization code still requires independent correctness/security review
before merging; no further file-latency implementation is needed for DB acceptance. Every
child and the final parent retain independent complete configured JVM/UI/scripted gates,
actual documentation and reviewed merge/Done requirements.

The [last integrated run before the scope amendment](authoritative-reader-paging-early-evidence.md#last-integrated-run-before-the-scope-amendment)
retains the failed original file-bound measurements and source/capture identities.

## Approved-scope native validation

The [native benchmark receipts](authoritative-reader-native-benchmarks.md#approved-scope-native-validation)
retain the approved DB first-draw/slow-control results and exact file compatibility checks.

### Independent opt-in file benchmark

The [historical opt-in receipt](authoritative-reader-native-benchmarks.md#independent-opt-in-file-benchmark)
preserves the separate full-file timing failure and its original source identity.

### Current-source file compatibility measurement — 2026-10-23

The [current measurement](authoritative-reader-native-benchmarks.md#current-source-file-compatibility-measurement--2026-10-23)
records both file methods, all 16 compatibility cases, the active opt-in timing miss,
Mac readiness failures and the supported commands, with raw evidence identities.
The [independent review](authoritative-reader-native-benchmarks.md#independent-review-and-configured-acceptance--2026-10-23)
authenticates those receipts and records cached/skipped gate distinctions and full scripted acceptance.

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

The [early paging proof and manual receipts](authoritative-reader-paging-early-evidence.md#native-and-deterministic-proof)
retain the original test-first failures, committed-bound repair, JVM controls and native/runtime
identities verbatim. Current native mappings and subsequent acceptance receipts remain below.

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

The paging failure repeated expecteditemDecodes200/actual400 on offlineopening1 after offlineopening0 passed. Chronological phone records show each of the two explicit attempts decoding200, then a separate read_parent_closure after successful reader_page in the failing arm only. Child #2452 subsequently confirmed and repaired this publication race, with independent acceptance recorded below. These two historical count failures remain failures; the unchanged-tree pass is not a flaky waiver. Numeric #2451 stays limited to exact validated number encoding; positive file latency belongs to independent non-blocking #2456 under the approved scope above. Parent acceptance remains separate.

Numeric2451's additional real FileConversationCache→SavedAuthoritativeDestination/registry/coordinator/repository/ViewModel exact opaque-number cold-reopen probe ran on59f2079541 plus formatter-only7694aeb3af:1selected/executed/passed,0failures/errors/skips, four offline/held-newest first/reopen arms with exact raw values and no extra request at rest. Evidence logs/evidence/2451/manual-20261022/reopen-59f2, manifest0d7dac300d6aab6f12770f11a77080ac2603e7adaddd0145f4e073ad3164d46c. It supplements340green without claiming current native or independent acceptance. Every child and parent still requires its own independent complete configured Linux gates, actual documentation and reviewed merge/Done.

## Retry publication and baseline ownership (#2452)

A restored store and its committed baseline must publish as one observation boundary.
`applyAuthoritativeRestore` publishes the store before computing `persistedAuthoritative`,
under the `authoritativeThreads` monitor. Reading that map without the monitor allowed an
observer on another worker to see 400 restored facts against the previous 200-fact baseline.
It classified the extra facts as uncommitted and scheduled redundant parent closure: the
successful requested page decoded 200 facts, then closure decoded them again. The phone
sequence and controlled regression establish product duplicate work rather than cumulative
counter lifetime, an extra reader demand or a waived synchronization assertion.

The observer now reads the baseline on `processingDispatcher` under the same publication
monitor before deciding whether closure is needed. It releases the monitor before validation,
disk, suspension, writer or coordinator work. Existing historyWrites → store ordering and
reader/generation/cancellation, retirement and ownership fences remain binding. A concurrent
map alone does not make store publication and baseline computation atomic to readers.

`AuthoritativeReaderPagingTest.retryPublicationCannotExposeAnOlderBaselineToTheObserver`
uses real disk/repository/ViewModel and a two-worker dispatcher. A private replacement map
pauses its existing compute immediately after store publication, exposing the exact boundary
without a production test seam. Latches release in finally. The test retains failed demand,
settled Retry, explicit success, exact 400 facts, two attempts and no extra requests or demand
at rest through offline/held-newest openings and cold reopens. An unchanged-tree rerun alone
cannot cover this edge.

### Test-first and focused receipts

The earlier d87e failure and 3351 repeat above remain retained. Between them, unchanged-tree
paging rerun `ui-kjku5emj` on formatted `c0bdcd8ad2` selected/executed/passed **1/1/1**, zero
failures/errors/skips, with all four arms passing exact counts. Historical raw location:
`logs/evidence/2449/manual-20261022/paging-count-rerun-c0bd/`, manifest
`8e2a024cdbf3c1ff3e63a5935e14ea7e33ac6431c490b22145404804e1e3139e`.

Plan `6e28823be` preceded regression `54296992a3` (formatter-only `9d99e75a7f`) and repair
`76b1e89803`. Pre-repair **1 selected/executed, 0 passed, 1 failed, 0 errors/skips** recorded
`old_baseline=true`, 400 item reads/decodes, 200 ordinal reads, two evidence reads, zero
evidence decodes and 208 queries on offline opening 0. It failed the unchanged 200-decode
assertion. Raw XML/log/test/unchanged production/plan/formatter diff were retained at
`logs/evidence/2452/manual-20261022/red-5429/`, manifest
`bd9738ccb51eb19027b28d753247a71407d8e59d5c804f1c1f8d1804d0a22487`.

Repair `76b1e89803` passed **67 selected/executed/passed**, zero failures/errors/skips,
plus lint/build/format/native compilation. The race passed all four arms with
`old_baseline=false`, 200 payload decodes and six queries. Native `ui-9j7pnxza` used an
invalid Class.method selector and executed no product method (initializationError); its
`green-76b1` folder name is not a native pass. Manifest:
`90545df2556bf3c4e80d8b4f91fed3fa0bca831a408edcdec03802cbb3a7b020`.
Corrected unchanged-source Class#method invocation `ui-zfqyaha8` selected/executed/passed
**1/1/1**, zero failures/errors/skips. All four arms retained exact 400 rows, 200 item decodes,
200 ordinal reads, one evidence row, six queries, two attempts and offline zero/held-newest
one request with no automatic walk. Frames were 890/371/319/261 ms. Raw XML/phone/APKs/source/
plan/image receipt: `logs/evidence/2452/manual-20261022/native-selector-76b1/`, manifest
`a0d4f94056857bb39e9b6f77c56e9b36bf3508a1faec8df95ce77129e44b4d45`.

### Independent Linux acceptance and runtime identity

The [independent verdict](https://github.com/pyrycode/pyrycode-mobile/pull/2461#issuecomment-6335719043)
accepted source **`0fa1511b4aa32fcf60ac67b2bb14d266512abf8c`** after code, protocol and security
review, including retained parent changes. The authoritative stage record accepts that exact
candidate. The [repair plan](../../specs/architecture/2452-retry-baseline-publication.md)
records the original causal investigation and security constraints.

| Invocation | Selected/listed | Executed | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| JVM `jvm-ho1221sd` with build checks | 5286 | 5286 | 5286 | 0 | 0 | 0 |
| UI `ui-xqr5rfl2` initial run | 333 | 332 | 331 | 1 | 0 | 1 |
| UI named rerun `ui-mk4qdbbt` | 1 | 1 | 1 | 0 | 0 | 0 |
| Scripted `scripted-all-1zpuid03` | 24 | 24 | 24 | 0 | 0 | 0 |

The UI initial exit 1 remains failed. Its unrelated
`RecordedMobileSuiteTest#interactiveTurn_modelChange_roundTripsAndStaysPerConversation`
passed the dispatcher-selected same-tree rerun, accepted under known #2368 flake disposition.
`RenameDialogCaptureTest#renameAtFigmaViewport` remained skipped. All six saved-thread native
methods passed in the initial full UI suite, including the named database retry/reopen method;
no separate focused run is claimed for this candidate. Pre-verification, docs guard, script
checks (211 executed), build/lint/format and native compilation passed in the verifier's run.
macOS is outside this Linux ticket's scope.

Fresh JVM XML contains the named publication regression and its passing four-arm output:
`old_baseline=false`, **200 item reads/decodes, 200 ordinal reads, one evidence read,
zero evidence decodes, six queries**. Evidence decoding reused an existing JVM value; the
native fixture instead decoded its one evidence row. Native retry retained **400 exact rows,
two attempts, 200 item reads/decodes, 200 ordinal reads, one evidence read/decode, six queries**,
zero offline/one held-newest request and no additional work at rest. Frames in offline
open/reopen then held-newest open/reopen order were **94/97/107/66 ms**. DB opening frames
were **107/81/97/116 ms**, 200 item decodes and five queries each. Actual 3000 ms DB-read slow
control frames **3114/3092/3076/3083 ms** rejected the unchanged 2000 ms SwiftShader assertion;
the product target remains 1000 ms.

Both file compatibility methods passed all eight ordinary/fragmented offline/held-newest
first/reopen arms: **36000 facts / 18000 islands / 18000 visible rows**, exact raw/numeric/
owner/read assertions, offline one/online two full reads and zero retired-API reads.
`required=false followup=2456` preserves the approved DB/file separation and independent
opt-in benchmark above. Saved-history native tests use controlled transport, no external
daemon; scripted checks use zero real Claude turns.

Raw evidence was retained under
`$AGENTS_REPO_PATH/logs/evidence/2452/independent-0fa151-20261022/`:
`jvm-ho1221sd/TEST-de.pyryco.mobile.data.cache.AuthoritativeReaderPagingTest.xml`,
`ui-xqr5rfl2/0-TEST-pixel2Api33Atd_0-_app-.xml` and per-method phone logs,
`ui-mk4qdbbt/`, `scripted-all-1zpuid03/`, and `final-verifier/` gate logs/APKs/acceptance.
`SHA256SUMS-final-verifier` lists **1112 files**, SHA-256
`262d2acbc4218ab617fbca9318d635a9ad135681040aeb5fa26ea732be357775`.
Identities recorded before device execution:

- Application APK SHA-256: `f43495242759c3487730d3a66ca71af2601e649c1c222298078343da7c373991`.
- Instrumentation APK SHA-256: `01a75803642a63b9a892ed0dcf0df095f9c55e6ffa209100bb86fd043644e5c7`.
- Android 13/SwiftShader managed image: `5eb8ca4e8aecd9b09a6f76a82060255fe12b7e150136cc2bf7ae32cc67169d7e`.
- Scripted daemon source: `2c68b595440dd7e8e843af9cff0d9a708bcc81a7`; binary SHA-256:
  `d93c56662d0072e8aec9d1466bee3f6845f7cdc55247273f177b48b209bdb6ac`.

Historical manual raw bundles were unavailable on the verifier host; their manifest references
above are preserved rather than claimed rehashed. Current acceptance rests on fresh local
reports. Raw folders are subject to 30-day retention; these counts and identities preserve
the receipt after pruning. This accepts #2452 implementation and configured Linux checks.
Reviewed merge into `feature/2451` remains dispatcher-owned. It does not accept #2447, #2449,
\#2437, #2451 or final #2334; each retains independent acceptance, documentation and workflow.
Existing manual holds, claims, counters and approvals remain in force.

## Negotiated decorator state and retry contract (#2473)

A transparent `NegotiatedThread` decorator must forward `localHistory(conversationId)` as
the selected provider's exact flow. Inheriting the default Idle flow hides actual local-page
Loading and Retry even while facts and reader ownership still pass through correctly.
`TappingConversationRepository`, `InitialAttentionQueryGate.negotiated` and
`PassiveThreadProbe.negotiated` now preserve it, including their installed
`tapNegotiated=true` composition. Retry continues through the existing `older` action.
No collector, query, reader, lease or state conversion is needed to expose that contract.

`NegotiatedLocalHistoryDecoratorTest` covers the direct provider, each wrapper and the installed
chain. It checks exact flow identity, Loading → Retry → original older action → Loading → Idle,
selected host/conversation/support/connection/store and unchanged generation, with zero extra
observation/open/cancellation. A synthetic route establishes forwarding; the affected real
Room/repository/ViewModel paging, reader replacement, cancellation and reset tests retain
their separate ownership controls. This omission was independent of #2470's three synthetic
fixture regressions. The [plan](../../specs/architecture/2473-negotiated-local-history.md)
retains the causal boundary and security review.

### Causal red, repaired controls and failed integration

All paths in this section are relative to `$AGENTS_REPO_PATH/logs/evidence/` as retained on
2026-10-22. Raw captures have 30-day retention; paths describe where they were then.

Before any wrapper repair, source `956e50fd7954963670abc578dbe25907ba9ecd36` freshly executed
five methods: **1 direct-provider pass, 4 intended failures, 0 errors/skips**. Each decorator
case stopped at actual Loading being hidden as Idle; later Retry assertions were not reached
in those failed cases. Formatter-only regression layout matches `ad102b99a`. Original XML,
unchanged wrapper snapshots and preparation evidence remain in
`2473/manual-20261022/decorators-red-956e/`, 12 manifest entries, SHA-256
`9cc1c675dead8ce4384782bb31ac5aa95d33e06cad280a03838da7dc8f99c9d8`.

Repaired `a1804c61ddc77687c5331ebcd023da9d95a9f093` freshly executed/passed **41/41**, zero
failures/errors/skips: decorator 5, passive observation 3, initial attention 11 and reader
paging 22. Formatting and SpotlessCheck passed. Raw source/XML/logs:
`2473/manual-20261022/decorators-green-a180/`, 17 entries, manifest
`d431f9d06713d2dab843883693673f2ce99962c3428cbcce5f357dde487e3e46`.
Its reused APKs are historical; no native method executed in that job.

The [first independent verdict](https://github.com/pyrycode/pyrycode-mobile/pull/2474#issuecomment-6343784125)
rejected `1ef86c2223d44013fd146ced40942649913f723f`. Script checks passed 214/214 and fresh JVM
`jvm-56p4ulqi` passed **5310/5310**, zero failures/errors/skips. Full UI `ui-nbzo2_kh` listed
334, executed 333, passed 332, failed 1, skipped 1, errors 0. All seven SavedThread methods
passed, but recorded Agent restart failed with `NoSuchElementException` in placement checks.
Same-tree repeat `ui-b9400df3` failed **1/1**; merged-main
`99aab2c925fdea78ffd204c7b363c2a37dea1674` comparison `ui-s2xougn4` passed **1/1**, both with
zero errors/skips. Scripted-all did not run. This classified the integrated stack as regressing
against main without isolating the cause to the three forwarders.

The original failure and comparisons survive in
`2473/independent-1ef8-20261022/operator-final-failure-20261022T145645Z/`, 1800 manifest entries,
SHA-256 `73b2389de1614596d6a26a04915403e3009e3c736b4b155be0c8c52e4912484f`.
PR application/test APK hashes were
`6bed26813c48fea169637680df9ad4332dc91e52df19396ae37fae9249e1904f` /
`024a1c53f5f28a0f672b92334f3ef88b500b7fa7d033809b71d0f2a3474f2470`.
Source snapshots, original PR XML/phone logs, comparison APK/runtime identities and managed
image are retained. The main comparison's final standalone raw XML and phone log were missed
before cleanup: only normalized stdout XML, full stderr and the explicit limitation receipt
survive for that final comparison. Later green evidence does not replace those missing files.

### Partial passive receipts and strict placement readiness

Parent #2470 corrected required-ID readiness, merged ordinarily as
`22a0da1dd1bbb17f8715a9d2f891401b30fbf041` from repair
`247074d70d64b44ee7be41681380d39a9c6becce`. Every baseline-family, before-anchor and optional
recovered-send ID must be present in the current admitted passive screen receipt before
strict lookups. A missing fact leaves the original 120000 ms wait unready. Once present,
duplicate/stable-field/full-content/family/send/epoch/placement checks still run, and the
same-receipt identity check rejects retired generations. Global-store facts cannot fill a
partial screen receipt. This changes readiness, with no production or timeout change.

On exact integrated `22a0`, native `ui-n1r1dqwk` executed/passed the original recorded Agent
method **1/1**, zero failures/errors/skips. Phone evidence first lacked fact 85, rejected
retired generation 0 after reset, then accepted the identical current generation 1 receipt
with 23 facts/version 96/checkpoint 96 and original placement indexes 4/8/1, including final
restart/settlement. Formatting passed; no JVM methods executed. Raw source/XML/phone/image:
`2470/manual-20261022/readiness-integrated-22a0/`, 32 entries, manifest
`4278638a6242a7c5f046388c6d5eb042787fbd02a286d850bbfeb9e09c283a7e`.
Its product APK was reused: SHA-256
`633eab16b9ac41926bbfa2c6b027e01bc7373c134d39503ff38423855e10d3b3`,
mtime 10:14:06.218391Z; test APK
`2b789bcc9ab25695d2faca70370f0b835c45af6629434d125f48eb4ecaf16521`,
mtime 15:07:26.487407Z. These identities belong to that focused run.

### Independent Linux acceptance on the integrated candidate

The [accepted verifier verdict](https://github.com/pyrycode/pyrycode-mobile/pull/2474#issuecomment-6345107538)
and authoritative stage run
`66d85823fca3674377ab6f6b4f90674bfe2c25b3d56ef8a8b89d35133550ed09`
accept **`688f93fcbdbf591d785a135af1e09aa07c48947a`**, PR #2474, base `feature/2470`, with
merged main `99aab2c925fdea78ffd204c7b363c2a37dea1674`. Review found no further issue in the
bounded correction and retained the earlier broader review; it did not repeat that broader
review. Security boundaries remain unchanged: no production, query, reader, collector,
persistence, capability, transport or dependency addition.

| Configured command | Invocation and result | Failed/errors/skipped |
| --- | --- | --- |
| `python3 scripts/pre-verify.py` | Passed, exit 0 | — |
| `scripts/docs-guard.sh` | Passed, exit 0 | — |
| `python3 -m unittest discover -s scripts` | 214 executed/passed, exit 0 | 0/0/0 |
| `python3 scripts/jvm-test-gate.py --with-build-checks` | `jvm-3z4ei9jw`: 5310 passing cached results; **0 fresh executions**, exit 0 | 0/0/0 |
| `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui` | `ui-2xkw_1tg`: 334 listed, **333 executed/passed**, exit 0 | 0/0/1 |
| `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py scripted-all` | `scripted-all-5vouhl2f`: **24 executed/passed**, exit 0 | 0/0/0 |

The combined gate completed lint, Spotless, assembly and native compilation. JVM
`invocation.json` explicitly records `FROM-CACHE`, `reusedGradleResult=true`,
`executedInReport=5310`, `executedThisInvocation=0`. Its XML contains all five passing
decorator methods. This configured reuse is accepted; the earlier fresh 5310 execution
retains its `1ef86c` provenance. The summary report's 5310 count describes report results,
not fresh execution on `688f93`.

Fresh full UI XML contains all seven passing `SavedThreadFirstDrawDeviceTest` methods:

- `databaseSavedThreads_upwardDemandAndRetry_offlineAndHeldNewest_coldReopen`
- `databaseSavedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_coldOpenAndReopen`
- `slowRestore_negativeControlRejectsTheSameFirstDrawBound`
- `slowOlderDatabaseRead_negativeControlRejectsTheSameRetryFrameBound`
- `authoritativeFileCompatibility_preservesExactFacts_offlineAndHeldNewest_firstOpenAndReopen`
- `authoritativeFileCompatibility_exposesNoRetiredReadApis_firstOpenAndReopen`
- `heldCpuPipeCancelsAndClosesItsDescriptor`

The recorded Agent `interactiveTurn_earlierSessionAgent_survivesRestartAndSettlementReconnect`
passed in that full UI run. Scripted XML contains the passing
`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_earlierSessionAgent_survivesRestartAndSettlementReconnect`.
No separate focused run is claimed for this candidate. The sole UI skip was
`RenameDialogCaptureTest.renameAtFigmaViewport`; its screenshot was not verified.

DB opening/demand/Retry/cold-reopen keep the **1000 ms product target / 2000 ms SwiftShader
assertion** and actual consumed-DB **3000 ms** negative controls. Retry-frame phone timings
for offline first/reopen then held-newest first/reopen were **181/189/203/169 ms**. The slow
older-read control measured actual successful reads **3042/3042/3053/3043 ms**, with retry
frames **3267/3239/3286/3299 ms**, rejecting the same bound. Both file compatibility methods
retain exact **36000 facts / 18000 islands / 18000 visible messages**, raw numeric/ownership/
immutability/read/request assertions and zero retired reads. Phone logs mark
`required=false followup=2456 openings=8`; positive file latency remains independent,
non-blocking [#2456](https://github.com/pyrycode/pyrycode-mobile/issues/2456).

### Accepted source, binary and capture provenance

Final archive: `2473/independent-688f-20261022/verifier-final-20261022T155224Z/`.
The verifier authenticated all **2140** manifest entries; `SHA256SUMS` SHA-256:
`d7b8344df7bb741fa4c71248fedbd31897b9eceb9b9e6951f2ec2b071c3ac7d4`.
It retains gate logs/pass metadata, `progress-receipt.json`, candidate diffs, 12 matching
Kotlin snapshots, original and normalized XML, per-method phone logs, APKs and image/runtime
receipts. Source stayed at `688f93` throughout capture. Relevant reports are under
`dispatcher-tests/{jvm-3z4ei9jw,ui-2xkw_1tg,scripted-all-5vouhl2f}/dispatcher.xml`;
JVM has per-class XML and invocation metadata; UI has per-method logcat and managed-image
metadata. Copied older gate logs retain their mtimes and source binding and are historical.

- Application APK: `6bed26813c48fea169637680df9ad4332dc91e52df19396ae37fae9249e1904f`, mtime 15:24:14.394907Z.
- Test APK: `48779082677c5515c931959c4965216ac21aad4d243dcd943fcb4cfb409dd162`, mtime 15:25:11.144985Z.
- Android 13/SwiftShader image: `5eb8ca4e8aecd9b09a6f76a82060255fe12b7e150136cc2bf7ae32cc67169d7e`.
- Host: Linux x86_64, kernel 7.0.0-38-generic; OpenJDK 21.0.12.1.

SavedThread fixtures use production Room/cache/repository/ViewModel with controlled transport.
Scripted checks use an isolated daemon/relay and fakeclaude, zero real Claude turns. Harness
receipts explicitly mark unused alternate daemon logs unavailable; no current external-provider
or live-Claude acceptance is claimed. Historical missing baseline raw XML/phone files remain
unavailable. macOS is outside this Linux ticket's verification scope.

This records #2473's accepted checks and actual documentation. Normal reviewed merge into
`feature/2470`, issue closure and central Done remain dispatcher-owned. It accepts no ancestor
and releases no ancestor hold, error or blocker. The user-approved acceptance update in
[PR #2474](https://github.com/pyrycode/pyrycode-mobile/pull/2474) supersedes earlier repeated
per-ancestor full-suite/docs requirements: focused regressions and independent repair review
remain, followed by ordinary stack integration and one complete acceptance/documentation pass
on final integrated #2334. Earlier receipts and failed results above remain historical.

## Prior first-draw map retained during reviewed storage retirement

The [earlier first-draw maps](authoritative-reader-paging-early-evidence.md#prior-first-draw-map-retained-during-reviewed-storage-retirement)
remain verbatim as history, with the current native mapping and receipts above governing acceptance.

## Final integrated #2334 receipt

The user-approved consolidated plan integrates the reviewed repairs at source
`688f93fcbdbf591d785a135af1e09aa07c48947a`. The accepted run above supplies complete
unchanged-code acceptance; later changes are documentation only. All eight repair PRs
are merged and issues closed/Done. The [integration and scoped review ledger](authoritative-reader-paging-final-acceptance.md)
records the final acceptance basis.
