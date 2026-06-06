# App preferences

Typed wrapper around a single shared `DataStore<Preferences>` for app-level key-value state. The home for non-secret app settings that survive process death (appearance + per-conversation defaults today; more in Phase 3). Paired-server state moved out to the encrypted PairedServerStore in #295.

## What it does

Exposes app-level preferences as typed `Flow<T>` reads + `suspend fun` writes. Seven preferences today, in three groups — the two foundational appearance keys, the four "Defaults for new conversations" keys added in #231, and the first Notifications key (`notificationsEnabled`, added in #268):

- `themeMode: Flow<ThemeMode>` — `ThemeMode.SYSTEM` by default (#86); persisted as the enum's `name` under `stringPreferencesKey("theme_mode")`. Both "key absent" and "stored string not in `ThemeMode.entries`" fall through to `SYSTEM` via `ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM` — no throw, no `runCatching`. Read at two surfaces: at `MainActivity.setContent`'s root, an `appPreferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)` resolves `darkTheme: Boolean` for `PyrycodeMobileTheme(...)` (preserving `isSystemInDarkTheme()` on `SYSTEM`); since #87 the Settings route reads it via `koinViewModel<SettingsViewModel>().themeMode.collectAsStateWithLifecycle()` (a `StateFlow` projection over the same upstream — see [Settings ViewModel](settings-viewmodel.md)). The matching `suspend fun setThemeMode(mode: ThemeMode)` is wired in #87 by `SettingsViewModel.onSelectTheme(...)`, called from the Settings → Theme picker dialog's confirm button; one write fans out to both collectors above.
- `useWallpaperColors: Flow<Boolean>` — `false` by default (#88); `booleanPreferencesKey("use_wallpaper_colors")`. Read at two surfaces: at `MainActivity.setContent`'s root as a sibling to the `themeMode` collector, then forwarded into `PyrycodeMobileTheme(darkTheme = …, dynamicColor = useWallpaperColors)`; and since #89 inside `composable(Routes.SETTINGS)` via `koinViewModel<SettingsViewModel>().useWallpaperColors.collectAsStateWithLifecycle()` (a `StateFlow` projection over the same upstream — see [Settings ViewModel](settings-viewmodel.md)). The theme's pre-existing SDK gate (`dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` at `Theme.kt:275`) handles the "Android < 12 OR preference false → brand palette" branch internally, so no composition-root version check is needed. Matching `suspend fun setUseWallpaperColors(enabled: Boolean)` is wired in #89 by `SettingsViewModel.onToggleUseWallpaperColors(...)`, called from the Settings → Appearance "Use Material You dynamic color" switch row's `onCheckedChange` (headline updated from the prior "Use wallpaper colors" in #163 to match Figma `17:2`); one write fans out to both collectors above.

"Defaults for new conversations" keys (#231, schema-only — no UI/consumer wiring yet; read by the eventual new-conversation materialiser and by the StatusSheet override stubs #228/#229):

- `defaultModel: Flow<Model>` — `Model.OPUS_4_7` by default; `stringPreferencesKey("default_model")` holding `.name`. Tolerant-unknown fallback via `Model.entries.firstOrNull { it.name == stored } ?: Model.OPUS_4_7` — same shape as `themeMode`. Matching `suspend fun setDefaultModel(model: Model)` was wired by [#232](../codebase/232.md)'s Settings model-picker slice via `SettingsViewModel.onSelectDefaultModel(...)`. The first **read** consumer landed earlier in [#253](../codebase/253.md) — [`ThreadViewModel.selectedModelFlow`](thread-screen.md) pre-combines this flow with an in-memory `MutableStateFlow<Model?>` per-conversation override before folding into the main `combine` — so a Settings-side write from [#232](../codebase/232.md) now fans out to both the Settings row's own subtitle and the StatusSheet model section on every open conversation. `Model` (`{ OPUS_4_7, SONNET_4_6, HAIKU_4_5 }`) in `data/preferences/Model.kt` gained a top-level `fun Model.label(): String` extension in [#253](../codebase/253.md) — see [the (partially walked-back) `.label()` decision below](#design-decision-defer-label-extensions-on-data-layer-enums).
- `defaultEffort: Flow<Effort>` — `Effort.HIGH` by default; `stringPreferencesKey("default_effort")` holding `.name`. Same tolerant-unknown fallback shape. Matching `suspend fun setDefaultEffort(effort: Effort)`. `Effort` (`{ LOW, MEDIUM, HIGH, XHIGH, MAX }`) lives in `data/preferences/Effort.kt`; the `fun Effort.label(): String` extension lives one package over at `ui/settings/EffortPickerDialog.kt:77` (originally `internal` per [#233](../codebase/233.md); widened to top-level public in [#229](../codebase/229.md) when the StatusSheet `FilterChip` row became the second consumer — see [the design decision below](#design-decision-defer-label-extensions-on-data-layer-enums) for the per-enum walk-back rule).
- `defaultYolo: Flow<Boolean>` — `false` by default; `booleanPreferencesKey("default_yolo")`. Mirrors `useWallpaperColors`. Matching `suspend fun setDefaultYolo(enabled: Boolean)`.
- `defaultWorkspace: Flow<String>` — `DEFAULT_SCRATCH_CWD` by default (imported from `de.pyryco.mobile.data.model`, **not** re-declared here); `stringPreferencesKey("default_workspace")`. Holds either the scratch-cwd sentinel or a bound-folder cwd as a plain `String` — reuses the same convention `Conversation.cwd` already encodes, deliberately not a `sealed Workspace { Scratch; Bound(cwd) }` (would be premature abstraction for one slice of work; downstream consumers all work in `String` terms). Matching `suspend fun setDefaultWorkspace(cwd: String)`. **First *read* consumer landed in #240** — [`ChannelListViewModel.CreateDiscussionTapped`](./channel-list-viewmodel.md) reads it via `appPreferences.defaultWorkspace.first()` inside the existing `viewModelScope.launch { … }` and passes it to `repository.createDiscussion(workspace = …)`. **First *write* consumer landed in [#235](../codebase/235.md)** — the Settings "Default workspace" row opens the reused [`WorkspacePicker`](./workspace-picker.md) host and persists the pick via `SettingsViewModel.onSelectDefaultWorkspace(path)` → `setDefaultWorkspace(path)`. With both ends wired the round-trip is closed end-to-end: a path picked in Settings now changes where the next FAB short-press discussion is created (distinguishing `defaultWorkspace` from `defaultYolo`, which stays write-live / read-dead after #234). The long-press FAB (`ChannelListEvent.WorkspacePicked`) intentionally bypasses the read — an explicit per-conversation pick always overrides the default.

Notifications key ([#268](../codebase/268.md)):

- `notificationsEnabled: Flow<Boolean>` — **`true` by default** (`booleanPreferencesKey("notifications_enabled")`); the getter is `prefs[NOTIFICATIONS_ENABLED] ?: true`. **The only boolean in this file that defaults to `true`** — every sibling (`useWallpaperColors`, `defaultYolo`) defaults `false`. The `true` is Figma-sourced: node `17:2` renders the "Push notifications when claude responds" toggle ON (contrasting the OFF Default-YOLO toggle directly above), so persistence must preserve ON-by-default. Matching `suspend fun setNotificationsEnabled(enabled: Boolean)`. Mirrors the `defaultYolo` getter/setter shape exactly *except* the default value — the lesson the [#268](../codebase/268.md) `notificationsEnabled_defaultsToTrue` guard test pins is "copy the sibling's *shape*, source the default from Figma, not from the sibling you cloned." **Write-live but read-dead** (like `defaultYolo`): the Settings switch persists it end-to-end, but nothing reads it to act on — actual notification *delivery* is Phase 4. The first preference outside the pairing/theme + defaults bundle, and the trigger the splitting note below names (see § Adding a preference).

Phase 4 FCM push key ([#364](../codebase/364.md)):

- `pushToken: Flow<String?>` — the phone's FCM registration token, persisted so it survives process death and can be re-registered with the daemon on every reconnect without waiting for Firebase to re-mint it. `stringPreferencesKey("push_token")`. **The first key in this file with no fallback default** — the getter is the bare `dataStore.data.map { prefs -> prefs[PUSH_TOKEN] }` (no `?: …` elvis), so absence reads as `null`. The `null` is **load-bearing**: it is the "dormant / nothing to register yet" signal the connect-time re-registration reader depends on — *not* an oversight to coalesce away with a default. Matching `suspend fun setPushToken(token: String)`; single-key `edit` overwrite means a new value replaces the prior one (last-write-wins, the rotation path [#361] drives via Firebase's `onNewToken`; no history retained). **Dormant today** — no live caller in the shipped app: the *origin* (Firebase `onNewToken` → `setPushToken`) is the #361 Firebase slice, and the *reader* (connect-time re-registration, which then calls [`RemoteConversationRepository.registerPushToken`](remote-conversation-repository.md), the [#359](../codebase/359.md) wire sender) is the orchestration sibling. **Never logged** (AC #5 — a grep-verified code-level invariant, not unit-tested). Stored as a non-secret (see the storage-tier note below), so this is the first `data/preferences/` key whose value comes from outside the user/UI.

Secrets (the pairing token / device static key) are explicitly **not** stored here — those live Keystore-wrapped under `data/crypto/` (the device X25519 static keypair, the paired-server key store). `AppPreferences` is for non-secret booleans/strings/ints. **The FCM `pushToken` is deliberately on this non-secret side, not a contradiction:** an FCM registration token is a device-scoped wake *address*, not a credential — possessing it is insufficient to push a notification (an attacker also needs the project's FCM server key, held only on the daemon/backend), it rotates, and it is overwrite-only. Classified by value × revocability (mirroring KitchenClaw ADR-007's DataStore-for-non-sensitive / encrypted-store-for-auth-tokens split), it belongs in plain `DataStore<Preferences>`. Do **not** "upgrade" it to Keystore — that would miscategorise it against the existing trust boundary. See [#364](../codebase/364.md) for the full security rationale.

## How it works

Two Koin singletons:

1. A `DataStore<Preferences>` bound to the on-disk file `<filesDir>/datastore/app_prefs.preferences_pb` (filename `app_prefs`; DataStore appends `.preferences_pb`).
2. `AppPreferences`, which takes the `DataStore<Preferences>` in its constructor and exposes typed accessors.

```kotlin
// de/pyryco/mobile/data/preferences/AppPreferences.kt
class AppPreferences(private val dataStore: DataStore<Preferences>) {

    val themeMode: Flow<ThemeMode> =
        dataStore.data.map { prefs ->
            val stored = prefs[THEME_MODE]
            ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[THEME_MODE] = mode.name }
    }

    val useWallpaperColors: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[USE_WALLPAPER_COLORS] ?: false }

    suspend fun setUseWallpaperColors(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[USE_WALLPAPER_COLORS] = enabled }
    }

    val defaultModel: Flow<Model> =
        dataStore.data.map { prefs ->
            val stored = prefs[DEFAULT_MODEL]
            Model.entries.firstOrNull { it.name == stored } ?: Model.OPUS_4_7
        }

    suspend fun setDefaultModel(model: Model) {
        dataStore.edit { prefs -> prefs[DEFAULT_MODEL] = model.name }
    }

    val defaultEffort: Flow<Effort> =
        dataStore.data.map { prefs ->
            val stored = prefs[DEFAULT_EFFORT]
            Effort.entries.firstOrNull { it.name == stored } ?: Effort.HIGH
        }

    suspend fun setDefaultEffort(effort: Effort) {
        dataStore.edit { prefs -> prefs[DEFAULT_EFFORT] = effort.name }
    }

    val defaultYolo: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[DEFAULT_YOLO] ?: false }

    suspend fun setDefaultYolo(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DEFAULT_YOLO] = enabled }
    }

    val notificationsEnabled: Flow<Boolean> =                       // #268 — note the `?: true`
        dataStore.data.map { prefs -> prefs[NOTIFICATIONS_ENABLED] ?: true }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[NOTIFICATIONS_ENABLED] = enabled }
    }

    val defaultWorkspace: Flow<String> =
        dataStore.data.map { prefs -> prefs[DEFAULT_WORKSPACE] ?: DEFAULT_SCRATCH_CWD }

    suspend fun setDefaultWorkspace(cwd: String) {
        dataStore.edit { prefs -> prefs[DEFAULT_WORKSPACE] = cwd }
    }

    val pushToken: Flow<String?> =                                  // #364 — note: NO `?: default`
        dataStore.data.map { prefs -> prefs[PUSH_TOKEN] }

    suspend fun setPushToken(token: String) {
        dataStore.edit { prefs -> prefs[PUSH_TOKEN] = token }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val USE_WALLPAPER_COLORS = booleanPreferencesKey("use_wallpaper_colors")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val DEFAULT_EFFORT = stringPreferencesKey("default_effort")
        val DEFAULT_YOLO = booleanPreferencesKey("default_yolo")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val DEFAULT_WORKSPACE = stringPreferencesKey("default_workspace")
        val PUSH_TOKEN = stringPreferencesKey("push_token")
    }
}
```

Sibling enum files in the same package — `ThemeMode` is identifier-only; `Model` carries a top-level `fun Model.label(): String` extension since [#253](../codebase/253.md), and `Effort`'s sibling `fun Effort.label(): String` lives one package over in `ui/settings/EffortPickerDialog.kt:77` (originally `internal` per #233, widened to public in [#229](../codebase/229.md)):

```kotlin
// de/pyryco/mobile/data/preferences/ThemeMode.kt
enum class ThemeMode { SYSTEM, LIGHT, DARK }

// de/pyryco/mobile/data/preferences/Model.kt          (#231; .label() added #253)
enum class Model { OPUS_4_7, SONNET_4_6, HAIKU_4_5 }

fun Model.label(): String =
    when (this) {
        Model.OPUS_4_7 -> "Opus 4.7"
        Model.SONNET_4_6 -> "Sonnet 4.6"
        Model.HAIKU_4_5 -> "Haiku 4.5"
    }

// de/pyryco/mobile/data/preferences/Effort.kt         (#231)
enum class Effort { LOW, MEDIUM, HIGH, XHIGH, MAX }

// de/pyryco/mobile/ui/settings/EffortPickerDialog.kt  (#233 internal; #229 widened to public)
fun Effort.label(): String =
    when (this) {
        Effort.LOW -> "low"
        Effort.MEDIUM -> "medium"
        Effort.HIGH -> "high"
        Effort.XHIGH -> "xhigh"
        Effort.MAX -> "max"
    }
```

`ThemeMode`'s label mapping ("System" / "Light" / "Dark", per the `internal ThemeMode.label()` extension at the bottom of `SettingsScreen.kt`; was `"System default"` between #86 and #163) lives at the Settings call site, and dark/light resolution lives at the composition root (`when (themeMode) { SYSTEM -> isSystemInDarkTheme(); LIGHT -> false; DARK -> true }`). `PyrycodeMobileTheme`'s signature stays `darkTheme: Boolean`; the caller computes the boolean. <a id="design-decision-defer-label-extensions-on-data-layer-enums"></a>**Original [#231](../codebase/231.md) convention:** `Model` and `Effort` followed the same convention by design — the first UI consumer (Settings model/effort pickers) owns label strings; baking them in here would either be unused dead code or pin `String` literals into the data layer that may want to be Android string resources later. `CLAUDE.md` lists Compose Multiplatform as a walk-back trigger and asks for `data/` to stay portable; display strings are not data-layer concerns.

**[#253](../codebase/253.md) partial walk-back, scoped to `Model`:** when the second UI consumer materialised (sibling slice [#254](../codebase/254.md)'s Status Sheet radio group alongside #253's status-row label derivation), the deferral cost flipped — two private helpers across two slices, plus an inevitable third extract-on-third-use later, are more expensive than co-locating `Model.label()` once at 7 LOC. The labels (`"Opus 4.7"`, `"Sonnet 4.6"`, `"Haiku 4.5"`) are product-vendor names that don't localise — they ship in the same form across locales the same way "Anthropic" or "Claude" would, so the portability concern is mooted for `Model` specifically.

**[#229](../codebase/229.md) partial walk-back, scoped to `Effort`:** when the second UI consumer materialised (the StatusSheet `FilterChip` row from #229 alongside the [#233](../codebase/233.md) `EffortPickerDialog` + Settings row subtitle), the same threshold was crossed for `Effort`. Rather than promote the extension to `data/preferences/Effort.kt` next to the enum, [#229](../codebase/229.md) widened the existing `EffortPickerDialog.kt:77` extension from `internal` to top-level public — minimum-diff one-line visibility change, same surface cost as moving it. The labels (`"low"` / `"medium"` / `"high"` / `"xhigh"` / `"max"`) are vendor-neutral CLI flag names that don't localise the way navigation strings do — the portability concern is mooted the same way it was for `Model`. **The walk-back is scoped to `Model` and `Effort` only**; `ThemeMode` still has one UI consumer and keeps its package-local `internal` extension. Re-evaluate per enum when a second consumer materialises — the rule is *not* "co-locate by default", it's "co-locate when ≥2 callers ship in the same release window and the strings are vendor-stable". The identifier shape (`OPUS_4_7`, `HIGH`) still documents the value unambiguously for non-UI consumers.

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
   **Exception — meaningful `null` (since [#364](../codebase/364.md)):** drop the `?: default` and type the reader `Flow<T?>` *only* when absence is itself semantically load-bearing (a key downstream branches on as "unset"). `pushToken: Flow<String?>` is the first such key — `null` means "nothing to register yet". Don't reach for this by default: a missing appearance/defaults preference always wants its neutral fallback, not `null`. The test then asserts `assertNull` on a fresh store (see `pushToken_defaultsToNull`) instead of the default value.
3. Add the matching `suspend` setter:
   ```kotlin
   suspend fun setDarkTheme(value: Boolean) {
       dataStore.edit { prefs -> prefs[DARK_THEME] = value }
   }
   ```
4. Add a unit test mirroring `AppPreferencesTest`: default-on-miss + round-trip.

Once `AppPreferences` accumulates ~5 keys, consider splitting by domain (`AppPreferences` + `ThemePreferences` + `NotificationPreferences`), each backed by its **own** `DataStore<Preferences>` file binding in `AppModule.kt`. Don't pre-abstract over preference classes — rule of three.

**Current count: 9 keys (post-#364).** The threshold is crossed but the split stays deferred. The four #231 keys (`defaultModel`, `defaultEffort`, `defaultYolo`, `defaultWorkspace`) form one logical group ("defaults for new conversations") consumed together; splitting them across two preference classes would force re-stitching imports within the same feature. **The note used to say "re-evaluate when the first non-defaults Phase-3 key (notifications) lands" — that key landed at [#268](../codebase/268.md), and the split was *not* evaluated:** the ticket was scoped as XS plumbing mirroring `defaultYolo` and added `notificationsEnabled` inline. So the named seam (between `AppPreferences` = pairing/theme + defaults bundle, and a hypothetical `NotificationPreferences`) has now been reached and passed unevaluated — the same deferral pattern as the [`SettingsViewModel`](settings-viewmodel.md) sealed-state lift. The split is now a future dedicated-refactor decision, not something a feature slice will naturally trigger; revisit only if the Notifications group grows a second or third key that would benefit from its own DataStore file.

## Configuration

- **Dependency:** `androidx.datastore:datastore-preferences` (alias `androidx-datastore-preferences`, version `1.1.7`). Pinned for Kotlin `2.2.10` + AGP `9.2.1`. If a later bump trips an unresolved-artifact or stdlib-alignment warning, bump *up* to the latest stable `1.1.x` / `1.2.x` (`./gradlew --refresh-dependencies assembleDebug`). Do not downgrade below `1.1.x` — the typed Preferences API stabilised there.
- **Flavor:** Preferences (locked by Stack Decision). Not Proto. The typed-key API (`booleanPreferencesKey` …) is the only sanctioned path.
- **On-disk filename:** `app_prefs` — part of the storage contract from #11 onward. Renaming orphans every installed user's state.
- **Injection:** consumers get `AppPreferences` via Koin (`koinInject()` in composables, `by inject()` / `get()` in non-Compose code). They do **not** depend on `DataStore<Preferences>` directly; the wrapper is the seam.

## Usage

```kotlin
// In MainActivity's composition root (#13's NavHost start-destination gate; #295 moved this read to PairedServerStore)
val pairedServerStore = koinInject<PairedServerStore>()
val paired: Boolean? by produceState<Boolean?>(initialValue = null, pairedServerStore) {
    value = pairedServerStore.load() != null
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

- **Cold-to-hot Flow.** Each preference flow (e.g. `themeMode`) is cold; on collection it emits the current persisted value first, then a new value on each subsequent `edit { }`.
- **Dispatcher.** DataStore's internal scope runs on `Dispatchers.IO`. Collectors don't need to switch — collecting from `Main` is idiomatic.
- **Writes serialise.** Concurrent `edit { }` calls from multiple coroutines are serialised by DataStore. The wrapper does not add its own mutex.
- **Lifecycle.** DataStore's scope outlives any individual collector or `viewModelScope`. Process death is the only teardown.
- **Default-on-miss.** `prefs[KEY] ?: <default>` handles cold start without a sentinel write — the first launch reads `false` without writing anything to disk.

## Edge cases / limitations

- **No corruption handler installed.** `PreferenceDataStoreFactory.create` accepts a `corruptionHandler: ReplaceFileCorruptionHandler<Preferences>?` parameter — currently `null`. If on-disk corruption is ever observed in the field, the right fix is a single line at the binding site. Evidence-based — not adding a defense for an unobserved failure mode.
- **No `.catch { }` on read paths.** Non-cancellation throwables propagate; callers decide what to do (today: nothing — Phase 3 may add a UI banner once a real failure mode appears).
- **Not a secret store.** Anything sensitive (the pairing token, the device static key, credentials) belongs Keystore-wrapped under `data/crypto/`, not here. **Not every token is a secret, though:** the FCM `pushToken` (#364) is a low-value, rotating wake *address* and lives here by design — classify by value × revocability, not by "it's called a token." See the storage-tier note under § What it does.
- **Compose Multiplatform walk-back.** `AppPreferences` itself is portable Kotlin (depends only on DataStore + coroutines). The Android-specific `androidContext().preferencesDataStoreFile(...)` lives in `AppModule.kt` — if the walk-back happens, only the Koin binding needs replacing.

## Related

- Ticket notes: `../codebase/11.md`, `../codebase/12.md` (first write site), `../codebase/13.md` (first read site — `NavHost` start-destination gate), `../codebase/86.md` (second key — `themeMode`; first reactive-collect consumer), `../codebase/87.md` (first `setThemeMode` write site — Settings Theme picker dialog via `SettingsViewModel`), `../codebase/88.md` (third key — `useWallpaperColors`; second sibling collector at `setContent` root → `PyrycodeMobileTheme(dynamicColor = …)`), `../codebase/89.md` (first `setUseWallpaperColors` write site — Settings Use-wallpaper-colors switch row via `SettingsViewModel.onToggleUseWallpaperColors`), `../codebase/231.md` (keys 4–7 — "Defaults for new conversations" schema: `defaultModel`, `defaultEffort`, `defaultYolo`, `defaultWorkspace` + `Model` / `Effort` sibling enums), `../codebase/232.md` (first `setDefaultModel` writer — Settings `ModelPickerDialog` row via `SettingsViewModel.onSelectDefaultModel`), `../codebase/240.md` (first `defaultWorkspace` consumer — `ChannelListViewModel.CreateDiscussionTapped` reads via `.first()` inside the existing `viewModelScope.launch { … }` and passes to `repository.createDiscussion(workspace = …)`; long-press picker path unchanged — explicit user pick still overrides the default)
- Spec: `docs/specs/architecture/11-datastore-app-preferences.md`; `docs/specs/architecture/86-theme-mode-preference.md`; `docs/specs/architecture/87-settings-theme-picker-dialog.md`; `docs/specs/architecture/88-use-wallpaper-colors-preference.md`; `docs/specs/architecture/89-settings-use-wallpaper-colors-switch.md`; `docs/specs/architecture/231-defaults-schema-model-effort-yolo-workspace.md`
- DI feature: `dependency-injection.md`
- First consumers: #12 (Scanner pairing-write — merged), #13 (conditional `NavHost` start destination — merged), #86 (`themeMode` flow → `PyrycodeMobileTheme(darkTheme = …)` + Settings Theme row subtitle — merged), #87 (`setThemeMode` write site — merged), #88 (`useWallpaperColors` flow → `PyrycodeMobileTheme(dynamicColor = …)` — merged), #89 (`setUseWallpaperColors` write site — Settings → Appearance switch row via `SettingsViewModel` — merged), #231 (`defaultModel` / `defaultEffort` / `defaultYolo` / `defaultWorkspace` schema landed — merged), [#233](../codebase/233.md) (first `defaultEffort` read+write consumer — Settings `EffortPickerDialog` row; declared the `internal fun Effort.label()` extension at `EffortPickerDialog.kt:77` per the first-UI-consumer convention — merged), #240 (first `defaultWorkspace` read consumer — `ChannelListViewModel.CreateDiscussionTapped` short-press FAB — merged), [#253](../codebase/253.md) (first `defaultModel` read consumer — [`ThreadViewModel.selectedModelFlow`](thread-screen.md) pre-combines with an in-memory `MutableStateFlow<Model?>` per-conversation override before folding into the main `combine`; also added `fun Model.label()` extension co-located with the enum — merged), [#254](../codebase/254.md) (StatusSheet Model section — second `Model.label()` consumer, triggered the #253 walk-back — merged), [#229](../codebase/229.md) (second `defaultEffort` read consumer — `ThreadViewModel.selectedEffortFlow` mirroring `selectedModelFlow`'s shape; the StatusSheet's `FilterChip` row became the second `Effort.label()` consumer, triggering the `internal → public` walk-back on the same extension. Also reads — and explicitly **ignores** — `defaultYolo` per the architectural single-writer invariant on `ThreadViewModel.yoloEnabled` — merged), [#232](../codebase/232.md) (first `setDefaultModel` writer — Settings `ModelPickerDialog` row via `SettingsViewModel.onSelectDefaultModel(...)`; closes the loop on the [#253](../codebase/253.md) read consumer by giving it a Settings-side writer — merged), [#234](../codebase/234.md) (first `setDefaultYolo` writer — Settings Default-YOLO switch row; write-live but read-dead, no consumer acts on it — merged), [#235](../codebase/235.md) (first `setDefaultWorkspace` writer — Settings "Default workspace" row opens the reused [`WorkspacePicker`](workspace-picker.md) host via `SettingsViewModel.onSelectDefaultWorkspace(...)`; closes the `defaultWorkspace` write→read loop with the #240 reader — merged). Still pending: the new-conversation materialiser that reads `defaultModel` / `defaultEffort` / `defaultYolo` together (`defaultYolo` is currently dead code — write-live but no read consumer; #229's `ThreadViewModel.yoloEnabled` is intentionally hardcoded `false`).
- [#268](../codebase/268.md) (first Notifications key — `notificationsEnabled` flow + `setNotificationsEnabled` setter + `booleanPreferencesKey("notifications_enabled")`, the **first definer + first writer in one slice**; the only `?: true` default in the file; consumed by the Settings "Push notifications when claude responds" switch via `SettingsViewModel.onTogglePushNotifications`; write-live / read-dead until Phase 4 notification delivery — merged)
- [#364](../codebase/364.md) (Phase 4 FCM push — `pushToken: Flow<String?>` + `setPushToken` setter + `stringPreferencesKey("push_token")`, the **first key with no fallback default** — `null` is the load-bearing "nothing to register yet" signal; the first key whose value originates outside the user/UI (Firebase); deliberately plain-DataStore non-secret per the storage-tier classification; **dormant** — origin is the #361 Firebase slice, reader is the connect-time re-registration sibling that calls [`RemoteConversationRepository.registerPushToken`](remote-conversation-repository.md) ([#359](../codebase/359.md)); never logged — merged)
- Phase 3+: remaining `Settings` preferences and notification *delivery* (Phase 4) will land as additional keys here (or as sibling classes once the Notifications group grows past one key — see the splitting rule above; the first-non-defaults-key trigger already fired at #268 without prompting a split).
