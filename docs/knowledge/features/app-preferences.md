# App preferences

Typed wrapper around a single shared `DataStore<Preferences>` for non-secret settings that survive process death. Workspace defaults are stored by host; appearance, model, effort, YOLO and notification preferences remain app-wide. Paired-server state lives in the encrypted [PairedServerStore](paired-server-store.md).

## What it does

Exposes preferences as typed `Flow<T>` reads + `suspend fun` writes, covering appearance, defaults for new conversations and notifications:

- `themeMode: Flow<ThemeMode>` — `ThemeMode.DARK` by default
  ([#1147](../../specs/architecture/1147-default-dark-mode.md)); both a missing
  `theme_mode` key and an unknown stored name read as Dark. Saved `SYSTEM`, `LIGHT`
  and `DARK` names remain stored across restart and upgrade; the key, enum names
  and setter are unchanged, so no migration is needed. The app root always uses
  the static dark palette, regardless of the saved choice or Android night mode.
  [Settings ViewModel](settings-viewmodel.md) starts at Dark before the saved
  value loads, then uses that value for the Theme subtitle and picker selection,
  keeping all three choices. The matching
  `suspend fun setThemeMode(mode: ThemeMode)` is called through
  `SettingsViewModel.onSelectTheme(...)` when the picker is confirmed; one write
  updates the Settings projection and stored value without changing the palette.
- `useWallpaperColors: Flow<Boolean>` — `false` by default (#88); `booleanPreferencesKey("use_wallpaper_colors")`. [Settings ViewModel](settings-viewmodel.md) collects the saved value for the Appearance switch. `MainActivity` no longer collects it: the root passes `dynamicColor = false` to `PyrycodeMobileTheme`, so a saved `true` remains stored and visible in Settings but cannot enable wallpaper colours at runtime. Matching `suspend fun setUseWallpaperColors(enabled: Boolean)` is wired through `SettingsViewModel.onToggleUseWallpaperColors(...)` from the Settings → Appearance "Use Material You dynamic color" switch row's `onCheckedChange`.

"Defaults for new conversations" preferences:

- `defaultModel: Flow<Model>` — `Model.OPUS_4_7` by default; `stringPreferencesKey("default_model")` holding `.name`. Tolerant-unknown fallback via `Model.entries.firstOrNull { it.name == stored } ?: Model.OPUS_4_7` — same shape as `themeMode`. Matching `suspend fun setDefaultModel(model: Model)` was wired by [#232](../codebase/232.md)'s Settings model-picker slice via `SettingsViewModel.onSelectDefaultModel(...)`. `Model` (`{ OPUS_4_7, SONNET_4_6, HAIKU_4_5 }`) in `data/preferences/Model.kt` gained a top-level `fun Model.label(): String` extension in [#253](../codebase/253.md) — see [the (partially walked-back) `.label()` decision below](#design-decision-defer-label-extensions-on-data-layer-enums). **Between [#253](../codebase/253.md) and [#807](../codebase/807.md)**, [`ThreadViewModel.selectedModelFlow`](thread-screen.md) pre-combined this flow with an in-memory per-conversation override, so a Settings-side write fanned out to both the Settings row's own subtitle and the thread's Status sheet. **[#807](../codebase/807.md) deleted that thread-side read** — the thread's model now comes from the daemon's own session settings, never from this device. `defaultModel` is read today only by `SettingsViewModel` itself (its own picker row's current selection + subtitle) — write-live and read-live for Settings, but with exactly one consumer, the same posture `defaultYolo` already had.
- `defaultEffort: Flow<Effort>` — `Effort.HIGH` by default; `stringPreferencesKey("default_effort")` holding `.name`. Same tolerant-unknown fallback shape. Matching `suspend fun setDefaultEffort(effort: Effort)`. `Effort` (`{ LOW, MEDIUM, HIGH, XHIGH, MAX }`) lives in `data/preferences/Effort.kt`; the `fun Effort.label(): String` extension lives one package over at `ui/settings/EffortPickerDialog.kt:77` (originally `internal` per [#233](../codebase/233.md); widened to top-level public in [#229](../codebase/229.md) when the StatusSheet `FilterChip` row became the second consumer — see [the design decision below](#design-decision-defer-label-extensions-on-data-layer-enums) for the per-enum walk-back rule). **[#807](../codebase/807.md) retired the thread-side read the same way it did for `defaultModel`** — `defaultEffort` is now consumed only by `SettingsViewModel`'s own picker.
- `defaultYolo: Flow<Boolean>` — `false` by default; `booleanPreferencesKey("default_yolo")`. Mirrors `useWallpaperColors`. Matching `suspend fun setDefaultYolo(enabled: Boolean)`. Write-live, read-dead — no consumer acts on it; [#807](../codebase/807.md) did not change this, since the thread never read it in the first place (see [thread-composer-footer.md § Sourcing](thread-composer-footer.md#sourcing)).
- `defaultWorkspace(serverId: String): Flow<String>` — a host's default workspace,
  using the same plain `String` path or `DEFAULT_SCRATCH_CWD` sentinel as
  `Conversation.cwd`. Import the sentinel from `data.model`; do not redefine it.
  The matching suspend operations are `setDefaultWorkspace(serverId, cwd)` and
  `removeDefaultWorkspace(serverId)`, both returning `Result<Unit>`.

  **Identity and fallback.** Keys use `default_workspace_host:<serverId>` with the
  exact saved server id: no trimming, case folding, host labels or relay URLs.
  Missing or removed values always read as scratch, before and after migration;
  host reads never fall back to the unqualified path. Updating or removing one
  host's value leaves other hosts and app-wide preferences unchanged, including
  after reopening the store.

  **One-time migration.** The caller explicitly invokes the suspend
  `migrateDefaultWorkspace(initialServerIds: Set<String>): Result<Unit>` with its
  initial saved-host snapshot. The first successful call permanently records the
  sole id as legacy owner, even if no legacy path exists. Zero or multiple ids
  permanently record no owner. An existing legacy path is copied only to that
  owner and only when its host key is absent; an existing value, including an
  explicit scratch value, wins. Ownership, transfer, completion and removal of
  the old `default_workspace` key commit in one DataStore edit. Repeated calls,
  restart or later pairing cannot repeat the transfer or reassign ownership.

  **Transitional legacy API.** The unqualified `defaultWorkspace: Flow<String>`
  property and `setDefaultWorkspace(cwd: String)` keep using `default_workspace`
  until migration succeeds. Afterwards they resolve the recorded owner's current
  host key from the same read snapshot or write transaction. Host-keyed updates
  and removal therefore appear through the legacy flow too; removal yields
  scratch while retaining ownership, so a later legacy write still targets that
  host. With no owner, legacy reads return scratch and writes are no-ops, while
  explicit host reads and writes remain available.

  **Consumer integration.** [MainActivity startup](navigation.md#how-it-works)
  loads the full saved-host collection and awaits successful migration before
  exposing pairing or creation. Production
  [ChannelListViewModel](channel-list-viewmodel.md) creation reads
  `defaultWorkspace(serverId).first()` for its captured host, including `demo`;
  explicit workspace picks bypass the default. [SettingsViewModel](settings-viewmodel.md)
  read and wrote the unqualified legacy pair until
  [#714](https://github.com/pyrycode/pyrycode-mobile/issues/714) closed the
  #713/#749 split: its `defaultWorkspace` projection and `onSelectDefaultWorkspace`
  handler now read and write `defaultWorkspace(ownerServerId)` /
  `setDefaultWorkspace(ownerServerId, path)` for the Settings destination's
  captured owner, editing through the same [workspace picker](workspace-picker.md)
  host as before; a destination that captured no host reads the scratch sentinel
  and writes nothing. No production caller reads or writes the unqualified pair
  today — it remains only as the transitional migration contract described above,
  exercised directly by `HostWorkspacePreferencesTest`.
  See the [storage contract plan](../../specs/architecture/711-host-default-workspaces.md).

Notifications key ([#268](../codebase/268.md)):

- `notificationsEnabled: Flow<Boolean>` — **`true` by default** (`booleanPreferencesKey("notifications_enabled")`); the getter is `prefs[NOTIFICATIONS_ENABLED] ?: true`. **The only boolean in this file that defaults to `true`** — every sibling (`useWallpaperColors`, `defaultYolo`) defaults `false`. The `true` is Figma-sourced: node `17:2` renders the "Push notifications when claude responds" toggle ON (contrasting the OFF Default-YOLO toggle directly above), so persistence must preserve ON-by-default. Matching `suspend fun setNotificationsEnabled(enabled: Boolean)`. Mirrors the `defaultYolo` getter/setter shape exactly *except* the default value — the lesson the [#268](../codebase/268.md) `notificationsEnabled_defaultsToTrue` guard test pins is "copy the sibling's *shape*, source the default from Figma, not from the sibling you cloned." **Read since [#685](../../specs/architecture/685-mobile-attention-alerts.md):** it is one of `AttentionNotifier`'s three post-time gates (foreground → this flow → `POST_NOTIFICATIONS`), and `PyryNavHost`'s channel-list `LaunchedEffect` reads it to decide whether to ask for notification permission at all. The first preference outside the pairing/theme + defaults bundle, and the trigger the splitting note below names (see § Adding a preference).
- `notificationPermissionAsked: Flow<Boolean>` — **`false` by default**, **only-ever-set** to `true` ([#685](../../specs/architecture/685-mobile-attention-alerts.md)): `booleanPreferencesKey("notification_permission_asked")`; getter `prefs[NOTIFICATION_PERMISSION_ASKED] ?: false`; `suspend fun setNotificationPermissionAsked()` takes no argument, because there is no path back to `false`. Records only that Android's `POST_NOTIFICATIONS` system prompt has been shown once, regardless of the answer — a denial is not distinguished from a grant here; `ContextCompat.checkSelfPermission` is the live grant check, read separately at each gate. Read by `PyryNavHost`'s channel-list one-time-ask condition (`shouldAskNotificationPermission(enabled, granted, asked)`, `MainActivity.kt`) and written by the one `rememberNotificationPermissionRequest` helper both the Settings switch and that channel-list check call through, so "asked" means asked from either place. See [Push messaging service § Attention alerts and the tap route](push-messaging-service.md#attention-alerts-and-the-tap-route-685).

Phase 4 FCM push key ([#364](../codebase/364.md)):

- `pushToken: Flow<String?>` — the phone's FCM registration token, persisted so it survives process death and can be re-registered with the daemon on every reconnect without waiting for Firebase to re-mint it. `stringPreferencesKey("push_token")`. **The first key in this file with no fallback default** — the getter is the bare `dataStore.data.map { prefs -> prefs[PUSH_TOKEN] }` (no `?: …` elvis), so absence reads as `null`. The `null` is **load-bearing**: it is the "dormant / nothing to register yet" signal the connect-time re-registration reader depends on — *not* an oversight to coalesce away with a default. Matching `suspend fun setPushToken(token: String)`; single-key `edit` overwrite means a new value replaces the prior one (last-write-wins, no history retained). **Live since [#361](../codebase/361.md).** Both writers go through the same sink, [`PushTokenSink.onNewToken`](push-messaging-service.md): `PyryMessagingService.onNewToken` on a rotation, and, since [#1102](https://github.com/pyrycode/pyrycode-mobile/issues/1102), [`PushTokenRefresher`](push-messaging-service.md) on a request it makes itself at start and on each foreground while this flow reads empty — the sink's own app-lifetime scope means either caller's write survives its own caller being destroyed right after. The reader is this same `Flow`, observed directly by [`RelayRepositoryCoordinator`](relay-repository-coordinator-seams-and-passthroughs.md#connect-time-fcm-push-token-re-registration-365)'s `pushTokens` param on every open host, so a rotation re-registers without a reconnect; a host that is offline picks up the current value on its next `Open` through the same collector. **Never logged** (AC #5 — a grep-verified code-level invariant, not unit-tested; the sink logs `event=push_token_stored outcome=…` only). Stored as a non-secret (see the storage-tier note below), so this is the first `data/preferences/` key whose value comes from outside the user/UI.

Remembered effort key ([#686](https://github.com/pyrycode/pyrycode-mobile/issues/686)):

- `rememberedEffort: Flow<String?>` — the phone's one remembered effort level, app-wide across chats, channels and hosts, `null` until an effort write has ever succeeded. `stringPreferencesKey("remembered_effort")`, no fallback — the same shape as `pushToken`'s bare `dataStore.data.map { prefs -> prefs[KEY] }`. **Deliberately apart from `defaultEffort`:** the stored value is a daemon-published level string (e.g. `xhigh`), not an `Effort` entry, and it is set only after a `set_session_settings` write the daemon acknowledges — never from `defaultEffort`'s `HIGH` substitute-on-miss, a passive settings reading, or the Settings "Default effort" row. Matching `suspend fun setRememberedEffort(level: String): Result<Unit>`, the `editWorkspace` IOException-to-`Result` posture; logs `event=remembered_effort_set outcome=success|io_failure`, never the level.
- Read and written through an adapter, not directly: `AppPreferences.asRememberedEffortStore()` (`ui/conversations/thread/EffortRecall.kt`) exposes these two members as `RememberedEffortStore`, the interface `ThreadViewModel` depends on (`RememberedEffortStore.None` by default, so the demo path and pre-#686 tests stay inert). The `EffortRecall` collaborator decides once per thread opening whether to write the remembered level through the normal effort write path, and remembers a level only after any effort write — a tap or a recall — is acknowledged. See [Thread composer footer — remembered effort recall](thread-composer-footer-effort-recall.md#remembered-effort-recall-686) for the decision and isolation rules.
- `suspend fun clearRememberedEffort(): Result<Unit>` ([#545](https://github.com/pyrycode/pyrycode-mobile/issues/545)) — removes the `REMEMBERED_EFFORT` key outright, mirroring `setRememberedEffort`'s try/catch-`IOException` shape and logging `event=remembered_effort_cleared outcome=success|io_failure`, never the level. `defaultEffort` is untouched. The only caller is the live e2e suite's `restoreSettings` cleanup, so each of [#545](https://github.com/pyrycode/pyrycode-mobile/issues/545)'s `interactiveTurn_*` methods leaves no remembered level behind for a later method in the same curated run — see [e2e coverage](../../e2e-interactive-stream.md).

Remembered model key ([#1222](../../specs/architecture/1222-remember-acknowledged-model.md)):

- `rememberedModel: Flow<String?>` reads the app-wide `remembered_model` key as the exact daemon-published model string, or `null` before any acknowledged choice. It does not parse through the fixed `Model` enum or fall back to `defaultModel`. The Settings default and this choice remain independent; changing either does not write the other. Unlike remembered effort, opening an existing thread does not read or apply this value. It is stored for the new-chat consumer.
- `setRememberedModel(value: String): Result<Unit>` writes that string verbatim after a thread's model change is acknowledged. A DataStore `IOException` returns failure and leaves the prior value intact; logs record only `event=remembered_model_set outcome=success|io_failure`, never the value. See [Thread composer footer § Remembered model choice](thread-composer-footer.md#remembered-model-choice-1222) for the success-only write boundary.

Secrets (the pairing token / device static key) are explicitly **not** stored here — those live Keystore-wrapped under `data/crypto/` (the device X25519 static keypair, the paired-server key store). `AppPreferences` is for non-secret booleans/strings/ints. **The FCM `pushToken` is deliberately on this non-secret side, not a contradiction:** an FCM registration token is a device-scoped wake *address*, not a credential — possessing it is insufficient to push a notification (an attacker also needs the project's FCM server key, held only on the daemon/backend), it rotates, and it is overwrite-only. Classified by value × revocability (mirroring KitchenClaw ADR-007's DataStore-for-non-sensitive / encrypted-store-for-auth-tokens split), it belongs in plain `DataStore<Preferences>`. Do **not** "upgrade" it to Keystore — that would miscategorise it against the existing trust boundary. See [#364](../codebase/364.md) for the full security rationale.

## How it works

Two Koin singletons:

1. A `DataStore<Preferences>` bound to the on-disk file `<filesDir>/datastore/app_prefs.preferences_pb` (filename `app_prefs`; DataStore appends `.preferences_pb`).
2. `AppPreferences`, which takes the `DataStore<Preferences>` in its constructor and exposes typed accessors.

`MainActivity` passes `darkTheme = true` and `dynamicColor = false` to
`PyrycodeMobileTheme` for every destination. It still injects `AppPreferences`
for workspace migration, but does not collect either appearance preference.
`SettingsViewModel.themeMode` uses `stateIn` with `ThemeMode.DARK` as its initial
value, matching the reader's missing/unknown fallback. A saved choice appears
in Settings when DataStore emits; reading it does not rewrite the stored name
or change the runtime palette.

```kotlin
// de/pyryco/mobile/data/preferences/AppPreferences.kt (app-wide accessor excerpt)
class AppPreferences(private val dataStore: DataStore<Preferences>) {

    val themeMode: Flow<ThemeMode> =
        dataStore.data.map { prefs ->
            val stored = prefs[THEME_MODE]
            ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.DARK
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

    val rememberedEffort: Flow<String?> =                          // #686 — note: NO `?: default`
        dataStore.data.map { prefs -> prefs[REMEMBERED_EFFORT] }

    suspend fun setRememberedEffort(level: String): Result<Unit> =
        try {
            dataStore.edit { prefs -> prefs[REMEMBERED_EFFORT] = level }
            Result.success(Unit)
        } catch (error: IOException) {
            Result.failure(error)
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
        val REMEMBERED_EFFORT = stringPreferencesKey("remembered_effort")
        val DEFAULT_YOLO = booleanPreferencesKey("default_yolo")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
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

`ThemeMode`'s label mapping ("System" / "Light" / "Dark", per the `internal ThemeMode.label()` extension at the bottom of `SettingsScreen.kt`; was `"System default"` between #86 and #163) lives at the Settings call site, while the app root always selects the static dark palette. `PyrycodeMobileTheme` still accepts explicit arguments for previews and isolated components. <a id="design-decision-defer-label-extensions-on-data-layer-enums"></a>**Original [#231](../codebase/231.md) convention:** `Model` and `Effort` followed the same convention by design — the first UI consumer (Settings model/effort pickers) owns label strings; baking them in here would either be unused dead code or pin `String` literals into the data layer that may want to be Android string resources later. `CLAUDE.md` lists Compose Multiplatform as a walk-back trigger and asks for `data/` to stay portable; display strings are not data-layer concerns.

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

Reads are reactive: collectors receive the current persisted value on subscription and subsequent DataStore updates. Writes are `suspend` and durable on success. Host workspace writes, removal and migration return IO failures as `Result.failure`; cancellation propagates. A failed migration commits neither ownership nor transfer and can be retried. Legacy setters retain their throwing error behavior. Workspace operation logs contain static event/outcome codes, never server ids, paths or exception messages.

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

The threshold is crossed but the split stays deferred. Workspace keys and migration metadata share the existing store so the ownership decision and path transfer remain atomic. The four #231 preferences (`defaultModel`, `defaultEffort`, `defaultYolo`, `defaultWorkspace`) form one logical group ("defaults for new conversations") consumed together; splitting them across two preference classes would force re-stitching imports within the same feature. **The note used to say "re-evaluate when the first non-defaults Phase-3 key (notifications) lands" — that key landed at [#268](../codebase/268.md), and the split was *not* evaluated:** the ticket was scoped as XS plumbing mirroring `defaultYolo` and added `notificationsEnabled` inline. So the named seam (between `AppPreferences` = pairing/theme + defaults bundle, and a hypothetical `NotificationPreferences`) has now been reached and passed unevaluated — the same deferral pattern as the [`SettingsViewModel`](settings-viewmodel.md) sealed-state lift. The split is now a future dedicated-refactor decision, not something a feature slice will naturally trigger; revisit only if the Notifications group grows a second or third key that would benefit from its own DataStore file.

## Configuration

- **Dependency:** `androidx.datastore:datastore-preferences` (alias `androidx-datastore-preferences`, version `1.1.7`). Pinned for Kotlin `2.2.10` + AGP `9.2.1`. If a later bump trips an unresolved-artifact or stdlib-alignment warning, bump *up* to the latest stable `1.1.x` / `1.2.x` (`./gradlew --refresh-dependencies assembleDebug`). Do not downgrade below `1.1.x` — the typed Preferences API stabilised there.
- **Flavor:** Preferences (locked by Stack Decision). Not Proto. The typed-key API (`booleanPreferencesKey` …) is the only sanctioned path.
- **On-disk filename:** `app_prefs` — part of the storage contract from #11 onward. Renaming orphans every installed user's state.
- **Injection:** consumers get `AppPreferences` via Koin (`koinInject()` in composables, `by inject()` / `get()` in non-Compose code). They do **not** depend on `DataStore<Preferences>` directly; the wrapper is the seam.

## Usage

The [composition-root gate](navigation.md#how-it-works) owns workspace migration;
callers must not infer legacy ownership from the current selection. The Chats-section
Create confirmation holds the clicked host and sends `createDiscussion(null)` through
that host's repository; the daemon chooses the folder regardless of this saved preference.
Settings still reads and writes per-host defaults for its own controls.

Collecting reactively is the right shape when a screen genuinely needs live re-composition on flag flips. `collectAsStateWithLifecycle` is on the classpath today via `lifecycle-runtime-compose` (pulled in by a prior ticket; earlier revisions of this doc called it absent — that caveat is stale as of #86):

```kotlin
val themeMode by appPrefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
```

Pass the fresh-store default as `initialValue` so the Settings subtitle starts
at Dark. A saved System or Light choice updates the subtitle and picker after
the first DataStore emission; the app stays on the static dark palette.

## State + concurrency

- **Cold-to-hot Flow.** Each preference flow (e.g. `themeMode`) is cold; on collection it emits the current persisted value first, then a new value on each subsequent `edit { }`.
- **Dispatcher.** DataStore's internal scope runs on `Dispatchers.IO`. Collectors don't need to switch — collecting from `Main` is idiomatic.
- **Writes serialise.** Concurrent `edit { }` calls from multiple coroutines are serialised by DataStore. The wrapper does not add its own mutex.
- **Lifecycle.** DataStore's scope outlives any individual collector or `viewModelScope`. Process death is the only teardown.
- **Default-on-miss.** `prefs[KEY] ?: <default>` handles cold start without a sentinel write — the first launch reads `false` without writing anything to disk.

## Testing

`AppPreferencesTest` covers missing and unknown theme names, setter round trips,
and `themeMode_existingStoredNames_surviveRestart`, which reopens temporary stores
seeded with each literal `SYSTEM` / `LIGHT` / `DARK` name and verifies that reads
preserve the serialized value. Literal seeds exercise the existing storage
contract independently of the current setter. The Settings initial-value check
runs both before collection and after loading; see
[SettingsViewModel testing](settings-viewmodel-testing.md).

`MainActivityInsetsDeviceTest.savedAppearanceAndAndroidModeCannotChangeStaticDarkPalette`
launches the real activity at 412 × 892 with saved Light, System and Dark values,
wallpaper colours on and off, and both Android night modes. It checks the static
dark canvas pixel, retained appearance values and an unrelated preference.
The [emulator/Figma comparison](../../../app/src/androidTest/assets/palette-1238/palette-comparison.png)
uses node `15:8` (inspected 2026-09-28); its lower canvas matches, while glow,
system bars and fixture content are outside the root palette contract.

The remembered-model tests cover absence after changing the Settings default,
an out-of-enum value that leaves that default intact, and verbatim persistence
after closing and reopening an on-disk DataStore. A successful setter alone
would not prove the restart requirement.

[HostWorkspacePreferencesTest](../../../app/src/test/java/de/pyryco/mobile/data/preferences/HostWorkspacePreferencesTest.kt)
reopens real temporary DataStore files after cancelling and joining the previous
store's job. Its migration matrix crosses absent/present legacy paths with
absent/custom/scratch host values. Checking only copied paths misses the ownership
contract: even with no old path, compatibility writes must still target the
original sole host after reopening and another migration call. Removing the
completion guard made that case redirect later writes to another host
([#711 test evidence](https://github.com/pyrycode/pyrycode-mobile/pull/716)).

Keep the absent-legacy case and assert a later legacy write reaches the original
owner; a scratch read alone cannot distinguish permanent ownership from no owner.
Likewise, preserve the explicit-scratch case: scratch is a stored choice and must
prevent an old path from being copied over it.

Any restart simulation that cancels one store's owner scope and reopens the same
file must join the first scope's job, not merely cancel it, or the second open
throws `IllegalStateException: There are multiple DataStores active for the same
file` — `cancel()` returns before DataStore's active-file bookkeeping releases the
file. `rememberedEffort_survivesProcessDeath` here and `HostWorkspacePreferencesTest`
above do this correctly; `ThreadViewModelEffortRecallTest.aSuccessfulTap_survivesAnAppRestart`
(`ui/conversations/thread/`) did not and failed on every isolated run until #1075
added the missing `job1.join()`. See [Development verification § Test scheduling
and harnesses](development-verification-test-scheduling.md#test-scheduling-and-harnesses) for the
full mechanism.

## Edge cases / limitations

- **No corruption handler installed.** `PreferenceDataStoreFactory.create` accepts a `corruptionHandler: ReplaceFileCorruptionHandler<Preferences>?` parameter — currently `null`. If on-disk corruption is ever observed in the field, the right fix is a single line at the binding site. Evidence-based — not adding a defense for an unobserved failure mode.
- **No `.catch { }` on read paths.** Non-cancellation throwables propagate; callers decide what to do (today: nothing — Phase 3 may add a UI banner once a real failure mode appears).
- **Not a secret store.** Anything sensitive (the pairing token, the device static key, credentials) belongs Keystore-wrapped under `data/crypto/`, not here. **Not every token is a secret, though:** the FCM `pushToken` (#364) is a low-value, rotating wake *address* and lives here by design — classify by value × revocability, not by "it's called a token." See the storage-tier note under § What it does.
- **Compose Multiplatform walk-back.** `AppPreferences` itself is portable Kotlin (depends only on DataStore + coroutines). The Android-specific `androidContext().preferencesDataStoreFile(...)` lives in `AppModule.kt` — if the walk-back happens, only the Koin binding needs replacing.

## Related

- Ticket notes: `../codebase/11.md`, `../codebase/12.md` (first write site), `../codebase/13.md` (first read site — `NavHost` start-destination gate), `../codebase/86.md` (second key — `themeMode`; first reactive-collect consumer), `../codebase/87.md` (first `setThemeMode` write site — Settings Theme picker dialog via `SettingsViewModel`), `../codebase/88.md` (third key — `useWallpaperColors`; second sibling collector at `setContent` root → `PyrycodeMobileTheme(dynamicColor = …)`), `../codebase/89.md` (first `setUseWallpaperColors` write site — Settings Use-wallpaper-colors switch row via `SettingsViewModel.onToggleUseWallpaperColors`), `../codebase/231.md` (keys 4–7 — "Defaults for new conversations" schema: `defaultModel`, `defaultEffort`, `defaultYolo`, `defaultWorkspace` + `Model` / `Effort` sibling enums), `../codebase/232.md` (first `setDefaultModel` writer — Settings `ModelPickerDialog` row via `SettingsViewModel.onSelectDefaultModel`), `../codebase/240.md` (first `defaultWorkspace` consumer — `ChannelListViewModel.CreateDiscussionTapped` reads via `.first()` inside the existing `viewModelScope.launch { … }` and passes to `repository.createDiscussion(workspace = …)`; long-press picker path unchanged — explicit user pick still overrides the default)
- Spec: `docs/specs/architecture/11-datastore-app-preferences.md`; `docs/specs/architecture/86-theme-mode-preference.md`; `docs/specs/architecture/87-settings-theme-picker-dialog.md`; `docs/specs/architecture/88-use-wallpaper-colors-preference.md`; `docs/specs/architecture/89-settings-use-wallpaper-colors-switch.md`; `docs/specs/architecture/231-defaults-schema-model-effort-yolo-workspace.md`
- DI feature: `dependency-injection.md`
- First consumers: #12 (Scanner pairing-write — merged), #13 (conditional `NavHost` start destination — merged), #86 (`themeMode` flow → `PyrycodeMobileTheme(darkTheme = …)` + Settings Theme row subtitle — merged), #87 (`setThemeMode` write site — merged), #88 (`useWallpaperColors` flow → `PyrycodeMobileTheme(dynamicColor = …)` — merged), #89 (`setUseWallpaperColors` write site — Settings → Appearance switch row via `SettingsViewModel` — merged), #231 (`defaultModel` / `defaultEffort` / `defaultYolo` / `defaultWorkspace` schema landed — merged), [#233](../codebase/233.md) (first `defaultEffort` read+write consumer — Settings `EffortPickerDialog` row; declared the `internal fun Effort.label()` extension at `EffortPickerDialog.kt:77` per the first-UI-consumer convention — merged), #240 (first `defaultWorkspace` read consumer — `ChannelListViewModel.CreateDiscussionTapped` short-press FAB — merged), [#253](../codebase/253.md) (first `defaultModel` read consumer — [`ThreadViewModel.selectedModelFlow`](thread-screen.md) pre-combines with an in-memory `MutableStateFlow<Model?>` per-conversation override before folding into the main `combine`; also added `fun Model.label()` extension co-located with the enum — merged), [#254](../codebase/254.md) (StatusSheet Model section — second `Model.label()` consumer, triggered the #253 walk-back — merged), [#229](../codebase/229.md) (second `defaultEffort` read consumer — `ThreadViewModel.selectedEffortFlow` mirroring `selectedModelFlow`'s shape; the StatusSheet's `FilterChip` row became the second `Effort.label()` consumer, triggering the `internal → public` walk-back on the same extension. Also reads — and explicitly **ignores** — `defaultYolo` per the architectural single-writer invariant on `ThreadViewModel.yoloEnabled` — merged), [#232](../codebase/232.md) (first `setDefaultModel` writer — Settings `ModelPickerDialog` row via `SettingsViewModel.onSelectDefaultModel(...)`; closes the loop on the [#253](../codebase/253.md) read consumer by giving it a Settings-side writer — merged), [#234](../codebase/234.md) (first `setDefaultYolo` writer — Settings Default-YOLO switch row; write-live but read-dead, no consumer acts on it — merged), [#235](../codebase/235.md) (first `setDefaultWorkspace` writer — Settings "Default workspace" row opens the reused [`WorkspacePicker`](workspace-picker.md) host via `SettingsViewModel.onSelectDefaultWorkspace(...)`; closes the `defaultWorkspace` write→read loop with the #240 reader — merged), [`../codebase/807.md`](../codebase/807.md) (**removed** the [#253](../codebase/253.md)/[#229](../codebase/229.md) `ThreadViewModel` read consumers of `defaultModel` / `defaultEffort` — the thread now sources its model and effort from `ConversationRepository.observeSessionSettings` / `observeModelMenu`, never from this device; both flows are now read only by `SettingsViewModel`'s own picker, the same one-consumer posture `defaultYolo` already had — merged). Still pending: the new-conversation materialiser that reads `defaultModel` / `defaultEffort` / `defaultYolo` together (`defaultYolo` is currently dead code — write-live but no read consumer; #229's `ThreadViewModel.yoloEnabled` is intentionally hardcoded `false`).
- [#268](../codebase/268.md) (first Notifications key — `notificationsEnabled` flow + `setNotificationsEnabled` setter + `booleanPreferencesKey("notifications_enabled")`, the **first definer + first writer in one slice**; the only `?: true` default in the file; consumed by the Settings "Push notifications when claude responds" switch via `SettingsViewModel.onTogglePushNotifications`; write-live / read-dead until Phase 4 notification delivery — merged)
- [#364](../codebase/364.md) (Phase 4 FCM push — `pushToken: Flow<String?>` + `setPushToken` setter + `stringPreferencesKey("push_token")`, the **first key with no fallback default** — `null` is the load-bearing "nothing to register yet" signal; the first key whose value originates outside the user/UI (Firebase); deliberately plain-DataStore non-secret per the storage-tier classification; **dormant** — origin is the #361 Firebase slice, reader is the connect-time re-registration sibling that calls [`RemoteConversationRepository.registerPushToken`](remote-conversation-repository.md) ([#359](../codebase/359.md)); never logged — merged)
- [#1102](https://github.com/pyrycode/pyrycode-mobile/issues/1102) (second writer — [`PushTokenRefresher`](push-messaging-service.md) asks FCM for the current token itself at start and on each foreground while this key reads empty, fixing a live-gate flake where `onNewToken` alone left a fresh install unreachable by push until FCM's own retry succeeded — merged)
- Phase 3+: remaining `Settings` preferences and notification *delivery* (Phase 4) will land as additional keys here (or as sibling classes once the Notifications group grows past one key — see the splitting rule above; the first-non-defaults-key trigger already fired at #268 without prompting a split).
- [#686](https://github.com/pyrycode/pyrycode-mobile/issues/686) (mobile port of desktop [#1549](https://github.com/pyrycode/pyrycode-desktop/issues/1549) / PR #1554 — `rememberedEffort: Flow<String?>` + `setRememberedEffort` setter + `stringPreferencesKey("remembered_effort")`, the **second key with no fallback default** after `pushToken`; read and written only through `AppPreferences.asRememberedEffortStore()` and the `EffortRecall` collaborator, never directly by `ThreadViewModel` or Settings; see [Thread composer footer — remembered effort recall](thread-composer-footer-effort-recall.md#remembered-effort-recall-686) for the once-per-opening decision — merged)
