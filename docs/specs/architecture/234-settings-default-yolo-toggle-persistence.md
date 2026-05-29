# 234 — Default YOLO toggle persistence

Wire the existing "Default YOLO" Switch in Settings through to `AppPreferences.defaultYolo` so the value persists across app restarts. Pure plumbing — mirrors the existing `useWallpaperColors` Switch persistence pattern. No new types, no new files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The Default YOLO row (node `17:50`) sits inside the "Defaults for new conversations" section. M3 `ListItem` with `headlineContent` "Default YOLO", static `supportingContent` "off", and a trailing M3 `Switch`. The row is already rendered at `SettingsScreen.kt:181-187`; this ticket changes only the data binding (the `checked` source and the `onCheckedChange` target) and adds no new visual elements. Static "off" supporting text is preserved as-is — see **Open questions** for the dynamic-supporting-text follow-up consideration.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:59-64` — confirm `defaultYolo: Flow<Boolean>` and `suspend fun setDefaultYolo(enabled: Boolean)` already exist (shipped in #231). No changes to this file.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt:29-34` — `useWallpaperColors` StateFlow exposure, the pattern to mirror.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt:65-67` — `onToggleUseWallpaperColors` callback, the pattern to mirror.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:50-65` — composable signature where `useWallpaperColors: Boolean` (line 54) and `onToggleUseWallpaperColors: (Boolean) -> Unit` (line 59) appear; add two analogous params for YOLO.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:80-89` — `var defaultYolo by remember { mutableStateOf(false) }` (line 84) and surrounding locals; remove the YOLO local, keep the others.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:181-187` — the Default YOLO `SettingsRow` block whose `Switch` currently binds to the local. Rebind to the new params.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:335-373` — both `@Preview` functions; both call `SettingsScreen(...)` with named args. Each must pass the two new params with defaults (`defaultYolo = false`, `onToggleDefaultYolo = {}`).
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:219-239` — `composable(Routes.SETTINGS) { ... }` block. Lines 222, 228, 233 show how `useWallpaperColors` is collected + threaded; add the YOLO equivalents next to them.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt:138-177` — the `useWallpaperColors` test trio. Mirror it (three new `@Test` functions) for `defaultYolo`.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt:54-63` — `newDataStore()` helper and `makeVm(...)` factory. Reuse without modification.

## Context

The existing "Default YOLO" Switch row writes to a local `var defaultYolo by remember { mutableStateOf(false) }` — value lost on recomposition outside the composition scope and on process restart. The `AppPreferences` plumbing (Flow + setter + DataStore key) shipped in #231; this ticket consumes it.

Per #229's design, the StatusSheet does NOT initialise per-conversation YOLO from this Settings default — per-conversation YOLO always starts off. The Settings default exists only as a persisted user-intent signal and a potential hook for future flows; no new-conversation flow consumes it today. The data binding still needs to persist for the user-facing toggle to behave like a setting and not a transient UI nub.

## Design

### Data flow

```
AppPreferences.defaultYolo (Flow<Boolean>, source of truth)
  │
  ├── SettingsViewModel.defaultYolo: StateFlow<Boolean>            (stateIn, WhileSubscribed 5s, initial=false)
  │     │
  │     └── MainActivity Routes.SETTINGS — collectAsStateWithLifecycle()
  │           │
  │           └── SettingsScreen(defaultYolo = …, onToggleDefaultYolo = …)
  │                 │
  │                 └── Switch(checked = defaultYolo, onCheckedChange = onToggleDefaultYolo)
  │
  └── SettingsViewModel.onToggleDefaultYolo(enabled)
        └── viewModelScope.launch { appPreferences.setDefaultYolo(enabled) }
              └── DataStore write → Flow re-emits → StateFlow re-emits → UI recomposes
```

### Changes per file

**`SettingsViewModel.kt`** — add one StateFlow + one callback, mirroring `useWallpaperColors`:

- Add `val defaultYolo: StateFlow<Boolean>` sourced from `appPreferences.defaultYolo`, `.stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), initialValue = false)`. Place adjacent to `useWallpaperColors` (after `defaultEffort` and before `archivedDiscussionCount` reads natural; placement is non-load-bearing).
- Add `fun onToggleDefaultYolo(enabled: Boolean)` that does `viewModelScope.launch { appPreferences.setDefaultYolo(enabled) }`. Place adjacent to `onToggleUseWallpaperColors` / `onSelectDefaultEffort`.

**`SettingsScreen.kt`** — three edits:

