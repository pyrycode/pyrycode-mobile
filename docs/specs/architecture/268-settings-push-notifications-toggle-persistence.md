# 268 — Push notifications toggle persistence

Wire the existing "Push notifications when claude responds" Switch in Settings through to a **new** `AppPreferences.notificationsEnabled` preference so the value persists across app restarts. Pure plumbing — mirrors the `defaultYolo` Switch persistence pattern (#234, closed), with **one deliberate deviation: the preference defaults to `true`, not `false`.** No new types, no new files. Persists the preference only — actual notification delivery is Phase 4 (same scope boundary as #234); nothing consumes this preference today beyond the Settings switch itself.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The Push-notifications row (node `17:64`) sits inside the "Notifications" section. M3 `ListItem` with `headlineContent` "Push notifications when claude responds", **no** supporting text, and a trailing M3 `Switch` rendered **ON** by default (filled `Schemes/primary` track — verified against the screenshot; contrast the OFF Default-YOLO toggle in the section directly above). This ON-by-default render is the source of truth for the `true` default below. The row is already rendered at `SettingsScreen.kt:205-213`; this ticket changes only the data binding (the `checked` source and the `onCheckedChange` target) and adds no new visual elements.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:59-64` — `defaultYolo: Flow<Boolean>` getter + `suspend fun setDefaultYolo(enabled: Boolean)` setter, the getter/setter pair to mirror. **Note the `?: false` default — you will write `?: true` instead.**
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:73-81` — the `private companion object` of `booleanPreferencesKey`/`stringPreferencesKey` entries; add `NOTIFICATIONS_ENABLED` here.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt:53-58` — `defaultYolo` StateFlow exposure via `stateIn(... initialValue = false)`, the pattern to mirror with `initialValue = true`.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt:97-99` — `onToggleDefaultYolo` callback, the pattern to mirror.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:49-70` — composable signature where `defaultYolo: Boolean` (line 55) and `onToggleDefaultYolo: (Boolean) -> Unit` (line 62) appear; add two analogous params for push notifications.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:88` — the `var pushNotifications by remember { mutableStateOf(true) }` local to delete. (Leave the sibling `showThemeDialog`/`showModelDialog`/`showEffortDialog` locals at lines 89-91 untouched.)
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:204-213` — the "Notifications" section header + the Push-notifications `SettingsRow` whose `Switch` currently binds to the local at line 209 (`checked = pushNotifications`) and assigns the local at line 210 (`onCheckedChange = { pushNotifications = it }`). Rebind both to the new params. Note: this row has **no** `supporting` arg — leave it that way (see Open questions).
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:326-380` — both `@Preview` functions (`SettingsScreenLightPreview`, `SettingsScreenDarkPreview`); each calls `SettingsScreen(...)` with named args. Each must pass the two new params (`pushNotifications = true`, `onTogglePushNotifications = {}`).
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:228-259` — `composable(Routes.SETTINGS) { ... }`. Line 235 shows `val defaultYolo by vm.defaultYolo.collectAsStateWithLifecycle()`; line 244 shows `defaultYolo = defaultYolo,`; line 251 shows `onToggleDefaultYolo = vm::onToggleDefaultYolo,`. Add the push-notifications equivalents next to each.
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt:142-157` — the `defaultYolo` test pair (`defaultYolo_defaultsToFalse`, `setDefaultYolo_roundTripsBothValues`); mirror it with the **inverted default** (`true`). Setup/teardown at `:30-44` is reused as-is.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt:300-339` — the `defaultYolo` test trio. Mirror it (three new `@Test` functions) for `pushNotifications`, inverting the default-value expectations.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt:54-63` — `newDataStore()` helper and `makeVm(...)` factory. Reuse without modification.

## Context

The "Push notifications when claude responds" Switch row writes to a local `var pushNotifications by remember { mutableStateOf(true) }` (`SettingsScreen.kt:88`) — the value is lost on screen rebuild and on process restart. This ticket adds the missing `AppPreferences` plumbing (key + Flow + setter) and threads it through the ViewModel and route, exactly as #234 did for `defaultYolo`.

**Difference from #234:** there, the `AppPreferences` surface (`defaultYolo`/`setDefaultYolo` + DataStore key) already shipped in #231, so #234 only consumed it. Here the `AppPreferences` surface does **not** exist yet — this ticket creates it. That is why this spec adds production changes *and* tests to `AppPreferences`/`AppPreferencesTest`, whereas #234 did not.

## The load-bearing deviation: default = `true`

Every other boolean preference in `AppPreferences` defaults to `false` (`pairedServerExists`, `useWallpaperColors`, `defaultYolo`). This one is the exception. Figma renders the Push-notifications toggle ON, and the live placeholder is `mutableStateOf(true)`; persistence must preserve ON-by-default. Concretely, **three** sites carry the `true` default and all three must agree:

1. `AppPreferences.notificationsEnabled` getter — `prefs[NOTIFICATIONS_ENABLED] ?: true` (**not** `?: false`).
2. `SettingsViewModel.pushNotifications` — `stateIn(... initialValue = true)` (**not** `false`).
3. `AppPreferencesTest.notificationsEnabled_defaultsToTrue` — the deterministic guard that pins #1.

Do not copy the `false` default from the `defaultYolo` lines you are mirroring. The `defaultsToTrue` test is the safety net against a stochastic copy-paste slip; do not omit it.

## Design

### Data flow

```
AppPreferences.notificationsEnabled (Flow<Boolean>, source of truth, default = true)
  │
  ├── SettingsViewModel.pushNotifications: StateFlow<Boolean>        (stateIn, WhileSubscribed 5s, initial=true)
  │     │
  │     └── MainActivity Routes.SETTINGS — collectAsStateWithLifecycle()
  │           │
  │           └── SettingsScreen(pushNotifications = …, onTogglePushNotifications = …)
  │                 │
  │                 └── Switch(checked = pushNotifications, onCheckedChange = onTogglePushNotifications)
  │
  └── SettingsViewModel.onTogglePushNotifications(enabled)
        └── viewModelScope.launch { appPreferences.setNotificationsEnabled(enabled) }
              └── DataStore write → Flow re-emits → StateFlow re-emits → UI recomposes
```

### Changes per file

**`AppPreferences.kt`** — add a key + getter + setter, mirroring the `defaultYolo` block at `:59-64`:

- Companion key (in the `private companion object`, `:73-81`): `val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")`.
- Getter: `val notificationsEnabled: Flow<Boolean> = dataStore.data.map { prefs -> prefs[NOTIFICATIONS_ENABLED] ?: true }`. **`?: true`.**
- Setter: `suspend fun setNotificationsEnabled(enabled: Boolean)` → `dataStore.edit { prefs -> prefs[NOTIFICATIONS_ENABLED] = enabled }`.

No new imports (`booleanPreferencesKey`, `Flow`, `map`, `edit` are all already imported).

**`SettingsViewModel.kt`** — add one StateFlow + one callback, mirroring `defaultYolo`:

- `val pushNotifications: StateFlow<Boolean>` sourced from `appPreferences.notificationsEnabled`, `.stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), initialValue = true)`. **`initialValue = true`.** Place adjacent to `defaultYolo` (placement non-load-bearing).
- `fun onTogglePushNotifications(enabled: Boolean)` → `viewModelScope.launch { appPreferences.setNotificationsEnabled(enabled) }`. Place adjacent to `onToggleDefaultYolo`.

**`SettingsScreen.kt`** — three edits:

1. Composable signature (`:49-70`): add `pushNotifications: Boolean` and `onTogglePushNotifications: (Boolean) -> Unit` parameters. Suggested placement: a `pushNotifications: Boolean` among the other `Boolean` state params and `onTogglePushNotifications` among the callbacks; ordering is non-load-bearing as long as the previews and `MainActivity` call use named args (they do).
2. Body: delete `var pushNotifications by remember { mutableStateOf(true) }` (`:88`). Rebind the Switch in the Push-notifications row (`:208-211`) to `Switch(checked = pushNotifications, onCheckedChange = onTogglePushNotifications)`.
3. Previews (`:326-380`): both `SettingsScreenLightPreview` and `SettingsScreenDarkPreview` call `SettingsScreen(...)` with named args. Add `pushNotifications = true` and `onTogglePushNotifications = {}` to each.

Leave the row's absence of a `supporting` arg unchanged (see Open questions).

**`MainActivity.kt`** — inside `composable(Routes.SETTINGS) { ... }` (`:228-259`):

1. Add `val pushNotifications by vm.pushNotifications.collectAsStateWithLifecycle()` next to the `defaultYolo` collection at `:235`.
2. Add `pushNotifications = pushNotifications,` to the `SettingsScreen(...)` call, near `defaultYolo = defaultYolo,` at `:244`.
3. Add `onTogglePushNotifications = vm::onTogglePushNotifications,` to the `SettingsScreen(...)` call, near `onToggleDefaultYolo = vm::onToggleDefaultYolo,` at `:251`.

### Key types

No new types. Reuses `Boolean` directly (Switch state). No sealed-class additions, no enums, no data classes. No DI changes — `AppPreferences` and `SettingsViewModel` are already Koin-wired; the new members ride the existing graph.

## State + concurrency model

Identical to every other boolean preference in this file:

- Single `StateFlow<Boolean>` in `SettingsViewModel`, sharing strategy `WhileSubscribed(5_000ms)` matching every sibling StateFlow. Cold flow `appPreferences.notificationsEnabled` becomes hot through `stateIn`; survives configuration changes via `viewModelScope`; tears down 5s after the last subscriber. **`initialValue = true`** so the UI shows ON during the brief window before the first DataStore emission (correct for a default-on preference; an `initialValue = false` here would cause a visible OFF→ON flicker on cold open).
- Toggle callback dispatches via `viewModelScope.launch { … }` (default Main dispatcher); the suspend `setNotificationsEnabled` internally hops to the DataStore I/O dispatcher. No manual dispatcher switching.
- UI collects via `collectAsStateWithLifecycle()` in the `Routes.SETTINGS` composable — automatic stop-on-STOPPED, no manual lifecycle wiring.
- No new coroutines, no shared mutable state, no synchronization concerns.

## Error handling

Match the established settings pattern exactly. The `setNotificationsEnabled` write fires-and-forgets via `viewModelScope.launch { … }` without try/catch, like `setDefaultYolo`/`setThemeMode`/`setDefaultModel`/`setUseWallpaperColors`. The `notificationsEnabled` read flow has no `.catch { }` operator (matching all sibling default flows — only `archivedDiscussionCount` catches, because its upstream repository can fail). Adding error handling here would diverge from the settings pattern for no observed-failure reason. If DataStore writes start failing in practice, address it uniformly across all settings callbacks in a separate ticket.

## Testing strategy

Unit tests only. No instrumented tests, no Compose UI test (the Switch row's visual rendering is unchanged; only the state binding moves). Reuse existing helpers verbatim.

**`AppPreferencesTest.kt`** — two new functions mirroring the `defaultYolo` pair (`:142-157`), with the inverted default:

- **`notificationsEnabled_defaultsToTrue`** — fresh DataStore, no prior write. Assert `prefs.notificationsEnabled.first() == true`. *This is the deterministic guard for the non-obvious default; do not omit it.*
- **`setNotificationsEnabled_roundTripsBothValues`** — set `false` → assert `false`; set `true` → assert `true`; set `false` → assert `false`. (Starting with `false` proves an explicit write overrides the `true` default in both directions.)

**`SettingsViewModelTest.kt`** — three new functions mirroring the `defaultYolo` trio (`:300-339`), inverting default-value expectations. Use the single-collector + `advanceUntilIdle()` pattern shared by every flow test in this file:

- **`pushNotifications_initialState_emitsTrue_whenNoStoredValue`** — fresh DataStore; after collector attaches + `advanceUntilIdle()`, assert `vm.pushNotifications.value == true`.
- **`pushNotifications_initialState_mirrorsPersistedFalse`** — call `prefs.setNotificationsEnabled(false)` before constructing the VM; assert `vm.pushNotifications.value == false`. (Persisting `false` — the non-default — is the meaningful mirror: it proves a stored value overrides the `true` default.)
- **`onTogglePushNotifications_persistsAndFlowReEmits`** — toggle to `false` via `vm.onTogglePushNotifications(false)`; assert both `prefs.notificationsEnabled.first() == false` AND `vm.pushNotifications.value == false`. Then toggle to `true`; assert both flip back.

No `MainActivity` test added — the wiring is identical-shape to the existing pass-throughs (`themeMode`, `useWallpaperColors`, `defaultModel`, `defaultEffort`, `defaultYolo`), none of which have route-level tests. The route integration is covered by the manual-verification AC (open app → switch reads ON → toggle off → force-stop → relaunch Settings → switch reads OFF).

## Open questions

- **Supporting text.** Unlike the Default YOLO row (static `supporting = "off"`), the Push-notifications row has no supporting subtext in either the current code or Figma node `17:64`. This spec preserves that — no `supporting` arg is added. If PO later wants the toggle rows in this section to show an "on"/"off" state line for consistency, that is a separate follow-up (and would pair with the same dynamic-supporting-text question #234 flagged for Default YOLO).
