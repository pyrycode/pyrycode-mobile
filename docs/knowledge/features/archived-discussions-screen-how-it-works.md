# Archived Discussions screen — how it works

Split out of [Archived Discussions screen](archived-discussions-screen.md) on 2026-09-22 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Archived Discussions screen](archived-discussions-screen.md); see that document for the rest.

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
    data object RestoreFailed : ArchivedDiscussionsEffect      // since #557, payload-free
}

class ArchivedDiscussionsViewModel(
    private val repository: ConversationRepository,
    hostLabel: Flow<String> = flowOf(""),                      // since #715
) : ViewModel() {
    val host: StateFlow<String> = hostLabel.stateIn(viewModelScope, WhileSubscribed(STOP_TIMEOUT_MILLIS), "")
}
```

**Since #715, `repository` is the owning host's own facade, not the compatibility one — every read and every write on this screen goes through it.** [`ThreadDestinationFactory.archive(handle)`](dependency-injection-host-conversation-source.md#destination-ownership) resolves it once, at construction, via #636's exact-host seam (a `StableConversationRepository` over that host's `currentRepository`), so a compatibility-selection change, a reconnect or an unpair can move neither the rows nor a pending restore — and a host holding the same conversation id under a different host is unreachable by construction rather than by a check. `unarchive` against the real relay was already reachable before this ticket (the `mutationsSupported` gate flipped to `true` in #572); what #715 fixes is *which* host's relay it reaches.

`hostLabel` (second constructor parameter, defaulted to `flowOf("")` so the pre-#715 unit-test construction sites stay call-compatible — Koin always passes it explicitly) is that same host's resolved display name, or blank when it names none. `host` lifts it with its own `stateIn(WhileSubscribed(STOP_TIMEOUT_MILLIS), "")` because the header it feeds is drawn outside the `UiState` branch (see [`ArchivedDiscussionsScreen`](#archiveddiscussionsscreen-stateless) below) and has to stay stable across `Loading`, `Error` and `Loaded` alike — a field on `Loaded` couldn't do that.

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

- `RestoreRequested(id, displayName)` → `viewModelScope.launch { try { repository.unarchive(id); _effects.send(RestoreSucceeded(displayName)) } catch (e: CancellationException) { throw e } catch (e: RelayErrorException) { _effects.send(RestoreFailed) } catch (e: IllegalStateException) { _effects.send(RestoreFailed) } }` since #557 (replaces the pre-#557 `runCatching { … }.onSuccess { … }` silent-no-op). Success stays list-driven — the repository's confirmed upsert re-emits `observeConversations` without the conversation and the row leaves the `Archived` projection naturally; the success branch is unchanged from #177. On failure a server `error` reply (`RelayErrorException`, reachable because `unarchive` is request/reply — [#549](../codebase/549.md)) or a disconnected repository (`IllegalStateException`) both surface the payload-free `RestoreFailed`, rendered as a fixed-string snackbar — the server-supplied exception message is never read. `CancellationException` is rethrown **before** the typed catches (it extends `IllegalStateException` on the JVM — [[catch-illegalstate-swallows-cancellation]]), so `viewModelScope` teardown mid-restore stays inert. See [`codebase/557.md`](../codebase/557.md).
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
    hostName: String = "",                                    // since #715
)
```

