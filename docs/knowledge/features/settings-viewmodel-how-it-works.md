# SettingsViewModel — how it works

Split out of [SettingsViewModel](settings-viewmodel.md) on 2026-09-21 to keep that document under
the 50000-byte size cap the docs guard enforces. This section moved here verbatim and kept its
heading, so its anchors are unchanged. Part of [SettingsViewModel](settings-viewmodel.md); see that
document for what it does, its configuration/usage wiring, its edge cases, its testing and its links.

## How it works

```kotlin
class SettingsViewModel(
    private val appPreferences: AppPreferences,
    conversationRepository: ConversationRepository,
    private val ownerServerId: String,   // from Routes.settingsOwner(handle); blank = no host
    hosts: Flow<List<SettingsHost>>,     // every saved host's identity + live status
) : ViewModel() {
    val connection: StateFlow<SettingsConnectionState> =           // #750, replacing #749's host
        hosts
            .flatMapLatest { saved ->
                val ownerMissing = ownerServerId.isNotBlank() && saved.none { it.serverId == ownerServerId }
                // combine over an empty array never emits — without this short-circuit an unpaired
                // phone would stay at Resolving forever and silently lose its no-host copy.
                if (saved.isEmpty()) {
                    flowOf(SettingsConnectionState.Loaded(emptyList(), ownerMissing))
                } else {
                    combine(saved.map { it.status }) { statuses ->
                        SettingsConnectionState.Loaded(
                            saved.mapIndexed { index, h ->
                                SettingsHostRow(
                                    h.serverId, h.displayName, h.relayUrl, statuses[index],
                                    isOwner = ownerServerId.isNotBlank() && h.serverId == ownerServerId,
                                )
                            },
                            ownerMissing = ownerMissing,
                        )
                    }
                }
            }
            .catch { emit(SettingsConnectionState.Loaded(emptyList(), ownerMissing = false)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = SettingsConnectionState.Resolving,
            )

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
        (if (ownerServerId.isBlank()) flowOf(DEFAULT_SCRATCH_CWD) else appPreferences.defaultWorkspace(ownerServerId))
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = DEFAULT_SCRATCH_CWD,
            )

    private val pendingWorkspacePicker = MutableStateFlow<String?>(null)
    val workspacePickerServerId: StateFlow<String?> = pendingWorkspacePicker.asStateFlow()

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
        if (ownerServerId.isBlank()) {
            RelayLog.d { "event=settings_workspace_picker_rejected code=no_owner" }
            return
        }
        pendingWorkspacePicker.value = ownerServerId
        RelayLog.d { "event=settings_workspace_picker_opened" }
    }

    fun onSelectDefaultWorkspace(path: String) {
        val serverId = pendingWorkspacePicker.value
        if (serverId == null) {
            RelayLog.d { "event=settings_workspace_default_rejected code=no_open_picker" }
            return
        }
        pendingWorkspacePicker.value = null
        viewModelScope.launch { appPreferences.setDefaultWorkspace(serverId, path) }
    }

    fun onWorkspacePickerDismissed() {
        pendingWorkspacePicker.value = null
        RelayLog.d { "event=settings_workspace_picker_dismissed" }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
```

