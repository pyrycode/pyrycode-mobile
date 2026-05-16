# Archived Discussions screen

Recovery surface for archived conversations that the (forthcoming) 30-day auto-archive worker has evicted. Reached from Settings → "Archived discussions" since #94. Stateless screen + small ViewModel + nav-graph entry; shipped under `ui/settings/` (alongside `SettingsScreen`) because discoverability follows the entry point, not the entity. Locked to Figma node `18:2` since #176 — segmented 2-tab header (Channels / Discussions) over the shared `ArchivedDiscussionRow`. Was the sibling of `LicenseScreen` from #91 to #163; #163 deleted `LicenseScreen` along with its route, asset, and test once its Settings entry point disappeared.

The package name and the public type names (`ArchivedDiscussionsScreen`, `ArchivedDiscussionsViewModel`, `ArchivedDiscussionsUiState`, `ArchivedDiscussionsEvent`) are unchanged from #94 even though #176 broadened the scope to "archive surface for both tiers" — rename to `Archive*` was offered by the #176 spec as optional and the developer kept the names to minimise diff (would have churned `MainActivity`, the Koin binding, and three test files for zero behavior gain). Treat "Archived Discussions" in the type names as a historical artefact of the screen's origin; the surface itself now covers archived channels and archived discussions equally.

## What it does

