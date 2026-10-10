# Saved-thread restoration latency (#2026)

## Files read

- `SavedThreadFirstDrawDeviceTest.kt`: `Probe`, `repository`, `openAndMeasure`, `withFixture`; cold construction through exact visible text and committed frame, with unchanged fixtures and request counts.
- `FileConversationCache.kt`: `readDecodedThread`, `decodeThreadRecord`, `validatedRows`, `validatedHistory`, `cachedThreadRowProof`; fresh bytes, typed decoding, independent metadata rejection and canonical row proofs.
- `HistoryCoverage.kt`: `validated`, `retainedBy`, `historyRowProofs`; structural checks and proof pruning must remain authoritative.
- `CachingConversationRepository.kt`: `observeThreadSnapshot`; saved rows/order are merged on the configured worker before publication.
- `ThreadContentScheduling.kt`: `paceThreadContent`; accumulated values wait for an Android frame.
- `FragmentedHistoryFixture.kt`: `fragmentedHistoryFixture`; 18,000 displayed rows, 36,000 entries and 18,000 spans.
- `DecodedThreadRestoreTest.kt`: exact-byte replacements, invalid metadata, legacy decoding and removal regressions.
- `docs/knowledge/features/thread-screen-testing.md`: Allocation margin and retained evidence (#2018); whole-class success cannot replace isolated proof or erase retained misses.
- `docs/knowledge/features/conversation-cache-layout.md`: Thread document readers/writers; freshness and canonical proof encoding constrain allocation repairs.
- `docs/knowledge/features/caching-conversation-repository-testing.md`: cache/coverage, reconnect and held-worker regression selection.
- `docs/specs/architecture/2018-saved-thread-first-draw.md`: previous repairs and all partial failures.

## Design source

N/A: performance repair preserving existing screen layout, styling and interactions. The refinement comment explicitly establishes that no new Figma anchor is needed. No composable visual change is planned.

## Context

Main still misses the existing one-second saved-thread draw contract in isolated execution after #2018. Retained merge-base evidence measured fragmented offline first-open restore/snapshot/complete/draw at 922/1212/1511/1688 ms. These timings identify cache and downstream work as investigation targets, not an established remaining cause. Diagnose with fresh content-free phase measurements, then record the selected repair and its evidence in Revisions before changing production behavior. No decision record is expected.

## Design

Remove measured redundant work or temporary allocations in the cache restoration/proof path and, only if phase evidence requires it, cached snapshot preparation. Prefer reuse of validated immutable data within the existing synchronous operation over introducing another cache or asynchronous publication. Preserve all byte freshness, version, row identity, metadata structure and retained-content proof checks. Canonical persisted SHA-256 inputs and full digests remain compatible; no file schema, wire, layout, ordering, merge or reconnect contract changes.

Use the existing isolated device method as the red regression. Add targeted compatibility/rejection assertions before any new logic. The selected implementation is bounded to at most three production files in the inspected path, no exported types or signatures, no consumer migrations, three criteria and unchanged rejection branches. Forecast including plan, tests and diagnostics: approximately 850 written lines, below 1,600. Recount after diagnosis and before handoff. No remote numeric feature branch overlaps the proposed cache, coverage, repository or device probe files at initial fetch.

## State and concurrency model

Keep the cache IO dispatcher and mutex, one retained immutable decode, caller cancellation and invalidation on mutation/missing/changed/unreadable documents. Keep repository cold collection, processing dispatcher, scoped writer finalization, ViewModel ownership and real frame pacing. No new job, scope, state flow, UI state or event. Any reusable digest belongs to one synchronous batch and must reset between independent values without crossing a suspension or thread boundary.

## State transitions and identity reuse

| Event | Tests |
| --- | --- |
| Fresh cache/repository, ordinary or fragmented, offline or held newest | Isolated `savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen` |
| Fresh ViewModel/composition with same repository | Same method, all four reopen cases |
| Injected 3,000 ms restore delay | Isolated `slowRestore_negativeControlRejectsTheSameFirstDrawBound` rejects identical 1,000 ms bound |
| Same-size replacement, buffer/multibyte boundaries, length change or trailing document | `DecodedThreadRestoreTest` freshness cases |
| Missing/deleted/unreadable file, same conversation on another host | `DecodedThreadRestoreTest` invalidation and isolation cases |
| Invalid optional metadata, invalid rows or changed proof | `DecodedThreadRestoreTest`, `UnsignedHistoryCacheTest`, `HistoryCacheReworkTest` |
| Legacy aliases and older document forms | `UnsignedHistoryCacheTest`, `HistoryHashCompatibilityTest`, legacy decode cases |
| Empty live snapshot, held newest response and reconnect | `CachingConversationRepositoryTest`, `CoalescedThreadWritesTest`, existing held-newest regression |

## Error handling

Invalid version or rows reject the document. Malformed optional history independently withholds position while keeping valid rows. Missing/unreadable documents cannot borrow a retained value. Existing classified read failures remain graceful, mutations return their existing results, and cancellation/programming failures retain their current propagation. Logs contain only static event codes, counts and durations; never content, cursors, identities, credentials or document bytes.

## Testing strategy

Read the baseline isolated method's fresh XML/logcat and phase timings. Run new focused regressions red before implementation, then affected cache/proof, unsigned coverage, durability, held-newest and worker tests green. Run the isolated first-draw method and isolated negative control with the configured Android 13 managed device through the existing FIFO device hold. Preserve exact timer placement, viewport/frame endpoint, all eight cases, fixture sizes, exact rows/markers and offline/connected ask counts. Device-only reason: real disk, worker allocation/scheduling and Android committed frames cannot be proven by Robolectric virtual time.

Retain revision, command, exit, XML timestamp, executed/passed/failed/skipped/error counts and all cumulative phases under `/tmp/builder-2026/`, including every miss. Existing historical evidence under `/tmp/builder-2006/rework-evidence/` remains untouched. Run lint, assemble, test compilation and forced formatting checks; merge main, push and run final assemble plus `scripts/pre-verify.py --gradle`. Dispatcher owns unchanged full UI gate and must report nonzero counts for both named methods, including failures/skips. Existing live offline/reconnect coverage owns integration; no new real-Claude flow is added.

## Open Questions

- Which remaining timed phase dominates the fresh isolated miss, and what minimal allocation/work repair gives sufficient margin? Resolve in Revisions with measured evidence.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/thread-screen-testing.md`, “Allocation margin and retained evidence (#2018)”: established #2026 cause, repair and counted focused/full-UI evidence; preserve earlier misses and distinguish dispatcher results.
- Pending only if cache implementation changes: `docs/knowledge/features/conversation-cache-layout.md`, Thread document readers/proofs: implementation details with unchanged freshness, schema and proof contracts.

## Security review

**Verdict:** PASS

- [Trust boundaries] `readDecodedThread` remains the file-to-domain boundary. MUST-preserve constraint: byte reuse requires a successful full fresh read; invalid rows reject the document and optional history is independently validated. Compatibility/rejection tests cover hostile replacements.
- [Tokens] No credential generation, persistence or transit changes. Digests prove retained content and are not secrets; they must remain canonical, complete SHA-256 values.
- [Files/storage] Existing hashed host/conversation paths and app-private cache root remain; daemon text never names a file. Existing atomic temporary-file replacement and backup policy remain. Conversation bodies follow the documented existing unencrypted app-private cache policy; this repair adds no storage/exposure surface.
- [Android attack surface] No component, intent, provider, WebView, permission or keyboard changes. Restored rows use existing inert text renderers.
- [Cryptography] Standard `MessageDigest` SHA-256 only; no new primitive, Noise/key/nonce change. Batch reuse must reset between inputs; independent canonical hash vectors verify this.
- [Network/I/O] No transport, URL, size-limit or timeout changes. Offline restore cannot issue newest requests; connected requests remain one per opening with the response held.
- [Errors/logs] Only static events, phase durations and row counts may be logged. No daemon text, cursor, path, identifiers, bytes, key, token or raw exception output is added.
- [Concurrency] Existing IO mutex, worker and caller/ViewModel scopes remain. No retained cross-host result or digest crosses a suspension/thread boundary; teardown and cancellation stay unchanged.
- [Threat model] Malicious relay drop/delay is handled by existing offline cache behavior and is unchanged. Hostile persisted daemon content still passes row/metadata/proof validation and inert rendering. Rooted-device token theft and UI screenshot/accessibility leakage remain with existing key-storage/UI owners; this change neither processes credentials nor changes their defenses. Protocol security model is `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`, Security model; no wire change.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-10
