# Archived Discussions screen

Recovery surface for archived conversations that the (forthcoming) 30-day auto-archive worker has evicted. Reached from Settings → "Archived discussions" since #94. Stateless screen + small ViewModel + nav-graph entry; shipped under `ui/settings/` (alongside `SettingsScreen`) because discoverability follows the entry point, not the entity. Locked to Figma node `18:2` since #176 — segmented 2-tab header (Channels / Discussions) over an `ArchiveRow` (#177; the pre-#177 file-private `ArchivedDiscussionRow` — faded `ConversationRow` + long-press dropdown — is gone). Was the sibling of `LicenseScreen` from #91 to #163; #163 deleted `LicenseScreen` along with its route, asset, and test once its Settings entry point disappeared.

The package name and the public type names (`ArchivedDiscussionsScreen`, `ArchivedDiscussionsViewModel`, `ArchivedDiscussionsUiState`, `ArchivedDiscussionsEvent`, `ArchivedDiscussionsEffect`) are unchanged from #94 even though #176 broadened the scope to "archive surface for both tiers" and #177 swapped the row treatment — rename to `Archive*` was offered by the #176 spec as optional and the developer kept the names to minimise diff (would have churned `MainActivity`, the Koin binding, and three test files for zero behavior gain). Treat "Archived Discussions" in the type names as a historical artefact of the screen's origin; the surface itself now covers archived channels and archived discussions equally.

## What it does

Lists archived conversations partitioned into two tabs — **Channels** (`isPromoted = true`) and **Discussions** (`isPromoted = false`) — with live counts in each tab label (e.g. `Channels (3)`, `Discussions (8)`). Default tab is **Discussions** (preserves the pre-#176 landing surface). Tapping a tab switches the body's source between the two partitions. Tap the trailing restore icon-button on a row (#177 — one-tap inline replaces the pre-#177 long-press dropdown) → `ConversationRepository.unarchive(id)` flips the bit (#96), the row leaves the `Archived` filter on the next stream emission, the restored conversation reappears in its tier's primary surface (Recent discussions for an unpromoted, channel list for a promoted), and a `Snackbar` with `Restored <name>` confirms via a `SnackbarHost` wired into the screen's `Scaffold`. The 30-day auto-archive worker itself is a separate ticket — this screen assumes archived rows exist (the `seed-discussion-archived` seed from #93 guarantees one for manual verification; archived channels currently rely on the test-time seed builder or hand-rolled scenarios).

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
    data class RestoreRequested(
        val conversationId: String,
        val displayName: String,                 // resolved-fallback display string; see #177
    ) : ArchivedDiscussionsEvent
    data object BackTapped : ArchivedDiscussionsEvent
    data class TabSelected(val tab: ArchiveTab) : ArchivedDiscussionsEvent
}

