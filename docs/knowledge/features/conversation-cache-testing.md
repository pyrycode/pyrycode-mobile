# Conversation cache — Testing

Split from [Conversation cache](conversation-cache.md); this topic retains the section anchors.

## Testing

### Authoritative import and inert legacy files (#2385)

`MigratingConversationCacheTest.ownershipInvariant_legacyFilesStayInertAcrossReopens`
seeds both file families for two case-distinct hosts sharing conversation ids, unknown-owner
hashed legacy files and different pre-existing legacy Room rows. Two fresh cache/database
reopens must preserve exact legacy Room content and source bytes, with no legacy import
bookkeeping. Empty legacy pages are valid empty pages, not null. The same probe checks exact
owner-scoped authoritative content/certification, metadata and supported read state.
`idempotenceInvariant_committedImportIsNeverRepeatedOrOverwritten` additionally keeps a newer
Room save across restart despite its older authoritative source.

Keep the authoritative `classificationInvariant_*` probes for invalid, unsupported and
wrong-owner sources, retryable access/IO failures, inaccessible parents across restart,
deferred-import write refusal and cancelled read/write result delivery after commit.
`atomicityInvariant_interruptedImportKeepsSourceAndRetries` checks rollback/retry and
pre-commit cancellation; `certificationInvariant_importAndPagesKeepSavedCheckpoint` retains
exact page certification. Conversation/host `retirementInvariant_*` probes keep leftover-file
and held-work fences. Check the other host's legacy source bytes directly: expecting those
files to import would reintroduce the retired behavior. Pending sends remain independent.
`ConversationCacheFactoryTest` uses authoritative snapshots to prove production no-backup
custody and saved-pairing admission, including cancellation while waiting for the cache mutex.

The [retained Linux manifest and sibling XML/raw output](../../../app/src/test/resources/verification/legacy-import-2385/results.json)
record **89 executed/passed, 0 failed/errors/skipped**, with each class selected separately:
`MigratingConversationCacheTest` 13, `ConversationCacheFactoryTest` 3,
`OwnedLegacyMigrationTest` 1, `LegacyRoomStorageTest` 18, `AuthoritativeRoomStorageTest` 14
and `FileConversationCacheThreadTest` 40. The cache/factory runs used the recorded pre-format
source hashes; the other four used implementation revision
`4854376725a204030779deffd8865a2eeb976b3f`. Formatting left production hashes unchanged.
Historical red evidence at `f7b5aee1484fd4d2feb47ce83b6a2b3318c23b24` remains
**1 executed, 0 passed, 1 failed, 0 errors/skips**, failing on unexpected legacy import bookkeeping.

