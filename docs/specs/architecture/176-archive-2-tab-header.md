# Spec: Archive — 2-tab segmented header with live counts (#176)

Ticket: https://github.com/pyrycode/pyrycode-mobile/issues/176
Parent: #125 (this slice introduces the header + per-tier partition; archive-specific row + inline restore + snackbar is the follow-up child)
Size: S

## Context

Today's `ArchivedDiscussionsScreen` shows only archived discussions — the VM drops promoted (channel) conversations with `conversations.filter { !it.isPromoted }`. The repository already exposes both via `observeConversations(ConversationFilter.Archived)`, so the data layer is untouched. This slice introduces the Figma-18:2 segmented header that switches the body between archived channels and archived discussions with live counts, while continuing to render the **existing** `ArchivedDiscussionRow` (a faded `ConversationRow` + long-press `DropdownMenu` restore) for both tabs. The archive-specific row redesign + inline restore + snackbar is explicitly out of scope (follow-up child).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=18-2

`Column` rooted at `Schemes/surface`: M3 `TopAppBar` with title "Archived" (left-aligned, no back-icon shown in the frame but our screen keeps the existing back-arrow — `MainActivity` pops back via `BackTapped`); below it a segmented header (M3 `SecondaryTabRow` analogue) with two equal-flex tabs ("Channels (n)" / "Discussions (n)") using `Schemes/on-surface` for active label, `Schemes/on-surface-variant` for inactive label, `label-large` typography, a 2dp `Schemes/primary` indicator under the active tab, and a 1dp `Schemes/outline-variant` bottom divider across the whole row; body is a `LazyColumn` of the existing `ArchivedDiscussionRow`.

**Default-tab note:** the Figma frame illustrates Channels as the active tab for layout reference. AC §3 explicitly mandates **Discussions** as the default selected tab to preserve the existing landing surface. Implement per AC; the Figma is otherwise authoritative for visual treatment.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModel.kt:1-76` — current state shape (`Loading | Empty | Loaded(discussions) | Error`), filter usage, and StateFlow plumbing. This is the file you replace.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt:1-182` — current screen, including private `ArchivedDiscussionRow` composable + `CenteredText` helper + three previews. Keep `ArchivedDiscussionRow` and `CenteredText` as-is; replace the top-level `ArchivedDiscussionsScreen` body and the Loaded preview.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:28-46` — `ConversationRow` API contract (no change needed; `ArchivedDiscussionRow` already wraps it correctly).
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:214-228` — the single consumer of `ArchivedDiscussionsScreen`. You add a `when` branch for the new event variant; otherwise unchanged.
- `app/src/test/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModelTest.kt:1-349` — existing tests (one `stubRepo` / `erroringRepo` / `RecordingRepo` per file). The same helpers stay; tests must be rewritten for the new state shape.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` — confirm `ConversationFilter.Archived` semantics include both promoted + unpromoted archived rows (it does; the existing VM filters one out client-side).
- `app/src/main/res/values/strings.xml:25-28` — current `archived_*` keys. Two are obsoleted by this slice (`archived_discussions_title`, `archived_discussions_empty`); two stay (`archived_discussions_settings_row`, `archived_discussions_count_supporting`) because the Settings row is separate scope.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` — confirm `MaterialTheme.colorScheme.primary` and `colorScheme.outlineVariant` slots resolve to the `Schemes/primary` and `Schemes/outline-variant` tokens (they do via the M3 default mapping).

## Design

### Types & state shape

Replace the existing sealed `ArchivedDiscussionsUiState` with:

```kotlin
sealed interface ArchivedDiscussionsUiState {
    data object Loading : ArchivedDiscussionsUiState
    data class Loaded(
        val channels: List<Conversation>,        // archived && isPromoted
        val discussions: List<Conversation>,     // archived && !isPromoted
        val selectedTab: ArchiveTab,
    ) : ArchivedDiscussionsUiState
    data class Error(val message: String) : ArchivedDiscussionsUiState
}

enum class ArchiveTab { Channels, Discussions }
```

Notes:
- `Empty` is removed as a top-level state. AC §5 mandates the tab header stays visible while one tier has zero items — that requires `Loaded` to render even when `channels` or `discussions` is empty. Per-tab emptiness is computed from `Loaded.channels.isEmpty()` / `Loaded.discussions.isEmpty()` inside the body.
- `Loaded` carries **both** lists so the header counts (`channels.size`, `discussions.size`) are always available regardless of selected tab; tab switching is a pure UI re-render with no flow re-collection. AC §1's "VM should emit both lists with counts" maps directly onto this.
- `selectedTab` lives in the VM (not in `rememberSaveable`) so tests can assert tab-selection behavior deterministically and so the default-tab decision is encoded next to the rest of the VM state. Configuration changes are still survived: `viewModelScope` outlives recomposition.

### Events

```kotlin
sealed interface ArchivedDiscussionsEvent {
    data class RestoreRequested(val conversationId: String) : ArchivedDiscussionsEvent
    data object BackTapped : ArchivedDiscussionsEvent
    data class TabSelected(val tab: ArchiveTab) : ArchivedDiscussionsEvent  // new
}
```

