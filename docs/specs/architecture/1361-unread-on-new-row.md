# #1361 Mark a chat unread when any new row arrives

## Files read

- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt`: `HostAttentionState` (`onEvent`, `completed`, `isCounted`, `opened`, `boundedPositions`) and the `ConversationAttention.Unread` KDoc. The fold gains one step.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `launchAttention`, `updateAttention`, `Held`. Gains the row-count collector.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt`: `threadByConversation` and `observe`. Every row a thread appends goes through this one store, including the attachment-offer row `AttachmentOfferProjection.apply` appends (#983), so its per-conversation list size is the row count.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: the interface. A defaulted member keeps `FakeConversationRepository`, `StableConversationRepository` and the test fakes untouched. `CachingConversationRepository` delegates with `by`, so it forwards the new member.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: owns `threadProjection`; overrides the new member.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt`: `ReadPosition`, two equality tokens. Its shape and `FileConversationCache`'s document stay unchanged.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: the thread destination opens its `ConversationViewing` view for the view model's lifetime; `observeMessages` backfill and `requestHistory` pages land while it is open.
- `docs/knowledge/features/dependency-injection-host-conversation-source.md` § "Attention state (#877)" and § "Attention alerts (#685)": the alert is keyed on `counted` growing on a `TurnEnd`, which this ticket leaves alone.

Overlapping in-flight branches: #1350, #1351, #1355 (`ThreadProjection`), #1343, #1351, #1410 (`RemoteConversationRepository`), #1343, #1353, #1359, #1410, #1411 (`ConversationRepository`). All are additive elsewhere in those files; my edits are one new member each, so no dependency.

## Context

Unread moves today only when a live `TurnEnd` is counted. Desktop (`isConversationUnread` with `stampLastReadFor`) counts thread rows: a conversation is unread when its thread holds rows the user has not seen. This ticket moves mobile's trigger to new rows while keeping mobile's storage, its `TurnEnd` alert and its failure mark. No decision record needed; the trigger change is described in the feature overview by the documentation stage.

## Design

**Row counts.** `ThreadProjection.observeRowCounts(): Flow<Map<String, Int>>` maps `threadByConversation` to each conversation's list size, `distinctUntilChanged`. Growth of an existing row (a delta into the same bubble, a tool result, a finalize, a denial, progress) changes no size, so it emits nothing new. `ConversationRepository.observeThreadRowCounts(): Flow<Map<String, Int>> = emptyFlow()` is the defaulted member; `RemoteConversationRepository` returns `threadProjection.observeRowCounts()`.

**Fold.** `HostAttentionState.rowsAdded(conversationId: String, viewing: Boolean, token: String): HostAttentionState`:

- viewing: unchanged (the view already opened it, and opening is what reads it);
- already unread (position present with `completedTurnId != readTurnId`): unchanged, so a stored turn id stays put for the cold-process redelivery check in `isCounted`;
- otherwise: position becomes `ReadPosition(token, previous readTurnId)`, moved to the newest end of the bounded map as `completed` does.

`token` is client-minted per unread-making change (a random UUID string minted by `HostConversationSource`), so it can never equal a stored read token or a daemon turn id. The fold stays pure: the token is an input. `completed` is unchanged: a counted `TurnEnd` not viewed still marks unread (desktop counts a turn end as a row), alerts once through `counted`, and stores its turn id.

**Collector.** In `launchAttention`, one more job under the entry: `connection.repositories.collectLatest { repository -> ... }` keeps `seen: Map<String, Int>` starting **empty for each repository**, because a repository's `ThreadProjection` starts empty per connection, so rows already there when the source first reads the counts (replay landing first) are new. For each emission, the ids whose count exceeds `seen[id] ?: 0` are folded through `rowsAdded` with `viewing.isViewing(serverId, id)` and a fresh token, in one `updateAttention`; then `seen = counts`. A null repository resets nothing else (the existing collector handles `disconnected`).

## State and concurrency model

The new job is a child of `Held.job` on the source's dispatcher, cancelled with the entry or `dispose`; `collectLatest` cancels the previous repository's collection on a replacement. `seen` is local to the repository's collection, so it needs no lock. The fold runs under `updateAttention`'s monitor, which also guards against a retired entry. The count flow is a cold map over a `StateFlow`, so conflation can merge two appends into one emission; the comparison is by size, so the result is the same.

## Error handling

No new failure modes: the flow cannot throw (a map over a `StateFlow`), and the default member is an empty flow for the demo and fakes. Nothing logs a conversation id; the fold does not log.

## Testing strategy

`ConversationAttentionTest` (pure fold):

- a row in a background conversation is unread; a row while viewing changes nothing; a second row while already unread keeps the position (and a stored turn id) unchanged; opening reads it; the next row after opening is unread again with a token different from the read one.
- a `TurnEnd` that follows rows still alerts-counts (`counted` grows) and stays unread; a `TurnEnd` with no rows still marks unread (existing test).

`HostConversationSourceAttentionTest` with a fake repository exposing a `MutableStateFlow<Map<String, Int>>` of counts:

- growth of a background conversation's count marks it unread before any `TurnEnd`; an unchanged count (in-place growth) on a read conversation stays read.
- a viewed conversation whose count grows (the backfill on opening) stays read; opening an unread one reads it; positions written by a row survive a restart through `MemoryCache` and a pre-change `ReadPosition("t1", null)` still restores as unread (existing restart test).
- a repository replacement: a new repository whose first emission already holds rows for a background conversation marks it unread; a read conversation absent from the new counts stays read; a viewed conversation whose thread is backfilled in the new repository stays read.
- the existing alert and failure tests keep passing unchanged.

Run: `./gradlew testDebugUnitTest --tests "de.pyryco.mobile.di.ConversationAttentionTest" --tests "de.pyryco.mobile.di.HostConversationSourceAttentionTest" --tests "de.pyryco.mobile.data.repository.*Thread*"`.

Not operator-facing in a new way (the dot already exists, no visual change), so no rung-3 scenario.

## Open Questions

- Does any existing test assert a stored token from a row path? None today; the new ones assert unread/read state rather than the random token.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/dependency-injection-host-conversation-source.md` § "Attention state (#877)": unread by new thread rows rather than by turn completion, which rows count (every append to the thread store; in-place growth does not), the client-minted token, and the zero baseline for a new connection's repository.
