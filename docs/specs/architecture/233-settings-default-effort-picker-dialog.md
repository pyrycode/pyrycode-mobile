# Spec: Settings default-effort picker dialog (#233)

Issue: [#233](https://github.com/pyrycode/pyrycode-mobile/issues/233)
Depends on: #231 (`Effort` enum + `AppPreferences.defaultEffort` / `setDefaultEffort` — merged in `c86e86f`)
Sibling slice: #232 (Default model picker — branch `origin/feature/232` exists with zero diff vs `main` at architect time; file-overlap check returned no actual overlap. This spec follows the #87 `ThemePickerDialog` precedent and sets the same shape #232 should naturally adopt; whichever lands second rebases trivially since each adds an independent `StateFlow` + handler to `SettingsViewModel` and an independent row wire-up in `SettingsScreen.kt`.)

## Context

The Settings screen's "Default effort" row at `SettingsScreen.kt:144-149` is currently inert — hardcoded subtitle `"high"`, no `onClick`. This slice wires it to a Material 3 single-choice dialog backed by `AppPreferences.defaultEffort`, mirroring the `ThemePickerDialog` precedent from #87 and adding a `defaultEffort: StateFlow<Effort>` + `onSelectDefaultEffort(effort)` pair to `SettingsViewModel`.

This is the second consumer of the defaults schema introduced by #231. The first (model picker) is the sibling slice #232. The third Phase-2 consumer is `StatusSheet` (tracked under #229) — that consumer is **not** part of this ticket.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The "Default effort" row is a `ListItem` under the "Defaults for new conversations" section, with headline "Default effort", a `body-small` supporting line showing the current effort label, and a trailing chevron. Visually identical to the "Theme" row above it. The picker dialog itself has no Figma sub-node; the ticket explicitly authorises M3 single-choice dialog defaults — use `AlertDialog` with five `Row { RadioButton + Text }` entries and confirm/dismiss buttons styled from the standard M3 dialog tokens (`Schemes/surface-container-high` container, `Schemes/on-surface` title, `Schemes/primary` confirm-button text).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/preferences/Effort.kt:3` — the five-value enum (`LOW`, `MEDIUM`, `HIGH`, `XHIGH`, `MAX`) the picker selects between.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:49-57` — `defaultEffort: Flow<Effort>` and `suspend fun setDefaultEffort(effort)`; the entire data-side API this slice consumes. Default value is `Effort.HIGH`.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ThemePickerDialog.kt` — the precedent. The new `EffortPickerDialog.kt` is a near-mechanical copy of this file with `ThemeMode` → `Effort`, `R.string.settings_theme_dialog_title` → `R.string.settings_effort_dialog_title`, and `ThemeMode.label()` → `Effort.label()`. Use the same imports, the same `var pending by remember(selected) { mutableStateOf(selected) }` shape, the same `Modifier.selectableGroup()` wrapping, and the same OK/Cancel button shape.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:79-90` — current `showThemeDialog` state + `if (showThemeDialog) ThemePickerDialog(...)` block. The new `showEffortDialog` state + `EffortPickerDialog(...)` block sits adjacent, using the same shape.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:144-149` — the inert "Default effort" row this slice wires.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:295-300` — the `internal fun ThemeMode.label()` extension. The new `Effort.label()` extension follows the same pattern but lives in `EffortPickerDialog.kt` (file-local internal extension, same package — `SettingsScreen.kt` resolves it without an import).
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:304-334` — the two `@Preview` composables that need new `defaultEffort` / `onSelectDefaultEffort` parameters passed.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt:20-51` — existing `themeMode: StateFlow<ThemeMode>` + `onSelectTheme(mode)` shape. The new `defaultEffort` + `onSelectDefaultEffort` are a mechanical copy with `ThemeMode` → `Effort`, `themeMode` → `defaultEffort`, `setThemeMode` → `setDefaultEffort`, and `ThemeMode.SYSTEM` → `Effort.HIGH` as the `initialValue`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:208-222` — the Settings route's VM-to-Screen wiring. Add a `val defaultEffort by vm.defaultEffort.collectAsStateWithLifecycle()` line and pass `defaultEffort = defaultEffort, onSelectDefaultEffort = vm::onSelectDefaultEffort` to `SettingsScreen(...)`.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt:63-133` — the six `themeMode` / `onSelectTheme` test cases. The new `defaultEffort` / `onSelectDefaultEffort` cases follow the same `runTest(dispatcher) { … }` template with the existing `newDataStore()` + real `AppPreferences` rig.
- `app/src/main/res/values/strings.xml:24` — `settings_theme_dialog_title` precedent; add `settings_effort_dialog_title` adjacent to it.

## Design

### Package layout

```
ui/settings/
├── SettingsScreen.kt          (modified — pass two new params, add dialog open-state, wire row tap)
├── SettingsViewModel.kt       (modified — add defaultEffort StateFlow + onSelectDefaultEffort)
├── ThemePickerDialog.kt       (untouched)
└── EffortPickerDialog.kt      (new — picker composable + Effort.label() extension)
```

Separate file for the dialog matches the #87 precedent — `ThemePickerDialog.kt` lives next to `SettingsScreen.kt` to keep the screen file from growing past ~340 lines, and the dialog composable carries its own `Effort.label()` extension at file scope.

### `EffortPickerDialog.kt` (new)

Top of file: package `de.pyryco.mobile.ui.settings`, identical import block to `ThemePickerDialog.kt` with `ThemeMode` → `Effort`.

```kotlin
@Composable
internal fun EffortPickerDialog(
    selected: Effort,
    onConfirm: (Effort) -> Unit,
    onDismiss: () -> Unit,
)
```

Body: mechanical copy of `ThemePickerDialog.kt:34-74` with `ThemeMode.entries` → `Effort.entries`, `R.string.settings_theme_dialog_title` → `R.string.settings_effort_dialog_title`, and `mode.label()` referring to the new `Effort.label()` extension below.

Below the composable, at file top level:

```kotlin
internal fun Effort.label(): String =
    when (this) {
        Effort.LOW -> "low"
        Effort.MEDIUM -> "medium"
        Effort.HIGH -> "high"
        Effort.XHIGH -> "xhigh"
        Effort.MAX -> "max"
    }
```

Lowercase labels match the existing hard-coded `supporting = "high"` value in `SettingsScreen.kt:146` and the lowercase enum names the ticket calls out. **No `strings.xml` extraction for the five labels** — `ThemeMode.label()` does the same thing and out-of-scope cleanup is rejected by `CLAUDE.md`. Same `internal` visibility, same package, same idiom.

### `SettingsViewModel` additions

Add (alongside the existing `themeMode` block at lines 20-25):

```kotlin
val defaultEffort: StateFlow<Effort> =
    appPreferences.defaultEffort.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = Effort.HIGH,
    )
```

Initial value `Effort.HIGH` matches `AppPreferences.defaultEffort`'s fallback when no value is persisted. Add (alongside `onSelectTheme` at line 45):

```kotlin
fun onSelectDefaultEffort(effort: Effort) {
    viewModelScope.launch { appPreferences.setDefaultEffort(effort) }
}
```

Same `WhileSubscribed(5_000L)` rationale as the existing flows. Same fire-and-forget pattern as `onSelectTheme` — DataStore handles thread-safety, and the next emission of `defaultEffort` reflects the persisted value once `edit { … }` completes.

### `SettingsScreen` modifications

New parameter signature (additions in **bold**):

```kotlin
fun SettingsScreen(
    themeMode: ThemeMode,
    useWallpaperColors: Boolean,
    archivedDiscussionCount: Int,
    **defaultEffort: Effort,**
    onSelectTheme: (ThemeMode) -> Unit,
    onToggleUseWallpaperColors: (Boolean) -> Unit,
    **onSelectDefaultEffort: (Effort) -> Unit,**
    onBack: () -> Unit,
    onOpenArchivedDiscussions: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Inside `Scaffold`'s content lambda, add adjacent to the existing `showThemeDialog` state at line 79:

```kotlin
var showEffortDialog by remember { mutableStateOf(false) }
```

And adjacent to the `ThemePickerDialog(...)` block at lines 81-90:

```kotlin
if (showEffortDialog) {
    EffortPickerDialog(
        selected = defaultEffort,
        onConfirm = { effort ->
            onSelectDefaultEffort(effort)
            showEffortDialog = false
        },
        onDismiss = { showEffortDialog = false },
    )
}
```

Rewire the existing "Default effort" row at lines 144-149:

```kotlin
SettingsRow(
    headline = "Default effort",
    supporting = defaultEffort.label(),
    trailing = { ChevronIcon() },
    onClick = { showEffortDialog = true },
)
```

`defaultEffort.label()` resolves to the new file-top-level extension in `EffortPickerDialog.kt` (same package, `internal` visibility).

Update both `@Preview` composables (`SettingsScreenLightPreview` at line 304 and `SettingsScreenDarkPreview` at line 320) to pass `defaultEffort = Effort.HIGH, onSelectDefaultEffort = {}`. **Do not** add a default value to `defaultEffort` on the screen composable — match the existing strict-parameter style (`themeMode` has no default).

### `MainActivity` Settings route rewiring

Inside the existing `composable(Routes.SETTINGS) { … }` block at lines 208-222, add the new collection and pass-through:

```kotlin
val defaultEffort by vm.defaultEffort.collectAsStateWithLifecycle()
// then in SettingsScreen(...):
defaultEffort = defaultEffort,
onSelectDefaultEffort = vm::onSelectDefaultEffort,
```

Position the new lines adjacent to the existing `themeMode` / `onSelectTheme` lines to keep the route block grouped by feature.

### String resource

Add to `app/src/main/res/values/strings.xml` adjacent to `settings_theme_dialog_title` (line 24):

```xml
<string name="settings_effort_dialog_title">Default effort</string>
```

The Effort label strings themselves are **not** added to `strings.xml` — they live in the `Effort.label()` extension, matching how `ThemeMode.label()` handles its three labels. Three duplicates of "low" / "medium" / … is the kind of out-of-scope refactor `CLAUDE.md` explicitly forbids.

## State + concurrency model

- `SettingsViewModel.defaultEffort` — hot `StateFlow<Effort>` from `stateIn(viewModelScope, WhileSubscribed(5_000L), Effort.HIGH)`. Standard repo idiom; identical mechanics to `themeMode`.
- Dispatcher: ViewModel's default `Main.immediate` for `viewModelScope`. DataStore performs IO on its internal dispatcher. `onSelectDefaultEffort` is fire-and-forget `viewModelScope.launch { … }` — no result reporting back to the UI because the same `defaultEffort` flow re-emits the persisted value once `edit { … }` completes.
- Dialog open-state (`showEffortDialog`) is local UI state in `SettingsScreen`'s scaffold-content lambda — does not survive process death; deliberate (re-entering Settings should land on the closed list).
- Dialog `pending` is `var pending by remember(selected) { mutableStateOf(selected) }` inside `EffortPickerDialog`. The `remember(selected)` key resets `pending` when the parent opens the dialog with a (possibly changed) `selected`.
- Two `defaultEffort` collectors are possible after #229 lands (Settings VM + StatusSheet's ThreadViewModel). DataStore `Flow`s broadcast to all collectors; one write fans out cleanly. No coordination required here.

## Error handling

None at this layer. `appPreferences.defaultEffort` already handles malformed stored values via the `Effort.entries.firstOrNull { it.name == stored } ?: Effort.HIGH` fallback (see `AppPreferences.kt:49-53` and the `AppPreferencesTest` coverage from #231). `setDefaultEffort` can in principle throw `IOException` from DataStore, but the rest of this codebase doesn't surface DataStore write failures to the UI (`onSelectTheme`, `onToggleUseWallpaperColors`, `setPairedServerExists` all share the fire-and-forget pattern) and no failure mode has been observed. If a write fails, the persisted value stays at its prior value and the next flow emission reflects that; the dialog closes either way.

## Testing strategy

### Unit tests (JVM, `./gradlew test`)

Append to `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt`. Use the existing `dispatcher`, `newDataStore()`, and `makeVm()` helpers; no new test rig needed. Add `import de.pyryco.mobile.data.preferences.Effort` at the top.

Scenarios (mechanical copies of the corresponding `themeMode` tests with `ThemeMode` → `Effort` substitutions):

- **`defaultEffort_initialState_emitsHigh_whenNoStoredValue`** — fresh datastore, launch a collector, `advanceUntilIdle()`, assert `vm.defaultEffort.value == Effort.HIGH`.
- **`defaultEffort_initialState_mirrorsPersistedValue`** — pre-write `prefs.setDefaultEffort(Effort.LOW)` before constructing the VM, launch a collector, `advanceUntilIdle()`, assert `vm.defaultEffort.value == Effort.LOW`.
- **`onSelectDefaultEffort_persistsLow`** — call `vm.onSelectDefaultEffort(Effort.LOW)`, `advanceUntilIdle()`, assert `prefs.defaultEffort.first() == Effort.LOW`.
- **`onSelectDefaultEffort_persistsMax`** — same shape, `Effort.MAX` (round-tripping the extreme value of the enum to catch off-by-one in `Effort.entries` lookup).
- **`defaultEffort_flowReEmits_afterOnSelectDefaultEffort`** — start a collector, call `onSelectDefaultEffort(Effort.LOW)` then `onSelectDefaultEffort(Effort.XHIGH)`; assert `vm.defaultEffort.value` reaches `Effort.XHIGH` after `advanceUntilIdle()`. This is the AC's "exposed flow re-emits after persistence" gate.

Five tests cover AC #5 fully without redundancy: `LOW` (boundary), `MAX` (other boundary), and `XHIGH` (middle non-default) exercise the round trip across the enum; the initial-state pair covers the empty + populated cases; the re-emit test gates the StateFlow contract.

No new Compose UI test for the dialog — repo has no Compose UI tests for Settings, the dialog is a thin `AlertDialog` wrapper, and the VM-level tests cover the contract that matters. Visual fidelity is verified by the updated `SettingsScreenLightPreview` / `SettingsScreenDarkPreview` plus the developer launching the app once via `./gradlew installDebug` and tapping the row.

### Instrumented tests

None. No `connectedAndroidTest` additions.

## Acceptance criteria mapping

- **AC #1** (tap opens dialog; five radio rows; current selection reflected) → `SettingsScreen` row `onClick` flips `showEffortDialog = true` → `EffortPickerDialog(selected = defaultEffort, …)` renders five rows from `Effort.entries`. Dialog title from `R.string.settings_effort_dialog_title`. Labels from `Effort.label()`.
- **AC #2** (confirm persists; cancel/dismiss leaves unchanged) → Dialog's OK button: `onConfirm(pending)` → `SettingsScreen` invokes `onSelectDefaultEffort(pending)` → VM writes via `setDefaultEffort`. Cancel + `onDismissRequest`: dialog closes without invoking `onConfirm`; `pending` discarded with the composition. OK/Cancel shape matches #87 — see § Design § `EffortPickerDialog.kt`.
- **AC #3** (subtitle updates without process restart) → `MainActivity` collects `vm.defaultEffort` via `collectAsStateWithLifecycle()`; write triggers DataStore emission; row recomposes with new `defaultEffort.label()`.
- **AC #4** (`defaultEffort: StateFlow<Effort>` + `onSelectDefaultEffort(effort)` exposed on VM) → see § Design § `SettingsViewModel` additions.
- **AC #5** (`SettingsViewModelTest` covers initial state + selection persistence + re-emit) → see § Testing strategy.

## Open questions

None. Dialog shape is OK/Cancel (matches #87). Dialog file placement is separate file (matches #87). `Effort.label()` lives in `EffortPickerDialog.kt` as a file-top-level internal extension (mirrors `ThemeMode.label()` placement, which lives in `SettingsScreen.kt`; either file is acceptable per `CLAUDE.md` since both are same-package). Lowercase labels match the existing hard-coded subtitle text and the ticket's enum naming.
