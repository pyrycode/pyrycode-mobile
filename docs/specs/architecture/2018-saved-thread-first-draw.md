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

At base `5133aa796352a5136df426979ab0634e8a554140`, the whole device class passed (2 executed, 2 passed, zero failures/skips), drawing fragmented fresh instances at 850/762 ms. The named method alone then failed (1 executed, 0 passed, 1 failed, zero skips): fragmented offline first open restored/snapshotted/completed/drew at 954/1325/1579/1722 ms. Fresh XML and logcat are retained under `/tmp/builder-2018/baseline-isolated/`. Allocation-blocking GC occurred in that interval. The differing outcomes expose insufficient allocation margin; the bound and probe endpoint remain valid. No decision record is required.

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

## Revisions

### 2026-10-10 — Measured restore allocation repair

The instrumented isolated run failed on fragmented held-newest first open at 1476 ms (restore/snapshot/content 758/1157/1337 ms). Cache phases measured read/decode/row-validation/metadata/proofs at 45/139/77/210/192 ms. The prior offline opening passed at 906 ms, but even its retained-decode reopen took 937 ms. The raw metadata tree followed by typed coverage consumes substantial restore time and allocations, and four full `readText` calls allocate whole-document buffers even when only comparing unchanged bytes. Fresh evidence is under `/tmp/builder-2018/diagnosis/`; exit 1, one executed/failed, zero passed/skipped.

Decode the usual valid document directly as the existing `CachedThread`; on serialization failure, use the existing `CachedThreadRead`/raw-metadata fallback. Validate rows before accepting history, independently catch metadata structural failure, and retain all content proof checks. Missing legacy `spans` remains compatible through that fallback. For a retained decode at the same path, compare the entire freshly opened document text against the held text through a fixed-size character buffer; return the held value only after matching all characters and EOF. A mismatch retires it and freshly reads/decodes the current document. This preserves the existing UTF-8 `readText` interpretation and freshness contract while avoiding whole-document temporary strings on unchanged reads. No file length or timestamp participates, and no first-open fixture is warmed.

Add regressions for fully typed unsigned coverage (including maximum unsigned ids), stale direct proofs, legacy omitted spans, structural metadata rejection, and a same-length replacement whose difference crosses the comparison buffer boundary. Keep the observed red device probe and all measurement/fixture assertions unchanged. One production file, no exported declarations or signature/call-site updates; forecast remains below 500 written lines.

### 2026-10-10 — Remove first-decode document string

The complete class still missed: fragmented offline first open drew at 2058 ms (restore/snapshot/content 1323/1727/1877 ms), despite the isolated pass. Cache read/decode/validation/metadata/proofs were 84/534/216/108/281 ms, and GC reclaimed whole-document large objects. Retain this miss under `/tmp/builder-2018/repair-class-miss/`; exit 1, two executed, one passed/one failed, zero skips. The negative control still rejected its 3640 ms draw.

Keep one exact document **byte array**, decode it through the installed kotlinx-serialization JSON stream API and compare freshly opened bytes through a fixed-size byte buffer before reuse. This eliminates the additional whole-document UTF-16 string on valid first restore and its subsequent retention. Serialization failure still uses the prior UTF-8 text/raw-metadata fallback; no disk encoding or writer changes. Path plus all bytes plus EOF establish freshness. Add valid whitespace growth/truncation and trailing-second-document rejection controls, retaining the existing multibyte boundary/replacement tests. `decodeFromStream` is present in the installed 1.8.1 artifact and its existing serializers remain the authority. No new dependency or exported declaration; still one production file and under 500 written lines.

### 2026-10-10 — Defer unused signed compatibility collections

The streamed reader still missed on isolated fragmented offline first open: 1216/1542/1851/2103 ms cumulative restore/snapshot/content/draw, with cache phases 63/598/114/91/249 ms and repeated allocation GC. Keep the fresh exit-1 XML (one executed/failed, zero passes/skips) under `/tmp/builder-2018/stream-miss/`. The device setup first waited for another managed-device process's AVD lock; that wait is outside the measured interval.

