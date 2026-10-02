# #1354 — Resume older-history loading from the saved chat's oldest message

## Files read

- `data/cache/ConversationCache.kt` — `ConversationCache` (`readThread`, `writeThread`, inert defaults), `cacheableThreadRows`, `MAX_CACHED_THREAD_ROWS`, `ReadPosition` (#877, the analogue). Gains the position read and write.
- `data/cache/FileConversationCache.kt` — `CachedThread`, `writeThread`, `readThread`, `decodeThread`, `mutate`, `removeConversation`, `removeHost`. The position is stored inside `CachedThread`.
- `data/repository/ConversationRepository.kt` — `ConversationRepository.requestHistory`, `HistoryPage`. Gains two members with inert defaults and the `HistoryPosition` value.
- `data/repository/CachingConversationRepository.kt` — `observeMessages` (the row writer), `delete` and its `deleted` set. Overrides the two new members.
- `ui/conversations/thread/ThreadHistoryDemand.kt` — `ThreadHistoryDemand` (`settled`, `cursorRefused`, `tail`), `MAX_HISTORY_PAGES` KDoc. Gains `restored`.
- `ui/conversations/thread/ThreadViewModel.kt` — `historyDemand`, `onDemandOlderHistory`, `launchHistoryAsk`, `claimHistorySlot`.
- `di/AppModule.kt` (`repository` wraps `CachingConversationRepository` in `decorateRepository`) and `e2e/UnrecognizedRowSentinel.kt` (`TappingConversationRepository`) — both are `ConversationRepository by delegate`, so the new members forward with no edit.
- `docs/knowledge/features/conversation-cache.md`, `remote-conversation-repository-reads-and-thread-store-history-paging.md` — read for the cache and walk contracts; the documentation stage updates them.

Overlapping in-flight branches: #1340, #1346, #1355 (`ThreadViewModel.kt`, other blocks) and #1356 (`FileConversationCache.kt` row kinds, `ConversationRepository.kt`). Edits there stay additive and local.

## Design source

Figma node 16-8 (Conversation Thread Screen). No visual change: the existing oldest-end slot states (`ThreadHistoryTail`) are reused, so the verifier's visual check has nothing new to compare.

## Context

Since #1352 history loads only on a pull, and every open starts `historyDemand` from an empty cursor, so the first pull in a saved thread re-fetches the newest page the user already holds. Desktop saves the walk's coverage beside the timeline (`chatHistory.ts` `coverage`, `chatHistoryWriter.ts`, `historyPageBridge.ts` `requestOlderHistory`). This ticket mirrors it. No decision record needed: it extends the #797 thread document rather than adding a storage family.

## Design

### `HistoryPosition` (data/repository, beside `HistoryPage`)

`data class HistoryPosition(val cursor: String, val atStart: Boolean)` — the last received page's opaque cursor and `atStart`. `toString` omits the cursor so no stray interpolation can log it. Lives in `data/repository` because `ConversationRepository` exposes it and the cache already imports `ThreadItem` from there.

### `ConversationCache`

- `suspend fun readHistoryPosition(serverId, conversationId): HistoryPosition? = null` — graceful like `readThread`: a missing, unreadable or pre-#1354 document yields `null`.
- `suspend fun writeHistoryPosition(serverId, conversationId, position: HistoryPosition?): Result<Unit> = Result.success(Unit)` — `null` clears. Keeps the stored rows.

### `FileConversationCache`

- `CachedThread` gains `history: CachedHistoryPosition? = null`, so a document written before reads as rows with no position. `CachedHistoryPosition(cursor, atStart)` is private with a redacting `toString`.
- `writeThread` reads the stored position (a header-only decode of the same document; anything unreadable is no position) and writes it back beside the new rows. When the rows were trimmed at `MAX_CACHED_THREAD_ROWS`, it writes no position, because the oldest saved row no longer matches it.
- `writeHistoryPosition` reads the stored rows through the validated `decodeThread` path (unreadable is no rows, as `removeConversation` treats an unreadable metadata document) and writes them back with the new position. Clearing when no document exists writes nothing, so a clear never conjures a file.
- `readHistoryPosition` decodes the whole document through the validated path, so a document whose rows are unreadable has no position either.
- Both writes run inside one `mutate`, holding the instance mutex across read-modify-write, so the row writer and the position writer can never interleave and drop each other's half.
- `removeConversation` and `removeHost` delete the document, so the position goes with the rows for free.

### `ConversationRepository` / `CachingConversationRepository`

- `ConversationRepository` gains `suspend fun readHistoryPosition(conversationId): HistoryPosition? = null` and `suspend fun writeHistoryPosition(conversationId, position: HistoryPosition?) {}`. Inert defaults keep every double and the remote untouched.
- `CachingConversationRepository` forwards both to its cache under its own `serverId`. A write for an id in `deleted` is skipped (same reason as the row writer). A failed write logs `event=history_position_write_failed` and is not surfaced.
- `decorateRepository` and `TappingConversationRepository` forward by delegation already.

### `ThreadHistoryDemand`

- `fun restored(cursor: String, atStart: Boolean): ThreadHistoryDemand` — copy with the saved cursor, and `stoppedBy = AtStart` when `atStart`. Takes the two scalars rather than `HistoryPosition`, keeping the file's narrowing. `pagesLoaded` is carried, so seeding never resets the `MAX_HISTORY_PAGES` budget.
- The `MAX_HISTORY_PAGES` KDoc and `historyDemand`'s "Not persisted" KDoc are corrected: re-entering continues from the saved position; the cap stays per open.

### `ThreadViewModel`

- `historySeed: Job`, launched right after `historyDemand`: reads `repository.readHistoryPosition(conversationId)` and folds it with `restored`. Opening still asks nothing.
- `onDemandOlderHistory`: if `historySeed` has not completed, it launches a coroutine that joins it and re-enters, so a pull during the read asks with the saved cursor instead of the newest page. Extra pulls in that window collapse through `canAsk` once the first claims the slot.
- `launchHistoryAsk`: the ask becomes `val page = try { … } catch …` with every failure branch returning. On success it writes `HistoryPosition(page.cursor, page.atStart)` **before** folding `settled`, so the single in-flight slot also orders position writes. A failed ask writes nothing, so the saved position is unchanged. A refused cursor (`history.invalid_cursor` on a non-empty cursor) writes `null` along with `cursorRefused()`, so the next pull and the next open start from the newest page.
- A restored `atStart` sets `stoppedBy = AtStart`, so `canAsk` drops the pull and `tail(connected = false)` already returns `None`.

## State and concurrency model

- `historySeed` runs in `viewModelScope` and is cancelled with it. The default repository read returns without suspending, so for every in-memory repository the seed completes during construction.
- `historyDemand` keeps its CAS claim and `update` folds; the seed's fold is an `update`. No ask can be in flight before the seed completes, because `onDemandOlderHistory` defers until it does and retry needs a prior ask.
- Position writes happen inside the in-flight window, so at most one is outstanding per thread. The cache's mutex orders a position write against the concurrent row write from `observeMessages`.

## Error handling

- Cache reads are graceful (`null`). Cache writes return `Result`; `CachingConversationRepository` logs a static event on failure and the walk continues. A lost position write costs only a re-fetched page on the next open.
- The cursor is never logged, parsed or used as a path or key. New log lines are static: `event=history_position_write_failed`.

## Testing strategy

- `FileConversationCacheThreadTest`: position round-trips beside rows through a fresh instance; a row write keeps the position and a position write keeps the rows; a live-only thread has no position; a pre-#1354 document reads rows with no position; `null` clears; a trimmed row write drops the position; `removeConversation` and `removeHost` remove it.
- `CachingConversationRepositoryTest`: with a `FileConversationCache`, a position written through the repository survives a row write from `observeMessages`, and reads back under the wrapper's `serverId`; a deleted conversation's position write is skipped.
- `ThreadHistoryDemandTest`: `restored` sets cursor and the `AtStart` stop and keeps `pagesLoaded`.
- `ThreadViewModelTest` (extending `HistoryRepo` with a saved position, a gated read and recorded writes): opening asks nothing; with a saved position the first pull asks with its cursor, with none it asks `""`; a pull during a gated read asks with the saved cursor; a saved `atStart` asks nothing and shows no offline notice when disconnected; a settled page writes its position; a failed ask writes nothing; a refused saved cursor writes `null` and the next pull asks `""`.
- No device test and no real-Claude scenario: the change is a cache-backed data path whose screen states are already covered by `ThreadScreenHistoryTest`.

## Open Questions

- None.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/conversation-cache.md` — the saved history position: what sets it (a received page), what clears it (`history.invalid_cursor`, trimming), that a failed ask never changes it, and that it is removed with the thread document.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md` — the same, and correct the statement that leaving and re-entering a thread starts a fresh walk.

## Revisions

### 2026-10-02 — verifier rework on PR #1470

- **The trim rule now runs on the production path.** The verifier found that `CachingConversationRepository.observeMessages` handed `writeThread` its `cacheableThreadRows`, which had already taken the newest `MAX_CACHED_THREAD_ROWS`. `writeThread` therefore never saw a trim, so it kept a position that no longer matched the oldest saved row. New contract: `observeMessages` passes the drawn rows to `writeThread`, and `writeThread` applies `cacheableThreadRows` itself, as its KDoc already required. The `lastWritten` comparison stays on the cacheable rows, so an unchanged write is still skipped. The `ConversationCache.writeThread` KDoc now says that a caller must pass the untrimmed rows. The new test `a drawn thread trimmed at the row limit drops the saved history position` in `CachingConversationRepositoryTest` proves the rule through the repository. It fails against the old call. The test's `RecordingCache` now records `cacheableThreadRows` of what it is handed, which is what `writeThread` keeps, because the repository no longer pre-filters.
- **The gap paragraph in the `CachingConversationRepository` KDoc is corrected.** A gap above the cached base, left when more than one page arrived while the app was offline, is filled by the walk only when no position is saved. Once a position is saved, the first pull continues from rows older than the cached ones and never comes back to the newest page, so the gap stays until the position is cleared. The ticket requires resume-from-position, and desktop behaves the same way. Filling that gap, for example by asking for the newest page on open when the cached tail is stale, needs a product decision, and the PR raises it.
- **The order of the position write and the row write is accepted, not changed.** `launchHistoryAsk` saves the page's position as soon as `requestHistory` returns. The page's rows reach the document later, through the `observeMessages` collector. If that row write fails, or the ViewModel is cleared in between, the saved position runs ahead of the saved rows, and the next open skips that page. Ordering the two writes would couple the ViewModel to the collector's write. The window opens only on a failed or abandoned row write, and its cost is one page missing from the cache, recoverable once `history.invalid_cursor` clears the position or the thread is removed. Desktop saves coverage and rows together. The security review below records this as a finding.
- **The `security-sensitive` label requires a security review, and this rework adds it.** The original plan was committed after the label was applied but had no review.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The cursor is daemon-minted and crosses into the app inside a `history_page` frame. That frame is decoded in `HistoryPayloads` behind `OkHttpRelayTransport`'s `MAX_INBOUND_FRAME_CHARS` cap, so a stored cursor is bounded by one inbound frame. Past the decode, the cursor is only stored and sent back to the daemon in a later `request_history`. Mobile never parses it, renders it, or uses it as a path, a filename or a cache key. The thread document's path comes from `threadDocumentFor(serverId, conversationId)`, never from the cursor. A cursor that has been tampered with on disk reaches only the daemon. The daemon validates it and refuses it with `history.invalid_cursor`, and `launchHistoryAsk` then clears the saved position.
- [Tokens and secrets] No findings. The protocol's conversation-history section says the cursor "is not a secret and not a capability": the encoding is reversible, and the cursor is deliberately unsigned. Storing it in plaintext beside the rows therefore adds no credential to disk. The rows it sits beside are already stored in plaintext under the #797 cache's threat model.
- [Files and storage] No findings. The position is stored inside the existing thread document under `noBackupFilesDir/conversations` (`AppModule`), which is app-private and excluded from cloud backup. Writes go through `writeAtomically` inside `mutate`, so a kill mid-write leaves the previous document intact. `removeConversation` and `removeHost` delete the document, and the position goes with it. Older documents decode because `CachedThread.history` defaults to `null`.
- [Files and storage] Accepted, no change. A delete can bring the document back. `CachingConversationRepository.writeHistoryPosition` checks `deleted` before it takes the cache's mutex. If a `delete` and its `removeConversation` both complete between that check and the write, `writeHistoryPosition` rewrites the thread document with empty rows and a position. The row writer in `observeMessages` has the same check-then-write shape. The leftover file holds no message content, only an opaque cursor that is not a secret, and `removeHost` deletes it. Closing the race would mean moving the `deleted` check under the cache's mutex for both writers. That is out of proportion for orphaned bytes.
- [Android attack surface] No findings. No component, intent, deep link or WebView is added.
- [Cryptography] No findings. The ticket adds no randomness, primitives or key material, and the Noise session is untouched.
- [Network and I/O] No findings, with one bound restated. A restored position does not reset the per-open page budget. `ThreadHistoryDemand.restored` carries `pagesLoaded`, so a hostile daemon that never reports `atStart` is still stopped at `MAX_HISTORY_PAGES` per open. A restored `atStart` stops the walk with no request at all.
- [Errors, logs and telemetry] No findings. The cursor never reaches a log. `HistoryPosition.toString` and `CachedHistoryPosition.toString` both leave it out. The new log lines carry static codes only: `event=history_position_write_failed` and `conversation_cache operation=read_history status=failed code=…`. `position writes log no cursor` asserts this.
- [Concurrency] No findings. The row writer and the position writer read, modify and write the same document under the cache instance's one mutex, inside `mutate`, so neither drops the other's half. `historySeed` runs in `viewModelScope`, and a pull made before it finishes waits on it rather than asking with an empty cursor. At most one position write is outstanding per thread, because writes happen inside the single in-flight history slot.
- [Concurrency] Accepted, no change. A failed or abandoned row write can leave the position ahead of the rows (see Revisions). The cost is one page missing from the cache, with no exposure of content.
- [Threat model] A malicious relay sees only Noise ciphertext, so it can neither read nor plant a cursor. A hostile daemon can mint any cursor up to one frame. Mobile only stores it and sends it back, under the page cap above. Token theft from disk is unaffected, because the cursor is not a token. UI leakage: nothing renders the cursor.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-02