`Scaffold` with an M3 `TopAppBar` — title `stringResource(R.string.archived_title)` ("Archived" since #176; replaces the pre-#176 "Archived discussions"; still a single line, matching Figma `18:2` exactly — see the header note below), `navigationIcon` the canonical back-arrow `IconButton` (`Icons.AutoMirrored.Filled.ArrowBack` + reused `R.string.cd_back`) that dispatches `BackTapped`, and (since #177) `snackbarHost = { SnackbarHost(snackbarHostState) }` — first `SnackbarHost` in the codebase. Above the `Scaffold`: `val snackbarHostState = remember { SnackbarHostState() }`, `val resources = LocalResources.current`, and a `LaunchedEffect(effects, snackbarHostState) { effects.collect { effect -> when (effect) { is RestoreSucceeded -> snackbarHostState.showSnackbar(resources.getString(R.string.restored_snackbar, effect.displayName)) } } }`. The default `effects = emptyFlow()` keeps the three `@Preview`s (which don't care about effects) call-site-compatible.

**Owning-host header (since #715).** The Scaffold's body `Column` opens with a conditional `Text(hostName.take(MAX_WORKSPACE_LABEL_CHARS), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)`, rendered only when `hostName.isNotBlank()`, sitting **below** the `TopAppBar` and above `SecondaryTabRow` — outside the `when (state)` branch, so it reads the same across `Loading`/`Error`/`Loaded`. This is a plan revision, not the original design: the first pass put the owner on a second `TopAppBar` title line, but M3's small `TopAppBar` is a fixed 64dp container that a two-line title overruns at large font scale, and this would have been the first two-line bar title in the codebase — nothing carried that risk. A `Text` in a `Column` grows with the font scale instead of being clipped, and Figma `18:2`'s single-line "Archived" bar is now matched exactly; the owning host is identified one line lower rather than in the bar itself. `MAX_WORKSPACE_LABEL_CHARS` ([`ui/workspace/WorkspaceDisplayName.kt`](workspace-picker.md)) clamps the name at the render boundary because it can originate in a scanned QR payload or locally-entered host metadata, neither of which `parsePairingPayload` bounds by length. Two of the three `@Preview`s pass a `hostName` — see [Previews](archived-discussions-screen.md#previews) below.

Body branches on the `UiState`:

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

`SettingsScreen` carries an `onOpenArchivedDiscussions: (() -> Unit)?` parameter — **nullable since #715**, the one exception to the "navigation callbacks never carry defaults" rule from #87/#91, because a destination that owns no host has no archive to open. `SettingsRow`'s own `onClick: (() -> Unit)?` already draws its `ListItem` inert (no `clickable`, no ripple) when passed `null`, so the row degrades gracefully rather than offering a tap that could only be rejected; it still draws its trailing `ChevronIcon()` regardless, so the inert state isn't visually distinguished from a live one (flagged as a verifier NIT, not fixed here). The Storage row's headline reads `stringResource(R.string.archived_discussions_settings_row)` ("Archived discussions" — the Settings copy literal kept the pre-#176 wording even after the screen title flipped to "Archived"; the two are deliberately separate string ids so they can diverge without forking a usage site). The row's `supporting = stringResource(R.string.archived_discussions_count_supporting, archivedDiscussionCount)` ("N archived") is driven by [`SettingsViewModel.archivedDiscussionCount`](settings-viewmodel.md) since #164, and since #715 that count is projected from the **same owning host** this row opens — see [Dependency injection § Destination ownership](dependency-injection-host-conversation-source.md#destination-ownership).

**Two doors, one destination, both owner-bound since #715.** Settings' row is one; the [channel list](channel-list-screen.md)'s own archive entry (`ChannelListEvent.ArchiveTapped`, since #737) is the other. The two capture their owner from deliberately different sources: Settings' row inherits the owner its own destination already holds (read back from its route argument), while the channel list's entry reads compatibility selection fresh at tap time, the same way the settings gear beside it does. Neither can pass a blank id — Settings draws its row inert as above, and with no host selected the list's tap does nothing.

`MainActivity.PyryNavHost`:

```kotlin
composable(
    route = Routes.SETTINGS,
    arguments = Routes.settingsArguments(),
) { backStackEntry ->
    val settingsOwner = Routes.settingsOwner(backStackEntry.arguments)
    val vm = koinViewModel<SettingsViewModel>()
    val archivedDiscussionCount by vm.archivedDiscussionCount.collectAsStateWithLifecycle()
    // ...
    SettingsScreen(
        // ...
        onOpenArchivedDiscussions =
            settingsOwner.takeIf { it.isNotEmpty() }?.let { owner ->
                { navController.navigate(Routes.archive(owner)) }
            },
        onOpenAbout = { navController.navigate(Routes.ABOUT) },
    )
}
// Wrapped in HostDestination, unlike Settings and like thread/literal: an unknown or newly-unpaired
// owner bounces to the channel list rather than falling through to another host's archive.
composable(
    route = Routes.ARCHIVED_DISCUSSIONS,
    arguments = Routes.archiveArguments(),
) { backStackEntry ->
    HostDestination(Routes.archiveOwner(backStackEntry.arguments), destinations, navController) {
        val vm = koinViewModel<ArchivedDiscussionsViewModel>()
        val state by vm.state.collectAsStateWithLifecycle()
        val hostName by vm.host.collectAsStateWithLifecycle()
        ArchivedDiscussionsScreen(
            state = state,
            onEvent = { event ->
                when (event) {
                    ArchivedDiscussionsEvent.BackTapped -> navController.popBackStack()
                    is ArchivedDiscussionsEvent.RestoreRequested -> vm.onEvent(event)
                    is ArchivedDiscussionsEvent.TabSelected -> vm.onEvent(event)
                }
            },
            effects = vm.effects,
            hostName = hostName,
        )
    }
}
```

The channel list's own door, in the `PyryNavHost` destination block that handles `ChannelListEvent`:

```kotlin
ChannelListEvent.ArchiveTapped ->
    destinations.selectedServerId()?.let {
        navController.navigate(Routes.archive(it))
    }
```

`Routes.ARCHIVED_DISCUSSIONS = "archived_discussions/{serverId}"` — a **required path segment**, unlike `Routes.SETTINGS`'s optional query argument: Settings has to stay open for an unpaired phone, Archive has no such case, and a route that cannot express "no owner" is the cheapest way to keep one from being invented. Three helpers mirror the existing `thread`/`literal`/`settings` trio: `Routes.archive(serverId: String)` (`Uri.encode`d into the path segment, same per-component encoding discipline), `Routes.archiveArguments()` (one `NavType.StringType` argument named `serverId`), and `Routes.archiveOwner(arguments: Bundle?)`. Because the route changed from a bare constant into a route *pattern*, **every** caller had to change — a rework caught one that the original plan's reading list missed: the channel list's own door still called `navController.navigate(Routes.ARCHIVED_DISCUSSIONS)`, which matched the pattern and bound the literal text `{serverId}` as the owner; `HostDestination` rejected it as unknown and bounced the tap straight back to the list. The two callers above are current. The NavHost intercepts `BackTapped` before forwarding to the VM (the VM's `Unit` arm is a no-op); `RestoreRequested` and `TabSelected` (since #176) are forwarded as-is. The `effects` flow is passed straight through from the VM to the screen — the destination block doesn't collect it (the screen owns the `SnackbarHostState` and collects internally).

Koin: `viewModel { get<ThreadDestinationFactory>().archive(get()) }` in `appModule`, replacing the pre-#715 `viewModel { ArchivedDiscussionsViewModel(get()) }`. `ThreadDestinationFactory.archive(handle: SavedStateHandle)` reads `serverId` from the entry's `SavedStateHandle` and builds `ArchivedDiscussionsViewModel(repository(serverId), hostLabel(serverId))` — `repository(serverId)` is [#636's exact-host seam](dependency-injection-host-conversation-source.md#exact-host-repository-access) verbatim, and `hostLabel` is a private helper mapping the factory's existing `hosts()` projection to the owner's resolved display name (`displayName` when non-blank, else the server id — the same fallback `SettingsHostRow`/`HostIdentityRow` use), narrowed to a single `String` so no `SettingsHost` or stored pairing record ever reaches this ViewModel. See [Dependency injection § Destination ownership](dependency-injection-host-conversation-source.md#destination-ownership).

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
- `restore_failed` → `"Couldn't restore this conversation. Try again."` (`Snackbar` copy on restore failure, fixed local text covering both the server-error and disconnected cases — since #557)
- `cd_restore_archive` → `"Restore %1$s"` (`IconButton.contentDescription` on the trailing restore affordance; `%1$s` is the resolved display name — since #177)

`cd_back` reused unchanged. The pre-#176 `archived_discussions_title` and `archived_discussions_empty` were deleted in #176; the pre-#177 `restore_action` ("Restore", sole consumer was the now-deleted `DropdownMenuItem`) was deleted in #177 — grep-confirmed before deletion.

Plurals deferred: `"0 / 1 / 3 archived"` are all grammatical without inflection, the codebase has no plurals precedent yet, and switching to a `<plurals>` resource later is mechanical. YAGNI.
