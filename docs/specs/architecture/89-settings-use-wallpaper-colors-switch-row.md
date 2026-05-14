# Spec — #89 feat(ui): Settings — "Use wallpaper colors" switch row

Issue: [#89](https://github.com/pyrycode/pyrycode-mobile/issues/89)
Depends on (both merged):
- #87 — introduced `SettingsViewModel` (currently exposes `themeMode` + `onSelectTheme`).
- #88 — added `AppPreferences.useWallpaperColors: Flow<Boolean>` + `suspend fun setUseWallpaperColors(...)`, and wired `MainActivity.setContent { … }` to collect it and pass `dynamicColor = useWallpaperColors` into `PyrycodeMobileTheme`.

## Context

The Settings screen already contains a placeholder switch row labelled "Use Material You dynamic color" backed by `var materialYou by remember { mutableStateOf(true) }` (visible in `SettingsScreen.kt:73, 117-122`). It is local-only — toggling it changes the on-screen switch but does not touch DataStore, does not affect the app's `ColorScheme`, and is not gated by SDK level. This ticket replaces that stub with the real wiring:

1. Extend `SettingsViewModel` with a `useWallpaperColors: StateFlow<Boolean>` reading `AppPreferences.useWallpaperColors`, and an `onToggleUseWallpaperColors(enabled: Boolean)` event that writes through `AppPreferences.setUseWallpaperColors`.
2. Replace the placeholder row in `SettingsScreen.kt` with a VM-driven, SDK-gated switch row. On Android < S, the switch is rendered disabled with the AC's helper text; on Android >= S, the switch reflects and writes the persisted preference.
3. Pass the new state + event from `MainActivity`'s settings route into `SettingsScreen`.

The composition-root `dynamicColor` collection from #88 already re-renders `PyrycodeMobileTheme` reactively, so a single write fans out to the live theme without additional plumbing — AC #2's "updates immediately without a process restart" is satisfied by infrastructure already in place.

**Label divergence from Figma.** The Figma node `17-2` shows the row label as "Use Material You dynamic color" (the older copy that ships in the placeholder today). The ticket body explicitly specifies the new label as **"Use wallpaper colors"** — the user-facing name has been clarified since the Figma was drawn. **Follow the ticket body**, not the Figma string. Visual layout (row height, leading body-large label, trailing switch, no subtitle) follows the Figma exactly; only the headline text changes.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

Settings screen — the row directly beneath the Theme row in the Appearance section. M3 `ListItem` shape: `body-large` headline ("Use wallpaper colors") in `Schemes/on-surface`, no subtitle in the enabled state, trailing M3 `Switch` (Material You filled track when checked). In the disabled (pre-S) state the row gains a `body-small` supporting line "Requires Android 12 or newer" in `Schemes/on-surface-variant` and the switch renders in the standard M3 disabled colors. Row uses the existing `SettingsRow` composable in the same file — identical 16dp horizontal padding, 10dp vertical, surface container — so the spacing matches the Theme row above and the Default-YOLO switch row below.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:73,117-122` — the existing placeholder: `var materialYou by remember { mutableStateOf(true) }` and the `SettingsRow(headline = "Use Material You dynamic color", trailing = { Switch(...) })` block. This is the exact site to rewire. Note the two other live local-state switches in the same composable (`defaultYolo`, `pushNotifications`); do NOT touch those — only the `materialYou` row is in scope.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:48-55` — current `SettingsScreen(...)` signature. Adds two new parameters per § Design § SettingsScreen signature; the two existing `@Preview` composables at the bottom of the file must pass explicit values.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:229-243` — `SettingsRow(headline, supporting, trailing, onClick)` private composable. The `supporting: String?` parameter is the slot for the "Requires Android 12 or newer" helper text on the disabled path — no changes to `SettingsRow` itself are needed.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` — current VM, complete. Extend with one `StateFlow<Boolean>` + one fire-and-forget event method, mirroring the existing `themeMode` / `onSelectTheme` pair line-for-line.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt:42-119` — the existing test rig: `Dispatchers.setMain(UnconfinedTestDispatcher())`, `TemporaryFolder`-backed `PreferenceDataStoreFactory`, real `AppPreferences`, `runTest(dispatcher) { … }` with `advanceUntilIdle()`. New tests slot in next to the theme-mode tests using the same shape; the existing theme tests are the closest analogue (boolean instead of enum is the only delta).
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:31-42` — `useWallpaperColors: Flow<Boolean>` + `setUseWallpaperColors`. Default-`false` when key absent (the AC's "default is OFF" behavior; do not override at the VM layer).
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:200-210` — the settings route. Wire the new state + event into the `SettingsScreen(...)` call. The root-level `useWallpaperColors` collection at lines 62-63 stays untouched — it's the upstream collector that gives AC #2's live re-theme for free.
- `app/src/main/res/values/strings.xml` — string catalog; add the row label and the helper-text strings here (see § Design § String resources).
- `CLAUDE.md` — Min SDK 33 (= API 33 > S = 31). The disabled-on-< S branch is therefore unreachable at runtime today, but the ticket explicitly asks for it. Implement it as specified; don't argue it away.

## Design

### Package layout

```
ui/settings/
├── SettingsScreen.kt          (modified — placeholder row replaced, signature gains 2 params)
└── SettingsViewModel.kt       (modified — adds one StateFlow + one event)

test/.../ui/settings/
└── SettingsViewModelTest.kt   (modified — 3 new tests)

MainActivity.kt                (modified — wires 2 new params through Settings route)
res/values/strings.xml         (modified — 2 new strings)
```

No new files. No new types. The row composable does **not** get extracted — it lives inline next to its siblings in `SettingsScreen.kt`, matching the existing convention where every section's rows are inline `SettingsRow(...)` calls.

### `SettingsViewModel` additions

Add alongside the existing `themeMode` / `onSelectTheme` pair:

```kotlin
val useWallpaperColors: StateFlow<Boolean> = /* stateIn off appPreferences.useWallpaperColors,
                                                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                                                initialValue = false */

fun onToggleUseWallpaperColors(enabled: Boolean) {
    viewModelScope.launch { appPreferences.setUseWallpaperColors(enabled) }
}
```

`initialValue = false` matches `AppPreferences.useWallpaperColors`'s default-when-absent, so there is no one-frame flash. `WhileSubscribed(5_000L)` reuses the existing `STOP_TIMEOUT_MILLIS` constant — no new tuning knob.

The event method name is `onToggleUseWallpaperColors` (parallel to `onSelectTheme`). Method-name nit: `onUseWallpaperColorsChanged` would also be fine; pick the former for grammatical parallelism with `onSelectTheme` and keep it consistent across the file.

### `SettingsScreen` signature

Add two parameters; keep the existing five:

```kotlin
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    useWallpaperColors: Boolean,
    onSelectTheme: (ThemeMode) -> Unit,
    onToggleUseWallpaperColors: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenLicense: () -> Unit,
    onOpenArchivedDiscussions: () -> Unit,
    modifier: Modifier = Modifier,
) { … }
```

Parameter ordering: state values together (`themeMode`, `useWallpaperColors`), then event callbacks together (`onSelectTheme`, `onToggleUseWallpaperColors`), then nav callbacks, then `modifier`. Matches how the Compose stdlib orders signatures and groups related concerns visually.

### Row replacement

Inside `SettingsScreen`'s `Scaffold` content lambda, locate the Appearance section and replace the placeholder block (current `var materialYou by remember { mutableStateOf(true) }` plus its `SettingsRow(headline = "Use Material You dynamic color", trailing = { Switch(checked = materialYou, onCheckedChange = { materialYou = it }) })`) with:

1. **Remove** `var materialYou by remember { mutableStateOf(true) }` — no longer needed.
2. **Compute** SDK gate at composition build time (single read, no remember needed — it's a constant for the process lifetime): `val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`.
3. **Replace** the row with the wired-up version:

```kotlin
SettingsRow(
    headline = stringResource(R.string.settings_use_wallpaper_colors),
    supporting =
        if (!dynamicColorSupported) {
            stringResource(R.string.settings_use_wallpaper_colors_unsupported)
        } else {
            null
        },
    trailing = {
        Switch(
            checked = useWallpaperColors,
            onCheckedChange = onToggleUseWallpaperColors,
            enabled = dynamicColorSupported,
        )
    },
)
```

Notes:

- `Switch.enabled = false` plus a `null` `onCheckedChange` would both work to make the tap a no-op; passing the callback unconditionally and gating purely on `enabled` is simpler and matches the M3 idiom — when `enabled = false`, the Switch ignores taps and renders disabled colors. No need to short-circuit the callback.
- No row-level `onClick` — the existing switch rows on this screen (`defaultYolo`, `pushNotifications`) don't make the whole row tappable, and the Figma shows the same pattern. Stay consistent; don't introduce a row-tap-to-toggle pattern here.
- Helper text is `body-small` on `Schemes/on-surface-variant` automatically — `SettingsRow` routes `supporting` into `ListItem.supportingContent`, which applies the M3 supporting-line style.

### `MainActivity` rewiring

Update the settings route (currently lines 200-210). Add the second state collection and pass both new params to `SettingsScreen`:

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

**Do not touch** lines 58-70 — the root-level `appPreferences.useWallpaperColors.collectAsStateWithLifecycle(...)` and the `PyrycodeMobileTheme(darkTheme = …, dynamicColor = useWallpaperColors)` call. That's #88's wiring; it's what makes AC #2's live re-theme work. The SettingsViewModel's collector is an independent second subscriber to the same upstream DataStore flow — DataStore broadcasts to all collectors, so one write fans out to both.

### String resources

Add to `app/src/main/res/values/strings.xml`:

- `settings_use_wallpaper_colors` → "Use wallpaper colors"
- `settings_use_wallpaper_colors_unsupported` → "Requires Android 12 or newer"

The existing placeholder uses a hard-coded `"Use Material You dynamic color"` string. The replacement extracts the new string to `strings.xml` (matching the `archived_discussions_settings_row` precedent above). The other switch rows on this screen still use hard-coded strings — extracting them is out of scope for this ticket; stay focused.

### Previews

The two `@Preview` composables at the bottom of `SettingsScreen.kt` (`SettingsScreenLightPreview`, `SettingsScreenDarkPreview`) currently pass `themeMode = ThemeMode.SYSTEM, onSelectTheme = {}`. Add the two new params:

- `useWallpaperColors = false` (matches default, shows the enabled-but-unchecked state on a build with `Build.VERSION.SDK_INT >= S`; preview tooling runs at whatever the IDE's current preview SDK is, typically the project's `compileSdk` — so the enabled state will render).
- `onToggleUseWallpaperColors = {}`.

A third preview specifically for the disabled-pre-S state is **out of scope** — Compose `@Preview` doesn't easily simulate a lower runtime SDK level, and the disabled path is structurally simple (M3 default-disabled colors + helper-text supporting line). The developer can eyeball it from a one-off temporary `dynamicColorSupported = false` flip if curious, but no permanent preview is needed.

### Why no sealed `SettingsState` / `SettingsEvent` yet

Same rationale as #87's spec: the ticket adds one more pair of (state, event) entries; the screen still has nothing else stateful at the VM layer. Introducing a sealed surface for two fields would be premature abstraction. When a third real preference lands (e.g., #90 or beyond), revisit.

## State + concurrency model

- **VM flow.** `useWallpaperColors: StateFlow<Boolean>` backed by `appPreferences.useWallpaperColors.stateIn(viewModelScope, WhileSubscribed(5_000L), false)`. Identical lifetime + cancellation profile to `themeMode`. The 5-second stop timeout means rotation / brief back-stack hops don't churn the upstream Flow; leaving Settings entirely cancels it.
- **Setter dispatch.** `onToggleUseWallpaperColors` does `viewModelScope.launch { appPreferences.setUseWallpaperColors(enabled) }`. Fire-and-forget. DataStore handles its own IO dispatcher internally; no explicit `Dispatchers.IO` switch needed. No result is returned to the UI — the same flow re-emits with the persisted value once `edit { … }` completes, and the switch's `checked` state re-renders from that emission.
- **Two upstream collectors.** `MainActivity.setContent { … }` (composition-root, drives `PyrycodeMobileTheme(dynamicColor = …)`) and `SettingsViewModel` (drives the switch's `checked` state) both subscribe to the same `appPreferences.useWallpaperColors` Flow. A write triggers both. This is the load-bearing fact behind AC #2 — already true after #88, this ticket adds the second subscriber on the VM side.
- **No local UI state.** The previous `var materialYou by remember { mutableStateOf(true) }` is deleted outright. The switch's `checked` and `onCheckedChange` come straight from the VM-backed state + event, with no intermediate `remember`. This is correct: the persisted value is the single source of truth, and any divergence between local state and persisted state would surface as a one-frame visual glitch on toggle.
- **SDK gate.** `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` evaluated once at composition build. No `remember` — it's a process-lifetime constant; reading it on every recomposition is fine.

## Error handling

None at this layer. `appPreferences.useWallpaperColors` cannot throw on read (DataStore's projected `Flow<Boolean>` swallows IO via `dataStore.data` re-emission; on first read it materializes the empty file). `setUseWallpaperColors` can in principle throw `IOException` from `dataStore.edit`, but #88's spec already documented this is unhandled by precedent (see `setThemeMode`, `setPairedServerExists`) and there is no observed failure mode — [[Evidence-Based Fix Selection]] says don't add a defense. If the write fails the persisted value stays at its prior value and the next flow emission reflects that; the switch flips back. Acceptable.

If a write failure ever becomes a real issue, add a `Result`-returning write API + snackbar surface — out of scope here.

## Testing strategy

### Unit tests (JVM, `./gradlew test`)

Extend `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` with three new tests, reusing the existing `TemporaryFolder` rig, the `newDataStore()` helper, and the `Dispatchers.setMain(UnconfinedTestDispatcher())` setup. New tests slot in after the existing `themeMode_flowReEmits_afterOnSelectTheme` test. Each test instantiates a fresh `AppPreferences(newDataStore())` and a `SettingsViewModel(prefs)` — the same pattern as every existing test in the file.

Scenarios (bullet points; developer translates to JUnit `@Test fun`s in the file's style):

- **`useWallpaperColors_initialState_emitsFalse_whenNoStoredValue`** — fresh datastore, no writes. Launch a `vm.useWallpaperColors.collect { }` collector, `advanceUntilIdle()`, assert `vm.useWallpaperColors.value == false`. Mirrors `initialState_emitsSystem_whenNoStoredValue`.
- **`useWallpaperColors_initialState_mirrorsPersistedTrue`** — pre-write `prefs.setUseWallpaperColors(true)` before constructing the VM, `advanceUntilIdle()`, then construct the VM, launch a collector, `advanceUntilIdle()`, assert `vm.useWallpaperColors.value == true`. Mirrors `initialState_mirrorsPersistedValue`.
- **`onToggleUseWallpaperColors_persistsAndFlowReEmits`** — launch a collector on `vm.useWallpaperColors`, `advanceUntilIdle()`. Call `vm.onToggleUseWallpaperColors(true)`, `advanceUntilIdle()`, assert `prefs.useWallpaperColors.first() == true` AND `vm.useWallpaperColors.value == true`. Then call `vm.onToggleUseWallpaperColors(false)`, `advanceUntilIdle()`, assert both `prefs.useWallpaperColors.first() == false` AND `vm.useWallpaperColors.value == false`. This single test satisfies the AC's "toggling persists via the fake" and "exposed flow re-emits after persistence" jointly — splitting into two near-identical tests would just add noise. Mirrors `themeMode_flowReEmits_afterOnSelectTheme` plus a persistence assertion.

No fake `AppPreferences` is introduced — the existing tests run the real `AppPreferences` against a real `PreferenceDataStoreFactory`, and that's what these tests do too. The full round-trip is the contract under test; faking the layer that we're testing the integration with would be backwards.

### Compose UI tests

None. The repo has no Compose UI tests for Settings, the row is a thin M3 wrapper, and visual fidelity is verified by the existing `@Preview` composables plus a manual smoke test on a device/emulator (toggle the switch, observe the theme change). The VM tests carry the contract that matters.

The SDK-gate branch (Android < S → disabled + helper text) is unreachable at runtime today (Min SDK 33 > S), so a test that fakes a lower SDK level would be testing dead defensive code. Skip it.

### Instrumented tests

None.

## Acceptance criteria mapping

- **AC #1** ("Use wallpaper colors" row beneath Theme row, label + optional subtitle + trailing M3 Switch) → row inserted at the placeholder's location in `SettingsScreen.kt`, headline from `R.string.settings_use_wallpaper_colors`, subtitle from `R.string.settings_use_wallpaper_colors_unsupported` only on the disabled-pre-S branch, `Switch` in the trailing slot.
- **AC #2** (Android 12+: enabled, mirrors `AppPreferences.useWallpaperColors`, persists via setter, theme updates live) → `dynamicColorSupported = true` path renders `enabled = true`; `checked = useWallpaperColors` mirrors the VM-backed state which is `stateIn` off the DataStore flow; `onCheckedChange = onToggleUseWallpaperColors` writes through; root-level collector in `MainActivity` re-renders `PyrycodeMobileTheme(dynamicColor = …)` on the same flow emission.
- **AC #3** (Android < 12: switch disabled, helper text, tap is no-op) → `dynamicColorSupported = false` path renders `enabled = false` (M3 disabled switch colors, no tap handling) and supplies the helper-text string to `supporting`. The preference value is irrelevant on this branch — `useWallpaperColors` is still read and bound, but with `enabled = false` the switch never invokes `onCheckedChange`.
- **AC #4** (`SettingsViewModel` gains `useWallpaperColors: StateFlow<Boolean>` + toggle event; composable stays thin) → see § Design § SettingsViewModel additions. The composable receives state + event as parameters; no extra logic in `SettingsScreen`.
- **AC #5** (`SettingsViewModelTest` covers initial state, toggle persistence, flow re-emission) → see § Testing strategy. Three new tests; the third combines the persistence + re-emission assertions because the AC's two conditions share the same observation.

## Open questions

None. The row label is set by the ticket body (not the Figma — see § Context). The lack of a row-level `onClick` mirrors the existing switch rows. The disabled-on-< S branch is structurally unreachable today but implemented per AC. No sealed-state surface yet — premature for two fields.
