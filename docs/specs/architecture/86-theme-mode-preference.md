# Spec — #86 feat(theme): persist ThemeMode preference + reactive PyrycodeMobileTheme wiring

## Context

The Settings screen scaffolded in #64 shows an Appearance → Theme row whose subtitle is a hardcoded `"System"` literal and whose tap is a no-op. Before the sibling picker dialog can land, the data path needs to exist: a persisted enum-backed preference, plus a wiring change so the composition root resolves the right scheme through `PyrycodeMobileTheme`.

This slice is intentionally invisible-by-default: there is no UI to change the value, and `SYSTEM` (the default) preserves current behavior. The visible UX (picker dialog) is the sibling ticket. Split from #74.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:1-23` — the only existing preference; new `themeMode` flow + setter follow the same shape (`dataStore.data.map` + `dataStore.edit`).
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt:1-55` — `TemporaryFolder` + real `PreferenceDataStoreFactory` test pattern. New tests slot in next to the existing two.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:46-78` — composition root (`setContent { PyrycodeMobileTheme { … } }`). This is what the ticket body imprecisely calls "PyryApp"; the Kotlin `PyryApp` class is the Koin Application, not the composition root. The theme-mode resolution happens here, inside the `setContent` lambda, before `PyrycodeMobileTheme(…)`.
- `app/src/main/java/de/pyryco/mobile/PyryApp.kt:1-15` — read once to confirm it's an `Application` subclass and **not** the place to put theme resolution. Don't edit this file.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt:264-289` — `PyrycodeMobileTheme(darkTheme: Boolean = isSystemInDarkTheme(), …)`. **Signature stays unchanged** — caller computes `darkTheme`.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:94-100` — the Theme row whose hardcoded `supporting = "System"` is replaced.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:17-28` — confirms `AppPreferences` is already a `single { … }`; no DI changes needed.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

This slice is text-source only on an existing surface: the Appearance → Theme row (Figma nodes `17:25`–`17:29`) keeps its body-large headline, body-small supporting line on `schemes/on-surface-variant`, and trailing chevron exactly as #64 implemented them. The only change is that the supporting text now comes from a flow instead of a literal. No new components, tokens, or layout adjustments. The picker dialog has no Figma node yet — that's the sibling ticket's concern.

## Design

### Package layout

```
de/pyryco/mobile/data/preferences/
├── AppPreferences.kt          (edited)
└── ThemeMode.kt               (new)
```

### New types

**`ThemeMode` (sealed by enum — three values, exhaustive `when` is the goal):**

```kotlin
enum class ThemeMode { SYSTEM, LIGHT, DARK }
```

That's the entire file. No companion methods (label mapping lives in `SettingsScreen.kt`; resolution lives in `MainActivity.kt`). Package: `de.pyryco.mobile.data.preferences`.

### `AppPreferences` additions

Contract (signatures only — body follows the existing `pairedServerExists` shape):

- `val themeMode: Flow<ThemeMode>` — backed by a `stringPreferencesKey("theme_mode")` holding `ThemeMode.name`. When the key is absent OR the stored string doesn't match any enum value, the flow emits `ThemeMode.SYSTEM`. Use a guarded lookup (`ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM` or `runCatching { ThemeMode.valueOf(stored) }.getOrDefault(SYSTEM)` — either is fine; the test pins behavior, not implementation).
- `suspend fun setThemeMode(mode: ThemeMode)` — writes `mode.name` to the same key via `dataStore.edit { … }`.
- New private key constant in the companion: `val THEME_MODE = stringPreferencesKey("theme_mode")`.

Imports needed beyond the existing set: `androidx.datastore.preferences.core.stringPreferencesKey`.

### Composition-root wiring (`MainActivity.kt`)

The resolution must stay inside a `@Composable` scope because `isSystemInDarkTheme()` is composable. The current `setContent { PyrycodeMobileTheme { Scaffold(…) { … } } }` block becomes:

1. Inject `AppPreferences` via `koinInject<AppPreferences>()` at the top of the `setContent` lambda (before `PyrycodeMobileTheme`).
2. Collect the flow as state with `appPreferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)`. Initial value `SYSTEM` is correct because that's the same default the flow would have emitted on first read — no flash-of-wrong-theme.
3. Resolve `darkTheme: Boolean` with an exhaustive `when` on the collected value: `SYSTEM → isSystemInDarkTheme()`, `LIGHT → false`, `DARK → true`.
4. Pass `darkTheme = resolved` into `PyrycodeMobileTheme(…)`.

`Theme.kt`'s signature does **not** change — keeping `darkTheme: Boolean` lets the 18 other `PyrycodeMobileTheme(…)` call sites (all `@Preview` composables under `ui/`) remain untouched. This is the "let the caller compute `darkTheme: Boolean`" path the ticket's Technical Notes prefers.

### Settings subtitle (`SettingsScreen.kt`)

The Theme row's `supporting = "System"` literal is replaced with a value driven by the flow. The recommended seam: hoist `themeMode` into `SettingsScreen`'s parameter list rather than injecting `AppPreferences` inside the composable, matching the project's stateless-composable convention (CLAUDE.md → "Hoist state to the ViewModel; UI receives `state: UiState` and `onEvent: (Event) -> Unit`").

Contract:

- `SettingsScreen(onBack, onOpenLicense, themeMode: ThemeMode = ThemeMode.SYSTEM, modifier = Modifier)` — add `themeMode` parameter with a `SYSTEM` default so the existing two `@Preview` composables at the bottom of the file keep compiling without edits.
- The Routes.SETTINGS NavHost entry in `MainActivity.kt` injects `AppPreferences`, collects the same flow with `collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)`, and passes the value into `SettingsScreen(themeMode = …)`. Yes, this means the flow is collected twice in the activity (once for the theme resolution at the composition root, once inside the Settings nav entry) — that's correct and cheap; `DataStore` deduplicates, and the alternative (drilling the value from the root through `PyryNavHost` into a single `composable`) adds boilerplate to a flow that only one screen consumes.
- Inside `SettingsScreen`, a `private` top-level helper maps the enum to the contract label (no `stringResource` — these labels are not localized in this slice, and the sibling picker ticket reuses the same strings to avoid drift):
  ```kotlin
  private fun ThemeMode.label(): String = when (this) {
      ThemeMode.SYSTEM -> "System default"
      ThemeMode.LIGHT -> "Light"
      ThemeMode.DARK -> "Dark"
  }
  ```
- The Theme row becomes `SettingsRow(headline = "Theme", supporting = themeMode.label(), trailing = { ChevronIcon() }, onClick = {})` — `onClick = {}` stays (row remains non-interactive in this slice per AC).

### Data flow (one screen of recomposition)

```
DataStore (disk)
   │
   ▼  Flow<Preferences>