sealed interface ArchivedDiscussionsEffect {                  // since #177
    data class RestoreSucceeded(val displayName: String) : ArchivedDiscussionsEffect
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

- `RestoreRequested(id, displayName)` → `viewModelScope.launch { runCatching { repository.unarchive(id) }.onSuccess { _effects.send(RestoreSucceeded(displayName)) } }` since #177. The repository's `MutableStateFlow.update` re-emits and the row leaves the `Archived` projection naturally on the next collector tick; the success branch posts a one-shot effect that the screen reifies as a `Snackbar`. The `runCatching` is deliberate — pre-#177 the arm was a bare `repository.unarchive(id)` call and the Fake never throws, but Phase 4's Ktor remote will throw on transient failures and a bare throw leaks into `viewModelScope`'s default uncaught handler. Swallowing keeps the snackbar semantics ("on success only") intact and avoids the unhandled-exception path; the trade-off (no log on failure) is pre-flagged with an inline comment for the future `RestoreFailed(displayName)` effect path. See [`codebase/177.md`](../codebase/177.md) § Lessons learned.
- `TabSelected(tab)` → `selectedTab.value = event.tab`. Non-suspending — `MutableStateFlow.value` setter is not `suspend`, so no `viewModelScope.launch`.
- `BackTapped` is a `Unit` arm. Navigation lives in the NavHost; the arm exists so the screen can hand the VM a single uniform `onEvent` lambda rather than carrying a separate `onBack: () -> Unit` callback. Same pattern as `DiscussionListEvent.BackTapped`.

**Effect channel (since #177).** `private val _effects = Channel<ArchivedDiscussionsEffect>(Channel.BUFFERED)`, exposed publicly as `val effects: Flow<ArchivedDiscussionsEffect> = _effects.receiveAsFlow()`. First non-nav one-shot effect channel in the codebase — the established `navigationEvents` shape is reserved for `ChannelListNavigation` / `DiscussionListNavigation` (nav targets); UI effects (snackbar/toast) get their own channel exposed alongside. Both channels are valid on the same VM; future tickets that need both should declare them separately rather than folding one into the other.

No `navigationEvents` channel — restore stays on-screen and back is owned by the NavHost.

### `ArchivedDiscussionsScreen` (stateless)

```kotlin
@Composable
fun ArchivedDiscussionsScreen(
    state: ArchivedDiscussionsUiState,
    onEvent: (ArchivedDiscussionsEvent) -> Unit,
    modifier: Modifier = Modifier,
    effects: Flow<ArchivedDiscussionsEffect> = emptyFlow(),   // since #177
)
```

`Scaffold` with an M3 `TopAppBar` — title `stringResource(R.string.archived_title)` ("Archived" since #176; replaces the pre-#176 "Archived discussions"), `navigationIcon` the canonical back-arrow `IconButton` (`Icons.AutoMirrored.Filled.ArrowBack` + reused `R.string.cd_back`) that dispatches `BackTapped`, and (since #177) `snackbarHost = { SnackbarHost(snackbarHostState) }` — first `SnackbarHost` in the codebase. Above the `Scaffold`: `val snackbarHostState = remember { SnackbarHostState() }`, `val resources = LocalResources.current`, and a `LaunchedEffect(effects, snackbarHostState) { effects.collect { effect -> when (effect) { is RestoreSucceeded -> snackbarHostState.showSnackbar(resources.getString(R.string.restored_snackbar, effect.displayName)) } } }`. The default `effects = emptyFlow()` keeps the three `@Preview`s (which don't care about effects) call-site-compatible. Body branches on the `UiState`:

- `Loading` → centered `"Loading…"` via a file-private `CenteredText(text, modifier)` helper.
- `Error(message)` → centered `"Couldn't load archived discussions: $message"`. Note the literal still reads "discussions" — error-path copy refresh is explicitly out of scope per the #176 spec, even though the title flipped to the single word "Archived". Refresh in a follow-up if/when error UX is revisited.
- `Loaded` → file-private `LoadedBody(state, onEvent, modifier)` composable.

`LoadedBody` is a `Column` containing:

1. **Tab header.** `SecondaryTabRow(selectedTabIndex = state.selectedTab.ordinal) { Tab(…); Tab(…) }`. The two `Tab` children correspond to `ArchiveTab.Channels` (ordinal 0) and `ArchiveTab.Discussions` (ordinal 1) — the ordinal-to-position mapping is implicit; if the enum gains a value in front of `Channels` later, this breaks silently and the call site must swap to an explicit lookup. Each `Tab`'s `selected` is `state.selectedTab == <variant>`, `onClick` dispatches `TabSelected(<variant>)`, and `text` reads `stringResource(R.string.archived_tab_<variant>, state.<list>.size)` — the `%1$d` count is interpolated live from the partition size.
   - **No explicit `indicator =` override.** `SecondaryTabRow`'s default indicator is already a 2dp `colorScheme.primary` underline matching Figma 18:2 — the spec's drafted `SecondaryIndicator(Modifier.tabIndicatorOffset(…), color = MaterialTheme.colorScheme.primary, height = 2.dp)` override is dropped. Chosen over `PrimaryTabRow` because Primary's M3 rounded-pill indicator doesn't match the flat 2dp underline Figma specifies.
2. **Body.** Selected tab → `val items = when (state.selectedTab) { Channels -> state.channels; Discussions -> state.discussions }`. If `items.isEmpty()`, renders a centered `stringResource(R.string.archived_empty_<variant>)` ("No archived channels" or "No archived discussions") **below the tab header** — the header stays mounted, satisfying AC §5. Otherwise renders a `LazyColumn` keyed by `it.id` of [`ArchiveRow`](#archiverow-since-177) for each item. The row's `displayName` argument and the `RestoreRequested(id, displayName)` payload both read from a file-private `Conversation.displayName(): String` extension (`name?.takeIf { it.isNotBlank() } ?: if (isPromoted) "Untitled channel" else "Untitled discussion"`) — same fallback `ConversationRow` carries inline, deliberately duplicated rather than refactored shared per the #177 spec.

The same `ArchiveRow` is reused for both tabs (the row composable takes any `Conversation` and renders the archived treatment regardless of `isPromoted`).

### `ArchiveRow` (since #177)

```kotlin
@Composable
fun ArchiveRow(
    conversation: Conversation,
    displayName: String,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Public composable at `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ArchiveRow.kt`. Replaces the pre-#177 file-private `ArchivedDiscussionRow` (faded `ConversationRow` + long-press `DropdownMenu`). Renders as:

```kotlin
Row(fillMaxWidth, padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    ConversationAvatar(conversation)                                   // 40dp leading
    Column(Modifier.weight(1f), Arrangement.spacedBy(2.dp)) {
        Text(displayName, titleMedium, onSurface, maxLines=1, Ellipsis)
        Text("Archived ${formatRelativeTime(lastUsedAt)}",
             bodySmall, onSurfaceVariant,
             Modifier.alpha(0.75f), maxLines=1, Ellipsis)
    }
    IconButton(onClick = onRestore, Modifier.size(40.dp)) {             // 40dp tap target
        Icon(Icons.Filled.Refresh, "Restore $displayName",
             Modifier.size(22.dp), tint = onSurfaceVariant)             // 22dp glyph
    }
}
```

Notes:

- **No row-level `alpha(0.65f)` dimming.** Figma 18:2 renders archived rows at full opacity; the only alpha modulation in the row is the `0.75f` on the subtitle text per spec. The pre-#177 row-level dimming (carried from #94's "secondary-tier signal" reuse from #69) is gone.
- **Row itself is not clickable.** Only the trailing `IconButton` is interactive — no `clickable` / `combinedClickable` on the outer `Row`. Long-press affordance is removed entirely.
- **`displayName` is a parameter, not derived inside.** The caller (`LoadedBody`) computes the fallback once and passes the same string into `ArchiveRow`'s `displayName`, the `RestoreRequested(id, displayName)` event payload, and (transitively) the `Restored <name>` snackbar text. Single source of truth for the fallback resolution; the row never re-derives it.
- **Subtitle text is `stringResource(R.string.archived_relative_subtitle, formatRelativeTime(conversation.lastUsedAt))`.** `Conversation.lastUsedAt` is the timestamp source — there's still no `archivedAt: Instant?` field on `Conversation`, and adding one is the 30-day auto-archive worker's job, not this row's.
- **`IconButton.contentDescription` interpolates the row's `displayName`** via `stringResource(R.string.cd_restore_archive, displayName)` — e.g. `"Restore old-project-experiments"` or `"Restore Untitled discussion"`. Visible label and the screen-reader announcement stay in lockstep even for nameless conversations.
- **Restore icon is `Icons.Filled.Refresh`** (the circular-arrow glyph in `material-icons-core`). The spec drafted `Icons.Filled.Restore` with `Replay` / `History` / `Undo` as fallbacks; all four ship only in `material-icons-extended`, which is banned project-wide ([Settings screen](settings-screen.md) rule). `Refresh` is in `core` and reads as "restore" in context. See [`codebase/177.md`](../codebase/177.md) § Lessons learned for the cross-check rule when a spec recommends an icon constant.
- **Two `@Preview`s** (`ArchiveRowLightPreview`, `ArchiveRowDarkPreview`) seeded from a private `previewArchivedConversation()` helper using `Clock.System.now() - 14.days` so the subtitle reads `"Archived 2w ago"`. Both `widthDp = 412`.

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
        effects = vm.effects,                          // since #177
    )
}
```

`Routes.ARCHIVED_DISCUSSIONS = "archived_discussions"`. The NavHost intercepts `BackTapped` before forwarding to the VM (the VM's `Unit` arm is a no-op); `RestoreRequested` and `TabSelected` (since #176) are forwarded as-is. The `effects` flow is passed straight through from the VM to the screen — the destination block doesn't collect it (the screen owns the `SnackbarHostState` and collects internally).

Koin: `viewModel { ArchivedDiscussionsViewModel(get()) }` in `appModule` — constructor signature is unchanged across #94/#176 (the `selectedTab` flow is constructed in the field initializer, not injected).

### Strings

Resource set in `app/src/main/res/values/strings.xml` after #177:

- `archived_title` → `"Archived"` (TopAppBar; since #176 — replaces `archived_discussions_title`)
- `archived_tab_channels` → `"Channels (%1$d)"` (tab label with live count; since #176)
- `archived_tab_discussions` → `"Discussions (%1$d)"` (tab label with live count; since #176)
- `archived_empty_channels` → `"No archived channels"` (per-tab empty; since #176)
- `archived_empty_discussions` → `"No archived discussions"` (per-tab empty; since #176 — replaces the screen-level `archived_discussions_empty`)
- `archived_discussions_settings_row` → `"Archived discussions"` (Settings row headline; unchanged across #94/#176/#177 — Settings copy intentionally kept the pre-#176 wording)
- `archived_discussions_count_supporting` → `"%1$d archived"` (Settings row supporting line, live count; since #164)
- `archived_relative_subtitle` → `"Archived %1$s"` (`ArchiveRow` subtitle text; `%1$s` is the `formatRelativeTime` output — since #177)
- `restored_snackbar` → `"Restored %1$s"` (`Snackbar` copy on successful restore; `%1$s` is the resolved display name — since #177)
- `cd_restore_archive` → `"Restore %1$s"` (`IconButton.contentDescription` on the trailing restore affordance; `%1$s` is the resolved display name — since #177)

`cd_back` reused unchanged. The pre-#176 `archived_discussions_title` and `archived_discussions_empty` were deleted in #176; the pre-#177 `restore_action` ("Restore", sole consumer was the now-deleted `DropdownMenuItem`) was deleted in #177 — grep-confirmed before deletion.

Plurals deferred: `"0 / 1 / 3 archived"` are all grammatical without inflection, the codebase has no plurals precedent yet, and switching to a `<plurals>` resource later is mechanical. YAGNI.

## Configuration / usage

Mounted at `Routes.ARCHIVED_DISCUSSIONS` (`"archived_discussions"`) in [`PyryNavHost`](navigation.md). Sole entry point: the [Settings screen](settings-screen.md) Storage section's "Archived discussions" row. No deep-link, no back-stack policy beyond the default `popBackStack()` on `BackTapped`.

Manual verification path (post-#177):

1. `./gradlew installDebug` → open app → tap settings gear in the channel-list `TopAppBar` → scroll Settings to **Storage** → tap **Archived discussions**.
2. The seeded archived discussion (`seed-discussion-archived`, lastUsedAt `2026-04-15`) renders under the **Discussions** tab (default) as an `ArchiveRow`: 40dp avatar + `titleMedium` headline + `bodySmall` "Archived 1mo ago"-shaped subtitle + trailing 40dp restore `IconButton`. No row-level alpha dimming. Both tab labels show counts (`Channels (0)`, `Discussions (1)`).
3. Tap the **Channels** tab → "No archived channels" centered empty body; tab header stays visible, count badges unchanged.
4. Tap back to **Discussions** → row reappears. Tap the trailing restore icon-button (one tap, no long-press) → row animates out, `Snackbar` appears at the bottom reading `Restored Untitled discussion` (or the configured name if non-null), Discussions tab body shows "No archived discussions".
5. Back-arrow twice → channel list → restored discussion appears under Recent discussions.
6. Toggle dark mode via Settings → Theme; revisit (kill + relaunch to reseed). Both light + dark variants render.

Tab selection survives recomposition / rotation (`MutableStateFlow` in the VM, VM survives configuration change). Leaving the screen lets `WhileSubscribed(5_000L)` keep the VM warm for 5s; popping the back-stack within that grace window restores the previously-selected tab, popping after re-creates the VM with `Discussions` again. Acceptable per the #176 spec — `rememberSaveable` was the alternative and was deliberately not chosen so unit tests can assert tab-selection deterministically.

## Edge cases / limitations

- **"Archive date" trailing slot is `formatRelativeTime(lastUsedAt)`, not a true `archivedAt`.** `Conversation` has no `archivedAt: Instant?` field; `archive(id)` (#93) flips a boolean without stamping a timestamp. The existing trailing slot prints "Apr 15" for the seed — close to the AC's "archive date" but not literally it. The natural owner of `archivedAt` is the 30-day auto-archive worker (it needs the timestamp to decide which records to evict); add the field there, then switch this screen's trailing slot to format it.
- **Restore failure is not user-visible** (post-#177 the snackbar fires on success only). The arm wraps `runCatching { repository.unarchive(id) }.onSuccess { _effects.send(...) }` — any throw is swallowed, no snackbar, no log. The Fake never throws on `unarchive` (no-op on a missing id is fine), so this path is currently dead. Phase 4's Ktor remote will introduce real failure modes; the visible-error / retry surface lands then via a new `ArchivedDiscussionsEffect.RestoreFailed(displayName)` + paired "Couldn't restore" snackbar. The trade-off vs the spec's bare-throw (which would let the exception propagate to `viewModelScope`'s default uncaught handler) is documented in [`codebase/177.md`](../codebase/177.md) § Lessons learned — the `runCatching` swap keeps the snackbar contract intact while preventing an unhandled-exception crash on the Phase-4 failure path, at the cost of not logging the failure today.
- **Settings entry-row count counts only archived discussions, not archived channels.** Since #164 the Settings row reads `"N archived"` where N = `count { !it.isPromoted }` on the same `Archived` upstream — the pre-#176 screen-side filter that #176 walked back. Archived channels are now first-class in this screen but invisible on the Settings entry. A follow-up could promote the Settings projection to `count { archived = true }` for parity; not done here because (a) the Settings copy literal is still "Archived discussions" and (b) the count number's contract with users hasn't changed yet. Documented divergence.
- **`SecondaryTabRow`'s `selectedTabIndex = state.selectedTab.ordinal` mapping is positional.** `ArchiveTab.Channels.ordinal == 0` matches the first `Tab` child position; `ArchiveTab.Discussions.ordinal == 1` matches the second. If the enum grows a new value **in front of** `Channels` (e.g. `enum class ArchiveTab { All, Channels, Discussions }`), the ordinal mapping silently shifts and the wrong tab is rendered as active. Adding values to the end is safe; reordering is the risk. The mitigation in the screen is to read the ordinal once at `selectedTabIndex = state.selectedTab.ordinal` and to spell out the per-`Tab` `selected = (state.selectedTab == <variant>)` check explicitly, so a re-ordering would surface as a "tab indicator under tab X but tab Y's body content" visual mismatch in previews. Not enforced in code; carry as a known risk if the enum ever grows.
- **Tab selection resets to `Discussions` after the `WhileSubscribed(5_000L)` grace expires.** Documented above; tolerable because Phase 0 has no process-death scenario reachable on the fake-data path. If Phase 4's real backend introduces durable session state, revisit whether tab selection should survive longer (probably via `SavedStateHandle`, not `rememberSaveable` — the VM is still the right home).

## Testing

`ArchivedDiscussionsViewModelTest` (unit) at `app/src/test/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModelTest.kt` — 15 `@Test` methods (post-#177; #176 grew it from #94's 9 to 13, #177 added 2 more), mirroring `DiscussionListViewModelTest`'s idiom (`Dispatchers.setMain(UnconfinedTestDispatcher())` + `runTest` + `advanceUntilIdle`; anonymous `ConversationRepository` stub with `TODO("not used")` on unused methods; `RecordingRepo` capturing `unarchiveCalls: List<String>` for the restore-action path; new `throwingUnarchiveRepo` since #177 whose `unarchive` throws `RuntimeException("boom")`):

- `initialState_isLoading` — fresh VM, no collector, `state.value == Loading`.
- `loaded_passesArchivedFilter` — verifies the captured `ConversationFilter` is `Archived`.
- `loaded_partitionsByIsPromoted` — emit `[discussion-A, channel-B, discussion-C]` → `Loaded(channels = [channel-B], discussions = [discussion-A, discussion-C], selectedTab = Discussions)`. Replaces the pre-#176 `loaded_dropsPromotedArchivedFromList` (which was the `!isPromoted` post-filter lock).
- `loaded_defaultSelectedTab_isDiscussions` — first `Loaded` emission has `selectedTab == ArchiveTab.Discussions`. AC §3 contract; fails if anyone flips the default later.
- `tabSelected_channels_updatesStateOnly` — dispatch `TabSelected(Channels)` → same lists, `selectedTab == Channels`. Confirms tab switch does not re-collect or re-partition.
- `tabSelected_discussions_returnsToDefault` — flip to Channels then back to Discussions → `selectedTab == Discussions`.
- `loaded_channels_emptyWhenAllUnpromoted` — emit `[discussion]` → `Loaded.channels.isEmpty()`, `Loaded.discussions == [discussion]`.
- `loaded_discussions_emptyWhenAllPromoted` — emit `[channel]` → `Loaded.discussions.isEmpty()`, `Loaded.channels == [channel]`. Replaces the pre-#176 `empty_whenAllArchivedAreChannels`.
- `loaded_bothEmpty_whenSourceEmitsEmptyList` — emit `[]` → `Loaded(channels = [], discussions = [], selectedTab = Discussions)`. Replaces the pre-#176 `empty_whenSourceEmitsEmptyList`; the new shape asserts the 3-field `Loaded` rather than the deleted top-level `Empty`.
- `restoreRequested_callsUnarchive_withConversationId` — `RecordingRepo` asserts `unarchiveCalls == [id]`. Constructor arg updated in #177 to pass the new `displayName` (`RestoreRequested("disc-7", "Untitled discussion")`); the captured-id assertion is unchanged.
- `restoreRequested_emitsRestoreSucceededEffect_afterUnarchive` (since #177) — `async { vm.effects.first() }` started before the event dispatches so the collector is subscribed when `_effects.send` runs; asserts `effectDeferred.await() == RestoreSucceeded(displayName = "old-project-experiments")`.
- `restoreRequested_doesNotEmitEffect_whenUnarchiveThrows` (since #177) — uses `throwingUnarchiveRepo` (a new private helper whose `unarchive` throws `RuntimeException("boom")`); asserts `withTimeoutOrNull(100.milliseconds) { vm.effects.first() } == null`. This is also the regression guard for the `runCatching` swap — if a future refactor reverts to a bare-throw, the test fails by virtue of `viewModelScope`'s exception handler kicking in.
- `backTapped_doesNotCallUnarchive` — `BackTapped` is a no-op for the repo. (Unchanged from #94.)
- `error_whenSourceFlowThrows` — stream throws → `Error("network down")`. (Unchanged from #94.)
- `error_messageIsNonBlank_whenExceptionMessageIsNull` — null message → non-blank fallback. (Unchanged from #94.)

No instrumented Compose tests. Pre-#177 the screen had none and #177 didn't add any; the `@Preview`s (three for `ArchivedDiscussionsScreen` + two for `ArchiveRow` = five total post-#177; the pre-#177 `ArchivedDiscussionsRowMenuPreview` was deleted with the long-press dropdown) provide the visual coverage. A follow-up belt-and-suspenders Compose test can mirror `DiscussionListScreenTest`'s shape if a regression appears — the `Snackbar` text is one obvious candidate but verifying it requires `composeTestRule.waitUntil { … }` against the live `SnackbarHostState`, which doesn't have prior art in this codebase.

`SettingsScreenTest`'s `setContent` blocks were not touched by #176 — the Settings-side parameters and the new `archivedDiscussionCount` arg are #164 territory, independent of this slice.

## Previews

Five `@Preview`s, all `private`, all `widthDp = 412` (post-#177):

Inside `ArchivedDiscussionsScreen.kt` — three previews, all `darkTheme = false`:

- `ArchivedScreenDiscussionsPreview` — Discussions tab active, one archived channel + one archived discussion seeded so both tab counts are non-zero. Discussions list renders as the body.
- `ArchivedScreenChannelsPreview` — Channels tab active, same seeds, channel list renders as the body.
- `ArchivedScreenDiscussionsEmptyPreview` — Discussions tab active, one archived channel seeded + zero archived discussions, body renders the "No archived discussions" centered empty state with the tab header still visible (AC §5 visual lock).

Inside `ArchiveRow.kt` (since #177) — two previews, light + dark:

- `ArchiveRowLightPreview` — `ArchiveRow` rendered standalone in light theme with a seeded archived `Conversation` 14 days old (subtitle reads `"Archived 2w ago"`).
- `ArchiveRowDarkPreview` — same fixture, `darkTheme = true`, `uiMode = Configuration.UI_MODE_NIGHT_YES`. Verifies the trailing-icon `onSurfaceVariant` tint reads against the dark `surface` background.

The pre-#177 `ArchivedDiscussionsRowMenuPreview` (long-press menu open via `menuInitiallyExpanded = true`) is deleted with the `ArchivedDiscussionRow` it was previewing. Other screen-level dark previews aren't needed — the screen reuses `SecondaryTabRow` (covered elsewhere) and `ArchiveRow`'s dark coverage lives in `ArchiveRow.kt`. A Channels-empty preview was considered and dropped (symmetrical to `ArchivedScreenDiscussionsEmptyPreview` and would add no new visual contract).

## Related

- Ticket notes:
  - [`../codebase/94.md`](../codebase/94.md) — original screen + VM + long-press dropdown
  - [`../codebase/176.md`](../codebase/176.md) — 2-tab segmented header + VM partition + `TabSelected` event
  - [`../codebase/177.md`](../codebase/177.md) — `ArchiveRow` + inline restore + `Snackbar` via `ArchivedDiscussionsEffect`
  - [`../codebase/164.md`](../codebase/164.md) — Settings-side live-count projection over the same `Archived` upstream
- Specs: `docs/specs/architecture/94-archived-discussions-screen.md`, `docs/specs/architecture/176-archive-2-tab-header.md`, `docs/specs/architecture/177-archive-row-inline-restore-snackbar.md`
- Parent: split from #125 (the broader archive overhaul). #176 + #177 together close out the #125 split — there is no further child ticket.
- Data foundations: [`../codebase/93.md`](../codebase/93.md) (`Conversation.archived` + `ConversationFilter.Archived` + seed), [`../codebase/96.md`](../codebase/96.md) (`unarchive(id)` primitive)
- Sibling screens: [Discussion list screen](discussion-list-screen.md) (shape this screen mirrored at the row level pre-#177 — `Scaffold` + back-arrow + `LazyColumn` + long-press menu + `alpha(0.65f)`; #177's `ArchiveRow` walked the per-row alpha + long-press menu back for the archive surface specifically); the segmented-tab pattern is new to the codebase with #176 and a future tabbed screen should follow the `MutableStateFlow<X> + combine` shape established here. Was siblings with `LicenseScreen` (#91) under `ui/settings/` until #163 deleted that screen.
- Hosted ViewModel pattern: similar in shape to [Discussion list view-model](discussion-list-viewmodel.md), now with a UI-state slot (`selectedTab`) `combine`d alongside the data source (#176) and a `Channel<Effect>` for one-shot UI signals (#177) — see [`codebase/177.md`](../codebase/177.md) § Patterns established for the rule on splitting `navigationEvents` from `effects`.
- Reused primitives in `ArchiveRow`: [Conversation avatar](conversation-avatar.md) (40dp leading bubble), `formatRelativeTime` (`internal` in `ui/conversations/components/RelativeTime.kt`).
- Entry point: [Settings screen](settings-screen.md) Storage section row
- Navigation: [Navigation](navigation.md) — route `archived_discussions`
- Figma:
  - Original (no dedicated frame) — pre-#176 visuals derived from Recent Discussions row treatment in #69 with muted-alpha state signal
  - 18:2 — https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=18-2 (locked since #176; `ArchiveRow` anatomy locked since #177)
- Follow-ups: (a) `Conversation.archivedAt: Instant?` stamped by the 30-day auto-archive worker, then this screen formats it instead of `lastUsedAt`; (b) snackbar "Undo" action — out of scope per the #177 issue body; (c) Settings projection promoted to `count { archived = true }` for parity with the new Channels tab; (d) visible-error / retry surface when Phase 4's Ktor remote arrives — `ArchivedDiscussionsEffect.RestoreFailed(displayName)` is the natural shape.
