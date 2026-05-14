# Spec — Archived Discussions screen + ViewModel + Settings entry (#94)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:18-51` — interface + `ConversationFilter.Archived` enum variant. The filter the new VM passes to `observeConversations`.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:32-46,135-143,336-343` — confirms `ConversationFilter.Archived` already filters on `conv.archived` regardless of `isPromoted` (so it includes archived channels too — see § Open questions), confirms `unarchive(id)` flips the flag and re-emits, confirms `seed-discussion-archived` exists for manual verification.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModel.kt` — the closest VM pattern: `combine(observeConversations, …)` → `stateIn(WhileSubscribed(5_000))`, sealed `UiState`, `onEvent` dispatch, navigation channel. Copy the shape; drop the `pendingPromotion` combinator since restore is a one-shot, not a dialog.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListScreen.kt` — closest screen pattern: `Scaffold` + `TopAppBar` with back arrow, `LazyColumn` with `items(key = { it.id })`, long-press `DropdownMenu` over `ConversationRow`, centered "empty/loading/error" branches. Inherit the alpha(0.65f) muted treatment for the row (PO note: reduced-contrast for archived state).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:30-101` — public composable signature, including `onLongClick`. Renders displayName + workspace label + `formatRelativeTime(lastUsedAt)` in trailing. Reuse as-is; do **not** add a parallel row composable.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RelativeTime.kt` — `formatRelativeTime(Instant)` produces "Apr 15" / "Apr 15, 2025" for older instants; the existing trailing slot already renders an acceptable "archive date" proxy (see § Open questions on `archivedAt`).
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:46-54,179-185` — current `Storage` section has a row "Archived conversations" / "11 archived" with `onClick = {}`. The signature has to gain a navigation callback; the row's headline and supporting text change.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:93-221` — `PyryNavHost` and the private `Routes` object. New route + `composable(Routes.ARCHIVED_DISCUSSIONS)` block added here; `Routes.SETTINGS` block extends to pass the new navigation callback to `SettingsScreen`.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:18-30` — Koin module: add one `viewModel { ArchivedDiscussionsViewModel(get()) }` line next to the others.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModelTest.kt` — the test idiom to mirror: `Dispatchers.setMain(UnconfinedTestDispatcher())`, `MutableSharedFlow` source, anonymous `ConversationRepository` stub with `TODO("not used")` on every unused method, `RecordingRepo` pattern for verifying a suspend call, `runTest { … advanceUntilIdle() }`. Copy the `stubRepo` / `recordingRepo` helpers, swap `promote` → `unarchive`.
- `app/src/main/res/values/strings.xml` — add new keys here; reuse existing `cd_back`.

## Design source

N/A — no dedicated Archived Discussions view exists in the canonical Figma file yet. Per the ticket body, the screen derives its visuals from the existing Recent Discussions row treatment (#69), with the muted treatment already in `DiscussionListScreen`'s `Modifier.alpha(0.65f)` on the row. A dedicated Figma view is a recommended follow-up; not blocking this slice — manual verification against the seeded archived discussion (`seed-discussion-archived`) is sufficient.

## Context

Plan Phase 3 specifies an "Archived discussions" view reachable from Settings. The repository primitives landed in #93 (retain-on-archive + `ConversationFilter.Archived` + seeded archived discussion) and #96 (`unarchive(id)` primitive). This ticket builds the surface — screen, ViewModel, Settings entry, navigation graph wiring — so a user can recover an auto-archived discussion. The 30-day auto-archive worker itself is a separate ticket; this slice only needs the archived state to be reachable, which the seed in #93 guarantees.

## Design

### Package layout

The screen ships under `ui/settings/` rather than `ui/conversations/list/`. Rationale: it's reached from Settings (matching `LicenseScreen`'s placement); discoverability for future maintenance follows the entry point. The VM consumes `ConversationRepository`, which is fine — the existing `SettingsViewModel` already consumes `AppPreferences`, so settings-package ViewModels are not constrained to settings-only dependencies.

New files:

- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModel.kt`
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt`
- `app/src/test/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModelTest.kt`

### ViewModel contract

