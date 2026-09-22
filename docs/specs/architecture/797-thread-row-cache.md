# #797 — Keep a conversation's thread readable while it is disconnected

## Files read

- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt` → `ConversationCache`, `ConversationCacheException` — the contract this slice extends with a thread-row family; `removeConversation`'s KDoc already says a later family must extend it.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt` → `FileConversationCache` (`mutate`, `readOrEmpty`, `failureCode`, `hostDirectory`, `sha256Hex`), `CachedConversations`/`CachedConversation` — the versioned-envelope record shape, the temp-file-plus-atomic-move write and the graceful-read / reporting-mutation failure model this slice reuses verbatim.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt` → `mergeHistoryRows`, `alreadyHolds`, `holdsBoundary` — the one join per row kind; the restore is `live.mergeHistoryRows(restored)`, never a second dedup.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `ThreadItem` (`MessageItem`, `SessionBoundary`, `UnrecognizedMessage`), `BoundaryReason` — the rows to persist; `UnrecognizedMessage`'s KDoc forbids persisting it.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` → `Message`, `ToolCall`, `ToolCallStatus`, `Role` — `isStreaming` and `ToolCallStatus.Running` mark the in-flight rows that are never written.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `StableConversationRepository.observeMessages` — emits `emptyList()` between connections; the layer this slice wraps.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `hostConversationModule`, `ThreadDestinationFactory.repository` — the seam: wrap the stable repository before `decorateRepository`; the cache is resolved only when `useRelay`, the same rule `HostConversationSource.relay(get(), cache = get())` follows.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → the history walk (`requestHistory` → `settled(pageCursor, atStart)`) — reads only a page's `cursor`/`atStart`, so rows added at the `observeMessages` layer cannot tell it the log has started. Untouched.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `removeOwnEcho` — the live projection can *remove* a row (a dropped queued send's echo), which is why the merge base is the open-time restored snapshot, not an accumulating union (see Open questions).
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt` → `hostConversationModule(useRelay = true, decorateRepository = ::TappingConversationRepository)` — why the cache must sit under the hook.
- Test doubles: `InertConversationCache` (androidTest), `RecordingCache` in `HostConversationSourceTest`, `InertConversationCache` in `RelayConnectionFactoryTest`.
- `docs/knowledge/features/conversation-cache.md` — layout, failure model, "every persistence assertion reads through a second instance" testing lesson, which this slice's tests keep.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Conversation Thread Screen: top app bar, a column of assistant/user message bubbles, then the status row and the input bar. This ticket adds no visual: restored rows draw through the existing bubble, tool and delimiter composables, and the existing `ConnectionBanner` in its offline state stays above them. Visual fidelity is unchanged by design.

## Context

A thread's rows live in the connection-scoped `RemoteConversationRepository`; `StableConversationRepository.observeMessages` emits `emptyList()` between connections and nothing survives process death, so a thread read an hour ago opens blank offline. #795/#796 shipped the app-private cache (metadata) and its single Koin binding. This slice adds the thread-row family and the restore. No new binding, no new cache root. No ADR needed — it extends #795's decisions.

## Design

### Contract — `ConversationCache` gains a thread-row family

```kotlin
suspend fun readThread(serverId: String, conversationId: String): List<ThreadItem>
suspend fun writeThread(serverId: String, conversationId: String, rows: List<ThreadItem>): Result<Unit>
```

- Both have default bodies (`emptyList()` / `Result.success(Unit)`) on the interface, the precedent `ConversationRepository.refreshSessionSettings` sets, so the three inert/recording test doubles need no edit. `FileConversationCache` overrides both.
- `writeThread` is a whole-thread replace. It stores `cacheableThreadRows(rows)` — never the raw list.
- `removeConversation` and `removeHost` now also remove the thread family (KDoc updated).
- New top-level `fun cacheableThreadRows(rows: List<ThreadItem>): List<ThreadItem>` in `ConversationCache.kt`: drops `UnrecognizedMessage`, any `Message` with `isStreaming`, any message whose `toolCall?.status == Running`; then keeps the newest `MAX_CACHED_THREAD_ROWS` (= 200) by `takeLast` (the thread is in arrival order). One function, used by the cache (enforcement) and the wrapper (write-when-changed comparison), so the two can never disagree.

### Storage — `FileConversationCache`

- Layout: `<root>/<sha256hex(serverId)>/threads/<sha256hex(conversationId)>.json`. Under the host directory, so `removeHost`'s recursive delete covers it; the conversation id is hashed exactly like the server id, so it never enters a path.
- Envelope `CachedThread(version: Int, rows: List<CachedThreadRow>)`; `CachedThreadRow(message: CachedMessage? = null, boundary: CachedBoundary? = null)` — exactly one set. `CachedMessage(id, sessionId, role, content, timestamp: String, tool: CachedToolCall? = null)` (no `isStreaming`: restored rows are always settled); `CachedToolCall(toolName, input, output, status)`; `CachedBoundary(previousSessionId, newSessionId, reason, occurredAt: String, workspaceCwd: String? = null)`. All file-private. Enums serialize by name; `Instant` as ISO text like `lastUsedAt`.
- Read validation (any failure → `IllegalArgumentException` → `invalid_data` → empty, one coded log line, no repair): version match; each row has exactly one of `message`/`boundary`; no `Running` tool status; message ids distinct; boundary `(previous,new)` pairs distinct. The last two keep a tampered or buggy document from handing the `LazyColumn` duplicate keys.
- Write: same temp-file + `ATOMIC_MOVE` as `store`, via a small shared helper so both families use one write path. Under the same `Mutex` and `mutate`, so failures report `ConversationCacheException("conversation cache write_thread failed: <code>")` with no cause.
- `removeConversation`: existing metadata rewrite, plus delete of the thread document (absent → no-op; `delete()` false while still existing → `IOException` → `io`).

### Restore — `CachingConversationRepository` (new, `data/repository/`)

```kotlin
class CachingConversationRepository(
    private val delegate: ConversationRepository,
    private val cache: ConversationCache,
    private val serverId: String,
) : ConversationRepository by delegate {
    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>>
}
```

- Every other member delegates (Kotlin class delegation), so stall, queue, retry, compaction, thinking, usage limit, modals and one-shots keep their live-only behaviour — nothing restored can reopen a prompt or restart an indicator (AC 2).
- `observeMessages`: a cold `flow {}` that reads `restored = cache.readThread(serverId, conversationId)` **once** per collection, then collects `delegate.observeMessages(id)`; each emission `drawn = live.mergeHistoryRows(restored)` is emitted, then `cacheable = cacheableThreadRows(drawn)` is written iff it differs from the last-written set (initially `restored`, so an offline open writes nothing and `assistant_delta` churn writes nothing until the turn settles).
- A failed read is empty (the cache's contract), and because the read happens once, a later failure cannot blank rows already drawn. A failed write logs `event=thread_cache_write_failed` only; `lastWritten` is not advanced, so the next change retries.
- Emits before writing, so a disk write never delays a frame; cancellation of the collector cancels an in-flight write (atomic move keeps the old document).

### Wiring — `AppModule.kt`

- `ThreadDestinationFactory` gains `cache: ConversationCache?`; `hostConversationModule` passes `if (useRelay) get() else null`.
- `repository(...)`, non-demo branch: `decorateRepository(stable.cachedFor(serverId))` where the stable repository is wrapped in `CachingConversationRepository` when `cache != null && serverId.isNotEmpty()`. The hook's signature is unchanged; `TappingConversationRepository` sees the cached flow; the demo branch returns `fake` uncached.

## State + concurrency model

No scope is owned and nothing is launched. The per-collection state (`restored`, `lastWritten`) is local to the `flow {}` block, so two screens observing one conversation each hold their own; the cache's per-instance `Mutex` serializes their writes (last writer wins with a complete drawn set). Reads/writes run on the cache's injected IO dispatcher. Cancellation is the collector's (`viewModelScope`).

## Error handling

- Read: graceful empty (cache contract) — never an exception into UI state.
- Write: `Result` failure → one static log line, retried on next change.
- Nothing logs a row, a conversation id or a server id.

## Testing strategy

Unit only (JVM, `TemporaryFolder`, `runTest`):

- `FileConversationCacheTest` (extended) — reads through a **second** instance:
  - thread round-trips field-for-field (message, tool call Done/Failed, boundary with and without `workspaceCwd`);
  - unrecognized, streaming and running-tool rows are dropped on write; the stored set is bounded to the newest 200;
  - threads are isolated per conversation and per host; never-written reads empty;
  - truncated / wrong-version / duplicate-message-id / running-status documents read empty and are left on disk;
  - `removeConversation` removes that thread (and only that one), `removeHost` removes the host's threads;
  - no conversation id appears in any path or log line.
- `CachingConversationRepositoryTest` (new) — fake cache + a `MutableStateFlow`-backed delegate:
  - offline (delegate emits empty): draws restored rows verbatim, writes nothing;
  - reconnect: live newest page overlapping restored ids/boundaries merges with no duplicate key, restored older rows precede;
  - a streaming delta sequence writes only once the row settles; unchanged settled set writes nothing;
  - failed read → live rows only; failed write → next change retries;
  - non-thread flows are pure delegation (e.g. `observeStall` / `observeQueue` untouched by the cache).

No Compose UI test: restored rows draw through existing composables. Live behaviour (rung 3) is #673's, per the ticket; this slice lands no emulator scenario.

## Open questions

1. **Merge base: open-time snapshot or accumulating union?** Snapshot, as the ticket specifies. A union would resurrect rows the live projection deliberately removes (`removeOwnEcho` on a dropped queued send). Cost: after a mid-screen reconnect, rows that arrived during the previous connection and are older than the new newest page drop out of `drawn` until the walk re-pages — the same as today without a cache — and the cache is rewritten from that smaller set. Accepted; paging back restores them.
2. Blank `serverId` (a malformed route): no cache layer, so rows are never filed under the empty id's namespace.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/conversation-cache.md` — § The contract (thread-row family), § Layout (`threads/<sha256(conversationId)>.json`), § What's deliberately not here (remove the "No thread-row restore yet" bullet), § Testing; and `docs/knowledge/features/stable-conversation-repository.md` / `dependency-injection.md` for the caching layer under `decorateRepository`.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — rows persisted are already-decoded domain values; reading them back returns the same daemon-authored text, exactly as untrusted as live and rendered through the same length-bounded text path. The one new hazard — duplicate keys crashing the `LazyColumn` — is closed twice: the read rejects a document with duplicate message ids or boundary pairs (`FileConversationCache` thread decode), and the draw goes through `mergeHistoryRows`, the existing single join.
- [Trust boundaries] No findings — `UnrecognizedMessage` (unbounded, model-adjacent JSON) is dropped by `cacheableThreadRows` inside `writeThread` itself, not only by the caller, so no caller can persist one.
- [Tokens] No findings — no credential is stored; the document holds conversation content only, under the storage scope #795 chose for that reason.
- [File / storage] No findings — both identifiers become SHA-256 hex path components (no traversal, no id in the namespace); the thread document lives under `noBackupFilesDir` via the existing root, so it transfers exactly as far as the pairing credentials; writes are temp-file + `ATOMIC_MOVE`. No encryption at rest, per #795's accepted residual (conversation content is not a credential).
- [File / storage] SHOULD FIX (accepted, noted) — a hostile daemon can make single message contents large; the document is bounded by row count (200), not bytes. Its size is bounded by what the live projection already holds in memory for that thread; a byte cap is deferred until observed.
- [Inter-process] No findings — no component, intent or deep link added.
- [Crypto] No findings — SHA-256 via `MessageDigest` for namespace derivation only.
- [Network & I/O] No findings — no network path changes; only `observeMessages` gains a local layer.
- [Logs] No findings — the wrapper logs one static event name on write failure; the cache keeps its static operation/code lines; no row, conversation id or server id is logged, asserted in tests.
- [Concurrency] No findings — no scope owned; per-collection state is flow-local; the cache's single `Mutex` serializes both families; the factory binds one server id per destination, so host A's rows can never be written under host B.
- [Threat model] No findings — restored rows can never become interactive state: modals, thinking, stall, queue, retry and compaction stay live-only by delegation, and running tools / streaming rows are never written and are rejected on read. Removal on unpair / permanent delete is wired by #798 (OUT OF SCOPE here; this slice makes the cache operations cover the family).

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
