# Spec — DataStore-backed `AppPreferences` (#11)

## Files to read first

- `gradle/libs.versions.toml` — version catalog. Add one `[versions]` entry (`datastore`) and one `[libraries]` entry (`androidx-datastore-preferences`). Mirror the alphabetic placement style used in spec #32.
- `app/build.gradle.kts:42-55` — `dependencies { ... }` block. One new `implementation(libs.androidx.datastore.preferences)` line, alphabetically inside the `androidx-*` group (after `androidx.core.ktx`, before `androidx.lifecycle.runtime.ktx`).
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` — current state is the empty `appModule` from #32 with three TODO-style comment lines. Replace the `#11 — AppPreferences (DataStore)` comment line with two `single { ... }` lines. Leave the `#4` and "upcoming UI tickets" comment lines until those tickets land.
- `app/src/main/java/de/pyryco/mobile/PyryApp.kt` — read once. **Do not modify.** Confirms `androidContext(this@PyryApp)` is already wired by #32, so `androidContext()` inside `appModule` resolves at startup time.
- `docs/specs/architecture/32-koin-di-scaffold.md` (Dependency wiring section + "Edit list") — mirror the catalog/build edit style. Same pattern: add `[versions]` entry, add `[libraries]` entry, add `implementation(...)` line, no `[bundles]`.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:1-22` — existing unit-test style: plain JUnit 4 + `runBlocking` + `kotlinx.coroutines.flow.first`. Match it; do not introduce `kotlinx-coroutines-test` for one test.
- `CLAUDE.md` (Stack, Don't sections) — confirms DataStore (Preferences flavor) is the locked choice, MVI conventions, and that `data/` must stay portable (Compose Multiplatform walk-back trigger). `AppPreferences` lives outside `data/` to keep `data/` Android-free.

## Context

Three downstream consumers will read/write app-level boolean state:

- **#13** — conditional `NavHost` start destination keyed on whether a paired server has been recorded.
- **#12** — Scanner screen, which writes `pairedServerExists = true` on successful pairing.
- **Phase 3 Settings** — eventually surfaces this and adds others (theme, notifications).

Each of those tickets would otherwise have to wire up `PreferenceDataStoreFactory` themselves. Landing a single shared `DataStore<Preferences>` + typed wrapper now means #12 and #13 each cost a one-line Koin injection and a single `.first()` / `.setX(...)` call.

The `pairingToken` itself (a secret) is **explicitly out of scope** — see ticket "Out of scope". This ticket only records the non-secret boolean fact that pairing has completed. Storing the token will be a separate security-sensitive ticket using EncryptedSharedPreferences or the Keystore-backed equivalent.

## Design

### Dependency wiring

**`gradle/libs.versions.toml`** — additions only:

Add to `[versions]`:

```toml
datastore = "1.1.7"
```

Add to `[libraries]`, alphabetically inside the `androidx-*` block (after `androidx-core-ktx`, before `androidx-espresso-core`):

```toml
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
```

Pin guidance for the developer: `1.1.7` is a known-good stable on the 1.1 line for Kotlin `2.2.10` + AGP `9.2.1`. If Gradle reports an unresolved artifact or a Kotlin-stdlib alignment warning, bump to the latest stable `1.1.x` or `1.2.x` patch (`./gradlew --refresh-dependencies assembleDebug`). Do not downgrade below `1.1.x` — the typed `Preferences` API used by this spec stabilised there.

**`app/build.gradle.kts`** — one new line in `dependencies { ... }`:

```kotlin
implementation(libs.androidx.datastore.preferences)
```

Placement: alphabetically inside the `androidx-*` group, between `implementation(libs.androidx.core.ktx)` and `implementation(libs.androidx.lifecycle.runtime.ktx)`. No test-scope DataStore dependency — production artifact is used by the unit test too (tmp-file-backed).

### `AppPreferences.kt` (new file)

Path: `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt`.

Rationale for path: a new `data/preferences/` subpackage rather than living in `data/repository/`. Repository is for conversation-domain data; preferences is a distinct concern (key-value app state). The subpackage anticipates Phase 3 Settings adding sibling classes (`ThemePreferences`, etc.) without polluting `data/repository/`.

**Note on `data/` portability**: CLAUDE.md says "keep `data/` portable — Compose Multiplatform is a walk-back trigger". `AppPreferences` itself is portable Kotlin (depends only on the DataStore API and `kotlinx.coroutines.Flow`). The **Android binding** (constructing the `DataStore<Preferences>` from a `Context`) lives in `di/AppModule.kt`, not inside `AppPreferences`. If the walk-back happens, only the Koin binding needs a multiplatform replacement; `AppPreferences` itself crosses cleanly.

Shape:

```kotlin
package de.pyryco.mobile.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

