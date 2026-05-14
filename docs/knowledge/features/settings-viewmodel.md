# SettingsViewModel

Thin ViewModel for the Settings screen. Exposes the persisted `ThemeMode` as a hot `StateFlow` and a single typed callback for the picker dialog's confirm. Introduced in #87.

## What it does

- Reads `AppPreferences.themeMode` and exposes it as `themeMode: StateFlow<ThemeMode>`.
- Forwards `fun onSelectTheme(mode: ThemeMode)` into `AppPreferences.setThemeMode(mode)` via `viewModelScope.launch { … }`.

That's the entire surface. No sealed `SettingsState` / `SettingsEvent` envelope yet — the [Settings screen](settings-screen.md) still has exactly one row backed by real persisted state, so a single hot `StateFlow<X>` + a single `fun onSelectX(...)` is all the screen needs.

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

    fun onSelectTheme(mode: ThemeMode) {
        viewModelScope.launch { appPreferences.setThemeMode(mode) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
```

- **`stateIn(WhileSubscribed(5_000L), initial = SYSTEM)`** — same idiom as [`ChannelListViewModel`](channel-list-viewmodel.md) and [`DiscussionListViewModel`](discussion-list-viewmodel.md). The 5 s grace period keeps the upstream subscription alive across config changes (rotation, dark-mode toggle) without holding it open after the screen leaves the back stack. `ThemeMode.SYSTEM` is the right `initialValue` because it's also what [`AppPreferences`](app-preferences.md)'s cold flow emits first on a fresh DataStore — same value, no first-frame mis-themed flash.
- **Fire-and-forget write.** `onSelectTheme` doesn't `await` the `edit { … }` and doesn't surface a result. The persisted value re-emits through the upstream `Flow` after `setThemeMode` returns; both this VM's `themeMode` and the root-level `MainActivity` collector pick up the change. Same fire-and-forget shape as `MainActivity.kt`'s `setPairedServerExists` call from #12 — see [App preferences § Edge cases](app-preferences.md) for why no `Result`-returning write API exists today.

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
    SettingsScreen(
        themeMode = themeMode,
        onSelectTheme = vm::onSelectTheme,
        onBack = { navController.popBackStack() },
        onOpenLicense = { navController.navigate(Routes.LICENSE) },
    )
}
```

Note the no-arg `collectAsStateWithLifecycle()` — `vm.themeMode` is already a `StateFlow`, so the overload picks up `StateFlow.value` as initial. Compare with the root-level `appPreferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)` in `MainActivity`'s `setContent` block, which collects the *cold* flow and must supply a same-shaped neutral default.

## Edge cases / limitations

- **No write-failure surface.** `setThemeMode` can in principle throw `IOException` from DataStore; this VM does not catch or surface that. If a write ever fails in the field, the persisted value stays at its prior value, the upstream re-emits the prior value, and the dialog closes either way. Adding a snackbar would mean adding a `Result`-returning write API on `AppPreferences` — out of scope until an actual failure mode is observed (see [evidence-based fix selection](app-preferences.md)).
- **Single-key surface.** When the second persisted Settings preference lands (notification sound, default model, default effort — see [Settings screen](settings-screen.md) for the full row inventory), this is the trigger to lift the VM to a sealed `SettingsState` with per-field reducers. Until then, growing more `StateFlow<X>` + `fun onSelectX(...)` pairs is cheaper than the MVI envelope.

## Testing

`app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` — six methods. Uses the `AppPreferencesTest` rig (real `PreferenceDataStoreFactory` over `TemporaryFolder` + real `AppPreferences`, no fake) plus `Dispatchers.setMain(UnconfinedTestDispatcher())` + `runTest(dispatcher) { … advanceUntilIdle() … }` from [`ChannelListViewModelTest`](channel-list-viewmodel.md).

Coverage:

- `initialState_emitsSystem_whenNoStoredValue` — fresh datastore.
- `initialState_mirrorsPersistedValue` — pre-write `DARK`, construct VM, assert `vm.themeMode.value == DARK`.
- `onSelectTheme_persistsLight` / `_persistsDark` / `_persistsSystem` — round-trip each `ThemeMode` (the SYSTEM case starts from a pre-written DARK to exercise the round-trip-from-non-default path).
- `themeMode_flowReEmits_afterOnSelectTheme` — two consecutive `onSelectTheme` calls, assert `vm.themeMode.value` reaches each in turn.

Tests that read `vm.themeMode.value` `launch` a no-op collector and `advanceUntilIdle()` first to keep the `WhileSubscribed(5_000L)` upstream subscribed; otherwise `.value` stays at the `initialValue = ThemeMode.SYSTEM` literal and the test asserts the wrong thing. Tests that read through `prefs.themeMode.first()` directly don't need the collector — they bypass the VM's `stateIn` projection.

## Related

- Spec: `docs/specs/architecture/87-settings-theme-picker-dialog.md`
- Ticket notes: [`../codebase/87.md`](../codebase/87.md)
- Sibling VMs: [ChannelListViewModel](channel-list-viewmodel.md), [DiscussionListViewModel](discussion-list-viewmodel.md)
- Backing preference: [App preferences](app-preferences.md) — `themeMode` flow + `setThemeMode` setter
- Consumer screen: [Settings screen](settings-screen.md) — Theme row + `ThemePickerDialog`
- Sibling root collector: `MainActivity.setContent { … appPreferences.themeMode.collectAsStateWithLifecycle(...) }` from #86 — drives `PyrycodeMobileTheme(darkTheme = …)` from the same upstream flow this VM reads, so a single `setThemeMode` write fans out to both surfaces.
