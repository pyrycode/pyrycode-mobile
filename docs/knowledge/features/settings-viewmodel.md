# SettingsViewModel

Thin ViewModel for the Settings screen. Exposes persisted preferences (`themeMode`, `useWallpaperColors`, `defaultModel`, `defaultEffort`, `defaultYolo`, `pushNotifications`, `defaultWorkspace`) as hot `StateFlow`s and matching fire-and-forget setter callbacks, plus (since #164) a derived `archivedDiscussionCount` projection over `ConversationRepository`, plus (since #235) a transient `workspacePickerVisible` flag + open/pick/dismiss triple driving the reused [`WorkspacePicker`](workspace-picker.md) host, plus (since #749) a `host: StateFlow<SettingsHostState>` projection that resolves the destination's own captured owner against every saved host. Introduced in #87 (Theme row); extended in #89 (Use-wallpaper-colors switch), #164 (archived-discussion count for the Storage row), #232 (Default-model picker), #233 (Default-effort picker), #234 (Default-YOLO switch), #235 (Default-workspace picker host), #268 (Push-notifications switch — the first pair outside the "Defaults for new conversations" group, and the first preference here to default `true`), #398 (a forwarded `connectionStatus` flow for the Connection-section status line — the **first** signal exposed here that is *not* a DataStore preference, sourced from the [`RelayRepositoryCoordinator`](relay-repository-coordinator.md) and forwarded verbatim), and #749 (`connectionStatus` **removed** — it followed compatibility selection, which is the defect #749 fixes — and replaced by the host-owned `host` projection below).

App-wide settings (the seven preference pairs, `archivedDiscussionCount`, the workspace-picker triple) stay exactly as they were; #749 touches only the Connection-section identity/status signal. `archivedDiscussionCount` and the default-workspace picker remain compatibility-bound pending #715 and #714 respectively — #749 does not rebind them.

## What it does

- Reads `AppPreferences.themeMode` and exposes it as `themeMode: StateFlow<ThemeMode>`.
- Forwards `fun onSelectTheme(mode: ThemeMode)` into `AppPreferences.setThemeMode(mode)` via `viewModelScope.launch { … }`.
- Reads `AppPreferences.useWallpaperColors` and exposes it as `useWallpaperColors: StateFlow<Boolean>` (#89).
- Forwards `fun onToggleUseWallpaperColors(enabled: Boolean)` into `AppPreferences.setUseWallpaperColors(enabled)` via `viewModelScope.launch { … }` (#89).
- Reads `AppPreferences.defaultModel` and exposes it as `defaultModel: StateFlow<Model>` (#232). `initialValue = Model.OPUS_4_7` matches the upstream's fallback when no value is persisted (#231).
- Forwards `fun onSelectDefaultModel(model: Model)` into `AppPreferences.setDefaultModel(model)` via `viewModelScope.launch { … }` (#232).
- Reads `AppPreferences.defaultEffort` and exposes it as `defaultEffort: StateFlow<Effort>` (#233). `initialValue = Effort.HIGH` matches the upstream's fallback when no value is persisted (#231).
- Forwards `fun onSelectDefaultEffort(effort: Effort)` into `AppPreferences.setDefaultEffort(effort)` via `viewModelScope.launch { … }` (#233).
- Reads `AppPreferences.defaultYolo` and exposes it as `defaultYolo: StateFlow<Boolean>` (#234). `initialValue = false` matches the upstream's default when no value is persisted (#231). Same boolean-pair shape as `useWallpaperColors`, not the enum-pair shape of `defaultModel` / `defaultEffort`.
- Forwards `fun onToggleDefaultYolo(enabled: Boolean)` into `AppPreferences.setDefaultYolo(enabled)` via `viewModelScope.launch { … }` (#234). This is `defaultYolo`'s **first writer** — until #234 the key was write-dead (#229 reads it only to ignore it; see § Edge cases).
- Reads `AppPreferences.notificationsEnabled` and exposes it as `pushNotifications: StateFlow<Boolean>` (#268). **`initialValue = true`** — the load-bearing exception: it matches `AppPreferences.notificationsEnabled`'s `?: true` default (Figma `17:2` renders the toggle ON), so the UI shows ON during the brief window before the first DataStore emission. An `initialValue = false` here would flash OFF→ON on cold open. Same boolean-pair shape as `useWallpaperColors` / `defaultYolo`, **but inverted default** — copied from the `defaultYolo` block with the `false` deliberately *not* carried over.
- Forwards `fun onTogglePushNotifications(enabled: Boolean)` into `AppPreferences.setNotificationsEnabled(enabled)` via `viewModelScope.launch { … }` (#268). Write-live but read-dead — nothing consumes `notificationsEnabled` today; notification *delivery* is Phase 4 (see § Edge cases).
- Reads `AppPreferences.defaultWorkspace` and exposes it as `defaultWorkspace: StateFlow<String>` (#235). `initialValue = DEFAULT_SCRATCH_CWD` is the sentinel, so the row shows "scratch" before the cold flow's first emission. This is `defaultWorkspace`'s **first writer** (the first *reader* was #240's FAB short-press cwd); with both ends wired the value round-trips end-to-end — see [App preferences](app-preferences.md).
- Exposes `workspacePickerVisible: StateFlow<Boolean>` (#235), backed by a `private val pendingWorkspacePicker = MutableStateFlow(false)` exposed via `asStateFlow()` — a hot in-memory transient UI flag, **not** a `stateIn`-projected preference. Drives the reused [`WorkspacePicker`](workspace-picker.md) host's `visible` prop. Three handlers: `fun onDefaultWorkspaceTapped()` (flag → `true`, opens the picker), `fun onSelectDefaultWorkspace(path: String)` (flag → `false` then `viewModelScope.launch { appPreferences.setDefaultWorkspace(path) }` — the single funnel for both AC2 recent-pick and AC3 create-new, since the host routes both through one `onPicked`), and `fun onWorkspacePickerDismissed()` (flag → `false`, **no persist**). This is the **picker-*host* shape copied from [`ThreadViewModel`](thread-screen.md)** — distinct from the picker-*dialog* rows (theme/model/effort), whose `show…Dialog` visibility lives in screen-local `remember` state, not the VM. The asymmetry is intentional: AC #6 mandates the VM expose the flag, and the `WorkspacePicker` host is designed to be driven by a hoisted `Boolean` (its `if (!visible) return` gate is the dismiss mechanism).
- Reads `ConversationRepository.observeConversations(ConversationFilter.Archived)`, maps it to `count { !it.isPromoted }`, and exposes the result as `archivedDiscussionCount: StateFlow<Int>` (#164). Mirrors `ArchivedDiscussionsViewModel`'s filter exactly so the Settings Storage row's "N archived" supporting text and the destination [Archived Discussions screen](archived-discussions-screen.md)'s list never disagree.
- **(#398, removed #749)** Used to re-expose the coordinator's `connectionStatus: StateFlow<ConnectionStatus>` verbatim as a constructor `val`. Removed because it followed **compatibility selection**, not the destination's own owner: with two hosts paired, opening Settings for one and then changing selection silently re-pointed what the screen described. See `host` below, its replacement.
- Resolves `ownerServerId: String` (the server id `Routes.settingsOwner` read out of the destination's `SavedStateHandle`, blank when the destination owns no host) against `hosts: Flow<List<SettingsHost>>` (every saved host's identity + live status, built by [`ThreadDestinationFactory.settings`](dependency-injection.md#destination-ownership)) and exposes the result as `host: StateFlow<SettingsHostState>` (#749) — the signal [`HostIdentityRow`](settings-screen.md) and its two non-owned rows render. Both `ownerServerId` and `hosts` are constructor parameters, resolved by **exact case-sensitive id**, the same equality `PairedServerCollectionStore.loadById` and the registry's entry map use. `SettingsHost` is a small `ui.settings`-local transport shape (`serverId`, `displayName: String?`, `relayUrl`, `status: StateFlow<ConnectionStatus>`) — four scalars and a status flow, **never** a `PairedServerEntry` or `PairedServer`: those carry the pairing token and the server static key, and unlike `PairedServer` this class has no redacting `toString` to stop a crash trace from rendering them. `SettingsHostState` is a sealed interface with four cases: `Resolving` (the `stateIn` initial value, before the first `hosts` emission — renders nothing so the join's latency can't flash wrong copy), `Unpaired` (blank owner — resolved synchronously, without ever consulting `hosts`), `Unknown` (non-blank owner absent from the emitted list, including after removal), and `Owned(serverId, displayName, relayUrl, status)` — whose `val name: String` resolves `displayName?.takeIf { it.isNotBlank() } ?: serverId` once, here, so every caller of the fallback agrees.

Seven parallel `(StateFlow<X>, fun onToggleX/onSelectX(...))` preference pairs, one read-only projection, **and** one picker-host triple (the `workspacePickerVisible` flag + open/pick/dismiss handlers from #235 — not a persisted-preference pair). No sealed `SettingsState` / `SettingsEvent` envelope — the threshold-crossing decision was deferred at #233 (with #232 in flight), passed unevaluated at #234 (scoped as XS plumbing), passed unevaluated again at #235 (the **last** remaining defaults slice, scoped as S plumbing), and passed unevaluated a fourth time at #268 — where `pushNotifications` became the **seventh** pair and the *first one outside the "Defaults for new conversations" group*, copied mechanically from `defaultYolo` into the Notifications section without the lift ever being weighed. There are no more rows to act as a "natural trigger"; the shape has settled by default and any future lift is its own dedicated refactor ticket (see § Edge cases below).

## How it works

```kotlin
class SettingsViewModel(
    private val appPreferences: AppPreferences,
    conversationRepository: ConversationRepository,
    ownerServerId: String,               // #749 — from Routes.settingsOwner(handle); blank = no host
    hosts: Flow<List<SettingsHost>>,     // #749 — every saved host's identity + live status
) : ViewModel() {
    val host: StateFlow<SettingsHostState> =
        if (ownerServerId.isBlank()) {
            MutableStateFlow<SettingsHostState>(SettingsHostState.Unpaired)
        } else {
            hosts
                .flatMapLatest { saved ->
                    val owner = saved.firstOrNull { it.serverId == ownerServerId }
                    owner?.status?.map { SettingsHostState.Owned(owner.serverId, owner.displayName, owner.relayUrl, it) }
                        ?: flowOf(SettingsHostState.Unknown)
                }
                .catch { emit(SettingsHostState.Unknown) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = SettingsHostState.Resolving,
                )
        }

    val themeMode: StateFlow<ThemeMode> =
        appPreferences.themeMode.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ThemeMode.SYSTEM,
        )

    val useWallpaperColors: StateFlow<Boolean> =
        appPreferences.useWallpaperColors.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = false,
        )

    val defaultModel: StateFlow<Model> =
        appPreferences.defaultModel.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = Model.OPUS_4_7,
        )

    val defaultEffort: StateFlow<Effort> =
        appPreferences.defaultEffort.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = Effort.HIGH,
        )

    val defaultYolo: StateFlow<Boolean> =
        appPreferences.defaultYolo.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = false,
        )

    val pushNotifications: StateFlow<Boolean> =
        appPreferences.notificationsEnabled.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = true,                      // #268 — matches the `?: true` default
        )

    val defaultWorkspace: StateFlow<String> =
        appPreferences.defaultWorkspace.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = DEFAULT_SCRATCH_CWD,
        )

    private val pendingWorkspacePicker = MutableStateFlow(false)
    val workspacePickerVisible: StateFlow<Boolean> = pendingWorkspacePicker.asStateFlow()

    val archivedDiscussionCount: StateFlow<Int> =
        conversationRepository
            .observeConversations(ConversationFilter.Archived)
            .map { conversations -> conversations.count { !it.isPromoted } }
            .catch { emit(0) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = 0,
            )

    fun onSelectTheme(mode: ThemeMode) {
        viewModelScope.launch { appPreferences.setThemeMode(mode) }
    }

    fun onToggleUseWallpaperColors(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setUseWallpaperColors(enabled) }
    }

    fun onSelectDefaultModel(model: Model) {
        viewModelScope.launch { appPreferences.setDefaultModel(model) }
    }

    fun onSelectDefaultEffort(effort: Effort) {
        viewModelScope.launch { appPreferences.setDefaultEffort(effort) }
    }

    fun onToggleDefaultYolo(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setDefaultYolo(enabled) }
    }

    fun onTogglePushNotifications(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setNotificationsEnabled(enabled) }
    }

    fun onDefaultWorkspaceTapped() {
        pendingWorkspacePicker.value = true
    }

    fun onSelectDefaultWorkspace(path: String) {
        pendingWorkspacePicker.value = false
        viewModelScope.launch { appPreferences.setDefaultWorkspace(path) }
    }

    fun onWorkspacePickerDismissed() {
        pendingWorkspacePicker.value = false
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
```

- **`stateIn(WhileSubscribed(5_000L), initial = <neutral>)`** — same idiom as [`ChannelListViewModel`](channel-list-viewmodel.md) and [`DiscussionListViewModel`](discussion-list-viewmodel.md). The 5 s grace period keeps the upstream subscription alive across config changes (rotation, dark-mode toggle) without holding it open after the screen leaves the back stack. All eight preference/derived `stateIn` projections pick the value the cold upstream would emit first as their `initialValue` (`ThemeMode.SYSTEM` for `themeMode`, `false` for `useWallpaperColors`, `Model.OPUS_4_7` for `defaultModel` — matches `AppPreferences.defaultModel`'s tolerant-unknown fallback from #231, `Effort.HIGH` for `defaultEffort` — same shape, `false` for `defaultYolo` — matches `AppPreferences.defaultYolo`'s default from #231, **`true` for `pushNotifications` (#268) — the lone `true` in the set, matching `AppPreferences.notificationsEnabled`'s `?: true` Figma-sourced default**, `DEFAULT_SCRATCH_CWD` for `defaultWorkspace` (#235) — matches `AppPreferences.defaultWorkspace`'s sentinel fallback so the row shows "scratch" before the first emission, `0` for `archivedDiscussionCount` — which also happens to be Figma 17:2's "0 archived" empty-state literal) — same value, no first-frame flash. The `pushNotifications` case is the one where picking the wrong literal would be *visible* (a `false` initial would flash OFF→ON on cold open), which is why the #268 spec called the `true` initial load-bearing. `STOP_TIMEOUT_MILLIS = 5_000L` is the single tuning knob for all these projections, `host` (#749) included. The `workspacePickerVisible` flag (#235) is **not** in this set — it's a hot `MutableStateFlow(false)` exposed via `asStateFlow()`, not a `stateIn`-lifted cold upstream, so it has no `WhileSubscribed`/`initialValue` recipe; it's pure transient UI state that resets to `false` on pick or dismiss.
- **`host`'s `initialValue` is `SettingsHostState.Resolving`, not a "neutral" preference-shaped value (#749).** This is the one projection here over a genuinely cold upstream `hosts` join (the registry's `hostConnections` re-map plus one `store.list()` read per emission), rather than a DataStore flow that always has a well-known first value — so unlike the other seven, there is no correct guess to make before the first emission. `Resolving` renders nothing, deliberately, rather than flashing `Unpaired` or `Unknown` for one frame while the join catches up. It is also the one projection whose `stateIn` lift is conditional: a blank `ownerServerId` short-circuits to a plain `MutableStateFlow(Unpaired)` and never subscribes to `hosts` at all, so a destination opened with nothing to own can't even transiently show `Resolving`.
- **`flatMapLatest` over `hosts`, not `map`.** Each re-emission of the host list re-derives which entry is the owner and switches to *that* entry's own `status` flow, cancelling the previous collection first. A host-list re-emission that carries a removal is itself what cancels the departing owner's status collector — there is no window where a removed host's stale status can still reach `host`.
- **`conversationRepository` is a constructor parameter, not a `private val`.** Only the field-initializer for `archivedDiscussionCount` reads it once; promoting it to a property would advertise an instance dependency that doesn't exist. If a future event handler ever needs to call `conversationRepository.archive(...)` from this VM, promote then — see [`../codebase/164.md`](../codebase/164.md) § Patterns established.
- **`.catch { emit(0) }` upstream of `stateIn` (archived count only).** Deliberate divergence from `ArchivedDiscussionsViewModel`, which surfaces `Error(message)` for the same upstream because the list IS its screen's primary content. Settings' supporting text is read-only metadata about that other screen, so an upstream throw collapses to `"0 archived"` rather than tearing down the flow. The rule the two consumers now demonstrate: **supportive-metadata projections swallow upstream errors; primary-content projections surface them.** Future supportive-metadata flows (count badges, last-updated timestamps, "N pending" lines) should follow.
- **Fire-and-forget writes.** None of `onSelectTheme`, `onToggleUseWallpaperColors`, `onSelectDefaultModel`, `onSelectDefaultEffort`, `onToggleDefaultYolo`, `onTogglePushNotifications`, `onSelectDefaultWorkspace` `await`s the `edit { … }` or surfaces a result. The persisted value re-emits through the upstream `Flow` after the setter returns; both this VM's projection and (for `themeMode` / `useWallpaperColors`) the root-level `MainActivity` collector that drives `PyrycodeMobileTheme(darkTheme = …, dynamicColor = …)` pick up the change. `defaultModel` has [`ThreadViewModel.selectedModelFlow`](thread-screen.md) as a second consumer since #253 (StatusSheet model section), so an `onSelectDefaultModel` write fans out to both this VM's projection and the StatusSheet's current-model display on every open conversation. `defaultEffort`'s second consumer is `ThreadViewModel.selectedEffortFlow` (#229) via the StatusSheet `FilterChip` row. `defaultYolo` (since #234) has **no second consumer** — an `onToggleDefaultYolo` write fans out only to this VM's own projection (the row's reflected Switch state); #229 deliberately does not seed per-conversation YOLO from it (see § Edge cases). `pushNotifications` (since #268) likewise has **no second consumer** — an `onTogglePushNotifications` write fans out only to this VM's own projection; notification *delivery* is Phase 4, so nothing else reads `notificationsEnabled` today. `defaultWorkspace` (since #235) has a second consumer in [`ChannelListViewModel`](channel-list-viewmodel.md) (#240), but **not a live one** — it reads `appPreferences.defaultWorkspace.first()` as a one-shot snapshot at FAB-short-press time, not a continuous collector, so an `onSelectDefaultWorkspace` write doesn't fan out live; it's simply persisted and picked up on the next short-press. Same fire-and-forget shape as `MainActivity.kt`'s `setPairedServerExists` call from #12 — see [App preferences § Edge cases](app-preferences.md) for why no `Result`-returning write API exists today.

## Configuration / usage

Registered once in the Koin module:

```kotlin
// di/AppModule.kt
viewModel { get<ThreadDestinationFactory>().settings(get(), get(), get()) }
```

The Koin registration changed shape in #749: `SettingsViewModel` construction moved from an inline
`viewModel { SettingsViewModel(get(), get(), get<RelayRepositoryCoordinator>().connectionStatus) }`
into `ThreadDestinationFactory.settings(handle, preferences, repository)`, alongside its `thread`
and `literal` methods — see [dependency injection § Destination ownership](dependency-injection.md#destination-ownership)
for the factory method and the `hosts()` projection it builds `SettingsViewModel`'s `hosts` argument
from. `preferences` and `repository` stay the same compatibility-bound `AppPreferences` /
`ConversationRepository` singletons #398 resolved; only the connection signal moved from a direct
coordinator read to the per-host `hosts` join.

Consumed once at the Settings NavHost destination:

```kotlin
composable(route = Routes.SETTINGS, arguments = Routes.settingsArguments()) {
    val vm = koinViewModel<SettingsViewModel>()
    val host by vm.host.collectAsStateWithLifecycle()   // #749
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val useWallpaperColors by vm.useWallpaperColors.collectAsStateWithLifecycle()
    val archivedDiscussionCount by vm.archivedDiscussionCount.collectAsStateWithLifecycle()
    val defaultModel by vm.defaultModel.collectAsStateWithLifecycle()
    val defaultEffort by vm.defaultEffort.collectAsStateWithLifecycle()
    val defaultYolo by vm.defaultYolo.collectAsStateWithLifecycle()
    val pushNotifications by vm.pushNotifications.collectAsStateWithLifecycle()
    val defaultWorkspace by vm.defaultWorkspace.collectAsStateWithLifecycle()
    val workspacePickerVisible by vm.workspacePickerVisible.collectAsStateWithLifecycle()
    SettingsScreen(
        host = host,                                 // #749
        themeMode = themeMode,
        useWallpaperColors = useWallpaperColors,
        archivedDiscussionCount = archivedDiscussionCount,
        defaultModel = defaultModel,
        defaultEffort = defaultEffort,
        defaultYolo = defaultYolo,
        pushNotifications = pushNotifications,
        defaultWorkspace = defaultWorkspace,
        workspacePickerVisible = workspacePickerVisible,
        onSelectTheme = vm::onSelectTheme,
        onToggleUseWallpaperColors = vm::onToggleUseWallpaperColors,
        onSelectDefaultModel = vm::onSelectDefaultModel,
        onSelectDefaultEffort = vm::onSelectDefaultEffort,
        onToggleDefaultYolo = vm::onToggleDefaultYolo,
        onTogglePushNotifications = vm::onTogglePushNotifications,
        onDefaultWorkspaceTapped = vm::onDefaultWorkspaceTapped,
        onSelectDefaultWorkspace = vm::onSelectDefaultWorkspace,
        onWorkspacePickerDismissed = vm::onWorkspacePickerDismissed,
        onPairServer = { navController.navigate(Routes.SCANNER) },   // #749
        onBack = { navController.popBackStack() },
        onOpenArchivedDiscussions = { navController.navigate(Routes.ARCHIVED_DISCUSSIONS) },
        onOpenAbout = { navController.navigate(Routes.ABOUT) },
    )
}
```

Note the no-arg `collectAsStateWithLifecycle()` calls — all ten `vm.*` reads (eight preference/derived projections + the `workspacePickerVisible` flag + `host` since #749) are already `StateFlow`s, so the overload picks up `StateFlow.value` as initial. Compare with the root-level `appPreferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)` / `appPreferences.useWallpaperColors.collectAsStateWithLifecycle(initialValue = false)` in `MainActivity`'s `setContent` block, which collect the *cold* flows and must supply same-shaped neutral defaults. The composition-root collectors are independent of this VM's projections — DataStore broadcasts to every subscriber, so one `setUseWallpaperColors(...)` write reaches both the global theme repaint and this VM's `useWallpaperColors.value` from a single upstream emission. The `archivedDiscussionCount` flow has no composition-root sibling — it's Settings-screen-local. `defaultModel` and `defaultEffort` each have a second consumer in `ThreadViewModel` (StatusSheet model + effort sections) since #253 / #229 respectively; the new-conversation materialiser will eventually become a third consumer of both. `defaultYolo` (since #234) is Settings-screen-local with no second consumer — #229 deliberately does not read it; the new-conversation materialiser is its only prospective future reader. `pushNotifications` (since #268) is likewise Settings-screen-local with no second consumer — notification *delivery* (Phase 4) is its only prospective future reader. `defaultWorkspace` (since #235) is read by [`ChannelListViewModel`](channel-list-viewmodel.md) (#240) but as a one-shot `.first()` snapshot at FAB-short-press time, not a live composition-root collector. The `workspacePickerVisible` flag is Settings-screen-local transient UI state with no upstream and no sibling collector at all. `host` (since #749) has no composition-root sibling either — it is resolved from the destination's own `SavedStateHandle` argument, not from any compatibility selection state `MainActivity` tracks elsewhere.

## Edge cases / limitations

- **No write-failure surface.** `setThemeMode` / `setUseWallpaperColors` / `setDefaultModel` / `setDefaultEffort` / `setDefaultYolo` / `setNotificationsEnabled` / `setDefaultWorkspace` can each in principle throw `IOException` from DataStore; this VM does not catch or surface that. If a write ever fails in the field, the persisted value stays at its prior value, the upstream re-emits the prior value, and the dialog (or Switch, or picker row) reverts either way. Adding a snackbar would mean adding a `Result`-returning write API on `AppPreferences` — out of scope until an actual failure mode is observed (see [evidence-based fix selection](app-preferences.md)).
- **`defaultYolo` is write-live but read-dead (#234).** The Default-YOLO Switch persists end-to-end — toggle it, restart, it sticks. But **nothing reads `defaultYolo` to act on it.** Per [#229](../codebase/229.md)'s design the StatusSheet does not seed per-conversation YOLO from this default — `ThreadViewModel.yoloEnabled` is hardcoded `false` and `ThreadViewModelTest.kt:375-416` pins that invariant. The Settings default exists purely as a persisted user-intent signal and a hook for the eventual new-conversation materialiser. Don't wire it into new-conversation creation as a "fix" for the dead-end — that would have to revisit #229's "per-conversation YOLO always starts off" invariant first. See [`../codebase/234.md`](../codebase/234.md) § Lessons learned.
- **`pushNotifications` is write-live but read-dead (#268), and defaults `true`.** The Push-notifications Switch persists end-to-end, but **nothing reads `notificationsEnabled` to act on it** — actual notification *delivery* is Phase 4 (same scope boundary as #234). The `true` default is the load-bearing exception: it's the only preference here whose `initialValue` is `true` (Figma `17:2` renders the toggle ON), copied from the `defaultYolo` block with the `false` default deliberately *not* carried over. Don't wire it into a system-notification channel as a "fix" for the dead-end — that's a deliberate Phase-0 scope boundary. See [`../codebase/268.md`](../codebase/268.md).
- **`host` resolution errors swallow to `Unknown`, matching the archived-count rule (#749).** `.catch { emit(SettingsHostState.Unknown) }` sits directly upstream of the `stateIn` — a throwing `hosts` source (in practice defence in depth: an unreadable paired-server blob makes `store.list()` return empty rather than throw) collapses to the same copy a genuinely absent owner shows, never to a crash or a stale value. This is the same "supportive-metadata projections swallow upstream errors" rule `archivedDiscussionCount`'s `.catch { emit(0) }` established.
- **Archived-count read-failure swallowed silently.** `.catch { emit(0) }` on the upstream — if the repository's `Archived` stream ever throws, the row reads `"0 archived"` and `[Archived Discussions screen](archived-discussions-screen.md)` becomes the surface that owns the error UI. Tested via `MutableSharedFlow` source emit + cancel-then-throw scenarios isn't necessary; the `.catch` is one line and the AC explicitly accepts "0 archived" as the empty/error display.
- **Seven-pair surface (writable preferences) — the "lift to sealed `SettingsState`" threshold passed unevaluated a fourth time at #268, and there are no triggers left.** #87 set the rule that the MVI envelope arrives on the *third real persisted preference*. The count was one after #87 (`themeMode`), two after #89 (`+useWallpaperColors`), three after #233 (`+defaultEffort`, deferred), four after #232 (`+defaultModel`), five after #234 (`+defaultYolo`), six after #235 (`+defaultWorkspace`), and is **seven after #268 (`+pushNotifications`)**. #232/#233's lessons named "the next defaults consumer (YOLO switch row or workspace picker, whichever lands first)" as the unambiguous re-evaluation point; #234 (YOLO) and #235 (workspace) both landed scoped as XS/S plumbing and never raised the lift question, so the mechanical-copy pattern ran out the entire "Defaults for new conversations" group. #268 then copied the same `(StateFlow<Boolean>, fun onToggleX)` shape *outside* that group — into the Notifications section — confirming the pattern is self-perpetuating across sections and that **no "natural trigger" row remains.** The shape has settled by default; any lift is now a dedicated refactor ticket that must restructure all seven pairs at once, not a side-effect of the next feature slice. See [`../codebase/232.md`](../codebase/232.md), [`../codebase/233.md`](../codebase/233.md), [`../codebase/234.md`](../codebase/234.md), and [`../codebase/268.md`](../codebase/268.md) § Threshold notes for the deferral trail. The `archivedDiscussionCount` projection from #164 (read-only, data-derived), the `workspacePickerVisible` flag from #235 (transient UI state), and the `host` projection from #749 (read-only, resolved against saved hosts — the successor to #398's forwarded `connectionStatus`, which counted the same way) do not count toward the threshold.

## Testing

`app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` — 46 methods through #749. Uses the `AppPreferencesTest` rig (real `PreferenceDataStoreFactory` over `TemporaryFolder` + real `AppPreferences`, no fake) plus `Dispatchers.setMain(UnconfinedTestDispatcher())` + `runTest(dispatcher) { … advanceUntilIdle() … }` from [`ChannelListViewModelTest`](channel-list-viewmodel.md). Since #164 a hand-rolled `ConversationRepository` stub backed by a `MutableSharedFlow<List<Conversation>>(replay = 0)` drives the archived-count scenarios; the prior nine theme/wallpaper tests + five `defaultEffort` tests (#233) + five `defaultModel` tests (#232) + three `defaultYolo` tests (#234) + seven `defaultWorkspace`/workspace-picker tests (#235, not individually enumerated in the list below — a gap from #235's doc run) + three `pushNotifications` tests (#268) all stay untouched through the `makeVm` helper, which #749 changed to `makeVm(prefs, repo = stubRepo(), ownerServerId: String = "", hosts: Flow<List<SettingsHost>> = MutableStateFlow(emptyList())) = SettingsViewModel(prefs, repo, ownerServerId, hosts)` — the **last two params are defaulted** precisely so every existing call site compiles unchanged, replacing #398's defaulted `connectionStatus` param in the same slot. A `host(serverId, displayName = null, relayUrl = "wss://relay.example/$serverId", status = MutableStateFlow(OFFLINE))` helper builds `SettingsHost` fixtures for the new cases below.

Coverage:

- `initialState_emitsSystem_whenNoStoredValue` — fresh datastore.
- `initialState_mirrorsPersistedValue` — pre-write `DARK`, construct VM, assert `vm.themeMode.value == DARK`.
- `onSelectTheme_persistsLight` / `_persistsDark` / `_persistsSystem` — round-trip each `ThemeMode` (the SYSTEM case starts from a pre-written DARK to exercise the round-trip-from-non-default path).
- `themeMode_flowReEmits_afterOnSelectTheme` — two consecutive `onSelectTheme` calls, assert `vm.themeMode.value` reaches each in turn.
- `useWallpaperColors_initialState_emitsFalse_whenNoStoredValue` — fresh datastore boolean default (#89).
- `useWallpaperColors_initialState_mirrorsPersistedTrue` — pre-write `true`, construct VM, assert `vm.useWallpaperColors.value == true` (#89).
- `onToggleUseWallpaperColors_persistsAndFlowReEmits` — `false → true → false` walk, asserting both `prefs.useWallpaperColors.first()` (persistence) and `vm.useWallpaperColors.value` (re-emission) on each step (#89). Single test covers both AC clauses ("persists via the fake" + "flow re-emits after persistence") in one body — same compression rationale as #88's round-trip-plus-re-emit collapse.
- `defaultModel_initialState_emitsOpus47_whenNoStoredValue` — fresh datastore, collector + `advanceUntilIdle`, `value == Model.OPUS_4_7` (#232).
- `defaultModel_initialState_mirrorsPersistedValue` — pre-write `Model.HAIKU_4_5`, construct VM, assert `vm.defaultModel.value == Model.HAIKU_4_5` (#232).
- `onSelectDefaultModel_persistsSonnet46` / `_persistsHaiku45` — round-trip the two non-default variants via `vm.onSelectDefaultModel(...)` + `prefs.defaultModel.first()` (the third variant `OPUS_4_7` is covered transitively by the fresh-datastore default test) (#232).
- `defaultModel_flowReEmits_afterOnSelectDefaultModel` — two consecutive `onSelectDefaultModel` calls (`SONNET_4_6` → `HAIKU_4_5`), assert `vm.defaultModel.value` reaches each in turn. Gates AC #5's "exposed flow re-emits after persistence" (#232).
- `defaultEffort_initialState_emitsHigh_whenNoStoredValue` — fresh datastore, collector + `advanceUntilIdle`, `value == Effort.HIGH` (#233).
- `defaultEffort_initialState_mirrorsPersistedValue` — pre-write `LOW`, construct VM, assert `vm.defaultEffort.value == Effort.LOW` (#233).
- `onSelectDefaultEffort_persistsLow` / `_persistsMax` — round-trip both `Effort.entries` boundaries (catches off-by-one in the enum lookup); middle values stay uncovered because they ride the same `setDefaultEffort` round-trip the boundaries already exercise (#233).
- `defaultEffort_flowReEmits_afterOnSelectDefaultEffort` — two consecutive `onSelectDefaultEffort` calls (`LOW` → `XHIGH`), assert `vm.defaultEffort.value` reaches each in turn. Gates AC #5's "exposed flow re-emits after persistence" (#233).
- `defaultYolo_initialState_emitsFalse_whenNoStoredValue` — fresh datastore, collector + `advanceUntilIdle`, `value == false` (#234).
- `defaultYolo_initialState_mirrorsPersistedTrue` — pre-write `true`, construct VM, assert `vm.defaultYolo.value == true` (#234).
- `onToggleDefaultYolo_persistsAndFlowReEmits` — `true → false` walk, asserting both `prefs.defaultYolo.first()` (persistence) and `vm.defaultYolo.value` (re-emission) on each step (#234). Single test covers both AC clauses ("persists" + "flow re-emits after persistence") in one body — three tests total (not the five-test enum template) because a boolean has no `.entries` boundaries to round-trip; same compression as the `useWallpaperColors` trio it mirrors.
- `pushNotifications_initialState_emitsTrue_whenNoStoredValue` — fresh datastore, collector + `advanceUntilIdle`, `value == true` (#268). The **inverted** mirror of `defaultYolo_initialState_emitsFalse_…` — guards the load-bearing `true` default at the VM layer (the data-layer guard is `AppPreferencesTest.notificationsEnabled_defaultsToTrue`).
- `pushNotifications_initialState_mirrorsPersistedFalse` — pre-write `false` (the **non-default**), construct VM, assert `vm.pushNotifications.value == false` (#268). Persisting `false` is the meaningful mirror: it proves a stored value overrides the `true` default.
- `onTogglePushNotifications_persistsAndFlowReEmits` — `false → true` walk, asserting both `prefs.notificationsEnabled.first()` (persistence) and `vm.pushNotifications.value` (re-emission) on each step (#268). Three tests total, same boolean compression as the `defaultYolo` trio — default expectations inverted throughout.
- `host_resolvesOwnerAmongTwoSavedHosts` — two saved hosts, owner is the second; asserts the resolved `Owned`'s `serverId`/`name`/`relayUrl`/`status` are the owner's and never the other host's (#749).
- `host_keepsOwnerAcrossHostListReemission` — the at-this-layer form of a compatibility-selection change, since selection isn't observable at this layer at all: re-emit the same two hosts in swapped order and assert the resolved owner and its status are unchanged (#749).
- `host_followsOwnerRenameAndStatusUpdates` — mutate the owner's status flow, then re-emit the host list with a new `displayName` for the same id, asserting `host.value` picks up both while the flow stays subscribed (#749).
- `host_namesUnnamedOwnerByItsServerId` — `displayName = null` and a blank `"   "` both resolve `Owned.name` to the server id (#749).
- `host_reportsUnknownWhenOwnerIsNotSaved` — owner id absent from a two-host list → `SettingsHostState.Unknown`, never a fallback to either saved host (#749).
- `host_reportsUnknownWhenTheOwnerIsRemovedWhileOpen` — owner present then the host list re-emits without it → `Unknown` (#749).
- `host_reportsUnpairedWhenTheDestinationOwnsNoHost` — blank `ownerServerId` → `Unpaired` synchronously, before any collector runs, and **stays** `Unpaired` even after a host subsequently appears in `hosts` — the blank-owner short-circuit never re-consults the list (#749).
- `host_resolvesBeforeTheFirstHostEmission` — non-blank owner, `hosts` a cold `MutableSharedFlow` that has not emitted yet → `host.value == SettingsHostState.Resolving` (#749). Pins the `stateIn` initial value, not a race — the test never lets `hosts` emit.
- `archivedDiscussionCount_initialValue_isZero` — fresh VM, no source emission; collector launched + `advanceUntilIdle`; `value == 0` (#164).
- `archivedDiscussionCount_reflectsArchivedUnpromotedConversations` — emit three `archivedDiscussion(...)` fixtures; `value == 3` (#164).
- `archivedDiscussionCount_excludesPromotedArchivedConversations` — emit `[archivedDiscussion("disc-1"), archivedChannel("chan-1"), archivedChannel("chan-2")]`; `value == 1` (locks in the `!isPromoted` post-filter, #164).
- `archivedDiscussionCount_updates_whenConversationArchived` — emit `emptyList()` → 0, then emit `[archivedDiscussion]` → 1 (#164).
- `archivedDiscussionCount_updates_whenConversationUnarchived` — emit `[archivedDiscussion]` → 1, then emit `emptyList()` → 0 (#164).
- `archivedDiscussionCount_passesArchivedFilter` — `stubRepo(source, captureFiltersInto = captured)` records every `ConversationFilter` value the VM passes to `observeConversations`; assert `captured == [ConversationFilter.Archived]` (#164). The hand-rolled stub exists primarily for this assertion — the real `FakeConversationRepository` would require either internal inspection or contrived count differentials to verify the filter value.

Tests that read `vm.themeMode.value` / `vm.useWallpaperColors.value` / `vm.defaultModel.value` / `vm.defaultEffort.value` / `vm.defaultYolo.value` / `vm.pushNotifications.value` / `vm.archivedDiscussionCount.value` `launch` a no-op collector and `advanceUntilIdle()` first to keep the `WhileSubscribed(5_000L)` upstream subscribed; otherwise `.value` stays at the `initialValue` literal (`ThemeMode.SYSTEM` / `false` / `Model.OPUS_4_7` / `Effort.HIGH` / `false` / **`true`** for `pushNotifications` / `0`) and the test asserts the wrong thing — note `pushNotifications`'s initial is `true`, so a missing collector would make `pushNotifications_initialState_mirrorsPersistedFalse` *pass for the wrong reason* (it would read the `true` literal instead of the persisted `false`), which is exactly why that test launches the collector. Tests that read through `prefs.themeMode.first()` / `prefs.useWallpaperColors.first()` / `prefs.defaultModel.first()` / `prefs.defaultEffort.first()` / `prefs.defaultYolo.first()` / `prefs.notificationsEnabled.first()` directly don't need the collector — they bypass the VM's `stateIn` projection.

## Related

- Spec: `docs/specs/architecture/87-settings-theme-picker-dialog.md`; `docs/specs/architecture/89-settings-use-wallpaper-colors-switch.md`; `docs/specs/architecture/164-settings-archived-discussion-live-count.md`; `docs/specs/architecture/232-settings-default-model-picker-dialog.md`; `docs/specs/architecture/233-settings-default-effort-picker-dialog.md`; `docs/specs/architecture/234-settings-default-yolo-toggle-persistence.md`; `docs/specs/architecture/235-settings-default-workspace-picker.md`; `docs/specs/architecture/268-settings-push-notifications-toggle-persistence.md`; `docs/specs/architecture/398-settings-connection-status-line-wiring.md` (superseded by #749); `docs/specs/architecture/749-settings-destination-host-owner.md`
- Ticket notes: [`../codebase/87.md`](../codebase/87.md), [`../codebase/89.md`](../codebase/89.md), [`../codebase/164.md`](../codebase/164.md), [`../codebase/232.md`](../codebase/232.md), [`../codebase/233.md`](../codebase/233.md), [`../codebase/234.md`](../codebase/234.md), [`../codebase/235.md`](../codebase/235.md), [`../codebase/268.md`](../codebase/268.md), [`../codebase/398.md`](../codebase/398.md)
- Host projection source (#749): [Dependency injection § Destination ownership](dependency-injection.md#destination-ownership) — `ThreadDestinationFactory.settings` and the `hosts()` join it builds from `RelayConnectionRegistry.hostConnections` + `PairedServerCollectionStore.list()`
- Sibling VMs: [ChannelListViewModel](channel-list-viewmodel.md), [DiscussionListViewModel](discussion-list-viewmodel.md)
- Sibling consumer of the archived stream: [ArchivedDiscussionsViewModel](archived-discussions-screen.md) — primary-content surface (surfaces `Error(message)`); this VM is the supportive-metadata sibling that swallows the same upstream's errors via `.catch { emit(0) }`
- Backing preferences: [App preferences](app-preferences.md) — `themeMode` flow + `setThemeMode` setter; `useWallpaperColors` flow + `setUseWallpaperColors` setter (#89's write site); `defaultModel` flow + `setDefaultModel` setter (#231 schema, #232 first writer); `defaultEffort` flow + `setDefaultEffort` setter (#231 schema, #233 first consumer); `defaultYolo` flow + `setDefaultYolo` setter (#231 schema, #234 first writer); `defaultWorkspace` flow + `setDefaultWorkspace` setter (#231 schema, #235 first writer); `notificationsEnabled` flow + `setNotificationsEnabled` setter (#268 first definer + first writer — the only `?: true` default)
- Backing repository: [Conversation repository](conversation-repository.md) — `observeConversations(ConversationFilter.Archived)` (#93)
- Consumer screen: [Settings screen](settings-screen.md) — Theme row + `ThemePickerDialog`; Use-wallpaper-colors switch row (#89); Storage row "N archived" supporting line (#164); Default-model row + `ModelPickerDialog` (#232); Default-effort row + `EffortPickerDialog` (#233); Default-YOLO switch row (#234); Default-workspace row + `WorkspacePicker` host (#235); Push-notifications switch row (#268); Connection-section host identity row + non-owned states (#749, superseding #398's status line)
- Sibling root collectors: `MainActivity.setContent { … appPreferences.themeMode.collectAsStateWithLifecycle(...) ; appPreferences.useWallpaperColors.collectAsStateWithLifecycle(...) }` from #86 + #88 — drive `PyrycodeMobileTheme(darkTheme = …, dynamicColor = …)` from the same upstream flows this VM reads, so a single `setThemeMode` / `setUseWallpaperColors` write fans out to both the global theme and the Settings row. `archivedDiscussionCount` has no composition-root sibling — it's Settings-screen-local.