```kotlin
sealed interface ArchivedDiscussionsUiState {
    data object Loading : ArchivedDiscussionsUiState
    data object Empty : ArchivedDiscussionsUiState
    data class Loaded(val discussions: List<Conversation>) : ArchivedDiscussionsUiState
    data class Error(val message: String) : ArchivedDiscussionsUiState
}

sealed interface ArchivedDiscussionsEvent {
    data class RestoreRequested(val conversationId: String) : ArchivedDiscussionsEvent
    data object BackTapped : ArchivedDiscussionsEvent
}

class ArchivedDiscussionsViewModel(
    private val repository: ConversationRepository,
) : ViewModel() {
    val state: StateFlow<ArchivedDiscussionsUiState> = /* see § State + concurrency */
    fun onEvent(event: ArchivedDiscussionsEvent)
}
```

Behavior contract (no implementation bodies — developer writes them):

- `state` is built from `repository.observeConversations(ConversationFilter.Archived)`, then filtered in the VM to `!isPromoted` (see § Open questions: the repository's `Archived` filter includes promoted-archived too; the screen only shows archived **discussions**). Empty list → `Empty`; non-empty → `Loaded(list)`; stream throws → `Error(message)`.
- `.catch { e -> emit(Error(e.message?.ifBlank { null } ?: "Failed to load archived discussions.")) }` — same defensive shape as `DiscussionListViewModel`.
- `.stateIn(viewModelScope, WhileSubscribed(5_000L), Loading)`.
- `RestoreRequested(id)` → `viewModelScope.launch { repository.unarchive(id) }`. No optimistic-local-state needed — the repository's `MutableStateFlow` re-emits, and the row leaves the `Archived` filter naturally.
- `BackTapped` is `Unit` in the VM (navigation lives in the NavHost). It's present so the screen can dispatch a single, uniform `onEvent` rather than carrying a separate `onBack: () -> Unit` callback; this matches `DiscussionListEvent.BackTapped`.
- No `navigationEvents` channel needed — restore stays on-screen (row disappears), and back is handled by the NavHost.

### Screen contract

```kotlin
@Composable
fun ArchivedDiscussionsScreen(
    state: ArchivedDiscussionsUiState,
    onEvent: (ArchivedDiscussionsEvent) -> Unit,
    modifier: Modifier = Modifier,
)
```

Layout:

- `Scaffold` with `TopAppBar` — title `R.string.archived_discussions_title` ("Archived discussions"), `navigationIcon` is `Icons.AutoMirrored.Filled.ArrowBack` with `contentDescription = stringResource(R.string.cd_back)` that dispatches `BackTapped`.
- Body branches:
  - `Loading` → centered "Loading…" (same `CenteredText` helper as `DiscussionListScreen`)
  - `Empty` → centered `stringResource(R.string.archived_discussions_empty)` ("No archived discussions")
  - `Error` → centered "Couldn't load archived discussions: ${state.message}"
  - `Loaded` → `LazyColumn` of `ArchivedDiscussionRow` items keyed by `conversation.id`
- `ArchivedDiscussionRow` (private composable in the same file): wraps the existing `ConversationRow` with:
  - `lastMessage = null` (no message-preview wired in Phase 1; the AC's "last-message preview" maps to the existing supporting-text slot, which is empty for now — see § Open questions)
  - `Modifier.alpha(0.65f)` (muted treatment, matching the archived/PO-noted reduced contrast)
  - `onLongClick = { menuExpanded = true }`
  - A `DropdownMenu` over the row with one `DropdownMenuItem` "Restore" (`R.string.restore_action`) that dispatches `RestoreRequested(conversation.id)` and dismisses
- No `SwipeToDismissBox` (ticket: "exactly one gesture, not both"). Long-press dropdown matches the spec verbatim and is consistent with `DiscussionListScreen`'s long-press menu pattern.

The trailing slot of `ConversationRow` renders `formatRelativeTime(conversation.lastUsedAt)`. For the seeded archived discussion (`lastUsedAt = 2026-04-15`) this prints "Apr 15" — acceptable as the "archive date" surface for this slice (see § Open questions for the `archivedAt` follow-up).

### Settings row + navigation

`SettingsScreen` gains a parameter:

```kotlin
fun SettingsScreen(
    themeMode: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    onOpenLicense: () -> Unit,
    onOpenArchivedDiscussions: () -> Unit,   // new
    modifier: Modifier = Modifier,
)
```

The existing `Storage` → `Archived conversations` row is rewritten in place:

- Headline → `"Archived discussions"` (verbatim from the ticket; the row only navigates to archived **discussions**, not archived channels)
- Supporting text → removed (the stale "11 archived" was placeholder copy; AC doesn't request a count, and adding live-counting would expand scope into another VM-StateFlow combinator)
- `onClick = onOpenArchivedDiscussions`

Existing previews (`SettingsScreenLightPreview`, `SettingsScreenDarkPreview`) gain `onOpenArchivedDiscussions = {}`.

`MainActivity.PyryNavHost`:

- `Routes` gains `const val ARCHIVED_DISCUSSIONS = "archived_discussions"`
- `composable(Routes.SETTINGS)` extends to pass `onOpenArchivedDiscussions = { navController.navigate(Routes.ARCHIVED_DISCUSSIONS) }`
- New `composable(Routes.ARCHIVED_DISCUSSIONS)` block, mirroring the `LicenseScreen` shape:
  - `val vm = koinViewModel<ArchivedDiscussionsViewModel>()`
  - `val state by vm.state.collectAsStateWithLifecycle()`
  - `ArchivedDiscussionsScreen(state = state, onEvent = { event -> when (event) { ArchivedDiscussionsEvent.BackTapped -> navController.popBackStack(); is ArchivedDiscussionsEvent.RestoreRequested -> vm.onEvent(event) } })`

`AppModule` gains one line: `viewModel { ArchivedDiscussionsViewModel(get()) }`.

### Strings

Add to `app/src/main/res/values/strings.xml`:

- `archived_discussions_title` → `"Archived discussions"`
- `archived_discussions_empty` → `"No archived discussions"`
- `archived_discussions_settings_row` → `"Archived discussions"` (used by the Settings row headline)
- `restore_action` → `"Restore"`

Reuse `cd_back`.

## State + concurrency model

- One job: the cold `Flow<List<Conversation>>` from `repository.observeConversations(ConversationFilter.Archived)`, filtered to `!isPromoted` inside the VM, mapped to `Empty | Loaded`, `.catch` → `Error`, hot-promoted by `.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), Loading)`. Five-second linger matches `DiscussionListViewModel` and survives configuration changes without leaking.
- Restore: `viewModelScope.launch { repository.unarchive(id) }`. Default `Dispatchers.Main.immediate` from `viewModelScope` is fine — the Fake mutates an in-memory `MutableStateFlow.update`; Phase 4's Ktor remote will move IO to `Dispatchers.IO` inside the repository, not here.
- No manual cancellation logic needed; `viewModelScope` is cancelled by Lifecycle.
- No race between restore and the upstream re-emit — `unarchive` updates the shared `MutableStateFlow` atomically, the filter recomputes, the row drops from the next emission. No need for an optimistic ID-set in the VM.

## Error handling

- Stream failure → `Error(message)` rendered as centered text. Same shape as `DiscussionListViewModel`; rationale documented in #45.
- `unarchive` failure → fire-and-forget in `viewModelScope.launch`. The Fake never throws on a valid id, and Phase 4 isn't here yet. **Do not** add a transient snackbar or retry surface in this ticket — that's defending a failure mode that hasn't been observed and expands scope past the AC. If Phase 4 makes restore failure-prone, a follow-up adds the visible-error path.
- A `RestoreRequested` for an id no longer in the archived list is harmless: the next stream emission will not contain it, so it's already gone from the UI; the suspend call will throw `IllegalArgumentException("Unknown conversation: $id")` from the Fake, which is swallowed by the fire-and-forget. Acceptable for Phase 1.

## Testing strategy

### Unit (`./gradlew test`)

`ArchivedDiscussionsViewModelTest` — mirror `DiscussionListViewModelTest`'s structure. Use `Dispatchers.setMain(UnconfinedTestDispatcher())` + `runTest` + `advanceUntilIdle`. Stub repo via an anonymous `ConversationRepository` with `TODO("not used")` on unused methods.

Scenarios (each a discrete `@Test`, no shared mutable state across tests):

- **initialState_isLoading** — fresh VM, no collector, `state.value == Loading`.
- **loaded_passesArchivedFilter** — `combine` requests `ConversationFilter.Archived` (capture the filter the stub was called with). Same `captured: MutableList<ConversationFilter>` pattern as `DiscussionListViewModelTest.loaded_containsOnlyUnpromoted_inSourceOrder`.
- **loaded_dropsPromotedArchivedFromList** — source emits `[archivedDiscussion, archivedChannel]`; VM exposes only the discussion (verifies the `!isPromoted` post-filter inside the VM).
- **empty_whenSourceEmitsEmptyList** — empty list → `Empty`.
- **empty_whenAllArchivedAreChannels** — source emits `[archivedChannel]` only; VM yields `Empty` because the post-filter strips them.
- **restoreRequested_callsUnarchive_withConversationId** — `RecordingRepo` capturing `unarchiveCalls: List<String>`; verify the captured id matches.
- **restoreRequested_doesNotCallUnarchive_synchronouslyBeforeAdvance** — optional; verifies the launch happens. (Skip if the test idiom feels noisy — `advanceUntilIdle` covers it.)
- **error_whenSourceFlowThrows** — `erroringRepo("network down")` → `Error("network down")`.
- **error_messageIsNonBlank_whenExceptionMessageIsNull** — null/blank message → non-blank fallback.

The "list-observation" path is exercised by the first five scenarios; the "restore-action" path by `restoreRequested_callsUnarchive_withConversationId`. Together they cover the AC's "ViewModel unit test covers the archived-list observation and the restore-action path."

### Instrumented (`./gradlew connectedAndroidTest`)

**Not required by the AC**, and the codebase has no precedent for Compose-tests on every new screen (e.g. `DiscussionListScreenTest` exists but the VM contract is the binding spec). Manual verification of the seeded archived discussion (`seed-discussion-archived`) covers the visual path.

If the developer wants belt-and-suspenders, add `ArchivedDiscussionsScreenTest` under `app/src/androidTest/.../ui/settings/` — mirror `DiscussionListScreenTest`'s minimal `ComposeTestRule.setContent` + `onNodeWithText` shape. Not a blocker.

## Manual verification (developer should do before declaring done)

1. `./gradlew installDebug` on an emulator (Pixel 7, API 34).
2. Open the app → Channel List → tap the gear → Settings opens.
3. Scroll to `Storage` → tap `Archived discussions` → screen opens with one row (the seeded archived discussion). Confirm muted alpha rendering and the back arrow.
4. Long-press the row → `Restore` dropdown appears. Tap it. Row leaves the list; screen becomes the empty state ("No archived discussions").
5. Back-arrow → returns to Settings. Back-arrow → returns to Channel List → confirm the restored discussion appears in the Recent discussions section.
6. Toggle dark mode (Settings → Theme → Dark) and revisit the archived list with the seed re-archived (kill & re-launch the app to reseed) — confirm both light and dark variants render.

## Open questions

1. **`archivedAt` vs `lastUsedAt` as the displayed timestamp.** The `Conversation` model has no `archivedAt: Instant?` field; `archive()` flips a boolean without recording a timestamp. The trailing slot of `ConversationRow` currently renders `formatRelativeTime(lastUsedAt)`, which for an archived item is "the last time it was used before being archived" — close to the AC's "archive date" but not literally it. **Resolution for this ticket:** ship as-is (no model change), label remains the existing relative-time format. **Follow-up:** when the 30-day auto-archive ticket lands, add `archivedAt: Instant?` to `Conversation` and switch this screen's trailing slot to format that field. Filing as a follow-up is cheaper than expanding scope here.
2. **The repository's `ConversationFilter.Archived` includes archived channels too.** `FakeConversationRepository.kt:42` filters on `conv.archived` alone, with no `isPromoted` check. The screen only wants archived **discussions**, so the VM applies a `!isPromoted` post-filter on the emitted list. This is the simpler choice than adding a `ConversationFilter.ArchivedDiscussions` variant — the latter would push the change into the data layer (interface + Fake) for one screen's needs. If a future screen wants archived channels, the existing `Archived` filter still works.
3. **Live count on the Settings row.** Stripped per § Design — adding it expands scope into another VM-StateFlow combinator. If the PO wants the count back later, it lands in `SettingsViewModel` as a `StateFlow<Int>` mapped from the same archived stream.
