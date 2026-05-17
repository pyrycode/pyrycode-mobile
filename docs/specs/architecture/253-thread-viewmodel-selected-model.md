# 253 — ThreadViewModel selectedModel state plumbing

## Context

Phase 0 currently stubs the model on `ThreadUiState` as a constant `String = "Opus 4.7"` populated from `ThreadViewModel.companion.STUB_MODEL` (`ThreadViewModel.kt:28, 109`). The sibling slice #254 ships the user-visible Status Sheet (M3 `ModalBottomSheet` with a Model-section radio group) and needs:

1. A typed `Model` on `ThreadUiState` so the radio group can compare against an enum, not a label string.
2. Initial value sourced from `AppPreferences.defaultModel` (already shipped — `AppPreferences.kt:39`).
3. A per-conversation override that does **not** mutate the Settings default (per-thread selection is in-memory only this slice; Phase 4 will persist it).

This slice lands the state-plumbing only. No user-visible change: the status row still renders "Opus 4.7" by default across light, dark, and above-delimiter-dim previews. `ThreadStatusRow`'s `model: String` parameter shape is preserved; the screen derives the label from `state.selectedModel`.

## Design source

N/A — state-plumbing slice; no user-visible change. The UI surface lands in the sibling slice (#254).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:20-113` — current `ThreadUiState` data class, `ThreadViewModel` `combine`-based state production, and the `STUB_MODEL` companion constant to replace.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:60-71` — the single `state.model` read site inside the bottom-bar `ThreadStatusRow` call; only consumer of the field.
- `app/src/main/java/de/pyryco/mobile/data/preferences/Model.kt:1-3` — the enum (`OPUS_4_7`, `SONNET_4_6`, `HAIKU_4_5`). Add the `Model.label()` extension here.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:39-47` — the `defaultModel: Flow<Model>` and `setDefaultModel(...)` you'll consume; note the unparseable-stored-value fallback to `Model.OPUS_4_7` is already built in.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:29-37` — `single { AppPreferences(get()) }` is already exposed; the `ThreadViewModel` Koin binding gains one extra `get()`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:38-179, 399-458` — existing setup, the two stub assertions to migrate (lines 172-192), `makeVm` helper to extend, and the `fixedRepo` test double pattern.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt:39-66` — **the canonical pattern** for injecting a real-DataStore-backed `AppPreferences` into a unit test (`TemporaryFolder` + `PreferenceDataStoreFactory.create`). Adopt this verbatim shape in `ThreadViewModelTest`.
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt:22-44` — the alternative `CoroutineScope(Dispatchers.IO + Job())` setup, useful for the non-`runTest` initial-value tests in this file.
- `CLAUDE.md` — MVI conventions: stateless composables, sealed types, single `StateFlow<UiState>` per ViewModel, test-first.

## Design

### `ThreadUiState`

Rename `val model: String = "Opus 4.7"` → `val selectedModel: Model = Model.OPUS_4_7`. Add the `Model` import. All other fields unchanged. Data-class defaults (`Model.OPUS_4_7`) provide the initial value the `stateIn(WhileSubscribed)` initial state inherits — no preview-site updates needed (none pass `model = ...` explicitly today).

### `ThreadViewModel`

Add `AppPreferences` as a fourth constructor parameter, appended last to minimize blast radius on the test helper:

```kotlin
class ThreadViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConversationRepository,
    private val connectionStateSource: ConnectionStateSource,
    private val appPreferences: AppPreferences,
) : ViewModel()
```

Add a private override `MutableStateFlow<Model?>` named `modelOverride`, initialized to `null` (`null` ⇒ "use the Settings default"):

```kotlin
private val modelOverride = MutableStateFlow<Model?>(null)
```

Add a derived selected-model flow that prefers override over default. Pre-combining keeps the existing 3-arg `combine { conversations, items, pickerVisible -> ... }` block from growing to 5 args:

```kotlin
private val selectedModelFlow: Flow<Model> =
    combine(appPreferences.defaultModel, modelOverride) { default, override -> override ?: default }
