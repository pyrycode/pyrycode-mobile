# Archived Discussions screen

Recovery surface for discussions that the (forthcoming) 30-day auto-archive worker has evicted. Reached from Settings → "Archived discussions" since #94. Stateless screen + small ViewModel + nav-graph entry; shipped under `ui/settings/` (alongside `SettingsScreen`) because discoverability follows the entry point, not the entity. Was the sibling of `LicenseScreen` from #91 to #163; #163 deleted `LicenseScreen` along with its route, asset, and test once its Settings entry point disappeared.

## What it does

Lists archived discussions and lets the user restore any of them. Long-press a row → "Restore" dropdown → tap → `ConversationRepository.unarchive(id)` flips the bit (#96), the row leaves the `Archived` filter on the next stream emission, and the restored discussion reappears in the Recent discussions section of the channel list. The 30-day auto-archive worker itself is a separate ticket — this screen assumes archived rows exist (the `seed-discussion-archived` seed from #93 guarantees one for manual verification).

The matching Settings row gained a live "N archived" supporting line in #164 — see [Settings screen § Storage](settings-screen.md) and [Settings ViewModel](settings-viewmodel.md). The pre-#164 framing in this doc was "no count subtitle, live-counting would expand the surface into another `SettingsViewModel`-StateFlow combinator" — that turned out to be exactly what #164 did, with the projection mirroring this screen's `!isPromoted` post-filter for cross-surface agreement. The two consumers now demonstrate a split: this screen surfaces `Error(message)` for the upstream because the list IS the screen's primary content; the Settings row's projection swallows the same upstream's errors via `.catch { emit(0) }` because it's supportive metadata about *this* screen.

## How it works

### `ArchivedDiscussionsViewModel`

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

class ArchivedDiscussionsViewModel(private val repository: ConversationRepository) : ViewModel()
```

State is built from `repository.observeConversations(ConversationFilter.Archived).map { it.filter { !it.isPromoted } … }.catch { Error(message) }.stateIn(viewModelScope, WhileSubscribed(5_000L), Loading)`. The `!isPromoted` post-filter is screen-local on purpose — the repository's `Archived` filter (#93) is `isPromoted`-agnostic so it composes for a future archived-channels screen; this consumer wants only archived **discussions**, so it filters in the VM rather than fragmenting the data-layer interface with an `ArchivedDiscussions` variant. Empty filtered list → `Empty`; non-empty → `Loaded(list)`. `.catch` mirrors `DiscussionListViewModel`'s shape: non-blank message preserved; null/blank falls back to `"Failed to load archived discussions."`.

`onEvent` handles two cases:

- `RestoreRequested(id)` → `viewModelScope.launch { repository.unarchive(id) }`. No optimistic-local-state — the repository's `MutableStateFlow.update` re-emits, and the row leaves the `Archived` projection naturally on the next collector tick. A second `RestoreRequested` for an id no longer in the archived list is harmless: the Fake throws `IllegalArgumentException`, which the fire-and-forget swallows. Acceptable for Phase 0.
- `BackTapped` is a `Unit` arm. Navigation lives in the NavHost; the arm exists so the screen can hand the VM a single uniform `onEvent` lambda rather than carrying a separate `onBack: () -> Unit` callback. Same pattern as `DiscussionListEvent.BackTapped`.

No `navigationEvents` channel — restore stays on-screen and back is owned by the NavHost.

### `ArchivedDiscussionsScreen` (stateless)

```kotlin
@Composable
fun ArchivedDiscussionsScreen(
    state: ArchivedDiscussionsUiState,
    onEvent: (ArchivedDiscussionsEvent) -> Unit,
    modifier: Modifier = Modifier,
)
```

`Scaffold` with an M3 `TopAppBar` — title `stringResource(R.string.archived_discussions_title)` ("Archived discussions"), `navigationIcon` the canonical back-arrow `IconButton` (`Icons.AutoMirrored.Filled.ArrowBack` + reused `R.string.cd_back`) that dispatches `BackTapped`. Body branches on the `UiState`:

- `Loading` → centered `"Loading…"` via a file-private `CenteredText(text, modifier)` helper
- `Empty` → centered `stringResource(R.string.archived_discussions_empty)` ("No archived discussions")
- `Error(message)` → centered `"Couldn't load archived discussions: $message"`
- `Loaded(discussions)` → `LazyColumn` keyed by `it.id`, items rendered as the file-private `ArchivedDiscussionRow`

`ArchivedDiscussionRow(discussion, onRestore, menuInitiallyExpanded = false)` is a `Box` wrapping the shared [`ConversationRow`](conversation-row.md) with:

- `Modifier.alpha(0.65f)` — same muted treatment `DiscussionListScreen` uses for its secondary-tier rows (#69), lifted to here as the archived-tier signal. Reusing one alpha across both signals is deliberate; they don't coexist in the same screen, so re-using the number is correct.
- `onLongClick = { menuExpanded = true }` — uses the optional `onLongClick: (() -> Unit)?` parameter on `ConversationRow` (added in #25), which switches the row's modifier chain from `clickable` to `combinedClickable`.
- A sibling `DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false })` with one `DropdownMenuItem` "Restore" (`stringResource(R.string.restore_action)`) that dismisses the menu and dispatches `ArchivedDiscussionsEvent.RestoreRequested(discussion.id)`.
- A `Box` wraps the row + menu so the menu anchors visually to the row. Same shape `DiscussionListScreen`'s `DiscussionRow` uses for its long-press menu (#25); this is the second use, so it's the pattern for "attach a `DropdownMenu` to a shared row composable without editing the row."

**Exactly one gesture, not both.** The ticket's "exactly one gesture, not both" clause is taken literally: long-press only. No `SwipeToDismissBox`. `DiscussionListScreen`'s save-as-channel action ships both gestures, but that was a deliberate redundancy for a more destructive-feeling action; restore is unambiguously reversible.

`menuInitiallyExpanded` is a default-`false` parameter so a `@Preview` can render the dropdown open without driving it through gesture state. Same affordance `DiscussionListScreen` exposes on its menu preview.

### Settings row + nav graph

`SettingsScreen` gains a required `onOpenArchivedDiscussions: () -> Unit` parameter (no default — navigation/event callbacks never carry defaults; rule established in #87 and #91). The pre-existing Storage row in `SettingsScreen`'s body is rewritten in place:

- Headline → `stringResource(R.string.archived_discussions_settings_row)` ("Archived discussions"). The row only navigates to archived **discussions**, not archived channels, so the literal matches.
- `supporting = "11 archived"` placeholder line dropped in #94 — and brought back as a real live count in #164 via the new `R.string.archived_discussions_count_supporting` format resource, backed by `SettingsViewModel.archivedDiscussionCount`.
- `onClick = onOpenArchivedDiscussions`.

`MainActivity.PyryNavHost`:

```kotlin
composable(Routes.SETTINGS) {
    val vm = koinViewModel<SettingsViewModel>()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    SettingsScreen(
        themeMode = themeMode,
        onSelectTheme = vm::onSelectTheme,
        onBack = { navController.popBackStack() },
        onOpenArchivedDiscussions = { navController.navigate(Routes.ARCHIVED_DISCUSSIONS) },
    )
}
composable(Routes.ARCHIVED_DISCUSSIONS) {
    val vm = koinViewModel<ArchivedDiscussionsViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    ArchivedDiscussionsScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                ArchivedDiscussionsEvent.BackTapped -> navController.popBackStack()
                is ArchivedDiscussionsEvent.RestoreRequested -> vm.onEvent(event)
            }
        },
    )
}
```

`Routes.ARCHIVED_DISCUSSIONS = "archived_discussions"`. The NavHost intercepts `BackTapped` before forwarding to the VM (the VM's `Unit` arm is a no-op); `RestoreRequested` is forwarded as-is.

Koin: `viewModel { ArchivedDiscussionsViewModel(get()) }` in `appModule`.

### Strings

Four new ids in `app/src/main/res/values/strings.xml`:

- `archived_discussions_title` → `"Archived discussions"` (TopAppBar)
- `archived_discussions_empty` → `"No archived discussions"`
- `archived_discussions_settings_row` → `"Archived discussions"` (Settings row headline; duplicates the title literal but lives separately so the two surfaces can diverge without forking a usage site)
- `restore_action` → `"Restore"` (dropdown item)

`cd_back` reused unchanged.

## Configuration / usage

Mounted at `Routes.ARCHIVED_DISCUSSIONS` (`"archived_discussions"`) in [`PyryNavHost`](navigation.md). Sole entry point: the [Settings screen](settings-screen.md) Storage section's "Archived discussions" row. No deep-link, no back-stack policy beyond the default `popBackStack()` on `BackTapped`.

Manual verification path (per the spec):

1. `./gradlew installDebug` → open app → tap settings gear in the channel-list `TopAppBar` → scroll Settings to **Storage** → tap **Archived discussions**.
2. The seeded archived discussion (`seed-discussion-archived`, lastUsedAt `2026-04-15`) renders as the only row with `alpha(0.65f)`.
3. Long-press the row → **Restore** dropdown → tap → row leaves the list, state transitions to `Empty` ("No archived discussions").
4. Back-arrow twice → channel list → restored discussion appears under Recent discussions.
5. Toggle dark mode via Settings → Theme; revisit (kill + relaunch to reseed). Both light + dark variants render.

## Edge cases / limitations

- **"Archive date" trailing slot is `formatRelativeTime(lastUsedAt)`, not a true `archivedAt`.** `Conversation` has no `archivedAt: Instant?` field; `archive(id)` (#93) flips a boolean without stamping a timestamp. The existing trailing slot prints "Apr 15" for the seed — close to the AC's "archive date" but not literally it. The natural owner of `archivedAt` is the 30-day auto-archive worker (it needs the timestamp to decide which records to evict); add the field there, then switch this screen's trailing slot to format it.
- **Restore failure is not user-visible.** `viewModelScope.launch { repository.unarchive(id) }` fire-and-forget. The Fake's only failure path is `IllegalArgumentException("Unknown conversation: $id")` for an id no longer present, which is harmless (the row is already gone from the UI by the time the throw lands). Phase 4's Ktor remote will introduce real failure modes; the visible-error / retry surface lands then, not here.
- **Archived **channels** are not shown.** The repository's `Archived` filter is `isPromoted`-agnostic by design (so it composes for a future archived-channels screen); this VM applies a `!isPromoted` post-filter. No production flow currently archives a channel, but the screen would still hide it if one existed. The path for a parallel archived-channels view is "a new VM that filters the same stream to `isPromoted`."
- **Settings entry-row count mirrors this screen's filter.** Since #164 the Settings row reads `"N archived"` where N = `count { !it.isPromoted }` on the same `Archived` upstream — same filter literal as this VM. If this VM's filter ever changes (e.g. excluding pinned archived rows), the Settings projection must change in lockstep, or the row and the list will disagree. See [Settings ViewModel § archivedDiscussionCount](settings-viewmodel.md).

## Testing

`ArchivedDiscussionsViewModelTest` (unit) at `app/src/test/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModelTest.kt` — nine `@Test` methods, mirroring `DiscussionListViewModelTest`'s idiom (`Dispatchers.setMain(UnconfinedTestDispatcher())` + `runTest` + `advanceUntilIdle`; anonymous `ConversationRepository` stub with `TODO("not used")` on unused methods; `RecordingRepo` capturing `unarchiveCalls: List<String>` for the restore-action path):

- `initialState_isLoading` — fresh VM, no collector, `state.value == Loading`.
- `loaded_passesArchivedFilter` — verifies the captured `ConversationFilter` is `Archived`.
- `loaded_dropsPromotedArchivedFromList` — source emits `[archivedDiscussion, archivedChannel]`; VM exposes only the discussion (locks in the `!isPromoted` post-filter).
- `empty_whenSourceEmitsEmptyList` — empty list → `Empty`.
- `empty_whenAllArchivedAreChannels` — source emits `[archivedChannel]` only; VM yields `Empty`.
- `restoreRequested_callsUnarchive_withConversationId` — `RecordingRepo` asserts `unarchiveCalls == [id]`.
- `error_whenSourceFlowThrows` — stream throws → `Error("network down")`.
- `error_messageIsNonBlank_whenExceptionMessageIsNull` — null message → non-blank fallback.

No instrumented Compose-test for the screen. The codebase has no precedent for shipping a Compose-test on every new screen (`DiscussionListScreenTest` exists but the VM contract is the binding spec). Manual verification against the `seed-discussion-archived` seed covers the visual path; a follow-up belt-and-suspenders test can mirror `DiscussionListScreenTest`'s shape if a regression appears.

`SettingsScreenTest`'s two `setContent` blocks were updated to propagate `onOpenArchivedDiscussions = {}` so they keep compiling against the new required parameter; no new test methods.

## Previews

Three `@Preview`s, all `private`, all `widthDp = 412`:

- `ArchivedDiscussionsScreenLoadedPreview` — light, one seeded archived discussion.
- `ArchivedDiscussionsScreenEmptyPreview` — light, `UiState.Empty`.
- `ArchivedDiscussionsRowMenuPreview` — light, `ArchivedDiscussionRow` rendered standalone with `menuInitiallyExpanded = true` so the dropdown is visible.

No dark previews this slice — the muted-alpha treatment renders identically modulo the underlying theme, and the screen reuses the same `ConversationRow` that already has dark coverage elsewhere.

## Related

- Ticket notes: [`../codebase/94.md`](../codebase/94.md); [`../codebase/164.md`](../codebase/164.md) (Settings-side live-count projection over the same `Archived` upstream)
- Spec: `docs/specs/architecture/94-archived-discussions-screen.md`
- Data foundations: [`../codebase/93.md`](../codebase/93.md) (`Conversation.archived` + `ConversationFilter.Archived` + seed), [`../codebase/96.md`](../codebase/96.md) (`unarchive(id)` primitive)
- Sibling screens: [Discussion list screen](discussion-list-screen.md) (shape it mirrors — `Scaffold` + back-arrow + `LazyColumn` + long-press menu + `alpha(0.65f)`; one tier different). Was siblings with `LicenseScreen` (#91) under `ui/settings/` until #163 deleted that screen.
- Hosted ViewModel pattern: similar in shape to [Discussion list view-model](discussion-list-viewmodel.md), simpler — no `combine` (one upstream), no `navigationEvents` channel.
- Entry point: [Settings screen](settings-screen.md) Storage section row
- Navigation: [Navigation](navigation.md) — route `archived_discussions`
- Figma: N/A (no dedicated Archived Discussions view exists in the canonical Figma file `g2HIq2UyPhslEoHRokQmHG` yet — visuals derive from the Recent Discussions row treatment in #69 with the muted-alpha state signal). A dedicated Figma view is a recommended follow-up.
- Follow-ups: (a) `Conversation.archivedAt: Instant?` stamped by the 30-day auto-archive worker, then this screen formats it instead of `lastUsedAt`; (b) dedicated Figma view; (c) visible-error / retry surface when Phase 4's Ktor remote arrives.
