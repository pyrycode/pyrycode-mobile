# #877 — Track each conversation's attention state per host

## Files read

- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSource`, `HostConversationConnection`, `Held`, `update`, `reconcile` — the app-scoped, per-host owner this ticket extends. It already runs one job per host, retires the host when its bundle is replaced, and restores from the cache without letting a stale entry publish.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `reconcile` — the one place a relay `HostConversationConnection` is built from a host's `RelayRepositoryCoordinator`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `liveSessionEvents`, `currentModal`, `questionBatches`, `currentRepository` — the per-host sources. `currentModal` survives a reconnect. `questionBatches` empties on teardown. `currentRepository` is null between connections.
- `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt` → `TurnState`, `TurnEnd` — the running and completion signals. `TurnEnd.turnId` is the only per-turn identity on the stream. No `event_id` is surfaced.
- `app/src/main/java/de/pyryco/mobile/data/model/ModalUiState.kt` → `ModalUiState.Open.conversationId` (#816) — blank means the prompt belongs to no row.
- `app/src/main/java/de/pyryco/mobile/data/model/QuestionBatch.kt` → `batchFor` — the per-conversation lookup.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/TurnOutcomeIndicator.kt` → `turnOutcomeReport`, `TurnOutcomeReport.Kind` — the #805 classification. `Failed` and `StoppedEarly` count as failed. `Interrupted` does not.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt`, `FileConversationCache.kt` → `removeHost`, `removeConversation`, `writeAtomically`, `hostDirectory` — host-keyed, app-private (`noBackupFilesDir`), hashed paths and atomic writes. `removeConversation`'s KDoc requires a new per-conversation family to extend it.
- `app/src/main/java/de/pyryco/mobile/di/ObservablePairedServerStore.kt` → unpair calls `ConversationCache.removeHost`, so read positions under the host directory are dropped with the host.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `hostState`, `observeHostEntry`, `onHostRowTapped`. A new snapshot re-subscribes every preview flow through `onStart { emit(null) }`, so attention must not travel on `HostConversationSnapshot`. Otherwise every `turn_state` would blank the recent-chat previews.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `viewModel { … thread … }` binding and `ThreadDestinationFactory.thread`.
- `../pyrycode-desktop/src/renderer/src/store/conversationStatus.ts`, `conversationLastReadStore.ts` — the precedence order and the per-installation read-mark approach this ticket mirrors.
- `../pyrycode/docs/protocol-mobile.md` § Reconnect replay & resync — replay re-sends only `event_id > last_event_id`. A cold process advertises no cursor. `history_page` entries never reach `liveSessionEvents`.

## Design source

**Figma:** N/A — the ticket has no Figma section. This slice changes view-model state only, and drawing the state is a separate blocked ticket. No visual-fidelity check applies.

## Context

The tree's rows carry no activity state. This slice gives every row on every host exactly one `ConversationAttention` value, in desktop's order with mobile's extra **Failed** level: WaitingForAnswer, Running, Failed, Unread, Idle.

**Overlap with #818 (In Code Review, PR #903).** #818 edits one line of `ThreadDestinationFactory.thread` in `AppModule.kt`. This ticket edits only the `viewModel { … thread … }` binding in `appModule`, which is more than a hundred lines away. This is a known deviation from § A2's block-on-any-overlap rule. The rule exists to prevent merge conflicts, and two separate hunks do not conflict. I will prove the result with `git merge-tree` against `origin/feature/818` before opening the PR and record it there.

An ADR is not needed. The read mark is a client-side fiction, the same decision desktop recorded.

## Design

### Types (new file `di/ConversationAttention.kt`)

- `enum class ConversationAttention { WaitingForAnswer, Running, Failed, Unread, Idle }` is declared in precedence order.
- `fun resolveAttention(waiting: Boolean, running: Boolean, failed: Boolean, unread: Boolean): ConversationAttention` is total and has parameter order = precedence.
- `internal data class HostAttentionState(running: Set<String>, failed: Set<String>, positions: Map<String, ReadPosition>, counted: Map<String, List<String>>)` is the pure fold for one host, keyed by conversation id:
  - `onEvent(event: LiveSessionEvent, viewing: Boolean): HostAttentionState`
    - `TurnState` Thinking or Responding adds the conversation to running and removes it from failed ("its next turn starts").
    - `TurnState` Idle removes the conversation from running.
    - `TurnEnd` removes the conversation from running. If `turnId` is blank or over `MAX_TURN_ID_CHARS`, the fold stops there. It also stops if the turn is already **counted**: it is in the bounded per-conversation `counted` list, or equals the stored position's `completedTurnId` or `readTurnId`. Otherwise it records the turn as counted. If `viewing` is true, it sets `ReadPosition(T, T)`. If not, it sets `completedTurnId = T` and keeps the prior `readTurnId`. If `viewing` is false and `turnOutcomeReport(event)?.kind` is `Failed` or `StoppedEarly`, it adds the conversation to failed. If `viewing` is true or the turn succeeded, it removes the conversation from failed.
    - Every other event is a no-op.
  - `opened(conversationId)` removes the conversation from failed and sets `readTurnId = completedTurnId` on its position, if there is one.
  - `disconnected()` clears running and nothing else.
  - `restored(stored: Map<String, ReadPosition>)` merges stored positions under the live ones. Live wins.
  - `resolve(modal: ModalUiState, batches: List<QuestionBatch>): Map<String, ConversationAttention>` returns only **non-Idle** entries. WaitingForAnswer comes from `Open` with a non-blank matching `conversationId`, or from `batches.batchFor(id) != null`.
