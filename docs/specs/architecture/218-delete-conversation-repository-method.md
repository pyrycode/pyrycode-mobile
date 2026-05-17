# spec: `ConversationRepository.delete(conversationId)` (#218)

XS data-layer slice. One new `suspend` interface method + one Fake override + tests. No UI; the Channel Info host (separate slice, deferred from #144) consumes this surface.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:11-101` — interface contract; docstring style, mutator shape, default-body precedent for cascade-escape members
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:142-160` — `archive` + `unarchive` impls, the closest neighbours by shape; new `delete` slots immediately under them
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:23-62` — class header + storage shape (`MutableStateFlow<Map<String, ConversationRecord>>`) + observer projections (note the tolerant `records[id] ?: …` fallbacks in `observeMessages` and `observeLastMessage`)
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:330-408` — `archive` test cluster (`archive_movesConversation_…`, `archive_onUnknownId_throws`, `archive_isIdempotent`); copy the style, swap the unknown-id assertion
- `docs/knowledge/features/conversation-repository.md:49-56` — Conventions block, especially the "Default-implementations are reserved for the cascade-escape valve" rule and the "Failures throw" convention (this spec deviates from the failure-throw default for `delete`; § Design records why)

## Context

The interface today exposes `archive` / `unarchive` (soft, reversible) but no permanent-delete surface. Phase 0 needs a destructive counterpart so the Channel Info sheet's Delete action — and any future delete entry point — has a repository call to make instead of a stub. Pure data-layer slice; no UI is touched here.

## Design

### Interface change

Add one new `suspend` member to `ConversationRepository`, in the `archive` / `unarchive` neighbourhood (between `unarchive` and `rename` is the natural slot — keeps the lifecycle-mutator cluster together).

**Signature:**

```kotlin
suspend fun delete(conversationId: String): Unit =
    error("delete is not implemented for this ConversationRepository")
```

Default body uses the `error("…")` cascade-escape shape (precedent: `createWorkspaceFolder`, conversation-repository.md:53). Two reasons it's a default-body member, not abstract:

1. **Cascade pressure.** 11 anonymous `object : ConversationRepository` test fakes across 5 files (see § Background — fake-cascade map) would otherwise need to add a stubbed `override`. None of them invoke or care about delete; making them write a stub buys nothing.
2. **`error("…")` shape, not silent no-op.** Delete is destructive — a test fake that silently no-ops would hide bugs (test wires a fake expecting delete to do something, gets silent success, the test passes vacuously). `error("…")` surfaces accidental invocation loudly. The tolerant "no-op on unknown id" semantic belongs in the concrete `FakeConversationRepository`, not in the interface default.

**KDoc (write the prose; one short paragraph + the failure-mode line):**

- Lead: "Permanently removes the conversation from the store. Tolerant of unknown ids: calling `delete` on an id that is not present is a silent no-op."
- Contrast line: "Unlike [archive] and [unarchive], which throw `IllegalArgumentException` on unknown ids, `delete` converges on the post-condition — after a successful return, the conversation is not in `observeConversations`."
- Stream-effect line: "Streams collected for the deleted conversation re-emit the empty projection (`observeMessages` → `emptyList()`; `observeLastMessage` → `null`); they do not complete."
- Cascade-escape default line (mirror `createWorkspaceFolder`'s phrasing): default throws — implementations that do not support deletion inherit the default; the Channel Info sheet is the only production consumer; test fakes never invoke this method, so the throwing default is unreachable in tests today.

### `FakeConversationRepository` override

One-liner. Implementation shape:

```kotlin
override suspend fun delete(conversationId: String) {
    state.update { it - conversationId }
}
```

Why this shape:

- **`Map - key` is silently tolerant of unknown keys.** Returns an `equals`-identical map when the key is absent → `MutableStateFlow.update` does not re-emit. Same free idempotency as `archive`'s `record.copy(... archived = true)` shape (conversation-repository.md:92).
- **No `unknown(id)` throw.** Deliberate deviation from `archive`/`unarchive`/`rename`/`mintNewSession`/`sendMessage` — see contract KDoc above. The deviation is recorded in the KDoc, not silently encoded.
- **No `bumpWorkspace`.** Delete removes the conversation from the store; the `recents` stream is not affected. (Workspaces are a per-conversation cwd, not a per-conversation-lifecycle concern; `archive`/`unarchive` also don't touch `recents`.)
- **No `lastUsedAt` bump.** Mirrors the `archive`/`unarchive` decision: delete is a lifecycle transition, not usage.

Place the override immediately below `unarchive` (currently FakeConversationRepository.kt:152-160). KDoc on the impl can be a one-liner — defer the contract text to the interface KDoc; the impl note just says "see contract".

### State + concurrency model

- **One atomic CAS** via `state.update { it - conversationId }`. No `Mutex`, no `lateinit var` (no entity to return). Matches the existing `archive` / `unarchive` shape.
- **No coroutine launching.** Method body is synchronous, completes in one update. `suspend` for interface consistency only.
- **Dispatcher-agnostic.** No `flowOn`, no `withContext`. Same as every other mutator on this class.
- **Observer convergence is automatic.** The single `MutableStateFlow<Map<String, ConversationRecord>>` is the source of truth for all three projections:
  - `observeConversations(...)` → `records.values` no longer includes the deleted id → re-emits the new list.
  - `observeMessages(deletedId)` → `records[deletedId] ?: return@map emptyList()` (FakeConversationRepository.kt:53) → re-emits `emptyList()`.
  - `observeLastMessage(deletedId)` → `records[deletedId]?.messages?.maxByOrNull { it.timestamp }` (FakeConversationRepository.kt:59) → re-emits `null`.

The AC's "the architect resolves whether to complete the flows or just re-emit empty" question is resolved as **re-emit empty** — the flows do not complete. Rationale:

- All three projections are `state.map { … }` over the long-lived `MutableStateFlow`. The map's lifetime is the collector's scope; only the collector can end the flow.
- Calling `take(1)`/`first()` on a flow that *completes* on deletion is observably the same as on one that *re-emits empty* — both yield the empty/null value. But long-lived collectors (e.g. a `StateFlow`-backed `UiState` projection in a ViewModel whose screen happens to still be in composition during a race) would, under "complete", silently lose their subscription and never recover. Re-emit-empty is the safer convergence shape and matches how `observeMessages` already handles "unknown id" today.
- No code change is needed to achieve this — it's a property of the existing projection scaffolding. The spec records the decision so the developer doesn't write a redundant `.takeWhile { … }`.

### Error handling

- **Unknown id: silent no-op.** No exception thrown. Documented in KDoc; asserted in tests (§ Testing strategy, test 2).
- **No other failure modes.** In-memory `Map` subtraction is total; no I/O, no parse, no permission boundary.

## Testing strategy

Unit tests live in `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt`, alongside the existing `archive_*` cluster (test file lines 330-408 are the shape reference). Use `runBlocking { … }` and the same `repo.observeConversations(...).first()` style as the existing tests; do not introduce `runTest` or new harness types.

**Test scenarios** (write each as a single `@Test fun` in the established style):

1. `delete_removesConversation_from_observeConversations_All` — create a discussion via `repo.createDiscussion()`, assert it appears in `ConversationFilter.All`, call `repo.delete(created.id)`, assert it no longer appears in `All` (`none { it.id == created.id }`). Covers AC test 1.

2. `delete_onUnknownId_doesNotThrow` — invoke `repo.delete("nope")` from `runBlocking { … }` with no try/catch; success is the absence of exception. Style note: do NOT mirror `archive_onUnknownId_throws`'s try/catch wrapper — for the "does not throw" case, just call it and let the test pass if no exception escapes. Covers AC test 2.

3. `delete_isIdempotent` — create, delete twice, assert the conversation is absent from `All` (`none { it.id == created.id }`). Matches the `archive_isIdempotent` shape (test file line 395). Anchors the equals-identical-map free idempotency in the test suite.

4. `delete_causes_observeMessages_toReEmitEmpty` — use a seeded channel that has messages (`seed-channel-pyrycode-mobile` has 6 messages across 2 sessions). Assert `observeMessages("seed-channel-pyrycode-mobile").first().isNotEmpty()` before delete, call `repo.delete("seed-channel-pyrycode-mobile")`, then assert `observeMessages("seed-channel-pyrycode-mobile").first() == emptyList<ThreadItem>()`. Anchors the re-emit-empty (not flow-complete) decision from § State + concurrency model.

5. `delete_causes_observeLastMessage_toReEmitNull` — same seed channel, assert `observeLastMessage(id).first() != null` before, `repo.delete(id)`, assert `observeLastMessage(id).first() == null` after. Symmetric to test 4.

Tests 1-3 are mandatory (AC + idempotency convention). Tests 4-5 are mandatory too — they anchor the architect-resolved "re-emit empty, don't complete" decision; without them the decision is just prose. Total: 5 small tests, ~30-50 LOC.

No instrumented tests. No fakes/mocks beyond `FakeConversationRepository` itself.

## Background — fake-cascade map

For developer reference (do not edit these files in this ticket; they inherit the new `delete` default and need no override):

- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt:279`
- `app/src/test/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModelTest.kt:59,366,407,497`
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModelTest.kt:56,354,395,444`
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt:420,470`
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:131`

(Eleven sites, five files — matches the count documented in conversation-repository.md:53.)

## What this ticket deliberately does not do

- **No UI wiring.** Channel Info's Delete action is the host ticket's concern (deferred from #144); this slice only adds the repository surface.
- **No active-subscriber coordination.** Per the ticket's Technical Notes, the host handles thread-screen navigation pop when the open conversation is deleted.
- **No tombstone marker / undo-delete.** `delete` is destructive; the soft-delete reversible action is `archive`. No `undelete` symmetric to `unarchive`.
- **No batch delete.** Add `deleteBatch(ids)` if and when a real use case needs it (matches the "no `archiveBatch`" deliberate-absence rule in conversation-repository.md:101).
- **No Phase 4 remote semantics.** Phase 4's Ktor-backed impl will introduce its own not-found / network-error handling for the network case; that's a separate ticket. For Phase 0, the contract is "tolerant of unknown id, in-memory only".

## Open questions

None for this slice. The AC explicitly delegated the "complete vs re-emit" decision to architect, and § State + concurrency model resolves it as "re-emit empty" with rationale.
