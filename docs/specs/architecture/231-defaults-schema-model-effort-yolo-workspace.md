# 231 — "Defaults for new conversations" schema: Model/Effort enums + DataStore keys

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` (whole file, 43 lines) — the exact pattern to mirror; `themeMode` flow + `setThemeMode` setter at lines 21–29 is the canonical template for the four new fields
- `app/src/main/java/de/pyryco/mobile/data/preferences/ThemeMode.kt` — single-line enum file shape for `Model.kt` and `Effort.kt`
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt` (whole file, 96 lines) — test conventions (`TemporaryFolder` + `PreferenceDataStoreFactory`, `runBlocking` + `.first()`, separate `defaultsToX` and `setX_roundTrips` test pairs); the `themeMode_unparseableStoredValue_fallsBackToSystem` test at lines 74–78 is the template for enum fallback tests
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:17-21` — `DEFAULT_SCRATCH_CWD` constant; import this directly, do not re-declare

## Context

Pure data-layer slice for the "Defaults for new conversations" feature (split from #147). Establishes the shared schema that downstream slices will consume:

- Settings UI pickers for model / effort / YOLO / workspace defaults (separate tickets)
- StatusSheet per-conversation overrides — #228 (model) and #229 (effort) currently stub these defaults pending this schema landing
- The new-conversation consumer slice that reads these defaults when materializing a new `Conversation`

No UI, no consumer wiring, no `DefaultsBundle` abstraction. Each of the four fields is its own `Flow` + `suspend` setter on `AppPreferences`, exactly mirroring the existing `themeMode` / `setThemeMode` / `useWallpaperColors` / `setUseWallpaperColors` shape.

## Design

### New file: `app/src/main/java/de/pyryco/mobile/data/preferences/Model.kt`

```kotlin
package de.pyryco.mobile.data.preferences

enum class Model { OPUS_4_7, SONNET_4_6, HAIKU_4_5 }
```

### New file: `app/src/main/java/de/pyryco/mobile/data/preferences/Effort.kt`

```kotlin
package de.pyryco.mobile.data.preferences