Design notes:

- **Constructor takes `DataStore<Preferences>`, not `Context`.** This is the testability seam — the unit test constructs a tmp-file-backed `DataStore<Preferences>` and hands it in. If `AppPreferences` took a `Context`, the test would need Robolectric or instrumentation; the AC explicitly forbids that ("uses an in-memory or `tmp`-backed DataStore, not a real Android context").
- **`Flow<Boolean>` is hot from DataStore's perspective.** DataStore emits the current value on first collection and re-emits on every `edit { }`. Default value (`false`) comes from the `?: false` elvis, not a separate "default seed" write — this avoids a startup write on first launch.
- **Keys declared as a `private companion object` constant.** Anchors the string `"paired_server_exists"` once, so a renamed property doesn't silently break stored data and so the key string isn't visible to other classes. `booleanPreferencesKey` is the type-safe DataStore API — do not use `stringPreferencesKey` + `.toBoolean()`.
- **No `Result` / no error wrapper.** DataStore swallows `IOException`s into its own retry/replay layer; surfacing a `Result<Boolean>` here would be theatre. If reading the file ever throws a non-IO exception (corruption), letting it propagate is correct — Phase 3 can add a `.catch { }` once a real failure mode is observed (evidence-based).
- **No injection-context coupling.** The class doesn't know it lives behind Koin; it could equally be constructed manually in a test or migrated to Hilt without changes.
- **Single key today.** Adding the second preference (Phase 3 theme) follows the same shape: one private key constant, one `val xxx: Flow<T>`, one `suspend fun setXxx(value: T)`. No abstraction needed until ~5 keys (rule of three).

### `AppModule.kt` edit