- `counted` is capped at `MAX_COUNTED_TURNS_PER_CONVERSATION` (16), newest last. `positions` is capped at `MAX_READ_POSITIONS` (1000) per host, and the oldest insertion is dropped first.

### Read-position storage (`ConversationCache`, `FileConversationCache`)

- `data class ReadPosition(val completedTurnId: String, val readTurnId: String?)` has `val unread get() = readTurnId != completedTurnId`. No stored entry means read.
- `ConversationCache.readReadPositions(serverId): Map<String, ReadPosition>` is graceful like `readConversations`, and defaults to `emptyMap()`.
- `ConversationCache.writeReadPositions(serverId, positions): Result<Unit>` replaces the host's whole set and defaults to success. Test doubles are untouched.
- The file implementation stores `<root>/<sha256(serverId)>/read-positions.json` as a versioned **array** of `{conversation_id, completed_turn_id, read_turn_id}`. Duplicate ids reject the document. It uses the existing mutex, `mutate` and `writeAtomically`. `removeHost` already deletes the directory. `removeConversation` also drops that conversation's entry.

### Per-host plumbing

- `HostConversationConnection` gains `liveSessionEvents: Flow<LiveSessionEvent> = emptyFlow()`, `modal: StateFlow<ModalUiState>` (Hidden) and `questionBatches: StateFlow<List<QuestionBatch>>` (empty). Defaults keep the demo path and every test constructor unchanged. `RelayConnectionRegistry.reconcile` passes the coordinator's three flows.
- `HostConversationSource`:
  - `val attention: StateFlow<Map<String, Map<String, ConversationAttention>>>` is keyed by `serverId`, then by conversation id, with non-Idle entries only. It is published beside `snapshots` from `Held` state: `attention`, `modal`, `batches` and `positions` (`MutableStateFlow<Map<…>?>`, null until restored).
  - For each new `Held`, it launches collectors under `entry.job`:
    1. Folds `liveSessionEvents` through `onEvent`, with `viewing` read under the monitor.
    2. Folds `repositories` null emissions into `disconnected()`.
    3. Combines `modal` and `questionBatches` into the entry.
    4. When a cache is present, reads the stored positions, folds `restored`, and then writes each distinct positions map after the restore completes. Without a cache, positions are in-memory only.
  - Every mutation goes through one `@Synchronized` `updateAttention(entry, change)`. It uses the same staleness guard as `update`, factored into `isCurrent(entry)`, so a retired bundle cannot publish or write.
  - `markOpened(serverId, conversationId)` folds `opened` into that host only.
  - `viewConversation(serverId, conversationId): Closeable` increments a viewing multiset keyed by `(serverId, conversationId)` and calls `markOpened`. `close()` decrements the count and is idempotent. Blank ids return a no-op handle.
- `ChannelListViewModel`:
  - `HostChannelListEntry` gains `attention: Map<String, ConversationAttention> = emptyMap()` and the total `attentionFor(conversationId): ConversationAttention` with an Idle default.
  - `hostState` combines the projected entries with `hostSource.attention` at the entry level. Attention changes therefore never restart preview flows.
  - `onHostRowTapped` calls `hostSource.markOpened`.
- DI: the thread `viewModel { }` binding adds `hostSource.viewConversation(serverId, conversationId)` to the `ThreadViewModel` through `ViewModel.addCloseable`. Viewing lasts as long as the thread's ViewModel. `ThreadViewModel` itself is unchanged.

## State + concurrency model

- All state stays in `HostConversationSource`'s existing `SupervisorJob` scope on the injected dispatcher. Per-host collectors run under `entry.job`, which `reconcile` cancels when the host's bundle is replaced or removed and `dispose` cancels with the scope.
- Mutations are synchronous under the class monitor. `viewing`, the fold and the publish are atomic with respect to each other. The cache read suspends outside the monitor, as the existing restore does.
- Persistence is one collector per host over a `StateFlow`, so bursts conflate. Writes are sequential per host.
- Background: the lifecycle driver closes supervisors, so `currentRepository` goes null, the collector runs `disconnected()`, and running clears. Positions and failed survive in memory. Positions also survive on disk.

## Error handling