`HistoryCoverage` eagerly constructs signed span/gap/cursor/walk/order/entry collections alongside its unsigned authoritative collections, even when the restore pipeline only consumes unsigned fields. Defer these immutable compatibility views and their span-derived high-water/unknown-edge values with thread-safe `lazy`; preserve their public getter types, signed clipping/filtering, independent copies and exclusion from disk serialization. No consumers migrate and no scheduling changes. Existing signed/unsigned coverage tests plus the new `signedCompatibilityInvariant_restoredAndCopiedViewsStayIndependentAndOffDisk` cover the contract; the unchanged first-draw device probe is the red performance regression. Two production files, still below 500 written lines. Initial plan's one-file repair candidate expands to the second measured allocation source.

### 2026-10-10 — Share typed decoding with metadata writes

Deferring signed views reduced the isolated fragmented offline opening to 920 ms, but held-newest fresh opening still missed at 1297 ms (798/1004/1182 ms cumulative restore/snapshot/content; cache 6/379/122/47/175 ms). Fresh one-executed/failed XML and log are under `/tmp/builder-2018/lazy-miss/`. Before that restore, GC reclaimed millions of temporary objects from the repeated metadata write, and continued during restore. `readThreadRecord`, used by `writeHistoryPosition`, still builds the whole row JSON tree before typed decoding. Share the streamed typed decoder and independent metadata validation between the restore reader and this writer's reread, eliminating that allocation burst while preserving its row/version/coverage checks and exact serialized output. Remove the private single-caller `decodeStoredRows`; no public signature changes. Tests remain the existing writer/metadata compatibility suite and unchanged device probe. Delegated compatibility properties are inherently excluded by serialization; remove their redundant `@Transient` annotations, retaining annotations on stored derived fields.

### 2026-10-10 — Use the faster typed string parser

Sharing the writer decoder did not establish the bound: isolated fragmented offline first open measured 843/1139/1273/1369 ms; cache 3/472/105/54/177 ms. Retain this exit-1, one-executed/failed XML under `/tmp/builder-2018/writer-miss/`. The stream decoder remains the largest phase and has materially higher decode duration than the initial directly typed string decoder. Use the directly typed string parser for the shared decoder while retaining only document bytes after decode. The temporary UTF-8-decoded string is released after construction; unchanged subsequent reads still compare through the bounded buffer. This revision supersedes stream parsing, without reinstating either the metadata/row JSON tree or repeated retained-read strings. No experimental API or dependency remains.

### 2026-10-10 — Validate in place

The directly typed string/shared-writer run still failed: fragmented offline 940/1191/1378/1483 ms cumulative, cache 61/347/155/67/250 ms. Retain its one-executed/failed fresh XML under `/tmp/builder-2018/typed-writer-miss/`. The cache remains the dominant miss, and all its checks stay inside the measured interval. Replace `validatedRows`' filtered/distinct temporary lists with one identity-set pass over the mapped domain rows, preserving every running-tool and kind-specific duplicate rejection. Validate already-normalized unsigned spans in stored order (positive, non-reversed, sorted, disjoint and non-adjacent), and validate each gap directly against its neighbouring spans instead of sorting/merging and allocating zipped collections. The normalized-span/max-endpoint regression ran before implementation; existing cache rejection tests cover every row kind. No lifecycle state, proof policy, disk field, consumer or reject branch is added. Recount including deleted/replaced lines and the diagnostic revisions is about 550 written lines across two production and two test files plus the plan; this exceeds the initial 500-line forecast and remains below the 1600-line one-ticket ceiling.

### 2026-10-10 — Reuse validated domain rows during metadata writes

The metadata writer mapped every saved row into the domain four times: for optional old-history validation, whole-row validation, incoming claim retention and incoming claim binding. `decodeThreadDocument` now returns its stored records together with the single validated domain list, and `writeHistoryPosition` reuses that list for both incoming coverage operations. Preserve version/row/old-metadata validation, stale-proof removal, binding and serialized records; only duplicate mapping allocations disappear. Remove the single-caller `readThreadRecord`; the private helper has one consumer to update. This reduces the measured fixture-write allocation burst preceding the restore without changing the fixture, warming the restore reader or moving its timer. Expected total remains around 600 written lines, two production files and no public migration.

### 2026-10-10 — Coordinate focused device execution