The [verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2446#issuecomment-6330757434)
at `7c60f58390f9e66ee102788bda8b088bdce17675` records complete Linux JVM/build evidence:
**5338 executed/passed, 0 failed/errors/skipped**. All 13 cache methods and both named
pairing-admission methods executed and passed; the six classes above account for 89 passes
in this full-suite run as well. Configured UI evidence records **327 executed/passed,
0 failed/errors, 1 skipped** (`renameAtFigmaViewport`); scripted-all records **24 executed/passed,
0 failed/errors/skipped**. These dispatcher results are separate from the focused builder runs.
Native production-opener upgrade proof belongs to #2386 and final integration to #2364;
macOS is outside this Linux ticket's verification scope. Documentation ran no tests.

### Indexed legacy disk probes (#2162)

`LegacyRoomStorageTest` uses Robolectric with a real on-disk Room database and fresh database/
storage instances between persistence assertions. An in-memory database or a same-instance
read cannot establish restart fidelity. Its 18 probes cover all six persisted row kinds and
fields, exact owner isolation, equal/misleading timestamp order, stale continuations, unsigned
extremes, legacy fragment bindings, exclusions/uniqueness, 100000-row trims with and without
coverage, optional-history isolation, scoped removal and transaction rollback after failure or
cancellation. Metadata-only reads assert zero row payload reads/decodes.

Bounded probes measure actual row payload reads/decodes, ordinal-only probes and evidence
reads/decodes at the DAO/codec boundaries. Equal returned row counts alone do not establish
bounded work. Retain these independent cases:

- `boundedEvidenceInvariant_fragmentedFixtureDoesNotDecodeOutsideWindow` keeps the unchanged
  `fragmentedHistoryFixture`: 18000 rows, 36000 durable entries and 18000 spans. Newest-200 reads/
  decodes exactly 200 row payloads, probes one older ordinal and reads/decodes 1002 evidence cells.
  Adding older held rows does not increase those page counts. Exhaustive backward traversal
  visits all 18000 rows exactly once over 90 pages.
- `boundedEvidenceInvariant_olderCursorsAndWalksDoNotGrowContinuousSpanPages` grows older
  cursor/walk evidence inside one continuous span by 36000 cells while keeping the selected
  newest 200 rows unchanged. Counts remain 200 row reads/decodes, one ordinal probe, 609 evidence
  reads and 608 evidence decodes. Sparse fragmented spans alone cannot catch this full-span scan.
- `boundedEvidenceInvariant_sharedGapBoundaryKeepsBothSides` reopens disk and traverses one-row
  newest/before pages through spans `[1,1]`, `[3,3]`, `[5,5]` and gaps `[1,3]`, `[3,5]`. The
  middle page must retain both touching gaps, both retry walks and nearest relevant cursors while
  excluding non-intersecting intervals. Check `gapCursors` directly too: an independent page-edge
  lookup can mask a missing sibling-query result.
- `boundedEvidenceInvariant_olderEvidenceDoesNotGrowSharedBoundaryPages` and
  `boundedEvidenceInvariant_sharedGapBoundaryPreservesUnsignedExtremes` shift the same boundary
  case and exercise maximum-unsigned positions. Adding 4000 unrelated older evidence cells
  leaves the middle page at one row read/decode, one ordinal-only probe, 16 evidence reads and
  13 evidence decodes.
- `committedEvidenceInvariant_parseableInvalidSpansAndClaimsAreIsolated` and
  `committedEvidenceInvariant_parseableInvalidFragmentBindingsAreIsolated` corrupt selected
  evidence with parseable but invalid cells. Assert that rows remain readable, optional evidence/
  saved cursor/completeness disappear and storage is unchanged. Malformed JSON alone does not
  exercise projection validation.

The [builder's focused report](https://github.com/pyrycode/pyrycode-mobile/pull/2164) records
`testDebugUnitTest -PfullRetentionTests=true` selecting `LegacyRoomStorageTest`,
`LegacyRowCodecTest`, `FileConversationCacheThreadTest`, `DecodedThreadRestoreTest`,
`HistoryHashCompatibilityTest`, `UnsignedHistoryCacheTest` and `HistoryDurabilityTest`:
102 executed/passed, 0 failed/errors, 0 skipped, including 18 Room tests. These retain canonical
file-codec/proof compatibility, unsigned/history durability and existing restore/timing/exact-row
regressions; Room probes supplement them. Keep full-retention admission rather than shrinking
the shared fixture or replacing full-cap disk tests with count-only assertions.

The [final verifier report](https://github.com/pyrycode/pyrycode-mobile/pull/2164#issuecomment-6179402443)
for `9f939dd05333da04c104c71522c3baabf02fed72` (2026-10-14) records the fresh unit XML from
`./gradlew check`: 5610 executed/passed, 0 failed/errors, 0 skipped; the seven named classes had
100 executed/passed, 0 failed/errors, 0 skipped, including all 18 Room methods. Each of the three
shared-boundary methods above executed once and passed, with the unchanged 16-read/13-decode
counts in report output. These dispatcher counts differ from the separate focused builder run.
The dispatcher gate report also records UI: 261 executed/passed, 0 failed, 1 skipped
(`renameAtFigmaViewport`); scripted-all: 22 executed/passed, 0 failed, 0 skipped. The skipped
capture supplies no visual evidence. This unbound data-layer work needs no real-Claude scenario.
Counts here preserve the reports' evidence; removed gate-worktree build paths are not durable
artifact links.

Import/application binding belongs to #2161, authoritative Room storage to #2163 and reader
activation/owned partial writes to #2068. These disk probes do not establish application restore
latency or production bounded reading before those consumers are activated.

### Held-retry atomic rollback and final flush (#2263)

`AuthoritativeOwnedUpsertTest.atomicInvariant_heldFailureAndFinalFlushKeepNewestFacts`
is active on Linux. A fresh `MigratingConversationCache` reader can need Room transaction
admission even after import has settled: `importFamily` still uses a transaction to inspect it.
The commit hook deliberately runs inside the writer transaction. Holding retry there can block
the independent reader, so preserve #2256's retry hold in the wrapper before Room admission.

Keep the controlled sequence: commit the seed record, hold the first write after mutation,
admit newer facts while it is held, then release it to fail. `rolledBack.await()` acknowledges
the failed operation's actual rollback; `retryEntered.await()` confirms retry reached the
pre-admission hold. Assert `allowRetry.isCompleted` is false both before and after
`assertEquals(prior, fresh())`. Here `fresh()` opens and closes a separate disk-backed Room
connection over the same database file. A writer-connection read or a read after retry release
would miss the independent-reader invariant.

Only after that exact prior-record assertion may retry be released and observer cancellation/
final flush joined. Another newly opened reader must equal the exact newest record: revised
item 1 and new item 3, untouched item 2, and the prior checkpoint, coverage and incarnation.
Keep both whole-record assertions and the probe enabled when reconciling the proposed
tracker-linked ignore from PR #2264. This wrapper uses `FakeConversationRepository` with no
negotiated provider; its disk-cache evidence does not establish production activation.

The [retained Linux manifest and sibling raw XML](../../../app/src/test/resources/verification/held-retry-2263/results.json)
record commands, revisions, source/report hashes and the reader-ordering proof. Historical
baseline `03e455f7bd9e667a18dfe57a41ed1b897de016c5` executed **1, 0 passed, 1 failed**;
the earlier #2245 class run executed **17, 16 passed, 1 failed**, both with **0 errors/skips**.
These remain failed evidence. With the repaired test source committed at
`eca2b2956b17bcb9bd12883844fb13afb4ddf2f5`, separate focused method and class runs using
`testDebugUnitTest -PfullRetentionTests=true` executed/passed **1/1** and **17/17**,
respectively, with **0 failures/errors/skips**. Both green XML reports include the named method.

The [verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2294#issuecomment-6254399073)
at `1d510460bf81bacf6aaf2b9730670192a3e3df3a` records the complete Linux JVM gate:
**5994 executed/passed, 0 failures/errors/skips**, including all 17 class methods and this
named probe. This full-suite result is separate from the focused runs. macOS was outside this
ticket's verification scope. See the [probe repair plan](../../specs/architecture/2263-held-retry-atomic-probe.md)
and [wrapper scheduling tests](caching-conversation-repository-testing.md).

### Full-cap retention certification proof (#2439)

`AuthoritativeOwnedUpsertTest.retentionInvariant_trimClearsCertification` seeds a fresh,
disk-backed Room database with all 100000 retained rows. Overflow must retain exactly the
newest rows and revisions while clearing durable checkpoint/coverage. A later revision and
metadata-only catch-up/page completion must not reinstall that invalid certificate after
reopen. A fresh conservative baseline must still certify a fully durable window. Keep all
four independent full-record reads, cancellation/final-flush joins and write-work assertions;
smaller fixtures, same-instance reads or count-only assertions would weaken this proof.

Bounded writes do not imply a short full-cap read/assertion phase. The original-deadline
diagnostic measured four independent reads/assertions at 10.357, 10.674, 10.816 and 10.467
seconds, about 86% of the 49.146-second body. Seeding took 5.577 seconds, first observation
114 ms and final flushes 4–41 ms. Every boundary completed. The unchanged method passed in
51.312 seconds and the instrumented method in 50.841 seconds. These measurements justify
only this method's finite two-minute `runTest` allowance, below the 180-second outer watchdog.
Measure read work separately from observer cancellation and writer flush before extending
a deadline; neither `UncompletedCoroutinesError` alone nor these measurements establishes
deadlock or host starvation. Static phase codes use monotonic wall time, not scheduler time.

The [retained Linux evidence and sibling raw XML/output](../../../app/src/test/resources/verification/retention-2439/evidence.txt)
preserve the failed main run at `d2130e229e5ae7382cf7f6cebe790db6ae8574d5`:
**1 executed, 0 passed, 1 failed, 0 skipped**, 68.685 seconds, after all five write checkpoints.
The original gate path was overwritten; the committed failed XML and gate excerpt came from
the retained #2373 verifier bundle. Keep those failures distinct from subsequent passes.
Separate repaired focused method/class runs with `-PfullRetentionTests=true` executed/passed
**1/1** and **17/17**, respectively, with **0 failures/errors/skips**. The method took
51.529 seconds alone and 43.871 seconds in the class. The class source revision was
`5643be8abb991820b06fbf80aa1b70e87d1b10c9`; the evidence records both runs' source hashes.

The [verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2443#issuecomment-6328796989)
for candidate `75afa8d5a2c55cef6f46b1d5059938a1ba007404` confirms complete Linux JVM
acceptance: `python3 scripts/jvm-test-gate.py --with-build-checks`, invocation `jvm-dq3to5jr`,
**6447 executed/passed, 0 failures/errors/skips**. All **17** affected-class methods passed;
the exact retention method passed in **45.861 seconds**. This complete-gate result supersedes
the builder's pending-dispatcher note. macOS was outside this ticket's verification scope;
no device or real-Claude acceptance is required for this Room-backed proof.

When reconciling #2334/PR #2372, preserve this allowance, diagnostics and every assertion in
its overlapping method hunk. Its broader paging feature and #2437/PR #2438's Retry-generation
repair are independent of this completion repair. See the
[repair plan](../../specs/architecture/2439-retention-certification-completion.md).

### Owned coverage snapshot acceptance (#2291)

`OwnedLegacyCoverageConcurrencyTest` keeps real disk-backed Room while distinguishing history
read, publication, writer acceptance, row commit, evidence failure and cancellation. A
`runCurrent()` drain does not acknowledge external Room I/O completion; collector delivery precedes
`ThreadWriter.accept`. The local `Source.afterEmission` callback runs after `emit` returns from
the repository collector, acknowledging acceptance of that exact snapshot. `first()` aborts
emission before the callback and cannot supply that acknowledgement. See
[test scheduling guidance](development-verification-test-scheduling.md#test-scheduling-and-harnesses)
and the [owned coverage contract](conversation-cache-legacy-mutations.md#owned-coverage-and-received-evidence-2254).

- `intentInvariant_rowFailureKeepsRetryableWork` holds the first nonempty snapshot's real
  history read and proves a scheduler drain leaves acceptance incomplete. After release and
  explicit acceptance, the failed row transaction must retain the exact pending key and leave
  disk rows empty. Retry uses an empty live capture, so resupplying the row cannot hide a lost
  intent. Success retires that intent and a fresh database/cache reader returns exactly row 1.
  Acceptance acknowledgement does not isolate the scheduled 100 ms writer from
  `writeHistoryPosition`. Real Room I/O can suspend the explicit save while
  `runTest` advances the shared clock, allowing both paths to reach the same
  failure hook. The probe now gives its writer a separately driven
  `TestCoroutineScheduler`. While the history transaction is held, advancing only
  the main clock must leave writer time at zero and produce exactly one failed
  attempt. After rollback, assert the exact retained intent and empty disk,
  retry from an empty live capture, then explicitly release the writer timer.
  Assert intent pruning, no additional failed attempt and exact row 1 through a
  fresh reader. Cleanup releases all barriers and joins saves/readers before
  Room closes; keep the post-emission acknowledgement as well as clock isolation.
- `revisionInvariant_rowSuccessSurvivesEvidenceFailureAndCancellation` retains both branches:
  committed rows survive evidence failure and cancellation, and the cancelled save cannot
  finish while its non-cancellable commit is deliberately held. Final one-row publication is
  held inside the collector to prove delivery has not acknowledged acceptance. Release it and
  await the final snapshot's post-emission callback before cancellation/final flush. After
  stale restore release, a fresh reader must contain exactly row 1, with row 2 removed and
  matching proof keys. Every exit releases holds and completes saves/readers before Room closes.

The [retained manifest and sibling raw output/compressed XML](../../../app/src/test/resources/verification/owned-acceptance-2291/results.json)
record Linux commands, source revisions and hashes. Controlled unrepaired ordering at
`e5a22b25243caa8debec1a81d11532086d357fef` executed **2, 0 passed, 2 failed, 0 errors/skips**:
the intent map was empty and the final disk snapshot retained row 2. Both controlled failures
establish a shared missing-acceptance boundary in the probes; no production defect was shown.
The cold red console has one explicitly marked truncated compiler-warning chunk; complete
failure XML and test-result output remain intact. At repaired source
`0ce36236b2ee3bd507705922ef6e38517781778b`, the exact pair passed **2/2** and the affected
class passed **9/9**, each with **0 failures/errors/skips** and complete captures. These are
separate focused runs, preserving exact persistence assertions and both revision branches.

The #2272 recovery's [retained index and raw XML](../../../app/src/test/resources/markdown-2272/recovery-evidence.txt)
distinguish this timer overlap from #2291's earlier acceptance ordering defect.
Controlled red at `0f33e4062` executed **1, 0 passed, 1 failed, 0 errors/skips**:
the assertion expected one failed attempt but observed two, with transaction
origins `history` at virtual time 100 ms and `writer` at 201 ms. Unchanged baseline
method/class selections passed **1/1** and **9/9**; those retries did not clear the
historical failure. Repaired source-equivalent `86dc2f098698297ef9672630bdc73b6b4ec331d2`
passed the exact method **1/1** and class **9/9**, each with **0 failures/errors/skips**.
The index preserves precommit source hashes and identifies the method run before
formatting. Linux identity was Ubuntu 24.04.5 x86_64, JDK 21, Robolectric API 33.
The fresh worker independently used `-Xmx4g` (4294967296 bytes); Gradle used
`-Xmx4096m`. A Gradle heap increase alone cannot repair test-executor exhaustion.

The prior partial #2272 gate at `ea1061b4e43f` remains failed: **5740 executed,
5739 passed, 1 failed, 1 skipped**, plus separate lint and test-worker heap
exhaustion. Accepted complete Linux verification at
`07566472b2eac2574ae4f0e68eed6ddaa5724d67` then executed/passed **6086,
0 failures/errors/skips**, including `intentInvariant_rowFailureKeepsRetryableWork`.
That is full-suite evidence, separate from the focused recovery selections. See
[the accepted run identity, gate counts and retained bundle](markdown-text.md#testing)
for Android UI/scripted evidence and its one unrelated UI skip. No production
cache defect was established; exact rollback, retained-intent and fresh-reader
assertions stay in place. macOS was outside this Linux ticket's scope.

Historical failures remain failures. #2278's retained stderr classifies both methods as
`AssertionError`; its original assertion details remain unavailable beyond the full-log pointer
on build-fractal-2. The same-tree pair rerun passed **2/2, 0 failures/errors/skips**. #2279's
original full log was recovered during verification at
`/work/Projects/pyrycode-mobile-agents/logs/verifier-gate_#2279_4.log`, SHA-256
`8a288b192e88dc6545fb6e898b9c3af832d6a50eda7fe22ff8d967b4932f2082`, matching its retained
pointer. Its revision assertion expected only row 1 but found rows 1 and 2, matching the
controlled reproduction. That historical class executed **9, 8 passed, 1 failed, 0 errors/skips**;
its separate exact-method retry passed **1/1, 0 failures/errors/skips**. This recovery supersedes
the builder's earlier uncertainty about #2279, without identifying #2278's assertions. The
independent [gap-subtraction timeout](#received-gap-subtraction-runtime-2269) has a different cause.

The [verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2296#issuecomment-6254914512)
at `994f7ead2aa1fed18f54003edc8fc6f63ed2cb1e` records the complete configured Linux JVM gate,
`python3 scripts/jvm-test-gate.py --with-build-checks`: **5994 executed/passed, 0 failures/errors/skips**.
Fresh gate XML includes both named methods once, passed, and all **9/9** class methods passed.
The report was retained at `/work/Projects/pyrycode-mobile-agents/logs/verifier-gate_#2291_4.log`
(SHA-256 `377da25aaf3091902f59b66e991e20cf664ee5c7d0dd3c3583ef7dad20ac2aed`), distinct from
the focused captures. Configured UI evidence records **334 executed/passed, 0 failed/errors,
1 skipped** (`renameAtFigmaViewport`, no visual evidence); scripted-all records **22 executed/passed,
0 failures/errors/skips**. macOS was outside this Linux ticket's scope; no real-Claude scenario
was required. See the [diagnosis plan](../../specs/architecture/2291-owned-coverage-probe-acceptance.md).

### Received-gap subtraction runtime (#2269)

`OwnedLegacyCoverageTest.anchorInvariant_subtractingProducerKeepsValidReceivedGapWalk`
combines change/removal cases at 20 and 18000 rows under one `runTest` deadline. Its
24-statement localized mutation counters precede substantial real-disk restore, repeated-save
and reopened-reader verification work. Equal bounded SQL work does not bound the whole fixture's
runtime. Keep both sizes, real disk-backed Room and production repository integration, exact
rows/spans/gaps/walks/cursors, withdrawn proofs, conservative unknown/start state and unrelated
evidence equality. The [owned coverage contract](conversation-cache-legacy-mutations.md#owned-coverage-and-received-evidence-2254)
and invalid-anchor companion control remain unchanged.

Content-free monotonic phase markers sit outside the measured SQL window, with an explicit final
database close before each `complete` marker. The original-deadline diagnostic completed all four
cases and closes at 52.250 seconds of body time: large change/removal cases took 28.201/22.245
seconds, while their measured mutations took only 423/285 milliseconds. A repaired run then crossed
the former minute during the final 18000-row removal `verify_final_reopen` phase
(57.346–60.703 seconds), closed and completed. This measured aggregate cost justifies the bounded
`runTest(timeout = 2.minutes)` on this method, roughly twice observed complete runtime. Preserve
the fixture and assertions when revisiting this budget.

The historical candidate `01c9fccc87efaace3cbff97c5026df4550d49f71` and base
`9281e2e5926ead8d22e5de5961183de0e38ffa6c` captures each record **1 executed, 0 passed,
1 failed, 0 errors/skips** with `UncompletedCoroutinesError` after the last mutation counter.
They locate the remaining work in the final removal case but cannot identify its precise
interrupted call. Fresh completion demonstrates insufficient aggregate runtime headroom;
no suspended-coroutine or Room defect was demonstrated. Green retries do not erase those failures.

The [retained Linux manifest and sibling compressed XML](../../../app/src/test/resources/verification/gap-subtraction-2269/results.json)
preserve commands, revisions, hashes, counts and phase output. The unchanged baseline at
`6e292874310dde62d1f8461f805dda9224ad9195` passed **1/1** in 54.708 seconds; the diagnostic
at `1faf301baf71e781739a9d4a2fa8cc58308f056d` passed **1/1** in 53.973 seconds. At repaired
revision `2bc42d88b54286d18b4ccb056f79429fabb03db5`, the exact method passed **1/1** in
63.138 seconds and the class passed **16/16** in 116.183 seconds (named method 49.890 seconds,
invalid-anchor control passed). Every green selection had **0 failures/errors/skips**.

The [verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2293#issuecomment-6253996404)
at `c53b4b0e008bc4826ffac022c8a10850749f6015` confirms the complete Linux JVM gate:
**5992 executed/passed, 0 failures/errors/skips**. Its class XML records **16/16** passed in
112.145 seconds, including this method in 52.268 seconds and the invalid-anchor control.
This is full-suite evidence, separate from the focused runs. macOS was outside this ticket's
verification scope; no device or real-Claude acceptance was required. See the
[diagnosis plan](../../specs/architecture/2269-reliable-gap-subtraction-probe.md).

### Cancelled committed import cleanup (#2260)

`MigratingConversationCacheTest.classificationInvariant_cancelledCommittedImportCannotReadmitUnobservedWrites`
observes the Room commit before cancelling its paused reader. Since #2385, preserve both
authoritative read/write import cases, the cancelled-child assertion,
`import_unobserved` rejection, exact imported content and fresh-instance restart/no-replacement
checks. Letting the reader observe the committed result before cancellation would miss the fence
these assertions exercise.

Room's IO cancellation continuation can arrive after `TestCoroutineScheduler.runCurrent()`
returns. The private `cancelAndDrain` therefore repeatedly drives the selected scheduler until
the reader completes, under a 10-second deadline on `NonCancellable + Dispatchers.Default`.
Timeout reports job state and virtual time without joining. The reader has an independent owner
scope, cancelled in `finally`, and the import release gate is completed on every exit; otherwise
a cleanup failure could still leave `runBlocking` implicitly waiting for its child. See
[coroutine cleanup guidance](development-verification-test-scheduling.md#test-scheduling-and-harnesses).

`cleanupInvariant_lateContinuationCompletesAfterInitialDrain` explicitly releases the held
finalizer after the first drain, proving another scheduler turn is needed without sleep-based
ordering. `cleanupInvariant_nonCompletionFailsWithoutWaitingForOwner` keeps that finalizer held
while awaiting the cleanup caller's diagnostic failure. Releasing it before checking the caller
would hide an enclosing-scope wait. Both probes release held work in `finally` and use bounded
fallback draining.

The [retained Linux evidence](../../../app/src/test/resources/verification/cancelled-import-2260/results.json)
records source revisions, commands, hashes, raw output and XML. At
`66c1431cea22b0a876df26513ec97d56e50f100f`, the focused run executed/passed all three named
methods above (**3 passed, 0 failed, 0 skipped**); the complete class executed/passed **17,
0 failed, 0 skipped**, including those same methods. The complete old-cleanup red captures at
`af2d57bfdcecb5e1cd72c430f97d2feb68e5368b` and
`8b797521a3469e37ce2ad1a8ad137e3a5c0ec054` each executed **2, 0 passed, 2 failed, 0 skipped**
without a watchdog halt. The initial red run also failed both tests; its truncated output is
identified separately from the complete captures.

The [verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2261#issuecomment-6247160957)
at `d7db558791aafbbd85a6a41198d0b2dfe0120cea` confirms the Linux JVM gate executed/passed
**5930, 0 failed, 0 skipped**, including all 17 class methods and the three named methods.
This full-suite evidence is separate from the focused builder runs. macOS was outside this
ticket's verification scope.

Keep the [historical failed capture](https://github.com/pyrycode/pyrycode-mobile/blob/2739a690cc5719e9a255962f8d382d303beffb6c/app/src/test/resources/verification/owned-coverage-2254/rework-results.json)
distinct: at `c5529900e3f772398c899f97576ce73d599d7898`, the original class run exceeded the
180-second method limit and 30-second interrupt grace, then exited 124 without class XML or
reliable counts. Unchanged retries passed **1/1** for the method and **15/15** for the class,
each with **0 failed, 0 skipped**. Those retries do not erase the failed capture. A late Room
continuation remains a source-supported explanation for that historical hang; this test-only
repair establishes no production cache defect.

### File-cache regressions

[`FileConversationCacheTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/cache/FileConversationCacheTest.kt)
is a plain JVM unit test on a `TemporaryFolder` — no Robolectric, no device — because the
implementation takes a root `File` rather than a `Context`. 20 cases cover field-for-field and
null-field round-trips, write-order preservation, every graceful-empty-read shape (never
written, truncated, non-JSON, unsupported version, unparseable timestamp, duplicate id), host
and conversation isolation, replace semantics, the traversal-shaped server id, absence of any
identifier from the filesystem namespace, absence of credential-shaped strings and identifiers
from stored bytes, absence of identifiers from `RelayLog` output across both success and
failure paths, and a coded, causeless exception on a forced write failure.

**Every persistence assertion reads through a second `FileConversationCache` constructed over
the same root**, not the instance that wrote it. The one-entry validated thread decode
(#1949) makes this distinction concrete: a same-instance read alone proves nothing about
"a fresh process that did not write it." Every thread read still checks freshly read bytes,
but persistence and reuse need separate assertions. This is the JVM-root equivalent of the paired-server
store's ["recreating the store over the same DataStore can pass on cached preferences"](paired-server-store.md#testing)
lesson. `RelayLog.sink`/`enabled` are captured and restored around each test, the same idiom
`SettingsViewModelTest` uses, which is what makes the "no identifier reaches a log" assertion
real rather than a convention.

[`FileConversationCacheThreadTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/cache/FileConversationCacheThreadTest.kt)
(#797) is a sibling file rather than an extension of the test above, with the same second-instance
and log-capture discipline. 23 cases cover: a field-for-field round trip (message, a tool call in
both a settled and a failed status, a boundary with and without `workspaceCwd`); that unrecognized,
streaming and running-tool rows are dropped on write; per-conversation and per-host isolation;
whole-thread replace on a second write; that two boundaries sharing a session pair but differing in
`occurredAt` read back rather than being rejected (#775, a double idle-evict of the same session);
every graceful-empty-read shape including a duplicate message id, a duplicate boundary identity
(the full triple, not the pair) and a running-status document (left on disk, unrepaired);
`removeConversation` removing one thread and leaving siblings; removal still working against host
metadata that was never written; `removeHost` removing every thread under it and no other host's;
no conversation id in a path or a log line, success or failure; and a coded, causeless exception on
a forced write failure. #1353 added: a field-for-field round trip of a banner (both levels,
`truncated` true), a compaction row (null and non-null token counts, `manual` true/false) and a
refusal, in their original positions among a message and a boundary; a refusal with and a refusal
without a fallback model on one shared `occurredAt` both reading back, since their key also carries
`fallbackModel != null`; a literal pre-#1353 document holding only a message and a boundary still
reading; a document repeating a banner, compaction or refusal key, or a row setting two kinds, each
reading empty; the 100000 limit on `MAX_CACHED_THREAD_ROWS`; and that `cacheableThreadRows`, called
directly rather than through a 100k-row file write, keeps the newest rows past the limit.

[`FileConversationCacheReadPositionTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/cache/FileConversationCacheReadPositionTest.kt)
(#877) is a third sibling file, same second-instance and log-capture discipline. It covers a
round trip; host isolation, with `removeHost` dropping a host's positions and leaving a sibling
host's untouched; `removeConversation` dropping exactly one conversation's entry; and every
graceful-empty-read shape (never written, corrupt, duplicate conversation id) reading back empty
rather than throwing or repairing.

[`FileConversationCacheThreadTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/cache/FileConversationCacheThreadTest.kt)
also carries #1354's position cases: a round trip through a fresh instance; a row write keeping an
existing position and a position write keeping existing rows; a thread fed only live rows storing
none; a literal pre-#1354 document (rows, no `history` key) still reading as rows with no
position; `null` clearing a stored position; a write trimmed at `MAX_CACHED_THREAD_ROWS` dropping
a legacy position (coverage-bearing positions reset backwards state and invalidate lost claims);
`removeConversation` and `removeHost` removing it along with the rows; and no cursor
reaching `RelayLog` on either the read or the write-side re-read failure path.

[`CachingConversationRepositoryTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/repository/CachingConversationRepositoryTest.kt)
proves the same rules through the wrapper, against a real `FileConversationCache`: a position
written through `writeHistoryPosition` survives a concurrent row write from `observeMessages` and
reads back under the wrapper's own `serverId`; a deleted conversation's position write is skipped,
the same guard the row writer already has; and — added after the first verifier pass flagged that
the production path never exercised the trim rule — a drawn thread trimmed at
`MAX_CACHED_THREAD_ROWS` drops a legacy saved position when written through
`CachingConversationRepository.observeMessages` itself, not only through a direct call to the
cache. See [Caching conversation repository](caching-conversation-repository.md) for why
`observeMessages` now hands `writeThread` the untrimmed drawn rows rather than pre-trimming them.

`DecodedThreadRestoreTest` (#1949) checks shared row/position decode reuse, mutation
invalidation, host isolation, same-length/same-time external replacement, malformed
optional metadata, unreadable replacement and removal. Typed row reads also preserve
arbitrary field order and unknown-field handling. File size and modification time are
not content evidence. `HistoryHashCompatibilityTest` compares identities and row proofs
with the previous formatter and persisted representation, including multiple independent
inputs in a reused digest and maximum-unsigned restore order. Keep stale-claim and
legacy-alias tests alongside the matching-coverage identity-preservation regression:
reusing immutable coverage must never bypass proof checks.

`HistoryDurabilityTest` (#1832) covers fresh-instance coverage/high-water and cursor restore,
partial fills, conservative legacy migration, cache exclusions, failed row writes and interruption
between row/state writes. `HistoryCacheReworkTest` exercises complete production paths for durable
order, saved cursor/stop trim reset and deletion during suspended writers; see
[wrapper testing](caching-conversation-repository-testing.md).

Real app process death is proved on a device: [#1833](https://github.com/pyrycode/pyrycode-mobile/issues/1833)'s
external force-stop proof stops the app without clearing data, and a post made meanwhile appears
once after relaunch without scrolling. See the [evidence](../../e2e-interactive-stream.md#verification-status).

The two `HistoryCacheReworkTest` trim-reset cases reconcile 100,001 rows, write the production
100,000-row retained file and restore through fresh cache/repository instances. Keep that full-cap
fixture and its saved cursor/stop, coverage and reader-demand assertions. Its real disk work can
exceed `runTest`'s default one-minute wall deadline under load without a suspended ViewModel
handoff; [#1842](https://github.com/pyrycode/pyrycode-mobile/pull/1915#issuecomment-6038977116)
scopes a bounded three-minute timeout to `trimmingResetsWalk`, rather than shrinking the fixture
or changing cache production behavior.

The original storage-family checks did not include Compose or emulator scenarios: #796's
restored conversation rows
and #797's restored thread rows both draw through the same composables a live row does.
That establishes shared rendering, not restore latency. The production-wired
[saved-thread first-draw device check](thread-screen-testing-saved-first-draw.md#saved-thread-first-draw-1949)
now covers real cache/repository/ViewModel restoration and committed drawing. See [dependency injection §
Testing](dependency-injection.md#testing) for `HostConversationSourceTest`'s restore/live-race
cases and for why every other instrumented container built from `appModule` overrides this binding
with a shared `InertConversationCache` fake rather than supplying a real `Context`. See [Caching
conversation repository § Testing](caching-conversation-repository-testing.md) for the restore
merge's own unit coverage. Live continuity across a real reconnect — a loaded conversation staying
readable while its host link is cut and reconciling a peer's turn once the link is restored — is
proven live by [#850](https://github.com/pyrycode/pyrycode-mobile/issues/850)
(`InteractiveStreamE2ETest.interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`), not this
cache's or the wrapper's own unit suite.
