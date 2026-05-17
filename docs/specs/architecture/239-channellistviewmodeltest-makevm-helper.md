# Spec: Introduce `makeVm` helper in `ChannelListViewModelTest` (#239)

Issue: [#239](https://github.com/pyrycode/pyrycode-mobile/issues/239)
Split from: #236 (parent that would have added an `AppPreferences`-derived constructor dep to `ChannelListViewModel`; this slice is pure test-infra so the next ticket can change one helper line instead of 18 call sites).

## Context

`ChannelListViewModelTest` currently constructs `ChannelListViewModel` directly at 18 sites. Future tickets adding constructor deps to the VM (the very next one wires `AppPreferences.defaultWorkspace` into the short-press FAB; other plausible Phase-1 candidates pull `defaultModel` / `defaultYolo` from `AppPreferences`) would inherit an 18-site edit cascade — tripping the architect's >10 consumer-call-site red line and forcing a split anyway.

This ticket front-loads that split: introduce a private `makeVm(...)` helper plus the `AppPreferences` test rig already used by `SettingsViewModelTest`, migrate the 18 sites, and stop. **Production code is not modified.** `ChannelListViewModel`'s constructor is unchanged at the end of this ticket; the helper *internally* builds an `AppPreferences` that the future VM dep will consume, but the VM does not see it yet.

The helper is the seam, not the VM. When the subsequent ticket adds the new constructor parameter, the diff is "one parameter on `ChannelListViewModel`, one parameter on `makeVm`" — not an 18-site cascade.

## Design source

N/A — pure test-infra refactor. No production code, no UI surface change.

## Files to read first

- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt` — the file being refactored. All 18 direct-construction sites and both private stub helpers (`stubRepo`, `erroringRepo`) live here. Read top-to-bottom before editing; the migration is purely intra-file.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt:36-62` — the canonical `TemporaryFolder` + `dispatcher` + `newDataStore()` + `makeVm(...)` pattern that this ticket lifts verbatim. Match its shape (`@get:Rule val tmp = TemporaryFolder()`, `private val dispatcher = UnconfinedTestDispatcher()`, `private fun TestScope.newDataStore(): DataStore<Preferences>` returning a `PreferenceDataStoreFactory.create(scope = backgroundScope, …)`).
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:12-14` — the single-arg `AppPreferences(dataStore: DataStore<Preferences>)` constructor that the helper instantiates internally.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:54-56` — the current single-arg `ChannelListViewModel(repository)` constructor. After this ticket it is still single-arg; do not modify it.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt:64-73` — concrete example of a `runTest(dispatcher) { … }` test that constructs `AppPreferences(newDataStore())` inline, used as the shape `makeVm`'s callers will adopt at each migration site.

## Design

### Test-class structure (after refactor)

```
class ChannelListViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()

    // @Before / @After setUpMainDispatcher / tearDownMainDispatcher
    //   — switched to use `dispatcher` field (not a fresh UnconfinedTestDispatcher())

    private fun TestScope.newDataStore(): DataStore<Preferences> = …   // lift verbatim from SettingsViewModelTest

    private fun TestScope.makeVm(
        repository: ConversationRepository,
        prefs: AppPreferences = AppPreferences(newDataStore()),
    ): ChannelListViewModel = …

    // @Test methods — each runTest { … } body still constructs its own repo
    //   (stubRepo / erroringRepo / FakeConversationRepository) inline,
    //   then writes  val vm = makeVm(repo)  in place of the old
    //   `val vm = ChannelListViewModel(repo)`.

    // existing private helpers (stubRepo, erroringRepo, sampleChannel, sampleDiscussion)
    //   remain unchanged.
}
```

### `makeVm` contract

Signature:

```kotlin
private fun TestScope.makeVm(
    repository: ConversationRepository,
    prefs: AppPreferences = AppPreferences(newDataStore()),
): ChannelListViewModel
```

Behavioural requirements:

- Returns a `ChannelListViewModel` constructed with `repository` (and only `repository` — the VM constructor is unchanged by this ticket; `prefs` is *built and held by the helper* but **not** passed to the VM yet).
- Every call site that doesn't care about prefs writes `val vm = makeVm(repo)` — the `prefs` default fires and builds a per-test `AppPreferences` over a fresh `TemporaryFolder`-backed DataStore.
- Tests that need a pre-seeded pref would write `val vm = makeVm(repo, prefs = AppPreferences(newDataStore()).also { it.setX(...); advanceUntilIdle() })`. **No existing test in this ticket exercises that override** — all 18 existing tests use the default. The override exists as the future-proofing seam.
- The helper is a `TestScope` extension (matches `SettingsViewModelTest`'s `newDataStore()` shape) so it can call `newDataStore()`, which itself uses `backgroundScope` from the surrounding `runTest(dispatcher) { … }`.

### Why the helper builds an `AppPreferences` even though the VM doesn't take one

The whole point of the ticket. The next ticket adds an `AppPreferences` (or a narrow dep derived from it) to `ChannelListViewModel`'s constructor. The diff in *that* ticket should be:

1. Add the parameter to `ChannelListViewModel(…)`.
2. Pass `prefs` (or a derived value from it) inside `makeVm`'s `ChannelListViewModel(...)` call.
3. Done. Zero call-site touches.

If we don't build the `AppPreferences` here, the next ticket re-does the 18-site cascade to thread a `prefs` argument through. Building it here — unused on the VM but ready in the helper — is the cheap seam.

### Migration mechanics

Three patterns in the existing 18 sites:

| Existing | After |
|---|---|
| `val vm = ChannelListViewModel(stubRepo(channels, discussions))` | `val vm = makeVm(stubRepo(channels, discussions))` |
| `val vm = ChannelListViewModel(erroringRepo("…", throwOn = …))` | `val vm = makeVm(erroringRepo("…", throwOn = …))` |
| `val vm = ChannelListViewModel(FakeConversationRepository())` *(or `val vm = ChannelListViewModel(repository)` where `repository` is already a local val)* | `val vm = makeVm(FakeConversationRepository())` / `val vm = makeVm(repository)` |

Mechanical search-replace per site within the same `runTest { … }` block.

### `runTest` switchover

Every existing test uses bare `runTest { … }` (relying on the `@Before`-installed `Main` dispatcher). Switch each to `runTest(dispatcher) { … }` — matching `SettingsViewModelTest`'s shape — so that `TestScope.newDataStore()` (and therefore `TestScope.makeVm(...)`) is in scope. This is the same template `SettingsViewModelTest.kt:64-237` uses; copy it directly.

The `@Before` / `@After` `Dispatchers.setMain(...) / resetMain()` pair is retained, but the dispatcher passed to `setMain` becomes the test-class-level `dispatcher` field (not a freshly-constructed `UnconfinedTestDispatcher()` each `@Before`). Identical behaviour, single source of truth, matches `SettingsViewModelTest:41-51`.

### What does *not* change

- `ChannelListViewModel` source file — untouched.
- The Koin DI module — untouched (ticket body forbids it explicitly).
- `stubRepo`, `erroringRepo`, `sampleChannel`, `sampleDiscussion` private helpers — untouched.
- The semantic content of any test (assertions, ordering, the repository each test uses) — untouched. Only the VM-construction line and the `runTest` parameter change per test.

## State + concurrency model

Test-only file; no production concurrency to design.

The DataStore lifecycle: each test gets its own `TemporaryFolder` (per-test JUnit rule lifecycle), its own `app_prefs.preferences_pb` file inside it, and its own `AppPreferences` instance scoped to that file. The `PreferenceDataStoreFactory.create(scope = backgroundScope, …)` call binds the DataStore to `runTest`'s `backgroundScope` — when the test finishes, `runTest` cancels `backgroundScope`, the DataStore writer coroutine winds down, and `TemporaryFolder.after()` deletes the file. Identical lifecycle to `SettingsViewModelTest`.

`Dispatchers.setMain(dispatcher)` continues to bind `Dispatchers.Main` for `viewModelScope`-launched work inside the VM. No additional dispatcher wiring.

## Error handling

N/A — test-infra change with no runtime error paths.

If `makeVm` is called outside a `runTest(dispatcher) { … }` block, the compile fails because `newDataStore()` is a `TestScope` extension. That's the intended guardrail; no extra defence needed.

## Testing strategy

Unit tests only: `./gradlew test`. No instrumented test changes.

The test suite for this ticket is **the migrated tests themselves**. The pass criterion is: all 18 existing `ChannelListViewModelTest` test methods continue to pass with identical assertions, after migration to `makeVm`. No new test methods are added in this ticket.

Scenarios that must remain green after migration (one bullet per existing test, paraphrased — the developer migrates the test body, not the assertion):

- `initialState_isLoading` — `MutableSharedFlow` channels + discussions, no emission, asserts `Loading`.
- `loaded_whenSourceEmitsNonEmpty` — emit one channel + empty discussions, asserts `Loaded(channels=[sampleChannel], recentDiscussions=[], count=0)`.
- `empty_whenSourceEmitsEmptyList` — emit empty channels + empty discussions, asserts `Empty(recentDiscussions=[], count=0)`.
- `loaded_carriesDiscussionsCount` — emit one channel + three discussions, asserts `Loaded(…, count=3)`.
- `empty_carriesDiscussionsCount` — emit empty channels + two discussions, asserts `Empty(…, count=2)`.
- `discussionsCount_updatesReactively` — emit one channel + one discussion, then emit five discussions; asserts `Loaded(…, recentDiscussions=top-3, count=5)`.
- `loadingPersists_untilBothFlowsEmit` — emit channels only; asserts still `Loading` until discussions also emit.
- `recentDiscussions_isCappedAtThree` — emit four discussions; asserts only top three surface, count=4.
- `recentDiscussions_orderingFollowsUpstream` — upstream emits in non-time-sorted order; asserts VM preserves that order (no re-sort).
- `error_whenChannelsFlowThrows` — channels flow throws; asserts `Error("network down")`.
- `error_whenDiscussionsFlowThrows` — discussions flow throws; asserts `Error("discussions broke")`.
- `error_messageIsNonBlank_whenExceptionMessageIsNull` — null exception message; asserts non-blank fallback.
- `recentDiscussionsTapped_isNoOp` — `RecentDiscussionsTapped` event leaves state unchanged.
- `createDiscussionTapped_createsOneUnpromotedConversation` — uses real `FakeConversationRepository`; asserts a new unpromoted conversation appears.
- `recentDiscussionLastMessages_populatedFromFake_endToEnd` — uses real `FakeConversationRepository`; asserts `recentDiscussionLastMessages["seed-discussion-a"]` populated and `"seed-discussion-b"` absent.
- `createDiscussionTapped_emitsToThreadNavigationWithCreatedId` — uses real `FakeConversationRepository`; asserts a `ToThread` navigation event carrying the new id.
- `longPressFab_setsWorkspacePickerVisibleToTrue` — `LongPressFab` event; asserts `workspacePickerVisible = true`.
- `workspacePicked_createsDiscussionWithPickedWorkspace_emitsNavigation_andClearsVisibility` — `WorkspacePicked` event; asserts new conversation has the picked cwd, navigation emits, picker clears.

Acceptance check the developer runs at the end:

```bash
./gradlew test --tests "de.pyryco.mobile.ui.conversations.list.ChannelListViewModelTest"
```

All 18 tests must pass. Then a wider `./gradlew test` to confirm no neighbour-test regression from the dispatcher-field reshape.

Static check: `grep -n "ChannelListViewModel(" app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt` must return zero hits (the only construction-site form remaining should be `makeVm(...)`). The classname appears in the class declaration and inside the helper return type — fine — but no direct `ChannelListViewModel(...)` constructor invocation should survive.

## Open questions

None. The helper shape is dictated by the `SettingsViewModelTest` precedent (ticket body explicitly says "match its `TemporaryFolder` + `dispatcher` + `newDataStore()` shape"), the migration is mechanical, and the VM constructor is held constant.