enum class Effort { LOW, MEDIUM, HIGH, XHIGH, MAX }
```

### `AppPreferences.kt` additions

Add (in this order, after the existing `useWallpaperColors` block, before the `companion object`):

- `val defaultModel: Flow<Model>` — read `DEFAULT_MODEL` string key, map via `Model.entries.firstOrNull { it.name == stored } ?: Model.OPUS_4_7` (mirrors `themeMode` lines 21–25)
- `suspend fun setDefaultModel(model: Model)` — writes `model.name` to `DEFAULT_MODEL`
- `val defaultEffort: Flow<Effort>` — same shape, falls back to `Effort.HIGH`
- `suspend fun setDefaultEffort(effort: Effort)` — writes `effort.name` to `DEFAULT_EFFORT`
- `val defaultYolo: Flow<Boolean>` — reads `DEFAULT_YOLO`, fallback `false` (mirrors `pairedServerExists` lines 14–15)
- `suspend fun setDefaultYolo(enabled: Boolean)` — writes `DEFAULT_YOLO`
- `val defaultWorkspace: Flow<String>` — reads `DEFAULT_WORKSPACE`, fallback `DEFAULT_SCRATCH_CWD` (import from `de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD`)
- `suspend fun setDefaultWorkspace(cwd: String)` — writes `DEFAULT_WORKSPACE`

New companion-object key constants (added to existing block):

```kotlin
val DEFAULT_MODEL = stringPreferencesKey("default_model")
val DEFAULT_EFFORT = stringPreferencesKey("default_effort")
val DEFAULT_YOLO = booleanPreferencesKey("default_yolo")
val DEFAULT_WORKSPACE = stringPreferencesKey("default_workspace")
```

### Design decision: defer `.label()` extension

The ticket calls this out explicitly. **Deferred.** The first UI consumer (Settings model picker) will introduce label strings — and that consumer is the right place to decide whether labels live as a Kotlin extension, an Android string resource, or a Compose-side lookup. Adding a `.label()` here now would either (a) be unused dead code, or (b) bake `String` literals into the data layer that may want to be Android string resources later. `CLAUDE.md` calls Compose Multiplatform a walk-back trigger and asks us to keep `data/` portable — label strings are display concerns, not data-layer concerns.

The enum identifiers (`OPUS_4_7`, `SONNET_4_6`, `HAIKU_4_5`) document the model unambiguously for any non-UI consumer (e.g. #228 reading `defaultModel` to seed StatusSheet state). UI display strings land with the first UI consumer.

### Design decision: `defaultWorkspace` is `Flow<String>`, not a richer type

The sentinel `DEFAULT_SCRATCH_CWD` is just a `String` constant, and downstream consumers (#228/#229 stubs and the future workspace picker) work in terms of `cwd: String`. A `sealed Workspace { Scratch; Bound(cwd: String) }` would be premature abstraction for one slice of work; reuse the existing string-sentinel convention from `Conversation.cwd`.

## State + concurrency model

No new state machinery. Each field follows the existing pattern:

- Read: `dataStore.data.map { ... }` — cold `Flow`, recomposes on every preference write that changes the mapped value (DataStore deduplicates internally).
- Write: `dataStore.edit { ... }` inside a `suspend` function — caller chooses scope (typically `viewModelScope.launch { prefs.setDefaultModel(...) }`).
- No `viewModelScope` jobs introduced. No hot flows. No dispatcher switching — DataStore handles IO internally.

## Error handling

- **Unknown stored enum value** (manual edit, schema migration, downgrade) — falls back to the documented default (`Model.OPUS_4_7` / `Effort.HIGH`). Same pattern as `themeMode` line 24.
- **Missing key** (never written) — falls back to the documented default via `?:`.
- **DataStore IO errors** — propagated by the underlying `dataStore.data` flow per DataStore's contract; no swallowing. The existing `themeMode` / `useWallpaperColors` flows don't catch either, so we stay consistent.

## Testing strategy

Unit tests only (`./gradlew test`) — `AppPreferences` is pure Kotlin, no Android dependencies, runs on the JVM. Extend `AppPreferencesTest.kt`; no new test file.

Reuse the existing `TemporaryFolder` + `PreferenceDataStoreFactory.create` setup at lines 22–43 (each `@Test` already gets a fresh DataStore via `@Before`).

Add the following tests (per the existing `defaultsToX` + `setX_roundTrips` + unparseable-fallback pattern):

- `defaultModel_defaultsToOpus47` — assert initial value is `Model.OPUS_4_7`.
- `setDefaultModel_roundTripsAllValues` — loop over `Model.entries`, set and re-read each.
- `defaultModel_unparseableStoredValue_fallsBackToOpus47` — write a garbage string via `dataStore.edit { it[stringPreferencesKey("default_model")] = "GPT5" }`, assert flow emits `Model.OPUS_4_7`.
- `defaultEffort_defaultsToHigh` — assert initial value is `Effort.HIGH`.
- `setDefaultEffort_roundTripsAllValues` — loop over `Effort.entries`.
- `defaultEffort_unparseableStoredValue_fallsBackToHigh` — same shape as the model fallback test.
- `defaultYolo_defaultsToFalse` — assert initial value is `false`.
- `setDefaultYolo_roundTripsBothValues` — set true → read true; set false → read false; set true → read true (mirrors `setUseWallpaperColors_roundTripsBothValues` at lines 87–95).
- `defaultWorkspace_defaultsToScratchCwd` — assert initial value is `DEFAULT_SCRATCH_CWD`.
- `setDefaultWorkspace_roundTripsCustomCwd` — set `"/home/user/code/myproj"`, read it back; set `DEFAULT_SCRATCH_CWD`, read it back.

Ten new tests total. No instrumented tests, no MockK, no fakes.

## Open questions

None — the ticket's one explicit architect call (the `.label()` extension) is resolved above (defer to first UI consumer).