`TabSelected` updates the internal `selectedTab` MutableStateFlow; `RestoreRequested` and `BackTapped` are unchanged.

### ViewModel wiring

- Hold `private val selectedTab = MutableStateFlow(ArchiveTab.Discussions)` — the **default tab** per AC §3 lives here as the initial value.
- Build the public `state: StateFlow<ArchivedDiscussionsUiState>` via:

  ```
  combine(
      repository.observeConversations(ConversationFilter.Archived),
      selectedTab,
  ) { conversations, tab ->
      val channels    = conversations.filter { it.isPromoted }
      val discussions = conversations.filter { !it.isPromoted }
      ArchivedDiscussionsUiState.Loaded(channels, discussions, tab)
  }
  .catch { ... emit Error(...) ... }
  .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), Loading)
  ```

- `onEvent`:
  - `TabSelected(tab)` → `selectedTab.value = tab` (synchronous; no `viewModelScope.launch` needed — `MutableStateFlow` set is non-suspending)
  - `RestoreRequested(id)` → unchanged (calls `repository.unarchive(id)` in `viewModelScope.launch`)
  - `BackTapped` → unchanged (handled by `MainActivity`)

- Keep the existing `STOP_TIMEOUT_MILLIS = 5_000L` companion.

### Screen composable

Single `ArchivedDiscussionsScreen(state, onEvent, modifier)` top-level composable, same parameter shape as today.

- `Scaffold` with the same `TopAppBar` as today, but title now `stringResource(R.string.archived_title)` ("Archived"; replaces the obsolete `archived_discussions_title`). Back navigation icon and `BackTapped` event stay.
- When `state` is `Loading` → `CenteredText("Loading…", bodyModifier)` (unchanged).
- When `state` is `Error` → `CenteredText("Couldn't load archived discussions: ${state.message}", bodyModifier)` (unchanged copy — error path UX is unchanged and a copy refresh is out of scope).
- When `state` is `Loaded` → a `Column` containing:
  1. **Header.** `SecondaryTabRow(selectedTabIndex = state.selectedTab.ordinal, containerColor = Color.Transparent, indicator = { tabPositions -> SecondaryIndicator(Modifier.tabIndicatorOffset(tabPositions[state.selectedTab.ordinal]), color = MaterialTheme.colorScheme.primary, height = 2.dp) })`. Two `Tab` children — one per `ArchiveTab` enum value — each with `selected = (state.selectedTab == tab)`, `onClick = { onEvent(ArchivedDiscussionsEvent.TabSelected(tab)) }`, and `text = { Text(<label>) }`. Labels are produced by two new string resources accepting an integer placeholder (see §strings).
  2. **Body** (per-tab list / empty). A `LazyColumn` over the per-tab list (selected via `when (state.selectedTab) { Channels -> state.channels; Discussions -> state.discussions }`). If the chosen list is empty, render `CenteredText(<tier-specific empty message>, ...)` *in place of* the `LazyColumn` but **below the header** — the header stays mounted. `ArchivedDiscussionRow` and its long-press `DropdownMenu` restore are reused unchanged for both tabs.
- Tab indicator color: M3 `SecondaryTabRow`'s default is `colorScheme.primary` already, but the AC's 2dp height is non-default (M3 default is 2dp for `SecondaryIndicator`; verify against current Compose-bom version while implementing). If the default already matches (2dp, primary), the explicit `indicator =` override can be dropped — preferred. Otherwise, pass an explicit `SecondaryIndicator(...)` per the sketch above.
- Choose `SecondaryTabRow` over `PrimaryTabRow` because Primary's M3 rounded-pill indicator does not match Figma's flat 2dp underline.

### MainActivity glue

Add the `TabSelected` branch to the existing `when` over `ArchivedDiscussionsEvent` in `MainActivity.kt`:

- `is ArchivedDiscussionsEvent.TabSelected -> vm.onEvent(event)`

No other navigation/Scaffold changes. The route key (`Routes.ARCHIVED_DISCUSSIONS`) stays.

### DI

No change — `ArchivedDiscussionsViewModel` constructor signature is unchanged (still `(ConversationRepository)`).

### Strings

Edit `app/src/main/res/values/strings.xml`:

- **Add** `archived_title` = `Archived`
- **Add** `archived_tab_channels` = `Channels (%1$d)`
- **Add** `archived_tab_discussions` = `Discussions (%1$d)`
- **Add** `archived_empty_channels` = `No archived channels`
- **Add** `archived_empty_discussions` = `No archived discussions`
- **Remove** `archived_discussions_title` (only consumer is `ArchivedDiscussionsScreen`, replaced by `archived_title`)
- **Remove** `archived_discussions_empty` (only consumer is `ArchivedDiscussionsScreen`, replaced by the per-tier keys)
- **Keep** `archived_discussions_settings_row` and `archived_discussions_count_supporting` — both are consumed by `SettingsScreen.kt:195-202` for the Settings landing row and are out of scope for this slice.

## State + concurrency model

