# Conversation cache — Testing

Split from [Conversation cache](conversation-cache.md); this topic retains the section anchors.

## Testing

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

### Cancelled committed import cleanup (#2260)

`MigratingConversationCacheTest.classificationInvariant_cancelledCommittedImportCannotReadmitUnobservedWrites`
observes the Room commit before cancelling its paused reader. Preserve all four combinations of
read/write import and legacy/authoritative storage, the cancelled-child assertion,
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
[wrapper testing](caching-conversation-repository-testing.md#testing).

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
conversation repository § Testing](caching-conversation-repository-testing.md#testing) for the restore
merge's own unit coverage. Live continuity across a real reconnect — a loaded conversation staying
readable while its host link is cut and reconciling a peer's turn once the link is restored — is
proven live by [#850](https://github.com/pyrycode/pyrycode-mobile/issues/850)
(`InteractiveStreamE2ETest.interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`), not this
cache's or the wrapper's own unit suite.
