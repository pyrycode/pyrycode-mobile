# Spec: Short-press FAB uses Settings `defaultWorkspace` via `AppPreferences` wiring (#240)

Issue: [#240](https://github.com/pyrycode/pyrycode-mobile/issues/240)
Split from: #236 (closed; replaced by [#239](https://github.com/pyrycode/pyrycode-mobile/issues/239) + #240). #239 already landed the `makeVm(...)` test seam — this ticket spends that seam by adding the constructor dependency exactly once.

## Context

The short-press FAB currently calls `repository.createDiscussion()` with no `workspace` argument. The default-null parameter falls into `FakeConversationRepository.createDiscussion`, which sets `cwd = workspace ?: ""` — new discussions land with an empty `cwd`. Settings #231 introduced `AppPreferences.defaultWorkspace: Flow<String>` (default: `DEFAULT_SCRATCH_CWD = "~/.pyrycode/scratch"`), but no consumer is wired to it yet.

This ticket closes that loop for the short-press path. After this ticket: short-press → read `appPreferences.defaultWorkspace.first()` → pass it to `createDiscussion(workspace = …)`. Long-press → unchanged (an explicit user pick already passes a `workspace` argument and must continue to override the default).

The `makeVm(...)` helper introduced in #239 already constructs an `AppPreferences` internally; it simply doesn't pass it to the VM yet. This ticket is precisely the diff #239 set up for: one parameter on `ChannelListViewModel`, one parameter on `makeVm` — no call-site cascade.

## Design source

N/A — no UI surface change. The FAB tap targets and the resulting thread render unchanged; only the `cwd` string on the newly-created `Conversation` differs based on the persisted default. The workspace chip in the thread is rendered by existing UI from `Conversation.cwd` without modification.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:54-56` — current single-arg constructor (`ChannelListViewModel(repository)`); this ticket adds the second parameter.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:125-148` — `onEvent` body. The `CreateDiscussionTapped` branch at line 127–131 is the only branch that changes; `WorkspacePicked` at line 134–140 must remain byte-for-byte identical except for surrounding context.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:66-71` — `defaultWorkspace: Flow<String>` getter and setter. Flow always emits — the `?:` in the getter ensures non-null with `DEFAULT_SCRATCH_CWD` as the fallback. Read once via `.first()`.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt:16-19` — canonical Koin shape: `class SettingsViewModel(private val appPreferences: AppPreferences, …) : ViewModel()`. Match the parameter name (`appPreferences`) and ordering (the new dep follows the existing `repository` dep).
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:32` — current `viewModel { ChannelListViewModel(get()) }`. Mirror the `SettingsViewModel` line directly above (`viewModel { SettingsViewModel(get(), get()) }`) — add one `, get()` and trust Koin's type resolution to pick up the `single { AppPreferences(get()) }` already registered on line 29.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt:62-65` — the `makeVm` helper from #239. After this ticket the `@Suppress("UNUSED_PARAMETER")` annotation goes away and the `prefs` parameter flows into the `ChannelListViewModel(...)` call.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt:323-435` — the existing FAB-path tests (`createDiscussionTapped_createsOneUnpromotedConversation`, `createDiscussionTapped_emitsToThreadNavigationWithCreatedId`, `longPressFab_setsWorkspacePickerVisibleToTrue`, `workspacePicked_createsDiscussionWithPickedWorkspace_emitsNavigation_andClearsVisibility`). The two new test cases sit next to these and reuse their idiom.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:91-118` — `createDiscussion` body. The branch the developer cares about: line 108 (`cwd = workspace ?: ""`) plus `bumpWorkspace(conversation.cwd)` on line 116. See the "State + concurrency model" subsection below on the incidental cwd-string change.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:21` — `const val DEFAULT_SCRATCH_CWD: String = "~/.pyrycode/scratch"`. Used in test assertions if the developer needs to reference it; cited here so the developer doesn't hardcode the literal in the new tests.

## Design

### Constructor surface

`ChannelListViewModel` gains one parameter, positioned after the existing `repository`:

```kotlin
class ChannelListViewModel(
    private val repository: ConversationRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() { … }
```

No other field, no other public surface, no `StateFlow<String>` projection of `defaultWorkspace` — the read is one-shot per event.

### Event-handling change

Only the `CreateDiscussionTapped` branch of `onEvent`. Behavioural change:

- **Before:** launch coroutine → `repository.createDiscussion()` → emit `ToThread(createdId)`.
- **After:** launch coroutine → read `appPreferences.defaultWorkspace.first()` → `repository.createDiscussion(workspace = <that value>)` → emit `ToThread(createdId)`.

The `.first()` read happens inside the existing `viewModelScope.launch { … }`. No additional scopes, no `runBlocking`, no parallel-`async`. The read is sequenced before the create call; navigation emission follows the create.

The `WorkspacePicked` branch is **unchanged**. The explicit user pick (`event.workspace`) flows directly into `createDiscussion(workspace = event.workspace)` as today. Do not factor a shared helper that funnels both paths through `appPreferences` — that would defeat the AC ("long-press is unchanged").

The `LongPressFab` and `WorkspacePickerDismissed` branches are unchanged.

### Koin wiring

`AppModule.kt:32`: `viewModel { ChannelListViewModel(get()) }` becomes `viewModel { ChannelListViewModel(get(), get()) }`. The `single { AppPreferences(get()) }` registration on line 29 is already there from prior tickets; no module-level additions.

### Test seam

The #239 `makeVm` helper already constructs an `AppPreferences` over a per-test `TemporaryFolder`-backed DataStore. Two changes:

1. The `@Suppress("UNUSED_PARAMETER")` annotation on the `prefs` parameter is removed.
2. The body changes from `ChannelListViewModel(repository)` to `ChannelListViewModel(repository, prefs)`.

That's the entire helper diff. All 18 existing call sites are untouched — they pass the helper's default `prefs` (a fresh `AppPreferences` whose `defaultWorkspace` flow emits `DEFAULT_SCRATCH_CWD`).

### Two new test cases

Both live at the bottom of the existing `@Test` block, next to the `WorkspacePicked` test. They use the `prefs` override on `makeVm`.

**Case A — short-press uses persisted default:**

- Construct a real `FakeConversationRepository` and capture pre-state via `observeConversations(ConversationFilter.Discussions).first()`.
- Construct an `AppPreferences` over a fresh `newDataStore()`, call `setDefaultWorkspace("~/projects/my-thing")` on it, then `advanceUntilIdle()` so the write commits before the VM reads it.
- Build the VM via `makeVm(repo, prefs = thePrefs)`.
- Dispatch `ChannelListEvent.CreateDiscussionTapped`; `advanceUntilIdle()`.
- Assert the single newly-created `Conversation` has `cwd == "~/projects/my-thing"`.

**Case B — long-press still wins over default:**

- Same setup but `setDefaultWorkspace("~/projects/default-path")` on the prefs.
- Build the VM via `makeVm(repo, prefs = thePrefs)`.
- Dispatch `ChannelListEvent.LongPressFab` then `ChannelListEvent.WorkspacePicked("~/projects/user-pick")`; `advanceUntilIdle()`.
- Assert the single newly-created `Conversation` has `cwd == "~/projects/user-pick"` (the user pick, **not** the default).

Both cases follow the assertion shape of the existing `workspacePicked_createsDiscussionWithPickedWorkspace_emitsNavigation_andClearsVisibility` test at line 408–435 — use `(after - before.toSet()).single { it.id !in beforeIds }` to isolate the new conversation, then `assertEquals(<expected>, created.cwd)`.

### What does *not* change

- The `ChannelListViewModel` public surface other than the constructor (`state`, `navigationEvents`, `onEvent`).
- The `WorkspacePicked` / `LongPressFab` / `WorkspacePickerDismissed` branches of `onEvent`.
- `ConversationRepository` interface — no method-signature changes.
- `FakeConversationRepository` — no changes. The `cwd = workspace ?: ""` line stays. With this ticket, when the user has not customized the default and the helper's prefs default is `DEFAULT_SCRATCH_CWD`, the VM will pass `"~/.pyrycode/scratch"` to `createDiscussion`, so `cwd` will be `"~/.pyrycode/scratch"` rather than `""`. See the State + concurrency model section for the implication.
- The `promote` repository method — explicitly out of scope per the ticket body.
- The 18 existing `ChannelListViewModelTest` test methods — none of them assert on `cwd`, so the incidental string change from `""` to `"~/.pyrycode/scratch"` is invisible to their assertions.

## State + concurrency model

### Coroutine sequencing in `CreateDiscussionTapped`

The single `viewModelScope.launch { … }` block now contains two suspending calls in sequence:

1. `val workspace = appPreferences.defaultWorkspace.first()` — completes when DataStore emits its current value (one buffered read; with the value cached in DataStore's in-process replay it's effectively synchronous after the first read of the process lifetime).
2. `val conversation = repository.createDiscussion(workspace = workspace)`.
3. `navigationChannel.send(ChannelListNavigation.ToThread(conversation.id))`.

No race: even rapid double-taps result in two independent `launch` coroutines that each do their own `.first()` read; both will read the same DataStore value. If the user opens Settings between taps and changes the default, each tap reads the value visible at the moment it executes — that's the desired behaviour.

### Dispatcher

The launch inherits `viewModelScope`'s dispatcher (`Dispatchers.Main.immediate` on Android). DataStore's `Flow<Preferences>` switches internally to its own dispatcher for IO and switches back to the collector's context for emission. No manual `withContext(Dispatchers.IO)` — DataStore handles that.

### Incidental `cwd` string change

Before this ticket, an unconfigured user's short-press created a `Conversation` with `cwd = ""`. After this ticket, the same user creates `cwd = "~/.pyrycode/scratch"` (the `DEFAULT_SCRATCH_CWD` constant that `AppPreferences.defaultWorkspace` defaults to).

Downstream impact: `FakeConversationRepository.bumpWorkspace` is gated by `if (cwd.isEmpty() || cwd == DEFAULT_SCRATCH_CWD) return` (line 275). Both old-value (`""`) and new-value (`DEFAULT_SCRATCH_CWD`) fall into the early-return — no recent-workspaces list pollution. None of the 18 existing tests assert on the empty-string vs `DEFAULT_SCRATCH_CWD` distinction, so they continue to pass. The thread-UI workspace chip already handles either form (this ticket doesn't touch thread UI; the chip rendering is whatever it was for `cwd = ""` or `cwd = DEFAULT_SCRATCH_CWD` today).

This is a benign side-effect of the wiring direction, not a behavioural regression. Don't add a normalization step that maps `DEFAULT_SCRATCH_CWD` → `""` to preserve the old shape — that's a defense for a problem that hasn't been observed and would obscure the wiring.

### Cancellation

If the user navigates away mid-creation (e.g. via system back), `viewModelScope` cancels and both `.first()` and `createDiscussion` get cancellation-checked. `Channel.send` is cancellable. No special handling needed — same shape as the existing pre-#240 code.

## Error handling

`appPreferences.defaultWorkspace.first()` cannot reasonably throw in test or production: the underlying `dataStore.data.map { … ?: DEFAULT_SCRATCH_CWD }` guarantees a non-null `String` on every emission, and DataStore swallows file-not-found by emitting `emptyPreferences()` (which maps to the fallback). The existing pre-#240 code has no try/catch around `repository.createDiscussion()`; this ticket does not add one. If a future ticket discovers a real failure mode (e.g. disk full → DataStore write failure during set, then read), it'll surface there.

Do not wrap the `.first()` in a `runCatching` block. Don't add a "fallback to scratch" inside the VM — that's `AppPreferences.defaultWorkspace`'s job, and it already does it.

## Testing strategy

Unit tests only, `./gradlew test`. No instrumented tests.

**New tests** (two, both inside `ChannelListViewModelTest`):

| Test name | Setup | Action | Assertion |
|---|---|---|---|
| `shortPressFab_usesPersistedDefaultWorkspace` | Real `FakeConversationRepository`; `AppPreferences` with `setDefaultWorkspace("~/projects/my-thing")`; `advanceUntilIdle()` after the write | `onEvent(CreateDiscussionTapped)`; `advanceUntilIdle()` | New `Conversation.cwd == "~/projects/my-thing"` |
| `longPressPicker_overridesDefaultWorkspace` | Real `FakeConversationRepository`; `AppPreferences` with `setDefaultWorkspace("~/projects/default-path")`; `advanceUntilIdle()` | `onEvent(LongPressFab)`; `onEvent(WorkspacePicked("~/projects/user-pick"))`; `advanceUntilIdle()` | New `Conversation.cwd == "~/projects/user-pick"` |

Both new tests construct the VM via `makeVm(repo, prefs = thePrefs)`. Use `(after - before.toSet()).single { it.id !in beforeIds }` to isolate the created conversation; do not assume the new discussion is at any particular list index.

**Existing tests** (18, all in `ChannelListViewModelTest`):

All must pass unchanged after the `makeVm` helper-body diff. The helper's default `prefs` continues to yield `defaultWorkspace = DEFAULT_SCRATCH_CWD` per `AppPreferences`'s fallback. The four tests that exercise `FakeConversationRepository` end-to-end (`createDiscussionTapped_createsOneUnpromotedConversation`, `createDiscussionTapped_emitsToThreadNavigationWithCreatedId`, `recentDiscussionLastMessages_populatedFromFake_endToEnd`, `workspacePicked_createsDiscussionWithPickedWorkspace_emitsNavigation_andClearsVisibility`) don't assert on `cwd` for the freshly-created discussion (the last one asserts on `cwd` but uses the user-picked value, not the default), so the incidental `""` → `DEFAULT_SCRATCH_CWD` shift is invisible.

Acceptance check the developer runs at the end:

```bash
./gradlew test --tests "de.pyryco.mobile.ui.conversations.list.ChannelListViewModelTest"
./gradlew test
```

All 20 `ChannelListViewModelTest` tests (18 existing + 2 new) must pass, and the wider suite must stay green.

Static check: `grep -n "appPreferences" app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` should return exactly two hits — the constructor parameter declaration and the `.first()` call. If a third hit appears, the developer has over-built (e.g. added a `StateFlow` projection or a derived field).

## Open questions

None. The wiring shape is dictated by the #239 seam (`makeVm` already builds the prefs), the Koin pattern is dictated by `SettingsViewModel`'s precedent (line 34 of `AppModule.kt`), and the new test cases mirror existing FAB tests for assertion shape.