- **`stateIn(WhileSubscribed(5_000L), initial = <neutral>)`** — same idiom as [`ChannelListViewModel`](channel-list-viewmodel.md) and [`DiscussionListViewModel`](discussion-list-viewmodel.md). The 5 s grace period keeps the upstream subscription alive across config changes (rotation, dark-mode toggle) without holding it open after the screen leaves the back stack. All eight preference/derived `stateIn` projections pick the value the cold upstream would emit first as their `initialValue` (`ThemeMode.SYSTEM` for `themeMode`, `false` for `useWallpaperColors`, `Model.OPUS_4_7` for `defaultModel` — matches `AppPreferences.defaultModel`'s tolerant-unknown fallback from #231, `Effort.HIGH` for `defaultEffort` — same shape, `false` for `defaultYolo` — matches `AppPreferences.defaultYolo`'s default from #231, **`true` for `pushNotifications` (#268) — the lone `true` in the set, matching `AppPreferences.notificationsEnabled`'s `?: true` Figma-sourced default**, `DEFAULT_SCRATCH_CWD` for `defaultWorkspace` (#235; host-keyed by #714) — matches `AppPreferences.defaultWorkspace(serverId)`'s sentinel fallback so the row shows "scratch" before the first emission, and is also the literal value for a blank `ownerServerId`'s `flowOf(DEFAULT_SCRATCH_CWD)` — the two paths share one `initialValue` even though only the non-blank path has a cold upstream to guess ahead of, `0` for `archivedDiscussionCount` — which also happens to be Figma 17:2's "0 archived" empty-state literal) — same value, no first-frame flash. The `pushNotifications` case is the one where picking the wrong literal would be *visible* (a `false` initial would flash OFF→ON on cold open), which is why the #268 spec called the `true` initial load-bearing. `STOP_TIMEOUT_MILLIS = 5_000L` is the single tuning knob for all these projections, `connection` included. The `workspacePickerServerId` flag (#235, rekeyed by #714) is **not** in this set — it's a hot `MutableStateFlow<String?>(null)` exposed via `asStateFlow()`, not a `stateIn`-lifted cold upstream, so it has no `WhileSubscribed`/`initialValue` recipe; it's pure transient UI state that resets to `null` on pick or dismiss.
- **`connection`'s `initialValue` is `SettingsConnectionState.Resolving`, not a "neutral" preference-shaped value.** This is the one projection here over a genuinely cold upstream `hosts` join (the registry's `hostConnections` re-map plus one `store.list()` read per emission), rather than a DataStore flow that always has a well-known first value — so unlike the other seven, there is no correct guess to make before the first emission. `Resolving` renders nothing, deliberately, rather than flashing an empty or wrong list for one frame while the join catches up. Unlike #749's `host`, this `stateIn` lift is **unconditional** — a blank `ownerServerId` still subscribes to `hosts`, because #750 needs the saved-host list even for a destination that owns none of it; only the per-row `isOwner` computation, not the subscription, is what a blank owner short-circuits.
- **`flatMapLatest` over `hosts`, not `map`.** Each re-emission of the host list re-derives every row's `isOwner` flag and switches to a fresh `combine` over *every* host's own `status` flow, cancelling the previous `combine` — and every per-host collector inside it — first. A host-list re-emission that carries a removal is itself what cancels the departing host's status collector, whether or not it was the owner — there is no window where a removed host's stale status can still reach `connection`. `combine` over an empty array never emits, which is why the empty-list branch above short-circuits to a plain `flowOf(...)` rather than falling into `combine(emptyList()) { ... }` and leaving `connection` at `Resolving` forever.
- **`conversationRepository` is a constructor parameter, not a `private val`.** Only the field-initializer for `archivedDiscussionCount` reads it once; promoting it to a property would advertise an instance dependency that doesn't exist. If a future event handler ever needs to call `conversationRepository.archive(...)` from this VM, promote then — see [`../codebase/164.md`](../codebase/164.md) § Patterns established.
- **`.catch { emit(0) }` upstream of `stateIn` (archived count) and `.catch { emit(Loaded(emptyList(), ownerMissing = false)) }` (connection).** Deliberate divergence from `ArchivedDiscussionsViewModel`, which surfaces `Error(message)` for the same upstream because the list IS its screen's primary content. Settings' supporting text and connection rows are read-only metadata about hosts and conversations that live elsewhere, so an upstream throw collapses to `"0 archived"` or to naming no host rather than tearing down the flow. The rule these consumers now demonstrate: **supportive-metadata projections swallow upstream errors; primary-content projections surface them.** Future supportive-metadata flows (count badges, last-updated timestamps, "N pending" lines) should follow.
- **Fire-and-forget writes.** None of `onSelectTheme`, `onToggleUseWallpaperColors`, `onSelectDefaultModel`, `onSelectDefaultEffort`, `onToggleDefaultYolo`, `onTogglePushNotifications`, `onSelectDefaultWorkspace` `await`s the `edit { … }` or surfaces a result. The persisted value re-emits through the upstream `Flow` after the setter returns; both this VM's projection and (for `themeMode` / `useWallpaperColors`) the root-level `MainActivity` collector that drives `PyrycodeMobileTheme(darkTheme = …, dynamicColor = …)` pick up the change. `defaultModel` has [`ThreadViewModel.selectedModelFlow`](thread-screen.md) as a second consumer since #253 (StatusSheet model section), so an `onSelectDefaultModel` write fans out to both this VM's projection and the StatusSheet's current-model display on every open conversation. `defaultEffort`'s second consumer is `ThreadViewModel.selectedEffortFlow` (#229) via the StatusSheet `FilterChip` row. `defaultYolo` (since #234) has **no second consumer** — an `onToggleDefaultYolo` write fans out only to this VM's own projection (the row's reflected Switch state); #229 deliberately does not seed per-conversation YOLO from it (see § Edge cases). `pushNotifications` (since #268) likewise has **no second consumer** — an `onTogglePushNotifications` write fans out only to this VM's own projection; notification *delivery* is Phase 4, so nothing else reads `notificationsEnabled` today. `defaultWorkspace` has a second consumer in [`ChannelListViewModel`](channel-list-viewmodel.md) (#240), but **not a live one** — it reads `appPreferences.defaultWorkspace(capturedServerId).first()` as a one-shot snapshot at FAB-short-press time, not a continuous collector, so an `onSelectDefaultWorkspace` write doesn't fan out live; it's simply persisted and picked up on the next short-press for that same host. Since #714 both readers key off `serverId` rather than sharing one unqualified flow, so a write from Settings for host A and a short-press read for host B legitimately never see each other — only a write and a later read for the *same* host agree. Same fire-and-forget shape as `MainActivity.kt`'s `setPairedServerExists` call from #12 — see [App preferences § Edge cases](app-preferences.md) for why no `Result`-returning write API exists today.
- **`onSelectDefaultWorkspace` targets the *pending* owner, not `ownerServerId` (#714).** `pendingWorkspacePicker.value` is read into a local `serverId` and cleared **before** `viewModelScope.launch` — no suspension point sits between deciding the write's target and clearing the flag, so a dismissal or a second pick racing the coroutine cannot retarget an in-flight write. Reading off the pending value rather than off the constructor-captured `ownerServerId` is what gives a late-arriving pick (one behind a dismiss, or a second one behind a first) somewhere to fail: `serverId == null` and the write is dropped and logged, rather than silently landing on whatever owner happens to be captured.
