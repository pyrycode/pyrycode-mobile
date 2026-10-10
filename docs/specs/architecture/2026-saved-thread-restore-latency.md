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

## Revisions

### 2026-10-10 — Isolated baseline and decoder allocation target

Fresh unchanged isolated execution failed at fragmented offline first open: 845/1053/1193/1314 ms cumulative restore/snapshot/complete/draw. Cache read/decode/validation/metadata/proofs measured 7/517/150/23/123 ms; the worker used 824 ms wall and 416 ms CPU. Exit 1, one executed/failed, zero passed/skipped/errors; XML timestamp `2026-10-10T04:31:30` and selected-method logcat are retained under `/tmp/builder-2026/baseline/`. Historical #2006 and merge-base misses were also copied to `/tmp/builder-2026/prior/` without changing their originals.

Typed JSON decoding is the largest timed cache phase. The serialization library creates an implicit-null element marker and a separate object decoder when `explicitNulls` is false; the saved-thread records and `HistoryCoverage` already give every nullable field a default. Use an immutable decoder-only `Json(MobileJson)` with `explicitNulls = true` for typed thread records, the independent fallback and optional metadata. This removes unnecessary per-record decoder/marker allocation without changing decoded defaults. Keep conversation/read-position readers, every writer and canonical row-proof encoding on unchanged `MobileJson`; changing encoding would alter persisted hashes. Required fields remain required, legacy omitted spans retain their existing fallback, and all row/version/structure/proof checks remain in the same timed operation.

Before this repair, the full-size fresh-cache rows/claims regression passed, and the new omitted/explicit-null metadata and timestamp-compatibility methods passed 2/2 with no failures, skips or errors; fresh XML is under `/tmp/builder-2026/pre-compatibility/`. A temporary delegated history serializer measures metadata's share of decode in the queued diagnostic run; remove it after reading that result, keeping its XML/logcat. No device probe or fixture change is made.

Security review remains PASS: only the decoder configuration changes; nullable defaults are explicit in the inspected thread DTOs, required metadata rejection is pinned by the pre-repair tests, and encoding/freshness/proof validation boundaries are unchanged. Recount remains below 850 written lines, one production file, zero exported types/signature migrations and unchanged reject branches. Final focused device evidence must establish sufficient wall-clock margin; the allocation analysis alone is not acceptance proof.

The delegated diagnostic also reproduced the isolated miss: 602/800/1010/1153 ms cumulative; cache 3/298/107/25/134 ms, with optional-history decoding accounting for 151 ms of the 298 ms decode phase. The worker used 579 ms wall / 490 ms CPU. Exit 1, 1/0/1/0/0 executed/passed/failed/skipped/errors, XML timestamp `2026-10-10T04:46:43`, retained under `/tmp/builder-2026/decode-diagnosis/`. Both row and history parsing contribute; remove the temporary diagnostic serializer and apply the decoder-only configuration to both, retaining existing phase logs and all validation.

### 2026-10-10 — Remaining timestamp parser allocations

Decoder-only isolated execution on merged revision `5b48c698fc9942b86ccba4ab9823b23470545397` still failed at 569/748/946/1139 ms (cache read/decode/validation/metadata/proofs: 1/282/99/28/134 ms). Retain exit 1, 1/0/1/0/0 XML timestamp `2026-10-10T05:00:16` under `/tmp/builder-2026/decoder-isolated/`. The separate negative control passed 1/1/0/0/0, exit 0, timestamp `2026-10-10T05:00:39`, at 3020/3290/3313/3388 ms under `decoder-control/`. The configuration alone does not establish the bound.

Row validation repeatedly invokes the general Kotlin date-format parser, creating parse state for every persisted timestamp; this work also runs during the metadata writer's required reread before cold restore. Add a private `parseThreadInstant` common to the six thread row kinds: recognize only fixed-width whole-second UTC timestamps, read ASCII numeric components without substrings, validate calendar/time using standard `LocalDateTime.of`, and construct the domain `Instant` from UTC epoch seconds. All other forms and any invalid fast-path component fall through to the unchanged `Instant.parse`, preserving legacy offset/fraction/extended-year handling and exact rejection behavior. No custom calendar arithmetic or accepted-input widening. The already committed timestamp compatibility test passed against the original parser before implementation and includes invalid dates, hour 24 and leap seconds. Encoding and proof hashing remain unchanged. Security review PASS: standard calendar validation is mandatory and malformed input still reaches the original classified rejection path. No signature, exported type, wire, lifecycle, fixture or deadline change; still one production file and under 850 written lines.

### 2026-10-10 — Resolved cause and focused acceptance evidence

The remaining cost was unnecessary decoder allocation and general timestamp-parser state during restoration, including the writer's required reread before cold restore. The two local repairs reduce the fragmented offline cache phases to 1/168/10/14/82 ms and its worker to 278 ms wall / 269 ms CPU. No repository, scheduler, test probe or fixture change was needed. The open diagnosis question is resolved by the measured reduction and committed-frame evidence, rather than snapshot publication alone.

On revision `b1029033bd5cea81b457e3969a89dd2152791ef0`, isolated first-draw execution passed 1/1/0/0/0 executed/passed/failed/skipped/errors, exit 0, XML timestamp `2026-10-10T05:07:37`. All eight cases passed; committed draw ranged from 100 to 611 ms, including fragmented offline first-open at 293/407/523/609 ms and fragmented connected first-open at 258/346/472/611 ms. The isolated negative control passed 1/1/0/0/0, exit 0, timestamp `2026-10-10T05:07:59`, rejecting the identical deadline at 3036/3389/3434/3533 ms. Complete XML, selected-method logcat, command/revision/exit manifests and all nine cumulative tuples are retained in `/tmp/builder-2026/timestamp-isolated/`, `timestamp-control/` and `focused-evidence.json`; prior misses remain retained. The PR reports every tuple and the unchanged full-UI gate remains pending with the dispatcher.

Focused cache/proof/coverage/repository/held-worker checks passed 181/181 with zero failures/skips/errors; the expanded timestamp compatibility method separately passed 1/1 with zero failures/skips/errors. Lint, debug assembly and device-test compilation passed. Final scope is one production file, one unit-test file and this plan; no exported types, six private parser call-site edits and unchanged rejection branches. The final source/test/plan diff is below 300 inserted-plus-deleted lines; transient diagnosis and plan revisions keep total written work below 400 lines. Documentation stage must also record the timestamp fast path and legacy fallback in the cache-layout handoff.
