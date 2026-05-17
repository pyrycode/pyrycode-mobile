# Spec: Default model picker dialog + wire row

**Ticket:** #232
**Size:** XS

## Context

The "Default model" row in `SettingsScreen.kt:153-158` is currently inert — hardcoded subtitle "Opus 4.7", empty `onClick`. The `Model` enum, `AppPreferences.defaultModel`/`setDefaultModel`, and `Model.label()` already exist (landed via #231). All that remains is the UI dialog and the ViewModel exposure to wire the row to persisted state.

The pattern is mechanically identical to `ThemePickerDialog` / `EffortPickerDialog` (both landed; both nearly line-for-line equivalent). This ticket adds the third instance. PO has called out — and the architect concurs — that we do NOT generalize a "single-choice picker" abstraction yet: three near-identical call sites is still below the rule-of-three threshold given how thin each instance is.

Downstream consumer wiring (`ThreadViewModel` reading `defaultModel` to drop its #228 stub) is owned by #228, not this ticket.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The Default model row (Figma node `17:38–17:42`) is a Material 3 two-line `ListItem` inside the "Defaults for new conversations" section: `headlineContent` "Default model" (`Schemes/on-surface`, body-large), `supportingContent` "Opus 4.7" (`Schemes/on-surface-variant`, body-small), trailing chevron. The row is already implemented at `SettingsScreen.kt:153-158` with hardcoded subtitle and empty click — visual fidelity is preserved by reusing the existing `SettingsRow` composable; this spec only changes the data flowing into it. The picker dialog itself has no Figma sub-node — implement with stock Material 3 `AlertDialog` + radio rows, matching `ThemePickerDialog` and `EffortPickerDialog` byte-for-byte (PO-confirmed).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/settings/EffortPickerDialog.kt` (entire, 85 LOC) — the dialog to clone. Swap `Effort` → `Model`, swap `R.string.settings_effort_dialog_title` → new `R.string.settings_model_dialog_title`. `Model.label()` already exists in `data/preferences/Model.kt`, so no `.label()` extension is added in the new file.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ThemePickerDialog.kt` (entire, 76 LOC) — the original precedent. Confirms the AlertDialog + selectableGroup + OK/Cancel button shape.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:48-105, 152-164, 319-353` — composable signature (params), the `showEffortDialog`/`EffortPickerDialog` block to mirror, the "Default model" row to wire, the two preview functions to update.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` (entire, 68 LOC) — the ViewModel to extend. New `defaultModel` flow + `onSelectDefaultModel` go alongside `defaultEffort` + `onSelectDefaultEffort` (`SettingsViewModel.kt:35-40, 61-63`).
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:39-47` — confirms `defaultModel: Flow<Model>` and `setDefaultModel(Model)` exist with the expected signature, and the default-when-unset is `Model.OPUS_4_7`.
- `app/src/main/java/de/pyryco/mobile/data/preferences/Model.kt` (entire, ~10 LOC) — confirms `Model` enum + `Model.label()` (top-level extension, not member). The new dialog imports it directly.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:219-236` — the `Routes.SETTINGS` composable destination. Mirror the `defaultEffort` collection + pass-through for `defaultModel`.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt:179-236` — the Effort test block to mirror for Model (initial-default, initial-from-persisted, persists-each-value, flow-re-emits). Real `AppPreferences` over `PreferenceDataStoreFactory` with `TemporaryFolder` — no MockK, no Fake.
- `app/src/main/res/values/strings.xml:24-25` — confirms title-resource convention; add `settings_model_dialog_title` here.

## Design

### Files

**New:** `app/src/main/java/de/pyryco/mobile/ui/settings/ModelPickerDialog.kt` (~40 LOC). Line-for-line clone of `EffortPickerDialog.kt` with the following substitutions:

- Import `de.pyryco.mobile.data.preferences.Model` instead of `Effort`
- Composable signature: `internal fun ModelPickerDialog(selected: Model, onConfirm: (Model) -> Unit, onDismiss: () -> Unit)`
- `Model.entries.forEach { model -> ... }` in the `selectableGroup`
- Title resource: `stringResource(R.string.settings_model_dialog_title)`
- Reuse the existing `Model.label()` from `data/preferences/Model.kt`; do NOT redefine it in this file. (`EffortPickerDialog.kt:77-84` defines `Effort.label()` locally because the original Effort enum did not have one when EffortPickerDialog was first written; Model already has its label extension at the data layer, so this file just imports it.)

**Modified:** `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` — add two members (`defaultModel: StateFlow<Model>` + `fun onSelectDefaultModel(model: Model)`) mirroring `defaultEffort` / `onSelectDefaultEffort` at lines 35-40 and 61-63. `initialValue = Model.OPUS_4_7` (matches the persistence-layer default). Import `Model` from `data.preferences`.

**Modified:** `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt`
- Add two parameters to the `SettingsScreen` composable signature (after `defaultEffort`, before `onSelectTheme`): `defaultModel: Model`, `onSelectDefaultModel: (Model) -> Unit`.
- Add `var showModelDialog by remember { mutableStateOf(false) }` next to the existing dialog state.
- Add a `ModelPickerDialog(...)` open-state block mirroring the `EffortPickerDialog` block at `SettingsScreen.kt:96-105`.
- Update the "Default model" row at `SettingsScreen.kt:153-158`: `supporting = defaultModel.label()`, `onClick = { showModelDialog = true }`.
- Update both `@Preview` functions (`SettingsScreenLightPreview`, `SettingsScreenDarkPreview`) to pass `defaultModel = Model.OPUS_4_7` and `onSelectDefaultModel = {}`.
- Add `import de.pyryco.mobile.data.preferences.Model`.

**Modified:** `app/src/main/java/de/pyryco/mobile/MainActivity.kt:219-236` — add one `collectAsStateWithLifecycle` line for `vm.defaultModel` and pass `defaultModel = defaultModel` + `onSelectDefaultModel = vm::onSelectDefaultModel` to the `SettingsScreen(...)` invocation. Mirror the `defaultEffort` lines (224 and 229/232).

**Modified:** `app/src/main/res/values/strings.xml` — add one string resource after `settings_effort_dialog_title`:

```xml
<string name="settings_model_dialog_title">Default model</string>
```

The title matches the row headline (Material 3 single-choice-dialog convention; identical pattern to "Default effort" / "Theme").

### Dialog shape: OK/Cancel (matches precedent)

The ticket's AC #2 asks the architect to choose between OK/Cancel and tap-to-commit. **Choose OK/Cancel**, identical to `ThemePickerDialog` and `EffortPickerDialog`. The `pending` selection lives in `var pending by remember(selected) { mutableStateOf(selected) }`; the dialog persists only on `onConfirm`; `onDismiss` (Cancel, tap-outside, back) leaves `AppPreferences.defaultModel` untouched. This matches user expectations established by the two existing picker dialogs and avoids creating a third interaction model for the same row archetype.

## State + concurrency model

- `defaultModel: StateFlow<Model>` — `appPreferences.defaultModel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), initialValue = Model.OPUS_4_7)`. Identical shape to the existing `defaultEffort` flow. Hot-while-subscribed cold flow underneath; `WhileSubscribed(5_000)` matches the rest of the ViewModel — no new tuning needed.
- `onSelectDefaultModel(model: Model)` — `viewModelScope.launch { appPreferences.setDefaultModel(model) }`. Fire-and-forget write to DataStore; the flow re-emits naturally as DataStore data changes propagate. Same idiom as `onSelectDefaultEffort` (`SettingsViewModel.kt:61-63`).
- No new dispatcher choice; DataStore handles IO internally.
- Dialog-local `pending` state is `remember`d inside the composable and is automatically scoped to dialog lifetime — destroyed on dismiss; reseeded via `remember(selected)` when the persisted value changes externally between opens.

## Error handling

No new failure modes. DataStore writes can fail in pathological scenarios (disk full, process killed mid-write); the existing `appPreferences.setDefaultEffort` pattern silently swallows these (no per-call try/catch in `SettingsViewModel`), and we mirror that. The `StateFlow` simply doesn't re-emit if the underlying write doesn't land — a no-op from the user's perspective, identical to the current `defaultEffort` failure surface. No incident has been observed; no defense to add. (Pipeline rule: evidence-based fix selection.)

## Testing strategy

Add four `runTest` cases to `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt`, mirroring the Effort tests at lines 179-236. Use the existing `newDataStore()` helper and real `AppPreferences`. No new fakes, no MockK.

Test scenarios (bullets, not bodies — developer writes the assertions in the existing idiom):

- **`defaultModel_initialState_emitsOpus47_whenNoStoredValue`** — fresh DataStore, construct VM, collect once, `advanceUntilIdle`, assert `Model.OPUS_4_7`. Mirrors `defaultEffort_initialState_emitsHigh_whenNoStoredValue` at line 179-187.
- **`defaultModel_initialState_mirrorsPersistedValue`** — pre-persist `Model.HAIKU_4_5` via `prefs.setDefaultModel(...)`, construct VM, collect once, assert `vm.defaultModel.value == Model.HAIKU_4_5`. Mirrors line 189-200.
- **`onSelectDefaultModel_persistsSonnet46`** and **`onSelectDefaultModel_persistsHaiku45`** — construct VM, call `vm.onSelectDefaultModel(...)`, `advanceUntilIdle`, assert `prefs.defaultModel.first()` matches. Two cases sufficient (the third is covered transitively by the initial-state default = Opus). Mirrors lines 202-220.
- **`defaultModel_flowReEmits_afterOnSelectDefaultModel`** — construct VM, collect, call `vm.onSelectDefaultModel(Model.SONNET_4_6)`, `advanceUntilIdle`, assert `vm.defaultModel.value == Model.SONNET_4_6`, then call with `Model.HAIKU_4_5`, assert again. Mirrors lines 222-236.

UI behavior (the dialog opens on tap, persists on OK, leaves state on Cancel, dynamic subtitle updates) is **not** covered by a Compose UI test in this ticket — same coverage decision the EffortPickerDialog ticket made (no `ComposeTestRule` test was added for that dialog either). The ViewModel-layer tests above prove the persistence + re-emission contract; the dialog itself is a thin, declarative AlertDialog clone whose correctness is already established by the two existing precedents. If a Compose test is wanted, it should be a follow-up that covers all three picker dialogs uniformly, not a one-off here.

## Open questions

None. Pattern, dependencies, dialog shape, and test coverage are all fully determined by precedent.
