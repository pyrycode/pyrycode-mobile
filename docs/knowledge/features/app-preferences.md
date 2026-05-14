# App preferences

Typed wrapper around a single shared `DataStore<Preferences>` for app-level key-value state. The home for non-secret app settings that survive process death (pairing-completed flag today; theme, notifications later in Phase 3).

## What it does

Exposes app-level preferences as typed `Flow<T>` reads + `suspend fun` writes. Two preferences today:

- `pairedServerExists: Flow<Boolean>` — `false` by default; flips to `true` once the Scanner screen records a successful server pairing (#12). Read by `MainActivity`'s composition root to decide the `NavHost` start destination between `welcome` and `channel_list` (#13), and reserved for `Settings` (Phase 3) to surface pairing state.
- `themeMode: Flow<ThemeMode>` — `ThemeMode.SYSTEM` by default (#86); persisted as the enum's `name` under `stringPreferencesKey("theme_mode")`. Both "key absent" and "stored string not in `ThemeMode.entries`" fall through to `SYSTEM` via `ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM` — no throw, no `runCatching`. Read at two surfaces: at `MainActivity.setContent`'s root, an `appPreferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)` resolves `darkTheme: Boolean` for `PyrycodeMobileTheme(...)` (preserving `isSystemInDarkTheme()` on `SYSTEM`); since #87 the Settings route reads it via `koinViewModel<SettingsViewModel>().themeMode.collectAsStateWithLifecycle()` (a `StateFlow` projection over the same upstream — see [Settings ViewModel](settings-viewmodel.md)). The matching `suspend fun setThemeMode(mode: ThemeMode)` is wired in #87 by `SettingsViewModel.onSelectTheme(...)`, called from the Settings → Theme picker dialog's confirm button; one write fans out to both collectors above.

Secrets (the pairing token itself) are explicitly **not** stored here — that's a separate security-sensitive ticket using EncryptedSharedPreferences / the Keystore. `AppPreferences` is only for non-secret booleans/strings/ints.

## How it works

Two Koin singletons:

1. A `DataStore<Preferences>` bound to the on-disk file `<filesDir>/datastore/app_prefs.preferences_pb` (filename `app_prefs`; DataStore appends `.preferences_pb`).
2. `AppPreferences`, which takes the `DataStore<Preferences>` in its constructor and exposes typed accessors.

```kotlin
// de/pyryco/mobile/data/preferences/AppPreferences.kt
class AppPreferences(private val dataStore: DataStore<Preferences>) {

    val pairedServerExists: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[PAIRED_SERVER_EXISTS] ?: false }

    suspend fun setPairedServerExists(value: Boolean) {
        dataStore.edit { prefs -> prefs[PAIRED_SERVER_EXISTS] = value }
    }

    val themeMode: Flow<ThemeMode> =
        dataStore.data.map { prefs ->
            val stored = prefs[THEME_MODE]
            ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[THEME_MODE] = mode.name }
    }

    private companion object {
        val PAIRED_SERVER_EXISTS = booleanPreferencesKey("paired_server_exists")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
```

`ThemeMode` is a sibling enum file in the same package:

```kotlin
// de/pyryco/mobile/data/preferences/ThemeMode.kt
enum class ThemeMode { SYSTEM, LIGHT, DARK }
```

No companion methods on the enum — label mapping ("System default" / "Light" / "Dark") lives at the Settings call site, and dark/light resolution lives at the composition root (`when (themeMode) { SYSTEM -> isSystemInDarkTheme(); LIGHT -> false; DARK -> true }`). `PyrycodeMobileTheme`'s signature stays `darkTheme: Boolean`; the caller computes the boolean.

```kotlin
// de/pyryco/mobile/di/AppModule.kt (excerpt)
single<DataStore<Preferences>> {
    PreferenceDataStoreFactory.create(
        produceFile = { androidContext().preferencesDataStoreFile("app_prefs") },
    )
}
single { AppPreferences(get()) }
```

Reads are reactive: collectors receive the current persisted value on subscription (DataStore's replay-1) and a new emission on every `edit { }`. Writes are `suspend` and durable when the call returns.

## Adding a preference

1. Add a private key constant to the `companion object`:
   ```kotlin
   val DARK_THEME = booleanPreferencesKey("dark_theme")
   ```
   Pick the right type-safe builder for the value: `booleanPreferencesKey`, `intPreferencesKey`, `stringPreferencesKey`, `floatPreferencesKey`, `longPreferencesKey`, or `stringSetPreferencesKey`. **Do not** `stringPreferencesKey` + `.toBoolean()` shortcuts. For enum-typed prefs, use `stringPreferencesKey` storing `.name` — never `intPreferencesKey` storing an ordinal. Ordinals reshuffle on enum reorder/removal and silently corrupt the stored value across upgrades. See `themeMode` for the canonical shape.
2. Add a `Flow<T>` reader with an explicit default via elvis:
   ```kotlin
   val darkTheme: Flow<Boolean> =
       dataStore.data.map { prefs -> prefs[DARK_THEME] ?: false }
   ```
3. Add the matching `suspend` setter:
   ```kotlin
   suspend fun setDarkTheme(value: Boolean) {
       dataStore.edit { prefs -> prefs[DARK_THEME] = value }
   }
   ```
4. Add a unit test mirroring `AppPreferencesTest`: default-on-miss + round-trip.

Once `AppPreferences` accumulates ~5 keys, consider splitting by domain (`AppPreferences` + `ThemePreferences` + `NotificationPreferences`), each backed by its **own** `DataStore<Preferences>` file binding in `AppModule.kt`. Don't pre-abstract over preference classes — rule of three.

## Configuration

- **Dependency:** `androidx.datastore:datastore-preferences` (alias `androidx-datastore-preferences`, version `1.1.7`). Pinned for Kotlin `2.2.10` + AGP `9.2.1`. If a later bump trips an unresolved-artifact or stdlib-alignment warning, bump *up* to the latest stable `1.1.x` / `1.2.x` (`./gradlew --refresh-dependencies assembleDebug`). Do not downgrade below `1.1.x` — the typed Preferences API stabilised there.
- **Flavor:** Preferences (locked by Stack Decision). Not Proto. The typed-key API (`booleanPreferencesKey` …) is the only sanctioned path.
- **On-disk filename:** `app_prefs` — part of the storage contract from #11 onward. Renaming orphans every installed user's state.
- **Injection:** consumers get `AppPreferences` via Koin (`koinInject()` in composables, `by inject()` / `get()` in non-Compose code). They do **not** depend on `DataStore<Preferences>` directly; the wrapper is the seam.

## Usage

```kotlin
// In MainActivity's composition root (#13's NavHost-start-destination gate — one-shot read)
val appPreferences = koinInject<AppPreferences>()
val paired: Boolean? by produceState<Boolean?>(initialValue = null, appPreferences) {
    value = appPreferences.pairedServerExists.first()
}
when (val v = paired) {
    null -> Surface(Modifier.fillMaxSize()) {}                  // neutral placeholder during first emit
    else -> PyryNavHost(if (v) Routes.CHANNEL_LIST else Routes.WELCOME)
}

// In #12's Scanner destination on tap (no ViewModel — see Scanner screen feature doc)
val appPreferences = koinInject<AppPreferences>()
val scope = rememberCoroutineScope()
// ...inside onTap:
scope.launch {
    appPreferences.setPairedServerExists(true)
    navController.navigate(Routes.CHANNEL_LIST) {
        popUpTo(Routes.SCANNER) { inclusive = true }
        launchSingleTop = true
    }
}
```

Collecting reactively is the right shape when a screen genuinely needs live re-composition on flag flips. `collectAsStateWithLifecycle` is on the classpath today via `lifecycle-runtime-compose` (pulled in by a prior ticket; earlier revisions of this doc called it absent — that caveat is stale as of #86):

```kotlin
val themeMode by appPrefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
```

Pass the enum's neutral default (the same value the cold flow would emit first on a fresh DataStore) as `initialValue` — that closes the one-frame gap between activity start and the first DataStore emission, so the UI never flashes the wrong scheme / wrong subtitle.

## State + concurrency

- **Cold-to-hot Flow.** `pairedServerExists` is cold; on collection it emits the current persisted value first, then a new value on each subsequent `edit { }`.
- **Dispatcher.** DataStore's internal scope runs on `Dispatchers.IO`. Collectors don't need to switch — collecting from `Main` is idiomatic.
- **Writes serialise.** Concurrent `edit { }` calls from multiple coroutines are serialised by DataStore. The wrapper does not add its own mutex.
- **Lifecycle.** DataStore's scope outlives any individual collector or `viewModelScope`. Process death is the only teardown.
- **Default-on-miss.** `prefs[KEY] ?: <default>` handles cold start without a sentinel write — the first launch reads `false` without writing anything to disk.

## Edge cases / limitations

- **No corruption handler installed.** `PreferenceDataStoreFactory.create` accepts a `corruptionHandler: ReplaceFileCorruptionHandler<Preferences>?` parameter — currently `null`. If on-disk corruption is ever observed in the field, the right fix is a single line at the binding site. Evidence-based — not adding a defense for an unobserved failure mode.
- **No `.catch { }` on read paths.** Non-cancellation throwables propagate; callers decide what to do (today: nothing — Phase 3 may add a UI banner once a real failure mode appears).
- **Not a secret store.** Anything sensitive (tokens, credentials) belongs in EncryptedSharedPreferences / Keystore, not here.
- **Compose Multiplatform walk-back.** `AppPreferences` itself is portable Kotlin (depends only on DataStore + coroutines). The Android-specific `androidContext().preferencesDataStoreFile(...)` lives in `AppModule.kt` — if the walk-back happens, only the Koin binding needs replacing.

## Related

- Ticket notes: `../codebase/11.md`, `../codebase/12.md` (first write site), `../codebase/13.md` (first read site — `NavHost` start-destination gate), `../codebase/86.md` (second key — `themeMode`; first reactive-collect consumer), `../codebase/87.md` (first `setThemeMode` write site — Settings Theme picker dialog via `SettingsViewModel`)
- Spec: `docs/specs/architecture/11-datastore-app-preferences.md`; `docs/specs/architecture/86-theme-mode-preference.md`; `docs/specs/architecture/87-settings-theme-picker-dialog.md`
- DI feature: `dependency-injection.md`
- First consumers: #12 (Scanner pairing-write — merged), #13 (conditional `NavHost` start destination — merged), #86 (`themeMode` flow → `PyrycodeMobileTheme(darkTheme = …)` + Settings Theme row subtitle — merged), #87 (`setThemeMode` write site — merged).
- Phase 3: notification + remaining `Settings` preferences will land as additional keys here (or as sibling classes once this file passes ~5 keys per the splitting rule above).
