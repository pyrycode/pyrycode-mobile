# App preferences

Typed wrapper around a single shared `DataStore<Preferences>` for app-level key-value state. The home for non-secret app settings that survive process death (pairing-completed flag today; theme, notifications later in Phase 3).

## What it does

Exposes app-level preferences as typed `Flow<T>` reads + `suspend fun` writes. Today there is one preference:

- `pairedServerExists: Flow<Boolean>` — `false` by default; flips to `true` once the Scanner screen records a successful server pairing. Used by the conditional `NavHost` start destination (#13) to decide between the Welcome flow and the channel list, and by `Settings` (Phase 3) to surface pairing state.

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

    private companion object {
        val PAIRED_SERVER_EXISTS = booleanPreferencesKey("paired_server_exists")
    }
}
```

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
   Pick the right type-safe builder for the value: `booleanPreferencesKey`, `intPreferencesKey`, `stringPreferencesKey`, `floatPreferencesKey`, `longPreferencesKey`, or `stringSetPreferencesKey`. **Do not** `stringPreferencesKey` + `.toBoolean()` shortcuts.
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
// In a ViewModel (#13's NavHost decision)
private val prefs: AppPreferences by inject()
viewModelScope.launch {
    val paired = prefs.pairedServerExists.first()
    startDestination = if (paired) Routes.ChannelList else Routes.Welcome
}

// In #12's Scanner ViewModel on successful pairing
viewModelScope.launch {
    prefs.setPairedServerExists(true)
}
```

Collecting reactively is also fine when a screen needs live updates:

```kotlin
val paired by appPrefs.pairedServerExists.collectAsStateWithLifecycle(initialValue = false)
```

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

- Ticket notes: `../codebase/11.md`
- Spec: `docs/specs/architecture/11-datastore-app-preferences.md`
- DI feature: `dependency-injection.md`
- First consumers: #13 (conditional `NavHost` start destination), #12 (Scanner pairing-write).
- Phase 3: theme + notification preferences will land as sibling classes in `data/preferences/`.