- A cache read failure yields empty. The conversation starts read, and no failure is surfaced.
- A cache write failure is logged with an event and a static code and the write is dropped. The in-memory state stays authoritative for the session.
- A malformed `turnId` (blank or oversized) is not counted. Running still ends.
- Nothing reaches UI state as an error. Attention is a derived display value.

## Testing strategy

JVM unit tests with `runTest` and `UnconfinedTestDispatcher`:

- `ConversationAttentionTest` covers the pure fold and resolver:
  - One adjacent precedence pair each: Waiting over Running, Running over Failed, Failed over Unread, Unread over Idle.
  - A blank-id modal counts for no row. A question batch sets WaitingForAnswer.
  - An interrupted `TurnEnd` does not count as failed. `StoppedEarly` does.
  - A turn completed while viewing is not unread.
  - `opened` clears unread and failed. A next-turn start clears failed.
  - A re-delivered `TurnEnd` does nothing, whether it is in the counted list or equals a stored position. A re-delivered, already-read turn stays read.
  - `disconnected` clears running only. `restored` lets live win.
- `HostConversationSourceAttentionTest` covers two fake hosts through `HostConversationConnection` flows:
  - Two hosts share a conversation id, and each keeps its own state.
  - Opening on host A leaves host B's unread.
  - One host's repository goes null: its running clears, its snapshot keeps separate `relay` and `pyrycode` legs, and the other host's legs and attention are unchanged.
  - Re-delivery after a reconnect (repository null, then a new repository, then the same `TurnEnd`) marks nothing.
  - Positions persist through a recording cache and are restored by a new source. That is the restart case.
  - A viewing handle suppresses unread, and closing it re-enables unread.
- `FileConversationCacheReadPositionTest`:
  - Round trip.
  - The host is isolated, and `removeHost` drops the positions.
  - `removeConversation` drops one entry.
  - A corrupt or duplicate document yields empty.
- `HostChannelListViewModelTest`: one case where a row's `attentionFor` reflects the source, and one where `onHostRowTapped` clears unread.
- There is no rung-3 scenario. The ticket is not operator-facing yet, because drawing the state is the blocked follow-up. The live acceptance belongs to that ticket and #676.

## Documentation handoff

None named by the ticket. The documentation stage may fold the attention model into `docs/knowledge/features/channel-list-viewmodel.md` and `conversation-cache.md`. That is pending for the documentation stage.

## Open questions

1. *Is a turn first delivered in a reconnect replay tail "re-delivered"?* A tail turn is one the phone never saw, and the stream carries no replay flag to tell it apart. The resolution is that dedup is by `turnId` identity, so a turn seen before never counts again. A tail turn the phone never saw counts like a live completion, because it did complete while the operator was not viewing. The connection transition itself changes nothing except running. History pages never touch the fold.
2. *Should failed survive a restart?* No. The ticket asks only for read positions to persist, and failed is in memory.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. Conversation ids and turn ids are daemon-authored, and they are used only as equality keys: map keys in memory and JSON array values on disk. They are never used as a path, a URL, a log field or rendered text. `resolveAttention` returns a client-owned enum, so no daemon text reaches Compose through this slice.
- [Tokens] No findings. No credential is read, stored or logged. Read positions hold turn ids only.
- [File / storage] SHOULD FIX, addressed in the design. A hostile daemon could mint unbounded conversation ids or huge turn ids to grow the document. Bounds: `MAX_TURN_ID_CHARS`, a blank or oversized turn id is not counted, `MAX_READ_POSITIONS` per host and `MAX_COUNTED_TURNS_PER_CONVERSATION`. Paths remain `sha256(serverId)` plus a fixed file name, so no id reaches the filesystem namespace. Storage is `noBackupFilesDir` through the existing binding, and writes are atomic through `writeAtomically`. `removeHost` covers unpairing, and `removeConversation` is extended per its contract.
- [Android surface] No findings. No new intent, deep link, push or WebView.
- [Crypto] No findings. `MessageDigest` SHA-256 path hashing is reused, and no new primitive is added.
- [Network & I/O] No findings. There is no new frame and no new send. The existing inbound flows are read only.
- [Logs] SHOULD FIX, to verify in Phase B. New log lines carry event names, counts and static codes only. They must never carry a conversation id, turn id, modal text or batch text.
- [Concurrency] No findings. Collectors are scoped to `entry.job`, which is cancelled on bundle replacement and dispose. `isCurrent` stops a retired entry from publishing or persisting. Mutations are atomic under the monitor. The viewing handle's `close` is idempotent, so a double close cannot drive the count negative and suppress another screen's viewing.
- [Threat model] Hostile relay: it can drop or delay events, so attention can be stale. That is only a display fault, and running clears on disconnect. Hostile daemon frame: bounded as above. A daemon can mark any conversation unread or failed, which it could already do by sending messages. Token theft from disk: read positions are not secrets. OUT OF SCOPE: rendering the state (the blocked drawing ticket) and live acceptance (#676).

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
