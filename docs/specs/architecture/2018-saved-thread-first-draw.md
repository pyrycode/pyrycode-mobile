# Saved-thread first-draw reliability (#2018)

## Files read

- `SavedThreadFirstDrawDeviceTest.kt`: `Probe`, `repository`, `installHost`, `openAndMeasure`; monotonic construction-to-committed-frame contract and fixtures.
- `FileConversationCache.kt`: `readDecodedThread`, `decodeHistory`, `validatedRows`, `CachedThreadRead`; validated decode reuse still builds an optional-history JSON tree.
- `HistoryCoverage.kt`: `validated`, `retainedBy`, `historyRowProofs`; preserve all structural and retained-content checks.
- `CachingConversationRepository.kt`: `observeThreadSnapshot`, `readHistoryPosition`; four fresh document reads across the independent restore consumers.
- `ThreadViewModel.kt`: `historySeed`, `threadItems`, `threadContent`; restore, fold, projection and frame publication remain timed.
- `ThreadContentScheduling.kt`: `paceThreadContent`; retain real Android frame scheduling.
- `ThreadScreen.kt`: `ThreadMessageList`; retain layout and viewport geometry.
- `DecodedThreadRestoreTest.kt`, `CachedThreadWorkerTest.kt`: decode freshness, metadata fallback and worker contracts.
- `docs/knowledge/features/thread-screen-testing.md`: Saved-thread first draw (#1949); an isolated pass cannot erase a full-gate miss, and sparse fixtures conceal allocations.
- `docs/knowledge/features/conversation-cache-layout.md`: exact freshly read bytes and path, independent metadata rejection and proof compatibility.
- `docs/specs/architecture/1949-saved-thread-first-draw.md` and merged change `d7c1e20e8a43a871164f3a7c83ae430bc233fb7b`: prior allocation repairs and remaining margin.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot: dark radial background, left/right message bubbles using on-primary-fixed/on-primary roles, body-medium text, session rule/label/rule delimiters, top title/back/menu and bottom composer/footer. This repair changes cache restore work only; existing screen geometry, tokens, assets and interactions remain the visual target.

## Context

At base `5133aa796352a5136df426979ab0634e8a554140`, the whole device class passed (2 executed, 2 passed, zero failures/skips), drawing fragmented fresh instances at 850/762 ms. The named method alone then failed (1 executed, 0 passed, 1 failed, zero skips): fragmented offline first open restored/snapshotted/completed/drew at 954/1325/1579/1722 ms. Fresh XML and logcat are retained under `/tmp/builder-2018/baseline-isolated/`. Allocation-blocking GC occurred in that interval. Negative-control-first process warming therefore hides insufficient allocation margin; the bound and probe endpoint remain valid. No decision record is required.

## Design

First add content-free cache phase timing to distinguish document read, typed row decode, row validation, metadata decode and proof retention. Use that fresh evidence to pin the allocation repair in Revisions before changing behavior. The leading candidate is decoding valid optional history directly into the existing typed record instead of building a large JSON tree and then the same typed coverage. Preserve the current independent fallback for malformed metadata and legacy compatibility. Reuse still requires a successful fresh read of exact document bytes and matching host/conversation path. Every version, row identity, structural coverage and content proof check remains mandatory; writers, schema and retention do not change.

No new dependency, exported type, ViewModel, UI state/event or consumer migration is planned. Expected written work is under 500 lines (plan, diagnostics, local cache repair and regression tests); at most two production files, zero signature changes, three acceptance criteria, and fewer than ten error branches. Remote numeric feature branches have no overlapping edits in the proposed cache/repository/probe files as of the initial fetch.

## State and concurrency model

Keep the cache's existing IO dispatcher and mutex, one immutable retained decode, and existing invalidation on writes/missing/changed/unreadable documents. No scope or job is added. Repository restore consumers, ViewModel cancellation and main/worker/frame scheduling remain unchanged. Diagnostics measure synchronous phases with a monotonic clock and emit only phase durations and counts.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Fresh ordinary/fragmented cache and repository, offline or held newest | Existing named first-draw device method, isolated and whole-class execution |
| Reopen same repository with fresh ViewModel/composition | Same device method; all four reopen cases |
| Same-length external replacement / same ids on different hosts | `DecodedThreadRestoreTest` freshness and host tests |
| Malformed optional metadata / invalid rows / changed proof | Decode regression tests plus existing unsigned-cache/durability tests |
| Mutation, removal or unreadable replacement after retained decode | Existing `DecodedThreadRestoreTest` cases |
| Injected 3000 ms cache read | Existing negative control rejects the identical 1000 ms assertion |

## Error handling

Retain classified read fallback: invalid rows/version reject the document; malformed optional metadata withholds history while keeping valid rows. Cancellation and unrelated programming errors remain unclassified. Logs contain static event names, counts and timings only. No daemon text, identifiers, paths, cursors, hashes or decrypted content is logged.

## Testing strategy

The existing isolated first-draw method is the observed red test. Add cache compatibility/rejection regressions before the repair. Run affected decode, unsigned coverage/cache, durability, hash compatibility, held-newest and projection tests; run the first-draw method alone and the complete device class, preserving XML/logcat and all cumulative timings. Device-only reason: real disk, allocation/worker scheduling and committed Android frames cannot be established by Robolectric virtual time. Retain fixture sizes, exact rows/markers, ask counts and held response; leave the 1000 ms assertion and 3000 ms negative control unchanged. Run lint, assemble, Android-test compilation, formatting and final pre-verify after merging main. The dispatcher owns the full UI gate. #1949 already owns live offline/reconnect integration; no new live scenario is required.

## Open Questions

- Which synchronous cache phase dominates the reproduced restore interval? Resolve with fresh diagnostics before the repair and record the exact replacement contract in Revisions.