```

Extend the main `combine` from 3 to 4 sources, adding `selectedModelFlow`. Replace `model = STUB_MODEL` with `selectedModel = selectedModel` inside the `ThreadUiState(...)` constructor (rename the local lambda parameter accordingly).

Drop the `STUB_MODEL` companion constant. **Leave `STUB_EFFORT` and `STUB_TOKEN_PERCENT` untouched** — out of scope per ticket.

Expose the override mutation:

```kotlin
fun onModelSelected(model: Model) { modelOverride.value = model }
```

`viewModelScope.launch` is **not** needed — the only side effect is a synchronous `StateFlow.value` write. No `appPreferences.setDefaultModel(...)` call — that's the whole point of "does not mutate Settings".

### Fallback semantics

The AC says "falling back to `Model.OPUS_4_7` if the flow has not yet emitted". This is satisfied by two layers already in place:

1. `ThreadUiState`'s data-class default (`selectedModel: Model = Model.OPUS_4_7`) feeds the `stateIn` `initialValue` constructor, so any subscriber that reads `state.value` before the combine has produced its first emission sees `OPUS_4_7`.
2. `appPreferences.defaultModel` itself maps unparseable/missing stored values to `Model.OPUS_4_7` (`AppPreferences.kt:42`), so the first emission of the combine pipeline never produces `null`.

No additional `onStart { emit(OPUS_4_7) }` is needed.

### `ThreadScreen`

Single read site at `ThreadScreen.kt:64`: change `model = state.model` to `model = state.selectedModel.label()`. `ThreadStatusRow`'s `model: String` parameter is unchanged.

### `Model.label()` extension

Add a top-level extension co-located with the enum:

```kotlin
fun Model.label(): String = when (this) {
    Model.OPUS_4_7 -> "Opus 4.7"
    Model.SONNET_4_6 -> "Sonnet 4.6"
    Model.HAIKU_4_5 -> "Haiku 4.5"
}
```

**Rationale:** the sibling slice #254 ships a radio group that needs per-option labels; a private helper in `ThreadScreen.kt` would force #254 to duplicate the mapping (or extract it then). Co-locating with the enum once at near-zero surface cost (4 LOC + signature) is the cheaper of the two paths the ticket leaves open. This does not violate "do not introduce a shared util" — it's an enum-local extension, not a new util layer. The pre-existing hardcoded `"Opus 4.7"` in `SettingsScreen.kt:155` is **not** migrated here (out of scope).

### Koin wiring

`di/AppModule.kt:36` — add one `get()` for `AppPreferences`:

```kotlin
viewModel { ThreadViewModel(get(), get(), get(), get()) }
```

The module already exposes `single { AppPreferences(get()) }` at line 29; no new binding needed.

## State + concurrency model

- **Hot/cold:** `selectedModelFlow` is cold (built from `combine` of an upstream cold `Flow<Model>` and a hot `MutableStateFlow<Model?>`). It is folded into the existing `state: StateFlow<ThreadUiState>` via `stateIn(WhileSubscribed(5_000))` — same lifetime semantics as today.
- **Dispatcher:** no change. `appPreferences.defaultModel` collection happens on whatever dispatcher `viewModelScope` provides (Main by default). DataStore reads occur on its own internal IO scope.
- **Shutdown:** the existing `WhileSubscribed(5_000)` covers tear-down. `modelOverride` is a plain `MutableStateFlow` owned by the ViewModel — GC'd when the ViewModel is cleared. No `onCleared()` override needed.
- **Override lifetime:** as specified in the ticket — destroyed with the ViewModel. Configuration change (`Activity` recreate) re-creates the ViewModel via Koin and re-reads `appPreferences.defaultModel`; the override is lost. That's the documented Phase 0 behavior; Phase 4 will persist.

## Error handling

No new failure modes. `appPreferences.defaultModel` is already nullable-safe (falls back to `OPUS_4_7` on unparseable stored values per `AppPreferences.kt:42`). DataStore IO errors during reads propagate as flow errors; the existing `combine` does not `catch {}` them today, and this slice does not change that posture (out of scope — would be a behavior change to address across all `ThreadViewModel` flows at once).

## Testing strategy

Unit tests only (`./gradlew test`). No instrumented tests needed — no Compose-rendering change, no resource lookup.

### Test fixture setup

Adopt the `ChannelListViewModelTest` pattern verbatim in `ThreadViewModelTest`. Add:

- `@get:Rule val tmp = TemporaryFolder()` (already a JUnit dependency in the test classpath — see `AppPreferencesTest`).
- A `CoroutineScope(Dispatchers.IO + Job())` set up in `@Before` and cancelled in `@After`, used as the DataStore scope. This shape (vs `TestScope.backgroundScope`) supports both the runTest tests and the four non-runTest initial-value tests in this file.
- A private `newDataStore(): DataStore<Preferences>` helper that calls `PreferenceDataStoreFactory.create(scope = prefsScope, produceFile = { tmp.newFile("app_prefs.preferences_pb") })`. Each call produces a unique file — safe to invoke multiple times per test.
- Extend the `makeVm` helper with a fourth parameter `appPreferences: AppPreferences = AppPreferences(newDataStore())` and forward it to the `ThreadViewModel` constructor.

### Existing tests to update

- `state_initialValue_includesStubModelEffortAndTokenPercentDefaults` (line 172) — assert `Model.OPUS_4_7 == vm.state.value.selectedModel` instead of `"Opus 4.7" == vm.state.value.model`. Rename test → `state_initialValue_includesDefaultModelEffortAndTokenPercentDefaults` (or similar) since "stub" no longer applies to model. `effort` and `tokenPercent` assertions unchanged.
- `state_postSubscription_emitsStubModelEffortAndTokenPercent` (line 182) — same migration: `Model.OPUS_4_7 == vm.state.value.selectedModel`. Rename test.

### New tests to add (bulleted scenarios — developer writes them in the existing JUnit 4 idiom)

- **`selectedModel_followsAppPreferencesDefault`** — given an `AppPreferences` with `setDefaultModel(Model.SONNET_4_6)` called before VM construction, after subscribing and `advanceUntilIdle`, assert `vm.state.value.selectedModel == Model.SONNET_4_6`.
- **`selectedModel_reemitsWhenAppPreferencesDefaultChanges`** — subscribe, assert default `OPUS_4_7`, call `prefs.setDefaultModel(Model.HAIKU_4_5)`, `advanceUntilIdle`, assert `selectedModel == Model.HAIKU_4_5`.
- **`onModelSelected_overridesPerConversationWithoutMutatingPreferences`** — given `appPreferences.defaultModel.first() == Model.OPUS_4_7`, subscribe, call `vm.onModelSelected(Model.HAIKU_4_5)`, `advanceUntilIdle`, assert `vm.state.value.selectedModel == Model.HAIKU_4_5` **and** `appPreferences.defaultModel.first() == Model.OPUS_4_7` (unchanged). This is the AC's explicit verification line.
- **`onModelSelected_overrideWinsOverSubsequentDefaultChange`** — call `onModelSelected(Model.HAIKU_4_5)`, then `prefs.setDefaultModel(Model.SONNET_4_6)`, `advanceUntilIdle`, assert `selectedModel == Model.HAIKU_4_5` (override sticks).

The four new tests + the two updates total ~6 test functions, ~60-80 LOC of test code including the `TemporaryFolder` and prefs-scope setup.

### Out of scope for tests

- No test for `Model.label()` mapping — the screen integration is covered transitively by the existing preview-driven render check (and #254 lands the Status Sheet tests that exercise label mapping more thoroughly).
- No reset-to-default path (the ticket's `onModelSelected(model: Model)` signature is non-nullable; setting `modelOverride.value = null` from outside the VM is not exposed this slice).

## Open questions

- **`Model.label()` location.** Spec recommends the enum file. If the developer finds an idiomatic reason to keep it private in `ThreadScreen.kt` (e.g. a project convention surfaced in adjacent code that this spec missed), document the choice in the PR description and the next slice (#254) will need to extract it. The architect's bias: one-time placement next to the enum is cheaper than two private helpers across two slices.
- **Race: override set before first `defaultModel` emission.** `combine` does not emit until **all** upstream flows have produced at least one value. `MutableStateFlow<Model?>(null)` already has a value, and `appPreferences.defaultModel` emits its stored-or-default value on first collection. So the combine pipeline produces its first emission as soon as DataStore yields — no extra synchronization needed. The non-subscribed `state.value` read sees the data-class default `OPUS_4_7` via `stateIn.initialValue`.