AppPreferences.themeMode: Flow<ThemeMode>
   │
   ├──► MainActivity setContent — collectAsStateWithLifecycle
   │      │
   │      ▼  when(value): SYSTEM→isSystemInDarkTheme(), LIGHT→false, DARK→true
   │    PyrycodeMobileTheme(darkTheme = …) ──► color scheme
   │
   └──► MainActivity composable(Routes.SETTINGS) — collectAsStateWithLifecycle
          │
          ▼  themeMode parameter
        SettingsScreen ──► Theme row supporting = themeMode.label()
```

## State + concurrency model

- **Flow shape.** Cold `Flow<ThemeMode>` derived from `dataStore.data` via `map`. No `stateIn` / hot-flow wrapping at the repository layer — the consumers handle hot conversion with `collectAsStateWithLifecycle`.
- **Dispatchers.** `DataStore` runs its reads on its own internal IO dispatcher; the `map { … }` projection is cheap (enum lookup + string compare). Nothing to schedule explicitly.
- **Setter scope.** `setThemeMode` is `suspend` and writes via `dataStore.edit { … }` — no `viewModelScope` is involved in this slice because the row stays non-interactive. The sibling picker ticket adds a `rememberCoroutineScope().launch { prefs.setThemeMode(…) }` callsite when the dialog confirms.
- **Lifecycle.** `collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)` is used at both consumer sites. The `SYSTEM` initial value masks the one-frame gap between activity start and the first DataStore emission, so the app never flashes the wrong scheme.
- **Cancellation.** Both collectors are tied to composition; no manual cleanup. The activity-level collector lives for the lifetime of the activity, which is the desired scope.

## Error handling

The only failure modes touch the flow:

1. **Key absent (first launch).** `prefs[THEME_MODE]` returns `null`. The lookup falls through to `ThemeMode.SYSTEM`. Covered by the default test.
2. **Stored string unparseable** (downgrade, manual edit, future enum-value removal). The lookup returns `SYSTEM`. Covered by the fallback test.
3. **DataStore IO error.** Out of scope — `DataStore`'s contract is to surface read errors through the flow itself; existing `pairedServerExists` doesn't handle them either, and adding error handling here would be premature ([[Evidence-Based Fix Selection]] — no observed failure).

No UI surface for errors. The setter is `suspend` and can throw `IOException` from `dataStore.edit`; the sibling picker ticket will decide whether to surface a snackbar or swallow.

## Testing strategy

All new coverage is unit (`./gradlew test`) in `AppPreferencesTest.kt`, alongside the two existing tests. Same `TemporaryFolder` + real-`PreferenceDataStoreFactory` setup. Three new test scenarios — written as bullet points; the developer translates into JUnit functions in the project's existing style:

- **`themeMode_defaultsToSystem`** — fresh DataStore, no writes. Assert `prefs.themeMode.first() == ThemeMode.SYSTEM`.
- **`setThemeMode_roundTripsAllValues`** — iterate `ThemeMode.entries`; for each `mode`: `prefs.setThemeMode(mode)`, then assert `prefs.themeMode.first() == mode`. Single test function with an in-test loop is preferred over three near-identical functions; AC says "round-trip of each enum value", not "one test per value".
- **`themeMode_unparseableStoredValue_fallsBackToSystem`** — bypass the typed setter and write a garbage string to the underlying key directly:
  ```kotlin
  dataStore.edit { it[stringPreferencesKey("theme_mode")] = "PURPLE" }
  ```
  Then assert `prefs.themeMode.first() == ThemeMode.SYSTEM`. The key constant in `AppPreferences` is private; the test re-creates a `stringPreferencesKey` with the same name `"theme_mode"` — two such instances reference the same underlying entry by key-name equality, so this works without exposing the constant.

No Compose UI test is added. The Settings row subtitle change is covered transitively by the existing `@Preview` composables (compile-time) and by the data-layer tests (behavior). Adding `ComposeTestRule` coverage for a one-line subtitle swap on a non-interactive row would exceed this slice's scope.

No instrumented (`./gradlew connectedAndroidTest`) coverage needed.

## Open questions

- **Should the unparseable-value test also exercise the empty-string case?** The AC says "unparseable" which subsumes both. A single bogus-string test is sufficient; the developer can add an empty-string case if they think it sharpens the contract — it shouldn't change the implementation either way.
- **`stringPreferencesKey` vs an int-typed key holding an ordinal.** String is correct: ordinals reshuffle if enum values are added/reordered, and the AC explicitly says "string-typed DataStore key holds the enum's `name`."
