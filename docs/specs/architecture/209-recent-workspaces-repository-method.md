# 209 — `recentWorkspaces()` on `ConversationRepository`

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:18-67` — the interface this ticket extends. Note the existing shape: cold `Flow` reads + `suspend` one-shot mutators.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:23-32` — the `state: MutableStateFlow<Map<String, ConversationRecord>>` storage model. Recents will be a sibling `MutableStateFlow<List<String>>` next to it.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:87-250` — the five cwd-affecting mutators (`createDiscussion`, `promote`, `changeWorkspace`, `startNewSession`, plus `mintNewSession`). Each is the bump point for the new state.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:266-437` — `SEED_RECORDS` / `buildSeedRecords`. The initial recents list is derived from these seeds.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:1-21` — the `Conversation` data class and the `DEFAULT_SCRATCH_CWD` sentinel (`"~/.pyrycode/scratch"`).
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:1-60` — test conventions: `runBlocking`, `flow.first()`, JUnit 4 assertions, no Turbine.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:130-169` — the shape of an anonymous `object : ConversationRepository` fake in the existing test suite. Read this to confirm the default-impl decision in § Design is correct: these fakes will inherit the default and need **no edits**.

## Context

Ticket #205 (Workspace Picker bottom sheet) needs a data surface listing recently-used workspace folders. This ticket adds the **read side**: a single new method on `ConversationRepository`. The write side (`createWorkspaceFolder`, an explicit pin) ships separately and depends on this one's storage shape landing first.

Phase 0 is fake-backed; only `FakeConversationRepository` implements meaningful behavior. A future `RemoteConversationRepository` (Phase 4) will satisfy the same contract against the mobile API.

## Design

### Interface change

Add one method to `ConversationRepository`:

```kotlin
/**
 * Workspace folders previously bound to any conversation, deduped and ordered
 * most-recent-first by the latest cwd-affecting write across all conversations.
 *
 * Excludes "no bound workspace" cwds: the empty string `""` and
 * [DEFAULT_SCRATCH_CWD]. Cold flow; re-emits on every state change.
 *
 * Default returns an empty flow — implementations that do not track workspace
 * history (e.g. test fakes that ignore this surface) inherit the default and
 * do not need to override.
 */