- One cold flow source: `observeConversations(ConversationFilter.Archived)`.
- One UI-driven `MutableStateFlow<ArchiveTab>` (default `Discussions`) inside the VM.
- `combine` fuses both; the result `stateIn`s on `viewModelScope` with `SharingStarted.WhileSubscribed(5_000L)` (matches the existing pattern across this codebase, e.g. `ChannelListViewModel`).
- All work runs on `Dispatchers.Main` by default (no IO boundary in the VM; the repository is a fake/in-memory implementation in Phase 0). No explicit `flowOn`.
- Cancellation: leaving the screen lets `WhileSubscribed(5_000L)` keep the flow warm for 5s in case of config change, then it cancels. Restoring the screen restarts collection; tab selection is reset to `Discussions` because `MutableStateFlow` lives inside the VM instance and the VM is re-created on a fresh nav. (If the user re-enters the screen during the 5s grace window the previously-selected tab survives because Koin scope keeps the VM alive — acceptable.)

## Error handling

- The flow's `.catch` emits `ArchivedDiscussionsUiState.Error(message)` exactly as today, with the same blank-message fallback (`"Failed to load archived discussions."`). Error copy refresh is out of scope; keep the existing wording (`"Couldn't load archived discussions: <msg>"` in the screen).
- Per AC §5, the empty body is a normal `Loaded` outcome (not `Error`), so error styling does not change.
- No new IO failure modes are introduced (no new repo calls).

## Testing strategy

Replace `ArchivedDiscussionsViewModelTest.kt` contents (helpers `stubRepo`, `erroringRepo`, `RecordingRepo`, `sampleArchivedDiscussion`, `sampleArchivedChannel` stay verbatim — they fit the new tests). Keep test framework choice: JUnit4 + `runTest` + `MutableSharedFlow` source, same as today. No new test framework, no MockK.

Required test cases (bullet form — write each as a single `@Test` method using the existing helpers):

- `initialState_isLoading` — fresh VM with no emission ⇒ `Loading`. (Carried over.)
- `loaded_passesArchivedFilter` — VM calls `observeConversations` with `ConversationFilter.Archived`. (Carried over.)
- `loaded_partitionsByIsPromoted` — emit `[discussion-A, channel-B, discussion-C]` ⇒ `Loaded(channels=[channel-B], discussions=[discussion-A, discussion-C], selectedTab=Discussions)`.
- `loaded_defaultSelectedTab_isDiscussions` — first `Loaded` emission has `selectedTab == ArchiveTab.Discussions`. (AC §3 contract; will fail if anyone flips the default to Channels later.)
- `tabSelected_channels_updatesStateOnly` — start in default state; dispatch `TabSelected(Channels)` ⇒ same `channels`/`discussions` lists, `selectedTab == Channels`. Confirms tab switch does not re-collect or mutate the data partition.
- `tabSelected_discussions_returnsToDefault` — flip to Channels then back to Discussions ⇒ `selectedTab == Discussions`.
- `loaded_channels_emptyWhenAllUnpromoted` — emit `[discussion-A]` ⇒ `Loaded.channels.isEmpty()`, `Loaded.discussions == [discussion-A]`. (No more `Empty` state; the screen handles per-tab empty rendering.)
- `loaded_discussions_emptyWhenAllPromoted` — emit `[channel-A]` ⇒ `Loaded.discussions.isEmpty()`, `Loaded.channels == [channel-A]`.
- `loaded_bothEmpty_whenSourceEmitsEmptyList` — emit `[]` ⇒ `Loaded(channels=[], discussions=[], selectedTab=Discussions)`. (Replaces the old `empty_whenSourceEmitsEmptyList` test.)
- `restoreRequested_callsUnarchive_withConversationId` — unchanged. (Carried over.)
- `backTapped_doesNotCallUnarchive` — unchanged. (Carried over.)
- `error_whenSourceFlowThrows` — unchanged. (Carried over.)
- `error_messageIsNonBlank_whenExceptionMessageIsNull` — unchanged. (Carried over.)

Compose / instrumented tests: none required for this slice. The screen body is straightforward composition of stock M3 components; no instrumented tests exist for the current archive screen to begin with. The two new previews (Loaded-Channels-active, Loaded-Discussions-active) provide visual coverage. Add a third Loaded-DiscussionsEmpty preview to confirm header-stays-visible behavior visually.

Run locally: `./gradlew test` (unit) and `./gradlew lint` (lint).

## Open questions

1. **Compose-bom default indicator dimensions.** Verify against the project's current Compose-bom version whether `SecondaryTabRow`'s default `indicator` is already 2dp and uses `colorScheme.primary`. If yes, prefer the default (no explicit `indicator =` override). If not, pass the explicit `SecondaryIndicator(..., height = 2.dp, color = MaterialTheme.colorScheme.primary)` per the design section. Either implementation satisfies AC §3.
2. **Count truncation.** If the user has > 999 archived items in a tier (unlikely in Phase 0 with the fake repo, but worth a moment of thought), the tab label `Channels (1234)` may push the row past one line and visually break the segmented layout. Acceptable for this slice; track for the follow-up if it becomes real with the live backend in Phase 4.
