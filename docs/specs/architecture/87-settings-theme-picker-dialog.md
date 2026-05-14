# Spec: Settings theme picker dialog (System / Light / Dark)

Issue: [#87](https://github.com/pyrycode/pyrycode-mobile/issues/87)
Depends on: #86 (`ThemeMode` enum, `theme_mode` DataStore key, root-level theme wiring — merged in `2cd2c85`)

## Context

The Settings screen renders a Theme row whose subtitle already reflects the persisted `ThemeMode`, but `onClick = {}`. This spec wires the row to a Material 3 single-choice dialog ("System default" / "Light" / "Dark") and hoists the read + write of `ThemeMode` from the Settings route composable into a new `SettingsViewModel`. The picker writes through `AppPreferences.setThemeMode(...)`; the root-level theme wiring in `MainActivity` already re-renders `PyrycodeMobileTheme` reactively, so a single write fans out to both surfaces without further coordination.

Material You dynamic color is explicitly out of scope.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The Theme row that opens this dialog lives in the Settings Screen node (17-2) — a `ListItem` under the "Appearance" section with headline "Theme", a `body-small` supporting line showing the current mode label, and a trailing chevron. The picker dialog itself has no Figma sub-node; the ticket explicitly authorises M3 single-choice dialog defaults. Use `AlertDialog` with three `Row { RadioButton + Text }` entries and confirm/dismiss buttons styled from the standard M3 dialog tokens (`Schemes/surface-container-high` container, `Schemes/on-surface` title text, `Schemes/primary` confirm button text).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:11-35` — `themeMode: Flow<ThemeMode>` and `suspend fun setThemeMode(mode)`; the entire data-side API this slice consumes.
- `app/src/main/java/de/pyryco/mobile/data/preferences/ThemeMode.kt:3` — the three-value enum the picker selects between.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:52-87` — root-level `appPreferences.themeMode.collectAsStateWithLifecycle(...)` that drives `PyrycodeMobileTheme(darkTheme = …)`. **Do not touch this.** It's the upstream surface that re-renders the app theme when the user picks a new mode; leaving it intact is what gives AC #3 for free.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:192-201` — current `composable(Routes.SETTINGS) { … }` block that this slice rewires. Drop the local `appPreferences = koinInject<AppPreferences>()` + `themeMode = …` collection; replace with `vm = koinViewModel<SettingsViewModel>()` + `state = vm.themeMode.collectAsStateWithLifecycle()`.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:48-53` — current `SettingsScreen(...)` signature with the `themeMode: ThemeMode = ThemeMode.SYSTEM` parameter that this slice deletes; replace with the new VM-driven signature (see § Design).
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:97-102` — the Theme row whose `onClick = {}` becomes the dialog open trigger.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:266-271` — the private `ThemeMode.label()` extension whose visibility this slice broadens (see § Design § Label helper).
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:17-28` — the Koin module; add a `viewModel { SettingsViewModel(get()) }` line alongside the existing `ChannelListViewModel` / `DiscussionListViewModel` registrations.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:43-99` — canonical VM shape in this repo: constructor-injected dependency, `StateFlow` exposed via `stateIn(scope, SharingStarted.WhileSubscribed(5_000L), initial)`, `viewModelScope.launch` for write paths. `SettingsViewModel` follows the same idiom in miniature.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt:30-49` — `Dispatchers.setMain(UnconfinedTestDispatcher())` + `runTest { … }` template plus `advanceUntilIdle()` flow rhythm — copy the setup for `SettingsViewModelTest`.
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt:21-78` — the `TemporaryFolder` + `PreferenceDataStoreFactory` + real `AppPreferences` test rig. `SettingsViewModelTest` will reuse this rig verbatim and instantiate the VM around the real `AppPreferences` (no AppPreferences fake — see § Testing strategy).

## Design

### Package layout

```
ui/settings/
├── SettingsScreen.kt          (modified — drop themeMode param, add VM-driven state, dialog open-state, row tap handler)
├── ThemePickerDialog.kt       (new — private-ish composable for the dialog body)
├── SettingsViewModel.kt       (new)
└── LicenseScreen.kt           (untouched)
```

The dialog gets its own file because `SettingsScreen.kt` is already ~290 lines; adding a ~40-line dialog composable plus its preview keeps the screen file focused on the row list. Same package as `SettingsScreen`, so visibility stays minimal — the dialog composable can be marked `internal` to the module without leaking into a wider surface.

### `SettingsViewModel`

```kotlin
class SettingsViewModel(
    private val appPreferences: AppPreferences,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> = /* stateIn off appPreferences.themeMode,
                                              SharingStarted.WhileSubscribed(5_000L),
                                              initialValue = ThemeMode.SYSTEM */

    fun onSelectTheme(mode: ThemeMode) {
        viewModelScope.launch { appPreferences.setThemeMode(mode) }
    }
}
```

Mirrors `ChannelListViewModel`'s `stateIn(scope, SharingStarted.WhileSubscribed(5_000L), initial)` shape exactly. No sealed `SettingsState` / `SettingsEvent` — the ticket explicitly defers those until more rows leave the placeholder phase. `viewModelScope` dispatch is fine for writes (`AppPreferences.setThemeMode` is a single suspending DataStore `edit`; the DataStore library handles thread-safety internally).

**Why `WhileSubscribed(5_000L)` and not `Eagerly`:** keeps the upstream flow active across config changes (recomposition triggered by rotation, dark-mode toggle) but cancels it when the Settings screen leaves the back stack. Matches the rest of the codebase; no reason to diverge here.

### `SettingsScreen` rewiring

New signature:

```kotlin
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    onOpenLicense: () -> Unit,
    modifier: Modifier = Modifier,
) { … }
```

The composable stays stateless w.r.t. the persisted theme — `themeMode` arrives as a parameter, the selection callback goes back to the VM. The dialog's **open** state is local UI state (`var showThemeDialog by remember { mutableStateOf(false) }`) hoisted no further than this composable, because (a) it doesn't survive process death meaningfully — re-entering Settings reopens at closed, which is the expected UX, and (b) hoisting it into the VM would require a sealed event surface this slice intentionally avoids.

The Theme row's `onClick` becomes `{ showThemeDialog = true }`. When `showThemeDialog` is true, render `ThemePickerDialog(...)` (see below).

Default-parameter removal: the existing `themeMode: ThemeMode = ThemeMode.SYSTEM` default goes away. The two `@Preview` composables at the bottom of the file (`SettingsScreenLightPreview` / `SettingsScreenDarkPreview`) must pass explicit values: `themeMode = ThemeMode.SYSTEM, onSelectTheme = {}`.

### `ThemePickerDialog` composable

```kotlin
@Composable
internal fun ThemePickerDialog(
    selected: ThemeMode,
    onConfirm: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) { … }
```

**Dialog shape — picked: M3 `AlertDialog` with OK / Cancel buttons.** The radio rows track a local `var pending by remember(selected) { mutableStateOf(selected) }` that resets when the parent reopens the dialog with a (possibly changed) `selected`. Confirm button calls `onConfirm(pending)` then the parent flips `showThemeDialog = false`; Cancel and `onDismissRequest` both call `onDismiss()` (which the parent maps to the same flip), so the unconfirmed `pending` is discarded. This matches AC #2's "cancelling (or dismissing without confirming) leaves the persisted value unchanged" cleanly.

Why OK/Cancel over tap-to-commit-and-dismiss: the AC's language ("confirming" / "cancelling") implies an intermediate review step, and an accidental tap shouldn't immediately repaint the entire app's theme. Three options is small enough that the extra button row isn't fatiguing.

Body structure (inside `AlertDialog`'s `text` slot):
- A `Column` of three rows.
- Each row: `Modifier.fillMaxWidth().selectable(selected = pending == mode, role = Role.RadioButton, onClick = { pending = mode }).padding(...)`, containing `RadioButton(selected = pending == mode, onClick = null)` + `Spacer(8.dp)` + `Text(mode.label(), style = MaterialTheme.typography.bodyLarge)`.
- Use `Modifier.selectableGroup()` on the column so TalkBack groups the three options correctly.

Title: `Text(stringResource(R.string.settings_theme_dialog_title))`.

### Label helper

Promote `ThemeMode.label()` from the current `private` at `SettingsScreen.kt:266` to `internal` (visibility unchanged at runtime — the function is still file-top-level and uses the same body), so `ThemePickerDialog.kt` in the same package can import it. Do **not** move it into `data/preferences/` — these are UI strings, not data-layer concepts, and `data/` is the Compose-Multiplatform walk-back boundary per `CLAUDE.md`.

A single function continues to define the three labels, used by both the row subtitle and each radio's text. No duplication.

### Koin registration

`AppModule.kt`: add one line:

```kotlin
viewModel { SettingsViewModel(get()) }
```

`get()` resolves the already-registered `single { AppPreferences(get()) }`.

### `MainActivity` rewiring

Replace the current `composable(Routes.SETTINGS) { … }` block (lines 192–201) with:

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

The `import org.koin.compose.koinInject` line at the top of `MainActivity.kt` stays — it's still used by the `Scanner` route and the root-level `setContent { val appPreferences = koinInject<AppPreferences>() }` that drives the theme. **Do not remove the root-level collection or the import.**

### String resources

Add to `app/src/main/res/values/strings.xml`:

- `settings_theme_dialog_title` → "Theme"

Reuse the existing label strings via `ThemeMode.label()` — these aren't yet in `strings.xml` (they live as hard-coded strings in the extension function). Out of scope to extract them in this slice; do not introduce a third copy in `strings.xml` either. The label function is the single source of truth.

## State + concurrency model

- `SettingsViewModel.themeMode` — hot `StateFlow` backed by `stateIn(viewModelScope, WhileSubscribed(5_000L), ThemeMode.SYSTEM)`. Cancellation: when no collectors remain for 5 s, the upstream DataStore `Flow` is cancelled; the next subscription restarts it. Standard idiom in this repo.
- Dispatcher: ViewModel's default `Main.immediate` for the `viewModelScope`. DataStore performs its IO on its own dispatcher internally; no explicit `withContext(Dispatchers.IO)` needed at this layer. `SettingsViewModel.onSelectTheme` is a fire-and-forget `viewModelScope.launch`; no result is reported back to the UI because the same `themeMode` flow re-emits with the persisted value once `edit { … }` completes.
- Root-level subscription: `MainActivity.setContent { … }` already collects `appPreferences.themeMode` independently. The new `SettingsViewModel` adds a second subscriber to the same upstream `Flow`. DataStore `Flow`s broadcast to all collectors, so one write fans out to both — this is what AC #3 (no process restart) depends on. The two collectors share an upstream but are otherwise independent.
- Dialog open-state: `var showThemeDialog by remember { mutableStateOf(false) }`, scoped to the composable. Does **not** survive process death; deliberate (re-entering Settings should land on the closed list, not a stale open dialog).
- Dialog `pending`: `var pending by remember(selected) { mutableStateOf(selected) }`. The `remember(selected)` key ensures that when the parent recomposes with an updated `selected` (e.g., the persisted value changed while the dialog was closed), the next dialog opening picks up the new value as its starting point.

## Error handling

None at this layer. `appPreferences.themeMode` already handles malformed stored values (see `AppPreferencesTest.themeMode_unparseableStoredValue_fallsBackToSystem`). `setThemeMode` can in principle throw an `IOException` from DataStore, but the rest of this codebase doesn't surface DataStore write failures to the UI (see `MainActivity.kt:120` for the same fire-and-forget pattern on `setPairedServerExists`) and there is no observed failure mode to defend against. If the write fails, the persisted value stays at its prior value and the next flow emission will reflect that; the dialog closes either way.

If a write failure ever becomes a real issue, add a `Result`-returning write API on `AppPreferences` and a snackbar at the screen — out of scope here.

## Testing strategy

### Unit tests (JVM, `./gradlew test`)

`app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` — new file. Use the `AppPreferencesTest` rig (real `PreferenceDataStoreFactory` backed by `TemporaryFolder`, real `AppPreferences`) plus `Dispatchers.setMain(UnconfinedTestDispatcher())` + `runTest { … }` from `ChannelListViewModelTest`. The VM is thin — testing it against the real `AppPreferences` exercises the round-trip end-to-end without adding a fake just for this slice.

Scenarios:

- **initialState_emitsSystem_whenNoStoredValue** — fresh datastore, collect `vm.themeMode` once with `advanceUntilIdle()`, assert `ThemeMode.SYSTEM`.
- **initialState_mirrorsPersistedValue** — pre-write `prefs.setThemeMode(ThemeMode.DARK)` before constructing the VM, assert `vm.themeMode.value == DARK` after `advanceUntilIdle()` on a launched collector.
- **onSelectTheme_persistsLight** — call `vm.onSelectTheme(ThemeMode.LIGHT)`, `advanceUntilIdle()`, assert `prefs.themeMode.first() == LIGHT`.
- **onSelectTheme_persistsDark** — same shape, `DARK`.
- **onSelectTheme_persistsSystem** — same shape, `SYSTEM` (round-tripping back from a non-default).
- **themeMode_flowReEmits_afterOnSelectTheme** — start a collector capturing emissions; call `onSelectTheme(DARK)` then `onSelectTheme(LIGHT)`; assert the flow's `.value` reaches `LIGHT` after `advanceUntilIdle()`. This is the AC's "exposed flow re-emits after persistence" gate.

No need to write a Compose UI test for the dialog at this slice — the existing repo has no Compose UI tests for Settings, and the dialog is a thin M3 wrapper over `AlertDialog` + `RadioButton`. The VM tests cover the contract that matters; visual fidelity is verified by the existing `SettingsScreenLightPreview` / `SettingsScreenDarkPreview` (which now pass an explicit `themeMode` value) plus the developer running the app once.

### Instrumented tests

None. No new Compose UI tests, no `connectedAndroidTest` additions.

## Acceptance criteria mapping

- **AC #1** (tap opens dialog; three radio rows; current selection reflected) → `SettingsScreen` row `onClick` flips `showThemeDialog = true` → `ThemePickerDialog(selected = themeMode, …)` renders three rows from `ThemeMode.entries`, each with `RadioButton(selected = pending == mode)`. Dialog title from `R.string.settings_theme_dialog_title`. Labels from `ThemeMode.label()`.
- **AC #2** (confirm persists; cancel/dismiss leaves unchanged) → Dialog's OK button: `onConfirm(pending)` → `SettingsScreen` invokes `onSelectTheme(pending)` → VM writes. Cancel + `onDismissRequest`: dialog closes without invoking `onConfirm`; `pending` is discarded with the composition.
- **AC #3** (theme re-renders without process restart) → `MainActivity.setContent { … }` already collects `appPreferences.themeMode`; a write fans out to that collector. The row subtitle updates because `SettingsViewModel.themeMode` collects the same flow.
- **AC #4** (`SettingsViewModel` Koin-injected; route obtains via `koinViewModel`; old direct injection + `themeMode` parameter removed) → `AppModule.kt` adds `viewModel { SettingsViewModel(get()) }`. `MainActivity` settings route rewired as shown above.
- **AC #5** (`SettingsViewModelTest` covers the three scenarios) → see § Testing strategy.

## Open questions

None. The dialog shape (OK/Cancel vs tap-to-commit) is decided in § Design. The label-helper visibility is decided in § Design § Label helper. The `pending` state lifetime is decided in § State + concurrency model.
