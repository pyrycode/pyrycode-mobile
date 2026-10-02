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
