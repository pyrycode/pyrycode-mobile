# SettingsViewModel

Thin ViewModel for the Settings screen. Exposes persisted preferences (`themeMode`, `useWallpaperColors`) as hot `StateFlow`s and matching fire-and-forget setter callbacks, plus (since #164) a derived `archivedDiscussionCount` projection over `ConversationRepository`. Introduced in #87 (Theme row); extended in #89 (Use-wallpaper-colors switch) and #164 (archived-discussion count for the Storage row).

## What it does

- Reads `AppPreferences.themeMode` and exposes it as `themeMode: StateFlow<ThemeMode>`.
- Forwards `fun onSelectTheme(mode: ThemeMode)` into `AppPreferences.setThemeMode(mode)` via `viewModelScope.launch { … }`.
- Reads `AppPreferences.useWallpaperColors` and exposes it as `useWallpaperColors: StateFlow<Boolean>` (#89).
- Forwards `fun onToggleUseWallpaperColors(enabled: Boolean)` into `AppPreferences.setUseWallpaperColors(enabled)` via `viewModelScope.launch { … }` (#89).
- Reads `ConversationRepository.observeConversations(ConversationFilter.Archived)`, maps it to `count { !it.isPromoted }`, and exposes the result as `archivedDiscussionCount: StateFlow<Int>` (#164). Mirrors `ArchivedDiscussionsViewModel`'s filter exactly so the Settings Storage row's "N archived" supporting text and the destination [Archived Discussions screen](archived-discussions-screen.md)'s list never disagree.

Two parallel `(StateFlow<X>, fun onSelectX(...))` pairs plus one read-only projection. No sealed `SettingsState` / `SettingsEvent` envelope yet — the [Settings screen](settings-screen.md) has exactly two rows backed by real persisted state, and #87 set the rule that the MVI envelope arrives on the *third real persisted preference* (the rule counts persisted writable preferences; `archivedDiscussionCount` from #164 is a read-only derived projection and does not trip the trigger). The next persisted Settings preference (notification sound, default model, default effort) is the trigger to lift.

## How it works

```kotlin
class SettingsViewModel(
    private val appPreferences: AppPreferences,
    conversationRepository: ConversationRepository,
) : ViewModel() {
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

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
```

- **`stateIn(WhileSubscribed(5_000L), initial = <neutral>)`** — same idiom as [`ChannelListViewModel`](channel-list-viewmodel.md) and [`DiscussionListViewModel`](discussion-list-viewmodel.md). The 5 s grace period keeps the upstream subscription alive across config changes (rotation, dark-mode toggle) without holding it open after the screen leaves the back stack. All three flows pick the value the cold upstream would emit first as their `initialValue` (`ThemeMode.SYSTEM` for `themeMode`, `false` for `useWallpaperColors`, `0` for `archivedDiscussionCount` — which also happens to be Figma 17:2's "0 archived" empty-state literal) — same value, no first-frame flash. `STOP_TIMEOUT_MILLIS = 5_000L` is the single tuning knob for all three projections.
- **`conversationRepository` is a constructor parameter, not a `private val`.** Only the field-initializer for `archivedDiscussionCount` reads it once; promoting it to a property would advertise an instance dependency that doesn't exist. If a future event handler ever needs to call `conversationRepository.archive(...)` from this VM, promote then — see [`../codebase/164.md`](../codebase/164.md) § Patterns established.
- **`.catch { emit(0) }` upstream of `stateIn` (archived count only).** Deliberate divergence from `ArchivedDiscussionsViewModel`, which surfaces `Error(message)` for the same upstream because the list IS its screen's primary content. Settings' supporting text is read-only metadata about that other screen, so an upstream throw collapses to `"0 archived"` rather than tearing down the flow. The rule the two consumers now demonstrate: **supportive-metadata projections swallow upstream errors; primary-content projections surface them.** Future supportive-metadata flows (count badges, last-updated timestamps, "N pending" lines) should follow.
- **Fire-and-forget writes.** Neither `onSelectTheme` nor `onToggleUseWallpaperColors` `await`s the `edit { … }` or surfaces a result. The persisted value re-emits through the upstream `Flow` after the setter returns; both this VM's projection and the root-level `MainActivity` collector (the one that drives `PyrycodeMobileTheme(darkTheme = …, dynamicColor = …)`) pick up the change. Same fire-and-forget shape as `MainActivity.kt`'s `setPairedServerExists` call from #12 — see [App preferences § Edge cases](app-preferences.md) for why no `Result`-returning write API exists today.

## Configuration / usage

Registered once in the Koin module:

```kotlin
// di/AppModule.kt
viewModel { SettingsViewModel(get(), get()) }
```

The two `get()` calls resolve to the existing `AppPreferences` and `ConversationRepository` bindings (the latter is the same `FakeConversationRepository` instance `ChannelListViewModel` / `DiscussionListViewModel` / `ArchivedDiscussionsViewModel` share).

Consumed once at the Settings NavHost destination:

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
```

Note the no-arg `collectAsStateWithLifecycle()` calls — `vm.themeMode`, `vm.useWallpaperColors`, and `vm.archivedDiscussionCount` are already `StateFlow`s, so the overload picks up `StateFlow.value` as initial. Compare with the root-level `appPreferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)` / `appPreferences.useWallpaperColors.collectAsStateWithLifecycle(initialValue = false)` in `MainActivity`'s `setContent` block, which collect the *cold* flows and must supply same-shaped neutral defaults. The composition-root collectors are independent of this VM's projections — DataStore broadcasts to every subscriber, so one `setUseWallpaperColors(...)` write reaches both the global theme repaint and this VM's `useWallpaperColors.value` from a single upstream emission. The archived-count flow has no composition-root sibling — it's a Settings-screen-local read.

## Edge cases / limitations

- **No write-failure surface.** `setThemeMode` can in principle throw `IOException` from DataStore; this VM does not catch or surface that. If a write ever fails in the field, the persisted value stays at its prior value, the upstream re-emits the prior value, and the dialog closes either way. Adding a snackbar would mean adding a `Result`-returning write API on `AppPreferences` — out of scope until an actual failure mode is observed (see [evidence-based fix selection](app-preferences.md)).
- **Archived-count read-failure swallowed silently.** `.catch { emit(0) }` on the upstream — if the repository's `Archived` stream ever throws, the row reads `"0 archived"` and `[Archived Discussions screen](archived-discussions-screen.md)` becomes the surface that owns the error UI. Tested via `MutableSharedFlow` source emit + cancel-then-throw scenarios isn't necessary; the `.catch` is one line and the AC explicitly accepts "0 archived" as the empty/error display.
- **Two-key surface (writable preferences).** When the *third real persisted preference* lands (notification sound, default model, default effort — see [Settings screen](settings-screen.md) for the full row inventory), this is the trigger to lift the VM to a sealed `SettingsState` with per-field reducers. Until then, growing more `StateFlow<X>` + `fun onSelectX(...)` pairs is cheaper than the MVI envelope. #89 exercised the "grow by one pair" half of the rule from #87 — the count is now two; one more, and the envelope earns its keep. The `archivedDiscussionCount` projection from #164 is read-only and derived from the data layer, so it does not count toward the threshold.

## Testing

`app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` — 15 methods since #164. Uses the `AppPreferencesTest` rig (real `PreferenceDataStoreFactory` over `TemporaryFolder` + real `AppPreferences`, no fake) plus `Dispatchers.setMain(UnconfinedTestDispatcher())` + `runTest(dispatcher) { … advanceUntilIdle() … }` from [`ChannelListViewModelTest`](channel-list-viewmodel.md). Since #164 a hand-rolled `ConversationRepository` stub backed by a `MutableSharedFlow<List<Conversation>>(replay = 0)` drives the archived-count scenarios; the prior nine tests stay one-arg through a `makeVm(prefs, repo = stubRepo()) = SettingsViewModel(prefs, repo)` helper.

Coverage:

- `initialState_emitsSystem_whenNoStoredValue` — fresh datastore.
- `initialState_mirrorsPersistedValue` — pre-write `DARK`, construct VM, assert `vm.themeMode.value == DARK`.
- `onSelectTheme_persistsLight` / `_persistsDark` / `_persistsSystem` — round-trip each `ThemeMode` (the SYSTEM case starts from a pre-written DARK to exercise the round-trip-from-non-default path).
- `themeMode_flowReEmits_afterOnSelectTheme` — two consecutive `onSelectTheme` calls, assert `vm.themeMode.value` reaches each in turn.
- `useWallpaperColors_initialState_emitsFalse_whenNoStoredValue` — fresh datastore boolean default (#89).
- `useWallpaperColors_initialState_mirrorsPersistedTrue` — pre-write `true`, construct VM, assert `vm.useWallpaperColors.value == true` (#89).
- `onToggleUseWallpaperColors_persistsAndFlowReEmits` — `false → true → false` walk, asserting both `prefs.useWallpaperColors.first()` (persistence) and `vm.useWallpaperColors.value` (re-emission) on each step (#89). Single test covers both AC clauses ("persists via the fake" + "flow re-emits after persistence") in one body — same compression rationale as #88's round-trip-plus-re-emit collapse.
- `archivedDiscussionCount_initialValue_isZero` — fresh VM, no source emission; collector launched + `advanceUntilIdle`; `value == 0` (#164).
- `archivedDiscussionCount_reflectsArchivedUnpromotedConversations` — emit three `archivedDiscussion(...)` fixtures; `value == 3` (#164).
- `archivedDiscussionCount_excludesPromotedArchivedConversations` — emit `[archivedDiscussion("disc-1"), archivedChannel("chan-1"), archivedChannel("chan-2")]`; `value == 1` (locks in the `!isPromoted` post-filter, #164).
- `archivedDiscussionCount_updates_whenConversationArchived` — emit `emptyList()` → 0, then emit `[archivedDiscussion]` → 1 (#164).
- `archivedDiscussionCount_updates_whenConversationUnarchived` — emit `[archivedDiscussion]` → 1, then emit `emptyList()` → 0 (#164).
- `archivedDiscussionCount_passesArchivedFilter` — `stubRepo(source, captureFiltersInto = captured)` records every `ConversationFilter` value the VM passes to `observeConversations`; assert `captured == [ConversationFilter.Archived]` (#164). The hand-rolled stub exists primarily for this assertion — the real `FakeConversationRepository` would require either internal inspection or contrived count differentials to verify the filter value.

Tests that read `vm.themeMode.value` / `vm.useWallpaperColors.value` / `vm.archivedDiscussionCount.value` `launch` a no-op collector and `advanceUntilIdle()` first to keep the `WhileSubscribed(5_000L)` upstream subscribed; otherwise `.value` stays at the `initialValue` literal (`ThemeMode.SYSTEM` / `false` / `0`) and the test asserts the wrong thing. Tests that read through `prefs.themeMode.first()` / `prefs.useWallpaperColors.first()` directly don't need the collector — they bypass the VM's `stateIn` projection.

## Related

- Spec: `docs/specs/architecture/87-settings-theme-picker-dialog.md`; `docs/specs/architecture/89-settings-use-wallpaper-colors-switch.md`; `docs/specs/architecture/164-settings-archived-discussion-live-count.md`
- Ticket notes: [`../codebase/87.md`](../codebase/87.md), [`../codebase/89.md`](../codebase/89.md), [`../codebase/164.md`](../codebase/164.md)
- Sibling VMs: [ChannelListViewModel](channel-list-viewmodel.md), [DiscussionListViewModel](discussion-list-viewmodel.md)
- Sibling consumer of the archived stream: [ArchivedDiscussionsViewModel](archived-discussions-screen.md) — primary-content surface (surfaces `Error(message)`); this VM is the supportive-metadata sibling that swallows the same upstream's errors via `.catch { emit(0) }`
- Backing preferences: [App preferences](app-preferences.md) — `themeMode` flow + `setThemeMode` setter; `useWallpaperColors` flow + `setUseWallpaperColors` setter (#89's write site)
- Backing repository: [Conversation repository](conversation-repository.md) — `observeConversations(ConversationFilter.Archived)` (#93)
- Consumer screen: [Settings screen](settings-screen.md) — Theme row + `ThemePickerDialog`; Use-wallpaper-colors switch row (#89); Storage row "N archived" supporting line (#164)
- Sibling root collectors: `MainActivity.setContent { … appPreferences.themeMode.collectAsStateWithLifecycle(...) ; appPreferences.useWallpaperColors.collectAsStateWithLifecycle(...) }` from #86 + #88 — drive `PyrycodeMobileTheme(darkTheme = …, dynamicColor = …)` from the same upstream flows this VM reads, so a single `setThemeMode` / `setUseWallpaperColors` write fans out to both the global theme and the Settings row. `archivedDiscussionCount` has no composition-root sibling — it's Settings-screen-local.
