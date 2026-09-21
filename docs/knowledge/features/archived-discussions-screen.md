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
    data object RestoreFailed : ArchivedDiscussionsEffect      // since #557, payload-free
}

class ArchivedDiscussionsViewModel(
    private val repository: ConversationRepository,
    hostLabel: Flow<String> = flowOf(""),                      // since #715
) : ViewModel() {
    val host: StateFlow<String> = hostLabel.stateIn(viewModelScope, WhileSubscribed(STOP_TIMEOUT_MILLIS), "")
}
```

**Since #715, `repository` is the owning host's own facade, not the compatibility one — every read and every write on this screen goes through it.** [`ThreadDestinationFactory.archive(handle)`](dependency-injection.md#destination-ownership) resolves it once, at construction, via #636's exact-host seam (a `StableConversationRepository` over that host's `currentRepository`), so a compatibility-selection change, a reconnect or an unpair can move neither the rows nor a pending restore — and a host holding the same conversation id under a different host is unreachable by construction rather than by a check. `unarchive` against the real relay was already reachable before this ticket (the `mutationsSupported` gate flipped to `true` in #572); what #715 fixes is *which* host's relay it reaches.

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

**Owning-host header (since #715).** The Scaffold's body `Column` opens with a conditional `Text(hostName.take(MAX_WORKSPACE_LABEL_CHARS), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)`, rendered only when `hostName.isNotBlank()`, sitting **below** the `TopAppBar` and above `SecondaryTabRow` — outside the `when (state)` branch, so it reads the same across `Loading`/`Error`/`Loaded`. This is a plan revision, not the original design: the first pass put the owner on a second `TopAppBar` title line, but M3's small `TopAppBar` is a fixed 64dp container that a two-line title overruns at large font scale, and this would have been the first two-line bar title in the codebase — nothing carried that risk. A `Text` in a `Column` grows with the font scale instead of being clipped, and Figma `18:2`'s single-line "Archived" bar is now matched exactly; the owning host is identified one line lower rather than in the bar itself. `MAX_WORKSPACE_LABEL_CHARS` ([`ui/workspace/WorkspaceDisplayName.kt`](workspace-picker.md)) clamps the name at the render boundary because it can originate in a scanned QR payload or locally-entered host metadata, neither of which `parsePairingPayload` bounds by length. Two of the three `@Preview`s pass a `hostName` — see [Previews](#previews) below.

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

`SettingsScreen` carries an `onOpenArchivedDiscussions: (() -> Unit)?` parameter — **nullable since #715**, the one exception to the "navigation callbacks never carry defaults" rule from #87/#91, because a destination that owns no host has no archive to open. `SettingsRow`'s own `onClick: (() -> Unit)?` already draws its `ListItem` inert (no `clickable`, no ripple) when passed `null`, so the row degrades gracefully rather than offering a tap that could only be rejected; it still draws its trailing `ChevronIcon()` regardless, so the inert state isn't visually distinguished from a live one (flagged as a verifier NIT, not fixed here). The Storage row's headline reads `stringResource(R.string.archived_discussions_settings_row)` ("Archived discussions" — the Settings copy literal kept the pre-#176 wording even after the screen title flipped to "Archived"; the two are deliberately separate string ids so they can diverge without forking a usage site). The row's `supporting = stringResource(R.string.archived_discussions_count_supporting, archivedDiscussionCount)` ("N archived") is driven by [`SettingsViewModel.archivedDiscussionCount`](settings-viewmodel.md) since #164, and since #715 that count is projected from the **same owning host** this row opens — see [Dependency injection § Destination ownership](dependency-injection.md#destination-ownership).

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

Koin: `viewModel { get<ThreadDestinationFactory>().archive(get()) }` in `appModule`, replacing the pre-#715 `viewModel { ArchivedDiscussionsViewModel(get()) }`. `ThreadDestinationFactory.archive(handle: SavedStateHandle)` reads `serverId` from the entry's `SavedStateHandle` and builds `ArchivedDiscussionsViewModel(repository(serverId), hostLabel(serverId))` — `repository(serverId)` is [#636's exact-host seam](dependency-injection.md#exact-host-repository-access) verbatim, and `hostLabel` is a private helper mapping the factory's existing `hosts()` projection to the owner's resolved display name (`displayName` when non-blank, else the server id — the same fallback `SettingsHostRow`/`HostIdentityRow` use), narrowed to a single `String` so no `SettingsHost` or stored pairing record ever reaches this ViewModel. See [Dependency injection § Destination ownership](dependency-injection.md#destination-ownership).

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

## Configuration / usage

Mounted at `Routes.ARCHIVED_DISCUSSIONS` (`"archived_discussions/{serverId}"`, a required path segment since #715) in [`PyryNavHost`](navigation.md), wrapped in `HostDestination` so an unknown or newly-removed owner leaves for the channel list rather than falling through to another host's rows. Entry points: the [Settings screen](settings-screen.md) Storage section's "Archived discussions" row (inherits the owner Settings' own destination already holds), and, since #737, the archive entry on the [channel list](channel-list-screen.md)'s own bar (reads compatibility selection fresh at tap time) — one destination, two doors, each capturing its owner from a different source since #715. No deep-link, no back-stack policy beyond the default `popBackStack()` on `BackTapped`.

Manual verification path (post-#715):

1. `./gradlew installDebug` → open app with at least one paired host → tap the archive entry on the channel list's own bar (or tap the settings entry → scroll Settings to **Storage** → tap **Archived discussions**).
2. The seeded archived discussion (`seed-discussion-archived`, lastUsedAt `2026-04-15`) renders under the **Discussions** tab (default) as an `ArchiveRow`: 40dp avatar + `titleMedium` headline + `bodySmall` "Archived 1mo ago"-shaped subtitle + trailing 40dp restore `IconButton`. No row-level alpha dimming. Both tab labels show counts (`Channels (0)`, `Discussions (1)`). Below the "Archived" app bar, a `labelMedium` line names the owning host.
3. Tap the **Channels** tab → "No archived channels" centered empty body; tab header stays visible, count badges unchanged.
4. Tap back to **Discussions** → row reappears. Tap the trailing restore icon-button (one tap, no long-press) → row animates out, `Snackbar` appears at the bottom reading `Restored Untitled discussion` (or the configured name if non-null), Discussions tab body shows "No archived discussions".
5. Back-arrow twice → channel list → restored discussion appears under Recent discussions.
6. Toggle dark mode via Settings → Theme; revisit (kill + relaunch to reseed). Both light + dark variants render.

Tab selection survives recomposition / rotation (`MutableStateFlow` in the VM, VM survives configuration change). Leaving the screen lets `WhileSubscribed(5_000L)` keep the VM warm for 5s; popping the back-stack within that grace window restores the previously-selected tab, popping after re-creates the VM with `Discussions` again. Acceptable per the #176 spec — `rememberSaveable` was the alternative and was deliberately not chosen so unit tests can assert tab-selection deterministically.

## Edge cases / limitations

- **"Archive date" trailing slot is `formatRelativeTime(lastUsedAt)`, not a true `archivedAt`.** `Conversation` has no `archivedAt: Instant?` field; `archive(id)` (#93) flips a boolean without stamping a timestamp. The existing trailing slot prints "Apr 15" for the seed — close to the AC's "archive date" but not literally it. The natural owner of `archivedAt` is the 30-day auto-archive worker (it needs the timestamp to decide which records to evict); add the field there, then switch this screen's trailing slot to format it.
- **Restore failure surfaces since #557** — a fixed-string `restore_failed` snackbar covers both the server-`error` (`RelayErrorException`) and disconnected (`IllegalStateException`) cases; the server-supplied message is never shown. Resolves the gap this bullet used to describe (pre-#557 silent `runCatching` no-op). See [`codebase/557.md`](../codebase/557.md).
- **Restore against the real relay is reachable — this overview previously said otherwise.** `RemoteConversationRepository.mutationsSupported` was flipped from `false` to `true` in #572, before this ticket; the pre-#572 statement here (that `unarchive` sat behind a disabled family gate) was already stale by the time #715 landed and is corrected as part of #715's documentation handoff. What #715 fixes is *which* host's relay a restore reaches — see [`ArchivedDiscussionsViewModel`](#archiveddiscussionsviewmodel) above. The rung-3 two-host archive/restore e2e is #676, tracked separately from this correction.
- **Settings entry-row count counts only archived discussions, not archived channels.** Since #164 the Settings row reads `"N archived"` where N = `count { !it.isPromoted }` on the same `Archived` upstream — the pre-#176 screen-side filter that #176 walked back. Archived channels are now first-class in this screen but invisible on the Settings entry. A follow-up could promote the Settings projection to `count { archived = true }` for parity; not done here because (a) the Settings copy literal is still "Archived discussions" and (b) the count number's contract with users hasn't changed yet. Documented divergence — preserved by #715, which moved *which host's* repository the count reads from without touching the projection itself.
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
- `restoreRequested_whenDisconnected_surfacesRestoreFailed` (since #557, replaces the pre-#557 `restoreRequested_doesNotEmitEffect_whenUnarchiveThrows`) — `throwingUnarchiveRepo` (now parameterized by `error: Throwable`) throws `IllegalStateException`; asserts `vm.effects.first() == RestoreFailed` and a captured default-uncaught-exception-handler stayed empty (proof the typed catch ran, not that the throw escaped). AC's required disconnected-ISE case.
- `restoreRequested_whenServerError_surfacesRestoreFailed_withoutLeakingMessage` (since #557) — same shape, `throwingUnarchiveRepo` throws `RelayErrorException(message = "leak me")`; asserts `RestoreFailed` (payload-free, so the message is structurally unable to surface) and an empty uncaught-handler capture.
- `restoreRequested_scopeCancellationMidRestore_isInert_notMisSurfaced` (since #557) — `GatingUnarchiveRepo` suspends `unarchive` on a `CompletableDeferred` until `ViewModelStore().clear()` cancels `viewModelScope`; asserts no effect was emitted and the uncaught-handler capture stayed empty, proving the `CancellationException`-first rethrow keeps teardown inert (AC #4).
- `backTapped_doesNotCallUnarchive` — `BackTapped` is a no-op for the repo. (Unchanged from #94.)
- `error_whenSourceFlowThrows` — stream throws → `Error("network down")`. (Unchanged from #94.)
- `error_messageIsNonBlank_whenExceptionMessageIsNull` — null message → non-blank fallback. (Unchanged from #94.)
- `host_surfacesOwnerLabel_andFollowsRenames` (since #715) — a `MutableStateFlow<String>` label fed as `hostLabel`; asserts `vm.host.value` tracks a later rename emission on the same flow rather than requiring re-navigation.
- `host_isBlank_whenOwnerIsNotSaved` (since #715) — `hostLabel = flowOf("")` → `vm.host.value == ""`. The layer below `HostDestination`'s route guard: proves the label itself cannot borrow another host's identity.
- `restoreRequested_callsOnlyTheConstructedRepository_whenIdsCollide` (since #715) — two independent `RecordingRepo`s holding the *same* conversation id; only the one the VM was constructed with (`owner`) sees `unarchiveCalls`, the other (`other`) sees none. The colliding-id guarantee (AC #2) asserted at unit scope.

No instrumented Compose tests on `ArchivedDiscussionsScreen` itself. Pre-#177 the screen had none and #177 didn't add any; the `@Preview`s (three for `ArchivedDiscussionsScreen` + two for `ArchiveRow` = five total post-#177; the pre-#177 `ArchivedDiscussionsRowMenuPreview` was deleted with the long-press dropdown) provide the visual coverage. A follow-up belt-and-suspenders Compose test can mirror `DiscussionListScreenTest`'s shape if a regression appears — the `Snackbar` text is one obvious candidate but verifying it requires `composeTestRule.waitUntil { … }` against the live `SnackbarHostState`, which doesn't have prior art in this codebase. Route-level and cross-host behavior is now covered on-device instead — see below.

`SettingsScreenTest`'s `setContent` blocks were not touched by #176 — the Settings-side parameters and the new `archivedDiscussionCount` arg are #164 territory, independent of this slice. Since #715, `setSettings()`'s `onOpenArchivedDiscussions` parameter defaults to a non-null `{}` (preserving every existing call site) with one new method, `archiveRow_isNotClickable_whenNoOwnerToOpenItFor`, passing `null` and asserting `assertHasNoClickAction()` on the "Archived discussions" row — the layer below the route guard that proves a blank owner never gets a tap to reject.

### Device test — `ArchiveNavigationTest` (since #715)

`app/src/androidTest/.../ui/settings/ArchiveNavigationTest.kt` copies `SettingsNavigationTest`'s production-route harness (`PyryNavHost` + `Routes` + Koin bindings + two Noise peers, no Activity startup gate) and, like [`LiteralScreenNavigationTest`](literal-screen-surface.md), gives both hosts the **same** archived conversation id (`SHARED_ID`) — proving the colliding-id guarantee means giving two hosts the same id, not merely two different ones:

- `archiveKeepsItsCapturedOwnerAcrossSelectionChangeAndRestoration` — opens Archive through the Settings row under host Alpha (id carrying reserved characters `"A /?#%"`, proving the path-segment encoding round-trips), asserts the route argument and rendered row are Alpha's; a compatibility-selection change to Bravo, a `StateRestorationTester` saved-instance-state restore, and a Back-then-reopen-under-Bravo all leave (or correctly change) the captured owner.
- `restoreReachesOnlyTheOwnerAndLeavesTheOtherHostsMatchingIdArchived` — restores Alpha's row while selection points at Bravo; asserts Bravo's peer received **no `unarchive_conversation` frame at all**, Alpha's did, the snackbar reads the success copy, and the row leaves the list only after the peer's `conversation_updated` reply (`NavigationPeer.unarchived(false)` since #715 — the frame is request/reply, so a peer that never answers proves the row stays put).
- `removingTheOwnerLeavesTheDestinationRatherThanShowingAnotherHost` — removes Alpha's paired-server record while its Archive is open; asserts the destination departs for `Routes.CHANNEL_LIST` rather than rendering Bravo's rows. Asserted on the *departure*, not on the other host's absence — a host-bound facade under an unknown owner emits `emptyList()`, which renders as a plausible "no archived discussions," so the weaker assertion would pass even with the route guard silently failing to fire.
- `theChannelListsArchiveEntryOpensTheSelectedHostsArchive` — the channel list's own door (not Settings'); opens Alpha's archive via the list's archive icon, then, after popping back and changing selection to Bravo, opens Bravo's — proving this door re-reads selection on every tap rather than inheriting a captured owner the way Settings' row does. Added in rework after the verifier's MUST FIX (the plan's reading list had missed this second call site entirely — see [Navigation § Testing](navigation.md#testing)).

`NavigationPeer` gained an optional `archivedId` constructor parameter and an `unarchive_conversation` handler (opt-in, so `LiteralScreenNavigationTest` and `SettingsNavigationTest` see an unchanged peer).

## Previews

Five `@Preview`s, all `private`, all `widthDp = 412` (post-#177):

Inside `ArchivedDiscussionsScreen.kt` — three previews, all `darkTheme = false`:

- `ArchivedScreenDiscussionsPreview` — Discussions tab active, one archived channel + one archived discussion seeded so both tab counts are non-zero. Discussions list renders as the body. Since #715, passes `hostName = "studio-mini"` — an ordinary short name that renders whole, the common case for the new header line.
- `ArchivedScreenChannelsPreview` — Channels tab active, same seeds, channel list renders as the body. Since #715, passes a deliberately long `hostName` (well past `MAX_WORKSPACE_LABEL_CHARS`) so the clamp and the ellipsis overflow both render in this preview — added in rework after the verifier flagged that no preview exercised the one new visual element in the PR.
- `ArchivedScreenDiscussionsEmptyPreview` — Discussions tab active, one archived channel seeded + zero archived discussions, body renders the "No archived discussions" centered empty state with the tab header still visible (AC §5 visual lock). Still defaults `hostName = ""` — the empty-state visual contract doesn't depend on the header line.

Inside `ArchiveRow.kt` (since #177) — two previews, light + dark:

- `ArchiveRowLightPreview` — `ArchiveRow` rendered standalone in light theme with a seeded archived `Conversation` 14 days old (subtitle reads `"Archived 2w ago"`).
- `ArchiveRowDarkPreview` — same fixture, `darkTheme = true`, `uiMode = Configuration.UI_MODE_NIGHT_YES`. Verifies the trailing-icon `onSurfaceVariant` tint reads against the dark `surface` background.

The pre-#177 `ArchivedDiscussionsRowMenuPreview` (long-press menu open via `menuInitiallyExpanded = true`) is deleted with the `ArchivedDiscussionRow` it was previewing. Other screen-level dark previews aren't needed — the screen reuses `SecondaryTabRow` (covered elsewhere) and `ArchiveRow`'s dark coverage lives in `ArchiveRow.kt`. A Channels-empty preview was considered and dropped (symmetrical to `ArchivedScreenDiscussionsEmptyPreview` and would add no new visual contract).

## Related

- Ticket notes:
  - [`../codebase/94.md`](../codebase/94.md) — original screen + VM + long-press dropdown
  - [`../codebase/176.md`](../codebase/176.md) — 2-tab segmented header + VM partition + `TabSelected` event
  - [`../codebase/177.md`](../codebase/177.md) — `ArchiveRow` + inline restore + `Snackbar` via `ArchivedDiscussionsEffect`
  - [`../codebase/557.md`](../codebase/557.md) — `RestoreFailed` effect + try/catch rewrite closing the restore-failure gap
  - [`../codebase/164.md`](../codebase/164.md) — Settings-side live-count projection over the same `Archived` upstream
  - #715 has no `../codebase/715.md` — the per-ticket archive was frozen 2026-09-05, before this ticket; its design, security review and revisions live in `docs/specs/architecture/715-archive-host-owner.md`, and its lessons live in this document, [Navigation](navigation.md) and [Dependency injection](dependency-injection.md) instead.
- Specs: `docs/specs/architecture/94-archived-discussions-screen.md`, `docs/specs/architecture/176-archive-2-tab-header.md`, `docs/specs/architecture/177-archive-row-inline-restore-snackbar.md`, `docs/specs/architecture/715-archive-host-owner.md` (host-bound repository + route ownership + host-identified header)
- Parent: split from #125 (the broader archive overhaul). #176 + #177 together closed out the #125 split; #715 (host ownership, split from #637 via #749) is a later, independent slice.
- Data foundations: [`../codebase/93.md`](../codebase/93.md) (`Conversation.archived` + `ConversationFilter.Archived` + seed), [`../codebase/96.md`](../codebase/96.md) (`unarchive(id)` primitive)
- Sibling screens: [Discussion list screen](discussion-list-screen.md) (shape this screen mirrored at the row level pre-#177 — `Scaffold` + back-arrow + `LazyColumn` + long-press menu + `alpha(0.65f)`; #177's `ArchiveRow` walked the per-row alpha + long-press menu back for the archive surface specifically); the segmented-tab pattern is new to the codebase with #176 and a future tabbed screen should follow the `MutableStateFlow<X> + combine` shape established here. Was siblings with `LicenseScreen` (#91) under `ui/settings/` until #163 deleted that screen.
- Hosted ViewModel pattern: similar in shape to [Discussion list view-model](discussion-list-viewmodel.md), now with a UI-state slot (`selectedTab`) `combine`d alongside the data source (#176) and a `Channel<Effect>` for one-shot UI signals (#177) — see [`codebase/177.md`](../codebase/177.md) § Patterns established for the rule on splitting `navigationEvents` from `effects`.
- Reused primitives in `ArchiveRow`: [Conversation avatar](conversation-avatar.md) (40dp leading bubble), `formatRelativeTime` (`internal` in `ui/conversations/components/RelativeTime.kt`).
- Entry point: [Settings screen](settings-screen.md) Storage section row
- Navigation: [Navigation](navigation.md) — route `archived_discussions/{serverId}` (since #715; a bare `archived_discussions` before it)
- Figma:
  - Original (no dedicated frame) — pre-#176 visuals derived from Recent Discussions row treatment in #69 with muted-alpha state signal
  - 18:2 — https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=18-2 (locked since #176; `ArchiveRow` anatomy locked since #177; the #715 owner line is this ticket's own AC-driven adaptation, drawn below the bar rather than in the frame itself)
- Follow-ups: (a) `Conversation.archivedAt: Instant?` stamped by the 30-day auto-archive worker, then this screen formats it instead of `lastUsedAt`; (b) snackbar "Undo" action — out of scope per the #177 issue body; (c) Settings projection promoted to `count { archived = true }` for parity with the new Channels tab; (d) ~~visible-error / retry surface when Phase 4's Ktor remote arrives~~ — shipped #557 as the payload-free `RestoreFailed`; (e) ~~flip the `mutationsSupported` gate for `unarchive` so restore is reachable against the real relay~~ — shipped #572, before this ticket; (f) the rung-3 two-host archive/restore e2e remains #676.