1. Composable signature (`:50-65`): add `defaultYolo: Boolean` and `onToggleDefaultYolo: (Boolean) -> Unit` parameters. Suggested placement: alongside the other defaults — after `defaultEffort: Effort` and after `onSelectDefaultEffort: (Effort) -> Unit` respectively, so the param order tracks the section ordering in the UI.
2. Body (`:80-89` and `:181-187`): delete `var defaultYolo by remember { mutableStateOf(false) }` (line 84). Rewire the Switch in the Default YOLO row to `Switch(checked = defaultYolo, onCheckedChange = onToggleDefaultYolo)`.
3. Previews (`:335-373`): both `SettingsScreenLightPreview` and `SettingsScreenDarkPreview` call `SettingsScreen(...)` with named args. Add `defaultYolo = false` and `onToggleDefaultYolo = {}` to each.

Keep `supporting = "off"` literal in the Default YOLO row unchanged (see Open questions).

**`MainActivity.kt`** — inside `composable(Routes.SETTINGS) { ... }` (`:219-239`):

1. Add `val defaultYolo by vm.defaultYolo.collectAsStateWithLifecycle()` next to the existing `defaultEffort` collection at `:225`.
2. Add `defaultYolo = defaultYolo,` to the `SettingsScreen(...)` call, near `defaultEffort = defaultEffort,` at `:231`.
3. Add `onToggleDefaultYolo = vm::onToggleDefaultYolo,` to the `SettingsScreen(...)` call, near `onSelectDefaultEffort = vm::onSelectDefaultEffort,` at `:235`.

### Key types

No new types. Reuses `Boolean` directly (Switch state). No sealed-class additions, no enums, no data classes.

## State + concurrency model

- Single `StateFlow<Boolean>` in `SettingsViewModel`, sharing strategy `WhileSubscribed(5_000ms)` matching every other StateFlow in the file. Cold flow `appPreferences.defaultYolo` becomes hot through `stateIn`; survives configuration changes via `viewModelScope`; tears down 5s after the last subscriber.
- Toggle callback dispatches via `viewModelScope.launch { … }` (default Main dispatcher); the suspend `setDefaultYolo` call internally hops to the DataStore I/O dispatcher. No manual dispatcher switching.
- UI collects via `collectAsStateWithLifecycle()` in the `Routes.SETTINGS` composable — automatic stop-on-STOPPED behavior, no manual lifecycle wiring.
- No new coroutines, no shared mutable state, no synchronization concerns.

## Error handling

DataStore `edit { ... }` can theoretically throw `IOException`. The existing `useWallpaperColors`, `setThemeMode`, `setDefaultModel`, and `setDefaultEffort` callbacks all fire-and-forget via `viewModelScope.launch { … }` without wrapping in try/catch. Match that — adding error handling here would diverge from the established settings pattern for no observed-failure reason. If DataStore writes start failing in practice, address it uniformly across all settings callbacks in a separate ticket.

The `defaultYolo` read flow has no `.catch { }` operator (matching all sibling defaults flows — only `archivedDiscussionCount` catches because the upstream repository can fail). DataStore read errors are sufficiently rare that propagating to the UI is the right default; the existing pattern is the precedent.

## Testing strategy

Unit tests only, in `SettingsViewModelTest.kt`. Mirror the `useWallpaperColors` test trio (lines 138-177); reuse `newDataStore()` / `makeVm()` helpers verbatim.

Three new test functions:

- **`defaultYolo_initialState_emitsFalse_whenNoStoredValue`** — fresh DataStore, no prior write. Assert `vm.defaultYolo.value == false` after collector attaches and `advanceUntilIdle()`.
- **`defaultYolo_initialState_mirrorsPersistedTrue`** — call `prefs.setDefaultYolo(true)` before constructing the VM; assert `vm.defaultYolo.value == true` after collector attaches and `advanceUntilIdle()`.
- **`onToggleDefaultYolo_persistsAndFlowReEmits`** — toggle to `true` via `vm.onToggleDefaultYolo(true)`; assert both `prefs.defaultYolo.first() == true` AND `vm.defaultYolo.value == true`. Then toggle to `false`; assert both flip back. Uses the same single-collector pattern as `onToggleUseWallpaperColors_persistsAndFlowReEmits` at `:162-177`.

No `MainActivity` test added — the wiring is identical-shape to four existing pass-throughs (`themeMode`, `useWallpaperColors`, `defaultModel`, `defaultEffort`), none of which have route-level tests. The integration check happens via manual verification (per AC).

`AppPreferences` round-trip persistence is already covered by #231's tests — do not re-test that surface.

No instrumented tests required. No Compose UI test required (the Switch row's visual rendering is unchanged; only the state binding moves).

## Open questions

- **Dynamic supporting text.** The Figma node `17:50` shows "off" as the supporting text while the Switch is OFF; the sibling defaults rows (Default model "Opus 4.7", Default effort "high", Default workspace "scratch") show their current value. The Default YOLO row could plausibly show "on" / "off" reflecting Switch state, matching that sibling pattern. The ticket body does **not** request this change ("only the `checked` source and the `onCheckedChange` target change"), so this spec preserves the static `supporting = "off"` literal. Flagging as a candidate for a follow-up ticket if PO wants per-row consistency in the section.