At `ac2dfc64a`, the queued raw Gradle run eventually executed and failed: fragmented offline 1451/1843/2208/2344 ms, cache 21/680/176/117/369 ms. Fresh XML timestamp `2026-10-09T22:38:42`, exit 1, one executed/failed, no passes/skips, retained under `/tmp/builder-2018/validation-miss/`. Read-only Gradle diagnostics confirmed another live gate was active, and own setup progressed from shared AVD locking to waiting for a snapshot subprocess. Raw focused Gradle does not acquire the Python gate's shared device hold. Subsequent focused commands take the existing FIFO `device_hold` from the pipeline helper before executing the same managed-device task, with `disableAnimations=true` as the dispatcher UI gate uses. This changes no fixture, cache instance, assertion, measurement start or committed-frame endpoint. Retain all raw-run misses; exclusive focused evidence supplements them and the full UI gate remains dispatcher-owned. This is a test-execution coordination adjustment, with no repository harness/configuration edit.

### 2026-10-10 — Stream atomic thread writes

Exclusive focused execution at `4d1026e74` still failed held-newest fragmented first open: 784/947/1046/1146 ms cumulative, cache 23/336/110/39/235 ms. Offline first/reopen were 781/392 ms. Preserve exit-1, one-executed/failed fresh XML timestamp `2026-10-09T22:41:52` under `/tmp/builder-2018/exclusive-miss/`. GC reclaimed 49 MB of large objects after the second whole-document write and continued through restore. Device coordination alone does not repair this miss.

For the two thread-document writers, encode the same `CachedThread` serializer/configuration directly to a buffered UTF-8 temporary-file stream, close it, then perform the existing atomic move. The shared atomic-write helper still handles directory creation and commit; other document writers retain their existing text encoding. This eliminates whole-document string/byte buffers during large thread saves and preserves field names, schema/version, row order, proofs, retention, error classification and atomic replacement. Existing `HistoryHashCompatibilityTest` verifies canonical persisted row hashes, including multibyte text; the full cache writer/unsigned claim regression selection already passed before the change. Stream decoding remains superseded by the faster typed string parser. No new dependency, public signature or timer/fixture change.

### 2026-10-10 — Remove per-row identity formatting temporaries

At `53aa7eb90`, exclusive streamed-write execution still failed fragmented offline: 1052/1361/1560/1668 ms cumulative; cache 2/464/177/39/267 ms. Retain exit-1, one-executed/failed fresh XML timestamp `2026-10-09T22:44:52` under `/tmp/builder-2018/streamed-write-miss/`. Whole-document write buffers disappeared, but the remaining read/validation/proof path still allocates heavily.

Build the existing length-prefixed identity text directly in one string builder rather than a formatted temporary string for each identity part; preserve UTF-16 lengths and every scalar/null/list conversion. Expanded `HistoryHashCompatibilityTest` vectors (null/nested empty list, Unicode, signed/unsigned extremes and ambiguous concatenations) passed before the change. Count nullable row kinds directly instead of allocating a six-element array and filtered list on every row. Keep the same exactly-one-kind rejection and all existing cache rejection tests. No public signature, hash encoding, kind semantics, fixture or timer changes; total forecast remains under 700 written lines.

### 2026-10-10 — Distinguish worker CPU from elapsed restore time

Add content-free wall/CPU diagnostics around the existing IO dispatcher's runnable in `SavedThreadFirstDrawDeviceTest.repository`, delegated to `Dispatchers.IO` with the original context and cancellation behavior. Android `Debug.threadCpuTimeNanos` measures CPU spent on the same worker; monotonic elapsed time includes allocation GC and scheduling delays. It is diagnostic evidence, never the acceptance clock: `Probe.start`, all four cumulative phase markers, exact viewport/committed-frame checks, fixtures, cold cache/repository instances, ask counts, 1000 ms assertion and 3000 ms negative control remain unchanged. No job, scope, test retry or artificial delay is added. The device-only reason remains real disk/worker/committed Android frames. Use this measurement to resolve the large variation across restore phases before further changes.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/conversation-cache-layout.md`, Thread document readers — exact byte-array retention, bounded fresh-byte comparison and direct typed decoding with independent optional-metadata fallback; unchanged format, proofs and invalidation.
- Pending documentation stage: `docs/knowledge/features/thread-screen-testing.md`, Saved-thread first draw — retain the baseline/partial-repair misses, fresh isolated and class timings, and the dispatcher-owned UI gate result when available.

- Pending documentation stage: `docs/knowledge/features/conversation-cache-layout.md`, history coverage — signed compatibility collections are computed once on demand; unsigned disk fields and validation remain authoritative.

- Pending documentation stage: `docs/knowledge/features/conversation-cache-layout.md`, Thread document writers — buffered JSON encoding into the same atomic temporary-file replacement, with one reused validated domain row list for metadata writes.