Path: `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` (existing file from #32).

Final state after this ticket:

```kotlin
package de.pyryco.mobile.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import de.pyryco.mobile.data.preferences.AppPreferences
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create(
            produceFile = { androidContext().preferencesDataStoreFile("app_prefs") },
        )
    }
    single { AppPreferences(get()) }
    // Bindings added by downstream tickets:
    //   #4  — FakeConversationRepository as ConversationRepository
    //   upcoming UI tickets — ViewModels via viewModel { }
}
```

Diff from current:

- Remove the `#11 — AppPreferences (DataStore)` line from the comment block (it's now satisfied).
- Add five imports (alphabetically positioned).
- Add two `single { ... }` blocks above the comment block.

Design notes:

- **Two singles, not one inlined.** The `DataStore<Preferences>` is its own singleton so that Phase 3 Settings (which will read the same `app_prefs` file) can inject the same instance. DataStore enforces a one-instance-per-file invariant at runtime — constructing two `DataStore`s pointing at the same file throws. Separating the binding now is a one-line cost that prevents a future architect from having to retro-factor it. Two consumers, one shared file → one DataStore singleton.
- **`preferencesDataStoreFile("app_prefs")`** resolves to `<app filesDir>/datastore/app_prefs.preferences_pb` — the canonical Android location for DataStore-Preferences files. The filename `app_prefs` (no extension; DataStore appends `.preferences_pb`) anchors the storage location; renaming it later orphans existing user data. Treat it as part of the on-disk contract from this ticket onward.
- **Why not the `Context.preferencesDataStore(name = ...)` delegate?** That delegate creates a hidden `Context`-extension singleton, which is not visible to Koin and forces consumers to access it via `context.dataStore` instead of constructor injection. Using `PreferenceDataStoreFactory.create` directly is the documented escape hatch for DI frameworks.
- **No `scope` parameter on `PreferenceDataStoreFactory.create`.** Default is `CoroutineScope(Dispatchers.IO + SupervisorJob())`, which is correct for app-scoped state. Overriding would invite leaks (a `viewModelScope` would tear down DataStore on screen exit; an explicit `GlobalScope` is exactly what the default gives you with cleaner semantics).
- **No corruption handler.** The constructor accepts a `corruptionHandler: ReplaceFileCorruptionHandler<Preferences>?` parameter — leave null. Evidence-based: no corruption observed; if it surfaces, that's a separate ticket with a real failure mode to design around.

### Other files

`PyryApp.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `ConversationRepository.kt`, `FakeConversationRepository.kt`, theme files — **not touched.** The Koin scaffold from #32 (`startKoin { androidContext(...) modules(appModule) }`) is already in place and remains correct.

## State + concurrency model

- **`AppPreferences.pairedServerExists`** is a cold `Flow<Boolean>` that becomes hot on collection. Each collector receives the current persisted value as its first emission (DataStore's replay-1 semantics) and a new emission on each subsequent `edit { }`. ViewModels in #13 will collect it via `.first()` for the start-destination decision, or `collectAsStateWithLifecycle()` if a screen ever needs reactive updates.
- **`setPairedServerExists`** is `suspend` because `DataStore.edit` is. Callers (#12's Scanner ViewModel) invoke it from `viewModelScope.launch`. The write is durable when the suspend returns — DataStore flushes before resuming.
- **Dispatcher**: DataStore's internal scope runs on `Dispatchers.IO`. The Flow does not require the collector to be on IO — collecting from Main is fine and idiomatic. No manual dispatcher switching at any call site.
- **Cancellation**: DataStore's scope outlives any individual collector. Cancelling a `viewModelScope` cancels the collection but does not close the DataStore. Process death is the only teardown.
- **Concurrency safety**: Concurrent `edit { }` calls from multiple coroutines are serialised by DataStore. The wrapper does not need its own mutex.

## Error handling

- **Read path**: any non-cancellation throwable propagates through the Flow. This ticket does not add a `.catch { }`. If on-disk file corruption ever happens, the right fix is a `ReplaceFileCorruptionHandler` at the Koin `PreferenceDataStoreFactory.create` site — defer until observed.
- **Write path**: `edit { }` can throw `IOException` on disk-full or permission failure. Callers in #12 will need to decide whether to surface a banner; that's their concern, not this wrapper's. No try/catch here.
- **Default-on-miss**: `prefs[PAIRED_SERVER_EXISTS] ?: false` handles the cold-start case (no file yet, no key written) without a sentinel write. This is the only "error-like" path that touches user-visible behaviour and it's handled explicitly.

## Testing strategy

One new unit test file: `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt`. JUnit 4 + `runBlocking`, matching the existing repository-test style. No `kotlinx-coroutines-test` dependency — overkill for two assertions.

Shape:

```kotlin
package de.pyryco.mobile.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppPreferencesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmp.newFile("app_prefs.preferences_pb") },
        )
        prefs = AppPreferences(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun pairedServerExists_defaultsToFalse() = runBlocking {
        assertEquals(false, prefs.pairedServerExists.first())
    }

    @Test
    fun setPairedServerExists_true_isReflectedInNextEmit() = runBlocking {
        prefs.setPairedServerExists(true)
        assertEquals(true, prefs.pairedServerExists.first())
    }
}
```

Test-design notes:

- **`TemporaryFolder` JUnit rule** gives each test its own disposable directory. The DataStore is bound to a fresh file per test — no inter-test leakage.
- **`scope = CoroutineScope(Dispatchers.IO + Job())`** is the test-owned scope for DataStore's internal work. `@After` cancels it so the test process doesn't leak background coroutines between tests.
- **`runBlocking`** matches the existing test style and is fine for two suspending assertions. Switching to `runTest` would force a `TestDispatcher` on DataStore and add a `kotlinx-coroutines-test` `testImplementation` line — not worth it for this scope.
- **`tmp.newFile(...)` is called inside `produceFile = { ... }`** rather than at `@Before` time. That's a defensive nicety: `PreferenceDataStoreFactory.create` is lazy about calling `produceFile`, so the file is created on first read/write, not at setup.
- **No mocking** (no MockK, no Mockito). The fake-vs-mock preference in the rest of the codebase is fakes; this test uses the real `DataStore` against a real (tmp) file, which is the strongest form of that preference.

Build-level signals:

- **`./gradlew test`** — must pass; runs `AppPreferencesTest` along with the existing suite.
- **`./gradlew assembleDebug`** — must pass; confirms the catalog/build edits resolve and that the Koin binding compiles against the real Koin/DataStore APIs.

Manual smoke (optional, not required for AC): `./gradlew installDebug` and verify the app launches as before. There are no consumers yet, so the only signal is "still launches".

## Edit list (developer's checklist)

1. `gradle/libs.versions.toml` — add `datastore = "1.1.7"` to `[versions]`; add `androidx-datastore-preferences` alias to `[libraries]`.
2. `app/build.gradle.kts` — add `implementation(libs.androidx.datastore.preferences)` inside the `androidx-*` group of `dependencies { ... }`.
3. `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` — new file (new `data/preferences/` package), body as shown above.
4. `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` — add five imports, add the two `single { ... }` blocks, remove the `#11 — AppPreferences (DataStore)` comment line.
5. `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt` — new file, body as shown above.
6. Run `./gradlew assembleDebug` then `./gradlew test`. Both must pass.

## Open questions

None. The wrapper shape, the two-singles binding, the test pattern, and the package placement are all determined by existing conventions (CLAUDE.md, spec #32, the FakeConversationRepositoryTest style). If `datastore = "1.1.7"` doesn't resolve, follow the bump guidance — no architect re-run needed for a patch bump.