Lists archived conversations partitioned into two tabs — **Channels** (`isPromoted = true`) and **Discussions** (`isPromoted = false`) — with live counts in each tab label (e.g. `Channels (3)`, `Discussions (8)`). Default tab is **Discussions** (preserves the pre-#176 landing surface). Tapping a tab switches the body's source between the two partitions. Long-press a row → "Restore" dropdown → tap → `ConversationRepository.unarchive(id)` flips the bit (#96), the row leaves the `Archived` filter on the next stream emission, and the restored conversation reappears in its tier's primary surface (Recent discussions for an unpromoted, channel list for a promoted). The 30-day auto-archive worker itself is a separate ticket — this screen assumes archived rows exist (the `seed-discussion-archived` seed from #93 guarantees one for manual verification; archived channels currently rely on the test-time seed builder or hand-rolled scenarios).

The matching Settings row gained a live "N archived" supporting line in #164 — see [Settings screen § Storage](settings-screen.md) and [Settings ViewModel](settings-viewmodel.md). That projection currently reads `count { !it.isPromoted }` so it counts archived **discussions** only; archived channels are invisible on the Settings entry by design (the #164 projection mirrored the pre-#176 screen's `!isPromoted` post-filter). With #176 the screen itself surfaces archived channels too — a follow-up could promote the Settings projection to `count { archived = true }` for parity, but the current divergence is documented and tolerable.

The two consumers of the same `observeConversations(ConversationFilter.Archived)` flow now demonstrate a split that goes back to #164: this screen surfaces `Error(message)` because the list IS the screen's primary content; the Settings row's projection swallows the same upstream's errors via `.catch { emit(0) }` because it's supportive metadata about *this* screen.

## How it works

### `ArchivedDiscussionsViewModel`

```kotlin
enum class ArchiveTab { Channels, Discussions }

sealed interface ArchivedDiscussionsUiState {
    data object Loading : ArchivedDiscussionsUiState
    data class Loaded(
        val channels: List<Conversation>,       // archived && isPromoted
        val discussions: List<Conversation>,    // archived && !isPromoted
        val selectedTab: ArchiveTab,
    ) : ArchivedDiscussionsUiState
    data class Error(val message: String) : ArchivedDiscussionsUiState
}

sealed interface ArchivedDiscussionsEvent {
    data class RestoreRequested(val conversationId: String) : ArchivedDiscussionsEvent
    data object BackTapped : ArchivedDiscussionsEvent
    data class TabSelected(val tab: ArchiveTab) : ArchivedDiscussionsEvent
}

class ArchivedDiscussionsViewModel(private val repository: ConversationRepository) : ViewModel()
```

State is built by combining the data stream with a UI-driven tab selector:

```kotlin
private val selectedTab = MutableStateFlow(ArchiveTab.Discussions)  // AC §3 default

val state: StateFlow<ArchivedDiscussionsUiState> =
    combine(
        repository.observeConversations(ConversationFilter.Archived),
        selectedTab,
    ) { conversations, tab ->
        ArchivedDiscussionsUiState.Loaded(
            channels    = conversations.filter { it.isPromoted },
            discussions = conversations.filter { !it.isPromoted },
            selectedTab = tab,
        ) as ArchivedDiscussionsUiState   // widening cast is required — see Lessons learned in 176.md
    }.catch { e ->
        emit(ArchivedDiscussionsUiState.Error(
            if (e.message.isNullOrBlank()) "Failed to load archived discussions." else e.message!!
        ))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), Loading)
```

Three things to know:

1. **There is no top-level `Empty` state since #176.** AC §5's "tab header stays visible while one tier is empty" requires `Loaded` to render even when `channels` or `discussions` is empty — the chrome (tab row) needs counts from both lists at all times, so a single top-level `Empty` couldn't carry them. Per-tab emptiness is computed in the body from `Loaded.channels.isEmpty()` / `Loaded.discussions.isEmpty()`.
2. **`Loaded` carries both lists.** Tab switching is a pure UI re-render — the `combine` re-emits with the new tab slot while the data side stays cached at its last value. No re-collection of the upstream cold flow.
3. **The `!isPromoted` post-filter from #94 is gone.** The pre-#176 VM applied `it.filter { !it.isPromoted }` to drop archived channels client-side; #176 keeps both partitions because the screen now needs them. The repository's `Archived` filter (#93) is still `isPromoted`-agnostic, as designed for this exact composition.

`onEvent` handles three cases:

- `RestoreRequested(id)` → `viewModelScope.launch { repository.unarchive(id) }`. Fire-and-forget; the repository's `MutableStateFlow.update` re-emits and the row leaves the `Archived` projection naturally on the next collector tick. No optimistic-local-state.
- `TabSelected(tab)` → `selectedTab.value = event.tab`. Non-suspending — `MutableStateFlow.value` setter is not `suspend`, so no `viewModelScope.launch`.
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

`Scaffold` with an M3 `TopAppBar` — title `stringResource(R.string.archived_title)` ("Archived" since #176; replaces the pre-#176 "Archived discussions"), `navigationIcon` the canonical back-arrow `IconButton` (`Icons.AutoMirrored.Filled.ArrowBack` + reused `R.string.cd_back`) that dispatches `BackTapped`. Body branches on the `UiState`:

- `Loading` → centered `"Loading…"` via a file-private `CenteredText(text, modifier)` helper.
- `Error(message)` → centered `"Couldn't load archived discussions: $message"`. Note the literal still reads "discussions" — error-path copy refresh is explicitly out of scope per the #176 spec, even though the title flipped to the single word "Archived". Refresh in a follow-up if/when error UX is revisited.
- `Loaded` → file-private `LoadedBody(state, onEvent, modifier)` composable.

`LoadedBody` is a `Column` containing:

1. **Tab header.** `SecondaryTabRow(selectedTabIndex = state.selectedTab.ordinal) { Tab(…); Tab(…) }`. The two `Tab` children correspond to `ArchiveTab.Channels` (ordinal 0) and `ArchiveTab.Discussions` (ordinal 1) — the ordinal-to-position mapping is implicit; if the enum gains a value in front of `Channels` later, this breaks silently and the call site must swap to an explicit lookup. Each `Tab`'s `selected` is `state.selectedTab == <variant>`, `onClick` dispatches `TabSelected(<variant>)`, and `text` reads `stringResource(R.string.archived_tab_<variant>, state.<list>.size)` — the `%1$d` count is interpolated live from the partition size.
   - **No explicit `indicator =` override.** `SecondaryTabRow`'s default indicator is already a 2dp `colorScheme.primary` underline matching Figma 18:2 — the spec's drafted `SecondaryIndicator(Modifier.tabIndicatorOffset(…), color = MaterialTheme.colorScheme.primary, height = 2.dp)` override is dropped. Chosen over `PrimaryTabRow` because Primary's M3 rounded-pill indicator doesn't match the flat 2dp underline Figma specifies.
2. **Body.** Selected tab → `val items = when (state.selectedTab) { Channels -> state.channels; Discussions -> state.discussions }`. If `items.isEmpty()`, renders a centered `stringResource(R.string.archived_empty_<variant>)` ("No archived channels" or "No archived discussions") **below the tab header** — the header stays mounted, satisfying AC §5. Otherwise renders a `LazyColumn` keyed by `it.id` of the file-private `ArchivedDiscussionRow` for each item.

The same `ArchivedDiscussionRow` is reused for both tabs (the row composable is `isPromoted`-agnostic — it's a faded `ConversationRow` + long-press dropdown). An archive-specific row redesign with inline restore + snackbar is the follow-up child of #125 and explicitly **not** part of #176.

### `ArchivedDiscussionRow` (file-private)

```kotlin
@Composable
private fun ArchivedDiscussionRow(
    discussion: Conversation,             // typed for the pre-#176 callsite; reads any Conversation
    onRestore: () -> Unit,
    menuInitiallyExpanded: Boolean = false,
)
```

A `Box` wrapping the shared [`ConversationRow`](conversation-row.md) with:

- `Modifier.alpha(0.65f)` — same muted treatment `DiscussionListScreen` uses for its secondary-tier rows (#69), lifted here as the archived-tier signal. Reusing one alpha across both signals is deliberate; they don't coexist in the same screen, so re-using the number is correct.
- `onLongClick = { menuExpanded = true }` — uses the optional `onLongClick: (() -> Unit)?` parameter on `ConversationRow` (added in #25), which switches the row's modifier chain from `clickable` to `combinedClickable`.
- A sibling `DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false })` with one `DropdownMenuItem` "Restore" (`stringResource(R.string.restore_action)`) that dismisses the menu and dispatches `ArchivedDiscussionsEvent.RestoreRequested(discussion.id)`.
- A `Box` wraps the row + menu so the menu anchors visually to the row. Same shape `DiscussionListScreen`'s `DiscussionRow` uses for its long-press menu (#25); the second use established this as the pattern for "attach a `DropdownMenu` to a shared row composable without editing the row."

The parameter name `discussion: Conversation` is a legacy from #94 when the row only handled the unpromoted tier; the underlying type is `Conversation`, so the row renders an archived channel correctly when passed one in #176's Channels tab. A rename to `conversation` is a tolerable cleanup if a future ticket touches the file for other reasons — not worth a churn-only PR.

**Exactly one gesture, not both.** The #94 "exactly one gesture, not both" clause carried into #176: long-press only. No `SwipeToDismissBox`. `DiscussionListScreen`'s save-as-channel action ships both gestures, but that was a deliberate redundancy for a more destructive-feeling action; restore is unambiguously reversible. The archive-specific row redesign (#125 follow-up child) replaces the long-press dropdown with an inline restore button + snackbar.

`menuInitiallyExpanded` is a default-`false` parameter so a `@Preview` can render the dropdown open without driving it through gesture state.

### Settings row + nav graph

`SettingsScreen` carries a required `onOpenArchivedDiscussions: () -> Unit` parameter (no default — navigation/event callbacks never carry defaults; rule established in #87 and #91); the Storage row's headline reads `stringResource(R.string.archived_discussions_settings_row)` ("Archived discussions" — the Settings copy literal kept the pre-#176 wording even after the screen title flipped to "Archived"; the two are deliberately separate string ids so they can diverge without forking a usage site). The row's `supporting = stringResource(R.string.archived_discussions_count_supporting, archivedDiscussionCount)` ("N archived") is driven by [`SettingsViewModel.archivedDiscussionCount`](settings-viewmodel.md) since #164.

`MainActivity.PyryNavHost`:

```kotlin
composable(Routes.SETTINGS) {
    val vm = koinViewModel<SettingsViewModel>()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val useWallpaperColors by vm.useWallpaperColors.collectAsStateWithLifecycle()
    val archivedDiscussionCount by vm.archivedDiscussionCount.collectAsStateWithLifecycle()
    SettingsScreen(
        themeMode = themeMode,
        useWallpaperColors = useWallpaperColors,
        archivedDiscussionCount = archivedDiscussionCount,
        onSelectTheme = vm::onSelectTheme,
        onToggleUseWallpaperColors = vm::onToggleUseWallpaperColors,
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
                is ArchivedDiscussionsEvent.TabSelected -> vm.onEvent(event)
            }
        },
    )
}
```

`Routes.ARCHIVED_DISCUSSIONS = "archived_discussions"`. The NavHost intercepts `BackTapped` before forwarding to the VM (the VM's `Unit` arm is a no-op); `RestoreRequested` and `TabSelected` (since #176) are forwarded as-is.

Koin: `viewModel { ArchivedDiscussionsViewModel(get()) }` in `appModule` — constructor signature is unchanged across #94/#176 (the `selectedTab` flow is constructed in the field initializer, not injected).

### Strings

Resource set in `app/src/main/res/values/strings.xml` after #176:

- `archived_title` → `"Archived"` (TopAppBar; since #176 — replaces `archived_discussions_title`)
- `archived_tab_channels` → `"Channels (%1$d)"` (tab label with live count; since #176)
- `archived_tab_discussions` → `"Discussions (%1$d)"` (tab label with live count; since #176)
- `archived_empty_channels` → `"No archived channels"` (per-tab empty; since #176)
- `archived_empty_discussions` → `"No archived discussions"` (per-tab empty; since #176 — replaces the screen-level `archived_discussions_empty`)
- `archived_discussions_settings_row` → `"Archived discussions"` (Settings row headline; unchanged across #94/#176 — Settings copy intentionally kept the pre-#176 wording)
- `archived_discussions_count_supporting` → `"%1$d archived"` (Settings row supporting line, live count; since #164)
- `restore_action` → `"Restore"` (dropdown item; unchanged across #94/#176)

`cd_back` reused unchanged. The pre-#176 `archived_discussions_title` and `archived_discussions_empty` were deleted in #176 — both had a single consumer in this screen and the broader title flip + per-tier empty messages obsoleted them.

Plurals deferred: `"0 / 1 / 3 archived"` are all grammatical without inflection, the codebase has no plurals precedent yet, and switching to a `<plurals>` resource later is mechanical. YAGNI.

## Configuration / usage

Mounted at `Routes.ARCHIVED_DISCUSSIONS` (`"archived_discussions"`) in [`PyryNavHost`](navigation.md). Sole entry point: the [Settings screen](settings-screen.md) Storage section's "Archived discussions" row. No deep-link, no back-stack policy beyond the default `popBackStack()` on `BackTapped`.

Manual verification path (post-#176):

1. `./gradlew installDebug` → open app → tap settings gear in the channel-list `TopAppBar` → scroll Settings to **Storage** → tap **Archived discussions**.
2. The seeded archived discussion (`seed-discussion-archived`, lastUsedAt `2026-04-15`) renders under the **Discussions** tab (default) with `alpha(0.65f)`. Both tab labels show counts (`Channels (0)`, `Discussions (1)`).
3. Tap the **Channels** tab → "No archived channels" centered empty body; tab header stays visible, count badges unchanged.
4. Tap back to **Discussions** → row reappears. Long-press the row → **Restore** dropdown → tap → row leaves the list, Discussions tab body shows "No archived discussions".
5. Back-arrow twice → channel list → restored discussion appears under Recent discussions.
6. Toggle dark mode via Settings → Theme; revisit (kill + relaunch to reseed). Both light + dark variants render.

Tab selection survives recomposition / rotation (`MutableStateFlow` in the VM, VM survives configuration change). Leaving the screen lets `WhileSubscribed(5_000L)` keep the VM warm for 5s; popping the back-stack within that grace window restores the previously-selected tab, popping after re-creates the VM with `Discussions` again. Acceptable per the #176 spec — `rememberSaveable` was the alternative and was deliberately not chosen so unit tests can assert tab-selection deterministically.

## Edge cases / limitations

- **"Archive date" trailing slot is `formatRelativeTime(lastUsedAt)`, not a true `archivedAt`.** `Conversation` has no `archivedAt: Instant?` field; `archive(id)` (#93) flips a boolean without stamping a timestamp. The existing trailing slot prints "Apr 15" for the seed — close to the AC's "archive date" but not literally it. The natural owner of `archivedAt` is the 30-day auto-archive worker (it needs the timestamp to decide which records to evict); add the field there, then switch this screen's trailing slot to format it.
- **Restore failure is not user-visible.** `viewModelScope.launch { repository.unarchive(id) }` fire-and-forget. The Fake's only failure path is `IllegalArgumentException("Unknown conversation: $id")` for an id no longer present, which is harmless (the row is already gone from the UI by the time the throw lands). Phase 4's Ktor remote will introduce real failure modes; the visible-error / retry surface lands then, not here. The archive-specific row redesign (#125 follow-up child) introduces a snackbar that surfaces both the restore confirmation and any failures.
- **Settings entry-row count counts only archived discussions, not archived channels.** Since #164 the Settings row reads `"N archived"` where N = `count { !it.isPromoted }` on the same `Archived` upstream — the pre-#176 screen-side filter that #176 walked back. Archived channels are now first-class in this screen but invisible on the Settings entry. A follow-up could promote the Settings projection to `count { archived = true }` for parity; not done here because (a) the Settings copy literal is still "Archived discussions" and (b) the count number's contract with users hasn't changed yet. Documented divergence.
- **`SecondaryTabRow`'s `selectedTabIndex = state.selectedTab.ordinal` mapping is positional.** `ArchiveTab.Channels.ordinal == 0` matches the first `Tab` child position; `ArchiveTab.Discussions.ordinal == 1` matches the second. If the enum grows a new value **in front of** `Channels` (e.g. `enum class ArchiveTab { All, Channels, Discussions }`), the ordinal mapping silently shifts and the wrong tab is rendered as active. Adding values to the end is safe; reordering is the risk. The mitigation in the screen is to read the ordinal once at `selectedTabIndex = state.selectedTab.ordinal` and to spell out the per-`Tab` `selected = (state.selectedTab == <variant>)` check explicitly, so a re-ordering would surface as a "tab indicator under tab X but tab Y's body content" visual mismatch in previews. Not enforced in code; carry as a known risk if the enum ever grows.
- **Tab selection resets to `Discussions` after the `WhileSubscribed(5_000L)` grace expires.** Documented above; tolerable because Phase 0 has no process-death scenario reachable on the fake-data path. If Phase 4's real backend introduces durable session state, revisit whether tab selection should survive longer (probably via `SavedStateHandle`, not `rememberSaveable` — the VM is still the right home).

## Testing

`ArchivedDiscussionsViewModelTest` (unit) at `app/src/test/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModelTest.kt` — 13 `@Test` methods (post-#176), mirroring `DiscussionListViewModelTest`'s idiom (`Dispatchers.setMain(UnconfinedTestDispatcher())` + `runTest` + `advanceUntilIdle`; anonymous `ConversationRepository` stub with `TODO("not used")` on unused methods; `RecordingRepo` capturing `unarchiveCalls: List<String>` for the restore-action path):

- `initialState_isLoading` — fresh VM, no collector, `state.value == Loading`.
- `loaded_passesArchivedFilter` — verifies the captured `ConversationFilter` is `Archived`.
- `loaded_partitionsByIsPromoted` — emit `[discussion-A, channel-B, discussion-C]` → `Loaded(channels = [channel-B], discussions = [discussion-A, discussion-C], selectedTab = Discussions)`. Replaces the pre-#176 `loaded_dropsPromotedArchivedFromList` (which was the `!isPromoted` post-filter lock).
- `loaded_defaultSelectedTab_isDiscussions` — first `Loaded` emission has `selectedTab == ArchiveTab.Discussions`. AC §3 contract; fails if anyone flips the default later.
- `tabSelected_channels_updatesStateOnly` — dispatch `TabSelected(Channels)` → same lists, `selectedTab == Channels`. Confirms tab switch does not re-collect or re-partition.
- `tabSelected_discussions_returnsToDefault` — flip to Channels then back to Discussions → `selectedTab == Discussions`.
- `loaded_channels_emptyWhenAllUnpromoted` — emit `[discussion]` → `Loaded.channels.isEmpty()`, `Loaded.discussions == [discussion]`.
- `loaded_discussions_emptyWhenAllPromoted` — emit `[channel]` → `Loaded.discussions.isEmpty()`, `Loaded.channels == [channel]`. Replaces the pre-#176 `empty_whenAllArchivedAreChannels`.
- `loaded_bothEmpty_whenSourceEmitsEmptyList` — emit `[]` → `Loaded(channels = [], discussions = [], selectedTab = Discussions)`. Replaces the pre-#176 `empty_whenSourceEmitsEmptyList`; the new shape asserts the 3-field `Loaded` rather than the deleted top-level `Empty`.
- `restoreRequested_callsUnarchive_withConversationId` — `RecordingRepo` asserts `unarchiveCalls == [id]`. (Unchanged from #94.)
- `backTapped_doesNotCallUnarchive` — `BackTapped` is a no-op for the repo. (Unchanged from #94.)
- `error_whenSourceFlowThrows` — stream throws → `Error("network down")`. (Unchanged from #94.)
- `error_messageIsNonBlank_whenExceptionMessageIsNull` — null message → non-blank fallback. (Unchanged from #94.)

No instrumented Compose tests. Pre-#176 the screen had none and #176 didn't add any; the four `@Preview`s (Loaded-Discussions, Loaded-Channels, Loaded-Discussions-empty, long-press menu open) provide the visual coverage. A follow-up belt-and-suspenders Compose test can mirror `DiscussionListScreenTest`'s shape if a regression appears.

`SettingsScreenTest`'s `setContent` blocks were not touched by #176 — the Settings-side parameters and the new `archivedDiscussionCount` arg are #164 territory, independent of this slice.

## Previews

Four `@Preview`s, all `private`, all `widthDp = 412`, all `darkTheme = false` (post-#176):

- `ArchivedScreenDiscussionsPreview` — Discussions tab active, one archived channel + one archived discussion seeded so both tab counts are non-zero. Discussions list renders as the body.
- `ArchivedScreenChannelsPreview` — Channels tab active, same seeds, channel list renders as the body.
- `ArchivedScreenDiscussionsEmptyPreview` — Discussions tab active, one archived channel seeded + zero archived discussions, body renders the "No archived discussions" centered empty state with the tab header still visible (AC §5 visual lock).
- `ArchivedDiscussionsRowMenuPreview` — `ArchivedDiscussionRow` rendered standalone with `menuInitiallyExpanded = true` so the dropdown is visible. Unchanged across #94/#176.

No dark previews this slice — the muted-alpha treatment renders identically modulo the underlying theme, and the screen reuses the same `ConversationRow` + `SecondaryTabRow` that already have dark coverage elsewhere. A Channels-empty preview was considered and dropped (symmetrical to `ArchivedScreenDiscussionsEmptyPreview` and would add no new visual contract).

## Related

- Ticket notes:
  - [`../codebase/94.md`](../codebase/94.md) — original screen + VM + long-press dropdown
  - [`../codebase/176.md`](../codebase/176.md) — 2-tab segmented header + VM partition + `TabSelected` event
  - [`../codebase/164.md`](../codebase/164.md) — Settings-side live-count projection over the same `Archived` upstream
- Specs: `docs/specs/architecture/94-archived-discussions-screen.md`, `docs/specs/architecture/176-archive-2-tab-header.md`
- Parent: split from #125 (the broader archive overhaul; the archive-specific row + inline restore + snackbar is the follow-up child of #125)
- Data foundations: [`../codebase/93.md`](../codebase/93.md) (`Conversation.archived` + `ConversationFilter.Archived` + seed), [`../codebase/96.md`](../codebase/96.md) (`unarchive(id)` primitive)
- Sibling screens: [Discussion list screen](discussion-list-screen.md) (shape this screen mirrors at the row level — `Scaffold` + back-arrow + `LazyColumn` + long-press menu + `alpha(0.65f)`); the segmented-tab pattern is new to the codebase with #176 and a future tabbed screen should follow the `MutableStateFlow<X> + combine` shape established here. Was siblings with `LicenseScreen` (#91) under `ui/settings/` until #163 deleted that screen.
- Hosted ViewModel pattern: similar in shape to [Discussion list view-model](discussion-list-viewmodel.md), now with a UI-state slot (`selectedTab`) `combine`d alongside the data source — see #176's "Patterns established" for the rule.
- Entry point: [Settings screen](settings-screen.md) Storage section row
- Navigation: [Navigation](navigation.md) — route `archived_discussions`
- Figma:
  - Original (no dedicated frame) — pre-#176 visuals derived from Recent Discussions row treatment in #69 with muted-alpha state signal
  - 18:2 — https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=18-2 (locked since #176)
- Follow-ups: (a) `Conversation.archivedAt: Instant?` stamped by the 30-day auto-archive worker, then this screen formats it instead of `lastUsedAt`; (b) archive-specific row redesign + inline restore + snackbar (#125 follow-up child); (c) Settings projection promoted to `count { archived = true }` for parity with the new Channels tab; (d) visible-error / retry surface when Phase 4's Ktor remote arrives.
