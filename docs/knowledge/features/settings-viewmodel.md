# SettingsViewModel

Thin ViewModel for the Settings screen. Exposes persisted preferences (`themeMode`, `useWallpaperColors`, `defaultModel`, `defaultEffort`) as hot `StateFlow`s and matching fire-and-forget setter callbacks, plus (since #164) a derived `archivedDiscussionCount` projection over `ConversationRepository`. Introduced in #87 (Theme row); extended in #89 (Use-wallpaper-colors switch), #164 (archived-discussion count for the Storage row), #232 (Default-model picker), and #233 (Default-effort picker).

## What it does

- Reads `AppPreferences.themeMode` and exposes it as `themeMode: StateFlow<ThemeMode>`.
- Forwards `fun onSelectTheme(mode: ThemeMode)` into `AppPreferences.setThemeMode(mode)` via `viewModelScope.launch { … }`.
- Reads `AppPreferences.useWallpaperColors` and exposes it as `useWallpaperColors: StateFlow<Boolean>` (#89).
- Forwards `fun onToggleUseWallpaperColors(enabled: Boolean)` into `AppPreferences.setUseWallpaperColors(enabled)` via `viewModelScope.launch { … }` (#89).
- Reads `AppPreferences.defaultModel` and exposes it as `defaultModel: StateFlow<Model>` (#232). `initialValue = Model.OPUS_4_7` matches the upstream's fallback when no value is persisted (#231).
- Forwards `fun onSelectDefaultModel(model: Model)` into `AppPreferences.setDefaultModel(model)` via `viewModelScope.launch { … }` (#232).
- Reads `AppPreferences.defaultEffort` and exposes it as `defaultEffort: StateFlow<Effort>` (#233). `initialValue = Effort.HIGH` matches the upstream's fallback when no value is persisted (#231).
- Forwards `fun onSelectDefaultEffort(effort: Effort)` into `AppPreferences.setDefaultEffort(effort)` via `viewModelScope.launch { … }` (#233).
- Reads `ConversationRepository.observeConversations(ConversationFilter.Archived)`, maps it to `count { !it.isPromoted }`, and exposes the result as `archivedDiscussionCount: StateFlow<Int>` (#164). Mirrors `ArchivedDiscussionsViewModel`'s filter exactly so the Settings Storage row's "N archived" supporting text and the destination [Archived Discussions screen](archived-discussions-screen.md)'s list never disagree.

Four parallel `(StateFlow<X>, fun onSelectX(...))` pairs plus one read-only projection. No sealed `SettingsState` / `SettingsEvent` envelope yet — the threshold-crossing decision was deliberately deferred at #233 with #232 in flight; with #232 now landed, the lift trigger is fully cleared and the next defaults consumer is the re-evaluation point (see § Edge cases below).

## How it works

```kotlin
class SettingsViewModel(
    private val appPreferences: AppPreferences,
    conversationRepository: ConversationRepository,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> =
        appPreferences.themeMode.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ThemeMode.SYSTEM,
        )

    val useWallpaperColors: StateFlow<Boolean> =
        appPreferences.useWallpaperColors.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = false,
        )

    val defaultModel: StateFlow<Model> =
        appPreferences.defaultModel.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = Model.OPUS_4_7,
        )

    val defaultEffort: StateFlow<Effort> =
        appPreferences.defaultEffort.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = Effort.HIGH,
        )

    val archivedDiscussionCount: StateFlow<Int> =
        conversationRepository
            .observeConversations(ConversationFilter.Archived)
            .map { conversations -> conversations.count { !it.isPromoted } }
            .catch { emit(0) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = 0,
            )

    fun onSelectTheme(mode: ThemeMode) {
        viewModelScope.launch { appPreferences.setThemeMode(mode) }
    }

    fun onToggleUseWallpaperColors(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setUseWallpaperColors(enabled) }
    }

    fun onSelectDefaultModel(model: Model) {
        viewModelScope.launch { appPreferences.setDefaultModel(model) }
    }

    fun onSelectDefaultEffort(effort: Effort) {
        viewModelScope.launch { appPreferences.setDefaultEffort(effort) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
```

- **`stateIn(WhileSubscribed(5_000L), initial = <neutral>)`** — same idiom as [`ChannelListViewModel`](channel-list-viewmodel.md) and [`DiscussionListViewModel`](discussion-list-viewmodel.md). The 5 s grace period keeps the upstream subscription alive across config changes (rotation, dark-mode toggle) without holding it open after the screen leaves the back stack. All five flows pick the value the cold upstream would emit first as their `initialValue` (`ThemeMode.SYSTEM` for `themeMode`, `false` for `useWallpaperColors`, `Model.OPUS_4_7` for `defaultModel` — matches `AppPreferences.defaultModel`'s tolerant-unknown fallback from #231, `Effort.HIGH` for `defaultEffort` — same shape, `0` for `archivedDiscussionCount` — which also happens to be Figma 17:2's "0 archived" empty-state literal) — same value, no first-frame flash. `STOP_TIMEOUT_MILLIS = 5_000L` is the single tuning knob for all five projections.
- **`conversationRepository` is a constructor parameter, not a `private val`.** Only the field-initializer for `archivedDiscussionCount` reads it once; promoting it to a property would advertise an instance dependency that doesn't exist. If a future event handler ever needs to call `conversationRepository.archive(...)` from this VM, promote then — see [`../codebase/164.md`](../codebase/164.md) § Patterns established.
- **`.catch { emit(0) }` upstream of `stateIn` (archived count only).** Deliberate divergence from `ArchivedDiscussionsViewModel`, which surfaces `Error(message)` for the same upstream because the list IS its screen's primary content. Settings' supporting text is read-only metadata about that other screen, so an upstream throw collapses to `"0 archived"` rather than tearing down the flow. The rule the two consumers now demonstrate: **supportive-metadata projections swallow upstream errors; primary-content projections surface them.** Future supportive-metadata flows (count badges, last-updated timestamps, "N pending" lines) should follow.
- **Fire-and-forget writes.** None of `onSelectTheme`, `onToggleUseWallpaperColors`, `onSelectDefaultModel`, `onSelectDefaultEffort` `await`s the `edit { … }` or surfaces a result. The persisted value re-emits through the upstream `Flow` after the setter returns; both this VM's projection and (for `themeMode` / `useWallpaperColors`) the root-level `MainActivity` collector that drives `PyrycodeMobileTheme(darkTheme = …, dynamicColor = …)` pick up the change. `defaultModel` has [`ThreadViewModel.selectedModelFlow`](thread-screen.md) as a second consumer since #253 (StatusSheet model section), so an `onSelectDefaultModel` write fans out to both this VM's projection and the StatusSheet's current-model display on every open conversation. `defaultEffort`'s second consumer is `ThreadViewModel.selectedEffortFlow` (#229) via the StatusSheet `FilterChip` row. Same fire-and-forget shape as `MainActivity.kt`'s `setPairedServerExists` call from #12 — see [App preferences § Edge cases](app-preferences.md) for why no `Result`-returning write API exists today.

## Configuration / usage

Registered once in the Koin module:

```kotlin
// di/AppModule.kt
viewModel { SettingsViewModel(get(), get()) }
```

The two `get()` calls resolve to the existing `AppPreferences` and `ConversationRepository` bindings (the latter is the same `FakeConversationRepository` instance `ChannelListViewModel` / `DiscussionListViewModel` / `ArchivedDiscussionsViewModel` share).

Consumed once at the Settings NavHost destination:

```kotlin
composable(Routes.SETTINGS) {
    val vm = koinViewModel<SettingsViewModel>()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val useWallpaperColors by vm.useWallpaperColors.collectAsStateWithLifecycle()
    val archivedDiscussionCount by vm.archivedDiscussionCount.collectAsStateWithLifecycle()
    val defaultModel by vm.defaultModel.collectAsStateWithLifecycle()
    val defaultEffort by vm.defaultEffort.collectAsStateWithLifecycle()
    SettingsScreen(
        themeMode = themeMode,
        useWallpaperColors = useWallpaperColors,
        archivedDiscussionCount = archivedDiscussionCount,
        defaultModel = defaultModel,
        defaultEffort = defaultEffort,
        onSelectTheme = vm::onSelectTheme,
        onToggleUseWallpaperColors = vm::onToggleUseWallpaperColors,
        onSelectDefaultModel = vm::onSelectDefaultModel,
        onSelectDefaultEffort = vm::onSelectDefaultEffort,
        onBack = { navController.popBackStack() },
        onOpenArchivedDiscussions = { navController.navigate(Routes.ARCHIVED_DISCUSSIONS) },
    )
}
```

Note the no-arg `collectAsStateWithLifecycle()` calls — all five `vm.*` projections are already `StateFlow`s, so the overload picks up `StateFlow.value` as initial. Compare with the root-level `appPreferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)` / `appPreferences.useWallpaperColors.collectAsStateWithLifecycle(initialValue = false)` in `MainActivity`'s `setContent` block, which collect the *cold* flows and must supply same-shaped neutral defaults. The composition-root collectors are independent of this VM's projections — DataStore broadcasts to every subscriber, so one `setUseWallpaperColors(...)` write reaches both the global theme repaint and this VM's `useWallpaperColors.value` from a single upstream emission. The `archivedDiscussionCount` flow has no composition-root sibling — it's Settings-screen-local. `defaultModel` and `defaultEffort` each have a second consumer in `ThreadViewModel` (StatusSheet model + effort sections) since #253 / #229 respectively; the new-conversation materialiser will eventually become a third consumer of both.

## Edge cases / limitations

- **No write-failure surface.** `setThemeMode` / `setUseWallpaperColors` / `setDefaultModel` / `setDefaultEffort` can each in principle throw `IOException` from DataStore; this VM does not catch or surface that. If a write ever fails in the field, the persisted value stays at its prior value, the upstream re-emits the prior value, and the dialog closes either way. Adding a snackbar would mean adding a `Result`-returning write API on `AppPreferences` — out of scope until an actual failure mode is observed (see [evidence-based fix selection](app-preferences.md)).
- **Archived-count read-failure swallowed silently.** `.catch { emit(0) }` on the upstream — if the repository's `Archived` stream ever throws, the row reads `"0 archived"` and `[Archived Discussions screen](archived-discussions-screen.md)` becomes the surface that owns the error UI. Tested via `MutableSharedFlow` source emit + cancel-then-throw scenarios isn't necessary; the `.catch` is one line and the AC explicitly accepts "0 archived" as the empty/error display.
- **Four-key surface (writable preferences) — the "lift to sealed `SettingsState`" threshold is fully cleared.** #87 set the rule that the MVI envelope arrives on the *third real persisted preference*. The count was one after #87 (`themeMode`), two after #89 (`+useWallpaperColors`), three after #233 (`+defaultEffort`, deferred), and is four after #232 (`+defaultModel`). The lift was deliberately deferred at #233 because the sibling #232 (model picker) was in flight at architect time and lifting under #233 would force #232 to either rebase onto a new envelope or land its own mechanical-copy pair and rebase later. With #232 now landed, **both siblings are merged and there's no remaining in-flight defaults slice to amortise against — the next defaults consumer (YOLO switch row or workspace picker, whichever lands first) is the unambiguous re-evaluation point.** That ticket's architect should weigh: lift the four existing pairs into a sealed `SettingsState` / `SettingsEvent` envelope before adding the fifth, or accept the drift and continue the mechanical-copy pattern. See [`../codebase/232.md`](../codebase/232.md) and [`../codebase/233.md`](../codebase/233.md) § Lessons learned for the deferral trail. The `archivedDiscussionCount` projection from #164 is read-only and derived from the data layer, so it does not count toward the threshold.

## Testing

`app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` — 25 methods since #232. Uses the `AppPreferencesTest` rig (real `PreferenceDataStoreFactory` over `TemporaryFolder` + real `AppPreferences`, no fake) plus `Dispatchers.setMain(UnconfinedTestDispatcher())` + `runTest(dispatcher) { … advanceUntilIdle() … }` from [`ChannelListViewModelTest`](channel-list-viewmodel.md). Since #164 a hand-rolled `ConversationRepository` stub backed by a `MutableSharedFlow<List<Conversation>>(replay = 0)` drives the archived-count scenarios; the prior nine theme/wallpaper tests + five `defaultEffort` tests (#233) + five `defaultModel` tests (#232) stay one-arg through a `makeVm(prefs, repo = stubRepo()) = SettingsViewModel(prefs, repo)` helper.

Coverage:

- `initialState_emitsSystem_whenNoStoredValue` — fresh datastore.
- `initialState_mirrorsPersistedValue` — pre-write `DARK`, construct VM, assert `vm.themeMode.value == DARK`.
- `onSelectTheme_persistsLight` / `_persistsDark` / `_persistsSystem` — round-trip each `ThemeMode` (the SYSTEM case starts from a pre-written DARK to exercise the round-trip-from-non-default path).
- `themeMode_flowReEmits_afterOnSelectTheme` — two consecutive `onSelectTheme` calls, assert `vm.themeMode.value` reaches each in turn.
- `useWallpaperColors_initialState_emitsFalse_whenNoStoredValue` — fresh datastore boolean default (#89).
- `useWallpaperColors_initialState_mirrorsPersistedTrue` — pre-write `true`, construct VM, assert `vm.useWallpaperColors.value == true` (#89).
- `onToggleUseWallpaperColors_persistsAndFlowReEmits` — `false → true → false` walk, asserting both `prefs.useWallpaperColors.first()` (persistence) and `vm.useWallpaperColors.value` (re-emission) on each step (#89). Single test covers both AC clauses ("persists via the fake" + "flow re-emits after persistence") in one body — same compression rationale as #88's round-trip-plus-re-emit collapse.
- `defaultModel_initialState_emitsOpus47_whenNoStoredValue` — fresh datastore, collector + `advanceUntilIdle`, `value == Model.OPUS_4_7` (#232).
- `defaultModel_initialState_mirrorsPersistedValue` — pre-write `Model.HAIKU_4_5`, construct VM, assert `vm.defaultModel.value == Model.HAIKU_4_5` (#232).
- `onSelectDefaultModel_persistsSonnet46` / `_persistsHaiku45` — round-trip the two non-default variants via `vm.onSelectDefaultModel(...)` + `prefs.defaultModel.first()` (the third variant `OPUS_4_7` is covered transitively by the fresh-datastore default test) (#232).
- `defaultModel_flowReEmits_afterOnSelectDefaultModel` — two consecutive `onSelectDefaultModel` calls (`SONNET_4_6` → `HAIKU_4_5`), assert `vm.defaultModel.value` reaches each in turn. Gates AC #5's "exposed flow re-emits after persistence" (#232).
- `defaultEffort_initialState_emitsHigh_whenNoStoredValue` — fresh datastore, collector + `advanceUntilIdle`, `value == Effort.HIGH` (#233).
- `defaultEffort_initialState_mirrorsPersistedValue` — pre-write `LOW`, construct VM, assert `vm.defaultEffort.value == Effort.LOW` (#233).
- `onSelectDefaultEffort_persistsLow` / `_persistsMax` — round-trip both `Effort.entries` boundaries (catches off-by-one in the enum lookup); middle values stay uncovered because they ride the same `setDefaultEffort` round-trip the boundaries already exercise (#233).
- `defaultEffort_flowReEmits_afterOnSelectDefaultEffort` — two consecutive `onSelectDefaultEffort` calls (`LOW` → `XHIGH`), assert `vm.defaultEffort.value` reaches each in turn. Gates AC #5's "exposed flow re-emits after persistence" (#233).
- `archivedDiscussionCount_initialValue_isZero` — fresh VM, no source emission; collector launched + `advanceUntilIdle`; `value == 0` (#164).
- `archivedDiscussionCount_reflectsArchivedUnpromotedConversations` — emit three `archivedDiscussion(...)` fixtures; `value == 3` (#164).
- `archivedDiscussionCount_excludesPromotedArchivedConversations` — emit `[archivedDiscussion("disc-1"), archivedChannel("chan-1"), archivedChannel("chan-2")]`; `value == 1` (locks in the `!isPromoted` post-filter, #164).
- `archivedDiscussionCount_updates_whenConversationArchived` — emit `emptyList()` → 0, then emit `[archivedDiscussion]` → 1 (#164).
- `archivedDiscussionCount_updates_whenConversationUnarchived` — emit `[archivedDiscussion]` → 1, then emit `emptyList()` → 0 (#164).
- `archivedDiscussionCount_passesArchivedFilter` — `stubRepo(source, captureFiltersInto = captured)` records every `ConversationFilter` value the VM passes to `observeConversations`; assert `captured == [ConversationFilter.Archived]` (#164). The hand-rolled stub exists primarily for this assertion — the real `FakeConversationRepository` would require either internal inspection or contrived count differentials to verify the filter value.

Tests that read `vm.themeMode.value` / `vm.useWallpaperColors.value` / `vm.defaultModel.value` / `vm.defaultEffort.value` / `vm.archivedDiscussionCount.value` `launch` a no-op collector and `advanceUntilIdle()` first to keep the `WhileSubscribed(5_000L)` upstream subscribed; otherwise `.value` stays at the `initialValue` literal (`ThemeMode.SYSTEM` / `false` / `Model.OPUS_4_7` / `Effort.HIGH` / `0`) and the test asserts the wrong thing. Tests that read through `prefs.themeMode.first()` / `prefs.useWallpaperColors.first()` / `prefs.defaultModel.first()` / `prefs.defaultEffort.first()` directly don't need the collector — they bypass the VM's `stateIn` projection.

## Related

- Spec: `docs/specs/architecture/87-settings-theme-picker-dialog.md`; `docs/specs/architecture/89-settings-use-wallpaper-colors-switch.md`; `docs/specs/architecture/164-settings-archived-discussion-live-count.md`; `docs/specs/architecture/232-settings-default-model-picker-dialog.md`; `docs/specs/architecture/233-settings-default-effort-picker-dialog.md`
- Ticket notes: [`../codebase/87.md`](../codebase/87.md), [`../codebase/89.md`](../codebase/89.md), [`../codebase/164.md`](../codebase/164.md), [`../codebase/232.md`](../codebase/232.md), [`../codebase/233.md`](../codebase/233.md)
- Sibling VMs: [ChannelListViewModel](channel-list-viewmodel.md), [DiscussionListViewModel](discussion-list-viewmodel.md)
- Sibling consumer of the archived stream: [ArchivedDiscussionsViewModel](archived-discussions-screen.md) — primary-content surface (surfaces `Error(message)`); this VM is the supportive-metadata sibling that swallows the same upstream's errors via `.catch { emit(0) }`
- Backing preferences: [App preferences](app-preferences.md) — `themeMode` flow + `setThemeMode` setter; `useWallpaperColors` flow + `setUseWallpaperColors` setter (#89's write site); `defaultModel` flow + `setDefaultModel` setter (#231 schema, #232 first writer); `defaultEffort` flow + `setDefaultEffort` setter (#231 schema, #233 first consumer)
- Backing repository: [Conversation repository](conversation-repository.md) — `observeConversations(ConversationFilter.Archived)` (#93)
- Consumer screen: [Settings screen](settings-screen.md) — Theme row + `ThemePickerDialog`; Use-wallpaper-colors switch row (#89); Storage row "N archived" supporting line (#164); Default-model row + `ModelPickerDialog` (#232); Default-effort row + `EffortPickerDialog` (#233)
- Sibling root collectors: `MainActivity.setContent { … appPreferences.themeMode.collectAsStateWithLifecycle(...) ; appPreferences.useWallpaperColors.collectAsStateWithLifecycle(...) }` from #86 + #88 — drive `PyrycodeMobileTheme(darkTheme = …, dynamicColor = …)` from the same upstream flows this VM reads, so a single `setThemeMode` / `setUseWallpaperColors` write fans out to both the global theme and the Settings row. `archivedDiscussionCount` has no composition-root sibling — it's Settings-screen-local.