fun recentWorkspaces(): Flow<List<String>> = flowOf(emptyList())
```

**Why a default implementation.** Adding `recentWorkspaces` as an abstract method forces a `TODO("not used")` stub into each of the 11 anonymous `object : ConversationRepository` fakes across 5 test files (`SettingsViewModelTest.kt`, `ArchivedDiscussionsViewModelTest.kt`, `DiscussionListViewModelTest.kt`, `ChannelListViewModelTest.kt`, `ThreadViewModelTest.kt`). 11 call sites crosses the architect-side red line (> 10 simultaneous-update consumers) and would normally trip a split. The cascade is structurally unsplittable in Kotlin — interface evolution is all-or-nothing for abstract methods — so the only escape is a default implementation.

`flowOf(emptyList())` is semantically meaningful, not a placeholder: a repository that does not track workspace history legitimately has no recents. The Workspace Picker consuming this flow will render an empty Recent section — exactly the correct behavior for a fake that does not care about this surface.

Production implementations MUST override:
- `FakeConversationRepository` (this ticket): override with the storage-backed flow described below.
- Future `RemoteConversationRepository` (Phase 4): override with a server-side query.

The 11 anonymous test-only fakes inherit the default — **no edits to those 5 test files**.

This is the only deviation from the existing "all methods abstract, explicit `TODO` stubs everywhere" convention in this interface. The deviation is deliberate, justified by the cascade cost, and confined to one method.

### `FakeConversationRepository` storage

Add a sibling `MutableStateFlow<List<String>>` alongside the existing `state: MutableStateFlow<Map<String, ConversationRecord>>`:

```kotlin
private val recents = MutableStateFlow<List<String>>(initialRecents())
```

`initialRecents()` is a private companion-object helper computing the seeded recents from `SEED_RECORDS`:

- Take every seeded `Conversation`.
- Sort by `lastUsedAt` descending.
- Map to `cwd`.
- Filter out `""` and `DEFAULT_SCRATCH_CWD`.
- Apply `.distinct()` to dedupe (Kotlin's `distinct()` preserves first occurrence, which is the most-recent occurrence after the sort).

With current seeds, initial list = `["~/Workspace/pyrycode-mobile", "~/Workspace/joi-pilates", "~/Workspace/personal"]`.

Override on the implementation:

```kotlin
override fun recentWorkspaces(): Flow<List<String>> = recents
```

(Returning `MutableStateFlow` directly is fine — Kotlin upcasts to `Flow<List<String>>` at the call site. No need for `.asStateFlow()` since the consumer only collects.)

### Bump logic

Add one private helper:

```kotlin
private fun bumpWorkspace(cwd: String) {
    if (cwd.isEmpty() || cwd == DEFAULT_SCRATCH_CWD) return
    recents.update { current -> listOf(cwd) + current.filterNot { it == cwd } }
}
```

Invariant: empty string and `DEFAULT_SCRATCH_CWD` are filtered at insert time (never enter the list). Read returns the list as-is.

Call `bumpWorkspace(resultCwd)` after each cwd-touching mutator's `state.update { ... }` block, where `resultCwd` is the conversation's `cwd` after the write:

| Mutator | When to bump | What `cwd` to pass |
|---|---|---|
| `createDiscussion(workspace)` | Always (filter inside bump handles `""`) | `workspace ?: ""` (the value just assigned to `Conversation.cwd`) |
| `promote(conversationId, name, workspace)` | Always | `updated.cwd` (already computed; equals `workspace ?: record.conversation.cwd`) |
| `changeWorkspace(conversationId, workspace)` | Always | `workspace` (always non-null at this signature) |
| `startNewSession(conversationId, workspace)` | Always | `updatedConversation.cwd` inside `mintNewSession` (equals `workspace ?: record.conversation.cwd`) |
| `rename` | Never — does not touch `cwd` | — |
| `sendMessage` | Never — does not touch `cwd` | — |
| `archive` / `unarchive` | Never — does not touch `cwd` | — |

For `promote` and `startNewSession`, the bump fires unconditionally even when `workspace` is `null`. Rationale: the resulting `cwd` either changed (workspace provided) or stayed the same (workspace null), and bumping with an already-present cwd is a no-op that correctly re-prepends it. **However**, this means a `null`-workspace `promote` of a discussion whose current cwd is `DEFAULT_SCRATCH_CWD` will correctly skip the bump (filter inside `bumpWorkspace`), preserving the AC exclusion. Likewise for `null`-workspace `startNewSession` of a scratch discussion.

The bump fires **after** the `state.update { ... }` block, not inside it. Two separate atomic writes is acceptable because:
- Subscribers to `state` and `recents` see consistent-enough views; there is no invariant tying a specific `state` snapshot to a specific `recents` snapshot.
- A single consumer (the Workspace Picker) reads only `recents`; it does not need to coordinate with `state` snapshots.

`mintNewSession` is the shared helper between `changeWorkspace` and `startNewSession`. Add the bump call once inside `mintNewSession` after its `state.update` block, using `updatedConversation.cwd` lifted out of the lambda (`lateinit var` or capture via the existing `lateinit var newSession` pattern).

### Concurrency

- `MutableStateFlow.update { }` is atomic (compare-and-set loop). Each bump is atomic on its own; two concurrent bumps will both serialize through the StateFlow's update mechanism, with the later write winning the prepend position. Order under concurrency: whichever bump's `update` block runs last has its cwd at index 0. This matches "most-recent-first" semantics.
- No `Dispatchers` change needed. Bumps run on the caller's coroutine; the `update { }` itself is non-suspending.
- Cold flow semantics: `recents` is a `MutableStateFlow`, exposed as `Flow<List<String>>`. New collectors receive the current value on subscription, then every subsequent change. This matches the "Cold flow, re-emits on every repository state change" wording in the AC. (Strictly, `StateFlow` is hot-by-shape but cold-by-contract per the AC's intent — the test exercises observable re-emission, not subscription semantics.)

### Error handling

None. The method does not fail. Empty list is a valid output (no recents yet). The bump filter handles "no bound workspace" cwds silently.

## Testing strategy

Three new unit tests in `FakeConversationRepositoryTest.kt` (JUnit 4 + `runBlocking` + `Flow.first()`, matching existing conventions). Add them at the end of the file before the closing brace.

**Test 1 — `recentWorkspaces_dedupes_repeatedCwds`**

- Construct `FakeConversationRepository()`.
- Promote `seed-discussion-a` with `workspace = "~/Workspace/foo"`.
- Call `changeWorkspace` on `seed-channel-personal` with `"~/Workspace/foo"` (same cwd).
- Assert `repo.recentWorkspaces().first()` contains `"~/Workspace/foo"` exactly once, at index 0.
- Assert total list size = previous distinct workspaces + 1 (foo added, not duplicated).

**Test 2 — `recentWorkspaces_ordersByMostRecentWrite`**

- Construct `FakeConversationRepository()`.
- Capture initial recents — should be `["~/Workspace/pyrycode-mobile", "~/Workspace/joi-pilates", "~/Workspace/personal"]` (from seeds sorted by `lastUsedAt`).
- `changeWorkspace` on `seed-channel-personal` to `"~/Workspace/personal"` (re-bump). Assert recents now lead with `"~/Workspace/personal"`.
- `createDiscussion(workspace = "~/Workspace/new")`. Assert recents now lead with `"~/Workspace/new"`, followed by `"~/Workspace/personal"`, then the rest.

**Test 3 — `recentWorkspaces_excludesEmptyStringAndDefaultScratch`**

- Construct `FakeConversationRepository()`.
- `createDiscussion(workspace = null)` → emits `""` as cwd.
- `createDiscussion(workspace = DEFAULT_SCRATCH_CWD)` → emits scratch sentinel.
- Assert `repo.recentWorkspaces().first()` contains neither `""` nor `DEFAULT_SCRATCH_CWD`.
- Assert initial recents (seed-driven) also contains neither sentinel — covers the seed-time filter.

No instrumented tests; this is pure data-layer logic. No `connectedAndroidTest` impact.

## Open questions

None. All AC items map to concrete decisions above. The PO's "architect's choice" on `MutableStateFlow` vs `observeConversations` derivation is decided in favor of `MutableStateFlow` (per the bump table above) — this is also the storage shape the follow-up write-side ticket (#TBD, `createWorkspaceFolder`) will plug into without further refactor.

## Out of scope

- **Consolidating the "no bound workspace" predicate.** The ticket leaves the choice between inline `cwd.isEmpty() || cwd == DEFAULT_SCRATCH_CWD` and a `Conversation.hasBoundWorkspace()` helper to the architect. Chose inline: only one call site needs the predicate today (inside `bumpWorkspace`). Extract to a helper if and when a second site appears.
- **`createWorkspaceFolder` write-side.** Separate ticket. The `recents` `MutableStateFlow` is the storage hook it will use.
- **Real backend integration.** Phase 4. The default impl on the interface means the eventual `RemoteConversationRepository` will need to override but has no current API surface to wire against.
