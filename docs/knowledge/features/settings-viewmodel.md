# SettingsViewModel

Thin ViewModel for the Settings screen. Exposes persisted preferences (`themeMode`, `useWallpaperColors`) as hot `StateFlow`s and matching fire-and-forget setter callbacks. Introduced in #87 (Theme row); extended in #89 (Use-wallpaper-colors switch).

## What it does

- Reads `AppPreferences.themeMode` and exposes it as `themeMode: StateFlow<ThemeMode>`.
- Forwards `fun onSelectTheme(mode: ThemeMode)` into `AppPreferences.setThemeMode(mode)` via `viewModelScope.launch { … }`.
- Reads `AppPreferences.useWallpaperColors` and exposes it as `useWallpaperColors: StateFlow<Boolean>` (#89).
- Forwards `fun onToggleUseWallpaperColors(enabled: Boolean)` into `AppPreferences.setUseWallpaperColors(enabled)` via `viewModelScope.launch { … }` (#89).

Two parallel `(StateFlow<X>, fun onSelectX(...))` pairs. No sealed `SettingsState` / `SettingsEvent` envelope yet — the [Settings screen](settings-screen.md) has exactly two rows backed by real persisted state, and #87 set the rule that the MVI envelope arrives on the *third* real persisted preference. The next persisted Settings preference (notification sound, default model, default effort) is the trigger to lift.

## How it works

```kotlin
class SettingsViewModel(
    private val appPreferences: AppPreferences,
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

- **`stateIn(WhileSubscribed(5_000L), initial = <neutral>)`** — same idiom as [`ChannelListViewModel`](channel-list-viewmodel.md) and [`DiscussionListViewModel`](discussion-list-viewmodel.md). The 5 s grace period keeps the upstream subscription alive across config changes (rotation, dark-mode toggle) without holding it open after the screen leaves the back stack. Both flows pick the value the cold flow would emit first on a fresh DataStore as their `initialValue` (`ThemeMode.SYSTEM` for `themeMode`, `false` for `useWallpaperColors`) — same value, no first-frame flash. `STOP_TIMEOUT_MILLIS = 5_000L` is the single tuning knob for both projections.
- **Fire-and-forget writes.** Neither `onSelectTheme` nor `onToggleUseWallpaperColors` `await`s the `edit { … }` or surfaces a result. The persisted value re-emits through the upstream `Flow` after the setter returns; both this VM's projection and the root-level `MainActivity` collector (the one that drives `PyrycodeMobileTheme(darkTheme = …, dynamicColor = …)`) pick up the change. Same fire-and-forget shape as `MainActivity.kt`'s `setPairedServerExists` call from #12 — see [App preferences § Edge cases](app-preferences.md) for why no `Result`-returning write API exists today.

## Configuration / usage

Registered once in the Koin module:

```kotlin
// di/AppModule.kt
viewModel { SettingsViewModel(get()) }
```

Consumed once at the Settings NavHost destination:

```kotlin
composable(Routes.SETTINGS) {
    val vm = koinViewModel<SettingsViewModel>()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val useWallpaperColors by vm.useWallpaperColors.collectAsStateWithLifecycle()
    SettingsScreen(
        themeMode = themeMode,
        useWallpaperColors = useWallpaperColors,
        onSelectTheme = vm::onSelectTheme,
        onToggleUseWallpaperColors = vm::onToggleUseWallpaperColors,
        onBack = { navController.popBackStack() },
        onOpenLicense = { navController.navigate(Routes.LICENSE) },
        onOpenArchivedDiscussions = { navController.navigate(Routes.ARCHIVED_DISCUSSIONS) },
    )
}
```

Note the no-arg `collectAsStateWithLifecycle()` calls — `vm.themeMode` and `vm.useWallpaperColors` are already `StateFlow`s, so the overload picks up `StateFlow.value` as initial. Compare with the root-level `appPreferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)` / `appPreferences.useWallpaperColors.collectAsStateWithLifecycle(initialValue = false)` in `MainActivity`'s `setContent` block, which collect the *cold* flows and must supply same-shaped neutral defaults. The composition-root collectors are independent of this VM's projections — DataStore broadcasts to every subscriber, so one `setUseWallpaperColors(...)` write reaches both the global theme repaint and this VM's `useWallpaperColors.value` from a single upstream emission.

## Edge cases / limitations

- **No write-failure surface.** `setThemeMode` can in principle throw `IOException` from DataStore; this VM does not catch or surface that. If a write ever fails in the field, the persisted value stays at its prior value, the upstream re-emits the prior value, and the dialog closes either way. Adding a snackbar would mean adding a `Result`-returning write API on `AppPreferences` — out of scope until an actual failure mode is observed (see [evidence-based fix selection](app-preferences.md)).
- **Two-key surface.** When the *third* persisted Settings preference lands (notification sound, default model, default effort — see [Settings screen](settings-screen.md) for the full row inventory), this is the trigger to lift the VM to a sealed `SettingsState` with per-field reducers. Until then, growing more `StateFlow<X>` + `fun onSelectX(...)` pairs is cheaper than the MVI envelope. #89 exercised the "grow by one pair" half of the rule from #87 — the count is now two; one more, and the envelope earns its keep.

## Testing

`app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` — nine methods. Uses the `AppPreferencesTest` rig (real `PreferenceDataStoreFactory` over `TemporaryFolder` + real `AppPreferences`, no fake) plus `Dispatchers.setMain(UnconfinedTestDispatcher())` + `runTest(dispatcher) { … advanceUntilIdle() … }` from [`ChannelListViewModelTest`](channel-list-viewmodel.md).

Coverage:

- `initialState_emitsSystem_whenNoStoredValue` — fresh datastore.
- `initialState_mirrorsPersistedValue` — pre-write `DARK`, construct VM, assert `vm.themeMode.value == DARK`.
- `onSelectTheme_persistsLight` / `_persistsDark` / `_persistsSystem` — round-trip each `ThemeMode` (the SYSTEM case starts from a pre-written DARK to exercise the round-trip-from-non-default path).
- `themeMode_flowReEmits_afterOnSelectTheme` — two consecutive `onSelectTheme` calls, assert `vm.themeMode.value` reaches each in turn.
- `useWallpaperColors_initialState_emitsFalse_whenNoStoredValue` — fresh datastore boolean default (#89).
- `useWallpaperColors_initialState_mirrorsPersistedTrue` — pre-write `true`, construct VM, assert `vm.useWallpaperColors.value == true` (#89).
- `onToggleUseWallpaperColors_persistsAndFlowReEmits` — `false → true → false` walk, asserting both `prefs.useWallpaperColors.first()` (persistence) and `vm.useWallpaperColors.value` (re-emission) on each step (#89). Single test covers both AC clauses ("persists via the fake" + "flow re-emits after persistence") in one body — same compression rationale as #88's round-trip-plus-re-emit collapse.

Tests that read `vm.themeMode.value` or `vm.useWallpaperColors.value` `launch` a no-op collector and `advanceUntilIdle()` first to keep the `WhileSubscribed(5_000L)` upstream subscribed; otherwise `.value` stays at the `initialValue` literal (`ThemeMode.SYSTEM` / `false`) and the test asserts the wrong thing. Tests that read through `prefs.themeMode.first()` / `prefs.useWallpaperColors.first()` directly don't need the collector — they bypass the VM's `stateIn` projection.

## Related

- Spec: `docs/specs/architecture/87-settings-theme-picker-dialog.md`; `docs/specs/architecture/89-settings-use-wallpaper-colors-switch.md`
- Ticket notes: [`../codebase/87.md`](../codebase/87.md), [`../codebase/89.md`](../codebase/89.md)
- Sibling VMs: [ChannelListViewModel](channel-list-viewmodel.md), [DiscussionListViewModel](discussion-list-viewmodel.md)
- Backing preferences: [App preferences](app-preferences.md) — `themeMode` flow + `setThemeMode` setter; `useWallpaperColors` flow + `setUseWallpaperColors` setter (#89's write site)
- Consumer screen: [Settings screen](settings-screen.md) — Theme row + `ThemePickerDialog`; Use-wallpaper-colors switch row (#89)
- Sibling root collectors: `MainActivity.setContent { … appPreferences.themeMode.collectAsStateWithLifecycle(...) ; appPreferences.useWallpaperColors.collectAsStateWithLifecycle(...) }` from #86 + #88 — drive `PyrycodeMobileTheme(darkTheme = …, dynamicColor = …)` from the same upstream flows this VM reads, so a single `setThemeMode` / `setUseWallpaperColors` write fans out to both the global theme and the Settings row.
